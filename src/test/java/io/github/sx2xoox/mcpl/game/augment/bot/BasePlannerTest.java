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
        List<Placement> clear = plan.placements().stream().filter(p -> p.part() == Part.CLEAR).toList();
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
    void aTrapperRingsItsBaseWithCobwebs() {
        long plain = BasePlanner.plan(FLAT, 0, 0, 7, 100, 0).placements().stream()
                .filter(p -> p.part() == Part.COBWEB).count();
        long trapped = BasePlanner.plan(FLAT, 0, 0, 7, 100, 0, true).placements().stream()
                .filter(p -> p.part() == Part.COBWEB).count();
        assertEquals(2, plain);
        assertEquals(6, trapped);
        Plan plan = BasePlanner.plan(FLAT, 0, 0, 7, 100, 0, true);
        for (Placement web : plan.placements()) {
            if (web.part() == Part.COBWEB) {
                assertFalse(plan.inside(web.x() + 0.5, web.z() + 0.5), "outside the walls");
                assertFalse(web.x() == plan.outer()[0] && web.z() == plan.outer()[2], "never in its own doorway");
            }
        }
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
}
