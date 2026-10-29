package com.android.internal.os;

/**
 * COMPILE-TIME STUB ONLY — see ApplicationSharedMemory.java in this directory.
 *
 * `SystemServer.run()` calls
 *   RuntimeInit.setDefaultApplicationWtfHandler(SystemServer::handleEarlySystemWtf)
 * at services.jar classes.dex 0x017e. The real method body is a single field
 * store (framework.jar bytecode: `sput-object` + `return-void`), so calling it
 * from A15Statements cannot disturb the process state that SystemServer then
 * overwrites anyway.
 */
public final class RuntimeInit {
    private RuntimeInit() {}

    /** Real name: com.android.internal.os.RuntimeInit$ApplicationWtfHandler. */
    public interface ApplicationWtfHandler {
        void handleWtf(String tag, Throwable throwable, boolean isSystem);
    }

    public static void setDefaultApplicationWtfHandler(ApplicationWtfHandler handler) {}
}
