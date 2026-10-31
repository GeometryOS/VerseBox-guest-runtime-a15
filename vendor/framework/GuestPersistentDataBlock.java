package io.a10vm.guest;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/**
 * The {@code persistent_data_block} endpoint Android 15 requires to finish an
 * install-commit.
 *
 * <p>{@code PackageInstallerSession.markAsSealed()} (services.jar, line 2441)
 * calls {@code Context.getSystemService(PersistentDataBlockManager.class)}, and
 * {@code SystemServiceRegistry}'s fetcher resolves that through
 * {@code ServiceManager.getServiceOrThrow("persistent_data_block")}. SystemServer
 * only starts {@code PersistentDataBlockService} when the device declares an FRP
 * partition ({@code ro.frp.pst}); a redroid image has none, so the lookups throw:
 *
 * <pre>
 *   GUEST_LOG 6 SystemServiceRegistry: No service published for: persistent_data_block
 *   android.os.ServiceManager$ServiceNotFoundException: No service published for: persistent_data_block
 *     at android.os.ServiceManager.getServiceOrThrow(ServiceManager.java:203)
 *     at android.app.SystemServiceRegistry$82.createService(SystemServiceRegistry.java:1111)
 *     at com.android.server.pm.PackageInstallerSession.markAsSealed(PackageInstallerSession.java:2441)
 *     at com.android.server.pm.PackageInstallerSession.commit(PackageInstallerSession.java:2190)
 *     at com.android.server.pm.PackageManagerShellCommand.doCommitSession(PackageManagerShellCommand.java:4305)
 *     at com.android.server.pm.PackageManagerShellCommand.runInstallCommit(PackageManagerShellCommand.java:1719)
 * </pre>
 *
 * <p>That exception is the whole "应用导入失败" chain: it aborts the shell command
 * inside system_server, libbinder answers the caller with a failed reply, Java
 * reports {@code Unknown binder error code 0xffffff91} / {@code DeadObjectException},
 * the host treats the import as failed, and the APK is left in {@code /data/app}
 * without a launcher entry. Real device 2026-10-31 17:29-17:50, instance
 * 1793438854447, importing 哔哩哔哩 (216 MB).
 *
 * <p>Publishing a neutral endpoint makes {@code createService()} succeed; a device
 * with no FRP partition answers exactly like this (no block, nothing to wipe).
 * Every answer is the "no persistent data block" value, and the class never
 * throws, because a throw here lands back in {@code markAsSealed}.
 */
final class GuestPersistentDataBlock implements InvocationHandler {
    static void register() throws Exception {
        LocalGuestService.register("persistent_data_block",
                "android.service.persistentdata.IPersistentDataBlockService",
                new GuestPersistentDataBlock());
    }

    @Override public Object invoke(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            // No FRP partition: nothing stored, nothing enabled, nothing to wipe.
            case "isEnabled": return false;
            case "getOemUnlockEnabled": return false;
            case "isFactoryResetProtectionActive": return false;
            case "getDataBlockSize": return 0;
            case "getMaximumDataBlockSize": return 0L;
            case "getFlashLockState": return 0;
            case "read": return new byte[0];
            case "write": return 0;            // int: 0 bytes written
            case "wipe": return null;
            case "setOemUnlockEnabled": return null;
            case "setOemUnlockAllowed": return null;
            default: return defaultValue(method.getReturnType());
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == byte[].class) return new byte[0];
        return null;
    }
}
