package io.github.sx2xoox.mcpl.core.arena;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import io.github.sx2xoox.mcpl.core.map.MapBlocks;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.TileState;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.FaceAttachable;
import org.bukkit.block.data.Hangable;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Cocoa;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.TrapDoor;
import org.jspecify.annotations.Nullable;

/**
 * What terrain damage ({@link TerrainDamage}) may break: natural ground versus structures, blocks that must never
 * break, and the blocks attached to a block (which go and come back with it).
 *
 * <p>The rules go by material, tags and block data, so they behave the same on a server and in tests.
 */
final class TerrainRules {

    /** The six neighbours of a block. */
    static final List<BlockFace> FACES = List.of(BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.EAST,
            BlockFace.SOUTH, BlockFace.WEST);

    /** Natural ground besides the tagged dirt, stone, sand and terracotta. */
    private static final Set<Material> GROUND = EnumSet.of(Material.GRAVEL, Material.SUSPICIOUS_GRAVEL,
            Material.CLAY, Material.MUD, Material.DIRT_PATH, Material.FARMLAND, Material.SANDSTONE,
            Material.RED_SANDSTONE, Material.CALCITE, Material.DRIPSTONE_BLOCK, Material.SMOOTH_BASALT,
            Material.SOUL_SAND, Material.SOUL_SOIL, Material.END_STONE, Material.ICE, Material.PACKED_ICE,
            Material.BLUE_ICE, Material.SNOW_BLOCK, Material.MOSS_BLOCK, Material.PALE_MOSS_BLOCK,
            Material.MYCELIUM, Material.PODZOL, Material.ROOTED_DIRT, Material.COARSE_DIRT, Material.GRASS_BLOCK,
            Material.DIRT, Material.STONE, Material.MAGMA_BLOCK);

    /** Blocks that never break besides the unbreakable and explosion-proof ones. */
    private static final Set<Material> TECHNICAL = EnumSet.of(Material.BARRIER, Material.LIGHT,
            Material.STRUCTURE_VOID, Material.BEDROCK, Material.NETHER_PORTAL, Material.END_PORTAL,
            Material.END_GATEWAY, Material.END_PORTAL_FRAME, Material.PISTON, Material.STICKY_PISTON,
            Material.PISTON_HEAD, Material.MOVING_PISTON, Material.REINFORCED_DEEPSLATE, Material.SPAWNER,
            Material.TRIAL_SPAWNER, Material.VAULT, Material.BUBBLE_COLUMN);

    /** Water plants: always full of water. */
    private static final Set<Material> UNDERWATER = EnumSet.of(Material.KELP, Material.KELP_PLANT,
            Material.SEAGRASS, Material.TALL_SEAGRASS);

    /** Non-solid blocks that need nothing to hold them (they are not attached to the block below). */
    private static final Set<Material> FREE_STANDING = EnumSet.of(Material.LIGHT, Material.STRUCTURE_VOID,
            Material.COBWEB);

    /** Solid blocks that stand on the block below and fall apart without it. */
    private static final Set<Material> STANDING = EnumSet.of(Material.CACTUS, Material.BAMBOO, Material.SNOW,
            Material.CHORUS_PLANT, Material.CHORUS_FLOWER, Material.BIG_DRIPLEAF, Material.BIG_DRIPLEAF_STEM,
            Material.SCAFFOLDING, Material.POINTED_DRIPSTONE, Material.SUGAR_CANE);

    /** Plants hanging from the block above. */
    private static final Set<Material> HANGING = EnumSet.of(Material.WEEPING_VINES, Material.WEEPING_VINES_PLANT,
            Material.CAVE_VINES, Material.CAVE_VINES_PLANT, Material.HANGING_ROOTS, Material.SPORE_BLOSSOM,
            Material.PALE_HANGING_MOSS);

    /** Thin growths clinging to the faces of the blocks around them. */
    private static final Set<Material> CLINGING = EnumSet.of(Material.VINE, Material.GLOW_LICHEN,
            Material.SCULK_VEIN, Material.RESIN_CLUMP);

    /** Blocks held by the block behind them (opposite their facing). */
    private static final Set<Material> MOUNTED = EnumSet.of(Material.LADDER, Material.TRIPWIRE_HOOK,
            Material.SMALL_AMETHYST_BUD, Material.MEDIUM_AMETHYST_BUD, Material.LARGE_AMETHYST_BUD,
            Material.AMETHYST_CLUSTER);

    private TerrainRules() {
    }

