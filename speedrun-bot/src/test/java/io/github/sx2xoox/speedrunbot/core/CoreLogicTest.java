package io.github.sx2xoox.speedrunbot.core;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreLogicTest {

    // ---------------------------------------------------------------- mining time (1.16.1 numbers)

    @Test
    void stoneWithWoodenPickaxe() {
        // stone hardness 1.5, wooden pick speed 2: 2 / 1.5 / 30 = 0.0444 -> 23 ticks (1.15 s, the wiki value)
        float d = MiningTime.delta(1.5f, true, MiningTime.WOOD, 0, false, true);
        assertEquals(23, MiningTime.ticks(d));
    }

    @Test
    void stoneByHandIsSlowAndDropsNothing() {
        // 1 / 1.5 / 100 -> 150 ticks in exact maths; the client sums floats, so 151 ticks really
        float d = MiningTime.delta(1.5f, false, MiningTime.HAND, 0, false, true);
        assertEquals(151, MiningTime.ticks(d));
    }

    @Test
    void logByHandAndIronOreWithStonePick() {
        // log: 1 / 2 / 30 -> 60 ticks exact, 61 with the client's float sum
        assertEquals(61, MiningTime.ticks(MiningTime.delta(2f, true, MiningTime.HAND, 0, false, true)));
        // iron ore: 4 / 3 / 30 -> 23 ticks
        assertEquals(23, MiningTime.ticks(MiningTime.delta(3f, true, MiningTime.STONE, 0, false, true)));
    }

    @Test
    void airborneAndUnderwaterArePenalised() {
        float ground = MiningTime.delta(0.5f, true, MiningTime.HAND, 0, false, true);
        float air = MiningTime.delta(0.5f, true, MiningTime.HAND, 0, false, false);
        float both = MiningTime.delta(0.5f, true, MiningTime.HAND, 0, true, false);
        assertEquals(ground / 5f, air, 1e-6);
        assertEquals(ground / 25f, both, 1e-6);
    }

    @Test
    void instantAndUnbreakable() {
        assertEquals(0, MiningTime.ticks(MiningTime.delta(0f, true, 1f, 0, false, true)));
        assertEquals(Integer.MAX_VALUE, MiningTime.ticks(MiningTime.delta(-1f, true, 1f, 0, false, true)));
        assertEquals(1, MiningTime.ticksWithCooldown(2f));
        assertEquals(23 + MiningTime.BREAK_COOLDOWN_TICKS,
                MiningTime.ticksWithCooldown(MiningTime.delta(1.5f, true, MiningTime.WOOD, 0, false, true)));
    }

    // ---------------------------------------------------------------- IGT

    @Test
    void igtCountsServerTicksFromWorldEntry() {
        Igt igt = new Igt(1000);
        assertTrue(igt.split("wood", 1400));
        assertFalse(igt.split("wood", 1500));
        assertEquals(400, igt.splitTicks("wood"));
        assertEquals(-1, igt.splitTicks("nether"));
        assertEquals("0:20.000", Igt.format(igt.splitTicks("wood")));
        assertEquals("2:30.050", Igt.format(3001));
        assertEquals(150.05, Igt.seconds(3001), 1e-9);
    }

    // ---------------------------------------------------------------- input limits

    @Test
    void turningIsRateLimitedAlongTheShortestWay() {
        assertEquals(45f, InputLimits.approachAngle(0f, 170f, InputLimits.MAX_YAW_PER_TICK), 1e-4);
        assertEquals(-45f, InputLimits.approachAngle(0f, 190f, InputLimits.MAX_YAW_PER_TICK), 1e-4);
        assertEquals(10f, InputLimits.approachAngle(0f, 10f, InputLimits.MAX_YAW_PER_TICK), 1e-4);
        assertEquals(90f, InputLimits.approachPitch(80f, 120f, 35f), 1e-4);
        assertEquals(0f, InputLimits.yawTo(0, 1), 1e-4);
        assertEquals(90f, InputLimits.wrap(InputLimits.yawTo(-1, 0)), 1e-4);
        assertEquals(90f, InputLimits.pitchTo(0, -1, 0), 1e-4);
    }

    // ---------------------------------------------------------------- fair perception

    @Test
    void viewFrustumAndRenderDistance() {
        PerceptionRules fair = new PerceptionRules(true, 8, 200);
        assertEquals(128, fair.maxRange(), 1e-9);
        // looking +Z (yaw 0, pitch 0)
        assertTrue(fair.inView(0f, 0f, 0, 0, 10));
        assertFalse(fair.inView(0f, 0f, 0, 0, -10), "behind");
        assertFalse(fair.inView(0f, 0f, 0, 10, 1), "straight up is outside a 70 deg vertical FOV");
        assertTrue(fair.inView(0f, 0f, 0.5, 0.5, -0.5), "touching distance is always sensed");
        assertFalse(fair.inView(0f, 0f, 0, 0, 129), "beyond render distance");
        PerceptionRules omni = new PerceptionRules(false, 8, 200);
        assertTrue(omni.inView(0f, 0f, 0, 0, -10));
    }

    @Test
    void sampledRaysStayInsideTheFrustum() {
        PerceptionRules fair = new PerceptionRules(true, 8, 96);
        Random r = new Random(1);
        for (int i = 0; i < 2000; i++) {
            float yaw = r.nextFloat() * 360 - 180;
            float pitch = r.nextFloat() * 180 - 90;
            double[] d = fair.sampleRay(yaw, pitch, r);
            assertTrue(fair.inView(yaw, pitch, d[0] * 50, d[1] * 50, d[2] * 50), "ray " + i);
        }
    }

    // ---------------------------------------------------------------- route selector

    @Test
    void routeSelectorPrefersANearSurfacePoolOnlyWhenItIsWorthIt() {
        Params p = Params.defaults();
        RouteSelector s = new RouteSelector(p);
        RouteSelector.Observation none = new RouteSelector.Observation().water(20);
        assertEquals(RouteSelector.Route.DIG_CAST, s.choose(none).route);

        RouteSelector.Observation near = new RouteSelector.Observation().surfaceLava(25).water(20).lavaToWater(10).iron(4, 15);
        assertEquals(RouteSelector.Route.SURFACE_CAST, s.choose(near).route);

        RouteSelector.Observation tooFar = new RouteSelector.Observation().surfaceLava(500).water(20);
        assertEquals(1, s.rank(tooFar).size());

        // without iron in sight the surface route pays for each missing ingot
        RouteSelector.Observation noIron = new RouteSelector.Observation().surfaceLava(25).water(20).lavaToWater(10);
        assertEquals(RouteSelector.Route.DIG_CAST, s.choose(noIron).route);
        p.with("route.ironMissingSec", 1);
        assertEquals(RouteSelector.Route.SURFACE_CAST, s.choose(noIron).route);
    }

    @Test
    void paramsOverrideDefaults() {
        Params p = Params.fromJson("{\"wood.logs\": 7, \"unknown.key\": 3}");
        assertEquals(7, p.getInt("wood.logs"));
        assertEquals(Params.DEFAULTS.get("stone.cobble"), p.get("stone.cobble"));
        assertTrue(p.toJson().contains("wood.logs"));
    }

    // ---------------------------------------------------------------- portal casting plan

    @Test
    void portalPlanGeometry() {
        PortalPlan plan = new PortalPlan(new Cell(10, 11, 20), new Cell(1, 0, 0), new Cell(0, 0, -1));
        List<Cell> frame = plan.frame();
        assertEquals(10, frame.size());
        assertEquals(6, plan.interior().size());
        Set<Cell> all = new HashSet<>(frame);
        all.addAll(plan.interior());
        all.addAll(plan.corners());
        assertEquals(20, all.size(), "4x5 rectangle without overlaps");
        // the frame is a valid nether portal outline: bottom row at y=11, top at y=15, all in plane z=20
        for (Cell c : frame) {
            assertEquals(20, c.z);
            assertTrue(c.y >= 11 && c.y <= 15);
        }
        // bottom first, top last (each cast keeps rock or obsidian under it)
        assertEquals(11, frame.get(0).y);
        assertEquals(15, frame.get(9).y);
        // fronts of non-bottom frame cells are room cells, bottom fronts are floor holes
        Set<Cell> room = new HashSet<>(plan.room());
        for (Cell c : frame) {
            if (plan.isBottom(c)) {
                assertTrue(plan.floorHoles().contains(plan.front(c)));
            } else {
                assertTrue(room.contains(plan.front(c)), "front of " + c);
            }
        }
        // the cells that must hold the lava never include the room
        for (Cell c : frame) {
            for (Cell s : plan.mustBeSolid(c)) {
                assertFalse(room.contains(s), s + " next to " + c);
                assertFalse(plan.floorHoles().contains(s));
            }
        }
        assertFalse(room.contains(plan.waterStation()));
        assertEquals(plan.waterStation().y, 11);
        assertTrue(plan.interior().contains(plan.fireCell()));
        assertEquals(new Cell(11, 11, 20), plan.lightTarget());
    }

    @Test
    void castOrderKeepsEachPocketClosed() {
        // when a cell is cast, every must-be-solid neighbour is rock (not yet carved) or already obsidian
        PortalPlan plan = new PortalPlan(new Cell(0, 64, 0), new Cell(0, 0, 1), new Cell(1, 0, 0));
        Set<Cell> carvedBefore = new HashSet<>(plan.room());
        Set<Cell> obsidian = new HashSet<>();
        for (Cell c : plan.frame()) {
            for (Cell s : plan.mustBeSolid(c)) {
                assertFalse(carvedBefore.contains(s), "neighbour " + s + " of " + c + " is carved");
            }
            obsidian.add(c);
        }
        assertEquals(10, obsidian.size());
    }

    @Test
    void cellPackingRoundTrips() {
        Cell[] cs = {new Cell(0, 0, 0), new Cell(-5, 70, 123456), new Cell(29999984, 255, -29999984), new Cell(-1, 1, -1)};
        for (Cell c : cs) {
            assertEquals(c, Cell.unpack(c.pack()));
        }
    }
}
