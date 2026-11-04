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
import org.bukkit.block.data.Bisected;

/**
 * One lane: the coloured brick road with white edges on the play mat, a round plaza in the road's colour for every
 * tower (ringed in the owner's colour), a pair of candy canes at each plaza, a few round sponge bushes on the verges,
 * a row of lollipop lamps along the outer verge and one big toy on it in the middle (a toy train by the TOP lane, a
 * wind-up car by the BOTTOM lane).
 */
final class ToyLaneZone implements MapZone {

    /** Sponge bushes on the verges, in quarter coordinates of the BLUE half's TOP lane: {ax along, side, radius}. */
    private static final double[][] BUSHES = {{14, 1, 2.2}, {50, -1, 2}, {54, 1, 2.4}};
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
                // The sweet faces along the road, so it never leans over it.
                ToyKit.lollipop(canvas, q, (int) Math.round(p[0] - n[0] * LAMP_OFFSET),
                        (int) Math.round(-(p[1] - n[1] * LAMP_OFFSET)), LAMP_HEIGHT,
                        Math.abs(n[1]) >= Math.abs(n[0]));
            }
            if (lane == Lane.TOP) {
                train(canvas, q);
            } else {
                windUpCar(canvas, q);
            }
        }
        ToyKit.scatter(canvas, this);
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

    /**
     * A toy train on the outer verge, heading for the river: a locomotive (a red frame with a cowcatcher, a round
     * yellow boiler with a chimney, a blue cab with a window, a lamp inside and a white roof) and a wagon of toy
     * blocks coupled behind it, on a strip of wooden track.
     */
    private static void train(MapCanvas canvas, Quarter q) {
        int ax = TOY_AX;
        int az = TOY_AZ;
        BlockSpec track = BlockSpec.of(Material.BIRCH_TRAPDOOR).half(Bisected.Half.BOTTOM).facing(BlockFace.NORTH);
        for (int x : new int[] {ax - 2, ax - 1, ax + 8, ax + 9}) {
            for (int dz = 0; dz <= 1; dz++) {
                q.place(canvas, x, G + 1, az + dz, track);
            }
        }
        for (int dz = 0; dz <= 1; dz++) {
            int z = az + dz;
            // The locomotive (ax .. ax + 4): cowcatcher and frame, the boiler rounded at the front, the cab.
            q.place(canvas, ax, G + 1, z, ToyKit.stairs(ToyKit.RED_STAIRS, BlockFace.EAST, false));
            for (int x = ax + 1; x <= ax + 4; x++) {
                q.set(canvas, x, G + 1, z, ToyKit.RED);
            }
            q.place(canvas, ax + 1, G + 2, z, ToyKit.stairs(ToyKit.YELLOW_STAIRS, BlockFace.EAST, false));
            q.set(canvas, ax + 2, G + 2, z, ToyKit.YELLOW);
            q.place(canvas, ax + 2, G + 3, z, ToyKit.stairs(ToyKit.YELLOW_STAIRS, BlockFace.EAST, false));
            for (int x = ax + 3; x <= ax + 4; x++) {
                q.set(canvas, x, G + 2, z, ToyKit.BLUE);
                q.set(canvas, x, G + 4, z, ToyKit.BLUE);
                q.place(canvas, x, G + 5, z, ToyKit.WHITE_SLAB);
            }
            // The cab's window looks forward over the boiler; its back wall is whole.
            q.set(canvas, ax + 4, G + 3, z, ToyKit.BLUE);
            // The wagon (ax + 6 .. ax + 7): a red frame and a yellow box.
            for (int x = ax + 6; x <= ax + 7; x++) {
                q.set(canvas, x, G + 1, z, ToyKit.RED);
                q.set(canvas, x, G + 2, z, ToyKit.YELLOW);
            }
            // The coupling between them.
            q.set(canvas, ax + 5, G + 1, z, ToyKit.FENCE);
        }
        // The chimney (a white cap on top) and the lamp inside the cab.
        q.set(canvas, ax + 2, G + 4, az, ToyKit.RED);
        q.place(canvas, ax + 2, G + 5, az, ToyKit.WHITE_SLAB);
        q.place(canvas, ax + 3, G + 3, az + 1, ToyKit.LANTERN_HANGING);
        // Toy blocks riding in the wagon.
        q.set(canvas, ax + 6, G + 3, az, ToyKit.WHITE);
        q.set(canvas, ax + 7, G + 3, az + 1, ToyKit.BLUE);
        q.place(canvas, ax + 7, G + 3, az, ToyKit.stairs(ToyKit.RED_STAIRS, BlockFace.WEST, false));
    }

    /**
     * A wind-up toy car on the outer verge: a red body (a white stripe) with a sloped bonnet and boot, deep red wheels,
     * a light blue cabin under a white roof and a big wind-up key in its back.
     */
    private static void windUpCar(MapCanvas canvas, Quarter q) {
        int ax = TOY_AX + 1;
        int az = TOY_AZ;
        BlockSpec wheel = BlockSpec.of(Material.RED_NETHER_BRICK_WALL);
        for (int dz = 0; dz <= 1; dz++) {
            int z = az + dz;
            q.set(canvas, ax, G + 1, z, wheel);
            q.set(canvas, ax + 4, G + 1, z, wheel);
            for (int x = ax + 1; x <= ax + 3; x++) {
                q.set(canvas, x, G + 1, z, ToyKit.RED);
                q.set(canvas, x, G + 2, z, x == ax + 2 ? ToyKit.WHITE : ToyKit.RED);
            }
            q.place(canvas, ax, G + 2, z, ToyKit.stairs(ToyKit.RED_STAIRS, BlockFace.EAST, false));
            q.place(canvas, ax + 4, G + 2, z, ToyKit.stairs(ToyKit.RED_STAIRS, BlockFace.WEST, false));
            q.place(canvas, ax + 1, G + 3, z, ToyKit.stairs(ToyKit.WHITE_STAIRS, BlockFace.EAST, false));
            q.set(canvas, ax + 2, G + 3, z, ToyKit.SKY);
            q.place(canvas, ax + 3, G + 3, z, ToyKit.stairs(ToyKit.WHITE_STAIRS, BlockFace.WEST, false));
            q.place(canvas, ax + 2, G + 4, z, ToyKit.WHITE_SLAB);
        }
        // The wind-up key: a white stem out of the boot and a light blue bow across it.
        q.set(canvas, ax + 5, G + 2, az, ToyKit.WHITE);
        ToyKit.plane(canvas, q, false, az - 1, ax + 6, G + 1, new String[] {".B.", "BWB", ".B."}, false,
                Map.of('B', ToyKit.SKY, 'W', ToyKit.WHITE));
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
