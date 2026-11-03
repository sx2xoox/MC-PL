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
 * The west end: the attackers' camp (a palisaded yard with a roofed muster ground and three gates) and the siege field
 * in front of it, with tracks to the three gorge crossings, the ruins of a hamlet on the north side, a quarry on the
 * south side and siege engines and barricades as cover along the main road.
 */
final class SiegeCampZone implements MapZone {

    private static final int YARD_X1 = -86;
    private static final int YARD_X2 = SiegeLayout.CAMP_FRONT;
    private static final int YARD_Z = 18;
    private static final int G = SiegeLayout.GROUND_Y;

    @Override
    public String name() {
        return "camp";
    }

    @Override
    public boolean contains(int x, int z) {
        return SiegeLayout.isInterior(x, z) && x < -44;
    }

    @Override
    public void build(MapCanvas canvas) {
        Random random = canvas.random(name());
        tracks(canvas, random);
        yard(canvas, random);
        pavilion(canvas, random);
        tents(canvas, random);
        hamlet(canvas, random);
        quarry(canvas, random);
        engines(canvas, random);
        trees(canvas, random);
    }

    // ------------------------------------------------------------------ ground

    private void tracks(MapCanvas canvas, Random random) {
        // Yard floor: trampled.
        for (int x = YARD_X1; x <= YARD_X2; x++) {
            for (int z = -YARD_Z; z <= YARD_Z; z++) {
                if (canvas.canWrite(x, z) && random.nextInt(3) > 0) {
                    canvas.set(x, G, z, SiegeKit.pick(random, Material.COARSE_DIRT, Material.DIRT_PATH,
                            Material.PACKED_MUD, Material.GRASS_BLOCK));
                }
            }
        }
        // Main road and the two flank tracks out of the yard gates.
        for (int x = YARD_X2; x < -44; x++) {
            for (int dz = -3; dz <= 3; dz++) {
                setTrack(canvas, random, x, dz, Math.abs(dz) == 3 ? 3 : 1);
            }
            double t = (x - YARD_X2) / 22.0;
            int north = (int) Math.round(-13 + (SiegeLayout.ROPE_BRIDGE_Z + 13) * Math.min(1, t));
            int south = (int) Math.round(13 + 2 * Math.min(1, t));
            for (int dz = -1; dz <= 1; dz++) {
                setTrack(canvas, random, x, north + dz, 2);
                setTrack(canvas, random, x, south + dz, 2);
            }
        }
    }

    private static void setTrack(MapCanvas canvas, Random random, int x, int z, int sparse) {
        if (!canvas.canWrite(x, z) || random.nextInt(sparse) != 0) {
            return;
        }
        int y = SiegeLayout.ground(x, z);
        canvas.set(x, y, z, SiegeKit.track(random));
    }

    // ------------------------------------------------------------------ the yard

    /** Palisade around the yard: the cliff closes the back, three gates open to the field. */
    private void yard(MapCanvas canvas, Random random) {
        for (int z = -YARD_Z; z <= YARD_Z; z++) {
            boolean gate = Math.abs(z) <= 2 || Math.abs(z) >= 11 && Math.abs(z) <= 15;
            if (gate) {
                continue;
            }
            palisade(canvas, random, YARD_X2, z);
        }
        for (int x = YARD_X1; x <= YARD_X2; x++) {
            palisade(canvas, random, x, -YARD_Z);
            palisade(canvas, random, x, YARD_Z);
        }
        // Gate posts with torches and banners of canvas.
        for (int z : new int[] {-3, 3, -16, -10, 10, 16}) {
            canvas.fill(YARD_X2, G + 1, z, YARD_X2, G + 5, z, Blocks.log(Material.STRIPPED_SPRUCE_LOG, Axis.Y));
            canvas.set(YARD_X2, G + 6, z, Blocks.lantern(false, false));
        }
        for (int z = -2; z <= 2; z++) {
            canvas.set(YARD_X2, G + 5, z, Blocks.log(Material.SPRUCE_LOG, Axis.Z));
        }
        canvas.set(YARD_X2 - 1, G + 4, 0, Blocks.lantern(false, true));
        canvas.set(YARD_X2 + 1, G + 4, 0, Blocks.lantern(false, true));
        // Watch platforms in the front corners (decoration, out of reach).
        for (int z : new int[] {-YARD_Z, YARD_Z}) {
            canvas.fill(YARD_X2 - 2, G + 6, z - Integer.signum(z) * 2, YARD_X2, G + 6, z,
                    BlockSpec.of(Material.SPRUCE_PLANKS));
            canvas.set(YARD_X2 - 1, G + 7, z - Integer.signum(z), Blocks.lantern(false, false));
        }
    }

