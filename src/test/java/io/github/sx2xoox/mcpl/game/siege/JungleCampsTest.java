package io.github.sx2xoox.mcpl.game.siege;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.github.sx2xoox.mcpl.core.game.GameStartException;
import io.github.sx2xoox.mcpl.game.superpower.runtime.MatchContext;
import io.github.sx2xoox.mcpl.game.superpower.runtime.MatchHost;
import io.github.sx2xoox.mcpl.game.superpower.support.MatchHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.PiglinBrute;
import org.bukkit.entity.Player;
import org.bukkit.entity.Ravager;
import org.bukkit.entity.Silverfish;
import org.bukkit.entity.Vex;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 공성전 monster camps (docs/siege.md §13) on the fallback layout. */
class JungleCampsTest {

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
        config.set(SiegeSettings.HALFTIME_SECONDS, 1);
        config.set(SiegeSettings.SPAWN_ZONE_DAMAGE, 0);
        config.set(SiegeProgressSettings.XP_PASSIVE, 0);
        // Every camp up a second into the fight.
        for (CampKind.Tier tier : CampKind.Tier.values()) {
            config.set(CampSettings.tierPath(tier, "first-seconds"), 1);
        }
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    private SiegeMatch start(int players) {
        h.join(players);
        try {
            h.hub.games().start(siege, null);
        } catch (GameStartException e) {
            throw new AssertionError(e);
        }
        h.tickUntil(() -> siege.currentMatch() != null && siege.currentMatch().phase() == SiegeMatch.Phase.FIGHT,
                2000, "the fight to start");
        SiegeMatch match = siege.currentMatch();
        h.tickUntil(() -> match.camps().camps().stream().allMatch(JungleCamps.Camp::isAlive), 60, "the camps");
        return match;
    }

    private static List<Player> members(SiegeMatch match, int team) {
        List<Player> out = new ArrayList<>();
        for (UUID id : match.teams().get(team).members()) {
            out.add(match.session().hub().plugin().getServer().getPlayer(id));
        }
        return out;
    }

    private static JungleCamps.Camp camp(SiegeMatch match, String id) {
        JungleCamps.Camp camp = match.camps().camp(id);
        assertNotNull(camp, "no camp " + id);
        return camp;
    }

    private static MatchContext ctx(SiegeMatch match) {
        return match.abilities().context();
    }

    /** Kills every monster of the camp with ability damage from {@code killer}. */
    private void clear(SiegeMatch match, JungleCamps.Camp camp, Player killer) {
        for (Mob mob : camp.mobs()) {
            mob.setNoDamageTicks(0);
            ctx(match).damage(killer, mob, 100_000);
        }
        assertFalse(camp.isAlive(), camp.site().id() + " cleared");
    }

    private long count(Class<? extends LivingEntity> type) {
        return h.flatWorld().getEntities().stream().filter(type::isInstance).filter(e -> !e.isDead()).count();
    }

    private static DamageSource hit(Mob by) {
        return DamageSource.builder(DamageType.MOB_ATTACK).withCausingEntity(by).withDirectEntity(by).build();
    }

