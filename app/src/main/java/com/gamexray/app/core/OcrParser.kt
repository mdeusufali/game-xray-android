package com.gamexray.app.core

import kotlin.math.abs

/**
 * Parses a full positioned OCR frame into structured game data.
 *
 * The parser deliberately prefers "not detected" over a guess. Raw OCR line text is
 * never mutated; a canonical copy is used only for matching.
 */
class OcrParser {

    private val bengaliDigits = charArrayOf('০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯')

    private val currency = "(?:৳|₹|₨|\\$|\\bTK\\b|\\bTAKA\\b|\\bBDT\\b|\\bRS\\.?)"
    private val number = "(\\d[\\d,]*(?:\\.\\d{1,2})?)"
    private val totalKeyword = "(?:PANEL\\s+TOTAL|TOTAL\\s+BET|TOTAL|AMOUNT|BET\\s+AMOUNT|MONEY|STAKE)"

    private val resultWord = Regex("\\b(WINS?|WON|WINNER|RESULT|JIT(?:SE|LO|ECHHE)?|JEET|JETE|JAY)\\b")
    private val promoWords = Regex("\\b(BIGGEST|TOP\\s+WINNER|HISTORY|LAST|WINNERS?\\s+LIST|LEADERBOARD|AWARD|PREDICTION|TRENDING|RANK)\\b")
    private val excludedFromNumbers = Regex("\\b(HISTORY|LAST|BIGGEST|TOP|RANGE|RESULT\\s+HISTORY|LEADERBOARD)\\b")

    fun parse(frame: OcrFrame): ParsedScreen {
        val canonical = frame.lines.map { canonicalText(it.text) }
        val full = canonical.joinToString(" ")
        val stageText = detectStage(full)
        val bettingStage = when {
            stageText == "BET LOCKED" -> BettingStage.BETTING_LOCKED
            stageText == "PLACE BET" -> BettingStage.BETTING_OPEN
            else -> BettingStage.NOT_DETECTED
        }

        val labels = findSideLabels(frame.lines)
        val andarLabel = labels.firstOrNull { it.first == Side.ANDAR }?.second
        val baharLabel = labels.firstOrNull { it.first == Side.BAHAR }?.second
        val sideBySide = labelsAreSideBySide(andarLabel, baharLabel)
        val boundary = if (sideBySide) labelBoundary(andarLabel, baharLabel) else null

        val andarTotal = findPanelTotal(frame.lines, canonical, Side.ANDAR)
        val baharTotal = findPanelTotal(frame.lines, canonical, Side.BAHAR)
        val andarOdds = findOdds(frame.lines, canonical, Side.ANDAR, andarLabel)
        val baharOdds = findOdds(frame.lines, canonical, Side.BAHAR, baharLabel)

        val pctA = findPercentage(canonical, Side.ANDAR)
        val pctB = findPercentage(canonical, Side.BAHAR)
        val (andarPct, baharPct) = validatePercentPair(pctA, pctB)

        val dealt = findCardsDealt(frame.lines, canonical)
        val winner = findWinner(frame.lines, canonical)
        val amounts = findVisibleAmounts(
            frame.lines, canonical, boundary, sideBySide, andarTotal, baharTotal
        )
        val balance = findMoneyLabelled(frame.lines, canonical, Regex("\\b(BALANCE|WALLET|CREDIT|AVAILABLE|CASH)\\b"), "balance")
        val totalStake = findMoneyLabelled(frame.lines, canonical, Regex("\\bTOTAL\\s+STAKE\\b"), "visible total stake")

        val tokens = collectNumberTokens(frame.lines, canonical)
        for (token in tokens) {
            token.side = sideFromGeometry(
                frame.lines[token.lineIndex], boundary, andarLabel, baharLabel, sideBySide
            )
        }

        return ParsedScreen(
            stageText = stageText,
            bettingStage = bettingStage,
            andarLabel = andarLabel,
            baharLabel = baharLabel,
            andarTotal = andarTotal,
            baharTotal = baharTotal,
            andarOdds = andarOdds,
            baharOdds = baharOdds,
            andarPercentage = andarPct,
            baharPercentage = baharPct,
            cardsDealtOcr = dealt,
            ocrWinner = winner,
            visibleAmounts = amounts,
            visibleBalance = balance,
            visibleTotalStake = totalStake,
            numberTokens = tokens,
            sideLabelLines = labels.map { it.second }
        )
    }

