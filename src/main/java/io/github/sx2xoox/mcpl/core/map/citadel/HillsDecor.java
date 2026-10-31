package io.github.sx2xoox.mcpl.core.map.citadel;

import java.util.ArrayList;
import java.util.List;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

/**
 * Everything that fills the hills between the landmarks: the signal beacon ruin, archery targets by the east road,
 * the broken hillfort ring around the tower, haystacks, dry stone field walls, log breastworks on the tower hill,
 * rocky outcrops, bushes, a few trees and the grass and flowers. Cover pieces (walls, logs, hay, rocks, bushes) are
 * lumps, so {@link HillsSite#settleLumps()} gives each a climbable side.
 */
final class HillsDecor {

    private static final Material[] ROCK = {Material.STONE, Material.STONE, Material.ANDESITE, Material.ANDESITE,
            Material.COBBLESTONE, Material.MOSSY_COBBLESTONE, Material.TUFF, Material.STONE};
    private static final Material[] ROCK_TOP = {Material.MOSSY_COBBLESTONE, Material.MOSS_BLOCK, Material.STONE,
            Material.ANDESITE, Material.MOSSY_COBBLESTONE};
    private static final Material[] FIELD_STONE = {Material.COBBLESTONE, Material.COBBLESTONE,
            Material.MOSSY_COBBLESTONE, Material.MOSSY_COBBLESTONE, Material.STONE, Material.ANDESITE};
    private static final Material[] FLOWERS = {Material.DANDELION, Material.POPPY, Material.CORNFLOWER,
            Material.OXEYE_DAISY, Material.AZURE_BLUET, Material.ALLIUM};
    private static final Material[] LEAVES = {Material.OAK_LEAVES, Material.OAK_LEAVES, Material.AZALEA_LEAVES,
            Material.FLOWERING_AZALEA_LEAVES, Material.BIRCH_LEAVES};

    private final HillsSite site;
    private final List<int[]> placed = new ArrayList<>();

    HillsDecor(HillsSite site) {
        this.site = site;
    }

    // ------------------------------------------------------------------ landmarks

    /**
     * The signal beacon ruin: a round stone platform on the knoll, a raised hearth with a cold brazier, and the stumps
     * of the pillars that held its roof.
     */
    void beacon(int[] center) {
        int cx = center[0];
        int cz = center[1];
        int g = site.ground(cx, cz);
        for (int u = -4; u <= 4; u++) {
            for (int v = -4; v <= 4; v++) {
                double d = Math.sqrt(u * u + v * v);
                int x = cx + u;
                int z = cz + v;
                if (d > 3.6) {
                    if (d <= 4.6 && site.use(x, z) == HillsSite.Use.STRUCTURE) {
                        site.set(x, site.ground(x, z), z, site.pick(Material.COARSE_DIRT, Material.GRAVEL,
                                Material.GRASS_BLOCK));
                    }
                    continue;
                }
                for (int y = site.ground(x, z) + 1; y <= g; y++) {
                    site.set(x, y, z, Material.STONE_BRICKS);
                }
                if (d > 2.6) {
                    if (site.hash(x, z, 91) > -0.35) {
                        site.set(x, g + 1, z, Blocks.stairs(site.pick(Material.STONE_BRICK_STAIRS,
                                Material.MOSSY_STONE_BRICK_STAIRS), inward(u, v)));
                    }
                } else {
                    site.set(x, g + 1, z, BlockSpec.of(site.pick(Material.STONE_BRICKS, Material.MOSSY_STONE_BRICKS,
                            Material.CRACKED_STONE_BRICKS, Material.POLISHED_ANDESITE)));
                }
            }
        }
        // The hearth and the brazier.
        for (int u = -1; u <= 1; u++) {
            for (int v = -1; v <= 1; v++) {
                site.set(cx + u, g + 2, cz + v, BlockSpec.of(u == 0 && v == 0 ? Material.CHISELED_STONE_BRICKS
                        : Material.STONE_BRICKS));
            }
        }
        site.set(cx, g + 3, cz, Blocks.campfire(false));
        site.set(cx + 1, g + 3, cz, Blocks.stairs(Material.STONE_BRICK_STAIRS, BlockFace.EAST));
        site.set(cx - 1, g + 3, cz, Blocks.stairs(Material.STONE_BRICK_STAIRS, BlockFace.WEST));
        site.set(cx, g + 3, cz + 1, Blocks.stairs(Material.STONE_BRICK_STAIRS, BlockFace.SOUTH));
        site.set(cx, g + 3, cz - 1, Blocks.stairs(Material.STONE_BRICK_STAIRS, BlockFace.NORTH));
        // Pillar stumps.
        int[][] stumps = {{2, -2, 2}, {-2, -2, 1}, {-2, 2, 2}, {2, 2, 1}};
        for (int[] stump : stumps) {
            for (int k = 1; k <= stump[2]; k++) {
                site.set(cx + stump[0], g + 1 + k, cz + stump[1], k == stump[2] && stump[2] > 1
                        ? Blocks.slab(Material.MOSSY_STONE_BRICK_SLAB) : BlockSpec.of(Material.STONE_BRICKS));
            }
        }
        site.set(cx - 3, g + 1, cz + 1, Blocks.lantern(false, false));
    }

