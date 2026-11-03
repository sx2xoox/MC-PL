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
 * The megacorp headquarters: a forecourt with security barriers, the podium (16 high) with the 메인프레임 on its
 * roof, reached by the grand stairs (main), the parking ramp along its north side and the glass stair tower on its
 * south side, and the HQ skyscraper whose lobby is the defenders' last spawn.
 */
final class SiegeHqZone implements MapZone {

    private static final int G = SiegeLayout.GROUND_Y;
    private static final int P = SiegeLayout.PODIUM_Y;
    private static final int X1 = SiegeLayout.PODIUM_X1;
    private static final int X2 = SiegeLayout.EAST_END;
    private static final int PZ = SiegeLayout.PODIUM_Z;
    private static final int CORE = SiegeLayout.CORE_X;
    /** The ramp's landing on the podium level (x range). */
    private static final int LANDING_X1 = 126;
    private static final int LANDING_X2 = 132;
    /** The stair tower's door onto the podium (x range). */
    private static final int EXIT_X1 = 102;
    private static final int EXIT_X2 = 104;

    @Override
    public String name() {
        return "hq";
    }

    @Override
    public boolean contains(int x, int z) {
        return SiegeLayout.isInterior(x, z) && x >= SiegeLayout.HQ_WEST;
    }

    @Override
    public void build(MapCanvas canvas) {
        Random random = canvas.random(name());
        podium(canvas, random);
        grandStairs(canvas);
        ramp(canvas);
        stairTower(canvas);
        skyscraper(canvas, random);
        mainframe(canvas);
        terraces(canvas, random);
        roofCover(canvas, random);
        forecourt(canvas, random);
        SiegeStreets.block(canvas, random, 72, -88, 160, -74, BlockSpec.SOUTH, 3, 6, 0);
        SiegeStreets.block(canvas, random, 112, 74, 160, 88, BlockSpec.NORTH, 3, 6, 0);
    }

    // ------------------------------------------------------------------ podium

    private void podium(MapCanvas canvas, Random random) {
        for (int x = X1; x <= X2; x++) {
            for (int z = -PZ; z <= PZ; z++) {
                boolean face = x == X1 || Math.abs(z) == PZ;
                for (int y = G + 1; y < P; y++) {
                    canvas.set(x, y, z, face ? podiumFace(x, y, z) : SiegeKit.fill(random));
                }
                canvas.set(x, P, z, roofTile(x, z));
            }
        }
        // Parapet, open at the stairs, the ramp landing and the stair tower's door.
        for (int x = X1; x <= X2; x++) {
            for (int z = -PZ; z <= PZ; z++) {
                boolean edge = x == X1 || x == X2 || Math.abs(z) == PZ;
                if (!edge || x == X1 && Math.abs(z) <= SiegeLayout.MAIN_HALF
                        || z == -PZ && x >= LANDING_X1 && x <= LANDING_X2
                        || z == PZ && x >= EXIT_X1 - 1 && x <= EXIT_X2 + 1) {
                    continue;
                }
                canvas.set(x, P + 1, z, BlockSpec.of(Material.POLISHED_BLACKSTONE_WALL));
            }
        }
        // The corporate billboards on the west face, either side of the stairs.
        for (int sign : new int[] {-1, 1}) {
            int a1 = sign < 0 ? -48 : 18;
            SiegeKit.board(canvas, random, a1, G + 4, X1, 31, 9, false, -1, "CYAN", "LIGHT_BLUE");
        }
    }

    private static BlockSpec podiumFace(int x, int y, int z) {
        int along = x == X1 ? z : x;
        int row = y - G;
        if (row == 1 || row == 15) {
            return BlockSpec.of(Material.POLISHED_BLACKSTONE_BRICKS);
        }
        if (row == 5 || row == 10) {
            return Math.floorMod(along, 2) == 0 ? SiegeKit.glow(row == 5 ? "MAGENTA" : "CYAN")
                    : BlockSpec.of(Material.BLACK_CONCRETE);
        }
        if (row == 4 || row == 9 || row == 14 || Math.floorMod(along, 5) == 0) {
            return BlockSpec.of(Material.POLISHED_DEEPSLATE);
        }
        return BlockSpec.of(Material.TINTED_GLASS);
    }

