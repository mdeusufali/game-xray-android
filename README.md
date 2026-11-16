# Game X-Ray Android

Game X-Ray is a local Android screen-capture and computer-vision dashboard for the visible
Andar-Bahar game screen. It uses Android MediaProjection (with user approval), on-device ML Kit
Latin OCR, and pure-Kotlin image analysis. It does not connect to game APIs, WebSockets, remote
history, or any network service.

Version **0.4.0** (`versionCode` 13).

## What is implemented

### Capture and OCR
1. Full-screen MediaProjection capture in a foreground service, with an overlay dashboard.
2. On-device ML Kit OCR. Raw OCR text is stored separately from structured parsed values.
3. OCR lines, blocks, elements, bounding boxes, confidence values, image size, and timestamps are
   retained for diagnostics.
4. Processing is throttled, runs on a background executor, and persists status/round state so a
   restart can restore the active round and already-counted cards.

### Screen parsing
5. `PLACE BET` / `BET LOCKED` stage detection (lock takes precedence).
6. Separate Andar and Bahar panel totals, previous totals, and differences. Displayed totals are
   treated as totals and are never summed across screenshots.
7. Side odds and percentages are parsed separately and validated:
   - odds must be within `1.01 .. 1000`
   - percentages must be within `0 .. 100` and the pair must sum to approximately 100
   - bare side numbers are not accepted as panel totals
   - a line naming both sides is rejected as a per-side total
8. `CARDS DEALT` OCR values are validated to the 0–52 range.
9. Visible balances, total stake, individual bet amounts, and generic number tokens are retained
   with their source line and coordinates.
10. Real-layout panel totals printed as bare badges at the right edge of each side panel are
    accepted only with strict geometry (standalone number, same vertical band, far right edge).
    Anything ambiguous stays `NOT DETECTED`.
11. OCR mistakes remain `UNCERTAIN`, `REJECTED`, or `NOT DETECTED`; they never become confirmed
    financial results.

### Card detection and counting
12. Andar/Bahar card display zones are located from the OCR side-label geometry.
    - Side-by-side left/right labels split by X.
    - Real stacked layouts (small `ANDAR`/`BAHAR` row labels on the right) split by Y into
      row bands, never by X.
    - Settings can override both zones with manual `left,top,right,bottom` percentages.
13. Bright, low-saturation, card-shaped connected components are detected separately inside each
    zone. The mask is tuned to exclude dealer arms/hands and the ornate cut card.
14. Every card gets two luminance fingerprints. A track must be seen in repeated frames before it
    is confirmed. Movement is re-matched by overlap, fingerprint, size, and proximity.
15. Adjacent identical face-down cards are kept as separate tracks.
16. Confirmed counts are monotonic inside a round. A card disappearing behind an animation is not
    recounted when it reappears. Reset happens only at a genuine new round.
17. Cards visible while betting is still open are treated as stale/transition cards and are ignored
    until the bet locks.
18. Heavily overlapping fan rows can merge into one wide shape. The detector never invents a split
    when the columns have no separating valleys; `CARDS DEALT` OCR is shown separately, and the
    image count is treated as a lower bound in that case.

### Round lifecycle and winners
19. A round ID is created once for the betting period. Repeated `PLACE BET` frames do not create
    duplicate rounds. A new round starts after a completed round when betting opens again.
20. Personal bet settings (side, stake, personal odds) are live during betting and frozen at
    betting lock/first card. Later edits never change an earlier round's history.
21. Winner states are explicit:
    - `WINNER_CONFIRMED`: repeated explicit OCR result wording, or OCR plus stable visual evidence
    - `VISUAL_WINNER_CANDIDATE`: only the visual highlight changed; never used for money
    - `RESULT_NOT_DETECTED`: no reliable result evidence
    - `RESULT_CONFLICT`: OCR/visual evidence disagrees
22. Visual winner scoring is directional: a winning panel glows in its side colour (blue for Andar,
    red/orange for Bahar) and only positive luma/colour gain scores. A dimming losing panel cannot
    create a false "both panels changed" conflict. A candidate survives brief payout animations.
23. A completed record is emitted once per round. Repeated result frames and duplicate records with
    a different ID inside a short time window are rejected by the local store.
24. A locked round that ends without a confirmed result (candidate or conflict only) is closed once
    on the next genuine round boundary as `UNSETTLED`, preserving the candidate/conflict state but
    never producing P/L.
25. P/L is calculated only with a confirmed winner, a valid personal side, a positive stake, and
    valid personal odds. Missing data leaves P/L unavailable but still saves the round record.
    Overall Andar/Bahar win counts also include confirmed records only.

### Local data
26. SharedPreferences + JSON persistence, no Room and no network dependency.
27. Up to 500 round records, 300 changed OCR snapshots, and 200 status messages are retained.
28. Older text-only history snapshots are still readable.
29. Dashboard, history, diagnostics, settings, and overall statistics (wins/losses, stake, profit,
    loss, net result, settled/unsettled, Andar/Bahar winner counts) are available in the app.

