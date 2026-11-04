package io.github.sx2xoox.mcpl.core.map.toykingdom;

import static io.github.sx2xoox.mcpl.core.map.toykingdom.ToyKit.G;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.MapZone;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites.Lane;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites.Side;
import io.github.sx2xoox.mcpl.core.map.toykingdom.ToyKit.Quarter;

/**
 * A side's toy castle (BLUE: light blue walls and blue roofs, RED: pink walls and red roofs) around the core.
 *
 * <p>Outside: two-block walls seven high on a plinth in the team colour, a white band and merlons on top, a big heart
 * on every wall face between a gate and a turret, gates under stepped arches with a keystone, a round turret with
 * windows, a cone roof and a yellow pennant on every corner; before the front gate a lowered drawbridge on a checkered
 * mat, guarded by two toy soldiers.
 *
 * <p>Inside: the core's stage in the middle (a white plate with a pastel ring, open {@link ToyLayout#CORE_RADIUS}
 * around and {@link ToyLayout#CORE_CLEARANCE} up) between four candy pillars, carpets in the team colour from the
 * three gates to the stage, a spawn mat inside each lane gate, the shop stall against the side wall on the team's left
 * (as it faces the enemy) between the front turret and the lane gate, two flower boxes inside the front gate and a
 * checker of floorboards and white tiles.
 */
final class ToyCastleZone implements MapZone {

    /** Height (above the ground) of the turret windows. */
    private static final int WINDOW_Y = 6;
    /** The stage's pillars, in quarter coordinates (their low corner; 2 x 2 each). */
    private static final int[] PILLAR_AX = {ToyLayout.CORE_AX - 9, ToyLayout.CORE_AX + 8};
    private static final int PILLAR_AZ = 8;
    private static final int PILLAR_HEIGHT = 9;
    /** The flower boxes inside the front gate. */
    private static final int PLANTER_AX = ToyLayout.CASTLE_FRONT + 3;
    private static final int PLANTER_AZ = 5;
    /** A heart, bottom row first. */
    private static final String[] HEART = {"..X..", ".XXX.", "XXXXX", ".X.X."};

    private final Side side;

    ToyCastleZone(Side side) {
        this.side = side;
    }

    @Override
    public String name() {
        return side == Side.BLUE ? "blue-castle" : "red-castle";
    }

    @Override
    public boolean contains(int x, int z) {
        return ToyLayout.partOf(x, z).equals(name());
    }

    @Override
    public void build(MapCanvas canvas) {
        int sx = ToyLayout.sign(side);
        int r = ToyLayout.BUILD_RADIUS;
        for (int ax = ToyLayout.CASTLE_ZONE_FRONT; ax <= r; ax++) {
            for (int z = -ToyLayout.CASTLE_ZONE_HALF_Z; z <= ToyLayout.CASTLE_ZONE_HALF_Z; z++) {
                if (contains(sx * ax, z)) {
                    ToyKit.ground(canvas, sx * ax, z);
                }
            }
        }
        for (Lane lane : Lane.values()) {
            Quarter q = Quarter.of(side, lane);
            walls(canvas, q);
            hearts(canvas, q);
            for (int ax : new int[] {ToyLayout.CASTLE_FRONT + 1, ToyLayout.CASTLE_BACK - 1}) {
                turret(canvas, q, ax, ToyLayout.CASTLE_HALF_Z - 1);
            }
            soldier(canvas, q, ToyLayout.CASTLE_FRONT - 2, ToyLayout.GATE_HALF + 2);
            planter(canvas, q, PLANTER_AX, PLANTER_AZ);
            for (int ax : PILLAR_AX) {
                pillar(canvas, q, ax, PILLAR_AZ);
            }
        }
        shop(canvas, new Quarter(sx, ToyLayout.left(side)));
        // The castle's back wall stands against the kingdom's toy wall: the strip between them is toy wall too.
        for (int ax = ToyLayout.CASTLE_BACK + 1; ax <= r; ax++) {
            for (int z = -ToyLayout.CASTLE_ZONE_HALF_Z; z <= ToyLayout.CASTLE_ZONE_HALF_Z; z++) {
                if (contains(sx * ax, z)) {
                    ToyKit.brick(canvas, sx * ax, z, 3, ToyBorderZone.colour(sx * ax, z));
                }
            }
        }
    }

