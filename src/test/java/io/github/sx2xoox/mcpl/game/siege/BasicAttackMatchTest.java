package io.github.sx2xoox.mcpl.game.siege;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.github.sx2xoox.mcpl.core.game.GameStartException;
import io.github.sx2xoox.mcpl.core.game.MatchTeam;
import io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttack;
import io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttackSettings;
import io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttackSettings.Feel;
import io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttacks;
import io.github.sx2xoox.mcpl.game.superpower.runtime.Participant;
import io.github.sx2xoox.mcpl.game.superpower.runtime.SuperpowerMatch;
import io.github.sx2xoox.mcpl.game.superpower.support.DummyAbility;
import io.github.sx2xoox.mcpl.game.superpower.support.MatchHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import io.papermc.paper.event.player.PlayerArmSwingEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Basic attacks (평타) in 공성전: a left-click with the ability weapon fires the ability's own attack; every shape
 * hits enemies only, the cadence holds, vanilla sword damage is never added on top, damage grows with levels and the
 * sword item, and 초능력전쟁 is untouched.
 */
class BasicAttackMatchTest {

    private static final String PREFIX = SiegeMatch.BASIC_ATTACKS + "." + BasicAttackSettings.ATTACKS + ".dummy.";
    private static final String FEEL = SiegeMatch.BASIC_ATTACKS + ".";

    private MatchHarness h;
    private SiegeGame siege;
    private ConfigurationSection config;

