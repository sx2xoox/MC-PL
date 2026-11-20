package io.github.sx2xoox.mcpl.core.map.citadel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.MapBlocks;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.jspecify.annotations.Nullable;

/**
 * The forest's tree crowns. Every leaf and branch of a crown is registered here, so that once all trees stand the
 * canopy can be finished as one walkable roof: gaps inside crowns are filled, every crown top is shaped so neighbouring
 * columns differ by at most one block (players who fly or climb up can walk across), and every larger patch of canopy
 * gets ladders up a trunk, the hunters' old tree-stand ladders, so nobody is stuck up there.
 */
final class ForestCanopy {

    /** Free air below every crown, in blocks above the ground. */
    static final int CLEARANCE = 4;
    /** Canopy patches at least this large (columns) get a ladder. */
    private static final int LADDER_MIN_PATCH = 10;
    /** One more ladder per this many columns of a patch. */
    private static final int COLUMNS_PER_LADDER = 260;

    private static final int NONE = Integer.MAX_VALUE;
    private static final BlockFace[] FACES = {BlockFace.EAST, BlockFace.WEST, BlockFace.SOUTH, BlockFace.NORTH};

    /** A trunk ladders can be fixed to: its columns and how much a ladder on it is wanted. */
    record Trunk(int[] columns, int priority) {
    }

    private final ForestSite site;
    private final int[] bottom = new int[ForestSite.columns()];
    private final Material[] leaf = new Material[ForestSite.columns()];
    private final boolean[] fixed = new boolean[ForestSite.columns()];
    private final boolean[] excluded = new boolean[ForestSite.columns()];
    private final boolean[] trunkColumn = new boolean[ForestSite.columns()];
    private final int[] ceiling = new int[ForestSite.columns()];
    private final int[] floor = new int[ForestSite.columns()];
    private final List<Trunk> trunks = new ArrayList<>();

    ForestCanopy(ForestSite site) {
        this.site = site;
        Arrays.fill(bottom, NONE);
        Arrays.fill(ceiling, NONE);
        Arrays.fill(floor, Integer.MIN_VALUE);
    }

    // ------------------------------------------------------------------ building crowns

    /** Places a crown leaf if the position is air and high enough above the ground; returns true if placed. */
    boolean leaves(int x, int y, int z, Material material) {
        if (!site.inZone(x, z) || y < site.ground(x, z) + CLEARANCE + 1 || !site.isAir(x, y, z)
                || y > ceiling[ForestSite.index(x, z)] || y < floor[ForestSite.index(x, z)]) {
            return false;
        }
        site.set(x, y, z, BlockSpec.of(material));
        register(x, y, z, material);
        return true;
    }

    /** Places a branch (log or wood) of a crown, replacing leaves. */
    void wood(int x, int y, int z, BlockSpec spec) {
        if (!site.inZone(x, z) || y < site.ground(x, z) + CLEARANCE + 1 || y > ceiling[ForestSite.index(x, z)]
                || y < floor[ForestSite.index(x, z)]) {
            return;
        }
        BlockSpec current = site.get(x, y, z);
        if (current != null && !current.isAir() && !current.material().name().endsWith("_LEAVES")) {
            return;
        }
        site.set(x, y, z, spec);
        register(x, y, z, null);
    }

    /** A filled ellipsoid of leaves with a ragged rim ({@code ragged} 0..1). */
    void blob(double cx, double cy, double cz, double rx, double ry, double rz, Material material, double ragged) {
        int ix = (int) Math.ceil(rx);
        int iy = (int) Math.ceil(ry);
        int iz = (int) Math.ceil(rz);
        int bx = (int) Math.round(cx);
        int by = (int) Math.round(cy);
        int bz = (int) Math.round(cz);
        for (int dx = -ix; dx <= ix; dx++) {
            for (int dy = -iy; dy <= iy; dy++) {
                for (int dz = -iz; dz <= iz; dz++) {
                    double n = ForestSite.sq((bx + dx - cx) / (rx + 0.5)) + ForestSite.sq((by + dy - cy) / (ry + 0.5))
                            + ForestSite.sq((bz + dz - cz) / (rz + 0.5));
                    if (n <= 1.0 - ragged * site.random.nextDouble() * n) {
                        leaves(bx + dx, by + dy, bz + dz, material);
                    }
                }
            }
        }
    }

