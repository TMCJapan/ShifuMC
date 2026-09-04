// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.probe;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * プレイヤー経路のイベントが届くかを、bot を動かして確かめる。
 *
 * <p>bot が参加したら、chat で {@code !bot <指示>} を送って動かし、
 * 届いたイベントを {@code [probe]} の行で残す。最後に数え上げて止める。
 */
public final class PlayerProbe extends JavaPlugin implements Listener {
    private final List<String> seen = new ArrayList<>();
    private final Map<String, Integer> counts = new TreeMap<>();
    private UUID botId;
    private Player joined;
    private int moves;
    private int interacts;
    private boolean cancelledOnce;

    @Override
    public void onEnable() {
        this.getServer().getPluginManager().registerEvents(this, this);
        this.getServer().getMessenger().registerIncomingPluginChannel(this, "shifu:probe",
                (channel, player, message) -> this.note("plugin message on " + channel
                        + " from " + player.getName() + ": " + new String(message, java.nio.charset.StandardCharsets.UTF_8)));
        this.getServer().getMessenger().registerOutgoingPluginChannel(this, "shifu:probe");
        this.getLogger().info("[probe] waiting for the bot");
    }

    private void note(final String text) {
        this.seen.add(text);
        this.getLogger().info("[probe] " + text);
    }

    private void count(final String name) {
        this.counts.merge(name, 1, Integer::sum);
    }

    private void tell(final Player player, final String text) {
        player.sendMessage(Component.text(text));
    }

    private void later(final long ticks, final Runnable task) {
        Bukkit.getScheduler().runTaskLater(this, task, ticks);
    }

    @EventHandler
    public void onAsyncPreLogin(final AsyncPlayerPreLoginEvent event) {
        this.note("async pre-login " + event.getName() + " async=" + event.isAsynchronous() + " thread=" + Thread.currentThread().getName());
    }

    @EventHandler
    @SuppressWarnings("deprecation")
    public void onPreLogin(final PlayerPreLoginEvent event) {
        this.note("pre-login (sync) " + event.getName() + " thread=" + Thread.currentThread().getName());
    }

