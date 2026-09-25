// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.drive;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import net.kyori.adventure.text.Component;

/**
 * 実際のプラグイン(WorldEdit と EssentialsX)を bot に叩かせて、結果を見る検証用プラグイン。
 *
 * <p>bot が参加したら op にして、チャットで {@code !bot cmd <コマンド>} を送る。
 * ブロックが本当に変わったかは、この側から読んで {@code [drive]} の行に残す。
 */
public final class PluginDrive extends JavaPlugin implements Listener {
    private Location origin;

    @Override
    public void onEnable() {
        this.getServer().getPluginManager().registerEvents(this, this);
        // 世界の名前・鍵・環境。参加した世界が world_nether と出たので、並びを見る
        for (final org.bukkit.World world : Bukkit.getWorlds()) {
            this.note("world " + world.getName() + " key=" + world.getKey() + " env=" + world.getEnvironment()
                    + " spawn=" + brief(world.getSpawnLocation()));
        }
        this.getLogger().info("[drive] waiting for the bot");
    }

    @EventHandler
    public void onCommandPreprocess(final org.bukkit.event.player.PlayerCommandPreprocessEvent event) {
        this.note("PlayerCommandPreprocessEvent " + event.getMessage());
    }

    private void note(final String text) {
        this.getLogger().info("[drive] " + text);
    }

    private void tell(final Player player, final String text) {
        player.sendMessage(Component.text(text));
    }

