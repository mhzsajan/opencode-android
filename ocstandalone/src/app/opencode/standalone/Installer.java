package app.opencode.standalone;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public final class Installer {

    public static final String STAMP = "payload-v2";
    private static final String BIN = "opencode-bin";
    private static final String[] LIBS = {
            "lib/ld-linux-aarch64.so.1",
            "lib/libc.so.6",
            "lib/libm.so.6",
            "lib/libpthread.so.0",
            "lib/libdl.so.2"
    };

    public interface Progress {
        void onBytes(long copied);
    }

    private Installer() {
    }

    public static File libDir(Context c) {
        return new File(c.getFilesDir(), "lib");
    }

    public static File loader(Context c) {
        return new File(libDir(c), "ld-linux-aarch64.so.1");
    }

    public static File binary(Context c) {
        return new File(c.getFilesDir(), BIN);
    }

    public static File home(Context c) {
        File f = new File(c.getFilesDir(), "home");
        if (!f.exists()) f.mkdirs();
        return f;
    }

    public static boolean isInstalled(Context c) {
        File stamp = new File(c.getFilesDir(), ".payload-stamp");
        if (!stamp.exists()) return false;
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(stamp);
            byte[] buf = new byte[STAMP.length()];
            int n = in.read(buf);
            in.close();
            return n == STAMP.length() && new String(buf).equals(STAMP);
        } catch (Throwable t) {
            return false;
        }
    }

    public static synchronized void install(Context c, Progress p) throws Exception {
        if (isInstalled(c)) return;

        AssetManager am = c.getAssets();
        File lib = libDir(c);
        if (!lib.exists() && !lib.mkdirs()) throw new IllegalStateException("cannot create " + lib);

        // assets are deflated inside the apk, so AssetManager.openFd() throws
        // "probably compressed" -- stream them with open() instead.
        Counter counter = new Counter();
        for (String name : LIBS) {
            File out = new File(c.getFilesDir(), name);
            copy(am, name, out, counter, p);
            out.setReadable(true, true);
            out.setExecutable(true, true);
        }
        File bin = binary(c);
        copy(am, BIN, bin, counter, p);
        bin.setReadable(true, true);
        bin.setExecutable(true, true);
        if (p != null) p.onBytes(counter.n);

        FileOutputStream fo = new FileOutputStream(new File(c.getFilesDir(), ".payload-stamp"));
        fo.write(STAMP.getBytes());
        fo.close();
    }

    private static final class Counter {
        long n;
    }

    private static void copy(AssetManager am, String name, File out, Counter c, Progress p) throws Exception {
        InputStream in = am.open(name);
        try {
            FileOutputStream os = new FileOutputStream(out);
            try {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    c.n += n;
                    if (p != null) p.onBytes(c.n);
                }
                os.flush();
            } finally {
                os.close();
            }
        } finally {
            in.close();
        }
    }
}
