package io.github.sx2xoox.mcpl.game.superpower.support.world;

import java.util.function.Predicate;

import com.google.common.base.Preconditions;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * Block and entity ray tracing with the semantics of Paper's {@code CraftWorld} (see {@code World#rayTraceBlocks},
 * {@code World#rayTraceEntities} and {@code World#rayTrace}), on top of the {@link FlatBlock} shape model:
 *
 * <ul>
 *     <li>a zero or non-finite direction is an {@link IllegalArgumentException}, a negative distance returns null;</li>
 *     <li>blocks are visited along the ray (voxel traversal) and the first one whose shape the ray enters wins;
 *     a ray that starts inside a solid block hits it immediately (like vanilla {@code VoxelShape#clip});</li>
 *     <li>{@code ignorePassableBlocks} = collision shapes only, otherwise outlines (plants) count too; fluids follow
 *     the {@link FluidCollisionMode};</li>
 *     <li>entities are hit through their bounding boxes expanded by {@code raySize}; the combined trace returns the
 *     closer of the block and entity hits.</li>
 * </ul>
 */
public final class RayTracing {

    private RayTracing() {
    }

    /** Validates the arguments like the server does; returns false if the trace returns null (negative distance). */
    static boolean validate(Vector start, Vector direction, double maxDistance) {
        start.checkFinite();
        direction.checkFinite();
        Preconditions.checkArgument(direction.lengthSquared() > 0,
                "Direction's magnitude (%s) need to be greater than 0", direction.lengthSquared());
        return maxDistance >= 0.0;
    }

    public static @Nullable RayTraceResult blocks(World world, Vector start, Vector direction, double maxDistance,
                                                  FluidCollisionMode fluids, boolean ignorePassable,
                                                  @Nullable Predicate<? super Block> canCollide) {
        if (!validate(start, direction, maxDistance)) {
            return null;
        }
        Vector dir = direction.clone().normalize();
        int x = floor(start.getX());
        int y = floor(start.getY());
        int z = floor(start.getZ());
        int stepX = (int) Math.signum(dir.getX());
        int stepY = (int) Math.signum(dir.getY());
        int stepZ = (int) Math.signum(dir.getZ());
        double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dir.getX());
        double tDeltaY = stepY == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dir.getY());
        double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dir.getZ());
        double tMaxX = firstBoundary(start.getX(), x, stepX, tDeltaX);
        double tMaxY = firstBoundary(start.getY(), y, stepY, tDeltaY);
        double tMaxZ = firstBoundary(start.getZ(), z, stepZ, tDeltaZ);
        double t = 0;
        // A few voxels beyond the distance are harmless: the box test below enforces maxDistance exactly.
        while (t <= maxDistance + 1.0E-9) {
            if (y >= world.getMinHeight() && y < world.getMaxHeight()) {
                Block block = world.getBlockAt(x, y, z);
                if (isTarget(block, fluids, ignorePassable) && (canCollide == null || canCollide.test(block))) {
                    RayTraceResult hit = clipCube(block, start, dir, maxDistance);
                    if (hit != null) {
                        return hit;
                    }
                }
            }
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                x += stepX;
                t = tMaxX;
                tMaxX += tDeltaX;
            } else if (tMaxY <= tMaxZ) {
                y += stepY;
                t = tMaxY;
                tMaxY += tDeltaY;
            } else {
                z += stepZ;
                t = tMaxZ;
                tMaxZ += tDeltaZ;
            }
            if (Double.isInfinite(t)) {
                break;
            }
        }
        return null;
    }

    private static double firstBoundary(double start, int cell, int step, double tDelta) {
        if (step == 0) {
            return Double.POSITIVE_INFINITY;
        }
        double boundary = step > 0 ? cell + 1 - start : start - cell;
        return boundary * tDelta;
    }

    private static @Nullable RayTraceResult clipCube(Block block, Vector start, Vector dir, double maxDistance) {
        BoundingBox cube = new BoundingBox(block.getX(), block.getY(), block.getZ(), block.getX() + 1,
                block.getY() + 1, block.getZ() + 1);
        if (cube.contains(start)) {
            // Vanilla VoxelShape#clip: a ray starting inside the shape hits right away.
            Vector at = start.clone().add(dir.clone().multiply(0.001 * maxDistance));
            return new RayTraceResult(at, block, approximateFace(dir).getOppositeFace());
        }
        return clip(cube, start, dir, maxDistance, block);
    }

    /**
     * Slab intersection of a ray with a box. Unlike {@link BoundingBox#rayTrace}, a ray running exactly along a face
     * plane (a zero direction component with the start on the plane) gives a finite result instead of NaN.
     */
    private static @Nullable RayTraceResult clip(BoundingBox box, Vector start, Vector dir, double maxDistance,
                                                 Block block) {
        double near = Double.NEGATIVE_INFINITY;
        double far = Double.POSITIVE_INFINITY;
        BlockFace face = null;
        double[] s = {start.getX(), start.getY(), start.getZ()};
        double[] d = {dir.getX(), dir.getY(), dir.getZ()};
        double[] lo = {box.getMinX(), box.getMinY(), box.getMinZ()};
        double[] hi = {box.getMaxX(), box.getMaxY(), box.getMaxZ()};
        BlockFace[] lowFaces = {BlockFace.WEST, BlockFace.DOWN, BlockFace.NORTH};
        BlockFace[] highFaces = {BlockFace.EAST, BlockFace.UP, BlockFace.SOUTH};
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(d[axis]) < 1.0E-12) {
                if (s[axis] < lo[axis] || s[axis] > hi[axis]) {
                    return null;
                }
                continue;
            }
            double t1 = (lo[axis] - s[axis]) / d[axis];
            double t2 = (hi[axis] - s[axis]) / d[axis];
            BlockFace enter = d[axis] > 0 ? lowFaces[axis] : highFaces[axis];
            if (t1 > t2) {
                double swap = t1;
                t1 = t2;
                t2 = swap;
            }
            if (t1 > near) {
                near = t1;
                face = enter;
            }
            far = Math.min(far, t2);
            if (near > far || far < 0) {
                return null;
            }
        }
        if (near < 0 || near > maxDistance || face == null) {
            return null;
        }
        return new RayTraceResult(start.clone().add(dir.clone().multiply(near)), block, face);
    }

    private static BlockFace approximateFace(Vector dir) {
        double ax = Math.abs(dir.getX());
        double ay = Math.abs(dir.getY());
        double az = Math.abs(dir.getZ());
        if (ax >= ay && ax >= az) {
            return dir.getX() >= 0 ? BlockFace.EAST : BlockFace.WEST;
        }
        if (ay >= az) {
            return dir.getY() >= 0 ? BlockFace.UP : BlockFace.DOWN;
        }
        return dir.getZ() >= 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    /** Whether the ray can hit {@code block} at all under the given modes. */
    static boolean isTarget(Block block, FluidCollisionMode fluids, boolean ignorePassable) {
        Material type = block.getType();
        if (FlatBlock.collides(type)) {
            return true;
        }
        if (type == Material.WATER || type == Material.LAVA) {
            return switch (fluids) {
                case ALWAYS -> true;
                case SOURCE_ONLY -> isSource(block.getBlockData());
                case NEVER -> false;
            };
        }
        return !ignorePassable && FlatBlock.hasOutline(type);
    }

    private static boolean isSource(BlockData data) {
        return !(data instanceof Levelled levelled) || levelled.getLevel() == 0;
    }

    /** Entity ray trace like {@code CraftWorld#rayTraceEntities}: nearest expanded bounding box along the ray. */
    public static @Nullable RayTraceResult entities(World world, Vector start, Vector direction, double maxDistance,
                                                    double raySize, @Nullable Predicate<? super Entity> filter) {
        if (!validate(start, direction, maxDistance)) {
            return null;
        }
        Entity nearest = null;
        RayTraceResult nearestHit = null;
        double nearestDistance = Double.MAX_VALUE;
        for (Entity entity : world.getEntities()) {
            if (!entity.isValid() || (filter != null && !filter.test(entity))) {
                continue;
            }
            BoundingBox box = entity.getBoundingBox().expand(raySize);
            RayTraceResult hit = box.rayTrace(start, direction, maxDistance);
            if (hit == null) {
                continue;
            }
            double distance = start.distanceSquared(hit.getHitPosition());
            if (distance < nearestDistance) {
                nearest = entity;
                nearestHit = hit;
                nearestDistance = distance;
            }
        }
        return nearest == null ? null
                : new RayTraceResult(nearestHit.getHitPosition(), nearest, nearestHit.getHitBlockFace());
    }

    /** Combined trace like {@code CraftWorld#rayTrace}: blocks first, then entities up to the block hit. */
    public static @Nullable RayTraceResult combined(World world, Vector start, Vector direction, double maxDistance,
                                                    FluidCollisionMode fluids, boolean ignorePassable, double raySize,
                                                    @Nullable Predicate<? super Entity> filter,
                                                    @Nullable Predicate<? super Block> canCollide) {
        RayTraceResult blockHit = blocks(world, start, direction, maxDistance, fluids, ignorePassable, canCollide);
        double blockDistance = maxDistance;
        if (blockHit != null) {
            blockDistance = start.distance(blockHit.getHitPosition());
        }
        RayTraceResult entityHit = entities(world, start, direction, blockDistance, raySize, filter);
        if (blockHit == null) {
            return entityHit;
        }
        if (entityHit == null) {
            return blockHit;
        }
        return start.distanceSquared(entityHit.getHitPosition()) < blockDistance * blockDistance ? entityHit : blockHit;
    }

    /**
     * Vanilla {@code LivingEntity#hasLineOfSight(Entity)}: eye to eye, collision shapes only, no fluids, at most 128
     * blocks, same world.
     */
    public static boolean hasLineOfSight(LivingEntity viewer, Entity target) {
        if (!viewer.getWorld().equals(target.getWorld())) {
            return false;
        }
        Vector from = viewer.getEyeLocation().toVector();
        double targetEye = target instanceof LivingEntity living ? living.getEyeLocation().getY()
                : target.getLocation().getY() + target.getHeight() * 0.85;
        Vector to = target.getLocation().toVector().setY(targetEye);
        return clearLine(viewer.getWorld(), from, to, 128.0);
    }

    /** True if no collision shape lies between the two points (and they are at most {@code maxDistance} apart). */
    public static boolean clearLine(World world, Vector from, Vector to, double maxDistance) {
        Vector delta = to.clone().subtract(from);
        double distance = delta.length();
        if (distance > maxDistance) {
            return false;
        }
        if (distance < 1.0E-7) {
            return true;
        }
        return blocks(world, from, delta, distance, FluidCollisionMode.NEVER, true, null) == null;
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }
}
