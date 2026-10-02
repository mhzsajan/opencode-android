package app.opencode.launcher;

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

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {

    private static final String TAG = "oclaunch";
    private static final String TERMUX = "com.termux";
    private static final String SERVICE = "com.termux.app.RunCommandService";
    private static final String HOME = "/data/data/com.termux/files/home";
    private static final String PREFIX = "/data/data/com.termux/files/usr";
    private static final String SERVER_SCRIPT = HOME + "/.shortcuts/oc-server";
    private static final String PROJECT = HOME + "/myproject";
    private static final String URL = "http://127.0.0.1:4096/";
    private static final int MAX_RETRIES = 40;
    private static final long SPAWN_THROTTLE = 5000;

    private static final String PERM = "com.termux.permission.RUN_COMMAND";
    private static final int REQ_PERM = 7;
    private static final int REQ_FILE = 101;
    private static final int REQ_MIC = 102;

    private WebView web;
    private ProgressBar bar;
    private int retries = 0;
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
        web.getSettings().setCacheMode(android.webkit.WebSettings.LOAD_DEFAULT);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        bar = new ProgressBar(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(bar, lp);

        setContentView(root);

        if (checkSelfPermission(PERM) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            start();
        } else {
            requestPermissions(new String[] { PERM }, REQ_PERM);
        }
    }

    private void start() {
        if (!ping()) ensureServer();
        load();
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
                    "opencode needs permission to run commands in Termux", android.widget.Toast.LENGTH_LONG).show();
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
            bar.setVisibility(View.GONE);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
            if (req != null && req.isForMainFrame()) retry();
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest req, WebResourceResponse err) {
            if (req != null && req.isForMainFrame()) retry();
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
                for (int i = 0; i < n; i++) {
                    out[i] = data.getClipData().getItemAt(i).getUri();
                }
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
        if (retries >= MAX_RETRIES) return;
        retries++;
        bar.setVisibility(View.VISIBLE);
        web.postDelayed(new Runnable() {
            public void run() {
                if (ping()) {
                    retries = 0;
                    web.loadUrl(URL);
                } else {
                    ensureServer();
                    retry();
                }
            }
        }, 1000);
    }

    private void load() {
        retries = 0;
        bar.setVisibility(View.VISIBLE);
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
        } catch (Throwable ignored) {
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
        if (!ping()) start();
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
