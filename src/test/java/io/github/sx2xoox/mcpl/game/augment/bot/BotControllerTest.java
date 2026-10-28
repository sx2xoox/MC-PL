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
import io.github.sx2xoox.mcpl.game.augment.bot.BotBrain.Behaviour;
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
        for (BotPersonality each : BotPersonality.values()) {
            h.settings().set(BotSettings.PERSONALITIES + "." + each.key(), 0); // the plain utility AI
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

    // =================================================================== hazards, getting unstuck, the base

    @Test
    void aBotInPowderSnowClearsItAndClimbsOut() {
        BotController bot = fightingBot("보통");
        World world = h.match().wild().world();
        WanderingTrader legs = bot.body().legs();
        Location feet = legs.getLocation();
        int x = feet.getBlockX();
        int y = feet.getBlockY();
        int z = feet.getBlockZ();
        admin.teleport(feet.clone().add(0, 0, 30));
        world.getBlockAt(x, y, z).setType(Material.POWDER_SNOW);
        world.getBlockAt(x, y + 1, z).setType(Material.POWDER_SNOW);
        h.ticks(1);
        assertEquals(1, bot.snowEscapes(), "out of the snow before anything else");
        assertFalse(world.getBlockAt(x, y, z).getType() == Material.POWDER_SNOW, "cleared where it stood");
        assertFalse(world.getBlockAt(x, y + 1, z).getType() == Material.POWDER_SNOW);
    }

    @Test
    void hazardsAreNeverAGoalOrASpawnSpot() {
        BotController bot = fightingBot("보통");
        World world = h.match().wild().world();
        Location at = bot.body().legs().getLocation().add(20, 0, 20);
        int top = world.getHighestBlockYAt(at.getBlockX(), at.getBlockZ());
        world.getBlockAt(at.getBlockX(), top + 1, at.getBlockZ()).setType(Material.POWDER_SNOW);
        assertTrue(Hazards.hazardousColumn(world, at.getBlockX(), at.getBlockZ()));
        Location goal = BotController.ground(world, at.getBlockX() + 0.5, at.getBlockZ() + 0.5);
        assertFalse(goal.getBlockX() == at.getBlockX() && goal.getBlockZ() == at.getBlockZ(),
                "a snow pit is walked around, not into");
        Location spot = new Location(world, at.getBlockX() + 1.5, top + 1, at.getBlockZ() + 0.5);
        assertFalse(BotManager.dry(spot), "no spawn next to powder snow");
        Location cactus = bot.body().legs().getLocation().add(-20, 0, -20);
        world.getBlockAt(cactus.getBlockX() + 1, cactus.getBlockY(), cactus.getBlockZ()).setType(Material.CACTUS);
        assertFalse(BotManager.dry(cactus), "nor next to a cactus");
    }

    @Test
    void aStuckBotTunnelsThroughAWallTowardsItsTarget() {
        BotController.autoRecovery = true;
        BotController bot = fightingBot("지옥");
        World world = h.match().wild().world();
        WanderingTrader legs = bot.body().legs();
        Location at = legs.getLocation();
        int x = at.getBlockX();
        int y = at.getBlockY();
        int z = at.getBlockZ();
        for (int dz = -3; dz <= 3; dz++) {
            for (int dy = 0; dy <= 4; dy++) {
                world.getBlockAt(x + 2, y + dy, z + dz).setType(Material.STONE);
            }
        }
        admin.setGlowing(true); // known through the wall
        admin.teleport(at.clone().add(14, 0, 0));
        // the mock pathfinder never moves the legs: the bot finds itself stuck and makes its own way
        h.ticks(Recovery.WINDOW * 2 + 120);
        assertTrue(bot.recoveries().getOrDefault(Recovery.Kind.TUNNEL, 0) > 0, "tunnelled: " + bot.recoveries());
        assertTrue(world.getBlockAt(x + 2, y, z).getType().isAir() || world.getBlockAt(x + 2, y, z + 1).getType()
                .isAir() || world.getBlockAt(x + 2, y, z - 1).getType().isAir(), "a 1x2 hole through the wall");
        assertTrue(legs.getLocation().getX() > at.getX() + 1, "and walked into it");
    }

    @Test
    void theBaseIsClosedWithoutHolesAndItsDoorShuts() {
        h.settings().set(AugmentSettings.FARMING, 120);
        assertTrue(h.server.dispatchCommand(admin, "게임 증강 봇전 1 지옥"));
        h.tickUntilPhase(MatchPhase.FARMING);
        BotController bot = h.match().bots().all().getFirst();
        World world = h.match().wild().world();
        h.tickUntil(() -> bot.base() != null, 400, "a base site");
        BasePlanner.Plan plan = bot.base();
        bot.body().legs().teleport(new Location(world, plan.x() + 0.5, plan.floorY() + 1, plan.z() + 0.5));
        h.tickUntil(() -> bot.built() >= plan.placements().size(), 20 * 100, "the whole base");
        for (BasePlanner.Placement p : plan.placements()) {
            if (p.part() == BasePlanner.Part.WALL || p.part() == BasePlanner.Part.ROOF) {
                assertTrue(world.getBlockAt(p.x(), p.y(), p.z()).getType().isSolid(), "no hole at " + p);
            }
        }
        Mannequin body = bot.body().body();
        bot.closeDoor(body);
        assertTrue(bot.doorClosed());
        for (int[] cell : plan.door()) {
            assertTrue(world.getBlockAt(cell[0], cell[1], cell[2]).getType().isSolid(), "the doorway is shut");
        }
        bot.openDoor();
        assertFalse(bot.doorClosed());
        for (int[] cell : plan.door()) {
            assertTrue(world.getBlockAt(cell[0], cell[1], cell[2]).getType().isAir(), "and open again");
        }
    }

    @Test
    void outOfBlocksItWaitsInsteadOfLeavingHoles() {
        h.settings().set(AugmentSettings.FARMING, 120);
        assertTrue(h.server.dispatchCommand(admin, "게임 증강 봇전 1 지옥"));
        h.tickUntilPhase(MatchPhase.FARMING);
        BotController bot = h.match().bots().all().getFirst();
        World world = h.match().wild().world();
        h.tickUntil(() -> bot.base() != null, 400, "a base site");
        BasePlanner.Plan plan = bot.base();
        bot.body().legs().teleport(new Location(world, plan.x() + 0.5, plan.floorY() + 1, plan.z() + 0.5));
        h.ticks(60);
        int built = bot.built();
        for (int i = 0; i < built; i++) {
            BasePlanner.Placement p = plan.placements().get(i);
            assertFalse(world.getBlockAt(p.x(), p.y(), p.z()).getType().isAir(), "every counted block stands: " + p);
        }
        assertTrue(bot.built() <= bot.blocks() + built, "never counts a block it did not have");
    }

    @Test
    void anAssassinCreepsUpFromBehind() {
        for (BotPersonality each : BotPersonality.values()) {
            h.settings().set(BotSettings.PERSONALITIES + "." + each.key(), each == BotPersonality.ASSASSIN ? 1 : 0);
        }
        BotController bot = fightingBot("보통");
        assertEquals(BotPersonality.ASSASSIN, bot.personality());
        Location at = bot.body().legs().getLocation();
        Location spot = at.clone().add(7, 0, 0);
        spot.setYaw(-90); // looking east: its back to the bot
        admin.teleport(spot);
        h.ticks(20);
        assertEquals(Behaviour.ENGAGE, bot.decision().behaviour());
        assertEquals(BotMotion.Gait.SNEAK, bot.gait(), "crouch-walking in");
        assertTrue(bot.body().body().isSneaking());
        assertTrue(BotMotion.blocksPerSecond(BotMotion.Gait.SNEAK, 5, 1) < BotMotion.WALK_BPS);
    }

    @Test
    void theFartherOutTheEarlierItHeadsIn() {
        assertEquals(400, BotController.borderLookahead(10, 12), "inside the final zone: 20 s");
        assertTrue(BotController.borderLookahead(150, 12) > BotController.borderLookahead(60, 12));
        assertEquals(2400, BotController.borderLookahead(1000, 12), "at most 2 min");
    }
}
