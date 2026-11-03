package io.github.sx2xoox.mcpl.core.map.toykingdom;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites.Lane;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites.Side;
import org.bukkit.Material;

/**
 * The few blocks of 장난감 왕국 and the shared pieces: the ground of every column and the toy props (ball bushes,
 * hedges, candy canes, toy blocks, cone roofs). Props are placed in quarter coordinates ({@code ax, az >= 0}) through a
 * {@link Quarter}, so both halves (and both lanes) are mirror images.
 */
final class ToyKit {

    private ToyKit() {
    }

    static final int G = ToyLayout.GROUND_Y;

    static final BlockSpec GRASS = BlockSpec.of(Material.GRASS_BLOCK);
    static final BlockSpec DIRT = BlockSpec.of(Material.DIRT);
    static final BlockSpec HEDGE = BlockSpec.of(Material.OAK_LEAVES);
    static final BlockSpec WHITE = BlockSpec.of(Material.WHITE_CONCRETE);
    static final BlockSpec YELLOW = BlockSpec.of(Material.YELLOW_CONCRETE);
    static final BlockSpec SKY = BlockSpec.of(Material.LIGHT_BLUE_CONCRETE);
    static final BlockSpec PINK = BlockSpec.of(Material.PINK_CONCRETE);
    static final BlockSpec BLUE = BlockSpec.of(Material.BLUE_CONCRETE);
    static final BlockSpec RED = BlockSpec.of(Material.RED_CONCRETE);
    static final BlockSpec WOOD = BlockSpec.of(Material.BIRCH_PLANKS);
    static final BlockSpec SAND = BlockSpec.of(Material.SAND);
    static final BlockSpec WATER = BlockSpec.of(Material.WATER);
    static final BlockSpec JELLY = BlockSpec.of(Material.SLIME_BLOCK);

    /** The road colour of a lane: TOP the yellow brick road, BOTTOM the light blue one. */
    static BlockSpec road(Lane lane) {
        return lane == Lane.TOP ? YELLOW : SKY;
    }

    /** A side's strong colour (roofs, rings) and its pastel (castle walls, spawn mat). */
    static BlockSpec strong(Side side) {
        return side == Side.BLUE ? BLUE : RED;
    }

    static BlockSpec pastel(Side side) {
        return side == Side.BLUE ? SKY : PINK;
    }

    // ------------------------------------------------------------------ ground

    /** Builds the ground of one column: dirt under a top block chosen by the plan. */
    static void ground(MapCanvas canvas, int x, int z) {
        double out = ToyLayout.outline(x, z);
        if (out > ToyLayout.HEDGE_WIDTH + ToyLayout.APRON_WIDTH) {
            return;
        }
        for (int y = G - 3; y < G; y++) {
            canvas.set(x, y, z, DIRT);
        }
        if (out > 0) {
            canvas.set(x, G, z, GRASS);
            return;
        }
        Side side = ToyLayout.sideOf(x);
        int ax = Math.abs(x);
        if (ToyLayout.inCastle(x, z)) {
            canvas.set(x, G, z, castleFloor(side, ax, z));
            return;
        }
        double centre = ToyLayout.centre(x, z);
        if (centre <= ToyLayout.PIT_RADIUS) {
            canvas.set(x, G - 1, z, SAND);
            return;
        }
        if (centre <= ToyLayout.PIT_FRAME) {
            canvas.set(x, G, z, WOOD);
            return;
        }
        if (ToyLayout.river(x, z) <= ToyLayout.RIVER_HALF) {
            canvas.set(x, G - 1, z, SAND);
            canvas.set(x, G, z, WATER);
            return;
        }
        Lane lane = ToyLayout.laneOf(z);
        double tower = ToyLayout.towerDistance(x, z);
        double d = ToyLayout.laneDistance(lane, x, z);
        BlockSpec top;
        if (tower <= ToyLayout.TOWER_RADIUS + 0.5) {
            top = road(lane);
        } else if (tower <= ToyLayout.RING_OUTER) {
            top = strong(side);
        } else if (d <= ToyLayout.ROAD_HALF) {
            top = road(lane);
        } else if (d <= ToyLayout.EDGE_HALF) {
            top = WHITE;
        } else {
            top = GRASS;
        }
        canvas.set(x, G, z, top);
    }

