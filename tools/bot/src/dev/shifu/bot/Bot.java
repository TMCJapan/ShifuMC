// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.bot;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.BitSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.HashedStack;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.BundlerInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.CommonPacketTypes;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.configuration.ClientConfigurationPacketListener;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ServerboundSelectKnownPacks;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.network.protocol.login.ClientLoginPacketListener;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ClientboundLoginCompressionPacket;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundLoginAcknowledgedPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.network.EventLoopGroupHolder;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.phys.Vec3;

/**
 * プレイヤー経路を通すためのヘッドレスクライアント。
 *
 * <p>サーバー自身の {@code Connection} と packet の型で接続する。
 * クライアント側の listener は動的 Proxy で作り、要る packet だけ扱う。
 * 受信の復号はサーバーの codec をそのまま使い、レジストリが要る packet
 * (ワールドの内容など)は復号に失敗しても捨てて先へ進む。
 *
 * <p>サーバー側の検証プラグイン({@code tools/probe})が chat で
 * {@code !bot <指示>} を送り、それに従って packet を送る。
 *
 * <pre>
 * java -cp <versions/26.2/paper-26.2.jar;libraries/**> dev.shifu.bot.Bot [host] [port] [name] [seconds]
 * </pre>
 */
public final class Bot {
    private static final Packet<PacketListener> IGNORED = new Packet<>() {
        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public PacketType<? extends Packet<PacketListener>> type() {
            return (PacketType) CommonPacketTypes.CLIENTBOUND_KEEP_ALIVE;
        }

        @Override
        public void handle(final PacketListener listener) {
        }
    };

    private final String name;
    private final CountDownLatch done = new CountDownLatch(1);
    private Connection connection;
    private ClientGamePacketListener game;
    private volatile double x;
    private volatile double y;
    private volatile double z;
    private volatile float yaw;
    private volatile float pitch;
    private volatile boolean loaded;
    private volatile int teleports;

    private Bot(final String name) {
        this.name = name;
    }

