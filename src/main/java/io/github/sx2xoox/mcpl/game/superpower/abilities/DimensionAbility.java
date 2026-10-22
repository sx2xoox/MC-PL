package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 차원술사 — folds space. A safety net pulls the owner back from a deadly fall or the void once per match, the active
 * links two dimension gates (place the exit, later step through an entrance to it, allies may follow), and the
 * ultimate tears the area apart and shuffles the enemies' positions.
 *
 * <p>Look: dark teal and violet. A gate is an upright octagonal ring of teal glass bars, slowly turning, around a
 * layered void of black and violet glass with portal motes falling into it and a teal spiral inside. The collapse marks
 * every enemy with a spinning violet sigil and a rising teal helix, then swaps them with streaks of light between the
 * old and new spots.
 */
public final class DimensionAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("dimension", "차원술사", Material.END_PORTAL_FRAME)
            .color(TextColor.color(0x2EC4B6))
            .difficulty(3)
            .passive("차원 안전망",
                    "치명적인 낙하 피해를 입거나 공허로 떨어지기 직전, 마지막으로 밟은 안전한 땅(출구 차원문이 있으면 그곳)으로 "
                            + "귀환합니다. 경기당 {rescue_charges}회.")
            .active("차원문",
                    "처음 쓰면 발밑에 출구 차원문을 엽니다({gate_duration}초 유지, 쿨타임 {mark_cooldown}초). 다시 쓰면 입구를 열어 "
                            + "{gate_range}블록 안의 출구로 이동하고 출구 주변 {burst_radius}블록의 적에게 {burst_damage} 피해를 줍니다. "
                            + "입구는 {gate_open}초간 열려 아군도 지나갈 수 있고, 웅크리고 쓰면 출구를 옮깁니다.",
                    12)
            .ultimate("차원 붕괴",
                    "반경 {ult_radius}블록의 적에게 차원 표식을 새기고 {ult_delay}초 뒤 표식이 새겨진 적들의 위치를 무작위로 "
                            + "뒤섞습니다(한 명이면 주변의 무작위 지점으로). 휘말린 적은 {ult_damage} 피해를 입고 "
                            + "{ult_nausea}초간 멀미, {ult_slow}초간 구속 II에 걸립니다.")
            .param("rescue_charges", 1)
            .param("gate_duration", 45)
            .param("mark_cooldown", 1)
            .param("gate_range", 60)
            .param("gate_open", 3)
            .param("burst_radius", 2.5)
            .param("burst_damage", 3)
            .param("ult_radius", 12)
            .param("ult_delay", 0.6)
            .param("ult_damage", 4)
            .param("ult_nausea", 3)
            .param("ult_slow", 1)
            .build();

    private static final String COLOR_TAG = "#2EC4B6";
    /** Pale teal light, teal body, deep violet void. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xA6FFF2, 0x2EC4B6, 0x3B1466);
    /** The violet accent of the void. */
    private static final Color VIOLET = Color.fromRGB(0x8E4DFF);
    private static final Particle.DustTransition SWIRL = new Particle.DustTransition(PALETTE.body(), VIOLET, 0.8f);
    private static final Particle.DustTransition GLOW = PALETTE.glow(1.0f);
    private static final Particle.DustTransition FADE = PALETTE.fade(1.0f);
    private static final Particle.DustOptions VIOLET_DUST = new Particle.DustOptions(VIOLET, 0.9f);
    private static final double TAU = Math.PI * 2;

    private static final double MAX_SPEED = 2.5;

    // passive: dimension safety net
    private static final long SAFE_SAMPLE_PERIOD = 10;
    /** The rescue happens this far below the bottom of the world, before the void starts to hurt. */
    private static final double VOID_MARGIN = 2;
    /** While a charge is left, a fall deeper than this shows the net opening under the owner. */
    private static final float NET_WARNING_FALL = 8;

    // active: dimension gates
    private static final double GROUND_SNAP = 2.5;
    /** A trip shorter than this is refused (the owner is standing at the exit already). */
    private static final double MIN_TRAVEL = 2;
    /** An ally whose hitbox comes this close to the entrance's middle is carried through. */
    private static final double ENTRANCE_REACH = 0.8;
    private static final double ALLY_OFFSET = 1.2;
    private static final double BURST_PUSH = 0.8;
    private static final double BURST_LIFT = 0.35;
    private static final long CONSUMED_CLOSE_DELAY = 12;

    // gate model
    private static final int BARS = 8;
    private static final float GATE_RADIUS = 1.05f;
    /** Height of the gate's middle above the ground it stands on. */
    private static final double GATE_HEIGHT = 1.15;
    private static final float BAR_THICKNESS = 0.11f;
    private static final float CORE_SIDE = 1.3f;
    private static final float CORE_DEPTH = 0.03f;
    private static final int OPEN_TICKS = 6;
    private static final int CLOSE_TICKS = 6;
    private static final long SPIN_PERIOD = 4;
    private static final float SPIN_STEP = 9f;
    private static final BlockData FRAME_GLASS = Material.CYAN_STAINED_GLASS.createBlockData();
    private static final BlockData VOID_GLASS = Material.BLACK_STAINED_GLASS.createBlockData();
    private static final BlockData RIFT_GLASS = Material.PURPLE_STAINED_GLASS.createBlockData();

    // ultimate: dimension collapse
    /** Enemies that get a display sigil (the rest get particles only), to cap display entities. */
    private static final int MAX_SIGILS = 4;
    private static final float SIGIL_SIDE = 1.5f;
    private static final float SIGIL_SPIN = 18f;
    private static final double MIN_DISPLACEMENT = 4;
    private static final int RANDOM_SPOT_TRIES = 24;
    private static final double RANDOM_SPOT_LIFT = 3;
    private static final double RANDOM_SPOT_DROP = 8;
    private static final int SLOW_AMPLIFIER = 1;
    private static final int AFTERGLOW_TICKS = 12;

    /** Where the owner last stood on safe ground, for the safety net. */
    private @Nullable Location lastSafe;
    private int rescuesUsed;
    private @Nullable Gate exit;
    /** The next active cooldown's multiplier: short after placing an exit, full after travelling. */
    private double nextCooldownFactor = 1.0;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        exit = null;
        lastSafe = null;
    }

    @Override
    public double cooldownMultiplier() {
        return nextCooldownFactor;
    }

    // ------------------------------------------------------------------ passive: dimension safety net

    @Override
    public void onTick(long tick) {
        Player owner = player();
        if (tick % SAFE_SAMPLE_PERIOD == 0) {
            remember(owner);
        }
        if (owner.getLocation().getY() < owner.getWorld().getMinHeight() - VOID_MARGIN) {
            rescue(owner);
        } else if (tick % 3 == 0 && owner.getFallDistance() > NET_WARNING_FALL
                && rescuesUsed < params().integer("rescue_charges")) {
            netFx(owner, tick);
        }
    }

    /** A deep fall with a charge left: a teal rift ring spreads under the owner, the net ready to catch it. */
    private void netFx(Player owner, long tick) {
        Location under = owner.getLocation().subtract(0, 0.6, 0);
        Vfx.ring(ctx(), under, 0.9, 14, tick * 0.3, Particle.DUST_COLOR_TRANSITION, SWIRL, false);
        Vfx.ring(ctx(), under.clone().subtract(0, 0.3, 0), 0.55, 8, -tick * 0.4, Particle.DUST, VIOLET_DUST, false);
        Vfx.dot(under, Particle.REVERSE_PORTAL, null, false);
    }

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled()) {
            return;
        }
        Player owner = player();
        // The final damage already has the absorption hearts taken off: compare it with the health alone.
        boolean lethalFall = Combat.isFall(event) && event.getFinalDamage() >= owner.getHealth();
        if ((lethalFall || isVoid(event)) && rescue(owner)) {
            event.setCancelled(true);
        }
    }

    private static boolean isVoid(EntityDamageEvent event) {
        return event.getCause() == DamageCause.VOID
                || event.getDamageSource().getDamageType() == DamageType.OUT_OF_WORLD;
    }

    /** Remembers the owner's spot while it stands on safe ground. */
    private void remember(Player owner) {
        if (owner.isInsideVehicle() || !Motion.isGrounded(owner)) {
            return;
        }
        Location feet = owner.getLocation();
        if (Spots.isSafe(ctx(), feet, Body.standing(owner))) {
            lastSafe = feet;
        }
    }

    /**
     * Pulls the owner back to the exit gate (if one is open and safe) or the last safe ground. False when no charge is
     * left or there is nowhere safe to go.
     */
    private boolean rescue(Player owner) {
        if (rescuesUsed >= params().integer("rescue_charges")) {
            return false;
        }
        Body body = Body.standing(owner);
        World world = owner.getWorld();
        Location destination = null;
        Gate gate = exit;
        boolean viaGate = false;
        if (gate != null && gate.isOpen() && gate.feet.getWorld().equals(world)
                && Spots.isSafe(ctx(), gate.feet, body)) {
            destination = gate.feet.clone();
            viaGate = true;
        } else if (lastSafe != null && lastSafe.getWorld().equals(world) && Spots.isSafe(ctx(), lastSafe, body)) {
            destination = lastSafe.clone();
        }
        if (destination == null) {
            return false;
        }
        Location from = owner.getLocation();
        destination.setYaw(from.getYaw());
        destination.setPitch(from.getPitch());
        if (!owner.teleport(destination, TeleportCause.PLUGIN)) {
            return false;
        }
        rescuesUsed++;
        owner.setVelocity(new Vector());
        owner.setFallDistance(0);
        rescueFx(owner, from, destination, viaGate);
        int left = Math.max(0, params().integer("rescue_charges") - rescuesUsed);
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>✦ 차원 안전망!</b></" + COLOR_TAG + "> <gray>남은 횟수 "
                + left + "</gray>"));
        return true;
    }

    /** The fall is folded away: an implosion where the owner was and a ring of light opening around it on the ground. */
    private void rescueFx(Player owner, Location from, Location to, boolean viaGate) {
        double height = owner.getHeight();
        if (from.getWorld().equals(to.getWorld()) && from.getY() > from.getWorld().getMinHeight()) {
            implode(from.clone().add(0, height * 0.5, 0), 1.2, 16, true);
        }
        Location body = to.clone().add(0, height * 0.5, 0);
        Vector facing = Motion.horizontalFacing(to);
        Loop.repeat(ctx(), owner, 0, 1, 6, run -> {
            double radius = 0.4 + run * 0.15;
            verticalRing(body, facing, radius, 20, run * 0.3, Particle.DUST_COLOR_TRANSITION, GLOW, true);
            return true;
        });
        Vfx.burst(ctx(), body, PALETTE, 0.8, Particle.REVERSE_PORTAL, true);
        Vfx.shockwave(ctx(), owner, to.clone().add(0, 0.1, 0), 0.3, 1.8, 6, Particle.DUST, VIOLET_DUST, true);
        Fx.sound(to, Sound.BLOCK_RESPAWN_ANCHOR_SET_SPAWN, 1.0f, 1.3f);
        Fx.sound(to, Sound.ENTITY_ENDERMAN_TELEPORT, 0.9f, 0.7f + Fx.jitter(0.05f));
        Fx.sound(to, Sound.BLOCK_BEACON_POWER_SELECT, 0.8f, 1.6f);
        Gate gate = exit;
        if (viaGate && gate != null) {
            gate.pulse();
        }
    }

    // ------------------------------------------------------------------ active: dimension gate

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        if (owner.isInsideVehicle()) {
            return ActivationResult.fail("탈것에 탄 채로는 사용할 수 없습니다");
        }
        Gate current = exit;
        if (current != null && !current.isOpen()) {
            exit = null;
            current = null;
        }
        if (current == null || owner.isSneaking()) {
            return placeExit(owner, current);
        }
        return travel(owner, current);
    }

    private ActivationResult placeExit(Player owner, @Nullable Gate previous) {
        Location feet = exitSpot(owner);
        if (feet == null) {
            return ActivationResult.fail("이곳에는 차원문을 열 수 없습니다");
        }
        if (previous != null) {
            previous.close();
        }
        long life = Math.max(20, params().ticks("gate_duration"));
        Gate gate = new Gate(feet, owner.getLocation().getYaw(), life, true);
        exit = gate;
        gate.open();
        Location body = gate.center();
        Vfx.cloud(ctx(), body, Particle.REVERSE_PORTAL, 24, 0.4, 0.05, null, false);
        Vfx.shockwave(ctx(), owner, feet.clone().add(0, 0.1, 0), 0.3, 1.6, 6, Particle.DUST_COLOR_TRANSITION, GLOW,
                false);
        Fx.sound(feet, Sound.BLOCK_END_PORTAL_FRAME_FILL, 1.0f, 0.9f + Fx.jitter(0.05f));
        Fx.sound(feet, Sound.BLOCK_END_PORTAL_FRAME_FILL, 0.8f, 1.35f + Fx.jitter(0.05f));
        Fx.sound(feet, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.8f, 1.2f + Fx.jitter(0.05f));
        double cooldown = params().cooldownSeconds();
        nextCooldownFactor = cooldown > 0 ? Math.max(0, params().num("mark_cooldown")) / cooldown : 1.0;
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">✦ 출구 차원문 설치</" + COLOR_TAG + "> <gray>다시 사용하면 귀환 ("
                + AbilityInfo.formatNumber(params().num("gate_duration")) + "초)</gray>"));
        return ActivationResult.success();
    }

    /** The owner's spot, lowered onto ground right below, if a body fits there safely; null otherwise. */
    private @Nullable Location exitSpot(Player owner) {
        Location feet = owner.getLocation();
        World world = feet.getWorld();
        Spots.snapToGround(world, feet, GROUND_SNAP);
        BoundingBox below = new BoundingBox(feet.getX() - 0.25, feet.getY() - 0.15, feet.getZ() - 0.25,
                feet.getX() + 0.25, feet.getY() - 0.01, feet.getZ() + 0.25);
        boolean standing = world.hasCollisionsIn(below) || feet.getBlock().isLiquid();
        if (!standing || !Spots.isSafe(ctx(), feet, Body.standing(owner))) {
            return null;
        }
        feet.setPitch(0);
        return feet;
    }

    private ActivationResult travel(Player owner, Gate gate) {
        Location from = owner.getLocation();
        if (!gate.feet.getWorld().equals(from.getWorld())) {
            return ActivationResult.fail("출구 차원문이 다른 곳에 있습니다");
        }
        double distance = from.distance(gate.feet);
        double range = params().num("gate_range");
        if (distance < MIN_TRAVEL) {
            return ActivationResult.fail("이미 출구 차원문 앞입니다 <gray>웅크리고 사용하면 출구를 옮깁니다</gray>");
        }
        if (distance > range) {
            return ActivationResult.fail("출구 차원문이 너무 멉니다 (" + Math.round(distance) + " / "
                    + AbilityInfo.formatNumber(range) + "블록) <gray>웅크리고 사용하면 출구를 옮깁니다</gray>");
        }
        Location destination = gate.feet.clone();
        destination.setYaw(from.getYaw());
        destination.setPitch(from.getPitch());
        if (!Spots.isSafe(ctx(), destination, Body.standing(owner))) {
            return ActivationResult.fail("출구 차원문이 막혀 있습니다 <gray>웅크리고 사용하면 출구를 옮깁니다</gray>");
        }
        if (!owner.teleport(destination, TeleportCause.PLUGIN)) {
            return ActivationResult.fail("차원 이동이 막혔습니다");
        }
        owner.setFallDistance(0);
        exit = null;
        nextCooldownFactor = 1.0;

        long open = Math.max(1, params().ticks("gate_open"));
        Location mouth = from.clone();
        Spots.snapToGround(mouth.getWorld(), mouth, GROUND_SNAP);
        Gate entrance = new Gate(mouth, from.getYaw(), open, false);
        entrance.open();
        gate.closeIn(open);
        gate.pulse();
        departFx(owner, from, destination);
        burst(owner, gate);
        Link link = new Link(owner, entrance, gate, open);
        Loop.repeat(ctx(), owner, 1, 1, open, link);
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">✦ 차원 이동</" + COLOR_TAG + "> <gray>"
                + Math.round(distance) + "블록</gray>"));
        return ActivationResult.success();
    }

    /** The departure: space folds in on the old spot and a stream of light races to the exit. */
    private void departFx(Player owner, Location from, Location to) {
        double height = owner.getHeight();
        Location fromBody = from.clone().add(0, height * 0.5, 0);
        Location toBody = to.clone().add(0, height * 0.5, 0);
        implode(fromBody, 1.3, 18, false);
        Vfx.cloud(ctx(), fromBody, Particle.REVERSE_PORTAL, 16, 0.3, 0.04, null, false);
        int duration = Math.clamp(Math.round(from.distance(to) * 0.6), 6, 30);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int motes = Vfx.n(ctx(), 10);
        for (int i = 0; i < motes; i++) {
            Vector jitter = new Vector(random.nextDouble(-0.3, 0.3), random.nextDouble(-0.6, 0.6),
                    random.nextDouble(-0.3, 0.3));
            Vfx.trail(fromBody.clone().add(jitter), toBody.clone().add(jitter), i % 3 == 0 ? VIOLET : PALETTE.body(),
                    duration + random.nextInt(4), false);
        }
        Fx.sound(from, Sound.ENTITY_ENDERMAN_TELEPORT, 0.9f, 0.8f + Fx.jitter(0.05f));
        Fx.sound(from, Sound.BLOCK_END_PORTAL_FRAME_FILL, 0.8f, 0.7f + Fx.jitter(0.05f));
        Fx.sound(to, Sound.ITEM_CHORUS_FRUIT_TELEPORT, 1.0f, 0.8f + Fx.jitter(0.05f));
        Fx.sound(to, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.8f, 1.4f + Fx.jitter(0.05f));
    }

    /** The arrival tears the space around the exit: nearby enemies are hurt and thrown away from it. */
    private void burst(Player owner, Gate gate) {
        double radius = Math.max(0, params().num("burst_radius"));
        double damage = Math.max(0, params().num("burst_damage"));
        Location center = gate.center();
        Location ground = gate.feet.clone().add(0, 0.1, 0);
        Vfx.dot(center, Particle.FLASH, PALETTE.core(), false);
        Vfx.cloud(ctx(), center, Particle.REVERSE_PORTAL, 30, 0.3, 0.5, null, false);
        Vfx.shockwave(ctx(), owner, ground, 0.4, Math.max(0.8, radius), 6, Particle.DUST_COLOR_TRANSITION,
                PALETTE.glow(1.2f), false);
        Fx.sound(center, Sound.ENTITY_SHULKER_TELEPORT, 1.0f, 0.8f + Fx.jitter(0.05f));
        if (radius <= 0) {
            return;
        }
        for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius)) {
            Combat.hit(ctx(), owner, enemy, damage);
            if (!Combat.isTargetable(ctx(), owner, enemy)) {
                continue;
            }
            Vector away = Motion.horizontal(enemy.getLocation().toVector().subtract(gate.feet.toVector()));
            if (away.lengthSquared() < 1.0E-6) {
                away = Motion.horizontalFacing(gate.feet);
            }
            Motion.push(enemy, away.multiply(BURST_PUSH).setY(BURST_LIFT), MAX_SPEED);
            Vfx.burst(ctx(), Fx.body(enemy), PALETTE, 0.5, Particle.REVERSE_PORTAL, false);
        }
    }

    /**
     * The open link after a gate travel: allies who step into the entrance come out at the exit, and light keeps
     * flowing from one gate to the other.
     */
    private final class Link implements LongPredicate {

        private final Player owner;
        private final Gate entrance;
        private final Gate exitGate;
        private final long open;
        private final Set<UUID> passed = new HashSet<>();

        Link(Player owner, Gate entrance, Gate exitGate, long open) {
            this.owner = owner;
            this.entrance = entrance;
            this.exitGate = exitGate;
            this.open = open;
        }

        @Override
        public boolean test(long tick) {
            if (tick >= open - 1 || !entrance.isOpen()) {
                return false;
            }
            Location mouth = entrance.center();
            for (Player ally : ctx().alliesNear(owner, mouth, ENTRANCE_REACH)) {
                if (passed.add(ally.getUniqueId())) {
                    carry(ally);
                }
            }
            if (tick % 3 == 0 && mouth.getWorld().equals(exitGate.feet.getWorld())) {
                Location target = exitGate.center();
                int duration = Math.clamp(Math.round(mouth.distance(target) * 0.6), 6, 30);
                Vfx.trail(Vfx.scatter(mouth, 0.5), Vfx.scatter(target, 0.5), tick % 2 == 0 ? VIOLET : PALETTE.body(),
                        duration, false);
            }
            return true;
        }

        private void carry(Player ally) {
            Location spot = allySpot(ally);
            if (spot == null) {
                return;
            }
            Location from = ally.getLocation();
            spot.setYaw(from.getYaw());
            spot.setPitch(from.getPitch());
            if (!ally.teleport(spot, TeleportCause.PLUGIN)) {
                return;
            }
            ally.setFallDistance(0);
            implode(from.clone().add(0, ally.getHeight() * 0.5, 0), 1.0, 12, false);
            Vfx.burst(ctx(), spot.clone().add(0, ally.getHeight() * 0.5, 0), PALETTE, 0.6, Particle.REVERSE_PORTAL,
                    false);
            Fx.sound(spot, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.1f + Fx.jitter(0.1f));
            exitGate.pulse();
            ally.sendActionBar(Text.mm("<" + COLOR_TAG + ">✦ 차원문을 통과했습니다</" + COLOR_TAG + ">"));
        }

        /** Next to the exit (beside the owner who just came out of it), or right in it as a last resort. */
        private @Nullable Location allySpot(Player ally) {
            Body body = Body.standing(ally);
            Vector right = exitGate.right();
            Vector normal = exitGate.normal();
            Vector[] offsets = {right.clone().multiply(ALLY_OFFSET), right.clone().multiply(-ALLY_OFFSET),
                    normal.clone().multiply(ALLY_OFFSET), normal.clone().multiply(-ALLY_OFFSET), new Vector()};
            for (Vector offset : offsets) {
                Location feet = exitGate.feet.clone().add(offset);
                Spots.snapToGround(feet.getWorld(), feet, 1.1);
                if (Spots.isSafe(ctx(), feet, body)) {
                    return feet;
                }
            }
            return null;
        }
    }

    /**
     * A dimension gate: an upright octagon of teal glass bars around a void of black and violet glass, all ten
     * displays sharing the gate's middle as location and posed in the gate's own frame (x along the gate, y up, z its
     * normal), turning slowly in its plane.
     */
    private final class Gate implements LongPredicate {

        private final Location feet;
        private final Quaternionf frame;
        private final boolean outlined;
        private final List<BlockDisplay> bars = new ArrayList<>(BARS);
        private final List<BlockDisplay> cores = new ArrayList<>(2);
        private long life;
        private long age;
        private float roll;
        private boolean closed;

        Gate(Location feet, float yawDegrees, long life, boolean outlined) {
            this.feet = feet.clone();
            this.frame = new Quaternionf().rotateY((float) -Math.toRadians(yawDegrees));
            this.life = life;
            this.outlined = outlined;
        }

        Location center() {
            Location center = feet.clone().add(0, GATE_HEIGHT, 0);
            center.setYaw(0);
            center.setPitch(0);
            return center;
        }

        Vector right() {
            Vector3f x = new Quaternionf(frame).transform(new Vector3f(1, 0, 0));
            return new Vector(x.x(), x.y(), x.z());
        }

        Vector normal() {
            Vector3f z = new Quaternionf(frame).transform(new Vector3f(0, 0, 1));
            return new Vector(z.x(), z.y(), z.z());
        }

        boolean isOpen() {
            return !closed;
        }

        void open() {
            Player owner = player();
            Location at = center();
            long lifetime = life + OPEN_TICKS + CLOSE_TICKS + 20;
            for (int k = 0; k < BARS; k++) {
                BlockDisplay bar = Displays.block(ctx(), owner, at, FRAME_GLASS, barPose(k, roll, 1.9f, 0.05f),
                        lifetime);
                bars.add(bar);
            }
            for (int i = 0; i < 2; i++) {
                BlockDisplay core = Displays.block(ctx(), owner, at, i == 0 ? VOID_GLASS : RIFT_GLASS,
                        corePose(i, roll, 0.02f), lifetime);
                cores.add(core);
            }
            if (outlined) {
                Displays.outline(cores.getFirst(), PALETTE.body());
            }
            ctx().runLater(owner, 1, () -> pose(1f, OPEN_TICKS));
            Loop.repeat(ctx(), owner, 0, 1, lifetime, this);
        }

        @Override
        public boolean test(long tick) {
            age = tick;
            if (closed) {
                return false;
            }
            if (tick >= life) {
                if (exit == this) {
                    expired();
                }
                close();
                return false;
            }
            if (tick > OPEN_TICKS && tick % SPIN_PERIOD == 0) {
                roll = (roll + SPIN_STEP) % 360f;
                pose(1f, (int) SPIN_PERIOD);
            }
            ambient(tick);
            return true;
        }

        /** An unused exit ran out of time: the owner is told (the gate then closes as usual). */
        private void expired() {
            Player owner = player();
            if (owner.isOnline()) {
                owner.sendActionBar(Text.mm("<gray>출구 차원문이 닫혔습니다</gray>"));
                owner.playSound(owner, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, SoundCategory.PLAYERS, 0.6f, 0.7f);
            }
        }

        /** Ends the gate {@code ticks} from now (sooner if it would close earlier anyway). */
        void closeIn(long ticks) {
            life = Math.min(life, age + Math.max(1, ticks));
        }

        /** A burst of light through the void: the core swells for a moment. */
        void pulse() {
            if (closed) {
                return;
            }
            for (int i = 0; i < cores.size(); i++) {
                Displays.animate(cores.get(i), corePose(i, roll, 1.35f), 2);
            }
            ctx().runLater(player(), 3, () -> {
                if (!closed) {
                    for (int i = 0; i < cores.size(); i++) {
                        Displays.animate(cores.get(i), corePose(i, roll, 1f), 4);
                    }
                }
            });
            Location at = center();
            Vfx.dot(at, Particle.FLASH, PALETTE.core(), false);
            Vfx.cloud(ctx(), at, Particle.END_ROD, 6, 0.4, 0.05, null, false);
        }

        void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (exit == this) {
                exit = null;
            }
            Player owner = player();
            for (int k = 0; k < bars.size(); k++) {
                BlockDisplay bar = bars.get(k);
                if (bar.isValid()) {
                    Displays.animate(bar, barPose(k, roll + 40f, 0.05f, 0.3f), CLOSE_TICKS);
                }
            }
            for (int i = 0; i < cores.size(); i++) {
                BlockDisplay core = cores.get(i);
                if (core.isValid()) {
                    Displays.animate(core, corePose(i, roll + 40f, 0.01f), CLOSE_TICKS);
                }
            }
            List<BlockDisplay> all = new ArrayList<>(bars);
            all.addAll(cores);
            ctx().runLater(owner, CLOSE_TICKS, () -> all.forEach(display -> {
                if (display.isValid()) {
                    display.remove();
                }
            }));
            Location at = center();
            implode(at, 1.1, 14, false);
            Vfx.cloud(ctx(), at, Particle.REVERSE_PORTAL, 12, 0.3, 0.02, null, false);
            Fx.sound(at, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.6f, 0.9f + Fx.jitter(0.05f));
            Fx.sound(at, Sound.ENTITY_ENDERMAN_TELEPORT, 0.4f, 0.6f + Fx.jitter(0.05f));
        }

        private void pose(float scale, int ticks) {
            for (int k = 0; k < bars.size(); k++) {
                Displays.animate(bars.get(k), barPose(k, roll, scale, 1f), ticks);
            }
            for (int i = 0; i < cores.size(); i++) {
                Displays.animate(cores.get(i), corePose(i, roll, scale), ticks);
            }
        }

        /** Bar {@code k} of the octagon, at {@code spread} times the radius and {@code thickness} times its width. */
        private Transformation barPose(int k, float rollDegrees, float spread, float thickness) {
            double mid = Math.toRadians(rollDegrees) + (k + 0.5) * TAU / BARS;
            float distance = (float) (GATE_RADIUS * Math.cos(Math.PI / BARS)) * spread;
            float length = (float) (2 * GATE_RADIUS * Math.sin(Math.PI / BARS)) * Math.min(1f, spread)
                    + BAR_THICKNESS * thickness;
            float width = BAR_THICKNESS * thickness;
            Vector3f center = new Vector3f((float) Math.cos(mid) * distance, (float) Math.sin(mid) * distance, 0);
            Quaternionf rotation = new Quaternionf(frame).rotateZ((float) (mid + Math.PI / 2));
            return box(frame, center, rotation, new Vector3f(Math.max(0.01f, length), width, width));
        }

        /** The void: a black square and a violet diamond over it, together an eight-pointed tear. */
        private Transformation corePose(int index, float rollDegrees, float scale) {
            Quaternionf rotation = new Quaternionf(frame)
                    .rotateZ((float) Math.toRadians(rollDegrees * (index == 0 ? -1.5f : 1f) + index * 45));
            float side = CORE_SIDE * scale;
            return box(frame, new Vector3f(0, 0, index == 0 ? -0.015f : 0.015f), rotation,
                    new Vector3f(side, side, CORE_DEPTH));
        }

        /** Portal motes falling into the void, a teal spiral turning inside and sparks on the rim. */
        private void ambient(long tick) {
            Location at = center();
            World world = at.getWorld();
            Vector u = right();
            Vector v = new Vector(0, 1, 0);
            int arms = 2;
            int perArm = Vfx.n(ctx(), 3);
            for (int arm = 0; arm < arms; arm++) {
                for (int k = 0; k < perArm; k++) {
                    double r = 0.25 + 0.6 * (k + 1) / perArm;
                    double angle = -tick * 0.25 + arm * Math.PI + k * 0.7;
                    Location point = at.clone().add(u.clone().multiply(Math.cos(angle) * r))
                            .add(v.clone().multiply(Math.sin(angle) * r));
                    world.spawnParticle(Particle.DUST_COLOR_TRANSITION, point, 1, 0, 0, 0, 0, SWIRL);
                }
            }
            if (tick % 2 == 0) {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                for (int i = 0; i < Vfx.n(ctx(), 2); i++) {
                    double angle = random.nextDouble(TAU);
                    Vector offset = u.clone().multiply(Math.cos(angle) * 1.4).add(v.clone().multiply(Math.sin(angle) * 1.4));
                    world.spawnParticle(Particle.PORTAL, at, 0, offset.getX(), offset.getY(), offset.getZ(), 1.0);
                }
            }
            if (tick % 6 == 0) {
                double angle = ThreadLocalRandom.current().nextDouble(TAU);
                Vfx.dot(at.clone().add(u.clone().multiply(Math.cos(angle) * GATE_RADIUS))
                        .add(v.clone().multiply(Math.sin(angle) * GATE_RADIUS)), Particle.END_ROD, null, false);
            }
            if (tick % 10 == 0) {
                Vfx.ring(ctx(), feet.clone().add(0, 0.08, 0), 0.75, 12, tick * 0.05, Particle.DUST, VIOLET_DUST, false);
            }
            if (tick % 40 == 20) {
                Fx.sound(at, Sound.BLOCK_PORTAL_AMBIENT, 0.25f, 1.4f + Fx.jitter(0.1f));
            }
        }
    }

    // ------------------------------------------------------------------ ultimate: dimension collapse

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        double radius = params().num("ult_radius");
        Location center = owner.getLocation();
        List<LivingEntity> targets = ctx().enemiesNear(owner, center, radius);
        if (targets.isEmpty()) {
            return ActivationResult.fail("반경 " + AbilityInfo.formatNumber(radius) + "블록 안에 적이 없습니다");
        }
        new Collapse(owner, center, radius, List.copyOf(targets)).begin();
        return ActivationResult.success();
    }

    /**
     * 차원 붕괴: every enemy in range is marked by a spinning violet sigil and a teal helix, then the marked enemies
     * trade places (a random cycle, so nobody stays where it was) — a lone enemy is thrown to a random safe spot.
     */
    private final class Collapse {

        private final Player owner;
        private final Location center;
        private final double radius;
        private final List<LivingEntity> marked;
        private final Map<UUID, BlockDisplay> sigils = new HashMap<>();
        private final long delay;
        private float spin;

        Collapse(Player owner, Location center, double radius, List<LivingEntity> marked) {
            this.owner = owner;
            this.center = center;
            this.radius = radius;
            this.marked = marked;
            this.delay = Math.max(1, params().ticks("ult_delay"));
        }

        void begin() {
            Location chest = owner.getLocation().add(0, owner.getHeight() * 0.55, 0);
            Vfx.dot(chest, Particle.FLASH, PALETTE.body(), true);
            implode(chest, 1.6, 24, true);
            Location ground = center.clone().add(0, 0.1, 0);
            Vfx.shockwave(ctx(), owner, ground, 0.5, radius, 8, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.4f),
                    true);
            Vfx.shockwave(ctx(), owner, ground.clone().add(0, 0.3, 0), 0.3, radius * 0.7, 8, Particle.DUST,
                    VIOLET_DUST, true);
            Fx.sound(center, Sound.BLOCK_END_PORTAL_SPAWN, 0.6f, 1.5f);
            Fx.sound(center, Sound.BLOCK_BEACON_POWER_SELECT, 1.0f, 0.6f);
            Fx.sound(center, Sound.ENTITY_ILLUSIONER_PREPARE_MIRROR, 1.2f, 1.0f);
            for (LivingEntity enemy : marked) {
                if (sigils.size() < MAX_SIGILS) {
                    BlockDisplay sigil = Displays.block(ctx(), owner, sigilSpot(enemy), RIFT_GLASS,
                            sigilPose(0, 0.1f), delay + 24);
                    sigil.setTeleportDuration(2);
                    Displays.outline(sigil, VIOLET);
                    Displays.animateNextTick(ctx(), owner, sigil, sigilPose(SIGIL_SPIN, 1f), 3);
                    sigils.put(enemy.getUniqueId(), sigil);
                }
                Fx.sound(enemy.getLocation(), Sound.BLOCK_PORTAL_TRIGGER, 0.35f, 1.8f + Fx.jitter(0.1f));
            }
            Loop.repeat(ctx(), owner, 1, 1, delay, tick -> {
                if (tick >= delay - 1) {
                    shuffle();
                    return false;
                }
                mark(tick);
                return true;
            });
        }

        /** The telegraph: sigils spin under the marked enemies while a helix of teal light winds up around them. */
        private void mark(long tick) {
            spin += SIGIL_SPIN;
            double progress = (double) (tick + 1) / delay;
            for (LivingEntity enemy : marked) {
                if (!Combat.isTargetable(ctx(), owner, enemy)) {
                    continue;
                }
                Location feet = enemy.getLocation();
                Vfx.helix(ctx(), feet, 0.75, enemy.getHeight() * (0.4 + 0.7 * progress), 1.2, 2, 6, tick * 0.45,
                        Particle.DUST_COLOR_TRANSITION, SWIRL, true);
                Vfx.ring(ctx(), feet.clone().add(0, 0.1, 0), 1.3 - 0.8 * progress, 12, -tick * 0.4,
                        Particle.DUST_COLOR_TRANSITION, GLOW, true);
                if (tick % 2 == 0) {
                    Location body = Fx.body(enemy);
                    body.getWorld().spawnParticle(Particle.PORTAL, body, Vfx.n(ctx(), 6), 0.3, 0.5, 0.3, 0.6);
                }
                BlockDisplay sigil = sigils.get(enemy.getUniqueId());
                if (sigil != null && sigil.isValid()) {
                    sigil.teleport(sigilSpot(enemy));
                    Displays.animate(sigil, sigilPose(spin, 1f), 1);
                }
            }
        }

        private void shuffle() {
            List<LivingEntity> caught = new ArrayList<>();
            for (LivingEntity enemy : marked) {
                if (Combat.isTargetable(ctx(), owner, enemy) && enemy.getWorld().equals(center.getWorld())
                        && enemy.getLocation().distance(center) <= radius * 1.5 + 2) {
                    caught.add(enemy);
                }
            }
            Fx.sound(center, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.4f, 0.9f);
            Fx.sound(center, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 0.6f);
            if (caught.isEmpty()) {
                fadeSigils();
                return;
            }
            Map<LivingEntity, Location> destinations = plan(caught);
            Map<LivingEntity, Location> origins = new HashMap<>();
            for (LivingEntity enemy : caught) {
                origins.put(enemy, enemy.getLocation());
            }
            // Move everyone first, then hurt: a death mid-way never leaves half the swap undone.
            for (LivingEntity enemy : caught) {
                Location destination = destinations.get(enemy);
                if (destination == null) {
                    continue;
                }
                Location look = enemy.getLocation();
                destination.setYaw(look.getYaw());
                destination.setPitch(look.getPitch());
                if (enemy.teleport(destination, TeleportCause.PLUGIN)) {
                    enemy.setVelocity(new Vector());
                    enemy.setFallDistance(0);
                }
            }
            double damage = Math.max(0, params().num("ult_damage"));
            int nausea = (int) Math.min(Integer.MAX_VALUE, params().ticks("ult_nausea"));
            int slow = (int) Math.min(Integer.MAX_VALUE, params().ticks("ult_slow"));
            int moved = 0;
            for (LivingEntity enemy : caught) {
                Location from = origins.get(enemy);
                swapFx(enemy, from);
                if (from.distanceSquared(enemy.getLocation()) > 1) {
                    moved++;
                }
                Combat.hit(ctx(), owner, enemy, damage);
                if (!Combat.isTargetable(ctx(), owner, enemy)) {
                    continue;
                }
                if (nausea > 0) {
                    enemy.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, nausea, 0, false, true, true));
                }
                if (slow > 0) {
                    enemy.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slow, SLOW_AMPLIFIER, false,
                            true, true));
                }
            }
            fadeSigils();
            if (owner.isOnline()) {
                owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>차원 붕괴!</b></" + COLOR_TAG + "> <white>"
                        + moved + "명 뒤섞음</white>"));
            }
        }

        /** Destinations: a random cycle over the enemies' own spots, or a random safe spot for a lone enemy. */
        private Map<LivingEntity, Location> plan(List<LivingEntity> caught) {
            Map<LivingEntity, Location> destinations = new HashMap<>();
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int count = caught.size();
            if (count == 1) {
                LivingEntity lone = caught.getFirst();
                Location spot = randomSpot(lone);
                if (spot != null) {
                    destinations.put(lone, spot);
                }
                return destinations;
            }
            // Sattolo's shuffle: a single random cycle, so every enemy moves to another enemy's spot.
            int[] order = new int[count];
            for (int i = 0; i < count; i++) {
                order[i] = i;
            }
            for (int i = count - 1; i > 0; i--) {
                int j = random.nextInt(i);
                int swap = order[i];
                order[i] = order[j];
                order[j] = swap;
            }
            for (int i = 0; i < count; i++) {
                LivingEntity enemy = caught.get(i);
                Location spot = groundedSwapSpot(caught.get(order[i]).getLocation());
                if (spot == null || !Spots.isSafe(ctx(), spot, Body.standing(enemy))) {
                    spot = randomSpot(enemy);
                }
                if (spot != null) {
                    destinations.put(enemy, spot);
                }
            }
            return destinations;
        }

        /**
         * Another enemy's spot as a destination, lowered onto the ground below it: an enemy caught mid-jump, flying or
         * leaping must not hand its height over (someone swapped into the air could fall to death, uncredited). Null
         * if there is no ground close enough below.
         */
        private @Nullable Location groundedSwapSpot(Location spot) {
            World world = spot.getWorld();
            Spots.snapToGround(world, spot, RANDOM_SPOT_DROP);
            BoundingBox below = new BoundingBox(spot.getX() - 0.25, spot.getY() - 0.15, spot.getZ() - 0.25,
                    spot.getX() + 0.25, spot.getY() - 0.01, spot.getZ() + 0.25);
            return world.hasCollisionsIn(below) || spot.getBlock().isLiquid() ? spot : null;
        }

        /** A random safe spot within the collapse, at least {@link #MIN_DISPLACEMENT} blocks from where it stands. */
        private @Nullable Location randomSpot(LivingEntity enemy) {
            Location from = enemy.getLocation();
            Body body = Body.standing(enemy);
            World world = center.getWorld();
            double minimum = Math.min(MIN_DISPLACEMENT, radius * 0.5);
            for (int i = 0; i < RANDOM_SPOT_TRIES; i++) {
                Location spot = Vfx.scatter(center, radius);
                spot.setY(center.getY() + RANDOM_SPOT_LIFT);
                if (spot.getBlock().getType().isSolid()) {
                    continue;
                }
                Spots.snapToGround(world, spot, RANDOM_SPOT_DROP);
                if (spot.distance(from) >= minimum && Spots.isSafe(ctx(), spot, body)
                        && Spots.clearPath(spot.clone().add(0, body.eyeHeight(), 0), center.clone().add(0, 1, 0))) {
                    return spot;
                }
            }
            return null;
        }

        /** Light streaking from the old spot to the new one, a flash on arrival and a fading ring where it was. */
        private void swapFx(LivingEntity enemy, Location from) {
            Location to = enemy.getLocation();
            double half = enemy.getHeight() * 0.5;
            Location fromBody = from.clone().add(0, half, 0);
            Location toBody = to.clone().add(0, half, 0);
            if (fromBody.getWorld().equals(toBody.getWorld()) && fromBody.distanceSquared(toBody) > 0.25) {
                int duration = Math.clamp(Math.round(fromBody.distance(toBody) * 0.8), 4, 14);
                ThreadLocalRandom random = ThreadLocalRandom.current();
                int motes = Vfx.n(ctx(), 8);
                for (int i = 0; i < motes; i++) {
                    Vector jitter = new Vector(random.nextDouble(-0.25, 0.25), random.nextDouble(-0.6, 0.6),
                            random.nextDouble(-0.25, 0.25));
                    Vfx.trail(fromBody.clone().add(jitter), toBody.clone().add(jitter), i % 2 == 0 ? VIOLET
                            : PALETTE.body(), duration, true);
                }
                Vfx.ring(ctx(), from.clone().add(0, 0.1, 0), 0.8, 14, 0, Particle.DUST_COLOR_TRANSITION, FADE, true);
            }
            Vfx.dot(toBody, Particle.FLASH, PALETTE.core(), true);
            Vfx.cloud(ctx(), toBody, Particle.REVERSE_PORTAL, 16, 0.3, 0.06, null, true);
            verticalRing(toBody, toBody.toVector().subtract(center.toVector()), 0.9, 16, 0,
                    Particle.DUST_COLOR_TRANSITION, GLOW, true);
            Fx.sound(to, Sound.ENTITY_SHULKER_TELEPORT, 0.9f, 0.8f + Fx.jitter(0.1f));
            BlockDisplay sigil = sigils.get(enemy.getUniqueId());
            if (sigil != null && sigil.isValid()) {
                // The sigil glides after its victim to the new spot.
                sigil.setTeleportDuration(3);
                sigil.teleport(sigilSpot(enemy));
            }
            Location glow = toBody.clone();
            Loop.repeat(ctx(), owner, 2, 2, AFTERGLOW_TICKS / 2, run -> {
                Location mote = Vfx.scatter(glow, 0.7).add(0, ThreadLocalRandom.current().nextDouble(-0.8, 0.8), 0);
                Vfx.dot(mote, Particle.DUST, VIOLET_DUST, false);
                if (run % 2 == 0) {
                    Vfx.dot(mote, Particle.REVERSE_PORTAL, null, false);
                }
                return true;
            });
        }

        private void fadeSigils() {
            for (BlockDisplay sigil : sigils.values()) {
                if (sigil.isValid()) {
                    ctx().runLater(owner, 4, () -> {
                        if (sigil.isValid()) {
                            Displays.shrinkAway(ctx(), owner, sigil, 6);
                        }
                    });
                }
            }
        }

        private Location sigilSpot(LivingEntity enemy) {
            Location at = enemy.getLocation().add(0, 0.06, 0);
            at.setYaw(0);
            at.setPitch(0);
            return at;
        }
    }

    /** A flat square sigil lying on the ground, turned {@code spinDegrees} around the vertical, scaled. */
    private static Transformation sigilPose(float spinDegrees, float scale) {
        Quaternionf rotation = new Quaternionf().rotateY((float) Math.toRadians(spinDegrees))
                .rotateX((float) Math.toRadians(-90)).rotateZ((float) Math.toRadians(45));
        float side = SIGIL_SIDE * scale;
        return box(new Quaternionf(), new Vector3f(), rotation, new Vector3f(side, side, CORE_DEPTH));
    }

    // ------------------------------------------------------------------ shared visuals

    /** Motes drawn in from a sphere around {@code center} into its middle. */
    private void implode(Location center, double radius, int points, boolean force) {
        int count = Vfx.n(ctx(), points);
        double golden = Math.PI * (3 - Math.sqrt(5));
        double spin = ThreadLocalRandom.current().nextDouble(TAU);
        for (int i = 0; i < count; i++) {
            double y = 1 - (i + 0.5) * 2.0 / count;
            double r = Math.sqrt(Math.max(0, 1 - y * y));
            double angle = spin + i * golden;
            Location from = center.clone().add(Math.cos(angle) * r * radius, y * radius, Math.sin(angle) * r * radius);
            Vfx.trail(from, center, i % 2 == 0 ? PALETTE.body() : VIOLET, 6, force);
        }
    }

    /** A ring standing upright around {@code center}, facing {@code facing} (its horizontal part). */
    private <T> void verticalRing(Location center, Vector facing, double radius, int points, double phase,
                                  Particle particle, @Nullable T data, boolean force) {
        Vector forward = facing.clone().setY(0);
        if (forward.lengthSquared() < 1.0E-6) {
            forward = new Vector(0, 0, 1);
        }
        Vector side = forward.normalize().crossProduct(new Vector(0, 1, 0));
        int count = Vfx.n(ctx(), points);
        for (int i = 0; i < count; i++) {
            double angle = phase + i * TAU / count;
            Location at = center.clone().add(side.clone().multiply(Math.cos(angle) * radius))
                    .add(0, Math.sin(angle) * radius, 0);
            Vfx.dot(at, particle, data, force);
        }
    }

    /**
     * A box of {@code size} whose middle sits at {@code localCenter} in {@code frame}, turned by {@code rotation}
     * (which already includes the frame).
     */
    private static Transformation box(Quaternionf frame, Vector3f localCenter, Quaternionf rotation, Vector3f size) {
        Vector3f center = new Quaternionf(frame).transform(new Vector3f(localCenter));
        Vector3f translation = center.sub(new Quaternionf(rotation).transform(new Vector3f(size).mul(0.5f)));
        return new Transformation(translation, new Quaternionf(rotation), new Vector3f(size), new Quaternionf());
    }
}
