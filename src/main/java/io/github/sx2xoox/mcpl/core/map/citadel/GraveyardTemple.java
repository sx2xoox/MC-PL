package io.github.sx2xoox.mcpl.core.map.citadel;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import io.github.sx2xoox.mcpl.core.map.citadel.GraveyardBuilder.Palette;
import io.github.sx2xoox.mcpl.core.map.citadel.GraveyardBuilder.Use;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

/**
 * The ruined temple in the middle of the graveyard: a podium four blocks high with wide stairs on all four sides and
 * a collapsed corner, a portico with a broken pediment and a colonnade around a cella whose front has lost its roof,
 * pews in the nave and an altar under what is left of the roof. The broken front-west corner of the cella climbs like
 * a stair onto the roof, and a ladder in the back corner leads up through a hole, so the roof is a high perch with
 * two ways up (and open to every side).
 *
 * <pre>
 *   z -83..-81  back stairs           x -3..3
 *   z -80..-56  podium (top y 68)     x -12..12
 *   z -77..-62  cella (walls y 69..74) x -8..8, roof over z -78..-68
 *   z -59       portico columns
 *   z -55..-53  grand stairs           x -6..6
 * </pre>
 */
final class GraveyardTemple {

    static final int X1 = -12;
    static final int X2 = 12;
    static final int Z_NORTH = -80;
    static final int Z_SOUTH = -56;
    /** Top block of the podium; players walk on the podium at {@code TOP + 1}. */
    static final int TOP = 68;
    static final int FLOOR = TOP + 1;

    static final int CX = 8;
    static final int CZ_NORTH = -77;
    static final int CZ_SOUTH = -62;
    /** Highest block of the cella walls. */
    static final int WALL_TOP = FLOOR + 5;
    /** First row (towards the front) still roofed. */
    static final int ROOF_FRONT = -68;

    private static final Palette PODIUM_FACE = Palette.of(Material.DEEPSLATE_BRICKS, 8,
            Material.CRACKED_DEEPSLATE_BRICKS, 3, Material.DEEPSLATE_TILES, 2, Material.COBBLED_DEEPSLATE, 1,
            Material.MOSSY_COBBLESTONE, 1);
    private static final Palette OUTER_FLOOR = Palette.of(Material.STONE_BRICKS, 5, Material.MOSSY_STONE_BRICKS, 4,
            Material.CRACKED_STONE_BRICKS, 3, Material.POLISHED_ANDESITE, 2, Material.MOSS_BLOCK, 1,
            Material.TUFF_BRICKS, 1);
    private static final Palette INNER_FLOOR = Palette.of(Material.DEEPSLATE_TILES, 6,
            Material.CRACKED_DEEPSLATE_TILES, 3, Material.POLISHED_DEEPSLATE, 2, Material.DEEPSLATE_BRICKS, 1);
    private static final Palette WALL_LOW = Palette.of(Material.DEEPSLATE_BRICKS, 5,
            Material.CRACKED_DEEPSLATE_BRICKS, 2, Material.POLISHED_DEEPSLATE, 1);
    private static final Palette WALL_HIGH = Palette.of(Material.STONE_BRICKS, 6, Material.MOSSY_STONE_BRICKS, 3,
            Material.CRACKED_STONE_BRICKS, 3, Material.ANDESITE, 1);

    /** Columns are pale weathered stone drums on dark bases, standing out against the deepslate podium and roof. */
    private static final BlockSpec COLUMN_BASE = BlockSpec.of(Material.POLISHED_DEEPSLATE);
    private static final Palette SHAFT = Palette.of(Material.POLISHED_ANDESITE, 6, Material.ANDESITE, 2,
            Material.CRACKED_STONE_BRICKS, 1);
    private static final BlockSpec CAPITAL = BlockSpec.of(Material.CHISELED_STONE_BRICKS);

    private final GraveyardBuilder b;

    GraveyardTemple(GraveyardBuilder builder) {
        this.b = builder;
    }

