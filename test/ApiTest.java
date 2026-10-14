import ai.saauf.pollinations.helpers.DeviceFlow;
import ai.saauf.pollinations.helpers.PollinationsApi;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ApiTest {
  static final List<String> paths = new ArrayList<String>();
  static final List<String> bodies = new ArrayList<String>();
  static final List<String> keys = new ArrayList<String>();
  static final AtomicInteger polls = new AtomicInteger();
  static String pollMode = "pending";
  static int expiry = 20;
  static final List<Long> pollTimes = new ArrayList<Long>();
  static int checks;

  static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
    checks++;
  }

  static void handle(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().toString();
    String body = new String(PollinationsApi.read(exchange.getRequestBody()), "UTF-8");
    paths.add(path);
    bodies.add(body);
    keys.add(exchange.getRequestHeaders().getFirst("Authorization"));
    String json = "{}";
    String type = "application/json";
    int status = 200;
    if (path.equals("/text/models")) json = "[{\"name\":\"dynamic-model\"}]";
    else if (path.equals("/v1/chat/completions"))
      json = "{\"choices\":[{\"message\":{\"content\":\"Hello\"}}]}";
    else if (path.startsWith("/image/")) {
      json = "JPEG";
      type = "image/jpeg";
    } else if (path.equals("/v1/audio/speech")) {
      json = "ID3audio";
      type = "audio/mpeg";
    } else if (path.equals("/v1/audio/transcriptions")) json = "{\"text\":\"transcribed\"}";
    else if (path.equals("/api/device/code")) {
      polls.set(0);
      pollTimes.clear();
      json =
          "{\"user_code\":\"TEST-1234\",\"device_code\":\"private-device-code\",\"verification_uri_complete\":\"https://enter.pollinations.ai/device?user_code=TEST-1234\",\"interval\":1,\"expires_in\":"
              + expiry
              + "}";
    } else if (path.equals("/api/device/token")) {
      int count = polls.incrementAndGet();
      pollTimes.add(System.nanoTime());
      if (pollMode.equals("denied")) {
        status = 400;
        json = "{\"error\":\"access_denied\"}";
      } else if (count == 1) {
        status = 400;
        json =
            pollMode.equals("slow")
                ? "{\"error\":\"slow_down\"}"
                : "{\"error\":\"authorization_pending\"}";
      } else json = "{\"access_token\":\"user-token\"}";
    } else {
      status = 503;
      json = "{\"error\":{\"message\":\"temporary failure sk_test\"}}";
    }
    byte[] bytes = json.getBytes("UTF-8");
    exchange.getResponseHeaders().set("Content-Type", type);
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  public static void main(String[] args) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        new com.sun.net.httpserver.HttpHandler() {
          public void handle(HttpExchange e) throws IOException {
            ApiTest.handle(e);
          }
        });
    server.start();
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    PollinationsApi api = new PollinationsApi(base, base);
    try {
      check(
          new JSONArray(new String(api.models("text").body, "UTF-8"))
              .getJSONObject(0)
              .getString("name")
              .equals("dynamic-model"),
          "live catalog decoding");
      check(keys.get(0) == null, "catalog must not send a credential");
      int before = paths.size();
      try {
        api.models("../../account/keys");
        throw new AssertionError("invalid model kind");
      } catch (IOException expected) {
      }
      check(paths.size() == before, "reject invalid kind without a request");
      JSONArray messages =
          new JSONArray().put(new JSONObject().put("role", "user").put("content", "hello"));
      check(api.chat("sk_test", "", messages).equals("Hello"), "text result");
      JSONObject posted = new JSONObject(bodies.get(bodies.size() - 1));
      check(!posted.has("model"), "preserve server model default");
      check(
          posted.getJSONArray("messages").getJSONObject(0).getString("content").equals("hello"),
          "message body");
      PollinationsApi.Reply image = api.image("sk_test", "blue / cat?", "vendor/model");
      check(image.contentType.equals("image/jpeg"), "actual image MIME");
      check(new String(image.body, "UTF-8").equals("JPEG"), "image bytes");
      check(
          paths.get(paths.size() - 1).equals("/image/blue%20%2F%20cat%3F?model=vendor%2Fmodel"),
          "encoded path and model");
      check(api.speech("sk_test", "Hi", "", "").contentType.equals("audio/mpeg"), "speech MIME");
      posted = new JSONObject(bodies.get(bodies.size() - 1));
      check(
          posted.length() == 1 && posted.getString("input").equals("Hi"), "speech server defaults");
      check(
          api.transcribe("sk_test", new byte[] {0, 1, 2}, "audio/wav", "").equals("transcribed"),
          "transcription result");
      String multipart = bodies.get(bodies.size() - 1);
      check(multipart.contains("filename=\"recording.wav\""), "recording filename extension");
      check(multipart.contains("Content-Type: audio/wav"), "recording MIME preserved");
      check(!multipart.contains("name=\"model\""), "omit empty transcription model");
      check(
          api.transcribe("sk_test", new byte[] {0, 1, 2}, "audio/mp4", "").equals("transcribed"),
          "M4A transcription result");
      multipart = bodies.get(bodies.size() - 1);
      check(multipart.contains("filename=\"recording.m4a\""), "M4A filename extension");
      check(multipart.contains("Content-Type: audio/mp4"), "M4A MIME preserved");
      for (int i = 1; i < paths.size(); i++) {
        check(keys.get(i).equals("Bearer sk_test"), "authorization header");
        check(
            !paths.get(i).contains("sk_test") && !bodies.get(i).contains("sk_test"),
            "no key in URL or body");
      }
      try {
        api.post("/error", "sk_test", new JSONObject());
        throw new AssertionError("missing API error");
      } catch (IOException error) {
        check(error.getMessage().contains("503"), "HTTP status in errors");
        check(!error.getMessage().contains("sk_test"), "redacted API errors");
      }
      final List<String> userCodes = new ArrayList<String>();
      DeviceFlow.Listener listener =
          new DeviceFlow.Listener() {
            public void code(JSONObject code) throws Exception {
              userCodes.add(code.getString("user_code"));
            }

            public boolean cancelled() {
              return false;
            }
          };
      check(
          DeviceFlow.signIn(api, "pk_public", listener).equals("user-token"), "device-flow token");
      check(polls.get() == 2, "pending then approved");
      check(pollTimes.get(1) - pollTimes.get(0) >= 950000000L, "returned polling interval");
      check(userCodes.get(0).equals("TEST-1234"), "public sign-in code");
      int start = paths.lastIndexOf("/api/device/code");
      posted = new JSONObject(bodies.get(start));
      check(
          posted.getString("scope").equals("") && posted.getString("client_id").equals("pk_public"),
          "no identity or account scopes");
      check(
          keys.get(start) == null && keys.get(start + 1) == null,
          "device flow does not send user key");
      pollMode = "slow";
      check(DeviceFlow.signIn(api, "", listener).equals("user-token"), "slow-down recovery");
      check(pollTimes.get(1) - pollTimes.get(0) >= 5950000000L, "slow-down adds five seconds");
      pollMode = "denied";
      try {
        DeviceFlow.signIn(api, "", listener);
        throw new AssertionError("denial not surfaced");
      } catch (IOException error) {
        check(error.getMessage().equals("access_denied"), "authorization denial");
      }
      expiry = 0;
      try {
        DeviceFlow.signIn(api, "", listener);
        throw new AssertionError("expiry not surfaced");
      } catch (IOException error) {
        check(error.getMessage().contains("expired"), "expiry");
      }
      check(polls.get() == 0, "expired attempt must not poll");
      expiry = 20;
      try {
        DeviceFlow.signIn(
            api,
            "",
            new DeviceFlow.Listener() {
              public void code(JSONObject code) {}

              public boolean cancelled() {
                return true;
              }
            });
        throw new AssertionError("cancel not surfaced");
      } catch (IOException error) {
        check(error.getMessage().contains("cancelled"), "cancel");
      }
      check(polls.get() == 0, "cancelled attempt must not poll");
      System.out.println(checks + " HTTP and device-flow checks passed");
    } finally {
      server.stop(0);
    }
  }
}
