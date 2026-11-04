package io.github.sx2xoox.mcpl.core.map.toykingdom;

import java.util.Map;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites.Lane;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites.Side;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.jspecify.annotations.Nullable;

/**
 * The blocks of 장난감 왕국 and the shared pieces: the floor of every column (a mint play mat of big tiles on wooden
 * floorboards) and the toy props (toy brick walls, cotton candy and sponge balls, candy canes, lollipop lamps, toy
 * blocks). Props are placed in quarter coordinates ({@code ax, az >= 0}) through a {@link Quarter}, so both halves (and
 * both lanes) are mirror images; shaped detail blocks (stairs, slabs, trapdoors, buttons, banners) go through
 * {@link Quarter#place}, which mirrors their facing too. The detail blocks are a second tone of the toy colours
 * (cherry = pink, quartz = white, bamboo = yellow, birch = the floorboards, red nether brick = deep red) that round off
 * corners, slopes and rims.
 */
final class ToyKit {

    private ToyKit() {
    }

    static final int G = ToyLayout.GROUND_Y;

    /** The play mat: big tiles of two mints. */
    static final BlockSpec MAT = BlockSpec.of(Material.LIME_WOOL);
    static final BlockSpec MAT_TILE = BlockSpec.of(Material.LIME_CONCRETE);
    /** Wooden floorboards: the room's floor around the mat, under it, the courtyards and the sandbox frame. */
    static final BlockSpec WOOD = BlockSpec.of(Material.BIRCH_PLANKS);
    static final BlockSpec WHITE = BlockSpec.of(Material.WHITE_CONCRETE);
    static final BlockSpec YELLOW = BlockSpec.of(Material.YELLOW_CONCRETE);
    static final BlockSpec SKY = BlockSpec.of(Material.LIGHT_BLUE_CONCRETE);
    static final BlockSpec PINK = BlockSpec.of(Material.PINK_CONCRETE);
    static final BlockSpec BLUE = BlockSpec.of(Material.BLUE_CONCRETE);
    static final BlockSpec RED = BlockSpec.of(Material.RED_CONCRETE);
    static final BlockSpec SAND = BlockSpec.of(Material.SAND);
    static final BlockSpec WATER = BlockSpec.of(Material.WATER);
    static final BlockSpec JELLY = BlockSpec.of(Material.SLIME_BLOCK);
    /** Soft toys: cotton candy (pink wool) and sponge. */
    static final BlockSpec COTTON = BlockSpec.of(Material.PINK_WOOL);
    static final BlockSpec SPONGE = BlockSpec.of(Material.SPONGE);
    /** The glowing sweet in the middle of a lollipop lamp (and the clock's hour marks). */
    static final BlockSpec GLOW = BlockSpec.of(Material.PEARLESCENT_FROGLIGHT);

    /** The shade of a sponge bush (its underside). */
    static final BlockSpec SPONGE_SHADE = BlockSpec.of(Material.WET_SPONGE);
    /** Glass (the marble jar). */
    static final BlockSpec GLASS = BlockSpec.of(Material.GLASS);

    // Detail blocks: the second tone of each toy colour, for slopes, rims and rounded corners.
    static final Material WHITE_STAIRS = Material.QUARTZ_STAIRS;
    static final Material PINK_STAIRS = Material.CHERRY_STAIRS;
    static final Material YELLOW_STAIRS = Material.BAMBOO_STAIRS;
    static final Material RED_STAIRS = Material.RED_NETHER_BRICK_STAIRS;
    static final Material WOOD_STAIRS = Material.BIRCH_STAIRS;
    static final BlockSpec WHITE_SLAB = BlockSpec.of(Material.QUARTZ_SLAB).slab(Slab.Type.BOTTOM);
    static final BlockSpec WOOD_SLAB = BlockSpec.of(Material.BIRCH_SLAB).slab(Slab.Type.BOTTOM);
    static final BlockSpec WOOD_SLAB_TOP = BlockSpec.of(Material.BIRCH_SLAB).slab(Slab.Type.TOP);
    static final BlockSpec FENCE = BlockSpec.of(Material.BIRCH_FENCE);
    /** A thin white stick that glows (lollipop and cotton candy sticks, a ballerina's leg). */
    static final BlockSpec STICK = BlockSpec.of(Material.END_ROD).facing(BlockFace.UP);
    static final BlockSpec CHAIN = BlockSpec.of(Material.IRON_CHAIN);
    static final BlockSpec LANTERN_HANGING = BlockSpec.of(Material.LANTERN).hanging(true);
    static final BlockSpec CREAM = BlockSpec.of(Material.WHITE_CARPET);
    /** Pink blossoms on a bush or topiary (a carpet: it holds on any block). */
    static final BlockSpec BLOSSOM = BlockSpec.of(Material.PINK_CARPET);

