package app.wireroute;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/** Keeps the pre-upgrade selection and encrypted profile inside the app's private storage. */
final class UpgradeSnapshot {
    static void preserve(Context context) {
        File destination = new File(context.getNoBackupFilesDir(), "before-3.1");
        File completed = new File(destination, "complete");
        if (completed.exists()) return;
        try {
            if (!destination.isDirectory() && !destination.mkdirs()) return;
            File prefs = new File(context.getFilesDir().getParentFile(), "shared_prefs");
            for (String name : new String[]{"routing.xml", "profile_metadata.xml",
                    "wireroute_site_routes_v1.xml", "site_library_v1.xml"}) {
                copyIfPresent(new File(prefs, name), new File(destination, name));
            }
            for (String name : new String[]{"profile.wrc", "profile.wrc.bak", "profile-library.wrc"}) {
                copyIfPresent(new File(context.getNoBackupFilesDir(), name), new File(destination, name));
            }
            try (FileOutputStream output = new FileOutputStream(completed)) { output.write(1); output.getFD().sync(); }
        } catch (IOException ignored) { /* Never interrupt a working VPN if a backup cannot be written. */ }
    }

    private static void copyIfPresent(File source, File target) throws IOException {
        if (!source.isFile()) return;
        try (FileInputStream input = new FileInputStream(source); FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192]; int size;
            while ((size = input.read(buffer)) != -1) output.write(buffer, 0, size);
            output.getFD().sync();
        }
    }
}
