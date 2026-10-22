package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Body;
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
import net.kyori.adventure.text.Component;
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
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 늑대인간 — a feral melee hunter. Stronger and faster under the night sky (a little even by day), pounces on its
 * prey with a claw leap that makes it bleed, and as the ultimate turns into a towering beast whose every claw
 * strike bleeds and feeds it, howling its enemies into a fearful crawl.
 *
 * <p>Look: brown fur and molten-gold eyes. Melee hits flash a gold claw glint (under the moon also two torn slashes);
 * nightfall wakes the werewolf with a growl and rising gold. The pounce marks its landing spot in gold and leaves a
 * streak of fur and two gold glints where the eyes passed; the claw rakes three white blades across in front of the
 * werewolf and tears three slashes across each victim with a spray of blood, and bleeding enemies drip blood. The
 * beast form bursts out of a spiral of fur, shedding tufts that tumble away, under a pale full moon that follows it;
 * the howl rolls out as golden rings and the frightened enemies shiver in a cold, dark swirl.
 */
public final class WerewolfAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("werewolf", "늑대인간", Material.WOLF_ARMOR)
            .color(TextColor.color(0xC8963E))
            .difficulty(2)
            .passive("야성",
                    "밤에는 공격력이 {night_damage}%, 이동 속도가 {night_speed}% 오릅니다. 낮에는 그 절반만 "
                            + "오릅니다.")
            .active("할퀴기 도약",
                    "조준한 적(없으면 바라보는 방향)으로 최대 {leap_distance}블록 덮쳐, 부딪히거나 착지한 곳 반경 "
                            + "{claw_radius}블록의 적에게 {claw_damage} 피해와 {bleed_duration}초간 출혈({bleed_interval}초마다 "
                            + "{bleed_damage} 피해)을 입힙니다. 이 도약으로는 낙하 피해를 받지 않습니다.",
                    8)
            .ultimate("야수화",
                    "{ult_duration}초간 {ult_scale}배 크기의 야수가 되어 이동 속도 +{ult_speed}%와 최대치의 야성을 "
                            + "얻고, 근접 공격마다 출혈을 일으키며 입힌 피해의 {ult_lifesteal}%를 회복합니다. 변신하며 "
                            + "울부짖어 반경 {howl_radius}블록의 적을 {howl_duration}초간 공포에 빠뜨립니다(이동 속도 "
                            + "-{howl_slow}%).")
            // Bleeding and lifesteal on every claw strike come from this instance's damage hooks and ticks.
            .ultimateNotStandalone()
            .param("night_damage", 20)
            .param("night_speed", 15)
            .param("leap_distance", 7)
            .param("leap_height", 1.5)
            .param("claw_radius", 2.5)
            .param("claw_damage", 4)
            .param("bleed_duration", 3)
            .param("bleed_interval", 1)
            .param("bleed_damage", 1)
            .param("ult_duration", 10)
            .param("ult_scale", 1.3)
            .param("ult_speed", 30)
            .param("ult_lifesteal", 25)
            .param("howl_radius", 8)
            .param("howl_duration", 2)
            .param("howl_slow", 50)
            .build();

    private static final String GOLD = "#E8B04A";
    private static final double TAU = Math.PI * 2;

    // Pounce physics (vanilla player: gravity 0.08/tick, vertical drag 0.98, horizontal air drag 0.91)
    private static final double AIR_DRAG = 0.91;
    private static final double MAX_POUNCE_SPEED = 1.6;
    private static final double MAX_VELOCITY = 2.5;
    /** A pounce aimed at an enemy stops this far short of its feet, so the claw lands in its face. */
    private static final double POUNCE_STOP_SHORT = 0.9;
    /** Extra aim range beyond the leap, so a target just out of reach is still pounced towards. */
    private static final double AIM_SLACK = 2;
    /** Gap between the hitboxes that counts as crashing into an enemy mid-leap. */
    private static final double CONTACT_GAP = 0.45;
    /** A pounce shortened by the arena border stops this far before it. */
    private static final double BORDER_MARGIN = 1.0;
    private static final long MAX_LEAP_TICKS = 40;
    /** Ticks after which a leap that never left the ground (blocked, laggy client) claws where it stands. */
    private static final long STUCK_TICKS = 6;
    /** Most ticks a slow connection may add before a leap that never left the ground counts as stuck. */
    private static final long MAX_LAG_TICKS = 8;
    private static final long LANDING_GUARD_TICKS = 80;
    private static final double CLAW_PUSH = 0.45;
    private static final double CLAW_LIFT = 0.25;
    private static final double FLINCH_PUSH = 0.35;

    // Beast form
    private static final int GROW_STEPS = 4;
    private static final long GROW_INTERVAL = 2;
    private static final double STANDING_HEIGHT = 1.8;
    private static final double HEADROOM_HALF_WIDTH = 0.25;
    private static final long PASSIVE_CHECK_PERIOD = 20;

    // ------------------------------------------------------------------ look: gold eyes, brown fur, dark bark
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFD54F, 0x8D6E63, 0x3E2723);
    private static final Particle.DustOptions FUR = new Particle.DustOptions(Color.fromRGB(0x7A5C48), 1.1f);
    private static final Particle.DustOptions FUR_DARK = new Particle.DustOptions(Color.fromRGB(0x4E3A2C), 0.9f);
    private static final Particle.DustOptions EYE_GLINT = new Particle.DustOptions(PALETTE.core(), 0.7f);
    private static final Particle.DustOptions BLOOD = new Particle.DustOptions(Color.fromRGB(0x9E0B0F), 1.0f);
    private static final Particle.DustOptions DREAD = new Particle.DustOptions(Color.fromRGB(0x3A2E4A), 1.2f);
    private static final Particle.DustTransition CLAW_ARC = new Particle.DustTransition(Color.WHITE,
            PALETTE.core(), 0.9f);
    private static final BlockData BLOOD_BLOCK = Material.REDSTONE_BLOCK.createBlockData();
    private static final BlockData BLOOD_DRIP = Material.RED_CONCRETE.createBlockData();
    private static final BlockData SLASH = Material.WHITE_CONCRETE.createBlockData();
    private static final TextColor MOON_COLOR = TextColor.color(0xFFF3C4);
    private static final Particle.DustOptions MOON_HALO = new Particle.DustOptions(Color.fromRGB(0xFFF3C4), 0.9f);
    /** Display entities this ability keeps alive at most at once (extra claw marks fall back to particles). */
    private static final int MAX_DISPLAYS = 24;
    private static final int SLASH_DRAW_TICKS = 2;
    private static final long SLASH_HOLD_TICKS = 5;
    private static final int SLASH_FADE_TICKS = 6;
    private static final float SLASH_LENGTH = 1.15f;
    private static final float SLASH_WIDTH = 0.06f;
    private static final float SLASH_GAP = 0.22f;
    private static final float RAKE_LENGTH = 1.5f;
    /** Degrees the rake's blades lean at the start and the end of the sweep. */
    private static final float RAKE_SWEEP = 55f;
    private static final int RAKE_TICKS = 3;
    private static final int RAKE_FADE_TICKS = 5;
    /** Fewest ticks between two moonlit slash marks of the feral passive. */
    private static final long FERAL_MARK_TICKS = 12;
    private static final int FUR_TUFTS = 6;
    private static final int TUFT_FLIGHT_TICKS = 10;
    private static final BlockData[] FUR_BLOCKS = {Material.BROWN_WOOL.createBlockData(),
            Material.BROWN_TERRACOTTA.createBlockData()};
    private static final float MOON_SCALE = 6.5f;
    private static final double MOON_HEIGHT = 1.4;
    private static final int HOWL_TICKS = 8;

    /** The passive's current strength: 0 none (before the grant), 1 half (day), 2 full (night or beast form). */
    private int feral;
    private final List<NamespacedKey> feralKeys = new ArrayList<>();
    private final Map<UUID, Bleed> bleeds = new LinkedHashMap<>();
    private final LandingGuard landing = new LandingGuard();
    private boolean leaping;
    private @Nullable Beast beast;
    private int liveDisplays;
    private long lastFeralMark = Long.MIN_VALUE / 2;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    // ------------------------------------------------------------------ passive: feral

    @Override
    public void onGrant() {
        updateFeral(true);
    }

    @Override
    public void onRevoke() {
        // The context removes the modifiers and displays; only forget them here.
        feralKeys.clear();
        feral = 0;
        bleeds.clear();
        leaping = false;
        landing.disarm();
        beast = null;
    }

    @Override
    public void onTick(long tick) {
        Player owner = player();
        landing.update(owner, tick);
        if (tick % PASSIVE_CHECK_PERIOD == 0) {
            updateFeral(false);
        }
        if (!bleeds.isEmpty()) {
            tickBleeds(tick);
        }
        if (beast == null && feral == 2 && tick % 6 == 0 && owner.isSprinting()) {
            // Under the moon the werewolf sheds fur as it runs.
            Location feet = owner.getLocation().add(0, 0.3, 0);
            Vfx.dot(Vfx.scatter(feet, 0.3), Particle.DUST, FUR, false);
        }
    }

    private boolean isNight() {
        return !player().getWorld().isDayTime();
    }

    /** Applies the passive bonus for the current time of day (full in beast form), re-applying it on a change. */
    private void updateFeral(boolean granting) {
        int level = beast != null || isNight() ? 2 : 1;
        if (level == feral) {
            return;
        }
        // The moon rises when night falls mid-match, or right away for a werewolf whose fight starts at night.
        boolean nightfall = level == 2 && beast == null && (granting || feral == 1);
        Player owner = player();
        for (NamespacedKey key : feralKeys) {
            ctx().removeModifier(owner, key);
        }
        feralKeys.clear();
        feral = level;
        double share = level == 2 ? 1.0 : 0.5;
        double damage = params().num("night_damage") / 100.0 * share;
        if (Math.abs(damage) > 1.0E-6) {
            feralKeys.add(ctx().addModifier(owner, owner, Attribute.ATTACK_DAMAGE, Math.max(-0.9, damage),
                    Operation.MULTIPLY_SCALAR_1, 0));
        }
        double speed = params().num("night_speed") / 100.0 * share;
        if (Math.abs(speed) > 1.0E-6) {
            feralKeys.add(ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, Math.max(-0.9, speed),
                    Operation.MULTIPLY_SCALAR_1, 0));
        }
        if (nightfall) {
            moonrise(owner);
        }
    }

    /** Night falls: a low growl, gold glints rising from the werewolf and a short message. */
    private void moonrise(Player owner) {
        Location body = Fx.body(owner);
        Vfx.helix(ctx(), owner.getLocation(), 0.55, owner.getHeight() + 0.3, 1.25, 2, 10, 0,
                Particle.DUST_COLOR_TRANSITION, PALETTE.glow(0.8f), false);
        Vfx.cloud(ctx(), body, Particle.DUST, 8, 0.35, 0, FUR, false);
        Fx.sound(body, Sound.ENTITY_WOLF_BIG_GROWL, 0.8f, 0.7f + Fx.jitter(0.05f));
        Fx.sound(body, Sound.ITEM_GOAT_HORN_SOUND_6, 0.35f, 1.3f);
        owner.sendActionBar(Text.mm("<" + GOLD + ">☾ 달이 떴습니다</" + GOLD + "> <gray>야성이 깨어납니다</gray>"));
    }

    // ------------------------------------------------------------------ damage hooks

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (landing.absorb(event, ctx().currentTick())) {
            return;
        }
        if (leaping && Combat.isFall(event)) {
            event.setCancelled(true);
            return;
        }
        // The beast's bigger body may brush a low ceiling; never let that suffocate it.
        if (beast != null && event.getCause() == DamageCause.SUFFOCATION) {
            event.setCancelled(true);
        }
    }

    /**
     * The owner's own melee hits: in beast form every claw strike bleeds the victim and feeds the beast; otherwise
     * the feral passive shows on the hit (gold claw glints, torn slashes under the moon).
     */
    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (ctx().isAbilityDamage() || !player().equals(event.getDamager())) {
            return;
        }
        DamageCause cause = event.getCause();
        if (cause != DamageCause.ENTITY_ATTACK && cause != DamageCause.ENTITY_SWEEP_ATTACK) {
            return;
        }
        if (beast == null) {
            feralStrike(victim);
            return;
        }
        double dealt = Math.min(Math.max(0, event.getFinalDamage()), victim.getHealth() + victim.getAbsorptionAmount());
        double heal = dealt * Math.max(0, params().num("ult_lifesteal")) / 100.0;
        if (heal > 0.01) {
            ctx().heal(player(), heal);
            feedVisual(victim);
        }
        bleed(victim);
        clawMarks(victim, player().getLocation(), 0.8f, 3);
        Location body = Fx.body(victim);
        victim.getWorld().spawnParticle(Particle.BLOCK, body, Vfx.n(ctx(), 8), 0.2, 0.3, 0.2, 0.12, BLOOD_BLOCK);
        Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.6f, 0.8f + Fx.jitter(0.1f));
    }

    /**
     * The feral passive on a melee hit: a gold claw glint raking across the victim and a tuft of fur. Under the moon
     * (full strength) the claws also tear two slashes across the victim, at most every {@link #FERAL_MARK_TICKS}.
     */
    private void feralStrike(LivingEntity victim) {
        Player owner = player();
        Location body = Fx.body(victim);
        float yaw = owner.getLocation().getYaw();
        Vfx.arc(ctx(), body.clone().add(0, 0.15, 0), victim.getWidth() * 0.5 + 0.35, yaw, 110, 9,
                Particle.DUST_COLOR_TRANSITION, CLAW_ARC, false);
        Vfx.cloud(ctx(), body, Particle.DUST, 3, 0.2, 0, FUR, false);
        long now = ctx().currentTick();
        if (feral < 2 || now - lastFeralMark < FERAL_MARK_TICKS) {
            return;
        }
        lastFeralMark = now;
        clawMarks(victim, owner.getLocation(), 0.7f, 2);
        Vfx.dot(body, Particle.FLASH, PALETTE.core(), false);
        Fx.sound(body, Sound.ENTITY_WOLF_GROWL, 0.5f, 0.8f + Fx.jitter(0.1f));
    }

    /** Blood motes flowing from the victim into the beast's chest. */
    private void feedVisual(LivingEntity victim) {
        Location to = Fx.body(player());
        if (!victim.getWorld().equals(to.getWorld())) {
            return;
        }
        Location from = Fx.body(victim);
        int motes = Vfx.n(ctx(), 3);
        for (int i = 0; i < motes; i++) {
            Vfx.trail(Vfx.scatter(from, 0.25), to, i % 2 == 0 ? BLOOD.getColor() : PALETTE.core(), 8 + i, false);
        }
    }

    // ------------------------------------------------------------------ bleeding

    /** One bleeding enemy: damage every {@code bleed_interval} until {@code endTick}. */
    private static final class Bleed {
        private final LivingEntity target;
        private long nextTick;
        private long endTick;

        Bleed(LivingEntity target, long nextTick, long endTick) {
            this.target = target;
            this.nextTick = nextTick;
            this.endTick = endTick;
        }
    }

    /** Starts (or refreshes, without stacking) a bleed on {@code target}. */
    private void bleed(LivingEntity target) {
        long duration = params().ticks("bleed_duration");
        long interval = Math.max(1, params().ticks("bleed_interval"));
        if (duration <= 0 || params().num("bleed_damage") <= 0 || !Combat.isTargetable(ctx(), player(), target)) {
            return;
        }
        long now = ctx().currentTick();
        Bleed existing = bleeds.get(target.getUniqueId());
        if (existing != null && existing.target.equals(target)) {
            existing.endTick = Math.max(existing.endTick, now + duration);
            return;
        }
        bleeds.put(target.getUniqueId(), new Bleed(target, now + interval, now + duration));
    }

    private void tickBleeds(long now) {
        long interval = Math.max(1, params().ticks("bleed_interval"));
        double damage = params().num("bleed_damage");
        Iterator<Bleed> it = bleeds.values().iterator();
        List<LivingEntity> due = new ArrayList<>();
        while (it.hasNext()) {
            Bleed bleed = it.next();
            LivingEntity target = bleed.target;
            if (!Combat.isTargetable(ctx(), player(), target) || now > bleed.endTick) {
                it.remove();
                continue;
            }
            if (now >= bleed.nextTick) {
                bleed.nextTick += interval;
                due.add(target);
                if (bleed.nextTick > bleed.endTick) {
                    it.remove();
                }
            } else if (now % 4 == 0) {
                drip(target);
            }
        }
        // Dealt after the walk: the hits run hooks that may start or refresh bleeds.
        for (LivingEntity target : due) {
            Combat.hit(ctx(), player(), target, damage);
            if (target.isValid()) {
                bleedTick(target);
            }
        }
    }

    /** A drop of blood falling from a bleeding enemy. */
    private void drip(LivingEntity target) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Location at = target.getLocation().add(random.nextDouble(-0.25, 0.25),
                target.getHeight() * random.nextDouble(0.35, 0.75), random.nextDouble(-0.25, 0.25));
        Vfx.dot(at, Particle.FALLING_DUST, BLOOD_DRIP, false);
    }

    /** A bleed tick: a spurt of blood and a wet sound. */
    private void bleedTick(LivingEntity target) {
        Location body = Fx.body(target);
        World world = body.getWorld();
        world.spawnParticle(Particle.DUST, body, Vfx.n(ctx(), 6), 0.22, 0.3, 0.22, 0, BLOOD);
        world.spawnParticle(Particle.BLOCK, body, Vfx.n(ctx(), 5), 0.15, 0.2, 0.15, 0.08, BLOOD_BLOCK);
        Vfx.dot(body.clone().add(0, 0.2, 0), Particle.FALLING_DUST, BLOOD_DRIP, false);
        Fx.sound(body, Sound.ENTITY_PLAYER_HURT_SWEET_BERRY_BUSH, 0.5f, 0.7f + Fx.jitter(0.1f));
    }

    // ------------------------------------------------------------------ active: claw leap

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        if (owner.isInsideVehicle()) {
            return ActivationResult.fail("탈것에 탄 채로는 도약할 수 없습니다");
        }
        if (leaping) {
            return ActivationResult.fail("이미 도약 중입니다");
        }
        Location start = owner.getLocation();
        double maxDistance = Math.max(0.5, params().num("leap_distance"));
        LivingEntity prey = ctx().findTarget(owner, maxDistance + AIM_SLACK);
        Vector flat;
        double distance;
        if (prey != null) {
            flat = prey.getLocation().toVector().subtract(start.toVector()).setY(0);
            distance = Math.min(maxDistance, Math.max(0, flat.length() - POUNCE_STOP_SHORT));
            flat = flat.lengthSquared() < 1.0E-6 ? Motion.horizontalFacing(start) : flat.normalize();
        } else {
            flat = Motion.horizontalFacing(start);
            distance = maxDistance;
        }
        double reach = Spots.arenaReach(ctx(), start, flat, distance);
        if (reach < distance) {
            // Shortened by the border: keep a margin, the landing still slides a little.
            distance = Math.max(0, reach - BORDER_MARGIN);
        }

        double up = Motion.launchSpeed(Math.max(0.3, params().num("leap_height")));
        long air = airTicks(up);
        double forward = Math.min(MAX_POUNCE_SPEED, distance * (1 - AIR_DRAG) / (1 - Math.pow(AIR_DRAG, air)));
        owner.setVelocity(Motion.clamp(flat.clone().multiply(forward).setY(up), MAX_VELOCITY));
        owner.setFallDistance(0);
        leaping = true;
        landing.arm(ctx().currentTick(), LANDING_GUARD_TICKS);

        takeOff(start, flat, distance);
        Pounce pounce = new Pounce(flat, STUCK_TICKS + lagTicks(owner));
        Loop.repeat(ctx(), owner, 1, 1, MAX_LEAP_TICKS, pounce::step, pounce::timeout);
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

    /** Take-off: dust kicked up at the feet, a growl, and a faint gold mark where the pounce will land. */
    private void takeOff(Location start, Vector flat, double distance) {
        World world = start.getWorld();
        Location feet = start.clone().add(0, 0.1, 0);
        Vfx.ring(ctx(), feet, 0.6, 10, ThreadLocalRandom.current().nextDouble(TAU), Particle.CLOUD, null, false);
        world.spawnParticle(Particle.DUST, feet, Vfx.n(ctx(), 10), 0.35, 0.1, 0.35, 0, FUR);
        BlockData ground = groundUnder(start);
        if (ground != null) {
            world.spawnParticle(Particle.BLOCK, feet, Vfx.n(ctx(), 10), 0.4, 0.05, 0.4, 0.1, ground);
        }
        Location target = start.clone().add(flat.clone().multiply(distance));
        RayTraceResult floor = world.rayTraceBlocks(target.clone().add(0, 1.5, 0), new Vector(0, -1, 0), 6,
                FluidCollisionMode.ALWAYS, true);
        if (floor != null) {
            Location mark = floor.getHitPosition().toLocation(world).add(0, 0.12, 0);
            Vfx.ring(ctx(), mark, Math.max(0.5, params().num("claw_radius")) * 0.6, 12, 0, Particle.DUST,
                    EYE_GLINT, false);
        }
        Fx.sound(start, Sound.ENTITY_WOLF_ANGRY_GROWL, 1.0f, 0.75f + Fx.jitter(0.08f));
        Fx.sound(start, Sound.ENTITY_GOAT_LONG_JUMP, 0.9f, 0.8f + Fx.jitter(0.05f));
        Fx.sound(start, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.5f, 0.6f);
    }

    /** One pounce in flight: claws whatever it crashes into, or the ground where it lands. */
    private final class Pounce {
        private final Vector flat;
        /** A leap the server never sees leave the ground claws in place after this (more on a slow connection). */
        private final long stuckAfter;
        private boolean airborne;
        private boolean done;

        Pounce(Vector flat, long stuckAfter) {
            this.flat = flat;
            this.stuckAfter = stuckAfter;
        }

        boolean step(long tick) {
            Player owner = player();
            owner.setFallDistance(0);
            Location at = owner.getLocation();
            streak(at, tick);
            BoundingBox reach = owner.getBoundingBox().expand(CONTACT_GAP);
            for (LivingEntity enemy : ctx().enemiesNear(owner, Fx.body(owner), 2.5)) {
                if (enemy.getBoundingBox().overlaps(reach)) {
                    finish(true);
                    return false;
                }
            }
            boolean grounded = Motion.isGrounded(owner) || owner.isInWater() || owner.isClimbing();
            if (!grounded) {
                airborne = true;
            } else if (airborne || tick >= stuckAfter) {
                finish(true);
                return false;
            }
            return true;
        }

        /** The leap took too long (it went over an edge): it ends without a claw. */
        void timeout() {
            finish(false);
        }

        private void finish(boolean claw) {
            if (done) {
                return;
            }
            done = true;
            leaping = false;
            Player owner = player();
            owner.setFallDistance(0);
            if (claw) {
                claw(owner, flat);
            }
        }
    }

    /** The pounce's trail: fur streaming off the body and two gold glints where the eyes passed. */
    private void streak(Location at, long tick) {
        Player owner = player();
        World world = at.getWorld();
        Location body = at.clone().add(0, owner.getHeight() * 0.5, 0);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, body, Vfx.n(ctx(), 3), 0.25, 0.35, 0.25, 0,
                PALETTE.fade(1.2f));
        world.spawnParticle(Particle.DUST, body, Vfx.n(ctx(), 2), 0.2, 0.3, 0.2, 0, FUR_DARK);
        if (tick % 2 == 0) {
            Vector back = Motion.horizontalFacing(at).multiply(-0.6);
            Vector side = new Vector(-back.getZ(), 0, back.getX()).normalize().multiply(0.12);
            Location eyes = at.clone().add(0, owner.getEyeHeight(), 0).add(back);
            Vfx.trail(eyes.clone().add(side), eyes.clone().add(side).add(back), PALETTE.core(), 4, false);
            Vfx.trail(eyes.clone().subtract(side), eyes.clone().subtract(side).add(back), PALETTE.core(), 4, false);
        }
    }

    /** The claw: every enemy around the owner is torn, bleeds and is shoved back a little. */
    private void claw(Player owner, Vector flat) {
        Location center = Fx.body(owner);
        double radius = Math.max(0.5, params().num("claw_radius"));
        double damage = params().num("claw_damage");
        int struck = 0;
        for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius)) {
            // Claws do not reach through walls.
            if (!Spots.clearPath(center, Fx.body(enemy))) {
                continue;
            }
            Combat.hit(ctx(), owner, enemy, damage);
            if (!Combat.isTargetable(ctx(), owner, enemy)) {
                continue;
            }
            struck++;
            bleed(enemy);
            Vector away = enemy.getLocation().toVector().subtract(owner.getLocation().toVector()).setY(0);
            if (away.lengthSquared() < 1.0E-4) {
                away = flat.clone();
            }
            Motion.push(enemy, away.normalize().multiply(CLAW_PUSH).setY(CLAW_LIFT), MAX_VELOCITY);
            clawMarks(enemy, owner.getLocation(), 1.0f, 3);
            Location body = Fx.body(enemy);
            World world = body.getWorld();
            world.spawnParticle(Particle.BLOCK, body, Vfx.n(ctx(), 14), 0.25, 0.35, 0.25, 0.15, BLOOD_BLOCK);
            world.spawnParticle(Particle.DUST, body, Vfx.n(ctx(), 8), 0.3, 0.4, 0.3, 0, BLOOD);
            world.spawnParticle(Particle.CRIT, body, Vfx.n(ctx(), 10), 0.3, 0.4, 0.3, 0.35);
            Vfx.dot(body, Particle.SWEEP_ATTACK, null, false);
        }
        clawSwipe(owner, flat, radius);
        if (struck > 0) {
            owner.sendActionBar(Text.mm("<" + GOLD + ">할퀴기!</" + GOLD + "> <gray>" + struck + "명 출혈</gray>"));
        }
    }

    /**
     * The swipe itself: three white claw blades rake across in front of the owner (block displays sweeping from one
     * side to the other), with three parallel arcs of white fading to gold, a puff of fur, torn-up ground and the
     * layered sound of claws and a snarl.
     */
    private void clawSwipe(Player owner, Vector flat, double radius) {
        Location at = owner.getLocation();
        float yaw = (float) Math.toDegrees(Math.atan2(-flat.getX(), flat.getZ()));
        double height = owner.getHeight();
        for (int i = 0; i < 3; i++) {
            Location level = at.clone().add(0, height * (0.35 + 0.18 * i), 0);
            Vfx.arc(ctx(), level, radius * (0.6 + 0.12 * i), yaw, 130, 14, Particle.DUST_COLOR_TRANSITION, CLAW_ARC,
                    false);
        }
        rake(owner, flat, yaw);
        Location body = Fx.body(owner);
        Vfx.cloud(ctx(), body, Particle.DUST, 6, 0.35, 0, FUR, false);
        BlockData ground = groundUnder(at);
        if (ground != null) {
            at.getWorld().spawnParticle(Particle.BLOCK, at.clone().add(0, 0.1, 0), Vfx.n(ctx(), 12), 0.5, 0.05, 0.5,
                    0.12, ground);
        }
        Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.75f + Fx.jitter(0.08f));
        Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.9f, 0.8f + Fx.jitter(0.08f));
        Fx.sound(body, Sound.ENTITY_RAVAGER_ATTACK, 0.7f, 1.2f + Fx.jitter(0.08f));
        Fx.sound(body, Sound.ENTITY_WOLF_BIG_GROWL, 0.8f, 0.9f + Fx.jitter(0.08f));
    }

    /**
     * Three long claw blades in front of the owner's chest, tilted to one side, that sweep across to the other side
     * in a few ticks and thin out: the shape of the swipe, readable from any angle.
     */
    private void rake(Player owner, Vector flat, float yaw) {
        long lifetime = RAKE_TICKS + RAKE_FADE_TICKS + 3;
        if (!reserveDisplays(3, lifetime)) {
            return;
        }
        Location at = Fx.body(owner).add(flat.clone().multiply(owner.getWidth() * 0.5 + 0.7));
        at.setYaw(0);
        at.setPitch(0);
        Vector side = new Vector(flat.getZ(), 0, -flat.getX());
        for (int i = -1; i <= 1; i++) {
            Vector offset = side.clone().multiply(i * 0.28).add(flat.clone().multiply(Math.abs(i) * -0.12));
            float length = RAKE_LENGTH * (i == 0 ? 1.0f : 0.85f);
            Transformation from = Displays.transform((float) offset.getX(), 0.15f, (float) offset.getZ(),
                    SLASH_WIDTH, length * 0.3f, 0.02f, yaw, 0, -RAKE_SWEEP);
            Transformation across = Displays.transform((float) offset.getX(), -0.1f, (float) offset.getZ(),
                    SLASH_WIDTH, length, 0.02f, yaw, 0, RAKE_SWEEP);
            Transformation gone = Displays.transform((float) offset.getX(), -0.3f, (float) offset.getZ(), 0.004f,
                    length * 1.05f, 0.004f, yaw, 0, RAKE_SWEEP + 10);
            BlockDisplay blade = Displays.block(ctx(), owner, at, SLASH, from, lifetime);
            Displays.animateNextTick(ctx(), owner, blade, across, RAKE_TICKS);
            ctx().runLater(owner, RAKE_TICKS + 1, () -> {
                Displays.animate(blade, gone, RAKE_FADE_TICKS);
                ctx().runLater(owner, RAKE_FADE_TICKS, () -> {
                    if (blade.isValid()) {
                        blade.remove();
                    }
                });
            });
        }
    }

    /**
     * {@code count} white slashes torn across the victim, facing the attacker: they are drawn in two ticks, hang a
     * moment and thin out. Falls back to particle lines when the display budget is spent.
     */
    private void clawMarks(LivingEntity victim, Location from, float size, int count) {
        Location chest = Fx.body(victim).add(0, victim.getHeight() * 0.1, 0);
        Vector toward = from.toVector().subtract(chest.toVector()).setY(0);
        if (toward.lengthSquared() < 1.0E-6) {
            toward = Motion.horizontalFacing(victim.getLocation());
        }
        toward.normalize();
        // Slightly in front of the victim, on the attacker's side.
        Location at = chest.clone().add(toward.clone().multiply(victim.getWidth() * 0.5 + 0.08));
        at.setYaw(0);
        at.setPitch(0);
        float yaw = (float) Math.toDegrees(Math.atan2(-toward.getX(), toward.getZ()));
        Vector side = new Vector(toward.getZ(), 0, -toward.getX());
        long lifetime = SLASH_DRAW_TICKS + SLASH_HOLD_TICKS + SLASH_FADE_TICKS + 2;
        double first = -(count - 1) / 2.0;
        if (!reserveDisplays(count, lifetime)) {
            for (int k = 0; k < count; k++) {
                Location mid = at.clone().add(side.clone().multiply((first + k) * SLASH_GAP * size));
                Location top = mid.clone().add(side.clone().multiply(0.25 * size)).add(0, 0.45 * size, 0);
                Location bottom = mid.clone().subtract(side.clone().multiply(0.25 * size)).subtract(0, 0.45 * size, 0);
                Vfx.line(ctx(), top, bottom, 8, Particle.DUST_COLOR_TRANSITION, CLAW_ARC, false);
            }
            return;
        }
        Player owner = player();
        float roll = 28f;
        for (int k = 0; k < count; k++) {
            double slot = first + k;
            Vector offset = side.clone().multiply(slot * SLASH_GAP * size);
            float length = SLASH_LENGTH * size * (Math.abs(slot) < 0.6 ? 1.0f : 0.85f);
            Transformation start = Displays.transform((float) offset.getX(), (float) offset.getY(),
                    (float) offset.getZ(), SLASH_WIDTH, 0.04f, 0.02f, yaw, 0, roll);
            Transformation drawn = Displays.transform((float) offset.getX(), (float) offset.getY(),
                    (float) offset.getZ(), SLASH_WIDTH, length, 0.02f, yaw, 0, roll);
            Transformation thin = Displays.transform((float) offset.getX(), (float) offset.getY() - 0.1f,
                    (float) offset.getZ(), 0.005f, length * 1.05f, 0.005f, yaw, 0, roll);
            BlockDisplay slash = Displays.block(ctx(), owner, at, SLASH, start, lifetime);
            Displays.animateNextTick(ctx(), owner, slash, drawn, SLASH_DRAW_TICKS);
            ctx().runLater(owner, SLASH_DRAW_TICKS + SLASH_HOLD_TICKS, () -> {
                Displays.animate(slash, thin, SLASH_FADE_TICKS);
                ctx().runLater(owner, SLASH_FADE_TICKS, () -> {
                    if (slash.isValid()) {
                        slash.remove();
                    }
                });
            });
        }
    }

    // ------------------------------------------------------------------ ultimate: beast form

    @Override
    public ActivationResult onUltimate() {
        if (beast != null) {
            return ActivationResult.fail("이미 야수화한 상태입니다");
        }
        Player owner = player();
        Beast form = new Beast(Body.scaleOf(owner));
        beast = form;
        form.begin();
        return ActivationResult.success();
    }

    /** One beast transformation: grows, howls, hunts, then shrinks back and cleans up. */
    private final class Beast {
        private final double baseScale;
        private final double growth;
        private final List<NamespacedKey> buffs = new ArrayList<>();
        private @Nullable NamespacedKey scaleKey;
        private @Nullable TextDisplay moon;
        private int step;
        private boolean blocked;
        private long endTick;

        Beast(double baseScale) {
            this.baseScale = baseScale;
            this.growth = Math.max(0, params().num("ult_scale") * baseScale - baseScale);
        }

        void begin() {
            Player owner = player();
            long duration = Math.max(1, params().ticks("ult_duration"));
            endTick = ctx().currentTick() + duration;
            double speed = params().num("ult_speed") / 100.0;
            if (Math.abs(speed) > 1.0E-6) {
                buffs.add(ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, Math.max(-0.9, speed),
                        Operation.MULTIPLY_SCALAR_1, 0));
            }
            updateFeral(false);

            Location at = owner.getLocation();
            transformBurst(owner, at);
            howl(owner, at);
            moon = spawnMoon(owner, duration);
            Loop.repeat(ctx(), owner, 0, 1, duration, this::tick, this::shrink);
        }

        private boolean tick(long tick) {
            Player owner = player();
            if (tick % GROW_INTERVAL == 0 && step < GROW_STEPS && growth > 0 && !blocked) {
                grow(owner);
            }
            followMoon(owner, tick);
            if (tick % 3 == 0) {
                furAura(owner, tick);
            }
            if (tick % 5 == 0) {
                long left = Math.max(0, endTick - ctx().currentTick());
                owner.sendActionBar(Text.mm("<" + GOLD + ">☾ 야수화</" + GOLD + "> <gray>" + Fx.seconds(left)
                        + "초</gray>"));
            }
            if (tick % 50 == 25) {
                Fx.sound(owner.getLocation(), Sound.ENTITY_WOLF_BIG_GROWL, 0.6f, 0.6f + Fx.jitter(0.05f));
            }
            return true;
        }

        private void grow(Player owner) {
            int next = step + 1;
            double nextScale = baseScale + growth * next / GROW_STEPS;
            if (!hasHeadroom(owner, nextScale) || !Spots.isFree(owner.getWorld(), owner.getLocation(),
                    Body.PLAYER.width() * nextScale, Body.PLAYER.height() * nextScale)) {
                blocked = true;
                return;
            }
            setStep(owner, next);
            Location feet = owner.getLocation().add(0, 0.1, 0);
            Vfx.ring(ctx(), feet, owner.getWidth() * 0.7, 10, next * 0.6, Particle.DUST, FUR, true);
            Fx.sound(feet, Sound.ENTITY_WOLF_BIG_GROWL, 0.7f, 0.6f + next * 0.05f);
        }

        private void setStep(Player owner, int value) {
            NamespacedKey previous = scaleKey;
            if (previous != null) {
                ctx().removeModifier(owner, previous);
                scaleKey = null;
            }
            step = value;
            if (value > 0) {
                scaleKey = ctx().addModifier(owner, owner, Attribute.SCALE, growth * value / GROW_STEPS,
                        Operation.ADD_NUMBER, 0);
            }
        }

        private void followMoon(Player owner, long tick) {
            TextDisplay display = moon;
            if (display == null) {
                return;
            }
            if (!display.isValid() || !display.getWorld().equals(owner.getWorld())) {
                moon = null;
                return;
            }
            Location above = owner.getLocation().add(0, owner.getHeight() + MOON_HEIGHT, 0);
            above.setYaw(0);
            above.setPitch(0);
            display.teleport(above);
            if (tick % 4 == 0) {
                Location halo = above.clone().add(0, 0.45, 0);
                Vfx.ring(ctx(), halo, 0.75, 10, tick * 0.08, Particle.DUST, MOON_HALO, true);
                if (tick % 8 == 0) {
                    Vfx.dot(Vfx.scatter(halo, 0.7), Particle.END_ROD, null, false);
                }
            }
        }

        /** Fur swirling around the beast and the odd gold glint. */
        private void furAura(Player owner, long tick) {
            Location base = owner.getLocation();
            double radius = owner.getWidth() * 0.75;
            double height = owner.getHeight();
            for (int i = 0; i < 2; i++) {
                double angle = tick * 0.35 + i * Math.PI;
                double y = height * (0.2 + 0.6 * ((tick * 0.07 + i * 0.5) % 1.0));
                Vfx.dot(base.clone().add(Math.cos(angle) * radius, y, Math.sin(angle) * radius), Particle.DUST,
                        i == 0 ? FUR : FUR_DARK, false);
            }
            if (tick % 9 == 0) {
                Vfx.dot(base.clone().add(0, height * 0.9, 0).add(Motion.horizontalFacing(base).multiply(-0.4)),
                        Particle.DUST, EYE_GLINT, false);
            }
        }

        private void shrink() {
            Player owner = player();
            Fx.sound(owner.getLocation(), Sound.ENTITY_WOLF_WHINE, 0.8f, 0.6f + Fx.jitter(0.05f));
            Fx.sound(owner.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 0.6f, 0.8f);
            TextDisplay display = moon;
            moon = null;
            if (display != null && display.isValid()) {
                Displays.animate(display, Displays.centered(0, 0, 0, 0.01f, 0.01f, 0.01f, 0, 0, 0), 8);
                ctx().runLater(owner, 8, () -> {
                    if (display.isValid()) {
                        display.remove();
                    }
                });
            }
            Loop.repeat(ctx(), owner, 0, GROW_INTERVAL, Math.max(1, step), tick -> {
                setStep(owner, Math.max(0, step - 1));
                Vfx.cloud(ctx(), Fx.body(owner), Particle.DUST, 5, 0.4, 0, FUR, false);
                return step > 0;
            }, this::end);
        }

        private void end() {
            Player owner = player();
            setStep(owner, 0);
            for (NamespacedKey key : buffs) {
                ctx().removeModifier(owner, key);
            }
            buffs.clear();
            if (beast == this) {
                beast = null;
            }
            updateFeral(false);
            Location body = Fx.body(owner);
            Vfx.cloud(ctx(), body, Particle.DUST_COLOR_TRANSITION, 10, 0.4, 0, PALETTE.fade(1.1f), false);
            Vfx.ring(ctx(), owner.getLocation().add(0, 0.1, 0), 0.8, 10, 0, Particle.DUST, FUR_DARK, false);
        }
    }

    /**
     * The transformation: a spiral of fur bursts up around the owner, a golden flash where its eyes ignite, and a
     * thunder of growls.
     */
    private void transformBurst(Player owner, Location at) {
        World world = at.getWorld();
        Location body = at.clone().add(0, owner.getHeight() * 0.55, 0);
        world.spawnParticle(Particle.FLASH, body, 1, 0, 0, 0, 0, PALETTE.core(), true);
        Vfx.helix(ctx(), at, 0.9, owner.getHeight() * 1.5, 2, 3, 14, 0, Particle.DUST, FUR, true);
        Vfx.helix(ctx(), at, 0.65, owner.getHeight() * 1.3, 2, 2, 10, Math.PI / 3, Particle.DUST_COLOR_TRANSITION,
                PALETTE.glow(1.0f), true);
        Vfx.cloud(ctx(), body, Particle.DUST, 18, 0.6, 0, FUR_DARK, true);
        BlockData ground = groundUnder(at);
        if (ground != null) {
            Vfx.ring(ctx(), at.clone().add(0, 0.1, 0), 1.0, 8, 0, Particle.DUST_PILLAR, ground, true);
        }
        furTufts(owner, body);
        Fx.sound(at, Sound.ENTITY_WOLF_BIG_GROWL, 1.4f, 0.55f);
        Fx.sound(at, Sound.ENTITY_RAVAGER_ROAR, 0.8f, 1.2f + Fx.jitter(0.05f));
        Fx.sound(at, Sound.ITEM_ARMOR_EQUIP_LEATHER, 1.0f, 0.6f);
    }

    /** Tufts of fur (small woolly block displays) burst off the changing body, tumble outwards and fall away. */
    private void furTufts(Player owner, Location body) {
        long lifetime = TUFT_FLIGHT_TICKS + 8;
        if (!reserveDisplays(FUR_TUFTS, lifetime)) {
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Location at = body.clone();
        at.setYaw(0);
        at.setPitch(0);
        double phase = random.nextDouble(TAU);
        for (int i = 0; i < FUR_TUFTS; i++) {
            double angle = phase + i * TAU / FUR_TUFTS + random.nextDouble(-0.25, 0.25);
            float size = (float) random.nextDouble(0.16, 0.26);
            BlockDisplay tuft = Displays.block(ctx(), owner, at, FUR_BLOCKS[i % FUR_BLOCKS.length],
                    Displays.scaled(size * 0.4f), lifetime);
            tuft.setBrightness(null);
            double reach = random.nextDouble(1.3, 2.1);
            float spin = (float) random.nextDouble(180, 400);
            Transformation out = Displays.transform((float) (Math.cos(angle) * reach), (float) random.nextDouble(-0.4,
                    0.6), (float) (Math.sin(angle) * reach), size, size * 0.7f, size, spin, spin * 0.5f, 0);
            Displays.animateNextTick(ctx(), owner, tuft, out, TUFT_FLIGHT_TICKS);
            ctx().runLater(owner, TUFT_FLIGHT_TICKS + 1, () -> {
                if (tuft.isValid()) {
                    Displays.shrinkAway(ctx(), owner, tuft, 6);
                }
            });
        }
    }

    /**
     * The howl: golden rings roll out from the beast's head to the howl radius; every enemy inside flinches away and
     * crawls in fear for a moment, wrapped in a cold, dark swirl.
     */
    private void howl(Player owner, Location at) {
        double radius = Math.max(0.5, params().num("howl_radius"));
        long fear = params().ticks("howl_duration");
        double slow = Math.clamp(params().num("howl_slow") / 100.0, 0.0, 1.0);
        Fx.sound(at, Sound.ITEM_GOAT_HORN_SOUND_6, 1.6f, 0.75f);
        Fx.sound(at, Sound.ENTITY_WOLF_BIG_GROWL, 1.2f, 0.5f);
        Fx.sound(at, Sound.ENTITY_WARDEN_ROAR, 0.4f, 1.6f + Fx.jitter(0.05f));
        Location head = at.clone().add(0, owner.getEyeHeight(), 0);
        Loop.repeat(ctx(), owner, 0, 1, HOWL_TICKS, tick -> {
            double t = (double) (tick + 1) / HOWL_TICKS;
            double r = radius * (1 - (1 - t) * (1 - t));
            int points = (int) Math.max(12, Math.min(48, r * 5));
            Vfx.ring(ctx(), head, r, points, tick * 0.2, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.2f), true);
            Vfx.ring(ctx(), at.clone().add(0, 0.2, 0), r * 0.95, points / 2, -tick * 0.2, Particle.DUST,
                    PALETTE.dust(1.3f), true);
            return true;
        });
        int scared = 0;
        for (LivingEntity enemy : ctx().enemiesNear(owner, at, radius)) {
            if (!Combat.isTargetable(ctx(), owner, enemy)) {
                continue;
            }
            scared++;
            if (fear > 0 && slow > 0) {
                ctx().addModifier(owner, enemy, Attribute.MOVEMENT_SPEED, -slow, Operation.MULTIPLY_SCALAR_1, fear);
            }
            Vector away = enemy.getLocation().toVector().subtract(at.toVector()).setY(0);
            if (away.lengthSquared() > 1.0E-4) {
                Motion.push(enemy, away.normalize().multiply(FLINCH_PUSH).setY(0.15), MAX_VELOCITY);
            }
            Vfx.trail(head, Fx.body(enemy), PALETTE.core(), 6, true);
            dread(enemy, fear);
        }
        if (scared > 0) {
            owner.sendActionBar(Text.mm("<" + GOLD + ">울부짖음!</" + GOLD + "> <gray>" + scared + "명 공포</gray>"));
        }
    }

    /** A frightened enemy: a cold, dark swirl at its feet and shivering smoke while the fear lasts. */
    private void dread(LivingEntity enemy, long ticks) {
        Loop.repeat(ctx(), player(), 2, 3, Math.max(1, ticks / 3), run -> {
            if (!enemy.isValid() || enemy.isDead()) {
                return false;
            }
            Location feet = enemy.getLocation().add(0, 0.15, 0);
            Vfx.ring(ctx(), feet, 0.55, 8, run * 0.5, Particle.DUST, DREAD, false);
            if (run % 2 == 0) {
                Vfx.dot(feet.clone().add(Fx.jitter(0.3f), enemy.getHeight() * 0.5, Fx.jitter(0.3f)), Particle.SMOKE,
                        null, false);
            }
            return true;
        });
    }

    /** A pale full moon (a text display disk) that hangs over the beast while the form lasts. */
    private @Nullable TextDisplay spawnMoon(Player owner, long duration) {
        if (!reserveDisplays(1, duration + 10)) {
            return null;
        }
        Location above = owner.getLocation().add(0, owner.getHeight() + MOON_HEIGHT, 0);
        above.setYaw(0);
        above.setPitch(0);
        TextDisplay display = Displays.spawn(ctx(), owner, above, TextDisplay.class, duration + 10, d -> {
            d.text(Component.text("●", MOON_COLOR));
            d.setBillboard(Display.Billboard.CENTER);
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            d.setShadowed(false);
            d.setTransformation(Displays.centered(0, 0, 0, 0.01f, 0.01f, 0.01f, 0, 0, 0));
        });
        Displays.animateNextTick(ctx(), owner, display,
                Displays.centered(0, 0, 0, MOON_SCALE, MOON_SCALE, MOON_SCALE, 0, 0, 0), 10);
        return display;
    }

    // ------------------------------------------------------------------ helpers

    /** Ticks the server sees the owner's movement late: one round trip, capped (ping can read 0 after joining). */
    private static long lagTicks(Player player) {
        return Math.min(MAX_LAG_TICKS, (long) Math.ceil(Math.max(0, player.getPing()) / 50.0));
    }

    /** True if a player at its current position has room above its head for a body of {@code scale}. */
    private static boolean hasHeadroom(Player player, double scale) {
        Location at = player.getLocation();
        BoundingBox column = new BoundingBox(at.getX() - HEADROOM_HALF_WIDTH, at.getY() + 0.1,
                at.getZ() - HEADROOM_HALF_WIDTH, at.getX() + HEADROOM_HALF_WIDTH,
                at.getY() + STANDING_HEIGHT * scale + 0.05, at.getZ() + HEADROOM_HALF_WIDTH);
        return !player.getWorld().hasCollisionsIn(column);
    }

    /** The solid block right under {@code at}, null over air, fluids and plants. */
    private static @Nullable BlockData groundUnder(Location at) {
        BlockData ground = at.clone().subtract(0, 0.2, 0).getBlock().getBlockData();
        return ground.getMaterial().isSolid() ? ground : null;
    }

    /** Claims {@code count} display entities for {@code lifetime} ticks if that stays within {@link #MAX_DISPLAYS}. */
    private boolean reserveDisplays(int count, long lifetime) {
        if (count <= 0 || liveDisplays + count > MAX_DISPLAYS) {
            return false;
        }
        liveDisplays += count;
        ctx().runLater(player(), Math.max(1, lifetime), () -> liveDisplays -= count);
        return true;
    }
}