    /** The colours of toy bricks, in turn. */
    static final BlockSpec[] TOY_COLOURS = {PINK, YELLOW, SKY, WHITE};

    /** The road colour of a lane: TOP the yellow brick road, BOTTOM the light blue one. */
    static BlockSpec road(Lane lane) {
        return lane == Lane.TOP ? YELLOW : SKY;
    }

    /** A side's strong colour (roofs, rings) and its pastel (castle walls, spawn mat). */
    static BlockSpec strong(Side side) {
        return side == Side.BLUE ? BLUE : RED;
    }

    static BlockSpec pastel(Side side) {
        return side == Side.BLUE ? SKY : PINK;
    }

    // ------------------------------------------------------------------ ground

    /** The play mat at a column: 4 x 4 tiles of two mints. */
    static BlockSpec mat(int x, int z) {
        return Math.floorMod(Math.floorDiv(x, 4) + Math.floorDiv(z, 4), 2) == 0 ? MAT : MAT_TILE;
    }

    /** Builds the ground of one column: floorboards under a top block chosen by the plan (nothing over a drop). */
    static void ground(MapCanvas canvas, int x, int z) {
        double out = ToyLayout.outline(x, z);
        if (out > ToyLayout.HEDGE_WIDTH + ToyLayout.APRON_WIDTH || ToyLayout.inDrop(x, z)) {
            return;
        }
        for (int y = G - 3; y < G; y++) {
            canvas.set(x, y, z, WOOD);
        }
        if (ToyLayout.onCliffEdge(x, z)) {
            // Red and white warning stripes along the edge of a drop.
            canvas.set(x, G, z, Math.floorMod(x + z, 2) == 0 ? RED : WHITE);
            return;
        }
        if (out > 0) {
            canvas.set(x, G, z, WOOD);
            return;
        }
        Side side = ToyLayout.sideOf(x);
        int ax = Math.abs(x);
        if (ToyLayout.inCastle(x, z)) {
            canvas.set(x, G, z, castleFloor(side, ax, z));
            return;
        }
        if (ToyLayout.onFrontPlaza(x, z)) {
            // A checkerboard mat in the side's pastel and white, like a toy board at the castle's door; the lowered
            // drawbridge (floorboards) runs across it from the gate.
            boolean bridge = Math.abs(z) <= ToyLayout.GATE_HALF - 1 && ax >= ToyLayout.CASTLE_ZONE_FRONT;
            canvas.set(x, G, z, bridge ? WOOD : Math.floorMod(ax + z, 2) == 0 ? WHITE : pastel(side));
            return;
        }
        double centre = ToyLayout.centre(x, z);
        if (centre <= ToyLayout.PIT_RADIUS) {
            canvas.set(x, G - 1, z, SAND);
            return;
        }
        if (centre <= ToyLayout.PIT_FRAME) {
            canvas.set(x, G, z, WOOD);
            return;
        }
        if (ToyLayout.river(x, z) <= ToyLayout.RIVER_HALF) {
            canvas.set(x, G - 1, z, SAND);
            canvas.set(x, G, z, WATER);
            return;
        }
        Lane lane = ToyLayout.laneOf(z);
        double tower = ToyLayout.towerDistance(x, z);
        double d = ToyLayout.laneDistance(lane, x, z);
        BlockSpec top;
        if (tower <= ToyLayout.TOWER_RADIUS + 0.5) {
            top = road(lane);
        } else if (tower <= ToyLayout.RING_OUTER) {
            top = strong(side);
        } else if (d <= ToyLayout.ROAD_HALF) {
            top = road(lane);
        } else if (d <= ToyLayout.EDGE_HALF) {
            top = WHITE;
        } else {
            top = mat(x, z);
        }
        canvas.set(x, G, z, top);
    }