    private static String plain(net.kyori.adventure.text.Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    // ------------------------------------------------------------------ tests

    @Test
    void campsSpawnWithTheirLooksAndLeaveNothingBehind() {
        config.set(CampSettings.HEALTH_PER_EXTRA_PLAYER, 0.5);
        SiegeMatch match = start(4);
        assertEquals(6, match.camps().camps().size());
        assertEquals(3, count(Vex.class), "drones");
        assertEquals(3, count(Silverfish.class), "rats");
        assertEquals(2, count(IronGolem.class), "scrap golems");
        assertEquals(1, count(PiglinBrute.class), "레드 서버");
        assertEquals(1, count(WitherSkeleton.class), "블루 서버");
        assertEquals(1, count(Ravager.class), "메인프레임 수호자");

        CampSettings settings = CampSettings.read(config);
        Mob guardian = camp(match, "guardian").mobs().getFirst();
        assertEquals(400 * 1.5, guardian.getHealth(), 1.0E-6, "2 a side: +50 % health");
        assertEquals("메인프레임 수호자", plain(guardian.customName()));
        AttributeInstance scale = guardian.getAttribute(Attribute.SCALE);
        if (scale != null) {
            assertEquals(settings.mob(CampKind.GUARDIAN).scale(), scale.getBaseValue(), 1.0E-9, "the epic is big");
        }
        assertTrue(settings.mob(CampKind.DRONES).scale() < settings.mob(CampKind.RED).scale()
                && settings.mob(CampKind.RED).scale() < settings.mob(CampKind.GUARDIAN).scale(), "size by tier");
        assertNotNull(camp(match, "red").label(), "a label over each camp");
        assertFalse(guardian.isAware(), "monsters stand still until hit");

        assertTrue(h.server.dispatchCommand(h.server.getConsoleSender(), "게임 중지"));
        h.tickUntil(() -> h.hub.games().session() == null, 2000, "the session to close");
        for (Class<? extends LivingEntity> type : List.of(Vex.class, Silverfish.class, IronGolem.class,
                PiglinBrute.class, WitherSkeleton.class, Ravager.class)) {
            assertEquals(0, count(type), type.getSimpleName() + " removed");
        }
    }

    @Test
    void abilitiesOfBothTeamsHitTheMonsters() {
        SiegeMatch match = start(2);
        Player attacker = members(match, 0).getFirst();
        Player defender = members(match, 1).getFirst();
        Mob rat = camp(match, "rats").mobs().getFirst();
        MatchHost host = match.abilities().host();
        assertNotNull(host);
        assertTrue(host.extraTargets().contains(rat));
        assertTrue(ctx(match).isEnemy(attacker, rat), "an enemy of the attackers");
        assertTrue(ctx(match).isEnemy(defender, rat), "and of the defenders");
        assertTrue(ctx(match).enemiesNear(defender, rat.getLocation(), 2).contains(rat));
        double before = rat.getHealth();
        rat.setNoDamageTicks(0);
        ctx(match).damage(defender, rat, 3);
        assertEquals(before - 3, rat.getHealth(), 1.0E-6, "ability damage lands");
        assertTrue(camp(match, "rats").isFighting(), "the camp fights back");
        assertTrue(rat.isAware());
    }

    @Test
    void aPulledCampGoesHomeWithFullHealth() {
        config.set(CampSettings.RESET, 2);
        SiegeMatch match = start(2);
        Player attacker = members(match, 0).getFirst();
        JungleCamps.Camp red = camp(match, "red");
        Mob brute = red.mobs().getFirst();
        double full = brute.getHealth();
        brute.setNoDamageTicks(0);
        ctx(match).damage(attacker, brute, 10);
        assertTrue(red.isFighting());
        brute.teleport(red.at().add(40, 0, 0));
        h.ticks(25);
        assertFalse(red.isFighting(), "pulled beyond the leash: reset");
        assertTrue(brute.getLocation().distance(red.at()) < 3, "back home");
        assertEquals(full, brute.getHealth(), 1.0E-6, "full health again");

        brute.setNoDamageTicks(0);
        ctx(match).damage(attacker, brute, 10);
        assertTrue(red.isFighting());
        h.tickUntil(() -> !red.isFighting(), 80, "a fight nobody keeps up resets");
        assertEquals(full, brute.getHealth(), 1.0E-6);
    }

    @Test
    void theKillingTeamSplitsTheExperienceAndTheLastHitGetsABonus() {
        config.set(CampSettings.tierPath(CampKind.Tier.SMALL, "respawn-seconds"), 5);
        SiegeMatch match = start(4);
        List<Player> attackers = members(match, 0);
        List<Player> defenders = members(match, 1);
        JungleCamps.Camp drones = camp(match, "drones");
        Player killer = attackers.get(0);
        Player helper = attackers.get(1);
        killer.teleport(drones.at().add(1, 0, 0));
        helper.teleport(drones.at().add(-1, 0, 0));
        clear(match, drones, killer);
        // 45 XP split by two (rounded up) and +15 for the last hit.
        assertEquals(23 + 15, match.experience(killer.getUniqueId()));
        assertEquals(23, match.experience(helper.getUniqueId()));
        for (Player defender : defenders) {
            assertEquals(0, match.experience(defender.getUniqueId()), "nothing for the other team");
        }
        assertEquals(0, count(Vex.class), "no drone left");
        h.ticks(20);
        assertTrue(plain(drones.label().text()).contains("0:0"), "a respawn timer: " + plain(drones.label().text()));
        h.tickUntil(drones::isAlive, 20 * 6, "the drones to come back");
        assertEquals(3, count(Vex.class));

        // A far teammate gets nothing; alone the killer takes it all.
        JungleCamps.Camp rats = camp(match, "rats");
        helper.teleport(match.spawnFor(helper.getUniqueId()));
        int before = match.experience(killer.getUniqueId());
        clear(match, rats, killer);
        assertEquals(before + 45 + 15, match.experience(killer.getUniqueId()));
        assertEquals(23, match.experience(helper.getUniqueId()));
    }

    @Test
    void buffCampsGiveTheirTeamBuffs() {
        SiegeMatch match = start(2);
        Player attacker = members(match, 0).getFirst();
        Player defender = members(match, 1).getFirst();
        assertNotNull(match.camps().hudLine(), "the big camps in the sidebar");

        clear(match, camp(match, "red"), attacker);
        assertTrue(match.camps().hasRedBuff(attacker.getUniqueId()));
        assertFalse(match.camps().hasRedBuff(defender.getUniqueId()));
        assertNotNull(attacker.getPotionEffect(PotionEffectType.STRENGTH));
        assertEquals(100 + 30, match.experience(attacker.getUniqueId()), "buff camp: 100 XP and the last hit");
        defender.setFireTicks(0);
        defender.setNoDamageTicks(0);
        ctx(match).damage(attacker, defender, 1);
        assertTrue(defender.getFireTicks() > 0, "the red buff burns");

        // Blue: the gauge runs (from level 3) with ultimate.per-second at 0.
        match.progress().grant(defender.getUniqueId(), 200);
        match.abilities().setGauge(defender, 0);
        clear(match, camp(match, "blue"), defender);
        assertTrue(match.camps().hasBlueBuff(defender.getUniqueId()));
        assertNotNull(defender.getPotionEffect(PotionEffectType.HASTE));
        h.ticks(60);
        assertTrue(ctx(match).gauge(defender) > 0, "the blue buff charges the gauge");
        assertTrue(plain(match.camps().hudLine()).contains(":"), "respawn timers in the sidebar");

        match.expireHalf();
        assertFalse(match.camps().hasBlueBuff(defender.getUniqueId()), "buffs end with the half");
        assertNull(attacker.getPotionEffect(PotionEffectType.STRENGTH));
    }

    @Test
    void theEpicRewardsTheWholeTeam() {
        SiegeMatch match = start(4);
        List<Player> defenders = members(match, 1);
        Player killer = defenders.get(0);
        Player far = defenders.get(1);
        far.teleport(match.spawnFor(far.getUniqueId()));
        clear(match, camp(match, "guardian"), killer);
        assertEquals(300 + 50, match.experience(killer.getUniqueId()));
        assertEquals(300, match.experience(far.getUniqueId()), "every member, wherever");
        assertNotNull(far.getPotionEffect(PotionEffectType.RESISTANCE));
        assertNotNull(far.getPotionEffect(PotionEffectType.ABSORPTION));
        for (Player attacker : members(match, 0)) {
            assertEquals(0, match.experience(attacker.getUniqueId()));
        }
    }

    @Test
    void monstersOnlyHurtPlayersInPlayWithTheirOwnDamage() {
        SiegeMatch match = start(2);
        Player attacker = members(match, 0).getFirst();
        Mob golem = camp(match, "scrap").mobs().getFirst();
        Mob drone = camp(match, "drones").mobs().getFirst();
        double health = attacker.getHealth();
        attacker.setNoDamageTicks(0);
        attacker.damage(50, hit(golem));
        assertEquals(health - CampSettings.read(config).mob(CampKind.SCRAP).damage(), attacker.getHealth(), 1.0E-6,
                "the camp's damage, not the vanilla hit");
        double droneHealth = drone.getHealth();
        drone.setNoDamageTicks(0);
        drone.damage(5, hit(golem));
        assertEquals(droneHealth, drone.getHealth(), 1.0E-6, "monsters do not hurt each other");
    }
}
