package io.github.sx2xoox.mcpl.game.augment.bot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * Plans a bot's base from terrain samples, without Bukkit: where (high, flat, dry ground, towards the final zone, away
 * from other bases) and what (walls with a doorway, a lookout pillar or a roof, a chest and a crafting table, cobweb
 * traps), in the order a player would build it. {@link BotController} places the blocks a few per second and only
 * where the world has air.
 */
public final class BasePlanner {

    /** What the ground is at a column. */
    public enum Surface {
        SOLID, WATER, LAVA, NONE,
        /** Powder snow, cactus, berry bushes, magma, fire: no base on or next to it. */
        HAZARD
    }

    /** The terrain as the planner sees it. */
    public interface Terrain {
        /** Y of the highest solid (or liquid) block of the column. */
        int height(int x, int z);

        Surface surface(int x, int z);
    }

    /** One part of the base. */
    public enum Part {
        /** A block inside the base to dig away (a bump in the floor): broken, not placed. */
        CLEAR,
        /** Fills a dip in the floor. */
        FLOOR,
        WALL, PILLAR, ROOF, CHEST, CRAFTING_TABLE, COBWEB
    }

    /** One block to place, in world coordinates. */
    public record Placement(int x, int y, int z, Part part) {
    }

    /** Materials matching the biome (Bukkit material names). */
    public record Palette(String wall, String pillar, String roof) {

        /** The palette for a biome key such as {@code minecraft:desert}. */
        public static Palette forBiome(@Nullable String biome) {
            String b = biome == null ? "" : biome.toLowerCase(Locale.ROOT);
            if (b.contains("badlands")) {
                return new Palette("RED_SANDSTONE", "TERRACOTTA", "RED_SANDSTONE");
            }
            if (b.contains("desert") || b.contains("beach")) {
                return new Palette("SANDSTONE", "SMOOTH_SANDSTONE", "SANDSTONE");
            }
            if (b.contains("jungle") || b.contains("bamboo")) {
                return new Palette("JUNGLE_PLANKS", "JUNGLE_LOG", "JUNGLE_PLANKS");
            }
            if (b.contains("savanna")) {
                return new Palette("ACACIA_PLANKS", "ACACIA_LOG", "ACACIA_PLANKS");
            }
            if (b.contains("dark_forest")) {
                return new Palette("DARK_OAK_PLANKS", "DARK_OAK_LOG", "DARK_OAK_PLANKS");
            }
            if (b.contains("birch")) {
                return new Palette("BIRCH_PLANKS", "BIRCH_LOG", "BIRCH_PLANKS");
            }
            if (b.contains("taiga") || b.contains("snow") || b.contains("frozen") || b.contains("grove")) {
                return new Palette("SPRUCE_PLANKS", "SPRUCE_LOG", "SPRUCE_PLANKS");
            }
            if (b.contains("swamp") || b.contains("mangrove")) {
                return new Palette("MANGROVE_PLANKS", "MANGROVE_LOG", "MANGROVE_PLANKS");
            }
            if (b.contains("cherry")) {
                return new Palette("CHERRY_PLANKS", "CHERRY_LOG", "CHERRY_PLANKS");
            }
            if (b.contains("forest") || b.contains("plains") || b.contains("meadow") || b.contains("river")) {
                return new Palette("OAK_PLANKS", "OAK_LOG", "OAK_PLANKS");
            }
            return new Palette("COBBLESTONE", "STONE_BRICKS", "COBBLESTONE");
        }
    }

    /**
     * A planned base.
     *
     * @param x          center column
     * @param z          center column
     * @param floorY     the ground block the bot stands on inside (feet at {@code floorY + 1})
     * @param half       half side (walls at this distance from the center)
     * @param doorX      the doorway column (the bot walks in here)
     * @param doorZ      the doorway column
     * @param placements blocks in building order
     */
    public record Plan(int x, int z, int floorY, int half, int doorX, int doorZ, List<Placement> placements) {

        public Plan {
            placements = List.copyOf(placements);
        }

