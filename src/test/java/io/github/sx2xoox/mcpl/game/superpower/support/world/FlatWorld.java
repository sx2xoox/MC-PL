package io.github.sx2xoox.mcpl.game.superpower.support.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

import io.papermc.paper.math.Position;
import org.bukkit.Difficulty;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LightningStrike;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.block.BlockMock;
import org.mockbukkit.mockbukkit.entity.LightningStrikeMock;
import org.mockbukkit.mockbukkit.world.Coordinate;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * A flat test arena: bedrock at y=0, stone up to {@link #FLOOR_Y} and air above, so entities stand at
 * {@link #SURFACE} (y=64). It answers the world queries MockBukkit leaves unimplemented, consistently with the
 * {@link FlatBlock} shape model:
 *
 * <ul>
 *     <li>{@link #rayTraceBlocks}, {@link #rayTraceEntities} and {@link #rayTrace} (see {@link RayTracing});</li>
 *     <li>{@link #hasCollisionsIn}: true if the box overlaps (with positive volume) a solid block;</li>
 *     <li>{@link #strikeLightningEffect}: no-op, recorded in {@link #lightningEffects()};</li>
 *     <li>entity spawning hands out the {@link MockEntities} doubles (displays with billboards, mobs that can be
 *     damaged with a {@code DamageSource}, mannequins, coloured arrows).</li>
 * </ul>
 * The difficulty is {@link Difficulty#EASY}, Paper's default {@code server.properties} value. Movement, gravity,
 * projectiles and fall damage are simulated separately by {@link WorldPhysics} (see {@link #physics()}).
 */
public final class FlatWorld extends WorldMock {

    /** Highest stone layer. */
    public static final int FLOOR_Y = 63;
    /** The y coordinate entities stand on. */
    public static final double SURFACE = FLOOR_Y + 1;

    private final Map<Coordinate, BlockMock> blocks = new HashMap<>();
    private final List<Location> lightningEffects = new ArrayList<>();
    private final WorldPhysics physics = new WorldPhysics(this);

    public FlatWorld(String name) {
        super(Material.STONE, 0, 256, FLOOR_Y);
        setName(name);
        setSpawnLocation(new Location(this, 0.5, SURFACE, 0.5));
        setDifficulty(Difficulty.EASY);
    }

    public WorldPhysics physics() {
        return physics;
    }

    /** Locations of every {@link #strikeLightningEffect} so far. */
    public List<Location> lightningEffects() {
        return lightningEffects;
    }

    /** Sets a block (e.g. a wall for a test) without any events. */
    public void setBlock(int x, int y, int z, Material type) {
        getBlockAt(x, y, z).setType(type);
    }

    /** Builds a wall of {@code type} along x from {@code x1} to {@code x2} at {@code z}, {@code height} blocks high. */
    public void wallAlongX(int x1, int x2, int z, int height, Material type) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int y = 0; y < height; y++) {
                setBlock(x, FLOOR_Y + 1 + y, z, type);
            }
        }
    }

    /** True if any block overlapping the entity's box is of {@code type}. */
    public static boolean isIn(Entity entity, Material type) {
        BoundingBox box = entity.getBoundingBox();
        for (int x = (int) Math.floor(box.getMinX()); x <= (int) Math.floor(box.getMaxX()); x++) {
            for (int y = (int) Math.floor(box.getMinY()); y <= (int) Math.floor(box.getMaxY()); y++) {
                for (int z = (int) Math.floor(box.getMinZ()); z <= (int) Math.floor(box.getMaxZ()); z++) {
                    if (y >= entity.getWorld().getMinHeight() && y < entity.getWorld().getMaxHeight()
                            && entity.getWorld().getBlockAt(x, y, z).getType() == type) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ blocks

    @Override
    public BlockMock getBlockAt(Coordinate coordinate) {
        BlockMock block = blocks.get(coordinate);
        return block != null ? block : createBlock(coordinate);
    }

    @Override
    public BlockMock createBlock(Coordinate coordinate) {
        if (coordinate.y >= getMaxHeight()) {
            throw new ArrayIndexOutOfBoundsException("Y larger than max height");
        }
        if (coordinate.y < getMinHeight()) {
            throw new ArrayIndexOutOfBoundsException("Y smaller than min height");
        }
        Material type = coordinate.y == getMinHeight() ? Material.BEDROCK
                : coordinate.y <= FLOOR_Y ? Material.STONE : Material.AIR;
        FlatBlock block = new FlatBlock(type, new Location(this, coordinate.x, coordinate.y, coordinate.z));
        blocks.put(coordinate, block);
        return block;
    }

    @Override
    public boolean hasCollisionsIn(BoundingBox box) {
        int minY = Math.max(getMinHeight(), (int) Math.floor(box.getMinY()));
        int maxY = Math.min(getMaxHeight() - 1, (int) Math.floor(box.getMaxY()));
        for (int x = (int) Math.floor(box.getMinX()); x <= (int) Math.floor(box.getMaxX()); x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = (int) Math.floor(box.getMinZ()); z <= (int) Math.floor(box.getMaxZ()); z++) {
                    if (FlatBlock.collides(getBlockAt(x, y, z).getType())
                            && box.overlaps(new BoundingBox(x, y, z, x + 1, y + 1, z + 1))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ ray tracing

    @Override
    public @Nullable RayTraceResult rayTraceBlocks(Location start, Vector direction, double maxDistance) {
        return rayTraceBlocks(start, direction, maxDistance, FluidCollisionMode.NEVER, false);
    }

    @Override
    public @Nullable RayTraceResult rayTraceBlocks(Location start, Vector direction, double maxDistance,
                                                   FluidCollisionMode fluidCollisionMode) {
        return rayTraceBlocks(start, direction, maxDistance, fluidCollisionMode, false);
    }

    @Override
    public @Nullable RayTraceResult rayTraceBlocks(Location start, Vector direction, double maxDistance,
                                                   FluidCollisionMode fluidCollisionMode,
                                                   boolean ignorePassableBlocks) {
        return rayTraceBlocks(start, direction, maxDistance, fluidCollisionMode, ignorePassableBlocks, null);
    }

    @Override
    public @Nullable RayTraceResult rayTraceBlocks(Position start, Vector direction, double maxDistance,
                                                   FluidCollisionMode fluidCollisionMode,
                                                   boolean ignorePassableBlocks,
                                                   @Nullable Predicate<? super Block> canCollide) {
        checkWorld(start);
        return RayTracing.blocks(this, toVector(start), direction, maxDistance, fluidCollisionMode,
                ignorePassableBlocks, canCollide);
    }

    @Override
    public @Nullable RayTraceResult rayTraceEntities(Location start, Vector direction, double maxDistance) {
        return rayTraceEntities(start, direction, maxDistance, 0.0, null);
    }

    @Override
    public @Nullable RayTraceResult rayTraceEntities(Location start, Vector direction, double maxDistance,
                                                     double raySize) {
        return rayTraceEntities(start, direction, maxDistance, raySize, null);
    }

    @Override
    public @Nullable RayTraceResult rayTraceEntities(Location start, Vector direction, double maxDistance,
                                                     @Nullable Predicate<? super Entity> filter) {
        return rayTraceEntities(start, direction, maxDistance, 0.0, filter);
    }

    @Override
    public @Nullable RayTraceResult rayTraceEntities(Location start, Vector direction, double maxDistance,
                                                     double raySize, @Nullable Predicate<? super Entity> filter) {
        return rayTraceEntities((Position) start, direction, maxDistance, raySize, filter);
    }

    @Override
    public @Nullable RayTraceResult rayTraceEntities(Position start, Vector direction, double maxDistance,
                                                     double raySize, @Nullable Predicate<? super Entity> filter) {
        checkWorld(start);
        return RayTracing.entities(this, toVector(start), direction, maxDistance, raySize, filter);
    }

    @Override
    public @Nullable RayTraceResult rayTrace(Location start, Vector direction, double maxDistance,
                                             FluidCollisionMode fluidCollisionMode, boolean ignorePassableBlocks,
                                             double raySize, @Nullable Predicate<? super Entity> filter) {
        return rayTrace(start, direction, maxDistance, fluidCollisionMode, ignorePassableBlocks, raySize, filter,
                null);
    }

    @Override
    public @Nullable RayTraceResult rayTrace(Position start, Vector direction, double maxDistance,
                                             FluidCollisionMode fluidCollisionMode, boolean ignorePassableBlocks,
                                             double raySize, @Nullable Predicate<? super Entity> filter,
                                             @Nullable Predicate<? super Block> canCollide) {
        checkWorld(start);
        return RayTracing.combined(this, toVector(start), direction, maxDistance, fluidCollisionMode,
                ignorePassableBlocks, raySize, filter, canCollide);
    }

    private void checkWorld(Position start) {
        if (start instanceof Location location && location.getWorld() != null && !equals(location.getWorld())) {
            throw new IllegalArgumentException("Location start cannot be in a different world");
        }
    }

    private static Vector toVector(Position position) {
        return new Vector(position.x(), position.y(), position.z());
    }

    // ------------------------------------------------------------------ entities

    @Override
    public LightningStrike strikeLightningEffect(Location location) {
        lightningEffects.add(location.clone());
        // A purely visual bolt: nothing is added to the world, so it can neither hurt nor ignite anything.
        LightningStrikeMock bolt = new LightningStrikeMock(MockBukkit.getMock(), UUID.randomUUID());
        bolt.setLocation(location.clone());
        return bolt;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T extends Entity> T spawn(Location location, Class<T> clazz, @Nullable Consumer<? super T> function,
                                      CreatureSpawnEvent.SpawnReason reason, boolean randomizeData,
                                      boolean callSpawnEvent) {
        Class substitute = MockEntities.SUBSTITUTES.get(clazz);
        if (substitute == null) {
            return super.spawn(location, clazz, function, reason, randomizeData, callSpawnEvent);
        }
        return (T) super.spawn(location, substitute, (Consumer) function, reason, randomizeData, callSpawnEvent);
    }

    /** Like Paper, refuses particles with missing or mistyped data (MockBukkit accepts anything). */
    @Override
    public <T> void spawnParticle(org.bukkit.Particle particle, @Nullable List<org.bukkit.entity.Player> receivers,
                                 org.bukkit.entity.@Nullable Player source, double x, double y, double z, int count,
                                 double offsetX, double offsetY, double offsetZ, double speedX, double speedY,
                                 double speedZ, @Nullable T data, boolean force,
                                 org.bukkit.Particle.RandomizationType randomizationType) {
        PaperRules.checkParticle(particle, data);
        super.spawnParticle(particle, receivers, source, x, y, z, count, offsetX, offsetY, offsetZ, speedX, speedY,
                speedZ, data, force, randomizationType);
    }
}
