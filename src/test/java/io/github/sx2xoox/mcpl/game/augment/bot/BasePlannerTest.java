package io.github.sx2xoox.mcpl.game.augment.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;

import io.github.sx2xoox.mcpl.game.augment.bot.BasePlanner.Design;
import io.github.sx2xoox.mcpl.game.augment.bot.BasePlanner.Part;
import io.github.sx2xoox.mcpl.game.augment.bot.BasePlanner.Placement;
import io.github.sx2xoox.mcpl.game.augment.bot.BasePlanner.Plan;
import io.github.sx2xoox.mcpl.game.augment.bot.BasePlanner.Surface;
import org.junit.jupiter.api.Test;

/** The base planner on synthetic terrain: where bases go and what they look like. */
class BasePlannerTest {

    private static BasePlanner.Terrain terrain(BiFunction<Integer, Integer, Integer> height,
                                               BiFunction<Integer, Integer, Surface> surface) {
        return new BasePlanner.Terrain() {
            @Override
            public int height(int x, int z) {
                return height.apply(x, z);
            }

            @Override
            public Surface surface(int x, int z) {
                return surface.apply(x, z);
            }
        };
    }

    private static final BasePlanner.Terrain FLAT = terrain((x, z) -> 64, (x, z) -> Surface.SOLID);

    @Test
    void aSmallBaseIsAClosedBoxWithADoorwayFacingTheZoneSlitsALookoutAndFurniture() {
        Plan plan = BasePlanner.plan(FLAT, 0, 0, 5, 100, 0);
        assertEquals(2, plan.half());
        assertEquals(2, plan.doorX(), "the doorway faces the zone (east)");
        assertEquals(0, plan.doorZ());
        Set<String> walls = new HashSet<>();
        for (Placement p : plan.placements()) {
            assertTrue(p.y() > 64, "never below the ground: " + p);
            if (p.part() == Part.WALL) {
                assertEquals(2, Math.max(Math.abs(p.x()), Math.abs(p.z())), "walls on the ring");
                walls.add(p.x() + "," + p.y() + "," + p.z());
            }
        }
        assertFalse(walls.contains("2,65,0") || walls.contains("2,66,0"), "a two-high doorway");
        assertTrue(walls.contains("2,67,0"), "with a lintel");
        assertEquals(List.of("-2,66,0", "0,66,2", "0,66,-2"), plan.slits().stream()
                .map(c -> c[0] + "," + c[1] + "," + c[2]).toList(), "a shooting slit at eye height in every other wall");
        for (int[] slit : plan.slits()) {
            assertFalse(walls.contains(slit[0] + "," + slit[1] + "," + slit[2]), "the slit stays open");
        }
        assertEquals(16 * 3 - 2 - 3, walls.size(), "the ring three high minus the doorway and the slits");
        long roof = plan.placements().stream().filter(p -> p.part() == Part.ROOF && p.y() == 68).count();
        assertEquals(25, roof, "a full roof: no holes anywhere");
        assertTrue(plan.placements().stream().anyMatch(p -> p.part() == Part.PILLAR && p.y() > 68), "a lookout");
        assertEquals(List.of(2, 65, 0), List.of(plan.door().get(0)[0], plan.door().get(0)[1], plan.door().get(0)[2]));
        assertEquals(1, plan.inner()[0], "held from just inside");
        assertEquals(3, plan.outer()[0]);
        Placement chest = plan.placements().stream().filter(p -> p.part() == Part.CHEST).findFirst().orElseThrow();
        Placement table = plan.placements().stream().filter(p -> p.part() == Part.CRAFTING_TABLE).findFirst()
                .orElseThrow();
        assertEquals(65, chest.y());
        assertTrue(Math.abs(chest.x()) < 2 && Math.abs(chest.z()) < 2, "inside");
        assertTrue(chest.x() < 0, "away from the door");
        assertFalse(chest.x() == table.x() && chest.z() == table.z());
        assertTrue(plan.inside(0.5, 0.5));
        assertFalse(plan.inside(3, 0));
    }