    /** Marks the temple's columns so paths, graves and trees keep off it. */
    void reserve() {
        b.mark(X1 - 1, Z_NORTH - 3, X2 + 1, Z_SOUTH + 3, Use.STRUCTURE);
        b.mark(X1 - 4, -74, X1 - 1, -66, Use.STRUCTURE);
        b.mark(X2 + 1, -79, X2 + 4, -71, Use.STRUCTURE);
        b.mark(X2 - 2, -62, X2 + 6, -51, Use.STRUCTURE);
        b.mark(-7, Z_SOUTH, 7, -52, Use.STRUCTURE);
    }

    void build() {
        podium();
        stairs();
        collapsedCorner();
        cella();
        roof();
        portico();
        peristyle();
        altar();
        nave();
        overgrowth();
    }

    // ------------------------------------------------------------------ podium and stairs

    private void podium() {
        for (int x = X1; x <= X2; x++) {
            for (int z = Z_NORTH; z <= Z_SOUTH; z++) {
                boolean edge = x == X1 || x == X2 || z == Z_NORTH || z == Z_SOUTH;
                for (int y = GraveyardBuilder.GROUND + 1; y < TOP; y++) {
                    b.set(x, y, z, edge ? PODIUM_FACE.pick(b.random) : BlockSpec.of(Material.COBBLED_DEEPSLATE));
                }
                b.set(x, TOP, z, floorBlock(x, z, edge));
            }
        }
        // A plinth course along the foot of the podium (a one-block step, not a way up).
        for (int x = X1 - 1; x <= X2 + 1; x++) {
            plinth(x, Z_SOUTH + 1, BlockFace.NORTH);
            plinth(x, Z_NORTH - 1, BlockFace.SOUTH);
        }
        for (int z = Z_NORTH; z <= Z_SOUTH; z++) {
            plinth(X1 - 1, z, BlockFace.EAST);
            plinth(X2 + 1, z, BlockFace.WEST);
        }
        // Cornice: upside-down stairs under the podium's top edge.
        for (int x = X1; x <= X2; x++) {
            cornice(x, Z_SOUTH + 1, BlockFace.NORTH);
            cornice(x, Z_NORTH - 1, BlockFace.SOUTH);
        }
        for (int z = Z_NORTH; z <= Z_SOUTH; z++) {
            cornice(X1 - 1, z, BlockFace.EAST);
            cornice(X2 + 1, z, BlockFace.WEST);
        }
    }

    private BlockSpec floorBlock(int x, int z, boolean edge) {
        if (edge) {
            return BlockSpec.of(b.chance(0.2) ? Material.CRACKED_DEEPSLATE_TILES : Material.POLISHED_DEEPSLATE);
        }
        boolean inCella = Math.abs(x) < CX && z > CZ_NORTH && z < CZ_SOUTH;
        if (inCella) {
            if (Math.abs(x) <= 1) {
                return BlockSpec.of(b.chance(0.25) ? Material.CRACKED_POLISHED_BLACKSTONE_BRICKS
                        : Material.POLISHED_BLACKSTONE_BRICKS);
            }
            return INNER_FLOOR.pick(b.random);
        }
        // The open floor around the cella is overgrown towards the edges.
        double moss = b.noise(x, z, 4, 31);
        if (moss > 0.72) {
            return BlockSpec.of(b.chance(0.5) ? Material.MOSS_BLOCK : Material.GRASS_BLOCK);
        }
        return OUTER_FLOOR.pick(b.random);
    }

    private void plinth(int x, int z, BlockFace towardsPodium) {
        if (b.isAir(x, GraveyardBuilder.GROUND + 1, z) && b.chance(0.8)) {
            b.set(x, GraveyardBuilder.GROUND + 1, z, Blocks.stairs(Material.POLISHED_DEEPSLATE_STAIRS, towardsPodium));
        }
    }

    private void cornice(int x, int z, BlockFace towardsPodium) {
        if (b.chance(0.75)) {
            b.set(x, TOP, z, Blocks.stairsTop(Material.DEEPSLATE_BRICK_STAIRS, towardsPodium));
        }
    }

