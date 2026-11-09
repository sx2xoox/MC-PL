package io.github.sx2xoox.mcpl.core.map.citadel;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

/**
 * The ruined windmill on the second hill: a round stone tower with a millstone room, an upper floor reached by an inner
 * stair or an outside ladder, a wall broken open to the north-east whose rubble steps lead up onto the wall top, and
 * its torn sails still turned to the south (one arm has fallen into the grass).
 *
 * <p>Local coordinates: {@code u} east, {@code v} south of the tower's center; {@code b} is the y of the hilltop.
 */
final class HillsWindmill {

    /** Cells closer than this to the center are inside the tower. */
    private static final double INNER = 4.0;
    /** Cells up to this distance are the tower wall. */
    private static final double OUTER = 5.5;
    /** The angle (degrees, 0 = east, 90 = south) where the wall is broken down to the upper floor. */
    private static final double BREACH = 312;
    /** Offset (south) of the plane of the sails. */
    private static final int SAILS = 7;
    /** Height of the sails' hub above the hilltop. */
    private static final int HUB = 11;

    private static final BlockSpec PLANKS = BlockSpec.of(Material.SPRUCE_PLANKS);
    private static final BlockSpec FENCE = BlockSpec.of(Material.SPRUCE_FENCE);

    private final HillsSite site;
    private final int cx;
    private final int cz;
    private final int b;

    HillsWindmill(HillsSite site, int[] center) {
        this.site = site;
        this.cx = center[0];
        this.cz = center[1];
        this.b = site.ground(cx, cz);
    }

    void build() {
        reserveSails();
        groundworks();
        walls();
        floors();
        stair();
        openings();
        head();
        sails();
        fallenArm();
        dressing();
    }

    // ------------------------------------------------------------------ parts

    private void reserveSails() {
        for (int u = -11; u <= 11; u++) {
            for (int v = SAILS - 1; v <= SAILS + 1; v++) {
                site.mark(cx + u, cz + v, HillsSite.Use.STRUCTURE);
            }
        }
    }

    private void groundworks() {
        for (int u = -8; u <= 8; u++) {
            for (int v = -8; v <= 8; v++) {
                int x = cx + u;
                int z = cz + v;
                double d = Math.sqrt(u * u + v * v);
                if (d > 7.5 || d < OUTER || site.use(x, z) != HillsSite.Use.STRUCTURE) {
                    continue;
                }
                double worn = site.noise(x / 2.5, z / 2.5, 71);
                if (worn > 0.15) {
                    site.set(x, site.ground(x, z), z, site.pick(Material.COARSE_DIRT, Material.GRAVEL,
                            Material.DIRT_PATH, Material.COARSE_DIRT));
                }
            }
        }
    }

    /** The round stone wall, from its foundation to a broken top (lowest at the breach, rising in rubble steps). */
    private void walls() {
        for (int u = -6; u <= 6; u++) {
            for (int v = -6; v <= 6; v++) {
                double d = Math.sqrt(u * u + v * v);
                if (d < INNER || d > OUTER) {
                    continue;
                }
                int x = cx + u;
                int z = cz + v;
                for (int y = site.ground(x, z) + 1; y <= b; y++) {
                    site.set(x, y, z, Material.COBBLESTONE);
                }
                int top = wallTop(u, v);
                for (int y = b + 1; y <= top; y++) {
                    set(u, y, v, wallBlock(y));
                }
                site.setGround(x, z, b);
            }
        }
    }

    /** The y of the wall's top block at a wall cell. */
    private int wallTop(int u, int v) {
        double angle = Math.toDegrees(Math.atan2(v, u));
        if (angle < 0) {
            angle += 360;
        }
        double fromBreach = angle - BREACH;
        if (fromBreach < -180) {
            fromBreach += 360;
        } else if (fromBreach > 180) {
            fromBreach -= 360;
        }
        int full = b + 10 - (site.hash(cx + u, cz + v, 72) > 0.45 ? 1 : 0);
        if (fromBreach >= -16 && fromBreach <= 10) {
            return b + 5;
        }
        if (fromBreach > 10 && fromBreach < 90) {
            // Rubble steps rising clockwise from the breach, one block per 15 degrees.
            return Math.min(full, b + 5 + (int) Math.ceil((fromBreach - 10) / 15.0));
        }
        return full;
    }

    private BlockSpec wallBlock(int y) {
        double roll = site.random.nextDouble();
        if (y <= b + 2) {
            return BlockSpec.of(roll < 0.55 ? Material.COBBLESTONE : roll < 0.85 ? Material.MOSSY_COBBLESTONE
                    : Material.ANDESITE);
        }
        Material m = roll < 0.5 ? Material.STONE_BRICKS : roll < 0.7 ? Material.MOSSY_STONE_BRICKS
                : roll < 0.85 ? Material.CRACKED_STONE_BRICKS : roll < 0.95 ? Material.COBBLESTONE
                : Material.ANDESITE;
        return BlockSpec.of(m);
    }

