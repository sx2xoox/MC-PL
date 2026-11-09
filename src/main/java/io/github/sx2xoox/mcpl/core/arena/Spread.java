package io.github.sx2xoox.mcpl.core.arena;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.jspecify.annotations.Nullable;

/**
 * Finds safe places to put players in an arena. A safe spot stands on a solid, non-hazardous floor (no magma, cactus,
 * campfire, dripstone, leaves or fluids) with two non-solid, non-liquid, non-hazardous blocks above it.
 *
 * <p>Runs synchronously and loads the chunks it inspects; it samples a bounded number of columns, so it is cheap
 * enough to call once at match start.
 */
public final class Spread {

    private static final Set<Material> HAZARD_FLOORS = EnumSet.of(
            Material.MAGMA_BLOCK, Material.CACTUS, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
            Material.POINTED_DRIPSTONE, Material.BARRIER);

    private static final Set<Material> HAZARD_SPACE = EnumSet.of(
            Material.FIRE, Material.SOUL_FIRE, Material.COBWEB, Material.POWDER_SNOW, Material.SWEET_BERRY_BUSH,
            Material.WITHER_ROSE, Material.NETHER_PORTAL, Material.END_PORTAL, Material.END_GATEWAY,
            Material.LAVA, Material.WATER, Material.BUBBLE_COLUMN);

    private Spread() {
    }

    /**
     * {@code count} safe standing locations inside the arena, spread out so that the smallest distance between any two
     * is as large as possible. Each faces the arena center. If the arena has fewer safe spots than requested, spots
     * are reused; if it has none, the arena center is returned for everyone.
     */
    public static List<Location> findSpawns(Arena arena, int count, Random random) {
        if (count <= 0) {
            return List.of();
        }
        int wanted = Math.max(24, Math.min(240, count * 12));
        List<Location> candidates = new ArrayList<>(wanted);
        for (int attempt = 0; attempt < wanted * 4 && candidates.size() < wanted; attempt++) {
            Location point = arena.randomPoint(random, 0.85);
            Location ground = safeGround(arena.world(), point.getBlockX(), point.getBlockZ(), arena.center().getBlockY());
            if (ground != null) {
                candidates.add(ground);
            }
        }
        return pick(arena, candidates, count, random);
    }

    /**
     * Like {@link #findSpawns}, but for natural terrain of any height (a survival world): every candidate is the top
     * of its column (ignoring leaves), so mountains and valleys count as well as the center's height; columns whose top
     * is water, lava, leaves or another unsafe floor are skipped.
     */
    public static List<Location> findSurfaceSpawns(Arena arena, int count, Random random) {
        if (count <= 0) {
            return List.of();
        }
        int wanted = Math.max(24, Math.min(240, count * 12));
        List<Location> candidates = new ArrayList<>(wanted);
        for (int attempt = 0; attempt < wanted * 4 && candidates.size() < wanted; attempt++) {
            Location point = arena.randomPoint(random, 0.85);
            Location ground = surface(arena.world(), point.getBlockX(), point.getBlockZ());
            if (ground != null) {
                candidates.add(ground);
            }
        }
        return pick(arena, candidates, count, random);
    }