    private void stairs() {
        // Grand stairs (front), climbing north.
        for (int x = -6; x <= 6; x++) {
            flight(x, Z_SOUTH + 1, 0, 1, BlockFace.NORTH, Material.POLISHED_DEEPSLATE_STAIRS);
        }
        // Cheek walls with soul lanterns at the foot of the grand stairs.
        for (int side = -1; side <= 1; side += 2) {
            int x = side * 7;
            for (int i = 0; i < 3; i++) {
                int z = Z_SOUTH + 1 + i;
                for (int y = GraveyardBuilder.GROUND + 1; y <= TOP - i; y++) {
                    b.set(x, y, z, BlockSpec.of(Material.POLISHED_DEEPSLATE));
                }
            }
            b.set(x, TOP - 2, Z_SOUTH + 3, BlockSpec.of(Material.CHISELED_DEEPSLATE));
            b.set(x, TOP - 1, Z_SOUTH + 3, Blocks.lantern(true, false));
        }
        // West stairs climbing east, east stairs climbing west, back stairs climbing south.
        for (int z = -73; z <= -67; z++) {
            flight(X1 - 1, z, -1, 0, BlockFace.EAST, Material.STONE_BRICK_STAIRS);
        }
        for (int z = -78; z <= -72; z++) {
            flight(X2 + 1, z, 1, 0, BlockFace.WEST, Material.STONE_BRICK_STAIRS);
        }
        for (int x = -3; x <= 3; x++) {
            flight(x, Z_NORTH - 1, 0, -1, BlockFace.SOUTH, Material.DEEPSLATE_BRICK_STAIRS);
        }
    }

    /**
     * Three steps from the ground up to the podium, starting next to it at {@code x,z} and going outwards by
     * {@code dx,dz}; walking up towards {@code facing}.
     */
    private void flight(int x, int z, int dx, int dz, BlockFace facing, Material stairs) {
        for (int i = 0; i < 3; i++) {
            int sx = x + dx * i;
            int sz = z + dz * i;
            int stepY = TOP - 1 - i;
            for (int y = GraveyardBuilder.GROUND + 1; y < stepY; y++) {
                b.set(sx, y, sz, BlockSpec.of(Material.COBBLED_DEEPSLATE));
            }
            b.clearBox(sx, stepY + 1, sz, sx, TOP + 2, sz);
            Material m = b.chance(0.15) ? Material.COBBLESTONE_STAIRS : stairs;
            if (b.chance(0.06)) {
                m = Material.MOSSY_STONE_BRICK_STAIRS;
            }
            b.set(sx, stepY, sz, Blocks.stairs(m, facing));
        }
    }

    /** The south-east corner of the podium has caved in: a slope of rubble leads up to it. */
    private void collapsedCorner() {
        for (int x = X2 - 2; x <= X2; x++) {
            for (int z = Z_SOUTH - 2; z <= Z_SOUTH; z++) {
                if (b.chance(0.6)) {
                    b.set(x, TOP, z, GraveyardBuilder.RUBBLE.pick(b.random));
                }
            }
        }
        b.rubble(X2 + 1, Z_SOUTH + 1, GraveyardBuilder.GROUND, 4.4, GraveyardBuilder.RUBBLE);
        b.rubble(X2 + 3, Z_SOUTH - 3, GraveyardBuilder.GROUND, 2.4, GraveyardBuilder.RUBBLE);
        for (int i = 0; i < 3; i++) {
            b.set(X2 + 3 + i, GraveyardBuilder.GROUND + 1, Z_SOUTH + 5, SHAFT.pick(b.random));
        }
    }

    // ------------------------------------------------------------------ cella

