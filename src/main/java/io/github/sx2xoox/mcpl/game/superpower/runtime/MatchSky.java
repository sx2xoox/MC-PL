package io.github.sx2xoox.mcpl.game.superpower.runtime;

import java.util.Random;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.map.MapService;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.game.superpower.SuperpowerSettings;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.World;
import org.jspecify.annotations.Nullable;

/**
 * The sky over a match in a plugin map world ({@code mcpl_map_*}, fixed at noon in clear weather otherwise): the fight
 * starts at noon, the sun goes down while the border shrinks (golden sunset in the middle of the shrink) and the final
 * fight is at midnight; in some matches a shower passes at a random moment ({@code games.superpower.sky}). Abilities
 * read the world itself ({@code isDayTime}, {@code hasStorm}, {@code isInRain}), so the werewolf, the astronomer, the
 * phantom and the herald of light feel the night and the hydromancer, the angler and the kappa the rain.
 *
 * <p>Only plugin map worlds (an ordinary world is shared with everyone on it) and never in practice. The match puts
 * the world back to noon in clear weather when it stops (end, {@code /게임 중지}, plugin disable), and the start of a
 * match does so too in case a crash left it changed.
 */
final class MatchSky {

    /** Midnight: the time at the final border size. */
    static final long MIDNIGHT = 18_000;
    /** The sun touches the horizon and the sky turns gold (half way through the shrink). */
    static final long SUNSET = 12_000;
    /** Vanilla's night ({@code /time set night}); announced at the latest here. */
    static final long NIGHT = 13_000;
    /** The world time is moved this often (ticks): smooth for the sun, a small clock packet per update. */
    static final int TIME_PERIOD = 2;
    /** No rain in the first seconds of the fight (players are still looking at their abilities). */
    static final long RAIN_EARLIEST_TICKS = 20 * 20;

    private final GameSession session;
    private final Messages messages;
    private final BorderController border;
    private final UUID worldId;
    private final boolean dayNight;
    /** Fight ticks of the shower, or -1 for a dry match. */
    private final long rainStart;
    private final long rainEnd;
    private long lastTime = -1;
    private boolean sunsetAnnounced;
    private boolean nightAnnounced;
    private boolean raining;
    private boolean rainDone;
    private boolean stopped;

    private MatchSky(GameSession session, Messages messages, BorderController border, World world, boolean dayNight,
                     long rainStart, long rainEnd) {
        this.session = session;
        this.messages = messages;
        this.border = border;
        this.worldId = world.getUID();
        this.dayNight = dayNight;
        this.rainStart = rainStart;
        this.rainEnd = rainEnd;
    }

    /**
     * The sky of a match (not practice) whose arena lies in a plugin map world, or null for any other world. Rolls
     * the shower now ({@code sky.rain-chance}).
     */
    static @Nullable MatchSky create(GameSession session, Messages messages, SuperpowerSettings settings,
                                     BorderController border) {
        World world = session.arena().center().getWorld();
        if (world == null || !session.hub().maps().isMapWorld(world)) {
            return null;
        }
        boolean dayNight = settings.dayNight() && !world.isFixedTime();
        long start = -1;
        long end = -1;
        Random random = session.random();
        if (settings.rainChance() > 0 && random.nextDouble() < settings.rainChance()) {
            long duration = between(random, settings.rainMinTicks(), settings.rainMaxTicks());
            long[] window = rainWindow(border, settings.timeLimitTicks(), duration);
            start = between(random, window[0], window[1]);
            end = start + duration;
        }
        return new MatchSky(session, messages, border, world, dayNight, start, end);
    }

    /**
     * Earliest and latest fight tick a shower of {@code duration} may start: after the first seconds, and over by the
     * end of the shrink (where the final fight starts) and by the time limit when it can.
     */
    static long[] rainWindow(BorderController border, long timeLimitTicks, long duration) {
        long latest = Math.max(RAIN_EARLIEST_TICKS, border.delayTicks() + border.shrinkTicks() - duration);
        if (timeLimitTicks > 0) {
            latest = Math.min(latest, Math.max(0, timeLimitTicks - duration));
        }
        return new long[] {Math.min(RAIN_EARLIEST_TICKS, latest), latest};
    }

