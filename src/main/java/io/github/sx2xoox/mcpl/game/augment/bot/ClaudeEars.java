package io.github.sx2xoox.mcpl.game.augment.bot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jspecify.annotations.Nullable;

/**
 * The Claude bots' ears ({@code bots.claude.listen}): what players type in chat during the match. The chat event comes
 * on a chat thread, so the handler only puts the speaker's id and the plain text in a thread-safe queue; the
 * {@link BotManager} drains it on the main thread, checks who spoke (a participant or a spectator of this match) and
 * hands each line to the Claude bots it reaches ({@link #address}): an {@code @클로드} (or {@code @name}) call goes
 * to one bot and brings its next request forward, its name or "클로드" said anywhere and a line said near it are
 * heard for its next request. Registered with the session, so it is unregistered when the match ends.
 */
public final class ClaudeEars implements Listener {

    /** One chat line as the chat thread saw it. */
    record Said(UUID speaker, String text, long nanos) {
    }

    /**
     * Who a line is for.
     *
     * @param call {@code @클로드} / {@code @name}: a call that wants a quick answer
     * @param bot  the index of the bot it names (-1: none in particular)
     */
    record Address(boolean call, int bot) {
    }

    /** Lines waiting for the main thread at most (a chat flood drops the rest). */
    static final int INBOX_MAX = 64;
    /** Words that call every Claude bot. */
    static final List<String> CALL_WORDS = List.of("클로드", "claude");

    private final Queue<Said> inbox = new ConcurrentLinkedQueue<>();
    private final AtomicInteger size = new AtomicInteger();
    private volatile boolean closed;

    /** Chat thread (or main thread for plugin chat): no Bukkit access, only the queue. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (closed) {
            return;
        }
        offer(event.getPlayer().getUniqueId(),
                PlainTextComponentSerializer.plainText().serialize(event.message()));
    }

    /** Queues a line (any thread). */
    void offer(UUID speaker, String text) {
        if (closed || text.isBlank()) {
            return;
        }
        if (size.incrementAndGet() > INBOX_MAX) {
            size.decrementAndGet();
            return;
        }
        inbox.add(new Said(speaker, text, System.nanoTime()));
    }

    /** The lines queued so far, oldest first (main thread). */
    List<Said> drain() {
        List<Said> out = new ArrayList<>();
        for (Said said; (said = inbox.poll()) != null; ) {
            size.decrementAndGet();
            out.add(said);
        }
        return out;
    }

    /** No more lines (the match ended). */
    void close() {
        closed = true;
        inbox.clear();
        size.set(0);
    }

    boolean closed() {
        return closed;
    }

    /**
     * Who {@code text} is for, given the Claude bots' names (without the prefix, e.g. "철수 봇"): a call
     * ({@code @클로드}, {@code @claude}, {@code @철수}, {@code @철수봇}; with a name after {@code @클로드} that bot), a
     * mention (the word or a name without {@code @}), or null (only bots near the speaker hear it). Spaces and case
     * are ignored.
     */
    static @Nullable Address address(String text, List<String> names) {
        String flat = squash(text);
        for (int i = 0; i < names.size(); i++) {
            for (String alias : aliases(names.get(i))) {
                if (flat.contains("@" + alias)) {
                    return new Address(true, i);
                }
            }
        }
        boolean called = false;
        boolean mentioned = false;
        for (String word : CALL_WORDS) {
            called |= flat.contains("@" + word);
            mentioned |= flat.contains(word);
        }
        int named = -1;
        for (int i = 0; i < names.size() && named < 0; i++) {
            for (String alias : aliases(names.get(i))) {
                if (flat.contains(alias)) {
                    named = i;
                    break;
                }
            }
        }
        if (called) {
            return new Address(true, named);
        }
        return mentioned || named >= 0 ? new Address(false, named) : null;
    }

    /** "철수 봇" → "철수봇", "철수" (two characters at least). */
    static List<String> aliases(String name) {
        String full = squash(name);
        List<String> out = new ArrayList<>(2);
        if (full.length() >= 2) {
            out.add(full);
        }
        if (full.endsWith("봇") && full.length() >= 3) {
            out.add(full.substring(0, full.length() - 1));
        }
        return out;
    }

    static String squash(String text) {
        return text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
