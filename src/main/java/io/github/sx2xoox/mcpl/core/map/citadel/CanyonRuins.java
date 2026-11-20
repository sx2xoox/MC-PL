package io.github.sx2xoox.mcpl.core.map.citadel;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

/**
 * What the citadel's builders left in the canyon: the old quarry camp on the keep side (a roofless stone hut, a
 * wooden crane over the gorge, stacks of cut stone, a cold campfire), a broken round watch post in the west and a
 * crumbling wall near the ring road.
 */
final class CanyonRuins {

    private static final int GROUND = CanyonPlan.GROUND;

    private final MapCanvas canvas;
    private final CanyonPlan plan;
    private final CanyonTerrain terrain;

    CanyonRuins(MapCanvas canvas, CanyonPlan plan, CanyonTerrain terrain) {
        this.canvas = canvas;
        this.plan = plan;
        this.terrain = terrain;
    }

    void build() {
        quarryHut(-56, -32);
        crane(-60, -24);
        stoneStacks();
        watchPost(-83, -10);
        brokenWall();
        mesaLookout();
        ridgeCairn();
    }

    // ------------------------------------------------------------------ high places

    /**
     * A broken parapet around the edge of the mesa top, open where the ramps and ladders arrive, and a lantern on a
     * stump in the middle: cover up there, but no fortress.
     */
    private void mesaLookout() {
        int top = CanyonRocks.MESA_TOP;
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                int x = CanyonRocks.MESA_X + dx;
                int z = CanyonRocks.MESA_Z + dz;
                double d = Math.hypot(dx, dz);
                if (d < 2.8 || d > 4.2 || terrain.top(x, z) != top || canvas.isSolid(x, top + 1, z)) {
                    continue;
                }
                double theta = Math.toDegrees(Math.atan2(dz, dx));
                if (theta < 0) {
                    theta += 360;
                }
                // Openings at the ladders (east, west) and where the ramps arrive (just before them).
                if (near(theta, 0, 30) || near(theta, 180, 30) || near(theta, 160, 22) || near(theta, 340, 22)) {
                    continue;
                }
                if (plan.hash(x, 11, z) % 5 == 0) {
                    continue;
                }
                boolean tall = plan.hash(x, 12, z) % 3 == 0;
                put(x, top + 1, z, tall ? BlockSpec.of(rubbleWall(x, top + 1, z))
                        : Blocks.slab(plan.roll(x, top, z, 309) < 60 ? Material.STONE_BRICK_SLAB
                        : Material.MOSSY_COBBLESTONE_SLAB));
                if (tall && plan.roll(x, top + 2, z, 310) < 50) {
                    put(x, top + 2, z, Blocks.slab(Material.STONE_BRICK_SLAB));
                }
            }
        }
        int x = CanyonRocks.MESA_X;
        int z = CanyonRocks.MESA_Z;
        if (terrain.top(x, z) == top) {
            put(x, top + 1, z, Material.CHISELED_STONE_BRICKS);
            put(x, top + 2, z, Material.STONE_BRICKS);
            put(x, top + 3, z, Blocks.lantern(false, false));
        }
    }

    private static boolean near(double theta, double target, double tolerance) {
        double diff = Math.abs(((theta - target) % 360 + 540) % 360 - 180);
        return diff <= tolerance;
    }

    /** A cairn of stacked stones on the high end of the ridge. */
    private void ridgeCairn() {
        double ax = CanyonRocks.RIDGE_A[0];
        double az = CanyonRocks.RIDGE_A[1];
        double bx = CanyonRocks.RIDGE_B[0];
        double bz = CanyonRocks.RIDGE_B[1];
        double length = Math.hypot(ax - bx, az - bz);
        int x = (int) Math.round(bx + (ax - bx) / length * (length - 2.5));
        int z = (int) Math.round(bz + (az - bz) / length * (length - 2.5));
        int top = terrain.top(x, z);
        if (top < CanyonRocks.RIDGE_CREST || canvas.isSolid(x, top + 1, z)) {
            return;
        }
        put(x, top + 1, z, Material.MOSSY_COBBLESTONE);
        put(x, top + 2, z, Material.COBBLESTONE);
        put(x, top + 3, z, Blocks.slab(Material.COBBLESTONE_SLAB));
        if (terrain.top(x + 1, z) == top && !canvas.isSolid(x + 1, top + 1, z)) {
            put(x + 1, top + 1, z, Blocks.slab(Material.MOSSY_COBBLESTONE_SLAB));
        }
    }

    /** A spruce post with a lantern, marking the top of a way down into the gorge. */
    void lanternPost(int x, int z) {
        if (!canvas.canWrite(x, z) || terrain.top(x, z) != GROUND || canvas.isSolid(x, GROUND + 1, z)) {
            return;
        }
        put(x, GROUND + 1, z, Blocks.log(Material.SPRUCE_LOG, Axis.Y));
        put(x, GROUND + 2, z, BlockSpec.of(Material.SPRUCE_FENCE));
        put(x, GROUND + 3, z, Blocks.lantern(false, false));
        terrain.reserve(x, z);
    }

    private void put(int x, int y, int z, BlockSpec spec) {
        if (canvas.canWrite(x, z)) {
            canvas.set(x, y, z, spec);
        }
    }

    private void put(int x, int y, int z, Material material) {
        put(x, y, z, BlockSpec.of(material));
    }

    private Material rubbleWall(int x, int y, int z) {
        int roll = plan.roll(x, y, z, 301);
        return roll < 35 ? Material.COBBLESTONE : roll < 55 ? Material.MOSSY_COBBLESTONE : roll < 75
                ? Material.STONE_BRICKS : roll < 88 ? Material.CRACKED_STONE_BRICKS : Material.MOSSY_STONE_BRICKS;
    }

    // ------------------------------------------------------------------ quarry camp

    /**
     * The quarrymen's hut: 7 by 6, walls broken down to one to four blocks, doorways on the west and south and a
     * collapsed north-east corner; half of a slab roof still rests on a beam.
     */
    private void quarryHut(int x0, int z0) {
        int x1 = x0 + 6;
        int z1 = z0 + 5;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                boolean wall = x == x0 || x == x1 || z == z0 || z == z1;
                if (terrain.top(x, z) != GROUND) {
                    continue;
                }
                // A flat floor of packed earth and old planks.
                put(x, GROUND, z, plan.roll(x, GROUND, z, 302) < 60 ? Material.COARSE_DIRT : Material.SPRUCE_PLANKS);
                terrain.reserve(x, z);
                if (!wall) {
                    continue;
                }
                boolean door = x == x0 && (z == z0 + 2 || z == z0 + 3) || z == z1 && (x == x0 + 3 || x == x0 + 4);
                boolean collapsed = x >= x1 - 1 && z <= z0 + 1;
                boolean corner = (x == x0 || x == x1) && (z == z0 || z == z1);
                int height = corner ? 4 : 1 + plan.hash(x, 7, z) % 4;
                if (collapsed) {
                    height = x == x1 && z == z0 ? 1 : 0;
                }
                for (int y = GROUND + 1; y <= GROUND + height; y++) {
                    if (door && y <= GROUND + 3) {
                        continue;
                    }
                    put(x, y, z, corner ? Blocks.log(Material.SPRUCE_LOG, Axis.Y) : BlockSpec.of(rubbleWall(x, y, z)));
                }
                if (door) {
                    terrain.reserve(x - (x == x0 ? 1 : 0), z + (z == z1 ? 1 : 0));
                }
            }
        }
        // Lintels over the doorways.
        for (int z = z0 + 2; z <= z0 + 3; z++) {
            put(x0, GROUND + 4, z, Blocks.log(Material.SPRUCE_LOG, Axis.Z));
        }
        for (int x = x0 + 3; x <= x0 + 4; x++) {
            put(x, GROUND + 4, z1, Blocks.log(Material.SPRUCE_LOG, Axis.X));
        }
        // What is left of the roof: a beam and slabs over the west half.
        for (int z = z0; z <= z1; z++) {
            put(x0 + 2, GROUND + 4, z, Blocks.log(Material.SPRUCE_LOG, Axis.Z));
        }
        for (int x = x0; x <= x0 + 3; x++) {
            for (int z = z0; z <= z1; z++) {
                if (plan.roll(x, GROUND + 5, z, 303) < 78) {
                    put(x, GROUND + 5, z, Blocks.slab(Material.SPRUCE_SLAB));
                }
            }
        }
        put(x0 + 1, GROUND + 3, z0 + 2, Blocks.lantern(false, true));
        put(x0 + 1, GROUND + 4, z0 + 2, Material.SPRUCE_PLANKS);
        put(x1 - 1, GROUND + 1, z1 - 1, Material.BARREL);
        put(x1 - 1, GROUND + 1, z1 - 2, Material.BARREL);
        put(x1 - 1, GROUND + 2, z1 - 1, Material.BARREL);
        put(x0 + 1, GROUND + 1, z1 - 1, BlockSpec.of(Material.STONECUTTER).facing(BlockFace.EAST));
        // A cold campfire with log seats east of the hut.
        int fx = x1 + 3;
        int fz = z0 + 3;
        put(fx, GROUND + 1, fz, Blocks.campfire(false));
        put(fx, GROUND + 1, fz - 2, Blocks.log(Material.SPRUCE_LOG, Axis.X));
        put(fx, GROUND + 1, fz + 2, Blocks.log(Material.SPRUCE_LOG, Axis.X));
        terrain.reserve(fx, fz, 2.5);
    }

    /** A wooden crane leaning over the gorge, a chain and a stone block still hanging from its arm. */
    private void crane(int x, int z) {
        BlockFace out = BlockFace.WEST;
        for (int y = GROUND + 1; y <= GROUND + 7; y++) {
            put(x, y, z, Blocks.log(Material.SPRUCE_LOG, Axis.Y));
        }
        put(x + 1, GROUND + 1, z, Blocks.stairs(Material.SPRUCE_STAIRS, BlockFace.WEST));
        put(x, GROUND + 1, z + 1, Blocks.stairs(Material.SPRUCE_STAIRS, BlockFace.NORTH));
        put(x, GROUND + 1, z - 1, Blocks.stairs(Material.SPRUCE_STAIRS, BlockFace.SOUTH));
        for (int i = 1; i <= 6; i++) {
            put(x + out.getModX() * i, GROUND + 7, z, Blocks.log(Material.SPRUCE_LOG, Axis.X));
        }
        put(x + out.getModX(), GROUND + 6, z, BlockSpec.of(Material.SPRUCE_FENCE));
        put(x - out.getModX(), GROUND + 7, z, Blocks.log(Material.SPRUCE_LOG, Axis.X));
        put(x - out.getModX(), GROUND + 6, z, BlockSpec.of(Material.SPRUCE_FENCE));
        int hx = x + out.getModX() * 6;
        for (int y = GROUND + 3; y <= GROUND + 6; y++) {
            put(hx, y, z, Blocks.log(Material.IRON_CHAIN, Axis.Y));
        }
        put(hx, GROUND + 2, z, Material.SMOOTH_STONE);
        put(x + out.getModX() * 3, GROUND + 6, z, Blocks.lantern(false, true));
        terrain.reserve(x, z, 1.5);
    }

    /** Blocks of cut stone waiting since the quarry was abandoned. */
    private void stoneStacks() {
        int[][] stacks = {{-57, -25, 2, 2, 2}, {-53, -24, 3, 1, 1}, {-43, -27, 2, 1, 1}};
        for (int[] s : stacks) {
            for (int dx = 0; dx < s[2]; dx++) {
                for (int dz = 0; dz < s[3]; dz++) {
                    int x = s[0] + dx;
                    int z = s[1] + dz;
                    if (terrain.reserved(x, z) || terrain.top(x, z) != GROUND) {
                        continue;
                    }
                    for (int h = 1; h <= s[4]; h++) {
                        int roll = plan.roll(x, GROUND + h, z, 304);
                        Material m = roll < 40 ? Material.SMOOTH_STONE : roll < 70 ? Material.STONE_BRICKS
                                : roll < 85 ? Material.POLISHED_ANDESITE : Material.CHISELED_STONE_BRICKS;
                        put(x, GROUND + h, z, m);
                    }
                    if (s[4] == 1 && plan.roll(x, 70, z, 305) < 40) {
                        put(x, GROUND + 2, z, Blocks.slab(Material.SMOOTH_STONE_SLAB));
                    }
                    terrain.reserve(x, z);
                }
            }
        }
    }

    // ------------------------------------------------------------------ watch post

    /**
     * The stump of a round watch post: a broken ring wall up to seven blocks high with two doorways, stairs inside up
     * to half a floor from which to look out over the wall.
     */
    private void watchPost(int cx, int cz) {
        int base = GROUND;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                int x = cx + dx;
                int z = cz + dz;
                double d = Math.hypot(dx, dz);
                if (d > 3.5) {
                    continue;
                }
                put(x, base, z, plan.roll(x, base, z, 306) < 55 ? Material.COBBLESTONE : Material.STONE_BRICKS);
                terrain.reserve(x, z);
                if (d < 2.5) {
                    continue;
                }
                double theta = Math.toDegrees(Math.atan2(dz, dx));
                boolean door = dx == 0 && dz == 3 || dx == 3 && dz == 0;
                int height = 2 + (int) Math.round(5 * Math.abs(Math.sin(Math.toRadians(theta * 1.5 + 40))));
                for (int y = base + 1; y <= base + height; y++) {
                    if (door && y <= base + 3) {
                        continue;
                    }
                    put(x, y, z, rubbleWall(x, y, z));
                }
                if (door) {
                    terrain.reserve(x + Integer.signum(dx), z + Integer.signum(dz));
                }
            }
        }
        // Stairs up the inside of the west wall to half a floor on the north side.
        put(cx - 2, base + 1, cz + 1, Blocks.stairs(Material.STONE_BRICK_STAIRS, BlockFace.NORTH));
        put(cx - 2, base + 1, cz, Material.STONE_BRICKS);
        put(cx - 2, base + 2, cz, Blocks.stairs(Material.STONE_BRICK_STAIRS, BlockFace.NORTH));
        put(cx - 2, base + 1, cz - 1, Material.STONE_BRICKS);
        put(cx - 2, base + 2, cz - 1, Material.STONE_BRICKS);
        put(cx - 2, base + 3, cz - 1, Blocks.stairs(Material.STONE_BRICK_STAIRS, BlockFace.NORTH));
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= -1; dz++) {
                if (dz == -1 && dx == -2) {
                    continue;
                }
                put(cx + dx, base + 4, cz + dz, Blocks.slabTop(Material.SPRUCE_SLAB));
                for (int y = base + 5; y <= base + 6; y++) {
                    if (canvas.isSolid(cx + dx, y, cz + dz) && Math.abs(dx) < 2) {
                        canvas.clear(cx + dx, y, cz + dz);
                    }
                }
            }
        }
        put(cx + 1, base + 1, cz + 1, Material.BARREL);
    }

    // ------------------------------------------------------------------ broken wall

    /** A crumbling stretch of an old inner wall near the ring road, with gaps to walk through and a lantern pillar. */
    private void brokenWall() {
        double radius = 41.5;
        int lastX = Integer.MIN_VALUE;
        int lastZ = Integer.MIN_VALUE;
        for (double a = 205; a <= 229; a += 0.4) {
            int[] p = CitadelLayout.polar(radius, a);
            int x = p[0];
            int z = p[1];
            if (x == lastX && z == lastZ) {
                continue;
            }
            lastX = x;
            lastZ = z;
            if (!canvas.canWrite(x, z) || terrain.reserved(x, z) || terrain.top(x, z) != GROUND
                    || CanyonPlan.edgeDistance(x, z) < 3) {
                continue;
            }
            int segment = (int) ((a - 205) / 3.1);
            if (segment % 3 == 2) {
                continue;
            }
            int height = 1 + plan.hash(x, 9, z) % 3;
            boolean pillar = Math.abs(a - 217) < 0.6;
            if (pillar) {
                height = 4;
            }
            for (int y = GROUND + 1; y <= GROUND + height; y++) {
                put(x, y, z, pillar && y == GROUND + height ? Material.CHISELED_STONE_BRICKS : rubbleWall(x, y, z));
            }
            if (pillar) {
                put(x, GROUND + height + 1, z, Blocks.lantern(false, false));
            } else if (plan.roll(x, 80, z, 307) < 35) {
                put(x, GROUND + height + 1, z, Blocks.slab(plan.roll(x, 81, z, 308) < 50
                        ? Material.STONE_BRICK_SLAB : Material.MOSSY_STONE_BRICK_SLAB));
            }
            terrain.reserve(x, z);
        }
    }
}
