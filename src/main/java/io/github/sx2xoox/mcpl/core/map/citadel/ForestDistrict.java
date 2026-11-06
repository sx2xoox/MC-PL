package io.github.sx2xoox.mcpl.core.map.citadel;

import java.util.ArrayList;
import java.util.List;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.MapZone;
import org.bukkit.Axis;
import org.bukkit.Material;

/**
 * The forest district (south, 60°..120°): an old forest that has grown over the citadel's gardens. Oaks, birches,
 * firs, pines and dark oaks of every size stand between mossy boulders, fallen logs, bushes and ferns; two huge old
 * trees and a giant hollow tree with a deck in its crown rise above them. Trails lead from the ring road through a
 * broken archway to the hollow tree and on to a ruined hunter's lodge, a mossy crag with a cleft through it and the
 * radial roads; a fallen giant trunk makes a tunnel on the trail along the keep, and a brook runs from a spring under
 * the crag past the hollow tree to a reedy pond by the lodge.
 *
 * <p>Every crown starts at least five blocks above the ground and is shaped so it can be walked on; patches of canopy
 * have ladders up a trunk. See {@link ForestSite} for the shared tools and {@link ForestCanopy} for the crowns.
 */
final class ForestDistrict implements MapZone {

    // Landmarks (map-local columns).
    private static final int[] HOLLOW_TREE = {0, 65};
    private static final int[] LODGE = {-25, 77};
    private static final int[] CRAG = {22, 79};
    private static final int[] FALLEN_GIANT = {-15, 48};
    private static final int[] ARCH = {0, 42};
    private static final int[][] GIANTS = {{19, 47}, {-27, 59}};

    /** Trails as polylines of map-local points. */
    private static final int[][][] TRAILS = {
            // From the ring road through the archway to the hollow tree's west door.
            {{0, 35}, {0, 42}, {-2, 49}, {-5, 56}, {-9, 62}, {-11, 65}},
            // From the east door past the crag to the 60° road.
            {{11, 65}, {17, 63}, {25, 62}, {31, 61}, {37, 62}},
            // From the south door to the outer wall.
            {{0, 72}, {1, 78}, {-2, 86}, {-1, 92}, {0, 97}},
            // From the west door to the lodge and on to the 120° road.
            {{-11, 65}, {-15, 68}, {-18, 73}, {-19, 77}, {-22, 81}, {-30, 82}, {-38, 80}, {-45, 78}},
            // Along the keep: from the 60° road through the archway and the fallen giant to the 120° road.
            {{23, 39}, {14, 43}, {6, 44}, {0, 43}, {-6, 46}, {-9, 48}, {-22, 48}, {-27, 45}},
    };

    @Override
    public String name() {
        return "forest";
    }

    @Override
    public boolean contains(int x, int z) {
        return CitadelLayout.isDistrict(CitadelLayout.District.FOREST, x, z);
    }

    @Override
    public void build(MapCanvas canvas) {
        ForestSite site = new ForestSite(canvas, canvas.random(name()));
        terrain(site);
        floor(site);
        trails(site);
        new ForestBrook(site).build();

        ForestRuins ruins = new ForestRuins(site);
        ruins.arch(ARCH[0], ARCH[1]);
        ruins.wall(ARCH[0] + 8, ARCH[1] + 3, ARCH[0] + 14, ARCH[1] + 7);
        ruins.wall(ARCH[0] - 8, ARCH[1] + 4, ARCH[0] - 11, ARCH[1] + 9);
        new ForestFallenGiant(site, FALLEN_GIANT[0], FALLEN_GIANT[1]).build();
        ForestHollowTree hollow = new ForestHollowTree(site, HOLLOW_TREE[0], HOLLOW_TREE[1]);
        hollow.build();
        new ForestLodge(site, LODGE[0], LODGE[1]).build();
        ForestRocks rocks = new ForestRocks(site);
        double along = Math.atan2(CRAG[1], CRAG[0]) + Math.PI / 2;
        rocks.crag(CRAG[0], CRAG[1], along, 8);
        boulders(site, rocks);

        ForestTrees trees = new ForestTrees(site);
        forest(site, trees);
        deadwood(site, trees);
        undergrowth(site, trees);

        site.canopy().finish();
        hollow.finish();
        trailLanterns(site);
        hangingGrowth(site);
    }

