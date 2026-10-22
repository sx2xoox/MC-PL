package io.github.sx2xoox.mcpl.game.superpower;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import io.github.sx2xoox.mcpl.core.game.MatchTeam;
import io.github.sx2xoox.mcpl.game.superpower.runtime.MatchContext;
import io.github.sx2xoox.mcpl.game.superpower.runtime.SuperpowerMatch;
import io.github.sx2xoox.mcpl.game.superpower.support.MatchHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wolf;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TargetingTest {

    private MatchHarness h;

    @BeforeEach
    void setUp() {
        h = new MatchHarness();
        h.registerTestAbilities();
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    private Wolf summonWolf(MatchContext ctx, Player owner) {
        return ctx.summon(owner, owner.getWorld().spawn(owner.getLocation(), Wolf.class));
    }

    @Test
    void aimingNeverLocksOntoAStealthedPlayer() {
        h.close();
        h = MatchHarness.flat(); // findTarget ray-traces blocks, which only the flat world supports
        h.registerTestAbilities();
        List<TestPlayer> players = h.join(2);
        SuperpowerMatch match = h.startFight();
        MatchContext ctx = match.context();
        TestPlayer a = players.get(0);
        TestPlayer b = players.get(1);
        Location center = match.session().arena().center();
        a.teleport(center);
        b.teleport(center.clone().add(-4, 0, 0));
        a.setRotation(90, 0); // facing -x, straight at b
        assertEquals(b, ctx.findTarget(a, 20), "a visible enemy straight ahead is targeted");
        a.hidePlayer(h.hub.plugin(), b);
        assertNull(ctx.findTarget(a, 20), "a stealthed enemy is never aimed at (it would reveal them)");
        a.showPlayer(h.hub.plugin(), b);
        assertEquals(b, ctx.findTarget(a, 20));
    }

    @Test
    void freeForAllEveryoneElseIsAnEnemy() {
        List<TestPlayer> players = h.join(3);
        SuperpowerMatch match = h.startFight();
        for (TestPlayer player : players) {
            assertTrue(match.replaceAbility(player, "probe"));
        }
        h.healAll(players);
        MatchContext ctx = match.context();
        TestPlayer a = players.get(0);
        TestPlayer b = players.get(1);
        TestPlayer c = players.get(2);
        Location center = match.session().arena().center();
        a.teleport(center);
        b.teleport(center.clone().add(3, 0, 0));
        c.teleport(center.clone().add(30, 0, 0));

        assertTrue(ctx.isEnemy(a, b));
        assertFalse(ctx.isEnemy(a, a), "never the owner");
        assertFalse(ctx.isAlly(a, b), "no allies in free-for-all");
        assertFalse(ctx.isAlly(a, a));

        Wolf wolfOfA = summonWolf(ctx, a);
        assertTrue(ctx.isAlly(a, wolfOfA), "own summons are allies");
        assertFalse(ctx.isEnemy(a, wolfOfA));
        assertTrue(ctx.isEnemy(b, wolfOfA), "summons are enemies of the owner's enemies");
        assertFalse(ctx.isAlly(b, wolfOfA));
        assertEquals(a, ctx.resolveAttacker(wolfOfA), "summon damage is credited to the owner");

        Zombie bystander = a.getWorld().spawn(center, Zombie.class);
        assertFalse(ctx.isEnemy(a, bystander), "unrelated mobs are never enemies");
        assertFalse(ctx.isAlly(a, bystander));

        List<?> near = ctx.enemiesNear(a, a.getLocation(), 5);
        assertEquals(List.of(b), near, "nearest enemies by bounding box; not the owner's wolf, not far players");
        assertTrue(ctx.enemiesNear(b, b.getLocation(), 5).contains(wolfOfA));
        assertTrue(ctx.alliesNear(a, a.getLocation(), 50).isEmpty());

        // Summons never hurt or target their owner; the owner cannot hurt them.
        double health = a.getHealth();
        a.simulateDamage(3, wolfOfA);
        assertEquals(health, a.getHealth(), 1.0E-9, "a summon cannot hurt its owner");
        double wolfHealth = wolfOfA.getHealth();
        ((org.mockbukkit.mockbukkit.entity.LivingEntityMock) wolfOfA).simulateDamage(3, a);
        assertEquals(wolfHealth, wolfOfA.getHealth(), 1.0E-9, "the owner cannot hurt their summon");
        b.simulateDamage(3, wolfOfA);
        assertEquals(17.0, b.getHealth(), 1.0E-9, "summons hurt enemies");
        EntityTargetLivingEntityEvent targetOwner = new EntityTargetLivingEntityEvent(wolfOfA, a,
                EntityTargetEvent.TargetReason.OWNER_ATTACKED_TARGET);
        h.server.getPluginManager().callEvent(targetOwner);
        assertTrue(targetOwner.isCancelled(), "summons never target their owner");
        EntityTargetLivingEntityEvent targetEnemy = new EntityTargetLivingEntityEvent(wolfOfA, b,
                EntityTargetEvent.TargetReason.CLOSEST_PLAYER);
        h.server.getPluginManager().callEvent(targetEnemy);
        assertFalse(targetEnemy.isCancelled());

        a.setRotation(90, 0); // facing -x, away from everyone
        assertNull(ctx.findTarget(a, 20), "nothing in the line of sight");

        match.session().eliminate(c, null);
        assertFalse(ctx.isEnemy(a, c), "eliminated players are no targets");
        match.session().eliminate(a, null);
        assertFalse(wolfOfA.isValid(), "summons leave with their owner");
    }

    @Test
    void teamModeProtectsTeammatesAndTheirSummons() {
        h.hub.config().setTeamSize(2);
        List<TestPlayer> players = h.join(4);
        SuperpowerMatch match = h.startFight();
        for (TestPlayer player : players) {
            assertTrue(match.replaceAbility(player, "probe"));
        }
        h.healAll(players);
        MatchContext ctx = match.context();
        TestPlayer a = players.getFirst();
        MatchTeam team = match.session().teamOf(a);
        Player mate = null;
        Player opponent = null;
        for (TestPlayer player : players) {
            if (player == a) {
                continue;
            }
            if (team.contains(player)) {
                mate = player;
            } else {
                opponent = player;
            }
        }
        Location center = match.session().arena().center();
        a.teleport(center);
        mate.teleport(center.clone().add(2, 0, 0));
        opponent.teleport(center.clone().add(0, 0, 2));

        assertTrue(ctx.isAlly(a, mate));
        assertFalse(ctx.isEnemy(a, mate));
        assertTrue(ctx.isEnemy(a, opponent));
        assertFalse(ctx.isAlly(a, opponent));
        assertEquals(List.of(mate), ctx.alliesNear(a, a.getLocation(), 10));

        Wolf mateWolf = summonWolf(ctx, mate);
        Wolf opponentWolf = summonWolf(ctx, opponent);
        assertTrue(ctx.isAlly(a, mateWolf), "a teammate's summon is an ally");
        assertFalse(ctx.isEnemy(a, mateWolf));
        assertTrue(ctx.isEnemy(a, opponentWolf));

        double health = mate.getHealth();
        ctx.damage(a, mate, 5);
        assertEquals(health, mate.getHealth(), 1.0E-9, "ability damage never hits teammates");
        a.simulateDamage(2, mateWolf);
        assertEquals(20.0, a.getHealth(), 1.0E-9, "a teammate's summon cannot hurt you");
        ctx.damage(a, opponent, 5);
        assertEquals(15.0, opponent.getHealth(), 1.0E-9);
    }
}
