package io.github.sx2xoox.mcpl.core.map.toykingdom;

import static io.github.sx2xoox.mcpl.core.map.toykingdom.ToyKit.G;

import java.util.Map;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.MapZone;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites.Lane;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites.Side;
import io.github.sx2xoox.mcpl.core.map.toykingdom.ToyKit.Quarter;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

/**
 * The jungle between the lanes, a toy room on the play mat: a few long walls of toy bricks shape a middle corridor
 * (castle front gate to the dragon's sandbox, starting on the castle's checkered mat) and two rows of clearings, one
 * per objective. Every clearing has its own round rug (a white ring in it) and its own landmark just outside the open
 * area: jelly cubes on a pink rug (젤리 무리), a cheese wedge with a wind-up key on a yellow one (태엽 쥐 떼), a big toy
 * box on a yellow one (곰인형 대장), rabbit ears with a pocket watch between them on a pink one (시계 토끼), a candy
 * fountain on a light blue one (사탕 샘물) and a telescope tower on a light blue one (망원경 탑). Piles of toy boxes and
 * cotton candy trees fill the corners in between (with a box of crayons on the TOP side and a jar of marbles on the
 * BOTTOM side), and giant toys stand in the far corner pockets beyond the lanes' bends (a stack of alphabet blocks by
 * the TOP lane, a spinning top by the BOTTOM lane). The props are shaped with stairs, slabs and other detail blocks in
 * a second tone of their colours ({@link ToyKit}).
 */
final class ToyJungleZone implements MapZone {

    /** Toy brick walls in quarter coordinates (both sides, both halves): {ax1, az1, ax2, az2, radius}. */
    static final double[][] WALLS = {
        {24, 12, 34, 12, 1.5},
        {46, 12, 58, 12, 1.5},
        {43, 21, 43, 35, 1.5},
        {25, 21, 25, 35, 1.5},
    };
    private static final BlockSpec[] WALL_COLOURS = {ToyKit.YELLOW, ToyKit.PINK, ToyKit.SKY, ToyKit.SKY};

    /** Cotton candy trees in quarter coordinates: {ax, az, radius}. */
    static final double[][] TREES = {{10, 17, 3}};

    /** Rug radius and its white ring. */
    private static final double RUG = 6.5;

    @Override
    public String name() {
        return "jungle";
    }

    @Override
    public boolean contains(int x, int z) {
        return ToyLayout.partOf(x, z).equals(name());
    }

