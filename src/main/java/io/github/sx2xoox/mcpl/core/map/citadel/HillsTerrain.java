package io.github.sx2xoox.mcpl.core.map.citadel;

import static io.github.sx2xoox.mcpl.core.map.citadel.HillsSite.clamp;
import static io.github.sx2xoox.mcpl.core.map.citadel.HillsSite.lerp;
import static io.github.sx2xoox.mcpl.core.map.citadel.HillsSite.smoothstep;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import org.bukkit.Material;

/**
 * The rolling ground of the hills district: a tall watchtower hill, the windmill hill joined to it by a saddle, low
 * knolls, gentle swells everywhere else, and a flat hollow for the camp. Every slope is walkable (neighbouring columns
 * differ by at most one block) and the district's edges stay at ground level.
 */
final class HillsTerrain {

    private static final int MIN_X = 0;
    private static final int MAX_X = 97;
    private static final int MIN_Z = -97;
    private static final int MAX_Z = 0;

    private static final BlockSpec DIRT = BlockSpec.of(Material.DIRT);
    private static final BlockSpec STONE = BlockSpec.of(Material.STONE);

    private final HillsSite site;

    HillsTerrain(HillsSite site) {
        this.site = site;
    }

    void build() {
        int width = MAX_X - MIN_X + 1;
        int depth = MAX_Z - MIN_Z + 1;
        int[][] height = new int[width][depth];
        boolean[][] zone = new boolean[width][depth];
        for (int x = MIN_X; x <= MAX_X; x++) {
            for (int z = MIN_Z; z <= MAX_Z; z++) {
                if (site.inZone(x, z)) {
                    zone[x - MIN_X][z - MIN_Z] = true;
                    height[x - MIN_X][z - MIN_Z] = (int) Math.round(Math.max(0, height(x, z)));
                }
            }
        }
        limitSlopes(height, zone);
        for (int x = MIN_X; x <= MAX_X; x++) {
            for (int z = MIN_Z; z <= MAX_Z; z++) {
                if (zone[x - MIN_X][z - MIN_Z]) {
                    column(x, z, height[x - MIN_X][z - MIN_Z]);
                }
            }
        }
    }

    // ------------------------------------------------------------------ shape

    /** The wanted height above the base ground (before slopes are limited). */
    private double height(int x, int z) {
        double h = 0;
        h = Math.max(h, dome(x, z, HillsDistrict.TOWER, 7.5, 19, HillsDistrict.TOWER_HILL, 11));
        h = Math.max(h, dome(x, z, HillsDistrict.WINDMILL, 7, 16, HillsDistrict.WINDMILL_HILL, 12));
        h = Math.max(h, ridge(x, z, HillsDistrict.TOWER, HillsDistrict.WINDMILL, 5.4, 8.5));
        h = Math.max(h, ridge(x, z, HillsDistrict.TOWER, HillsDistrict.CRAGS, 3.6, 7));
        h = Math.max(h, dome(x, z, HillsDistrict.CRAGS, 2, 8, 3.5, 15));
        h = Math.max(h, dome(x, z, HillsDistrict.KNOLL, 2, 10, 3.2, 13));
        h = Math.max(h, dome(x, z, HillsDistrict.BEACON, 4, 10.5, 5, 14));
        h = Math.max(h, dome(x, z, HillsDistrict.SPUR, 1.5, 9, 2.6, 16));
        // Gentle swells everywhere except on the flat tops the buildings stand on.
        double flat = Math.max(1 - smoothstep(8.5, 11, distTo(x, z, HillsDistrict.TOWER)),
                1 - smoothstep(8, 10.5, distTo(x, z, HillsDistrict.WINDMILL)));
        double swell = (site.fbm(x / 15.0, z / 15.0, 3) * 0.5 + 0.5) * 2.2 - 0.35;
        h += Math.max(0, swell) * (1 - flat);
        // The camp sits in a flat hollow.
        double camp = smoothstep(HillsDistrict.CAMP_RADIUS + 0.5, HillsDistrict.CAMP_RADIUS + 5,
                distTo(x, z, HillsDistrict.CAMP));
        h = lerp(0, h, camp);
        // So does the barn, on a level pad.
        double bx = Math.max(Math.abs(x - HillsDistrict.BARN[0]) - (HillsBarn.HALF_LENGTH + 2.5), 0);
        double bz = Math.max(Math.abs(z - HillsDistrict.BARN[1]) - (HillsBarn.HALF_WIDTH + 3.5), 0);
        h = lerp(0, h, smoothstep(0, 4, Math.sqrt(bx * bx + bz * bz)));
        // Down to the base ground along the roads, the ring road and the outer ring.
        return h * smoothstep(0.5, 7, edgeDistance(x, z));
    }

