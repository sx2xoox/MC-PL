package io.github.sx2xoox.mcpl.game.augment.bot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * Plans a bot's base from terrain samples, without Bukkit: where (high, flat, dry ground, towards the final zone, away
 * from other bases), which layout (its personality's {@link Design}: a keep, a tower, a bunker, a burrow, a trap
 * compound, a watch post or a box), how big (what it can close in the farming time left with the blocks it will have:
 * {@link #fit}) and in what order: the floor and the doorway, the walls, the roof (closed: {@link Plan#closure()}),
 * then furniture, towers, battlements, lights and traps. {@link BotController} places the blocks a few per second
 * and only where the world has air.
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
        /** Y of the highest solid (or liquid) block of the column; the ground under trees. */
        int height(int x, int z);

        Surface surface(int x, int z);
    }

    /** One part of the base. */
    public enum Part {
        /**
         * A block to dig away (a bump in the floor, a tree trunk in the room, earth in the doorway): broken, not
         * placed; nothing to do where there is air.
         */
        CLEAR,
        /** Fills a dip in the floor (or the step outside the doorway). */
        FLOOR,
        WALL,
        /** Part of the shell in the trim (logs: a keep's band between storeys, a tower's corners). */
        TRIM,
        /** Decoration and lookouts on top (towers, battlements). */
        PILLAR,
        ROOF, CHEST, CRAFTING_TABLE, COBWEB,
        /** A palisade post (a keep's palisade, a trapper's funnel and fence ring). */
        FENCE,
        /** A sweet berry bush (scratches and slows whoever walks through). */
        BUSH,
        /** Battlements (slabs). */
        CRENEL,
        /** A window pane in an upper storey (never in a shooting slit). */
        WINDOW,
        /** A lantern. */
        LIGHT
    }

    /** One block to place, in world coordinates. */
    public record Placement(int x, int y, int z, Part part) {
    }

    /**
     * Materials matching the biome and the layout (Bukkit material names). A part may name several blocks
     * ({@code "STONE_BRICKS|MOSSY_STONE_BRICKS|CRACKED_STONE_BRICKS"}): the first makes most of it, the others are
     * mixed in here and there ({@link #pick}), as a player builds with whatever they farmed.
     */
    public record Palette(String wall, String pillar, String roof, String trim, String floor, String crenel,
                          String window, String light) {

        /** A palette whose trim is its pillar block. */
        public Palette(String wall, String pillar, String roof) {
            this(wall, pillar, roof, pillar);
        }

        /** A palette with the wall's floor, the pillar's battlements, glass panes and lanterns. */
        public Palette(String wall, String pillar, String roof, String trim) {
            this(wall, pillar, roof, trim, wall, pillar, "GLASS_PANE", "LANTERN");
        }

        /** Share of a part in its first block (the rest are mixed in). */
        static final int MAIN_SHARE = 72;

        /** The block of {@code options} at {@code (x, y, z)}: the first mostly, the others speckled in. */
        public static String pick(String options, int x, int y, int z) {
            String[] all = options.split("\\|");
            if (all.length == 1) {
                return all[0];
            }
            int h = Math.floorMod((x * 73856093) ^ (y * 19349663) ^ (z * 83492791), 1000);
            if (h % 100 < MAIN_SHARE) {
                return all[0];
            }
            return all[1 + (h / 100) % (all.length - 1)];
        }

        /** The palisade's fence: the trim's wood, else spruce. */
        public String fence() {
            String wood = trim.split("\\|")[0].replace("STRIPPED_", "");
            return wood.endsWith("_LOG") ? wood.substring(0, wood.length() - 4) + "_FENCE" : "SPRUCE_FENCE";
        }

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
     * The layout of a base: every base-building personality has its own, recognisable at a glance and fitting how it
     * fights from it. All of them are closed (walls, roof, a doorway two blocks can shut).
     */
    public enum Design {
        /** A plain box: three-high walls, a roof, a slit in every wall but the door's, a lookout block. */
        BOX("상자", 5),
        /**
         * 요새꾼: a big keep with double walls, a two-deep gate (an airlock), slits on every side, corner towers and
         * battlements.
         */
        KEEP("성채", 9),
        /** 탱커: a low bunker with double walls and a one-wide roofed corridor to the doorway, held with the shield. */
        BUNKER("벙커", 7),
        /**
         * 저격수: a tower with a nest up top (a parapet with slits on every side, a roof), reached by pillaring up the
         * shaft in the middle, which it digs out again to come down.
         */
        TOWER("저격탑", 5),
        /**
         * 생존왕: a room dug four blocks under the ground (the earth is its walls and roof), a staircase down to a door
         * it can seal, nothing to see from outside.
         */
        BURROW("땅굴", 5),
        /**
         * 트래퍼: a box in a trap field: rings of cobwebs and pits around it on every side, a funnel of webs to the
         * doorway and a layer of webs on the roof.
         */
        TRAPS("함정 기지", 5),
        /** 어부지리형: a small post on the highest ground around, with a crenellated parapet to watch from. */
        WATCHPOST("감시 초소", 5);

        private final String koreanName;
        private final int minSize;

        Design(String koreanName, int minSize) {
            this.koreanName = koreanName;
            this.minSize = minSize;
        }

        public String koreanName() {
            return koreanName;
        }

        /** The smallest side this layout works with (thick walls need room inside). */
        public int minSize() {
            return minSize;
        }
    }

    /**
     * A planned base.
     *
     * @param design     its layout
     * @param x          center column
     * @param z          center column
     * @param floorY     the floor block of the room (feet at {@code floorY + 1}; under the ground for a burrow)
     * @param half       half side of its footprint (the outer walls at this distance from the center)
     * @param doorX      the doorway column (the bot walks in here)
     * @param doorZ      the doorway column
     * @param inner      the standing spot just inside the doorway (feet block {x, y, z}): where it holds the door
     * @param outer      the standing spot just outside the way in (feet block {x, y, z})
     * @param nestY      the floor block of where it shoots from (the tower's nest; the room floor otherwise)
     * @param roofY      the topmost roof layer
     * @param slits      shooting slits {x, y, z, standX, standY, standZ}: the gap and the feet block behind it
     * @param placements blocks in building order
     */
    public record Plan(Design design, int x, int z, int floorY, int half, int doorX, int doorZ, int[] inner,
                       int[] outer, int nestY, int roofY, List<int[]> slits, List<Placement> placements) {

        public Plan {
            slits = List.copyOf(slits);
            placements = List.copyOf(placements);
        }

        /** The side of the footprint (walls included). */
        public int size() {
            return 2 * half + 1;
        }

        /**
         * How many placements close the base: everything up to the last roof block (the floor, the doorway, the walls
         * and the roof); furniture, lookouts and traps come after.
         */
        public int closure() {
            int last = -1;
            for (int i = 0; i < placements.size(); i++) {
                if (placements.get(i).part() == Part.ROOF) {
                    last = i;
                }
            }
            return last + 1;
        }

        /** Blocks it needs to place to close the base (on its terrain: dips cost more, bumps and hills less). */
        public int closureBlocks() {
            int count = 0;
            for (Placement p : placements.subList(0, closure())) {
                if (p.part() != Part.CLEAR) {
                    count++;
                }
            }
            return count;
        }

        /** The blocks that keep it closed (floor, walls, roof): a hole in any of them is mended. */
        public List<Placement> shell() {
            List<Placement> out = new ArrayList<>();
            for (Placement p : placements) {
                if (p.part() == Part.FLOOR || p.part() == Part.WALL || p.part() == Part.TRIM
                        || p.part() == Part.ROOF) {
                    out.add(p);
                }
            }
            return out;
        }

        /** True if {@code (bx, bz)} is over the footprint, inside the outer walls. */
        public boolean inside(double bx, double bz) {
            return Math.abs(bx - (x + 0.5)) < half && Math.abs(bz - (z + 0.5)) < half;
        }

        /** True if feet at {@code (bx, by, bz)} are inside it (in the room or up in the nest, not on the roof). */
        public boolean inside(double bx, double by, double bz) {
            return inside(bx, bz) && by > floorY + 0.5 && by < roofY;
        }

        /**
         * The doorway cells ({x, y, z}, bottom first): open to walk through, closed with blocks to hold it. Two high;
         * a burrow's three (its stairs come down into it: a head would hit a lintel).
         */
        public List<int[]> door() {
            if (design == Design.BURROW) {
                return List.of(new int[] {doorX, floorY + 1, doorZ}, new int[] {doorX, floorY + 2, doorZ},
                        new int[] {doorX, floorY + 3, doorZ});
            }
            return List.of(new int[] {doorX, floorY + 1, doorZ}, new int[] {doorX, floorY + 2, doorZ});
        }

        /** True for a tower: the nest is reached up the shaft in the middle. */
        public boolean tower() {
            return nestY > floorY;
        }

        /**
         * The placements it makes from outside, before it can stand in the room: a burrow's staircase and room are dug
         * from the top of the stairs; 0 for the rest.
         */
        public int fromOutside() {
            if (design != Design.BURROW) {
                return 0;
            }
            int last = -1;
            for (int i = 0; i < placements.size(); i++) {
                Placement p = placements.get(i);
                if (p.part() == Part.CLEAR && Math.max(Math.abs(p.x() - x), Math.abs(p.z() - z)) < half) {
                    last = i;
                }
            }
            return last + 1;
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
    /** The smallest base: a 3 x 3 room inside walls. */
    public static final int MIN_SIZE = 5;
    /** The biggest base. */
    public static final int MAX_SIZE = 13;
    /** Share of the time left it plans to need for closing its base (the rest covers misses and digging). */
    static final double TIME_SAFETY = 0.5;

    /**
     * Blocks a closed base of {@code design} and side {@code size} needs on flat ground (the walls, the floor and the
     * roof, not the furniture, lookouts or traps).
     */
    public static int closureBlocks(Design design, int size) {
        Terrain flat = new Terrain() {
            @Override
            public int height(int x, int z) {
                return 64;
            }

            @Override
            public Surface surface(int x, int z) {
                return Surface.SOLID;
            }
        };
        return plan(flat, 0, 0, size, 100, 0, design).closureBlocks();
    }

    /**
     * How many blocks it can close a base with: the fewer of what it can place in the time it has (placements a
     * second times the match seconds left, less the walk there, with {@link #TIME_SAFETY}) and the blocks it will
     * have by then.
     *
     * @param blocksPerSecond placements a second (match clock)
     * @param secondsLeft     match seconds of the farming still to go
     * @param walkSeconds     match seconds the walk to the site takes
     * @param blocks          blocks it will have for the base
     */
    public static double budget(double blocksPerSecond, double secondsLeft, double walkSeconds, int blocks) {
        double time = blocksPerSecond * Math.max(0, secondsLeft - walkSeconds) * TIME_SAFETY;
        return Math.min(time, Math.max(0, blocks));
    }

    /**
     * The biggest side (odd, {@code wanted} down to the layout's smallest) of {@code design} it can close within
     * {@code budget} blocks; if not even the smallest fits, the plain box that does (down to {@link #MIN_SIZE}, built
     * anyway: a small closed base beats a big open one).
     */
    public static Fit fit(Design design, int wanted, double budget) {
        int size = Math.max(design.minSize(), Math.min(MAX_SIZE, wanted | 1));
        // a burrow's walls and roof are mostly the earth around it: a third of them is what it really places
        double cost = design == Design.BURROW ? 1.0 / 3 : 1;
        for (; size >= design.minSize(); size -= 2) {
            if (closureBlocks(design, size) * cost <= budget) {
                return new Fit(design, size);
            }
        }
        return new Fit(Design.BOX, fitSize(wanted, budget));
    }

    /** A layout and its side. */
    public record Fit(Design design, int size) {
    }

    /**
     * The biggest box side (odd, {@code wanted} down to {@link #MIN_SIZE}) it can close within {@code budget} blocks;
     * the smallest if even that does not fit.
     */
    public static int fitSize(int wanted, double budget) {
        int size = Math.max(MIN_SIZE, Math.min(MAX_SIZE, wanted | 1));
        while (size > MIN_SIZE && closureBlocks(Design.BOX, size) > budget) {
            size -= 2;
        }
        return size;
    }

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
        // a big base spans more ground: its walls may reach further down, its floor is levelled a bit more
        int maxStep = MAX_STEP + Math.max(0, half - 3);
        int floorStep = MAX_FLOOR_STEP + (half >= 5 ? 1 : 0);
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int dx = -half - 1; dx <= half + 1; dx++) {
            for (int dz = -half - 1; dz <= half + 1; dz++) {
                if (terrain.surface(x + dx, z + dz) != Surface.SOLID) {
                    return Double.NaN; // no base in water, lava or over the void
                }
                int h = terrain.height(x + dx, z + dz);
                if (Math.abs(dx) < half && Math.abs(dz) < half && Math.abs(h - center) > floorStep) {
                    return Double.NaN; // the inside must be walkable
                }
                min = Math.min(min, h);
                max = Math.max(max, h);
            }
        }
        if (max - min > maxStep) {
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

    /** The plain box at {@code (x, z)} (see the long {@code plan}). */
    public static Plan plan(Terrain terrain, int x, int z, int size, double zoneX, double zoneZ) {
        return plan(terrain, x, z, size, zoneX, zoneZ, Design.BOX);
    }

    /** The plain box, or with {@code traps} the trapper's base in its trap field. */
    public static Plan plan(Terrain terrain, int x, int z, int size, double zoneX, double zoneZ, boolean traps) {
        return plan(terrain, x, z, size, zoneX, zoneZ, traps ? Design.TRAPS : Design.BOX);
    }

    /**
     * The base of {@code design} at {@code (x, z)} with side {@code size} (at least the design's smallest), its
     * doorway facing the final zone at {@code (zoneX, zoneZ)}; placements in building order: the room levelled and the
     * doorway opened, the walls layer by layer from the lowest ground up, the roof (closed), then the furniture,
     * lookouts and traps.
     */
    public static Plan plan(Terrain terrain, int x, int z, int size, double zoneX, double zoneZ, Design design) {
        int side = Math.max(design.minSize(), Math.min(MAX_SIZE, size));
        int half = switch (design) {
            case TOWER -> side >= 9 ? 3 : 2; // the side is its height; the nest widens at the top sizes
            case BURROW -> side >= 7 ? 3 : 2;
            default -> side / 2;
        };
        double ax = zoneX - x;
        double az = zoneZ - z;
        int ox = 0;
        int oz = 0;
        if (Math.abs(ax) >= Math.abs(az)) {
            ox = ax >= 0 ? 1 : -1;
        } else {
            oz = az >= 0 ? 1 : -1;
        }
        Shape s = new Shape(terrain, x, z, half, ox, oz);
        return switch (design) {
            case BOX, TRAPS, WATCHPOST -> s.box(design);
            case KEEP -> s.keep();
            case BUNKER -> s.bunker();
            case TOWER -> s.tower(side);
            case BURROW -> s.burrow();
        };
    }

    /** Builds the placement list of one plan. */
    private static final class Shape {
        private final Terrain terrain;
        private final int x;
        private final int z;
        private final int half;
        /** Towards the doorway. */
        private final int ox;
        private final int oz;
        /** Along the doorway's wall. */
        private final int px;
        private final int pz;
        private final List<Placement> out = new ArrayList<>();
        private final java.util.Set<String> open = new java.util.HashSet<>();
        private final List<int[]> slits = new ArrayList<>();
        /** Wall columns built of the trim (a tower's log corners). */
        private final java.util.Set<String> trim = new java.util.HashSet<>();

        Shape(Terrain terrain, int x, int z, int half, int ox, int oz) {
            this.terrain = terrain;
            this.x = x;
            this.z = z;
            this.half = half;
            this.ox = ox;
            this.oz = oz;
            this.px = -oz;
            this.pz = ox;
        }

        private static String key(int x, int y, int z) {
            return x + "," + y + "," + z;
        }

        private int ground(int cx, int cz) {
            return terrain.height(cx, cz);
        }

        /** Digs whatever stands in the cell (as far as the terrain shows: up to the ground). */
        private void clear(int cx, int y, int cz) {
            if (y <= ground(cx, cz)) {
                out.add(new Placement(cx, y, cz, Part.CLEAR));
            }
        }

        /** A level room of radius {@code r} around the center: bumps dug, dips filled, the room up to {@code top}. */
        private void level(int r, int floorY, int top) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    int h = ground(x + dx, z + dz);
                    for (int y = h; y > floorY; y--) {
                        out.add(new Placement(x + dx, y, z + dz, Part.CLEAR));
                    }
                    for (int y = h + 1; y <= floorY; y++) {
                        out.add(new Placement(x + dx, y, z + dz, Part.FLOOR));
                    }
                    // the room itself: whatever stands in it above the ground (a tree trunk) is chopped
                    for (int y = Math.max(h, floorY) + 1; y <= top; y++) {
                        out.add(new Placement(x + dx, y, z + dz, Part.CLEAR));
                    }
                }
            }
        }

        /** An opening through the walls at {@code r} from the center along {@code (sx, sz)} plus {@code offset}. */
        private void opening(int sx, int sz, int[] radii, int offset, int fromY, int toY) {
            for (int r : radii) {
                int cx = x + sx * r + (sz != 0 ? offset : 0);
                int cz = z + sz * r + (sx != 0 ? offset : 0);
                for (int y = fromY; y <= toY; y++) {
                    open.add(key(cx, y, cz));
                    clear(cx, y, cz);
                }
            }
        }

        /** The doorway through every ring and the step outside it at floor level. */
        private void doorway(int floorY, int... radii) {
            opening(ox, oz, radii, 0, floorY + 1, floorY + 2);
            int sx = x + ox * (radii[0] + 1);
            int sz = z + oz * (radii[0] + 1);
            step(sx, sz, floorY);
        }

        /** A standing cell at floor level outside: earth dug away above, a step under it if the ground is lower. */
        private void step(int cx, int cz, int floorY) {
            int h = ground(cx, cz);
            for (int y = Math.min(h, floorY + 2); y > floorY; y--) {
                out.add(new Placement(cx, y, cz, Part.CLEAR));
            }
            for (int y = Math.max(h + 1, floorY - 1); y <= floorY; y++) {
                out.add(new Placement(cx, y, cz, Part.FLOOR));
            }
        }

        /** A slit at eye height through every ring on the side {@code (sx, sz)}, stood at from the room. */
        private void slit(int sx, int sz, int[] radii, int offset, int eyeY, int room) {
            opening(sx, sz, radii, offset, eyeY, eyeY);
            int cx = x + sx * radii[0] + (sz != 0 ? offset : 0);
            int cz = z + sz * radii[0] + (sx != 0 ? offset : 0);
            slits.add(new int[] {cx, eyeY, cz, x + sx * room + (sz != 0 ? offset : 0), eyeY - 1,
                    z + sz * room + (sx != 0 ? offset : 0)});
        }

        /** Slits in the middle of every wall but the doorway's. */
        private void sideSlits(int[] radii, int eyeY, int room) {
            for (int[] side : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                if (side[0] != ox || side[1] != oz) {
                    slit(side[0], side[1], radii, 0, eyeY, room);
                }
            }
        }

        /** The lowest ground under the rings. */
        private int lowest(int[] radii, int floorY) {
            int lowest = floorY;
            for (int r : radii) {
                for (int[] column : ring(x, z, r)) {
                    lowest = Math.min(lowest, ground(column[0], column[1]));
                }
            }
            return lowest;
        }

        /** Walls on every ring, layer by layer from {@code fromY} up to {@code toY}, down to the ground. */
        private void walls(int[] radii, int fromY, int toY) {
            for (int y = fromY; y <= toY; y++) {
                for (int r : radii) {
                    for (int[] column : ring(x, z, r)) {
                        if (y > ground(column[0], column[1]) && !open.contains(key(column[0], y, column[1]))) {
                            out.add(new Placement(column[0], y, column[1],
                                    trim.contains(column[0] + "," + column[1]) ? Part.TRIM : Part.WALL));
                        }
                    }
                }
            }
        }

        private void roof(int r, int y) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    out.add(new Placement(x + dx, y, z + dz, Part.ROOF));
                }
            }
        }

        /** A chest and a crafting table in the room's corners away from the door ({@code r}: the room's radius). */
        private void furniture(int r, int floorY) {
            int backX = ox == 0 ? 1 : -ox;
            int backZ = oz == 0 ? 1 : -oz;
            out.add(new Placement(x + backX * r, floorY + 1, z + backZ * r, Part.CHEST));
            int tableX = ox != 0 ? x + backX * r : x - backX * r;
            int tableZ = ox != 0 ? z - backZ * r : z + backZ * r;
            out.add(new Placement(tableX, floorY + 1, tableZ, Part.CRAFTING_TABLE));
        }

        private int[] at(int r, int y) {
            return new int[] {x + ox * r, y, z + oz * r};
        }

        private Plan done(Design design, int floorY, int[] inner, int[] outer, int nestY, int roofY) {
            return new Plan(design, x, z, floorY, half, x + ox * half, z + oz * half, inner, outer, nestY, roofY,
                    slits, out);
        }

        // ------------------------------------------------------------------- the layouts

        Plan box(Design design) {
            int floorY = ground(x, z);
            int[] rings = {half};
            level(half - 1, floorY, floorY + WALL_HEIGHT);
            doorway(floorY, rings);
            sideSlits(rings, floorY + 2, half - 1);
            int top = floorY + WALL_HEIGHT;
            walls(rings, lowest(rings, floorY) + 1, top);
            roof(half, top + 1);
            furniture(half - 1, floorY);
            boolean big = half >= 3;
            // a lookout block on the roof corner away from the doorway
            int cornerX = x - (ox != 0 ? ox : -1) * half;
            int cornerZ = z - (oz != 0 ? oz : -1) * half;
            for (int y = top + 2; y <= top + (big ? 2 : 3); y++) {
                out.add(new Placement(cornerX, y, cornerZ, Part.PILLAR));
            }
            if (design == Design.WATCHPOST) {
                crenels(half, top + 2); // a parapet all round the roof to watch the fights from
                lights(half - 1, top + 2);
            } else if (design == Design.BOX && big) {
                lights(half - 1, top + 2);
            }
            if (design == Design.TRAPS) {
                trapField(top + 1);
            } else if (big) {
                // two cobwebs out in front of the doorway
                for (int side : new int[] {-1, 1}) {
                    web(x + ox * (half + 2) + px * side, z + oz * (half + 2) + pz * side);
                }
            }
            return done(design, floorY, at(half - 1, floorY + 1), at(half + 1, floorY + 1), floorY, top + 1);
        }

        /** Every other block of the ring at {@code half}, at {@code y}: battlements. */
        private void crenels(int r, int y) {
            List<int[]> edge = ring(x, z, r);
            for (int i = 0; i < edge.size(); i += 2) {
                out.add(new Placement(edge.get(i)[0], y, edge.get(i)[1], Part.CRENEL));
            }
        }

        /** Lanterns on the roof at {@code y}, {@code r} from the middle (inside the battlements). */
        private void lights(int r, int y) {
            for (int[] c : new int[][] {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) {
                out.add(new Placement(x + c[0] * r, y, z + c[1] * r, Part.LIGHT));
            }
        }

        /** A column of {@code part} from the ground (or {@code from}) up to {@code to}. */
        private void column(int cx, int cz, int from, int to, Part part) {
            for (int y = Math.max(from, ground(cx, cz) + 1); y <= to; y++) {
                out.add(new Placement(cx, y, cz, part));
            }
        }

        /**
         * 요새꾼's keep, grander the bigger: double walls with a two-deep gate (an airlock) and slits on every side,
         * battlements, towers at the back corners. From 11 on: two storeys with a log band between them and windows
         * up top, a tower at every corner; from 13: a gatehouse in front of the gate and a palisade all round.
         */
        Plan keep() {
            int floorY = ground(x, z);
            int[] rings = {half, half - 1};
            int room = half - 2;
            boolean storeys = half >= 5;
            boolean grand = half >= 6;
            level(room, floorY, floorY + WALL_HEIGHT);
            doorway(floorY, rings);
            sideSlits(rings, floorY + 2, room);
            for (int side : new int[] {-1, 1}) {
                slit(ox, oz, rings, 2 * side, floorY + 2, room); // and either side of the gate
            }
            int top = floorY + WALL_HEIGHT;
            List<Placement> windows = new ArrayList<>();
            if (storeys) {
                // windows on the upper storey
                for (int[] side : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    for (int offset : grand ? new int[] {-3, 0, 3} : new int[] {0}) {
                        opening(side[0], side[1], new int[] {half}, offset, floorY + 6, floorY + 6);
                        windows.add(new Placement(x + side[0] * half + (side[1] != 0 ? offset : 0), floorY + 6,
                                z + side[1] * half + (side[0] != 0 ? offset : 0), Part.WINDOW));
                    }
                }
            }
            walls(rings, lowest(rings, floorY) + 1, top);
            if (storeys) {
                for (int[] c : ring(x, z, half)) {
                    out.add(new Placement(c[0], top + 1, c[1], Part.TRIM)); // a log band between the storeys
                }
                for (int dx = -half + 1; dx < half; dx++) {
                    for (int dz = -half + 1; dz < half; dz++) {
                        out.add(new Placement(x + dx, top + 1, z + dz, Part.FLOOR)); // the upper floor
                    }
                }
                walls(new int[] {half}, top + 2, top + 4);
                top += 4;
            }
            roof(half, top + 1);
            furniture(room, floorY);
            out.addAll(windows);
            crenels(half, top + 2);
            lights(half - 2, top + 2);
            // towers: 2 x 2 at the corners, standing out of the walls, crenellated
            for (int[] corner : new int[][] {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) {
                boolean back = corner[0] * ox + corner[1] * oz < 0 || ox == 0 && oz == 0;
                if (!storeys && !back) {
                    continue; // a small keep: two towers at the back
                }
                int cx = x + corner[0] * half;
                int cz = z + corner[1] * half;
                column(cx, cz, top + 2, top + 4, Part.PILLAR);
                for (int[] c : new int[][] {{cx + corner[0], cz}, {cx, cz + corner[1]}, {cx + corner[0],
                        cz + corner[1]}}) {
                    column(c[0], c[1], Integer.MIN_VALUE, top + 4, Part.PILLAR);
                }
                out.add(new Placement(cx + corner[0], top + 5, cz + corner[1], Part.PILLAR));
                out.add(new Placement(cx, top + 5, cz, Part.PILLAR));
            }
            if (grand) {
                // a gatehouse: two flanking towers in front of the gate and a roof over the way in
                for (int k = 1; k <= 2; k++) {
                    for (int side : new int[] {-1, 1}) {
                        column(x + ox * (half + k) + px * side * 2, z + oz * (half + k) + pz * side * 2,
                                Integer.MIN_VALUE, floorY + 6, Part.PILLAR);
                    }
                    for (int j = -1; j <= 1; j++) {
                        out.add(new Placement(x + ox * (half + k) + px * j, floorY + 4, z + oz * (half + k) + pz * j,
                                Part.TRIM));
                    }
                }
                for (int side : new int[] {-1, 1}) {
                    out.add(new Placement(x + ox * (half + 2) + px * side * 2, floorY + 7,
                            z + oz * (half + 2) + pz * side * 2, Part.PILLAR));
                }
                // a palisade all round, open in front of the gatehouse
                for (int[] c : ring(x, z, half + 4)) {
                    int along = (c[0] - x) * ox + (c[1] - z) * oz;
                    int across = (c[0] - x) * px + (c[1] - z) * pz;
                    if (along == half + 4 && Math.abs(across) <= 1 || terrain.surface(c[0], c[1]) != Surface.SOLID) {
                        continue;
                    }
                    out.add(new Placement(c[0], ground(c[0], c[1]) + 1, c[1], Part.FENCE));
                }
            }
            return done(Design.KEEP, floorY, at(room, floorY + 1), at(half + 1, floorY + 1), floorY, top + 1);
        }

        /**
         * 탱커's bunker: low (two blocks of room), thick walls (three rings from 9 on), slits through them, and a
         * one-wide roofed corridor out of the doorway: the chokepoint it holds with its shield.
         */
        Plan bunker() {
            int floorY = ground(x, z);
            int[] rings = half >= 4 ? new int[] {half, half - 1, half - 2} : new int[] {half, half - 1};
            int room = half - rings.length;
            int top = floorY + 2;
            int length = half >= 4 ? 3 : 2;
            level(room, floorY, top);
            opening(ox, oz, rings, 0, floorY + 1, floorY + 2);
            sideSlits(rings, floorY + 2, room);
            List<Placement> corridor = new ArrayList<>();
            for (int k = 1; k <= length; k++) {
                int cx = x + ox * (half + k);
                int cz = z + oz * (half + k);
                step(cx, cz, floorY);
                for (int side : new int[] {-1, 1}) {
                    int wx = cx + px * side;
                    int wz = cz + pz * side;
                    for (int y = ground(wx, wz) + 1; y <= top; y++) {
                        corridor.add(new Placement(wx, y, wz, Part.WALL));
                    }
                    corridor.add(new Placement(wx, top + 1, wz, Part.ROOF));
                }
                corridor.add(new Placement(cx, top + 1, cz, Part.ROOF));
            }
            step(x + ox * (half + length + 1), z + oz * (half + length + 1), floorY);
            walls(rings, lowest(rings, floorY) + 1, top);
            for (Placement p : corridor) {
                if (p.part() == Part.WALL) {
                    out.add(p);
                }
            }
            roof(half, top + 1);
            for (Placement p : corridor) {
                if (p.part() == Part.ROOF) {
                    out.add(p);
                }
            }
            furniture(room, floorY);
            return done(Design.BUNKER, floorY, at(room, floorY + 1), at(half + length + 1, floorY + 1), floorY,
                    top + 1);
        }

        /**
         * 저격수's tower: a nest up top (taller and wider the bigger: {@code size} blocks up, 7 x 7 from 9 on) with
         * a slit on every side under a crenellated roof, log corners all the way up, and a shaft in the middle it
         * pillars up and digs out again to come down.
         */
        Plan tower(int size) {
            int floorY = ground(x, z);
            int[] rings = {half};
            int nest = floorY + Math.max(4, size - 1);
            for (int[] corner : new int[][] {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) {
                trim.add((x + corner[0] * half) + "," + (z + corner[1] * half));
            }
            level(half - 1, floorY, floorY + WALL_HEIGHT);
            doorway(floorY, rings);
            for (int[] side : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                slit(side[0], side[1], rings, 0, nest + 2, half - 1); // the nest looks out on every side
            }
            walls(rings, lowest(rings, floorY) + 1, nest);
            for (int dx = -half + 1; dx < half; dx++) {
                for (int dz = -half + 1; dz < half; dz++) {
                    if (dx != 0 || dz != 0) {
                        out.add(new Placement(x + dx, nest, z + dz, Part.FLOOR)); // round the shaft
                    }
                }
            }
            walls(rings, nest + 1, nest + 2);
            roof(half, nest + 3);
            furniture(half - 1, floorY);
            crenels(half, nest + 4);
            lights(half - 1, nest + 4);
            return done(Design.TOWER, floorY, at(half - 1, floorY + 1), at(half + 1, floorY + 1), nest, nest + 3);
        }

        /**
         * 생존왕's burrow: a room (3 x 3, 5 x 5 from 7 on) dug four blocks under the ground, the earth its walls and
         * roof (only holes are patched, with the same earth), a staircase down to a door it can seal.
         */
        Plan burrow() {
            int g = Integer.MAX_VALUE;
            for (int dx = -half; dx <= half; dx++) {
                for (int dz = -half; dz <= half; dz++) {
                    g = Math.min(g, ground(x + dx, z + dz));
                }
            }
            int floorY = g - 4;
            int room = half - 1;
            // the way down first, from the top: a staircase to the doorway (it digs its way down it)
            for (int k = 4; k >= 1; k--) {
                int cx = x + ox * (half + k);
                int cz = z + oz * (half + k);
                int feet = floorY + 1 + k;
                for (int y = feet + 2; y >= feet; y--) {
                    clear(cx, y, cz);
                }
                if (k < 4) {
                    out.add(new Placement(cx, feet - 1, cz, Part.FLOOR)); // the tread (where a cave is under it)
                }
            }
            opening(ox, oz, new int[] {half}, 0, floorY + 1, floorY + 3); // three high: the stairs come down into it
            out.add(new Placement(x + ox * half, floorY, z + oz * half, Part.FLOOR));
            for (int y = floorY + 3; y >= floorY + 1; y--) {
                for (int dx = -room; dx <= room; dx++) {
                    for (int dz = -room; dz <= room; dz++) {
                        out.add(new Placement(x + dx, y, z + dz, Part.CLEAR));
                    }
                }
            }
            for (int k = 1; k <= 3; k++) {
                int feet = floorY + 1 + k;
                for (int side : new int[] {-1, 1}) {
                    int wx = x + ox * (half + k) + px * side;
                    int wz = z + oz * (half + k) + pz * side;
                    for (int y = feet; y <= Math.min(feet + 1, ground(wx, wz)); y++) {
                        out.add(new Placement(wx, y, wz, Part.WALL));
                    }
                }
            }
            for (int dx = -room; dx <= room; dx++) {
                for (int dz = -room; dz <= room; dz++) {
                    out.add(new Placement(x + dx, floorY, z + dz, Part.FLOOR));
                }
            }
            for (int y = floorY + 1; y <= floorY + 3; y++) {
                for (int[] column : ring(x, z, half)) {
                    if (!open.contains(key(column[0], y, column[1]))) {
                        out.add(new Placement(column[0], y, column[1], Part.WALL));
                    }
                }
            }
            roof(half, g);
            furniture(room, floorY);
            return done(Design.BURROW, floorY, at(room, floorY + 1), at(half + 4, floorY + 5), floorY, g);
        }

        /** A cobweb on the ground at the column (if there is ground). */
        private void web(int cx, int cz) {
            if (terrain.surface(cx, cz) == Surface.SOLID) {
                out.add(new Placement(cx, ground(cx, cz) + 1, cz, Part.COBWEB));
            }
        }

        /**
         * The trapper's field (the start of it: it keeps laying traps further out once closed): a fenced funnel along
         * the lane to the doorway (the only free way in, straight at the shut door and its slits), webs at the
         * funnel's mouth and under the slits, a ring of two-deep pits, berry bushes and webs two out from the walls,
         * a palisade with bushes in its gaps one further, a scatter of webs beyond (from 7 on), and a few webs on the
         * solid roof against anyone pillaring over.
         */
        private void trapField(int roofY) {
            int rings = half >= 3 ? 3 : 2;
            java.util.Set<String> lane = new java.util.HashSet<>();
            for (int k = 1; k <= rings + 2; k++) {
                lane.add((x + ox * (half + k)) + "," + (z + oz * (half + k)));
            }
            java.util.Set<String> done = new java.util.HashSet<>();
            // a fenced funnel along the lane: whoever comes in is led straight at the shut door and the slits
            for (int k = 2; k <= rings + 2; k++) {
                for (int side : new int[] {-1, 1}) {
                    int cx = x + ox * (half + k) + px * side;
                    int cz = z + oz * (half + k) + pz * side;
                    done.add(cx + "," + cz);
                    ground(cx, cz, Part.FENCE);
                }
            }
            // webs at the funnel's mouth and under the slits: the chokepoints
            for (int side : new int[] {-1, 1}) {
                int cx = x + ox * (half + rings + 3) + px * side * 2;
                int cz = z + oz * (half + rings + 3) + pz * side * 2;
                done.add(cx + "," + cz);
                web(cx, cz);
            }
            for (int[] side : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                if (side[0] != ox || side[1] != oz) {
                    int cx = x + side[0] * (half + 1);
                    int cz = z + side[1] * (half + 1);
                    done.add(cx + "," + cz);
                    web(cx, cz);
                }
            }
            // two out: pits two deep, berry bushes and a few webs; three out: a palisade with gaps (bushes in
            // them); further out (from 7 on) a scatter of webs
            for (int r = 0; r < rings; r++) {
                List<int[]> cells = ring(x, z, half + 2 + r);
                for (int i = 0; i < cells.size(); i++) {
                    int[] c = cells.get(i);
                    String id = c[0] + "," + c[1];
                    if (lane.contains(id) || !done.add(id) || terrain.surface(c[0], c[1]) != Surface.SOLID) {
                        continue;
                    }
                    int h = ground(c[0], c[1]);
                    if (r == 0) {
                        switch (i % 4) {
                            case 0 -> {
                                out.add(new Placement(c[0], h, c[1], Part.CLEAR)); // a pit two deep
                                out.add(new Placement(c[0], h - 1, c[1], Part.CLEAR));
                            }
                            case 2 -> web(c[0], c[1]);
                            default -> ground(c[0], c[1], Part.BUSH);
                        }
                    } else if (r == 1) {
                        ground(c[0], c[1], i % 5 == 0 ? Part.BUSH : Part.FENCE);
                    } else if (i % 3 == 0) {
                        web(c[0], c[1]);
                    }
                }
            }
            // a few webs on the roof against anyone pillaring over (the roof itself is solid)
            for (int dx = -half; dx <= half; dx++) {
                for (int dz = -half; dz <= half; dz++) {
                    if (Math.floorMod(dx + 2 * dz, 4) == 0) {
                        out.add(new Placement(x + dx, roofY + 1, z + dz, Part.COBWEB));
                    }
                }
            }
        }

        /** A block of {@code part} standing on the ground at the column (if there is ground). */
        private void ground(int cx, int cz, Part part) {
            if (terrain.surface(cx, cz) == Surface.SOLID) {
                out.add(new Placement(cx, ground(cx, cz) + 1, cz, part));
            }
        }
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
