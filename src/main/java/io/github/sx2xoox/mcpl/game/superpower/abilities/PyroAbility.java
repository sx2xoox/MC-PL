package io.github.sx2xoox.mcpl.game.superpower.abilities;

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
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.tag.DamageTypeTags;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;

/**
 * 화염술사 — a straightforward burner. Immune to fire and sets melee victims ablaze; the active fires a quick
 * three-shot fireball volley, the ultimate wraps the owner in a fire vortex that follows them around.
 *
 * <p>Look (gold → orange → deep red): fireballs are spinning fire-charge sprites trailing heat dust, flames and
 * smoke that bloom into fire on impact. The vortex is three flame spiral arms, each led by an orbiting fire comet,
 * over a slowly turning magma sigil and fenced by an ember ring.
 */
public final class PyroAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("pyro", "화염술사", Material.BLAZE_POWDER)
            .color(TextColor.color(0xFF7A2E))
            .difficulty(1)
            .passive("불꽃의 몸",
                    "불과 용암 같은 화염 피해를 받지 않습니다. 근접 공격한 적을 {melee_burn}초간 불태웁니다.")
            .active("화염구",
                    "화염구 {shots}발을 빠르게 연사합니다. 화염구가 터지면 반경 {fireball_radius}블록의 적에게 "
                            + "{fireball_damage} 피해를 주고 {fireball_burn}초간 불태우며, "
                            + "마지막 화염구는 적을 밀쳐냅니다.",
                    8)
            .ultimate("불바다",
                    "{ult_duration}초간 나를 따라다니는 반경 {ult_radius}블록의 화염 소용돌이를 일으킵니다. "
                            + "안에 있는 적은 {ult_interval}초마다 {ult_damage} 피해를 입고 불탑니다.")
            .param("melee_burn", 2)
            .param("shots", 3)
            .param("fireball_damage", 3)
            .param("fireball_radius", 1.5)
            .param("fireball_burn", 3)
            .param("fireball_range", 32)
            .param("fireball_push", 0.4)
            .param("ult_duration", 6)
            .param("ult_radius", 6)
            .param("ult_interval", 0.5)
            .param("ult_damage", 1)
            .param("ult_burn", 2)
            .build();

    /** Blocks travelled per tick by a fireball. */
    private static final double FIREBALL_SPEED = 1.5;
    /** How close the flight path must pass an enemy's hitbox to count as a direct hit. */
    private static final double FIREBALL_HITBOX = 0.35;
    /** Collision sampling distance along the flight path, so fast fireballs never skip a target. */
    private static final double SAMPLE_STEP = 0.4;
    private static final long SHOT_INTERVAL_TICKS = 4;
    /** Vanilla deals burn damage whenever the remaining fire ticks are a multiple of this. */
    private static final int BURN_PERIOD = 20;
    /** Upward part of the fireball push, relative to its horizontal speed. */
    private static final double PUSH_LIFT = 0.6;
    private static final double MAX_PUSH = 1.5;

    // Visuals
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFD08A, 0xFF6A00, 0xB71C1C);
    private static final Particle.DustOptions SCORCH = new Particle.DustOptions(PALETTE.edge(), 1.3f);
    private static final double GOLDEN_ANGLE = Math.PI * (3 - Math.sqrt(5));
    /** The fireball sprite hangs this far below the simulated flight line (the eye height). */
    private static final double SPRITE_DROP = 0.2;
    private static final float FIREBALL_SCALE = 0.8f;
    /** Degrees a fire sprite turns per tick. */
    private static final float SPRITE_SPIN = 47f;
    private static final long FIRE_ABSORB_COOLDOWN_TICKS = 10;
    private static final int VORTEX_ARMS = 3;
    private static final int VORTEX_POINTS_PER_ARM = 7;
    private static final double VORTEX_SPIN = 0.32;
    /** How far (radians) each spiral arm curls between the owner and the edge. */
    private static final double ARM_CURL = 2.4;
    private static final double ARM_RISE = 1.6;
    private static final float COMET_SCALE = 1.05f;
    private static final long COMET_GROW_TICKS = 6;
    private static final float SIGIL_SIZE = 2.6f;
    private static final long SIGIL_GROW_TICKS = 8;
    /** How far below a jumping owner the sigil still finds the ground. */
    private static final double SIGIL_DROP = 4;
    private static final long VORTEX_FADE_TICKS = 10;

    /** Last tick the "fire is absorbed" feedback played, to throttle it while standing in fire or lava. */
    private long lastAbsorbFx = -FIRE_ABSORB_COOLDOWN_TICKS;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    // ------------------------------------------------------------------ passive

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled() || !isFireDamage(event)) {
            return;
        }
        event.setCancelled(true);
        if (event.getCause() == DamageCause.FIRE_TICK) {
            // Immune anyway: drop the burning overlay so it does not block the owner's view.
            player().setFireTicks(0);
        }
        absorbFire();
    }

    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (ctx().isAbilityDamage() || !player().equals(event.getDamager()) || !ctx().isEnemy(player(), victim)) {
            return;
        }
        DamageCause cause = event.getCause();
        if (cause != DamageCause.ENTITY_ATTACK && cause != DamageCause.ENTITY_SWEEP_ATTACK) {
            return;
        }
        ignite(victim, params().ticks("melee_burn"));
        flameLick(victim, 1.0f);
        Fx.sound(Fx.body(victim), Sound.ITEM_FIRECHARGE_USE, 0.35f, 1.5f + Fx.jitter(0.1f));
    }

    private static boolean isFireDamage(EntityDamageEvent event) {
        DamageCause cause = event.getCause();
        if (cause == DamageCause.FIRE || cause == DamageCause.FIRE_TICK || cause == DamageCause.LAVA) {
            return true;
        }
        // Covers magma blocks, campfires and fireballs, which now report CONTACT/PROJECTILE causes.
        Tag<DamageType> fire = DamageTypeTags.IS_FIRE;
        return fire != null && fire.isTagged(event.getDamageSource().getDamageType());
    }

    /** Fire the owner is immune to flows into its body as embers (throttled while standing in fire or lava). */
    private void absorbFire() {
        long now = ctx().currentTick();
        if (now - lastAbsorbFx < FIRE_ABSORB_COOLDOWN_TICKS) {
            return;
        }
        lastAbsorbFx = now;
        Location chest = Fx.body(player());
        int motes = Vfx.n(ctx(), 4);
        for (int i = 0; i < motes; i++) {
            Location from = Vfx.scatter(player().getLocation(), 0.9).add(0, 0.1, 0);
            Vfx.trail(from, chest, i % 2 == 0 ? PALETTE.core() : PALETTE.body(), 8, false);
        }
    }

    /** A short lick of flame around {@code victim}'s waist: an ember ring, rising flames and heat dust. */
    private void flameLick(LivingEntity victim, float size) {
        Location body = Fx.body(victim);
        World world = body.getWorld();
        double phase = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        Vfx.ring(ctx(), body.clone().add(0, -0.35, 0), 0.45 * size, 6, phase, Particle.SMALL_FLAME, null, false);
        world.spawnParticle(Particle.FLAME, body, Vfx.n(ctx(), 4), 0.2, 0.35, 0.2, 0.02);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, body, Vfx.n(ctx(), 4), 0.25, 0.4, 0.25, 0,
                PALETTE.glow(1.1f * size));
    }

    // ------------------------------------------------------------------ active: fireball volley

    @Override
    public ActivationResult onActive() {
        int shots = Math.max(1, params().integer("shots"));
        launchFireball(0, shots == 1);
        if (shots > 1) {
            Loop.repeat(ctx(), player(), SHOT_INTERVAL_TICKS, SHOT_INTERVAL_TICKS, shots - 1, shot -> {
                launchFireball((int) shot + 1, shot + 2 == shots);
                return true;
            });
        }
        return ActivationResult.success();
    }

    private void launchFireball(int index, boolean last) {
        Location eye = player().getEyeLocation();
        Vector direction = eye.getDirection();
        Fireball fireball = new Fireball(eye, direction, last);
        long maxTicks = (long) Math.ceil(params().num("fireball_range") / FIREBALL_SPEED) + 2;
        Loop.repeat(ctx(), player(), 0, 1, maxTicks, fireball);
        muzzleFlare(eye, direction);
        Fx.sound(eye, Sound.ENTITY_BLAZE_SHOOT, 0.8f, 0.9f + index * 0.15f + Fx.jitter(0.05f));
        Fx.sound(eye, Sound.ITEM_FIRECHARGE_USE, 0.45f, 1.3f + index * 0.1f + Fx.jitter(0.05f));
    }

    /** The cast at the hand: a small flame ring around the aim, a jet of flames and a puff of smoke. */
    private void muzzleFlare(Location eye, Vector direction) {
        World world = eye.getWorld();
        Location muzzle = eye.clone().add(direction.clone().multiply(0.9)).subtract(0, SPRITE_DROP, 0);
        aimRing(muzzle, direction, 0.3, 7, Particle.SMALL_FLAME);
        int jets = Vfx.n(ctx(), 4);
        for (int i = 0; i < jets; i++) {
            // count 0: the offsets become the particle's direction and "extra" its speed.
            world.spawnParticle(Particle.FLAME, muzzle.getX(), muzzle.getY(), muzzle.getZ(), 0,
                    direction.getX() + Fx.jitter(0.25f), direction.getY() + Fx.jitter(0.25f),
                    direction.getZ() + Fx.jitter(0.25f), 0.12 + i * 0.03);
        }
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, muzzle, Vfx.n(ctx(), 3), 0.08, 0.08, 0.08, 0,
                PALETTE.glow(1.2f));
        world.spawnParticle(Particle.SMOKE, muzzle, Vfx.n(ctx(), 2), 0.05, 0.05, 0.05, 0.01);
    }

    /** A simulated fireball: flies straight and bursts on the first enemy, block or fluid it meets. */
    private final class Fireball implements LongPredicate {

        private final World world;
        private final Location position;
        private final Vector direction;
        private final ItemDisplay visual;
        private final boolean last;
        private double remaining;

        Fireball(Location eye, Vector direction, boolean last) {
            this.world = eye.getWorld();
            this.position = eye.clone();
            this.direction = direction.clone().normalize();
            this.last = last;
            this.remaining = params().num("fireball_range");
            long lifetime = (long) Math.ceil(remaining / FIREBALL_SPEED) + 6;
            Location spawn = flat(eye).add(this.direction.clone().multiply(1.0)).subtract(0, SPRITE_DROP, 0);
            this.visual = Displays.item(ctx(), player(), spawn, ItemStack.of(Material.FIRE_CHARGE),
                    sprite(0.3f, 0), lifetime);
            visual.setBillboard(Display.Billboard.CENTER);
            visual.setTeleportDuration(1);
        }

        @Override
        public boolean test(long tick) {
            double step = Math.min(FIREBALL_SPEED, remaining);
            if (step <= 1.0E-3) {
                burst(position);
                return false;
            }
            RayTraceResult wall = world.rayTraceBlocks(position, direction, step, FluidCollisionMode.ALWAYS, true);
            double travel = wall == null ? step : wall.getHitPosition().distance(position.toVector());

            int samples = Math.max(1, (int) Math.ceil(travel / SAMPLE_STEP));
            for (int i = 1; i <= samples; i++) {
                Location probe = position.clone().add(direction.clone().multiply(travel * i / samples));
                if (!ctx().enemiesNear(player(), probe, FIREBALL_HITBOX).isEmpty()) {
                    burst(probe);
                    return false;
                }
            }
            if (wall != null) {
                Location impact = wall.getHitPosition().toLocation(world);
                Block block = wall.getHitBlock();
                if (block != null && block.getType() == Material.WATER) {
                    fizzle(impact);
                } else {
                    burst(impact.subtract(direction.clone().multiply(0.2)));
                }
                return false;
            }

            Location from = position.clone();
            position.add(direction.clone().multiply(step));
            remaining -= step;
            fly(from, tick);
            if (remaining <= 1.0E-3) {
                burst(position);
                return false;
            }
            return true;
        }

        /** Moves the spinning sprite and leaves a trail of glowing dust, licking flames, smoke and embers. */
        private void fly(Location from, long tick) {
            Location head = flat(position).subtract(0, SPRITE_DROP, 0);
            Location tail = flat(from).subtract(0, SPRITE_DROP, 0);
            if (visual.isValid()) {
                visual.teleport(head);
                float pulse = FIREBALL_SCALE + 0.08f * (float) Math.sin(tick * 1.3);
                Displays.animate(visual, sprite(pulse, SPRITE_SPIN * (tick + 1)), tick == 0 ? 2 : 1);
            }
            Vfx.line(ctx(), tail, head, 1.6, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(0.9f), false);
            Vector back = direction.clone().multiply(-1);
            int flames = Vfx.n(ctx(), 2);
            for (int i = 0; i < flames; i++) {
                world.spawnParticle(Particle.FLAME, head.getX(), head.getY(), head.getZ(), 0,
                        back.getX() + Fx.jitter(0.3f), back.getY() + Fx.jitter(0.3f), back.getZ() + Fx.jitter(0.3f),
                        0.06);
            }
            Vfx.dot(tail, Particle.DUST, SCORCH, false);
            Vfx.dot(tail, Particle.SMOKE, null, false);
            if (tick % 3 == 0) {
                Vfx.dot(head, Particle.LAVA, null, false);
            }
        }

        private void burst(Location at) {
            visual.remove();
            double radius = params().num("fireball_radius");
            double damage = params().num("fireball_damage");
            long burn = params().ticks("fireball_burn");
            double push = params().num("fireball_push");
            Vector shove = Motion.horizontal(direction).multiply(push).setY(push * PUSH_LIFT);
            for (LivingEntity enemy : ctx().enemiesNear(player(), at, radius)) {
                Combat.hit(ctx(), player(), enemy, damage);
                if (!Combat.isTargetable(ctx(), player(), enemy)) {
                    continue;
                }
                ignite(enemy, burn);
                flameLick(enemy, 0.8f);
                if (last && push > 0) {
                    Motion.push(enemy, shove, MAX_PUSH);
                }
            }
            fireBloom(at, radius, last);
        }

        private void fizzle(Location at) {
            visual.remove();
            world.spawnParticle(Particle.CLOUD, at, Vfx.n(ctx(), 8), 0.15, 0.1, 0.15, 0.03);
            world.spawnParticle(Particle.WHITE_SMOKE, at, Vfx.n(ctx(), 6), 0.15, 0.1, 0.15, 0.04);
            world.spawnParticle(Particle.SMOKE, at, Vfx.n(ctx(), 4), 0.15, 0.1, 0.15, 0.02);
            Fx.sound(at, Sound.BLOCK_FIRE_EXTINGUISH, 0.7f, 1.3f + Fx.jitter(0.1f));
            Fx.sound(at, Sound.BLOCK_LAVA_EXTINGUISH, 0.4f, 1.6f + Fx.jitter(0.1f));
        }
    }

    /** A camera-facing fire-charge sprite of {@code scale}, turned {@code spin} degrees in the view plane. */
    private static Transformation sprite(float scale, float spin) {
        return Displays.centered(0, 0, 0, scale, scale, scale, 0, 0, spin % 360f);
    }

    /**
     * The fireball impact, staged: a white-gold flash with a ball of fire flying outward, a ring of heat racing to
     * the edge of the splash and lava pops, then a few ticks of rising embers and smoke. The volley's last
     * fireball blooms bigger.
     */
    private void fireBloom(Location at, double radius, boolean big) {
        World world = at.getWorld();
        Vfx.burst(ctx(), at, PALETTE, big ? radius * 1.1 : radius * 0.8, Particle.FLAME, false);
        bloom(at, Particle.FLAME, big ? 22 : 14, big ? 0.22 : 0.16, false);
        Vfx.shockwave(ctx(), player(), at, 0.3, radius, big ? 5 : 4, Particle.DUST, PALETTE.dust(1.2f), false);
        world.spawnParticle(Particle.LAVA, at, Vfx.n(ctx(), big ? 6 : 3), 0.2, 0.2, 0.2, 0);
        world.spawnParticle(Particle.LARGE_SMOKE, at, Vfx.n(ctx(), 3), 0.2, 0.2, 0.2, 0.03);
        Location center = at.clone();
        float spread = (float) (radius * 0.4);
        Loop.repeat(ctx(), player(), 2, 2, 6, i -> {
            Location ember = center.clone().add(Fx.jitter(spread), 0.1 + i * 0.12, Fx.jitter(spread));
            Vfx.dot(ember, Particle.DUST_COLOR_TRANSITION, PALETTE.fade(0.9f), false);
            world.spawnParticle(Particle.SMALL_FLAME, ember.getX(), ember.getY(), ember.getZ(), 0, 0, 1, 0, 0.04);
            if (i % 2 == 0) {
                Vfx.dot(ember.add(0, 0.3, 0), Particle.SMOKE, null, false);
            }
            return true;
        });
        Fx.sound(at, Sound.ENTITY_GENERIC_EXPLODE, big ? 0.75f : 0.5f, (big ? 1.3f : 1.6f) + Fx.jitter(0.15f));
        Fx.sound(at, Sound.ITEM_FIRECHARGE_USE, 0.8f, 0.8f + Fx.jitter(0.1f));
        Fx.sound(at, Sound.BLOCK_LAVA_POP, 0.6f, 0.8f + Fx.jitter(0.2f));
    }

    // ------------------------------------------------------------------ ultimate: fire vortex

    @Override
    public ActivationResult onUltimate() {
        long duration = Math.max(1, params().ticks("ult_duration"));
        long interval = Math.max(1, params().ticks("ult_interval"));
        double radius = params().num("ult_radius");

        Location start = player().getLocation();
        ignitionBlast(start, radius);
        Vortex vortex = new Vortex(start, radius, duration);
        Loop.repeat(ctx(), player(), 0, 1, duration, tick -> {
            Location center = player().getLocation();
            vortex.draw(center, tick);
            if (tick % interval == 0) {
                scorch(center, radius);
            }
            return true;
        }, () -> vortex.dieDown(player().getLocation()));
        return ActivationResult.success();
    }

    private void scorch(Location center, double radius) {
        double damage = params().num("ult_damage");
        long burn = params().ticks("ult_burn");
        Location chest = Fx.body(player());
        for (LivingEntity enemy : ctx().enemiesNear(player(), center, radius)) {
            Combat.hit(ctx(), player(), enemy, damage);
            if (!Combat.isTargetable(ctx(), player(), enemy)) {
                continue;
            }
            ignite(enemy, burn);
            flameLick(enemy, 0.9f);
            // A tendril of fire from the caster to every enemy it scorches.
            Location body = Fx.body(enemy);
            Vfx.trail(chest, body, PALETTE.body(), 6, true);
            Vfx.trail(chest.clone().add(0, 0.3, 0), body, PALETTE.core(), 8, true);
        }
        Fx.sound(center, Sound.ENTITY_BLAZE_BURN, 1.2f, 0.7f + Fx.jitter(0.15f));
    }

    /**
     * The ultimate's cast: a white flash, a pillar of flame spiralling up around the owner, a ring of fire racing out
     * to the vortex edge and flames shooting outward along the ground.
     */
    private void ignitionBlast(Location center, double radius) {
        World world = center.getWorld();
        world.spawnParticle(Particle.FLASH, center.clone().add(0, 1.0, 0), 1, 0, 0, 0, 0, PALETTE.core(), true);
        Vfx.helix(ctx(), center, 0.9, 3.2, 1.5, 3, 14, 0, Particle.FLAME, null, true);
        Vfx.shockwave(ctx(), player(), center.clone().add(0, 0.2, 0), 0.6, radius, 8, Particle.FLAME, null, true);
        int rays = Vfx.n(ctx(), 36);
        double speed = Math.max(0.2, radius * 0.06);
        for (int i = 0; i < rays; i++) {
            double angle = i * (Math.PI * 2 / rays);
            // count 0: the offsets become the particle's direction and "extra" its speed.
            world.spawnParticle(Particle.FLAME, center.getX(), center.getY() + 0.4, center.getZ(), 0,
                    Math.cos(angle), 0.05, Math.sin(angle), speed, null, true);
        }
        world.spawnParticle(Particle.LAVA, center.clone().add(0, 0.5, 0), Vfx.n(ctx(), 10), 0.6, 0.3, 0.6, 0, null,
                true);
        Fx.sound(center, Sound.ENTITY_BLAZE_SHOOT, 1.4f, 0.55f);
        Fx.sound(center, Sound.ITEM_FIRECHARGE_USE, 1.2f, 0.6f);
        Fx.sound(center, Sound.ENTITY_GHAST_SHOOT, 0.8f, 0.7f);
        Fx.sound(center, Sound.ENTITY_BLAZE_AMBIENT, 1.0f, 0.5f + Fx.jitter(0.05f));
    }

    /**
     * The visual body of the fire vortex: three spiral flame arms with a fire comet (a spinning fire-charge sprite)
     * leading each arm, a magma sigil turning under the owner, an ember ring at the edge and sparks rising inside.
     */
    private final class Vortex {

        private final double radius;
        private final ItemDisplay[] comets = new ItemDisplay[VORTEX_ARMS];
        private final BlockDisplay[] sigil = new BlockDisplay[2];

        Vortex(Location start, double radius, long duration) {
            this.radius = radius;
            long lifetime = duration + VORTEX_FADE_TICKS + 5;
            for (int arm = 0; arm < VORTEX_ARMS; arm++) {
                ItemDisplay comet = Displays.item(ctx(), player(), flat(start).add(0, 1.0, 0),
                        ItemStack.of(Material.FIRE_CHARGE), sprite(0.1f, 0), lifetime);
                comet.setBillboard(Display.Billboard.CENTER);
                comet.setTeleportDuration(1);
                comets[arm] = comet;
            }
            BlockData magma = Material.MAGMA_BLOCK.createBlockData();
            for (int i = 0; i < sigil.length; i++) {
                BlockDisplay plate = Displays.block(ctx(), player(), sigilAt(groundUnder(start), i), magma,
                        sigilShape(0.2f, i * 45f), lifetime);
                plate.setTeleportDuration(1);
                Displays.animateNextTick(ctx(), player(), plate, sigilShape(SIGIL_SIZE, i * 45f + 30f),
                        (int) SIGIL_GROW_TICKS);
                sigil[i] = plate;
            }
        }

        void draw(Location owner, long tick) {
            World world = owner.getWorld();
            double cx = owner.getX();
            double cy = owner.getY();
            double cz = owner.getZ();
            double spin = tick * VORTEX_SPIN;

            // Spiral arms sweeping outwards and upwards: white-hot near the owner, dark red at the edge.
            int points = Vfx.n(ctx(), VORTEX_POINTS_PER_ARM);
            for (int arm = 0; arm < VORTEX_ARMS; arm++) {
                double base = spin + arm * (Math.PI * 2 / VORTEX_ARMS);
                for (int i = 1; i <= points; i++) {
                    double f = i / (double) points;
                    double r = radius * f;
                    double angle = base + f * ARM_CURL;
                    double x = cx + Math.cos(angle) * r;
                    double y = cy + 0.15 + f * ARM_RISE;
                    double z = cz + Math.sin(angle) * r;
                    switch (i % 3) {
                        case 0 -> world.spawnParticle(Particle.SMALL_FLAME, x, y, z, 1, 0, 0, 0, 0, null, true);
                        case 1 -> world.spawnParticle(Particle.FLAME, x, y, z, 1, 0, 0, 0, 0, null, true);
                        default -> world.spawnParticle(Particle.DUST_COLOR_TRANSITION, x, y, z, 1, 0, 0, 0, 0,
                                f < 0.5 ? PALETTE.glow(1.3f) : PALETTE.fade(1.4f), true);
                    }
                }
                moveComet(arm, owner, base + ARM_CURL, tick);
            }
            // The owner is engulfed in flames.
            world.spawnParticle(Particle.FLAME, cx, cy + 1.0, cz, Vfx.n(ctx(), 2), 0.3, 0.6, 0.3, 0.01, null, true);
            // The edge of the inferno, so everyone can see where it ends.
            if (tick % 2 == 0) {
                Location edge = owner.clone().add(0, 0.2, 0);
                Vfx.ring(ctx(), edge, radius, 18, -spin * 0.5, Particle.DUST, PALETTE.dust(1.5f), true);
                Vfx.ring(ctx(), edge, radius - 0.15, 10, -spin * 0.5 + 0.17, Particle.DUST, SCORCH, true);
            }
            // Sparks rising from the burning ground.
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int sparks = Vfx.n(ctx(), 3);
            for (int i = 0; i < sparks; i++) {
                Location spark = Vfx.scatter(owner, radius * 0.95).add(0, 0.1, 0);
                world.spawnParticle(i == 0 ? Particle.LAVA : Particle.FLAME, spark.getX(), spark.getY(),
                        spark.getZ(), 0, 0, 1, 0, 0.06 + random.nextDouble(0.08), null, true);
            }
            moveSigil(owner, tick);
            if (tick % 10 == 5) {
                Fx.sound(owner, Sound.BLOCK_FIRE_AMBIENT, 1.4f, 0.8f + Fx.jitter(0.2f));
            }
        }

        private void moveComet(int arm, Location owner, double angle, long tick) {
            ItemDisplay comet = comets[arm];
            if (!comet.isValid()) {
                return;
            }
            double r = radius * 0.97;
            comet.teleport(flat(owner).add(Math.cos(angle) * r, 0.15 + ARM_RISE, Math.sin(angle) * r));
            float scale = tick < COMET_GROW_TICKS ? COMET_SCALE * (tick + 1) / COMET_GROW_TICKS
                    : COMET_SCALE + 0.1f * (float) Math.sin(tick * 0.9);
            Displays.animate(comet, sprite(scale, -SPRITE_SPIN * tick), 1);
        }

        private void moveSigil(Location owner, long tick) {
            Location ground = groundUnder(owner);
            for (int i = 0; i < sigil.length; i++) {
                BlockDisplay plate = sigil[i];
                if (!plate.isValid()) {
                    continue;
                }
                plate.teleport(sigilAt(ground, i));
                if (tick >= SIGIL_GROW_TICKS && tick % 4 == 0) {
                    Displays.animate(plate, sigilShape(SIGIL_SIZE, i * 45f + 30f + tick * 3f), 4);
                }
            }
        }

        /** The vortex dies down: displays shrink away, a smoke ring rolls outward and embers settle. */
        void dieDown(Location center) {
            for (ItemDisplay comet : comets) {
                if (comet.isValid()) {
                    Displays.shrinkAway(ctx(), player(), comet, 6);
                }
            }
            for (BlockDisplay plate : sigil) {
                if (plate.isValid()) {
                    Displays.shrinkAway(ctx(), player(), plate, (int) VORTEX_FADE_TICKS);
                }
            }
            World world = center.getWorld();
            Vfx.shockwave(ctx(), player(), center.clone().add(0, 0.5, 0), radius * 0.5, radius * 1.1, 6,
                    Particle.LARGE_SMOKE, null, true);
            world.spawnParticle(Particle.LARGE_SMOKE, center.clone().add(0, 0.6, 0), Vfx.n(ctx(), 14),
                    radius * 0.35, 0.4, radius * 0.35, 0.02, null, true);
            Location at = center.clone();
            ThreadLocalRandom random = ThreadLocalRandom.current();
            Loop.repeat(ctx(), player(), 0, 2, VORTEX_FADE_TICKS / 2 + 3, i -> {
                int embers = Vfx.n(ctx(), (int) Math.max(1, 8 - i));
                for (int e = 0; e < embers; e++) {
                    Location ember = Vfx.scatter(at, radius).add(0, 0.2 + random.nextDouble(1.2), 0);
                    Vfx.dot(ember, Particle.DUST_COLOR_TRANSITION, PALETTE.fade(1.0f), true);
                }
                return true;
            });
            Fx.sound(center, Sound.BLOCK_FIRE_EXTINGUISH, 1.0f, 0.7f);
            Fx.sound(center, Sound.BLOCK_FIRE_EXTINGUISH, 0.6f, 1.2f + Fx.jitter(0.1f));
        }
    }

    /** The ground right under {@code owner} (its feet when it is higher up than that), so the sigil stays down. */
    private static Location groundUnder(Location owner) {
        Location ground = flat(owner);
        Spots.snapToGround(ground.getWorld(), ground, SIGIL_DROP);
        return ground;
    }

    /** Where sigil plate {@code index} lies on {@code ground}; the two plates are a hair apart so they never z-fight. */
    private static Location sigilAt(Location ground, int index) {
        return ground.clone().add(0, 0.04 + index * 0.015, 0);
    }

    /** A flat magma plate turned {@code yaw} degrees; two of them, 45 degrees apart, make an eight-pointed sigil. */
    private static Transformation sigilShape(float size, float yaw) {
        return Displays.transform(0, 0, 0, size, 0.01f, size, yaw, 0, 0);
    }

    // ------------------------------------------------------------------ helpers

    /** {@code count} (scaled) particles flying outward from {@code at} evenly in all directions at {@code speed}. */
    private void bloom(Location at, Particle particle, int count, double speed, boolean force) {
        World world = at.getWorld();
        int total = Vfx.n(ctx(), count);
        for (int i = 0; i < total; i++) {
            double y = 1 - (i + 0.5) * 2.0 / total;
            double r = Math.sqrt(Math.max(0, 1 - y * y));
            double angle = i * GOLDEN_ANGLE;
            world.spawnParticle(particle, at.getX(), at.getY(), at.getZ(), 0, Math.cos(angle) * r, y,
                    Math.sin(angle) * r, speed, null, force);
        }
    }

    /** A small ring of {@code particle} around the aim axis {@code direction}, centred on {@code center}. */
    private void aimRing(Location center, Vector direction, double radius, int points, Particle particle) {
        Vector axis = direction.clone().normalize();
        Vector side = axis.clone().crossProduct(new Vector(0, 1, 0));
        if (side.lengthSquared() < 1.0E-6) {
            side = new Vector(1, 0, 0);
        }
        side.normalize();
        Vector up = side.clone().crossProduct(axis).normalize();
        int count = Vfx.n(ctx(), points);
        double phase = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        for (int i = 0; i < count; i++) {
            double angle = phase + i * (Math.PI * 2 / count);
            Vector offset = side.clone().multiply(Math.cos(angle) * radius)
                    .add(up.clone().multiply(Math.sin(angle) * radius));
            Vfx.dot(center.clone().add(offset), particle, null, false);
        }
    }

    /** {@code at} without its rotation, so display entities placed there keep their own orientation. */
    private static Location flat(Location at) {
        Location copy = at.clone();
        copy.setYaw(0);
        copy.setPitch(0);
        return copy;
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
}
