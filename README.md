# Game X-Ray Android

Game X-Ray is a local Android screen-capture and computer-vision dashboard for the visible
Andar-Bahar game screen. It uses Android MediaProjection (with user approval), on-device ML Kit
Latin OCR, and pure-Kotlin image analysis. It does not connect to game APIs, WebSockets, remote
history, or any network service.

Version **0.3.0** (`versionCode` 12).

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
10. OCR mistakes remain `UNCERTAIN`, `REJECTED`, or `NOT DETECTED`; they never become confirmed
    financial results.

### Card detection and counting
11. Andar/Bahar card display zones are located from the OCR side-label geometry.
12. Bright, low-saturation, card-shaped connected components are detected separately inside each
    zone.
13. Every card gets two luminance fingerprints. A track must be seen in repeated frames before it
    is confirmed. Movement is re-matched by overlap, fingerprint, size, and proximity.
14. Adjacent identical face-down cards are kept as separate tracks.
15. Confirmed counts are monotonic inside a round. A card disappearing behind an animation is not
    recounted when it reappears. Reset happens only at a genuine new round.
16. Cards visible while betting is still open are treated as stale/transition cards and are ignored
    until the bet locks.

### Round lifecycle and winners
17. A round ID is created once for the betting period. Repeated `PLACE BET` frames do not create
    duplicate rounds. A new round starts after a completed round when betting opens again.
18. Personal bet settings (side, stake, personal odds) are live during betting and frozen at
    betting lock/first card. Later edits never change an earlier round's history.
19. Winner states are explicit:
    - `WINNER_CONFIRMED`: repeated explicit OCR result wording, or OCR plus stable visual evidence
    - `VISUAL_WINNER_CANDIDATE`: only the visual highlight changed; never used for money
    - `RESULT_NOT_DETECTED`: no reliable result evidence
    - `RESULT_CONFLICT`: OCR/visual evidence disagrees
20. A completed record is emitted once per round. Repeated result frames and duplicate records with
    a different ID inside a short time window are rejected by the local store.
21. P/L is calculated only with a confirmed winner, a valid personal side, a positive stake, and
    valid personal odds. Missing data leaves P/L unavailable but still saves the round record.

### Local data
22. SharedPreferences + JSON persistence, no Room and no network dependency.
23. Up to 500 round records, 300 changed OCR snapshots, and 200 status messages are retained.
24. Older text-only history snapshots are still readable.
25. Dashboard, history, diagnostics, settings, and overall statistics (wins/losses, stake, profit,
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
- OCR stage, label, total, odds, percentage, card-count, balance, amount, and winner parsing
- both-side total rejection, bare-number rejection, and Bengali digit normalization
- synthetic card-shape detection, separate Andar/Bahar zones, duplicate candidates, adjacent
  identical cards, movement, stability, uncertainty, reset, and restore
- money previous/current/difference tracking and independent sides
- winner confirmation timing, visual-only candidates, conflicts, sticky results, visual streaks,
  blinks, and open-betting suppression
- bet freeze, round transitions, monotonic card counts, stale-card grace, missing bet data, and
  one-record-per-round completion
- win/loss/net P/L accounting, overall statistics, and duplicate-result prevention

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
`Game-X-Ray-debug-v0.3.0` artifact.

## Use

1. Install the APK.
2. Allow the floating-window permission.
3. Tap Start screen capture and approve Android's screen-capture prompt.
4. Switch to the game. The floating panel shows OCR status, card counts, panel totals, odds,
   winner state, and P/L.
5. In My Bet Settings, enter your own side, stake, and personal odds. Do not enter public panel
   totals as your stake.
6. Refresh the dashboard/history to inspect saved rounds and diagnostics. Tap STOP on the floating
   panel to stop capture.

Android displays a persistent notification during screen capture. Captured frames are processed
on-device and are not uploaded by this app.

## Important limitations and real-game validation

- **OCR is not a guaranteed card/rank/suit detector.** Card counting uses visible image shapes
  inside the OCR-located Andar/Bahar zones, not OCR of card faces.
- **Visual card/highlight detection has only been validated with synthetic test images** in this
  repository. It has not been verified against screenshots from the real game. Real game layouts,
  overlays, animations, compression artifacts, and card styling may require threshold tuning.
- Panel totals and odds depend on the game's actual labels and OCR quality. Ambiguous values stay
  unavailable rather than being guessed.
- A winner is confirmed only from repeated explicit result wording or OCR/visual agreement. A
  visual-only highlight is shown as a candidate and never used for P/L.
- Statistics describe saved, confirmed results. They are not predictions of future outcomes.
- P/L uses the personal side, stake, and odds entered by the user. It is not financial advice and
  cannot guarantee a result.
- All OCR text and history are stored locally on the device. The app has no network/API/WebSocket
  feature.
- To finish real-game validation, capture test screenshots for: betting open, betting locked,
  2–10 cards on each side, card movement/animation, hidden card faces, a visual-only winner, an
  explicit `ANDAR WINS` / `BAHAR WINS` result, and a conflicting/ambiguous result screen.
