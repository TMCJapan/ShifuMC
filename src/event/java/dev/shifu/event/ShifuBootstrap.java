// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.event;

import java.io.File;
import joptsimple.OptionSet;
import net.minecraft.server.MinecraftServer;

/**
 * Paper の設定を立ち上げる。
 *
 * <p>アダプタ層({@code CraftWorld} や {@code ChatProcessor})は
 * {@code PaperConfig} の static 欄と {@code Level.paperConfig} が入っている
 * 前提で書かれている。Paper は {@code DedicatedServer.initServer} で読んでいるが、
 * vanilla の行の書き換えなので、配線({@code patches/wire})から呼ぶ形にした。
 *
 * <p>設定の値は vanilla の NMS からは読まれない(Paper のパッチは当てていない)。
 * 読むのはアダプタ層と、そこから呼ばれるプラグインだけ。
 *
 * <p>読んだ位置: Paper-Server
 * {@code src/main/java/net/minecraft/server/dedicated/DedicatedServer.java}(initServer),
 * {@code src/main/java/com/destroystokyo/paper/PaperConfig.java}(init)
 */
public final class ShifuBootstrap {
    /**
     * Bukkit の Main が解析した起動時の引数。
     *
     * <p>Paper は MinecraftServer の構築子の引数を 1 つ増やして渡すが、vanilla の
     * 構築子の宣言は変えられない。アダプタ層(PaperBootstrap)が起動前にここへ置き、
     * 構築子の中で {@code this.options} に移す(patches/wire)。
     * CraftServer.getConfigFile() が this.console.options を読むので、これが無いと
     * CraftServer の構築中に NPE になる。
     *
     * <p>MinecraftServer の static 欄にしないのは、触ると MinecraftServer の初期化が
     * 走るため。1.21.11 の DEMO_SETTINGS は {@code new GameRules(...)} を作り、
     * ゲームルールの登録は BuiltInRegistries を触るので、{@code Bootstrap.bootStrap()}
     * より前に走ると「Not bootstrapped」で落ちる。
     */
    public static OptionSet options;

    private ShifuBootstrap() {
    }


    /**
     * プラグインのログ(java.util.logging)を log4j に流し、System.out と System.err も向ける。
     *
     * <p>これが無いと、プラグインの {@code getLogger()} の出力が JUL の既定の書式で
     * 標準エラーに出るだけになり、{@code [HH:mm:ss INFO]:} の書式にも
     * {@code logs/latest.log} にも入らない。
     *
     * <p>読んだ位置: paper-server
     * patches/sources/net/minecraft/server/dedicated/DedicatedServer.java.patch:60-72
     */
    public static void forwardPluginLogs() {
        final java.util.logging.Logger global = java.util.logging.Logger.getLogger("");
        global.setUseParentHandlers(false);

        for (final java.util.logging.Handler handler : global.getHandlers()) {
            global.removeHandler(handler);
        }

        global.addHandler(new org.bukkit.craftbukkit.util.ForwardLogHandler());

        final org.apache.logging.log4j.Logger logger = org.apache.logging.log4j.LogManager.getRootLogger();
        System.setOut(org.apache.logging.log4j.io.IoBuilder.forLogger(logger)
                .setLevel(org.apache.logging.log4j.Level.INFO).buildPrintStream());
        System.setErr(org.apache.logging.log4j.io.IoBuilder.forLogger(logger)
                .setLevel(org.apache.logging.log4j.Level.WARN).buildPrintStream());
    }

    public static void exitAfterServerThread() {
        final Thread serverThread = Thread.currentThread();
        final Thread exit = new Thread(() -> {
            try {
                serverThread.join();
            } catch (final InterruptedException ignored) {
                return;
            }

            System.exit(0);
        }, "Shifu exit");
        exit.setDaemon(true);
        exit.start();
    }

    /**
     * Paper の設定({@code paper.yml})を読む。1.18.2 の Paper は
     * {@code PaperConfig} の static 欄に読み込むので、サーバーごとの持ち物は無い。
     * {@code Level} の構築子が {@code PaperWorldConfig} を作るため、世界を読む前に済ませる。
     */
    public static void initializeConfigurations(final MinecraftServer server) {
        com.destroystokyo.paper.PaperConfig.init((File) options.valueOf("paper-settings"));
        com.destroystokyo.paper.PaperConfig.registerCommands();
    }
}