    @Test
    void aBigBaseIsThreeHighWithARoofAndTrapsAtTheDoor() {
        Plan plan = BasePlanner.plan(FLAT, 0, 0, 7, 0, -100);
        assertEquals(-3, plan.doorZ(), "faces north towards the zone");
        assertTrue(plan.placements().stream().anyMatch(p -> p.part() == Part.ROOF && p.y() == 68));
        List<Placement> webs = plan.placements().stream().filter(p -> p.part() == Part.COBWEB).toList();
        assertEquals(2, webs.size());
        for (Placement web : webs) {
            assertEquals(-5, web.z(), "two blocks outside the doorway");
        }
        // built like a player: the walls layer by layer before the roof
        int firstRoof = indexOf(plan, Part.ROOF);
        int lastWall = 0;
        for (int i = 0; i < plan.placements().size(); i++) {
            if (plan.placements().get(i).part() == Part.WALL) {
                lastWall = i;
            }
        }
        assertTrue(lastWall < firstRoof);
        int previousY = 0;
        for (Placement p : plan.placements()) {
            if (p.part() == Part.WALL) {
                assertTrue(p.y() >= previousY, "bottom up");
                previousY = p.y();
            }
        }
    }

    @Test
    void wallsFillDipsDownToTheGround() {
        BasePlanner.Terrain dip = terrain((x, z) -> x == -2 && z == 0 ? 62 : 64, (x, z) -> Surface.SOLID);
        Plan plan = BasePlanner.plan(dip, 0, 0, 5, 100, 0);
        assertTrue(plan.placements().stream().anyMatch(p -> p.x() == -2 && p.z() == 0 && p.y() == 63));
    }

    @Test
    void noBaseInWaterLavaOrOverTheVoidOrOnACliff() {
        BasePlanner.Terrain lake = terrain((x, z) -> 62, (x, z) -> x > 10 ? Surface.WATER : Surface.SOLID);
        assertTrue(Double.isNaN(BasePlanner.score(lake, 12, 0, 5, 0, 0, List.of())));
        assertFalse(Double.isNaN(BasePlanner.score(lake, 0, 0, 5, 0, 0, List.of())));
        BasePlanner.Terrain lava = terrain((x, z) -> 62, (x, z) -> x == 1 ? Surface.LAVA : Surface.SOLID);
        assertTrue(Double.isNaN(BasePlanner.score(lava, 0, 0, 5, 0, 0, List.of())));
        BasePlanner.Terrain cliff = terrain((x, z) -> x > 0 ? 80 : 64, (x, z) -> Surface.SOLID);
        assertTrue(Double.isNaN(BasePlanner.score(cliff, 0, 0, 5, 0, 0, List.of())));
    }

    @Test
    void noBaseOnOrNextToPowderSnowCactusOrMagmaNorWhereTheBorderWillBe() {
        BasePlanner.Terrain snow = terrain((x, z) -> 64, (x, z) -> x == 4 && z == 0 ? Surface.HAZARD : Surface.SOLID);
        assertTrue(Double.isNaN(BasePlanner.score(snow, 0, 0, 5, 0, 0, List.of())), "powder snow two blocks out");
        assertFalse(Double.isNaN(BasePlanner.score(snow, -10, 0, 5, 0, 0, List.of())));
        assertTrue(Double.isNaN(BasePlanner.score(FLAT, 80, 0, 7, 0, 0, List.of(), 0.04, 60)),
                "beyond where the border will be");
        assertFalse(Double.isNaN(BasePlanner.score(FLAT, 40, 0, 7, 0, 0, List.of(), 0.04, 60)));
        List<int[]> spots = List.of(new int[] {0, 0}, new int[] {50, 0});
        BasePlanner.Terrain hillFar = terrain((x, z) -> Math.hypot(x - 50, z) < 6 ? 70 : 64,
                (x, z) -> Surface.SOLID);
        assertEquals(50, BasePlanner.choose(hillFar, spots, 5, 0, 0, List.of(), 0.04, 1000)[0],
                "a plain bot likes the far hill");
        assertEquals(0, BasePlanner.choose(hillFar, spots, 5, 0, 0, List.of(), 0.45, 1000)[0],
                "one that wants the middle stays there");
    }

