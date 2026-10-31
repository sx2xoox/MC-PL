package io.github.sx2xoox.mcpl.core.map.citadel;

import java.util.Random;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import io.github.sx2xoox.mcpl.core.map.citadel.TownPainter.Palette;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Stairs;

/**
 * The chapel south of the market and its bell tower, the town's lookout, on a terrace two blocks above the town.
 *
 * <p>The terrace ({@code x 50..71, z 39..50} and {@code x 50..65, z 51..61}) has stone retaining walls and four
 * staircases: a wide flight from the chapel street, one from the churchyard, one from the garden behind the tower and
 * one from the orchard. The nave ({@code x 55..63, z 46..61}) still has its roof over the northern third; further
 * south the roof fell in and the side walls crumble down in steps to the terrace, so the walls are ramps up to the
 * remaining roof. The bell tower ({@code x 64..70, z 43..49}) has a spiral stair (open in the middle) up to a
 * crenellated platform at {@code y = 80} (players stand at 81, +16 above the streets) with the bell; halfway up a
 * window opens onto the nave roof. Ways up: the tower stair (two doors), the crumbled nave walls, and the nave roof
 * into the tower.
 */
final class TownChapel {

    /** The terrace's top: the chapel's ground. */
    private static final int G = TownPainter.GROUND + 2;

    // nave
    private static final int NX0 = 55;
    private static final int NX1 = 63;
    private static final int NZ0 = 46;
    private static final int NZ1 = 61;
    private static final int NAVE_TOP = G + 7;
    /** Last row (z) under the remaining roof. */
    private static final int ROOF_END = 51;

    // tower
    private static final int TX0 = 64;
    private static final int TX1 = 70;
    private static final int TZ0 = 43;
    private static final int TZ1 = 49;
    private static final int PLATFORM = G + 14;

    private static final Palette WALL = Palette.of(Material.STONE_BRICKS, Material.STONE_BRICKS, Material.STONE_BRICKS,
                    Material.STONE_BRICKS, Material.CRACKED_STONE_BRICKS, Material.MOSSY_STONE_BRICKS,
                    Material.MOSSY_STONE_BRICKS, Material.TUFF_BRICKS)
            .withSlabs(Material.STONE_BRICK_SLAB, Material.MOSSY_STONE_BRICK_SLAB)
            .withStairs(Material.STONE_BRICK_STAIRS, Material.MOSSY_STONE_BRICK_STAIRS);

    private TownChapel() {
    }

    static void build(TownPainter p) {
        terrace(p);
        nave(p);
        naveRoof(p);
        naveInterior(p);
        tower(p);
        yard(p);
    }

    // ------------------------------------------------------------------ terrace

    static boolean onTerrace(int x, int z) {
        return x >= 50 && x <= 71 && z >= 39 && z <= 50 || x >= 50 && x <= 65 && z >= 51 && z <= NZ1;
    }

    /**
     * The terrace: earth behind two-block retaining walls of stone, a low broken parapet along the edges, and four
     * staircases (two steps each) up from the town.
     */
    private static void terrace(TownPainter p) {
        Random random = p.random();
        int ground = TownPainter.GROUND;
        for (int x = 49; x <= 72; x++) {
            for (int z = 38; z <= NZ1 + 1; z++) {
                if (!onTerrace(x, z)) {
                    continue;
                }
                boolean edge = !onTerrace(x - 1, z) || !onTerrace(x + 1, z) || !onTerrace(x, z - 1)
                        || !onTerrace(x, z + 1);
                p.set(x, ground, z, edge ? WALL.next(random) : BlockSpec.of(Material.DIRT));
                p.set(x, ground + 1, z, edge ? WALL.next(random) : BlockSpec.of(Material.DIRT));
                if (edge) {
                    p.set(x, G, z, random.nextDouble() < 0.7 ? BlockSpec.of(Material.STONE_BRICKS)
                            : WALL.next(random));
                    // a broken parapet on the open sides
                    boolean open = z == 39 || x == 50 || x == 71 && z <= 47;
                    if (open && (x + z) % 3 != 0 && random.nextDouble() < 0.6) {
                        p.set(x, G + 1, z, random.nextBoolean() ? BlockSpec.of(Material.STONE_BRICKS)
                                : Blocks.slab(Material.MOSSY_STONE_BRICK_SLAB));
                    }
                } else {
                    double roll = random.nextDouble();
                    p.set(x, G, z, roll < 0.08 ? Material.COARSE_DIRT : roll < 0.12 ? Material.MOSS_BLOCK
                            : Material.GRASS_BLOCK);
                    if (roll > 0.9) {
                        p.set(x, G + 1, z, roll > 0.97 ? Material.FERN : Material.SHORT_GRASS);
                    }
                }
            }
        }
        // staircases: {x, z, run direction (the way up), width}
        flight(p, 57, 37, BlockFace.SOUTH, 7);
        flight(p, 48, 52, BlockFace.EAST, 3);
        flight(p, 73, 49, BlockFace.WEST, 2);
        flight(p, 67, 58, BlockFace.WEST, 2);
    }

