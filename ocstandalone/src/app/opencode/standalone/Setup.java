package app.opencode.standalone;

import android.Manifest;
import android.app.Activity;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * First-run setup shown before the WebView.
 *
 * Why this exists: the app can read and write the phone's files, so it needs the
 * storage/media grants, and opencode needs a provider key before chat works.
 * Requesting them one screen at a time with live state is better than the old
 * behaviour, where a missing grant only surfaced as a confusing failure deep
 * inside a tool call much later.
 *
 * Nothing here is mandatory -- every group can be skipped and revisited from
 * this screen at any time, because the user's choice is the point.
 */
public class Setup extends Activity {

    public static final String PREFS = "oc_setup";

    /** One row in the permission list. */
    private static final class Item {
        final String label;
        final String why;
        final String[] perms;
        Item(String label, String why, String... perms) {
            this.label = label;
            this.why = why;
            this.perms = perms;
        }
    }

    private Item[] items;
    private TextView[] stateViews;
    private LinearLayout list;
    private TextView summary;
    private TextView keyNote;
    private EditKey keyField;
    private Button continueBtn;

    // OpenCode account (device OAuth) state.
    private TextView accountNote;
    private Button signInBtn;
    private Button signOutBtn;
    private volatile Thread signInThread;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** Minimal inline field: no XML inflation, matches the rest of the app. */
    static class EditKey extends android.widget.EditText {
        EditKey(Activity a) {
            super(a);
            setSingleLine(true);
            setTextColor(0xFFEEEEEE);
            setHintTextColor(0xFF6E6E73);
            setHint("oc_sk_... or sk-...");
            setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
    }

    private static Item[] catalogue() {
        java.util.List<Item> out = new java.util.ArrayList<Item>();
        // READ/WRITE_EXTERNAL_STORAGE are declared with maxSdkVersion="28" in the
        // manifest, so on Android 10+ requestPermissions() returns "denied"
        // without showing any dialog -- the Allow button looked dead (reported on
        // Android 16). All-files access, which has its own row below, is the
        // supported path on those versions.
        if (Build.VERSION.SDK_INT <= 28) {
            out.add(new Item("Files & folders",
                    "Read and edit anything in your phone's storage",
                    "android.permission.READ_EXTERNAL_STORAGE",
                    "android.permission.WRITE_EXTERNAL_STORAGE"));
        }
        if (Build.VERSION.SDK_INT >= 33) {
            out.add(new Item("Photos & videos",
                    "Let the agent work with your photo and video libraries",
                    "android.permission.READ_MEDIA_IMAGES",
                    "android.permission.READ_MEDIA_VIDEO"));
            out.add(new Item("Music & audio",
                    "Access to your audio files",
                    "android.permission.READ_MEDIA_AUDIO"));
        } else {
            out.add(new Item("Media", "Access to photos, video and audio",
                    "android.permission.READ_EXTERNAL_STORAGE"));
        }
        out.add(new Item("Camera", "Take photos when a task needs one",
                "android.permission.CAMERA"));
        out.add(new Item("Contacts", "Look up names and numbers you ask about",
                "android.permission.READ_CONTACTS",
                "android.permission.WRITE_CONTACTS"));
        out.add(new Item("Location", "Answer questions about where you are",
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.ACCESS_COARSE_LOCATION"));
        out.add(new Item("Nearby devices",
                "See Bluetooth and Wi-Fi devices around you",
                "android.permission.BLUETOOTH_CONNECT",
                "android.permission.BLUETOOTH_SCAN"));
        out.add(new Item("Phone", "Device and network details when a task needs them",
                "android.permission.READ_PHONE_STATE"));
        out.add(new Item("Microphone", "Dictate a prompt instead of typing",
                "android.permission.RECORD_AUDIO"));
        return out.toArray(new Item[0]);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xFF0B0B0D);
        getWindow().setNavigationBarColor(0xFF0B0B0D);

        items = catalogue();
        stateViews = new TextView[items.length];

        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        col.setPadding(pad, dp(24), pad, dp(32));
        scroll.addView(col, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("Set up opencode solo", 22, 0xFFFFFFFF);
        title.setPadding(0, 0, 0, dp(8));
        col.addView(title);

        TextView blurb = text("This app runs the opencode coding agent on your phone. "
                + "Grant what you want it to touch — everything here is optional and "
                + "changeable later from this same screen.", 14, 0xFFA0A0A6);
        blurb.setLineSpacing(0, 1.25f);
        blurb.setPadding(0, 0, 0, dp(20));
        col.addView(blurb);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        col.addView(list);

        for (int i = 0; i < items.length; i++) {
            list.addView(row(i));
        }

        // All-files access is special: it is not a runtime permission, it is a
        // special app-access setting. It is the one that actually lets the agent
        // edit files outside its own sandbox, so it gets its own row.
        col.addView(storageRow());

        col.addView(accountSection());
        col.addView(keySection());

        summary = text("", 13, 0xFF8E8E93);
        summary.setPadding(0, dp(16), 0, dp(8));
        col.addView(summary);

        continueBtn = new Button(this);
        continueBtn.setText("Start opencode");
        continueBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                saveKey();
                getSharedPreferences(PREFS, MODE_PRIVATE)
                        .edit().putBoolean("seen", true).apply();
                startActivity(new Intent(Setup.this, MainActivity.class));
                finish();
            }
        });
        col.addView(continueBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView later = text("You can change any of this later by reopening setup.", 12, 0xFF6E6E73);
        later.setGravity(Gravity.CENTER);
        later.setPadding(0, dp(12), 0, 0);
        col.addView(later);

        setContentView(scroll);
        refresh();
    }