    /** Height (blocks above the floor) of the cella wall at a wall column. */
    private int wallHeight(int x, int z) {
        int full = WALL_TOP - FLOOR + 1;
        // The front-west corner is broken down to one block and rises one block per block: a climbable ruin.
        if (z == CZ_SOUTH && x <= -2) {
            return Math.min(full, 1 + (x + CX));
        }
        if (x == -CX && z >= CZ_SOUTH - 5) {
            return Math.min(full, 1 + (CZ_SOUTH - z));
        }
        // A breach in the east wall, behind the side door.
        if (x == CX && z <= -71 && z >= -75) {
            return 1 + Math.abs(z + 73);
        }
        return full;
    }

    private void cella() {
        for (int x = -CX; x <= CX; x++) {
            for (int z = CZ_NORTH; z <= CZ_SOUTH; z++) {
                boolean wall = Math.abs(x) == CX || z == CZ_NORTH || z == CZ_SOUTH;
                if (!wall) {
                    continue;
                }
                int h = wallHeight(x, z);
                for (int i = 0; i < h; i++) {
                    int y = FLOOR + i;
                    boolean pilaster = (Math.abs(x) == CX && (z - CZ_SOUTH) % 5 == 0)
                            || (z == CZ_NORTH || z == CZ_SOUTH) && Math.abs(x) % 5 == 3;
                    BlockSpec spec;
                    if (pilaster) {
                        spec = i == h - 1 && h > 2 ? BlockSpec.of(Material.CHISELED_STONE_BRICKS)
                                : BlockSpec.of(Material.POLISHED_DEEPSLATE);
                    } else if (i < 2) {
                        spec = WALL_LOW.pick(b.random);
                    } else if (i == 3) {
                        spec = BlockSpec.of(b.chance(0.3) ? Material.CRACKED_STONE_BRICKS
                                : Material.CHISELED_STONE_BRICKS);
                    } else {
                        spec = WALL_HIGH.pick(b.random);
                    }
                    b.set(x, y, z, spec);
                }
            }
        }
        // Front door with an arch, side doors, a back door.
        b.clearBox(-2, FLOOR, CZ_SOUTH, 2, FLOOR + 3, CZ_SOUTH);
        b.set(-2, FLOOR + 3, CZ_SOUTH, Blocks.stairsTop(Material.STONE_BRICK_STAIRS, BlockFace.WEST));
        b.set(2, FLOOR + 3, CZ_SOUTH, Blocks.stairsTop(Material.STONE_BRICK_STAIRS, BlockFace.EAST));
        b.set(0, FLOOR + 4, CZ_SOUTH, BlockSpec.of(Material.CHISELED_DEEPSLATE));
        b.clearBox(-CX, FLOOR, -72, -CX, FLOOR + 2, -70);
        b.clearBox(CX, FLOOR, -67, CX, FLOOR + 2, -65);
        b.clearBox(4, FLOOR, CZ_NORTH, 5, FLOOR + 2, CZ_NORTH);
        // Narrow barred windows.
        for (int z : new int[] {-75, -68}) {
            window(-CX, z);
        }
        for (int z : new int[] {-69}) {
            window(CX, z);
        }
        window(-5, CZ_NORTH);
        window(5, CZ_SOUTH);
        // Rubble below the east breach, inside and out.
        b.rubble(CX + 1, -73, TOP, 1.6, GraveyardBuilder.RUBBLE);
        b.rubble(CX - 1, -73, TOP, 1.3, GraveyardBuilder.RUBBLE);
    }

    private void window(int x, int z) {
        for (int y = FLOOR + 1; y <= FLOOR + 3; y++) {
            if (!b.isAir(x, y, z)) {
                b.set(x, y, z, b.chance(0.75) ? BlockSpec.of(Material.IRON_BARS) : BlockSpec.AIR);
            }
        }
    }

    // ------------------------------------------------------------------ roof

    /** Height of the roof slab above column {@code x} (a half-pitch gable with its ridge at x = 0). */
    private static int roofY(int x) {
        int k = CX + 1 - Math.abs(x);
        return WALL_TOP + 1 + k / 2;
    }

    private static boolean roofTopSlab(int x) {
        return (CX + 1 - Math.abs(x)) % 2 == 1;
    }

