package io.github.sx2xoox.mcpl.game.superpower;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Logger;

import io.github.sx2xoox.mcpl.core.game.GameResult;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.game.GameState;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.game.superpower.runtime.MatchContext;
import io.github.sx2xoox.mcpl.game.superpower.runtime.MatchPhase;
import io.github.sx2xoox.mcpl.game.superpower.runtime.Participant;
import io.github.sx2xoox.mcpl.game.superpower.runtime.SuperpowerMatch;
import io.github.sx2xoox.mcpl.game.superpower.support.MatchHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.ProbeAbility;
import io.github.sx2xoox.mcpl.game.superpower.support.Scenario;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.game.superpower.ui.AbilityItems;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wolf;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Regression tests for the superpower runtime: gauge, hook routing, match flow and settings. */
class RuntimeFixesTest {

    private static final double EPSILON = 1.0E-6;

    private MatchHarness h;

    @BeforeEach
    void setUp() {
        h = MatchHarness.flat();
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    // =================================================================== test abilities

    /** Deals damage now, later (nested tasks) and through summons; records its last instance. */
    static final class StrikerAbility extends Ability {

        static final AbilityInfo INFO = AbilityInfo.builder("striker", "타격수", Material.STICK)
                .passive("없음", "아무 일도 하지 않습니다.")
                .active("늑대", "늑대를 부릅니다.", 5)
                .ultimate("연타", "지금, 나중에, 늑대로 때립니다.")
                .build();
        static final List<StrikerAbility> INSTANCES = new ArrayList<>();

        Wolf ultimateWolf;
        Wolf activeWolf;

        StrikerAbility() {
            INSTANCES.add(this);
        }

        @Override
        public AbilityInfo info() {
            return INFO;
        }

        private LivingEntity enemy() {
            for (Player other : ctx().alivePlayers()) {
                if (ctx().isEnemy(player(), other)) {
                    return other;
                }
            }
            throw new IllegalStateException("no enemy");
        }

        private Wolf wolf() {
            Wolf wolf = player().getWorld().spawn(player().getLocation(), Wolf.class);
            wolf.getAttribute(Attribute.ATTACK_DAMAGE).setBaseValue(3);
            return ctx().summon(player(), wolf);
        }

        @Override
        public ActivationResult onActive() {
            activeWolf = wolf();
            return ActivationResult.success();
        }

        @Override
        public ActivationResult onUltimate() {
            LivingEntity target = enemy();
            ctx().damage(player(), target, 4);
            ctx().runLater(player(), 30, () -> ctx().runLater(player(), 30, () -> ctx().damage(player(), target, 5)));
            ultimateWolf = wolf();
            return ActivationResult.success();
        }
    }

    /** Cancels every hit it takes from an entity. */
    static final class DodgerAbility extends Ability {

        static final AbilityInfo INFO = AbilityInfo.builder("dodger", "회피꾼", Material.FEATHER)
                .passive("회피", "모든 공격을 피합니다.")
                .active("없음", "아무 일도 하지 않습니다.", 5)
                .ultimate("없음", "아무 일도 하지 않습니다.")
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
            return ActivationResult.success();
        }

        @Override
        public void onTakeDamage(EntityDamageEvent event) {
            if (event instanceof EntityDamageByEntityEvent) {
                event.setCancelled(true);
            }
        }
    }

    /** An ultimate that kills every enemy on the spot. */
    static final class FinisherAbility extends Ability {

