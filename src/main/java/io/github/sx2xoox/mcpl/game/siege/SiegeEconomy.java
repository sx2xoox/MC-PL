package io.github.sx2xoox.mcpl.game.siege;

import java.util.UUID;

/**
 * The gold of one 공성전 match (docs/siege-v2.md §3). The match itself only asks and forwards
 * ({@link SiegeMatch#gold}, {@link SiegeMatch#addGold}); the gold/shop feature installs a real economy with
 * {@link SiegeMatch#economy(SiegeEconomy)} (typically from a {@link SiegeModule}). Until then {@link #NONE} keeps
 * nobody's gold and the sidebar shows no gold.
 */
public interface SiegeEconomy {

    /** No gold at all. */
    SiegeEconomy NONE = new SiegeEconomy() {
        @Override
        public int gold(UUID player) {
            return 0;
        }

        @Override
        public void addGold(UUID player, int amount, String reason) {
        }

        @Override
        public boolean enabled() {
            return false;
        }
    };

    /** The gold {@code player} has. */
    int gold(UUID player);

    /**
     * Gives (or, negative, takes) {@code amount} gold; {@code reason} is a short machine id ({@code kill}, {@code assist},
     * {@code tower}, {@code passive}, {@code minion}, ...) for feedback and logs.
     */
    void addGold(UUID player, int amount, String reason);

    /** Whether there is gold in this match (the sidebar shows it). */
    default boolean enabled() {
        return true;
    }

    /** A new round: gold and items start over (docs: 라운드가 끝나면 초기화). */
    default void newRound() {
    }
}
