package io.github.sx2xoox.mcpl.core.map.citadel;

import java.util.ArrayList;
import java.util.List;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import org.bukkit.Axis;
import org.bukkit.Material;

/**
 * The forest's trees, all hand-shaped rather than vanilla: oaks with lumpy crowns, slender birches, tiered firs,
 * umbrella pines, broad dark oaks on twin trunks, huge old trees with root flares and long branches, dead snags,
 * bushes and stumps. Crowns go through {@link ForestCanopy} so they always start above head height and end up
 * walkable; their tops never rise faster than one block per column (a fir's cap is a 45° cone).
 */
final class ForestTrees {

    enum Kind { OAK, BIRCH, FIR, PINE, DARK_OAK, GIANT_OAK, GIANT_DARK_OAK, SNAG }

    /** A placed tree: trunk position and how far its crown reaches. */
    record Tree(Kind kind, int x, int z, double crown) {
    }

    private final ForestSite site;
    private final ForestCanopy canopy;
    private final List<Tree> trees = new ArrayList<>();

    ForestTrees(ForestSite site) {
        this.site = site;
        this.canopy = site.canopy();
    }

    List<Tree> trees() {
        return trees;
    }

    /** How far the crown of a tree of {@code kind} reaches from its trunk (for spacing and edge checks). */
    static double crownRadius(Kind kind) {
        return switch (kind) {
            case OAK -> 5;
            case BIRCH -> 2.8;
            case FIR -> 3.4;
            case PINE -> 3.6;
            case DARK_OAK -> 6;
            case GIANT_OAK, GIANT_DARK_OAK -> 10;
            case SNAG -> 2.5;
        };
    }

    /** Trunk radius kept free around the trunk position. */
    static double trunkRadius(Kind kind) {
        return switch (kind) {
            case DARK_OAK -> 1.6;
            case GIANT_OAK, GIANT_DARK_OAK -> 3.2;
            default -> 1;
        };
    }

    void grow(Kind kind, int x, int z) {
        switch (kind) {
            case OAK -> oak(x, z);
            case BIRCH -> birch(x, z);
            case FIR -> fir(x, z);
            case PINE -> pine(x, z);
            case DARK_OAK -> darkOak(x, z);
            case GIANT_OAK -> giant(x, z, Material.OAK_WOOD, Material.OAK_LOG, Material.OAK_LEAVES);
            case GIANT_DARK_OAK -> giant(x, z, Material.DARK_OAK_WOOD, Material.DARK_OAK_LOG,
                    Material.DARK_OAK_LEAVES);
            case SNAG -> snag(x, z);
        }
        trees.add(new Tree(kind, x, z, crownRadius(kind)));
        site.markDisk(x, z, trunkRadius(kind), ForestSite.TRUNK, true);
    }

    // ------------------------------------------------------------------ species

    private void oak(int x, int z) {
        int g = site.ground(x, z);
        int height = site.range(6, 8);
        column(x, z, g + 1, g + height, Material.OAK_LOG);
        rootKnots(x, z, g, Material.OAK_WOOD, 2);
        int cy = g + height;
        Material accent = site.chance(0.15) ? Material.AZALEA_LEAVES : Material.OAK_LEAVES;
        canopy.blob(x + 0.5 * site.range(-1.0, 1.0), cy, z + 0.5 * site.range(-1.0, 1.0), site.range(3.0, 3.6),
                site.range(2.2, 2.7), site.range(3.0, 3.6), Material.OAK_LEAVES, 0.25);
        int lobes = site.range(2, 3);
        double start = site.range(0, Math.PI * 2);
        for (int i = 0; i < lobes; i++) {
            double a = start + i * Math.PI * 2 / lobes + site.range(-0.4, 0.4);
            double dx = Math.cos(a) * site.range(2.2, 3.0);
            double dz = Math.sin(a) * site.range(2.2, 3.0);
            branch(x, cy - 2, z, (int) Math.round(x + dx), cy - 1 + site.range(0, 1), (int) Math.round(z + dz),
                    Material.OAK_LOG);
            canopy.blob(x + dx, cy + site.range(0, 1), z + dz, site.range(1.9, 2.5), site.range(1.6, 2.0),
                    site.range(1.9, 2.5), i == 0 ? accent : Material.OAK_LEAVES, 0.3);
        }
        canopy.trunk(new int[] {ForestSite.index(x, z)}, 2);
    }

