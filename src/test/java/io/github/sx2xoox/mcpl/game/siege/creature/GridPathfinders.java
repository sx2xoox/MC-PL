package io.github.sx2xoox.mcpl.game.siege.creature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.WeakHashMap;

import com.destroystokyo.paper.entity.Pathfinder;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * A stand-in for the server's pathfinder on a test server (MockBukkit has none), close to vanilla's ground navigation
 * on a flat world: walk nodes on the block grid, a body wider than a block taking {@code floor(width + 1)} blocks a
 * node (vanilla's {@code WalkNodeEvaluator}), diagonal steps only past two open sides, a path to the closest node it
 * can reach when the goal is blocked (a partial path), and bodies that follow the nodes' centres and collide with
 * solid blocks (they slide along a wall and stop at a corner, as vanilla bodies rub at one). Solid = a solid block at
 * feet or head height ({@code Material#isSolid}: the 공성전 barrier walls).
 *
 * <p>{@link #tick()} moves every following body once (call it once a server tick).
 */
public final class GridPathfinders {

    /** How far round its start a path is looked for (blocks, vanilla's follow range order). */
    private static final int RANGE = 40;

    private final Map<Mob, Grid> pathfinders = new WeakHashMap<>();

    /** The pathfinder of {@code mob}. */
    public Pathfinder of(Mob mob) {
        return pathfinders.computeIfAbsent(mob, Grid::new);
    }

    /** Moves every body along its path by one tick. */
    public void tick() {
        for (Grid grid : new ArrayList<>(pathfinders.values())) {
            grid.follow();
        }
    }

    private static boolean solid(World world, int x, int y, int z) {
        return world.getBlockAt(x, y, z).getType().isSolid() || world.getBlockAt(x, y + 1, z).getType().isSolid();
    }

    /** Blocks a node takes on each axis (vanilla: {@code floor(width + 1)}). */
    private static int nodeSize(Mob mob) {
        return (int) Math.floor(mob.getWidth() + 1);
    }

    private static final class Result implements Pathfinder.PathResult {
        private final List<Location> points;
        private final boolean reaches;
        private int next;

        private Result(List<Location> points, boolean reaches) {
            this.points = points;
            this.reaches = reaches;
        }

        @Override
        public List<Location> getPoints() {
            return points;
        }

        @Override
        public int getNextPointIndex() {
            return next;
        }

        @Override
        public @Nullable Location getNextPoint() {
            return next < points.size() ? points.get(next) : null;
        }

        @Override
        public @Nullable Location getFinalPoint() {
            return points.isEmpty() ? null : points.getLast();
        }

        @Override
        public boolean canReachFinalPoint() {
            return reaches;
        }
    }

    private record Node(int x, int z) {
    }

    private static final class Grid implements Pathfinder {
        private final Mob mob;
        private @Nullable Result path;
        private double speed;

        private Grid(Mob mob) {
            this.mob = mob;
        }

        @Override
        public Mob getEntity() {
            return mob;
        }

        @Override
        public void stopPathfinding() {
            path = null;
        }

        @Override
        public boolean hasPath() {
            return path != null;
        }

        @Override
        public @Nullable PathResult getCurrentPath() {
            return path;
        }

        @Override
        public @Nullable PathResult findPath(Location loc, int reachRange) {
            return find(loc);
        }

        @Override
        public @Nullable PathResult findPath(Entity target, int reachRange) {
            return find(target.getLocation());
        }

        @Override
        public boolean moveTo(PathResult result, double speed) {
            if (!(result instanceof Result own) || own.points.isEmpty()) {
                return false;
            }
            this.path = own;
            this.speed = speed;
            return true;
        }

        @Override
        public boolean canOpenDoors() {
            return false;
        }

        @Override
        public void setCanOpenDoors(boolean canOpenDoors) {
        }

        @Override
        public boolean canPassDoors() {
            return false;
        }

        @Override
        public void setCanPassDoors(boolean canPassDoors) {
        }

        @Override
        public boolean canFloat() {
            return false;
        }

        @Override
        public void setCanFloat(boolean canFloat) {
        }

        private boolean open(int x, int y, int z, int n) {
            World world = mob.getWorld();
            for (int dx = 0; dx < n; dx++) {
                for (int dz = 0; dz < n; dz++) {
                    if (solid(world, x + dx, y, z + dz)) {
                        return false;
                    }
                }
            }
            return true;
        }

        /** The node whose centre is nearest {@code at}. */
        private Node nodeAt(Location at, int n) {
            return new Node((int) Math.floor(at.getX() - n / 2.0 + 0.5), (int) Math.floor(at.getZ() - n / 2.0 + 0.5));
        }

        private @Nullable Result find(Location goal) {
            int n = nodeSize(mob);
            int y = mob.getLocation().getBlockY();
            Node start = nodeAt(mob.getLocation(), n);
            Node end = nodeAt(goal, n);
            Map<Node, Node> from = new HashMap<>();
            Map<Node, Double> cost = new HashMap<>();
            PriorityQueue<Object[]> open = new PriorityQueue<>((a, b) -> Double.compare((double) a[1],
                    (double) b[1]));
            cost.put(start, 0.0);
            open.add(new Object[] {start, h(start, end)});
            Node best = start;
            double bestH = h(start, end);
            while (!open.isEmpty()) {
                Object[] top = open.poll();
                Node node = (Node) top[0];
                double g = cost.get(node);
                if ((double) top[1] > g + h(node, end) + 1.0E-9) {
                    continue;
                }
                double toGoal = h(node, end);
                if (toGoal < bestH) {
                    best = node;
                    bestH = toGoal;
                }
                if (node.equals(end)) {
                    break;
                }
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dz == 0) {
                            continue;
                        }
                        Node next = new Node(node.x() + dx, node.z() + dz);
                        if (Math.abs(next.x() - start.x()) > RANGE || Math.abs(next.z() - start.z()) > RANGE
                                || !open(next.x(), y, next.z(), n)) {
                            continue;
                        }
                        if (dx != 0 && dz != 0 && (!open(node.x() + dx, y, node.z(), n)
                                || !open(node.x(), y, node.z() + dz, n))) {
                            continue;
                        }
                        double step = dx != 0 && dz != 0 ? Math.sqrt(2) : 1;
                        Double known = cost.get(next);
                        if (known == null || g + step < known - 1.0E-9) {
                            cost.put(next, g + step);
                            from.put(next, node);
                            open.add(new Object[] {next, g + step + h(next, end)});
                        }
                    }
                }
            }
            List<Location> points = new ArrayList<>();
            for (Node at = best; at != null; at = from.get(at)) {
                points.add(new Location(mob.getWorld(), at.x() + n / 2.0, y, at.z() + n / 2.0));
            }
            Collections.reverse(points);
            return new Result(points, best.equals(end));
        }

        private static double h(Node a, Node b) {
            return Math.hypot(a.x() - b.x(), a.z() - b.z());
        }

        /** One tick along the path: towards the next node's centre, colliding with solid blocks. */
        private void follow() {
            Result result = path;
            if (result == null || !mob.isValid()) {
                return;
            }
            Location at = mob.getLocation();
            double width = mob.getWidth();
            double reach = width > 0.75 ? width / 2 : 0.75 - width / 2;
            while (result.next < result.points.size()) {
                Location point = result.points.get(result.next);
                if (Math.abs(point.getX() - at.getX()) < reach && Math.abs(point.getZ() - at.getZ()) < reach) {
                    result.next++;
                } else {
                    break;
                }
            }
            if (result.next >= result.points.size()) {
                path = null;
                return;
            }
            Location point = result.points.get(result.next);
            Vector delta = new Vector(point.getX() - at.getX(), 0, point.getZ() - at.getZ());
            double length = delta.length();
            double move = Math.min(length, CreatureMover.STEP_PER_TICK * speed);
            if (length < 1.0E-9) {
                return;
            }
            double nx = at.getX() + delta.getX() / length * move;
            double nz = at.getZ() + delta.getZ() / length * move;
            double half = width / 2;
            Location next = at.clone();
            if (fits(nx, nz, half, at.getBlockY())) {
                next.setX(nx);
                next.setZ(nz);
            } else if (fits(nx, at.getZ(), half, at.getBlockY())) {
                next.setX(nx);
            } else if (fits(at.getX(), nz, half, at.getBlockY())) {
                next.setZ(nz);
            } else {
                return; // rubbing at a corner
            }
            next.setDirection(delta);
            mob.teleport(next);
        }

        private boolean fits(double x, double z, double half, int y) {
            World world = mob.getWorld();
            for (int bx = (int) Math.floor(x - half + 1.0E-6); bx <= (int) Math.floor(x + half - 1.0E-6); bx++) {
                for (int bz = (int) Math.floor(z - half + 1.0E-6); bz <= (int) Math.floor(z + half - 1.0E-6); bz++) {
                    if (solid(world, bx, y, bz)) {
                        return false;
                    }
                }
            }
            return true;
        }
    }
}