    fun canonicalText(text: String): String {
        var value = text
        for (i in bengaliDigits.indices) value = value.replace(bengaliDigits[i], ('0' + i))
        return value.uppercase()
            .replace(Regex("\\bBOHAR\\b"), "BAHAR")
            .replace(Regex("\\bBAHER\\b"), "BAHAR")
            .replace(Regex("\\bBHAR\\b"), "BAHAR")
            .replace(Regex("\\bANDER\\b"), "ANDAR")
            .replace(Regex("\\bANDHAR\\b"), "ANDAR")
            .replace(Regex("\\bIDAR\\b"), "ANDAR")
            .replace(Regex("\\bNDAR\\b"), "ANDAR")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Side names are sometimes OCR'd with a trailing card value ("BAHAR 9") or with a
     * dropped prefix ("IDAR"). Match the side token without requiring a word boundary
     * after it, because a digit is also a word character.
     */
    private fun containsSide(text: String, side: Side): Boolean {
        val pattern = Regex("(?<![A-Z])" + side.label + "(?![A-Z])")
        return pattern.containsMatchIn(text)
    }

    private fun lineSide(text: String): Side {
        val hasA = containsSide(text, Side.ANDAR)
        val hasB = containsSide(text, Side.BAHAR)
        return when {
            hasA && !hasB -> Side.ANDAR
            hasB && !hasA -> Side.BAHAR
            else -> Side.UNKNOWN
        }
    }

    private fun detectStage(full: String): String {
        // OCR frequently appends a stray letter or exclamation mark to LOCKED
        // (for example "BET LOCKEDI" or "BET LOCKED!").
        if (Regex("\\bBET\\s*(?:IS\\s*)?LOCKED[A-Z0-9!|._-]*").containsMatchIn(full) ||
            Regex("\\bBETTING\\s*(?:IS\\s*)?(?:CLOSED|LOCKED)[A-Z0-9!|._-]*").containsMatchIn(full)
        ) return "BET LOCKED"
        if (Regex("\\bPLACE\\s*(?:YOUR\\s*)?BET\\b").containsMatchIn(full) ||
            Regex("\\bBETTING\\s+(?:IS\\s+)?OPEN\\b").containsMatchIn(full) ||
            Regex("\\bBETS?\\s+OPEN\\b").containsMatchIn(full) ||
            Regex("\\bBET\\s+NOW\\b").containsMatchIn(full)
        ) return "PLACE BET"
        return "NOT DETECTED"
    }

    private fun findSideLabels(lines: List<OcrLine>): List<Pair<Side, OcrLine>> {
        val out = mutableListOf<Pair<Side, OcrLine>>()
        val rangeRow = Regex("\\b\\d{1,2}\\s*[-–]\\s*\\d{1,2}\\b|\\b\\d{1,2}\\s*\\+")
        for (line in lines) {
            val u = canonicalText(line.text)
            val side = lineSide(u)
            if (side == Side.UNKNOWN) continue
            if (u.contains("%") || u.contains("ODDS") || u.contains("TOTAL") ||
                u.contains("AMOUNT") || u.contains("CARDS") || u.contains("DEALT") ||
                promoWords.containsMatchIn(u) || resultWord.containsMatchIn(u) || u.contains("WIN") ||
                rangeRow.containsMatchIn(u)
            ) continue
            val refined = refineSideLabel(line, side)
            if (refined.box.width > 0.46f || refined.box.height > 0.10f) continue
            out.add(side to refined)
        }
        // Prefer a clean, tight label over a line that merely contains the side name.
        return out.sortedWith(
            compareByDescending<Pair<Side, OcrLine>> {
                val u = canonicalText(it.second.text)
                var score = it.second.confidence * 10f
                if (lineSide(u) == it.first) score += 50f
                if (u == it.first.label) score += 100f
                if (it.second.box.width <= 0.22f) score += 10f
                score
            }.thenBy { it.second.box.area }
        )
    }

    private fun refineSideLabel(line: OcrLine, side: Side): OcrLine {
        val element = line.elements.firstOrNull { lineSide(canonicalText(it.text)) == side }
        if (element == null) return line
        return OcrLine(
            text = element.text,
            box = element.box,
            confidence = line.confidence,
            elements = line.elements,
            blockIndex = line.blockIndex
        )
    }

    private fun labelBoundary(a: OcrLine?, b: OcrLine?): Float? = when {
        a != null && b != null -> ((a.box.centerX + b.box.centerX) / 2f).coerceIn(0.2f, 0.8f)
        a != null -> 0.5f
        b != null -> 0.5f
        else -> null
    }

    private fun findPanelTotal(lines: List<OcrLine>, canonical: List<String>, side: Side): MoneyReading {
        val s = side.label
        val sideToken = "(?<![A-Z])$s(?![A-Z])"
        val keyedPatterns = listOf(
            Regex("\\b$totalKeyword\\s+(?:OF\\s+)?$sideToken\\s*[:=#-]?\\s*$currency?\\s*$number"),
            Regex("$sideToken\\s+(?:PANEL\\s+)?$totalKeyword\\s*[:=#-]?\\s*$currency?\\s*$number")
        )
        val currencyPatterns = listOf(
            Regex("$sideToken\\s*[:=#-]?\\s*$currency\\s*$number"),
            Regex("$sideToken\\s*[:=#-]?\\s*$number\\s*$currency")
        )
        var sawKeyword = false
        var sawInvalid = false
        for (index in canonical.indices) {
            val u = canonical[index]
            // A line that names both sides, or no side, is never a per-side total.
            if (lineSide(u) != side) continue
            if (isNonTotalLine(u)) continue
            val keyed = keyedPatterns.firstNotNullOfOrNull { it.find(u) }
            if (keyed != null) {
                sawKeyword = true
                val raw = keyed.groupValues.getOrNull(1).orEmpty()
                val parsed = parseMoney(raw)
                if (Validation.money(parsed)) {
                    return MoneyReading(parsed, DetectionStatus.DETECTED, raw, "side total label", index)
                }
                sawInvalid = true
                continue
            }
            val withCurrency = currencyPatterns.firstNotNullOfOrNull { it.find(u) }
            if (withCurrency != null) {
                val raw = withCurrency.groupValues.getOrNull(1).orEmpty()
                val parsed = parseMoney(raw)
                if (Validation.money(parsed)) {
                    return MoneyReading(parsed, DetectionStatus.DETECTED, raw, "side label with currency", index)
                }
                sawInvalid = true
            }
        }

        // The real mobile layout prints the side panel total as a bare number in a badge at the
        // right edge of that side's panel (for example ANDAR chips "510"). It is only accepted
        // when it is a standalone amount far to the right of the panel label and inside that
        // panel's vertical band; anything ambiguous stays NOT DETECTED.
        val badge = findPanelBadgeAmount(lines, canonical, side)
        if (badge != null) return badge

        return when {
            sawInvalid -> MoneyReading.uncertain("", "amount failed money validation")
            sawKeyword -> MoneyReading.uncertain("", "total label found without a usable amount")
            else -> MoneyReading.notDetected("no labelled $s total visible")
        }
    }

    private fun findPanelBadgeAmount(
        lines: List<OcrLine>,
        canonical: List<String>,
        side: Side
    ): MoneyReading? {
        val label = lines.firstOrNull { line ->
            val u = canonicalText(line.text)
            containsSide(u, side) &&
                line.box.centerX < 0.5f &&
                line.box.centerY in 0.35f..0.75f &&
                !isNonTotalLine(u)
        } ?: return null
        val bareAmount = Regex("^(?:[A-Z]{1,3}\\s*)?\\d[\\d,]*(?:\\.\\d{1,2})?$")
        var best: MoneyReading? = null
        var bestDistance = Float.MAX_VALUE
        for (index in canonical.indices) {
            val u = canonical[index]
            if (!bareAmount.matches(u)) continue
            val box = lines[index].box
            if (box.right < 0.80f || box.width > 0.18f) continue
            val dy = abs(box.centerY - label.box.centerY)
            val dx = box.centerX - label.box.centerX
            if (dy > 0.055f || dx < 0.30f) continue
            val raw = u.replace(Regex("[^0-9.,]"), "")
            val value = parseMoney(raw)
            if (!Validation.money(value)) continue
            val distance = dy * 2f + abs(dx)
            if (distance < bestDistance) {
                bestDistance = distance
                best = MoneyReading(
                    value,
                    DetectionStatus.DETECTED,
                    raw,
                    "panel badge amount",
                    index
                )
            }
        }
        return best
    }

    private fun isNonTotalLine(upper: String): Boolean {
        if (upper.contains("%")) return true
        if (upper.contains("ODDS")) return true
        if (Regex("\\b1\\s*[:/]\\s*\\d").containsMatchIn(upper)) return true
        if (upper.contains("CARD") || upper.contains("DEALT")) return true
        if (upper.contains("MIN") || upper.contains("MAX")) return true
        if (excludedFromNumbers.containsMatchIn(upper)) return true
        if (resultWord.containsMatchIn(upper)) return true
        return false
    }

    private fun findOdds(
        lines: List<OcrLine>,
        canonical: List<String>,
        side: Side,
        label: OcrLine?
    ): ValueReading<Double> {
        val ratio = Regex("(?<!\\d)1\\s*[:/]\\s*(\\d{1,4}(?:\\.\\d{1,3})?)(?!\\d)")
        val oddsWord = Regex("\\bODDS?\\b[^0-9%]{0,12}(\\d{1,4}(?:\\.\\d{1,3})?)")
        val xStyle = Regex("(\\d{1,4}(?:\\.\\d{1,3})?)\\s*[X](?![A-Z0-9])")
        val rangeRow = Regex("\\b\\d{1,2}\\s*[-–]\\s*\\d{1,2}\\b|\\b\\d{1,2}\\s*\\+")

        fun allowed(u: String): Boolean {
            if (u.contains("%")) return false
            if (rangeRow.containsMatchIn(u)) return false
            if (excludedFromNumbers.containsMatchIn(u)) return false
            if (u.contains("RANGE") || u.contains("HISTORY") || u.contains("BIGGEST")) return false
            if (u.contains("MIN") || u.contains("MAX")) return false
            val explicit = lineSide(u)
            if (explicit != Side.UNKNOWN && explicit != side) return false
            return true
        }

        fun reading(index: Int, match: MatchResult, reason: String): ValueReading<Double> {
            val raw = match.groupValues[1]
            val value = raw.toDoubleOrNull()
            return if (Validation.odds(value)) {
                ValueReading(value, DetectionStatus.DETECTED, raw, reason, index)
            } else {
                ValueReading(null, DetectionStatus.UNCERTAIN, raw, "odds outside 1.01..1000", index)
            }
        }

        // 1) A line that explicitly names the side and carries a ratio or odds word.
        for (index in canonical.indices) {
            val u = canonical[index]
            if (lineSide(u) != side) continue
            if (!allowed(u)) continue
            val match = ratio.find(u) ?: oddsWord.find(u) ?: xStyle.find(u) ?: continue
            return reading(index, match, "side line odds")
        }

        // 2) A line close to the side label. Text adjacency alone is not enough: the card
        // range table also contains "1:7.5", but it is far away in screen coordinates.
        if (label != null) {
            var best: ValueReading<Double>? = null
            var bestDistance = Float.MAX_VALUE
            for (index in canonical.indices) {
                val u = canonical[index]
                if (lineSide(u) != Side.UNKNOWN) continue
                if (!allowed(u)) continue
                val match = ratio.find(u) ?: oddsWord.find(u) ?: xStyle.find(u) ?: continue
                val box = lines[index].box
                val dy = abs(box.centerY - label.box.centerY)
                val dx = abs(box.centerX - label.box.centerX)
                if (dy > 0.075f || dx > 0.35f) continue
                if (isRangeTableContext(canonical, index, rangeRow)) continue
                val distance = dy * 2f + dx
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = reading(index, match, "odds near side label")
                }
            }
            if (best != null) return best
        }
        return ValueReading(null, DetectionStatus.NOT_DETECTED, "", "no $side odds detected")
    }

