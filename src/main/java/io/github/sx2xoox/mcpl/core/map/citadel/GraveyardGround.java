package io.github.sx2xoox.mcpl.core.map.citadel;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import io.github.sx2xoox.mcpl.core.map.citadel.GraveyardBuilder.Palette;
import io.github.sx2xoox.mcpl.core.map.citadel.GraveyardBuilder.Use;
import org.bukkit.Material;

/**
 * The graveyard's ground: the path network (a paved processional way from the ring road to the temple, an arc lane in
 * front of the temple, lanes along its sides and a back lane that runs through the barrow), the texture of the soil
 * (grass with patches of podzol, coarse dirt and moss) and the plants that grow on it.
 */
final class GraveyardGround {

    static final Palette PAVED = Palette.of(Material.STONE_BRICKS, 5, Material.MOSSY_STONE_BRICKS, 3,
            Material.CRACKED_STONE_BRICKS, 3, Material.POLISHED_ANDESITE, 2, Material.ANDESITE, 1,
            Material.MOSS_BLOCK, 1, Material.COBBLESTONE, 1);
    static final Palette LANE = Palette.of(Material.DIRT_PATH, 6, Material.COARSE_DIRT, 3, Material.GRAVEL, 2,
            Material.MOSSY_COBBLESTONE, 1);

    /** Points of the back lane, west road to east road (it passes through the barrow's tunnel). */
    static final int[][] BACK_LANE = {{-42, -79}, {-32, -86}, {-10, -86}, {0, -88}, {12, -87}, {17, -82},
            {18, -79}};

    private final GraveyardBuilder b;

    GraveyardGround(GraveyardBuilder builder) {
        this.b = builder;
    }

    // ------------------------------------------------------------------ paths

    void paths() {
        // The processional way and the statue plaza.
        segment(0, -36, 0, -52, 2.5, PAVED, 0.0);
        disk(0, -46, 4.6, PAVED);
        // Lanes.
        arc(51, 240, 300, 1.2, LANE);
        segment(-19, -52, -18, -82, 1.5, LANE, 0.3);
        segment(19, -51, 18, -80, 1.5, LANE, 0.3);
        for (int i = 0; i + 1 < BACK_LANE.length; i++) {
            int[] a = BACK_LANE[i];
            int[] c = BACK_LANE[i + 1];
            segment(a[0], a[1], c[0], c[1], 1.5, LANE, 0.25);
        }
        segment(0, -84, 0, -88, 2.5, PAVED, 0.2);
    }

    /** Paths along the line from {@code x1,z1} to {@code x2,z2}; {@code ragged} is the chance to skip edge blocks. */
    void segment(int x1, int z1, int x2, int z2, double halfWidth, Palette palette, double ragged) {
        int pad = (int) Math.ceil(halfWidth) + 1;
        for (int x = Math.min(x1, x2) - pad; x <= Math.max(x1, x2) + pad; x++) {
            for (int z = Math.min(z1, z2) - pad; z <= Math.max(z1, z2) + pad; z++) {
                double d = distanceToSegment(x, z, x1, z1, x2, z2);
                if (d <= halfWidth) {
                    pave(x, z, palette, d > halfWidth - 1 && b.chance(ragged));
                }
            }
        }
    }

    private void arc(double radius, double fromAngle, double toAngle, double halfWidth, Palette palette) {
        for (int x = GraveyardBuilder.MIN_X; x <= GraveyardBuilder.MAX_X; x++) {
            for (int z = GraveyardBuilder.MIN_Z; z <= GraveyardBuilder.MAX_Z; z++) {
                double a = CitadelLayout.angle(x, z);
                if (a < fromAngle || a > toAngle) {
                    continue;
                }
                double d = Math.abs(CitadelLayout.radius(x, z) - radius);
                if (d <= halfWidth) {
                    pave(x, z, palette, d > halfWidth - 1 && b.chance(0.25));
                }
            }
        }
    }

