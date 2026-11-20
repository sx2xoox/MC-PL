package io.github.sx2xoox.speedrunbot.server;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.Packet;

/**
 * A connection with no client behind it: packets to the "client" are dropped. Idea from Carpet mod's
 * NetworkManagerFake (MIT, gnembon); written anew for 1.16.1 Yarn names.
 */
public final class FakeConnection extends ClientConnection {
    public FakeConnection() {
        super(NetworkSide.SERVERBOUND);
    }

    @Override
    public void send(Packet<?> packet, GenericFutureListener<? extends Future<? super Void>> callback) {
    }

    @Override
    public void disableAutoRead() {
    }

    @Override
    public void handleDisconnection() {
    }
}
