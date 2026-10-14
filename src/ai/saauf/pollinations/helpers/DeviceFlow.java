package ai.saauf.pollinations.helpers;

import java.io.IOException;
import org.json.JSONObject;

public final class DeviceFlow {
  public interface Listener {
    void code(JSONObject code) throws Exception;

    boolean cancelled();
  }

  public static String signIn(PollinationsApi api, String appKey, Listener listener)
      throws Exception {
    JSONObject code = api.startSignIn(appKey);
    listener.code(code);
    int interval = Math.max(1, code.optInt("interval", 5));
    long deadline = System.nanoTime() + code.getLong("expires_in") * 1000000000L;
    while (!listener.cancelled() && System.nanoTime() < deadline) {
      Thread.sleep(interval * 1000L);
      if (listener.cancelled() || System.nanoTime() >= deadline) break;
      PollinationsApi.Reply reply = api.pollSignIn(code.getString("device_code"));
      JSONObject body = reply.json();
      if (reply.status == 200 && body.has("access_token")) {
        String token = body.getString("access_token");
        if (token.length() == 0) throw new IOException("Empty sign-in token");
        return token;
      }
      String error = body.optString("error", "Sign-in failed");
      if (error.equals("slow_down")) interval += 5;
      else if (!error.equals("authorization_pending")) throw new IOException(error);
    }
    if (listener.cancelled()) throw new IOException("Sign-in cancelled");
    throw new IOException("Sign-in code expired. Try again.");
  }
}
