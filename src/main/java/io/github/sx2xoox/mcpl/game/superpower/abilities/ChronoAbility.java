package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.function.LongPredicate;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Body;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.LandingGuard;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 시간술사 — time manipulation. Shorter cooldowns, a rewind to where (and how healthy) the owner was a few seconds
 * ago, and a time stop that pins every nearby enemy in place, mid-air included.
 *
 * <p>Look: gold for the present, cyan for the past. The rewind sends three small clocks spiralling backwards into the
 * spot the owner left and retraces the undone path as a cyan thread with ghostly afterimages. The time stop lays a
 * golden clock face on the ground whose hands race during the stop wave and then freeze; every frozen enemy gets a
 * gray-blue stasis shell, a still clock and a countdown.
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
    /** Pale gold core, clock gold body, and the cyan of the past as the edge. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFE08A, 0xF2C14E, 0x6FE3FF);
    /** Gray-blue of stopped time. */
    private static final Color STILL = Color.fromRGB(0xA8, 0xB8, 0xD0);
    private static final Particle.DustTransition ECHO_DUST = new Particle.DustTransition(PALETTE.core(),
            PALETTE.edge(), 0.7f);
    private static final Particle.DustTransition WAVE_DUST = new Particle.DustTransition(PALETTE.body(), STILL, 1.5f);
    private static final Particle.DustTransition STILL_DUST = new Particle.DustTransition(STILL, PALETTE.edge(), 0.9f);
    private static final Particle.DustOptions PAST_DUST = new Particle.DustOptions(PALETTE.edge(), 0.8f);

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
    private static final double LABEL_OFFSET = 1.05;
    private static final double CLOCK_OFFSET = 0.45;
    /** At most this many frozen enemies also get a still clock over their head (display budget). */
    private static final int MAX_STILL_CLOCKS = 5;

    // Visuals
    private static final long RETRACE_TICKS = 8;
    private static final long CLOCK_SPIRAL_TICKS = 10;
    private static final double DIAL_RADIUS = 2.6;
    /** Degrees per tick the dial's hands race while the stop wave spreads. */
    private static final float MINUTE_SPIN = 45;
    private static final float HOUR_SPIN = 12;

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

    /**
     * A faint gold-to-cyan ghost where a rewind would take the owner: a slowly turning ring at the feet and a thin
     * spine of motes, visible to the owner only and only while the rewind is ready.
     */
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
        double phase = tick * 0.08;
        int ring = Vfx.n(ctx(), 6);
        for (int i = 0; i < ring; i++) {
            double angle = phase + i * (Math.PI * 2 / ring);
            owner.spawnParticle(Particle.DUST_COLOR_TRANSITION, echo.x() + Math.cos(angle) * 0.35, echo.y() + 0.08,
                    echo.z() + Math.sin(angle) * 0.35, 1, 0, 0, 0, 0, ECHO_DUST);
        }
        for (double h = 0.35; h < 1.9; h += 0.5) {
            owner.spawnParticle(Particle.DUST_COLOR_TRANSITION, echo.x(), echo.y() + h, echo.z(), 1, 0.06, 0.04, 0.06,
                    0, ECHO_DUST);
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
        Location to = feetOf(moment);
        to.setYaw(from.getYaw());
        to.setPitch(from.getPitch());
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
        rewindEffects(owner, from, to, path, restored > 0.05);
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
            if (Spots.isSafe(ctx(), feetOf(moment), body)) {
                return moment;
            }
        }
        return null;
    }

    /** Where a rewind to {@code moment} puts the feet: the recorded spot, {@link #onSurface}. */
    private static Location feetOf(Moment moment) {
        return onSurface(new Location(moment.world(), moment.x(), moment.y(), moment.z()));
    }

    /**
     * {@code feet} (in place) lifted out of the floor if they reach a hair into it, so a replayed position stands
     * exactly on the surface: a body put even a few thousandths of a block into the ground sinks on the client. Feet
     * in the air (mid-jump) are left alone.
     */
    private static Location onSurface(Location feet) {
        Spots.snapToGround(feet.getWorld(), feet, 0);
        return feet;
    }

    private void rewindEffects(Player owner, Location from, Location to, List<Moment> path, boolean healed) {
        World world = from.getWorld();
        double height = owner.getHeight();
        Location fromBody = flat(from.clone().add(0, height * 0.5, 0));

        // Departure: three little clocks spiral backwards into the spot the owner just left.
        clockSpiral(fromBody);
        Vfx.cloud(ctx(), fromBody, Particle.ENCHANT, 24, 0.35, 0.8, null, false);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, fromBody, Vfx.n(ctx(), 10), 0.3, 0.6, 0.3, 0,
                PALETTE.glow(1.0f));

        // The undone path, retraced from the present back into the past.
        retrace(owner, world, path, height);

        // Arrival: a gold ripple over the ground and a flash; a rising spiral when health came back too.
        Location feet = flat(to.clone().add(0, 0.06, 0));
        Location toBody = feet.clone().add(0, height * 0.5, 0);
        Vfx.shockwave(ctx(), owner, feet, 0.3, 1.4, 5, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.0f), false);
        Vfx.burst(ctx(), toBody, PALETTE, 0.6, Particle.END_ROD, false);
        if (healed) {
            Vfx.helix(ctx(), feet, 0.55, height, 1.5, 2, 12, 0, Particle.DUST, PALETTE.dust(0.9f), false);
            Vfx.cloud(ctx(), toBody.clone().add(0, 0.5, 0), Particle.HEART, 2, 0.3, 0, null, false);
        }

        Fx.sound(from, Sound.BLOCK_BEACON_POWER_SELECT, 0.8f, 1.8f);
        Fx.sound(from, Sound.ITEM_BOOK_PAGE_TURN, 1.0f, 1.6f + Fx.jitter(0.1f));
        Fx.sound(to, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.9f, 1.4f + Fx.jitter(0.05f));
        Fx.sound(to, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.7f, 1.8f + Fx.jitter(0.1f));
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

    /**
     * Draws the undone path over {@link #RETRACE_TICKS}, newest moment first: a gold-to-cyan thread with motes
     * running backwards along it, a bright head and a ghostly afterimage every few steps.
     */
    private void retrace(Player owner, World world, List<Moment> path, double height) {
        List<Location> points = new ArrayList<>();
        for (int i = path.size() - 1; i >= 0; i--) {
            Moment m = path.get(i);
            if (m.world().equals(world)) {
                points.add(new Location(world, m.x(), m.y() + height * 0.5, m.z()));
            }
        }
        if (points.isEmpty()) {
            return;
        }
        int perTick = (int) Math.max(1, Math.ceil(points.size() / (double) RETRACE_TICKS));
        Loop.repeat(ctx(), owner, 0, 1, RETRACE_TICKS + 1, step -> {
            int start = (int) step * perTick;
            int end = Math.min(points.size(), start + perTick);
            for (int i = start; i < end; i++) {
                Location at = points.get(i);
                Vfx.dot(at, Particle.DUST_COLOR_TRANSITION, PALETTE.fade(1.1f), false);
                if (i + 1 < points.size()) {
                    Vfx.trail(at, points.get(i + 1), PALETTE.edge(), 3, false);
                }
                if (i % 6 == 3) {
                    // An afterimage: a faint cyan figure standing where the owner was.
                    for (double h = -height * 0.45; h <= height * 0.45; h += height * 0.3) {
                        Vfx.dot(at.clone().add(0, h, 0), Particle.DUST, PAST_DUST, false);
                    }
                }
            }
            if (start < end) {
                Vfx.dot(points.get(end - 1), Particle.END_ROD, null, false);
            }
            return end < points.size();
        });
    }

    /** Three small clocks orbiting {@code center} backwards while spiralling into it, then gone. */
    private void clockSpiral(Location center) {
        Player owner = player();
        List<ItemDisplay> clocks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ItemDisplay clock = Displays.item(ctx(), owner, spiralPoint(center, i, 0), ItemStack.of(Material.CLOCK),
                    Displays.centered(0, 0, 0, 0.45f, 0.45f, 0.45f, 0, 0, 0), CLOCK_SPIRAL_TICKS + 6);
            clock.setBillboard(Display.Billboard.CENTER);
            clocks.add(clock);
        }
        Loop.repeat(ctx(), owner, 1, 1, CLOCK_SPIRAL_TICKS, step -> {
            for (int i = 0; i < clocks.size(); i++) {
                ItemDisplay clock = clocks.get(i);
                if (clock.isValid()) {
                    clock.teleport(spiralPoint(center, i, step + 1));
                    // Turning counter-clockwise: time runs backwards.
                    Displays.animate(clock, Displays.centered(0, 0, 0, 0.45f, 0.45f, 0.45f, 0, 0, (step + 1) * 40f), 1);
                }
            }
            return true;
        }, () -> clocks.forEach(clock -> {
            if (clock.isValid()) {
                Displays.shrinkAway(ctx(), owner, clock, 4);
            }
        }));
    }

    private static Location spiralPoint(Location center, int index, long step) {
        double t = Math.min(1.0, step / (double) CLOCK_SPIRAL_TICKS);
        double radius = 0.9 * (1 - t) + 0.1;
        double angle = index * (Math.PI * 2 / 3) - step * 0.45;
        return center.clone().add(Math.cos(angle) * radius, 0.35 - t * 0.3, Math.sin(angle) * radius);
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
            frozen.add(freeze(enemy, duration, frozen.size() < MAX_STILL_CLOCKS));
        }
        Dial dial = new Dial(origin, duration + 14);

        Fx.sound(origin, Sound.BLOCK_BELL_RESONATE, 1.6f, 0.7f);
        Fx.sound(origin, Sound.BLOCK_BEACON_DEACTIVATE, 1.6f, 0.5f);
        Fx.sound(origin, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 1.2f, 0.6f);
        Fx.sound(origin, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 1.0f, 0.6f + Fx.jitter(0.05f));
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">⏸ 시간 정지</" + COLOR_TAG + "> <white>" + frozen.size()
                + "명</white>"));
        Loop.repeat(ctx(), player(), 0, 1, duration, new Stasis(frozen, dial, origin, radius, duration));
        return ActivationResult.success();
    }

    private Frozen freeze(LivingEntity enemy, long duration, boolean withClock) {
        Player owner = player();
        // Timed modifiers: they expire on their own even if the owner dies mid-stop.
        List<NamespacedKey> modifiers = new ArrayList<>();
        stop(owner, enemy, Attribute.MOVEMENT_SPEED, -1, AttributeModifier.Operation.MULTIPLY_SCALAR_1, duration,
                modifiers);
        stop(owner, enemy, Attribute.JUMP_STRENGTH, -1, AttributeModifier.Operation.MULTIPLY_SCALAR_1, duration,
                modifiers);
        stop(owner, enemy, Attribute.GRAVITY, -1, AttributeModifier.Operation.MULTIPLY_SCALAR_1, duration, modifiers);
        stop(owner, enemy, Attribute.KNOCKBACK_RESISTANCE, 1, AttributeModifier.Operation.ADD_NUMBER, duration,
                modifiers);
        enemy.setVelocity(new Vector());

        // The moment it stops: a pale flash and a gray-blue shell of motes hanging around the body.
        Location pin = onSurface(enemy.getLocation());
        World world = pin.getWorld();
        Location body = flat(pin.clone().add(0, enemy.getHeight() * 0.5, 0));
        world.spawnParticle(Particle.FLASH, body, 1, 0, 0, 0, 0, PALETTE.core(), true);
        Vfx.sphere(ctx(), body, Math.max(0.8, enemy.getHeight() * 0.55), 28, Particle.DUST_COLOR_TRANSITION,
                STILL_DUST, true);

        TextDisplay label = Displays.text(ctx(), owner, labelSpot(enemy), labelText(duration), 1.0f, duration + 10);
        label.setBackgroundColor(Color.fromARGB(110, 40, 28, 0));
        ItemDisplay clock = null;
        if (withClock) {
            // A clock that stopped: it hangs over the head without turning.
            clock = Displays.item(ctx(), owner, clockSpot(enemy), ItemStack.of(Material.CLOCK),
                    Displays.centered(0, 0, 0, 0.01f, 0.01f, 0.01f, 0, 0, 0), duration + 10);
            clock.setBillboard(Display.Billboard.CENTER);
            Displays.animateNextTick(ctx(), owner, clock, Displays.centered(0, 0, 0, 0.5f, 0.5f, 0.5f, 0, 0, -20), 4);
        }
        if (enemy instanceof Player victim) {
            // Frozen time: no skills either.
            ctx().silence(victim, duration);
            victim.playSound(victim, Sound.BLOCK_BEACON_DEACTIVATE, SoundCategory.PLAYERS, 0.8f, 0.6f);
        }
        return new Frozen(enemy, pin, label, clock, modifiers);
    }

    private void stop(Player owner, LivingEntity enemy, Attribute attribute, double amount,
                      AttributeModifier.Operation operation, long duration, List<NamespacedKey> keys) {
        if (enemy.getAttribute(attribute) != null) {
            keys.add(ctx().addModifier(owner, enemy, attribute, amount, operation, duration));
        }
    }

    private record Frozen(LivingEntity entity, Location pin, TextDisplay label, @Nullable ItemDisplay clock,
                          List<NamespacedKey> modifiers) {

        /** Removes the label and the still clock at once. */
        void removeDisplays() {
            if (label.isValid()) {
                label.remove();
            }
            if (clock != null && clock.isValid()) {
                clock.remove();
            }
        }
    }

    /** Holds every frozen enemy in place, draws the wave and countdowns, and resumes time at the end. */
    private final class Stasis implements LongPredicate {

        private final List<Frozen> frozen;
        private final Dial dial;
        private final Location origin;
        private final double radius;
        private final long duration;

        Stasis(List<Frozen> frozen, Dial dial, Location origin, double radius, long duration) {
            this.frozen = frozen;
            this.dial = dial;
            this.origin = origin.clone();
            this.radius = radius;
            this.duration = duration;
        }

        @Override
        public boolean test(long tick) {
            if (tick < WAVE_TICKS) {
                drawWave(tick);
                // The dial's hands race while the wave spreads, then stop dead with everything else.
                dial.turn(MINUTE_SPIN, HOUR_SPIN);
            }
            long left = duration - tick;
            Iterator<Frozen> it = frozen.iterator();
            while (it.hasNext()) {
                Frozen f = it.next();
                LivingEntity entity = f.entity();
                if (!Combat.isTargetable(ctx(), player(), entity)) {
                    // Killed (and maybe already back as a spectator), or no longer an enemy: let go for good, so the
                    // pin, the shell and the messages never touch it again.
                    release(f);
                    it.remove();
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
         * Puts a displaced enemy back on its pin (if the spot is still free, with the body not reaching into the
         * floor, and inside the arena) and keeps the label and the clock above it.
         */
        private void hold(Frozen f) {
            LivingEntity entity = f.entity();
            Location now = entity.getLocation();
            if (now.getWorld().equals(f.pin().getWorld())
                    && now.distanceSquared(f.pin()) > PIN_TOLERANCE * PIN_TOLERANCE && ctx().isInsideArena(f.pin())) {
                Location back = f.pin().clone();
                back.setYaw(now.getYaw());
                back.setPitch(now.getPitch());
                BoundingBox box = entity.getBoundingBox();
                if (Spots.isFree(now.getWorld(), back, box.getWidthX(), box.getHeight())
                        && entity.teleport(back, TeleportCause.PLUGIN)) {
                    entity.setVelocity(new Vector());
                }
            }
            if (f.label().isValid() && f.label().getWorld().equals(entity.getWorld())) {
                f.label().teleport(labelSpot(entity));
            }
            ItemDisplay clock = f.clock();
            if (clock != null && clock.isValid() && clock.getWorld().equals(entity.getWorld())) {
                clock.teleport(clockSpot(entity));
            }
        }

        /** The stop wave: a gold ring turning gray-blue as it runs out, with a cyan ring trailing it. */
        private void drawWave(long tick) {
            Location ground = flat(origin.clone().add(0, 0.3, 0));
            double r = radius * (tick + 1) / (double) WAVE_TICKS;
            int points = Math.clamp(Math.round(r * 5), 12, 80);
            Vfx.ring(ctx(), ground, r, points, tick * 0.1, Particle.DUST_COLOR_TRANSITION, WAVE_DUST, true);
            double trailing = r - radius / WAVE_TICKS * 0.6;
            if (trailing > 0.5) {
                Vfx.ring(ctx(), ground.clone().add(0, 0.25, 0), trailing, points / 2, tick * 0.1 + 0.2, Particle.DUST,
                        PAST_DUST, true);
            }
        }

        /** Gray-blue rings of hanging motes at the frozen enemy's feet and head, turning very slowly. */
        private void drawStillness(LivingEntity entity, long tick) {
            double r = Math.max(0.5, entity.getWidth() * 0.9);
            Location feet = flat(entity.getLocation().add(0, 0.1, 0));
            Vfx.ring(ctx(), feet, r, 10, tick * 0.03, Particle.DUST_COLOR_TRANSITION, STILL_DUST, false);
            Vfx.ring(ctx(), feet.clone().add(0, entity.getHeight(), 0), r * 0.7, 8, -tick * 0.03, Particle.DUST,
                    PALETTE.dust(0.7f), false);
        }

        /** Removes the displays and the freeze of an entity that dropped out of the stop early. */
        private void release(Frozen f) {
            f.removeDisplays();
            for (NamespacedKey key : f.modifiers()) {
                ctx().removeModifier(f.entity(), key);
            }
        }

        private void resume() {
            for (Frozen f : frozen) {
                LivingEntity entity = f.entity();
                if (!Combat.isTargetable(ctx(), player(), entity)) {
                    f.removeDisplays();
                    continue;
                }
                if (f.label().isValid()) {
                    f.label().remove();
                }
                // The still clock starts again: one quick turn as it shrinks away.
                ItemDisplay clock = f.clock();
                if (clock != null && clock.isValid()) {
                    Displays.animate(clock, Displays.centered(0, 0, 0, 0.55f, 0.55f, 0.55f, 0, 0, 140), 3);
                    ctx().runLater(player(), 3, () -> {
                        if (clock.isValid()) {
                            Displays.shrinkAway(ctx(), player(), clock, 4);
                        }
                    });
                }
                // Time flows again: the shell breaks into light.
                Location body = flat(entity.getLocation().add(0, entity.getHeight() * 0.5, 0));
                Vfx.cloud(ctx(), body, Particle.END_ROD, 8, 0.3, 0.06, null, true);
                Vfx.ring(ctx(), body, 0.8, 14, 0, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.0f), true);
                if (entity instanceof Player victim) {
                    victim.sendActionBar(Text.mm("<gray>시간이 다시 흐릅니다</gray>"));
                }
            }
            dial.release();
            Fx.sound(origin, Sound.BLOCK_BEACON_ACTIVATE, 1.4f, 1.6f);
            Fx.sound(origin, Sound.BLOCK_BELL_USE, 1.2f, 1.4f);
            Fx.sound(origin, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.2f, 1.2f + Fx.jitter(0.1f));
        }
    }

    // ------------------------------------------------------------------ the clock dial

    /**
     * A golden clock face laid on the ground where the time stop was cast: twelve hour marks (gold at 12, 3, 6 and 9,
     * cyan glass between) and two gold hands turning around the centre. Fourteen displays.
     */
    private final class Dial {

        private final List<BlockDisplay> parts = new ArrayList<>();
        private final Location center;
        private final BlockDisplay minuteHand;
        private final BlockDisplay hourHand;
        private float minuteAngle;
        private float hourAngle = 120;

        Dial(Location origin, long lifetime) {
            Player owner = player();
            center = flat(origin.clone().add(0, 0.02, 0));
            for (int i = 0; i < 12; i++) {
                boolean major = i % 3 == 0;
                double angle = i * 30;
                double rad = Math.toRadians(angle);
                Location at = center.clone().add(Math.cos(rad) * DIAL_RADIUS, 0, Math.sin(rad) * DIAL_RADIUS);
                float width = major ? 0.16f : 0.08f;
                float length = major ? 0.7f : 0.38f;
                Material material = major ? Material.GOLD_BLOCK : Material.LIGHT_BLUE_STAINED_GLASS;
                float yaw = (float) angle - 90;
                BlockDisplay mark = Displays.block(ctx(), owner, at, material.createBlockData(),
                        Displays.transform(0, 0.02f, 0, 0.001f, 0.03f, 0.001f, yaw, 0, 0), lifetime);
                Displays.animateNextTick(ctx(), owner, mark,
                        Displays.transform(0, 0.02f, 0, width, 0.03f, length, yaw, 0, 0), 4);
                parts.add(mark);
            }
            minuteHand = hand(owner, 2.2f, 0.09f, 0.05f, minuteAngle, lifetime);
            hourHand = hand(owner, 1.4f, 0.14f, 0.08f, hourAngle, lifetime);
            Location centerAt = center.clone().add(0, 0.06, 0);
            Vfx.burst(ctx(), centerAt, PALETTE, 0.6, Particle.END_ROD, true);
        }

        private BlockDisplay hand(Player owner, float length, float width, float lift, float angle, long lifetime) {
            BlockDisplay hand = Displays.block(ctx(), owner, center, Material.GOLD_BLOCK.createBlockData(),
                    handShape(0.001f, width, lift, angle), lifetime);
            Displays.animateNextTick(ctx(), owner, hand, handShape(length, width, lift, angle), 4);
            parts.add(hand);
            return hand;
        }

        /** Advances the hands by the given degrees, interpolated over one tick. */
        void turn(float minuteDegrees, float hourDegrees) {
            minuteAngle += minuteDegrees;
            hourAngle += hourDegrees;
            Displays.animate(minuteHand, handShape(2.2f, 0.09f, 0.05f, minuteAngle), 1);
            Displays.animate(hourHand, handShape(1.4f, 0.14f, 0.08f, hourAngle), 1);
        }

        /** Time flows again: the hands give a last jolt and the whole face sinks away. */
        void release() {
            turn(MINUTE_SPIN, HOUR_SPIN);
            Player owner = player();
            ctx().runLater(owner, 2, () -> {
                for (BlockDisplay part : parts) {
                    if (part.isValid()) {
                        Displays.shrinkAway(ctx(), owner, part, 6);
                    }
                }
            });
            Vfx.ring(ctx(), center.clone().add(0, 0.1, 0), DIAL_RADIUS, 36, 0, Particle.DUST_COLOR_TRANSITION,
                    PALETTE.glow(1.2f), true);
        }

        /** A flat bar from the centre outwards, {@code angle} degrees around the dial (clockwise seen from above). */
        private static Transformation handShape(float length, float width, float lift, float angle) {
            Quaternionf rotation = new Quaternionf().rotateY((float) Math.toRadians(-angle));
            Vector3f scale = new Vector3f(width, 0.03f, length);
            Vector3f pivot = new Vector3f(0.5f, 0, 0).mul(scale).rotate(rotation);
            return new Transformation(pivot.negate().add(0, lift, 0), rotation, scale, new Quaternionf());
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Location labelSpot(LivingEntity entity) {
        return flat(entity.getLocation().add(0, entity.getHeight() + LABEL_OFFSET, 0));
    }

    private static Location clockSpot(LivingEntity entity) {
        return flat(entity.getLocation().add(0, entity.getHeight() + CLOCK_OFFSET, 0));
    }

    private static Component labelText(long ticksLeft) {
        return Text.mm("<" + COLOR_TAG + ">⏸</" + COLOR_TAG + "> <white>" + Fx.seconds(ticksLeft) + "</white>");
    }

    /** A copy without yaw and pitch, so display entities are not turned by the entity rotation as well. */
    private static Location flat(Location location) {
        Location copy = location.clone();
        copy.setYaw(0);
        copy.setPitch(0);
        return copy;
    }
}
