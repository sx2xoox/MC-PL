package io.github.sx2xoox.mcpl.core.map.siege;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import org.bukkit.Material;

/**
 * City blocks and street furniture of 네온 시티: rows of buildings with alleys between them, glass skybridges, and the
 * cover along the streets (cars, barriers, crates, lamps, vending machines, planters).
 */
final class SiegeStreets {

    private SiegeStreets() {
    }

    private static final int G = SiegeLayout.GROUND_Y;

    /**
     * Fills a city block with a row of buildings along x, each as deep as the block, with alleys (3-5 wide) between
     * some of them. Buildings get doors on the {@code doors} sides; some wide ones are enterable up to the roof.
     */
    static List<SiegeKit.Building> block(MapCanvas canvas, Random random, int x1, int z1, int x2, int z2, int doors,
                                         int minStoreys, int maxStoreys, double enterable) {
        List<SiegeKit.Building> out = new ArrayList<>();
        int x = x1;
        while (x2 - x + 1 >= 7) {
            int w = 8 + random.nextInt(8);
            if (x2 - x + 1 - w < 7) {
                w = x2 - x + 1;
            }
            boolean enter = w >= 13 && random.nextDouble() < enterable;
            int storeys = minStoreys + random.nextInt(maxStoreys - minStoreys + 1);
            SiegeKit.Building b = new SiegeKit.Building(x, z1, x + w - 1, z2, storeys, enter, doors);
            SiegeKit.building(canvas, random, b);
            out.add(b);
            x += w;
            int gap = random.nextInt(3) == 0 ? 0 : 3 + random.nextInt(3);
            if (gap > 0 && x2 - (x + gap) + 1 >= 7) {
                alley(canvas, random, x, z1, x + gap - 1, z2, b);
                x += gap;
            }
        }
        return out;
    }

    /** An alley: dumpsters and crates along it, cables overhead between the two walls. */
    private static void alley(MapCanvas canvas, Random random, int x1, int z1, int x2, int z2,
                              SiegeKit.Building west) {
        int depth = z2 - z1;
        for (int i = 0; i < depth / 9; i++) {
            int z = z1 + 4 + i * 9 + random.nextInt(3);
            if (z + 1 >= z2 - 2) {
                break;
            }
            if (random.nextBoolean()) {
                SiegeKit.dumpster(canvas, x1, z, false);
            } else if (x2 - x1 >= 3) {
                SiegeKit.crates(canvas, random, x2 - 1, z, G + 1);
            }
        }
        for (int i = 0; i < 3; i++) {
            int z = z1 + 2 + random.nextInt(Math.max(1, depth - 3));
            int y = G + 7 + random.nextInt(Math.max(1, west.roof() - G - 8));
            if (canvas.isSolid(x1 - 1, y, z) && canvas.isSolid(x2 + 1, y, z)) {
                SiegeKit.cable(canvas, x1, y, z, x2, z);
            }
        }
    }

    /**
     * A glass skybridge between two enterable buildings across a street running along x: from the north building's
     * south wall {@code zNorth} to the south building's north wall {@code zSouth}, centred on {@code cx}, on the
     * skybridge level. Each building's stairs must leave {@code cx - 1 .. cx + 1} free on that storey.
     */
    static void skybridge(MapCanvas canvas, int cx, int zNorth, int zSouth, String colour) {
        int y = SiegeLayout.BRIDGE_Y;
        for (int z = zNorth; z <= zSouth; z++) {
            boolean wall = z == zNorth || z == zSouth;
            for (int x = cx - 2; x <= cx + 2; x++) {
                boolean side = x == cx - 2 || x == cx + 2;
                if (wall) {
                    if (!side) {
                        canvas.set(x, y, z, BlockSpec.of(Material.POLISHED_DEEPSLATE));
                        for (int h = 1; h <= 3; h++) {
                            canvas.clear(x, y + h, z);
                        }
                    }
                    continue;
                }
                canvas.set(x, y, z, side ? BlockSpec.of(Material.POLISHED_BLACKSTONE)
                        : x == cx ? BlockSpec.of(Material.SEA_LANTERN) : BlockSpec.of(Material.GRAY_CONCRETE));
                if (side) {
                    canvas.set(x, y + 1, z, SiegeKit.glass(colour));
                    canvas.set(x, y + 2, z, BlockSpec.of(Material.GLASS));
                    canvas.set(x, y + 3, z, BlockSpec.of(Material.GLASS));
                } else {
                    for (int h = 1; h <= 3; h++) {
                        canvas.clear(x, y + h, z);
                    }
                }
                canvas.set(x, y + 4, z, side ? SiegeKit.concrete(colour) : SiegeKit.glass(colour));
                if (x == cx) {
                    canvas.set(x, y - 1, z, SiegeKit.glow(colour));
                }
            }
        }
    }