    /**
     * True for natural ground: dirt, grass, moss, mud, sand, gravel, clay, snow, natural stone, terracotta and the
     * like. Everything else (bricks, planks, logs, cobblestone, ...) belongs to a structure.
     */
    static boolean isGround(Material material) {
        return GROUND.contains(material) || material.name().endsWith("_ORE")
                || tagged(Tag.DIRT, material) || tagged(Tag.BASE_STONE_OVERWORLD, material)
                || tagged(Tag.BASE_STONE_NETHER, material) || tagged(Tag.TERRACOTTA, material)
                || tagged(Tag.SAND, material) || tagged(Tag.NYLIUM, material);
    }

    private static boolean tagged(@Nullable Tag<Material> tag, Material material) {
        return tag != null && tag.isTagged(material);
    }

    /** Never broken: unbreakable, explosion-proof (obsidian and the like) and technical blocks. */
    static boolean isUnbreakable(Material material) {
        return TECHNICAL.contains(material) || material.getHardness() < 0 || material.getBlastResistance() >= 1200;
    }

    /** Water, lava, waterlogged blocks and water plants: breaking next to them would let a fluid spill. */
    static boolean isWet(BlockData data) {
        Material material = data.getMaterial();
        return material == Material.WATER || material == Material.LAVA || material == Material.BUBBLE_COLUMN
                || UNDERWATER.contains(material) || data instanceof Waterlogged waterlogged
                && waterlogged.isWaterlogged();
    }

    /** Blocks that fall (or snap off, like pointed dripstone) without the block holding them. */
    static boolean hasGravity(Material material) {
        return MapBlocks.hasGravity(material) || material.hasGravity();
    }

    /** True if the block has a block entity (chests, signs, banners, skulls, spawners, ...). */
    static boolean hasBlockEntity(Block block) {
        return block.getState(false) instanceof TileState;
    }

    /** The two halves of a door, a tall flower or tall grass (not stairs and trapdoors, which are halves of nothing). */
    static boolean isDoubleBlock(BlockData data) {
        return data instanceof Bisected && !(data instanceof Stairs) && !(data instanceof TrapDoor);
    }

    /**
     * True if {@code attached}, the neighbour on side {@code face} of a block of {@code support}, is held by that
     * block: it would pop off (or come apart) without it. Torches, ladders, buttons and vines on its sides, plants,
     * carpets, snow and doors on top, lanterns and roots hanging below, the other half of a double block.
     */
    static boolean isAttached(BlockData attached, BlockFace face, BlockData support) {
        Material material = attached.getMaterial();
        if (material.isAir() || FREE_STANDING.contains(material)) {
            return false;
        }
        if (isDoubleBlock(attached)) {
            Bisected.Half half = ((Bisected) attached).getHalf();
            if (support.getMaterial() == material && support instanceof Bisected other && other.getHalf() != half
                    && (face == BlockFace.UP && half == Bisected.Half.TOP
                    || face == BlockFace.DOWN && half == Bisected.Half.BOTTOM)) {
                return true; // its other half
            }
            return face == BlockFace.UP && half == Bisected.Half.BOTTOM;
        }
        if (attached instanceof FaceAttachable faceAttachable) {
            return switch (faceAttachable.getAttachedFace()) {
                case FLOOR -> face == BlockFace.UP;
                case CEILING -> face == BlockFace.DOWN;
                case WALL -> attached instanceof Directional directional && directional.getFacing() == face;
            };
        }
        if (attached instanceof Hangable hangable) {
            return hangable.isHanging() ? face == BlockFace.DOWN : face == BlockFace.UP;
        }
        if (attached instanceof Cocoa cocoa) {
            return cocoa.getFacing() == face.getOppositeFace();
        }
        if (attached instanceof Directional directional && isMounted(material)) {
            return directional.getFacing() == face;
        }
        if (CLINGING.contains(material)) {
            return attached instanceof MultipleFacing facing && facing.hasFace(face.getOppositeFace());
        }
        if (HANGING.contains(material) || material.name().endsWith("_HANGING_SIGN")
                && !material.name().endsWith("_WALL_HANGING_SIGN")) {
            return face == BlockFace.DOWN;
        }
        if (face != BlockFace.UP) {
            return false;
        }
        // On top: plants, carpets, rails, plates, torches, pots, ... and the solid things that stand on the ground.
        return !material.isSolid() || STANDING.contains(material) || material.name().endsWith("CAKE");
    }

    /** Wall torches, ladders, wall signs, banners and heads, coral fans, hooks, amethyst buds. */
    private static boolean isMounted(Material material) {
        String name = material.name();
        return MOUNTED.contains(material) || name.endsWith("WALL_TORCH") || name.endsWith("_WALL_FAN")
                || name.endsWith("WALL_SIGN") || name.endsWith("WALL_BANNER") || name.endsWith("WALL_HEAD")
                || name.endsWith("WALL_SKULL");
    }
}