    /** True where the roof has fallen in (besides the whole front part). */
    private boolean roofHole(int x, int z) {
        // Ladder hole in the back-east corner.
        if (x == CX - 1 && (z == CZ_NORTH + 1 || z == CZ_NORTH + 2)) {
            return true;
        }
        // A ragged hole over the east aisle and a smaller one at the west eave.
        double dx = (x - 4) / 2.6;
        double dz = (z + 72) / 2.2;
        if (dx * dx + dz * dz < 1.0) {
            return true;
        }
        double wx = (x + 7) / 1.4;
        double wz = (z + 74) / 1.4;
        return wx * wx + wz * wz < 1.0;
    }

    private void roof() {
        int zFront = ROOF_FRONT;
        int zBack = CZ_NORTH - 1;
        for (int x = -CX - 1; x <= CX + 1; x++) {
            int y = roofY(x);
            boolean top = roofTopSlab(x);
            // The front edge of what is left is ragged.
            int ragged = (int) Math.floor(b.hash(x, 0, 77) * 2.2);
            for (int z = zBack; z <= zFront; z++) {
                if (z > zFront - ragged && Math.abs(x) < CX) {
                    continue;
                }
                if (roofHole(x, z)) {
                    continue;
                }
                Material slab = b.chance(0.12) ? Material.COBBLED_DEEPSLATE_SLAB
                        : b.chance(0.08) ? Material.POLISHED_BLACKSTONE_BRICK_SLAB : Material.DEEPSLATE_TILE_SLAB;
                if (x == 0) {
                    b.set(x, y, z, BlockSpec.of(Material.POLISHED_BLACKSTONE_BRICKS));
                } else {
                    b.set(x, y, z, top ? Blocks.slabTop(slab) : Blocks.slab(slab));
                }
            }
        }
        // Gable wall at the back, under the roof.
        for (int x = -CX + 1; x <= CX - 1; x++) {
            for (int y = WALL_TOP + 1; y < roofY(x); y++) {
                b.set(x, y, CZ_NORTH, WALL_HIGH.pick(b.random));
            }
        }
        // Bare rafters stick out of the broken front edge of the roof (their tops can be walked from the roof).
        for (int x : new int[] {-6, -3, 0, 3, 6}) {
            int front = Integer.MIN_VALUE;
            for (int z = ROOF_FRONT; z >= zBack; z--) {
                if (!b.isAir(x, roofY(x), z)) {
                    front = z;
                    break;
                }
            }
            if (front == Integer.MIN_VALUE) {
                continue;
            }
            int length = 1 + (int) Math.floor(b.hash(x, front, 78) * 3);
            for (int z = front - 2; z <= front + length; z++) {
                b.set(x, roofY(x) - 1, z, Blocks.log(Material.DARK_OAK_LOG, Axis.Z));
            }
        }
        // Hanging soul lanterns under the roof.
        b.hangingLantern(0, roofY(0), -73, 2, true);
        b.hangingLantern(0, roofY(0), -75, 1, true);
        b.hangingLantern(-3, roofY(-3), -76, 1, true);
        // Ladder up through the hole in the back-east corner.
        for (int y = FLOOR; y <= roofY(CX - 1) - 1; y++) {
            b.set(CX - 1, y, CZ_NORTH + 1, BlockSpec.of(Material.LADDER).facing(BlockFace.SOUTH));
        }
    }

    // ------------------------------------------------------------------ columns

