package io.github.sx2xoox.mcpl.core.map.toykingdom;

import static io.github.sx2xoox.mcpl.core.map.toykingdom.ToyKit.G;

import java.util.List;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.MapZone;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites.PropMotion;
import io.github.sx2xoox.mcpl.core.map.toykingdom.ToyKit.Quarter;

/**
 * The middle: a shallow river along {@code x = 0} from lane to lane (one block of water on sand, so anyone can wade
 * through and step out) and, where it would cross the corridor, the toy dragon's round sandbox: sand one block down in
 * a birch frame, open to the sky. Halfway along each arm of the river a toy rainbow arches over the water from one
 * puffy white cloud on the bank to the other, high enough to wade under, a toy sailing boat lies at anchor near the
 * sandbox and a rubber duck floats by; by the sandbox a toy bucket full of sand, a sand castle on its rim and a spade
 * stuck in it: these are models ({@link #props}) on invisible footings (waterlogged in the river, so the water stays).
 */
final class ToyRiverZone implements MapZone {

    @Override
    public String name() {
        return "river";
    }

    @Override
    public boolean contains(int x, int z) {
        return ToyLayout.partOf(x, z).equals(name());
    }

    @Override
    public void build(MapCanvas canvas) {
        int hz = ToyLayout.RIVER_ZONE_HALF_Z;
        int r = (int) Math.ceil(ToyLayout.RIVER_ZONE_RADIUS);
        for (int x = -r; x <= r; x++) {
            for (int z = -hz; z <= hz; z++) {
                if (contains(x, z)) {
                    ToyKit.ground(canvas, x, z);
                }
            }
        }
        int middle = (ToyLayout.RIVER_Z1 + ToyLayout.RIVER_Z2) / 2;
        rainbow(canvas, -middle);
        rainbow(canvas, middle);
        boat(canvas, -BOAT_Z);
        boat(canvas, BOAT_Z);
        for (int s : new int[] {-1, 1}) {
            duck(canvas, new Quarter(1, s));
            bucket(canvas, new Quarter(s, -1));
            sandCastle(canvas, new Quarter(s, -1));
            shovel(canvas, new Quarter(s, 1));
        }
        ToyKit.scatter(canvas, this);
    }

    /** Where the toy sailing boats lie at anchor (centre of the hull, |z|). */
    private static final int BOAT_Z = ToyLayout.RIVER_Z1 + 4;
    /**
     * A toy sailing boat at anchor (a model, {@link #props}; z: the hull's middle): where its hull lies in the water
     * an invisible deck one step up from the river, so anyone can still climb aboard; there is room to wade past on
     * both sides.
     */
    private static void boat(MapCanvas canvas, int z) {
        Quarter q = new Quarter(1, Integer.signum(z));
        int az = Math.abs(z);
        for (int dz = -2; dz <= 2; dz++) {
            for (int x = -1; x <= 1; x++) {
                if (Math.abs(dz) < 2 || x == 0) {
                    ToyKit.footing(canvas, q, x, G, az + dz);
                }
            }
        }
    }

    /** Where the rubber ducks swim (|z| of the head), between the rainbow and the lane. */
    private static final int DUCK_Z = 33;

    /**
     * A big rubber duck floating in the river (a model, {@link #props}; a quarter of it, x = 0 its middle): an
     * invisible footing in the water under its body.
     */
    private static void duck(MapCanvas canvas, Quarter q) {
        int az = DUCK_Z;
        for (int dz = 0; dz <= 2; dz++) {
            ToyKit.footing(canvas, q, 0, G, az + dz);
        }
        for (int x : new int[] {-1, 1}) {
            ToyKit.footing(canvas, q, x, G, az + 1);
        }
    }

    /** The toy sand bucket by the sandbox (quarter coordinates of its middle) and its footing (3 x 3 but the corners). */
    private static final int BUCKET_AX = 11;
    private static final int BUCKET_AZ = 9;

