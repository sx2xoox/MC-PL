package io.github.sx2xoox.mcpl.core.map;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Slab;
import org.jspecify.annotations.Nullable;

/**
 * Block placement checks any map can run on its finished canvas: every block that needs support has it (lanterns,
 * torches, ladders, signs, banners, buttons, carpets, plates, rails, candles, plants, vines, doors), chains hang from
 * something, trees are whole (leaves near a log, trunks on the ground), stairs and slabs are oriented. Findings are
 * grouped by kind with a few examples each, so a report stays short.
 */
public final class MapBlockChecks {

    private static final int EXAMPLES = 4;

    private final MapCanvas canvas;
    private final BiFunction<Integer, Integer, String> zoneOf;
    private final Map<String, List<String>> findings = new LinkedHashMap<>();
    private final Map<String, Integer> counts = new HashMap<>();

    public MapBlockChecks(MapCanvas canvas, BiFunction<Integer, Integer, String> zoneOf) {
        this.canvas = canvas;
        this.zoneOf = zoneOf;
    }

    /** Runs every check; an empty list means every block is placed correctly. */
    public List<String> run() {
        List<int[]> chains = new ArrayList<>();
        List<int[]> leaves = new ArrayList<>();
        Set<Long> logs = new HashSet<>();
        canvas.forEach((x, y, z, spec) -> {
            String n = spec.material().name();
            if (isChain(n)) {
                chains.add(new int[] {x, y, z});
            }
            if (n.endsWith("_LEAVES")) {
                leaves.add(new int[] {x, y, z});
            }
            if (isLog(n)) {
                logs.add(key(x, y, z));
                if (n.endsWith("_LOG") && (spec.axis() == null || spec.axis() == Axis.Y)) {
                    BlockSpec below = canvas.get(x, y - 1, z);
                    if (below == null || below.isAir() || MapBlocks.isFluid(below.material())) {
                        add("floating trunk", x, y, z, spec);
                    }
                }
            }
            check(x, y, z, spec, n);
        });
        chains(chains);
        leaves(leaves, logs);
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : findings.entrySet()) {
            out.add(counts.get(entry.getKey()) + "x " + entry.getKey() + ": " + String.join(", ", entry.getValue()));
        }
        return out;
    }

    private void check(int x, int y, int z, BlockSpec spec, String n) {
        Material m = spec.material();
        if (n.endsWith("LANTERN") && m != Material.SEA_LANTERN && m != Material.JACK_O_LANTERN) {
            if (spec.hanging() == Boolean.TRUE) {
                if (!supportsDown(canvas.get(x, y + 1, z))) {
                    add("hanging lantern without a block above", x, y, z, spec);
                }
            } else if (!supportsUp(canvas.get(x, y - 1, z))) {
                add("lantern without a block below", x, y, z, spec);
            }
            return;
        }
        if (n.endsWith("_WALL_TORCH") || n.equals("LADDER") || n.endsWith("_WALL_SIGN")
                || n.endsWith("_WALL_HANGING_SIGN") || n.endsWith("_WALL_BANNER") || n.endsWith("_BUTTON")
                || n.equals("LEVER")) {
            BlockFace facing = spec.facing() == null ? BlockFace.NORTH : spec.facing();
            BlockSpec behind = canvas.get(x - facing.getModX(), y, z - facing.getModZ());
            boolean ok = n.endsWith("_HANGING_SIGN") ? behind != null && !behind.isAir()
                    : behind != null && (MapBlocks.isFullCube(behind) || !n.equals("LADDER")
                    && !n.endsWith("_WALL_TORCH") && MapBlocks.isStandable(behind));
            if (!ok) {
                add(n.toLowerCase().replace('_', ' ') + " without a block behind", x, y, z, spec);
            }
            return;
        }
        if (n.equals("TORCH") || n.equals("SOUL_TORCH") || n.equals("REDSTONE_TORCH") || n.equals("COPPER_TORCH")) {
            if (!supportsUp(canvas.get(x, y - 1, z))) {
                add("torch without a block below", x, y, z, spec);
            }
            return;
        }
        if (n.endsWith("_HANGING_SIGN") || n.equals("SPORE_BLOSSOM") || n.equals("HANGING_ROOTS")
                || n.startsWith("CAVE_VINES") || n.startsWith("WEEPING_VINES") || n.equals("PALE_HANGING_MOSS")) {
            BlockSpec above = canvas.get(x, y + 1, z);
            if (above == null || above.isAir()) {
                add(n.toLowerCase().replace('_', ' ') + " without a block above", x, y, z, spec);
            }
            return;
        }
        if (n.endsWith("_SIGN") || n.endsWith("_BANNER") || n.endsWith("_CARPET") || n.endsWith("PRESSURE_PLATE")
                || n.endsWith("RAIL") || n.endsWith("CANDLE") || n.equals("REDSTONE_WIRE") || n.equals("REPEATER")
                || n.equals("COMPARATOR") || n.equals("SNOW") || n.equals("SEA_PICKLE") || n.equals("TWISTING_VINES")) {
            BlockSpec below = canvas.get(x, y - 1, z);
            if (below == null || !MapBlocks.isStandable(below)) {
                add(n.toLowerCase().replace('_', ' ') + " without a block below", x, y, z, spec);
            }
            return;
        }
        if (isPlant(n)) {
            BlockSpec below = canvas.get(x, y - 1, z);
            if (spec.half() == Bisected.Half.TOP) {
                if (below == null || below.material() != m || below.half() != Bisected.Half.BOTTOM) {
                    add("upper half of a tall plant without its lower half", x, y, z, spec);
                }
                return;
            }
            if (below == null || !isSoil(below.material(), n)) {
                add(n.toLowerCase().replace('_', ' ') + " not on soil", x, y, z, below == null ? spec : below);
            }
            if (isTallPlant(n)) {
                BlockSpec above = canvas.get(x, y + 1, z);
                if (above == null || above.material() != m || above.half() != Bisected.Half.TOP) {
                    add("tall plant without its upper half", x, y, z, spec);
                }
            }
            return;
        }
        if (n.equals("VINE") || n.equals("GLOW_LICHEN")) {
            boolean attached = false;
            for (int[] d : new int[][] {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}}) {
                BlockSpec next = canvas.get(x + d[0], y + d[1], z + d[2]);
                attached |= next != null && (MapBlocks.isFullCube(next) || d[1] == 1 && next.material() == m);
            }
            if (!attached) {
                add(n.toLowerCase().replace('_', ' ') + " not attached to a block", x, y, z, spec);
            }
            return;
        }
        if (n.endsWith("_DOOR")) {
            boolean top = spec.half() == Bisected.Half.TOP;
            BlockSpec other = canvas.get(x, y + (top ? -1 : 1), z);
            boolean whole = other != null && other.material() == m
                    && other.half() == (top ? Bisected.Half.BOTTOM : Bisected.Half.TOP);
            if (!whole) {
                add("half a door", x, y, z, spec);
            } else if (!top) {
                BlockSpec below = canvas.get(x, y - 1, z);
                if (below == null || !MapBlocks.isStandable(below)) {
                    add("door without a floor", x, y, z, spec);
                }
            }
            return;
        }
        if (n.endsWith("_STAIRS") && (spec.facing() == null || spec.half() == null)) {
            add("stairs without facing/half", x, y, z, spec);
        }
        if (n.endsWith("_SLAB") && spec.slab() == null) {
            add("slab without a type", x, y, z, spec);
        }
    }

    /** Every connected run of chains touches a solid block (it hangs from or stands on something). */
    private void chains(List<int[]> chains) {
        Set<Long> seen = new HashSet<>();
        for (int[] start : chains) {
            if (!seen.add(key(start[0], start[1], start[2]))) {
                continue;
            }
            ArrayDeque<int[]> queue = new ArrayDeque<>();
            queue.add(start);
            boolean anchored = false;
            int size = 0;
            while (!queue.isEmpty()) {
                int[] at = queue.poll();
                size++;
                for (int[] d : SIDES6) {
                    int x = at[0] + d[0];
                    int y = at[1] + d[1];
                    int z = at[2] + d[2];
                    BlockSpec next = canvas.get(x, y, z);
                    if (next == null || next.isAir()) {
                        continue;
                    }
                    if (isChain(next.material().name())) {
                        if (seen.add(key(x, y, z))) {
                            queue.add(new int[] {x, y, z});
                        }
                    } else if (MapBlocks.isStandable(next) && !next.material().name().endsWith("LANTERN")
                            || next.material() == Material.SEA_LANTERN) {
                        anchored = true;
                    }
                }
            }
            if (!anchored) {
                add("chain hanging in the air (" + size + " long)", start[0], start[1], start[2],
                        canvas.get(start[0], start[1], start[2]));
            }
        }
    }

    /**
     * Trees must be whole: in a clump of leaves that has a log, every leaf is within 6 steps (through leaves) of a
     * log, like trees in the game; a clump without any log (a hedge, a bush) must rest on something, or it is a crown
     * whose trunk was cut away.
     */
    private void leaves(List<int[]> leaves, Set<Long> logs) {
        if (leaves.isEmpty()) {
            return;
        }
        Map<Long, Integer> distance = new HashMap<>();
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        for (long log : logs) {
            distance.put(log, 0);
            queue.add(unkey(log));
        }
        while (!queue.isEmpty()) {
            int[] at = queue.poll();
            int d = distance.get(key(at[0], at[1], at[2]));
            if (d >= 6) {
                continue;
            }
            for (int[] s : SIDES6) {
                int x = at[0] + s[0];
                int y = at[1] + s[1];
                int z = at[2] + s[2];
                BlockSpec next = canvas.get(x, y, z);
                if (next != null && isLeaves(next) && !distance.containsKey(key(x, y, z))) {
                    distance.put(key(x, y, z), d + 1);
                    queue.add(new int[] {x, y, z});
                }
            }
        }
        Set<Long> seen = new HashSet<>();
        for (int[] leaf : leaves) {
            if (!seen.add(key(leaf[0], leaf[1], leaf[2]))) {
                continue;
            }
            // The clump of this leaf (leaves and logs touching it).
            List<int[]> clump = new ArrayList<>();
            boolean hasLog = false;
            boolean rests = false;
            ArrayDeque<int[]> todo = new ArrayDeque<>();
            todo.add(leaf);
            while (!todo.isEmpty()) {
                int[] at = todo.poll();
                clump.add(at);
                BlockSpec below = canvas.get(at[0], at[1] - 1, at[2]);
                rests |= below != null && !below.isAir() && !isLeaves(below) && !MapBlocks.isFluid(below.material());
                for (int[] s : SIDES6) {
                    int x = at[0] + s[0];
                    int y = at[1] + s[1];
                    int z = at[2] + s[2];
                    BlockSpec next = canvas.get(x, y, z);
                    if (next == null) {
                        continue;
                    }
                    hasLog |= logs.contains(key(x, y, z));
                    if (isLeaves(next) && seen.add(key(x, y, z))) {
                        todo.add(new int[] {x, y, z});
                    }
                }
            }
            for (int[] at : clump) {
                if (hasLog && !distance.containsKey(key(at[0], at[1], at[2]))) {
                    add("leaves too far from their trunk", at[0], at[1], at[2], canvas.get(at[0], at[1], at[2]));
                }
            }
            if (!hasLog && !rests) {
                add("leaves floating without a trunk (" + clump.size() + ")", leaf[0], leaf[1], leaf[2],
                        canvas.get(leaf[0], leaf[1], leaf[2]));
            }
        }
    }

    private static boolean isLeaves(BlockSpec spec) {
        return spec.material().name().endsWith("_LEAVES");
    }

    // ------------------------------------------------------------------ support rules

    /** True if a lantern or torch can stand on {@code below} (it offers the centre of its top face). */
    static boolean supportsUp(@Nullable BlockSpec below) {
        if (below == null || below.isAir()) {
            return false;
        }
        String n = below.material().name();
        if (n.endsWith("_SLAB")) {
            return below.slab() == Slab.Type.TOP || below.slab() == Slab.Type.DOUBLE;
        }
        if (n.endsWith("_STAIRS")) {
            return below.half() == Bisected.Half.TOP;
        }
        if (isChain(n) || n.equals("END_ROD") || n.endsWith("LIGHTNING_ROD")) {
            return below.axis() == null || below.axis() == Axis.Y || below.facing() == BlockFace.UP
                    || below.facing() == BlockFace.DOWN || below.facing() == null;
        }
        return MapBlocks.isFullCube(below) || MapBlocks.family(below.material()) != MapBlocks.Family.NONE
                || n.equals("HOPPER");
    }

    /** True if a lantern can hang from {@code above} (it offers the centre of its bottom face). */
    static boolean supportsDown(@Nullable BlockSpec above) {
        if (above == null || above.isAir()) {
            return false;
        }
        String n = above.material().name();
        if (n.endsWith("_SLAB")) {
            return above.slab() == Slab.Type.BOTTOM || above.slab() == Slab.Type.DOUBLE || above.slab() == null;
        }
        if (n.endsWith("_STAIRS")) {
            return above.half() != Bisected.Half.TOP;
        }
        if (isChain(n) || n.equals("END_ROD") || n.endsWith("LIGHTNING_ROD")) {
            return above.axis() == null || above.axis() == Axis.Y || above.facing() == BlockFace.UP
                    || above.facing() == BlockFace.DOWN || above.facing() == null;
        }
        return MapBlocks.isFullCube(above) || MapBlocks.family(above.material()) != MapBlocks.Family.NONE;
    }

    static boolean isChain(String n) {
        return n.equals("IRON_CHAIN") || n.endsWith("COPPER_CHAIN");
    }

    private static boolean isLog(String n) {
        return n.endsWith("_LOG") || n.endsWith("_WOOD") && !n.contains("PLANKS") || n.endsWith("_STEM")
                && !n.equals("MUSHROOM_STEM") || n.endsWith("_HYPHAE");
    }

    private static final Set<String> PLANTS = Set.of("DANDELION", "POPPY", "BLUE_ORCHID", "ALLIUM", "AZURE_BLUET",
            "OXEYE_DAISY", "CORNFLOWER", "LILY_OF_THE_VALLEY", "WITHER_ROSE", "TORCHFLOWER", "SUNFLOWER", "LILAC",
            "ROSE_BUSH", "PEONY", "PITCHER_PLANT", "SHORT_GRASS", "TALL_GRASS", "FERN", "LARGE_FERN", "DEAD_BUSH",
            "BUSH", "FIREFLY_BUSH", "AZALEA", "FLOWERING_AZALEA", "SWEET_BERRY_BUSH", "PINK_PETALS", "WILDFLOWERS",
            "OPEN_EYEBLOSSOM", "CLOSED_EYEBLOSSOM", "SHORT_DRY_GRASS", "TALL_DRY_GRASS", "SUGAR_CANE", "CACTUS",
            "BAMBOO", "LEAF_LITTER", "CACTUS_FLOWER");

    private static boolean isPlant(String n) {
        return PLANTS.contains(n) || n.endsWith("_TULIP") && !n.startsWith("POTTED_") || n.endsWith("_SAPLING") && !n.equals("BAMBOO_SAPLING");
    }

    private static boolean isTallPlant(String n) {
        return n.equals("SUNFLOWER") || n.equals("LILAC") || n.equals("ROSE_BUSH") || n.equals("PEONY")
                || n.equals("TALL_GRASS") || n.equals("LARGE_FERN") || n.equals("PITCHER_PLANT")
;
    }

    private static boolean isSoil(Material below, String plant) {
        String b = below.name();
        boolean dirt = b.equals("GRASS_BLOCK") || b.equals("DIRT") || b.equals("COARSE_DIRT") || b.equals("PODZOL")
                || b.equals("ROOTED_DIRT") || b.equals("MOSS_BLOCK") || b.equals("PALE_MOSS_BLOCK") || b.equals("MUD")
                || b.equals("MUDDY_MANGROVE_ROOTS") || b.equals("MYCELIUM") || b.equals("FARMLAND");
        boolean sandy = b.equals("SAND") || b.equals("RED_SAND") || b.endsWith("TERRACOTTA") || b.equals("SUSPICIOUS_SAND");
        return switch (plant) {
            case "DEAD_BUSH", "SHORT_DRY_GRASS", "TALL_DRY_GRASS" -> dirt || sandy;
            case "CACTUS" -> b.equals("SAND") || b.equals("RED_SAND") || b.equals("CACTUS");
            case "SUGAR_CANE" -> dirt || sandy || b.equals("SUGAR_CANE");
            case "BAMBOO" -> dirt || sandy || b.equals("GRAVEL") || b.equals("BAMBOO");
            case "LEAF_LITTER", "PINK_PETALS", "WILDFLOWERS" -> MapBlocks.isFullCubeMaterial(below);
            case "AZALEA", "FLOWERING_AZALEA" -> dirt || b.equals("CLAY");
            default -> dirt;
        };
    }

    // ------------------------------------------------------------------ bookkeeping

    private static final int[][] SIDES6 = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private void add(String kind, int x, int y, int z, @Nullable BlockSpec spec) {
        counts.merge(kind, 1, Integer::sum);
        List<String> examples = findings.computeIfAbsent(kind, k -> new ArrayList<>());
        if (examples.size() < EXAMPLES) {
            examples.add((spec == null ? "?" : spec.name()) + "@" + x + "," + y + "," + z + "[" + zoneOf.apply(x, z)
                    + "]");
        }
    }

    private static long key(int x, int y, int z) {
        return ((long) (x + 4096) << 32) | ((long) (z + 4096) << 12) | (y + 2048);
    }

    private static int[] unkey(long key) {
        int y = (int) (key & 0xFFF) - 2048;
        int z = (int) ((key >>> 12) & 0xFFFFF) - 4096;
        int x = (int) (key >>> 32) - 4096;
        return new int[] {x, y, z};
    }
}
