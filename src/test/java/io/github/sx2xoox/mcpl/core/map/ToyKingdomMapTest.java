package io.github.sx2xoox.mcpl.core.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites.Lane;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites.ObjectiveKind;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites.Side;
import io.github.sx2xoox.mcpl.core.map.toykingdom.ToyHall;
import io.github.sx2xoox.mcpl.core.map.toykingdom.ToyKingdomMap;
import io.github.sx2xoox.mcpl.core.map.toykingdom.ToyLayout;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * 장난감 왕국 is playable as a two-lane siege map and stays calm: both sides walk everywhere and back, every site of the
 * {@link LaneSites} contract is open, flat and reachable, the lane paths are walkable, both halves are mirror images,
 * each zone keeps to a few block types, and no block misbehaves.
 */
class ToyKingdomMapTest {

    /**
     * Most distinct block types (materials) a zone may use: the floorboards, the three mints of the play mat's puzzle
     * tiles and a handful of toy colours (the lanes two more: the damp shade of the sponge bushes and the brighter
     * brick of the road). The jungle has three more (a rug per camp, its soft toys: cotton candy, jelly, and the glass
     * of the marble jar).
     */
    private static final int PALETTE_BUDGET = 12;
    private static final int JUNGLE_PALETTE_BUDGET = 13;
    /**
     * Detail blocks shape the props (stairs, slabs, fences, trapdoors, buttons, chains, lanterns, candles, carpets,
     * banners, pots, rods) and are counted apart: they add shapes in a second tone of the toy colours (quartz,
     * cherry, bamboo, birch, red nether brick) and small trinkets, not new colours. Three more than the props need:
     * the flat toy pieces and camp confetti left on the mat are carpets in all four toy colours.
     */
    private static final int DETAIL_BUDGET = 21;
    private static final int MAP_DETAIL_BUDGET = 31;
    /**
     * The clockwork hall around the kingdom is architecture, not a play area: cream (walls, pillars), crimson (bricks,
     * rails, banners), gold (two golds, light blocks, lanterns, chains) and the glass of the vault and the clock (three
     * colours), plus the team colours of the clock hands' tips. Its budget is separate (details included) so the
     * kingdom stays calm.
     */
    private static final int HALL_PALETTE_BUDGET = 16;

    private static ToyKingdomMap map;
    private static MapCanvas canvas;
    private static MapAudit audit;
    private static MapAudit.Report report;
    private static Map<Long, MapAudit.Node> nodes;
    private static Set<MapAudit.Node> reachable;
    private static List<String> blockFindings;

    @BeforeAll
    static void build() {
        MockBukkit.mock();
        map = new ToyKingdomMap();
        long started = System.nanoTime();
        canvas = map.render();
        System.out.println("[toykingdom] built in " + (System.nanoTime() - started) / 1_000_000 + " ms");
        LaneSites.Spot start = map.spawns(Side.BLUE).getFirst();
        int radius = (int) Math.ceil(Math.hypot(map.buildRadius(), ToyLayout.HALF_Z + 8));
        audit = new MapAudit(canvas, radius, ToyLayout::partOf);
        report = audit.run(start.x(), start.y(), start.z());
        blockFindings = new MapBlockChecks(canvas, ToyLayout::partOf).run();
        nodes = new HashMap<>();
        for (MapAudit.Node node : audit.nodes()) {
            nodes.put(key(node.x(), node.y(), node.z()), node);
        }
        reachable = flood(node(start));
        System.out.println("[toykingdom] " + canvas.blockCount() + " blocks; " + report.summary());
        for (Side side : Side.values()) {
            System.out.println("[toykingdom] sites " + side + ": spawns " + map.spawns(side) + " shop " + map.shop(side)
                    + " core " + map.core(side) + " top " + map.towers(side, Lane.TOP) + " bottom "
                    + map.towers(side, Lane.BOTTOM) + " objectives " + map.objectives(side));
        }
        System.out.println("[toykingdom] sites epic " + map.epic() + " top path " + map.lanePath(Lane.TOP)
                + " bottom path " + map.lanePath(Lane.BOTTOM));
        blockFindings.forEach(f -> System.out.println("[toykingdom] block: " + f));
    }

    @AfterAll
    static void tearDown() {
        MockBukkit.unmock();
    }

    // ------------------------------------------------------------------ the contract

