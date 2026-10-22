package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Motion;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 대지술사 — the ground itself fights for the owner. Hard to knock around, sends an earthquake wave of erupting
 * ground forward that launches enemies, and as the ultimate locks every nearby enemy inside a stone cage.
 */
public final class EarthAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("earth", "대지술사", Material.DRIPSTONE_BLOCK)
            .color(TextColor.color(0xB0703C))
            .difficulty(1)
            .passive("반석",
                    "넉백 저항이 {knockback_resist}% 증가합니다.")
            .active("지진",
                    "바라보는 방향 {quake_range}블록 부채꼴({quake_angle}°)로 땅을 솟구치게 합니다. "
                            + "휩쓸린 적은 {quake_damage} 피해를 입고 {quake_launch}블록 떠오릅니다.",
                    10)
            .ultimate("바위 감옥",
                    "반경 {ult_radius}블록의 적을 각각 {ult_duration}초간 바위 감옥에 가둬 움직이지 못하게 합니다. "
                            + "감옥의 창 사이로는 공격할 수 있습니다.")
            .param("knockback_resist", 40)
            .param("quake_range", 8)
            .param("quake_angle", 70)
            .param("quake_damage", 5)
            .param("quake_launch", 1.5)
            .param("ult_radius", 10)
            .param("ult_duration", 3)
            .build();

    private static final String COLOR_TAG = "#B0703C";
    private static final Particle.DustOptions EARTH_DUST = new Particle.DustOptions(Color.fromRGB(0x8A, 0x63, 0x3B), 1.4f);
    private static final BlockData FALLBACK_GROUND = Material.DRIPSTONE_BLOCK.createBlockData();
    private static final BlockData CAGE_WALL = Material.COBBLESTONE.createBlockData();
    private static final BlockData CAGE_WALL_MOSSY = Material.MOSSY_COBBLESTONE.createBlockData();
    private static final BlockData CAGE_PILLAR = Material.DRIPSTONE_BLOCK.createBlockData();
    private static final BlockData CAGE_ROOF = Material.TUFF.createBlockData();

    private static final double MAX_SPEED = 2.5;

    // active: earthquake
    /** Blocks the wave front advances per tick. */
    private static final double WAVE_STEP = 1.0;
    /** Target spacing between neighbouring wave rays at the far edge of the fan. */
    private static final double RAY_SPACING = 1.0;
    private static final int MAX_RAYS = 15;
    /** How far below the owner's feet the wave may start (casting while jumping is fine). */
    private static final int START_DEPTH = 3;
    /** How far a wave ray may step down per block; deeper drops end the ray. */
    private static final int MAX_STEP_DOWN = 3;
    private static final double HIT_RADIUS = 1.1;
    private static final double LAUNCH_PUSH = 0.35;
    private static final int RISE_AT = 1;
    private static final int RISE_TICKS = 3;
    private static final int SINK_AT = 8;
    private static final int SINK_TICKS = 5;
    private static final int REMOVE_AT = 13;
    private static final float BURIED_CENTER = 0.15f;

    // ultimate: stone cage
    private static final int SHOCKWAVE_TICKS = 8;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    // ------------------------------------------------------------------ passive: bedrock stance

    @Override
    public void onGrant() {
        double resist = params().num("knockback_resist") / 100.0;
        if (resist > 0) {
            ctx().addModifier(player(), player(), Attribute.KNOCKBACK_RESISTANCE, Math.min(1.0, resist),
                    AttributeModifier.Operation.ADD_NUMBER, 0);
        }
    }

    // ------------------------------------------------------------------ active: earthquake

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        Location feet = owner.getLocation();
        Block ground = groundUnder(feet);
        if (ground == null) {
            return ActivationResult.fail("발밑에 땅이 없습니다");
        }
        double yaw = Math.toRadians(feet.getYaw());
        Vector forward = new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
        Quake quake = new Quake(feet, ground.getY(), forward);

        Location stomp = feet.clone();
        stomp.setY(ground.getY() + 1.05);
        World world = owner.getWorld();
        world.spawnParticle(Particle.BLOCK, stomp, 24, 0.6, 0.1, 0.6, 0.15, displayData(ground));
        world.spawnParticle(Particle.DUST_PLUME, stomp, 10, 0.5, 0.05, 0.5, 0.02);
        Fx.sound(stomp, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 1.0f, 0.8f + Fx.jitter(0.05f));
        Fx.sound(stomp, Sound.ENTITY_RAVAGER_STEP, 1.2f, 0.6f);
        Loop.repeat(ctx(), player(), 0, 1, quake.totalTicks(), quake);
        return ActivationResult.success();
    }

    /** The first solid block at or below the owner's feet, at most {@link #START_DEPTH} blocks down. */
    private static @Nullable Block groundUnder(Location feet) {
        World world = feet.getWorld();
        int top = Math.min(feet.getBlockY(), world.getMaxHeight() - 1);
        int bottom = Math.max(world.getMinHeight(), feet.getBlockY() - START_DEPTH);
        for (int y = top; y >= bottom; y--) {
            Block block = world.getBlockAt(feet.getBlockX(), y, feet.getBlockZ());
            if (!block.isPassable()) {
                return block;
            }
        }
        return null;
    }

    /** One ground chunk thrown up by the wave. */
    private record Chunk(BlockDisplay display, float size, Quaternionf tilt, float peak) {
    }

    /** A ring of chunks raised by the wave front in the same tick. */
    private record Ring(long born, List<Chunk> chunks) {
    }

    /**
     * The earthquake wave: rays fanned around the facing direction advance one block per tick, following the terrain
     * (one block up, a few blocks down) and stopping at walls. Every ground block they cross pops up as a block
     * display, and enemies standing on it are struck once.
     */
    private final class Quake implements LongPredicate {

        private final World world;
        private final double originX;
        private final double originZ;
        private final Vector[] rays;
        private final int[] rayGround;
        private final boolean[] rayAlive;
        private final int steps;
        private final double damage;
        private final double launch;
        private final Set<Long> raised = new HashSet<>();
        private final Set<UUID> struck = new HashSet<>();
        private final List<Ring> rings = new ArrayList<>();

        Quake(Location feet, int groundY, Vector forward) {
            this.world = feet.getWorld();
            this.originX = feet.getX();
            this.originZ = feet.getZ();
            double range = Math.max(1, params().num("quake_range"));
            double fan = Math.toRadians(Math.clamp(params().num("quake_angle"), 0.0, 360.0));
            this.steps = (int) Math.ceil(range / WAVE_STEP);
            int count = fan <= 0 ? 1 : (int) Math.min(MAX_RAYS, Math.max(3, Math.ceil(fan * range / RAY_SPACING) + 1));
            this.rays = new Vector[count];
            for (int i = 0; i < count; i++) {
                double angle = count == 1 ? 0 : -fan / 2 + fan * i / (count - 1);
                rays[i] = forward.clone().rotateAroundY(angle);
            }
            this.rayGround = new int[count];
            Arrays.fill(rayGround, groundY);
            this.rayAlive = new boolean[count];
            Arrays.fill(rayAlive, true);
            this.damage = params().num("quake_damage");
            this.launch = Motion.launchSpeed(params().num("quake_launch"));
        }

        long totalTicks() {
            return steps + REMOVE_AT + 1L;
        }

        @Override
        public boolean test(long tick) {
            if (tick < steps) {
                advance((tick + 1) * WAVE_STEP, tick);
            }
            animate(tick);
            if (tick >= totalTicks() - 1) {
                rings.forEach(ring -> ring.chunks().forEach(chunk -> chunk.display().remove()));
                rings.clear();
                if (!struck.isEmpty() && player().isOnline()) {
                    player().sendActionBar(Text.mm("<" + COLOR_TAG + ">지진 <white>" + struck.size()
                            + "명 적중!</white></" + COLOR_TAG + ">"));
                }
                return false;
            }
            return true;
        }

        private void advance(double distance, long tick) {
            List<Chunk> chunks = new ArrayList<>();
            double soundX = 0;
            double soundY = 0;
            double soundZ = 0;
            for (int i = 0; i < rays.length; i++) {
                if (!rayAlive[i]) {
                    continue;
                }
                int x = (int) Math.floor(originX + rays[i].getX() * distance);
                int z = (int) Math.floor(originZ + rays[i].getZ() * distance);
                int previous = rayGround[i];
                if (!passable(world, x, previous + 1, z) && !passable(world, x, previous + 2, z)) {
                    rayAlive[i] = false; // a wall at least two blocks high stops this part of the wave
                    continue;
                }
                Block ground = surface(x, z, previous);
                if (ground == null) {
                    rayAlive[i] = false; // a cliff or a hole: the wave cannot follow
                    continue;
                }
                rayGround[i] = ground.getY();
                if (!raised.add(blockKey(x, ground.getY(), z))) {
                    continue;
                }
                chunks.add(raise(ground));
                soundX = x + 0.5;
                soundY = ground.getY() + 1;
                soundZ = z + 0.5;
                strikeAround(new Location(world, x + 0.5, ground.getY() + 1.0, z + 0.5), rays[i]);
            }
            if (!chunks.isEmpty()) {
                rings.add(new Ring(tick, chunks));
                Location at = new Location(world, soundX, soundY, soundZ);
                Fx.sound(at, Sound.BLOCK_DRIPSTONE_BLOCK_BREAK, 0.8f, 0.6f + Fx.jitter(0.1f));
                if (tick % 2 == 0) {
                    Fx.sound(at, Sound.BLOCK_ROOTED_DIRT_BREAK, 0.7f, 0.7f + Fx.jitter(0.1f));
                }
            }
        }

        /** The walkable ground block of column (x, z): at most one step up and {@link #MAX_STEP_DOWN} down. */
        private @Nullable Block surface(int x, int z, int previous) {
            int top = Math.min(previous + 1, world.getMaxHeight() - 2);
            int bottom = Math.max(world.getMinHeight(), previous - MAX_STEP_DOWN);
            for (int y = top; y >= bottom; y--) {
                Block block = world.getBlockAt(x, y, z);
                if (!block.isPassable() && block.getRelative(BlockFace.UP).isPassable()) {
                    return block;
                }
            }
            return null;
        }

        private Chunk raise(Block ground) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            BlockData data = displayData(ground);
            float size = 0.82f + random.nextFloat() * 0.12f;
            Quaternionf tilt = new Quaternionf().rotateXYZ(
                    (random.nextFloat() - 0.5f) * 0.5f,
                    (random.nextFloat() - 0.5f) * 1.2f,
                    (random.nextFloat() - 0.5f) * 0.5f);
            float peak = 1.1f + random.nextFloat() * 0.3f;
            // Spawned in the air block above the ground (the pose lowers the cube back into place): clients light a
            // display from the block at its position, and inside the solid ground block that is pitch black.
            Location above = ground.getLocation().add(0, 1, 0);
            BlockDisplay display = ctx().track(player(), world.spawn(above, BlockDisplay.class, d -> {
                d.setPersistent(false);
                d.setBlock(data);
                d.setTransformation(chunkPose(size, tilt, BURIED_CENTER));
            }));
            Location top = ground.getLocation().add(0.5, 1.02, 0.5);
            world.spawnParticle(Particle.BLOCK, top, 4, 0.3, 0.05, 0.3, 0.1, data);
            return new Chunk(display, size, tilt, peak);
        }

        private void animate(long tick) {
            Iterator<Ring> iterator = rings.iterator();
            while (iterator.hasNext()) {
                Ring ring = iterator.next();
                long age = tick - ring.born();
                if (age == RISE_AT) {
                    ring.chunks().forEach(chunk -> pose(chunk, chunk.peak(), RISE_TICKS));
                } else if (age == SINK_AT) {
                    ring.chunks().forEach(chunk -> pose(chunk, BURIED_CENTER - 0.1f, SINK_TICKS));
                } else if (age >= REMOVE_AT) {
                    ring.chunks().forEach(chunk -> chunk.display().remove());
                    iterator.remove();
                }
            }
        }

        private void pose(Chunk chunk, float center, int duration) {
            BlockDisplay display = chunk.display();
            if (!display.isValid()) {
                return;
            }
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(duration);
            display.setTransformation(chunkPose(chunk.size(), chunk.tilt(), center));
        }

        private void strikeAround(Location point, Vector direction) {
            for (LivingEntity enemy : ctx().enemiesNear(player(), point, HIT_RADIUS)) {
                if (!struck.add(enemy.getUniqueId())) {
                    continue;
                }
                Combat.hit(ctx(), player(), enemy, damage);
                if (!Combat.isTargetable(ctx(), player(), enemy)) {
                    continue;
                }
                Motion.push(enemy, direction.clone().multiply(LAUNCH_PUSH).setY(launch), MAX_SPEED);
                Location body = enemy.getLocation().add(0, enemy.getHeight() * 0.5, 0);
                world.spawnParticle(Particle.CRIT, body, 10, 0.3, 0.4, 0.3, 0.2);
                world.spawnParticle(Particle.BLOCK, enemy.getLocation(), 12, 0.4, 0.2, 0.4, 0.2, FALLBACK_GROUND);
                Fx.sound(body, Sound.ENTITY_IRON_GOLEM_ATTACK, 0.9f, 0.8f + Fx.jitter(0.1f));
            }
        }
    }

    /**
     * A cube of edge {@code size} whose centre sits at (0.5, {@code centerY}, 0.5) of its ground block, rotated by
     * {@code tilt} around that centre. The display stands one block higher, in the air above the ground, and the
     * block model spans 0..1, so the translation moves the rotated centre back down into place.
     */
    private static Transformation chunkPose(float size, Quaternionf tilt, float centerY) {
        Vector3f half = new Vector3f(size / 2, size / 2, size / 2).rotate(tilt);
        Vector3f translation = new Vector3f(0.5f, centerY - 1f, 0.5f).sub(half);
        return new Transformation(translation, new Quaternionf(tilt), new Vector3f(size, size, size), new Quaternionf());
    }

    /** The block shown for an erupting ground block: the block itself when it is a plain full cube. */
    private static BlockData displayData(Block ground) {
        return ground.getType().isOccluding() ? ground.getBlockData() : FALLBACK_GROUND;
    }

    // ------------------------------------------------------------------ ultimate: stone cage

    @Override
    public ActivationResult onUltimate() {
        double radius = params().num("ult_radius");
        Location origin = player().getLocation();
        List<LivingEntity> targets = ctx().enemiesNear(player(), origin, radius);
        if (targets.isEmpty()) {
            return ActivationResult.fail("반경 " + AbilityInfo.formatNumber(radius) + "블록 안에 적이 없습니다");
        }
        long duration = Math.max(1, params().ticks("ult_duration"));

        Fx.sound(origin, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 1.4f, 0.6f);
        Fx.sound(origin, Sound.ENTITY_WARDEN_EMERGE, 0.9f, 1.4f);
        Loop.repeat(ctx(), player(), 0, 1, SHOCKWAVE_TICKS, tick -> {
            drawShockwave(origin, radius * (tick + 1) / SHOCKWAVE_TICKS);
            return true;
        });

        List<Location> cages = new ArrayList<>();
        for (LivingEntity target : List.copyOf(targets)) {
            cages.add(imprison(target, duration));
        }
        player().sendActionBar(Text.mm("<" + COLOR_TAG + ">⛰ " + cages.size() + "명 감금!</" + COLOR_TAG + ">"));
        ctx().runLater(player(), duration, () -> cages.forEach(EarthAbility::crumble));
        return ActivationResult.success();
    }

    /** Roots {@code target} and builds a cage of temporary blocks around it. Returns the cage centre. */
    private Location imprison(LivingEntity target, long ticks) {
        centerInBlock(target);
        target.setVelocity(new Vector());
        ctx().addModifier(player(), target, Attribute.MOVEMENT_SPEED, -1.0,
                AttributeModifier.Operation.MULTIPLY_SCALAR_1, ticks);
        ctx().addModifier(player(), target, Attribute.JUMP_STRENGTH, -1.0,
                AttributeModifier.Operation.MULTIPLY_SCALAR_1, ticks);
        buildCage(target, ticks);

        Location body = target.getLocation().add(0, target.getHeight() * 0.5, 0);
        World world = target.getWorld();
        world.spawnParticle(Particle.DUST_PILLAR, target.getLocation(), 24, 0.8, 0.1, 0.8, 0.1, CAGE_PILLAR);
        world.spawnParticle(Particle.BLOCK, body, 20, 0.7, 0.6, 0.7, 0.1, CAGE_WALL);
        Fx.sound(body, Sound.BLOCK_DRIPSTONE_BLOCK_PLACE, 1.2f, 0.6f + Fx.jitter(0.05f));
        Fx.sound(body, Sound.BLOCK_POINTED_DRIPSTONE_LAND, 1.0f, 0.8f + Fx.jitter(0.1f));
        return body;
    }

    /**
     * Builds the cage from temporary blocks: a closed ring at foot level, corner pillars up to head height (the gaps
     * between them are windows to attack through), a roof, and a floor when the target is airborne. Cells that are
     * not replaceable or that overlap any living entity are skipped, so nobody can suffocate.
     */
    private void buildCage(LivingEntity target, long ticks) {
        World world = target.getWorld();
        BoundingBox box = target.getBoundingBox();
        int bx = (int) Math.floor(box.getCenterX());
        int bz = (int) Math.floor(box.getCenterZ());
        int bottom = (int) Math.floor(box.getMinY());
        int top = (int) Math.floor(box.getMaxY() - 1.0E-3);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int y = bottom; y <= top; y++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    boolean inside = dx == 0 && dz == 0;
                    boolean corner = dx != 0 && dz != 0;
                    boolean window = y != bottom && !corner;
                    if (inside || window) {
                        continue;
                    }
                    BlockData data = corner ? CAGE_PILLAR : random.nextFloat() < 0.3f ? CAGE_WALL_MOSSY : CAGE_WALL;
                    placeCell(world.getBlockAt(bx + dx, y, bz + dz), data, ticks);
                }
            }
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                placeCell(world.getBlockAt(bx + dx, top + 1, bz + dz), CAGE_ROOF, ticks);
            }
        }
        placeCell(world.getBlockAt(bx, bottom - 1, bz), CAGE_WALL, ticks);
    }

    private void placeCell(Block block, BlockData data, long ticks) {
        int y = block.getY();
        if (y < block.getWorld().getMinHeight() || y >= block.getWorld().getMaxHeight() || isOccupied(block)) {
            return;
        }
        ctx().placeTempBlock(block, data, ticks);
    }

    private static boolean isOccupied(Block block) {
        BoundingBox cell = BoundingBox.of(block);
        return !block.getWorld().getNearbyEntities(cell, entity -> entity instanceof LivingEntity).isEmpty();
    }

    /**
     * Moves the target to the middle of its block column when that space is free (and inside the arena), so the cage
     * fits around it.
     */
    private void centerInBlock(LivingEntity target) {
        Location location = target.getLocation();
        double dx = location.getBlockX() + 0.5 - location.getX();
        double dz = location.getBlockZ() + 0.5 - location.getZ();
        if (Math.abs(dx) < 0.05 && Math.abs(dz) < 0.05) {
            return;
        }
        BoundingBox moved = target.getBoundingBox().shift(dx, 0, dz).expand(-0.01);
        Location centered = location.clone().add(dx, 0, dz);
        if (target.getWorld().hasCollisionsIn(moved) || !ctx().isInsideArena(centered)) {
            return;
        }
        target.teleport(centered);
    }

    private static void crumble(Location center) {
        World world = center.getWorld();
        world.spawnParticle(Particle.BLOCK, center, 40, 0.8, 0.8, 0.8, 0.15, CAGE_PILLAR);
        world.spawnParticle(Particle.BLOCK, center, 20, 0.8, 0.8, 0.8, 0.15, CAGE_WALL);
        world.spawnParticle(Particle.DUST_PLUME, center, 8, 0.6, 0.6, 0.6, 0.02);
        Fx.sound(center, Sound.BLOCK_DRIPSTONE_BLOCK_BREAK, 1.2f, 0.7f + Fx.jitter(0.05f));
        Fx.sound(center, Sound.BLOCK_STONE_BREAK, 1.0f, 0.6f + Fx.jitter(0.05f));
    }

    private void drawShockwave(Location origin, double radius) {
        World world = origin.getWorld();
        int points = (int) Math.max(12, Math.min(48, radius * 5));
        double y = origin.getY() + 0.2;
        for (int i = 0; i < points; i++) {
            double angle = i * (Math.PI * 2 / points);
            double x = origin.getX() + Math.cos(angle) * radius;
            double z = origin.getZ() + Math.sin(angle) * radius;
            if (i % 3 == 0) {
                world.spawnParticle(Particle.BLOCK, x, y, z, 1, 0.1, 0.05, 0.1, 0, FALLBACK_GROUND);
            } else {
                world.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0.05, 0, 0, EARTH_DUST);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static boolean passable(World world, int x, int y, int z) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
            return true;
        }
        return world.getBlockAt(x, y, z).isPassable();
    }

    private static long blockKey(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | (long) y & 0xFFFL;
    }
}
