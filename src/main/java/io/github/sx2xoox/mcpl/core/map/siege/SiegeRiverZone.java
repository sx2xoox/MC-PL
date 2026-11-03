package io.github.sx2xoox.mcpl.core.map.siege;

import java.util.Random;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.MapZone;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

/**
 * The river gorge and the killing ground before the outer wall. Three crossings, one per lane: the arched stone
 * bridge of the main road, a sagging rope bridge on the north flank, and on the south flank stairs down to a ford of
 * stepping stones, where the tunnel under the wall starts (see {@code SiegeMap#tunnel}). Ladders at the bridges let
 * anyone who falls in climb out.
 */
final class SiegeRiverZone implements MapZone {

    private static final int G = SiegeLayout.GROUND_Y;
    private static final int FORD_X = SiegeLayout.gorgeX(SiegeLayout.FORD_Z);

    @Override
    public String name() {
        return "river";
    }

    @Override
    public boolean contains(int x, int z) {
        return SiegeLayout.isInterior(x, z) && x >= -44 && x < -17;
    }

    /** Last column (x) of the ford's east landing; the tunnel starts right after it. */
    static int eastLandingX2() {
        return FORD_X + 8;
    }

    @Override
    public void build(MapCanvas canvas) {
        Random random = canvas.random(name());
        gorge(canvas, random);
        tracks(canvas, random);
        stoneBridge(canvas, random);
        ropeBridge(canvas, random);
        ford(canvas, random);
        killingGround(canvas, random);
    }

    // ------------------------------------------------------------------ the gorge

    private void gorge(MapCanvas canvas, Random random) {
        for (int x = -44; x < -17; x++) {
            for (int z = -SiegeLayout.HALF_WIDTH; z <= SiegeLayout.HALF_WIDTH; z++) {
                if (!canvas.canWrite(x, z)) {
                    continue;
                }
                int dx = Math.abs(x - SiegeLayout.gorgeX(z));
                if (dx <= SiegeLayout.GORGE_HALF) {
                    canvas.set(x, SiegeLayout.RIVER_BED, z, SiegeKit.pick(random, Material.GRAVEL, Material.GRAVEL,
                            Material.CLAY, Material.SAND, Material.STONE));
                    for (int y = SiegeLayout.RIVER_BED + 1; y <= SiegeLayout.WATER_TOP; y++) {
                        canvas.set(x, y, z, BlockSpec.of(Material.WATER));
                    }
                    for (int y = SiegeLayout.WATER_TOP + 1; y <= G; y++) {
                        canvas.clear(x, y, z);
                    }
                    if (random.nextInt(9) == 0) {
                        canvas.set(x, SiegeLayout.RIVER_BED + 1, z,
                                BlockSpec.of(Material.SEAGRASS).waterlogged(true));
                    }
                } else if (dx <= SiegeLayout.GORGE_HALF + 2) {
                    // Rock faces instead of dirt where the gorge cuts the ground.
                    for (int y = SiegeLayout.WATER_TOP - 2; y < G; y++) {
                        canvas.set(x, y, z, SiegeKit.rock(random, y));
                    }
                    if (dx == SiegeLayout.GORGE_HALF + 1 && random.nextInt(4) == 0) {
                        canvas.set(x, G, z, BlockSpec.of(Material.MOSS_BLOCK));
                    }
                }
            }
        }
    }

    private void tracks(MapCanvas canvas, Random random) {
        for (int x = -44; x < -17; x++) {
            for (int dz = -3; dz <= 3; dz++) {
                track(canvas, random, x, dz, Math.abs(dz) == 3 ? 3 : 1, x > -27);
            }
            // North: rope bridge to the breach; south: the ford stairs, then along the wall.
            double t = Math.max(0, Math.min(1, (x + 28) / 12.0));
            int north = (int) Math.round(SiegeLayout.ROPE_BRIDGE_Z + (SiegeLayout.BREACH_Z
                    - SiegeLayout.ROPE_BRIDGE_Z) * t);
            for (int dz = -1; dz <= 1; dz++) {
                track(canvas, random, x, north + dz, 2, false);
                track(canvas, random, x, 15 + dz, 2, false);
            }
        }
    }

