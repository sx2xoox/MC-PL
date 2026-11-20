package io.github.sx2xoox.mcpl.game.augment.bot;

import java.util.Map;

/**
 * One Messages API call of the Claude bot, blocking: it runs on the {@link ClaudeService}'s own threads, never on the
 * server thread, and touches no Bukkit API. The implementation ({@link ClaudeSdkClient}) is the only class that uses
 * the Anthropic SDK, so a server without the library still loads every other class.
 */
public interface ClaudeClient extends AutoCloseable {

    /**
     * A request.
     *
     * @param system the fixed system prompt (sent with a cache breakpoint)
     * @param user   the changing part
     * @param schema the JSON schema of the answer ({@code output_config.format})
     */
    record Request(String model, String effort, int maxTokens, String system, String user,
                   Map<String, Object> schema) {
    }

    /**
     * The answer.
     *
     * @param stopReason {@code end_turn}, {@code refusal}, {@code max_tokens}, ...
     * @param text       the text of the answer (the JSON)
     */
    record Reply(String stopReason, String text, long inputTokens, long outputTokens, long cacheReadTokens,
                 long cacheWriteTokens) {
    }

    /** What went wrong, as the commander counts it. */
    enum FailureKind {
        TIMEOUT, RATE_LIMIT, OVERLOADED, AUTH, HTTP, IO, OTHER
    }

    /** A failed call; the message never carries the key. */
    final class Failure extends Exception {

        private static final long serialVersionUID = 1L;
        private final FailureKind kind;
        private final int status;

        public Failure(FailureKind kind, int status, String message) {
            super(message);
            this.kind = kind;
            this.status = status;
        }

        public FailureKind kind() {
            return kind;
        }

        /** The HTTP status (0: none). */
        public int status() {
            return status;
        }
    }

    Reply call(Request request) throws Failure;

    @Override
    void close();
}
