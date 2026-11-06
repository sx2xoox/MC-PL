package io.github.sx2xoox.mcpl.core.map.siege;

import java.util.Random;
import java.util.function.BiPredicate;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.MapZone;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

/**
 * The data hub: the round 데이터 허브 plaza ringed by data pylons, under a square ring walkway on the skybridge
 * level (stairs down at its north-west and south-east corners, glass bridges into two enterable towers), and the
 * server farm where the defenders spawn after the front tower fell.
 */
final class SiegeHubZone implements MapZone {

    private static final int G = SiegeLayout.GROUND_Y;
    private static final int N = BlockSpec.NORTH;
    private static final int E = BlockSpec.EAST;
    private static final int S = BlockSpec.SOUTH;
    private static final int W = BlockSpec.WEST;
    private static final int CX = SiegeLayout.MIDDLE_X;
    /** The ring walkway: deck where {@code max(|dx|, |dz|)} is between these, on the skybridge level. */
    static final int RING_IN = 17;
    static final int RING_OUT = 19;

    @Override
    public String name() {
        return "hub";
    }

    @Override
    public boolean contains(int x, int z) {
        return SiegeLayout.isInterior(x, z) && x >= SiegeLayout.HUB_WEST && x < SiegeLayout.HQ_WEST;
    }

    @Override
    public void build(MapCanvas canvas) {
        Random random = canvas.random(name());
        pylons(canvas);
        ring(canvas);
        farm(canvas, random);
        SiegeKit.building(canvas, random, new SiegeKit.Building(36, -43, 49, -22, 4, true, N | S));
        SiegeKit.building(canvas, random, new SiegeKit.Building(36, 22, 49, 41, 4, true, N | S));
        SiegeStreets.skybridge(canvas, 42, -22, -RING_OUT, "CYAN");
        SiegeStreets.skybridge(canvas, 42, RING_OUT, 22, "CYAN");
        SiegeStreets.block(canvas, random, -9, -43, 24, -22, N | S, 2, 5, 0.5);
        SiegeStreets.block(canvas, random, -9, 22, 24, 41, N | S, 2, 5, 0.5);
        SiegeStreets.block(canvas, random, 51, 22, 63, 41, N | S | E, 2, 4, 0);
        SiegeStreets.block(canvas, random, -9, -88, 24, -57, S, 3, 6, 0.5);
        SiegeStreets.block(canvas, random, 36, -88, 63, -57, S, 3, 6, 0.5);
        SiegeStreets.block(canvas, random, -9, 59, 24, 88, N, 3, 6, 0.5);
        SiegeStreets.block(canvas, random, 36, 59, 63, 88, N, 3, 6, 0.5);
        BiPredicate<Integer, Integer> keep = (x, z) -> SiegeZones.keepFree(x, z) || underRing(x, z);
        SiegeStreets.streetAlongX(canvas, random, -10, 71, -SiegeLayout.MAIN_HALF, SiegeLayout.MAIN_HALF,
                SiegeWayfinding.MAIN, keep);
        SiegeStreets.streetAlongX(canvas, random, -10, 71, SiegeLayout.NORTH_Z1, SiegeLayout.NORTH_Z2,
                SiegeWayfinding.NORTH, keep);
        SiegeStreets.streetAlongZ(canvas, random, 26, 34, -88, -24, keep);
        SiegeStreets.streetAlongZ(canvas, random, 26, 34, 24, 88, keep);
        SiegeStreets.streetAlongZ(canvas, random, 64, 71, -88, 88, keep);
    }

    private static boolean underRing(int x, int z) {
        int d = Math.max(Math.abs(x - CX), Math.abs(z));
        return d >= RING_IN - 2 && d <= RING_OUT + 2 || x <= 12 && x >= 0 && z >= -21 && z <= -15
                || x >= 48 && x <= 60 && z >= 15 && z <= 21;
    }

    /** Eight data pylons around the platform (outside its radius), cyan strips, 13 high. */
    private void pylons(MapCanvas canvas) {
        for (int a = 0; a < 8; a++) {
            double angle = Math.PI / 8 + a * Math.PI / 4;
            int px = CX + (int) Math.round(13 * Math.cos(angle));
            int pz = (int) Math.round(13 * Math.sin(angle));
            // Every other pylon rises high above the roofs: the middle tower's landmark.
            SiegeKit.pylon(canvas, px - (px > CX ? 0 : 1), pz - (pz > 0 ? 0 : 1), G + 1, a % 2 == 0 ? 13 : 34,
                    "CYAN");
        }
    }

