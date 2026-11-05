package io.github.sx2xoox.mcpl.game.siege.lanes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Comparator;
import java.util.List;

import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import io.github.sx2xoox.mcpl.core.pack.ItemDisplayModels;
import io.github.sx2xoox.mcpl.core.pack.PackAudience;
import io.github.sx2xoox.mcpl.game.siege.creature.ModelCreatureLook;
import io.github.sx2xoox.mcpl.game.siege.arena.LaneModifiers;
import io.github.sx2xoox.mcpl.game.siege.shop.ShopItem;
import io.github.sx2xoox.mcpl.game.siege.shop.ShopMenu;
import io.github.sx2xoox.mcpl.game.siege.shop.SiegeShop;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.Test;

/** 공성전 v2 gold and shop (docs/siege-v2.md §3). */
class SiegeShopTest extends LaneTestBase {

    @Override
    void configure(MemoryConfiguration config) {
        config.set("minions.enabled", false);
        config.set("jungle.enabled", false);
        config.set("gold.per-second", 0);
        config.set("gold.start", 5000);
    }

    private SiegeShop shop() {
        return systems.shop();
    }

    private int gold(Player player) {
        return systems.gold().balance(player.getUniqueId());
    }

    private Player shopper(LaneSites.Side side, int index) {
        Player player = player(side, index);
        player.teleport(shop().terminal(side).add(2, 0, 0));
        return player;
    }

