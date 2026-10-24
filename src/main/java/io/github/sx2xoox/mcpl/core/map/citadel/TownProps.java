package io.github.sx2xoox.mcpl.core.map.citadel;

import java.util.Random;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

/**
 * Small pieces of the town that give cover and life between the buildings: carts, hay, barrels, lamp posts, wood
 * piles, fenced gardens, little trees, low field walls and broken wall fragments. Everything stands on the ground
 * ({@code y = 65}) and is low enough to step or jump onto, or tall enough to hide behind.
 */
final class TownProps {

    private static final int G = TownPainter.GROUND;

    private static final Material[] FLOWERS = {Material.POPPY, Material.DANDELION, Material.CORNFLOWER,
            Material.ALLIUM, Material.AZURE_BLUET, Material.OXEYE_DAISY, Material.RED_TULIP, Material.ORANGE_TULIP,
            Material.WHITE_TULIP, Material.PINK_TULIP, Material.LILY_OF_THE_VALLEY};

    private static final Material[] TALL_FLOWERS = {Material.ROSE_BUSH, Material.PEONY, Material.LILAC};

    private TownProps() {
    }

    /**
     * A hand cart: a 3x2 bed of slabs on two pairs of wheels (trapdoors), shafts pointing {@code facing}, loaded with
     * {@code load}. {@code x,z} is the bed's corner nearest the north-west.
     */
    static void cart(TownPainter p, int x, int z, BlockFace facing, Material load) {
        boolean alongX = facing == BlockFace.EAST || facing == BlockFace.WEST;
        int lenX = alongX ? 3 : 2;
        int lenZ = alongX ? 2 : 3;
        for (int ix = x; ix < x + lenX; ix++) {
            for (int iz = z; iz < z + lenZ; iz++) {
                p.set(ix, G + 1, iz, Blocks.slabTop(Material.SPRUCE_SLAB));
            }
        }
        // wheels on the long sides, at both ends
        if (alongX) {
            for (int ix : new int[] {x, x + 2}) {
                p.set(ix, G + 1, z - 1, Blocks.trapdoor(Material.DARK_OAK_TRAPDOOR, BlockFace.NORTH, false, true));
                p.set(ix, G + 1, z + 2, Blocks.trapdoor(Material.DARK_OAK_TRAPDOOR, BlockFace.SOUTH, false, true));
            }
            int sx = facing == BlockFace.EAST ? x + 3 : x - 1;
            p.set(sx, G + 1, z, Material.SPRUCE_FENCE);
            p.set(sx, G + 1, z + 1, Material.SPRUCE_FENCE);
        } else {
            for (int iz : new int[] {z, z + 2}) {
                p.set(x - 1, G + 1, iz, Blocks.trapdoor(Material.DARK_OAK_TRAPDOOR, BlockFace.WEST, false, true));
                p.set(x + 2, G + 1, iz, Blocks.trapdoor(Material.DARK_OAK_TRAPDOOR, BlockFace.EAST, false, true));
            }
            int sz = facing == BlockFace.SOUTH ? z + 3 : z - 1;
            p.set(x, G + 1, sz, Material.SPRUCE_FENCE);
            p.set(x + 1, G + 1, sz, Material.SPRUCE_FENCE);
        }
        // the load
        Random random = p.random();
        for (int ix = x; ix < x + lenX; ix++) {
            for (int iz = z; iz < z + lenZ; iz++) {
                if (random.nextDouble() < 0.6) {
                    p.set(ix, G + 2, iz, load == Material.HAY_BLOCK ? Blocks.log(load, Axis.X)
                            : BlockSpec.of(load).facing(BlockFace.UP));
                }
            }
        }
    }

