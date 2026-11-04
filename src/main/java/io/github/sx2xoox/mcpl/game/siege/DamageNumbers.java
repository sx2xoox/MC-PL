package io.github.sx2xoox.mcpl.game.siege;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * Floating damage numbers (docs/siege.md "화면 표시"): a number pops up over whatever was hit, rises and fades within a
 * second. Colours: {@link Kind#NORMAL} white, {@link Kind#ABILITY} yellow, {@link Kind#HEAL} green ({@code +}),
 * {@link Kind#STRUCTURE} orange; a basic attack's combo finisher ({@link Kind#FINISH}) is a bigger white number and its
 * critical hit ({@link Kind#CRITICAL}) a big gold one with {@code !}. Hits on one target of one kind are summed for {@value #AGGREGATE_TICKS} ticks into a
 * single number, so a fast multi-hit ability does not spam. Only players within {@code range} blocks of the target
 * (at the moment it pops) see it. Every display is tracked by the session.
 */
public final class DamageNumbers {

    /** What kind of number (its colour). */
    public enum Kind {
        NORMAL(NamedTextColor.WHITE, 1.0f),
        ABILITY(NamedTextColor.YELLOW, 1.0f),
        HEAL(NamedTextColor.GREEN, 1.0f),
        STRUCTURE(NamedTextColor.GOLD, 1.0f),
        /** The last attack of a basic attack combo. */
        FINISH(NamedTextColor.WHITE, 1.35f),
        /** A critical basic attack hit. */
        CRITICAL(TextColor.color(0xFFB020), 1.6f);

        private final TextColor color;
        private final float scale;

        Kind(TextColor color, float scale) {
            this.color = color;
            this.scale = scale;
        }

        public TextColor color() {
            return color;
        }

        /** Size of the number (1 = usual). */
        public float scale() {
            return scale;
        }

        /** The kind of a hit: ability damage, else a basic attack's accent (finisher, critical), else normal. */
        public static Kind ofHit(boolean ability, BasicAttack.Accent accent) {
            if (ability) {
                return ABILITY;
            }
            return switch (accent) {
                case CRITICAL -> CRITICAL;
                case FINISH -> FINISH;
                case NONE -> NORMAL;
            };
        }
    }

    /** Hits on one target of one kind within this many ticks become one number. */
    public static final int AGGREGATE_TICKS = 3;
    /** Ticks a number stays. */
    public static final int LIFETIME_TICKS = 20;
    private static final int RISE_TICKS = 14;
    private static final Display.Brightness FULL_BRIGHT = new Display.Brightness(15, 15);

    private record Key(UUID target, Kind kind) {
    }

    private static final class Pending {
        double amount;
        Location at;
        Predicate<? super Player> viewers;

        Pending(Location at, Predicate<? super Player> viewers) {
            this.at = at;
            this.viewers = viewers;
        }
    }

    private final GameSession session;
    private final double range;
    private final Map<Key, Pending> pending = new LinkedHashMap<>();
    private ToDoubleFunction<Entity> height = Entity::getHeight;
    private boolean enabled = true;
    private boolean timerStarted;
    private int spawned;

    public DamageNumbers(GameSession session, double range) {
        this.session = session;
        this.range = range;
    }

    /**
     * How tall what is drawn of an entity is (default its hitbox): a creature whose pack model is taller than its
     * vanilla body has its numbers pop over the model, not inside it.
     */
    public void height(ToDoubleFunction<Entity> height) {
        this.height = height;
    }

    /** Off: {@link #show} does nothing. */
    public void enabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            pending.clear();
        }
    }

    /** {@code amount} (health points) on {@code target}: the number pops just above it. */
    public void show(Entity target, double amount, Kind kind) {
        show(target, amount, kind, viewer -> true);
    }

    /**
     * {@code amount} on {@code target}, seen only by nearby viewers {@code viewers} accepts (e.g. not by those from
     * whom a stealthed target is concealed).
     */
    public void show(Entity target, double amount, Kind kind, Predicate<? super Player> viewers) {
        Location top = target.getLocation().add(0, height.applyAsDouble(target) + 0.25, 0);
        show(target.getUniqueId(), top, amount, kind, viewers);
    }

    /** {@code amount} at {@code at}; {@code key} groups hits on the same thing (e.g. a structure's id). */
    public void show(UUID key, Location at, double amount, Kind kind) {
        show(key, at, amount, kind, viewer -> true);
    }

    private void show(UUID key, Location at, double amount, Kind kind, Predicate<? super Player> viewers) {
        if (!enabled || !(amount >= 0.05) || at.getWorld() == null) {
            return;
        }
        Pending entry = pending.computeIfAbsent(new Key(key, kind), k -> new Pending(at.clone(), viewers));
        entry.amount += amount;
        entry.at = at.clone();
        entry.viewers = viewers;
        if (!timerStarted) {
            timerStarted = true;
            session.runTimer(AGGREGATE_TICKS, AGGREGATE_TICKS, this::flush);
        }
    }

    /** Spawns the numbers collected since the last flush. */
    public void flush() {
        if (pending.isEmpty()) {
            return;
        }
        List<Map.Entry<Key, Pending>> due = new ArrayList<>(pending.entrySet());
        pending.clear();
        for (Map.Entry<Key, Pending> entry : due) {
            Pending next = entry.getValue();
            Kind kind = entry.getKey().kind();
            pop(next.at, text(next.amount, kind), kind.scale(), kind == Kind.STRUCTURE, next.viewers);
        }
    }

    /** Numbers waiting for the next flush (tests). */
    public int pending() {
        return pending.size();
    }

    /** Numbers spawned so far (tests). */
    public int spawned() {
        return spawned;
    }

    /** @param seeThrough drawn through blocks and models (a structure's number, by its see-through bar) */
    private void pop(Location at, Component text, float scale, boolean seeThrough,
            Predicate<? super Player> viewers) {
        World world = at.getWorld();
        if (world == null) {
            return;
        }
        double jitter = 0.35;
        Location spot = at.clone().add((session.random().nextDouble() - 0.5) * jitter * 2, 0,
                (session.random().nextDouble() - 0.5) * jitter * 2);
        try {
            TextDisplay display = world.spawn(spot, TextDisplay.class, d -> {
                d.setPersistent(false);
                d.setVisibleByDefault(false);
                d.text(text);
                d.setBillboard(Display.Billboard.CENTER);
                d.setShadowed(true);
                d.setSeeThrough(seeThrough);
                d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                d.setBrightness(FULL_BRIGHT);
                d.setTransformation(transform(0, 0.6f * scale));
            });
            session.track(display);
            spawned++;
            double max = range * range;
            for (Player viewer : session.viewers()) {
                if (viewer.getWorld().equals(world) && viewer.getLocation().distanceSquared(spot) <= max
                        && viewers.test(viewer)) {
                    viewer.showEntity(session.plugin(), display);
                }
            }
            session.runLater(1, () -> {
                if (display.isValid()) {
                    display.setInterpolationDelay(0);
                    display.setInterpolationDuration(RISE_TICKS);
                    display.setTransformation(transform(0.9f, 1.1f * scale));
                }
            });
            session.runLater(RISE_TICKS, () -> {
                if (display.isValid()) {
                    display.setTextOpacity((byte) 96);
                }
            });
            session.runLater(LIFETIME_TICKS, () -> {
                display.remove();
                session.untrack(display);
            });
        } catch (RuntimeException e) {
            session.plugin().getLogger().log(Level.FINE, "Could not spawn a damage number", e);
        }
    }

    private static Transformation transform(float rise, float scale) {
        return new Transformation(new Vector3f(0, rise, 0), new AxisAngle4f(), new Vector3f(scale, scale, scale),
                new AxisAngle4f());
    }

    /** The number: bold, in the kind's colour, {@code +} in front of a heal. */
    public static Component text(double amount, Kind kind) {
        String number = format(amount);
        String shown = kind == Kind.HEAL ? "+" + number : kind == Kind.CRITICAL ? number + "!" : number;
        return Component.text(shown, kind.color())
                .decoration(TextDecoration.BOLD, true);
    }

    /** One decimal under 10 (dropped when whole), a whole number from 10 on. */
    public static String format(double amount) {
        if (amount >= 10) {
            return String.valueOf(Math.round(amount));
        }
        double tenths = Math.round(amount * 10) / 10.0;
        return tenths == Math.rint(tenths) ? String.valueOf((long) tenths) : String.valueOf(tenths);
    }
}
