// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.exittrace;

import java.lang.instrument.Instrumentation;
import java.util.Map;

/**
 * JVM が静かに終わるときに、全スレッドのスタックを出す。
 *
 * <pre>-javaagent:tools/build/exittrace.jar</pre>
 *
 * System.exit で止まると shutdown hook が動くので、そこで Thread.getAllStackTraces を出す。
 * 呼び出した側(main スレッド)が Shutdown.exit の中にいるので、誰が終わらせたか分かる。
 */
public final class ExitTrace {

    public static void premain(String args, Instrumentation instrumentation) {
        Runtime.getRuntime().addShutdownHook(new Thread(ExitTrace::dump, "shifu-exit-trace"));
        System.err.println("[exittrace] armed");
    }

    private static void dump() {
        System.err.println("[exittrace] JVM が終わろうとしている");

        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            Thread thread = entry.getKey();

            if (thread.getName().equals("shifu-exit-trace")) {
                continue;
            }

            StackTraceElement[] stack = entry.getValue();

            if (stack.length == 0) {
                continue;
            }

            boolean exiting = false;

            for (StackTraceElement frame : stack) {
                if (frame.getClassName().equals("java.lang.Shutdown") || frame.getMethodName().equals("exit")
                        || frame.getMethodName().equals("halt")) {
                    exiting = true;
                }
            }

            if (!exiting && !thread.getName().equals("main")) {
                continue;
            }

            System.err.println("[exittrace] スレッド " + thread.getName());

            for (StackTraceElement frame : stack) {
                System.err.println("[exittrace]     at " + frame);
            }
        }

        System.err.flush();
    }
}