    private void disk(int cx, int cz, double radius, Palette palette) {
        int r = (int) Math.ceil(radius);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz <= radius * radius) {
                    pave(cx + dx, cz + dz, palette, false);
                }
            }
        }
    }

    private void pave(int x, int z, Palette palette, boolean skip) {
        // The first path drawn over a column keeps it (the paved way stays paved where lanes cross it).
        if (!b.inside(x, z) || b.use(x, z) != Use.FREE) {
            return;
        }
        b.mark(x, z, Use.PATH);
        if (!skip) {
            b.set(x, GraveyardBuilder.GROUND, z, palette.pick(b.random));
        }
    }

    static double distanceToSegment(double px, double pz, double x1, double z1, double x2, double z2) {
        double dx = x2 - x1;
        double dz = z2 - z1;
        double len2 = dx * dx + dz * dz;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - x1) * dx + (pz - z1) * dz) / len2));
        double cx = x1 + dx * t;
        double cz = z1 + dz * t;
        return Math.hypot(px - cx, pz - cz);
    }

    // ------------------------------------------------------------------ terrain

    /**
     * Gentle rolls of the open ground, up to two blocks: never next to paths or buildings (they stay flat), flat near
     * the district's edges and in the entrance courtyard, and never more than a block of slope.
     */
    void rolls() {
        int w = GraveyardBuilder.MAX_X - GraveyardBuilder.MIN_X + 1;
        int d = GraveyardBuilder.MAX_Z - GraveyardBuilder.MIN_Z + 1;
        int[][] h = new int[w][d];
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < d; j++) {
                int x = GraveyardBuilder.MIN_X + i;
                int z = GraveyardBuilder.MIN_Z + j;
                if (!b.inside(x, z) || b.use(x, z) != Use.FREE || GraveyardBuilder.margin(x, z) < 4
                        || CitadelLayout.radius(x, z) < 47) {
                    continue;
                }
                double n = b.noise(x, z, 13, 7);
                h[i][j] = Math.max(0, Math.min(2, (int) Math.floor((n - 0.42) * 7)));
            }
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < w; i++) {
                for (int j = 0; j < d; j++) {
                    int lowest = Math.min(Math.min(i > 0 ? h[i - 1][j] : 0, i < w - 1 ? h[i + 1][j] : 0),
                            Math.min(j > 0 ? h[i][j - 1] : 0, j < d - 1 ? h[i][j + 1] : 0));
                    if (h[i][j] > lowest + 1) {
                        h[i][j] = lowest + 1;
                        changed = true;
                    }
                }
            }
        }
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < d; j++) {
                if (h[i][j] > 0) {
                    b.raiseGround(GraveyardBuilder.MIN_X + i, GraveyardBuilder.MIN_Z + j,
                            GraveyardBuilder.GROUND + h[i][j], BlockSpec.of(Material.GRASS_BLOCK));
                }
            }
        }
    }

    // ------------------------------------------------------------------ soil

    /** Grass with patches of podzol, coarse dirt, rooted dirt and moss on every free column. */
    void texture() {
        for (int x = GraveyardBuilder.MIN_X; x <= GraveyardBuilder.MAX_X; x++) {
            for (int z = GraveyardBuilder.MIN_Z; z <= GraveyardBuilder.MAX_Z; z++) {
                Use use = b.use(x, z);
                if (!b.inside(x, z) || use == Use.PATH || use == Use.STRUCTURE) {
                    continue;
                }
                int y = b.ground(x, z);
                BlockSpec top = b.get(x, y, z);
                if (top == null || top.material() != Material.GRASS_BLOCK) {
                    continue;
                }
                b.set(x, y, z, soil(x, z));
            }
        }
    }

    private BlockSpec soil(int x, int z) {
        double dead = b.noise(x, z, 11, 1) * 0.75 + b.noise(x, z, 4, 4) * 0.25;
        double moss = b.noise(x, z, 7, 2);
        double mix = b.noise(x, z, 3, 3);
        if (dead > 0.66) {
            // Patches of dead, dark earth: podzol in the middle, coarse dirt around, a little rooted dirt.
            if (dead > 0.72) {
                return BlockSpec.of(mix < 0.65 ? Material.PODZOL : Material.ROOTED_DIRT);
            }
            return BlockSpec.of(mix < 0.5 ? Material.COARSE_DIRT : Material.PODZOL);
        }
        if (moss > 0.76) {
            return BlockSpec.of(mix < 0.75 ? Material.MOSS_BLOCK : Material.GRASS_BLOCK);
        }
        return BlockSpec.of(Material.GRASS_BLOCK);
    }

    // ------------------------------------------------------------------ plants

    /** Grass, ferns, dead bushes and a few pale flowers on open soil (run last, after everything is built). */
    void cover() {
        for (int x = GraveyardBuilder.MIN_X; x <= GraveyardBuilder.MAX_X; x++) {
            for (int z = GraveyardBuilder.MIN_Z; z <= GraveyardBuilder.MAX_Z; z++) {
                if (!b.inside(x, z) || b.use(x, z) == Use.PATH || b.use(x, z) == Use.GRAVE) {
                    continue;
                }
                int y = b.canvas.surfaceY(x, z);
                BlockSpec top = b.get(x, y, z);
                if (top == null || !b.isAir(x, y + 1, z) || !b.isAir(x, y + 2, z)) {
                    continue;
                }
                plant(x, y + 1, z, top.material());
            }
        }
    }

    private void plant(int x, int y, int z, Material soil) {
        double roll = b.hash(x, z, 41);
        double lush = b.noise(x, z, 7, 42);
        boolean dirt = soil == Material.PODZOL || soil == Material.COARSE_DIRT || soil == Material.ROOTED_DIRT;
        boolean green = soil == Material.GRASS_BLOCK || soil == Material.MOSS_BLOCK;
        if (dirt) {
            if (roll < 0.10) {
                b.set(x, y, z, Material.DEAD_BUSH);
            } else if (roll < 0.16) {
                b.set(x, y, z, Material.SHORT_GRASS);
            } else if (roll < 0.19) {
                b.set(x, y, z, Material.LEAF_LITTER);
            }
            return;
        }
        if (!green) {
            return;
        }
        double grass = 0.03 + lush * 0.15;
        if (roll < grass) {
            b.set(x, y, z, Material.SHORT_GRASS);
        } else if (roll < grass + 0.025) {
            b.set(x, y, z, Material.FERN);
        } else if (roll < grass + 0.035 && lush > 0.5) {
            Blocks.tallPlant(b.canvas, x, y, z, Material.TALL_GRASS);
        } else if (roll < grass + 0.042) {
            b.set(x, y, z, b.pick(FLOWERS));
        } else if (roll < grass + 0.047) {
            b.set(x, y, z, roll * 1000 % 2 < 1 ? Material.CLOSED_EYEBLOSSOM : Material.OPEN_EYEBLOSSOM);
        } else if (roll < grass + 0.05) {
            b.set(x, y, z, Material.FIREFLY_BUSH);
        }
    }

    private static final Material[] FLOWERS = {Material.LILY_OF_THE_VALLEY, Material.AZURE_BLUET,
            Material.OXEYE_DAISY, Material.POPPY, Material.LILY_OF_THE_VALLEY};
}