    /**
     * A two-step flight from the town up onto the terrace: its lower step at {@code x,z}, climbing towards {@code up},
     * {@code width} blocks wide (to the east for north-south flights, to the south for east-west ones).
     */
    private static void flight(TownPainter p, int x, int z, BlockFace up, int width) {
        int ground = TownPainter.GROUND;
        boolean alongX = up == BlockFace.NORTH || up == BlockFace.SOUTH;
        for (int i = 0; i < width; i++) {
            int sx = x + (alongX ? i : 0);
            int sz = z + (alongX ? 0 : i);
            p.clearBox(sx, ground + 1, sz, sx, ground + 3, sz);
            p.set(sx, ground + 1, sz, Blocks.stairs(Material.STONE_BRICK_STAIRS, up));
            int hx = sx + up.getModX();
            int hz = sz + up.getModZ();
            p.set(hx, ground + 1, hz, Material.STONE_BRICKS);
            p.set(hx, ground + 2, hz, Blocks.stairs(Material.STONE_BRICK_STAIRS, up));
            p.clearBox(hx, ground + 3, hz, hx, ground + 4, hz);
            // the parapet opens where the flight arrives
            int tx = hx + up.getModX();
            int tz = hz + up.getModZ();
            p.clearBox(tx, G + 1, tz, tx, G + 2, tz);
        }
    }

    // ------------------------------------------------------------------ nave

    /** Height of the nave's side wall top at row {@code z}: full under the roof, then one lower per row. */
    private static int sideWallTop(int z) {
        if (z <= ROOF_END + 1) {
            return NAVE_TOP;
        }
        return Math.max(G + 1, NAVE_TOP - (z - ROOF_END - 1));
    }

