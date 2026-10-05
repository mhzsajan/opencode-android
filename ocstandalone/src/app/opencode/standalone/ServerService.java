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

    private static volatile boolean running = false;
    private volatile Process proc;
    private Thread watcher;
    private volatile boolean stopping = false;

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
     * Bun's Happy Eyeballs needs both address families to be worth having. The
     * DNS fixes live here because the app has no /etc/resolv.conf: Android keeps
     * its resolver private, so the bundled glibc binary has nothing to read and
     * falls back to a loopback nameserver that nothing listens on. Without these
     * two files, every upstream fetch fails with
     * "Cannot connect to API: Was there a typo in the url or port?".
     *
     * They are optional: if staging them fails the server still starts, just
     * without the fix.
     */
    private static final String[][] NET_ENV = {
            {"DNS_RESULT_ORDER", "ipv6first"},
            {"DNS_AUTO_FALLBACK", "1"},
    };

    private void stageNetFiles(File home) {
        File etc = new File(home, "etc");
        if (!etc.exists()) etc.mkdirs();

        File rc = new File(etc, "resolv.conf");
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(rc);
            out.write("nameserver 8.8.8.8\nnameserver 1.1.1.1\n".getBytes("UTF-8"));
            out.close();
            Log.i(TAG, "wrote " + rc);
        } catch (Throwable t) {
            Log.w(TAG, "could not write resolv.conf", t);
            try { if (out != null) out.close(); } catch (IOException ignored) { }
        }
    }

    private boolean launch() {
        File lib = Installer.libDir(this);
        File ld = Installer.loader(this);
        File bin = Installer.binary(this);
        if (!ld.exists() || !bin.exists()) {
            Log.e(TAG, "payload missing, run Installer first");
            return false;
        }
        File home = Installer.home(this);
        File project = Projects.dir(this);
        if (!project.exists()) project.mkdirs();
        stageNetFiles(home);
        File tmp = getCacheDir();
        File log = new File(home, "server.log");

        ProcessBuilder pb = new ProcessBuilder(
                ld.getAbsolutePath(),
                bin.getAbsolutePath(),
                "serve",
                "--port", String.valueOf(PORT),
                "--hostname", "127.0.0.1",
                "--pure",
                "--print-logs",
                "--log-level", "INFO");
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
        for (String[] kv : NET_ENV) env.put(kv[0], kv[1]);
        env.put("RESOLV_CONF", new File(home, "etc/resolv.conf").getAbsolutePath());
        env.put("RES_OPTIONS", "timeout:2 attempts:2");

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
