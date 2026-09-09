// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.event;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import io.papermc.paper.adventure.PaperAdventure;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.rcon.RconConsoleSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.dimension.end.EndDragonFight;
import net.minecraft.world.level.levelgen.feature.EndPodiumFeature;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Location;
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.craftbukkit.block.CraftBlockState;
import org.bukkit.craftbukkit.block.CraftBlockStates;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.craftbukkit.util.CraftLocation;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.TimeSkipEvent;

import java.net.DatagramPacket;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * プレイヤー・サーバー・世界(境界、時間、生成)の発火。{@link ShifuEvents} と同じ約束:
 * 登録が無ければ何も作らずに「vanilla を続けてよい」を返し、返り値は「vanilla の処理を続けてよいか」。
 * 差し込む位置は {@code patches/events/player-server.rules}。
 */
public final class PlayerEvents {
    private PlayerEvents() {
    }

    private static boolean listening(final org.bukkit.event.HandlerList handlers) {
        return ShifuEvents.listening(handlers);
    }

    // ------------------------------------------------------------ コマンド

    /** UnknownCommandEvent に登録が無いか(無ければ vanilla の送信をそのまま行う)。 */
    public static boolean silentUnknownCommand() {
        return !listening(org.bukkit.event.command.UnknownCommandEvent.getHandlerList());
    }






    /**
     * PlayerCommandPreprocessEvent(署名の無いコマンド)。解析の前。
     * 取り消しは効く。文の差し替えは hand の {@code shifuPerformUnsignedChatCommand} で
     * 差し替えた文を改めて通す(そのときはこの発火を通らない)。
     *
     * 読んだ位置: paper-server patches/sources/net/minecraft/server/network/ServerGamePacketListenerImpl.java.patch(PlayerCommandPreprocessEvent)
     */
    private static boolean rewritingCommand;




    /**
     * RemoteServerCommandEvent(RCON)。Paper は実行するサーバースレッドのタスクの中で発火する。
     * Shifu は vanilla のタスクの前に、自分のタスクで発火して待つ(2 回サーバースレッドに乗る)。
     * 文の差し替えは効かない(vanilla の lambda が final の引数を掴んでいる)。
     */
    public static boolean remoteCommand(final DedicatedServer server, final RconConsoleSource source, final String command) {
        if (!listening(org.bukkit.event.server.RemoteServerCommandEvent.getHandlerList())) {
            return true;
        }

        final boolean[] allowed = {true};
        server.executeBlocking(() -> {
            final CommandSourceStack wrapper = source.createCommandSourceStack();
            final org.bukkit.event.server.RemoteServerCommandEvent event = new org.bukkit.event.server.RemoteServerCommandEvent(
                    source.getBukkitSender(wrapper), command);
            allowed[0] = event.callEvent();
        });

        return allowed[0];
    }

    /** WhitelistToggleEvent。設定を書く前。取り消しは無い。 */
    public static void whitelistToggle(final boolean enabled) {
        if (!listening(com.destroystokyo.paper.event.server.WhitelistToggleEvent.getHandlerList())) {
            return;
        }

        new com.destroystokyo.paper.event.server.WhitelistToggleEvent(enabled).callEvent();
    }


    private static io.papermc.paper.command.brigadier.CommandSourceStack difficultySource;


    /**
     * PlayerCommandPreprocessEvent。コマンドを実行する直前。
     *
     * <p>文が差し替えられたら、差し替えた文で vanilla の私有メソッドを呼び直して false を返す。
     * 呼び直しの中でもう 1 度発火しないように旗で止める(Paper も同じ形)。
     *
     * @return vanilla の実行へ進んでよいか
     */
    public static boolean commandPreprocess(final net.minecraft.server.network.ServerGamePacketListenerImpl connection,
                                            final String command) {
        if (rewritingCommand
                || !listening(org.bukkit.event.player.PlayerCommandPreprocessEvent.getHandlerList())) {
            return true;
        }

        final org.bukkit.event.player.PlayerCommandPreprocessEvent event =
                new org.bukkit.event.player.PlayerCommandPreprocessEvent(
                        connection.player.getBukkitEntity(), "/" + command,
                        new org.bukkit.craftbukkit.util.LazyPlayerSet(connection.player.level().getServer()));

        if (!event.callEvent()) {
            return false;
        }

        final String changed = event.getMessage().substring(1);

        if (changed.equals(command)) {
            return true;
        }

        rewritingCommand = true;

        try {
            connection.shifuPerformUnsignedChatCommand(changed);
        } finally {
            rewritingCommand = false;
        }

        return false;
    }

