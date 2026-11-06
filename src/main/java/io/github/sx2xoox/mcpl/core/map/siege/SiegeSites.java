package io.github.sx2xoox.mcpl.core.map.siege;

import java.util.List;

/**
 * The places of a 공성전 map the game needs (see docs/siege.md): where each side spawns and where the towers stand.
 * Coordinates are blocks relative to the map center on x/z and absolute on y, like everything an {@code ArenaMap}
 * builds. The map is asymmetric: the attackers come from the −x end (the towers face them), the defenders hold three
 * towers in a row; the two teams swap sides at half time on the same map.
 */
public interface SiegeSites {

    /** Where the attackers (re)spawn: at least three spots side by side, facing the defenders' side. */
    List<Spot> attackerSpawns();

    /**
     * Where the defenders (re)spawn, by the number of towers already destroyed: {@code get(0)} while all three stand,
     * {@code get(1)} after the front tower fell (further back), {@code get(2)} after the middle one fell (next to the
     * core). At least three spots each.
     */
    List<List<Spot>> defenderSpawns();

    /** The towers in the order they must be destroyed: front, middle, core. */
    List<Tower> towers();

    /**
     * Neutral objectives (docs/siege.md §12): capture points either team can take for experience. Optional: a map
     * without them plays without objectives. {@link Objective#major()} marks the big timed objective.
     */
    default List<Objective> objectives() {
        return List.of();
    }

    /**
     * Neutral monster camps (docs/siege.md §13), like jungle camps: monsters either team can kill for experience and,
     * for the bigger ones, a team buff; each comes back on its own timer. Optional: a map without them plays without
     * camps. The game decides the monsters by {@link Camp#kind()}.
     */
    default List<Camp> camps() {
        return List.of();
    }

    /**
     * The named districts the attackers pass, as x ranges (relative to the map center): entering one shows its name.
     * Default: none.
     */
    default List<District> districts() {
        return List.of();
    }

    /**
     * A district of the map: every place with {@code minX <= x <= maxX}.
     *
     * @param id   stable id ({@code slums}, {@code firewall}, {@code hub}, {@code hq})
     * @param name Korean name shown to players ("네온 빈민가")
     */
    record District(String id, String name, int minX, int maxX) {
    }

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

    /**
     * A capture point: players standing within {@code radius} blocks (horizontally, a few blocks up or down) of
     * {@code x, y, z} capture it. The map keeps the centre a free standing place.
     *
     * @param id     stable id ({@code north}, {@code canal}, {@code center})
     * @param name   Korean name shown to players ("북쪽 데이터 노드")
     * @param major  the big neutral objective that appears on a timer (otherwise a small data node)
     */
    record Objective(String id, String name, int x, int y, int z, int radius, boolean major) {
    }

    /**
     * A monster camp: the monsters stand around {@code x, y, z} (a free standing place) within {@code radius} blocks,
     * a flat open area the map keeps clear of blocks up to the monsters' height.
     *
     * @param id   stable id ({@code west-drones}, {@code red}, {@code guardian})
     * @param name Korean name shown to players ("드론 둥지")
     * @param kind which monsters live there: {@code drones}, {@code rats}, {@code scrap} (small camps), {@code red},
     *             {@code blue} (buff camps) or {@code guardian} (the epic boss)
     */
    record Camp(String id, String name, String kind, int x, int y, int z, int radius) {
    }
}
