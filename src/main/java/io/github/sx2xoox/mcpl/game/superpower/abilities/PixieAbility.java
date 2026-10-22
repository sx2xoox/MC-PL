package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Body;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Motion;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Stealth;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 요정 — a tiny, fast, fragile trickster. Hard to hit thanks to its size, blinds everyone around with fairy dust
 * while slipping out of sight, and takes to the air on glittering wings as the ultimate.
 */
public final class PixieAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("pixie", "요정", Material.PINK_PETALS)
            .color(TextColor.color(0xFF8AD8))
            .difficulty(2)
            .passive("요정의 몸",
                    "몸이 {scale}배로 작아져 맞히기 어렵지만 최대 체력이 {health_penalty} 줄어듭니다. 항상 신속 "
                            + "{speed_level}단계와 점프 강화 {jump_level}단계 효과를 받습니다.")
            .active("요정 가루",
                    "주변 {dust_radius}블록 안의 적을 {blind_duration}초간 실명시키고, {invis_duration}초간 적의 "
                            + "눈에서 사라집니다. 공격하면 모습이 드러납니다.",
                    8)
            .ultimate("요정의 날개",
                    "{flight_duration}초간 자유롭게 날 수 있습니다. 날개가 사라지면 땅에 닿을 때까지 천천히 "
                            + "내려옵니다.")
            // Flight must be taken away again by this instance (timer + onRevoke), so it cannot be borrowed.
            .ultimateNotStandalone()
            .param("scale", 0.6)
            .param("health_penalty", 4)
            .param("speed_level", 1)
            .param("jump_level", 1)
            .param("dust_radius", 5)
            .param("blind_duration", 2)
            .param("invis_duration", 3)
            .param("flight_duration", 6)
            .param("fly_speed", 80)
            .build();

    private static final String PINK = "#FF8AD8";
    private static final Color PETAL = Color.fromRGB(0xFF, 0x8A, 0xD8);
    private static final Color SKY = Color.fromRGB(0x8A, 0xE6, 0xFF);
    private static final Color GLOW = Color.fromRGB(0xFF, 0xF2, 0xFB);
    private static final Particle.DustTransition FAIRY_DUST = new Particle.DustTransition(PETAL, SKY, 0.9f);
    private static final Particle.DustTransition WING_DUST = new Particle.DustTransition(GLOW, PETAL, 0.6f);
    private static final Particle.DustTransition WING_EDGE = new Particle.DustTransition(SKY, GLOW, 0.6f);
    private static final Particle.DustOptions SPARKLE_PINK = new Particle.DustOptions(PETAL, 0.6f);
    private static final Particle.DustOptions SPARKLE_SKY = new Particle.DustOptions(SKY, 0.6f);

    /** Butterfly-like wing outline in body space: x = distance out from the spine, y = height from the shoulders. */
    private static final double[][] WING = {
            {0.1, 0.1}, {0.3, 0.38}, {0.52, 0.58}, {0.74, 0.62}, {0.86, 0.46}, {0.72, 0.24}, {0.45, 0.08},
            {0.38, -0.08}, {0.56, -0.24}, {0.6, -0.44}, {0.42, -0.5}, {0.22, -0.3}};
    private static final double SHOULDER_HEIGHT = 1.35;
    /** Wings are drawn larger than the owner's (tiny) scale so they stay readable. */
    private static final double WING_SIZE = 1.6;

    private static final double DEFAULT_FLY_SPEED = 0.1;
    private static final double TAKE_OFF_VELOCITY = 0.55;
    private static final int SLOW_FALL_REFRESH_TICKS = 40;
    private static final long MAX_DESCENT_TICKS = 30 * 20;
    private static final long FALL_GRACE_TICKS = 10;
    private static final double AGGRO_DROP_RANGE = 32;
    private static final double MIN_HEALTH = 2;

    // Stealth
    private final Stealth stealth = new Stealth();

    // Flight
    private boolean flying;
    private boolean descending;
    private boolean savedAllowFlight;
    private float savedFlySpeed = (float) DEFAULT_FLY_SPEED;
    private long flightEndTick;
    private long descentEndTick;
    private long fallGraceUntil = -1;

    private @Nullable Location lastTrailSample;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    // ------------------------------------------------------------------ passive: fairy body

    @Override
    public void onGrant() {
        Player owner = player();
        double scale = Math.clamp(params().num("scale"), 0.2, 1.0);
        if (scale < 1.0) {
            ctx().addModifier(owner, owner, Attribute.SCALE, scale - 1.0, AttributeModifier.Operation.ADD_NUMBER, 0);
        }
        double max = Combat.maxHealth(owner);
        double penalty = Math.clamp(params().num("health_penalty"), 0, Math.max(0, max - MIN_HEALTH));
        if (penalty > 0) {
            ctx().addModifier(owner, owner, Attribute.MAX_HEALTH, -penalty, AttributeModifier.Operation.ADD_NUMBER,
                    0);
            double newMax = Combat.maxHealth(owner);
            if (owner.getHealth() > newMax) {
                owner.setHealth(newMax);
            }
        }
        // The same numbers vanilla Speed / Jump Boost use, as permanent modifiers the context cleans up.
        double speedLevel = Math.max(0, params().num("speed_level"));
        if (speedLevel > 0) {
            ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, 0.2 * speedLevel,
                    AttributeModifier.Operation.MULTIPLY_SCALAR_1, 0);
        }
        double jumpLevel = Math.max(0, params().num("jump_level"));
        if (jumpLevel > 0) {
            ctx().addModifier(owner, owner, Attribute.JUMP_STRENGTH, 0.1 * jumpLevel,
                    AttributeModifier.Operation.ADD_NUMBER, 0);
            ctx().addModifier(owner, owner, Attribute.SAFE_FALL_DISTANCE, jumpLevel,
                    AttributeModifier.Operation.ADD_NUMBER, 0);
        }
        Location body = owner.getLocation().add(0, 0.5, 0);
        body.getWorld().spawnParticle(Particle.DUST_COLOR_TRANSITION, body, 20, 0.3, 0.4, 0.3, 0, FAIRY_DUST);
        Fx.sound(body, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.8f);
    }

    @Override
    public void onRevoke() {
        reveal(false);
        if (flying || descending) {
            Player owner = player();
            if (flying) {
                restoreFlight(owner);
            }
            if (owner.isOnline()) {
                removeOwnSlowFalling(owner);
            }
            flying = false;
            descending = false;
        }
    }

    @Override
    public void onTick(long tick) {
        Player owner = player();
        if (flying) {
            tickFlight(owner, tick);
        } else if (descending) {
            tickDescent(owner, tick);
        }
        if (stealth.isActive()) {
            tickStealth(owner, tick);
        } else if (!flying && tick % 8 == 0) {
            trailSparkle(owner);
        }
    }

    /** A faint glitter left behind while moving, the pixie's signature. */
    private void trailSparkle(Player owner) {
        Location feet = owner.getLocation();
        Location last = lastTrailSample;
        lastTrailSample = feet;
        if (last == null || last.getWorld() != feet.getWorld() || last.distanceSquared(feet) < 0.25) {
            return;
        }
        Location at = feet.add(0, owner.getHeight() * 0.4, 0);
        boolean pink = ThreadLocalRandom.current().nextBoolean();
        owner.getWorld().spawnParticle(Particle.DUST, at, 1, 0.12, 0.12, 0.12, 0, pink ? SPARKLE_PINK : SPARKLE_SKY);
    }

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled() || !Combat.isFall(event)) {
            return;
        }
        if (flying || descending || ctx().currentTick() <= fallGraceUntil) {
            event.setCancelled(true);
        }
    }

    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (stealth.isActive() && !event.isCancelled()) {
            reveal(true);
        }
    }

    // ------------------------------------------------------------------ active: fairy dust

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        Location center = owner.getLocation();
        World world = center.getWorld();
        double radius = params().num("dust_radius");
        int blind = (int) params().ticks("blind_duration");

        List<LivingEntity> enemies = ctx().enemiesNear(owner, center, radius);
        for (LivingEntity enemy : enemies) {
            if (blind > 0) {
                enemy.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, blind, 0, false, true, true));
            }
            if (enemy instanceof Mob mob && owner.equals(mob.getTarget())) {
                mob.setTarget(null);
            }
            Location head = enemy.getEyeLocation();
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, head, 12, 0.3, 0.25, 0.3, 0, FAIRY_DUST);
            world.spawnParticle(Particle.CHERRY_LEAVES, head, 3, 0.3, 0.2, 0.3, 0);
            if (enemy instanceof Player victim) {
                victim.playSound(victim, Sound.ENTITY_ILLUSIONER_PREPARE_BLINDNESS, SoundCategory.PLAYERS, 0.8f, 1.6f);
            }
        }

        Location body = center.clone().add(0, owner.getHeight() * 0.5, 0);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, body, 40, 0.8, 0.6, 0.8, 0, FAIRY_DUST);
        world.spawnParticle(Particle.CHERRY_LEAVES, body, 14, 1.2, 0.6, 1.2, 0);
        world.spawnParticle(Particle.END_ROD, body, 8, 0.5, 0.4, 0.5, 0.05);
        dustWave(center, radius);
        Fx.sound(center, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.5f + Fx.jitter(0.1f));
        Fx.sound(center, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 2.0f);
        Fx.sound(center, Sound.ENTITY_ALLAY_ITEM_THROWN, 0.9f, 1.3f + Fx.jitter(0.1f));

        startStealth(owner, params().ticks("invis_duration"));
        owner.sendActionBar(Text.mm(enemies.isEmpty()
                ? "<" + PINK + ">✿ 요정 가루</" + PINK + "> <gray>모습을 감췄습니다</gray>"
                : "<" + PINK + ">✿ 요정 가루</" + PINK + "> <white>" + enemies.size() + "명 실명</white> "
                        + "<gray>· 모습을 감췄습니다</gray>"));
        return ActivationResult.success();
    }

    /** A sparkling ring that sweeps out to the dust radius over a few ticks (visual only). */
    private void dustWave(Location center, double radius) {
        int frames = 5;
        Loop.repeat(ctx(), player(), 0, 1, frames, run -> {
            long frame = run + 1;
            double r = radius * frame / frames;
            int points = Math.max(8, (int) (r * 5));
            World world = center.getWorld();
            double offset = frame * 0.4;
            for (int i = 0; i < points; i++) {
                double angle = offset + Math.PI * 2 * i / points;
                Location at = center.clone().add(Math.cos(angle) * r, 0.25, Math.sin(angle) * r);
                world.spawnParticle(Particle.DUST, at, 1, 0, 0.05, 0, 0, i % 2 == 0 ? SPARKLE_PINK : SPARKLE_SKY);
            }
            return true;
        });
    }

    // ------------------------------------------------------------------ stealth

    private void startStealth(Player owner, long ticks) {
        if (ticks <= 0) {
            return;
        }
        // Allies and spectators keep seeing the vanilla translucent body; enemies do not see the owner at all.
        owner.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, (int) ticks + 5, 0, false, false, true));
        stealth.begin(ctx(), owner, ticks, () -> reveal(true));
        Stealth.dropAggro(ctx(), owner, AGGRO_DROP_RANGE);
    }

    private void tickStealth(Player owner, long tick) {
        if (tick % 6 == 0) {
            // The only tell: a few specks of dust drifting where the pixie is.
            Location at = owner.getLocation().add(0, 0.2, 0);
            owner.getWorld().spawnParticle(Particle.DUST, at, 1, 0.15, 0.1, 0.15, 0, SPARKLE_PINK);
        }
        if (!flying && tick % 5 == 0) {
            owner.sendActionBar(Text.mm("<" + PINK + ">✿ 투명</" + PINK + "> <gray>"
                    + Fx.seconds(stealth.ticksLeft(ctx().currentTick())) + "초</gray>"));
        }
        if (tick % 20 == 0) {
            stealth.hideFromEnemies(ctx(), owner);
            Stealth.dropAggro(ctx(), owner, AGGRO_DROP_RANGE);
        }
    }

    /** Ends the stealth (if any), showing the owner to everyone it was hidden from. */
    private void reveal(boolean feedback) {
        Player owner = player();
        if (!stealth.end(ctx(), owner) || !owner.isOnline()) {
            return;
        }
        PotionEffect invisibility = owner.getPotionEffect(PotionEffectType.INVISIBILITY);
        if (invisibility != null && !invisibility.hasParticles()) {
            owner.removePotionEffect(PotionEffectType.INVISIBILITY);
        }
        if (feedback) {
            Location body = owner.getLocation().add(0, owner.getHeight() * 0.5, 0);
            body.getWorld().spawnParticle(Particle.DUST_COLOR_TRANSITION, body, 14, 0.25, 0.3, 0.25, 0, FAIRY_DUST);
            Fx.sound(body, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 0.6f, 1.8f + Fx.jitter(0.1f));
        }
    }

    // ------------------------------------------------------------------ ultimate: fairy wings

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        if (owner.isInsideVehicle()) {
            return ActivationResult.fail("탈것에 탄 채로는 날 수 없습니다");
        }
        GameMode mode = owner.getGameMode();
        if (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) {
            return ActivationResult.fail("지금은 날개를 펼칠 수 없습니다");
        }
        long now = ctx().currentTick();
        if (!flying) {
            savedAllowFlight = owner.getAllowFlight();
            savedFlySpeed = owner.getFlySpeed();
        }
        flying = true;
        descending = false;
        flightEndTick = now + Math.max(1, params().ticks("flight_duration"));
        removeOwnSlowFalling(owner);

        float speed = (float) Math.clamp(DEFAULT_FLY_SPEED * params().num("fly_speed") / 100.0, 0.02, 0.2);
        owner.setAllowFlight(true);
        owner.setFlySpeed(speed);
        Vector velocity = owner.getVelocity();
        owner.setVelocity(new Vector(velocity.getX(), Math.max(velocity.getY(), TAKE_OFF_VELOCITY),
                velocity.getZ()));
        owner.setFlying(true);
        owner.setFallDistance(0);

        Location body = owner.getLocation().add(0, owner.getHeight() * 0.5, 0);
        World world = body.getWorld();
        world.spawnParticle(Particle.CHERRY_LEAVES, body, 24, 0.8, 0.4, 0.8, 0);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, body, 30, 0.6, 0.5, 0.6, 0, FAIRY_DUST);
        world.spawnParticle(Particle.END_ROD, body, 10, 0.4, 0.4, 0.4, 0.08);
        drawWings(owner, 0.1);
        Fx.sound(body, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.0f, 1.6f);
        Fx.sound(body, Sound.ENTITY_ALLAY_AMBIENT_WITHOUT_ITEM, 1.0f, 1.2f + Fx.jitter(0.1f));
        Fx.sound(body, Sound.ITEM_ARMOR_EQUIP_ELYTRA, 0.8f, 1.4f);
        return ActivationResult.success();
    }

    private void tickFlight(Player owner, long tick) {
        long left = flightEndTick - tick;
        if (left <= 0 || !owner.getAllowFlight()) {
            endFlight(owner, tick);
            return;
        }
        owner.setFallDistance(0);
        if (!stealth.isActive() && tick % 2 == 0) {
            double flap = 0.25 + 0.5 * (0.5 + 0.5 * Math.sin(tick * 1.1));
            drawWings(owner, flap);
        }
        if (tick % 5 == 0) {
            String bar = "<" + PINK + ">✿ 요정의 날개</" + PINK + "> <gray>"
                    + Fx.seconds(left) + "초</gray>";
            if (stealth.isActive()) {
                bar += " <dark_gray>·</dark_gray> <gray>투명</gray>";
            }
            owner.sendActionBar(Text.mm(bar));
        }
        if (left <= 60 && left % 20 == 0) {
            owner.playSound(owner, Sound.BLOCK_NOTE_BLOCK_CHIME, SoundCategory.PLAYERS, 0.8f,
                    1.0f + 0.2f * (3 - left / 20f));
        }
        if (tick % 16 == 0) {
            Fx.sound(owner.getLocation(), Sound.ENTITY_ALLAY_AMBIENT_WITHOUT_ITEM, 0.3f, 1.6f + Fx.jitter(0.2f));
        }
    }

    private void endFlight(Player owner, long tick) {
        flying = false;
        restoreFlight(owner);
        fallGraceUntil = tick + FALL_GRACE_TICKS;
        if (!Motion.isGrounded(owner) && !owner.isInWater()) {
            descending = true;
            descentEndTick = tick + MAX_DESCENT_TICKS;
            owner.addPotionEffect(slowFalling());
        }
        Location body = owner.getLocation().add(0, owner.getHeight() * 0.5, 0);
        body.getWorld().spawnParticle(Particle.CHERRY_LEAVES, body, 10, 0.5, 0.3, 0.5, 0);
        Fx.sound(body, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 0.7f, 1.3f);
        owner.sendActionBar(Text.mm("<" + PINK + ">✿ 날개가 사라졌습니다</" + PINK + ">"
                + (descending ? " <gray>천천히 내려갑니다</gray>" : "")));
    }

    private void tickDescent(Player owner, long tick) {
        owner.setFallDistance(0);
        if (tick > descentEndTick || owner.isInWater() || Motion.isGrounded(owner)) {
            descending = false;
            fallGraceUntil = tick + FALL_GRACE_TICKS;
            removeOwnSlowFalling(owner);
            return;
        }
        PotionEffect current = owner.getPotionEffect(PotionEffectType.SLOW_FALLING);
        if (current == null || current.getDuration() < SLOW_FALL_REFRESH_TICKS / 2) {
            owner.addPotionEffect(slowFalling());
        }
        if (tick % 4 == 0) {
            owner.getWorld().spawnParticle(Particle.CHERRY_LEAVES, owner.getLocation().add(0, 0.3, 0), 1,
                    0.2, 0.1, 0.2, 0);
        }
    }

    /** Gives the flight back to how it was before the ultimate (only in modes where the plugin controls it). */
    private void restoreFlight(Player owner) {
        if (!owner.isOnline()) {
            return;
        }
        GameMode mode = owner.getGameMode();
        if (mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE) {
            owner.setFlying(false);
            owner.setAllowFlight(savedAllowFlight);
        }
        owner.setFlySpeed(savedFlySpeed);
    }

    private static PotionEffect slowFalling() {
        return new PotionEffect(PotionEffectType.SLOW_FALLING, SLOW_FALL_REFRESH_TICKS, 0, false, false, true);
    }

    /** Removes slow falling only if it is the (particle-less) one this ability applied. */
    private static void removeOwnSlowFalling(Player owner) {
        PotionEffect current = owner.getPotionEffect(PotionEffectType.SLOW_FALLING);
        if (current != null && !current.hasParticles() && current.getDuration() <= SLOW_FALL_REFRESH_TICKS) {
            owner.removePotionEffect(PotionEffectType.SLOW_FALLING);
        }
    }

    /**
     * Draws a pair of glittering wings behind the owner's back.
     *
     * @param flap how far the wings are swept back, in radians (0 = spread flat to the sides)
     */
    private static void drawWings(Player owner, double flap) {
        double bodyScale = Body.scaleOf(owner);
        double scale = bodyScale * WING_SIZE;
        double yaw = Math.toRadians(owner.getBodyYaw());
        Vector right = new Vector(-Math.cos(yaw), 0, -Math.sin(yaw));
        Vector back = new Vector(Math.sin(yaw), 0, -Math.cos(yaw));
        Location shoulder = owner.getLocation().add(0, SHOULDER_HEIGHT * bodyScale, 0)
                .add(back.clone().multiply(0.15 * bodyScale));
        World world = owner.getWorld();
        for (int side = -1; side <= 1; side += 2) {
            Vector out = right.clone().multiply(side * Math.cos(flap)).add(back.clone().multiply(Math.sin(flap)));
            for (int i = 0; i < WING.length; i++) {
                double[] point = WING[i];
                Location at = shoulder.clone().add(out.clone().multiply(point[0] * scale)).add(0, point[1] * scale, 0);
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, 1, 0, 0, 0, 0,
                        i % 3 == 0 ? WING_EDGE : WING_DUST);
            }
        }
    }
}
