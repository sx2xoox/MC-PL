package io.github.sx2xoox.mcpl.core.map.toykingdom;

import static io.github.sx2xoox.mcpl.core.map.toykingdom.ToyKit.G;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.MapZone;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites.Lane;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites.Side;
import io.github.sx2xoox.mcpl.core.map.toykingdom.ToyKit.Quarter;

/**
 * The jungle between the lanes: a lawn shaped by a few long hedges into a middle corridor (castle front gate to the
 * dragon's sandbox) and two rows of clearings, one clearing per objective, each with its own landmark just outside
 * the open area: jelly cubes (젤리 무리), a cheese wedge (태엽 쥐 떼), a toy block arch (곰인형 대장), rabbit ears
 * (시계 토끼), a candy fountain (사탕 샘물) and a telescope tower (망원경 탑).
 */
final class ToyJungleZone implements MapZone {

    /** Hedges in quarter coordinates (both sides, both halves): {ax1, az1, ax2, az2, radius}. */
    static final double[][] HEDGES = {
        {24, 12, 34, 12, 1.5},
        {46, 12, 58, 12, 1.5},
        {43, 21, 43, 35, 1.5},
        {25, 21, 25, 35, 1.5},
    };

    /** Round bushes in quarter coordinates: {ax, az, radius}. */
    static final double[][] BALLS = {{58, 32, 2.5}, {10, 17, 3}};

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
            for (Lane lane : Lane.values()) {
                Quarter q = Quarter.of(side, lane);
                for (double[] h : HEDGES) {
                    ToyKit.hedge(canvas, q, h[0], h[1], h[2], h[3], h[4]);
                }
                for (double[] b : BALLS) {
                    ToyKit.ball(canvas, q, (int) b[0], (int) b[1], b[2]);
                }
            }
            Quarter top = Quarter.of(side, Lane.TOP);
            Quarter bottom = Quarter.of(side, Lane.BOTTOM);
            jellies(canvas, top);
            teddyArch(canvas, top);
            fountain(canvas, top);
            cheese(canvas, bottom);
            rabbitEars(canvas, bottom);
            telescope(canvas, bottom);
        }
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
    }

    /** 곰인형 대장: an arch of giant toy blocks (pink and yellow pillars, a white beam) behind the clearing. */
    private static void teddyArch(MapCanvas canvas, Quarter q) {
        ToyKit.toyBlock(canvas, q, 31, G + 1, 35, 2, ToyKit.PINK);
        ToyKit.toyBlock(canvas, q, 36, G + 1, 35, 2, ToyKit.YELLOW);
        for (int ax = 31; ax <= 37; ax++) {
            for (int az = 35; az <= 36; az++) {
                for (int y = G + 3; y <= G + 4; y++) {
                    q.set(canvas, ax, y, az, ToyKit.WHITE);
                }
            }
        }
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
}
