package io.github.sx2xoox.speedrunbot.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads a buried-treasure map the way a player does: the map image shows the player's own marker and the red X;
 * the X's offset from the player marker, times the map's blocks per marker unit, is where to walk. The bot also uses
 * its own coordinates (F3, which every player can open) to dig at the chunk offset where buried treasure sits
 * (block 9, 9 inside the chunk in 1.16). Nothing here reads the world or the structure locator.
 *
 * <p>Marker coordinates are what the map screen draws: bytes from -128 to 127, two units per map pixel, map centre
 * = 0. A map of scale s spans 128 << s blocks, so one marker unit is (1 << s) / 2 blocks. A player outside the map is
 * drawn as a small marker pinned to the map's edge (only the direction is known then).
 */
public final class TreasureMap {
    /** One marker as the map screen shows it. */
    public static final class Marker {
        public final String type;
        public final int x;
        public final int z;

        public Marker(String type, int x, int z) {
            this.type = type;
            this.x = x;
            this.z = z;
        }

        @Override
        public String toString() {
            return type + "(" + x + "," + z + ")";
        }
    }

    /** What a held map shows: its scale and its markers. */
    public static final class Reading {
        public final int scale;
        public final List<Marker> markers = new ArrayList<>();

        public Reading(int scale) {
            this.scale = scale;
        }

        public Reading add(String type, int x, int z) {
            markers.add(new Marker(type, x, z));
            return this;
        }

        Marker find(String... types) {
            for (Marker m : markers) {
                for (String t : types) {
                    if (m.type.equals(t)) {
                        return m;
                    }
                }
            }
            return null;
        }

        @Override
        public String toString() {
            return "scale " + scale + " " + markers;
        }
    }

    /** Where to go: an estimated block position (exact = the player is on the map), or only a direction. */
    public static final class Estimate {
        public final double x;
        public final double z;
        public final boolean exact;

        Estimate(double x, double z, boolean exact) {
            this.x = x;
            this.z = z;
            this.exact = exact;
        }

        /** The block column the treasure chest is buried in: offset 9, 9 of the chunk holding the estimate. */
        public int[] digColumn() {
            int cx = (int) Math.floor(x / 16.0), cz = (int) Math.floor(z / 16.0);
            return new int[]{cx * 16 + 9, cz * 16 + 9};
        }

        @Override
        public String toString() {
            return (exact ? "at " : "toward ") + Math.round(x) + "," + Math.round(z);
        }
    }

    private TreasureMap() {
    }

    public static double blocksPerUnit(int scale) {
        return (1 << scale) / 2.0;
    }

    /**
     * The treasure's position from the bot's own position (F3) and the map reading, or null when the map shows no X
     * or no player marker. When the player marker is pinned to the edge, the estimate is a point 64 blocks along the
     * direction to the X (walk there and read the map again).
     */
    public static Estimate locate(Reading r, double playerX, double playerZ) {
        Marker x = r.find("RED_X", "TARGET_X");
        if (x == null) {
            return null;
        }
        Marker p = r.find("PLAYER");
        double k = blocksPerUnit(r.scale);
        if (p != null) {
            return new Estimate(playerX + (x.x - p.x) * k, playerZ + (x.z - p.z) * k, true);
        }
        Marker off = r.find("PLAYER_OFF_MAP", "PLAYER_OFF_LIMITS");
        if (off == null) {
            return null;
        }
        double dx = x.x - off.x, dz = x.z - off.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-6) {
            return null;
        }
        return new Estimate(playerX + dx / len * 64, playerZ + dz / len * 64, false);
    }
}
