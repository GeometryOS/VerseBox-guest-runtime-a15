package io.a10vm.guest;

import android.os.PersistableBundle;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

final class PrivateVolumes implements InvocationHandler {
    private Object listener;
    private final Set<Integer> unlocked = new HashSet<>();
    private final Set<String> unhandled = new HashSet<>();
    private boolean storageSizeReported;
    /** One line per candidate path, so the log names which filesystem answered. */
    private static final java.util.Set<String> candidateReported = new java.util.HashSet<>();

    static void register() throws Exception {
        PrivateVolumes volumes = new PrivateVolumes();
        int systemUser = Class.forName("android.os.UserHandle").getField("USER_SYSTEM").getInt(null);
        volumes.prepare(systemUser);
        LocalGuestService.register("vold", "android.os.IVold", volumes);
        Set<Integer> active = new HashSet<>();
        LocalGuestService.register("storaged", "android.os.IStoraged", (proxy, method, args) -> {
            if ("onUserStarted".equals(method.getName())) { active.add((Integer) args[0]); return null; }
            if ("onUserStopped".equals(method.getName())) { active.remove((Integer) args[0]); return null; }
            if ("getRecentPerf".equals(method.getName())) return -1;
            throw new UnsupportedOperationException("Private storage statistics: " + method.getName());
        });
    }

    private void prepare(int user) throws Exception {
        if (user != 0) throw new UnsupportedOperationException("Guest user " + user);
        for (String directory : new String[] {"/data/user/0", "/data/user_de/0", "/data/system_ce/0",
                "/data/system_de/0", "/data/misc_ce/0", "/data/misc_de/0", "/data/media/0", "/storage/emulated/0",
                "/storage/emulated/0/Android/data", "/storage/emulated/0/Android/obb", "/storage/emulated/0/Android/media"})
            Files.createDirectories(Paths.get(directory));
        alias("/storage/self/primary", "../emulated/0");
        alias("/mnt/user/0/primary", "../../../storage/emulated/0");
        for (String mode : new String[] {"default", "read", "write", "full"})
            alias("/mnt/runtime/" + mode + "/emulated", "../../../storage/emulated");
    }

    /** Reports the emulated volume the way vold does after a reset. */
    private void announce() throws Exception {
        notify("onVolumeCreated", "emulated", 2, null, null);
        notify("onVolumeInternalPathChanged", "emulated", "/data/media");
        notify("onVolumePathChanged", "emulated", "/storage/emulated");
    }

    private static void alias(String name, String target) throws Exception {
        Path path = Paths.get(name);
        if (Files.isSymbolicLink(path) && Files.readSymbolicLink(path).toString().equals(target)) return;
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalStateException("Guest storage alias already occupied: " + name);
        Files.createDirectories(path.getParent());
        Files.createSymbolicLink(path, Paths.get(target));
    }

    /**
     * Calls one IVoldListener method on the framework's listener.
     *
     * The listener is an AIDL interface whose parameter list is *not* stable
     * across API levels and LocalGuestService.call() matches by name + arity, so
     * the arguments are adapted to the image's own signature instead of being
     * hard-coded for one version. Android 15 added a userId to two of them:
     *   A9/A10: onVolumeCreated(String,int,String,String) / onVolumeStateChanged(String,int)
     *   A15   : onVolumeCreated(String,int,String,String,int)
     *           onVolumeStateChanged(String,int,int)
     * Passing the A10 shape to A15 reset() throws
     *   java.lang.NoSuchMethodException: android.os.IVoldListener.onVolumeCreated
     * (instance 1793357860577, 19:00), which aborts resetIfBootedAndConnected()
     * before the emulated volume exists, and that is why
     * "StorageManagerService: No primary storage defined yet; hacking together a
     * stub" is still in the log after MediaProvider started working.
     */
    private void notify(String method, Object... args) throws Exception {
        if (listener == null) throw new IllegalStateException("Missing guest volume listener");
        java.lang.reflect.Method target = null;
        for (java.lang.reflect.Method candidate : Class.forName("android.os.IVoldListener").getMethods())
            if (candidate.getName().equals(method)) { target = candidate; break; }
        if (target == null) throw new NoSuchMethodException("android.os.IVoldListener." + method);
        Class<?>[] types = target.getParameterTypes();
        Object[] adapted = new Object[types.length];
        for (int i = 0; i < types.length; ++i)
            adapted[i] = i < args.length ? args[i] : blank(types[i]);
        LocalGuestService.call(listener, "android.os.IVoldListener", method, adapted);
    }

    private static Object blank(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }

