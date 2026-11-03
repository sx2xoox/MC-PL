package io.github.sx2xoox.mcpl.core.map.siege;

import java.util.List;

/**
 * The places of a 공성전 map the game needs (see docs/siege.md): where each side spawns and where the towers stand.
 * Coordinates are blocks relative to the map center on x/z and absolute on y, like everything an {@code ArenaMap}
 * builds. The map is asymmetric: the attackers come from one end, the defenders hold three towers in a row; the two
 * teams swap sides at half time on the same map.
 */
public interface SiegeSites {

    /** Where the attackers (re)spawn: three spots side by side, facing the defenders' side. */
    List<Spot> attackerSpawns();

    /**
     * Where the defenders (re)spawn, by the number of towers already destroyed: {@code get(0)} while all three stand,
     * {@code get(1)} after the front tower fell (further back), {@code get(2)} after the middle one fell (next to the
     * core). Three spots each.
     */
    List<List<Spot>> defenderSpawns();

    /** The towers in the order they must be destroyed: front, middle, core. */
    List<Tower> towers();

    /**
     * A standing place: the block the feet are in, and the direction to face.
     *
     * @param yaw Minecraft yaw in degrees (0 = +z)
     */
    record Spot(int x, int y, int z, float yaw) {
    }

    /**
     * A tower: the game puts its crystal (a display entity with a hitbox) at {@code x, y, z}, the centre of an open
     * platform the map leaves free of blocks within {@code radius} (the defence zone around it).
     *
     * @param id     stable id ({@code front}, {@code middle}, {@code core})
     * @param name   Korean name shown to players ("성문 탑")
     * @param radius blocks around the crystal that count as "near the tower" (defence bonus, capture)
     */
    record Tower(String id, String name, int x, int y, int z, int radius) {
    }
}