    @Override
    public void build(MapCanvas canvas) {
        int r = ToyLayout.BUILD_RADIUS;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                if (contains(x, z)) {
                    ToyKit.ground(canvas, x, z);
                }
            }
        }
        for (Side side : Side.values()) {
            Quarter top = Quarter.of(side, Lane.TOP);
            Quarter bottom = Quarter.of(side, Lane.BOTTOM);
            rug(canvas, top, 52, 22, ToyKit.PINK);
            rug(canvas, top, 34, 28, ToyKit.YELLOW);
            rug(canvas, top, 16, 26, ToyKit.SKY);
            rug(canvas, bottom, 52, 22, ToyKit.YELLOW);
            rug(canvas, bottom, 34, 28, ToyKit.PINK);
            rug(canvas, bottom, 16, 26, ToyKit.SKY);
            for (Lane lane : Lane.values()) {
                Quarter q = Quarter.of(side, lane);
                for (int i = 0; i < WALLS.length; i++) {
                    double[] w = WALLS[i];
                    ToyKit.wall(canvas, q, w[0], w[1], w[2], w[3], w[4], WALL_COLOURS[i]);
                }
                for (double[] t : TREES) {
                    ToyKit.cottonCandy(canvas, q, (int) t[0], (int) t[1], 2, t[2]);
                }
                boxPiles(canvas, q);
            }
            blockStack(canvas, top);
            spinningTop(canvas, bottom);
            jellies(canvas, top);
            toyBox(canvas, top);
            fountain(canvas, top);
            cheese(canvas, bottom);
            rabbitEars(canvas, bottom);
            telescope(canvas, bottom);
            crayonBox(canvas, top, SMALL_TOY_AX - 1, SMALL_TOY_AZ - 1);
            marbleJar(canvas, bottom, SMALL_TOY_AX, SMALL_TOY_AZ);
        }
        ToyKit.scatter(canvas, this);
    }

    /** Where the small toys stand (crayons TOP, marbles BOTTOM): between the corridor's cotton tree and box pile. */
    private static final int SMALL_TOY_AX = 21;
    private static final int SMALL_TOY_AZ = 16;

    /** A camp's round rug in its colour with a white ring, flat in the floor. */
    private void rug(MapCanvas canvas, Quarter q, int cx, int cz, BlockSpec colour) {
        int r = (int) Math.ceil(RUG);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double d = Math.hypot(dx, dz);
                if (d > RUG || !contains(q.x(cx + dx), q.z(cz + dz))) {
                    continue;
                }
                q.set(canvas, cx + dx, G, cz + dz, d > RUG - 2 && d <= RUG - 1 ? ToyKit.WHITE : colour);
            }
        }
        // A dotted white stitch around the rug where it lies on the mat.
        for (int dx = -r - 1; dx <= r + 1; dx++) {
            for (int dz = -r - 1; dz <= r + 1; dz++) {
                double d = Math.hypot(dx, dz);
                int x = q.x(cx + dx);
                int z = q.z(cz + dz);
                if (d > RUG && d <= RUG + 1 && contains(x, z) && ToyKit.isMat(canvas.get(x, G, z))
                        && ToyKit.stitch(x, z)) {
                    canvas.set(x, G, z, ToyKit.WHITE);
                }
            }
        }
    }

    /**
     * Piles of toy boxes in the corners between the clearings (paths run between them): one between the corridor and
     * the 곰인형/토끼 clearing, one beyond the 젤리/쥐 clearing.
     */
    private static void boxPiles(MapCanvas canvas, Quarter q) {
        ToyKit.toyBox(canvas, q, 28, 16, 4, 3, 3, ToyKit.PINK);
        ToyKit.toyBlock(canvas, q, 33, G + 1, 17, 2, ToyKit.YELLOW);
        ToyKit.toyBox(canvas, q, 54, 31, 4, 4, 3, ToyKit.SKY);
        ToyKit.toyBox(canvas, q, 58, 30, 3, 3, 5, ToyKit.YELLOW);
    }

    /** 젤리 무리: three wobbly jelly puddings around the clearing. */
    private static void jellies(MapCanvas canvas, Quarter q) {
        jelly(canvas, q, 57, 20);
        jelly(canvas, q, 55, 27);
        jelly(canvas, q, 58, 15);
    }

    /**
     * A jelly pudding: a 3 x 3 slime cube rounded on top (a cross), whipped cream draped over the corners and the
     * cross, and a cherry (a lit red candle) in the middle.
     */
    private static void jelly(MapCanvas canvas, Quarter q, int ax, int az) {
        for (int dx = 0; dx <= 2; dx++) {
            for (int dz = 0; dz <= 2; dz++) {
                q.set(canvas, ax + dx, G + 1, az + dz, ToyKit.JELLY);
                q.set(canvas, ax + dx, G + 2, az + dz, ToyKit.JELLY);
                boolean corner = dx != 1 && dz != 1;
                if (corner) {
                    q.place(canvas, ax + dx, G + 3, az + dz, ToyKit.CREAM);
                } else {
                    q.set(canvas, ax + dx, G + 3, az + dz, ToyKit.JELLY);
                    if (dx != dz) {
                        q.place(canvas, ax + dx, G + 4, az + dz, ToyKit.CREAM);
                    }
                }
            }
        }
        q.place(canvas, ax + 1, G + 4, az + 1, BlockSpec.of(Material.RED_CANDLE).lit(true));
    }

    /**
     * 태엽 쥐 떼: a big cheese wedge at the clearing's castle side, its slope smoothed with stairs and two holes in its
     * sides, the mice's wind-up key stuck in the top.
     */
    private static void cheese(MapCanvas canvas, Quarter q) {
        int[] heights = {1, 2, 3, 3};
        for (int i = 0; i < heights.length; i++) {
            int ax = 57 + i;
            for (int az = 19; az <= 25; az++) {
                for (int y = G + 1; y <= G + heights[i]; y++) {
                    boolean hole = ax == 59 && y == G + 2 && az == 19 || ax == 60 && y == G + 2 && az == 25
                            || ax == 60 && y == G + 1 && az == 21;
                    if (hole) {
                        continue;
                    }
                    boolean slope = y == G + heights[i] && heights[i] < heights[heights.length - 1];
                    q.place(canvas, ax, y, az, slope
                            ? ToyKit.stairs(ToyKit.YELLOW_STAIRS, BlockFace.EAST, false) : ToyKit.YELLOW);
                }
            }
        }
        windUpKey(canvas, q, 60, 22, G + 4);
    }

    /** The bow of the mice's wind-up key: two rounded pink rings either side of the white stem. */
    private static final String[] KEY_BOW = {"PPPWPPP", "P.PPP.P", "PPP.PPP"};

    /**
     * The wind-up key of the clockwork mice, stuck in the top of the cheese: a white stem and a pink bow of two rings,
     * facing the clearing.
     */
    private static void windUpKey(MapCanvas canvas, Quarter q, int ax, int az, int y) {
        q.set(canvas, ax, y, az, ToyKit.WHITE);
        q.set(canvas, ax, y + 1, az, ToyKit.WHITE);
        ToyKit.plane(canvas, q, false, az - 3, ax, y + 2, KEY_BOW, true,
                Map.of('P', ToyKit.PINK, 'W', ToyKit.WHITE));
    }

    /**
     * 곰인형 대장: the teddy's big toy box behind the clearing (pink with a white rim and a white heart, its lid open,
     * toy blocks peeking out) and a stack of two toy blocks beside it.
     */
    private static void toyBox(MapCanvas canvas, Quarter q) {
        ToyKit.toyBox(canvas, q, 31, 34, 7, 3, 4, ToyKit.PINK);
        // A white heart on its front.
        ToyKit.plane(canvas, q, true, 32, 34, G + 1, new String[] {"..W..", ".WWW.", ".W.W."}, false,
                Map.of('W', ToyKit.WHITE));
        ToyKit.toyBlock(canvas, q, 39, G + 1, 35, 2, ToyKit.SKY);
        ToyKit.toyBlock(canvas, q, 39, G + 3, 35, 2, ToyKit.YELLOW);
    }

    /** The rabbit's ears, bottom row first: white with a pink inside, the tips leaning outwards (BOTTOM side). */
    private static final String[] EAR_RIGHT = {"WWW.", "WPW.", "WPW.", "WPW.", "WPW.", ".WPW", ".WPW", "..W."};
    private static final String[] EAR_LEFT = {".WWW", ".WPW", ".WPW", ".WPW", ".WPW", "WPW.", "WPW.", ".W.."};
    /** The pocket watch: a yellow rim, a white face, red hands at ten past twelve, a crown on top. */
    private static final String[] WATCH = {".YYY.", "YWWWY", "YWRRY", "YWRWY", ".YYY.", "..Y.."};

    /**
     * 시계 토끼: two tall rabbit ears (white with a pink inside, rounded, the tips leaning outwards) standing up behind
     * the clearing, and the rabbit's round pocket watch between them, high enough to walk under.
     */
    private static void rabbitEars(MapCanvas canvas, Quarter q) {
        Map<Character, BlockSpec> ear = Map.of('W', ToyKit.WHITE, 'P', ToyKit.PINK);
        ToyKit.plane(canvas, q, true, 28, 36, G + 1, EAR_LEFT, true, ear);
        ToyKit.plane(canvas, q, true, 37, 36, G + 1, EAR_RIGHT, true, ear);
        ToyKit.plane(canvas, q, true, 32, 36, G + 4, WATCH, true,
                Map.of('Y', ToyKit.YELLOW, 'W', ToyKit.WHITE, 'R', ToyKit.RED));
        q.set(canvas, 34, G + 10, 36, ToyKit.CHAIN);
    }

    /**
     * 사탕 샘물: a two-tier candy fountain: a round basin of water with a bevelled white rim, a white bowl on a pink and
     * white candy pillar (water in it too) and a sparkle on top.
     */
    private static void fountain(MapCanvas canvas, Quarter q) {
        int cx = 16;
        int cz = 35;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                double d = Math.hypot(dx, dz);
                if (d == 0) {
                    continue;
                }
                if (d <= 2.5) {
                    q.set(canvas, cx + dx, G + 1, cz + dz, ToyKit.WATER);
                } else if (d <= 3.3) {
                    q.place(canvas, cx + dx, G + 1, cz + dz, ToyKit.bevel(ToyKit.WHITE_STAIRS, dx, dz, false));
                }
                // The bowl stands high enough over the basin to wade in under it.
                if (d <= 1.5) {
                    q.set(canvas, cx + dx, G + 4, cz + dz, ToyKit.WHITE);
                    q.set(canvas, cx + dx, G + 5, cz + dz, ToyKit.WATER);
                } else if (d <= 2.3) {
                    q.place(canvas, cx + dx, G + 4, cz + dz, ToyKit.bevel(ToyKit.WHITE_STAIRS, dx, dz, true));
                    q.place(canvas, cx + dx, G + 5, cz + dz, ToyKit.WHITE_SLAB);
                }
            }
        }
        BlockSpec[] pillar = {ToyKit.PINK, ToyKit.WHITE, ToyKit.PINK, ToyKit.WHITE, ToyKit.PINK, ToyKit.WHITE};
        for (int i = 0; i < pillar.length; i++) {
            q.set(canvas, cx, G + 1 + i, cz, pillar[i]);
        }
        q.place(canvas, cx, G + 7, cz, ToyKit.STICK);
    }

    /**
     * 망원경 탑: a small white lookout (a light blue base band and windows) with a railed wooden balcony on top, a yellow
     * telescope on a tripod pointing out over the clearing and a red pennant at the back.
     */
    private static void telescope(MapCanvas canvas, Quarter q) {
        int cx = 16;
        int cz = 35;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                double d = Math.hypot(dx, dz);
                if (d > 2.3) {
                    continue;
                }
                if (d <= 1.5) {
                    for (int y = G + 1; y <= G + 6; y++) {
                        boolean window = (dx == 0) != (dz == 0) && (y == G + 4 || y == G + 5);
                        q.set(canvas, cx + dx, y, cz + dz, y == G + 1 || window ? ToyKit.SKY : ToyKit.WHITE);
                    }
                }
                q.place(canvas, cx + dx, G + 7, cz + dz, ToyKit.WOOD_SLAB_TOP);
                if (d > 1.5) {
                    q.set(canvas, cx + dx, G + 8, cz + dz, ToyKit.FENCE);
                }
            }
        }
        // The telescope on its tripod: the eyepiece at the back, the lens out over the clearing (towards -az).
        q.set(canvas, cx, G + 8, cz, ToyKit.FENCE);
        q.place(canvas, cx, G + 9, cz + 1, ToyKit.stairs(ToyKit.YELLOW_STAIRS, BlockFace.NORTH, false));
        for (int z = cz - 2; z <= cz; z++) {
            q.set(canvas, cx, G + 9, z, ToyKit.YELLOW);
        }
        q.set(canvas, cx, G + 9, cz - 3, ToyKit.SKY);
        // A red pennant on a pole at the back of the balcony, flying outwards.
        for (int y = G + 9; y <= G + 10; y++) {
            q.set(canvas, cx + 1, y, cz + 2, ToyKit.FENCE);
        }
        q.place(canvas, cx + 2, G + 10, cz + 2, BlockSpec.of(Material.RED_WALL_BANNER).facing(BlockFace.EAST));
    }

    /**
     * A box of crayons on the floor (a yellow box with a white band), four crayons of different lengths standing in it,
     * their tips sharpened (candles of their colour).
     */
    private static void crayonBox(MapCanvas canvas, Quarter q, int ax, int az) {
        for (int dx = 0; dx <= 2; dx++) {
            for (int dz = 0; dz <= 2; dz++) {
                q.set(canvas, ax + dx, G + 1, az + dz, ToyKit.YELLOW);
                q.set(canvas, ax + dx, G + 2, az + dz, ToyKit.WHITE);
            }
        }
        crayon(canvas, q, ax, az, 2, ToyKit.RED, Material.RED_CANDLE);
        crayon(canvas, q, ax + 1, az + 1, 3, ToyKit.SKY, Material.LIGHT_BLUE_CANDLE);
        crayon(canvas, q, ax + 2, az + 2, 1, ToyKit.PINK, Material.PINK_CANDLE);
        crayon(canvas, q, ax, az + 2, 2, ToyKit.MAT_TILE, Material.LIME_CANDLE);
    }

    private static void crayon(MapCanvas canvas, Quarter q, int ax, int az, int length, BlockSpec colour,
                               Material tip) {
        for (int i = 1; i <= length; i++) {
            q.set(canvas, ax, G + 2 + i, az, colour);
        }
        q.place(canvas, ax, G + 3 + length, az, BlockSpec.of(tip));
    }

    /**
     * A jar of marbles: a round glass jar with a narrow neck and a wooden lid, the marbles (toy colours) piled up
     * inside.
     */
    private static void marbleJar(MapCanvas canvas, Quarter q, int cx, int cz) {
        BlockSpec[] marbles = {ToyKit.PINK, ToyKit.SKY, ToyKit.YELLOW, ToyKit.RED};
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                double d = Math.hypot(dx, dz);
                if (d > 2.3) {
                    continue;
                }
                for (int y = G + 1; y <= G + 3; y++) {
                    BlockSpec spec;
                    if (d > 1.5) {
                        spec = ToyKit.GLASS;
                    } else if (y < G + 3 || ToyKit.speckle(cx + dx, y, cz + dz, 2)) {
                        spec = marbles[Math.floorMod((cx + dx) * 3 + (cz + dz) * 5 + y, marbles.length)];
                    } else {
                        continue;
                    }
                    q.set(canvas, cx + dx, y, cz + dz, spec);
                }
                if (d <= 1.5) {
                    if (d > 0) {
                        q.set(canvas, cx + dx, G + 4, cz + dz, ToyKit.GLASS);
                    }
                    q.place(canvas, cx + dx, G + 5, cz + dz, ToyKit.WOOD_SLAB);
                }
            }
        }
    }

    /** Where a corner toy stands (quarter coordinates): in the corner pocket beyond a lane's bend, on its diagonal. */
    private static final int CORNER_AX = ToyLayout.HALF_X - ToyLayout.CORNER
            + (int) Math.round((ToyLayout.CORNER - 5.0) / Math.sqrt(2));
    private static final int CORNER_AZ = ToyLayout.HALF_Z - ToyLayout.CORNER
            + (int) Math.round((ToyLayout.CORNER - 5.0) / Math.sqrt(2));

    /**
     * Two big alphabet blocks (yellow with red letters, light blue with yellow letters; white bevelled frames) side by
     * side along the wall and a red triangle block on top of both.
     */
    private static void blockStack(MapCanvas canvas, Quarter q) {
        int ax = CORNER_AX;
        int az = CORNER_AZ;
        ToyKit.toyBlock(canvas, q, ax - 4, G + 1, az, 4, ToyKit.YELLOW);
        ToyKit.toyBlock(canvas, q, ax, G + 1, az - 4, 4, ToyKit.SKY);
        ToyKit.box(canvas, q, ax - 2, G + 5, az - 2, 3, 1, 3, ToyKit.RED);
        ToyKit.prism(canvas, q, ax - 2, G + 6, az - 2, 3, 3, ToyKit.RED);
    }

    /**
     * A spinning top on its tip: white, yellow and red rings, widest at head height, its profile smoothed with stairs,
     * a white handle with a red knob on top.
     */
    private static void spinningTop(MapCanvas canvas, Quarter q) {
        double[] radius = {0.5, 1.5, 2.3, 3.2, 2.6, 1.6, 0.5, 0.5, 0.5};
        BlockSpec[] ring = {ToyKit.WHITE, ToyKit.YELLOW, ToyKit.RED, ToyKit.WHITE, ToyKit.RED, ToyKit.YELLOW,
            ToyKit.WHITE, ToyKit.WHITE, ToyKit.RED};
        ToyKit.lathe(canvas, q, CORNER_AX, CORNER_AZ, G + 1, radius, ring);
    }
}
