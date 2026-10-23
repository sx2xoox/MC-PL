package io.github.sx2xoox.mcpl.core.arena;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Logger;

import io.github.sx2xoox.mcpl.core.config.BlockDamageScope;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/**
 * Terrain damage of one match: explosions and heavy impacts of the mode break blocks, the battlefield gets more and more
 * ruined, and everything comes back at once when the match ends (the blocks are removed through the
 * {@link TempBlockService}, crash-safe). Nothing ever drops as an item.
 *
 * <h2>Ground and structures</h2>
 * Every column touched gets its original <b>surface</b>, cached for the match: the top of the natural ground (a run of
 * at least three ground blocks such as dirt, grass, sand, gravel, natural stone or terracotta, see
 * {@link TerrainRules#isGround}), or the solid block right on top of it (paving, a floor, the foot of a wall).
 * <ul>
 *     <li>Above the surface everything breaks freely within the blast: walls, pillars, rocks, trees, bridges, roofs.</li>
 *     <li>The ground is dug at most two blocks below its original surface, from the top, and never where fewer than
 *     two solid blocks would remain under the crater (thin floors over the void). Repeated blasts never dig deeper.</li>
 *     <li>A crater never leaves a pit nobody can climb out of: every dug column must lead, one block up at a time, to
 *     ground that was not dug; columns that do not are dug less (so craters come out bowl shaped).</li>
 *     <li>A block standing over the void (nothing solid anywhere below it) never breaks.</li>
 * </ul>
 *
 * <h2>Never broken</h2>
 * Block entities (chests, signs, banners, skulls, spawners, ...), temporary blocks (and the blocks holding them up),
 * blocks next to water, lava or waterlogged blocks, blocks holding up a falling block (sand, gravel, concrete powder,
 * anvils, pointed dripstone, ...), blocks holding a painting or an item frame, unbreakable, explosion-proof and
 * technical blocks, and anything outside the arena. Blocks attached to a broken block (torches, ladders, vines, plants
 * and snow on top, hanging lanterns, both halves of doors and tall plants, ...) go and come back with it; if one of
 * them may not break, the block stays too.
 *
 * <p>At most {@code protection.ability-block-damage-limit} blocks break per match. Main thread only.
 */
public final class TerrainDamage {

    /** One broken block: its corner and what it was. */
    public record Broken(Location location, BlockData data) {
    }

    /** The ground is dug at most this deep below its original surface. */
    public static final int MAX_DEPTH = 2;
    /** Solid blocks that must remain under the deepest dug block. */
    private static final int SOLID_BELOW = 2;
    /** A natural ground run is at least this many ground blocks deep. */
    private static final int GROUND_RUN = 3;
    /** Columns are scanned this far above and below the blast for their surface and floor. */
    private static final int SCAN_RANGE = 64;
    /** Blasts are clamped to this radius (blocks). */
    public static final double MAX_RADIUS = 8;
    /** A broken block takes at most this many attached blocks with it. */
    private static final int MAX_GROUP = 32;
    /** A column's standing height is looked for up to this far above its surface (more is a wall). */
    private static final int CLIMB = 3;
    /** Older craters this many columns around a blast are checked for a way out too. */
    private static final int ESCAPE_MARGIN = 6;
    private static final int NONE = Integer.MIN_VALUE;

    /** A column's original surface ({@link #NONE} without ground) and its lowest solid block (MAX without any). */
    private record Column(int surface, int floor) {
        boolean hasSurface() {
            return surface != NONE;
        }
    }

    private record ColumnKey(UUID world, int x, int z) {
    }

    private record Pos(int x, int y, int z) {
        static Pos of(Block block) {
            return new Pos(block.getX(), block.getY(), block.getZ());
        }
    }

    /** A block the blast wants to break, with its distance to the blast. */
    private record Candidate(Block block, double distance, boolean ground) {
    }

    /** An accepted block and what goes with it (root first). */
    private record Group(Candidate root, List<Block> blocks) {
    }

    private final TempBlockService blocks;
    private final BlockDamageScope scope;
    private final int limit;
    private final Predicate<World> mapWorld;
    private final Logger logger;
    private final Map<ColumnKey, Column> columns = new HashMap<>();
    private int broken;
    private boolean warnedLimit;