    // ------------------------------------------------------------ 眠り

    /**
     * Player.setSleepingIgnored を入れた人が 1 人でも居るか。
     * 誰も呼んでいなければ全員 false なので、vanilla の判定へ進んでよい。
     */
    public static boolean anySleepIgnored(final java.util.List<ServerPlayer> players) {
        for (final ServerPlayer player : players) {
            if (player.fauxSleeping) {
                return true;
            }
        }

        return false;
    }

    /**
     * 「寝ている割合が足りているか」を、setSleepingIgnored の分も数えて出す。
     *
     * <p>Paper と同じで、**実際に深く寝ている人が 1 人も居なければ false**。
     * 無人の夜が飛ばないようにするため。
     *
     * 読んだ位置: patches/server/Add-PlayerSetSpawnEvent.patch と
     *            Paper-Server src/main/java/net/minecraft/server/players/SleepStatus.java
     */
    public static boolean enoughDeepSleeping(final java.util.List<ServerPlayer> players,
                                             final int deepSleepers, final int needed) {
        int counted = deepSleepers;
        boolean anyDeepSleep = false;

        for (final ServerPlayer player : players) {
            if (player.isSleepingLongEnough()) {
                anyDeepSleep = true;
            } else if (player.fauxSleeping) {
                counted++;
            }
        }

        return anyDeepSleep && counted >= needed;
    }


    // ------------------------------------------------------------ 時間

    private static boolean timeListening() {
        return listening(TimeSkipEvent.getHandlerList());
    }

    /** TimeSkipEvent を作って発火する。vanilla の時間は世界ごと。 */
    private static TimeSkipEvent timeSkip(final org.bukkit.World world, final TimeSkipEvent.SkipReason reason, final long amount) {
        final TimeSkipEvent event = new TimeSkipEvent(world, reason, amount);
        event.callEvent();

        return event;
    }



    /**
     * 夜を飛ばす前の時刻。控えていなければ {@link Long#MIN_VALUE}。
     *
     * <p>静的で持てるのは、ここを通るのがサーバースレッドだけだから
     * ({@code ServerLevel.tick})。局所変数にすると vanilla の {@code l} が
     * 3 つ後ろの slot に入り、MixinExtras の {@code @Local} が当たらなくなる。
     */
    private static long nightTime = Long.MIN_VALUE;

    /** 夜を飛ばしたあと、プレイヤーを起こしてよいか。取り消されたときだけ false。 */
    private static boolean nightWake = true;

    /** 夜を飛ばす前の時刻を控える。登録が無ければ読まない。 */
    public static void nightBefore(final ServerLevel level) {
        nightTime = timeListening() ? level.getDayTime() : Long.MIN_VALUE;
        nightWake = true;
    }

    /**
     * TimeSkipEvent(NIGHT_SKIP)。vanilla が朝へ動かしたあと。
     * 取り消されたら時刻を戻して、起こさないことにする。
     */
    public static void nightSkipped(final ServerLevel level) {
        if (nightTime == Long.MIN_VALUE) {
            return;
        }

        final long before = nightTime;
        nightTime = Long.MIN_VALUE;
        final long after = level.getDayTime();
        final TimeSkipEvent event = timeSkip(level.getWorld(), TimeSkipEvent.SkipReason.NIGHT_SKIP, after - before);

        if (event.isCancelled()) {
            level.setDayTime(before);
            nightWake = false;

            return;
        }

        if (before + event.getSkipAmount() != after) {
            level.setDayTime(before + event.getSkipAmount());
        }
    }

    /** 起こしてよいか。1 度読んだら true に戻る。 */
    public static boolean shouldWake() {
        final boolean wake = nightWake;
        nightWake = true;

        return wake;
    }


    // ------------------------------------------------------------ 世界



    private static final java.util.Set<MapId> initializedMaps = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * BlockDestroyEvent。壊す効果の前。登録が無ければ null を返し、呼ぶ側は vanilla のまま進む。
     */
    public static com.destroystokyo.paper.event.block.BlockDestroyEvent blockDestroy(
            final Level level, final BlockPos pos, final BlockState state, final FluidState fluid, final boolean drop) {
        if (!listening(com.destroystokyo.paper.event.block.BlockDestroyEvent.getHandlerList())) {
            return null;
        }

        final int xp = state.getBlock().getExpDrop(state, (ServerLevel) level, pos, ItemStack.EMPTY, true);
        final com.destroystokyo.paper.event.block.BlockDestroyEvent event = new com.destroystokyo.paper.event.block.BlockDestroyEvent(
                CraftBlock.at(level, pos), org.bukkit.craftbukkit.block.data.CraftBlockData.fromData(fluid.createLegacyBlock()), org.bukkit.craftbukkit.block.data.CraftBlockData.fromData(state), xp, drop);
        event.callEvent();

        return event;
    }