    private static void nave(TownPainter p) {
        Random random = p.random();
        // floor: stone with a polished aisle
        for (int x = NX0; x <= NX1; x++) {
            for (int z = NZ0; z <= NZ1; z++) {
                boolean wall = x == NX0 || x == NX1 || z == NZ0 || z == NZ1;
                if (wall) {
                    p.set(x, G, z, Material.STONE_BRICKS);
                } else if (x == 59) {
                    p.set(x, G, z, random.nextDouble() < 0.85 ? Material.POLISHED_ANDESITE : Material.ANDESITE);
                } else {
                    p.set(x, G, z, random.nextDouble() < 0.12 ? BlockSpec.of(Material.MOSS_BLOCK)
                            : WALL.next(random));
                }
            }
        }
        // side walls (west and east), stepping down south of the roof
        for (int z = NZ0; z <= NZ1; z++) {
            int top = sideWallTop(z);
            for (int x : new int[] {NX0, NX1}) {
                if (x == NX1 && z >= TZ0 && z <= TZ1) {
                    top = NAVE_TOP; // under the tower's shoulder
                }
                for (int y = G + 1; y <= top; y++) {
                    boolean corner = z == NZ0 || z == NZ1;
                    p.set(x, y, z, corner ? BlockSpec.of(Material.CHISELED_STONE_BRICKS) : WALL.next(random));
                }
            }
        }
        // north gable wall with a three-wide arched doorway and a round window
        for (int x = NX0; x <= NX1; x++) {
            for (int y = G + 1; y <= NAVE_TOP; y++) {
                p.set(x, y, NZ0, WALL.next(random));
            }
        }
        p.clearBox(58, G + 1, NZ0, 60, G + 3, NZ0);
        p.set(58, G + 4, NZ0, Blocks.stairsTop(Material.STONE_BRICK_STAIRS, BlockFace.WEST));
        p.set(60, G + 4, NZ0, Blocks.stairsTop(Material.STONE_BRICK_STAIRS, BlockFace.EAST));
        p.clear(59, G + 4, NZ0);
        p.set(59, G + 5, NZ0, Material.CHISELED_STONE_BRICKS);
        // south wall: a low broken stub
        for (int x = NX0 + 1; x < NX1; x++) {
            int h = 1 + (int) Math.round(Math.abs(Math.sin(x * 1.3)) * 1.4);
            for (int y = G + 1; y <= G + h; y++) {
                p.set(x, y, NZ1, WALL.next(random));
            }
        }
        // tall lancet windows in the side walls (only where the wall is still high)
        for (int z = NZ0 + 4; z < NZ1; z += 3) {
            for (int x : new int[] {NX0, NX1}) {
                if (x == NX1 && z >= TZ0 && z <= TZ1 + 1) {
                    continue;
                }
                if (sideWallTop(z) >= G + 5) {
                    p.clearBox(x, G + 2, z, x, G + 4, z);
                }
            }
        }
        // side door in the west wall
        p.clearBox(NX0, G + 1, 49, NX0, G + 3, 49);
        p.door(NX0, G + 1, 49, Material.SPRUCE_DOOR, BlockFace.WEST, true);
        // pilasters under the eaves of the roofed part
        for (int z : new int[] {NZ0 + 2, ROOF_END}) {
            for (int y = G + 1; y <= NAVE_TOP - 1; y++) {
                p.set(NX0 - 1, y, z, y == G + 1 ? BlockSpec.of(Material.CHISELED_STONE_BRICKS)
                        : WALL.next(random));
            }
        }
        // a stub of the fallen gable at the south end, and rubble where the roof came down
        p.rubble(59, 56, G + 1, 2.2, 2, TownPainter.RUBBLE);
        p.rubble(57, 59, G + 1, 1.4, 1, TownPainter.RUBBLE);
        for (int x = NX0 + 1; x <= NX0 + 4; x++) {
            p.log(x, G + 1, 54, Material.STRIPPED_SPRUCE_LOG, Axis.X);
        }
    }

    /** The roof over the northern rows: a deepslate gable whose east eave meets the tower window. */
    private static void naveRoof(TownPainter p) {
        Random random = p.random();
        int span = NX1 - NX0 + 3; // with the overhang
        int maxRise = (span - 1) / 2;
        for (int x = NX0 - 1; x <= NX1 + 1; x++) {
            int rise = Math.min(x - (NX0 - 1), (NX1 + 1) - x);
            int y = NAVE_TOP + rise;
            for (int z = NZ0 - 1; z <= ROOF_END; z++) {
                if (x >= TX0 && z >= TZ0 && z <= TZ1) {
                    continue; // the tower stands there
                }
                if (rise == maxRise) {
                    p.set(x, y - 1, z, Material.DEEPSLATE_TILES);
                    p.set(x, y, z, Blocks.slab(Material.DEEPSLATE_TILE_SLAB));
                } else {
                    Material stairs = random.nextDouble() < 0.15 ? Material.COBBLED_DEEPSLATE_STAIRS
                            : Material.DEEPSLATE_TILE_STAIRS;
                    p.set(x, y, z, Blocks.stairs(stairs, x < 59 ? BlockFace.EAST : BlockFace.WEST));
                }
            }
            // the north gable fills up to the roof
            if (x >= NX0 && x <= NX1) {
                int under = rise == maxRise ? y - 2 : y - 1;
                for (int gy = NAVE_TOP + 1; gy <= under; gy++) {
                    p.set(x, gy, NZ0, WALL.next(random));
                }
            }
        }
        // a stained glass window in the gable
        p.set(59, NAVE_TOP + 2, NZ0, Material.RED_STAINED_GLASS_PANE);
        p.set(59, NAVE_TOP + 3, NZ0, Material.YELLOW_STAINED_GLASS_PANE);
        // the broken roof edge: a few stairs missing over the last row
        p.clear(57, NAVE_TOP + 2, ROOF_END);
        p.clear(61, NAVE_TOP + 2, ROOF_END);
    }