    @Test
    void roughGroundIsLevelledFirst() {
        // a bump and a dip inside a 7x7 base
        BasePlanner.Terrain rough = terrain((x, z) -> x == 1 && z == 1 ? 66 : x == -1 && z == 0 ? 62 : 64,
                (x, z) -> Surface.SOLID);
        assertFalse(Double.isNaN(BasePlanner.score(rough, 0, 0, 7, 0, 0, List.of())), "two up or down is fine");
        Plan plan = BasePlanner.plan(rough, 0, 0, 7, 100, 0);
        List<Placement> clear = plan.placements().stream()
                .filter(p -> p.part() == Part.CLEAR && p.y() <= rough.height(p.x(), p.z())).toList();
        assertEquals(2, clear.size(), "the bump is dug away");
        assertTrue(clear.stream().allMatch(p -> p.x() == 1 && p.z() == 1 && p.y() > 64));
        List<Placement> floor = plan.placements().stream().filter(p -> p.part() == Part.FLOOR).toList();
        assertEquals(2, floor.size(), "the dip is filled");
        assertTrue(floor.stream().allMatch(p -> p.x() == -1 && p.z() == 0 && p.y() <= 64));
        assertTrue(plan.placements().indexOf(clear.getFirst()) < indexOf(plan, Part.WALL), "before the walls");
        BasePlanner.Terrain steep = terrain((x, z) -> x == 1 && z == 1 ? 68 : 64, (x, z) -> Surface.SOLID);
        assertTrue(Double.isNaN(BasePlanner.score(steep, 0, 0, 7, 0, 0, List.of())), "but no base on a crag");
    }

    @Test
    void aTrapperBuildsATrapFieldWithNoFreeWayIn() {
        Plan plan = BasePlanner.plan(FLAT, 0, 0, 5, 100, 0, Design.TRAPS);
        assertEquals(Design.TRAPS, plan.design());
        long roofWebs = plan.placements().stream()
                .filter(p -> p.part() == Part.COBWEB && p.y() == plan.roofY() + 1).count();
        assertEquals(3, roofWebs, "three webs on the corners of the solid roof against pillaring over");
        long roofTiles = plan.placements().stream()
                .filter(p -> p.part() == Part.ROOF && p.y() == plan.roofY()).count();
        assertEquals(25, roofTiles, "the roof itself is solid: no webs in it");
        Set<String> trapped = new HashSet<>();
        Set<String> fences = new HashSet<>();
        java.util.EnumSet<Part> kinds = java.util.EnumSet.noneOf(Part.class);
        for (Placement p : plan.placements()) {
            boolean outside = Math.max(Math.abs(p.x()), Math.abs(p.z())) > plan.half();
            if (outside && (p.part() == Part.COBWEB || p.part() == Part.BUSH || p.part() == Part.CLEAR && p.y() <= 64)) {
                trapped.add(p.x() + "," + p.z());
                kinds.add(p.part());
            }
            if (p.part() == Part.FENCE) {
                fences.add(p.x() + "," + p.z());
            }
        }
        assertEquals(java.util.EnumSet.of(Part.COBWEB, Part.BUSH, Part.CLEAR), kinds, "webs, bushes and pits, mixed");
        assertTrue(plan.placements().stream().anyMatch(p -> p.part() == Part.CLEAR && p.y() == 63
                && Math.max(Math.abs(p.x()), Math.abs(p.z())) == 4), "two-deep pits in the ring");
        // the ring two out from the walls is all traps but for the lane to the doorway
        for (int[] c : BasePlanner.ring(0, 0, 4)) {
            boolean lane = c[1] == 0 && c[0] > 0;
            boolean funnel = c[0] == 4 && Math.abs(c[1]) == 1;
            assertEquals(!lane, trapped.contains(c[0] + "," + c[1]) || funnel, "ring cell " + c[0] + "," + c[1]);
        }
        assertTrue(fences.size() > 15, "a palisade ring with gaps");
        for (int k = 1; k <= 4; k++) {
            assertFalse(trapped.contains((2 + k) + ",0"), "the lane to its own doorway stays free");
            if (k >= 2) {
                assertTrue(fences.contains((2 + k) + ",1") && fences.contains((2 + k) + ",-1"), "a fenced funnel");
            }
        }
        assertTrue(BasePlanner.plan(FLAT, 0, 0, 5, 100, 0).placements().stream()
                .noneMatch(p -> p.part() == Part.COBWEB), "a plain small box has no traps");
    }

