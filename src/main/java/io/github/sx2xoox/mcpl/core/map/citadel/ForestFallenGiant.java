package io.github.sx2xoox.mcpl.core.map.citadel;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import org.bukkit.Material;

/**
 * A fallen giant: the hollow trunk of an ancient tree lying along the east-west axis, a tunnel five blocks wide and
 * five high with a hole broken through its side. Its mossy back can be climbed from the earth its roots tore up on the
 * north side.
 */
final class ForestFallenGiant {

    private static final int HALF_LENGTH = 7;
    private static final double OUTER = 3.4;
    private static final double INNER = 2.4;

    private final ForestSite site;
    private final int cx;
    private final int cz;
    private final int g;

    ForestFallenGiant(ForestSite site, int cx, int cz) {
        this.site = site;
        this.cx = cx;
        this.cz = cz;
        this.g = site.ground(cx, cz);
    }

    void build() {
        int cy = g + 3;
        double outer = (OUTER + 0.5) * (OUTER + 0.5);
        double inner = (INNER + 0.5) * (INNER + 0.5);
        for (int x = cx - HALF_LENGTH; x <= cx + HALF_LENGTH; x++) {
            boolean end = Math.abs(x - cx) >= HALF_LENGTH - 1;
            for (int dz = -4; dz <= 4; dz++) {
                int z = cz + dz;
                site.mark(x, z, ForestSite.BUILT);
                site.canopy().limit(x, z, g);
                for (int dy = -2; dy <= 3; dy++) {
                    double rr = dz * dz + dy * dy;
                    if (rr > outer) {
                        continue;
                    }
                    int y = cy + dy;
                    if (rr <= inner) {
                        site.clear(x, y, z);
                        continue;
                    }
                    // Ragged, broken ends.
                    if (end && site.hash(x, y * 13 + z, 91) < 0.35) {
                        continue;
                    }
                    site.set(x, y, z, shell(x, y, z));
                }
                if (Math.abs(dz) <= 2) {
                    site.set(x, g, z, floor(x, z));
                }
            }
        }
        // A hole broken through the south side: a third way in.
        for (int x = cx - 1; x <= cx; x++) {
            for (int y = g + 1; y <= g + 3; y++) {
                site.clear(x, y, cz + 3);
            }
            site.set(x, g, cz + 3, floor(x, cz + 3));
        }
        mound();
        dressing();
    }

    private BlockSpec shell(int x, int y, int z) {
        double moss = site.noise(x, z * 2 + y, 3, 92);
        if (y >= g + 5 && moss > 0.45) {
            return BlockSpec.of(Material.MOSS_BLOCK);
        }
        return BlockSpec.of(moss < 0.15 ? Material.MOSSY_COBBLESTONE : Material.OAK_WOOD);
    }

    private BlockSpec floor(int x, int z) {
        double n = site.hash(x, z, 93);
        return BlockSpec.of(n < 0.4 ? Material.PODZOL : n < 0.7 ? Material.MOSS_BLOCK : Material.ROOTED_DIRT);
    }

    /** The torn-up earth on the north side near the root end: a slope onto the trunk's back. */
    private void mound() {
        boolean[] region = new boolean[ForestSite.columns()];
        for (int x = cx - HALF_LENGTH + 1; x <= cx + HALF_LENGTH - 1; x++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (ForestSite.inBox(x, cz + dz)) {
                    region[ForestSite.index(x, cz + dz)] = true;
                }
            }
        }
        for (int x = cx + 1; x <= cx + HALF_LENGTH + 1; x++) {
            for (int dz = -9; dz <= -4; dz++) {
                int z = cz + dz;
                if (site.inZone(x, z) && (site.use(x, z) == ForestSite.FREE || site.use(x, z) == ForestSite.BUILT)) {
                    region[ForestSite.index(x, z)] = true;
                    site.mark(x, z, ForestSite.ROCK);
                }
            }
        }
        site.raiseToSlope(region, null, site::top, (x, y, z, top) -> {
            if (Math.abs(z - cz) <= 3) {
                return top ? BlockSpec.of(Material.MOSS_BLOCK) : BlockSpec.of(Material.OAK_WOOD);
            }
            if (top) {
                double n = site.hash(x, z, 94);
                return BlockSpec.of(n < 0.4 ? Material.GRASS_BLOCK : n < 0.7 ? Material.COARSE_DIRT
                        : Material.ROOTED_DIRT);
            }
            return BlockSpec.of(site.hash(x, y, 95) < 0.3 ? Material.ROOTED_DIRT : Material.DIRT);
        });
        // The mound's surface is the new ground for plants.
        for (int x = cx + 1; x <= cx + HALF_LENGTH + 1; x++) {
            for (int dz = -9; dz <= -4; dz++) {
                int z = cz + dz;
                if (site.inZone(x, z)) {
                    site.setGround(x, z, site.top(x, z));
                }
            }
        }
    }

    private void dressing() {
        for (int x = cx - HALF_LENGTH; x <= cx + HALF_LENGTH; x++) {
            for (int dz = -3; dz <= 3; dz++) {
                int z = cz + dz;
                int top = site.top(x, z);
                BlockSpec at = site.get(x, top, z);
                if (top > g + 3 && at != null && at.material() == Material.MOSS_BLOCK && site.isAir(x, top + 1, z)) {
                    double n = site.hash(x, z, 96);
                    if (n < 0.3) {
                        site.set(x, top + 1, z, Material.MOSS_CARPET);
                    } else if (n < 0.38) {
                        site.set(x, top + 1, z, Material.FERN);
                    } else if (n < 0.43) {
                        site.set(x, top + 1, z, Material.BROWN_MUSHROOM);
                    }
                }
                // Mushrooms and hanging roots inside.
                if (Math.abs(dz) <= 2 && site.isAir(x, g + 1, z)) {
                    double n = site.hash(x, z, 97);
                    if (n < 0.06) {
                        site.set(x, g + 1, z, Material.RED_MUSHROOM);
                    } else if (n < 0.14) {
                        site.set(x, g + 1, z, Material.BROWN_MUSHROOM);
                    } else if (n < 0.3) {
                        site.set(x, g + 1, z, Material.LEAF_LITTER);
                    }
                }
                if (dz == 0 && site.hash(x, z, 98) < 0.3 && site.isAir(x, g + 5, z) && !site.isAir(x, g + 6, z)) {
                    site.set(x, g + 5, z, Material.HANGING_ROOTS);
                }
            }
        }
    }
}
