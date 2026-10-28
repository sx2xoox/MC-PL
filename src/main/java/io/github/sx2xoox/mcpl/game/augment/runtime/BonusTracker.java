package io.github.sx2xoox.mcpl.game.augment.runtime;

import java.util.Set;

import io.github.sx2xoox.mcpl.game.augment.api.Params;
import io.github.sx2xoox.mcpl.game.augment.bonus.BonusCondition;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jspecify.annotations.Nullable;

/**
 * Detects the {@link BonusCondition}s and hands them to {@link AugmentMatch#award}, which checks that the condition
 * is on, not done yet by the player and (first-come) by nobody.
 */
public final class BonusTracker {

    private static final Set<Material> HELMETS = Set.of(Material.IRON_HELMET, Material.DIAMOND_HELMET,
            Material.NETHERITE_HELMET);
    private static final Set<Material> CHESTPLATES = Set.of(Material.IRON_CHESTPLATE, Material.DIAMOND_CHESTPLATE,
            Material.NETHERITE_CHESTPLATE);
    private static final Set<Material> LEGGINGS = Set.of(Material.IRON_LEGGINGS, Material.DIAMOND_LEGGINGS,
            Material.NETHERITE_LEGGINGS);
    private static final Set<Material> BOOTS = Set.of(Material.IRON_BOOTS, Material.DIAMOND_BOOTS,
            Material.NETHERITE_BOOTS);

    private final AugmentMatch match;

    BonusTracker(AugmentMatch match) {
        this.match = match;
    }

    private Params params(BonusCondition condition) {
        return match.config().params(condition);
    }

    /** Every tick: landings in water after a long fall; every second: full iron armor. */
    void tick() {
        boolean armorCheck = match.clock() % 20 == 0;
        for (PlayerState state : match.states()) {
            Player player = Bukkit.getPlayer(state.id);
            if (player == null || !match.session().isInPlay(player)) {
                continue;
            }
            float fall = player.getFallDistance();
            if (state.lastFallDistance >= params(BonusCondition.MIRACLE_LANDING).num("min_fall") && fall <= 0f
                    && inWater(player)) {
                match.award(player, BonusCondition.MIRACLE_LANDING);
            }
            state.lastFallDistance = fall;
            if (armorCheck && fullIronArmor(player.getInventory())) {
                match.award(player, BonusCondition.IRON_ARTISAN);
            }
        }
    }

    private static boolean inWater(Player player) {
        Block feet = player.getLocation().getBlock();
        if (feet.getType() == Material.WATER || feet.getType() == Material.BUBBLE_COLUMN) {
            return true;
        }
        BlockData data = feet.getBlockData();
        return data instanceof Waterlogged waterlogged && waterlogged.isWaterlogged();
    }

    static boolean fullIronArmor(PlayerInventory inventory) {
        return is(inventory.getHelmet(), HELMETS) && is(inventory.getChestplate(), CHESTPLATES)
                && is(inventory.getLeggings(), LEGGINGS) && is(inventory.getBoots(), BOOTS);
    }

    private static boolean is(@Nullable ItemStack item, Set<Material> allowed) {
        return item != null && allowed.contains(item.getType());
    }

    /** A participant killed an enemy combatant (credited kill: a player, a bot or a practice dummy). */
    void onPlayerKill(Player killer, LivingEntity victim) {
        match.award(killer, BonusCondition.FIRST_BLOOD);
        PlayerState state = match.state(killer.getUniqueId());
        Params params = params(BonusCondition.COMEBACK);
        boolean low = killer.getHealth() > 0 && killer.getHealth() <= params.num("low_health");
        Long behind = state == null ? null : state.underdogAgainst.get(victim.getUniqueId());
        boolean underdog = behind != null && match.clock() - behind <= params.ticks("window");
        if (low || underdog) {
            match.award(killer, BonusCondition.COMEBACK);
        }
    }

    /** Two participants exchanged a hit (before the damage is applied): remember who was behind on health. */
    void onPlayerHit(LivingEntity victim, Player attacker) {
        PlayerState attackerState = match.state(attacker.getUniqueId());
        PlayerState victimState = victim instanceof Player ? match.state(victim.getUniqueId()) : null;
        if (attackerState == null) {
            return;
        }
        if (victim.getHealth() > attacker.getHealth()) {
            attackerState.underdogAgainst.put(victim.getUniqueId(), match.clock());
        } else if (victimState != null && attacker.getHealth() > victim.getHealth()) {
            victimState.underdogAgainst.put(attacker.getUniqueId(), match.clock());
        }
    }

    /** A participant takes {@code finalDamage} fall damage (monitor): 기적의 착지 if they survive it barely. */
    void onFallDamage(Player player, double finalDamage) {
        Params params = params(BonusCondition.MIRACLE_LANDING);
        if (player.getFallDistance() < params.num("min_fall")) {
            return;
        }
        double left = player.getHealth() + player.getAbsorptionAmount() - finalDamage;
        if (left > 0 && left <= params.num("max_health")) {
            match.award(player, BonusCondition.MIRACLE_LANDING);
        }
    }

    /** A participant broke a block (not cancelled). */
    void onBlockBreak(Player player, Block block) {
        Material type = block.getType();
        if ((type == Material.DIAMOND_ORE || type == Material.DEEPSLATE_DIAMOND_ORE)
                && block.getY() < params(BonusCondition.ABYSS_MINER).num("max_y")) {
            match.award(player, BonusCondition.ABYSS_MINER);
        }
    }

    /** A naturally generated loot container was filled for a participant opening it. */
    void onLootContainer(Player player) {
        match.award(player, BonusCondition.TREASURE_HUNTER);
    }

    /** A participant killed a mob. */
    void onMobKill(Player killer, LivingEntity mob) {
        if (!(mob instanceof Enemy) || match.phase() != MatchPhase.FARMING) {
            return;
        }
        PlayerState state = match.state(killer.getUniqueId());
        if (state == null) {
            return;
        }
        state.mobKills++;
        if (state.mobKills >= params(BonusCondition.MONSTER_HUNTER).integer("kills")) {
            match.award(killer, BonusCondition.MONSTER_HUNTER);
        }
    }

    /** A participant enchanted an item at an enchanting table. */
    void onEnchant(Player player) {
        match.award(player, BonusCondition.MAGE_PATH);
    }

    /** Hostile mobs a participant killed during the farming (for the HUD and tests). */
    public int mobKills(Player player) {
        PlayerState state = match.state(player.getUniqueId());
        return state == null ? 0 : state.mobKills;
    }
}
