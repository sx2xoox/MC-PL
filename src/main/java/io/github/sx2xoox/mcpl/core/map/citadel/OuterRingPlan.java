package io.github.sx2xoox.mcpl.core.map.citadel;

import java.util.ArrayList;
import java.util.List;

/**
 * Where everything of the outer ring stands: the radii and heights of the curtain wall, the six gatehouses, the twelve
 * towers, the four breaches, the two posterns and the stair flights up to the wall-walk. Angles are degrees as in
 * {@link CitadelLayout} (0 east, 90 south).
 *
 * <p>Features are shaped in local coordinates around their angle: {@code u} runs outwards along the radial line at
 * that angle, {@code v} across it (positive towards larger angles, clockwise seen from above).
 *
 * <pre>
 *   r  96..98.5   walkable strip inside the wall (stair flights up to the wall-walk)
 *   r 98.5..102.5 the curtain wall: wall-walk on top (y 73, players at 74), parapet + merlons on the outer ring
 *   r 102.5..106  flat apron outside the wall (the arena border is at 105)
 *   r 106..121    hills rising to about +8..+12, crags, trees, boulders, wrecked trebuchets and camps
 *   r 121..124    a jagged rock rim (three to seven blocks above the hills) that keeps players on the island
 * </pre>
 */
final class OuterRingPlan {

    /** Top block of the untouched ground. */
    static final int GROUND = CitadelLayout.GROUND_Y;
    /** Top block of the wall-walk (players stand at 74, ten blocks above the ground). */
    static final int WALK = GROUND + 9;
    /** Floor of the rooftops of tall towers (players stand at 79). */
    static final int ROOF = WALK + 5;

    static final double WALL_IN = 98.5;
    static final double PARAPET_IN = 101.5;
    static final double WALL_OUT = 102.5;
    /** Radius of the tower centers: towers jut out of the wall towards the hills. */
    static final double TOWER_RADIUS = 104;

    // Gatehouse shape (local coordinates of the road's angle).
    static final double GATE_HALF = 3.5;
    static final double GATE_BODY_HALF = 6;
    static final double GATE_BODY_OUT = 106;
    static final double GATE_FLANK_U = 105;
    static final double GATE_FLANK_V = 7.5;
    static final double GATE_FLANK_SIZE = 4;

    enum TowerKind {
        /** Round bastion: open platform level with the wall-walk. */
        BASTION,
        /** Round tower with a room at wall-walk level and a rooftop. */
        TALL,
        /** Square tower (axis-aligned) with a room at wall-walk level and a rooftop. */
        SQUARE,
        /** Round stump, fallen outwards, filled with rubble. */
        COLLAPSED
    }

    /** A tower centered on the wall line at {@code angle}. */
    record Tower(double angle, TowerKind kind, int cx, int cz) {

        static final double SIZE = 4.5;

        static Tower at(double angle, TowerKind kind) {
            int[] c = CitadelLayout.polar(TOWER_RADIUS, angle);
            return new Tower(angle, kind, c[0], c[1]);
        }

        boolean round() {
            return kind != TowerKind.SQUARE;
        }

        /** Distance from the tower's center (Chebyshev for square towers). */
        double dist(int x, int z) {
            int dx = x - cx;
            int dz = z - cz;
            return round() ? Math.sqrt(dx * dx + dz * dz) : Math.max(Math.abs(dx), Math.abs(dz));
        }

        boolean footprint(int x, int z) {
            return dist(x, z) < SIZE;
        }

        boolean interior(int x, int z) {
            return dist(x, z) < SIZE - 1;
        }

        boolean ring(int x, int z) {
            return footprint(x, z) && !interior(x, z);
        }
    }

    /** A gatehouse where a road crosses the wall; {@code stairSide} is the side (+1/-1) of its stair flight. */
    record Gate(double angle, int stairSide) {

        /** Center of the flanking tower on {@code side} (+1/-1). */
        int[] flank(int side) {
            double a = angle + side * Math.toDegrees(Math.atan2(GATE_FLANK_V, GATE_FLANK_U));
            return CitadelLayout.polar(Math.hypot(GATE_FLANK_U, GATE_FLANK_V), a);
        }

