package io.github.sx2xoox.mcpl.game.superpower.support.world;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.util.BoundingBox;
import org.jspecify.annotations.Nullable;
import org.mockbukkit.mockbukkit.block.BlockMock;

/**
 * A {@link BlockMock} that answers the shape questions MockBukkit leaves unimplemented ({@link #isPassable},
 * {@link #getBoundingBox}) and hands out copies of its location like the server does.
 *
 * <p>Shape model (good enough for the cube worlds of {@link FlatWorld}): every <em>solid</em> material is a full
 * collidable cube; air has no shape; every other material (plants, fluids, ...) has no collision but a full-cube
 * outline. Slabs, stairs, fences and other partial shapes are not modelled.
 */
public final class FlatBlock extends BlockMock {

    public FlatBlock(Material material, Location location) {
        super(material, location);
    }

    /** True if a block of {@code type} is a full collidable cube in the test world. */
    public static boolean collides(Material type) {
        return type.isSolid();
    }

    /** True if a block of {@code type} has a selection outline (anything but air). */
    public static boolean hasOutline(Material type) {
        return !type.isAir();
    }

    /**
     * A copy, like the server's {@code CraftBlock#getLocation}. MockBukkit returns its internal location, so code
     * such as {@code block.getLocation().add(0.5, 1, 0.5)} (fine on a server) would move the block itself.
     */
    @Override
    public Location getLocation() {
        return super.getLocation().clone();
    }

    @Override
    public @Nullable Location getLocation(@Nullable Location target) {
        if (target == null) {
            return null;
        }
        Location own = super.getLocation();
        target.setWorld(own.getWorld());
        target.set(own.getX(), own.getY(), own.getZ());
        target.setYaw(0);
        target.setPitch(0);
        return target;
    }

    /** Like vanilla: passable means "no collision shape" (air, plants, fluids...). */
    @Override
    public boolean isPassable() {
        return !collides(getType());
    }

    /** The outline box like the server returns it: the full cube, or an empty box at the corner for air. */
    @Override
    public BoundingBox getBoundingBox() {
        if (!hasOutline(getType())) {
            return new BoundingBox(getX(), getY(), getZ(), getX(), getY(), getZ());
        }
        return new BoundingBox(getX(), getY(), getZ(), getX() + 1, getY() + 1, getZ() + 1);
    }
}
