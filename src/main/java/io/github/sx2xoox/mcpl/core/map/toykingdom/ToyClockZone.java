package io.github.sx2xoox.mcpl.core.map.toykingdom;

import static io.github.sx2xoox.mcpl.core.map.toykingdom.ToyHall.CHAIN;
import static io.github.sx2xoox.mcpl.core.map.toykingdom.ToyHall.DARK_GOLD;
import static io.github.sx2xoox.mcpl.core.map.toykingdom.ToyHall.DIAL;
import static io.github.sx2xoox.mcpl.core.map.toykingdom.ToyHall.DIAL_HEART;
import static io.github.sx2xoox.mcpl.core.map.toykingdom.ToyHall.G;
import static io.github.sx2xoox.mcpl.core.map.toykingdom.ToyHall.GOLD;
import static io.github.sx2xoox.mcpl.core.map.toykingdom.ToyHall.HANGING;
import static io.github.sx2xoox.mcpl.core.map.toykingdom.ToyHall.LIGHT;
import static io.github.sx2xoox.mcpl.core.map.toykingdom.ToyHall.TOP;
import static io.github.sx2xoox.mcpl.core.map.toykingdom.ToyHall.VAULT;
import static io.github.sx2xoox.mcpl.core.map.toykingdom.ToyHall.WINE;

import java.util.List;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.MapZone;
import org.jspecify.annotations.Nullable;

/**
 * The ceiling of the clockwork hall (see {@link ToyHall}): a flat ceiling of purple glass at {@code h = 80} with, right
 * over the dragon, a giant golden clock face (amber glass, a gold mainspring spiral in the middle, gold hour lines,
 * Roman numerals in gold on a crimson ring, a minute track with a light every five minutes and a toothed gold rim) and
 * its two hands hanging under it at ten past ten (seen from below; the hour hand's tip red over RED, the minute hand's
 * blue over BLUE, mirror images of each other). Around the clock hang gold gears in three layers, meshing with its rim
 * and with each other, on golden axles, the larger ones with a lantern on a chain.
 *
 * <p>The clock reads correctly from below: twelve is towards the TOP lane (−z) and three towards BLUE (−x).
 */
final class ToyClockZone implements MapZone {

    /** The clock face: its toothed rim's pitch, the numeral ring (inner, outer) and the numerals' middle. */
    static final double DIAL_PITCH = 47.5;
    static final double DIAL_RADIUS = DIAL_PITCH + 2;
    private static final double RING_IN = 30.5;
    private static final double RING_OUT = 40.5;
    private static final double NUMERALS = 35.5;
    /** Pixels of the numerals' font in blocks. */
    private static final double FONT = 1.7;
    /** The hands: length from the middle, and the clock time they show (hours, minutes) as clock angles. */
    private static final double HAND = 37.5;
    private static final double HOUR_ANGLE = Math.toRadians(300 + 5);
    private static final double MINUTE_ANGLE = Math.toRadians(360 - 305);

    /** A gear under the ceiling in quarter coordinates (mirrored to all four quarters), its layer and chain. */
    record Gear(int x, int z, double pitch, int spokes, int layer, int chain) {

        /** The height of the gear's plane (above the ground): layer 0 right under the ceiling, each layer 3 lower. */
        int h() {
            return TOP - 1 - 3 * layer;
        }

        /** Its full radius (to the tips of the teeth). */
        double radius() {
            return pitch + 2.5;
        }
    }

    /** The gears, meshing with the clock's rim (A, C, F, H) and with each other (B on A, D on C, E in the corner). */
    static final List<Gear> GEARS = List.of(
            new Gear(66, 0, 16, 6, 0, 0),
            new Gear(82, 23, 9, 6, 2, 6),
            new Gear(50, 35, 11, 6, 1, 7),
            new Gear(69, 30, 6, 4, 0, 0),
            new Gear(85, 46, 7, 4, 2, 4),
            new Gear(14, 53, 5, 4, 2, 0),
            new Gear(33, 47, 8, 4, 2, 8),
            new Gear(64, 51, 6, 4, 2, 0),
            new Gear(48, 56, 4, 4, 3, 0));

    @Override
    public String name() {
        return "clock";
    }

    @Override
    public boolean contains(int x, int z) {
        return true;
    }