    private static void track(MapCanvas canvas, Random random, int x, int z, int sparse, boolean paved) {
        if (!canvas.canWrite(x, z) || random.nextInt(sparse) != 0
                || Math.abs(x - SiegeLayout.gorgeX(z)) <= SiegeLayout.GORGE_HALF) {
            return;
        }
        canvas.set(x, G, z, paved ? SiegeKit.paving(random) : SiegeKit.track(random));
    }

    // ------------------------------------------------------------------ crossings

    /** The main road's bridge: a 7-wide deck with walls, arched underneath, towers at both ends. */
    private void stoneBridge(MapCanvas canvas, Random random) {
        int x1 = -42;
        int x2 = -28;
        for (int x = x1; x <= x2; x++) {
            for (int z = -4; z <= 4; z++) {
                canvas.set(x, G, z, Math.abs(z) == 4 ? SiegeKit.dark(random) : SiegeKit.paving(random));
                int dx = Math.abs(x - SiegeLayout.gorgeX(z));
                if (dx <= SiegeLayout.GORGE_HALF) {
                    int depth = dx >= 5 ? 4 : dx >= 4 ? 2 : 1;
                    for (int y = G - depth; y < G; y++) {
                        canvas.set(x, y, z, SiegeKit.masonry(random));
                    }
                    if (dx == 3) {
                        canvas.set(x, G - 2, z, Blocks.stairsTop(Material.STONE_BRICK_STAIRS,
                                x > SiegeLayout.gorgeX(z) ? BlockFace.EAST : BlockFace.WEST));
                    }
                }
                if (Math.abs(z) == 4) {
                    canvas.set(x, G + 1, z, BlockSpec.of(x % 3 == 0 ? Material.MOSSY_STONE_BRICK_WALL
                            : Material.STONE_BRICK_WALL));
                }
            }
        }
        // Bridge towers (cover and landmarks).
        for (int x : new int[] {x1 - 1, x2 + 1}) {
            for (int z : new int[] {-5, 5}) {
                for (int dz = 0; dz <= 1; dz++) {
                    int bz = z + Integer.signum(z) * dz;
                    canvas.fill(x, G + 1, bz, x, G + 4, bz, SiegeKit.masonry(random));
                    canvas.set(x, G + 5, bz, Blocks.slab(Material.STONE_BRICK_SLAB));
                }
                canvas.set(x, G + 5, z, Blocks.lantern(false, false));
            }
        }
        // Ladders out of the water on both banks, just south of the bridge.
        ladder(canvas, 6, true);
        ladder(canvas, 6, false);
        ladder(canvas, -6, true);
        ladder(canvas, -6, false);
    }

    /** A ladder from the river bed up to the bank, on the east or west face of the gorge at {@code z}. */
    private static void ladder(MapCanvas canvas, int z, boolean east) {
        int gx = SiegeLayout.gorgeX(z);
        int x = east ? gx + SiegeLayout.GORGE_HALF : gx - SiegeLayout.GORGE_HALF;
        BlockFace facing = east ? BlockFace.WEST : BlockFace.EAST;
        for (int y = SiegeLayout.RIVER_BED + 1; y <= G; y++) {
            BlockSpec spec = BlockSpec.of(Material.LADDER).facing(facing);
            canvas.set(x, y, z, y <= SiegeLayout.WATER_TOP ? spec.waterlogged(true) : spec);
        }
    }

