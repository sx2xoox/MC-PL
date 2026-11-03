package io.github.sx2xoox.mcpl.core.map.toykingdom;

import static io.github.sx2xoox.mcpl.core.map.toykingdom.ToyKit.G;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.MapZone;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites.Lane;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites.Side;
import io.github.sx2xoox.mcpl.core.map.toykingdom.ToyKit.Quarter;

/**
 * A side's toy castle (BLUE: light blue walls and blue roofs, RED: pink walls and red roofs): a walled courtyard with
 * wooden boards, the core on a white plate in the middle, the spawn mat behind it and the shop counter in the back
 * corner. Gates open to both lanes (north and south walls) and to the jungle (front wall); a round turret with a cone
 * roof stands on every corner.
 */
final class ToyCastleZone implements MapZone {

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
            for (int ax : new int[] {ToyLayout.CASTLE_FRONT + 1, ToyLayout.CASTLE_BACK - 1}) {
                turret(canvas, q, ax, ToyLayout.CASTLE_HALF_Z - 1);
            }
        }
        shop(canvas, new Quarter(sx, 1));
        // The castle's back wall stands against the kingdom's hedge: the strip between them is hedge too.
        for (int ax = ToyLayout.CASTLE_BACK + 1; ax <= r; ax++) {
            for (int z = -ToyLayout.CASTLE_ZONE_HALF_Z; z <= ToyLayout.CASTLE_ZONE_HALF_Z; z++) {
                if (contains(sx * ax, z)) {
                    for (int y = G + 1; y <= G + 3; y++) {
                        canvas.set(sx * ax, y, z, ToyKit.HEDGE);
                    }
                }
            }
        }
    }

    /** The walls of one half (north or south) of the castle: two blocks thick, a white band and merlons on top. */
    private void walls(MapCanvas canvas, Quarter q) {
        BlockSpec body = ToyKit.pastel(side);
        int front = ToyLayout.CASTLE_FRONT;
        int back = ToyLayout.CASTLE_BACK;
        int half = ToyLayout.CASTLE_HALF_Z;
        int top = G + ToyLayout.WALL_HEIGHT;
        for (int ax = front; ax <= back; ax++) {
            for (int az = 0; az <= half; az++) {
                boolean side = az >= half - 1;
                boolean end = ax <= front + 1 || ax >= back - 1;
                if (!side && !end) {
                    continue;
                }
                boolean laneGate = side && Math.abs(ax - ToyLayout.GATE_AX) <= ToyLayout.GATE_HALF;
                boolean frontGate = !side && ax <= front + 1 && az <= ToyLayout.GATE_HALF;
                boolean gate = laneGate || frontGate;
                for (int y = G + 1; y <= top; y++) {
                    if (gate && y < top) {
                        continue;
                    }
                    q.set(canvas, ax, y, az, y == top ? ToyKit.WHITE : body);
                }
                // Merlons on the outer row of the wall, every other block.
                boolean outer = side ? az == half : ax == front || ax == back;
                int along = side ? ax : az;
                if (outer && along % 2 == 0) {
                    q.set(canvas, ax, top + 1, az, ToyKit.WHITE);
                }
            }
        }
    }

    /** A round turret on a corner: a pastel tower with a white band and a cone roof in the side's strong colour. */
    private void turret(MapCanvas canvas, Quarter q, int cax, int caz) {
        double radius = 3.2;
        int top = G + ToyLayout.WALL_HEIGHT + 3;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                double d = Math.hypot(dx, dz);
                if (d > radius) {
                    continue;
                }
                for (int y = G + 1; y <= top; y++) {
                    q.set(canvas, cax + dx, y, caz + dz, y == top ? ToyKit.WHITE : ToyKit.pastel(side));
                }
                // The cone: one ring narrower per layer.
                int layers = (int) Math.floor(radius + 0.8 - d) + 1;
                for (int i = 1; i <= layers; i++) {
                    q.set(canvas, cax + dx, top + i, caz + dz, ToyKit.strong(side));
                }
            }
        }
    }

    /** The shop: a white counter in the back corner under a striped awning, the shopkeeper's spot in front. */
    private void shop(MapCanvas canvas, Quarter q) {
        int counter = ToyLayout.SHOP_AX + 3;
        int z1 = ToyLayout.SHOP_Z - 2;
        int z2 = ToyLayout.SHOP_Z + 2;
        for (int az = z1; az <= z2; az++) {
            canvas.set(q.x(counter), G + 1, az, ToyKit.WHITE);
            for (int ax = counter - 1; ax <= ToyLayout.CASTLE_BACK - 2; ax++) {
                canvas.set(q.x(ax), G + 4, az, Math.floorMod(az, 2) == 0 ? ToyKit.WHITE : ToyKit.strong(side));
            }
        }
    }
}