    @Override
    public void build(MapCanvas canvas) {
        int r = canvas.radius();
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                double d = ToyLayout.outline(x, z);
                if (d > ToyHall.FLAT) {
                    continue;
                }
                canvas.set(x, G + TOP, z, ceiling(x, z, d));
                BlockSpec hand = hands(x, z);
                if (hand != null) {
                    canvas.set(x, G + TOP - 1, z, hand);
                }
            }
        }
        for (Gear gear : GEARS) {
            for (int sx = -1; sx <= 1; sx += 2) {
                for (int sz = -1; sz <= 1; sz += 2) {
                    if (sz == 1 && gear.z() == 0) {
                        continue;
                    }
                    gear(canvas, gear, sx, sz);
                }
            }
        }
    }

    // ------------------------------------------------------------------ the ceiling and the clock face

    /** The gold rays of the ceiling around the clock (every 10 degrees) and its gold rings (every 8 blocks). */
    private static final double RAYS = Math.toRadians(10);
    private static final double RING_FIRST = 57;
    private static final double RING_STEP = 8;

    /**
     * The flat ceiling: the clock face, a gold band along its edge and around the clock purple glass with gold rays and
     * rings like a spider's web (a light where they cross).
     */
    private static BlockSpec ceiling(int x, int z, double d) {
        double r = Math.hypot(x, z);
        if (r <= DIAL_RADIUS) {
            return dial(x, z, r);
        }
        if (d > ToyHall.FLAT - 1) {
            return GOLD;
        }
        double phi = clockAngle(x, z);
        boolean ray = r * Math.abs(phi - RAYS * Math.round(phi / RAYS)) <= 0.5;
        double k = (r - RING_FIRST) / RING_STEP;
        boolean ring = k > -0.5 && Math.abs(r - RING_FIRST - RING_STEP * Math.round(k)) <= 0.5;
        if (ray && ring) {
            return LIGHT;
        }
        return ray || ring ? GOLD : VAULT;
    }

    /** The clock angle of a column seen from below: 0 at twelve (−z), clockwise (twelve, then three at −x). */
    static double clockAngle(double x, double z) {
        double a = Math.atan2(-x, -z);
        return a < 0 ? a + 2 * Math.PI : a;
    }

    private static BlockSpec dial(int x, int z, double r) {
        double phi = clockAngle(x, z);
        if (r > DIAL_PITCH) {
            int teeth = 2 * (int) Math.round(Math.PI * DIAL_PITCH / 4);
            double f = phi / (2 * Math.PI) * teeth + 0.25;
            return f - Math.floor(f) < 0.5 ? GOLD : VAULT;
        }
        if (r > DIAL_PITCH - 2) {
            return GOLD;
        }
        if (r > RING_OUT + 1) {
            double m = phi / (2 * Math.PI) * 60;
            long tick = Math.round(m);
            double off = Math.abs(m - tick) * 2 * Math.PI * r / 60;
            if (tick % 5 == 0 && off <= 0.8) {
                return LIGHT;
            }
            return off <= 0.5 && r > RING_OUT + 3 ? GOLD : DIAL;
        }
        if (r > RING_OUT) {
            return GOLD;
        }
        if (r > RING_IN) {
            return numerals(r, phi) ? GOLD : VAULT;
        }
        if (r > RING_IN - 1) {
            return GOLD;
        }
        if (r > 14.5) {
            // A sunburst on two ambers: dark gold hour lines, thin gold rays at the half hours and (further out) the
            // quarters, a thin gold ring across. The lines are a block wide, so the daylight still comes through.
            double hour = phi / (2 * Math.PI) * 12;
            if (line(hour, r, 1)) {
                return DARK_GOLD;
            }
            if (Math.abs(r - 22) <= 0.5 || line(hour, r, 2) || r > 22 && line(hour, r, 4)) {
                return GOLD;
            }
            return (int) Math.floor(hour) % 2 == 0 ? DIAL : DIAL_HEART;
        }
        if (r > 13.5) {
            return GOLD;
        }
        if (r > 3) {
            // The mainspring: a gold spiral winding out four times.
            double turn = (r - 3.5) / 2.5 - phi / (2 * Math.PI);
            return turn - Math.floor(turn) < 0.42 ? GOLD : DIAL_HEART;
        }
        return r <= 1.5 ? LIGHT : DARK_GOLD;
    }

    /** True on a radial line at every {@code 1 / per} hour (a block wide at radius {@code r}). */
    private static boolean line(double hour, double r, int per) {
        double f = hour * per;
        return Math.abs(f - Math.round(f)) * 2 * Math.PI * r / (12 * per) <= 0.5;
    }

    // ------------------------------------------------------------------ numerals

    private static final String[] ROMAN = {"XII", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI"};

    /** A letter of the font, five rows from the top. */
    private static String[] glyph(char c) {
        return switch (c) {
            case 'I' -> new String[] {"#", "#", "#", "#", "#"};
            case 'V' -> new String[] {"#.#", "#.#", "#.#", "#.#", ".#."};
            default -> new String[] {"#.#", "#.#", ".#.", "#.#", "#.#"};
        };
    }

    /**
     * True on the strokes of the numerals: each stands on the ring at its hour, its top towards the rim, reading
     * clockwise (as seen from below).
     */
    private static boolean numerals(double r, double phi) {
        int hour = (int) Math.round(phi / (Math.PI / 6)) % 12;
        double rel = phi - hour * Math.PI / 6;
        if (rel > Math.PI) {
            rel -= 2 * Math.PI;
        }
        double across = r * rel;
        double up = r - NUMERALS;
        String text = ROMAN[hour];
        int width = text.length() - 1;
        for (char c : text.toCharArray()) {
            width += glyph(c)[0].length();
        }
        int col = (int) Math.floor(across / FONT + width / 2.0);
        int row = (int) Math.floor((2.5 * FONT - up) / FONT);
        if (row < 0 || row >= 5 || col < 0 || col >= width) {
            return false;
        }
        for (char c : text.toCharArray()) {
            String[] g = glyph(c);
            int w = g[0].length();
            if (col < w) {
                return g[row].charAt(col) == '#';
            }
            col -= w + 1;
            if (col < 0) {
                return false;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ hands

    /**
     * The hands under the clock face: a gold hub, a counterweight, a crimson shaft edged in gold, a gold ring near the
     * end and a team coloured tip.
     */
    private static @Nullable BlockSpec hands(int x, int z) {
        double r = Math.hypot(x, z);
        if (r <= 1.5) {
            return LIGHT;
        }
        if (r <= 3.5) {
            return GOLD;
        }
        if (r > HAND + 0.5) {
            return null;
        }
        BlockSpec hour = hand(x, z, HOUR_ANGLE, ToyKit.RED);
        return hour != null ? hour : hand(x, z, MINUTE_ANGLE, ToyKit.BLUE);
    }

    private static @Nullable BlockSpec hand(int x, int z, double angle, BlockSpec tip) {
        double dx = -Math.sin(angle);
        double dz = -Math.cos(angle);
        double along = x * dx + z * dz;
        double across = Math.abs(x * dz - z * dx);
        double weight = Math.hypot(along + 7, across);
        if (weight <= 3) {
            return weight <= 1.5 ? WINE : GOLD;
        }
        double ring = Math.hypot(along - (HAND - 13), across);
        if (ring <= 4.2) {
            return ring >= 3 ? GOLD : null;
        }
        if (along >= -4.5 && along <= HAND - 7.5 && across <= 1.7) {
            return across <= 0.9 ? WINE : GOLD;
        }
        if (along > HAND - 7.5 && along <= HAND && across <= (HAND - along) * 0.5 + 0.5) {
            return tip;
        }
        return null;
    }

    // ------------------------------------------------------------------ gears

    /** One gear in one quarter: its disc, the axle up to the ceiling and its chandelier. */
    private static void gear(MapCanvas canvas, Gear gear, int sx, int sz) {
        int h = gear.h();
        int reach = (int) Math.ceil(gear.radius());
        for (int a = -reach; a <= reach; a++) {
            for (int b = -reach; b <= reach; b++) {
                BlockSpec spec = ToyHall.gear(a, b, gear.pitch(), gear.spokes());
                if (spec == null) {
                    continue;
                }
                int x = sx * (gear.x() + a);
                int z = sz * (gear.z() + b);
                if (ToyHall.vaultHeight(x, z) > h + 1) {
                    canvas.set(x, G + h, z, spec);
                }
            }
        }
        int cx = sx * gear.x();
        int cz = sz * gear.z();
        for (int y = h + 1; y < TOP; y++) {
            if (canvas.get(cx, G + y, cz) == null) {
                canvas.set(cx, G + y, cz, DARK_GOLD);
            }
        }
        for (int i = 1; i <= gear.chain(); i++) {
            canvas.set(cx, G + h - i, cz, CHAIN);
        }
        if (gear.chain() > 0) {
            canvas.set(cx, G + h - gear.chain() - 1, cz, HANGING);
        }
    }
}
