package io.github.sx2xoox.speedrunbot.brain;

import io.github.sx2xoox.speedrunbot.core.TreasureMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.block.BlockState;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * The ocean route real runners use: a shipwreck seen near the spawn is searched for its chests (iron, food, TNT and
 * the treasure map), the map is read like a player reads it (the red X relative to the player's marker) and the
 * buried treasure is dug up at the chunk's 9, 9 column (from the bot's own coordinates). Its iron replaces mining.
 * Everything comes from what the bot saw: wood blocks in the water, chests in view, the map screen.
 */
public final class TreasureHunt {
    public enum Phase { NONE, SHIP, LOOT_SHIP, READ_MAP, WALK, DIG, LOOT_TREASURE, DONE, FAILED }

    private final RunBrain b;
    private Phase phase = Phase.NONE;
    private BlockPos ship;
    private int phaseTicks;
    private int totalTicks;
    private int hullMined;
    private BlockPos hullTarget;
    private BlockPos lookout;
    private TreasureMap.Estimate estimate;
    private int[] column;
    private int readTicks;
    private int digStartY = Integer.MIN_VALUE;
    private int columnTries;
    private String why = "";

    public TreasureHunt(RunBrain b) {
        this.b = b;
    }

    public Phase phase() {
        return phase;
    }

    public boolean active() {
        return phase != Phase.NONE && phase != Phase.DONE && phase != Phase.FAILED;
    }

    public boolean finished() {
        return phase == Phase.DONE || phase == Phase.FAILED;
    }

    public BlockPos ship() {
        return ship;
    }

    public String why() {
        return why;
    }

    private void go(Phase p) {
        if (p != phase) {
            b.trace("treasure " + phase + " -> " + p + (estimate != null ? " estimate=" + estimate : ""));
        }
        phase = p;
        phaseTicks = 0;
    }

    private void fail(String reason) {
        why = reason;
        go(Phase.FAILED);
    }

    /** A seen shipwreck: the densest cluster of woodwork that sits in or next to water. Null if none is known. */
    public static BlockPos findShip(WorldMemory mem, BlockPos from, double maxDist) {
        BlockPos best = null;
        int bestN = 0;
        LongIterator it = mem.of(WorldMemory.Kind.WOODWORK).iterator();
        int checked = 0;
        while (it.hasNext() && checked < 400) {
            long l = it.nextLong();
            BlockPos p = BlockPos.fromLong(l);
            if (from.getSquaredDistance(p) > maxDist * maxDist) {
                continue;
            }
            checked++;
            int n = 0, wet = 0;
            LongIterator it2 = mem.of(WorldMemory.Kind.WOODWORK).iterator();
            while (it2.hasNext()) {
                BlockPos q = BlockPos.fromLong(it2.nextLong());
                if (q.getSquaredDistance(p) <= 36) {
                    n++;
                    if (wetOrLow(mem, q)) {
                        wet++;
                    }
                }
            }
            if (n >= 6 && wet >= 2 && n > bestN && p.getY() >= 35 && p.getY() <= 70 && bigWater(mem, p)) {
                bestN = n;
                best = p;
            }
        }
        return best;
    }

    /** A sea or a big lake around (many water sources seen near it): a mineshaft beside a spring is no shipwreck. */
    private static boolean bigWater(WorldMemory mem, BlockPos p) {
        int n = 0;
        LongIterator it = mem.of(WorldMemory.Kind.WATER_SOURCE).iterator();
        while (it.hasNext()) {
            long l = it.nextLong();
            int dx = BlockPos.unpackLongX(l) - p.getX(), dz = BlockPos.unpackLongZ(l) - p.getZ();
            if (dx * dx + dz * dz <= 144 && BlockPos.unpackLongY(l) >= 55 && ++n >= 25) {
                return true;
            }
        }
        return false;
    }

    private static boolean wetOrLow(WorldMemory mem, BlockPos q) {
        for (Direction d : Direction.values()) {
            BlockState s = mem.get(q.offset(d));
            if (s != null && Blocks2.isWater(s)) {
                return true;
            }
        }
        return false;
    }

