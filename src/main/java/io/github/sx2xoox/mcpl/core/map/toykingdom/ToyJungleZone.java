package io.github.sx2xoox.mcpl.core.map.toykingdom;

import static io.github.sx2xoox.mcpl.core.map.toykingdom.ToyKit.G;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.MapZone;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites.Lane;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites.Side;
import io.github.sx2xoox.mcpl.core.map.toykingdom.ToyKit.Quarter;

/**
 * The jungle between the lanes, a toy room on the play mat: a few long walls of toy bricks shape a middle corridor
 * (castle front gate to the dragon's sandbox, starting on the castle's checkered mat) and two rows of clearings, one
 * per objective. Every clearing has its own round rug (a white ring in it) and its own landmark just outside the open
 * area: jelly cubes on a pink rug (젤리 무리), a cheese wedge with a wind-up key on a yellow one (태엽 쥐 떼), a big toy
 * box on a yellow one (곰인형 대장), rabbit ears with a pocket watch between them on a pink one (시계 토끼), a candy
 * fountain on a light blue one (사탕 샘물) and a telescope tower on a light blue one (망원경 탑). Piles of toy boxes and
 * cotton candy trees fill the corners in between, and giant toys stand in the far corner pockets beyond the lanes'
 * bends (a stack of toy blocks by the TOP lane, a spinning top by the BOTTOM lane).
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
        }
    }

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

    /** 젤리 무리: three wobbly jelly cubes around the clearing. */
    private static void jellies(MapCanvas canvas, Quarter q) {
        ToyKit.toyBlock(canvas, q, 57, G + 1, 20, 3, ToyKit.JELLY);
        ToyKit.toyBlock(canvas, q, 55, G + 1, 27, 3, ToyKit.JELLY);
        ToyKit.toyBlock(canvas, q, 58, G + 1, 15, 3, ToyKit.JELLY);
    }

    /** 태엽 쥐 떼: a big cheese wedge (stepped, with two holes) at the clearing's castle side. */
    private static void cheese(MapCanvas canvas, Quarter q) {
        int[] heights = {1, 2, 3, 3};
        for (int i = 0; i < heights.length; i++) {
            int ax = 57 + i;
            for (int az = 19; az <= 25; az++) {
                for (int y = G + 1; y <= G + heights[i]; y++) {
                    boolean hole = ax == 59 && y == G + 2 && az == 19 || ax == 60 && y == G + 2 && az == 25
                            || ax == 60 && y == G + 1 && az == 21;
                    if (!hole) {
                        q.set(canvas, ax, y, az, ToyKit.YELLOW);
                    }
                }
            }
        }
        windUpKey(canvas, q, 60, 22, G + 4);
    }

    /**
     * The wind-up key of the clockwork mice, stuck in the top of the cheese: a white stem and a pink bow of two rings,
     * facing the clearing.
     */
    private static void windUpKey(MapCanvas canvas, Quarter q, int ax, int az, int y) {
        q.set(canvas, ax, y, az, ToyKit.WHITE);
        q.set(canvas, ax, y + 1, az, ToyKit.WHITE);
        for (int s : new int[] {-1, 1}) {
            int c = az + 2 * s;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    if (dz != 0 || dy != 1) {
                        q.set(canvas, ax, y + 2 + dy, c + dz, ToyKit.PINK);
                    }
                }
            }
        }
        q.set(canvas, ax, y + 3, az, ToyKit.PINK);
    }

    /**
     * 곰인형 대장: the teddy's big toy box behind the clearing (pink with a white rim, toy blocks peeking out) and a
     * stack of two toy blocks beside it.
     */
    private static void toyBox(MapCanvas canvas, Quarter q) {
        ToyKit.toyBox(canvas, q, 31, 34, 7, 3, 4, ToyKit.PINK);
        ToyKit.toyBlock(canvas, q, 39, G + 1, 35, 2, ToyKit.SKY);
        ToyKit.toyBlock(canvas, q, 39, G + 3, 35, 2, ToyKit.YELLOW);
    }

    /** 시계 토끼: two tall rabbit ears (white with a pink inside) standing up behind the clearing. */
    private static void rabbitEars(MapCanvas canvas, Quarter q) {
        for (int centre : new int[] {31, 37}) {
            for (int dx = -1; dx <= 1; dx++) {
                int height = dx == 0 ? 7 : 6;
                for (int i = 1; i <= height; i++) {
                    boolean inner = dx == 0 && i >= 2 && i <= 5;
                    q.set(canvas, centre + dx, G + i, 36, inner ? ToyKit.PINK : ToyKit.WHITE);
                }
            }
        }
        // The rabbit's pocket watch hangs between the ears, high enough to walk under: a white face with yellow hands.
        for (int ax = 33; ax <= 35; ax++) {
            for (int y = G + 4; y <= G + 6; y++) {
                boolean hand = ax == 34 && y >= G + 5 || ax == 35 && y == G + 5;
                q.set(canvas, ax, y, 36, hand ? ToyKit.YELLOW : ToyKit.WHITE);
            }
        }
    }

    /** 사탕 샘물: a round white basin of water with a pink and white candy pillar in the middle. */
    private static void fountain(MapCanvas canvas, Quarter q) {
        int cx = 16;
        int cz = 35;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                double d = Math.hypot(dx, dz);
                if (d <= 1.5) {
                    q.set(canvas, cx + dx, G + 1, cz + dz, ToyKit.WATER);
                } else if (d <= 2.5) {
                    q.set(canvas, cx + dx, G + 1, cz + dz, ToyKit.WHITE);
                }
            }
        }
        for (int i = 1; i <= 4; i++) {
            q.set(canvas, cx, G + i, cz, i % 2 == 0 ? ToyKit.WHITE : ToyKit.PINK);
        }
        q.set(canvas, cx, G + 5, cz, ToyKit.PINK);
    }

    /** 망원경 탑: a small white lookout with a yellow cap and a yellow telescope pointing over the clearing. */
    private static void telescope(MapCanvas canvas, Quarter q) {
        int cx = 16;
        int cz = 35;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.hypot(dx, dz) > 1.5) {
                    continue;
                }
                for (int y = G + 1; y <= G + 7; y++) {
                    BlockSpec spec = y == G + 7 ? ToyKit.YELLOW : ToyKit.WHITE;
                    q.set(canvas, cx + dx, y, cz + dz, spec);
                }
            }
        }
        for (int az = cz - 3; az <= cz + 1; az++) {
            q.set(canvas, cx, G + 8, az, ToyKit.YELLOW);
        }
        q.set(canvas, cx, G + 9, cz - 3, ToyKit.YELLOW);
    }

    /** Where a corner toy stands (quarter coordinates): in the corner pocket beyond a lane's bend, on its diagonal. */
    private static final int CORNER_AX = ToyLayout.HALF_X - ToyLayout.CORNER
            + (int) Math.round((ToyLayout.CORNER - 5.0) / Math.sqrt(2));
    private static final int CORNER_AZ = ToyLayout.HALF_Z - ToyLayout.CORNER
            + (int) Math.round((ToyLayout.CORNER - 5.0) / Math.sqrt(2));

    /** Two big toy blocks (yellow, light blue) side by side along the wall and a red one on top of both. */
    private static void blockStack(MapCanvas canvas, Quarter q) {
        int ax = CORNER_AX;
        int az = CORNER_AZ;
        ToyKit.toyBlock(canvas, q, ax - 4, G + 1, az, 4, ToyKit.YELLOW);
        ToyKit.toyBlock(canvas, q, ax, G + 1, az - 4, 4, ToyKit.SKY);
        ToyKit.toyBlock(canvas, q, ax - 2, G + 5, az - 2, 3, ToyKit.RED);
    }

    /** A spinning top on its tip: white, yellow and red rings, widest at head height, a white handle on top. */
    private static void spinningTop(MapCanvas canvas, Quarter q) {
        double[] radius = {0.5, 1.5, 2.3, 3.2, 2.6, 1.6, 0.5, 0.5};
        BlockSpec[] ring = {ToyKit.WHITE, ToyKit.YELLOW, ToyKit.RED, ToyKit.WHITE, ToyKit.RED, ToyKit.YELLOW,
            ToyKit.WHITE, ToyKit.WHITE};
        for (int i = 0; i < radius.length; i++) {
            int r = (int) Math.ceil(radius[i]);
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.hypot(dx, dz) <= radius[i]) {
                        q.set(canvas, CORNER_AX + dx, G + 1 + i, CORNER_AZ + dz, ring[i]);
                    }
                }
            }
        }
    }
}
