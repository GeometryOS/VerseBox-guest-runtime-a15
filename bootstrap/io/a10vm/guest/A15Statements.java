package io.a10vm.guest;

import com.android.internal.os.ApplicationSharedMemory;
import com.android.internal.os.RuntimeInit;
import com.android.server.SystemServer$$ExternalSyntheticLambda0;

/**
 * Direct-call-site reproducer for the four statements `SystemServer.run()`
 * executes between `0x0176 traceEnd` and `0x0188 StartServices`
 * (services.jar!classes.dex, see docs/android15-port/12 §3.1):
 *
 * <pre>
 *   0x0179  new-instance SystemServer$$ExternalSyntheticLambda0
 *   0x017b  invoke-direct  &lt;init&gt;()V
 *   0x017e  invoke-static  RuntimeInit.setDefaultApplicationWtfHandler(lambda)
 *   0x0181  invoke-static  ApplicationSharedMemory.create()
 *   0x0185  invoke-static  ApplicationSharedMemory.setInstance(created)
 * </pre>
 *
 * Why this class exists: every earlier probe reached those classes through
 * `java.lang.reflect` (Class.forName / Method.invoke). Reflection is a different
 * call shape from the `new-instance` / `invoke-static` sites inside run(), and
 * all eleven reflective probes plus the real-lambda probe came back `ok` while
 * run() still dies. The types referenced below come from compile-time stubs in
 * `bootstrap/stubs/` (never added to framework-check.dex), so javac emits genuine
 * direct call sites while ART resolves the REAL classes from framework.jar at
 * runtime.
 *
 * Ordering rule: `installSingleton` must stay false before SystemServer.run().
 * `ApplicationSharedMemory.setInstance` is one-shot per process
 * (`if (sInstance != null) throw IllegalStateException`), so running the last
 * step early would turn the statement under test into a guaranteed failure.
 * A15Boot calls run(false) before SystemServer.main and run(true) only in the
 * post-mortem phase.
 */
public final class A15Statements {
    private A15Statements() {}

    public static void run(boolean installSingleton) {
        step("lambda_new", () -> {
            Object lambda = new SystemServer$$ExternalSyntheticLambda0();
            System.out.println("a15_stmt_lambda_new_class=" + lambda.getClass().getName());
        });
        step("wtf_handler", () -> RuntimeInit.setDefaultApplicationWtfHandler(
                new SystemServer$$ExternalSyntheticLambda0()));
        step("create", () -> System.out.println("a15_stmt_create="
                + ApplicationSharedMemory.create()));
        if (installSingleton) {
            step("set_instance", () -> ApplicationSharedMemory.setInstance(
                    ApplicationSharedMemory.create()));
        } else {
            System.out.println("a15_stmt_set_instance=skipped(pre-run)");
        }
        System.out.flush();
    }

    private interface Step {
        void run() throws Throwable;
    }

    private static void step(String name, Step step) {
        try {
            step.run();
            System.out.println("a15_stmt_" + name + "=ok");
        } catch (Throwable failure) {
            System.out.println("a15_stmt_" + name + "=threw:" + failure);
            failure.printStackTrace(System.out);
        }
        System.out.flush();
    }
}
