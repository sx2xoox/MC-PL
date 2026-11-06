package io.github.sx2xoox.mcpl.core.map.toykingdom;

import java.util.Map;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
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
 * floorboards) and the toy props (toy brick walls, toy blocks; the bigger toys are resource-pack models,
 * {@link Quarter#prop}). Props are placed in quarter coordinates ({@code ax, az >= 0}) through a
 * {@link Quarter}, so both halves (and both lanes) are mirror images; shaped detail blocks (stairs, slabs, trapdoors,
 * buttons, banners) go through {@link Quarter#place}, which mirrors their facing too. The detail blocks are a second
 * tone of the toy colours (cherry = pink, quartz = white, bamboo = yellow, birch = the floorboards, red nether brick =
 * deep red) that round off corners, slopes and rims.
 */
final class ToyKit {

    private ToyKit() {
    }

    static final int G = ToyLayout.GROUND_Y;

    /** The play mat: big interlocking puzzle tiles of two mints, now and then a tile of a bluer mint. */
    static final BlockSpec MAT = BlockSpec.of(Material.LIME_WOOL);
    static final BlockSpec MAT_TILE = BlockSpec.of(Material.LIME_CONCRETE);
    static final BlockSpec MAT_ODD = BlockSpec.of(Material.OXIDIZED_COPPER);
    /** The second tone of each brick road (a brighter brick here and there). */
    static final BlockSpec YELLOW_BRICK = BlockSpec.of(Material.YELLOW_WOOL);
    static final BlockSpec SKY_BRICK = BlockSpec.of(Material.LIGHT_BLUE_WOOL);
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
    /** The glowing sweet in the middle of a lollipop lamp (and the clock's hour marks). */
    static final BlockSpec GLOW = BlockSpec.of(Material.PEARLESCENT_FROGLIGHT);

    // Detail blocks: the second tone of each toy colour, for slopes, rims and rounded corners.
    static final Material WHITE_STAIRS = Material.QUARTZ_STAIRS;
    static final Material PINK_STAIRS = Material.CHERRY_STAIRS;
    static final Material YELLOW_STAIRS = Material.BAMBOO_STAIRS;
    static final Material RED_STAIRS = Material.RED_NETHER_BRICK_STAIRS;
    static final Material WOOD_STAIRS = Material.BIRCH_STAIRS;
    static final BlockSpec WHITE_SLAB = BlockSpec.of(Material.QUARTZ_SLAB).slab(Slab.Type.BOTTOM);
    static final BlockSpec WOOD_SLAB_TOP = BlockSpec.of(Material.BIRCH_SLAB).slab(Slab.Type.TOP);
    static final BlockSpec FENCE = BlockSpec.of(Material.BIRCH_FENCE);
    /** A thin white stick that glows (a ballerina's leg, the fountain's sparkle). */
    static final BlockSpec STICK = BlockSpec.of(Material.END_ROD).facing(BlockFace.UP);
    static final BlockSpec LANTERN_HANGING = BlockSpec.of(Material.LANTERN).hanging(true);
    /** Pink blossoms on a topiary (a carpet: it holds on any block). */
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

    /** The puzzle tiles of the play mat: 7 x 7, centred on the middle so both halves mirror. */
    static final int TILE = 7;

    /**
     * The play mat at a column: 7 x 7 puzzle tiles of two mints (checkered, about one in seven of the bluer mint) that
     * interlock: on every edge one tile pushes a knob (a one block neck and a three block head) into its neighbour, so
     * the seams read as a foam puzzle mat. Mirror symmetric across x.
     */
    static BlockSpec mat(int x, int z) {
        int h = TILE / 2;
        int tx = Math.floorDiv(x + h, TILE);
        int tz = Math.floorDiv(z + h, TILE);
        int u = x + h - tx * TILE;
        int v = z + h - tz * TILE;
        int last = TILE - 1;
        if (knob(u, v) && xKnobInto(tx - 1, tx, tz) == tx) {
            return tile(tx - 1, tz);
        }
        if (knob(last - u, v) && xKnobInto(tx, tx + 1, tz) == tx) {
            return tile(tx + 1, tz);
        }
        if (knob(v, u) && zKnobInto(tz - 1, tz, tx) == tz) {
            return tile(tx, tz - 1);
        }
        if (knob(last - v, u) && zKnobInto(tz, tz + 1, tx) == tz) {
            return tile(tx, tz + 1);
        }
        return tile(tx, tz);
    }

    /** True for the knob's place {@code depth} blocks in from a tile edge, {@code across} along it. */
    private static boolean knob(int depth, int across) {
        int mid = TILE / 2;
        return depth == 0 && across == mid || depth == 1 && Math.abs(across - mid) <= 1;
    }

    /** The tile an x edge's knob points into: away from the middle on even rows, towards it on odd rows. */
    private static int xKnobInto(int a, int b, int tz) {
        int far = Math.abs(b) > Math.abs(a) ? b : a;
        int near = far == a ? b : a;
        return Math.floorMod(tz, 2) == 0 ? far : near;
    }

    /** The tile a z edge's knob points into. */
    private static int zKnobInto(int a, int b, int tx) {
        return Math.floorMod(Math.abs(tx) + a, 2) == 0 ? b : a;
    }

    /** The colour of a whole puzzle tile. */
    private static BlockSpec tile(int tx, int tz) {
        int hash = Math.abs(tx) * 0x9E3779B1 ^ tz * 0x85EBCA6B;
        hash ^= hash >>> 13;
        hash *= 0xC2B2AE35;
        hash ^= hash >>> 16;
        if (Math.floorMod(hash, 7) == 0) {
            return MAT_ODD;
        }
        return Math.floorMod(tx + tz, 2) == 0 ? MAT : MAT_TILE;
    }

    /** True for a column of plain play mat (any of its tones). */
    static boolean isMat(@Nullable BlockSpec spec) {
        return spec != null && (spec.equals(MAT) || spec.equals(MAT_TILE) || spec.equals(MAT_ODD));
    }

    /**
     * A brick of a road at a column: bricks two long in running bond, about two in five of the road's brighter tone, so
     * the road reads as laid bricks rather than paint.
     */
    static BlockSpec roadBrick(Lane lane, int x, int z) {
        int ax = Math.abs(x);
        int brick = Math.floorDiv(ax + Math.floorMod(z, 2), 2);
        boolean bright = Math.floorMod(brick * 7 + z * 3 + brick * z, 5) < 2;
        return bright ? (lane == Lane.TOP ? YELLOW_BRICK : SKY_BRICK) : road(lane);
    }

    /** A dotted white stitch (every third block) along the seams of the mat beside a road or a ring. */
    static boolean stitch(int x, int z) {
        return Math.floorMod(Math.abs(x) + Math.abs(z), 3) == 0;
    }

    /** The angle (0..1 of a turn) of a column around the nearest tower of its lane half. */
    static double towerAngle(int x, int z) {
        int ax = Math.abs(x);
        int az = Math.abs(z);
        int[] best = ToyLayout.TOWERS[0];
        for (int[] t : ToyLayout.TOWERS) {
            if (Math.hypot(ax - t[0], az + t[1]) < Math.hypot(ax - best[0], az + best[1])) {
                best = t;
            }
        }
        double a = Math.atan2(az + best[1], ax - best[0]) / (2 * Math.PI);
        return a - Math.floor(a);
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
            // The sandbox's frame: wooden boards, painted in the toy colours by turns around the ring.
            double turn = Math.atan2(z, ax) / (2 * Math.PI) + 0.5;
            int board = (int) Math.floor(turn * 20);
            canvas.set(x, G, z, board % 2 == 0 ? WOOD : TOY_COLOURS[board / 2 % TOY_COLOURS.length]);
            return;
        }
        double river = ToyLayout.river(x, z);
        if (river <= ToyLayout.RIVER_HALF) {
            canvas.set(x, G - 1, z, SAND);
            canvas.set(x, G, z, WATER);
            return;
        }
        if (river <= ToyLayout.RIVER_HALF + 1) {
            // The bank: an edging of toy bricks, two blocks of each colour in turn along the water.
            canvas.set(x, G, z, TOY_COLOURS[Math.floorMod(Math.floorDiv(Math.abs(z), 2), TOY_COLOURS.length)]);
            return;
        }
        Lane lane = ToyLayout.laneOf(z);
        int az = Math.abs(z);
        if (Math.abs(ax - ToyLayout.GATE_AX) <= ToyLayout.ROAD_HALF && az > ToyLayout.CASTLE_HALF_Z
                && az <= ToyLayout.CASTLE_HALF_Z + 3) {
            // A doormat before each lane gate: the road's colour checkered with the owner's on both sides of the
            // road's middle.
            boolean check = Math.abs(ax - ToyLayout.GATE_AX) > 1 && Math.floorMod(ax + az, 2) == 0;
            canvas.set(x, G, z, check ? strong(side) : roadBrick(lane, x, z));
            return;
        }
        double tower = ToyLayout.towerDistance(x, z);
        double d = ToyLayout.laneDistance(lane, x, z);
        BlockSpec top;
        if (tower <= ToyLayout.TOWER_RADIUS + 0.5) {
            // The plaza: bricks with a dashed ring in the owner's colour where the tower's base will stand.
            boolean dash = tower > 5.5 && tower <= 6.5 && Math.floorMod((int) Math.floor(towerAngle(x, z) * 24), 2) == 0;
            top = dash ? strong(side) : roadBrick(lane, x, z);
        } else if (tower <= ToyLayout.RING_OUTER) {
            top = strong(side);
        } else if (d <= ToyLayout.ROAD_HALF) {
            top = roadBrick(lane, x, z);
        } else if (d <= ToyLayout.EDGE_HALF) {
            top = WHITE;
        } else if ((d <= ToyLayout.EDGE_HALF + 1 || tower <= ToyLayout.RING_OUTER + 1) && stitch(x, z)) {
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
        if (ax == ToyLayout.CASTLE_FRONT + 2 || ax == ToyLayout.CASTLE_BACK - 2
                || az == ToyLayout.CASTLE_HALF_Z - 2) {
            // A skirting of the pastel along the foot of the walls frames the checker.
            return pastel(side);
        }
        return Math.floorMod(Math.floorDiv(ax, 2) + Math.floorDiv(z, 2), 2) == 0 ? WOOD : WHITE;
    }


    // ------------------------------------------------------------------ toys left on the mat

    /** The camps' centres on the BLUE half ({@code |x|, z}) and their rugs' colours (see {@link ToyJungleZone}). */
    static final int[][] CAMPS = {{52, -22}, {34, -28}, {16, -26}, {52, 22}, {34, 28}, {16, 26}};
    static final BlockSpec[] CAMP_COLOURS = {PINK, YELLOW, SKY, YELLOW, PINK, SKY};
    /** The camps' rugs: radius (see {@link ToyJungleZone}) and how far their confetti spreads beyond it. */
    static final double CAMP_RUG = 6.5;
    static final double CAMP_CONFETTI = 11;
    static final BlockSpec[] CARPETS = {BlockSpec.of(Material.PINK_CARPET), BlockSpec.of(Material.YELLOW_CARPET),
        BlockSpec.of(Material.LIGHT_BLUE_CARPET), BlockSpec.of(Material.WHITE_CARPET)};

    /**
     * Little toy pieces left lying flat on the play mat after the props are built: a carpet square in a toy colour
     * here and there, and around every camp a sprinkle of confetti in its rug's colour, so the camp's theme spills onto
     * the mat. Only on free plain mat, well off the roads, the tower rings and the camps' rugs (all flat, nothing to
     * trip over). Mirror symmetric across x.
     */
    static void scatter(MapCanvas canvas, int x, int z) {
        if (!isMat(canvas.get(x, G, z)) || !isAir(canvas.get(x, G + 1, z)) || !isAir(canvas.get(x, G + 2, z))) {
            return;
        }
        if (ToyLayout.laneDistance(ToyLayout.laneOf(z), x, z) <= ToyLayout.EDGE_HALF + 2.5
                || ToyLayout.towerDistance(x, z) <= ToyLayout.RING_OUTER + 2.5) {
            return;
        }
        int ax = Math.abs(x);
        int hash = Math.floorMod(ax * 92821 + z * 68917 + ax * z * 31 + (ax ^ z) * 13, 9973);
        for (int i = 0; i < CAMPS.length; i++) {
            double d = Math.hypot(ax - CAMPS[i][0], z - CAMPS[i][1]);
            if (d <= CAMP_RUG + 1) {
                return;
            }
            if (d <= CAMP_CONFETTI && hash % 9 == 0) {
                canvas.set(x, G + 1, z, CARPETS[colourIndex(CAMP_COLOURS[i])]);
                return;
            }
        }
        if (hash % 83 == 0) {
            canvas.set(x, G + 1, z, CARPETS[hash / 83 % CARPETS.length]);
        }
    }

    private static int colourIndex(BlockSpec colour) {
        return colour.equals(PINK) ? 0 : colour.equals(YELLOW) ? 1 : colour.equals(SKY) ? 2 : 3;
    }

    private static boolean isAir(@Nullable BlockSpec spec) {
        return spec == null || spec.isAir();
    }

    /** {@link #scatter} over every column of a zone. */
    static void scatter(MapCanvas canvas, io.github.sx2xoox.mcpl.core.map.MapZone zone) {
        int r = ToyLayout.BUILD_RADIUS;
        for (int x = -r; x <= r; x++) {
            for (int z = -ToyLayout.HALF_Z; z <= ToyLayout.HALF_Z; z++) {
                if (zone.contains(x, z)) {
                    scatter(canvas, x, z);
                }
            }
        }
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

        /**
         * The real x of a point given in continuous quarter coordinates (block {@code ax} spans {@code ax .. ax + 1},
         * so its middle is {@code ax + 0.5}): mirrored with the blocks.
         */
        double px(double ax) {
            return sx * (ax - 0.5) + 0.5;
        }

        /** The real z of a point in continuous quarter coordinates (see {@link #px}). */
        double pz(double az) {
            return sz * (az - 0.5) + 0.5;
        }

        /** The Minecraft yaw (0 = +z) of the direction {@code (dax, daz)} given in quarter coordinates. */
        float yaw(double dax, double daz) {
            return (float) Math.toDegrees(Math.atan2(-sx * dax, sz * daz));
        }

        /** A model prop standing at the continuous quarter point {@code (ax, az)}, its front towards {@code yaw}. */
        LaneSites.ModelProp prop(String model, double ax, double y, double az, float yaw, LaneSites.PropMotion motion) {
            return new LaneSites.ModelProp("prop/" + model, px(ax), y, pz(az), yaw, 1.0, motion);
        }
    }

    /** Invisible footing under a model prop (a minimal hitbox where its blocks used to stand). */
    static final BlockSpec FOOTING = BlockSpec.of(Material.BARRIER);
    /** The footing where it stands in water (the river): waterlogged, so the water stays. */
    static final BlockSpec FOOTING_WET = FOOTING.waterlogged(true);

    /** Sets a footing (quarter coordinates), waterlogged where it takes the place of water. */
    static void footing(MapCanvas canvas, Quarter q, int ax, int y, int az) {
        BlockSpec there = canvas.get(q.x(ax), y, q.z(az));
        q.set(canvas, ax, y, az, there != null && there.material() == Material.WATER ? FOOTING_WET : FOOTING);
    }
    /** An invisible light where a model prop glows (a lollipop lamp's sweet). */
    static final BlockSpec PROP_LIGHT = BlockSpec.of(Material.LIGHT);

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

    /** Distance from a point to a segment. */
    static double segment(double px, double pz, double x1, double z1, double x2, double z2) {
        double dx = x2 - x1;
        double dz = z2 - z1;
        double len = dx * dx + dz * dz;
        double t = len == 0 ? 0 : Math.max(0, Math.min(1, ((px - x1) * dx + (pz - z1) * dz) / len));
        return Math.hypot(px - (x1 + t * dx), pz - (z1 + t * dz));
    }
}
