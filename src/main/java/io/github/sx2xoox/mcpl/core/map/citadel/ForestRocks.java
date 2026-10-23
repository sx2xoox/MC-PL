package io.github.sx2xoox.mcpl.core.map.citadel;

import java.util.ArrayList;
import java.util.List;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import org.bukkit.Material;

/**
 * Mossy rocks: boulders, boulder clusters and the big crag. Every rock is a mound of stone whose top rises at most
 * one block per column from a scree slope on one or more sides, so players can climb every rock from somewhere while
 * its other sides stay steep enough to hide behind.
 */
final class ForestRocks {

    private final ForestSite site;

    ForestRocks(ForestSite site) {
        this.site = site;
    }

    /**
     * A rock: an irregular ellipse of {@code rx} by {@code rz} blocks turned by {@code rotation} (radians),
     * {@code height} blocks high, with a scree slope down each side facing one of {@code rampAngles} (radians).
     * {@code plateau} 0 makes a rounded boulder; above 0 a flat-topped rock whose top covers that share of the
     * ellipse, with cliffs all round except where the scree runs down. Returns false (and builds nothing) if the
     * ground there is not free ({@code overlap}: other rocks may be there).
     */
    boolean rock(double cx, double cz, double rx, double rz, double rotation, int height, double plateau,
                 boolean overlap, double... rampAngles) {
        int reach = (int) Math.ceil(Math.max(rx, rz) * 1.2) + height + 2;
        int bx = (int) Math.round(cx);
        int bz = (int) Math.round(cz);
        double cos = Math.cos(rotation);
        double sin = Math.sin(rotation);
        int salt = site.random.nextInt(1000);
        boolean[] footprint = new boolean[ForestSite.columns()];
        boolean[] region = new boolean[ForestSite.columns()];
        int[] heights = new int[ForestSite.columns()];
        int count = 0;
        for (int x = bx - reach; x <= bx + reach; x++) {
            for (int z = bz - reach; z <= bz + reach; z++) {
                double u = (x - cx) * cos + (z - cz) * sin;
                double v = -(x - cx) * sin + (z - cz) * cos;
                double rho = Math.sqrt(ForestSite.sq(u / rx) + ForestSite.sq(v / rz))
                        * (0.85 + 0.3 * site.noise(x, z, 2.5, salt));
                if (rho > 1) {
                    continue;
                }
                if (!usable(x, z, overlap)) {
                    return false;
                }
                int i = ForestSite.index(x, z);
                footprint[i] = true;
                if (plateau <= 0) {
                    heights[i] = Math.max(1, (int) Math.round(height * Math.pow(1 - rho * rho, 0.55)));
                    region[i] = true;
                } else if (rho <= plateau) {
                    heights[i] = height - (site.noise(x, z, 3, salt + 1) > 0.65 ? 1 : 0);
                    region[i] = true;
                } else {
                    heights[i] = Math.max(1, (int) Math.round(height * Math.sqrt((1 - rho) / (1 - plateau))));
                }
                count++;
            }
        }
        if (count == 0) {
            return false;
        }
        // The scree: bands of columns running out from the rock's core on the ramp sides.
        double halfWidth = Math.max(1.5, Math.min(rx, rz) * 0.5);
        int ramps = 0;
        for (double rampAngle : rampAngles) {
            double rampX = Math.cos(rampAngle);
            double rampZ = Math.sin(rampAngle);
            // How far the ellipse reaches in the ramp's direction; the scree runs on from there.
            double relative = rampAngle - rotation;
            double extent = 1 / Math.sqrt(ForestSite.sq(Math.cos(relative) / rx)
                    + ForestSite.sq(Math.sin(relative) / rz));
            List<Integer> scree = new ArrayList<>();
            boolean blocked = false;
            for (int x = bx - reach; x <= bx + reach && !blocked; x++) {
                for (int z = bz - reach; z <= bz + reach; z++) {
                    if (!ForestSite.inBox(x, z)) {
                        continue;
                    }
                    double along = (x - cx) * rampX + (z - cz) * rampZ;
                    double across = Math.abs(-(x - cx) * rampZ + (z - cz) * rampX);
                    if (along > 0 && across <= halfWidth && along <= extent * Math.max(plateau, 0.3) + height + 1) {
                        if (footprint[ForestSite.index(x, z)] || usable(x, z, true)) {
                            scree.add(ForestSite.index(x, z));
                        } else if (across < 1) {
                            blocked = true;
                            break;
                        }
                    }
                }
            }
            if (!blocked) {
                scree.forEach(i -> region[i] = true);
                ramps++;
            }
        }
        if (ramps == 0) {
            return false;
        }
        for (int i = 0; i < footprint.length; i++) {
            if (!footprint[i]) {
                continue;
            }
            int x = ForestSite.columnX(i);
            int z = ForestSite.columnZ(i);
            int g = site.ground(x, z);
            for (int y = g + 1; y <= g + heights[i]; y++) {
                site.set(x, y, z, y == g + heights[i] ? rockTop(x, y, z) : rock(x, y, z));
            }
        }
        site.raiseToSlope(region, null, site::stackTop, (x, y, z, top) -> top ? screeTop(x, y, z) : rock(x, y, z));
        for (int i = 0; i < region.length; i++) {
            if (region[i] || footprint[i]) {
                int x = ForestSite.columnX(i);
                int z = ForestSite.columnZ(i);
                site.mark(x, z, ForestSite.ROCK);
                site.setGround(x, z, site.stackTop(x, z));
                dress(x, z);
            }
        }
        return true;
    }

