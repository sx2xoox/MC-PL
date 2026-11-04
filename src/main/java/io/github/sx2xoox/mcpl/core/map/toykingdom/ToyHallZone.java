package io.github.sx2xoox.mcpl.core.map.toykingdom;

import static io.github.sx2xoox.mcpl.core.map.toykingdom.ToyHall.CLOTH;
import static io.github.sx2xoox.mcpl.core.map.toykingdom.ToyHall.COLUMN;
import static io.github.sx2xoox.mcpl.core.map.toykingdom.ToyHall.CREAM;
import static io.github.sx2xoox.mcpl.core.map.toykingdom.ToyHall.DARK_GOLD;
import static io.github.sx2xoox.mcpl.core.map.toykingdom.ToyHall.DECKS;
import static io.github.sx2xoox.mcpl.core.map.toykingdom.ToyHall.G;
import static io.github.sx2xoox.mcpl.core.map.toykingdom.ToyHall.GOLD;
import static io.github.sx2xoox.mcpl.core.map.toykingdom.ToyHall.HANGING;
import static io.github.sx2xoox.mcpl.core.map.toykingdom.ToyHall.LANTERN;
import static io.github.sx2xoox.mcpl.core.map.toykingdom.ToyHall.LIGHT;
import static io.github.sx2xoox.mcpl.core.map.toykingdom.ToyHall.RAIL;
import static io.github.sx2xoox.mcpl.core.map.toykingdom.ToyHall.VAULT;
import static io.github.sx2xoox.mcpl.core.map.toykingdom.ToyHall.WALL_TOP;
import static io.github.sx2xoox.mcpl.core.map.toykingdom.ToyHall.WINE;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.MapZone;
import io.github.sx2xoox.mcpl.core.map.toykingdom.ToyHall.Kind;
import io.github.sx2xoox.mcpl.core.map.toykingdom.ToyHall.Spot;
import org.jspecify.annotations.Nullable;

/**
 * The walls and the vault of the clockwork hall (see {@link ToyHall}): the plinth behind the toy brick wall (crimson
 * blind arches with a gold gear or ship's wheel in every bay), three galleries and a walkway with crimson rails crowned
 * by lanterns and lanterns hanging under every deck, arched windows in the crimson back wall, giant quartz pillars with
 * crimson and gold bands and a lamp on top, red banners with a gold gear, the gate arch over each lane drop with a
 * giant ship's wheel above it, a giant gear in the clock bay behind each castle, and the purple glass vault with gold
 * ribs over the pillars. Built after the kingdom, over every column; it never writes the kingdom's columns below the
 * ceiling.
 */
final class ToyHallZone implements MapZone {

    /** The gate arch over a lane drop: open below a half circle of this radius on this height, |x| <= 12. */
    private static final double GATE_RADIUS = 12.5;
    private static final int GATE_SPRING = 9;
    /** The ship's wheel above the gate and the gear of the clock bay: radius and centre height. */
    private static final double GATE_WHEEL = 8;
    private static final int GATE_WHEEL_H = 36;
    private static final double CLOCK_GEAR = 10.5;
    private static final int CLOCK_GEAR_H = 30;
    /** The plinth's blind arches (straight up to this height, then round) and their medallions' centre height. */
    private static final int PANEL_SPRING = 7;
    private static final int MEDAL_H = 6;

