package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;

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
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 무도가 — a martial artist of the wind. Every third punch of a combo on the same enemy stuns it, the active throws a
 * palm of compressed wind (장풍) that blasts the first enemy away, and the ultimate (백열권) closes in on an enemy for a
 * storm of punches that ends in a palm strike sending it flying.
 *
 * <p>Look: white and sky-blue wind. The 장풍 is a translucent hand of glass (palm, four fingers and a thumb) flying
 * inside spinning rings of wind; combo hits flash white arcs and a stun spins three stars over the victim's head. The
 * barrage throws glassy fist afterimages from both shoulders under a growing hit counter and ends with a giant palm
 * slamming into the target, a shockwave and a wind trail behind the launched body.
 */
public final class MonkAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("monk", "무도가", Material.BREEZE_ROD)
            .color(TextColor.color(0x8FD8FF))
            .difficulty(2)
            .passive("연환격",
                    "같은 적을 {combo_window}초 안에 연달아 근접 공격하면 3번째 공격마다 {combo_bonus} 추가 피해를 주고 "
                            + "{stun_duration}초간 기절시킵니다(이동·점프 불가).")
            .active("장풍",
                    "바라보는 방향으로 바람 손바닥을 날립니다({palm_range}블록). 처음 맞은 적에게 {palm_damage} 피해를 주고 "
                            + "약 {palm_push}블록 날려보냅니다.",
                    8)
            .ultimate("백열권",
                    "조준한 적(없으면 가장 가까운 적, {ult_range}블록 이내) 앞으로 순간이동해 {ult_duration}초간 붙잡아 두고 "
                            + "{ult_hits}번 연타({ult_hit_damage} 피해씩)한 뒤, 마지막 장타로 {ult_final_damage} 피해를 주며 "
                            + "약 {ult_launch}블록 날려보냅니다.")
            .param("combo_window", 2)
            .param("combo_bonus", 2)
            .param("stun_duration", 0.5)
            .param("palm_range", 16)
            .param("palm_damage", 5)
            .param("palm_push", 7)
            .param("ult_range", 16)
            .param("ult_duration", 2)
            .param("ult_hits", 16)
            .param("ult_hit_damage", 0.5)
            .param("ult_final_damage", 4)
            .param("ult_launch", 9)
            .build();

    private static final String COLOR_TAG = "#8FD8FF";
    /** White wind, sky blue body, a deeper blue edge for the fades. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFFFFF, 0x8FD8FF, 0x2F7FC1);
    private static final Particle.DustTransition GLOW = PALETTE.glow(0.9f);
    private static final Particle.DustOptions BODY = PALETTE.dust(0.8f);
    private static final Particle.DustTransition FADE = PALETTE.fade(1.0f);
    private static final double TAU = Math.PI * 2;

    /** Hard cap for every velocity this ability applies. */
    private static final double MAX_SPEED = 2.5;
    /**
     * Roughly how many blocks a body knocked back with a small hop travels per block/tick of horizontal launch speed
     * (vanilla air drag 0.91 for the hop, then ground friction). Turns the "push" params (blocks) into a speed.
     */
    private static final double PUSH_PER_SPEED = 8.0;
    private static final double PUSH_LIFT = 0.42;

    // passive: chain strikes
    private static final int COMBO_LENGTH = 3;
    private static final int STAR_COUNT = 3;
    private static final long PIP_LABEL_TICKS = 20;

    // active: wind palm
    private static final double PALM_SPEED = 1.15;
    /** Distance from the palm's centre to an enemy's hitbox that counts as a hit. */
    private static final double PALM_HIT_RADIUS = 0.85;
    private static final double SWEEP_STEP = 0.4;
    private static final float PALM_SCALE = 1.35f;
    private static final float PALM_START_SCALE = 0.35f;
    private static final int PALM_GROW_TICKS = 3;
    private static final int KNOCK_TRAIL_TICKS = 10;
    private static final int AFTERGLOW_TICKS = 12;

    // ultimate: hundred fists
    /** The stance before the first punch, while the lock-on ring closes. */
    private static final int WINDUP_TICKS = 4;
    /** The barrage breaks if the owner ends up farther than this from the target. */
    private static final double BARRAGE_REACH = 4.5;
    private static final double LAUNCH_LIFT = 0.6;
    private static final float FIST_SIZE = 0.34f;
    private static final int FIST_FLIGHT_TICKS = 2;
    private static final int FIST_LIFE_TICKS = 5;
    private static final float FINISHER_SCALE = 2.3f;
    private static final long LANDING_GRACE_TICKS = 100;

    private static final BlockData PALM_GLASS = Material.WHITE_STAINED_GLASS.createBlockData();
    private static final BlockData FINGER_GLASS = Material.LIGHT_BLUE_STAINED_GLASS.createBlockData();
    private static final float HAND_DEPTH = 0.12f;

    /**
     * The wind hand in its own frame: x to the right, y up, z along the flight. Centre and size in blocks at scale 1,
     * plus a roll around the flight axis (the splayed thumb).
     */
    private static final Part[] HAND = {
            new Part(0f, -0.06f, 0.64f, 0.66f, 0f, true),
            new Part(-0.245f, 0.5f, 0.14f, 0.44f, 0f, false),
            new Part(-0.082f, 0.535f, 0.14f, 0.51f, 0f, false),
            new Part(0.082f, 0.52f, 0.14f, 0.48f, 0f, false),
            new Part(0.245f, 0.46f, 0.13f, 0.36f, 0f, false),
            new Part(-0.43f, 0.05f, 0.15f, 0.36f, 32f, false),
    };

    // combo state of the passive
    private @Nullable UUID comboTarget;
    private int comboCount;
    private long lastComboHit = Long.MIN_VALUE;
    /** The floating combo counter over the current combo target (only the newest one is kept). */
    private @Nullable TextDisplay comboLabel;

    private record Part(float cx, float cy, float sx, float sy, float roll, boolean palm) {

        /** This part's box in a hand turned by {@code frame}, scaled, pushed {@code forward} blocks along the flight. */
        Transformation pose(Quaternionf frame, float scale, float forward) {
            Quaternionf rotation = new Quaternionf(frame).rotateZ((float) Math.toRadians(roll));
            Vector3f size = new Vector3f(sx * scale, sy * scale, HAND_DEPTH * scale);
            Vector3f center = new Quaternionf(frame).transform(new Vector3f(cx * scale, cy * scale, forward));
            Vector3f translation = center.sub(new Quaternionf(rotation).transform(new Vector3f(size).mul(0.5f)));
            return new Transformation(translation, rotation, size, new Quaternionf());
        }
    }

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        comboTarget = null;
        comboCount = 0;
        comboLabel = null;
    }

    // ------------------------------------------------------------------ passive: chain strikes

    /** The finishing third hit of a combo gets the bonus; the victim may still cancel it. */
    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled() || !isOwnMelee(event, victim)) {
            return;
        }
        if (comboOn(victim) + 1 >= COMBO_LENGTH) {
            event.setDamage(event.getDamage() + Math.max(0, params().num("combo_bonus")));
        }
    }

    /** The hit landed: count the combo and stun on the third hit. */
    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (!isOwnMelee(event, victim)) {
            return;
        }
        int count = comboOn(victim) + 1;
        comboTarget = victim.getUniqueId();
        lastComboHit = ctx().currentTick();
        if (count >= COMBO_LENGTH) {
            comboCount = 0;
            stun(victim);
        } else {
            comboCount = count;
            comboPip(victim, count);
        }
    }

    /** The owner's own sword swing at an enemy (not ability damage, sweeps, projectiles or summon bites). */
    private boolean isOwnMelee(EntityDamageByEntityEvent event, LivingEntity victim) {
        return !ctx().isAbilityDamage() && player().equals(event.getDamager())
                && event.getCause() == DamageCause.ENTITY_ATTACK && ctx().isEnemy(player(), victim);
    }

    /** Hits already in the running combo on {@code victim}: 0 for another target or after the window lapsed. */
    private int comboOn(LivingEntity victim) {
        long window = params().ticks("combo_window");
        if (!victim.getUniqueId().equals(comboTarget) || ctx().currentTick() - lastComboHit > window) {
            return 0;
        }
        return comboCount;
    }

    /** First and second hits: a white slash arc and a rising tick. */
    private void comboPip(LivingEntity victim, int count) {
        Location body = Fx.body(victim);
        double yaw = player().getLocation().getYaw();
        Vfx.arc(ctx(), body, 0.7, yaw, 120, 9, Particle.DUST_COLOR_TRANSITION, GLOW, false);
        Vfx.cloud(ctx(), body, Particle.CRIT, 4, 0.2, 0.2, null, false);
        Fx.sound(body, Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f, 1.1f + 0.3f * count + Fx.jitter(0.05f));
        String pips = "<white>" + "●".repeat(count) + "</white><#4F7FA0>" + "○".repeat(COMBO_LENGTH - count)
                + "</#4F7FA0>";
        player().sendActionBar(Text.mm("<" + COLOR_TAG + ">연환 </" + COLOR_TAG + ">" + pips));
        comboLabel(victim, "<b>" + pips + "</b>", PIP_LABEL_TICKS);
    }

    /** A counter popping up over the victim's head: the combo pips, or the stun call on the third hit. */
    private void comboLabel(LivingEntity victim, String text, long lifetime) {
        TextDisplay previous = comboLabel;
        if (previous != null && previous.isValid()) {
            previous.remove();
        }
        Location above = flat(victim.getLocation().add(0, victim.getHeight() + 0.4, 0));
        TextDisplay label = Displays.text(ctx(), player(), above, Text.mm(text), 0.4f, lifetime);
        comboLabel = label;
        Displays.animateNextTick(ctx(), player(), label, Displays.centered(0, 0.25f, 0, 0.9f, 0.9f, 0.9f, 0, 0, 0),
                3);
        ctx().runLater(player(), Math.max(1, lifetime - 5), () -> {
            if (label.isValid()) {
                Displays.shrinkAway(ctx(), player(), label, 4);
            }
        });
    }

    /** The third hit: the victim is rooted for the stun, with stars spinning over its head. */
    private void stun(LivingEntity victim) {
        long ticks = params().ticks("stun_duration");
        if (ticks > 0) {
            root(victim, ticks);
        }
        Location body = Fx.body(victim);
        World world = body.getWorld();
        world.spawnParticle(Particle.FLASH, body, 1, 0, 0, 0, 0, PALETTE.core());
        Vector toward = victim.getLocation().toVector().subtract(player().getLocation().toVector());
        verticalRing(body, toward, 0.9, 16, 0, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.2f), false);
        verticalRing(body, toward, 0.6, 10, 0.3, Particle.DUST, BODY, false);
        Vfx.cloud(ctx(), body, Particle.CLOUD, 6, 0.2, 0.08, null, false);
        Vfx.cloud(ctx(), body, Particle.CRIT, 10, 0.25, 0.35, null, false);
        Vfx.dot(body, Particle.GUST, null, false);
        Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 1.1f + Fx.jitter(0.1f));
        Fx.sound(body, Sound.BLOCK_BELL_USE, 0.6f, 1.9f);
        Fx.sound(body, Sound.ENTITY_BREEZE_WIND_BURST, 0.6f, 1.5f + Fx.jitter(0.1f));
        player().sendActionBar(Text.mm("<" + COLOR_TAG + "><b>연환격!</b></" + COLOR_TAG + "> <white>●●●</white> "
                + "<gray>" + AbilityInfo.formatNumber(params().num("stun_duration")) + "초 기절</gray>"));
        comboLabel(victim, "<b><gradient:#FFFFFF:" + COLOR_TAG + ">기절!</gradient></b>", Math.max(12, ticks + 6));
        if (ticks > 0) {
            stars(victim, ticks);
        }
    }

    /** Three little stars circling the stunned victim's head. */
    private void stars(LivingEntity victim, long ticks) {
        Loop.repeat(ctx(), player(), 0, 2, Math.max(1, ticks / 2), run -> {
            if (!victim.isValid() || victim.isDead()) {
                return false;
            }
            Location head = victim.getLocation().add(0, victim.getHeight() + 0.25, 0);
            for (int i = 0; i < STAR_COUNT; i++) {
                double angle = run * 0.8 + i * TAU / STAR_COUNT;
                Location star = head.clone().add(Math.cos(angle) * 0.45, Math.sin(run * 0.6 + i) * 0.06,
                        Math.sin(angle) * 0.45);
                Vfx.dot(star, Particle.DUST_COLOR_TRANSITION, GLOW, false);
            }
            if (run % 2 == 0) {
                Vfx.dot(head, Particle.ELECTRIC_SPARK, null, false);
            }
            return true;
        });
    }

    /** Roots {@code target} for {@code ticks}: no walking, no jumping. Returns the modifier keys. */
    private NamespacedKey[] root(LivingEntity target, long ticks) {
        return new NamespacedKey[]{
                ctx().addModifier(player(), target, Attribute.MOVEMENT_SPEED, -1.0,
                        AttributeModifier.Operation.MULTIPLY_SCALAR_1, ticks),
                ctx().addModifier(player(), target, Attribute.JUMP_STRENGTH, -1.0,
                        AttributeModifier.Operation.MULTIPLY_SCALAR_1, ticks)};
    }

    // ------------------------------------------------------------------ active: wind palm

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection();
        if (!Double.isFinite(direction.lengthSquared()) || direction.lengthSquared() < 1.0E-6) {
            return ActivationResult.fail("방향을 알 수 없습니다");
        }
        direction.normalize();
        new Palm(owner, eye, direction, Math.max(1, params().num("palm_range"))).launch();
        return ActivationResult.success();
    }

    /**
     * The 장풍: a hand of wind flying straight along the view, stopped by the first enemy (damage and a big push) or
     * the first wall.
     */
    private final class Palm implements LongPredicate {

        private final Player owner;
        private final Vector direction;
        private final Vector u;
        private final Vector v;
        private final double range;
        private final World world;
        private Location position;
        private double travelled;
        private @Nullable Hand hand;

        Palm(Player owner, Location eye, Vector direction, double range) {
            this.owner = owner;
            this.direction = direction;
            this.range = range;
            this.world = eye.getWorld();
            Vector[] basis = basis(direction);
            this.u = basis[0];
            this.v = basis[1];
            this.position = eye.clone().subtract(0, 0.25, 0);
        }

        void launch() {
            Location hands = position.clone().add(direction.clone().multiply(0.6));
            hand = new Hand(hands, facing(direction), PALM_START_SCALE, (long) Math.ceil(range / PALM_SPEED) + 16);
            hand.poseNextTick(PALM_SCALE, 0, PALM_GROW_TICKS);
            // The cast: a puff of wind from the hands and the breeze's shot.
            Vfx.dot(hands, Particle.SMALL_GUST, null, false);
            Vfx.cloud(ctx(), hands, Particle.DUST_COLOR_TRANSITION, 10, 0.15, 0, GLOW, false);
            Vfx.cloud(ctx(), hands, Particle.CLOUD, 5, 0.1, 0.06, null, false);
            verticalRing(hands, direction, 0.45, 12, 0, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(0.8f), false);
            Fx.sound(hands, Sound.ENTITY_BREEZE_SHOOT, 1.0f, 1.15f + Fx.jitter(0.08f));
            Fx.sound(hands, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.6f, 1.5f + Fx.jitter(0.1f));
            Fx.sound(hands, Sound.ENTITY_BREEZE_INHALE, 0.5f, 1.6f + Fx.jitter(0.1f));
            Loop.repeat(ctx(), owner, 0, 1, (long) Math.ceil(range / PALM_SPEED) + 1, this);
        }

        @Override
        public boolean test(long tick) {
            double step = Math.min(PALM_SPEED, range - travelled);
            if (step <= 1.0E-3) {
                fade();
                return false;
            }
            RayTraceResult wall = world.rayTraceBlocks(position, direction, step, FluidCollisionMode.NEVER, true);
            double reach = wall == null ? step : wall.getHitPosition().distance(position.toVector());
            LivingEntity victim = sweep(reach);
            if (victim != null) {
                strike(victim);
                return false;
            }
            if (wall != null) {
                position = wall.getHitPosition().toLocation(world).subtract(direction.clone().multiply(0.3));
                splat();
                return false;
            }
            position = position.clone().add(direction.clone().multiply(step));
            travelled += step;
            if (hand != null) {
                hand.moveTo(position);
            }
            flight(tick);
            return true;
        }

        /** The first enemy within reach of the palm on its way through this tick's segment. */
        private @Nullable LivingEntity sweep(double reach) {
            for (double d = 0; ; d += SWEEP_STEP) {
                double along = Math.min(d, reach);
                Location point = position.clone().add(direction.clone().multiply(along));
                List<LivingEntity> found = ctx().enemiesNear(owner, point, PALM_HIT_RADIUS);
                if (!found.isEmpty()) {
                    position = point;
                    return found.getFirst();
                }
                if (along >= reach) {
                    return null;
                }
            }
        }

        /** Two spinning rings of wind around the palm, streaks flying back and gusts in its wake. */
        private void flight(long tick) {
            double phase = tick * 0.7;
            ring(position.clone().subtract(direction.clone().multiply(0.35)), 0.85, 10, phase, Particle.DUST_COLOR_TRANSITION,
                    GLOW);
            ring(position.clone().subtract(direction.clone().multiply(0.95)), 0.55, 7, -phase, Particle.DUST, BODY);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int streaks = Vfx.n(ctx(), 2);
            for (int i = 0; i < streaks; i++) {
                double angle = random.nextDouble(TAU);
                Location rim = position.clone().add(u.clone().multiply(Math.cos(angle) * 0.7))
                        .add(v.clone().multiply(Math.sin(angle) * 0.7));
                Vfx.trail(rim, rim.clone().subtract(direction.clone().multiply(1.8)),
                        i % 2 == 0 ? PALETTE.core() : PALETTE.body(), 5, false);
            }
            if (tick % 3 == 0) {
                Vfx.dot(position.clone().subtract(direction.clone().multiply(0.9)), Particle.SMALL_GUST, null, false);
            }
            world.spawnParticle(Particle.CLOUD, position.clone().subtract(direction.clone().multiply(0.6)), 1, 0.1, 0.1,
                    0.1, 0.01);
            if (tick % 4 == 0) {
                Fx.sound(position, Sound.ENTITY_BREEZE_IDLE_AIR, 0.5f, 1.4f + Fx.jitter(0.1f));
            }
        }

        private <T> void ring(Location center, double radius, int points, double phase, Particle particle, T data) {
            int count = Vfx.n(ctx(), points);
            for (int i = 0; i < count; i++) {
                double angle = phase + i * TAU / count;
                Location at = center.clone().add(u.clone().multiply(Math.cos(angle) * radius))
                        .add(v.clone().multiply(Math.sin(angle) * radius));
                Vfx.dot(at, particle, data, false);
            }
        }

        /** The palm lands on an enemy: damage, a big push along the flight, and the hand slaps and fades. */
        private void strike(LivingEntity victim) {
            Combat.hit(ctx(), owner, victim, Math.max(0, params().num("palm_damage")));
            Location body = Fx.body(victim);
            if (Combat.isTargetable(ctx(), owner, victim)) {
                Vector push = Motion.horizontal(direction);
                if (push.lengthSquared() < 1.0E-6) {
                    push = Motion.horizontal(victim.getLocation().toVector().subtract(owner.getLocation().toVector()));
                }
                double speed = Math.max(0, params().num("palm_push")) / PUSH_PER_SPEED;
                if (Motion.push(victim, push.multiply(speed).setY(PUSH_LIFT), MAX_SPEED)) {
                    windTrail(victim, KNOCK_TRAIL_TICKS);
                }
            }
            impact(body, direction, 1.0);
            if (hand != null) {
                hand.moveTo(position);
                hand.slap(PALM_SCALE);
            }
            Fx.sound(body, Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1.0f, 0.9f + Fx.jitter(0.08f));
            Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 0.8f + Fx.jitter(0.08f));
            Fx.sound(body, Sound.ENTITY_BREEZE_DEFLECT, 0.7f, 1.4f + Fx.jitter(0.1f));
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">장풍 적중!</" + COLOR_TAG + ">"));
        }

        /** The palm flattens against a wall and bursts into wind. */
        private void splat() {
            if (hand != null) {
                hand.moveTo(position);
                hand.slap(PALM_SCALE * 0.9f);
            }
            Vfx.burst(ctx(), position, PALETTE, 0.6, Particle.CLOUD, false);
            Vfx.dot(position, Particle.SMALL_GUST, null, false);
            Fx.sound(position, Sound.ENTITY_BREEZE_WIND_BURST, 0.8f, 1.2f + Fx.jitter(0.1f));
        }

        /** Out of range: the hand thins out and the wind disperses. */
        private void fade() {
            if (hand != null) {
                hand.vanish(4);
            }
            Vfx.cloud(ctx(), position, Particle.CLOUD, 6, 0.3, 0.03, null, false);
            Vfx.cloud(ctx(), position, Particle.DUST_COLOR_TRANSITION, 6, 0.4, 0, FADE, false);
        }
    }

    // ------------------------------------------------------------------ ultimate: hundred fists

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        if (owner.isInsideVehicle()) {
            return ActivationResult.fail("탈것에 탄 채로는 사용할 수 없습니다");
        }
        double range = params().num("ult_range");
        List<LivingEntity> candidates = new ArrayList<>();
        LivingEntity aimed = ctx().findTarget(owner, range);
        if (aimed != null) {
            candidates.add(aimed);
        }
        List<LivingEntity> nearby = new ArrayList<>(ctx().enemiesNear(owner, owner.getLocation(), range));
        nearby.sort(Comparator.comparingInt(enemy -> owner.hasLineOfSight(enemy) ? 0 : 1));
        for (LivingEntity enemy : nearby) {
            if (!candidates.contains(enemy)) {
                candidates.add(enemy);
            }
        }
        if (candidates.isEmpty()) {
            return ActivationResult.fail(AbilityInfo.formatNumber(range) + "블록 안에 적이 없습니다");
        }
        for (LivingEntity target : candidates) {
            if (!target.getWorld().equals(owner.getWorld())) {
                continue;
            }
            Location from = owner.getLocation();
            boolean close = from.distance(target.getLocation()) <= BARRAGE_REACH - 1.5 && owner.hasLineOfSight(target);
            Location spot = Spots.around(ctx(), owner, target, 180);
            if (spot == null && !close) {
                continue;
            }
            if (spot != null && !owner.teleport(spot, TeleportCause.PLUGIN) && !close) {
                return ActivationResult.fail("순간이동이 막혔습니다");
            }
            owner.setFallDistance(0);
            dashFx(from, owner.getLocation());
            new Barrage(owner, target).begin();
            return ActivationResult.success();
        }
        return ActivationResult.fail("적 앞에 설 공간이 없습니다");
    }

    /** The step in: wind streaks from the old spot to the new one and a puff where the owner left. */
    private void dashFx(Location from, Location to) {
        Player owner = player();
        double height = owner.getHeight();
        Location fromBody = from.clone().add(0, height * 0.55, 0);
        Location toBody = to.clone().add(0, height * 0.55, 0);
        Vfx.cloud(ctx(), fromBody, Particle.CLOUD, 10, 0.3, 0.04, null, true);
        Vfx.dot(fromBody, Particle.GUST, null, true);
        if (from.getWorld().equals(to.getWorld()) && from.distanceSquared(to) > 0.25) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int streaks = Vfx.n(ctx(), 12);
            for (int i = 0; i < streaks; i++) {
                Vector jitter = new Vector(random.nextDouble(-0.3, 0.3), random.nextDouble(-0.7, 0.7),
                        random.nextDouble(-0.3, 0.3));
                Vfx.trail(fromBody.clone().add(jitter), toBody.clone().add(jitter),
                        i % 3 == 0 ? PALETTE.core() : PALETTE.body(), 4 + random.nextInt(4), true);
            }
        }
        Fx.sound(from, Sound.ENTITY_BREEZE_JUMP, 1.0f, 1.3f + Fx.jitter(0.1f));
        Fx.sound(to, Sound.ENTITY_BREEZE_LAND, 1.0f, 1.2f + Fx.jitter(0.1f));
        Fx.sound(to, Sound.ENTITY_BREEZE_CHARGE, 1.0f, 1.3f);
    }

    /**
     * 백열권: the target is rooted while a storm of punches lands, every punch a pair of glassy fist afterimages
     * from the shoulders and a spark of impact, under a hit counter. The last blow is a giant palm that launches it.
     */
    private final class Barrage implements LongPredicate {

        private final Player owner;
        private final LivingEntity target;
        private final int hits;
        private final long duration;
        private final double hitDamage;
        private final NamespacedKey[] root;
        private int punches;
        private @Nullable TextDisplay counter;

        Barrage(Player owner, LivingEntity target) {
            this.owner = owner;
            this.target = target;
            this.hits = Math.max(1, params().integer("ult_hits"));
            this.duration = Math.max(10, params().ticks("ult_duration"));
            this.hitDamage = Math.max(0, params().num("ult_hit_damage"));
            this.root = root(target, WINDUP_TICKS + duration + 2);
        }

        void begin() {
            target.setVelocity(new Vector(0, Math.min(0, target.getVelocity().getY()), 0));
            guardLanding(owner);
            Location body = Fx.body(target);
            Vfx.dot(body, Particle.FLASH, PALETTE.body(), true);
            Vfx.helix(ctx(), owner.getLocation(), 0.7, owner.getHeight() + 0.3, 1.5, 2, 12, 0,
                    Particle.DUST_COLOR_TRANSITION, GLOW, true);
            Fx.sound(body, Sound.ENTITY_BREEZE_INHALE, 1.0f, 0.8f);
            counter = Displays.text(ctx(), owner, labelSpot(), Text.mm(counterText(0)), 0.01f,
                    WINDUP_TICKS + duration + 20);
            Displays.animateNextTick(ctx(), owner, counter, Displays.centered(0, 0, 0, 0.9f, 0.9f, 0.9f, 0, 0, 0), 4);
            Loop.repeat(ctx(), owner, 0, 1, WINDUP_TICKS + duration + 1, this, this::release);
        }

        @Override
        public boolean test(long tick) {
            if (!Combat.isTargetable(ctx(), owner, target) || !target.getWorld().equals(owner.getWorld())
                    || owner.getLocation().distance(target.getLocation()) > BARRAGE_REACH) {
                return false;
            }
            if (counter != null && counter.isValid()) {
                counter.teleport(labelSpot());
            }
            if (tick < WINDUP_TICKS) {
                windup(tick);
                return true;
            }
            long elapsed = tick - WINDUP_TICKS;
            if (elapsed >= duration) {
                finisher();
                return false;
            }
            while (punches < hits && punches * duration / hits <= elapsed) {
                punch(punches++);
            }
            return true;
        }

        /** The stance: a lock-on ring closes in on the target while wind gathers around the owner. */
        private void windup(long tick) {
            double t = (tick + 1.0) / WINDUP_TICKS;
            Location feet = target.getLocation().add(0, 0.1, 0);
            Vfx.ring(ctx(), feet, 1.6 - 1.0 * t, 16, tick * 0.5, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.1f),
                    true);
            Location chest = owner.getLocation().add(0, owner.getHeight() * 0.55, 0);
            Vfx.ring(ctx(), chest, 1.1 - 0.6 * t, 12, -tick * 0.6, Particle.DUST, BODY, true);
            Vfx.cloud(ctx(), owner.getLocation().add(0, 0.1, 0), Particle.CLOUD, 3, 0.4, 0.02, null, false);
        }

        private void punch(int index) {
            Combat.hit(ctx(), owner, target, hitDamage);
            if (!Combat.isTargetable(ctx(), owner, target)) {
                return;
            }
            // Pinned in place: the hits carry no knockback, any drift is cancelled.
            target.setVelocity(new Vector(0, Math.min(0, target.getVelocity().getY()), 0));
            ThreadLocalRandom random = ThreadLocalRandom.current();
            Vector toward = target.getLocation().toVector().subtract(owner.getLocation().toVector()).setY(0);
            Vector forward = toward.lengthSquared() < 1.0E-6 ? Motion.horizontalFacing(owner.getLocation())
                    : toward.normalize();
            Vector side = new Vector(-forward.getZ(), 0, forward.getX());
            Location impact = target.getLocation().subtract(forward.clone().multiply(target.getWidth() * 0.5 + 0.05))
                    .add(side.clone().multiply(random.nextDouble(-0.35, 0.35)))
                    .add(0, target.getHeight() * random.nextDouble(0.3, 0.85), 0);
            int hand = index % 2 == 0 ? 1 : -1;
            Location shoulder = owner.getLocation().add(0, owner.getHeight() * 0.72, 0)
                    .add(side.clone().multiply(0.38 * hand)).add(forward.clone().multiply(0.3));
            fist(shoulder, impact, forward);
            Vfx.trail(shoulder, impact, PALETTE.core(), 2, false);

            double progress = (double) (index + 1) / hits;
            Vfx.cloud(ctx(), impact, Particle.CRIT, 4, 0.12, 0.25, null, false);
            Vfx.cloud(ctx(), impact, Particle.DUST_COLOR_TRANSITION, 3, 0.15, 0, GLOW, false);
            verticalRing(impact, forward, 0.35 + 0.1 * random.nextDouble(), 8, random.nextDouble(TAU),
                    Particle.DUST, BODY, false);
            if (index % 4 == 3) {
                Vfx.dot(impact, Particle.SWEEP_ATTACK, null, false);
                Vfx.dot(impact, Particle.SMALL_GUST, null, false);
            }
            Fx.sound(impact, index % 2 == 0 ? Sound.ENTITY_PLAYER_ATTACK_WEAK : Sound.ENTITY_PLAYER_ATTACK_STRONG,
                    0.7f, (float) (0.9 + 0.8 * progress) + Fx.jitter(0.06f));
            if (index % 3 == 0) {
                Fx.sound(impact, Sound.ENTITY_BREEZE_DEFLECT, 0.4f, 1.6f + Fx.jitter(0.15f));
            }
            bumpCounter(index + 1);
        }

        /** A glassy fist afterimage thrown from the shoulder into the impact point, then popping. */
        private void fist(Location from, Location to, Vector forward) {
            Location start = flat(from);
            float yaw = (float) Math.toDegrees(Math.atan2(-forward.getX(), forward.getZ()));
            BlockDisplay display = Displays.block(ctx(), owner, start, FINGER_GLASS,
                    Displays.transform(0, 0, 0, FIST_SIZE * 0.6f, FIST_SIZE * 0.6f, FIST_SIZE * 0.9f, yaw, 0, 0),
                    FIST_LIFE_TICKS + 2);
            display.setTeleportDuration(FIST_FLIGHT_TICKS);
            Location end = flat(to);
            ctx().runLater(owner, 1, () -> {
                if (display.isValid()) {
                    display.teleport(end);
                    Displays.animate(display, Displays.transform(0, 0, 0, FIST_SIZE, FIST_SIZE, FIST_SIZE * 1.3f, yaw,
                            0, 0), FIST_FLIGHT_TICKS);
                }
            });
            ctx().runLater(owner, 1 + FIST_FLIGHT_TICKS, () -> {
                if (display.isValid()) {
                    Displays.shrinkAway(ctx(), owner, display, 2);
                }
            });
        }

        private void bumpCounter(int count) {
            TextDisplay label = counter;
            if (label == null || !label.isValid()) {
                return;
            }
            label.text(Text.mm(counterText(count)));
            float pop = (float) Math.min(1.5, 1.0 + 0.03 * count);
            Displays.animate(label, Displays.centered(0, 0, 0, pop * 1.25f, pop * 1.25f, pop * 1.25f, 0, 0, 0), 0);
            ctx().runLater(owner, 1, () -> Displays.animate(label, Displays.centered(0, 0, 0, pop, pop, pop, 0, 0,
                    0), 2));
        }

        private String counterText(int count) {
            return "<b><gradient:#FFFFFF:" + COLOR_TAG + ">" + count + " HIT</gradient></b>";
        }

        private Location labelSpot() {
            return flat(target.getLocation().add(0, target.getHeight() + 0.75, 0));
        }

        /** The last blow: a giant palm slams into the target and launches it away. */
        private void finisher() {
            Location body = Fx.body(target);
            Vector toward = target.getLocation().toVector().subtract(owner.getLocation().toVector()).setY(0);
            Vector forward = toward.lengthSquared() < 1.0E-6 ? Motion.horizontalFacing(owner.getLocation())
                    : toward.normalize();

            Location palmAt = body.clone().subtract(forward.clone().multiply(target.getWidth() * 0.5 + 0.9));
            Hand palm = new Hand(palmAt, facing(forward), 0.6f, 16);
            palm.outline(PALETTE.body());
            palm.poseNextTick(FINISHER_SCALE, 0.5f, 2);
            ctx().runLater(owner, 6, () -> palm.vanish(5));

            Combat.hit(ctx(), owner, target, Math.max(0, params().num("ult_final_damage")));
            ctx().removeModifier(target, root[0]);
            ctx().removeModifier(target, root[1]);
            if (Combat.isTargetable(ctx(), owner, target)) {
                double speed = Math.max(0, params().num("ult_launch")) / PUSH_PER_SPEED;
                if (Motion.push(target, forward.clone().multiply(speed).setY(LAUNCH_LIFT), MAX_SPEED)) {
                    windTrail(target, KNOCK_TRAIL_TICKS + 6);
                }
            }

            World world = body.getWorld();
            world.spawnParticle(Particle.FLASH, body, 1, 0, 0, 0, 0, PALETTE.core(), true);
            Vfx.dot(body, Particle.EXPLOSION, null, true);
            Vfx.dot(body, Particle.GUST_EMITTER_SMALL, null, true);
            impact(body, forward, 1.6);
            Vfx.shockwave(ctx(), owner, target.getLocation().add(0, 0.1, 0), 0.5, 3.5, 6, Particle.DUST_COLOR_TRANSITION,
                    PALETTE.glow(1.3f), true);
            Fx.sound(body, Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1.4f, 0.7f);
            Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.2f, 0.6f);
            Fx.sound(body, Sound.ITEM_MACE_SMASH_AIR, 1.0f, 0.9f + Fx.jitter(0.05f));
            Fx.sound(body, Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 1.6f + Fx.jitter(0.1f));
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>백열권!</b></" + COLOR_TAG + "> <white>" + (punches + 1)
                    + "연타</white>"));
        }

        /** Runs when the barrage ends by itself (finished, target lost or out of reach): the counter fades. */
        private void release() {
            TextDisplay label = counter;
            if (label != null && label.isValid()) {
                ctx().runLater(owner, 10, () -> {
                    if (label.isValid()) {
                        Displays.shrinkAway(ctx(), owner, label, 4);
                    }
                });
            }
            if (punches < hits && target.isValid()) {
                // Broken off early: let the target go right away.
                ctx().removeModifier(target, root[0]);
                ctx().removeModifier(target, root[1]);
            }
        }
    }

    /**
     * Keeps the owner's fall distance at zero until it has stood on the ground for a moment (at most
     * {@link #LANDING_GRACE_TICKS}), so stepping in front of an airborne enemy never ends in fall damage. A loop
     * rather than a {@link LandingGuard}, so it also protects a Gambler that borrowed the ultimate.
     */
    private void guardLanding(Player owner) {
        int[] grounded = {0};
        Loop.repeat(ctx(), owner, 0, 1, LANDING_GRACE_TICKS, run -> {
            owner.setFallDistance(0);
            grounded[0] = Motion.isGrounded(owner) || owner.isInWater() ? grounded[0] + 1 : 0;
            return grounded[0] < LandingGuard.SETTLE_TICKS;
        });
    }

    // ------------------------------------------------------------------ shared visuals

    /** An impact of wind: core flash, a ring blown outwards across the hit direction, sparks and a gust. */
    private void impact(Location at, Vector direction, double size) {
        Vfx.burst(ctx(), at, PALETTE, 0.7 * size, Particle.CLOUD, false);
        Vfx.dot(at, Particle.GUST, null, false);
        Vfx.cloud(ctx(), at, Particle.CRIT, (int) Math.round(10 * size), 0.3, 0.4, null, false);
        Vector axis = direction.clone();
        Location center = at.clone();
        Loop.repeat(ctx(), player(), 0, 1, 4, run -> {
            double radius = (0.6 + run * 0.45) * size;
            verticalRing(center.clone().add(axis.clone().multiply(0.25 * run)), axis, radius,
                    (int) Math.round(14 * size), run * 0.4, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.1f), false);
            return true;
        });
        Location glow = at.clone();
        Loop.repeat(ctx(), player(), 4, 2, AFTERGLOW_TICKS / 2, run -> {
            Location mote = Vfx.scatter(glow, 0.9 * size).add(0, ThreadLocalRandom.current().nextDouble(-0.5, 0.5), 0);
            Vfx.dot(mote, Particle.DUST_COLOR_TRANSITION, FADE, false);
            if (run % 2 == 0) {
                Vfx.dot(mote, Particle.END_ROD, null, false);
            }
            return true;
        });
    }

    /** White wind streaming off a body that was knocked away. */
    private void windTrail(LivingEntity body, int ticks) {
        Loop.repeat(ctx(), player(), 1, 1, ticks, run -> {
            if (!body.isValid() || body.isDead()) {
                return false;
            }
            Location at = Fx.body(body);
            body.getWorld().spawnParticle(Particle.CLOUD, at, 1, 0.15, 0.3, 0.15, 0.01);
            Vfx.dot(at.clone().add(0, 0.3, 0), Particle.DUST_COLOR_TRANSITION, GLOW, false);
            return true;
        });
    }

    /** A ring standing across {@code facing} (its normal) around {@code center}. */
    private <T> void verticalRing(Location center, Vector facing, double radius, int points, double phase,
                                  Particle particle, @Nullable T data, boolean force) {
        Vector normal = facing.lengthSquared() < 1.0E-6 ? new Vector(0, 0, 1) : facing.clone().normalize();
        Vector[] basis = basis(normal);
        int count = Vfx.n(ctx(), points);
        for (int i = 0; i < count; i++) {
            double angle = phase + i * TAU / count;
            Location at = center.clone().add(basis[0].clone().multiply(Math.cos(angle) * radius))
                    .add(basis[1].clone().multiply(Math.sin(angle) * radius));
            Vfx.dot(at, particle, data, force);
        }
    }

    /**
     * The wind hand: six glass boxes (palm, four fingers, thumb) that share one display location, posed together in a
     * frame facing the flight direction.
     */
    private final class Hand {

        private final List<BlockDisplay> parts = new ArrayList<>(HAND.length);
        private final Quaternionf frame;

        Hand(Location at, Quaternionf frame, float scale, long lifetime) {
            this.frame = frame;
            Location anchor = flat(at);
            for (Part part : HAND) {
                BlockDisplay display = Displays.block(ctx(), player(), anchor, part.palm() ? PALM_GLASS : FINGER_GLASS,
                        part.pose(frame, scale, 0), lifetime);
                display.setTeleportDuration(1);
                parts.add(display);
            }
        }

        void poseNextTick(float scale, float forward, int ticks) {
            ctx().runLater(player(), 1, () -> pose(scale, forward, ticks));
        }

        void pose(float scale, float forward, int ticks) {
            for (int i = 0; i < parts.size(); i++) {
                Displays.animate(parts.get(i), HAND[i].pose(frame, scale, forward), ticks);
            }
        }

        void moveTo(Location at) {
            Location anchor = flat(at);
            for (BlockDisplay part : parts) {
                if (part.isValid()) {
                    part.teleport(anchor);
                }
            }
        }

        void outline(Color color) {
            for (BlockDisplay part : parts) {
                Displays.outline(part, color);
            }
        }

        /** Spreads a little and pushes forward as it hits, then fades. */
        void slap(float scale) {
            pose(scale * 1.3f, 0.35f, 2);
            ctx().runLater(player(), 3, () -> vanish(4));
        }

        void vanish(int ticks) {
            for (BlockDisplay part : parts) {
                if (part.isValid()) {
                    Displays.shrinkAway(ctx(), player(), part, ticks);
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** The rotation that turns the hand frame (z forward, y up) to face {@code direction}. */
    private static Quaternionf facing(Vector direction) {
        double yaw = Math.atan2(-direction.getX(), direction.getZ());
        double pitch = Math.atan2(-direction.getY(), Math.hypot(direction.getX(), direction.getZ()));
        return new Quaternionf().rotateY((float) -yaw).rotateX((float) pitch);
    }

    /** Two unit vectors perpendicular to {@code normal} and to each other. */
    private static Vector[] basis(Vector normal) {
        Vector n = normal.clone().normalize();
        Vector helper = Math.abs(n.getY()) > 0.95 ? new Vector(1, 0, 0) : new Vector(0, 1, 0);
        Vector u = n.clone().crossProduct(helper).normalize();
        Vector v = n.clone().crossProduct(u).normalize();
        return new Vector[]{u, v};
    }

    /** A copy without yaw and pitch, so display entities are not turned by the entity rotation as well. */
    private static Location flat(Location location) {
        Location copy = location.clone();
        copy.setYaw(0);
        copy.setPitch(0);
        return copy;
    }
}