    private void birch(int x, int z) {
        int g = site.ground(x, z);
        int height = site.range(7, 10);
        column(x, z, g + 1, g + height, Material.BIRCH_LOG);
        double rx = site.range(1.7, 2.2);
        canopy.blob(x, g + height - 0.5, z, rx, site.range(2.4, 3.0), rx, Material.BIRCH_LEAVES, 0.15);
        if (site.chance(0.5)) {
            double a = site.range(0, Math.PI * 2);
            canopy.blob(x + Math.cos(a) * 1.5, g + height - 2.5, z + Math.sin(a) * 1.5, 1.3, 1.2, 1.3,
                    Material.BIRCH_LEAVES, 0.2);
        }
        canopy.trunk(new int[] {ForestSite.index(x, z)}, 1);
    }

    /**
     * A fir: a pointed cap whose radius grows one block per layer (so its top can be walked), then tiers of
     * alternating width down to head height.
     */
    private void fir(int x, int z) {
        int g = site.ground(x, z);
        int height = site.range(10, 13);
        column(x, z, g + 1, g + height, Material.SPRUCE_LOG);
        int radius = site.chance(0.5) ? 3 : 2;
        int tip = g + height + 1;
        int bottom = g + Math.max(ForestCanopy.CLEARANCE + 1, height - 6);
        for (int y = tip; y >= bottom; y--) {
            int k = tip - y;
            double r = k <= radius ? k : (k - radius) % 2 == 1 ? radius - 0.6 : radius;
            ring(x, y, z, r, Material.SPRUCE_LEAVES);
        }
        litter(x, z, 3.5, Material.PODZOL);
        canopy.trunk(new int[] {ForestSite.index(x, z)}, 1);
    }

    /** A pine: a tall bare trunk with a flat, lumpy crown of dark needles. */
    private void pine(int x, int z) {
        int g = site.ground(x, z);
        int height = site.range(10, 13);
        column(x, z, g + 1, g + height, Material.SPRUCE_LOG);
        int cy = g + height;
        canopy.blob(x, cy, z, site.range(2.4, 3.0), 1.2, site.range(2.4, 3.0), Material.SPRUCE_LEAVES, 0.2);
        for (int i = 0; i < 3; i++) {
            double a = site.range(0, Math.PI * 2);
            double dx = Math.cos(a) * site.range(1.5, 2.5);
            double dz = Math.sin(a) * site.range(1.5, 2.5);
            branch(x, cy - 2 - i, z, (int) Math.round(x + dx), cy - 1 - i, (int) Math.round(z + dz),
                    Material.SPRUCE_LOG);
            canopy.blob(x + dx, cy - 1 - i, z + dz, site.range(1.4, 1.9), 0.9, site.range(1.4, 1.9),
                    Material.SPRUCE_LEAVES, 0.25);
        }
        litter(x, z, 3, Material.PODZOL);
        canopy.trunk(new int[] {ForestSite.index(x, z)}, 1);
    }

