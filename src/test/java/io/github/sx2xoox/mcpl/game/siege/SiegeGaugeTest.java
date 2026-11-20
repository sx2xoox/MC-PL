package io.github.sx2xoox.mcpl.game.siege;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import io.github.sx2xoox.mcpl.core.game.GameStartException;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import io.github.sx2xoox.mcpl.game.siege.bot.SiegeBotDifficulty;
import io.github.sx2xoox.mcpl.game.siege.bot.SiegeBots;
import io.github.sx2xoox.mcpl.game.siege.minion.MinionWaves;
import io.github.sx2xoox.mcpl.game.siege.tower.Tower;
import io.github.sx2xoox.mcpl.game.superpower.SuperpowerGame;
import io.github.sx2xoox.mcpl.game.superpower.SuperpowerSettings;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttack;
import io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttackSettings;
import io.github.sx2xoox.mcpl.game.superpower.runtime.MatchPhase;
import io.github.sx2xoox.mcpl.game.superpower.runtime.SuperpowerMatch;
import io.github.sx2xoox.mcpl.game.superpower.support.MatchHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import io.papermc.paper.event.player.PlayerArmSwingEvent;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The ultimate gauge in 공성전: basic attacks (평타) and skills charge it on everything they fight, weighed by
 * {@code games.siege.gauge.*} (enemy players and bots 1, minions and monsters 0.5, structures 0.3), by the health the
 * target really lost; hits of the attacker's own ultimate never do. 초능력전쟁 still charges on players only.
 */
class SiegeGaugeTest {

    private static final String ID = "gauge_nuke";
    private static final double ULTIMATE_DAMAGE = 3;
    private static final String PREFIX = SiegeMatch.BASIC_ATTACKS + "." + BasicAttackSettings.ATTACKS + "." + ID + ".";

    /** Neutral but for an ultimate that hits every enemy within 6 blocks through the ability context. */
    static final class NukeAbility extends Ability {
        static final AbilityInfo INFO = AbilityInfo.builder(ID, "게이지 시험", Material.TNT)
                .color(TextColor.color(0xFF5555))
                .passive("없음", "아무 일도 하지 않습니다.")
                .active("없음", "아무 일도 하지 않습니다.", 30)
                .ultimate("폭발", "주변 적에게 피해를 줍니다.")
                .ultimateNotStandalone()
                .build();

        @Override
        public AbilityInfo info() {
            return INFO;
        }

        @Override
        public ActivationResult onActive() {
            return ActivationResult.success();
        }

        @Override
        public ActivationResult onUltimate() {
            for (LivingEntity enemy : ctx().enemiesNear(player(), player().getLocation(), 6)) {
                ctx().damage(player(), enemy, ULTIMATE_DAMAGE);
            }
            return ActivationResult.success();
        }
    }

    private MatchHarness h;
    private SiegeGame siege;
    private ConfigurationSection config;

