package io.a10vm.guest;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;

/**
 * Minimal gatekeeper endpoint for Android 15 (API 35).
 *
 * Why it is needed
 * ----------------
 * startOtherServices() reaches StartBiometricService (SystemServer.java:2680) and
 * BiometricService's constructor calls Injector.getGateKeeperService(), which is
 * android.security.GateKeeper.getService():
 *
 *     IGateKeeperService.Stub.asInterface(
 *             ServiceManager.getService(Context.GATEKEEPER_SERVICE))
 *
 * and throws IllegalStateException("Gatekeeper service not available") when the
 * binder is missing. Real-device evidence (instance 1793226895323, 2026-10-29
 * 06:37):
 *
 *     java.lang.RuntimeException: Failed to create service
 *       com.android.server.biometrics.BiometricService
 *     Caused by: java.lang.IllegalStateException: Gatekeeper service not available
 *       at android.security.GateKeeper.getService(GateKeeper.java:41)
 *       at com.android.server.biometrics.BiometricService$Injector
 *             .getGateKeeperService(BiometricService.java:1182)
 *       at com.android.server.biometrics.BiometricService.<init>(BiometricService.java:1303)
 *
 * The image ships system/bin/gatekeeperd and a HIDL 1.0 software HAL, but this
 * runtime has no init: nothing starts either of them, and gatekeeperd would also
 * need its own /data/misc/gatekeeper directory. That is the same class of gap as
 * artd / sensorservice / audioserver; for gatekeeper the project already has a
 * proven bootstrap endpoint (A9Boot -> A9GateKeeper). This is the API 35 version.
 *
 * Interface and transaction numbers are taken from the image's own AOSP tag,
 * system/core/gatekeeperd/binder/android/service/gatekeeper/
 * IGateKeeperService.aidl (android-15.0.0_r36):
 *
 *     1 enroll(int, byte[], byte[], byte[])
 *     2 verify(int, byte[], byte[])
 *     3 verifyChallenge(int, long, byte[], byte[])
 *     4 getSecureUserId(int) -> long        <-- moved from 5 in Android 9
 *     5 clearSecureUserId(int)
 *     6 reportDeviceSetupComplete()
 *
 * getSecureUserId returns 0 = GateKeeper.INVALID_SECURE_USER_ID, i.e. "this
 * container has no secure user". BiometricService only calls it on
 * getLastAuthenticationTime(); enroll/verify/verifyChallenge are never reached
 * because the guest has no lock screen. The endpoint still answers them with a
 * null GateKeeperResponse instead of failing the parcel.
 */
public final class A15GateKeeper {
    private static final String NAME = "android.service.gatekeeper.IGateKeeperService";

    private static final int TRANSACTION_ENROLL = 1;
    private static final int TRANSACTION_VERIFY = 2;
    private static final int TRANSACTION_VERIFY_CHALLENGE = 3;
    private static final int TRANSACTION_GET_SECURE_USER_ID = 4;
    private static final int TRANSACTION_CLEAR_SECURE_USER_ID = 5;
    private static final int TRANSACTION_REPORT_DEVICE_SETUP_COMPLETE = 6;

    private A15GateKeeper() {}

    public static void register() throws Exception {
        IBinder binder = new Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                if (reply == null) return true;
                reply.writeNoException();
                switch (code) {
                    case TRANSACTION_GET_SECURE_USER_ID:
                        // long getSecureUserId(int userId)
                        reply.writeLong(0L);
                        break;
                    case TRANSACTION_ENROLL:
                    case TRANSACTION_VERIFY:
                    case TRANSACTION_VERIFY_CHALLENGE:
                        // GateKeeperResponse is a typed parcelable; write "null" so
                        // the client's readTypedObject() returns null instead of
                        // failing on a half-written reply.
                        reply.writeInt(0);
                        break;
                    case TRANSACTION_CLEAR_SECURE_USER_ID:
                    case TRANSACTION_REPORT_DEVICE_SETUP_COMPLETE:
                        // void
                        break;
                    default:
                        // Unknown transaction: never leave the parcel without the
                        // exception status written above.
                        break;
                }
                return true;
            }
        };
        Class<?> manager = Class.forName("android.os.ServiceManager");
        manager.getMethod("addService", String.class, IBinder.class)
                .invoke(null, NAME, binder);
    }
}
