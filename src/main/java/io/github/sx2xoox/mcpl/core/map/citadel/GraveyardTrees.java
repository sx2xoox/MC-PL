package io.github.sx2xoox.mcpl.core.map.citadel;

import java.util.Random;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import io.github.sx2xoox.mcpl.core.map.citadel.GraveyardBuilder.Use;
import org.bukkit.Axis;
import org.bukkit.Material;

/**
 * Dead and gnarled trees: crooked trunks of dark oak or spruce with bare branches, a few tufts of grey or dark
 * leaves, pale moss or roots hanging from the boughs, now and then a soul lantern on a chain, and roots and pale moss
 * around the foot.
 */
final class GraveyardTrees {

    private static final int[][] DIRECTIONS = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};

    /** Tree spots {x, z, height}. */
    private static final int[][] SPOTS = {
            {-12, -44, 8}, {16, -45, 7}, {-25, -51, 6}, {25, -53, 6}, {-35, -76, 7}, {-14, -90, 6}, {9, -91, 6},
            {35, -83, 7}, {36, -71, 6}, {-15, -64, 6}, {16, -68, 5}, {37, -78, 5}, {-28, -88, 6}, {14, -88, 7},
            {-38, -80, 5}};

    private final GraveyardBuilder b;

    GraveyardTrees(GraveyardBuilder builder) {
        this.b = builder;
    }

    void build() {
        for (int i = 0; i < SPOTS.length; i++) {
            int[] s = SPOTS[i];
            if (!b.free(s[0], s[1], 3) && b.use(s[0], s[1]) != Use.GRAVE) {
                continue;
            }
            Random random = b.canvas.random("graveyard-tree-" + i);
            boolean pale = i % 4 == 0;
            tree(s[0], s[1], s[2], pale, random);
        }
    }

    private void tree(int x, int z, int height, boolean pale, Random random) {
        // Pale trees keep dark trunks too: only their leaves and moss are grey.
        Material log = random.nextInt(3) == 0 ? Material.SPRUCE_LOG : Material.DARK_OAK_LOG;
        Material wood = log == Material.SPRUCE_LOG ? Material.SPRUCE_WOOD : Material.DARK_OAK_WOOD;
        int ground = b.ground(x, z);
        int y0 = ground + 1;
        // Clear graves or plants in the way of the trunk.
        b.mark(x, z, Use.TREE);
        // Roots and a skirt of pale moss.
        for (int[] d : DIRECTIONS) {
            if (random.nextDouble() < 0.6 && b.isAir(x + d[0], y0, z + d[1])) {
                b.set(x + d[0], y0, z + d[1], Blocks.log(log, d[0] != 0 ? Axis.X : Axis.Z));
                b.mark(x + d[0], z + d[1], Use.TREE);
            }
        }
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                int px = x + dx;
                int pz = z + dz;
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > 3.2 || b.use(px, pz) == Use.PATH || b.use(px, pz) == Use.STRUCTURE) {
                    continue;
                }
                int g = b.ground(px, pz);
                BlockSpec top = b.get(px, g, pz);
                if (top == null || !b.isAir(px, g + 1, pz)) {
                    continue;
                }
                double roll = b.hash(px, pz, 301);
                if (pale && roll < 0.5 - d * 0.12) {
                    b.set(px, g + 1, pz, BlockSpec.of(Material.PALE_MOSS_CARPET));
                } else if (roll < 0.35 - d * 0.08) {
                    b.set(px, g, pz, BlockSpec.of(pale ? Material.PALE_MOSS_BLOCK : Material.ROOTED_DIRT));
                } else if (roll > 0.85) {
                    b.set(px, g + 1, pz, BlockSpec.of(Material.LEAF_LITTER));
                }
            }
        }
        // A crooked trunk: it leans one block sideways once or twice on the way up.
        int tx = x;
        int tz = z;
        int[] lean = DIRECTIONS[random.nextInt(4)];
        int kink = 2 + random.nextInt(2);
        for (int i = 0; i < height; i++) {
            int y = y0 + i;
            if (i == kink || i == kink + 3 && random.nextBoolean()) {
                tx += lean[0];
                tz += lean[1];
                b.set(tx, y - 1, tz, BlockSpec.of(wood));
            }
            b.set(tx, y, tz, i == 0 ? BlockSpec.of(wood) : Blocks.log(log, Axis.Y));
        }
        int top = y0 + height - 1;
        // Bare branches, reaching out and up.
        int branches = 2 + random.nextInt(2);
        int first = random.nextInt(4);
        for (int k = 0; k < branches; k++) {
            int[] d = DIRECTIONS[(first + k) % 4];
            int by = top - 1 - random.nextInt(Math.max(1, height / 2 - 1)) + (k == 0 ? 1 : 0);
            branch(tx, by, tz, d, 2 + random.nextInt(2), log, pale, random);
        }
    }

    private void branch(int x, int y, int z, int[] d, int length, Material log, boolean pale, Random random) {
        int bx = x;
        int by = y;
        int bz = z;
        for (int i = 1; i <= length; i++) {
            bx += d[0];
            bz += d[1];
            if (i > 1 && random.nextDouble() < 0.55) {
                by++;
            }
            if (!b.isAir(bx, by, bz)) {
                return;
            }
            b.set(bx, by, bz, Blocks.log(log, d[0] != 0 ? Axis.X : Axis.Z));
            if (random.nextDouble() < 0.35 && b.isAir(bx, by - 1, bz) && b.isAir(bx, by - 2, bz)) {
                b.set(bx, by - 1, bz, BlockSpec.of(pale ? Material.PALE_HANGING_MOSS : Material.HANGING_ROOTS));
            }
        }
        // A twig up at the end, maybe a tuft of grey leaves or a lantern hanging below.
        if (b.isAir(bx, by + 1, bz) && random.nextDouble() < 0.45) {
            b.set(bx, by + 1, bz, Blocks.log(log, Axis.Y));
            by++;
        }
        double roll = random.nextDouble();
        if (roll < 0.35) {
            Material leaves = pale ? Material.PALE_OAK_LEAVES : Material.DARK_OAK_LEAVES;
            b.set(bx, by + 1, bz, Blocks.leaves(leaves));
            if (random.nextBoolean()) {
                b.set(bx + d[1], by, bz + d[0], Blocks.leaves(leaves));
            }
        } else if (roll < 0.55) {
            int hx = bx - d[0];
            int hz = bz - d[1];
            int hy = by - 1;
            if (b.isAir(hx, hy - 1, hz) && b.isAir(hx, hy - 2, hz) && b.isAir(hx, hy - 3, hz)) {
                b.hangingLantern(hx, hy + 1, hz, 1, true);
            }
        }
    }
}
