package io.github.sx2xoox.mcpl.game.augment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import io.github.sx2xoox.mcpl.game.augment.augments.EnchantKitAugment;
import io.github.sx2xoox.mcpl.game.augment.augments.PotionBagAugment;
import io.github.sx2xoox.mcpl.game.augment.runtime.AugmentMatch;
import io.github.sx2xoox.mcpl.game.augment.support.AugmentHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemEnchantments;
import io.papermc.paper.datacomponent.item.PotionContents;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.Wolf;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Group A augments: resources, farming and survival. */
class ResourceAugmentsTest {

    private AugmentHarness h;
    private TestPlayer a;
    private TestPlayer b;
    private AugmentMatch match;

    @BeforeEach
    void setUp() {
        h = new AugmentHarness();
        h.noRounds();
        List<TestPlayer> players = h.join(3);
        a = players.get(0);
        b = players.get(1);
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    private void grant(TestPlayer player, String id) {
        match.grant(player, h.game.registry().get(id).info());
    }

    private void stop() {
        h.hub.games().stop(Component.text("test"));
    }

    private World world() {
        return match.wild().world();
    }

    private int count(Material material) {
        int total = 0;
        for (ItemStack item : a.getInventory().getContents()) {
            if (item != null && item.getType() == material) {
                total += item.getAmount();
            }
        }
        return total;
    }

    private List<Item> itemsOnGround() {
        return List.copyOf(world().getEntitiesByClass(Item.class));
    }

    /** Breaks {@code block} as {@code player} the way the server does: break event, then the drop event. */
    private List<Item> mine(TestPlayer player, Block block, Material drop, int amount) {
        BlockState state = block.getState();
        h.server.getPluginManager().callEvent(new BlockBreakEvent(block, player));
        block.setType(Material.AIR);
        List<Item> items = new ArrayList<>();
        items.add(world().dropItem(block.getLocation().add(0.5, 0.5, 0.5), ItemStack.of(drop, amount)));
        h.server.getPluginManager().callEvent(new BlockDropItemEvent(block, state, player, items));
        return items;
    }

    @Test
    void minerTouchAndEarthBlessingMineFasterUntilTheMatchEnds() {
        match = h.startFarming();
        double base = a.getAttribute(Attribute.BLOCK_BREAK_SPEED).getValue();
        grant(a, "miner_touch");
        assertEquals(base * 1.3, a.getAttribute(Attribute.BLOCK_BREAK_SPEED).getValue(), 1.0E-6);
        grant(a, "earth_blessing");
        assertEquals(base * 2.3, a.getAttribute(Attribute.BLOCK_BREAK_SPEED).getValue(), 1.0E-6, "they stack");
        stop();
        assertEquals(base, a.getAttribute(Attribute.BLOCK_BREAK_SPEED).getValue(), 1.0E-6);
    }

    @Test
    void lumberjackFellsTheLogsAboveButNotBelowOrOtherWood() {
        match = h.startFarming();
        grant(a, "lumberjack");
        Block origin = world().getBlockAt(10, 100, 10);
        for (int y = 99; y <= 104; y++) {
            world().getBlockAt(10, y, 10).setType(Material.OAK_LOG);
        }
        world().getBlockAt(11, 105, 10).setType(Material.OAK_LOG); // a diagonal branch
        world().getBlockAt(10, 105, 10).setType(Material.BIRCH_LOG); // another kind of wood
        h.server.getPluginManager().callEvent(new BlockBreakEvent(origin, a));
        for (int y = 101; y <= 104; y++) {
            assertEquals(Material.AIR, world().getBlockAt(10, y, 10).getType(), "log at y " + y);
        }
        assertEquals(Material.AIR, world().getBlockAt(11, 105, 10).getType(), "the branch");
        assertEquals(Material.OAK_LOG, world().getBlockAt(10, 99, 10).getType(), "the log below stays");
        assertEquals(Material.BIRCH_LOG, world().getBlockAt(10, 105, 10).getType(), "other wood stays");

        world().getBlockAt(20, 100, 20).setType(Material.OAK_LOG);
        world().getBlockAt(20, 101, 20).setType(Material.OAK_LOG);
        a.setSneaking(true);
        h.server.getPluginManager().callEvent(new BlockBreakEvent(world().getBlockAt(20, 100, 20), a));
        assertEquals(Material.OAK_LOG, world().getBlockAt(20, 101, 20).getType(), "sneaking breaks one log");
    }

    @Test
    void autoSmeltTurnsRawOreIntoIngots() {
        match = h.startFarming();
        grant(a, "auto_smelt");
        Block ore = world().getBlockAt(3, 30, 3);
        ore.setType(Material.IRON_ORE);
        List<Item> drops = mine(a, ore, Material.RAW_IRON, 1);
        assertEquals(Material.IRON_INGOT, drops.getFirst().getItemStack().getType());
        Block copper = world().getBlockAt(4, 30, 3);
        copper.setType(Material.COPPER_ORE);
        drops = mine(a, copper, Material.RAW_COPPER, 3);
        assertEquals(ItemStack.of(Material.COPPER_INGOT, 3), drops.getFirst().getItemStack());

        Block rawBlock = world().getBlockAt(5, 30, 3);
        rawBlock.setType(Material.STONE);
        drops = mine(a, rawBlock, Material.RAW_IRON, 1);
        assertEquals(Material.RAW_IRON, drops.getFirst().getItemStack().getType(), "only ores are smelted");

        drops = mine(b, world().getBlockAt(6, 30, 3), Material.RAW_IRON, 1);
        assertEquals(Material.RAW_IRON, drops.getFirst().getItemStack().getType(), "only for the owner");
    }

    @Test
    void luckyPickDoublesOreDropsButNotWithSilkTouch() {
        h.setParam("augments", "lucky_pick", "chance", 100);
        match = h.startFarming();
        grant(a, "lucky_pick");
        Block ore = world().getBlockAt(3, 30, 3);
        ore.setType(Material.COAL_ORE);
        assertEquals(2, mine(a, ore, Material.COAL, 1).getFirst().getItemStack().getAmount());

        ItemStack silk = ItemStack.of(Material.DIAMOND_PICKAXE);
        silk.addEnchantment(Enchantment.SILK_TOUCH, 1);
        a.getInventory().setItemInMainHand(silk);
        ore.setType(Material.COAL_ORE);
        assertEquals(1, mine(a, ore, Material.COAL_ORE, 1).getFirst().getItemStack().getAmount());
    }

    @Test
    void feastHealsAndFillsSaturationAfterTheMeal() {
        match = h.startFarming();
        grant(a, "feast");
        a.setHealth(10);
        a.setFoodLevel(20);
        a.setSaturation(0);
        h.server.getPluginManager().callEvent(new PlayerItemConsumeEvent(a, ItemStack.of(Material.BREAD),
                EquipmentSlot.HAND));
        assertEquals(12, a.getHealth(), 1.0E-6);
        h.ticks(1);
        assertEquals(4, a.getSaturation(), 1.0E-6);

        h.server.getPluginManager().callEvent(new PlayerItemConsumeEvent(a, ItemStack.of(Material.MILK_BUCKET),
                EquipmentSlot.HAND));
        assertEquals(12, a.getHealth(), 1.0E-6, "drinks are no meal");
    }

    @Test
    void itemAugmentsHandOutTheirItems() {
        match = h.startFarming();
        grant(a, "rations");
        assertEquals(2, count(Material.GOLDEN_APPLE));
        assertEquals(16, count(Material.COOKED_BEEF));
        grant(a, "netherite_scrap");
        assertEquals(1, count(Material.NETHERITE_INGOT));
        assertEquals(1, count(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE));

        grant(a, "potion_bag");
        assertEquals(6, count(Material.POTION));
        grant(a, "enchant_kit");
        assertEquals(3, count(Material.ENCHANTED_BOOK));
    }

    /** MockBukkit's inventories drop data components, so the item contents are checked on the created stacks. */
    @Test
    void potionsAndBooksCarryTheirContents() {
        PotionContents contents = PotionBagAugment.potion(PotionType.HEALING)
                .getData(DataComponentTypes.POTION_CONTENTS);
        assertNotNull(contents);
        assertEquals(PotionType.HEALING, contents.potion());
        ItemEnchantments stored = EnchantKitAugment.book(Enchantment.SHARPNESS, 3)
                .getData(DataComponentTypes.STORED_ENCHANTMENTS);
        assertNotNull(stored);
        assertEquals(java.util.Map.of(Enchantment.SHARPNESS, 3), stored.enchantments());
    }

    @Test
    void smithBlessingWearsArmorWhereNothingIsWorn() {
        match = h.startFarming();
        a.getInventory().setHelmet(ItemStack.of(Material.LEATHER_HELMET));
        grant(a, "smith_blessing");
        assertEquals(Material.LEATHER_HELMET, a.getInventory().getHelmet().getType(), "kept");
        assertEquals(Material.IRON_CHESTPLATE, a.getInventory().getChestplate().getType());
        assertEquals(Material.IRON_LEGGINGS, a.getInventory().getLeggings().getType());
        assertEquals(Material.IRON_BOOTS, a.getInventory().getBoots().getType());
        assertEquals(1, count(Material.IRON_HELMET), "the helmet went to the inventory");
        assertEquals(1, count(Material.IRON_PICKAXE));
        assertEquals(1, count(Material.IRON_AXE));
    }

    @Test
    void nightEyesKeepsNightVisionAndTakesItAway() {
        match = h.startFarming();
        grant(a, "night_eyes");
        PotionEffect effect = a.getPotionEffect(PotionEffectType.NIGHT_VISION);
        assertNotNull(effect);
        assertTrue(effect.isInfinite());
        a.removePotionEffect(PotionEffectType.NIGHT_VISION); // milk
        h.ticks(20);
        assertTrue(a.hasPotionEffect(PotionEffectType.NIGHT_VISION), "given back");
        stop();
        assertFalse(a.hasPotionEffect(PotionEffectType.NIGHT_VISION));
    }

    @Test
    void treasureSenseMarksNearbyValuableOresForTheOwnerOnly() {
        match = h.startFarming();
        Location at = a.getLocation();
        Block diamond = world().getBlockAt(at.getBlockX() + 3, at.getBlockY() - 4, at.getBlockZ());
        diamond.setType(Material.DEEPSLATE_DIAMOND_ORE);
        world().getBlockAt(at.getBlockX() - 3, at.getBlockY() - 4, at.getBlockZ()).setType(Material.COAL_ORE);
        world().getBlockAt(at.getBlockX() + 40, at.getBlockY() - 4, at.getBlockZ()).setType(Material.GOLD_ORE);
        grant(a, "treasure_sense");
        h.ticks(1);
        List<BlockDisplay> marks = List.copyOf(world().getEntitiesByClass(BlockDisplay.class));
        assertEquals(1, marks.size(), "only the diamond ore in range");
        BlockDisplay mark = marks.getFirst();
        assertEquals(diamond.getLocation(), mark.getLocation());
        assertTrue(mark.isGlowing());
        assertTrue(a.canSee(mark), "the owner sees it");
        assertFalse(b.canSee(mark), "nobody else does");
        h.ticks(4 * 20);
        assertTrue(world().getEntitiesByClass(BlockDisplay.class).isEmpty(), "gone after the duration");
        h.ticks(6 * 20);
        assertEquals(1, world().getEntitiesByClass(BlockDisplay.class).size(), "scanned again");
        stop();
        assertTrue(world().getEntitiesByClass(BlockDisplay.class).isEmpty());
    }

    @Test
    void xpSpringGivesLevelsAndDoublesExperience() {
        match = h.startFarming();
        grant(a, "xp_spring");
        assertEquals(10, a.getLevel());
        PlayerExpChangeEvent event = new PlayerExpChangeEvent(a, 5);
        h.server.getPluginManager().callEvent(event);
        assertEquals(10, event.getAmount());
        PlayerExpChangeEvent other = new PlayerExpChangeEvent(b, 5);
        h.server.getPluginManager().callEvent(other);
        assertEquals(5, other.getAmount());
    }

    @Test
    void beastFriendWolvesFightForTheOwnerAndLeaveWithTheMatch() {
        match = h.startFarming();
        grant(a, "beast_friend");
        List<Wolf> wolves = List.copyOf(world().getEntitiesByClass(Wolf.class));
        assertEquals(2, wolves.size());
        for (Wolf wolf : wolves) {
            assertTrue(wolf.isTamed());
            assertEquals(a, wolf.getOwner());
            assertEquals(a.getUniqueId(), match.session().combat().ownerOf(wolf), "hits count as the owner's");
        }
        Wolf wolf = wolves.getFirst();
        wolf.setTarget(b);
        h.ticks(1);
        assertNull(wolf.getTarget(), "no players before the fight");

        b.setHealth(20);
        wolf.setNoDamageTicks(0);
        b.damage(4, DamageSource.builder(DamageType.MOB_ATTACK).withCausingEntity(wolf).withDirectEntity(wolf)
                .build());
        assertEquals(20, b.getHealth(), 1.0E-6, "the farming phase PvP gate covers the wolves");
        stop();
        assertTrue(world().getEntitiesByClass(Wolf.class).isEmpty(), "removed with the match");
    }

    @Test
    void midasPickSometimesTurnsStoneIntoGoldAndDiamonds() {
        h.setParam("augments", "midas_pick", "gold", 100);
        h.setParam("augments", "midas_pick", "diamond", 100);
        match = h.startFarming();
        grant(a, "midas_pick");
        Block stone = world().getBlockAt(3, 30, 3);
        stone.setType(Material.STONE);
        int before = itemsOnGround().size();
        h.server.getPluginManager().callEvent(new BlockBreakEvent(stone, a));
        assertEquals(before, itemsOnGround().size(), "not without a pickaxe");

        a.getInventory().setItemInMainHand(ItemStack.of(Material.IRON_PICKAXE));
        h.server.getPluginManager().callEvent(new BlockBreakEvent(stone, a));
        List<Material> dropped = itemsOnGround().stream().map(item -> item.getItemStack().getType()).toList();
        assertTrue(dropped.contains(Material.GOLD_INGOT));
        assertTrue(dropped.contains(Material.DIAMOND));

        stone.setType(Material.ANDESITE);
        int now = itemsOnGround().size();
        h.server.getPluginManager().callEvent(new BlockBreakEvent(stone, a));
        assertEquals(now, itemsOnGround().size(), "only stone and deepslate");
    }

    @Test
    void regenHeartHealsOutOfCombat() {
        match = h.startFarming();
        grant(a, "regen_heart");
        a.setHealth(10);
        h.ticks(5 * 20 + 1);
        assertTrue(a.getHealth() > 10, "regenerating after 5 s without damage");
        a.setNoDamageTicks(0);
        a.damage(2, DamageSource.builder(DamageType.LAVA).build());
        double hurt = a.getHealth();
        h.ticks(4 * 20);
        assertEquals(hurt, a.getHealth(), 1.0E-6, "damage restarts the wait");
        h.ticks(2 * 20);
        assertTrue(a.getHealth() > hurt);
    }

    @Test
    void earthBlessingDropsExtraExperienceFromOres() {
        match = h.startFarming();
        grant(a, "earth_blessing");
        Block ore = world().getBlockAt(3, 30, 3);
        ore.setType(Material.COAL_ORE);
        BlockBreakEvent event = new BlockBreakEvent(ore, a);
        event.setExpToDrop(2);
        h.server.getPluginManager().callEvent(event);
        List<ExperienceOrb> orbs = List.copyOf(world().getEntitiesByClass(ExperienceOrb.class));
        assertEquals(1, orbs.size());
        assertEquals(2, orbs.getFirst().getExperience());
        assertEquals(2, event.getExpToDrop(), "the event itself is untouched");
    }
}
