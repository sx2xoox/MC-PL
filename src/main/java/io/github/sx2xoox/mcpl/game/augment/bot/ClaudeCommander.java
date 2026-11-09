package io.github.sx2xoox.mcpl.game.augment.bot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

/**
 * The commander of one Claude bot: every few seconds it takes a snapshot of what the bot perceives (main thread),
 * sends it to the Claude API on the {@link ClaudeService}'s threads and, once the answer is back, applies it on the
 * main thread: standing orders for the {@link BotBrain} ({@link ClaudeOrders}), notes on rivals for the next request,
 * the card it takes when an augment round offers some, and now and then a chat line.
 *
 * <p>Never more than one request at a time. Nothing waits for an answer: the bot plays on its own brain meanwhile.
 * A failed, refused, unreadable or stale answer (older than two intervals) is dropped and the orders lapse after
 * three intervals without a fresh answer, so a dead connection leaves a plain bot. A card offer that is not answered
 * within {@code augment-wait-seconds} is decided by the bot's own pick.
 *
 * <p>It listens ({@code bots.claude.listen}, {@link ClaudeEars}): chat lines that reach it go into its next request as
 * data, at most {@link #HEARD_MAX}, each once (a failed request hands them on to the next). An {@code @클로드} call
 * brings that request forward ({@code quick-reply-gap-seconds} after the last one at the soonest, within the per-minute
 * and per-match caps; never a second request at a time). The answer's {@code reply} is said in chat to that player,
 * by name, at most once per {@code reply-cooldown-seconds} per player; {@link ClaudeFilter} drops lines that cross the
 * line.
 */
final class ClaudeCommander {

    /** A card offer waiting for its answer. */
    private record Offer(List<ClaudePrompt.Card> cards, String fallback, String round, long deadline,
                         Consumer<String> then) {
    }

    private record Pending(Future<ClaudeService.Result> future, long sentAt, long staleAt, ClaudeSnapshot snapshot,
                           @Nullable Offer offer, List<Heard> heard) {
    }

    /**
     * A chat line that reached it.
     *
     * @param at   when it was said ({@link BotHost#clock()} ticks)
     * @param call an {@code @클로드} call (wants a quick answer); else a mention or a line said near it
     */
    record Heard(java.util.UUID speaker, String name, String text, long at, boolean call) {
    }

    /** Chat lines sent with one request at most. */
    static final int HEARD_MAX = 6;
    /** Longest chat line sent (characters). */
    static final int HEARD_CHARS = 120;
    /** A chat line older than this is dropped (ticks). */
    static final long HEARD_TTL = 60 * 20;
    /** Lines waiting at most (the oldest go first). */
    static final int HEARD_KEPT = 12;

    /** The most events kept between two requests. */
    static final int EVENTS_MAX = 10;

    private final BotController bot;
    private final ClaudeService service;
    private final ClaudeSettings settings;
    private final BotHost host;
    private final ClaudeLedger ledger = new ClaudeLedger();
    private final Deque<Offer> offers = new ArrayDeque<>();
    /** Events since the last request, with how often each happened. */
    private final Map<String, Integer> events = new LinkedHashMap<>();
    private @Nullable Pending pending;
    private ClaudeOrders orders = ClaudeOrders.NONE;
    private long ordersUntil = -1;
    private String notes = "";
    private String lastReason = "";
    private long nextCall;
    private long nextChat;
    private boolean stopped;
    private final Deque<Heard> heard = new ArrayDeque<>();
    /** An @클로드 call is waiting for a quick answer. */
    private boolean quick;
    private long lastSent = Long.MIN_VALUE / 4;
    /** When the requests brought forward in the last minute went out. */
    private final Deque<Long> quickTimes = new ArrayDeque<>();
    /** When it last answered each player. */
    private final Map<java.util.UUID, Long> repliedAt = new java.util.HashMap<>();

    ClaudeCommander(BotController bot, ClaudeService service, BotHost host) {
        this.bot = bot;
        this.service = service;
        this.settings = service.settings();
        this.host = host;
        this.nextCall = 20 + Math.floorMod(bot.id().hashCode(), 40); // spread several Claude bots out
    }

    ClaudeLedger ledger() {
        return ledger;
    }

    String model() {
        return settings.model();
    }

    /** The orders in force at {@code now} (null once they lapsed: no fresh answer for three intervals). */
    @Nullable ClaudeOrders orders(long now) {
        return now <= ordersUntil ? orders : null;
    }

    String notes() {
        return notes;
    }

    String lastReason() {
        return lastReason;
    }

    boolean busy() {
        return pending != null;
    }

    /** Something worth remembering happened ("영희 봇 hit you with arrows"); merged with repeats. */
    void event(String line) {
        if (stopped) {
            return;
        }
        if (events.containsKey(line) || events.size() < EVENTS_MAX) {
            events.merge(line, 1, Integer::sum);
        }
    }