    @Test
    void everyBaseBuildingPersonalityHasItsOwnLayout() {
        Plan keep = BasePlanner.plan(FLAT, 0, 0, 9, 100, 0, Design.KEEP);
        assertTrue(walls(keep).contains("4,65,1") && walls(keep).contains("3,65,1"), "a keep's walls are double");
        assertFalse(walls(keep).contains("4,65,0") || walls(keep).contains("3,65,0"), "a gate two deep");
        assertEquals(5, keep.slits().size(), "slits on every side, two of them beside the gate");
        assertTrue(keep.placements().stream().anyMatch(p -> p.part() == Part.PILLAR && p.x() == -4 && p.z() == -4
                && p.y() == 70), "corner towers");
        Plan bunker = BasePlanner.plan(FLAT, 0, 0, 7, 100, 0, Design.BUNKER);
        assertEquals(67, bunker.roofY(), "a bunker is low");
        assertTrue(walls(bunker).contains("4,65,1") && walls(bunker).contains("5,65,-1"), "a corridor to its door");
        assertEquals(List.of(6, 65, 0), List.of(bunker.outer()[0], bunker.outer()[1], bunker.outer()[2]));
        Plan tower = BasePlanner.plan(FLAT, 0, 0, 9, 100, 0, Design.TOWER);
        assertTrue(tower.tower());
        assertEquals(3, tower.half(), "a wide nest");
        assertEquals(72, tower.nestY());
        assertEquals(75, tower.roofY(), "eleven blocks up");
        assertEquals(4, tower.slits().size(), "the nest looks out on every side");
        assertTrue(tower.slits().stream().allMatch(s -> s[1] == 74 && s[4] == 73));
        assertTrue(tower.placements().stream().anyMatch(p -> p.part() == Part.TRIM && p.x() == 3 && p.z() == 3),
                "log corners");
        Plan small = BasePlanner.plan(FLAT, 0, 0, 7, 100, 0, Design.TOWER);
        assertTrue(small.roofY() < tower.roofY() && small.half() == 2, "an easy sniper's tower is shorter");
        assertTrue(tower.placements().stream().noneMatch(p -> p.x() == 0 && p.z() == 0 && p.part() != Part.CLEAR
                && p.part() != Part.ROOF), "the shaft in the middle stays open");
        Plan burrow = BasePlanner.plan(FLAT, 0, 0, 5, 100, 0, Design.BURROW);
        assertEquals(60, burrow.floorY(), "dug four blocks down");
        assertTrue(burrow.placements().stream().allMatch(p -> p.y() <= 64), "nothing shows above the ground");
        assertTrue(burrow.slits().isEmpty());
        assertEquals(65, burrow.outer()[1], "its way in starts at the surface");
        assertTrue(burrow.fromOutside() > 0, "dug from its staircase");
        for (int i = burrow.fromOutside(); i < burrow.placements().size(); i++) {
            Placement p = burrow.placements().get(i);
            assertFalse(p.part() == Part.CLEAR && Math.max(Math.abs(p.x()), Math.abs(p.z())) < 2,
                    "the whole room is dug before it walks down into it");
        }
        Plan post = BasePlanner.plan(FLAT, 0, 0, 5, 100, 0, Design.WATCHPOST);
        assertTrue(post.placements().stream().filter(p -> p.part() == Part.CRENEL && p.y() == 69).count() >= 6,
                "a crenellated parapet");
    }

