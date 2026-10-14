# Pollinations Blocks

[![Made with pollinations.ai](https://raw.githubusercontent.com/pollinations/pollinations/main/packages/ui/src/brand/badge-made-with.svg)](https://pollinations.ai/?ref=badge)

An Android extension for MIT App Inventor and Kodular. Use blocks for text, images, speech, transcription and questions about a picked image. Each person signs in to use their own Pollen. The sample contains no user API key.

## Try the sample

Import `sample/validated/Pollinations-AppInventor.aia` or `sample/validated/Pollinations-Kodular.aia` into its matching editor. Each includes the extension and its blocks. For an existing project, import `dist/ai.saauf.pollinations.aix` from the Extension palette and add a Pollinations component. `sample/PollinationsDemo.aia` is the generated sample used for these imports.

The sample was imported into both online editors and compiled to an APK in each on October 14, 2026. Both block workspaces showed zero errors and warnings. Android runtime checks are still pending; editor compilation does not verify microphone access, sign-in, playback or generation on a device.

1. Tap **Show text models** to check the live catalog without signing in.
2. Tap **Sign in**. The browser opens Pollinations' device consent page. Review the permitted models, expiry and Pollen budget there before allowing access.
3. Return to the app after approval. The status changes when polling receives the user key.
4. Enter a model ID appropriate to the operation, or leave it empty for the API default. The same Model field is shared by text, image, speech and transcription.
5. Use **Ask**, **Generate image**, **Speak**, or **Pick image and ask**. Use **Record audio** followed by **Stop recording** to record an M4A file and send it for transcription. Recording requests microphone permission only when selected.
6. **Sign out** removes the key from this app. Revoke it in the Pollinations dashboard if you also want to invalidate the server-side credential.

Generation consumes the signed-in user's Pollen and is subject to their model permissions and budget. This extension does not buy Pollen, change billing settings or make generation free. A rejected permission or exhausted budget appears in the Error event.

## Blocks

| Block | Result |
| --- | --- |
| `SignIn` | Opens device consent; emits `SignInCode`, then `SignInComplete` on approval |
| `SignOut` | Clears the local user key and discards pending results |
| `SignedIn` | Whether a key is stored locally; does not prove it is still valid |
| `AppKey` | Optional publishable `pk_` app key for attribution; never enter a user secret |
| `AskText(prompt, model)` | `Result("text", text)` |
| `Chat(messagesJson, model)` | `Result("chat", text)` for an OpenAI-compatible JSON messages array |
| `AskAboutImage(prompt, imagePath, model)` | `Result("vision", text)`; use a vision-capable model |
| `GenerateImage(prompt, model)` | `FileReady("image", path, mimeType)` |
| `Speak(text, model, voice)` | `FileReady("speech", path, mimeType)` |
| `RecordAudio`, `StopRecording` | `RecordingReady(path)` with a private M4A recording |
| `Transcribe(audioPath, model)` | `Result("transcribe", text)` |
| `LoadModels(kind)` | `Result("models", JSON)`; kind is text, image, audio, video or 3d |
| `Cancel` | Stops sign-in polling, aborts an active recording and discards pending request results |

All failures emit `Error(operation, message)`. Native event callbacks run on the UI thread. `FileReady` files live in the private app cache and may be removed by Android. Requests already sent to the API can finish and be billed after Cancel or SignOut; cancellation does not cancel a provider job.

Use the current [text](https://gen.pollinations.ai/text/models), [image](https://gen.pollinations.ai/image/models) and [audio](https://gen.pollinations.ai/audio/models) catalogs. Model capabilities, prices and account restrictions can change. Video and 3D catalogs can be loaded, but this extension has no video or 3D generation block.

## Sign-in and files

Device sign-in follows [Bring your own Pollen](https://github.com/pollinations/pollinations/blob/main/BRING_YOUR_OWN_POLLEN.md). It requests no profile, key-management or account-management scopes. The consent page controls generation access. Polling respects the returned interval and backs off on `slow_down`.

The resulting user key stays in private Android SharedPreferences, not in the designer, exported project, URL or result events. Requests send it only as an Authorization header to `gen.pollinations.ai`. Redirects are disabled. SignOut and lifecycle cleanup discard stale results; local recording stops when cancelled or the component is destroyed. Android backup or a compromised device can still expose app data; private storage is not an encrypted credential vault. Avoid using Companion with sensitive credentials; compiled apps have their own sandbox.

Selected media is read through App Inventor's media utilities and sent to the generation API. Files and responses are limited to 32 MiB. The built-in recorder uses AAC in an M4A container because App Inventor's SoundRecorder uses 3GP, which is absent from the documented transcription formats.

## Build and checks

Use Python 3, Git and a JDK 8 installation. Initialize the pinned official App Inventor dependencies and run:

```sh
git submodule update --init --depth 1
python3 scripts/check.py --jdk /path/to/jdk8
```

The check script downloads checksum-pinned Ant and the test-only JSON library from Maven Central, builds the AIX with the official extension template, runs local HTTP/device-flow integration checks, generates the sample, and validates its descriptors and block references. No account key or provider request is needed. Test dependencies are not bundled into the extension.

`sample/validated/` contains editor-exported projects. The generated sample remains available for rebuilding. See `VERIFICATION.md` for the exact tested artifacts and remaining device checks.

## Source and attribution

Extension source, helper scripts, tests and sample blocks are by Saauf and licensed under the scoped MIT license in `LICENSE`. The build setup comes from [MIT's extension template](https://github.com/mit-cml/extension-template); its original README is preserved as `TEMPLATE.md`. App Inventor dependencies retain their upstream licenses and pinned submodule revision. This is an independent extension, not an official Pollinations or MIT product.