    private static long between(Random random, long min, long max) {
        return max <= min ? min : min + random.nextLong(max - min + 1);
    }

    /** 0 at noon … 1 at midnight: the border's progress, or the same schedule when the border never moves. */
    static double dusk(BorderController border, long fightTicks) {
        if (border.shrinks()) {
            return border.progress(fightTicks);
        }
        long since = fightTicks - border.delayTicks();
        if (since < 0) {
            return 0.0;
        }
        return border.shrinkTicks() <= 0 ? 1.0 : Math.min(1.0, since / (double) border.shrinkTicks());
    }

    /** The match starts: noon in clear weather (also undoing what a crash may have left). */
    void begin() {
        World world = world();
        if (world != null) {
            MapService.resetSky(world);
        }
    }

    /** One fight tick (after the border's). */
    void tick(long fightTicks) {
        World world = world();
        if (stopped || world == null) {
            return;
        }
        if (dayNight && fightTicks % TIME_PERIOD == 0) {
            updateTime(world, fightTicks);
        }
        if (rainStart >= 0) {
            updateRain(world, fightTicks);
        }
    }

    private void updateTime(World world, long fightTicks) {
        long time = MapService.NOON + Math.round((MIDNIGHT - MapService.NOON) * dusk(border, fightTicks));
        if (time != lastTime) {
            lastTime = time;
            if (world.getTime() != time) {
                world.setTime(time);
            }
        }
        if (!nightAnnounced && (time >= NIGHT || (time >= SUNSET && !world.isDayTime()))) {
            // Night for the abilities too: they ask the world whether it is day.
            nightAnnounced = true;
            sunsetAnnounced = true;
            session.broadcast(messages.prefixed("sky.night"));
            session.playSound(Sound.AMBIENT_CAVE, 0.6f, 1.0f);
        } else if (!sunsetAnnounced && time >= SUNSET) {
            sunsetAnnounced = true;
            session.broadcast(messages.prefixed("sky.sunset"));
            session.playSound(Sound.BLOCK_BELL_USE, 0.6f, 0.6f);
        }
    }

    private void updateRain(World world, long fightTicks) {
        if (!raining && !rainDone && fightTicks >= rainStart) {
            raining = true;
            world.setStorm(true);
            world.setThundering(false);
            // Map worlds do not advance their weather; elsewhere the shower must not end on its own either.
            world.setWeatherDuration((int) Math.min(Integer.MAX_VALUE, rainEnd - fightTicks + 200));
            session.broadcast(messages.prefixed("sky.rain-start"));
            session.playSound(Sound.WEATHER_RAIN_ABOVE, 0.5f, 1.0f);
        } else if (raining && fightTicks >= rainEnd) {
            raining = false;
            rainDone = true;
            world.setStorm(false);
            world.setThundering(false);
            session.broadcast(messages.prefixed("sky.rain-stop"));
        }
    }

    /** The match stopped: noon in clear weather again. Safe to call twice; never throws. */
    void restore(Logger logger) {
        if (stopped) {
            return;
        }
        stopped = true;
        raining = false;
        World world = world();
        if (world == null) {
            return; // unloaded: it is reset when it loads again (MapService#load)
        }
        try {
            MapService.resetSky(world);
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Could not reset the sky of " + world.getName(), e);
        }
    }

    private @Nullable World world() {
        return Bukkit.getWorld(worldId);
    }

    /** True while day turns to night in this match ({@code sky.day-night}). */
    boolean dayNight() {
        return dayNight;
    }

    /** The fight tick the shower starts, or -1 for a dry match. */
    long rainStart() {
        return rainStart;
    }

    /** The fight tick the shower ends, or -1 for a dry match. */
    long rainEnd() {
        return rainEnd;
    }

    boolean isRaining() {
        return raining;
    }
}