    /**
     * The standing location on top of the highest non-leaf block of column {@code x, z} if that is a safe floor,
     * else null. Loads the chunk if needed.
     */
    public static @Nullable Location surface(World world, int x, int z) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            world.getChunkAt(x >> 4, z >> 4);
        }
        Block top = world.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        return isSafeFloor(top) ? standOn(top) : null;
    }

    /** {@code count} of the candidates, as far apart as possible, facing the center (see {@link #findSpawns}). */
    private static List<Location> pick(Arena arena, List<Location> candidates, int count, Random random) {
        List<Location> chosen = new ArrayList<>(count);
        if (candidates.isEmpty()) {
            Location fallback = safeSpot(arena, arena.center());
            for (int i = 0; i < count; i++) {
                chosen.add(fallback.clone());
            }
            return chosen;
        }
        List<Location> pool = new ArrayList<>(candidates);
        chosen.add(pool.remove(random.nextInt(pool.size())));
        while (chosen.size() < count) {
            if (pool.isEmpty()) {
                pool.addAll(candidates);
            }
            int bestIndex = 0;
            double bestDistance = -1;
            for (int i = 0; i < pool.size(); i++) {
                double nearest = Double.MAX_VALUE;
                for (Location picked : chosen) {
                    nearest = Math.min(nearest, horizontalDistanceSquared(pool.get(i), picked));
                }
                if (nearest > bestDistance) {
                    bestDistance = nearest;
                    bestIndex = i;
                }
            }
            chosen.add(pool.remove(bestIndex));
        }
        Location center = arena.center();
        for (Location location : chosen) {
            face(location, center);
        }
        return chosen;
    }

    /**
     * A safe spot at or near {@code near} (searching its column, then random columns around it within the arena),
     * falling back to {@code near} itself.
     */
    public static Location safeSpot(Arena arena, Location near) {
        World world = arena.world();
        Location target = world.equals(near.getWorld()) ? arena.clampInside(near) : arena.center();
        Location ground = safeGround(world, target.getBlockX(), target.getBlockZ(), target.getBlockY());
        if (ground != null) {
            ground.setYaw(near.getYaw());
            ground.setPitch(near.getPitch());
            return ground;
        }
        Random random = new Random(target.getBlockX() * 31L + target.getBlockZ());
        for (int i = 0; i < 32; i++) {
            int x = target.getBlockX() + random.nextInt(17) - 8;
            int z = target.getBlockZ() + random.nextInt(17) - 8;
            ground = safeGround(world, x, z, target.getBlockY());
            if (ground != null) {
                return ground;
            }
        }
        return target;
    }

    /**
     * The standing location (block center, on top of the floor) of the safe floor in column {@code x, z} closest to
     * the surface near {@code referenceY}, or null if the column has none. Loads the chunk if needed.
     */
    public static @Nullable Location safeGround(World world, int x, int z, int referenceY) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            world.getChunkAt(x >> 4, z >> 4);
        }
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 3;
        if (!world.hasCeiling()) {
            Block top = world.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            if (Math.abs(top.getY() - referenceY) <= 48 && isSafeFloor(top)) {
                return standOn(top);
            }
        }
        int high = Math.min(maxY, referenceY + 24);
        int low = Math.max(minY, referenceY - 32);
        for (int y = high; y >= low; y--) {
            Block floor = world.getBlockAt(x, y, z);
            if (isSafeFloor(floor)) {
                return standOn(floor);
            }
        }
        return null;
    }

    /** True if a player can stand on {@code floor} safely (see class docs). */
    public static boolean isSafeFloor(Block floor) {
        Material type = floor.getType();
        if (!type.isSolid() || HAZARD_FLOORS.contains(type) || Tag.LEAVES.isTagged(type)) {
            return false;
        }
        Block feet = floor.getRelative(BlockFace.UP);
        Block head = feet.getRelative(BlockFace.UP);
        return isFreeSpace(feet) && isFreeSpace(head);
    }

    private static boolean isFreeSpace(Block block) {
        Material type = block.getType();
        return !type.isSolid() && !block.isLiquid() && !HAZARD_SPACE.contains(type);
    }

    private static Location standOn(Block floor) {
        return new Location(floor.getWorld(), floor.getX() + 0.5, floor.getY() + 1.0, floor.getZ() + 0.5);
    }

    private static void face(Location location, Location target) {
        double dx = target.getX() - location.getX();
        double dz = target.getZ() - location.getZ();
        if (dx * dx + dz * dz < 1.0E-4) {
            return;
        }
        location.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        location.setPitch(0f);
    }

    private static double horizontalDistanceSquared(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }
}