    private static void naveInterior(TownPainter p) {
        // pews either side of the aisle
        for (int z = 50; z <= 56; z += 2) {
            for (int x : new int[] {56, 57, 61, 62}) {
                if (!p.isSolid(x, G + 1, z)) {
                    p.set(x, G + 1, z, Blocks.stairs(Material.DARK_OAK_STAIRS, BlockFace.NORTH));
                }
            }
        }
        // the altar on a raised step at the south end
        for (int x = 57; x <= 61; x++) {
            p.set(x, G + 1, 59, Blocks.stairs(Material.STONE_BRICK_STAIRS, BlockFace.SOUTH));
            p.set(x, G + 1, 60, Material.POLISHED_ANDESITE);
        }
        p.set(59, G + 2, 60, Material.CHISELED_STONE_BRICKS);
        p.set(58, G + 2, 60, Material.LECTERN);
        p.set(60, G + 2, 60, Material.CANDLE);
        p.set(59, G + 3, 60, BlockSpec.of(Material.CANDLE).lit(false));
        // hanging lanterns under the remaining roof
        p.set(59, NAVE_TOP + 3, 48, Material.IRON_CHAIN);
        p.set(59, NAVE_TOP + 2, 48, Blocks.lantern(false, true));
        p.set(59, NAVE_TOP + 3, 50, Material.IRON_CHAIN);
        p.set(59, NAVE_TOP + 2, 50, Blocks.lantern(false, true));
        p.setIfAir(NX0 + 1, NAVE_TOP, NZ0 + 1, BlockSpec.of(Material.COBWEB));
        p.setIfAir(NX1 - 1, NAVE_TOP, NZ0 + 1, BlockSpec.of(Material.COBWEB));
    }

    // ------------------------------------------------------------------ tower

    /** The interior ring of the tower, clockwise from the north-west corner. */
    private static final int[][] RING = {
            {65, 44}, {66, 44}, {67, 44}, {68, 44}, {69, 44},
            {69, 45}, {69, 46}, {69, 47}, {69, 48},
            {68, 48}, {67, 48}, {66, 48}, {65, 48},
            {65, 47}, {65, 46}, {65, 45}};

    /** Ring index of the first step (just inside the east door). */
    private static final int STAIR_START = 6;
    /** Number of rising cells: 13 steps from y 65 to 77, then the landing at the platform. */
    private static final int STEPS = 13;

