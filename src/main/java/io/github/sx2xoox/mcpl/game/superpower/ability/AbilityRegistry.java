package io.github.sx2xoox.mcpl.game.superpower.ability;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

/** All known ability types, in registration order. */
public final class AbilityRegistry {

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    /** An ability type: its description and a factory producing a fresh, unbound instance. */
    public record Entry(AbilityInfo info, Supplier<? extends Ability> factory) {
    }

    public void register(AbilityInfo info, Supplier<? extends Ability> factory) {
        if (entries.containsKey(info.id())) {
            throw new IllegalArgumentException("Duplicate ability id: " + info.id());
        }
        entries.put(info.id(), new Entry(info, factory));
    }

    public @Nullable Entry get(String id) {
        return entries.get(id);
    }

    public Collection<Entry> all() {
        return Collections.unmodifiableCollection(entries.values());
    }

    /** Creates a fresh, unbound instance and checks that it reports the registered info. */
    public Ability create(String id) {
        Entry entry = entries.get(id);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown ability: " + id);
        }
        Ability ability = entry.factory().get();
        if (ability.info() != entry.info()) {
            throw new IllegalStateException("Ability " + id + " returned a different AbilityInfo instance");
        }
        return ability;
    }
}