        double flankDist(int x, int z, int side) {
            int[] c = flank(side);
            return Math.hypot(x - c[0], z - c[1]);
        }

        /** The masonry block over the gate passage. */
        boolean body(int x, int z) {
            double u = u(x, z, angle);
            return u >= WALL_IN && u < GATE_BODY_OUT && Math.abs(v(x, z, angle)) < GATE_BODY_HALF;
        }

        boolean flankFootprint(int x, int z) {
            return flankDist(x, z, 1) < GATE_FLANK_SIZE || flankDist(x, z, -1) < GATE_FLANK_SIZE;
        }

        /** Columns the gatehouse builds (curtain included). */
        boolean footprint(int x, int z) {
            return body(x, z) || flankFootprint(x, z);
        }

        /** The open passage through the gatehouse (and the road beyond it). */
        boolean passage(int x, int z) {
            double u = u(x, z, angle);
            return u > CitadelLayout.DISTRICT_OUTER && u < GATE_BODY_OUT + 3 && Math.abs(v(x, z, angle)) < GATE_HALF;
        }
    }

    /** A breach: the wall has fallen over {@code halfWidth} blocks each side; {@code low} keeps a knee-high stub. */
    record Breach(double angle, double halfWidth, boolean low) {
    }

    /**
     * A straight stair flight on the inner strip, along the wall: its top step (level with the wall-walk) is at
     * {@code vTop} across the radial line at {@code angle}, and it descends towards {@code dir} (+1/-1).
     */
    record Flight(double angle, double vTop, int dir) {

        static final int STEPS = WALK - GROUND;

        /** Height of the step's top block in this column, or -1 outside the flight. */
        int stepTop(int x, int z) {
            double r = CitadelLayout.radius(x, z);
            if (r <= CitadelLayout.DISTRICT_OUTER || r >= WALL_IN || u(x, z, angle) <= 0) {
                return -1;
            }
            double d = (v(x, z, angle) - vTop) * dir;
            if (d < 0 || d >= STEPS) {
                return -1;
            }
            return WALK - (int) Math.floor(d);
        }
    }

    /** A small gate (3 wide, 4 high) through the curtain. */
    record Postern(double angle) {

        boolean contains(int x, int z) {
            return u(x, z, angle) > 0 && Math.abs(v(x, z, angle)) < 1.6;
        }
    }

    final List<Gate> gates = new ArrayList<>();
    final List<Tower> towers = new ArrayList<>();
    final List<Breach> breaches = new ArrayList<>();
    final List<Flight> flights = new ArrayList<>();
    final List<Postern> posterns = new ArrayList<>();

    OuterRingPlan() {
        int[] roads = CitadelLayout.ROAD_ANGLES;
        for (int i = 0; i < roads.length; i++) {
            int side = i % 2 == 0 ? 1 : -1;
            gates.add(new Gate(roads[i], side));
            // The flight climbs along the inner face up to the gatehouse roof, beside the passage.
            flights.add(new Flight(roads[i], side * 4.6, side));
        }
        towers.add(Tower.at(15, TowerKind.TALL));
        towers.add(Tower.at(45, TowerKind.COLLAPSED));
        towers.add(Tower.at(77, TowerKind.SQUARE));
        towers.add(Tower.at(103, TowerKind.SQUARE));
        towers.add(Tower.at(136, TowerKind.COLLAPSED));
        towers.add(Tower.at(164, TowerKind.TALL));
        towers.add(Tower.at(196, TowerKind.BASTION));
        towers.add(Tower.at(224, TowerKind.TALL));
        towers.add(Tower.at(257, TowerKind.SQUARE));
        towers.add(Tower.at(283, TowerKind.SQUARE));
        towers.add(Tower.at(316, TowerKind.BASTION));
        towers.add(Tower.at(344, TowerKind.COLLAPSED));
        breaches.add(new Breach(30, 8, false));
        breaches.add(new Breach(150, 9, false));
        breaches.add(new Breach(210, 10, true));
        breaches.add(new Breach(330, 8, false));
        posterns.add(new Postern(90));
        posterns.add(new Postern(270));
        flights.add(new Flight(90, 2.6, 1));
        flights.add(new Flight(270, -2.6, -1));
    }

