package io.github.sx2xoox.mcpl.game.superpower.runtime;

/** Phases of a superpower match (all inside the hub's RUNNING state). */
public enum MatchPhase {
    /** A short pause after the hub's START title, before abilities are handed out. */
    INTRO,
    /** CHOICE mode: players pick from their menu. */
    SELECT,
    /** Slot-machine reveals are playing. */
    REVEAL,
    /** Invulnerable preparation; abilities known but not usable; rerolls (RANDOM). */
    PREP,
    /** Abilities are granted and the fight is on. */
    FIGHT
}
