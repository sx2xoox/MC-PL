package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashSet;
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
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.tag.DamageTypeTags;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 용암술사 — molten rock that lingers on the ground. Hardened against heat, it burns whoever strikes it in melee;
 * the active lobs a lava bomb that leaves a boiling pool behind, and the ultimate raises a small volcano under the
 * owner that erupts and then rains telegraphed lava chunks around it.
 *
 * <p>Look (bright yellow-orange, deep orange, basalt black — heavier and darker than the pyro's flames): the bomb is
 * a tumbling magma block trailing smoke and dripping lava; the pool is a crust of blackstone around glowing magma
 * and shroomlight plates with bubbles that swell and pop. The volcano is a ring of leaning blackstone slabs with a
 * glowing vent that rises out of the ground, erupts in a column of fire and smoke, and spits magma rocks that arc
 * onto glowing target rings.
 */
public final class MagmaAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("magma", "용암술사", Material.MAGMA_CREAM)
            .color(TextColor.color(0xF2601F))
            .difficulty(2)
            .passive("용암 피부",
                    "불·용암·화상 피해를 {heat_resist}% 덜 받습니다. 나를 근접 공격한 적은 {retaliate_burn}초간 불탑니다.")
            .active("용암탄",
                    "조준한 곳({bomb_range}블록 이내)으로 용암탄을 포물선으로 던집니다. 떨어진 곳 반경 {bomb_radius}블록의 "
                            + "적에게 {bomb_damage} 피해를 주고, {pool_duration}초간 반경 {pool_radius}블록의 끓는 용암 "
                            + "웅덩이를 남깁니다. 웅덩이 위의 적은 {pool_interval}초마다 {pool_damage} 피해를 입습니다.",
                    9)
            .ultimate("화산",
                    "{erupt_delay}초 동안 땅이 끓어오른 뒤 발밑에서 화산이 분화해 반경 {erupt_radius}블록의 적에게 "
                            + "{erupt_damage} 피해를 주고 띄워 올립니다. 이어서 {rain_duration}초간 반경 {rain_radius}블록에 "
                            + "용암 덩어리가 쏟아집니다. 떨어질 곳은 미리 표시되며, 맞은 적은 {chunk_damage} 피해를 입고 "
                            + "{chunk_burn}초간 불탑니다.")
            .param("heat_resist", 50)
            .param("retaliate_burn", 2)
            .param("bomb_range", 18)
            .param("bomb_radius", 2)
            .param("bomb_damage", 4)
            .param("pool_duration", 5)
            .param("pool_radius", 2.5)
            .param("pool_interval", 0.5)
            .param("pool_damage", 1)
            .param("erupt_delay", 0.5)
            .param("erupt_radius", 3.5)
            .param("erupt_damage", 4)
            .param("erupt_launch", 1.5)
            .param("rain_duration", 4)
            .param("rain_radius", 9)
            .param("rain_interval", 0.3)
            .param("rain_warning", 0.8)
            .param("rain_aimed", 40)
            .param("chunk_radius", 1.6)
            .param("chunk_damage", 3)
            .param("chunk_burn", 2)
            .build();

    private static final String COLOR_TAG = "#F2601F";
    /** Molten yellow core, lava orange body, cooled basalt edge. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFD54F, 0xF2601F, 0x1C0F0A);
    private static final Color EMBER = Color.fromRGB(0xFF8A3D);
    private static final Particle.DustOptions HEAT = PALETTE.dust(1.2f);
    private static final Particle.DustOptions CRUST = new Particle.DustOptions(PALETTE.edge(), 1.3f);
    private static final Particle.DustTransition GLOW = PALETTE.glow(1.1f);
    private static final Particle.DustTransition COOL = PALETTE.fade(1.2f);
    private static final BlockData MAGMA = Material.MAGMA_BLOCK.createBlockData();
    private static final BlockData SHROOMLIGHT = Material.SHROOMLIGHT.createBlockData();
    private static final BlockData BLACKSTONE = Material.BLACKSTONE.createBlockData();
    private static final BlockData BASALT = Material.POLISHED_BASALT.createBlockData();

    private static final double MAX_SPEED = 2.5;
    /** Display entities this ability keeps alive at most at once (extras fall back to particles). */
    private static final int MAX_DISPLAYS = 24;
    /** Vanilla deals burn damage whenever the remaining fire ticks are a multiple of this. */
    private static final int BURN_PERIOD = 20;

    // passive
    private static final long HEAT_FX_COOLDOWN = 10;

    // active: lava bomb
    /** Horizontal blocks per tick of the lob; short throws still take a minimum time to arc. */
    private static final double BOMB_SPEED = 0.9;
    private static final long BOMB_MIN_TICKS = 8;
    private static final long BOMB_MAX_TICKS = 24;
    /** Extra ticks a bomb may keep falling past its target (aimed at the sky or over a drop). */
    private static final long BOMB_FALL_TICKS = 40;
    private static final double BOMB_GRAVITY = 0.05;
    private static final double BOMB_HITBOX = 0.45;
    private static final double SAMPLE_STEP = 0.4;
    private static final float BOMB_SIZE = 0.42f;
    private static final int GROUND_SNAP = 8;
    /** How far below an impact a pool still finds the ground to lie on. */
    private static final int POOL_DROP = 3;
    private static final double POOL_REACH_ABOVE = 1.2;
    private static final long POOL_GROW_TICKS = 6;
    private static final long POOL_FADE_TICKS = 10;
    private static final long BUBBLE_PERIOD = 6;
    private static final long BUBBLE_TICKS = 8;

    // ultimate: volcano
    private static final int CONE_SLABS = 5;
    private static final double CONE_RADIUS = 0.95;
    private static final float SLAB_HEIGHT = 1.35f;
    private static final float SLAB_WIDTH = 1.05f;
    private static final float SLAB_THICKNESS = 0.45f;
    private static final float SLAB_LEAN = -26f;
    private static final double VENT_HEIGHT = 1.1;
    private static final double MIN_RAIN_DISTANCE = 1.6;
    private static final int TARGET_TRIES = 4;
    private static final float CHUNK_SIZE = 0.55f;
    private static final double CHUNK_APEX = 3.5;
    private static final double ERUPT_PUSH = 0.35;
    private static final long SUBSIDE_TICKS = 12;

    private long lastHeatFx = -HEAT_FX_COOLDOWN;
    /** Display entities spawned by this instance that are still alive. */
    private int liveDisplays;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    // ------------------------------------------------------------------ passive: molten skin

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (isFireDamage(event)) {
            double resist = Math.clamp(params().num("heat_resist") / 100.0, 0.0, 1.0);
            if (resist >= 1.0) {
                event.setCancelled(true);
            } else if (resist > 0) {
                event.setDamage(event.getDamage() * (1 - resist));
            }
            heatFx();
            return;
        }
        // Ability damage (reported as ENTITY_ATTACK for some types) is not a melee attack.
        if (ctx().isAbilityDamage()) {
            return;
        }
        DamageCause cause = event.getCause();
        if (!(event instanceof EntityDamageByEntityEvent byEntity)
                || (cause != DamageCause.ENTITY_ATTACK && cause != DamageCause.ENTITY_SWEEP_ATTACK)
                || !(byEntity.getDamager() instanceof LivingEntity attacker)
                || !ctx().isEnemy(player(), attacker)) {
            return;
        }
        long burn = params().ticks("retaliate_burn");
        if (burn <= 0) {
            return;
        }
        ignite(attacker, burn);
        retaliateFx(attacker);
    }

    private static boolean isFireDamage(EntityDamageEvent event) {
        DamageCause cause = event.getCause();
        if (cause == DamageCause.FIRE || cause == DamageCause.FIRE_TICK || cause == DamageCause.LAVA) {
            return true;
        }
        // Covers magma blocks, campfires and fireballs, which report other causes.
        Tag<DamageType> fire = DamageTypeTags.IS_FIRE;
        return fire != null && fire.isTagged(event.getDamageSource().getDamageType());
    }

    /** Heat soaks into the molten skin: the body glows and smoulders briefly (throttled). */
    private void heatFx() {
        long now = ctx().currentTick();
        if (now - lastHeatFx < HEAT_FX_COOLDOWN) {
            return;
        }
        lastHeatFx = now;
        Location body = Fx.body(player());
        World world = body.getWorld();
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, body, Vfx.n(ctx(), 6), 0.3, 0.45, 0.3, 0, COOL);
        world.spawnParticle(Particle.SMOKE, body, Vfx.n(ctx(), 3), 0.25, 0.4, 0.25, 0.01);
    }

    /** Molten spatter from the owner's skin onto the attacker. */
    private void retaliateFx(LivingEntity attacker) {
        Location from = Fx.body(player());
        Location to = Fx.body(attacker);
        World world = to.getWorld();
        Vfx.trail(from, to, EMBER, 4, false);
        Vfx.trail(from.clone().add(0, 0.25, 0), to, PALETTE.core(), 5, false);
        world.spawnParticle(Particle.LAVA, to, Vfx.n(ctx(), 3), 0.2, 0.3, 0.2, 0);
        world.spawnParticle(Particle.FLAME, to, Vfx.n(ctx(), 5), 0.25, 0.4, 0.25, 0.02);
        world.spawnParticle(Particle.DUST, to, Vfx.n(ctx(), 4), 0.3, 0.4, 0.3, 0, HEAT);
        Fx.sound(to, Sound.ENTITY_MAGMA_CUBE_SQUISH_SMALL, 0.8f, 0.8f + Fx.jitter(0.1f));
        Fx.sound(to, Sound.BLOCK_LAVA_POP, 0.6f, 1.2f + Fx.jitter(0.15f));
    }

    // ------------------------------------------------------------------ active: lava bomb

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection();
        Location start = eye.clone().add(direction.clone().multiply(0.8)).subtract(0, 0.25, 0);
        Location target = aimPoint(params().num("bomb_range"));
        Bomb bomb = new Bomb(start, target);
        Loop.repeat(ctx(), owner, 0, 1, bomb.maxTicks(), bomb, bomb::expire);

        World world = start.getWorld();
        world.spawnParticle(Particle.FLAME, start, Vfx.n(ctx(), 6), 0.12, 0.12, 0.12, 0.04);
        world.spawnParticle(Particle.LAVA, start, Vfx.n(ctx(), 2), 0.1, 0.1, 0.1, 0);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, start, Vfx.n(ctx(), 5), 0.12, 0.12, 0.12, 0, GLOW);
        Fx.sound(start, Sound.ENTITY_WITCH_THROW, 0.9f, 0.6f + Fx.jitter(0.05f));
        Fx.sound(start, Sound.ENTITY_MAGMA_CUBE_JUMP, 0.8f, 0.7f + Fx.jitter(0.08f));
        Fx.sound(start, Sound.BLOCK_LAVA_POP, 0.7f, 0.9f + Fx.jitter(0.1f));
        return ActivationResult.success();
    }

    /**
     * Where a lobbed bomb is aimed: the aimed enemy's feet, or where the view ray hits a block (or ends), snapped
     * down to the ground when there is ground close below.
     */
    private Location aimPoint(double range) {
        Player owner = player();
        LivingEntity aimed = ctx().findTarget(owner, range);
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection();
        Location point;
        if (aimed != null) {
            point = aimed.getLocation();
        } else {
            RayTraceResult hit = owner.getWorld().rayTraceBlocks(eye, direction, range, FluidCollisionMode.NEVER, true);
            point = hit == null
                    ? eye.clone().add(direction.clone().multiply(range))
                    : hit.getHitPosition().toLocation(owner.getWorld()).subtract(direction.clone().multiply(0.2));
        }
        Double ground = groundBelow(point, GROUND_SNAP);
        if (ground != null) {
            point.setY(ground);
        }
        return point;
    }

    /** A lobbed lava bomb: flies a parabola to its target and bursts on the first enemy or block it meets. */
    private final class Bomb implements LongPredicate {

        private final World world;
        private final Location position;
        private final Vector velocity;
        private final long flightTicks;
        private final @Nullable BlockDisplay rock;
        private final Quaternionf spin = new Quaternionf();
        private final Vector3f spinAxis;
        private boolean landed;

        Bomb(Location start, Location target) {
            this.world = start.getWorld();
            this.position = flat(start);
            Vector delta = target.toVector().subtract(start.toVector());
            double horizontal = Math.hypot(delta.getX(), delta.getZ());
            this.flightTicks = Math.clamp(Math.round(horizontal / BOMB_SPEED), BOMB_MIN_TICKS, BOMB_MAX_TICKS);
            double t = flightTicks;
            this.velocity = new Vector(delta.getX() / t, (delta.getY() + 0.5 * BOMB_GRAVITY * t * t) / t,
                    delta.getZ() / t);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            this.spinAxis = new Vector3f(random.nextFloat() - 0.5f, 1f, random.nextFloat() - 0.5f).normalize();
            long lifetime = maxTicks() + 4;
            this.rock = reserveDisplays(1, lifetime)
                    ? Displays.block(ctx(), player(), position, MAGMA, cube(BOMB_SIZE, spin), lifetime) : null;
            if (rock != null) {
                rock.setTeleportDuration(1);
            }
        }

        long maxTicks() {
            return flightTicks + BOMB_FALL_TICKS;
        }

        @Override
        public boolean test(long tick) {
            double step = velocity.length();
            if (step < 1.0E-6) {
                return true;
            }
            Vector direction = velocity.clone().multiply(1 / step);
            RayTraceResult wall = world.rayTraceBlocks(position, direction, step, FluidCollisionMode.ALWAYS, true);
            double travel = wall == null ? step : wall.getHitPosition().distance(position.toVector());
            int samples = Math.max(1, (int) Math.ceil(travel / SAMPLE_STEP));
            for (int i = 1; i <= samples; i++) {
                Location probe = position.clone().add(direction.clone().multiply(travel * i / samples));
                if (!ctx().enemiesNear(player(), probe, BOMB_HITBOX).isEmpty()) {
                    land(probe);
                    return false;
                }
            }
            if (wall != null) {
                Location impact = wall.getHitPosition().toLocation(world).subtract(direction.clone().multiply(0.1));
                Block block = wall.getHitBlock();
                if (block != null && block.isLiquid()) {
                    quench(impact);
                } else {
                    land(impact);
                }
                return false;
            }
            Location from = position.clone();
            position.add(velocity);
            velocity.setY(velocity.getY() - BOMB_GRAVITY);
            fly(from, tick);
            return true;
        }

        /** Moves the tumbling rock and leaves smoke, flames and lava drips behind it. */
        private void fly(Location from, long tick) {
            if (rock != null && rock.isValid()) {
                rock.teleport(position);
                spin.rotateAxis(0.55f, spinAxis);
                Displays.animate(rock, cube(BOMB_SIZE, spin), 1);
            }
            Vfx.line(ctx(), from, position, 1.5, Particle.DUST_COLOR_TRANSITION, GLOW, false);
            Vfx.dot(from, Particle.FLAME, null, false);
            if (tick % 2 == 0) {
                Vfx.dot(from, Particle.LARGE_SMOKE, null, false);
                Vfx.dot(position, Particle.FALLING_LAVA, null, false);
            } else {
                Vfx.dot(from, Particle.DUST, CRUST, false);
            }
        }

        private void land(Location at) {
            landed = true;
            if (rock != null) {
                rock.remove();
            }
            impact(at);
        }

        /** The bomb drops into water (or lava): it hardens in a burst of steam, without a pool. */
        private void quench(Location at) {
            landed = true;
            if (rock != null) {
                rock.remove();
            }
            world.spawnParticle(Particle.CLOUD, at, Vfx.n(ctx(), 10), 0.3, 0.2, 0.3, 0.05);
            world.spawnParticle(Particle.WHITE_SMOKE, at, Vfx.n(ctx(), 8), 0.3, 0.2, 0.3, 0.04);
            world.spawnParticle(Particle.SPLASH, at, Vfx.n(ctx(), 12), 0.3, 0.1, 0.3, 0.2);
            world.spawnParticle(Particle.BLOCK, at, Vfx.n(ctx(), 8), 0.2, 0.1, 0.2, 0.1, BLACKSTONE);
            Fx.sound(at, Sound.BLOCK_LAVA_EXTINGUISH, 1.0f, 0.8f + Fx.jitter(0.1f));
            Fx.sound(at, Sound.ENTITY_GENERIC_SPLASH, 0.6f, 1.2f + Fx.jitter(0.1f));
        }

        /** Runs when the loop ends by itself: a bomb that never landed fizzles out. */
        void expire() {
            if (landed) {
                return;
            }
            if (rock != null) {
                rock.remove();
            }
            world.spawnParticle(Particle.LARGE_SMOKE, position, Vfx.n(ctx(), 6), 0.2, 0.2, 0.2, 0.02);
            Fx.sound(position, Sound.BLOCK_FIRE_EXTINGUISH, 0.6f, 1.2f);
        }
    }

    /** The bomb bursts: splash damage, then a pool on the ground below (if there is ground close below). */
    private void impact(Location at) {
        Player owner = player();
        double radius = params().num("bomb_radius");
        Location center = at.clone();
        int hits = 0;
        for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius)) {
            Combat.hit(ctx(), owner, enemy, params().num("bomb_damage"));
            hits++;
            if (Combat.isTargetable(ctx(), owner, enemy)) {
                flameLick(enemy);
            }
        }
        World world = at.getWorld();
        Vfx.burst(ctx(), center, PALETTE, radius * 0.7, Particle.LAVA, false);
        world.spawnParticle(Particle.BLOCK, center, Vfx.n(ctx(), 14), 0.3, 0.2, 0.3, 0.15, MAGMA);
        world.spawnParticle(Particle.LARGE_SMOKE, center, Vfx.n(ctx(), 5), 0.3, 0.2, 0.3, 0.04);
        bloom(center, Particle.FLAME, 16, 0.18, false);
        Fx.sound(center, Sound.ITEM_BUCKET_EMPTY_LAVA, 1.0f, 0.8f + Fx.jitter(0.1f));
        Fx.sound(center, Sound.ENTITY_MAGMA_CUBE_SQUISH, 1.0f, 0.7f + Fx.jitter(0.1f));
        Fx.sound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.45f, 1.5f + Fx.jitter(0.1f));
        if (hits > 0 && owner.isOnline()) {
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">용암탄 <white>" + hits + "명 적중!</white></"
                    + COLOR_TAG + ">"));
        }

        Double ground = groundBelow(at.clone().add(0, 0.3, 0), POOL_DROP);
        if (ground == null) {
            return;
        }
        Location poolCenter = flat(at);
        poolCenter.setY(ground);
        if (poolCenter.clone().add(0, 0.1, 0).getBlock().isLiquid()) {
            // Lava does not pool under water.
            return;
        }
        Pool pool = new Pool(poolCenter);
        pool.open();
        Loop.repeat(ctx(), owner, 1, 1, pool.life, pool, pool::cool);
    }

    /**
     * A boiling lava pool: a blackstone crust around molten magma and a glowing heart, bubbles swelling and popping,
     * smoke and heat shimmer rising. Every {@code pool_interval} the enemies standing in it get burned.
     */
    private final class Pool implements LongPredicate {

        private final Location center;
        private final World world;
        private final double radius;
        private final long life;
        private final long interval;
        private final List<BlockDisplay> plates = new ArrayList<>();

        Pool(Location center) {
            this.center = center;
            this.world = center.getWorld();
            this.radius = Math.max(0.5, params().num("pool_radius"));
            this.life = Math.max(1, params().ticks("pool_duration"));
            this.interval = Math.max(1, params().ticks("pool_interval"));
        }

        void open() {
            Player owner = player();
            long lifetime = life + POOL_FADE_TICKS + 4;
            float size = (float) (radius * 2);
            float yaw = ThreadLocalRandom.current().nextFloat() * 90f;
            // Crust (outermost, darkest), molten magma, then the bright heart; each a hair higher than the last.
            addPlate(BLACKSTONE, size, yaw, 0.02f, lifetime, owner);
            addPlate(BLACKSTONE, size, yaw + 45f, 0.025f, lifetime, owner);
            addPlate(MAGMA, size * 0.78f, yaw + 22.5f, 0.04f, lifetime, owner);
            addPlate(MAGMA, size * 0.78f, yaw + 67.5f, 0.045f, lifetime, owner);
            addPlate(SHROOMLIGHT, size * 0.38f, yaw + 10f, 0.06f, lifetime, owner);

            Location surface = center.clone().add(0, 0.1, 0);
            Vfx.shockwave(ctx(), owner, surface, 0.3, radius, POOL_GROW_TICKS, Particle.DUST_COLOR_TRANSITION,
                    GLOW, false);
            world.spawnParticle(Particle.LAVA, surface, Vfx.n(ctx(), 8), radius * 0.4, 0.1, radius * 0.4, 0);
            Fx.sound(surface, Sound.BLOCK_LAVA_AMBIENT, 1.0f, 0.8f + Fx.jitter(0.1f));
        }

        private void addPlate(BlockData data, float size, float yaw, float height, long lifetime, Player owner) {
            if (!reserveDisplays(1, lifetime)) {
                return;
            }
            BlockDisplay plate = Displays.block(ctx(), owner, center, data, plate(size * 0.15f, yaw, height),
                    lifetime);
            Displays.animateNextTick(ctx(), owner, plate, plate(size, yaw, height), (int) POOL_GROW_TICKS);
            plates.add(plate);
        }

        @Override
        public boolean test(long tick) {
            // The first burn comes one interval in: whoever steps right out is spared.
            if (tick % interval == interval - 1) {
                scorch();
            }
            boil(tick);
            if (tick % BUBBLE_PERIOD == 0) {
                bubble();
            }
            if (tick % 20 == 10) {
                Fx.sound(center, Sound.BLOCK_LAVA_AMBIENT, 0.6f, 0.9f + Fx.jitter(0.15f));
            }
            return true;
        }

        private void scorch() {
            Player owner = player();
            double damage = params().num("pool_damage");
            for (LivingEntity enemy : ctx().enemiesNear(owner, center.clone().add(0, 0.5, 0), radius + 1)) {
                if (!inPool(enemy)) {
                    continue;
                }
                Combat.hit(ctx(), owner, enemy, damage);
                if (Combat.isTargetable(ctx(), owner, enemy)) {
                    Location feet = enemy.getLocation().add(0, 0.1, 0);
                    Vfx.ring(ctx(), feet, 0.45, 7, ThreadLocalRandom.current().nextDouble(Math.PI * 2),
                            Particle.SMALL_FLAME, null, false);
                    world.spawnParticle(Particle.LAVA, feet, 1, 0.15, 0, 0.15, 0);
                    Fx.sound(feet, Sound.ENTITY_GENERIC_BURN, 0.4f, 1.3f + Fx.jitter(0.15f));
                }
            }
        }

        /** Standing on the pool: horizontally inside it and with the feet close above its surface. */
        private boolean inPool(LivingEntity enemy) {
            Location feet = enemy.getLocation();
            double dx = feet.getX() - center.getX();
            double dz = feet.getZ() - center.getZ();
            double reach = radius + enemy.getWidth() / 2;
            double dy = feet.getY() - center.getY();
            return dx * dx + dz * dz <= reach * reach && dy >= -0.5 && dy <= POOL_REACH_ABOVE;
        }

        /** Heat shimmer, smoke wisps and lava pops over the pool, and its glowing rim. */
        private void boil(long tick) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int motes = Vfx.n(ctx(), 2);
            for (int i = 0; i < motes; i++) {
                Location at = Vfx.scatter(center, radius * 0.9).add(0, 0.15, 0);
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, at.getX(), at.getY(), at.getZ(), 0, 0, 1, 0,
                        0.05, COOL);
            }
            if (tick % 2 == 0) {
                Location at = Vfx.scatter(center, radius * 0.8).add(0, 0.1, 0);
                Vfx.dot(at, random.nextBoolean() ? Particle.SMALL_FLAME : Particle.LAVA, null, false);
            }
            if (tick % 4 == 0) {
                Location at = Vfx.scatter(center, radius * 0.7).add(0, 0.3, 0);
                world.spawnParticle(Particle.SMOKE, at.getX(), at.getY(), at.getZ(), 0, 0, 1, 0, 0.04);
                Vfx.ring(ctx(), center.clone().add(0, 0.08, 0), radius, (int) Math.max(10, radius * 5), tick * 0.05,
                        Particle.DUST, random.nextBoolean() ? HEAT : CRUST, false);
            }
        }

        /** A bubble of glowing magma swells out of the pool and pops. */
        private void bubble() {
            if (!reserveDisplays(1, BUBBLE_TICKS + 2)) {
                return;
            }
            Player owner = player();
            Location at = Vfx.scatter(center, radius * 0.7).add(0, 0.05, 0);
            float size = 0.18f + ThreadLocalRandom.current().nextFloat() * 0.12f;
            BlockDisplay bubble = Displays.block(ctx(), owner, at, SHROOMLIGHT, Displays.scaled(0.05f),
                    BUBBLE_TICKS + 2);
            Displays.animateNextTick(ctx(), owner, bubble, Displays.transform(0, size * 0.6f, 0, size, size * 0.8f,
                    size, 0, 0, 0), (int) BUBBLE_TICKS - 3);
            ctx().runLater(owner, BUBBLE_TICKS - 2, () -> {
                if (bubble.isValid()) {
                    bubble.remove();
                    Location pop = at.clone().add(0, size * 0.6, 0);
                    Vfx.dot(pop, Particle.LAVA, null, false);
                    pop.getWorld().spawnParticle(Particle.DUST, pop, Vfx.n(ctx(), 3), 0.1, 0.1, 0.1, 0, HEAT);
                    Fx.sound(pop, Sound.BLOCK_LAVA_POP, 0.6f, 0.8f + Fx.jitter(0.3f));
                }
            });
        }

        /** The pool cools: the plates sink away in a hiss of smoke and the last embers die. */
        void cool() {
            for (BlockDisplay plate : plates) {
                if (plate.isValid()) {
                    Displays.shrinkAway(ctx(), player(), plate, (int) POOL_FADE_TICKS);
                }
            }
            world.spawnParticle(Particle.LARGE_SMOKE, center.clone().add(0, 0.3, 0), Vfx.n(ctx(), 8), radius * 0.4,
                    0.1, radius * 0.4, 0.02);
            Location at = center.clone();
            Loop.repeat(ctx(), player(), 2, 2, POOL_FADE_TICKS / 2, run -> {
                Location ember = Vfx.scatter(at, radius * 0.8).add(0, 0.1 + run * 0.1, 0);
                Vfx.dot(ember, Particle.DUST_COLOR_TRANSITION, COOL, false);
                return true;
            });
            Fx.sound(center, Sound.BLOCK_FIRE_EXTINGUISH, 0.7f, 0.7f + Fx.jitter(0.1f));
        }
    }

    // ------------------------------------------------------------------ ultimate: volcano

    @Override
    public ActivationResult onUltimate() {
        Location feet = player().getLocation();
        Double ground = groundBelow(feet.clone().add(0, 0.2, 0), 3);
        if (ground == null) {
            return ActivationResult.fail("화산을 일으킬 땅이 없습니다");
        }
        Location vent = flat(feet);
        vent.setY(ground);
        Volcano volcano = new Volcano(vent);
        volcano.raise();
        Loop.repeat(ctx(), player(), 0, 1, volcano.totalTicks(), volcano, volcano::subside);
        return ActivationResult.success();
    }

    /**
     * The volcano: rises out of the ground while it rumbles, erupts, then for {@code rain_duration} spits lava
     * chunks that arc onto telegraphed spots around it, some of them at enemies' feet.
     */
    private final class Volcano implements LongPredicate {

        private final Location vent;
        private final World world;
        private final long delay;
        private final long rainTicks;
        private final long rainInterval;
        private final long warning;
        private final double rainRadius;
        private final List<BlockDisplay> cone = new ArrayList<>();
        private final Set<UUID> struck = new HashSet<>();

        Volcano(Location vent) {
            this.vent = vent;
            this.world = vent.getWorld();
            this.delay = Math.max(1, params().ticks("erupt_delay"));
            this.rainTicks = params().ticks("rain_duration");
            this.rainInterval = Math.max(1, params().ticks("rain_interval"));
            this.warning = Math.max(4, params().ticks("rain_warning"));
            this.rainRadius = Math.max(MIN_RAIN_DISTANCE + 0.5, params().num("rain_radius"));
        }

        long totalTicks() {
            return delay + rainTicks + 1;
        }

        /** The cone breaks out of the ground while the ground boils around it: the eruption's telegraph. */
        void raise() {
            Player owner = player();
            long lifetime = totalTicks() + warning + SUBSIDE_TICKS + 6;
            for (int i = 0; i < CONE_SLABS; i++) {
                if (!reserveDisplays(1, lifetime)) {
                    break;
                }
                float yaw = i * 360f / CONE_SLABS;
                BlockDisplay slab = Displays.block(ctx(), owner, vent, i % 2 == 0 ? BLACKSTONE : BASALT,
                        slabPose(yaw, 0.05f), lifetime);
                Displays.animateNextTick(ctx(), owner, slab, slabPose(yaw, 1f), (int) Math.max(2, delay));
                cone.add(slab);
            }
            if (reserveDisplays(2, lifetime)) {
                BlockDisplay heart = Displays.block(ctx(), owner, vent, SHROOMLIGHT,
                        Displays.transform(0, 0.1f, 0, 0.05f, 0.05f, 0.05f, 0, 0, 0), lifetime);
                Displays.animateNextTick(ctx(), owner, heart,
                        Displays.transform(0, 0.55f, 0, 0.8f, 0.8f, 0.8f, 45, 0, 0), (int) Math.max(2, delay));
                BlockDisplay mouth = Displays.block(ctx(), owner, vent, MAGMA, plate(0.1f, 0, 0.9f), lifetime);
                Displays.animateNextTick(ctx(), owner, mouth, plate(1.15f, 20, 0.95f), (int) Math.max(2, delay));
                cone.add(heart);
                cone.add(mouth);
            }
            Fx.sound(vent, Sound.ENTITY_WARDEN_DIG, 1.2f, 1.3f);
            Fx.sound(vent, Sound.BLOCK_LAVA_AMBIENT, 1.5f, 0.5f);
            Fx.sound(vent, Sound.BLOCK_BASALT_PLACE, 1.2f, 0.6f + Fx.jitter(0.05f));
        }

        @Override
        public boolean test(long tick) {
            if (tick < delay) {
                rumble(tick);
                return true;
            }
            if (tick == delay) {
                erupt();
            }
            long rainTick = tick - delay;
            if (rainTick < rainTicks) {
                smoke(rainTick);
                if (rainTick % rainInterval == 0) {
                    launchChunk();
                }
            }
            return true;
        }

        /** The ground boils inside the eruption radius, which glows on the floor. */
        private void rumble(long tick) {
            double radius = params().num("erupt_radius");
            Location floor = vent.clone().add(0, 0.1, 0);
            Vfx.ring(ctx(), floor, radius, (int) Math.max(14, radius * 7), tick * 0.1, Particle.DUST_COLOR_TRANSITION,
                    GLOW, true);
            Vfx.ring(ctx(), floor, radius * (1 - (double) tick / delay) + 0.3, 12, -tick * 0.2, Particle.DUST, HEAT,
                    true);
            Location pop = Vfx.scatter(vent, radius).add(0, 0.1, 0);
            world.spawnParticle(Particle.LAVA, pop, 1, 0, 0, 0, 0, null, true);
            world.spawnParticle(Particle.SMOKE, vent.clone().add(0, 0.8, 0), Vfx.n(ctx(), 2), 0.3, 0.2, 0.3, 0.02,
                    null, true);
        }

        private void erupt() {
            Player owner = player();
            double radius = params().num("erupt_radius");
            Location mouth = vent.clone().add(0, VENT_HEIGHT, 0);
            world.spawnParticle(Particle.FLASH, mouth, 1, 0, 0, 0, 0, PALETTE.core(), true);
            Vfx.helix(ctx(), vent, 0.7, 5.5, 2.0, 3, 16, 0, Particle.FLAME, null, true);
            Vfx.helix(ctx(), vent, 0.9, 4.5, 1.5, 2, 12, Math.PI / 3, Particle.DUST_COLOR_TRANSITION, GLOW, true);
            int jets = Vfx.n(ctx(), 24);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < jets; i++) {
                // count 0: the offsets become the particle's direction and "extra" its speed.
                world.spawnParticle(Particle.FLAME, mouth.getX(), mouth.getY(), mouth.getZ(), 0,
                        random.nextGaussian() * 0.25, 1, random.nextGaussian() * 0.25, 0.5 + random.nextDouble() * 0.4,
                        null, true);
            }
            world.spawnParticle(Particle.LAVA, mouth, Vfx.n(ctx(), 30), 0.5, 0.3, 0.5, 0, null, true);
            world.spawnParticle(Particle.CAMPFIRE_SIGNAL_SMOKE, mouth, Vfx.n(ctx(), 10), 0.4, 0.3, 0.4, 0.06, null,
                    true);
            world.spawnParticle(Particle.LARGE_SMOKE, mouth, Vfx.n(ctx(), 20), 0.8, 0.5, 0.8, 0.08, null, true);
            world.spawnParticle(Particle.BLOCK, vent.clone().add(0, 0.3, 0), Vfx.n(ctx(), 30), radius * 0.4, 0.2,
                    radius * 0.4, 0.2, BLACKSTONE, true);
            Vfx.shockwave(ctx(), owner, vent.clone().add(0, 0.15, 0), 0.6, radius, 6, Particle.DUST_COLOR_TRANSITION,
                    GLOW, true);
            Vfx.shockwave(ctx(), owner, vent.clone().add(0, 0.25, 0), 0.4, radius * 0.8, 6, Particle.LAVA, null, true);
            Fx.sound(vent, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 0.5f + Fx.jitter(0.05f));
            Fx.sound(vent, Sound.ITEM_BUCKET_EMPTY_LAVA, 1.5f, 0.5f);
            Fx.sound(vent, Sound.ENTITY_BLAZE_SHOOT, 1.2f, 0.5f + Fx.jitter(0.05f));
            Fx.sound(vent, Sound.BLOCK_LAVA_EXTINGUISH, 1.0f, 0.6f);

            double damage = params().num("erupt_damage");
            double launch = Motion.launchSpeed(params().num("erupt_launch"));
            for (LivingEntity enemy : ctx().enemiesNear(owner, vent.clone().add(0, 0.5, 0), radius)) {
                Combat.hit(ctx(), owner, enemy, damage);
                struck.add(enemy.getUniqueId());
                if (!Combat.isTargetable(ctx(), owner, enemy)) {
                    continue;
                }
                Vector out = Motion.horizontal(enemy.getLocation().toVector().subtract(vent.toVector()));
                Motion.push(enemy, out.multiply(ERUPT_PUSH).setY(launch), MAX_SPEED);
                flameLick(enemy);
            }
        }

        /** The vent keeps smoking and spitting while the rain lasts. */
        private void smoke(long rainTick) {
            Location mouth = vent.clone().add(0, VENT_HEIGHT, 0);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            world.spawnParticle(Particle.FLAME, mouth.getX(), mouth.getY(), mouth.getZ(), 0,
                    random.nextGaussian() * 0.2, 1, random.nextGaussian() * 0.2, 0.3, null, true);
            Vfx.dot(mouth.clone().add(Fx.jitter(0.3f), 0, Fx.jitter(0.3f)), Particle.DUST_COLOR_TRANSITION, GLOW,
                    true);
            if (rainTick % 3 == 0) {
                world.spawnParticle(Particle.CAMPFIRE_SIGNAL_SMOKE, mouth, 1, 0.2, 0.1, 0.2, 0.03, null, true);
                world.spawnParticle(Particle.LAVA, mouth, 1, 0.2, 0.1, 0.2, 0, null, true);
            }
            if (rainTick % 10 == 0) {
                Fx.sound(vent, Sound.BLOCK_LAVA_AMBIENT, 1.2f, 0.6f + Fx.jitter(0.1f));
                Fx.sound(vent, Sound.ENTITY_BLAZE_BURN, 0.8f, 0.6f + Fx.jitter(0.1f));
            }
        }

        private void launchChunk() {
            Location target = pickTarget();
            if (target == null) {
                return;
            }
            Chunk chunk = new Chunk(vent.clone().add(0, VENT_HEIGHT, 0), target, warning);
            Loop.repeat(ctx(), player(), 0, 1, warning + 1, chunk);
            Fx.sound(vent, Sound.ENTITY_GHAST_SHOOT, 0.7f, 0.6f + Fx.jitter(0.1f));
        }

        /** Some chunks at an enemy's feet, the rest scattered around the vent; always on the ground. */
        private @Nullable Location pickTarget() {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            if (random.nextDouble() * 100 < params().num("rain_aimed")) {
                List<LivingEntity> enemies = ctx().enemiesNear(player(), vent, rainRadius);
                if (!enemies.isEmpty()) {
                    Location feet = enemies.get(random.nextInt(enemies.size())).getLocation();
                    Location at = feet.clone().add(Fx.jitter(0.6f), 0.5, Fx.jitter(0.6f));
                    Double ground = groundBelow(at, 4);
                    if (ground != null) {
                        at.setY(ground);
                        return flat(at);
                    }
                }
            }
            for (int i = 0; i < TARGET_TRIES; i++) {
                Location at = Vfx.scatter(vent, rainRadius);
                if (at.distanceSquared(vent) < MIN_RAIN_DISTANCE * MIN_RAIN_DISTANCE) {
                    continue;
                }
                at.add(0, 3, 0);
                Double ground = groundBelow(at, 8);
                if (ground != null) {
                    at.setY(ground);
                    return flat(at);
                }
            }
            return null;
        }

        /** The volcano sinks back into the ground once the last chunks have landed. */
        void subside() {
            ctx().runLater(player(), warning, () -> {
                for (BlockDisplay piece : cone) {
                    if (piece.isValid()) {
                        Displays.shrinkAway(ctx(), player(), piece, (int) SUBSIDE_TICKS);
                    }
                }
                world.spawnParticle(Particle.LARGE_SMOKE, vent.clone().add(0, 0.8, 0), Vfx.n(ctx(), 12), 0.6, 0.4,
                        0.6, 0.03, null, true);
                world.spawnParticle(Particle.BLOCK, vent.clone().add(0, 0.5, 0), Vfx.n(ctx(), 16), 0.6, 0.4, 0.6, 0.1,
                        BLACKSTONE, true);
                Fx.sound(vent, Sound.BLOCK_BASALT_BREAK, 1.2f, 0.6f);
                Fx.sound(vent, Sound.BLOCK_FIRE_EXTINGUISH, 1.0f, 0.6f);
                if (!struck.isEmpty() && player().isOnline()) {
                    player().sendActionBar(Text.mm("<" + COLOR_TAG + ">화산 <white>" + struck.size()
                            + "명 적중!</white></" + COLOR_TAG + ">"));
                }
            });
        }

        /**
         * One lava chunk: a glowing ring marks where it will land while the rock arcs over from the vent, then it
         * bursts there.
         */
        private final class Chunk implements LongPredicate {

            private final Location from;
            private final Location target;
            private final long flight;
            private final @Nullable BlockDisplay rock;
            private final Quaternionf spin = new Quaternionf();
            private final Vector3f spinAxis;

            Chunk(Location from, Location target, long flight) {
                this.from = flat(from);
                this.target = target;
                this.flight = flight;
                ThreadLocalRandom random = ThreadLocalRandom.current();
                this.spinAxis = new Vector3f(random.nextFloat() - 0.5f, 1f, random.nextFloat() - 0.5f).normalize();
                this.rock = reserveDisplays(1, flight + 3)
                        ? Displays.block(ctx(), player(), this.from, MAGMA, cube(0.2f, spin), flight + 3) : null;
                if (rock != null) {
                    rock.setTeleportDuration(1);
                }
            }

            @Override
            public boolean test(long tick) {
                if (tick >= flight) {
                    if (rock != null) {
                        rock.remove();
                    }
                    land();
                    return false;
                }
                telegraph(tick);
                double t = (double) (tick + 1) / flight;
                Location at = arc(t);
                if (rock != null && rock.isValid()) {
                    rock.teleport(at);
                    spin.rotateAxis(0.6f, spinAxis);
                    Displays.animate(rock, cube(CHUNK_SIZE * (float) Math.min(1, 0.4 + t * 2), spin), 1);
                }
                Vfx.dot(at, Particle.FLAME, null, true);
                if (tick % 2 == 0) {
                    Vfx.dot(at, Particle.LARGE_SMOKE, null, true);
                } else {
                    Vfx.dot(at, Particle.DUST_COLOR_TRANSITION, GLOW, true);
                }
                return true;
            }

            /** A point on the arc from the vent to the target, {@code t} in 0..1. */
            private Location arc(double t) {
                double apex = CHUNK_APEX + from.distance(target) * 0.25;
                Vector delta = target.toVector().subtract(from.toVector());
                return from.clone().add(delta.multiply(t)).add(0, 4 * apex * t * (1 - t) + 0.3, 0);
            }

            /** The landing spot glows: a ring tightening as the rock comes down, and a pulsing core. */
            private void telegraph(long tick) {
                double radius = params().num("chunk_radius");
                double progress = (double) tick / flight;
                Location floor = target.clone().add(0, 0.12, 0);
                if (tick % 2 == 0) {
                    Vfx.ring(ctx(), floor, radius, (int) Math.max(10, radius * 8), tick * 0.15,
                            Particle.DUST_COLOR_TRANSITION, GLOW, true);
                }
                Vfx.ring(ctx(), floor, radius * (1 - progress) + 0.2, 8, -tick * 0.3, Particle.DUST, HEAT, true);
                if (tick % 4 == 0) {
                    Vfx.dot(floor, Particle.LAVA, null, true);
                }
            }

            private void land() {
                Player owner = player();
                double radius = params().num("chunk_radius");
                long burn = params().ticks("chunk_burn");
                Location center = target.clone().add(0, 0.5, 0);
                for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius)) {
                    Combat.hit(ctx(), owner, enemy, params().num("chunk_damage"));
                    struck.add(enemy.getUniqueId());
                    if (Combat.isTargetable(ctx(), owner, enemy)) {
                        ignite(enemy, burn);
                        flameLick(enemy);
                    }
                }
                Vfx.burst(ctx(), center, PALETTE, radius * 0.8, Particle.LAVA, true);
                world.spawnParticle(Particle.BLOCK, center, Vfx.n(ctx(), 12), 0.4, 0.2, 0.4, 0.15, MAGMA, true);
                world.spawnParticle(Particle.LARGE_SMOKE, center, Vfx.n(ctx(), 4), 0.3, 0.2, 0.3, 0.04, null, true);
                Vfx.shockwave(ctx(), owner, target.clone().add(0, 0.15, 0), 0.3, radius, 4, Particle.DUST, CRUST, true);
                Fx.sound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.4f + Fx.jitter(0.15f));
                Fx.sound(center, Sound.BLOCK_LAVA_POP, 1.0f, 0.7f + Fx.jitter(0.15f));
                Fx.sound(center, Sound.BLOCK_BASALT_BREAK, 0.9f, 0.7f + Fx.jitter(0.1f));
                Location scorch = target.clone();
                Loop.repeat(ctx(), owner, 2, 2, 5, run -> {
                    Location at = Vfx.scatter(scorch, radius * 0.6).add(0, 0.1, 0);
                    Vfx.dot(at, run % 2 == 0 ? Particle.SMALL_FLAME : Particle.SMOKE, null, false);
                    Vfx.dot(at.add(0, 0.2, 0), Particle.DUST_COLOR_TRANSITION, COOL, false);
                    return true;
                });
            }
        }
    }

    /** A blackstone slab of the cone at compass angle {@code yaw}, leaning in over the vent; {@code grow} 0..1. */
    private static Transformation slabPose(float yaw, float grow) {
        double angle = Math.toRadians(yaw);
        float x = (float) (-Math.sin(angle) * CONE_RADIUS);
        float z = (float) (Math.cos(angle) * CONE_RADIUS);
        float height = Math.max(0.05f, SLAB_HEIGHT * grow);
        // Centered a little below the ground so the slab's foot is buried, rising as it grows.
        return Displays.transform(x, height / 2 - 0.25f, z, SLAB_WIDTH, height, SLAB_THICKNESS, yaw, SLAB_LEAN, 0);
    }

    // ------------------------------------------------------------------ shared effects

    /** Flames licking up an enemy that got burned. */
    private void flameLick(LivingEntity victim) {
        Location body = Fx.body(victim);
        World world = body.getWorld();
        Vfx.ring(ctx(), body.clone().add(0, -0.35, 0), 0.45, 6, ThreadLocalRandom.current().nextDouble(Math.PI * 2),
                Particle.SMALL_FLAME, null, false);
        world.spawnParticle(Particle.FLAME, body, Vfx.n(ctx(), 4), 0.2, 0.35, 0.2, 0.02);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, body, Vfx.n(ctx(), 4), 0.25, 0.4, 0.25, 0, GLOW);
    }

    /**
     * Sets {@code target} on fire for at least {@code ticks}. A target that is already burning keeps its burn phase
     * (vanilla burns every 20 fire ticks), so frequent refreshes extend the fire without speeding up its damage.
     */
    private static void ignite(LivingEntity target, long ticks) {
        if (!target.isValid() || target.isDead() || ticks <= 0) {
            return;
        }
        int fireTicks = (int) Math.min(Integer.MAX_VALUE - BURN_PERIOD, ticks);
        int current = target.getFireTicks();
        if (current <= 0) {
            target.setFireTicks(fireTicks);
        } else if (current < fireTicks) {
            int periods = (fireTicks - current + BURN_PERIOD - 1) / BURN_PERIOD;
            target.setFireTicks(current + periods * BURN_PERIOD);
        }
    }

    /** The top of the first solid block at most {@code depth} blocks below {@code point}, null if none. */
    private static @Nullable Double groundBelow(Location point, int depth) {
        World world = point.getWorld();
        int x = point.getBlockX();
        int z = point.getBlockZ();
        int start = Math.min(point.getBlockY(), world.getMaxHeight() - 1);
        int end = Math.max(world.getMinHeight(), start - depth);
        for (int y = start; y >= end; y--) {
            Block block = world.getBlockAt(x, y, z);
            if (!block.isPassable() && block.getRelative(BlockFace.UP).isPassable()) {
                return block.getBoundingBox().getMaxY();
            }
        }
        return null;
    }

    /** {@code count} (scaled) particles flying outward from {@code at} evenly in all directions at {@code speed}. */
    private void bloom(Location at, Particle particle, int count, double speed, boolean force) {
        World world = at.getWorld();
        int total = Vfx.n(ctx(), count);
        double golden = Math.PI * (3 - Math.sqrt(5));
        for (int i = 0; i < total; i++) {
            double y = 1 - (i + 0.5) * 2.0 / total;
            double r = Math.sqrt(Math.max(0, 1 - y * y));
            double angle = i * golden;
            world.spawnParticle(particle, at.getX(), at.getY(), at.getZ(), 0, Math.cos(angle) * r, y,
                    Math.sin(angle) * r, speed, null, force);
        }
    }

    /**
     * Claims {@code count} display entities for {@code lifetime} ticks if that stays within {@link #MAX_DISPLAYS}.
     */
    private boolean reserveDisplays(int count, long lifetime) {
        if (count <= 0 || liveDisplays + count > MAX_DISPLAYS) {
            return false;
        }
        liveDisplays += count;
        ctx().runLater(player(), Math.max(1, lifetime), () -> liveDisplays -= count);
        return true;
    }

    /** A flat square plate of edge {@code size}, turned {@code yaw} degrees, lying {@code height} above the origin. */
    private static Transformation plate(float size, float yaw, float height) {
        return Displays.transform(0, height, 0, size, 0.02f, size, yaw, 0, 0);
    }

    /** A cube of edge {@code size} centered on the display's origin, rotated around its center. */
    private static Transformation cube(float size, Quaternionf rotation) {
        Vector3f translation = new Quaternionf(rotation).transform(new Vector3f(size / 2)).negate();
        return new Transformation(translation, new Quaternionf(rotation), new Vector3f(size), new Quaternionf());
    }

    /** {@code at} without its rotation, so display entities placed there keep their own orientation. */
    private static Location flat(Location at) {
        Location copy = at.clone();
        copy.setYaw(0);
        copy.setPitch(0);
        return copy;
    }
}
