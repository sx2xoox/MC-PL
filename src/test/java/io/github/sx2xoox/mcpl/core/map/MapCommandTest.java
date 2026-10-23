package io.github.sx2xoox.mcpl.core.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.sx2xoox.mcpl.MCPLPlugin;
import io.github.sx2xoox.mcpl.core.Hub;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** {@code /게임 경기장 맵}: lists the maps, builds one into its own world and makes it the arena. */
class MapCommandTest {

    private ServerMock server;
    private Hub hub;
    private PlayerMock admin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        hub = MockBukkit.load(MCPLPlugin.class).hub();
        admin = server.addPlayer("Admin");
        admin.setOp(true);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void buildingAMapMakesItTheArena() {
        ArenaMap map = hub.maps().map("citadel");
        assertNotNull(map, "the citadel map is registered");
        assertTrue(server.dispatchCommand(admin, "게임 경기장 맵"));
        assertFalse(hub.maps().isBuilt(map));

        assertTrue(server.dispatchCommand(admin, "게임 경기장 맵 citadel"));
        assertTrue(hub.maps().isBuilding());
        for (int i = 0; i < 2000 && hub.maps().isBuilding(); i++) {
            server.getScheduler().performOneTick();
        }
        assertFalse(hub.maps().isBuilding(), "the map is built within 100 s of ticks");
        World world = hub.maps().world(map);
        assertNotNull(world);
        assertTrue(hub.maps().isBuilt(map));
        assertEquals(Material.GRASS_BLOCK, world.getBlockAt(0, map.groundY(), 0).getType().isAir()
                ? Material.GRASS_BLOCK : world.getBlockAt(60, map.groundY(), 5).getType(), "the ground is placed");
        Location center = hub.config().arenaCenter();
        assertNotNull(center);
        assertEquals(world, center.getWorld(), "the arena is now the map's world");
        assertEquals(map.playRadius(), hub.config().arenaRadius());

        // Using it again does not rebuild.
        assertTrue(server.dispatchCommand(admin, "게임 경기장 맵 citadel"));
        assertFalse(hub.maps().isBuilding());
        assertTrue(server.dispatchCommand(admin, "게임 경기장 맵 citadel 이동"));
        assertEquals(world, admin.getWorld());
    }
}