    @BeforeEach
    void setUp() {
        h = MatchHarness.flat();
        SuperpowerGame superpower = (SuperpowerGame) h.hub.games().game(SuperpowerGame.ID);
        superpower.registry().register(NukeAbility.INFO, NukeAbility::new);
        superpower.abilities().load(superpower.registry());
        h.settings().set(SuperpowerSettings.GAUGE_SECOND, 0);
        h.settings().set(SuperpowerSettings.GAUGE_TAKEN, 0);
        h.settings().set(SuperpowerSettings.GAUGE_DEALT, 1.0);
        // no post-ultimate lock: an ultimate hit must earn nothing on its own account
        h.settings().set(SuperpowerSettings.ULTIMATE_LOCK, 0);
        siege = (SiegeGame) h.hub.games().game(SiegeGame.ID);
        siege.sitesOverride(new FallbackLaneSites((int) FlatWorld.SURFACE));
        config = siege.config();
        config.set(SiegeSettings.ABILITY_POOL, List.of(ID));
        config.set(SiegeSettings.ROUND_PREP_SECONDS, 1);
        config.set(SiegeSettings.ATTACK_DAMAGE, 0);
        config.set(SiegeSettings.SPAWN_ZONE_DAMAGE, 0);
        config.set(SiegeSettings.PROTECTION_SECONDS, 0);
        config.set(SiegeProgressSettings.XP_PASSIVE, 0);
        config.set("minions.first-wave-seconds", 120);
        config.set(SiegeMatch.BASIC_ATTACKS + "." + BasicAttackSettings.CRITICAL + "."
                + BasicAttackSettings.CRIT_CHANCE, 0);
        // a burst around the attacker: hits whatever stands next to it, no aim needed
        config.set(PREFIX + BasicAttackSettings.SHAPE, BasicAttack.Shape.NOVA.key());
        config.set(PREFIX + BasicAttackSettings.RANGE, 4);
        config.set(PREFIX + BasicAttackSettings.DAMAGE, 4);
        config.set(PREFIX + BasicAttackSettings.HITS, 1);
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    private SiegeMatch start(int players) {
        h.join(players);
        return begin();
    }

    private SiegeMatch begin() {
        try {
            h.hub.games().start(siege, null);
        } catch (GameStartException e) {
            throw new AssertionError(e);
        }
        h.tickUntil(() -> siege.currentMatch() != null && siege.currentMatch().phase() == SiegeMatch.Phase.FIGHT,
                2000, "the fight");
        return siege.currentMatch();
    }

    private static Player player(SiegeMatch match, int team) {
        UUID id = match.teams().get(team).members().iterator().next();
        return match.session().hub().plugin().getServer().getPlayer(id);
    }

    private static double gauge(SiegeMatch match, Player player) {
        return match.abilities().participant(player.getUniqueId()).gauge();
    }

    /** Puts {@code player} {@code dx} blocks east of the test spot, looking east, at full health. */
    private static Location place(SiegeMatch match, Player player, double dx) {
        Location center = match.session().arena().center();
        Location at = new Location(center.getWorld(), center.getX() + dx, FlatWorld.SURFACE, center.getZ() + 40,
                -90f, 0f);
        player.teleport(at);
        player.setHealth(player.getAttribute(Attribute.MAX_HEALTH).getValue());
        player.getInventory().setHeldItemSlot(0);
        return at;
    }

    private void swing(Player player) {
        h.server.getPluginManager().callEvent(new PlayerArmSwingEvent(player, EquipmentSlot.HAND));
    }

    private static double health(LivingEntity entity) {
        return entity.isDead() ? 0 : entity.getHealth() + entity.getAbsorptionAmount();
    }

    // =================================================================== players

    @Test
    void aBasicAttackOnAnEnemyPlayerChargesTheFullRate() {
        SiegeMatch match = start(2);
        Player attacker = player(match, 0);
        Player enemy = player(match, 1);
        place(match, attacker, 0);
        place(match, enemy, 1.5);
        double before = health(enemy);
        swing(attacker);
        h.ticks(5);
        double lost = before - health(enemy);
        assertTrue(lost > 0, "the basic attack lands");
        assertEquals(lost, gauge(match, attacker), 1.0E-6, "a player hit: lost health × 1");
        assertEquals(0, gauge(match, enemy), 1.0E-9, "per-damage-taken is 0 here");
    }

    @Test
    void theUltimatesOwnHitsChargeNothing() {
        SiegeMatch match = start(2);
        Player attacker = player(match, 0);
        Player enemy = player(match, 1);
        match.abilities().setGauge(attacker, 100);
        // a structure of the enemy side next to the attacker, too
        Tower tower = enemyOuterTower(match, attacker);
        attacker.teleport(tower.base().clone().add(3.5, 0, 0));
        enemy.teleport(tower.base().clone().add(4.5, 0, 0));
        double towerBefore = tower.health();
        double enemyBefore = health(enemy);
        match.abilities().useUltimate(attacker);
        h.ticks(2);
        assertTrue(health(enemy) < enemyBefore, "the ultimate hurts the enemy");
        assertTrue(tower.health() < towerBefore, "and the structure");
        assertEquals(0, gauge(match, attacker), 1.0E-9, "its own ultimate never refills the gauge");
    }

    // =================================================================== structures, minions, bots

    private static Tower enemyOuterTower(SiegeMatch match, Player attacker) {
        LaneSites.Side side = match.side(attacker.getUniqueId());
        for (Tower tower : match.towers()) {
            if (tower.owner() != side && tower.kind() == Tower.Kind.OUTER && !tower.isShielded()) {
                return tower;
            }
        }
        throw new AssertionError("no enemy outer tower");
    }

    @Test
    void aBasicAttackOnAnEnemyStructureChargesAtItsFactor() {
        SiegeMatch match = start(2);
        Player attacker = player(match, 0);
        place(match, player(match, 1), 0);
        Tower tower = enemyOuterTower(match, attacker);
        Location spot = tower.base().clone().add(3.5, 0, 0);
        spot.setYaw(90f);
        attacker.teleport(spot);
        attacker.getInventory().setHeldItemSlot(0);
        double before = tower.health();
        swing(attacker);
        h.ticks(5);
        double lost = before - tower.health();
        assertTrue(lost > 0, "the basic attack hurts the structure");
        assertEquals(lost * 0.3, gauge(match, attacker), 1.0E-6, "a structure hit: lost health × 0.3");
    }

    @Test
    void aBasicAttackOnAnEnemyMinionChargesAtItsFactor() {
        config.set("minions.first-wave-seconds", 1);
        SiegeMatch match = start(2);
        Player attacker = player(match, 0);
        place(match, player(match, 1), 30);
        LaneSites.Side side = match.side(attacker.getUniqueId());
        MinionWaves minions = match.systems().minions();
        h.tickUntil(() -> minions.minions().stream().anyMatch(m -> m.side() != side), 600, "an enemy minion");
        MinionWaves.Minion minion = minions.minions().stream().filter(m -> m.side() != side).findFirst().orElseThrow();
        Location at = place(match, attacker, 0);
        minion.body().teleport(at.clone().add(1.5, 0, 0));
        double before = health(minion.body());
        swing(attacker);
        h.ticks(1);
        double lost = before - health(minion.body());
        assertTrue(lost > 0, "the basic attack hurts the minion");
        assertEquals(lost * 0.5, gauge(match, attacker), 1.0E-6, "a minion hit: lost health × 0.5");
    }

    private SiegeMatch startWithBots() {
        config.set("bots.jobs", List.of("knight"));
        h.join(1);
        siege.requestBots(new SiegeBots.Setup(siege.botSettings(), 1, SiegeBotDifficulty.NORMAL));
        return begin();
    }

    /** The body of a bot on the other side than {@code player}. */
    private static Mannequin enemyBot(SiegeMatch match, Player player) {
        LaneSites.Side side = match.side(player.getUniqueId());
        for (SiegeModule module : match.modules()) {
            if (module instanceof SiegeBots bots) {
                UUID id = bots.ids().stream().filter(bot -> match.side(bot) != side).findFirst().orElseThrow();
                Mannequin body = bots.body(id);
                assertNotNull(body);
                return body;
            }
        }
        throw new AssertionError("no bots module");
    }

    @Test
    void aBasicAttackOnAnEnemyBotChargesLikeOnAPlayer() {
        SiegeMatch match = startWithBots();
        Player attacker = match.session().participants().iterator().next();
        Mannequin body = enemyBot(match, attacker);
        Location at = place(match, attacker, 0);
        body.teleport(at.clone().add(1.5, 0, 0));
        double before = health(body);
        swing(attacker);
        h.ticks(1);
        double lost = before - health(body);
        assertTrue(lost > 0, "the basic attack hurts the bot");
        assertEquals(lost, gauge(match, attacker), 1.0E-6, "a bot hit: lost health × 1, like a player");
    }

    @Test
    void aBotsHitChargesThePlayerItHurts() {
        h.settings().set(SuperpowerSettings.GAUGE_TAKEN, 1.0);
        SiegeMatch match = startWithBots();
        Player player = match.session().participants().iterator().next();
        Mannequin bot = enemyBot(match, player);
        place(match, player, 0);
        match.abilities().setGauge(player, 0);
        double before = health(player);
        player.damage(2, DamageSource.builder(DamageType.MOB_ATTACK).withCausingEntity(bot).withDirectEntity(bot)
                .build());
        double lost = before - health(player);
        assertTrue(lost > 0, "the bot's hit lands");
        assertEquals(lost, gauge(match, player), 1.0E-6, "damage taken from a bot: lost health × per-damage-taken");
    }

    // =================================================================== 초능력전쟁

    @Test
    void theClassicMatchStillChargesOnPlayersOnly() {
        List<? extends Player> players = h.join(2);
        SuperpowerMatch match = h.startFight();
        Player attacker = players.get(0);
        Player enemy = players.get(1);
        // neutral abilities: no passive dodges or hurts anything
        assertTrue(match.replaceAbility(attacker, ID));
        assertTrue(match.replaceAbility(enemy, ID));
        h.healAll(players);
        match.setGauge(attacker, 0);
        Zombie zombie = attacker.getWorld().spawn(attacker.getLocation().add(2, 0, 0), Zombie.class);
        zombie.damage(5, DamageSource.builder(DamageType.PLAYER_ATTACK).withCausingEntity(attacker)
                .withDirectEntity(attacker).build());
        assertTrue(zombie.getHealth() < zombie.getAttribute(Attribute.MAX_HEALTH).getValue(), "the zombie is hurt");
        assertEquals(0, match.participant(attacker.getUniqueId()).gauge(), 1.0E-9, "no gauge for a mob");
        double before = health(enemy);
        enemy.damage(5, DamageSource.builder(DamageType.PLAYER_ATTACK).withCausingEntity(attacker)
                .withDirectEntity(attacker).build());
        double lost = before - health(enemy);
        assertTrue(lost > 0);
        assertEquals(lost, match.participant(attacker.getUniqueId()).gauge(), 1.0E-6, "an enemy player: × 1");
        assertEquals(MatchPhase.FIGHT, match.phase());
    }
}