    /** Straw targets west of the barn, with a cold fire for the archers. */
    void archeryRange() {
        int[][] targets = {{45, -8}, {49, -8}};
        for (int[] t : targets) {
            for (int dx = -1; dx <= 1; dx++) {
                int x = t[0] + dx;
                int z = t[1];
                if (!site.free(x, z)) {
                    continue;
                }
                int g = site.ground(x, z);
                site.stack(x, z, g + 1, Blocks.log(Material.HAY_BLOCK, Axis.Y));
                if (dx == 0) {
                    site.set(x, g + 2, z, Material.TARGET);
                    site.lump(x, z, g + 2);
                }
            }
        }
        if (site.free(47, -12)) {
            site.set(47, site.ground(47, -12) + 1, -12, Blocks.campfire(false));
            site.mark(47, -12, HillsSite.Use.STRUCTURE);
        }
    }

    /**
     * The old hillfort ring on the tower hill's crest: arcs of a low, broken stone wall at the edge of the hilltop,
     * open wherever a path arrives.
     */
    void hillfort() {
        int[] tower = HillsDistrict.TOWER;
        double[][] arcs = {{18, 68}, {104, 134}, {176, 238}, {334, 372}};
        for (int[] cell : HillsSite.ring(8)) {
            int u = cell[0];
            int v = cell[1];
            double angle = HillsSite.degrees(u, v);
            boolean inArc = false;
            for (double[] arc : arcs) {
                if (angle >= arc[0] && angle <= arc[1] || angle + 360 >= arc[0] && angle + 360 <= arc[1]) {
                    inArc = true;
                }
            }
            int x = tower[0] + u;
            int z = tower[1] + v;
            if (!inArc || site.use(x, z) != HillsSite.Use.STRUCTURE || !site.isAir(x, site.ground(x, z) + 1, z)) {
                continue;
            }
            double roll = site.hash(x, z, 110);
            if (roll < -0.75) {
                continue; // fallen stones
            }
            int g = site.ground(x, z);
            site.stack(x, z, g + 1, BlockSpec.of(site.pick(Material.MOSSY_STONE_BRICKS, Material.STONE_BRICKS,
                    Material.CRACKED_STONE_BRICKS, Material.MOSSY_COBBLESTONE)));
            if (roll > 0.15) {
                site.set(x, g + 2, z, roll > 0.6 ? Blocks.slab(Material.MOSSY_STONE_BRICK_SLAB)
                        : BlockSpec.of(site.pick(Material.MOSSY_STONE_BRICKS, Material.STONE_BRICKS)));
                site.lump(x, z, g + 2);
            }
        }
    }

