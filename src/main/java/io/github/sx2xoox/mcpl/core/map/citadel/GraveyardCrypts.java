package io.github.sx2xoox.mcpl.core.map.citadel;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import io.github.sx2xoox.mcpl.core.map.citadel.GraveyardBuilder.Palette;
import io.github.sx2xoox.mcpl.core.map.citadel.GraveyardBuilder.Use;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

/**
 * The graveyard's three crypts, each with two ways in and out:
 * <ul>
 *   <li>the black mausoleum (west): a deepslate tomb house with a portico, a south and an east door, a roof terrace
 *       reached by a ladder and by its broken north-west corner;</li>
 *   <li>the sunken catacomb (east): a pillared hall under the ground between two open stairwells (the north one
 *       inside the ruined chapel, see {@link GraveyardChapel}), lit through a collapsed skylight and an iron grate;</li>
 *   <li>the barrow (north-west): a grassy burial mound the back lane passes through in a stone-lined tunnel.</li>
 * </ul>
 * It also shapes the district's terrain: the barrow and a low knoll with dead trees east of the catacomb.
 */
final class GraveyardCrypts {

    private static final int G = GraveyardBuilder.GROUND;

    // Mausoleum: walls x -30..-22, z -74..-66, portico to z -64.
    private static final int MX1 = -30;
    private static final int MX2 = -22;
    private static final int MZ_NORTH = -74;
    private static final int MZ_SOUTH = -66;
    /** The mausoleum stands on a one-block plinth: its floor (first air) is two above the ground. */
    private static final int M_FLOOR = G + 2;
    private static final int M_WALL_TOP = M_FLOOR + 4;

    // Catacomb hall: inside x 22..28, z -77..-63, floor block y 58.
    private static final int CX1 = 22;
    private static final int CX2 = 28;
    private static final int CZ_NORTH = -77;
    private static final int CZ_SOUTH = -63;
    private static final int C_FLOOR = G - 6;
    private static final int C_CEILING = G - 1;

    // Barrow and knoll.
    static final int BARROW_X = -21;
    static final int BARROW_Z = -86;
    private static final int BX = BARROW_X;
    private static final int BZ = BARROW_Z;
    private static final int KX = 33;
    private static final int KZ = -70;

    private static final Palette MAUSOLEUM_WALL = Palette.of(Material.DEEPSLATE_BRICKS, 6,
            Material.CRACKED_DEEPSLATE_BRICKS, 3, Material.DEEPSLATE_TILES, 1, Material.POLISHED_DEEPSLATE, 1);
    private static final Palette CATACOMB_WALL = Palette.of(Material.DEEPSLATE_BRICKS, 4,
            Material.CRACKED_DEEPSLATE_BRICKS, 2, Material.DEEPSLATE_TILES, 3, Material.CRACKED_DEEPSLATE_TILES, 2,
            Material.COBBLED_DEEPSLATE, 1, Material.MOSSY_STONE_BRICKS, 1);
    private static final Palette TUNNEL_WALL = Palette.of(Material.MOSSY_STONE_BRICKS, 3, Material.STONE_BRICKS, 2,
            Material.MOSSY_COBBLESTONE, 2, Material.COBBLESTONE, 1, Material.TUFF, 1);

    private final GraveyardBuilder b;
    private int[][] barrow;

    GraveyardCrypts(GraveyardBuilder builder) {
        this.b = builder;
    }

    void reserve() {
        b.mark(MX1 - 1, MZ_NORTH - 1, MX2 + 1, MZ_SOUTH + 3, Use.STRUCTURE);
        // Catacomb stairwells with their rims, and the skylights.
        b.mark(CX1 + 1, CZ_SOUTH + 1, CX2 - 1, CZ_SOUTH + 7, Use.STRUCTURE);
        b.mark(CX1 + 1, CZ_NORTH - 7, CX2 - 1, CZ_NORTH - 1, Use.STRUCTURE);
        b.mark(CX1 + 1, -73, CX2 - 1, -69, Use.STRUCTURE);
        b.mark(CX1 + 2, -67, CX2 - 2, -65, Use.STRUCTURE);
    }

    // ------------------------------------------------------------------ terrain

