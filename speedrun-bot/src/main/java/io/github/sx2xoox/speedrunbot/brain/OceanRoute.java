package io.github.sx2xoox.speedrunbot.brain;

import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.item.BoatItem;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * The ocean route after the buried treasure (design.md section 13): tools from the treasure iron, a boat across the
 * sea to an underwater magma ravine (found by the bubble columns its magma floor sends up, seen through the water
 * within the water fog), down the bubble column to the ravine floor, and a dry pocket in the ravine wall behind a
 * door (doors keep water out); from there the normal casting goals take over (lava under the ravine floor, water
 * bucket filled at sea). Boat physics are vanilla; the bot only holds keys (W/A/D, sneak to get out).
 */
public final class OceanRoute {
    public enum Phase { CRAFT, GRAVEL, LAUNCH, BOARD, SAIL, DIVE, SURFACE, WALL, DOOR, OPEN, DRY_DIG, ENTER, DONE, FAILED }

    private final RunBrain b;
    private Phase phase = Phase.CRAFT;
    private int phaseTicks;
    private int totalTicks;
    private String why = "";
    private float heading = Float.NaN;
    private float lastBoatYaw = Float.NaN;
    private BlockPos column;
    private BlockPos stand;
    private Direction wallDir;
    private int launchTries;
    private int legTicks;
    private BlockPos dry;
    private Vec3d lastPos;
    private Vec3d spiralCentre;
    private Vec3d waypoint;
    private int spiralDir;
    private int spiralLeg;
    private Vec3d spiralAt;

    private void nextWaypoint(Vec3d p) {
        if (spiralAt == null) {
            spiralAt = spiralCentre;
        }
        spiralLeg++;
        int len = 48 * ((spiralLeg + 1) / 2);
        double rad = Math.toRadians(spiralDir * 90f);
        spiralAt = spiralAt.add(-Math.sin(rad) * len, 0, Math.cos(rad) * len);
        spiralDir = (spiralDir + 1) % 4;
        waypoint = spiralAt;
        legTicks = 0;
    }

    public OceanRoute(RunBrain b) {
        this.b = b;
        if (RunBrain.DEV_OCEAN != null) {
            phase = Phase.valueOf(RunBrain.DEV_OCEAN);
        }
    }

    public Phase phase() {
        return phase;
    }

    public boolean finished() {
        return phase == Phase.DONE || phase == Phase.FAILED;
    }

    /** Under the sea at the ravine floor: air comes from the door pocket, not from swimming up 50 blocks. */
    public boolean managesAir() {
        return phase.ordinal() >= Phase.DIVE.ordinal() && phase.ordinal() <= Phase.ENTER.ordinal() && phase != Phase.SURFACE;
    }

    public boolean failed() {
        return phase == Phase.FAILED;
    }

    public String why() {
        return why;
    }

    /** The dry cell in the ravine wall (null before it exists). */
    public BlockPos dryCell() {
        return dry;
    }

    private void go(Phase p) {
        if (p != phase) {
            b.trace("ocean " + phase + " -> " + p);
        }
        phase = p;
        phaseTicks = 0;
    }

    private void fail(String reason) {
        why = reason;
        b.trace("ocean route failed: " + reason);
        go(Phase.FAILED);
    }

    public void tick() {
        phaseTicks++;
        totalTicks++;
        if (managesAir() && phase != Phase.DIVE) {
            b.c.noFloat(); // stay on the bottom
        }
        if (phase.ordinal() >= Phase.DIVE.ordinal() && phase.ordinal() <= Phase.ENTER.ordinal() && b.s.onGround()) {
            BlockState under = b.mem.get(b.feet().down());
            if (under != null && under.getBlock() == Blocks.MAGMA_BLOCK) {
                b.c.holdSneak(); // sneak: magma burns only those who do not
            }
        }
        switch (phase) {
            case CRAFT:
                craft();
                break;
            case GRAVEL:
                gravel();
                break;
            case LAUNCH:
                launch();
                break;
            case BOARD:
                board();
                break;
            case SAIL:
                sail();
                break;
            case DIVE:
                dive();
                break;
            case SURFACE:
                // swim up and get back into the boat (it floats where it was left)
                b.c.lookAngles(b.s.yaw(), -60f);
                b.c.move(0f, 0f, true, false, false);
                if (b.s.pos().y > 61.5 || phaseTicks > 300) {
                    go(Phase.BOARD);
                }
                break;
            case WALL:
                wall();
                break;
            case DOOR:
                door();
                break;
            case OPEN:
                openDoor();
                break;
            case DRY_DIG:
                dryDig();
                break;
            case ENTER:
                enter();
                break;

            default:
                break;
        }
    }

    // ------------------------------------------------------------------ crafting

    static boolean isDoor(Item i) {
        return i == Items.OAK_DOOR || i == Items.SPRUCE_DOOR || i == Items.BIRCH_DOOR || i == Items.JUNGLE_DOOR || i == Items.ACACIA_DOOR
                || i == Items.DARK_OAK_DOOR;
    }

    private static boolean isBoat(Item i) {
        return i instanceof BoatItem;
    }