    /**
     * The walls of one half (north or south) of the castle: two blocks thick on a plinth in the team colour, a white
     * band and merlons on top, the gates under stepped arches with a keystone.
     */
    private void walls(MapCanvas canvas, Quarter q) {
        BlockSpec body = ToyKit.pastel(side);
        BlockSpec strong = ToyKit.strong(side);
        int front = ToyLayout.CASTLE_FRONT;
        int back = ToyLayout.CASTLE_BACK;
        int half = ToyLayout.CASTLE_HALF_Z;
        int top = G + ToyLayout.WALL_HEIGHT;
        for (int ax = front; ax <= back; ax++) {
            for (int az = 0; az <= half; az++) {
                boolean sideWall = az >= half - 1;
                boolean end = ax <= front + 1 || ax >= back - 1;
                if (!sideWall && !end) {
                    continue;
                }
                boolean laneGate = sideWall && Math.abs(ax - ToyLayout.GATE_AX) <= ToyLayout.GATE_HALF;
                boolean frontGate = !sideWall && ax <= front + 1 && az <= ToyLayout.GATE_HALF;
                boolean gate = laneGate || frontGate;
                int fromMiddle = laneGate ? Math.abs(ax - ToyLayout.GATE_AX) : az;
                // The arch: open up to three below the band at its sides, one below in the middle.
                int open = !gate ? G : fromMiddle == ToyLayout.GATE_HALF ? top - 3 : fromMiddle >= 2 ? top - 2
                        : top - 1;
                for (int y = open + 1; y <= top; y++) {
                    BlockSpec spec;
                    if (y == top) {
                        spec = gate && fromMiddle == 0 ? strong : ToyKit.WHITE;
                    } else if (gate || y == G + 1) {
                        spec = strong;
                    } else {
                        spec = body;
                    }
                    q.set(canvas, ax, y, az, spec);
                }
                // Merlons on the outer row of the wall, every other block.
                boolean outer = sideWall ? az == half : ax == front || ax == back;
                int along = sideWall ? ax : az;
                if (outer && along % 2 == 0) {
                    q.set(canvas, ax, top + 1, az, ToyKit.WHITE);
                }
            }
        }
    }

    /** A heart in the team colour on the outer face of every wall stretch between a turret and a gate. */
    private void hearts(MapCanvas canvas, Quarter q) {
        int front = ToyLayout.CASTLE_FRONT;
        int half = ToyLayout.CASTLE_HALF_Z;
        int turretReach = 4;
        // Side wall: between the front turret and the lane gate, and between the gate and the back turret.
        int[] sideCentres = {(front + 1 + turretReach + ToyLayout.GATE_AX - ToyLayout.GATE_HALF - 1) / 2,
            (ToyLayout.GATE_AX + ToyLayout.GATE_HALF + 1 + ToyLayout.CASTLE_BACK - 1 - turretReach) / 2};
        for (int c : sideCentres) {
            heart(canvas, q, c, half, true);
        }
        // Front wall: between the front gate and the corner turret.
        heart(canvas, q, front, (ToyLayout.GATE_HALF + 1 + half - 1 - turretReach) / 2, false);
    }

    private void heart(MapCanvas canvas, Quarter q, int ax, int az, boolean alongX) {
        for (int row = 0; row < HEART.length; row++) {
            for (int i = 0; i < 5; i++) {
                if (HEART[row].charAt(i) != 'X') {
                    continue;
                }
                int offset = i - 2;
                // The pattern is symmetric, so it reads the same from either side.
                q.set(canvas, alongX ? ax + offset : ax, G + 2 + row, alongX ? az : az + offset, ToyKit.strong(side));
            }
        }
    }

