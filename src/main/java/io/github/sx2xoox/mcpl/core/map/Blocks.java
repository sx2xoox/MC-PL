package io.github.sx2xoox.mcpl.core.map;

import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Slab;

/**
 * Ready-made {@link BlockSpec}s for the shapes maps use most, and helpers for two-block-tall blocks.
 *
 * <p>Facings follow the game: a stair's {@code facing} is the side its tall back is on (it is climbed from the
 * opposite side), a door's {@code facing} is the direction a player looks when walking out through it.
 */
public final class Blocks {

    private Blocks() {
    }

    public static BlockSpec of(Material material) {
        return BlockSpec.of(material);
    }

    /** An upright stair whose back faces {@code facing} (walk up it towards {@code facing}). */
    public static BlockSpec stairs(Material material, BlockFace facing) {
        return BlockSpec.of(material).facing(facing).half(Bisected.Half.BOTTOM);
    }

    /** An upside-down stair (for arches, eaves and overhangs). */
    public static BlockSpec stairsTop(Material material, BlockFace facing) {
        return BlockSpec.of(material).facing(facing).half(Bisected.Half.TOP);
    }

    /** A slab in the lower half of the block. */
    public static BlockSpec slab(Material material) {
        return BlockSpec.of(material).slab(Slab.Type.BOTTOM);
    }

    /** A slab in the upper half of the block. */
    public static BlockSpec slabTop(Material material) {
        return BlockSpec.of(material).slab(Slab.Type.TOP);
    }

    /** A log, pillar or chain lying along {@code axis}. */
    public static BlockSpec log(Material material, Axis axis) {
        return BlockSpec.of(material).axis(axis);
    }

    /** Leaves (always persistent: they never decay). */
    public static BlockSpec leaves(Material material) {
        return BlockSpec.of(material);
    }

    /** A lantern ({@code soul} for the blue one) standing on a block or hanging from one. */
    public static BlockSpec lantern(boolean soul, boolean hanging) {
        return BlockSpec.of(soul ? Material.SOUL_LANTERN : Material.LANTERN).hanging(hanging);
    }

    /** An unlit (smoking ruin) or lit campfire. */
    public static BlockSpec campfire(boolean lit) {
        return BlockSpec.of(Material.CAMPFIRE).lit(lit);
    }

    /** A trapdoor on the {@code top} or bottom half of its block, hinged on {@code facing}. */
    public static BlockSpec trapdoor(Material material, BlockFace facing, boolean top, boolean open) {
        return BlockSpec.of(material).facing(facing).half(top ? Bisected.Half.TOP : Bisected.Half.BOTTOM).open(open);
    }

    /** A wall torch fixed to the block behind it, facing {@code facing}. */
    public static BlockSpec wallTorch(boolean soul, BlockFace facing) {
        return BlockSpec.of(soul ? Material.SOUL_WALL_TORCH : Material.WALL_TORCH).facing(facing);
    }

    /** Places a two-block door at {@code x,y,z} (its lower half) and the block above. */
    public static void door(MapCanvas canvas, int x, int y, int z, Material door, BlockFace facing, boolean open) {
        canvas.set(x, y, z, BlockSpec.of(door).facing(facing).half(Bisected.Half.BOTTOM).open(open));
        canvas.set(x, y + 1, z, BlockSpec.of(door).facing(facing).half(Bisected.Half.TOP).open(open));
    }

    /** Places a two-block-tall plant (tall grass, large fern, sunflower, lilac, rose bush, peony) at {@code x,y,z}. */
    public static void tallPlant(MapCanvas canvas, int x, int y, int z, Material plant) {
        canvas.set(x, y, z, BlockSpec.of(plant).half(Bisected.Half.BOTTOM));
        canvas.set(x, y + 1, z, BlockSpec.of(plant).half(Bisected.Half.TOP));
    }
}