    private void use(Player player, int slot) {
        player.getInventory().setHeldItemSlot(slot);
        ItemStack item = player.getInventory().getItem(slot);
        new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, item, null, BlockFace.SELF,
                EquipmentSlot.HAND).callEvent();
    }

    private int slotOf(Player player, String tag) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (tag.equals(shop().tag(contents[slot]))) {
                return slot;
            }
        }
        return -1;
    }

    @Test
    void goldComesFromTimeKillsAndTowers() {
        config.set("gold.per-second", 1);
        config.set("gold.start", 500);
        start(2, 1);
        Player killer = player(LaneSites.Side.BLUE, 0);
        Player helper = player(LaneSites.Side.BLUE, 1);
        assertEquals(500, gold(killer));
        run(40);
        assertEquals(502, gold(killer), "+1 a second");
        systems.playerKilled(killer.getUniqueId(), List.of(helper.getUniqueId(), killer.getUniqueId()));
        assertEquals(652, gold(killer));
        assertEquals(552, gold(helper));
        systems.towerDestroyed(LaneSites.Side.BLUE);
        assertEquals(772, gold(killer));
        assertEquals(502, gold(player(LaneSites.Side.RED, 0)), "the enemy gets nothing");
        systems.endRound();
        assertEquals(500, gold(killer), "reset with the round");
    }

    @Test
    void theShopkeeperFacesItsSpotsYaw() {
        PackAudience everyone = new PackAudience() {
            @Override
            public boolean hasPack(Player player) {
                return true;
            }

            @Override
            public Subscription subscribe(java.util.function.Consumer<Player> listener) {
                return () -> { };
            }
        };
        ModelCreatureLook look = new ModelCreatureLook(h.hub.plugin(), everyone);
        java.util.Map<java.util.UUID, ModelCreatureLook.ModelCreature> models = new java.util.HashMap<>();
        skin = (body, kind, team) -> {
            ModelCreatureLook.ModelCreature model = look.attach(body, kind, team);
            models.put(body.getUniqueId(), model);
            return model;
        };
        start(1, 1);
        run(5);
        for (LaneSites.Side side : LaneSites.Side.values()) {
            LaneSites.Spot spot = arena.sites().shop(side);
            Location home = shop().terminal(side);
            Sheep keeper = h.flatWorld().getEntitiesByClass(Sheep.class).stream()
                    .min(Comparator.comparingDouble(v -> v.getLocation().distanceSquared(home))).orElseThrow();
            // the spot's yaw is where the keeper looks (LaneSites#shop), never where a shopper looks
            org.bukkit.util.Vector wanted = new Location(home.getWorld(), 0, 0, 0, spot.yaw(), 0).getDirection();
            org.bukkit.util.Vector looks = keeper.getLocation().getDirection().setY(0).normalize();
            assertTrue(looks.dot(wanted) > 0.99, side + ": the body looks " + looks + ", not " + wanted);
            assertEquals(Math.floorMod((int) spot.yaw(), 360), Math.floorMod(Math.round(keeper.getBodyYaw()), 360),
                    side + ": the body turned too");
            ModelCreatureLook.ModelCreature model = models.get(keeper.getUniqueId());
            assertNotNull(model, "the 태엽 상인 model");
            for (ItemDisplay display : model.displays()) {
                org.joml.Vector3f front = ItemDisplayModels.front(display.getLocation().getYaw(),
                        display.getTransformation());
                assertTrue(front.x * wanted.getX() + front.z * wanted.getZ() > 0.95,
                        side + ": the model looks " + front + ", not " + wanted);
            }
        }
    }

    @Test
    void theTerminalStandsInEachBaseAndShopsOnlyNearby() {
        start(1, 1);
        assertEquals(2, count(Interaction.class));
        Player blue = player(LaneSites.Side.BLUE, 0);
        assertSame(SiegeShop.Result.TOO_FAR, shop().buy(blue, ShopItem.ORB));
        assertEquals(5000, gold(blue));
        // At the enemy's terminal: still too far from the own one.
        blue.teleport(shop().terminal(LaneSites.Side.RED));
        assertSame(SiegeShop.Result.TOO_FAR, shop().buy(blue, ShopItem.ORB));
        assertFalse(shop().open(blue));

        blue.teleport(shop().terminal(LaneSites.Side.BLUE).add(7, 0, 0));
        // clicking the terminal: the shopkeeper there bows
        Location home = shop().terminal(LaneSites.Side.BLUE);
        Interaction terminal = h.flatWorld().getEntitiesByClass(Interaction.class).stream()
                .min(Comparator.comparingDouble(i -> i.getLocation().distanceSquared(home))).orElseThrow();
        new PlayerInteractEntityEvent(blue, terminal).callEvent();
        assertEquals(List.of("shopkeeper attack"), skinEvents);
        assertTrue(shop().open(blue));
        ShopMenu menu = shop().menu(blue);
        assertNotNull(menu);
        int slot = menu.slotOf(ShopItem.ORB);
        InventoryClickEvent click = new InventoryClickEvent(blue.getOpenInventory(), InventoryType.SlotType.CONTAINER,
                slot, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        click.callEvent();
        assertTrue(click.isCancelled(), "nothing is taken out of the menu");
        assertEquals(1, shop().count(blue.getUniqueId(), ShopItem.ORB), "bought by clicking");
        assertEquals(5000 - 700, gold(blue));

        systems.endRound();
        assertEquals(0, count(Interaction.class), "the terminals go with the round");
    }

    @Test
    void purchasesAreRefusedWithoutGoldSlotsOrWhenOwned() {
        config.set("gold.start", 1000);
        start(1, 1);
        Player blue = shopper(LaneSites.Side.BLUE, 0);
        assertSame(SiegeShop.Result.BOUGHT, shop().buy(blue, ShopItem.ARMOR_1));
        assertEquals(650, gold(blue));
        assertSame(SiegeShop.Result.OWNED, shop().buy(blue, ShopItem.ARMOR_1));
        assertSame(SiegeShop.Result.UPGRADED, shop().buy(blue, ShopItem.ARMOR_2));
        assertEquals(150, gold(blue), "only the difference");
        assertSame(SiegeShop.Result.OWNED, shop().buy(blue, ShopItem.ARMOR_1), "a better one is owned");
        assertEquals(1, shop().owned(blue.getUniqueId()).size(), "one slot for the whole line");
        assertSame(SiegeShop.Result.NO_GOLD, shop().buy(blue, ShopItem.ARMOR_3), "600 for the upgrade");
        assertEquals(150, gold(blue));
        systems.gold().add(blue.getUniqueId(), 2000, null);
        assertSame(SiegeShop.Result.BOUGHT, shop().buy(blue, ShopItem.SHOES));
        assertSame(SiegeShop.Result.OWNED, shop().buy(blue, ShopItem.SHOES), "shoes do not stack");
        assertSame(SiegeShop.Result.BOUGHT, shop().buy(blue, ShopItem.CANDY));
        assertSame(SiegeShop.Result.BOUGHT, shop().buy(blue, ShopItem.CANDY));
        assertEquals(6, shop().count(blue.getUniqueId(), ShopItem.CANDY), "3 a purchase, one slot");
        assertSame(SiegeShop.Result.BOUGHT, shop().buy(blue, ShopItem.CANDY));
        assertSame(SiegeShop.Result.STACK_FULL, shop().buy(blue, ShopItem.CANDY));
        assertSame(SiegeShop.Result.BOUGHT, shop().buy(blue, ShopItem.WARD));
        assertSame(SiegeShop.Result.BOUGHT, shop().buy(blue, ShopItem.WOODEN_SWORD));
        assertEquals(5, shop().owned(blue.getUniqueId()).size());
        assertSame(SiegeShop.Result.BOUGHT, shop().buy(blue, ShopItem.HAMMER));
        assertSame(SiegeShop.Result.SLOTS_FULL, shop().buy(blue, ShopItem.ORB), "six slots");
        assertSame(SiegeShop.Result.UPGRADED, shop().buy(blue, ShopItem.STEEL_SWORD), "an upgrade needs no slot");
    }

    @Test
    void passiveItemsWorkForTheBuyerOnly() {
        start(1, 1);
        Player blue = shopper(LaneSites.Side.BLUE, 0);
        Player red = shopper(LaneSites.Side.RED, 0);
        double attack = blue.getAttribute(Attribute.ATTACK_DAMAGE).getValue();
        double speed = blue.getAttribute(Attribute.MOVEMENT_SPEED).getValue();
        double armor = blue.getAttribute(Attribute.ARMOR).getValue();
        shop().buy(blue, ShopItem.ARMOR_3);
        shop().buy(blue, ShopItem.STEEL_SWORD);
        shop().buy(blue, ShopItem.SHOES);
        shop().buy(blue, ShopItem.ORB);
        shop().buy(blue, ShopItem.HAMMER);
        shop().buy(blue, ShopItem.REVIVE_CLOCK);
        assertEquals(armor + 10, blue.getAttribute(Attribute.ARMOR).getValue(), 1.0E-9);
        assertEquals((attack + 1.5) * 1.08, blue.getAttribute(Attribute.ATTACK_DAMAGE).getValue(), 1.0E-9,
                "the steel sword adds 1.5, the orb 8 %");
        assertEquals(speed * 1.08, blue.getAttribute(Attribute.MOVEMENT_SPEED).getValue(), 1.0E-9);
        assertEquals(-3, systems.respawnSeconds(blue.getUniqueId()), 1.0E-9);
        assertEquals(0, systems.respawnSeconds(red.getUniqueId()), 1.0E-9);
        assertEquals(armor, red.getAttribute(Attribute.ARMOR).getValue(), 1.0E-9, "the enemy has none of it");

        // Ability damage: +20 % from the orb dealt by the buyer, −18 % taken by the buyer.
        red.teleport(blue.getLocation().add(1, 0, 0));
        abilityHit(blue, red, 10);
        assertEquals(20 - 12, red.getHealth(), 1.0E-6);
        red.setHealth(20);
        abilityHit(red, blue, 10);
        assertEquals(20 - 8.2, blue.getHealth(), 1.0E-6);
        blue.setHealth(20);
        // Melee is not ability damage.
        melee(red, blue, 5);
        assertTrue(blue.getHealth() >= 15 - 1.0E-6, "no skill reduction on melee");

        // The hammer: +25 % on towers and cores, only for the buyer.
        Zombie tower = h.flatWorld().spawn(red.getLocation().add(2, 0, 0), Zombie.class);
        arena.structures.get(LaneSites.Side.RED).add(tower);
        tower.getAttribute(Attribute.MAX_HEALTH).setBaseValue(100);
        tower.setHealth(100);
        melee(blue, tower, 10);
        assertEquals(87.5, tower.getHealth(), 1.0E-6);
        melee(red, tower, 10);
        assertEquals(77.5, tower.getHealth(), 1.0E-6);
    }

    @Test
    void theHourglassShortensCooldowns() {
        start(1, 1);
        Player blue = shopper(LaneSites.Side.BLUE, 0);
        Player red = player(LaneSites.Side.RED, 0);
        shop().buy(blue, ShopItem.HOURGLASS);
        assertEquals(0.20, systems.modifiers().cooldownReduction(blue.getUniqueId()), 1.0E-9);
        run(20);
        assertEquals(0.2 / 0.8, arena.cooldownGiven.get(blue.getUniqueId()), 1.0E-9);
        assertFalse(arena.cooldownGiven.containsKey(red.getUniqueId()));
    }

    @Test
    void candiesHealOverTime() {
        start(1, 1);
        Player blue = shopper(LaneSites.Side.BLUE, 0);
        shop().buy(blue, ShopItem.CANDY);
        run(20);
        int slot = slotOf(blue, "candy");
        assertEquals(6, slot, "in the hotbar");
        assertEquals(3, blue.getInventory().getItem(slot).getAmount());
        blue.setHealth(10);
        use(blue, slot);
        assertEquals(2, shop().count(blue.getUniqueId(), ShopItem.CANDY));
        assertEquals(2, blue.getInventory().getItem(slot).getAmount());
        run(100);
        assertEquals(14, blue.getHealth(), 0.2, "half way");
        run(110);
        assertEquals(18, blue.getHealth(), 0.2, "8 over 10 s");
    }

    @Test
    void wardsRevealNearbyEnemiesOnly() {
        start(1, 2);
        Player blue = shopper(LaneSites.Side.BLUE, 0);
        Player redNear = player(LaneSites.Side.RED, 0);
        Player redFar = player(LaneSites.Side.RED, 1);
        shop().buy(blue, ShopItem.WARD);
        assertEquals(2, shop().count(blue.getUniqueId(), ShopItem.WARD));
        run(20);
        Location spot = at(0, 0);
        blue.teleport(spot);
        redNear.teleport(spot.clone().add(8, 0, 0));
        redFar.teleport(spot.clone().add(20, 0, 0));
        use(blue, slotOf(blue, "ward"));
        assertEquals(1, shop().wards(blue.getUniqueId()));
        assertEquals(1, shop().count(blue.getUniqueId(), ShopItem.WARD));
        assertTrue(redNear.hasPotionEffect(PotionEffectType.GLOWING));
        assertFalse(redFar.hasPotionEffect(PotionEffectType.GLOWING));
        assertFalse(blue.hasPotionEffect(PotionEffectType.GLOWING), "never the own side");
        run(90 * 20);
        assertEquals(0, shop().wards(blue.getUniqueId()), "gone after 90 s");
    }

    @Test
    void theRecallFlagTakesYouHomeUnlessHit() {
        start(1, 1);
        Player blue = player(LaneSites.Side.BLUE, 0);
        Player red = player(LaneSites.Side.RED, 0);
        run(20);
        int flag = slotOf(blue, "recall_flag");
        assertEquals(8, flag, "everyone has a flag");
        assertEquals(Material.BLUE_BANNER, blue.getInventory().getItem(flag).getType());
        Location away = at(0, 0);
        blue.teleport(away);
        use(blue, flag);
        assertTrue(shop().isRecalling(blue.getUniqueId()));
        run(4 * 20);
        melee(red, blue, 1);
        assertFalse(shop().isRecalling(blue.getUniqueId()), "a hit stops it");
        assertEquals(away.getX(), blue.getLocation().getX(), 1.0E-6);

        use(blue, flag);
        run(8 * 20 + 1);
        assertFalse(shop().isRecalling(blue.getUniqueId()));
        assertTrue(blue.getLocation().getX() < -60, "home at the base");
        assertEquals(slotOf(blue, "recall_flag"), flag, "kept: infinite uses");
    }

    @Test
    void everythingResetsWithTheRound() {
        start(1, 1);
        Player blue = shopper(LaneSites.Side.BLUE, 0);
        double armor = blue.getAttribute(Attribute.ARMOR).getValue();
        shop().buy(blue, ShopItem.ARMOR_1);
        shop().buy(blue, ShopItem.ORB);
        shop().buy(blue, ShopItem.CANDY);
        run(20);
        assertTrue(slotOf(blue, "candy") >= 0);
        systems.endRound();
        assertTrue(shop().owned(blue.getUniqueId()).isEmpty());
        assertEquals(-1, slotOf(blue, "candy"));
        assertEquals(-1, slotOf(blue, "recall_flag"));
        assertEquals(armor, blue.getAttribute(Attribute.ARMOR).getValue(), 1.0E-9);
        assertEquals(0, systems.modifiers().total(blue.getUniqueId(), LaneModifiers.Stat.SKILL_DAMAGE), 1.0E-9);
        assertEquals(5000, gold(blue));
        systems.beginRound();
        assertEquals(8, slotOf(blue, "recall_flag"), "a new flag for the new round");
    }
}
