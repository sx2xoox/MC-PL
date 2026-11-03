package io.github.sx2xoox.mcpl.game.superpower.ui;

import java.util.Set;

import org.bukkit.NamespacedKey;
import org.jspecify.annotations.Nullable;

/**
 * The 3D weapon models of the MCPL resource pack, one per ability ({@code mcpl:weapon/<ability id>}). The weapon is the
 * item a player fights and casts with (see docs/siege.md); players without the pack see the vanilla item instead, so
 * callers must only put the model on the item for viewers that have the pack ({@code PackAudience#hasPack}), or accept
 * the vanilla look when the pack is off.
 */
public final class AbilityWeaponModels {

    /** Ability ids that have a weapon model in the pack. Filled as models are added (tools/assetgen/models). */
    static final Set<String> MODELED = Set.of();

    private AbilityWeaponModels() {
    }

    /** The item model of {@code abilityId}'s weapon, or null when the pack has none for it. */
    public static @Nullable NamespacedKey model(String abilityId) {
        return MODELED.contains(abilityId) ? new NamespacedKey("mcpl", "weapon/" + abilityId) : null;
    }
}
