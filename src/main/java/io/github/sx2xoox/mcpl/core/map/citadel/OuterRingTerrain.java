package io.github.sx2xoox.mcpl.core.map.citadel;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import org.bukkit.Material;

/**
 * The ground of the outer ring: rubble mounds in the breaches, a flat apron outside the wall, then hills that rise to
 * a jagged rock rim, with passes where the roads and breaches lead out and a few crags (cliffs facing the citadel,
 * climbable from behind).
 *
 * <p>The ground is a height map. Before anything is placed it is relaxed so that neighbouring columns never differ by
 * more than one block: every hill can be walked up and down, and a player knocked out of the arena can always walk
 * back to a gate or breach. Only the crags (reached from the hillside behind them) and the rim (a barrier, never
 * reachable on foot) break that rule on purpose.
 */
final class OuterRingTerrain {

    enum Kind { NONE, GROUND, RUBBLE, STRUCTURE, CRAG, RIM }

    private static final int R = CitadelLayout.BUILD_RADIUS;
    private static final int GROUND = OuterRingPlan.GROUND;

    private final OuterRingPlan plan;
    private final OuterRingKit kit;
    private final int[][] height = new int[2 * R + 1][2 * R + 1];
    private final Kind[][] kind = new Kind[2 * R + 1][2 * R + 1];

    OuterRingTerrain(OuterRingPlan plan, OuterRingKit kit) {
        this.plan = plan;
        this.kit = kit;
    }

    // ------------------------------------------------------------------ queries

    /** Top block of the ground in a column (GROUND where the zone left it flat). */
    int height(int x, int z) {
        if (Math.abs(x) > R || Math.abs(z) > R) {
            return GROUND;
        }
        Kind k = kind[x + R][z + R];
        return k == null || k == Kind.NONE ? GROUND : height[x + R][z + R];
    }

    Kind kind(int x, int z) {
        if (Math.abs(x) > R || Math.abs(z) > R) {
            return Kind.NONE;
        }
        Kind k = kind[x + R][z + R];
        return k == null ? Kind.NONE : k;
    }

    // ------------------------------------------------------------------ shaping

    /** Computes the height map (no blocks yet). */
    void shape() {
        for (int x = -R; x <= R; x++) {
            for (int z = -R; z <= R; z++) {
                if (!CitadelLayout.isOuterRing(x, z)) {
                    kind[x + R][z + R] = Kind.NONE;
                    continue;
                }
                if (plan.structure(x, z)) {
                    kind[x + R][z + R] = Kind.STRUCTURE;
                    height[x + R][z + R] = GROUND;
                    continue;
                }
                double hills = hills(x, z);
                double rubble = rubble(x, z);
                kind[x + R][z + R] = rubble > 0.5 && rubble >= hills ? Kind.RUBBLE : Kind.GROUND;
                height[x + R][z + R] = GROUND + (int) Math.floor(Math.max(0, Math.max(hills, rubble)));
            }
        }
        flattenAprons();
        relax();
        // Breach columns where the broken wall stands higher than the rubble become wall again.
        for (int x = -R; x <= R; x++) {
            for (int z = -R; z <= R; z++) {
                Kind k = kind[x + R][z + R];
                if ((k == Kind.GROUND || k == Kind.RUBBLE) && OuterRingPlan.inWallBand(x, z)
                        && plan.wallTop(x, z) > height[x + R][z + R]) {
                    kind[x + R][z + R] = Kind.STRUCTURE;
                }
            }
        }
        crags();
        rim();
    }

    /** The hills outside the wall, in blocks above the ground. */
    private double hills(int x, int z) {
        double r = CitadelLayout.radius(x, z);
        double t = (r - 105) / 15.0;
        if (t <= 0) {
            return 0;
        }
        double rise = 10 * OuterRingKit.smooth(t);
        double scale = 0.75 + 0.45 * kit.fbm(x / 34.0, z / 34.0, 1);
        double bumps = (kit.fbm(x / 11.0, z / 11.0, 2) - 0.5) * 6 * Math.min(1, t * 1.5);
        return (rise * scale + bumps) * passFactor(x, z);
    }

