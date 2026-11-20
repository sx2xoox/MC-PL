package io.github.sx2xoox.mcpl.core.map;

import java.util.Locale;
import java.util.Objects;

import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.jspecify.annotations.Nullable;

/**
 * One block of a map, described with typed properties instead of a block state string, so a wrong property can never
 * make a real server reject the map: {@link MapBlocks#toData} applies each property only where the block supports it.
 *
 * <p>Build specs with {@link #of(Material)} and the {@code with...} methods, or the presets in {@link Blocks}:
 * <pre>{@code
 * BlockSpec step = BlockSpec.of(Material.STONE_BRICK_STAIRS).facing(BlockFace.NORTH).half(Bisected.Half.TOP);
 * }</pre>
 * Fence, wall, pane and bar connections are not set by hand: {@link MapCanvas#finish()} computes them from the
 * neighbours. Leaves are always made persistent so they never decay.
 *
 * @param material    the block (must be a block material)
 * @param facing      {@code Directional} facing (stairs, logs' bark side is {@link #axis}, doors, trapdoors, ...)
 * @param half        {@code Bisected} half (stairs, trapdoors, doors, tall plants)
 * @param slab        slab type (bottom, top, double)
 * @param axis        {@code Orientable} axis (logs, pillars, chains, basalt, ...)
 * @param shape       stairs shape (corners)
 * @param hanging     lanterns hanging from the ceiling
 * @param open        doors, trapdoors and gates
 * @param lit         campfires, furnaces, redstone lamps, candles
 * @param waterlogged waterlogged blocks
 * @param connections north/east/south/west connections as a bitmask of {@link #NORTH}...{@link #WEST} (computed)
 * @param up          the post of a wall (computed)
 */
public record BlockSpec(
        Material material,
        @Nullable BlockFace facing,
        Bisected.@Nullable Half half,
        Slab.@Nullable Type slab,
        @Nullable Axis axis,
        Stairs.@Nullable Shape shape,
        @Nullable Boolean hanging,
        @Nullable Boolean open,
        @Nullable Boolean lit,
        @Nullable Boolean waterlogged,
        int connections,
        boolean up
) {

    public static final int NORTH = 1;
    public static final int EAST = 2;
    public static final int SOUTH = 4;
    public static final int WEST = 8;

    /** Explicit air: carves a block out (a canvas position that was never set is also air). */
    public static final BlockSpec AIR = of(Material.AIR);

    public BlockSpec {
        Objects.requireNonNull(material, "material");
        if (!material.isBlock()) {
            throw new IllegalArgumentException(material + " is not a block");
        }
        if (material.isLegacy()) {
            throw new IllegalArgumentException(material + " is a legacy material");
        }
    }

    public static BlockSpec of(Material material) {
        return new BlockSpec(material, null, null, null, null, null, null, null, null, null, 0, false);
    }

    public BlockSpec facing(BlockFace facing) {
        return new BlockSpec(material, facing, half, slab, axis, shape, hanging, open, lit, waterlogged, connections,
                up);
    }

    public BlockSpec half(Bisected.Half half) {
        return new BlockSpec(material, facing, half, slab, axis, shape, hanging, open, lit, waterlogged, connections,
                up);
    }

    public BlockSpec slab(Slab.Type slab) {
        return new BlockSpec(material, facing, half, slab, axis, shape, hanging, open, lit, waterlogged, connections,
                up);
    }

    public BlockSpec axis(Axis axis) {
        return new BlockSpec(material, facing, half, slab, axis, shape, hanging, open, lit, waterlogged, connections,
                up);
    }

    public BlockSpec shape(Stairs.Shape shape) {
        return new BlockSpec(material, facing, half, slab, axis, shape, hanging, open, lit, waterlogged, connections,
                up);
    }

    public BlockSpec hanging(boolean hanging) {
        return new BlockSpec(material, facing, half, slab, axis, shape, hanging, open, lit, waterlogged, connections,
                up);
    }

    public BlockSpec open(boolean open) {
        return new BlockSpec(material, facing, half, slab, axis, shape, hanging, open, lit, waterlogged, connections,
                up);
    }

    public BlockSpec lit(boolean lit) {
        return new BlockSpec(material, facing, half, slab, axis, shape, hanging, open, lit, waterlogged, connections,
                up);
    }

    public BlockSpec waterlogged(boolean waterlogged) {
        return new BlockSpec(material, facing, half, slab, axis, shape, hanging, open, lit, waterlogged, connections,
                up);
    }

    /** Used by {@link MapCanvas#finish()}; maps never set connections by hand. */
    BlockSpec connected(int connections, boolean up) {
        return new BlockSpec(material, facing, half, slab, axis, shape, hanging, open, lit, waterlogged, connections,
                up);
    }

    public boolean isAir() {
        return material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR;
    }

    /** Lowercase material name, e.g. {@code "stone_brick_stairs"}. */
    public String name() {
        return material.name().toLowerCase(Locale.ROOT);
    }
}
