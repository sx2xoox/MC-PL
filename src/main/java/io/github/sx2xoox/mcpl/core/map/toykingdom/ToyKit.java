package io.github.sx2xoox.mcpl.core.map.toykingdom;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites.Lane;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites.Side;
import org.bukkit.Material;

/**
 * The blocks of 장난감 왕국 and the shared pieces: the floor of every column (a mint play mat of big tiles on wooden
 * floorboards) and the toy props (toy brick walls, cotton candy and sponge balls, candy canes, lollipop lamps, toy
 * blocks). Props are placed in quarter coordinates ({@code ax, az >= 0}) through a {@link Quarter}, so both halves (and
 * both lanes) are mirror images.
 */
final class ToyKit {

    private ToyKit() {
    }

    static final int G = ToyLayout.GROUND_Y;

    /** The play mat: big tiles of two mints. */
    static final BlockSpec MAT = BlockSpec.of(Material.LIME_WOOL);
    static final BlockSpec MAT_TILE = BlockSpec.of(Material.LIME_CONCRETE);
    /** Wooden floorboards: the room's floor around the mat, under it, the courtyards and the sandbox frame. */
    static final BlockSpec WOOD = BlockSpec.of(Material.BIRCH_PLANKS);
    static final BlockSpec WHITE = BlockSpec.of(Material.WHITE_CONCRETE);
    static final BlockSpec YELLOW = BlockSpec.of(Material.YELLOW_CONCRETE);
    static final BlockSpec SKY = BlockSpec.of(Material.LIGHT_BLUE_CONCRETE);
    static final BlockSpec PINK = BlockSpec.of(Material.PINK_CONCRETE);
    static final BlockSpec BLUE = BlockSpec.of(Material.BLUE_CONCRETE);
    static final BlockSpec RED = BlockSpec.of(Material.RED_CONCRETE);
    static final BlockSpec SAND = BlockSpec.of(Material.SAND);
    static final BlockSpec WATER = BlockSpec.of(Material.WATER);
    static final BlockSpec JELLY = BlockSpec.of(Material.SLIME_BLOCK);
    /** Soft toys: cotton candy (pink wool) and sponge. */
    static final BlockSpec COTTON = BlockSpec.of(Material.PINK_WOOL);
    static final BlockSpec SPONGE = BlockSpec.of(Material.SPONGE);
    /** The glowing sweet in the middle of a lollipop lamp (and the clock's hour marks). */
    static final BlockSpec GLOW = BlockSpec.of(Material.PEARLESCENT_FROGLIGHT);

    /** The colours of toy bricks, in turn. */
    static final BlockSpec[] TOY_COLOURS = {PINK, YELLOW, SKY, WHITE};

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

    /** The play mat at a column: 4 x 4 tiles of two mints. */
    static BlockSpec mat(int x, int z) {
        return Math.floorMod(Math.floorDiv(x, 4) + Math.floorDiv(z, 4), 2) == 0 ? MAT : MAT_TILE;
    }

    /** Builds the ground of one column: floorboards under a top block chosen by the plan (nothing over a drop). */
    static void ground(MapCanvas canvas, int x, int z) {
        double out = ToyLayout.outline(x, z);
        if (out > ToyLayout.HEDGE_WIDTH + ToyLayout.APRON_WIDTH || ToyLayout.inDrop(x, z)) {
            return;
        }
        for (int y = G - 3; y < G; y++) {
            canvas.set(x, y, z, WOOD);
        }
        if (ToyLayout.onCliffEdge(x, z)) {
            // Red and white warning stripes along the edge of a drop.
            canvas.set(x, G, z, Math.floorMod(x + z, 2) == 0 ? RED : WHITE);
            return;
        }
        if (out > 0) {
            canvas.set(x, G, z, WOOD);
            return;
        }
        Side side = ToyLayout.sideOf(x);
        int ax = Math.abs(x);
        if (ToyLayout.inCastle(x, z)) {
            canvas.set(x, G, z, castleFloor(side, ax, z));
            return;
        }
        if (ToyLayout.onFrontPlaza(x, z)) {
            // A checkerboard mat in the side's pastel and white, like a toy board at the castle's door; the lowered
            // drawbridge (floorboards) runs across it from the gate.
            boolean bridge = Math.abs(z) <= ToyLayout.GATE_HALF - 1 && ax >= ToyLayout.CASTLE_ZONE_FRONT;
            canvas.set(x, G, z, bridge ? WOOD : Math.floorMod(ax + z, 2) == 0 ? WHITE : pastel(side));
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
            top = mat(x, z);
        }
        canvas.set(x, G, z, top);
    }

