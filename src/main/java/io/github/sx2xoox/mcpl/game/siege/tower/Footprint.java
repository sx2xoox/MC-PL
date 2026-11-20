package io.github.sx2xoox.mcpl.game.siege.tower;

import java.util.ArrayList;
import java.util.List;

/**
 * The solid shape of a 공성전 building ({@link TowerLook#footprint}): square layers of blocks around the base that bodies
 * may not pass (the structure fills them with temporary barrier blocks), one shape while it stands and one for its
 * ruin. Pure geometry in blocks relative to the base (feet level, x/z centre); the building's facing does not matter,
 * every layer is symmetric enough.
 *
 * @param standing the layers while the building stands
 * @param ruin     the layers of its ruin (empty: nothing left in the way)
 */
public record Footprint(List<Layer> standing, List<Layer> ruin) {

    /** No collision at all. */
    public static final Footprint NONE = new Footprint(List.of(), List.of());

    /**
     * One square layer: blocks whose centres lie within {@code half} of ({@code x}, {@code z}) on both axes, from
     * {@code bottom} up to {@code top} (blocks above the base, the top exclusive).
     */
    public record Layer(double x, double z, double half, double bottom, double top) {

        public static Layer centred(double half, double bottom, double top) {
            return new Layer(0, 0, half, bottom, top);
        }
    }

    public Footprint {
        standing = List.copyOf(standing);
        ruin = List.copyOf(ruin);
    }

    /** The layers of the building now. */
    public List<Layer> layers(boolean ruined) {
        return ruined ? ruin : standing;
    }

    /**
     * Half the width of the solid square at the ground (how far the building reaches from its base on x and z): the
     * widest layer that starts at the ground, 0 for none.
     */
    public double halfWidth(boolean ruined) {
        double out = 0;
        for (Layer layer : layers(ruined)) {
            if (layer.bottom() <= 0) {
                out = Math.max(out, Math.max(Math.abs(layer.x()), Math.abs(layer.z())) + outerHalf(layer.half()));
            }
        }
        return out;
    }

    /**
     * This shape cut down to fit {@code radius} (a map's {@code Structure#radius}, the room it keeps clear round the
     * base): a layer reaching further is narrowed (gone when nothing is left), so walls never trap a spawn or a lane a
     * small map put close to its building.
     */
    public Footprint within(double radius) {
        return new Footprint(fit(standing, radius), fit(ruin, radius));
    }

    private static List<Layer> fit(List<Layer> layers, double radius) {
        List<Layer> out = new ArrayList<>();
        for (Layer layer : layers) {
            double offset = Math.max(Math.abs(layer.x()), Math.abs(layer.z()));
            double half = Math.min(layer.half(), radius - 0.5 - offset);
            if (half >= 0) {
                out.add(new Layer(layer.x(), layer.z(), half, layer.bottom(), layer.top()));
            }
        }
        return out;
    }

    /** The highest top of the layers (0 for none). */
    public double height(boolean ruined) {
        double out = 0;
        for (Layer layer : layers(ruined)) {
            out = Math.max(out, layer.top());
        }
        return out;
    }

    /**
     * How far the blocks of a layer of {@code half} reach from its centre, for a centre in the middle of a block (the
     * usual base): the blocks whose centres lie within {@code half}, to their outer faces.
     */
    static double outerHalf(double half) {
        return Math.floor(half + 1.0E-9) + 0.5;
    }