        /** True if {@code (bx, bz)} is inside the walls. */
        public boolean inside(double bx, double bz) {
            return Math.abs(bx - (x + 0.5)) < half && Math.abs(bz - (z + 0.5)) < half;
        }

        /** The two doorway cells ({x, y, z}, bottom first): open to walk through, closed with blocks to hold it. */
        public List<int[]> door() {
            return List.of(new int[] {doorX, floorY + 1, doorZ}, new int[] {doorX, floorY + 2, doorZ});
        }

        /** The standing spot just inside the doorway (feet block {x, y, z}): where it holds the door. */
        public int[] inner() {
            return new int[] {doorX - Integer.signum(doorX - x), floorY + 1, doorZ - Integer.signum(doorZ - z)};
        }

        /** The standing spot just outside the doorway (feet block {x, y, z}). */
        public int[] outer() {
            return new int[] {doorX + Integer.signum(doorX - x), floorY + 1, doorZ + Integer.signum(doorZ - z)};
        }

        /** The shooting slits ({x, y, z} of the gap in every wall but the door's), at eye height. */
        public List<int[]> slits() {
            List<int[]> out = new ArrayList<>();
            int[][] sides = {{half, 0}, {-half, 0}, {0, half}, {0, -half}};
            for (int[] side : sides) {
                int sx = x + side[0];
                int sz = z + side[1];
                if (sx != doorX || sz != doorZ) {
                    out.add(new int[] {sx, floorY + 2, sz});
                }
            }
            return out;
        }