    private void later(final long ticks, final Runnable task) {
        Bukkit.getScheduler().runTaskLater(this, task, ticks);
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        final Player bot = event.getPlayer();
        this.note("join " + bot.getName() + " in " + bot.getWorld().getName() + " at " + brief(bot.getLocation()));
        bot.setOp(true);
        bot.setGameMode(org.bukkit.GameMode.CREATIVE);

        // AuthMe を入れていると、登録するまでコマンドが通らず、飛ぶ速さも 0 にされる。
        // 26.2 の run-mix で入れ忘れていたので、/home も /flyspeed も //set も
        // 「In order to use this command you must be authenticated!」で止まっていた(9-25)。
        // 2 度目からは登録済みなので、どちらも送る。効かない方は
        // 「登録済み」「ログイン済み」で断られるだけで害が無い。
        // (読んだ位置: AuthMe 6.0.1 LimboServiceHelper.revokeLimboStates の setFlySpeed(0) / setWalkSpeed(0)、
        // PlayerListener.onPlayerCommandPreprocess の DENIED_COMMAND、javap)
        if (Bukkit.getPluginManager().isPluginEnabled("AuthMe")) {
            this.later(10, () -> this.tell(bot, "!bot cmd register shifu1234 shifu1234"));
            this.later(14, () -> this.tell(bot, "!bot cmd login shifu1234"));
        }

        // 空中に足場を作って、その上で WorldEdit を使わせる
        this.origin = new Location(bot.getWorld(), bot.getLocation().getBlockX(), 150.0, bot.getLocation().getBlockZ());
        this.later(20, () -> {
            // AuthMe はログインしたところで、参加した時点の op を戻す(参加の時点では op でない)。
            // 入れ直さないと /sethome も //set も権限なしで断られた(9-25 の 26.2)。
            // (読んだ位置: AuthMe 6.0.1 LimboServiceHelper.createLimboPlayer の Player.isOp、revokeLimboStates の setOp、javap)
            bot.setOp(true);
            // ver/1.21.11 の drive と同じく、空中へ運ぶ前にクリエイティブへ入れ直す
            bot.setGameMode(org.bukkit.GameMode.CREATIVE);
            bot.teleport(this.origin);
            this.note("teleported to " + brief(this.origin) + " (" + bot.getGameMode() + ")");
        });

        // EssentialsX。結果が読めるように、家を置いて離れてから戻る
        this.later(60, () -> this.tell(bot, "!bot cmd sethome shifu"));
        this.later(80, () -> {
            bot.teleport(this.origin.clone().add(40, 0, 40));
            this.note("moved away to " + brief(bot.getLocation()));
        });
        this.later(100, () -> this.tell(bot, "!bot cmd home shifu"));
        this.later(130, () -> this.note("after /home: " + brief(bot.getLocation())
                + " (home was " + brief(this.origin) + ", distance "
                + String.format("%.1f", bot.getLocation().distance(this.origin)) + ")"));
        // EssentialsX の /speed は、種類を書かないと isFlying() で歩く速さか飛ぶ速さかを選ぶ。
        // bot が飛んでいなければ /speed 3 は歩く速さを変える。飛ぶ速さは別名の /flyspeed で変えさせる
        // (読んだ位置: EssentialsX 2.22.0 Commandspeed.run の isFlyAlias / isWalkAlias / Player.isFlying、javap)。
        // run-mix の ver/* の版には EssentialsX が入っていない。/speed は Unknown command になっていた。
        this.later(140, () -> {
            if (!Bukkit.getPluginManager().isPluginEnabled("Essentials")) {
                this.note("EssentialsX is not installed; /flyspeed not checked");
                return;
            }
            this.note("fly speed before /flyspeed = " + bot.getFlySpeed());
            this.tell(bot, "!bot cmd flyspeed 3");
            this.later(10, () -> this.note("fly speed after /flyspeed 3 = " + bot.getFlySpeed()));
        });
        // タブ一覧の表示名。vanilla は常に null を返すので、入れた名前が返るかを見る
        this.later(152, () -> {
            bot.setPlayerListName("ShifuTab");
            this.note("player list name = " + bot.getPlayerListName());
        });
        this.later(155, () -> this.tell(bot, "!bot cmd gamemode survival"));
        this.later(158, () -> {
            this.note("gamemode after /gamemode survival = " + bot.getGameMode());
            this.tell(bot, "!bot cmd gamemode creative");
        });

        // WorldEdit。//pos1 //pos2 //set stone で 3x3x3 を石にする
        this.later(160, () -> {
            final Location a = this.origin.clone().add(2, 0, 2);
            final Location b = this.origin.clone().add(4, 2, 4);
            this.note("region " + brief(a) + " .. " + brief(b));
            this.tell(bot, "!bot cmd /pos1 " + a.getBlockX() + "," + a.getBlockY() + "," + a.getBlockZ());
            this.later(20, () -> this.tell(bot, "!bot cmd /pos2 " + b.getBlockX() + "," + b.getBlockY() + "," + b.getBlockZ()));
            this.later(40, () -> this.tell(bot, "!bot cmd /set stone"));
            this.later(70, () -> {
                int stone = 0;

                for (int x = a.getBlockX(); x <= b.getBlockX(); x++) {
                    for (int y = a.getBlockY(); y <= b.getBlockY(); y++) {
                        for (int z = a.getBlockZ(); z <= b.getBlockZ(); z++) {
                            final Block block = a.getWorld().getBlockAt(x, y, z);

                            if (block.getType() == Material.STONE) {
                                stone++;
                            }
                        }
                    }
                }

                this.note("//set stone -> " + stone + " / 27 blocks are stone");
                this.tell(bot, "!bot cmd /undo");
            });
            this.later(100, () -> {
                int air = 0;

                for (int x = a.getBlockX(); x <= b.getBlockX(); x++) {
                    for (int y = a.getBlockY(); y <= b.getBlockY(); y++) {
                        for (int z = a.getBlockZ(); z <= b.getBlockZ(); z++) {
                            if (a.getWorld().getBlockAt(x, y, z).getType() == Material.AIR) {
                                air++;
                            }
                        }
                    }
                }

                this.note("//undo -> " + air + " / 27 blocks are air again");
            });
        });

        this.later(300, () -> {
            this.note("---- done ----");
            this.tell(bot, "!bot quit");
        });
        this.later(340, () -> Bukkit.shutdown());
    }

    private static String brief(final Location at) {
        return String.format("%s(%.1f, %.1f, %.1f)", at.getWorld().getName(), at.getX(), at.getY(), at.getZ());
    }
}
