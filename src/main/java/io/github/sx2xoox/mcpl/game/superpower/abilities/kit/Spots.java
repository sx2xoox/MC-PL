package io.github.sx2xoox.mcpl.game.superpower.abilities.kit;

import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityContext;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Lightable;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * Finding and checking teleport, swap and dash destinations.
 *
 * <p>{@link #isSafe} is the check every teleport destination of the owner must pass: the body fits without
 * touching blocks or hazards, the first thing below is harmless ground or water (never the void, lava or fire) and
 * the spot is inside the arena and its current border ({@link AbilityContext#isInsideArena}). Velocity-based dashes,
 * which have no exact destination, shorten themselves with {@link #arenaReach}.
 */
public final class Spots {

    /** Angles (degrees) around a target's back tried by {@link #around}, best first. */
    private static final double[] AROUND_ANGLES = {0, 40, -40, 80, -80, 120, -120, 180};
    /** Gaps (blocks) between the two hitboxes tried by {@link #around}. */
    private static final double[] AROUND_DISTANCES = {1.2, 2.0};
    /** Height offsets tried by {@link #around}, for slopes and steps. */
    private static final double[] AROUND_HEIGHTS = {0, 0.5, 1.0, -0.5, -1.0};
    /** {@link #around} destinations hovering at most this high above ground are snapped onto it. */
    private static final double AROUND_GROUND_SNAP = 1.1;
    /** Sampling distance of {@link #arenaReach}. */
    private static final double ARENA_STEP = 0.5;

    private Spots() {
    }

    /**
     * The full safety check for a teleport destination: {@link #fits} (no blocks, no hazards),
     * {@link #hasGroundBelow} (not above the void or a hazard) and inside the arena and its current border.
     */
    public static boolean isSafe(AbilityContext ctx, Location feet, Body body) {
        World world = feet.getWorld();
        return fits(world, feet, body.width(), body.height()) && hasGroundBelow(world, feet)
                && ctx.isInsideArena(feet);
    }

    /**
     * True if a standing body of the given size fits at {@code feet}: {@link #isFree} and no harmful block in any
     * block column the body overlaps (lava and fire burn whatever part of the hitbox touches them).
     */
    public static boolean fits(World world, Location feet, double width, double height) {
        if (!isFree(world, feet, width, height)) {
            return false;
        }
        double half = Math.max(0, width / 2 - 0.01);
        int minX = (int) Math.floor(feet.getX() - half);
        int maxX = (int) Math.floor(feet.getX() + half);
        int minZ = (int) Math.floor(feet.getZ() - half);
        int maxZ = (int) Math.floor(feet.getZ() + half);
        int bottom = (int) Math.floor(feet.getY() + 0.01);
        int top = (int) Math.floor(feet.getY() + height);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = bottom; y <= top; y++) {
                    if (isHazard(world.getBlockAt(x, y, z))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** True if a body of the given size at {@code feet} touches no block and stays within the world's height. */
    public static boolean isFree(World world, Location feet, double width, double height) {
        if (feet.getY() < world.getMinHeight() + 1 || feet.getY() + height > world.getMaxHeight()) {
            return false;
        }
        double half = width / 2 - 0.01;
        BoundingBox box = new BoundingBox(feet.getX() - half, feet.getY() + 0.01, feet.getZ() - half,
                feet.getX() + half, feet.getY() + height, feet.getZ() + half);
        return !world.hasCollisionsIn(box);
    }

    /**
     * Blocks nobody should be teleported into, next to or onto: lava, fire, a lit campfire, magma, powder snow,
     * cobwebs, cactus (its collision box is inset, so a body beside it still touches it), berry bushes, wither roses
     * and portals (which would carry the player out of the arena).
     */
    public static boolean isHazard(Block block) {
        return switch (block.getType()) {
            case LAVA, FIRE, SOUL_FIRE, MAGMA_BLOCK, POWDER_SNOW, COBWEB, CACTUS, SWEET_BERRY_BUSH, WITHER_ROSE,
                 NETHER_PORTAL, END_PORTAL, END_GATEWAY -> true;
            case CAMPFIRE, SOUL_CAMPFIRE -> block.getBlockData() instanceof Lightable campfire && campfire.isLit();
            default -> false;
        };
    }

    /**
     * False above the void and above hazards: the first block or fluid straight below {@code feet} must exist, must
     * not be harmful ({@link #isHazard}: lava, magma...) and must not carry a harmful block such as fire on top.
     * Water counts as safe ground.
     */
    public static boolean hasGroundBelow(World world, Location feet) {
        double depth = feet.getY() - world.getMinHeight();
        if (depth <= 0) {
            return false;
        }
        RayTraceResult ground = world.rayTraceBlocks(feet, new Vector(0, -1, 0), depth, FluidCollisionMode.ALWAYS,
                true);
        if (ground == null) {
            return false;
        }
        Block block = ground.getHitBlock();
        // Fire, cobwebs and berry bushes have no collision, so the ray passes them and hits the block they sit on.
        return block == null || (!isHazard(block) && !isHazard(block.getRelative(BlockFace.UP)));
    }

    /** Lowers {@code feet} (in place) onto the ground if the ground is at most {@code maxDrop} blocks below. */
    public static void snapToGround(World world, Location feet, double maxDrop) {
        RayTraceResult ground = world.rayTraceBlocks(feet, new Vector(0, -1, 0), maxDrop, FluidCollisionMode.NEVER,
                true);
        if (ground != null) {
            feet.setY(ground.getHitPosition().getY());
        }
    }

    /** True if no solid block lies on the straight line between {@code from} and {@code to} (same world). */
    public static boolean clearPath(Location from, Location to) {
        Vector delta = to.toVector().subtract(from.toVector());
        double length = delta.length();
        if (length < 1.0E-3) {
            return true;
        }
        return from.getWorld().rayTraceBlocks(from, delta.multiply(1 / length), length, FluidCollisionMode.NEVER,
                true) == null;
    }

    /**
     * A safe spot for {@code mover} right next to {@code target}, preferring the target's back turned by
     * {@code angleOffset} degrees, facing the target. Every candidate must pass {@link #isSafe} and have a clear
     * line to the target's eyes, so the mover never ends up behind a wall. Null if there is no room.
     */
    public static @Nullable Location around(AbilityContext ctx, LivingEntity mover, LivingEntity target,
                                            double angleOffset) {
        World world = target.getWorld();
        Location base = target.getLocation();
        Vector back = base.getDirection().setY(0);
        if (back.lengthSquared() < 1.0E-4) {
            back = new Vector(0, 0, 1);
        }
        back.normalize().multiply(-1).rotateAroundY(Math.toRadians(angleOffset));

        Body body = Body.standing(mover);
        Location targetEye = target.getEyeLocation();
        Vector focus = target.getLocation().add(0, target.getHeight() * 0.6, 0).toVector();
        double clearance = target.getWidth() / 2 + body.width() / 2;
        for (double angle : AROUND_ANGLES) {
            Vector side = back.clone().rotateAroundY(Math.toRadians(angle));
            for (double distance : AROUND_DISTANCES) {
                for (double dy : AROUND_HEIGHTS) {
                    Location feet = base.clone().add(side.clone().multiply(distance + clearance)).add(0, dy, 0);
                    snapToGround(world, feet, AROUND_GROUND_SNAP);
                    if (!isSafe(ctx, feet, body) || !clearPath(targetEye, feet.clone().add(0, body.eyeHeight(), 0))) {
                        continue;
                    }
                    Vector look = focus.clone().subtract(feet.toVector().add(new Vector(0, body.eyeHeight(), 0)));
                    if (look.lengthSquared() > 1.0E-6) {
                        feet.setDirection(look);
                    }
                    return feet;
                }
            }
        }
        return null;
    }

    /**
     * How far a movement from {@code origin} along {@code direction} may go so that it ends inside the arena and its
     * current border: {@code distance} when that end point is inside, otherwise the farthest point (sampled every
     * half block) that is, or 0 if there is none. A zero direction never moves, so it returns {@code distance}.
     */
    public static double arenaReach(AbilityContext ctx, Location origin, Vector direction, double distance) {
        if (distance <= 0) {
            return 0;
        }
        double length = direction.length();
        if (!(length > 1.0E-9)) {
            return distance;
        }
        Vector unit = direction.clone().multiply(1 / length);
        for (double d = distance; d > 0; d -= ARENA_STEP) {
            if (ctx.isInsideArena(origin.clone().add(unit.clone().multiply(d)))) {
                return d;
            }
        }
        return 0;
    }
}