    /** The north flank's rope bridge: 3 wide, sagging one block in the middle, fence rails, lantern posts. */
    private void ropeBridge(MapCanvas canvas, Random random) {
        int zc = SiegeLayout.ROPE_BRIDGE_Z;
        int gx = SiegeLayout.gorgeX(zc);
        for (int x = gx - 7; x <= gx + 7; x++) {
            int deck = Math.abs(x - gx) <= 2 ? G - 1 : G;
            for (int z = zc - 2; z <= zc + 2; z++) {
                boolean rail = Math.abs(z - zc) == 2;
                boolean overWater = Math.abs(x - SiegeLayout.gorgeX(z)) <= SiegeLayout.GORGE_HALF;
                if (overWater || Math.abs(x - gx) <= SiegeLayout.GORGE_HALF + 1) {
                    for (int y = deck + 1; y <= G; y++) {
                        canvas.clear(x, y, z);
                    }
                    canvas.set(x, deck, z, rail ? Blocks.log(Material.SPRUCE_LOG, Axis.X)
                            : random.nextInt(6) == 0 ? BlockSpec.of(Material.STRIPPED_SPRUCE_WOOD)
                            : BlockSpec.of(Material.SPRUCE_PLANKS));
                }
                if (rail) {
                    canvas.set(x, deck + 1, z, Material.SPRUCE_FENCE);
                }
            }
            if (Math.abs(x - gx) == SiegeLayout.GORGE_HALF + 2) {
                for (int z : new int[] {zc - 2, zc + 2}) {
                    canvas.fill(x, G + 1, z, x, G + 3, z, Blocks.log(Material.SPRUCE_LOG, Axis.Y));
                    canvas.set(x, G + 4, z, Blocks.lantern(false, false));
                }
            }
        }
        // Chain stays under the middle of the bridge.
        canvas.fill(gx, G - 4, zc, gx, G - 2, zc, Blocks.log(Material.IRON_CHAIN, Axis.Y));
    }

    /** The south flank's ford: stairs down both banks, stepping stones over the river, the tunnel mouth east. */
    private void ford(MapCanvas canvas, Random random) {
        int fz = SiegeLayout.FORD_Z;
        int floor = SiegeLayout.WATER_TOP;
        for (int x = FORD_X - 8; x <= FORD_X + 8; x++) {
            boolean landing = Math.abs(x - FORD_X) >= 6;
            for (int z = fz - 4; z <= fz + 2; z++) {
                if (landing) {
                    shelf(canvas, random, x, z, floor);
                } else if (z >= fz - 1 && z <= fz + 1) {
                    boolean gap = x == FORD_X - 2 || x == FORD_X + 1 || x == FORD_X + 3;
                    boolean water = Math.abs(x - SiegeLayout.gorgeX(z)) <= SiegeLayout.GORGE_HALF;
                    if (gap && water) {
                        continue;
                    }
                    shelf(canvas, random, x, z, floor);
                    canvas.set(x, floor, z, SiegeKit.pick(random, Material.MOSSY_COBBLESTONE,
                            Material.COBBLESTONE, Material.STONE, Material.ANDESITE));
                }
            }
            if (landing) {
                // Stairs up to the bank, towards the north.
                for (int i = 0; i < 5; i++) {
                    int z = fz - 5 - i;
                    int top = floor + 1 + i;
                    canvas.fill(x, SiegeLayout.RIVER_BED, z, x, top - 1, z, SiegeKit.rock(random, top));
                    canvas.set(x, top, z, Blocks.stairs(Material.COBBLESTONE_STAIRS, BlockFace.NORTH));
                    for (int y = top + 1; y <= G; y++) {
                        canvas.clear(x, y, z);
                    }
                }
            }
        }
        // Posts and a lantern where the stairs start.
        for (int x : new int[] {FORD_X - 9, FORD_X - 5, FORD_X + 5, FORD_X + 9}) {
            int z = fz - 10;
            if (canvas.canWrite(x, z) && Math.abs(x - SiegeLayout.gorgeX(z)) > SiegeLayout.GORGE_HALF) {
                canvas.set(x, G + 1, z, Material.SPRUCE_FENCE);
                canvas.set(x, G + 2, z, Blocks.lantern(false, false));
            }
        }
        // The tunnel mouth: a timber frame in the east face.
        int mx = FORD_X + 8;
        for (int z = fz - 2; z <= fz + 2; z++) {
            canvas.set(mx, floor + 4, z, Blocks.log(Material.SPRUCE_LOG, Axis.Z));
        }
    }

