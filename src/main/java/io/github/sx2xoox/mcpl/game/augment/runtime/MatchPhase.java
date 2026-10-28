package io.github.sx2xoox.mcpl.game.augment.runtime;

/** The phases of a 증강 야생전 match. */
public enum MatchPhase {
    /** The match world is still being generated; players wait where they are, unhurt. */
    PREPARING,
    /** Survival in the fresh world, players cannot hurt each other. */
    FARMING,
    /** PvP on, the world border shrinks; last player or team standing wins. */
    FIGHT
}