    /**
     * @param blocks   the store that removes and restores the blocks
     * @param scope    where blocks may break
     * @param limit    at most this many blocks break in the match
     * @param mapWorld which worlds are battle map worlds
     */
    public TerrainDamage(TempBlockService blocks, BlockDamageScope scope, int limit, Predicate<World> mapWorld,
                         Logger logger) {
        this.blocks = blocks;
        this.scope = scope;
        this.limit = Math.max(0, limit);
        this.mapWorld = mapWorld;
        this.logger = logger;
    }

    /** True if blocks of {@code world} may break in this match. */
    public boolean appliesTo(World world) {
        boolean allowed = switch (scope) {
            case OFF -> false;
            case MAP_WORLDS -> mapWorld.test(world);
            case ALL_WORLDS -> true;
        };
        return allowed && limit > 0;
    }

    /** Blocks broken in this match so far. */
    public int brokenCount() {
        return broken;
    }

    /** Blocks that may still break in this match. */
    public int remaining() {
        return Math.max(0, limit - broken);
    }

    /**
     * Breaks the blocks whose centers lie within {@code radius} of {@code center} (see the class rules), only where
     * {@code inside} accepts a block's center. Returns what broke, nearest first.
     */
    public List<Broken> blast(Location center, double radius, Predicate<Location> inside) {
        return blastAlong(center, center, radius, inside);
    }

    /**
     * Breaks the blocks whose centers lie within {@code radius} of the segment {@code from}-{@code to} (a dash, a
     * charge), only where {@code inside} accepts a block's center. Returns what broke, nearest first.
     */
    public List<Broken> blastAlong(Location from, Location to, double radius, Predicate<Location> inside) {
        World world = from.getWorld();
        if (world == null || !world.equals(to.getWorld()) || !appliesTo(world) || !(radius > 0)
                || !Double.isFinite(radius) || remaining() <= 0 || !finite(from) || !finite(to)) {
            return List.of();
        }
        Blast blast = new Blast(world, from.toVector(), to.toVector(), Math.min(radius, MAX_RADIUS), inside);
        List<Broken> result = blast.run();
        broken += result.size();
        if (remaining() <= 0 && !warnedLimit) {
            warnedLimit = true;
            logger.info("Abilities broke " + broken + " blocks in this match, the configured limit "
                    + "(protection.ability-block-damage-limit); no more blocks break until it ends.");
        }
        return result;
    }

    private static boolean finite(Location location) {
        return Double.isFinite(location.getX()) && Double.isFinite(location.getY()) && Double.isFinite(location.getZ());
    }

    // ------------------------------------------------------------------ columns

    /** The column's original surface and floor, scanned the first time it is touched in the match. */
    private Column column(World world, int x, int z, int nearY) {
        ColumnKey key = new ColumnKey(world.getUID(), x, z);
        Column cached = columns.get(key);
        if (cached != null) {
            return cached;
        }
        Column scanned = scan(world, x, z, nearY);
        columns.put(key, scanned);
        return scanned;
    }

    private Column scan(World world, int x, int z, int nearY) {
        int top = Math.min(world.getMaxHeight() - 1, nearY + SCAN_RANGE);
        int bottom = Math.max(world.getMinHeight(), nearY - SCAN_RANGE);
        if (top < bottom) {
            return new Column(NONE, Integer.MAX_VALUE);
        }
        int size = top - bottom + 1;
        boolean[] ground = new boolean[size];
        boolean[] solid = new boolean[size];
        int floor = Integer.MAX_VALUE;
        for (int y = bottom; y <= top; y++) {
            Material material = blocks.originalData(world.getBlockAt(x, y, z)).getMaterial();
            ground[y - bottom] = TerrainRules.isGround(material);
            solid[y - bottom] = material.isSolid();
            if (solid[y - bottom] && floor == Integer.MAX_VALUE) {
                floor = y;
            }
        }
        int groundTop = NONE;
        int anyGround = NONE;
        for (int y = top; y >= bottom && groundTop == NONE; y--) {
            int i = y - bottom;
            if (!ground[i]) {
                continue;
            }
            if (anyGround == NONE) {
                anyGround = y;
            }
            boolean run = true;
            for (int below = 1; below < GROUND_RUN; below++) {
                run &= i - below >= 0 && ground[i - below];
            }
            if (run) {
                groundTop = y;
            }
        }
        if (groundTop == NONE) {
            groundTop = anyGround;
        }
        if (groundTop == NONE) {
            return new Column(NONE, floor);
        }
        int surface = groundTop + 1 <= top && solid[groundTop + 1 - bottom] ? groundTop + 1 : groundTop;
        return new Column(surface, floor);
    }

