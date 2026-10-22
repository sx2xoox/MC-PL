package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

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
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.DyedItemColor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.util.TriState;
import org.bukkit.Color;
import org.bukkit.Difficulty;
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
import org.bukkit.block.data.BlockData;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 좀비 — patient zero. Its melee hits infect (rot damage and a stiff gait, passed on once to enemies standing close),
 * the active lunges at an enemy and bites it (feeding on enemies already infected), and the ultimate is the outbreak:
 * a groan infects every enemy around, then zombies claw out of the ground at the feet of every infected enemy and hunt
 * their hosts, their bites infecting again.
 *
 * <p>The ultimate is built on the infection (its hosts, the extra zombies for enemies that were already sick and the
 * zombies' infecting bites, which are hooks), so the gambler never borrows it. The zombies are the owner's summons
 * (credited, never touching the owner's allies); on Peaceful, where mobs cannot hurt players, they bite through the
 * ability instead. They never burn in daylight, never turn into drowned and leave nothing behind.
 *
 * <p>Look: rotten green and blood red. Infected enemies wear a sickly green biohazard mark and drip green rot, every
 * contagion jumps as a stream of green motes, the bite clamps a pair of bone-white jaws shut on the victim in a spray of
 * blood, and the outbreak cracks the ground open around the hosts, where rotting green arms reach out of the dirt before
 * the dead climb out.
 */
public final class ZombieAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("zombie", "좀비", Material.ROTTEN_FLESH)
            .color(TextColor.color(0x6FA84A))
            .difficulty(1)
            .passive("감염",
                    "근접 공격으로 맞힌 적을 {infect_duration}초간 감염시킵니다. 감염된 적은 1초마다 {infect_damage} 피해를 "
                            + "입고 이동 속도가 {infect_slow}% 느려지며, {spread_interval}초마다 {spread_radius}블록 안의 "
                            + "감염되지 않은 다른 적에게 감염을 옮깁니다(옮은 감염은 {spread_duration}초 유지되며 다시 옮지 "
                            + "않습니다).")
            .active("물어뜯기",
                    "조준한 적(없으면 앞쪽, {bite_range}블록 이내)에게 덤벼들어 처음 닿은 적을 물어뜯습니다. {bite_damage} "
                            + "피해를 주고 감염시키며, 이미 감염된 적이면 살점을 뜯어 {feed_damage} 피해를 더 주고 체력을 "
                            + "{feed_heal} 회복합니다. 덤벼드는 동안 낙하 피해를 받지 않고, 아무도 물지 못하면 쿨타임이 "
                            + "절반만 돕니다.",
                    8)
            .ultimate("좀비 아포칼립스",
                    "땅을 울리는 신음과 함께 반경 {ult_radius}블록의 적을 모두 감염시킵니다. {rise_delay}초 뒤 "
                            + "{horde_range}블록 안의 감염된 적마다 발치에서 좀비가 {zombies_per}마리씩(원래 감염돼 있던 적에게서는 "
                            + "{zombies_infected}마리, 모두 합쳐 최대 {max_zombies}마리) 기어 나와 {zombie_duration}초간 그 적을 "
                            + "쫓습니다. 좀비(체력 {zombie_health}, 공격력 {zombie_damage})에게 물린 적은 다시 감염됩니다.")
            .ultimateNotStandalone()
            .param("infect_duration", 5)
            .param("infect_damage", 0.5)
            .param("infect_slow", 10)
            .param("spread_interval", 2)
            .param("spread_radius", 3)
            .param("spread_duration", 3)
            .param("bite_range", 6)
            .param("bite_damage", 4)
            .param("feed_damage", 2)
            .param("feed_heal", 3)
            .param("ult_radius", 10)
            .param("rise_delay", 0.75)
            .param("horde_range", 24)
            .param("zombies_per", 1)
            .param("zombies_infected", 2)
            .param("max_zombies", 6)
            .param("zombie_duration", 10)
            .param("zombie_health", 12)
            .param("zombie_damage", 3)
            .build();

    private static final String ROT = "#6FA84A";
    private static final String SICK = "#C8E6A0";
    private static final String BLOOD_TAG = "#B3141B";
    private static final double TAU = Math.PI * 2;

    // ------------------------------------------------------------------ look: rotten green and blood
    /** Sickly pale green, rotten green, the dark of grave soil. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xC8E6A0, 0x6FA84A, 0x23310F);
    private static final Color BLOOD = Color.fromRGB(0x8A0F12);
    /** The dried-blood red of the risen zombies' caps. */
    private static final Color CAP_COLOR = Color.fromRGB(0x5A1A12);
    private static final Particle.DustTransition GLOW = PALETTE.glow(1.0f);
    private static final Particle.DustTransition GLOW_SMALL = PALETTE.glow(0.6f);
    private static final Particle.DustTransition FADE = PALETTE.fade(1.1f);
    private static final Particle.DustOptions ROT_DUST = PALETTE.dust(0.8f);
    private static final Particle.DustOptions ROT_SMALL = PALETTE.dust(0.5f);
    private static final Particle.DustOptions BLOOD_DUST = new Particle.DustOptions(BLOOD, 1.0f);
    private static final Particle.DustOptions GRAVE_DUST = new Particle.DustOptions(Color.fromRGB(0x3B2A1A), 1.2f);

    /** Block types of the visuals; block data is created on use (it needs a running server). */
    private static final Material ARM_BLOCK = Material.GREEN_TERRACOTTA;
    private static final Material HAND_BLOCK = Material.LIME_TERRACOTTA;
    private static final Material CRACK_BLOCK = Material.COARSE_DIRT;
    private static final Material BLOOD_BLOCK = Material.REDSTONE_BLOCK;
    private static final Material DIRT_BLOCK = Material.ROOTED_DIRT;

    /** Display entities one zombie keeps alive at most at once (extra visuals fall back to particles). */
    private static final int MAX_DISPLAYS = 24;
    /** Caps every velocity this ability applies. */
    private static final double MAX_VELOCITY = 2.5;
    private static final double REFUND = 0.5;

    // passive
    private static final long INFECTION_PERIOD = 2;
    private static final long DOT_PERIOD = 20;
    private static final long DRIP_PERIOD = 6;
    private static final float MARK_SCALE = 0.7f;
    private static final double MARK_ABOVE = 0.5;

    // active: the lunge
    private static final double AIM_SLACK = 1.5;
    private static final double STOP_SHORT = 0.8;
    private static final double LUNGE_HEIGHT = 0.6;
    private static final double AIR_DRAG = 0.91;
    private static final double MAX_LUNGE_SPEED = 1.4;
    private static final double CONTACT_GAP = 0.5;
    private static final double LAND_REACH = 1.4;
    private static final double BORDER_MARGIN = 1.0;
    private static final long MAX_LUNGE_TICKS = 30;
    private static final long STUCK_TICKS = 6;
    private static final long MAX_LAG_TICKS = 8;
    private static final long LANDING_GUARD_TICKS = 60;
    private static final long JAW_TICKS = 3;
    private static final long JAW_LIFE = 12;

    // ultimate: the outbreak
    private static final double ZOMBIE_WIDTH = 0.6;
    private static final double ZOMBIE_HEIGHT = 1.95;
    private static final double ZOMBIE_SPEED = 0.3;
    private static final long CONTROL_PERIOD = 5;
    private static final double CHASE_RANGE = 16;
    private static final double STRIKE_REACH = 0.8;
    private static final long STRIKE_TICKS = 20;
    private static final double[] SPAWN_ANGLES = {0, 60, -60, 120, -120, 180, 30, -30, 90, -90, 150, -150};
    private static final double[] SPAWN_DISTANCES = {1.3, 1.9};
    private static final int ARMS_PER_HOST = 2;
    private static final TextColor NAME_COLOR = TextColor.color(0x6FA84A);

    private final Map<UUID, Infection> infections = new HashMap<>();
    private final List<Display> live = new ArrayList<>();
    private final List<Walker> horde = new ArrayList<>();
    private final Set<UUID> hordeIds = new HashSet<>();
    private final LandingGuard landing = new LandingGuard();
    private boolean lunging;
    /** True while a zombie bites through the ability (Peaceful), so the hit counts as the zombie's bite. */
    private boolean zombieBite;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        infections.clear();
        horde.clear();
        hordeIds.clear();
        lunging = false;
    }

    // ================================================================== passive: infection

    /** One infected enemy: when it is cured, its next rot tick and contagion, and the mark over its head. */
    private static final class Infection {
        final LivingEntity victim;
        long expires;
        long nextRot;
        long nextSpread;
        boolean contagious;
        @Nullable NamespacedKey slow;
        @Nullable TextDisplay mark;
        boolean markHidden;

        Infection(LivingEntity victim) {
            this.victim = victim;
        }
    }

    /** Own melee and the zombies' bites infect. */
    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        boolean ownMelee = !ctx().isAbilityDamage() && player().equals(event.getDamager())
                && (event.getCause() == DamageCause.ENTITY_ATTACK || event.getCause() == DamageCause.ENTITY_SWEEP_ATTACK);
        boolean zombieHit = hordeIds.contains(event.getDamager().getUniqueId()) || zombieBite;
        if (!ownMelee && !zombieHit) {
            return;
        }
        infect(victim, params().ticks("infect_duration"), true);
        if (zombieHit) {
            Location body = Fx.body(victim);
            body.getWorld().spawnParticle(Particle.BLOCK, body, Vfx.n(ctx(), 8), 0.2, 0.3, 0.2, 0.1,
                    BLOOD_BLOCK.createBlockData());
            Fx.sound(body, Sound.ENTITY_ZOMBIE_INFECT, 0.8f, 1.1f + Fx.jitter(0.1f));
        }
    }

    private boolean isInfected(LivingEntity victim) {
        Infection infection = infections.get(victim.getUniqueId());
        return infection != null && ctx().currentTick() < infection.expires;
    }

    /** Infects {@code victim} for {@code ticks} (at least as long as it already is); contagious ones spread. */
    private void infect(LivingEntity victim, long ticks, boolean contagious) {
        if (!Combat.isTargetable(ctx(), player(), victim) || ticks <= 0) {
            return;
        }
        long now = ctx().currentTick();
        Infection infection = infections.get(victim.getUniqueId());
        boolean fresh = infection == null || now >= infection.expires;
        if (infection == null) {
            infection = new Infection(victim);
            infections.put(victim.getUniqueId(), infection);
        }
        if (fresh) {
            infection.nextRot = now + DOT_PERIOD;
            infection.nextSpread = now + Math.max(1, params().ticks("spread_interval"));
            infection.contagious = contagious;
        } else {
            infection.contagious |= contagious;
        }
        infection.expires = Math.max(infection.expires, now + ticks);
        if (infection.slow == null) {
            double slow = Math.clamp(params().num("infect_slow") / 100.0, 0, 1);
            if (slow > 0) {
                infection.slow = ctx().addModifier(player(), victim, Attribute.MOVEMENT_SPEED, -slow,
                        Operation.MULTIPLY_SCALAR_1, 0);
            }
        }
        showMark(infection);
        if (fresh && !isHiddenFromOwner(victim)) {
            Location body = Fx.body(victim);
            Vfx.cloud(ctx(), body, Particle.DUST_COLOR_TRANSITION, 10, 0.3, 0, GLOW, false);
            Vfx.ring(ctx(), victim.getLocation().add(0, 0.1, 0), 0.6, 10, 0, Particle.DUST, ROT_DUST, false);
            Fx.sound(body, Sound.ENTITY_ZOMBIE_INFECT, 0.6f, 0.9f + Fx.jitter(0.1f));
        }
    }

    @Override
    public void onTick(long tick) {
        landing.update(player(), ctx().currentTick());
        if (infections.isEmpty() || tick % INFECTION_PERIOD != 0) {
            return;
        }
        Player owner = player();
        long now = ctx().currentTick();
        List<LivingEntity> spreadTo = new ArrayList<>();
        for (Iterator<Infection> it = infections.values().iterator(); it.hasNext(); ) {
            Infection infection = it.next();
            LivingEntity victim = infection.victim;
            boolean gone = !Combat.isTargetable(ctx(), owner, victim);
            // The last rot falls on the moment the infection ends: 5 seconds of infection rot 5 times.
            if (!gone && now >= infection.nextRot && infection.nextRot <= infection.expires) {
                infection.nextRot += DOT_PERIOD;
                rot(infection);
                gone = !Combat.isTargetable(ctx(), owner, victim);
            }
            if (gone || now >= infection.expires) {
                cure(infection);
                it.remove();
                continue;
            }
            follow(infection);
            if (infection.contagious && now >= infection.nextSpread) {
                infection.nextSpread = now + Math.max(1, params().ticks("spread_interval"));
                LivingEntity next = contagion(victim, spreadTo);
                if (next != null) {
                    spreadTo.add(next);
                    contagionFx(victim, next);
                }
            }
            if (tick % DRIP_PERIOD == 0 && !isHiddenFromOwner(victim)) {
                Location body = Fx.body(victim);
                World world = body.getWorld();
                world.spawnParticle(Particle.DUST, body, Vfx.n(ctx(), 2), 0.25, 0.4, 0.25, 0, ROT_SMALL);
                Vfx.dot(body.clone().add(Fx.jitter(0.25f), -0.2, Fx.jitter(0.25f)), Particle.FALLING_DUST,
                        Material.LIME_CONCRETE_POWDER.createBlockData(), false);
            }
        }
        for (LivingEntity next : spreadTo) {
            infect(next, params().ticks("spread_duration"), false);
        }
    }

    /** One tick of rot: credited ability damage and a green squelch. */
    private void rot(Infection infection) {
        LivingEntity victim = infection.victim;
        Combat.hit(ctx(), player(), victim, Math.max(0, params().num("infect_damage")));
        if (isHiddenFromOwner(victim) || !victim.isValid()) {
            return;
        }
        Location body = Fx.body(victim);
        Vfx.cloud(ctx(), body, Particle.DUST_COLOR_TRANSITION, 5, 0.25, 0, GLOW_SMALL, false);
        Vfx.cloud(ctx(), body, Particle.SNEEZE, 2, 0.2, 0.01, null, false);
        Fx.sound(body, Sound.ENTITY_SLIME_SQUISH_SMALL, 0.6f, 0.6f + Fx.jitter(0.1f));
    }

    /** The nearest enemy within the spread radius of {@code from} that is not infected yet (nor picked this tick). */
    private @Nullable LivingEntity contagion(LivingEntity from, List<LivingEntity> picked) {
        double radius = Math.max(0, params().num("spread_radius"));
        Location body = Fx.body(from);
        for (LivingEntity enemy : ctx().enemiesNear(player(), body, radius)) {
            if (enemy.equals(from) || isInfected(enemy) || picked.contains(enemy) || isHiddenFromOwner(enemy)) {
                continue;
            }
            if (Spots.clearPath(body, Fx.body(enemy))) {
                return enemy;
            }
        }
        return null;
    }

    private void contagionFx(LivingEntity from, LivingEntity to) {
        Location a = Fx.body(from);
        Location b = Fx.body(to);
        for (int i = 0; i < Vfx.n(ctx(), 4); i++) {
            Vfx.trail(a.clone().add(0, 0.15 * i - 0.2, 0), b, i % 2 == 0 ? PALETTE.body() : PALETTE.core(), 8 + i,
                    false);
        }
        Vector delta = b.toVector().subtract(a.toVector());
        int points = Vfx.n(ctx(), 8);
        for (int i = 1; i < points; i++) {
            double t = (double) i / points;
            Vfx.dot(a.clone().add(delta.clone().multiply(t)).add(0, Math.sin(Math.PI * t) * 0.6, 0), Particle.DUST,
                    ROT_DUST, false);
        }
        Fx.sound(b, Sound.ENTITY_ZOMBIE_INFECT, 0.7f, 1.3f + Fx.jitter(0.1f));
        Fx.sound(a, Sound.ENTITY_PLAYER_BREATH, 0.6f, 0.6f);
    }

    private void cure(Infection infection) {
        if (infection.slow != null) {
            ctx().removeModifier(infection.victim, infection.slow);
            infection.slow = null;
        }
        TextDisplay mark = infection.mark;
        if (mark != null && mark.isValid()) {
            Displays.shrinkAway(ctx(), player(), mark, 4);
        }
        infection.mark = null;
    }

    private void showMark(Infection infection) {
        TextDisplay mark = infection.mark;
        if (mark != null && mark.isValid()) {
            return;
        }
        if (!room(1)) {
            return;
        }
        mark = keep(Displays.text(ctx(), player(), markSpot(infection.victim),
                Text.mm("<b><" + ROT + ">☣</" + ROT + "></b>"), MARK_SCALE, 0));
        mark.setTeleportDuration((int) INFECTION_PERIOD);
        infection.mark = mark;
        infection.markHidden = false;
        follow(infection);
    }

    /** The mark follows its victim; it is not shown while the victim is hidden from the zombie. */
    private void follow(Infection infection) {
        TextDisplay mark = infection.mark;
        if (mark == null || !mark.isValid()) {
            return;
        }
        if (mark.getWorld().equals(infection.victim.getWorld())) {
            Displays.moveTo(mark, markSpot(infection.victim));
        }
        boolean hide = isHiddenFromOwner(infection.victim);
        if (hide != infection.markHidden) {
            infection.markHidden = hide;
            mark.setViewRange(hide ? 0f : 1.5f);
        }
    }

    private static Location markSpot(LivingEntity victim) {
        return victim.getLocation().add(0, victim.getHeight() + MARK_ABOVE, 0);
    }

    // ================================================================== active: the bite

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (landing.absorb(event, ctx().currentTick())) {
            return;
        }
        if (lunging && Combat.isFall(event)) {
            event.setCancelled(true);
        }
    }

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        if (owner.isInsideVehicle()) {
            return ActivationResult.fail("탈것에 탄 채로는 덤벼들 수 없습니다");
        }
        if (lunging) {
            return ActivationResult.fail("이미 덤벼드는 중입니다");
        }
        Location start = owner.getLocation();
        double range = Math.max(0.5, params().num("bite_range"));
        LivingEntity prey = ctx().findTarget(owner, range + AIM_SLACK);
        Vector flat;
        double distance;
        if (prey != null) {
            flat = prey.getLocation().toVector().subtract(start.toVector()).setY(0);
            distance = Math.min(range, Math.max(0, flat.length() - STOP_SHORT));
            flat = flat.lengthSquared() < 1.0E-6 ? Motion.horizontalFacing(start) : flat.normalize();
        } else {
            flat = Motion.horizontalFacing(start);
            distance = range;
        }
        double reach = Spots.arenaReach(ctx(), start, flat, distance);
        if (reach < distance) {
            distance = Math.max(0, reach - BORDER_MARGIN);
        }
        double up = Motion.launchSpeed(LUNGE_HEIGHT);
        long air = airTicks(up);
        double forward = Math.min(MAX_LUNGE_SPEED, distance * (1 - AIR_DRAG) / (1 - Math.pow(AIR_DRAG, air)));
        owner.setVelocity(Motion.clamp(flat.clone().multiply(forward).setY(up), MAX_VELOCITY));
        owner.setFallDistance(0);
        lunging = true;
        landing.arm(ctx().currentTick(), LANDING_GUARD_TICKS);
        lungeFx(start, flat);
        Lunge lunge = new Lunge(flat, STUCK_TICKS + lagTicks(owner));
        Loop.repeat(ctx(), owner, 1, 1, MAX_LUNGE_TICKS, lunge::step, lunge::timeout);
        return ActivationResult.success();
    }

    /** Ticks until a body launched upwards with {@code up} is back at its starting height. */
    private static long airTicks(double up) {
        double y = 0;
        double v = up;
        for (int t = 1; t < 200; t++) {
            y += v;
            v = (v - Motion.GRAVITY) * Motion.DRAG;
            if (y <= 0) {
                return t;
            }
        }
        return 200;
    }

    /** Ticks the server sees the owner's movement late: one round trip, capped (ping can read 0 after joining). */
    private static long lagTicks(Player player) {
        return Math.min(MAX_LAG_TICKS, (long) Math.ceil(Math.max(0, player.getPing()) / 50.0));
    }

    /** The lunge in flight: bites the first enemy it touches, or whoever is in reach where it lands. */
    private final class Lunge {
        private final Vector flat;
        private final long stuckAfter;
        private boolean airborne;
        private boolean done;

        Lunge(Vector flat, long stuckAfter) {
            this.flat = flat;
            this.stuckAfter = stuckAfter;
        }

        boolean step(long tick) {
            Player owner = player();
            owner.setFallDistance(0);
            streak(owner);
            BoundingBox reach = owner.getBoundingBox().expand(CONTACT_GAP);
            for (LivingEntity enemy : ctx().enemiesNear(owner, Fx.body(owner), 2.5)) {
                if (enemy.getBoundingBox().overlaps(reach) && !isHiddenFromOwner(enemy)) {
                    finish(enemy);
                    return false;
                }
            }
            boolean grounded = Motion.isGrounded(owner) || owner.isInWater() || owner.isClimbing();
            if (!grounded) {
                airborne = true;
            } else if (airborne || tick >= stuckAfter) {
                finish(landingPrey(owner));
                return false;
            }
            return true;
        }

        void timeout() {
            finish(null);
        }

        private @Nullable LivingEntity landingPrey(Player owner) {
            Location body = Fx.body(owner);
            for (LivingEntity enemy : ctx().enemiesNear(owner, body, LAND_REACH)) {
                if (!isHiddenFromOwner(enemy) && Spots.clearPath(body, Fx.body(enemy))) {
                    return enemy;
                }
            }
            return null;
        }

        private void finish(@Nullable LivingEntity prey) {
            if (done) {
                return;
            }
            done = true;
            lunging = false;
            Player owner = player();
            owner.setFallDistance(0);
            if (prey != null) {
                // Stop on the victim instead of sliding past it.
                owner.setVelocity(new Vector(0, -Motion.GRAVITY * Motion.DRAG, 0));
                bite(owner, prey, flat);
                return;
            }
            double left = ctx().cooldownRemaining(owner);
            if (left > 0) {
                ctx().reduceCooldown(owner, left * REFUND);
            }
            Location feet = owner.getLocation().add(0, 0.1, 0);
            Vfx.cloud(ctx(), feet, Particle.DUST, 6, 0.3, 0.02, GRAVE_DUST, false);
            Fx.sound(feet, Sound.ENTITY_ZOMBIE_AMBIENT, 0.6f, 0.7f + Fx.jitter(0.05f));
            owner.sendActionBar(Text.mm("<gray>허공을 물었습니다</gray> <dark_gray>(쿨타임 절반 회복)</dark_gray>"));
        }
    }

    /** The bite: damage and infection; an enemy that was already infected is fed on. */
    private void bite(Player owner, LivingEntity prey, Vector flat) {
        boolean feeding = isInfected(prey);
        double damage = Math.max(0, params().num("bite_damage")) + (feeding ? Math.max(0, params().num("feed_damage")) : 0);
        Combat.hit(ctx(), owner, prey, damage);
        infect(prey, params().ticks("infect_duration"), true);
        Location body = Fx.body(prey);
        jaws(prey);
        World world = body.getWorld();
        world.spawnParticle(Particle.BLOCK, body, Vfx.n(ctx(), 16), 0.25, 0.3, 0.25, 0.15, BLOOD_BLOCK.createBlockData());
        world.spawnParticle(Particle.DUST, body, Vfx.n(ctx(), 10), 0.3, 0.35, 0.3, 0, BLOOD_DUST);
        Vfx.cloud(ctx(), body, Particle.DAMAGE_INDICATOR, 4, 0.2, 0.1, null, false);
        Vfx.arc(ctx(), body, 0.6, (float) Math.toDegrees(Math.atan2(-flat.getX(), flat.getZ())), 120, 10,
                Particle.DUST_COLOR_TRANSITION, FADE, false);
        Fx.sound(body, Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 0.7f, 1.3f + Fx.jitter(0.08f));
        Fx.sound(body, Sound.ENTITY_GENERIC_EAT, 1.0f, 0.7f + Fx.jitter(0.08f));
        Fx.sound(body, Sound.ENTITY_PLAYER_HURT_SWEET_BERRY_BUSH, 0.8f, 0.8f + Fx.jitter(0.08f));
        if (feeding) {
            double heal = Math.max(0, params().num("feed_heal"));
            if (heal > 0 && owner.isValid() && !owner.isDead()) {
                ctx().heal(owner, heal);
                Location chest = Fx.body(owner);
                for (int i = 0; i < Vfx.n(ctx(), 4); i++) {
                    Vfx.trail(body.clone().add(0, 0.2 * i - 0.3, 0), chest, i % 2 == 0 ? BLOOD : PALETTE.body(),
                            8 + i, false);
                }
                Fx.sound(chest, Sound.ENTITY_PLAYER_BURP, 0.7f, 0.8f + Fx.jitter(0.08f));
            }
            owner.sendActionBar(Text.mm("<" + BLOOD_TAG + "><b>포식!</b></" + BLOOD_TAG + "> <white>"
                    + AbilityInfo.formatNumber(damage) + " 피해</white><gray> · 체력</gray> <white>+"
                    + AbilityInfo.formatNumber(heal) + "</white>"));
        } else {
            owner.sendActionBar(Text.mm("<" + ROT + "><b>물어뜯기!</b></" + ROT + "> <gray>감염</gray>"));
        }
    }

    /** A pair of bone-white jaws clamping shut on the victim's head. */
    private void jaws(LivingEntity prey) {
        if (!room(2)) {
            return;
        }
        Player owner = player();
        Location head = prey.getLocation().add(0, prey.getHeight() * 0.8, 0);
        TextDisplay upper = keep(Displays.text(ctx(), owner, head, Text.mm("<b><#F2EDE0>▼▼▼</#F2EDE0></b>"), 0.9f,
                JAW_LIFE));
        TextDisplay lower = keep(Displays.text(ctx(), owner, head, Text.mm("<b><#F2EDE0>▲▲▲</#F2EDE0></b>"), 0.9f,
                JAW_LIFE));
        upper.setTransformation(Displays.centered(0, 0.45f, 0, 0.9f, 0.9f, 0.9f, 0, 0, 0));
        lower.setTransformation(Displays.centered(0, -0.55f, 0, 0.9f, 0.9f, 0.9f, 0, 0, 0));
        Displays.animateNextTick(ctx(), owner, upper, Displays.centered(0, 0.02f, 0, 1.0f, 0.8f, 1.0f, 0, 0, 0),
                (int) JAW_TICKS);
        Displays.animateNextTick(ctx(), owner, lower, Displays.centered(0, -0.15f, 0, 1.0f, 0.8f, 1.0f, 0, 0, 0),
                (int) JAW_TICKS);
        ctx().runLater(owner, JAW_LIFE - 5, () -> {
            for (TextDisplay jaw : new TextDisplay[] {upper, lower}) {
                if (jaw.isValid()) {
                    Displays.shrinkAway(ctx(), owner, jaw, 4);
                }
            }
        });
    }

    /** The lunge's take-off: grave dust at the feet and a hungry groan. */
    private void lungeFx(Location start, Vector flat) {
        Location feet = start.clone().add(0, 0.1, 0);
        World world = feet.getWorld();
        Vfx.ring(ctx(), feet, 0.6, 10, ThreadLocalRandom.current().nextDouble(TAU), Particle.DUST, GRAVE_DUST, false);
        BlockData ground = feet.clone().subtract(0, 0.3, 0).getBlock().getBlockData();
        if (ground.getMaterial().isSolid()) {
            world.spawnParticle(Particle.BLOCK, feet, Vfx.n(ctx(), 10), 0.35, 0.05, 0.35, 0.1, ground);
        }
        Vfx.arc(ctx(), feet.clone().add(0, 1.0, 0), 0.8, (float) Math.toDegrees(Math.atan2(-flat.getX(), flat.getZ())),
                100, 8, Particle.DUST_COLOR_TRANSITION, GLOW_SMALL, false);
        Fx.sound(start, Sound.ENTITY_ZOMBIE_AMBIENT, 1.0f, 0.8f + Fx.jitter(0.08f));
        Fx.sound(start, Sound.ENTITY_GOAT_LONG_JUMP, 0.6f, 0.7f + Fx.jitter(0.05f));
    }

    /** The lunge's trail: rot and grave dust streaming off the body. */
    private void streak(Player owner) {
        Location body = Fx.body(owner);
        World world = body.getWorld();
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, body, Vfx.n(ctx(), 3), 0.25, 0.35, 0.25, 0, FADE);
        world.spawnParticle(Particle.DUST, body, Vfx.n(ctx(), 2), 0.2, 0.3, 0.2, 0, GRAVE_DUST);
    }

    // ================================================================== ultimate: the outbreak

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        double radius = Math.max(1, params().num("ult_radius"));
        double hordeRange = Math.max(radius, params().num("horde_range"));
        boolean anyone = false;
        for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), hordeRange)) {
            if (isHiddenFromOwner(enemy)) {
                continue;
            }
            if (isInfected(enemy) || enemy.getLocation().distance(owner.getLocation()) <= radius + 0.5) {
                anyone = true;
                break;
            }
        }
        if (!anyone) {
            return ActivationResult.fail("감염시킬 적이 없습니다");
        }
        outbreak(owner, radius, hordeRange);
        return ActivationResult.success();
    }

    /** The groan, the infection of everyone around, the cracking ground and, after the delay, the rising dead. */
    private void outbreak(Player owner, double radius, double hordeRange) {
        Set<UUID> sick = new HashSet<>();
        for (Infection infection : infections.values()) {
            if (isInfected(infection.victim)) {
                sick.add(infection.victim.getUniqueId());
            }
        }
        Location center = owner.getLocation();
        Location feet = center.clone().add(0, 0.1, 0);
        Vfx.shockwave(ctx(), owner, feet, 0.5, radius, 8, Particle.DUST_COLOR_TRANSITION, GLOW, true);
        Vfx.shockwave(ctx(), owner, feet.clone().add(0, 0.4, 0), 0.4, radius * 0.85, 10, Particle.DUST, GRAVE_DUST,
                true);
        Vfx.cloud(ctx(), Fx.body(owner), Particle.SNEEZE, 20, 0.6, 0.05, null, true);
        Fx.sound(center, Sound.ENTITY_ZOMBIE_AMBIENT, 1.6f, 0.5f);
        Fx.sound(center, Sound.ENTITY_ZOMBIE_VILLAGER_AMBIENT, 1.2f, 0.6f);
        Fx.sound(center, Sound.ENTITY_HUSK_AMBIENT, 1.2f, 0.7f);
        Fx.sound(center, Sound.ENTITY_ZOMBIE_INFECT, 1.4f, 0.6f);
        owner.sendActionBar(Text.mm("<" + ROT + "><b>좀비 아포칼립스</b></" + ROT + "> <" + SICK + ">죽은 자들이 깨어납니다...</" + SICK + ">"));

        for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius)) {
            if (isHiddenFromOwner(enemy)) {
                continue;
            }
            for (int i = 0; i < Vfx.n(ctx(), 3); i++) {
                Vfx.trail(Fx.body(owner), Fx.body(enemy).add(0, 0.2 * i - 0.2, 0), PALETTE.body(), 10 + 2 * i, true);
            }
            infect(enemy, params().ticks("infect_duration"), true);
        }

        List<LivingEntity> hosts = new ArrayList<>();
        for (LivingEntity enemy : ctx().enemiesNear(owner, center, hordeRange)) {
            if (isInfected(enemy) && !isHiddenFromOwner(enemy)) {
                hosts.add(enemy);
            }
        }
        int max = Math.max(0, params().integer("max_zombies"));
        int per = Math.max(0, params().integer("zombies_per"));
        int sickPer = Math.max(0, params().integer("zombies_infected"));
        Map<LivingEntity, Integer> planned = new HashMap<>();
        int total = 0;
        for (LivingEntity host : hosts) {
            int want = Math.min(max - total, sick.contains(host.getUniqueId()) ? sickPer : per);
            if (want <= 0) {
                continue;
            }
            planned.put(host, want);
            total += want;
            crack(host);
        }
        long delay = Math.max(1, params().ticks("rise_delay"));
        ctx().runLater(owner, delay, () -> {
            for (Map.Entry<LivingEntity, Integer> entry : planned.entrySet()) {
                rise(owner, entry.getKey(), entry.getValue());
            }
        });
    }

    /** The ground cracks open at the host's feet and rotting arms reach out of the dirt (the telegraph). */
    private void crack(LivingEntity host) {
        Player owner = player();
        Location feet = host.getLocation();
        Spots.snapToGround(feet.getWorld(), feet, 2);
        Location ground = Displays.unrotated(feet.add(0, 0.02, 0));
        long delay = Math.max(1, params().ticks("rise_delay"));
        if (room(1 + ARMS_PER_HOST * 2)) {
            BlockDisplay crack = keep(Displays.block(ctx(), owner, ground, CRACK_BLOCK.createBlockData(),
                    Displays.transform(0, 0, 0, 0.1f, 0.02f, 0.1f, ThreadLocalRandom.current().nextFloat() * 90, 0, 0),
                    delay + 20));
            Displays.animateNextTick(ctx(), owner, crack, Displays.transform(0, 0, 0, 1.9f, 0.04f, 1.9f,
                    ThreadLocalRandom.current().nextFloat() * 90, 0, 0), (int) Math.min(10, delay));
            ctx().runLater(owner, delay + 8, () -> {
                if (crack.isValid()) {
                    Displays.shrinkAway(ctx(), owner, crack, 8);
                }
            });
            double phase = ThreadLocalRandom.current().nextDouble(TAU);
            for (int i = 0; i < ARMS_PER_HOST; i++) {
                double angle = phase + i * TAU / ARMS_PER_HOST;
                Location base = ground.clone().add(Math.cos(angle) * 0.7, 0, Math.sin(angle) * 0.7);
                arm(base, (float) Math.toDegrees(Math.atan2(Math.cos(angle), -Math.sin(angle))), delay);
            }
        }
        if (isHiddenFromOwner(host)) {
            return;
        }
        Loop.repeat(ctx(), owner, 0, 3, Math.max(1, delay / 3), run -> {
            if (!host.isValid()) {
                return false;
            }
            Location at = host.getLocation().add(0, 0.1, 0);
            Vfx.ring(ctx(), at, 1.1 - 0.15 * run, 12, run * 0.5, Particle.DUST_COLOR_TRANSITION, GLOW, true);
            World world = at.getWorld();
            world.spawnParticle(Particle.BLOCK, at, Vfx.n(ctx(), 6), 0.6, 0.05, 0.6, 0.1, DIRT_BLOCK.createBlockData());
            if (run % 2 == 0) {
                Fx.sound(at, Sound.BLOCK_ROOTED_DIRT_BREAK, 0.9f, 0.6f + Fx.jitter(0.1f));
            }
            return true;
        });
        Fx.sound(feet, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 0.5f, 0.6f);
    }

    /** A rotting arm (forearm and hand) bursting up out of the ground, leaning towards the host, then sinking. */
    private void arm(Location base, float yaw, long hold) {
        Player owner = player();
        BlockDisplay forearm = keep(Displays.block(ctx(), owner, base, ARM_BLOCK.createBlockData(),
                Displays.transform(0, 0, 0, 0.18f, 0.01f, 0.18f, yaw, 15, 0), hold + 16));
        BlockDisplay hand = keep(Displays.block(ctx(), owner, base, HAND_BLOCK.createBlockData(),
                Displays.transform(0, 0, 0, 0.24f, 0.01f, 0.12f, yaw, 15, 0), hold + 16));
        Displays.animateNextTick(ctx(), owner, forearm, Displays.transform(0, 0.3f, 0, 0.18f, 0.6f, 0.18f, yaw, 25, 0),
                4);
        Displays.animateNextTick(ctx(), owner, hand, Displays.transform(0, 0.68f, 0.12f, 0.26f, 0.2f, 0.14f, yaw, 40,
                0), 4);
        ctx().runLater(owner, 5, () -> {
            // Grasping: the hand clenches back and forth.
            Displays.animate(hand, Displays.transform(0, 0.62f, 0.18f, 0.24f, 0.16f, 0.16f, yaw, 65, 0), 4);
        });
        ctx().runLater(owner, Math.max(6, hold), () -> {
            for (BlockDisplay part : new BlockDisplay[] {forearm, hand}) {
                if (part.isValid()) {
                    Displays.shrinkAway(ctx(), owner, part, 8);
                }
            }
        });
    }

    /** Zombies climb out next to {@code host} (as many as fit), each one a summon hunting that host. */
    private void rise(Player owner, LivingEntity host, int count) {
        if (!host.isValid() || host.isDead()) {
            return;
        }
        List<Location> spots = spawnSpots(host, count);
        for (Location spot : spots) {
            Zombie zombie = raise(owner, spot);
            if (zombie == null) {
                continue;
            }
            Walker walker = new Walker(zombie, host, params().ticks("zombie_duration"));
            horde.add(walker);
            hordeIds.add(zombie.getUniqueId());
            walker.start();
            emerge(spot);
        }
        if (!spots.isEmpty()) {
            Fx.sound(host.getLocation(), Sound.ENTITY_ZOMBIE_AMBIENT, 1.2f, 0.7f + Fx.jitter(0.1f));
            Fx.sound(host.getLocation(), Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 0.8f, 0.8f);
        }
    }

    /** Safe standing spots around {@code host}: room for a zombie, ground below, in the arena, in sight of the host. */
    private List<Location> spawnSpots(LivingEntity host, int count) {
        List<Location> spots = new ArrayList<>();
        World world = host.getWorld();
        Location base = host.getLocation();
        Location focus = Fx.body(host);
        double phase = ThreadLocalRandom.current().nextDouble(360);
        for (double distance : SPAWN_DISTANCES) {
            for (double angle : SPAWN_ANGLES) {
                if (spots.size() >= count) {
                    return spots;
                }
                double radians = Math.toRadians(phase + angle);
                Location point = base.clone().add(Math.cos(radians) * distance, 0, Math.sin(radians) * distance);
                RayTraceResult down = world.rayTraceBlocks(point.clone().add(0, 1.0, 0), new Vector(0, -1, 0), 3.0,
                        FluidCollisionMode.NEVER, true);
                if (down == null) {
                    continue;
                }
                Location feet = down.getHitPosition().toLocation(world);
                boolean taken = spots.stream().anyMatch(other -> other.distanceSquared(feet) < 0.8);
                if (taken || !Spots.fits(world, feet, ZOMBIE_WIDTH, ZOMBIE_HEIGHT) || !Spots.hasGroundBelow(world, feet)
                        || !ctx().isInsideArena(feet) || !Spots.clearPath(focus, feet.clone().add(0, 1.0, 0))) {
                    continue;
                }
                Vector look = focus.toVector().subtract(feet.toVector().add(new Vector(0, 1.6, 0)));
                if (look.lengthSquared() > 1.0E-6) {
                    feet.setDirection(look);
                }
                spots.add(feet);
            }
        }
        return spots;
    }

    /** Spawns one zombie at {@code at} as the owner's summon. Null if it did not spawn. */
    private @Nullable Zombie raise(Player owner, Location at) {
        double health = Math.max(1, params().num("zombie_health"));
        double damage = Math.max(0, params().num("zombie_damage"));
        boolean peaceful = at.getWorld().getDifficulty() == Difficulty.PEACEFUL;
        Consumer<Zombie> setup = spawned -> {
            spawned.setPersistent(false);
            setBase(spawned, Attribute.MAX_HEALTH, health);
            setBase(spawned, Attribute.ATTACK_DAMAGE, damage);
            setBase(spawned, Attribute.MOVEMENT_SPEED, ZOMBIE_SPEED);
            spawned.setHealth(Math.min(health, Combat.maxHealth(spawned)));
            spawned.setAdult();
            dress(spawned.getEquipment());
            if (peaceful) {
                // Monsters are discarded on Peaceful the tick they spawn unless told otherwise.
                spawned.setDespawnInPeacefulOverride(TriState.FALSE);
            }
            spawned.setCustomNameVisible(true);
        };
        // No random data: always an adult, no random gear, no reinforcement calls, no door breaking, no looting.
        Zombie zombie = at.getWorld().spawn(at, Zombie.class, false, setup);
        if (!zombie.isValid()) {
            return null;
        }
        ctx().summon(owner, zombie);
        return zombie;
    }

    /**
     * Empty hands and a blood-stained cap: unbreakable, since any helmet keeps a zombie from burning in daylight.
     * Nothing drops.
     */
    private static void dress(@Nullable EntityEquipment equipment) {
        if (equipment == null) {
            return;
        }
        equipment.clear();
        ItemStack cap = ItemStack.of(Material.LEATHER_HELMET);
        cap.setData(DataComponentTypes.DYED_COLOR, DyedItemColor.dyedItemColor(CAP_COLOR));
        cap.setData(DataComponentTypes.UNBREAKABLE);
        equipment.setHelmet(cap, true);
        for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                EquipmentSlot.FEET, EquipmentSlot.HAND, EquipmentSlot.OFF_HAND}) {
            equipment.setDropChance(slot, 0f);
        }
    }

    private static void setBase(LivingEntity entity, Attribute attribute, double value) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance == null) {
            entity.registerAttribute(attribute);
            instance = entity.getAttribute(attribute);
        }
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    /** A zombie climbing out: dirt bursting up and grave dust. */
    private void emerge(Location spot) {
        World world = spot.getWorld();
        Location ground = spot.clone().add(0, 0.15, 0);
        world.spawnParticle(Particle.BLOCK, ground, Vfx.n(ctx(), 18), 0.4, 0.1, 0.4, 0.2, DIRT_BLOCK.createBlockData());
        BlockData floor = spot.clone().subtract(0, 0.3, 0).getBlock().getBlockData();
        if (floor.getMaterial().isSolid()) {
            world.spawnParticle(Particle.BLOCK, ground, Vfx.n(ctx(), 10), 0.4, 0.1, 0.4, 0.2, floor);
        }
        Vfx.helix(ctx(), spot, 0.5, ZOMBIE_HEIGHT, 1.0, 2, 8, 0, Particle.DUST_COLOR_TRANSITION, GLOW, true);
        Vfx.cloud(ctx(), spot.clone().add(0, 1.0, 0), Particle.SNEEZE, 8, 0.3, 0.03, null, false);
        Fx.sound(spot, Sound.BLOCK_ROOTED_DIRT_BREAK, 1.2f, 0.6f);
        Fx.sound(spot, Sound.ENTITY_HUSK_CONVERTED_TO_ZOMBIE, 0.8f, 0.8f + Fx.jitter(0.1f));
    }

    /** One risen zombie and the controller task that keeps it hunting its host until its time runs out. */
    private final class Walker {
        private final Zombie mob;
        private final LivingEntity host;
        private final long expiresAt;
        private boolean dismissed;
        private long nextStrike;

        Walker(Zombie mob, LivingEntity host, long lifetime) {
            this.mob = mob;
            this.host = host;
            this.expiresAt = ctx().currentTick() + Math.max(1, lifetime);
        }

        void start() {
            updateName(expiresAt - ctx().currentTick());
            mob.setTarget(host);
            long runs = (expiresAt - ctx().currentTick()) / CONTROL_PERIOD + 2;
            Loop.repeat(ctx(), player(), CONTROL_PERIOD, CONTROL_PERIOD, runs, this::control, () -> dismiss(true));
        }

        private boolean control(long run) {
            if (dismissed) {
                return false;
            }
            if (!mob.isValid() || mob.isDead()) {
                forget();
                dismissed = true;
                crumble(mob.getLocation());
                return false;
            }
            long left = expiresAt - ctx().currentTick();
            if (left <= 0) {
                return false;
            }
            if (run % (20 / CONTROL_PERIOD) == 0) {
                updateName(left);
            }
            if (mob.isConverting()) {
                // A zombie under water would turn into a new, untracked drowned.
                mob.setConversionTime(-1);
            }
            LivingEntity target = chooseTarget();
            if (target != mob.getTarget()) {
                mob.setTarget(target);
            }
            if (target instanceof Player victim && mob.getWorld().getDifficulty() == Difficulty.PEACEFUL) {
                strike(victim);
            }
            if (run % 2 == 0) {
                Location body = mob.getLocation().add(0, 1.0, 0);
                Vfx.dot(body.clone().add(Fx.jitter(0.3f), Fx.jitter(0.5f), Fx.jitter(0.3f)), Particle.DUST, ROT_DUST,
                        false);
            }
            return true;
        }

        /** Its host while that is still a visible enemy in reach, otherwise the nearest visible enemy. */
        private @Nullable LivingEntity chooseTarget() {
            if (isValidTarget(host)) {
                return host;
            }
            LivingEntity current = mob.getTarget();
            if (current != null && isValidTarget(current)) {
                return current;
            }
            for (LivingEntity candidate : ctx().enemiesNear(player(), mob.getLocation(), CHASE_RANGE)) {
                if (!isHiddenFromOwner(candidate)) {
                    return candidate;
                }
            }
            return null;
        }

        private boolean isValidTarget(LivingEntity target) {
            return Combat.isTargetable(ctx(), player(), target) && !isHiddenFromOwner(target)
                    && target.getWorld().equals(mob.getWorld())
                    && target.getLocation().distanceSquared(mob.getLocation()) <= CHASE_RANGE * CHASE_RANGE * 2.25;
        }

        /**
         * Peaceful only: vanilla mob attacks on players are dropped there, so the zombie bites through the context
         * (credited, enemy-only) at the vanilla attack pace once it is in reach.
         */
        private void strike(Player target) {
            if (!mob.getBoundingBox().expand(STRIKE_REACH).overlaps(target.getBoundingBox())) {
                mob.getPathfinder().moveTo(target, 1.2);
                return;
            }
            long now = ctx().currentTick();
            if (now < nextStrike) {
                return;
            }
            nextStrike = now + STRIKE_TICKS;
            zombieBite = true;
            try {
                Combat.hit(ctx(), player(), target, Math.max(0, params().num("zombie_damage")), DamageType.MOB_ATTACK);
            } finally {
                zombieBite = false;
            }
        }

        private void updateName(long ticksLeft) {
            long seconds = Math.max(0, (ticksLeft + 19) / 20);
            mob.customName(Component.text("감염자", NAME_COLOR)
                    .append(Component.text(" " + seconds + "초", NamedTextColor.GRAY)));
        }

        private void forget() {
            horde.remove(this);
            hordeIds.remove(mob.getUniqueId());
        }

        /** Time is up: the zombie collapses into rot and dirt. Safe to call more than once. */
        void dismiss(boolean effects) {
            forget();
            if (dismissed) {
                return;
            }
            dismissed = true;
            if (mob.isValid()) {
                Location at = mob.getLocation();
                mob.remove();
                if (effects) {
                    crumble(at);
                }
            }
        }
    }

    /** A zombie falling apart: rotten flesh, grave dirt and a last groan. */
    private void crumble(Location at) {
        World world = at.getWorld();
        Location body = at.clone().add(0, 0.9, 0);
        world.spawnParticle(Particle.BLOCK, body, Vfx.n(ctx(), 12), 0.25, 0.5, 0.25, 0.1, DIRT_BLOCK.createBlockData());
        Vfx.sphere(ctx(), body, 0.5, 12, Particle.DUST_COLOR_TRANSITION, FADE, false);
        Vfx.cloud(ctx(), at.clone().add(0, 0.2, 0), Particle.DUST, 6, 0.3, 0, GRAVE_DUST, false);
        Fx.sound(at, Sound.ENTITY_ZOMBIE_DEATH, 0.8f, 0.8f + Fx.jitter(0.1f));
    }

    // ================================================================== helpers

    private boolean isHiddenFromOwner(LivingEntity entity) {
        return entity instanceof Player hidden && !player().canSee(hidden);
    }

    /** Claims room for {@code count} more displays (dead ones are forgotten first). */
    private boolean room(int count) {
        live.removeIf(display -> !display.isValid());
        return live.size() + count <= MAX_DISPLAYS;
    }

    private <T extends Display> T keep(T display) {
        live.add(display);
        return display;
    }
}
