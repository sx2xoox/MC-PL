package io.github.sx2xoox.mcpl.core.map;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiPredicate;

import javax.imageio.ImageIO;

import org.bukkit.Material;

/**
 * Renders a map canvas to PNG images so its design can be looked at without a game client: a top-down map (colour of
 * the top block, shaded by height) and an isometric view (seen from the south-east, from above).
 *
 * <p>Colours approximate the blocks' looks (see {@link #color(Material)}).
 */
public final class MapPreview {

    private final MapCanvas canvas;

    /** Night look: dark sky, dim blocks, light sources (and what sits next to them) glow. */
    public static boolean night;

    public MapPreview(MapCanvas canvas) {
        this.canvas = canvas;
    }

    // ------------------------------------------------------------------ top-down

    /**
     * Top-down image of the columns in {@code [minX..maxX] x [minZ..maxZ]} that {@code include} accepts, {@code scale}
     * pixels per block. North is up. A circle of {@code ringRadius} (if > 0) is drawn around the map center.
     */
    public BufferedImage topDown(int minX, int minZ, int maxX, int maxZ, int scale,
                                 BiPredicate<Integer, Integer> include, int ringRadius) {
        int width = (maxX - minX + 1) * scale;
        int height = (maxZ - minZ + 1) * scale;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(night ? new Color(8, 8, 16) : new Color(18, 20, 28));
        g.fillRect(0, 0, width, height);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!include.test(x, z)) {
                    continue;
                }
                int top = topY(x, z);
                if (top < canvas.minY()) {
                    continue;
                }
                BlockSpec spec = canvas.get(x, top, z);
                Color base = color(spec.material());
                // Hill shading: brighter where the ground rises towards the north-west light.
                int west = topY(x - 1, z);
                int north = topY(x, z - 1);
                double shade = 1.0 + 0.09 * ((top - Math.max(west, canvas.minY())) + (top - Math.max(north,
                        canvas.minY())));
                shade = Math.max(0.55, Math.min(1.35, shade));
                // Height tint: higher blocks a little lighter.
                double height01 = (top - 60) / 40.0;
                Color c = shadeColor(base, (shade + Math.max(-0.15, Math.min(0.25, height01 * 0.25)))
                        * light(x, top, z, spec));
                g.setColor(c);
                g.fillRect((x - minX) * scale, (z - minZ) * scale, scale, scale);
            }
        }
        if (ringRadius > 0) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(255, 80, 80, 170));
            g.setStroke(new BasicStroke(Math.max(1, scale / 2f)));
            int cx = (0 - minX) * scale + scale / 2;
            int cz = (0 - minZ) * scale + scale / 2;
            g.drawOval(cx - ringRadius * scale, cz - ringRadius * scale, ringRadius * 2 * scale,
                    ringRadius * 2 * scale);
        }
        g.dispose();
        return image;
    }

    /** Highest non-air block of the column, or below minY. */
    public int topY(int x, int z) {
        for (int y = canvas.maxY(); y >= canvas.minY(); y--) {
            BlockSpec spec = canvas.get(x, y, z);
            if (spec != null && !spec.isAir()) {
                return y;
            }
        }
        return canvas.minY() - 1;
    }

    // ------------------------------------------------------------------ elevation

    /**
     * Street view looking east (+x) from {@code fromX}: for every column {@code z} and height, the first block met
     * within {@code depth} blocks, darker with distance; {@code scale} pixels per block. North is left.
     */
    public BufferedImage elevationEast(int fromX, int depth, int minZ, int maxZ, int minY, int maxY, int scale) {
        int width = (maxZ - minZ + 1) * scale;
        int height = (maxY - minY + 1) * scale;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(night ? new Color(12, 10, 26) : new Color(150, 190, 235));
        g.fillRect(0, 0, width, height);
        for (int z = minZ; z <= maxZ; z++) {
            for (int y = minY; y <= maxY; y++) {
                for (int x = fromX; x < fromX + depth; x++) {
                    BlockSpec spec = canvas.get(x, y, z);
                    if (spec == null || spec.isAir()) {
                        continue;
                    }
                    double fade = 1.0 - 0.6 * (x - fromX) / depth;
                    g.setColor(shadeColor(color(spec.material()), fade * light(x, y, z, spec)));
                    g.fillRect((z - minZ) * scale, (maxY - y) * scale, scale, scale);
                    break;
                }
            }
        }
        g.dispose();
        return image;
    }

    // ------------------------------------------------------------------ isometric

    /**
     * Isometric view of the columns {@code include} accepts inside the box, seen from the south-east and above:
     * {@code tile} pixels per block width. Only visible faces are drawn.
     */
    public BufferedImage isometric(int minX, int minZ, int maxX, int maxZ, int tile,
                                   BiPredicate<Integer, Integer> include) {
        int half = tile / 2;
        int quarter = Math.max(1, tile / 4);
        int blockHeight = half + quarter / 2 + 1;
        int spanX = maxX - minX + 1;
        int spanZ = maxZ - minZ + 1;
        int yRange = canvas.maxY() - canvas.minY() + 1;
        int width = (spanX + spanZ) * half + tile;
        int height = (spanX + spanZ) * quarter + yRange * blockHeight + tile;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(night ? new Color(12, 10, 26) : new Color(150, 190, 235));
        g.fillRect(0, 0, width, height);
        // Painter's order: from the back (low x+z) to the front, bottom up.
        List<int[]> blocks = new ArrayList<>();
        canvas.forEach((x, y, z, spec) -> {
            if (x < minX || x > maxX || z < minZ || z > maxZ || !include.test(x, z)) {
                return;
            }
            if (exposed(x, y, z)) {
                blocks.add(new int[] {x, y, z});
            }
        });
        blocks.sort((a, b) -> {
            int da = a[0] + a[2];
            int db = b[0] + b[2];
            if (da != db) {
                return Integer.compare(da, db);
            }
            if (a[1] != b[1]) {
                return Integer.compare(a[1], b[1]);
            }
            return Integer.compare(a[0], b[0]);
        });
        int originX = spanZ * half + half;
        int originY = yRange * blockHeight + half;
        for (int[] b : blocks) {
            int x = b[0];
            int y = b[1];
            int z = b[2];
            BlockSpec spec = canvas.get(x, y, z);
            Color base = shadeColor(color(spec.material()), light(x, y, z, spec));
            int lx = x - minX;
            int lz = z - minZ;
            int sx = originX + (lx - lz) * half;
            int sy = originY + (lx + lz) * quarter - (y - canvas.minY()) * blockHeight;
            Polygon topFace = new Polygon(new int[] {sx, sx + half, sx, sx - half},
                    new int[] {sy - quarter, sy, sy + quarter, sy}, 4);
            Polygon left = new Polygon(new int[] {sx - half, sx, sx, sx - half},
                    new int[] {sy, sy + quarter, sy + quarter + blockHeight, sy + blockHeight}, 4);
            Polygon right = new Polygon(new int[] {sx, sx + half, sx + half, sx},
                    new int[] {sy + quarter, sy, sy + blockHeight, sy + quarter + blockHeight}, 4);
            g.setColor(shadeColor(base, 0.62));
            g.fillPolygon(left);
            g.setColor(shadeColor(base, 0.8));
            g.fillPolygon(right);
            g.setColor(shadeColor(base, 1.05));
            g.fillPolygon(topFace);
        }
        g.dispose();
        return image;
    }

    // ------------------------------------------------------------------ perspective

    /** A camera for {@link #perspective}: eye position, yaw (0 = looking +z, 90 = looking −x), pitch (up > 0), fov. */
    public record Camera(double x, double y, double z, double yaw, double pitch, double fov) {
    }

    private short[] grid;
    private List<BlockSpec> gridPalette;
    private int[] skyTop;
    private MapLight blockLight;

    /**
     * A perspective picture of the map from {@code camera} ({@code fov} degrees across the width), close to what a
     * player sees: rays through the blocks (2 x 2 per pixel), glass tints what lies behind it, faces are shaded by their
     * direction, places under the open sky are brighter, block light (lanterns, light blocks) warms what is near it,
     * light sources glow. Lanterns, chains, walls and fences have their smaller shapes. The sky is a day gradient (blue
     * overhead, pink at the horizon), the void below the horizon a dusk violet.
     */
    public BufferedImage perspective(Camera camera, int width, int height) {
        prepareGrid();
        double yaw = Math.toRadians(camera.yaw());
        double pitch = Math.toRadians(camera.pitch());
        double[] f = {-Math.sin(yaw) * Math.cos(pitch), Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch)};
        double[] right = {-Math.cos(yaw), 0, -Math.sin(yaw)};
        double[] up = cross(right, f);
        double tan = Math.tan(Math.toRadians(camera.fov()) / 2);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int samples = 2;
        java.util.stream.IntStream.range(0, height).parallel().forEach(j -> {
            for (int i = 0; i < width; i++) {
                double[] sum = new double[3];
                for (int sy = 0; sy < samples; sy++) {
                    for (int sx = 0; sx < samples; sx++) {
                        double px = (2 * (i + (sx + 0.5) / samples) / width - 1) * tan;
                        double py = (1 - 2 * (j + (sy + 0.5) / samples) / height) * tan * height / width;
                        double[] dir = {f[0] + px * right[0] + py * up[0], f[1] + px * right[1] + py * up[1],
                            f[2] + px * right[2] + py * up[2]};
                        double len = Math.sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2]);
                        double[] c = trace(camera.x(), camera.y(), camera.z(), dir[0] / len, dir[1] / len,
                                dir[2] / len);
                        sum[0] += c[0];
                        sum[1] += c[1];
                        sum[2] += c[2];
                    }
                }
                int n = samples * samples;
                int rgb = (clamp(sum[0] / n) << 16) | (clamp(sum[1] / n) << 8) | clamp(sum[2] / n);
                synchronized (image) {
                    image.setRGB(i, j, rgb);
                }
            }
        });
        return image;
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private void prepareGrid() {
        if (grid != null) {
            return;
        }
        int r = canvas.radius();
        int size = 2 * r + 1;
        int ys = canvas.maxY() - canvas.minY() + 1;
        grid = new short[size * size * ys];
        gridPalette = new ArrayList<>();
        gridPalette.add(null);
        java.util.Map<BlockSpec, Short> index = new java.util.HashMap<>();
        skyTop = new int[size * size];
        java.util.Arrays.fill(skyTop, Integer.MIN_VALUE);
        canvas.forEach((x, y, z, spec) -> {
            Short id = index.get(spec);
            if (id == null) {
                id = (short) gridPalette.size();
                gridPalette.add(spec);
                index.put(spec, id);
            }
            grid[cell(x, y, z)] = id;
            if (!spec.material().name().contains("GLASS")) {
                int column = (x + r) * size + z + r;
                skyTop[column] = Math.max(skyTop[column], y);
            }
        });
        blockLight = new MapLight(canvas);
    }

    private int cell(int x, int y, int z) {
        int r = canvas.radius();
        int size = 2 * r + 1;
        int ys = canvas.maxY() - canvas.minY() + 1;
        return ((x + r) * size + (z + r)) * ys + (y - canvas.minY());
    }

    private BlockSpec block(int x, int y, int z) {
        int r = canvas.radius();
        if (Math.abs(x) > r || Math.abs(z) > r || y < canvas.minY() || y > canvas.maxY()) {
            return null;
        }
        return gridPalette.get(grid[cell(x, y, z)]);
    }

    /** The colour (0..255 per channel) seen along a ray. */
    private double[] trace(double ox, double oy, double oz, double dx, double dy, double dz) {
        int x = (int) Math.floor(ox);
        int y = (int) Math.floor(oy);
        int z = (int) Math.floor(oz);
        int stepX = dx > 0 ? 1 : -1;
        int stepY = dy > 0 ? 1 : -1;
        int stepZ = dz > 0 ? 1 : -1;
        double tDeltaX = Math.abs(1 / dx);
        double tDeltaY = Math.abs(1 / dy);
        double tDeltaZ = Math.abs(1 / dz);
        double tMaxX = (dx > 0 ? x + 1 - ox : ox - x) * tDeltaX;
        double tMaxY = (dy > 0 ? y + 1 - oy : oy - y) * tDeltaY;
        double tMaxZ = (dz > 0 ? z + 1 - oz : oz - z) * tDeltaZ;
        double[] colour = {0, 0, 0};
        double transmit = 1;
        double t = 0;
        int axis = -1;
        BlockSpec lastGlass = null;
        int r = canvas.radius();
        while (t < 700 && transmit > 0.02) {
            BlockSpec spec = block(x, y, z);
            if (spec != null && !spec.isAir()) {
                String n = spec.material().name();
                if (n.contains("GLASS")) {
                    if (spec != lastGlass) {
                        Color c = color(spec.material());
                        double alpha = n.contains("STAINED") ? 0.42 : 0.12;
                        double lit = skyLitCell(x, y + 1, z) ? 1.05 : 0.8;
                        colour[0] += transmit * alpha * c.getRed() * lit;
                        colour[1] += transmit * alpha * c.getGreen() * lit;
                        colour[2] += transmit * alpha * c.getBlue() * lit;
                        transmit *= 1 - alpha;
                    }
                    lastGlass = spec;
                } else {
                    lastGlass = null;
                    int face = shapeHit(spec, x, y, z, ox, oy, oz, dx, dy, dz, t, axis);
                    if (face >= -1) {
                        double[] c = shade(spec, x, y, z, face, dx, dy, dz, t);
                        colour[0] += transmit * c[0];
                        colour[1] += transmit * c[1];
                        colour[2] += transmit * c[2];
                        return colour;
                    }
                }
            } else {
                lastGlass = null;
            }
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                x += stepX;
                t = tMaxX;
                tMaxX += tDeltaX;
                axis = 0;
            } else if (tMaxY < tMaxZ) {
                y += stepY;
                t = tMaxY;
                tMaxY += tDeltaY;
                axis = 1;
            } else {
                z += stepZ;
                t = tMaxZ;
                tMaxZ += tDeltaZ;
                axis = 2;
            }
            if (Math.abs(x) > r + 2 && (x > 0) == (dx > 0) || Math.abs(z) > r + 2 && (z > 0) == (dz > 0)
                    || y > canvas.maxY() + 2 && dy > 0 || y < canvas.minY() - 2 && dy < 0) {
                break;
            }
        }
        double[] sky = sky(dy);
        colour[0] += transmit * sky[0];
        colour[1] += transmit * sky[1];
        colour[2] += transmit * sky[2];
        return colour;
    }

    private static double[] sky(double dy) {
        if (dy < 0) {
            double k = Math.min(1, -dy * 3);
            return new double[] {lerp(235, 60, k), lerp(185, 40, k), lerp(200, 90, k)};
        }
        double k = Math.pow(Math.min(1, dy), 0.6);
        return new double[] {lerp(245, 95, k), lerp(185, 150, k), lerp(200, 230, k)};
    }

    private static double lerp(double a, double b, double k) {
        return a + (b - a) * k;
    }

    /**
     * The axis of the face where a ray meets a block's shape inside its cell, or {@code -2} when it misses (lanterns,
     * chains, walls and fences are smaller than their cell; everything else fills it).
     */
    private static int shapeHit(BlockSpec spec, int x, int y, int z, double ox, double oy, double oz, double dx,
                                double dy, double dz, double t, int axis) {
        String n = spec.material().name();
        double[] box;
        if (n.equals("LANTERN") || n.equals("SOUL_LANTERN")) {
            box = spec.hanging() == Boolean.TRUE ? new double[] {0.31, 0.06, 0.31, 0.69, 0.62, 0.69}
                    : new double[] {0.31, 0, 0.31, 0.69, 0.56, 0.69};
        } else if (n.endsWith("CHAIN")) {
            box = new double[] {0.42, 0, 0.42, 0.58, 1, 0.58};
        } else if (n.endsWith("_WALL") || n.endsWith("_FENCE")) {
            box = new double[] {0.22, 0, 0.22, 0.78, 1, 0.78};
        } else {
            return axis;
        }
        double tmin = -1e9;
        double tmax = 1e9;
        int hitAxis = axis;
        double[] o = {ox - x, oy - y, oz - z};
        double[] d = {dx, dy, dz};
        for (int a = 0; a < 3; a++) {
            if (Math.abs(d[a]) < 1e-12) {
                if (o[a] < box[a] || o[a] > box[a + 3]) {
                    return -2;
                }
                continue;
            }
            double t1 = (box[a] - o[a]) / d[a];
            double t2 = (box[a + 3] - o[a]) / d[a];
            double near = Math.min(t1, t2);
            if (near > tmin) {
                tmin = near;
                hitAxis = a;
            }
            tmax = Math.min(tmax, Math.max(t1, t2));
        }
        return tmax >= Math.max(tmin, t - 1e-9) ? hitAxis : -2;
    }

    /** The colour of a block's face seen from a ray (the face by the axis the ray entered through). */
    private double[] shade(BlockSpec spec, int x, int y, int z, int axis, double dx, double dy, double dz, double t) {
        Color base = color(spec.material());
        double[] c = {base.getRed(), base.getGreen(), base.getBlue()};
        // The cell in front of the face, where the light comes from.
        int fx = x - (axis == 0 ? (dx > 0 ? 1 : -1) : 0);
        int fy = y - (axis == 1 ? (dy > 0 ? 1 : -1) : 0);
        int fz = z - (axis == 2 ? (dz > 0 ? 1 : -1) : 0);
        double face = switch (axis) {
            case 1 -> dy < 0 ? 1.0 : 0.66;
            case 0 -> 0.86;
            case 2 -> 0.76;
            default -> 0.9;
        };
        double result;
        double warm = 0;
        if (MapLight.emission(spec.material()) > 0) {
            result = 1.35;
        } else {
            double ambient = skyLitCell(fx, fy, fz) ? 1.0 : 0.6;
            double block = blockLight.at(fx, fy, fz) / 15.0;
            result = face * Math.max(ambient, 0.42 + 0.62 * block);
            warm = block * block * 0.35;
        }
        double fog = 1 - Math.exp(-t / 520);
        double[] out = new double[3];
        double[] warmTint = {255, 190, 110};
        double[] haze = {225, 190, 205};
        for (int i = 0; i < 3; i++) {
            double v = c[i] * result + warmTint[i] * warm * 0.5;
            out[i] = v * (1 - fog) + haze[i] * fog;
        }
        return out;
    }

    /** True if a cell sees the sky straight up (nothing but glass and air above it). */
    private boolean skyLitCell(int x, int y, int z) {
        int r = canvas.radius();
        if (Math.abs(x) > r || Math.abs(z) > r) {
            return true;
        }
        return y > skyTop[(x + r) * (2 * r + 1) + z + r];
    }

    /** Brightness factor of a block in the night look (1 by day). */
    private double light(int x, int y, int z, BlockSpec spec) {
        if (!night) {
            return 1.0;
        }
        if (emissive(spec)) {
            return 1.25;
        }
        int[][] sides = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] d : sides) {
            BlockSpec next = canvas.get(x + d[0], y + d[1], z + d[2]);
            if (next != null && emissive(next)) {
                return 0.85;
            }
        }
        return 0.42;
    }

    private static boolean emissive(BlockSpec spec) {
        String n = spec.material().name();
        return n.equals("SEA_LANTERN") || n.endsWith("FROGLIGHT") || n.equals("GLOWSTONE") || n.equals("SHROOMLIGHT")
                || n.endsWith("LANTERN") || n.equals("END_ROD") || n.contains("TORCH") || n.endsWith("_BULB")
                || n.equals("REDSTONE_BLOCK") || n.equals("MAGMA_BLOCK") || n.equals("CRYING_OBSIDIAN")
                || n.equals("BEACON") || n.equals("REDSTONE_LAMP");
    }

    private boolean exposed(int x, int y, int z) {
        return !opaque(x, y + 1, z) || !opaque(x + 1, y, z) || !opaque(x, y, z + 1);
    }

    private boolean opaque(int x, int y, int z) {
        BlockSpec spec = canvas.get(x, y, z);
        return spec != null && MapBlocks.isFullCube(spec);
    }

    // ------------------------------------------------------------------ files

    public static void write(BufferedImage image, File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create " + parent);
        }
        ImageIO.write(image, "png", file);
    }

    // ------------------------------------------------------------------ colours

    private static Color shadeColor(Color c, double f) {
        return new Color(clamp(c.getRed() * f), clamp(c.getGreen() * f), clamp(c.getBlue() * f));
    }

    private static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }

    /** Approximate colour of a block. */
    public static Color color(Material material) {
        String n = material.name();
        Color exact = exact(n);
        if (exact != null) {
            return exact;
        }
        String s = n.toLowerCase(Locale.ROOT);
        Color dyed = dyed(s);
        if (dyed != null) {
            return dyed;
        }
        if (s.contains("leaves")) {
            return s.contains("birch") ? new Color(110, 150, 70) : s.contains("spruce") ? new Color(60, 95, 60)
                    : s.contains("dark_oak") ? new Color(55, 100, 35) : s.contains("cherry") ? new Color(230, 160, 190)
                    : s.contains("azalea") ? new Color(95, 135, 50) : s.contains("pale_oak") ? new Color(150, 160, 150)
                    : new Color(70, 125, 45);
        }
        if (s.contains("water")) {
            return new Color(50, 90, 200);
        }
        if (s.contains("lava") || s.contains("magma")) {
            return new Color(220, 100, 20);
        }
        if (s.contains("deepslate") || s.contains("blackstone") || s.contains("basalt")) {
            return new Color(70, 70, 78);
        }
        if (s.contains("mossy")) {
            return new Color(105, 120, 85);
        }
        if (s.contains("cracked") || s.contains("stone_brick") || s.contains("stone") || s.contains("cobble")
                || s.contains("andesite") || s.contains("smooth_stone")) {
            return s.contains("polished") ? new Color(135, 137, 138) : new Color(122, 122, 122);
        }
        if (s.contains("tuff")) {
            return new Color(108, 110, 102);
        }
        if (s.contains("diorite") || s.contains("calcite") || s.contains("quartz") || s.contains("bone")) {
            return new Color(222, 220, 215);
        }
        if (s.contains("granite")) {
            return new Color(150, 103, 86);
        }
        if (s.contains("sandstone") || s.contains("sand")) {
            return s.contains("red") ? new Color(180, 100, 40) : new Color(220, 205, 160);
        }
        if (s.contains("mud_brick") || s.contains("packed_mud")) {
            return new Color(140, 105, 80);
        }
        if (s.contains("brick")) {
            return new Color(150, 75, 60);
        }
        if (s.contains("spruce")) {
            return new Color(105, 80, 50);
        }
        if (s.contains("dark_oak")) {
            return new Color(70, 48, 26);
        }
        if (s.contains("birch")) {
            return new Color(195, 180, 125);
        }
        if (s.contains("jungle")) {
            return new Color(160, 115, 80);
        }
        if (s.contains("acacia")) {
            return new Color(170, 92, 50);
        }
        if (s.contains("mangrove")) {
            return new Color(118, 55, 50);
        }
        if (s.contains("bamboo")) {
            return new Color(222, 196, 92);
        }
        if (s.contains("cherry")) {
            return new Color(225, 180, 175);
        }
        if (s.contains("crimson")) {
            return new Color(110, 50, 70);
        }
        if (s.contains("warped")) {
            return new Color(45, 110, 105);
        }
        if (s.contains("pale_oak")) {
            return new Color(225, 215, 210);
        }
        if (s.contains("oak") || s.contains("plank") || s.contains("barrel") || s.contains("bookshelf")) {
            return new Color(160, 130, 80);
        }
        if (s.contains("grass") || s.contains("fern") || s.contains("moss") || s.contains("vine")) {
            return new Color(95, 150, 60);
        }
        if (s.contains("dirt") || s.contains("podzol") || s.contains("rooted") || s.contains("farmland")) {
            return new Color(130, 95, 65);
        }
        if (s.contains("gravel")) {
            return new Color(130, 125, 122);
        }
        if (s.contains("glass")) {
            return new Color(190, 215, 225);
        }
        if (s.contains("iron") || s.contains("chain") || s.contains("anvil")) {
            return new Color(170, 170, 175);
        }
        if (s.contains("gold")) {
            return new Color(240, 200, 60);
        }
        if (s.contains("copper")) {
            return s.contains("oxidized") || s.contains("weathered") ? new Color(80, 160, 130)
                    : new Color(190, 110, 80);
        }
        if (s.contains("lantern") || s.contains("torch") || s.contains("glowstone") || s.contains("shroomlight")
                || s.contains("froglight")) {
            return new Color(250, 210, 120);
        }
        if (s.contains("red")) {
            return new Color(170, 50, 45);
        }
        if (s.contains("white") || s.contains("snow")) {
            return new Color(235, 238, 240);
        }
        if (s.contains("black")) {
            return new Color(35, 35, 40);
        }
        if (s.contains("purple") || s.contains("amethyst")) {
            return new Color(130, 80, 170);
        }
        if (s.contains("blue")) {
            return new Color(55, 75, 165);
        }
        if (s.contains("yellow")) {
            return new Color(230, 200, 60);
        }
        if (s.contains("green") || s.contains("lime")) {
            return new Color(90, 150, 60);
        }
        if (s.contains("brown")) {
            return new Color(110, 75, 50);
        }
        if (s.contains("gray") || s.contains("grey")) {
            return new Color(100, 100, 105);
        }
        if (s.contains("orange")) {
            return new Color(220, 130, 50);
        }
        if (s.contains("pink")) {
            return new Color(230, 150, 175);
        }
        if (s.contains("cyan")) {
            return new Color(40, 140, 150);
        }
        if (s.contains("flower") || s.contains("poppy") || s.contains("tulip") || s.contains("dandelion")) {
            return new Color(200, 120, 90);
        }
        return new Color(160, 150, 140);
    }

    private static final String[] DYES = {"light_blue", "light_gray", "white", "orange", "magenta", "yellow", "lime",
        "pink", "gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
    private static final int[][] DYE_RGB = {{58, 175, 217}, {142, 142, 134}, {233, 236, 236}, {240, 118, 19},
        {189, 68, 179}, {248, 197, 39}, {112, 185, 25}, {237, 141, 172}, {62, 68, 71}, {21, 137, 145},
        {121, 42, 172}, {53, 57, 157}, {114, 71, 40}, {84, 109, 27}, {160, 39, 34}, {20, 21, 25}};

    /** Colours of dyed concrete, glass, wool, terracotta and carpets. */
    private static Color dyed(String s) {
        if (!(s.endsWith("_concrete") || s.contains("stained_glass") || s.endsWith("_wool") || s.endsWith("_carpet")
                || s.endsWith("_terracotta") && !s.contains("glazed") || s.endsWith("_concrete_powder"))) {
            return null;
        }
        for (int i = 0; i < DYES.length; i++) {
            if (s.startsWith(DYES[i] + "_")) {
                int[] c = DYE_RGB[i];
                // Concrete is a little deeper than wool or carpet of the same dye (as in the game).
                double f = s.endsWith("_terracotta") ? 0.6 : s.endsWith("_concrete") ? 0.88 : 1.0;
                return new Color((int) (c[0] * f), (int) (c[1] * f), (int) (c[2] * f));
            }
        }
        return null;
    }

    private static Color exact(String name) {
        return switch (name) {
            case "SEA_LANTERN" -> new Color(200, 240, 235);
            case "PEARLESCENT_FROGLIGHT" -> new Color(245, 140, 230);
            case "VERDANT_FROGLIGHT" -> new Color(170, 245, 140);
            case "OCHRE_FROGLIGHT" -> new Color(250, 225, 120);
            case "GOLD_BLOCK" -> new Color(246, 208, 61);
            case "RAW_GOLD_BLOCK" -> new Color(200, 150, 45);
            case "RED_NETHER_BRICKS", "RED_NETHER_BRICK_WALL", "RED_NETHER_BRICK_STAIRS",
                 "RED_NETHER_BRICK_SLAB" -> new Color(100, 22, 28);
            case "SMOOTH_SANDSTONE" -> new Color(224, 212, 165);
            case "QUARTZ_PILLAR" -> new Color(236, 230, 222);
            case "END_ROD" -> new Color(250, 245, 235);
            case "TINTED_GLASS" -> new Color(40, 34, 46);
            case "REDSTONE_BLOCK" -> new Color(220, 30, 20);
            case "IRON_BLOCK" -> new Color(205, 205, 210);
            case "POLISHED_BLACKSTONE", "POLISHED_BLACKSTONE_BRICKS", "BLACKSTONE",
                 "CRACKED_POLISHED_BLACKSTONE_BRICKS" -> new Color(45, 40, 48);
            case "TUFF", "TUFF_BRICKS" -> new Color(108, 110, 102);
            case "SMOOTH_BASALT" -> new Color(72, 72, 78);
            case "GRASS_BLOCK" -> new Color(98, 150, 62);
            case "DIRT_PATH" -> new Color(148, 122, 70);
            case "COARSE_DIRT" -> new Color(120, 88, 60);
            case "PODZOL" -> new Color(100, 70, 35);
            case "STONE" -> new Color(125, 125, 125);
            case "COBBLESTONE" -> new Color(115, 115, 115);
            case "MOSSY_COBBLESTONE" -> new Color(100, 118, 88);
            case "GRAVEL" -> new Color(132, 126, 124);
            case "WATER" -> new Color(52, 92, 205);
            case "ICE", "PACKED_ICE", "BLUE_ICE" -> new Color(150, 185, 245);
            case "OAK_LOG", "OAK_WOOD" -> new Color(105, 85, 50);
            case "SPRUCE_LOG", "SPRUCE_WOOD" -> new Color(60, 42, 25);
            case "DARK_OAK_LOG", "DARK_OAK_WOOD" -> new Color(60, 46, 28);
            case "BIRCH_LOG", "BIRCH_WOOD" -> new Color(215, 210, 200);
            case "HAY_BLOCK" -> new Color(200, 170, 40);
            case "SPONGE" -> new Color(205, 195, 85);
            case "WET_SPONGE" -> new Color(170, 172, 64);
            case "TERRACOTTA" -> new Color(150, 92, 66);
            case "CLAY" -> new Color(160, 166, 178);
            case "MUD" -> new Color(60, 55, 60);
            case "SHORT_GRASS", "TALL_GRASS" -> new Color(90, 140, 55);
            case "CAMPFIRE" -> new Color(200, 110, 40);
            default -> null;
        };
    }
}