    private void portico() {
        int z = -59;
        standing(-9, z, 2);
        standing(-4, z, 6);
        standing(4, z, 6);
        standing(9, z, 1);
        // The fallen fourth column lies across the terrace.
        drums(5, -57, 1, 0, 4);
        b.set(9, FLOOR, -57, CAPITAL);
        // A lintel over the two standing columns, tied back to the cella, under a broken pediment whose stepped
        // edge (like the cella's broken corner) can be climbed from the roof side.
        int top = FLOOR + 6;
        for (int x = -3; x <= 3; x++) {
            b.set(x, top, z, BlockSpec.of(x == 0 ? Material.CHISELED_DEEPSLATE : Material.POLISHED_DEEPSLATE));
        }
        for (int x : new int[] {-4, 4}) {
            b.set(x, top, z - 1, BlockSpec.of(Material.POLISHED_DEEPSLATE));
            b.set(x, top, z - 2, BlockSpec.of(Material.POLISHED_DEEPSLATE));
        }
        int[] pediment = {1, 2, 3, 2, 1};
        for (int i = 0; i < pediment.length; i++) {
            int x = i - 3;
            for (int k = 1; k <= pediment[i]; k++) {
                boolean edge = k == pediment[i] && i != 2;
                b.set(x, top + k, z, edge ? Blocks.stairs(Material.DEEPSLATE_TILE_STAIRS,
                        i < 2 ? BlockFace.EAST : BlockFace.WEST) : BlockSpec.of(Material.DEEPSLATE_TILES));
            }
        }
        b.set(0, top - 1, z, Blocks.lantern(true, true));
    }

    private void peristyle() {
        int[] zs = {-63, -67, -71, -75, -79};
        int[] west = {3, 6, 1, 4, 6};
        int[] east = {1, 5, 6, 2, 3};
        for (int i = 0; i < zs.length; i++) {
            standing(-11, zs[i], west[i]);
            standing(11, zs[i], east[i]);
        }
        for (int x : new int[] {-6, -1, 6}) {
            standing(x, -79, x == -1 ? 4 : 2);
        }
        // Fallen drums from the broken ones.
        drums(-11, -73, 0, 1, 1);
        drums(10, -62, 0, 1, 2);
        drums(-3, -79, 1, 0, 2);
    }

    /** Fallen column drums lying on the podium floor from {@code x,z} along {@code dx,dz}. */
    private void drums(int x, int z, int dx, int dz, int count) {
        for (int i = 0; i < count; i++) {
            b.set(x + dx * i, FLOOR, z + dz * i, SHAFT.pick(b.random));
        }
    }

    /** A column {@code height} blocks tall on the podium; full height ones carry a capital. */
    private void standing(int x, int z, int height) {
        int full = WALL_TOP - FLOOR + 1;
        b.set(x, FLOOR, z, COLUMN_BASE);
        for (int y = FLOOR + 1; y < FLOOR + height; y++) {
            b.set(x, y, z, SHAFT.pick(b.random));
        }
        if (height >= full) {
            b.set(x, FLOOR + height, z, CAPITAL);
        }
    }

    // ------------------------------------------------------------------ inside

    private void altar() {
        // Dais of polished blackstone, one step up.
        for (int x = -4; x <= 4; x++) {
            for (int z = -76; z <= -73; z++) {
                b.set(x, FLOOR, z, BlockSpec.of(Material.POLISHED_BLACKSTONE_BRICKS));
            }
            b.set(x, FLOOR, -72, Blocks.stairs(Material.POLISHED_BLACKSTONE_BRICK_STAIRS, BlockFace.NORTH));
        }
        // The altar.
        b.set(-1, FLOOR + 1, -74, Blocks.stairs(Material.POLISHED_BLACKSTONE_STAIRS, BlockFace.EAST));
        b.set(0, FLOOR + 1, -74, BlockSpec.of(Material.CRYING_OBSIDIAN));
        b.set(1, FLOOR + 1, -74, Blocks.stairs(Material.POLISHED_BLACKSTONE_STAIRS, BlockFace.WEST));
        b.set(0, FLOOR + 2, -74, BlockSpec.of(Material.BLACK_CANDLE).lit(true));
        // A stepped reredos behind it, climbable from the sides.
        int[] heights = {1, 2, 3, 2, 1};
        for (int i = 0; i < heights.length; i++) {
            int x = i - 2;
            for (int y = FLOOR + 1; y <= FLOOR + heights[i]; y++) {
                BlockSpec spec = x == 0 && y == FLOOR + 2 ? BlockSpec.of(Material.GILDED_BLACKSTONE)
                        : BlockSpec.of(Material.CHISELED_POLISHED_BLACKSTONE);
                b.set(x, y, -76, spec);
            }
        }
        // Braziers on both sides of the dais.
        for (int side = -1; side <= 1; side += 2) {
            b.set(side * 5, FLOOR, -75, BlockSpec.of(Material.CHISELED_POLISHED_BLACKSTONE));
            b.set(side * 5, FLOOR + 1, -75, BlockSpec.of(Material.SOUL_CAMPFIRE).lit(true));
            b.set(side * 3, FLOOR + 1, -73, BlockSpec.of(Material.CANDLE).lit(true));
        }
    }