        /** True if {@code (bx, by, bz)} is one of the slits. */
        boolean slit(int bx, int by, int bz) {
            for (int[] s : slits()) {
                if (s[0] == bx && s[1] == by && s[2] == bz) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Bases of different bots are at least this far apart. */
    static final double MIN_BASE_DISTANCE = 20;
    /** Most height difference allowed under the walls. */
    static final int MAX_STEP = 4;
    /** Most height difference of the floor inside from its center (bumps are dug away, dips filled). */
    static final int MAX_FLOOR_STEP = 2;
    /** Every base's walls are this high above its floor (feet + 3: a player cannot jump over), then the roof. */
    static final int WALL_HEIGHT = 3;

    private BasePlanner() {
    }

    /**
     * Scores a spot: high ground, flat, dry, closer to the final zone, away from the other bases; NaN if the base
     * cannot stand there.
     */
    public static double score(Terrain terrain, int x, int z, int size, double zoneX, double zoneZ,
                               List<int[]> otherBases) {
        return score(terrain, x, z, size, zoneX, zoneZ, otherBases, 0.04, Double.POSITIVE_INFINITY);
    }

    /**
     * {@link #score} with how much the distance to the zone counts ({@code zoneWeight} per block) and the farthest a
     * base may reach from the zone center ({@code safeHalf}, along either axis: where the border will still be for a
     * good while); NaN beyond it.
     */
    public static double score(Terrain terrain, int x, int z, int size, double zoneX, double zoneZ,
                               List<int[]> otherBases, double zoneWeight, double safeHalf) {
        int half = size / 2;
        if (Math.max(Math.abs(x - zoneX), Math.abs(z - zoneZ)) + half > safeHalf) {
            return Double.NaN; // the border will swallow it
        }
        for (int dx = -half - 2; dx <= half + 2; dx++) {
            for (int dz = -half - 2; dz <= half + 2; dz++) {
                Surface surface = terrain.surface(x + dx, z + dz);
                if (surface == Surface.HAZARD || surface == Surface.LAVA) {
                    return Double.NaN; // never on or next to powder snow, cactus, magma or lava
                }
            }
        }
        for (int[] other : otherBases) {
            if (Math.hypot(other[0] - x, other[1] - z) < MIN_BASE_DISTANCE) {
                return Double.NaN;
            }
        }
        int center = terrain.height(x, z);
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int dx = -half - 1; dx <= half + 1; dx++) {
            for (int dz = -half - 1; dz <= half + 1; dz++) {
                if (terrain.surface(x + dx, z + dz) != Surface.SOLID) {
                    return Double.NaN; // no base in water, lava or over the void
                }
                int h = terrain.height(x + dx, z + dz);
                if (Math.abs(dx) < half && Math.abs(dz) < half && Math.abs(h - center) > MAX_FLOOR_STEP) {
                    return Double.NaN; // the inside must be walkable
                }
                min = Math.min(min, h);
                max = Math.max(max, h);
            }
        }
        if (max - min > MAX_STEP) {
            return Double.NaN;
        }
        double around = 0;
        int samples = 0;
        int ring = half + 6;
        for (int i = 0; i < 16; i++) {
            double angle = i * Math.PI / 8;
            int sx = x + (int) Math.round(Math.cos(angle) * ring);
            int sz = z + (int) Math.round(Math.sin(angle) * ring);
            if (terrain.surface(sx, sz) == Surface.SOLID) {
                around += terrain.height(sx, sz);
                samples++;
            }
        }
        double height = samples == 0 ? 0 : center - around / samples;
        double zone = Math.hypot(zoneX - x, zoneZ - z);
        return 3 * Math.max(-4, Math.min(8, height)) - 4 * (max - min) - zoneWeight * zone;
    }

    /** The best of the candidate columns ({@code {x, z}}), or null if none fits. */
    public static int @Nullable [] choose(Terrain terrain, List<int[]> candidates, int size, double zoneX, double zoneZ,
                                          List<int[]> otherBases) {
        return choose(terrain, candidates, size, zoneX, zoneZ, otherBases, 0.04, Double.POSITIVE_INFINITY);
    }

    /** {@link #choose} with a zone weight and a safe half side (see the long {@code score}). */
    public static int @Nullable [] choose(Terrain terrain, List<int[]> candidates, int size, double zoneX, double zoneZ,
                                          List<int[]> otherBases, double zoneWeight, double safeHalf) {
        int[] best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int[] candidate : candidates) {
            double score = score(terrain, candidate[0], candidate[1], size, zoneX, zoneZ, otherBases, zoneWeight,
                    safeHalf);
            if (!Double.isNaN(score) && score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    /** The base at {@code (x, z)} with cobwebs at the door if it is big (see the long {@code plan}). */
    public static Plan plan(Terrain terrain, int x, int z, int size, double zoneX, double zoneZ) {
        return plan(terrain, x, z, size, zoneX, zoneZ, false);
    }

    /**
     * The base at {@code (x, z)}: a closed box of {@code size} (three-high walls on every side down to the ground, a
     * two-high doorway facing the final zone, a shooting slit at eye height in the middle of every other wall, a full
     * roof with a lookout block on the corner away from the door, a chest and a crafting table inside), cobweb traps
     * outside the doorway if it is big (more, and at the back corners, with {@code traps}).
     */
    public static Plan plan(Terrain terrain, int x, int z, int size, double zoneX, double zoneZ, boolean traps) {
        int half = Math.max(2, size / 2);
        int floorY = terrain.height(x, z);
        boolean big = half >= 3;
        // doorway on the side facing the zone center
        double ax = zoneX - x;
        double az = zoneZ - z;
        int doorX = x;
        int doorZ = z;
        if (Math.abs(ax) >= Math.abs(az)) {
            doorX = x + (ax >= 0 ? half : -half);
        } else {
            doorZ = z + (az >= 0 ? half : -half);
        }
        Plan shape = new Plan(x, z, floorY, half, doorX, doorZ, List.of());
        List<Placement> out = new ArrayList<>();
        // a level floor first: bumps dug away, dips filled
        for (int dx = -half + 1; dx < half; dx++) {
            for (int dz = -half + 1; dz < half; dz++) {
                int h = terrain.height(x + dx, z + dz);
                for (int y = h; y > floorY; y--) {
                    out.add(new Placement(x + dx, y, z + dz, Part.CLEAR));
                }
                for (int y = h + 1; y <= floorY; y++) {
                    out.add(new Placement(x + dx, y, z + dz, Part.FLOOR));
                }
            }
        }
        // walls, layer by layer from the lowest ground up
        int top = floorY + WALL_HEIGHT;
        int lowest = floorY;
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) == half) {
                    lowest = Math.min(lowest, terrain.height(x + dx, z + dz));
                }
            }
        }
        for (int y = lowest + 1; y <= top; y++) {
            for (int[] column : ring(x, z, half)) {
                int ground = terrain.height(column[0], column[1]);
                if (y <= ground) {
                    continue;
                }
                boolean door = column[0] == doorX && column[1] == doorZ && y > floorY && y <= floorY + 2;
                if (!door && !shape.slit(column[0], y, column[1])) {
                    out.add(new Placement(column[0], y, column[1], Part.WALL));
                }
            }
        }
        // the roof closes it; a lookout block on the corner away from the doorway
        int cornerX = x - Integer.signum(doorX - x) * half;
        int cornerZ = z - Integer.signum(doorZ - z) * half;
        if (cornerX == x) {
            cornerX = x + half;
        }
        if (cornerZ == z) {
            cornerZ = z + half;
        }
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                out.add(new Placement(x + dx, top + 1, z + dz, Part.ROOF));
            }
        }
        for (int y = top + 2; y <= top + (big ? 2 : 3); y++) {
            out.add(new Placement(cornerX, y, cornerZ, Part.PILLAR));
        }
        // a chest and a crafting table in the inside corners away from the door
        int inner = half - 1;
        int backX = doorX == x ? 1 : -Integer.signum(doorX - x);
        int backZ = doorZ == z ? 1 : -Integer.signum(doorZ - z);
        out.add(new Placement(x + backX * inner, floorY + 1, z + backZ * inner, Part.CHEST));
        // the other back corner: mirrored along the wall that faces the doorway
        boolean doorOnX = doorX != x;
        int tableX = doorOnX ? x + backX * inner : x - backX * inner;
        int tableZ = doorOnX ? z - backZ * inner : z + backZ * inner;
        out.add(new Placement(tableX, floorY + 1, tableZ, Part.CRAFTING_TABLE));
        if (big || traps) {
            int ox = Integer.signum(doorX - x);
            int oz = Integer.signum(doorZ - z);
            int outX = doorX + ox * 2;
            int outZ = doorZ + oz * 2;
            int sideX = oz != 0 ? 1 : 0;
            int sideZ = ox != 0 ? 1 : 0;
            List<int[]> webs = new ArrayList<>();
            for (int side : new int[] {-1, 1}) {
                webs.add(new int[] {outX + sideX * side, outZ + sideZ * side});
                if (traps) {
                    webs.add(new int[] {outX + ox + sideX * side * 2, outZ + oz + sideZ * side * 2});
                    // the back corners too: whoever circles the base walks into them
                    webs.add(new int[] {x - ox * (half + 2) + sideX * side * (half + 2),
                            z - oz * (half + 2) + sideZ * side * (half + 2)});
                }
            }
            for (int[] web : webs) {
                if (terrain.surface(web[0], web[1]) == Surface.SOLID) {
                    out.add(new Placement(web[0], terrain.height(web[0], web[1]) + 1, web[1], Part.COBWEB));
                }
            }
        }
        return new Plan(x, z, floorY, half, doorX, doorZ, out);
    }

    /** The wall ring's columns, walking around the square. */
    static List<int[]> ring(int x, int z, int half) {
        List<int[]> out = new ArrayList<>();
        for (int dx = -half; dx <= half; dx++) {
            out.add(new int[] {x + dx, z - half});
        }
        for (int dz = -half + 1; dz <= half; dz++) {
            out.add(new int[] {x + half, z + dz});
        }
        for (int dx = half - 1; dx >= -half; dx--) {
            out.add(new int[] {x + dx, z + half});
        }
        for (int dz = half - 1; dz > -half; dz--) {
            out.add(new int[] {x - half, z + dz});
        }
        return out;
    }
}
