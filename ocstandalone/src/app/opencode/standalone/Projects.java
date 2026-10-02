package app.opencode.standalone;

import android.content.Context;
import android.os.Environment;

import java.io.File;

public final class Projects {

    private static final String NAME = "OpenCode";

    private Projects() {
    }

    public static boolean hasFullAccess() {
        return Environment.isExternalStorageManager();
    }

    public static File dir(Context c) {
        if (hasFullAccess()) {
            File shared = new File(Environment.getExternalStorageDirectory(), NAME);
            if (shared.exists() || shared.mkdirs()) return shared;
        }
        File[] ext = c.getExternalFilesDirs(null);
        File base = (ext != null && ext.length > 0 && ext[0] != null) ? ext[0] : c.getFilesDir();
        File f = new File(base, NAME);
        if (!f.exists()) f.mkdirs();
        return f;
    }
}
