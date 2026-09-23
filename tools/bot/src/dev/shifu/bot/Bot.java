// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.bot;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.BitSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.ArgumentSignatures;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.PacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.game.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ClientboundPingPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPongPacket;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.login.ClientLoginPacketListener;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ClientboundLoginCompressionPacket;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.network.protocol.login.ServerboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import io.netty.buffer.Unpooled;

/**
 * プレイヤー経路を通すためのヘッドレスクライアント(1.19.4 の版)。
 *
 * <p>サーバー自身の {@code Connection} と packet の型で接続する。
 * クライアント側の listener は動的 Proxy で作り、要る packet だけ扱う。
 * 受信の復号はサーバーの codec をそのまま使い、レジストリが要る packet
 * (ワールドの内容など)は復号に失敗しても捨てて先へ進む。
 *
 * <p>1.19.4 には configuration の段が無い。ログインの GameProfile を受けたら
 * そのまま PLAY へ移り、ClientInformation を送る。
 *
 * <p>サーバー側の検証プラグイン({@code tools/plugin-drive})が chat で
 * {@code !bot <指示>} を送り、それに従って packet を送る。
 *
 * <pre>
 * java -cp <versions/1.19.4/paper-1.19.4.jar;libraries/**> dev.shifu.bot.Bot [host] [port] [name] [seconds]
 * </pre>
 */
public final class Bot {
    private final String name;
    private final CountDownLatch done = new CountDownLatch(1);
    private Connection connection;
    private ClientGamePacketListener game;
    private volatile double x;
    private volatile double y;
    private volatile double z;
    private volatile float yaw;
    private volatile float pitch;
    private volatile int teleports;
    /** 自走の指示("dx,dz,steps,ms")。無人のサーバーでプレイヤーの範囲を作るための決まった歩き方。 */
    private static String walk;

    private Bot(final String name) {
        this.name = name;
    }

    public static void main(final String[] args) throws Exception {
        final String host = args.length > 0 ? args[0] : "127.0.0.1";
        final int port = args.length > 1 ? Integer.parseInt(args[1]) : 25599;
        final String name = args.length > 2 ? args[2] : "ShifuBot";
        final long seconds = args.length > 3 ? Long.parseLong(args[3]) : 90;
        walk = args.length > 4 && args[4].startsWith("walk=") ? args[4].substring(5) : null;

        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        final Bot bot = new Bot(name);

        try {
            bot.connect(host, port);
        } catch (final Throwable t) {
            // Bootstrap が System.err を logger 送りにするので、控えた方へ出す
            t.printStackTrace(OUT);
            OUT.flush();
            System.exit(1);
        }

        if (!bot.done.await(seconds, TimeUnit.SECONDS)) {
            log("time is up");
        }

        if (bot.connection.isConnected()) {
            bot.connection.disconnect(Component.literal("done"));
        }

        Thread.sleep(500);
        log("exit");
        System.exit(0);
    }

    /** Bootstrap.bootStrap() が System.out を logger 送りに差し替えるので、その前のものを控えておく。 */
    private static final java.io.PrintStream OUT = System.out;

    static void log(final String text) {
        OUT.println("[bot] " + text);
        OUT.flush();
    }

    // ------------------------------------------------------------ 接続

    private void connect(final String host, final int port) throws InterruptedException {
        this.connection = Connection.connectToServer(new InetSocketAddress(host, port), false);
        // channel は event loop の channelActive で入る。connect の sync が返った直後はまだ null のことがある
        for (int i = 0; i < 200 && this.connection.channel == null; i++) {
            Thread.sleep(10);
        }
        log("connected to " + host + ":" + port);

        // 復号に失敗した packet(レジストリが要るもの)を捨てる decoder に差し替える
        this.connection.channel.pipeline().replace("decoder", "decoder", new ForgivingDecoder());

        final ClientLoginPacketListener login = this.listener(ClientLoginPacketListener.class, this::onLogin);
        this.connection.setListener(login);
        // Connection.send は packet ごとの protocol に event loop の中で切り替える。ここで setProtocol を
        // 呼ぶと intention packet より先に LOGIN になって、intention の encode に失敗し黙って切れる
        this.connection.send(new ClientIntentionPacket(host, port, ConnectionProtocol.LOGIN));
        this.connection.send(new ServerboundHelloPacket(this.name, Optional.empty()));
    }

