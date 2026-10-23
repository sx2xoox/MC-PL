package io.github.sx2xoox.mcpl.core.map.citadel;

import java.util.Random;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.jspecify.annotations.Nullable;

/**
 * The plants of the canyon district, placed last on whatever surface the other builders left: a few wind-bent acacias
 * and dead trees, shrubs, grass and flower patches on the plateau, dead bushes on dry ground, ferns in the damp gorge
 * and roots hanging from overhangs.
 */
final class CanyonFlora {

    private static final Material[] FLOWERS = {Material.POPPY, Material.DANDELION, Material.CORNFLOWER,
        Material.AZURE_BLUET, Material.OXEYE_DAISY, Material.ALLIUM};

    private final MapCanvas canvas;
    private final CanyonPlan plan;
    private final CanyonTerrain terrain;
    private final Random random;

    CanyonFlora(MapCanvas canvas, CanyonPlan plan, CanyonTerrain terrain, Random random) {
        this.canvas = canvas;
        this.plan = plan;
        this.terrain = terrain;
        this.random = random;
    }

    void build() {
        trees();
        shrubs();
        groundCover();
        hangingRoots();
    }

    // ------------------------------------------------------------------ trees

    private void trees() {
        acacia(-77, -7, 1);
        acacia(-40, -8, -1);
        oak(-48, -25);
        deadTree(-38, -46);
        deadTree(-52, -75);
    }

    private boolean canGrow(int x, int z) {
        return canvas.canWrite(x, z) && !terrain.reserved(x, z) && terrain.top(x, z) == CanyonPlan.GROUND
                && terrain.kind(x, z) == CanyonTerrain.Kind.PLATEAU && !canvas.isSolid(x, CanyonPlan.GROUND + 1, z);
    }

    private void putIfFree(int x, int y, int z, BlockSpec spec) {
        if (canvas.canWrite(x, z) && !canvas.isSolid(x, y, z)) {
            canvas.set(x, y, z, spec);
        }
    }

    /** A flat-topped, wind-bent acacia: the trunk leans one block towards {@code lean} (x) halfway up. */
    private void acacia(int x, int z, int lean) {
        if (!canGrow(x, z)) {
            return;
        }
        int y = CanyonPlan.GROUND + 1;
        BlockSpec log = Blocks.log(Material.ACACIA_LOG, Axis.Y);
        canvas.set(x, y, z, log);
        canvas.set(x, y + 1, z, log);
        putIfFree(x + lean, y + 2, z, log);
        putIfFree(x + lean, y + 3, z, log);
        putIfFree(x, y + 3, z - 1, Blocks.log(Material.ACACIA_LOG, Axis.Z));
        int cx = x + lean;
        int top = y + 4;
        canopy(cx, top, z, 2.9, Material.ACACIA_LEAVES);
        canopy(cx, top + 1, z, 1.7, Material.ACACIA_LEAVES);
        canopy(x - lean, top - 1, z - 2, 1.4, Material.ACACIA_LEAVES);
        terrain.reserve(x, z, 1.5);
    }

    /** A small oak giving the quarry camp some shade. */
    private void oak(int x, int z) {
        if (!canGrow(x, z)) {
            return;
        }
        int y = CanyonPlan.GROUND + 1;
        for (int i = 0; i < 5; i++) {
            canvas.set(x, y + i, z, Blocks.log(Material.OAK_LOG, Axis.Y));
        }
        canopy(x, y + 3, z, 2.4, Material.OAK_LEAVES);
        canopy(x, y + 4, z, 2.6, Material.OAK_LEAVES);
        canopy(x, y + 5, z, 1.6, Material.OAK_LEAVES);
        terrain.reserve(x, z, 1.5);
    }

    /** A bleached dead tree with a few bare branches. */
    private void deadTree(int x, int z) {
        if (!canGrow(x, z)) {
            return;
        }
        int y = CanyonPlan.GROUND + 1;
        BlockSpec log = Blocks.log(Material.STRIPPED_SPRUCE_LOG, Axis.Y);
        for (int i = 0; i < 5; i++) {
            canvas.set(x, y + i, z, log);
        }
        putIfFree(x + 1, y + 3, z, Blocks.log(Material.STRIPPED_SPRUCE_LOG, Axis.X));
        putIfFree(x + 2, y + 4, z, Blocks.log(Material.STRIPPED_SPRUCE_LOG, Axis.Y));
        putIfFree(x, y + 2, z - 1, Blocks.log(Material.STRIPPED_SPRUCE_LOG, Axis.Z));
        putIfFree(x, y + 3, z - 2, Blocks.log(Material.STRIPPED_SPRUCE_LOG, Axis.Y));
        putIfFree(x - 1, y + 5, z, Blocks.log(Material.STRIPPED_SPRUCE_LOG, Axis.X));
        putIfFree(x, y + 5, z, BlockSpec.of(Material.SPRUCE_FENCE));
        terrain.reserve(x, z, 1.5);
    }

