package io.github.sx2xoox.mcpl.core.map.toykingdom;

import static io.github.sx2xoox.mcpl.core.map.toykingdom.ToyKit.G;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.MapZone;
import org.bukkit.Material;

/**
 * The glass dome over the whole kingdom, like a snow globe: clear glass with a few tinted arches across it (pink,
 * light blue, yellow), and on its flat top, right over the dragon, a giant clock face (white glass, a yellow rim,
 * glowing hour marks and two hands showing ten past ten: the hour hand blue towards BLUE, the minute hand red towards
 * RED). Glass lets the daylight through, so the kingdom stays bright; the dome also keeps everyone in.
 *
 * <p>The shell follows {@link ToyLayout#inDomeShell}; it is built last over every column (it only writes its own
 * blocks, high above the other zones or on the floorboards beyond the toy wall).
 */
final class ToyDomeZone implements MapZone {

    static final BlockSpec GLASS = BlockSpec.of(Material.GLASS);
    static final BlockSpec FACE = BlockSpec.of(Material.WHITE_STAINED_GLASS);
    /** The tinted arches across the dome at these {@code |x|} (two blocks wide), and their glass. */
    private static final int[] ARCH_AX = {24, 48, 72};
    private static final BlockSpec[] ARCH_GLASS = {BlockSpec.of(Material.PINK_STAINED_GLASS),
        BlockSpec.of(Material.LIGHT_BLUE_STAINED_GLASS), BlockSpec.of(Material.YELLOW_STAINED_GLASS)};
    /** The clock hands' length, and the hour marks' distance from the middle. */
    private static final double HAND = ToyLayout.CLOCK_RADIUS - 7;
    private static final double MARKS = ToyLayout.CLOCK_RADIUS - 3;

    @Override
    public String name() {
        return "dome";
    }

    @Override
    public boolean contains(int x, int z) {
        return true;
    }

    @Override
    public void build(MapCanvas canvas) {
        int r = ToyLayout.BUILD_RADIUS;
        int top = G + ToyLayout.DOME_HEIGHT;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                if (ToyLayout.outline(x, z) > ToyLayout.DOME_FOOT) {
                    continue;
                }
                for (int y = G + 1; y <= top; y++) {
                    if (ToyLayout.inDomeShell(x, y, z)) {
                        canvas.set(x, y, z, block(x, y, z));
                    }
                }
            }
        }
    }

    /** The block of the shell at a place: the clock on the top, a tinted arch or clear glass. */
    static BlockSpec block(int x, int y, int z) {
        double d = Math.hypot(x, z);
        if (d <= ToyLayout.CLOCK_RADIUS && y >= G + ToyLayout.DOME_HEIGHT - 4) {
            return clock(x, z, d);
        }
        int ax = Math.abs(x);
        for (int i = 0; i < ARCH_AX.length; i++) {
            if (ax == ARCH_AX[i] || ax == ARCH_AX[i] + 1) {
                return ARCH_GLASS[i];
            }
        }
        return GLASS;
    }

    private static BlockSpec clock(int x, int z, double d) {
        if (d > ToyLayout.CLOCK_RADIUS - 1) {
            return ToyKit.YELLOW;
        }
        if (d <= 2.5) {
            return ToyKit.YELLOW;
        }
        // Twelve hour marks (twelve o'clock towards -z), the quarters larger.
        for (int hour = 0; hour < 12; hour++) {
            double angle = Math.PI * 2 * hour / 12;
            double mx = Math.sin(angle) * MARKS;
            double mz = -Math.cos(angle) * MARKS;
            if (Math.hypot(x - mx, z - mz) <= (hour % 3 == 0 ? 1.8 : 1.1)) {
                return ToyKit.GLOW;
            }
        }
        // Ten past ten: the hands point to ten (towards -x) and to two (towards +x), mirror images of each other.
        double hx = Math.sin(Math.PI * 2 * 10 / 12) * HAND;
        double hz = -Math.cos(Math.PI * 2 * 10 / 12) * HAND;
        if (ToyKit.segment(x, z, 0, 0, hx, hz) <= 1) {
            return ToyKit.BLUE;
        }
        if (ToyKit.segment(x, z, 0, 0, -hx, hz) <= 1) {
            return ToyKit.RED;
        }
        return FACE;
    }
}