    @Test
    void theSitesAreComplete() {
        for (Side side : Side.values()) {
            assertTrue(map.spawns(side).size() >= 8, "spots for up to 8 players of " + side);
            for (Lane lane : Lane.values()) {
                assertEquals(2, map.towers(side, lane).size(), "two towers of " + side + " on " + lane);
            }
            List<LaneSites.Objective> objectives = map.objectives(side);
            assertEquals(6, objectives.size(), side + " has six objectives");
            Set<ObjectiveKind> kinds = EnumSet.noneOf(ObjectiveKind.class);
            objectives.forEach(o -> kinds.add(o.kind()));
            assertEquals(EnumSet.complementOf(EnumSet.of(ObjectiveKind.DRAGON)), kinds, "one of each kind");
        }
        assertEquals(ObjectiveKind.DRAGON, map.epic().kind());
        assertTrue(map.epic().radius() >= 8, "a large dragon pit");
        assertEquals(0, map.epic().x());
        assertEquals(0, map.epic().z());
        Set<String> ids = new HashSet<>();
        for (LaneSites.Structure s : structures()) {
            assertTrue(ids.add(s.id()), "unique id " + s.id());
        }
        for (Side side : Side.values()) {
            for (LaneSites.Objective o : map.objectives(side)) {
                assertTrue(ids.add(o.id()), "unique id " + o.id());
            }
        }
        Set<String> regionNames = new TreeSet<>();
        map.regions().forEach(r -> regionNames.add(r.name()));
        assertTrue(regionNames.containsAll(List.of("BLUE 진영", "RED 진영", "탑 라인", "바텀 라인", "정글")),
                "region titles: " + regionNames);
        assertTrue(regionNames.stream().anyMatch(n -> n.contains("강") && n.contains("드래곤")), "river/dragon region");
    }

    @Test
    void theBasesFaceEachOther() {
        assertTrue(map.core(Side.BLUE).x() < -60 && map.core(Side.RED).x() > 60, "BLUE on -x, RED on +x");
        for (Lane lane : Lane.values()) {
            for (LaneSites.Structure tower : map.towers(Side.BLUE, lane)) {
                assertTrue(lane == Lane.TOP ? tower.z() < 0 : tower.z() > 0, tower.id() + " on its lane's side");
            }
            List<LaneSites.Structure> towers = map.towers(Side.BLUE, lane);
            assertTrue(towers.get(0).x() > towers.get(1).x(), "the outer tower stands further from the BLUE base");
        }
        for (Side side : Side.values()) {
            LaneSites.Spot shop = map.shop(side);
            // The shop stands in the courtyard on the team's left as it faces the enemy (BLUE -z, RED +z).
            assertTrue(ToyLayout.inCourtyard(shop.x(), shop.z()), side + " shop in the courtyard: " + shop);
            assertEquals(side == Side.BLUE ? -1 : 1, Integer.signum(shop.z()), side + " shop on the team's left");
            // The shopkeeper faces the courtyard: the core's stage lies ahead of it.
            LaneSites.Structure core = map.core(side);
            double yaw = Math.toRadians(shop.yaw());
            double ahead = -Math.sin(yaw) * (core.x() - shop.x()) + Math.cos(yaw) * (core.z() - shop.z());
            assertTrue(ahead > 0.9 * Math.hypot(core.x() - shop.x(), core.z() - shop.z()),
                    side + " shopkeeper faces the courtyard: yaw " + shop.yaw());
            List<LaneSites.Spot> spawns = map.spawns(side);
            for (LaneSites.Spot spawn : spawns) {
                // Every spawn lies within the shop's range (8) of the shopkeeper, on the team's left like the shop.
                double d = Math.hypot(spawn.x() - shop.x(), spawn.z() - shop.z());
                assertTrue(d <= 8, side + " spawn " + spawn + " within the shop range: " + d);
                assertEquals(Integer.signum(shop.z()), Integer.signum(spawn.z()), "spawn beside the shop " + spawn);
                assertTrue(ToyLayout.inCourtyard(spawn.x(), spawn.z()), "spawn in the courtyard " + spawn);
                // Off the core's stage (its rim) and so off the core's walls (within its radius).
                assertTrue(Math.hypot(spawn.x() - core.x(), spawn.z() - core.z()) > ToyLayout.CORE_RADIUS + 2.5,
                        "spawn off the core's stage " + spawn);
                float expected = side == Side.BLUE ? -90 : 90;
                assertEquals(expected, spawn.yaw(), 0.01, "spawns face the enemy base");
                for (LaneSites.Spot other : spawns) {
                    double apart = Math.hypot(spawn.x() - other.x(), spawn.z() - other.z());
                    assertTrue(spawn == other || apart >= 1.5, "spawns " + spawn + " and " + other + " apart");
                }
            }
        }
    }