    @BeforeEach
    void setUp() {
        h = MatchHarness.flat();
        // two neutral abilities: a pool smaller than a team (two players) is filled with random real abilities, whose
        // passives would hurt the attacker or dodge the hit at random
        h.registerDummies();
        h.settings().set("ultimate.per-second", 0);
        siege = (SiegeGame) h.hub.games().game(SiegeGame.ID);
        siege.sitesOverride(new FallbackLaneSites((int) FlatWorld.SURFACE));
        config = siege.config();
        config.set(SiegeSettings.ABILITY_POOL, List.of(DummyAbility.INFO.id(), DummyAbility.TWIN_INFO.id()));
        config.set(SiegeSettings.ROUND_PREP_SECONDS, 1);
        config.set(SiegeSettings.ATTACK_DAMAGE, 0);
        config.set(SiegeSettings.SPAWN_ZONE_DAMAGE, 0);
        config.set(SiegeSettings.PROTECTION_SECONDS, 0);
        config.set(SiegeProgressSettings.XP_PASSIVE, 0);
        // exact damage below: no random critical hits unless a test turns them on
        config.set(FEEL + BasicAttackSettings.CRITICAL + "." + BasicAttackSettings.CRIT_CHANCE, 0);
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
                2000, "the fight");
        return siege.currentMatch();
    }

    /**
     * Attacker (the one holding {@code dummy}, whose basic attack the tests configure), enemy and (with 3 players) an
     * ally of the attacker.
     */
    private record Cast(Player attacker, Player enemy, Player ally) {
    }

    private Cast cast(SiegeMatch match) {
        List<Player> big = members(match.teams().get(0));
        List<Player> small = members(match.teams().get(1));
        if (big.size() < small.size() || big.size() == small.size() && dummyOf(match, big) == null) {
            List<Player> swap = big;
            big = small;
            small = swap;
        }
        Player attacker = dummyOf(match, big);
        assertNotNull(attacker, "the bigger team holds the configured dummy");
        Player ally = null;
        for (Player player : big) {
            if (!player.equals(attacker)) {
                ally = player;
            }
        }
        return new Cast(attacker, small.get(0), ally);
    }

    private static @Nullable Player dummyOf(SiegeMatch match, List<Player> team) {
        for (Player player : team) {
            Participant participant = match.abilities().participant(player.getUniqueId());
            if (participant != null && DummyAbility.INFO.id().equals(participant.abilityId())) {
                return player;
            }
        }
        return null;
    }

    private List<Player> members(MatchTeam team) {
        List<Player> out = new ArrayList<>();
        for (UUID id : team.members()) {
            out.add(h.server.getPlayer(id));
        }
        return out;
    }

    /** Puts {@code player} {@code dx} blocks east and {@code dz} south of the test spot, looking east. */
    private void place(SiegeMatch match, Player player, double dx, double dz) {
        Location center = match.session().arena().center();
        Location at = new Location(center.getWorld(), center.getX() + dx, FlatWorld.SURFACE, center.getZ() + 40 + dz,
                -90f, 0f);
        player.teleport(at);
        player.setVelocity(new org.bukkit.util.Vector());
        player.setFallDistance(0);
        player.setFireTicks(0);
        player.setHealth(max(player));
        player.getInventory().setHeldItemSlot(0);
    }

    private static double max(Player player) {
        return player.getAttribute(Attribute.MAX_HEALTH).getValue();
    }

    private void swing(Player player) {
        h.server.getPluginManager().callEvent(new PlayerArmSwingEvent(player, EquipmentSlot.HAND));
    }

    // =================================================================== shapes

    @ParameterizedTest
    @EnumSource(BasicAttack.Shape.class)
    void everyShapeHitsAnEnemyButNeverAnAllyOrItself(BasicAttack.Shape shape) {
        config.set(PREFIX + BasicAttackSettings.SHAPE, shape.key());
        config.set(PREFIX + BasicAttackSettings.RANGE, 6);
        config.set(PREFIX + BasicAttackSettings.HITS, 2);
        config.set(PREFIX + BasicAttackSettings.INTERVAL, 2);
        SiegeMatch match = start(3);
        Cast cast = cast(match);
        assertNotNull(cast.ally(), "three players: two on one team");
        assertTrue(match.abilities().hasBasicAttacks(), "공성전 turns basic attacks on");
        place(match, cast.attacker(), 0, 0);
        place(match, cast.enemy(), 2.5, 0);
        place(match, cast.ally(), 1.6, 1.3);

        java.util.List<String> hurt = new java.util.ArrayList<>();
        h.server.getPluginManager().registerEvent(org.bukkit.event.entity.EntityDamageEvent.class,
                new org.bukkit.event.Listener() { }, org.bukkit.event.EventPriority.MONITOR, (l, e) -> {
                    var d = (org.bukkit.event.entity.EntityDamageEvent) e;
                    if (d.getEntity().equals(cast.attacker()) && !d.isCancelled()
                            && cast.attacker().equals(d.getDamageSource().getCausingEntity())) {
                        hurt.add(d.getCause() + " " + d.getDamageSource().getDamageType().getKey() + " "
                                + d.getDamageSource().getCausingEntity() + " " + d.getFinalDamage());
                    }
                }, h.hub.plugin());
        swing(cast.attacker());
        h.ticks(30);
        assertEquals(List.of(), hurt, "the attacker never hurts itself");

        assertTrue(cast.enemy().getHealth() < max(cast.enemy()), shape + " hits the enemy");
        assertEquals(max(cast.ally()), cast.ally().getHealth(), 1.0E-9, shape + " never hurts an ally");
        assertEquals(max(cast.attacker()), cast.attacker().getHealth(), 1.0E-9, shape + " never hurts its owner");
    }

    @Test
    void everyPoolAbilitysBasicAttackLandsOnAnEnemyInFront() {
        SiegeMatch match = start(3);
        Cast cast = cast(match);
        SuperpowerMatch abilities = match.abilities();
        List<String> missed = new ArrayList<>();
        for (BasicAttack attack : BasicAttacks.defaults().values()) {
            assertTrue(abilities.replaceAbility(cast.attacker(), attack.id()), attack.id());
            place(match, cast.attacker(), 0, 0);
            place(match, cast.enemy(), 2.5, 0);
            place(match, cast.ally(), -2, 0.5);
            h.ticks(1);
            place(match, cast.attacker(), 0, 0);
            place(match, cast.enemy(), 2.5, 0);
            assertTrue(abilities.basicAttack(cast.attacker()), attack.id() + " fires");
            h.ticks(30);
            if (!(cast.enemy().getHealth() < max(cast.enemy()))) {
                missed.add(attack.id());
            }
            assertEquals(max(cast.ally()), cast.ally().getHealth(), 1.0E-9, attack.id() + " spares the ally");
            cast.attacker().setHealth(max(cast.attacker()));
        }
        assertEquals(List.of(), missed, "basic attacks that missed an enemy right in front");
    }

    // =================================================================== cadence & vanilla hits

    @Test
    void cadenceIsEnforcedAndAnEarlyClickIsKept() {
        SiegeMatch match = start(2);
        Cast cast = cast(match);
        place(match, cast.attacker(), 0, 0);
        place(match, cast.enemy(), 2.5, 0);
        SuperpowerMatch abilities = match.abilities();
        double hit = BasicAttacks.FALLBACK.damage();
        int cooldown = BasicAttacks.FALLBACK.cooldown();

        assertTrue(abilities.basicAttack(cast.attacker()));
        assertEquals(max(cast.enemy()) - hit, cast.enemy().getHealth(), 1.0E-6, "one plain hit");
        assertFalse(abilities.basicAttack(cast.attacker()), "not again in the same tick");
        swing(cast.attacker());
        h.ticks(cooldown - 6);
        assertFalse(abilities.basicAttack(cast.attacker()), "too early: ignored");
        h.ticks(4);
        assertEquals(max(cast.enemy()) - hit, cast.enemy().getHealth(), 1.0E-6, "nothing more yet");
        assertFalse(abilities.basicAttack(cast.attacker()), "2 ticks early: kept for later");
        h.ticks(3);
        assertEquals(max(cast.enemy()) - 2 * hit, cast.enemy().getHealth(), 1.0E-6, "the kept click fired");
    }

    @Test
    void vanillaSwordDamageIsNeverAddedOnTop() {
        SiegeMatch match = start(2);
        Cast cast = cast(match);
        place(match, cast.attacker(), 0, 0);
        place(match, cast.enemy(), 2.5, 0);

        PrePlayerAttackEntityEvent attack = new PrePlayerAttackEntityEvent(cast.attacker(), cast.enemy(), true);
        h.server.getPluginManager().callEvent(attack);
        assertTrue(attack.isCancelled(), "the vanilla hit of the weapon never starts");
        cast.enemy().damage(6, DamageSource.builder(DamageType.PLAYER_ATTACK)
                .withCausingEntity(cast.attacker()).withDirectEntity(cast.attacker()).build());
        assertEquals(max(cast.enemy()), cast.enemy().getHealth(), 1.0E-9, "a forwarded sword hit is cancelled");

        swing(cast.attacker());
        assertEquals(max(cast.enemy()) - BasicAttacks.FALLBACK.damage(), cast.enemy().getHealth(), 1.0E-6,
                "only the basic attack lands");

        cast.attacker().getInventory().setHeldItemSlot(5);
        h.ticks(BasicAttacks.FALLBACK.cooldown() + 1);
        double before = cast.enemy().getHealth();
        swing(cast.attacker());
        assertEquals(before, cast.enemy().getHealth(), 1.0E-9, "no basic attack without the weapon in hand");
    }

    @Test
    void damageGrowsWithLevelsAndAttackDamageItems() {
        SiegeMatch match = start(2);
        Cast cast = cast(match);
        Player attacker = cast.attacker();
        double base = BasicAttacks.FALLBACK.damage();
        int cooldown = BasicAttacks.FALLBACK.cooldown();

        match.progress().grant(attacker.getUniqueId(), 1600);
        assertEquals(9, match.level(attacker.getUniqueId()));
        place(match, attacker, 0, 0);
        place(match, cast.enemy(), 2.5, 0);
        assertTrue(match.abilities().basicAttack(attacker));
        assertEquals(base * 1.6, max(cast.enemy()) - cast.enemy().getHealth(), 1.0E-6, "level 9: ×1.6");

        AttributeInstance damage = attacker.getAttribute(Attribute.ATTACK_DAMAGE);
        assertNotNull(damage);
        damage.addModifier(new AttributeModifier(new NamespacedKey("test", "sword"), 3,
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
        damage.addModifier(new AttributeModifier(NamespacedKey.minecraft("base_attack_damage"), 5,
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        h.ticks(cooldown);
        place(match, cast.enemy(), 2.5, 0);
        assertTrue(match.abilities().basicAttack(attacker));
        double expected = base * 1.6 * (1 + 3 / BasicAttackSettings.DEFAULT_BONUS_REFERENCE);
        assertEquals(expected, max(cast.enemy()) - cast.enemy().getHealth(), 1.0E-6,
                "a +3 sword adds +3 to a 5-damage hit (proportionally), the weapon's own damage is not a bonus");
    }

    @Test
    void sideEffectsSlowAndBurn() {
        config.set(PREFIX + BasicAttackSettings.EFFECT, "slow");
        config.set(PREFIX + BasicAttackSettings.EFFECT_POWER, 0.15);
        config.set(PREFIX + BasicAttackSettings.EFFECT_SECONDS, 1);
        SiegeMatch match = start(2);
        Cast cast = cast(match);
        place(match, cast.attacker(), 0, 0);
        place(match, cast.enemy(), 2.5, 0);
        AttributeInstance speed = cast.enemy().getAttribute(Attribute.MOVEMENT_SPEED);
        double normal = speed.getValue();

        assertTrue(match.abilities().basicAttack(cast.attacker()));
        assertEquals(normal * 0.85, speed.getValue(), 1.0E-6, "slowed by 15%");
        h.ticks(21);
        assertEquals(normal, speed.getValue(), 1.0E-6, "for one second");
    }

    @Test
    void damageOverTimeAddsItsTotal() {
        config.set(PREFIX + BasicAttackSettings.EFFECT, "dot");
        config.set(PREFIX + BasicAttackSettings.EFFECT_POWER, 2);
        config.set(PREFIX + BasicAttackSettings.EFFECT_SECONDS, 2);
        SiegeMatch match = start(2);
        Cast cast = cast(match);
        place(match, cast.attacker(), 0, 0);
        place(match, cast.enemy(), 2.5, 0);

        assertTrue(match.abilities().basicAttack(cast.attacker()));
        h.ticks(60);
        assertEquals(BasicAttacks.FALLBACK.damage() + 2, max(cast.enemy()) - cast.enemy().getHealth(), 1.0E-6);
    }

    @Test
    void canBeTurnedOff() {
        config.set(SiegeMatch.BASIC_ATTACKS + "." + BasicAttackSettings.ENABLED, false);
        SiegeMatch match = start(2);
        assertFalse(match.abilities().hasBasicAttacks());
    }

    // =================================================================== combo, critical hits, displays

    @Test
    void theThirdAttackInARowIsAFinisherAndAPauseStartsOver() {
        SiegeMatch match = start(2);
        Cast cast = cast(match);
        Feel feel = Feel.DEFAULT;
        double hit = BasicAttacks.FALLBACK.damage();
        int cooldown = BasicAttacks.FALLBACK.cooldown();
        SuperpowerMatch abilities = match.abilities();
        double[] dealt = new double[4];
        for (int i = 0; i < 4; i++) {
            place(match, cast.attacker(), 0, 0);
            place(match, cast.enemy(), 2.5, 0);
            assertTrue(abilities.basicAttack(cast.attacker()), "attack " + i);
            dealt[i] = max(cast.enemy()) - cast.enemy().getHealth();
            h.ticks(cooldown);
        }
        assertEquals(hit, dealt[0], 1.0E-6);
        assertEquals(hit, dealt[1], 1.0E-6);
        assertEquals(hit * feel.finisherDamage(), dealt[2], 1.0E-6, "the third is the finisher");
        assertEquals(hit, dealt[3], 1.0E-6, "then a new combo");
        assertEquals(1, abilities.basicAttackComboStep(cast.attacker()));

        h.ticks((int) feel.comboResetTicks() + 1);
        assertEquals(0, abilities.basicAttackComboStep(cast.attacker()), "a pause starts the combo over");
        place(match, cast.attacker(), 0, 0);
        place(match, cast.enemy(), 2.5, 0);
        assertTrue(abilities.basicAttack(cast.attacker()));
        h.ticks(cooldown);
        place(match, cast.enemy(), 2.5, 0);
        assertTrue(abilities.basicAttack(cast.attacker()));
        assertEquals(hit, max(cast.enemy()) - cast.enemy().getHealth(), 1.0E-6,
                "after the pause the second attack is no finisher");
    }

    @Test
    void comboAndCriticalNumbersComeFromTheConfig() {
        config.set(FEEL + BasicAttackSettings.COMBO + "." + BasicAttackSettings.COMBO_LENGTH, 2);
        config.set(FEEL + BasicAttackSettings.COMBO + "." + BasicAttackSettings.FINISHER_DAMAGE, 2.0);
        config.set(FEEL + BasicAttackSettings.CRITICAL + "." + BasicAttackSettings.CRIT_CHANCE, 1.0);
        config.set(FEEL + BasicAttackSettings.CRITICAL + "." + BasicAttackSettings.CRIT_DAMAGE, 1.5);
        SiegeMatch match = start(2);
        Cast cast = cast(match);
        double hit = BasicAttacks.FALLBACK.damage();
        place(match, cast.attacker(), 0, 0);
        place(match, cast.enemy(), 2.5, 0);
        assertTrue(match.abilities().basicAttack(cast.attacker()));
        assertEquals(hit * 1.5, max(cast.enemy()) - cast.enemy().getHealth(), 1.0E-6, "always critical: ×1.5");
        h.ticks(BasicAttacks.FALLBACK.cooldown());
        place(match, cast.enemy(), 2.5, 0);
        assertTrue(match.abilities().basicAttack(cast.attacker()));
        assertEquals(hit * 1.5 * 2.0, max(cast.enemy()) - cast.enemy().getHealth(), 1.0E-6,
                "a two-hit combo: the second is the finisher (×2), critical too");
    }

    @Test
    void criticalHitsComeAtTheirChance() {
        config.set(FEEL + BasicAttackSettings.CRITICAL + "." + BasicAttackSettings.CRIT_CHANCE, 0.25);
        config.set(FEEL + BasicAttackSettings.COMBO + "." + BasicAttackSettings.COMBO_LENGTH, 1);
        SiegeMatch match = start(2);
        Cast cast = cast(match);
        double hit = BasicAttacks.FALLBACK.damage();
        int crits = 0;
        int attacks = 200;
        for (int i = 0; i < attacks; i++) {
            place(match, cast.attacker(), 0, 0);
            place(match, cast.enemy(), 2.5, 0);
            assertTrue(match.abilities().basicAttack(cast.attacker()));
            double dealt = max(cast.enemy()) - cast.enemy().getHealth();
            if (Math.abs(dealt - hit * Feel.DEFAULT.critDamage()) < 1.0E-6) {
                crits++;
            } else {
                assertEquals(hit, dealt, 1.0E-6);
            }
            h.ticks(BasicAttacks.FALLBACK.cooldown());
        }
        assertTrue(crits > attacks * 0.12 && crits < attacks * 0.38, "about a quarter critical, was " + crits);
    }

    @Test
    void displaysAreCappedAndGoneSoonAfter() {
        SiegeMatch match = start(2);
        Cast cast = cast(match);
        SuperpowerMatch abilities = match.abilities();
        int cap = Feel.DEFAULT.maxPerAttack();
        for (BasicAttack attack : BasicAttacks.defaults().values()) {
            assertTrue(abilities.replaceAbility(cast.attacker(), attack.id()), attack.id());
            place(match, cast.attacker(), 0, 0);
            place(match, cast.enemy(), 2.5, 0);
            h.ticks(1);
            place(match, cast.attacker(), 0, 0);
            place(match, cast.enemy(), 2.5, 0);
            assertTrue(abilities.basicAttack(cast.attacker()), attack.id());
            int most = 0;
            for (int t = 0; t < 40; t++) {
                most = Math.max(most, fxDisplays(match));
                assertTrue(fxDisplays(match) <= Feel.DEFAULT.maxPerPlayer(), attack.id());
                h.ticks(1);
            }
            assertTrue(most > 0, attack.id() + " shows displays");
            assertTrue(most <= cap, attack.id() + ": at most " + cap + " displays per attack, was " + most);
            h.ticks(30);
            assertEquals(0, fxDisplays(match), attack.id() + ": every display is gone after the attack");
            assertEquals(0, abilities.basicAttackDisplays(), attack.id());
            cast.attacker().setHealth(max(cast.attacker()));
            cast.enemy().setHealth(max(cast.enemy()));
        }
    }

    @Test
    void theDisplayBudgetIsConfigurable() {
        config.set(FEEL + BasicAttackSettings.EFFECTS + "." + BasicAttackSettings.MAX_PER_ATTACK, 5);
        SiegeMatch match = start(2);
        Cast cast = cast(match);
        place(match, cast.attacker(), 0, 0);
        place(match, cast.enemy(), 2.5, 0);
        assertTrue(match.abilities().basicAttack(cast.attacker()));
        int most = 0;
        for (int t = 0; t < 20; t++) {
            most = Math.max(most, fxDisplays(match));
            h.ticks(1);
        }
        assertTrue(most > 0 && most <= 5, "at most 5, was " + most);
    }

    @Test
    void displaysCanBeTurnedOff() {
        config.set(FEEL + BasicAttackSettings.EFFECTS + "." + BasicAttackSettings.DISPLAYS, false);
        SiegeMatch match = start(2);
        Cast cast = cast(match);
        place(match, cast.attacker(), 0, 0);
        place(match, cast.enemy(), 2.5, 0);
        assertTrue(match.abilities().basicAttack(cast.attacker()));
        h.ticks(2);
        assertEquals(0, fxDisplays(match), "particles only");
        assertTrue(cast.enemy().getHealth() < max(cast.enemy()), "the attack still hits");
    }

    @Test
    void displaysGoWhenTheAttackerDies() {
        SiegeMatch match = start(2);
        Cast cast = cast(match);
        SuperpowerMatch abilities = match.abilities();
        assertTrue(abilities.replaceAbility(cast.attacker(), "ninja"));
        place(match, cast.attacker(), 0, 0);
        place(match, cast.enemy(), 12, 0);
        h.ticks(1);
        place(match, cast.attacker(), 0, 0);
        assertTrue(abilities.basicAttack(cast.attacker()));
        h.ticks(2);
        assertTrue(fxDisplays(match) > 0, "shuriken in flight");
        cast.attacker().setHealth(0);
        h.ticks(2);
        assertEquals(0, fxDisplays(match), "the dead attacker's displays are gone");
    }

    @Test
    void displaysGoWhenTheMatchEnds() {
        SiegeMatch match = start(2);
        Cast cast = cast(match);
        SuperpowerMatch abilities = match.abilities();
        assertTrue(abilities.replaceAbility(cast.attacker(), "ninja"));
        place(match, cast.attacker(), 0, 0);
        place(match, cast.enemy(), 12, 0);
        h.ticks(1);
        place(match, cast.attacker(), 0, 0);
        assertTrue(abilities.basicAttack(cast.attacker()));
        h.ticks(1);
        assertTrue(fxDisplays(match) > 0, "shuriken in flight");
        org.bukkit.World world = match.session().arena().center().getWorld();
        h.hub.games().stop(net.kyori.adventure.text.Component.text("test"));
        h.ticks(1);
        assertEquals(0, fxDisplays(world), "the match end removes every display");
    }

    @Test
    void aFlyingArrowPointsWhereItFliesAsTheClientDrawsItems() {
        SiegeMatch match = start(2);
        Cast cast = cast(match);
        SuperpowerMatch abilities = match.abilities();
        assertTrue(abilities.replaceAbility(cast.attacker(), "archer"));
        place(match, cast.attacker(), 0, 0);
        place(match, cast.enemy(), 20, 6);
        h.ticks(1);
        place(match, cast.attacker(), 0, 0);
        assertTrue(abilities.basicAttack(cast.attacker()));
        h.ticks(1);
        org.bukkit.entity.ItemDisplay arrow = null;
        for (org.bukkit.entity.Entity entity : match.session().arena().center().getWorld().getEntities()) {
            if (entity instanceof org.bukkit.entity.ItemDisplay item && item.isValid()
                    && item.getScoreboardTags().contains("mcpl_basic_fx")) {
                arrow = item;
            }
        }
        assertNotNull(arrow, "the arrow flies as an item display");
        org.bukkit.util.Transformation t = arrow.getTransformation();
        org.joml.Vector3f origin = io.github.sx2xoox.mcpl.core.pack.ItemDisplayModels.drawn(0, t,
                new org.joml.Vector3f());
        org.joml.Vector3f tip = io.github.sx2xoox.mcpl.core.pack.ItemDisplayModels.drawn(0, t,
                new org.joml.Vector3f(0.5f, 0.5f, 0)).sub(origin);
        assertTrue(tip.x > 0.5f * tip.length(), "the sprite's tip points east, where the attacker looks: " + tip);
    }

    private static int fxDisplays(SiegeMatch match) {
        return fxDisplays(match.session().arena().center().getWorld());
    }

    private static int fxDisplays(org.bukkit.World world) {
        int count = 0;
        for (org.bukkit.entity.Entity entity : world.getEntities()) {
            if (entity instanceof org.bukkit.entity.Display && entity.isValid()
                    && entity.getScoreboardTags().contains("mcpl_basic_fx")) {
                count++;
            }
        }
        return count;
    }

    // =================================================================== 초능력전쟁

    @Test
    void superpowerWarIsUnchanged() {
        List<TestPlayer> players = h.join(2);
        SuperpowerMatch match = h.startFight();
        for (TestPlayer player : players) {
            match.replaceAbility(player, "dummy");
        }
        h.healAll(players);
        assertFalse(match.hasBasicAttacks(), "no basic attacks outside a host that turns them on");
        Player attacker = players.get(0);
        Player victim = players.get(1);
        attacker.teleport(victim.getLocation().clone().add(-2.5, 0, 0).setDirection(new org.bukkit.util.Vector(1, 0, 0)));
        swing(attacker);
        h.ticks(5);
        assertEquals(max(victim), victim.getHealth(), 1.0E-9, "a swing is just a swing");
        assertFalse(match.basicAttack(attacker));
        victim.damage(3, DamageSource.builder(DamageType.PLAYER_ATTACK)
                .withCausingEntity(attacker).withDirectEntity(attacker).build());
        assertEquals(max(victim) - 3, victim.getHealth(), 1.0E-9, "sword hits land as before");
    }
}
