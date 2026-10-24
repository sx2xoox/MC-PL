package io.github.sx2xoox.mcpl.game.superpower.loot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

import io.github.sx2xoox.mcpl.core.arena.Arena;
import io.github.sx2xoox.mcpl.core.arena.Spread;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.map.ArenaMap;
import io.github.sx2xoox.mcpl.core.map.MapService;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Fence;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.Wall;
import org.jspecify.annotations.Nullable;

/**
 * Finds the places loot chests go. A chest spot is a free block (air or a plant, never a fluid, a real block or a
 * temporary one) on a full, safe floor, with free space above (so the lid opens) and room on at least three sides,
 * away from hoppers and other containers. It never blocks a way: the open places around it stay connected without it
 * (a chest is never right in front of a doorway, an archway, a one-wide staircase or a corridor).
 *
 * <p><b>Reachable on foot</b>, with steps a player can also walk back (level, one block up or down, ladders and vines;
 * no drops, no swimming, no closed doors):
 * <ul>
 *     <li>outside built-in maps ({@link #connectTo}), a spot must be connected to where a player stands when the fight
 *     starts: sealed caves, closed buildings, roofs, plateaus and tower tops are not, whatever their size;</li>
 *     <li>in a built-in map world a small walk search from the spot must reach at least {@value #REACH_NODES} standing
 *     places: roofs and tower tops that need parkour are small islands, and the cut-off areas a built-in map may have
 *     are at most 80 places ({@code CitadelMapTest}).</li>
 * </ul>
 *
 * <p><b>Near the ground.</b> In a built-in map world the floor closest to the map's ground level is preferred in every
 * column (a street before a roof, a hall before an attic); elsewhere the column's surface (without leaves) is, or the
 * walkable floor closest below it (a hall under a roof).
 *
 * <p>Runs on the main thread and loads the chunks it inspects; it samples a bounded number of columns, like
 * {@link Spread}.
 */
public final class LootPlacer {

    /** Standing places a spot must reach with reversible steps to count as reachable on foot. */
    public static final int REACH_NODES = 160;
    /** Chests stay within this share of the arena radius (like player spawns). */
    public static final double RADIUS_FRACTION = 0.85;
    /** How high above its landing spot a supply drop starts falling (blocks). */
    public static final int FALL_HEIGHT = 40;
    /** Chests are never closer than this to each other (blocks). */
    static final double MIN_GAP = 4.0;
    private static final int MAX_SAMPLES = 400;
    private static final int BEST_OF = 8;
    private static final int SEARCH_ABOVE = 12;
    private static final int SEARCH_BELOW = 10;
    private static final int SURFACE_RANGE = 48;
    private static final int REACH_HEIGHT = 32;
    /** Spots within this many blocks of the ground level count as "on the ground" (preferred). */
    private static final int NEAR_GROUND = 4;
    /** Most standing places {@link #connectTo} visits (far more than an arena of radius 100 has on one level). */
    static final int REGION_NODES = 150_000;
    /** Outside the final border, a raised supply drop spot counts as this many blocks farther from the center. */
    private static final double RAISED_PENALTY = 6.0;
    private static final int NONE = Integer.MIN_VALUE;
    /** The eight cells around a spot in ring order: N, NE, E, SE, S, SW, W, NW (even indexes are the sides). */
    private static final int[][] RING = {{0, -1}, {1, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}};

    private static final Set<Material> HAZARDS = EnumSet.of(
            Material.FIRE, Material.SOUL_FIRE, Material.LAVA, Material.WATER, Material.COBWEB, Material.POWDER_SNOW,
            Material.SWEET_BERRY_BUSH, Material.WITHER_ROSE, Material.MAGMA_BLOCK, Material.CACTUS,
            Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.NETHER_PORTAL, Material.END_PORTAL,
            Material.END_GATEWAY, Material.BUBBLE_COLUMN, Material.POINTED_DRIPSTONE);

    private static final Set<Material> WET = EnumSet.of(
            Material.WATER, Material.LAVA, Material.SEAGRASS, Material.TALL_SEAGRASS, Material.KELP,
            Material.KELP_PLANT, Material.BUBBLE_COLUMN);

