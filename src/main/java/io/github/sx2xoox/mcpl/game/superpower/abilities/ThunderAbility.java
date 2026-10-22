package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

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
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 뇌신 — lightning caster. Melee hits may shock, the active calls a telegraphed bolt on the aimed spot that
 * launches enemies, the ultimate smites every enemy in a wide radius several times.
 *
 * <p>Look (white, pale yellow, electric blue): a ring of sparks closes in on the aimed spot while charge falls from
 * the sky, then a jagged bolt of glowing white segments slams down with branching sparks and ground arcs. The
 * ultimate gathers a dark storm cloud of tumbling blocks over the caster that flickers with lightning, marks every
 * target with a spark ring and strikes it from the sky.
 */
public final class ThunderAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("thunder", "뇌신", Material.LIGHTNING_ROD)
            .color(TextColor.color(0xFFE14D))
            .difficulty(2)
            .passive("정전기",
                    "근접 공격 시 {shock_chance}% 확률로 적을 감전시켜 {shock_damage} 피해를 주고 "
                            + "{shock_slow}초간 구속 II를 겁니다.")
            .active("낙뢰",
                    "{range}블록 안의 조준한 곳에 번개를 내리꽂습니다. {strike_delay}초 뒤 반경 {radius}블록의 적에게 "
                            + "{damage} 피해를 주고 {launch_height}블록 높이로 띄웁니다.",
                    9)
            .ultimate("천벌",
                    "반경 {ult_radius}블록 안의 모든 적에게 {ult_interval}초 간격으로 낙뢰를 {ult_strikes}번 "
                            + "내리칩니다. 낙뢰 한 번에 {ult_damage} 피해를 줍니다.")
            .param("shock_chance", 25)
            .param("shock_damage", 1)
            .param("shock_slow", 1)
            .param("range", 30)
            .param("radius", 2.5)
            .param("damage", 6)
            .param("strike_delay", 0.3)
            .param("launch_height", 2)
            .param("ult_radius", 20)
            .param("ult_strikes", 3)
            .param("ult_interval", 1)
            .param("ult_damage", 4)
            .build();

    private static final double GRAVITY = 0.08;
    private static final double OUTWARD_PUSH = 0.3;
    private static final double MAX_VELOCITY = 2.5;
    /** Ticks between casting the ultimate and the first wave of bolts (the storm gathers). */
    private static final long ULT_WINDUP_TICKS = 10;

    // Visuals
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFFFFF, 0xFFF6A8, 0x7FD4FF);
    private static final Particle.DustOptions ARC_DUST = new Particle.DustOptions(PALETTE.edge(), 0.7f);
    private static final Particle.DustOptions EDGE_DUST = new Particle.DustOptions(PALETTE.edge(), 1.0f);
    private static final Particle.DustOptions BRANCH_DUST = PALETTE.dust(0.8f);
    private static final BlockData BOLT_CORE = Material.WHITE_CONCRETE.createBlockData();
    private static final BlockData[] CLOUD_BLOCKS = {Material.GRAY_WOOL.createBlockData(),
            Material.LIGHT_GRAY_WOOL.createBlockData(), Material.GRAY_CONCRETE_POWDER.createBlockData()};
    /** Display entities this ability keeps alive at most at once (bolts fall back to particles beyond it). */
    private static final int MAX_DISPLAYS = 24;
    private static final double GOLDEN_ANGLE = Math.PI * (3 - Math.sqrt(5));
    /** Height above the impact where a bolt starts. */
    private static final double BOLT_HEIGHT = 14;
    private static final int BOLT_SEGMENTS = 6;
    private static final double BOLT_JITTER = 0.8;
    private static final float BOLT_THICKNESS = 0.09f;
    /** Ticks a bolt stays on screen; it flickers once in between. */
    private static final long BOLT_TICKS = 7;
    private static final int GROUND_ARCS = 5;
    private static final long AFTERGLOW_TICKS = 14;
    // Storm cloud
    private static final int CLOUD_SIZE = 10;
    private static final double CLOUD_HEIGHT = 8.5;
    private static final double CLOUD_RADIUS = 3.6;
    private static final double CLOUD_TURN = 0.015;
    private static final long CLOUD_GROW_TICKS = 8;
    private static final long CLOUD_FADE_TICKS = 10;
    private static final Display.Brightness STORM_DARK = new Display.Brightness(4, 4);
    /** Ticks before each wave that the targets are marked. */
    private static final long WAVE_TELEGRAPH_TICKS = 5;

    /** Display entities spawned by this instance that are still alive. */
    private int liveDisplays;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    // ------------------------------------------------------------------ passive: static shock

    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (ctx().isAbilityDamage() || event.getCause() != DamageCause.ENTITY_ATTACK
                || !player().equals(event.getDamager()) || !ctx().isEnemy(player(), victim)) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() * 100.0 >= params().num("shock_chance")) {
            return;
        }
        int slowTicks = (int) Math.min(Integer.MAX_VALUE, params().ticks("shock_slow"));
        if (slowTicks > 0) {
            victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slowTicks, 1, false, true, true));
        }
        shockVisual(victim);
        player().sendActionBar(Text.mm("<#FFE14D>⚡ 감전!</#FFE14D>"));

        // Deal the shock right after the melee hit has resolved, instead of nesting it inside this event.
        double damage = params().num("shock_damage");
        ctx().runLater(player(), 1, () -> Combat.hit(ctx(), player(), victim, damage));
    }

    /** Static discharge: a spark jumps from the owner's hand into the victim, and small arcs crawl over its body. */
    private void shockVisual(LivingEntity victim) {
        Location body = victim.getLocation().add(0, victim.getHeight() * 0.55, 0);
        World world = victim.getWorld();
        Location hand = player().getEyeLocation().add(player().getEyeLocation().getDirection().multiply(0.6))
                .subtract(0, 0.35, 0);
        Vfx.trail(hand, body, PALETTE.body(), 3, false);
        Vfx.trail(hand, body.clone().add(0, 0.2, 0), PALETTE.edge(), 4, false);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double half = victim.getWidth() * 0.6;
        for (int arc = 0; arc < 3; arc++) {
            Location from = body.clone().add(random.nextDouble(-half, half), random.nextDouble(-0.6, 0.6),
                    random.nextDouble(-half, half));
            Location to = body.clone().add(random.nextDouble(-half, half), random.nextDouble(-0.6, 0.6),
                    random.nextDouble(-half, half));
            drawArc(zigzag(from, to, 3, 0.15, 1), ARC_DUST, 6);
        }
        world.spawnParticle(Particle.ELECTRIC_SPARK, body, Vfx.n(ctx(), 10), 0.3, 0.5, 0.3, 0.15);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, body, Vfx.n(ctx(), 5), 0.3, 0.5, 0.3, 0,
                PALETTE.glow(0.9f));
        Fx.sound(body, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.45f, 1.8f + Fx.jitter(0.15f));
        Fx.sound(body, Sound.BLOCK_COPPER_BULB_TURN_ON, 0.6f, 1.7f + Fx.jitter(0.1f));
    }

    // ------------------------------------------------------------------ active: lightning strike

    @Override
    public ActivationResult onActive() {
        double range = params().num("range");
        Location target = aimPoint(range);
        if (target == null) {
            return ActivationResult.fail(AbilityInfo.formatNumber(range) + "블록 안의 땅이나 적을 조준하세요");
        }
        double radius = params().num("radius");
        long delay = params().ticks("strike_delay");

        Fx.sound(target, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.0f, 1.6f);
        Fx.sound(player().getLocation(), Sound.ENTITY_EVOKER_CAST_SPELL, 0.6f, 1.8f);
        castSparks();
        chargeFromSky(target, Math.max(1, delay));
        drawWarning(target, radius, 0, Math.max(1, delay));
        if (delay <= 0) {
            strike(target);
        } else {
            Loop.repeat(ctx(), player(), 1, 1, delay, tick -> {
                if (tick == delay - 1) {
                    strike(target);
                    return false;
                }
                drawWarning(target, radius, tick + 1, delay);
                return true;
            });
        }
        return ActivationResult.success();
    }

    /** The aimed enemy's feet, otherwise the aimed block surface, or null if nothing is in range. */
    private @Nullable Location aimPoint(double range) {
        LivingEntity aimed = ctx().findTarget(player(), range);
        if (aimed != null) {
            return aimed.getLocation();
        }
        Location eye = player().getEyeLocation();
        World world = eye.getWorld();
        RayTraceResult result = world.rayTraceBlocks(eye, eye.getDirection(), range, FluidCollisionMode.SOURCE_ONLY,
                true);
        if (result == null) {
            return null;
        }
        Vector point = result.getHitPosition();
        BlockFace face = result.getHitBlockFace();
        if (face != null) {
            point.add(face.getDirection().multiply(0.05));
        }
        return point.toLocation(world);
    }

    /** Sparks crackle around the caster's raised hand. */
    private void castSparks() {
        Location eye = player().getEyeLocation();
        Location hand = eye.clone().add(eye.getDirection().multiply(0.5)).add(0, 0.25, 0);
        hand.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, hand, Vfx.n(ctx(), 8), 0.15, 0.15, 0.15, 0.2);
        hand.getWorld().spawnParticle(Particle.DUST_COLOR_TRANSITION, hand, Vfx.n(ctx(), 4), 0.12, 0.12, 0.12, 0,
                PALETTE.glow(0.8f));
    }

    /** Charge falls from the sky onto the target, arriving exactly when the bolt does, under a puff of dark cloud. */
    private void chargeFromSky(Location target, long ticks) {
        Location sky = target.clone().add(0, BOLT_HEIGHT, 0);
        int motes = Vfx.n(ctx(), 5);
        for (int i = 0; i < motes; i++) {
            Location from = Vfx.scatter(sky, 1.6);
            Vfx.trail(from, target.clone().add(0, 0.3, 0), i % 2 == 0 ? PALETTE.core() : PALETTE.edge(),
                    (int) ticks, true);
        }
        target.getWorld().spawnParticle(Particle.LARGE_SMOKE, sky, Vfx.n(ctx(), 6), 1.2, 0.3, 1.2, 0.01, null,
                true);
    }

    /** The telegraph: the strike radius outlined, and a spark ring closing in on the target as the bolt nears. */
    private void drawWarning(Location center, double radius, long tick, long delay) {
        Location ground = center.clone().add(0, 0.15, 0);
        double progress = Math.min(1.0, (double) tick / delay);
        double closing = radius * (1.25 - 0.9 * progress);
        Vfx.ring(ctx(), ground, closing, 16, tick * 0.5, Particle.ELECTRIC_SPARK, null, true);
        Vfx.ring(ctx(), ground, closing, 10, tick * 0.5 + 0.3, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.0f),
                true);
        if (tick % 2 == 0) {
            Vfx.ring(ctx(), ground, radius, 20, -tick * 0.2, Particle.DUST, EDGE_DUST, true);
        }
    }

    private void strike(Location point) {
        World world = point.getWorld();
        world.strikeLightningEffect(point);
        Location top = point.clone().add(Fx.jitter(1.5f), BOLT_HEIGHT, Fx.jitter(1.5f));
        bolt(top, point, false);
        impact(point, params().num("radius"), true, false);

        double radius = params().num("radius");
        double damage = params().num("damage");
        double launch = launchVelocity(params().num("launch_height"));
        int hits = 0;
        for (LivingEntity enemy : ctx().enemiesNear(player(), point, radius)) {
            Combat.hit(ctx(), player(), enemy, damage);
            hits++;
            if (!Combat.isTargetable(ctx(), player(), enemy)) {
                continue;
            }
            Vector push = enemy.getLocation().toVector().subtract(point.toVector()).setY(0);
            if (push.lengthSquared() > 1.0E-4) {
                push.normalize().multiply(OUTWARD_PUSH);
            } else {
                push.zero();
            }
            Motion.push(enemy, push.setY(launch), MAX_VELOCITY);
            world.spawnParticle(Particle.ELECTRIC_SPARK, Fx.body(enemy), Vfx.n(ctx(), 12), 0.3, 0.5, 0.3, 0.3);
        }
        if (hits > 0) {
            player().sendActionBar(Text.mm("<#FFE14D>⚡ 낙뢰 적중</#FFE14D> <gray>" + hits + "명</gray>"));
        }
    }

    /**
     * A bolt's impact, staged: a white flash and a spark fountain, a shock ring racing out to {@code radius}, arcs
     * crawling over the ground, scorched smoke and kicked-up debris, then (for a {@code big} one) a crackling
     * afterglow.
     */
    private void impact(Location point, double radius, boolean big, boolean force) {
        World world = point.getWorld();
        Location ground = point.clone().add(0, 0.15, 0);
        world.spawnParticle(Particle.FLASH, point.clone().add(0, 0.5, 0), 1, 0, 0, 0, 0, PALETTE.core(), force);
        world.spawnParticle(Particle.ELECTRIC_SPARK, point.clone().add(0, 0.4, 0), Vfx.n(ctx(), big ? 36 : 20),
                0.5, 0.4, 0.5, 0.6, null, force);
        world.spawnParticle(Particle.END_ROD, point.clone().add(0, 0.3, 0), Vfx.n(ctx(), big ? 8 : 4), 0.1, 0.1,
                0.1, 0.12, null, force);
        Vfx.shockwave(ctx(), player(), ground, 0.3, radius, big ? 5 : 4, Particle.DUST_COLOR_TRANSITION,
                PALETTE.glow(1.3f), force);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int arcs = big ? GROUND_ARCS : 3;
        double phase = random.nextDouble(Math.PI * 2);
        for (int i = 0; i < arcs; i++) {
            double angle = phase + i * (Math.PI * 2 / arcs) + random.nextDouble(-0.3, 0.3);
            double reach = radius * random.nextDouble(0.7, 1.05);
            Location end = ground.clone().add(Math.cos(angle) * reach, 0, Math.sin(angle) * reach);
            drawArc(zigzag(ground, end, 4, 0.35, 0), ARC_DUST, 5);
        }
        world.spawnParticle(Particle.LARGE_SMOKE, ground, Vfx.n(ctx(), big ? 6 : 3), 0.4, 0.1, 0.4, 0.02, null,
                force);
        BlockData floor = point.clone().subtract(0, 0.2, 0).getBlock().getBlockData();
        if (!floor.getMaterial().isAir()) {
            world.spawnParticle(Particle.DUST_PILLAR, ground, Vfx.n(ctx(), big ? 16 : 8), 0.5, 0.1, 0.5, 0.1,
                    floor, force);
        }
        if (big) {
            Location at = ground.clone();
            Loop.repeat(ctx(), player(), 2, 2, AFTERGLOW_TICKS / 2, i -> {
                Location spark = Vfx.scatter(at, radius * 0.8).add(0, 0.1, 0);
                Vfx.dot(spark, Particle.ELECTRIC_SPARK, null, false);
                Vfx.dot(spark.clone().add(0, 0.2, 0), Particle.DUST, ARC_DUST, false);
                if (i % 2 == 0) {
                    Vfx.dot(Vfx.scatter(at, radius * 0.5).add(0, 0.2, 0), Particle.SMOKE, null, false);
                }
                return true;
            });
        }
        Fx.sound(point, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, big ? 1.0f : 0.8f, 1.2f + Fx.jitter(0.15f));
    }

    // ------------------------------------------------------------------ ultimate: divine punishment

    @Override
    public ActivationResult onUltimate() {
        double radius = params().num("ult_radius");
        if (ctx().enemiesNear(player(), player().getLocation(), radius).isEmpty()) {
            return ActivationResult.fail("반경 " + AbilityInfo.formatNumber(radius) + "블록 안에 적이 없습니다");
        }
        int strikes = Math.max(1, params().integer("ult_strikes"));
        long interval = Math.max(1, params().ticks("ult_interval"));
        long total = ULT_WINDUP_TICKS + (strikes - 1) * interval + 1;

        Location origin = player().getLocation();
        Fx.sound(origin, Sound.ITEM_TRIDENT_THUNDER, 1.6f, 0.8f);
        Fx.sound(origin, Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.8f);
        Fx.sound(origin, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.5f, 1.5f + Fx.jitter(0.1f));
        World world = origin.getWorld();
        world.spawnParticle(Particle.FLASH, origin.clone().add(0, 1, 0), 1, 0, 0, 0, 0, PALETTE.core(), true);
        world.spawnParticle(Particle.ELECTRIC_SPARK, origin.clone().add(0, 1, 0), Vfx.n(ctx(), 40), 0.5, 0.8, 0.5,
                0.4, null, true);
        Vfx.helix(ctx(), origin, 0.7, 3.0, 2.0, 2, 16, 0, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.0f), true);
        Vfx.shockwave(ctx(), player(), origin.clone().add(0, 0.15, 0), 0.5, 4.0, 6, Particle.ELECTRIC_SPARK, null,
                true);

        StormCloud cloud = new StormCloud(origin, total + CLOUD_FADE_TICKS + 5);
        Loop.repeat(ctx(), player(), 0, 1, total, tick -> {
            Location center = player().getLocation();
            cloud.drift(center, tick);
            drawStorm(center, tick);
            long sinceWindup = tick - ULT_WINDUP_TICKS;
            if (sinceWindup >= 0 && sinceWindup % interval == 0) {
                int wave = (int) (sinceWindup / interval) + 1;
                if (wave <= strikes) {
                    smite(center, radius, wave, strikes, cloud);
                }
            }
            long lead = Math.min(WAVE_TELEGRAPH_TICKS, interval - 1);
            long nextSince = sinceWindup < 0 ? 0 : (sinceWindup / interval + 1) * interval;
            int nextWave = (int) (nextSince / interval) + 1;
            if (lead > 0 && nextWave <= strikes && ULT_WINDUP_TICKS + nextSince - tick == lead) {
                markTargets(center, radius, cloud, lead);
            }
            return true;
        }, cloud::dissipate);
        return ActivationResult.success();
    }

    private void smite(Location center, double radius, int wave, int strikes, StormCloud cloud) {
        double damage = params().num("ult_damage");
        List<LivingEntity> enemies = ctx().enemiesNear(player(), center, radius);
        for (LivingEntity enemy : enemies) {
            Location at = enemy.getLocation();
            World world = at.getWorld();
            world.strikeLightningEffect(at);
            Location top = at.clone().add(Fx.jitter(1.2f), BOLT_HEIGHT - 2, Fx.jitter(1.2f));
            bolt(top, at, true);
            impact(at, 1.4, false, true);
            world.spawnParticle(Particle.ELECTRIC_SPARK, at.clone().add(0, 1, 0), Vfx.n(ctx(), 16), 0.4, 0.8, 0.4,
                    0.4, null, true);
            Combat.hit(ctx(), player(), enemy, damage);
        }
        cloud.flash();
        player().sendActionBar(Text.mm("<#FFE14D>⚡ 천벌 " + wave + "/" + strikes + "</#FFE14D> <gray>"
                + enemies.size() + "명 적중</gray>"));
    }

    /** Marks the next wave's targets: a spark ring at their feet and charge flowing from the cloud above them. */
    private void markTargets(Location center, double radius, StormCloud cloud, long lead) {
        for (LivingEntity enemy : ctx().enemiesNear(player(), center, radius)) {
            Location feet = enemy.getLocation().add(0, 0.15, 0);
            Vfx.ring(ctx(), feet, 0.9, 14, 0, Particle.ELECTRIC_SPARK, null, true);
            Vfx.ring(ctx(), feet, 0.9, 10, 0.3, Particle.DUST, EDGE_DUST, true);
            Vfx.trail(cloud.center(), enemy.getLocation().add(0, BOLT_HEIGHT - 2, 0), PALETTE.core(),
                    (int) lead, true);
            Vfx.trail(enemy.getLocation().add(0, BOLT_HEIGHT - 2, 0), enemy.getEyeLocation(), PALETTE.body(),
                    (int) lead, true);
        }
        Fx.sound(center, Sound.BLOCK_BEACON_POWER_SELECT, 0.6f, 1.9f + Fx.jitter(0.05f));
    }

    private void drawStorm(Location center, long tick) {
        World world = center.getWorld();
        world.spawnParticle(Particle.ELECTRIC_SPARK, center.clone().add(0, 1.0, 0), Vfx.n(ctx(), 2), 0.4, 0.7, 0.4,
                0.05, null, true);
        if (tick % 4 == 0) {
            // A small arc crawls around the caster.
            ThreadLocalRandom random = ThreadLocalRandom.current();
            Location from = center.clone().add(random.nextDouble(-0.5, 0.5), random.nextDouble(0.2, 1.8),
                    random.nextDouble(-0.5, 0.5));
            Location to = center.clone().add(random.nextDouble(-0.5, 0.5), random.nextDouble(0.2, 1.8),
                    random.nextDouble(-0.5, 0.5));
            drawArc(zigzag(from, to, 3, 0.15, 1), ARC_DUST, 5);
        }
        if (tick % 6 == 0) {
            Fx.sound(center, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.5f, 0.6f + Fx.jitter(0.1f));
        }
    }

    /**
     * A dark storm cloud of tumbling gray blocks overhead that follows the caster, turns slowly, flickers with
     * inner lightning and lights up whenever a wave strikes.
     */
    private final class StormCloud {

        private final List<Puff> puffs = new ArrayList<>();
        private Location center;

        StormCloud(Location origin, long lifetime) {
            this.center = origin.clone().add(0, CLOUD_HEIGHT, 0);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int size = reserveDisplays(CLOUD_SIZE, lifetime) ? CLOUD_SIZE : 0;
            for (int i = 0; i < size; i++) {
                double r = Math.sqrt((i + 0.5) / size) * CLOUD_RADIUS;
                double angle = i * GOLDEN_ANGLE;
                Vector offset = new Vector(Math.cos(angle) * r, random.nextDouble(-0.4, 0.4), Math.sin(angle) * r);
                BlockDisplay block = Displays.block(ctx(), player(), flat(center.clone().add(offset)),
                        CLOUD_BLOCKS[i % CLOUD_BLOCKS.length], Displays.scaled(0.05f), lifetime);
                block.setBrightness(STORM_DARK);
                block.setTeleportDuration(3);
                Puff puff = new Puff(block, offset, (float) random.nextDouble(1.3, 2.1),
                        (float) random.nextDouble(0.8, 1.2), (float) random.nextDouble(360),
                        (float) random.nextDouble(-8, 8));
                Displays.animateNextTick(ctx(), player(), block, puff.shape(1), (int) CLOUD_GROW_TICKS);
                puffs.add(puff);
            }
        }

        Location center() {
            return center.clone();
        }

        void drift(Location owner, long tick) {
            center = owner.clone().add(0, CLOUD_HEIGHT, 0);
            double turn = tick * CLOUD_TURN;
            for (Puff puff : puffs) {
                if (puff.block().isValid()) {
                    puff.block().teleport(flat(center.clone().add(puff.offset().clone().rotateAroundY(turn))));
                }
            }
            World world = owner.getWorld();
            if (tick % 2 == 0) {
                world.spawnParticle(Particle.LARGE_SMOKE, center, Vfx.n(ctx(), 2), CLOUD_RADIUS * 0.6, 0.3,
                        CLOUD_RADIUS * 0.6, 0.01, null, true);
            }
            if (tick % 3 == 0 && !puffs.isEmpty()) {
                // Inner lightning: one puff lights up for a moment.
                Puff pick = puffs.get(ThreadLocalRandom.current().nextInt(puffs.size()));
                light(pick.block(), 2);
                Location at = center.clone().add(pick.offset().clone().rotateAroundY(turn)).subtract(0, 0.6, 0);
                world.spawnParticle(Particle.ELECTRIC_SPARK, at, Vfx.n(ctx(), 4), 0.6, 0.2, 0.6, 0.2, null, true);
            }
        }

        /** The whole cloud lights up as a wave strikes. */
        void flash() {
            for (Puff puff : puffs) {
                light(puff.block(), 3);
            }
            center.getWorld().spawnParticle(Particle.FLASH, center, 1, 0, 0, 0, 0, PALETTE.core(), true);
        }

        private void light(BlockDisplay block, long ticks) {
            if (!block.isValid()) {
                return;
            }
            block.setBrightness(Displays.FULL_BRIGHT);
            ctx().runLater(player(), ticks, () -> {
                if (block.isValid()) {
                    block.setBrightness(STORM_DARK);
                }
            });
        }

        /** The storm is spent: the blocks puff apart and the cloud thins into smoke. */
        void dissipate() {
            for (Puff puff : puffs) {
                BlockDisplay block = puff.block();
                if (block.isValid()) {
                    Displays.animate(block, puff.shape(1.25f), 3);
                    ctx().runLater(player(), 3, () -> Displays.shrinkAway(ctx(), player(), block,
                            (int) CLOUD_FADE_TICKS - 3));
                }
            }
            center.getWorld().spawnParticle(Particle.LARGE_SMOKE, center, Vfx.n(ctx(), 18), CLOUD_RADIUS * 0.5,
                    0.4, CLOUD_RADIUS * 0.5, 0.03, null, true);
            Fx.sound(center, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.4f, 0.6f + Fx.jitter(0.1f));
        }
    }

    /** One block of the storm cloud: its display, its place in the cloud and its (unscaled) size and tilt. */
    private record Puff(BlockDisplay block, Vector offset, float width, float height, float yaw, float pitch) {

        Transformation shape(float scale) {
            return Displays.transform(0, 0, 0, width * scale, height * scale, width * 0.9f * scale, yaw, pitch, 0);
        }
    }

    // ------------------------------------------------------------------ bolts

    /**
     * Draws a jagged bolt from {@code top} down to {@code bottom}: glowing white display segments with an
     * electric-blue outline (particles only when the display budget is spent), sparks at every kink and two
     * branches forking off.
     */
    private void bolt(Location top, Location bottom, boolean force) {
        List<Location> points = zigzag(top, bottom, BOLT_SEGMENTS, BOLT_JITTER, 0.3);
        World world = top.getWorld();
        for (int i = 0; i + 1 < points.size(); i++) {
            Vfx.line(ctx(), points.get(i), points.get(i + 1), 1.4, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(0.9f),
                    force);
            Vfx.dot(points.get(i), Particle.ELECTRIC_SPARK, null, force);
        }
        world.spawnParticle(Particle.END_ROD, points.get(points.size() / 2), Vfx.n(ctx(), 3), 0.3, 1.5, 0.3, 0.02,
                null, force);
        if (reserveDisplays(points.size() - 1, BOLT_TICKS)) {
            spawnSegments(points);
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int b = 0; b < 2; b++) {
            Location fork = points.get(1 + random.nextInt(points.size() - 2));
            double angle = random.nextDouble(Math.PI * 2);
            Location end = fork.clone().add(Math.cos(angle) * 2.5, -random.nextDouble(2.0, 3.5),
                    Math.sin(angle) * 2.5);
            drawArc(zigzag(fork, end, 3, 0.4, 0.3), BRANCH_DUST, 4);
        }
    }

    /** The bolt's display segments: they flicker off and on once, then thin out and vanish. */
    private void spawnSegments(List<Location> points) {
        List<BlockDisplay> segments = new ArrayList<>();
        List<Transformation> full = new ArrayList<>();
        List<Transformation> thin = new ArrayList<>();
        for (int i = 0; i + 1 < points.size(); i++) {
            Location from = points.get(i);
            Vector delta = points.get(i + 1).toVector().subtract(from.toVector());
            Location aim = from.clone().setDirection(delta);
            float length = (float) delta.length() + BOLT_THICKNESS;
            float thickness = BOLT_THICKNESS * (1.0f - 0.35f * i / points.size());
            Transformation shape = Displays.transform(0, 0, 0, thickness, thickness, length, aim.getYaw(),
                    aim.getPitch(), 0);
            BlockDisplay segment = Displays.block(ctx(), player(), flat(from.clone().add(delta.multiply(0.5))),
                    BOLT_CORE, shape, BOLT_TICKS);
            Displays.outline(segment, PALETTE.edge());
            segments.add(segment);
            full.add(shape);
            thin.add(Displays.transform(0, 0, 0, 0.015f, 0.015f, length, aim.getYaw(), aim.getPitch(), 0));
        }
        Loop.repeat(ctx(), player(), 2, 1, BOLT_TICKS - 2, step -> {
            for (int i = 0; i < segments.size(); i++) {
                BlockDisplay segment = segments.get(i);
                if (step == 0) {
                    Displays.animate(segment, thin.get(i), 0);
                } else if (step == 1) {
                    Displays.animate(segment, full.get(i), 0);
                } else if (step == 3) {
                    Displays.animate(segment, thin.get(i), 2);
                }
            }
            return true;
        });
    }

    /** Particle arc through {@code points}: colored dust along the segments and a spark at each kink. */
    private void drawArc(List<Location> points, Particle.DustOptions dust, double perBlock) {
        for (int i = 0; i + 1 < points.size(); i++) {
            Vfx.line(ctx(), points.get(i), points.get(i + 1), perBlock, Particle.DUST, dust, false);
            if (i > 0) {
                Vfx.dot(points.get(i), Particle.ELECTRIC_SPARK, null, false);
            }
        }
    }

    /**
     * Points of a jagged line from {@code from} to {@code to} in {@code segments} pieces, every inner point shoved
     * sideways by up to {@code jitter} blocks (less towards the end, so bolts land precisely) and up or down by
     * {@code vertical} times that (0 keeps a ground arc flat).
     */
    private static List<Location> zigzag(Location from, Location to, int segments, double jitter, double vertical) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Vector delta = to.toVector().subtract(from.toVector());
        List<Location> points = new ArrayList<>(segments + 1);
        points.add(from.clone());
        for (int i = 1; i < segments; i++) {
            double t = (double) i / segments;
            double amount = jitter * (1 - t * 0.6);
            points.add(from.clone().add(delta.clone().multiply(t)).add(random.nextDouble(-amount, amount),
                    random.nextDouble(-amount, amount) * vertical, random.nextDouble(-amount, amount)));
        }
        points.add(to.clone());
        return points;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Claims {@code count} display entities for {@code lifetime} ticks if that stays within {@link #MAX_DISPLAYS}.
     * Visuals that cannot claim theirs fall back to particles.
     */
    private boolean reserveDisplays(int count, long lifetime) {
        if (count <= 0 || liveDisplays + count > MAX_DISPLAYS) {
            return false;
        }
        liveDisplays += count;
        ctx().runLater(player(), Math.max(1, lifetime), () -> liveDisplays -= count);
        return true;
    }

    /** {@code at} without its rotation, so display entities placed there keep their own orientation. */
    private static Location flat(Location at) {
        Location copy = at.clone();
        copy.setYaw(0);
        copy.setPitch(0);
        return copy;
    }

    /** Initial upward speed that lifts a player roughly {@code height} blocks (vanilla gravity 0.08/tick). */
    private static double launchVelocity(double height) {
        return Math.sqrt(2 * GRAVITY * Math.max(0, height));
    }
}