    private static void palisade(MapCanvas canvas, Random random, int x, int z) {
        if (!canvas.canWrite(x, z)) {
            return;
        }
        int height = 4 + (Math.floorMod(x * 7 + z * 3, 5) == 0 ? 1 : 0);
        canvas.fill(x, G + 1, z, x, G + height, z, Blocks.log(random.nextInt(5) == 0 ? Material.STRIPPED_SPRUCE_LOG
                : Material.SPRUCE_LOG, Axis.Y));
        canvas.set(x, G + height + 1, z, BlockSpec.of(Material.SPRUCE_FENCE));
    }

    /** The roofed muster ground over the spawn, open to the front. */
    private void pavilion(MapCanvas canvas, Random random) {
        int x1 = -84;
        int x2 = -74;
        int z1 = -7;
        int z2 = 7;
        int roof = G + 6;
        for (int x = x1; x <= x2; x += 5) {
            for (int z : new int[] {z1, z2}) {
                canvas.fill(x, G + 1, z, x, roof - 1, z, Blocks.log(Material.SPRUCE_LOG, Axis.Y));
            }
        }
        for (int x = x1; x <= x2; x++) {
            for (int z = z1 - 1; z <= z2 + 1; z++) {
                int lift = Math.min(2, Math.min(z - (z1 - 1), (z2 + 1) - z) / 3);
                canvas.set(x, roof + lift, z, (x + z) % 3 == 0 ? BlockSpec.of(Material.RED_WOOL)
                        : BlockSpec.of(Material.WHITE_WOOL));
            }
        }
        for (int z = z1; z <= z2; z++) {
            canvas.set(x1, roof - 1, z, Blocks.log(Material.SPRUCE_LOG, Axis.Z));
            canvas.set(x2, roof - 1, z, Blocks.log(Material.SPRUCE_LOG, Axis.Z));
        }
        canvas.set(-80, roof - 1, 0, Blocks.lantern(false, true));
        canvas.set(-80, roof - 1, -5, Blocks.lantern(false, true));
        canvas.set(-80, roof - 1, 5, Blocks.lantern(false, true));
        // Back wall of the muster ground: racks and supplies.
        for (int z = z1 + 1; z < z2; z++) {
            canvas.set(x1, G + 1, z, z % 2 == 0 ? BlockSpec.of(Material.BARREL)
                    : BlockSpec.of(Material.SPRUCE_FENCE));
        }
        canvas.set(x1, G + 1, 0, Material.SMITHING_TABLE);
        canvas.set(x1, G + 1, 1, Material.GRINDSTONE);
        canvas.set(x1, G + 1, -1, Material.ANVIL);
        // A war table with a map in front of the spawn.
        canvas.set(-77, G + 1, -6, Material.CARTOGRAPHY_TABLE);
        canvas.set(-77, G + 1, 6, Material.FLETCHING_TABLE);
        // Campfires by the gates.
        canvas.set(-70, G + 1, -6, Blocks.campfire(true));
        canvas.set(-70, G + 1, 6, Blocks.campfire(true));
    }

    /** Canvas tents along both side palisades. */
    private void tents(MapCanvas canvas, Random random) {
        for (int x = -82; x <= -70; x += 6) {
            tent(canvas, x, -YARD_Z + 2, true, random);
            tent(canvas, x, YARD_Z - 2, false, random);
        }
        SiegeKit.crates(canvas, -72, G, -10, random);
        SiegeKit.crates(canvas, -72, G, 10, random);
        SiegeKit.crates(canvas, -82, G, -11, random);
        SiegeKit.crates(canvas, -82, G, 11, random);
        SiegeKit.lampPost(canvas, -71, G, -2, false);
        SiegeKit.lampPost(canvas, -71, G, 2, false);
    }

