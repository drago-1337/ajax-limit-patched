package com.minecraftcombatai.novemberajax;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

import java.util.function.Consumer;

/**
 * The practice bot's "network card" on the integrated server. Nothing is encoded or written and
 * there is no Netty channel: each packet the server sends the bot is handed, as the live object,
 * to {@link PracticeBot}, which only cares about its own knockback. It reports itself connected
 * so vanilla treats the bot as an ordinary logged-in player.
 *
 * It is never registered with the server's connection listener, so vanilla never ticks it: no
 * keep-alive, no inbound packets, and no client-authoritative position snap-back. The bot runs
 * its own physics (PracticeBot#tick).
 */
final class BotConnection extends Connection {
    private final Consumer<Packet<?>> sink;
    private volatile boolean open = true;

    BotConnection(Consumer<Packet<?>> sink) {
        super(PacketFlow.SERVERBOUND);
        this.sink = sink;
    }

    @Override
    public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
        deliver(packet);
    }

    private void deliver(Packet<?> packet) {
        if (packet instanceof BundlePacket<?> bundle) {
            for (Packet<?> sub : bundle.subPackets()) deliver(sub);
            return;
        }
        try {
            sink.accept(packet);
        } catch (RuntimeException ignored) {
            // a bot's packet handling must never take the server tick down
        }
    }

    @Override
    public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocol, T packetListener) {
    }

    @Override
    public void setupOutboundProtocol(ProtocolInfo<?> protocol) {
    }

    @Override
    public void flushChannel() {
    }

    @Override
    public void setReadOnly() {
    }

    @Override
    public boolean isConnected() {
        return open;
    }

    @Override
    public void disconnect(DisconnectionDetails details) {
        open = false;
    }

    @Override
    public void handleDisconnection() {
    }
}

