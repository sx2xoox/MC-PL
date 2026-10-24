package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Body;
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
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import io.papermc.paper.event.player.PlayerArmSwingEvent;
import net.kyori.adventure.text.format.TextColor;
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
import org.bukkit.entity.Interaction;
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
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 쭉쭉이 — the stretchy rubber hero. A fully wound-up punch stretches its arm a little further (its rubber body takes no
 * fall damage but flies further when knocked back, and stiffens in the cold); it stretches both arms out to plant a
 * rubber band between two anchors that bounces back every enemy trying to cross it (and lets go to snap forward), and
 * as its ultimate it blows itself up into a huge rubber ball that bumps enemies away like billiard balls: the bump barely
 * hurts, but an enemy knocked into a wall or into another enemy does.
 *
 * <p>Look (rubber mint, elastic purple, stripe white): a mint arm shoots out to the target and snaps back with a twang on
 * a wound-up punch. The band is three strands (purple, white, purple) stretched between two mint posts topped with white
 * gloves, the mint arms reaching from the shoulders to them; a crossing enemy dents the strands deep, and they whip
 * back. The ball is a teal glass orb with purple and white stripes turning around it, squashing on landings and hissing
 * flat at the end.
 */
public final class RubberAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("rubber", "쭉쭉이", Material.RESIN_CLUMP)
            .color(TextColor.color(0x5FD3B5))
            .difficulty(2)
            .passive("늘어나는 팔",
                    "힘을 모은 근접 공격(공격 게이지 {stretch_charge}% 이상)만 팔이 쭉 늘어나 사거리가 {stretch_reach}블록 "
                            + "늘어납니다. 고무 몸이라 낙하 피해를 받지 않는 대신 바닐라 넉백을 {knockback_taken}% 더 "
                            + "받습니다. 얼어붙음·눈덩이·빙판 같은 냉기 피해를 받으면 {harden_duration}초간 고무가 굳어 "
                            + "늘어나는 팔이 사라지고 이동 속도가 {harden_slow}% 느려집니다.")
            .active("고무줄 튕기기",
                    "두 팔을 쭉 늘여 바라보는 곳({band_min}~{band_max}블록 앞) 양쪽 바닥이나 벽에 손을 박고, 폭 최대 "
                            + "{band_width}·높이 {band_height}블록의 고무줄을 {band_duration}초간 걸어 둡니다(내가 "
                            + "{band_leash}블록 넘게 멀어지면 풀림). 나와 아군은 그냥 지나가지만, 줄을 넘으려는 적은 줄이 쭉 "
                            + "늘어났다 '팅' — {snap_damage} 피해를 입고 온 쪽으로 약 {snap_bounce}블록 되튕겨 "
                            + "{snap_stagger}초간 휘청입니다(이동 속도 -{stagger_slow}%, 적마다 최대 {snap_max}번, 남에게 "
                            + "밀려 넘어와도 튕김). 적이 줄을 근접 공격으로 {cut_hits}번 치면 '툭' 끊기고 남은 쿨타임이 "
                            + "절반으로 줄어듭니다. 웅크리면 줄을 놓아 앞으로 '탁' 튕기며 줄 너머 {release_reach}블록 안의 "
                            + "적에게 {release_damage} 피해를 주고 약 {release_push}블록 밀쳐냅니다.",
                    9)
            .ultimate("빵빵 부풀기",
                    "숨을 크게 들이마셔 {ball_duration}초간 {ball_scale}배 크기의 동그란 고무공이 됩니다. 공격과 액티브를 "
                            + "쓸 수 없지만 받는 피해가 {ball_guard}% 줄고 이동 속도가 {ball_speed}%, 점프력이 "
                            + "{ball_jump}% 늘어납니다. 몸에 닿은 적은 '뽀용' {bump_damage} 피해를 입고 약 {bump_bounce}블록 "
                            + "튕겨 나갑니다(같은 적은 {bump_cooldown}초마다). 튕겨 나간 적이 벽이나 다른 적에 부딪히면 "
                            + "부딪힌 적 모두 {crash_damage} 피해를 입고 {crash_stagger}초간 휘청이며(적마다 최대 "
                            + "{crash_max}번), 맞은 적은 당구공처럼 이어서 튕겨 나갑니다. 끝나거나 F를 다시 누르면 "
                            + "'피쉬식' 바람이 빠집니다.")
            .ultimateNotStandalone()
            .param("stretch_charge", 90)
            .param("stretch_reach", 0.75)
            .param("knockback_taken", 25)
            .param("harden_duration", 3)
            .param("harden_slow", 15)
            .param("band_min", 3)
            .param("band_max", 8)
            .param("band_width", 7)
            .param("band_height", 2)
            .param("band_duration", 4)
            .param("band_leash", 10)
            .param("snap_damage", 3)
            .param("snap_bounce", 5)
            .param("snap_stagger", 1)
            .param("stagger_slow", 50)
            .param("snap_max", 2)
            .param("cut_hits", 2)
            .param("release_reach", 2)
            .param("release_damage", 2)
            .param("release_push", 4)
            .param("ball_duration", 6)
            .param("ball_scale", 2)
            .param("ball_guard", 30)
            .param("ball_speed", 10)
            .param("ball_jump", 60)
            .param("bump_damage", 1)
            .param("bump_bounce", 6)
            .param("bump_cooldown", 1)
            .param("crash_damage", 3)
            .param("crash_stagger", 0.5)
            .param("crash_max", 3)
            .build();

    private static final String MINT_TAG = "#5FD3B5";
    private static final String PURPLE_TAG = "#7A4FD8";
    private static final String ICE_TAG = "#9ED8F0";

    // ------------------------------------------------------------------ look
    /** Stripe white, rubber mint, elastic purple. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xF4F4F4, 0x5FD3B5, 0x7A4FD8);
    private static final Color MINT = Color.fromRGB(0x5FD3B5);
    private static final Color PURPLE = Color.fromRGB(0x7A4FD8);
    private static final Color WHITE = Color.fromRGB(0xF4F4F4);
    private static final Particle.DustOptions MINT_DUST = new Particle.DustOptions(MINT, 0.8f);
    private static final Particle.DustOptions PURPLE_DUST = new Particle.DustOptions(PURPLE, 1.0f);
    private static final Particle.DustOptions WHITE_DUST = new Particle.DustOptions(WHITE, 0.9f);
    private static final Particle.DustOptions ICE_DUST = new Particle.DustOptions(Color.fromRGB(0x9ED8F0), 0.9f);
    private static final Particle.DustTransition GLOW = PALETTE.glow(1.0f);
    private static final Particle.DustTransition FADE = PALETTE.fade(1.2f);

    /** Block types (block data is created on use: it needs a running server). */
    private static final Material ARM = Material.DIAMOND_BLOCK;
    private static final Material GLOVE = Material.WHITE_CONCRETE;
    private static final Material ELASTIC = Material.PURPLE_CONCRETE;
    private static final Material STRIPE = Material.WHITE_CONCRETE;
    private static final Material BALL_SHELL = Material.CYAN_STAINED_GLASS;

    /** Ability ids whose damage counts as cold (besides freezing). */
    private static final Set<String> COLD = Set.of("frost", "snowman", "skater");

    private static final int MAX_DISPLAYS = 24;
    private static final double TAU = Math.PI * 2;
    private static final double MAX_SPEED = 2.0;
    private static final double AIR_DRAG = 0.91;
    /** Ticks a bounced body flies before it lands, for a lift of about {@link #BOUNCE_LIFT}. */
    private static final int BOUNCE_AIR_TICKS = 11;
    private static final double BOUNCE_LIFT = 0.42;

    // passive
    private static final long READY_SPARK_PERIOD = 10;
    private static final int ARM_OUT_TICKS = 2;
    private static final int ARM_BACK_TICKS = 3;
    private static final long KNOCK_FX_PERIOD = 6;

    // active: rubber band
    private static final double BAND_DEFAULT_HEIGHT = 2;
    /** The three strands at these shares of the band's height. */
    private static final double[] STRAND_HEIGHTS = {0.17, 0.52, 0.85};
    private static final float STRAND_WIDTH = 0.07f;
    private static final float POST_WIDTH = 0.2f;
    private static final float GLOVE_SIZE = 0.3f;
    private static final float ARM_WIDTH = 0.14f;
    private static final double BULGE_DEPTH = 0.9;
    private static final double TOUCH_MARGIN = 0.12;
    /** A bounced enemy is left alone this long, so one crossing does not count twice. */
    private static final long BOUNCE_GRACE = 12;
    private static final double INTERACTION_SPACING = 1.0;
    private static final float INTERACTION_WIDTH = 0.9f;
    private static final double MIN_WIDTH = 1.0;
    private static final double SIDE_RAY_HEIGHT = 1.0;
    private static final long SLACK_TICKS = 6;

    // ultimate: rubber ball
    private static final double CONTACT_MARGIN = 0.25;
    /** Three turned cubes make a roundish rubber ball. */
    private static final int SHELL_PARTS = 3;
    /** A cube's side as a share of the ball's size (its corners stick out, its faces sit inside). */
    private static final float SHELL_SHARE = 0.8f;
    private static final long FLING_MAX_TICKS = 30;
    private static final double WALL_PROBE = 0.3;
    private static final double TRANSFER = 0.8;
    private static final long LANDING_GRACE = 40;

    // ------------------------------------------------------------------ state

    private final List<Display> live = new ArrayList<>();
    private @Nullable RubberListener listener;
    private @Nullable NamespacedKey reach;
    private @Nullable NamespacedKey hardenSlow;
    private long hardenUntil = Long.MIN_VALUE / 2;
    private long lastSwing = Long.MIN_VALUE / 2;
    private long lastKnockFx = Long.MIN_VALUE / 2;
    private boolean stretchedHit;
    private @Nullable Band band;
    private @Nullable Ball ball;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onGrant() {
        RubberListener created = new RubberListener();
        Bukkit.getPluginManager().registerEvents(created, ctx().plugin());
        listener = created;
    }

    @Override
    public void onRevoke() {
        if (listener != null) {
            HandlerList.unregisterAll(listener);
            listener = null;
        }
        reach = null;
        hardenSlow = null;
        band = null;
        ball = null;
        live.clear();
    }

    /**
     * Other players' input and the knockback the owner takes: arm swings (the attack meter), the band being struck, F to
     * let the air out of the ball.
     */
    private final class RubberListener implements Listener {

        @EventHandler(priority = EventPriority.MONITOR)
        public void onSwing(PlayerArmSwingEvent event) {
            if (event.getPlayer().equals(player()) && event.getHand() == EquipmentSlot.HAND) {
                lastSwing = ctx().currentTick();
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onHeld(PlayerItemHeldEvent event) {
            if (event.getPlayer().equals(player())) {
                lastSwing = ctx().currentTick();
            }
        }

        /** The rubber body flies further from vanilla knockback (abilities push with velocities instead). */
        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onKnockback(EntityKnockbackEvent event) {
            if (!event.getEntity().equals(player())) {
                return;
            }
            switch (event.getCause()) {
                case ENTITY_ATTACK, SWEEP_ATTACK, DAMAGE, EXPLOSION -> {
                    double extra = 1 + Math.max(0, params().num("knockback_taken")) / 100.0;
                    Vector knock = event.getKnockback();
                    event.setKnockback(new Vector(knock.getX() * extra, knock.getY(), knock.getZ() * extra));
                    wobble();
                }
                default -> {
                }
            }
        }

        /** A melee blow on the band (its hitboxes are only shown to the owner's enemies). */
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onBandStruck(EntityDamageByEntityEvent event) {
            Band current = band;
            if (current != null && event.getEntity() instanceof Interaction part && current.owns(part)) {
                current.struck(event.getDamager(), part, event.getCause());
            }
        }

        /** F again while the ball is up: the air goes out (the match itself cancels the swap). */
        @EventHandler(priority = EventPriority.MONITOR)
        public void onSwap(PlayerSwapHandItemsEvent event) {
            Ball current = ball;
            if (current != null && event.getPlayer().equals(player()) && ctx().currentTick() > current.started) {
                current.deflate();
            }
        }
    }

    // ------------------------------------------------------------------ passive: stretchy arms

    /**
     * The owner's attack meter (0..1), kept like the server keeps it: it empties on every arm swing (Paper resets the
     * attack strength when it handles the swing), on a hit and on a change of the held item, and refills over
     * 20 / attack speed ticks ({@code Player#getAttackCooldown} reads the same meter, with the same half-tick nudge).
     */
    private double attackCharge(Player owner, long now) {
        AttributeInstance speed = owner.getAttribute(Attribute.ATTACK_SPEED);
        double full = 20.0 / Math.max(0.1, speed == null ? 4.0 : speed.getValue());
        return Math.clamp((now - lastSwing + 0.5) / full, 0.0, 1.0);
    }

    private boolean hardened() {
        return ctx().currentTick() < hardenUntil;
    }

    @Override
    public void onTick(long tick) {
        Player owner = player();
        long now = ctx().currentTick();
        boolean hard = hardened();
        boolean ready = !hard && ball == null
                && attackCharge(owner, now) >= Math.clamp(params().num("stretch_charge") / 100.0, 0.0, 1.0);
        if (ready && reach == null) {
            double extra = Math.max(0, params().num("stretch_reach"));
            if (extra > 0) {
                reach = ctx().addModifier(owner, owner, Attribute.ENTITY_INTERACTION_RANGE, extra,
                        Operation.ADD_NUMBER, 0);
            }
        } else if (!ready && reach != null) {
            ctx().removeModifier(owner, reach);
            reach = null;
        }
        if (ready && tick % READY_SPARK_PERIOD == 0) {
            Vfx.dot(rightHand(owner), Particle.DUST, MINT_DUST, false);
        }
        if (hard && tick % 4 == 0) {
            Vfx.dot(Fx.body(owner).add(Fx.jitter(0.35f), Fx.jitter(0.6f), Fx.jitter(0.35f)), Particle.DUST, ICE_DUST,
                    false);
            if (tick % 12 == 0) {
                Vfx.dot(owner.getEyeLocation().add(0, 0.3, 0), Particle.SNOWFLAKE, null, false);
            }
        }
    }

    private boolean isOwnMelee(EntityDamageByEntityEvent event) {
        return !ctx().isAbilityDamage() && player().equals(event.getDamager())
                && (event.getCause() == DamageCause.ENTITY_ATTACK || event.getCause() == DamageCause.ENTITY_SWEEP_ATTACK);
    }

    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled() || !isOwnMelee(event)) {
            return;
        }
        long now = ctx().currentTick();
        if (ball != null) {
            // A ball has no fists.
            event.setCancelled(true);
            if (now % 10 == 0) {
                player().sendActionBar(Text.mm("<" + MINT_TAG + ">공처럼 부풀어 공격할 수 없습니다</" + MINT_TAG + ">"));
            }
            return;
        }
        stretchedHit = reach != null;
        lastSwing = now;
    }

    /** A wound-up punch landed: the arm shot out to the victim and snaps back. */
    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (!isOwnMelee(event) || !stretchedHit) {
            return;
        }
        stretchedHit = false;
        Player owner = player();
        if (seen(owner, victim)) {
            stretchArm(owner, Fx.body(victim));
        }
        Fx.sound(owner.getLocation(), Sound.BLOCK_NOTE_BLOCK_BANJO, 0.8f, 1.6f + Fx.jitter(0.1f));
        Fx.sound(owner.getLocation(), Sound.ENTITY_SLIME_SQUISH_SMALL, 0.8f, 1.3f + Fx.jitter(0.1f));
    }

    /** A mint arm from the right shoulder to {@code target}, out in a flash and back with a twang. */
    private void stretchArm(Player owner, Location target) {
        if (!reserve(2)) {
            return;
        }
        Location shoulder = rightShoulder(owner);
        Vector3f along = target.toVector().subtract(shoulder.toVector()).toVector3f();
        BlockDisplay arm = keep(Displays.block(ctx(), owner, shoulder, ARM.createBlockData(),
                beam(new Vector3f(), new Vector3f(along).mul(0.1f), ARM_WIDTH), 12));
        BlockDisplay glove = keep(Displays.block(ctx(), owner, shoulder, GLOVE.createBlockData(),
                cube(new Vector3f(along).mul(0.1f), GLOVE_SIZE * 0.8f), 12));
        Displays.animateNextTick(ctx(), owner, arm, beam(new Vector3f(), along, ARM_WIDTH), ARM_OUT_TICKS);
        Displays.animateNextTick(ctx(), owner, glove, cube(along, GLOVE_SIZE * 0.8f), ARM_OUT_TICKS);
        ctx().runLater(owner, 1 + ARM_OUT_TICKS, () -> {
            Displays.animate(arm, beam(new Vector3f(), new Vector3f(along).mul(0.05f), ARM_WIDTH), ARM_BACK_TICKS);
            Displays.animate(glove, cube(new Vector3f(along).mul(0.05f), GLOVE_SIZE * 0.6f), ARM_BACK_TICKS);
        });
        ctx().runLater(owner, 1 + ARM_OUT_TICKS + ARM_BACK_TICKS, () -> {
            arm.remove();
            glove.remove();
        });
        Vfx.dot(target, Particle.DUST_COLOR_TRANSITION, GLOW, false);
        Vfx.cloud(ctx(), target, Particle.CRIT, 4, 0.15, 0.2, null, false);
    }

    /** A wobble of rubber when it is knocked flying. */
    private void wobble() {
        long now = ctx().currentTick();
        if (now - lastKnockFx < KNOCK_FX_PERIOD) {
            return;
        }
        lastKnockFx = now;
        Player owner = player();
        Vfx.ring(ctx(), Fx.body(owner), 0.5, 10, 0, Particle.DUST, MINT_DUST, false);
        Fx.sound(owner.getLocation(), Sound.ENTITY_SLIME_JUMP_SMALL, 0.7f, 1.3f + Fx.jitter(0.1f));
    }

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled()) {
            return;
        }
        Player owner = player();
        if (Combat.isFall(event)) {
            // A rubber body just bounces.
            event.setCancelled(true);
            boing(owner);
            return;
        }
        Ball current = ball;
        if (current != null) {
            if (event.getCause() == DamageCause.SUFFOCATION) {
                event.setCancelled(true);
                return;
            }
            double guard = Math.clamp(params().num("ball_guard") / 100.0, 0.0, 1.0);
            event.setDamage(event.getDamage() * (1 - guard));
        }
        if (isCold(event)) {
            harden(owner);
        }
    }

    /**
     * Cold: freezing, or the skills of a frost, snowman or skater participant (its ability damage and its thrown snow;
     * a plain sword blow of theirs is no colder than anyone's).
     */
    private boolean isCold(EntityDamageEvent event) {
        if (event.getCause() == DamageCause.FREEZE) {
            return true;
        }
        if (!(event instanceof EntityDamageByEntityEvent byEntity)
                || !ctx().isAbilityDamage() && !(byEntity.getDamager() instanceof Projectile)) {
            return false;
        }
        Player attacker = ctx().resolveAttacker(byEntity.getDamager());
        Ability ability = attacker == null ? null : ctx().abilityOf(attacker);
        return ability != null && COLD.contains(ability.info().id());
    }

    /** The cold stiffens the rubber: no stretching and a slower body for a while. */
    private void harden(Player owner) {
        long ticks = Math.max(1, params().ticks("harden_duration"));
        boolean fresh = !hardened();
        hardenUntil = ctx().currentTick() + ticks;
        if (hardenSlow != null) {
            ctx().removeModifier(owner, hardenSlow);
            hardenSlow = null;
        }
        double slow = Math.clamp(params().num("harden_slow") / 100.0, 0.0, 1.0);
        if (slow > 0) {
            hardenSlow = ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, -slow, Operation.MULTIPLY_SCALAR_1,
                    ticks);
        }
        if (reach != null) {
            ctx().removeModifier(owner, reach);
            reach = null;
        }
        if (fresh) {
            Location body = Fx.body(owner);
            Vfx.cloud(ctx(), body, Particle.DUST, 12, 0.35, 0, ICE_DUST, false);
            Vfx.cloud(ctx(), body, Particle.SNOWFLAKE, 6, 0.3, 0.02, null, false);
            Fx.sound(body, Sound.BLOCK_GLASS_HIT, 1.0f, 0.6f);
            Fx.sound(body, Sound.ENTITY_PLAYER_HURT_FREEZE, 0.6f, 1.2f);
            owner.sendActionBar(Text.mm("<" + ICE_TAG + ">❄ 고무가 굳었다!</" + ICE_TAG + "> <gray>"
                    + AbilityInfo.formatNumber(params().num("harden_duration")) + "초간 팔이 늘어나지 않습니다</gray>"));
        }
    }

    /** A fall into a rubber body: "뽀잉". */
    private void boing(Player owner) {
        Location feet = owner.getLocation().add(0, 0.1, 0);
        Vfx.ring(ctx(), feet, 0.7, 14, 0, Particle.DUST_COLOR_TRANSITION, GLOW, false);
        Vfx.ring(ctx(), feet, 1.1, 18, 0.2, Particle.DUST, PURPLE_DUST, false);
        Fx.sound(feet, Sound.ENTITY_SLIME_JUMP, 0.9f, 0.9f + Fx.jitter(0.1f));
        Fx.sound(feet, Sound.BLOCK_SLIME_BLOCK_FALL, 0.8f, 1.2f);
    }

    // ------------------------------------------------------------------ active: rubber band

    @Override
    public ActivationResult onActive() {
        if (ball != null) {
            return ActivationResult.fail("공처럼 부풀어 팔을 뻗을 수 없습니다");
        }
        Player owner = player();
        Band planned = Band.plan(this, owner);
        if (planned == null) {
            return ActivationResult.fail("고무줄을 걸 자리가 없습니다");
        }
        Band old = band;
        if (old != null) {
            old.slacken();
        }
        band = planned;
        planned.stretch();
        return ActivationResult.success();
    }

    /**
     * The rubber band: three strands between two posts, a vertical sheet across the owner's facing. Enemies trying to
     * get across are bounced back the way they came (by position, whoever pushed them); the owner and its allies pass.
     * Its hitboxes (interaction entities only the owner's enemies can see) can be cut; a fresh crouch lets it go.
     */
    private static final class Band implements LongPredicate {

        private final RubberAbility rubber;
        private final Player owner;
        private final Location center;
        /** Pointing away from the owner's side at the cast. */
        private final Vector normal;
        /** Along the band, to the owner's left. */
        private final Vector tangent;
        private final double left;
        private final double right;
        private final double height;
        private final long duration;
        private final Map<UUID, Integer> sides = new HashMap<>();
        private final Map<UUID, Integer> bounces = new HashMap<>();
        private final Map<UUID, Long> resting = new HashMap<>();
        private final List<BlockDisplay> strands = new ArrayList<>();
        private final List<BlockDisplay> fixtures = new ArrayList<>();
        private final List<Interaction> hitboxes = new ArrayList<>();
        private @Nullable BlockDisplay leftArm;
        private @Nullable BlockDisplay rightArm;
        private double bulgeAt;
        private double bulge;
        private long bulgeTick = Long.MIN_VALUE / 2;
        private int cuts;
        private boolean wasSneaking;
        private boolean done;

        private Band(RubberAbility rubber, Player owner, Location center, Vector normal, double left, double right) {
            this.rubber = rubber;
            this.owner = owner;
            this.center = center;
            this.normal = normal;
            this.tangent = new Vector(normal.getZ(), 0, -normal.getX());
            this.left = left;
            this.right = right;
            this.height = Math.max(0.5, rubber.params().num("band_height"));
            this.duration = Math.max(1, rubber.params().ticks("band_duration"));
            this.wasSneaking = owner.isSneaking();
            this.bulgeAt = (left - right) / 2;
        }

        /**
         * Where the band goes: on the ground where the owner looks, {@code band_min}..{@code band_max} blocks ahead,
         * across its facing; each end reaches out to a wall or to half the width. Null if there is no ground or room.
         */
        static @Nullable Band plan(RubberAbility rubber, Player owner) {
            Location eye = owner.getEyeLocation();
            Vector facing = Motion.horizontalFacing(eye);
            double min = Math.max(1, rubber.params().num("band_min"));
            double max = Math.max(min, rubber.params().num("band_max"));
            Vector look = eye.getDirection();
            double distance = max;
            if (look.lengthSquared() > 1.0E-6) {
                RayTraceResult hit = eye.getWorld().rayTraceBlocks(eye, look.normalize(), max + 2,
                        FluidCollisionMode.NEVER, true);
                if (hit != null) {
                    Vector flat = hit.getHitPosition().subtract(eye.toVector()).setY(0);
                    distance = flat.length() - 0.3;
                } else if (look.getY() < 0) {
                    distance = max;
                }
            }
            distance = Math.clamp(distance, min, max);
            World world = eye.getWorld();
            // Back towards the owner until the band stands on open ground (not inside a wall).
            for (double d = distance; d >= min - 1.0E-6; d -= 0.5) {
                Location spot = owner.getLocation().add(facing.clone().multiply(d)).add(0, 1.2, 0);
                double probe = spot.getY();
                Spots.snapToGround(world, spot, 4.5);
                if (spot.getY() >= probe - 1.0E-6) {
                    continue; // no ground under it within reach
                }
                if (!Spots.isFree(world, spot, 0.4, Math.min(1.8, rubber.params().num("band_height")))
                        || !Spots.hasGroundBelow(world, spot) || !rubber.ctx().isInsideArena(spot)
                        || !Spots.clearPath(eye, spot.clone().add(0, 0.5, 0))) {
                    continue;
                }
                double half = Math.max(MIN_WIDTH, rubber.params().num("band_width")) / 2;
                Vector tangent = new Vector(facing.getZ(), 0, -facing.getX());
                double l = reachOut(world, spot, tangent, half);
                double r = reachOut(world, spot, tangent.clone().multiply(-1), half);
                if (l + r < MIN_WIDTH) {
                    continue;
                }
                return new Band(rubber, owner, spot, facing, l, r);
            }
            return null;
        }

        /** How far the band reaches to one side: up to {@code half}, or to the first wall. */
        private static double reachOut(World world, Location center, Vector way, double half) {
            Location from = center.clone().add(0, SIDE_RAY_HEIGHT, 0);
            RayTraceResult wall = world.rayTraceBlocks(from, way, half, FluidCollisionMode.NEVER, true);
            return wall == null ? half : Math.max(0, wall.getHitPosition().distance(from.toVector()) - 0.05);
        }

        /** The point on the band {@code t} along it (+ to the owner's left), at height {@code y} over the ground. */
        private Location point(double t, double y) {
            return center.clone().add(tangent.clone().multiply(t)).add(0, y, 0);
        }

        /** The band in the center's frame: {@code t} along, {@code y} up, {@code s} across (+ away from the owner). */
        private Vector3f local(double t, double y, double s) {
            Vector v = tangent.clone().multiply(t).add(new Vector(0, y, 0)).add(normal.clone().multiply(s));
            return v.toVector3f();
        }

        void stretch() {
            Location shoulder = rubber.rightShoulder(owner);
            if (rubber.reserve(STRAND_HEIGHTS.length * 2 + 6)) {
                for (int i = 0; i < STRAND_HEIGHTS.length; i++) {
                    Material type = i == 1 ? STRIPE : ELASTIC;
                    for (int half = 0; half < 2; half++) {
                        BlockDisplay strand = rubber.keep(Displays.block(rubber.ctx(), owner, center,
                                type.createBlockData(), beam(local(0, 0, 0), new Vector3f(0.01f), 0.01f), 0));
                        strands.add(strand);
                    }
                }
                for (int end = 0; end < 2; end++) {
                    double t = end == 0 ? left : -right;
                    BlockDisplay post = rubber.keep(Displays.block(rubber.ctx(), owner, center, ARM.createBlockData(),
                            beam(local(t, 0, 0), new Vector3f(0, 0.01f, 0), POST_WIDTH), 0));
                    BlockDisplay glove = rubber.keep(Displays.block(rubber.ctx(), owner, center,
                            GLOVE.createBlockData(), cube(local(t, height, 0), 0.02f), 0));
                    fixtures.add(post);
                    fixtures.add(glove);
                }
                leftArm = rubber.keep(Displays.block(rubber.ctx(), owner, point(left, height), ARM.createBlockData(),
                        beam(new Vector3f(), new Vector3f(0.01f), ARM_WIDTH), 0));
                rightArm = rubber.keep(Displays.block(rubber.ctx(), owner, point(-right, height),
                        ARM.createBlockData(), beam(new Vector3f(), new Vector3f(0.01f), ARM_WIDTH), 0));
                rubber.ctx().runLater(owner, 1, () -> {
                    for (int end = 0; end < 2; end++) {
                        double t = end == 0 ? left : -right;
                        Displays.animate(fixtures.get(end * 2), beam(local(t, 0, 0),
                                new Vector3f(0, (float) height, 0), POST_WIDTH), 3);
                        Displays.animate(fixtures.get(end * 2 + 1), cube(local(t, height, 0), GLOVE_SIZE), 3);
                    }
                    poseStrands(3);
                });
            }
            for (double t = -right + INTERACTION_SPACING / 2; t <= left - INTERACTION_SPACING / 2 + 1.0E-6;
                 t += INTERACTION_SPACING) {
                Location at = point(t, 0);
                Interaction hitbox = rubber.ctx().track(owner, at.getWorld().spawn(Displays.unrotated(at),
                        Interaction.class, box -> {
                            box.setPersistent(false);
                            box.setInteractionWidth(INTERACTION_WIDTH);
                            box.setInteractionHeight((float) height);
                            box.setResponsive(true);
                        }));
                hitboxes.add(hitbox);
            }
            hideHitboxesFromFriends();
            Vfx.line(rubber.ctx(), shoulder, point(left, height), 3, Particle.DUST, MINT_DUST, false);
            Vfx.line(rubber.ctx(), shoulder, point(-right, height), 3, Particle.DUST, MINT_DUST, false);
            for (Location anchor : List.of(point(left, 0.1), point(-right, 0.1))) {
                Vfx.burst(rubber.ctx(), anchor, PALETTE, 0.4, Particle.CRIT, false);
                Block ground = anchor.clone().subtract(0, 0.3, 0).getBlock();
                if (!ground.isPassable()) {
                    anchor.getWorld().spawnParticle(Particle.BLOCK, anchor, Vfx.n(rubber.ctx(), 8), 0.2, 0.05, 0.2, 0,
                            ground.getBlockData());
                }
            }
            Fx.sound(shoulder, Sound.ITEM_CROSSBOW_LOADING_END, 1.0f, 1.4f);
            Fx.sound(center, Sound.BLOCK_TRIPWIRE_ATTACH, 1.0f, 0.8f);
            Fx.sound(center, Sound.ENTITY_SLIME_SQUISH, 0.8f, 1.2f);
            owner.sendActionBar(Text.mm("<" + MINT_TAG + ">쭈욱—</" + MINT_TAG + "> <gray>고무줄 " + Fx.seconds(duration)
                    + "초 · 웅크리면 탁!</gray>"));
            Loop.repeat(rubber.ctx(), owner, 1, 1, duration, this, this::slacken);
        }

        /** The hitboxes are for the owner's enemies to strike: the owner and its allies never see them. */
        private void hideHitboxesFromFriends() {
            for (Player viewer : owner.getWorld().getPlayers()) {
                if (viewer.equals(owner) || rubber.ctx().isAlly(owner, viewer)) {
                    for (Interaction hitbox : hitboxes) {
                        viewer.hideEntity(rubber.ctx().plugin(), hitbox);
                    }
                }
            }
        }

        boolean owns(Interaction part) {
            return !done && hitboxes.contains(part);
        }

        @Override
        public boolean test(long tick) {
            if (done) {
                return false;
            }
            if (!owner.getWorld().equals(center.getWorld()) || leash() > rubber.params().num("band_leash")) {
                return false;
            }
            boolean sneaking = owner.isSneaking();
            boolean pressed = sneaking && !wasSneaking;
            wasSneaking = sneaking;
            if (pressed) {
                release();
                return false;
            }
            guard();
            poseArms();
            long age = rubber.ctx().currentTick() - bulgeTick;
            if (age <= 8) {
                poseStrands(2);
            }
            if (tick % 4 == 0) {
                double t = ThreadLocalRandom.current().nextDouble(-right, left);
                Vfx.dot(point(t, height * STRAND_HEIGHTS[2]), Particle.WAX_OFF, null, false);
            }
            return true;
        }

        /** Horizontal distance from the owner to the band. */
        private double leash() {
            Vector rel = owner.getLocation().toVector().subtract(center.toVector());
            double t = Math.clamp(rel.dot(tangent), -right, left);
            double s = rel.dot(normal);
            return Math.hypot(rel.dot(tangent) - t, s);
        }

        /** Every enemy at the band that came from one side and is getting to the other is bounced back. */
        private void guard() {
            long now = rubber.ctx().currentTick();
            double reach = Math.max(left, right) + 3;
            for (LivingEntity enemy : rubber.ctx().enemiesNear(owner, center, reach)) {
                UUID id = enemy.getUniqueId();
                Location at = enemy.getLocation();
                Vector rel = at.toVector().subtract(center.toVector());
                double s = rel.dot(normal);
                double t = rel.dot(tangent);
                double half = enemy.getWidth() / 2;
                boolean across = t >= -right - half && t <= left + half && at.getY() < center.getY() + height
                        && at.getY() + enemy.getHeight() > center.getY();
                boolean touching = Math.abs(s) <= half + TOUCH_MARGIN;
                Integer side = sides.get(id);
                if (!across || (!touching && (side == null || Math.signum(s) == side))) {
                    if (!touching) {
                        sides.put(id, s > 0 ? 1 : -1);
                    }
                    continue;
                }
                if (side == null) {
                    continue; // it was there when the band went up: it picks a side when it steps off
                }
                if (now < resting.getOrDefault(id, Long.MIN_VALUE / 2)) {
                    continue;
                }
                int used = bounces.getOrDefault(id, 0);
                if (used >= Math.max(0, rubber.params().integer("snap_max"))) {
                    if (Math.signum(s) != side && !touching) {
                        // Worn out: the band lets it slip through.
                        sides.put(id, -side);
                        slip(enemy, t);
                    }
                    continue;
                }
                bounces.put(id, used + 1);
                resting.put(id, now + BOUNCE_GRACE);
                bounce(enemy, side, t);
            }
        }

        /** "팅": the band stretches with the enemy and throws it back the way it came. */
        private void bounce(LivingEntity enemy, int side, double t) {
            Combat.hit(rubber.ctx(), owner, enemy, rubber.params().num("snap_damage"));
            if (Combat.isTargetable(rubber.ctx(), owner, enemy)) {
                double distance = Math.max(0, rubber.params().num("snap_bounce"));
                double speed = distance * (1 - AIR_DRAG) / (1 - Math.pow(AIR_DRAG, BOUNCE_AIR_TICKS));
                Motion.push(enemy, normal.clone().multiply(side * speed).setY(BOUNCE_LIFT), MAX_SPEED);
                enemy.setFallDistance(0);
                rubber.stagger(owner, enemy, rubber.params().ticks("snap_stagger"));
            }
            Location contact = point(t, height * 0.5);
            Fx.sound(contact, Sound.BLOCK_NOTE_BLOCK_BANJO, 1.0f, 0.7f + Fx.jitter(0.05f));
            Fx.sound(contact, Sound.ITEM_CROSSBOW_SHOOT, 0.9f, 1.4f + Fx.jitter(0.1f));
            Fx.sound(contact, Sound.ENTITY_SLIME_JUMP, 0.8f, 1.0f);
            if (!seen(owner, enemy)) {
                return; // heard, never seen
            }
            bulgeAt = Math.clamp(t, -right + 0.3, left - 0.3);
            bulge = -side * BULGE_DEPTH;
            bulgeTick = rubber.ctx().currentTick();
            poseStrands(1);
            Vfx.burst(rubber.ctx(), contact, PALETTE, 0.5, Particle.WAX_OFF, false);
            rubber.pop(owner, contact.clone().add(0, 0.9, 0), "<b><" + MINT_TAG + ">팅!</" + MINT_TAG + "></b>", 1.3f);
        }

        /** A worn-out band gives way with a sad stretch. */
        private void slip(LivingEntity enemy, double t) {
            Location contact = point(t, height * 0.5);
            Fx.sound(contact, Sound.ENTITY_SLIME_SQUISH_SMALL, 0.8f, 0.6f);
            if (seen(owner, enemy)) {
                Vfx.cloud(rubber.ctx(), contact, Particle.DUST, 6, 0.3, 0, PURPLE_DUST, false);
            }
        }

        /** The strands: two segments each, meeting at the bulge point (straight when nothing presses on them). */
        private void poseStrands(int ticks) {
            long age = rubber.ctx().currentTick() - bulgeTick;
            double depth;
            if (age <= 2) {
                depth = bulge;
            } else if (age <= 5) {
                depth = -bulge * 0.35; // the whip back past straight
            } else {
                depth = 0;
            }
            for (int i = 0; i < STRAND_HEIGHTS.length; i++) {
                double y = height * STRAND_HEIGHTS[i];
                Vector3f a = local(left, y, 0);
                Vector3f m = local(bulgeAt, y, depth * (0.6 + 0.4 * (1 - Math.abs(STRAND_HEIGHTS[i] - 0.5) * 2)));
                Vector3f b = local(-right, y, 0);
                BlockDisplay first = strands.size() > i * 2 ? strands.get(i * 2) : null;
                BlockDisplay second = strands.size() > i * 2 + 1 ? strands.get(i * 2 + 1) : null;
                if (first != null && first.isValid()) {
                    Displays.animate(first, beam(a, new Vector3f(m).sub(a), STRAND_WIDTH), ticks);
                }
                if (second != null && second.isValid()) {
                    Displays.animate(second, beam(m, new Vector3f(b).sub(m), STRAND_WIDTH), ticks);
                }
            }
        }

        /** The mint arms reaching from the owner's shoulders to the posts. */
        private void poseArms() {
            Location leftShoulder = rubber.leftShoulder(owner);
            Location rightShoulder = rubber.rightShoulder(owner);
            BlockDisplay l = leftArm;
            if (l != null && l.isValid()) {
                Vector3f along = leftShoulder.toVector().subtract(l.getLocation().toVector()).toVector3f();
                Displays.animate(l, beam(new Vector3f(), along, ARM_WIDTH), 1);
            }
            BlockDisplay r = rightArm;
            if (r != null && r.isValid()) {
                Vector3f along = rightShoulder.toVector().subtract(r.getLocation().toVector()).toVector3f();
                Displays.animate(r, beam(new Vector3f(), along, ARM_WIDTH), 1);
            }
        }

        /** Struck by an enemy's melee blow: a twang, and the band snaps after enough of them. */
        void struck(Entity damager, Interaction part, DamageCause cause) {
            if (done || cause != DamageCause.ENTITY_ATTACK && cause != DamageCause.ENTITY_SWEEP_ATTACK) {
                return;
            }
            Player attacker = rubber.ctx().resolveAttacker(damager);
            if (attacker == null || !attacker.equals(damager) || !rubber.ctx().isEnemy(owner, attacker)) {
                return;
            }
            cuts++;
            Location at = part.getLocation().add(0, height * 0.6, 0);
            Fx.sound(at, Sound.BLOCK_NOTE_BLOCK_BANJO, 0.8f, 1.8f);
            Vfx.cloud(rubber.ctx(), at, Particle.DUST, 5, 0.2, 0, PURPLE_DUST, false);
            if (cuts >= Math.max(1, rubber.params().integer("cut_hits"))) {
                cut(at);
            }
        }

        /** "툭": the band snaps in two, the halves whip back to the posts and the arms let go. */
        private void cut(Location at) {
            if (done) {
                return;
            }
            done = true;
            rubber.band = rubber.band == this ? null : rubber.band;
            double remaining = rubber.ctx().cooldownRemaining(owner);
            if (remaining > 0) {
                rubber.ctx().reduceCooldown(owner, remaining / 2);
            }
            Fx.sound(at, Sound.ENTITY_ITEM_BREAK, 1.0f, 0.8f);
            Fx.sound(at, Sound.BLOCK_TRIPWIRE_DETACH, 1.0f, 0.7f);
            Vfx.burst(rubber.ctx(), at, PALETTE, 0.5, Particle.WAX_OFF, false);
            rubber.pop(owner, at.clone().add(0, 0.7, 0), "<b><" + PURPLE_TAG + ">툭</" + PURPLE_TAG + "></b>", 1.2f);
            owner.sendActionBar(Text.mm("<" + PURPLE_TAG + ">고무줄이 끊겼다!</" + PURPLE_TAG + "> <gray>남은 쿨타임 "
                    + "절반</gray>"));
            for (int i = 0; i < strands.size(); i++) {
                BlockDisplay strand = strands.get(i);
                if (!strand.isValid()) {
                    continue;
                }
                double y = height * STRAND_HEIGHTS[i / 2];
                boolean first = i % 2 == 0;
                Vector3f anchor = first ? local(left, y, 0) : local(-right, y, 0);
                Displays.animate(strand, beam(anchor, new Vector3f(0, -0.05f, 0), STRAND_WIDTH), 3);
            }
            fadeAll(5);
        }

        /** "탁": let go, the band whips forward and smacks whoever stands just beyond it. */
        void release() {
            if (done) {
                return;
            }
            done = true;
            rubber.band = rubber.band == this ? null : rubber.band;
            Vector rel = owner.getLocation().toVector().subtract(center.toVector());
            int far = rel.dot(normal) > 0 ? -1 : 1;
            double reach = Math.max(0, rubber.params().num("release_reach"));
            int hits = 0;
            for (LivingEntity enemy : rubber.ctx().enemiesNear(owner, center, Math.max(left, right) + reach + 1)) {
                Vector at = enemy.getLocation().toVector().subtract(center.toVector());
                double s = at.dot(normal) * far;
                double t = at.dot(tangent);
                double half = enemy.getWidth() / 2;
                if (s < -half || s > reach + half || t < -right - half || t > left + half) {
                    continue;
                }
                Combat.hit(rubber.ctx(), owner, enemy, rubber.params().num("release_damage"));
                hits++;
                if (Combat.isTargetable(rubber.ctx(), owner, enemy)) {
                    double distance = Math.max(0, rubber.params().num("release_push"));
                    double speed = distance * (1 - AIR_DRAG) / (1 - Math.pow(AIR_DRAG, BOUNCE_AIR_TICKS));
                    Motion.push(enemy, normal.clone().multiply(far * speed).setY(BOUNCE_LIFT * 0.8), MAX_SPEED);
                    enemy.setFallDistance(0);
                }
            }
            bulgeAt = (left - right) / 2;
            bulge = far * BULGE_DEPTH * 1.4;
            bulgeTick = rubber.ctx().currentTick();
            poseStrands(2);
            Location mid = point(bulgeAt, height * 0.5).add(normal.clone().multiply(far * 0.8));
            Vfx.burst(rubber.ctx(), mid, PALETTE, 0.6, Particle.CRIT, false);
            Vfx.arc(rubber.ctx(), point(bulgeAt, height * 0.5), 1.2, yawOf(normal.clone().multiply(far)), 120, 14,
                    Particle.DUST_COLOR_TRANSITION, GLOW, false);
            rubber.pop(owner, mid.clone().add(0, 0.8, 0), "<b><" + MINT_TAG + ">탁!</" + MINT_TAG + "></b>", 1.4f);
            Fx.sound(mid, Sound.ITEM_CROSSBOW_SHOOT, 1.2f, 0.8f);
            Fx.sound(mid, Sound.ENTITY_SLIME_ATTACK, 1.0f, 0.9f);
            Fx.sound(mid, Sound.ENTITY_BREEZE_WIND_BURST, 0.6f, 1.5f);
            owner.sendActionBar(Text.mm("<" + MINT_TAG + ">탁!</" + MINT_TAG + ">" + (hits > 0 ? " <white>" + hits
                    + "명</white>" : "")));
            fadeAll(SLACK_TICKS);
        }

        /** Time is up or the owner wandered off: the band goes slack and the arms come home. */
        void slacken() {
            if (done) {
                return;
            }
            done = true;
            rubber.band = rubber.band == this ? null : rubber.band;
            for (int i = 0; i < strands.size(); i++) {
                BlockDisplay strand = strands.get(i);
                if (!strand.isValid()) {
                    continue;
                }
                double y = height * STRAND_HEIGHTS[i / 2] * 0.3;
                Vector3f a = i % 2 == 0 ? local(left, y, 0) : local((left - right) / 2, 0.05, 0);
                Vector3f b = i % 2 == 0 ? local((left - right) / 2, 0.05, 0) : local(-right, y, 0);
                Displays.animate(strand, beam(a, new Vector3f(b).sub(a), STRAND_WIDTH), 4);
            }
            Fx.sound(center, Sound.ENTITY_SLIME_SQUISH_SMALL, 0.8f, 0.7f);
            fadeAll(SLACK_TICKS);
        }

        /** Every part of the band shrinks away; the arms snap back to the shoulders first. */
        private void fadeAll(long after) {
            for (BlockDisplay arm : new BlockDisplay[] {leftArm, rightArm}) {
                if (arm != null && arm.isValid()) {
                    Displays.animate(arm, beam(new Vector3f(), new Vector3f(0, 0.01f, 0), ARM_WIDTH * 0.5f), 3);
                }
            }
            for (Interaction hitbox : hitboxes) {
                hitbox.remove();
            }
            hitboxes.clear();
            List<Display> parts = new ArrayList<>(strands);
            parts.addAll(fixtures);
            if (leftArm != null) {
                parts.add(leftArm);
            }
            if (rightArm != null) {
                parts.add(rightArm);
            }
            rubber.ctx().runLater(owner, Math.max(1, after), () -> {
                for (Display part : parts) {
                    if (part.isValid()) {
                        Displays.shrinkAway(rubber.ctx(), owner, part, 4);
                    }
                }
            });
        }
    }

    /** Staggered: walking slowed for a moment. */
    private void stagger(Player owner, LivingEntity enemy, long ticks) {
        double slow = Math.clamp(params().num("stagger_slow") / 100.0, 0.0, 1.0);
        if (ticks > 0 && slow > 0) {
            ctx().addModifier(owner, enemy, Attribute.MOVEMENT_SPEED, -slow, Operation.MULTIPLY_SCALAR_1, ticks);
        }
    }

    // ------------------------------------------------------------------ ultimate: rubber ball

    @Override
    public ActivationResult onUltimate() {
        if (ball != null) {
            return ActivationResult.fail("이미 부풀어 있습니다");
        }
        Player owner = player();
        double scale = Math.max(1, params().num("ball_scale"));
        // Headroom is what matters (the grown eyes must not end up in a ceiling); a wall at the side only squeezes the
        // ball, and nothing suffocates it while it is one.
        if (!Spots.isFree(owner.getWorld(), owner.getLocation(), Body.PLAYER.width(), Body.PLAYER.height() * scale)) {
            return ActivationResult.fail("부풀어 오를 공간이 없습니다");
        }
        Ball started = new Ball(owner, scale);
        ball = started;
        started.inflate();
        return ActivationResult.success();
    }

    /**
     * The rubber ball: a big, bouncy body that bumps every enemy it touches away (once a second each); a bumped enemy
     * that hits a wall or another enemy on its way hurts (and the other enemy flies on, like a billiard ball).
     */
    private final class Ball implements LongPredicate {

        private final Player owner;
        private final double scale;
        private final long duration;
        final long started;
        private final List<NamespacedKey> boosts = new ArrayList<>();
        private final Map<UUID, Long> nextBump = new HashMap<>();
        private final Map<UUID, Integer> crashes = new HashMap<>();
        private final List<BlockDisplay> shell = new ArrayList<>();
        private boolean airborne;
        private boolean done;

        Ball(Player owner, double scale) {
            this.owner = owner;
            this.scale = scale;
            this.duration = Math.max(1, params().ticks("ball_duration"));
            this.started = ctx().currentTick();
        }

        void inflate() {
            boosts.add(ctx().addModifier(owner, owner, Attribute.SCALE, scale - 1, Operation.ADD_NUMBER, duration));
            double speed = params().num("ball_speed") / 100.0;
            if (speed != 0) {
                boosts.add(ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, speed,
                        Operation.MULTIPLY_SCALAR_1, duration));
            }
            double jump = params().num("ball_jump") / 100.0;
            if (jump != 0) {
                boosts.add(ctx().addModifier(owner, owner, Attribute.JUMP_STRENGTH, jump,
                        Operation.MULTIPLY_SCALAR_1, duration));
            }
            // A bouncy landing after it went flat again never hurts either (this one outlasts the ball on purpose).
            ctx().addModifier(owner, owner, Attribute.FALL_DAMAGE_MULTIPLIER, -1.0, Operation.MULTIPLY_SCALAR_1,
                    duration + LANDING_GRACE);
            if (reach != null) {
                ctx().removeModifier(owner, reach);
                reach = null;
            }
            Location center = middle();
            float size = (float) (scale * 1.5);
            if (reserve(SHELL_PARTS)) {
                for (int i = 0; i < SHELL_PARTS; i++) {
                    BlockDisplay part = keep(Displays.block(ctx(), owner, center, BALL_SHELL.createBlockData(),
                            shellPose(i, 0, 0.2f), duration + 20));
                    part.setTeleportDuration(1);
                    owner.hideEntity(ctx().plugin(), part);
                    shell.add(part);
                    Displays.animateNextTick(ctx(), owner, part, shellPose(i, 0, size), 6);
                }
            }
            Fx.sound(center, Sound.ENTITY_PUFFER_FISH_BLOW_UP, 1.2f, 0.7f);
            Fx.sound(center, Sound.ENTITY_PLAYER_BREATH, 1.0f, 0.5f);
            Fx.sound(center, Sound.ENTITY_SLIME_SQUISH, 1.0f, 0.6f);
            Vfx.sphere(ctx(), center, scale * 0.8, 40, Particle.DUST_COLOR_TRANSITION, GLOW, true);
            pop(owner, owner.getLocation().add(0, owner.getHeight() * scale + 0.4, 0),
                    "<b><" + MINT_TAG + ">빵빵!</" + MINT_TAG + "></b>", 1.6f);
            owner.sendActionBar(Text.mm("<b><" + MINT_TAG + ">빵빵 부풀기</" + MINT_TAG + "></b> <gray>"
                    + Fx.seconds(duration) + "초 · 부딪혀 튕겨 내세요 · F로 바람 빼기</gray>"));
            Loop.repeat(ctx(), owner, 1, 1, duration, this, this::deflate);
        }

        /** The middle of the (grown) body. */
        private Location middle() {
            return owner.getLocation().add(0, owner.getHeight() * 0.5, 0);
        }

        @Override
        public boolean test(long tick) {
            if (done) {
                return false;
            }
            Location center = middle();
            float size = (float) (owner.getHeight() * 0.85);
            for (int i = 0; i < shell.size(); i++) {
                BlockDisplay part = shell.get(i);
                if (part.isValid()) {
                    Displays.moveTo(part, center);
                    if (tick % 2 == 0) {
                        Displays.animate(part, shellPose(i, tick * 6f, size), 2);
                    }
                }
            }
            stripes(center, size, tick);
            landing(tick);
            bump(center);
            return true;
        }

        /** Purple and white stripes turning around the ball. */
        private void stripes(Location center, float size, long tick) {
            if (tick % 2 != 0) {
                return;
            }
            double radius = size * 0.55;
            double phase = tick * 0.12;
            int points = Vfx.n(ctx(), 14);
            for (int i = 0; i < points; i++) {
                double angle = phase + i * TAU / points;
                double y = Math.sin(angle * 2 + phase) * radius * 0.25;
                Vfx.dot(center.clone().add(Math.cos(angle) * radius, y, Math.sin(angle) * radius), Particle.DUST,
                        i % 2 == 0 ? PURPLE_DUST : WHITE_DUST, false);
            }
        }

        /** A squash and a "뽀잉" whenever the ball comes down. */
        private void landing(long tick) {
            boolean grounded = Motion.isGrounded(owner);
            if (!grounded) {
                airborne = true;
                return;
            }
            if (airborne && tick > 2) {
                Location feet = owner.getLocation().add(0, 0.1, 0);
                Vfx.ring(ctx(), feet, owner.getWidth() * 0.9, 16, 0, Particle.DUST_COLOR_TRANSITION, GLOW, false);
                Fx.sound(feet, Sound.ENTITY_SLIME_JUMP, 1.0f, 0.6f + Fx.jitter(0.05f));
                for (int i = 0; i < shell.size(); i++) {
                    BlockDisplay part = shell.get(i);
                    if (part.isValid()) {
                        float size = (float) (owner.getHeight() * 0.85);
                        Displays.animate(part, Displays.transform(0, 0, 0, size * 1.15f, size * 0.8f, size * 1.15f,
                                tick * 6f + i * 45f, i * 35.26f, 0), 2);
                    }
                }
            }
            airborne = false;
        }

        /** Enemies touching the ball are bumped away, each at most once per bump cooldown. */
        private void bump(Location center) {
            long now = ctx().currentTick();
            BoundingBox body = owner.getBoundingBox().expand(CONTACT_MARGIN);
            for (LivingEntity enemy : ctx().enemiesNear(owner, center, owner.getHeight())) {
                if (!body.overlaps(enemy.getBoundingBox())
                        || now < nextBump.getOrDefault(enemy.getUniqueId(), Long.MIN_VALUE / 2)) {
                    continue;
                }
                nextBump.put(enemy.getUniqueId(), now + Math.max(1, params().ticks("bump_cooldown")));
                Vector away = Motion.horizontal(enemy.getLocation().toVector().subtract(owner.getLocation().toVector()));
                if (away.lengthSquared() < 1.0E-6) {
                    away = Motion.horizontalFacing(owner.getLocation());
                }
                Combat.hit(ctx(), owner, enemy, params().num("bump_damage"));
                if (Combat.isTargetable(ctx(), owner, enemy)) {
                    double distance = Math.max(0, params().num("bump_bounce"));
                    double speed = distance * (1 - AIR_DRAG) / (1 - Math.pow(AIR_DRAG, BOUNCE_AIR_TICKS));
                    if (Motion.push(enemy, away.clone().multiply(speed).setY(BOUNCE_LIFT), MAX_SPEED)) {
                        enemy.setFallDistance(0);
                        new Fling(this, enemy, away, speed).start();
                    }
                }
                Location contact = Fx.body(enemy);
                Fx.sound(contact, Sound.ENTITY_SLIME_JUMP, 1.0f, 1.2f + Fx.jitter(0.1f));
                Fx.sound(contact, Sound.BLOCK_NOTE_BLOCK_BANJO, 0.7f, 1.0f + Fx.jitter(0.1f));
                if (seen(owner, enemy)) {
                    Vfx.burst(ctx(), contact, PALETTE, 0.4, Particle.WAX_OFF, false);
                    pop(owner, contact.clone().add(0, 1.0, 0), "<b><" + MINT_TAG + ">뽀용</" + MINT_TAG + "></b>",
                            1.1f);
                }
            }
        }

        /** True if {@code enemy} may still be hurt by a crash; counts it. */
        boolean crash(LivingEntity enemy) {
            int used = crashes.getOrDefault(enemy.getUniqueId(), 0);
            if (used >= Math.max(0, params().integer("crash_max"))) {
                return false;
            }
            crashes.put(enemy.getUniqueId(), used + 1);
            return true;
        }

        /** The air goes out: "피쉬식", the ball shrinks back to the owner. */
        void deflate() {
            if (done) {
                return;
            }
            done = true;
            if (ball == this) {
                ball = null;
            }
            for (NamespacedKey key : boosts) {
                ctx().removeModifier(owner, key);
            }
            boosts.clear();
            for (BlockDisplay part : shell) {
                if (part.isValid()) {
                    Displays.shrinkAway(ctx(), owner, part, 6);
                }
            }
            Location center = owner.getLocation().add(0, 1.0, 0);
            Fx.sound(center, Sound.ENTITY_PUFFER_FISH_BLOW_OUT, 1.2f, 0.7f);
            Fx.sound(center, Sound.BLOCK_FIRE_EXTINGUISH, 0.5f, 1.8f);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < Vfx.n(ctx(), 6); i++) {
                Vector puff = new Vector(random.nextDouble(-1, 1), random.nextDouble(0, 0.6), random.nextDouble(-1, 1))
                        .multiply(0.25);
                center.getWorld().spawnParticle(Particle.CLOUD, center, 0, puff.getX(), puff.getY(), puff.getZ(), 1.0);
            }
            Vfx.cloud(ctx(), center, Particle.DUST, 10, 0.6, 0, MINT_DUST, false);
            pop(owner, owner.getLocation().add(0, 2.4, 0), "<" + MINT_TAG + ">피쉬식…</" + MINT_TAG + ">", 1.0f);
            if (owner.isOnline()) {
                owner.sendActionBar(Text.mm("<" + MINT_TAG + ">피쉬식…</" + MINT_TAG + "> <gray>바람이 빠졌습니다</gray>"));
            }
        }
    }

    /**
     * An enemy bumped by the ball, watched in flight: hitting a wall or another enemy hurts (both of them), and the other
     * enemy flies on.
     */
    private final class Fling implements LongPredicate {

        private final Ball ball;
        private final LivingEntity enemy;
        private final Vector direction;
        private final double speed;

        Fling(Ball ball, LivingEntity enemy, Vector direction, double speed) {
            this.ball = ball;
            this.enemy = enemy;
            this.direction = direction.clone();
            this.speed = speed;
        }

        void start() {
            Loop.repeat(ctx(), player(), 1, 1, FLING_MAX_TICKS, this);
        }

        @Override
        public boolean test(long tick) {
            Player owner = player();
            if (!Combat.isTargetable(ctx(), owner, enemy)) {
                return false;
            }
            if (tick >= 3 && (Motion.isGrounded(enemy) || enemy.isInWater())) {
                return false; // landed in the open
            }
            BoundingBox box = enemy.getBoundingBox();
            BoundingBox probe = new BoundingBox(box.getMinX() + 0.05, box.getMinY() + 0.3, box.getMinZ() + 0.05,
                    box.getMaxX() - 0.05, box.getMaxY() - 0.05, box.getMaxZ() - 0.05)
                    .shift(direction.getX() * WALL_PROBE, 0, direction.getZ() * WALL_PROBE);
            if (enemy.getWorld().hasCollisionsIn(probe)) {
                wall(owner);
                return false;
            }
            BoundingBox reach = box.clone().expand(0.3);
            for (LivingEntity other : ctx().enemiesNear(owner, Fx.body(enemy), 1.5)) {
                if (!other.equals(enemy) && reach.overlaps(other.getBoundingBox())) {
                    knock(owner, other);
                    return false;
                }
            }
            if (tick % 2 == 0 && seen(owner, enemy)) {
                Vfx.dot(Fx.body(enemy), Particle.DUST, MINT_DUST, false);
            }
            return true;
        }

        /** Into a wall: it hurts, and the enemy bounces off a little. */
        private void wall(Player owner) {
            Location contact = Fx.body(enemy).add(direction.clone().multiply(enemy.getWidth() * 0.5 + 0.1));
            if (ball.crash(enemy)) {
                Combat.hit(ctx(), owner, enemy, params().num("crash_damage"));
                stagger(owner, enemy, params().ticks("crash_stagger"));
            }
            if (Combat.isTargetable(ctx(), owner, enemy)) {
                enemy.setVelocity(direction.clone().multiply(-0.25).setY(0.2));
            }
            crashFx(owner, contact, enemy);
        }

        /** Into another enemy: both hurt, and the other one flies on like a billiard ball. */
        private void knock(Player owner, LivingEntity other) {
            Location contact = Fx.body(enemy).add(Fx.body(other)).multiply(0.5);
            if (ball.crash(enemy)) {
                Combat.hit(ctx(), owner, enemy, params().num("crash_damage"));
                stagger(owner, enemy, params().ticks("crash_stagger"));
            }
            if (ball.crash(other)) {
                Combat.hit(ctx(), owner, other, params().num("crash_damage"));
                stagger(owner, other, params().ticks("crash_stagger"));
            }
            if (Combat.isTargetable(ctx(), owner, enemy)) {
                enemy.setVelocity(direction.clone().multiply(speed * 0.15).setY(0.1));
            }
            if (Combat.isTargetable(ctx(), owner, other)
                    && Motion.push(other, direction.clone().multiply(speed * TRANSFER).setY(BOUNCE_LIFT * 0.8),
                    MAX_SPEED)) {
                other.setFallDistance(0);
                new Fling(ball, other, direction, speed * TRANSFER).start();
            }
            crashFx(owner, contact, other);
        }

        private void crashFx(Player owner, Location contact, LivingEntity other) {
            Fx.sound(contact, Sound.ENTITY_GOAT_RAM_IMPACT, 1.0f, 1.1f + Fx.jitter(0.1f));
            Fx.sound(contact, Sound.ENTITY_SLIME_ATTACK, 0.8f, 0.8f);
            if (!seen(owner, enemy) || !seen(owner, other)) {
                return;
            }
            Vfx.burst(ctx(), contact, PALETTE, 0.6, Particle.CRIT, false);
            Vfx.cloud(ctx(), contact, Particle.POOF, 4, 0.2, 0.05, null, false);
            pop(owner, contact.clone().add(0, 0.9, 0), "<b><" + PURPLE_TAG + ">쿵!</" + PURPLE_TAG + "></b>", 1.4f);
        }
    }

    /** A rotated cube of the ball's shell: two of them make a roundish ball. */
    private static Transformation shellPose(int index, float spin, float size) {
        float side = size * SHELL_SHARE;
        return switch (index) {
            case 0 -> Displays.transform(0, 0, 0, side, side, side, spin, 0, 0);
            case 1 -> Displays.transform(0, 0, 0, side, side, side, spin + 45f, 35.26f, 0);
            default -> Displays.transform(0, 0, 0, side, side, side, -spin + 22.5f, -35.26f, 45f);
        };
    }

    // ------------------------------------------------------------------ helpers

    private Location rightShoulder(Player owner) {
        return shoulder(owner, -1);
    }

    private Location leftShoulder(Player owner) {
        return shoulder(owner, 1);
    }

    /** A shoulder ({@code side} +1 left, -1 right) at the owner's current size. */
    private static Location shoulder(Player owner, int side) {
        Location feet = owner.getLocation();
        Vector facing = Motion.horizontalFacing(feet);
        Vector left = new Vector(facing.getZ(), 0, -facing.getX()).multiply(-1);
        double scale = owner.getHeight() / 1.8;
        return feet.add(0, 1.35 * scale, 0).add(left.multiply(0.38 * side * scale));
    }

    private static Location rightHand(Player owner) {
        Location eye = owner.getEyeLocation();
        Vector facing = Motion.horizontalFacing(eye);
        Vector right = new Vector(-facing.getZ(), 0, facing.getX());
        return eye.add(facing.multiply(0.4)).add(right.multiply(0.35)).subtract(0, 0.6, 0);
    }

    /** A word popping up and fading. */
    private void pop(Player owner, Location at, String mini, float scale) {
        if (!reserve(1)) {
            return;
        }
        TextDisplay text = keep(Displays.text(ctx(), owner, at, Text.mm(mini), 0.3f, 20));
        Displays.animateNextTick(ctx(), owner, text, Displays.centered(0, 0.3f, 0, scale, scale, scale, 0, 0, 0), 4);
        ctx().runLater(owner, 12, () -> {
            if (text.isValid()) {
                Displays.shrinkAway(ctx(), owner, text, 5);
            }
        });
    }

    /** True unless {@code entity} is a player hidden from {@code owner} (stealth): nothing may reveal it. */
    private static boolean seen(Player owner, Entity entity) {
        return !(entity instanceof Player hidden) || owner.canSee(hidden);
    }

    private boolean reserve(int count) {
        live.removeIf(display -> !display.isValid());
        return live.size() + count <= MAX_DISPLAYS;
    }

    private <T extends Display> T keep(T display) {
        live.add(display);
        return display;
    }

    /** Minecraft's yaw (degrees: 0 = +Z, 90 = -X) of the horizontal part of {@code direction}. */
    private static float yawOf(Vector direction) {
        Vector flat = Motion.horizontal(direction);
        if (flat.lengthSquared() < 1.0E-6) {
            return 0;
        }
        return (float) Math.toDegrees(Math.atan2(-flat.getX(), flat.getZ()));
    }

    /** A cube of {@code size} centered on {@code center} (relative to the display). */
    private static Transformation cube(Vector3f center, float size) {
        return box(center, new Vector3f(size), new Quaternionf());
    }

    /** A box of {@code size} centered on {@code center} (relative to the display), turned by {@code rotation}. */
    private static Transformation box(Vector3f center, Vector3f size, Quaternionf rotation) {
        Vector3f translation = new Vector3f(center).sub(new Quaternionf(rotation).transform(new Vector3f(size)
                .mul(0.5f)));
        return new Transformation(translation, new Quaternionf(rotation), new Vector3f(size), new Quaternionf());
    }

    /** A square bar of {@code width} from {@code start} along {@code along} (both relative to the display). */
    private static Transformation beam(Vector3f start, Vector3f along, float width) {
        float length = Math.max(0.001f, along.length());
        Quaternionf rotation = along.lengthSquared() < 1.0E-8f ? new Quaternionf()
                : new Quaternionf().rotationTo(new Vector3f(0, 0, 1), new Vector3f(along).normalize());
        Vector3f center = new Vector3f(start).add(new Vector3f(along).mul(0.5f));
        return box(center, new Vector3f(width, width, length), rotation);
    }
}
