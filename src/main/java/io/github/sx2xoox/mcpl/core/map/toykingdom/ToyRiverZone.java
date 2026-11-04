package io.github.sx2xoox.mcpl.core.map.toykingdom;

import static io.github.sx2xoox.mcpl.core.map.toykingdom.ToyKit.G;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.MapZone;

/**
 * The middle: a shallow river along {@code x = 0} from lane to lane (one block of water on sand, so anyone can wade
 * through and step out) and, where it would cross the corridor, the toy dragon's round sandbox: sand one block down in
 * a birch frame, open to the sky. Halfway along each arm of the river a toy rainbow (red, yellow, light blue) arches
 * over the water from one white cloud on the bank to the other, high enough to wade under, and a toy sailing boat lies
 * at anchor near the sandbox.
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
    }

    /** Where the toy sailing boats lie at anchor (centre of the hull, |z|). */
    private static final int BOAT_Z = ToyLayout.RIVER_Z1 + 4;

    /**
     * A toy sailing boat at anchor in the river (z: the hull's middle): a flat wooden deck three wide in the water, a
     * white bow and stern, a white mast and a red and white sail; there is room to wade past on both sides.
     */
    private static void boat(MapCanvas canvas, int z) {
        int dir = Integer.signum(z);
        // A flat deck in the water (one step up from the river), a white bow and stern.
        for (int dz = -2; dz <= 2; dz++) {
            for (int x = -1; x <= 1; x++) {
                if (Math.abs(dz) < 2 || x == 0) {
                    canvas.set(x, G, z + dz, ToyKit.WOOD);
                }
            }
        }
        canvas.set(0, G + 1, z - 2, ToyKit.WHITE);
        canvas.set(0, G + 1, z + 2, ToyKit.WHITE);
        for (int y = G + 1; y <= G + 6; y++) {
            canvas.set(0, y, z, ToyKit.WHITE);
        }
        // The sail, beside the mast towards the middle of the map: three rows narrowing upwards, a red stripe.
        for (int row = 0; row < 3; row++) {
            for (int i = 1; i <= 3 - row; i++) {
                canvas.set(0, G + 3 + row, z - dir * i, row == 1 ? ToyKit.RED : ToyKit.WHITE);
            }
        }
    }

    /** Outer radius of the rainbow (its feet stand on the banks, inside the river zone). */
    private static final double RAINBOW_RADIUS = 6.5;

    /** A rainbow across the river at {@code z}: three bands one block thick, a cloud at each foot. */
    private static void rainbow(MapCanvas canvas, int z) {
        int r = (int) Math.floor(RAINBOW_RADIUS);
        for (int x = -r; x <= r; x++) {
            for (int dy = 0; dy <= r; dy++) {
                double d = Math.hypot(x, dy);
                if (d > RAINBOW_RADIUS || d <= RAINBOW_RADIUS - 3) {
                    continue;
                }
                BlockSpec band = d <= RAINBOW_RADIUS - 2 ? ToyKit.SKY : d <= RAINBOW_RADIUS - 1 ? ToyKit.YELLOW
                        : ToyKit.RED;
                canvas.set(x, G + 1 + dy, z, band);
            }
        }
        for (int s : new int[] {-1, 1}) {
            for (int ax = r - 2; ax <= r; ax++) {
                for (int dz = -1; dz <= 1; dz++) {
                    canvas.set(s * ax, G + 1, z + dz, ToyKit.WHITE);
                }
                canvas.set(s * ax, G + 2, z, ToyKit.WHITE);
            }
            canvas.set(s * (r - 1), G + 2, z - 1, ToyKit.WHITE);
            canvas.set(s * (r - 1), G + 2, z + 1, ToyKit.WHITE);
        }
    }
}
