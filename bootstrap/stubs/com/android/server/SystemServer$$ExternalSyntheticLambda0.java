package com.android.server;

import com.android.internal.os.RuntimeInit;

/**
 * COMPILE-TIME STUB ONLY — see com/android/internal/os/ApplicationSharedMemory.java.
 *
 * The real `com.android.server.SystemServer$$ExternalSyntheticLambda0` is a
 * D8-generated class inside services.jar; on a real device its constructor is
 * public (proved on device: `getDeclaredConstructor().newInstance()` succeeded
 * without setAccessible). This stub exists only so javac can emit the same
 * `new-instance` + `invoke-direct <init>()V` pair that SystemServer.run()
 * executes at 0x0179/0x017b. The class is never written to framework-check.dex.
 */
public final class SystemServer$$ExternalSyntheticLambda0 implements RuntimeInit.ApplicationWtfHandler {
    public SystemServer$$ExternalSyntheticLambda0() {}

    @Override
    public void handleWtf(String tag, Throwable throwable, boolean isSystem) {}
}