    // ------------------------------------------------------------------ one blast

    private final class Blast {

        private final World world;
        private final Vector from;
        private final Vector to;
        private final double radius;
        private final Predicate<Location> inside;
        private final int nearY;
        /** Accepted blocks of this blast. */
        private final Set<Pos> taken = new HashSet<>();
        private final List<Group> groups = new ArrayList<>();
        /** Ground columns dug deeper by this blast: column → its accepted ground groups, deepest last. */
        private final Map<Long, List<Group>> dug = new LinkedHashMap<>();
        private final List<BoundingBox> hangingHolds = new ArrayList<>();
        private int budget;

        Blast(World world, Vector from, Vector to, double radius, Predicate<Location> inside) {
            this.world = world;
            this.from = from;
            this.to = to;
            this.radius = radius;
            this.inside = inside;
            this.nearY = (int) Math.floor(Math.min(from.getY(), to.getY()));
            this.budget = remaining();
        }

        List<Broken> run() {
            BoundingBox box = BoundingBox.of(from, to).expand(radius);
            collectHanging(box);
            List<Candidate> candidates = candidates(box);
            // Top down: whatever a block holds up is decided before the block itself.
            candidates.sort(Comparator.comparingInt((Candidate c) -> c.block().getY()).reversed()
                    .thenComparingDouble(Candidate::distance));
            for (Candidate candidate : candidates) {
                if (budget <= 0) {
                    break;
                }
                accept(candidate);
            }
            keepEscapable();
            return apply();
        }

        // -------------------------------------------------------------- candidates

        private List<Candidate> candidates(BoundingBox box) {
            List<Candidate> out = new ArrayList<>();
            int minY = Math.max(world.getMinHeight(), (int) Math.floor(box.getMinY()));
            int maxY = Math.min(world.getMaxHeight() - 1, (int) Math.floor(box.getMaxY()));
            for (int x = (int) Math.floor(box.getMinX()); x <= (int) Math.floor(box.getMaxX()); x++) {
                for (int z = (int) Math.floor(box.getMinZ()); z <= (int) Math.floor(box.getMaxZ()); z++) {
                    Column column = column(world, x, z, nearY);
                    List<Candidate> groundLayer = new ArrayList<>(MAX_DEPTH);
                    for (int y = maxY; y >= minY; y--) {
                        double distance = distanceToSegment(x + 0.5, y + 0.5, z + 0.5);
                        if (distance > radius) {
                            continue;
                        }
                        Block block = world.getBlockAt(x, y, z);
                        if (!column.hasSurface()) {
                            if (column.floor() < y) {
                                out.add(new Candidate(block, distance, false));
                            }
                        } else if (y > column.surface()) {
                            out.add(new Candidate(block, distance, false));
                        } else if (y > column.surface() - MAX_DEPTH) {
                            groundLayer.add(new Candidate(block, distance, true));
                        }
                    }
                    out.addAll(groundLayer(column, x, z, groundLayer));
                }
            }
            return out;
        }

        /**
         * The ground blocks of a column this blast may dig: from the top of the original surface down, at most
         * {@link #MAX_DEPTH} deep, keeping {@link #SOLID_BELOW} solid blocks under the crater.
         */
        private List<Candidate> groundLayer(Column column, int x, int z, List<Candidate> reached) {
            if (reached.isEmpty()) {
                return List.of();
            }
            int surface = column.surface();
            int depth = 0;
            for (int y = surface; y > surface - MAX_DEPTH; y--) {
                int level = y;
                boolean gone = blocks.isRemoved(world.getBlockAt(x, y, z));
                if (!gone && reached.stream().noneMatch(c -> c.block().getY() == level)) {
                    break;
                }
                depth++;
            }
            while (depth > 0 && !solidUnder(x, surface - depth + 1, z)) {
                depth--;
            }
            List<Candidate> out = new ArrayList<>();
            for (Candidate candidate : reached) {
                if (candidate.block().getY() > surface - depth) {
                    out.add(candidate);
                }
            }
            return out;
        }