    /** Starts at a seen shipwreck (or straight at the map when one is already carried). */
    public void start(BlockPos shipPos) {
        ship = shipPos;
        totalTicks = 0;
        go(b.inv().has(Items.FILLED_MAP) ? Phase.READ_MAP : Phase.SHIP);
    }

    public void tick() {
        phaseTicks++;
        totalTicks++;
        if (totalTicks > b.p.getInt("treasure.maxTicks")) {
            fail("timeout_" + phase);
            return;
        }
        switch (phase) {
            case SHIP:
                searchShip();
                break;
            case LOOT_SHIP:
            case LOOT_TREASURE:
                loot();
                break;
            case READ_MAP:
                readMap();
                break;
            case WALK:
                walk();
                break;
            case DIG:
                dig();
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------ the shipwreck

    private BlockPos chestNear(BlockPos center, double r) {
        return b.mem.nearest(WorldMemory.Kind.CHEST, center, r, l -> !b.badTargets().contains(l));
    }

    private void searchShip() {
        if (b.inv().has(Items.FILLED_MAP)) {
            go(Phase.READ_MAP);
            return;
        }
        BlockPos chest = chestNear(ship, 14);
        if (chest != null) {
            b.looter().start(chest);
            go(Phase.LOOT_SHIP);
            return;
        }
        if (phaseTicks > b.p.getInt("treasure.shipTicks")) {
            fail("ship_no_chest");
            return;
        }
        // walk around the wreck looking at it; then open the hull where it is thickest
        BlockPos f = b.feet();
        if (lookout == null || phaseTicks % 200 == 0) {
            lookout = shipLookout(f);
        }
        if (lookout != null && f.getSquaredDistance(lookout) > 9 && hullMined == 0) {
            if (b.mover.go(Navigator.near(lookout, 2)) == Mover.Status.FAILED) {
                lookout = null;
            }
            if (phaseTicks % 30 < 10) {
                b.c.lookAt(Vec3d.ofCenter(ship));
            }
            return;
        }
        if (phaseTicks % 60 < 20) {
            b.c.lookAt(Vec3d.ofCenter(ship).add((phaseTicks % 20 - 10) * 0.6, -1, 0));
            return;
        }
        if (hullTarget == null || !isWoodwork(hullTarget)) {
            if (hullTarget != null) {
                hullMined++;
            }
            hullTarget = b.mem.nearest(WorldMemory.Kind.WOODWORK, ship, 8, l -> !b.badTargets().contains(l));
            if (hullTarget == null) {
                fail("ship_gone");
                return;
            }
        }
        Navigator.Goal g = Navigator.reach(hullTarget, 4.0);
        if (!g.done(f.getX(), f.getY(), f.getZ())) {
            if (b.mover.go(g) == Mover.Status.FAILED) {
                b.badTargets().add(hullTarget.asLong());
                hullTarget = null;
            }
            return;
        }
        if (b.a.mine(hullTarget, false) == Actions.Status.FAILED) {
            b.badTargets().add(hullTarget.asLong());
            hullTarget = null;
        }
    }

    private BlockPos shipLookout(BlockPos f) {
        // a known standable/swimmable cell 3-6 blocks from the wreck's centre, nearest to us
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                int r2 = dx * dx + dz * dz;
                if (r2 < 9 || r2 > 36) {
                    continue;
                }
                for (int dy = -3; dy <= 4; dy++) {
                    BlockPos q = ship.add(dx, dy, dz);
                    BlockState here = b.mem.get(q), head = b.mem.get(q.up());
                    if (here == null || head == null || Blocks2.pass(here) == Blocks2.Pass.SOLID || Blocks2.pass(head) == Blocks2.Pass.SOLID
                            || Blocks2.isLava(here)) {
                        continue;
                    }
                    double d = q.getSquaredDistance(f);
                    if (d < bd) {
                        bd = d;
                        best = q;
                    }
                }
            }
        }
        return best;
    }

    private boolean isWoodwork(BlockPos p) {
        BlockState s = b.mem.get(p);
        return s != null && WorldMemory.classify(s) == WorldMemory.Kind.WOODWORK;
    }