    /** A rounded boulder with scree on the sides facing {@code rampAngles}. */
    boolean boulder(double cx, double cz, double rx, double rz, double rotation, int height, boolean overlap,
                    double... rampAngles) {
        return rock(cx, cz, rx, rz, rotation, height, 0, overlap, rampAngles);
    }

    private boolean usable(int x, int z, boolean overlap) {
        if (!site.inZone(x, z) || ForestSite.edge(x, z) < 1.5) {
            return false;
        }
        byte use = site.use(x, z);
        return use == ForestSite.FREE || overlap && use == ForestSite.ROCK;
    }

    /** Two to four boulders of different sizes leaning together. */
    boolean cluster(double cx, double cz, double size) {
        int pieces = site.range(2, 4);
        double start = site.range(0, Math.PI * 2);
        for (int i = 0; i < pieces; i++) {
            double a = start + i * Math.PI * 2 / pieces + site.range(-0.4, 0.4);
            double d = i == 0 ? 0 : site.range(2.5, 3.5) * size;
            double rx = site.range(1.6, 2.6) * size;
            double rz = site.range(1.3, 2.0) * size;
            int height = i == 0 ? (int) Math.round(site.range(3.0, 4.0) * size) : site.range(1, 3);
            boolean built = boulder(cx + Math.cos(a) * d, cz + Math.sin(a) * d, rx, rz, site.range(0, Math.PI),
                    Math.max(1, height), i > 0, site.range(0, Math.PI * 2));
            if (i == 0 && !built) {
                return false;
            }
        }
        return true;
    }

    /**
     * The crag: a long flat-topped ridge of mossy rock {@code height} blocks high lying along {@code alongAngle}
     * (radians), with cliffs on its long sides, scree slopes up both ends and a cleft running through it.
     */
    void crag(int cx, int cz, double alongAngle, int height) {
        rock(cx, cz, 11, 6.5, alongAngle, height, 0.72, false, alongAngle, alongAngle + Math.PI);
        double ax = Math.cos(alongAngle);
        double az = Math.sin(alongAngle);
        double sx = -az;
        double sz = ax;
        // The cleft: a passage three wide and three to four high through the rock, a third of the way along.
        double px = cx - ax * 4;
        double pz = cz - az * 4;
        for (double s = -9; s <= 9; s += 0.5) {
            for (double w = -1; w <= 1; w += 0.5) {
                int x = (int) Math.round(px + sx * s + ax * w);
                int z = (int) Math.round(pz + sz * s + az * w);
                if (!site.inZone(x, z) || site.use(x, z) != ForestSite.ROCK) {
                    continue;
                }
                int g = CitadelLayout.GROUND_Y;
                int top = site.stackTop(x, z);
                int headroom = Math.abs(w) < 0.3 ? 4 : 3;
                if (top < g + headroom + 2) {
                    // Near the cliff faces the rock is too low to roof the passage: open it to the sky there.
                    for (int y = g + 1; y <= top; y++) {
                        site.clear(x, y, z);
                    }
                    site.setGround(x, z, g);
                } else {
                    for (int y = g + 1; y <= g + headroom; y++) {
                        site.clear(x, y, z);
                    }
                }
                site.set(x, g, z, BlockSpec.of(site.hash(x, z, 56) < 0.5 ? Material.COARSE_DIRT : Material.GRAVEL));
            }
        }
        // Lesser rocks at its feet.
        rock(cx + ax * 9 + sx * 8, cz + az * 9 + sz * 8, 2.6, 2.0, alongAngle, 3, 0, true, alongAngle);
        rock(cx - ax * 10 - sx * 8, cz - az * 10 - sz * 8, 2.2, 1.7, alongAngle + 1, 2, 0, true, alongAngle + 2);
        rock(cx + ax * 2 - sx * 9, cz + az * 2 - sz * 9, 2.0, 1.6, alongAngle, 2, 0, true, alongAngle + Math.PI);
        cairn((int) Math.round(cx - ax * 1.5), (int) Math.round(cz - az * 1.5));
    }