## Project structure

- `app/src/main/java/com/gamexray/app/core/` — Android-independent engine:
  - `OcrParser.kt` — positioned OCR parsing and validation
  - `CardDetector.kt` — zones, shape detection, fingerprints, visual statistics
  - `CardCounter.kt` — duplicate-safe card tracking
  - `MoneyTracker.kt` — previous/current/difference panel totals
  - `WinnerDetector.kt` — visual evidence, winner states, settlement/accounting
  - `RoundManager.kt` — round state machine, bet freeze, record creation
  - `FrameEngine.kt` — end-to-end frame pipeline
  - `RoundDeduplication.kt` — duplicate-result guard
- `app/src/main/java/com/gamexray/app/data/` — JSON codecs and local store
- `app/src/main/java/com/gamexray/app/AndroidVision.kt` — bitmap/frame conversion
- `app/src/main/java/com/gamexray/app/CaptureService.kt` — capture, persistence, overlay
- `app/src/main/java/com/gamexray/app/MainActivity.kt` — dashboard/history/diagnostics/settings
- `app/src/test/java/com/gamexray/app/core/` — pure-JVM unit tests

## Tests

Run the local JVM test suite:

```bash
gradle :app:testDebugUnitTest
```

The 73 tests cover:
- OCR stage, label, total, odds, percentage, card-count, balance, amount, winner, and real-layout
  panel-badge parsing
- both-side total rejection, bare-number rejection, badge-geometry rejection, and Bengali digit
  normalization
- synthetic card-shape detection, separate Andar/Bahar zones, stacked row-band zones, manual zone
  override, duplicate candidates, adjacent identical cards, movement, stability, uncertainty,
  reset, and restore
- money previous/current/difference tracking and independent sides
- winner confirmation timing, visual-only candidates, candidate retention through payout
  animation, conflicts, sticky results, directional visual scoring, visual streaks, blinks, and
  open-betting suppression
- bet freeze, round transitions, monotonic card counts, stale-card grace, missing bet data,
  one-record-per-round completion, and one-time unsettled finalization at a new-round boundary
- win/loss/net P/L accounting, confirmed-only side-win counting, overall statistics, and
  duplicate-result prevention

Also run:

```bash
gradle :app:lintDebug
gradle :app:assembleDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Build

Push to `main` or run the GitHub Actions workflow manually. CI runs unit tests, lint, and
`assembleDebug`, verifies the APK signature/package metadata, and uploads the debug APK as the
`Game-X-Ray-debug-v0.4.0` artifact.

## Use

1. Install the APK.
2. Allow the floating-window permission.
3. Tap Start screen capture and approve Android's screen-capture prompt.
4. Switch to the game. The floating panel shows OCR status, card counts, panel totals, odds,
   winner state, and P/L.
5. In My Bet Settings, enter your own side, stake, and personal odds. Do not enter public panel
   totals as your stake.
6. If the automatic card rows do not line up on your device, use **CARD ZONES** and enter the
   Andar/Bahar rows as `left,top,right,bottom` percentages. Leave both blank for automatic zones.
7. Refresh the dashboard/history to inspect saved rounds and diagnostics. Tap STOP on the floating
   panel to stop capture.

Android displays a persistent notification during screen capture. Captured frames are processed
on-device and are not uploaded by this app.

## Important limitations and real-game validation

- **OCR is not a guaranteed card/rank/suit detector.** Card counting uses visible image shapes
  inside the OCR-located Andar/Bahar zones, not OCR of card faces.
- **Overlapping fan rows are the main remaining card-count risk.** A 72-second real gameplay video
  was used to map the stacked layout, tighten the white-card mask so dealer hands are excluded, and
  confirm the Andar/Bahar panel glow colours. Real frames still show heavily overlapped cards
  merging into one wide row with no column valleys, so the image count can be a lower bound.
  `CARDS DEALT` OCR is displayed separately and manual zones are configurable. Exact per-side
  visual counts on heavily overlapped rows are not claimed as verified.
- Panel totals and odds depend on the game's actual labels and OCR quality. Ambiguous values stay
  unavailable rather than being guessed.
- A winner is confirmed only from repeated explicit result wording or OCR/visual agreement. A
  visual-only highlight is shown as a candidate and never used for P/L. A visual highlight was
  observed on real frames (red/Bahar and blue/Andar), but OCR wording still decides confirmation.
- Statistics describe saved, confirmed results. They are not predictions of future outcomes.
- P/L uses the personal side, stake, and odds entered by the user. It is not financial advice and
  cannot guarantee a result.
- All OCR text and history are stored locally on the device. The app has no network/API/WebSocket
  feature.
- To finish real-game validation, capture test screenshots for: betting open, betting locked,
  non-overlapping cards on each side, overlapped/fanned cards on each side, card movement/animation,
  hidden card faces, a visual-only winner, an explicit `ANDAR WINS` / `BAHAR WINS` result, and a
  conflicting/ambiguous result screen.
