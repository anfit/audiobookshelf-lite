package lite.abs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONException;
import org.json.JSONObject;

/** Tiny Audiobookshelf client: logs in with the hardcoded credentials, keeps the access token fresh, retries once on 401. */
final class Api {
    private static final long MAX_TOKEN_AGE_MS = 45L * 60 * 1000; // ABS access tokens last 1h by default
    private static String base = Config.BASE;
    private static String user = Config.USER;
    private static String pass = Config.PASS;
    private static String token;
    private static long tokenAt;

    private Api() {}

    static synchronized void configure(String b, String u, String p) {
        base = b; user = u; pass = p; token = null;
    }

    static synchronized String base() { return base; }

    static synchronized String token() throws IOException {
        if (token == null || System.currentTimeMillis() - tokenAt > MAX_TOKEN_AGE_MS) login();
        return token;
    }

    private static synchronized void invalidate() { token = null; }

    private static void login() throws IOException {
        try {
            JSONObject b = new JSONObject();
            b.put("username", user);
            b.put("password", pass);
            HttpURLConnection c = open("/login", "POST");
            c.setRequestProperty("x-return-tokens", "true");
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            write(c, b.toString());
            int code = c.getResponseCode();
            if (code != 200) { drain(c); throw new IOException("login failed (HTTP " + code + ")"); }
            token = new JSONObject(new String(readAll(c.getInputStream()), "UTF-8")).getJSONObject("user").getString("accessToken");
            tokenAt = System.currentTimeMillis();
        } catch (JSONException e) {
            throw new IOException("login: unexpected response");
        }
    }

    private static HttpURLConnection open(String path, String method) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        c.setRequestMethod(method);
        return c;
    }

    private static void write(HttpURLConnection c, String body) throws IOException {
        byte[] data = body.getBytes("UTF-8");
        c.setFixedLengthStreamingMode(data.length);
        OutputStream os = c.getOutputStream();
        os.write(data);
        os.close();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    private static void drain(HttpURLConnection c) {
        try { InputStream e = c.getErrorStream(); if (e != null) readAll(e); } catch (IOException ignored) {}
    }

    /** Authenticated request returning the raw response bytes. */
    static byte[] bytes(String method, String path, JSONObject body) throws IOException {
        for (int attempt = 0; ; attempt++) {
            HttpURLConnection c = open(path, method);
            c.setRequestProperty("Authorization", "Bearer " + token());
            if (body != null) {
                c.setRequestProperty("Content-Type", "application/json");
                c.setDoOutput(true);
                write(c, body.toString());
            }
            int code = c.getResponseCode();
            if (code == 401 && attempt == 0) { drain(c); invalidate(); continue; }
            if (code >= 400) { drain(c); throw new IOException(method + " " + path + " -> HTTP " + code); }
            return readAll(c.getInputStream());
        }
    }

    static String text(String method, String path, JSONObject body) throws IOException {
        return new String(bytes(method, path, body), "UTF-8");
    }

    static JSONObject json(String method, String path, JSONObject body) throws IOException {
        try {
            return new JSONObject(text(method, path, body));
        } catch (JSONException e) {
            throw new IOException(path + ": bad JSON");
        }
    }
}
