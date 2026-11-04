package io.github.sx2xoox.mcpl.core.map.toykingdom;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.jspecify.annotations.Nullable;

/**
 * The plan of the clockwork hall around 장난감 왕국 (docs/siege-v2.md §6): the kingdom is a toy left on the floor of a
 * huge round clock tower hall. Around the toy brick wall rise three tiers of arcades (a plinth, three galleries with
 * balconies and arched windows, a walkway on top), giant cream pillars with crimson and gold bands, and over it all a
 * purple glass vault that runs into a flat ceiling holding a giant golden clock face among meshing gears.
 *
 * <p>Everything is measured from the outline of the walkable area: {@code d = ToyLayout.outline(x, z)} goes outwards
 * (the toy brick wall is {@code 0 < d <= 2.5}) and {@code h = y - GROUND_Y} goes up. Along the wall a column lies in a
 * bay, on a pillar or in one of the four special bays ({@link #spot}): the gate over each lane drop (long sides,
 * {@code x = 0}) and the clock bay behind each castle (short sides, {@code z = 0}). The hall is mirror symmetric across
 * both axes (only the clock's numerals and hands' colours are not).
 *
 * <pre>
 *   h 80  ───────────── flat ceiling: the clock, gears below it ──────────────
 *          ╲ vault (purple glass, gold ribs over the pillars)
 *   h 48  ═╪═ walkway (rail + lanterns)   │
 *   h 36  ═╪═ gallery 3                   │ back wall with arched windows
 *   h 24  ═╪═ gallery 2                   │ (d 9.5..11.5)
 *   h 12  ═╪═ gallery 1                   │
 *   h  0  ▓┃▓ plinth (crimson blind arches, gold gears)
 *         d 0 .. 2.5 toy brick wall, pillars d 0.5..5.5
 * </pre>
 */
public final class ToyHall {

    private ToyHall() {
    }

    static final int G = ToyLayout.GROUND_Y;

    // ------------------------------------------------------------------ section (d outwards, h up)

    /** The pillars stand in front of everything ({@code PILLAR_FRONT < d <= PILLAR_BACK}). */
    static final double PILLAR_FRONT = 0.5;
    static final double PILLAR_BACK = 5.5;
    /** The crimson nose of each deck, the rail on it and the banners under it ({@code NOSE < d <= NOSE + 1}). */
    static final double NOSE = 1.5;
    /** The plinth's face ({@code FACE < d <= FACE_BACK}), right behind the toy brick wall. */
    static final double FACE = ToyLayout.HEDGE_WIDTH;
    static final double FACE_BACK = 4.5;
    /** The back wall of the galleries ({@code BACK < d <= OUTER}): crimson inside, cream outside. */
    static final double BACK = 9.5;
    public static final double OUTER = 11.5;
    /** The decks: the plinth's top and the floors of the galleries; the last is the walkway under the vault. */
    public static final int[] DECKS = {12, 24, 36, 48};
    public static final int WALL_TOP = 48;
    /** The flat ceiling (the clock) and where the vault becomes flat ({@code d <= FLAT}). */
    public static final int TOP = 80;
    static final double FLAT = -10.5;

    // ------------------------------------------------------------------ along the wall

    /** Arc length of a quarter of the outline (from the middle of a long side to the middle of a short side). */
    static final double QUARTER = (ToyLayout.HALF_X - ToyLayout.CORNER) + ToyLayout.CORNER * Math.PI / 2
            + (ToyLayout.HALF_Z - ToyLayout.CORNER);
    /** Half width of the pillars (5 wide) and of the special bays (the gate: |x| <= 14, the clock bay: |z| <= 14). */
    static final double PILLAR_HALF = 2.5;
    static final double SPECIAL_HALF = 14.5;
    /**
     * The pillars of a quarter along the outline: every 14 blocks on the straight parts (so the bays there are centred
     * on a column), the three corner bays share the rest.
     */
    static final double[] PILLARS = pillars();

