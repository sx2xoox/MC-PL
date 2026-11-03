package io.github.sx2xoox.mcpl.game.superpower.ui;

import java.util.List;
import java.util.Set;

import org.bukkit.NamespacedKey;
import org.jspecify.annotations.Nullable;

/**
 * The 3D weapon models of the MCPL resource pack, one per ability ({@code mcpl:item/weapon/<ability id>}). The weapon is
 * the item a player fights and casts with (see docs/siege.md). The item itself is the same for every viewer: a vanilla
 * sword ({@link #SWORDS}, by difficulty: {@link AbilityItems#weaponModel}) with {@code custom_model_data} strings
 * {@code [ability id]}; the pack overrides those swords' item definitions with a {@code minecraft:select} on that string
 * (PackBuilder), so players with the pack see the 3D weapon and everyone else, and every other sword, the vanilla one.
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

    /**
     * The vanilla swords an ability weapon is drawn as; the pack overrides their item definitions
     * (PackBuilder#WEAPON_SWORDS lists the same ones; ResourcePackContentsTest checks it).
     */
    public static final List<String> SWORDS = List.of("iron_sword", "diamond_sword", "netherite_sword");

    private AbilityWeaponModels() {
    }

    /** Every ability id that has a weapon model. */
    public static Set<String> modeled() {
        return MODELED;
    }

    /**
     * The pack's own item definition of {@code abilityId}'s weapon ({@code mcpl:weapon/<id>}, e.g. for displays), or
     * null when the pack has none for it. Ability swords do not use it: they keep the vanilla item model (see above).
     */
    public static @Nullable NamespacedKey model(String abilityId) {
        return MODELED.contains(abilityId) ? new NamespacedKey("mcpl", "weapon/" + abilityId) : null;
    }
}
