package io.github.sx2xoox.mcpl.core.map.citadel;

import java.util.HashSet;
import java.util.Set;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

/**
 * The towers along the curtain wall. They jut out towards the hills and every one is also a way through the wall: a
 * ground-floor room with a door from the inner strip, doors on both flanks onto the strip of ground outside the wall,
 * one towards the hills, and a ladder up to the wall-walk.
 *
 * <ul>
 *   <li>{@link OuterRingPlan.TowerKind#BASTION}: an open platform level with the wall-walk, behind a crenellated
 *   parapet.</li>
 *   <li>{@link OuterRingPlan.TowerKind#TALL} and {@link OuterRingPlan.TowerKind#SQUARE}: a hall at wall-walk level
 *   (the walk runs through it) and a crenellated rooftop above, reached by a ladder.</li>
 *   <li>{@link OuterRingPlan.TowerKind#COLLAPSED}: a horseshoe stump that fell outwards, the inside a low rubble
 *   mound, open on both flanks, with a trail of stones lying in the grass.</li>
 * </ul>
 *
 * <p>Ladders stand in the half of the tower nearest the wall, inside the arena border, so the rooftops can be reached
 * without leaving the play area.
 */
final class OuterRingTower {

    private static final int GROUND = OuterRingPlan.GROUND;
    private static final int WALK = OuterRingPlan.WALK;
    private static final int ROOF = OuterRingPlan.ROOF;
    private static final int SPAN = 6;
    /** Ladders stay inside this radius (inside the arena border). */
    private static final double LADDER_RADIUS = 104.6;

    private final OuterRingKit kit;

    OuterRingTower(OuterRingKit kit) {
        this.kit = kit;
    }

    void build(OuterRingPlan.Tower tower) {
        if (tower.kind() == OuterRingPlan.TowerKind.COLLAPSED) {
            collapsed(tower);
            return;
        }
        boolean tall = tower.kind() != OuterRingPlan.TowerKind.BASTION;
        for (int x = tower.cx() - SPAN; x <= tower.cx() + SPAN; x++) {
            for (int z = tower.cz() - SPAN; z <= tower.cz() + SPAN; z++) {
                if (!tower.footprint(x, z)) {
                    continue;
                }
                if (tower.interior(x, z)) {
                    interiorColumn(x, z, tall);
                } else {
                    ringColumn(tower, x, z, tall);
                }
            }
        }
        doors(tower);
        ladder(tower, GROUND + 1, WALK, -2.3, 2.4, false);
        if (tall) {
            long support = ladder(tower, WALK + 1, ROOF, 0.3, 3.0, true);
            windows(tower, support);
            // A watch fire on the rooftop.
            kit.set(tower.cx(), ROOF + 1, tower.cz(), Blocks.campfire(kit.hash(tower.cx(), tower.cz(), 95) < 0.6));
        }
        // A hanging lantern lights the ground-floor room.
        kit.set(tower.cx(), WALK - 1, tower.cz(), Blocks.lantern(false, true));
    }

    private void interiorColumn(int x, int z, boolean tall) {
        kit.set(x, GROUND, z, kit.pick(OuterRingKit.PAVING, x, GROUND, z));
        kit.clearColumn(x, z, GROUND + 1, WALK - 1);
        kit.set(x, WALK, z, kit.pick(OuterRingKit.WALK, x, WALK, z));
        if (tall) {
            kit.clearColumn(x, z, WALK + 1, ROOF - 1);
            kit.set(x, ROOF, z, kit.pick(OuterRingKit.WALK, x, ROOF, z));
        }
    }

