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
        g.setColor(new Color(18, 20, 28));
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
                Color c = shadeColor(base, shade + Math.max(-0.15, Math.min(0.25, height01 * 0.25)));
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
        g.setColor(new Color(150, 190, 235));
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
            Color base = color(spec.material());
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

    private static Color exact(String name) {
        return switch (name) {
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
            case "TERRACOTTA" -> new Color(150, 92, 66);
            case "CLAY" -> new Color(160, 166, 178);
            case "MUD" -> new Color(60, 55, 60);
            case "SHORT_GRASS", "TALL_GRASS" -> new Color(90, 140, 55);
            case "CAMPFIRE" -> new Color(200, 110, 40);
            default -> null;
        };
    }
}