    private static void tower(TownPainter p) {
        Random random = p.random();
        // walls
        for (int x = TX0; x <= TX1; x++) {
            for (int z = TZ0; z <= TZ1; z++) {
                boolean wall = x == TX0 || x == TX1 || z == TZ0 || z == TZ1;
                boolean corner = (x == TX0 || x == TX1) && (z == TZ0 || z == TZ1);
                p.set(x, G, z, wall ? BlockSpec.of(Material.STONE_BRICKS) : WALL.next(random));
                if (!wall) {
                    p.clearBox(x, G + 1, z, x, PLATFORM - 1, z);
                    continue;
                }
                for (int y = G + 1; y <= PLATFORM; y++) {
                    p.set(x, y, z, corner ? BlockSpec.of(y % 4 == 0 ? Material.CHISELED_STONE_BRICKS
                            : Material.STONE_BRICKS) : WALL.next(random));
                }
            }
        }
        // a plinth course around the base
        for (int x = TX0 - 1; x <= TX1 + 1; x++) {
            for (int z = TZ0 - 1; z <= TZ1 + 1; z++) {
                boolean ring = x == TX0 - 1 || x == TX1 + 1 || z == TZ0 - 1 || z == TZ1 + 1;
                if (ring && !(x <= NX1 && z >= NZ0) && !p.isSolid(x, G + 1, z)) {
                    p.set(x, G + 1, z, WALL.stair(random, facingInto(x, z)));
                }
            }
        }
        // doors: north (towards the market) and east
        p.clearBox(67, G + 1, TZ0 - 1, 67, G + 2, TZ0 - 1);
        p.clearBox(TX1 + 1, G + 1, 45, TX1 + 1, G + 2, 45);
        p.door(67, G + 1, TZ0, Material.SPRUCE_DOOR, BlockFace.NORTH, false);
        p.door(TX1, G + 1, 45, Material.SPRUCE_DOOR, BlockFace.EAST, true);
        // spiral stair around an open well
        for (int i = 0; i < STEPS; i++) {
            int[] c = RING[(STAIR_START + i) % RING.length];
            int[] next = RING[(STAIR_START + i + 1) % RING.length];
            int y = G + 1 + i;
            // every step is a stair turned towards the next one (corners too, so the climb never needs a jump)
            BlockFace dir = faceTowards(c, next);
            boolean turn = isCornerCell(c);
            p.set(c[0], y, c[1], Blocks.stairs(Material.STONE_BRICK_STAIRS, dir));
            if (y <= G + 3) {
                p.fill(c[0], G + 1, c[1], c[0], y - 1, c[1], BlockSpec.of(Material.STONE_BRICKS));
            } else if (y >= G + 5) {
                // an upside-down stair under each floating step
                p.setIfAir(c[0], y - 1, c[1], turn ? Blocks.slabTop(Material.STONE_BRICK_SLAB)
                        : Blocks.stairsTop(Material.STONE_BRICK_STAIRS, dir.getOppositeFace()));
            }
        }
        // the platform: covers the inside except the stairwell above the last steps
        for (int x = TX0 + 1; x < TX1; x++) {
            for (int z = TZ0 + 1; z < TZ1; z++) {
                p.set(x, PLATFORM, z, Material.SPRUCE_PLANKS);
            }
        }
        for (int i = STEPS - 3; i < STEPS; i++) {
            int[] c = RING[(STAIR_START + i) % RING.length];
            p.clear(c[0], PLATFORM, c[1]);
        }
        int[] landing = RING[(STAIR_START + STEPS) % RING.length];
        p.set(landing[0], PLATFORM, landing[1], Material.STONE_BRICKS);
        // a rail along the stairwell
        for (int i = STEPS - 3; i < STEPS; i++) {
            int[] c = RING[(STAIR_START + i) % RING.length];
            p.set(c[0], PLATFORM + 1, c[1] + 1, Material.SPRUCE_FENCE);
        }
        // a string course of polished stone around the middle
        for (int x = TX0; x <= TX1; x++) {
            for (int z = TZ0; z <= TZ1; z++) {
                boolean wall = x == TX0 || x == TX1 || z == TZ0 || z == TZ1;
                boolean corner = (x == TX0 || x == TX1) && (z == TZ0 || z == TZ1);
                if (wall && !corner && p.isSolid(x, NAVE_TOP, z)) {
                    p.set(x, NAVE_TOP, z, Material.POLISHED_ANDESITE);
                }
            }
        }
        // the top overhangs the walls by one block on corbels (upside-down stairs), with a parapet on the edge
        for (int x = TX0 - 1; x <= TX1 + 1; x++) {
            for (int z = TZ0 - 1; z <= TZ1 + 1; z++) {
                boolean ring = x == TX0 - 1 || x == TX1 + 1 || z == TZ0 - 1 || z == TZ1 + 1;
                if (!ring) {
                    continue;
                }
                boolean corner = (x == TX0 - 1 || x == TX1 + 1) && (z == TZ0 - 1 || z == TZ1 + 1);
                p.set(x, PLATFORM - 1, z, corner ? BlockSpec.of(Material.CHISELED_STONE_BRICKS)
                        : Blocks.stairsTop(Material.STONE_BRICK_STAIRS, facingInto(x, z)));
                p.set(x, PLATFORM, z, corner ? BlockSpec.of(Material.CHISELED_STONE_BRICKS)
                        : BlockSpec.of(Material.STONE_BRICKS));
            }
        }
        // parapet with merlons; taller pinnacles with lanterns on the corners
        for (int x = TX0 - 1; x <= TX1 + 1; x++) {
            for (int z = TZ0 - 1; z <= TZ1 + 1; z++) {
                boolean wall = x == TX0 - 1 || x == TX1 + 1 || z == TZ0 - 1 || z == TZ1 + 1;
                if (!wall) {
                    continue;
                }
                boolean corner = (x == TX0 - 1 || x == TX1 + 1) && (z == TZ0 - 1 || z == TZ1 + 1);
                if (corner) {
                    p.set(x, PLATFORM + 1, z, Material.CHISELED_STONE_BRICKS);
                    p.set(x, PLATFORM + 2, z, Material.STONE_BRICKS);
                    p.set(x, PLATFORM + 3, z, Blocks.lantern(false, false));
                } else if ((x + z) % 2 == 0) {
                    p.set(x, PLATFORM + 1, z, random.nextDouble() < 0.8 ? Material.STONE_BRICKS
                            : Material.MOSSY_STONE_BRICKS);
                }
            }
        }
        // the bell in the middle of the platform
        p.set(67, PLATFORM + 1, 46, BlockSpec.of(Material.BELL).facing(BlockFace.NORTH));
        // windows beside the stair, each sill level with its step: onto the nave roof (steps 7 and 8, where the
        // roof's east eave meets the tower) and slits to shoot from
        for (int step : new int[] {1, 3, 5, 7, 8, 11}) {
            int[] c = RING[(STAIR_START + step) % RING.length];
            int y = G + 1 + step;
            int wx = c[0] == 65 ? TX0 : c[0] == 69 ? TX1 : c[0];
            int wz = wx == c[0] ? (c[1] == 44 ? TZ0 : TZ1) : c[1];
            p.set(wx, y, wz, Material.STONE_BRICKS);
            p.clearBox(wx, y + 1, wz, wx, y + 2, wz);
        }
    }