    private int count(java.util.function.Predicate<Item> p) {
        return Actions.count(b.s.inventory(), st -> !st.isEmpty() && p.test(st.getItem()));
    }

    private void craft() {
        List<Workbench.Need> needs = new ArrayList<>();
        int iron = b.inv().count(Items.IRON_INGOT);
        boolean pick = b.inv().pickaxe();
        if (!pick) {
            if (iron >= 7) {
                needs.add(new Workbench.Need("iron_pickaxe", i -> i == Items.IRON_PICKAXE, 1, true));
            } else {
                needs.add(new Workbench.Need("wooden_pickaxe", i -> i == Items.WOODEN_PICKAXE, 1, true));
            }
        }
        if (b.inv().buckets() == 0) {
            needs.add(new Workbench.Need("bucket", i -> i == Items.BUCKET, 1, true));
        }
        if (count(OceanRoute::isBoat) == 0) {
            needs.add(new Workbench.Need("boat", OceanRoute::isBoat, 1, true));
        }
        if (count(OceanRoute::isDoor) == 0) {
            needs.add(new Workbench.Need("door", OceanRoute::isDoor, 1, true));
        }
        if (needs.isEmpty()) {
            if (!b.s.screenOpen()) {
                go(Phase.GRAVEL);
            } else {
                b.c.closeScreen();
            }
            return;
        }
        Actions.Status st = b.bench.run(needs);
        if (st == Actions.Status.FAILED) {
            fail("craft_" + b.bench.error());
        }
    }

    // ------------------------------------------------------------------ gravel (flint for the lighter, later)

    private BlockPos gravelTarget;

    /** A little gravel from the shore or shallow sea floor: under the sea every gravel block is next to water. */
    private void gravel() {
        if (b.inv().has(Items.FLINT) || b.inv().count(Items.GRAVEL) >= 3 || b.inv().lighter() || phaseTicks > 600) {
            go(Phase.LAUNCH);
            return;
        }
        BlockPos f = b.feet();
        if (gravelTarget == null || b.mem.get(gravelTarget) == null || b.mem.get(gravelTarget).getBlock() != Blocks.GRAVEL) {
            gravelTarget = b.mem.nearest(WorldMemory.Kind.GRAVEL, f, 28, l -> !b.badTargets().contains(l) && BlockPos.unpackLongY(l) >= 48);
            if (gravelTarget == null) {
                b.trace("no gravel seen near the shore: flint later");
                go(Phase.LAUNCH);
                return;
            }
        }
        Navigator.Goal g = Navigator.reach(gravelTarget, 4.0);
        if (!g.done(f.getX(), f.getY(), f.getZ())) {
            if (b.mover.go(g) == Mover.Status.FAILED) {
                b.badTargets().add(gravelTarget.asLong());
                gravelTarget = null;
            }
            return;
        }
        if (b.a.mine(gravelTarget, true) == Actions.Status.FAILED) {
            b.badTargets().add(gravelTarget.asLong());
            gravelTarget = null;
        }
    }

    // ------------------------------------------------------------------ boat

