package io.github.sx2xoox.mcpl.game.augment.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import io.github.sx2xoox.mcpl.game.augment.AugmentSettings;
import io.github.sx2xoox.mcpl.game.augment.runtime.AugmentMatch;
import io.github.sx2xoox.mcpl.game.augment.runtime.MatchPhase;
import io.github.sx2xoox.mcpl.game.augment.support.AugmentHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.game.superpower.support.world.MockEntities;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.EntityMock;

/** The controller on a mock world: gaits, swimming, never inside a block, glowing rivals. */
class BotControllerTest {

    private AugmentHarness h;
    private TestPlayer admin;

    @BeforeEach
    void setUp() {
        h = new AugmentHarness();
        admin = h.join("Admin");
        admin.setOp(true);
        h.settings().set(AugmentSettings.BORDER_DELAY, 600); // no border to flee from
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    private BotController fightingBot(String difficulty) {
        assertTrue(h.server.dispatchCommand(admin, "게임 증강 봇전 1 " + difficulty));
        h.tickUntilPhase(MatchPhase.FIGHT);
        return h.match().bots().all().getFirst();
    }

    private static MockEntities.RecordingPathfinder pathfinder(BotController bot) {
        return (MockEntities.RecordingPathfinder) bot.body().legs().getPathfinder();
    }

    /** Blocks a second of its last path, without its speed augments (a 질풍 of a round makes it faster). */
    private static double lastSpeed(BotController bot) {
        List<Double> speeds = pathfinder(bot).speeds();
        var speed = bot.body().body().getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED);
        double factor = speed == null ? 1 : speed.getValue() / speed.getBaseValue();
        return BotMotion.groundSpeed(BotMotion.REFERENCE_ATTRIBUTE, speeds.getLast()) / factor;
    }

    @Test
    void itSprintsToAFarTargetAndWalksWhenClose() {
        BotController bot = fightingBot("지옥");
        Location at = bot.body().legs().getLocation();
        admin.teleport(at.clone().add(30, 0, 0));
        h.ticks(30);
        assertEquals(admin.getUniqueId(), bot.decision().target());
        assertEquals(BotMotion.Gait.SPRINT, bot.gait());
        assertEquals(BotMotion.SPRINT_BPS, lastSpeed(bot), 0.05, "a sprinting player's pace, not a slow walk");
        admin.teleport(bot.body().legs().getLocation().add(2, 0, 0));
        h.ticks(30);
        assertEquals(BotMotion.WALK_BPS, lastSpeed(bot), 0.05, "lining up and strafing at a walk");
    }

    @Test
    void itSwimsWithItsHeadUpTowardsWhereItGoes() {
        BotController bot = fightingBot("보통");
        WanderingTrader legs = bot.body().legs();
        admin.teleport(legs.getLocation().add(30, 0, 0));
        h.ticks(20);
        ((EntityMock) legs).setInWater(true);
        legs.setVelocity(new Vector());
        h.ticks(1);
        Vector velocity = legs.getVelocity();
        assertTrue(velocity.getY() > 0, "floats up: " + velocity);
        double flat = Math.hypot(velocity.getX(), velocity.getZ());
        assertTrue(flat >= BotMotion.SWIM_SPRINT * 0.9, "swims at a player's pace: " + velocity);
        Location to = pathfinder(bot).destinations().getLast();
        Location at = legs.getLocation();
        assertTrue(velocity.getX() * (to.getX() - at.getX()) + velocity.getZ() * (to.getZ() - at.getZ()) > 0,
                "towards where it goes: " + velocity);
    }

    @Test
    void itNeverPlacesABlockWhereItsLegsAreOrAreAboutToBe() {
        BotController bot = fightingBot("보통");
        World world = h.match().wild().world();
        WanderingTrader legs = bot.body().legs();
        Mannequin body = bot.body().body();
        Location feet = legs.getLocation();
        admin.teleport(feet.clone().add(0, 0, 20)); // nobody else near
        // the body is a tick behind the legs: it still stands a block back
        body.teleport(feet.clone().add(-1.2, 0, 0));
        int x = feet.getBlockX();
        int y = feet.getBlockY();
        int z = feet.getBlockZ();
        assertFalse(bot.place(body, x, y + 1, z, Material.STONE), "never on the legs' head");
        assertTrue(world.getBlockAt(x, y + 1, z).getType().isAir());
        legs.setVelocity(new Vector(0.3, 0, 0));
        assertTrue(bot.blocksSomeone(world, x + 1, y + 1, z), "nor where they walk next");
        legs.setVelocity(new Vector());
        assertFalse(bot.blocksSomeone(world, x + 3, y + 1, z), "a few blocks away is fine");
    }