    @Test
    void everySpawnAndShopSpotIsAFreeReachablePlace() {
        Set<String> distinct = new HashSet<>();
        for (Side side : Side.values()) {
            List<LaneSites.Spot> spots = new ArrayList<>(map.spawns(side));
            spots.add(map.shop(side));
            for (LaneSites.Spot spot : spots) {
                assertTrue(distinct.add(spot.x() + "," + spot.y() + "," + spot.z()), "two spots at " + spot);
                MapAudit.Node node = nodes.get(key(spot.x(), spot.y(), spot.z()));
                assertNotNull(node, "a player can stand at " + spot);
                assertTrue(reachable.contains(node), "reachable from the BLUE spawn: " + spot);
            }
        }
        Set<MapAudit.Node> fromRed = flood(node(map.spawns(Side.RED).getFirst()));
        assertTrue(fromRed.contains(node(map.spawns(Side.BLUE).getFirst())), "RED reaches the BLUE base");
    }

    @Test
    void theTowerAndCorePlatformsAreFlatOpenAndReachable() {
        for (LaneSites.Structure s : structures()) {
            int r = s.radius();
            assertTrue(r >= 6, s.id() + ": platform radius " + r);
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dz * dz > r * r) {
                        continue;
                    }
                    int x = s.x() + dx;
                    int z = s.z() + dz;
                    BlockSpec floor = canvas.get(x, s.y() - 1, z);
                    assertTrue(floor != null && MapBlocks.isFullCube(floor), s.id() + ": flat floor at " + x + "," + z);
                    int clearance = s.id().contains("core") ? ToyLayout.CORE_CLEARANCE : ToyLayout.CLEARANCE;
                    for (int y = s.y(); y <= s.y() + clearance; y++) {
                        assertFalse(canvas.isSolid(x, y, z), s.id() + ": open at " + x + "," + y + "," + z);
                    }
                    assertTrue(reachable.contains(nodes.get(key(x, s.y(), z))), s.id() + ": reachable at " + x + ","
                            + z);
                }
            }
        }
    }

    @Test
    void theObjectivesAreOpenFlatAndReachable() {
        List<LaneSites.Objective> all = new ArrayList<>();
        for (Side side : Side.values()) {
            all.addAll(map.objectives(side));
        }
        all.add(map.epic());
        for (LaneSites.Objective o : all) {
            boolean epic = o.kind() == ObjectiveKind.DRAGON;
            assertTrue(o.radius() >= 4, o.id() + ": room for the monsters");
            for (int dx = -o.radius(); dx <= o.radius(); dx++) {
                for (int dz = -o.radius(); dz <= o.radius(); dz++) {
                    if (dx * dx + dz * dz > o.radius() * o.radius()) {
                        continue;
                    }
                    int x = o.x() + dx;
                    int z = o.z() + dz;
                    MapAudit.Node node = nodes.get(key(x, o.y(), z));
                    assertNotNull(node, o.id() + ": flat standable ground at " + x + "," + z);
                    assertTrue(reachable.contains(node), o.id() + ": reachable at " + x + "," + z);
                    int height = epic ? 10 : dx * dx + dz * dz <= 4 ? 5 : 3;
                    for (int y = o.y(); y < o.y() + height; y++) {
                        assertFalse(canvas.isSolid(x, y, z), o.id() + ": open at " + x + "," + y + "," + z);
                    }
                }
            }
            for (LaneSites.Structure s : structures()) {
                assertTrue(Math.hypot(o.x() - s.x(), o.z() - s.z()) > o.radius() + s.radius() + 6,
                        o.id() + " stays away from " + s.id());
            }
        }
    }

    @Test
    void theLanePathsAreWalkableAndPassEveryTower() {
        for (Lane lane : Lane.values()) {
            List<LaneSites.Point> path = map.lanePath(lane);
            assertTrue(path.getFirst().x() <= -ToyLayout.CASTLE_FRONT, lane + " starts in the BLUE base");
            assertTrue(path.getLast().x() >= ToyLayout.CASTLE_FRONT, lane + " ends in the RED base");
            for (int i = 0; i < path.size(); i++) {
                LaneSites.Point p = path.get(i);
                MapAudit.Node node = nodes.get(key(p.x(), p.y(), p.z()));
                assertNotNull(node, lane + " waypoint " + i + " stands on walkable ground: " + p);
                assertTrue(reachable.contains(node), lane + " waypoint reachable: " + p);
                LaneSites.Point mirror = path.get(path.size() - 1 - i);
                assertEquals(p.x(), -mirror.x(), lane + " path is mirror symmetric at " + i);
                assertEquals(p.z(), mirror.z(), lane + " path is mirror symmetric at " + i);
                if (i == 0) {
                    continue;
                }
                LaneSites.Point q = path.get(i - 1);
                double gap = Math.hypot(p.x() - q.x(), p.z() - q.z());
                assertTrue(gap <= 12 && gap >= 1, lane + " waypoints " + (i - 1) + ".." + i + " are " + gap + " apart");
                // Continuous: every column on the straight line between two waypoints is walkable at the same level.
                int steps = (int) Math.ceil(gap * 2);
                for (int s = 0; s <= steps; s++) {
                    int x = (int) Math.round(q.x() + (p.x() - q.x()) * (double) s / steps);
                    int z = (int) Math.round(q.z() + (p.z() - q.z()) * (double) s / steps);
                    assertNotNull(nodes.get(key(x, p.y(), z)), lane + ": walkable between waypoints at " + x + ","
                            + z);
                }
            }
            for (Side side : Side.values()) {
                for (LaneSites.Structure tower : map.towers(side, lane)) {
                    assertTrue(path.stream().anyMatch(p -> Math.hypot(p.x() - tower.x(), p.z() - tower.z())
                            <= tower.radius()), lane + " passes through " + tower.id());
                }
            }
        }
    }

    @Test
    void theLanesAreClearlyColoured() {
        for (Lane lane : Lane.values()) {
            Material road = lane == Lane.TOP ? Material.YELLOW_CONCRETE : Material.LIGHT_BLUE_CONCRETE;
            // Its bricks come in two tones of the road's colour (concrete and the brighter wool).
            Material brick = lane == Lane.TOP ? Material.YELLOW_WOOL : Material.LIGHT_BLUE_WOOL;
            // The road, or where it crosses the team coloured ring around a tower plaza.
            Set<Material> allowed = Set.of(road, brick, Material.BLUE_CONCRETE, Material.RED_CONCRETE);
            int onRoad = 0;
            int outside = 0;
            List<LaneSites.Point> path = map.lanePath(lane);
            for (int i = 1; i < path.size(); i++) {
                LaneSites.Point a = path.get(i - 1);
                LaneSites.Point b = path.get(i);
                for (int s = 0; s < 10; s++) {
                    int x = (int) Math.round(a.x() + (b.x() - a.x()) * s / 10.0);
                    int z = (int) Math.round(a.z() + (b.z() - a.z()) * s / 10.0);
                    if (ToyLayout.inCastle(x, z)) {
                        continue;
                    }
                    outside++;
                    BlockSpec floor = canvas.get(x, a.y() - 1, z);
                    assertNotNull(floor, lane + " floor under " + x + "," + z);
                    assertTrue(allowed.contains(floor.material()), lane + " road under " + x + "," + z + ": " + floor);
                    onRoad += floor.material() == road || floor.material() == brick ? 1 : 0;
                }
            }
            assertTrue(onRoad >= outside * 0.9, lane + ": " + onRoad + " of " + outside + " places on the road");
        }
    }

    @Test
    void theDropsStayAwayFromThePathsAndAreMarked() {
        int r = map.buildRadius();
        int drops = 0;
        int holes = 0;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                if (!ToyLayout.inDrop(x, z) || !ToyLayout.isInterior(x, z)) {
                    continue;
                }
                drops++;
                boolean hole = ToyLayout.inHole(x, z);
                holes += hole ? 1 : 0;
                for (int y = canvas.minY(); y <= canvas.maxY(); y++) {
                    BlockSpec spec = canvas.get(x, y, z);
                    assertTrue(spec == null || spec.isAir() || y - ToyLayout.GROUND_Y >= ToyHall.KINGDOM_TOP,
                            "nothing in a drop below the hall's ceiling at " + x + "," + y + "," + z);
                }
                String at = " near the drop at " + x + "," + z;
                assertTrue(ToyLayout.laneDistance(ToyLayout.laneOf(z), x, z) >= ToyLayout.EDGE_HALF + 5,
                        "a lane" + at);
                for (LaneSites.Structure s : structures()) {
                    assertTrue(Math.hypot(x - s.x(), z - s.z()) > s.radius() + 12, s.id() + at);
                }
                for (Side side : Side.values()) {
                    for (LaneSites.Objective o : map.objectives(side)) {
                        // A hole in the middle keeps out of the camp's leash (jelly/mice 12, teddy/rabbit 14).
                        double keep = hole ? Math.max(o.radius() + 4, campLeash(o.kind())) + 1 : o.radius() + 16;
                        assertTrue(Math.hypot(x - o.x(), z - o.z()) > keep, o.id() + at);
                    }
                    // The spawn mat stands beside the shop inside the front gate: the same distance as the shop.
                    for (LaneSites.Spot spot : map.spawns(side)) {
                        assertTrue(Math.hypot(x - spot.x(), z - spot.z()) > (hole ? 20 : 40), "a spawn" + at);
                    }
                    assertTrue(Math.hypot(x - map.shop(side).x(), z - map.shop(side).z()) > (hole ? 20 : 40),
                            "the shop" + at);
                }
                if (hole) {
                    // Outside the dragon's leash (20), the castle zones and the front plazas.
                    assertTrue(Math.hypot(x, z) > DRAGON_LEASH + ToyLayout.HOLE_DRAGON_GAP, "the dragon" + at);
                    assertEquals("jungle", ToyLayout.partOf(x, z), "holes only in the jungle corridor" + at);
                    assertTrue(Math.abs(x) < ToyLayout.CASTLE_FRONT - ToyLayout.FRONT_PLAZA_RADIUS - 5,
                            "the castle's front plaza" + at);
                } else {
                    assertTrue(Math.hypot(x, z) > map.epic().radius() + 24, "the dragon" + at);
                }
            }
        }
        assertTrue(drops > 0, "the map has drops");
        assertTrue(holes > 0, "the middle has holes");
        System.out.println("[toykingdom] drops: " + drops + " columns, holes in the middle: " + holes);
        // Every walkable column at the edge of a drop is striped red and white.
        for (MapAudit.Node node : reachable) {
            if (!ToyLayout.onCliffEdge(node.x(), node.z()) || !ToyLayout.isInterior(node.x(), node.z())) {
                continue;
            }
            Material floor = canvas.get(node.x(), node.y() - 1, node.z()).material();
            assertTrue(floor == Material.RED_CONCRETE || floor == Material.WHITE_CONCRETE,
                    "warning stripes at " + node + ": " + floor);
        }
    }

    @Test
    void theHallClosesOverTheKingdom() {
        int r = map.buildRadius();
        int g = ToyLayout.GROUND_Y;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                int vault = ToyHall.vaultHeight(x, z);
                if (vault < 0) {
                    for (int y = canvas.minY(); y <= canvas.maxY(); y++) {
                        assertFalse(canvas.isSolid(x, y, z), "beyond the hall's back wall at " + x + "," + y + "," + z);
                    }
                    continue;
                }
                assertTrue(canvas.isSolid(x, g + vault, z), "the roof is closed at " + x + "," + (g + vault) + "," + z);
                for (int y = g + vault + 1; y <= canvas.maxY(); y++) {
                    assertFalse(canvas.isSolid(x, y, z), "on top of the roof at " + x + "," + y + "," + z);
                }
                if (ToyLayout.inCliff(x, z) && !ToyLayout.isInterior(x, z)) {
                    // The gate over a lane drop: open from the void up to its arch.
                    for (int y = canvas.minY(); y <= g + 8; y++) {
                        assertFalse(canvas.isSolid(x, y, z), "the gate over the drop is open at " + x + "," + y + ","
                                + z);
                    }
                }
            }
        }
        // The plinth behind the toy brick wall stands at least 12 high all around (except over the drops).
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                double d = ToyLayout.outline(x, z);
                if (d <= 2.5 || d > 3.5 || ToyLayout.inCliff(x, z)) {
                    continue;
                }
                for (int y = g + 1; y <= g + ToyHall.DECKS[0]; y++) {
                    assertTrue(canvas.isSolid(x, y, z), "the plinth is closed at " + x + "," + y + "," + z);
                }
            }
        }
    }

    @Test
    void theCeilingKeepsTheKingdomBright() {
        // Glass lets the sky light through; under an opaque block of the ceiling (the clock's numeral ring, the gears,
        // the hands, the ribs) the sky light at the floor drops by one per block to the nearest open column.
        int r = map.buildRadius();
        int size = 2 * r + 1;
        int[] dist = new int[size * size];
        java.util.Arrays.fill(dist, Integer.MAX_VALUE);
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        int shaded = 0;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                boolean shade = false;
                for (int y = ToyLayout.GROUND_Y + ToyHall.KINGDOM_TOP; y <= canvas.maxY() && !shade; y++) {
                    BlockSpec spec = canvas.get(x, y, z);
                    shade = spec != null && MapBlocks.isFullCube(spec) && !spec.material().name().contains("GLASS");
                }
                shaded += shade ? 1 : 0;
                if (!shade) {
                    dist[(x + r) * size + z + r] = 0;
                    queue.add(new int[] {x, z});
                }
            }
        }
        int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!queue.isEmpty()) {
            int[] at = queue.poll();
            int here = dist[(at[0] + r) * size + at[1] + r];
            for (int[] s : steps) {
                int nx = at[0] + s[0];
                int nz = at[1] + s[1];
                if (Math.abs(nx) > r || Math.abs(nz) > r || dist[(nx + r) * size + nz + r] <= here + 1) {
                    continue;
                }
                dist[(nx + r) * size + nz + r] = here + 1;
                queue.add(new int[] {nx, nz});
            }
        }
        int worst = 0;
        String darkest = "";
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                int here = dist[(x + r) * size + z + r];
                if (ToyLayout.isInterior(x, z) && here > worst) {
                    worst = here;
                    darkest = x + "," + z;
                }
            }
        }
        System.out.println("[toykingdom] ceiling: " + shaded + " shaded columns, darkest floor sky light "
                + (15 - worst) + " at " + darkest);
        assertTrue(worst <= 4, "the floor gets sky light 11 or more everywhere, the darkest has " + (15 - worst)
                + " at " + darkest);
    }

    @Test
    void theHallIsLitWithLanterns() {
        int[] lanterns = {0};
        int[] lights = {0};
        canvas.forEach((x, y, z, spec) -> {
            if (!ToyHall.isHall(x, y, z)) {
                return;
            }
            if (spec.material() == Material.LANTERN) {
                lanterns[0]++;
            } else if (MapLight.emission(spec.material()) > 0) {
                lights[0]++;
            }
        });
        System.out.println("[toykingdom] hall: " + lanterns[0] + " lanterns, " + lights[0] + " light blocks");
        assertTrue(lanterns[0] >= 1000, "rows of lanterns on every balcony: " + lanterns[0]);
        assertTrue(lights[0] >= 300, "light blocks in the pillars, windows, vault and clock: " + lights[0]);
    }

    @Test
    void theRoadsStayClearForTheMinions() {
        // Nothing stands on a road or its white edge (the minions march up to 3.5 blocks off the centre line).
        int r = map.buildRadius();
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                if (ToyLayout.inCastle(x, z) || !ToyLayout.isInterior(x, z)
                        || ToyLayout.laneDistance(ToyLayout.laneOf(z), x, z) > ToyLayout.EDGE_HALF) {
                    continue;
                }
                for (int y = ToyLayout.GROUND_Y + 1; y <= ToyLayout.GROUND_Y + 4; y++) {
                    assertFalse(canvas.isSolid(x, y, z), "an obstacle on the road at " + x + "," + y + "," + z);
                }
            }
        }
    }

    // ------------------------------------------------------------------ the whole map

    @Test
    void everyGroundPlaceCanBeReachedAndLeft() {
        assertTrue(report.problems().isEmpty(), "no floating sand, spilling water, lava or fire:\n"
                + report.summary());
        List<MapAudit.Node> low = new ArrayList<>();
        for (MapAudit.Node node : audit.nodes()) {
            if (!reachable.contains(node) && ToyLayout.isInterior(node.x(), node.z())
                    && node.y() <= ToyLayout.GROUND_Y + 2) {
                low.add(node);
            }
        }
        assertTrue(low.isEmpty(), "ground places cut off: " + low.size() + " e.g. "
                + low.subList(0, Math.min(10, low.size())));
        assertTrue(report.stranded().isEmpty(), "no pits you cannot leave:\n" + report.summary());
    }

    @Test
    void nobodyCanWalkOffTheMap() {
        for (MapAudit.Node node : reachable) {
            assertTrue(ToyLayout.isInterior(node.x(), node.z()), "a reachable place beyond the hedge: " + node);
        }
    }

    @Test
    void everyBlockHasItsSupport() {
        assertTrue(blockFindings.isEmpty(), "blocks placed wrong:\n" + String.join("\n", blockFindings));
    }

    @Test
    void bothHalvesAreMirrorImages() {
        for (Lane lane : Lane.values()) {
            List<LaneSites.Structure> blue = map.towers(Side.BLUE, lane);
            List<LaneSites.Structure> red = map.towers(Side.RED, lane);
            for (int i = 0; i < blue.size(); i++) {
                assertMirrored(blue.get(i).x(), blue.get(i).z(), red.get(i).x(), red.get(i).z(), blue.get(i).id());
            }
        }
        assertMirrored(map.core(Side.BLUE).x(), map.core(Side.BLUE).z(), map.core(Side.RED).x(),
                map.core(Side.RED).z(), "core");
        // The shops stand on each team's left: point symmetric, not mirrored.
        assertMirrored(map.shop(Side.BLUE).x(), map.shop(Side.BLUE).z(), map.shop(Side.RED).x(),
                -map.shop(Side.RED).z(), "shop (point symmetric)");
        for (int i = 0; i < map.spawns(Side.BLUE).size(); i++) {
            LaneSites.Spot b = map.spawns(Side.BLUE).get(i);
            LaneSites.Spot r = map.spawns(Side.RED).get(i);
            // The spawn mat stands beside the shop on each team's left: point symmetric too.
            assertMirrored(b.x(), b.z(), r.x(), -r.z(), "spawn " + i + " (point symmetric)");
        }
        for (ObjectiveKind kind : ObjectiveKind.values()) {
            if (kind == ObjectiveKind.DRAGON) {
                continue;
            }
            LaneSites.Objective b = objective(Side.BLUE, kind);
            LaneSites.Objective r = objective(Side.RED, kind);
            assertMirrored(b.x(), b.z(), r.x(), r.z(), kind.name());
            assertEquals(b.radius(), r.radius(), kind + " radius");
        }
        // The blocks: every solid block has a solid mirror image (team colours may differ); the shop stalls are point
        // symmetric instead.
        int r = map.buildRadius();
        int mismatches = 0;
        String example = "";
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                if (ToyLayout.inShopStall(x, z) || ToyLayout.inShopStall(-x, z)) {
                    for (int y = canvas.minY(); y <= canvas.maxY(); y++) {
                        if (canvas.isSolid(x, y, z) != canvas.isSolid(-x, y, -z) && mismatches++ == 0) {
                            example = x + "," + y + "," + z + " (shop)";
                        }
                    }
                    continue;
                }
                for (int y = canvas.minY(); y <= canvas.maxY(); y++) {
                    if (canvas.isSolid(x, y, z) != canvas.isSolid(-x, y, z)) {
                        if (mismatches++ == 0) {
                            example = x + "," + y + "," + z;
                        }
                    }
                }
            }
        }
        assertEquals(0, mismatches, "blocks without a mirror image, e.g. at " + example);
    }

    @Test
    void eachZoneKeepsToAFewBlockTypes() {
        Map<String, Set<Material>> palettes = new TreeMap<>();
        Map<String, Set<Material>> details = new TreeMap<>();
        canvas.forEach((x, y, z, spec) -> {
            boolean hall = ToyHall.isHall(x, y, z);
            (!hall && isDetail(spec.material()) ? details : palettes)
                    .computeIfAbsent(hall ? "hall" : ToyLayout.partOf(x, z), k -> new TreeSet<>())
                    .add(spec.material());
        });
        details.forEach((zone, palette) -> {
            System.out.println("[toykingdom] details " + zone + " (" + palette.size() + "): " + palette);
            assertTrue(palette.size() <= DETAIL_BUDGET, zone + " uses " + palette.size() + " detail blocks: "
                    + palette);
        });
        Set<Material> allDetails = new TreeSet<>();
        details.values().forEach(allDetails::addAll);
        assertTrue(allDetails.size() <= MAP_DETAIL_BUDGET, "the whole map uses " + allDetails.size()
                + " detail blocks: " + allDetails);
        palettes.forEach((zone, palette) -> System.out.println("[toykingdom] palette " + zone + " (" + palette.size()
                + "): " + palette));
        assertEquals(Set.of("border", "blue-castle", "red-castle", "top-lane", "bottom-lane", "jungle", "river",
                "hall"), palettes.keySet());
        palettes.forEach((zone, palette) -> assertTrue(palette.size() <= switch (zone) {
            case "jungle" -> JUNGLE_PALETTE_BUDGET;
            case "hall" -> HALL_PALETTE_BUDGET;
            default -> PALETTE_BUDGET;
        }, zone + " uses " + palette.size() + " block types: " + palette));
        Set<Material> kingdom = new TreeSet<>();
        palettes.forEach((zone, palette) -> {
            if (!zone.equals("hall")) {
                kingdom.addAll(palette);
            }
        });
        assertTrue(kingdom.size() <= 21, "the kingdom uses " + kingdom.size() + " block types: " + kingdom);
    }

    /** A block that shapes a prop rather than colouring it (see {@link #DETAIL_BUDGET}); glass and water are not. */
    private static boolean isDetail(Material material) {
        return !MapBlocks.isFullCubeMaterial(material) && !MapBlocks.isFluid(material)
                && !material.name().contains("GLASS");
    }

    @Test
    void theMapHasTheRightSize() {
        assertTrue(2 * ToyLayout.HALF_X >= 190 && 2 * ToyLayout.HALF_X <= 210, "about 200 blocks long");
        assertTrue(2 * ToyLayout.HALF_Z >= 110 && 2 * ToyLayout.HALF_Z <= 130, "about 120 blocks wide");
        // The kingdom is about 130 000 blocks, the hall around and over it about as much again.
        assertTrue(canvas.blockCount() < 700_000, "a light map: " + canvas.blockCount());
        int steps = distance(node(map.spawns(Side.BLUE).getFirst()), node(map.spawns(Side.RED).getFirst()));
        System.out.println("[toykingdom] BLUE spawn to RED spawn: " + steps + " blocks");
        assertTrue(steps >= 150 && steps <= 230, "base to base is " + steps + " blocks");
    }

    // ------------------------------------------------------------------ helpers

    /** The dragon's leash (games.siege.jungle defaults): the holes in the middle keep out of it. */
    private static final double DRAGON_LEASH = 20;

    /** A camp's leash (games.siege.jungle defaults): jelly and mice 12, teddy and rabbit 14, the others none. */
    private static double campLeash(ObjectiveKind kind) {
        return switch (kind) {
            case JELLY, MICE -> 12;
            case TEDDY, RABBIT -> 14;
            default -> 0;
        };
    }

    private static void assertMirrored(int bx, int bz, int rx, int rz, String what) {
        assertTrue(Math.abs(bx + rx) <= 1 && Math.abs(bz - rz) <= 1,
                what + ": RED " + rx + "," + rz + " mirrors BLUE " + bx + "," + bz);
    }

    private static LaneSites.Objective objective(Side side, ObjectiveKind kind) {
        return map.objectives(side).stream().filter(o -> o.kind() == kind).findFirst().orElseThrow();
    }

    private static List<LaneSites.Structure> structures() {
        List<LaneSites.Structure> out = new ArrayList<>();
        for (Side side : Side.values()) {
            for (Lane lane : Lane.values()) {
                out.addAll(map.towers(side, lane));
            }
            out.add(map.core(side));
        }
        return out;
    }

    private static MapAudit.Node node(LaneSites.Spot spot) {
        MapAudit.Node node = nodes.get(key(spot.x(), spot.y(), spot.z()));
        assertNotNull(node, "a player can stand at " + spot);
        return node;
    }

    private static Set<MapAudit.Node> flood(MapAudit.Node start) {
        Set<MapAudit.Node> seen = new HashSet<>();
        seen.add(start);
        ArrayDeque<MapAudit.Node> queue = new ArrayDeque<>(seen);
        while (!queue.isEmpty()) {
            for (MapAudit.Node to : audit.moves(queue.poll())) {
                if (seen.add(to)) {
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