    /** A sea-level water cell next to the bot (where the boat goes), or null. */
    private BlockPos waterSpot() {
        BlockPos f = b.feet();
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int y = 61; y <= 62; y++) {
                    BlockPos q = new BlockPos(f.getX() + dx, y, f.getZ() + dz);
                    BlockState st = b.mem.get(q), up = b.mem.get(q.up());
                    if (st == null || up == null || !Blocks2.isWater(st) || !up.isAir()) {
                        continue;
                    }
                    double d = Vec3d.ofCenter(q).squaredDistanceTo(b.s.eyePos());
                    if (d >= 2.5 * 2.5 && d < bd && d <= 4.2 * 4.2) {
                        bd = d;
                        best = q;
                    }
                }
            }
        }
        return best;
    }

    private BlockPos seaGoal;

    private void launch() {
        if (b.s.inBoat()) {
            b.split("boat");
            go(Phase.SAIL);
            return;
        }
        if (!b.inv().has(Items.WATER_BUCKET) && b.inv().buckets() > 0 && b.s.inWater()) {
            // fill the bucket now (the dry pocket under the sea needs water for the casting)
            if (b.a.equip(Items.BUCKET) == Actions.Status.DONE) {
                b.c.lookAngles(b.s.yaw(), 70f);
                if (b.s.pitch() > 60f) {
                    b.c.use();
                }
            }
            return;
        }
        if (phaseTicks > 1200) {
            fail("launch_timeout");
            return;
        }
        BlockPos spot = waterSpot();
        if (spot == null) {
            // walk to the sea: the nearest seen sea-level water
            if (seaGoal == null || phaseTicks % 200 == 0) {
                seaGoal = b.mem.nearest(WorldMemory.Kind.WATER_SOURCE, b.feet(), 96, l -> BlockPos.unpackLongY(l) == 62);
            }
            if (seaGoal == null || b.mover.go(Navigator.near(seaGoal, 2)) == Mover.Status.FAILED) {
                b.mover.reset();
                b.c.move(1f, 0f, false, true, false);
            }
            return;
        }
        if (b.a.equip(OceanRoute::isBoatStack) != Actions.Status.DONE) {
            return;
        }
        // aim at the water surface and use the boat (BoatItem rays through fluids)
        Vec3d aim = Vec3d.ofCenter(spot).add(0, 0.45, 0);
        b.c.lookAt(aim);
        if (b.c.aimed(aim, 2f) && b.c.use()) {
            launchTries++;
            go(Phase.BOARD);
        }
    }

    private static boolean isBoatStack(net.minecraft.item.ItemStack st) {
        return !st.isEmpty() && isBoat(st.getItem());
    }

    private void board() {
        if (b.s.inBoat()) {
            b.split("boat");
            go(Phase.SAIL);
            return;
        }
        List<BoatEntity> boats = b.perception.visible(BoatEntity.class, 24, e -> !e.hasPassengers());
        if (boats.isEmpty()) {
            b.c.lookAngles(b.s.yaw() + 20f, 10f); // look around for it
            if (phaseTicks > 60) {
                go(launchTries < 4 && count(OceanRoute::isBoat) > 0 ? Phase.LAUNCH : Phase.FAILED);
                if (phase == Phase.FAILED) {
                    why = "no_boat";
                }
            }
            return;
        }
        BoatEntity boat = boats.get(0);
        Vec3d aim = boat.getPos().add(0, 0.3, 0);
        if (boat.getPos().distanceTo(b.s.eyePos()) > 2.6) {
            b.c.lookAt(aim);
            b.c.move(1f, 0f, false, false, false);
            return;
        }
        b.c.lookAt(aim);
        if (b.c.aimed(aim, 4f)) {
            b.c.useEntity();
        }
        if (phaseTicks > 500) {
            fail("board_timeout");
        }
    }

    /** Open sea away from the shore: the mean direction from here to seen sea-level water far from land. */
    private float seaHeading() {
        BlockPos f = b.feet();
        double sx = 0, sz = 0;
        LongIterator it = b.mem.of(WorldMemory.Kind.WATER_SOURCE).iterator();
        int k = 0;
        while (it.hasNext() && k < 20000) {
            long l = it.nextLong();
            k++;
            if (BlockPos.unpackLongY(l) != 62) {
                continue;
            }
            double dx = BlockPos.unpackLongX(l) - f.getX(), dz = BlockPos.unpackLongZ(l) - f.getZ();
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d < 8) {
                continue;
            }
            sx += dx / d;
            sz += dz / d;
        }
        return io.github.sx2xoox.speedrunbot.core.InputLimits.yawTo(sx, sz);
    }

    /** Land ahead at or above the water surface within this distance along the heading. */
    private boolean landAhead(float yaw, int dist) {
        Vec3d p = b.s.pos();
        double rad = Math.toRadians(yaw);
        double dx = -Math.sin(rad), dz = Math.cos(rad);
        for (int i = 2; i <= dist; i++) {
            BlockPos q = new BlockPos(p.x + dx * i, 62, p.z + dz * i);
            for (int y = 62; y <= 63; y++) {
                BlockState st = b.mem.get(q.getX(), y, q.getZ());
                if (st != null && Blocks2.pass(st) == Blocks2.Pass.SOLID) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The nearest seen downward bubble column cell (a magma floor below), as an x,z column. */
    private BlockPos bubbles() {
        BlockPos f = b.feet();
        return b.mem.nearest(WorldMemory.Kind.BUBBLES_DOWN, new BlockPos(f.getX(), 55, f.getZ()), 80,
                l -> !badColumns.contains(BlockPos.asLong(BlockPos.unpackLongX(l), 0, BlockPos.unpackLongZ(l))) && cluster(l) >= MIN_CLUSTER);
    }

    /** Bubble columns of a ravine floor come many to a place (a quarter of the floor is magma); a ruin's few do not. */
    private static final int MIN_CLUSTER = 5;
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet badColumns = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

    private int cluster(long at) {
        int x0 = BlockPos.unpackLongX(at), z0 = BlockPos.unpackLongZ(at);
        it.unimi.dsi.fastutil.longs.LongOpenHashSet cols = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        LongIterator it = b.mem.of(WorldMemory.Kind.BUBBLES_DOWN).iterator();
        while (it.hasNext()) {
            long l = it.nextLong();
            int dx = BlockPos.unpackLongX(l) - x0, dz = BlockPos.unpackLongZ(l) - z0;
            if (dx * dx + dz * dz <= 100) {
                cols.add(BlockPos.asLong(BlockPos.unpackLongX(l), 0, BlockPos.unpackLongZ(l)));
            }
        }
        return cols.size();
    }

    /** A column that ended on a shallow magma block (a ruin, not a ravine): never again, nor its neighbours. */
    private void rejectColumn(BlockPos c) {
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                badColumns.add(BlockPos.asLong(c.getX() + dx, 0, c.getZ() + dz));
            }
        }
    }

    private void sail() {
        if (!b.s.inBoat()) {
            if (column != null && phaseTicks > 5) {
                go(Phase.DIVE);
                return;
            }
            if (phaseTicks > 40) {
                go(Phase.LAUNCH); // knocked out of the boat: get back in
            }
            return;
        }
        legTicks++;
        Vec3d now = b.s.pos();
        if (lastPos != null && now.y < lastPos.y - 0.2) {
            BlockPos f = b.feet();
            b.trace(String.format("sinking y=%.2f below=%s at=%s up=%s", now.y, b.mem.get(f.down()), b.mem.get(f), b.mem.get(f.up())));
        }
        if (phaseTicks % 40 == 0) {
            b.trace(String.format("sail pos=%.1f,%.1f,%.1f boatYaw=%.0f heading=%.0f bubbles seen=%d", now.x, now.y, now.z, b.s.boatYaw(), heading,
                    b.mem.count(WorldMemory.Kind.BUBBLES_DOWN)));
        }
        double speed = lastPos == null ? 0 : Math.hypot(now.x - lastPos.x, now.z - lastPos.z); // the passenger's velocity reads zero
        lastPos = now;
        if (totalTicks > b.p.getInt("ocean.sailTicks") + 2400) {
            fail("no_ravine");
            return;
        }
        float by = b.s.boatYaw();
        BlockPos col = bubbles();
        if (col != null && column == null) {
            b.split("ravine");
            b.trace("bubble column (magma floor below) seen at " + col);
        }
        if (col != null) {
            column = col;
        }
        float lookYaw;
        float lookPitch;
        float forward = 1f;
        if (column != null) {
            Vec3d p = b.s.pos();
            double dx = column.getX() + 0.5 - p.x, dz = column.getZ() + 0.5 - p.z;
            double dist = Math.sqrt(dx * dx + dz * dz);
            heading = io.github.sx2xoox.speedrunbot.core.InputLimits.yawTo(dx, dz);
            lookYaw = heading;
            lookPitch = 45f;
            if (dist < 3.5) {
                forward = speed > 0.08 ? -1f : 0f; // brake
            }
            if (dist < 1.0 || (dist < 1.8 && speed < 0.05)) {
                b.trace(String.format("getting out over the bubbles at %.1f,%.1f", p.x, p.z));
                b.c.move(0f, 0f, false, false, true); // sneak: out of the boat
                return;
            }
        } else {
            // a square spiral around where the boat was launched (legs 48, 48, 96, 96, ...): the bubbles of a magma
            // ravine show at the surface and through the water within the fog on both sides of the course
            Vec3d p = b.s.pos();
            if (spiralCentre == null) {
                spiralCentre = p;
                spiralDir = (int) Math.floorMod(Math.round(seaHeading() / 90f), 4);
                nextWaypoint(p);
                b.trace(String.format("searching in a spiral from %.0f,%.0f", p.x, p.z));
            }
            double dx = waypoint.x - p.x, dz = waypoint.z - p.z;
            if (dx * dx + dz * dz < 8 * 8 || legTicks > 600) {
                nextWaypoint(p);
                dx = waypoint.x - p.x;
                dz = waypoint.z - p.z;
            }
            heading = io.github.sx2xoox.speedrunbot.core.InputLimits.yawTo(dx, dz);
            if (legTicks > 40 && legTicks % 20 == 0 && landAhead(heading, 10)) {
                b.trace(String.format("land ahead of waypoint %.0f,%.0f: next one", waypoint.x, waypoint.z));
                nextWaypoint(p);
            }
            // sweep the view over the water on both sides: the bubbles show within the water fog
            int t = phaseTicks % 80;
            lookYaw = heading + (t < 40 ? -70 + t * 3.5f : 70 - (t - 40) * 3.5f);
            lookPitch = 25f;
        }
        b.c.lookAngles(lookYaw, lookPitch);
        steer(heading, forward);
    }

    /** Holds the boat keys for one tick: W (forward) and A/D toward the wanted heading. */
    private void steer(float wanted, float forward) {
        float by = b.s.boatYaw();
        float err = io.github.sx2xoox.speedrunbot.core.InputLimits.wrap(wanted - by);
        float yawVel = Float.isNaN(lastBoatYaw) ? 0f : io.github.sx2xoox.speedrunbot.core.InputLimits.wrap(by - lastBoatYaw);
        lastBoatYaw = by;
        // steer: A turns left (yaw down), D right (yaw up); lead the turn by the current turn rate
        float pred = err - yawVel * 6f;
        float strafe = pred > 4f ? -1f : pred < -4f ? 1f : 0f;
        if (Math.abs(err) > 60f && forward > 0) {
            forward = 0.0f; // turn on the spot first
        }
        b.c.move(forward, strafe, false, false, false);
    }

    // ------------------------------------------------------------------ ferry (the boat to a place, e.g. the treasure)

    /** Boat or no boat to take over water: carried, or ridden. */
    public boolean hasBoat() {
        return b.s.inBoat() || count(OceanRoute::isBoat) > 0 || !b.perception.visible(BoatEntity.class, 8, e -> !e.hasPassengers()).isEmpty();
    }

    private int ferryTicks;
    private int ferryPlace;

    /**
     * One tick of crossing water by boat toward (x,z): put the boat in, get in, steer, and get out when there or when
     * land is right ahead. RUNNING while under way, ARRIVED when out of the boat near the place or the shore, FAILED
     * when there is no boat to use.
     */
    public Mover.Status ferry(int x, int z) {
        ferryTicks++;
        Vec3d p = b.s.pos();
        double dx = x + 0.5 - p.x, dz = z + 0.5 - p.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (!b.s.inBoat()) {
            if (count(OceanRoute::isBoat) > 0) {
                BlockPos spot = waterSpot();
                if (spot == null || b.a.equip(OceanRoute::isBoatStack) != Actions.Status.DONE) {
                    return spot == null ? Mover.Status.FAILED : Mover.Status.RUNNING;
                }
                Vec3d aim = Vec3d.ofCenter(spot).add(0, 0.45, 0);
                b.c.lookAt(aim);
                if (b.c.aimed(aim, 2f) && b.c.use()) {
                    ferryPlace++;
                }
                return ferryPlace > 4 ? Mover.Status.FAILED : Mover.Status.RUNNING;
            }
            List<BoatEntity> boats = b.perception.visible(BoatEntity.class, 8, e -> !e.hasPassengers());
            if (boats.isEmpty()) {
                return Mover.Status.FAILED;
            }
            Vec3d aim = boats.get(0).getPos().add(0, 0.3, 0);
            b.c.lookAt(aim);
            if (aim.distanceTo(b.s.eyePos()) > 2.6) {
                b.c.move(1f, 0f, false, false, false);
            }
            if (b.c.aimed(aim, 4f)) {
                b.c.useEntity();
            }
            return Mover.Status.RUNNING;
        }
        float h = io.github.sx2xoox.speedrunbot.core.InputLimits.yawTo(dx, dz);
        if (dist < 2.5 || (dist < 40 && landAhead(h, 3))) {
            b.trace(String.format("ferry: out of the boat %.0f blocks from %d,%d", dist, x, z));
            b.c.move(0f, 0f, false, false, true);
            return Mover.Status.ARRIVED;
        }
        b.c.lookAngles(h, 20f);
        steer(h, dist < 6 ? 0.4f : 1f);
        return Mover.Status.RUNNING;
    }

    // ------------------------------------------------------------------ under water

    private int floorTicks;

    private void dive() {
        Vec3d p = b.s.pos();
        if (column == null && RunBrain.DEV_OCEAN != null) {
            column = b.feet();
        }
        BlockPos f = b.feet();
        boolean over = isBubbles(f) || isBubbles(f.up());
        if (!over) {
            // into the nearest bubble column at about this depth (it drags down fast), else toward the one seen
            BlockPos t = b.mem.nearest(WorldMemory.Kind.BUBBLES_DOWN, f, 12, l -> Math.abs(BlockPos.unpackLongY(l) - f.getY()) <= 3);
            if (t == null) {
                t = column;
            }
            if (t != null) {
                double dx = t.getX() + 0.5 - p.x, dz = t.getZ() + 0.5 - p.z;
                float yaw = io.github.sx2xoox.speedrunbot.core.InputLimits.yawTo(dx, dz);
                b.c.lookAngles(yaw, 35f);
                b.c.move(dx * dx + dz * dz > 1 ? 1f : 0.4f, 0f, false, false, false);
                b.c.sink();
            }
        }
        if (over) {
            b.c.lookAngles(b.s.yaw() + 4f, 80f); // look down and around: the floor and walls come into view
            b.c.sink();
        }
        if (phaseTicks % 20 == 0) {
            b.trace(String.format("diving y=%.1f air=%d hp=%.0f over=%s", p.y, b.s.air(), b.s.health(), over));
        }
        if (b.s.onGround() && p.y >= 20 && over) {
            BlockState under = b.mem.get(b.feet().down());
            if (under != null && under.getBlock() == Blocks.MAGMA_BLOCK) {
                b.trace(String.format("the bubbles end on magma at y %.0f: no ravine here, back up", p.y));
                rejectColumn(column);
                column = null;
                go(Phase.SURFACE);
                return;
            }
        }
        boolean atFloor = (b.s.onGround() && p.y < 20) || (p.y < 13 && Math.abs(b.s.velocity().y) < 0.02);
        if (atFloor) {
            if (++floorTicks > 3) {
                b.split("ravine_floor");
                b.trace(String.format("at the ravine floor y=%.1f air=%d", p.y, b.s.air()));
                go(Phase.WALL);
            }
        } else {
            floorTicks = 0;
        }
        if (phaseTicks > 500) {
            fail("dive_timeout");
        }
    }

    private boolean isBubbles(BlockPos q) {
        BlockState st = b.mem.get(q);
        return st != null && st.getBlock() == Blocks.BUBBLE_COLUMN;
    }

    /** Solid wall rock a door pocket can be cut into (not falling, not obsidian, not ore of interest). */
    private boolean wallRock(BlockPos q) {
        BlockState st = b.mem.get(q);
        if (st == null || Blocks2.pass(st) != Blocks2.Pass.SOLID || Blocks2.isLava(st)) {
            return false;
        }
        if ((st.getBlock() == Blocks.GRAVEL || st.getBlock() == Blocks.SAND) && q.getY() > 11) {
            return false; // falling blocks only at the bottom row
        }
        return st.getBlock() != Blocks.OBSIDIAN && st.getBlock() != Blocks.MAGMA_BLOCK && st.getBlock() != Blocks.BEDROCK;
    }

    /** A cell in water with solid ground under it, next to two layers of wall rock in some direction. */
    private boolean wallSpot(BlockPos q, Direction d) {
        BlockState here = b.mem.get(q), head = b.mem.get(q.up()), under = b.mem.get(q.down());
        if (under == null && q.getY() == 11) {
            under = Blocks.OBSIDIAN.getDefaultState(); // the floor of an underwater ravine (y 10 is always obsidian or magma)
        }
        if (here == null) {
            here = Blocks.WATER.getDefaultState();
        }
        if (head == null) {
            head = Blocks.WATER.getDefaultState();
        }
        if (under == null || !floor(under)) {
            return false;
        }
        if (under.getBlock() == Blocks.MAGMA_BLOCK && !q.equals(b.feet())) {
            return false; // working there burns (sneaking would do, but a cell without magma is better)
        }
        if (Blocks2.pass(here) == Blocks2.Pass.SOLID || Blocks2.pass(head) == Blocks2.Pass.SOLID) {
            return false;
        }
        BlockPos w1 = q.offset(d), w2 = w1.offset(d);
        BlockState w1under = b.mem.get(w1.down());
        // the block under the opening is hidden in the rock: unseen counts as rock (it carries the door)
        return wallRock(w1) && wallRock(w1.up()) && (w1under == null || (Blocks2.pass(w1under) == Blocks2.Pass.SOLID && !Blocks2.isLava(w1under))) && (b.mem.get(w2) == null || wallRock(w2)) && (b.mem.get(w2.up()) == null || wallRock(w2.up()));
    }

    private void wall() {
        if (b.s.air() < 60 && phaseTicks > 40) {
            b.trace("air low at the floor (" + b.s.air() + ")");
        }
        BlockPos f = b.feet();
        if (stand == null || (phaseTicks % 60 == 0 && !f.equals(stand) && !wallStarted)) {
            stand = null;
            // floor cells reachable by walking on this level (the drag of the bubbles only pulls down)
            java.util.ArrayDeque<BlockPos> open = new java.util.ArrayDeque<>();
            java.util.Map<BlockPos, BlockPos> seen = new java.util.HashMap<>();
            open.add(f);
            seen.put(f, null);
            seenFloor = 0;
            while (!open.isEmpty() && stand == null && seen.size() < 200) {
                seenFloor++;
                BlockPos q = open.poll();
                for (Direction d : Direction.Type.HORIZONTAL) {
                    if (wallSpot(q, d)) {
                        stand = q;
                        wallDir = d;
                        break;
                    }
                }
                for (Direction d : Direction.Type.HORIZONTAL) {
                    BlockPos n = q.offset(d);
                    BlockState ns = b.mem.get(n), nh = b.mem.get(n.up()), nu = b.mem.get(n.down());
                    // unseen cells count as open water over a floor (the ravine floor is continuous at this level)
                    if (seen.containsKey(n) || (ns != null && Blocks2.pass(ns) == Blocks2.Pass.SOLID)
                            || (nh != null && Blocks2.pass(nh) == Blocks2.Pass.SOLID) || (nu != null && !floor(nu))
                            || (ns == null && nh == null) || Math.abs(n.getX() - f.getX()) > 10
                            || Math.abs(n.getZ() - f.getZ()) > 10) {
                        continue;
                    }
                    seen.put(n, q);
                    open.add(n);
                }
            }
            floorPath.clear();
            for (BlockPos q = stand; q != null; q = seen.get(q)) {
                floorPath.addFirst(q);
            }
            if (stand != null) {
                b.trace("wall spot " + stand + " facing " + wallDir + " (feet " + f + ")");
            }
            if (stand == null) {
                // look around to see the walls
                b.c.lookAngles(b.s.yaw() + 30f, 20f);
                if (phaseTicks > 160) {
                    int rock = 0, known = 0;
                    for (BlockPos q : BlockPos.iterate(f.add(-8, 0, -8), f.add(8, 1, 8))) {
                        if (b.mem.get(q) != null) {
                            known++;
                            if (wallRock(q)) {
                                rock++;
                            }
                        }
                    }
                    for (Direction d : Direction.Type.HORIZONTAL) {
                        BlockPos n = f.offset(d);
                        b.trace("  " + d + ": " + b.mem.get(n) + " / " + b.mem.get(n.up()) + " / under " + b.mem.get(n.down()));
                    }
                    b.trace("no wall: floor cells reached " + seenFloor + ", known cells " + known + ", rock " + rock);
                    fail("no_wall");
                }
                return;
            }
        }
        if (!f.equals(stand)) {
            // walk the floor path (no swimming up: the drag of the bubbles, and the floor is the level we want)
            while (floorPath.size() > 1 && (floorPath.peekFirst().equals(f) || !floorPath.contains(f) && floorPath.peekFirst().getSquaredDistance(f) < 0.5)) {
                floorPath.pollFirst();
            }
            BlockPos next = floorPath.isEmpty() ? stand : floorPath.peekFirst();
            if (floorPath.contains(f)) {
                while (!floorPath.peekFirst().equals(f)) {
                    floorPath.pollFirst();
                }
                floorPath.pollFirst();
                next = floorPath.isEmpty() ? stand : floorPath.peekFirst();
            }
            Vec3d c = Vec3d.ofBottomCenter(next);
            Vec3d p = b.s.pos();
            b.c.lookAngles(io.github.sx2xoox.speedrunbot.core.InputLimits.yawTo(c.x - p.x, c.z - p.z), 20f);
            b.c.move(1f, 0f, false, false, false);
            if (!b.s.onGround()) {
                b.c.sink();
            }
            if (phaseTicks > 400) {
                fail("wall_unreached");
            }
            return;
        }
        wallStarted = true;
        BlockPos w1 = stand.offset(wallDir);
        for (BlockPos q : new BlockPos[]{w1.up(), w1}) {
            BlockState st = b.mem.get(q);
            if (st != null && Blocks2.pass(st) == Blocks2.Pass.SOLID) {
                b.c.sink();
                if (b.a.mine(q, false) == Actions.Status.FAILED) {
                    fail("wall_mine");
                }
                return;
            }
        }
        go(Phase.DOOR);
    }

    private int seenFloor;
    private boolean wallStarted;
    private final java.util.ArrayDeque<BlockPos> floorPath = new java.util.ArrayDeque<>();

    /** Something to stand on (magma too: it burns, it does not let one fall). */
    private static boolean floor(BlockState st) {
        return Blocks2.pass(st) == Blocks2.Pass.SOLID || st.getBlock() == Blocks.MAGMA_BLOCK;
    }

    private int lastDoorClick = -100;

    private boolean doorAt(BlockPos q) {
        BlockState st = b.mem.get(q);
        return st != null && st.getBlock() instanceof DoorBlock;
    }

    private void door() {
        BlockPos w1 = stand.offset(wallDir);
        if (doorAt(w1)) {
            go(Phase.OPEN);
            return;
        }
        if (phaseTicks > 200) {
            fail("door_place");
            return;
        }
        // keep out of the opening: the door cannot be placed into the player
        Vec3d p = b.s.pos();
        double into = (p.x - (stand.getX() + 0.5)) * wallDir.getOffsetX() + (p.z - (stand.getZ() + 0.5)) * wallDir.getOffsetZ();
        if (into > 0.15) {
            b.c.lookAngles(wallDir.getOpposite().asRotation(), 10f);
            b.c.move(1f, 0f, false, false, false);
            if (phaseTicks % 20 == 0) {
                b.trace(String.format("door: backing out of the opening (%.2f in) yaw %.0f", into, b.s.yaw()));
            }
            return;
        }
        b.c.sink();
        if (b.a.equip(st -> !st.isEmpty() && isDoor(st.getItem())) != Actions.Status.DONE) {
            return;
        }
        // click the top face of the rock under the opening: the door stands in the opening, facing into the wall
        BlockPos support = w1.down();
        Vec3d point = Vec3d.ofCenter(support).add(0, 0.5, 0);
        // the door faces the way the player faces when it is placed: face into the wall (closed, its plate is then on
        // our side; open, it swings to a side and leaves the middle of the opening free)
        b.c.lookAt(point.subtract(0, 0.05, 0));
        net.minecraft.util.hit.HitResult ch = b.s.crosshair();
        float yawErr = Math.abs(io.github.sx2xoox.speedrunbot.core.InputLimits.wrap(b.s.yaw() - wallDir.asRotation()));
        if (ch instanceof BlockHitResult && ((BlockHitResult) ch).getBlockPos().equals(support) && ((BlockHitResult) ch).getSide() == Direction.UP
                && yawErr < 40f) {
            b.c.use();
        }
        if (phaseTicks % 20 == 1) {
            net.minecraft.util.hit.HitResult h = b.s.crosshair();
            b.trace("door: support " + support + " = " + b.mem.get(support) + ", crosshair " + (h instanceof BlockHitResult
                    ? ((BlockHitResult) h).getBlockPos() + "/" + ((BlockHitResult) h).getSide() : h.getType()) + " held " + b.s.inventory().getMainHandStack()
                    + " pos " + b.s.pos());
        }
    }

    private void openDoor() {
        BlockPos w1 = stand.offset(wallDir);
        BlockState st = b.mem.get(w1);
        if (st == null || !(st.getBlock() instanceof DoorBlock)) {
            fail("door_gone");
            return;
        }
        if (st.get(DoorBlock.OPEN)) {
            go(Phase.DRY_DIG);
            return;
        }
        if (phaseTicks > 100) {
            fail("door_open");
            return;
        }
        // no sneaking: a sneaking player's right click with an item in hand does not reach the door
        if (b.a.equip(i -> !i.isEmpty() && !(i.getItem() instanceof net.minecraft.item.BlockItem)) != Actions.Status.DONE) {
            return;
        }
        // right click either half of the door (its thin closed plate faces us)
        Vec3d aim = Vec3d.ofCenter(w1).add(-wallDir.getOffsetX() * 0.35, 0.3, -wallDir.getOffsetZ() * 0.35);
        b.c.lookAt(aim);
        net.minecraft.util.hit.HitResult h = b.s.crosshair();
        if (h instanceof BlockHitResult && h.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK) {
            BlockPos hp = ((BlockHitResult) h).getBlockPos();
            if (hp.equals(w1) || hp.equals(w1.up())) {
                if (phaseTicks > lastDoorClick + 15 && b.c.use()) {
                    lastDoorClick = phaseTicks;
                    b.trace("door: click, seen as " + st);
                }
            } else if (hp.equals(w1.offset(wallDir)) || hp.equals(w1.offset(wallDir).up()) || hp.equals(w1.offset(wallDir).down())) {
                b.trace("door: open (the crosshair passes it)");
                go(Phase.DRY_DIG);
            } else if (phaseTicks % 20 == 0) {
                b.trace("door: crosshair on " + hp + " not the door");
            }
        }
    }

    private void dryDig() {
        BlockPos w2 = stand.offset(wallDir, 2);
        if (b.s.air() < 200) {
            // breathe in the door: its cells hold no water
            Vec3d p = b.s.pos();
            double into = (p.x - (stand.getX() + 0.5)) * wallDir.getOffsetX() + (p.z - (stand.getZ() + 0.5)) * wallDir.getOffsetZ();
            if (into < 0.8) {
                b.c.lookAngles(wallDir.asRotation(), 30f);
                b.c.move(0.6f, 0f, false, false, false);
            }
        }
        for (BlockPos q : new BlockPos[]{w2.up(), w2}) {
            BlockState st = b.mem.get(q);
            if (st == null || Blocks2.pass(st) == Blocks2.Pass.SOLID) {
                b.c.sink();
                if (b.a.mine(q, false) == Actions.Status.FAILED) {
                    fail("dry_mine");
                }
                if (phaseTicks > 600) {
                    fail("dry_timeout");
                }
                return;
            }
        }
        BlockState a = b.mem.get(w2), c = b.mem.get(w2.up());
        if (a != null && c != null && Blocks2.isWater(a)) {
            fail("pocket_flooded");
            return;
        }
        go(Phase.ENTER);
    }

    private void enter() {
        BlockPos w2 = stand.offset(wallDir, 2);
        BlockPos f = b.feet();
        if (f.equals(w2)) {
            dry = w2;
            b.split("dry_pocket");
            b.trace("in a dry pocket behind the door at " + w2 + " air=" + b.s.air());
            Actions.keepWaterOut = true;
            go(Phase.DONE); // the casting goals take over: lava (heard or found in the rock), site, frame
            return;
        }
        if (phaseTicks % 40 == 1) {
            BlockPos w1 = stand.offset(wallDir);
            b.trace("enter: door " + b.mem.get(w1) + " / " + b.mem.get(w1.up()) + " pos " + b.s.pos() + " yaw " + b.s.yaw());
        }
        if (phaseTicks > 160) {
            fail("enter_timeout");
            return;
        }
        // through the middle of the free part of the door cell (the open door is a plate along one side, and the
        // player is 0.6 wide in a 0.81 wide gap)
        BlockPos w1 = stand.offset(wallDir);
        double lateral = 0.5;
        BlockState ds = b.mem.get(w1);
        if (ds != null) {
            net.minecraft.util.math.Box bb = ds.getCollisionShape(net.minecraft.world.EmptyBlockView.INSTANCE, BlockPos.ORIGIN).getBoundingBox();
            boolean alongX = wallDir.getAxis() == Direction.Axis.X;
            double lo = alongX ? bb.minZ : bb.minX, hi = alongX ? bb.maxZ : bb.maxX;
            lateral = lo > 0.5 ? lo / 2 : (1 + hi) / 2; // the gap beside the plate
        }
        Vec3d target = Vec3d.ofBottomCenter(w2);
        Vec3d p0 = b.s.pos();
        boolean alongX = wallDir.getAxis() == Direction.Axis.X;
        double latNow = alongX ? p0.z - Math.floor(p0.z) : p0.x - Math.floor(p0.x);
        if (Math.abs(latNow - lateral) > 0.04 && b.feet().equals(stand)) {
            // line up first: step sideways in the stand cell
            Vec3d side = alongX ? new Vec3d(p0.x, p0.y, Math.floor(p0.z) + lateral) : new Vec3d(Math.floor(p0.x) + lateral, p0.y, p0.z);
            b.c.lookAngles(wallDir.asRotation(), 10f);
            double dl = (alongX ? side.z - p0.z : side.x - p0.x);
            // strafe: +1 = left of the view; left of facing d is d rotated counter-clockwise
            Direction left = wallDir.rotateYCounterclockwise();
            double leftAmount = dl * (alongX ? left.getOffsetZ() : left.getOffsetX());
            b.c.move(0f, leftAmount > 0 ? 0.6f : -0.6f, false, false, false);
            return;
        }
        target = alongX ? new Vec3d(target.x, target.y, Math.floor(p0.z) + lateral) : new Vec3d(Math.floor(p0.x) + lateral, target.y, target.z);
        b.c.lookAngles(io.github.sx2xoox.speedrunbot.core.InputLimits.yawTo(target.x - b.s.pos().x, target.z - b.s.pos().z), 10f);
        b.c.move(0.7f, 0f, false, false, false);
    }
}
