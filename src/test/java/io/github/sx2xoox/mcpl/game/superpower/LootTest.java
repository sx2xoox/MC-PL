package io.github.sx2xoox.mcpl.game.superpower;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import io.github.sx2xoox.mcpl.core.arena.Arena;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.game.GameState;
import io.github.sx2xoox.mcpl.game.superpower.loot.LootChest;
import io.github.sx2xoox.mcpl.game.superpower.loot.LootConsumables;
import io.github.sx2xoox.mcpl.game.superpower.loot.LootEntry;
import io.github.sx2xoox.mcpl.game.superpower.loot.LootManager;
import io.github.sx2xoox.mcpl.game.superpower.loot.LootPlacer;
import io.github.sx2xoox.mcpl.game.superpower.loot.LootSettings;
import io.github.sx2xoox.mcpl.game.superpower.loot.LootTier;
import io.github.sx2xoox.mcpl.game.superpower.runtime.MatchPhase;
import io.github.sx2xoox.mcpl.game.superpower.runtime.SuperpowerMatch;
import io.github.sx2xoox.mcpl.game.superpower.runtime.SuperpowerMatch.ItemUseResult;
import io.github.sx2xoox.mcpl.game.superpower.support.MatchHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import io.github.sx2xoox.mcpl.game.superpower.ui.AbilityItems;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.PotionContents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Endermite;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Loot chests of 초능력전쟁: placement, opening, supply drops, the superpower consumables and the cleanup. */
class LootTest {

    private static final double EPSILON = 1.0E-9;

    private MatchHarness h;

    @BeforeEach
    void setUp() {
        h = MatchHarness.flat();
        h.settings().set("loot.enabled", true);
        // Only the tested sources charge the gauge.
        h.settings().set("ultimate.per-second", 0);
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    // =================================================================== helpers

    private static LootManager loot(SuperpowerMatch match) {
        LootManager loot = match.loot();
        assertNotNull(loot, "loot is on");
        return loot;
    }

    private PlayerInteractEvent rightClick(Player player, Block block) {
        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK,
                player.getInventory().getItemInMainHand(), block, BlockFace.UP, EquipmentSlot.HAND);
        h.server.getPluginManager().callEvent(event);
        return event;
    }

