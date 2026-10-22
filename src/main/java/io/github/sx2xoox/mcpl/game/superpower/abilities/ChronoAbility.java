package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Body;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.LandingGuard;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 시간술사 — time manipulation. Shorter cooldowns, a rewind to where (and how healthy) the owner was a few seconds
 * ago, and a time stop that pins every nearby enemy in place, mid-air included.
 */
public final class ChronoAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("chrono", "시간술사", Material.CLOCK)
            .color(TextColor.color(0xF2C14E))
            .difficulty(2)
            .passive("가속된 시간",
                    "액티브 쿨타임이 {cooldown_reduction}% 줄어듭니다. 되감기를 쓸 수 있으면 {rewind_seconds}초 전의 "
                            + "내 위치가 나에게만 희미하게 보입니다.")
            .active("되감기",
                    "{rewind_seconds}초 전의 위치로 돌아갑니다. 그때의 체력이 지금보다 높았다면 체력도 되돌립니다.",
                    12)
            .ultimate("시간 정지",
                    "반경 {ult_radius}블록의 모든 적의 시간을 {ult_duration}초간 멈춥니다. 멈춘 적은 움직이거나 뛸 수 없고 "
                            + "능력도 쓸 수 없으며(침묵), 밀려나지도 않고, 공중에 있었다면 그대로 떠 있습니다.")
            .param("cooldown_reduction", 20)
            .param("rewind_seconds", 3)
            .param("ult_radius", 15)
            .param("ult_duration", 3)
            .build();

    private static final String COLOR_TAG = "#F2C14E";
    private static final Color CHRONO_GOLD = Color.fromRGB(0xF2, 0xC1, 0x4E);
    private static final Color PAST_BLUE = Color.fromRGB(0x6F, 0xC8, 0xFF);
    private static final Color STILL_GRAY = Color.fromRGB(0xB8, 0xB8, 0xC4);
    private static final Particle.DustOptions ECHO_DUST = new Particle.DustOptions(CHRONO_GOLD, 0.7f);
    private static final Particle.DustOptions FROZEN_DUST = new Particle.DustOptions(CHRONO_GOLD, 0.9f);
    private static final Particle.DustTransition REWIND_DUST =
            new Particle.DustTransition(CHRONO_GOLD, PAST_BLUE, 1.0f);
    private static final Particle.DustTransition WAVE_DUST = new Particle.DustTransition(CHRONO_GOLD, STILL_GRAY, 1.4f);

    // Rewind history
    private static final long RECORD_INTERVAL = 2;
    /** Extra history kept beyond the rewind window (ticks). */
    private static final long HISTORY_SLACK = 10;
    private static final long ECHO_INTERVAL = 5;
    /** The echo is only drawn when it is at least this far away (no clutter while standing still). */
    private static final double ECHO_MIN_DISTANCE = 1.5;
    private static final long LANDING_GRACE_TICKS = 100;

    // Time stop
    private static final long WAVE_TICKS = 8;
    /** A frozen enemy displaced further than this (by anything) is put back. */
    private static final double PIN_TOLERANCE = 0.6;
    private static final double LABEL_OFFSET = 0.9;
    private static final int FROZEN_RING_POINTS = 8;

    /** Where the owner was, sampled every {@link #RECORD_INTERVAL} ticks, oldest first. */
    private final Deque<Moment> history = new ArrayDeque<>();
    /** Cancels the fall damage of the landing after a rewind that ended in mid-air. */
    private final LandingGuard landing = new LandingGuard();

    private record Moment(long tick, World world, double x, double y, double z, double health) {
    }

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    // ------------------------------------------------------------------ passive: hastened time + echo

    @Override
    public double cooldownMultiplier() {
        return Math.clamp(1.0 - params().num("cooldown_reduction") / 100.0, 0.1, 1.0);
    }

    @Override
    public void onTick(long tick) {
        if (tick % RECORD_INTERVAL == 0) {
            record(tick);
            landing.update(player(), tick);
        }
        if (tick % ECHO_INTERVAL == 0) {
            drawEcho(tick);
        }
    }

    private void record(long tick) {
        Player owner = player();
        Location at = owner.getLocation();
        history.addLast(new Moment(tick, at.getWorld(), at.getX(), at.getY(), at.getZ(), owner.getHealth()));
        long keep = params().ticks("rewind_seconds") + HISTORY_SLACK;
        while (!history.isEmpty() && tick - history.peekFirst().tick() > keep) {
            history.removeFirst();
        }
    }

    /** A faint golden silhouette where a rewind would take the owner, visible to the owner only while it is ready. */
    private void drawEcho(long tick) {
        if (ctx().cooldownRemaining(player()) > 0) {
            return;
        }
        Moment echo = momentAt(tick - params().ticks("rewind_seconds"));
        Player owner = player();
        if (echo == null || !echo.world().equals(owner.getWorld())) {
            return;
        }
        Location now = owner.getLocation();
        double dx = echo.x() - now.getX();
        double dy = echo.y() - now.getY();
        double dz = echo.z() - now.getZ();
        if (dx * dx + dy * dy + dz * dz < ECHO_MIN_DISTANCE * ECHO_MIN_DISTANCE) {
            return;
        }
        for (double h = 0.2; h < 1.9; h += 0.55) {
            owner.spawnParticle(Particle.DUST, echo.x(), echo.y() + h, echo.z(), 1, 0.08, 0.05, 0.08, 0, ECHO_DUST);
        }
    }

    /** The oldest recorded moment at or after {@code tick} (the newest one if all are older). */
    private @Nullable Moment momentAt(long tick) {
        Moment found = null;
        for (Moment moment : history) {
            found = moment;
            if (moment.tick() >= tick) {
                break;
            }
        }
        return found;
    }

    // ------------------------------------------------------------------ active: rewind

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        if (owner.isInsideVehicle()) {
            return ActivationResult.fail("탈것에 탄 채로는 되감을 수 없습니다");
        }
        if (history.isEmpty()) {
            return ActivationResult.fail("아직 되감을 시간이 없습니다");
        }
        long target = ctx().currentTick() - params().ticks("rewind_seconds");
        Moment moment = safestMomentNear(owner, target);
        if (moment == null) {
            return ActivationResult.fail("되돌아갈 자리가 막혀 있습니다");
        }
        Location from = owner.getLocation();
        Location to = new Location(moment.world(), moment.x(), moment.y(), moment.z(), from.getYaw(), from.getPitch());
        double health = owner.getHealth();
        if (to.distanceSquared(from) < 0.5 * 0.5 && moment.health() <= health) {
            return ActivationResult.fail("되감아도 달라지는 것이 없습니다");
        }

        List<Moment> path = new ArrayList<>();
        for (Moment m : history) {
            if (m.tick() >= moment.tick()) {
                path.add(m);
            }
        }
        if (!owner.teleport(to, TeleportCause.PLUGIN)) {
            return ActivationResult.fail("되감기가 막혔습니다");
        }
        owner.setFallDistance(0);
        // Rewinding into the middle of a jump must not end in fall damage.
        landing.armIfAirborne(owner, ctx().currentTick(), LANDING_GRACE_TICKS);

        double restored = 0;
        if (moment.health() > health) {
            restored = moment.health() - health;
            ctx().heal(owner, restored);
        }
        rewindEffects(owner, from, to, path);
        history.clear();

        String suffix = restored > 0.05
                ? " <green>+" + AbilityInfo.formatNumber(Math.round(restored * 10) / 10.0) + " 체력</green>" : "";
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">⟲ 되감기</" + COLOR_TAG + ">" + suffix));
        return ActivationResult.success();
    }

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        landing.absorb(event, ctx().currentTick());
    }

    /**
     * The recorded moment closest to {@code target} (older first on ties) whose spot is still free and safe
     * ({@link Spots#isSafe}: no blocks or hazards, no void, inside the arena).
     */
    private @Nullable Moment safestMomentNear(Player owner, long target) {
        List<Moment> candidates = new ArrayList<>(history);
        candidates.sort(Comparator.comparingLong((Moment m) -> Math.abs(m.tick() - target))
                .thenComparingLong(Moment::tick));
        Body body = Body.standing(owner);
        for (Moment moment : candidates) {
            if (!moment.world().equals(owner.getWorld())) {
                continue;
            }
            Location feet = new Location(moment.world(), moment.x(), moment.y(), moment.z());
            if (Spots.isSafe(ctx(), feet, body)) {
                return moment;
            }
        }
        return null;
    }

    private void rewindEffects(Player owner, Location from, Location to, List<Moment> path) {
        World world = from.getWorld();
        double half = owner.getHeight() * 0.5;
        Location fromBody = from.clone().add(0, half, 0);
        world.spawnParticle(Particle.ENCHANT, fromBody, 30, 0.3, 0.6, 0.3, 0.8);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, fromBody, 12, 0.3, 0.6, 0.3, 0, REWIND_DUST);
        // The undone path, drawn as a golden thread fading into the past.
        for (Moment m : path) {
            if (m.world().equals(world)) {
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, m.x(), m.y() + half, m.z(), 1, 0.05, 0.05, 0.05,
                        0, REWIND_DUST);
            }
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int duration = Math.clamp(Math.round(from.distance(to) * 0.8), 4, 14);
        for (int i = 0; i < 10; i++) {
            double ox = (random.nextDouble() - 0.5) * 0.6;
            double oy = random.nextDouble() * owner.getHeight();
            double oz = (random.nextDouble() - 0.5) * 0.6;
            Color color = i % 2 == 0 ? CHRONO_GOLD : PAST_BLUE;
            world.spawnParticle(Particle.TRAIL, from.clone().add(ox, oy, oz), 1, 0, 0, 0, 0,
                    new Particle.Trail(to.clone().add(ox, oy, oz), color, duration));
        }
        Location toBody = to.clone().add(0, half, 0);
        world.spawnParticle(Particle.END_ROD, toBody, 12, 0.3, 0.6, 0.3, 0.03);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, toBody, 12, 0.3, 0.6, 0.3, 0, REWIND_DUST);

        Fx.sound(from, Sound.BLOCK_BEACON_POWER_SELECT, 0.8f, 1.8f);
        Fx.sound(to, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.9f, 1.4f + Fx.jitter(0.05f));
        // A quick descending tick-tock, like a tape being rewound.
        for (int i = 1; i <= 3; i++) {
            float pitch = 1.9f - i * 0.3f;
            ctx().runLater(owner, i * 2L, () -> {
                if (owner.isOnline() && ctx().isAlive(owner)) {
                    Fx.sound(owner.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f, pitch);
                }
            });
        }
    }

    // ------------------------------------------------------------------ ultimate: time stop

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        double radius = params().num("ult_radius");
        Location origin = owner.getLocation();
        List<LivingEntity> enemies = ctx().enemiesNear(owner, origin, radius);
        if (enemies.isEmpty()) {
            return ActivationResult.fail("반경 " + AbilityInfo.formatNumber(radius) + "블록 안에 적이 없습니다");
        }
        long duration = Math.max(1, params().ticks("ult_duration"));
        List<Frozen> frozen = new ArrayList<>();
        for (LivingEntity enemy : enemies) {
            frozen.add(freeze(enemy, duration));
        }

        Fx.sound(origin, Sound.BLOCK_BELL_RESONATE, 1.6f, 0.7f);
        Fx.sound(origin, Sound.BLOCK_BEACON_DEACTIVATE, 1.6f, 0.5f);
        Fx.sound(origin, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 1.2f, 0.6f);
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">⏸ 시간 정지</" + COLOR_TAG + "> <white>" + frozen.size()
                + "명</white>"));
        Loop.repeat(ctx(), player(), 0, 1, duration, new Stasis(frozen, origin, radius, duration));
        return ActivationResult.success();
    }

    private Frozen freeze(LivingEntity enemy, long duration) {
        Player owner = player();
        // Timed modifiers: they expire on their own even if the owner dies mid-stop.
        stop(owner, enemy, Attribute.MOVEMENT_SPEED, -1, AttributeModifier.Operation.MULTIPLY_SCALAR_1, duration);
        stop(owner, enemy, Attribute.JUMP_STRENGTH, -1, AttributeModifier.Operation.MULTIPLY_SCALAR_1, duration);
        stop(owner, enemy, Attribute.GRAVITY, -1, AttributeModifier.Operation.MULTIPLY_SCALAR_1, duration);
        stop(owner, enemy, Attribute.KNOCKBACK_RESISTANCE, 1, AttributeModifier.Operation.ADD_NUMBER, duration);
        enemy.setVelocity(new Vector());

        Location pin = enemy.getLocation();
        World world = pin.getWorld();
        Location body = pin.clone().add(0, enemy.getHeight() * 0.5, 0);
        world.spawnParticle(Particle.FLASH, body, 1, 0, 0, 0, 0, CHRONO_GOLD);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, body, 16, 0.4, 0.6, 0.4, 0, WAVE_DUST);
        TextDisplay label = ctx().track(owner, world.spawn(labelSpot(enemy), TextDisplay.class, display -> {
            display.text(labelText(duration));
            display.setBillboard(Display.Billboard.CENTER);
            display.setTeleportDuration(2);
            display.setPersistent(false);
            display.setShadowed(true);
            display.setBackgroundColor(Color.fromARGB(110, 40, 28, 0));
            display.setBrightness(new Display.Brightness(15, 15));
        }));
        if (enemy instanceof Player victim) {
            // Frozen time: no skills either.
            ctx().silence(victim, duration);
            victim.playSound(victim, Sound.BLOCK_BEACON_DEACTIVATE, SoundCategory.PLAYERS, 0.8f, 0.6f);
        }
        return new Frozen(enemy, pin, label);
    }

    private void stop(Player owner, LivingEntity enemy, Attribute attribute, double amount,
                      AttributeModifier.Operation operation, long duration) {
        if (enemy.getAttribute(attribute) != null) {
            ctx().addModifier(owner, enemy, attribute, amount, operation, duration);
        }
    }

    private record Frozen(LivingEntity entity, Location pin, TextDisplay label) {
    }

    /** Holds every frozen enemy in place, draws the wave and countdowns, and resumes time at the end. */
    private final class Stasis implements LongPredicate {

        private final List<Frozen> frozen;
        private final Location origin;
        private final double radius;
        private final long duration;

        Stasis(List<Frozen> frozen, Location origin, double radius, long duration) {
            this.frozen = frozen;
            this.origin = origin.clone();
            this.radius = radius;
            this.duration = duration;
        }

        @Override
        public boolean test(long tick) {
            if (tick < WAVE_TICKS) {
                drawWave(tick);
            }
            long left = duration - tick;
            for (Frozen f : frozen) {
                LivingEntity entity = f.entity();
                if (!entity.isValid() || entity.isDead()) {
                    if (f.label().isValid()) {
                        f.label().remove();
                    }
                    continue;
                }
                if (tick % 2 == 0) {
                    hold(f);
                }
                if (tick % 5 == 0) {
                    f.label().text(labelText(left));
                    drawStillness(entity, tick);
                }
                if (tick % 10 == 0 && entity instanceof Player victim) {
                    victim.sendActionBar(Text.mm("<" + COLOR_TAG + ">⏸ 시간이 멈췄습니다</" + COLOR_TAG + "> <gray>"
                            + Fx.seconds(left) + "초</gray>"));
                }
            }
            if (tick % 10 == 0) {
                Fx.sound(origin, Sound.BLOCK_NOTE_BLOCK_HAT, 1.2f, (tick / 10) % 2 == 0 ? 0.8f : 0.6f);
            }
            if (tick >= duration - 1) {
                resume();
                return false;
            }
            return true;
        }

        /**
         * Puts a displaced enemy back on its pin (if the spot is still free and inside the arena) and keeps the
         * label above it.
         */
        private void hold(Frozen f) {
            LivingEntity entity = f.entity();
            Location now = entity.getLocation();
            if (now.getWorld().equals(f.pin().getWorld())
                    && now.distanceSquared(f.pin()) > PIN_TOLERANCE * PIN_TOLERANCE && ctx().isInsideArena(f.pin())) {
                Location back = f.pin().clone();
                back.setYaw(now.getYaw());
                back.setPitch(now.getPitch());
                BoundingBox box = entity.getBoundingBox().shift(back.clone().subtract(now)).expand(-0.02);
                if (!now.getWorld().hasCollisionsIn(box) && entity.teleport(back, TeleportCause.PLUGIN)) {
                    entity.setVelocity(new Vector());
                }
            }
            if (f.label().isValid() && f.label().getWorld().equals(entity.getWorld())) {
                f.label().teleport(labelSpot(entity));
            }
        }

        private void drawWave(long tick) {
            World world = origin.getWorld();
            double r = radius * (tick + 1) / (double) WAVE_TICKS;
            int points = Math.clamp(Math.round(r * 5), 12, 72);
            double y = origin.getY() + 0.3;
            for (int i = 0; i < points; i++) {
                double angle = i * (Math.PI * 2 / points);
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, origin.getX() + Math.cos(angle) * r, y,
                        origin.getZ() + Math.sin(angle) * r, 1, 0, 0.05, 0, 0, WAVE_DUST);
            }
        }

        /** A slowly turning golden ring around the frozen enemy, like a clock face that stopped. */
        private void drawStillness(LivingEntity entity, long tick) {
            double r = Math.max(0.5, entity.getWidth() * 0.9);
            Fx.ring(Fx.body(entity), r, FROZEN_RING_POINTS, tick * 0.05, Particle.DUST, FROZEN_DUST);
        }

        private void resume() {
            for (Frozen f : frozen) {
                if (f.label().isValid()) {
                    f.label().remove();
                }
                LivingEntity entity = f.entity();
                if (!entity.isValid() || entity.isDead()) {
                    continue;
                }
                Location body = entity.getLocation().add(0, entity.getHeight() * 0.5, 0);
                body.getWorld().spawnParticle(Particle.END_ROD, body, 8, 0.3, 0.5, 0.3, 0.06);
                if (entity instanceof Player victim) {
                    victim.sendActionBar(Text.mm("<gray>시간이 다시 흐릅니다</gray>"));
                }
            }
            Fx.sound(origin, Sound.BLOCK_BEACON_ACTIVATE, 1.4f, 1.6f);
            Fx.sound(origin, Sound.BLOCK_BELL_USE, 1.2f, 1.4f);
        }
    }

    private static Location labelSpot(LivingEntity entity) {
        return entity.getLocation().add(0, entity.getHeight() + LABEL_OFFSET, 0);
    }

    private static Component labelText(long ticksLeft) {
        return Text.mm("<" + COLOR_TAG + ">⏸</" + COLOR_TAG + "> <white>" + Fx.seconds(ticksLeft) + "</white>");
    }
}
