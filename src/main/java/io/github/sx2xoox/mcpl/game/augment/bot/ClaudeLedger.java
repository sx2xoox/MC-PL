package io.github.sx2xoox.mcpl.game.augment.bot;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The record of one Claude bot's calls in a match: how many, how fast, what failed, the tokens and the estimated cost
 * (server log {@code [bots end]} and {@code stats/augment-claude/*.csv}). Pure; main thread only.
 */
public final class ClaudeLedger {

    /**
     * USD per million tokens: input, output, cache read, cache write (1.25 × input, the 5-minute cache). Unknown
     * models are priced as Opus (an estimate errs high).
     */
    record Price(double input, double output, double cacheRead, double cacheWrite) {

        static Price of(String model) {
            String m = model.toLowerCase(Locale.ROOT);
            if (m.startsWith("claude-haiku-5-5")) {
                return new Price(0.1, 0.5, 0.01, 0.125);
            }
            if (m.startsWith("claude-sonnet-5-5")) {
                return new Price(2, 10, 0.2, 2.5);
            }
            return new Price(4, 20, 0.2, 5);
        }
    }

    int calls;
    int answered;
    int stale;
    int refusals;
    int badAnswers;
    final Map<ClaudeClient.FailureKind, Integer> failures = new EnumMap<>(ClaudeClient.FailureKind.class);
    long latencyNanos;
    long inputTokens;
    long outputTokens;
    long cacheReadTokens;
    long cacheWriteTokens;
    int augmentPicks;
    int augmentFallbacks;
    int chats;
    final Map<String, Integer> goals = new TreeMap<>();

    void reply(ClaudeClient.Reply reply, long nanos) {
        latencyNanos += nanos;
        inputTokens += reply.inputTokens();
        outputTokens += reply.outputTokens();
        cacheReadTokens += reply.cacheReadTokens();
        cacheWriteTokens += reply.cacheWriteTokens();
    }

    void failed(ClaudeClient.FailureKind kind, long nanos) {
        latencyNanos += nanos;
        failures.merge(kind, 1, Integer::sum);
    }

    public int calls() {
        return calls;
    }

    /** Calls whose answer was used. */
    public int answered() {
        return answered;
    }

    /** Failed calls (HTTP errors, timeouts, rate limits; not stale or refused ones). */
    public int failures() {
        int sum = 0;
        for (int n : failures.values()) {
            sum += n;
        }
        return sum;
    }

    public int stale() {
        return stale;
    }

    public int refusals() {
        return refusals;
    }

    public int augmentPicks() {
        return augmentPicks;
    }

    public int augmentFallbacks() {
        return augmentFallbacks;
    }

    public long inputTokens() {
        return inputTokens;
    }

    public long outputTokens() {
        return outputTokens;
    }

    public long cacheReadTokens() {
        return cacheReadTokens;
    }

    public long cacheWriteTokens() {
        return cacheWriteTokens;
    }

    /** Average time of a finished call (answered or failed), in milliseconds. */
    public double averageLatencyMillis() {
        int finished = answered + refusals + badAnswers + failures();
        return finished == 0 ? 0 : latencyNanos / 1.0E6 / finished;
    }

    /** The estimated cost in USD. */
    public double cost(String model) {
        Price p = Price.of(model);
        return (inputTokens * p.input() + outputTokens * p.output() + cacheReadTokens * p.cacheRead()
                + cacheWriteTokens * p.cacheWrite()) / 1.0E6;
    }

    /** One summary for the server log. */
    public String summary(String model) {
        return String.format(Locale.ROOT, "calls=%d answered=%d failed=%d%s stale=%d refusals=%d bad=%d "
                        + "avgLatencyMs=%.0f tokens in/out/cacheRead/cacheWrite=%d/%d/%d/%d cost=$%.4f augments=%d "
                        + "(fallback %d) chats=%d goals=%s", calls, answered, failures(),
                failures.isEmpty() ? "" : failures.toString(), stale, refusals, badAnswers, averageLatencyMillis(),
                inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens, cost(model), augmentPicks,
                augmentFallbacks, chats, goals);
    }
}
