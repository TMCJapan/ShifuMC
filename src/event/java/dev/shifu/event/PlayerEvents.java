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
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.rcon.RconConsoleSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantedItemInUse;
import net.minecraft.world.item.enchantment.LevelBasedValue;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.Level;
import net.minecraft.server.ServerLinks;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
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
     * UnknownCommandEvent。構文の失敗を送る代わりに発火し、イベントの文を送る。
     * Paper は生の失敗文と位置の文を 1 つにまとめて渡すので同じにする。
     * 位置の文(context)は vanilla が組み立てたものを受け取り、無い経路では null。
     *
     * 読んだ位置: paper-server patches/sources/net/minecraft/commands/Commands.java.patch(UnknownCommandEvent)
     */
    public static void unknownCommand(final CommandSourceStack sender, final String commandString,
                                      final CommandSyntaxException e, final MutableComponent context) {
        net.kyori.adventure.text.Component message = PaperAdventure.asAdventure(ComponentUtils.fromMessage(e.getRawMessage()));

        if (context != null) {
            message = message.append(net.kyori.adventure.text.Component.newline()).append(PaperAdventure.asAdventure(context));
        }

        final org.bukkit.event.command.UnknownCommandEvent event = new org.bukkit.event.command.UnknownCommandEvent(
                dev.shifu.command.ApiSource.wrap(sender), commandString,
                org.spigotmc.SpigotConfig.unknownCommandMessage.isEmpty() ? null : message);
        org.bukkit.Bukkit.getServer().getPluginManager().callEvent(event);

        if (event.message() != null) {
            sender.sendFailure(PaperAdventure.asVanilla(event.message()));
        }
    }

    /**
     * AsyncPlayerSendCommandsEvent と PlayerCommandSendEvent。木を組み終えて packet を送る直前。
     * Paper は別スレッドで組んで 2 回発火するが、vanilla は同期なので 1 回(hasFiredAsync = false)。
     * 外された名前は木から消す。
     *
     * 読んだ位置: paper-server patches/sources/net/minecraft/commands/Commands.java.patch(PlayerCommandSendEvent)
     */
    public static void commandSend(final ServerPlayer player, final RootCommandNode<CommandSourceStack> root) {
        if (!listening(org.bukkit.event.player.PlayerCommandSendEvent.getHandlerList())
                && !listening(com.destroystokyo.paper.event.brigadier.AsyncPlayerSendCommandsEvent.getHandlerList())) {
            return;
        }

        final Collection<String> names = new LinkedHashSet<>();

        for (final CommandNode<CommandSourceStack> node : root.getChildren()) {
            names.add(node.getName());
        }

        new com.destroystokyo.paper.event.brigadier.AsyncPlayerSendCommandsEvent<io.papermc.paper.command.brigadier.CommandSourceStack>(player.getBukkitEntity(), (com.mojang.brigadier.tree.RootCommandNode) (Object) root, false).callEvent();
        final org.bukkit.event.player.PlayerCommandSendEvent event = new org.bukkit.event.player.PlayerCommandSendEvent(
                player.getBukkitEntity(), new LinkedHashSet<>(names));
        event.callEvent();

        for (final String name : names) {
            if (!event.getCommands().contains(name)) {
                root.removeCommand(name);
            }
        }
    }

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
     * 読んだ位置: paper-server patches/sources/net/minecraft/server/players/SleepStatus.java.patch
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

    /**
     * AsyncPlayerSendSuggestionsEvent。補完候補をクライアントへ送る直前。
     *
     * 候補が空のときは既定で取り消し済みにして渡す(Paper と同じ)。登録が無ければ
     * 発火せず、渡された候補をそのまま返すので vanilla の送信になる。
     *
     * @return 送る候補。差し替えられていなければ引数と同じもの。取り消されたら null
     *
     * 読んだ位置: paper-server patches/sources/net/minecraft/server/network/ServerGamePacketListenerImpl.java.patch(AsyncPlayerSendSuggestionsEvent)
     */
    public static com.mojang.brigadier.suggestion.Suggestions sendSuggestions(
            final ServerPlayer player, final String buffer,
            final com.mojang.brigadier.suggestion.Suggestions suggestions) {
        if (!listening(com.destroystokyo.paper.event.brigadier.AsyncPlayerSendSuggestionsEvent.getHandlerList())) {
            return suggestions;
        }

        final com.destroystokyo.paper.event.brigadier.AsyncPlayerSendSuggestionsEvent event =
                new com.destroystokyo.paper.event.brigadier.AsyncPlayerSendSuggestionsEvent(
                        player.getBukkitEntity(), suggestions, buffer);
        event.setCancelled(suggestions.isEmpty());

        return event.callEvent() ? event.getSuggestions() : null;
    }

    /**
     * PlayerCommandPreprocessEvent(署名の無いコマンド)。解析の前。
     * 取り消しは効く。文の差し替えは hand の {@code shifuPerformUnsignedChatCommand} で
     * 差し替えた文を改めて通す(そのときはこの発火を通らない)。
     *
     * 読んだ位置: paper-server patches/sources/net/minecraft/server/network/ServerGamePacketListenerImpl.java.patch(PlayerCommandPreprocessEvent)
     */
    private static boolean rewritingCommand;

    public static boolean commandPreprocess(final ServerGamePacketListenerImpl connection, final String command) {
        if (rewritingCommand || !listening(org.bukkit.event.player.PlayerCommandPreprocessEvent.getHandlerList())) {
            return true;
        }

        final org.bukkit.event.player.PlayerCommandPreprocessEvent event = new org.bukkit.event.player.PlayerCommandPreprocessEvent(
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

    /**
     * PlayerCommandPreprocessEvent(署名付きコマンド)。Paper も文の差し替えは解析に使わない
     * (署名が合わなくなる)ので、取り消しだけ。
     */
    public static boolean commandPreprocessSigned(final ServerGamePacketListenerImpl connection, final String command) {
        if (!listening(org.bukkit.event.player.PlayerCommandPreprocessEvent.getHandlerList())) {
            return true;
        }

        return new org.bukkit.event.player.PlayerCommandPreprocessEvent(
                connection.player.getBukkitEntity(), "/" + command,
                new org.bukkit.craftbukkit.util.LazyPlayerSet(connection.player.level().getServer())).callEvent();
    }

    /**
     * ServerCommandEvent(コマンドブロック)。実行の直前。
     *
     * @return 実行する文。取り消されたら null。変えられていなければ渡した参照そのもの
     */
    public static String commandBlock(final CommandSourceStack source, final String command) {
        if (!listening(org.bukkit.event.server.ServerCommandEvent.getHandlerList())) {
            return command;
        }

        final String trimmed = net.minecraft.commands.Commands.trimOptionalPrefix(command);
        final org.bukkit.event.server.ServerCommandEvent event = new org.bukkit.event.server.ServerCommandEvent(
                source.getBukkitSender(), trimmed);

        if (!event.callEvent()) {
            return null;
        }

        return event.getCommand().equals(trimmed) ? command : event.getCommand();
    }

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

    /**
     * WorldDifficultyChangeEvent。ロックの判定を通ったあと、書く前。取り消しは無い
     * (イベントが Cancellable ではない)。vanilla の難易度はサーバー全体なので世界はオーバーワールド。
     * 送り主は {@code /difficulty} が呼ぶ直前に置いたもの。他の経路では null。
     */
    public static void difficultyChange(final MinecraftServer server, final Difficulty difficulty) {
        final io.papermc.paper.command.brigadier.CommandSourceStack source = difficultySource;
        difficultySource = null;

        if (!listening(io.papermc.paper.event.world.WorldDifficultyChangeEvent.getHandlerList())) {
            return;
        }

        new io.papermc.paper.event.world.WorldDifficultyChangeEvent(
                server.overworld().getWorld(), source, org.bukkit.craftbukkit.util.CraftDifficulty.toBukkit(difficulty)).callEvent();
    }

    private static io.papermc.paper.command.brigadier.CommandSourceStack difficultySource;

    /** 次の難易度の変更の送り主を置く({@code /difficulty} から)。 */
    public static void difficultySource(final CommandSourceStack source) {
        if (!listening(io.papermc.paper.event.world.WorldDifficultyChangeEvent.getHandlerList())) {
            return;
        }

        difficultySource = dev.shifu.command.ApiSource.wrap(source);
    }

    /**
     * WorldGameRuleChangeEvent。書く直前。取り消されたら書かない。値が差し替えられていたら
     * ここで書いて false を返す(vanilla の行は飛ばす。メッセージと返り値は元の値のまま)。
     *
     * 読んだ位置: CraftEventFactory.handleGameRuleSet
     */
    public static <T> boolean gameRuleSet(final GameRule<T> rule, final T value, final ServerLevel level,
                                          final org.bukkit.command.CommandSender sender) {
        if (!listening(io.papermc.paper.event.world.WorldGameRuleChangeEvent.getHandlerList())) {
            return true;
        }

        final String text = rule.serialize(value);
        final io.papermc.paper.event.world.PaperWorldGameRuleChangeEvent event = new io.papermc.paper.event.world.PaperWorldGameRuleChangeEvent(
                level.getWorld(), sender, org.bukkit.craftbukkit.CraftGameRule.minecraftToBukkit(rule), text);

        if (!event.callEvent()) {
            return false;
        }

        if (event.getValue().equals(text)) {
            return true;
        }

        level.getGameRules().set(rule, rule.deserialize(event.getValue()).getOrThrow(), level);

        return false;
    }

    public static <T> boolean gameRuleSet(final GameRule<T> rule, final T value, final CommandSourceStack source) {
        if (!listening(io.papermc.paper.event.world.WorldGameRuleChangeEvent.getHandlerList())) {
            return true;
        }

        return gameRuleSet(rule, value, source.getLevel(), source.getBukkitSender());
    }

    public static <T> boolean gameRuleSet(final GameRule<T> rule, final T value, final ServerPlayer player) {
        if (!listening(io.papermc.paper.event.world.WorldGameRuleChangeEvent.getHandlerList())) {
            return true;
        }

        return gameRuleSet(rule, value, player.level(), player.getBukkitEntity());
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
     * TimeSkipEvent(/time set)。書く直前。量が差し替えられていたらここで書いて false。
     *
     * 読んだ位置: paper-server patches/sources/net/minecraft/server/commands/TimeCommand.java.patch
     */
    public static boolean timeSet(final ServerLevel level, final int time) {
        if (!timeListening()) {
            return true;
        }

        final long current = level.getDayTime();
        final TimeSkipEvent event = timeSkip(level.getWorld(), TimeSkipEvent.SkipReason.COMMAND, time - current);

        if (event.isCancelled()) {
            return false;
        }

        if (current + event.getSkipAmount() == time) {
            return true;
        }

        level.setDayTime(current + event.getSkipAmount());

        return false;
    }

    /** TimeSkipEvent(/time add)。 */
    public static boolean timeAdd(final ServerLevel level, final int amount) {
        if (!timeListening()) {
            return true;
        }

        final TimeSkipEvent event = timeSkip(level.getWorld(), TimeSkipEvent.SkipReason.COMMAND, amount);

        if (event.isCancelled()) {
            return false;
        }

        if (event.getSkipAmount() == amount) {
            return true;
        }

        level.setDayTime(level.getDayTime() + event.getSkipAmount());

        return false;
    }

    /** 夜を飛ばす前の時刻。登録が無ければ読まずに 0。 */
    public static long nightBefore(final ServerLevel level) {
        return timeListening() ? level.getDayTime() : 0L;
    }

    /**
     * TimeSkipEvent(NIGHT_SKIP)。vanilla が朝へ動かしたあと。取り消されたら戻して、起こさない。
     *
     * @return 起こしてよいか
     */
    public static boolean nightSkipped(final ServerLevel level, final long before) {
        if (!timeListening()) {
            return true;
        }

        final long after = level.getDayTime();
        final TimeSkipEvent event = timeSkip(level.getWorld(), TimeSkipEvent.SkipReason.NIGHT_SKIP, after - before);

        if (event.isCancelled()) {
            level.setDayTime(before);

            return false;
        }

        if (before + event.getSkipAmount() != after) {
            level.setDayTime(before + event.getSkipAmount());
        }

        return true;
    }

    // ------------------------------------------------------------ 世界

    /** WorldSaveEvent。保存の頭。 */
    public static void worldSave(final ServerLevel level) {
        if (!listening(org.bukkit.event.world.WorldSaveEvent.getHandlerList())) {
            return;
        }

        new org.bukkit.event.world.WorldSaveEvent(level.getWorld()).callEvent();
    }

    /**
     * MapInitializeEvent。{@code setMapData} で置く前。
     * 地図の Bukkit 側({@code mapView})は誰も作らないので、無ければここで作る。
     */
    public static void mapInitialize(final MapId id, final MapItemSavedData data) {
        if (!listening(org.bukkit.event.server.MapInitializeEvent.getHandlerList())) {
            return;
        }

        initializedMaps.add(id);
        data.id = id;

        if (data.mapView == null) {
            data.mapView = new org.bukkit.craftbukkit.map.CraftMapView(data);
        }

        new org.bukkit.event.server.MapInitializeEvent(data.mapView).callEvent();
    }

    private static final java.util.Set<MapId> initializedMaps = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * MapInitializeEvent。{@code getMapData} で読み込んだとき。Paper と同じく、ディスクから
     * 初めて読んだ地図で 1 度だけ出す({@code setMapData} で出したものは出さない)。
     * 登録があるときだけ読むが、読みは貯め込みに当たるので vanilla の次の行と同じ物になる。
     *
     * 読んだ位置: paper-server patches/sources/net/minecraft/server/level/ServerLevel.java.patch(Call missing map initialize event)
     */
    public static void mapLoaded(final ServerLevel level, final MapId id) {
        if (!listening(org.bukkit.event.server.MapInitializeEvent.getHandlerList())) {
            return;
        }

        final MapItemSavedData data = level.getServer().getDataStorage().get(MapItemSavedData.type(id));

        if (data == null || !initializedMaps.add(id)) {
            return;
        }

        data.id = id;

        if (data.mapView == null) {
            data.mapView = new org.bukkit.craftbukkit.map.CraftMapView(data);
        }

        new org.bukkit.event.server.MapInitializeEvent(data.mapView).callEvent();
    }

    /** 変える前のスポーン。登録が無ければ null。 */
    public static Location spawnBefore(final ServerLevel level) {
        if (!listening(org.bukkit.event.world.SpawnChangeEvent.getHandlerList())) {
            return null;
        }

        return level.getWorld().getSpawnLocation();
    }

    /** SpawnChangeEvent。vanilla が書いたあと。位置が同じなら出さない(Paper と同じ)。 */
    public static void spawnChanged(final ServerLevel level, final Location previous) {
        if (previous == null) {
            return;
        }

        final Location now = level.getWorld().getSpawnLocation();

        if (now.getBlockX() == previous.getBlockX() && now.getBlockY() == previous.getBlockY() && now.getBlockZ() == previous.getBlockZ()
                && now.getYaw() == previous.getYaw() && now.getPitch() == previous.getPitch()) {
            return;
        }

        new org.bukkit.event.world.SpawnChangeEvent(level.getWorld(), previous).callEvent();
    }

    /**
     * ServerExceptionEvent。vanilla はエンティティの tick の例外でサーバーを落とす。
     * 落ちる前に知らせるだけで、落とす挙動は変えない(Paper は落とさずに捨てる)。
     */
    public static void entityException(final Entity entity, final Throwable t) {
        if (!listening(com.destroystokyo.paper.event.server.ServerExceptionEvent.getHandlerList())) {
            return;
        }

        final String message = String.format("Entity threw exception at %s:%s,%s,%s",
                entity.level().dimension().identifier(), entity.getX(), entity.getY(), entity.getZ());
        new com.destroystokyo.paper.event.server.ServerExceptionEvent(
                new com.destroystokyo.paper.exception.ServerInternalException(message, t)).callEvent();
    }

    /** ServerExceptionEvent。ブロックエンティティの tick の例外。 */
    public static void blockEntityException(final Level level, final BlockPos pos, final Throwable t) {
        if (!listening(com.destroystokyo.paper.event.server.ServerExceptionEvent.getHandlerList())) {
            return;
        }

        final String message = String.format("BlockEntity threw exception at %s:%s,%s,%s",
                level.dimension().identifier(), pos.getX(), pos.getY(), pos.getZ());
        new com.destroystokyo.paper.event.server.ServerExceptionEvent(
                new com.destroystokyo.paper.exception.ServerInternalException(message, t)).callEvent();
    }

    /**
     * BlockDestroyEvent。壊す効果の前。取り消しは効く。
     *
     * @return イベント。登録が無ければ null
     */
    public static com.destroystokyo.paper.event.block.BlockDestroyEvent blockDestroy(
            final Level level, final BlockPos pos, final BlockState state, final FluidState fluid, final boolean drop) {
        if (!listening(com.destroystokyo.paper.event.block.BlockDestroyEvent.getHandlerList())) {
            return null;
        }

        final int xp = state.getBlock().getExpDrop(state, (ServerLevel) level, pos, ItemStack.EMPTY, true);
        final com.destroystokyo.paper.event.block.BlockDestroyEvent event = new com.destroystokyo.paper.event.block.BlockDestroyEvent(
                CraftBlock.at(level, pos), fluid.createLegacyBlock().asBlockData(), state.asBlockData(), xp, drop);
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

    /**
     * PreSpawnerSpawnEvent。スポナーがエンティティを作る前。
     *
     * @return 0 = 続ける、1 = この 1 体を飛ばす、2 = このスポナーの残りも止める
     */
    public static int preSpawnerSpawn(final ServerLevel level, final Vec3 spawnPos, final EntityType<?> type, final BlockPos pos) {
        if (!listening(com.destroystokyo.paper.event.entity.PreSpawnerSpawnEvent.getHandlerList())) {
            return 0;
        }

        final com.destroystokyo.paper.event.entity.PreSpawnerSpawnEvent event = new com.destroystokyo.paper.event.entity.PreSpawnerSpawnEvent(
                CraftLocation.toBukkit(spawnPos, level),
                org.bukkit.craftbukkit.entity.CraftEntityType.minecraftToBukkit(type),
                CraftLocation.toBukkit(pos, level));

        if (event.callEvent()) {
            return 0;
        }

        return event.shouldAbortSpawn() ? 2 : 1;
    }

    /** SpawnerSpawnEvent。世界に入れる直前。 */
    public static boolean spawnerSpawn(final Entity entity, final BlockPos pos) {
        if (!listening(org.bukkit.event.entity.SpawnerSpawnEvent.getHandlerList())) {
            return true;
        }

        entity.spawnReason = org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.SPAWNER;

        return !CraftEventFactory.callSpawnerSpawnEvent(entity, pos).isCancelled();
    }

    /** DragonEggFormEvent に登録が無いか(無ければ vanilla がそのまま置く)。 */
    public static boolean silentDragonEgg() {
        return !listening(io.papermc.paper.event.block.DragonEggFormEvent.getHandlerList());
    }

    /**
     * DragonEggFormEvent。vanilla の「初回だけ卵を置く」の代わりに、Paper と同じく毎回発火する。
     * 2 回目以降は取り消し済みで出し、プラグインが戻せば置く。
     */
    public static void dragonEggForm(final EnderDragonFight fight, final ServerLevel level, final BlockPos origin, final boolean previouslyKilled) {
        final BlockPos eggPos = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, EndPodiumFeature.getLocation(origin));
        final CraftBlockState eggState = CraftBlockStates.getBlockState(level, eggPos);
        eggState.setBlock(Blocks.DRAGON_EGG.defaultBlockState());
        final io.papermc.paper.event.block.DragonEggFormEvent event = new io.papermc.paper.event.block.DragonEggFormEvent(
                CraftBlock.at(level, eggPos), eggState, new org.bukkit.craftbukkit.boss.CraftDragonBattle(fight));

        if (previouslyKilled) {
            event.setCancelled(true);
        }

        if (event.callEvent()) {
            ((CraftBlockState) event.getNewState()).place(net.minecraft.world.level.block.Block.UPDATE_ALL);
        }
    }

    // ------------------------------------------------------------ 世界の境界

    private static boolean borderApplying;

    private static boolean borderReady(final WorldBorder border, final org.bukkit.event.HandlerList handlers) {
        return !borderApplying && border.world != null && listening(handlers);
    }

    /**
     * WorldBorderCenterChangeEvent。書く前。中心が差し替えられていたら、その値で自分を
     * 呼び直して false(vanilla の引数は final)。
     *
     * 読んだ位置: paper-server patches/sources/net/minecraft/world/level/border/WorldBorder.java.patch
     */
    public static boolean borderCenter(final WorldBorder border, final double x, final double z) {
        if (!borderReady(border, io.papermc.paper.event.world.border.WorldBorderCenterChangeEvent.getHandlerList())) {
            return true;
        }

        final org.bukkit.World world = border.world.getWorld();
        final io.papermc.paper.event.world.border.WorldBorderCenterChangeEvent event = new io.papermc.paper.event.world.border.WorldBorderCenterChangeEvent(
                world, world.getWorldBorder(), new Location(world, border.getCenterX(), 0, border.getCenterZ()), new Location(world, x, 0, z));

        if (!event.callEvent()) {
            return false;
        }

        if (event.getNewCenter().getX() == x && event.getNewCenter().getZ() == z) {
            return true;
        }

        borderApplying = true;

        try {
            border.setCenter(event.getNewCenter().getX(), event.getNewCenter().getZ());
        } finally {
            borderApplying = false;
        }

        return false;
    }

    /** WorldBorderBoundsChangeEvent(即時)。書く前。 */
    public static boolean borderSize(final WorldBorder border, final double size) {
        if (!borderReady(border, io.papermc.paper.event.world.border.WorldBorderBoundsChangeEvent.getHandlerList())) {
            return true;
        }

        final org.bukkit.World world = border.world.getWorld();
        final io.papermc.paper.event.world.border.WorldBorderBoundsChangeEvent event = new io.papermc.paper.event.world.border.WorldBorderBoundsChangeEvent(
                world, world.getWorldBorder(), io.papermc.paper.event.world.border.WorldBorderBoundsChangeEvent.Type.INSTANT_MOVE,
                border.getSize(), size, 0);

        if (!event.callEvent()) {
            return false;
        }

        borderApplying = true;

        try {
            if (event.getType() == io.papermc.paper.event.world.border.WorldBorderBoundsChangeEvent.Type.STARTED_MOVE && event.getDurationTicks() > 0) {
                border.lerpSizeBetween(event.getOldSize(), event.getNewSize(), event.getDurationTicks(), border.world.getGameTime());

                return false;
            }

            if (event.getNewSize() == size) {
                return true;
            }

            border.setSize(event.getNewSize());
        } finally {
            borderApplying = false;
        }

        return false;
    }

    /** WorldBorderBoundsChangeEvent(時間をかけて)。書く前。 */
    public static boolean borderLerp(final WorldBorder border, final double from, final double to, final long ticks, final long gameTime) {
        if (!borderReady(border, io.papermc.paper.event.world.border.WorldBorderBoundsChangeEvent.getHandlerList())) {
            return true;
        }

        final org.bukkit.World world = border.world.getWorld();
        final io.papermc.paper.event.world.border.WorldBorderBoundsChangeEvent.Type type = from == to
                ? io.papermc.paper.event.world.border.WorldBorderBoundsChangeEvent.Type.INSTANT_MOVE
                : io.papermc.paper.event.world.border.WorldBorderBoundsChangeEvent.Type.STARTED_MOVE;
        final io.papermc.paper.event.world.border.WorldBorderBoundsChangeEvent event = new io.papermc.paper.event.world.border.WorldBorderBoundsChangeEvent(
                world, world.getWorldBorder(), type, from, to, ticks);

        if (!event.callEvent()) {
            return false;
        }

        if (event.getNewSize() == to && event.getDurationTicks() == ticks) {
            return true;
        }

        borderApplying = true;

        try {
            border.lerpSizeBetween(from, event.getNewSize(), event.getDurationTicks(), gameTime);
        } finally {
            borderApplying = false;
        }

        return false;
    }

    /** WorldBorderBoundsChangeFinishEvent。動きが終わった tick。 */
    public static void borderFinish(final WorldBorder border, final double from, final double to, final double duration) {
        if (border.world == null || !listening(io.papermc.paper.event.world.border.WorldBorderBoundsChangeFinishEvent.getHandlerList())) {
            return;
        }

        final org.bukkit.World world = border.world.getWorld();
        new io.papermc.paper.event.world.border.WorldBorderBoundsChangeFinishEvent(world, world.getWorldBorder(), from, to, duration).callEvent();
    }

    // ------------------------------------------------------------ ログイン・設定フェーズ

    /**
     * PlayerConnectionValidateLoginEvent。vanilla の禁止・ホワイトリスト・満員の判定のあと。
     * プラグインが弾く理由を変えたり、許したりできる。
     *
     * @return 弾く理由。許すなら null
     */
    public static Component validateLogin(final io.papermc.paper.connection.PlayerConnection connection, final Component error) {
        if (!listening(io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent.getHandlerList())) {
            return error;
        }

        final io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent event = new io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent(
                connection, error == null ? null : PaperAdventure.asAdventure(error));
        event.callEvent();

        return event.getKickMessage() == null ? null : PaperAdventure.asVanilla(event.getKickMessage());
    }

    /** PlayerServerFullCheckEvent に登録が無いか。 */
    public static boolean silentServerFull() {
        return !listening(io.papermc.paper.event.player.PlayerServerFullCheckEvent.getHandlerList());
    }

    /**
     * PlayerServerFullCheckEvent。入れるかを決める最後のところ。Paper と同じく満員でなくても
     * 発火するので、プラグインは満員でない相手を弾くことも、満員の相手を入れることもできる。
     *
     * @param full vanilla の判定(満員で、上限を超えて入れる相手でもない)
     * @return 弾く文。入れてよければ null
     */
    public static Component serverFull(final NameAndId nameAndId, final boolean full) {
        final io.papermc.paper.event.player.PlayerServerFullCheckEvent event = new io.papermc.paper.event.player.PlayerServerFullCheckEvent(
                new com.destroystokyo.paper.profile.CraftPlayerProfile(nameAndId),
                PaperAdventure.asAdventure(Component.translatable("multiplayer.disconnect.server_full")), full);
        event.callEvent();

        return event.isAllowed() ? null : PaperAdventure.asVanilla(event.kickMessage());
    }

    /** ProfileWhitelistVerifyEvent に登録が無いか。 */
    public static boolean silentWhitelist() {
        return !listening(com.destroystokyo.paper.event.profile.ProfileWhitelistVerifyEvent.getHandlerList());
    }

    /**
     * ProfileWhitelistVerifyEvent。{@code isWhiteListed} の判定の代わり。弾く文の差し替えは
     * 効かない(vanilla の文を使う)。{@code isWhiteListed} を呼ぶ経路すべてで発火する
     * (ログインのほか、ホワイトリストの読み直しで追い出すときも)。
     */
    public static boolean whitelistVerify(final PlayerList list, final NameAndId nameAndId,
                                          final boolean whitelisted, final boolean isOp) {
        final com.destroystokyo.paper.event.profile.ProfileWhitelistVerifyEvent event = new com.destroystokyo.paper.event.profile.ProfileWhitelistVerifyEvent(
                new com.destroystokyo.paper.profile.CraftPlayerProfile(nameAndId), list.isUsingWhitelist(), whitelisted, isOp,
                net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(org.spigotmc.SpigotConfig.whitelistMessage));
        whitelistKick = null;
        event.callEvent();

        if (!event.isWhitelisted() && event.kickMessage() != null) {
            whitelistKick = PaperAdventure.asVanilla(event.kickMessage());
        }

        return event.isWhitelisted();
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

    /**
     * 同期のイベントを、サーバースレッドで発火して結果を待つ。
     *
     * <p>設定フェーズ({@code startConfiguration})は netty のスレッドで走るので、そのまま
     * 発火すると Bukkit が「同期のイベントは同期でしか発火できない」で落とす。Paper は
     * 設定フェーズをサーバースレッドへ寄せているが、それは vanilla の呼ぶ場所を変えることになる。
     * 発火だけをサーバースレッドの待ち行列へ回して、返るまで待つ。
     */
    private static <T> T onServerThread(final java.util.function.Supplier<T> body) {
        if (org.bukkit.Bukkit.isPrimaryThread()) {
            return body.get();
        }

        final org.bukkit.craftbukkit.util.Waitable<T> waitable = new org.bukkit.craftbukkit.util.Waitable<>() {
            @Override
            protected T evaluate() {
                return body.get();
            }
        };
        net.minecraft.server.MinecraftServer.getServer().processQueue.add(waitable);

        try {
            return waitable.get();
        } catch (final InterruptedException | java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException(e);
        }
    }

    /** PlayerLinksSendEvent。送る前。差し替えたリンクを返す。 */
    public static ServerLinks linksSend(final ServerConfigurationPacketListenerImpl listener, final ServerLinks links) {
        if (!listening(org.bukkit.event.player.PlayerLinksSendEvent.getHandlerList())) {
            return links;
        }

        return onServerThread(() -> {
            final org.bukkit.craftbukkit.CraftServerLinks bukkit = new org.bukkit.craftbukkit.CraftServerLinks(links);
            new org.bukkit.event.player.PlayerLinksSendEvent(listener.paperConnection, bukkit).callEvent();

            return bukkit.getServerLinks();
        });
    }

    /**
     * PlayerCodeOfConductSendEvent。送る文が決まったあと。Paper は文が無くても発火するが、
     * Shifu はあるときだけ。null を返したら送らない。
     */
    public static String codeOfConduct(final ServerConfigurationPacketListenerImpl listener, final String text) {
        if (!listening(io.papermc.paper.event.connection.configuration.PlayerCodeOfConductSendEvent.getHandlerList())) {
            return text;
        }

        return onServerThread(() -> {
            final io.papermc.paper.event.connection.configuration.PlayerCodeOfConductSendEvent event =
                    new io.papermc.paper.event.connection.configuration.PlayerCodeOfConductSendEvent(listener.paperConnection, text);
            event.callEvent();

            return event.getCodeOfConduct();
        });
    }

    // ------------------------------------------------------------ プレイヤーの packet

    /** ClientTickEndEvent。クライアントの tick 終わりの packet。 */
    public static void clientTickEnd(final ServerPlayer player) {
        if (!listening(io.papermc.paper.event.packet.ClientTickEndEvent.getHandlerList())) {
            return;
        }

        new io.papermc.paper.event.packet.ClientTickEndEvent(player.getBukkitEntity()).callEvent();
    }

    /**
     * PlayerJumpEvent。{@code jumpFromGround} の直前。取り消されたら from へ戻して packet を捨てる。
     * from は接続が控えている直前の発火位置(PlayerMoveEvent と同じ)。
     *
     * 読んだ位置: paper-server patches/sources/net/minecraft/server/network/ServerGamePacketListenerImpl.java.patch(PlayerJumpEvent)
     */
    public static boolean jump(final ServerGamePacketListenerImpl connection,
                               final double startX, final double startY, final double startZ,
                               final double targetX, final double targetY, final double targetZ,
                               final float targetYRot, final float targetXRot) {
        if (!listening(com.destroystokyo.paper.event.player.PlayerJumpEvent.getHandlerList())) {
            return true;
        }

        final ServerPlayer player = connection.player;
        final Location from = connection.shifuMoveFrom(startX, startY, startZ, player.getYRot(), player.getXRot());
        final Location to = new Location(player.level().getWorld(), targetX, targetY, targetZ, targetYRot, targetXRot);
        final com.destroystokyo.paper.event.player.PlayerJumpEvent event = new com.destroystokyo.paper.event.player.PlayerJumpEvent(
                player.getBukkitEntity(), from, to);

        if (event.callEvent()) {
            return true;
        }

        connection.internalTeleport(event.getFrom());

        return false;
    }

    /**
     * PlayerMoveEvent(乗り物に乗っている間)。vanilla が乗り物を動かしたあと。
     * 中身は {@link ShifuEvents#playerMove} と同じ。Paper がプレイヤーを乗り物の位置へ動かす
     * 細工は使わないので、イベントの中で {@code player.getLocation()} を読むと動く前の位置。
     */
    public static boolean vehicleMove(final ServerGamePacketListenerImpl connection,
                                      final double targetX, final double targetY, final double targetZ,
                                      final float targetYRot, final float targetXRot) {
        if (!listening(org.bukkit.event.player.PlayerMoveEvent.getHandlerList())) {
            return true;
        }

        final ServerPlayer player = connection.player;

        return ShifuEvents.playerMove(connection, player.getX(), player.getY(), player.getZ(),
                targetX, targetY, targetZ, targetYRot, targetXRot);
    }

    private static boolean swapCancelled;

    /**
     * PlayerSwapHandItemsEvent。入れ替える前。取り消されたら {@link #swapHandsCancelled} が true。
     * 物が差し替えられていたらここで持たせて false。
     *
     * @return vanilla の入れ替えを行ってよいか
     */
    public static boolean swapHands(final ServerPlayer player, final ItemStack swap) {
        swapCancelled = false;

        if (!listening(org.bukkit.event.player.PlayerSwapHandItemsEvent.getHandlerList())) {
            return true;
        }

        final CraftItemStack mainHand = CraftItemStack.asCraftMirror(swap);
        final CraftItemStack offHand = CraftItemStack.asCraftMirror(player.getItemInHand(InteractionHand.MAIN_HAND));
        final org.bukkit.event.player.PlayerSwapHandItemsEvent event = new org.bukkit.event.player.PlayerSwapHandItemsEvent(
                player.getBukkitEntity(), mainHand.clone(), offHand.clone());

        if (!event.callEvent()) {
            swapCancelled = true;

            return false;
        }

        if (event.getOffHandItem().equals(offHand) && event.getMainHandItem().equals(mainHand)) {
            return true;
        }

        final ItemStack newOff = event.getOffHandItem().equals(offHand)
                ? player.getItemInHand(InteractionHand.MAIN_HAND) : CraftItemStack.asNMSCopy(event.getOffHandItem());
        final ItemStack newMain = event.getMainHandItem().equals(mainHand) ? swap : CraftItemStack.asNMSCopy(event.getMainHandItem());
        player.setItemInHand(InteractionHand.OFF_HAND, newOff);
        player.setItemInHand(InteractionHand.MAIN_HAND, newMain);

        return false;
    }

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

    /**
     * PlayerEditBookEvent。vanilla が本を書き換えたあと。取り消されたら控えに戻す。
     * 中身が差し替えられていたら置き直す。署名の場合は {@code signed} が新しい本で、
     * 取り消されたら元の本をスロットに戻す。
     */
    public static void bookEdited(final ServerPlayer player, final int slot, final ItemStack before, final ItemStack after, final boolean signing) {
        if (before == null) {
            return;
        }

        final org.bukkit.event.player.PlayerEditBookEvent event = new org.bukkit.event.player.PlayerEditBookEvent(
                player.getBukkitEntity(), slot >= 0 && slot <= 8 ? slot : -1,
                (org.bukkit.inventory.meta.BookMeta) CraftItemStack.getItemMeta(before),
                (org.bukkit.inventory.meta.BookMeta) CraftItemStack.getItemMeta(after), signing);
        event.callEvent();

        if (event.isCancelled()) {
            player.getInventory().setItem(slot, before);
            player.containerMenu.forceSlot(player.getInventory(), slot);

            return;
        }

        CraftItemStack.setItemMeta(after, event.getNewBookMeta());
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

    /**
     * PlayerSpawnChangeEvent と PlayerSetSpawnEvent。{@code setRespawnPosition} の頭。
     * 位置や通知が差し替えられていたら、その値で自分を呼び直して false(引数は final)。
     *
     * 読んだ位置: paper-server patches/sources/net/minecraft/server/level/ServerPlayer.java.patch(PlayerSetSpawnEvent)
     */
    public static boolean setSpawn(final ServerPlayer player, final ServerPlayer.RespawnConfig config, final boolean showMessage) {
        if (settingSpawn) {
            return true;
        }

        if (!listening(com.destroystokyo.paper.event.player.PlayerSetSpawnEvent.getHandlerList())
                && !listening(org.bukkit.event.player.PlayerSpawnChangeEvent.getHandlerList())) {
            return true;
        }

        final com.destroystokyo.paper.event.player.PlayerSetSpawnEvent.Cause cause = spawnPlayer == player && spawnCause != null
                ? spawnCause : com.destroystokyo.paper.event.player.PlayerSetSpawnEvent.Cause.UNKNOWN;
        spawnPlayer = null;
        spawnCause = null;

        Location location = null;
        boolean notify = false;

        if (config != null) {
            notify = showMessage && !config.isSamePosition(player.getRespawnConfig());
            location = CraftLocation.toBukkit(config.respawnData().pos(), player.level().getServer().getLevel(config.respawnData().dimension()));
            location.setYaw(config.respawnData().yaw());
            location.setPitch(config.respawnData().pitch());
        }

        final org.bukkit.event.player.PlayerSpawnChangeEvent first = new org.bukkit.event.player.PlayerSpawnChangeEvent(
                player.getBukkitEntity(), location, config != null && config.forced(),
                cause == com.destroystokyo.paper.event.player.PlayerSetSpawnEvent.Cause.PLAYER_RESPAWN
                        ? org.bukkit.event.player.PlayerSpawnChangeEvent.Cause.RESET
                        : org.bukkit.event.player.PlayerSpawnChangeEvent.Cause.valueOf(cause.name()));
        first.callEvent();

        final net.kyori.adventure.text.Component message = notify
                ? PaperAdventure.asAdventure(Component.translatable("block.minecraft.set_spawn")) : null;
        final com.destroystokyo.paper.event.player.PlayerSetSpawnEvent event = new com.destroystokyo.paper.event.player.PlayerSetSpawnEvent(
                player.getBukkitEntity(), cause, first.getNewSpawn(), first.isForced(), notify, message);
        event.setCancelled(first.isCancelled());

        if (!event.callEvent()) {
            return false;
        }

        final boolean sameLocation = (event.getLocation() == null) == (location == null)
                && (location == null || (event.getLocation().equals(location) && event.isForced() == config.forced()));
        final boolean sameNotice = event.willNotifyPlayer() == notify && (message == null || message.equals(event.getNotification()));

        if (sameLocation && sameNotice) {
            return true;
        }

        ServerPlayer.RespawnConfig replaced = config;

        if (!sameLocation) {
            replaced = event.getLocation() == null ? null : new ServerPlayer.RespawnConfig(
                    LevelData.RespawnData.of(
                            ((org.bukkit.craftbukkit.CraftWorld) event.getLocation().getWorld()).getHandle().dimension(),
                            CraftLocation.toBlockPos(event.getLocation()), event.getLocation().getYaw(), event.getLocation().getPitch()),
                    event.isForced());
        }

        if (event.willNotifyPlayer() && event.getNotification() != null) {
            player.sendSystemMessage(PaperAdventure.asVanilla(event.getNotification()));
        }

        settingSpawn = true;

        try {
            player.setRespawnPosition(replaced, false);
        } finally {
            settingSpawn = false;
        }

        return false;
    }

    // ------------------------------------------------------------ ブロックを壊す

    /**
     * BlockDamageEvent。壊し始めの判定のあと(クリエイティブの即時破壊は通らない)。
     *
     * @return イベント。登録が無ければ null
     */
    public static org.bukkit.event.block.BlockDamageEvent blockDamage(final ServerPlayer player, final BlockPos pos,
                                                                     final Direction direction, final boolean instaBreak) {
        if (!listening(org.bukkit.event.block.BlockDamageEvent.getHandlerList())) {
            return null;
        }

        return CraftEventFactory.callBlockDamageEvent(player, pos, direction, player.getInventory().getSelectedItem(), instaBreak);
    }

    /** BlockDamageAbortEvent。壊すのをやめたとき。 */
    public static void blockDamageAbort(final ServerPlayer player, final BlockPos pos) {
        if (!listening(org.bukkit.event.block.BlockDamageAbortEvent.getHandlerList())) {
            return;
        }

        CraftEventFactory.callBlockDamageAbortEvent(player, pos, player.getInventory().getSelectedItem());
    }

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

    /**
     * PlayerShieldDisableEvent。クールダウンを付ける前。
     *
     * @return 付ける tick 数。取り消されたら -1。攻撃者が置かれていなければ元の値
     */
    public static int shieldDisable(final net.minecraft.world.entity.player.Player player, final int cooldownTicks) {
        if (!listening(io.papermc.paper.event.player.PlayerShieldDisableEvent.getHandlerList())) {
            return cooldownTicks;
        }

        final LivingEntity attacker = shieldAttacker;
        shieldAttacker = null;

        if (attacker == null) {
            return cooldownTicks;
        }

        final io.papermc.paper.event.player.PlayerShieldDisableEvent event = new io.papermc.paper.event.player.PlayerShieldDisableEvent(
                (org.bukkit.entity.Player) player.getBukkitEntity(), attacker.getBukkitEntity(), cooldownTicks);

        return event.callEvent() ? event.getCooldown() : -1;
    }

    /** EntityLungeEvent。突きの効果を当てる前。取り消しだけ効く(強さは lambda の中で決まる)。 */
    public static int lungePower(final Entity user, final int level) {
        if (!(user instanceof LivingEntity living) || !listening(io.papermc.paper.event.entity.EntityLungeEvent.getHandlerList())) {
            return level;
        }

        final io.papermc.paper.event.entity.EntityLungeEvent event = new io.papermc.paper.event.entity.EntityLungeEvent(
                living.getBukkitLivingEntity(), level);

        return event.callEvent() ? event.getLungePower() : Integer.MIN_VALUE;
    }

    /**
     * EntityCombustByEntityEvent / EntityCombustEvent(火属性のエンチャント)。燃やす前。
     * 長さが差し替えられていたらここで燃やして false。
     */
    public static boolean enchantIgnite(final EnchantedItemInUse item, final Entity entity, final LevelBasedValue duration, final int level) {
        if (!listening(org.bukkit.event.entity.EntityCombustByEntityEvent.getHandlerList())
                && !listening(org.bukkit.event.entity.EntityCombustEvent.getHandlerList())) {
            return true;
        }

        final float seconds = duration.calculate(level);
        final org.bukkit.event.entity.EntityCombustEvent event = item.owner() != null
                ? new org.bukkit.event.entity.EntityCombustByEntityEvent(item.owner().getBukkitEntity(), entity.getBukkitEntity(), seconds)
                : new org.bukkit.event.entity.EntityCombustEvent(entity.getBukkitEntity(), seconds);

        if (!event.callEvent()) {
            return false;
        }

        if (event.getDuration() == seconds) {
            return true;
        }

        entity.igniteForSeconds(event.getDuration());

        return false;
    }

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
