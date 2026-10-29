package io.github.sx2xoox.mcpl.game.augment.bot;

import java.util.EnumSet;
import java.util.Set;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * What the world can kill a bot with, and the {@link Recovery.Grid} over a Bukkit world. Powder snow is the sneaky
 * one: it does not block motion, so it is not part of the height maps (the "top" of a column is the block under it)
 * and a walker sinks in and freezes; every column check therefore looks above the top too.
 */
public final class Hazards {

    /** Blocks that hurt, trap or freeze whoever is in or on them. */
    static final Set<Material> HAZARDS = EnumSet.of(Material.POWDER_SNOW, Material.LAVA, Material.FIRE,
            Material.SOUL_FIRE, Material.CACTUS, Material.SWEET_BERRY_BUSH, Material.MAGMA_BLOCK, Material.CAMPFIRE,
            Material.SOUL_CAMPFIRE, Material.WITHER_ROSE, Material.POINTED_DRIPSTONE, Material.COBWEB,
            Material.HONEY_BLOCK);

    /** Blocks a bot never breaks. */
    static final Set<Material> UNBREAKABLE = EnumSet.of(Material.BEDROCK, Material.BARRIER, Material.END_PORTAL_FRAME,
            Material.END_PORTAL, Material.END_GATEWAY, Material.NETHER_PORTAL, Material.COMMAND_BLOCK,
            Material.CHAIN_COMMAND_BLOCK, Material.REPEATING_COMMAND_BLOCK, Material.STRUCTURE_BLOCK, Material.JIGSAW,
            Material.LIGHT, Material.REINFORCED_DEEPSLATE, Material.SPAWNER, Material.TRIAL_SPAWNER, Material.VAULT,
            Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL);

    private Hazards() {
    }

    public static boolean hazard(Material type) {
        return HAZARDS.contains(type);
    }

    /** A hazard at the block, or powder snow / fire right above a floor (sinks in, burns). */
    public static boolean hazard(Block block) {
        return HAZARDS.contains(block.getType());
    }

    /**
     * True if the column's surface is dangerous to stand on or next to: its top (as the height map sees it) is a
     * hazard, or powder snow (or another non-solid hazard) lies on it.
     */
    public static boolean hazardousColumn(World world, int x, int z) {
        Block top = world.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (hazard(top)) {
            return true;
        }
        for (int dy = 1; dy <= 2; dy++) {
            if (hazard(top.getRelative(0, dy, 0))) {
                return true;
            }
        }
        return false;
    }

    /** True if there is a hazard within {@code radius} blocks around (and one below) the standing spot. */
    public static boolean hazardNear(Location feet, int radius) {
        World world = feet.getWorld();
        int x = feet.getBlockX();
        int y = feet.getBlockY();
        int z = feet.getBlockZ();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    Block block = world.getBlockAt(x + dx, y + dy, z + dz);
                    if (hazard(block) || block.isLiquid()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** The legs' feet or head are in powder snow (it sinks, it freezes). */
    public static boolean inPowderSnow(Location feet) {
        Block at = feet.getBlock();
        return at.getType() == Material.POWDER_SNOW || at.getRelative(0, 1, 0).getType() == Material.POWDER_SNOW;
    }

    /** True if the feet are under the open sky (nothing that blocks motion above the head). */
    public static boolean underground(Location feet) {
        World world = feet.getWorld();
        int top = world.getHighestBlockYAt(feet.getBlockX(), feet.getBlockZ(), HeightMap.MOTION_BLOCKING_NO_LEAVES);
        return top > feet.getBlockY() + 2;
    }

    /** {@link Recovery.Grid} over a Bukkit world. */
    record WorldGrid(World world) implements Recovery.Grid {
        @Override
        public boolean solid(int x, int y, int z) {
            return world.getBlockAt(x, y, z).getType().isSolid() && !hazard(x, y, z);
        }

        @Override
        public boolean breakable(int x, int y, int z) {
            if (y <= world.getMinHeight() || y >= world.getMaxHeight() - 1) {
                return false;
            }
            Material type = world.getBlockAt(x, y, z).getType();
            return !UNBREAKABLE.contains(type) && type != Material.LAVA && type != Material.WATER;
        }

        @Override
        public boolean liquid(int x, int y, int z) {
            return world.getBlockAt(x, y, z).isLiquid();
        }

        @Override
        public boolean lava(int x, int y, int z) {
            return world.getBlockAt(x, y, z).getType() == Material.LAVA;
        }

        @Override
        public boolean hazard(int x, int y, int z) {
            Material type = world.getBlockAt(x, y, z).getType();
            return HAZARDS.contains(type) && type != Material.LAVA;
        }
    }
}