    @Test
    void theDifficultyDecidesHowGrandAKeepIs() {
        Plan easy = BasePlanner.plan(FLAT, 0, 0, 9, 100, 0, Design.KEEP);
        Plan hell = BasePlanner.plan(FLAT, 0, 0, 13, 100, 0, Design.KEEP);
        assertEquals(2, towers(easy), "a small keep: two towers at the back");
        assertEquals(4, towers(hell), "a castle: a tower at every corner");
        assertTrue(easy.placements().stream().noneMatch(p -> p.part() == Part.TRIM || p.part() == Part.FENCE));
        assertTrue(hell.placements().stream().anyMatch(p -> p.part() == Part.TRIM && p.y() == 68),
                "two storeys with a log band between them");
        assertTrue(hell.placements().stream().anyMatch(p -> p.part() == Part.FLOOR && p.y() == 68), "an upper floor");
        assertTrue(hell.placements().stream().filter(p -> p.part() == Part.FENCE).count() > 40, "a palisade");
        assertTrue(hell.placements().stream().anyMatch(p -> p.part() == Part.PILLAR && p.x() == 8 && p.z() == 2),
                "a gatehouse");
        assertTrue(hell.roofY() > easy.roofY());
        assertEquals(12, hell.placements().stream().filter(p -> p.part() == Part.WINDOW).count(),
                "windows up top (never in a slit)");
        for (Placement w : hell.placements()) {
            if (w.part() == Part.WINDOW) {
                assertTrue(hell.slits().stream().noneMatch(s -> s[0] == w.x() && s[1] == w.y() && s[2] == w.z()));
            }
        }
        assertTrue(BasePlanner.closureBlocks(Design.KEEP, 13) > 2 * BasePlanner.closureBlocks(Design.KEEP, 9));
        // both are keeps: double walls, the gate two deep, slits beside it
        for (Plan keep : List.of(easy, hell)) {
            assertEquals(5, keep.slits().size());
            assertTrue(walls(keep).contains(keep.half() + ",65,1") && walls(keep).contains((keep.half() - 1) + ",65,1"));
        }
    }

