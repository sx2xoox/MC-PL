package io.github.sx2xoox.mcpl.core.player;

/**
 * A new snapshot could not be written to disk ({@link SnapshotStore#capture}). Nothing was kept and nothing was taken
 * from the player: the caller must leave the player out of the match instead of resetting them, since their real
 * state would otherwise exist only in memory.
 */
public final class SnapshotWriteException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SnapshotWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
