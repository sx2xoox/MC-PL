package io.github.sx2xoox.mcpl.core.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

import io.github.sx2xoox.mcpl.core.map.siege.SiegeLayout;
import io.github.sx2xoox.mcpl.core.map.siege.SiegeMap;
import io.github.sx2xoox.mcpl.core.map.siege.SiegeSites;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * 협곡 요새 is playable as a siege map: both sides can walk everywhere and back, every spawn spot and tower platform is
 * usable, each tower can be reached along three separate lanes, and no block misbehaves.
 */
class SiegeMapTest {

    /** Around a tower the lanes may merge (platform plus a ring to fight in). */
    private static final int MEET_RADIUS = SiegeLayout.TOWER_RADIUS + 6;

    private static SiegeMap map;
    private static MapCanvas canvas;
    private static MapAudit audit;
    private static MapAudit.Report report;
    private static Map<Long, MapAudit.Node> nodes;
    private static Set<MapAudit.Node> reachable;

    @BeforeAll
    static void build() {
        MockBukkit.mock();
        map = new SiegeMap();
        canvas = map.render();
        SiegeSites.Spot start = map.attackerSpawns().getFirst();
        // The audit area is a circle: make it cover the whole strip.
        int radius = (int) Math.ceil(Math.hypot(map.buildRadius(), SiegeLayout.HALF_WIDTH));
        audit = new MapAudit(canvas, radius, SiegeLayout::partOf);
        // (from an open column of the camp: the audit starts on the highest place of its column)
        report = audit.run(SiegeLayout.CAMP_FRONT - 2, 0);
        nodes = new HashMap<>();
        for (MapAudit.Node node : audit.nodes()) {
            nodes.put(key(node.x(), node.y(), node.z()), node);
        }
        reachable = flood(List.of(node(start)), (x, z) -> true);
        System.out.println("[siege] " + canvas.blockCount() + " blocks; " + report.summary());
    }

    @AfterAll
    static void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void theSitesAreComplete() {
        assertTrue(map.attackerSpawns().size() >= 8, "spots for up to 8 attackers");
        assertEquals(3, map.defenderSpawns().size(), "one defender spawn per towers-down stage");
        for (List<SiegeSites.Spot> stage : map.defenderSpawns()) {
            assertTrue(stage.size() >= 8, "spots for up to 8 defenders");
        }
        assertEquals(List.of("front", "middle", "core"), map.towers().stream().map(SiegeSites.Tower::id).toList());
        assertTrue(map.towers().get(0).x() < map.towers().get(1).x()
                && map.towers().get(1).x() < map.towers().get(2).x(), "the towers stand in a row away from the "
                + "attackers");
    }

    @Test
    void everySpawnSpotIsAFreeStandingPlaceReachableFromTheOtherSide() {
        List<SiegeSites.Spot> all = new ArrayList<>(map.attackerSpawns());
        map.defenderSpawns().forEach(all::addAll);
        Set<String> distinct = new HashSet<>();
        for (SiegeSites.Spot spot : all) {
            assertTrue(distinct.add(spot.x() + "," + spot.y() + "," + spot.z()), "two spots at " + spot);
            MapAudit.Node node = nodes.get(key(spot.x(), spot.y(), spot.z()));
            assertNotNull(node, "a player can stand at " + spot);
            assertTrue(reachable.contains(node), "reachable from the attackers' spawn: " + spot);
        }
        // And back: the defenders reach the attackers' camp from each of their spawns.
        for (List<SiegeSites.Spot> stage : map.defenderSpawns()) {
            Set<MapAudit.Node> from = flood(List.of(node(stage.getFirst())), (x, z) -> true);
            assertTrue(from.contains(node(map.attackerSpawns().getFirst())), "defenders reach the attackers' camp");
        }
    }