        /** True if the {@link #SOLID_BELOW} blocks under {@code y} are solid and stay. */
        private boolean solidUnder(int x, int y, int z) {
            for (int below = 1; below <= SOLID_BELOW; below++) {
                Block block = world.getBlockAt(x, y - below, z);
                if (y - below < world.getMinHeight() || !block.getType().isSolid() || blocks.isRemoved(block)) {
                    return false;
                }
            }
            return true;
        }

        private double distanceToSegment(double x, double y, double z) {
            Vector point = new Vector(x, y, z);
            Vector line = to.clone().subtract(from);
            double length = line.lengthSquared();
            double t = length < 1.0E-9 ? 0 : Math.clamp(point.clone().subtract(from).dot(line) / length, 0.0, 1.0);
            return point.distance(from.clone().add(line.multiply(t)));
        }

        // -------------------------------------------------------------- groups

        private void accept(Candidate candidate) {
            Block root = candidate.block();
            if (taken.contains(Pos.of(root)) || root.isEmpty() || blocks.isRemoved(root)) {
                return;
            }
            if (candidate.ground() && !aboveGone(root)) {
                return; // the ground is only ever dug from the top
            }
            List<Block> group = group(root);
            if (group == null || group.size() > budget) {
                return;
            }
            for (Block block : group) {
                taken.add(Pos.of(block));
            }
            budget -= group.size();
            Group accepted = new Group(candidate, group);
            groups.add(accepted);
            if (candidate.ground()) {
                dug.computeIfAbsent(columnKey(root.getX(), root.getZ()), key -> new ArrayList<>()).add(accepted);
            }
        }

        /** True if the ground block above {@code block} (in the ground layer) is gone or going. */
        private boolean aboveGone(Block block) {
            Column column = column(world, block.getX(), block.getZ(), nearY);
            if (block.getY() >= column.surface()) {
                return true;
            }
            Block above = block.getRelative(BlockFace.UP);
            return taken.contains(Pos.of(above)) || blocks.isRemoved(above);
        }

        /**
         * The block and everything attached to it (root first), or null if any of them may not break or the group
         * would leave something it holds up (a falling block, a temporary block, a painting) hanging.
         */
        private List<Block> group(Block root) {
            List<Block> group = new ArrayList<>();
            Set<Pos> members = new HashSet<>();
            Deque<Block> queue = new ArrayDeque<>();
            queue.add(root);
            members.add(Pos.of(root));
            while (!queue.isEmpty()) {
                Block block = queue.poll();
                if (!breakable(block, block == root)) {
                    return null;
                }
                group.add(block);
                if (group.size() > MAX_GROUP) {
                    return null;
                }
                BlockData data = block.getBlockData();
                for (BlockFace face : TerrainRules.FACES) {
                    Block next = block.getRelative(face);
                    Pos pos = Pos.of(next);
                    if (members.contains(pos) || taken.contains(pos) || outsideHeight(next)
                            || blocks.isRemoved(next)) {
                        continue;
                    }
                    if (TerrainRules.isAttached(next.getBlockData(), face, data)) {
                        members.add(pos);
                        queue.add(next);
                    }
                }
            }
            for (Block block : group) {
                if (!neighboursAllow(block, members) || holdsHanging(block)) {
                    return null;
                }
            }
            return group;
        }

        private boolean breakable(Block block, boolean root) {
            if (outsideHeight(block) || block.isEmpty() || blocks.isTemporary(block)) {
                return false;
            }
            BlockData data = block.getBlockData();
            Material material = data.getMaterial();
            if (TerrainRules.isWet(data) || TerrainRules.isUnbreakable(material)
                    || !inside.test(block.getLocation().add(0.5, 0.5, 0.5))
                    || TerrainRules.hasBlockEntity(block)) {
                return false;
            }
            if (!root) {
                return true;
            }
            // Over the void (nothing solid below in its column) nothing breaks.
            Column column = column(world, block.getX(), block.getZ(), nearY);
            return column.floor() < block.getY();
        }

