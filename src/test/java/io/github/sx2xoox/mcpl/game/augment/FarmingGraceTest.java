package io.github.sx2xoox.mcpl.game.augment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import io.github.sx2xoox.mcpl.game.augment.bonus.BonusCondition;
import io.github.sx2xoox.mcpl.game.augment.runtime.AugmentMatch;
import io.github.sx2xoox.mcpl.game.augment.runtime.MatchPhase;
import io.github.sx2xoox.mcpl.game.augment.support.AugmentHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The farming grace: no damage for players and bots, no hunger, until the fight starts (not in practice). */
class FarmingGraceTest {

    private AugmentHarness h;

    @BeforeEach
    void setUp() {
        h = new AugmentHarness();
        h.noRounds();
        h.settings().set(AugmentSettings.FARMING_INVULNERABLE, true);
        h.settings().set(AugmentSettings.FARMING_NO_HUNGER, true);
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    private static void hurt(LivingEntity victim, DamageType type, double amount) {
        victim.setNoDamageTicks(0);
        victim.damage(amount, DamageSource.builder(type).build());
    }

    private static boolean starves(TestPlayer player, int food) {
        FoodLevelChangeEvent event = new FoodLevelChangeEvent(player, food, null);
        player.getServer().getPluginManager().callEvent(event);
        return !event.isCancelled();
    }

    @Test
    void playersAreInvulnerableAndFedUntilTheFight() {
        List<TestPlayer> players = h.join(2);
        TestPlayer a = players.getFirst();
        players.get(1).setOp(true);
        AugmentMatch match = h.startFarming();
        assertTrue(match.farmingInvulnerable());
        for (DamageType type : List.of(DamageType.FALL, DamageType.LAVA, DamageType.DROWN, DamageType.IN_FIRE)) {
            hurt(a, type, 6);
        }
        assertEquals(20, a.getHealth(), 1.0E-6, "no damage from any source while farming");

        assertFalse(starves(a, 15), "the food level never drops");
        assertTrue(starves(a, 20), "eating still works");
        a.setFoodLevel(12);
        a.setSaturation(0f);
        h.ticks(20);
        assertEquals(20, a.getFoodLevel(), "topped up");
        assertEquals(20f, a.getSaturation(), 1.0E-6f);

        assertTrue(h.server.dispatchCommand(players.get(1), "게임 증강 다음단계"), "the admin skips to the fight");
        assertEquals(MatchPhase.FIGHT, match.phase());
        assertFalse(match.farmingInvulnerable());
        hurt(a, DamageType.FALL, 6);
        assertEquals(14, a.getHealth(), 1.0E-6, "the fight hurts again");
        assertTrue(starves(a, 15), "and hunger is back");
    }

    @Test
    void theGraceEndsWhenTheFarmingTimeRunsOut() {
        TestPlayer a = h.join(2).getFirst();
        AugmentMatch match = h.startFarming();
        hurt(a, DamageType.FALL, 4);
        assertEquals(20, a.getHealth(), 1.0E-6);
        h.tickUntilPhase(MatchPhase.FIGHT);
        hurt(a, DamageType.FALL, 4);
        assertEquals(16, a.getHealth(), 1.0E-6);
        assertFalse(match.farmingNoHunger());
    }

    @Test
    void theTogglesTurnTheGraceOff() {
        h.settings().set(AugmentSettings.FARMING_INVULNERABLE, false);
        h.settings().set(AugmentSettings.FARMING_NO_HUNGER, false);
        TestPlayer a = h.join(2).getFirst();
        AugmentMatch match = h.startFarming();
        hurt(a, DamageType.FALL, 4);
        assertEquals(16, a.getHealth(), 1.0E-6, "falls hurt while farming");
        assertTrue(starves(a, 15));
        assertFalse(match.farmingInvulnerable());
    }

    @Test
    void aBarelySurvivedFallOnlyCountsInTheFight() {
        h.settings().set(AugmentSettings.FARMING_INVULNERABLE, false);
        TestPlayer a = h.join(2).getFirst();
        AugmentMatch match = h.startFarming();
        a.setFallDistance(30);
        a.setHealth(7);
        hurt(a, DamageType.FALL, 4);
        assertEquals(3, a.getHealth(), 1.0E-6);
        assertFalse(match.bonuses(a.getUniqueId()).contains(BonusCondition.MIRACLE_LANDING), "not while farming");
    }

    @Test
    void botsAreInvulnerableWhileFarmingToo() {
        TestPlayer admin = opped(h);
        assertTrue(h.server.dispatchCommand(admin, "게임 증강 봇전 1 쉬움"));
        h.tickUntil(() -> h.match() != null && h.match().phase() == MatchPhase.FARMING, 3000, "farming");
        AugmentMatch match = h.match();
        LivingEntity body = match.bots().all().getFirst().body().body();
        double health = body.getHealth();
        hurt(body, DamageType.FALL, 5);
        hurt(body, DamageType.LAVA, 5);
        assertEquals(health, body.getHealth(), 1.0E-6, "bots are as safe as players");
        match.advancePhase();
        hurt(body, DamageType.FALL, 5);
        assertEquals(health - 5, body.getHealth(), 1.0E-6, "until the fight");
    }

    @Test
    void practiceKeepsItsOwnRules() {
        TestPlayer admin = opped(h);
        assertTrue(h.server.dispatchCommand(admin, "게임 증강 연습"));
        h.tickUntil(() -> h.match() != null && h.match().phase() == MatchPhase.FARMING, 3000, "practice farming");
        assertFalse(h.match().farmingInvulnerable());
        hurt(admin, DamageType.FALL, 4);
        assertEquals(16, admin.getHealth(), 1.0E-6, "practice falls hurt");
        assertTrue(starves(admin, 15));
    }

    /** The first opped player (joins one if needed). */
    private static TestPlayer opped(AugmentHarness h) {
        for (var player : h.server.getOnlinePlayers()) {
            if (player.isOp() && player instanceof TestPlayer test) {
                return test;
            }
        }
        TestPlayer admin = h.join("Admin");
        admin.setOp(true);
        return admin;
    }
}