    /**
     * The courtyard floor: the core's stage (a white plate with a pastel ring, rimmed in the team colour and white),
     * carpets in the team colour from the gates to the stage, the spawn mat (pastel, white spots, a team coloured
     * frame) and a checker of floorboards and white tiles everywhere else.
     */
    private static BlockSpec castleFloor(Side side, int ax, int z) {
        int az = Math.abs(z);
        double core = Math.hypot(ax - ToyLayout.CORE_AX, z);
        double r = ToyLayout.CORE_RADIUS;
        if (core <= r + 0.5) {
            return core > r - 3.5 && core <= r - 2.5 ? pastel(side) : WHITE;
        }
        if (core <= r + 1.5) {
            return strong(side);
        }
        if (core <= r + 2.5) {
            return WHITE;
        }
        boolean frontCarpet = ax < ToyLayout.CORE_AX && az <= 1;
        boolean laneCarpet = Math.abs(ax - ToyLayout.GATE_AX) <= 1;
        if (frontCarpet || laneCarpet) {
            return strong(side);
        }
        int x = ToyLayout.sign(side) * ax;
        if (ToyLayout.inSpawnMat(x, z, false)) {
            boolean spot = false;
            for (int[] s : ToyLayout.SPAWN_SPOTS) {
                spot |= s[0] == ax && s[1] == az;
            }
            return spot ? WHITE : pastel(side);
        }
        if (ToyLayout.inSpawnMat(x, z, true)) {
            return strong(side);
        }
        return Math.floorMod(Math.floorDiv(ax, 2) + Math.floorDiv(z, 2), 2) == 0 ? WOOD : WHITE;
    }


    // ------------------------------------------------------------------ quarters

    /** Maps quarter coordinates ({@code ax, az >= 0}) to a real quarter of the map. */
    record Quarter(int sx, int sz) {

        static Quarter of(Side side, Lane lane) {
            return new Quarter(ToyLayout.sign(side), ToyLayout.sign(lane));
        }

        int x(int ax) {
            return sx * ax;
        }

        int z(int az) {
            return sz * az;
        }

        void set(MapCanvas canvas, int ax, int y, int az, BlockSpec spec) {
            canvas.set(x(ax), y, z(az), spec);
        }

        /**
         * Sets a block given as it would stand in the BLUE/TOP-like quarter with {@code +ax} = east and {@code +az} =
         * south: its facing (and a stair's corner shape) is mirrored into this quarter.
         */
        void place(MapCanvas canvas, int ax, int y, int az, BlockSpec spec) {
            canvas.set(x(ax), y, z(az), mirrored(spec, sx < 0, sz < 0));
        }
    }

    /** {@code spec} mirrored across x ({@code mx}) and/or z ({@code mz}): facing and stair corners follow. */
    static BlockSpec mirrored(BlockSpec spec, boolean mx, boolean mz) {
        BlockSpec out = spec;
        BlockFace f = spec.facing();
        if (f != null && (mx && (f == BlockFace.EAST || f == BlockFace.WEST)
                || mz && (f == BlockFace.NORTH || f == BlockFace.SOUTH))) {
            out = out.facing(f.getOppositeFace());
        }
        Stairs.Shape shape = spec.shape();
        if (shape != null && mx != mz) {
            out = out.shape(switch (shape) {
                case OUTER_LEFT -> Stairs.Shape.OUTER_RIGHT;
                case OUTER_RIGHT -> Stairs.Shape.OUTER_LEFT;
                case INNER_LEFT -> Stairs.Shape.INNER_RIGHT;
                case INNER_RIGHT -> Stairs.Shape.INNER_LEFT;
                case STRAIGHT -> Stairs.Shape.STRAIGHT;
            });
        }
        return out;
    }

    // ------------------------------------------------------------------ detail shapes

    /** Straight stairs of {@code material}: its tall back towards {@code facing}, upside down if {@code top}. */
    static BlockSpec stairs(Material material, BlockFace facing, boolean top) {
        return BlockSpec.of(material).facing(facing).half(top ? Bisected.Half.TOP : Bisected.Half.BOTTOM)
                .shape(Stairs.Shape.STRAIGHT);
    }

    /** The stairs of a toy colour's second tone, or null for a colour without one (light blue, mint, blue). */
    static @Nullable Material stairsOf(BlockSpec colour) {
        if (colour.equals(WHITE)) {
            return WHITE_STAIRS;
        }
        if (colour.equals(PINK)) {
            return PINK_STAIRS;
        }
        if (colour.equals(YELLOW)) {
            return YELLOW_STAIRS;
        }
        if (colour.equals(RED)) {
            return RED_STAIRS;
        }
        if (colour.equals(WOOD)) {
            return WOOD_STAIRS;
        }
        return null;
    }