    private static void bucket(MapCanvas canvas, Quarter q) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 || dz == 0) {
                    ToyKit.footing(canvas, q, BUCKET_AX + dx, G + 1, BUCKET_AZ + dz);
                    ToyKit.footing(canvas, q, BUCKET_AX + dx, G + 2, BUCKET_AZ + dz);
                }
            }
        }
    }

    /**
     * The little sand castle on the sandbox's rim, just outside the dragon's ring (its middle block in quarter
     * coordinates): a footing one high where its sand towers stood, on the sand one block down.
     */
    private static final int CASTLE_AX = 7;
    private static final int CASTLE_AZ = 8;

    private static void sandCastle(MapCanvas canvas, Quarter q) {
        ToyKit.footing(canvas, q, CASTLE_AX, G, CASTLE_AZ);
        ToyKit.footing(canvas, q, CASTLE_AX - 1, G, CASTLE_AZ + 1);
        ToyKit.footing(canvas, q, CASTLE_AX + 1, G, CASTLE_AZ - 1);
    }

    /** The toy spade stuck in the sandbox's sand outside the dragon's ring: a footing two high where it stands. */
    private static final int SPADE_AX = 7;
    private static final int SPADE_AZ = 8;

    private static void shovel(MapCanvas canvas, Quarter q) {
        ToyKit.footing(canvas, q, SPADE_AX, G, SPADE_AZ);
        ToyKit.footing(canvas, q, SPADE_AX, G + 1, SPADE_AZ);
    }

    /** Outer radius of the rainbow (its feet stand on the banks, inside the river zone). */
    private static final double RAINBOW_RADIUS = 6.5;

    /**
     * A rainbow across the river at {@code z} is a model ({@link #props}): its puffy clouds on the banks keep an
     * invisible footing of their old shape.
     */
    private static void rainbow(MapCanvas canvas, int z) {
        int r = (int) Math.floor(RAINBOW_RADIUS);
        for (int s : new int[] {-1, 1}) {
            ToyKit.lathe(canvas, new Quarter(s, 1), r - 1, z, G + 1, new double[] {1.5, 1},
                    new BlockSpec[] {ToyKit.FOOTING, ToyKit.FOOTING});
        }
    }

    /** The water's surface (the base of the floating models). */
    private static final double SURFACE = G + 0.9;

    /**
     * The river's model props: the toy sailing boats (a red hull, a striped sail, bunting) and the rubber ducks bobbing
     * on the water, their bows towards the middle of the map, the rainbow over each arm of the river between two sleepy
     * clouds; by the sandbox the buckets, the sand castles and the spades (castle and spade on the sand, one block
     * down), facing the dragon's nest.
     */
    static void props(List<LaneSites.ModelProp> out) {
        int middle = (ToyLayout.RIVER_Z1 + ToyLayout.RIVER_Z2) / 2;
        for (int s : new int[] {-1, 1}) {
            Quarter q = new Quarter(1, s);
            out.add(q.prop("boat", 0.5, SURFACE, BOAT_Z + 0.5, q.yaw(0, -1), PropMotion.BOB));
            out.add(q.prop("duck", 0.5, SURFACE, DUCK_Z + 1.5, q.yaw(0, -1), PropMotion.BOB));
            out.add(q.prop("rainbow", 0.5, G + 1, middle + 0.5, q.yaw(0, -1), PropMotion.STILL));
            Quarter near = new Quarter(s, -1);
            Quarter far = new Quarter(s, 1);
            float nest = near.yaw(-1, -1);
            out.add(near.prop("sand_bucket", BUCKET_AX + 0.5, G + 1, BUCKET_AZ + 0.5, nest, PropMotion.STILL));
            out.add(near.prop("sand_castle", CASTLE_AX + 0.5, G, CASTLE_AZ + 0.5, nest, PropMotion.STILL));
            out.add(far.prop("sand_spade", SPADE_AX + 0.5, G, SPADE_AZ + 0.5, far.yaw(-1, -1), PropMotion.STILL));
        }
    }
}
