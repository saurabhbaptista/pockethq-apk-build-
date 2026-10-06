package com.pocketproject.hq;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.Window;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.content.SharedPreferences;
import java.security.MessageDigest;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Public build shell: private offline HTML is inserted locally after compilation. */
public final class MainActivity extends Activity {
    private static final String TAG = "PocketHQ";
    private static final String LOCAL_BASE = "https://pockethq.invalid/";
    private static final int REQUEST_SAVE = 201;
    private static final int REQUEST_IMPORT = 202;
    private static final int MAX_EXPORT_BYTES = 4 * 1024 * 1024;
    private WebView webView;
    private ValueCallback<Uri[]> pendingImport;
    private byte[] pendingExport;
    private final AtomicBoolean saving = new AtomicBoolean(false);
    private final ExecutorService diskExecutor = Executors.newSingleThreadExecutor();
    private static final String OFA_CLOUD_PREFS = "ofa.cloud.session.v1";
    private static final String OFA_ALLOWED_BASE_SHA256 = "65f21056a7092d5dd1a2fd49c5efbf86ff6245952301e50dd51dd5f9a9280f37";
    private static final String OFA_AUTH_REDIRECT = "com.pocketproject.hq://auth-callback";
    private static final String OFA_AUTH_SIGNUP_PATH = "/auth/v1/signup?redirect_to=com.pocketproject.hq%3A%2F%2Fauth-callback";
    private SharedPreferences cloudPrefs;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(11, 17, 24));
        window.setNavigationBarColor(Color.rgb(11, 17, 24));
        window.getDecorView().setSystemUiVisibility(0);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(11, 17, 24));
        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(11, 17, 24));
        root.addView(webView, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setBlockNetworkLoads(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        WebView.setWebContentsDebuggingEnabled(false);
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !LOCAL_BASE.equals(request.getUrl().toString());
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("data:") || url.startsWith("about:")) return null;
                return new WebResourceResponse("text/plain", "UTF-8", new java.io.ByteArrayInputStream(new byte[0]));
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage message) {
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    Log.e(TAG, "Interface error line " + message.lineNumber() + ": " + message.message());
                }
                return true;
            }
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                                        FileChooserParams params) {
                if (pendingImport != null) pendingImport.onReceiveValue(null);
                pendingImport = callback;
                try {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.setType("application/json");
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivityForResult(intent, REQUEST_IMPORT);
                    return true;
                } catch (ActivityNotFoundException ex) {
                    pendingImport = null;
                    callback.onReceiveValue(null);
                    notifyUser("No document picker is available.");
                    return true;
                }
            }
        });
        cloudPrefs = getSharedPreferences(OFA_CLOUD_PREFS, MODE_PRIVATE);
        webView.addJavascriptInterface(new BackupBridge(), "PocketNative");
        webView.addJavascriptInterface(new OFACloudBridge(), "OFACloud");
        try {
            webView.loadDataWithBaseURL(LOCAL_BASE, readBundledHtml(), "text/html", "UTF-8", null);
        } catch (IOException ex) {
            Log.e(TAG, "Bundled interface could not be opened", ex);
            webView.loadDataWithBaseURL(LOCAL_BASE,
                "<body style='background:#0b1118;color:white;font-family:sans-serif;padding:25px'>" +
                "Pocket HQ could not open its bundled interface. Please reinstall the app.</body>",
                "text/html", "UTF-8", null);
        }
        handleAuthRedirect(getIntent());
    }

    private void handleAuthRedirect(Intent intent) {
        if (intent == null) return;
        Uri data = intent.getData();
        if (data == null ||
            !"com.pocketproject.hq".equals(data.getScheme()) ||
            !"auth-callback".equals(data.getHost())) return;

        try {
            String fragment = data.getFragment();
            Uri fragmentUri = fragment == null || fragment.isEmpty()
                ? null
                : Uri.parse("https://pockethq.invalid/?" + fragment);

            String error = data.getQueryParameter("error_description");
            if ((error == null || error.isEmpty()) && fragmentUri != null) {
                error = fragmentUri.getQueryParameter("error_description");
            }
            if (error != null && !error.isEmpty()) {
                final JSONObject payload = new JSONObject()
                    .put("ok", false)
                    .put("signedIn", false)
                    .put("authRedirect", true)
                    .put("error", error.length() > 180 ? error.substring(0, 180) : error);
                if (webView != null) webView.postDelayed(() -> cloudCallback("auth", payload), 350);
                return;
            }

            String access = fragmentUri == null ? null : fragmentUri.getQueryParameter("access_token");
            String refresh = fragmentUri == null ? null : fragmentUri.getQueryParameter("refresh_token");
            String expires = fragmentUri == null ? null : fragmentUri.getQueryParameter("expires_in");

            if (access != null && !access.isEmpty()) {
                JSONObject session = new JSONObject().put("access_token", access);
                if (refresh != null && !refresh.isEmpty()) session.put("refresh_token", refresh);
                if (expires != null && !expires.isEmpty()) {
                    try { session.put("expires_in", Long.parseLong(expires)); }
                    catch (NumberFormatException ignored) { }
                }
                saveSession(session);
                final JSONObject payload = new JSONObject()
                    .put("ok", true)
                    .put("signedIn", true)
                    .put("authRedirect", true)
                    .put("email", cloudPrefs.getString("email", ""));
                if (webView != null) webView.postDelayed(() -> cloudCallback("auth", payload), 350);
            }
        } catch (Exception e) {
            Log.e(TAG, "Unable to process OFA auth callback", e);
        } finally {
            intent.setData(null);
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleAuthRedirect(intent);
    }

    private String readBundledHtml() throws IOException {
        try (InputStream in = getAssets().open("index.html"); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }


    // Read-only bridge. Sends NO private app data to GitHub.
    private static final String OFA_GITHUB_API =
        "https://api.github.com/repos/saurabhbaptista/pockethq-apk-build-";

    // Fixed public visualization grid for model cloud cover. This never sends device
    // location or private OFA data. The final point anchors Chief to Abu Dhabi.
    private static final double[] OFA_WEATHER_LAT = new double[] {
        -60,-60,-60,-60,-60,-60,
        -30,-30,-30,-30,-30,-30,
          0,  0,  0,  0,  0,  0,
         30, 30, 30, 30, 30, 30,
         60, 60, 60, 60, 60, 60,
         24.4539
    };
    private static final double[] OFA_WEATHER_LON = new double[] {
        -150,-90,-30,30,90,150,
        -150,-90,-30,30,90,150,
        -150,-90,-30,30,90,150,
        -150,-90,-30,30,90,150,
        -150,-90,-30,30,90,150,
        54.3773
    };
    private static final long OFA_WEATHER_CACHE_MS = 20L * 60L * 1000L;
    private static final String OFA_WEATHER_CACHE_KEY = "ofa.weather.snapshot.v1";
    private static final String OFA_WEATHER_TIME_KEY = "ofa.weather.fetched.v1";

    private String fetchOFAEndpoint(String path) throws IOException {
        if (!"/actions/runs?per_page=15".equals(path) &&
            !"/issues?state=open&per_page=80".equals(path) &&
            !path.matches("/issues/\\d+/comments\\?per_page=30")) {
            throw new IOException("Endpoint not permitted");
        }
        java.net.HttpURLConnection connection = (java.net.HttpURLConnection)
                new java.net.URL(OFA_GITHUB_API + path).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "OFA-Android-3.4");
        try {
            if (connection.getResponseCode() != 200) {
                throw new IOException("GitHub public API returned HTTP " + connection.getResponseCode());
            }
            try (InputStream stream = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = stream.read(buffer)) != -1) {
                    if (output.size() + n > 850000) throw new IOException("Public API response too large");
                    output.write(buffer, 0, n);
                }
                return output.toString("UTF-8");
            }
        } finally {
            connection.disconnect();
        }
    }


    private JSONObject fetchOFAWeatherSnapshot() throws Exception {
        StringBuilder lat = new StringBuilder();
        StringBuilder lon = new StringBuilder();
        for (int i = 0; i < OFA_WEATHER_LAT.length; i++) {
            if (i > 0) { lat.append(','); lon.append(','); }
            lat.append(String.format(java.util.Locale.ROOT, "%.4f", OFA_WEATHER_LAT[i]));
            lon.append(String.format(java.util.Locale.ROOT, "%.4f", OFA_WEATHER_LON[i]));
        }
        String endpoint = "https://api.open-meteo.com/v1/forecast?latitude=" + lat +
            "&longitude=" + lon +
            "&current=cloud_cover,cloud_cover_low,cloud_cover_mid,cloud_cover_high,precipitation,weather_code,is_day,temperature_2m,apparent_temperature,wind_speed_10m" +
            "&timezone=UTC&forecast_days=1";
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "OFA-Android-3.2");
        try {
            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300
                ? connection.getInputStream() : connection.getErrorStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (stream != null) {
                try (InputStream in = stream) {
                    byte[] buffer = new byte[8192]; int n;
                    while ((n = in.read(buffer)) != -1) {
                        if (output.size() + n > 450000) throw new IOException("Weather response too large");
                        output.write(buffer, 0, n);
                    }
                }
            }
            if (code < 200 || code >= 300) throw new IOException("Weather provider HTTP " + code);
            String raw = output.toString("UTF-8").trim();
            JSONArray roots = raw.startsWith("[")
                ? new JSONArray(raw)
                : new JSONArray().put(new JSONObject(raw));
            JSONArray samples = new JSONArray();
            for (int i = 0; i < roots.length(); i++) {
                JSONObject root = roots.optJSONObject(i);
                if (root == null) continue;
                JSONObject current = root.optJSONObject("current");
                if (current == null) continue;
                JSONObject sample = new JSONObject();
                sample.put("lat", root.optDouble("latitude",
                    OFA_WEATHER_LAT[Math.min(i, OFA_WEATHER_LAT.length - 1)]));
                sample.put("lon", root.optDouble("longitude",
                    OFA_WEATHER_LON[Math.min(i, OFA_WEATHER_LON.length - 1)]));
                sample.put("cloud", current.optDouble("cloud_cover", 0.0));
                sample.put("low", current.optDouble("cloud_cover_low", 0.0));
                sample.put("mid", current.optDouble("cloud_cover_mid", 0.0));
                sample.put("high", current.optDouble("cloud_cover_high", 0.0));
                sample.put("precip", current.optDouble("precipitation", 0.0));
                sample.put("code", current.optInt("weather_code", 0));
                sample.put("isDay", current.optInt("is_day", 0));
                sample.put("temp", current.optDouble("temperature_2m", Double.NaN));
                sample.put("apparent", current.optDouble("apparent_temperature", Double.NaN));
                sample.put("wind", current.optDouble("wind_speed_10m", Double.NaN));
                samples.put(sample);
            }
            if (samples.length() < 12) throw new IOException("Weather grid incomplete");
            return new JSONObject()
                .put("ok", true)
                .put("source", "Open-Meteo")
                .put("fetchedAt", System.currentTimeMillis())
                .put("stale", false)
                .put("samples", samples);
        } finally {
            connection.disconnect();
        }
    }

    private void weatherCallback(JSONObject payload) {
        final String js = "if(window.OFAWeatherReceive){window.OFAWeatherReceive(" +
            JSONObject.quote(payload.toString()) + ");}";
        runOnUiThread(() -> {
            if (!isFinishing() && webView != null) webView.evaluateJavascript(js, null);
        });
    }

    private org.json.JSONArray readPublicComments(int issueNumber) throws Exception {
        if (issueNumber <= 0) return new org.json.JSONArray();
        org.json.JSONArray raw = new org.json.JSONArray(
            fetchOFAEndpoint("/issues/" + issueNumber + "/comments?per_page=30"));
        org.json.JSONArray clean = new org.json.JSONArray();
        int start = Math.max(0, raw.length() - 20);
        for (int i = start; i < raw.length(); ++i) {
            org.json.JSONObject item = raw.optJSONObject(i);
            if (item == null) continue;
            org.json.JSONObject out = new org.json.JSONObject();
            String body = item.optString("body", "");
            if (body.length() > 1600) body = body.substring(0, 1600);
            out.put("body", body);
            out.put("createdAt", item.optString("created_at", ""));
            org.json.JSONObject user = item.optJSONObject("user");
            out.put("actor", user == null ? "OFA" : user.optString("login", "OFA"));
            clean.put(out);
        }
        return clean;
    }

    private String publicWatchtowerSnapshot() throws Exception {
        org.json.JSONObject result = new org.json.JSONObject();
        result.put("fetchedAt", new java.text.SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US) {{
                setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            }}.format(new java.util.Date()));
        org.json.JSONObject runs = new org.json.JSONObject(
            fetchOFAEndpoint("/actions/runs?per_page=15"));
        org.json.JSONArray items = runs.optJSONArray("workflow_runs");
        if (items != null) for (int i = 0; i < items.length(); ++i) {
            org.json.JSONObject run = items.optJSONObject(i);
            if (run == null ||
                !"Build Pocket HQ offline Android shell".equals(run.optString("name")) ||
                !"completed".equals(run.optString("status"))) continue;
            org.json.JSONObject latest = new org.json.JSONObject();
            latest.put("runId", run.optLong("id"));
            latest.put("conclusion", run.optString("conclusion", "unknown"));
            latest.put("attempt", run.optInt("run_attempt", 1));
            latest.put("updatedAt", run.optString("updated_at", ""));
            latest.put("commit", run.optString("head_sha", ""));
            result.put("build", latest);
            break;
        }
        org.json.JSONArray issues = new org.json.JSONArray(
            fetchOFAEndpoint("/issues?state=open&per_page=80"));
        org.json.JSONArray incidents = new org.json.JSONArray();
        org.json.JSONObject heartbeat = null;
        int managerLogIssue = 0;
        int workerLogIssue = 0;
        for (int i = 0; i < issues.length(); ++i) {
            org.json.JSONObject issue = issues.optJSONObject(i);
            if (issue == null) continue;
            String title = issue.optString("title", "");
            if (title.startsWith("[OFA] Watchtower")) {
                heartbeat = new org.json.JSONObject();
                heartbeat.put("number", issue.optInt("number"));
                heartbeat.put("updatedAt", issue.optString("updated_at", ""));
            } else if (title.startsWith("[OFA] Chief ↔ Managers")) {
                managerLogIssue = issue.optInt("number");
            } else if (title.startsWith("[OFA] Workers")) {
                workerLogIssue = issue.optInt("number");
            } else if (title.startsWith("[OFA] Engineering")) {
                org.json.JSONObject incident = new org.json.JSONObject();
                incident.put("number", issue.optInt("number"));
                incident.put("title", title.length() > 120 ? title.substring(0, 120) : title);
                incident.put("createdAt", issue.optString("created_at", ""));
                incidents.put(incident);
            }
        }
        result.put("heartbeat", heartbeat == null ? org.json.JSONObject.NULL : heartbeat);
        result.put("incidents", incidents);
        result.put("managerMessages", readPublicComments(managerLogIssue));
        result.put("workerMessages", readPublicComments(workerLogIssue));
        result.put("scope", "PUBLIC_GITHUB_BUILD_ONLY");
        return result.toString();
    }


    private static String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] raw = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte b : raw) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }

    private String cloudBase() throws Exception {
        String base = cloudPrefs.getString("base_url", "");
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (!OFA_ALLOWED_BASE_SHA256.equals(sha256(base))) throw new IOException("OFA private backend is not configured");
        return base;
    }

    private String cloudKey() throws IOException {
        String key = cloudPrefs.getString("publishable_key", "");
        if (!(key.startsWith("sb_publishable_") || key.split("\\.").length == 3)) {
            throw new IOException("OFA publishable key is not configured");
        }
        return key;
    }

    private JSONObject httpJson(String method, String path, JSONObject body, boolean authenticated) throws Exception {
        String base = cloudBase();
        if (!path.startsWith("/auth/v1/") && !path.startsWith("/rest/v1/")) {
            throw new IOException("OFA endpoint not permitted");
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(base + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(12000);
        connection.setRequestProperty("apikey", cloudKey());
        connection.setRequestProperty("Accept", "application/json");
        if (authenticated) {
            String token = ensureAccessToken();
            if (token.isEmpty()) throw new IOException("Sign in to OFA first");
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
        }
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (stream != null) {
            try (InputStream in = stream) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = in.read(buffer)) != -1) {
                    if (out.size() + count > 2200000) throw new IOException("OFA response too large");
                    out.write(buffer, 0, count);
                }
            }
        }
        connection.disconnect();
        String raw = new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
        if (code < 200 || code >= 300) {
            String detail = "HTTP " + code;
            try {
                JSONObject e = raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
                detail = e.optString("msg", e.optString("message", e.optString("error_description", detail)));
            } catch (Exception ignored) { }
            throw new IOException(detail.length() > 180 ? detail.substring(0,180) : detail);
        }
        if (raw.isEmpty()) return new JSONObject();
        if (raw.startsWith("[")) return new JSONObject().put("items", new JSONArray(raw));
        return new JSONObject(raw);
    }

    private String ensureAccessToken() throws Exception {
        long expiresAt = cloudPrefs.getLong("expires_at_ms", 0L);
        String access = cloudPrefs.getString("access_token", "");
        if (!access.isEmpty() && System.currentTimeMillis() + 60000L < expiresAt) return access;
        String refresh = cloudPrefs.getString("refresh_token", "");
        if (refresh.isEmpty()) return access;
        JSONObject response = httpJsonNoAuth("POST", "/auth/v1/token?grant_type=refresh_token",
            new JSONObject().put("refresh_token", refresh));
        saveSession(response);
        return cloudPrefs.getString("access_token", "");
    }

    private JSONObject httpJsonNoAuth(String method, String path, JSONObject body) throws Exception {
        String base = cloudBase();
        if (!path.startsWith("/auth/v1/")) throw new IOException("Auth endpoint not permitted");
        HttpURLConnection connection = (HttpURLConnection) new URL(base + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(12000);
        connection.setRequestProperty("apikey", cloudKey());
        connection.setRequestProperty("Accept", "application/json");
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
        }
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (stream != null) {
            try (InputStream in = stream) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = in.read(buffer)) != -1) {
                    if (out.size() + count > 1000000) throw new IOException("Auth response too large");
                    out.write(buffer, 0, count);
                }
            }
        }
        connection.disconnect();
        String raw = new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
        if (code < 200 || code >= 300) {
            String detail = "HTTP " + code;
            try {
                JSONObject e = raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
                detail = e.optString("msg", e.optString("message", e.optString("error_description", detail)));
            } catch (Exception ignored) { }
            throw new IOException(detail.length() > 180 ? detail.substring(0,180) : detail);
        }
        return raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
    }

    private void saveSession(JSONObject response) {
        String access = response.optString("access_token", "");
        String refresh = response.optString("refresh_token", "");
        long expiresIn = Math.max(60L, response.optLong("expires_in", 3600L));
        JSONObject user = response.optJSONObject("user");
        String email = user == null ? cloudPrefs.getString("email", "") : user.optString("email", "");
        SharedPreferences.Editor edit = cloudPrefs.edit();
        if (!access.isEmpty()) edit.putString("access_token", access).putLong("expires_at_ms", System.currentTimeMillis() + expiresIn * 1000L);
        if (!refresh.isEmpty()) edit.putString("refresh_token", refresh);
        if (!email.isEmpty()) edit.putString("email", email);
        edit.apply();
    }

    private void cloudCallback(String kind, JSONObject payload) {
        final String js = "window.OFACloudReceive(" + JSONObject.quote(kind) + "," +
            JSONObject.quote(payload.toString()) + ");";
        runOnUiThread(() -> {
            if (!isFinishing() && webView != null) webView.evaluateJavascript(js, null);
        });
    }

    private JSONObject cloudError(Exception error) {
        String message = error.getMessage() == null ? "OFA private backend unavailable" : error.getMessage();
        if (message.length() > 200) message = message.substring(0, 200);
        try { return new JSONObject().put("ok", false).put("error", message); }
        catch (Exception ignored) { return new JSONObject(); }
    }

    private final class OFACloudBridge {
        @JavascriptInterface public boolean configure(String baseUrl, String publishableKey) {
            try {
                String normalized = String.valueOf(baseUrl == null ? "" : baseUrl).trim();
                while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length()-1);
                if (!OFA_ALLOWED_BASE_SHA256.equals(sha256(normalized))) return false;
                if (publishableKey == null || !publishableKey.startsWith("sb_publishable_")) return false;
                cloudPrefs.edit().putString("base_url", normalized)
                    .putString("publishable_key", publishableKey).apply();
                return true;
            } catch (Exception e) { return false; }
        }

        @JavascriptInterface public String authState() {
            try {
                JSONObject result = new JSONObject();
                String access = cloudPrefs.getString("access_token","");
                String refresh = cloudPrefs.getString("refresh_token","");
                long expiresAt = cloudPrefs.getLong("expires_at_ms", 0L);
                boolean accessFresh = !access.isEmpty() && System.currentTimeMillis() + 60000L < expiresAt;
                result.put("configured", !cloudPrefs.getString("base_url","").isEmpty());
                result.put("signedIn", accessFresh || !refresh.isEmpty());
                result.put("sessionNeedsRefresh", !refresh.isEmpty() && !accessFresh);
                result.put("sessionExpiresAtMs", expiresAt);
                result.put("email", cloudPrefs.getString("email",""));
                return result.toString();
            } catch (Exception e) { return "{\"configured\":false,\"signedIn\":false}"; }
        }

        @JavascriptInterface public void recoverSession() {
            diskExecutor.execute(() -> {
                try {
                    String token = ensureAccessToken();
                    JSONObject result = new JSONObject()
                        .put("ok", true)
                        .put("signedIn", !token.isEmpty())
                        .put("sessionRecovered", !token.isEmpty())
                        .put("email", cloudPrefs.getString("email",""));
                    cloudCallback("auth", result);
                } catch (Exception e) {
                    JSONObject result = cloudError(e);
                    try {
                        result.put("signedIn", false)
                              .put("sessionRecoveryFailed", true);
                    } catch (Exception ignored) { }
                    cloudCallback("auth", result);
                }
            });
        }

        @JavascriptInterface public void signUp(String email, String password) {
            diskExecutor.execute(() -> {
                try {
                    JSONObject response = httpJsonNoAuth("POST", OFA_AUTH_SIGNUP_PATH,
                        new JSONObject().put("email", String.valueOf(email).trim())
                            .put("password", String.valueOf(password)));
                    saveSession(response);
                    JSONObject result = new JSONObject().put("ok", true);
                    result.put("signedIn", !response.optString("access_token","").isEmpty());
                    result.put("needsEmailConfirmation", response.optString("access_token","").isEmpty());
                    JSONObject user = response.optJSONObject("user");
                    if (user != null) result.put("email", user.optString("email",""));
                    cloudCallback("auth", result);
                } catch (Exception e) { cloudCallback("auth", cloudError(e)); }
            });
        }

        @JavascriptInterface public void signIn(String email, String password) {
            diskExecutor.execute(() -> {
                try {
                    JSONObject response = httpJsonNoAuth("POST", "/auth/v1/token?grant_type=password",
                        new JSONObject().put("email", String.valueOf(email).trim())
                            .put("password", String.valueOf(password)));
                    saveSession(response);
                    cloudCallback("auth", new JSONObject().put("ok", true).put("signedIn", true)
                        .put("email", cloudPrefs.getString("email","")));
                } catch (Exception e) { cloudCallback("auth", cloudError(e)); }
            });
        }

        @JavascriptInterface public void signOut() {
            diskExecutor.execute(() -> {
                try {
                    String token = cloudPrefs.getString("access_token","");
                    if (!token.isEmpty()) {
                        try { httpJson("POST", "/auth/v1/logout", new JSONObject(), true); }
                        catch (Exception ignored) { }
                    }
                } finally {
                    cloudPrefs.edit().remove("access_token").remove("refresh_token")
                        .remove("expires_at_ms").remove("email").apply();
                    try { cloudCallback("auth", new JSONObject().put("ok", true).put("signedIn", false)); }
                    catch (Exception ignored) { }
                }
            });
        }

        @JavascriptInterface public void refreshOffice() {
            diskExecutor.execute(() -> {
                try {
                    JSONObject result = httpJson("POST",
                        "/rest/v1/rpc/ofa_mobile_snapshot", new JSONObject(), true);
                    result.put("email", cloudPrefs.getString("email",""));
                    result.put("fetchedAt", System.currentTimeMillis());
                    cloudCallback("office", result);
                } catch (Exception e) { cloudCallback("office", cloudError(e)); }
            });
        }

        @JavascriptInterface public void decideApproval(String approvalId, String decision) {
            diskExecutor.execute(() -> {
                try {
                    String id = String.valueOf(approvalId == null ? "" : approvalId).trim();
                    String choice = String.valueOf(decision == null ? "" : decision).trim().toLowerCase(java.util.Locale.ROOT);
                    if (!id.matches("[0-9a-fA-F-]{36}")) throw new IOException("Invalid approval");
                    if (!"approved".equals(choice) && !"rejected".equals(choice)) throw new IOException("Invalid decision");
                    JSONObject response = httpJson("POST", "/rest/v1/rpc/ofa_ceo_decide_approval",
                        new JSONObject().put("approval_id", id).put("decision", choice), true);
                    cloudCallback("approval", new JSONObject().put("ok", true).put("result", response));
                } catch (Exception e) { cloudCallback("approval", cloudError(e)); }
            });
        }

        @JavascriptInterface public void sendChiefMessage(String message) {
            diskExecutor.execute(() -> {
                try {
                    String clean = String.valueOf(message == null ? "" : message).trim();
                    if (clean.isEmpty() || clean.length() > 12000) throw new IOException("Message must be 1–12000 characters");
                    JSONObject response = httpJson("POST", "/rest/v1/rpc/ofa_ceo_message_to_chief",
                        new JSONObject().put("message_text", clean), true);
                    cloudCallback("chief-message", new JSONObject().put("ok", true).put("result", response));
                } catch (Exception e) { cloudCallback("chief-message", cloudError(e)); }
            });
        }
    }

    private final class BackupBridge {
        @JavascriptInterface public void refreshOFAWeather() {
            diskExecutor.execute(() -> {
                long now = System.currentTimeMillis();
                String cached = cloudPrefs.getString(OFA_WEATHER_CACHE_KEY, "");
                long cachedAt = cloudPrefs.getLong(OFA_WEATHER_TIME_KEY, 0L);
                if (!cached.isEmpty() && now - cachedAt >= 0L && now - cachedAt < OFA_WEATHER_CACHE_MS) {
                    try {
                        JSONObject payload = new JSONObject(cached);
                        payload.put("stale", false);
                        weatherCallback(payload);
                        return;
                    } catch (Exception ignored) { }
                }
                try {
                    JSONObject payload = fetchOFAWeatherSnapshot();
                    cloudPrefs.edit()
                        .putString(OFA_WEATHER_CACHE_KEY, payload.toString())
                        .putLong(OFA_WEATHER_TIME_KEY, payload.optLong("fetchedAt", now))
                        .apply();
                    weatherCallback(payload);
                } catch (Exception error) {
                    if (!cached.isEmpty()) {
                        try {
                            JSONObject payload = new JSONObject(cached);
                            payload.put("ok", true).put("stale", true);
                            weatherCallback(payload);
                            return;
                        } catch (Exception ignored) { }
                    }
                    try {
                        weatherCallback(new JSONObject().put("ok", false)
                            .put("error", "Live weather model unavailable"));
                    } catch (Exception ignored) { }
                }
            });
        }

        @JavascriptInterface public void refreshOFABuildStatus() {
            diskExecutor.execute(() -> {
                String payload;
                try { payload = publicWatchtowerSnapshot(); }
                catch (Exception error) {
                    org.json.JSONObject failure = new org.json.JSONObject();
                    try {
                        failure.put("error", "Cannot reach public GitHub Watchtower. " +
                            (error instanceof IOException ? error.getMessage() : "Retry later."));
                    } catch (Exception ignored) { }
                    payload = failure.toString();
                }
                final String safeJs = "window.OFAWatchtowerReceive(" +
                    org.json.JSONObject.quote(payload) + ");";
                runOnUiThread(() -> {
                    if (!isFinishing() && webView != null)
                        webView.evaluateJavascript(safeJs, null);
                });
            });
        }

        @JavascriptInterface public void updateWidget(String compactStateJson) {
            if (compactStateJson == null || compactStateJson.length() > 5000) return;
            try {
                WidgetProvider.updateSnapshot(MainActivity.this, compactStateJson);
            } catch (Exception ex) {
                Log.w(TAG, "Widget update failed", ex);
            }
        }

        @JavascriptInterface public void saveBase64(String proposedName, String mimeType, String encoded) {
            if (!saving.compareAndSet(false, true)) { notifyUser("A save dialog is already open."); return; }
            if (encoded == null || encoded.length() > (MAX_EXPORT_BYTES * 4 / 3 + 256)) {
                saving.set(false); notifyUser("Export exceeds the 4 MB safety limit."); return;
            }
            if (!"application/json".equals(mimeType) && !"text/markdown".equals(mimeType)) {
                saving.set(false); notifyUser("Unsupported export format."); return;
            }
            try {
                byte[] raw = Base64.decode(encoded, Base64.DEFAULT);
                if (raw.length > MAX_EXPORT_BYTES) throw new IllegalArgumentException("Export too large");
                final String extension = "application/json".equals(mimeType) ? ".json" : ".md";
                String name = proposedName == null ? "PocketHQ" : proposedName.replaceAll("[^a-zA-Z0-9._-]", "_");
                if (name.length() > 100) name = name.substring(0, 100);
                if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(extension)) name += extension;
                final String safeName = name;
                runOnUiThread(() -> {
                    if (isFinishing() || (Build.VERSION.SDK_INT >= 17 && isDestroyed())) {
                        pendingExport = null; saving.set(false); return;
                    }
                    pendingExport = raw;
                    try {
                        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType(mimeType);
                        intent.putExtra(Intent.EXTRA_TITLE, safeName);
                        startActivityForResult(intent, REQUEST_SAVE);
                    } catch (ActivityNotFoundException ex) {
                        pendingExport = null; saving.set(false);
                        notifyUser("No compatible file-saving application is installed.");
                    }
                });
            } catch (IllegalArgumentException ex) {
                saving.set(false); notifyUser("Could not prepare the export file.");
            }
        }
    }

    private void notifyUser(String message) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show());
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        if (requestCode == REQUEST_IMPORT) {
            ValueCallback<Uri[]> callback = pendingImport;
            pendingImport = null;
            if (callback != null) {
                Uri selected = resultCode == RESULT_OK && intent != null ? intent.getData() : null;
                callback.onReceiveValue(selected == null ? null : new Uri[]{selected});
            }
        } else if (requestCode == REQUEST_SAVE) {
            final byte[] data = pendingExport;
            pendingExport = null;
            saving.set(false);
            if (resultCode == RESULT_OK && intent != null && intent.getData() != null && data != null) {
                final Uri outputUri = intent.getData();
                diskExecutor.execute(() -> {
                    try (OutputStream out = getContentResolver().openOutputStream(outputUri, "wt")) {
                        if (out == null) throw new IOException("Document could not be opened");
                        out.write(data);
                        out.flush();
                        notifyUser("Pocket HQ file saved.");
                    } catch (Exception ex) {
                        Log.e(TAG, "Unable to save exported document", ex);
                        notifyUser("Export failed. Retry and select another folder.");
                    }
                });
            }
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.postDelayed(() -> {
                if (!isFinishing() && webView != null) {
                    webView.evaluateJavascript(
                        "if(window.OFAAppResume){window.OFAAppResume();}", null);
                }
            }, 250);
        }
    }

    @Override protected void onDestroy() {
        if (pendingImport != null) { pendingImport.onReceiveValue(null); pendingImport = null; }
        if (webView != null) {
            webView.removeJavascriptInterface("PocketNative");
            webView.removeJavascriptInterface("OFACloud");
            if (webView.getParent() instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) webView.getParent()).removeView(webView);
            }
            webView.destroy();
            webView = null;
        }
        diskExecutor.shutdown();
        super.onDestroy();
    }
}