    public static void main(final String[] args) throws Exception {
        final String host = args.length > 0 ? args[0] : "127.0.0.1";
        final int port = args.length > 1 ? Integer.parseInt(args[1]) : 25599;
        final String name = args.length > 2 ? args[2] : "ShifuBot";
        final long seconds = args.length > 3 ? Long.parseLong(args[3]) : 90;

        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        final Bot bot = new Bot(name);
        bot.connect(host, port);

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

    static void log(final String text) {
        System.out.println("[bot] " + text);
        System.out.flush();
    }

    // ------------------------------------------------------------ 接続

    private void connect(final String host, final int port) {
        this.connection = new Connection(PacketFlow.CLIENTBOUND);
        final ChannelFuture future = Connection.connect(
                new InetSocketAddress(host, port), EventLoopGroupHolder.remote(false), this.connection);
        future.syncUninterruptibly();
        log("connected to " + host + ":" + port);

        final ClientLoginPacketListener login = this.listener(ClientLoginPacketListener.class, ConnectionProtocol.LOGIN, this::onLogin);
        this.connection.initiateServerboundPlayConnection(host, port, login);
        this.connection.send(new ServerboundHelloPacket(this.name,
                UUID.nameUUIDFromBytes(("OfflinePlayer:" + this.name).getBytes(StandardCharsets.UTF_8))));
    }

    private void onLogin(final String method, final Object packet) {
        switch (method) {
            case "handleCompression" -> this.connection.setupCompression(((ClientboundLoginCompressionPacket) packet).getCompressionThreshold(), false);
            case "handleLoginFinished" -> {
                log("login finished");
                final ClientConfigurationPacketListener config = this.listener(
                        ClientConfigurationPacketListener.class, ConnectionProtocol.CONFIGURATION, this::onConfiguration);
                this.connection.setupInboundProtocol(ConfigurationProtocols.CLIENTBOUND, config);
                this.connection.send(ServerboundLoginAcknowledgedPacket.INSTANCE);
                this.connection.setupOutboundProtocol(ConfigurationProtocols.SERVERBOUND);
                this.connection.send(new ServerboundClientInformationPacket(ClientInformation.createDefault()));
            }
            case "handleCustomQuery" -> this.connection.send(
                    new ServerboundCustomQueryAnswerPacket(((ClientboundCustomQueryPacket) packet).transactionId(), null));
            case "handleDisconnect" -> {
                log("disconnected while logging in: " + ((ClientboundLoginDisconnectPacket) packet).reason().getString());
                this.done.countDown();
            }
            case "handleHello" -> log("server asked for encryption; the server must be in offline mode");
            default -> {
            }
        }
    }

    private void onConfiguration(final String method, final Object packet) {
        switch (method) {
            case "handleKeepAlive" -> this.connection.send(new ServerboundKeepAlivePacket(((ClientboundKeepAlivePacket) packet).getId()));
            case "handlePing" -> this.connection.send(new ServerboundPongPacket(((ClientboundPingPacket) packet).getId()));
            case "handleSelectKnownPacks" -> this.connection.send(new ServerboundSelectKnownPacks(List.of()));
            case "handleConfigurationFinished" -> {
                log("configuration finished");
                final RegistryAccess access = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
                this.game = this.listener(ClientGamePacketListener.class, ConnectionProtocol.PLAY, this::onGame);
                this.connection.setupInboundProtocol(
                        forgiving(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(access))), this.game);
                this.connection.send(ServerboundFinishConfigurationPacket.INSTANCE);
                this.connection.setupOutboundProtocol(
                        GameProtocols.SERVERBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(access), () -> false));
            }
            case "handleDisconnect" -> {
                log("disconnected while configuring: " + ((ClientboundDisconnectPacket) packet).reason().getString());
                this.done.countDown();
            }
            default -> {
            }
        }
    }

    private void onGame(final String method, final Object packet) throws Exception {
        switch (method) {
            case "handleBundlePacket" -> {
                for (Packet<? super ClientGamePacketListener> sub : ((BundlePacket<ClientGamePacketListener>) packet).subPackets()) {
                    sub.handle(this.game);
                }
            }
            case "handleKeepAlive" -> this.connection.send(new ServerboundKeepAlivePacket(((ClientboundKeepAlivePacket) packet).getId()));
            case "handlePing" -> this.connection.send(new ServerboundPongPacket(((ClientboundPingPacket) packet).getId()));
            case "handleLogin" -> log("joined the game");
            case "handleMovePlayer" -> this.accept((ClientboundPlayerPositionPacket) packet);
            case "handleSetHealth" -> log("health " + ((ClientboundSetHealthPacket) packet).getHealth());
            case "handlePlayerCombatKill" -> {
                log("died: " + ((ClientboundPlayerCombatKillPacket) packet).message().getString());
                Thread.sleep(200);
                this.connection.send(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
                log("sent respawn");
            }
            case "handleSystemChat" -> this.command(((ClientboundSystemChatPacket) packet).content().getString());
            case "handleDisconnect" -> {
                log("disconnected: " + ((ClientboundDisconnectPacket) packet).reason().getString());
                this.done.countDown();
            }
            default -> {
            }
        }
    }

    /** サーバーから届いた位置を受け入れ、vanilla のクライアントと同じく位置の packet を返す。 */
    private void accept(final ClientboundPlayerPositionPacket packet) {
        final PositionMoveRotation change = packet.change();
        final Set<Relative> relatives = packet.relatives();
        this.x = relatives.contains(Relative.X) ? this.x + change.position().x : change.position().x;
        this.y = relatives.contains(Relative.Y) ? this.y + change.position().y : change.position().y;
        this.z = relatives.contains(Relative.Z) ? this.z + change.position().z : change.position().z;
        this.yaw = relatives.contains(Relative.Y_ROT) ? this.yaw + change.yRot() : change.yRot();
        this.pitch = relatives.contains(Relative.X_ROT) ? this.pitch + change.xRot() : change.xRot();
        this.teleports++;
        log(String.format("teleport #%d to %.2f %.2f %.2f", this.teleports, this.x, this.y, this.z));
        this.connection.send(new ServerboundAcceptTeleportationPacket(packet.id()));
        this.connection.send(new ServerboundMovePlayerPacket.PosRot(new Vec3(this.x, this.y, this.z), this.yaw, this.pitch, false, false));

        if (!this.loaded) {
            this.loaded = true;
            this.connection.send(new ServerboundPlayerLoadedPacket());
        }
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
                    this.connection.send(new ServerboundMovePlayerPacket.PosRot(new Vec3(this.x, this.y, this.z), this.yaw, this.pitch, false, false));
                    Thread.sleep(50);
                }
                log(String.format("moved to %.2f %.2f %.2f", this.x, this.y, this.z));
            }
            case "chat" -> this.connection.send(new ServerboundChatPacket(rest, Instant.now(), 0L, null,
                    new LastSeenMessages.Update(0, new BitSet(20), LastSeenMessages.Update.IGNORE_CHECKSUM)));
            // コマンド。チャットとは別の packet
            case "cmd" -> this.connection.send(
                    new net.minecraft.network.protocol.game.ServerboundChatCommandPacket(rest));
            case "click" -> this.connection.send(new ServerboundContainerClickPacket(0, 0, (short) Integer.parseInt(rest), (byte) 0,
                    ContainerInput.PICKUP, new Int2ObjectOpenHashMap<>(), HashedStack.EMPTY));
            case "drop" -> this.connection.send(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.DROP_ITEM, BlockPos.ZERO, Direction.DOWN, 0));
            case "respawn" -> this.connection.send(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
            case "fly" -> {
                // 飛行の切り替え。サーバーは flying だけを読む
                final net.minecraft.world.entity.player.Abilities abilities = new net.minecraft.world.entity.player.Abilities();
                abilities.flying = Boolean.parseBoolean(rest);
                this.connection.send(new net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket(abilities));
            }
            case "eat" -> this.connection.send(new net.minecraft.network.protocol.game.ServerboundUseItemPacket(
                    net.minecraft.world.InteractionHand.MAIN_HAND, 1, 0.0F, 0.0F));
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
            // 名乗り(minecraft:brand)。vanilla の codec が本文を書く唯一のプラグインメッセージ。
            // 知らないチャンネルは DiscardedPayload になり、vanilla の codec は本文を書かないので送れない
            case "brand" -> this.connection.send(new net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket(
                    new net.minecraft.network.protocol.common.custom.BrandPayload(rest)));
            case "where" -> log(String.format("at %.2f %.2f %.2f", this.x, this.y, this.z));
            case "quit" -> {
                this.connection.disconnect(Component.literal("done"));
                this.done.countDown();
            }
            default -> log("unknown command " + verb);
        }
    }

    private static BlockPos blockPos(final String text) {
        final String[] parts = text.trim().split("\\s+");

        return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }

    // ------------------------------------------------------------ 仕掛け

    interface Handler {
        void handle(String method, Object packet) throws Exception;
    }

    /** 要る packet だけ扱う listener。それ以外の handle は何もしない。 */
    private <T extends PacketListener> T listener(final Class<T> type, final ConnectionProtocol protocol, final Handler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "flow":
                    return PacketFlow.CLIENTBOUND;
                case "protocol":
                    return protocol;
                case "isAcceptingMessages":
                case "shouldHandleMessage":
                    return true;
                case "shouldPropagateHandlingExceptions":
                    return false;
                case "createDisconnectionInfo":
                    return new DisconnectionDetails((Component) args[0]);
                case "onDisconnect":
                    log("connection closed: " + ((DisconnectionDetails) args[0]).reason().getString());
                    this.done.countDown();
                    return null;
                case "onPacketError":
                    log("packet error: " + args[1]);
                    return null;
                case "toString":
                    return "Bot(" + protocol + ")";
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

    /** 復号に失敗した packet を捨てて先へ進む codec。 */
    private static <T extends PacketListener> ProtocolInfo<T> forgiving(final ProtocolInfo<T> real) {
        final StreamCodec<ByteBuf, Packet<? super T>> codec = new StreamCodec<>() {
            @Override
            @SuppressWarnings("unchecked")
            public Packet<? super T> decode(final ByteBuf buf) {
                try {
                    return real.codec().decode(buf);
                } catch (final Throwable t) {
                    buf.readerIndex(buf.writerIndex());

                    return (Packet<? super T>) IGNORED;
                }
            }

            @Override
            public void encode(final ByteBuf buf, final Packet<? super T> packet) {
                real.codec().encode(buf, packet);
            }
        };

        return new ProtocolInfo<>() {
            @Override
            public ConnectionProtocol id() {
                return real.id();
            }

            @Override
            public PacketFlow flow() {
                return real.flow();
            }

            @Override
            public StreamCodec<ByteBuf, Packet<? super T>> codec() {
                return codec;
            }

            @Override
            public BundlerInfo bundlerInfo() {
                return real.bundlerInfo();
            }
        };
    }
}