    // ------------------------------------------------------------------ ground

    /** Gentle rolls in the ground, flat around the landmarks and at the district's edges. */
    private static void terrain(ForestSite site) {
        int[] heights = new int[ForestSite.columns()];
        double[][] flat = {
                {HOLLOW_TREE[0], HOLLOW_TREE[1], 11, 16}, {LODGE[0], LODGE[1], 11, 15},
                {FALLEN_GIANT[0], FALLEN_GIANT[1], 10, 13}, {ARCH[0], ARCH[1], 7, 10}, {CRAG[0], CRAG[1], 13, 15},
        };
        for (int x = ForestSite.MIN_X; x <= ForestSite.MAX_X; x++) {
            for (int z = ForestSite.MIN_Z; z <= ForestSite.MAX_Z; z++) {
                if (!site.inZone(x, z)) {
                    continue;
                }
                double fade = clamp((ForestSite.edge(x, z) - 2) / 8);
                for (double[] f : flat) {
                    double d = Math.hypot(x - f[0], z - f[1]);
                    fade = Math.min(fade, clamp((d - f[2]) / (f[3] - f[2])));
                }
                fade = Math.min(fade, clamp((ForestBrook.distance(x, z) - 3) / 4));
                double n = site.noise(x, z, 15, 1) * 0.8 + site.noise(x, z, 6, 2) * 0.2;
                double hill = Math.max(0, (n - 0.42) * 14);
                heights[ForestSite.index(x, z)] = (int) Math.round(hill * fade);
            }
        }
        // Never more than one block between neighbours: every slope can be walked.
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < heights.length; i++) {
                int x = ForestSite.columnX(i);
                int z = ForestSite.columnZ(i);
                for (int[] side : ForestSite.SIDES) {
                    int nx = x + side[0];
                    int nz = z + side[1];
                    int neighbour = ForestSite.inBox(nx, nz) ? heights[ForestSite.index(nx, nz)] : 0;
                    if (heights[i] > neighbour + 1) {
                        heights[i] = neighbour + 1;
                        changed = true;
                    }
                }
            }
        }
        int top = CitadelLayout.GROUND_Y;
        for (int i = 0; i < heights.length; i++) {
            int x = ForestSite.columnX(i);
            int z = ForestSite.columnZ(i);
            if (heights[i] <= 0 || !site.inZone(x, z)) {
                continue;
            }
            for (int y = top; y < top + heights[i]; y++) {
                site.set(x, y, z, Material.DIRT);
            }
            site.set(x, top + heights[i], z, Material.GRASS_BLOCK);
            site.setGround(x, z, top + heights[i]);
        }
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    /** The forest floor: grass with patches of podzol, moss and rooted earth. */
    private static void floor(ForestSite site) {
        for (int x = ForestSite.MIN_X; x <= ForestSite.MAX_X; x++) {
            for (int z = ForestSite.MIN_Z; z <= ForestSite.MAX_Z; z++) {
                if (!site.inZone(x, z)) {
                    continue;
                }
                double podzol = site.noise(x, z, 9, 3);
                double moss = site.noise(x, z, 4, 5);
                Material top;
                if (podzol > 0.7) {
                    top = Material.PODZOL;
                } else if (moss > 0.72) {
                    top = Material.MOSS_BLOCK;
                } else if (site.hash(x, z, 6) < 0.015) {
                    top = Material.ROOTED_DIRT;
                } else {
                    continue;
                }
                site.set(x, site.ground(x, z), z, top);
            }
        }
    }

    /** Forest trails: trodden earth, a little winding, about three blocks wide. */
    private static void trails(ForestSite site) {
        for (int[][] trail : TRAILS) {
            for (int s = 0; s + 1 < trail.length; s++) {
                int[] a = trail[s];
                int[] b = trail[s + 1];
                int minX = Math.min(a[0], b[0]) - 3;
                int maxX = Math.max(a[0], b[0]) + 3;
                int minZ = Math.min(a[1], b[1]) - 3;
                int maxZ = Math.max(a[1], b[1]) + 3;
                for (int x = minX; x <= maxX; x++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        if (!site.inZone(x, z)) {
                            continue;
                        }
                        double d = segmentDistance(x, z, a, b);
                        double width = 1.2 + 0.6 * site.noise(x, z, 5, 7);
                        if (d > width) {
                            continue;
                        }
                        double n = site.hash(x, z, 8);
                        Material m = n < 0.5 ? Material.DIRT_PATH : n < 0.8 ? Material.COARSE_DIRT
                                : n < 0.9 ? Material.PODZOL : n < 0.95 ? Material.ROOTED_DIRT : Material.GRAVEL;
                        if (d > width - 0.5 && site.hash(x, z, 9) < 0.5) {
                            m = Material.GRASS_BLOCK;
                        }
                        site.set(x, site.ground(x, z), z, m);
                        site.mark(x, z, ForestSite.TRAIL);
                    }
                }
            }
        }
    }

    private static double segmentDistance(int x, int z, int[] a, int[] b) {
        double dx = b[0] - a[0];
        double dz = b[1] - a[1];
        double t = ((x - a[0]) * dx + (z - a[1]) * dz) / (dx * dx + dz * dz);
        t = Math.max(0, Math.min(1, t));
        return Math.hypot(x - (a[0] + dx * t), z - (a[1] + dz * t));
    }

    // ------------------------------------------------------------------ trees

    /** The giant trees, a pine on the crag, then the forest itself scattered around them, thinning out in glades. */
    private static void forest(ForestSite site, ForestTrees trees) {
        trees.grow(ForestTrees.Kind.GIANT_OAK, GIANTS[0][0], GIANTS[0][1]);
        trees.grow(ForestTrees.Kind.GIANT_DARK_OAK, GIANTS[1][0], GIANTS[1][1]);
        trees.grow(ForestTrees.Kind.PINE, CRAG[0] - 4, CRAG[1] + 1);

        List<ForestTrees.Tree> placed = new ArrayList<>(trees.trees());
        for (int attempt = 0; attempt < 6000; attempt++) {
            int x = site.range(ForestSite.MIN_X, ForestSite.MAX_X);
            int z = site.range(ForestSite.MIN_Z, ForestSite.MAX_Z);
            if (!site.inZone(x, z)) {
                continue;
            }
            // Clearings: the forest thins out where this noise is low.
            if (site.noise(x, z, 14, 10) < 0.36) {
                continue;
            }
            ForestTrees.Kind kind = species(site, x, z);
            double crown = ForestTrees.crownRadius(kind);
            if (ForestSite.edge(x, z) < crown * 0.9 + 0.5 || !site.isFree(x, z, ForestTrees.trunkRadius(kind) + 0.5)
                    || !site.avoids(x, z, 2.5, ForestSite.TRAIL) || !site.avoids(x, z, crown, ForestSite.BUILT)
                    || !site.avoids(x, z, 2.5, ForestSite.ROCK)) {
                continue;
            }
            boolean crowded = false;
            for (ForestTrees.Tree other : placed) {
                double spacing = 0.85 * (crown + other.crown()) + 0.5;
                if (Math.hypot(x - other.x(), z - other.z()) < spacing) {
                    crowded = true;
                    break;
                }
            }
            if (crowded) {
                continue;
            }
            trees.grow(kind, x, z);
            placed.add(trees.trees().getLast());
        }
    }

    /** Lighter woods of oak and birch near the keep, oak and dark oak in the middle, firs and pines by the wall. */
    private static ForestTrees.Kind species(ForestSite site, int x, int z) {
        double r = CitadelLayout.radius(x, z);
        double roll = site.random.nextDouble();
        double birchGrove = site.noise(x, z, 20, 11);
        if (roll < 0.015) {
            return ForestTrees.Kind.SNAG;
        }
        if (r < 52) {
            return birchGrove > 0.55 || roll < 0.35 ? ForestTrees.Kind.BIRCH
                    : roll < 0.85 ? ForestTrees.Kind.OAK : ForestTrees.Kind.DARK_OAK;
        }
        if (r < 78) {
            return roll < 0.35 ? ForestTrees.Kind.OAK : roll < 0.6 ? ForestTrees.Kind.DARK_OAK
                    : birchGrove > 0.6 || roll < 0.75 ? ForestTrees.Kind.BIRCH
                    : roll < 0.88 ? ForestTrees.Kind.FIR : ForestTrees.Kind.PINE;
        }
        return roll < 0.35 ? ForestTrees.Kind.FIR : roll < 0.6 ? ForestTrees.Kind.PINE
                : roll < 0.78 ? ForestTrees.Kind.DARK_OAK : roll < 0.9 ? ForestTrees.Kind.OAK : ForestTrees.Kind.BIRCH;
    }

    // ------------------------------------------------------------------ rocks, deadwood, undergrowth

    private static void boulders(ForestSite site, ForestRocks rocks) {
        int clusters = 0;
        for (int attempt = 0; attempt < 500 && clusters < 20; attempt++) {
            int x = site.range(ForestSite.MIN_X, ForestSite.MAX_X);
            int z = site.range(ForestSite.MIN_Z, ForestSite.MAX_Z);
            if (!site.inZone(x, z) || ForestSite.edge(x, z) < 6 || !site.isFree(x, z, 4)
                    || !site.avoids(x, z, 9, ForestSite.ROCK) || !site.avoids(x, z, 3, ForestSite.TRAIL)) {
                continue;
            }
            if (site.chance(0.55) ? rocks.cluster(x, z, site.range(0.8, 1.2))
                    : rocks.boulder(x, z, site.range(1.5, 2.6), site.range(1.2, 2.0), site.range(0, Math.PI),
                    site.range(2, 4), false, site.range(0, Math.PI * 2))) {
                clusters++;
            }
        }
        // Small stones along the trails.
        for (int attempt = 0; attempt < 120; attempt++) {
            int x = site.range(ForestSite.MIN_X, ForestSite.MAX_X);
            int z = site.range(ForestSite.MIN_Z, ForestSite.MAX_Z);
            if (site.inZone(x, z) && site.use(x, z) == ForestSite.FREE && site.isBareGround(x, z)
                    && !site.avoids(x, z, 2.5, ForestSite.TRAIL) && site.avoids(x, z, 1.5, ForestSite.TRAIL)
                    && site.avoids(x, z, 2, ForestSite.TRUNK)) {
                int g = site.ground(x, z);
                site.set(x, g + 1, z, site.chance(0.5) ? rocks.rockTop(x, g + 1, z) : rocks.rock(x, g + 1, z));
                site.mark(x, z, ForestSite.ROCK);
                rocks.dress(x, z);
            }
        }
    }

    private static void deadwood(ForestSite site, ForestTrees trees) {
        Material[] logs = {Material.OAK_LOG, Material.BIRCH_LOG, Material.SPRUCE_LOG, Material.DARK_OAK_LOG};
        int fallen = 0;
        for (int attempt = 0; attempt < 400 && fallen < 14; attempt++) {
            int x = site.range(ForestSite.MIN_X, ForestSite.MAX_X);
            int z = site.range(ForestSite.MIN_Z, ForestSite.MAX_Z);
            if (!site.inZone(x, z) || ForestSite.edge(x, z) < 4 || !site.avoids(x, z, 4, ForestSite.TRUNK)) {
                continue;
            }
            Axis axis = site.chance(0.5) ? Axis.X : Axis.Z;
            if (trees.fallenLog(x, z, axis, site.range(4, 9), logs[site.random.nextInt(logs.length)])) {
                fallen++;
            }
        }
        int stumps = 0;
        for (int attempt = 0; attempt < 300 && stumps < 14; attempt++) {
            int x = site.range(ForestSite.MIN_X, ForestSite.MAX_X);
            int z = site.range(ForestSite.MIN_Z, ForestSite.MAX_Z);
            if (site.inZone(x, z) && site.isFree(x, z, 1.5) && site.isBareGround(x, z)
                    && site.avoids(x, z, 2, ForestSite.TRAIL)) {
                trees.stump(x, z, logs[site.random.nextInt(logs.length)]);
                stumps++;
            }
        }
    }

    private static void undergrowth(ForestSite site, ForestTrees trees) {
        Material[] bushLeaves = {Material.OAK_LEAVES, Material.OAK_LEAVES, Material.AZALEA_LEAVES,
                Material.FLOWERING_AZALEA_LEAVES, Material.SPRUCE_LEAVES, Material.DARK_OAK_LEAVES};
        int bushes = 0;
        for (int attempt = 0; attempt < 900 && bushes < 70; attempt++) {
            int x = site.range(ForestSite.MIN_X, ForestSite.MAX_X);
            int z = site.range(ForestSite.MIN_Z, ForestSite.MAX_Z);
            if (site.inZone(x, z) && ForestSite.edge(x, z) >= 2.5 && site.isFree(x, z, 2)
                    && site.avoids(x, z, 2.5, ForestSite.TRAIL) && site.isBareGround(x, z)) {
                trees.bush(x, z, bushLeaves[site.random.nextInt(bushLeaves.length)]);
                bushes++;
            }
        }
        Material[] flowers = {Material.LILY_OF_THE_VALLEY, Material.OXEYE_DAISY, Material.AZURE_BLUET,
                Material.CORNFLOWER, Material.ALLIUM, Material.POPPY, Material.DANDELION};
        for (int x = ForestSite.MIN_X; x <= ForestSite.MAX_X; x++) {
            for (int z = ForestSite.MIN_Z; z <= ForestSite.MAX_Z; z++) {
                if (!site.inZone(x, z) || !site.isBareGround(x, z)) {
                    continue;
                }
                int g = site.ground(x, z);
                if (!site.isAir(x, g + 1, z) || !site.isAir(x, g + 2, z)) {
                    continue;
                }
                BlockSpec ground = site.get(x, g, z);
                if (ground == null) {
                    continue;
                }
                byte use = site.use(x, z);
                double fern = site.noise(x, z, 6, 20);
                double bloom = site.noise(x, z, 9, 21);
                double r = site.hash(x, z, 22);
                boolean trail = use == ForestSite.TRAIL || use == ForestSite.BUILT;
                Material plant = null;
                switch (ground.material()) {
                    case GRASS_BLOCK -> {
                        if (trail) {
                            plant = r < 0.08 ? Material.SHORT_GRASS : null;
                        } else if (r < fern * 0.35) {
                            plant = r < fern * 0.06 ? Material.LARGE_FERN : Material.FERN;
                        } else if (r < 0.32) {
                            plant = r < 0.3 ? Material.SHORT_GRASS : Material.TALL_GRASS;
                        } else if (bloom > 0.66 && r < 0.45) {
                            plant = flowers[(int) (site.hash(x, z, 23) * flowers.length)];
                        } else if (r > 0.995) {
                            plant = Material.FIREFLY_BUSH;
                        }
                    }
                    case PODZOL, COARSE_DIRT, ROOTED_DIRT -> {
                        if (!trail) {
                            plant = r < 0.14 ? Material.FERN : r < 0.18 ? Material.BROWN_MUSHROOM
                                    : r < 0.2 ? Material.RED_MUSHROOM : r < 0.42 ? Material.LEAF_LITTER : null;
                        } else if (r < 0.1) {
                            plant = Material.LEAF_LITTER;
                        }
                    }
                    case MOSS_BLOCK -> plant = trail ? null : r < 0.35 && site.isLevel(x, z, g)
                            ? Material.MOSS_CARPET : r < 0.45 ? Material.FERN : null;
                    default -> {
                    }
                }
                if (plant == Material.LARGE_FERN || plant == Material.TALL_GRASS) {
                    Blocks.tallPlant(site.canvas, x, g + 1, z, plant);
                } else if (plant != null) {
                    site.set(x, g + 1, z, plant);
                }
            }
        }
    }

    // ------------------------------------------------------------------ finishing touches

    /** Spore blossoms, pale moss and roots hanging from the undersides of crowns and branches. */
    private static void hangingGrowth(ForestSite site) {
        for (int x = ForestSite.MIN_X; x <= ForestSite.MAX_X; x++) {
            for (int z = ForestSite.MIN_Z; z <= ForestSite.MAX_Z; z++) {
                if (!site.inZone(x, z) || !site.canopy().isCanopy(x, z)) {
                    continue;
                }
                int g = site.ground(x, z);
                int above = g + 1;
                while (above < g + 20 && site.isOpen(x, above, z)) {
                    above++;
                }
                BlockSpec crown = site.get(x, above, z);
                if (crown == null || above < g + 6 || above >= g + 20 || !site.isAir(x, above - 1, z)) {
                    continue;
                }
                String name = crown.material().name();
                double r = site.hash(x, z, 32);
                if (name.endsWith("_LOG") && r < 0.3) {
                    site.set(x, above - 1, z, Material.HANGING_ROOTS);
                } else if ((name.startsWith("AZALEA") || name.startsWith("FLOWERING")) && r < 0.08
                        || name.startsWith("DARK_OAK") && r < 0.025) {
                    site.set(x, above - 1, z, Material.SPORE_BLOSSOM);
                } else if ((name.startsWith("SPRUCE") || name.startsWith("DARK_OAK")) && r > 0.95) {
                    site.set(x, above - 1, z, Material.PALE_HANGING_MOSS);
                    if (above - 2 >= g + 5 && r > 0.98) {
                        site.set(x, above - 2, z, Material.PALE_HANGING_MOSS);
                    }
                }
            }
        }
    }

    /** Lanterns hung on chains from the crowns over the trails, a lit way through the woods. */
    private static void trailLanterns(ForestSite site) {
        List<int[]> placed = new ArrayList<>();
        for (int x = ForestSite.MIN_X; x <= ForestSite.MAX_X; x++) {
            for (int z = ForestSite.MIN_Z; z <= ForestSite.MAX_Z; z++) {
                if (!site.inZone(x, z) || site.use(x, z) != ForestSite.TRAIL || site.hash(x, z, 30) > 0.35) {
                    continue;
                }
                int g = site.ground(x, z);
                int above = g + 1;
                while (above < g + 12 && site.isOpen(x, above, z)) {
                    above++;
                }
                BlockSpec crown = site.get(x, above, z);
                if (above < g + 6 || above > g + 8 || crown == null || !crown.material().name().endsWith("_LEAVES")
                        && !crown.material().name().endsWith("_LOG")) {
                    continue;
                }
                boolean near = false;
                for (int[] p : placed) {
                    near |= Math.hypot(p[0] - x, p[1] - z) < 11;
                }
                if (near) {
                    continue;
                }
                for (int y = above - 1; y > g + 4; y--) {
                    site.set(x, y, z, Blocks.log(Material.IRON_CHAIN, Axis.Y));
                }
                site.set(x, g + 4, z, Blocks.lantern(site.hash(x, z, 31) < 0.3, true));
                placed.add(new int[] {x, z});
            }
        }
    }
}