    private void ringColumn(OuterRingPlan.Tower tower, int x, int z, boolean tall) {
        boolean onWalk = onWalk(x, z);
        for (int y = GROUND + 1; y < WALK; y++) {
            kit.set(x, y, z, kit.wall(x, y, z));
        }
        kit.set(x, WALK, z, onWalk ? kit.pick(OuterRingKit.WALK, x, WALK, z)
                : BlockSpec.of(Material.POLISHED_ANDESITE));
        int top;
        if (tall) {
            // The wall-walk passes through the hall: the ring is open there, under a lintel.
            int from = onWalk ? WALK + 4 : WALK + 1;
            if (onWalk) {
                kit.clearColumn(x, z, WALK + 1, WALK + 3);
            }
            for (int y = from; y <= ROOF; y++) {
                kit.set(x, y, z, y == ROOF ? kit.pick(OuterRingKit.WALL_TOP, x, y, z) : kit.wall(x, y, z));
            }
            top = ROOF + 1;
        } else {
            if (onWalk) {
                return;
            }
            top = WALK + 1;
        }
        kit.set(x, top, z, kit.pick(OuterRingKit.WALL_TOP, x, top, z));
        if (merlon(tower, x, z, tall)) {
            kit.set(x, top + 1, z, kit.pick(OuterRingKit.WALL_TOP, x, top + 1, z));
        }
    }

    private static boolean onWalk(int x, int z) {
        return CitadelLayout.radius(x, z) < OuterRingPlan.PARAPET_IN;
    }

    /** A ring column carrying the tower's parapet course. */
    private static boolean parapet(OuterRingPlan.Tower tower, int x, int z, boolean tall) {
        return tower.ring(x, z) && (tall || !onWalk(x, z));
    }

    private static boolean merlonPattern(OuterRingPlan.Tower tower, int x, int z) {
        double a = Math.atan2(z - tower.cz(), x - tower.cx());
        int s = (int) Math.floor((a + Math.PI) * OuterRingPlan.Tower.SIZE);
        return Math.floorMod(s, 3) != 2;
    }