    private void loot() {
        Actions.Status st = b.looter().tick();
        if (st == Actions.Status.RUNNING) {
            return;
        }
        if (phase == Phase.LOOT_TREASURE) {
            b.split("treasure");
            go(Phase.DONE);
            return;
        }
        if (b.inv().has(Items.FILLED_MAP)) {
            b.split("treasure_map");
            go(Phase.READ_MAP);
        } else {
            go(Phase.SHIP); // another chest of the wreck
        }
    }

    // ------------------------------------------------------------------ the map

    private void readMap() {
        if (b.a.equip(Items.FILLED_MAP) != Actions.Status.DONE) {
            if (!b.inv().has(Items.FILLED_MAP)) {
                fail("map_lost");
            }
            return;
        }
        if (++readTicks < 3) {
            return; // the map refreshes its markers while held
        }
        readTicks = 0;
        TreasureMap.Reading r = b.s.heldMap();
        Vec3d p = b.s.pos();
        estimate = r == null ? null : TreasureMap.locate(r, p.x, p.z);
        if (estimate == null) {
            if (phaseTicks > 60) {
                fail("map_unreadable " + r);
            }
            return;
        }
        b.trace("map " + r + " -> " + estimate);
        column = estimate.exact ? estimate.digColumn() : null;
        go(Phase.WALK);
    }

    private void walk() {
        Vec3d p = b.s.pos();
        if (column == null || phaseTicks % 200 == 199) {
            go(Phase.READ_MAP); // read again (closer now, or still off the map)
            return;
        }
        BlockPos f = b.feet();
        if (f.getX() == column[0] && f.getZ() == column[1] && (b.s.onGround() || b.s.inWater())) {
            digStartY = f.getY();
            go(Phase.DIG);
            return;
        }
        if (b.mover.go(Navigator.column(column[0], column[1], f.getY())) == Mover.Status.FAILED) {
            b.mover.reset();
        }
        if (Math.abs(p.x - column[0]) + Math.abs(p.z - column[1]) > 400) {
            fail("map_far");
        }
    }

    private void dig() {
        BlockPos f = b.feet();
        BlockPos chest = b.mem.nearest(WorldMemory.Kind.CHEST, f, 4, l -> !b.badTargets().contains(l));
        if (chest != null) {
            b.looter().start(chest);
            go(Phase.LOOT_TREASURE);
            return;
        }
        if (f.getX() != column[0] || f.getZ() != column[1]) {
            // slid off the column (sand fell): step back onto it
            b.mover.go(Navigator.column(column[0], column[1], f.getY()));
            return;
        }
        if (f.getY() < digStartY - 12 || phaseTicks > 1200) {
            if (++columnTries <= 1 && estimate != null) {
                // the estimate may sit next to a chunk border: try the nearer neighbouring chunk's column
                int[] alt = neighbourColumn();
                b.trace("no treasure in column " + column[0] + "," + column[1] + "; trying " + alt[0] + "," + alt[1]);
                column = alt;
                go(Phase.WALK);
                return;
            }
            fail("dig_nothing");
            return;
        }
        BlockPos below = f.down();
        BlockState st = b.mem.get(below);
        if (st != null && (Blocks2.isLava(st))) {
            fail("dig_lava");
            return;
        }
        if (st != null && Blocks2.pass(st) != Blocks2.Pass.SOLID) {
            b.c.lookAngles(b.s.yaw(), 90f);
            b.c.sink();
            return; // falling / swimming down
        }
        b.c.sink();
        if (b.a.mine(below, false) == Actions.Status.FAILED) {
            fail("dig_blocked");
        }
    }

    private int[] neighbourColumn() {
        double fx = estimate.x - Math.floor(estimate.x / 16.0) * 16, fz = estimate.z - Math.floor(estimate.z / 16.0) * 16;
        int dx = 0, dz = 0;
        double ex = Math.min(fx, 16 - fx), ez = Math.min(fz, 16 - fz);
        if (ex < ez) {
            dx = fx < 8 ? -16 : 16;
        } else {
            dz = fz < 8 ? -16 : 16;
        }
        return new int[]{column[0] + dx, column[1] + dz};
    }
}
