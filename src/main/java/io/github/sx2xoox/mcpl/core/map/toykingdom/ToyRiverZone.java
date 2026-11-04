package io.github.sx2xoox.mcpl.core.map.toykingdom;

import static io.github.sx2xoox.mcpl.core.map.toykingdom.ToyKit.G;

import java.util.Map;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.MapZone;
import io.github.sx2xoox.mcpl.core.map.toykingdom.ToyKit.Quarter;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

/**
 * The middle: a shallow river along {@code x = 0} from lane to lane (one block of water on sand, so anyone can wade
 * through and step out) and, where it would cross the corridor, the toy dragon's round sandbox: sand one block down in
 * a birch frame, open to the sky. Halfway along each arm of the river a toy rainbow (red, yellow, light blue) arches
 * over the water from one puffy white cloud on the bank to the other, high enough to wade under, and a toy sailing boat
 * lies at anchor near the sandbox.
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
    /** The sail beside the mast (the last column), bottom row first: white with a red stripe, its edge rounded. */
    private static final String[] SAIL = {"WWWF", ".RRF", "..WF"};

    /**
     * A toy sailing boat at anchor in the river (z: the hull's middle): a flat deck three wide in the water (red sides,
     * a wooden middle), a pointed white bow towards the middle of the map and a white stern, a thin wooden mast with a
     * red and white sail (its edge rounded) towards the middle and a red pennant flying the other way; there is room
     * to wade past on both sides.
     */
    private static void boat(MapCanvas canvas, int z) {
        Quarter q = new Quarter(1, Integer.signum(z));
        int az = Math.abs(z);
        // A flat deck in the water (one step up from the river, so anyone can climb aboard): red sides, a wooden
        // middle, a pointed white bow and a white stern.
        for (int dz = -2; dz <= 2; dz++) {
            for (int x = -1; x <= 1; x++) {
                if (Math.abs(dz) < 2 || x == 0) {
                    q.set(canvas, x, G, az + dz, x == 0 ? ToyKit.WOOD : ToyKit.RED);
                }
            }
        }
        q.place(canvas, 0, G + 1, az - 2, ToyKit.stairs(ToyKit.WHITE_STAIRS, BlockFace.SOUTH, false));
        q.place(canvas, 0, G + 1, az + 2, ToyKit.stairs(ToyKit.WHITE_STAIRS, BlockFace.NORTH, false));
        for (int y = G + 1; y <= G + 6; y++) {
            q.set(canvas, 0, y, az, ToyKit.FENCE);
        }
        ToyKit.plane(canvas, q, false, az - 3, 0, G + 3, SAIL, true,
                Map.of('W', ToyKit.WHITE, 'R', ToyKit.RED, 'F', ToyKit.FENCE));
        q.place(canvas, 0, G + 6, az + 1, BlockSpec.of(Material.RED_WALL_BANNER).facing(BlockFace.SOUTH));
    }

    /** Outer radius of the rainbow (its feet stand on the banks, inside the river zone). */
    private static final double RAINBOW_RADIUS = 6.5;

    /**
     * A rainbow across the river at {@code z}: three bands one block thick (its outline rounded with stairs of the
     * second tones), a puffy cloud at each foot (rounded with quartz stairs).
     */
    private static void rainbow(MapCanvas canvas, int z) {
        int r = (int) Math.floor(RAINBOW_RADIUS);
        String[] rows = new String[r + 1];
        for (int dy = 0; dy <= r; dy++) {
            StringBuilder row = new StringBuilder();
            for (int x = -r; x <= r; x++) {
                double d = Math.hypot(x, dy);
                row.append(d > RAINBOW_RADIUS || d <= RAINBOW_RADIUS - 3 ? '.'
                        : d <= RAINBOW_RADIUS - 2 ? 'S' : d <= RAINBOW_RADIUS - 1 ? 'Y' : 'R');
            }
            rows[dy] = row.toString();
        }
        Quarter q = new Quarter(1, 1);
        ToyKit.plane(canvas, q, true, -r, z, G + 1, rows, true,
                Map.of('R', ToyKit.RED, 'Y', ToyKit.YELLOW, 'S', ToyKit.SKY));
        for (int s : new int[] {-1, 1}) {
            Quarter side = new Quarter(s, 1);
            ToyKit.lathe(canvas, side, r - 1, z, G + 1, new double[] {1.5, 1}, new BlockSpec[] {ToyKit.WHITE,
                ToyKit.WHITE});
            for (int dz : new int[] {-2, 2}) {
                side.place(canvas, r - 1, G + 1, z + dz, ToyKit.bevel(ToyKit.WHITE_STAIRS, 0, dz, false));
            }
        }
    }
}