    /** A flat disk of leaves with ragged edges. */
    private void canopy(int cx, int y, int cz, double radius, Material leaves) {
        int r = (int) Math.ceil(radius);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double d = Math.hypot(dx, dz);
                if (d > radius || d > radius - 0.7 && plan.roll(cx + dx, y, cz + dz, 401) < 45) {
                    continue;
                }
                putIfFree(cx + dx, y, cz + dz, Blocks.leaves(leaves));
            }
        }
    }

    /** Low bushes of leaves scattered over the plateau: cover to crouch behind. */
    private void shrubs() {
        int placed = 0;
        for (int attempt = 0; attempt < 600 && placed < 26; attempt++) {
            int x = CanyonPlan.MIN_X + random.nextInt(CanyonPlan.SIZE_X);
            int z = CanyonPlan.MIN_Z + random.nextInt(CanyonPlan.SIZE_Z);
            if (!canGrow(x, z) || CanyonPlan.edgeDistance(x, z) < 3 || plan.rimDistance(x, z) < 2.5) {
                continue;
            }
            Material leaves = random.nextInt(3) == 0 ? Material.AZALEA_LEAVES : random.nextBoolean()
                    ? Material.OAK_LEAVES : Material.SPRUCE_LEAVES;
            int size = 1 + random.nextInt(3);
            for (int i = 0; i < size; i++) {
                int bx = x + (i == 1 ? 1 : 0);
                int bz = z + (i == 2 ? 1 : 0);
                if (canGrow(bx, bz)) {
                    canvas.set(bx, CanyonPlan.GROUND + 1, bz, Blocks.leaves(leaves));
                }
            }
            if (size == 3 && random.nextBoolean()) {
                putIfFree(x, CanyonPlan.GROUND + 2, z, Blocks.leaves(leaves));
            }
            placed++;
        }
    }

    // ------------------------------------------------------------------ ground cover

    private void groundCover() {
        for (int x = CanyonPlan.MIN_X; x <= CanyonPlan.MAX_X; x++) {
            for (int z = CanyonPlan.MIN_Z; z <= CanyonPlan.MAX_Z; z++) {
                if (!canvas.canWrite(x, z)) {
                    continue;
                }
                int y = topSolid(x, z);
                BlockSpec ground = canvas.get(x, y, z);
                if (ground == null || canvas.isSolid(x, y + 1, z)) {
                    continue;
                }
                Material plant = plantFor(ground.material(), x, y, z);
                if (plant == null) {
                    continue;
                }
                if (plant == Material.TALL_GRASS || plant == Material.LARGE_FERN) {
                    if (!canvas.isSolid(x, y + 2, z)) {
                        Blocks.tallPlant(canvas, x, y + 1, z, plant);
                    }
                } else {
                    canvas.set(x, y + 1, z, BlockSpec.of(plant));
                }
            }
        }
    }

    private int topSolid(int x, int z) {
        for (int y = 90; y > canvas.minY(); y--) {
            if (canvas.isSolid(x, y, z)) {
                return y;
            }
        }
        return canvas.minY();
    }

    private @Nullable Material plantFor(Material ground, int x, int y, int z) {
        int roll = plan.roll(x, y, z, 402);
        boolean damp = y < CanyonPlan.GROUND - 1;
        switch (ground) {
            case GRASS_BLOCK -> {
                if (plan.noise(x / 5.0, z / 5.0, 403) > 0.5 && roll < 24) {
                    int pick = (int) ((plan.noise(x / 9.0, z / 9.0, 404) + 1) / 2 * FLOWERS.length);
                    return FLOWERS[Math.max(0, Math.min(FLOWERS.length - 1, pick))];
                }
                if (damp) {
                    return roll < 22 ? Material.FERN : roll < 34 ? Material.SHORT_GRASS : roll < 38
                            ? Material.LARGE_FERN : null;
                }
                return roll < 15 ? Material.SHORT_GRASS : roll < 18 ? Material.TALL_GRASS : roll < 20
                        ? Material.FERN : null;
            }
            case MOSS_BLOCK -> {
                return roll < 18 ? Material.FERN : roll < 30 ? Material.SHORT_GRASS : roll < 32
                        ? Material.FIREFLY_BUSH : null;
            }
            case COARSE_DIRT, PODZOL, ROOTED_DIRT -> {
                return roll < (damp ? 4 : 8) ? Material.DEAD_BUSH : roll < 11 ? Material.SHORT_GRASS : null;
            }
            default -> {
                return null;
            }
        }
    }

    /** Roots hanging from the undersides of overhangs, arches and bridges over the gorge. */
    private void hangingRoots() {
        for (int x = CanyonPlan.MIN_X; x <= CanyonPlan.MAX_X; x++) {
            for (int z = CanyonPlan.MIN_Z; z <= CanyonPlan.MAX_Z; z++) {
                if (!canvas.canWrite(x, z) || !terrain.inGorge(x, z)) {
                    continue;
                }
                for (int y = terrain.top(x, z) + 3; y <= 63; y++) {
                    BlockSpec above = canvas.get(x, y + 1, z);
                    if (above == null || !isEarthy(above.material()) || canvas.isSolid(x, y, z)) {
                        continue;
                    }
                    if (plan.roll(x, y, z, 405) < 22) {
                        canvas.set(x, y, z, BlockSpec.of(Material.HANGING_ROOTS));
                    }
                }
            }
        }
    }

    private static boolean isEarthy(Material material) {
        String name = material.name();
        return name.contains("DIRT") || name.contains("STONE") || name.contains("TUFF") || name.contains("ANDESITE")
                || name.contains("GRANITE") || name.contains("TERRACOTTA") || name.contains("MOSS");
    }
}