    private void register(int x, int y, int z, @Nullable Material material) {
        int i = ForestSite.index(x, z);
        bottom[i] = Math.min(bottom[i], y);
        if (material != null) {
            leaf[i] = material;
        }
    }

    /** Counts a trunk's top as the bottom of the crown above it, so the gap between them is filled. */
    void anchor(int x, int y, int z) {
        if (site.inZone(x, z)) {
            register(x, y, z, null);
        }
    }

    /** A trunk that may carry a ladder; {@code priority} favours big old trees. */
    void trunk(int[] columns, int priority) {
        trunks.add(new Trunk(columns, priority));
        for (int column : columns) {
            trunkColumn[column] = true;
        }
    }

    /**
     * A column of a designed surface (the hollow tree's bowl) that the finish never raises or thins; it still shapes
     * the canopy around it. A patch with such a column has its own way up, so it gets no ladder.
     */
    void fix(int x, int z) {
        if (ForestSite.inBox(x, z)) {
            fixed[ForestSite.index(x, z)] = true;
        }
    }

    /** No crown block may grow above {@code maxY} in this column (decks, roofs, the inside of a hollow trunk). */
    void limit(int x, int z, int maxY) {
        if (ForestSite.inBox(x, z)) {
            int i = ForestSite.index(x, z);
            ceiling[i] = Math.min(ceiling[i], maxY);
        }
    }

    /** No crown block may grow below {@code minY} in this column (the space over a deck). */
    void floor(int x, int z, int minY) {
        if (ForestSite.inBox(x, z)) {
            int i = ForestSite.index(x, z);
            floor[i] = Math.max(floor[i], minY);
        }
    }

    /** A column the canopy finish must leave alone (a stairwell, a shaft). */
    void exclude(int x, int z) {
        if (ForestSite.inBox(x, z)) {
            excluded[ForestSite.index(x, z)] = true;
        }
    }

    boolean isCanopy(int x, int z) {
        return ForestSite.inBox(x, z) && bottom[ForestSite.index(x, z)] != NONE;
    }

    // ------------------------------------------------------------------ finishing

    /** Fills the crowns, shapes their tops into walkable slopes and adds ladders. Call after every tree stands. */
    void finish() {
        boolean[] region = new boolean[ForestSite.columns()];
        for (int i = 0; i < region.length; i++) {
            region[i] = bottom[i] != NONE && !excluded[i];
        }
        fillGaps(region);
        site.raiseToSlope(region, fixed, site::top, (x, y, z, top) -> BlockSpec.of(leafOf(x, z)));
        trimUndersides(region);
        addLadders(region);
    }