    private void nave() {
        // Rows of dark oak pews, many broken or missing.
        for (int z = -70; z <= -64; z += 2) {
            for (int x = -6; x <= 6; x++) {
                if (Math.abs(x) <= 1) {
                    continue;
                }
                double roll = b.hash(x, z, 501);
                if (roll < 0.22) {
                    continue;
                }
                BlockSpec pew = roll < 0.32 ? Blocks.slab(Material.DARK_OAK_SLAB)
                        : Blocks.stairs(Material.DARK_OAK_STAIRS, BlockFace.SOUTH);
                b.set(x, FLOOR, z, pew);
            }
        }
        // Rubble and fallen roof where the front of the roof came down.
        b.rubble(-5, -64, TOP, 1.8, GraveyardBuilder.RUBBLE);
        b.rubble(5, -63, TOP, 1.4, GraveyardBuilder.RUBBLE);
        b.set(3, FLOOR, -66, Blocks.slab(Material.DEEPSLATE_TILE_SLAB));
        b.set(-3, FLOOR, -67, Blocks.slab(Material.DEEPSLATE_TILE_SLAB));
        b.lying(-2, FLOOR, -65, Axis.Z, 1, Material.DARK_OAK_LOG);
        // Cobwebs in the dark back corners, high up.
        b.set(-CX + 1, WALL_TOP, CZ_NORTH + 1, BlockSpec.of(Material.COBWEB));
        b.set(-CX + 1, WALL_TOP - 1, CZ_NORTH + 1, BlockSpec.of(Material.COBWEB));
        b.set(CX - 2, WALL_TOP, CZ_NORTH + 1, BlockSpec.of(Material.COBWEB));
        b.set(-CX + 1, FLOOR, CZ_NORTH + 1, BlockSpec.of(Material.COBWEB));
        // Candles on the floor along the walls.
        for (int z : new int[] {-64, -69, -74}) {
            b.set(-CX + 1, FLOOR, z, BlockSpec.of(Material.CANDLE).lit(true));
            b.set(CX - 1, FLOOR, z - 1, BlockSpec.of(Material.CANDLE).lit(true));
        }
    }

    /** Moss creeping over the podium floor, pale moss hanging from the eaves of the roof. */
    private void overgrowth() {
        for (int x = X1; x <= X2; x++) {
            for (int z = Z_NORTH; z <= Z_SOUTH; z++) {
                int y = b.canvas.surfaceY(x, z);
                double moss = b.noise(x, z, 3, 33);
                if (moss > 0.74 && y == TOP && b.isAir(x, y + 1, z) && b.isAir(x, y + 2, z)) {
                    b.set(x, y + 1, z, BlockSpec.of(b.hash(x, z, 34) < 0.6 ? Material.MOSS_CARPET
                            : Material.PALE_MOSS_CARPET));
                }
            }
        }
        for (int x = -CX - 1; x <= CX + 1; x += 2 * CX + 2) {
            for (int z = CZ_NORTH - 1; z <= ROOF_FRONT; z++) {
                if (!b.isAir(x, roofY(x), z) && b.isAir(x, roofY(x) - 1, z) && b.hash(x, z, 35) < 0.35) {
                    b.set(x, roofY(x) - 1, z, BlockSpec.of(Material.PALE_HANGING_MOSS));
                }
            }
        }
    }
}