    /** The courtyard floor: wooden boards, the core's white plate in a team ring, the spawn mat. */
    private static BlockSpec castleFloor(Side side, int ax, int z) {
        double core = Math.hypot(ax - ToyLayout.CORE_AX, z);
        if (core <= ToyLayout.CORE_RADIUS + 0.5) {
            return WHITE;
        }
        if (core <= ToyLayout.CORE_RADIUS + 1.5) {
            return strong(side);
        }
        if (ax >= ToyLayout.SPAWN_AX1 && ax <= ToyLayout.SPAWN_AX2 && Math.abs(z) <= ToyLayout.SPAWN_HALF_Z) {
            return pastel(side);
        }
        return WOOD;
    }

    // ------------------------------------------------------------------ quarters

    /** Maps quarter coordinates ({@code ax, az >= 0}) to a real quarter of the map. */
    record Quarter(int sx, int sz) {

        static Quarter of(Side side, Lane lane) {
            return new Quarter(ToyLayout.sign(side), ToyLayout.sign(lane));
        }

        int x(int ax) {
            return sx * ax;
        }

        int z(int az) {
            return sz * az;
        }

        void set(MapCanvas canvas, int ax, int y, int az, BlockSpec spec) {
            canvas.set(x(ax), y, z(az), spec);
        }
    }

    // ------------------------------------------------------------------ props

    /**
     * A hedge along a segment in quarter coordinates: three blocks high, two at its rounded edge, so nobody hops onto
     * it.
     */
    static void hedge(MapCanvas canvas, Quarter q, double ax1, double az1, double ax2, double az2, double radius) {
        int minX = (int) Math.floor(Math.min(ax1, ax2) - radius);
        int maxX = (int) Math.ceil(Math.max(ax1, ax2) + radius);
        int minZ = (int) Math.floor(Math.min(az1, az2) - radius);
        int maxZ = (int) Math.ceil(Math.max(az1, az2) + radius);
        for (int ax = minX; ax <= maxX; ax++) {
            for (int az = minZ; az <= maxZ; az++) {
                double d = segment(ax, az, ax1, az1, ax2, az2);
                if (d > radius) {
                    continue;
                }
                int height = d <= radius - 1 ? 3 : 2;
                for (int y = G + 1; y <= G + height; y++) {
                    q.set(canvas, ax, y, az, HEDGE);
                }
            }
        }
    }

    /** A round bush resting on the ground (a squashed ball of leaves). */
    static void ball(MapCanvas canvas, Quarter q, int ax, int az, double radius) {
        int r = (int) Math.ceil(radius);
        double height = radius * 0.9;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = 0; dy <= r * 2; dy++) {
                    double ny = (dy + 0.5 - height) / height;
                    double nx = dx / (radius + 0.25);
                    double nz = dz / (radius + 0.25);
                    if (nx * nx + ny * ny + nz * nz <= 1) {
                        q.set(canvas, ax + dx, G + 1 + dy, az + dz, HEDGE);
                    }
                }
            }
        }
    }

    /**
     * A candy cane: a red and white striped post with its hook bent towards {@code (hookAx, hookAz)} (one block over,
     * then down one).
     */
    static void candyCane(MapCanvas canvas, Quarter q, int ax, int az, int hookAx, int hookAz, int height) {
        for (int i = 0; i < height; i++) {
            q.set(canvas, ax, G + 1 + i, az, i % 2 == 0 ? RED : WHITE);
        }
        int top = G + height;
        boolean alongX = Math.abs(hookAx - ax) >= Math.abs(hookAz - az);
        int hx = ax + (alongX ? Integer.signum(hookAx - ax) : 0);
        int hz = az + (alongX ? 0 : Integer.signum(hookAz - az));
        q.set(canvas, ax, top + 1, az, height % 2 == 0 ? RED : WHITE);
        q.set(canvas, hx, top + 1, hz, height % 2 == 0 ? WHITE : RED);
        q.set(canvas, hx, top, hz, height % 2 == 0 ? RED : WHITE);
    }

    /** A toy building block: a cube of {@code size} with its low corner at {@code ax, y, az}. */
    static void toyBlock(MapCanvas canvas, Quarter q, int ax, int y, int az, int size, BlockSpec spec) {
        for (int dx = 0; dx < size; dx++) {
            for (int dz = 0; dz < size; dz++) {
                for (int dy = 0; dy < size; dy++) {
                    q.set(canvas, ax + dx, y + dy, az + dz, spec);
                }
            }
        }
    }

    /** Distance from a point to a segment. */
    static double segment(double px, double pz, double x1, double z1, double x2, double z2) {
        double dx = x2 - x1;
        double dz = z2 - z1;
        double len = dx * dx + dz * dz;
        double t = len == 0 ? 0 : Math.max(0, Math.min(1, ((px - x1) * dx + (pz - z1) * dz) / len));
        return Math.hypot(px - (x1 + t * dx), pz - (z1 + t * dz));
    }
}