    private fun isRangeTableContext(
        canonical: List<String>,
        index: Int,
        rangeRow: Regex
    ): Boolean {
        for (i in (index - 1)..(index + 1)) {
            if (i !in canonical.indices) continue
            if (rangeRow.containsMatchIn(canonical[i])) return true
        }
        return false
    }

    private fun findPercentage(canonical: List<String>, side: Side): ValueReading<Double> {
        val re = Regex("\\b" + side.label + "\\b[^0-9%]{0,20}(\\d{1,3}(?:\\.\\d+)?)\\s*%")
        val alt = Regex("(\\d{1,3}(?:\\.\\d+)?)\\s*%[^A-Z0-9]{0,12}\\b" + side.label + "\\b")
        for (index in canonical.indices) {
            val u = canonical[index]
            if (!u.contains(side.label)) continue
            val m = re.find(u) ?: alt.find(u) ?: continue
            val raw = m.groupValues[1]
            val value = raw.toDoubleOrNull()
            if (!Validation.percent(value)) {
                return ValueReading(null, DetectionStatus.REJECTED, raw, "percentage outside 0..100", index)
            }
            return ValueReading(value, DetectionStatus.DETECTED, raw, "percentage label", index)
        }
        return ValueReading(null, DetectionStatus.NOT_DETECTED, "", "no $side percentage detected")
    }

