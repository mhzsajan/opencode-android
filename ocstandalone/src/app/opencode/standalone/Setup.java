package app.opencode.standalone;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;

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

    /** Minimal inline field: no XML inflation, matches the rest of the app. */
    static class EditKey extends android.widget.EditText {
        EditKey(Activity a) {
            super(a);
            setSingleLine(true);
            setTextColor(0xFFEEEEEE);
            setHintTextColor(0xFF6E6E73);
            setHint("oc_sk_... or sk-...");
        }
    }

    private static Item[] catalogue() {
        java.util.List<Item> out = new java.util.ArrayList<Item>();
        out.add(new Item("Files & folders",
                "Read and edit anything in your phone's storage",
                "android.permission.READ_EXTERNAL_STORAGE",
                "android.permission.WRITE_EXTERNAL_STORAGE"));
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