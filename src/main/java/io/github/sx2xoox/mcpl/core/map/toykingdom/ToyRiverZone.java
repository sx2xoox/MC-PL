package io.github.sx2xoox.mcpl.core.map.toykingdom;

import static io.github.sx2xoox.mcpl.core.map.toykingdom.ToyKit.G;

import java.util.List;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.MapZone;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites.PropMotion;
import io.github.sx2xoox.mcpl.core.map.toykingdom.ToyKit.Quarter;
import org.bukkit.Material;

/**
 * The middle: a shallow river along {@code x = 0} from lane to lane (one block of water on sand, so anyone can wade
 * through and step out) and, where it would cross the corridor, the toy dragon's round sandbox: sand one block down in
 * a birch frame, open to the sky. Halfway along each arm of the river a toy rainbow arches over the water from one
 * puffy white cloud on the bank to the other, high enough to wade under, a toy sailing boat lies at anchor near the
 * sandbox and a rubber duck floats by: these are models ({@link #props}) on invisible footings.
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
                    q.set(canvas, x, G, az + dz, ToyKit.FOOTING);
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
            q.set(canvas, 0, G, az + dz, ToyKit.FOOTING);
        }
        for (int x : new int[] {-1, 1}) {
            q.set(canvas, x, G, az + 1, ToyKit.FOOTING);
        }
    }

    /**
     * A toy sand bucket by the sandbox (quarter coordinates): light blue with a white rim, full of sand, a little sand
     * spilled beside it.
     */
    private static void bucket(MapCanvas canvas, Quarter q) {
        int ax = 11;
        int az = 9;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                q.set(canvas, ax + dx, G + 1, az + dz, ToyKit.SKY);
                q.set(canvas, ax + dx, G + 2, az + dz, dx == 0 && dz == 0 ? ToyKit.SAND : ToyKit.WHITE);
            }
        }
        q.set(canvas, ax - 2, G + 1, az + 2, ToyKit.SAND);
        q.set(canvas, ax - 1, G + 1, az + 2, ToyKit.SAND);
    }

    /**
     * A little sand castle on the sandbox's rim, just outside the dragon's ring (quarter coordinates): three towers of
     * sand, the middle one two high with a red candle for a flag.
     */
    private static void sandCastle(MapCanvas canvas, Quarter q) {
        q.set(canvas, 7, G, 8, ToyKit.SAND);
        q.set(canvas, 7, G + 1, 8, ToyKit.SAND);
        q.place(canvas, 7, G + 2, 8, BlockSpec.of(Material.RED_CANDLE).lit(true));
        q.set(canvas, 6, G, 9, ToyKit.SAND);
        q.set(canvas, 8, G, 7, ToyKit.SAND);
    }

    /**
     * A toy spade stuck in the sandbox's sand outside the dragon's ring: a yellow blade half buried, a deep red handle
     * and a red grip.
     */
    private static void shovel(MapCanvas canvas, Quarter q) {
        int ax = 7;
        int az = 8;
        q.set(canvas, ax, G, az, ToyKit.YELLOW);
        q.set(canvas, ax, G + 1, az, BlockSpec.of(Material.RED_NETHER_BRICK_WALL));
        q.set(canvas, ax, G + 2, az, BlockSpec.of(Material.RED_NETHER_BRICK_WALL));
        q.set(canvas, ax, G + 3, az, ToyKit.RED);
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
     * on the water, their bows towards the middle of the map, and the rainbow over each arm of the river between two
     * sleepy clouds.
     */
    static void props(List<LaneSites.ModelProp> out) {
        int middle = (ToyLayout.RIVER_Z1 + ToyLayout.RIVER_Z2) / 2;
        for (int s : new int[] {-1, 1}) {
            Quarter q = new Quarter(1, s);
            out.add(q.prop("boat", 0.5, SURFACE, BOAT_Z + 0.5, q.yaw(0, -1), PropMotion.BOB));
            out.add(q.prop("duck", 0.5, SURFACE, DUCK_Z + 1.5, q.yaw(0, -1), PropMotion.BOB));
            out.add(q.prop("rainbow", 0.5, G + 1, middle + 0.5, q.yaw(0, -1), PropMotion.STILL));
        }
    }
}