    /** A trapdoor matching a toy colour: cherry for pink and red, birch for the rest. */
    static Material trapdoorOf(BlockSpec colour) {
        return colour.equals(PINK) || colour.equals(RED) ? Material.CHERRY_TRAPDOOR : Material.BIRCH_TRAPDOOR;
    }

    /**
     * A stair bevelling the rim of something round or square: its tall part towards the middle, which lies
     * {@code -dx, -dz} away (quarter coordinates); on a diagonal it is an outer corner, so rims read as curves.
     */
    static BlockSpec bevel(Material material, double dx, double dz, boolean top) {
        double adx = Math.abs(dx);
        double adz = Math.abs(dz);
        BlockFace fx = dx > 0 ? BlockFace.WEST : BlockFace.EAST;
        BlockFace fz = dz > 0 ? BlockFace.NORTH : BlockFace.SOUTH;
        if (adz < 0.5 * adx) {
            return stairs(material, fx, top);
        }
        if (adx < 0.5 * adz) {
            return stairs(material, fz, top);
        }
        return stairs(material, fz, top).shape(left(fz) == fx ? Stairs.Shape.OUTER_LEFT : Stairs.Shape.OUTER_RIGHT);
    }

    /** The face to the left of {@code facing} (as a stair's corner shapes count it). */
    static BlockFace left(BlockFace facing) {
        return switch (facing) {
            case NORTH -> BlockFace.WEST;
            case WEST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.EAST;
            case EAST -> BlockFace.NORTH;
            default -> facing;
        };
    }

    /**
     * An upright picture in quarter coordinates: {@code rows} bottom first, one character per block along {@code +ax}
     * ({@code alongX}, at {@code az = fixed}) or {@code +az} (at {@code ax = fixed}) from {@code u0}, the bottom row at
     * {@code y0}; {@code '.'} leaves the block alone, other characters come from {@code key}. Where {@code round}, the
     * outer corners of colours with a second tone become stairs, so the outline reads as a curve.
     */
    static void plane(MapCanvas canvas, Quarter q, boolean alongX, int u0, int fixed, int y0, String[] rows,
                      boolean round, Map<Character, BlockSpec> key) {
        BlockFace plus = alongX ? BlockFace.EAST : BlockFace.SOUTH;
        BlockFace minus = plus.getOppositeFace();
        for (int r = 0; r < rows.length; r++) {
            for (int i = 0; i < rows[r].length(); i++) {
                char c = rows[r].charAt(i);
                if (c == '.' || c == ' ') {
                    continue;
                }
                BlockSpec spec = key.get(c);
                Material step = round ? stairsOf(spec) : null;
                if (step != null) {
                    boolean l = filled(rows, i - 1, r);
                    boolean rt = filled(rows, i + 1, r);
                    boolean up = filled(rows, i, r + 1);
                    boolean down = r == 0 ? y0 == G + 1 : filled(rows, i, r - 1);
                    if (!up && !l && rt && down) {
                        spec = stairs(step, plus, false);
                    } else if (!up && !rt && l && down) {
                        spec = stairs(step, minus, false);
                    } else if (!down && !l && rt && up) {
                        spec = stairs(step, plus, true);
                    } else if (!down && !rt && l && up) {
                        spec = stairs(step, minus, true);
                    }
                }
                int u = u0 + i;
                q.place(canvas, alongX ? u : fixed, y0 + r, alongX ? fixed : u, spec);
            }
        }
    }

    private static boolean filled(String[] rows, int i, int r) {
        if (r < 0 || r >= rows.length || i < 0 || i >= rows[r].length()) {
            return false;
        }
        char c = rows[r].charAt(i);
        return c != '.' && c != ' ';
    }

