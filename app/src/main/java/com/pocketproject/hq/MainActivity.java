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
        webView.addJavascriptInterface(new BackupBridge(), "PocketNative");
        try {
            webView.loadDataWithBaseURL(LOCAL_BASE, readBundledHtml(), "text/html", "UTF-8", null);
        } catch (IOException ex) {
            Log.e(TAG, "Bundled interface could not be opened", ex);
            webView.loadDataWithBaseURL(LOCAL_BASE,
                "<body style='background:#0b1118;color:white;font-family:sans-serif;padding:25px'>" +
                "Pocket HQ could not open its bundled interface. Please reinstall the app.</body>",
                "text/html", "UTF-8", null);
        }
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

    private String fetchOFAEndpoint(String path) throws IOException {
        if (!"/actions/runs?per_page=15".equals(path) &&
            !"/issues?state=open&per_page=80".equals(path)) {
            throw new IOException("Endpoint not permitted");
        }
        java.net.HttpURLConnection connection = (java.net.HttpURLConnection)
                new java.net.URL(OFA_GITHUB_API + path).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "OFA-Android-1.6");
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
        for (int i = 0; i < issues.length(); ++i) {
            org.json.JSONObject issue = issues.optJSONObject(i);
            if (issue == null) continue;
            String title = issue.optString("title", "");
            if (title.startsWith("[OFA] Watchtower")) {
                heartbeat = new org.json.JSONObject();
                heartbeat.put("number", issue.optInt("number"));
                heartbeat.put("updatedAt", issue.optString("updated_at", ""));
            } else if (title.startsWith("[OFA] Engineering")) {
                org.json.JSONObject incident = new org.json.JSONObject();
                incident.put("number", issue.optInt("number"));
                incident.put("title", title.length() > 120 ? title.substring(0, 120) : title);
                incident.put("createdAt", issue.optString("created_at", ""));
                incidents.put(incident);
            }
            if (incidents.length() >= 15) break;
        }
        result.put("heartbeat", heartbeat == null ? org.json.JSONObject.NULL : heartbeat);
        result.put("incidents", incidents);
        result.put("scope", "PUBLIC_GITHUB_BUILD_ONLY");
        return result.toString();
    }

    private final class BackupBridge {
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

    @Override protected void onDestroy() {
        if (pendingImport != null) { pendingImport.onReceiveValue(null); pendingImport = null; }
        if (webView != null) {
            webView.removeJavascriptInterface("PocketNative");
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
