package app.opencode.standalone;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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

public class MainActivity extends Activity {

    private static final String TAG = "ocstandalone";
    private static final String URL = "http://127.0.0.1:4096/";
    private static final int MAX_RETRIES = 180;
    private static final long SPAWN_THROTTLE = 5000;
    private static final int REQ_FILE = 101;
    private static final int REQ_MIC = 102;
    private static final int REQ_NOTIF = 103;

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
        web.setVisibility(View.VISIBLE); // handoff step 2: never hide it, so a failed load can surface a real error page
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
        root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        status = new TextView(this);
        status.setTextColor(0xFF9A9A9E);
        status.setTextSize(13);
        status.setGravity(Gravity.CENTER);
        status.setPadding(32, 0, 32, 0);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(status, slp);

        setContentView(root);

        askNotification();
        bootstrap();
    }

    private void askNotification() {
        if (Build.VERSION.SDK_INT < 33) return;
        // apps below targetSdk 33 get the prompt automatically when the first
        // notification channel is created, and the permission is not requestable
        if (getApplicationInfo().targetSdkVersion < 33) return;
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { android.Manifest.permission.POST_NOTIFICATIONS }, REQ_NOTIF);
        }
    }

    private void askStorage() {
        if (Projects.hasFullAccess()) return;
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    private void bootstrap() {
        if (Installer.isInstalled(this)) {
            beginServer();
            return;
        }
        bar.setVisibility(View.VISIBLE);
        status.setText("Preparing runtime (one time, ~180 MB)...");
        status.setVisibility(View.VISIBLE);
        new Thread(new Runnable() {
            public void run() {
                try {
                    Installer.install(MainActivity.this, new Installer.Progress() {
                        public void onBytes(final long copied) {
                            runOnUiThread(new Runnable() {
                                public void run() {
                                    status.setText("Installing runtime... "
                                            + (copied / 1048576) + " MB");
                                }
                            });
                        }
                    });
                    runOnUiThread(new Runnable() {
                        public void run() {
                            status.setVisibility(View.GONE);
                            bar.setVisibility(View.GONE);
                            beginServer();
                        }
                    });
                } catch (Throwable t) {
                    Log.e(TAG, "install failed", t);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            status.setText("Install failed: " + t.getMessage());
                        }
                    });
                }
            }
        }, "oc-install").start();
    }

    private void beginServer() {
        Log.d(TAG, "beginServer");
        askStorage();
        ensureServer();
        loadWhenReady();
    }

    private void ensureServer() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastSpawn < SPAWN_THROTTLE) return;
        lastSpawn = now;
        try {
            ServerService.start(this);
        } catch (Throwable t) {
            Log.e(TAG, "cannot start service", t);
        }
    }

    private void loadWhenReady() {
        // ping() must not run on the main thread: NetworkOnMainThreadException
        // made it fail forever, which was the "blank UI" bug.
        if (waiting) return;
        waiting = true;
        new Thread(new Runnable() {
            public void run() {
                final boolean ok = ping();
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (ok) {
                            waiting = false;
                            if (!loadedOnce) load();
                            return;
                        }
                        if (retries >= MAX_RETRIES) {
                            waiting = false;
                            Log.d(TAG, "loadWhenReady: giving up after " + retries + " attempts");
                            return;
                        }
                        retries++;
                        Log.d(TAG, "loadWhenReady attempt " + retries + " (ping failed)");
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
        }, "oc-ping").start();
    }

    private void load() {
        retries = 0;
        bar.setVisibility(View.VISIBLE);
        web.setVisibility(View.VISIBLE);
        Log.d(TAG, "load " + URL);
        web.loadUrl(URL);
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
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
            if (req != null && req.isForMainFrame()) {
                Log.d(TAG, "main frame error " + err.getErrorCode() + ": " + err.getDescription()
                        + " url=" + req.getUrl());
                loadedOnce = false;
                view.loadUrl("about:blank");
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
                toast("No file picker available");
                return false;
            }
            return true;
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
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
                                      android.os.Message resultMsg) {
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
            WebView.WebViewTransport t = (WebView.WebViewTransport) resultMsg.obj;
            t.setWebView(popup);
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

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_MIC) return;
        if (pendingPermission == null) return;
        boolean ok = grantResults.length > 0
                && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (ok) pendingPermission.grant(pendingPermission.getResources());
        else pendingPermission.deny();
        pendingPermission = null;
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    private void openExternal(Uri u) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, u);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            toast("No app can open this link");
        }
    }

    private void retry() {
        loadWhenReady();
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
            in.read(new byte[1]);
            in.close();
            Log.d(TAG, "ping ok code=" + code);
            return code >= 200 && code < 400;
        } catch (Throwable t) {
            Log.d(TAG, "ping failed: " + t);
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
        View f = getCurrentFocus();
        if (f != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
        }
        web.clearFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web == null) return;
        if (!Installer.isInstalled(this)) return;
        // no synchronous ping here (it would hit NetworkOnMainThreadException);
        // the async chain decides whether a reload is needed via loadedOnce
        retries = 0;
        ensureServer();
        loadWhenReady();
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
            toast("Press back again to exit");
        }
    }
}