    /**
     * A round toy turned on a lathe around {@code (cax, caz)}: layer {@code i} (from {@code y0} up) is a disc of
     * {@code radius[i]} in {@code colour[i]}. Rim blocks with nothing above (or below) them are bevelled with the
     * colour's stairs, so the profile is smooth.
     */
    static void lathe(MapCanvas canvas, Quarter q, int cax, int caz, int y0, double[] radius, BlockSpec[] colour) {
        for (int i = 0; i < radius.length; i++) {
            int r = (int) Math.ceil(radius[i]);
            double above = i + 1 < radius.length ? radius[i + 1] : -1;
            double below = i > 0 ? radius[i - 1] : Double.MAX_VALUE;
            Material step = stairsOf(colour[i]);
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    double d = Math.hypot(dx, dz);
                    if (d > radius[i]) {
                        continue;
                    }
                    BlockSpec spec = colour[i];
                    if (step != null && d > radius[i] - 1 && d > 0) {
                        if (d > above) {
                            spec = bevel(step, dx, dz, false);
                        } else if (d > below) {
                            spec = bevel(step, dx, dz, true);
                        }
                    }
                    q.place(canvas, cax + dx, y0 + i, caz + dz, spec);
                }
            }
        }
    }

    /** A small deterministic scatter in quarter coordinates (so every quarter gets the same). */
    static boolean speckle(int ax, int y, int az, int oneIn) {
        return Math.floorMod(ax * 7 + az * 13 + y * 5 + ax * az, oneIn) == 0;
    }

    // ------------------------------------------------------------------ props

    /**
     * A column of a toy brick wall: {@code height} blocks of {@code colour}, the top course studded with white every
     * other block (by the real column, so walls of both halves look alike).
     */
    static void brick(MapCanvas canvas, int x, int z, int height, BlockSpec colour) {
        for (int i = 1; i <= height; i++) {
            boolean stud = i == height && Math.floorMod(x + z, 2) == 0;
            canvas.set(x, G + i, z, stud ? WHITE : colour);
        }
    }

    /**
     * A toy brick wall along a segment in quarter coordinates: three blocks high, two at its rounded edge, so nobody
     * hops onto it.
     */
    static void wall(MapCanvas canvas, Quarter q, double ax1, double az1, double ax2, double az2, double radius,
                     BlockSpec colour) {
        int minX = (int) Math.floor(Math.min(ax1, ax2) - radius);
        int maxX = (int) Math.ceil(Math.max(ax1, ax2) + radius);
        int minZ = (int) Math.floor(Math.min(az1, az2) - radius);
        int maxZ = (int) Math.ceil(Math.max(az1, az2) + radius);
        for (int ax = minX; ax <= maxX; ax++) {
            for (int az = minZ; az <= maxZ; az++) {
                double d = segment(ax, az, ax1, az1, ax2, az2);
                if (d > radius) {
                    continue;
                }
                brick(canvas, q.x(ax), q.z(az), d <= radius - 1 ? 3 : 2, colour);
            }
        }
    }

    /**
     * A soft round toy resting on the ground (a squashed ball), of cotton candy or sponge. A sponge bush is two-tone
     * (a damp, darker underside) with a few pink blossoms on top.
     */
    static void ball(MapCanvas canvas, Quarter q, int ax, int az, double radius, BlockSpec spec) {
        int r = (int) Math.ceil(radius);
        double height = radius * 0.9;
        boolean sponge = spec.equals(SPONGE);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int top = -1;
                for (int dy = 0; dy <= r * 2; dy++) {
                    double ny = (dy + 0.5 - height) / height;
                    double nx = dx / (radius + 0.25);
                    double nz = dz / (radius + 0.25);
                    if (nx * nx + ny * ny + nz * nz <= 1) {
                        q.set(canvas, ax + dx, G + 1 + dy, az + dz, sponge && dy == 0 ? SPONGE_SHADE : spec);
                        top = dy;
                    }
                }
                if (sponge && top >= 1 && speckle(ax + dx, 0, az + dz, 3)) {
                    q.place(canvas, ax + dx, G + 2 + top, az + dz, BLOSSOM);
                }
            }
        }
    }

    /**
     * A cotton candy tree: a thin white stick of {@code stick} blocks and a cotton ball of {@code radius} on it, high
     * enough to walk under; three tones of fluff (a deeper pink underside, pink, white tufts on top).
     */
    static void cottonCandy(MapCanvas canvas, Quarter q, int ax, int az, int stick, double radius) {
        for (int i = 1; i <= stick; i++) {
            q.place(canvas, ax, G + i, az, STICK);
        }
        int r = (int) Math.ceil(radius);
        int cy = G + stick + r;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -r; dy <= r; dy++) {
                    if (!inFluff(dx, dy, dz, radius)) {
                        continue;
                    }
                    BlockSpec spec = COTTON;
                    if (!inFluff(dx, dy - 1, dz, radius)) {
                        spec = PINK;
                    } else if (dy >= 1 && !inFluff(dx, dy + 1, dz, radius) && speckle(ax + dx, dy, az + dz, 3)) {
                        spec = WHITE;
                    }
                    q.set(canvas, ax + dx, cy + dy, az + dz, spec);
                }
            }
        }
    }

    private static boolean inFluff(int dx, int dy, int dz, double radius) {
        double ny = dy / (radius * 0.8);
        return dx * dx / (radius * radius) + dz * dz / (radius * radius) + ny * ny <= 1;
    }

    /**
     * A candy cane: a red and white striped post with its hook bent towards {@code (hookAx, hookAz)}: round over the
     * top (stairs) and two blocks down, its tip rounded off too.
     */
    static void candyCane(MapCanvas canvas, Quarter q, int ax, int az, int hookAx, int hookAz, int height) {
        for (int i = 0; i < height; i++) {
            q.set(canvas, ax, G + 1 + i, az, stripe(i));
        }
        boolean alongX = Math.abs(hookAx - ax) >= Math.abs(hookAz - az);
        int sx = alongX ? Integer.signum(hookAx - ax) : 0;
        int sz = alongX ? 0 : Integer.signum(hookAz - az);
        BlockFace towards = alongX ? (sx > 0 ? BlockFace.EAST : BlockFace.WEST)
                : (sz > 0 ? BlockFace.SOUTH : BlockFace.NORTH);
        BlockFace back = towards.getOppositeFace();
        int top = G + height;
        int hx = ax + sx;
        int hz = az + sz;
        q.place(canvas, ax, top + 1, az, stairs(stairsOf(stripe(height)), towards, false));
        q.place(canvas, hx, top + 1, hz, stairs(stairsOf(stripe(height + 1)), back, false));
        q.set(canvas, hx, top, hz, stripe(height + 2));
        q.place(canvas, hx, top - 1, hz, stairs(stairsOf(stripe(height + 3)), back, true));
    }

    private static BlockSpec stripe(int i) {
        return i % 2 == 0 ? RED : WHITE;
    }

    /** A lollipop lamp with its sweet across x (see {@link #lollipop(MapCanvas, Quarter, int, int, int, boolean)}). */
    static void lollipop(MapCanvas canvas, Quarter q, int ax, int az, int height) {
        lollipop(canvas, q, ax, az, height, true);
    }

    /** The swirl of a lollipop's sweet, bottom row first: red, white, the glowing middle. */
    private static final String[] SWEET = {"..WWR..", ".WWWWR.", "WRRRWRR", "RRWgWRR", "RRWRRRW", ".RWWWW.",
        "..RWW.."};

    /**
     * A lollipop lamp: a thin glowing white stick of {@code height} on a little white foot and a round sweet on top
     * (seven across, along x or z: a red and white swirl around a glowing middle, its outline rounded with stairs of
     * the second tones), so the verges are lit at night too.
     */
    static void lollipop(MapCanvas canvas, Quarter q, int ax, int az, int height, boolean alongX) {
        q.place(canvas, ax, G + 1, az, WHITE_SLAB);
        for (int i = 2; i <= height; i++) {
            q.place(canvas, ax, G + i, az, STICK);
        }
        plane(canvas, q, alongX, (alongX ? ax : az) - 3, alongX ? az : ax, G + height + 1, SWEET, true,
                Map.of('R', RED, 'W', WHITE, 'g', GLOW));
    }

    /**
     * A toy building block (an alphabet block): a cube of {@code size} with its low corner at {@code ax, y, az}; from
     * size 3 a white frame along its edges (the top edges bevelled) and a panel of a second colour on top.
     */
    static void toyBlock(MapCanvas canvas, Quarter q, int ax, int y, int az, int size, BlockSpec spec) {
        if (size < 3) {
            box(canvas, q, ax, y, az, size, size, size, spec);
            return;
        }
        BlockSpec accent = accent(spec);
        double mid = (size - 1) / 2.0;
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                for (int k = 0; k < size; k++) {
                    int edges = (i == 0 || i == size - 1 ? 1 : 0) + (j == 0 || j == size - 1 ? 1 : 0)
                            + (k == 0 || k == size - 1 ? 1 : 0);
                    BlockSpec block;
                    if (edges >= 2 && j == size - 1) {
                        block = bevel(WHITE_STAIRS, i - mid, k - mid, false);
                    } else if (edges >= 2) {
                        block = WHITE;
                    } else if (edges == 1 && j == size - 1) {
                        block = accent;
                    } else {
                        block = spec;
                    }
                    q.place(canvas, ax + i, y + j, az + k, block);
                }
            }
        }
    }

    /** The letter colour on a toy block of {@code spec}. */
    static BlockSpec accent(BlockSpec spec) {
        if (spec.equals(YELLOW)) {
            return RED;
        }
        if (spec.equals(SKY)) {
            return YELLOW;
        }
        if (spec.equals(PINK)) {
            return SKY;
        }
        return YELLOW;
    }

    /**
     * A triangular toy block (a roof prism) {@code w} wide and {@code d} deep with its low corner at {@code ax, y, az}:
     * the ridge runs along z, the slopes are stairs of the colour's second tone.
     */
    static void prism(MapCanvas canvas, Quarter q, int ax, int y, int az, int w, int d, BlockSpec colour) {
        Material step = stairsOf(colour);
        for (int layer = 0; layer * 2 < w; layer++) {
            int from = ax + layer;
            int to = ax + w - 1 - layer;
            for (int x = from; x <= to; x++) {
                for (int k = 0; k < d; k++) {
                    BlockSpec spec = colour;
                    if (step != null && x == from && x != to) {
                        spec = stairs(step, BlockFace.EAST, false);
                    } else if (step != null && x == to && x != from) {
                        spec = stairs(step, BlockFace.WEST, false);
                    }
                    q.place(canvas, x, y + layer, az + k, spec);
                }
            }
        }
    }

    /** A box of {@code sx} by {@code sy} by {@code sz} blocks with its low corner at {@code ax, y, az}. */
    static void box(MapCanvas canvas, Quarter q, int ax, int y, int az, int sx, int sy, int sz, BlockSpec spec) {
        for (int dx = 0; dx < sx; dx++) {
            for (int dz = 0; dz < sz; dz++) {
                for (int dy = 0; dy < sy; dy++) {
                    q.set(canvas, ax + dx, y + dy, az + dz, spec);
                }
            }
        }
    }

    /**
     * A toy box: an open chest of {@code colour}, {@code sx} by {@code sz} and {@code height} high, white corner posts
     * and rim, full of toys (a few peeking out), its lid standing open along the back ({@code +az}) and a yellow latch
     * on the front.
     */
    static void toyBox(MapCanvas canvas, Quarter q, int ax, int az, int sx, int sz, int height, BlockSpec colour) {
        int top = G + height;
        for (int dx = 0; dx < sx; dx++) {
            for (int dz = 0; dz < sz; dz++) {
                boolean ex = dx == 0 || dx == sx - 1;
                boolean ez = dz == 0 || dz == sz - 1;
                for (int y = G + 1; y <= top; y++) {
                    BlockSpec spec;
                    if (ex && ez || (ex || ez) && y == top) {
                        spec = WHITE;
                    } else if (ex || ez || y < top) {
                        spec = colour;
                    } else {
                        // The toys inside, just below the rim.
                        spec = speckle(ax + dx, y, az + dz, 2) ? YELLOW : SKY;
                    }
                    q.set(canvas, ax + dx, y, az + dz, spec);
                }
            }
        }
        q.set(canvas, ax + 1, top + 1, az + 1, YELLOW);
        if (sx > 3) {
            q.set(canvas, ax + sx - 2, top + 1, az + sz - 2, SKY);
        }
        // The lid, swung open against the back rim.
        BlockSpec lid = BlockSpec.of(trapdoorOf(colour)).facing(BlockFace.NORTH).open(true)
                .half(Bisected.Half.BOTTOM);
        for (int dx = 0; dx < sx; dx++) {
            for (int i = 1; i <= Math.min(2, sz - 1); i++) {
                q.place(canvas, ax + dx, top + i, az + sz - 1, lid);
            }
        }
        // The latch: a yellow button on the front of the rim.
        q.place(canvas, ax + sx / 2, top, az - 1, BlockSpec.of(Material.BAMBOO_BUTTON).facing(BlockFace.NORTH));
    }

    /** Distance from a point to a segment. */
    static double segment(double px, double pz, double x1, double z1, double x2, double z2) {
        double dx = x2 - x1;
        double dz = z2 - z1;
        double len = dx * dx + dz * dz;
        double t = len == 0 ? 0 : Math.max(0, Math.min(1, ((px - x1) * dx + (pz - z1) * dz) / len));
        return Math.hypot(px - (x1 + t * dx), pz - (z1 + t * dz));
    }
}
