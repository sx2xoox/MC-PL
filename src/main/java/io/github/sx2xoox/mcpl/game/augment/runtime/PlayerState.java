package io.github.sx2xoox.mcpl.game.augment.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.bonus.BonusCondition;
import org.jspecify.annotations.Nullable;

/** Everything the match remembers about one participant (also after their elimination, for the results). */
final class PlayerState {

    final UUID id;
    /** Augments owned, in the order they were picked (kept after elimination for the results). */
    final List<AugmentSlot> slots = new ArrayList<>();
    final Deque<Offer> offers = new ArrayDeque<>();
    final Set<BonusCondition> bonuses = EnumSet.noneOf(BonusCondition.class);
    /** Players this one exchanged hits with while having less health than them → match tick (역전의 명수). */
    final Map<UUID, Long> underdogAgainst = new HashMap<>();
    int rerolls;
    int mobKills;
    float lastFallDistance;
    @Nullable OfferMenu menu;

    PlayerState(UUID id, int rerolls) {
        this.id = id;
        this.rerolls = rerolls;
    }

    boolean owns(AugmentInfo info) {
        for (AugmentSlot slot : slots) {
            if (slot.info().id().equals(info.id())) {
                return true;
            }
        }
        return false;
    }

    /** Augment infos owned, in pick order. */
    List<AugmentInfo> augments() {
        List<AugmentInfo> out = new ArrayList<>(slots.size());
        for (AugmentSlot slot : slots) {
            out.add(slot.info());
        }
        return out;
    }

    @Nullable Offer current() {
        return offers.peekFirst();
    }
}
