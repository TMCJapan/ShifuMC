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
        this.getLogger().info("[drive] waiting for the bot");
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
        this.note("join " + bot.getName());
        bot.setOp(true);
        bot.setGameMode(org.bukkit.GameMode.CREATIVE);

        // 空中に足場を作って、その上で WorldEdit を使わせる
        this.origin = new Location(bot.getWorld(), bot.getLocation().getBlockX(), 150.0, bot.getLocation().getBlockZ());
        this.later(20, () -> {
            bot.teleport(this.origin);
            this.note("teleported to " + brief(this.origin));
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
        this.later(140, () -> {
            this.note("fly speed before /speed = " + bot.getFlySpeed());
            this.tell(bot, "!bot cmd speed 3");
        });
        this.later(150, () -> this.note("fly speed after /speed 3 = " + bot.getFlySpeed()));
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
