package io.github.sx2xoox.mcpl.game.superpower.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.game.superpower.ability.Prompt;
import io.github.sx2xoox.mcpl.game.superpower.ability.PromptEndReason;
import io.github.sx2xoox.mcpl.game.superpower.ability.PromptHandle;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.util.Ticks;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The prompt channel of one match ({@link io.github.sx2xoox.mcpl.game.superpower.ability.AbilityContext#prompt}):
 * per target player one {@link Prompt} on screen at a time (action bar, public text over the head, optional title),
 * the others waiting by priority, then submission. A strictly higher priority pushes the shown prompt back (it keeps
 * the on-screen time it had left). Ticked once per fight tick after the abilities ({@link #tick()}).
 *
 * <p>Action bar: the shown prompt's line is re-sent every {@link #REFRESH_TICKS} ticks, so vanilla never fades it and
 * any other action-bar message of the match (abilities' status lines, the border warning) is overwritten again within
 * a quarter of a second. When the last prompt of a player ends early or expires, its action bar is cleared.
 */
final class PromptChannel {

    /** The shown prompt's action bar is sent again this often. */
    static final long REFRESH_TICKS = 5;
    /** At most this many prompts wait for one player; beyond that the lowest-priority, newest one is dropped. */
    static final int MAX_WAITING = 8;
    /** The overhead text floats this far over the top of the target's head (above the name tag and most markers). */
    static final double OVERHEAD_ABOVE = 1.0;
    static final float OVERHEAD_SCALE = 0.9f;
    private static final Color OVERHEAD_BACKGROUND = Color.fromARGB(0x60, 0, 0, 0);
    private static final int OVERHEAD_LINE_WIDTH = 240;
    private static final int TITLE_FADE_IN = 3;
    private static final int TITLE_FADE_OUT = 6;

    /** Waiting order: higher priority first, then the earlier submission. */
    private static final Comparator<Entry> ORDER = Comparator.<Entry>comparingInt(entry -> -entry.prompt.priority())
            .thenComparingLong(entry -> entry.sequence);

    private final SuperpowerMatch match;
    private final MatchContext ctx;
    private final GameSession session;
    private final Map<UUID, Lane> lanes = new LinkedHashMap<>();
    private long sequence;
    private boolean closed;

    PromptChannel(SuperpowerMatch match, MatchContext ctx) {
        this.match = match;
        this.ctx = ctx;
        this.session = match.session();
    }

    /** The prompts of one target: the one on screen and the waiting ones. */
    private static final class Lane {
        final UUID id;
        Player target;
        @Nullable Entry shown;
        final List<Entry> waiting = new ArrayList<>();

        Lane(Player target) {
            this.id = target.getUniqueId();
            this.target = target;
        }

        /** The shown prompt (if any) and the waiting ones, as a copy. */
        List<Entry> entries() {
            List<Entry> all = new ArrayList<>(waiting.size() + 1);
            if (shown != null) {
                all.add(shown);
            }
            all.addAll(waiting);
            return all;
        }
    }

    // =================================================================== submission

    PromptHandle submit(Player owner, Player target, Prompt prompt) {
        Objects.requireNonNull(prompt, "prompt");
        long now = ctx.currentTick();
        UUID ownerId = owner.getUniqueId();
        Entry entry = new Entry(prompt, owner, target, ++sequence, ctx.isUltimateOrigin(ownerId),
                match.abilityIdOf(ownerId));
        PromptEndReason refused = closed ? PromptEndReason.MATCH_END
                : !match.canOwnResources(ownerId) ? PromptEndReason.OWNER_REVOKED
                : !inPlay(target) ? PromptEndReason.TARGET_LEFT : null;
        if (refused != null) {
            finish(entry, refused);
            return entry;
        }
        Lane lane = lanes.computeIfAbsent(target.getUniqueId(), id -> new Lane(target));
        lane.target = target;
        Entry shown = lane.shown;
        if (shown == null) {
            show(lane, entry, now);
        } else if (prompt.priority() > shown.prompt.priority()) {
            pushBack(lane, shown, now);
            show(lane, entry, now);
        } else {
            enqueue(lane, entry, now);
        }
        return entry;
    }

    private boolean inPlay(Player player) {
        return player.isOnline() && session.isParticipant(player) && session.isInPlay(player);
    }

    private void enqueue(Lane lane, Entry entry, long now) {
        entry.waitingSince = now;
        lane.waiting.add(entry);
        while (lane.waiting.size() > MAX_WAITING) {
            Entry worst = lane.waiting.stream().max(ORDER).orElseThrow();
            end(lane, worst, worst.wasShown() ? PromptEndReason.INTERRUPTED : PromptEndReason.UNSEEN, now);
        }
    }

    // =================================================================== ticking

    /** One fight tick: expiries, the next prompts, waiting limits, and the shown prompts' displays. */
    void tick() {
        if (lanes.isEmpty()) {
            return;
        }
        long now = ctx.currentTick();
        for (Lane lane : List.copyOf(lanes.values())) {
            if (!inPlay(lane.target)) {
                endLane(lane, PromptEndReason.TARGET_LEFT, now);
                continue;
            }
            Entry shown = lane.shown;
            if (shown != null && shown.remaining(now) <= 0) {
                endAndPromote(lane, shown, PromptEndReason.EXPIRED, now);
            }
            promote(lane, now);
            for (Entry waiting : List.copyOf(lane.waiting)) {
                if (waiting.ended == null && waiting.waitingSince >= 0
                        && waiting.waitedTicks(now) >= waiting.prompt.maxWaitTicks()) {
                    end(lane, waiting, waiting.wasShown() ? PromptEndReason.INTERRUPTED : PromptEndReason.UNSEEN, now);
                }
            }
            Entry current = lane.shown;
            if (current != null) {
                render(lane, current, now);
            }
            if (lane.shown == null && lane.waiting.isEmpty()) {
                lanes.remove(lane.id, lane);
            }
        }
    }

    /** Shows the best waiting prompt while nothing is on screen (a shown prompt may cancel itself in onShown). */
    private void promote(Lane lane, long now) {
        while (lane.shown == null && !lane.waiting.isEmpty() && inPlay(lane.target)) {
            Entry next = lane.waiting.stream().min(ORDER).orElseThrow();
            lane.waiting.remove(next);
            show(lane, next, now);
        }
    }

    // =================================================================== showing

    private void show(Lane lane, Entry entry, long now) {
        lane.shown = entry;
        boolean first = entry.shownAt < 0;
        if (first) {
            entry.shownAt = now;
        }
        entry.showingSince = now;
        entry.stopWaiting(now);
        sendActionBar(entry, now);
        updateOverhead(entry);
        showTitle(entry, now);
        Consumer<PromptHandle> onShown = entry.prompt.onShown();
        if (first && onShown != null) {
            callback(entry, "prompt onShown", () -> onShown.accept(entry));
        }
    }

    /** A higher-priority prompt takes the screen: this one waits again with the on-screen time it has left. */
    private void pushBack(Lane lane, Entry entry, long now) {
        entry.accumulate(now);
        hideDisplays(entry, true);
        lane.shown = null;
        entry.waitingSince = now;
        lane.waiting.add(entry);
    }

    private void render(Lane lane, Entry entry, long now) {
        if (now - entry.lastActionBar >= REFRESH_TICKS) {
            sendActionBar(entry, now);
        }
        TextDisplay display = entry.display;
        if (display != null && display.isValid() && display.getWorld().equals(lane.target.getWorld())) {
            display.teleport(overheadSpot(lane.target));
            ctx.views().refresh(display);
        } else if (entry.overhead != null) {
            // Lost (removed by someone else, or the target changed worlds): a fresh one at the target.
            removeDisplay(entry);
            updateOverhead(entry);
        }
    }

    private void sendActionBar(Entry entry, long now) {
        entry.lastActionBar = now;
        entry.target.sendActionBar(entry.actionBar);
    }

    /** Spawns, updates or removes the overhead text of a shown prompt to match its current text. */
    private void updateOverhead(Entry entry) {
        Component text = entry.overhead;
        TextDisplay display = entry.display;
        if (text == null) {
            removeDisplay(entry);
            return;
        }
        if (display != null && display.isValid()) {
            display.text(text);
            return;
        }
        Player target = entry.target;
        TextDisplay spawned = target.getWorld().spawn(overheadSpot(target), TextDisplay.class, d -> {
            d.setPersistent(false);
            d.setVisibleByDefault(false);
            d.text(text);
            d.setBillboard(Display.Billboard.CENTER);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setBackgroundColor(OVERHEAD_BACKGROUND);
            d.setShadowed(true);
            d.setSeeThrough(false);
            d.setLineWidth(OVERHEAD_LINE_WIDTH);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setViewRange(1.5f);
            d.setTeleportDuration(1);
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                    new Vector3f(OVERHEAD_SCALE, OVERHEAD_SCALE, OVERHEAD_SCALE), new Quaternionf()));
        });
        session.track(spawned);
        entry.display = spawned;
        // Everyone reads it but the target itself (it has the action bar) and whoever the target is hidden from.
        ctx.views().add(null, entry.abilityId, spawned,
                viewer -> !viewer.equals(target) && !ctx.isConcealedFrom(target, viewer));
    }

    private static Location overheadSpot(Player target) {
        Location spot = target.getLocation().add(0, target.getHeight() + OVERHEAD_ABOVE, 0);
        spot.setYaw(0);
        spot.setPitch(0);
        return spot;
    }

    private void showTitle(Entry entry, long now) {
        if (entry.title == null && entry.subtitle == null) {
            if (entry.titleShown) {
                entry.titleShown = false;
                entry.target.clearTitle();
            }
            return;
        }
        long stay = Math.max(1, entry.remaining(now));
        Title.Times times = Title.Times.times(Ticks.duration(entry.titleShown ? 0 : TITLE_FADE_IN),
                Ticks.duration(stay), Ticks.duration(TITLE_FADE_OUT));
        entry.target.showTitle(Title.title(entry.title == null ? Component.empty() : entry.title,
                entry.subtitle == null ? Component.empty() : entry.subtitle, times));
        entry.titleShown = true;
    }

    /** Takes the prompt's displays off the screen; an early end also clears its title (an expiry lets it fade). */
    private void hideDisplays(Entry entry, boolean early) {
        removeDisplay(entry);
        if (entry.titleShown) {
            entry.titleShown = false;
            if (early && entry.target.isOnline()) {
                entry.target.clearTitle();
            }
        }
    }

    private void removeDisplay(Entry entry) {
        TextDisplay display = entry.display;
        entry.display = null;
        if (display != null) {
            ctx.views().remove(display);
            session.untrack(display);
            if (display.isValid()) {
                display.remove();
            }
        }
    }

    // =================================================================== ending

    /** Ends one prompt (no promotion); the caller shows the next one. */
    private void end(Lane lane, Entry entry, PromptEndReason reason, long now) {
        if (entry.ended != null) {
            return;
        }
        if (lane.shown == entry) {
            entry.accumulate(now);
            hideDisplays(entry, reason != PromptEndReason.EXPIRED);
            lane.shown = null;
        } else {
            lane.waiting.remove(entry);
            entry.stopWaiting(now);
        }
        finish(entry, reason);
    }

    /** Ends one prompt and shows the next; clears the action bar when nothing follows a prompt that was on screen. */
    private void endAndPromote(Lane lane, Entry entry, PromptEndReason reason, long now) {
        boolean onScreen = lane.shown == entry;
        end(lane, entry, reason, now);
        promote(lane, now);
        if (onScreen && lane.shown == null && lane.target.isOnline()) {
            lane.target.sendActionBar(Component.empty());
        }
    }

    /** Ends every prompt of a lane (the target left play, the match ends). */
    private void endLane(Lane lane, PromptEndReason reason, long now) {
        lanes.remove(lane.id, lane);
        Entry shown = lane.shown;
        if (shown != null) {
            end(lane, shown, reason, now);
            if (lane.target.isOnline()) {
                lane.target.sendActionBar(Component.empty());
            }
        }
        for (Entry waiting : List.copyOf(lane.waiting)) {
            end(lane, waiting, reason, now);
        }
    }

    /** The terminal step: records the reason and runs onEnd. */
    private void finish(Entry entry, PromptEndReason reason) {
        entry.ended = reason;
        BiConsumer<PromptHandle, PromptEndReason> onEnd = entry.prompt.onEnd();
        if (onEnd != null) {
            callback(entry, "prompt onEnd", () -> onEnd.accept(entry, reason));
        }
    }

    private void callback(Entry entry, String hook, Runnable action) {
        ctx.runAs(entry.ownerId, entry.ultimate, () -> match.guard().run(entry.abilityId, hook, action));
    }

    void cancel(Entry entry) {
        Lane lane = lanes.get(entry.target.getUniqueId());
        if (entry.ended != null || lane == null) {
            return;
        }
        endAndPromote(lane, entry, PromptEndReason.CANCELLED, ctx.currentTick());
    }

    /** Ends every prompt {@code owner}'s ability submitted (its ability is revoked). */
    void endOwnedBy(UUID owner, PromptEndReason reason) {
        long now = ctx.currentTick();
        for (Lane lane : List.copyOf(lanes.values())) {
            Entry shown = lane.shown;
            boolean onScreen = shown != null && shown.ownerId.equals(owner);
            boolean any = false;
            for (Entry entry : lane.entries()) {
                if (entry.ownerId.equals(owner)) {
                    end(lane, entry, reason, now);
                    any = true;
                }
            }
            if (!any) {
                continue;
            }
            // Only now the next prompt: none of the revoked owner's prompts is shown on the way out.
            promote(lane, now);
            if (onScreen && lane.shown == null && lane.target.isOnline()) {
                lane.target.sendActionBar(Component.empty());
            }
            if (lane.shown == null && lane.waiting.isEmpty()) {
                lanes.remove(lane.id, lane);
            }
        }
    }

    /** Ends every prompt for {@code target} (it left play). */
    void endTargeting(UUID target, PromptEndReason reason) {
        Lane lane = lanes.get(target);
        if (lane != null) {
            endLane(lane, reason, ctx.currentTick());
        }
    }

    /** Ends every prompt (the match ends). */
    void endAll(PromptEndReason reason) {
        long now = ctx.currentTick();
        for (Lane lane : List.copyOf(lanes.values())) {
            endLane(lane, reason, now);
        }
        lanes.clear();
    }

    /** Ends everything with {@link PromptEndReason#MATCH_END}; later submissions are refused. */
    void dispose() {
        endAll(PromptEndReason.MATCH_END);
        closed = true;
    }

    boolean hasShown(Player player) {
        Lane lane = lanes.get(player.getUniqueId());
        return lane != null && lane.shown != null;
    }

    /** Prompts active for {@code player}, shown first (tests/diagnostics). */
    int activeCount(Player player) {
        Lane lane = lanes.get(player.getUniqueId());
        return lane == null ? 0 : lane.entries().size();
    }

    // =================================================================== handles

    /** One submitted prompt, also its {@link PromptHandle}. */
    private final class Entry implements PromptHandle {

        final Prompt prompt;
        final Player owner;
        final UUID ownerId;
        final Player target;
        final long sequence;
        final boolean ultimate;
        final String abilityId;
        Component actionBar;
        @Nullable Component overhead;
        @Nullable Component title;
        @Nullable Component subtitle;
        long shownAt = -1;
        long showingSince = -1;
        /** On-screen ticks of earlier showings (before a push-back). */
        long shownBefore;
        long waitingSince = -1;
        /** Ticks of earlier waits (before a showing). */
        long waitedBefore;
        long lastActionBar = Long.MIN_VALUE / 2;
        boolean titleShown;
        @Nullable TextDisplay display;
        @Nullable PromptEndReason ended;

        Entry(Prompt prompt, Player owner, Player target, long sequence, boolean ultimate, String abilityId) {
            this.prompt = prompt;
            this.owner = owner;
            this.ownerId = owner.getUniqueId();
            this.target = target;
            this.sequence = sequence;
            this.ultimate = ultimate;
            this.abilityId = abilityId;
            this.actionBar = prompt.actionBar();
            this.overhead = prompt.overhead();
            this.title = prompt.title();
            this.subtitle = prompt.subtitle();
        }

        /** Folds the current showing into {@link #shownBefore} (it is taken off the screen now). */
        void accumulate(long now) {
            if (showingSince >= 0) {
                shownBefore += Math.max(0, now - showingSince);
                showingSince = -1;
            }
        }

        /** Leaves the queue, keeping the time it waited. */
        void stopWaiting(long now) {
            if (waitingSince >= 0) {
                waitedBefore += Math.max(0, now - waitingSince);
                waitingSince = -1;
            }
        }

        /** Ticks spent waiting so far, all waits together. */
        long waitedTicks(long now) {
            return waitedBefore + (waitingSince >= 0 ? Math.max(0, now - waitingSince) : 0);
        }

        long shownTicks(long now) {
            return shownBefore + (showingSince >= 0 ? Math.max(0, now - showingSince) : 0);
        }

        long remaining(long now) {
            return Math.max(0, prompt.durationTicks() - shownTicks(now));
        }

        @Override
        public Player owner() {
            return owner;
        }

        @Override
        public Player target() {
            return target;
        }

        @Override
        public Prompt prompt() {
            return prompt;
        }

        @Override
        public boolean isActive() {
            return ended == null;
        }

        @Override
        public boolean isShown() {
            return ended == null && showingSince >= 0;
        }

        @Override
        public boolean wasShown() {
            return shownAt >= 0;
        }

        @Override
        public long shownAtTick() {
            return shownAt;
        }

        @Override
        public long showingSinceTick() {
            return isShown() ? showingSince : -1;
        }

        @Override
        public boolean isShownFor(long ticks) {
            return isShown() && ctx.currentTick() - showingSince >= ticks;
        }

        @Override
        public long shownTicks() {
            return shownTicks(ctx.currentTick());
        }

        @Override
        public long remainingTicks() {
            return ended == null ? remaining(ctx.currentTick()) : 0;
        }

        @Override
        public @Nullable PromptEndReason endReason() {
            return ended;
        }

        @Override
        public void setActionBar(Component actionBar) {
            this.actionBar = Objects.requireNonNull(actionBar, "actionBar");
            if (isShown()) {
                sendActionBar(this, ctx.currentTick());
            }
        }

        @Override
        public void setOverhead(@Nullable Component overhead) {
            this.overhead = overhead;
            if (isShown()) {
                updateOverhead(this);
            }
        }

        @Override
        public void setTitle(@Nullable Component title, @Nullable Component subtitle) {
            this.title = title;
            this.subtitle = subtitle;
            if (isShown()) {
                showTitle(this, ctx.currentTick());
            }
        }

        @Override
        public void cancel() {
            PromptChannel.this.cancel(this);
        }
    }
}
