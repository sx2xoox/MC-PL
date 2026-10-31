package io.github.sx2xoox.mcpl.core.map.citadel;

import java.util.Random;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

/**
 * The island ruin: a broken round watchtower on the lake's big island, the district's high point (+14). A spiral
 * stair climbs the inside of the wall to a half floor under the battlements; a ladder on the north side is the second
 * way up, and a collapsed west flank opens the tower to the lake. Around it lie the stumps of the island's old
 * curtain wall.
 */
final class LakeRuin {

    /** Center of the tower (and of the island). */
    static final int CX = -70;
    static final int CZ = 32;
    static final int GROUND = LakeTerrain.GROUND_Y;
    /** The half floor under the battlements (players stand one higher). */
    static final int FLOOR = 77;
    /** Radius of the island plot that is flattened to the meadow level. */
    static final double PLOT_RADIUS = 7.5;

    private static final double WALL_IN = 4.0;
    private static final double WALL_OUT = 5.0;
    private static final double STAIR_IN = 2.0;
    /** The stair starts in the east and climbs clockwise (seen from above) through south and west. */
    private static final double STAIR_START = 0;
    private static final double STAIR_SPAN = 240;
    private static final double STEP_DEGREES = 20;
    /** The collapsed flank (degrees, 0 = east, 90 = south). */
    private static final double BREACH_CENTER = 50;
    private static final double BREACH_HALF = 48;

    private final MapCanvas canvas;
    private final Random random;

    LakeRuin(MapCanvas canvas, Random random) {
        this.canvas = canvas;
        this.random = random;
    }

    /** Levels the island plot (before the terrain is written). */
    static void plan(LakeTerrain terrain) {
        terrain.flattenDisk(CX, CZ, PLOT_RADIUS, GROUND);
        terrain.reserveDisk(CX, CZ, WALL_OUT + 1.5);
    }

    void build() {
        groundFloor();
        wall();
        stair();
        topFloor();
        ladder();
        rubble();
        curtainStumps();
    }

    // ------------------------------------------------------------------ tower

    private void groundFloor() {
        forEachColumn(WALL_IN, (dx, dz, d, phi) -> {
            int roll = random.nextInt(10);
            BlockSpec floor = roll < 4 ? LakeKit.brick(random) : roll < 6 ? BlockSpec.of(Material.COBBLESTONE)
                    : roll < 8 ? BlockSpec.of(Material.MOSSY_COBBLESTONE) : BlockSpec.of(Material.COARSE_DIRT);
            canvas.set(CX + dx, GROUND, CZ + dz, floor);
        });
    }

    private void wall() {
        forEachColumn(WALL_OUT, (dx, dz, d, phi) -> {
            if (d < WALL_IN) {
                return;
            }
            int x = CX + dx;
            int z = CZ + dz;
            int top = wallTop(phi);
            // Foundation into the island, then the wall itself.
            for (int y = GROUND - 3; y <= top; y++) {
                canvas.set(x, y, z, LakeKit.brick(random));
            }
            if (top < FLOOR && top > GROUND + 1 && random.nextInt(3) == 0) {
                canvas.set(x, top + 1, z, LakeKit.rubbleBit(random));
            }
            if (isDoor(phi)) {
                for (int y = GROUND + 1; y <= GROUND + 3; y++) {
                    canvas.clear(x, y, z);
                }
                canvas.set(x, GROUND, z, LakeKit.brick(random));
                canvas.set(x, GROUND + 4, z, LakeKit.brickStairsTop(random, LakeKit.face(-dx, -dz)));
            } else if (isWindow(phi) && top > 73) {
                canvas.clear(x, 71, z);
                canvas.clear(x, 72, z);
            }
        });
    }

    /** Height of the wall's top at angle {@code phi}: battlements, lowered where the flank collapsed. */
    private static int wallTop(double phi) {
        double off = angleDiff(phi, BREACH_CENTER);
        if (off < BREACH_HALF) {
            double hump = Math.cos(off / BREACH_HALF * Math.PI / 2);
            return (int) Math.round(FLOOR + 1 - 13.5 * hump * hump);
        }
        boolean merlon = ((int) Math.floor(phi / 16)) % 2 == 0;
        return FLOOR + (merlon ? 2 : 1);
    }

    private static boolean isDoor(double phi) {
        // North (towards the arch bridge) and north-east (towards the walk to the mill).
        return angleDiff(phi, 270) < 20 || angleDiff(phi, 330) < 16;
    }

    private static boolean isWindow(double phi) {
        return angleDiff(phi, 135) < 7 || angleDiff(phi, 180) < 7 || angleDiff(phi, 225) < 7;
    }

    private void stair() {
        forEachColumn(WALL_IN, (dx, dz, d, phi) -> {
            if (d >= WALL_IN || d < STAIR_IN) {
                return;
            }
            int step = stepAt(phi);
            if (step < 0) {
                return;
            }
            int x = CX + dx;
            int z = CZ + dz;
            int h = GROUND + 1 + step;
            double rad = Math.toRadians(phi);
            BlockFace up = LakeKit.face(-Math.sin(rad), Math.cos(rad));
            canvas.set(x, h, z, LakeKit.brickStairs(random, up));
            if (h >= GROUND + 5) {
                // The underside of the cantilevered step.
                canvas.set(x, h - 1, z, LakeKit.brickStairsTop(random, LakeKit.opposite(up)));
            }
        });
    }