    /** Blocks that move items into a neighbouring container (or are one): a chest never goes next to them. */
    private static final Set<Material> CONTAINERS = EnumSet.of(
            Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL, Material.HOPPER, Material.DROPPER,
            Material.DISPENSER, Material.CRAFTER, Material.ENDER_CHEST);

    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};

    /**
     * A chest spot.
     *
     * @param block  the block the chest replaces
     * @param facing the side the chest's front faces (an open side, towards the arena center if possible)
     */
    public record Spot(Block block, BlockFace facing) {

        /** The center of the block. */
        public Location center() {
            return block.getLocation().add(0.5, 0.5, 0.5);
        }

        double distanceSquared(Location other) {
            double dx = block.getX() + 0.5 - other.getX();
            double dz = block.getZ() + 0.5 - other.getZ();
            return dx * dx + dz * dz;
        }
    }

    private final World world;
    private final Arena arena;
    private final Random random;
    private final int groundY;
    private final boolean nearGround;
    private final Predicate<Block> taken;
    private final Map<Long, Boolean> standCache = new HashMap<>();
    private final Map<Long, Boolean> reachCache = new HashMap<>();
    /** Standing places connected to the players ({@link #connectTo}), or null to use the walk search. */
    private @Nullable Set<Long> walkable;

    /**
     * @param groundY    feet level of the ground (map ground + 1, or the arena center)
     * @param nearGround true in built-in map worlds: per column, the floor closest to {@code groundY} wins
     * @param taken      blocks that must not be used (temporary blocks)
     */
    public LootPlacer(Arena arena, Random random, int groundY, boolean nearGround, Predicate<Block> taken) {
        this.world = arena.world();
        this.arena = arena;
        this.random = random;
        this.groundY = groundY;
        this.nearGround = nearGround;
        this.taken = taken;
    }

    /** True in built-in map worlds (the map's ground level is preferred; reachability is the walk search). */
    public boolean usesMapGround() {
        return nearGround;
    }

    /**
     * Limits the reachable spots to {@code region} (from {@link #connectTo} of an earlier placer of the same match),
     * or with null back to the walk search.
     */
    public void useWalkable(@Nullable Set<Long> region) {
        this.walkable = region;
    }

    /**
     * Finds every standing place a player can walk to (with reversible steps) from one of {@code from} inside the arena
     * (and within {@value #REACH_HEIGHT} blocks up or down of them) and from now on accepts only spots among them. One
     * bounded flood fill (at most {@value #REGION_NODES} places) instead of a walk search per spot.
     *
     * @param from where the players stand
     * @return the region (to reuse with {@link #useWalkable}), or null if none of {@code from} is a standing place
     *         (then the walk search stays in use)
     */
    public @Nullable Set<Long> connectTo(Collection<Location> from) {
        Set<Long> seen = new HashSet<>();
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        int low = Integer.MAX_VALUE;
        int high = Integer.MIN_VALUE;
        for (Location location : from) {
            if (!world.equals(location.getWorld())) {
                continue;
            }
            int x = location.getBlockX();
            int y = location.getBlockY();
            int z = location.getBlockZ();
            // On a slab or a path the feet are inside the floor block; mid-jump they are above an empty one.
            for (int feet : new int[] {y, y + 1, y - 1, y - 2}) {
                if (stand(x, feet, z)) {
                    if (seen.add(key(x, feet, z))) {
                        queue.add(new int[] {x, feet, z});
                    }
                    low = Math.min(low, feet);
                    high = Math.max(high, feet);
                    break;
                }
            }
        }
        if (queue.isEmpty()) {
            return null;
        }
        double limit = arena.radius() + 8.0;
        while (!queue.isEmpty() && seen.size() < REGION_NODES) {
            int[] node = queue.poll();
            for (int[] next : moves(node[0], node[1], node[2])) {
                double dx = next[0] + 0.5 - arena.centerX();
                double dz = next[2] + 0.5 - arena.centerZ();
                if (next[1] < low - REACH_HEIGHT || next[1] > high + REACH_HEIGHT || dx * dx + dz * dz > limit * limit) {
                    continue;
                }
                if (seen.add(key(next[0], next[1], next[2]))) {
                    queue.add(next);
                }
            }
        }
        walkable = seen;
        return seen;
    }

    /** The placer for a match: the map's ground level in built-in map worlds, the arena center height elsewhere. */
    public static LootPlacer of(GameSession session, MapService maps) {
        Arena arena = session.arena();
        World world = arena.world();
        ArenaMap map = maps.isMapWorld(world)
                ? maps.map(world.getName().substring(MapService.WORLD_PREFIX.length())) : null;
        int ground = map != null ? map.groundY() + 1 : arena.center().getBlockY();
        return new LootPlacer(arena, session.random(), ground, map != null, session.hub().tempBlocks()::isTemporary);
    }

    // =================================================================== choosing spots

    /**
     * Up to {@code count} spread-out chest spots inside {@value #RADIUS_FRACTION} of the arena radius, at least
     * {@code avoidDistance} blocks (horizontally) from every location in {@code avoid} and {@value #MIN_GAP} from
     * every spot in {@code existing}. Fewer are returned if the arena has no more good places.
     */
    public List<Spot> chestSpots(int count, Collection<Location> avoid, double avoidDistance,
                                 Collection<Spot> existing) {
        if (count <= 0) {
            return List.of();
        }
        int samples = Math.max(48, Math.min(MAX_SAMPLES, count * 12));
        double avoidSquared = avoidDistance * avoidDistance;
        List<Spot> candidates = new ArrayList<>();
        Set<Long> columns = new HashSet<>();
        for (int attempt = 0; attempt < samples * 2 && candidates.size() < samples; attempt++) {
            Location point = arena.randomPoint(random, RADIUS_FRACTION);
            Spot spot = candidate(point.getBlockX(), point.getBlockZ(), false, columns);
            if (spot == null || !farFrom(spot, avoid, avoidSquared)) {
                continue;
            }
            // In a map, raised floors (wall walks, upper storeys) are fine but rarer than the ground.
            if (nearGround && !isNearGround(spot) && random.nextInt(3) != 0) {
                continue;
            }
            candidates.add(spot);
        }
        return choose(candidates, count, existing);
    }

    /**
     * True if the spot is within {@value #NEAR_GROUND} blocks of the map's ground level; outside built-in maps every
     * spot is (it is the surface, or the floor under it, of its column).
     */
    private boolean isNearGround(Spot spot) {
        return !nearGround || Math.abs(spot.block().getY() - groundY) <= NEAR_GROUND;
    }

    /**
     * Up to {@code count} supply drop spots under open sky, where the border keeps them longest: inside the final border
     * square ({@code finalHalfSize} blocks from the center along both axes) if there is room, spread apart and ground
     * before raised floors (in built-in maps); otherwise the spots closest to the center. The search starts around the
     * final square and widens only while spots are missing.
     */
    public List<Spot> supplySpots(int count, double finalHalfSize, Collection<Spot> existing) {
        if (count <= 0) {
            return List.of();
        }
        double maxRadius = Math.max(1, arena.radius() * RADIUS_FRACTION);
        double half = Math.max(1.0, finalHalfSize);
        // The circle through the final square's corners.
        double radius = Math.max(3, Math.min(maxRadius, half * Math.sqrt(2)));
        List<Spot> found = new ArrayList<>();
        Set<Long> columns = new HashSet<>();
        Location center = arena.center();
        while (true) {
            for (int attempt = 0; attempt < 48; attempt++) {
                double angle = random.nextDouble() * Math.PI * 2;
                double distance = Math.sqrt(random.nextDouble()) * radius;
                Spot spot = candidate((int) Math.floor(center.getX() + Math.cos(angle) * distance),
                        (int) Math.floor(center.getZ() + Math.sin(angle) * distance), true, columns);
                if (spot != null) {
                    found.add(spot);
                }
            }
            List<Spot> chosen = pickSupply(found, count, existing, half);
            if (chosen.size() >= count || radius >= maxRadius) {
                return chosen;
            }
            radius = Math.min(maxRadius, radius * 1.6);
        }
    }

    /**
     * The supply drop spots among {@code candidates}: inside the final square first (on the ground, then raised; each
     * group spread apart), then the rest by distance to the center.
     */
    private List<Spot> pickSupply(List<Spot> candidates, int count, Collection<Spot> existing, double half) {
        List<Spot> insideLow = new ArrayList<>();
        List<Spot> insideRaised = new ArrayList<>();
        List<Spot> outside = new ArrayList<>();
        for (Spot spot : candidates) {
            double dx = Math.abs(spot.block().getX() + 0.5 - arena.centerX());
            double dz = Math.abs(spot.block().getZ() + 0.5 - arena.centerZ());
            if (dx > half || dz > half) {
                outside.add(spot);
            } else {
                (isNearGround(spot) ? insideLow : insideRaised).add(spot);
            }
        }
        List<Spot> chosen = new ArrayList<>();
        List<Spot> placed = new ArrayList<>(existing);
        for (List<Spot> group : List.of(insideLow, insideRaised)) {
            List<Spot> picked = choose(group, count - chosen.size(), placed);
            chosen.addAll(picked);
            placed.addAll(picked);
        }
        Location center = arena.center();
        outside.sort(Comparator.comparingDouble(
                spot -> Math.sqrt(spot.distanceSquared(center)) + (isNearGround(spot) ? 0 : RAISED_PENALTY)));
        for (Spot spot : outside) {
            if (chosen.size() >= count) {
                break;
            }
            if (isFree(spot, placed) && reachable(spot.block())) {
                chosen.add(spot);
                placed.add(spot);
            }
        }
        return chosen;
    }

    /**
     * The reachable spot closest to {@code near} within {@code radius} blocks (horizontally), or null; under open sky
     * if {@code openSky}. Used when the planned landing spot of a supply drop was taken meanwhile.
     */
    public @Nullable Spot spotNear(Location near, int radius, boolean openSky, Collection<Spot> existing) {
        int cx = near.getBlockX();
        int cz = near.getBlockZ();
        for (int r = 0; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    Spot spot = spotInColumn(cx + dx, cz + dz, openSky);
                    if (spot != null && isFree(spot, existing) && reachable(spot.block())) {
                        return spot;
                    }
                }
            }
        }
        return null;
    }

    /** Best-candidate sampling: each pick is the farthest from everything chosen among a few random candidates. */
    private List<Spot> choose(List<Spot> candidates, int count, Collection<Spot> existing) {
        List<Spot> chosen = new ArrayList<>();
        List<Spot> placed = new ArrayList<>(existing);
        while (chosen.size() < count && !candidates.isEmpty()) {
            int bestIndex = -1;
            double bestDistance = -1;
            int tries = Math.min(BEST_OF, candidates.size());
            for (int i = 0; i < tries; i++) {
                int index = random.nextInt(candidates.size());
                double nearest = Double.MAX_VALUE;
                Location center = candidates.get(index).center();
                for (Spot other : placed) {
                    nearest = Math.min(nearest, other.distanceSquared(center));
                }
                if (nearest > bestDistance) {
                    bestDistance = nearest;
                    bestIndex = index;
                }
            }
            Spot best = candidates.remove(bestIndex);
            if (isFree(best, placed) && reachable(best.block())) {
                chosen.add(best);
                placed.add(best);
            }
        }
        return chosen;
    }

    private static boolean farFrom(Spot spot, Collection<Location> avoid, double minSquared) {
        for (Location location : avoid) {
            if (location.getWorld() != null && location.getWorld().equals(spot.block().getWorld())
                    && spot.distanceSquared(location) < minSquared) {
                return false;
            }
        }
        return true;
    }

    private static boolean isFree(Spot spot, Collection<Spot> others) {
        Location center = spot.center();
        for (Spot other : others) {
            if (other.distanceSquared(center) < MIN_GAP * MIN_GAP
                    && Math.abs(other.block().getY() - spot.block().getY()) < 6) {
                return false;
            }
        }
        return true;
    }

    private @Nullable Spot candidate(int x, int z, boolean openSky, Set<Long> columns) {
        if (!columns.add(((long) x << 32) ^ (z & 0xFFFFFFFFL))) {
            return null;
        }
        return spotInColumn(x, z, openSky);
    }

    // =================================================================== one column

    /**
     * The chest spot of column {@code x, z}: the valid spot closest to the column's reference height (the map ground,
     * or the surface), or null. {@code openSky} also requires nothing solid above it (supply drops fall in). With a
     * {@link #connectTo region} only spots in it count, so the column's next floor is tried instead of a sealed one.
     */
    public @Nullable Spot spotInColumn(int x, int z, boolean openSky) {
        if (!arena.contains(new Location(world, x + 0.5, groundY, z + 0.5))) {
            return null;
        }
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            world.getChunkAt(x >> 4, z >> 4);
        }
        int reference = groundY;
        if (!nearGround) {
            int surface = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
            if (Math.abs(surface - groundY) <= SURFACE_RANGE) {
                reference = surface;
            }
        }
        int min = Math.max(world.getMinHeight() + 1, reference - SEARCH_BELOW);
        int max = Math.min(world.getMaxHeight() - 2, reference + SEARCH_ABOVE);
        // Closest to the reference first; at equal distance the lower floor (ground before roof).
        for (int offset = 0; offset <= Math.max(SEARCH_ABOVE, SEARCH_BELOW); offset++) {
            for (int sign = offset == 0 ? 1 : -1; sign <= 1; sign += 2) {
                int y = reference + sign * offset;
                if (y < min || y > max) {
                    continue;
                }
                Block cell = world.getBlockAt(x, y, z);
                if (isChestSpot(cell) && (!openSky || hasOpenSky(cell))
                        && (walkable == null || walkable.contains(key(x, y, z)))) {
                    return new Spot(cell, facing(cell));
                }
            }
        }
        return null;
    }

    /** True if a chest can stand in {@code cell} (see the class docs; reachability is checked separately). */
    public boolean isChestSpot(Block cell) {
        if (taken.test(cell) || isWet(cell) || !(cell.isEmpty() || cell.isReplaceable())) {
            return false;
        }
        Block floor = cell.getRelative(BlockFace.DOWN);
        if (!Spread.isSafeFloor(floor) || !isFullFloor(floor) || taken.test(floor)) {
            return false;
        }
        int open = 0;
        for (BlockFace side : SIDES) {
            Block next = cell.getRelative(side);
            if (isContainer(next.getType())) {
                return false;
            }
            if (stand(next.getX(), next.getY(), next.getZ())) {
                open++;
            }
        }
        return open >= 3 && !isContainer(cell.getRelative(BlockFace.UP).getType()) && !cutsWay(cell);
    }

    /**
     * True if a chest in {@code cell} would cut a way: going around it through the eight cells around it, some open
     * side is no longer connected to the other open sides. That is the mouth of a doorway, an archway, a one-wide
     * staircase or a corridor (walls on both sides of the opening; a lintel stops a jump onto the chest). Neighbours
     * one block up or down count as open (steps), with at most one block between two neighbours.
     */
    private boolean cutsWay(Block cell) {
        int x = cell.getX();
        int y = cell.getY();
        int z = cell.getZ();
        int[] height = new int[RING.length];
        for (int i = 0; i < RING.length; i++) {
            height[i] = standingHeight(x + RING[i][0], y, z + RING[i][1]);
        }
        boolean[] link = new boolean[RING.length];
        int gap = -1;
        for (int i = 0; i < RING.length; i++) {
            int next = height[(i + 1) % RING.length];
            link[i] = height[i] != NONE && next != NONE && Math.abs(height[i] - next) <= 1;
            if (!link[i]) {
                gap = i;
            }
        }
        if (gap < 0) {
            return false; // open all around
        }
        // Walk the ring once from just after a gap; every gap ends a stretch of connected cells.
        int stretches = 0;
        boolean side = false;
        for (int step = 1; step <= RING.length; step++) {
            int i = (gap + step) % RING.length;
            side |= i % 2 == 0 && height[i] != NONE;
            if (!link[i]) {
                if (side) {
                    stretches++;
                }
                side = false;
            }
        }
        return stretches > 1;
    }

    /** The feet height a player stands at in column {@code x, z} next to feet height {@code y} (level first), or NONE. */
    private int standingHeight(int x, int y, int z) {
        if (stand(x, y, z)) {
            return y;
        }
        if (stand(x, y + 1, z)) {
            return y + 1;
        }
        return stand(x, y - 1, z) ? y - 1 : NONE;
    }

    /** A floor a chest sits flush on: a solid full block, not stairs, slabs, fences, beds, doors or block entities. */
    private static boolean isFullFloor(Block floor) {
        BlockData data = floor.getBlockData();
        Material type = floor.getType();
        if (data instanceof Stairs || isThin(type, data) || data instanceof Openable || data instanceof Bed
                || data instanceof Slab slab && slab.getType() != Slab.Type.DOUBLE) {
            return false;
        }
        if (HAZARDS.contains(type) || tagged(Tag.LEAVES, type) || type == Material.SCAFFOLDING) {
            return false;
        }
        return !(floor.getState(false) instanceof TileState);
    }

    /** Fences, walls, bars and panes: shapes nobody stands on (or puts a chest on) without a jump or parkour. */
    private static boolean isThin(Material type, BlockData data) {
        String name = type.name();
        return data instanceof Fence || data instanceof Wall || tagged(Tag.FENCES, type) || tagged(Tag.WALLS, type)
                || name.endsWith("_PANE") || name.endsWith("_BARS");
    }

    /** Fluids, waterlogged blocks and underwater plants (whose block is water too). */
    private static boolean isWet(Block block) {
        return block.isLiquid() || WET.contains(block.getType())
                || block.getBlockData() instanceof Waterlogged waterlogged && waterlogged.isWaterlogged();
    }

    private static boolean isContainer(Material type) {
        return CONTAINERS.contains(type) || tagged(Tag.SHULKER_BOXES, type) || tagged(Tag.COPPER_CHESTS, type);
    }

    private static boolean tagged(@Nullable Tag<Material> tag, Material type) {
        return tag != null && tag.isTagged(type);
    }

    /** Nothing solid or liquid above {@code cell} up to the height a supply drop falls from. */
    private boolean hasOpenSky(Block cell) {
        int top = Math.min(world.getMaxHeight() - 1, cell.getY() + FALL_HEIGHT);
        for (int y = cell.getY() + 1; y <= top; y++) {
            Block block = world.getBlockAt(cell.getX(), y, cell.getZ());
            if (block.getType().isSolid() || block.isLiquid()) {
                return false;
            }
        }
        return true;
    }

    /** The open side closest to the direction of the arena center. */
    private BlockFace facing(Block cell) {
        double dx = arena.centerX() - (cell.getX() + 0.5);
        double dz = arena.centerZ() - (cell.getZ() + 0.5);
        BlockFace best = BlockFace.SOUTH;
        double bestScore = -Double.MAX_VALUE;
        for (BlockFace side : SIDES) {
            Block next = cell.getRelative(side);
            double score = side.getModX() * dx + side.getModZ() * dz;
            if (!stand(next.getX(), next.getY(), next.getZ())) {
                score -= 1.0E6;
            }
            if (score > bestScore) {
                bestScore = score;
                best = side;
            }
        }
        return best;
    }

    // =================================================================== walking

    /**
     * True if a player standing in {@code cell} can walk (with steps it can also walk back) to the players' region
     * ({@link #connectTo}), or without one to at least {@value #REACH_NODES} standing places.
     */
    public boolean reachable(Block cell) {
        int x0 = cell.getX();
        int y0 = cell.getY();
        int z0 = cell.getZ();
        long start = key(x0, y0, z0);
        if (walkable != null) {
            return walkable.contains(start) && stand(x0, y0, z0);
        }
        Boolean known = reachCache.get(start);
        if (known != null) {
            return known;
        }
        if (!stand(x0, y0, z0)) {
            reachCache.put(start, false);
            return false;
        }
        double limit = arena.radius() + 8.0;
        Set<Long> seen = new HashSet<>();
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        seen.add(start);
        queue.add(new int[] {x0, y0, z0});
        boolean reached = false;
        while (!queue.isEmpty()) {
            int[] node = queue.poll();
            Boolean cached = reachCache.get(key(node[0], node[1], node[2]));
            if (cached != null && cached || seen.size() >= REACH_NODES) {
                reached = true;
                break;
            }
            for (int[] next : moves(node[0], node[1], node[2])) {
                double dx = next[0] + 0.5 - arena.centerX();
                double dz = next[2] + 0.5 - arena.centerZ();
                if (Math.abs(next[1] - y0) > REACH_HEIGHT || dx * dx + dz * dz > limit * limit) {
                    continue;
                }
                if (seen.add(key(next[0], next[1], next[2]))) {
                    queue.add(next);
                }
            }
        }
        for (long node : seen) {
            reachCache.putIfAbsent(node, reached);
        }
        return reached;
    }

    /** The standing places one reversible step away from the one at {@code x, y, z} (feet height {@code y}). */
    private List<int[]> moves(int x, int y, int z) {
        List<int[]> out = new ArrayList<>(6);
        boolean headroom = free(x, y + 2, z);
        for (BlockFace side : SIDES) {
            int nx = x + side.getModX();
            int nz = z + side.getModZ();
            if (stand(nx, y, nz)) {
                out.add(new int[] {nx, y, nz});
            }
            if (headroom && stand(nx, y + 1, nz)) {
                out.add(new int[] {nx, y + 1, nz});
            }
            if (free(nx, y + 1, nz) && stand(nx, y - 1, nz)) {
                out.add(new int[] {nx, y - 1, nz});
            }
        }
        if (climbable(x, y, z) || climbable(x, y + 1, z)) {
            if (stand(x, y + 1, z)) {
                out.add(new int[] {x, y + 1, z});
            }
        }
        if (climbable(x, y - 1, z) && stand(x, y - 1, z)) {
            out.add(new int[] {x, y - 1, z});
        }
        return out;
    }

    /** True if a player can stand with its feet in {@code x, y, z}: two free blocks on a floor (or a ladder). */
    private boolean stand(int x, int y, int z) {
        if (y <= world.getMinHeight() || y >= world.getMaxHeight() - 1) {
            return false;
        }
        long key = key(x, y, z);
        Boolean cached = standCache.get(key);
        if (cached != null) {
            return cached;
        }
        boolean result = free(x, y, z) && free(x, y + 1, z)
                && (isWalkableFloor(world.getBlockAt(x, y - 1, z)) || climbable(x, y, z));
        standCache.put(key, result);
        return result;
    }

    /** A block the body can be in: not solid (or an open door/gate), no fluid, nothing that hurts or traps. */
    private boolean free(int x, int y, int z) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
            return false;
        }
        Block block = world.getBlockAt(x, y, z);
        Material type = block.getType();
        if (isWet(block) || HAZARDS.contains(type)) {
            return false;
        }
        return !type.isSolid() || block.getBlockData() instanceof Openable openable && openable.isOpen();
    }

    /** A floor to walk on: solid, not leaves, fences or walls (standing on those takes a jump nobody can make). */
    private static boolean isWalkableFloor(Block floor) {
        Material type = floor.getType();
        if (!type.isSolid() || tagged(Tag.LEAVES, type) || type == Material.LAVA || type == Material.CACTUS) {
            return false;
        }
        BlockData data = floor.getBlockData();
        return !isThin(type, data) && !(data instanceof Openable openable && openable.isOpen());
    }

    private boolean climbable(int x, int y, int z) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
            return false;
        }
        return tagged(Tag.CLIMBABLE, world.getBlockAt(x, y, z).getType());
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | (y & 0xFFFL);
    }
}