    /**
     * A round turret on a corner: a pastel tower on a plinth with windows and a white band, a cone roof in the side's
     * strong colour and a yellow pennant on its tip.
     */
    private void turret(MapCanvas canvas, Quarter q, int cax, int caz) {
        double radius = 3.2;
        int top = G + ToyLayout.WALL_HEIGHT + 3;
        BlockSpec strong = ToyKit.strong(side);
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                double d = Math.hypot(dx, dz);
                if (d > radius) {
                    continue;
                }
                // Two-block windows on the four sides, in the team colour.
                boolean window = Math.abs(dx) + Math.abs(dz) == 3 && (dx == 0 || dz == 0);
                for (int y = G + 1; y <= top; y++) {
                    BlockSpec spec = y == top ? ToyKit.WHITE
                            : y == G + 1 || window && y >= G + WINDOW_Y && y <= G + WINDOW_Y + 1 ? strong
                            : ToyKit.pastel(side);
                    q.set(canvas, cax + dx, y, caz + dz, spec);
                }
                // The cone: one ring narrower per layer.
                int layers = (int) Math.floor(radius + 0.8 - d) + 1;
                for (int i = 1; i <= layers; i++) {
                    q.set(canvas, cax + dx, top + i, caz + dz, strong);
                }
            }
        }
        // A yellow pennant on the tip of the cone, flying away from the middle of the map.
        int tip = top + (int) Math.floor(radius + 0.8) + 1;
        for (int y = tip + 1; y <= tip + 3; y++) {
            q.set(canvas, cax, y, caz, ToyKit.WHITE);
        }
        for (int i = 1; i <= 2; i++) {
            q.set(canvas, cax + i, tip + 3, caz, ToyKit.YELLOW);
        }
        q.set(canvas, cax + 1, tip + 2, caz, ToyKit.YELLOW);
    }

    /**
     * A candy pillar by the stage: 2 x 2, striped in the team colour and white, a yellow cap and glowing sweets on top
     * (they light the stage at night).
     */
    private void pillar(MapCanvas canvas, Quarter q, int ax, int az) {
        for (int dx = 0; dx <= 1; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                for (int i = 1; i <= PILLAR_HEIGHT; i++) {
                    BlockSpec spec = i == PILLAR_HEIGHT ? ToyKit.YELLOW
                            : (i - 1) / 2 % 2 == 0 ? ToyKit.strong(side) : ToyKit.WHITE;
                    q.set(canvas, ax + dx, G + i, az + dz, spec);
                }
            }
        }
        q.set(canvas, ax, G + PILLAR_HEIGHT + 1, az, ToyKit.GLOW);
        q.set(canvas, ax + 1, G + PILLAR_HEIGHT + 1, az + 1, ToyKit.GLOW);
        q.set(canvas, ax + 1, G + PILLAR_HEIGHT + 1, az, ToyKit.YELLOW);
        q.set(canvas, ax, G + PILLAR_HEIGHT + 1, az + 1, ToyKit.YELLOW);
    }

    /** A flower box: a white box with a round mint topiary growing out of it. */
    private static void planter(MapCanvas canvas, Quarter q, int ax, int az) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                boolean rim = dx != 0 || dz != 0;
                q.set(canvas, ax + dx, G + 1, az + dz, rim ? ToyKit.WHITE : ToyKit.MAT);
                if (dx == 0 || dz == 0) {
                    q.set(canvas, ax + dx, G + 2, az + dz, ToyKit.MAT);
                }
            }
        }
        q.set(canvas, ax, G + 3, az, ToyKit.MAT);
    }

    /**
     * A wooden toy soldier on guard beside the front gate: white trousers, a coat and a tall busby in the side's strong
     * colour, a birch face, arms at its sides.
     */
    private void soldier(MapCanvas canvas, Quarter q, int ax, int az) {
        BlockSpec coat = ToyKit.strong(side);
        BlockSpec[] column = {ToyKit.WHITE, ToyKit.WHITE, coat, coat, ToyKit.WOOD, coat, coat};
        for (int i = 0; i < column.length; i++) {
            q.set(canvas, ax, G + 1 + i, az, column[i]);
        }
        for (int s : new int[] {-1, 1}) {
            q.set(canvas, ax, G + 3, az + s, coat);
            q.set(canvas, ax, G + 4, az + s, coat);
        }
    }

    /**
     * The shop stall against the side wall on the team's left: a white counter facing the courtyard, the shopkeeper's
     * spot behind it, a striped awning held by two white posts against the wall, a sign in the team colour on the wall
     * and a yellow coin on top.
     */
    private void shop(MapCanvas canvas, Quarter q) {
        int back = ToyLayout.CASTLE_HALF_Z - 2;
        for (int ax = ToyLayout.SHOP_AX1 + 1; ax <= ToyLayout.SHOP_AX2 - 1; ax++) {
            q.set(canvas, ax, G + 1, ToyLayout.SHOP_Z1, ToyKit.WHITE);
        }
        for (int ax : new int[] {ToyLayout.SHOP_AX1, ToyLayout.SHOP_AX2}) {
            for (int y = G + 1; y <= G + 3; y++) {
                q.set(canvas, ax, y, back, ToyKit.WHITE);
            }
        }
        for (int ax = ToyLayout.SHOP_AX1; ax <= ToyLayout.SHOP_AX2; ax++) {
            for (int az = ToyLayout.SHOP_Z1; az <= back; az++) {
                q.set(canvas, ax, G + 4, az, Math.floorMod(ax, 2) == 0 ? ToyKit.WHITE : ToyKit.strong(side));
            }
        }
        q.set(canvas, ToyLayout.SHOP_AX, G + 3, back, ToyKit.strong(side));
        q.set(canvas, ToyLayout.SHOP_AX, G + 5, ToyLayout.SHOP_AZ, ToyKit.YELLOW);
    }
}
