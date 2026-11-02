package io.a10vm.guest;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;

/**
 * Clears the write bit on an installed package's code before its process starts.
 *
 * <p>Android 14+ refuses to load a dex file the calling process may write:
 *
 * <pre>
 *   java.lang.SecurityException: Writable dex file
 *     '/data/app/~~zovRBuKivdCgkiH2h1N7sg==/com.huawei.appmarket-…/base.apk'
 *     is not allowed.
 *   at dalvik.system.DexFile.openDexFileNative(Native Method)
 *   … at android.app.LoadedApk.getClassLoader(LoadedApk.java:1134)
 *   … at android.app.ActivityThread.handleBindApplication(ActivityThread.java:7575)
 * </pre>
 *
 * <p>On a device this never fires because {@code /data/app} is owned by root and an
 * app runs as its own uid, so the APK is read-only <em>for the app</em>. In this
 * container the instance files are owned by the host app uid and every guest
 * process runs as that same uid, so an APK written by the guest's own
 * {@code installd} (mode 0644, owner-writable) is writable by the very process
 * that loads it. Installs that go through the host
 * ({@code FrameworkSession.makeDataApksReadOnly()}) already get fixed; installs
 * that go through the <em>guest's</em> PackageInstaller — the normal way to install
 * an app inside Android 15 — did not, which is why a freshly installed app crashed
 * on its first launch and only worked after the next virtual-machine start (that
 * start runs the host's chmod pass again).
 *
 * <p>The uid cannot be changed (a container is not root), so the equivalent
 * property is reproduced by removing the write bits: 0644 → 0444 for the APK,
 * 0755 → 0555 for native libraries, 0600 → 0400 for oat/vdex. Anything that still
 * needs to replace these files does so by rename inside the (writable) directory,
 * exactly as on a device. Real-device evidence for the mechanism: on 2026-10-31 a
 * manual {@code chmod -R a-w} of {@code rootfs/data/app} made bilibili and
 * deviceinfo start instead of crashing with this exception
 * (docs/android15-port/日用修复/04-进展日志.md, 第 91/92 轮).
 */
final class AppCodeReadOnly {
    /**
     * Strips the write bits of every regular file that belongs to {@code processName}'s
     * package under {@code <state>/rootfs/data/app}.
     *
     * @return how many files changed (0 = already read-only or no such package dir)
     */
    static int apply(File state, String processName) {
        if (state == null || processName == null || processName.isEmpty()) return 0;
        String base = processName.split(":", 2)[0];
        if (base.isEmpty() || base.equals("guest-application")) return 0;
        File[] slices = new File(state, "rootfs/data/app").listFiles();
        if (slices == null) return 0;
        int cleared = 0;
        for (File slice : slices) {
            File[] packages = slice.listFiles();
            if (packages == null) continue;
            for (File pack : packages) {
                // Installations are stored as <package>-<random>==; a process name may
                // carry a suffix (com.foo:push), and only the base package owns code.
                if (!pack.getName().startsWith(base + "-")) continue;
                cleared += walk(pack);
            }
        }
        return cleared;
    }

    private static int walk(File entry) {
        if (entry.isDirectory()) {
            File[] children = entry.listFiles();
            if (children == null) return 0;
            int cleared = 0;
            for (File child : children) cleared += walk(child);
            return cleared;
        }
        return clear(entry);
    }

    private static int clear(File file) {
        try {
            String path = file.getPath();
            StructStat stat = Os.lstat(path);
            if ((stat.st_mode & OsConstants.S_IFMT) != OsConstants.S_IFREG) return 0;
            int mode = stat.st_mode & 07777;
            if ((mode & 0222) == 0) return 0;
            Os.chmod(path, mode & ~0222);
            return 1;
        } catch (ErrnoException ignored) {
            return 0;
        }
    }
}
