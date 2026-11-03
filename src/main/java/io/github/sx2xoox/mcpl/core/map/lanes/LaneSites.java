package io.github.sx2xoox.mcpl.core.map.lanes;

import java.util.List;

/**
 * The places of a two-lane, two-base 공성전 map (see docs/siege-v2.md) the game reads: the map is symmetric, the BLUE
 * base on the −x end and the RED base on the +x end, a TOP lane (−z, curved) and a BOTTOM lane (+z, curved) between
 * them, jungle between the lanes and one epic objective pit in the centre. Coordinates are blocks relative to the map
 * centre on x/z and absolute on y, like everything an {@code ArenaMap} builds.
 */
public interface LaneSites {

    enum Side { BLUE, RED }

    enum Lane { TOP, BOTTOM }

    /** The six jungle objective kinds every side has one of, plus the centre epic (docs/siege-v2.md §4). */
    enum ObjectiveKind {
        /** Small camp A: 젤리 무리 (jelly slimes), solo-able. */
        JELLY,
        /** Small camp B: 태엽 쥐 떼 (wind-up mice), solo-able. */
        MICE,
        /** Attack buff: 곰인형 대장 (teddy bear captain). */
        TEDDY,
        /** Cooldown buff: 시계 토끼 (clockwork rabbit). */
        RABBIT,
        /** Capture point: 사탕 샘물 (candy fountain) heals the team. */
        FOUNTAIN,
        /** Capture point: 망원경 탑 (telescope tower) reveals nearby enemies. */
        TELESCOPE,
        /** The centre epic only: 장난감 드래곤 (toy dragon). */
        DRAGON
    }

    /** Where a side (re)spawns: at least 8 spots in its base, facing the enemy base. */
    List<Spot> spawns(Side side);

    /** The shop terminal of a side (in its base, next to the spawns). */
    Spot shop(Side side);

    /** A side's towers on a lane, outer first (2 per lane). */
    List<Structure> towers(Side side, Lane lane);

    /** A side's core (넥서스): destroying it ends the round at once. */
    Structure core(Side side);

    /**
     * The walking path of a lane from the BLUE base to the RED base (minions of RED walk it reversed): waypoints at most
     * ~12 blocks apart, every one on walkable ground, passing through each lane tower's radius.
     */
    List<Point> lanePath(Lane lane);

    /** A side's six jungle objective sites, one of each {@link ObjectiveKind}. */
    List<Objective> objectives(Side side);

    /** The centre epic objective (장난감 드래곤 pit). */
    Objective epic();

    /** Named regions for "you entered …" titles; may be empty. */
    default List<Region> regions() {
        return List.of();
    }

    /** A standing place: the block the feet are in, and the direction to face (Minecraft yaw, 0 = +z). */
    record Spot(int x, int y, int z, float yaw) {
    }

    /** A walkable waypoint (feet block). */
    record Point(int x, int y, int z) {
    }

    /**
     * A tower or core: the game puts its model and hitbox at {@code x, y, z} (feet level of a flat, open platform the
     * map keeps clear within {@code radius} and 20 blocks up).
     */
    record Structure(String id, String name, int x, int y, int z, int radius) {
    }

    /** A jungle or epic objective: an open, flat area of {@code radius} around {@code x, y, z} (feet level). */
    record Objective(String id, ObjectiveKind kind, String name, int x, int y, int z, int radius) {
    }

    /** A named rectangle of the map (x/z bounds, inclusive). */
    record Region(String id, String name, int minX, int maxX, int minZ, int maxZ) {
    }
}
