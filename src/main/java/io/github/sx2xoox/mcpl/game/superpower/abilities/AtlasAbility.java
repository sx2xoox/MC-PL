package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.LandingGuard;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Motion;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 아틀라스 — the titan who holds up the sky. He cannot be thrown into the air (he lands at once with a shockwave) and
 * punishes everything that flies, the active drops the weight of a piece of sky on the enemies around him, and as the
 * ultimate he hands the sky over: a hot potato that crushes whoever holds it when the time runs out, unless they pass
 * it on by hitting or bumping into someone else.
 *
 * <p>Look (celestial navy, star white, bronze): a few star motes always circle over his shoulders. The weight of the sky
 * is a ring of stars falling from above onto a navy disc; the heavy enemies carry a shard of night sky on their heads.
 * The sky itself is a celestial globe — a deep blue lapis core in a blue glass shell, a tilted bronze armillary ring
 * and twinkling stars — riding over the bearer's head with a countdown, thrown in an arc and hopping from bearer to
 * bearer; when it collapses, stars rain down and the glass bursts.
 */
public final class AtlasAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("atlas", "아틀라스", Material.GLOBE_BANNER_PATTERN)
            .color(TextColor.color(0xB07A3B))
            .difficulty(1)
            .passive("천공의 어깨",
                    "하늘을 떠받친 몸이라 높이 뜨지 않습니다. 띄워지면 곧바로 땅으로 내려앉으며 발밑에 충격파를 일으켜 "
                            + "반경 {anchor_radius}블록의 적에게 {anchor_damage} 피해를 주고 밀쳐냅니다(충격파는 "
                            + "{anchor_cooldown}초마다). 땅에서 {air_height}블록 넘게 뜬 적에게 주는 근접·투사체 피해가 "
                            + "{air_bonus}% 늘고, 그 적을 근접 공격하면 땅으로 내리꽂습니다.")
            .active("하늘의 무게",
                    "별빛 고리가 {weight_delay}초 동안 반경 {weight_radius}블록을 덮은 뒤 하늘 한 조각의 무게가 "
                            + "떨어집니다. 땅을 디딘 적은 {weight_damage} 피해를 입고 {heavy_duration}초간 무거워져 점프할 "
                            + "수 없고 이동 속도가 {heavy_slow}% 줄며 빨리 떨어집니다. 공중에 뜬 적(비행·도약·띄워진 적)은 "
                            + "땅으로 내리꽂혀 {air_damage} 피해를 입습니다.",
                    9)
            .ultimate("하늘 떠넘기기",
                    "거대한 천구를 {sky_range}블록 안의 조준한 적(없으면 가장 가까운 적)에게 {throw_time}초 동안 포물선으로 "
                            + "던져 하늘을 짊어지게 합니다. 짊어진 사람은 최대 {sky_duration}초간 이동 속도가 {sky_slow}% "
                            + "줄고 1초마다 {sky_tick}부터 {sky_tick_growth}씩 커지는 피해를 입습니다. 짊어진 사람은 다른 "
                            + "플레이어를 무엇으로든 맞히거나 몸으로 부딪혀(아틀라스에게는 맞혀서만) 하늘을 떠넘길 수 "
                            + "있고(시간은 계속 흐르며, 방금 "
                            + "넘겨준 사람에게는 {pass_immunity}초 동안 되넘길 수 없음), 시간이 다 되면 들고 있던 사람 위로 "
                            + "하늘이 무너져 {collapse_damage} 피해, 주변 {collapse_radius}블록의 다른 적은 "
                            + "{collapse_splash} 피해를 입습니다. 하늘이 아틀라스에게 넘어오면 가뿐히 짊어지고, "
                            + "{globe_hold}초 안에 쓰는 다음 액티브가 바로 천구 투척({globe_range}블록 안의 조준한 곳, "
                            + "반경 {globe_radius}블록의 적에게 {globe_damage} 피해)으로 바뀝니다.")
            .ultimateNotStandalone()
            .param("anchor_radius", 2.5)
            .param("anchor_damage", 1)
            .param("anchor_cooldown", 3)
            .param("air_height", 1.3)
            .param("air_bonus", 25)
            .param("weight_delay", 0.3)
            .param("weight_radius", 7)
            .param("weight_damage", 3)
            .param("heavy_duration", 2.5)
            .param("heavy_slow", 30)
            .param("air_damage", 5)
            .param("sky_range", 20)
            .param("throw_time", 0.8)
            .param("sky_duration", 5)
            .param("sky_slow", 15)
            .param("sky_tick", 0.5)
            .param("sky_tick_growth", 0.25)
            .param("pass_immunity", 1)
            .param("collapse_damage", 4)
            .param("collapse_radius", 3)
            .param("collapse_splash", 2)
            .param("globe_hold", 8)
            .param("globe_range", 20)
            .param("globe_radius", 3)
            .param("globe_damage", 7)
            .build();

    private static final String BRONZE_TAG = "#B07A3B";
    private static final String STAR_TAG = "#F2F4FF";
    private static final String SKY_TAG = "#7E9BE8";

    // ------------------------------------------------------------------ look
    /** Star white, the blue of the night sky, celestial navy. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xF2F4FF, 0x4A6BC0, 0x1B2A55);
    /** Polished bronze, bronze, dark bronze. */
    private static final Vfx.Palette BRONZE = Vfx.Palette.of(0xFFD9A0, 0xB07A3B, 0x4A2E12);
    private static final Color STAR = Color.fromRGB(0xF2F4FF);
    private static final Color NAVY = Color.fromRGB(0x1B2A55);
    private static final Particle.DustTransition STAR_MOTE = new Particle.DustTransition(STAR,
            Color.fromRGB(0x7E9BE8), 0.7f);
    private static final Particle.DustOptions NAVY_DUST = new Particle.DustOptions(NAVY, 1.4f);
    private static final Particle.DustOptions BRONZE_DUST = BRONZE.dust(0.9f);

    /** Block types of the displays; block data is created on use (it needs a running server). */
    private static final Material GLOBE_SHELL = Material.BLUE_STAINED_GLASS;
    private static final Material GLOBE_CORE = Material.LAPIS_BLOCK;
    private static final Material GLOBE_RING = Material.COPPER_BLOCK;
    private static final Material SKY_SHARD = Material.BLUE_STAINED_GLASS;

    private static final int MAX_DISPLAYS = 24;
    private static final double MAX_SPEED = 2.5;

    // passive: shoulders of the sky
    /**
     * An upward speed this much above his own jump's (0.42 plus jump boosts, which the server sets as his velocity when
     * he jumps): something launched him. Player velocity is what the server last set.
     */
    private static final double LAUNCH_MARGIN = 0.06;
    private static final double JUMP_SPEED = 0.42;
    private static final double JUMP_BOOST_STEP = 0.1;
    private static final double ANCHOR_DROP = -1.0;
    private static final long ANCHOR_TIMEOUT = 60;
    private static final long LANDING_GUARD_TICKS = 80;
    private static final double SHOCK_PUSH = 0.6;
    private static final double SHOCK_LIFT = 0.3;
    private static final double SLAM_SPEED = 1.6;
    private static final double GROUND_PROBE = 48;

    // active: weight of the sky
    private static final int FALLING_STARS = 6;
    private static final double STAR_DROP_HEIGHT = 6;
    /** The weight reaches this high above the ground (fliers and leapers). */
    private static final double WEIGHT_REACH_UP = 14;
    private static final double WEIGHT_REACH_DOWN = 3;

    // ultimate: the sky
    private static final int RING_BARS = 6;
    private static final float RING_RADIUS = 0.82f;
    private static final float RING_TILT = 23.5f;
    private static final float SHELL_SIZE = 1.05f;
    private static final float CORE_SIZE = 0.62f;
    private static final int GLOBE_STARS = 3;
    private static final double OVER_HEAD = 0.95;
    private static final double CONTACT_SLACK = 0.12;
    private static final long HOP_TICKS = 6;
    private static final double HOP_ARC = 0.45;
    private static final long SPIN_PERIOD = 3;
    /** Hard lifetime margin of the globe's displays beyond everything it may still do. */
    private static final long GLOBE_MARGIN = 120;
    private static final long THROW_TICKS = 12;

    // ------------------------------------------------------------------ state

    private final List<Display> live = new ArrayList<>();
    private final LandingGuard landing = new LandingGuard();
    private boolean anchoring;
    private long anchorSince;
    private long nextShock = Long.MIN_VALUE / 2;
    private @Nullable Sky sky;
    private @Nullable Globe held;
    private long heldUntil = Long.MIN_VALUE / 2;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        Sky current = sky;
        if (current != null) {
            current.release();
        }
        sky = null;
        held = null;
        anchoring = false;
        landing.disarm();
        live.clear();
    }

    @Override
    public void onTick(long tick) {
        Player owner = player();
        long now = ctx().currentTick();
        landing.update(owner, now);
        anchor(owner, now);
        if (tick % 4 == 0) {
            halo(owner, tick);
        }
        Globe carried = held;
        if (carried != null) {
            if (now > heldUntil || !carried.isValid()) {
                held = null;
                carried.rise();
                owner.sendActionBar(Text.mm("<gray>천구가 하늘로 돌아갔습니다</gray>"));
            } else {
                carried.follow(overHead(owner), tick);
                if (tick % 10 == 0) {
                    owner.sendActionBar(Text.mm("<" + BRONZE_TAG + ">🌐 천구 투척 준비</" + BRONZE_TAG + "> <gray>"
                            + Fx.seconds(heldUntil - now) + "초</gray>"));
                }
            }
        }
    }

    // ------------------------------------------------------------------ passive: shoulders of the sky

    /**
     * Nothing throws the titan into the air: an upward speed only another ability (or a golem's swing) can give him is
     * replaced by a fall at once, and when he is down again the ground shakes around his feet. Only the rise goes: a
     * push sideways that came with it still moves him (he cannot be launched, but he can be knocked back).
     */
    private void anchor(Player owner, long now) {
        Vector velocity = owner.getVelocity();
        if (velocity.getY() > jumpSpeed(owner) + LAUNCH_MARGIN && Double.isFinite(velocity.getX())
                && Double.isFinite(velocity.getZ())) {
            Vector flat = Motion.clamp(Motion.horizontal(velocity), MAX_SPEED);
            Vector down = new Vector(flat.getX(), ANCHOR_DROP, flat.getZ());
            owner.setVelocity(down);
            owner.setFallDistance(0);
            landing.arm(now, LANDING_GUARD_TICKS);
            if (!anchoring) {
                anchoring = true;
                anchorSince = now;
                Location head = owner.getLocation().add(0, owner.getHeight() + 0.6, 0);
                Vfx.line(ctx(), head.clone().add(0, 1.6, 0), head, 4, Particle.DUST_COLOR_TRANSITION, STAR_MOTE, false);
                Fx.sound(head, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.7f, 0.6f + Fx.jitter(0.05f));
            }
        }
        if (!anchoring) {
            return;
        }
        if (now - anchorSince > ANCHOR_TIMEOUT) {
            anchoring = false;
        } else if (now - anchorSince >= 1 && (Motion.isGrounded(owner) || owner.isInWater())) {
            anchoring = false;
            shockwave(owner, now);
        }
    }

    /** The upward speed of the owner's own jump (vanilla jump power: the jump strength plus jump boost). */
    private static double jumpSpeed(Player owner) {
        AttributeInstance strength = owner.getAttribute(Attribute.JUMP_STRENGTH);
        double jump = strength != null ? strength.getValue() : JUMP_SPEED;
        PotionEffect boost = owner.getPotionEffect(PotionEffectType.JUMP_BOOST);
        if (boost != null) {
            jump += JUMP_BOOST_STEP * (boost.getAmplifier() + 1);
        }
        return Math.max(JUMP_SPEED, jump);
    }

    /** The landing shockwave (on its own cooldown): a small push and a little damage around the feet. */
    private void shockwave(Player owner, long now) {
        Location feet = owner.getLocation().add(0, 0.1, 0);
        World world = feet.getWorld();
        Block ground = feet.clone().subtract(0, 0.3, 0).getBlock();
        if (!ground.isPassable()) {
            world.spawnParticle(Particle.BLOCK, feet, Vfx.n(ctx(), 10), 0.5, 0.05, 0.5, 0, ground.getBlockData());
        }
        Fx.sound(feet, Sound.BLOCK_HEAVY_CORE_PLACE, 1.0f, 0.7f + Fx.jitter(0.05f));
        if (now < nextShock) {
            Vfx.ring(ctx(), feet, 0.8, 10, 0, Particle.DUST, BRONZE_DUST, false);
            return;
        }
        nextShock = now + params().ticks("anchor_cooldown");
        double radius = Math.max(0, params().num("anchor_radius"));
        Vfx.shockwave(ctx(), owner, feet, 0.4, radius, 5, Particle.DUST_COLOR_TRANSITION, BRONZE.glow(1.2f), false);
        Vfx.shockwave(ctx(), owner, feet.clone().add(0, 0.05, 0), 0.2, radius * 0.8, 6, Particle.DUST, NAVY_DUST, false);
        Vfx.cloud(ctx(), feet.clone().add(0, 0.3, 0), Particle.END_ROD, 6, 0.4, 0.05, null, false);
        Fx.sound(feet, Sound.ITEM_MACE_SMASH_GROUND, 1.0f, 0.8f + Fx.jitter(0.05f));
        Fx.sound(feet, Sound.BLOCK_ANVIL_LAND, 0.5f, 0.6f + Fx.jitter(0.05f));
        double damage = params().num("anchor_damage");
        for (LivingEntity enemy : ctx().enemiesNear(owner, feet, radius)) {
            Combat.hit(ctx(), owner, enemy, damage);
            if (Combat.isTargetable(ctx(), owner, enemy)) {
                Vector away = awayFrom(feet, enemy);
                Motion.push(enemy, away.multiply(SHOCK_PUSH).setY(SHOCK_LIFT), MAX_SPEED);
            }
        }
    }

    /** A few stars always circle over his shoulders: the sky he carries. */
    private void halo(Player owner, long tick) {
        if (owner.isInvisible()) {
            return;
        }
        Location top = owner.getLocation().add(0, owner.getHeight() + 0.25, 0);
        World world = top.getWorld();
        double tilt = Math.toRadians(20);
        for (int i = 0; i < 2; i++) {
            double angle = tick * 0.12 + i * Math.PI;
            double x = Math.cos(angle) * 0.6;
            double z = Math.sin(angle) * 0.6;
            double y = Math.sin(angle) * Math.sin(tilt) * 0.6;
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, top.getX() + x, top.getY() + y, top.getZ() + z, 1, 0, 0,
                    0, 0, STAR_MOTE);
        }
        if (tick % 20 == 0) {
            Vfx.dot(top.clone().add(0, 0.1, 0), Particle.END_ROD, null, false);
        }
    }

    /**
     * Airborne for the titan: flying or gliding, or off the ground (water counts as ground) and more than
     * {@code air_height} blocks above it, higher than a jump.
     */
    private boolean airborne(LivingEntity entity) {
        if (entity.isGliding() || entity instanceof Player player && player.isFlying()) {
            return true;
        }
        if (Motion.isGrounded(entity) || entity.isInWater()) {
            return false;
        }
        return heightAboveGround(entity) > Math.max(0, params().num("air_height"));
    }

    /** How far the entity's feet are above the first block or fluid below them (very far over the void). */
    private static double heightAboveGround(Entity entity) {
        Location feet = entity.getLocation();
        RayTraceResult ground = entity.getWorld().rayTraceBlocks(feet.clone().add(0, 0.05, 0), new Vector(0, -1, 0),
                GROUND_PROBE, FluidCollisionMode.ALWAYS, true);
        return ground == null ? GROUND_PROBE : feet.getY() - ground.getHitPosition().getY();
    }

    /** The owner's own melee swing or a vanilla projectile it shot, at an enemy (never ability damage). */
    private boolean isOwnBlow(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (ctx().isAbilityDamage() || !ctx().isEnemy(player(), victim)) {
            return false;
        }
        Entity damager = event.getDamager();
        if (damager instanceof Projectile projectile) {
            return player().equals(projectile.getShooter());
        }
        return player().equals(damager) && isMelee(event.getCause());
    }

    private static boolean isMelee(DamageCause cause) {
        return cause == DamageCause.ENTITY_ATTACK || cause == DamageCause.ENTITY_SWEEP_ATTACK;
    }

    /** The anti-air bonus: only changes the damage, since the victim may still cancel the hit. */
    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled() || !isOwnBlow(event, victim) || !airborne(victim)) {
            return;
        }
        double bonus = Math.max(0, params().num("air_bonus")) / 100.0;
        event.setDamage(event.getDamage() * (1 + bonus));
    }

    /** A melee blow that landed on an enemy in the air hammers it down to the ground. */
    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (!isOwnBlow(event, victim) || !player().equals(event.getDamager()) || !airborne(victim)) {
            return;
        }
        Player owner = player();
        // Vanilla knockback is applied after this event; slam on the next tick so it is not overwritten.
        ctx().runLater(owner, 1, () -> {
            if (Combat.isTargetable(ctx(), owner, victim)) {
                Motion.push(victim, new Vector(0, -SLAM_SPEED, 0), MAX_SPEED);
                slamFx(owner, victim, "<b>추락!</b>");
            }
        });
    }

    /** Stars streak down through a slammed enemy. */
    private void slamFx(Player owner, LivingEntity victim, String label) {
        Location body = Fx.body(victim);
        Fx.sound(body, Sound.ITEM_MACE_SMASH_AIR, 0.9f, 0.7f + Fx.jitter(0.05f));
        Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.8f, 0.6f + Fx.jitter(0.05f));
        if (!seen(owner, victim)) {
            return;
        }
        Vfx.line(ctx(), body.clone().add(0, 2.2, 0), body.clone().subtract(0, 0.6, 0), 5, Particle.DUST_COLOR_TRANSITION,
                PALETTE.glow(1.0f), false);
        Vfx.cloud(ctx(), body, Particle.END_ROD, 5, 0.25, 0.08, null, false);
        Vfx.dot(body, Particle.FLASH, STAR, false);
        popText(body.clone().add(0, 1.1, 0), "<gradient:#F2F4FF:#4A6BC0>" + label + "</gradient>");
    }

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (!event.isCancelled()) {
            landing.absorb(event, ctx().currentTick());
        }
    }

    // ------------------------------------------------------------------ active: weight of the sky / globe throw

    @Override
    public ActivationResult onActive() {
        if (held != null) {
            return throwGlobe();
        }
        Player owner = player();
        Location center = owner.getLocation();
        Spots.snapToGround(center.getWorld(), center, WEIGHT_REACH_DOWN);
        center.setYaw(0);
        center.setPitch(0);
        double radius = Math.max(1, params().num("weight_radius"));
        long delay = Math.max(1, params().ticks("weight_delay"));
        fallingStars(owner, center, radius, delay);
        Fx.sound(center, Sound.BLOCK_BEACON_POWER_SELECT, 1.0f, 0.6f + Fx.jitter(0.05f));
        Fx.sound(center, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.2f, 0.5f);
        Fx.sound(center, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.8f, 0.6f + Fx.jitter(0.05f));
        Loop.repeat(ctx(), owner, 0, 1, delay + 1, run -> {
            if (run < delay) {
                double t = (double) (run + 1) / delay;
                Location ring = center.clone().add(0, STAR_DROP_HEIGHT * (1 - t) + 0.1, 0);
                Vfx.ring(ctx(), ring, radius, (int) Math.max(18, radius * 4), run * 0.3, Particle.DUST_COLOR_TRANSITION,
                        PALETTE.glow(1.3f), true);
                Vfx.ring(ctx(), center.clone().add(0, 0.12, 0), radius * t, (int) Math.max(10, radius * 3 * t),
                        -run * 0.2, Particle.DUST, NAVY_DUST, true);
                return true;
            }
            weightFalls(owner, center, radius);
            return false;
        });
        return ActivationResult.success();
    }

    /** Star sprites high over the edge of the area that fall with the sky (only telegraph, no hit). */
    private void fallingStars(Player owner, Location center, double radius, long delay) {
        if (!reserve(FALLING_STARS)) {
            return;
        }
        double phase = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        for (int i = 0; i < FALLING_STARS; i++) {
            double angle = phase + i * Math.PI * 2 / FALLING_STARS;
            Location high = center.clone().add(Math.cos(angle) * radius, STAR_DROP_HEIGHT, Math.sin(angle) * radius);
            TextDisplay star = keep(Displays.text(ctx(), owner, high, Text.mm("<b><" + STAR_TAG + ">✦</" + STAR_TAG
                    + "></b>"), 1.6f, delay + 8));
            Displays.outline(star, PALETTE.body());
            star.setTeleportDuration((int) Math.min(59, delay));
            Location low = high.clone();
            low.setY(center.getY() + 0.4);
            ctx().runLater(owner, 1, () -> Displays.moveTo(star, low));
            ctx().runLater(owner, delay + 1, () -> {
                if (star.isValid()) {
                    Displays.shrinkAway(ctx(), owner, star, 5);
                }
            });
        }
    }

    /** The piece of sky lands: grounded enemies grow heavy, airborne ones are hammered down. */
    private void weightFalls(Player owner, Location center, double radius) {
        World world = center.getWorld();
        Location floor = center.clone().add(0, 0.1, 0);
        Vfx.dot(floor.clone().add(0, 1, 0), Particle.FLASH, STAR, true);
        Vfx.shockwave(ctx(), owner, floor, 0.5, radius, 6, Particle.DUST_COLOR_TRANSITION, PALETTE.fade(1.6f), true);
        Vfx.shockwave(ctx(), owner, floor.clone().add(0, 0.1, 0), 0.3, radius * 0.85, 8, Particle.DUST, NAVY_DUST,
                true);
        Vfx.cloud(ctx(), floor.clone().add(0, 0.6, 0), Particle.END_ROD, 24, radius * 0.4, 0.02, null, true);
        Block ground = floor.clone().subtract(0, 0.3, 0).getBlock();
        if (!ground.isPassable()) {
            world.spawnParticle(Particle.BLOCK_CRUMBLE, floor, Vfx.n(ctx(), 16), radius * 0.4, 0.05, radius * 0.4, 0,
                    ground.getBlockData(), true);
        }
        Fx.sound(center, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 1.4f, 0.6f + Fx.jitter(0.04f));
        Fx.sound(center, Sound.BLOCK_ANVIL_LAND, 0.9f, 0.5f + Fx.jitter(0.04f));
        Fx.sound(center, Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 0.6f);

        int heavy = 0;
        int hammered = 0;
        for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius + WEIGHT_REACH_UP)) {
            Location at = enemy.getLocation();
            double flat = Math.hypot(at.getX() - center.getX(), at.getZ() - center.getZ()) - enemy.getWidth() / 2;
            double dy = at.getY() - center.getY();
            if (flat > radius || dy < -WEIGHT_REACH_DOWN || dy > WEIGHT_REACH_UP) {
                continue;
            }
            if (airborne(enemy)) {
                Combat.hit(ctx(), owner, enemy, params().num("air_damage"));
                if (Combat.isTargetable(ctx(), owner, enemy)) {
                    Motion.push(enemy, new Vector(0, -SLAM_SPEED * 1.2, 0), MAX_SPEED);
                    slamFx(owner, enemy, "<b>추락!</b>");
                }
                hammered++;
            } else {
                Combat.hit(ctx(), owner, enemy, params().num("weight_damage"));
                if (Combat.isTargetable(ctx(), owner, enemy)) {
                    makeHeavy(owner, enemy);
                }
                heavy++;
            }
        }
        if (heavy + hammered > 0) {
            owner.sendActionBar(Text.mm("<" + SKY_TAG + ">🌌 하늘의 무게</" + SKY_TAG + "> <white>" + heavy
                    + "명 짓눌림" + (hammered > 0 ? " · " + hammered + "명 추락" : "") + "</white>"));
        }
    }

    /** Heavy: no jump, slower, falling faster, and a shard of night sky pressing on the head. */
    private void makeHeavy(Player owner, LivingEntity enemy) {
        long duration = Math.max(1, params().ticks("heavy_duration"));
        double slow = Math.clamp(params().num("heavy_slow") / 100.0, 0.0, 1.0);
        ctx().addModifier(owner, enemy, Attribute.JUMP_STRENGTH, -1.0, Operation.MULTIPLY_SCALAR_1, duration);
        if (slow > 0) {
            ctx().addModifier(owner, enemy, Attribute.MOVEMENT_SPEED, -slow, Operation.MULTIPLY_SCALAR_1, duration);
        }
        ctx().addModifier(owner, enemy, Attribute.GRAVITY, 1.0, Operation.MULTIPLY_SCALAR_1, duration);
        if (enemy instanceof Player victim) {
            victim.sendActionBar(Text.mm("<" + SKY_TAG + "><b>하늘의 무게!</b></" + SKY_TAG + "> <gray>"
                    + Fx.seconds(duration) + "초간 점프할 수 없습니다</gray>"));
        }
        if (!seen(owner, enemy) || !reserve(1)) {
            return;
        }
        Location over = overHead(enemy);
        BlockDisplay shard = keep(Displays.block(ctx(), owner, over, SKY_SHARD.createBlockData(),
                Displays.transform(0, 0, 0, 0.05f, 0.05f, 0.05f, 0, 0, 0), duration + 8));
        shard.setTeleportDuration(1);
        Displays.animateNextTick(ctx(), owner, shard, Displays.transform(0, 0, 0, 0.95f, 0.14f, 0.95f, 0, 0, 0), 4);
        Loop.repeat(ctx(), owner, 1, 1, duration, run -> {
            if (!shard.isValid() || !Combat.isTargetable(ctx(), owner, enemy)) {
                return false;
            }
            Location above = overHead(enemy).subtract(0, 0.15 * Math.sin(run * 0.4), 0);
            Displays.moveTo(shard, above);
            shard.setViewRange(seen(owner, enemy) ? 1.5f : 0f);
            if (run % 3 == 0 && seen(owner, enemy)) {
                Location star = above.clone().add(Fx.jitter(0.4f), -0.1, Fx.jitter(0.4f));
                Vfx.dot(star, Particle.DUST_COLOR_TRANSITION, STAR_MOTE, false);
            }
            return true;
        }, () -> {
            if (shard.isValid()) {
                Displays.shrinkAway(ctx(), owner, shard, 4);
            }
        });
    }

    /** The globe Atlas got back is hurled at the aimed spot, where it bursts. */
    private ActivationResult throwGlobe() {
        Globe globe = held;
        if (globe == null || !globe.isValid()) {
            held = null;
            return ActivationResult.fail("짊어진 천구가 없습니다");
        }
        Player owner = player();
        double range = Math.max(1, params().num("globe_range"));
        Location target = aimGround(owner, range);
        if (target == null) {
            return ActivationResult.fail("천구를 던질 곳이 보이지 않습니다");
        }
        held = null;
        double radius = Math.max(0.5, params().num("globe_radius"));
        Location from = overHead(owner);
        Location landing = target.clone().add(0, 0.8, 0);
        double arc = 1.5 + from.distance(landing) * 0.18;
        Fx.sound(from, Sound.ENTITY_IRON_GOLEM_ATTACK, 1.0f, 0.6f + Fx.jitter(0.05f));
        Fx.sound(from, Sound.ITEM_TRIDENT_THUNDER, 0.6f, 1.4f + Fx.jitter(0.05f));
        owner.sendActionBar(Text.mm("<" + BRONZE_TAG + "><b>🌐 천구 투척!</b></" + BRONZE_TAG + ">"));
        Loop.repeat(ctx(), owner, 0, 1, THROW_TICKS, run -> {
            double t = (double) (run + 1) / THROW_TICKS;
            Location at = from.clone().add(landing.clone().subtract(from).toVector().multiply(t))
                    .add(0, 4 * arc * t * (1 - t), 0);
            globe.follow(at, run);
            if (run % 2 == 0) {
                Vfx.ring(ctx(), target.clone().add(0, 0.1, 0), radius * (1 - 0.3 * t), (int) Math.max(14, radius * 6),
                        run * 0.2, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.3f), true);
                Vfx.dot(at, Particle.END_ROD, null, false);
            }
            return true;
        }, () -> {
            globe.shatter(target.clone().add(0, 0.8, 0));
            globeImpact(owner, target, radius);
        });
        return ActivationResult.success();
    }

    private void globeImpact(Player owner, Location target, double radius) {
        Location floor = target.clone().add(0, 0.1, 0);
        Vfx.burst(ctx(), floor.clone().add(0, 0.8, 0), PALETTE, radius * 0.6, Particle.END_ROD, true);
        Vfx.shockwave(ctx(), owner, floor, 0.4, radius, 5, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.5f), true);
        Vfx.shockwave(ctx(), owner, floor.clone().add(0, 0.05, 0), 0.2, radius * 0.9, 7, Particle.DUST, BRONZE_DUST,
                true);
        Fx.sound(target, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 0.8f + Fx.jitter(0.05f));
        Fx.sound(target, Sound.BLOCK_GLASS_BREAK, 1.2f, 0.6f + Fx.jitter(0.05f));
        Fx.sound(target, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 1.2f, 0.8f);
        double damage = params().num("globe_damage");
        int hits = 0;
        for (LivingEntity enemy : ctx().enemiesNear(owner, target, radius)) {
            Combat.hit(ctx(), owner, enemy, damage);
            hits++;
            if (Combat.isTargetable(ctx(), owner, enemy)) {
                Motion.push(enemy, awayFrom(target, enemy).multiply(0.8).setY(0.45), MAX_SPEED);
            }
        }
        if (hits > 0) {
            owner.sendActionBar(Text.mm("<" + BRONZE_TAG + ">🌐 천구 투척</" + BRONZE_TAG + "> <white>" + hits
                    + "명 적중!</white>"));
        }
    }

    /**
     * Where a thrown thing lands: under the aimed enemy, else on top of the first block the view ray meets (or the
     * ground below where it ends), null if there is no ground there.
     */
    private @Nullable Location aimGround(Player owner, double range) {
        LivingEntity aimed = ctx().findTarget(owner, range);
        Location point;
        if (aimed != null) {
            point = aimed.getLocation();
        } else {
            Location eye = owner.getEyeLocation();
            Vector direction = eye.getDirection();
            RayTraceResult hit = owner.getWorld().rayTraceBlocks(eye, direction, range, FluidCollisionMode.NEVER, true);
            point = hit == null ? eye.clone().add(direction.clone().multiply(range))
                    : hit.getHitPosition().toLocation(owner.getWorld()).subtract(direction.clone().multiply(0.3));
        }
        Location ground = point.clone().add(0, 0.5, 0);
        double before = ground.getY();
        Spots.snapToGround(ground.getWorld(), ground, 8);
        if (ground.getY() >= before - 1.0E-6 && !Spots.hasGroundBelow(ground.getWorld(), ground)) {
            return null;
        }
        ground.setYaw(0);
        ground.setPitch(0);
        return ground;
    }

    // ------------------------------------------------------------------ ultimate: handing over the sky

    @Override
    public ActivationResult onUltimate() {
        if (sky != null) {
            return ActivationResult.fail("하늘이 아직 누군가의 어깨 위에 있습니다");
        }
        if (held != null) {
            return ActivationResult.fail("이미 천구를 짊어지고 있습니다");
        }
        Player owner = player();
        double range = Math.max(1, params().num("sky_range"));
        LivingEntity target = ctx().findTarget(owner, range);
        if (target == null) {
            target = nearestVisible(owner, range);
        }
        if (target == null) {
            return ActivationResult.fail(AbilityInfo.formatNumber(range) + "블록 안에 하늘을 떠넘길 적이 없습니다");
        }
        Sky handed = new Sky(owner);
        sky = handed;
        handed.hurl(target);
        return ActivationResult.success();
    }

    /** The nearest enemy the owner can see (not hidden, in line of sight), null if none. */
    private @Nullable LivingEntity nearestVisible(Player owner, double range) {
        for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), range)) {
            if (seen(owner, enemy) && Spots.clearPath(owner.getEyeLocation(), Fx.body(enemy))) {
                return enemy;
            }
        }
        return null;
    }

    /**
     * The sky as a hot potato. It is hurled at the target in an arc; whoever carries it is slowed and hurt more every
     * second, and passes it on by hitting another player with anything (a scoped damage listener) or by bumping into
     * them (checked every tick). The one who just passed it cannot get it back for {@code pass_immunity}. Atlas himself
     * takes it lightly: the sky ends there and his next active throws the globe. When the time is up it collapses on
     * the bearer.
     */
    private final class Sky implements Listener {

        private final Player owner;
        private final long duration = Math.max(20, params().ticks("sky_duration"));
        private final long immunity = params().ticks("pass_immunity");
        private final Globe globe;
        private @Nullable LivingEntity bearer;
        private @Nullable NamespacedKey slowKey;
        private @Nullable TextDisplay countdown;
        private @Nullable UUID lastGiver;
        private long giverUntil = Long.MIN_VALUE / 2;
        private long start = -1;
        private int pulses;
        private int passes;
        private boolean registered;
        private boolean done;

        Sky(Player owner) {
            this.owner = owner;
            long lifetime = params().ticks("throw_time") + duration + params().ticks("globe_hold") + GLOBE_MARGIN;
            this.globe = new Globe(owner, overHead(owner), lifetime);
        }

        /** The globe flies in an arc onto the target's head (following it as it moves). */
        void hurl(LivingEntity target) {
            long flight = Math.max(4, params().ticks("throw_time"));
            Location from = overHead(owner);
            double arc = 2.0 + from.distance(target.getLocation()) * 0.15;
            Fx.sound(from, Sound.ENTITY_IRON_GOLEM_ATTACK, 1.2f, 0.5f);
            Fx.sound(from, Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 0.7f);
            Fx.sound(from, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 0.6f);
            Vfx.cloud(ctx(), from, Particle.END_ROD, 12, 0.4, 0.06, null, true);
            Loop.repeat(ctx(), owner, 0, 1, flight, run -> {
                if (done) {
                    return false;
                }
                if (!Combat.isTargetable(ctx(), owner, target)) {
                    fizzle(globe.at());
                    return false;
                }
                double t = (double) (run + 1) / flight;
                Location to = overHead(target);
                Location at = from.clone().add(to.clone().subtract(from).toVector().multiply(t))
                        .add(0, 4 * arc * t * (1 - t), 0);
                globe.follow(at, run);
                globe.setShown(seen(owner, target));
                if (run % 2 == 0) {
                    Vfx.dot(at, Particle.END_ROD, null, true);
                    Vfx.ring(ctx(), target.getLocation().add(0, 0.1, 0), 0.9 - 0.4 * t, 10, run * 0.4,
                            Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.0f), false);
                }
                return true;
            }, () -> {
                if (done) {
                    return;
                }
                if (!Combat.isTargetable(ctx(), owner, target)) {
                    fizzle(globe.at());
                    return;
                }
                start = ctx().currentTick();
                Bukkit.getPluginManager().registerEvents(this, ctx().plugin());
                registered = true;
                if (reserve(1)) {
                    TextDisplay timer = keep(Displays.text(ctx(), owner, overHead(target).add(0, 1.0, 0),
                            countdownText(duration, duration), 0.9f, duration + GLOBE_MARGIN));
                    timer.setTeleportDuration(2);
                    countdown = timer;
                }
                takeOn(target, owner);
                ctx().announce(Text.mm("<" + SKY_TAG + ">🌌</" + SKY_TAG + "> <white><target></white><gray>의 어깨에 "
                        + "하늘이 내려앉았습니다!</gray> <" + STAR_TAG + ">맞히거나 부딪히면 하늘이 넘어갑니다</" + STAR_TAG
                        + ">", Placeholder.component("target", target.name())));
                Loop.repeat(ctx(), owner, 0, 1, duration + 1, this::step);
            });
        }

        /** One tick of the sky on someone's shoulders. False once it is over. */
        private boolean step(long tick) {
            if (done) {
                return false;
            }
            LivingEntity current = bearer;
            if (current == null || !Combat.isTargetable(ctx(), owner, current)) {
                fizzle(globe.at());
                return false;
            }
            long elapsed = ctx().currentTick() - start;
            int total = (int) (duration / 20);
            while (pulses < total && elapsed >= (pulses + 1) * 20L) {
                double damage = Math.max(0, params().num("sky_tick") + params().num("sky_tick_growth") * pulses);
                pulses++;
                pulse(current, damage);
                if (done || !Combat.isTargetable(ctx(), owner, current)) {
                    if (!done) {
                        fizzle(globe.at());
                    }
                    return false;
                }
            }
            if (elapsed >= duration) {
                collapse(current);
                return false;
            }
            Player touched = touching(current);
            if (touched != null) {
                pass(touched);
                if (done) {
                    return false;
                }
                current = bearer;
                if (current == null) {
                    return false;
                }
            }
            boolean shown = seen(owner, current);
            globe.follow(overHead(current), tick);
            globe.setShown(shown);
            TextDisplay timer = countdown;
            if (timer != null && timer.isValid()) {
                Displays.moveTo(timer, globe.at().clone().add(0, 0.95, 0));
                timer.setViewRange(shown ? 1.5f : 0f);
                if (tick % 2 == 0) {
                    timer.text(countdownText(duration - elapsed, duration));
                }
            }
            if (shown && tick % 2 == 0) {
                // The sky presses down: stars drizzle around the bearer.
                Location around = Vfx.scatter(current.getLocation(), 0.7).add(0, current.getHeight() + 0.3, 0);
                Vfx.trail(around, around.clone().subtract(0, current.getHeight() + 0.2, 0), STAR, 8, false);
            }
            if (current instanceof Player carrier && tick % 10 == 0) {
                carrier.sendActionBar(Text.mm("<" + SKY_TAG + "><b>🌌 하늘을 짊어졌습니다</b></" + SKY_TAG
                        + "> <white>" + Fx.seconds(duration - elapsed) + "초</white> <gray>· 맞히거나 부딪혀 "
                        + "넘기세요</gray>"));
            }
            return true;
        }

        /** The damage of one second under the sky, heavier every time. */
        private void pulse(LivingEntity current, double damage) {
            Combat.hit(ctx(), owner, current, damage);
            Location body = Fx.body(current);
            float pitch = (float) Math.max(0.5, 1.0 - 0.1 * pulses);
            Fx.sound(body, Sound.BLOCK_ANVIL_LAND, 0.5f, pitch + Fx.jitter(0.03f));
            Fx.sound(body, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1.0f, 0.5f + 0.1f * pulses);
            if (seen(owner, current)) {
                Vfx.ring(ctx(), current.getLocation().add(0, 0.1, 0), 0.9, 12, pulses * 0.5,
                        Particle.DUST_COLOR_TRANSITION, PALETTE.fade(1.1f), false);
                Vfx.cloud(ctx(), body, Particle.END_ROD, 3, 0.3, 0.02, null, false);
            }
        }

        /**
         * A player whose body touches the bearer's and who may take the sky, null if none. Never Atlas himself: he
         * could simply walk into the slowed bearer and turn his next active into the globe throw; the bearer has to
         * hit him to give it back.
         */
        private @Nullable Player touching(LivingEntity current) {
            BoundingBox box = current.getBoundingBox().expand(CONTACT_SLACK);
            for (Player player : ctx().alivePlayers()) {
                if (!player.equals(owner) && receives(player) && box.overlaps(player.getBoundingBox())) {
                    return player;
                }
            }
            return null;
        }

        /** True if {@code player} may take the sky now: Atlas or one of his enemies, but not straight back. */
        private boolean receives(Player player) {
            LivingEntity current = bearer;
            if (current == null || player.equals(current) || !player.getWorld().equals(current.getWorld())) {
                return false;
            }
            if (player.getUniqueId().equals(lastGiver) && ctx().currentTick() < giverUntil) {
                return false;
            }
            return player.equals(owner) ? ctx().isAlive(owner) : ctx().isEnemy(owner, player);
        }

        /** The bearer hit another player with anything: the sky goes to them. */
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onHit(EntityDamageByEntityEvent event) {
            if (done || bearer == null) {
                return;
            }
            try {
                LivingEntity current = bearer;
                Entity damager = event.getDamager();
                boolean byBearer = damager.equals(current)
                        || current instanceof Player carrier && carrier.equals(ctx().resolveAttacker(damager));
                if (byBearer && event.getEntity() instanceof Player victim && receives(victim)) {
                    pass(victim);
                }
            } catch (RuntimeException e) {
                ctx().plugin().getLogger().log(Level.WARNING, "Atlas sky pass failed", e);
            }
        }

        private void pass(Player to) {
            LivingEntity from = bearer;
            if (from == null) {
                return;
            }
            passes++;
            if (from instanceof Player giver) {
                giver.sendActionBar(Text.mm("<" + STAR_TAG + ">하늘을 떠넘겼습니다!</" + STAR_TAG + ">"));
            }
            globe.hopFrom(globe.at());
            Location at = Fx.body(to);
            Fx.sound(at, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 0.7f + Fx.jitter(0.05f));
            Fx.sound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.2f, 0.8f + Fx.jitter(0.1f));
            if (seen(owner, to) && seen(owner, from)) {
                Vfx.trail(Fx.body(from), at, STAR, 6, true);
            }
            if (to.equals(owner)) {
                catchIt();
                return;
            }
            takeOn(to, from);
        }

        /** {@code to} now carries the sky (slowed); {@code from} cannot get it back for a moment. */
        private void takeOn(LivingEntity to, LivingEntity from) {
            LivingEntity previous = bearer;
            NamespacedKey key = slowKey;
            if (previous != null && key != null) {
                ctx().removeModifier(previous, key);
            }
            bearer = to;
            lastGiver = from.getUniqueId();
            giverUntil = ctx().currentTick() + immunity;
            long left = Math.max(1, duration - Math.max(0, ctx().currentTick() - start));
            double slow = Math.clamp(params().num("sky_slow") / 100.0, 0.0, 1.0);
            slowKey = slow > 0 ? ctx().addModifier(owner, to, Attribute.MOVEMENT_SPEED, -slow,
                    Operation.MULTIPLY_SCALAR_1, left + 5) : null;
            Location head = overHead(to);
            Fx.sound(head, Sound.BLOCK_ANVIL_LAND, 0.8f, 0.7f + Fx.jitter(0.05f));
            Fx.sound(head, Sound.BLOCK_HEAVY_CORE_PLACE, 1.0f, 0.6f);
            if (seen(owner, to)) {
                Vfx.cloud(ctx(), head, Particle.END_ROD, 10, 0.4, 0.05, null, true);
                Vfx.ring(ctx(), to.getLocation().add(0, 0.1, 0), 1.0, 14, 0, Particle.DUST, NAVY_DUST, true);
            }
            if (to instanceof Player carrier) {
                carrier.sendActionBar(Text.mm("<" + SKY_TAG + "><b>🌌 하늘을 짊어졌습니다!</b></" + SKY_TAG
                        + "> <gray>다른 플레이어를 맞히거나 부딪혀 넘기세요</gray>"));
            }
        }

        /** Atlas takes the sky back lightly: the globe stays on his shoulders and the next active throws it. */
        private void catchIt() {
            LivingEntity previous = bearer;
            NamespacedKey key = slowKey;
            if (previous != null && key != null) {
                ctx().removeModifier(previous, key);
            }
            slowKey = null;
            bearer = null;
            held = globe;
            heldUntil = ctx().currentTick() + Math.max(20, params().ticks("globe_hold"));
            ctx().reduceCooldown(owner, Math.max(0, ctx().cooldownRemaining(owner)) + 1);
            Location head = overHead(owner);
            Vfx.burst(ctx(), head, BRONZE, 0.6, Particle.END_ROD, true);
            Fx.sound(head, Sound.BLOCK_BEACON_POWER_SELECT, 1.0f, 1.2f);
            Fx.sound(head, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);
            owner.sendActionBar(Text.mm("<" + BRONZE_TAG + "><b>🌐 하늘을 되찾았습니다!</b></" + BRONZE_TAG
                    + "> <gray>액티브로 천구를 던지세요</gray>"));
            release();
        }

        /** Time is up: the sky collapses on whoever holds it. */
        private void collapse(LivingEntity current) {
            Location at = current.getLocation();
            Location top = overHead(current);
            boolean shown = seen(owner, current);
            Combat.hit(ctx(), owner, current, params().num("collapse_damage"));
            double radius = Math.max(0, params().num("collapse_radius"));
            double splash = params().num("collapse_splash");
            for (LivingEntity other : ctx().enemiesNear(owner, at, radius)) {
                if (!other.equals(current)) {
                    Combat.hit(ctx(), owner, other, splash);
                    if (Combat.isTargetable(ctx(), owner, other)) {
                        Motion.push(other, awayFrom(at, other).multiply(0.5).setY(0.25), MAX_SPEED);
                    }
                }
            }
            Fx.sound(at, Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 0.6f + Fx.jitter(0.05f));
            Fx.sound(at, Sound.BLOCK_ANVIL_LAND, 1.2f, 0.5f);
            Fx.sound(at, Sound.BLOCK_GLASS_BREAK, 1.3f, 0.5f + Fx.jitter(0.05f));
            Fx.sound(at, Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 0.5f);
            if (shown) {
                Location floor = at.clone().add(0, 0.1, 0);
                Vfx.dot(top, Particle.FLASH, STAR, true);
                Vfx.shockwave(ctx(), owner, floor, 0.4, Math.max(1, radius), 6, Particle.DUST_COLOR_TRANSITION,
                        PALETTE.fade(1.5f), true);
                ThreadLocalRandom random = ThreadLocalRandom.current();
                for (int i = 0; i < Vfx.n(ctx(), 12); i++) {
                    Location high = Vfx.scatter(at, Math.max(1, radius)).add(0, 5 + random.nextDouble() * 2, 0);
                    Vfx.trail(high, new Location(at.getWorld(), high.getX(), at.getY(), high.getZ()),
                            random.nextBoolean() ? STAR : PALETTE.body(), 8 + random.nextInt(6), true);
                }
                popText(top.clone().add(0, 0.4, 0), "<gradient:#F2F4FF:#4A6BC0><b>하늘이 무너졌다!</b></gradient>");
            }
            globe.shatter(top);
            owner.sendActionBar(Text.mm("<" + SKY_TAG + ">🌌 하늘이 무너졌습니다</" + SKY_TAG + "> <gray>"
                    + passes + "번 넘겨짐</gray>"));
            release();
        }

        /** The bearer is gone before the time was up: the sky drifts away. */
        private void fizzle(Location at) {
            globe.rise();
            Fx.sound(at, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.0f, 1.2f);
            release();
        }

        /** Ends the sky: the listener goes, the slow comes off (also when Atlas's ability is revoked). */
        void release() {
            done = true;
            if (registered) {
                HandlerList.unregisterAll(this);
                registered = false;
            }
            LivingEntity current = bearer;
            NamespacedKey key = slowKey;
            if (current != null && key != null) {
                ctx().removeModifier(current, key);
            }
            slowKey = null;
            bearer = null;
            TextDisplay timer = countdown;
            if (timer != null && timer.isValid()) {
                timer.remove();
            }
            countdown = null;
            if (sky == this) {
                sky = null;
            }
        }
    }

    /** The seconds left, white turning amber then red as the sky's {@code total} time runs out. */
    private static net.kyori.adventure.text.Component countdownText(long ticksLeft, long total) {
        double fraction = Math.clamp((double) ticksLeft / Math.max(1, total), 0.0, 1.0);
        String color = fraction > 0.6 ? STAR_TAG : fraction > 0.3 ? "#FFD27F" : "#FF5A4E";
        return Text.mm("<b><" + color + ">" + Fx.seconds(ticksLeft) + "</" + color + "></b>");
    }

    /**
     * The celestial globe: a lapis core in a blue glass shell, a tilted ring of bronze bars and a few star sprites. Every
     * part is spawned unrotated at the globe's center (orientation lives in the transformations) and moved with
     * {@link Displays#moveTo}. The core and shell always come (they are the sky); the ring and the stars only as far as
     * the display budget allows.
     */
    private final class Globe {

        private final Player owner;
        private final List<BlockDisplay> parts = new ArrayList<>();
        private final List<TextDisplay> stars = new ArrayList<>();
        private final Vector3f axis = new Vector3f(0.3f, 1f, 0.15f).normalize();
        private Location at;
        private @Nullable Location hopFrom;
        private long hopTick;
        private boolean shown = true;

        Globe(Player owner, Location at, long lifetime) {
            this.owner = owner;
            this.at = Displays.unrotated(at);
            BlockDisplay core = keep(Displays.block(ctx(), owner, this.at, GLOBE_CORE.createBlockData(),
                    cube(0.05f, new Quaternionf()), lifetime));
            BlockDisplay shell = keep(Displays.block(ctx(), owner, this.at, GLOBE_SHELL.createBlockData(),
                    cube(0.05f, new Quaternionf()), lifetime));
            parts.add(core);
            parts.add(shell);
            boolean ring = reserve(RING_BARS);
            for (int i = 0; ring && i < RING_BARS; i++) {
                parts.add(keep(Displays.block(ctx(), owner, this.at, GLOBE_RING.createBlockData(), ringBar(i, 0.05f),
                        lifetime)));
            }
            for (int i = 0; i < GLOBE_STARS && reserve(1); i++) {
                TextDisplay star = keep(Displays.text(ctx(), owner, this.at, Text.mm("<" + STAR_TAG + ">✦</" + STAR_TAG
                        + ">"), 0.6f, lifetime));
                stars.add(star);
            }
            for (BlockDisplay part : parts) {
                part.setTeleportDuration(2);
                part.setViewRange(2.0f);
            }
            for (TextDisplay star : stars) {
                star.setTeleportDuration(2);
            }
            ctx().runLater(owner, 1, () -> {
                Displays.animate(core, cube(CORE_SIZE, new Quaternionf()), 4);
                Displays.animate(shell, cube(SHELL_SIZE, new Quaternionf().rotateY(0.6f).rotateX(0.5f)), 4);
                for (int i = 0; i < RING_BARS && 2 + i < parts.size(); i++) {
                    Displays.animate(parts.get(2 + i), ringBar(i, 1f), 4);
                }
            });
        }

        Location at() {
            return at.clone();
        }

        boolean isValid() {
            return parts.stream().anyMatch(Display::isValid);
        }

        /** Starts a short hop from {@code from} to wherever the globe follows next. */
        void hopFrom(Location from) {
            hopFrom = from.clone();
            hopTick = 0;
        }

        /** Moves the globe to {@code center} (via a short arc after a hop), spinning slowly. */
        void follow(Location center, long tick) {
            Location target = center;
            Location from = hopFrom;
            if (from != null && from.getWorld() == center.getWorld()) {
                hopTick++;
                double t = Math.min(1.0, (double) hopTick / HOP_TICKS);
                target = from.clone().add(center.clone().subtract(from).toVector().multiply(t))
                        .add(0, 4 * HOP_ARC * t * (1 - t), 0);
                if (t >= 1.0) {
                    hopFrom = null;
                }
            }
            at = Displays.unrotated(target);
            for (BlockDisplay part : parts) {
                Displays.moveTo(part, at);
            }
            for (int i = 0; i < stars.size(); i++) {
                double angle = tick * 0.09 + i * Math.PI * 2 / stars.size();
                double y = Math.sin(tick * 0.13 + i * 2.1) * 0.35;
                Displays.moveTo(stars.get(i), at.clone().add(Math.cos(angle) * 0.75, y, Math.sin(angle) * 0.75));
            }
            if (tick % SPIN_PERIOD == 0 && parts.size() >= 2) {
                float angle = tick * 0.06f;
                Displays.animate(parts.get(0), cube(CORE_SIZE, new Quaternionf().rotateAxis(angle, axis)),
                        (int) SPIN_PERIOD);
                Displays.animate(parts.get(1), cube(SHELL_SIZE, new Quaternionf().rotateAxis(-angle * 0.7f, axis)
                        .rotateY(0.6f).rotateX(0.5f)), (int) SPIN_PERIOD);
            }
            if (shown && tick % 3 == 0) {
                Vfx.dot(at.clone().add(Fx.jitter(0.6f), Fx.jitter(0.6f), Fx.jitter(0.6f)), Particle.DUST_COLOR_TRANSITION,
                        STAR_MOTE, false);
            }
        }

        void setShown(boolean visible) {
            if (visible == shown) {
                return;
            }
            shown = visible;
            for (BlockDisplay part : parts) {
                part.setViewRange(visible ? 2.0f : 0f);
            }
            for (TextDisplay star : stars) {
                star.setViewRange(visible ? 1.5f : 0f);
            }
        }

        /** The globe bursts: the shell flashes out, the ring bars fly apart, the stars scatter. */
        void shatter(Location where) {
            at = Displays.unrotated(where);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < parts.size(); i++) {
                BlockDisplay part = parts.get(i);
                if (!part.isValid()) {
                    continue;
                }
                Displays.moveTo(part, at);
                if (i < 2) {
                    Displays.animate(part, cube(i == 0 ? 0.1f : 2.4f, new Quaternionf().rotateY(1.2f)), 3);
                    ctx().runLater(owner, 3, () -> {
                        if (part.isValid()) {
                            Displays.shrinkAway(ctx(), owner, part, 3);
                        }
                    });
                } else {
                    part.setTeleportDuration(6);
                    double angle = random.nextDouble(Math.PI * 2);
                    Location away = at.clone().add(Math.cos(angle) * 2.2, random.nextDouble(-0.3, 1.2),
                            Math.sin(angle) * 2.2);
                    ctx().runLater(owner, 1, () -> Displays.moveTo(part, away));
                    ctx().runLater(owner, 6, () -> {
                        if (part.isValid()) {
                            Displays.shrinkAway(ctx(), owner, part, 4);
                        }
                    });
                }
            }
            for (TextDisplay star : stars) {
                if (star.isValid()) {
                    Displays.shrinkAway(ctx(), owner, star, 4);
                }
            }
            Vfx.cloud(ctx(), at, Particle.END_ROD, 20, 0.5, 0.15, null, true);
            Vfx.cloud(ctx(), at, Particle.BLOCK, 14, 0.5, 0.1, GLOBE_SHELL.createBlockData(), false);
        }

        /** The globe floats back up into the sky and fades. */
        void rise() {
            for (BlockDisplay part : parts) {
                if (part.isValid()) {
                    part.setTeleportDuration(10);
                }
            }
            for (TextDisplay star : stars) {
                if (star.isValid()) {
                    star.setTeleportDuration(10);
                }
            }
            Location up = at.clone().add(0, 3.5, 0);
            ctx().runLater(owner, 1, () -> {
                for (BlockDisplay part : parts) {
                    Displays.moveTo(part, up);
                }
                for (TextDisplay star : stars) {
                    Displays.moveTo(star, up);
                }
            });
            ctx().runLater(owner, 8, () -> {
                for (BlockDisplay part : parts) {
                    if (part.isValid()) {
                        Displays.shrinkAway(ctx(), owner, part, 4);
                    }
                }
                for (TextDisplay star : stars) {
                    if (star.isValid()) {
                        Displays.shrinkAway(ctx(), owner, star, 4);
                    }
                }
            });
            Vfx.cloud(ctx(), at, Particle.END_ROD, 8, 0.4, 0.03, null, false);
        }

        /** Ring bar {@code index} of the tilted armillary ring around the globe's center. */
        private Transformation ringBar(int index, float grow) {
            double angle = Math.PI * 2 * (index + 0.5) / RING_BARS;
            float length = (float) (2 * RING_RADIUS * Math.tan(Math.PI / RING_BARS)) * 1.08f * grow;
            Quaternionf tilt = new Quaternionf().rotateX((float) Math.toRadians(RING_TILT));
            Quaternionf rotation = new Quaternionf(tilt).mul(new Quaternionf().rotateY((float) -(angle + Math.PI / 2)));
            Vector3f center = tilt.transform(new Vector3f((float) Math.cos(angle) * RING_RADIUS * grow, 0,
                    (float) Math.sin(angle) * RING_RADIUS * grow));
            float thick = 0.07f * Math.max(0.05f, grow);
            return box(center, new Vector3f(length, thick, thick), rotation);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Location overHead(LivingEntity entity) {
        return Displays.unrotated(entity.getLocation().add(0, entity.getHeight() + OVER_HEAD, 0));
    }

    /** The horizontal direction from {@code from} to the entity (a random one if it stands right there). */
    private static Vector awayFrom(Location from, LivingEntity entity) {
        Vector away = Motion.horizontal(entity.getLocation().toVector().subtract(from.toVector()));
        if (away.lengthSquared() < 1.0E-6) {
            double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
            away = new Vector(Math.cos(angle), 0, Math.sin(angle));
        }
        return away;
    }

    private void popText(Location at, String mini) {
        if (!reserve(1)) {
            return;
        }
        Player owner = player();
        TextDisplay text = keep(Displays.text(ctx(), owner, at, Text.mm(mini), 0.5f, 18));
        Displays.animateNextTick(ctx(), owner, text, Displays.centered(0, 0.35f, 0, 1.1f, 1.1f, 1.1f, 0, 0, 0), 4);
        ctx().runLater(owner, 12, () -> {
            if (text.isValid()) {
                Displays.shrinkAway(ctx(), owner, text, 5);
            }
        });
    }

    /** True unless {@code entity} is a player hidden from {@code owner} (stealth): nothing may reveal it. */
    private static boolean seen(Player owner, Entity entity) {
        return !(entity instanceof Player hidden) || hidden.equals(owner) || owner.canSee(hidden);
    }

    private boolean reserve(int count) {
        live.removeIf(display -> !display.isValid());
        return live.size() + count <= MAX_DISPLAYS;
    }

    private <T extends Display> T keep(T display) {
        live.add(display);
        return display;
    }

    /** A cube of edge {@code size} centered on the block display's origin, rotated around its center. */
    private static Transformation cube(float size, Quaternionf rotation) {
        Vector3f translation = new Quaternionf(rotation).transform(new Vector3f(size / 2)).negate();
        return new Transformation(translation, new Quaternionf(rotation), new Vector3f(size), new Quaternionf());
    }

    /** A box of {@code size} centered on {@code center} (relative to the display), turned by {@code rotation}. */
    private static Transformation box(Vector3f center, Vector3f size, Quaternionf rotation) {
        Vector3f translation = new Vector3f(center).sub(new Quaternionf(rotation).transform(new Vector3f(size).mul(0.5f)));
        return new Transformation(translation, new Quaternionf(rotation), new Vector3f(size), new Quaternionf());
    }
}