    private fun validatePercentPair(
        a: ValueReading<Double>,
        b: ValueReading<Double>
    ): Pair<ValueReading<Double>, ValueReading<Double>> {
        if (a.value != null && b.value != null) {
            if (Validation.percentagesPair(a.value, b.value)) return a to b
            val reason = "Andar/Bahar percentages do not sum to ~100"
            return a.copy(value = null, status = DetectionStatus.UNCERTAIN, reason = reason) to
                b.copy(value = null, status = DetectionStatus.UNCERTAIN, reason = reason)
        }
        if (a.value != null || b.value != null) {
            val reason = "both side percentages required"
            return a.copy(value = null, status = DetectionStatus.UNCERTAIN, reason = reason) to
                b.copy(value = null, status = DetectionStatus.UNCERTAIN, reason = reason)
        }
        return a to b
    }

    private fun findCardsDealt(lines: List<OcrLine>, canonical: List<String>): ValueReading<Int> {
        val inlinePatterns = listOf(
            Regex("\\bCARDS?\\s+(?:DEALT|PLAYED|DRAWN)\\b\\s*[:#=-]?\\s*(\\d{1,2})"),
            Regex("\\bDEALT\\s*[:#=-]?\\s*(\\d{1,2})"),
            Regex("\\bTOTAL\\s+CARDS?\\b\\s*[:#=-]?\\s*(\\d{1,2})")
        )
        for (index in canonical.indices) {
            val u = canonical[index]
            if (!u.contains("CARD") && !u.contains("DEALT")) continue
            if (u.contains("DECK")) continue
            for (pattern in inlinePatterns) {
                val m = pattern.find(u) ?: continue
                val raw = m.groupValues[1]
                val value = raw.toIntOrNull()
                return if (Validation.cardCount(value)) {
                    ValueReading(value, DetectionStatus.DETECTED, raw, "cards dealt label", index)
                } else {
                    ValueReading(null, DetectionStatus.REJECTED, raw, "card count outside 0..52", index)
                }
            }
        }
        val labelIndices = canonical.indices.filter {
            val u = canonical[it]
            (u.contains("CARDS") && u.contains("DEALT")) || u == "CARDS DEALT"
        }
        for (labelIndex in labelIndices) {
            val labelBox = lines[labelIndex].box
            var bestIndex = -1
            var bestDistance = Float.MAX_VALUE
            for (index in canonical.indices) {
                if (index == labelIndex) continue
                val candidate = canonical[index]
                if (!Regex("^\\d{1,2}$").matches(candidate)) continue
                val box = lines[index].box
                val distance = abs(box.centerY - labelBox.centerY) + abs(box.centerX - labelBox.centerX)
                if (abs(box.centerY - labelBox.centerY) > 0.12f) continue
                if (abs(box.centerX - labelBox.centerX) > 0.35f) continue
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestIndex = index
                }
            }
            if (bestIndex < 0) {
                return ValueReading(null, DetectionStatus.UNCERTAIN, "", "cards dealt label without a valid count", labelIndex)
            }
            val raw = canonical[bestIndex]
            val value = raw.toIntOrNull()
            return if (Validation.cardCount(value)) {
                ValueReading(value, DetectionStatus.DETECTED, raw, "adjacent card count", bestIndex)
            } else {
                ValueReading(null, DetectionStatus.REJECTED, raw, "card count outside 0..52", bestIndex)
            }
        }
        return ValueReading(null, DetectionStatus.NOT_DETECTED, "", "cards dealt not visible")
    }

    private fun findWinner(lines: List<OcrLine>, canonical: List<String>): OcrWinnerSignal {
        val winners = mutableListOf<OcrWinner>()
        var conflict = false
        var conflictReason = ""
        for (index in canonical.indices) {
            val u = canonical[index]
            if (!u.contains("ANDAR") && !u.contains("BAHAR")) continue
            if (u.contains("%")) continue
            if (promoWords.containsMatchIn(u)) continue
            if (u.contains("NOT DETECTED") || u.contains("NO RESULT") ||
                u.contains("PENDING") || u.contains("WAITING")
            ) continue
            if (!resultWord.containsMatchIn(u)) continue

            val before = Regex("\\b(ANDAR|BAHAR)\\b[^%]{0,24}?" + resultWord.pattern).find(u)
            val after = Regex(resultWord.pattern + "[^%]{0,24}?\\b(ANDAR|BAHAR)\\b").find(u)
            val beforeSide = before?.groupValues?.getOrNull(1)?.let { Side.fromLabel(it) } ?: Side.UNKNOWN
            val afterSide = after?.groupValues?.lastOrNull()?.let { Side.fromLabel(it) } ?: Side.UNKNOWN
            val hasA = Regex("\\bANDAR\\b").containsMatchIn(u)
            val hasB = Regex("\\bBAHAR\\b").containsMatchIn(u)

            val side = when {
                beforeSide != Side.UNKNOWN && afterSide != Side.UNKNOWN && beforeSide != afterSide -> {
                    conflict = true
                    conflictReason = "same result line names both sides"
                    Side.UNKNOWN
                }
                beforeSide != Side.UNKNOWN -> beforeSide
                afterSide != Side.UNKNOWN -> afterSide
                hasA && !hasB -> Side.ANDAR
                hasB && !hasA -> Side.BAHAR
                else -> {
                    conflict = true
                    conflictReason = "result line is ambiguous"
                    Side.UNKNOWN
                }
            }
            if (side == Side.UNKNOWN) continue
            val phrase = resultWord.find(u)?.value ?: "RESULT"
            winners.add(
                OcrWinner(
                    side = side,
                    lineIndex = index,
                    lineText = lines[index].text,
                    confidence = lines[index].confidence.coerceIn(0f, 1f),
                    evidence = "explicit result wording on side line",
                    resultPhrase = phrase
                )
            )
        }
        if (winners.map { it.side }.distinct().size > 1) {
            conflict = true
            conflictReason = "different OCR lines name different winners"
        }
        return OcrWinnerSignal(
            side = if (conflict) Side.UNKNOWN else winners.firstOrNull()?.side ?: Side.UNKNOWN,
            winners = winners,
            conflict = conflict,
            conflictReason = conflictReason
        )
    }

    private fun findVisibleAmounts(
        lines: List<OcrLine>,
        canonical: List<String>,
        boundary: Float?,
        sideBySide: Boolean,
        andarTotal: MoneyReading,
        baharTotal: MoneyReading
    ): List<VisibleAmount> {
        val out = mutableListOf<VisibleAmount>()
        val totalLines = setOf(andarTotal.lineIndex, baharTotal.lineIndex)
        val notAnAmount = Regex(
            "\\b(MIN|MINIMUM|MAX|MAXIMUM|LIMIT|BALANCE|WALLET|CREDIT|AVAILABLE|CASH|" +
                "TOTAL\\s+STAKE|BIGGEST|WINNER|HISTORY|RANGE|ODDS)\\b"
        )
        for (index in canonical.indices) {
            val u = canonical[index]
            if (index in totalLines) continue
            if (u.contains("%") || isNonTotalLine(u)) continue
            if (notAnAmount.containsMatchIn(u)) continue
            val hasCurrency = Regex(currency).containsMatchIn(u)
            val hasBetWord = Regex("\\b(BET|AMOUNT|STAKE)\\b").containsMatchIn(u)
            if (!hasCurrency && !hasBetWord) continue

            val m = Regex(currency + "\\s*" + number).find(u)
                ?: Regex(number + "\\s*" + currency).find(u)
                ?: Regex("\\b(?:BET|AMOUNT|STAKE)\\b[^0-9]{0,8}" + number).find(u)
                ?: continue
            val raw = m.groupValues[1]
            val value = parseMoney(raw)
            if (!Validation.money(value)) continue
            val explicit = lineSide(u)
            val side = if (explicit != Side.UNKNOWN) {
                explicit
            } else if (sideBySide) {
                sideFromGeometry(lines[index], boundary, null, null, true)
            } else {
                Side.UNKNOWN
            }
            if (side == Side.UNKNOWN) continue
            out.add(
                VisibleAmount(
                    side = side,
                    value = value!!,
                    raw = raw,
                    lineIndex = index,
                    lineText = lines[index].text,
                    confidence = if (hasCurrency) 0.85f else 0.65f,
                    reason = if (hasCurrency) "currency amount near side" else "bet amount label"
                )
            )
        }
        return out.distinctBy { it.lineIndex to it.value }.take(40)
    }

    private fun findMoneyLabelled(
        lines: List<OcrLine>,
        canonical: List<String>,
        label: Regex,
        reason: String
    ): MoneyReading {
        for (index in canonical.indices) {
            val u = canonical[index]
            if (!label.containsMatchIn(u)) continue
            if (u.contains("%") || u.contains("ODDS") || Regex("\\b1\\s*[:/]\\s*\\d").containsMatchIn(u)) continue
            val m = Regex(currency + "\\s*" + number).find(u)
                ?: Regex("\\b(?:BALANCE|WALLET|CREDIT|AVAILABLE|CASH|TOTAL\\s+STAKE)\\b[^0-9]{0,12}" + number).find(u)
                ?: continue
            val raw = m.groupValues[1]
            val value = parseMoney(raw)
            return if (Validation.money(value)) {
                MoneyReading(value, DetectionStatus.DETECTED, raw, reason, index)
            } else {
                MoneyReading.uncertain(raw, "amount failed money validation", index)
            }
        }
        return MoneyReading.notDetected("no visible $reason")
    }

    private fun collectNumberTokens(lines: List<OcrLine>, canonical: List<String>): List<NumberToken> {
        val out = mutableListOf<NumberToken>()
        for (index in canonical.indices) {
            val u = canonical[index]
            val matches = Regex("\\d[\\d,]*(?:\\.\\d{1,4})?").findAll(u)
            for (m in matches) {
                val raw = m.value
                val value = raw.replace(",", "").toDoubleOrNull() ?: continue
                val after = u.substring(m.range.last + 1).trimStart()
                val before = u.substring(0, m.range.first)
                val kind = when {
                    after.startsWith("%") -> NumberKind.PERCENTAGE
                    u.contains("ODDS") || Regex("\\b1\\s*[:/]\\s*\\d").containsMatchIn(u) -> NumberKind.ODDS
                    u.contains("CARD") || u.contains("DEALT") -> NumberKind.CARD_COUNT
                    Regex(currency).containsMatchIn(u) ||
                        Regex("\\b(TOTAL|AMOUNT|BET|STAKE|BALANCE|WALLET)\\b").containsMatchIn(u) -> NumberKind.MONEY
                    else -> NumberKind.UNKNOWN
                }
                out.add(NumberToken(value, raw, kind, index, lines[index].text, lines[index].confidence))
            }
        }
        return out
    }

    private fun sideFromGeometry(
        line: OcrLine,
        boundary: Float?,
        andarLabel: OcrLine?,
        baharLabel: OcrLine?,
        sideBySide: Boolean
    ): Side {
        // Explicit side wording always wins and is the only safe assignment when the
        // labels are stacked vertically (the real game layout often does this).
        val explicit = lineSide(canonicalText(line.text))
        if (explicit != Side.UNKNOWN) return explicit
        if (!sideBySide) return Side.UNKNOWN

        if (andarLabel != null || baharLabel != null) {
            val top = listOfNotNull(andarLabel?.box?.top, baharLabel?.box?.top).minOrNull()!!
            val bottom = listOfNotNull(andarLabel?.box?.bottom, baharLabel?.box?.bottom).maxOrNull()!!
            if (line.box.centerY < top - 0.35f || line.box.centerY > bottom + 0.45f) {
                return Side.UNKNOWN
            }
        }
        if (boundary != null) {
            if (line.box.centerX < boundary - 0.02f) return Side.ANDAR
            if (line.box.centerX > boundary + 0.02f) return Side.BAHAR
        }
        if (andarLabel != null && baharLabel != null) {
            return if (line.box.centerDistance(andarLabel.box) <= line.box.centerDistance(baharLabel.box)) {
                Side.ANDAR
            } else {
                Side.BAHAR
            }
        }
        if (andarLabel != null) return Side.ANDAR
        if (baharLabel != null) return Side.BAHAR
        return Side.UNKNOWN
    }

    fun parseMoney(raw: String): Double? {
        val cleaned = raw.replace(",", "").trim()
        if (cleaned.isEmpty()) return null
        return cleaned.toDoubleOrNull()
    }
}
