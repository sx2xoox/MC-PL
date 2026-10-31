package io.github.sx2xoox.mcpl.game.superpower;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.game.GameState;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.game.superpower.runtime.MatchContext;
import io.github.sx2xoox.mcpl.game.superpower.runtime.MatchPhase;
import io.github.sx2xoox.mcpl.game.superpower.runtime.Participant;
import io.github.sx2xoox.mcpl.game.superpower.runtime.SuperpowerMatch;
import io.github.sx2xoox.mcpl.game.superpower.support.MatchHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.ProbeAbility;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The ability practice ({@code /게임 능력 연습}): solo start, training dummies, practice commands, cleanup. */
class PracticeModeTest {

    private static final double EPSILON = 1.0E-6;

    private MatchHarness h;
    private FlatWorld world;
    private TestPlayer admin;

    @BeforeEach
    void setUp() {
        h = MatchHarness.flat();
        world = h.flatWorld();
        h.registerTestAbilities();
        // Only damage charges the gauge in these tests.
        h.settings().set("ultimate.per-second", 0);
        admin = h.join("Admin");
        admin.setOp(true);
        place(admin, 0.5, 0.5, 0f);
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    // ------------------------------------------------------------------ helpers

    private void place(Player player, double x, double z, float yaw) {
        player.teleport(new Location(world, x, FlatWorld.SURFACE, z, yaw, 0f));
        player.setVelocity(new Vector());
        player.setFallDistance(0f);
    }

    private void aimAt(Player player, Entity target) {
        Location point = target.getLocation().add(0, target.getHeight() * 0.6, 0);
        Location look = player.getLocation();
        look.setDirection(point.toVector().subtract(player.getEyeLocation().toVector()));
        player.teleport(look);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static List<String> drain(TestPlayer player) {
        List<String> lines = new ArrayList<>();
        Component next;
        while ((next = player.nextComponentMessage()) != null) {
            lines.add(plain(next));
        }
        return lines;
    }

    private static boolean anyContains(List<String> lines, String text) {
        return lines.stream().anyMatch(line -> line.contains(text));
    }

    /** Runs the hub countdown until the practice fight is on. */
    private SuperpowerMatch untilPracticeRuns() {
        h.tickUntil(() -> h.match() != null && h.match().hasStarted() && h.match().phase() == MatchPhase.FIGHT,
                400, "the practice to start");
        SuperpowerMatch match = h.match();
        assertNotNull(match);
        assertTrue(match.isPractice());
        return match;
    }

    /** {@code /게임 능력 연습 <ability>} as the admin, run until it started. */
    private SuperpowerMatch practice(String ability) {
        assertTrue(h.server.dispatchCommand(admin, "게임 능력 연습 " + ability));
        return untilPracticeRuns();
    }

    private Participant participant(SuperpowerMatch match, Player player) {
        Participant participant = match.participant(player.getUniqueId());
        assertNotNull(participant, player.getName() + " takes part");
        return participant;
    }

    private List<TextDisplay> damageNumbers() {
        List<TextDisplay> numbers = new ArrayList<>();
        for (Entity entity : world.getEntities()) {
            if (entity instanceof TextDisplay display && plain(display.text()).startsWith("-")) {
                numbers.add(display);
            }
        }
        return numbers;
    }

    private long countEntities(Class<? extends Entity> type) {
        return world.getEntities().stream().filter(type::isInstance).count();
    }

    // ------------------------------------------------------------------ tests

    @Test
    void aSinglePlayerCanStartPracticeWithTheChosenAbility() {
        TestPlayer bystander = h.join("Bystander");
        place(bystander, 6.5, -6.5, 0f);
        drain(admin);
        SuperpowerMatch match = practice("화염술사");
        GameSession session = match.session();
        assertEquals(SuperpowerPracticeGame.ID, session.game().id());
        assertEquals(1, session.aliveCount(), "only the sender plays");
        assertTrue(session.isInPlay(admin));
        assertFalse(session.isParticipant(bystander), "everyone else watches");
        Participant participant = participant(match, admin);
        assertEquals("pyro", participant.abilityId());
        assertNotNull(participant.ability(), "bound and granted right away: no reveal or prep phase");
        assertEquals(0, match.settings().timeLimitTicks(), "no time limit");
        assertEquals(0, match.settings().prepTicks(), "no prep phase");
        assertTrue(anyContains(drain(admin), "능력 연습을 시작합니다"));

        h.ticks(20 * 30);
        assertTrue(session.isRunning(), "a lone player is no winner: practice keeps running");
        assertEquals(GameState.RUNNING, session.state());
    }

    @Test
    void practiceIsAModeOfItsOwnButNotInTheRotation() {
        assertNotNull(h.hub.games().find("연습"));
        assertNotNull(h.hub.games().find("능력연습"));
        assertSame(h.game.practice(), h.hub.games().find("practice"));
        assertEquals(1, h.game.practice().minPlayers());
        assertFalse(h.hub.config().rotationGames().contains(SuperpowerPracticeGame.ID));

        TestPlayer friend = h.join("Friend");
        assertTrue(h.server.dispatchCommand(admin, "게임 시작 연습"));
        SuperpowerMatch match = untilPracticeRuns();
        assertEquals(2, match.session().aliveCount(), "/게임 시작 연습 lets everyone practice");
        assertTrue(match.context().isEnemy(admin, friend), "players are enemies in a shared practice");
        assertEquals(3, match.dummiesOf(friend).size());
    }

    @Test
    void dummiesStandInFrontAndAreEnemies() {
        SuperpowerMatch match = practice("probe");
        List<Mannequin> dummies = match.dummiesOf(admin);
        assertEquals(3, dummies.size());
        double[] distances = {5, 8, 11};
        for (int i = 0; i < dummies.size(); i++) {
            Mannequin dummy = dummies.get(i);
            assertEquals("훈련용 허수아비", plain(dummy.customName()));
            assertEquals(distances[i], dummy.getLocation().getZ() - admin.getLocation().getZ(), 1.0E-3,
                    "dummy " + i + " stands " + distances[i] + " blocks ahead");
            assertTrue(dummy.getHealth() >= 100, "dummies have a lot of health");
            assertTrue(match.isDummy(dummy));
        }
        MatchContext ctx = match.context();
        for (Mannequin dummy : dummies) {
            assertTrue(ctx.isEnemy(admin, dummy), "a dummy is an enemy");
            assertFalse(ctx.isAlly(admin, dummy));
        }
        List<LivingEntity> near = ctx.enemiesNear(admin, admin.getLocation(), 20);
        assertTrue(near.containsAll(dummies), "enemiesNear finds the dummies");
        assertEquals(dummies.getFirst(), near.getFirst(), "nearest first");
        assertEquals(dummies.getFirst(), ctx.findTarget(admin, 20), "the aimed dummy straight ahead is targeted");
        aimAt(admin, dummies.get(2));
        assertEquals(dummies.get(2), ctx.findTarget(admin, 20), "the far dummies can be aimed at past the near one");
    }

    @Test
    void aDamagingActiveHurtsADummyShowsTheNumberAndChargesTheGauge() {
        SuperpowerMatch match = practice("thunder");
        Mannequin dummy = match.dummiesOf(admin).getFirst();
        double before = dummy.getHealth();
        aimAt(admin, dummy);
        ActivationResult result = match.useActive(admin);
        assertTrue(result.succeeded(), "낙뢰: " + result.reason());
        double damage = match.context().paramsFor("thunder").num("damage");
        h.tickUntil(() -> dummy.getHealth() < before, 100, "the bolt to strike the dummy");
        assertEquals(damage, before - dummy.getHealth(), 1.0E-3, "the bolt's damage landed on the dummy");
        List<TextDisplay> numbers = damageNumbers();
        assertEquals(1, numbers.size(), "a floating damage number");
        assertEquals("-" + io.github.sx2xoox.mcpl.core.ui.Format.number(damage), plain(numbers.getFirst().text()));
        assertEquals(damage * h.settings().getDouble("ultimate.per-damage-dealt"),
                participant(match, admin).gauge(), 1.0E-3, "damage to a dummy charges the gauge");

        double y = numbers.getFirst().getLocation().getY();
        h.ticks(6);
        assertTrue(numbers.getFirst().getLocation().getY() > y, "the number rises");
        h.ticks(30);
        assertTrue(damageNumbers().isEmpty(), "and disappears");
        h.ticks(80);
        assertEquals(dummy.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue(), dummy.getHealth(),
                EPSILON, "the dummy is healed after a pause");
    }

    @Test
    void meleeOnADummyIsCreditedToTheAttackersAbility() {
        SuperpowerMatch match = practice("probe");
        Mannequin dummy = match.dummiesOf(admin).getFirst();
        ProbeAbility probe = (ProbeAbility) participant(match, admin).ability();
        assertNotNull(probe);
        dummy.damage(3, DamageSource.builder(DamageType.PLAYER_ATTACK).withCausingEntity(admin)
                .withDirectEntity(admin).build());
        assertEquals(1, probe.dealt, "onDealDamage ran for the dummy");
        assertEquals(1, probe.landed, "onDamageDealt ran for the dummy");
        assertEquals(1, damageNumbers().size());
    }

    @Test
    void aKilledOrLostDummyComesBack() {
        SuperpowerMatch match = practice("probe");
        Mannequin first = match.dummiesOf(admin).getFirst();
        Location spot = first.getLocation();
        first.setHealth(0);
        first.remove();
        h.ticks(40);
        List<Mannequin> dummies = match.dummiesOf(admin);
        assertEquals(3, dummies.size(), "the dummy respawned");
        Mannequin back = dummies.getFirst();
        assertNotEquals(first.getUniqueId(), back.getUniqueId());
        assertEquals(0, back.getLocation().distance(spot), 1.0E-3, "on its spot");

        back.teleport(back.getLocation().add(0, 0, 40));
        h.ticks(20);
        assertEquals(0, back.getLocation().distance(spot), 1.0E-3, "a dummy thrown far away is put back");
    }

    @Test
    void switchingAbilitiesRevokesTheOldOneAndGrantsTheNewOne() {
        SuperpowerMatch match = practice("probe");
        ProbeAbility probe = (ProbeAbility) participant(match, admin).ability();
        assertNotNull(probe);
        assertEquals(1, probe.grants);
        admin.setHealth(10);
        drain(admin);

        assertTrue(h.server.dispatchCommand(admin, "게임 능력 변경 빙결술사"));
        Participant participant = participant(match, admin);
        assertEquals("frost", participant.abilityId());
        assertNotNull(participant.ability());
        assertEquals("frost", participant.ability().info().id());
        assertEquals(1, probe.revokes, "the old ability was revoked");
        assertEquals(20, admin.getHealth(), EPSILON, "healed for the new ability");
        assertTrue(anyContains(drain(admin), "빙결술사"));

        // The titan's bonus health comes with it and goes away with it.
        assertTrue(h.server.dispatchCommand(admin, "mcpl ability switch titan"));
        double titanMax = admin.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
        assertTrue(titanMax > 20);
        assertEquals(titanMax, admin.getHealth(), EPSILON);
        assertTrue(h.server.dispatchCommand(admin, "게임 능력 변경 probe"));
        assertEquals(20, admin.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue(), EPSILON);
        assertEquals(20, admin.getHealth(), EPSILON);

        // "연습 <능력>" while practicing switches too.
        assertTrue(h.server.dispatchCommand(admin, "게임 능력 연습 pyro"));
        assertEquals("pyro", participant(match, admin).abilityId());
    }

    @Test
    void replaceAbilityKeepsTheDamageTakenAcrossMaxHealthChanges() {
        SuperpowerMatch match = practice("titan");
        double titanMax = admin.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
        admin.setHealth(titanMax - 4);
        assertTrue(match.replaceAbility(admin, "probe"));
        assertEquals(16, admin.getHealth(), EPSILON, "4 damage taken stays 4 damage taken");
        assertTrue(match.replaceAbility(admin, "titan"));
        assertEquals(titanMax - 4, admin.getHealth(), EPSILON);
    }

    @Test
    void chargeFillsTheGauge() {
        SuperpowerMatch match = practice("probe");
        assertEquals(0, participant(match, admin).gauge(), EPSILON);
        assertTrue(h.server.dispatchCommand(admin, "게임 능력 충전"));
        assertEquals(100, participant(match, admin).gauge(), EPSILON);
        assertEquals(100, admin.getLevel(), "the XP bar shows it");
        assertTrue(match.useUltimate(admin).succeeded());
        assertTrue(h.server.dispatchCommand(admin, "mcpl ability charge"));
        assertEquals(100, participant(match, admin).gauge(), EPSILON, "again right after an ultimate");
    }

    @Test
    void cooldownsCanBeTurnedOff() {
        SuperpowerMatch match = practice("probe");
        assertTrue(match.useActive(admin).succeeded());
        ActivationResult blocked = match.useActive(admin);
        assertFalse(blocked.succeeded());
        assertEquals("cooldown", blocked.reason(), "default: the normal cooldown");

        assertTrue(h.server.dispatchCommand(admin, "게임 능력 쿨타임 끄기"));
        assertFalse(match.cooldownsEnabled());
        for (int i = 0; i < 3; i++) {
            assertTrue(match.useActive(admin).succeeded(), "use " + i + " right away");
        }
        assertTrue(h.server.dispatchCommand(admin, "mcpl ability cooldown on"));
        assertTrue(match.useActive(admin).succeeded());
        assertFalse(match.useActive(admin).succeeded(), "cooldowns are back");
    }

    @Test
    void deathRespawnsWithKitAndAbility() {
        SuperpowerMatch match = practice("probe");
        GameSession session = match.session();
        Location home = admin.getLocation();
        admin.getInventory().clear();
        admin.setNoDamageTicks(0);
        admin.damage(10_000, DamageSource.builder(DamageType.GENERIC_KILL).build());
        assertTrue(admin.isDead());
        assertNull(participant(match, admin).ability(), "revoked while dead");
        admin.respawn();
        h.server.getPluginManager().callEvent(new PlayerPostRespawnEvent(admin, admin.getLocation(), false, false,
                false, PlayerRespawnEvent.RespawnReason.DEATH));
        assertTrue(session.isRunning(), "death does not end the practice");
        assertTrue(session.isInPlay(admin), "back in play");
        assertEquals(GameMode.ADVENTURE, admin.getGameMode());
        assertEquals(0, admin.getLocation().distance(home), 1.0E-3, "at the practice spot");
        assertNotNull(participant(match, admin).ability(), "the ability is bound again");
        assertEquals("probe", participant(match, admin).abilityId());
        assertTrue(admin.getInventory().contains(Material.IRON_SWORD), "the kit is given again");
    }

    @Test
    void aPracticePlayerWaitingToRespawnIsNoSpectatorForAbilities() {
        SuperpowerMatch match = practice("probe");
        MatchContext ctx = match.context();
        assertFalse(ctx.isSpectator(admin));
        admin.setNoDamageTicks(0);
        admin.damage(10_000, DamageSource.builder(DamageType.GENERIC_KILL).build());
        assertTrue(admin.isDead());
        assertTrue(match.session().isSpectator(admin), "the session counts everyone out of play");
        assertFalse(ctx.isSpectator(admin), "but it still plays: spectators-only displays stay hidden from it");
        admin.respawn();
        h.server.getPluginManager().callEvent(new PlayerPostRespawnEvent(admin, admin.getLocation(), false, false,
                false, PlayerRespawnEvent.RespawnReason.DEATH));
        assertFalse(ctx.isSpectator(admin));
    }

    @Test
    void practiceCommandsAreRefusedInARealMatch() {
        TestPlayer other = h.join("Other");
        place(other, 4.5, 4.5, 180f);
        SuperpowerMatch match = h.startFight();
        assertFalse(match.isPractice());
        Participant participant = participant(match, admin);
        String ability = participant.abilityId();
        drain(admin);
        for (String command : List.of("게임 능력 변경 화염술사", "게임 능력 충전", "게임 능력 허수아비",
                "게임 능력 쿨타임 끄기", "게임 능력 연습", "mcpl ability charge")) {
            h.server.dispatchCommand(admin, command);
            assertTrue(anyContains(drain(admin), "진짜 경기 중"), command + " is refused");
        }
        assertEquals(ability, participant.abilityId(), "the ability is unchanged");
        assertTrue(participant.gauge() < 100, "the gauge is unchanged");
        assertTrue(match.cooldownsEnabled());
        assertEquals(0, countEntities(Mannequin.class), "no dummies in a real match");
    }

    @Test
    void stopRestoresThePlayerAndRemovesTheDummies() {
        admin.getInventory().clear();
        admin.getInventory().setItem(0, ItemStack.of(Material.DIAMOND, 5));
        admin.setGameMode(GameMode.SURVIVAL);
        SuperpowerMatch match = practice("thunder");
        GameSession session = match.session();
        Mannequin dummy = match.dummiesOf(admin).getFirst();
        aimAt(admin, dummy);
        assertTrue(match.useActive(admin).succeeded());
        h.tickUntil(() -> !damageNumbers().isEmpty(), 100, "a damage number");
        assertEquals(3, countEntities(Mannequin.class));

        assertTrue(h.server.dispatchCommand(admin, "게임 중지"));
        assertEquals(GameState.CLOSED, session.state());
        assertNull(h.hub.games().session());
        assertEquals(0, countEntities(Mannequin.class), "every dummy is gone");
        assertEquals(0, countEntities(TextDisplay.class), "every damage number is gone");
        assertEquals(0, h.hub.tempBlocks().size());
        h.ticks(5);
        assertEquals(0, countEntities(Mannequin.class), "and no dummy comes back");
        ItemStack restored = admin.getInventory().getItem(0);
        assertNotNull(restored);
        assertEquals(Material.DIAMOND, restored.getType(), "the inventory is restored");
        assertFalse(admin.getInventory().contains(Material.IRON_SWORD), "the kit is gone");
        assertEquals(GameMode.SURVIVAL, admin.getGameMode());
        assertEquals(20, admin.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue(), EPSILON);
    }

    @Test
    void everyoneLeavingEndsThePractice() {
        SuperpowerMatch match = practice("probe");
        GameSession session = match.session();
        admin.disconnect();
        assertEquals(GameState.CLOSED, session.state());
        assertEquals(0, countEntities(Mannequin.class));
    }

    @Test
    void othersCanJoinARunningPractice() {
        TestPlayer friend = h.join("Friend");
        SuperpowerMatch match = practice("probe");
        assertFalse(match.session().isInPlay(friend));
        assertTrue(h.server.dispatchCommand(friend, "게임 능력 연습 빙결술사"));
        assertTrue(match.session().isInPlay(friend), "joined the running practice");
        assertEquals("frost", participant(match, friend).abilityId());
        assertNotNull(participant(match, friend).ability());
        assertEquals(3, match.dummiesOf(friend).size());
        assertTrue(match.context().isEnemy(friend, match.dummiesOf(admin).getFirst()),
                "every dummy is everyone's enemy");
    }

    @Test
    void aPlayerLoggingInWatchesUntilTheyAsk() {
        SuperpowerMatch match = practice("probe");
        TestPlayer late = h.join("Late");
        assertFalse(match.session().isInPlay(late), "logging in does not pull anyone into the practice");
        assertEquals(GameMode.SPECTATOR, late.getGameMode());
        h.server.dispatchCommand(late, "게임 참가");
        assertFalse(match.session().isInPlay(late), "only /게임 능력 연습 joins");
        assertTrue(h.server.dispatchCommand(late, "mcpl ability practice"));
        assertTrue(match.session().isInPlay(late));
        assertNotNull(participant(match, late).ability(), "a random ability");
    }

    @Test
    void onlyAdminsStartAPractice() {
        TestPlayer user = h.join("User");
        drain(user);
        h.server.dispatchCommand(user, "게임 능력 연습");
        assertNull(h.hub.games().session());
        assertTrue(anyContains(drain(user), "관리자만"));
    }

    @Test
    void practiceActionsNeedARunningPractice() {
        drain(admin);
        h.server.dispatchCommand(admin, "게임 능력 충전");
        assertTrue(anyContains(drain(admin), "진행 중인 능력 연습이 없습니다"));
        h.server.dispatchCommand(admin, "게임 능력 연습 없는능력");
        assertTrue(anyContains(drain(admin), "찾을 수 없습니다"));
        assertNull(h.hub.games().session());
    }

    @Test
    void dummiesCanBePlacedAgain() {
        SuperpowerMatch match = practice("probe");
        List<Mannequin> before = match.dummiesOf(admin);
        place(admin, 10.5, 0.5, 90f);
        assertTrue(h.server.dispatchCommand(admin, "게임 능력 허수아비 5"));
        List<Mannequin> after = match.dummiesOf(admin);
        assertEquals(5, after.size());
        for (Mannequin old : before) {
            assertFalse(old.isValid(), "the old dummies are removed");
        }
        assertEquals(5, countEntities(Mannequin.class));
        // Facing yaw 90 (towards -x): the first dummy is 5 blocks that way.
        assertEquals(5.5, after.getFirst().getLocation().getX(), 1.0E-3);
    }
}
