// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.event;

import io.papermc.paper.configuration.PaperConfigurations;
import java.io.File;
import java.nio.file.Path;
import joptsimple.OptionSet;
import net.minecraft.server.MinecraftServer;

/**
 * Paper の設定を立ち上げる。
 *
 * <p>アダプタ層({@code CraftWorld} や {@code ChatProcessor})は
 * {@code Level.paperConfig()} と {@code GlobalConfiguration.get()} が入っている
 * 前提で書かれている。Paper は {@code Services.create} と {@code Level} の構築子で
 * 作っているが、どちらも vanilla の行の書き換えなので、配線
 * ({@code patches/wire})から呼ぶ形にした。
 *
 * <p>設定の値は vanilla の NMS からは読まれない(Paper のパッチは当てていない)。
 * 読むのはアダプタ層と、そこから呼ばれるプラグインだけ。
 *
 * <p>参照した位置(Paper 26.2):
 * {@code paper-server patches/sources/net/minecraft/server/Services.java.patch:28},
 * {@code paper-server patches/sources/net/minecraft/server/dedicated/DedicatedServer.java.patch:105}
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

    /** {@code paper.yml} / {@code config/} / {@code spigot.yml} の場所は Bukkit の Main が解析した引数から取る。 */
    public static PaperConfigurations paperConfigurations(final OptionSet options) {
        try {
            final Path legacy = ((File) options.valueOf("paper-settings")).toPath();
            final Path configDir = ((File) options.valueOf("paper-settings-directory")).toPath();
            final Path universe = ((File) options.valueOf("universe")).toPath();

            return PaperConfigurations.setup(legacy, configDir, universe, (File) options.valueOf("spigot-settings"));
        } catch (final Exception e) {
            throw new IllegalStateException("Paper の設定を用意できない", e);
        }
    }

    /**
     * 全体の設定とワールドの既定を読む。{@code GlobalConfiguration.get()} がここで入る。
     * ワールドごとの設定は {@code ServerLevel} が自分で作る({@code shifuCreateConfigs})。
     */
    public static void initializeConfigurations(final MinecraftServer server) {
        try {
            server.paperConfigurations.initializeGlobalConfiguration(server.registryAccess());
            server.paperConfigurations.initializeWorldDefaultsConfiguration(server.registryAccess());
        } catch (final org.spongepowered.configurate.ConfigurateException e) {
            throw new IllegalStateException("Paper の設定を読めない", e);
        }
    }

    /**
     * stop のあと JVM を終える。Bukkit のスケジューラ({@code CraftAsyncScheduler})の
     * 管理スレッドが非デーモンなので、vanilla のように放っておくと JVM が残る。
     * Paper は {@code DedicatedServer.stopServer} の末尾で {@code System.exit} するが、
     * それはサーバースレッドの上で、vanilla の shutdown hook({@code halt(true)})が
     * サーバースレッドを待つので待ち合いになる(Paper は hook を外している)。
     * Shifu はサーバースレッドが終わるのを別のスレッドで待ってから exit する。
     * 他に非デーモンのスレッドが無ければ、その前に vanilla と同じく自然に終わる。
     */
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
}