    /** Corner towers: pillars standing diagonally out of the corners. */
    private static int towers(Plan plan) {
        int count = 0;
        for (int[] c : new int[][] {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) {
            int tx = c[0] * (plan.half() + 1);
            int tz = c[1] * (plan.half() + 1);
            if (plan.placements().stream().anyMatch(p -> p.part() == Part.PILLAR && p.x() == tx && p.z() == tz)) {
                count++;
            }
        }
        return count;
    }

    @Test
    void aBurrowIsWalkedIntoStepByStep() {
        Plan burrow = BasePlanner.plan(FLAT, 0, 0, 5, 100, 0, Design.BURROW);
        // the door is at x 2 (feet 61), the stairs climb east to the surface at x 6 (feet 65)
        org.bukkit.Location top = new org.bukkit.Location(null, 6.5, 65, 0.5);
        org.bukkit.Location step = BotController.burrowStep(burrow, top, true);
        assertNotNull(step);
        assertEquals(5, step.getBlockX());
        assertEquals(64, step.getBlockY(), "one step down");
        org.bukkit.Location door = new org.bukkit.Location(null, 2.5, 61, 0.5);
        assertEquals(0, BotController.burrowStep(burrow, door, true).getBlockX(), "then into the room");
        org.bukkit.Location room = new org.bukkit.Location(null, 0.5, 61, 0.5);
        assertEquals(2, BotController.burrowStep(burrow, room, false).getBlockX(), "out by the door");
        assertEquals(64, BotController.burrowStep(burrow, new org.bukkit.Location(null, 4.5, 63, 0.5), false)
                .getBlockY(), "and up the stairs");
        assertNull(BotController.burrowStep(burrow, new org.bukkit.Location(null, 20.5, 65, 9.5), true),
                "not on the stairs");
    }

    @Test
    void everyLayoutIsClosedBeforeAnythingElse() {
        for (Design design : Design.values()) {
            Plan plan = BasePlanner.plan(FLAT, 0, 0, 13, 0, 100, design);
            int closure = plan.closure();
            assertTrue(closure > 0, design.name());
            int lastWall = -1;
            for (int i = 0; i < plan.placements().size(); i++) {
                Part part = plan.placements().get(i).part();
                if (part == Part.WALL || part == Part.FLOOR || part == Part.ROOF) {
                    assertTrue(i < closure, design + ": the shell first, " + plan.placements().get(i));
                }
                if (part == Part.CHEST || part == Part.CRAFTING_TABLE || part == Part.PILLAR || part == Part.COBWEB
                        || part == Part.FENCE || part == Part.CRENEL || part == Part.WINDOW || part == Part.LIGHT) {
                    assertTrue(i >= closure, design + ": furniture, lookouts and traps last");
                }
                if (part == Part.WALL) {
                    lastWall = i;
                }
            }
            assertTrue(lastWall < indexOf(plan, Part.ROOF), design + ": the walls before the roof");
            assertEquals(design == Design.BURROW ? 3 : 2, plan.door().size(), "a doorway two blocks can shut");
            assertTrue(plan.inside(plan.inner()[0] + 0.5, plan.inner()[2] + 0.5), design + ": held from inside");
            assertFalse(plan.inside(plan.outer()[0] + 0.5, plan.outer()[2] + 0.5), design + ": entered from outside");
        }
    }

    @Test
    void theSizeFitsTheTimeAndTheBlocksLeft() {
        assertTrue(BasePlanner.closureBlocks(Design.BOX, 5) < BasePlanner.closureBlocks(Design.BOX, 7));
        assertTrue(BasePlanner.closureBlocks(Design.BOX, 7) < BasePlanner.closureBlocks(Design.BOX, 9));
        assertTrue(BasePlanner.closureBlocks(Design.KEEP, 9) > BasePlanner.closureBlocks(Design.BOX, 9),
                "double walls cost more");
        // plenty: the size it wants
        assertEquals(new BasePlanner.Fit(Design.BOX, 11), BasePlanner.fit(Design.BOX, 11,
                BasePlanner.budget(3.5, 300, 0, 300)));
        // the blocks run short: smaller but closed
        assertEquals(300, BasePlanner.budget(3.5, 300, 0, 300), 1.0E-9, "the blocks bind");
        assertEquals(new BasePlanner.Fit(Design.BOX, 7), BasePlanner.fit(Design.BOX, 11, 120));
        // the time runs short: a long walk eats it (3.5 blocks a second over 20 s, half of it planned)
        assertEquals(35, BasePlanner.budget(3.5, 60, 40, 500), 1.0E-9, "the time binds");
        assertEquals(new BasePlanner.Fit(Design.BOX, 5), BasePlanner.fit(Design.BOX, 11, 35),
                "even with too little: the smallest closed box");
        // a keep that does not fit becomes a plain box that does
        assertEquals(new BasePlanner.Fit(Design.KEEP, 11), BasePlanner.fit(Design.KEEP, 11, 1000));
        assertEquals(new BasePlanner.Fit(Design.BOX, 5), BasePlanner.fit(Design.KEEP, 9, 100));
        assertEquals(new BasePlanner.Fit(Design.TOWER, 13), BasePlanner.fit(Design.TOWER, 13, 1000));
        BasePlanner.Fit shortTower = BasePlanner.fit(Design.TOWER, 13, 200);
        assertTrue(shortTower.design() == Design.TOWER && shortTower.size() < 13, "short of blocks: a shorter tower");
        for (Design design : Design.values()) {
            assertEquals(design, BasePlanner.fit(design, 7, 1000).design(), "plenty of blocks: its own layout");
        }
        assertEquals(0, BasePlanner.budget(3.5, 0, 0, 300), 1.0E-9, "in the fight: no time, the smallest base");
    }

    private static Set<String> walls(Plan plan) {
        Set<String> out = new HashSet<>();
        for (Placement p : plan.placements()) {
            if (p.part() == Part.WALL) {
                out.add(p.x() + "," + p.y() + "," + p.z());
            }
        }
        return out;
    }

    @Test
    void itPrefersHighGroundNearTheZoneAndKeepsAwayFromOtherBases() {
        // a hill around (40, 0)
        BasePlanner.Terrain hill = terrain((x, z) -> 64 + Math.max(0, 8 - (int) Math.hypot(x - 40, z) / 3),
                (x, z) -> Surface.SOLID);
        List<int[]> candidates = new ArrayList<>(List.of(new int[] {0, 0}, new int[] {40, 0}, new int[] {-40, 0}));
        int[] best = BasePlanner.choose(FLAT, candidates, 5, 0, 0, List.of());
        assertNotNull(best);
        assertEquals(0, best[0], "on flat ground: the closest to the zone");
        int[] onHill = BasePlanner.choose(hill, List.of(new int[] {0, 0}, new int[] {40, 0}), 5, 0, 0, List.of());
        assertNotNull(onHill);
        assertEquals(40, onHill[0], "the hilltop");
        int[] apart = BasePlanner.choose(FLAT, candidates, 5, 0, 0, List.<int[]>of(new int[] {5, 0}));
        assertNotNull(apart);
        assertTrue(Math.abs(apart[0]) == 40, "not inside another bot's base");
        assertNull(BasePlanner.choose(FLAT, List.<int[]>of(new int[] {5, 0}), 5, 0, 0, List.<int[]>of(new int[] {5, 0})));
    }

    @Test
    void aPaletteMixesItsBlocks() {
        String options = "STONE_BRICKS|MOSSY_STONE_BRICKS|CRACKED_STONE_BRICKS";
        java.util.Map<String, Integer> count = new java.util.HashMap<>();
        for (int x = 0; x < 30; x++) {
            for (int y = 0; y < 30; y++) {
                count.merge(BasePlanner.Palette.pick(options, x, y, 7), 1, Integer::sum);
            }
        }
        assertEquals(3, count.size(), "every block shows up");
        assertTrue(count.get("STONE_BRICKS") > 900 * 0.6 && count.get("STONE_BRICKS") < 900 * 0.85, "mostly the first");
        assertEquals(BasePlanner.Palette.pick(options, 3, 4, 5), BasePlanner.Palette.pick(options, 3, 4, 5),
                "the same block for the same spot (a mended hole looks like before)");
        assertEquals("OAK_PLANKS", BasePlanner.Palette.pick("OAK_PLANKS", 1, 2, 3));
        assertEquals("OAK_FENCE", new BasePlanner.Palette("OAK_PLANKS", "OAK_LOG", "OAK_PLANKS").fence());
    }

    @Test
    void thePaletteMatchesTheBiome() {
        assertEquals("SANDSTONE", BasePlanner.Palette.forBiome("minecraft:desert").wall());
        assertEquals("SPRUCE_PLANKS", BasePlanner.Palette.forBiome("minecraft:snowy_taiga").wall());
        assertEquals("OAK_PLANKS", BasePlanner.Palette.forBiome("minecraft:plains").wall());
        assertEquals("COBBLESTONE", BasePlanner.Palette.forBiome("minecraft:stony_peaks").wall());
        assertEquals("COBBLESTONE", BasePlanner.Palette.forBiome(null).wall());
    }

    @Test
    void theGearScheduleOnlyImprovesAndReachesPartDiamond() {
        double previous = -1;
        for (double p = 0; p <= 1.6; p += 0.05) {
            double score = Gear.at(p).score();
            assertTrue(score >= previous, "never worse at " + p);
            previous = score;
        }
        assertEquals(Gear.Weapon.STONE, Gear.at(0.15).sword(), "stone first");
        assertEquals(Gear.Armor.IRON, Gear.at(0.6).armor(0), "iron mid-farming");
        assertTrue(Gear.at(1.0).armor(1) == Gear.Armor.DIAMOND, "part diamond at PvP");
        assertTrue(Gear.at(1.0).armor(2) != Gear.Armor.DIAMOND, "but not full diamond");
    }

    @Test
    void bowAimLeadsAMovingTargetAndCompensatesTheDrop() {
        double[] still = Ballistics.launch(new double[] {0, 0, 0}, new double[] {30, 0, 0}, new double[] {0, 0, 0}, 1);
        assertTrue(still[1] > 0, "aims a little up for the drop");
        double[] moving = Ballistics.launch(new double[] {0, 0, 0}, new double[] {30, 0, 0},
                new double[] {0, 0, 0.3}, 1);
        assertTrue(moving[2] > 0.1, "leads a target moving sideways");
        double[] sloppy = Ballistics.launch(new double[] {0, 0, 0}, new double[] {30, 0, 0},
                new double[] {0, 0, 0.3}, 0.5);
        assertTrue(sloppy[2] < moving[2], "a weaker bot leads less");
        assertEquals(Ballistics.ARROW_SPEED, Math.sqrt(moving[0] * moving[0] + moving[1] * moving[1]
                + moving[2] * moving[2]), 1.0E-9);
    }

    private static int indexOf(Plan plan, Part part) {
        for (int i = 0; i < plan.placements().size(); i++) {
            if (plan.placements().get(i).part() == part) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void aBotWalksInAndOutOfEveryWalledBaseAlongItsDoorwayLane() {
        // regression: bots shut in their own base (pathfinder lost in the room, recovery refusing their walls) died
        // to the border; they now step out along the doorway lane, never through a wall
        for (Design design : new Design[] {Design.BOX, Design.WATCHPOST, Design.TRAPS, Design.KEEP, Design.BUNKER,
                Design.TOWER, Design.BURROW}) {
            for (int size : new int[] {5, 7, 9, 11}) {
                Plan plan = BasePlanner.plan(FLAT, 0, 0, size, 100, 30, design);
                Set<String> walls = new HashSet<>();
                for (Placement p : plan.placements()) {
                    if (p.part() != Part.CLEAR && p.part() != Part.FLOOR && p.y() == plan.floorY() + 1) {
                        walls.add(p.x() + "," + p.z());
                    }
                }
                for (double[] start : new double[][] {{0.5, 0.5}, {-plan.half() + 1.5, plan.half() - 0.5},
                        {plan.half() - 0.5, -plan.half() + 1.5}}) {
                    double x = start[0];
                    double z = start[1];
                    int steps = 0;
                    for (double[] next; (next = BotController.exitStep(plan, x, z)) != null; steps++) {
                        assertTrue(steps < 40, design + " " + size + ": out in a few steps");
                        // walk there in small bits: no bit may cross a wall cell
                        for (int k = 1; k <= 8; k++) {
                            double px = x + (next[0] - x) * k / 8;
                            double pz = z + (next[1] - z) * k / 8;
                            boolean inRoom = Math.abs(px - 0.5) < plan.half() - 0.5 && Math.abs(pz - 0.5)
                                    < plan.half() - 0.5;
                            if (!inRoom) {
                                assertFalse(walls.contains((int) Math.floor(px) + "," + (int) Math.floor(pz)),
                                        design + " " + size + " walks through a wall at " + px + "," + pz);
                            }
                        }
                        x = next[0];
                        z = next[1];
                    }
                    int[] out = plan.outer();
                    assertEquals(out[0] + 0.5, x, 0.6, design + " " + size + " ends at the spot outside");
                    assertEquals(out[2] + 0.5, z, 0.6, design + " " + size + " ends at the spot outside");
                }
                // and back in from the spot outside, along the lane to the room
                int[] out = plan.outer();
                double x = out[0] + 0.5;
                double z = out[2] + 0.5;
                int steps = 0;
                for (double[] next; (next = BotController.enterStep(plan, x, z)) != null; steps++) {
                    assertTrue(steps < 40, design + " " + size + ": in in a few steps");
                    x = next[0];
                    z = next[1];
                }
                assertEquals(plan.inner()[0] + 0.5, x, 0.2, design + " " + size + " ends in the room");
                assertEquals(plan.inner()[2] + 0.5, z, 0.2, design + " " + size + " ends in the room");
                assertTrue(plan.inside(x, plan.floorY() + 1, z), design + " " + size + " inside");
                if (design == Design.BURROW) {
                    // up its stairs a block per block out, to the top
                    assertEquals(plan.floorY() + 1, BotController.laneFeet(plan, plan.half()), 0.01);
                    assertEquals(plan.floorY() + 2, BotController.laneFeet(plan, plan.half() + 1), 0.01);
                    assertEquals(plan.outer()[1], BotController.laneFeet(plan, plan.half() + 4), 0.01);
                    assertTrue(BotController.onOwnStairs(plan, new org.bukkit.Location(null,
                            plan.doorX() + 0.5 + (plan.doorX() - plan.x()) / plan.half() * 2, plan.floorY() + 3,
                            plan.doorZ() + 0.5 + (plan.doorZ() - plan.z()) / plan.half() * 2)), "on its stairs");
                }
            }
        }
    }
}