    /** The millstone room's floor and the upper floor at b+5 (with the stair hole). */
    private void floors() {
        for (int u = -4; u <= 4; u++) {
            for (int v = -4; v <= 4; v++) {
                if (Math.sqrt(u * u + v * v) >= INNER) {
                    continue;
                }
                set(u, b, v, BlockSpec.of(site.pick(Material.SPRUCE_PLANKS, Material.SPRUCE_PLANKS,
                        Material.COBBLESTONE, Material.STONE_BRICKS)));
                boolean hole = v == 3 && u >= -1 && u <= 1;
                if (!hole) {
                    set(u, b + 5, v, u % 3 == 0 ? Blocks.log(Material.STRIPPED_SPRUCE_LOG, Axis.Z) : PLANKS);
                }
            }
        }
        // Railing on the north side of the stair hole.
        for (int u = -1; u <= 1; u++) {
            set(u, b + 6, 2, FENCE);
        }
    }

    /** The inner stair along the south wall, climbing east. */
    private void stair() {
        for (int k = 0; k <= 3; k++) {
            int u = -2 + k;
            for (int y = b + 1; y < b + 1 + k; y++) {
                set(u, y, 3, BlockSpec.of(Material.COBBLESTONE));
            }
            set(u, b + 1 + k, 3, Blocks.stairs(Material.SPRUCE_STAIRS, BlockFace.EAST));
        }
    }

    /** The west door, the broken north doorway, the east window with its ladder, and small windows. */
    private void openings() {
        // West: a spruce door in the outer cell, the inner cell open.
        for (int y = b + 1; y <= b + 3; y++) {
            clear(-4, y, 0);
        }
        Blocks.door(site.canvas, cx - 5, b + 1, cz, Material.SPRUCE_DOOR, BlockFace.WEST, false);
        set(-5, b + 3, 0, Blocks.stairsTop(Material.STONE_BRICK_STAIRS, BlockFace.EAST));
        // North: a doorway knocked through, three wide, with a ragged top.
        for (int u = -1; u <= 1; u++) {
            for (int v = -5; v <= -4; v++) {
                int top = b + 3 + (u == 0 ? 1 : 0);
                for (int y = b + 1; y <= top; y++) {
                    clear(u, y, v);
                }
            }
        }
        // East: a window on the upper floor, reached by a ladder up the outside of the wall.
        for (int u = 4; u <= 5; u++) {
            for (int y = b + 6; y <= b + 7; y++) {
                clear(u, y, 0);
            }
        }
        for (int y = b + 1; y <= b + 5; y++) {
            set(6, y, 0, BlockSpec.of(Material.LADDER).facing(BlockFace.EAST));
        }
        // Small windows on the upper floor (west and south-west).
        for (int y = b + 6; y <= b + 7; y++) {
            clear(-5, y, 0);
            clear(-4, y, 0);
            clear(-4, y, 3);
            clear(-3, y, 3);
        }
    }

    /** The timber head on the south wall top that carries the sails' axle. */
    private void head() {
        for (int u = -1; u <= 1; u++) {
            for (int v = 3; v <= 5; v++) {
                set(u, b + 11, v, Blocks.log(Material.STRIPPED_SPRUCE_LOG, Axis.Z));
                set(u, b + 12, v, Blocks.slab(Material.SPRUCE_SLAB));
            }
        }
        for (int v = 6; v <= SAILS; v++) {
            set(0, b + HUB, v, Blocks.log(Material.SPRUCE_LOG, Axis.Z));
        }
        // A beam end on the wall top: the step from the wall walk up onto the head.
        set(2, b + 11, 4, Blocks.log(Material.STRIPPED_SPRUCE_LOG, Axis.X));
        // The wall under and beside the head stays whole up to b+10.
        for (int u = -2; u <= 2; u++) {
            for (int v = 3; v <= 5; v++) {
                double d = Math.sqrt(u * u + v * v);
                if (d >= INNER && d <= OUTER) {
                    for (int y = b + 1; y <= b + 10; y++) {
                        if (site.isAir(cx + u, y, cz + v)) {
                            set(u, y, v, wallBlock(y));
                        }
                    }
                }
            }
        }
    }

