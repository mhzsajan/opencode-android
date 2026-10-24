package app.opencode.standalone;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public final class Installer {

    public static final String STAMP = "payload-v6";
    private static final String BIN = "opencode-bin";
    private static final String LOADER_PREFIX = "ld-";
    /** Optional launcher-tracer shipped only with the x86_64 payload. */
    private static final String SHIM = "shim";

    public interface Progress {
        void onBytes(long copied);
    }

    private Installer() {
    }

    public static File libDir(Context c) {
        return new File(c.getFilesDir(), "lib");
    }

    /**
     * The APK carries exactly one runtime payload - either the aarch64 build
     * (phones) or the x86_64 build (emulators) - and the two need different
     * loaders. Rather than guess from Build.SUPPORTED_ABIS (which on an x86_64
     * image lists both `x86_64` and the translated `arm64-v8a`), ask the assets
     * which payload is actually packaged.
     */
    public static String loaderName(Context c) {
        try {
            String[] names = c.getAssets().list("lib");
            if (names != null) {
                java.util.Arrays.sort(names);
                for (String n : names) {
                    if (n.startsWith(LOADER_PREFIX)) return n;
                }
            }
        } catch (Throwable ignored) {
            // fall through to what is already extracted
        }
        for (String n : new String[]{"ld-linux-aarch64.so.1", "ld-linux-x86-64.so.2"}) {
            if (new File(libDir(c), n).exists()) return n;
        }
        return "ld-linux-aarch64.so.1";
    }

    /** True when the packaged payload is the native x86_64 build. */
    public static boolean isX64(Context c) {
        // Ask the binary itself rather than the loader's filename: x86_64 is
        // spelled "x86-64" in ld-linux and "x86_64" in ld-musl, and the
        // payload may be either glibc or musl. ELF e_machine is authoritative.
        try {
            InputStream in = c.getAssets().open(BIN);
            try {
                byte[] h = new byte[20];
                int n = 0;
                while (n < 20) {
                    int r = in.read(h, n, 20 - n);
                    if (r <= 0) break;
                    n += r;
                }
                if (n >= 20) {
                    int machine = (h[18] & 0xff) | ((h[19] & 0xff) << 8);
                    return machine == 0x3e;   // 0xb7 is aarch64
                }
            } finally {
                in.close();
            }
        } catch (Throwable ignored) {
            // fall through to the loader name
        }
        return loaderName(c).indexOf("x86") >= 0;
    }

    public static File loader(Context c) {
        return new File(libDir(c), loaderName(c));
    }

    public static File binary(Context c) {
        return new File(c.getFilesDir(), BIN);
    }

    /**
     * The seccomp shim, or null when this payload does not need one.
     *
     * Only the x86_64 payload ships it: that build is musl+Zig+Bun and issues
     * syscalls bionic never does (lstat, mkdir, chmod, epoll_create, ...), which
     * the app's bionic-derived seccomp filter TRAPs. The aarch64 payload was
     * built against Termux's libc and stays on the direct-loader path.
     *
     * Presence of the asset, not the architecture, decides: that keeps a
     * shim-less arm64 apk and a shim-carrying x64 apk on one code path.
     */
    public static File shim(Context c) {
        File f = new File(c.getFilesDir(), SHIM);
        return f.exists() ? f : null;
    }

    public static File home(Context c) {
        File f = new File(c.getFilesDir(), "home");
        if (!f.exists()) f.mkdirs();
        return f;
    }

    public static boolean isInstalled(Context c) {
        File stamp = new File(c.getFilesDir(), ".payload-stamp");
        if (!stamp.exists()) return false;
        // The stamp carries the payload architecture as well: installing the
        // x86_64 apk over an aarch64 install must replace the payload, and a
        // bare "payload-vN" string would look like it already matched.
        String want = STAMP + "/" + loaderName(c);
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(stamp);
            byte[] buf = new byte[want.length()];
            int n = in.read(buf);
            in.close();
            return n == want.length() && new String(buf).equals(want);
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
        // Copy whatever the payload ships: the aarch64 build has 5 glibc libs,
        // the x86_64 build 3 (musl's loader doubles as libc, plus libstdc++
        // and libgcc_s for Bun's C++ runtime).
        String[] libs = am.list("lib");
        if (libs == null || libs.length == 0) {
            throw new IllegalStateException("apk carries no runtime payload");
        }
        for (String name : libs) {
            File out = new File(lib, name);
            copy(am, "lib/" + name, out, counter, p);
            out.setReadable(true, true);
            out.setExecutable(true, true);
        }
        File bin = binary(c);
        copy(am, BIN, bin, counter, p);
        bin.setReadable(true, true);
        bin.setExecutable(true, true);

        // Optional tracer. Deleted when the packaged payload has none so a
        // leftover copy from a previously installed variant can never wrap the
        // aarch64 loader.
        File shim = new File(c.getFilesDir(), SHIM);
        if (hasAsset(am, SHIM)) {
            copy(am, SHIM, shim, counter, p);
            shim.setReadable(true, true);
            shim.setExecutable(true, true);
        } else if (shim.exists()) {
            shim.delete();
        }

        if (p != null) p.onBytes(counter.n);

        FileOutputStream fo = new FileOutputStream(new File(c.getFilesDir(), ".payload-stamp"));
        fo.write((STAMP + "/" + loaderName(c)).getBytes());
        fo.close();
    }

    private static final class Counter {
        long n;
    }

    /** True when the apk carries assets/<name> (AssetManager gives no stat). */
    private static boolean hasAsset(AssetManager am, String name) {
        try {
            InputStream in = am.open(name);
            in.close();
            return true;
        } catch (Throwable t) {
            return false;
        }
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