    /** An A-frame tent 5 wide (x) with its ridge along z, 4 deep, opening towards the yard. */
    private static void tent(MapCanvas canvas, int cx, int edgeZ, boolean north, Random random) {
        Material cloth = random.nextBoolean() ? Material.WHITE_WOOL : Material.LIGHT_GRAY_WOOL;
        int dir = north ? 1 : -1;
        for (int i = 0; i < 4; i++) {
            int z = edgeZ + dir * i;
            for (int dx = -2; dx <= 2; dx++) {
                int h = 3 - Math.abs(dx);
                if (Math.abs(dx) == 2) {
                    canvas.set(cx + dx, G + 1, z, BlockSpec.of(cloth));
                } else {
                    canvas.set(cx + dx, G + h, z, BlockSpec.of(cloth));
                }
            }
        }
        // Back wall against the palisade.
        canvas.fill(cx - 1, G + 1, edgeZ, cx + 1, G + 2, edgeZ, BlockSpec.of(cloth));
        canvas.set(cx, G + 1, edgeZ + dir, Material.BROWN_CARPET);
        canvas.set(cx, G + 4, edgeZ + dir * 3, Material.SPRUCE_FENCE);
    }

    // ------------------------------------------------------------------ the field

    /** The burnt hamlet north of the road: two roofless cottages and a well, cover for the north flank. */
    private void hamlet(MapCanvas canvas, Random random) {
        cottage(canvas, -60, -34, -53, -27, random);
        cottage(canvas, -52, -22, -47, -17, random);
        // Well.
        int wx = -56;
        int wz = -21;
        int g = SiegeLayout.ground(wx, wz);
        canvas.fill(wx - 1, g + 1, wz - 1, wx + 1, g + 1, wz + 1, BlockSpec.of(Material.COBBLESTONE));
        canvas.set(wx, g + 1, wz, Material.WATER_CAULDRON);
        canvas.set(wx - 1, g + 2, wz - 1, Material.SPRUCE_FENCE);
        canvas.set(wx + 1, g + 2, wz + 1, Material.SPRUCE_FENCE);
        canvas.set(wx - 1, g + 3, wz - 1, Blocks.slab(Material.SPRUCE_SLAB));
        canvas.set(wx + 1, g + 3, wz + 1, Blocks.slab(Material.SPRUCE_SLAB));
        // A broken fence line along the track.
        for (int x = -63; x <= -48; x++) {
            if (random.nextInt(3) > 0) {
                int z = -37;
                if (canvas.canWrite(x, z)) {
                    canvas.set(x, SiegeLayout.ground(x, z) + 1, z, Material.SPRUCE_FENCE);
                }
            }
        }
    }