    /**
     * Thins every crown towards its rim from below, so crowns end in rounded lenses instead of walls of leaves. Only
     * leaves are removed, from the underside up, so tops (and walkability) stay as they are.
     */
    private void trimUndersides(boolean[] region) {
        int[] depth = new int[region.length];
        Arrays.fill(depth, NONE);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < region.length; i++) {
            if (!inCrown(region, i)) {
                continue;
            }
            int x = ForestSite.columnX(i);
            int z = ForestSite.columnZ(i);
            for (int[] side : ForestSite.SIDES) {
                int nx = x + side[0];
                int nz = z + side[1];
                if (!ForestSite.inBox(nx, nz) || !inCrown(region, ForestSite.index(nx, nz))) {
                    depth[i] = 1;
                    queue.add(i);
                    break;
                }
            }
        }
        while (!queue.isEmpty()) {
            int i = queue.poll();
            int x = ForestSite.columnX(i);
            int z = ForestSite.columnZ(i);
            for (int[] side : ForestSite.SIDES) {
                int nx = x + side[0];
                int nz = z + side[1];
                if (!ForestSite.inBox(nx, nz)) {
                    continue;
                }
                int j = ForestSite.index(nx, nz);
                if (inCrown(region, j) && depth[j] > depth[i] + 1) {
                    depth[j] = depth[i] + 1;
                    queue.add(j);
                }
            }
        }
        for (int i = 0; i < region.length; i++) {
            if (!region[i] || fixed[i]) {
                continue;
            }
            int x = ForestSite.columnX(i);
            int z = ForestSite.columnZ(i);
            int keep = 1 + depth[i] + depth[i] / 2;
            int top = site.top(x, z);
            // Only where open air hangs below the crown: trimming above a branch or a deck would leave a pocket.
            if (!site.isOpen(x, bottom[i] - 1, z) || !site.isOpen(x, bottom[i] - 2, z)) {
                continue;
            }
            for (int y = bottom[i]; y <= top - keep; y++) {
                BlockSpec spec = site.get(x, y, z);
                if (spec != null && spec.material().name().endsWith("_LEAVES")) {
                    site.clear(x, y, z);
                } else if (spec != null && !spec.isAir() && !MapBlocks.isPassable(spec)) {
                    break;
                }
            }
        }
    }

    private boolean inCrown(boolean[] region, int i) {
        return region[i] || excluded[i] && bottom[i] != NONE;
    }

    private Material leafOf(int x, int z) {
        Material material = leaf[ForestSite.index(x, z)];
        return material != null ? material : Material.OAK_LEAVES;
    }

    /**
     * Fills the air pockets inside crown columns that a player would fit in (two or more blocks high), so nobody can
     * get trapped inside a crown. One-block gaps stay: they keep the tiers of the crowns visible.
     */
    private void fillGaps(boolean[] region) {
        for (int i = 0; i < region.length; i++) {
            if (!region[i]) {
                continue;
            }
            int x = ForestSite.columnX(i);
            int z = ForestSite.columnZ(i);
            int top = site.top(x, z);
            BlockSpec fill = BlockSpec.of(leafOf(x, z));
            int run = 0;
            for (int y = bottom[i]; y <= top; y++) {
                BlockSpec spec = site.get(x, y, z);
                if (y < top && (spec == null || spec.isAir() || MapBlocks.isPassable(spec))) {
                    run++;
                    continue;
                }
                if (run >= 2) {
                    for (int fy = y - run; fy < y; fy++) {
                        site.set(x, fy, z, fill);
                    }
                }
                run = 0;
            }
        }
    }

    /** Gives every canopy patch large enough to matter one or more ladders up one of its trunks. */
    private void addLadders(boolean[] region) {
        int[] patch = new int[region.length];
        Arrays.fill(patch, -1);
        int patches = 0;
        for (int start = 0; start < region.length; start++) {
            if (!region[start] || patch[start] >= 0) {
                continue;
            }
            List<Integer> members = new ArrayList<>();
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(start);
            patch[start] = patches;
            while (!queue.isEmpty()) {
                int i = queue.poll();
                members.add(i);
                int x = ForestSite.columnX(i);
                int z = ForestSite.columnZ(i);
                for (int[] side : ForestSite.SIDES) {
                    int nx = x + side[0];
                    int nz = z + side[1];
                    if (!ForestSite.inBox(nx, nz)) {
                        continue;
                    }
                    int j = ForestSite.index(nx, nz);
                    if (region[j] && patch[j] < 0) {
                        patch[j] = patches;
                        queue.add(j);
                    }
                }
            }
            if (members.size() >= LADDER_MIN_PATCH && !hasFixed(members)) {
                int wanted = 1 + members.size() / COLUMNS_PER_LADDER;
                placeLadders(patches, patch, wanted);
            }
            patches++;
        }
    }

    private boolean hasFixed(List<Integer> members) {
        for (int i : members) {
            if (fixed[i]) {
                return true;
            }
        }
        return false;
    }

    /** Picks up to {@code wanted} ladder spots on the patch's trunks, spread apart, and builds them. */
    private void placeLadders(int id, int[] patch, int wanted) {
        List<int[]> candidates = new ArrayList<>();
        for (Trunk trunk : trunks) {
            for (int column : trunk.columns()) {
                int tx = ForestSite.columnX(column);
                int tz = ForestSite.columnZ(column);
                for (int f = 0; f < FACES.length; f++) {
                    int lx = tx + ForestSite.SIDES[f][0];
                    int lz = tz + ForestSite.SIDES[f][1];
                    if (!ForestSite.inBox(lx, lz)) {
                        continue;
                    }
                    int l = ForestSite.index(lx, lz);
                    if (patch[l] != id || trunkColumn[l] || !site.isBareGround(lx, lz)
                            || site.ground(lx, lz) != site.ground(tx, tz) || !hasLanding(lx, lz, tx, tz, id, patch)
                            || !approachable(lx, lz)) {
                        continue;
                    }
                    candidates.add(new int[] {lx, lz, f, trunk.priority(), tx, tz});
                }
            }
        }
        List<int[]> chosen = new ArrayList<>();
        while (chosen.size() < wanted && !candidates.isEmpty()) {
            int[] best = null;
            double bestScore = -1;
            for (int[] c : candidates) {
                double spread = 40;
                for (int[] other : chosen) {
                    spread = Math.min(spread, Math.hypot(c[0] - other[0], c[1] - other[1]));
                }
                double score = spread * 10 + c[3] + site.hash(c[0], c[1], 77);
                if (!chosen.isEmpty() && spread < 12) {
                    continue;
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = c;
                }
            }
            if (best == null) {
                break;
            }
            chosen.add(best);
        }
        for (int[] c : chosen) {
            buildLadder(c[0], c[1], FACES[c[2]], c[4], c[5], id, patch);
        }
    }

    /** True if a neighbour of the ladder column (not the trunk) belongs to the patch: the place to step off. */
    private boolean hasLanding(int lx, int lz, int tx, int tz, int id, int[] patch) {
        for (int[] side : ForestSite.SIDES) {
            int nx = lx + side[0];
            int nz = lz + side[1];
            if (nx == tx && nz == tz || !ForestSite.inBox(nx, nz)) {
                continue;
            }
            int j = ForestSite.index(nx, nz);
            if (patch[j] == id && !trunkColumn[j]) {
                return true;
            }
        }
        return false;
    }

    /** True if a player can walk up to the foot of the ladder from a neighbouring column. */
    private boolean approachable(int lx, int lz) {
        int g = site.ground(lx, lz);
        for (int[] side : ForestSite.SIDES) {
            int nx = lx + side[0];
            int nz = lz + side[1];
            if (site.inZone(nx, nz) && !trunkColumn[ForestSite.index(nx, nz)] && site.isBareGround(nx, nz)
                    && Math.abs(site.ground(nx, nz) - g) <= 1) {
                return true;
            }
        }
        return false;
    }

    private void buildLadder(int lx, int lz, BlockFace facing, int tx, int tz, int id, int[] patch) {
        int landing = Integer.MAX_VALUE;
        for (int[] side : ForestSite.SIDES) {
            int nx = lx + side[0];
            int nz = lz + side[1];
            if (nx == tx && nz == tz || !ForestSite.inBox(nx, nz)) {
                continue;
            }
            int j = ForestSite.index(nx, nz);
            if (patch[j] == id && !trunkColumn[j]) {
                landing = Math.min(landing, site.top(nx, nz));
            }
        }
        int g = site.ground(lx, lz);
        int top = site.top(lx, lz);
        for (int y = g + 1; y <= Math.max(top, landing + 2); y++) {
            site.clear(lx, y, lz);
        }
        BlockSpec ladder = BlockSpec.of(Material.LADDER).facing(facing);
        for (int y = g + 1; y <= landing; y++) {
            site.set(lx, y, lz, ladder);
            // The trunk behind the ladder: keep it solid so the ladder hangs on wood or leaves.
            if (site.isAir(tx, y, tz)) {
                site.set(tx, y, tz, BlockSpec.of(leafOf(tx, tz)));
            }
        }
        excluded[ForestSite.index(lx, lz)] = true;
    }
}