    /**
     * The courtyard floor: the core's stage (a white plate with a pastel ring, rimmed in the team colour and white),
     * carpets in the team colour from the gates to the stage, the spawn mat (pastel, white spots, a team coloured
     * frame) and a checker of floorboards and white tiles everywhere else.
     */
    private static BlockSpec castleFloor(Side side, int ax, int z) {
        int az = Math.abs(z);
        double core = Math.hypot(ax - ToyLayout.CORE_AX, z);
        double r = ToyLayout.CORE_RADIUS;
        if (core <= r + 0.5) {
            return core > r - 3.5 && core <= r - 2.5 ? pastel(side) : WHITE;
        }
        if (core <= r + 1.5) {
            return strong(side);
        }
        if (core <= r + 2.5) {
            return WHITE;
        }
        boolean frontCarpet = ax < ToyLayout.CORE_AX && az <= 1;
        boolean laneCarpet = Math.abs(ax - ToyLayout.GATE_AX) <= 1;
        if (frontCarpet || laneCarpet) {
            return strong(side);
        }
        int x = ToyLayout.sign(side) * ax;
        if (ToyLayout.inSpawnMat(x, z, false)) {
            boolean spot = false;
            for (int[] s : ToyLayout.SPAWN_SPOTS) {
                spot |= s[0] == ax && s[1] == az;
            }
            return spot ? WHITE : pastel(side);
        }
        if (ToyLayout.inSpawnMat(x, z, true)) {
            return strong(side);
        }
        return Math.floorMod(Math.floorDiv(ax, 2) + Math.floorDiv(z, 2), 2) == 0 ? WOOD : WHITE;
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
     * A column of a toy brick wall: {@code height} blocks of {@code colour}, the top course studded with white every
     * other block (by the real column, so walls of both halves look alike).
     */
    static void brick(MapCanvas canvas, int x, int z, int height, BlockSpec colour) {
        for (int i = 1; i <= height; i++) {
            boolean stud = i == height && Math.floorMod(x + z, 2) == 0;
            canvas.set(x, G + i, z, stud ? WHITE : colour);
        }
    }

    /**
     * A toy brick wall along a segment in quarter coordinates: three blocks high, two at its rounded edge, so nobody
     * hops onto it.
     */
    static void wall(MapCanvas canvas, Quarter q, double ax1, double az1, double ax2, double az2, double radius,
                     BlockSpec colour) {
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
                brick(canvas, q.x(ax), q.z(az), d <= radius - 1 ? 3 : 2, colour);
            }
        }
    }

    /** A soft round toy resting on the ground (a squashed ball), of cotton candy or sponge. */
    static void ball(MapCanvas canvas, Quarter q, int ax, int az, double radius, BlockSpec spec) {
        int r = (int) Math.ceil(radius);
        double height = radius * 0.9;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = 0; dy <= r * 2; dy++) {
                    double ny = (dy + 0.5 - height) / height;
                    double nx = dx / (radius + 0.25);
                    double nz = dz / (radius + 0.25);
                    if (nx * nx + ny * ny + nz * nz <= 1) {
                        q.set(canvas, ax + dx, G + 1 + dy, az + dz, spec);
                    }
                }
            }
        }
    }

    /**
     * A cotton candy tree: a white stick of {@code stick} blocks and a pink cotton ball of {@code radius} on it, high
     * enough to walk under.
     */
    static void cottonCandy(MapCanvas canvas, Quarter q, int ax, int az, int stick, double radius) {
        for (int i = 1; i <= stick; i++) {
            q.set(canvas, ax, G + i, az, WHITE);
        }
        int r = (int) Math.ceil(radius);
        int cy = G + stick + r;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -r; dy <= r; dy++) {
                    double ny = dy / (radius * 0.8);
                    if (dx * dx / (radius * radius) + dz * dz / (radius * radius) + ny * ny <= 1) {
                        q.set(canvas, ax + dx, cy + dy, az + dz, COTTON);
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

    /**
     * A lollipop lamp: a white stick of {@code height} with a round sweet on top (a 3 x 3 disc across x, red and white
     * swirl around a glowing middle), so the verges are lit at night too.
     */
    static void lollipop(MapCanvas canvas, Quarter q, int ax, int az, int height) {
        for (int i = 1; i <= height; i++) {
            q.set(canvas, ax, G + i, az, WHITE);
        }
        int cy = G + height + 2;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                BlockSpec spec = dx == 0 && dy == 0 ? GLOW : (dx + dy + 2) % 2 == 0 ? WHITE : RED;
                q.set(canvas, ax + dx, cy + dy, az, spec);
            }
        }
    }

    /** A toy building block: a cube of {@code size} with its low corner at {@code ax, y, az}. */
    static void toyBlock(MapCanvas canvas, Quarter q, int ax, int y, int az, int size, BlockSpec spec) {
        box(canvas, q, ax, y, az, size, size, size, spec);
    }

    /** A box of {@code sx} by {@code sy} by {@code sz} blocks with its low corner at {@code ax, y, az}. */
    static void box(MapCanvas canvas, Quarter q, int ax, int y, int az, int sx, int sy, int sz, BlockSpec spec) {
        for (int dx = 0; dx < sx; dx++) {
            for (int dz = 0; dz < sz; dz++) {
                for (int dy = 0; dy < sy; dy++) {
                    q.set(canvas, ax + dx, y + dy, az + dz, spec);
                }
            }
        }
    }

    /**
     * A toy box: a chest of {@code colour} with a white rim and lid edge, {@code sx} by {@code sz} and {@code height}
     * high, a few toy blocks peeking out of the top.
     */
    static void toyBox(MapCanvas canvas, Quarter q, int ax, int az, int sx, int sz, int height, BlockSpec colour) {
        for (int dx = 0; dx < sx; dx++) {
            for (int dz = 0; dz < sz; dz++) {
                boolean edge = dx == 0 || dz == 0 || dx == sx - 1 || dz == sz - 1;
                for (int y = G + 1; y <= G + height; y++) {
                    q.set(canvas, ax + dx, y, az + dz, y == G + height && edge ? WHITE : colour);
                }
            }
        }
        q.set(canvas, ax + 1, G + height + 1, az + 1, YELLOW);
        q.set(canvas, ax + sx - 2, G + height + 1, az + sz - 2, SKY);
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
