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
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Motion;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.format.TextColor;
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
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
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
                            + "휩쓸린 적은 {quake_damage} 피해를 입고 {quake_launch}블록 떠오릅니다. 전투 맵에서는 땅이 "
                            + "솟구치다 부딪힌 벽이 반경 {quake_block_radius}블록 무너집니다.",
                    10)
            .ultimate("바위 감옥",
                    "반경 {ult_radius}블록의 적을 각각 {ult_duration}초간 바위 감옥에 가둬 움직이지 못하게 합니다. "
                            + "감옥의 창 사이로는 공격할 수 있습니다.")
            .param("knockback_resist", 40)
            .param("quake_range", 8)
            .param("quake_angle", 70)
            .param("quake_damage", 5)
            .param("quake_launch", 1.5)
            .param("quake_block_radius", 1.5)
            .param("ult_radius", 10)
            .param("ult_duration", 3)
            .build();

    private static final String COLOR_TAG = "#B0703C";
    /** Sand highlight, clay body, dark loam. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xD7B98E, 0xB0703C, 0x4E342E);
    private static final Particle.DustOptions SOIL = PALETTE.dust(1.3f);
    private static final Particle.DustTransition SETTLE = PALETTE.fade(1.1f);
    private static final Particle.DustTransition SAND = PALETTE.glow(1.0f);
    private static final BlockData FALLBACK_GROUND = Material.DRIPSTONE_BLOCK.createBlockData();
    /** An upward pointed dripstone tip (the block's default state): the spikes of the quake and the cage. */
    private static final BlockData SPIKE = Material.POINTED_DRIPSTONE.createBlockData();
    private static final BlockData CAGE_WALL = Material.COBBLESTONE.createBlockData();
    private static final BlockData CAGE_WALL_MOSSY = Material.MOSSY_COBBLESTONE.createBlockData();
    private static final BlockData CAGE_PILLAR = Material.DRIPSTONE_BLOCK.createBlockData();
    private static final BlockData CAGE_ROOF = Material.TUFF.createBlockData();
    private static final BlockData[] RUBBLE = {CAGE_WALL, CAGE_WALL_MOSSY, CAGE_PILLAR, CAGE_ROOF};

    private static final double MAX_SPEED = 2.5;

    // passive: bedrock stance
    private static final long STANCE_FX_COOLDOWN = 10;

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
    /** Spikes only erupt from this many blocks out, at most this many per quake, on this share of the cells. */
    private static final int SPIKE_FROM_STEP = 3;
    private static final int MAX_QUAKE_SPIKES = 8;
    /** Walls the wave runs into crumble at points at least this far apart (one hole per stretch of wall). */
    private static final double WALL_BREAK_SPACING = 2.0;
    private static final float SPIKE_CHANCE = 0.3f;

    // ultimate: stone cage
    private static final int SHOCKWAVE_TICKS = 8;
    /** Cages that get the four erupting corner spikes (the rest only get particles), to cap display entities. */
    private static final int MAX_SPIKED_CAGES = 5;
    /**
     * Distance of a corner spike's foot from the cage's centre, along the diagonal: just outside the cage's 3x3
     * footprint, so the spike's display never stands inside a corner pillar (clients light a display from the block
     * at its position, and inside a solid pillar it renders black).
     */
    private static final double CORNER_SPIKE_DISTANCE = 2.35;
    /** A corner spike's display stands this far above its foot, safely inside the air above the ground. */
    private static final float SPIKE_ANCHOR_LIFT = 0.05f;
    private static final int RUBBLE_PER_CAGE = 3;
    private static final int MAX_RUBBLE = 12;
    private static final long TRICKLE_PERIOD = 6;

    private long nextStanceFx;

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

    /** Feedback for the stance: struck by someone, the owner's feet dig into the ground in a puff of soil. */
    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        long now = ctx().currentTick();
        if (event.isCancelled() || !(event instanceof EntityDamageByEntityEvent) || now < nextStanceFx) {
            return;
        }
        nextStanceFx = now + STANCE_FX_COOLDOWN;
        Location feet = player().getLocation().add(0, 0.05, 0);
        Block below = feet.clone().subtract(0, 0.3, 0).getBlock();
        BlockData ground = below.isPassable() ? FALLBACK_GROUND : displayData(below);
        Vfx.ring(ctx(), feet, 0.55, 10, ThreadLocalRandom.current().nextDouble(Math.PI * 2), Particle.DUST, SOIL,
                false);
        feet.getWorld().spawnParticle(Particle.BLOCK, feet, Vfx.n(ctx(), 6), 0.3, 0.02, 0.3, 0.05, ground);
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

        // The stomp: a burst of the ground under the owner and the fan outlined in front, so the wave reads at once.
        Location stomp = feet.clone();
        stomp.setY(ground.getY() + 1.05);
        World world = owner.getWorld();
        BlockData data = displayData(ground);
        world.spawnParticle(Particle.BLOCK, stomp, Vfx.n(ctx(), 24), 0.6, 0.1, 0.6, 0.15, data);
        world.spawnParticle(Particle.DUST_PLUME, stomp, Vfx.n(ctx(), 10), 0.5, 0.05, 0.5, 0.02);
        Vfx.ring(ctx(), stomp, 0.9, 14, 0, Particle.DUST_COLOR_TRANSITION, SETTLE, false);
        double fan = Math.clamp(params().num("quake_angle"), 10.0, 360.0);
        Vfx.arc(ctx(), stomp, 1.4, feet.getYaw(), fan, 9, Particle.DUST, SOIL, false);
        Vfx.arc(ctx(), stomp, 2.3, feet.getYaw(), fan, 13, Particle.DUST_COLOR_TRANSITION, SAND, false);
        Fx.sound(stomp, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 1.0f, 0.8f + Fx.jitter(0.05f));
        Fx.sound(stomp, Sound.ENTITY_RAVAGER_STEP, 1.2f, 0.6f + Fx.jitter(0.05f));
        Fx.sound(stomp, Sound.BLOCK_ROOTED_DIRT_BREAK, 1.0f, 0.5f);
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

    /**
     * One rock thrown up by the wave: a tilted chunk of the ground block, or a pointed dripstone spike. Its poses are
     * in the frame of the air block above the ground (see {@link #chunkPose} and {@link #spikePose}).
     */
    private record Chunk(BlockDisplay display, float size, Quaternionf tilt, float peak, boolean spike) {

        Transformation raised() {
            return spike ? spikePose(size, peak, tilt, -0.15f) : chunkPose(size, tilt, peak);
        }

        Transformation sunk() {
            return spike ? spikePose(size, 0.05f, tilt, -0.5f) : chunkPose(size, tilt, BURIED_CENTER - 0.1f);
        }
    }

    /** A ring of chunks raised by the wave front in the same tick. */
    private record Ring(long born, List<Chunk> chunks) {
    }

    /**
     * The earthquake wave: rays fanned around the facing direction advance one block per tick, following the terrain
     * (one block up, a few blocks down) and stopping at walls. Every ground block they cross pops up as a block
     * display (the far cells sometimes as dripstone spikes), and enemies standing on it are struck once.
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
        /** Where the wave broke through walls so far. */
        private final List<Location> breaches = new ArrayList<>();
        private int spikes;

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
            boolean spiked = false;
            for (int i = 0; i < rays.length; i++) {
                if (!rayAlive[i]) {
                    continue;
                }
                int x = (int) Math.floor(originX + rays[i].getX() * distance);
                int z = (int) Math.floor(originZ + rays[i].getZ() * distance);
                int previous = rayGround[i];
                if (!passable(world, x, previous + 1, z) && !passable(world, x, previous + 2, z)) {
                    rayAlive[i] = false; // a wall at least two blocks high stops this part of the wave
                    breach(new Location(world, x + 0.5, previous + 1.5, z + 0.5));
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
                Chunk chunk = raise(ground, tick, rays[i]);
                chunks.add(chunk);
                spiked |= chunk.spike();
                soundX = x + 0.5;
                soundY = ground.getY() + 1;
                soundZ = z + 0.5;
                strikeAround(new Location(world, x + 0.5, ground.getY() + 1.0, z + 0.5), rays[i], displayData(ground));
            }
            if (!chunks.isEmpty()) {
                rings.add(new Ring(tick, chunks));
                Location at = new Location(world, soundX, soundY, soundZ);
                // The rumble rises in pitch as the wave rolls on.
                float rise = 0.06f * tick;
                Fx.sound(at, Sound.BLOCK_DRIPSTONE_BLOCK_BREAK, 0.8f, 0.6f + rise + Fx.jitter(0.08f));
                if (tick % 2 == 0) {
                    Fx.sound(at, Sound.BLOCK_ROOTED_DIRT_BREAK, 0.7f, 0.7f + rise + Fx.jitter(0.1f));
                }
                if (spiked) {
                    Fx.sound(at, Sound.BLOCK_POINTED_DRIPSTONE_LAND, 0.8f, 0.8f + Fx.jitter(0.1f));
                }
            }
        }

        /** The wave crashes into a wall: it crumbles around {@code point} (one breach per stretch of wall). */
        private void breach(Location point) {
            double radius = params().num("quake_block_radius");
            if (radius <= 0) {
                return;
            }
            for (Location done : breaches) {
                if (done.distanceSquared(point) < WALL_BREAK_SPACING * WALL_BREAK_SPACING) {
                    return;
                }
            }
            breaches.add(point);
            ctx().breakBlocks(player(), point, radius);
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

        private Chunk raise(Block ground, long tick, Vector ray) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            boolean spike = tick >= SPIKE_FROM_STEP && spikes < MAX_QUAKE_SPIKES && random.nextFloat() < SPIKE_CHANCE;
            BlockData data = spike ? SPIKE : displayData(ground);
            float size;
            float peak;
            Quaternionf tilt;
            if (spike) {
                spikes++;
                size = 0.9f + random.nextFloat() * 0.25f;
                peak = 1.3f + random.nextFloat() * 0.6f;
                // Spikes lean forward along the wave.
                tilt = new Quaternionf().rotateAxis(0.25f + random.nextFloat() * 0.2f,
                        (float) ray.getZ(), 0, (float) -ray.getX()).rotateY(random.nextFloat() * 6.28f);
            } else {
                size = 0.82f + random.nextFloat() * 0.12f;
                peak = 1.1f + random.nextFloat() * 0.3f;
                tilt = new Quaternionf().rotateXYZ(
                        (random.nextFloat() - 0.5f) * 0.5f,
                        (random.nextFloat() - 0.5f) * 1.2f,
                        (random.nextFloat() - 0.5f) * 0.5f);
            }
            // Spawned in the air block above the ground (the poses lower the rock back into place): clients light a
            // display from the block at its position, and inside the solid ground block that is pitch black. It is
            // lit like the ground around it, not full bright, so it does not glow at night.
            Location above = ground.getLocation().add(0, 1, 0);
            Transformation buried = spike ? spikePose(size, 0.05f, tilt, -0.2f) : chunkPose(size, tilt, BURIED_CENTER);
            BlockDisplay display = Displays.spawn(ctx(), player(), above, BlockDisplay.class, REMOVE_AT + 2L, d -> {
                d.setBlock(data);
                d.setBrightness(null);
                d.setViewRange(1.0f);
                d.setTransformation(buried);
            });

            Location top = ground.getLocation().add(0.5, 1.02, 0.5);
            BlockData debris = displayData(ground);
            world.spawnParticle(Particle.BLOCK, top, Vfx.n(ctx(), 3), 0.3, 0.05, 0.3, 0.1, debris);
            Vfx.dot(top.clone().add(0, 0.2, 0), Particle.DUST, SOIL, false);
            if ((tick + ground.getX() + ground.getZ()) % 2 == 0) {
                world.spawnParticle(Particle.DUST_PILLAR, top, Vfx.n(ctx(), 2), 0.2, 0, 0.2, 0.05, debris);
            }
            return new Chunk(display, size, tilt, peak, spike);
        }

        private void animate(long tick) {
            Iterator<Ring> iterator = rings.iterator();
            while (iterator.hasNext()) {
                Ring ring = iterator.next();
                long age = tick - ring.born();
                if (age == RISE_AT) {
                    ring.chunks().forEach(chunk -> Displays.animate(chunk.display(), chunk.raised(), RISE_TICKS));
                } else if (age == SINK_AT) {
                    ring.chunks().forEach(chunk -> {
                        Displays.animate(chunk.display(), chunk.sunk(), SINK_TICKS);
                        settle(chunk.display().getLocation());
                    });
                } else if (age >= REMOVE_AT) {
                    ring.chunks().forEach(chunk -> chunk.display().remove());
                    iterator.remove();
                }
            }
        }

        /** Afterglow: a little dust settles where a rock sinks back. */
        private void settle(Location above) {
            Location at = above.clone().add(0.5, 0.25, 0.5);
            Vfx.dot(at, Particle.DUST_COLOR_TRANSITION, SETTLE, false);
        }

        private void strikeAround(Location point, Vector direction, BlockData ground) {
            for (LivingEntity enemy : ctx().enemiesNear(player(), point, HIT_RADIUS)) {
                if (!struck.add(enemy.getUniqueId())) {
                    continue;
                }
                Combat.hit(ctx(), player(), enemy, damage);
                if (!Combat.isTargetable(ctx(), player(), enemy)) {
                    continue;
                }
                Motion.push(enemy, direction.clone().multiply(LAUNCH_PUSH).setY(launch), MAX_SPEED);
                Location body = Fx.body(enemy);
                Location feet = enemy.getLocation().add(0, 0.1, 0);
                world.spawnParticle(Particle.BLOCK, feet, Vfx.n(ctx(), 12), 0.4, 0.2, 0.4, 0.2, ground);
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, body, Vfx.n(ctx(), 8), 0.35, 0.45, 0.35, 0, SAND);
                Vfx.cloud(ctx(), body, Particle.CRIT, 6, 0.3, 0.2, null, false);
                Vfx.ring(ctx(), feet, 0.7, 12, 0, Particle.DUST, SOIL, false);
                Fx.sound(body, Sound.ENTITY_IRON_GOLEM_ATTACK, 0.9f, 0.8f + Fx.jitter(0.1f));
                Fx.sound(body, Sound.BLOCK_TUFF_BREAK, 0.8f, 0.7f + Fx.jitter(0.1f));
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

    /**
     * A block of the given width and height standing on its bottom centre at (0.5, {@code baseY}, 0.5) of the
     * display's block (0 is the top of the ground below), tilted around that point.
     */
    private static Transformation spikePose(float width, float height, Quaternionf tilt, float baseY) {
        return spikePose(width, height, tilt, 0.5f, baseY, 0.5f);
    }

    /**
     * A block of the given width and height standing on its bottom centre at ({@code footX}, {@code baseY},
     * {@code footZ}) of the display, tilted around that point.
     */
    private static Transformation spikePose(float width, float height, Quaternionf tilt, float footX, float baseY,
                                            float footZ) {
        Vector3f bottom = new Quaternionf(tilt).transform(new Vector3f(width / 2, 0, width / 2));
        Vector3f translation = new Vector3f(footX, baseY, footZ).sub(bottom);
        return new Transformation(translation, new Quaternionf(tilt), new Vector3f(width, height, width),
                new Quaternionf());
    }

    /** The block shown for an erupting ground block: the block itself when it is a plain full cube. */
    private static BlockData displayData(Block ground) {
        return ground.getType().isOccluding() ? ground.getBlockData() : FALLBACK_GROUND;
    }

    // ------------------------------------------------------------------ ultimate: stone cage

    /** One cage: where it stands and the spikes that burst out of the ground around it. */
    private record Cage(Location body, Location feet, List<BlockDisplay> spikes) {
    }

    @Override
    public ActivationResult onUltimate() {
        double radius = params().num("ult_radius");
        Location origin = player().getLocation();
        List<LivingEntity> targets = ctx().enemiesNear(player(), origin, radius);
        if (targets.isEmpty()) {
            return ActivationResult.fail("반경 " + AbilityInfo.formatNumber(radius) + "블록 안에 적이 없습니다");
        }
        long duration = Math.max(1, params().ticks("ult_duration"));

        World world = origin.getWorld();
        Location stomp = origin.clone().add(0, 0.1, 0);
        world.spawnParticle(Particle.BLOCK, stomp, Vfx.n(ctx(), 40), 1.0, 0.1, 1.0, 0.2, FALLBACK_GROUND, true);
        world.spawnParticle(Particle.DUST_PLUME, stomp, Vfx.n(ctx(), 16), 0.8, 0.05, 0.8, 0.03, null, true);
        Vfx.ring(ctx(), stomp, 1.2, 18, 0, Particle.DUST_COLOR_TRANSITION, SAND, true);
        Fx.sound(origin, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 1.4f, 0.6f);
        Fx.sound(origin, Sound.ENTITY_WARDEN_EMERGE, 0.9f, 1.4f + Fx.jitter(0.05f));
        Fx.sound(origin, Sound.ENTITY_RAVAGER_ROAR, 0.6f, 0.7f + Fx.jitter(0.05f));
        Loop.repeat(ctx(), player(), 0, 1, SHOCKWAVE_TICKS, tick -> {
            drawShockwave(origin, radius * (tick + 1) / SHOCKWAVE_TICKS, tick);
            return true;
        });

        List<Cage> cages = new ArrayList<>();
        for (LivingEntity target : List.copyOf(targets)) {
            cages.add(imprison(target, duration, cages.size() < MAX_SPIKED_CAGES));
        }
        player().sendActionBar(Text.mm("<" + COLOR_TAG + ">⛰ " + cages.size() + "명 감금!</" + COLOR_TAG + ">"));
        Loop.repeat(ctx(), player(), TRICKLE_PERIOD, TRICKLE_PERIOD, Math.max(1, duration / TRICKLE_PERIOD - 1),
                run -> {
                    cages.forEach(EarthAbility::trickle);
                    return true;
                });
        ctx().runLater(player(), duration, () -> {
            int[] rubble = {0};
            for (Cage cage : cages) {
                crumble(cage, rubble);
            }
        });
        return ActivationResult.success();
    }

    /** Roots {@code target} and builds a cage of temporary blocks around it. */
    private Cage imprison(LivingEntity target, long ticks, boolean withSpikes) {
        centerInBlock(target);
        target.setVelocity(new Vector());
        ctx().addModifier(player(), target, Attribute.MOVEMENT_SPEED, -1.0,
                AttributeModifier.Operation.MULTIPLY_SCALAR_1, ticks);
        ctx().addModifier(player(), target, Attribute.JUMP_STRENGTH, -1.0,
                AttributeModifier.Operation.MULTIPLY_SCALAR_1, ticks);
        buildCage(target, ticks);

        Location feet = target.getLocation();
        Location body = feet.clone().add(0, target.getHeight() * 0.5, 0);
        World world = target.getWorld();
        world.spawnParticle(Particle.DUST_PILLAR, feet, Vfx.n(ctx(), 24), 0.8, 0.1, 0.8, 0.1, CAGE_PILLAR, true);
        world.spawnParticle(Particle.BLOCK, body, Vfx.n(ctx(), 16), 0.7, 0.6, 0.7, 0.1, CAGE_WALL, true);
        Vfx.helix(ctx(), feet.clone().add(0, 0.1, 0), 1.75, target.getHeight() + 0.9, 1.0, 2, 12,
                ThreadLocalRandom.current().nextDouble(Math.PI * 2), Particle.DUST_COLOR_TRANSITION, SETTLE, true);
        Fx.sound(body, Sound.BLOCK_DRIPSTONE_BLOCK_PLACE, 1.2f, 0.6f + Fx.jitter(0.05f));
        Fx.sound(body, Sound.BLOCK_POINTED_DRIPSTONE_LAND, 1.0f, 0.8f + Fx.jitter(0.1f));
        Fx.sound(body, Sound.BLOCK_TUFF_PLACE, 0.9f, 0.6f + Fx.jitter(0.08f));

        List<BlockDisplay> spikes = new ArrayList<>();
        if (withSpikes) {
            spikeCorners(feet, ticks, spikes);
        }
        return new Cage(body, feet, spikes);
    }

    /**
     * Four dripstone spikes burst out of the ground at the cage's diagonals, flaring outwards. Each display stands
     * on its spike's foot (on the ground of its own column), outside the cage, and skips a spot inside a wall.
     */
    private void spikeCorners(Location feet, long ticks, List<BlockDisplay> spikes) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        World world = feet.getWorld();
        for (int corner = 0; corner < 4; corner++) {
            double angle = Math.PI / 4 + corner * Math.PI / 2;
            double dx = Math.cos(angle);
            double dz = Math.sin(angle);
            Location foot = new Location(world, feet.getBlockX() + 0.5 + dx * CORNER_SPIKE_DISTANCE, feet.getY(),
                    feet.getBlockZ() + 0.5 + dz * CORNER_SPIKE_DISTANCE);
            Spots.snapToGround(world, foot, START_DEPTH);
            Location anchor = foot.add(0, SPIKE_ANCHOR_LIFT, 0);
            if (anchor.getBlock().getType().isOccluding()) {
                continue; // inside a wall: it would render black and stick out of the wall
            }
            float width = 0.8f + random.nextFloat() * 0.25f;
            float height = 1.6f + random.nextFloat() * 0.7f;
            Quaternionf lean = new Quaternionf().rotateAxis(0.3f + random.nextFloat() * 0.15f, (float) dz, 0,
                    (float) -dx);
            BlockDisplay spike = Displays.spawn(ctx(), player(), anchor, BlockDisplay.class, ticks + 8, d -> {
                d.setBlock(SPIKE);
                d.setBrightness(null);
                d.setTransformation(cornerSpikePose(width, 0.05f, lean, -0.2f));
            });
            Displays.animateNextTick(ctx(), player(), spike, cornerSpikePose(width, height, lean, -0.15f), 3);
            spikes.add(spike);
        }
    }

    /** {@link #spikePose} for a corner spike, whose display stands {@link #SPIKE_ANCHOR_LIFT} above its foot. */
    private static Transformation cornerSpikePose(float width, float height, Quaternionf tilt, float baseY) {
        return spikePose(width, height, tilt, 0, baseY - SPIKE_ANCHOR_LIFT, 0);
    }

    /** While a cage stands, grit trickles from its roof. */
    private static void trickle(Cage cage) {
        Location roof = cage.feet().clone().add(0, 2.0, 0);
        roof.getWorld().spawnParticle(Particle.FALLING_DUST, roof, 2, 0.35, 0.02, 0.35, 0, CAGE_ROOF);
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
     * fits around it. Only slid sideways within its column: its height stays exactly what it was.
     *
     * <p>A player is teleported even when it already stands centred: its client runs ahead of the position the
     * server knows, and the cage is built around the server's position. Without the teleport a running player's
     * client could already be a block further, inside a cage wall, and simply walk out of the cage.
     */
    private void centerInBlock(LivingEntity target) {
        Location location = target.getLocation();
        Location centered = location.clone();
        centered.setX(location.getBlockX() + 0.5);
        centered.setZ(location.getBlockZ() + 0.5);
        if (!(target instanceof Player) && centered.distanceSquared(location) < 0.05 * 0.05) {
            return;
        }
        if (!Spots.isFree(target.getWorld(), centered, target.getWidth(), target.getHeight())
                || !ctx().isInsideArena(centered)) {
            return;
        }
        target.teleport(centered);
    }

    /** The cage breaks apart: a burst of stone, rubble flying out and the spikes sinking back into the ground. */
    private void crumble(Cage cage, int[] rubbleSpawned) {
        Location center = cage.body();
        World world = center.getWorld();
        world.spawnParticle(Particle.BLOCK, center, Vfx.n(ctx(), 30), 0.8, 0.8, 0.8, 0.15, CAGE_PILLAR);
        world.spawnParticle(Particle.BLOCK, center, Vfx.n(ctx(), 16), 0.8, 0.8, 0.8, 0.15, CAGE_WALL);
        world.spawnParticle(Particle.BLOCK_CRUMBLE, center, Vfx.n(ctx(), 10), 0.8, 0.8, 0.8, 0, CAGE_ROOF);
        world.spawnParticle(Particle.DUST_PLUME, center, Vfx.n(ctx(), 8), 0.6, 0.6, 0.6, 0.02);
        Vfx.ring(ctx(), cage.feet().clone().add(0, 0.1, 0), 1.6, 16, 0, Particle.DUST_COLOR_TRANSITION, SETTLE, false);
        Fx.sound(center, Sound.BLOCK_DRIPSTONE_BLOCK_BREAK, 1.2f, 0.7f + Fx.jitter(0.05f));
        Fx.sound(center, Sound.BLOCK_STONE_BREAK, 1.0f, 0.6f + Fx.jitter(0.05f));
        Fx.sound(center, Sound.BLOCK_TUFF_BREAK, 0.8f, 0.8f + Fx.jitter(0.08f));

        for (BlockDisplay spike : cage.spikes()) {
            if (spike.isValid()) {
                Transformation current = spike.getTransformation();
                Quaternionf lean = new Quaternionf(current.getLeftRotation());
                Displays.animate(spike, cornerSpikePose(current.getScale().x(), 0.05f, lean, -0.6f), 5);
            }
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < RUBBLE_PER_CAGE && rubbleSpawned[0] < MAX_RUBBLE; i++, rubbleSpawned[0]++) {
            double angle = random.nextDouble(Math.PI * 2);
            float dx = (float) Math.cos(angle) * (1.4f + random.nextFloat());
            float dz = (float) Math.sin(angle) * (1.4f + random.nextFloat());
            float size = 0.28f + random.nextFloat() * 0.15f;
            BlockDisplay rock = Displays.block(ctx(), player(), center, RUBBLE[random.nextInt(RUBBLE.length)],
                    Displays.scaled(size), 13);
            rock.setBrightness(null);
            // Two straight legs approximate a thrown arc: up and out, then down to the ground.
            Displays.animateNextTick(ctx(), player(), rock, Displays.transform(dx * 0.5f, 0.6f, dz * 0.5f, size, size,
                    size, random.nextFloat() * 360, random.nextFloat() * 90, 0), 4);
            ctx().runLater(player(), 5, () -> Displays.animate(rock, Displays.transform(dx, -0.8f, dz, size * 0.5f,
                    size * 0.5f, size * 0.5f, random.nextFloat() * 360, random.nextFloat() * 180, 0), 7));
        }
    }

    private void drawShockwave(Location origin, double radius, long tick) {
        World world = origin.getWorld();
        int points = Vfx.n(ctx(), (int) Math.max(12, Math.min(48, radius * 5)));
        double y = origin.getY() + 0.2;
        double phase = tick * 0.2;
        for (int i = 0; i < points; i++) {
            double angle = phase + i * (Math.PI * 2 / points);
            double x = origin.getX() + Math.cos(angle) * radius;
            double z = origin.getZ() + Math.sin(angle) * radius;
            if (i % 3 == 0) {
                world.spawnParticle(Particle.BLOCK, x, y, z, 1, 0.1, 0.05, 0.1, 0, FALLBACK_GROUND, true);
            } else {
                world.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0.05, 0, 0, SOIL, true);
            }
        }
        if (radius > 1) {
            Vfx.ring(ctx(), new Location(world, origin.getX(), y + 0.15, origin.getZ()), radius - 0.6,
                    Math.max(8, points / 2), -phase, Particle.DUST_COLOR_TRANSITION, SAND, true);
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
