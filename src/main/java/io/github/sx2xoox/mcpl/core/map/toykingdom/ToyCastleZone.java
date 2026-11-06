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
 * A side's toy castle (BLUE: light blue walls and blue roofs, RED: pink walls and red roofs) around the core.
 *
 * <p>Outside: two-block walls seven high on a plinth in the team colour, a white band and merlons on top, a big heart
 * on every wall face between a gate and a turret, gates under stepped arches with a keystone, a round turret with
 * windows, a cone roof and a yellow pennant on every corner; before the front gate a lowered drawbridge on a checkered
 * mat, guarded by two toy soldiers.
 *
 * <p>Inside: the core's stage in the middle (a white plate with a pastel ring, open {@link ToyLayout#CORE_RADIUS}
 * around and {@link ToyLayout#CORE_CLEARANCE} up) between four candy pillars, carpets in the team colour from the
 * three gates to the stage, the shop stall against the side wall on the team's left (as it faces the enemy) between
 * the front turret and the lane gate with the spawn mat before it (in the front corner, inside the front gate), two
 * flower boxes inside the front gate, a music box in each back corner and a checker of floorboards and white tiles.
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
    /** The music boxes in the back corners of the courtyard (their low corner, 4 x 3). */
    private static final int MUSIC_BOX_AX = 85;
    private static final int MUSIC_BOX_AZ = 13;
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
            soldier(canvas, q, ToyLayout.CASTLE_FRONT - 1, ToyLayout.GATE_HALF + 2);
            planter(canvas, q, PLANTER_AX, PLANTER_AZ);
            musicBox(canvas, q, MUSIC_BOX_AX, MUSIC_BOX_AZ);
            for (int ax : PILLAR_AX) {
                pillar(canvas, q, ax, PILLAR_AZ);
            }
        }
        shop(canvas, new Quarter(sx, ToyLayout.left(side)));
        ToyKit.scatter(canvas, this);
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
                if (outer && !gate && ax != back) {
                    cornice(canvas, q, sideWall ? ax : ax - 1, sideWall ? az + 1 : az, sideWall, along);
                }
            }
        }
    }

    /**
     * The cornice outside a wall's top: a white overhang (upside down quartz stairs) under the band all along, and
     * below it a white corbel on every other block, so the flat pastel wall gets a shadow line and a rhythm. Not over
     * the gates, the turrets or the road at the lane gates.
     */
    private void cornice(MapCanvas canvas, Quarter q, int ax, int az, boolean sideWall, int along) {
        int top = G + ToyLayout.WALL_HEIGHT;
        if (!contains(q.x(ax), q.z(az))) {
            return;
        }
        for (int t : new int[] {ToyLayout.CASTLE_FRONT + 1, ToyLayout.CASTLE_BACK - 1}) {
            if (Math.hypot(ax - t, az - (ToyLayout.CASTLE_HALF_Z - 1)) <= 4.3) {
                return;
            }
        }
        if (sideWall && Math.abs(ax - ToyLayout.GATE_AX) <= ToyLayout.GATE_HALF + 2
                || !sideWall && az <= ToyLayout.GATE_HALF + 4) {
            return;
        }
        BlockFace in = sideWall ? BlockFace.NORTH : BlockFace.EAST;
        q.place(canvas, ax, top - 1, az, ToyKit.stairs(ToyKit.WHITE_STAIRS, in, true));
        if (along % 2 == 1) {
            q.place(canvas, ax, top - 2, az, ToyKit.stairs(ToyKit.WHITE_STAIRS, in, true));
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
                // Two-block windows on the four sides, in the team colour; those looking out of the castle are set
                // one block deep with a warm light inside, a white sill under them.
                boolean window = Math.abs(dx) + Math.abs(dz) == 3 && (dx == 0 || dz == 0);
                boolean outward = window && !ToyLayout.inCastle(q.x(cax + dx * 4 / 3), q.z(caz + dz * 4 / 3));
                boolean behind = Math.abs(dx) + Math.abs(dz) == 2 && (dx == 0 || dz == 0)
                        && !ToyLayout.inCastle(q.x(cax + dx * 2), q.z(caz + dz * 2));
                for (int y = G + 1; y <= top; y++) {
                    boolean pane = y >= G + WINDOW_Y && y <= G + WINDOW_Y + 1;
                    BlockSpec spec = y == top || y == G + WINDOW_Y - 1 && outward ? ToyKit.WHITE
                            : y == G + 1 || window && pane ? strong
                            : behind && pane ? ToyKit.GLOW
                            : ToyKit.pastel(side);
                    if (outward && pane) {
                        continue;
                    }
                    q.set(canvas, cax + dx, y, caz + dz, spec);
                }
                // The cone: one ring narrower per layer.
                int layers = (int) Math.floor(radius + 0.8 - d) + 1;
                for (int i = 1; i <= layers; i++) {
                    q.set(canvas, cax + dx, top + i, caz + dz, strong);
                }
            }
        }
        // The cone's eaves overhang the white band by a block, with a white trim under the overhang.
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                double d = Math.hypot(dx, dz);
                if (d > radius && d <= radius + 1.1 && contains(q.x(cax + dx), q.z(caz + dz))) {
                    q.set(canvas, cax + dx, top + 1, caz + dz, strong);
                    q.place(canvas, cax + dx, top, caz + dz, ToyKit.bevel(ToyKit.WHITE_STAIRS, dx, dz, true));
                }
            }
        }
        // A yellow pennant on a pole on the tip of the cone, flying away from the middle of the map.
        int tip = top + (int) Math.floor(radius + 0.8) + 1;
        for (int y = tip + 1; y <= tip + 3; y++) {
            q.set(canvas, cax, y, caz, ToyKit.FENCE);
        }
        q.place(canvas, cax + 1, tip + 3, caz, BlockSpec.of(Material.YELLOW_WALL_BANNER).facing(BlockFace.EAST));
    }

    /**
     * A candy pillar by the stage: 2 x 2, spiral striped in the team colour and white, a yellow band, four glowing
     * sweets (they light the stage at night) and a yellow wrapper twisted to a point on top.
     */
    private void pillar(MapCanvas canvas, Quarter q, int ax, int az) {
        int[][] around = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        for (int k = 0; k < around.length; k++) {
            int dx = around[k][0];
            int dz = around[k][1];
            for (int i = 1; i < PILLAR_HEIGHT; i++) {
                BlockSpec spec = (i + k) / 2 % 2 == 0 ? ToyKit.strong(side) : ToyKit.WHITE;
                q.set(canvas, ax + dx, G + i, az + dz, spec);
            }
            q.set(canvas, ax + dx, G + PILLAR_HEIGHT, az + dz, ToyKit.YELLOW);
            q.set(canvas, ax + dx, G + PILLAR_HEIGHT + 1, az + dz, ToyKit.GLOW);
            q.place(canvas, ax + dx, G + PILLAR_HEIGHT + 2, az + dz,
                    ToyKit.bevel(ToyKit.YELLOW_STAIRS, dx - 0.5, dz - 0.5, false));
        }
    }

    /**
     * A flower box: a white box with a bevelled quartz rim and a round mint topiary growing out of it, pink blossoms
     * on it.
     */
    private static void planter(MapCanvas canvas, Quarter q, int ax, int az) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                boolean rim = dx != 0 || dz != 0;
                q.set(canvas, ax + dx, G + 1, az + dz, rim ? ToyKit.WHITE : ToyKit.MAT);
                if (rim) {
                    q.place(canvas, ax + dx, G + 2, az + dz, dx != 0 && dz != 0 ? ToyKit.WHITE_SLAB
                            : ToyKit.bevel(ToyKit.WHITE_STAIRS, -dx, -dz, false));
                }
            }
        }
        q.set(canvas, ax, G + 2, az, ToyKit.MAT);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 || dz == 0) {
                    q.set(canvas, ax + dx, G + 3, az + dz, ToyKit.MAT);
                    if (dx != 0 || dz != 0) {
                        q.place(canvas, ax + dx, G + 4, az + dz, ToyKit.BLOSSOM);
                    }
                }
            }
        }
        q.set(canvas, ax, G + 4, az, ToyKit.MAT);
        q.place(canvas, ax, G + 5, az, ToyKit.BLOSSOM);
    }

    /**
     * A wooden toy soldier on guard against the wall beside the front gate, facing out: boots and a coat in the side's
     * strong colour, white trousers, a yellow buckle, button and epaulettes, birch hands and face, a tall busby with a
     * yellow plume and a rifle with a shining bayonet held upright in front of the outer hand.
     */
    private void soldier(MapCanvas canvas, Quarter q, int ax, int az) {
        BlockSpec coat = ToyKit.strong(side);
        q.set(canvas, ax, G + 1, az - 1, coat);
        q.set(canvas, ax, G + 1, az + 1, coat);
        for (int dz = -1; dz <= 1; dz++) {
            q.set(canvas, ax, G + 2, az + dz, ToyKit.WHITE);
            q.set(canvas, ax, G + 3, az + dz, dz == 0 ? ToyKit.YELLOW : coat);
            q.set(canvas, ax, G + 4, az + dz, coat);
            q.set(canvas, ax, G + 5, az + dz, coat);
        }
        for (int s : new int[] {-1, 1}) {
            q.set(canvas, ax, G + 3, az + 2 * s, ToyKit.WOOD);
            q.set(canvas, ax, G + 4, az + 2 * s, coat);
            q.set(canvas, ax, G + 5, az + 2 * s, ToyKit.YELLOW);
        }
        q.set(canvas, ax, G + 6, az, ToyKit.WOOD);
        q.set(canvas, ax, G + 7, az, coat);
        q.set(canvas, ax, G + 8, az, coat);
        q.place(canvas, ax, G + 9, az, BlockSpec.of(Material.YELLOW_CANDLE));
        q.place(canvas, ax - 1, G + 4, az, BlockSpec.of(Material.BAMBOO_BUTTON).facing(BlockFace.WEST));
        for (int y = G + 2; y <= G + 5; y++) {
            q.set(canvas, ax - 1, y, az + 2, ToyKit.FENCE);
        }
        q.place(canvas, ax - 1, G + 6, az + 2, ToyKit.STICK);
    }

    /**
     * A music box in a back corner of the courtyard: a box in the team colour with a white rim, its lid standing open
     * along the back, a little ballerina (a glowing leg, a pink tutu, a birch head) on a white turntable and a crank on
     * its side.
     */
    private void musicBox(MapCanvas canvas, Quarter q, int ax, int az) {
        for (int dx = 0; dx < 4; dx++) {
            for (int dz = 0; dz < 3; dz++) {
                boolean edge = dx == 0 || dx == 3 || dz == 0 || dz == 2;
                q.set(canvas, ax + dx, G + 1, az + dz, ToyKit.strong(side));
                q.set(canvas, ax + dx, G + 2, az + dz, edge ? ToyKit.WHITE : ToyKit.pastel(side));
            }
            q.place(canvas, ax + dx, G + 3, az + 2, BlockSpec.of(Material.BIRCH_TRAPDOOR).facing(BlockFace.NORTH)
                    .open(true).half(Bisected.Half.BOTTOM));
        }
        q.place(canvas, ax + 2, G + 3, az + 1, BlockSpec.of(Material.WHITE_CARPET));
        q.place(canvas, ax + 1, G + 3, az + 1, ToyKit.STICK);
        q.set(canvas, ax + 1, G + 4, az + 1, ToyKit.PINK);
        q.set(canvas, ax + 1, G + 5, az + 1, ToyKit.WOOD);
        q.place(canvas, ax - 1, G + 2, az + 1, BlockSpec.of(Material.LEVER).facing(BlockFace.WEST));
    }

    /**
     * The shop stall against the side wall on the team's left: a white counter facing the courtyard, the shopkeeper's
     * spot behind it, a striped awning held by two white posts against the wall (a valance along its front, two lamps
     * hanging under it), wares on the counter, a sign in the team colour on the wall and a big round coin on top.
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
        // A valance under the awning's front edge and two lamps hanging inside.
        BlockSpec valance = BlockSpec.of(ToyKit.trapdoorOf(ToyKit.strong(side))).facing(BlockFace.SOUTH).open(true)
                .half(Bisected.Half.TOP);
        for (int ax = ToyLayout.SHOP_AX1; ax <= ToyLayout.SHOP_AX2; ax++) {
            q.place(canvas, ax, G + 3, ToyLayout.SHOP_Z1, valance);
        }
        q.place(canvas, ToyLayout.SHOP_AX1 + 1, G + 3, ToyLayout.SHOP_AZ, ToyKit.LANTERN_HANGING);
        q.place(canvas, ToyLayout.SHOP_AX2 - 1, G + 3, ToyLayout.SHOP_AZ, ToyKit.LANTERN_HANGING);
        // Wares on the counter: a cake, a candle in the team colour, a potted tulip.
        q.place(canvas, ToyLayout.SHOP_AX1 + 1, G + 2, ToyLayout.SHOP_Z1, BlockSpec.of(Material.CAKE));
        q.place(canvas, ToyLayout.SHOP_AX1 + 2, G + 2, ToyLayout.SHOP_Z1,
                BlockSpec.of(side == Side.BLUE ? Material.BLUE_CANDLE : Material.RED_CANDLE).lit(true));
        q.place(canvas, ToyLayout.SHOP_AX2 - 1, G + 2, ToyLayout.SHOP_Z1, BlockSpec.of(Material.POTTED_PINK_TULIP));
        // A big gold coin on the awning, its edge rounded, a white shine in the middle.
        ToyKit.plane(canvas, q, true, ToyLayout.SHOP_AX - 1, ToyLayout.SHOP_AZ, G + 5,
                new String[] {"YYY", "YWY", "YYY"}, true, Map.of('Y', ToyKit.YELLOW, 'W', ToyKit.WHITE));
    }
}
