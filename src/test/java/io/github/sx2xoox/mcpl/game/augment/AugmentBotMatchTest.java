package io.github.sx2xoox.mcpl.game.augment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import io.github.sx2xoox.mcpl.core.game.GameResult;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.game.GameState;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.bot.BotBrain;
import io.github.sx2xoox.mcpl.game.augment.bot.BotController;
import io.github.sx2xoox.mcpl.game.augment.bot.BotDifficulty;
import io.github.sx2xoox.mcpl.game.augment.bot.BotManager;
import io.github.sx2xoox.mcpl.game.augment.bot.BotSettings;
import io.github.sx2xoox.mcpl.game.augment.command.BotCommand;
import io.github.sx2xoox.mcpl.game.augment.runtime.AugmentMatch;
import io.github.sx2xoox.mcpl.game.augment.runtime.MatchPhase;
import io.github.sx2xoox.mcpl.game.augment.support.AugmentHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.WanderingTrader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 봇전 on mock worlds: bots as participants, gear, augments, elimination, winning and cleanup. */
class AugmentBotMatchTest {

    private AugmentHarness h;
    private TestPlayer admin;

    @BeforeEach
    void setUp() {
        h = new AugmentHarness();
        admin = h.join("Admin");
        admin.setOp(true);
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    private AugmentMatch botMatch(String options) {
        assertTrue(h.server.dispatchCommand(admin, "게임 증강 봇전 " + options));
        h.tickUntil(() -> h.match() != null && h.match().phase() == MatchPhase.FARMING, 3000, "farming");
        return h.match();
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private void kill(LivingEntity victim, LivingEntity attacker) {
        victim.setNoDamageTicks(0);
        victim.damage(1000, DamageSource.builder(DamageType.PLAYER_ATTACK).withCausingEntity(attacker)
                .withDirectEntity(attacker).build());
    }

    @Test
    void oneHumanStartsAMatchFullOfNamedPlayerLikeBots() {
        AugmentMatch match = botMatch("3 쉬움");
        GameSession session = match.session();
        assertEquals(AugmentBotGame.ID, session.game().id());
        BotManager bots = match.bots();
        assertNotNull(bots);
        assertEquals(BotDifficulty.EASY, bots.difficulty());
        assertEquals(3, bots.all().size());
        assertEquals(4, session.aliveCount(), "bots count as alive participants");
        World world = match.wild().world();
        List<Mannequin> bodies = world.getEntitiesByClass(Mannequin.class).stream().toList();
        assertEquals(3, bodies.size(), "one player-like body each");
        assertEquals(3, world.getEntitiesByClass(WanderingTrader.class).size(), "and invisible legs");
        assertTrue(bodies.stream().anyMatch(body -> plain(body.customName()).equals("철수 봇")),
                "named like \"철수 봇\"");
        for (BotController bot : bots.all()) {
            assertTrue(session.isBot(bot.id()));
            assertEquals(bot.name(), session.plainName(bot.id()));
            assertTrue(bot.body().legs().isInvisible());
        }
        assertEquals(GameState.RUNNING, session.state());
    }

    @Test
    void theCountIsCappedAndOptionsComeInAnyOrder() {
        BotCommand.Options options = BotCommand.Options.parse("어려움 40");
        assertEquals(40, options.count());
        assertEquals(BotDifficulty.HARD, options.difficulty());
        assertNotNull(BotCommand.Options.parse("세개").error());
        h.settings().set(BotSettings.MAX, 4);
        AugmentMatch match = botMatch("40");
        assertEquals(4, match.bots().all().size());
        assertTrue(BotCommand.suggestions("지").contains("지옥"));
    }

    @Test
    void botsGearUpDuringTheFarmingAndBuildTheirBases() {
        AugmentMatch match = botMatch("2 지옥");
        BotController bot = match.bots().all().getFirst();
        double start = bot.loadout().score();
        h.ticks(20 * 6); // most of the 10 s farming of the harness
        assertTrue(bot.loadout().score() > start, "gear improves on the schedule");
        Mannequin body = bot.body().body();
        assertNotNull(body.getEquipment().getItemInMainHand());
        assertTrue(body.getEquipment().getItemInMainHand().getType().name().endsWith("_SWORD"));
        BotBrain.Decision decision = bot.decision();
        assertNotNull(decision);
        assertTrue(decision.behaviour() == BotBrain.Behaviour.BUILD_BASE
                || decision.behaviour() == BotBrain.Behaviour.FARM, "farming time is for farming: " + decision);
        assertNotNull(bot.base(), "picked a base spot");
        // the mock pathfinder does not walk: put it at its base like the real one would
        bot.body().legs().teleport(new org.bukkit.Location(match.wild().world(), bot.base().x() + 0.5,
                bot.base().floorY() + 1, bot.base().z() + 0.5));
        h.ticks(30);
        assertTrue(bot.built() > 0, "placed blocks");
        BasePlannerCheck.assertPlaced(match.wild().world(), bot.base(), bot.built());
    }

    @Test
    void botsGetBotSafeAugmentsAtTheRoundsShownInTheirTag() {
        AugmentMatch match = botMatch("2 보통");
        h.ticks(20 * 3); // the harness's round is at 2 s
        for (BotController bot : match.bots().all()) {
            List<AugmentInfo> owned = match.augments(bot.id());
            assertEquals(1, owned.size(), "one augment per round");
            assertTrue(owned.getFirst().forBots());
            assertNotNull(match.playerTag(bot.id()), "shown in the kill feed and results");
        }
    }

    @Test
    void killingEveryBotWinsAndBotKillsCount() {
        AugmentMatch match = botMatch("2 쉬움");
        GameSession session = match.session();
        h.tickUntilPhase(MatchPhase.FIGHT);
        List<BotController> bots = match.bots().all();
        LivingEntity first = bots.getFirst().body().body();
        kill(first, admin);
        assertFalse(session.isAlive(bots.getFirst().id()), "eliminated");
        assertEquals(1, session.stats().of(admin.getUniqueId()).kills(), "kill credited to the player");
        assertTrue(match.bonuses(admin.getUniqueId()).contains(
                io.github.sx2xoox.mcpl.game.augment.bonus.BonusCondition.FIRST_BLOOD), "a bot kill is first blood");
        kill(bots.get(1).body().body(), admin);
        assertEquals(GameState.ENDING, session.state());
        GameResult result = session.result();
        assertNotNull(result);
        assertTrue(result.isWinner(admin.getUniqueId()));
    }

    @Test
    void aBotCanKillThePlayerAndWin() {
        AugmentMatch match = botMatch("2 지옥");
        GameSession session = match.session();
        h.tickUntilPhase(MatchPhase.FIGHT);
        BotController bot = match.bots().all().getFirst();
        Mannequin body = bot.body().body();
        admin.teleport(body.getLocation().add(1.5, 0, 0));
        admin.setNoDamageTicks(0);
        admin.damage(1000, DamageSource.builder(DamageType.MOB_ATTACK).withCausingEntity(body)
                .withDirectEntity(body).build());
        assertEquals(GameState.ENDING, session.state(), "the last human fell");
        GameResult result = session.result();
        assertNotNull(result);
        assertTrue(result.isWinner(bot.id()), "the bot wins");
        for (BotController each : match.bots().all()) {
            assertEquals(session.isAlive(each.id()), result.isWinner(each.id()), "every bot still standing wins");
        }
        assertFalse(result.isWinner(admin.getUniqueId()));
        assertEquals(1, session.stats().of(bot.id()).kills(), "the bot got the kill");
    }

    @Test
    void playersCannotHurtBotsWhileFarming() {
        AugmentMatch match = botMatch("1 쉬움");
        LivingEntity body = match.bots().all().getFirst().body().body();
        double health = body.getHealth();
        body.damage(5, DamageSource.builder(DamageType.PLAYER_ATTACK).withCausingEntity(admin)
                .withDirectEntity(admin).build());
        assertEquals(health, body.getHealth(), 1.0E-6, "the PvP gate covers bots");
    }

    @Test
    void hitsOnTheInvisibleLegsLandOnTheBody() {
        AugmentMatch match = botMatch("1 쉬움");
        h.tickUntilPhase(MatchPhase.FIGHT);
        BotController bot = match.bots().all().getFirst();
        double health = bot.body().body().getHealth();
        bot.body().legs().damage(3, DamageSource.builder(DamageType.PLAYER_ATTACK).withCausingEntity(admin)
                .withDirectEntity(admin).build());
        assertTrue(bot.body().body().getHealth() < health);
    }

    @Test
    void aBotFightsAPlayerItSeesInTheFight() {
        h.settings().set(AugmentSettings.BORDER_DELAY, 600); // no border to flee from yet
        AugmentMatch match = botMatch("1 지옥");
        h.tickUntilPhase(MatchPhase.FIGHT);
        BotController bot = match.bots().all().getFirst();
        Mannequin body = bot.body().body();
        admin.teleport(body.getLocation().add(2, 0, 0));
        admin.getInventory().clear();
        double health = admin.getHealth();
        h.ticks(40);
        assertNotNull(bot.decision());
        assertEquals(admin.getUniqueId(), bot.decision().target(), "targets the visible player");
        assertTrue(admin.getHealth() < health, "and hits them");
    }

    @Test
    void everyBotSafeAugmentWorksOnABotWithoutErrors() {
        List<java.util.logging.LogRecord> problems = new java.util.ArrayList<>();
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                if (record.getLevel().intValue() >= java.util.logging.Level.WARNING.intValue()) {
                    problems.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        h.hub.plugin().getLogger().addHandler(handler);
        try {
            h.settings().set(AugmentSettings.BORDER_DELAY, 600);
            AugmentMatch match = botMatch("2 보통");
            h.tickUntilPhase(MatchPhase.FIGHT);
            List<BotController> bots = match.bots().all();
            BotController bot = bots.getFirst();
            int granted = 0;
            for (var entry : h.game.registry().all()) {
                if (entry.info().forBots() && match.grantBot(bot.id(), entry.info())) {
                    granted++;
                }
            }
            assertTrue(granted >= 15, "granted " + granted);
            LivingEntity body = bot.body().body();
            LivingEntity other = bots.get(1).body().body();
            other.teleport(body.getLocation().add(2, 0, 0));
            h.ticks(60);
            body.setNoDamageTicks(0);
            body.damage(3, DamageSource.builder(DamageType.PLAYER_ATTACK).withCausingEntity(admin)
                    .withDirectEntity(admin).build());
            other.setNoDamageTicks(0);
            other.damage(3, DamageSource.builder(DamageType.MOB_ATTACK).withCausingEntity(body)
                    .withDirectEntity(body).build());
            body.setNoDamageTicks(0);
            body.damage(1000, DamageSource.builder(DamageType.PLAYER_ATTACK).withCausingEntity(admin)
                    .withDirectEntity(admin).build());
            assertTrue(match.session().isAlive(bot.id()), "불사조의 깃털 saved the bot once");
            h.ticks(20);
            assertTrue(problems.isEmpty(), () -> problems.stream().map(r -> r.getMessage() + " "
                    + r.getThrown()).toList().toString());
        } finally {
            h.hub.plugin().getLogger().removeHandler(handler);
        }
    }

    @Test
    void botsDoNotSeeThroughWalls() {
        h.settings().set(AugmentSettings.BORDER_DELAY, 600);
        AugmentMatch match = botMatch("1 지옥");
        h.tickUntilPhase(MatchPhase.FIGHT);
        BotController bot = match.bots().all().getFirst();
        org.bukkit.Location at = bot.body().legs().getLocation();
        World world = at.getWorld();
        // a tall wall between the bot and a player 20 blocks away (too far to hear)
        for (int dz = -6; dz <= 6; dz++) {
            for (int dy = 0; dy <= 6; dy++) {
                world.getBlockAt(at.getBlockX() + 10, at.getBlockY() + dy, at.getBlockZ() + dz)
                        .setType(org.bukkit.Material.STONE);
            }
        }
        admin.teleport(at.clone().add(20, 0, 0));
        h.ticks(220); // longer than the bot remembers where it last saw someone
        BotBrain.Decision hidden = bot.decision();
        assertNotNull(hidden);
        assertFalse(admin.getUniqueId().equals(hidden.target()), "no target behind the wall: " + hidden);
        admin.teleport(at.clone().add(0, 0, 15));
        h.ticks(10);
        assertEquals(admin.getUniqueId(), bot.decision().target(), "seen in the open");
    }

    @Test
    void everyBotEntityIsRemovedWhenTheMatchStops() {
        AugmentMatch match = botMatch("3 보통");
        World world = match.wild().world();
        GameSession session = match.session();
        h.hub.games().stop(Component.text("test"));
        h.tickUntil(() -> session.state() == GameState.CLOSED, 400, "closed");
        assertTrue(world.getEntitiesByClass(Mannequin.class).isEmpty());
        assertTrue(world.getEntitiesByClass(WanderingTrader.class).isEmpty());
        for (World other : h.server.getWorlds()) {
            assertTrue(other.getEntitiesByClass(Mannequin.class).isEmpty(), "nothing left in " + other.getName());
        }
        assertNull(h.match());
    }

    /** Records the chunks the bots hold loaded (the mock server has no chunk tickets). */
    static final class RecordingTickets implements io.github.sx2xoox.mcpl.game.augment.bot.ChunkTickets {
        final java.util.Set<Long> held = new java.util.HashSet<>();
        int removedAll;

        @Override
        public void add(int chunkX, int chunkZ) {
            assertTrue(held.add(key(chunkX, chunkZ)), "never twice");
        }

        @Override
        public void remove(int chunkX, int chunkZ) {
            assertTrue(held.remove(key(chunkX, chunkZ)), "only what it holds");
        }

        @Override
        public void removeAll() {
            removedAll++;
            held.clear();
        }

        static long key(int x, int z) {
            return ((long) x << 32) | (z & 0xFFFFFFFFL);
        }
    }

    @Test
    void botsHoldTheChunksAroundThemLoadedAndLetGoAfterwards() {
        assertTrue(h.server.dispatchCommand(admin, "게임 증강 봇전 2 쉬움"));
        h.tickUntil(() -> h.match() != null, 400, "match");
        RecordingTickets tickets = new RecordingTickets();
        h.match().bots().useTickets(tickets);
        h.tickUntilPhase(MatchPhase.FARMING);
        AugmentMatch match = h.match();
        BotManager bots = match.bots();
        for (BotController bot : bots.all()) {
            org.bukkit.Location at = bot.body().legs().getLocation();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    assertTrue(tickets.held.contains(RecordingTickets.key((at.getBlockX() >> 4) + dx,
                            (at.getBlockZ() >> 4) + dz)), "3x3 around the legs from the spawn on");
                }
            }
        }
        assertEquals(tickets.held.size(), bots.heldChunks());
        BotController first = bots.all().getFirst();
        org.bukkit.Location far = first.body().legs().getLocation().add(36, 0, 0);
        int oldX = first.body().legs().getLocation().getBlockX() >> 4;
        int oldZ = first.body().legs().getLocation().getBlockZ() >> 4;
        first.body().legs().teleport(far);
        h.ticks(21);
        assertTrue(tickets.held.contains(RecordingTickets.key(far.getBlockX() >> 4, far.getBlockZ() >> 4)),
                "moves with the bot");
        BotController second = bots.all().get(1);
        org.bukkit.Location other = second.body().legs().getLocation();
        if (Math.abs((other.getBlockX() >> 4) - oldX) > 2 || Math.abs((other.getBlockZ() >> 4) - oldZ) > 2) {
            assertFalse(tickets.held.contains(RecordingTickets.key(oldX, oldZ)), "and lets go of the old area");
        }
        h.tickUntilPhase(MatchPhase.FIGHT);
        org.bukkit.Location dying = first.body().legs().getLocation();
        org.bukkit.Location living = second.body().legs().getLocation();
        kill(first.body().body(), admin);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int x = (dying.getBlockX() >> 4) + dx;
                int z = (dying.getBlockZ() >> 4) + dz;
                boolean shared = Math.abs(x - (living.getBlockX() >> 4)) <= 1
                        && Math.abs(z - (living.getBlockZ() >> 4)) <= 1;
                assertEquals(shared, tickets.held.contains(RecordingTickets.key(x, z)),
                        "a dead bot's chunks go at once (unless the other bot needs them)");
            }
        }
        h.hub.games().stop(Component.text("test"));
        assertTrue(tickets.held.isEmpty() && tickets.removedAll > 0, "every ticket goes at the end");
        assertEquals(0, bots.heldChunks());
    }

    @Test
    void botsSpawnOnDryGroundAndKeepBreathing() {
        AugmentMatch match = botMatch("1 쉬움");
        World world = match.wild().world();
        BotController bot = match.bots().all().getFirst();
        org.bukkit.Location at = bot.body().legs().getLocation();
        assertTrue(BotManager.dry(at.clone()), "spawned on dry ground");
        org.bukkit.Location pond = at.clone().add(30, 0, 30);
        world.getBlockAt(pond.getBlockX() + 1, pond.getBlockY() - 1, pond.getBlockZ())
                .setType(org.bukkit.Material.WATER);
        assertFalse(BotManager.dry(pond), "water next to it");
        assertTrue(BotManager.dry(pond.clone().add(10, 0, 0)), "dry a little further");
        Mannequin body = bot.body().body();
        body.setRemainingAir(30);
        h.ticks(2);
        assertEquals(body.getMaximumAir(), body.getRemainingAir(), "the body breathes on land");
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.getBlockAt(at.getBlockX() + 40 + dx, at.getBlockY() - 1, at.getBlockZ() + dz)
                        .setType(org.bukkit.Material.WATER);
            }
        }
        org.bukkit.Location shore = io.github.sx2xoox.mcpl.game.augment.bot.BotController.nearestDry(
                at.clone().add(40, 0, 0), 24);
        assertNotNull(shore, "a way out of the water");
        assertFalse(world.getBlockAt(shore.getBlockX(), shore.getBlockY() - 1, shore.getBlockZ()).isLiquid());
    }

    /** Checks placed base blocks in the world. */
    static final class BasePlannerCheck {
        static void assertPlaced(World world, io.github.sx2xoox.mcpl.game.augment.bot.BasePlanner.Plan plan, int built) {
            int solid = 0;
            for (int i = 0; i < built; i++) {
                var p = plan.placements().get(i);
                if (!world.getBlockAt(p.x(), p.y(), p.z()).getType().isAir()) {
                    solid++;
                }
            }
            assertTrue(solid > 0, "the blocks stand in the match world");
        }
    }
}