    /** Haystacks left from the mill's last harvest: stepped piles of hay bales. */
    void haystacks() {
        int[][] spots = {{47, -51}, {85, -27}, {33, -21}};
        for (int[] spot : spots) {
            int x0 = spot[0];
            int z0 = spot[1];
            if (!site.free(x0, z0, 2.5)) {
                continue;
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int x = x0 + dx;
                    int z = z0 + dz;
                    int height = dx >= 0 && dz <= 0 ? 2 : 1;
                    if (dx == 1 && dz == -1 && site.hash(x, z, 111) > 0) {
                        height = 3;
                    }
                    if (dx == -1 && dz == 1 && site.hash(x, z, 112) > 0.3) {
                        continue;
                    }
                    site.stack(x, z, site.ground(x, z) + height, Blocks.log(Material.HAY_BLOCK, Axis.Y));
                }
            }
            placed.add(new int[] {x0, z0});
        }
    }

    // ------------------------------------------------------------------ cover

    /** Dry stone walls across the fields, knee to chest high, broken by gaps (and wherever a path crosses). */
    void fieldWalls() {
        wall(31, -31, 35, -25, 37, -18, 37, -10);
        wall(43, -59, 46, -65, 51, -71);
        wall(78, -27, 83, -31, 87, -32);
        wall(51, -29, 56, -24, 59, -21);
        wall(74, -10, 77, -15);
    }

    private void wall(int... points) {
        int index = 0;
        for (int i = 0; i + 3 < points.length; i += 2) {
            double ax = points[i];
            double az = points[i + 1];
            double bx = points[i + 2];
            double bz = points[i + 3];
            double length = HillsSite.dist(ax, az, bx, bz);
            int lastX = Integer.MIN_VALUE;
            int lastZ = Integer.MIN_VALUE;
            for (double t = 0; t <= length; t += 0.25) {
                int x = (int) Math.round(ax + (bx - ax) * t / length);
                int z = (int) Math.round(az + (bz - az) * t / length);
                if (x == lastX && z == lastZ) {
                    continue;
                }
                lastX = x;
                lastZ = z;
                index++;
                if (index % 9 >= 7 || !site.free(x, z)) {
                    continue; // a gap
                }
                int g = site.ground(x, z);
                int height = site.hash(x, z, 92) > 0.35 ? 2 : 1;
                site.stack(x, z, g + 1, BlockSpec.of(FIELD_STONE[site.random.nextInt(FIELD_STONE.length)]));
                if (height == 2) {
                    site.set(x, g + 2, z, site.chance(0.5) ? Blocks.slab(site.pick(Material.COBBLESTONE_SLAB,
                            Material.MOSSY_COBBLESTONE_SLAB)) : BlockSpec.of(site.pick(Material.MOSSY_COBBLESTONE,
                            Material.COBBLESTONE)));
                    site.lump(x, z, g + 2);
                }
            }
        }
    }

    /** Log breastworks dug into the tower hill's slopes, facing down the hill (cover on the way up). */
    void breastworks() {
        int[] tower = HillsDistrict.TOWER;
        double[] angles = {100, 140, 175, 215, 250, 40};
        for (double angle : angles) {
            double rad = Math.toRadians(angle);
            double dist = 12.5 + site.hash((int) angle, 0, 93) * 1.2;
            double cx = tower[0] + Math.cos(rad) * dist;
            double cz = tower[1] + Math.sin(rad) * dist;
            // The row runs across the slope (perpendicular to the direction to the tower).
            boolean alongX = Math.abs(Math.sin(rad)) > Math.abs(Math.cos(rad));
            int half = 2 + (site.hash((int) angle, 1, 94) > 0 ? 1 : 0);
            int x0 = (int) Math.round(cx);
            int z0 = (int) Math.round(cz);
            for (int k = -half; k <= half; k++) {
                int x = alongX ? x0 + k : x0;
                int z = alongX ? z0 : z0 + k;
                if (!site.free(x, z)) {
                    continue;
                }
                int g = site.ground(x, z);
                BlockSpec log = Blocks.log(Material.SPRUCE_LOG, alongX ? Axis.X : Axis.Z);
                site.stack(x, z, g + 1, log);
                if (Math.abs(k) < half) {
                    site.set(x, g + 2, z, Math.abs(k) == half - 1 && site.chance(0.5)
                            ? Blocks.log(Material.STRIPPED_SPRUCE_LOG, alongX ? Axis.X : Axis.Z) : log);
                    site.lump(x, z, g + 2);
                }
            }
        }
    }

    /** Rocky outcrops: a cluster of crags on the outer flank and boulders scattered over the hills. */
    void rocks() {
        int[] crags = HillsDistrict.CRAGS;
        rock(crags[0], crags[1], 3.4, 4, 2.6);
        rock(crags[0] + 5, crags[1] + 3, 2.6, 3, 2.0);
        rock(crags[0] - 4, crags[1] - 4, 2.8, 3, 2.2);
        rock(crags[0] + 1, crags[1] - 6, 2.2, 3, 1.8);
        rock(crags[0] - 6, crags[1] + 3, 2.0, 2, 1.6);
        // Boulders guarding the climb to the tower and the windmill.
        int[][] near = {{54, -36}, {58, -21}, {77, -24}, {79, -36}, {66, -45}, {50, -49}, {69, -61}, {47, -55}};
        for (int[] at : near) {
            rock(at[0], at[1], 2.2 + site.random.nextDouble(), 3, 1.6 + site.random.nextDouble() * 0.6);
        }
        // Scattered boulders: Poisson-disc sampled so cover is never far away.
        int wanted = 22;
        for (int attempt = 0; attempt < 3000 && wanted > 0; attempt++) {
            int x = site.random.nextInt(97);
            int z = -site.random.nextInt(97);
            if (!site.free(x, z, 3.5) || HillsTerrain.edgeDistance(x, z) < 4 || tooClose(x, z, 11)) {
                continue;
            }
            double size = 1.4 + site.random.nextDouble() * 1.4;
            int height = size > 2.2 ? 3 : 2;
            rock(x, z, size, height, size * (0.6 + site.random.nextDouble() * 0.3));
            wanted--;
        }
    }

    private boolean tooClose(int x, int z, double min) {
        for (int[] p : placed) {
            if (HillsSite.dist(x, z, p[0], p[1]) < min) {
                return true;
            }
        }
        return false;
    }

    /**
     * A boulder: a rounded, noisy lump {@code height} blocks above the ground at its center, embedded in the slope
     * (so its downhill side stands out as a cliff).
     */
    private void rock(int cx, int cz, double rx, int height, double rz) {
        if (!site.free(cx, cz)) {
            return;
        }
        placed.add(new int[] {cx, cz});
        int base = site.ground(cx, cz);
        double turn = site.random.nextDouble() * Math.PI;
        double cos = Math.cos(turn);
        double sin = Math.sin(turn);
        int r = (int) Math.ceil(Math.max(rx, rz)) + 1;
        int salt = 95 + placed.size();
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                if (!site.free(x, z) && site.use(x, z) != HillsSite.Use.LUMP) {
                    continue;
                }
                double a = ((x - cx) * cos + (z - cz) * sin) / rx;
                double c = (-(x - cx) * sin + (z - cz) * cos) / rz;
                double n = a * a + c * c;
                if (n > 1.15) {
                    continue;
                }
                double h = height * Math.sqrt(Math.max(0, 1 - n)) + site.noise(x / 1.7, z / 1.7, salt) * 0.9 + 0.3;
                int top = base + (int) Math.round(h);
                int g = site.ground(x, z);
                if (top <= g || top <= site.lumpTop(x, z)) {
                    continue;
                }
                top = Math.min(top, g + height + 1);
                for (int y = g + 1; y < top; y++) {
                    site.set(x, y, z, ROCK[site.random.nextInt(ROCK.length)]);
                }
                site.set(x, top, z, site.chance(0.35) ? ROCK_TOP[site.random.nextInt(ROCK_TOP.length)]
                        : ROCK[site.random.nextInt(ROCK.length)]);
                site.lump(x, z, top);
            }
        }
    }

    /** Bushes of leaves, one or two blocks high, scattered over the hills and hugging the rocks. */
    void bushes() {
        int wanted = 30;
        for (int attempt = 0; attempt < 3000 && wanted > 0; attempt++) {
            int x = site.random.nextInt(97);
            int z = -site.random.nextInt(97);
            if (!site.free(x, z, 2.2) || HillsTerrain.edgeDistance(x, z) < 3) {
                continue;
            }
            Material leaves = LEAVES[site.random.nextInt(LEAVES.length)];
            double radius = 1.1 + site.random.nextDouble() * 0.9;
            int g0 = site.ground(x, z);
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    double d = Math.sqrt(dx * dx + dz * dz) + site.hash(x + dx, z + dz, 96) * 0.4;
                    int bx = x + dx;
                    int bz = z + dz;
                    if (d > radius + 0.3 || !site.free(bx, bz)) {
                        continue;
                    }
                    int g = site.ground(bx, bz);
                    int top = Math.max(g + 1, Math.min(g + 2, g0 + (d < radius - 0.6 ? 2 : 1)));
                    site.stack(bx, bz, top, Blocks.leaves(leaves));
                }
            }
            wanted--;
        }
    }

    // ------------------------------------------------------------------ greenery

    /** A few trees: an old oak on the knoll, birches by the windmill, a spruce by the camp, oaks on the slopes. */
    void trees() {
        int[] knoll = HillsDistrict.KNOLL;
        oak(knoll[0] + 1, knoll[1] + 1, 5, 3);
        birch(47, -63, 6);
        birch(44, -67, 5);
        birch(50, -68, 6);
        spruce(40, -56, 7);
        oak(72, -17, 4, 2);
        oak(88, -22, 4, 2);
        spruce(73, -53, 6);
        oak(40, -12, 4, 2);
    }

    private boolean treeSpot(int x, int z) {
        return site.free(x, z, 1.5);
    }

    private void oak(int x, int z, int trunk, int crown) {
        if (!treeSpot(x, z)) {
            return;
        }
        int g = site.ground(x, z);
        int top = g + trunk;
        for (int y = g + 1; y <= top; y++) {
            site.set(x, y, z, Blocks.log(Material.OAK_LOG, Axis.Y));
        }
        for (int dy = -1; dy <= 2; dy++) {
            int r = dy == 2 ? crown - 1 : dy == -1 ? crown - 1 : crown;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    double d = Math.sqrt(dx * dx + dz * dz);
                    if (d > r + 0.4 || d > r - 0.6 && site.hash(x + dx, z + dz + dy * 31, 97) > 0.2) {
                        continue;
                    }
                    if (dx == 0 && dz == 0 && dy <= 0) {
                        continue;
                    }
                    if (site.isAir(x + dx, top + dy, z + dz)) {
                        site.set(x + dx, top + dy, z + dz, Blocks.leaves(Material.OAK_LEAVES));
                    }
                }
            }
        }
        site.markDisk(x, z, 1.5, HillsSite.Use.STRUCTURE);
    }

    private void birch(int x, int z, int trunk) {
        if (!treeSpot(x, z)) {
            return;
        }
        int g = site.ground(x, z);
        int top = g + trunk;
        for (int y = g + 1; y <= top; y++) {
            site.set(x, y, z, Blocks.log(Material.BIRCH_LOG, Axis.Y));
        }
        for (int dy = -2; dy <= 1; dy++) {
            int r = dy >= 0 ? 1 : 2;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) == r && Math.abs(dz) == r && (r == 1 || site.hash(x + dx, z + dz, 98) > -0.4)) {
                        continue;
                    }
                    if (dx == 0 && dz == 0 && dy <= 0) {
                        continue;
                    }
                    if (site.isAir(x + dx, top + dy, z + dz)) {
                        site.set(x + dx, top + dy, z + dz, Blocks.leaves(Material.BIRCH_LEAVES));
                    }
                }
            }
        }
        site.markDisk(x, z, 1.5, HillsSite.Use.STRUCTURE);
    }

    private void spruce(int x, int z, int trunk) {
        if (!treeSpot(x, z)) {
            return;
        }
        int g = site.ground(x, z);
        int top = g + trunk;
        for (int y = g + 1; y <= top; y++) {
            site.set(x, y, z, Blocks.log(Material.SPRUCE_LOG, Axis.Y));
        }
        site.set(x, top + 1, z, Blocks.leaves(Material.SPRUCE_LEAVES));
        for (int y = g + 3; y <= top; y++) {
            int fromTop = top - y;
            int r = fromTop % 2 == 0 ? Math.min(2, 1 + fromTop / 3) : 1;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx == 0 && dz == 0 || Math.abs(dx) + Math.abs(dz) > r + (r > 1 ? 1 : 0)) {
                        continue;
                    }
                    if (site.isAir(x + dx, y, z + dz)) {
                        site.set(x + dx, y, z + dz, Blocks.leaves(Material.SPRUCE_LEAVES));
                    }
                }
            }
        }
        site.markDisk(x, z, 1.5, HillsSite.Use.STRUCTURE);
    }

    /** Grass, ferns and patches of wildflowers on the open grass. */
    void plants() {
        for (int x = 0; x <= 97; x++) {
            for (int z = -97; z <= 0; z++) {
                HillsSite.Use use = site.use(x, z);
                if (!site.inZone(x, z) || use != HillsSite.Use.FREE && use != HillsSite.Use.STRUCTURE) {
                    continue;
                }
                int g = site.ground(x, z);
                BlockSpec top = site.get(x, g, z);
                if (top == null || top.material() != Material.GRASS_BLOCK || !site.isAir(x, g + 1, z)) {
                    continue;
                }
                double roll = site.random.nextDouble();
                // Flowers grow in a few meadows; elsewhere grass tufts thicken where the swell noise is high.
                double meadow = site.noise(x / 9.0, z / 9.0, 99);
                double tufts = 0.06 + 0.1 * (site.noise(x / 5.0, z / 5.0, 101) * 0.5 + 0.5);
                boolean open = use == HillsSite.Use.FREE;
                if (meadow > 0.5 && roll < 0.16) {
                    int kind = (int) Math.floor((site.noise(x / 5.0, z / 5.0, 100) * 0.5 + 0.5) * FLOWERS.length);
                    site.set(x, g + 1, z, FLOWERS[Math.max(0, Math.min(FLOWERS.length - 1, kind))]);
                } else if (roll < tufts) {
                    site.set(x, g + 1, z, Material.SHORT_GRASS);
                } else if (open && roll < tufts + 0.012 && site.isAir(x, g + 2, z)) {
                    Blocks.tallPlant(site.canvas, x, g + 1, z, Material.TALL_GRASS);
                } else if (open && roll < tufts + 0.02) {
                    site.set(x, g + 1, z, Material.FERN);
                } else if (roll < tufts + 0.024) {
                    site.set(x, g + 1, z, FLOWERS[site.random.nextInt(FLOWERS.length)]);
                }
            }
        }
    }

    private static BlockFace inward(int u, int v) {
        if (Math.abs(u) >= Math.abs(v)) {
            return u > 0 ? BlockFace.WEST : BlockFace.EAST;
        }
        return v > 0 ? BlockFace.NORTH : BlockFace.SOUTH;
    }
}
