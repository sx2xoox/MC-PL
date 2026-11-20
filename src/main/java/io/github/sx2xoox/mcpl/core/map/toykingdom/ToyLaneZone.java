package io.github.sx2xoox.mcpl.core.map.toykingdom;

import static io.github.sx2xoox.mcpl.core.map.toykingdom.ToyKit.G;

import java.util.ArrayList;
import java.util.List;

import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.MapZone;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites.Lane;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites.PropMotion;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites.Side;
import io.github.sx2xoox.mcpl.core.map.toykingdom.ToyKit.Quarter;

/**
 * One lane: the coloured brick road with white edges on the play mat, a round plaza in the road's colour for every
 * tower (ringed in the owner's colour); its toys are models ({@link #props}): a few round sponge bushes on the verges,
 * a pair of candy canes at each plaza, a row of lollipop lamps along the outer verge and one big toy on it in the
 * middle (a toy train by the TOP lane, a wind-up car by the BOTTOM lane).
 */
final class ToyLaneZone implements MapZone {

    /** Sponge bushes on the verges, in quarter coordinates of the BLUE half's TOP lane: {ax along, side}. */
    private static final double[][] BUSHES = {{14, 1}, {50, -1}, {54, 1}};
    /** Lollipop lamps on the outer verge (towards the edge), at these {@code |x|} of the lane's centre line. */
    private static final int[] LAMPS = {8, 19, 41, 60};
    /** How far a lamp stands from the lane's centre line (clear of the road, its white edge and the minions). */
    private static final double LAMP_OFFSET = 6.5;
    /** A lamp's stick: tall enough that the train and the car show under the sweets. */
    private static final int LAMP_HEIGHT = 5;
    /** Where the toy train (TOP) and the wind-up car (BOTTOM) stand: the outer verge between the two middle lamps. */
    private static final int TOY_AX = 11;
    private static final int TOY_AZ = 56;

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
            for (int[] bush : bushes()) {
                // The bush is a model (props): an invisible footing where its body stood, three by three, two high.
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        q.place(canvas, bush[0] + dx, G + 1, bush[1] + dz, ToyKit.FOOTING);
                        q.place(canvas, bush[0] + dx, G + 2, bush[1] + dz, ToyKit.FOOTING);
                    }
                }
            }
            for (int[] lamp : lamps()) {
                // The lamp itself is a model (props); its light stays where the glowing sweet is.
                q.place(canvas, lamp[0], G + LAMP_HEIGHT + 4, lamp[1], ToyKit.PROP_LIGHT);
            }
            // The train and the car are models too: an invisible footing two blocks high where they stand.
            int from = lane == Lane.TOP ? TOY_AX - 1 : TOY_AX + 1;
            int to = lane == Lane.TOP ? TOY_AX + 8 : TOY_AX + 5;
            for (int ax = from; ax <= to; ax++) {
                for (int dz = 0; dz <= 1; dz++) {
                    q.place(canvas, ax, G + 1, TOY_AZ + dz, ToyKit.FOOTING);
                    q.place(canvas, ax, G + 2, TOY_AZ + dz, ToyKit.FOOTING);
                }
            }
        }
        ToyKit.scatter(canvas, this);
    }

    /** The sponge bushes of a quarter (quarter coordinates): {ax, az} of the middle block. */
    private static List<int[]> bushes() {
        List<int[]> out = new ArrayList<>();
        for (double[] bush : BUSHES) {
            double[] p = ToyLayout.curveAt(bush[0]);
            double[] n = normalAt(bush[0]);
            double off = 7 * bush[1];
            out.add(new int[] {(int) Math.round(p[0] + n[0] * off), (int) Math.round(-(p[1] + n[1] * off))});
        }
        return out;
    }

    /** The lollipop lamps of a quarter (quarter coordinates): {ax, az, 1 if the sweet faces along z}. */
    private static List<int[]> lamps() {
        List<int[]> out = new ArrayList<>();
        for (int ax : LAMPS) {
            double[] p = ToyLayout.curveAt(ax);
            double[] n = normalAt(ax);
            // The sweet faces along the road, so it never leans over it.
            out.add(new int[] {(int) Math.round(p[0] - n[0] * LAMP_OFFSET),
                (int) Math.round(-(p[1] - n[1] * LAMP_OFFSET)), Math.abs(n[1]) >= Math.abs(n[0]) ? 1 : 0});
        }
        return out;
    }

    /**
     * The lane's model props ({@code models/props.py}) in every quarter: the sponge bushes on the verges, their buds
     * turned along x (alternately towards the river and the base, so neighbours differ); the lollipop lamps; two candy
     * canes on the inner side of each tower plaza (towards the jungle), like a gate into the tower's ring, their hooks
     * bent towards the tower; on the outer verge between the two middle lamps a toy train on its track (TOP) or a
     * wind-up car with its key turning (BOTTOM), both heading for the river.
     */
    static void props(Lane lane, List<LaneSites.ModelProp> out) {
        for (Side side : Side.values()) {
            Quarter q = Quarter.of(side, lane);
            List<int[]> bushes = bushes();
            for (int i = 0; i < bushes.size(); i++) {
                int[] bush = bushes.get(i);
                out.add(q.prop("sponge_bush", bush[0] + 0.5, G + 1, bush[1] + 0.5, q.yaw(i % 2 == 0 ? -1 : 1, 0),
                        PropMotion.STILL));
            }
            for (int[] lamp : lamps()) {
                out.add(q.prop("lollipop", lamp[0] + 0.5, G + 1, lamp[1] + 0.5, lamp[2] == 1 ? q.yaw(0, -1)
                        : q.yaw(1, 0), PropMotion.STILL));
            }
            for (int index = 0; index < ToyLayout.TOWERS.length; index++) {
                int[] tower = ToyLayout.TOWERS[index];
                double[] n = ToyLayout.towerNormal(index);
                for (int s : new int[] {-1, 1}) {
                    int ax = (int) Math.round(tower[0] - n[0] * 10 + n[1] * 4.5 * s);
                    int az = (int) -Math.round(tower[1] - n[1] * 10 - n[0] * 4.5 * s);
                    // the hook (+x of the model) bends towards the tower: the front is a quarter turn from it
                    float hook = q.yaw(tower[0] - ax, -tower[1] - az);
                    out.add(q.prop("candy_cane", ax + 0.5, G + 1, az + 0.5, hook - 90, PropMotion.STILL));
                }
            }
            float ahead = q.yaw(-1, 0);
            if (lane == Lane.TOP) {
                out.add(q.prop("train", TOY_AX + 4, G + 1, TOY_AZ + 1, ahead, PropMotion.STILL));
            } else {
                out.add(q.prop("windup_car", TOY_AX + 3.5, G + 1, TOY_AZ + 1, ahead, PropMotion.STILL));
                out.add(q.prop("windup_car_key", TOY_AX + 3.5, G + 1, TOY_AZ + 1, ahead, PropMotion.KEY));
            }
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
