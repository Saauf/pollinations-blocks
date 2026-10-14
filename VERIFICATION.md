# Verification

Checked on October 14, 2026. No provider generation, Pollinations account authorization or Pollen spending was performed during these checks.

## Local build and protocol checks

- Built the Android extension with MIT's official extension template, JDK 8 and Ant 1.10.15.
- App Inventor dependencies are pinned to `78dc0a61b5f437ff6b54dc3998921fa599bea20f`.
- 43 checks passed against a local HTTP server. They cover request bodies, header-only credentials, model catalogs without authentication, prompt encoding, image/speech bytes and MIME types, WAV/M4A multipart uploads, error redaction, and device-flow pending/approved/denied/expired/cancelled/slow-down behavior.
- Device polling uses the interval returned by the server and adds five seconds after `slow_down`.
- Generated sample structure, component names, extension methods/events and archive contents passed validation. The sample excludes legacy singular descriptors that the official extension importer removes.

These checks exercise real Java HTTP connections to a local fixture. They do not execute Android MediaRecorder, SharedPreferences, permission prompts or Activity callbacks.

## Online editor checks

The final sample, including native AAC/M4A recording blocks, was imported into both signed-in editors. Each displayed the Pollinations component and loaded all sample blocks with zero block errors and warnings. Each compiled an Android APK successfully.

| Editor | Exported project | Version recorded in export |
| --- | --- | --- |
| MIT App Inventor | `sample/validated/Pollinations-AppInventor.aia` | YaVersion 237; Form 32 |
| Kodular Creator | `sample/validated/Pollinations-Kodular.aia` | YaVersion 247; Form 46 |

The exported projects contain the same `AndroidRuntime.jar` as `dist/ai.saauf.pollinations.aix`. They contain no keystore or credential file. The first sample import exposed an incorrect fully qualified component name; the final project uses the short name exported by the editor. The official SoundRecorder's 3GP format was also replaced with an AAC/M4A recorder to match the transcription API's documented formats.

Compiler success proves the editor can generate and link an APK. It does not prove Android runtime behavior or successful provider generation.

## Remaining Android checks

No Android phone or emulator was available. Before submitting a claim that the extension works end to end:

1. Install each editor's APK and launch it. Confirm all controls and the extension initialize.
2. Load the text catalog while signed out. Try Ask while signed out and confirm an actionable error.
3. Start sign-in and cancel it. Confirm the code stops polling and no late callback changes the app state.
4. Approve a device session manually after reviewing its expiry, permitted models and Pollen budget. Confirm that these match the intended test before allowing access.
5. Verify successful sign-in, persistence after restarting the app, then SignOut. Confirm stale requests cannot deliver results after signing out or switching users.
6. Grant and deny microphone permission. Record and stop a short M4A clip; confirm its MIME type and transcription behavior with a permitted model.
7. Test a picked image with a vision model, generated image display and speech playback under a separately approved generation budget. Record actual model IDs and any provider restrictions.
8. Capture a real app screenshot without codes, keys or account details. Use a GitHub-hosted image for the app submission.

The sample automatically submits a finished recording for transcription. Use it signed out for microphone/file checks that must not consume Pollen. Cancel discards pending results; a request already sent can still be billed.
