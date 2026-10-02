package app.opencode.standalone;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public final class Installer {

    public static final String STAMP = "payload-v1";
    private static final String BIN = "opencode-bin";
    private static final String[] LIBS = {
            "lib/ld-linux-aarch64.so.1",
            "lib/libc.so.6",
            "lib/libm.so.6",
            "lib/libpthread.so.0",
            "lib/libdl.so.2"
    };

    public interface Progress {
        void onProgress(int percent, String label);
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

        long total = 0;
        for (String l : LIBS) total += am.openFd(l).getLength();
        total += am.openFd(BIN).getLength();

        long done = 0;
        for (String name : LIBS) {
            File out = new File(c.getFilesDir(), name);
            done += copy(am, name, out);
            out.setReadable(true, true);
            out.setExecutable(true, true);
            if (p != null) p.onProgress((int) (done * 100 / total), name);
        }
        File bin = binary(c);
        done += copy(am, BIN, bin);
        bin.setReadable(true, true);
        bin.setExecutable(true, true);
        if (p != null) p.onProgress(100, "ready");

        FileOutputStream fo = new FileOutputStream(new File(c.getFilesDir(), ".payload-stamp"));
        fo.write(STAMP.getBytes());
        fo.close();
    }

    private static long copy(AssetManager am, String name, File out) throws Exception {
        InputStream in = am.open(name);
        OutputStream os = new FileOutputStream(out);
        byte[] buf = new byte[1 << 16];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            os.write(buf, 0, n);
            total += n;
        }
        os.flush();
        os.close();
        in.close();
        return total;
    }
}