    @Override public synchronized Object invoke(Object proxy, Method method, Object[] args) throws Exception {
        String name = method.getName();
        switch (name) {
            case "setListener": listener = args[0]; return null;
            case "needsCheckpoint": case "needsRollback": case "supportsCheckpoint":
            case "supportsFileCheckpoint": case "supportsBlockCheckpoint": return false;
            case "commitChanges": case "monitor": return null;
            case "remountUid": return null;
            case "mkdirs":
                Path path = Paths.get((String) args[0]).normalize();
                if (!path.startsWith("/storage/emulated/0") && !path.startsWith("/storage/self/primary") &&
                        !path.startsWith("/mnt/runtime") && !path.startsWith("/mnt/user/0/primary"))
                    throw new IllegalArgumentException("Unknown guest external storage path: " + path);
                Files.createDirectories(path);
                return null;
            // Android 15's StorageManagerService asks vold to create the per-app
            // external directory it is about to hand to the app (Gallery's cache,
            // DocumentsUI's files, ...).  Real arguments, captured on device
            // (instance 1793525278509, r126 log line 61216):
            //
            //   a15_vold_app_dir=/storage/emulated/10051/Android/data/
            //       storage/emulated/0/Android/data/com.android.gallery3d/cache
            //
            // That is this endpoint's old generic branch treating args[0] as a
            // package name and args[1]=10051 as a user id.  The real signature is
            // setupAppDir(String path, int appId): args[0] is already the complete
            // directory to create (here the *cache* dir of the app) and args[1] is
            // the appId, not a user.  r125 built the wrong tree and the target
            // stayed absent, so Gallery failed with
            //   java.io.FileNotFoundException:
            //     /storage/emulated/0/Android/data/com.android.gallery3d/cache/
            //       imgcache.idx: open failed: ENOENT
            // (application-28715-25.log:158).
            //
            // Creating exactly the requested path is what vold does for the leaf;
            // the parent chain already exists from prepare().
            case "setupAppDir":
            case "fixupAppDir": {
                String requested = (String) args[0];
                Path appDir = Paths.get(requested.startsWith("/") ? requested : "/" + requested).normalize();
                if (!appDir.startsWith("/storage/emulated/0") && !appDir.startsWith("/storage/self/primary") &&
                        !appDir.startsWith("/mnt/runtime") && !appDir.startsWith("/mnt/user/0/primary"))
                    throw new IllegalArgumentException("Unknown guest external storage path: " + appDir);
                Files.createDirectories(appDir);
                System.out.println("a15_vold_app_dir=" + appDir + " ok=true");
                return null;
            }
            case "reset":
                prepare(0);
                announce();
                return null;
            // A15: mount(String volId, int flags, int userId, IVoldMountCallback).
            // vold's job here is to hand StorageManagerService a /dev/fuse fd
            // through that callback so MediaProvider's ExternalStorageService can
            // start the FUSE session. This container has no /dev/fuse and no
            // mounting privileges, so the honest answer is the A9/A10 one: the
            // guest's external storage is a plain directory tree that
            // prepare() creates under /storage/emulated/0 (with the /mnt/runtime
            // and /mnt/user/0/primary aliases real vold would also publish), and
            // the volume is reported as mounted. Never invoking the callback is
            // deliberate: it tells StorageManagerService there is no FUSE session
            // to attach, instead of handing it an fd that cannot be mounted.
            case "mount": {
                String id = (String) args[0];
                if (!"emulated".equals(id)) return defaultValue(method.getReturnType());
                int user = args.length > 2 && args[2] instanceof Integer ? (Integer) args[2] : 0;
                prepare(user);
                notify("onVolumeStateChanged", id, 2, user);
                return null;
            }
            case "onUserAdded": prepare((Integer) args[0]); return null;
            // StorageManagerService.onUserUnlocking() connects MediaProvider's
            // ExternalStorageService (mStorageSessionController.onUnlockUser)
            // *and then* calls mVold.onUserStarted(). Re-announcing the emulated
            // volume here is what makes the volume survive the boot order this
            // container actually has: resetIfBootedAndConnected() also creates it
            // from vold.reset(), and whenever that happens before the user unlock
            // the earlier announcement is dropped with
            //   StorageManagerService: Ignoring volume emulated because user 0
            //     does not support external storage
            // (instance 1793358281843, 19:04; the same reset in instance
            // 1793357860577 ran *after* the unlock, so the order is a race, not a
            // constant). Announce again once the service is up instead of relying
            // on which callback wins.
            case "onUserStarted":
                prepare((Integer) args[0]);
                announce();
                return null;
            case "createUserKey":
                prepare((Integer) args[0]); return method.getReturnType() == boolean.class ? true : null;
            // Round 78: LockSettingsService.initializeSyntheticPassword() calls
            // StorageManagerService.setCeStorageProtection() right after creating
            // the synthetic password. That forwards to IVold.setCeStorageProtection;
            // this runtime has no CE storage protection to apply, so the honest
            // container answer is a successful no-op:
            //   LockSettingsService.initializeSyntheticPassword(:3028)
            //   -> StorageManagerService.setCeStorageProtection(:3325)
            //   -> PrivateVolumes.invoke -> UnsupportedOperationException
            //      "Private directory volume: setCeStorageProtection"
            // (instance 1793270130107, 2026-10-29 18:35)
            case "setCeStorageProtection":
                return defaultValue(method.getReturnType());
            case "initUser0": prepare(0); return null;
            case "prepareUserStorage": prepare((Integer) args[1]); return null;
            case "unlockUserKey": prepare((Integer) args[0]); unlocked.add((Integer) args[0]); return null;
            // StorageManagerService.connectVold -> restoreCeUnlockedUsers() calls
            // this right after the listener is accepted; A10's IVold has it too and
            // the old shim threw "Private directory volume: getUnlockedUsers"
            // (Slog.e "Failed to get unlocked users from vold" on every boot).
            // Answer with the users this endpoint has actually unlocked so far.
            case "getUnlockedUsers": {
                int[] users = new int[unlocked.size()];
                int at = 0;
                for (Integer user : unlocked) users[at++] = user;
                return users;
            }
            case "lockUserKey": unlocked.remove((Integer) args[0]); return null;
            // S-track (round 90): StorageManagerService.getInternalStorageBlockDeviceSize()
            // caches mVold.getStorageSize() once (image services.dex, StorageManagerService
            // .getInternalStorageBlockDeviceSize @0x225da0-0x225dd4), and that cached value is
            // the total capacity Settings shows. The generic branch below answered 0 and
            // logged "a15_vold_unhandled=getStorageSize", so Settings displayed 0 B total and
            // then computed a *negative* "system" usage. Real vold reports the size of the
            // block device that backs /data; here the equivalent object is the guest's
            // emulated volume, so ask the guest filesystem itself.
            case "getStorageSize": {
                long bytes = totalBytes();
                if (!storageSizeReported) {
                    storageSizeReported = true;
                    System.out.println("a15_vold_storage_size=" + bytes);
                }
                return bytes;
            }
            case "onUserStopped": case "onSecureKeyguardStateChanged": case "shutdown": return null;
            case "fstrim": case "runIdleMaint": case "abortIdleMaint":
                Object task = args[args.length - 1];
                if (task != null) LocalGuestService.call(task, "android.os.IVoldTaskListener", "onFinished", -95,
                        new PersistableBundle());
                return null;
            case "mountAppFuse": throw new UnsupportedOperationException("Guest file proxy descriptors");
            default:
                // Android 15's StorageManagerService drives a much wider IVold
                // surface than API 28/29 did (openAppFuseFile, setupAppDir,
                // refreezeApp, getStorageLifeTime, ...). Throwing from here aborts
                // whichever storage path called it -- NoSuchMethodException on one
                // listener callback was already enough to lose the emulated volume
                // entirely -- so unimplemented calls answer the neutral container
                // value and are *named* in the log once, so the gap stays visible
                // instead of silently swallowing a call the framework relies on.
                if (unhandled.add(name))
                    System.out.println("a15_vold_unhandled=" + name);
                return defaultValue(method.getReturnType());
        }
    }