    private static double[] pillars() {
        double straightLong = ToyLayout.HALF_X - ToyLayout.CORNER;
        double[] out = new double[9];
        for (int i = 0; i < 5; i++) {
            out[i] = 17 + 14 * i;
        }
        double lastCorner = QUARTER - 31;
        double step = (lastCorner - out[4]) / 3;
        out[5] = out[4] + step;
        out[6] = out[4] + 2 * step;
        out[7] = lastCorner;
        out[8] = QUARTER - 17;
        assert out[4] <= straightLong;
        return out;
    }

    /** What a column of the wall belongs to. */
    enum Kind { GATE, CLOCK_BAY, PILLAR, BAY }

    /**
     * Where a column lies along the wall: its kind, the index of its pillar or bay in the quarter, and the offset from
     * the pillar's or bay's centre in blocks along the wall (for the special bays: from the axis, never negative).
     */
    record Spot(Kind kind, int index, double offset) {
    }

    /** The spot of a column (any column; columns inside the outline take the nearest edge). */
    static Spot spot(int x, int z) {
        return spot(x, z, true);
    }

    /** The spot of a column, its bay even on a pillar when {@code pillars} is false. */
    static Spot spot(int x, int z, boolean pillars) {
        double s = along(x, z);
        double k = stretch(x, z);
        if (s < SPECIAL_HALF) {
            return new Spot(Kind.GATE, 0, s * k);
        }
        if (s > QUARTER - SPECIAL_HALF) {
            return new Spot(Kind.CLOCK_BAY, 0, (QUARTER - s) * k);
        }
        for (int i = 0; i < PILLARS.length && pillars; i++) {
            double off = (s - PILLARS[i]) * k;
            if (Math.abs(off) <= PILLAR_HALF) {
                return new Spot(Kind.PILLAR, i, off);
            }
        }
        int bay = 0;
        while (bay < PILLARS.length - 2 && s > PILLARS[bay + 1]) {
            bay++;
        }
        double centre = (PILLARS[bay] + PILLARS[bay + 1]) / 2;
        return new Spot(Kind.BAY, bay, (s - centre) * k);
    }

    /**
     * Arc length along the outline of the point nearest to a column, in a quarter ({@code |x|, |z|}): 0 in the middle of
     * a long side ({@code x = 0}), {@link #QUARTER} in the middle of a short side ({@code z = 0}).
     */
    static double along(int x, int z) {
        double cx = ToyLayout.HALF_X - ToyLayout.CORNER;
        double cz = ToyLayout.HALF_Z - ToyLayout.CORNER;
        double qx = Math.abs(x) - cx;
        double qz = Math.abs(z) - cz;
        double arc = ToyLayout.CORNER * Math.PI / 2;
        if (qx > 0 && qz > 0) {
            return cx + ToyLayout.CORNER * Math.atan2(qx, qz);
        }
        boolean longSide = qx <= 0 && (qz > 0 || qz >= qx);
        return longSide ? Math.abs(x) : cx + arc + (cz - Math.abs(z));
    }

    /** Blocks along the wall per unit of {@link #along} at a column (more than 1 out in the corners, less inside). */
    static double stretch(int x, int z) {
        double qx = Math.abs(x) - (ToyLayout.HALF_X - ToyLayout.CORNER);
        double qz = Math.abs(z) - (ToyLayout.HALF_Z - ToyLayout.CORNER);
        if (qx > 0 && qz > 0) {
            return Math.max(0.2, (ToyLayout.CORNER + ToyLayout.outline(x, z)) / ToyLayout.CORNER);
        }
        return 1;
    }

    // ------------------------------------------------------------------ the vault