    /**
     * Cover along a street that runs along x between {@code z1} and {@code z2} (sidewalks included): lamps on both
     * curbs, and every 7-10 blocks a car, a barrier, crates or a vending machine.
     */
    static void streetAlongX(MapCanvas canvas, Random random, int x1, int x2, int z1, int z2,
                             java.util.function.BiPredicate<Integer, Integer> keepFree) {
        String lampColour = SiegeKit.neon(random);
        for (int x = x1 + 3; x <= x2 - 3; x += 12) {
            if (!keepFree.test(x, z1) && free(canvas, x, z1)) {
                SiegeKit.lamp(canvas, x, z1, 0, 1, lampColour);
            }
            if (!keepFree.test(x + 6, z2) && free(canvas, x + 6, z2)) {
                SiegeKit.lamp(canvas, x + 6, z2, 0, -1, lampColour);
            }
        }
        int x = x1 + 2 + random.nextInt(4);
        while (x <= x2 - 6) {
            int z = z1 + 2 + random.nextInt(Math.max(1, z2 - z1 - 6));
            boolean ok = true;
            for (int dx = -1; dx <= 6 && ok; dx++) {
                for (int dz = -1; dz <= 3 && ok; dz++) {
                    ok = !keepFree.test(x + dx, z + dz) && free(canvas, x + dx, z + dz);
                }
            }
            if (ok) {
                int roll = random.nextInt(10);
                if (roll < 4) {
                    SiegeKit.car(canvas, random, x, z, true);
                } else if (roll < 6) {
                    SiegeKit.barrier(canvas, x, z, random.nextBoolean(), 3 + random.nextInt(2));
                } else if (roll < 8) {
                    SiegeKit.crates(canvas, random, x, z, G + 1);
                } else if (roll < 9) {
                    SiegeKit.planter(canvas, random, x, z, x + 3, z + 2, G + 1);
                } else {
                    SiegeKit.vending(canvas, x, z, SiegeKit.neon(random));
                    SiegeKit.vending(canvas, x + 1, z, SiegeKit.neon(random));
                }
            }
            x += 7 + random.nextInt(4);
        }
    }

    /** Cover along a street that runs along z (the same props, turned). */
    static void streetAlongZ(MapCanvas canvas, Random random, int x1, int x2, int z1, int z2,
                             java.util.function.BiPredicate<Integer, Integer> keepFree) {
        int z = z1 + 2 + random.nextInt(4);
        while (z <= z2 - 6) {
            int x = x1 + 1 + random.nextInt(Math.max(1, x2 - x1 - 4));
            boolean ok = true;
            for (int dx = -1; dx <= 3 && ok; dx++) {
                for (int dz = -1; dz <= 6 && ok; dz++) {
                    ok = !keepFree.test(x + dx, z + dz) && free(canvas, x + dx, z + dz);
                }
            }
            if (ok) {
                int roll = random.nextInt(6);
                if (roll < 2) {
                    SiegeKit.car(canvas, random, x, z, false);
                } else if (roll < 4) {
                    SiegeKit.barrier(canvas, x, z, false, 3);
                } else {
                    SiegeKit.crates(canvas, random, x, z, G + 1);
                }
            }
            z += 8 + random.nextInt(4);
        }
    }

    /** True if a street column is writable and has nothing standing on the street yet. */
    static boolean free(MapCanvas canvas, int x, int z) {
        if (!canvas.canWrite(x, z)) {
            return false;
        }
        for (int y = G + 1; y <= G + 8; y++) {
            if (canvas.get(x, y, z) != null) {
                return false;
            }
        }
        return canvas.isSolid(x, G, z);
    }

    /** A market stall: four posts, a striped canopy, a counter on the street side. */
    static void stall(MapCanvas canvas, Random random, int x, int z, int dz) {
        String a = SiegeKit.neon(random);
        for (int dx = 0; dx <= 3; dx++) {
            for (int k = 0; k <= 2; k++) {
                int sz = z + k * dz;
                canvas.set(x + dx, G + 4, sz, dx % 2 == 0 ? SiegeKit.concrete(a) : BlockSpec.of(Material.WHITE_CONCRETE));
            }
            canvas.set(x + dx, G + 1, z + 2 * dz, dx == 0 || dx == 3 ? BlockSpec.of(Material.BARREL)
                    : Blocks.slab(Material.SMOOTH_STONE_SLAB));
        }
        for (int dx : new int[] {0, 3}) {
            for (int y = G + 1; y <= G + 3; y++) {
                if (canvas.get(x + dx, y, z) == null) {
                    canvas.set(x + dx, y, z, BlockSpec.of(Material.POLISHED_BLACKSTONE_WALL));
                }
            }
        }
        canvas.set(x + 1, G + 3, z + 2 * dz, Blocks.lantern(false, true));
        canvas.set(x + 2, G + 3, z + 2 * dz, SiegeKit.glow(a));
    }
}
