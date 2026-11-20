package io.github.sx2xoox.mcpl.game.augment.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.sx2xoox.mcpl.game.augment.AugmentSettings;
import io.github.sx2xoox.mcpl.game.augment.runtime.MatchPhase;
import io.github.sx2xoox.mcpl.game.augment.support.AugmentHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * preview83 on the mock world: a player who pillars up out of a bot's reach has the block under their feet mined
 * away, and a bot with lava poured on its feet steps straight out of it.
 */
class PerchLavaControllerTest {

    private AugmentHarness h;
    private TestPlayer admin;

    @BeforeEach
    void setUp() {
        h = new AugmentHarness();
        admin = h.join("Admin");
        admin.setOp(true);
        h.settings().set(AugmentSettings.BORDER_DELAY, 600);
        for (BotPersonality each : BotPersonality.values()) {
            h.settings().set(BotSettings.PERSONALITIES + "." + each.key(), 0);
        }
        BotController.autoRecovery = false; // the mock pathfinder never moves the legs
    }

    @AfterEach
    void tearDown() {
        BotController.autoRecovery = true;
        h.close();
    }

    private BotController fightingBot(String difficulty) {
        assertTrue(h.server.dispatchCommand(admin, "게임 증강 봇전 1 " + difficulty));
        h.tickUntilPhase(MatchPhase.FIGHT);
        return h.match().bots().all().getFirst();
    }

    /** Stone under the feet level {@code y} over a square of {@code r} around (x, z), air above. */
    private static void floor(World world, int x, int y, int z, int r) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                world.getBlockAt(x + dx, y - 1, z + dz).setType(Material.STONE);
                for (int dy = 0; dy <= 8; dy++) {
                    world.getBlockAt(x + dx, y + dy, z + dz).setType(Material.AIR);
                }
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"지옥", "보통", "쉬움"})
    void aPlayerOnALowPillarHasTheBlockUnderTheirFeetMinedAway(String difficulty) {
        BotController bot = fightingBot(difficulty);
        WanderingTrader legs = bot.body().legs();
        Location feet = legs.getLocation();
        World world = feet.getWorld();
        int x = feet.getBlockX();
        int y = feet.getBlockY();
        int z = feet.getBlockZ();
        floor(world, x, y, z, 8);
        int px = x + 4;
        for (int dy = 0; dy < 4; dy++) {
            world.getBlockAt(px, y + dy, z).setType(Material.DIRT); // a four-high pillar 4 blocks away
        }
        admin.teleport(new Location(world, px + 0.5, y + 4, z + 0.5));
        h.ticks(30);
        assertEquals(admin.getUniqueId(), bot.decision().target());
        assertTrue(bot.perchAnswers().getOrDefault(Perch.Response.CHOP, 0) > 0,
                "chops their pillar instead of standing under it: " + bot.perchAnswers());
        // the mock pathfinder does not walk: put the legs on the spot beside the column
        legs.teleport(new Location(world, px - 0.5, y, z + 0.5));
        bot.body().body().teleport(legs.getLocation());
        h.ticks(bot.mineTicks() + 15);
        assertTrue(world.getBlockAt(px, y + 3, z).getType().isAir(), "the block under their feet is mined");
        assertTrue(bot.chops() >= 1);
        assertEquals(Material.DIRT, world.getBlockAt(px, y + 2, z).getType(), "one block at a time, like a player");
    }

    @Test
    void lavaPouredOnItsFeetIsLeftAtOnce() {
        BotController bot = fightingBot("보통");
        WanderingTrader legs = bot.body().legs();
        Location feet = legs.getLocation();
        World world = feet.getWorld();
        int x = feet.getBlockX();
        int y = feet.getBlockY();
        int z = feet.getBlockZ();
        floor(world, x, y, z, 8);
        admin.teleport(new Location(world, x + 0.5, y, z + 30.5)); // far off: nothing else to do
        h.ticks(5);
        world.getBlockAt(x, y, z).setType(Material.LAVA);
        world.getBlockAt(x + 1, y, z).setType(Material.LAVA); // flowing east
        legs.setVelocity(new Vector());
        h.ticks(1);
        assertTrue(bot.dangerEscapes() > 0, "it noticed the lava");
        Vector v = legs.getVelocity();
        assertTrue(Math.hypot(v.getX(), v.getZ()) > 0.15, "moving out: " + v);
        assertTrue(v.getX() <= 0, "not further into the flow: " + v);
    }

    @Test
    void anEnemyWithALavaBucketInHandIsFoughtFromBeyondItsReach() {
        BotController bot = fightingBot("지옥");
        WanderingTrader legs = bot.body().legs();
        Location feet = legs.getLocation();
        World world = feet.getWorld();
        int x = feet.getBlockX();
        int y = feet.getBlockY();
        int z = feet.getBlockZ();
        floor(world, x, y, z, 12);
        admin.getInventory().setItemInMainHand(new org.bukkit.inventory.ItemStack(Material.LAVA_BUCKET));
        admin.teleport(new Location(world, x + 4.5, y, z + 0.5));
        h.ticks(20);
        assertEquals(admin.getUniqueId(), bot.decision().target());
        var destinations = ((io.github.sx2xoox.mcpl.game.superpower.support.world.MockEntities.RecordingPathfinder)
                legs.getPathfinder()).destinations();
        Location to = destinations.getLast();
        assertTrue(to.distance(admin.getLocation()) > BotController.LAVA_SPACING,
                "keeps out of the bucket's reach: heads for " + to + " (" + to.distance(admin.getLocation()) + ")");
    }

    @Test
    void aFightNextToLavaMovesAwayFromItFirst() {
        BotController bot = fightingBot("지옥");
        WanderingTrader legs = bot.body().legs();
        Location feet = legs.getLocation();
        World world = feet.getWorld();
        int x = feet.getBlockX();
        int y = feet.getBlockY();
        int z = feet.getBlockZ();
        floor(world, x, y, z, 8);
        world.getBlockAt(x - 2, y, z).setType(Material.LAVA); // two blocks behind it: one knockback away
        admin.teleport(new Location(world, x + 2.5, y, z + 0.5));
        h.ticks(20);
        assertTrue(bot.lavaSidesteps() > 0, "steps away from the lava before trading hits beside it");
    }
}