    private View row(final int i) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(14), 0, dp(14));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = text(items[i].label, 16, 0xFFFFFFFF);
        head.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView state = text("", 13, 0xFF8E8E93);
        state.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        state.setPadding(dp(10), 0, 0, 0);
        head.addView(state);
        stateViews[i] = state;

        box.addView(head);

        TextView why = text(items[i].why, 13, 0xFF8E8E93);
        why.setPadding(0, dp(2), 0, dp(8));
        box.addView(why);

        Button b = new Button(this);
        b.setText("Allow");
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ask(i);
            }
        });
        box.addView(b, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        box.setTag(new Object[]{items[i], b, state});
        return box;
    }

    private View storageRow() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(14), 0, dp(14));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text("All files access", 16, 0xFFFFFFFF), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView state = text("", 13, 0xFF8E8E93);
        state.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        head.addView(state);

        box.addView(head);
        TextView why = text("Lets opencode edit files anywhere on your phone, "
                + "including Downloads. Android treats this separately.", 13, 0xFF8E8E93);
        why.setPadding(0, dp(2), 0, dp(8));
        box.addView(why);

        Button b = new Button(this);
        b.setText("Open settings");
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Throwable t) {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                    } catch (Throwable t2) {
                        keyNote.setText("Could not open that settings screen on this phone.");
                    }
                }
            }
        });
        box.addView(b);
        storageState = state;
        return box;
    }

    private TextView storageState;

    private View accountSection() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(20), 0, 0);

        box.addView(text("OpenCode account", 16, 0xFFFFFFFF));
        TextView why = text("Sign in with your OpenCode account to use your Console "
                + "or Go subscription without pasting a key. The device flow shows a "
                + "code to enter on opencode.ai; this app never sees your password. "
                + "Stored on this phone only.", 13, 0xFF8E8E93);
        why.setPadding(0, dp(2), 0, dp(8));
        box.addView(why);

        accountNote = text("Not signed in.", 13, 0xFFE0A458);
        accountNote.setPadding(0, dp(2), 0, dp(8));
        box.addView(accountNote);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        signInBtn = new Button(this);
        signInBtn.setText("Sign in with OpenCode");
        signInBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startSignIn();
            }
        });
        row.addView(signInBtn, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        signOutBtn = new Button(this);
        signOutBtn.setText("Change account");
        signOutBtn.setVisibility(View.GONE);
        signOutBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                signOut();
            }
        });
        row.addView(signOutBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(row);

        refreshAccountState();
        return box;
    }

    private void refreshAccountState() {
        new Thread(new Runnable() {
            public void run() {
                if (!serverReady()) {
                    ui.post(new Runnable() {
                        public void run() {
                            accountNote.setText("Server not running yet. Tap \"Start opencode\" first, "
                                    + "then reopen Setup via the gear icon to sign in.");
                            accountNote.setTextColor(0xFFE0A458);
                            signInBtn.setEnabled(false);
                            signInBtn.setVisibility(View.VISIBLE);
                            signOutBtn.setVisibility(View.GONE);
                        }
                    });
                    return;
                }
                final String label = fetchAccountLabel();
                ui.post(new Runnable() {
                    public void run() {
                        signInBtn.setEnabled(true);
                        if (label != null) {
                            accountNote.setText("Connected: " + label);
                            accountNote.setTextColor(0xFF7BD88F);
                            signInBtn.setVisibility(View.GONE);
                            signOutBtn.setVisibility(View.VISIBLE);
                        } else {
                            accountNote.setText("Not signed in.");
                            accountNote.setTextColor(0xFFE0A458);
                            signInBtn.setVisibility(View.VISIBLE);
                            signOutBtn.setVisibility(View.GONE);
                        }
                    }
                });
            }
        }).start();
    }

    private boolean serverReady() {
        try {
            // 4096 is the server itself and answers 401 without credentials, which
            // still proves the server (and its shim) are alive. 4097 is the auth
            // proxy and answers 200. Either one being reachable means ready.
            for (String url : new String[] { "http://127.0.0.1:4097/api/info",
                    "http://127.0.0.1:4097/api/info" }) {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(2000);
                    c.setReadTimeout(2000);
                    int code = c.getResponseCode();
                    c.disconnect();
                    if (code == 200 || code == 401) return true;
                } catch (Throwable t) { /* try next */ }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private String fetchAccountLabel() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:4097/api/integration/opencode").openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            if (c.getResponseCode() != 200) return null;
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            JSONObject o = new JSONObject(sb.toString());
            JSONObject data = o.optJSONObject("data");
            if (data == null) return null;
            JSONArray conns = data.optJSONArray("connections");
            if (conns == null || conns.length() == 0) return null;
            JSONObject conn = conns.optJSONObject(0);
            if (conn == null) return null;
            String lbl = conn.optString("label", null);
            if (lbl == null || lbl.length() == 0) lbl = "OpenCode account";
            return lbl;
        } catch (Throwable t) {
            return null;
        }
    }

    private void startSignIn() {
        if (signInThread != null && signInThread.isAlive()) return;
        signInBtn.setEnabled(false);
        accountNote.setText("Starting sign-in...");
        accountNote.setTextColor(0xFF8E8E93);
        signInThread = new Thread(new Runnable() {
            public void run() {
                try {
                    if (!serverReady()) {
                        ui.post(new Runnable() { public void run() {
                            accountNote.setText("Server not running. Tap \"Start opencode\" first, "
                                    + "then reopen Setup via the gear icon.");
                            accountNote.setTextColor(0xFFE0A458);
                            signInBtn.setEnabled(true);
                        } });
                        return;
                    }
                    // Begin the device OAuth attempt.
                    HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:4097/api/integration/opencode/connect/oauth").openConnection();
                    c.setRequestMethod("POST");
                    c.setRequestProperty("Content-Type", "application/json");
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(30000);
                    c.setDoOutput(true);
                    OutputStream os = c.getOutputStream();
                    os.write("{\"methodID\":\"device\"}".getBytes("UTF-8"));
                    os.close();
                    if (c.getResponseCode() != 200) {
                        final String err = "Sign-in failed to start (HTTP " + c.getResponseCode() + ").";
                        ui.post(new Runnable() { public void run() { accountNote.setText(err); accountNote.setTextColor(0xFFE0A458); signInBtn.setEnabled(true); } });
                        return;
                    }
                    BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                    r.close();
                    JSONObject o = new JSONObject(sb.toString());
                    JSONObject data = o.getJSONObject("data");
                    final String attemptID = data.getString("attemptID");
                    final String url = data.getString("url");
                    final String instructions = data.optString("instructions", "Open the URL and enter the code.");
                    long expires = data.optJSONObject("time") != null ? data.getJSONObject("time").optLong("expires", 0) : 0;

                    ui.post(new Runnable() {
                        public void run() {
                            showSignInDialog(attemptID, url, instructions);
                        }
                    });

                    // Poll until complete / failed / expired / timeout.
                    long deadline = expires > 0 ? expires : (System.currentTimeMillis() + 10 * 60 * 1000);
                    while (System.currentTimeMillis() < deadline && signInThread == Thread.currentThread()) {
                        Thread.sleep(2000);
                        if (signInThread != Thread.currentThread()) return;
                        HttpURLConnection pc = (HttpURLConnection) new URL("http://127.0.0.1:4097/api/integration/opencode/connect/oauth/" + attemptID).openConnection();
                        pc.setConnectTimeout(10000);
                        pc.setReadTimeout(15000);
                        if (pc.getResponseCode() != 200) { pc.disconnect(); continue; }
                        BufferedReader pr = new BufferedReader(new InputStreamReader(pc.getInputStream(), "UTF-8"));
                        StringBuilder psb = new StringBuilder();
                        String pline;
                        while ((pline = pr.readLine()) != null) psb.append(pline);
                        pr.close();
                        pc.disconnect();
                        JSONObject po = new JSONObject(psb.toString());
                        JSONObject pdata = po.optJSONObject("data");
                        String status = pdata != null ? pdata.optString("status", "pending") : "pending";
                        if ("complete".equals(status)) {
                            ui.post(new Runnable() {
                                public void run() {
                                    accountNote.setText("Connected.");
                                    accountNote.setTextColor(0xFF7BD88F);
                                    signInBtn.setVisibility(View.GONE);
                                    signOutBtn.setVisibility(View.VISIBLE);
                                    if (signInDialog != null) { signInDialog.dismiss(); signInDialog = null; }
                                }
                            });
                            refreshAccountState();
                            return;
                        }
                        if ("failed".equals(status)) {
                            final String msg = pdata != null ? pdata.optString("message", "Sign-in failed.") : "Sign-in failed.";
                            ui.post(new Runnable() { public void run() { accountNote.setText(msg); accountNote.setTextColor(0xFFE0A458); signInBtn.setEnabled(true); if (signInDialog != null) { signInDialog.dismiss(); signInDialog = null; } } });
                            return;
                        }
                        if ("expired".equals(status)) {
                            ui.post(new Runnable() { public void run() { accountNote.setText("Sign-in expired. Try again."); accountNote.setTextColor(0xFFE0A458); signInBtn.setEnabled(true); if (signInDialog != null) { signInDialog.dismiss(); signInDialog = null; } } });
                            return;
                        }
                    }
                    ui.post(new Runnable() { public void run() { accountNote.setText("Sign-in timed out. Try again."); accountNote.setTextColor(0xFFE0A458); signInBtn.setEnabled(true); if (signInDialog != null) { signInDialog.dismiss(); signInDialog = null; } } });
                } catch (Throwable t) {
                    final String msg = (t instanceof java.net.ConnectException || t instanceof java.net.UnknownHostException)
                            ? "Could not reach the server. Open the app first, then try again."
                            : (t instanceof java.net.SocketTimeoutException)
                            ? "The server took too long to respond. Try again."
                            : "Sign-in error: " + t.getClass().getSimpleName()
                                    + (t.getMessage() != null ? " - " + t.getMessage() : "");
                    ui.post(new Runnable() { public void run() { accountNote.setText(msg); accountNote.setTextColor(0xFFE0A458); signInBtn.setEnabled(true); if (signInDialog != null) { signInDialog.dismiss(); signInDialog = null; } } });
                }
            }
        });
        signInThread.start();
    }

    private android.app.AlertDialog signInDialog;

    private void showSignInDialog(final String attemptID, final String url, final String instructions) {
        if (signInDialog != null) { try { signInDialog.dismiss(); } catch (Throwable t) {} }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(12));
        TextView t = text(instructions, 15, 0xFFFFFFFF);
        t.setPadding(0, 0, 0, dp(12));
        box.addView(t);
        TextView u = text(url, 12, 0xFF8E8E93);
        u.setPadding(0, 0, 0, dp(12));
        box.addView(u);
        Button open = new Button(this);
        open.setText("Open in browser");
        open.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
                catch (Throwable t) { accountNote.setText("Could not open browser."); }
            }
        });
        box.addView(open);
        TextView waiting = text("Waiting for approval on opencode.ai...", 13, 0xFF8E8E93);
        waiting.setPadding(0, dp(8), 0, 0);
        box.addView(waiting);

        signInDialog = new android.app.AlertDialog.Builder(this)
                .setTitle("Sign in with OpenCode")
                .setView(box)
                .setCancelable(true)
                .setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
                    public void onCancel(android.content.DialogInterface d) {
                        signInThread = null;
                        signInBtn.setEnabled(true);
                        accountNote.setText("Sign-in cancelled.");
                        accountNote.setTextColor(0xFFE0A458);
                    }
                })
                .create();
        signInDialog.show();
    }

    private void signOut() {
        signOutBtn.setEnabled(false);
        new Thread(new Runnable() {
            public void run() {
                try {
                    // Find and delete any stored credential for this integration.
                    HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:4097/api/integration/opencode").openConnection();
                    c.setConnectTimeout(5000);
                    c.setReadTimeout(5000);
                    if (c.getResponseCode() == 200) {
                        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line);
                        r.close();
                        JSONObject o = new JSONObject(sb.toString());
                        JSONObject data = o.optJSONObject("data");
                        JSONArray conns = data != null ? data.optJSONArray("connections") : null;
                        if (conns != null) {
                            for (int i = 0; i < conns.length(); i++) {
                                JSONObject conn = conns.optJSONObject(i);
                                if (conn == null) continue;
                                String cid = conn.optString("id", null);
                                if (cid == null || cid.length() == 0) continue;
                                HttpURLConnection dc = (HttpURLConnection) new URL("http://127.0.0.1:4097/api/credential/" + cid).openConnection();
                                dc.setRequestMethod("DELETE");
                                dc.setConnectTimeout(5000);
                                dc.setReadTimeout(5000);
                                dc.getResponseCode();
                                dc.disconnect();
                            }
                        }
                    }
                    c.disconnect();
                } catch (Throwable t) {
                    // best effort
                }
                ui.post(new Runnable() {
                    public void run() {
                        accountNote.setText("Signed out.");
                        accountNote.setTextColor(0xFFE0A458);
                        signInBtn.setVisibility(View.VISIBLE);
                        signInBtn.setEnabled(true);
                        signOutBtn.setVisibility(View.GONE);
                        signOutBtn.setEnabled(true);
                    }
                });
            }
        }).start();
    }

    private View keySection() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(20), 0, 0);

        box.addView(text("Provider key", 16, 0xFFFFFFFF));
        TextView why = text("opencode needs an API key to talk to a model. "
                + "Stored on this phone only. You can also add providers later from "
                + "inside the app.", 13, 0xFF8E8E93);
        why.setPadding(0, dp(2), 0, dp(8));
        box.addView(why);

        keyField = new EditKey(this);
        String existing = Auth.read(this);
        if (existing != null) keyField.setText(existing);
        box.addView(keyField, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout keyRow = new LinearLayout(this);
        keyRow.setOrientation(LinearLayout.HORIZONTAL);
        keyRow.setGravity(Gravity.CENTER_VERTICAL);

        Button paste = new Button(this);
        paste.setText("Paste");
        paste.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                            && cm.getPrimaryClip().getItemCount() > 0) {
                        CharSequence txt = cm.getPrimaryClip().getItemAt(0).coerceToText(Setup.this);
                        if (txt != null) keyField.setText(txt.toString().trim());
                    }
                } catch (Throwable t) { /* ignore */ }
            }
        });
        keyRow.addView(paste, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final Button showHide = new Button(this);
        showHide.setText("Show");
        showHide.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (keyField.getInputType() == (android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)) {
                    keyField.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                            | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                    showHide.setText("Hide");
                } else {
                    keyField.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                            | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
                    showHide.setText("Show");
                }
            }
        });
        keyRow.addView(showHide, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(keyRow);

        Button save = new Button(this);
        save.setText("Save key");
        save.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                saveKey();
            }
        });
        box.addView(save);

        keyNote = text("", 13, 0xFF7BD88F);
        keyNote.setPadding(0, dp(6), 0, 0);
        box.addView(keyNote);

        return box;
    }

    private void saveKey() {
        String k = keyField.getText().toString().trim();
        if (k.length() == 0) {
            keyNote.setText("No key saved. Chat will not work until one is added.");
            keyNote.setTextColor(0xFFE0A458);
            return;
        }
        Auth.write(this, k);
        keyNote.setText("Key saved.");
        keyNote.setTextColor(0xFF7BD88F);
    }

    private void ask(int i) {
        Item it = items[i];
        String[] want = new String[it.perms.length];
        for (int k = 0; k < it.perms.length; k++) want[k] = it.perms[k];
        try {
            requestPermissions(want, 900 + i);
        } catch (Throwable t) {
            stateViews[i].setText("unavailable");
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(code, perms, grants);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        refreshAccountState();
    }

    @Override
    protected void onDestroy() {
        signInThread = null;
        if (signInDialog != null) {
            try { signInDialog.dismiss(); } catch (Throwable t) {}
            signInDialog = null;
        }
        super.onDestroy();
    }

    private boolean allGranted(Item it) {
        for (String p : it.perms) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private void refresh() {
        int granted = 0;
        for (int i = 0; i < items.length; i++) {
            Item it = items[i];
            boolean ok = allGranted(it);
            ViewGroup box = (ViewGroup) list.getChildAt(i);
            Object[] tag = (Object[]) box.getTag();
            Button b = (Button) tag[1];
            TextView state = (TextView) tag[2];
            if (ok) {
                granted++;
                state.setText("allowed");
                state.setTextColor(0xFF7BD88F);
                b.setVisibility(View.GONE);
            } else {
                state.setText("not allowed");
                state.setTextColor(0xFFE0A458);
                b.setVisibility(View.VISIBLE);
            }
        }
        boolean files = Projects.hasFullAccess();
        if (storageState != null) {
            storageState.setText(files ? "allowed" : "not allowed");
            storageState.setTextColor(files ? 0xFF7BD88F : 0xFFE0A458);
        }
        if (summary != null) {
            summary.setText(granted + " of " + items.length + " permission groups allowed"
                    + (files ? ", files: yes" : ", files: no"));
        }
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    /**
     * Reads/writes the provider key the server actually uses:
     * filesDir/home/.local/share/opencode/auth.json, which is where opencode
     * looks given the HOME the service exports.
     */
    static final class Auth {
        private static File file(Activity a) {
            File d = new File(a.getFilesDir(), "home/.local/share/opencode");
            if (!d.exists()) d.mkdirs();
            return new File(d, "auth.json");
        }

        static String read(Activity a) {
            try {
                java.io.FileInputStream in = new java.io.FileInputStream(file(a));
                byte[] b = new byte[8192];
                int n = in.read(b);
                in.close();
                String s = new String(b, 0, Math.max(n, 0));
                int i = s.indexOf("\"key\"");
                if (i < 0) return null;
                int q1 = s.indexOf('"', s.indexOf(':', i)) ;
                int q2 = s.indexOf('"', q1 + 1);
                return q2 > 0 ? s.substring(q1 + 1, q2) : null;
            } catch (Throwable t) {
                return null;
            }
        }

        static void write(Activity a, String key) {
            try {
                String json = "{\n  \"opencode\": {\n    \"type\": \"api\",\n    \"key\": \""
                        + key.replace("\\", "\\\\").replace("\"", "\\\"") + "\"\n  }\n}\n";
                FileOutputStream out = new FileOutputStream(file(a));
                out.write(json.getBytes("UTF-8"));
                out.close();
            } catch (Throwable t) {
                // the caller already surfaced this via keyNote
            }
        }
    }
}