    /**
     * A chat line reached it ({@link BotManager} routes them, main thread). A call asks for a quick answer. Nothing
     * once stopped or with {@code listen} off.
     */
    void hear(Heard line) {
        if (stopped || !settings.listen()) {
            return;
        }
        ledger.heard++;
        heard.addLast(line);
        while (heard.size() > HEARD_KEPT) {
            heard.removeFirst();
        }
        quick |= line.call();
    }

    /** Lines waiting for its next request (tests). */
    int heardWaiting() {
        return heard.size();
    }

    /**
     * A card offer: the answer goes to {@code then} on the main thread, the bot's own {@code fallback} pick if no
     * usable answer comes within {@code augment-wait-seconds}.
     */
    void offer(List<ClaudePrompt.Card> cards, String fallback, String round, long now, Consumer<String> then) {
        if (stopped) {
            then.accept(fallback);
            return;
        }
        offers.addLast(new Offer(List.copyOf(cards), fallback, round, now + settings.augmentWaitTicks(), then));
    }

    private long interval() {
        return host.fight() ? settings.intervalTicks() : settings.farmingTicks();
    }

    /** Main thread, every tick while the bot is in play. */
    void tick(long now) {
        if (stopped) {
            return;
        }
        Pending p = pending;
        if (p != null) {
            if (p.future().isDone()) {
                pending = null;
                finish(p, now);
            } else if (now >= p.staleAt()) {
                pending = null;
                p.future().cancel(true);
                ledger.stale++;
                unheard(p);
                if (p.offer() != null) {
                    fallback(p.offer());
                }
                nextCall = now; // ask again with fresh news
            }
        }
        // offers still queued past their deadline: its own pick
        while (!offers.isEmpty() && pending == null && now >= offers.peekFirst().deadline()) {
            fallback(offers.removeFirst());
        }
        if (pending != null || bot.body() == null) {
            return;
        }
        while (!heard.isEmpty() && now - heard.peekFirst().at() > HEARD_TTL) {
            heard.removeFirst();
        }
        if (!offers.isEmpty()) {
            send(now, offers.removeFirst());
        } else if (now >= nextCall) {
            send(now, null);
        } else if (quickAllowed(now)) {
            quickTimes.addLast(now);
            ledger.quickCalls++;
            send(now, null);
        }
    }

    /**
     * An @클로드 call is waiting and the request may come early: the last one went out long enough ago, no back-off
     * (rate limit, bad key) holds the next one, and the caps leave room.
     */
    private boolean quickAllowed(long now) {
        if (!quick) {
            return false;
        }
        boolean called = false;
        for (Heard h : heard) {
            called |= h.call();
        }
        if (!called) {
            quick = false; // its call went out (or expired) already
            return false;
        }
        while (!quickTimes.isEmpty() && now - quickTimes.peekFirst() >= 60 * 20) {
            quickTimes.removeFirst();
        }
        return now - lastSent >= settings.quickGapTicks() && nextCall - now <= interval()
                && ledger.quickCalls < settings.quickMax() && quickTimes.size() < settings.quickPerMinute();
    }

    private void send(long now, @Nullable Offer offer) {
        ClaudeSnapshot snapshot = bot.snapshot(now, eventLines(), service::augmentName);
        events.clear();
        List<Heard> lines = new ArrayList<>();
        while (!heard.isEmpty() && lines.size() < HEARD_MAX) {
            lines.add(heard.removeLast()); // the newest
        }
        java.util.Collections.reverse(lines);
        List<ClaudePrompt.Heard> said = new ArrayList<>(lines.size());
        for (Heard h : lines) {
            said.add(new ClaudePrompt.Heard(h.name(), h.text(), Math.max(0, (now - h.at()) / 20), h.call()));
        }
        String user = ClaudePrompt.user(snapshot, notes, offer == null ? null : offer.cards(),
                offer == null ? "" : offer.round(), said);
        Future<ClaudeService.Result> future = service.submit(user);
        if (future == null) {
            if (offer != null) {
                fallback(offer);
            }
            return;
        }
        ledger.calls++;
        quick = false;
        lastSent = now;
        long staleAt = offer != null ? offer.deadline() : now + 2 * interval();
        pending = new Pending(future, now, staleAt, snapshot, offer, List.copyOf(lines));
        nextCall = now + interval();
    }

    /** The lines a request carried that got no usable answer wait for the next one. */
    private void unheard(Pending p) {
        List<Heard> lines = p.heard();
        for (int i = lines.size() - 1; i >= 0 && !stopped; i--) {
            heard.addFirst(lines.get(i));
            quick |= lines.get(i).call();
        }
        while (heard.size() > HEARD_KEPT) {
            heard.removeFirst();
        }
    }

    private List<String> eventLines() {
        List<String> out = new ArrayList<>(events.size());
        events.forEach((line, n) -> out.add(n > 1 ? line + " (x" + n + ")" : line));
        return out;
    }