    private static BlockSpec roofTile(int x, int z) {
        double d = Math.hypot(x - CORE, z);
        int ring = (int) Math.round(d);
        if (ring == 11 || ring == 16) {
            return BlockSpec.of(Material.CYAN_CONCRETE);
        }
        if (ring <= 10) {
            return ring % 3 == 0 ? BlockSpec.of(Material.POLISHED_DEEPSLATE) : BlockSpec.of(Material.WHITE_CONCRETE);
        }
        if (Math.floorMod(x, 8) == 0 || Math.floorMod(z, 8) == 0) {
            return BlockSpec.of(Material.POLISHED_BLACKSTONE);
        }
        return Math.floorMod(x + z, 2) == 0 ? BlockSpec.of(Material.GRAY_CONCRETE)
                : BlockSpec.of(Material.LIGHT_GRAY_CONCRETE);
    }

    // ------------------------------------------------------------------ the three ways up

    /** The grand stairs (main lane): one step per x from x 76 to the podium, glowing cheek walls. */
    private void grandStairs(MapCanvas canvas) {
        int h = SiegeLayout.MAIN_HALF;
        for (int i = 0; i < X1 - SiegeLayout.STAIRS_X1; i++) {
            int x = SiegeLayout.STAIRS_X1 + i;
            int top = G + 1 + i;
            for (int z = -h; z <= h; z++) {
                if (top > G + 1) {
                    canvas.fill(x, G + 1, z, x, top - 1, z, BlockSpec.of(Material.POLISHED_BLACKSTONE));
                }
                canvas.set(x, top, z, Blocks.stairs(Math.abs(z) == 4 ? Material.QUARTZ_STAIRS
                        : Material.POLISHED_DEEPSLATE_STAIRS, BlockFace.EAST));
            }
            for (int z : new int[] {-h - 1, h + 1}) {
                canvas.fill(x, G + 1, z, x, top, z, BlockSpec.of(Material.POLISHED_BLACKSTONE_BRICKS));
                canvas.set(x, top + 1, z, i % 2 == 0 ? SiegeKit.glow("CYAN") : BlockSpec.of(Material.BLACK_CONCRETE));
            }
        }
    }

    /** The parking ramp (north lane): half a block up per block along the podium's north side, to a landing. */
    private void ramp(MapCanvas canvas) {
        int z1 = SiegeLayout.RAMP_Z1;
        int z2 = -PZ - 1;
        for (int x = SiegeLayout.RAMP_X1; x <= LANDING_X2; x++) {
            int half = Math.min(2 * (P - G), x - SiegeLayout.RAMP_X1 + 1);
            int full = G + half / 2;
            boolean slab = half % 2 == 1;
            int surface = slab ? full + 1 : full;
            for (int z = z1; z <= z2; z++) {
                if (full > G) {
                    canvas.fill(x, G + 1, z, x, full, z, z == z1 && Math.floorMod(x, 3) == 0
                            ? BlockSpec.of(Material.POLISHED_BLACKSTONE_BRICKS) : BlockSpec.of(Material.GRAY_CONCRETE));
                }
                if (slab) {
                    canvas.set(x, full + 1, z, Blocks.slab(Material.SMOOTH_STONE_SLAB));
                } else if (z == (z1 + z2) / 2 && Math.floorMod(x, 4) == 0) {
                    canvas.set(x, full, z, BlockSpec.of(Material.YELLOW_CONCRETE));
                }
            }
            // Parapet on the outer edge, a neon strip on its face.
            canvas.set(x, surface + 1, z1 - 1 + 1, BlockSpec.of(Material.POLISHED_BLACKSTONE_WALL));
            if (surface > G + 2) {
                canvas.set(x, surface - 1, z1, SiegeKit.glow("YELLOW"));
            }
        }
        for (int z = z1; z <= z2; z++) {
            canvas.set(LANDING_X2 + 1, P, z, BlockSpec.of(Material.POLISHED_BLACKSTONE));
            canvas.fill(LANDING_X2 + 1, G + 1, z, LANDING_X2 + 1, P - 1, z, BlockSpec.of(Material.GRAY_CONCRETE));
            canvas.set(LANDING_X2 + 1, P + 1, z, BlockSpec.of(Material.POLISHED_BLACKSTONE_WALL));
        }
    }

