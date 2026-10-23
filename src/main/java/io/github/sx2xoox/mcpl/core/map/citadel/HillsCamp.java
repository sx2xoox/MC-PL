package io.github.sx2xoox.mcpl.core.map.citadel;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

/**
 * The army camp in the hollow below the hills: a ring palisade of logs with four gates and two breaches, a striped
 * command tent and two canvas tents (all open at both ends), a cold campfire with log benches, weapon racks and
 * stacked supplies.
 *
 * <p>Local coordinates: {@code u} east, {@code v} south of the camp's center; {@code y0} is the y of the ground.
 */
final class HillsCamp {

    /** Directions (degrees, 0 = east, 90 = south) of the gates: the tower, the keep, the north-east road, the mill. */
    private static final double[] GATES = {21, 135, 210, 318};
    /** Half the width of a gate, in degrees of the palisade circle. */
    private static final double GATE_HALF = 10;
    /** Where the palisade has fallen down (degrees). */
    private static final double[] BREACHES = {78, 262};

    private final HillsSite site;
    private final int cx;
    private final int cz;
    private final int y0;
    private final double radius = HillsDistrict.CAMP_RADIUS;

    HillsCamp(HillsSite site, int[] center) {
        this.site = site;
        this.cx = center[0];
        this.cz = center[1];
        this.y0 = site.ground(cx, cz);
    }

    void build() {
        trampledGround();
        palisade();
        campfire();
        commandTent();
        tent(-7, 2, -1, 5, true, Material.WHITE_WOOL, Material.LIGHT_GRAY_WOOL);
        tent(3, 1, 6, 7, false, Material.LIGHT_GRAY_WOOL, Material.WHITE_WOOL);
        racks();
        supplies();
    }

    // ------------------------------------------------------------------ parts

    private void trampledGround() {
        for (int u = -12; u <= 12; u++) {
            for (int v = -12; v <= 12; v++) {
                int x = cx + u;
                int z = cz + v;
                double d = Math.sqrt(u * u + v * v);
                if (d > radius + 1.5 || site.use(x, z) != HillsSite.Use.STRUCTURE) {
                    continue;
                }
                double worn = site.noise(x / 3.0, z / 3.0, 81) + (d < radius ? 0.35 : -0.3);
                Material top = worn > 0.55 ? site.pick(Material.COARSE_DIRT, Material.DIRT_PATH, Material.GRAVEL)
                        : worn > 0.1 ? site.pick(Material.COARSE_DIRT, Material.GRASS_BLOCK, Material.PODZOL,
                        Material.DIRT_PATH) : Material.GRASS_BLOCK;
                site.set(x, site.ground(x, z), z, top);
            }
        }
    }

    /** A ring of upright logs, 3 to 4 high, with gates and breaches (lumps: they get climbable broken ends). */
    private void palisade() {
        for (int[] cell : HillsSite.ring(radius)) {
            int u = cell[0];
            int v = cell[1];
            double angle = HillsSite.degrees(u, v);
            if (near(angle, GATES, GATE_HALF)) {
                continue;
            }
            int x = cx + u;
            int z = cz + v;
            int height;
            if (near(angle, GATES, GATE_HALF + 7)) {
                height = 4; // gate posts
            } else if (near(angle, BREACHES, 9)) {
                height = site.hash(x, z, 82) > 0.2 ? 1 : 0;
            } else {
                height = site.hash(x, z, 83) > 0.45 ? 4 : 3;
            }
            if (height == 0) {
                if (site.hash(x, z, 84) > -0.2) {
                    // A log fallen out of the wall.
                    site.stack(x, z, site.ground(x, z) + 1, Blocks.log(Material.SPRUCE_LOG,
                            Math.abs(u) > Math.abs(v) ? Axis.Z : Axis.X));
                }
                continue;
            }
            double roll = site.random.nextDouble();
            Material log = roll < 0.72 ? Material.SPRUCE_LOG : roll < 0.86 ? Material.OAK_LOG
                    : Material.STRIPPED_SPRUCE_LOG;
            site.stack(x, z, site.ground(x, z) + height, Blocks.log(log, Axis.Y));
        }
    }

    private static boolean near(double angle, double[] targets, double within) {
        for (double target : targets) {
            double diff = Math.abs(angle - target) % 360;
            if (Math.min(diff, 360 - diff) <= within) {
                return true;
            }
        }
        return false;
    }

    /** A cold campfire ringed with stone, and log benches around it. */
    private void campfire() {
        set(0, y0 + 1, 0, Blocks.campfire(false));
        for (int u = -1; u <= 1; u++) {
            for (int v = -1; v <= 1; v++) {
                if (u != 0 || v != 0) {
                    set(u, y0 + 1, v, Blocks.slab(site.pick(Material.COBBLESTONE_SLAB,
                            Material.MOSSY_COBBLESTONE_SLAB, Material.STONE_SLAB)));
                }
            }
        }
        for (int v = -1; v <= 1; v++) {
            bench(-3, v, Axis.Z);
        }
        for (int v = -2; v <= 0; v++) {
            bench(3, v, Axis.Z);
        }
    }