    /** A round hill with a flat top ({@code plateau}) falling to nothing at {@code radius}, with a noisy outline. */
    private double dome(int x, int z, int[] center, double plateau, double radius, double height, int salt) {
        double d = distTo(x, z, center);
        double warp = site.noise(x / 9.0, z / 9.0, salt) * 3.2 * smoothstep(plateau, plateau + 4, d);
        double t = clamp((d + warp - plateau) / (radius - plateau), 0, 1);
        return height * (0.5 + 0.5 * Math.cos(Math.PI * t));
    }

    /** A ridge along the segment {@code a..b}. */
    private double ridge(int x, int z, int[] a, int[] b, double height, double halfWidth) {
        double d = HillsSite.segmentDistance(x, z, a[0], a[1], b[0], b[1]);
        d += site.noise(x / 7.0, z / 7.0, 21) * 1.5;
        double t = clamp(d / halfWidth, 0, 1);
        return height * (0.5 + 0.5 * Math.cos(Math.PI * t));
    }

    private static double distTo(int x, int z, int[] point) {
        return HillsSite.dist(x, z, point[0], point[1]);
    }

    /** How far the column is from the nearest edge of the district (roads, ring road, outer ring). */
    static double edgeDistance(int x, int z) {
        double r = CitadelLayout.radius(x, z);
        double edge = Math.min(r - CitadelLayout.RING_ROAD_OUTER - 0.5, CitadelLayout.DISTRICT_OUTER + 0.5 - r);
        edge = Math.min(edge, CitadelLayout.roadDistance(x, z, 300) - CitadelLayout.ROAD_HALF_WIDTH);
        edge = Math.min(edge, CitadelLayout.roadDistance(x, z, 0) - CitadelLayout.ROAD_HALF_WIDTH);
        return edge;
    }

    /**
     * Lowers the heights until neighbouring columns differ by at most one block (and columns next to the district
     * by at most one block from the base ground), so every slope can be walked up.
     */
    private static void limitSlopes(int[][] height, boolean[][] zone) {
        int width = height.length;
        int depth = height[0].length;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int pass = 0; pass < 2; pass++) {
                for (int a = 0; a < width; a++) {
                    for (int b = 0; b < depth; b++) {
                        int i = pass == 0 ? a : width - 1 - a;
                        int j = pass == 0 ? b : depth - 1 - b;
                        if (!zone[i][j]) {
                            continue;
                        }
                        int limit = Math.min(Math.min(neighbour(height, zone, i - 1, j), neighbour(height, zone, i + 1, j)),
                                Math.min(neighbour(height, zone, i, j - 1), neighbour(height, zone, i, j + 1))) + 1;
                        if (height[i][j] > limit) {
                            height[i][j] = limit;
                            changed = true;
                        }
                    }
                }
            }
        }
    }

    private static int neighbour(int[][] height, boolean[][] zone, int i, int j) {
        if (i < 0 || j < 0 || i >= height.length || j >= height[0].length || !zone[i][j]) {
            return 0;
        }
        return height[i][j];
    }

    // ------------------------------------------------------------------ blocks

    /** Writes one column: stone core, dirt, and a top of grass (with worn and rocky patches). */
    private void column(int x, int z, int h) {
        int base = CitadelLayout.GROUND_Y;
        int top = base + h;
        site.setGround(x, z, top);
        for (int y = base; y < top; y++) {
            site.set(x, y, z, y >= top - 3 ? DIRT : STONE);
        }
        site.set(x, top, z, BlockSpec.of(topMaterial(x, z, h)));
    }

    private Material topMaterial(int x, int z, int h) {
        double rocky = site.noise(x / 4.0, z / 4.0, 31);
        double worn = site.noise(x / 6.0, z / 6.0, 32);
        double grain = site.hash(x, z, 33);
        if (h >= 4 && rocky > 0.55 && grain > -0.4) {
            return grain > 0.5 ? Material.ANDESITE : grain > 0.1 ? Material.STONE : Material.COARSE_DIRT;
        }
        if (worn > 0.62 && grain > 0.2) {
            return grain > 0.75 ? Material.ROOTED_DIRT : Material.COARSE_DIRT;
        }
        if (h >= 5 && grain > 0.95) {
            return Material.MOSS_BLOCK;
        }
        return Material.GRASS_BLOCK;
    }
}