    @Override
    public String name() {
        return "hall";
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
                if (d > ToyHall.OUTER || d <= ToyHall.FLAT) {
                    continue;
                }
                Spot spot = ToyHall.spot(x, z);
                if (d > ToyHall.PILLAR_FRONT) {
                    walls(canvas, x, z, d, spot);
                }
                vault(canvas, x, z, d, spot);
            }
        }
    }

    // ------------------------------------------------------------------ walls

    private static void walls(MapCanvas canvas, int x, int z, double d, Spot spot) {
        for (int h = -3; h <= WALL_TOP + 2; h++) {
            BlockSpec spec = wall(x, z, d, h, spot);
            if (spec == null) {
                continue;
            }
            if (d <= ToyHall.FACE && h <= 3) {
                continue;
            }
            canvas.set(x, G + h, z, spec);
        }
    }

    /** The block of the walls at a place, null for air. */
    private static @Nullable BlockSpec wall(int x, int z, double d, int h, Spot spot) {
        boolean back = d > ToyHall.BACK;
        if (spot.kind() == Kind.GATE) {
            BlockSpec gate = gate(spot.offset(), h);
            if (gate != null || gateOpen(spot.offset(), h)) {
                return gate;
            }
        }
        if (h <= 0) {
            // Foundations: the floorboards reach the plinth; only the back wall goes down to their bottom.
            return back && h >= -3 ? CREAM : null;
        }
        if (h == 4 && d <= ToyLayout.HEDGE_WIDTH - 1 && spot.kind() == Kind.BAY
                && Math.abs(Math.abs(spot.offset()) - 4) < 0.5) {
            // Lanterns on the toy brick wall, a pair in every bay: the seam between the kingdom and the hall glows.
            return LANTERN;
        }
        if (spot.kind() == Kind.PILLAR && d <= ToyHall.PILLAR_BACK) {
            return pillar(spot.offset(), d, h);
        }
        boolean nose = d > ToyHall.NOSE && d <= ToyHall.NOSE + 1;
        if (h > WALL_TOP) {
            return nose && !back ? front(x, z, h, spot) : null;
        }
        if (back) {
            return backWall(spot, d, h);
        }
        boolean special = spot.kind() == Kind.GATE || spot.kind() == Kind.CLOCK_BAY;
        if (special && d > ToyHall.NOSE && d <= ToyHall.NOSE + 2 && h > DECKS[0] && h < WALL_TOP) {
            return spot.kind() == Kind.GATE ? gateWall(spot.offset(), h) : clockWall(spot.offset(), h);
        }
        if (deck(h)) {
            return d <= ToyHall.NOSE ? null : nose ? WINE : CREAM;
        }
        if (nose && !special) {
            BlockSpec front = front(x, z, h, spot);
            if (front != null) {
                return front;
            }
        }
        if (h < DECKS[0] && d > ToyHall.FACE && d <= ToyHall.FACE_BACK) {
            return plinth(spot, h);
        }
        return null;
    }

    private static boolean deck(int h) {
        for (int deck : DECKS) {
            if (h == deck) {
                return true;
            }
        }
        return false;
    }

    /** The front row of a gallery (the deck's nose plane): the rail, its lanterns, hanging lanterns and banners. */
    private static @Nullable BlockSpec front(int x, int z, int h, Spot spot) {
        boolean even = (Math.abs(x) + Math.abs(z)) % 2 == 0;
        if (spot.kind() == Kind.BAY) {
            BlockSpec banner = banner(spot, h);
            if (banner != null) {
                return banner;
            }
            if (bannerColumn(spot, h)) {
                return null;
            }
        }
        for (int deck : DECKS) {
            if (h == deck + 1) {
                return RAIL;
            }
            if (h == deck + 2) {
                return even ? LANTERN : null;
            }
            if (h == deck - 1 && h > 3 && spot.kind() != Kind.PILLAR) {
                // Lanterns hang under the deck's nose every four blocks, a crimson corbel halfway between them.
                int beat = (Math.abs(x) + Math.abs(z)) % 4;
                return beat == 0 ? HANGING : beat == 2 ? corbel(x, z) : null;
            }
        }
        return null;
    }

    /** An upside down crimson stair under a deck's nose, its tall side towards the hall's wall. */
    private static BlockSpec corbel(int x, int z) {
        return ToyKit.stairs(ToyHall.CORBEL, ToyBorderZone.outward(x, z), true);
    }

    // ------------------------------------------------------------------ banners

    /**
     * The banners' emblems, 5 x 5 from the top row (G = gold, . = cloth), in turn along the wall: a sun-like gear, a
     * clock at three o'clock and a wind-up key.
     */
    private static final String[][] BANNER_EMBLEMS = {
        {"G.G.G", ".GGG.", "GG.GG", ".GGG.", "G.G.G"},
        {".GGG.", "G.G.G", "G.GGG", "G...G", ".GGG."},
        {"GG.GG", "G.G.G", "GG.GG", "..G..", "..GG."},
    };
    private static final int BANNER_ROWS = 9;

    /** True for the columns and tier of a bay's banner (gallery 1 in even bays, gallery 2 in odd bays). */
    private static boolean bannerColumn(Spot spot, int h) {
        if (Math.abs(spot.offset()) > 2.5) {
            return false;
        }
        int top = DECKS[1 + spot.index() % 2] - 1;
        return h <= top && h > top - BANNER_ROWS;
    }

    /** The block of a bay's banner at a height: a gold rod, red cloth with a gold gear, a notch at the bottom. */
    private static @Nullable BlockSpec banner(Spot spot, int h) {
        if (!bannerColumn(spot, h)) {
            return null;
        }
        int top = DECKS[1 + spot.index() % 2] - 1;
        int row = top - h;
        int col = (int) Math.round(spot.offset()) + 2;
        if (row == 0) {
            return GOLD;
        }
        // The tail: a notch, a swallowtail or a point, in turn.
        int cut = spot.index() / 2 % 3;
        if (row == BANNER_ROWS - 1) {
            boolean open = cut == 0 ? col == 2 : cut == 1 ? Math.abs(col - 2) <= 1 : col == 0 || col == 4;
            return open ? null : CLOTH;
        }
        if (row == BANNER_ROWS - 2 && cut == 1 && col == 2) {
            return null;
        }
        if (row == BANNER_ROWS - 2) {
            // A gold fringe band above the tail.
            return col % 2 == 0 ? GOLD : CLOTH;
        }
        String[] emblem = BANNER_EMBLEMS[spot.index() % BANNER_EMBLEMS.length];
        if (row >= 2 && row < 2 + emblem.length && col >= 0 && col < 5) {
            return emblem[row - 2].charAt(col) == 'G' ? GOLD : CLOTH;
        }
        return CLOTH;
    }

    // ------------------------------------------------------------------ pillars

    /**
     * A giant pillar: quartz with a crimson-gold-crimson band at every deck, a crimson foot, a gold capital and a lamp
     * on top of its front.
     */
    private static @Nullable BlockSpec pillar(double offset, double d, int h) {
        boolean front = d <= ToyHall.PILLAR_FRONT + 1 && Math.abs(offset) <= 0.5;
        if (h > WALL_TOP) {
            return h == WALL_TOP + 1 && front ? LIGHT : null;
        }
        if (h >= WALL_TOP - 2) {
            return front && h == WALL_TOP - 1 ? LIGHT : GOLD;
        }
        for (int i = 0; i < DECKS.length; i++) {
            int deck = DECKS[i];
            if (h == deck) {
                return GOLD;
            }
            if (Math.abs(h - deck) == 1) {
                return WINE;
            }
            // A crimson inset on the front of each storey with a gold boss in it (a lamp on the middle storey).
            if (d <= ToyHall.PILLAR_FRONT + 1 && h > deck && h - deck >= 3 && h - deck <= 9
                    && Math.abs(offset) <= 1.2) {
                return Math.abs(offset) <= 0.5 && h - deck == 6 ? (i == 1 ? LIGHT : GOLD) : WINE;
            }
        }
        return h <= 2 ? WINE : COLUMN;
    }

    // ------------------------------------------------------------------ the plinth

    /**
     * The plinth's face in a bay: cream, with a crimson blind arch (gold rim) holding a gold gear (even bays) or ship's
     * wheel (odd bays); the clock bays have a gear, the gates have none (their arch is real).
     */
    private static BlockSpec plinth(Spot spot, int h) {
        double off = spot.offset();
        double panel = h <= PANEL_SPRING ? Math.abs(off) : Math.hypot(off, h - PANEL_SPRING);
        if (h < 2 || panel > 4.5) {
            // A crimson course just over the toy brick wall and a gold one under the deck, between the arches.
            return h == 4 ? WINE : h == 10 && panel > 5.5 ? GOLD : CREAM;
        }
        if (panel > 3.5) {
            return GOLD;
        }
        boolean wheel = spot.kind() == Kind.BAY && spot.index() % 2 == 1;
        double a = off;
        double b = h - MEDAL_H;
        double r = Math.hypot(a, b);
        if (r <= 0.5) {
            return LIGHT;
        }
        double angle = Math.atan2(a, b);
        double eighth = Math.PI / 4;
        double across = r * Math.abs(Math.sin(angle - eighth * Math.round(angle / eighth)));
        if (wheel) {
            // A small ship's wheel: a rim and eight spokes running out into handles.
            if (r >= 1.9 && r <= 2.7) {
                return GOLD;
            }
            return across <= 0.5 && r <= 3.6 ? (r > 2.7 ? DARK_GOLD : GOLD) : WINE;
        }
        // A small gear: a rim with eight teeth and four spokes.
        if (r >= 1.6 && r <= 2.7) {
            return GOLD;
        }
        if (r > 2.7) {
            return r <= 3.7 && across <= 0.6 ? GOLD : WINE;
        }
        double quarter = Math.PI / 2;
        return r * Math.abs(Math.sin(angle - quarter * Math.round(angle / quarter))) <= 0.5 ? DARK_GOLD : WINE;
    }

    // ------------------------------------------------------------------ the back wall

    /**
     * The back wall of the galleries: crimson inside, cream outside with crimson bands at the decks and quartz pilasters
     * behind the pillars; in every bay an arched window per gallery (gold surround, a light for its keystone).
     */
    private static @Nullable BlockSpec backWall(Spot spot, double d, int h) {
        boolean inside = d <= ToyHall.BACK + 1;
        if (spot.kind() == Kind.BAY) {
            for (int g = 0; g < 3; g++) {
                int sill = DECKS[g] + 2;
                double dist = window(spot.offset(), h - sill);
                if (dist <= 0) {
                    // A gold mullion and transom set one block back from the inner face: four panes with depth.
                    boolean mullion = Math.abs(spot.offset()) <= 0.5 || h - sill == 5;
                    return mullion && !inside ? GOLD : null;
                }
                if (dist <= 1 && inside) {
                    boolean key = Math.abs(spot.offset()) < 0.5 && h > sill + 5;
                    return key ? LIGHT : GOLD;
                }
            }
        }
        if (deck(h)) {
            return inside ? CREAM : WINE;
        }
        if (!inside && spot.kind() == Kind.PILLAR && Math.abs(spot.offset()) <= 1.5 && h > 0) {
            return h >= WALL_TOP - 1 ? GOLD : COLUMN;
        }
        return inside ? WINE : CREAM;
    }

    /**
     * How far a place of the back wall is outside a window ({@code <= 0} inside it): 7 wide, straight for 5 blocks
     * above the sill, then a half circle.
     */
    private static double window(double offset, int above) {
        if (above < 0) {
            return -above;
        }
        double half = 3.5;
        if (above <= 5) {
            return Math.abs(offset) - half + 0.5;
        }
        return Math.hypot(offset, above - 5) - half + 0.5;
    }

    // ------------------------------------------------------------------ the gates over the lane drops

    private static boolean gateOpen(double offset, int h) {
        if (offset > ToyLayout.CLIFF_HALF_X) {
            return false;
        }
        return h < GATE_SPRING || Math.hypot(offset, h - GATE_SPRING) < GATE_RADIUS;
    }

    /**
     * The gate arch: nothing in the opening (the drop goes on into the void, the sky beyond), crimson jambs and a
     * crimson and gold arch around it with a light for the keystone; null elsewhere.
     */
    private static @Nullable BlockSpec gate(double offset, int h) {
        if (gateOpen(offset, h)) {
            return null;
        }
        double ring = h < GATE_SPRING ? offset - GATE_RADIUS : Math.hypot(offset, h - GATE_SPRING) - GATE_RADIUS;
        if (ring > 2 || h < 1) {
            return null;
        }
        if (h >= GATE_SPRING + GATE_RADIUS && offset < 1) {
            return LIGHT;
        }
        double angle = Math.atan2(offset, h - GATE_SPRING);
        return h < GATE_SPRING || (int) Math.floor(angle / (Math.PI / 12) + 0.5) % 2 == 0 ? WINE : GOLD;
    }

    /** The wall over a gate: cream, a crimson disc ringed with gold and on it a giant gold ship's wheel. */
    private static BlockSpec gateWall(double offset, int h) {
        double a = offset;
        double b = h - GATE_WHEEL_H;
        BlockSpec wheel = ToyHall.wheel(a, b, GATE_WHEEL);
        if (wheel != null) {
            return wheel;
        }
        double r = Math.hypot(a, b);
        if (r <= GATE_WHEEL + 1.5) {
            return WINE;
        }
        if (r <= GATE_WHEEL + 3.5) {
            return h % 2 == 0 && r > GATE_WHEEL + 2.5 ? DARK_GOLD : WINE;
        }
        return CREAM;
    }

    /** The wall of a clock bay (behind a castle): cream, a crimson disc ringed with gold and a giant gold gear on it. */
    private static BlockSpec clockWall(double offset, int h) {
        double a = offset;
        double b = h - CLOCK_GEAR_H;
        BlockSpec gear = ToyHall.gear(a, b, CLOCK_GEAR, 6);
        if (gear != null) {
            return gear;
        }
        double r = Math.hypot(a, b);
        if (r <= CLOCK_GEAR + 2.5) {
            return WINE;
        }
        if (r <= CLOCK_GEAR + 3.5 && h > DECKS[0] + 1) {
            return GOLD;
        }
        return CREAM;
    }

    // ------------------------------------------------------------------ the vault

    /** Where on the vault (meridian, from the spring) the bays' gears sit, and where the glass begins. */
    private static final double VAULT_GEAR = 13;
    private static final double VAULT_ROSE = 25.5;

    /**
     * One column of the vault: from its surface down to where its outer neighbours' surface is (so the shell is closed
     * where it is steep). Gold ribs over the pillars (a light every few blocks). Over the galleries (outside the
     * kingdom) it is a dark crimson coffer with a gold cornice, a gold gear in every bay (a giant one over the gates and
     * the clock bays), a gold ring and a light rosette; over the kingdom it is purple glass with thin gold rings, so the
     * daylight comes in.
     */
    private static void vault(MapCanvas canvas, int x, int z, double d, Spot spot) {
        int top = ToyHall.vaultHeight(x, z);
        int bottom = top;
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] s : sides) {
            int n = ToyHall.vaultHeight(x + s[0], z + s[1]);
            bottom = Math.min(bottom, (n < 0 ? WALL_TOP : n) + 1);
        }
        Spot bay = ToyHall.spot(x, z, false);
        for (int h = Math.max(bottom, WALL_TOP + 1); h <= top; h++) {
            canvas.set(x, G + h, z, vaultBlock(d, h, spot, bay));
        }
    }

    private static BlockSpec vaultBlock(double d, int h, Spot spot, Spot bay) {
        double m = ToyHall.meridian(d, h);
        double rim = ToyHall.meridianAt(0);
        // The ribs narrow to one block over the kingdom (the daylight).
        double ribHalf = m > rim ? 0.5 : 1.5;
        if (spot.kind() == Kind.PILLAR && Math.abs(spot.offset()) <= ribHalf) {
            return Math.abs(spot.offset()) <= 0.5 && Math.round(m) % 6 == 3 ? LIGHT : GOLD;
        }
        if (m > rim) {
            // Over the kingdom: glass with thin gold rings.
            return Math.round(m - rim) % 4 == 0 ? GOLD : VAULT;
        }
        if (m < 2 || m > rim - 1) {
            return GOLD;
        }
        boolean big = bay.kind() != Kind.BAY;
        double off = bay.offset();
        // The bays' gears differ in turn (pitch and spokes), so the coffers do not repeat one stamp.
        int turn = bay.index() % 3;
        double pitch = big ? 9.5 : turn == 1 ? 4.8 : turn == 2 ? 3.4 : 4;
        int spokes = big ? 8 : turn == 1 ? 6 : turn == 2 ? 3 : 4;
        BlockSpec gear = ToyHall.gear(off, m - (big ? VAULT_GEAR + 2 : VAULT_GEAR), pitch, spokes);
        if (gear != null) {
            return gear;
        }
        double rose = Math.hypot(off, m - VAULT_ROSE);
        if (!big && rose <= 0.6) {
            return LIGHT;
        }
        if (!big && rose <= 1.8) {
            return GOLD;
        }
        if (Math.abs(m - (VAULT_ROSE - 3.5)) <= 0.5) {
            return DARK_GOLD;
        }
        return WINE;
    }
}
