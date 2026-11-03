# Game X-Ray Android starter

This is a starter Android project, not a finished game-result detector.

## Current features
- Android MediaProjection screen capture after explicit user approval.
- Draggable floating panel.
- On-device ML Kit OCR for visible text.
- Live display of changing recognized text.

## Not implemented yet
- Reliable blue/red winner highlight recognition.
- Market amount extraction validation.
- Payout and company profit/loss calculations.
- Bypassing protected or blocked capture surfaces.

## Build
Open in Android Studio and run `assembleDebug`, or push this project to GitHub.
The included GitHub Actions workflow builds a debug APK on push or manual run.
Download the `Game-X-Ray-debug` artifact from the workflow run.

## Use
1. Install the APK.
2. Allow "Display over other apps".
3. Tap Start screen capture and approve Android's capture prompt.
4. Switch to the game. A draggable overlay shows locally recognized screen text.
5. Tap STOP to end capture.

Android shows a persistent notification while screen capture is active.