    /** Right-click in the air with the main-hand item. */
    private PlayerInteractEvent useHeld(Player player) {
        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR,
                player.getInventory().getItemInMainHand(), null, BlockFace.SELF, EquipmentSlot.HAND);
        h.server.getPluginManager().callEvent(event);
        return event;
    }

    private static void standNextTo(Player player, LootChest chest, int dx) {
        Block block = chest.block();
        player.teleport(new Location(block.getWorld(), block.getX() + dx + 0.5, block.getY(), block.getZ() + 0.5));
    }

    private static double horizontal(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static List<String> chat(TestPlayer player) {
        List<String> lines = new ArrayList<>();
        Component next;
        while ((next = player.nextComponentMessage()) != null) {
            lines.add(plain(next));
        }
        return lines;
    }

    private static List<String> actionBars(TestPlayer player) {
        List<String> bars = new ArrayList<>();
        Component next;
        while ((next = player.nextActionBar()) != null) {
            bars.add(plain(next));
        }
        return bars;
    }

    private static int count(Inventory inventory, Material type) {
        int total = 0;
        for (ItemStack stack : inventory.getContents()) {
            if (stack != null && stack.getType() == type) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    private List<ItemDisplay> displays() {
        List<ItemDisplay> displays = new ArrayList<>();
        for (Entity entity : h.flatWorld().getEntities()) {
            if (entity instanceof ItemDisplay display && display.isValid()) {
                displays.add(display);
            }
        }
        return displays;
    }

    /** A participant throws an ender pearl (spawned and announced like a real throw). */
    private EnderPearl throwPearl(Player player) {
        EnderPearl pearl = player.getWorld().spawn(player.getEyeLocation(), EnderPearl.class);
        pearl.setShooter(player);
        h.server.getPluginManager().callEvent(new ProjectileLaunchEvent(pearl));
        return pearl;
    }

    private boolean pearlTeleportCancelled(Player player, Location to) {
        PlayerTeleportEvent event = new PlayerTeleportEvent(player, player.getLocation(), to,
                PlayerTeleportEvent.TeleportCause.ENDER_PEARL);
        h.server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    /** Diamond pieces in one chest of {@code tier}, rolled like {@link LootManager} does. */
    private static int diamonds(LootSettings.Tier tier, Random random) {
        int items = tier.minItems() + random.nextInt(tier.maxItems() - tier.minItems() + 1);
        int count = 0;
        for (LootEntry entry : tier.pool().pick(random, items)) {
            for (LootEntry.Part part : entry.parts()) {
                if (part.template() != null && part.template().getType().name().startsWith("DIAMOND_")) {
                    count++;
                }
            }
        }
        return count;
    }

    private SuperpowerMatch probeFight(List<TestPlayer> players) {
        h.registerTestAbilities();
        SuperpowerMatch match = h.startFight();
        for (TestPlayer player : players) {
            assertTrue(match.replaceAbility(player, "probe"));
        }
        return match;
    }

    // =================================================================== settings

    @Test
    void chestAndSupplyCountsFollowPlayersAndArenaSize() {
        LootSettings settings = LootSettings.defaults(Logger.getLogger("LootTest"));
        assertEquals(8, settings.chestCount(4, 60), "1.5 per player + 2 at the reference radius");
        assertEquals(3, settings.chestCount(2, 20), "a tiny arena still gets the minimum");
        assertEquals(20, settings.chestCount(20, 105), "capped at the maximum");
        assertEquals(14, settings.chestCount(4, 105), "a big arena gets more");
        assertEquals(1, settings.supplyCount(2));
        assertEquals(2, settings.supplyCount(5));
        assertEquals(2, settings.supplyCount(16), "at most two supply drops");
        assertFalse(settings.inPractice(), "no loot in practice by default");
        assertTrue(settings.enabled());
        assertEquals(25.0, settings.crystalGauge(), EPSILON);
        assertFalse(settings.normal().pool().isEmpty());
        assertFalse(settings.rare().pool().isEmpty());
        assertFalse(settings.supply().pool().isEmpty());
    }

    @Test
    void diamondGearStaysOccasionalInTheDefaultPools() {
        LootSettings settings = LootSettings.defaults(Logger.getLogger("LootTest"));
        Random random = new Random(42);
        int rolls = 4000;
        int rareWithDiamond = 0;
        int supplyDiamonds = 0;
        for (int i = 0; i < rolls; i++) {
            rareWithDiamond += diamonds(settings.rare(), random) > 0 ? 1 : 0;
            supplyDiamonds += diamonds(settings.supply(), random);
        }
        double rare = rareWithDiamond / (double) rolls;
        double supply = supplyDiamonds / (double) rolls;
        assertTrue(rare > 0.2 && rare < 0.45, "about one rare chest in three has a diamond piece: " + rare);
        assertTrue(supply > 0.5 && supply < 0.85, "about 0.7 diamond pieces per supply drop: " + supply);
        for (int i = 0; i < 200; i++) {
            assertEquals(0, diamonds(settings.normal(), random), "never in a normal chest");
        }
    }

    @Test
    void poolEntriesParsePotionsBundlesAmountsAndConsumables() {
        LootEntry potion = LootEntry.parse("POTION swiftness seconds=30 weight=7");
        assertEquals(7, potion.weight());
        PotionContents contents = potion.parts().getFirst().template().getData(DataComponentTypes.POTION_CONTENTS);
        assertNotNull(contents);
        PotionEffect effect = contents.customEffects().getFirst();
        assertEquals(PotionEffectType.SPEED, effect.getType());
        assertEquals(600, effect.getDuration(), "a short potion");

        LootEntry healing = LootEntry.parse("SPLASH_POTION healing");
        assertEquals(LootEntry.DEFAULT_WEIGHT, healing.weight());
        assertEquals(Material.SPLASH_POTION, healing.parts().getFirst().template().getType());
        assertEquals(PotionType.HEALING,
                healing.parts().getFirst().template().getData(DataComponentTypes.POTION_CONTENTS).potion());

        LootEntry bow = LootEntry.parse("BOW + ARROW 8-12 weight=5");
        assertEquals(2, bow.parts().size());
        assertEquals(8, bow.parts().get(1).min());
        assertEquals(12, bow.parts().get(1).max());
        LootConsumables consumables = new LootConsumables(h.hub, h.game.messages());
        List<ItemStack> rolled = bow.roll(new Random(3), consumables, 25);
        assertEquals(Material.BOW, rolled.getFirst().getType());
        int arrows = rolled.get(1).getAmount();
        assertTrue(arrows >= 8 && arrows <= 12);

        LootEntry crystal = LootEntry.parse("궁극기결정 2");
        assertEquals(LootConsumables.Kind.ULTIMATE_CRYSTAL, crystal.parts().getFirst().consumable());
        ItemStack crystals = crystal.roll(new Random(1), consumables, 25).getFirst();
        assertEquals(2, crystals.getAmount());
        assertEquals(LootConsumables.Kind.ULTIMATE_CRYSTAL, consumables.kindOf(crystals));
        assertNull(consumables.kindOf(new ItemStack(Material.AMETHYST_SHARD)), "only marked items are consumables");

        assertEquals(16, LootEntry.parse("minecraft:cooked_beef:16").parts().getFirst().max(),
                "the kit's item forms work");
        assertEquals(2, LootEntry.parse("RECHARGE_CHARM x2").parts().getFirst().min());
        assertThrows(IllegalArgumentException.class, () -> LootEntry.parse("DIAMOND_SWORD swiftness"));
    }

    @Test
    void invalidConfigEntriesAreSkippedWithAWarning() {
        List<String> warnings = new ArrayList<>();
        Logger logger = Logger.getLogger("LootTest-" + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        MemoryConfiguration config = new MemoryConfiguration();
        LootSettings.declareDefaults(config);
        config.set("loot.normal.pool", List.of("DIAMOND 2", "NOT_A_THING 3", "POTION no_such_potion",
                "GOLDEN_APPLE 0", "DIAMOND weight=-1", "DIAMOND_SWORD swiftness", "COOKED_BEEF 5-2", "BOW +",
                "ARROW 4 sharp=2"));
        config.set("loot.rare.items", "lots");
        LootSettings settings = LootSettings.read(config, logger);

        assertEquals(1, settings.normal().pool().entries().size(), "only the valid entry is kept");
        assertEquals("DIAMOND 2", settings.normal().pool().entries().getFirst().spec());
        assertEquals(9, warnings.size(), "one warning per invalid entry and one for the item count: " + warnings);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("NOT_A_THING") && w.contains("loot.normal.pool")));
        assertEquals(3, settings.rare().minItems(), "an invalid item count falls back to the default");
        assertEquals(4, settings.rare().maxItems());
    }

    @Test
    void aMatchWithBrokenLootSettingsStillRuns() {
        h.settings().set("loot.normal.pool", List.of("NOPE"));
        h.settings().set("loot.rare.pool", List.of("ALSO_NOPE 3"));
        h.settings().set("loot.chests.min", "many");
        h.join(2);
        SuperpowerMatch match = h.startFight();
        assertTrue(loot(match).chests().isEmpty(), "no usable pool, no chests");
        h.ticks(40);
        assertEquals(GameState.RUNNING, match.session().state(), "the match goes on");
    }

    // =================================================================== placement

    @Test
    void chestsAreSpreadOnTheGroundAwayFromPlayersWhenTheFightStarts() {
        List<TestPlayer> players = h.join(4);
        GameSession session = h.start();
        h.tickUntilPhase(MatchPhase.PREP);
        SuperpowerMatch match = h.match();
        assertTrue(loot(match).chests().isEmpty(), "no chests before the fight");
        h.tickUntilPhase(MatchPhase.FIGHT);
        List<Location> spawns = new ArrayList<>();
        for (TestPlayer player : players) {
            spawns.add(player.getLocation());
        }
        LootManager loot = loot(match);
        int expected = loot.settings().chestCount(4, session.arena().radius());
        assertEquals(8, expected);
        assertEquals(expected, loot.chests().size(), "an open flat arena has room for every chest");
        Location center = session.arena().center();
        for (LootChest chest : loot.chests()) {
            Block block = chest.block();
            assertEquals(Material.CHEST, block.getType());
            assertTrue(h.hub.tempBlocks().isTemporary(block), "chests are temporary blocks");
            assertEquals((int) FlatWorld.SURFACE, block.getY(), "on the ground");
            assertTrue(horizontal(center, chest.center()) <= session.arena().radius() * LootPlacer.RADIUS_FRACTION + 1);
            for (Location spawn : spawns) {
                assertTrue(horizontal(spawn, chest.center()) >= loot.settings().playerDistance() - 0.5,
                        "no chest right next to a player");
            }
            for (LootChest other : loot.chests()) {
                if (other != chest) {
                    assertTrue(horizontal(other.center(), chest.center()) >= 4 - EPSILON, "chests are spread");
                }
            }
            assertFalse(chest.getInventory().isEmpty(), "every chest has loot");
            assertTrue(chest.tier() == LootTier.NORMAL || chest.tier() == LootTier.RARE);
        }
        assertTrue(chat(players.getFirst()).stream().anyMatch(line -> line.contains("상자 8개")),
                "everyone is told about the chests");
        File record = new File(h.hub.plugin().getDataFolder(), "tempblocks.yml");
        assertTrue(record.isFile(), "the chest blocks are on disk at once (crash recovery), not a second later");
        try {
            assertTrue(Files.readAllLines(record.toPath()).stream().filter(line -> !line.isBlank()).count() >= expected);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void placerKeepsChestsOutOfWaterOffParkourPlatformsAndOutOfCorridors() {
        FlatWorld world = h.flatWorld();
        int ground = (int) FlatWorld.SURFACE;
        for (int x = -30; x <= -10; x++) {
            for (int z = -10; z <= 10; z++) {
                world.setBlock(x, ground - 1, z, Material.WATER);
            }
        }
        // A tower top only parkour reaches (4 blocks up, no way up).
        for (int x = 10; x <= 15; x++) {
            for (int z = 10; z <= 15; z++) {
                for (int y = ground; y <= ground + 3; y++) {
                    world.setBlock(x, y, z, Material.STONE);
                }
            }
        }
        // A raised terrace with steps up to it: reachable.
        for (int x = 20; x <= 24; x++) {
            for (int z = -24; z <= -20; z++) {
                for (int y = ground; y <= ground + 2; y++) {
                    world.setBlock(x, y, z, Material.STONE);
                }
            }
        }
        world.setBlock(19, ground, -22, Material.STONE);
        world.setBlock(19, ground + 1, -22, Material.STONE);
        world.setBlock(18, ground, -22, Material.STONE);
        // A one-block-wide corridor.
        world.wallAlongX(0, 10, -30, 2, Material.STONE);
        world.wallAlongX(0, 10, -28, 2, Material.STONE);

        Arena arena = Arena.around(new Location(world, 0.5, ground, 0.5), 40);
        for (boolean nearGround : new boolean[] {false, true}) {
            LootPlacer placer = new LootPlacer(arena, new Random(7), ground, nearGround, block -> false);
            List<LootPlacer.Spot> spots = placer.chestSpots(120, List.of(), 0, List.of());
            assertTrue(spots.size() >= 20, "plenty of room: " + spots.size());
            for (LootPlacer.Spot spot : spots) {
                Block cell = spot.block();
                assertTrue(cell.isEmpty(), "never replacing a real block");
                assertFalse(cell.getRelative(BlockFace.DOWN).isLiquid(), "never on water");
                boolean onTower = cell.getX() >= 10 && cell.getX() <= 15 && cell.getZ() >= 10 && cell.getZ() <= 15;
                assertFalse(onTower, "never on a tower top: " + cell);
                assertFalse(cell.getZ() == -29 && cell.getX() >= 0 && cell.getX() <= 10, "never in a corridor");
                assertTrue(cell.getY() == ground || cell.getY() == ground + 3, "on the ground or the terrace");
            }
        }
        LootPlacer placer = new LootPlacer(arena, new Random(1), ground, true, block -> false);
        assertFalse(placer.reachable(world.getBlockAt(12, ground + 4, 12)), "a tower top needs parkour");
        assertTrue(placer.reachable(world.getBlockAt(22, ground + 3, -22)), "steps lead up to the terrace");
        assertTrue(placer.reachable(world.getBlockAt(0, ground, 30)));
        assertFalse(placer.isChestSpot(world.getBlockAt(-20, ground - 1, 0)), "never in water");
        assertFalse(placer.isChestSpot(world.getBlockAt(5, ground, -29)), "never blocking a corridor");
        assertFalse(placer.isChestSpot(world.getBlockAt(0, ground - 1, 0)), "never inside the ground");
        assertTrue(placer.isChestSpot(world.getBlockAt(0, ground, 30)));
        LootPlacer busy = new LootPlacer(arena, new Random(1), ground, true, block -> block.getX() == 0);
        assertFalse(busy.isChestSpot(world.getBlockAt(0, ground, 30)), "never on a temporary block");
        assertEquals("north-east", LootManager.directionKey(arena.center(), new Location(world, 30, ground, -30)));
        assertEquals("center", LootManager.directionKey(arena.center(), new Location(world, 3, ground, 2)));
        assertEquals("west", LootManager.directionKey(arena.center(), new Location(world, -30, ground, 1)));
    }

    @Test
    void chestsNeverPlugADoorwayOrTheFootOfAOneWideStaircase() {
        FlatWorld world = h.flatWorld();
        int ground = (int) FlatWorld.SURFACE;
        // A wall along x at z = 0, three high, with a one-wide archway at x = 0 (the lintel stops a jump onto a chest).
        world.wallAlongX(-10, 10, 0, 3, Material.STONE);
        world.setBlock(0, ground, 0, Material.AIR);
        world.setBlock(0, ground + 1, 0, Material.AIR);
        // A one-wide staircase going north at x = 20 between two walls: steps at z = 5 (one high) and z = 4 (two).
        for (int z = 3; z <= 5; z++) {
            for (int y = ground; y <= ground + 3; y++) {
                world.setBlock(19, y, z, Material.STONE);
                world.setBlock(21, y, z, Material.STONE);
            }
        }
        world.setBlock(20, ground, 5, Material.STONE);
        world.setBlock(20, ground, 4, Material.STONE);
        world.setBlock(20, ground + 1, 4, Material.STONE);
        Arena arena = Arena.around(new Location(world, 0.5, ground, 0.5), 40);
        LootPlacer placer = new LootPlacer(arena, new Random(1), ground, true, block -> false);

        assertFalse(placer.isChestSpot(world.getBlockAt(0, ground, 1)), "never right in front of an archway");
        assertFalse(placer.isChestSpot(world.getBlockAt(0, ground, -1)), "nor right behind it");
        assertFalse(placer.isChestSpot(world.getBlockAt(20, ground, 6)), "never at the foot of a narrow staircase");
        assertTrue(placer.isChestSpot(world.getBlockAt(0, ground, 3)), "a few steps away is fine");
        assertTrue(placer.isChestSpot(world.getBlockAt(5, ground, 1)), "along a wall is fine");
        assertTrue(placer.isChestSpot(world.getBlockAt(20, ground, 8)), "away from the stairs is fine");
        for (LootPlacer.Spot spot : placer.chestSpots(150, List.of(), 0, List.of())) {
            Block cell = spot.block();
            assertFalse(cell.getX() == 0 && Math.abs(cell.getZ()) == 1, "no chest plugs the archway: " + cell);
            assertFalse(cell.getX() == 20 && cell.getZ() == 6, "no chest plugs the staircase: " + cell);
        }
    }

    @Test
    void outsideMapsChestsGoOnlyWhereThePlayersCanWalk() {
        FlatWorld world = h.flatWorld();
        int ground = (int) FlatWorld.SURFACE;
        // A closed hall: walls around a 13 x 13 floor, a roof on top. Big enough for the walk search from inside (and
        // on its roof), but nobody can walk in or up.
        for (int x = 10; x <= 24; x++) {
            for (int z = 10; z <= 24; z++) {
                boolean wall = x == 10 || x == 24 || z == 10 || z == 24;
                for (int y = ground; y <= ground + 3; y++) {
                    if (wall || y == ground + 3) {
                        world.setBlock(x, y, z, Material.STONE);
                    }
                }
            }
        }
        Arena arena = Arena.around(new Location(world, 0.5, ground, 0.5), 40);
        LootPlacer placer = new LootPlacer(arena, new Random(5), ground, false, block -> false);
        Block inside = world.getBlockAt(17, ground, 17);
        Block roof = world.getBlockAt(17, ground + 4, 17);
        assertTrue(placer.reachable(inside), "the walk search alone takes the closed hall for open ground");
        assertTrue(placer.reachable(roof), "and its roof");

        LootPlacer connected = new LootPlacer(arena, new Random(5), ground, false, block -> false);
        assertNull(connected.connectTo(List.of(new Location(world, 0.5, ground + 30, 0.5))),
                "nobody stands anywhere: the walk search stays");
        assertNotNull(connected.connectTo(List.of(new Location(world, 0.5, ground, 0.5),
                new Location(world, -20.5, ground, 3.5))));
        assertFalse(connected.reachable(inside), "nobody can walk into the closed hall");
        assertFalse(connected.reachable(roof), "nor onto its roof");
        assertTrue(connected.reachable(world.getBlockAt(0, ground, 30)));
        List<LootPlacer.Spot> spots = connected.chestSpots(80, List.of(), 0, List.of());
        assertTrue(spots.size() >= 20, "plenty of room outside: " + spots.size());
        for (LootPlacer.Spot spot : spots) {
            Block cell = spot.block();
            assertFalse(cell.getX() >= 10 && cell.getX() <= 24 && cell.getZ() >= 10 && cell.getZ() <= 24,
                    "never in or on the closed hall: " + cell);
            assertEquals(ground, cell.getY());
        }
    }

    @Test
    void supplyDropsLandInsideTheFinalBorderWhereverTheStarterStood() {
        FlatWorld world = h.flatWorld();
        int ground = (int) FlatWorld.SURFACE;
        // The match was started from the top of a tower: the arena center is 20 blocks above the ground.
        Arena arena = Arena.around(new Location(world, 0.5, ground + 20, 0.5), 60);
        LootPlacer placer = new LootPlacer(arena, new Random(11), ground + 20, false, block -> false);
        List<LootPlacer.Spot> open = placer.supplySpots(2, 10, List.of());
        assertEquals(2, open.size());
        for (LootPlacer.Spot spot : open) {
            Block cell = spot.block();
            assertTrue(Math.abs(cell.getX()) <= 10 && Math.abs(cell.getZ()) <= 10, "inside the final square: " + cell);
            assertEquals(ground, cell.getY());
        }

        // A roofed market fills the final square: the drops land as close to it as they can, not anywhere.
        for (int x = -12; x <= 12; x++) {
            for (int z = -12; z <= 12; z++) {
                world.setBlock(x, ground + 3, z, Material.STONE);
            }
        }
        LootPlacer roofed = new LootPlacer(arena, new Random(11), ground + 20, false, block -> false);
        roofed.connectTo(List.of(new Location(world, 30.5, ground, 30.5)));
        List<LootPlacer.Spot> spots = roofed.supplySpots(2, 10, List.of());
        assertEquals(2, spots.size());
        for (LootPlacer.Spot spot : spots) {
            assertTrue(horizontal(arena.center(), spot.center()) <= 20, "close to the final square: " + spot);
            assertEquals(ground, spot.block().getY(), "on the ground, not on the market roof");
        }
    }

    // =================================================================== opening

    @Test
    void openingAChestShowsItsSharedTakeOnlyVirtualInventory() {
        h.settings().set("loot.normal.pool", List.of("DIAMOND 3"));
        h.settings().set("loot.normal.items", "1");
        h.settings().set("loot.rare.pool", List.of("EMERALD 2"));
        h.settings().set("loot.rare.items", "1");
        List<TestPlayer> players = h.join(2);
        SuperpowerMatch match = h.startFight();
        LootChest chest = loot(match).chests().getFirst();
        TestPlayer first = players.get(0);
        TestPlayer second = players.get(1);
        standNextTo(first, chest, 1);
        standNextTo(second, chest, -1);

        PlayerInteractEvent click = rightClick(first, chest.block());
        assertEquals(Event.Result.DENY, click.useInteractedBlock(), "never the vanilla chest");
        assertSame(chest.getInventory(), first.getOpenInventory().getTopInventory());
        assertTrue(chest.getInventory().getHolder() instanceof LootChest, "a virtual inventory of its own holder");
        boolean rare = chest.tier() == LootTier.RARE;
        assertEquals(rare ? 2 : 3, count(chest.getInventory(), rare ? Material.EMERALD : Material.DIAMOND),
                "the pool's items");
        rightClick(second, chest.block());
        assertSame(chest.getInventory(), second.getOpenInventory().getTopInventory(), "shared contents");

        int slot = chest.getInventory().first(rare ? Material.EMERALD : Material.DIAMOND);
        InventoryClickEvent put = new InventoryClickEvent(first.getOpenInventory(), InventoryType.SlotType.CONTAINER,
                slot == 0 ? 1 : 0, ClickType.LEFT, InventoryAction.PLACE_ALL);
        h.server.getPluginManager().callEvent(put);
        assertTrue(put.isCancelled(), "nothing can be put into a loot chest");
        InventoryClickEvent drop = new InventoryClickEvent(first.getOpenInventory(), InventoryType.SlotType.CONTAINER,
                slot, ClickType.DROP, InventoryAction.DROP_ALL_SLOT);
        h.server.getPluginManager().callEvent(drop);
        assertTrue(drop.isCancelled(), "loot is never thrown on the ground from the chest");
        InventoryClickEvent take = new InventoryClickEvent(first.getOpenInventory(), InventoryType.SlotType.CONTAINER,
                slot, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        h.server.getPluginManager().callEvent(take);
        assertFalse(take.isCancelled(), "taking is fine");

        h.ticks(30);
        assertFalse(chest.isRemoved(), "a chest with loot stays");
        first.closeInventory();
        second.closeInventory();
        h.ticks(2);
        assertFalse(chest.isRemoved(), "closing a chest that still has loot keeps it");
        assertEquals(Material.CHEST, chest.block().getType());
    }

    @Test
    void anEmptiedChestDisappearsOnceClosed() {
        List<TestPlayer> players = h.join(2);
        SuperpowerMatch match = h.startFight();
        LootManager loot = loot(match);
        int before = loot.chests().size();
        LootChest chest = loot.chests().getFirst();
        Block block = chest.block();
        TestPlayer player = players.getFirst();
        standNextTo(player, chest, 1);
        rightClick(player, block);
        chest.getInventory().clear();
        h.ticks(12);
        assertFalse(chest.isRemoved(), "not while someone still looks inside");
        player.closeInventory();
        h.ticks(2);
        assertTrue(chest.isRemoved());
        assertEquals(before - 1, loot.chests().size());
        assertEquals(Material.AIR, block.getType(), "the block is restored");
        assertFalse(h.hub.tempBlocks().isTemporary(block));
    }

    @Test
    void viewersWhoWalkAwayOrAreOutHaveTheChestClosed() {
        List<TestPlayer> players = h.join(2);
        SuperpowerMatch match = h.startFight();
        LootChest chest = loot(match).chests().getFirst();
        TestPlayer player = players.getFirst();
        standNextTo(player, chest, 1);
        rightClick(player, chest.block());
        assertSame(chest.getInventory(), player.getOpenInventory().getTopInventory());
        player.teleport(chest.center().add(20, 0, 0));
        h.ticks(11);
        assertNotSame(chest.getInventory(), player.getOpenInventory().getTopInventory(),
                "the chest closes when its viewer walks away");
    }

    @Test
    void teamsShareTheChestsToo() {
        h.hub.config().setTeamSize(2);
        List<TestPlayer> players = h.join(4);
        SuperpowerMatch match = h.startFight();
        assertTrue(match.session().isTeamMode());
        LootChest chest = loot(match).chests().getFirst();
        TestPlayer first = players.get(0);
        TestPlayer enemy = null;
        for (TestPlayer other : players) {
            if (!match.session().sameTeam(first, other)) {
                enemy = other;
            }
        }
        assertNotNull(enemy);
        standNextTo(first, chest, 1);
        standNextTo(enemy, chest, -1);
        rightClick(first, chest.block());
        rightClick(enemy, chest.block());
        assertSame(chest.getInventory(), first.getOpenInventory().getTopInventory());
        assertSame(chest.getInventory(), enemy.getOpenInventory().getTopInventory());
    }

    // =================================================================== nearby hint

    /** The sparkles of {@code chest} sent to {@code player} alone. */
    private static List<TestPlayer.SentParticle> hints(TestPlayer player, LootChest chest) {
        return player.particles().stream()
                .filter(sent -> sent.particle() == Particle.WAX_ON && sent.at().distanceSquared(chest.center()) < 1.0)
                .toList();
    }

    /** {@code distance} blocks from the chest, towards the arena center (so still inside the arena). */
    private static Location towardsCenter(LootChest chest, Location center, double distance) {
        Location at = chest.center();
        Vector direction = center.toVector().subtract(at.toVector()).setY(0);
        if (direction.lengthSquared() < 1) {
            direction = new Vector(1, 0, 0);
        }
        Location target = at.add(direction.normalize().multiply(distance));
        target.setY(chest.block().getY());
        return target;
    }

    @Test
    void unopenedNormalChestsSparkleOnlyForViewersNearby() {
        h.settings().set("loot.chances.rare", 0);
        TestPlayer watcher = h.join("Watcher");
        h.hub.games().setPrefersSpectating(watcher, true);
        List<TestPlayer> players = h.join(2);
        SuperpowerMatch match = h.startFight();
        LootChest chest = loot(match).chests().getFirst();
        assertEquals(LootTier.NORMAL, chest.tier());
        TestPlayer near = players.get(0);
        TestPlayer far = players.get(1);
        standNextTo(near, chest, 3);
        far.teleport(towardsCenter(chest, match.session().arena().center(), 20));
        watcher.teleport(towardsCenter(chest, match.session().arena().center(), 5));
        assertTrue(match.session().isSpectator(watcher));
        near.particles().clear();
        far.particles().clear();
        watcher.particles().clear();

        h.ticks(40); // exactly two whole seconds, whatever the phase of the effect timer
        List<TestPlayer.SentParticle> seen = hints(near, chest);
        assertEquals(2, seen.size(), "one sparkle a second for a player 3 blocks away");
        assertTrue(seen.stream().allMatch(sent -> sent.count() == 2), "a few small particles (particle scale 1)");
        assertTrue(hints(far, chest).isEmpty(), "nothing for a player 20 blocks away");
        assertEquals(2, hints(watcher, chest).size(), "spectators nearby see it too");

        rightClick(near, chest.block());
        near.closeInventory();
        near.particles().clear();
        h.ticks(41);
        assertFalse(chest.isRemoved(), "the chest still has loot");
        assertTrue(hints(near, chest).isEmpty(), "an opened chest no longer sparkles");
    }

    @Test
    void theNearbyHintCanBeTurnedOffOrShortened() {
        h.settings().set("loot.chances.rare", 0);
        h.settings().set("loot.nearby-hint", false);
        List<TestPlayer> players = h.join(2);
        SuperpowerMatch match = h.startFight();
        LootChest chest = loot(match).chests().getFirst();
        TestPlayer near = players.getFirst();
        standNextTo(near, chest, 2);
        near.particles().clear();
        h.ticks(41);
        assertTrue(hints(near, chest).isEmpty(), "loot.nearby-hint: false");
        assertTrue(h.hub.games().stop(Component.text("test")));

        h.settings().set("loot.nearby-hint", true);
        h.settings().set("loot.nearby-hint-radius", 4);
        h.settings().set("effects.particle-scale", 2.0);
        match = h.startFight();
        chest = loot(match).chests().getFirst();
        near.teleport(towardsCenter(chest, match.session().arena().center(), 6));
        near.particles().clear();
        h.ticks(41);
        assertTrue(hints(near, chest).isEmpty(), "6 blocks is beyond a radius of 4");
        standNextTo(near, chest, 3);
        h.ticks(41);
        List<TestPlayer.SentParticle> seen = hints(near, chest);
        assertFalse(seen.isEmpty(), "3 blocks is within it");
        assertTrue(seen.stream().allMatch(sent -> sent.count() == 4), "scaled by effects.particle-scale");
    }

    @Test
    void theAbilityItemCastsInsteadOfOpeningAndLeavesTheOffHandConsumableAlone() {
        List<TestPlayer> players = h.join(2);
        SuperpowerMatch match = probeFight(players);
        LootManager loot = loot(match);
        TestPlayer player = players.getFirst();
        LootChest chest = loot.chests().getFirst();
        standNextTo(player, chest, 1);
        player.getInventory().setItemInOffHand(loot.consumables().create(LootConsumables.Kind.RECHARGE_CHARM, 2, 25));
        player.getInventory().setHeldItemSlot(AbilityItems.SLOT);
        assertTrue(loot.isAbilityItem(player.getInventory().getItemInMainHand()));

        PlayerInteractEvent click = rightClick(player, chest.block());
        assertEquals(Event.Result.DENY, click.useInteractedBlock(), "never the vanilla chest");
        assertNotSame(chest.getInventory(), player.getOpenInventory().getTopInventory(),
                "the ability item casts, it does not open the chest");
        assertTrue(match.context().cooldownRemaining(player) > 0, "the active skill was used");
        // The client sends the off-hand use right after a main-hand use that does nothing in vanilla.
        PlayerInteractEvent offHand = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK,
                player.getInventory().getItemInOffHand(), chest.block(), BlockFace.UP, EquipmentSlot.OFF_HAND);
        h.server.getPluginManager().callEvent(offHand);
        assertEquals(2, player.getInventory().getItemInOffHand().getAmount(), "the charm is not used with the skill");
        assertTrue(match.context().cooldownRemaining(player) > 0, "the cooldown keeps running");

        player.getInventory().setHeldItemSlot(0);
        rightClick(player, chest.block());
        assertSame(chest.getInventory(), player.getOpenInventory().getTopInventory(), "any other hand opens it");
    }

    // =================================================================== supply drops

    @Test
    void supplyDropsAreAnnouncedAndFallWhenTheBorderStartsShrinking() {
        h.settings().set("border.start-delay-seconds", 2);
        h.settings().set("border.shrink-seconds", 60);
        h.settings().set("border.final-diameter", 20);
        h.settings().set("loot.supply.min", 2);
        List<TestPlayer> players = h.join(2);
        SuperpowerMatch match = h.startFight();
        LootManager loot = loot(match);
        chat(players.getFirst());
        h.ticks(20);
        assertEquals(0, loot.fallingCount(), "nothing before the border moves");
        h.tickUntil(() -> loot.fallingCount() > 0, 40, "supply drops falling");
        assertEquals(2, loot.fallingCount());
        List<String> lines = chat(players.getFirst());
        assertEquals(2, lines.stream().filter(line -> line.contains("보급 상자")).count(),
                "every drop is announced: " + lines);
        assertTrue(lines.stream().anyMatch(line -> line.contains("(") && line.contains(",")), "with coordinates");
        for (ItemDisplay crate : loot.fallingCrates()) {
            assertTrue(crate.isGlowing());
            assertTrue(crate.getLocation().getY() > FlatWorld.SURFACE + 20, "high in the sky");
        }

        h.ticks(loot.settings().supplyFallTicks() + 1);
        assertEquals(0, loot.fallingCount());
        List<LootChest> supplies = loot.chests().stream().filter(c -> c.tier() == LootTier.SUPPLY).toList();
        assertEquals(2, supplies.size(), "both landed as chests");
        Location center = match.session().arena().center();
        for (LootChest supply : supplies) {
            assertEquals(Material.CHEST, supply.block().getType());
            assertFalse(supply.getInventory().isEmpty());
            assertTrue(Math.abs(supply.center().getX() - center.getX()) <= 10.5
                    && Math.abs(supply.center().getZ() - center.getZ()) <= 10.5, "inside the final border");
            ItemDisplay marker = supply.marker();
            assertNotNull(marker);
            assertTrue(marker.isValid() && marker.isGlowing(), "a glowing marker while unopened");
        }
        assertEquals(supplies.size(), displays().size(), "the falling crates are gone, the markers stay");

        LootChest first = supplies.getFirst();
        ItemDisplay marker = first.marker();
        standNextTo(players.getFirst(), first, 1);
        rightClick(players.getFirst(), first.block());
        assertTrue(first.wasOpened());
        assertNull(first.marker());
        assertFalse(marker.isValid(), "the marker goes once opened");
    }

    // =================================================================== consumables

    @Test
    void ultimateCrystalChargesTheGaugeButNotDuringTheRechargeDelay() {
        h.settings().set("ultimate.recharge-delay-seconds", 1);
        List<TestPlayer> players = h.join(2);
        SuperpowerMatch match = probeFight(players);
        TestPlayer player = players.getFirst();
        LootManager loot = loot(match);
        player.getInventory().setItemInMainHand(loot.consumables().create(LootConsumables.Kind.ULTIMATE_CRYSTAL, 3,
                25));
        match.setGauge(player, 10);

        PlayerInteractEvent use = useHeld(player);
        assertEquals(Event.Result.DENY, use.useItemInHand());
        assertEquals(35.0, match.context().gauge(player), EPSILON);
        assertEquals(2, player.getInventory().getItemInMainHand().getAmount(), "one crystal used");
        useHeld(player);
        assertEquals(35.0, match.context().gauge(player), EPSILON, "one click is one use");

        h.ticks(1);
        match.setGauge(player, 100);
        assertTrue(match.useUltimate(player).succeeded());
        assertEquals(0.0, match.context().gauge(player), EPSILON);
        actionBars(player);
        h.ticks(LootManager.USE_REPEAT_TICKS);
        useHeld(player);
        assertEquals(0.0, match.context().gauge(player), EPSILON, "nothing charges during the recharge delay");
        assertEquals(2, player.getInventory().getItemInMainHand().getAmount(), "a refused crystal is kept");
        assertTrue(actionBars(player).stream().anyMatch(bar -> bar.contains("직후")), "the player is told why");
        assertEquals(ItemUseResult.GAUGE_LOCKED, match.chargeGaugeWithItem(player, 25));

        h.ticks(21);
        useHeld(player);
        assertEquals(25.0, match.context().gauge(player), EPSILON, "fine once the delay is over");
        assertEquals(1, player.getInventory().getItemInMainHand().getAmount());
        match.setGauge(player, 100);
        assertEquals(ItemUseResult.GAUGE_FULL, match.chargeGaugeWithItem(player, 25));
        h.ticks(LootManager.USE_REPEAT_TICKS);
        actionBars(player);
        useHeld(player);
        assertEquals(1, player.getInventory().getItemInMainHand().getAmount(), "not wasted on a full gauge");
        assertTrue(actionBars(player).stream().anyMatch(bar -> bar.contains("가득")), "the player is told why");
    }

    @Test
    void holdingTheButtonUsesOneConsumablePerPress() {
        List<TestPlayer> players = h.join(2);
        SuperpowerMatch match = probeFight(players);
        TestPlayer player = players.getFirst();
        LootManager loot = loot(match);
        player.getInventory().setItemInMainHand(loot.consumables().create(LootConsumables.Kind.ULTIMATE_CRYSTAL, 3,
                25));
        match.setGauge(player, 60);
        // A held button: the client repeats the use every 4 ticks.
        for (int i = 0; i < 6; i++) {
            useHeld(player);
            h.ticks(4);
        }
        assertEquals(85.0, match.context().gauge(player), EPSILON, "one crystal for one press");
        assertEquals(2, player.getInventory().getItemInMainHand().getAmount());
        h.ticks(LootManager.USE_REPEAT_TICKS);
        useHeld(player);
        assertEquals(100.0, match.context().gauge(player), EPSILON, "the next press uses the next crystal");
        assertEquals(1, player.getInventory().getItemInMainHand().getAmount());

        h.ticks(LootManager.USE_REPEAT_TICKS);
        actionBars(player);
        for (int i = 0; i < 6; i++) {
            useHeld(player);
            h.ticks(4);
        }
        assertEquals(1, actionBars(player).stream().filter(bar -> bar.contains("가득")).count(),
                "a refused press is told once, not on every repeat");
        assertEquals(1, player.getInventory().getItemInMainHand().getAmount());
    }

    @Test
    void rechargeCharmEndsTheActiveCooldown() {
        List<TestPlayer> players = h.join(2);
        SuperpowerMatch match = probeFight(players);
        TestPlayer player = players.getFirst();
        LootManager loot = loot(match);
        assertTrue(match.useActive(player).succeeded());
        assertTrue(match.context().cooldownRemaining(player) > 0);
        player.getInventory().setItemInMainHand(loot.consumables().create(LootConsumables.Kind.RECHARGE_CHARM, 2,
                25));

        useHeld(player);
        assertEquals(0.0, match.context().cooldownRemaining(player), EPSILON);
        assertEquals(0, player.getCooldown(h.game.items().cooldownGroup()), "the item overlay is cleared");
        assertEquals(1, player.getInventory().getItemInMainHand().getAmount());

        h.ticks(LootManager.USE_REPEAT_TICKS);
        useHeld(player);
        assertEquals(1, player.getInventory().getItemInMainHand().getAmount(), "not wasted when ready anyway");
        assertEquals(ItemUseResult.COOLDOWN_READY, match.rechargeActiveWithItem(player));
        assertTrue(match.useActive(player).succeeded(), "the active works again");
    }

    @Test
    void consumablesAreRefusedWhenSilencedOrOutsideTheFight() {
        h.registerTestAbilities();
        h.settings().set("prep-seconds", 30);
        List<TestPlayer> players = h.join(2);
        GameSession session = h.start();
        h.tickUntilPhase(MatchPhase.PREP);
        SuperpowerMatch match = h.match();
        TestPlayer player = players.getFirst();
        LootManager loot = loot(match);
        player.getInventory().setItemInMainHand(loot.consumables().create(LootConsumables.Kind.ULTIMATE_CRYSTAL, 2,
                25));
        useHeld(player);
        assertEquals(2, player.getInventory().getItemInMainHand().getAmount(), "not before the fight");
        assertEquals(ItemUseResult.NOT_FIGHTING, match.chargeGaugeWithItem(player, 25));

        h.tickUntilPhase(MatchPhase.FIGHT);
        assertTrue(match.replaceAbility(player, "probe"));
        player.getInventory().setItemInMainHand(loot.consumables().create(LootConsumables.Kind.ULTIMATE_CRYSTAL, 2,
                25));
        match.context().silence(player, 40);
        h.ticks(1);
        useHeld(player);
        assertEquals(0.0, match.context().gauge(player), EPSILON, "silenced players cannot use it");
        assertEquals(2, player.getInventory().getItemInMainHand().getAmount());
        assertEquals(ItemUseResult.SILENCED, match.chargeGaugeWithItem(player, 25));
        assertEquals(ItemUseResult.SILENCED, match.rechargeActiveWithItem(player));

        TestPlayer watcher = h.join("Watcher");
        assertEquals(ItemUseResult.NOT_IN_PLAY, match.chargeGaugeWithItem(watcher, 25));

        assertTrue(h.hub.games().stop(Component.text("test")));
        assertEquals(GameState.CLOSED, session.state());
        assertEquals(ItemUseResult.NOT_FIGHTING, match.chargeGaugeWithItem(player, 25), "not after the match");
        assertEquals(ItemUseResult.NOT_FIGHTING, match.rechargeActiveWithItem(player));
    }

    // =================================================================== cleanup

    @Test
    void everythingIsGoneWhenTheMatchIsStopped() {
        // Let participants drop items, to check that dropped loot does not outlive the match.
        h.hub.plugin().getConfig().set("protection.item-drop", false);
        h.hub.plugin().saveConfig();
        h.hub.config().load();
        h.settings().set("loot.enabled", true);
        h.settings().set("border.start-delay-seconds", 1);
        h.settings().set("border.shrink-seconds", 60);
        List<TestPlayer> players = h.join(2);
        SuperpowerMatch match = h.startFight();
        LootManager loot = loot(match);
        h.tickUntil(() -> loot.fallingCount() > 0, 60, "a supply drop falling");
        assertFalse(displays().isEmpty());
        List<Block> blocks = new ArrayList<>();
        for (LootChest chest : loot.chests()) {
            blocks.add(chest.block());
        }
        assertFalse(blocks.isEmpty());
        LootChest open = loot.chests().getFirst();
        TestPlayer viewer = players.getFirst();
        standNextTo(viewer, open, 1);
        rightClick(viewer, open.block());
        assertSame(open.getInventory(), viewer.getOpenInventory().getTopInventory());
        Item dropped = viewer.getWorld().dropItem(viewer.getLocation(), new ItemStack(Material.GOLDEN_APPLE));
        PlayerDropItemEvent drop = new PlayerDropItemEvent(viewer, dropped);
        h.server.getPluginManager().callEvent(drop);
        assertFalse(drop.isCancelled(), "dropping is allowed in this arena");

        assertTrue(h.hub.games().stop(Component.text("test")));
        assertEquals(0, h.hub.tempBlocks().size(), "no temporary block left");
        for (Block block : blocks) {
            assertEquals(Material.AIR, block.getType(), "every chest block is gone");
        }
        assertTrue(displays().isEmpty(), "no falling crate or marker left");
        assertFalse(dropped.isValid(), "dropped items do not outlive the match");
        assertNotSame(open.getInventory(), viewer.getOpenInventory().getTopInventory(), "open chests were closed");
        assertTrue(loot.chests().isEmpty());
        assertEquals(0, loot.fallingCount());
    }

    @Test
    void enderPearlsInFlightNeverOutliveTheMatchOrCarryPlayersAcrossWorlds() {
        World lobby = h.server.addSimpleWorld("lobby");
        List<TestPlayer> players = h.join(3);
        SuperpowerMatch match = h.startFight();
        GameSession session = match.session();
        TestPlayer thrower = players.get(0);
        EnderPearl pearl = throwPearl(thrower);
        assertTrue(session.isMatchEntity(pearl), "a thrown pearl belongs to the match");
        assertFalse(pearl.isPersistent());

        TestPlayer leaver = players.get(1);
        EnderPearl leaving = throwPearl(leaver);
        assertTrue(leaver.disconnect());
        assertFalse(leaving.isValid(), "removed before the player is saved, so it never comes back on the next join");
        assertTrue(pearl.isValid(), "other pearls fly on");

        assertFalse(pearlTeleportCancelled(thrower, thrower.getLocation().add(3, 0, 0)), "pearls work in the arena");
        assertTrue(pearlTeleportCancelled(thrower, lobby.getSpawnLocation()), "but never out of the arena world");
        TestPlayer watcher = h.join("Watcher");
        assertTrue(session.isMember(watcher));
        assertFalse(session.isInPlay(watcher));
        assertTrue(pearlTeleportCancelled(watcher, thrower.getLocation()), "and never for anyone not in play");

        Endermite mite = thrower.getWorld().spawn(thrower.getLocation(), Endermite.class);
        CreatureSpawnEvent hatch = new CreatureSpawnEvent(mite, CreatureSpawnEvent.SpawnReason.ENDER_PEARL);
        h.server.getPluginManager().callEvent(hatch);
        assertTrue(hatch.isCancelled(), "no endermite that would outlive the match");

        assertTrue(h.hub.games().stop(Component.text("test")));
        assertFalse(pearl.isValid(), "a pearl still flying is gone before everyone is restored");
    }

    @Test
    void aPearlThrownDuringTheResultsIsRemovedAtOnce() {
        List<TestPlayer> players = h.join(2);
        SuperpowerMatch match = h.startFight();
        GameSession session = match.session();
        session.eliminate(players.get(1), players.get(0));
        assertEquals(GameState.ENDING, session.state(), "a winner");
        TestPlayer winner = players.get(0);
        EnderPearl pearl = throwPearl(winner);
        assertFalse(pearl.isValid(), "the match is over: nothing it creates stays");
        assertTrue(pearlTeleportCancelled(winner, winner.getLocation().add(3, 0, 0)), "no pearl rides after the end");
    }

    @Test
    void chestsVanishAsSoonAsTheMatchIsDecided() {
        List<TestPlayer> players = h.join(2);
        SuperpowerMatch match = h.startFight();
        GameSession session = match.session();
        List<Block> blocks = new ArrayList<>();
        for (LootChest chest : loot(match).chests()) {
            blocks.add(chest.block());
        }
        assertFalse(blocks.isEmpty());
        session.eliminate(players.get(1), players.get(0));
        assertEquals(GameState.ENDING, session.state(), "a winner");
        for (Block block : blocks) {
            assertEquals(Material.AIR, block.getType());
        }
        assertEquals(0, h.hub.tempBlocks().size());
        assertTrue(loot(match).chests().isEmpty());
    }

    @Test
    void practiceHasNoLootUnlessEnabled() {
        TestPlayer admin = h.join("Admin");
        admin.setOp(true);
        assertTrue(h.server.dispatchCommand(admin, "게임 능력 연습"));
        h.tickUntil(() -> h.match() != null && h.match().hasStarted() && h.match().phase() == MatchPhase.FIGHT,
                400, "the practice to start");
        assertTrue(h.match().isPractice());
        assertNull(h.match().loot(), "no loot in practice by default");
        assertEquals(0, h.hub.tempBlocks().size());
        assertTrue(h.hub.games().stop(Component.text("test")));

        h.settings().set("loot.practice", true);
        assertTrue(h.server.dispatchCommand(admin, "게임 능력 연습"));
        h.tickUntil(() -> h.match() != null && h.match().hasStarted() && h.match().phase() == MatchPhase.FIGHT,
                400, "the practice to start again");
        assertNotNull(h.match().loot(), "loot.practice turns it on");
        assertFalse(h.match().loot().chests().isEmpty());
    }
}
