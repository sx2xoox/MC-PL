package io.github.sx2xoox.mcpl.core.game;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** A match could not be started; {@link #feedback()} is a ready-to-send Korean message explaining why. */
public final class GameStartException extends Exception {

    private final transient Component feedback;

    public GameStartException(Component feedback) {
        super(PlainTextComponentSerializer.plainText().serialize(feedback));
        this.feedback = feedback;
    }

    public GameStartException(Component feedback, Throwable cause) {
        super(PlainTextComponentSerializer.plainText().serialize(feedback), cause);
        this.feedback = feedback;
    }

    /** The message to show to whoever tried to start the match (prefix included). */
    public Component feedback() {
        return feedback;
    }
}
