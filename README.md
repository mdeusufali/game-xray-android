# Game X-Ray Android

Game X-Ray is a local Android screen-capture and OCR dashboard for the visible Andar-Bahar game screen. It uses Android MediaProjection (with user approval) and on-device ML Kit Latin OCR. It does not connect to game APIs, WebSockets, or web history.

## Implemented features
1. Screen capture and on-device OCR.
2. PLACE BET / BET LOCKED stage detection (BET LOCKED takes precedence).
3. Separate Andar and Bahar panel-total extraction; percentages and odds are rejected as totals.
4. Personal stake stored separately from public panel totals.
5. Side-specific visible odds parsing for Andar and Bahar.
6. CARDS DEALT counter parsing with 1–52 validation.
7. Historical Andar/Bahar percentages are displayed only when OCR values validate and sum to approximately 100%; saved-result statistics use up to the last 200 confirmed records.
8. Local round/snapshot history, retained up to 500 round records and 1000 changed OCR snapshots.
9. Winner is saved only when explicit result wording (WINS/WON/WINNER/RESULT) and exactly one side appear on the same OCR line.
10. P/L is calculated only when the user has entered their own side, stake and personal odds and an explicit winner is captured. Public totals and nearby odds are never substituted for personal bet details.
11. OCR values are validated; uncertain totals, percentages, odds, and winner values remain "not detected"/unavailable.
12. Dashboard, round history, latest OCR snapshots and diagnostic text are available in the app.
13. Android overlay and MediaProjection permission flow, foreground capture service, and GitHub Actions APK build/verification workflow.

## Important limitations
- OCR is not a guaranteed card/rank/suit detector. The app only reads text visible on the screen.
- Panel totals and side odds depend on the game's exact visible labels and OCR quality; ambiguous values remain unavailable rather than guessed.
- A winner is recorded only when the screen contains an explicit result phrase on the same OCR line as ANDAR or BAHAR. If the game uses a visual-only result, it will not be recorded.
- The 200-round statistics are calculated from confirmed, saved OCR results. They are not a prediction of future outcomes.
- P/L uses the personal side, stake and odds that the user manually enters. It is not financial advice and cannot predict or guarantee a win.
- All OCR text and history are stored locally on the device; this app has no network/API/WebSocket feature.

## Build
Push to `main` or manually run the GitHub Actions workflow. It runs `gradle clean assembleDebug`, verifies the APK signature and package metadata, and uploads the debug APK as the `Game-X-Ray-debug-v0.2.0` artifact.

## Use
1. Install the APK.
2. Allow the floating-window permission.
3. Tap Start screen capture and approve Android's screen-capture prompt.
4. Switch to the game. The floating panel shows OCR status.
5. In My Bet Settings, enter your own side, stake, and (if known) your personal odds. Do not enter public panel totals as your stake.
6. Refresh the dashboard/history to inspect captured data. Tap STOP on the floating panel to stop capture.

Android displays a persistent notification during screen capture. Captured screen text is processed on-device and is not uploaded by this app.