    private void onLogin(final String method, final Object packet) {
        switch (method) {
            case "handleCompression" -> this.connection.setupCompression(((ClientboundLoginCompressionPacket) packet).getCompressionThreshold(), false);
            case "handleGameProfile" -> {
                log("login finished");
                this.game = this.listener(ClientGamePacketListener.class, this::onGame);
                this.connection.setProtocol(ConnectionProtocol.PLAY);
                this.connection.setListener(this.game);
            }
            case "handleCustomQuery" -> this.connection.send(
                    new ServerboundCustomQueryPacket(((ClientboundCustomQueryPacket) packet).getTransactionId(), null));
            case "handleDisconnect" -> {
                log("disconnected while logging in: " + ((ClientboundLoginDisconnectPacket) packet).getReason().getString());
                this.done.countDown();
            }
            case "handleHello" -> log("server asked for encryption; the server must be in offline mode");
            default -> {
            }
        }
    }

    private void onGame(final String method, final Object packet) throws Exception {
        switch (method) {
            case "handleBundlePacket" -> {
                for (Packet<ClientGamePacketListener> sub : ((ClientboundBundlePacket) packet).subPackets()) {
                    sub.handle(this.game);
                }
            }
            case "handleKeepAlive" -> this.connection.send(new ServerboundKeepAlivePacket(((ClientboundKeepAlivePacket) packet).getId()));
            case "handlePing" -> this.connection.send(new ServerboundPongPacket(((ClientboundPingPacket) packet).getId()));
            case "handleLogin" -> {
                log("joined the game");
                // サーバーは Login packet を送る前に PLAY へ切り替える。GameProfile の直後に送ると、
                // サーバーがまだ LOGIN のまま読んで id が無く切れる(vanilla のクライアントもここで送る)
                this.connection.send(new ServerboundClientInformationPacket(
                        "en_us", 8, ChatVisiblity.FULL, true, 0, HumanoidArm.RIGHT, false, true));
            }
            case "handleMovePlayer" -> this.accept((ClientboundPlayerPositionPacket) packet);
            case "handleBossUpdate" -> ((net.minecraft.network.protocol.game.ClientboundBossEventPacket) packet).dispatch(
                    new net.minecraft.network.protocol.game.ClientboundBossEventPacket.Handler() {
                        @Override
                        public void add(final java.util.UUID id, final net.minecraft.network.chat.Component name, final float percent,
                                final net.minecraft.world.BossEvent.BossBarColor color, final net.minecraft.world.BossEvent.BossBarOverlay style,
                                final boolean darkenSky, final boolean dragonMusic, final boolean thickenFog) {
                            log("bossbar add name=" + name.getString() + " progress=" + percent + " color=" + color + " overlay=" + style);
                        }
                        @Override
                        public void remove(final java.util.UUID id) { log("bossbar remove"); }
                        @Override
                        public void updateProgress(final java.util.UUID id, final float percent) { log("bossbar progress=" + percent); }
                        @Override
                        public void updateName(final java.util.UUID id, final net.minecraft.network.chat.Component name) { log("bossbar name=" + name.getString()); }
                        @Override
                        public void updateStyle(final java.util.UUID id, final net.minecraft.world.BossEvent.BossBarColor color,
                                final net.minecraft.world.BossEvent.BossBarOverlay style) { log("bossbar color=" + color + " overlay=" + style); }
                    });
            case "handleSetHealth" -> log("health " + ((ClientboundSetHealthPacket) packet).getHealth());
            case "handlePlayerCombatKill" -> {
                log("died: " + ((ClientboundPlayerCombatKillPacket) packet).getMessage().getString());
                Thread.sleep(200);
                this.connection.send(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
                log("sent respawn");
            }
            case "handleSystemChat" -> this.command(((ClientboundSystemChatPacket) packet).content().getString());
            case "handleDisconnect" -> {
                log("disconnected: " + ((ClientboundDisconnectPacket) packet).getReason().getString());
                this.done.countDown();
            }
            default -> {
            }
        }
    }

    /** サーバーから届いた位置を受け入れ、vanilla のクライアントと同じく位置の packet を返す。 */
    private void accept(final ClientboundPlayerPositionPacket packet) {
        final Set<RelativeMovement> relatives = packet.getRelativeArguments();
        this.x = relatives.contains(RelativeMovement.X) ? this.x + packet.getX() : packet.getX();
        this.y = relatives.contains(RelativeMovement.Y) ? this.y + packet.getY() : packet.getY();
        this.z = relatives.contains(RelativeMovement.Z) ? this.z + packet.getZ() : packet.getZ();
        this.yaw = relatives.contains(RelativeMovement.Y_ROT) ? this.yaw + packet.getYRot() : packet.getYRot();
        this.pitch = relatives.contains(RelativeMovement.X_ROT) ? this.pitch + packet.getXRot() : packet.getXRot();
        this.teleports++;
        log(String.format("teleport #%d to %.2f %.2f %.2f", this.teleports, this.x, this.y, this.z));
        if (walk != null && this.teleports == 1) {
            // 参加の位置は毎回違う(spawnRadius の乱数は agent が固定しない)。op にしてある前提で決まった場所へ飛び、
            // その受け入れ(2 回目のテレポート)から歩く
            this.connection.send(new ServerboundChatCommandPacket("tp 0 120 0", Instant.now(), 0L,
                    ArgumentSignatures.EMPTY, new LastSeenMessages.Update(0, new BitSet(20))));
        } else if (walk != null && this.teleports == 2) {
            this.startWalk();
        }
        this.connection.send(new ServerboundAcceptTeleportationPacket(packet.getId()));
        this.move();
    }

    /** 最初のテレポート(参加)のあと、決まった歩き方で位置を送り続ける。drive のいないサーバー(vanilla)でも同じ範囲を読み込ませる。 */
    private void startWalk() {
        if (walk == null) {
            return;
        }
        final String[] parts = walk.split(",");
        final double dx = Double.parseDouble(parts[0]);
        final double dz = Double.parseDouble(parts[1]);
        final int steps = Integer.parseInt(parts[2]);
        final long ms = Long.parseLong(parts[3]);
        final Thread walker = new Thread(() -> {
            try {
                for (int i = 0; i < steps && this.connection.isConnected(); i++) {
                    this.x += dx;
                    this.z += dz;
                    this.move();
                    Thread.sleep(ms);
                }
                log(String.format("walked to %.2f %.2f %.2f", this.x, this.y, this.z));
            } catch (final InterruptedException stop) {
                Thread.currentThread().interrupt();
            }
        }, "walker");
        walker.setDaemon(true);
        walker.start();
    }

    /** いまの位置を送る。 */
    private void move() {
        this.connection.send(new ServerboundMovePlayerPacket.PosRot(
                this.x, this.y, this.z, this.yaw, this.pitch, false));
    }

    // ------------------------------------------------------------ 指示

    private void command(final String text) throws Exception {
        if (!text.startsWith("!bot ")) {
            log("chat: " + text);
            return;
        }

        final String[] words = text.substring(5).trim().split(" ", 2);
        final String verb = words[0];
        final String rest = words.length > 1 ? words[1] : "";
        log("command: " + verb + (rest.isEmpty() ? "" : " " + rest));

        switch (verb) {
            case "move" -> {
                for (int i = 0; i < 8; i++) {
                    this.x += 0.25;
                    this.move();
                    Thread.sleep(50);
                }
                log(String.format("moved to %.2f %.2f %.2f", this.x, this.y, this.z));
            }
            case "chat" -> this.connection.send(new ServerboundChatPacket(rest, Instant.now(), 0L, null,
                    new LastSeenMessages.Update(0, new BitSet(20))));
            // コマンド。チャットとは別の packet。1.19.4 は署名の欄を持つ(空で送る)
            case "cmd" -> this.connection.send(new ServerboundChatCommandPacket(rest, Instant.now(), 0L,
                    ArgumentSignatures.EMPTY, new LastSeenMessages.Update(0, new BitSet(20))));
            case "click" -> this.connection.send(new ServerboundContainerClickPacket(0, 0, Integer.parseInt(rest), 0,
                    ClickType.PICKUP, ItemStack.EMPTY, new Int2ObjectOpenHashMap<>()));
            // ブロックを壊す。クリエイティブなら START だけで壊れるが、両方送る
            case "break" -> {
                final BlockPos target = blockPos(rest);
                this.connection.send(new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, target, Direction.UP, 0));
                this.connection.send(new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, target, Direction.UP, 0));
            }
            case "drop" -> this.connection.send(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.DROP_ITEM, BlockPos.ZERO, Direction.DOWN, 0));
            // 持ち替え。数字は 0..8
            case "slot" -> this.connection.send(
                    new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(Integer.parseInt(rest)));
            case "respawn" -> this.connection.send(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
            case "fly" -> {
                // 飛行の切り替え。サーバーは flying だけを読む
                final net.minecraft.world.entity.player.Abilities abilities = new net.minecraft.world.entity.player.Abilities();
                abilities.flying = Boolean.parseBoolean(rest);
                this.connection.send(new net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket(abilities));
            }
            case "eat" -> this.connection.send(new net.minecraft.network.protocol.game.ServerboundUseItemPacket(
                    net.minecraft.world.InteractionHand.MAIN_HAND, 1));
            case "use" -> {
                // ブロックの上面を右クリック
                final BlockPos pos = blockPos(rest);
                final Vec3 hit = new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
                this.connection.send(new net.minecraft.network.protocol.game.ServerboundUseItemOnPacket(
                        net.minecraft.world.InteractionHand.MAIN_HAND,
                        new net.minecraft.world.phys.BlockHitResult(hit, Direction.UP, pos, false), 1));
            }
            case "dig" -> {
                // 壊し始めの packet(クリエイティブなら即座に壊れる)
                final BlockPos pos = blockPos(rest);
                this.connection.send(new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, Direction.UP, 2));
            }
            // 名乗り(minecraft:brand)。1.19.4 のプラグインメッセージはチャンネルと本文の組
            case "brand" -> {
                final FriendlyByteBuf data = new FriendlyByteBuf(Unpooled.buffer());
                data.writeUtf(rest);
                this.connection.send(new ServerboundCustomPayloadPacket(ServerboundCustomPayloadPacket.BRAND, data));
            }
            // 乗り物を動かす(ServerboundMoveVehiclePacket)。乗せるのはサーバー側(drive)
            case "vehicle" -> {
                final String[] parts = rest.trim().split("[,\\s]+");
                final FriendlyByteBuf data = new FriendlyByteBuf(Unpooled.buffer());
                data.writeDouble(Double.parseDouble(parts[0]));
                data.writeDouble(Double.parseDouble(parts[1]));
                data.writeDouble(Double.parseDouble(parts[2]));
                data.writeFloat(0.0f);
                data.writeFloat(0.0f);
                this.connection.send(new net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket(data));
            }
            // プラグインメッセージの本文。1.19.4 はチャンネルと生の bytes の組なので、知らないチャンネルでも送れる
            case "payload" -> {
                final String[] parts = rest.trim().split(" ", 2);
                final FriendlyByteBuf data = new FriendlyByteBuf(Unpooled.buffer());
                data.writeUtf(parts.length > 1 ? parts[1] : "");
                this.connection.send(new ServerboundCustomPayloadPacket(
                        new net.minecraft.resources.ResourceLocation(parts[0]), data));
            }
            case "where" -> log(String.format("at %.2f %.2f %.2f", this.x, this.y, this.z));
            case "quit" -> {
                this.connection.disconnect(Component.literal("done"));
                this.done.countDown();
            }
            default -> log("unknown command " + verb);
        }
    }

    private static BlockPos blockPos(final String text) {
        final String[] parts = text.trim().split("[,\\s]+");

        return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }

    // ------------------------------------------------------------ 仕掛け

    interface Handler {
        void handle(String method, Object packet) throws Exception;
    }

    /** 要る packet だけ扱う listener。それ以外の handle は何もしない。 */
    private <T extends PacketListener> T listener(final Class<T> type, final Handler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "isAcceptingMessages":
                case "shouldHandleMessage":
                    return true;
                case "shouldPropagateHandlingExceptions":
                    return false;
                case "onDisconnect":
                    log("connection closed: " + ((Component) args[0]).getString());
                    this.done.countDown();
                    return null;
                case "toString":
                    return "Bot(" + type.getSimpleName() + ")";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    if (args != null && args.length == 1 && args[0] instanceof Packet) {
                        try {
                            handler.handle(method.getName(), args[0]);
                        } catch (final Throwable t) {
                            log("failed to handle " + method.getName() + ": " + t);
                        }
                    }

                    return defaultValue(method.getReturnType());
            }
        }));
    }

    private static Object defaultValue(final Class<?> type) {
        if (type == boolean.class) {
            return false;
        }

        if (type == int.class || type == long.class || type == float.class || type == double.class
                || type == short.class || type == byte.class) {
            return 0;
        }

        return null;
    }

    /**
     * 復号に失敗した packet を捨てて先へ進む decoder。vanilla の {@code PacketDecoder} と同じく
     * id を読んで {@code ConnectionProtocol.createPacket} に渡し、例外なら残りを読み飛ばす。
     */
    private static final class ForgivingDecoder extends ByteToMessageDecoder {
        @Override
        protected void decode(final ChannelHandlerContext context, final ByteBuf in, final List<Object> out) {
            if (in.readableBytes() == 0) {
                return;
            }

            final FriendlyByteBuf buf = new FriendlyByteBuf(in);
            final int id = buf.readVarInt();
            final ConnectionProtocol protocol = context.channel().attr(Connection.ATTRIBUTE_PROTOCOL).get();

            try {
                final Packet<?> packet = protocol.createPacket(PacketFlow.CLIENTBOUND, id, buf);

                if (packet != null && buf.readableBytes() == 0) {
                    out.add(packet);
                    return;
                }
            } catch (final Throwable ignored) {
                // レジストリが要る packet(ワールドの内容など)。捨てる
            }

            in.readerIndex(in.writerIndex());
        }
    }
}