    /**
     * The square ring walkway on the skybridge level around the plaza, on columns, with glass railings, a neon strip
     * under it, and stairs down at two corners.
     */
    private void ring(MapCanvas canvas) {
        int y = SiegeLayout.BRIDGE_Y;
        for (int dx = -RING_OUT; dx <= RING_OUT; dx++) {
            for (int dz = -RING_OUT; dz <= RING_OUT; dz++) {
                int d = Math.max(Math.abs(dx), Math.abs(dz));
                if (d < RING_IN) {
                    continue;
                }
                int x = CX + dx;
                canvas.set(x, y, dz, d == 18 ? BlockSpec.of(Material.GRAY_CONCRETE)
                        : BlockSpec.of(Material.POLISHED_DEEPSLATE));
                canvas.set(x, y - 1, dz, d == 18 && Math.floorMod(dx + dz, 2) == 0 ? SiegeKit.glow("CYAN")
                        : BlockSpec.of(Material.BLACK_CONCRETE));
                if (d == RING_IN) {
                    canvas.set(x, y + 1, dz, BlockSpec.of(Material.CYAN_STAINED_GLASS_PANE));
                } else if (d == RING_OUT) {
                    canvas.set(x, y + 1, dz, BlockSpec.of(Material.BLACK_STAINED_GLASS_PANE));
                }
            }
        }
        // Columns at the corners and along the north and south sides (none in the avenue or the cross street).
        for (int sx : new int[] {-18, -9, 9, 18}) {
            for (int sz : new int[] {-18, 18}) {
                for (int yy = G + 1; yy < y - 1; yy++) {
                    canvas.set(CX + sx, yy, sz, yy % 3 == 0 ? SiegeKit.glow("CYAN")
                            : BlockSpec.of(Material.POLISHED_BLACKSTONE));
                }
            }
        }
        // Stairs: from the north side west (down to x 2), from the south side east (down to x 58).
        for (int i = 0; i < 9; i++) {
            int top = y - 1 - i;
            for (int z = -RING_OUT; z <= -RING_IN; z++) {
                int x = CX - RING_OUT - 1 - i;
                canvas.fill(x, G + 1, z, x, top - 1, z, BlockSpec.of(Material.POLISHED_BLACKSTONE_BRICKS));
                canvas.set(x, top, z, Blocks.stairs(Material.POLISHED_BLACKSTONE_BRICK_STAIRS, BlockFace.EAST));
            }
            for (int z = RING_IN; z <= RING_OUT; z++) {
                int x = CX + RING_OUT + 1 + i;
                canvas.fill(x, G + 1, z, x, top - 1, z, BlockSpec.of(Material.POLISHED_BLACKSTONE_BRICKS));
                canvas.set(x, top, z, Blocks.stairs(Material.POLISHED_BLACKSTONE_BRICK_STAIRS, BlockFace.WEST));
            }
        }
        // Open the railing where the stairs meet the deck.
        for (int z = -RING_OUT; z <= -RING_IN; z++) {
            canvas.clear(CX - RING_OUT, y + 1, z);
        }
        for (int z = RING_IN; z <= RING_OUT; z++) {
            canvas.clear(CX + RING_OUT, y + 1, z);
        }
    }

    /** The server farm (defender spawn 1): rows of glowing racks along the walls, the floor kept free. */
    private void farm(MapCanvas canvas, Random random) {
        SiegeKit.Building farm = new SiegeKit.Building(SiegeLayout.FARM_X1, SiegeLayout.FARM_Z1, SiegeLayout.FARM_X2,
                SiegeLayout.FARM_Z2, 2, false, W | S | E);
        SiegeKit.building(canvas, random, farm, new SiegeKit.Facade(Material.BLACK_CONCRETE,
                Material.POLISHED_BLACKSTONE, Material.CYAN_STAINED_GLASS, "CYAN"));
        int x1 = farm.x1() + 1;
        int x2 = farm.x2() - 1;
        for (int z = farm.z1() + 1; z < farm.z2(); z++) {
            canvas.clear((x1 + x2) / 2, G + 1, z);
            for (int x : new int[] {x1, x2}) {
                if (Math.abs(z - farm.cz()) <= 1) {
                    continue;
                }
                canvas.set(x, G + 1, z, BlockSpec.of(Material.BLACK_CONCRETE));
                canvas.set(x, G + 2, z, z % 2 == 0 ? SiegeKit.glow("CYAN") : BlockSpec.of(Material.IRON_BLOCK));
            }
        }
        for (int x = x1; x <= x2; x++) {
            if (canvas.get(x, G + 1, farm.cz()) != null && canvas.get(x, G + 1, farm.cz()).material()
                    != Material.BLACK_CONCRETE) {
                canvas.clear(x, G + 1, farm.cz());
            }
        }
        // A cooling stack on the roof.
        SiegeKit.pylon(canvas, farm.x1() + 3, farm.z1() + 3, farm.roof() + 1, 6, "CYAN");
    }
}
