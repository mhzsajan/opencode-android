package app.opencode.termux;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.ConsoleMessage;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * OpencodeTermux -- a WebView over the opencode server that Termux runs.
 *
 * Why this exists instead of the standalone app: on the test phone the same
 * opencode binary, launched from Termux, answers a real prompt in ~6 seconds,
 * while the standalone app's bundled copy of that identical binary never
 * manages an external socket. Rather than ship the broken path, this drives the
 * working one. The measurements are in docs/STANDALONE-NETWORK.md.
 *
 * The whole contract with Termux is one script, ~/.shortcuts/oc-server, which
 * that script guards with flock so repeated launches cannot pile up servers.
 */
public class MainActivity extends Activity {

    private static final String TAG = "octermux";
    private static final String TERMUX = "com.termux";
    private static final String SERVICE = "com.termux.app.RunCommandService";
    private static final String HOME = "/data/data/com.termux/files/home";
    private static final String PREFIX = "/data/data/com.termux/files/usr";
    private static final String SERVER_SCRIPT = HOME + "/.shortcuts/oc-server";
    private static final String PROJECT = HOME + "/myproject";
    private static final String URL = "http://127.0.0.1:4096/";
    // 180 x 1 s: a cold Termux launch can take a while on a busy phone, and
    // giving up early is exactly what made the first standalone build look
    // broken. The status line shows progress while this runs.
    private static final int MAX_RETRIES = 180;
    private static final long SPAWN_THROTTLE = 5000;

    private static final String PERM = "com.termux.permission.RUN_COMMAND";
    private static final int REQ_PERM = 7;
    private static final int REQ_FILE = 101;
    private static final int REQ_MIC = 102;