    /** 壊す効果を、イベントの値で出す(vanilla の行の代わり)。 */
    public static void destroyEffect(final Level level, final BlockPos pos, final BlockState state,
                                     final com.destroystokyo.paper.event.block.BlockDestroyEvent event) {
        if (!event.playEffect() || state.getBlock() instanceof net.minecraft.world.level.block.BaseFireBlock) {
            return;
        }

        final BlockState effect = ((org.bukkit.craftbukkit.block.data.CraftBlockData) event.getEffectBlock()).getState();
        level.levelEvent(net.minecraft.world.level.block.LevelEvent.PARTICLES_DESTROY_BLOCK, pos,
                net.minecraft.world.level.block.Block.getId(effect));
    }

    /**
     * PreCreatureSpawnEvent(自然湧き)。位置の判定の前。取り消しはこの位置を諦める。
     * {@code shouldAbortSpawn} は控えて、判定のあとで {@link #takeSpawnAbort} が読む。
     */
    public static boolean preCreatureSpawn(final ServerLevel level, final BlockPos pos, final EntityType<?> type) {
        spawnAbort = false;

        if (!listening(com.destroystokyo.paper.event.entity.PreCreatureSpawnEvent.getHandlerList())) {
            return true;
        }

        final com.destroystokyo.paper.event.entity.PreCreatureSpawnEvent event = new com.destroystokyo.paper.event.entity.PreCreatureSpawnEvent(
                CraftLocation.toBukkit(pos, level),
                org.bukkit.craftbukkit.entity.CraftEntityType.minecraftToBukkit(type),
                org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.NATURAL);
        final boolean allowed = event.callEvent();
        spawnAbort = event.shouldAbortSpawn();

        return allowed;
    }

    private static boolean spawnAbort;

    /**
     * 直前の PreCreatureSpawnEvent が「この回の湧きごとやめる」と言ったか。Paper は判定の
     * 返り値を enum にして呼び出し側で見るが、vanilla の返り値は真偽値なので控えで運ぶ。
     */
    public static boolean takeSpawnAbort() {
        final boolean abort = spawnAbort;
        spawnAbort = false;

        return abort;
    }



    /** DragonEggFormEvent に登録が無いか(無ければ vanilla がそのまま置く)。 */
    public static boolean silentDragonEgg() {
        return !listening(io.papermc.paper.event.block.DragonEggFormEvent.getHandlerList());
    }


    // ------------------------------------------------------------ 世界の境界

    private static boolean borderApplying;






    // ------------------------------------------------------------ ログイン・設定フェーズ




    /** ProfileWhitelistVerifyEvent に登録が無いか。 */
    public static boolean silentWhitelist() {
        return !listening(com.destroystokyo.paper.event.profile.ProfileWhitelistVerifyEvent.getHandlerList());
    }


    private static Component whitelistKick;

    /**
     * ホワイトリストで弾くときの文。プラグインが差し替えていなければ null(vanilla の文を使う)。
     * 直前の {@link #whitelistVerify} が置いたものを取り出して消す。
     */
    public static Component whitelistKickMessage() {
        final Component message = whitelistKick;
        whitelistKick = null;

        return message;
    }



    // PlayerCodeOfConductSendEvent は 26.x で入った Paper のイベントで、1.21.11 の API には無い。

    // ------------------------------------------------------------ プレイヤーの packet




    private static boolean swapCancelled;


    public static boolean swapHandsCancelled() {
        final boolean cancelled = swapCancelled;
        swapCancelled = false;

        return cancelled;
    }

    /** 本を書き換える前の控え。登録が無ければ null。 */
    public static ItemStack bookBefore(final ItemStack book) {
        if (!listening(org.bukkit.event.player.PlayerEditBookEvent.getHandlerList())) {
            return null;
        }

        return book.copy();
    }


    // ------------------------------------------------------------ スポーン地点

    private static ServerPlayer spawnPlayer;
    private static com.destroystokyo.paper.event.player.PlayerSetSpawnEvent.Cause spawnCause;
    private static boolean settingSpawn;

