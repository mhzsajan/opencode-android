package app.opencode.standalone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

public class ServerService extends Service {

    private static final String TAG = "ocstandalone";
    public static final int PORT = 4096;
    private static final String CHANNEL = "opencode_server";
    private static final int NOTIF_ID = 41;

    /** opencode v2 authenticates its API with HTTP Basic: user + server password. */
    public static final String PREFS = "oc_server";
    public static final String PREF_PASSWORD = "password";
    public static final String AUTH_USER = "opencode";

    public static String password(Context c) {
        if (c == null) return null;
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_PASSWORD, null);
    }

    private String ensurePassword() {
        android.content.SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String pw = sp.getString(PREF_PASSWORD, null);
        if (pw == null || pw.length() < 16) {
            byte[] b = new byte[24];
            new java.security.SecureRandom().nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            pw = sb.toString();
            sp.edit().putString(PREF_PASSWORD, pw).apply();
        }
        return pw;
    }

    private static volatile boolean running = false;
    private volatile Process proc;
    private Thread watcher;
    private volatile boolean stopping = false;
    private AuthProxy proxy;

    public static boolean isRunning() {
        return running;
    }

    public static void start(Context c) {
        Intent i = new Intent(c, ServerService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, ServerService.class));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        stopping = false;
        goForeground("starting");
        // The WebView talks to the proxy, which adds opencode v2's Basic auth header.
        proxy = new AuthProxy(ensurePassword());
        proxy.start();
        watcher = new Thread(new Runnable() {
            public void run() {
                supervise();
            }
        }, "oc-supervisor");
        watcher.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop".equals(intent.getAction())) {
            stopping = true;
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopping = true;
        if (proxy != null) {
            proxy.stop();
            proxy = null;
        }
        killTree();
        running = false;
        super.onDestroy();
    }

    private void goForeground(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, ServerService.class);
        stop.setAction("stop");
        PendingIntent si = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle(getString(R.string.notif_title))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(pi)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, getString(R.string.notif_stop), si).build());
        Notification n = b.build();

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void supervise() {
        long backoff = 1000;
        while (!stopping) {
            if (portServed()) {
                // Something already answers on PORT - typically the opencode CLI
                // running in Termux. Adopt it instead of fighting for the port:
                // our own spawn would die rc=1 (EADDRINUSE) in a restart loop,
                // which is what the endless "server exited rc=1" spam was.
                Log.i(TAG, "port " + PORT + " already served externally, adopting");
                goForeground("serving on port " + PORT + " (existing)");
                while (!stopping && portServed()) sleep(5000);
                if (stopping) break;
                Log.i(TAG, "external server gone, spawning our own");
                // fall through to launch()
            }
            boolean ok = launch();
            if (stopping) break;
            if (ok) {
                backoff = 1000;
                int code = waitFor();
                if (stopping) break;
                Log.w(TAG, "server exited rc=" + code + ", restarting");
                goForeground("restarting");
                sleep(1000);
            } else {
                Log.e(TAG, "launch failed, retry in " + backoff + "ms");
                goForeground("failed to start, retrying");
                sleep(backoff);
                backoff = Math.min(backoff * 2, 30000);
            }
        }
    }

    /** True if something already answers on PORT. Short timeouts so the supervisor
     *  thread can never hang on a frozen/half-open listener. */
    private boolean portServed() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://127.0.0.1:" + PORT + "/").openConnection();
            c.setConnectTimeout(700);
            c.setReadTimeout(700);
            c.setRequestMethod("GET");
            c.getResponseCode();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * Resolver configuration for the bundled server.
     *
     * Each payload's libc has its resolver path compiled in, so build.ps1
     * patches it per architecture:
     *
     *   aarch64, glibc (Termux's libc, 53-byte slot)
     *     /data/data/com.termux/files/usr/glibc/etc/resolv.conf
     *       -> /data/data/app.opencode.standalone/files/resolv.conf   (52 chars)
     *
     *   x86_64, musl (ld-musl-x86_64.so.1, 16-byte slot)
     *     /etc/resolv.conf
     *       -> .resolv.conf
     *
     * The x86_64 slot cannot hold an app-private absolute path, so its patched
     * name is relative: the libc resolves it against the child's cwd, which
     * launch() sets to the project directory. writeResolvConf() writes the file
     * into that directory too when the packaged payload is x86_64.
     *
     * Without this, res_init() finds no nameserver and every lookup fails
     * ("Failed to fetch models.dev ... Unable to connect"). The env vars used in
     * v1.3/v1.4 (RESOLV_CONF, DNS_RESULT_ORDER, DNS_AUTO_FALLBACK) are not read
     * by the binary at all - a strings scan of opencode-bin finds none of them.
     */
    private static final String RESOLV_CONF = "resolv.conf";
    /** Relative name patched into the x86_64 libc; resolved against the cwd. */
    private static final String RESOLV_CONF_X64 = ".resolv.conf";

    private void writeResolvConf(File project) {
        java.util.LinkedHashSet<String> servers = new java.util.LinkedHashSet<String>();
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm != null) {
                for (android.net.Network n : cm.getAllNetworks()) {
                    android.net.LinkProperties lp = cm.getLinkProperties(n);
                    if (lp == null) continue;
                    for (java.net.InetAddress a : lp.getDnsServers()) {
                        if (a == null) continue;
                        String h = a.getHostAddress();
                        if (h != null && h.length() > 0) servers.add(h);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "cannot read DNS servers: " + t);
        }
        // Public resolvers last: they keep a working network usable if Android's
        // per-network list is empty (e.g. Wi-Fi just came up).
        servers.add("8.8.8.8");
        servers.add("1.1.1.1");

        StringBuilder sb = new StringBuilder();
        for (String s : servers) sb.append("nameserver ").append(s).append('\n');
        sb.append("options timeout:5 attempts:2\n");

        File f = new File(getFilesDir(), RESOLV_CONF);
        try {
            FileOutputStream out = new FileOutputStream(f);
            out.write(sb.toString().getBytes("UTF-8"));
            out.close();
            Log.i(TAG, "resolv.conf -> " + sb.toString().replace("\n", " | "));
        } catch (Throwable t) {
            Log.e(TAG, "cannot write resolv.conf", t);
        }

        // The x86_64 libc's patched path is relative, so the file must sit in
        // the child's cwd. launch() sets that to the project directory.
        if (Installer.isX64(this) && project != null) {
            File rel = new File(project, RESOLV_CONF_X64);
            try {
                FileOutputStream out = new FileOutputStream(rel);
                out.write(sb.toString().getBytes("UTF-8"));
                out.close();
                Log.i(TAG, RESOLV_CONF_X64 + " -> " + rel.getAbsolutePath());
            } catch (Throwable t) {
                Log.e(TAG, "cannot write " + RESOLV_CONF_X64, t);
            }
        }
    }

    private boolean launch() {
        File lib = Installer.libDir(this);
        File ld = Installer.loader(this);
        File bin = Installer.binary(this);
        File shim = Installer.shim(this);
        if (!ld.exists() || !bin.exists()) {
            Log.e(TAG, "payload missing, run Installer first");
            return false;
        }
        File home = Installer.home(this);
        File project = Projects.dir(this);
        if (!project.exists()) project.mkdirs();
        File tmp = getCacheDir();
        File log = new File(home, "server.log");

        // The bundled libc has this file's path compiled in (see RESOLV_CONF
        // above); without it glibc cannot resolve anything.
        writeResolvConf(project);

        // opencode v2's server requires HTTP Basic auth on /api/*. Give it a
        // fixed per-install password (rather than the random one it prints at
        // startup) so the WebView can answer the auth challenge; MainActivity
        // supplies it in onReceivedHttpAuthRequest.
        String password = ensurePassword();

        // --library-path is not redundant with LD_LIBRARY_PATH: on an x86_64
        // image running a translated arm64 guest the env var never reaches the
        // loader, while this flag does. Pass both.
        //
        // The x86_64 payload runs under the seccomp shim, which traces it and
        // replays the syscalls this app's bionic-derived filter traps (see
        // shim/shim.c). The aarch64 payload needs no such thing.
        java.util.ArrayList<String> cmd = new java.util.ArrayList<String>();
        if (shim != null) cmd.add(shim.getAbsolutePath());
        cmd.add(ld.getAbsolutePath());
        cmd.add("--library-path");
        cmd.add(lib.getAbsolutePath());
        cmd.add(bin.getAbsolutePath());
        cmd.add("serve");
        cmd.add("--port");
        cmd.add(String.valueOf(PORT));
        cmd.add("--hostname");
        cmd.add("127.0.0.1");
        cmd.add("--print-logs");
        cmd.add("--log-level");
        cmd.add("info");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(project);
        Map<String, String> env = pb.environment();
        env.clear();
        env.put("LD_LIBRARY_PATH", lib.getAbsolutePath());
        env.put("HOME", home.getAbsolutePath());
        env.put("PATH", "/system/bin");
        env.put("TMPDIR", tmp.getAbsolutePath());
        env.put("XDG_DATA_HOME", new File(home, ".local/share").getAbsolutePath());
        env.put("XDG_CONFIG_HOME", new File(home, ".config").getAbsolutePath());
        env.put("XDG_CACHE_HOME", new File(home, ".cache").getAbsolutePath());
        env.put("OPENCODE_DISABLE_AUTOUPDATE", "1");
        env.put("OPENCODE_SERVER_PASSWORD", password);

        try {
            proc = pb.start();
        } catch (Throwable t) {
            Log.e(TAG, "spawn failed", t);
            return false;
        }
        pump(proc.getInputStream(), log);
        pump(proc.getErrorStream(), log);
        goForeground("serving on port " + PORT);
        Log.i(TAG, "spawned pid=" + safePid());
        return true;
    }

    private String safePid() {
        try {
            return String.valueOf(proc.pid());
        } catch (Throwable t) {
            return "?";
        }
    }

    private int waitFor() {
        try {
            return proc.waitFor();
        } catch (Throwable t) {
            return -1;
        }
    }

    private void killTree() {
        Process p = proc;
        proc = null;
        if (p == null) return;
        try {
            p.destroy();
        } catch (Throwable ignored) {
        }
        try {
            Thread.sleep(300);
            if (p.isAlive()) p.destroyForcibly();
        } catch (Throwable ignored) {
        }
    }

    private void pump(final InputStream in, final File log) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                BufferedReader r = null;
                FileWriter w = null;
                try {
                    r = new BufferedReader(new InputStreamReader(in));
                    w = new FileWriter(log, true);
                    String line;
                    while ((line = r.readLine()) != null) {
                        w.write(line);
                        w.write("\n");
                        w.flush();
                        // mirror into logcat: the child's own log file lives in
                        // app-private storage and is unreadable over adb, which
                        // made the "Cannot connect to API" cause invisible.
                        Log.d(TAG, "srv| " + line);
                    }
                } catch (Throwable ignored) {
                } finally {
                    try { if (r != null) r.close(); } catch (IOException ignored) { }
                    try { if (w != null) w.close(); } catch (IOException ignored) { }
                }
            }
        }, "oc-log");
        t.setDaemon(true);
        t.start();
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