    private void darkOak(int x, int z) {
        int g = site.ground(x, z);
        int height = site.range(6, 7);
        int[] columns = new int[4];
        int n = 0;
        for (int dx = 0; dx <= 1; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                int lift = dx + dz == 1 && site.chance(0.5) ? 1 : 0;
                column(x + dx, z + dz, g + 1, g + height + lift, Material.DARK_OAK_LOG);
                columns[n++] = ForestSite.index(x + dx, z + dz);
            }
        }
        rootKnots(x, z, g, Material.DARK_OAK_WOOD, 3);
        double cx = x + 0.5;
        double cz = z + 0.5;
        int cy = g + height + 1;
        canopy.blob(cx, cy, cz, site.range(4.0, 4.6), site.range(1.6, 2.0), site.range(4.0, 4.6),
                Material.DARK_OAK_LEAVES, 0.25);
        int lobes = 3;
        double start = site.range(0, Math.PI * 2);
        for (int i = 0; i < lobes; i++) {
            double a = start + i * Math.PI * 2 / lobes + site.range(-0.3, 0.3);
            double dx = Math.cos(a) * site.range(3.0, 3.6);
            double dz = Math.sin(a) * site.range(3.0, 3.6);
            branch(x, cy - 2, z, (int) Math.round(cx + dx), cy - 1, (int) Math.round(cz + dz), Material.DARK_OAK_LOG);
            canopy.blob(cx + dx, cy + site.range(0, 1), cz + dz, site.range(2.2, 2.8), site.range(1.5, 1.9),
                    site.range(2.2, 2.8), Material.DARK_OAK_LEAVES, 0.3);
        }
        litter(x, z, 4.5, Material.PODZOL);
        canopy.trunk(columns, 3);
    }

    /** A huge old tree: a thick round trunk, root flares on an earthen mound, long branches and a wide crown. */
    private void giant(int x, int z, Material wood, Material log, Material leaves) {
        int g = site.ground(x, z);
        int height = site.range(13, 15);
        double radius = site.range(1.7, 2.1);
        List<Integer> columns = new ArrayList<>();
        int r = (int) Math.ceil(radius);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > radius + 0.3) {
                    continue;
                }
                // The trunk narrows a little towards the top.
                int columnTop = g + height - (int) Math.round(Math.max(0, d - radius + 1.2) * 4);
                column(x + dx, z + dz, g + 1, columnTop, wood);
                canopy.anchor(x + dx, columnTop, z + dz);
                columns.add(ForestSite.index(x + dx, z + dz));
            }
        }
        roots(x, z, radius, wood, columns);
        // Branches with crown lobes at their ends, and a crown over the trunk.
        int branches = site.range(5, 6);
        double start = site.range(0, Math.PI * 2);
        for (int i = 0; i < branches; i++) {
            double a = start + i * Math.PI * 2 / branches + site.range(-0.3, 0.3);
            double reach = site.range(5.0, 6.5);
            int by = g + (int) Math.round(height * site.range(0.6, 0.8));
            double ex = x + Math.cos(a) * reach;
            double ez = z + Math.sin(a) * reach;
            int ey = by + site.range(2, 3);
            branch(x, by, z, (int) Math.round(ex), ey, (int) Math.round(ez), log);
            canopy.blob(ex, ey + 1, ez, site.range(3.0, 3.6), site.range(2.0, 2.4), site.range(3.0, 3.6), leaves,
                    0.3);
        }
        canopy.blob(x, g + height + 1, z, site.range(3.8, 4.4), site.range(2.4, 2.8), site.range(3.8, 4.4), leaves,
                0.2);
        litter(x, z, 6, Material.PODZOL);
        int[] trunk = new int[columns.size()];
        for (int i = 0; i < trunk.length; i++) {
            trunk[i] = columns.get(i);
        }
        canopy.trunk(trunk, 6);
    }

    /**
     * Root flares running out from a big trunk, and the earth heaped between them: the whole foot of the tree becomes
     * a mound whose every column can be climbed from the next.
     */
    private void roots(int x, int z, double radius, Material wood, List<Integer> trunk) {
        boolean[] region = new boolean[ForestSite.columns()];
        int roots = site.range(6, 8);
        double start = site.range(0, Math.PI * 2);
        for (int i = 0; i < roots; i++) {
            double a = start + i * Math.PI * 2 / roots + site.range(-0.25, 0.25);
            double length = site.range(3.0, 5.0);
            for (double s = radius; s <= radius + length; s += 0.5) {
                int rx = (int) Math.round(x + Math.cos(a) * s);
                int rz = (int) Math.round(z + Math.sin(a) * s);
                if (!site.inZone(rx, rz) || trunk.contains(ForestSite.index(rx, rz))) {
                    continue;
                }
                if (site.use(rx, rz) != ForestSite.FREE && site.use(rx, rz) != ForestSite.TRUNK
                        || !site.openAround(rx, rz)) {
                    break;
                }
                int rg = site.ground(rx, rz);
                int h = s < radius + length * 0.45 ? 2 : 1;
                for (int y = rg + 1; y <= rg + h; y++) {
                    if (site.isAir(rx, y, rz)) {
                        site.set(rx, y, rz, BlockSpec.of(wood));
                    }
                }
                site.mark(rx, rz, ForestSite.TRUNK);
            }
        }
        double reach = radius + 6;
        int ir = (int) Math.ceil(reach);
        for (int dx = -ir; dx <= ir; dx++) {
            for (int dz = -ir; dz <= ir; dz++) {
                int mx = x + dx;
                int mz = z + dz;
                if (Math.sqrt(dx * dx + dz * dz) > reach || !site.inZone(mx, mz)
                        || trunk.contains(ForestSite.index(mx, mz))) {
                    continue;
                }
                if (site.isMoundable(mx, mz)) {
                    region[ForestSite.index(mx, mz)] = true;
                }
            }
        }
        site.raiseToSlope(region, null, site::stackTop,
                (fx, fy, fz, top) -> top ? mound(fx, fz) : BlockSpec.of(Material.DIRT));
        // The mound is the new ground (but not the root ridges themselves: nothing grows on them).
        for (int i = 0; i < region.length; i++) {
            if (region[i]) {
                int mx = ForestSite.columnX(i);
                int mz = ForestSite.columnZ(i);
                BlockSpec top = site.get(mx, site.stackTop(mx, mz), mz);
                if (top != null && !top.material().name().endsWith("_WOOD")) {
                    site.setGround(mx, mz, site.stackTop(mx, mz));
                }
            }
        }
    }

    private BlockSpec mound(int x, int z) {
        double n = site.hash(x, z, 31);
        return BlockSpec.of(n < 0.35 ? Material.PODZOL : n < 0.6 ? Material.ROOTED_DIRT : n < 0.8
                ? Material.MOSS_BLOCK : Material.GRASS_BLOCK);
    }

    /**
     * A dead tree: a bare trunk broken off a few blocks up, its fallen pieces piled against it in a rough stair (so
     * its top can be climbed).
     */
    private void snag(int x, int z) {
        int g = site.ground(x, z);
        int height = site.range(4, 5);
        Material log = site.chance(0.5) ? Material.STRIPPED_OAK_LOG : Material.STRIPPED_SPRUCE_LOG;
        int[] side = ForestSite.SIDES[site.random.nextInt(4)];
        Axis axis = side[0] != 0 ? Axis.X : Axis.Z;
        for (int step = 1; step < height; step++) {
            int px = x + side[0] * step;
            int pz = z + side[1] * step;
            if (!site.inZone(px, pz) || site.use(px, pz) != ForestSite.FREE || site.ground(px, pz) != g) {
                // No room for the pile: only a stump is left.
                height = 1;
                break;
            }
        }
        column(x, z, g + 1, g + height, log);
        for (int h = height - 1; h >= 1; h--) {
            int px = x + side[0] * (height - h);
            int pz = z + side[1] * (height - h);
            for (int y = g + 1; y <= g + h; y++) {
                site.set(px, y, pz, y == g + h ? Blocks.log(log, axis) : Blocks.log(Material.OAK_WOOD, Axis.Y));
            }
            site.mark(px, pz, ForestSite.TRUNK);
        }
    }

    // ------------------------------------------------------------------ small growth

    /** A low leafy bush: one block high at the rim, two in the middle, so its top can be stepped onto. */
    void bush(int x, int z, Material leaves) {
        int g = site.ground(x, z);
        double r = site.range(1.0, 1.9);
        int ir = (int) Math.ceil(r);
        for (int dx = -ir; dx <= ir; dx++) {
            for (int dz = -ir; dz <= ir; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                int bx = x + dx;
                int bz = z + dz;
                if (d > r + 0.4 || !site.inZone(bx, bz) || site.use(bx, bz) != ForestSite.FREE
                        || site.ground(bx, bz) != g || !site.isBareGround(bx, bz)) {
                    continue;
                }
                site.set(bx, g + 1, bz, BlockSpec.of(leaves));
                if (d < r - 0.6 && site.chance(0.8)) {
                    site.set(bx, g + 2, bz, BlockSpec.of(leaves));
                }
                site.mark(bx, bz, ForestSite.ROCK);
            }
        }
    }

    /** A cut stump, one block high, sometimes with a mushroom on it. */
    void stump(int x, int z, Material log) {
        int g = site.ground(x, z);
        site.set(x, g + 1, z, Blocks.log(log, Axis.Y));
        if (site.chance(0.35)) {
            site.set(x, g + 2, z, site.chance(0.5) ? Material.BROWN_MUSHROOM : Material.RED_MUSHROOM);
        }
        site.mark(x, z, ForestSite.TRUNK);
    }

    /** A fallen trunk lying on flat ground along {@code axis}. Returns false if it does not fit. */
    boolean fallenLog(int x, int z, Axis axis, int length, Material log) {
        int g = site.ground(x, z);
        int dx = axis == Axis.X ? 1 : 0;
        int dz = axis == Axis.Z ? 1 : 0;
        for (int i = -1; i <= length; i++) {
            int lx = x + dx * i;
            int lz = z + dz * i;
            if (!site.inZone(lx, lz) || site.use(lx, lz) != ForestSite.FREE || site.ground(lx, lz) != g
                    || !site.isBareGround(lx, lz)) {
                return false;
            }
        }
        int gap = length >= 7 && site.chance(0.4) ? site.range(2, length - 3) : -10;
        for (int i = 0; i < length; i++) {
            int lx = x + dx * i;
            int lz = z + dz * i;
            site.mark(lx, lz, ForestSite.ROCK);
            if (i == gap) {
                site.set(lx, g + 1, lz, Material.BROWN_MUSHROOM);
                continue;
            }
            boolean mossy = site.noise(lx, lz, 3, 41) > 0.62;
            site.set(lx, g + 1, lz, mossy ? BlockSpec.of(Material.MOSS_BLOCK) : Blocks.log(log, axis));
            double r = site.hash(lx, lz, 42);
            if (r < 0.12) {
                site.set(lx, g + 2, lz, r < 0.07 ? Material.BROWN_MUSHROOM : Material.RED_MUSHROOM);
            } else if (r < 0.3) {
                site.set(lx, g + 2, lz, Material.LEAF_LITTER);
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ helpers

    private void column(int x, int z, int y1, int y2, Material log) {
        for (int y = y1; y <= y2; y++) {
            site.set(x, y, z, Blocks.log(log, Axis.Y));
        }
    }

    /** A crown branch: a line of logs lying along its main direction. */
    private void branch(int x1, int y1, int z1, int x2, int y2, int z2, Material log) {
        Axis axis = Math.abs(x2 - x1) >= Math.abs(z2 - z1) ? Axis.X : Axis.Z;
        int steps = Math.max(Math.abs(x2 - x1), Math.max(Math.abs(y2 - y1), Math.abs(z2 - z1)));
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            canopy.wood((int) Math.round(x1 + (x2 - x1) * t), (int) Math.round(y1 + (y2 - y1) * t),
                    (int) Math.round(z1 + (z2 - z1) * t), Blocks.log(log, axis));
        }
    }

    /** A full disk of crown leaves (a fir tier). */
    private void ring(int x, int y, int z, double r, Material leaves) {
        int ir = (int) Math.ceil(r);
        for (int dx = -ir; dx <= ir; dx++) {
            for (int dz = -ir; dz <= ir; dz++) {
                if (Math.sqrt(dx * dx + dz * dz) <= r + 0.35) {
                    canopy.leaves(x + dx, y, z + dz, leaves);
                }
            }
        }
    }

    /** Low bark knots at the foot of a trunk (one block high, so they can be stepped on). */
    private void rootKnots(int x, int z, int g, Material wood, int count) {
        for (int i = 0; i < count; i++) {
            int[] side = ForestSite.SIDES[site.random.nextInt(4)];
            int rx = x + side[0] * (wood == Material.DARK_OAK_WOOD && side[0] > 0 ? 2 : 1);
            int rz = z + side[1] * (wood == Material.DARK_OAK_WOOD && side[1] > 0 ? 2 : 1);
            if (site.inZone(rx, rz) && site.use(rx, rz) == ForestSite.FREE && site.ground(rx, rz) == g
                    && site.isBareGround(rx, rz)) {
                site.set(rx, g + 1, rz, BlockSpec.of(wood).axis(Axis.Y));
                site.mark(rx, rz, ForestSite.TRUNK);
            }
        }
    }

    /** Needles and leaf mould under a tree: podzol and coarse dirt on the ground. */
    private void litter(int x, int z, double radius, Material main) {
        int r = (int) Math.ceil(radius);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int lx = x + dx;
                int lz = z + dz;
                double d = Math.sqrt(dx * dx + dz * dz) / radius;
                if (d > 1 || !site.inZone(lx, lz) || site.use(lx, lz) == ForestSite.TRAIL
                        || site.use(lx, lz) == ForestSite.BUILT || site.chance(d * 0.8)) {
                    continue;
                }
                int g = site.ground(lx, lz);
                BlockSpec at = site.get(lx, g, lz);
                if (at != null && at.material() == Material.GRASS_BLOCK) {
                    site.set(lx, g, lz, site.chance(0.75) ? BlockSpec.of(main) : BlockSpec.of(Material.COARSE_DIRT));
                }
            }
        }
    }
}
