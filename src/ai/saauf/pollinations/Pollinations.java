package ai.saauf.pollinations;

import ai.saauf.pollinations.helpers.DeviceFlow;
import ai.saauf.pollinations.helpers.PollinationsApi;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaRecorder;
import android.net.Uri;
import android.util.Base64;
import com.google.appinventor.components.annotations.*;
import com.google.appinventor.components.common.ComponentCategory;
import com.google.appinventor.components.runtime.*;
import com.google.appinventor.components.runtime.util.AsynchUtil;
import com.google.appinventor.components.runtime.util.MediaUtil;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

@DesignerComponent(
    version = 1,
    description = "Text, images, speech and user-owned Pollen for block-based apps.",
    category = ComponentCategory.EXTENSION,
    nonVisible = true,
    iconName = "images/extension.png")
@SimpleObject(external = true)
@UsesPermissions(permissionNames = "android.permission.INTERNET, android.permission.RECORD_AUDIO")
public final class Pollinations extends AndroidNonvisibleComponent
    implements OnDestroyListener, Deleteable {
  private final Activity activity;
  private final SharedPreferences preferences;
  private final PollinationsApi api = new PollinationsApi();
  private volatile int session;
  private String key;
  private String appKey = "";
  private MediaRecorder recorder;
  private File recording;

  public Pollinations(ComponentContainer container) {
    super(container.$form());
    activity = container.$context();
    preferences = activity.getSharedPreferences("pollinations-blocks", Context.MODE_PRIVATE);
    key = preferences.getString("key", "");
    form.registerForOnDestroy(this);
  }

  @DesignerProperty(editorType = "string", defaultValue = "")
  @SimpleProperty(
      category = PropertyCategory.BEHAVIOR,
      description =
          "Optional publishable App Key (pk_...) for app attribution. Never put a user key here.")
  public void AppKey(String value) {
    appKey = value.trim();
  }

  @SimpleProperty(category = PropertyCategory.BEHAVIOR)
  public String AppKey() {
    return appKey;
  }

  @SimpleProperty(
      category = PropertyCategory.BEHAVIOR,
      description =
          "Whether a user key is stored on this device. Expired or revoked keys produce an API error.")
  public boolean SignedIn() {
    return key.length() > 0;
  }

  @SimpleEvent(description = "The code and consent page for the current sign-in attempt.")
  public void SignInCode(String code, String verificationUrl) {
    EventDispatcher.dispatchEvent(this, "SignInCode", code, verificationUrl);
  }

  @SimpleEvent(description = "The user key is stored privately and ready for requests.")
  public void SignInComplete() {
    EventDispatcher.dispatchEvent(this, "SignInComplete");
  }

  @SimpleEvent(description = "Text, chat, vision, transcription or model-catalog result.")
  public void Result(String operation, String text) {
    EventDispatcher.dispatchEvent(this, "Result", operation, text);
  }

  @SimpleEvent(description = "A generated file in private app cache, with its actual MIME type.")
  public void FileReady(String operation, String path, String mimeType) {
    EventDispatcher.dispatchEvent(this, "FileReady", operation, path, mimeType);
  }

  @SimpleEvent(
      description = "A recording is ready in private app cache. Pass its path to Transcribe.")
  public void RecordingReady(String path) {
    EventDispatcher.dispatchEvent(this, "RecordingReady", path);
  }

  @SimpleFunction(
      description = "Ask for microphone permission and record AAC audio in an M4A file.")
  public void RecordAudio() {
    final int expected = session;
    form.askPermission(
        "android.permission.RECORD_AUDIO",
        new PermissionResultHandler() {
          public void HandlePermissionResponse(String permission, boolean granted) {
            if (session != expected) return;
            if (!granted) {
              Error("record", "Microphone permission was denied");
              return;
            }
            if (recorder != null) {
              Error("record", "A recording is already in progress");
              return;
            }
            try {
              recording =
                  new File(activity.getCacheDir(), "pollinations-" + UUID.randomUUID() + ".m4a");
              recorder = new MediaRecorder();
              recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
              recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
              recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
              recorder.setOutputFile(recording.getAbsolutePath());
              recorder.prepare();
              recorder.start();
            } catch (Exception error) {
              abortRecording();
              Error("record", "Could not start recording");
            }
          }
        });
  }

  @SimpleFunction(description = "Stop recording and return its private M4A path in RecordingReady.")
  public void StopRecording() {
    if (recorder == null) {
      Error("record", "No recording is in progress");
      return;
    }
    File saved = recording;
    try {
      recorder.stop();
      releaseRecorder();
      recording = null;
      RecordingReady(saved.getAbsolutePath());
    } catch (Exception error) {
      releaseRecorder();
      saved.delete();
      recording = null;
      Error("record", "Recording was too short or could not be saved");
    }
  }

  private void releaseRecorder() {
    if (recorder != null) {
      recorder.release();
      recorder = null;
    }
  }

  private void abortRecording() {
    releaseRecorder();
    if (recording != null) {
      recording.delete();
      recording = null;
    }
  }

  @SimpleEvent(description = "An operation failed. The message never contains the user key.")
  public void Error(String operation, String message) {
    EventDispatcher.dispatchEvent(this, "Error", operation, message);
  }

  private void post(final int expected, final Runnable action) {
    activity.runOnUiThread(
        new Runnable() {
          public void run() {
            if (session == expected) action.run();
          }
        });
  }

  private void fail(final int expected, final String operation, final Exception error) {
    post(
        expected,
        new Runnable() {
          public void run() {
            String message = error.getMessage();
            Error(operation, message == null ? "Request failed" : message);
          }
        });
  }

  @SimpleFunction(
      description =
          "Open the user's Pollen consent page. Polling respects the server interval; no profile or account-management access is requested.")
  public void SignIn() {
    final int expected = ++session;
    final String clientId = appKey;
    AsynchUtil.runAsynchronously(
        new Runnable() {
          public void run() {
            try {
              final String token =
                  DeviceFlow.signIn(
                      api,
                      clientId,
                      new DeviceFlow.Listener() {
                        public boolean cancelled() {
                          return session != expected;
                        }

                        public void code(final JSONObject code) throws Exception {
                          final String verification = code.getString("verification_uri_complete");
                          final Uri uri = Uri.parse(verification);
                          if (!"https".equals(uri.getScheme())
                              || !"enter.pollinations.ai".equals(uri.getHost()))
                            throw new Exception("Invalid sign-in destination");
                          post(
                              expected,
                              new Runnable() {
                                public void run() {
                                  SignInCode(code.optString("user_code"), verification);
                                  activity.startActivity(new Intent(Intent.ACTION_VIEW, uri));
                                }
                              });
                        }
                      });
              post(
                  expected,
                  new Runnable() {
                    public void run() {
                      session++;
                      preferences.edit().putString("key", token).apply();
                      key = token;
                      SignInComplete();
                    }
                  });
            } catch (Exception error) {
              fail(expected, "signin", error);
            }
          }
        });
  }

  @SimpleFunction(
      description =
          "Clear the local user key and discard pending results. This does not revoke the key in the user's dashboard.")
  public void SignOut() {
    session++;
    abortRecording();
    key = "";
    preferences.edit().remove("key").apply();
  }

  @SimpleFunction(
      description =
          "Stop sign-in polling and discard results of requests already in progress. A dispatched generation may still be billed.")
  public void Cancel() {
    session++;
    abortRecording();
  }

  public void onDestroy() {
    session++;
    abortRecording();
  }

  public void onDelete() {
    session++;
    abortRecording();
  }

  private interface Work {
    Object run(String token) throws Exception;
  }

  private void run(final String operation, boolean needsKey, final Work work) {
    final int expected = session;
    final String token = key;
    if (needsKey && token.length() == 0) {
      Error(operation, "Sign in first");
      return;
    }
    AsynchUtil.runAsynchronously(
        new Runnable() {
          public void run() {
            try {
              Object value = work.run(token);
              if (value instanceof PollinationsApi.Reply) {
                PollinationsApi.Reply reply = (PollinationsApi.Reply) value;
                final String mime = reply.contentType;
                String suffix =
                    mime.equals("image/png")
                        ? ".png"
                        : mime.equals("image/jpeg")
                            ? ".jpg"
                            : mime.equals("image/webp")
                                ? ".webp"
                                : mime.equals("audio/mpeg")
                                    ? ".mp3"
                                    : mime.equals("audio/wav")
                                        ? ".wav"
                                        : mime.equals("audio/ogg") ? ".ogg" : ".bin";
                File file =
                    new File(activity.getCacheDir(), "pollinations-" + UUID.randomUUID() + suffix);
                FileOutputStream out = new FileOutputStream(file);
                try {
                  out.write(reply.body);
                } finally {
                  out.close();
                }
                final String path = file.getAbsolutePath();
                post(
                    expected,
                    new Runnable() {
                      public void run() {
                        FileReady(operation, path, mime);
                      }
                    });
              } else {
                final String result = String.valueOf(value);
                post(
                    expected,
                    new Runnable() {
                      public void run() {
                        Result(operation, result);
                      }
                    });
              }
            } catch (Exception error) {
              fail(expected, operation, error);
            }
          }
        });
  }

  @SimpleFunction(description = "Generate text. Leave model empty to use the server default.")
  public void AskText(final String prompt, final String model) {
    run(
        "text",
        true,
        new Work() {
          public Object run(String token) throws Exception {
            return api.chat(
                token,
                model,
                new JSONArray().put(new JSONObject().put("role", "user").put("content", prompt)));
          }
        });
  }

  @SimpleFunction(description = "Send an OpenAI-compatible JSON messages array.")
  public void Chat(final String messagesJson, final String model) {
    run(
        "chat",
        true,
        new Work() {
          public Object run(String token) throws Exception {
            return api.chat(token, model, new JSONArray(messagesJson));
          }
        });
  }

  @SimpleFunction(
      description =
          "Ask about an image selected by the user. Pick a vision-capable model from the live catalog.")
  public void AskAboutImage(final String prompt, final String imagePath, final String model) {
    run(
        "vision",
        true,
        new Work() {
          public Object run(String token) throws Exception {
            InputStream in = MediaUtil.openMedia(form, imagePath);
            byte[] bytes;
            try {
              bytes = PollinationsApi.read(in);
            } finally {
              in.close();
            }
            String mime =
                java.net.URLConnection.guessContentTypeFromStream(
                    new java.io.ByteArrayInputStream(bytes));
            if (mime == null || !mime.startsWith("image/"))
              throw new Exception("Unsupported image format");
            JSONArray content =
                new JSONArray()
                    .put(new JSONObject().put("type", "text").put("text", prompt))
                    .put(
                        new JSONObject()
                            .put("type", "image_url")
                            .put(
                                "image_url",
                                new JSONObject()
                                    .put(
                                        "url",
                                        "data:"
                                            + mime
                                            + ";base64,"
                                            + Base64.encodeToString(bytes, Base64.NO_WRAP))));
            return api.chat(
                token,
                model,
                new JSONArray().put(new JSONObject().put("role", "user").put("content", content)));
          }
        });
  }

  @SimpleFunction(
      description = "Generate an image; FileReady returns its local path and MIME type.")
  public void GenerateImage(final String prompt, final String model) {
    run(
        "image",
        true,
        new Work() {
          public Object run(String token) throws Exception {
            return api.image(token, prompt, model);
          }
        });
  }

  @SimpleFunction(
      description =
          "Generate speech; FileReady returns the local audio path. Model and voice can be empty for server defaults.")
  public void Speak(final String text, final String model, final String voice) {
    run(
        "speech",
        true,
        new Work() {
          public Object run(String token) throws Exception {
            return api.speech(token, text, model, voice);
          }
        });
  }

  @SimpleFunction(description = "Transcribe a user-selected recording. Result returns text.")
  public void Transcribe(final String audioPath, final String model) {
    run(
        "transcribe",
        true,
        new Work() {
          public Object run(String token) throws Exception {
            InputStream in = MediaUtil.openMedia(form, audioPath);
            byte[] bytes;
            try {
              bytes = PollinationsApi.read(in);
            } finally {
              in.close();
            }
            String mime = activity.getContentResolver().getType(Uri.parse(audioPath));
            if (mime == null && audioPath.toLowerCase(java.util.Locale.US).endsWith(".m4a"))
              mime = "audio/mp4";
            if (mime == null) mime = java.net.URLConnection.guessContentTypeFromName(audioPath);
            return api.transcribe(
                token, bytes, mime == null ? "application/octet-stream" : mime, model);
          }
        });
  }

  @SimpleFunction(
      description =
          "Fetch the live model catalog: text, image, audio, video or 3d. Returns its JSON in the Result event.")
  public void LoadModels(final String kind) {
    run(
        "models",
        false,
        new Work() {
          public Object run(String token) throws Exception {
            return new String(api.models(kind).body, "UTF-8");
          }
        });
  }
}