    private WebView web;
    private ProgressBar bar;
    private TextView status;
    private int retries = 0;
    private boolean waiting = false;
    private boolean loadedOnce = false;
    private long lastSpawn = 0;
    private long lastBack = 0;
    private ValueCallback<Uri[]> filePath;
    private PermissionRequest pendingPermission;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        getWindow().setStatusBarColor(0xFF0B0B0D);
        getWindow().setNavigationBarColor(0xFF0B0B0D);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0B0D);
        root.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        web = new WebView(this);
        web.setBackgroundColor(0xFF0B0B0D);
        // never hidden: if the server is not up, show Chromium's real error
        // page rather than a featureless dark screen (the bug that made the
        // standalone app look broken with no clue why)
        web.setVisibility(View.VISIBLE);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setDatabaseEnabled(true);
        web.getSettings().setLoadWithOverviewMode(true);
        web.getSettings().setUseWideViewPort(true);
        web.getSettings().setSupportZoom(false);
        web.getSettings().setBuiltInZoomControls(false);
        web.getSettings().setDisplayZoomControls(false);
        web.getSettings().setMediaPlaybackRequiresUserGesture(false);
        web.getSettings().setSupportMultipleWindows(true);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        bar = new ProgressBar(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(bar, lp);

        status = new TextView(this);
        status.setTextColor(0xFF9A9A9E);
        status.setTextSize(13);
        status.setGravity(Gravity.CENTER);
        status.setPadding(32, 0, 32, 0);
        root.addView(status, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        setContentView(root);

        if (checkSelfPermission(PERM) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            start();
        } else {
            requestPermissions(new String[] { PERM }, REQ_PERM);
        }
    }

    /**
     * Ask Termux to start the server (if it is not already up) and then wait
     * for port 4096 asynchronously.
     *
     * ping() must never run on the main thread: HttpURLConnection throws
     * NetworkOnMainThreadException, the old code swallowed it and concluded
     * "server not ready" forever, so the WebView never loaded. That was the
     * blank-UI bug in ocstandalone; this app had the same pattern.
     */
    private void start() {
        ensureServer();
        loadWhenReady();
    }

    private void loadWhenReady() {
        if (waiting) return;
        waiting = true;
        new Thread(new Runnable() {
            public void run() {
                final boolean ok = ping();
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (ok) {
                            waiting = false;
                            retries = 0;
                            bar.setVisibility(View.GONE);
                            if (!loadedOnce) load();
                            return;
                        }
                        if (retries >= MAX_RETRIES) {
                            waiting = false;
                            Log.d(TAG, "loadWhenReady: giving up after " + retries + " attempts");
                            status.setText("opencode is not answering on port 4096.\n"
                                    + "Start Termux once, then reopen this app.");
                            status.setVisibility(View.VISIBLE);
                            return;
                        }
                        retries++;
                        Log.d(TAG, "loadWhenReady attempt " + retries + " (ping failed)");
                        status.setText("Starting opencode in Termux"
                                + (retries > 1 ? " (attempt " + retries + ")" : "")
                                + "...");
                        status.setVisibility(View.VISIBLE);
                        bar.setVisibility(View.VISIBLE);
                        web.postDelayed(new Runnable() {
                            public void run() {
                                if (isFinishing()) {
                                    waiting = false;
                                    return;
                                }
                                ensureServer();
                                waiting = false;
                                loadWhenReady();
                            }
                        }, 1000);
                    }
                });
            }
        }, "octermux-ping").start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC) {
            if (pendingPermission == null) return;
            boolean ok = grantResults.length > 0
                    && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (ok) {
                pendingPermission.grant(pendingPermission.getResources());
            } else {
                pendingPermission.deny();
            }
            pendingPermission = null;
            return;
        }
        if (requestCode != REQ_PERM) return;
        if (grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            start();
        } else {
            android.widget.Toast.makeText(this,
                    "OpencodeTermux needs permission to run commands in Termux",
                    android.widget.Toast.LENGTH_LONG).show();
            try {
                Intent s = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()));
                s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(s);
            } catch (Throwable ignored) {
            }
        }
    }

    private class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (u == null) return false;
            String host = u.getHost();
            boolean local = "127.0.0.1".equals(host) || "localhost".equals(host) || "::1".equals(host);
            if (local) return false;
            openExternal(u);
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            Log.d(TAG, "page finished: " + url);
            if (url != null && url.startsWith("http")) loadedOnce = true;
            bar.setVisibility(View.GONE);
            status.setVisibility(View.GONE);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
            if (req != null && req.isForMainFrame()) {
                Log.d(TAG, "main frame error " + err.getErrorCode() + ": " + err.getDescription()
                        + " url=" + req.getUrl());
                loadedOnce = false;
                retry();
            }
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest req, WebResourceResponse err) {
            if (req != null && req.isForMainFrame()) {
                Log.d(TAG, "http error " + err.getStatusCode() + " url=" + req.getUrl());
                retry();
            }
        }
    }

    private class Chrome extends WebChromeClient {
        @Override
        public boolean onConsoleMessage(ConsoleMessage m) {
            Log.d(TAG, m.message() + " (" + m.sourceId() + ":" + m.lineNumber() + ")");
            return true;
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb,
                                         FileChooserParams params) {
            if (filePath != null) filePath.onReceiveValue(null);
            filePath = cb;
            Intent pick;
            try {
                pick = params.createIntent();
            } catch (Throwable t) {
                pick = new Intent(Intent.ACTION_GET_CONTENT);
                pick.setType("*/*");
            }
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            try {
                startActivityForResult(Intent.createChooser(pick, "Select file"), REQ_FILE);
            } catch (Throwable t) {
                filePath = null;
                android.widget.Toast.makeText(MainActivity.this,
                        "No file picker available", android.widget.Toast.LENGTH_SHORT).show();
                return false;
            }
            return true;
        }

        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            boolean wantsMic = false;
            for (String res : request.getResources()) {
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)) wantsMic = true;
            }
            if (!wantsMic) {
                request.deny();
                return;
            }
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                request.grant(request.getResources());
            } else {
                pendingPermission = request;
                requestPermissions(new String[] { android.Manifest.permission.RECORD_AUDIO }, REQ_MIC);
            }
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                      Message resultMsg) {
            WebView popup = new WebView(MainActivity.this);
            popup.getSettings().setJavaScriptEnabled(true);
            popup.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                    Uri u = req.getUrl();
                    if (u != null) web.loadUrl(u.toString());
                    v.destroy();
                    return true;
                }
            });
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(popup);
            resultMsg.sendToTarget();
            return true;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE || filePath == null) return;
        Uri[] out = null;
        if (resultCode == Activity.RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                out = new Uri[n];
                for (int i = 0; i < n; i++) out[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data.getData() != null) {
                out = new Uri[] { data.getData() };
            }
        }
        filePath.onReceiveValue(out);
        filePath = null;
    }

    private void openExternal(Uri u) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, u);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            android.widget.Toast.makeText(this, "No app can open this link",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void retry() {
        // async, main-thread-safe wait; onPageFinished clears the "loaded" flag's effect
        loadWhenReady();
    }

    private void load() {
        retries = 0;
        bar.setVisibility(View.VISIBLE);
        status.setVisibility(View.VISIBLE);
        status.setText("Starting opencode in Termux...");
        web.loadUrl(URL);
    }

    private void ensureServer() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastSpawn < SPAWN_THROTTLE) return;
        lastSpawn = now;
        try {
            Intent i = new Intent("com.termux.RUN_COMMAND");
            i.setComponent(new ComponentName(TERMUX, SERVICE));
            i.putExtra("com.termux.RUN_COMMAND_PATH", PREFIX + "/bin/bash");
            i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[] { "-l", SERVER_SCRIPT });
            i.putExtra("com.termux.RUN_COMMAND_WORKDIR", PROJECT);
            i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
            i.putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "opencode server");
            startService(i);
            Log.d(TAG, "asked Termux to run " + SERVER_SCRIPT);
        } catch (Throwable t) {
            Log.e(TAG, "cannot start Termux service", t);
            status.setText("Could not start Termux. Is it installed and running?");
        }
    }

    private boolean ping() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(URL).openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(1500);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            InputStream in = c.getInputStream();
            byte[] buf = new byte[1];
            in.read(buf);
            in.close();
            return code >= 200 && code < 400;
        } catch (Throwable t) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private boolean keyboardUp() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        return imm != null && imm.isActive();
    }

    private void hideKeyboard() {
        View focused = getCurrentFocus();
        if (focused != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        }
        web.clearFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web == null) return;
        // Keep an already-rendered page. Otherwise re-arm the async wait: no
        // synchronous ping here, it would throw NetworkOnMainThreadException.
        if (!loadedOnce) {
            retries = 0;
            start();
        }
    }

    @Override
    public void onBackPressed() {
        if (keyboardUp()) {
            hideKeyboard();
            return;
        }
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastBack < 2000) {
            finish();
        } else {
            lastBack = now;
            android.widget.Toast.makeText(this, "Press back again to exit",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }
}