    /** Four sail arms on the plane south of the tower: fence spars with torn wool sails; the south-west arm broke. */
    private void sails() {
        int z = SAILS;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                boolean broken = sx < 0 && sy < 0;
                int length = broken ? 2 : 8;
                for (int k = 1; k <= length; k++) {
                    set(sx * k, b + HUB + sy * k, z, FENCE);
                }
                if (broken) {
                    continue;
                }
                for (int a = -10; a <= 10; a++) {
                    for (int c = -10; c <= 10; c++) {
                        double along = (a * sx + c * sy) / 2.0;
                        double perp = (c * sx - a * sy) / 2.0;
                        if (along < 2.5 || along > 8.2 || perp < 0.5 || perp > 2.0) {
                            continue;
                        }
                        double roll = site.random.nextDouble();
                        if (roll < 0.07) {
                            continue;
                        }
                        Material cloth = roll < 0.75 ? Material.WHITE_WOOL : roll < 0.9 ? Material.LIGHT_GRAY_WOOL
                                : Material.COBWEB;
                        set(a, b + HUB + c, z, BlockSpec.of(cloth));
                    }
                }
            }
        }
    }

    /** The broken arm lies in the grass below the sails. */
    private void fallenArm() {
        int[][] line = {{-4, 10}, {-5, 10}, {-6, 11}, {-7, 11}, {-8, 12}, {-9, 12}};
        for (int i = 0; i < line.length; i++) {
            int x = cx + line[i][0];
            int z = cz + line[i][1];
            if (!site.free(x, z)) {
                continue;
            }
            site.set(x, site.ground(x, z) + 1, z, FENCE);
            site.mark(x, z, HillsSite.Use.STRUCTURE);
            int sx = x + (i % 2 == 0 ? 0 : 1);
            int sz = z + 1;
            if (site.free(sx, sz)) {
                site.set(sx, site.ground(sx, sz) + 1, sz, i % 3 == 0 ? Material.LIGHT_GRAY_WOOL
                        : Material.WHITE_WOOL);
                site.mark(sx, sz, HillsSite.Use.STRUCTURE);
            }
        }
    }

    /** The millstone, sacks, barrels, cobwebs and a lantern; rubble outside the breach. */
    private void dressing() {
        // The millstones: a pair of smooth stone slabs (a grindstone would need a wall to hang on).
        for (int u = -1; u <= 0; u++) {
            for (int v = -2; v <= -1; v++) {
                set(u, b + 1, v, Blocks.slab(Material.SMOOTH_STONE_SLAB));
            }
        }
        set(-2, b + 1, -2, Blocks.log(Material.HAY_BLOCK, Axis.Y));
        set(-3, b + 1, -1, Blocks.log(Material.HAY_BLOCK, Axis.X));
        set(2, b + 1, -2, BlockSpec.of(Material.BARREL).facing(BlockFace.UP));
        set(2, b + 1, 0, BlockSpec.of(Material.COMPOSTER));
        set(-1, b + 4, -2, Blocks.lantern(false, true));
        set(-2, b + 6, -2, BlockSpec.of(Material.BARREL).facing(BlockFace.UP));
        set(2, b + 6, -2, Blocks.lantern(false, false));
        set(-3, b + 9, 1, BlockSpec.of(Material.COBWEB));
        set(2, b + 9, -3, BlockSpec.of(Material.COBWEB));
        set(3, b + 4, 1, BlockSpec.of(Material.COBWEB));
        // Rubble fallen out of the breach.
        double rad = Math.toRadians(BREACH);
        for (int i = 0; i < 9; i++) {
            double dist = 6 + site.random.nextDouble() * 3;
            double angle = rad + (site.random.nextDouble() - 0.5) * 0.8;
            int x = cx + (int) Math.round(Math.cos(angle) * dist);
            int z = cz + (int) Math.round(Math.sin(angle) * dist);
            if (!site.inZone(x, z) || site.use(x, z) == HillsSite.Use.PATH
                    || !site.isAir(x, site.ground(x, z) + 1, z)) {
                continue;
            }
            BlockSpec rubble = site.chance(0.5) ? Blocks.slab(site.pick(Material.STONE_BRICK_SLAB,
                    Material.COBBLESTONE_SLAB, Material.MOSSY_COBBLESTONE_SLAB))
                    : BlockSpec.of(site.pick(Material.COBBLESTONE, Material.MOSSY_STONE_BRICKS,
                    Material.CRACKED_STONE_BRICKS));
            site.set(x, site.ground(x, z) + 1, z, rubble);
            site.mark(x, z, HillsSite.Use.STRUCTURE);
        }
    }

    // ------------------------------------------------------------------ helpers

    private void set(int u, int y, int v, BlockSpec spec) {
        site.set(cx + u, y, cz + v, spec);
    }

    private void clear(int u, int y, int v) {
        site.clear(cx + u, y, cz + v);
    }
}