    /** A loose pile of hay bales, two high in the middle (a step up, cover when crouching behind). */
    static void hayPile(TownPainter p, int x, int z) {
        Random random = p.random();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (Math.abs(dx) + Math.abs(dz) == 2 && random.nextBoolean()) {
                    continue;
                }
                if (p.isSolid(x + dx, G + 1, z + dz)) {
                    continue;
                }
                Axis axis = random.nextBoolean() ? Axis.X : Axis.Z;
                p.set(x + dx, G + 1, z + dz, Blocks.log(Material.HAY_BLOCK, axis));
                if (dx == 0 && dz == 0 || random.nextDouble() < 0.25 && Math.abs(dx) + Math.abs(dz) == 1) {
                    p.set(x + dx, G + 2, z + dz, Blocks.log(Material.HAY_BLOCK, axis));
                }
            }
        }
    }

    /** A cluster of barrels and a crate. */
    static void barrels(TownPainter p, int x, int z) {
        p.set(x, G + 1, z, BlockSpec.of(Material.BARREL).facing(BlockFace.UP));
        p.set(x + 1, G + 1, z, BlockSpec.of(Material.BARREL).facing(BlockFace.UP));
        p.set(x, G + 1, z + 1, BlockSpec.of(Material.BARREL).facing(BlockFace.NORTH));
        p.set(x, G + 2, z, BlockSpec.of(Material.BARREL).facing(BlockFace.UP));
        if (p.chance(0.5)) {
            p.set(x + 1, G + 1, z + 1, Material.SPRUCE_PLANKS);
        }
    }

    /** A wood pile: two rows of three logs lying along {@code axis}, two of them stacked a second time. */
    static void woodPile(TownPainter p, int x, int z, Axis axis) {
        int dx = axis == Axis.X ? 1 : 0;
        int dz = axis == Axis.Z ? 1 : 0;
        for (int i = 0; i < 3; i++) {
            p.log(x + dx * i, G + 1, z + dz * i, Material.SPRUCE_LOG, axis);
            p.log(x + dx * i + dz, G + 1, z + dz * i + dx, Material.OAK_LOG, axis);
            if (i < 2) {
                p.log(x + dx * i, G + 2, z + dz * i, Material.SPRUCE_LOG, axis);
            }
        }
    }

    /**
     * A fenced garden on {@code x0..x1, z0..z1}: fence all around with a gate on {@code gateSide} (and a gap on the
     * opposite side), flower beds inside, a few tall flowers and a bush.
     */
    static void garden(TownPainter p, int x0, int z0, int x1, int z1, BlockFace gateSide) {
        Random random = p.random();
        int midX = (x0 + x1) / 2;
        int midZ = (z0 + z1) / 2;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
                if (!p.owns(x, z)) {
                    continue;
                }
                if (edge) {
                    boolean gate = switch (gateSide) {
                        case NORTH -> z == z0 && x == midX;
                        case SOUTH -> z == z1 && x == midX;
                        case WEST -> x == x0 && z == midZ;
                        default -> x == x1 && z == midZ;
                    };
                    boolean gap = switch (gateSide) {
                        case NORTH -> z == z1 && Math.abs(x - midX) <= 1;
                        case SOUTH -> z == z0 && Math.abs(x - midX) <= 1;
                        case WEST -> x == x1 && Math.abs(z - midZ) <= 1;
                        default -> x == x0 && Math.abs(z - midZ) <= 1;
                    };
                    if (gate) {
                        BlockFace facing = gateSide == BlockFace.NORTH || gateSide == BlockFace.SOUTH
                                ? BlockFace.NORTH : BlockFace.EAST;
                        p.set(x, G + 1, z, BlockSpec.of(Material.SPRUCE_FENCE_GATE).facing(facing)
                                .open(random.nextBoolean()));
                    } else if (!gap && random.nextDouble() > 0.12) {
                        p.set(x, G + 1, z, Material.OAK_FENCE);
                    }
                    continue;
                }
                // beds in rows, paths between them
                boolean path = (gateSide == BlockFace.NORTH || gateSide == BlockFace.SOUTH) ? x == midX
                        : z == midZ;
                if (path) {
                    p.set(x, G, z, Material.DIRT_PATH);
                    continue;
                }
                p.set(x, G, z, random.nextDouble() < 0.7 ? Material.ROOTED_DIRT : Material.COARSE_DIRT);
                double roll = random.nextDouble();
                if (roll < 0.55) {
                    p.set(x, G + 1, z, FLOWERS[random.nextInt(FLOWERS.length)]);
                } else if (roll < 0.65) {
                    p.tallPlant(x, G + 1, z, TALL_FLOWERS[random.nextInt(TALL_FLOWERS.length)]);
                } else if (roll < 0.75) {
                    p.set(x, G + 1, z, Material.FERN);
                } else if (roll < 0.8) {
                    p.set(x, G + 1, z, Blocks.leaves(Material.FLOWERING_AZALEA_LEAVES));
                }
            }
        }
    }

    /**
     * A small town tree: a short trunk and a round crown, with a ladder nailed to the trunk on the {@code ladder} side
     * up through the crown (a perch about five blocks up).
     */
    static void tree(TownPainter p, int x, int z, Material log, Material leaves, BlockFace ladder) {
        Random random = p.random();
        int height = 5 + random.nextInt(2);
        for (int y = G + 1; y <= G + height; y++) {
            p.log(x, y, z, log, Axis.Y);
        }
        int top = G + height;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    double d = dx * dx + dz * dz + dy * dy * 2.2;
                    if (d > 5.2 || dx == 0 && dz == 0 && dy <= 0) {
                        continue;
                    }
                    if (random.nextDouble() < 0.12) {
                        continue;
                    }
                    p.setIfAir(x + dx, top + dy, z + dz, Blocks.leaves(leaves));
                }
            }
        }
        p.setIfAir(x, top + 1, z, Blocks.leaves(leaves));
        int lx = x + ladder.getModX();
        int lz = z + ladder.getModZ();
        p.clearBox(lx, G + 1, lz, lx, top + 2, lz);
        p.ladder(lx, G + 1, top + 1, lz, ladder);
    }

    /**
     * A low field wall of stones from {@code x1,z1} to {@code x2,z2} (straight or diagonal), one or two blocks high,
     * with gaps.
     */
    static void fieldWall(TownPainter p, int x1, int z1, int x2, int z2) {
        Random random = p.random();
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(z2 - z1));
        boolean previousLow = false;
        int px = Integer.MIN_VALUE;
        int pz = Integer.MIN_VALUE;
        for (int i = 0; i <= steps; i++) {
            double t = steps == 0 ? 0 : (double) i / steps;
            int x = (int) Math.round(x1 + (x2 - x1) * t);
            int z = (int) Math.round(z1 + (z2 - z1) * t);
            boolean beside = Math.abs(x - px) + Math.abs(z - pz) == 1;
            px = x;
            pz = z;
            if (random.nextDouble() < 0.12) {
                previousLow = false;
                continue; // a gap
            }
            p.set(x, G + 1, z, TownPainter.COBBLE.next(random));
            // a second layer only right beside a single stone, so it can be stepped onto
            if (previousLow && beside && random.nextDouble() < 0.4) {
                p.set(x, G + 2, z, random.nextBoolean() ? TownPainter.COBBLE.slab(random)
                        : BlockSpec.of(Material.MOSSY_COBBLESTONE));
                previousLow = false;
            } else {
                previousLow = true;
            }
        }
    }

    /**
     * A free-standing fragment of a ruined wall along x or z: up to {@code height} blocks high in the middle, stepping
     * down one block at a time towards both ends so it can be climbed.
     */
    static void wallFragment(TownPainter p, int x, int z, int length, Axis axis, int height,
                             TownPainter.Palette palette) {
        Random random = p.random();
        int peak = Math.min(height, (length - 1) / 2 + 1);
        for (int i = 0; i < length; i++) {
            int fromEnd = Math.min(i, length - 1 - i);
            int h = Math.max(1, Math.min(height, fromEnd + 1));
            int wx = x + (axis == Axis.X ? i : 0);
            int wz = z + (axis == Axis.Z ? i : 0);
            for (int y = G + 1; y <= G + h; y++) {
                p.set(wx, y, wz, palette.next(random));
            }
            // moss on the broken top now and then (the height stays one step from the next stone)
            if (h < peak && random.nextDouble() < 0.3) {
                p.set(wx, G + h, wz, Material.MOSS_BLOCK);
            }
        }
    }

    /**
     * A low grassy mound: an ellipse with radii {@code rx, rz}, {@code height} blocks high in the middle and falling
     * off by at most one block per step, so it can be walked over from every side.
     */
    static void mound(TownPainter p, int cx, int cz, double rx, double rz, int height) {
        int ix = (int) Math.ceil(rx);
        int iz = (int) Math.ceil(rz);
        for (int dx = -ix; dx <= ix; dx++) {
            for (int dz = -iz; dz <= iz; dz++) {
                double d = Math.sqrt(sq(dx / (rx + 0.5)) + sq(dz / (rz + 0.5)));
                int h = (int) Math.floor((1 - d) * (height + 0.6));
                int x = cx + dx;
                int z = cz + dz;
                if (h <= 0 || !p.owns(x, z) || p.isSolid(x, G + 1, z)) {
                    continue;
                }
                p.set(x, G, z, Material.DIRT);
                for (int y = G + 1; y < G + h; y++) {
                    p.set(x, y, z, Material.DIRT);
                }
                p.set(x, G + h, z, p.chance(0.1) ? Material.COARSE_DIRT : Material.GRASS_BLOCK);
            }
        }
    }

    /**
     * A weathered boulder on flat ground: a 2x2 block two high on its north side and one high on its south side (a
     * step up).
     */
    static void boulder(TownPainter p, int x, int z) {
        Random random = p.random();
        Material[] stone = {Material.ANDESITE, Material.STONE, Material.MOSSY_COBBLESTONE, Material.COBBLESTONE,
                Material.TUFF};
        for (int dx = 0; dx <= 1; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                p.set(x + dx, G + 1, z + dz, stone[random.nextInt(stone.length)]);
            }
        }
        p.set(x, G + 2, z, stone[random.nextInt(stone.length)]);
        p.set(x + 1, G + 2, z, random.nextBoolean() ? BlockSpec.of(Material.MOSS_BLOCK)
                : Blocks.slab(Material.ANDESITE_SLAB));
    }

    private static double sq(double v) {
        return v * v;
    }

    /** A leafy bush: two blocks high in the middle, one at the edges (a step up, cover when crouching). */
    static void bush(TownPainter p, int x, int z, Material leaves) {
        Random random = p.random();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (Math.abs(dx) + Math.abs(dz) == 2 && random.nextBoolean() || p.isSolid(x + dx, G + 1, z + dz)) {
                    continue;
                }
                p.set(x + dx, G + 1, z + dz, Blocks.leaves(leaves));
            }
        }
        p.set(x, G + 2, z, Blocks.leaves(leaves));
    }

    /** Scattered ground details: grass tufts, flowers and fallen stones on open grass inside {@code box}. */
    static void groundCover(TownPainter p, int minX, int minZ, int maxX, int maxZ, double density) {
        Random random = p.random();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!p.owns(x, z) || p.isSolid(x, G + 1, z)) {
                    continue;
                }
                BlockSpec ground = p.get(x, G, z);
                if (ground == null || ground.material() != Material.GRASS_BLOCK) {
                    continue;
                }
                double roll = random.nextDouble();
                if (roll > density) {
                    continue;
                }
                roll /= density;
                if (roll < 0.55) {
                    p.set(x, G + 1, z, Material.SHORT_GRASS);
                } else if (roll < 0.65) {
                    p.set(x, G + 1, z, FLOWERS[random.nextInt(FLOWERS.length)]);
                } else if (roll < 0.75) {
                    p.tallPlant(x, G + 1, z, Material.TALL_GRASS);
                } else if (roll < 0.82) {
                    p.set(x, G + 1, z, Material.FERN);
                } else if (roll < 0.88) {
                    p.set(x, G + 1, z, Blocks.slab(random.nextBoolean() ? Material.COBBLESTONE_SLAB
                            : Material.MOSSY_COBBLESTONE_SLAB));
                } else if (roll < 0.93) {
                    p.set(x, G + 1, z, Material.PINK_PETALS);
                } else {
                    p.set(x, G, z, random.nextBoolean() ? Material.COARSE_DIRT : Material.MOSS_BLOCK);
                }
            }
        }
    }
}
