// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.event;

import java.nio.charset.StandardCharsets;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * プラグインメッセージ(Bukkit の Messenger)の受け口。
 *
 * <p>この版の {@code ServerboundCustomPayloadPacket} は本文を自分で持っている({@code getData})ので、
 * 新しい版のように復号の途中で本文の控えを取る仕組みは要らない。
 *
 * <p><b>プラグインが 1 つも入っていなければ何もしない。</b>そのときに実行される命令列は vanilla と同じ。
 */
public final class PluginMessages {
    private static final ResourceLocation REGISTER = new ResourceLocation("register");
    private static final ResourceLocation UNREGISTER = new ResourceLocation("unregister");

    private PluginMessages() {
    }

    /** プラグインが 1 つでも入っているか。入っていなければ vanilla のまま何もしない。 */
    private static boolean enabled() {
        final net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();

        return server != null && server.server != null && server.server.getPluginManager().getPlugins().length != 0;
    }

    /**
     * {@code minecraft:register} / {@code unregister}(チャンネルの登録)。
     * {@code ServerboundCustomPayloadPacket.handle} の先頭、listener の {@code handleCustomPayload} を
     * 呼ぶ前に呼ぶ(patches/wire/net-minecraft-network-protocol-game-ServerboundCustomPayloadPacket.rules)。
     *
     * <p>Fabric API は {@code handleCustomPayload} の先頭に取り消せる inject を置き、register / unregister を
     * 自分のチャンネル登録として受けて取り消す。{@link #handle} はその後ろに差してあるので届かず、
     * Fabric API が入っていると CraftPlayer のチャンネルが 1 つも登録されず、sendPluginMessage が
     * 送らずに捨てていた。packet の {@code handle} は {@code handleCustomPayload} より前に動くので、
     * ここなら Fabric API より先に見られる。Fabric API が無くても同じ経路を通る。
     *
     * <p>本文は {@code handle} が戻ったところで release されるので、ここで写してからサーバースレッドへ回す。
     * ほかのプラグインメッセージも {@link #handle} がサーバースレッドの同じ待ち行列へ回すので、順番は届いた順のまま。
     *
     * <p>読んだ位置(javap): fabric-api-0.77.0+1.18.2.jar の fabric-networking-api-v1-0.77.0.jar
     *   net/fabricmc/fabric/mixin/networking/ServerPlayNetworkHandlerMixin.handleCustomPayloadReceivedAsync
     *   ({@code @Inject(method = "onCustomPayload", at = @At("HEAD"), cancellable = true)}、
     *   {@code addon.handle(packet)} が true なら cancel)と
     *   net/fabricmc/fabric/impl/networking/AbstractChanneledNetworkAddon.handle
     *   ({@code REGISTER_CHANNEL} / {@code UNREGISTER_CHANNEL} なら receiveRegistration して true)。
     */
    public static void registration(final ServerGamePacketListener listener, final ServerboundCustomPayloadPacket packet) {
        final ResourceLocation identifier = packet.getIdentifier();
        final boolean register = REGISTER.equals(identifier);

        if ((!register && !UNREGISTER.equals(identifier)) || !enabled()
                || !(listener instanceof ServerGamePacketListenerImpl game)) {
            return;
        }

        final FriendlyByteBuf body = packet.getData();
        final byte[] data = new byte[body.readableBytes()];
        body.getBytes(body.readerIndex(), data);

        net.minecraft.server.MinecraftServer.getServer().execute(() -> {
            // PacketUtils.ensureRunningOnSameThread と同じく接続が切れていれば何もしない
            if (!game.getConnection().isConnected()) {
                return;
            }

            final org.bukkit.craftbukkit.entity.CraftPlayer bukkit = game.player.getBukkitEntity();

            try {
                for (final String channel : new String(data, StandardCharsets.UTF_8).split("\0")) {
                    if (channel.isEmpty()) {
                        continue;
                    }

                    if (register) {
                        bukkit.addChannel(channel);
                    } else {
                        bukkit.removeChannel(channel);
                    }
                }
            } catch (final RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(PluginMessages.class)
                        .error("チャンネル {} のプラグインメッセージを処理できない", identifier, e);
            }
        });
    }

    /**
     * {@code handleCustomPayload}(vanilla は空)の中身。
     *
     * <p>Paper と同じことをする: Bukkit の Messenger へ渡す。{@code minecraft:register} /
     * {@code unregister} は {@link #registration} が受けているので、ここでは何もしない。
     *
     * <p>Paper は読めない本文で接続を切るが、vanilla は何もしないので切らずに記録だけ残す。
     *
     * <p>{@code minecraft:brand} は Paper の {@code clientBrandName} が private で、
     * 外から書けないため渡していない。
     *
     * <p>読んだ位置:
     *   Paper-Server@HEAD src/main/java/net/minecraft/server/network/ServerGamePacketListenerImpl.java:3545
     */
    public static void handle(final ServerGamePacketListenerImpl listener, final ServerboundCustomPayloadPacket packet) {
        if (!enabled() || REGISTER.equals(packet.getIdentifier()) || UNREGISTER.equals(packet.getIdentifier())) {
            return;
        }

        // vanilla は何もしない packet なので、ここから先はサーバースレッドで行う。
        net.minecraft.network.protocol.PacketUtils.ensureRunningOnSameThread(packet, listener,
                listener.player.getLevel());

        final ResourceLocation identifier = packet.getIdentifier();
        final FriendlyByteBuf body = packet.getData();
        final byte[] data = new byte[body.readableBytes()];
        body.readBytes(data);

        final org.bukkit.craftbukkit.entity.CraftPlayer bukkit = listener.player.getBukkitEntity();

        try {
            net.minecraft.server.MinecraftServer.getServer().server.getMessenger()
                    .dispatchIncomingMessage(bukkit, identifier.toString(), data);
        } catch (final RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(PluginMessages.class)
                    .error("チャンネル {} のプラグインメッセージを処理できない", identifier, e);
        }
    }
}