    /** Shapes the barrow and the knoll (their slopes never rise more than a block per block). */
    void terrain() {
        barrow = mound(BX, BZ, 11.5, 6.5, 5);
        int[][] knoll = mound(KX, KZ, 6.5, 7, 3);
        raise(BX, BZ, barrow);
        raise(KX, KZ, knoll);
    }

    private static final int MOUND_R = 14;

    /**
     * A height field (index [dx + R][dz + R]) of a mound with a rounded flat top, relaxed so neighbours differ by at
     * most 1 (its flanks are one-block steps all around).
     */
    private int[][] mound(int cx, int cz, double rx, double rz, int height) {
        int size = MOUND_R * 2 + 1;
        int[][] h = new int[size][size];
        for (int dx = -MOUND_R; dx <= MOUND_R; dx++) {
            for (int dz = -MOUND_R; dz <= MOUND_R; dz++) {
                double d = Math.sqrt(dx * dx / (rx * rx) + dz * dz / (rz * rz));
                double lump = (b.noise(cx + dx, cz + dz, 4, 61) - 0.5) * 1.4;
                double raw = (height + 2) * (1 - d * d) + lump;
                int x = cx + dx;
                int z = cz + dz;
                boolean ok = b.inside(x, z) && GraveyardBuilder.margin(x, z) >= 3;
                h[dx + MOUND_R][dz + MOUND_R] = ok ? Math.max(0, Math.min(height, (int) Math.floor(raw))) : 0;
            }
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < size; i++) {
                for (int j = 0; j < size; j++) {
                    int lowest = Integer.MAX_VALUE;
                    lowest = Math.min(lowest, i > 0 ? h[i - 1][j] : 0);
                    lowest = Math.min(lowest, i < size - 1 ? h[i + 1][j] : 0);
                    lowest = Math.min(lowest, j > 0 ? h[i][j - 1] : 0);
                    lowest = Math.min(lowest, j < size - 1 ? h[i][j + 1] : 0);
                    if (h[i][j] > lowest + 1) {
                        h[i][j] = lowest + 1;
                        changed = true;
                    }
                }
            }
        }
        return h;
    }

    /** Raises the ground by a height field; paths (the tunnel of the back lane) and buildings keep their ground. */
    private void raise(int cx, int cz, int[][] h) {
        for (int dx = -MOUND_R; dx <= MOUND_R; dx++) {
            for (int dz = -MOUND_R; dz <= MOUND_R; dz++) {
                int x = cx + dx;
                int z = cz + dz;
                int height = h[dx + MOUND_R][dz + MOUND_R];
                if (height <= 0 || b.use(x, z) == Use.STRUCTURE || b.use(x, z) == Use.PATH) {
                    continue;
                }
                b.raiseGround(x, z, G + height, BlockSpec.of(Material.GRASS_BLOCK));
            }
        }
    }

    // ------------------------------------------------------------------ build

    void build() {
        mausoleum();
        catacomb();
        barrowTunnel();
        barrowTop();
    }

    // ------------------------------------------------------------------ mausoleum

    private boolean deckMissing(int x, int z) {
        return z <= MZ_NORTH + 2 && x <= MX1 + 3 || z == MZ_NORTH && x <= MX1 + 4 || x == MX1 && z <= MZ_NORTH + 3;
    }

    private int mausoleumWallHeight(int x, int z) {
        int full = M_WALL_TOP - M_FLOOR + 1;
        if (z == MZ_NORTH && x <= MX1 + 4) {
            return Math.min(full, 1 + (x - MX1));
        }
        if (x == MX1 && z <= MZ_NORTH + 4) {
            return Math.min(full, 1 + (z - MZ_NORTH));
        }
        return full;
    }

    private void mausoleum() {
        // Plinth (one step up from all sides) and front steps.
        for (int x = MX1 - 1; x <= MX2 + 1; x++) {
            for (int z = MZ_NORTH - 1; z <= MZ_SOUTH + 2; z++) {
                boolean rim = x == MX1 - 1 || x == MX2 + 1 || z == MZ_NORTH - 1 || z == MZ_SOUTH + 2;
                b.set(x, G + 1, z, rim ? BlockSpec.of(b.chance(0.25) ? Material.CRACKED_DEEPSLATE_TILES
                        : Material.POLISHED_DEEPSLATE) : BlockSpec.of(b.chance(0.3)
                        ? Material.CRACKED_DEEPSLATE_TILES : Material.DEEPSLATE_TILES));
            }
        }
        for (int x = MX1 + 2; x <= MX2 - 2; x++) {
            b.set(x, G + 1, MZ_SOUTH + 3, Blocks.stairs(Material.POLISHED_DEEPSLATE_STAIRS, BlockFace.NORTH));
        }
        int floor = M_FLOOR;
        // Walls.
        for (int x = MX1; x <= MX2; x++) {
            for (int z = MZ_NORTH; z <= MZ_SOUTH; z++) {
                if (x != MX1 && x != MX2 && z != MZ_NORTH && z != MZ_SOUTH) {
                    continue;
                }
                int h = mausoleumWallHeight(x, z);
                boolean corner = (x == MX1 || x == MX2) && (z == MZ_NORTH || z == MZ_SOUTH);
                boolean middle = x == (MX1 + MX2) / 2 || z == (MZ_NORTH + MZ_SOUTH) / 2;
                for (int i = 0; i < h; i++) {
                    BlockSpec spec = corner || middle ? BlockSpec.of(Material.POLISHED_DEEPSLATE)
                            : i == 3 ? BlockSpec.of(Material.CHISELED_DEEPSLATE) : MAUSOLEUM_WALL.pick(b.random);
                    b.set(x, floor + i, z, spec);
                }
            }
        }
        int top = M_WALL_TOP;
        // Doors: south (3 wide, with an arch) and east (2 wide).
        b.clearBox(MX1 + 3, floor, MZ_SOUTH, MX2 - 3, floor + 3, MZ_SOUTH);
        b.set(MX1 + 3, floor + 3, MZ_SOUTH, Blocks.stairsTop(Material.DEEPSLATE_BRICK_STAIRS, BlockFace.WEST));
        b.set(MX2 - 3, floor + 3, MZ_SOUTH, Blocks.stairsTop(Material.DEEPSLATE_BRICK_STAIRS, BlockFace.EAST));
        b.clearBox(MX2, floor, -72, MX2, floor + 2, -71);
        // Roof deck over the house and the portico, with a raised middle and a lip of stairs.
        int deck = top + 1;
        for (int x = MX1; x <= MX2; x++) {
            for (int z = MZ_NORTH; z <= MZ_SOUTH + 2; z++) {
                if (deckMissing(x, z)) {
                    continue;
                }
                b.set(x, deck, z, BlockSpec.of(b.chance(0.2) ? Material.CRACKED_DEEPSLATE_TILES
                        : Material.DEEPSLATE_TILES));
            }
        }
        for (int x = MX1 + 2; x <= MX2 - 2; x++) {
            for (int z = MZ_NORTH + 2; z <= MZ_SOUTH - 2; z++) {
                boolean edge = x == MX1 + 2 || x == MX2 - 2 || z == MZ_NORTH + 2 || z == MZ_SOUTH - 2;
                if (!deckMissing(x, z)) {
                    b.set(x, deck + 1, z, edge ? Blocks.slab(Material.POLISHED_DEEPSLATE_SLAB)
                            : BlockSpec.of(Material.POLISHED_DEEPSLATE));
                }
            }
        }
        int mx = (MX1 + MX2) / 2;
        int mz = (MZ_NORTH + MZ_SOUTH) / 2;
        b.set(mx, deck + 2, mz, BlockSpec.of(Material.CHISELED_DEEPSLATE));
        for (int x = MX1 - 1; x <= MX2 + 1; x++) {
            lip(x, deck, MZ_SOUTH + 3, BlockFace.NORTH);
            lip(x, deck, MZ_NORTH - 1, BlockFace.SOUTH);
        }
        for (int z = MZ_NORTH; z <= MZ_SOUTH + 2; z++) {
            lip(MX1 - 1, deck, z, BlockFace.EAST);
            lip(MX2 + 1, deck, z, BlockFace.WEST);
        }
        b.set(MX2, deck + 1, MZ_SOUTH + 2, Blocks.lantern(true, false));
        b.set(MX1 + 1, deck + 1, MZ_SOUTH + 2, Blocks.lantern(true, false));
        // Portico columns.
        for (int x : new int[] {MX1 + 2, MX2 - 2}) {
            b.set(x, floor, MZ_SOUTH + 2, BlockSpec.of(Material.POLISHED_DEEPSLATE));
            for (int y = floor + 1; y <= top; y++) {
                b.set(x, y, MZ_SOUTH + 2, Blocks.log(Material.POLISHED_BASALT, Axis.Y));
            }
        }
        // Ladder up the east wall to the roof.
        for (int y = floor; y <= deck; y++) {
            b.set(MX2 + 1, y, -68, BlockSpec.of(Material.LADDER).facing(BlockFace.EAST));
        }
        // Inside: a sarcophagus, bones, candles, cobwebs, a hanging lantern and the fallen corner.
        for (int z = mz - 1; z <= mz + 1; z++) {
            b.set(mx, floor, z, BlockSpec.of(Material.POLISHED_BLACKSTONE_BRICKS));
        }
        b.set(mx, floor + 1, mz - 1, BlockSpec.of(Material.SKELETON_SKULL));
        b.set(mx, floor + 1, mz + 1, BlockSpec.of(Material.CANDLE).lit(true));
        b.set(MX2 - 1, floor, MZ_NORTH + 1, Blocks.log(Material.BONE_BLOCK, Axis.Y));
        b.set(MX2 - 1, floor + 1, MZ_NORTH + 1, BlockSpec.of(Material.SKELETON_SKULL));
        b.set(MX2 - 2, floor, MZ_NORTH + 1, Blocks.log(Material.BONE_BLOCK, Axis.X));
        b.set(MX2 - 1, floor, MZ_NORTH + 2, BlockSpec.of(Material.WHITE_CANDLE).lit(true));
        b.set(MX1 + 1, floor, MZ_SOUTH - 1, BlockSpec.of(Material.CANDLE).lit(true));
        b.set(MX2 - 1, top, MZ_SOUTH - 1, BlockSpec.of(Material.COBWEB));
        b.set(MX2 - 1, top, MZ_NORTH + 1, BlockSpec.of(Material.COBWEB));
        b.set(MX1 + 1, top, MZ_SOUTH - 1, BlockSpec.of(Material.COBWEB));
        b.hangingLantern(mx - 2, deck, mz, 1, true);
        b.rubble(MX1 + 2, MZ_NORTH + 2, G + 1, 1.7, GraveyardBuilder.RUBBLE);
        // Rubble that fell outside the broken corner.
        b.rubble(MX1 - 2, MZ_NORTH - 1, G, 1.5, GraveyardBuilder.RUBBLE);
    }

    private void lip(int x, int y, int z, BlockFace facing) {
        if (b.isAir(x, y, z) && !(x == MX2 + 1 && z == -68) && !deckMissing(x + facing.getModX(),
                z + facing.getModZ()) && b.chance(0.85)) {
            b.set(x, y, z, Blocks.stairsTop(Material.DEEPSLATE_TILE_STAIRS, facing));
        }
    }

    // ------------------------------------------------------------------ catacomb

    private void catacomb() {
        // Carve the hall and line it.
        for (int x = CX1 - 1; x <= CX2 + 1; x++) {
            for (int z = CZ_NORTH - 1; z <= CZ_SOUTH + 1; z++) {
                boolean wall = x == CX1 - 1 || x == CX2 + 1 || z == CZ_NORTH - 1 || z == CZ_SOUTH + 1;
                b.set(x, C_FLOOR, z, wall ? CATACOMB_WALL.pick(b.random)
                        : BlockSpec.of(b.chance(0.3) ? Material.CRACKED_DEEPSLATE_TILES
                        : b.chance(0.4) ? Material.POLISHED_DEEPSLATE : Material.DEEPSLATE_TILES));
                for (int y = C_FLOOR + 1; y < C_CEILING; y++) {
                    b.set(x, y, z, wall ? CATACOMB_WALL.pick(b.random) : BlockSpec.AIR);
                }
                b.set(x, C_CEILING, z, BlockSpec.of(b.chance(0.3) ? Material.COBBLED_DEEPSLATE
                        : Material.DEEPSLATE_TILES));
            }
        }
        // Pillars with arches.
        for (int z = CZ_NORTH + 3; z <= CZ_SOUTH - 3; z += 4) {
            for (int x : new int[] {CX1 + 1, CX2 - 1}) {
                for (int y = C_FLOOR + 1; y < C_CEILING; y++) {
                    b.set(x, y, z, BlockSpec.of(Material.POLISHED_DEEPSLATE));
                }
                b.set(x, C_CEILING - 1, z - 1, Blocks.stairsTop(Material.DEEPSLATE_TILE_STAIRS, BlockFace.SOUTH));
                b.set(x, C_CEILING - 1, z + 1, Blocks.stairsTop(Material.DEEPSLATE_TILE_STAIRS, BlockFace.NORTH));
            }
        }
        // Burial niches in the long walls with bones, skulls and candles.
        for (int z = CZ_NORTH + 1; z <= CZ_SOUTH - 1; z += 4) {
            niche(CX1 - 1, z, -1);
            niche(CX2 + 1, z, 1);
        }
        // Sarcophagi at the north end, candles and skulls on the floor.
        for (int x : new int[] {CX1, CX2}) {
            b.set(x, C_FLOOR + 1, CZ_NORTH, BlockSpec.of(Material.POLISHED_BLACKSTONE_BRICKS));
            b.set(x, C_FLOOR + 1, CZ_NORTH + 1, BlockSpec.of(Material.POLISHED_BLACKSTONE_BRICKS));
            b.set(x, C_FLOOR + 2, CZ_NORTH + 1, BlockSpec.of(Material.CANDLE).lit(true));
        }
        b.set(CX1, C_FLOOR + 1, -68, BlockSpec.of(Material.SKELETON_SKULL));
        b.set(CX2, C_FLOOR + 1, -72, BlockSpec.of(Material.BLACK_CANDLE).lit(true));
        b.set(CX1, C_FLOOR + 1, -75, BlockSpec.of(Material.WHITE_CANDLE).lit(true));
        b.set(CX2, C_FLOOR + 1, -64, BlockSpec.of(Material.CANDLE).lit(true));
        // Cobwebs in the corners under the ceiling.
        b.set(CX1, C_CEILING - 1, CZ_NORTH, BlockSpec.of(Material.COBWEB));
        b.set(CX2, C_CEILING - 1, CZ_NORTH, BlockSpec.of(Material.COBWEB));
        b.set(CX2, C_CEILING - 1, CZ_SOUTH, BlockSpec.of(Material.COBWEB));
        // Soul lanterns along the middle.
        for (int z : new int[] {-75, -68, -64}) {
            b.set(25, C_CEILING - 1, z, Blocks.lantern(true, true));
        }
        stairwell(CZ_SOUTH + 1, 1, BlockFace.SOUTH);
        stairwell(CZ_NORTH - 1, -1, BlockFace.NORTH);
        skylights();
        surface();
    }

    private void niche(int x, int z, int out) {
        int nx = x + out;
        b.clearBox(x, C_FLOOR + 1, z, x, C_FLOOR + 2, z);
        b.set(nx, C_FLOOR + 1, z, CATACOMB_WALL.pick(b.random));
        b.set(nx, C_FLOOR + 2, z, CATACOMB_WALL.pick(b.random));
        b.set(x, C_FLOOR + 1, z, Blocks.log(Material.BONE_BLOCK, Axis.Z));
        b.set(x, C_FLOOR + 2, z, b.chance(0.6) ? BlockSpec.of(Material.SKELETON_SKULL)
                : BlockSpec.of(Material.CANDLE).lit(true));
    }

    /** Open stairs from the hall's end wall at {@code zWall} up to the ground, going {@code dir} along z. */
    private void stairwell(int zWall, int dir, BlockFace up) {
        int steps = G - C_FLOOR;
        for (int i = 0; i < steps; i++) {
            int z = zWall + dir * i;
            int stepY = C_FLOOR + 1 + i;
            for (int x = CX1 + 2; x <= CX2 - 2; x++) {
                for (int y = C_FLOOR; y < stepY; y++) {
                    b.set(x, y, z, BlockSpec.of(Material.COBBLED_DEEPSLATE));
                }
                b.set(x, stepY, z, Blocks.stairs(b.chance(0.2) ? Material.COBBLED_DEEPSLATE_STAIRS
                        : Material.DEEPSLATE_BRICK_STAIRS, up));
                b.clearBox(x, stepY + 1, z, x, G + 3, z);
            }
            // Retaining walls on both sides, a broken parapet on top.
            for (int x : new int[] {CX1 + 1, CX2 - 1}) {
                for (int y = C_FLOOR; y <= G; y++) {
                    b.set(x, y, z, CATACOMB_WALL.pick(b.random));
                }
                if (i < steps - 1 && b.hash(x, z, 71) < 0.55) {
                    b.set(x, G + 1, z, Blocks.slab(Material.COBBLED_DEEPSLATE_SLAB));
                }
            }
        }
        // Soul lanterns on the retaining walls at the stairwell's mouth.
        int mouth = zWall + dir * (steps - 1);
        for (int x : new int[] {CX1 + 1, CX2 - 1}) {
            b.set(x, G, mouth, BlockSpec.of(Material.POLISHED_DEEPSLATE));
            b.set(x, G + 1, mouth, Blocks.lantern(true, false));
        }
    }

    private void skylights() {
        // A collapsed hole over the middle of the hall with rubble below.
        for (int x = CX1 + 2; x <= CX2 - 2; x++) {
            for (int z = -72; z <= -70; z++) {
                boolean corner = (x == CX1 + 2 || x == CX2 - 2) && (z == -72 || z == -70);
                if (corner && b.hash(x, z, 81) < 0.5) {
                    continue;
                }
                b.clear(x, C_CEILING, z);
                b.clear(x, G, z);
            }
        }
        b.rubble(25, -71, C_FLOOR, 1.7, GraveyardBuilder.RUBBLE);
        for (int x = CX1 + 1; x <= CX2 - 1; x++) {
            b.set(x, G + 1, -73, b.chance(0.5) ? Blocks.slab(Material.COBBLED_DEEPSLATE_SLAB)
                    : BlockSpec.of(Material.COBBLED_DEEPSLATE));
            b.set(x, G + 1, -69, b.chance(0.5) ? Blocks.slab(Material.MOSSY_COBBLESTONE_SLAB)
                    : BlockSpec.of(Material.MOSSY_COBBLESTONE));
        }
        // An iron grate in the ground further south.
        for (int x = CX1 + 2; x <= CX2 - 2; x++) {
            for (int z = -66; z <= -65; z++) {
                b.clear(x, C_CEILING, z);
                b.set(x, G, z, BlockSpec.of(Material.IRON_BARS));
            }
        }
        b.set(CX1 + 1, G, -66, BlockSpec.of(Material.POLISHED_DEEPSLATE));
        b.set(CX2 - 1, G, -65, BlockSpec.of(Material.POLISHED_DEEPSLATE));
    }

    /** Ruined walls around the stairwells above ground. */
    private void surface() {
        b.ruinedWall(CX1 - 1, -57, CX1 - 1, -61, G, 3, GraveyardBuilder.STONE_RUIN, 5);
        b.ruinedWall(CX2 + 1, -56, CX2 + 1, -60, G, 2, GraveyardBuilder.STONE_RUIN, 6);
    }

    // ------------------------------------------------------------------ barrow

    private int barrowHeight(int x, int z) {
        int dx = x - BX;
        int dz = z - BZ;
        if (Math.abs(dx) > MOUND_R || Math.abs(dz) > MOUND_R) {
            return 0;
        }
        return barrow[dx + MOUND_R][dz + MOUND_R];
    }

    /** The back lane's tunnel through the barrow: roofed where the mound is high, a stone-lined cut elsewhere. */
    private void barrowTunnel() {
        int roofed = 4;
        int west = Integer.MAX_VALUE;
        int east = Integer.MIN_VALUE;
        for (int x = BX - MOUND_R; x <= BX + MOUND_R; x++) {
            for (int z = BZ - 1; z <= BZ + 1; z++) {
                int h = barrowHeight(x, z);
                if (h <= 0 || b.use(x, z) != Use.PATH) {
                    continue;
                }
                if (h >= roofed) {
                    b.set(x, G + 4, z, TUNNEL_WALL.pick(b.random));
                    for (int y = G + 5; y <= G + h; y++) {
                        b.set(x, y, z, BlockSpec.of(y == G + h ? Material.GRASS_BLOCK : Material.DIRT));
                    }
                    b.setGround(x, z, G + h);
                    west = Math.min(west, x);
                    east = Math.max(east, x);
                }
            }
            // Line the sides of the passage with stone.
            for (int z : new int[] {BZ - 2, BZ + 2}) {
                int h = barrowHeight(x, z);
                for (int y = G + 1; y <= Math.min(G + 3, G + h); y++) {
                    b.set(x, y, z, TUNNEL_WALL.pick(b.random));
                }
            }
        }
        if (west > east) {
            return;
        }
        // Portals of standing stones at both ends of the roofed part.
        for (int x : new int[] {west, east}) {
            for (int z : new int[] {BZ - 2, BZ + 2}) {
                for (int y = G + 1; y <= G + 3; y++) {
                    b.set(x, y, z, BlockSpec.of(Material.POLISHED_ANDESITE));
                }
            }
            for (int z = BZ - 2; z <= BZ + 2; z++) {
                b.set(x, G + 4, z, BlockSpec.of(Material.CHISELED_STONE_BRICKS));
            }
        }
        // Niches with skulls and candles along the passage, cobwebs high in the corners.
        for (int x = west + 2; x <= east - 2; x += 3) {
            int side = (x / 3) % 2 == 0 ? BZ - 2 : BZ + 2;
            int back = side < BZ ? side - 1 : side + 1;
            if (barrowHeight(x, back) >= 3) {
                b.clear(x, G + 1, side);
                b.set(x, G + 1, back, TUNNEL_WALL.pick(b.random));
                b.set(x, G + 1, side, b.chance(0.5) ? BlockSpec.of(Material.SKELETON_SKULL)
                        : BlockSpec.of(Material.CANDLE).lit(true));
            }
        }
        b.set(west + 1, G + 3, BZ - 1, BlockSpec.of(Material.COBWEB));
        b.set(east - 1, G + 3, BZ + 1, BlockSpec.of(Material.COBWEB));
        b.set((west + east) / 2, G + 3, BZ - 1, Blocks.lantern(true, true));
    }

    /** A mossy cap, standing stones and a cairn on top of the barrow. */
    private void barrowTop() {
        for (int dx = -MOUND_R; dx <= MOUND_R; dx++) {
            for (int dz = -MOUND_R; dz <= MOUND_R; dz++) {
                int x = BX + dx;
                int z = BZ + dz;
                int h = barrowHeight(x, z);
                if (h >= 3 && b.use(x, z) != Use.PATH && b.ground(x, z) == G + h) {
                    double roll = b.hash(x, z, 63);
                    b.set(x, G + h, z, BlockSpec.of(roll < 0.55 ? Material.MOSS_BLOCK
                            : roll < 0.8 ? Material.GRASS_BLOCK : Material.PODZOL));
                }
            }
        }
        int top = G + barrowHeight(BX, BZ - 3);
        b.rubble(BX - 3, BZ - 3, top, 1.6, Palette.of(Material.MOSSY_COBBLESTONE, 2, Material.TUFF, 1,
                Material.ANDESITE, 1));
        int[][] stones = {{BX + 4, BZ - 4}, {BX - 6, BZ - 4}, {BX - 1, BZ + 5}};
        for (int[] s : stones) {
            int y = b.ground(s[0], s[1]);
            b.set(s[0], y + 1, s[1], BlockSpec.of(Material.POLISHED_ANDESITE));
            b.set(s[0], y + 2, s[1], Blocks.stairs(Material.ANDESITE_STAIRS, BlockFace.EAST));
            b.set(s[0] + 1, y + 1, s[1], Blocks.stairs(Material.ANDESITE_STAIRS, BlockFace.WEST));
        }
    }
}