        /**
         * The neighbours that stay must not need the block: no fluid may spill into its spot, no falling block or
         * temporary block may lose its support, and no dripstone may snap.
         */
        private boolean neighboursAllow(Block block, Set<Pos> members) {
            for (BlockFace face : TerrainRules.FACES) {
                Block next = block.getRelative(face);
                Pos pos = Pos.of(next);
                if (members.contains(pos) || taken.contains(pos) || outsideHeight(next) || blocks.isRemoved(next)) {
                    continue;
                }
                BlockData data = next.getBlockData();
                Material material = data.getMaterial();
                if (TerrainRules.isWet(data) || material == Material.POINTED_DRIPSTONE) {
                    return false;
                }
                if (face == BlockFace.UP && (TerrainRules.hasGravity(material) || blocks.isTemporary(next))) {
                    return false;
                }
            }
            return true;
        }

        private boolean outsideHeight(Block block) {
            return block.getY() < world.getMinHeight() || block.getY() >= world.getMaxHeight();
        }

        // -------------------------------------------------------------- paintings and item frames

        private void collectHanging(BoundingBox box) {
            Collection<Entity> hanging = world.getNearbyEntities(box.clone().expand(2), e -> e instanceof Hanging);
            for (Entity entity : hanging) {
                Hanging frame = (Hanging) entity;
                BoundingBox body = frame.getBoundingBox();
                hangingHolds.add(body);
                BlockFace face = frame.getAttachedFace();
                hangingHolds.add(body.clone().shift(face.getModX() * 0.5, face.getModY() * 0.5,
                        face.getModZ() * 0.5));
            }
        }

        /** True if a painting, an item frame or a leash knot hangs on the block. */
        private boolean holdsHanging(Block block) {
            if (hangingHolds.isEmpty()) {
                return false;
            }
            BoundingBox cube = new BoundingBox(block.getX(), block.getY(), block.getZ(), block.getX() + 1,
                    block.getY() + 1, block.getZ() + 1);
            for (BoundingBox hold : hangingHolds) {
                if (hold.overlaps(cube)) {
                    return true;
                }
            }
            return false;
        }

        // -------------------------------------------------------------- no stranded pits

        /**
         * Digs less where a column would become a pit. Every dug column near the blast (dug now or by an earlier
         * blast) must lead, one block up at a time (falling is fine), to a column that is not dug. Where one does not,
         * the deepest ground block this blast took from it (or, for an older crater, from its neighbours) stays, until
         * every crater can be climbed out of again.
         */
        private void keepEscapable() {
            if (dug.isEmpty()) {
                return; // only new ground blocks lower the floor; broken structures only open the way
            }
            while (true) {
                Map<Long, Integer> heights = new HashMap<>();
                Set<Long> region = dugColumnsNear();
                Set<Long> safe = new HashSet<>();
                boolean grew = true;
                while (grew) {
                    grew = false;
                    for (Long key : region) {
                        if (!safe.contains(key) && leadsOut(key, region, safe, heights)) {
                            safe.add(key);
                            grew = true;
                        }
                    }
                }
                boolean dropped = false;
                for (Long key : region) {
                    if (safe.contains(key)) {
                        continue;
                    }
                    if (dug.containsKey(key)) {
                        dropped |= dropDeepest(key);
                        continue;
                    }
                    for (BlockFace side : SIDES) {
                        dropped |= dropDeepest(columnKey(keyX(key) + side.getModX(), keyZ(key) + side.getModZ()));
                    }
                }
                if (!dropped) {
                    return; // every crater is fine (or only older craters are stuck and nothing new can change that)
                }
            }
        }

        /** True if the dug column {@code key} steps up (at most one block) onto a safe or undug neighbour. */
        private boolean leadsOut(long key, Set<Long> region, Set<Long> safe, Map<Long, Integer> heights) {
            int x = keyX(key);
            int z = keyZ(key);
            int height = height(heights, x, z);
            for (BlockFace side : SIDES) {
                long next = columnKey(x + side.getModX(), z + side.getModZ());
                int nextHeight = height(heights, x + side.getModX(), z + side.getModZ());
                if (nextHeight != NONE && nextHeight <= height + 1 && (!region.contains(next) || safe.contains(next))) {
                    return true;
                }
            }
            return false;
        }

