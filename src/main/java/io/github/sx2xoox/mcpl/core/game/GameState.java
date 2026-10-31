package io.github.sx2xoox.mcpl.core.game;

/** Phases of a {@link GameSession}, always in this order (a match may be aborted from any phase). */
public enum GameState {
    /** Title countdown; participants may still join or leave. Nobody is snapshotted yet. */
    COUNTDOWN,
    /** The match is being played: {@link GameInstance#start()} was called, {@link GameInstance#tick()} runs. */
    RUNNING,
    /** Results are shown; the mode was stopped. Everyone is restored when this ends. */
    ENDING,
    /** Everyone was restored; the session is gone. */
    CLOSED
}
