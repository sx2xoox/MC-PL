package io.github.sx2xoox.mcpl.game.augment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Random;

import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import io.github.sx2xoox.mcpl.game.augment.bonus.BonusCondition;
import io.github.sx2xoox.mcpl.game.augment.runtime.AugmentMatch;
import io.github.sx2xoox.mcpl.game.augment.runtime.MatchPhase;
import io.github.sx2xoox.mcpl.game.augment.support.AugmentHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Cow;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Zombie;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.LivingEntityMock;

/** The special achievements that grant an extra augment. */
class AugmentBonusTest {

    private AugmentHarness h;
    private List<TestPlayer> players;
    private TestPlayer a;
    private TestPlayer b;

    @BeforeEach
    void setUp() {
        h = new AugmentHarness();
        h.noRounds();
        players = h.join(3);
        a = players.get(0);
        b = players.get(1);
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    private void assertBonus(AugmentMatch match, TestPlayer player, BonusCondition condition) {
        assertTrue(match.bonuses(player.getUniqueId()).contains(condition), condition + " achieved");
        assertNotNull(match.currentOffer(player.getUniqueId()), "an extra offer");
        assertEquals(h.game.augments().snapshot().tier(condition), match.currentOffer(player.getUniqueId()).tier());
    }

    private void kill(TestPlayer victim, TestPlayer killer) {
        victim.simulateDamage(1, killer);
        victim.setHealth(0);
    }

    @Test
    void firstBloodGoesToTheFirstKillerOnly() {
        AugmentMatch match = h.startFight();
        kill(players.get(2), a);
        assertBonus(match, a, BonusCondition.FIRST_BLOOD);
        assertTrue(match.claimed().contains(BonusCondition.FIRST_BLOOD));
        assertEquals(AugmentTier.GOLD, match.currentOffer(a.getUniqueId()).tier());
    }

    @Test
    void comebackForAKillOnLowHealthOrAgainstAHealthierPlayer() {
        AugmentMatch match = h.startFight();
        TestPlayer c = players.get(2);
        a.setHealth(10);
        c.simulateDamage(2, a); // c (20) had more health than a (10)
        a.setHealth(18);
        kill(c, a);
        assertTrue(match.bonuses(a.getUniqueId()).contains(BonusCondition.COMEBACK), "the underdog won");

        b.setHealth(5);
        assertFalse(match.bonuses(b.getUniqueId()).contains(BonusCondition.COMEBACK));
        // b kills a with 2.5 hearts left (a is not healthier any more: 18 > 5 makes b the underdog anyway)
        kill(a, b);
        assertTrue(match.bonuses(b.getUniqueId()).contains(BonusCondition.COMEBACK));
    }

    @Test
    void abyssMinerNeedsADiamondOreDeepDown() {
        h.setParam("bonuses", "abyss_miner", "max_y", 10);
        AugmentMatch match = h.startFarming();
        Block high = match.wild().world().getBlockAt(5, 40, 5);
        high.setType(Material.DIAMOND_ORE);
        h.server.getPluginManager().callEvent(new org.bukkit.event.block.BlockBreakEvent(high, a));
        assertFalse(match.bonuses(a.getUniqueId()).contains(BonusCondition.ABYSS_MINER), "too high");
        Block deep = match.wild().world().getBlockAt(5, 5, 5);
        deep.setType(Material.DEEPSLATE_DIAMOND_ORE);
        h.server.getPluginManager().callEvent(new org.bukkit.event.block.BlockBreakEvent(deep, a));
        assertBonus(match, a, BonusCondition.ABYSS_MINER);
    }

    @Test
    void miracleLandingInWaterOrBarelyAlive() {
        AugmentMatch match = h.startFarming();
        a.setFallDistance(25);
        h.ticks(1);
        a.getLocation().getBlock().setType(Material.WATER);
        a.setFallDistance(0);
        h.ticks(1);
        assertBonus(match, a, BonusCondition.MIRACLE_LANDING);

        b.setFallDistance(30);
        b.setHealth(7);
        b.damage(4, DamageSource.builder(DamageType.FALL).build());
        assertEquals(3, b.getHealth(), 1.0E-6);
        assertTrue(match.bonuses(b.getUniqueId()).contains(BonusCondition.MIRACLE_LANDING), "landed with 1.5 hearts");

        TestPlayer c = players.get(2);
        c.setFallDistance(5);
        c.setHealth(5);
        c.damage(2, DamageSource.builder(DamageType.FALL).build());
        assertFalse(match.bonuses(c.getUniqueId()).contains(BonusCondition.MIRACLE_LANDING), "too short a fall");
    }

    @Test
    void ironArtisanIsFirstComeAndNeedsAllFourPieces() {
        AugmentMatch match = h.startFarming();
        a.getInventory().setHelmet(ItemStack.of(Material.IRON_HELMET));
        a.getInventory().setChestplate(ItemStack.of(Material.DIAMOND_CHESTPLATE));
        a.getInventory().setLeggings(ItemStack.of(Material.IRON_LEGGINGS));
        a.getInventory().setBoots(ItemStack.of(Material.LEATHER_BOOTS));
        h.ticks(21);
        assertFalse(match.bonuses(a.getUniqueId()).contains(BonusCondition.IRON_ARTISAN), "leather boots");
        a.getInventory().setBoots(ItemStack.of(Material.NETHERITE_BOOTS));
        b.getInventory().setHelmet(ItemStack.of(Material.IRON_HELMET));
        b.getInventory().setChestplate(ItemStack.of(Material.IRON_CHESTPLATE));
        b.getInventory().setLeggings(ItemStack.of(Material.IRON_LEGGINGS));
        b.getInventory().setBoots(ItemStack.of(Material.IRON_BOOTS));
        h.ticks(21);
        assertEquals(1, (a.getUniqueId().equals(firstArtisan(match)) ? 1 : 0)
                + (b.getUniqueId().equals(firstArtisan(match)) ? 1 : 0), "only one player gets it");
    }

    private java.util.UUID firstArtisan(AugmentMatch match) {
        for (TestPlayer player : players) {
            if (match.bonuses(player.getUniqueId()).contains(BonusCondition.IRON_ARTISAN)) {
                return player.getUniqueId();
            }
        }
        return null;
    }

    @Test
    void treasureHunterOpensANaturalLootChest() {
        AugmentMatch match = h.startFarming();
        Inventory chest = h.server.createInventory(null, 27);
        InventoryHolder holder = () -> chest;
        LootContext context = new LootContext.Builder(a.getLocation()).lootedEntity(a).build();
        h.server.getPluginManager().callEvent(new LootGenerateEvent(match.wild().world(), a, holder, table(), context,
                new ArrayList<>(), true));
        assertFalse(match.bonuses(a.getUniqueId()).contains(BonusCondition.TREASURE_HUNTER), "plugin loot does not count");
        h.server.getPluginManager().callEvent(new LootGenerateEvent(match.wild().world(), a, holder, table(), context,
                new ArrayList<>(), false));
        assertBonus(match, a, BonusCondition.TREASURE_HUNTER);
    }

    private static LootTable table() {
        return new LootTable() {
            @Override
            public Collection<ItemStack> populateLoot(Random random, LootContext context) {
                return List.of();
            }

            @Override
            public void fillInventory(Inventory inventory, Random random, LootContext context) {
            }

            @Override
            public NamespacedKey getKey() {
                return NamespacedKey.minecraft("chests/village/village_weaponsmith");
            }
        };
    }

    @Test
    void monsterHunterCountsHostileMobsKilledWhileFarming() {
        h.setParam("bonuses", "monster_hunter", "kills", 2);
        AugmentMatch match = h.startFarming();
        Location at = a.getLocation();
        killMob(match.wild().world().spawn(at, Cow.class));
        killMob(match.wild().world().spawn(at, Zombie.class));
        assertEquals(1, match.bonus().mobKills(a), "a cow is not a monster");
        assertFalse(match.bonuses(a.getUniqueId()).contains(BonusCondition.MONSTER_HUNTER));
        killMob(match.wild().world().spawn(at, Zombie.class));
        assertBonus(match, a, BonusCondition.MONSTER_HUNTER);
    }

    private void killMob(LivingEntity mob) {
        ((LivingEntityMock) mob).setKiller(a);
        h.server.getPluginManager().callEvent(new EntityDeathEvent(mob,
                DamageSource.builder(DamageType.PLAYER_ATTACK).withCausingEntity(a).withDirectEntity(a).build(),
                new ArrayList<>()));
    }

    @Test
    void magePathOnTheFirstEnchantmentAndBonusesCountOncePerPlayer() {
        AugmentMatch match = h.startFarming();
        Block table = match.wild().world().getBlockAt(1, 64, 1);
        EnchantItemEvent event = new EnchantItemEvent(a, a.getOpenInventory(), table, ItemStack.of(Material.IRON_SWORD),
                5, Map.of(Enchantment.SHARPNESS, 1), Enchantment.SHARPNESS, 1, 0);
        h.server.getPluginManager().callEvent(event);
        assertBonus(match, a, BonusCondition.MAGE_PATH);
        assertEquals(1, match.pendingOffers(a.getUniqueId()));
        h.server.getPluginManager().callEvent(event);
        assertEquals(1, match.pendingOffers(a.getUniqueId()), "once per player");
    }

    @Test
    void bonusesCanBeTurnedOff() {
        h.settings().set(AugmentSettings.BONUS, false);
        AugmentMatch match = h.startFight();
        kill(players.get(2), a);
        assertTrue(match.bonuses(a.getUniqueId()).isEmpty());
        assertEquals(MatchPhase.FIGHT, match.phase());
    }
}