    /** A dry landing at the water line: solid up to {@code floor}, open above. */
    private static void shelf(MapCanvas canvas, Random random, int x, int z, int floor) {
        if (!canvas.canWrite(x, z)) {
            return;
        }
        for (int y = SiegeLayout.RIVER_BED; y <= floor; y++) {
            canvas.set(x, y, z, SiegeKit.rock(random, y));
        }
        if (random.nextInt(3) == 0) {
            canvas.set(x, floor, z, BlockSpec.of(Material.GRAVEL));
        }
        for (int y = floor + 1; y <= G; y++) {
            canvas.clear(x, y, z);
        }
    }

    // ------------------------------------------------------------------ before the wall

    /** The killing ground: stakes, a wrecked ram, boulders and craters; cover in reach of every lane. */
    private void killingGround(MapCanvas canvas, Random random) {
        // Sharpened stakes (fence rows) with gaps.
        int[][] stakes = {{-24, -14, 5}, {-24, 6, 5}, {-20, -21, 3}, {-20, 18, 4}};
        for (int[] s : stakes) {
            for (int i = 0; i < s[2]; i++) {
                int z = s[1] + i;
                canvas.set(s[0], G + 1, z, Blocks.log(Material.SPRUCE_LOG, Axis.Z));
                canvas.set(s[0], G + 2, z, Material.SPRUCE_FENCE);
            }
        }
        // A wrecked ram on the road before the gate.
        for (int x = -23; x <= -19; x++) {
            canvas.set(x, G + 1, -1, Blocks.log(Material.DARK_OAK_LOG, Axis.X));
        }
        canvas.set(-22, G + 2, -1, Blocks.slab(Material.DARK_OAK_SLAB));
        canvas.set(-20, G + 2, -1, Blocks.slab(Material.DARK_OAK_SLAB));
        canvas.set(-23, G + 1, 0, Blocks.stairs(Material.DARK_OAK_STAIRS, BlockFace.EAST));
        // Boulders fallen from the cliffs.
        SiegeKit.boulder(canvas, -22, G, -33, 2.5, 2, 2, random);
        SiegeKit.boulder(canvas, -25, G, 33, 2, 2, 2.5, random);
        SiegeKit.boulder(canvas, -19, G, 10, 1.2, 1, 1.2, random);
        SiegeKit.boulder(canvas, -26, G, -9, 1.2, 1, 1.5, random);
        // Craters.
        for (int[] c : new int[][] {{-21, -7}, {-25, 12}, {-19, -17}}) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) <= 1 && canvas.canWrite(c[0] + dx, c[1] + dz)) {
                        canvas.clear(c[0] + dx, G, c[1] + dz);
                        canvas.set(c[0] + dx, G - 1, c[1] + dz, SiegeKit.pick(random, Material.COARSE_DIRT,
                                Material.GRAVEL, Material.DIRT));
                    }
                }
            }
        }
        // West bank cover: ruined bridgehead walls.
        for (int z = -9; z <= -6; z++) {
            canvas.fill(-44, G + 1, z, -44, G + 1 + (z == -9 ? 2 : 1), z, SiegeKit.rubble(random));
        }
        for (int z = 6; z <= 9; z++) {
            canvas.fill(-44, G + 1, z, -44, G + 1 + (z == 9 ? 2 : 1), z, SiegeKit.rubble(random));
        }
        SiegeKit.crates(canvas, -27, G, -7, random);
        SiegeKit.crates(canvas, -27, G, 22, random);
        SiegeKit.lampPost(canvas, -18, G, -5, false);
        SiegeKit.lampPost(canvas, -18, G, 5, false);
        for (int[] t : new int[][] {{-26, -37}, {-21, 37}, {-43, 35}, {-43, -36}}) {
            if (canvas.canWrite(t[0], t[1])) {
                SiegeKit.spruce(canvas, t[0], G, t[1], 7 + random.nextInt(3), random);
            }
        }
        for (int i = 0; i < 14; i++) {
            int x = -27 + random.nextInt(9);
            int z = (random.nextBoolean() ? 1 : -1) * (5 + random.nextInt(33));
            if (canvas.canWrite(x, z) && canvas.get(x, G + 1, z) == null && canvas.isSolid(x, G, z)) {
                SiegeKit.bush(canvas, x, G, z, Material.SPRUCE_LEAVES, random);
            }
        }
    }
}