    @Test
    void theTowerPlatformsAreOpenAndReachable() {
        for (SiegeSites.Tower tower : map.towers()) {
            int r = tower.radius();
            assertTrue(r >= 5 && r <= 7, "platform radius " + r);
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dz * dz > r * r) {
                        continue;
                    }
                    int x = tower.x() + dx;
                    int z = tower.z() + dz;
                    BlockSpec floor = canvas.get(x, tower.y() - 1, z);
                    assertTrue(floor != null && MapBlocks.isStandable(floor), tower.id() + ": floor at " + x + ","
                            + z);
                    for (int y = tower.y(); y <= tower.y() + 10; y++) {
                        assertTrue(!canvas.isSolid(x, y, z), tower.id() + ": the platform is open at " + x + ","
                                + y + "," + z + " (" + canvas.get(x, y, z) + ")");
                    }
                }
            }
            assertTrue(reachable.contains(nodes.get(key(tower.x(), tower.y(), tower.z()))),
                    tower.id() + " can be reached");
        }
    }

    @Test
    void everyGroundPlaceCanBeReachedAndLeft() {
        assertTrue(report.problems().isEmpty(), "no floating sand, spilling water, lava or fire:\n"
                + report.summary());
        // Places nobody can reach are only allowed up high (roofs, merlons, crates, tree tops, the cliffs).
        List<MapAudit.Node> low = new ArrayList<>();
        for (MapAudit.Node node : audit.nodes()) {
            if (!reachable.contains(node) && SiegeLayout.isInterior(node.x(), node.z())
                    && node.y() <= ground(node.x(), node.z()) + 2) {
                low.add(node);
            }
        }
        assertTrue(low.size() <= 20, "ground places cut off: " + low.size() + " e.g. "
                + low.subList(0, Math.min(10, low.size())));
        assertTrue(report.strandedShare() <= 0.002, "no pits you cannot leave:\n" + report.summary());
        for (MapAudit.Component part : report.stranded()) {
            assertTrue(part.size() <= 12, "no pit larger than 12 places:\n" + report.summary());
        }
    }

    @Test
    void nobodyCanWalkOffTheMap() {
        for (MapAudit.Node node : reachable) {
            assertTrue(SiegeLayout.isInterior(node.x(), node.z()), "a reachable place on the cliffs: " + node);
        }
    }

    @Test
    void eachTowerCanBeReachedAlongThreeLanes() {
        String[] names = {"north", "main", "south"};
        List<BiPredicate<Integer, Integer>> bands = List.of(
                (x, z) -> z <= -SiegeLayout.LANE_FLANK,
                (x, z) -> Math.abs(z) <= SiegeLayout.LANE_MAIN,
                (x, z) -> z >= SiegeLayout.LANE_FLANK);
        List<SiegeSites.Tower> towers = map.towers();
        for (int t = 0; t < towers.size(); t++) {
            List<SiegeSites.Tower> passed = towers.subList(0, t);
            SiegeSites.Tower target = towers.get(t);
            for (int lane = 0; lane < 3; lane++) {
                BiPredicate<Integer, Integer> band = bands.get(lane);
                BiPredicate<Integer, Integer> allowed = (x, z) -> x <= SiegeLayout.CAMP_FRONT
                        || band.test(x, z) || near(passed, x, z) || near(List.of(target), x, z);
                Set<MapAudit.Node> seen = flood(List.of(node(map.attackerSpawns().getFirst())), allowed);
                assertTrue(seen.contains(nodes.get(key(target.x(), target.y(), target.z()))),
                        "the " + names[lane] + " lane reaches the " + target.id() + " tower");
            }
        }
    }

    @Test
    void aFullPushIsALongWalk() {
        SiegeSites.Tower core = map.towers().getLast();
        int steps = distance(node(map.attackerSpawns().getFirst()), nodes.get(key(core.x(), core.y(), core.z())));
        System.out.println("[siege] attackers' spawn to the core: " + steps + " blocks");
        assertTrue(steps >= 120 && steps <= 175, "spawn to core is " + steps + " blocks");
    }

    // ------------------------------------------------------------------ helpers

    private static boolean near(List<SiegeSites.Tower> towers, int x, int z) {
        for (SiegeSites.Tower tower : towers) {
            int dx = x - tower.x();
            int dz = z - tower.z();
            if (dx * dx + dz * dz <= MEET_RADIUS * MEET_RADIUS) {
                return true;
            }
        }
        return false;
    }

    private static int ground(int x, int z) {
        return SiegeLayout.ground(x, z);
    }

    private static MapAudit.Node node(SiegeSites.Spot spot) {
        MapAudit.Node node = nodes.get(key(spot.x(), spot.y(), spot.z()));
        assertNotNull(node, "a player can stand at " + spot);
        return node;
    }

    private static Set<MapAudit.Node> flood(List<MapAudit.Node> starts, BiPredicate<Integer, Integer> allowed) {
        Set<MapAudit.Node> seen = new HashSet<>(starts);
        ArrayDeque<MapAudit.Node> queue = new ArrayDeque<>(starts);
        while (!queue.isEmpty()) {
            for (MapAudit.Node to : audit.moves(queue.poll())) {
                if (allowed.test(to.x(), to.z()) && seen.add(to)) {
                    queue.add(to);
                }
            }
        }
        return seen;
    }

    /** Steps (moves) on the shortest walk between two places. */
    private static int distance(MapAudit.Node from, MapAudit.Node to) {
        Map<MapAudit.Node, Integer> dist = new HashMap<>();
        ArrayDeque<MapAudit.Node> queue = new ArrayDeque<>();
        dist.put(from, 0);
        queue.add(from);
        while (!queue.isEmpty()) {
            MapAudit.Node node = queue.poll();
            if (node.equals(to)) {
                return dist.get(node);
            }
            for (MapAudit.Node next : audit.moves(node)) {
                if (!dist.containsKey(next)) {
                    dist.put(next, dist.get(node) + 1);
                    queue.add(next);
                }
            }
        }
        return Integer.MAX_VALUE;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x + 4096) << 32) | ((long) (z + 4096) << 12) | (y + 2048);
    }
}