    private void finish(Pending p, long now) {
        ClaudeService.Result result;
        try {
            result = p.future().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result = null;
        } catch (ExecutionException | java.util.concurrent.CancellationException e) {
            result = null;
        }
        if (result == null || result.failure() != null || result.reply() == null
                || !result.reply().stopReason().equals("end_turn")) {
            unheard(p);
        }
        if (result == null || result.failure() != null || result.reply() == null) {
            ClaudeClient.Failure failure = result == null || result.failure() == null
                    ? new ClaudeClient.Failure(ClaudeClient.FailureKind.OTHER, 0, "no result") : result.failure();
            ledger.failed(failure.kind(), result == null ? 0 : result.nanos());
            service.failed(bot.name(), failure);
            if (failure.kind() == ClaudeClient.FailureKind.RATE_LIMIT
                    || failure.kind() == ClaudeClient.FailureKind.OVERLOADED) {
                nextCall = Math.max(nextCall, now + 2 * interval()); // back off
            } else if (failure.kind() == ClaudeClient.FailureKind.AUTH) {
                nextCall = Long.MAX_VALUE; // a bad key stays bad: a plain bot for the rest of the match
            } else if (failure.kind() == ClaudeClient.FailureKind.IO) {
                // a broken connection (a pooled one the server had closed): ask again at once on a fresh one;
                // a card offer waits for that answer while its time lasts
                nextCall = Math.min(nextCall, now + 5);
                if (p.offer() != null && now < p.offer().deadline()) {
                    offers.addFirst(p.offer());
                    return;
                }
            }
            if (p.offer() != null) {
                fallback(p.offer());
            }
            return;
        }
        ClaudeClient.Reply reply = result.reply();
        ledger.reply(reply, result.nanos());
        if (!reply.stopReason().equals("end_turn")) {
            // refusal, max_tokens, ...: nothing usable, its own judgement goes on
            ledger.refusals++;
            if (p.offer() != null) {
                fallback(p.offer());
            }
            return;
        }
        ClaudeDecision decision = ClaudeDecision.parse(reply.text());
        if (decision == null) {
            unheard(p);
            ledger.badAnswers++;
            if (p.offer() != null) {
                fallback(p.offer());
            }
            return;
        }
        ledger.answered++;
        apply(decision, p, now);
    }

    private void apply(ClaudeDecision d, Pending p, long now) {
        ClaudeSnapshot.Rival target = p.snapshot().rival(d.target());
        orders = new ClaudeOrders(d.goal(), target == null ? null : target.id(), d.range());
        ordersUntil = now + 3 * interval();
        notes = d.notes();
        lastReason = d.reason();
        ledger.goals.merge(d.goal().name(), 1, Integer::sum);
        Offer offer = p.offer();
        if (offer != null) {
            boolean valid = false;
            for (ClaudePrompt.Card card : offer.cards()) {
                valid |= card.id().equals(d.augment());
            }
            if (valid) {
                ledger.augmentPicks++;
                offer.then().accept(d.augment());
            } else {
                fallback(offer);
            }
        }
        if (reply(d, p, now)) {
            return; // one line per answer
        }
        if (settings.chat() && !d.chat().isBlank() && now >= nextChat) {
            nextChat = now + settings.chatCooldownTicks();
            if (!ClaudeFilter.allows(d.chat())) {
                ledger.filtered++;
                return;
            }
            ledger.chats++;
            host.botSays(bot.id(), d.chat());
        }
    }

    /**
     * Says the answer's {@code reply} to the player it names, if that player spoke in this request, the reply cooldown
     * for them is over and the filter lets it through. The line calls them by name. True when it was said.
     */
    private boolean reply(ClaudeDecision d, Pending p, long now) {
        if (!settings.listen() || d.reply().isBlank() || d.replyTo().isBlank()) {
            return false;
        }
        Heard to = null;
        String wanted = ClaudeEars.squash(d.replyTo());
        for (Heard h : p.heard()) {
            if (h.name().equals(d.replyTo()) || ClaudeEars.squash(h.name()).equals(wanted)) {
                to = h;
            }
        }
        if (to == null) {
            return false;
        }
        Long last = repliedAt.get(to.speaker());
        if (last != null && now - last < settings.replyCooldownTicks()) {
            return false;
        }
        String line = d.reply();
        if (!ClaudeEars.squash(line).contains(ClaudeEars.squash(to.name()))) {
            line = to.name() + ", " + line;
        }
        line = ClaudeDecision.oneLine(line, ClaudeDecision.CHAT_MAX + 20);
        if (!ClaudeFilter.allows(line)) {
            ledger.filtered++;
            return false;
        }
        repliedAt.put(to.speaker(), now);
        ledger.replies++;
        host.botSays(bot.id(), line);
        return true;
    }

    private void fallback(Offer offer) {
        ledger.augmentFallbacks++;
        offer.then().accept(offer.fallback());
    }

    /**
     * The bot is out (or the match ends): the call in flight is cancelled and dropped, card offers still waiting get
     * the bot's own pick (the match ignores them once the bot is gone), the orders end.
     */
    void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        Pending p = pending;
        pending = null;
        if (p != null) {
            p.future().cancel(true);
        }
        offers.clear();
        heard.clear();
        quick = false;
        orders = ClaudeOrders.NONE;
        ordersUntil = -1;
    }

    boolean stopped() {
        return stopped;
    }
}