    @EventHandler
    public void onLogin(final PlayerLoginEvent event) {
        this.note("login " + event.getPlayer().getName() + " address=" + event.getAddress() + " result=" + event.getResult());
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        final Player bot = event.getPlayer();
        this.botId = bot.getUniqueId();
        this.joined = bot;
        this.note("join " + bot.getName() + " at " + brief(bot.getLocation()));

        this.later(40, () -> {
            bot.setGameMode(org.bukkit.GameMode.CREATIVE);
            final Location spawn = bot.getWorld().getSpawnLocation();
            final Location high = new Location(bot.getWorld(), spawn.getX() + 0.5, 200.0, spawn.getZ() + 0.5, 0.0F, 0.0F);
            this.note("teleport (plugin) -> " + bot.teleport(high));
        });
        this.later(80, () -> this.tell(bot, "!bot move"));
        this.later(120, () -> this.tell(bot, "!bot chat hello from the bot"));
        this.later(160, () -> {
            bot.getInventory().setItem(0, new ItemStack(Material.STONE, 16));
            this.tell(bot, "!bot drop");
        });
        this.later(200, () -> {
            bot.getInventory().setItem(0, new ItemStack(Material.STONE, 16));
            this.tell(bot, "!bot click 36");
        });
        // ブロックの右クリックと左クリック。足元 2 つ下に石を置いて、bot にその位置を教える
        this.later(240, () -> {
            final Location at = bot.getLocation();
            final Block block = at.getWorld().getBlockAt(at.getBlockX(), at.getBlockY() - 2, at.getBlockZ());
            block.setType(Material.STONE);
            bot.getInventory().setItem(0, new ItemStack(Material.STONE, 16));
            this.tell(bot, "!bot use " + block.getX() + " " + block.getY() + " " + block.getZ());
        });
        this.later(260, () -> {
            final Location at = bot.getLocation();
            final Block block = at.getWorld().getBlockAt(at.getBlockX(), at.getBlockY() - 2, at.getBlockZ());
            this.note("block under bot before dig: " + block.getType());
            this.tell(bot, "!bot dig " + block.getX() + " " + block.getY() + " " + block.getZ());
        });
        this.later(280, () -> {
            final Location at = bot.getLocation();
            final Block block = at.getWorld().getBlockAt(at.getBlockX(), at.getBlockY() - 2, at.getBlockZ());
            this.note("block under bot after dig: " + block.getType());
        });
        // 作物の生長。近くに畑を作って randomTickSpeed を上げる
        this.later(300, () -> {
            final Location at = bot.getLocation();
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    at.getWorld().getBlockAt(at.getBlockX() + dx, 190, at.getBlockZ() + dz).setType(Material.FARMLAND);
                    at.getWorld().getBlockAt(at.getBlockX() + dx, 191, at.getBlockZ() + dz).setType(Material.WHEAT);
                }
            }
            at.getWorld().getBlockAt(at.getBlockX(), 191, at.getBlockZ()).setType(Material.WATER);
            // 26.2 の /gamerule は名前の形が変わっているので、API で設定する
            this.note("farm placed, randomTickSpeed 4096 -> " + at.getWorld().setGameRule(org.bukkit.GameRule.RANDOM_TICK_SPEED, 4096));
        });
        this.later(360, () -> {
            this.note("randomTickSpeed back -> " + bot.getWorld().setGameRule(org.bukkit.GameRule.RANDOM_TICK_SPEED, 3));
            this.note("grow=" + this.counts.getOrDefault("BlockGrowEvent", 0)
                    + " spread=" + this.counts.getOrDefault("BlockSpreadEvent", 0)
                    + " form=" + this.counts.getOrDefault("BlockFormEvent", 0));
        });
        this.later(380, () -> this.note("tp command -> "
                + Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tp " + bot.getName() + " ~ ~ ~5")));
        // 飛行の切り替え。1 回目は取り消す。前回の接続の状態が残らないよう、先に降ろす
        this.later(388, () -> {
            this.note("flying before the test = " + bot.isFlying() + ", allowed = " + bot.getAllowFlight());
            bot.setFlying(false);
        });
        this.later(390, () -> this.tell(bot, "!bot fly true"));
        this.later(396, () -> {
            this.note("flying after cancelled toggle = " + bot.isFlying());
            this.tell(bot, "!bot fly true");
        });
        this.later(402, () -> this.note("flying after allowed toggle = " + bot.isFlying()));
        // ゲームモード。コマンドとプラグインの両方
        this.later(404, () -> this.note("gamemode command -> "
                + Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "gamemode survival " + bot.getName())));
        this.later(406, () -> {
            bot.setGameMode(org.bukkit.GameMode.CREATIVE);
            this.note("gamemode after plugin set = " + bot.getGameMode());
        });
        // TNT の点火。レッドストーンブロックを隣に置く
        this.later(408, () -> {
            final Location at = bot.getLocation();
            final Block tnt = at.getWorld().getBlockAt(at.getBlockX() + 3, 100, at.getBlockZ());
            tnt.setType(Material.TNT);
            tnt.getRelative(1, 0, 0).setType(Material.REDSTONE_BLOCK);
            this.note("tnt block after redstone = " + tnt.getType());
        });
        // 世界の境界。1 回目は取り消す
        this.later(409, () -> {
            final org.bukkit.WorldBorder border = bot.getWorld().getWorldBorder();
            this.note("border before = " + border.getSize());
            border.setSize(100.0);
            this.note("border after cancelled change = " + border.getSize());
            border.setSize(120.0);
            this.note("border after allowed change = " + border.getSize());
        });
        // コンソールのコマンド(ServerCommandEvent と WorldGameRuleChangeEvent、TimeSkipEvent)。
        // 26.2 のゲームルールは名前空間付きの id で指定する
        this.later(409, () -> this.note("gamerule command -> "
                + Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "gamerule block_drops false")));
        this.later(411, () -> this.note("time command -> "
                + Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "time set midnight")));
        // 食事。腹を減らしてからパンを使う(32 tick で食べ終える)
        this.later(410, () -> {
            bot.setFoodLevel(10);
            bot.getInventory().setItem(0, new ItemStack(Material.BREAD, 4));
            this.tell(bot, "!bot eat");
        });
        this.later(450, () -> this.note("bread left = " + bot.getInventory().getItem(0)));
        // プラグインメッセージ。bot が名乗りを送って、プラグインから読めるか
        this.later(452, () -> this.tell(bot, "!bot brand shifu-bot"));
        this.later(456, () -> {
            this.note("client brand = " + bot.getClientBrandName());
            this.note("listening channels = " + bot.getListeningPluginChannels());
        });
        this.later(420, () -> {
            final Location at = bot.getLocation();
            final Location ground = new Location(at.getWorld(), at.getX(), 100.0, at.getZ());
            // 岩盤より上の空中で爆発させる。ブロックが無いので壊すものは無いが、発火はする
            this.note("block explosion -> " + at.getWorld().createExplosion(ground, 3.0F, false, true));
            this.note("entity explosion (source = bot) -> " + at.getWorld().createExplosion(ground, 3.0F, false, true, bot));
            final TNTPrimed tnt = at.getWorld().spawn(ground, TNTPrimed.class);
            tnt.setFuseTicks(2);
            this.note("tnt spawned valid=" + tnt.isValid() + " dead=" + tnt.isDead() + " fuse=" + tnt.getFuseTicks());
            this.later(5, () -> this.note("tnt after 5 ticks dead=" + tnt.isDead() + " fuse=" + tnt.getFuseTicks()
                    + " tnt in world=" + at.getWorld().getEntitiesByClass(TNTPrimed.class).size()));
        });
        this.later(480, () -> {
            this.note("killing the bot (health " + bot.getHealth() + ")");
            bot.setHealth(0.0);
        });
        this.later(580, () -> {
            final Player now = Bukkit.getPlayer(this.botId);
            this.note("after respawn: same Player object = " + (now == this.joined)
                    + ", alive = " + (now != null && !now.isDead())
                    + ", at " + (now == null ? "?" : brief(now.getLocation())));
        });
        this.later(620, () -> {
            this.note("---- summary ----");
            for (String line : this.seen) {
                this.getLogger().info("[probe]   " + line);
            }
            this.getLogger().info("[probe]   counts " + this.counts);
            this.getLogger().info("[probe]   border size at the end = " + bot.getWorld().getWorldBorder().getSize());
            this.tell(bot, "!bot quit");
        });
        this.later(660, () -> Bukkit.shutdown());
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        this.note("quit " + event.getPlayer().getName());
    }

    @EventHandler
    public void onMove(final PlayerMoveEvent event) {
        this.moves++;

        if (this.moves <= 3 || this.moves % 10 == 0) {
            this.note("move #" + this.moves + " " + brief(event.getFrom()) + " -> " + brief(event.getTo()));
        }

        // 3 回目は取り消す。bot にはテレポートが届くはず
        if (this.moves == 3 && !this.cancelledOnce) {
            this.cancelledOnce = true;
            event.setCancelled(true);
            this.note("move #3 cancelled");
        }
    }

    @EventHandler
    public void onChat(final AsyncChatEvent event) {
        final String text = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(event.message());
        this.note("chat async=" + event.isAsynchronous() + " thread=" + Thread.currentThread().getName() + " message=\"" + text + "\"");
        event.message(Component.text("[probe] " + text));
    }

    @EventHandler
    public void onClick(final InventoryClickEvent event) {
        this.note("inventory click slot=" + event.getSlot() + " raw=" + event.getRawSlot() + " click=" + event.getClick()
                + " action=" + event.getAction() + " item=" + (event.getCurrentItem() == null ? "null" : event.getCurrentItem().getType()));
    }

    @EventHandler
    public void onDrop(final PlayerDropItemEvent event) {
        this.note("drop " + event.getItemDrop().getItemStack().getType() + " x" + event.getItemDrop().getItemStack().getAmount());
    }

    @EventHandler
    public void onInteract(final PlayerInteractEvent event) {
        this.interacts++;
        this.note("interact #" + this.interacts + " action=" + event.getAction() + " block="
                + (event.getClickedBlock() == null ? "null" : event.getClickedBlock().getType() + "@" + brief(event.getClickedBlock().getLocation()))
                + " face=" + event.getBlockFace() + " hand=" + event.getHand() + " item="
                + (event.getItem() == null ? "null" : event.getItem().getType()));
    }

    @EventHandler
    public void onBreak(final BlockBreakEvent event) {
        this.note("block break " + event.getBlock().getType() + " at " + brief(event.getBlock().getLocation()) + " by " + event.getPlayer().getName());
    }

    @EventHandler
    public void onChannelRegister(final org.bukkit.event.player.PlayerRegisterChannelEvent event) {
        this.note("register channel " + event.getChannel());
    }

    @EventHandler
    public void onBorder(final io.papermc.paper.event.world.border.WorldBorderBoundsChangeEvent event) {
        this.count("WorldBorderBoundsChangeEvent");
        this.note("border change " + event.getOldSize() + " -> " + event.getNewSize() + " type=" + event.getType());
        if (this.counts.get("WorldBorderBoundsChangeEvent") == 1) {
            event.setCancelled(true);
            this.note("cancelled the first WorldBorderBoundsChangeEvent");
        }
    }

    @EventHandler
    public void onGameRule(final io.papermc.paper.event.world.WorldGameRuleChangeEvent event) {
        this.note("gamerule change " + event.getGameRule().getName() + " -> " + event.getValue());
    }

    @EventHandler
    public void onTimeSkip(final org.bukkit.event.world.TimeSkipEvent event) {
        this.note("time skip reason=" + event.getSkipReason() + " amount=" + event.getSkipAmount());
    }

    @EventHandler
    public void onFoodLevel(final org.bukkit.event.entity.FoodLevelChangeEvent event) {
        this.note("food level -> " + event.getFoodLevel()
                + " item=" + (event.getItem() == null ? null : event.getItem().getType()));
    }

    @EventHandler
    public void onToggleFlight(final org.bukkit.event.player.PlayerToggleFlightEvent event) {
        this.count("PlayerToggleFlightEvent");
        this.note("toggle flight flying=" + event.isFlying());
        if (this.counts.get("PlayerToggleFlightEvent") == 1) {
            event.setCancelled(true);
            this.note("cancelled the first PlayerToggleFlightEvent");
        }
    }

    @EventHandler
    public void onGameMode(final org.bukkit.event.player.PlayerGameModeChangeEvent event) {
        this.note("gamemode change " + event.getNewGameMode() + " cause=" + event.getCause());
    }

    @EventHandler
    public void onTntPrime(final org.bukkit.event.block.TNTPrimeEvent event) {
        this.note("tnt prime cause=" + event.getCause() + " entity=" + event.getPrimingEntity()
                + " block=" + (event.getPrimingBlock() == null ? null : event.getPrimingBlock().getType()));
    }

    @EventHandler
    public void onConsume(final org.bukkit.event.player.PlayerItemConsumeEvent event) {
        this.note("consume " + event.getItem().getType() + " hand=" + event.getHand());
    }

    @EventHandler
    public void onServerCommand(final org.bukkit.event.server.ServerCommandEvent event) {
        this.note("server command " + event.getCommand());
    }

    @EventHandler
    public void onEntityChangeBlock(final org.bukkit.event.entity.EntityChangeBlockEvent event) {
        this.count("EntityChangeBlockEvent");
    }

    @EventHandler
    public void onGrow(final BlockGrowEvent event) {
        this.count("BlockGrowEvent");

        if (this.counts.get("BlockGrowEvent") == 1) {
            this.note("first grow " + event.getBlock().getType() + " -> " + event.getNewState().getBlockData().getAsString()
                    + " at " + brief(event.getBlock().getLocation()));
            // 1 つめは取り消す。あとで確かめられるように位置を残す
            event.setCancelled(true);
            this.note("first grow cancelled; block is now " + event.getBlock().getBlockData().getAsString());
            this.later(1, () -> this.note("block one tick after cancel: " + event.getBlock().getBlockData().getAsString()));
        }
    }

    @EventHandler
    public void onSpread(final BlockSpreadEvent event) {
        this.count("BlockSpreadEvent");
    }

    @EventHandler
    public void onForm(final BlockFormEvent event) {
        this.count("BlockFormEvent");
    }

    @EventHandler
    public void onTeleport(final PlayerTeleportEvent event) {
        this.note("teleport cause=" + event.getCause() + " " + brief(event.getFrom()) + " -> " + brief(event.getTo()));
    }

    @EventHandler
    public void onChangedWorld(final PlayerChangedWorldEvent event) {
        this.note("changed world from " + event.getFrom().getName());
    }

    @EventHandler
    public void onDeath(final PlayerDeathEvent event) {
        this.note("death message=\"" + (event.deathMessage() == null ? null
                : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(event.deathMessage()))
                + "\" drops=" + event.getDrops().size() + " exp=" + event.getDroppedExp() + " keepInventory=" + event.getKeepInventory());
        event.deathMessage(Component.text("[probe] the bot died"));
    }

    @EventHandler
    public void onRespawn(final PlayerRespawnEvent event) {
        this.note("respawn reason=" + event.getRespawnReason() + " at " + brief(event.getRespawnLocation()) + " bed=" + event.isBedSpawn());
        event.setRespawnLocation(event.getRespawnLocation().clone().add(0, 5, 0));
    }

    @EventHandler
    public void onEntityExplode(final EntityExplodeEvent event) {
        this.note("entity explode " + event.getEntityType() + " blocks=" + event.blockList().size() + " yield=" + event.getYield());
    }

    @EventHandler
    public void onBlockExplode(final BlockExplodeEvent event) {
        this.note("block explode at " + brief(event.getBlock().getLocation()) + " blocks=" + event.blockList().size());
    }

    private static String brief(final Location location) {
        return String.format("%s(%.1f, %.1f, %.1f)", location.getWorld() == null ? "?" : location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ());
    }
}
