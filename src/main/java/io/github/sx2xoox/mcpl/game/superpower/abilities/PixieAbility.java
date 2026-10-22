package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Body;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Motion;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Stealth;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
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
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
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

    // ------------------------------------------------------------------ look: white glitter, blossom pink, hot pink
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFFFFF, 0xFFC1EC, 0xFF8AD8);
    /** A cool sparkle mixed into the glitter so it shimmers instead of reading as one flat pink. */
    private static final Color SKY = Color.fromRGB(0xA8, 0xE8, 0xFF);
    private static final Particle.DustTransition GLITTER = PALETTE.glow(0.7f);
    private static final Particle.DustTransition PETAL_DUST = PALETTE.fade(1.0f);
    private static final Particle.DustOptions SPARK_PINK = new Particle.DustOptions(PALETTE.edge(), 0.6f);
    private static final Particle.DustOptions SPARK_SKY = new Particle.DustOptions(SKY, 0.55f);
    private static final BlockData UPPER_WING = Material.PINK_STAINED_GLASS.createBlockData();
    private static final BlockData LOWER_WING = Material.MAGENTA_STAINED_GLASS.createBlockData();

    // Wings (in the owner's body space, scaled by the owner's size times WING_SIZE)
    /** Height of the wing roots on a full-size body (mid-back, so the upper panes frame the shoulders). */
    private static final float WING_ROOT_HEIGHT = 1.05f;
    private static final float WING_BACK = 0.2f;
    /** Wings are drawn larger than the owner's (tiny) body so they stay readable on stream. */
    private static final float WING_SIZE = 1.6f;
    private static final float UPPER_LENGTH = 0.6f;
    private static final float UPPER_HEIGHT = 0.72f;
    private static final float LOWER_LENGTH = 0.36f;
    private static final float LOWER_HEIGHT = 0.46f;
    private static final float UPPER_ROLL = -35f;
    private static final float LOWER_ROLL = -110f;
    private static final float WING_THICKNESS = 0.02f;
    private static final double SWEEP_REST = 32;
    private static final double SWEEP_AMPLITUDE = 26;
    private static final int FLAP_STEP_TICKS = 2;
    private static final int WING_OPEN_TICKS = 5;
    private static final int WING_FOLD_TICKS = 6;
    /** In the last ticks of the flight the wings beat faster, a tell that the flight is ending. */
    private static final long WING_WARNING_TICKS = 40;

    // Fairy dust
    private static final int DUST_WAVE_TICKS = 6;
    private static final int PETALS = 6;
    private static final int PETAL_FLIGHT_TICKS = 9;
    private static final int TRAIL_TICKS = 6;

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
    private @Nullable Wings wings;

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
        // Shrinking into a fairy: a double helix of glitter spirals up around the new, tiny body.
        Location feet = owner.getLocation();
        double height = Body.PLAYER.height() * scale;
        Vfx.helix(ctx(), feet, 0.45, height + 0.3, 1.5, 2, 12, 0, Particle.DUST_COLOR_TRANSITION, GLITTER, false);
        Vfx.dot(feet.clone().add(0, height + 0.4, 0), Particle.END_ROD, null, false);
        Fx.sound(feet, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.8f + Fx.jitter(0.1f));
        Fx.sound(feet, Sound.ENTITY_ALLAY_ITEM_GIVEN, 0.5f, 1.6f);
    }

    @Override
    public void onRevoke() {
        reveal(false);
        if (wings != null) {
            wings.remove();
            wings = null;
        }
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
        } else if (!flying && tick % 4 == 0) {
            trailSparkle(owner, tick);
        }
    }

    /** A faint glitter (and now and then a petal) left behind while moving, the pixie's signature. */
    private void trailSparkle(Player owner, long tick) {
        Location feet = owner.getLocation();
        Location last = lastTrailSample;
        lastTrailSample = feet;
        if (last == null || last.getWorld() != feet.getWorld() || last.distanceSquared(feet) < 0.12) {
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Location at = feet.clone().add(random.nextDouble(-0.15, 0.15), owner.getHeight() * 0.45,
                random.nextDouble(-0.15, 0.15));
        Vfx.dot(at, Particle.DUST, random.nextBoolean() ? SPARK_PINK : SPARK_SKY, false);
        if (tick % 16 == 0) {
            Vfx.dot(at, Particle.CHERRY_LEAVES, null, false);
        } else if (tick % 12 == 0) {
            Vfx.dot(at.add(0, 0.2, 0), Particle.WAX_OFF, null, false);
        }
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
        double radius = params().num("dust_radius");
        int blind = (int) params().ticks("blind_duration");
        Location chest = center.clone().add(0, owner.getHeight() * 0.55, 0);

        List<LivingEntity> enemies = ctx().enemiesNear(owner, center, radius);
        for (LivingEntity enemy : enemies) {
            if (blind > 0) {
                enemy.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, blind, 0, false, true, true));
            }
            if (enemy instanceof Mob mob && owner.equals(mob.getTarget())) {
                mob.setTarget(null);
            }
            dustTrail(chest, enemy);
            if (enemy instanceof Player victim) {
                victim.playSound(victim, Sound.ENTITY_ILLUSIONER_PREPARE_BLINDNESS, SoundCategory.PLAYERS, 0.8f, 1.6f);
            }
        }
        if (blind > 0 && !enemies.isEmpty()) {
            dizzyStars(enemies, blind);
        }

        // The puff the pixie vanishes in: a flash of glitter, petals flying out and a wave sweeping to the radius.
        Vfx.burst(ctx(), chest, PALETTE, 0.8, Particle.CHERRY_LEAVES, false);
        Vfx.cloud(ctx(), chest, Particle.CHERRY_LEAVES, 10, 0.9, 0, null, false);
        petalBurst(chest);
        dustWave(center.clone().add(0, 0.25, 0), radius);
        Fx.sound(center, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.5f + Fx.jitter(0.1f));
        Fx.sound(center, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 2.0f);
        Fx.sound(center, Sound.ENTITY_ALLAY_ITEM_THROWN, 0.9f, 1.3f + Fx.jitter(0.1f));
        Fx.sound(center, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.5f, 1.9f + Fx.jitter(0.05f));

        startStealth(owner, params().ticks("invis_duration"));
        owner.sendActionBar(Text.mm(enemies.isEmpty()
                ? "<" + PINK + ">✿ 요정 가루</" + PINK + "> <gray>모습을 감췄습니다</gray>"
                : "<" + PINK + ">✿ 요정 가루</" + PINK + "> <white>" + enemies.size() + "명 실명</white> "
                        + "<gray>· 모습을 감췄습니다</gray>"));
        return ActivationResult.success();
    }

    /** A sparkling double ring that sweeps out to the dust radius (visual only). */
    private void dustWave(Location center, double radius) {
        Loop.repeat(ctx(), player(), 0, 1, DUST_WAVE_TICKS, run -> {
            double t = (double) (run + 1) / DUST_WAVE_TICKS;
            double r = radius * (1 - (1 - t) * (1 - t));
            int points = (int) Math.max(10, Math.round(r * 7));
            Vfx.ring(ctx(), center, r, points, run * 0.35, Particle.DUST_COLOR_TRANSITION, GLITTER, false);
            Vfx.ring(ctx(), center.clone().add(0, 0.12, 0), r * 0.92, Math.max(4, points / 3), run * 0.35 + 0.5,
                    Particle.DUST, run % 2 == 0 ? SPARK_PINK : SPARK_SKY, false);
            if (run == DUST_WAVE_TICKS - 1) {
                Vfx.ring(ctx(), center.clone().add(0, 0.3, 0), r, Math.max(6, points / 4), 0, Particle.WAX_OFF,
                        null, false);
            }
            return true;
        });
    }

    /** Pink petals (item displays) bursting out of the puff, spinning, then fading. */
    private void petalBurst(Location chest) {
        Player owner = player();
        Location at = chest.clone();
        at.setYaw(0);
        at.setPitch(0);
        ItemStack petal = new ItemStack(Material.PINK_PETALS);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double base = random.nextDouble(Math.PI * 2);
        for (int i = 0; i < PETALS; i++) {
            double angle = base + i * Math.PI * 2 / PETALS;
            float yaw = (float) Math.toDegrees(angle);
            ItemDisplay display = Displays.item(ctx(), owner, at, petal,
                    Displays.centered(0, 0, 0, 0.15f, 0.15f, 0.15f, yaw, 0, 0), PETAL_FLIGHT_TICKS + 12);
            double reach = 1.4 + random.nextDouble(0.5);
            Transformation out = Displays.centered((float) (Math.cos(angle) * reach),
                    (float) random.nextDouble(0.1, 0.6), (float) (Math.sin(angle) * reach), 0.5f, 0.5f, 0.5f,
                    yaw + 180, 60 + random.nextInt(40), random.nextInt(-60, 60));
            Displays.animateNextTick(ctx(), owner, display, out, PETAL_FLIGHT_TICKS);
            ctx().runLater(owner, PETAL_FLIGHT_TICKS + 1, () -> Displays.shrinkAway(ctx(), owner, display, 8));
        }
    }

    /** Glittering motes fly from the pixie into the enemy's eyes, then puff there. */
    private void dustTrail(Location from, LivingEntity enemy) {
        Location eye = enemy.getEyeLocation();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int motes = Vfx.n(ctx(), 3);
        for (int i = 0; i < motes; i++) {
            Location start = from.clone().add(random.nextDouble(-0.2, 0.2), random.nextDouble(-0.1, 0.2),
                    random.nextDouble(-0.2, 0.2));
            Vfx.trail(start, eye, i % 2 == 0 ? PALETTE.edge() : PALETTE.core(), TRAIL_TICKS + i, false);
        }
        ctx().runLater(player(), TRAIL_TICKS, () -> {
            if (!enemy.isValid()) {
                return;
            }
            Location head = enemy.getEyeLocation();
            Vfx.cloud(ctx(), head, Particle.DUST_COLOR_TRANSITION, 8, 0.25, 0, PETAL_DUST, false);
            Vfx.cloud(ctx(), head, Particle.CHERRY_LEAVES, 2, 0.3, 0, null, false);
        });
    }

    /** Little stars circle above the heads of the blinded enemies while the blindness lasts. */
    private void dizzyStars(List<LivingEntity> enemies, int ticks) {
        List<LivingEntity> dazed = new ArrayList<>(enemies);
        Loop.repeat(ctx(), player(), TRAIL_TICKS, 3, Math.max(1, (ticks - TRAIL_TICKS) / 3), run -> {
            dazed.removeIf(enemy -> !enemy.isValid() || !enemy.hasPotionEffect(PotionEffectType.BLINDNESS));
            for (LivingEntity enemy : dazed) {
                Location top = enemy.getLocation().add(0, enemy.getHeight() + 0.25, 0);
                double angle = run * 0.7;
                for (int i = 0; i < 2; i++) {
                    double a = angle + i * Math.PI;
                    Location at = top.clone().add(Math.cos(a) * 0.4, 0, Math.sin(a) * 0.4);
                    Vfx.dot(at, Particle.DUST, i == 0 ? SPARK_PINK : SPARK_SKY, false);
                }
                if (run % 3 == 0) {
                    Vfx.dot(top, Particle.WAX_OFF, null, false);
                }
            }
            return !dazed.isEmpty();
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
        if (wings != null) {
            // Glowing wings would give the hidden pixie away; they come back when the stealth ends.
            wings.remove();
            wings = null;
        }
    }

    private void tickStealth(Player owner, long tick) {
        if (tick % 6 == 0) {
            // The only tell: a speck of dust drifting where the pixie is.
            Location at = owner.getLocation().add(0, 0.2, 0);
            Vfx.dot(at, Particle.DUST, SPARK_PINK, false);
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
            // Glitter condenses back into the pixie.
            Location body = owner.getLocation().add(0, owner.getHeight() * 0.5, 0);
            Vfx.sphere(ctx(), body, 0.55, 16, Particle.DUST_COLOR_TRANSITION, GLITTER, false);
            Vfx.dot(body, Particle.FLASH, PALETTE.body(), false);
            Fx.sound(body, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 0.6f, 1.8f + Fx.jitter(0.1f));
            Fx.sound(body, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.5f, 2.0f);
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

        // Take-off: a flash of glitter, a pink ring rushing out over the ground and a spiral lifting the pixie.
        Location feet = owner.getLocation();
        Location body = feet.clone().add(0, owner.getHeight() * 0.5, 0);
        Vfx.burst(ctx(), body, PALETTE, 1.0, Particle.CHERRY_LEAVES, true);
        Vfx.shockwave(ctx(), owner, feet.clone().add(0, 0.1, 0), 0.3, 2.4, 6, Particle.DUST_COLOR_TRANSITION,
                PETAL_DUST, true);
        Vfx.helix(ctx(), feet, 0.6, 2.4, 1.5, 2, 14, 0, Particle.DUST_COLOR_TRANSITION, GLITTER, true);
        Vfx.cloud(ctx(), body, Particle.CHERRY_LEAVES, 18, 0.8, 0, null, true);
        if (!stealth.isActive()) {
            if (wings != null) {
                wings.remove();
            }
            wings = new Wings(owner);
        }
        Fx.sound(body, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.0f, 1.6f);
        Fx.sound(body, Sound.ENTITY_ALLAY_AMBIENT_WITHOUT_ITEM, 1.0f, 1.2f + Fx.jitter(0.1f));
        Fx.sound(body, Sound.ITEM_ARMOR_EQUIP_ELYTRA, 0.8f, 1.4f);
        Fx.sound(body, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9f, 2.0f);
        return ActivationResult.success();
    }

    private void tickFlight(Player owner, long tick) {
        long left = flightEndTick - tick;
        if (left <= 0 || !owner.getAllowFlight()) {
            endFlight(owner, tick);
            return;
        }
        owner.setFallDistance(0);
        if (!stealth.isActive()) {
            if (wings == null) {
                wings = new Wings(owner);
            }
            if (!wings.update(owner, tick, left <= WING_WARNING_TICKS)) {
                wings = null;
            }
            if (tick % 2 == 0) {
                // Glitter shed behind the flier.
                Location at = owner.getLocation().add(0, owner.getHeight() * 0.3, 0);
                Vfx.dot(at, Particle.DUST_COLOR_TRANSITION, GLITTER, false);
                if (tick % 8 == 0) {
                    Vfx.dot(at, Particle.CHERRY_LEAVES, null, false);
                }
            }
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
        if (wings != null) {
            wings.fold();
            wings = null;
        }
        Location body = owner.getLocation().add(0, owner.getHeight() * 0.5, 0);
        if (!stealth.isActive()) {
            Vfx.cloud(ctx(), body, Particle.CHERRY_LEAVES, 12, 0.5, 0, null, false);
            Vfx.cloud(ctx(), body, Particle.DUST_COLOR_TRANSITION, 10, 0.4, 0, PETAL_DUST, false);
        }
        Fx.sound(body, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 0.7f, 1.3f);
        Fx.sound(body, Sound.BLOCK_CHERRY_LEAVES_BREAK, 0.8f, 1.2f + Fx.jitter(0.1f));
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
        if (stealth.isActive()) {
            return;
        }
        if (tick % 4 == 0) {
            // Petals drifting down with the pixie, and a faint ring of glitter like a tiny parachute.
            Location feet = owner.getLocation();
            Vfx.dot(feet.clone().add(0, 0.3, 0), Particle.CHERRY_LEAVES, null, false);
            if (tick % 8 == 0) {
                Vfx.ring(ctx(), feet.clone().add(0, owner.getHeight() + 0.25, 0), 0.35, 6, tick * 0.2,
                        Particle.DUST, SPARK_PINK, false);
            }
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

    // ------------------------------------------------------------------ wings

    /**
     * A pair of butterfly wings on the owner's back: per side a large pink upper and a small magenta lower pane of
     * thin stained glass (block displays), pivoting at the shoulders. They follow the owner every tick and beat by
     * interpolating their sweep angle.
     */
    private final class Wings {
        private final BlockDisplay[] panes = new BlockDisplay[4];
        private final float size;
        private final float bodyScale;
        private double phase;
        private int age;

        Wings(Player owner) {
            this.bodyScale = (float) Body.scaleOf(owner);
            this.size = bodyScale * WING_SIZE;
            long lifetime = Math.max(1, flightEndTick - ctx().currentTick()) + WING_FOLD_TICKS + 20;
            Location at = anchor(owner);
            Transformation folded = new Transformation(new Vector3f(0, WING_ROOT_HEIGHT * bodyScale, 0),
                    new Quaternionf(), new Vector3f(0.001f), new Quaternionf());
            int index = 0;
            for (boolean left : new boolean[] {true, false}) {
                for (boolean upper : new boolean[] {true, false}) {
                    BlockDisplay pane = Displays.block(ctx(), owner, at, upper ? UPPER_WING : LOWER_WING, folded,
                            lifetime);
                    Displays.animateNextTick(ctx(), owner, pane, pane(left, upper, SWEEP_REST), WING_OPEN_TICKS);
                    panes[index++] = pane;
                }
            }
        }

        /** Follows the owner and beats; false (after removing itself) if the wings are gone. */
        boolean update(Player owner, long tick, boolean hurried) {
            for (BlockDisplay pane : panes) {
                if (!pane.isValid() || pane.getWorld() != owner.getWorld()) {
                    remove();
                    return false;
                }
            }
            Location at = anchor(owner);
            for (BlockDisplay pane : panes) {
                pane.teleport(at);
            }
            phase += hurried ? 0.9 : 0.5;
            // Let the opening animation play before the first beat.
            if (++age > WING_OPEN_TICKS && tick % FLAP_STEP_TICKS == 0) {
                double sweep = SWEEP_REST + SWEEP_AMPLITUDE * Math.sin(phase);
                int index = 0;
                for (boolean left : new boolean[] {true, false}) {
                    for (boolean upper : new boolean[] {true, false}) {
                        Displays.animate(panes[index++], pane(left, upper, sweep), FLAP_STEP_TICKS);
                    }
                }
                sparkleTips(owner, at, sweep);
            }
            return true;
        }

        /** Glitter shed from the two upper wing tips. */
        private void sparkleTips(Player owner, Location at, double sweep) {
            double yaw = Math.toRadians(at.getYaw());
            for (boolean left : new boolean[] {true, false}) {
                Transformation t = pane(left, true, sweep);
                Vector3f tip = t.getLeftRotation().transform(new Vector3f(t.getScale().x(), t.getScale().y(), 0))
                        .add(t.getTranslation());
                // Body space to world space: the display's yaw rotation (local +Z = facing).
                double x = tip.x() * Math.cos(yaw) - tip.z() * Math.sin(yaw);
                double z = tip.x() * Math.sin(yaw) + tip.z() * Math.cos(yaw);
                Location point = at.clone().add(x, tip.y(), z);
                Vfx.dot(point, Particle.DUST, left ? SPARK_PINK : SPARK_SKY, false);
                if (ThreadLocalRandom.current().nextInt(4) == 0) {
                    Vfx.dot(point, Particle.WAX_OFF, null, false);
                }
            }
            if (ThreadLocalRandom.current().nextInt(6) == 0) {
                Fx.sound(owner.getLocation(), Sound.ITEM_ELYTRA_FLYING, 0.08f, 2.0f);
            }
        }

        /** Folds the wings into the back and removes them. */
        void fold() {
            for (BlockDisplay pane : panes) {
                if (pane.isValid()) {
                    Displays.shrinkAway(ctx(), player(), pane, WING_FOLD_TICKS);
                }
            }
        }

        void remove() {
            for (BlockDisplay pane : panes) {
                if (pane.isValid()) {
                    pane.remove();
                }
            }
        }

        private Location anchor(Player owner) {
            Location at = owner.getLocation();
            at.setYaw(owner.getBodyYaw());
            at.setPitch(0);
            return at;
        }

        /**
         * One wing pane in body space (local +Z = facing, +X = the owner's left): a thin glass block with its corner
         * at the wing root, rolled outwards and swept back by {@code sweepDegrees}.
         */
        private Transformation pane(boolean left, boolean upper, double sweepDegrees) {
            float length = (upper ? UPPER_LENGTH : LOWER_LENGTH) * size;
            float height = (upper ? UPPER_HEIGHT : LOWER_HEIGHT) * size;
            float roll = (float) Math.toRadians(upper ? UPPER_ROLL : LOWER_ROLL);
            float sweep = (float) Math.toRadians(sweepDegrees);
            Quaternionf rotation = left
                    ? new Quaternionf().rotateY(sweep).rotateZ(roll)
                    : new Quaternionf().rotateY(-sweep).rotateY((float) Math.PI).rotateZ(roll);
            Vector3f root = new Vector3f((left ? 0.05f : -0.05f) * bodyScale, WING_ROOT_HEIGHT * bodyScale,
                    -WING_BACK * bodyScale);
            return new Transformation(root, rotation, new Vector3f(length, height, WING_THICKNESS),
                    new Quaternionf());
        }
    }
}
