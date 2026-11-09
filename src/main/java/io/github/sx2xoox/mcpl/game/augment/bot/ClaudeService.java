package io.github.sx2xoox.mcpl.game.augment.bot;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jspecify.annotations.Nullable;

/**
 * The Claude API connection of one match's Claude bots: the client, threads of its own for the blocking calls
 * (virtual threads; never the server thread, never Bukkit's async scheduler), the cached system prompt and the log of what went
 * wrong. Created by {@link BotManager#register} and closed with the match ({@link #close}: pending calls are
 * interrupted, the threads end). Without a key or without the SDK library there is no service: the Claude bots play
 * as plain bots and the console says so once.
 */
public final class ClaudeService {

    /** The result of one call, made on a service thread; read on the main thread once its future is done. */
    record Result(ClaudeClient.@Nullable Reply reply, ClaudeClient.@Nullable Failure failure, long nanos) {
    }

    /** What happened when the service was asked for (for the log and the stats file). */
    public enum Status {
        ON("on"), NO_KEY("no-key"), NO_SDK("no-sdk");

        private final String key;

        Status(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }

    private static final AtomicBoolean WARNED_NO_KEY = new AtomicBoolean();
    private static final AtomicBoolean WARNED_NO_SDK = new AtomicBoolean();
    private static final AtomicInteger THREADS = new AtomicInteger();

    private final ClaudeSettings settings;
    private final ClaudeClient client;
    private final ExecutorService executor;
    private final Logger logger;
    private final String system;
    private final List<ClaudePrompt.AugmentLine> catalog;
    private final Set<ClaudeClient.FailureKind> logged = EnumSet.noneOf(ClaudeClient.FailureKind.class);
    private boolean closed;

    ClaudeService(ClaudeSettings settings, ClaudeClient client, Logger logger, List<ClaudePrompt.AugmentLine> catalog,
                  int bots) {
        this.settings = settings;
        this.client = client;
        this.logger = logger;
        this.catalog = List.copyOf(catalog);
        this.system = ClaudePrompt.system(this.catalog);
        // virtual threads: a blocking socket read on one ends at once when the thread is interrupted (close), so a
        // match never leaves a call hanging behind it; at most one call per Claude bot runs at a time
        ThreadFactory threads = Thread.ofVirtual().name("MCPL-Claude-" + THREADS.incrementAndGet() + "-", 0).factory();
        this.executor = Executors.newThreadPerTaskExecutor(threads);
    }

    /**
     * The service for {@code bots} Claude bots, or the reason there is none (no key: the setting and the environment
     * are empty; no SDK: the library did not load). Says so on the console once per server run.
     */
    static Outcome create(ClaudeSettings settings, Logger logger, List<ClaudePrompt.AugmentLine> catalog, int bots,
                         Function<String, String> env) {
        String key = settings.resolveKey(env);
        if (key.isBlank()) {
            if (WARNED_NO_KEY.compareAndSet(false, true)) {
                logger.info("[Claude bot] No API key (games.augment.bots.claude.api-key or the environment variable "
                        + ClaudeSettings.KEY_ENV + "): Claude bots play as plain bots.");
            }
            return new Outcome(Status.NO_KEY, null);
        }
        ClaudeClient client;
        try {
            client = new ClaudeSdkClient(key, settings.baseUrl(), settings.timeoutMillis());
        } catch (LinkageError | RuntimeException e) {
            if (WARNED_NO_SDK.compareAndSet(false, true)) {
                logger.warning("[Claude bot] The Anthropic SDK did not load (" + e.getClass().getSimpleName()
                        + "): Claude bots play as plain bots. The server downloads it at start (plugin.yml libraries).");
            }
            return new Outcome(Status.NO_SDK, null);
        }
        return new Outcome(Status.ON, new ClaudeService(settings, client, logger, catalog, bots));
    }

    /** A service, or why there is none. */
    record Outcome(Status status, @Nullable ClaudeService service) {
    }

    ClaudeSettings settings() {
        return settings;
    }

    String system() {
        return system;
    }

    List<ClaudePrompt.AugmentLine> catalog() {
        return catalog;
    }

    /** The name of augment {@code id} (its id if unknown). */
    String augmentName(String id) {
        for (ClaudePrompt.AugmentLine line : catalog) {
            if (line.id().equals(id)) {
                return line.name();
            }
        }
        return id;
    }

    String augmentTier(String id) {
        for (ClaudePrompt.AugmentLine line : catalog) {
            if (line.id().equals(id)) {
                return line.tier();
            }
        }
        return "?";
    }

    /** Sends {@code user} on a service thread; null once closed. Main thread. */
    @Nullable Future<Result> submit(String user) {
        if (closed) {
            return null;
        }
        ClaudeClient.Request request = new ClaudeClient.Request(settings.model(), settings.effort(),
                settings.maxTokens(), system, user, ClaudePrompt.schema());
        return executor.submit(() -> {
            long started = System.nanoTime();
            try {
                return new Result(client.call(request), null, System.nanoTime() - started);
            } catch (ClaudeClient.Failure failure) {
                return new Result(null, failure, System.nanoTime() - started);
            } catch (RuntimeException | LinkageError e) {
                return new Result(null, new ClaudeClient.Failure(ClaudeClient.FailureKind.OTHER, 0,
                        e.getClass().getSimpleName()), System.nanoTime() - started);
            }
        });
    }

    /** Logs the first failure of each kind in the match (the rest are counted only). Main thread. */
    void failed(String bot, ClaudeClient.Failure failure) {
        if (logged.add(failure.kind())) {
            logger.warning("[Claude bot] " + bot + ": call failed (" + failure.kind() + ", " + failure.getMessage()
                    + "); it plays on with its own judgement. Further " + failure.kind()
                    + " failures this match are counted in [bots end].");
        }
    }

    /** A note for the log. */
    void note(String line) {
        logger.info("[Claude bot] " + line);
    }

    /** Ends the match's calls: pending ones are interrupted, the threads end, the client closes. */
    void close() {
        if (closed) {
            return;
        }
        closed = true;
        executor.shutdownNow();
        try {
            client.close();
        } catch (RuntimeException | LinkageError e) {
            logger.log(Level.FINE, "[Claude bot] closing the client", e);
        }
    }

    boolean closed() {
        return closed;
    }

    /** True once {@link #close} ran and the threads have ended (tests). */
    boolean terminated() {
        return executor.isTerminated();
    }

    /** Forgets the one-time console notes (tests). */
    static void resetWarnings() {
        WARNED_NO_KEY.set(false);
        WARNED_NO_SDK.set(false);
    }
}