    private void bench(int u, int v, Axis axis) {
        site.stack(cx + u, cz + v, y0 + 1, Blocks.log(Material.SPRUCE_LOG, axis));
    }

    /**
     * The command tent north of the fire: nine wide, ridge running north-south, red and white stripes, both ends
     * open (four blocks of head room under the ridge).
     */
    private void commandTent() {
        for (int v = -7; v <= -3; v++) {
            Material cloth = v % 2 == 0 ? Material.RED_WOOL : Material.WHITE_WOOL;
            for (int u = -4; u <= 4; u++) {
                int rise = 4 - Math.abs(u);
                if (rise == 4) {
                    set(u, y0 + 5, v, Blocks.log(Material.SPRUCE_LOG, Axis.Z));
                } else {
                    set(u, y0 + 1 + rise, v, BlockSpec.of(cloth));
                }
            }
        }
        // Inside: the map table, a lantern and a chest of orders.
        set(-2, y0 + 1, -6, BlockSpec.of(Material.CARTOGRAPHY_TABLE));
        set(2, y0 + 1, -6, BlockSpec.of(Material.BARREL).facing(BlockFace.UP));
        set(-2, y0 + 1, -4, Blocks.lantern(false, false));
    }

    /**
     * An A-frame canvas tent seven wide between {@code u1..u2, v1..v2}; {@code alongZ} means the ridge runs north to
     * south (open ends north and south), otherwise east to west.
     */
    private void tent(int u1, int v1, int u2, int v2, boolean alongZ, Material cloth, Material patch) {
        int mid = alongZ ? (u1 + u2) / 2 : (v1 + v2) / 2;
        for (int u = u1; u <= u2; u++) {
            for (int v = v1; v <= v2; v++) {
                int across = alongZ ? u - mid : v - mid;
                int rise = 3 - Math.abs(across);
                if (rise < 0) {
                    continue;
                }
                if (rise == 3) {
                    set(u, y0 + 4, v, Blocks.log(Material.SPRUCE_LOG, alongZ ? Axis.Z : Axis.X));
                } else {
                    set(u, y0 + 1 + rise, v, BlockSpec.of(site.chance(0.15) ? patch : cloth));
                }
            }
        }
        // A bedroll inside.
        int bu = alongZ ? mid : u1 + 1;
        int bv = alongZ ? v1 + 1 : mid;
        set(bu, y0 + 1, bv, BlockSpec.of(Material.BROWN_CARPET));
    }

    /** Spear racks: fence rails with lightning-rod spears, a barrel at the end to climb on. */
    private void racks() {
        rack(5, 7, -1);
        rack(-7, -5, -1);
    }

    private void rack(int u1, int u2, int v) {
        for (int u = u1; u <= u2; u++) {
            set(u, y0 + 1, v, BlockSpec.of(Material.SPRUCE_FENCE));
            set(u, y0 + 2, v, BlockSpec.of(Material.LIGHTNING_ROD));
            site.mark(cx + u, cz + v, HillsSite.Use.STRUCTURE);
        }
    }

    /** Crates, barrels and hay stacked by the racks and the palisade (lumps, so the stacks can be climbed). */
    private void supplies() {
        crate(8, -1, 1, Material.BARREL);
        crate(8, -2, 2, Material.HAY_BLOCK);
        crate(7, -2, 1, Material.HAY_BLOCK);
        crate(-8, -1, 1, Material.BARREL);
        crate(-8, 0, 1, Material.SPRUCE_PLANKS);
        crate(-9, 0, 2, Material.HAY_BLOCK);
        crate(-2, 7, 2, Material.SPRUCE_PLANKS);
        crate(-1, 7, 1, Material.BARREL);
        crate(-2, 8, 1, Material.HAY_BLOCK);
        set(1, y0 + 1, 7, Blocks.lantern(false, false));
        set(-6, y0 + 1, -3, Blocks.lantern(false, false));
        set(6, y0 + 1, -3, Blocks.lantern(false, false));
    }

    private void crate(int u, int v, int height, Material material) {
        BlockSpec spec = material == Material.BARREL ? BlockSpec.of(material).facing(BlockFace.UP)
                : material == Material.HAY_BLOCK ? Blocks.log(material, Axis.Y) : BlockSpec.of(material);
        site.stack(cx + u, cz + v, y0 + height, spec);
    }

    private void set(int u, int y, int v, BlockSpec spec) {
        site.set(cx + u, y, cz + v, spec);
    }
}
