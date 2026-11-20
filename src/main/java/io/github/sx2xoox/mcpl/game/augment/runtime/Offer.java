package io.github.sx2xoox.mcpl.game.augment.runtime;

import java.util.List;

import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import net.kyori.adventure.text.Component;

/**
 * Cards a player may pick one augment from: a timed round or a bonus. Queued per player; the time limit starts when
 * the offer becomes the player's current one.
 */
public final class Offer {

    /** Source of {@link #id} (main thread only). */
    private static long nextId;

    private final long id = ++nextId;
    private final AugmentTier tier;
    private final Component source;
    private List<AugmentInfo> cards;
    private long deadline = -1;
    private int revision;

    Offer(AugmentTier tier, Component source, List<AugmentInfo> cards) {
        this.tier = tier;
        this.source = source;
        this.cards = List.copyOf(cards);
    }

    /** Unique per offer: a dialog is tied to the offer it shows, so a stale one cannot pick from another. */
    public long id() {
        return id;
    }

    /** Counts the times the cards changed (rerolls, top-ups): a dialog showing older cards cannot pick. */
    public int revision() {
        return revision;
    }

    public AugmentTier tier() {
        return tier;
    }

    /** Why it was offered ("2번째 라운드", "보너스: 첫 번째 피"). */
    public Component source() {
        return source;
    }

    /** The cards shown (one to three). */
    public List<AugmentInfo> cards() {
        return cards;
    }

    void cards(List<AugmentInfo> cards) {
        this.cards = List.copyOf(cards);
        revision++;
    }

    /** Match tick at which a random card is granted; -1 until the offer is current. */
    public long deadline() {
        return deadline;
    }

    void deadline(long deadline) {
        this.deadline = deadline;
    }

    boolean started() {
        return deadline >= 0;
    }
}
