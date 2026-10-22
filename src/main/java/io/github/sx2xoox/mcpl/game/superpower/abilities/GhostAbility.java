package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Motion;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Stealth;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.block.data.BlockData;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 유령 — a restless spirit. Blades pass half through its ethereal body, its wail drives enemies away in terror
 * (unable to land a blow), and as the ultimate it becomes a true ghost for a moment: untouchable, unseen and swift,
 * until it bursts back into the world with a scream that hurts and slows everyone around it.
 *
 * <p>Look: pale white and frost blue. A melee hit leaves a translucent afterimage of the ghost drifting off its body.
 * 공포 sends a screaming specter (a glass shroud and head with black eyes and a gaping mouth) lunging forward through
 * the cone on rings of pale wailing; terrified enemies shake under a trembling "!" and drip cold sweat while they are
 * driven away. 유령화 dissolves the ghost into glass shards and mist; while it walks unseen only a faint chill of
 * mist marks its steps, and at the end a ring of frost gathers in before the specter screams out of its body.
 */
public final class GhostAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("ghost", "유령", Material.GHAST_TEAR)
            .color(TextColor.color(0xA9CBFF))
            .difficulty(2)
            .passive("영체",
                    "반쯤 투명한 영체라 받는 근접 피해가 {ethereal_reduction}% 줄어듭니다. 칼날이 몸을 꿰뚫으면 흐릿한 "
                            + "잔상이 흩어집니다.")
            .active("공포",
                    "소름 끼치는 비명을 질러 바라보는 방향 {fear_range}블록 원뿔 안의 적에게 {fear_damage} 피해를 주고 "
                            + "{fear_duration}초간 공포에 빠뜨립니다. 공포에 빠진 적은 나에게서 멀어지도록 떠밀리고 이동 속도가 "
                            + "{fear_slow}% 느려지며, 근접 공격으로 피해를 주지 못합니다.",
                    11)
            .ultimate("유령화",
                    "{phase_duration}초간 유령이 되어 어떤 피해도 받지 않고 적에게 보이지 않으며 이동 속도가 "
                            + "{phase_speed}% 빨라집니다. 그동안은 공격할 수 없고, 유령화가 끝나면 비명과 함께 나타나 주변 "
                            + "{burst_radius}블록의 적에게 {burst_damage} 피해를 주고 {burst_slow}초간 구속 II를 겁니다.")
            // Invulnerability, the attack lock and the stealth live in this instance's hooks and onRevoke.
            .ultimateNotStandalone()
            .param("ethereal_reduction", 25)
            .param("fear_range", 8)
            .param("fear_angle", 70)
            .param("fear_damage", 2)
            .param("fear_duration", 2)
            .param("fear_slow", 30)
            .param("phase_duration", 3)
            .param("phase_speed", 40)
            .param("burst_radius", 4)
            .param("burst_damage", 5)
            .param("burst_slow", 1.5)
            .build();

    private static final String PALE = "#A9CBFF";
    private static final double TAU = Math.PI * 2;

    // ------------------------------------------------------------------ look
    /** Moonlight white glow, frost blue body, the dusk blue of a cold night. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xF4FAFF, 0xA9CBFF, 0x3A4F7A);
    private static final Particle.DustTransition WISP = PALETTE.glow(0.8f);
    private static final Particle.DustTransition CHILL = PALETTE.fade(1.0f);
    private static final Particle.DustOptions MIST = new Particle.DustOptions(Color.fromRGB(0xDDE8F7), 1.4f);

    /** Block types of the visuals; their block data is created on first use (it needs a running server). */
    private static final Material SHROUD_BLOCK = Material.WHITE_STAINED_GLASS;
    private static final Material FACE_BLOCK = Material.BLACK_CONCRETE;
    private static final Material SHARD_BLOCK = Material.LIGHT_BLUE_STAINED_GLASS;

    private static final double MAX_SPEED = 1.5;

    // passive
    private static final long AFTERIMAGE_COOLDOWN = 12;
    private static final long WISP_PERIOD = 25;
    private static final double AFTERIMAGE_DRIFT = 0.45;
    private static final int AFTERIMAGE_FADE_TICKS = 8;

    // active: terror
    /** Enemies this close to the ghost are always caught, whatever the angle. */
    private static final double POINT_BLANK = 1.3;
    private static final long FLEE_PERIOD = 3;
    private static final double FLEE_SPEED = 0.38;
    private static final double FLEE_HOP = 0.12;
    private static final int SPECTER_LUNGE_TICKS = 6;
    private static final double SPECTER_LUNGE = 3.2;
    private static final int SPECTER_FADE_TICKS = 6;
    private static final int WAIL_RINGS = 4;

    // ultimate: phase
    private static final double AGGRO_DROP_RANGE = 24;
    private static final long BURST_TELEGRAPH_TICKS = 12;
    private static final int SHARDS = 6;
    private static final int SHARD_FLIGHT_TICKS = 8;
    private static final long LOCK_MESSAGE_COOLDOWN = 20;
    private static final int BURST_SLOW_AMPLIFIER = 1;

    private final Stealth stealth = new Stealth();
    private final Map<UUID, Fear> feared = new HashMap<>();
    private boolean phasing;
    private long phaseEnd;
    private @Nullable NamespacedKey phaseSpeed;
    private long lastAfterimage = Long.MIN_VALUE / 2; // "/ 2": now - this must not overflow
    private long lastLockMessage = Long.MIN_VALUE / 2;

    /** One terrified enemy: until when, its debuff modifiers and the trembling "!" over its head. */
    private static final class Fear {
        long until;
        final List<NamespacedKey> modifiers = new ArrayList<>();
        @Nullable TextDisplay mark;
    }

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        endPhase(false);
        // The fear modifiers are timed and expire on their own; only forget them.
        feared.clear();
    }

    // ------------------------------------------------------------------ passive: ethereal body

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (phasing) {
            if (!isInescapable(event)) {
                event.setCancelled(true);
            }
            return;
        }
        if (!(event instanceof EntityDamageByEntityEvent byEntity) || ctx().isAbilityDamage() || !isMelee(event)) {
            return;
        }
        Entity damager = byEntity.getDamager();
        Fear fear = feared.get(damager.getUniqueId());
        if (fear != null && fear.until > ctx().currentTick()) {
            // Too terrified to strike: the blow never lands.
            event.setCancelled(true);
            Vfx.cloud(ctx(), Fx.body(damager), Particle.DUST, 4, 0.25, 0, MIST, false);
            return;
        }
        double reduction = Math.clamp(params().num("ethereal_reduction") / 100.0, 0.0, 1.0);
        if (reduction > 0 && event.getDamage() > 0) {
            event.setDamage(event.getDamage() * (1 - reduction));
            afterimage(damager);
        }
    }

    private static boolean isMelee(EntityDamageEvent event) {
        DamageCause cause = event.getCause();
        return cause == DamageCause.ENTITY_ATTACK || cause == DamageCause.ENTITY_SWEEP_ATTACK;
    }

    /** Falling out of the world or being killed by command cannot be avoided. */
    private static boolean isInescapable(EntityDamageEvent event) {
        DamageCause cause = event.getCause();
        if (cause == DamageCause.VOID || cause == DamageCause.KILL || cause == DamageCause.SUICIDE) {
            return true;
        }
        DamageType type = event.getDamageSource().getDamageType();
        return type == DamageType.OUT_OF_WORLD || type == DamageType.GENERIC_KILL;
    }

    /** The blade passes through: a translucent afterimage of the ghost drifts off away from the attacker. */
    private void afterimage(Entity attacker) {
        long now = ctx().currentTick();
        if (now - lastAfterimage < AFTERIMAGE_COOLDOWN) {
            return;
        }
        lastAfterimage = now;
        Player owner = player();
        Vector away = Motion.horizontal(owner.getLocation().toVector().subtract(attacker.getLocation().toVector()));
        if (away.lengthSquared() < 1.0E-6) {
            away = Motion.horizontalFacing(owner.getLocation()).multiply(-1);
        }
        Location at = owner.getLocation();
        at.setYaw(owner.getBodyYaw());
        at.setPitch(0);
        BlockData glass = SHROUD_BLOCK.createBlockData();
        float scale = (float) Math.max(0.3, owner.getHeight() / 1.8);
        List<BlockDisplay> image = List.of(
                Displays.block(ctx(), owner, at, glass, box(-0.27f, 0.05f, -0.15f, 0.54f, 1.3f, 0.3f, scale),
                        AFTERIMAGE_FADE_TICKS + 6),
                Displays.block(ctx(), owner, at, glass, box(-0.24f, 1.35f, -0.24f, 0.48f, 0.48f, 0.48f, scale),
                        AFTERIMAGE_FADE_TICKS + 6));
        Location drifted = at.clone().add(away.clone().multiply(AFTERIMAGE_DRIFT));
        for (BlockDisplay part : image) {
            part.setTeleportDuration(AFTERIMAGE_FADE_TICKS);
            ctx().runLater(owner, 1, () -> {
                if (part.isValid()) {
                    part.teleport(drifted);
                    Displays.shrinkAway(ctx(), owner, part, AFTERIMAGE_FADE_TICKS);
                }
            });
        }
        // Mist streams through the body where the blade passed.
        Location chest = Fx.body(owner);
        Vfx.line(ctx(), chest.clone().subtract(away.clone().multiply(0.6)), chest.clone().add(away.clone().multiply(0.9)),
                6, Particle.DUST_COLOR_TRANSITION, WISP, false);
        Vfx.cloud(ctx(), chest.clone().add(away.clone().multiply(0.4)), Particle.WHITE_SMOKE, 4, 0.15, 0.02, null,
                false);
        Fx.sound(chest, Sound.ENTITY_VEX_HURT, 0.5f, 0.6f + Fx.jitter(0.08f));
        Fx.sound(chest, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.5f, 1.6f + Fx.jitter(0.1f));
    }

    @Override
    public void onTick(long tick) {
        Player owner = player();
        if (phasing) {
            tickPhase(owner, tick);
            return;
        }
        if (tick % WISP_PERIOD == 0) {
            // A pale wisp now and then drifts off the ghost's shoulders.
            ThreadLocalRandom random = ThreadLocalRandom.current();
            Location at = owner.getLocation().add(random.nextDouble(-0.3, 0.3), owner.getHeight() * 0.85,
                    random.nextDouble(-0.3, 0.3));
            Vfx.dot(at, Particle.DUST_COLOR_TRANSITION, WISP, false);
            at.getWorld().spawnParticle(Particle.WHITE_ASH, at, 0, 0, 0.02, 0, 1);
        }
        if (tick % 4 == 0 && owner.isSprinting()) {
            Vfx.dot(owner.getLocation().add(0, 0.15, 0), Particle.DUST, MIST, false);
        }
    }

    // ------------------------------------------------------------------ active: terror

    @Override
    public ActivationResult onActive() {
        if (phasing) {
            return ActivationResult.fail("유령화 중에는 공격할 수 없습니다");
        }
        Player owner = player();
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection();
        if (direction.lengthSquared() < 1.0E-6) {
            direction = new Vector(0, 0, 1);
        }
        direction.normalize();
        double range = Math.max(1, params().num("fear_range"));
        double cosHalf = Math.cos(Math.toRadians(Math.clamp(params().num("fear_angle"), 5, 170) / 2));
        double damage = params().num("fear_damage");
        long duration = Math.max(1, params().ticks("fear_duration"));

        wail(owner, eye, direction, range);
        int terrified = 0;
        for (LivingEntity enemy : ctx().enemiesNear(owner, eye, range)) {
            Location body = Fx.body(enemy);
            Vector to = body.toVector().subtract(eye.toVector());
            double distance = to.length();
            if (distance > POINT_BLANK && to.dot(direction) / distance < cosHalf) {
                continue;
            }
            if (!Spots.clearPath(eye, body)) {
                continue;
            }
            Combat.hit(ctx(), owner, enemy, damage);
            if (Combat.isTargetable(ctx(), owner, enemy)) {
                terrify(enemy, duration);
                terrified++;
            }
        }
        if (terrified > 0) {
            owner.sendActionBar(Text.mm("<" + PALE + ">☁ 공포</" + PALE + "> <white>" + terrified
                    + "명이 겁에 질렸습니다</white>"));
        }
        return ActivationResult.success();
    }

    /** Applies (or refreshes) the terror on {@code enemy}. */
    private void terrify(LivingEntity enemy, long duration) {
        Player owner = player();
        Fear fear = feared.computeIfAbsent(enemy.getUniqueId(), id -> new Fear());
        for (NamespacedKey key : fear.modifiers) {
            ctx().removeModifier(enemy, key);
        }
        fear.modifiers.clear();
        if (fear.mark != null && fear.mark.isValid()) {
            fear.mark.remove();
        }
        long now = ctx().currentTick();
        fear.until = now + duration;
        // No blow lands: the attack damage drops to nothing while the terror lasts.
        fear.modifiers.add(ctx().addModifier(owner, enemy, Attribute.ATTACK_DAMAGE, -1.0, Operation.MULTIPLY_SCALAR_1,
                duration));
        double slow = Math.clamp(params().num("fear_slow") / 100.0, 0.0, 0.95);
        if (slow > 0) {
            fear.modifiers.add(ctx().addModifier(owner, enemy, Attribute.MOVEMENT_SPEED, -slow,
                    Operation.MULTIPLY_SCALAR_1, duration));
        }
        TextDisplay mark = Displays.text(ctx(), owner, overHead(enemy), Text.mm("<b><" + PALE + ">!</" + PALE + "></b>"),
                0.4f, duration + 2);
        mark.setTeleportDuration(1);
        Displays.animateNextTick(ctx(), owner, mark, Displays.centered(0, 0, 0, 1.6f, 1.6f, 1.6f, 0, 0, 0), 3);
        fear.mark = mark;

        Location body = Fx.body(enemy);
        Vfx.cloud(ctx(), body, Particle.DUST_COLOR_TRANSITION, 10, 0.3, 0, CHILL, false);
        Vfx.cloud(ctx(), body, Particle.SOUL, 3, 0.25, 0.02, null, false);
        Fx.sound(body, Sound.ENTITY_VEX_CHARGE, 0.7f, 0.7f + Fx.jitter(0.08f));
        Fx.sound(body, Sound.ENTITY_SKELETON_AMBIENT, 0.4f, 1.8f + Fx.jitter(0.1f));
        long token = fear.until;
        Loop.repeat(ctx(), owner, 1, 1, duration, run -> flee(enemy, fear, token, run), () -> calm(enemy, fear, token));
    }

    /** One tick of terror: the enemy trembles, and is driven away from the ghost every few ticks. */
    private boolean flee(LivingEntity enemy, Fear fear, long token, long run) {
        if (fear.until != token || !Combat.isTargetable(ctx(), player(), enemy)) {
            return false;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        TextDisplay mark = fear.mark;
        if (mark != null && mark.isValid()) {
            mark.teleport(overHead(enemy).add(random.nextDouble(-0.07, 0.07), random.nextDouble(-0.05, 0.05),
                    random.nextDouble(-0.07, 0.07)));
        }
        if (run % FLEE_PERIOD == 0) {
            Vector away = Motion.horizontal(enemy.getLocation().toVector().subtract(player().getLocation().toVector()));
            if (away.lengthSquared() < 1.0E-6) {
                away = Motion.horizontalFacing(player().getLocation());
            }
            boolean grounded = Motion.isGrounded(enemy) || enemy.isInWater();
            double y = grounded ? FLEE_HOP : Math.min(0, enemy.getVelocity().getY());
            Motion.push(enemy, away.multiply(FLEE_SPEED).setY(y), MAX_SPEED);
            Location head = enemy.getLocation().add(0, enemy.getHeight() * 0.9, 0);
            Vfx.dot(head.clone().add(random.nextDouble(-0.25, 0.25), 0, random.nextDouble(-0.25, 0.25)),
                    Particle.FALLING_WATER, null, false);
            Vfx.dot(Fx.body(enemy).add(random.nextDouble(-0.3, 0.3), random.nextDouble(-0.3, 0.3),
                    random.nextDouble(-0.3, 0.3)), Particle.DUST_COLOR_TRANSITION, CHILL, false);
        }
        if (run % 8 == 4) {
            Fx.sound(enemy.getLocation(), Sound.ENTITY_SKELETON_STEP, 0.3f, 1.9f + Fx.jitter(0.1f));
        }
        return true;
    }

    /** The terror wears off: the "!" pops and the enemy may fight again. */
    private void calm(LivingEntity enemy, Fear fear, long token) {
        if (fear.until != token) {
            return;
        }
        feared.remove(enemy.getUniqueId());
        TextDisplay mark = fear.mark;
        if (mark != null && mark.isValid()) {
            Displays.shrinkAway(ctx(), player(), mark, 3);
        }
    }

    private static Location overHead(LivingEntity enemy) {
        return enemy.getLocation().add(0, enemy.getHeight() + 0.55, 0);
    }

    /** The wail: a screaming specter lunges through the cone on expanding rings of pale sound. */
    private void wail(Player owner, Location eye, Vector direction, double range) {
        Location mouth = eye.clone().add(direction.clone().multiply(0.8)).subtract(0, 0.35, 0);
        Specter specter = new Specter(owner, mouth, direction, 0.55f, SPECTER_LUNGE_TICKS + SPECTER_FADE_TICKS + 6);
        specter.lunge(direction.clone().multiply(SPECTER_LUNGE), 1.3f, SPECTER_LUNGE_TICKS);
        ctx().runLater(owner, SPECTER_LUNGE_TICKS, () -> specter.fade(SPECTER_FADE_TICKS));

        Vector side = direction.clone().crossProduct(new Vector(0, 1, 0));
        Vector right = side.lengthSquared() < 1.0E-6 ? new Vector(1, 0, 0) : side.normalize();
        Vector up = right.clone().crossProduct(direction).normalize();
        double half = Math.toRadians(Math.clamp(params().num("fear_angle"), 5, 170) / 2);
        Loop.repeat(ctx(), owner, 0, 1, WAIL_RINGS, run -> {
            double distance = range * (run + 1) / WAIL_RINGS;
            double radius = Math.max(0.4, Math.tan(half) * distance * 0.8);
            Location center = mouth.clone().add(direction.clone().multiply(distance));
            int points = Vfx.n(ctx(), (int) Math.max(10, radius * 7));
            for (int i = 0; i < points; i++) {
                double angle = i * TAU / points + run * 0.4;
                Location at = center.clone().add(right.clone().multiply(Math.cos(angle) * radius))
                        .add(up.clone().multiply(Math.sin(angle) * radius * 0.55));
                Vfx.dot(at, Particle.DUST_COLOR_TRANSITION, i % 3 == 0 ? WISP : CHILL, false);
            }
            Vfx.cloud(ctx(), center, Particle.WHITE_SMOKE, 3, radius * 0.4, 0.01, null, false);
            return true;
        });
        Vfx.cloud(ctx(), mouth, Particle.SOUL, 6, 0.2, 0.05, null, false);
        Fx.sound(mouth, Sound.ENTITY_GHAST_SCREAM, 0.9f, 1.2f + Fx.jitter(0.08f));
        Fx.sound(mouth, Sound.ENTITY_VEX_CHARGE, 1.0f, 0.6f + Fx.jitter(0.05f));
        Fx.sound(mouth, Sound.ENTITY_WARDEN_NEARBY_CLOSEST, 0.5f, 1.4f + Fx.jitter(0.1f));
    }

    // ------------------------------------------------------------------ ultimate: phase

    @Override
    public ActivationResult onUltimate() {
        if (phasing) {
            return ActivationResult.fail("이미 유령화 중입니다");
        }
        Player owner = player();
        long duration = Math.max(1, params().ticks("phase_duration"));
        phasing = true;
        phaseEnd = ctx().currentTick() + duration;
        int effectTicks = (int) Math.min(Integer.MAX_VALUE, duration + 5);
        owner.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, effectTicks, 0, false, false, true));
        double speed = params().num("phase_speed") / 100.0;
        phaseSpeed = Math.abs(speed) > 1.0E-6
                ? ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, Math.max(-0.9, speed),
                        Operation.MULTIPLY_SCALAR_1, duration)
                : null;
        stealth.begin(ctx(), owner, duration, () -> endPhase(true));
        Stealth.dropAggro(ctx(), owner, AGGRO_DROP_RANGE);
        dissolve(owner);
        return ActivationResult.success();
    }

    /** The ghost comes apart into glass shards and mist that scatter outwards. */
    private void dissolve(Player owner) {
        Location body = Fx.body(owner);
        Location feet = owner.getLocation().add(0, 0.1, 0);
        shards(owner, body, true);
        Vfx.helix(ctx(), owner.getLocation(), 0.6, owner.getHeight() + 0.4, 1.5, 2, 12,
                ThreadLocalRandom.current().nextDouble(TAU), Particle.DUST_COLOR_TRANSITION, WISP, true);
        Vfx.shockwave(ctx(), owner, feet, 0.3, 2.0, 5, Particle.DUST, MIST, false);
        Vfx.cloud(ctx(), body, Particle.WHITE_SMOKE, 12, 0.35, 0.03, null, true);
        Vfx.cloud(ctx(), body, Particle.SOUL, 8, 0.3, 0.04, null, true);
        Fx.sound(body, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.0f, 0.6f + Fx.jitter(0.05f));
        Fx.sound(body, Sound.ENTITY_VEX_AMBIENT, 0.8f, 0.5f + Fx.jitter(0.05f));
        Fx.sound(body, Sound.ENTITY_ALLAY_DEATH, 0.5f, 0.6f + Fx.jitter(0.05f));
        owner.sendActionBar(Text.mm("<" + PALE + "><b>☁ 유령화</b></" + PALE + "> <gray>"
                + Fx.seconds(phaseEnd - ctx().currentTick()) + "초</gray>"));
    }

    private void tickPhase(Player owner, long tick) {
        long left = phaseEnd - ctx().currentTick();
        if (tick % 3 == 0) {
            // The only trace of the walking ghost: a faint chill of mist where it steps.
            Vfx.dot(owner.getLocation().add(0, 0.1, 0), Particle.DUST, MIST, false);
        }
        if (tick % 5 == 0) {
            owner.sendActionBar(Text.mm("<" + PALE + ">☁ 유령화</" + PALE + "> <gray>" + Fx.seconds(left)
                    + "초</gray> <dark_gray>· 공격 불가</dark_gray>"));
        }
        if (tick % 20 == 0) {
            stealth.hideFromEnemies(ctx(), owner);
            Stealth.dropAggro(ctx(), owner, AGGRO_DROP_RANGE);
        }
        if (left > 0 && left <= BURST_TELEGRAPH_TICKS) {
            // Telegraph of the burst: a ring of frost closes in on the ghost, warning everyone around.
            double radius = Math.max(0.5, params().num("burst_radius"));
            double closing = 0.6 + (radius - 0.6) * left / BURST_TELEGRAPH_TICKS;
            Vfx.ring(ctx(), owner.getLocation().add(0, 0.12, 0), closing, (int) Math.max(12, closing * 7), tick * 0.3,
                    Particle.DUST_COLOR_TRANSITION, WISP, true);
            if (left == BURST_TELEGRAPH_TICKS) {
                Fx.sound(owner.getLocation(), Sound.ENTITY_WARDEN_SONIC_CHARGE, 0.5f, 1.8f);
            }
        }
    }

    /** The ghost cannot strike while phased: every hit it would deal is cancelled. */
    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (!phasing || event.isCancelled()) {
            return;
        }
        event.setCancelled(true);
        long now = ctx().currentTick();
        if (now - lastLockMessage >= LOCK_MESSAGE_COOLDOWN) {
            lastLockMessage = now;
            Vfx.cloud(ctx(), Fx.body(victim), Particle.DUST, 4, 0.2, 0, MIST, false);
            player().sendActionBar(Text.mm("<gray>유령은 아무것도 붙잡을 수 없습니다</gray>"));
        }
    }

    /**
     * Ends the phase (if any): the ghost is seen again and loses its speed. With {@code burst} (the phase ran out,
     * not revoked) it screams back into the world.
     */
    private void endPhase(boolean burst) {
        if (!phasing) {
            return;
        }
        phasing = false;
        Player owner = player();
        stealth.end(ctx(), owner);
        PotionEffect invisible = owner.getPotionEffect(PotionEffectType.INVISIBILITY);
        if (invisible != null && !invisible.hasParticles() && invisible.getAmplifier() == 0) {
            owner.removePotionEffect(PotionEffectType.INVISIBILITY);
        }
        NamespacedKey speed = phaseSpeed;
        phaseSpeed = null;
        if (speed != null) {
            ctx().removeModifier(owner, speed);
        }
        if (burst && owner.isOnline() && ctx().isAlive(owner)) {
            materialize(owner);
        }
    }

    /** The ghost bursts back into the world: a scream, a frost shockwave, and everyone close is hurt and slowed. */
    private void materialize(Player owner) {
        Location feet = owner.getLocation().add(0, 0.12, 0);
        Location body = Fx.body(owner);
        double radius = Math.max(0.5, params().num("burst_radius"));
        double damage = params().num("burst_damage");
        int slowTicks = (int) Math.min(Integer.MAX_VALUE, params().ticks("burst_slow"));
        int hits = 0;
        for (LivingEntity enemy : ctx().enemiesNear(owner, body, radius)) {
            Combat.hit(ctx(), owner, enemy, damage);
            if (!Combat.isTargetable(ctx(), owner, enemy)) {
                continue;
            }
            hits++;
            if (slowTicks > 0) {
                enemy.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slowTicks, BURST_SLOW_AMPLIFIER,
                        false, true, true));
            }
            Location at = Fx.body(enemy);
            Vfx.burst(ctx(), at, PALETTE, 0.6, Particle.SNOWFLAKE, true);
            Vfx.trail(body, at, PALETTE.core(), 4, true);
        }
        Vector facing = Motion.horizontalFacing(owner.getLocation());
        Specter specter = new Specter(owner, body.clone().add(0, 0.6, 0), facing, 0.6f, 20);
        specter.lunge(new Vector(0, 0.6, 0), 1.9f, 4);
        ctx().runLater(owner, 8, () -> specter.fade(8));
        shards(owner, body, false);
        Vfx.dot(body, Particle.FLASH, PALETTE.core(), true);
        Vfx.shockwave(ctx(), owner, feet, 0.4, radius, 6, Particle.DUST_COLOR_TRANSITION, CHILL, true);
        Vfx.shockwave(ctx(), owner, feet.clone().add(0, 0.15, 0), 0.3, radius * 0.8, 6, Particle.SNOWFLAKE, null,
                true);
        Vfx.sphere(ctx(), body, 1.2, 28, Particle.DUST_COLOR_TRANSITION, WISP, true);
        Vfx.cloud(ctx(), body, Particle.WHITE_SMOKE, 16, 0.6, 0.05, null, true);
        Fx.sound(body, Sound.ENTITY_GHAST_SCREAM, 1.2f, 0.9f + Fx.jitter(0.05f));
        Fx.sound(body, Sound.ENTITY_VEX_CHARGE, 1.0f, 0.5f);
        Fx.sound(body, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.0f, 1.3f);
        Fx.sound(body, Sound.BLOCK_GLASS_BREAK, 0.8f, 0.7f + Fx.jitter(0.05f));
        owner.sendActionBar(Text.mm("<" + PALE + ">☁ 유령화 해제</" + PALE + ">"
                + (hits > 0 ? " <white>" + hits + "명 적중</white>" : "")));
    }

    /** Six frosted glass shards: flying out of the body ({@code outward}) or bursting out and settling (return). */
    private void shards(Player owner, Location body, boolean outward) {
        BlockData glass = SHARD_BLOCK.createBlockData();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double base = random.nextDouble(TAU);
        for (int i = 0; i < SHARDS; i++) {
            double angle = base + i * TAU / SHARDS;
            Vector out = new Vector(Math.cos(angle), random.nextDouble(-0.3, 0.6), Math.sin(angle))
                    .multiply(outward ? 1.8 : 2.6);
            float size = 0.16f + random.nextFloat() * 0.12f;
            Quaternionf tumble = new Quaternionf().rotateXYZ(random.nextFloat() * 6.28f, random.nextFloat() * 6.28f,
                    random.nextFloat() * 6.28f);
            BlockDisplay shard = Displays.block(ctx(), owner, body, glass, cube(size, tumble),
                    SHARD_FLIGHT_TICKS + 10);
            shard.setTeleportDuration(SHARD_FLIGHT_TICKS);
            ctx().runLater(owner, 1, () -> {
                if (shard.isValid()) {
                    shard.teleport(body.clone().add(out));
                    Displays.animate(shard, cube(size * 0.6f, new Quaternionf(tumble).rotateY(2.4f)),
                            SHARD_FLIGHT_TICKS);
                }
            });
            ctx().runLater(owner, SHARD_FLIGHT_TICKS, () -> {
                if (shard.isValid()) {
                    Displays.shrinkAway(ctx(), owner, shard, 4);
                }
            });
        }
    }

    // ------------------------------------------------------------------ visuals

    /**
     * A screaming specter: a glass shroud with a head, two black eyes and a gaping black mouth, facing
     * {@code facing}. Every part shares one anchor so the whole figure moves and grows together.
     */
    private final class Specter {

        private final Player owner;
        private final List<BlockDisplay> parts = new ArrayList<>();
        private final List<Transformation> shapes = new ArrayList<>();
        private final Location anchor;

        Specter(Player owner, Location at, Vector facing, float scale, long lifetime) {
            this.owner = owner;
            this.anchor = at.clone();
            Vector flat = Motion.horizontal(facing);
            if (flat.lengthSquared() > 1.0E-6) {
                anchor.setDirection(flat);
            }
            anchor.setPitch(0);
            BlockData glass = SHROUD_BLOCK.createBlockData();
            BlockData dark = FACE_BLOCK.createBlockData();
            add(glass, box(-0.45f, -1.0f, -0.25f, 0.9f, 1.05f, 0.5f, 1), scale, lifetime);
            add(glass, box(-0.35f, 0.0f, -0.35f, 0.7f, 0.7f, 0.7f, 1), scale, lifetime);
            add(dark, box(0.08f, 0.4f, 0.33f, 0.14f, 0.16f, 0.06f, 1), scale, lifetime);
            add(dark, box(-0.22f, 0.4f, 0.33f, 0.14f, 0.16f, 0.06f, 1), scale, lifetime);
            add(dark, box(-0.1f, 0.08f, 0.33f, 0.2f, 0.26f, 0.06f, 1), scale, lifetime);
        }

        private void add(BlockData data, Transformation shape, float scale, long lifetime) {
            BlockDisplay part = Displays.block(ctx(), owner, anchor, data, scaled(shape, scale), lifetime);
            parts.add(part);
            shapes.add(shape);
        }

        /** Moves the figure by {@code offset} while growing it to {@code scale}, both over {@code ticks}. */
        void lunge(Vector offset, float scale, int ticks) {
            Location to = anchor.clone().add(offset);
            ctx().runLater(owner, 1, () -> {
                for (int i = 0; i < parts.size(); i++) {
                    BlockDisplay part = parts.get(i);
                    if (!part.isValid()) {
                        continue;
                    }
                    part.setTeleportDuration(ticks);
                    part.teleport(to);
                    Displays.animate(part, scaled(shapes.get(i), scale), ticks);
                }
            });
        }

        void fade(int ticks) {
            for (BlockDisplay part : parts) {
                if (part.isValid()) {
                    Displays.shrinkAway(ctx(), owner, part, ticks);
                }
            }
        }
    }

    /** A box in a display's own frame (+Z forward, +X left) from corner {@code (x, y, z)}, scaled by {@code s}. */
    private static Transformation box(float x, float y, float z, float sx, float sy, float sz, float s) {
        return new Transformation(new Vector3f(x * s, y * s, z * s), new Quaternionf(),
                new Vector3f(sx * s, sy * s, sz * s), new Quaternionf());
    }

    private static Transformation scaled(Transformation shape, float scale) {
        return new Transformation(new Vector3f(shape.getTranslation()).mul(scale), new Quaternionf(),
                new Vector3f(shape.getScale()).mul(scale), new Quaternionf());
    }

    /** A cube of edge {@code size} centered on the display's origin, rotated around its center. */
    private static Transformation cube(float size, Quaternionf rotation) {
        Vector3f translation = new Quaternionf(rotation).transform(new Vector3f(size / 2)).negate();
        return new Transformation(translation, new Quaternionf(rotation), new Vector3f(size), new Quaternionf());
    }
}
