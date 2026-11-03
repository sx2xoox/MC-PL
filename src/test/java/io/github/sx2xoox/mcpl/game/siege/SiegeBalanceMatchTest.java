package io.github.sx2xoox.mcpl.game.siege;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import io.github.sx2xoox.mcpl.core.game.GameStartException;
import io.github.sx2xoox.mcpl.game.siege.balance.SiegeBalanceSettings;
import io.github.sx2xoox.mcpl.game.siege.balance.SiegeModifiers;
import io.github.sx2xoox.mcpl.game.superpower.runtime.MatchContext;
import io.github.sx2xoox.mcpl.game.superpower.runtime.MatchHost;
import io.github.sx2xoox.mcpl.game.superpower.runtime.SuperpowerMatch;
import io.github.sx2xoox.mcpl.game.superpower.support.MatchHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The 공성전 ability balance in a match: the damage factor, level scaling, modifiers and perks; 초능력전쟁 untouched. */
class SiegeBalanceMatchTest {

    private MatchHarness h;
    private SiegeGame siege;
    private ConfigurationSection config;

    @BeforeEach
    void setUp() {
        h = MatchHarness.flat();
        h.registerDummy();
        h.registerTestAbilities();
        h.settings().set("ultimate.per-second", 0);
        siege = (SiegeGame) h.hub.games().game(SiegeGame.ID);
        siege.sitesOverride(new FallbackSiegeSites((int) FlatWorld.SURFACE));
        config = siege.config();
        config.set(SiegeSettings.ABILITY_POOL, List.of("dummy"));
        config.set(SiegeSettings.ROUND_PREP_SECONDS, 1);
        config.set(SiegeSettings.HALF_PREP_SECONDS, 1);
        config.set(SiegeSettings.SPAWN_ZONE_DAMAGE, 0);
        config.set(SiegeProgressSettings.XP_PASSIVE, 0);
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    private SiegeMatch start() {
        h.join(2);
        try {
            h.hub.games().start(siege, null);
        } catch (GameStartException e) {
            throw new AssertionError(e);
        }
        h.tickUntil(() -> siege.currentMatch() != null && siege.currentMatch().phase() == SiegeMatch.Phase.FIGHT,
                2000, "the fight");
        return siege.currentMatch();
    }

    private static Player member(SiegeMatch match, int team) {
        UUID id = match.teams().get(team).members().iterator().next();
        return match.session().hub().plugin().getServer().getPlayer(id);
    }

    /** Health {@code target} loses to {@code amount} of ability damage from {@code source}. */
    private static double lost(MatchContext ctx, Player source, Player target, double amount) {
        target.setHealth(target.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue());
        double before = target.getHealth();
        ctx.damage(source, target, amount);
        return before - target.getHealth();
    }

    @Test
    void abilityDamageIsLowerInSiegeAndGrowsWithLevels() {
        SiegeMatch match = start();
        Player attacker = member(match, 0);
        Player defender = member(match, 1);
        MatchContext ctx = match.abilities().context();
        double g = SiegeBalanceSettings.DEFAULT_ABILITY_DAMAGE;

        assertEquals(1, ctx.level(attacker), "level 1 at the start");
        assertEquals(4 * g, lost(ctx, attacker, defender, 4), 1.0E-6, "−35% at level 1");

        match.progress().grant(attacker.getUniqueId(), 1600);
        assertEquals(9, ctx.level(attacker));
        assertEquals(4 * g * 1.6, lost(ctx, attacker, defender, 4), 1.0E-6, "+7.5% per level: ×1.6 at level 9");

        match.modifiers().addPercent(attacker.getUniqueId(), "test:orb", SiegeModifiers.Stat.SKILL_DAMAGE, 10);
        match.modifiers().addPercent(defender.getUniqueId(), "test:armor", SiegeModifiers.Stat.SKILL_DAMAGE_TAKEN,
                -12);
        assertEquals(4 * g * 1.6 * 1.1 * 0.88, lost(ctx, attacker, defender, 4), 1.0E-6, "items stack on top");
    }

    @Test
    void perksAndCooldownModifiersReachTheHostedAbilities() {
        SiegeMatch match = start();
        Player attacker = member(match, 0);
        MatchHost host = match.abilities().host();
        assertNotNull(host);
        assertEquals(6, host.abilityParam(attacker, "pyro", "ult_radius", 6), 1.0E-9, "no perk at level 1");
        match.progress().grant(attacker.getUniqueId(), 60 + 100 + 140 + 180 + 220);
        assertEquals(6, match.level(attacker.getUniqueId()));
        assertEquals(7, host.abilityParam(attacker, "pyro", "ult_radius", 6), 1.0E-9, "pyro level 6: vortex +1");
        assertEquals(1.9, host.abilityParam(attacker, "pyro", "fireball_radius", 1.5), 1.0E-9, "level 3 perk kept");
        assertEquals(3, host.abilityParam(attacker, "pyro", "shots", 3), 1.0E-9, "the level 9 perk not yet");

        match.modifiers().addPercent(attacker.getUniqueId(), "test:hourglass", SiegeModifiers.Stat.COOLDOWN, -10);
        assertTrue(match.abilities().useActive(attacker).succeeded());
        double cooldown = match.abilities().context().cooldownRemaining(attacker);
        assertEquals(27, cooldown, 0.1, "dummy 30 s cooldown −10%");
    }

    @Test
    void superpowerWarIsUnchanged() {
        List<TestPlayer> players = h.join(2);
        SuperpowerMatch match = h.startFight();
        for (TestPlayer player : players) {
            match.replaceAbility(player, "dummy");
        }
        h.healAll(players);
        MatchContext ctx = match.context();
        assertEquals(0, ctx.level(players.get(0)), "no levels outside 공성전");
        assertEquals(4, lost(ctx, players.get(0), players.get(1), 4), 1.0E-9, "abilities.yml numbers as they are");
    }
}