        static final AbilityInfo INFO = AbilityInfo.builder("finisher", "마무리꾼", Material.NETHERITE_SWORD)
                .passive("없음", "아무 일도 하지 않습니다.")
                .active("없음", "아무 일도 하지 않습니다.", 5)
                .ultimate("끝내기일격", "모든 적을 쓰러뜨립니다.")
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
            for (Player other : List.copyOf(ctx().alivePlayers())) {
                ctx().damage(player(), other, 1000);
            }
            return ActivationResult.success();
        }
    }

    private void register(AbilityInfo info, Supplier<Ability> factory) {
        h.game.registry().register(info, factory::get);
        h.game.abilities().load(h.game.registry());
    }

    private static void melee(LivingEntity attacker, LivingEntity victim, double amount) {
        victim.setNoDamageTicks(0);
        victim.damage(amount, DamageSource.builder(attacker instanceof Player ? DamageType.PLAYER_ATTACK
                : DamageType.MOB_ATTACK).withCausingEntity(attacker).withDirectEntity(attacker).build());
    }

    // =================================================================== ultimate gauge

    @Test
    void anUltimateNeverRefillsItsOwnGauge() {
        h.settings().set(SuperpowerSettings.ULTIMATE_LOCK, 1);
        h.registerDummy();
        StrikerAbility.INSTANCES.clear();
        register(StrikerAbility.INFO, StrikerAbility::new);
        List<TestPlayer> players = h.join(2);
        SuperpowerMatch match = h.startFight();
        TestPlayer caster = players.get(0);
        TestPlayer victim = players.get(1);
        assertTrue(match.replaceAbility(caster, "striker"));
        assertTrue(match.replaceAbility(victim, "dummy"));
        h.healAll(players);
        MatchContext ctx = match.context();
        match.setGauge(caster, 100);
        match.setGauge(victim, 0);

        assertTrue(match.useUltimate(caster).succeeded());
        StrikerAbility striker = StrikerAbility.INSTANCES.getLast();
        assertEquals(16, victim.getHealth(), EPSILON);
        assertEquals(0, ctx.gauge(caster), EPSILON, "the ultimate's instant damage does not charge the caster");
        assertEquals(8, ctx.gauge(victim), EPSILON, "the victim still charges from damage taken");

        melee(caster, victim, 2);
        assertEquals(0, ctx.gauge(caster), EPSILON, "nothing charges right after an ultimate");
        ctx.addGauge(caster, 10);
        assertEquals(10, ctx.gauge(caster), EPSILON, "explicit grants are never locked");
        match.setGauge(caster, 0);

        h.ticks(65);
        assertEquals(16 - 2 - 5, victim.getHealth(), EPSILON, "the delayed ultimate hit landed");
        double charged = ctx.gauge(caster);
        assertTrue(charged <= 2.0, "delayed ultimate damage does not charge the caster (only time does): " + charged);

        double health = victim.getHealth();
        melee(striker.ultimateWolf, victim, 3);
        assertEquals(health - 3, victim.getHealth(), EPSILON, "a summon hits with exactly its attack damage");
        assertEquals(charged, ctx.gauge(caster), EPSILON, "the ultimate's summon does not charge the caster");

        assertTrue(match.useActive(caster).succeeded());
        melee(striker.activeWolf, victim, 3);
        assertEquals(charged + 12, ctx.gauge(caster), EPSILON, "other summons charge as usual (4% per damage)");
        melee(caster, victim, 2);
        assertEquals(charged + 20, ctx.gauge(caster), EPSILON, "melee charges again after the lock");
    }

    @Test
    void anUltimateThatEndsTheMatchIsNotAnnouncedOverTheResults() {
        h.registerDummy();
        register(FinisherAbility.INFO, FinisherAbility::new);
        List<TestPlayer> players = h.join(2);
        SuperpowerMatch match = h.startFight();
        GameSession session = match.session();
        TestPlayer caster = players.get(0);
        assertTrue(match.replaceAbility(caster, "finisher"));
        assertTrue(match.replaceAbility(players.get(1), "dummy"));
        Scenario.messages(caster);
        match.setGauge(caster, 100);

        assertTrue(match.useUltimate(caster).succeeded());
        assertNotEquals(GameState.RUNNING, session.state(), "the ultimate won the match");
        GameResult result = session.result();
        assertNotNull(result);
        assertTrue(result.isWinner(caster.getUniqueId()));
        for (String message : Scenario.messages(caster)) {
            assertFalse(message.contains("끝내기일격"), "no ultimate announcement after the results: " + message);
        }
    }

    // =================================================================== hook routing

    @Test
    void onHitEffectsOnlyRunForHitsThatLandedAndSummonHitsReachTheOwner() {
        h.registerTestAbilities();
        h.registerDummy();
        register(DodgerAbility.INFO, DodgerAbility::new);
        List<TestPlayer> players = h.join(2);
        SuperpowerMatch match = h.startFight();
        TestPlayer attacker = players.get(0);
        TestPlayer victim = players.get(1);
        assertTrue(match.replaceAbility(attacker, "probe"));
        ProbeAbility probe = ProbeAbility.INSTANCES.getLast();
        assertTrue(match.replaceAbility(victim, "dodger"));
        h.healAll(players);

        melee(attacker, victim, 3);
        assertEquals(1, probe.dealt, "onDealDamage sees the hit");
        assertEquals(0, probe.landed, "the victim dodged: no on-hit effects");
        assertEquals(20, victim.getHealth(), EPSILON);

        assertTrue(match.replaceAbility(victim, "dummy"));
        melee(attacker, victim, 3);
        assertEquals(2, probe.dealt);
        assertEquals(1, probe.landed, "a landed hit reaches onDamageDealt");

        Wolf wolf = attacker.getWorld().spawn(attacker.getLocation(), Wolf.class);
        wolf.getAttribute(Attribute.ATTACK_DAMAGE).setBaseValue(4);
        match.context().summon(attacker, wolf);
        double health = victim.getHealth();
        melee(wolf, victim, 4);
        assertEquals(3, probe.dealt, "a summon's hit goes to its owner's onDealDamage");
        assertEquals(2, probe.landed);
        assertEquals(health - 4, victim.getHealth(), EPSILON, "no difficulty scaling for summons");
    }

    @Test
    void abilityDamageLeavesTheVictimsInvulnerabilityAsItWas() {
        h.registerDummy();
        List<TestPlayer> players = h.join(2);
        SuperpowerMatch match = h.startFight();
        TestPlayer attacker = players.get(0);
        TestPlayer victim = players.get(1);
        assertTrue(match.replaceAbility(attacker, "dummy"));
        assertTrue(match.replaceAbility(victim, "dummy"));
        h.healAll(players);
        victim.setLastDamage(6);
        victim.setNoDamageTicks(15);
        match.context().damage(attacker, victim, 2);
        assertEquals(18, victim.getHealth(), EPSILON);
        assertEquals(6, victim.getLastDamage(), EPSILON, "a following melee hit is still compared with 6");
        assertEquals(15, victim.getNoDamageTicks());
    }

    // =================================================================== match flow

    @Test
    void theLastPlayersDyingToTheBorderTogetherIsADraw() {
        h.settings().set("border.start-delay-seconds", 0);
        h.settings().set("border.shrink-seconds", 0);
        h.settings().set("border.final-diameter", 6);
        h.settings().set("border.damage-per-second", 3);
        h.registerDummy();
        List<TestPlayer> players = h.join(2);
        SuperpowerMatch match = h.startFight();
        GameSession session = match.session();
        Location center = session.arena().center();
        for (TestPlayer player : players) {
            assertTrue(match.replaceAbility(player, "dummy"));
        }
        players.get(0).teleport(center.clone().add(40, 0, 0));
        players.get(1).teleport(center.clone().add(-40, 0, 0));
        for (TestPlayer player : players) {
            player.setHealth(1);
        }
        h.tickUntil(() -> session.state() != GameState.RUNNING, 60, "the border deaths");
        GameResult result = session.result();
        assertNotNull(result);
        assertTrue(result.isDraw(), "nobody survived the same border tick: a draw");
    }

    @Test
    void rerollFailuresFromTheItemGoToTheActionBar() {
        h.settings().set("prep-seconds", 30);
        h.settings().set("selection.rerolls", 0);
        List<TestPlayer> players = h.join(2);
        h.start();
        h.tickUntilPhase(MatchPhase.PREP);
        TestPlayer player = players.getFirst();
        Scenario.messages(player);
        player.setSneaking(true);
        for (int i = 0; i < 5; i++) {
            h.server.getPluginManager().callEvent(new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR,
                    player.getInventory().getItem(AbilityItems.SLOT), null, BlockFace.SELF, EquipmentSlot.HAND));
            h.ticks(4);
        }
        assertTrue(Scenario.messages(player).isEmpty(), "holding sneak + right-click does not spam the chat");
    }

    @Test
    void spectatorsSeeTheFinalAbilitiesWhenTheFightStarts() {
        h.settings().set("prep-seconds", 5);
        List<TestPlayer> players = h.join(2);
        TestPlayer viewer = h.join("Viewer");
        h.hub.games().setPrefersSpectating(viewer, true);
        h.start();
        h.tickUntilPhase(MatchPhase.PREP);
        SuperpowerMatch match = h.match();
        assertEquals(SuperpowerMatch.RerollResult.OK, match.reroll(players.getFirst()));
        h.tickUntilPhase(MatchPhase.FIGHT);
        Participant rerolled = match.participant(players.getFirst().getUniqueId());
        String name = h.game.registry().get(rerolled.abilityId()).info().name();
        List<String> seen = Scenario.messages(viewer);
        assertTrue(seen.stream().anyMatch(line -> line.contains("이번 경기의 능력")), "the list was sent: " + seen);
        assertTrue(seen.stream().anyMatch(line -> line.contains(players.getFirst().getName()) && line.contains(name)),
                "the list shows the rerolled ability " + name + ": " + seen);
    }

    @Test
    void withoutPrepTimeThereIsNoPrepAnnouncement() {
        h.settings().set("prep-seconds", 0);
        List<TestPlayer> players = h.join(2);
        h.start();
        h.tickUntilPhase(MatchPhase.FIGHT);
        for (String message : Scenario.messages(players.getFirst())) {
            assertFalse(message.contains("준비 시간"), "no '준비 시간 0초': " + message);
            assertFalse(message.contains("다시 뽑을 수"), "no reroll hint without a prep phase: " + message);
        }
    }

    // =================================================================== settings & texts

    @Test
    void numbersShowUpToTwoDecimals() {
        assertEquals("3", AbilityInfo.formatNumber(3.0));
        assertEquals("1.5", AbilityInfo.formatNumber(1.5));
        assertEquals("0.25", AbilityInfo.formatNumber(0.25));
        assertEquals("2.25", AbilityInfo.formatNumber(2.25));
        assertEquals("0.33", AbilityInfo.formatNumber(1.0 / 3.0));
        assertEquals("10", AbilityInfo.formatNumber(9.999));
    }

    @Test
    void sectionCommentsAndEffectScaleSettings() {
        MemoryConfiguration defaults = new MemoryConfiguration();
        SuperpowerSettings.declareDefaults(defaults);
        assertFalse(defaults.getComments("ultimate").isEmpty(), "the ultimate section has its comment");
        assertFalse(defaults.getComments("border").isEmpty(), "the border section has its comment");
        assertFalse(defaults.getComments(SuperpowerSettings.PARTICLE_SCALE).isEmpty());

        Logger logger = Logger.getLogger("RuntimeFixesTest");
        assertEquals(1.0, SuperpowerSettings.read(defaults, logger).particleScale(), EPSILON);
        assertEquals(200, SuperpowerSettings.read(defaults, logger).ultimateLockTicks());
        defaults.set(SuperpowerSettings.PARTICLE_SCALE, 5.0);
        assertEquals(2.0, SuperpowerSettings.read(defaults, logger).particleScale(), EPSILON);
        defaults.set(SuperpowerSettings.PARTICLE_SCALE, 0.0);
        assertEquals(0.25, SuperpowerSettings.read(defaults, logger).particleScale(), EPSILON);

        h.settings().set(SuperpowerSettings.PARTICLE_SCALE, 0.5);
        h.join(2);
        SuperpowerMatch match = h.startFight();
        assertEquals(0.5, match.context().effectScale(), EPSILON, "abilities see the configured particle scale");
    }
}
