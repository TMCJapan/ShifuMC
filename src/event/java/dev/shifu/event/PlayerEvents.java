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

    /** WorldSaveEvent。保存の頭。 */
    public static void worldSave(final ServerLevel level) {
        if (!listening(org.bukkit.event.world.WorldSaveEvent.getHandlerList())) {
            return;
        }

        new org.bukkit.event.world.WorldSaveEvent(level.getWorld()).callEvent();
    }

    /** SpawnChangeEvent の前。登録が無ければ控えない。 */
    public static org.bukkit.Location spawnBefore(final ServerLevel level) {
        return listening(org.bukkit.event.world.SpawnChangeEvent.getHandlerList())
                ? level.getWorld().getSpawnLocation()
                : null;
    }

    /** SpawnChangeEvent。vanilla が書いたあと。位置が同じなら出さない(Paper と同じ)。 */
    public static void spawnChanged(final ServerLevel level, final org.bukkit.Location previous) {
        if (previous == null) {
            return;
        }

        final org.bukkit.Location now = level.getWorld().getSpawnLocation();

        if (now.getBlockX() == previous.getBlockX() && now.getBlockY() == previous.getBlockY()
                && now.getBlockZ() == previous.getBlockZ()
                && now.getYaw() == previous.getYaw() && now.getPitch() == previous.getPitch()) {
            return;
        }

        new org.bukkit.event.world.SpawnChangeEvent(level.getWorld(), previous).callEvent();
    }

    /**
     * MapInitializeEvent。{@code setMapData} で置く前。
     * 1.20.6 の {@code MapItemSavedData.mapView} は欄の初期化子で作られるので、ここでは作らない。
     */
    public static void mapInitialize(final net.minecraft.world.level.saveddata.maps.MapId id,
                                     final net.minecraft.world.level.saveddata.maps.MapItemSavedData data) {
        if (!listening(org.bukkit.event.server.MapInitializeEvent.getHandlerList())
                || !initializedMaps.add(id)) {
            return;
        }

        data.id = id;
        new org.bukkit.event.server.MapInitializeEvent(data.mapView).callEvent();
    }


    /**
     * BlockDamageEvent。壊し始めの判定が済んだあと。
     *
     * @return 壊し始めてよいか。即時破壊にされたら vanilla の insta mine へ回す
     */
    public static org.bukkit.event.block.BlockDamageEvent blockDamage(
            final ServerPlayer player, final net.minecraft.core.BlockPos pos,
            final net.minecraft.core.Direction face, final boolean insta) {
        if (!listening(org.bukkit.event.block.BlockDamageEvent.getHandlerList())) {
            return null;
        }

        return org.bukkit.craftbukkit.event.CraftEventFactory.callBlockDamageEvent(
                player, pos, face, player.getInventory().getSelected(), insta);
    }

    /** BlockDamageAbortEvent。壊すのをやめたとき。 */
    public static void blockDamageAbort(final ServerPlayer player, final net.minecraft.core.BlockPos pos) {
        if (!listening(org.bukkit.event.block.BlockDamageAbortEvent.getHandlerList())) {
            return;
        }

        org.bukkit.craftbukkit.event.CraftEventFactory.callBlockDamageAbortEvent(
                player, pos, player.getInventory().getSelected());
    }


    /**
     * PlayerBedLeaveEvent。ベッドから出る直前。
     *
     * @return 出てよいか
     */
    public static boolean bedLeave(final ServerPlayer player) {
        if (!ShifuEvents.listening(org.bukkit.event.player.PlayerBedLeaveEvent.getHandlerList())) {
            return true;
        }

        final org.bukkit.block.Block bed = player.getSleepingPos()
                .<org.bukkit.block.Block>map(pos -> org.bukkit.craftbukkit.block.CraftBlock.at(player.level(), pos))
                .orElseGet(() -> player.getBukkitEntity().getLocation().getBlock());

        return new org.bukkit.event.player.PlayerBedLeaveEvent(
                player.getBukkitEntity(), bed, true).callEvent();
    }

    /**
     * PlayerChangedMainHandEvent と PlayerLocaleChangeEvent(Bukkit と Paper の両方)。
     * {@code updateOptions} が値を書き換える前。
     *
     * <p><b>未対応:</b> {@code PlayerClientOptionsChangeEvent}。構築子が取る
     * ClientOption の Map を作る Paper のメソッドが 1.20.6 の木に無い。
     */
    public static void clientOptions(final ServerPlayer player, final String oldLanguage,
                                     final net.minecraft.server.level.ClientInformation options) {
        if (player.getMainArm() != options.mainHand()
                && ShifuEvents.listening(org.bukkit.event.player.PlayerChangedMainHandEvent.getHandlerList())) {
            new org.bukkit.event.player.PlayerChangedMainHandEvent(player.getBukkitEntity(),
                    player.getMainArm() == net.minecraft.world.entity.HumanoidArm.LEFT
                            ? org.bukkit.inventory.MainHand.LEFT : org.bukkit.inventory.MainHand.RIGHT).callEvent();
        }

        if (oldLanguage != null && oldLanguage.equals(options.language())) {
            return;
        }

        if (ShifuEvents.listening(org.bukkit.event.player.PlayerLocaleChangeEvent.getHandlerList())) {
            new org.bukkit.event.player.PlayerLocaleChangeEvent(
                    player.getBukkitEntity(), options.language()).callEvent();
        }

        if (ShifuEvents.listening(com.destroystokyo.paper.event.player.PlayerLocaleChangeEvent.getHandlerList())) {
            new com.destroystokyo.paper.event.player.PlayerLocaleChangeEvent(
                    player.getBukkitEntity(), oldLanguage, options.language()).callEvent();
        }
    }

    /**
     * PlayerStartSpectatingEntityEvent と PlayerStopSpectatingEntityEvent。
     * 視点を移した直後。取り消されたら呼び出し元が元に戻す。
     *
     * @param from 前の視点
     * @param to   新しい視点。null は自分に戻す
     */
    public static boolean spectateChange(final ServerPlayer player, final net.minecraft.world.entity.Entity from,
                                         final net.minecraft.world.entity.Entity to) {
        if (to == null) {
            if (!ShifuEvents.listening(
                    com.destroystokyo.paper.event.player.PlayerStopSpectatingEntityEvent.getHandlerList())) {
                return true;
            }

            return new com.destroystokyo.paper.event.player.PlayerStopSpectatingEntityEvent(
                    player.getBukkitEntity(), from.getBukkitEntity()).callEvent();
        }

        if (!ShifuEvents.listening(
                com.destroystokyo.paper.event.player.PlayerStartSpectatingEntityEvent.getHandlerList())) {
            return true;
        }

        return new com.destroystokyo.paper.event.player.PlayerStartSpectatingEntityEvent(
                player.getBukkitEntity(), from.getBukkitEntity(), to.getBukkitEntity()).callEvent();
    }


    /**
     * PlayerArmSwingEvent(親は PlayerAnimationEvent)。腕を振る直前。
     *
     * <p>CraftBukkit はここで視線の先を引いて「空振り」を落としているが、
     * それは vanilla に無い判定なので入れていない。
     */
    public static boolean armSwing(final ServerPlayer player, final net.minecraft.world.InteractionHand hand) {
        if (!ShifuEvents.listening(org.bukkit.event.player.PlayerAnimationEvent.getHandlerList())) {
            return true;
        }

        return new io.papermc.paper.event.player.PlayerArmSwingEvent(player.getBukkitEntity(),
                org.bukkit.craftbukkit.CraftEquipmentSlot.getHand(hand)).callEvent();
    }

    /**
     * PlayerSwapHandItemsEvent。持ち替える直前。
     *
     * @return 出したイベント。登録が無ければ null
     */
    public static org.bukkit.event.player.PlayerSwapHandItemsEvent swapHandItems(final ServerPlayer player) {
        if (!ShifuEvents.listening(org.bukkit.event.player.PlayerSwapHandItemsEvent.getHandlerList())) {
            return null;
        }

        final org.bukkit.event.player.PlayerSwapHandItemsEvent event =
                new org.bukkit.event.player.PlayerSwapHandItemsEvent(player.getBukkitEntity(),
                        org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(
                                player.getItemInHand(net.minecraft.world.InteractionHand.OFF_HAND)).clone(),
                        org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(
                                player.getItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND)).clone());
        event.callEvent();

        return event;
    }

    /** 持ち替えたあと、プラグインが差し替えた物があれば入れ直す。 */
    public static void swapHandItemsApply(final ServerPlayer player,
                                          final org.bukkit.event.player.PlayerSwapHandItemsEvent event) {
        if (event == null) {
            return;
        }

        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(event.getMainHandItem()));
        player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND,
                org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(event.getOffHandItem()));
    }


    /**
     * PlayerHarvestBlockEvent。実を摘む直前。
     *
     * <p>落とす物は vanilla の行が決めるので、<b>{@code getItemsHarvested} の
     * 書き換えは使っていない。</b>
     */
    public static boolean harvestBlock(final net.minecraft.world.level.Level level,
                                       final BlockPos pos,
                                       final net.minecraft.world.entity.player.Player player,
                                       final net.minecraft.world.InteractionHand hand,
                                       final java.util.List<net.minecraft.world.item.ItemStack> drops) {
        if (!ShifuEvents.listening(org.bukkit.event.player.PlayerHarvestBlockEvent.getHandlerList())) {
            return true;
        }

        return !org.bukkit.craftbukkit.event.CraftEventFactory.callPlayerHarvestBlockEvent(
                level, pos, player, hand, drops).isCancelled();
    }

    /**
     * PlayerShearBlockEvent。ハサミで蜂の巣を切る直前。
     *
     * <p>落とす物は vanilla の行が決めるので、<b>{@code getDrops} の
     * 書き換えは使っていない。</b>
     */
    public static boolean shearBlock(final net.minecraft.world.level.Level level, final BlockPos pos,
                                     final net.minecraft.world.entity.player.Player player,
                                     final net.minecraft.world.InteractionHand hand,
                                     final net.minecraft.world.item.ItemStack tool,
                                     final net.minecraft.world.item.ItemStack drop) {
        if (!ShifuEvents.listening(io.papermc.paper.event.block.PlayerShearBlockEvent.getHandlerList())) {
            return true;
        }

        final java.util.List<org.bukkit.inventory.ItemStack> drops = new java.util.ArrayList<>();
        drops.add(org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(drop));

        return new io.papermc.paper.event.block.PlayerShearBlockEvent(
                (org.bukkit.entity.Player) player.getBukkitEntity(),
                org.bukkit.craftbukkit.block.CraftBlock.at(level, pos),
                org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(tool),
                org.bukkit.craftbukkit.CraftEquipmentSlot.getHand(hand), drops).callEvent();
    }

    /**
     * PlayerBucketEntityEvent(魚なら PlayerBucketFishEvent)。バケツに入れる直前。
     *
     * <p>差し替えたバケツ({@code setEntityBucket})は vanilla の行が持つので使っていない。
     */
    public static boolean bucketEntity(final net.minecraft.world.entity.LivingEntity entity,
                                       final net.minecraft.world.entity.player.Player player,
                                       final net.minecraft.world.item.ItemStack bucket,
                                       final net.minecraft.world.item.ItemStack filled,
                                       final net.minecraft.world.InteractionHand hand) {
        if (!ShifuEvents.listening(org.bukkit.event.player.PlayerBucketEntityEvent.getHandlerList())) {
            return true;
        }

        return !org.bukkit.craftbukkit.event.CraftEventFactory.callPlayerFishBucketEvent(
                entity, player, bucket, filled, hand).isCancelled();
    }


    /**
     * PlayerItemCooldownEvent。クールダウンを入れる直前。
     *
     * @return 入れる長さ。取り消されたら null
     */
    public static Integer itemCooldown(final net.minecraft.world.item.ItemCooldowns cooldowns,
                                       final net.minecraft.world.item.Item item, final int duration) {
        if (!(cooldowns instanceof net.minecraft.world.item.ServerItemCooldowns server)
                || !ShifuEvents.listening(io.papermc.paper.event.player.PlayerItemCooldownEvent.getHandlerList())) {
            return duration;
        }

        final io.papermc.paper.event.player.PlayerItemCooldownEvent event =
                new io.papermc.paper.event.player.PlayerItemCooldownEvent(server.player.getBukkitEntity(),
                        org.bukkit.craftbukkit.inventory.CraftItemType.minecraftToBukkit(item), duration);

        return event.callEvent() ? event.getCooldown() : null;
    }

    /**
     * PlayerNameEntityEvent。名札で名前を付ける直前。
     *
     * <p>差し替えた相手({@code setEntity})と名前({@code setName})は
     * vanilla の行が持つので使っていない。
     */
    public static boolean nameEntity(final net.minecraft.world.entity.player.Player user,
                                     final net.minecraft.world.entity.LivingEntity entity,
                                     final Component name) {
        if (!(user instanceof ServerPlayer player)
                || !ShifuEvents.listening(io.papermc.paper.event.player.PlayerNameEntityEvent.getHandlerList())) {
            return true;
        }

        return new io.papermc.paper.event.player.PlayerNameEntityEvent(player.getBukkitEntity(),
                entity.getBukkitLivingEntity(), PaperAdventure.asAdventure(name), true).callEvent();
    }

    /** PlayerElytraBoostEvent。エリトラ中に花火を使う直前。 */
    public static boolean elytraBoost(final net.minecraft.world.entity.player.Player user,
                                      final net.minecraft.world.item.ItemStack stack,
                                      final net.minecraft.world.entity.projectile.FireworkRocketEntity firework,
                                      final net.minecraft.world.InteractionHand hand) {
        if (!ShifuEvents.listening(
                com.destroystokyo.paper.event.player.PlayerElytraBoostEvent.getHandlerList())) {
            return true;
        }

        return new com.destroystokyo.paper.event.player.PlayerElytraBoostEvent(
                (org.bukkit.entity.Player) user.getBukkitEntity(),
                org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(stack),
                (org.bukkit.entity.Firework) firework.getBukkitEntity(),
                org.bukkit.craftbukkit.CraftEquipmentSlot.getHand(hand)).callEvent();
    }

    /** PlayerStopUsingItemEvent。使うのをやめた直後。 */
    public static void stopUsingItem(final net.minecraft.world.entity.LivingEntity entity) {
        if (!(entity instanceof ServerPlayer player)
                || !ShifuEvents.listening(io.papermc.paper.event.player.PlayerStopUsingItemEvent.getHandlerList())) {
            return;
        }

        new io.papermc.paper.event.player.PlayerStopUsingItemEvent(player.getBukkitEntity(),
                org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(player.getUseItem()),
                player.getTicksUsingItem()).callEvent();
    }


    /**
     * PlayerBedFailEnterEvent。寝られなかったとき。
     *
     * <p>爆発するかどうか({@code getWillExplode})は CraftBukkit が足した分岐で、
     * vanilla のこの経路には無い。渡しているのは取り消しだけ。
     */
    public static boolean bedFailEnter(final net.minecraft.world.entity.player.Player player,
                                       final net.minecraft.world.entity.player.Player.BedSleepingProblem reason,
                                       final net.minecraft.world.level.Level level, final BlockPos pos) {
        if (!(player instanceof ServerPlayer serverPlayer)
                || !ShifuEvents.listening(io.papermc.paper.event.player.PlayerBedFailEnterEvent.getHandlerList())) {
            return true;
        }

        return new io.papermc.paper.event.player.PlayerBedFailEnterEvent(serverPlayer.getBukkitEntity(),
                io.papermc.paper.event.player.PlayerBedFailEnterEvent.FailReason.values()[reason.ordinal()],
                org.bukkit.craftbukkit.block.CraftBlock.at(level, pos),
                !level.dimensionType().bedWorks(),
                reason.getMessage() == null ? null : PaperAdventure.asAdventure(reason.getMessage())).callEvent();
    }

}