        /**
         * The columns around the blast whose ground is (or would be) dug: the ones this blast digs and older craters
         * within {@link #ESCAPE_MARGIN} columns of it. Only columns touched before can hold an older crater.
         */
        private Set<Long> dugColumnsNear() {
            Set<Long> region = new HashSet<>(dug.keySet());
            BoundingBox box = BoundingBox.of(from, to).expand(radius + ESCAPE_MARGIN, 0, radius + ESCAPE_MARGIN);
            UUID worldId = world.getUID();
            for (int x = (int) Math.floor(box.getMinX()); x <= (int) Math.floor(box.getMaxX()); x++) {
                for (int z = (int) Math.floor(box.getMinZ()); z <= (int) Math.floor(box.getMaxZ()); z++) {
                    Column column = columns.get(new ColumnKey(worldId, x, z));
                    if (column != null && column.hasSurface() && isDug(x, z, column.surface())) {
                        region.add(columnKey(x, z));
                    }
                }
            }
            return region;
        }

        /** True if a ground block of the column's top layer is gone (removed before or taken now). */
        private boolean isDug(int x, int z, int surface) {
            for (int y = surface; y > surface - MAX_DEPTH; y--) {
                if (taken.contains(new Pos(x, y, z)) || blocks.isRemoved(world.getBlockAt(x, y, z))) {
                    return true;
                }
            }
            return false;
        }

        /** Keeps the deepest ground block this blast was going to take from column {@code key}; false if none. */
        private boolean dropDeepest(long key) {
            List<Group> dugHere = dug.get(key);
            if (dugHere == null || dugHere.isEmpty()) {
                return false;
            }
            drop(dugHere.removeLast());
            if (dugHere.isEmpty()) {
                dug.remove(key);
            }
            return true;
        }

        private void drop(Group group) {
            groups.remove(group);
            for (Block block : group.blocks()) {
                taken.remove(Pos.of(block));
            }
            budget += group.blocks().size();
        }

        /**
         * Where a player stands in column (x, z) after the blast: on the remaining ground, or on top of what stands on
         * it (up to {@link #CLIMB} blocks over the surface; higher is a wall, {@link Integer#MAX_VALUE}).
         * {@link #NONE} for a column without ground.
         */
        private int height(Map<Long, Integer> cache, int x, int z) {
            return cache.computeIfAbsent(columnKey(x, z), key -> {
                Column column = column(world, x, z, nearY);
                if (!column.hasSurface()) {
                    return NONE;
                }
                int surface = column.surface();
                int y = surface;
                while (y > surface - MAX_DEPTH && !solidAfter(x, y, z)) {
                    y--;
                }
                while (solidAfter(x, y + 1, z)) {
                    y++;
                    if (y > surface + CLIMB) {
                        return Integer.MAX_VALUE;
                    }
                }
                return y;
            });
        }

        private boolean solidAfter(int x, int y, int z) {
            if (y < world.getMinHeight() || y >= world.getMaxHeight() || taken.contains(new Pos(x, y, z))) {
                return false;
            }
            return world.getBlockAt(x, y, z).getType().isSolid();
        }

        // -------------------------------------------------------------- apply

        private List<Broken> apply() {
            List<Ranked> out = new ArrayList<>();
            for (Group group : groups) {
                List<Ranked> removed = new ArrayList<>(group.blocks().size());
                for (Block block : group.blocks()) {
                    removed.add(new Ranked(new Broken(block.getLocation(), block.getBlockData()),
                            group.root().distance()));
                }
                if (blocks.remove(group.blocks())) {
                    out.addAll(removed);
                }
            }
            out.sort(Comparator.comparingDouble(Ranked::distance));
            List<Broken> result = new ArrayList<>(out.size());
            for (Ranked ranked : out) {
                result.add(ranked.broken());
            }
            return result;
        }
    }

    private record Ranked(Broken broken, double distance) {
    }

    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};

    private static long columnKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static int keyX(long key) {
        return (int) (key >> 32);
    }

    private static int keyZ(long key) {
        return (int) key;
    }
}
