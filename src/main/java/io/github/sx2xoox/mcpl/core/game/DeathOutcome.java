package io.github.sx2xoox.mcpl.core.game;

import java.util.Objects;

import org.bukkit.Location;

/**
 * What happens to a participant who died, decided by {@link GameInstance#onDeath}.
 *
 * <ul>
 *     <li>{@link #eliminate()} — out of the match: becomes a spectator where they died (inside the arena).</li>
 *     <li>{@link #respawn(Location)} — back in play right away at the location (same game mode, inventory kept;
 *     re-equip in {@link GameInstance#onRespawn}).</li>
 *     <li>{@link #respawn(Location, int)} — spectates the death spot for a while, then respawns.</li>
 * </ul>
 */
public sealed interface DeathOutcome permits DeathOutcome.Eliminate, DeathOutcome.Respawn {

    /** The player is eliminated. */
    static DeathOutcome eliminate() {
        return Eliminate.INSTANCE;
    }

    /** The player respawns immediately at {@code location}. */
    static DeathOutcome respawn(Location location) {
        return new Respawn(location, 0);
    }

    /** The player watches as a spectator for {@code delayTicks}, then respawns at {@code location}. */
    static DeathOutcome respawn(Location location, int delayTicks) {
        return new Respawn(location, delayTicks);
    }

    /** See {@link #eliminate()}. */
    final class Eliminate implements DeathOutcome {
        private static final Eliminate INSTANCE = new Eliminate();

        private Eliminate() {
        }

        @Override
        public String toString() {
            return "Eliminate";
        }
    }

    /**
     * See {@link #respawn(Location, int)}.
     *
     * @param location   where the player comes back
     * @param delayTicks spectator time before coming back (0 = immediately)
     */
    record Respawn(Location location, int delayTicks) implements DeathOutcome {
        public Respawn {
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(location.getWorld(), "location world");
            location = location.clone();
            delayTicks = Math.max(0, delayTicks);
        }

        @Override
        public Location location() {
            return location.clone();
        }
    }
}
