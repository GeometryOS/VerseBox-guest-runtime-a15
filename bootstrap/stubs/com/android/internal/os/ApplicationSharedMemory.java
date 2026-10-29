package com.android.internal.os;

/**
 * COMPILE-TIME STUB ONLY. This file is compiled into
 * `artifacts/a15-stubs/` and put on javac's -classpath; it is deliberately NOT
 * fed to d8, so nothing here ever lands in framework-check.dex.
 *
 * Purpose: let `io.a10vm.guest.A15Statements` emit *real* `invoke-static`
 * bytecode for `ApplicationSharedMemory.create()/setInstance()`, exactly the
 * call shape `SystemServer.run()` uses at services.jar classes.dex 0x0181/0x0185.
 * At runtime those references resolve to the REAL class in framework.jar.
 *
 * Keeping the bodies empty is safe: A15Statements never executes a stub, it only
 * links against its signature. Do not add behaviour here.
 */
public class ApplicationSharedMemory {
    public static ApplicationSharedMemory create() { return null; }

    public static ApplicationSharedMemory getInstance() { return null; }

    public static void setInstance(ApplicationSharedMemory instance) {}
}