    /**
     * Total bytes of the guest's emulated volume filesystem, i.e. what vold's
     * getStorageSize() reports for the block device behind /data. The guest paths
     * resolve through the runtime's path bridge onto the host filesystem that holds
     * the instance, so statvfs is the honest answer for "how big is this device".
     * Tried in order and never throws: a 0 return keeps the old behaviour.
     */
    /** Reads the host's own total bytes from the shared network snapshot. */
    private static long hostTotalBytes() {
        String root = System.getenv("A10VM_STATE_ROOT");
        if (root == null || root.isEmpty()) return 0;
        try {
            Path path = Paths.get(root, "host-network.json");
            if (!Files.isRegularFile(path)) return 0;
            org.json.JSONObject snapshot = new org.json.JSONObject(
                    new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8));
            org.json.JSONObject storage = snapshot.optJSONObject("storage");
            return storage == null ? 0 : storage.optLong("totalBytes");
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static long totalBytes() {
        // The device's own /data filesystem is the honest answer, and the instance
        // state root is guaranteed to live on it: the guest paths below resolve
        // through the path bridge, so a missing emulated-volume mapping silently
        // answers for whatever filesystem "/" happens to be. A 256 GB phone reported
        // 492,196,392,960 B (rounded up by FileUtils to "512 GB") from the previous
        // measurement, so every candidate is named once in the log and the state
        // root is tried first.
        // 1) 宿主写在 host-network.json 里的真实值（唯一不会猜错的来源）。
        long host = hostTotalBytes();
        if (host > 0) {
            if (candidateReported.add("host")) System.out.println("a15_vold_storage_candidate=host:" + host);
            return host;
        }
        // 2) 兜底：客体路径。/data 与 /storage/emulated/0 都落在宿主同一个 data 分区上，
        //    所以它们比 A10VM_STATE_ROOT（宿主绝对路径，客体里未必解析得到）更可靠。
        java.util.List<String> paths = new java.util.ArrayList<>();
        paths.add("/data");
        paths.add("/storage/emulated/0");
        String root = System.getenv("A10VM_STATE_ROOT");
        if (root != null && !root.isEmpty()) paths.add(root);
        paths.add("/");
        long answer = 0;
        for (String path : paths) {
            long bytes = 0;
            try {
                bytes = new java.io.File(path).getTotalSpace();
            } catch (Throwable ignored) { }
            if (candidateReported.add(path))
                System.out.println("a15_vold_storage_candidate=" + path + ":" + bytes);
            if (answer == 0 && bytes > 0) answer = bytes;
        }
        return answer;
    }

    /** Android 9 exposes vold operations that Android 10 removed; no-op them without a service. */
    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }
}