    /**
     * The height (above the ground) of the vault's surface over a column: it springs from the top of the back wall and
     * curves in (a quarter ellipse, steep at first) to the flat ceiling at {@link #TOP}; {@code -1} beyond the hall.
     */
    public static int vaultHeight(int x, int z) {
        double d = ToyLayout.outline(x, z);
        if (d > OUTER) {
            return -1;
        }
        if (d <= FLAT) {
            return TOP;
        }
        double u = 1 - (OUTER - d) / (OUTER - FLAT);
        return WALL_TOP + (int) Math.round((TOP - WALL_TOP) * Math.sqrt(1 - u * u));
    }

    /** The vault's half width and height (a quarter ellipse from the spring to the flat ceiling). */
    private static final double VAULT_W = OUTER - FLAT;
    private static final double VAULT_H = TOP - WALL_TOP;
    /** Arc length along the vault from the spring by its angle ({@code MERIDIAN.length - 1} steps to 90 degrees). */
    private static final double[] MERIDIAN = meridians(2000);

    private static double[] meridians(int steps) {
        double[] out = new double[steps + 1];
        for (int i = 1; i <= steps; i++) {
            double a = Math.PI / 2 * (i - 0.5) / steps;
            out[i] = out[i - 1] + Math.hypot(VAULT_W * Math.sin(a), VAULT_H * Math.cos(a)) * Math.PI / 2 / steps;
        }
        return out;
    }

    /**
     * How far up the vault (along its curve, in blocks from the spring) a block of its shell lies: by the angle of the
     * place in the vault's ellipse, so steep and flat parts are measured alike.
     */
    static double meridian(double d, int h) {
        double u = 1 - (OUTER - d) / VAULT_W;
        double v = (h - WALL_TOP) / VAULT_H;
        double angle = Math.max(0, Math.min(Math.PI / 2, Math.atan2(v, u)));
        return MERIDIAN[(int) Math.round(angle / (Math.PI / 2) * (MERIDIAN.length - 1))];
    }

    /** {@link #meridian} of the vault's surface over a column at {@code d} (where the vault crosses it). */
    static double meridianAt(double d) {
        double u = Math.max(0, Math.min(1, 1 - (OUTER - d) / VAULT_W));
        return MERIDIAN[(int) Math.round(Math.acos(u) / (Math.PI / 2) * (MERIDIAN.length - 1))];
    }

    /** Nothing of the kingdom reaches this high; the hall's ceiling and gears are all above it. */
    public static final int KINGDOM_TOP = 50;

    /**
     * True for the hall's part of the map: everything over the toy brick wall (higher than 3) and beyond it above the
     * ground, the back wall's foundations beyond the floorboards, and the ceiling over the kingdom.
     */
    public static boolean isHall(int x, int y, int z) {
        double d = ToyLayout.outline(x, z);
        int h = y - G;
        if (h <= 0) {
            return d > ToyLayout.HEDGE_WIDTH + ToyLayout.APRON_WIDTH;
        }
        return h >= KINGDOM_TOP || d > FACE || d > 0 && h > 3;
    }

    /** True for every place inside the hall: under the vault or the ceiling and inside the back wall. */
    public static boolean underRoof(int x, int y, int z) {
        int v = vaultHeight(x, z);
        return v >= 0 && y - G < v;
    }

    // ------------------------------------------------------------------ blocks

