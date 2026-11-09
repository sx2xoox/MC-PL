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
 */
final class ClaudeCommander {

    /** A card offer waiting for its answer. */
    private record Offer(List<ClaudePrompt.Card> cards, String fallback, String round, long deadline,
                         Consumer<String> then) {
    }

    private record Pending(Future<ClaudeService.Result> future, long sentAt, long staleAt, ClaudeSnapshot snapshot,
                           @Nullable Offer offer) {
    }

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
        if (!offers.isEmpty()) {
            send(now, offers.removeFirst());
        } else if (now >= nextCall) {
            send(now, null);
        }
    }

    private void send(long now, @Nullable Offer offer) {
        ClaudeSnapshot snapshot = bot.snapshot(now, eventLines(), service::augmentName);
        events.clear();
        String user = ClaudePrompt.user(snapshot, notes, offer == null ? null : offer.cards(),
                offer == null ? "" : offer.round());
        Future<ClaudeService.Result> future = service.submit(user);
        if (future == null) {
            if (offer != null) {
                fallback(offer);
            }
            return;
        }
        ledger.calls++;
        long staleAt = offer != null ? offer.deadline() : now + 2 * interval();
        pending = new Pending(future, now, staleAt, snapshot, offer);
        nextCall = now + interval();
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
        if (settings.chat() && !d.chat().isBlank() && now >= nextChat) {
            nextChat = now + settings.chatCooldownTicks();
            ledger.chats++;
            host.botSays(bot.id(), d.chat());
        }
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
        orders = ClaudeOrders.NONE;
        ordersUntil = -1;
    }

    boolean stopped() {
        return stopped;
    }
}
