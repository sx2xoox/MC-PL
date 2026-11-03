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

    /**
     * Ability ids that have a weapon model in the pack ({@code tools/assetgen/models/weapons.py} WEAPONS; see
     * tools/assetgen/README.md "새 능력 무기 추가하는 법"). AbilityWeaponModelsTest checks each one is in the pack.
     */
    static final Set<String> MODELED = Set.of(
            // 공성전 default pool
            "police", "miner", "blacksmith", "gunslinger", "chef",
            // element, spirit and support abilities (weapons_b2.py)
            "hydro", "magma", "light", "shadow", "vampire", "reaper", "necromancer", "demon", "phoenix", "dragon",
            "werewolf", "dokkaebi", "healer", "bard", "alchemist",
            // batch 3 (tools/assetgen/models/weapons_b3.py)
            "engineer", "hacker", "mech", "bomber", "gravity", "magnet", "chrono", "blink", "speedster", "venom",
            "spider", "pirate", "angler", "farmer", "batter",
            // batch 1 (tools/assetgen/models/weapons_b1.py)
            "ninja", "blade", "duelist", "archer", "sniper", "hunter", "lancer", "berserker", "guardian",
            "fighter", "pyro", "frost", "thunder", "aero", "earth");

    private AbilityWeaponModels() {
    }

    /** Every ability id that has a weapon model. */
    public static Set<String> modeled() {
        return MODELED;
    }

    /** The item model of {@code abilityId}'s weapon, or null when the pack has none for it. */
    public static @Nullable NamespacedKey model(String abilityId) {
        return MODELED.contains(abilityId) ? new NamespacedKey("mcpl", "weapon/" + abilityId) : null;
    }
}
