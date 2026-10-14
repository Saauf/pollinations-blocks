package ai.saauf.pollinations.helpers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import org.json.JSONArray;
import org.json.JSONObject;

public final class PollinationsApi {
  public static final class Reply {
    public final int status;
    public final String contentType;
    public final byte[] body;

    Reply(int status, String contentType, byte[] body) {
      this.status = status;
      this.contentType = contentType;
      this.body = body;
    }

    public JSONObject json() throws Exception {
      return new JSONObject(new String(body, "UTF-8"));
    }
  }

  private final String gen;
  private final String auth;

  public PollinationsApi() {
    this("https://gen.pollinations.ai", "https://enter.pollinations.ai");
  }

  public PollinationsApi(String gen, String auth) {
    this.gen = gen;
    this.auth = auth;
  }

  public static byte[] read(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] chunk = new byte[8192];
    int count;
    while ((count = in.read(chunk)) != -1) {
      if (out.size() + count > 32 * 1024 * 1024) throw new IOException("File exceeds 32 MiB");
      out.write(chunk, 0, count);
    }
    return out.toByteArray();
  }

  private Reply request(String base, String path, String key, byte[] body, String contentType)
      throws Exception {
    HttpURLConnection connection = (HttpURLConnection) new URL(base + path).openConnection();
    connection.setInstanceFollowRedirects(false);
    connection.setConnectTimeout(15000);
    connection.setReadTimeout(base.equals(auth) ? 30000 : 300000);
    if (key.length() > 0) connection.setRequestProperty("Authorization", "Bearer " + key);
    connection.setRequestProperty("X-Polli-Client", "pollinations-blocks/1");
    try {
      if (body != null) {
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", contentType);
        connection.setFixedLengthStreamingMode(body.length);
        OutputStream out = connection.getOutputStream();
        try {
          out.write(body);
        } finally {
          out.close();
        }
      }
      int status = connection.getResponseCode();
      InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
      byte[] bytes = new byte[0];
      if (in != null) {
        try {
          bytes = read(in);
        } finally {
          in.close();
        }
      }
      String type = connection.getContentType();
      return new Reply(
          status, type == null ? "application/octet-stream" : type.split(";")[0], bytes);
    } finally {
      connection.disconnect();
    }
  }

  private Reply checked(Reply reply, String key) throws Exception {
    if (reply.status < 200 || reply.status >= 300) {
      String message = "HTTP " + reply.status;
      try {
        JSONObject json = reply.json();
        Object error = json.opt("error");
        if (error instanceof JSONObject)
          message += ": " + ((JSONObject) error).optString("message", "Request failed");
        else if (error instanceof String) message += ": " + error;
      } catch (Exception ignored) {
      }
      if (key.length() > 0) message = message.replace(key, "[redacted]");
      throw new IOException(message);
    }
    return reply;
  }

  public Reply post(String path, String key, JSONObject body) throws Exception {
    return checked(
        request(gen, path, key, body.toString().getBytes("UTF-8"), "application/json"), key);
  }

  public Reply models(String kind) throws Exception {
    if (!(kind.equals("text")
        || kind.equals("image")
        || kind.equals("audio")
        || kind.equals("video")
        || kind.equals("3d")))
      throw new IOException("Model kind must be text, image, audio, video or 3d");
    return checked(request(gen, "/" + kind + "/models", "", null, ""), "");
  }

  public String chat(String key, String model, JSONArray messages) throws Exception {
    JSONObject body = new JSONObject().put("messages", messages);
    if (model.length() > 0) body.put("model", model);
    return post("/v1/chat/completions", key, body)
        .json()
        .getJSONArray("choices")
        .getJSONObject(0)
        .getJSONObject("message")
        .getString("content");
  }

  public Reply image(String key, String prompt, String model) throws Exception {
    String path = "/image/" + URLEncoder.encode(prompt, "UTF-8").replace("+", "%20");
    if (model.length() > 0) path += "?model=" + URLEncoder.encode(model, "UTF-8");
    return checked(request(gen, path, key, null, ""), key);
  }

  public Reply speech(String key, String text, String model, String voice) throws Exception {
    JSONObject body = new JSONObject().put("input", text);
    if (model.length() > 0) body.put("model", model);
    if (voice.length() > 0) body.put("voice", voice);
    return post("/v1/audio/speech", key, body);
  }

  public String transcribe(String key, byte[] audio, String mime, String model) throws Exception {
    String boundary = "pollinations" + java.util.UUID.randomUUID().toString().replace("-", "");
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    if (model.indexOf('\r') >= 0 || model.indexOf('\n') >= 0)
      throw new IOException("Invalid model name");
    if (!mime.matches("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+")) mime = "application/octet-stream";
    String suffix =
        mime.equals("audio/wav") || mime.equals("audio/x-wav")
            ? ".wav"
            : mime.equals("audio/mpeg")
                ? ".mp3"
                : mime.equals("audio/ogg") ? ".ogg" : mime.equals("audio/mp4") ? ".m4a" : ".bin";
    if (model.length() > 0)
      body.write(
          ("--"
                  + boundary
                  + "\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n"
                  + model
                  + "\r\n")
              .getBytes("UTF-8"));
    body.write(
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"recording"
                + suffix
                + "\"\r\nContent-Type: "
                + mime
                + "\r\n\r\n")
            .getBytes("UTF-8"));
    body.write(audio);
    body.write(("\r\n--" + boundary + "--\r\n").getBytes("UTF-8"));
    return checked(
            request(
                gen,
                "/v1/audio/transcriptions",
                key,
                body.toByteArray(),
                "multipart/form-data; boundary=" + boundary),
            key)
        .json()
        .getString("text");
  }

  public JSONObject startSignIn(String appKey) throws Exception {
    JSONObject body = new JSONObject().put("scope", "");
    if (appKey.length() > 0) body.put("client_id", appKey);
    return checked(
            request(
                auth,
                "/api/device/code",
                "",
                body.toString().getBytes("UTF-8"),
                "application/json"),
            "")
        .json();
  }

  public Reply pollSignIn(String deviceCode) throws Exception {
    JSONObject body = new JSONObject().put("device_code", deviceCode);
    return request(
        auth, "/api/device/token", "", body.toString().getBytes("UTF-8"), "application/json");
  }
}
