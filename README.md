# Game X-Ray Android

A local Android screen-capture and OCR monitor. This is not a finished card detector, winner detector, or betting prediction engine.

## Current features
- Android MediaProjection screen capture after explicit user approval.
- Draggable floating panel with live OCR text.
- On-device ML Kit Latin text recognition.
- Scan counter persisted locally on the phone.
- Latest 50 changed OCR text snapshots saved locally.
- Floating panel window marked secure to reduce the chance that the panel's own text is included in captured OCR.

## Current limitations
- OCR reads the full captured screen; game-only region selection is not implemented.
- Snapshots are saved when the complete recognized text changes, not on every frame or every round.
- Card rank/suit recognition, chip recognition, round parsing, winner detection, betting analytics, and prediction are not implemented.
- OCR output can be incomplete or noisy. It must not be treated as verified game data.
- The app cannot know or guarantee future Andar-Bahar outcomes.

## Build
Open in Android Studio and run `assembleDebug`, or push this project to GitHub.
The included GitHub Actions workflow builds and verifies a debug APK on push or manual run.
Download the `Game-X-Ray-debug-v0.1.2` artifact from the workflow run.

## Use
1. Install the APK.
2. Allow "Display over other apps".
3. Tap Start screen capture and approve Android's capture prompt.
4. Switch to the game. The overlay shows locally recognized screen text.
5. Tap STOP to end capture.

Android shows a persistent notification while screen capture is active. Captured text history stays on this phone and is not uploaded by this app.
