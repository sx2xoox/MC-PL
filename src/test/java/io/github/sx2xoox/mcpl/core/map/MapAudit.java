package io.github.sx2xoox.mcpl.core.map;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import org.bukkit.Material;
import org.jspecify.annotations.Nullable;

/**
 * Checks a finished map canvas the way a player would experience it: where you can stand, where you can walk to from
 * the center and back, and blocks that would misbehave on a server (floating sand, spilling water, lava, fire).
 *
 * <p>Movement model (close to vanilla): a player stands on a standable block with two passable blocks above. From
 * there it can walk to a neighbouring column one block up (a jump, needing headroom), level, or drop up to
 * {@link #MAX_DROP} blocks. Ladders, vines and scaffolding climb; water swims (up and down). Wooden doors and gates
 * open.
 */
public final class MapAudit {

    /** Highest drop that counts as walkable (5 blocks deals 2 fall damage). */
    public static final int MAX_DROP = 5;

    private final MapCanvas canvas;
    private final int playRadius;
    private final Map<Long, Node> nodes = new HashMap<>();
    private final List<Node> all = new ArrayList<>();
    private final BiFunction<Integer, Integer, String> zoneOf;

    /** One place a player can stand: feet at {@code y}. */
    public record Node(int x, int y, int z) {
    }

    /** A group of nodes cut off from the center (or that cannot get back to it). */
    public record Component(int size, Node sample, String zone) {
    }

    public record Report(int standable, int reachable, List<Component> unreachable, int unreachableTotal,
                         List<Component> stranded, int strandedTotal, List<String> problems) {

        public double unreachableShare() {
            return standable == 0 ? 0 : (double) unreachableTotal / standable;
        }

        public double strandedShare() {
            return standable == 0 ? 0 : (double) strandedTotal / standable;
        }

        public String summary() {
            StringBuilder out = new StringBuilder();
            out.append(String.format("standable %d, reachable %d, unreachable %d (%.2f%%), stranded %d (%.2f%%)%n",
                    standable, reachable, unreachableTotal, unreachableShare() * 100, strandedTotal,
                    strandedShare() * 100));
            for (Component c : unreachable) {
                out.append("  unreachable ").append(c.size()).append(" at ").append(c.sample()).append(" [")
                        .append(c.zone()).append("]\n");
            }
            for (Component c : stranded) {
                out.append("  stranded ").append(c.size()).append(" at ").append(c.sample()).append(" [")
                        .append(c.zone()).append("]\n");
            }
            for (String problem : problems) {
                out.append("  problem: ").append(problem).append('\n');
            }
            return out.toString();
        }
    }

    public MapAudit(MapCanvas canvas, int playRadius, BiFunction<Integer, Integer, String> zoneOf) {
        this.canvas = canvas;
        this.playRadius = playRadius;
        this.zoneOf = zoneOf;
    }

    /** Runs every check; {@code startX, startZ} is where players are assumed to start (the center). */
    public Report run(int startX, int startZ) {
        collectNodes();
        Node start = highestNode(startX, startZ);
        List<String> problems = blockProblems();
        if (start == null) {
            problems.add("no place to stand at the start column " + startX + "," + startZ);
            return new Report(all.size(), 0, List.of(), all.size(), List.of(), 0, problems);
        }
        Set<Node> reachable = flood(start, false);
        Set<Node> canReturn = flood(start, true);
        Set<Node> unreachable = new HashSet<>(all);
        unreachable.removeAll(reachable);
        Set<Node> stranded = new HashSet<>(reachable);
        stranded.removeAll(canReturn);
        return new Report(all.size(), reachable.size(), components(unreachable), unreachable.size(),
                components(stranded), stranded.size(), problems);
    }

    /** Every place to stand inside the play radius. */
    public List<Node> nodes() {
        if (all.isEmpty()) {
            collectNodes();
        }
        return all;
    }

    // ------------------------------------------------------------------ nodes

