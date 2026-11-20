package io.github.sx2xoox.mcpl.game.augment.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

/** The known augments in registration order: their {@link AugmentInfo} and how to create one for a player. */
public final class AugmentRegistry {

    /** A registered augment. */
    public record Entry(AugmentInfo info, Function<AugmentContext, Augment> factory) {
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    /** @throws IllegalArgumentException for a duplicate id */
    public void register(AugmentInfo info, Function<AugmentContext, Augment> factory) {
        if (entries.putIfAbsent(info.id(), new Entry(info, factory)) != null) {
            throw new IllegalArgumentException("Duplicate augment id: " + info.id());
        }
    }

    public @Nullable Entry get(String id) {
        return entries.get(id);
    }

    public List<Entry> all() {
        return Collections.unmodifiableList(new ArrayList<>(entries.values()));
    }

    /** The augments of one tier, in registration order. */
    public List<AugmentInfo> ofTier(AugmentTier tier) {
        List<AugmentInfo> out = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (entry.info().tier() == tier) {
                out.add(entry.info());
            }
        }
        return out;
    }
}
