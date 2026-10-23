package io.github.sx2xoox.mcpl.core.map;

import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Hangable;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.Wall;

/**
 * Block shape knowledge the map code needs without asking the server: which blocks are full cubes, which ones connect
 * like fences, walls or panes, and how a {@link BlockSpec} becomes real {@link BlockData}.
 *
 * <p>The shape rules go by material name, so they behave the same in tests and on a server.
 */
public final class MapBlocks {

    /** Connection families: blocks of one family connect to each other and to full cubes. */
    public enum Family { NONE, FENCE, WALL, PANE }

    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};
    private static final int[] SIDE_BITS = {BlockSpec.NORTH, BlockSpec.EAST, BlockSpec.SOUTH, BlockSpec.WEST};

    private MapBlocks() {
    }

    /** The connection family of {@code material}. */
    public static Family family(Material material) {
        String name = material.name();
        if (name.endsWith("_FENCE")) {
            return Family.FENCE;
        }
        if (name.endsWith("_PANE") || material == Material.IRON_BARS || name.endsWith("_BARS")) {
            return Family.PANE;
        }
        if (name.endsWith("_WALL") && !name.endsWith("_SIGN") && !name.contains("_WALL_")) {
            return Family.WALL;
        }
        return Family.NONE;
    }

    /**
     * True for blocks with a full, solid cube shape: fences and walls connect to them, players stand on them and cannot
     * walk through them. Slabs (except double), stairs, plants, panes, doors and the like are not.
     */
    public static boolean isFullCube(BlockSpec spec) {
        Material material = spec.material();
        if (spec.isAir() || isFluid(material)) {
            return false;
        }
        if (material.name().endsWith("_SLAB")) {
            return spec.slab() == Slab.Type.DOUBLE;
        }
        return isFullCubeMaterial(material);
    }

    /** {@link #isFullCube} for the material's default state (a slab counts as a half block). */
    public static boolean isFullCubeMaterial(Material material) {
        String name = material.name();
        if (material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR
                || isFluid(material) || family(material) != Family.NONE) {
            return false;
        }
        if (isFullByName(name)) {
            return true;
        }
        for (String part : NON_FULL_PARTS) {
            if (name.contains(part)) {
                return false;
            }
        }
        for (String suffix : NON_FULL_SUFFIXES) {
            if (name.endsWith(suffix)) {
                return false;
            }
        }
        return !NON_FULL.contains(name);
    }

    /** True if a player can stand on top of the block (full cubes, slabs, stairs, fences, walls, ...). */
    public static boolean isStandable(BlockSpec spec) {
        Material material = spec.material();
        if (spec.isAir() || isFluid(material) || isPassable(spec)) {
            return false;
        }
        return true;
    }

    /** True if a player walks through the block (air, plants, torches, carpets on the floor are not passable). */
    public static boolean isPassable(BlockSpec spec) {
        Material material = spec.material();
        if (spec.isAir() || isFluid(material)) {
            return true;
        }
        String name = material.name();
        if (isFullByName(name) || name.equals("MANGROVE_ROOTS")) {
            return false;
        }
        if (spec.open() == Boolean.TRUE && (name.endsWith("_DOOR") || name.endsWith("_FENCE_GATE"))) {
            return true;
        }
        for (String part : PASSABLE_PARTS) {
            if (name.contains(part)) {
                return true;
            }
        }
        for (String suffix : PASSABLE_SUFFIXES) {
            if (name.endsWith(suffix)) {
                return true;
            }
        }
        return PASSABLE.contains(name);
    }

    public static boolean isFluid(Material material) {
        return material == Material.WATER || material == Material.LAVA;
    }

    /** Blocks that fall when nothing is below them. */
    public static boolean hasGravity(Material material) {
        String name = material.name();
        return material == Material.SAND || material == Material.RED_SAND || material == Material.GRAVEL
                || material == Material.SUSPICIOUS_SAND || material == Material.SUSPICIOUS_GRAVEL
                || name.endsWith("_CONCRETE_POWDER") || name.endsWith("ANVIL") || material == Material.DRAGON_EGG
                || material == Material.POINTED_DRIPSTONE || material == Material.SCAFFOLDING;
    }

    /** The bit of {@link BlockSpec#connections()} for a horizontal face (0 for others). */
    public static int bit(BlockFace face) {
        for (int i = 0; i < SIDES.length; i++) {
            if (SIDES[i] == face) {
                return SIDE_BITS[i];
            }
        }
        return 0;
    }

    /**
     * The real block data of {@code spec}. Every property is applied only where the block supports it (and only to
     * values the block allows), so a mistake in a map can never make the server reject the block.
     */
    public static BlockData toData(BlockSpec spec) {
        BlockData data = spec.material().createBlockData();
        if (spec.facing() != null && data instanceof Directional directional
                && directional.getFaces().contains(spec.facing())) {
            directional.setFacing(spec.facing());
        }
        if (spec.half() != null && data instanceof Bisected bisected) {
            bisected.setHalf(spec.half());
        }
        if (spec.slab() != null && data instanceof Slab slab) {
            slab.setType(spec.slab());
        }
        if (spec.axis() != null && data instanceof Orientable orientable && orientable.getAxes().contains(spec.axis())) {
            orientable.setAxis(spec.axis());
        }
        if (spec.shape() != null && data instanceof Stairs stairs) {
            stairs.setShape(spec.shape());
        }
        if (spec.hanging() != null && data instanceof Hangable hangable) {
            hangable.setHanging(spec.hanging());
        }
        if (spec.open() != null && data instanceof Openable openable) {
            openable.setOpen(spec.open());
        }
        if (spec.lit() != null && data instanceof Lightable lightable) {
            lightable.setLit(spec.lit());
        }
        if (spec.waterlogged() != null && data instanceof Waterlogged waterlogged) {
            waterlogged.setWaterlogged(spec.waterlogged());
        }
        if (data instanceof Leaves leaves) {
            leaves.setPersistent(true);
        }
        if (data instanceof Wall wall) {
            for (int i = 0; i < SIDES.length; i++) {
                wall.setHeight(SIDES[i], (spec.connections() & SIDE_BITS[i]) != 0 ? Wall.Height.LOW : Wall.Height.NONE);
            }
            wall.setUp(spec.up());
        } else if (data instanceof MultipleFacing facing && family(spec.material()) != Family.NONE) {
            for (int i = 0; i < SIDES.length; i++) {
                if (facing.getAllowedFaces().contains(SIDES[i])) {
                    facing.setFace(SIDES[i], (spec.connections() & SIDE_BITS[i]) != 0);
                }
            }
        }
        return data;
    }

    // ------------------------------------------------------------------ name tables

    /** Full cubes whose names contain a word of the non-full or passable lists. */
    private static boolean isFullByName(String name) {
        return FULL.contains(name) || name.endsWith("_CORAL_BLOCK") || name.endsWith("_MUSHROOM_BLOCK")
                || name.startsWith("BAMBOO_") && !name.endsWith("_SLAB") && !name.endsWith("_STAIRS")
                && !name.contains("_DOOR") && !name.contains("_FENCE") && !name.endsWith("_SIGN")
                && !name.endsWith("_BUTTON") && !name.endsWith("_PLATE") && !name.contains("RAFT")
                && !name.equals("BAMBOO_SAPLING");
    }

    private static final java.util.Set<String> FULL = java.util.Set.of("SNOW_BLOCK", "MUD_BRICKS", "PACKED_MUD",
            "MUDDY_MANGROVE_ROOTS", "MUSHROOM_STEM", "SEA_LANTERN", "JACK_O_LANTERN", "STRIPPED_BAMBOO_BLOCK",
            "CHISELED_BOOKSHELF", "MOSS_BLOCK", "PALE_MOSS_BLOCK", "SCULK", "SCULK_CATALYST", "AMETHYST_BLOCK",
            "BUDDING_AMETHYST", "DRIPSTONE_BLOCK", "HONEYCOMB_BLOCK");

    private static final java.util.List<String> NON_FULL_PARTS = java.util.List.of("_SLAB", "_STAIRS", "_DOOR",
            "TRAPDOOR", "_FENCE", "_WALL", "_PANE", "_CARPET", "_BUTTON", "PRESSURE_PLATE", "_SIGN", "_BANNER", "TORCH",
            "LANTERN", "_CANDLE", "CANDLE", "_SAPLING", "_FLOWER", "_TULIP", "_BED", "_HEAD", "_SKULL", "_CORAL",
            "RAIL", "_GLASS_PANE", "CHAIN", "_LEAVES_CARPET", "_BARS", "POT", "CAMPFIRE", "LADDER", "VINE",
            "MUSHROOM", "_ROOTS", "FERN", "SHORT_GRASS", "TALL_GRASS", "DEAD_BUSH", "SEAGRASS", "KELP", "LILY",
            "DRIPLEAF", "AZALEA", "BERRY", "SPORE", "LICHEN", "SCULK_VEIN", "COBWEB", "SNOW", "AMETHYST_BUD",
            "AMETHYST_CLUSTER", "POINTED_DRIPSTONE", "END_ROD", "LIGHTNING_ROD", "LEVER", "TRIPWIRE", "REDSTONE_WIRE",
            "COMPARATOR", "REPEATER", "DAYLIGHT", "ANVIL", "BELL", "CAULDRON", "HOPPER", "BREWING", "ENCHANTING",
            "GRINDSTONE", "LECTERN", "STONECUTTER", "CHEST", "CAKE", "CONDUIT", "HONEY_BLOCK", "SOUL_SAND",
            "MUD", "FARMLAND", "DIRT_PATH", "SCAFFOLDING", "CACTUS", "BAMBOO", "SUGAR_CANE", "PITCHER", "TORCHFLOWER",
            "EYEBLOSSOM", "PETALS", "LEAF_LITTER", "BUSH", "FIREFLY", "HANGING_MOSS", "MOSS_CARPET", "PALE_MOSS_CARPET");

    private static final java.util.List<String> NON_FULL_SUFFIXES = java.util.List.of("_PLATE", "_ROD");

    private static final java.util.Set<String> NON_FULL = java.util.Set.of("DANDELION", "POPPY", "BLUE_ORCHID",
            "ALLIUM", "AZURE_BLUET", "OXEYE_DAISY", "CORNFLOWER", "LILY_OF_THE_VALLEY", "WITHER_ROSE", "SUNFLOWER",
            "LILAC", "ROSE_BUSH", "PEONY", "WHEAT", "CARROTS", "POTATOES", "BEETROOTS", "NETHER_WART", "FIRE",
            "SOUL_FIRE", "BARRIER", "LIGHT", "STRUCTURE_VOID", "BEACON", "GLASS", "TINTED_GLASS", "ICE",
            "HEAVY_CORE", "DECORATED_POT", "FLOWER_POT", "SEA_PICKLE", "TURTLE_EGG", "SNIFFER_EGG", "FROGSPAWN",
            "SMALL_DRIPLEAF", "BIG_DRIPLEAF", "SPORE_BLOSSOM", "GLOW_LICHEN", "HANGING_ROOTS", "CREAKING_HEART");

    private static final java.util.List<String> PASSABLE_PARTS = java.util.List.of("_SAPLING", "_TULIP", "TORCH",
            "_BUTTON", "PRESSURE_PLATE", "_SIGN", "_BANNER", "RAIL", "VINE", "MUSHROOM", "_ROOTS", "FERN",
            "SHORT_GRASS", "TALL_GRASS", "DEAD_BUSH", "SEAGRASS", "KELP", "LICHEN", "SCULK_VEIN", "COBWEB", "LEVER",
            "TRIPWIRE", "REDSTONE_WIRE", "_CORAL", "BERRY_BUSH", "SUGAR_CANE", "PETALS", "LEAF_LITTER", "_BUSH",
            "HANGING_MOSS", "TORCHFLOWER", "EYEBLOSSOM", "PITCHER_PLANT", "SPORE_BLOSSOM", "LADDER");

    private static final java.util.List<String> PASSABLE_SUFFIXES = java.util.List.of("_FLOWER");

    private static final java.util.Set<String> PASSABLE = java.util.Set.of("DANDELION", "POPPY", "BLUE_ORCHID",
            "ALLIUM", "AZURE_BLUET", "OXEYE_DAISY", "CORNFLOWER", "LILY_OF_THE_VALLEY", "WITHER_ROSE", "SUNFLOWER",
            "LILAC", "ROSE_BUSH", "PEONY", "WHEAT", "CARROTS", "POTATOES", "BEETROOTS", "NETHER_WART", "FIRE",
            "SOUL_FIRE", "LIGHT", "STRUCTURE_VOID", "SNOW", "SMALL_DRIPLEAF", "FROGSPAWN", "SWEET_BERRY_BUSH");
}
