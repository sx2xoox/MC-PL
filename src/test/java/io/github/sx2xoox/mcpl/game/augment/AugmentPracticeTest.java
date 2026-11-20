package io.github.sx2xoox.mcpl.game.augment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.game.GameState;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import io.github.sx2xoox.mcpl.game.augment.bonus.BonusCondition;
import io.github.sx2xoox.mcpl.game.augment.command.AugmentCommand;
import io.github.sx2xoox.mcpl.game.augment.command.PracticeCommand;
import io.github.sx2xoox.mcpl.game.augment.runtime.AugmentMatch;
import io.github.sx2xoox.mcpl.game.augment.runtime.MatchPhase;
import io.github.sx2xoox.mcpl.game.augment.runtime.Offer;
import io.github.sx2xoox.mcpl.game.augment.support.AugmentHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 증강 연습: start alone, commands, dummies as enemies, phase skipping, cleanup. */
class AugmentPracticeTest {

    private AugmentHarness h;
    private TestPlayer admin;

    @BeforeEach
    void setUp() {
        h = new AugmentHarness();
        admin = h.join("Admin");
        admin.setOp(true);
        h.settings().set(AugmentPracticeSettings.RADIUS, 48);
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    private AugmentMatch practice() {
        assertTrue(h.server.dispatchCommand(admin, "게임 증강 연습"));
        h.tickUntil(() -> h.match() != null && h.match().phase() == MatchPhase.FARMING, 3000, "practice farming");
        return h.match();
    }

    private AugmentInfo info(String id) {
        return h.game.registry().get(id).info();
    }

    private void melee(LivingEntity victim, TestPlayer attacker, double amount) {
        victim.setNoDamageTicks(0);
        victim.damage(amount, DamageSource.builder(DamageType.PLAYER_ATTACK)
                .withCausingEntity(attacker).withDirectEntity(attacker).build());
    }

    @Test
    void oneAdminStartsAPracticeInAFreshSmallWorldWithoutRoundsOrATimer() {
        admin.getInventory().setItem(0, ItemStack.of(Material.DIAMOND, 2));
        AugmentMatch match = practice();
        assertTrue(match.isPractice());
        GameSession session = match.session();
        assertEquals(AugmentPracticeGame.ID, session.game().id());
        assertEquals("mcpl_wild_1", session.arena().world().getName());
        assertEquals(96, session.arena().world().getWorldBorder().getSize(), 1.0E-6, "practice radius");
        assertEquals(GameMode.SURVIVAL, admin.getGameMode());
        h.ticks(20 * 15); // longer than the farming time and the round of the harness
        assertEquals(MatchPhase.FARMING, match.phase(), "phases only advance by command");
        assertEquals(0, match.roundsPlayed(), "no timed rounds");
        assertEquals(GameState.RUNNING, session.state(), "no winner with one player");

        h.hub.games().stop(net.kyori.adventure.text.Component.text("test"));
        h.tickUntil(() -> session.state() == GameState.CLOSED, 400, "closed");
        assertEquals(List.of("mcpl_wild_1"), h.worlds.deleted, "the practice world is deleted");
        assertEquals("world", admin.getWorld().getName());
        assertEquals(2, admin.getInventory().getItem(0).getAmount(), "inventory restored");
    }

    @Test
    void onlyAdminsStartButAnyoneJoinsARunningPractice() {
        TestPlayer friend = h.join("Friend");
        assertTrue(h.server.dispatchCommand(friend, "게임 증강 연습"));
        assertNull(h.hub.games().session(), "a non-admin cannot start one");
        AugmentMatch match = practice();
        assertFalse(match.session().isInPlay(friend), "others watch until they ask");
        assertTrue(h.server.dispatchCommand(friend, "게임 증강 연습"));
        assertTrue(match.session().isInPlay(friend), "joined");
        assertEquals(match.wild().world(), friend.getWorld());
        assertEquals(GameMode.SURVIVAL, friend.getGameMode());
    }

    @Test
    void giveTakeAndCardsWorkByCommand() {
        AugmentMatch match = practice();
        assertTrue(h.server.dispatchCommand(admin, "게임 증강 받기 강철 체력"));
        assertTrue(match.owns(admin.getUniqueId(), info("iron_body")));
        assertEquals(24, admin.getAttribute(Attribute.MAX_HEALTH).getValue(), 1.0E-6);
        assertTrue(h.server.dispatchCommand(admin, "게임 증강 받기 gale"));
        assertTrue(h.server.dispatchCommand(admin, "게임 증강 빼기 iron_body"));
        assertFalse(match.owns(admin.getUniqueId(), info("iron_body")));
        assertEquals(20, admin.getAttribute(Attribute.MAX_HEALTH).getValue(), 1.0E-6, "its modifier is gone");
        assertTrue(h.server.dispatchCommand(admin, "게임 증강 빼기 전부"));
        assertTrue(match.augments(admin.getUniqueId()).isEmpty());

        assertTrue(h.server.dispatchCommand(admin, "게임 증강 카드 골드"));
        Offer offer = match.currentOffer(admin.getUniqueId());
        assertNotNull(offer, "an offer opened right away");
        assertEquals(AugmentTier.GOLD, offer.tier());
        assertTrue(match.reroll(admin), "rerolls work");
        AugmentInfo card = offer.cards().getFirst();
        assertTrue(match.pick(admin, card));
        assertTrue(match.owns(admin.getUniqueId(), card));
    }

    @Test
    void tabCompletionOffersAugmentsAndAll() {
        practice();
        h.server.dispatchCommand(admin, "게임 증강 받기 iron_body");
        List<String> give = AugmentCommand.augmentSuggestions(h.game.registry(), "강철");
        assertTrue(give.contains("강철 체력"), give.toString());
        List<String> take = PracticeCommand.ownedSuggestions(h.match(), admin.getUniqueId(), "");
        assertTrue(take.contains("전부"), take.toString());
        assertTrue(take.contains("iron_body"), take.toString());
        assertFalse(take.contains("gale"), "only owned ones");
    }

    @Test
    void nextPhaseTurnsOnTheFightThenShrinksTheBorder() {
        AugmentMatch match = practice();
        assertTrue(h.server.dispatchCommand(admin, "게임 증강 다음단계"));
        assertEquals(MatchPhase.FIGHT, match.phase());
        assertFalse(match.borderShrinking());
        assertTrue(h.server.dispatchCommand(admin, "게임 증강 다음단계"));
        assertTrue(match.borderShrinking(), "the border shrinks now");
        assertTrue(h.server.dispatchCommand(admin, "게임 증강 다음단계"));
        assertTrue(match.borderFinal());
        assertFalse(h.server.dispatchCommand(admin, "게임 증강 다음단계") && !match.borderFinal());
    }

    @Test
    void dummiesAreEnemiesForAugmentsAndTheirKillsCount() {
        AugmentMatch match = practice();
        assertTrue(h.server.dispatchCommand(admin, "게임 증강 허수아비 2"));
        List<? extends LivingEntity> dummies = match.dummiesOf(admin.getUniqueId());
        assertEquals(2, dummies.size());
        LivingEntity dummy = dummies.getFirst();
        double max = dummy.getHealth();
        assertEquals(40, max, 1.0E-6, "configured health");

        h.server.dispatchCommand(admin, "게임 증강 받기 sharp_edge");
        melee(dummy, admin, 2);
        double bonus = h.game.augments().snapshot().params(info("sharp_edge")).num("damage");
        assertEquals(2 + bonus, max - dummy.getHealth(), 1.0E-6, "outgoing damage hooks run on a dummy");

        h.server.dispatchCommand(admin, "게임 증강 받기 war_god");
        double attack = admin.getAttribute(Attribute.ATTACK_DAMAGE).getValue();
        melee(dummy, admin, 100);
        assertTrue(dummy.isDead(), "killed");
        assertTrue(admin.getAttribute(Attribute.ATTACK_DAMAGE).getValue() > attack, "war god counted the kill");
        assertTrue(match.bonuses(admin.getUniqueId()).contains(BonusCondition.FIRST_BLOOD),
                "a dummy kill is the first blood in practice");
        h.ticks(40);
        assertEquals(2, match.dummiesOf(admin.getUniqueId()).size(), "the dummy came back");
    }

    @Test
    void trackingAugmentsSeeDummiesAfterTheFightStarts() {
        AugmentMatch match = practice();
        h.server.dispatchCommand(admin, "게임 증강 허수아비 1");
        h.server.dispatchCommand(admin, "게임 증강 받기 hunter_compass");
        h.server.dispatchCommand(admin, "게임 증강 다음단계");
        LivingEntity dummy = match.dummiesOf(admin.getUniqueId()).getFirst();
        h.ticks(40);
        assertEquals(dummy.getLocation().getBlockX(), admin.getCompassTarget().getBlockX(), "the compass points at it");
    }

    @Test
    void theCompassTracksDummiesWhileFarmingInPractice() {
        AugmentMatch match = practice();
        h.server.dispatchCommand(admin, "게임 증강 허수아비 1");
        h.server.dispatchCommand(admin, "게임 증강 받기 hunter_compass");
        LivingEntity dummy = match.dummiesOf(admin.getUniqueId()).getFirst();
        h.ticks(40);
        assertEquals(dummy.getLocation().getBlockX(), admin.getCompassTarget().getBlockX(),
                "practice dummies are tracked before the fight phase too");
    }

    @Test
    void practisingPlayersCannotHurtEachOtherUntilPvpIsOn() {
        AugmentMatch match = practice();
        TestPlayer friend = h.join("Friend");
        h.server.dispatchCommand(friend, "게임 증강 연습");
        assertTrue(match.session().isInPlay(friend));
        h.server.dispatchCommand(admin, "게임 증강 다음단계");
        melee(friend, admin, 4);
        assertEquals(20, friend.getHealth(), 1.0E-6, "PvP off in practice by default, even in the fight");
        assertTrue(h.server.dispatchCommand(admin, "게임 증강 대전 켜기"));
        melee(friend, admin, 4);
        assertEquals(16, friend.getHealth(), 1.0E-6);
    }

    @Test
    void aPractisingPlayerWhoDiesComesBackWithTheirAugments() {
        AugmentMatch match = practice();
        h.server.dispatchCommand(admin, "게임 증강 받기 iron_body");
        admin.setHealth(0);
        h.ticks(5);
        assertTrue(match.session().isAlive(admin), "not eliminated in practice");
        assertTrue(match.owns(admin.getUniqueId(), info("iron_body")));
    }

    @Test
    void theMessagesFileIsValidYaml() throws Exception {
        org.bukkit.configuration.file.YamlConfiguration yaml = new org.bukkit.configuration.file.YamlConfiguration();
        try (java.io.Reader reader = new java.io.InputStreamReader(
                AugmentGame.class.getResourceAsStream("/" + AugmentGame.MESSAGES_FILE),
                java.nio.charset.StandardCharsets.UTF_8)) {
            yaml.load(reader); // throws on a YAML error (a bad escape loses the whole file)
        }
        assertTrue(yaml.getString("practice.usage-give", "").contains("<"));
        assertTrue(yaml.isString("settings.section.match.name"));
    }

    @Test
    void practiceCommandsAreRefusedInARealMatch() {
        h.join("Other");
        h.startFarming();
        assertFalse(h.server.dispatchCommand(admin, "게임 증강 받기 iron_body")
                && h.match().owns(admin.getUniqueId(), info("iron_body")));
        assertTrue(h.match().augments(admin.getUniqueId()).isEmpty());
    }
}
