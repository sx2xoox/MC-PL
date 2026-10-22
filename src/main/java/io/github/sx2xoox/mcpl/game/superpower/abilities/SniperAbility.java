package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 저격수 — a long-range marksman. Hits from afar land harder, the active is a telegraphed hitscan shot (a red aiming
 * laser everyone can see, then a single bullet that rewards headshots), and the ultimate marks every enemy on the
 * field: they glow, lose armor and take more damage from the sniper, who fires much faster while the mark lasts.
 *
 * <p>Look: the aiming laser is a thin red beam display with a glowing outline from the rifle hand to wherever the
 * crosshair rests, a red dot at its end and a lock-on ring closing around an enemy under it; it flickers white just
 * before the shot. The shot is a white-hot tracer that thins out, a muzzle blast ring, a lingering smoke line and a
 * red burst at the impact ("헤드샷!" pops over the head). The mark sweeps a radar ring out from the sniper and pins a
 * red "◎" reticle over every target for its duration.
 */
public final class SniperAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("sniper", "저격수", Material.SPYGLASS)
            .color(TextColor.color(0xFF4D4D))
            .difficulty(3)
            .passive("원거리 정밀",
                    "{bonus_start}블록보다 먼 적에게 주는 투사체·능력 피해가 1블록마다 {bonus_per_block}% 늘어납니다"
                            + "(최대 {bonus_max}%).")
            .active("정조준 사격",
                    "{aim_time}초간 레이저로 조준한 뒤(조준 중 이동 속도 -{aim_slow}%, 레이저는 모두에게 보임) 바라보는 "
                            + "방향으로 탄을 쏴 {shot_range}블록 안에서 처음 맞은 적에게 {shot_damage} 피해를 줍니다. "
                            + "헤드샷이면 피해가 {headshot_bonus}% 늘고 쿨타임이 {headshot_refund}초 줄어듭니다.",
                    8)
            .ultimate("표적 지정",
                    "{mark_range}블록 안의 모든 적을 {mark_duration}초간 표적으로 지정합니다. 표적은 발광하고 방어력이 "
                            + "{mark_armor} 줄며 저격수에게 받는 피해가 {mark_bonus}% 늘어납니다. 그동안 정조준 사격은 즉시 "
                            + "준비되고 조준 시간이 {mark_aim_time}초, 쿨타임이 {mark_cooldown_cut}% 줄어듭니다.")
            .ultimateNotStandalone()
            .param("bonus_start", 8)
            .param("bonus_per_block", 2.5)
            .param("bonus_max", 30)
            .param("aim_time", 1)
            .param("aim_slow", 50)
            .param("shot_range", 60)
            .param("shot_damage", 5)
            .param("headshot_bonus", 40)
            .param("headshot_refund", 2)
            .param("mark_range", 60)
            .param("mark_duration", 6)
            .param("mark_armor", 4)
            .param("mark_bonus", 20)
            .param("mark_aim_time", 0.5)
            .param("mark_cooldown_cut", 70)
            .build();

    private static final String COLOR_TAG = "#FF4D4D";
    /** White-hot core, laser red body, dark crimson edge. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFFFFF, 0xFF3B3B, 0x7A0010);
    private static final Particle.DustOptions LASER_DOT = new Particle.DustOptions(PALETTE.body(), 1.3f);
    private static final Particle.DustOptions LASER_FINE = new Particle.DustOptions(PALETTE.body(), 0.5f);
    private static final Particle.DustTransition HOT = PALETTE.glow(0.9f);
    private static final Particle.DustTransition EMBER = PALETTE.fade(0.7f);
    private static final BlockData LASER_BLOCK = Material.RED_STAINED_GLASS.createBlockData();
    private static final BlockData LASER_HOT_BLOCK = Material.WHITE_CONCRETE.createBlockData();
    private static final BlockData TRACER_BLOCK = Material.WHITE_CONCRETE.createBlockData();
    private static final BlockData RADAR_BLOCK = Material.RED_STAINED_GLASS.createBlockData();

    /** Hard cap for every velocity this ability applies. */
    private static final double MAX_SPEED = 2.5;
    /** Hitbox inflation of the shot (and the laser), a little forgiveness for the aim. */
    private static final double SHOT_RAY = 0.1;
    /** The head is the top part of the body starting this share of the body height below the eyes. */
    private static final double HEAD_SHARE = 0.17;

    // active: aimed shot
    private static final float LASER_WIDTH = 0.03f;
    private static final float LASER_WIDTH_LOCKED = 0.055f;
    /** The laser flickers white over the last ticks of the aim: the shot is coming. */
    private static final long FLICKER_TICKS = 6;
    private static final long BEEP_PERIOD = 5;
    private static final float TRACER_WIDTH = 0.11f;
    private static final int TRACER_FADE_TICKS = 4;
    private static final double SHOT_PUSH = 0.35;
    private static final double RECOIL = 0.18;
    private static final int HEADSHOT_LABEL_TICKS = 18;
    private static final int RANGE_LABEL_TICKS = 16;
    private static final long RANGE_FX_COOLDOWN = 4;
    /** Particle budget of the long lines (the tracer and the laser), whatever the range. */
    private static final int MAX_LINE_POINTS = 90;

    // ultimate: mark
    private static final int RADAR_BARS = 8;
    private static final int RADAR_TICKS = 10;
    private static final double RADAR_START = 0.8;
    private static final double RADAR_END = 5.5;
    /** At most this many reticle labels exist at once (the glow marks everyone, the labels the closest). */
    private static final int MAX_LABELS = 12;
    private static final int LOCK_ON_TICKS = 6;

    /** The aim in progress, null when the rifle is idle. */
    private @Nullable Aim aim;
    /** Enemies marked by the ultimate, by entity id. */
    private final Map<UUID, Mark> marks = new LinkedHashMap<>();
    private long markEnd = -1;
    /** Generation of the mark, so a stale end timer never ends a newer mark. */
    private int markToken;
    /** The target of the ability hit being dealt right now, and whether it landed (see {@link #onDamageDealt}). */
    private @Nullable LivingEntity pendingTarget;
    private boolean landed;
    private long lastRangeFx = Long.MIN_VALUE / 2;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        aim = null;
        // The mark dies with the sniper: the glow and the armor loss go with it.
        for (Mark mark : marks.values()) {
            mark.release();
        }
        marks.clear();
        markEnd = -1;
    }

    @Override
    public double cooldownMultiplier() {
        if (!marking()) {
            return 1.0;
        }
        return 1.0 - Math.clamp(params().num("mark_cooldown_cut") / 100.0, 0.0, 0.95);
    }

    private boolean marking() {
        return markEnd >= 0 && ctx().currentTick() < markEnd;
    }

    // ------------------------------------------------------------------ passive: long-range precision

    /** Only changes the damage: the range bonus for ranged hits and the mark bonus for marked targets. */
    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled() || !ctx().isEnemy(player(), victim)) {
            return;
        }
        double bonus = 0;
        if (isRangedHit(event)) {
            bonus += rangeBonus(victim);
        }
        if (isMarked(victim)) {
            bonus += Math.max(0, params().num("mark_bonus")) / 100.0;
        }
        if (bonus > 0) {
            event.setDamage(event.getDamage() * (1 + bonus));
        }
    }

    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (ctx().isAbilityDamage() && victim.equals(pendingTarget)) {
            landed = true;
        }
        if (isRangedHit(event) && ctx().isEnemy(player(), victim)) {
            double bonus = rangeBonus(victim);
            if (bonus > 0) {
                rangeFx(victim, bonus);
            }
        }
    }

    /**
     * A long-range hit landed: the distance pops up over the target in red ("32m"), a thin crimson ring snaps around
     * it and the sniper sees the bonus it earned.
     */
    private void rangeFx(LivingEntity victim, double bonus) {
        long now = ctx().currentTick();
        if (now - lastRangeFx < RANGE_FX_COOLDOWN) {
            return;
        }
        lastRangeFx = now;
        Player owner = player();
        long meters = Math.round(victim.getLocation().distance(owner.getLocation()));
        Location crown = victim.getLocation().add(0, victim.getHeight() + 0.3, 0);
        TextDisplay label = Displays.text(ctx(), owner, crown,
                Text.mm("<" + COLOR_TAG + ">" + meters + "m</" + COLOR_TAG + ">"), 0.45f, RANGE_LABEL_TICKS);
        // Rises only a little: a headshot label may pop up above it.
        Displays.animateNextTick(ctx(), owner, label, Displays.centered(0, 0.15f, 0, 0.8f, 0.8f, 0.8f, 0, 0, 0), 8);
        ctx().runLater(owner, RANGE_LABEL_TICKS - 5, () -> {
            if (label.isValid()) {
                Displays.shrinkAway(ctx(), owner, label, 4);
            }
        });
        Vfx.ring(ctx(), Fx.body(victim), 0.7, 14, ThreadLocalRandom.current().nextDouble(Math.PI), Particle.DUST,
                LASER_FINE, false);
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">⌖ " + meters + "m</" + COLOR_TAG + "> <gray>원거리 +"
                + AbilityInfo.formatNumber(Math.round(bonus * 1000) / 10.0) + "%</gray>"));
    }

    /** Ability damage dealt by the sniper, or a projectile it shot; melee swings never count. */
    private boolean isRangedHit(EntityDamageByEntityEvent event) {
        if (ctx().isAbilityDamage()) {
            return player().equals(event.getDamager());
        }
        if (event.getDamager() instanceof Projectile projectile) {
            return player().equals(projectile.getShooter());
        }
        return false;
    }

    /** The range bonus as a fraction: grows per block beyond {@code bonus_start}, capped at {@code bonus_max}. */
    private double rangeBonus(LivingEntity victim) {
        if (!victim.getWorld().equals(player().getWorld())) {
            return 0;
        }
        double distance = victim.getLocation().distance(player().getLocation());
        double percent = (distance - params().num("bonus_start")) * params().num("bonus_per_block");
        return Math.clamp(percent, 0.0, Math.max(0, params().num("bonus_max"))) / 100.0;
    }

    // ------------------------------------------------------------------ active: aimed shot

    @Override
    public ActivationResult onActive() {
        if (aim != null) {
            return ActivationResult.fail("이미 조준 중입니다");
        }
        long aimTicks = Math.max(1, params().ticks(marking() ? "mark_aim_time" : "aim_time"));
        Aim next = new Aim(aimTicks);
        aim = next;
        next.begin();
        Loop.repeat(ctx(), player(), 0, 1, aimTicks + 1, next, next::end);
        return ActivationResult.success();
    }

    /**
     * One aimed shot: the laser follows the crosshair for the aim time, then the rifle fires along the current view.
     * The sniper is slowed (a scope-like zoom) while aiming; a silence fizzles the shot.
     */
    private final class Aim implements LongPredicate {

        private final long aimTicks;
        private @Nullable NamespacedKey slow;
        private @Nullable BlockDisplay laser;
        private @Nullable LivingEntity locked;
        private long lockedSince;

        Aim(long aimTicks) {
            this.aimTicks = aimTicks;
        }

        void begin() {
            Player owner = player();
            double slowShare = Math.clamp(params().num("aim_slow") / 100.0, 0.0, 0.95);
            if (slowShare > 0) {
                slow = ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, -slowShare,
                        AttributeModifier.Operation.MULTIPLY_SCALAR_1, aimTicks + 2);
            }
            Location eye = owner.getEyeLocation();
            Trace trace = trace(eye, eye.getDirection(), params().num("shot_range"));
            Location from = muzzle(owner);
            laser = Displays.block(ctx(), owner, from, LASER_BLOCK,
                    beamPose(beamDelta(from, trace.end()), LASER_WIDTH), aimTicks + 4);
            laser.setTeleportDuration(1);
            Displays.outline(laser, PALETTE.body());

            // The rifle comes up: a red glint at the muzzle and the click of the scope.
            Vfx.dot(from, Particle.FLASH, PALETTE.body(), false);
            Vfx.cloud(ctx(), from, Particle.DUST_COLOR_TRANSITION, 6, 0.08, 0, HOT, false);
            disc(from, eye.getDirection(), 0.3, 10, Particle.DUST, LASER_FINE, false);
            Fx.sound(eye, Sound.ITEM_SPYGLASS_USE, 1.0f, 0.9f + Fx.jitter(0.05f));
            Fx.sound(eye, Sound.ITEM_CROSSBOW_LOADING_START, 0.8f, 1.3f + Fx.jitter(0.05f));
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">⌖ 조준 중...</" + COLOR_TAG + ">"));
        }

        @Override
        public boolean test(long tick) {
            Player owner = player();
            if (ctx().isSilenced(owner)) {
                fizzle();
                return false;
            }
            if (tick >= aimTicks) {
                fire();
                return false;
            }
            draw(tick);
            return true;
        }

        /** One tick of aiming: the laser follows the crosshair, the dot sits where it ends, a lock-on ring closes. */
        private void draw(long tick) {
            Player owner = player();
            Location eye = owner.getEyeLocation();
            Trace trace = trace(eye, eye.getDirection(), params().num("shot_range"));
            Location from = muzzle(owner);
            boolean hot = aimTicks - tick <= FLICKER_TICKS;
            if (laser != null && laser.isValid()) {
                laser.teleport(from);
                if (hot) {
                    laser.setBlock(tick % 2 == 0 ? LASER_HOT_BLOCK : LASER_BLOCK);
                }
                float width = trace.target() != null || hot ? LASER_WIDTH_LOCKED : LASER_WIDTH;
                Displays.animate(laser, beamPose(beamDelta(from, trace.end()), width), 1);
            }
            Location end = trace.end();
            Vfx.dot(end, Particle.DUST, LASER_DOT, true);
            if (tick % 2 == 0) {
                // A faint dotted line, so the laser reads from far away and around corners of the display.
                line(from, end, 0.35, Particle.DUST, LASER_FINE, true);
                Vfx.ring(ctx(), end, 0.18, 6, tick * 0.6, Particle.DUST, LASER_FINE, false);
            }
            LivingEntity target = trace.target();
            if (target != locked) {
                locked = target;
                lockedSince = tick;
                if (target != null) {
                    Fx.sound(Fx.body(target), Sound.BLOCK_NOTE_BLOCK_BIT, 0.9f, 1.9f);
                }
            }
            if (target != null) {
                // Lock-on: a ring closing in around the body under the laser, seen by everyone (and the target).
                double progress = Math.min(1.0, (double) (tick - lockedSince + 1) / LOCK_ON_TICKS);
                Vfx.ring(ctx(), Fx.body(target), 1.1 - 0.65 * progress, 14, tick * 0.35, Particle.DUST_COLOR_TRANSITION,
                        HOT, true);
            }
            if (tick % BEEP_PERIOD == 0) {
                float pitch = (float) Math.min(2.0, 1.0 + (double) tick / aimTicks);
                Fx.sound(eye, Sound.BLOCK_NOTE_BLOCK_BIT, 0.6f, pitch);
                Fx.sound(end, Sound.BLOCK_NOTE_BLOCK_BIT, 0.5f, pitch);
            }
            if (tick == Math.max(0, aimTicks - 3)) {
                Fx.sound(eye, Sound.ITEM_CROSSBOW_LOADING_END, 0.9f, 1.4f + Fx.jitter(0.05f));
            }
        }

        private void fire() {
            Player owner = player();
            Location eye = owner.getEyeLocation();
            Vector direction = eye.getDirection().normalize();
            Trace trace = trace(eye, direction, params().num("shot_range"));
            Location from = muzzle(owner);
            removeLaser();
            muzzleBlast(from, direction);
            tracer(from, trace.end());
            Fx.sound(eye, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 1.4f, 1.55f + Fx.jitter(0.08f));
            Fx.sound(eye, Sound.ITEM_CROSSBOW_SHOOT, 1.0f, 0.55f + Fx.jitter(0.05f));
            Fx.sound(eye, Sound.ENTITY_GENERIC_EXPLODE, 0.35f, 1.9f + Fx.jitter(0.05f));
            Fx.sound(eye, Sound.ENTITY_FIREWORK_ROCKET_BLAST_FAR, 3.0f, 0.8f);

            Vector recoil = Motion.horizontal(direction).multiply(-RECOIL).setY(0.05);
            owner.setVelocity(Motion.clamp(owner.getVelocity().add(recoil), MAX_SPEED));

            LivingEntity target = trace.target();
            if (target != null) {
                double damage = params().num("shot_damage");
                if (trace.head()) {
                    damage *= 1 + Math.max(0, params().num("headshot_bonus")) / 100.0;
                }
                if (strike(target, damage)) {
                    bodyImpact(trace.end(), direction);
                    if (trace.head()) {
                        headshot(target);
                    }
                    if (Combat.isTargetable(ctx(), owner, target)) {
                        Vector push = Motion.horizontal(direction).multiply(SHOT_PUSH).setY(0.12);
                        Motion.push(target, target.getVelocity().add(push), MAX_SPEED);
                    }
                } else {
                    // Dodged or blocked: the bullet just sparks past.
                    Vfx.cloud(ctx(), trace.end(), Particle.ELECTRIC_SPARK, 6, 0.2, 0.1, null, false);
                }
            } else if (trace.block() != null) {
                blockImpact(trace.end(), trace.block());
            }
        }

        private void fizzle() {
            removeLaser();
            Player owner = player();
            Location from = muzzle(owner);
            Vfx.cloud(ctx(), from, Particle.SMOKE, 6, 0.1, 0.02, null, false);
            Fx.sound(from, Sound.BLOCK_FIRE_EXTINGUISH, 0.5f, 1.6f + Fx.jitter(0.1f));
            owner.sendActionBar(Text.mm("<gray>조준이 흐트러졌습니다</gray>"));
        }

        private void removeLaser() {
            if (laser != null && laser.isValid()) {
                laser.remove();
            }
            laser = null;
        }

        /** Runs when the aim loop ends by itself (fired or fizzled): the rifle is idle again. */
        void end() {
            removeLaser();
            if (slow != null) {
                ctx().removeModifier(player(), slow);
                slow = null;
            }
            if (aim == this) {
                aim = null;
            }
        }
    }

    /**
     * Deals the shot's damage and tells whether it landed: on-hit effects (impact, push, headshot) only follow a hit
     * nothing cancelled.
     */
    private boolean strike(LivingEntity target, double damage) {
        if (!(damage > 0)) {
            return Combat.isTargetable(ctx(), player(), target);
        }
        pendingTarget = target;
        landed = false;
        try {
            Combat.hit(ctx(), player(), target, damage);
        } finally {
            pendingTarget = null;
        }
        return landed;
    }

    /** The shot leaves the barrel: a white flash, a blast ring around the muzzle and smoke pushed forward. */
    private void muzzleBlast(Location muzzle, Vector direction) {
        World world = muzzle.getWorld();
        Vfx.dot(muzzle, Particle.FLASH, PALETTE.core(), false);
        disc(muzzle, direction, 0.35, 14, Particle.DUST_COLOR_TRANSITION, HOT, false);
        disc(muzzle.clone().add(direction.clone().multiply(0.4)), direction, 0.55, 16, Particle.DUST, LASER_FINE,
                false);
        int sparks = Vfx.n(ctx(), 8);
        for (int i = 0; i < sparks; i++) {
            Vector spray = direction.clone().add(randomUnit().multiply(0.25)).normalize();
            // count 0: the offsets are a velocity, so the sparks fly out of the barrel
            world.spawnParticle(Particle.CRIT, muzzle.getX(), muzzle.getY(), muzzle.getZ(), 0, spray.getX(),
                    spray.getY(), spray.getZ(), 0.8);
        }
        world.spawnParticle(Particle.WHITE_SMOKE, muzzle.getX(), muzzle.getY(), muzzle.getZ(), 0, direction.getX(),
                direction.getY(), direction.getZ(), 0.15);
        Vfx.cloud(ctx(), muzzle, Particle.SMOKE, 5, 0.08, 0.02, null, false);
    }

    /**
     * The bullet's path: a white-hot beam with a red outline that thins out in a few ticks, a white-to-red dust line
     * and a smoke line that lingers a moment.
     */
    private void tracer(Location from, Location to) {
        Vector delta = beamDelta(from, to);
        BlockDisplay beam = Displays.block(ctx(), player(), from, TRACER_BLOCK, beamPose(delta, TRACER_WIDTH),
                TRACER_FADE_TICKS + 4L);
        Displays.outline(beam, PALETTE.body());
        Displays.animateNextTick(ctx(), player(), beam, beamPose(delta, 0.004f), TRACER_FADE_TICKS);
        line(from, to, 1.2, Particle.DUST_COLOR_TRANSITION, HOT, true);
        line(from, to, 0.25, Particle.ELECTRIC_SPARK, null, false);
        line(from, to, 0.5, Particle.WHITE_SMOKE, null, false);
        // Afterglow: the air along the path cools from red to dark crimson.
        Loop.repeat(ctx(), player(), 3, 3, 3, run -> {
            line(from, to, 0.35 - run * 0.1, Particle.DUST_COLOR_TRANSITION, EMBER, false);
            return true;
        });
    }

    /** A bullet striking a body: a red flash, a spray of sparks along the shot and a quick ring. */
    private void bodyImpact(Location at, Vector direction) {
        World world = at.getWorld();
        Vfx.dot(at, Particle.FLASH, PALETTE.body(), false);
        Vfx.cloud(ctx(), at, Particle.CRIT, 12, 0.2, 0.35, null, false);
        Vfx.cloud(ctx(), at, Particle.DUST_COLOR_TRANSITION, 10, 0.25, 0, HOT, false);
        world.spawnParticle(Particle.DAMAGE_INDICATOR, at, Vfx.n(ctx(), 3), 0.15, 0.15, 0.15, 0.1);
        disc(at, direction, 0.45, 12, Particle.DUST, LASER_DOT, false);
        Fx.sound(at, Sound.ENTITY_ARROW_HIT_PLAYER, 0.8f, 0.8f + Fx.jitter(0.1f));
        Fx.sound(at, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 1.1f + Fx.jitter(0.1f));
    }

    /** A headshot: a ring snaps around the head, "헤드샷!" pops up and the sniper hears a bright ding. */
    private void headshot(LivingEntity target) {
        Player owner = player();
        Location head = target.getEyeLocation();
        Vfx.ring(ctx(), head, 0.5, 16, ThreadLocalRandom.current().nextDouble(Math.PI), Particle.DUST_COLOR_TRANSITION,
                HOT, true);
        Vfx.ring(ctx(), head.clone().add(0, 0.15, 0), 0.75, 18, 0, Particle.END_ROD, null, false);
        TextDisplay label = Displays.text(ctx(), owner, head.clone().add(0, 0.8, 0),
                Text.mm("<b><gradient:#FFFFFF:" + COLOR_TAG + ">헤드샷!</gradient></b>"), 0.6f, HEADSHOT_LABEL_TICKS);
        Displays.animateNextTick(ctx(), owner, label, Displays.centered(0, 0.35f, 0, 1.25f, 1.25f, 1.25f, 0, 0, 0), 4);
        ctx().runLater(owner, HEADSHOT_LABEL_TICKS - 5, () -> {
            if (label.isValid()) {
                Displays.shrinkAway(ctx(), owner, label, 4);
            }
        });
        owner.playSound(owner, Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.PLAYERS, 0.9f, 2.0f);
        owner.playSound(owner, Sound.ENTITY_ARROW_HIT_PLAYER, SoundCategory.PLAYERS, 0.8f, 1.3f);

        double refund = Math.min(Math.max(0, params().num("headshot_refund")), ctx().cooldownRemaining(owner));
        String suffix = "";
        if (refund > 0) {
            ctx().reduceCooldown(owner, refund);
            suffix = " <gray>쿨타임 -" + AbilityInfo.formatNumber(Math.ceil(refund * 10) / 10.0) + "초</gray>";
        }
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>헤드샷!</b></" + COLOR_TAG + ">" + suffix));
    }

    /** A bullet striking a block: chips of the block, sparks and a puff of dust. */
    private void blockImpact(Location at, Block block) {
        World world = at.getWorld();
        if (block.getType().isSolid()) {
            world.spawnParticle(Particle.BLOCK, at, Vfx.n(ctx(), 8), 0.1, 0.1, 0.1, 0.15, block.getBlockData());
        }
        Vfx.cloud(ctx(), at, Particle.CRIT, 6, 0.1, 0.25, null, false);
        Vfx.cloud(ctx(), at, Particle.SMOKE, 4, 0.08, 0.02, null, false);
        Vfx.dot(at, Particle.DUST, LASER_DOT, false);
        Fx.sound(at, Sound.BLOCK_STONE_HIT, 1.0f, 1.4f + Fx.jitter(0.1f));
        Fx.sound(at, Sound.ENTITY_ARROW_HIT, 0.7f, 1.5f + Fx.jitter(0.1f));
    }

    // ------------------------------------------------------------------ ultimate: target designation

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        double range = params().num("mark_range");
        List<LivingEntity> targets = ctx().enemiesNear(owner, owner.getLocation(), range);
        if (targets.isEmpty()) {
            return ActivationResult.fail(AbilityInfo.formatNumber(range) + "블록 안에 표적으로 지정할 적이 없습니다");
        }
        for (Mark old : marks.values()) {
            old.release();
        }
        marks.clear();

        long ticks = Math.max(1, params().ticks("mark_duration"));
        long now = ctx().currentTick();
        markEnd = now + ticks;
        int token = ++markToken;
        // The rifle is ready right away.
        ctx().reduceCooldown(owner, Math.max(0, ctx().cooldownRemaining(owner)) + 1);

        double armor = Math.max(0, params().num("mark_armor"));
        int duration = (int) Math.min(Integer.MAX_VALUE, ticks);
        int labels = 0;
        for (LivingEntity target : targets) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, duration, 0, false, false, true));
            NamespacedKey armorKey = armor > 0
                    ? ctx().addModifier(owner, target, Attribute.ARMOR, -armor, AttributeModifier.Operation.ADD_NUMBER,
                    ticks)
                    : null;
            Mark mark = new Mark(target, armorKey);
            marks.put(target.getUniqueId(), mark);
            // The radar sweep reaches farther targets a little later.
            long delay = Math.min(RADAR_TICKS, Math.round(target.getLocation().distance(owner.getLocation()) / 1.5));
            boolean label = labels++ < MAX_LABELS;
            ctx().runLater(owner, delay, () -> mark.lockOn(label, ticks - delay));
        }
        radar(owner);
        Loop.repeat(ctx(), owner, 1, 1, ticks, run -> {
            if (token != markToken) {
                return false;
            }
            tickMarks(run, ticks);
            return true;
        }, () -> {
            if (token == markToken) {
                endMark();
            }
        });
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">◎ 표적 지정 <white>" + targets.size() + "명</white></"
                + COLOR_TAG + ">"));
        return ActivationResult.success();
    }

    private boolean isMarked(LivingEntity victim) {
        return marking() && marks.containsKey(victim.getUniqueId());
    }

    /** The cast: a radar sweep of red bars spinning out from the sniper, a scan pulse and a column of light. */
    private void radar(Player owner) {
        Location feet = owner.getLocation().add(0, 0.06, 0);
        feet.setYaw(0);
        feet.setPitch(0);
        double start = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        for (int i = 0; i < RADAR_BARS; i++) {
            double angle = start + i * Math.PI * 2 / RADAR_BARS;
            BlockDisplay bar = Displays.block(ctx(), owner, feet, RADAR_BLOCK, radarBar(angle, RADAR_START),
                    RADAR_TICKS + 10L);
            Displays.outline(bar, PALETTE.body());
            Displays.animateNextTick(ctx(), owner, bar, radarBar(angle + 1.2, RADAR_END), RADAR_TICKS);
            ctx().runLater(owner, RADAR_TICKS + 1L, () -> {
                if (bar.isValid()) {
                    Displays.shrinkAway(ctx(), owner, bar, 6);
                }
            });
        }
        Vfx.shockwave(ctx(), owner, feet, 0.6, 14, 12, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.3f), true);
        Location eye = owner.getEyeLocation();
        Vfx.dot(eye, Particle.FLASH, PALETTE.body(), true);
        Vfx.line(ctx(), feet, feet.clone().add(0, 9, 0), 2.5, Particle.END_ROD, null, true);
        Vfx.helix(ctx(), feet, 0.7, 2.6, 2, 2, 14, start, Particle.DUST_COLOR_TRANSITION, HOT, true);
        Fx.sound(eye, Sound.BLOCK_BEACON_ACTIVATE, 1.2f, 1.6f);
        Fx.sound(eye, Sound.ITEM_SPYGLASS_USE, 1.2f, 0.7f);
        Fx.sound(eye, Sound.BLOCK_TRIAL_SPAWNER_DETECT_PLAYER, 1.5f, 0.8f + Fx.jitter(0.05f));
    }

    private void tickMarks(long run, long total) {
        Player owner = player();
        for (Mark mark : marks.values()) {
            mark.follow(run);
        }
        if (run % 20 == 0) {
            long left = total - run;
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">◎ 표적 지정 <white>" + liveMarks() + "명</white> <gray>"
                    + Fx.seconds(left) + "초</gray></" + COLOR_TAG + ">"));
        }
        if (run % 10 == 0) {
            Vfx.ring(ctx(), owner.getLocation().add(0, 0.08, 0), 0.75, 12, run * 0.1, Particle.DUST, LASER_FINE,
                    false);
        }
    }

    private int liveMarks() {
        int live = 0;
        for (Mark mark : marks.values()) {
            if (Combat.isTargetable(ctx(), player(), mark.target)) {
                live++;
            }
        }
        return live;
    }

    private void endMark() {
        for (Mark mark : marks.values()) {
            mark.fade();
        }
        marks.clear();
        markEnd = -1;
        Fx.sound(player().getLocation(), Sound.ITEM_SPYGLASS_STOP_USING, 0.9f, 0.8f);
    }

    /** One marked enemy: its glow and armor loss, and the reticle label pinned over its head. */
    private final class Mark {

        private final LivingEntity target;
        private final @Nullable NamespacedKey armorKey;
        private @Nullable TextDisplay label;
        private final double phase = ThreadLocalRandom.current().nextDouble(Math.PI * 2);

        Mark(LivingEntity target, @Nullable NamespacedKey armorKey) {
            this.target = target;
            this.armorKey = armorKey;
        }

        /** The radar reached the target: a ring snaps shut around it and the reticle appears. */
        void lockOn(boolean withLabel, long lifetime) {
            if (!Combat.isTargetable(ctx(), player(), target)) {
                return;
            }
            Player owner = player();
            Location body = Fx.body(target);
            Vfx.trail(owner.getEyeLocation(), body, PALETTE.body(), 8, true);
            Loop.repeat(ctx(), owner, 0, 1, LOCK_ON_TICKS, run -> {
                if (!target.isValid()) {
                    return false;
                }
                double radius = 1.5 - 1.0 * (run + 1) / LOCK_ON_TICKS;
                Vfx.ring(ctx(), Fx.body(target), radius, 16, run * 0.4, Particle.DUST_COLOR_TRANSITION, HOT, true);
                return true;
            });
            Vfx.dot(body, Particle.FLASH, PALETTE.body(), true);
            Fx.sound(body, Sound.BLOCK_NOTE_BLOCK_BIT, 1.0f, 2.0f);
            Fx.sound(body, Sound.BLOCK_TRIAL_SPAWNER_DETECT_PLAYER, 0.8f, 1.3f + Fx.jitter(0.1f));
            if (target instanceof Player victim) {
                victim.sendActionBar(Text.mm("<" + COLOR_TAG + ">◎ 저격수의 표적이 되었습니다!</" + COLOR_TAG + ">"));
                victim.playSound(victim, Sound.ENTITY_ELDER_GUARDIAN_CURSE, SoundCategory.PLAYERS, 0.25f, 1.6f);
            }
            if (withLabel && lifetime > 0) {
                label = Displays.text(ctx(), owner, labelPoint(),
                        Text.mm("<b><" + COLOR_TAG + ">◎</" + COLOR_TAG + "></b>"), 0.3f, lifetime + 6);
                label.setTeleportDuration(1);
                Displays.outline(label, PALETTE.body());
                Displays.animateNextTick(ctx(), owner, label, Displays.centered(0, 0, 0, 1.4f, 1.4f, 1.4f, 0, 0, 0), 4);
            }
        }

        /** Keeps the reticle over the head and draws a turning red crosshair ring above it now and then. */
        void follow(long run) {
            if (!Combat.isTargetable(ctx(), player(), target)) {
                if (label != null && label.isValid()) {
                    label.remove();
                }
                return;
            }
            if (label != null && label.isValid()) {
                label.teleport(labelPoint());
            }
            if (run % 4 == 0) {
                Location crown = target.getLocation().add(0, target.getHeight() + 0.25, 0);
                Vfx.ring(ctx(), crown, 0.42, 10, phase + run * 0.12, Particle.DUST, LASER_FINE, true);
            }
            if (run % 8 == 0) {
                Vfx.ring(ctx(), target.getLocation().add(0, 0.05, 0), 0.6, 10, phase - run * 0.08,
                        Particle.DUST_COLOR_TRANSITION, EMBER, false);
            }
        }

        private Location labelPoint() {
            return target.getLocation().add(0, target.getHeight() + 0.55, 0);
        }

        /** The mark ran out: the reticle shrinks away with a few embers. */
        void fade() {
            if (label != null && label.isValid()) {
                Displays.shrinkAway(ctx(), player(), label, 5);
            }
            if (target.isValid() && !target.isDead()) {
                Vfx.cloud(ctx(), target.getLocation().add(0, target.getHeight() + 0.4, 0),
                        Particle.DUST_COLOR_TRANSITION, 4, 0.15, 0, EMBER, false);
            }
        }

        /** The sniper is gone: the glow and the armor loss end with it. */
        void release() {
            if (armorKey != null) {
                ctx().removeModifier(target, armorKey);
            }
            if (target.isValid() && !target.isDead()) {
                PotionEffect glow = target.getPotionEffect(PotionEffectType.GLOWING);
                if (glow != null && !glow.isInfinite()) {
                    target.removePotionEffect(PotionEffectType.GLOWING);
                }
            }
        }
    }

    // ------------------------------------------------------------------ aiming helpers

    /**
     * Where a ray along {@code direction} from {@code eye} ends: the first enemy hitbox (passing through allies and
     * bystanders), the first block, or the full range.
     */
    private record Trace(Location end, @Nullable LivingEntity target, boolean head, @Nullable Block block) {
    }

    private Trace trace(Location eye, Vector direction, double range) {
        World world = eye.getWorld();
        Vector unit = direction.clone();
        if (unit.lengthSquared() < 1.0E-8) {
            unit = new Vector(0, 0, 1);
        }
        unit.normalize();
        double reach = Math.max(1, range);
        Player owner = player();
        RayTraceResult hit = world.rayTrace(eye, unit, reach, FluidCollisionMode.NEVER, true, SHOT_RAY,
                entity -> entity instanceof LivingEntity && ctx().isEnemy(owner, entity));
        if (hit == null) {
            return new Trace(eye.clone().add(unit.multiply(reach)), null, false, null);
        }
        Location point = hit.getHitPosition().toLocation(world);
        if (hit.getHitEntity() instanceof LivingEntity target) {
            double headFrom = target.getEyeLocation().getY() - target.getHeight() * HEAD_SHARE;
            return new Trace(point, target, point.getY() >= headFrom, null);
        }
        return new Trace(point, null, false, hit.getHitBlock());
    }

    /** The rifle's muzzle: in front of the sniper's eyes, slightly right and below, like a shouldered rifle. */
    private static Location muzzle(Player owner) {
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection();
        Vector right = direction.getCrossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1.0E-6) {
            double yaw = Math.toRadians(eye.getYaw());
            right = new Vector(-Math.cos(yaw), 0, -Math.sin(yaw));
        }
        right.normalize();
        Vector up = right.getCrossProduct(direction).normalize();
        return eye.add(direction.multiply(0.55)).add(right.multiply(0.22)).subtract(up.multiply(0.2));
    }

    private static Vector beamDelta(Location from, Location to) {
        Vector delta = to.toVector().subtract(from.toVector());
        return delta.lengthSquared() < 1.0E-4 ? new Vector(0, 0, 0.01) : delta;
    }

    // ------------------------------------------------------------------ visual helpers

    /** A line of particles with at most {@link #MAX_LINE_POINTS} points, however long it is. */
    private <T> void line(Location from, Location to, double perBlock, Particle particle, @Nullable T data,
                          boolean force) {
        double length = from.distance(to);
        double density = length * perBlock > MAX_LINE_POINTS ? MAX_LINE_POINTS / Math.max(1, length) : perBlock;
        if (density > 0) {
            Vfx.line(ctx(), from, to, density, particle, data, force);
        }
    }

    /** A ring of particles in the plane perpendicular to {@code axis}, e.g. a muzzle blast around the barrel. */
    private <T> void disc(Location center, Vector axis, double radius, int points, Particle particle,
                          @Nullable T data, boolean force) {
        Vector normal = axis.clone();
        if (normal.lengthSquared() < 1.0E-8) {
            normal = new Vector(0, 1, 0);
        }
        normal.normalize();
        Vector u = normal.clone().crossProduct(Math.abs(normal.getY()) > 0.95 ? new Vector(1, 0, 0)
                : new Vector(0, 1, 0)).normalize();
        Vector v = normal.clone().crossProduct(u);
        int count = Vfx.n(ctx(), points);
        World world = center.getWorld();
        for (int i = 0; i < count; i++) {
            double angle = i * Math.PI * 2 / count;
            Vector offset = u.clone().multiply(Math.cos(angle) * radius).add(v.clone().multiply(Math.sin(angle) * radius));
            world.spawnParticle(particle, center.getX() + offset.getX(), center.getY() + offset.getY(),
                    center.getZ() + offset.getZ(), 1, 0, 0, 0, 0, data, force);
        }
    }

    /** A thin box of the given width from the block display's origin along {@code delta}. */
    private static Transformation beamPose(Vector delta, float width) {
        Vector3f direction = new Vector3f((float) delta.getX(), (float) delta.getY(), (float) delta.getZ());
        float length = direction.length();
        Quaternionf rotation = new Quaternionf().rotationTo(new Vector3f(0, 0, 1), direction.normalize());
        Vector3f translation = rotation.transform(new Vector3f(-width / 2, -width / 2, 0));
        return new Transformation(translation, rotation, new Vector3f(width, width, length), new Quaternionf());
    }

    /** A thin glowing radar bar lying on the ground, tangent to the circle of {@code distance} at {@code angle}. */
    private static Transformation radarBar(double angle, double distance) {
        float length = (float) Math.max(0.3, 2 * distance * Math.sin(Math.PI / RADAR_BARS) * 0.6);
        return Displays.transform((float) (Math.cos(angle) * distance), 0, (float) (Math.sin(angle) * distance),
                length, 0.04f, 0.12f, (float) Math.toDegrees(angle) - 90f, 0, 0);
    }

    private static Vector randomUnit() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Vector vector = new Vector(random.nextGaussian(), random.nextGaussian(), random.nextGaussian());
        return vector.lengthSquared() < 1.0E-6 ? new Vector(1, 0, 0) : vector.normalize();
    }
}