    /** Cream walls, decks and the outside. */
    static final BlockSpec CREAM = BlockSpec.of(Material.SMOOTH_SANDSTONE);
    /** The giant pillars. */
    static final BlockSpec COLUMN = BlockSpec.of(Material.QUARTZ_PILLAR).axis(Axis.Y);
    /** Crimson: bands, deck noses, the galleries' back walls, blind arches, the clock's numeral ring and hands. */
    static final BlockSpec WINE = BlockSpec.of(Material.RED_NETHER_BRICKS);
    static final BlockSpec RAIL = BlockSpec.of(Material.RED_NETHER_BRICK_WALL);
    static final BlockSpec GOLD = BlockSpec.of(Material.GOLD_BLOCK);
    static final BlockSpec DARK_GOLD = BlockSpec.of(Material.RAW_GOLD_BLOCK);
    /** The golden light blocks (gear hubs, keystones, pillar lamps, the clock's five minute marks). */
    static final BlockSpec LIGHT = BlockSpec.of(Material.OCHRE_FROGLIGHT).axis(Axis.Y);
    static final BlockSpec LANTERN = BlockSpec.of(Material.LANTERN);
    static final BlockSpec HANGING = BlockSpec.of(Material.LANTERN).hanging(true);
    static final BlockSpec CHAIN = BlockSpec.of(Material.IRON_CHAIN).axis(Axis.Y);
    /** The banners' cloth. */
    static final BlockSpec CLOTH = BlockSpec.of(Material.RED_WOOL);
    /** The vault and the ceiling around the clock: the sky shows through, violet. */
    static final BlockSpec VAULT = BlockSpec.of(Material.PURPLE_STAINED_GLASS);
    /** The clock face (amber glass: the daylight comes through it golden). */
    static final BlockSpec DIAL = BlockSpec.of(Material.YELLOW_STAINED_GLASS);
    static final BlockSpec DIAL_HEART = BlockSpec.of(Material.ORANGE_STAINED_GLASS);

    // ------------------------------------------------------------------ ornaments

    /**
     * A gear in its own plane ({@code a} across, {@code b} along its 0° direction, from its centre): teeth around a
     * rim at {@code pitch} (about four blocks a tooth, one centred at 0°, so the gear is mirror symmetric), an inner
     * ring on large gears, {@code spokes} spokes (one at 0°) and a hub with a light in it. Null between the spokes.
     */
    static @Nullable BlockSpec gear(double a, double b, double pitch, int spokes) {
        double r = Math.hypot(a, b);
        if (r > pitch + 2.5) {
            return null;
        }
        double angle = Math.atan2(a, b);
        if (r > pitch + 0.5) {
            int teeth = 2 * (int) Math.round(Math.PI * pitch / 4);
            double f = angle / (2 * Math.PI) * teeth + 0.25;
            return f - Math.floor(f) < 0.5 ? GOLD : null;
        }
        if (r > pitch - (pitch >= 9 ? 2 : 1.2)) {
            return GOLD;
        }
        double hub = pitch >= 9 ? Math.max(2.5, pitch * 0.24) : 1.2;
        if (r <= 1.2) {
            return LIGHT;
        }
        if (r <= hub) {
            return DARK_GOLD;
        }
        if (pitch >= 9 && Math.abs(r - (pitch * 0.58)) <= 0.6) {
            return DARK_GOLD;
        }
        double sector = 2 * Math.PI / spokes;
        double rel = angle - sector * Math.round(angle / sector);
        return r * Math.abs(Math.sin(rel)) <= 0.9 ? DARK_GOLD : null;
    }

    /**
     * A ship's wheel in its own plane: a gold rim of {@code radius}, eight spokes (one straight up) running out past
     * the rim into handles, a hub with a light in it. Null elsewhere.
     */
    static @Nullable BlockSpec wheel(double a, double b, double radius) {
        double r = Math.hypot(a, b);
        if (r > radius + 3) {
            return null;
        }
        if (r <= 1.2) {
            return LIGHT;
        }
        if (r <= 2.6) {
            return DARK_GOLD;
        }
        if (r >= radius - 1.2 && r <= radius) {
            return GOLD;
        }
        double angle = Math.atan2(a, b);
        double sector = Math.PI / 4;
        double rel = angle - sector * Math.round(angle / sector);
        double across = r * Math.abs(Math.sin(rel));
        if (r <= radius) {
            return across <= 0.6 ? GOLD : null;
        }
        return across <= (r > radius + 1.5 ? 1.1 : 0.6) ? DARK_GOLD : null;
    }
}