    /** The step number (0 = lowest) of the stair at angle {@code phi}, or -1 outside the stair. */
    private static int stepAt(double phi) {
        double a = ((phi - STAIR_START) % 360 + 360) % 360;
        if (a >= STAIR_SPAN) {
            return -1;
        }
        return (int) Math.floor(a / STEP_DEGREES);
    }

    private void topFloor() {
        forEachColumn(WALL_IN, (dx, dz, d, phi) -> {
            if (d >= WALL_IN) {
                return;
            }
            boolean sector = stepAt(phi) < 0;
            if (!sector && d >= 1.6) {
                return;
            }
            int x = CX + dx;
            int z = CZ + dz;
            canvas.set(x, FLOOR, z, random.nextInt(5) == 0 ? BlockSpec.of(Material.CRACKED_STONE_BRICKS)
                    : random.nextInt(4) == 0 ? BlockSpec.of(Material.MOSSY_STONE_BRICKS)
                    : BlockSpec.of(Material.STONE_BRICKS));
            canvas.set(x, FLOOR - 1, z, Blocks.slabTop(Material.STONE_BRICK_SLAB));
        });
        canvas.set(CX, FLOOR - 2, CZ, Blocks.lantern(false, true));
        canvas.set(CX + 1, FLOOR + 1, CZ - 1, Blocks.lantern(false, false));
        canvas.set(CX - 1, FLOOR + 1, CZ - 2, BlockSpec.of(Material.BARREL));
        canvas.set(CX + 1, FLOOR + 1, CZ - 3, Blocks.slab(Material.SPRUCE_SLAB));
    }

    /** A ladder up the north side to a gap in the battlements: the second way up. */
    private void ladder() {
        int x = CX + 3;
        int z = CZ - 5;
        for (int y = GROUND + 1; y <= FLOOR; y++) {
            canvas.set(x, y, z, BlockSpec.of(Material.LADDER).facing(BlockFace.NORTH));
        }
        // The wall behind it is the ladder's support; the battlement above it is broken to let climbers in.
        for (int[] gap : new int[][] {{3, -4}, {2, -4}, {3, -3}}) {
            canvas.set(CX + gap[0], FLOOR, CZ + gap[1], LakeKit.brick(random));
            canvas.clear(CX + gap[0], FLOOR + 1, CZ + gap[1]);
            canvas.clear(CX + gap[0], FLOOR + 2, CZ + gap[1]);
        }
    }

    private void rubble() {
        // Fallen stone below the collapsed flank, inside and out.
        double rad = Math.toRadians(BREACH_CENTER);
        for (double r : new double[] {6.6, 2.4}) {
            int x = CX + (int) Math.round(Math.cos(rad) * r);
            int z = CZ + (int) Math.round(Math.sin(rad) * r);
            LakeKit.rubbleHeap(canvas, random, x, z, GROUND, r > 5 ? 2.2 : 1.4);
        }
        for (double a : new double[] {BREACH_CENTER - 38, BREACH_CENTER + 40}) {
            double ar = Math.toRadians(a);
            LakeKit.rubbleHeap(canvas, random, CX + (int) Math.round(Math.cos(ar) * 6.5),
                    CZ + (int) Math.round(Math.sin(ar) * 6.5), GROUND, 1.3);
        }
    }

    /** Stumps of the island's old curtain wall, broken into short pieces (cover around the tower). */
    private void curtainStumps() {
        double radius = 7.2;
        // (Gaps where the arch bridge (north) and the walks (north-east, south-east) land.)
        double[][] arcs = {{100, 140}, {160, 200}, {215, 248}, {-12, 22}};
        for (double[] arc : arcs) {
            for (double a = arc[0]; a <= arc[1]; a += 3) {
                double rad = Math.toRadians(a);
                int x = CX + (int) Math.round(Math.cos(rad) * radius);
                int z = CZ + (int) Math.round(Math.sin(rad) * radius);
                if (!canvas.canWrite(x, z) || canvas.surfaceY(x, z) != GROUND) {
                    continue;
                }
                double t = (a - arc[0]) / (arc[1] - arc[0]);
                int height = 1 + (int) Math.round(2 * Math.sin(t * Math.PI));
                for (int y = GROUND + 1; y <= GROUND + height; y++) {
                    canvas.set(x, y, z, y == GROUND + height && random.nextBoolean() ? LakeKit.brickSlab(random)
                            : LakeKit.brick(random));
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    @FunctionalInterface
    private interface ColumnVisitor {
        void visit(int dx, int dz, double d, double phi);
    }

    private static void forEachColumn(double radius, ColumnVisitor visitor) {
        int r = (int) Math.ceil(radius);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > radius) {
                    continue;
                }
                double phi = Math.toDegrees(Math.atan2(dz, dx));
                visitor.visit(dx, dz, d, phi < 0 ? phi + 360 : phi);
            }
        }
    }

    private static double angleDiff(double a, double b) {
        double d = Math.abs(((a - b) % 360 + 360) % 360);
        return Math.min(d, 360 - d);
    }
}