    /** 次の {@code setRespawnPosition} の理由を置く。 */
    public static void spawnCause(final ServerPlayer player, final com.destroystokyo.paper.event.player.PlayerSetSpawnEvent.Cause cause) {
        if (!listening(com.destroystokyo.paper.event.player.PlayerSetSpawnEvent.getHandlerList())
                && !listening(org.bukkit.event.player.PlayerSpawnChangeEvent.getHandlerList())) {
            return;
        }

        spawnPlayer = player;
        spawnCause = cause;
    }


    // ------------------------------------------------------------ ブロックを壊す



    // ------------------------------------------------------------ 食事・盾・エンチャント

    /**
     * FoodLevelChangeEvent(満腹の効果)。vanilla が食べさせる前。
     * 値が差し替えられていたらその差でここで食べさせて false。
     */
    public static boolean saturation(final net.minecraft.world.entity.player.Player player, final int amplification) {
        final int old = player.getFoodData().getFoodLevel();
        final org.bukkit.event.entity.FoodLevelChangeEvent event = ShifuEvents.foodLevelChange(player, amplification + 1 + old);

        if (event == null) {
            return true;
        }

        if (event.isCancelled()) {
            return false;
        }

        if (event.getFoodLevel() == amplification + 1 + old) {
            return true;
        }

        player.getFoodData().eat(event.getFoodLevel() - old, 1.0F);

        return false;
    }

    /**
     * FoodLevelChangeEvent(食べ物を食べ終えたとき)。vanilla が満腹度を足す直前。
     * 値が差し替えられていたら、その差と元の saturation でここで足して false。
     *
     * 読んだ位置: paper-server patches/sources/net/minecraft/world/food/FoodProperties.java.patch
     */
    public static boolean eatFood(final net.minecraft.world.entity.player.Player player,
                                  final net.minecraft.world.food.FoodProperties properties, final ItemStack stack) {
        if (!listening(org.bukkit.event.entity.FoodLevelChangeEvent.getHandlerList())) {
            return true;
        }

        final int old = player.getFoodData().getFoodLevel();
        final org.bukkit.event.entity.FoodLevelChangeEvent event = CraftEventFactory.callFoodLevelChangeEvent(
                player, properties.nutrition() + old, stack);

        if (event.isCancelled()) {
            return false;
        }

        if (event.getFoodLevel() == properties.nutrition() + old) {
            return true;
        }

        player.getFoodData().shifuAdd(event.getFoodLevel() - old, properties.saturation());

        return false;
    }

    private static LivingEntity shieldAttacker;

    /** 盾を無効にする攻撃者を置く({@code Player.blockUsingItem} から)。 */
    public static void shieldAttacker(final LivingEntity attacker) {
        if (!listening(io.papermc.paper.event.player.PlayerShieldDisableEvent.getHandlerList())) {
            return;
        }

        shieldAttacker = attacker;
    }


    // EntityLungeEvent は 26.x で入った Paper のイベントで、1.21.11 の API には無い。


    // ------------------------------------------------------------ GS4 query

    public static boolean silentQuery() {
        return !listening(com.destroystokyo.paper.event.server.GS4QueryEvent.getHandlerList());
    }

    private static com.destroystokyo.paper.event.server.GS4QueryEvent.QueryResponse query(
            final com.destroystokyo.paper.event.server.GS4QueryEvent.QueryType type, final DatagramPacket packet,
            final com.destroystokyo.paper.event.server.GS4QueryEvent.QueryResponse response) {
        final com.destroystokyo.paper.event.server.GS4QueryEvent event = new com.destroystokyo.paper.event.server.GS4QueryEvent(
                type, packet.getAddress(), response);
        event.callEvent();

        return event.getResponse();
    }

    /** GS4QueryEvent(BASIC)。vanilla の 7 行の書き出しの代わり。 */
    public static void queryBasic(final net.minecraft.server.rcon.NetworkDataOutputStream dos, final DatagramPacket packet,
                                  final String motd, final String map, final int players, final int maxPlayers,
                                  final int port, final String hostIp, final String gameVersion) throws java.io.IOException {
        final com.destroystokyo.paper.event.server.GS4QueryEvent.QueryResponse response = query(
                com.destroystokyo.paper.event.server.GS4QueryEvent.QueryType.BASIC, packet,
                com.destroystokyo.paper.event.server.GS4QueryEvent.QueryResponse.builder()
                        .motd(motd).map(map).currentPlayers(players).maxPlayers(maxPlayers).port(port).hostname(hostIp)
                        .gameVersion(gameVersion)
                        .serverVersion(org.bukkit.Bukkit.getServer().getName() + " on " + org.bukkit.Bukkit.getServer().getBukkitVersion())
                        .build());
        dos.writeString(response.getMotd());
        dos.writeString("SMP");
        dos.writeString(response.getMap());
        dos.writeString(Integer.toString(response.getCurrentPlayers()));
        dos.writeString(Integer.toString(response.getMaxPlayers()));
        dos.writeShort((short) response.getPort());
        dos.writeString(response.getHostname());
    }

