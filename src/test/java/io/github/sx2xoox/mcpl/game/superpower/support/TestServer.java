package io.github.sx2xoox.mcpl.game.superpower.support;

import org.bukkit.WorldBorder;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldBorderMock;

/** MockBukkit server that can create per-player world borders (used by the hub's virtual border). */
@SuppressWarnings("unchecked")
public final class TestServer extends ServerMock {

    @Override
    public WorldBorder createWorldBorder() {
        return new WorldBorderMock(getWorlds().getFirst());
    }
}
