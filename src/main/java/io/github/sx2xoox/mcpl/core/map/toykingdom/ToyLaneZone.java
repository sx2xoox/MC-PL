package io.github.sx2xoox.mcpl.core.map.toykingdom;

import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.MapZone;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites.Lane;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites.Side;
import io.github.sx2xoox.mcpl.core.map.toykingdom.ToyKit.Quarter;

/**
 * One lane: the coloured brick road with white edges on the play mat, a round plaza in the road's colour for every
 * tower (ringed in the owner's colour), a pair of candy canes at each plaza, a few round sponge bushes on the verges
 * and a row of lollipop lamps along the outer verge.
 */
final class ToyLaneZone implements MapZone {

    /** Sponge bushes on the verges, in quarter coordinates of the BLUE half's TOP lane: {ax along, side, radius}. */
    private static final double[][] BUSHES = {{14, 1, 2.2}, {50, -1, 2}, {54, 1, 2.4}};
    /** Lollipop lamps on the outer verge (towards the edge), at these {@code |x|} of the lane's centre line. */
    private static final int[] LAMPS = {8, 19, 41, 60};
    /** How far a lamp stands from the lane's centre line (clear of the road, its white edge and the minions). */
    private static final double LAMP_OFFSET = 6.5;

    private final Lane lane;

    ToyLaneZone(Lane lane) {
        this.lane = lane;
    }

    @Override
    public String name() {
        return lane == Lane.TOP ? "top-lane" : "bottom-lane";
    }

    @Override
    public boolean contains(int x, int z) {
        return ToyLayout.partOf(x, z).equals(name());
    }

    @Override
    public void build(MapCanvas canvas) {
        int r = ToyLayout.BUILD_RADIUS;
        int sz = ToyLayout.sign(lane);
        for (int x = -r; x <= r; x++) {
            for (int az = 0; az <= r; az++) {
                if (contains(x, sz * az)) {
                    ToyKit.ground(canvas, x, sz * az);
                }
            }
        }
        for (Side side : Side.values()) {
            Quarter q = Quarter.of(side, lane);
            for (int t = 0; t < ToyLayout.TOWERS.length; t++) {
                canes(canvas, q, t);
            }
            for (double[] bush : BUSHES) {
                double[] p = ToyLayout.curveAt(bush[0]);
                double[] n = normalAt(bush[0]);
                double off = 7 * bush[1];
                ToyKit.ball(canvas, q, (int) Math.round(p[0] + n[0] * off), (int) Math.round(-(p[1] + n[1] * off)),
                        bush[2], ToyKit.SPONGE);
            }
            for (int ax : LAMPS) {
                double[] p = ToyLayout.curveAt(ax);
                double[] n = normalAt(ax);
                ToyKit.lollipop(canvas, q, (int) Math.round(p[0] - n[0] * LAMP_OFFSET),
                        (int) Math.round(-(p[1] - n[1] * LAMP_OFFSET)), 3);
            }
        }
    }

    /**
     * Two candy canes on the inner side of a tower plaza (towards the jungle), like a gate into the tower's ring, their
     * hooks bent towards the tower.
     */
    private static void canes(MapCanvas canvas, Quarter q, int index) {
        int[] tower = ToyLayout.TOWERS[index];
        double[] n = ToyLayout.towerNormal(index);
        for (int s : new int[] {-1, 1}) {
            double ax = tower[0] - n[0] * 10 + n[1] * 4.5 * s;
            double z = tower[1] - n[1] * 10 - n[0] * 4.5 * s;
            ToyKit.candyCane(canvas, q, (int) Math.round(ax), (int) -Math.round(z), tower[0], -tower[1], 5);
        }
    }

    private static double[] normalAt(double ax) {
        double[] a = ToyLayout.curveAt(ax - 1);
        double[] b = ToyLayout.curveAt(ax + 1);
        double tx = b[0] - a[0];
        double tz = b[1] - a[1];
        double len = Math.hypot(tx, tz);
        return new double[] {-tz / len, tx / len};
    }
}