    /**
     * The glass stair tower (south lane): a stair winding up three walls of the tower from the door on its south
     * side to a landing at podium level, out through the north wall onto the podium.
     */
    private void stairTower(MapCanvas canvas) {
        int x1 = SiegeLayout.STAIR_TOWER_X1;
        int x2 = SiegeLayout.STAIR_TOWER_X2;
        int z1 = SiegeLayout.STAIR_TOWER_Z1;
        int z2 = SiegeLayout.STAIR_TOWER_Z2;
        int roof = P + 6;
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                boolean wall = x == x1 || x == x2 || z == z1 || z == z2;
                boolean corner = (x == x1 || x == x2) && (z == z1 || z == z2);
                if (wall) {
                    for (int y = G + 1; y < roof; y++) {
                        canvas.set(x, y, z, corner ? (y % 3 == 0 ? SiegeKit.glow("LIME")
                                : BlockSpec.of(Material.POLISHED_BLACKSTONE))
                                : y % 5 == 0 ? BlockSpec.of(Material.POLISHED_DEEPSLATE)
                                : BlockSpec.of(Material.LIME_STAINED_GLASS_PANE));
                    }
                }
                canvas.set(x, roof, z, wall ? BlockSpec.of(Material.LIME_CONCRETE)
                        : BlockSpec.of(Material.BLACK_STAINED_GLASS));
            }
        }
        // The steps: west along the south wall, north along the west wall, east along the north wall.
        int k = 0;
        for (int x = x2 - 1; x >= x1 + 1; x--) {
            step(canvas, x, z2 - 1, k++, BlockFace.WEST);
        }
        for (int z = z2 - 2; z >= z1 + 1; z--) {
            step(canvas, x1 + 1, z, k++, BlockFace.NORTH);
        }
        for (int x = x1 + 2; k < P - G; x++) {
            step(canvas, x, z1 + 1, k++, BlockFace.EAST);
        }
        for (int x = x1 + 2 + 3; x <= x2 - 1; x++) {
            canvas.fill(x, G + 1, z1 + 1, x, P, z1 + 1, BlockSpec.of(Material.POLISHED_BLACKSTONE_BRICKS));
        }
        // Doors: south at the foot of the stair, north at the landing.
        for (int x = x2 - 3; x <= x2 - 1; x++) {
            for (int y = G + 1; y <= G + 3; y++) {
                canvas.clear(x, y, z2);
            }
        }
        for (int x = EXIT_X1; x <= EXIT_X2; x++) {
            for (int y = P + 1; y <= P + 3; y++) {
                canvas.clear(x, y, z1);
            }
            canvas.set(x, P, z1, BlockSpec.of(Material.POLISHED_BLACKSTONE));
        }
    }

    private static void step(MapCanvas canvas, int x, int z, int k, BlockFace facing) {
        canvas.set(x, G + 1 + k, z, Blocks.stairs(Material.POLISHED_BLACKSTONE_BRICK_STAIRS, facing));
    }

    /** The HQ skyscraper: a glass tower with neon corners, an open lobby (defender spawn 2) facing the core. */
    private void skyscraper(MapCanvas canvas, Random random) {
        int x1 = SiegeLayout.SKY_X1;
        int x2 = SiegeLayout.SKY_X2;
        int zz = SiegeLayout.SKY_Z;
        int top = P + 42;
        int lobby = P + 6;
        for (int x = x1; x <= x2; x++) {
            for (int z = -zz; z <= zz; z++) {
                boolean shell = x == x1 || x == x2 || Math.abs(z) == zz;
                boolean corner = (x == x1 || x == x2) && Math.abs(z) == zz;
                if (shell) {
                    int along = x == x1 || x == x2 ? z : x;
                    for (int y = P + 1; y <= top; y++) {
                        BlockSpec spec;
                        if (corner) {
                            spec = y % 2 == 0 ? SiegeKit.glow("MAGENTA") : BlockSpec.of(Material.BLACK_CONCRETE);
                        } else if (Math.floorMod(y - P, 5) == 0) {
                            spec = BlockSpec.of(Material.POLISHED_BLACKSTONE);
                        } else if (Math.floorMod(along, 4) == 0) {
                            spec = BlockSpec.of(Material.POLISHED_DEEPSLATE);
                        } else {
                            spec = random.nextInt(12) == 0 ? BlockSpec.of(Material.SEA_LANTERN)
                                    : BlockSpec.of(y > top - 6 ? Material.CYAN_STAINED_GLASS : Material.TINTED_GLASS);
                        }
                        canvas.set(x, y, z, spec);
                    }
                } else {
                    canvas.set(x, lobby, z, Math.floorMod(x, 4) == 0 && Math.floorMod(z, 4) == 0
                            ? BlockSpec.of(Material.SEA_LANTERN) : BlockSpec.of(Material.POLISHED_DEEPSLATE));
                    canvas.set(x, top, z, BlockSpec.of(Material.POLISHED_BLACKSTONE));
                    canvas.set(x, P, z, Math.floorMod(x + z, 2) == 0 ? BlockSpec.of(Material.WHITE_CONCRETE)
                            : BlockSpec.of(Material.POLISHED_DEEPSLATE));
                }
                canvas.set(x, top + 1, z, shell ? BlockSpec.of(Material.POLISHED_BLACKSTONE_WALL) : BlockSpec.AIR);
            }
        }
        // The lobby: open towards the core between columns.
        for (int z = -10; z <= 10; z++) {
            if (Math.floorMod(z, 5) == 0) {
                continue;
            }
            for (int y = P + 1; y < lobby; y++) {
                canvas.clear(x1, y, z);
            }
        }
        // Reception desk at the back.
        for (int z = -5; z <= 5; z++) {
            canvas.set(x2 - 4, P + 1, z, Math.abs(z) == 5 ? SiegeKit.glow("MAGENTA")
                    : BlockSpec.of(Material.WHITE_CONCRETE));
        }
        // The logo board high on the west face and a spire.
        SiegeKit.board(canvas, random, -12, P + 14, x1, 25, 11, false, -1, "MAGENTA", "PURPLE");
        int sx = (x1 + x2) / 2;
        for (int y = top + 1; y < SiegeLayout.MAX_Y; y++) {
            canvas.set(sx, y, 0, BlockSpec.of(Material.LIGHTNING_ROD));
        }
        canvas.set(sx, SiegeLayout.MAX_Y, 0, BlockSpec.of(Material.END_ROD));
    }

    /** Around the core: four arcs of glowing server racks and four tall pylons, open towards the ways in. */
    private void mainframe(MapCanvas canvas) {
        for (int a = 0; a < 4; a++) {
            double centre = Math.PI / 4 + a * Math.PI / 2;
            for (double t = -0.35; t <= 0.35; t += 0.04) {
                int x = CORE + (int) Math.round(13 * Math.cos(centre + t));
                int z = (int) Math.round(13 * Math.sin(centre + t));
                canvas.set(x, P + 1, z, BlockSpec.of(Material.BLACK_CONCRETE));
                canvas.set(x, P + 2, z, Math.floorMod(x + z, 2) == 0 ? SiegeKit.glow("CYAN")
                        : BlockSpec.of(Material.IRON_BLOCK));
            }
            int px = CORE + (int) Math.round(17 * Math.cos(centre));
            int pz = (int) Math.round(17 * Math.sin(centre));
            SiegeKit.pylon(canvas, px, pz, P + 1, 18, a % 2 == 0 ? "MAGENTA" : "CYAN");
        }
    }

    /**
     * Two raised terraces on the podium, north and south of the way to the core: three blocks high, steps up on
     * three sides, a glowing skylight in the middle and planters along the edge facing the core.
     */
    private void terraces(MapCanvas canvas, Random random) {
        for (int sign : new int[] {-1, 1}) {
            int x1 = 100;
            int x2 = 113;
            int za = sign * 27;
            int zb = sign * 40;
            int z1 = Math.min(za, zb);
            int z2 = Math.max(za, zb);
            int top = P + 3;
            for (int x = x1; x <= x2; x++) {
                for (int z = z1; z <= z2; z++) {
                    boolean edge = x == x1 || x == x2 || z == z1 || z == z2;
                    canvas.fill(x, P + 1, z, x, top - 1, z, edge ? BlockSpec.of(Material.POLISHED_BLACKSTONE_BRICKS)
                            : SiegeKit.fill(random));
                    boolean skylight = x >= x1 + 4 && x <= x2 - 4 && z >= z1 + 4 && z <= z2 - 4;
                    canvas.set(x, top, z, skylight ? (Math.floorMod(x + z, 2) == 0 ? SiegeKit.glow("MAGENTA")
                            : BlockSpec.of(Material.MAGENTA_STAINED_GLASS)) : edge
                            ? BlockSpec.of(Material.POLISHED_DEEPSLATE) : BlockSpec.of(Material.GRAY_CONCRETE));
                }
            }
            // Steps up: on the west and east ends and on the side facing the core path.
            int inner = sign < 0 ? z2 : z1;
            for (int i = 1; i <= 3; i++) {
                for (int z = z1 + 4; z <= z2 - 4; z++) {
                    stepUp(canvas, x1 - i, top - i, z, BlockFace.EAST);
                    stepUp(canvas, x2 + i, top - i, z, BlockFace.WEST);
                }
                for (int x = x1 + 4; x <= x1 + 7; x++) {
                    stepUp(canvas, x, top - i, inner - sign * i, sign < 0 ? BlockFace.NORTH : BlockFace.SOUTH);
                }
            }
            for (int x = x1 + 9; x <= x2 - 1; x += 3) {
                canvas.set(x, top + 1, inner, BlockSpec.of(Material.MOSS_BLOCK));
                canvas.set(x, top + 2, inner, BlockSpec.of(Material.FLOWERING_AZALEA));
            }
        }
    }

    private static void stepUp(MapCanvas canvas, int x, int y, int z, BlockFace facing) {
        canvas.fill(x, P + 1, z, x, y - 1, z, BlockSpec.of(Material.POLISHED_BLACKSTONE));
        canvas.set(x, y, z, Blocks.stairs(Material.POLISHED_BLACKSTONE_BRICK_STAIRS, facing));
    }

    /** Cover on the podium roof: planters, vents and crates on a jittered grid, off the ways in. */
    private void roofCover(MapCanvas canvas, Random random) {
        for (int x = X1 + 6; x <= SiegeLayout.SKY_X1 - 4; x += 9) {
            for (int z = -PZ + 6; z <= PZ - 6; z += 9) {
                int px = x + random.nextInt(3);
                int pz = z + random.nextInt(3);
                if (SiegeLayout.near(px, pz, CORE, 0, 20) || px <= X1 + 8 && Math.abs(pz) <= 14
                        || px >= 94 && px <= 118 && Math.abs(pz) >= 22 && Math.abs(pz) <= 45
                        || px >= 96 && px <= 106 && Math.abs(pz) <= 6 || Math.abs(pz) <= 12 && px >= 128) {
                    continue;
                }
                int roll = random.nextInt(4);
                if (roll == 0) {
                    SiegeKit.planter(canvas, random, px, pz, px + 2, pz + 2, P + 1);
                } else if (roll == 1) {
                    canvas.fill(px, P + 1, pz, px + 1, P + 1, pz + 1, BlockSpec.of(Material.LIGHT_GRAY_CONCRETE));
                    canvas.set(px, P + 2, pz, BlockSpec.of(Material.IRON_BARS));
                } else if (roll == 2) {
                    SiegeKit.crates(canvas, random, px, pz, P + 1);
                } else {
                    for (int i = 0; i < 4; i++) {
                        canvas.set(px + i, P + 1, pz, i % 2 == 0 ? BlockSpec.of(Material.YELLOW_CONCRETE)
                                : BlockSpec.of(Material.BLACK_CONCRETE));
                    }
                }
            }
        }
    }

    /** The forecourt: security barriers before the stairs, lamps, planters, parked cars. */
    private void forecourt(MapCanvas canvas, Random random) {
        for (int z = -9; z <= 9; z += 6) {
            SiegeKit.barrier(canvas, 72, z - 1, false, 3);
        }
        for (int sign : new int[] {-1, 1}) {
            SiegeKit.planter(canvas, random, 80, sign * 16 - 1, 84, sign * 16 + 1, G + 1);
            SiegeKit.lamp(canvas, 74, sign * 13, 0, -sign, "CYAN");
            SiegeKit.lamp(canvas, 88, sign * 13, 0, -sign, "CYAN");
        }
        BiPredicate<Integer, Integer> keep = (x, z) -> SiegeZones.keepFree(x, z) || x >= X1 - 1
                && Math.abs(z) <= PZ + 10 || z <= SiegeLayout.RAMP_Z1 + 0 && x >= SiegeLayout.RAMP_X1 - 2
                || Math.abs(z) <= 18 && x >= 70;
        SiegeStreets.streetAlongZ(canvas, random, 73, 90, -73, 88, keep);
        SiegeStreets.streetAlongX(canvas, random, 108, 160, 62, 72, keep);
        SiegeStreets.streetAlongX(canvas, random, 136, 160, -73, -62, keep);
    }
}