    /** GS4QueryEvent(FULL)。vanilla の 20 行の書き出しの代わり。プレイヤー名の列は vanilla のまま。 */
    public static void queryFull(final net.minecraft.server.rcon.NetworkDataOutputStream dos, final DatagramPacket packet,
                                 final String motd, final String gameVersion, final String map, final int players,
                                 final int maxPlayers, final int port, final String hostIp,
                                 final String[] playerNames) throws java.io.IOException {
        List<com.destroystokyo.paper.event.server.GS4QueryEvent.QueryResponse.PluginInformation> plugins = new ArrayList<>();

        if (((org.bukkit.craftbukkit.CraftServer) org.bukkit.Bukkit.getServer()).getQueryPlugins()) {
            for (final org.bukkit.plugin.Plugin plugin : org.bukkit.Bukkit.getPluginManager().getPlugins()) {
                plugins.add(com.destroystokyo.paper.event.server.GS4QueryEvent.QueryResponse.PluginInformation.of(
                        plugin.getName(), plugin.getDescription().getVersion()));
            }
        }

        final com.destroystokyo.paper.event.server.GS4QueryEvent.QueryResponse response = query(
                com.destroystokyo.paper.event.server.GS4QueryEvent.QueryType.FULL, packet,
                com.destroystokyo.paper.event.server.GS4QueryEvent.QueryResponse.builder()
                        .motd(motd).map(map).currentPlayers(players).maxPlayers(maxPlayers).port(port).hostname(hostIp)
                        .plugins(plugins).players(java.util.Arrays.asList(playerNames)).gameVersion(gameVersion)
                        .serverVersion(org.bukkit.Bukkit.getServer().getName() + " on " + org.bukkit.Bukkit.getServer().getBukkitVersion())
                        .build());
        final StringBuilder pluginsText = new StringBuilder(response.getServerVersion());

        if (!response.getPlugins().isEmpty()) {
            pluginsText.append(": ");
            final Iterator<com.destroystokyo.paper.event.server.GS4QueryEvent.QueryResponse.PluginInformation> iterator = response.getPlugins().iterator();

            while (iterator.hasNext()) {
                final com.destroystokyo.paper.event.server.GS4QueryEvent.QueryResponse.PluginInformation info = iterator.next();
                pluginsText.append(info.getName());

                if (info.getVersion() != null) {
                    pluginsText.append(' ').append(info.getVersion().replace(";", ","));
                }

                if (iterator.hasNext()) {
                    pluginsText.append("; ");
                }
            }
        }

        dos.writeString("hostname");
        dos.writeString(response.getMotd());
        dos.writeString("gametype");
        dos.writeString("SMP");
        dos.writeString("game_id");
        dos.writeString("MINECRAFT");
        dos.writeString("version");
        dos.writeString(response.getGameVersion());
        dos.writeString("plugins");
        dos.writeString(pluginsText.toString());
        dos.writeString("map");
        dos.writeString(response.getMap());
        dos.writeString("numplayers");
        dos.writeString(Integer.toString(response.getCurrentPlayers()));
        dos.writeString("maxplayers");
        dos.writeString(Integer.toString(response.getMaxPlayers()));
        dos.writeString("hostport");
        dos.writeString(Integer.toString(response.getPort()));
        dos.writeString("hostip");
        dos.writeString(response.getHostname());
        queryPlayerNames = response.getPlayers().toArray(new String[0]);
    }

    private static String[] queryPlayerNames;

    /**
     * 直前の FULL の応答でプラグインが載せたプレイヤー名の列。vanilla はこのあと
     * {@code getPlayerNames()} の列を書き出すので、その局所変数へ入れ替える。
     * 差し替えが無ければ渡された列をそのまま返す。
     */
    public static String[] queryPlayers(final String[] vanilla) {
        final String[] names = queryPlayerNames;
        queryPlayerNames = null;

        return names == null ? vanilla : names;
    }
}