    /**
     * The blocks of the layers at a base: {x, y, z} block coordinates. A block is in a layer when its centre lies
     * within the layer's half on x and z (and its bottom within the layer's heights).
     */
    public static List<int[]> blocks(List<Layer> layers, double baseX, int baseY, double baseZ) {
        List<int[]> out = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (Layer layer : layers) {
            double cx = baseX + layer.x();
            double cz = baseZ + layer.z();
            int x0 = (int) Math.ceil(cx - layer.half() - 0.5 - 1.0E-9);
            int x1 = (int) Math.floor(cx + layer.half() - 0.5 + 1.0E-9);
            int z0 = (int) Math.ceil(cz - layer.half() - 0.5 - 1.0E-9);
            int z1 = (int) Math.floor(cz + layer.half() - 0.5 + 1.0E-9);
            int y0 = baseY + (int) Math.floor(layer.bottom());
            int y1 = baseY + (int) Math.ceil(layer.top()) - 1;
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    for (int z = z0; z <= z1; z++) {
                        if (seen.add(key(x, y, z))) {
                            out.add(new int[] {x, y, z});
                        }
                    }
                }
            }
        }
        return out;
    }

    /** A block position packed into one long (for sets of blocks). */
    public static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
    }

    // ------------------------------------------------------------------ keeping bodies out (pure, x/z only)

    /**
     * The point ({@code x}, {@code z}) moved out of the boxes: to the nearest side of a box it is in, a hair past it
     * (a few passes, for boxes that touch); itself when it is clear.
     */
    public static double[] clear(List<Box> boxes, double x, double z) {
        double ox = x;
        double oz = z;
        for (int pass = 0; pass < 3; pass++) {
            boolean moved = false;
            for (Box box : boxes) {
                if (box.contains(ox, oz)) {
                    double[] edge = box.pushOut(ox, oz);
                    boolean alongX = edge[0] != ox;
                    ox = alongX ? edge[0] + Math.signum(edge[0] - box.centreX()) * 0.05 : edge[0];
                    oz = alongX ? edge[1] : edge[1] + Math.signum(edge[1] - box.centreZ()) * 0.05;
                    moved = true;
                }
            }
            if (!moved) {
                break;
            }
        }
        return new double[] {ox, oz};
    }

    /**
     * A path ({x, z} points) that goes round the boxes instead of through them: where it runs into a box, the part
     * inside is replaced by the box's edge from where it enters to where it leaves, along the shorter side (past the
     * corners), so every point and every segment of the result stays out of the boxes (on their edges at most). A path
     * that starts (ends) inside a box starts (ends) where it leaves (enters) it. A lane through its towers (their
     * walls grown by a body's half width and some room) so becomes one that walks round them.
     */
    public static List<double[]> detour(List<double[]> path, List<Box> boxes) {
        List<double[]> out = new ArrayList<>();
        for (double[] point : path) {
            out.add(new double[] {point[0], point[1]});
        }
        for (Box box : boxes) {
            out = round(out, box);
        }
        return out;
    }

    private static List<double[]> round(List<double[]> path, Box box) {
        int n = path.size();
        int firstSegment = -1;
        int lastSegment = -1;
        double firstT = 0;
        double lastT = 0;
        for (int i = 0; i + 1 < n; i++) {
            double[] a = path.get(i);
            double[] b = path.get(i + 1);
            double[] inside = box.clip(a[0], a[1], b[0], b[1]);
            if (inside != null) {
                if (firstSegment < 0) {
                    firstSegment = i;
                    firstT = inside[0];
                }
                lastSegment = i;
                lastT = inside[1];
            }
        }
        if (firstSegment < 0) {
            return path;
        }
        double[] entry = lerp(path.get(firstSegment), path.get(firstSegment + 1), firstT);
        double[] exit = lerp(path.get(lastSegment), path.get(lastSegment + 1), lastT);
        boolean startsInside = box.contains(path.getFirst()[0], path.getFirst()[1]);
        boolean endsInside = box.contains(path.getLast()[0], path.getLast()[1]);
        List<double[]> out = new ArrayList<>();
        if (startsInside && endsInside) {
            return path;
        }
        if (startsInside) {
            add(out, exit);
        } else {
            for (int i = 0; i <= firstSegment; i++) {
                add(out, path.get(i));
            }
            if (endsInside) {
                add(out, entry);
                return out;
            }
            // pulled taut round the box from the last point before it to the first after it, the shorter way
            double[] before = path.get(firstSegment);
            double[] after = path.get(lastSegment + 1);
            List<double[]> one = box.taut(before, after, box.corners(entry, exit, true));
            List<double[]> other = box.taut(before, after, box.corners(entry, exit, false));
            for (double[] corner : length(before, one, after) <= length(before, other, after) + 1.0E-9 ? one : other) {
                add(out, corner);
            }
        }
        for (int i = lastSegment + 1; i < n; i++) {
            add(out, path.get(i));
        }
        return out;
    }

    private static double length(double[] from, List<double[]> via, double[] to) {
        double out = 0;
        double[] at = from;
        for (double[] point : via) {
            out += Math.hypot(point[0] - at[0], point[1] - at[1]);
            at = point;
        }
        return out + Math.hypot(to[0] - at[0], to[1] - at[1]);
    }

    private static void add(List<double[]> out, double[] point) {
        if (out.isEmpty() || Math.hypot(out.getLast()[0] - point[0], out.getLast()[1] - point[1]) > 1.0E-6) {
            out.add(point);
        }
    }

    private static double[] lerp(double[] a, double[] b, double t) {
        return new double[] {a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t};
    }

    /**
     * The solid square of a standing building on the ground as an x/z box (world coordinates), {@code margin} wider on
     * each side.
     */
    public record Box(double minX, double minZ, double maxX, double maxZ) {

        public boolean contains(double x, double z) {
            return x > minX && x < maxX && z > minZ && z < maxZ;
        }

        public Box grow(double by) {
            return new Box(minX - by, minZ - by, maxX + by, maxZ + by);
        }

        public double centreX() {
            return (minX + maxX) / 2;
        }

        public double centreZ() {
            return (minZ + maxZ) / 2;
        }

        /** Horizontal distance from a point to the box (0 inside). */
        public double distance(double x, double z) {
            double dx = Math.max(0, Math.max(minX - x, x - maxX));
            double dz = Math.max(0, Math.max(minZ - z, z - maxZ));
            return Math.sqrt(dx * dx + dz * dz);
        }

        /** The nearest point on the box's edge for a point inside it (the point itself when outside). */
        public double[] pushOut(double x, double z) {
            if (!contains(x, z)) {
                return new double[] {x, z};
            }
            double left = x - minX;
            double right = maxX - x;
            double back = z - minZ;
            double front = maxZ - z;
            double least = Math.min(Math.min(left, right), Math.min(back, front));
            if (least == left) {
                return new double[] {minX, z};
            }
            if (least == right) {
                return new double[] {maxX, z};
            }
            return least == back ? new double[] {x, minZ} : new double[] {x, maxZ};
        }

        /**
         * One step of at most {@code step} blocks from ({@code x}, {@code z}) towards ({@code toX}, {@code toZ}) that
         * never enters the box: straight when clear, else sliding along the box's side (round its nearer corner
         * towards the goal), so a body walking at a building goes round it.
         */
        public double[] step(double x, double z, double toX, double toZ, double step) {
            double dx = toX - x;
            double dz = toZ - z;
            double length = Math.sqrt(dx * dx + dz * dz);
            if (length < 1.0E-9) {
                return new double[] {x, z};
            }
            double move = Math.min(step, length);
            double nx = x + dx / length * move;
            double nz = z + dz / length * move;
            if (!contains(nx, nz) && !crosses(x, z, nx, nz)) {
                return new double[] {nx, nz};
            }
            if (contains(x, z)) {
                return pushOut(x, z);
            }
            // blocked: slide along the side the body is at (past a z side: along x, else along z), towards the
            // goal; sliding along a side never enters the box
            // round the corner with the shorter way to the goal (stable: walking towards it only shortens it)
            if (z <= minZ || z >= maxZ) {
                double viaMin = Math.abs(x - minX) + Math.abs(toX - minX);
                double viaMax = Math.abs(maxX - x) + Math.abs(toX - maxX);
                return new double[] {x + (viaMin < viaMax ? -1 : 1) * move, z};
            }
            double viaMin = Math.abs(z - minZ) + Math.abs(toZ - minZ);
            double viaMax = Math.abs(maxZ - z) + Math.abs(toZ - maxZ);
            double towards = viaMin < viaMax ? -1 : 1;
            return new double[] {x, z + towards * move};
        }

        /** Whether the segment from (x0, z0) to (x1, z1) passes through the box. */
        public boolean crosses(double x0, double z0, double x1, double z1) {
            return clip(x0, z0, x1, z1) != null;
        }

        /**
         * The part of the segment from (x0, z0) to (x1, z1) inside the box as {t from, t to} (0 = the start, 1 = the
         * end), or null when it does not pass through the box (running along an edge does not).
         */
        public double @org.jspecify.annotations.Nullable [] clip(double x0, double z0, double x1, double z1) {
            double t0 = 0;
            double t1 = 1;
            double[] p = {-(x1 - x0), x1 - x0, -(z1 - z0), z1 - z0};
            double[] q = {x0 - minX, maxX - x0, z0 - minZ, maxZ - z0};
            for (int i = 0; i < 4; i++) {
                if (Math.abs(p[i]) < 1.0E-12) {
                    if (q[i] <= 0) {
                        return null;
                    }
                } else {
                    double r = q[i] / p[i];
                    if (p[i] < 0) {
                        t0 = Math.max(t0, r);
                    } else {
                        t1 = Math.min(t1, r);
                    }
                    if (t0 >= t1) {
                        return null;
                    }
                }
            }
            return new double[] {t0, t1};
        }

        /**
         * The corners passed walking the box's edge from {@code from} to {@code to} (both on the edge), in walking
         * order: {@code forward} = the way along +x from the min/min corner (then +z, -x, -z), else the other way.
         */
        public List<double[]> corners(double[] from, double[] to, boolean forward) {
            double w = maxX - minX;
            double h = maxZ - minZ;
            double perimeter = 2 * (w + h);
            double[][] corners = {{minX, minZ}, {maxX, minZ}, {maxX, maxZ}, {minX, maxZ}};
            double[] at = {0, w, w + h, 2 * w + h};
            double start = around(from);
            double end = around(to);
            double length = forward ? ((end - start) % perimeter + perimeter) % perimeter
                    : ((start - end) % perimeter + perimeter) % perimeter;
            List<double[]> passed = new ArrayList<>();
            List<Double> distance = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                double d = forward ? ((at[i] - start) % perimeter + perimeter) % perimeter
                        : ((start - at[i]) % perimeter + perimeter) % perimeter;
                if (d > 1.0E-9 && d < length - 1.0E-9) {
                    passed.add(corners[i]);
                    distance.add(d);
                }
            }
            List<double[]> out = new ArrayList<>();
            while (!passed.isEmpty()) {
                int next = 0;
                for (int i = 1; i < passed.size(); i++) {
                    if (distance.get(i) < distance.get(next)) {
                        next = i;
                    }
                }
                out.add(new double[] {passed.get(next)[0], passed.get(next)[1]});
                passed.remove(next);
                distance.remove(next);
            }
            return out;
        }

        /**
         * The {@code corners} (in walking order, from {@code from} to {@code to} round the box) a path from
         * {@code from} to {@code to} needs so that no segment of it passes through the box: each segment goes as far
         * along the corners as it can see.
         */
        public List<double[]> taut(double[] from, double[] to, List<double[]> corners) {
            List<double[]> candidates = new ArrayList<>(corners);
            candidates.add(to);
            List<double[]> out = new ArrayList<>();
            double[] at = from;
            int next = 0;
            while (next < candidates.size()) {
                int far = next;
                for (int j = candidates.size() - 1; j > next; j--) {
                    double[] c = candidates.get(j);
                    if (clip(at[0], at[1], c[0], c[1]) == null) {
                        far = j;
                        break;
                    }
                }
                if (far == candidates.size() - 1) {
                    break;
                }
                at = candidates.get(far);
                out.add(at);
                next = far + 1;
            }
            return out;
        }

        /** How far round the edge (from the min/min corner along +x, +z, -x, -z) the edge point nearest a point is. */
        private double around(double[] point) {
            double w = maxX - minX;
            double h = maxZ - minZ;
            double x = Math.clamp(point[0], minX, maxX);
            double z = Math.clamp(point[1], minZ, maxZ);
            double toMinZ = Math.abs(point[1] - minZ);
            double toMaxX = Math.abs(point[0] - maxX);
            double toMaxZ = Math.abs(point[1] - maxZ);
            double toMinX = Math.abs(point[0] - minX);
            double least = Math.min(Math.min(toMinZ, toMaxX), Math.min(toMaxZ, toMinX));
            if (least == toMinZ) {
                return x - minX;
            }
            if (least == toMaxX) {
                return w + (z - minZ);
            }
            if (least == toMaxZ) {
                return w + h + (maxX - x);
            }
            return 2 * w + h + (maxZ - z);
        }
    }
}