    /** A burnt, roofless cottage with broken walls (2-4 high) and a door gap on every side. */
    private static void cottage(MapCanvas canvas, int x1, int z1, int x2, int z2, Random random) {
        int base = 64;
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                base = Math.max(base, SiegeLayout.ground(x, z));
            }
        }
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                if (!canvas.canWrite(x, z)) {
                    continue;
                }
                for (int y = SiegeLayout.ground(x, z); y <= base; y++) {
                    canvas.set(x, y, z, y == base ? SiegeKit.pick(random, Material.SPRUCE_PLANKS,
                            Material.COBBLESTONE, Material.COARSE_DIRT) : BlockSpec.of(Material.DIRT));
                }
                boolean edge = x == x1 || x == x2 || z == z1 || z == z2;
                if (!edge) {
                    if (random.nextInt(9) == 0) {
                        canvas.set(x, base + 1, z, SiegeKit.pick(random, Material.COBBLESTONE_SLAB,
                                Material.SPRUCE_SLAB));
                    }
                    continue;
                }
                boolean door = x == (x1 + x2) / 2 || z == (z1 + z2) / 2;
                if (door) {
                    continue;
                }
                boolean corner = (x == x1 || x == x2) && (z == z1 || z == z2);
                int h = corner ? 4 : 1 + random.nextInt(3);
                for (int y = base + 1; y <= base + h; y++) {
                    canvas.set(x, y, z, corner ? Blocks.log(Material.STRIPPED_SPRUCE_LOG, Axis.Y)
                            : y == base + 1 ? BlockSpec.of(Material.COBBLESTONE) : random.nextInt(3) == 0
                            ? BlockSpec.of(Material.SPRUCE_PLANKS) : BlockSpec.of(Material.MUD_BRICKS));
                }
            }
        }
        canvas.set((x1 + x2) / 2, base + 1, (z1 + z2) / 2, Blocks.campfire(false));
    }

    /** A stone quarry south of the road: cut blocks, boulders and a crane. */
    private void quarry(MapCanvas canvas, Random random) {
        SiegeKit.boulder(canvas, -60, G, 30, 3, 3, 2.5, random);
        SiegeKit.boulder(canvas, -51, G, 34, 2.5, 2, 2, random);
        SiegeKit.boulder(canvas, -63, G, 22, 1.5, 1, 1.5, random);
        int[][] blocks = {{-56, 24}, {-54, 25}, {-55, 30}, {-49, 28}, {-52, 20}, {-47, 32}};
        for (int[] b : blocks) {
            int g = SiegeLayout.ground(b[0], b[1]);
            canvas.fill(b[0], g + 1, b[1], b[0] + 1, g + 1 + random.nextInt(2), b[1] + 1,
                    SiegeKit.pick(random, Material.SMOOTH_STONE, Material.STONE, Material.POLISHED_ANDESITE));
        }
        // Crane: a log mast with a beam and a hanging chain.
        int cx = -57;
        int cz = 35;
        int g = SiegeLayout.ground(cx, cz);
        canvas.fill(cx, g + 1, cz, cx, g + 7, cz, Blocks.log(Material.SPRUCE_LOG, Axis.Y));
        canvas.fill(cx + 1, g + 7, cz, cx + 4, g + 7, cz, Blocks.log(Material.SPRUCE_LOG, Axis.X));
        canvas.fill(cx + 4, g + 4, cz, cx + 4, g + 6, cz, Blocks.log(Material.IRON_CHAIN, Axis.Y));
        canvas.set(cx + 4, g + 3, cz, Material.BARREL);
    }

    /** Siege engines and barricades along the road: cover every ten blocks or so. */
    private void engines(MapCanvas canvas, Random random) {
        trebuchet(canvas, -58, 8, random);
        siegeTowerWreck(canvas, -51, -9, random);
        // A wrecked ram in the middle of the road splits the road in two.
        for (int x = -57; x <= -53; x++) {
            canvas.set(x, G + 1, 0, Blocks.log(Material.DARK_OAK_LOG, Axis.X));
        }
        canvas.set(-57, G + 1, -1, Blocks.slab(Material.SPRUCE_SLAB));
        canvas.set(-53, G + 1, 1, Blocks.slab(Material.SPRUCE_SLAB));
        canvas.fill(-55, G + 2, 0, -55, G + 2, 0, BlockSpec.of(Material.SPRUCE_PLANKS));
        // Barricade lines with gaps.
        SiegeKit.barricade(canvas, -62, SiegeLayout.ground(-62, -9), -9, 4, false, random);
        SiegeKit.barricade(canvas, -62, SiegeLayout.ground(-62, 5), 5, 4, false, random);
        SiegeKit.barricade(canvas, -48, SiegeLayout.ground(-48, 4), 4, 3, false, random);
        SiegeKit.barricade(canvas, -48, SiegeLayout.ground(-48, -6), -6, 3, false, random);
        SiegeKit.crates(canvas, -64, G, 2, random);
        SiegeKit.crates(canvas, -47, G, -1, random);
        hayCart(canvas, -60, -17, random);
        hayCart(canvas, -50, 17, random);
        SiegeKit.lampPost(canvas, -61, G, -4, false);
        SiegeKit.lampPost(canvas, -49, G, 4, false);
    }

    /** A trebuchet south of the road (a frame of logs with a raised arm). */
    private static void trebuchet(MapCanvas canvas, int x, int z, Random random) {
        int g = SiegeLayout.ground(x, z + 2);
        for (int dz : new int[] {0, 4}) {
            canvas.fill(x - 3, g + 1, z + dz, x + 3, g + 1, z + dz, Blocks.log(Material.DARK_OAK_LOG, Axis.X));
            canvas.fill(x, g + 2, z + dz, x, g + 5, z + dz, Blocks.log(Material.DARK_OAK_LOG, Axis.Y));
            canvas.set(x - 1, g + 2, z + dz, Blocks.stairs(Material.DARK_OAK_STAIRS, BlockFace.EAST));
            canvas.set(x + 1, g + 2, z + dz, Blocks.stairs(Material.DARK_OAK_STAIRS, BlockFace.WEST));
        }
        canvas.fill(x, g + 5, z + 1, x, g + 5, z + 3, Blocks.log(Material.SPRUCE_LOG, Axis.Z));
        // The arm leaning back over the camp, the counterweight hanging.
        canvas.line(x + 3, g + 4, z + 2, x - 4, g + 9, z + 2, Blocks.log(Material.SPRUCE_LOG, Axis.X));
        canvas.fill(x + 3, g + 2, z + 2, x + 3, g + 3, z + 2, BlockSpec.of(Material.COBBLED_DEEPSLATE));
        canvas.set(x - 2, g + 1, z + 2, Material.HAY_BLOCK);
        canvas.set(x - 2, g + 1, z + 1, Material.COBBLESTONE);
    }

    /** A burnt, toppled siege tower north of the road. */
    private static void siegeTowerWreck(MapCanvas canvas, int x, int z, Random random) {
        int g = SiegeLayout.ground(x + 2, z + 1);
        for (int dx = 0; dx <= 5; dx++) {
            for (int dz = 0; dz <= 3; dz++) {
                boolean frame = dz == 0 || dz == 3;
                if (frame) {
                    canvas.set(x + dx, g + 1, z + dz, Blocks.log(Material.DARK_OAK_LOG, Axis.X));
                    if (dx % 2 == 0) {
                        canvas.set(x + dx, g + 2, z + dz, Blocks.log(Material.DARK_OAK_LOG, Axis.Y));
                        canvas.set(x + dx, g + 3, z + dz, random.nextBoolean()
                                ? Blocks.slab(Material.DARK_OAK_SLAB) : BlockSpec.of(Material.DARK_OAK_PLANKS));
                    }
                } else if (dx == 0 || dx == 5) {
                    canvas.set(x + dx, g + 1, z + dz, BlockSpec.of(Material.DARK_OAK_PLANKS));
                    canvas.set(x + dx, g + 2, z + dz, Blocks.slab(Material.DARK_OAK_SLAB));
                }
            }
        }
        canvas.set(x + 2, g + 1, z + 1, Blocks.campfire(false));
        canvas.set(x + 3, g + 1, z + 2, Material.COBWEB);
    }

    private static void hayCart(MapCanvas canvas, int x, int z, Random random) {
        int g = SiegeLayout.ground(x, z);
        canvas.fill(x, g + 1, z, x + 2, g + 1, z + 1, BlockSpec.of(Material.SPRUCE_SLAB));
        canvas.fill(x, g + 2, z, x + 1, g + 2, z + 1, BlockSpec.of(Material.HAY_BLOCK));
        canvas.set(x + 3, g + 1, z, Material.SPRUCE_FENCE);
        canvas.set(x + 3, g + 1, z + 1, Material.SPRUCE_FENCE);
    }

    private void trees(MapCanvas canvas, Random random) {
        int[][] spots = {{-64, -33}, {-58, -38}, {-46, -35}, {-45, -24}, {-64, 37}, {-48, 37}, {-45, 22},
                {-62, -12}, {-63, 15}, {-46, 12}, {-53, -14}};
        for (int[] s : spots) {
            if (!canvas.canWrite(s[0], s[1])) {
                continue;
            }
            int g = SiegeLayout.ground(s[0], s[1]);
            if (random.nextInt(3) == 0) {
                SiegeKit.oak(canvas, s[0], g, s[1], 4 + random.nextInt(2), Material.OAK_LOG, Material.OAK_LEAVES,
                        random);
            } else {
                SiegeKit.spruce(canvas, s[0], g, s[1], 7 + random.nextInt(3), random);
            }
        }
        for (int i = 0; i < 22; i++) {
            int x = -64 + random.nextInt(20);
            int z = (random.nextBoolean() ? 1 : -1) * (8 + random.nextInt(30));
            if (canvas.canWrite(x, z) && canvas.get(x, SiegeLayout.ground(x, z) + 1, z) == null) {
                SiegeKit.bush(canvas, x, SiegeLayout.ground(x, z), z, random.nextBoolean() ? Material.OAK_LEAVES
                        : Material.AZALEA_LEAVES, random);
            }
        }
    }
}
