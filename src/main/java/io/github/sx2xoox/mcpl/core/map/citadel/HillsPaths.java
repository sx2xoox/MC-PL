package io.github.sx2xoox.mcpl.core.map.citadel;

import java.util.ArrayList;
import java.util.List;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

/**
 * Worn footpaths between the roads and the landmarks: trodden dirt and gravel that follow the hills, with stone steps
 * cut into the slope wherever the path climbs.
 */
final class HillsPaths {

    private static final Material[] SURFACE = {Material.DIRT_PATH, Material.DIRT_PATH, Material.DIRT_PATH,
            Material.DIRT_PATH, Material.DIRT_PATH, Material.COARSE_DIRT, Material.COARSE_DIRT, Material.GRAVEL};
    private static final Material[] STEPS = {Material.COBBLESTONE_STAIRS, Material.COBBLESTONE_STAIRS,
            Material.MOSSY_COBBLESTONE_STAIRS, Material.STONE_BRICK_STAIRS};
    private static final BlockFace[] FACES = {BlockFace.EAST, BlockFace.WEST, BlockFace.SOUTH, BlockFace.NORTH};
    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private final HillsSite site;
    private final List<int[]> laid = new ArrayList<>();

    HillsPaths(HillsSite site) {
        this.site = site;
    }

    void build() {
        // From the ring road over the knoll's shoulder to the tower's west stair.
        path(1.3, 33, -16, 40, -22, 47, -26, 55, -27, 62, -27);
        // From the east road up the tower hill's south face.
        path(1.2, 68, -3, 69, -11, 68, -19, 67, -26);
        // From the camp's east gate along the hollow to the tower.
        path(1.2, 51, -37, 57, -38, 63, -37, 67, -36);
        // Along the ridge from the tower to the windmill.
        path(1.1, 67, -37, 66, -43, 62, -48, 56, -52, 53, -57);
        // From the ring road to the camp's south gate.
        path(1.2, 29, -24, 32, -29, 34, -33);
        // From the north-east road to the windmill's back door.
        path(1.1, 46, -71, 52, -67, 58, -63);
        // From the tower's east stair down to the beacon knoll.
        path(1.0, 72, -36, 76, -30, 79, -24, 81, -18);
        // From the east road to the beacon knoll.
        path(1.0, 83, -3, 83, -9);
        steps();
    }

    /** Lays a path of {@code halfWidth} through the points {@code x1, z1, x2, z2, ...}. */
    private void path(double halfWidth, int... points) {
        for (int i = 0; i + 3 < points.length; i += 2) {
            double ax = points[i];
            double az = points[i + 1];
            double bx = points[i + 2];
            double bz = points[i + 3];
            double length = HillsSite.dist(ax, az, bx, bz);
            for (double t = 0; t <= length; t += 0.5) {
                double x = ax + (bx - ax) * t / length;
                double z = az + (bz - az) * t / length;
                // A little wobble so the path does not look ruled.
                double wobble = site.noise(x / 6.0, z / 6.0, 41) * 0.8;
                double w = halfWidth + site.noise(x / 3.0, z / 3.0, 42) * 0.35;
                brush(x + wobble * (bz - az) / length, z - wobble * (bx - ax) / length, w);
            }
        }
    }

    private void brush(double cx, double cz, double radius) {
        int r = (int) Math.ceil(radius);
        for (int x = (int) Math.floor(cx) - r; x <= (int) Math.ceil(cx) + r; x++) {
            for (int z = (int) Math.floor(cz) - r; z <= (int) Math.ceil(cz) + r; z++) {
                if (HillsSite.dist(x + 0.5, z + 0.5, cx, cz) > radius + 0.5) {
                    continue;
                }
                HillsSite.Use use = site.use(x, z);
                if (use == HillsSite.Use.FREE) {
                    site.set(x, site.ground(x, z), z, BlockSpec.of(SURFACE[site.random.nextInt(SURFACE.length)]));
                    site.mark(x, z, HillsSite.Use.PATH);
                    laid.add(new int[] {x, z});
                }
            }
        }
    }

    /**
     * Where the path climbs one block, the higher column's top becomes a stair facing up the slope, so walking up
     * the path is a series of half steps.
     */
    private void steps() {
        for (int[] at : laid) {
            int x = at[0];
            int z = at[1];
            int g = site.ground(x, z);
            int lower = -1;
            int lowerCount = 0;
            for (int s = 0; s < SIDES.length; s++) {
                int nx = x + SIDES[s][0];
                int nz = z + SIDES[s][1];
                if (site.use(nx, nz) == HillsSite.Use.PATH && site.ground(nx, nz) == g - 1) {
                    lower = s;
                    lowerCount++;
                }
            }
            if (lowerCount != 1) {
                continue;
            }
            // The column on the far side must not be lower too (a stair needs its back against the slope).
            int ox = x - SIDES[lower][0];
            int oz = z - SIDES[lower][1];
            if (site.ground(ox, oz) < g) {
                continue;
            }
            // Facing away from the lower neighbour: walk up the stair from that side.
            BlockFace facing = FACES[lower].getOppositeFace();
            site.set(x, g, z, Blocks.stairs(STEPS[site.random.nextInt(STEPS.length)], facing));
        }
    }
}