    private void collectNodes() {
        nodes.clear();
        all.clear();
        int r = playRadius;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                if ((long) x * x + (long) z * z > (long) r * r) {
                    continue;
                }
                for (int y = canvas.minY() + 1; y <= canvas.maxY(); y++) {
                    if (isStandPlace(x, y, z)) {
                        Node node = new Node(x, y, z);
                        nodes.put(key(x, y, z), node);
                        all.add(node);
                    }
                }
            }
        }
    }

    private boolean isStandPlace(int x, int y, int z) {
        if (!passable(x, y, z) || !passable(x, y + 1, z)) {
            return false;
        }
        BlockSpec below = canvas.get(x, y - 1, z);
        if (below != null && MapBlocks.isStandable(below)) {
            return true;
        }
        return isWater(x, y, z) || isClimbable(x, y, z);
    }

    private @Nullable Node highestNode(int x, int z) {
        for (int y = canvas.maxY(); y > canvas.minY(); y--) {
            Node node = nodes.get(key(x, y, z));
            if (node != null) {
                return node;
            }
        }
        return null;
    }

    private boolean passable(int x, int y, int z) {
        BlockSpec spec = canvas.get(x, y, z);
        if (spec == null) {
            return true;
        }
        if (spec.material() == Material.LAVA) {
            return false;
        }
        String name = spec.material().name();
        if (name.endsWith("_DOOR") && spec.material() != Material.IRON_DOOR || name.endsWith("_FENCE_GATE")) {
            return true;
        }
        return MapBlocks.isPassable(spec);
    }

    private boolean isWater(int x, int y, int z) {
        BlockSpec spec = canvas.get(x, y, z);
        return spec != null && (spec.material() == Material.WATER || spec.waterlogged() == Boolean.TRUE);
    }

    private boolean isClimbable(int x, int y, int z) {
        BlockSpec spec = canvas.get(x, y, z);
        if (spec == null) {
            return false;
        }
        String name = spec.material().name();
        return name.equals("LADDER") || name.contains("VINE") || name.equals("SCAFFOLDING");
    }

    // ------------------------------------------------------------------ movement

    /** Nodes reachable from {@code start} ({@code reverse}: nodes from which {@code start} is reachable). */
    private Set<Node> flood(Node start, boolean reverse) {
        Set<Node> seen = new HashSet<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        seen.add(start);
        queue.add(start);
        Map<Node, List<Node>> incoming = reverse ? incoming() : null;
        while (!queue.isEmpty()) {
            Node node = queue.poll();
            List<Node> next = reverse ? incoming.getOrDefault(node, List.of()) : moves(node);
            for (Node to : next) {
                if (seen.add(to)) {
                    queue.add(to);
                }
            }
        }
        return seen;
    }

    private Map<Node, List<Node>> incoming() {
        Map<Node, List<Node>> incoming = new HashMap<>();
        for (Node from : all) {
            for (Node to : moves(from)) {
                incoming.computeIfAbsent(to, k -> new ArrayList<>()).add(from);
            }
        }
        return incoming;
    }

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** Where a player standing at {@code node} can get in one step. */
    List<Node> moves(Node node) {
        List<Node> out = new ArrayList<>(6);
        int x = node.x();
        int y = node.y();
        int z = node.z();
        boolean swimming = isWater(x, y, z);
        boolean climbing = isClimbable(x, y, z);
        // Up: climb, swim, or nothing.
        if (swimming || climbing) {
            Node up = nodes.get(key(x, y + 1, z));
            if (up != null) {
                out.add(up);
            }
        }
        // Down within the column (ladders, water, or simply falling to the next place below).
        for (int dy = 1; dy <= MAX_DROP + 1; dy++) {
            Node down = nodes.get(key(x, y - dy, z));
            if (down != null) {
                if (dy <= MAX_DROP || swimming || climbing) {
                    out.add(down);
                }
                break;
            }
            if (!passable(x, y - dy, z)) {
                break;
            }
        }
        boolean headroom = passable(x, y + 2, z);
        for (int[] side : SIDES) {
            int nx = x + side[0];
            int nz = z + side[1];
            // A step or jump up: the target is one higher, the jump needs headroom here.
            Node upStep = nodes.get(key(nx, y + 1, nz));
            if (upStep != null && (headroom || swimming || climbing)) {
                out.add(upStep);
            }
            Node level = nodes.get(key(nx, y, nz));
            if (level != null) {
                out.add(level);
                continue;
            }
            // Walk off an edge: the body passes into the neighbour column and falls to the first place below.
            if (!passable(nx, y, nz) || !passable(nx, y + 1, nz)) {
                continue;
            }
            for (int dy = 1; dy <= MAX_DROP; dy++) {
                Node down = nodes.get(key(nx, y - dy, nz));
                if (down != null) {
                    out.add(down);
                    break;
                }
                if (!passable(nx, y - dy, nz)) {
                    break;
                }
            }
        }
        return out;
    }

    private List<Component> components(Set<Node> set) {
        List<Component> out = new ArrayList<>();
        Set<Node> left = new HashSet<>(set);
        while (!left.isEmpty()) {
            Node seed = left.iterator().next();
            ArrayDeque<Node> queue = new ArrayDeque<>();
            queue.add(seed);
            left.remove(seed);
            int size = 0;
            while (!queue.isEmpty()) {
                Node node = queue.poll();
                size++;
                for (Node to : moves(node)) {
                    if (left.remove(to)) {
                        queue.add(to);
                    }
                }
                // undirected: also pull in neighbours that move into this node
                for (int[] side : SIDES) {
                    for (int dy = -MAX_DROP; dy <= 1; dy++) {
                        Node other = nodes.get(key(node.x() + side[0], node.y() + dy, node.z() + side[1]));
                        if (other != null && left.contains(other) && moves(other).contains(node)) {
                            left.remove(other);
                            queue.add(other);
                        }
                    }
                }
            }
            out.add(new Component(size, seed, zoneOf.apply(seed.x(), seed.z())));
        }
        out.sort((a, b) -> Integer.compare(b.size(), a.size()));
        return out;
    }

    // ------------------------------------------------------------------ blocks

    private List<String> blockProblems() {
        List<String> problems = new ArrayList<>();
        int[] counts = new int[4];
        canvas.forEach((x, y, z, spec) -> {
            Material material = spec.material();
            if (MapBlocks.hasGravity(material) && !supports(canvas.get(x, y - 1, z))) {
                if (counts[0]++ < 5) {
                    problems.add("floating " + spec.name() + " at " + x + "," + y + "," + z + " ["
                            + zoneOf.apply(x, z) + "]");
                }
            }
            if (material == Material.WATER && spills(x, y, z)) {
                if (counts[1]++ < 5) {
                    problems.add("water can spill at " + x + "," + y + "," + z + " [" + zoneOf.apply(x, z) + "]");
                }
            }
            if (material == Material.LAVA || material == Material.FIRE || material == Material.SOUL_FIRE) {
                if (counts[2]++ < 5) {
                    problems.add(spec.name() + " at " + x + "," + y + "," + z + " [" + zoneOf.apply(x, z) + "]");
                }
            }
            if (material == Material.BARRIER || material == Material.LIGHT || material.name().contains("COMMAND")
                    || material == Material.STRUCTURE_BLOCK || material == Material.JIGSAW) {
                if (counts[3]++ < 5) {
                    problems.add("technical block " + spec.name() + " at " + x + "," + y + "," + z);
                }
            }
        });
        if (counts[0] > 5) {
            problems.add("... " + (counts[0] - 5) + " more floating blocks");
        }
        if (counts[1] > 5) {
            problems.add("... " + (counts[1] - 5) + " more spilling water blocks");
        }
        return problems;
    }

    private static boolean supports(@Nullable BlockSpec below) {
        return below != null && !below.isAir() && !MapBlocks.isFluid(below.material());
    }

    private boolean spills(int x, int y, int z) {
        for (int[] side : SIDES) {
            if (leaks(canvas.get(x + side[0], y, z + side[1]))) {
                return true;
            }
        }
        return leaks(canvas.get(x, y - 1, z));
    }

    private static boolean leaks(@Nullable BlockSpec spec) {
        if (spec == null || spec.isAir()) {
            return true;
        }
        if (spec.material() == Material.WATER || spec.waterlogged() == Boolean.TRUE) {
            return false;
        }
        return MapBlocks.isPassable(spec);
    }

    private static long key(int x, int y, int z) {
        return ((long) (x + 4096) << 32) | ((long) (z + 4096) << 12) | (y + 2048);
    }
}