    /** Lower ground (0..1) along the roads beyond the gates and in front of the breaches. */
    private double passFactor(int x, int z) {
        double factor = 1;
        for (OuterRingPlan.Gate gate : plan.gates) {
            if (OuterRingPlan.u(x, z, gate.angle()) <= 0) {
                continue;
            }
            double across = Math.abs(OuterRingPlan.v(x, z, gate.angle()));
            factor = Math.min(factor, 0.22 + 0.78 * OuterRingKit.smooth((across - 3) / 15));
        }
        for (OuterRingPlan.Breach breach : plan.breaches) {
            if (OuterRingPlan.u(x, z, breach.angle()) <= 0) {
                continue;
            }
            double across = Math.abs(OuterRingPlan.v(x, z, breach.angle()));
            factor = Math.min(factor, 0.45 + 0.55 * OuterRingKit.smooth((across - breach.halfWidth()) / 12));
        }
        return factor;
    }

    /** The rubble mound of a breach, in blocks above the ground. */
    private double rubble(int x, int z) {
        double best = 0;
        for (OuterRingPlan.Breach breach : plan.breaches) {
            double u = OuterRingPlan.u(x, z, breach.angle());
            if (u <= 0) {
                continue;
            }
            double across = Math.abs(OuterRingPlan.v(x, z, breach.angle())) / (breach.halfWidth() + 1.5);
            if (across >= 1) {
                continue;
            }
            double crest = breach.low() ? 3.6 : 4.8;
            // The big breaches spilled outwards: their slope is longer outside the wall.
            double center = breach.low() ? 100.5 : 101.5;
            double fall = u < center ? 1.05 : breach.low() ? 0.9 : 0.6;
            double height = crest * (1 - across * across) - Math.max(0, Math.abs(u - center) - 1) * fall
                    + (kit.noise(x / 2.5, z / 2.5, 3) - 0.5) * 1.6;
            best = Math.max(best, height);
        }
        return best;
    }