    private static boolean isCornerCell(int[] c) {
        return (c[0] == 65 || c[0] == 69) && (c[1] == 44 || c[1] == 48);
    }

    private static BlockFace faceTowards(int[] from, int[] to) {
        if (to[0] > from[0]) {
            return BlockFace.EAST;
        }
        if (to[0] < from[0]) {
            return BlockFace.WEST;
        }
        return to[1] > from[1] ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    /** For the plinth around the tower: the stair's back towards the tower. */
    private static BlockFace facingInto(int x, int z) {
        if (x < TX0) {
            return BlockFace.EAST;
        }
        if (x > TX1) {
            return BlockFace.WEST;
        }
        return z < TZ0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    // ------------------------------------------------------------------ yard

    /** The chapel square in front of the nave, a few graves and an old yew. */
    private static void yard(TownPainter p) {
        Random random = p.random();
        // paving in front of the doorway
        for (int x = 55; x <= 63; x++) {
            for (int z = 40; z <= 45; z++) {
                if (random.nextDouble() < 0.8) {
                    p.set(x, G, z, WALL.next(random));
                }
            }
        }
        // graves west of the nave
        for (int z = 52; z <= 58; z += 3) {
            int x = 52;
            p.set(x, G + 1, z, Blocks.stairs(random.nextBoolean() ? Material.MOSSY_STONE_BRICK_STAIRS
                    : Material.STONE_BRICK_STAIRS, BlockFace.WEST));
            p.set(x, G, z + 1, Material.COARSE_DIRT);
            if (random.nextBoolean()) {
                p.set(x, G + 1, z + 1, Material.POPPY);
            }
        }
        p.set(51, G + 1, 56, Blocks.stairs(Material.MOSSY_STONE_BRICK_STAIRS, BlockFace.WEST));
        p.set(51, G, 57, Material.COARSE_DIRT);
        // barrels with a lantern by the parapet
        p.set(52, G + 1, 41, BlockSpec.of(Material.BARREL).facing(BlockFace.UP));
        p.set(52, G + 1, 42, BlockSpec.of(Material.BARREL).facing(BlockFace.NORTH));
        p.set(52, G + 2, 41, Blocks.lantern(false, false));
        // stone benches
        p.set(54, G + 1, 41, Blocks.stairs(Material.STONE_BRICK_STAIRS, BlockFace.WEST)
                .shape(Stairs.Shape.STRAIGHT));
        p.set(54, G + 1, 42, Blocks.stairs(Material.STONE_BRICK_STAIRS, BlockFace.WEST));
        p.set(64, G + 1, 41, Blocks.stairs(Material.STONE_BRICK_STAIRS, BlockFace.EAST));
        p.set(64, G + 1, 42, Blocks.stairs(Material.STONE_BRICK_STAIRS, BlockFace.EAST));
    }
}