    /** A hunters' cairn on the crag's top: stacked mossy stones in three steps, a lantern on the lowest. */
    private void cairn(int x, int z) {
        int[][] steps = {{0, 0, 2}, {1, 0, 3}, {0, 1, 1}, {-1, 0, 1}};
        int base = site.stackTop(x, z);
        for (int[] s : steps) {
            int px = x + s[0];
            int pz = z + s[1];
            if (site.use(px, pz) != ForestSite.ROCK || site.stackTop(px, pz) != base) {
                return;
            }
        }
        for (int[] s : steps) {
            for (int h = 1; h <= s[2]; h++) {
                site.set(x + s[0], base + h, z + s[1], BlockSpec.of(h == s[2] ? Material.MOSSY_COBBLESTONE
                        : Material.COBBLESTONE));
            }
            site.setGround(x + s[0], z + s[1], base + s[2]);
        }
        site.set(x, base + 2, z + 1, BlockSpec.of(Material.LANTERN));
    }

    // ------------------------------------------------------------------ materials

    BlockSpec rock(int x, int y, int z) {
        double n = site.noise(x + y * 0.7, z - y * 0.5, 2.6, 51);
        double m = site.hash(x * 3 + y, z * 5 - y, 52);
        if (m < 0.08) {
            return BlockSpec.of(Material.COBBLESTONE);
        }
        if (n < 0.28) {
            return BlockSpec.of(Material.ANDESITE);
        }
        if (n < 0.40) {
            return BlockSpec.of(Material.MOSSY_COBBLESTONE);
        }
        if (n < 0.47) {
            return BlockSpec.of(Material.TUFF);
        }
        return BlockSpec.of(Material.STONE);
    }

    BlockSpec rockTop(int x, int y, int z) {
        double n = site.noise(x, z, 3.2, 53);
        if (n > 0.66) {
            return BlockSpec.of(Material.MOSS_BLOCK);
        }
        if (n > 0.5) {
            return BlockSpec.of(Material.MOSSY_COBBLESTONE);
        }
        return rock(x, y, z);
    }

    private BlockSpec screeTop(int x, int y, int z) {
        double n = site.hash(x, z, 54);
        if (n < 0.3) {
            return BlockSpec.of(Material.MOSSY_COBBLESTONE);
        }
        if (n < 0.5) {
            return BlockSpec.of(Material.COBBLESTONE);
        }
        if (n < 0.7) {
            return BlockSpec.of(Material.MOSS_BLOCK);
        }
        return rock(x, y, z);
    }

    /** Moss carpets and ferns on mossy tops (carpets only where no neighbour is lower, so they never add a step). */
    void dress(int x, int z) {
        int top = site.stackTop(x, z);
        BlockSpec at = site.get(x, top, z);
        if (at == null || !site.isAir(x, top + 1, z) || !site.isAir(x, top + 2, z)) {
            return;
        }
        if (at.material() == Material.MOSS_BLOCK) {
            double n = site.hash(x, z, 55);
            if (n < 0.35 && site.isLevel(x, z, top)) {
                site.set(x, top + 1, z, Material.MOSS_CARPET);
            } else if (n < 0.45) {
                site.set(x, top + 1, z, Material.FERN);
            } else if (n < 0.5) {
                site.set(x, top + 1, z, Material.SHORT_GRASS);
            }
        }
    }
}