    /** Keeps the ground flat right next to towers, gatehouses and the wall's outer face. */
    private void flattenAprons() {
        for (int x = -R; x <= R; x++) {
            for (int z = -R; z <= R; z++) {
                if (kind[x + R][z + R] != Kind.STRUCTURE || OuterRingPlan.inWallBand(x, z) && plan.towerAt(x, z) == null
                        && plan.gateAt(x, z) == null) {
                    continue;
                }
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        int nx = x + dx;
                        int nz = z + dz;
                        if (Math.abs(nx) <= R && Math.abs(nz) <= R && kind[nx + R][nz + R] == Kind.GROUND) {
                            height[nx + R][nz + R] = GROUND;
                        }
                    }
                }
            }
        }
        // The gate passages and the roads beyond them stay low.
        for (int x = -R; x <= R; x++) {
            for (int z = -R; z <= R; z++) {
                Kind k = kind[x + R][z + R];
                if ((k == Kind.GROUND || k == Kind.RUBBLE) && plan.inPassage(x, z)) {
                    height[x + R][z + R] = GROUND;
                    kind[x + R][z + R] = Kind.GROUND;
                }
            }
        }
    }

    /**
     * Lowers the ground until no two neighbouring columns differ by more than one block. Columns inside the districts
     * count as flat ground; structures are skipped (they stand on flat ground of their own).
     */
    private void relax() {
        boolean changed = true;
        int passes = 0;
        while (changed && passes++ < 200) {
            changed = false;
            for (int x = -R; x <= R; x++) {
                for (int z = -R; z <= R; z++) {
                    changed |= relaxColumn(x, z);
                }
            }
            for (int x = R; x >= -R; x--) {
                for (int z = R; z >= -R; z--) {
                    changed |= relaxColumn(x, z);
                }
            }
        }
    }

    private boolean relaxColumn(int x, int z) {
        Kind k = kind[x + R][z + R];
        if (k != Kind.GROUND && k != Kind.RUBBLE) {
            return false;
        }
        int limit = Integer.MAX_VALUE;
        for (int[] d : SIDES) {
            int nx = x + d[0];
            int nz = z + d[1];
            if (Math.abs(nx) > R || Math.abs(nz) > R) {
                continue;
            }
            Kind nk = kind[nx + R][nz + R];
            if (nk == Kind.GROUND || nk == Kind.RUBBLE) {
                limit = Math.min(limit, height[nx + R][nz + R] + 1);
            } else if (nk == Kind.NONE && CitadelLayout.radius(nx, nz) <= CitadelLayout.DISTRICT_OUTER) {
                limit = Math.min(limit, GROUND + 1);
            }
        }
        if (height[x + R][z + R] > limit) {
            height[x + R][z + R] = limit;
            return true;
        }
        return false;
    }

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /**
     * Crags: flat-topped rocks on the hillside whose top is level with the slope behind them, so they show a cliff
     * towards the citadel and are climbed from the back.
     */
    private void crags() {
        // Between the towers and the passes of the roads and breaches, behind the towers' doors.
        double[] angles = {22, 52, 68, 97, 128, 172, 203, 233, 263, 292, 322, 352};
        for (int i = 0; i < angles.length; i++) {
            double angle = angles[i] + (kit.hash(i, 0, 31) - 0.5) * 3;
            double center = 113.5 + kit.hash(i, 1, 31) * 1.5;
            double radial = 2.5 + kit.hash(i, 2, 31) * 1.2;
            double across = 4 + kit.hash(i, 3, 31) * 4;
            int[] back = CitadelLayout.polar(center + radial + 1.5, angle);
            int top = height(back[0], back[1]) + 1;
            for (int x = -R; x <= R; x++) {
                for (int z = -R; z <= R; z++) {
                    double u = OuterRingPlan.u(x, z, angle) - center;
                    double v = OuterRingPlan.v(x, z, angle);
                    double n = (u / radial) * (u / radial) + (v / across) * (v / across);
                    if (n > 1 || OuterRingPlan.u(x, z, angle) <= 0) {
                        continue;
                    }
                    Kind k = kind(x, z);
                    if (k != Kind.GROUND) {
                        continue;
                    }
                    // Ragged edge: the very rim of the ellipse is a step lower.
                    int h = n > 0.75 && kit.hash(x, z, 33) < 0.5 ? top - 1 : top;
                    if (h > height[x + R][z + R]) {
                        height[x + R][z + R] = h;
                        kind[x + R][z + R] = Kind.CRAG;
                    }
                }
            }
        }
    }

    /** The rim: a jagged rock barrier along the island's edge, at least two blocks above the ground next to it. */
    private void rim() {
        for (int x = -R; x <= R; x++) {
            for (int z = -R; z <= R; z++) {
                Kind k = kind[x + R][z + R];
                if (k != Kind.GROUND && k != Kind.RUBBLE) {
                    continue;
                }
                double r = CitadelLayout.radius(x, z);
                double inner = 120.6 + 2.2 * kit.fbm(x / 7.0, z / 7.0, 41);
                if (r < inner) {
                    continue;
                }
                int base = height[x + R][z + R];
                double jag = kit.fbm(x / 5.0, z / 5.0, 43);
                int extra = 3 + (int) Math.floor(jag * 4) + (kit.hash(x, z, 45) < 0.06 ? 2 : 0)
                        + (r > inner + 1.5 ? 1 : 0);
                height[x + R][z + R] = base + extra;
                kind[x + R][z + R] = Kind.RIM;
            }
        }
    }

    // ------------------------------------------------------------------ blocks

    /** Places the ground blocks (and keeps the untouched columns as the base built them). */
    void build() {
        for (int x = -R; x <= R; x++) {
            for (int z = -R; z <= R; z++) {
                Kind k = kind[x + R][z + R];
                if (k == null || k == Kind.NONE || k == Kind.STRUCTURE) {
                    continue;
                }
                int h = height[x + R][z + R];
                if (k == Kind.GROUND && h == GROUND) {
                    surfaceOnly(x, z);
                    continue;
                }
                int drop = h - lowestNeighbour(x, z);
                kit.set(x, GROUND, z, k == Kind.RUBBLE ? kit.pick(OuterRingKit.RUBBLE, x, GROUND, z)
                        : BlockSpec.of(Material.DIRT));
                for (int y = GROUND + 1; y <= h; y++) {
                    kit.set(x, y, z, block(k, x, y, z, h, drop));
                }
            }
        }
        islandEdge();
    }

    /** The island's outer face: bare rock from the rim down to a ragged underside, instead of layered soil. */
    private void islandEdge() {
        for (int x = -R; x <= R; x++) {
            for (int z = -R; z <= R; z++) {
                double r = CitadelLayout.radius(x, z);
                if (r <= R - 2.5 || r > R || !kit.canWrite(x, z)) {
                    continue;
                }
                int bottom = GROUND - 12 - (int) Math.floor(kit.fbm(x / 4.0, z / 4.0, 47) * 6);
                for (int y = bottom; y <= GROUND; y++) {
                    kit.set(x, y, z, kit.pick(OuterRingKit.ROCK, x, y, z));
                }
            }
        }
    }

    private BlockSpec block(Kind k, int x, int y, int z, int top, int drop) {
        int depth = top - y;
        switch (k) {
            case RUBBLE -> {
                if (depth == 0 && drop >= 1 && kit.hash(x, z, 51) < 0.22) {
                    Material slab = kit.hash(x, z, 52) < 0.5 ? Material.COBBLESTONE_SLAB
                            : Material.STONE_BRICK_SLAB;
                    return Blocks.slab(slab);
                }
                return kit.pick(OuterRingKit.RUBBLE, x, y, z);
            }
            case RIM -> {
                if (depth == 0 && kit.hash(x, z, 53) < 0.18) {
                    return BlockSpec.of(kit.hash(x, z, 54) < 0.5 ? Material.MOSS_BLOCK : Material.COARSE_DIRT);
                }
                return kit.pick(OuterRingKit.ROCK, x, y, z);
            }
            case CRAG -> {
                if (depth == 0 && drop < 2 && kit.hash(x, z, 55) < 0.45) {
                    return BlockSpec.of(kit.hash(x, z, 56) < 0.6 ? Material.GRASS_BLOCK : Material.MOSS_BLOCK);
                }
                return kit.pick(OuterRingKit.ROCK, x, y, z);
            }
            default -> {
                if (drop >= 2 && depth <= drop) {
                    return kit.pick(OuterRingKit.ROCK, x, y, z);
                }
                if (depth == 0) {
                    return surface(x, z, drop);
                }
                return depth <= 3 ? BlockSpec.of(Material.DIRT) : BlockSpec.of(Material.STONE);
            }
        }
    }

    /** Grass with patches of coarse dirt, podzol, moss and bare rock where the slope is steep. */
    private BlockSpec surface(int x, int z, int drop) {
        double patch = kit.noise(x / 6.0, z / 6.0, 61);
        double pick = kit.hash(x, z, 62);
        if (drop >= 1 && kit.noise(x / 4.0, z / 4.0, 64) > 0.62) {
            return kit.pick(OuterRingKit.ROCK, x, 0, z);
        }
        if (patch > 0.72) {
            return BlockSpec.of(pick < 0.5 ? Material.PODZOL : Material.COARSE_DIRT);
        }
        if (patch < 0.2) {
            return BlockSpec.of(pick < 0.6 ? Material.MOSS_BLOCK : Material.GRASS_BLOCK);
        }
        if (pick < 0.03) {
            return BlockSpec.of(Material.ANDESITE);
        }
        return BlockSpec.of(Material.GRASS_BLOCK);
    }

    /** Untouched flat columns: a worn strip along the wall's feet, the base's grass elsewhere. */
    private void surfaceOnly(int x, int z) {
        double r = CitadelLayout.radius(x, z);
        boolean foot = r > OuterRingPlan.WALL_OUT - 0.5 && r < OuterRingPlan.WALL_OUT + 1.5
                || r < OuterRingPlan.WALL_IN && r > OuterRingPlan.WALL_IN - 1.5;
        double pick = kit.hash(x, z, 63);
        if (foot && !plan.inPassage(x, z) && CitadelLayout.nearestRoadDistance(x, z) > CitadelLayout.ROAD_HALF_WIDTH) {
            if (pick < 0.25) {
                kit.set(x, GROUND, z, Material.COARSE_DIRT);
            } else if (pick < 0.37) {
                kit.set(x, GROUND, z, Material.MOSS_BLOCK);
            } else if (pick < 0.45) {
                kit.set(x, GROUND, z, Material.GRAVEL);
            }
        } else if (r > OuterRingPlan.WALL_OUT && kit.noise(x / 6.0, z / 6.0, 61) > 0.75 && pick < 0.5) {
            kit.set(x, GROUND, z, Material.COARSE_DIRT);
        }
    }

    private int lowestNeighbour(int x, int z) {
        int low = Integer.MAX_VALUE;
        for (int[] d : SIDES) {
            int nx = x + d[0];
            int nz = z + d[1];
            if (CitadelLayout.radius(nx, nz) > R) {
                continue;
            }
            Kind nk = kind(nx, nz);
            low = Math.min(low, nk == Kind.STRUCTURE ? GROUND : height(nx, nz));
        }
        return low == Integer.MAX_VALUE ? height(x, z) : low;
    }
}