    // ------------------------------------------------------------------ geometry

    /** Distance along the radial line at {@code angle} (outwards). */
    static double u(int x, int z, double angle) {
        double a = Math.toRadians(angle);
        return x * Math.cos(a) + z * Math.sin(a);
    }

    /** Signed distance across the radial line at {@code angle} (positive towards larger angles). */
    static double v(int x, int z, double angle) {
        double a = Math.toRadians(angle);
        return -x * Math.sin(a) + z * Math.cos(a);
    }

    static boolean inWallBand(int x, int z) {
        double r = CitadelLayout.radius(x, z);
        return r >= WALL_IN && r < WALL_OUT;
    }

    static boolean inParapet(int x, int z) {
        double r = CitadelLayout.radius(x, z);
        return r >= PARAPET_IN && r < WALL_OUT;
    }

    // ------------------------------------------------------------------ lookups

    Tower towerAt(int x, int z) {
        for (Tower tower : towers) {
            if (tower.footprint(x, z)) {
                return tower;
            }
        }
        return null;
    }

    Gate gateAt(int x, int z) {
        for (Gate gate : gates) {
            if (gate.footprint(x, z)) {
                return gate;
            }
        }
        return null;
    }

    boolean inPassage(int x, int z) {
        for (Gate gate : gates) {
            if (gate.passage(x, z)) {
                return true;
            }
        }
        return false;
    }

    boolean inPostern(int x, int z) {
        if (!inWallBand(x, z)) {
            return false;
        }
        for (Postern postern : posterns) {
            if (postern.contains(x, z)) {
                return true;
            }
        }
        return false;
    }

    /** Top of the step of a stair flight in this column, or -1. */
    int flightTop(int x, int z) {
        for (Flight flight : flights) {
            int top = flight.stepTop(x, z);
            if (top >= 0) {
                return top;
            }
        }
        return -1;
    }

    /**
     * Height of the wall-walk's top block in a wall-band column: {@link #WALK}, lower where a breach broke the wall
     * (the broken ends step down one block per block, so they can be climbed), {@code <= GROUND} where nothing is left.
     */
    int wallTop(int x, int z) {
        int top = WALK;
        for (Breach breach : breaches) {
            if (u(x, z, breach.angle()) <= 0) {
                continue;
            }
            double across = Math.abs(v(x, z, breach.angle()));
            if (across >= breach.halfWidth() + 12) {
                continue;
            }
            int t = GROUND + (int) Math.floor(across - (breach.halfWidth() - 2));
            if (breach.low()) {
                t = Math.max(t, GROUND + 2);
            }
            top = Math.min(top, t);
        }
        return top;
    }

    /** True for a column of the zone where a structure stands on the ground (the terrain stays flat under it). */
    boolean structure(int x, int z) {
        if (towerAt(x, z) != null || gateAt(x, z) != null) {
            return true;
        }
        return inWallBand(x, z) && wallTop(x, z) >= WALK;
    }

    /** The breach whose rubble reaches this column, or null. */
    Breach breachNear(int x, int z, double margin) {
        for (Breach breach : breaches) {
            if (u(x, z, breach.angle()) > 0 && Math.abs(v(x, z, breach.angle())) < breach.halfWidth() + margin) {
                return breach;
            }
        }
        return null;
    }

    /** True near a gate's road or a breach, outside the wall: keep these open as paths into the hills. */
    boolean onPath(int x, int z, double margin) {
        for (Gate gate : gates) {
            if (u(x, z, gate.angle()) > 0 && Math.abs(v(x, z, gate.angle())) < GATE_HALF + margin) {
                return true;
            }
        }
        return breachNear(x, z, margin - 4) != null;
    }
}