    @Test
    void aBlockOnItsHeadIsLeftBeforeItSuffocates() {
        BotController bot = fightingBot("보통");
        World world = h.match().wild().world();
        WanderingTrader legs = bot.body().legs();
        Mannequin body = bot.body().body();
        Location feet = legs.getLocation();
        world.getBlockAt(feet.getBlockX(), feet.getBlockY() + 1, feet.getBlockZ()).setType(Material.STONE);
        double health = body.getHealth();
        h.ticks(1);
        Location now = legs.getLocation();
        assertFalse(world.hasCollisionsIn(BotController.standing(now.getX(), now.getY(), now.getZ())),
                "stepped out of the block");
        assertTrue(now.distance(feet) < 4, "to the nearest free spot");
        assertEquals(now.getX(), body.getLocation().getX(), 1.0E-9, "the body follows the legs, not into the wall");
        assertFalse(world.hasCollisionsIn(BotController.standing(body.getLocation().getX(), body.getLocation().getY(),
                body.getLocation().getZ())));
        assertEquals(health, body.getHealth(), 1.0E-9);
    }

    @Test
    void itBreaksItsOwnBlockToGetOutAndSuffocationDamageGetsItOut() {
        BotController bot = fightingBot("보통");
        World world = h.match().wild().world();
        WanderingTrader legs = bot.body().legs();
        Mannequin body = bot.body().body();
        Location spot = legs.getLocation().add(6, 0, 0);
        int x = spot.getBlockX();
        int y = spot.getBlockY();
        int z = spot.getBlockZ();
        assertTrue(bot.place(body, x, y + 1, z, Material.COBBLESTONE), "its own block, away from it");
        legs.teleport(new Location(world, x + 0.5, y, z + 0.5));
        h.ticks(1);
        assertTrue(world.getBlockAt(x, y + 1, z).getType().isAir(), "mined its own block out of the way");
        assertEquals(x + 0.5, legs.getLocation().getX(), 1.0E-9, "and stayed where it was");
        // terrain on its head that it somehow got into: the suffocation hit takes it out and does no harm
        world.getBlockAt(x, y + 1, z).setType(Material.STONE);
        double health = body.getHealth();
        body.setNoDamageTicks(0);
        body.damage(1, DamageSource.builder(DamageType.IN_WALL).build());
        assertEquals(health, body.getHealth(), 1.0E-9, "out of the wall before it hurts");
        Location now = legs.getLocation();
        assertFalse(world.hasCollisionsIn(BotController.standing(now.getX(), now.getY(), now.getZ())));
        assertFalse(world.getBlockAt(x, y + 1, z).getType().isAir(), "terrain is not its to break");
    }

    @Test
    void aGlowingRivalIsSeenThroughAWall() {
        BotController bot = fightingBot("지옥");
        Location at = bot.body().legs().getLocation();
        World world = at.getWorld();
        for (int dz = -6; dz <= 6; dz++) {
            for (int dy = 0; dy <= 6; dy++) {
                world.getBlockAt(at.getBlockX() + 10, at.getBlockY() + dy, at.getBlockZ() + dz)
                        .setType(Material.STONE);
            }
        }
        admin.teleport(at.clone().add(20, 0, 0));
        h.ticks(220); // longer than it remembers where it last saw someone
        assertFalse(admin.getUniqueId().equals(bot.decision().target()), "hidden behind the wall");
        admin.setGlowing(true); // everyone glows in the final zone
        h.ticks(20);
        assertEquals(admin.getUniqueId(), bot.decision().target(), "a glowing player shows through walls");
    }

    @Test
    void anIdleBotGrowsIdleAndAFightResetsIt() {
        BotController bot = fightingBot("보통");
        AugmentMatch match = h.match();
        long now = match.clock();
        bot.idle(now);
        assertTrue(bot.idle(now + BotController.IDLE_TICKS) >= 1, "a minute without a fight");
        bot.fought(now + BotController.IDLE_TICKS);
        assertEquals(0, bot.idle(now + BotController.IDLE_TICKS), 1.0E-9);
    }
}