    /**
     * Merlons around a tower's parapet: two of every three ring blocks, and only next to a crenel, so every merlon top
     * can be climbed onto.
     */
    private static boolean merlon(OuterRingPlan.Tower tower, int x, int z, boolean tall) {
        if (!parapet(tower, x, z, tall) || !merlonPattern(tower, x, z)) {
            return false;
        }
        for (BlockFace face : OuterRingKit.cardinals()) {
            int nx = x + face.getModX();
            int nz = z + face.getModZ();
            if (parapet(tower, nx, nz, tall) && !merlonPattern(tower, nx, nz)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The ground-floor openings (three high): from the inner strip through the curtain, on both flanks onto the ground
     * outside the wall, and towards the hills.
     */
    private static boolean door(OuterRingPlan.Tower tower, int x, int z) {
        double v = Math.abs(OuterRingPlan.v(x, z, tower.angle()));
        double u = OuterRingPlan.u(x, z, tower.angle()) - OuterRingPlan.TOWER_RADIUS;
        if (tower.interior(x, z)) {
            return false;
        }
        if (v < 1.5 && u < 0) {
            return CitadelLayout.radius(x, z) >= OuterRingPlan.WALL_IN
                    && (tower.footprint(x, z) || OuterRingPlan.inWallBand(x, z));
        }
        if (!tower.ring(x, z)) {
            return false;
        }
        return v < 1.5 && u > 2.5 || v >= 2.4 && u >= -1.2 && u < 1.0;
    }

    private void doors(OuterRingPlan.Tower tower) {
        for (int x = tower.cx() - SPAN - 3; x <= tower.cx() + SPAN + 3; x++) {
            for (int z = tower.cz() - SPAN - 3; z <= tower.cz() + SPAN + 3; z++) {
                if (door(tower, x, z)) {
                    kit.clearColumn(x, z, GROUND + 1, GROUND + 3);
                    kit.set(x, GROUND, z, kit.pick(OuterRingKit.PAVING, x, GROUND, z));
                }
            }
        }
    }

    /** One-block windows in the hall of a tall tower, facing the hills. */
    private void windows(OuterRingPlan.Tower tower, long ladderSupport) {
        for (int x = tower.cx() - SPAN; x <= tower.cx() + SPAN; x++) {
            for (int z = tower.cz() - SPAN; z <= tower.cz() + SPAN; z++) {
                if (!tower.ring(x, z) || CitadelLayout.radius(x, z) < OuterRingPlan.PARAPET_IN + 1.5
                        || key(x, z) == ladderSupport) {
                    continue;
                }
                if (kit.hash(x, z, 91) < 0.35) {
                    kit.clear(x, WALK + 2, z);
                }
            }
        }
    }

    /**
     * A ladder inside the tower from {@code y1} to {@code y2} (the top rung replaces a floor block, leaving a hatch),
     * against a solid ring block, as close as possible to the local position {@code u, |v|} (relative to the tower's
     * center). {@code hall}: the ladder climbs the hall, whose ring is open towards the wall-walk. Returns the column
     * of the ring block holding it (see {@link #key}), or -1.
     */
    private long ladder(OuterRingPlan.Tower tower, int y1, int y2, double targetU, double targetV, boolean hall) {
        int bestX = 0;
        int bestZ = 0;
        BlockFace bestFace = null;
        double bestScore = -Double.MAX_VALUE;
        for (int x = tower.cx() - SPAN; x <= tower.cx() + SPAN; x++) {
            for (int z = tower.cz() - SPAN; z <= tower.cz() + SPAN; z++) {
                if (!tower.interior(x, z) || CitadelLayout.radius(x, z) > LADDER_RADIUS) {
                    continue;
                }
                double u = OuterRingPlan.u(x, z, tower.angle()) - OuterRingPlan.TOWER_RADIUS;
                double v = Math.abs(OuterRingPlan.v(x, z, tower.angle()));
                for (BlockFace face : OuterRingKit.cardinals()) {
                    int nx = x + face.getModX();
                    int nz = z + face.getModZ();
                    if (!tower.ring(nx, nz) || hall && onWalk(nx, nz) || !hall && door(tower, nx, nz)) {
                        continue;
                    }
                    double score = -Math.hypot(u - targetU, v - targetV);
                    if (score > bestScore) {
                        bestScore = score;
                        bestX = x;
                        bestZ = z;
                        bestFace = face.getOppositeFace();
                    }
                }
            }
        }
        if (bestFace == null) {
            return -1;
        }
        for (int y = y1; y <= y2; y++) {
            kit.set(bestX, y, bestZ, BlockSpec.of(Material.LADDER).facing(bestFace));
        }
        BlockFace toWall = bestFace.getOppositeFace();
        return key(bestX + toWall.getModX(), bestZ + toWall.getModZ());
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    // ------------------------------------------------------------------ collapsed

    /**
     * A tower that fell outwards: the curtain behind it still stands, what is left of the ring is a horseshoe of
     * broken shards on the hill side (tall on its flanks, down to the ground at its front), both flanks next to the
     * wall are open, the inside is a low rubble mound, and its stones lie in a trail on the grass.
     */
    private void collapsed(OuterRingPlan.Tower tower) {
        double out = Math.toRadians(tower.angle());
        double ox = Math.cos(out);
        double oz = Math.sin(out);
        boolean leftTaller = kit.hash(tower.cx(), tower.cz(), 97) < 0.5;
        for (int x = tower.cx() - SPAN; x <= tower.cx() + SPAN; x++) {
            for (int z = tower.cz() - SPAN; z <= tower.cz() + SPAN; z++) {
                if (!tower.footprint(x, z)) {
                    continue;
                }
                if (onWalk(x, z)) {
                    curtainColumn(x, z);
                    continue;
                }
                double d = tower.dist(x, z);
                double u = OuterRingPlan.u(x, z, tower.angle()) - OuterRingPlan.TOWER_RADIUS;
                kit.set(x, GROUND, z, kit.pick(OuterRingKit.RUBBLE, x, GROUND, z));
                if (tower.interior(x, z)) {
                    int top = GROUND + (int) Math.round(2.2 - d * 0.55 + kit.hash(x, z, 92) * 0.6);
                    for (int y = GROUND + 1; y <= top; y++) {
                        kit.set(x, y, z, kit.pick(OuterRingKit.RUBBLE, x, y, z));
                    }
                    continue;
                }
                if (u < 1.3) {
                    continue;
                }
                double dx = (x - tower.cx()) / Math.max(d, 0.01);
                double dz = (z - tower.cz()) / Math.max(d, 0.01);
                double facing = dx * ox + dz * oz;
                // Broken shards: tall on the flanks (one flank taller), down to the ground at the front.
                double lean = OuterRingPlan.v(x, z, tower.angle()) > 0 == leftTaller ? 1 : 0.6;
                int top = GROUND + 1 + (int) Math.round(11 * (1 - facing) * lean - kit.hash(x, z, 96) * 1.5);
                if (kit.hash(x, z, 93) < 0.12) {
                    top = Math.max(GROUND, top - 3);
                }
                for (int y = GROUND + 1; y <= top; y++) {
                    kit.set(x, y, z, y == top ? kit.pick(OuterRingKit.WALL_TOP, x, y, z) : kit.wall(x, y, z));
                }
            }
        }
        // The door from the inner strip still leads in.
        for (int x = tower.cx() - SPAN - 3; x <= tower.cx() + SPAN + 3; x++) {
            for (int z = tower.cz() - SPAN - 3; z <= tower.cz() + SPAN + 3; z++) {
                double r = CitadelLayout.radius(x, z);
                if (Math.abs(OuterRingPlan.v(x, z, tower.angle())) < 1.5 && r >= OuterRingPlan.WALL_IN
                        && r < OuterRingPlan.PARAPET_IN) {
                    kit.clearColumn(x, z, GROUND + 1, GROUND + 3);
                }
            }
        }
        fallenTrail(tower, ox, oz);
    }

    /** A wall column standing where the collapsed tower met the curtain. */
    private void curtainColumn(int x, int z) {
        for (int y = GROUND + 1; y < WALK; y++) {
            kit.set(x, y, z, kit.wall(x, y, z));
        }
        kit.set(x, WALK, z, kit.pick(OuterRingKit.WALK, x, WALK, z));
    }

    /** The fallen tower's stones, scattered outwards in a widening trail (one block high). */
    private void fallenTrail(OuterRingPlan.Tower tower, double ox, double oz) {
        double side = kit.hash(tower.cx(), tower.cz(), 94) < 0.5 ? -1 : 1;
        Set<Long> used = new HashSet<>();
        for (int i = 0; i < 26; i++) {
            double along = OuterRingPlan.Tower.SIZE + 1 + kit.random.nextDouble() * 9;
            double across = (kit.random.nextDouble() - 0.5) * (2 + along * 0.6) + side * along * 0.25;
            int x = (int) Math.round(tower.cx() + ox * along - oz * across);
            int z = (int) Math.round(tower.cz() + oz * along + ox * across);
            if (!kit.canWrite(x, z) || !used.add(key(x, z))) {
                continue;
            }
            int y = surface(x, z) + 1;
            if (y - 1 > GROUND + 3) {
                continue;
            }
            double pick = kit.random.nextDouble();
            if (pick < 0.55) {
                kit.setIfEmpty(x, y, z, kit.pick(OuterRingKit.RUBBLE));
            } else if (pick < 0.8) {
                kit.setIfEmpty(x, y, z, Blocks.slab(Material.STONE_BRICK_SLAB));
            } else {
                kit.setIfEmpty(x, y, z, Blocks.stairs(Material.STONE_BRICK_STAIRS,
                        OuterRingKit.cardinals()[kit.random.nextInt(4)]));
            }
        }
    }

    private int surface(int x, int z) {
        int y = kit.canvas.surfaceY(x, z);
        return Math.max(y, GROUND);
    }
}
