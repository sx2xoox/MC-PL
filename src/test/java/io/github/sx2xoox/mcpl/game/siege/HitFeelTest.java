package io.github.sx2xoox.mcpl.game.siege;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import io.github.sx2xoox.mcpl.core.game.GameStartException;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureKind;
import io.github.sx2xoox.mcpl.game.siege.creature.HitStrength;
import io.github.sx2xoox.mcpl.game.siege.tower.Tower;
import io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttack;
import io.github.sx2xoox.mcpl.game.superpower.support.MatchHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.Scenario;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.sound.AudioExperience;

/**
 * The hit feel (타격감): the attacker's own confirmation is theirs alone, hit sounds are spaced per target (never one
 * target's sounds swallowing another's), how hard a hit reads, where its sparks land, and the gold pop of a last hit is
 * the winner's alone and goes away again.
 */
class HitFeelTest {

    private MatchHarness h;
    private SiegeGame siege;

    @BeforeEach
    void setUp() {
        h = MatchHarness.flat();
        h.registerDummy();
        h.registerTestAbilities();
        h.settings().set("ultimate.per-second", 0);
        siege = (SiegeGame) h.hub.games().game(SiegeGame.ID);
        siege.sitesOverride(new FallbackLaneSites((int) FlatWorld.SURFACE));
        ConfigurationSection config = siege.config();
        config.set(SiegeSettings.ABILITY_POOL, List.of("dummy"));
        config.set(SiegeSettings.ROUND_PREP_SECONDS, 1);
        config.set(SiegeSettings.SPAWN_ZONE_DAMAGE, 0);
        config.set(SiegeSettings.ATTACK_DAMAGE, 0);
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
                2000, "the fight to start");
        return siege.currentMatch();
    }

    private static Player member(SiegeMatch match, int team) {
        Iterator<UUID> members = match.teams().get(team).members().iterator();
        return match.session().hub().plugin().getServer().getPlayer(members.next());
    }

    private static boolean heard(Player player, String sound) {
        return ((TestPlayer) player).getHeardSounds().stream().map(AudioExperience::getSound)
                .anyMatch(name -> name.endsWith(sound));
    }

    private static void quiet(Player... players) {
        for (Player player : players) {
            ((TestPlayer) player).getHeardSounds().clear();
        }
    }

    @Test
    void theAttackerAloneHearsTheirHitLand() {
        SiegeMatch match = start();
        Player a = member(match, 0);
        Player b = member(match, 1);
        LaneSites.Side side = match.side(a.getUniqueId());
        Tower outer = SiegeMatchTest.structure(match, side == LaneSites.Side.BLUE ? LaneSites.Side.RED
                : LaneSites.Side.BLUE, Tower.Kind.OUTER, LaneSites.Lane.TOP);
        a.teleport(outer.base().add(3, 0, 0));
        b.teleport(outer.base().add(4, 0, 0));
        quiet(a, b);
        match.hitTower(outer, a, 10);
        assertTrue(heard(a, "entity.player.attack.strong"), "the attacker's own 딱");
        assertFalse(heard(b, "entity.player.attack.strong"), "nobody else hears it, even standing there");
        assertTrue(heard(b, "block.bamboo_wood.hit"), "the tower's knock is everyone's");

        quiet(a);
        match.hitFeel().confirm(a, HitStrength.NORMAL, false);
        assertFalse(heard(a, "entity.player.attack.strong"), "one per gap: a sweep through many is one 딱");
        match.hitFeel().confirm(a, HitStrength.NORMAL, true);
        assertTrue(heard(a, "block.note_block.pling"), "a kill rings anyway");
        quiet(a);
        match.hitFeel().confirm(a, HitStrength.LIGHT, false);
        h.ticks(match.hitFeel().settings().confirmGapTicks());
        match.hitFeel().confirm(a, HitStrength.LIGHT, false);
        assertFalse(heard(a, "entity.player.attack.strong"), "a burn's tick is no hit to confirm");
    }

    @Test
    void hitSoundsAreSpacedPerTargetNeverAcrossTargets() {
        SiegeMatch match = start();
        Player a = member(match, 0);
        HitFeel feel = match.hitFeel();
        Location here = a.getLocation();
        Sheep jelly = here.getWorld().spawn(here.clone().add(2, 0, 0), Sheep.class);
        Sheep other = here.getWorld().spawn(here.clone().add(-2, 0, 0), Sheep.class);
        quiet(a);
        feel.creature(jelly, here, null, CreatureKind.JELLY, HitStrength.NORMAL, false, 1);
        assertTrue(heard(a, "entity.slime.squish_small"), "the jelly's own squish");
        assertTrue(heard(a, "block.note_block.basedrum"), "a thump under it");
        assertTrue(heard(a, "block.note_block.hat"), "a short bright tick on top");
        quiet(a);
        feel.creature(jelly, here, null, CreatureKind.JELLY, HitStrength.NORMAL, false, 1);
        assertFalse(heard(a, "entity.slime.squish_small"), "the same target within the gap: once");
        feel.creature(other, here, null, CreatureKind.TEDDY, HitStrength.NORMAL, false, 1);
        assertTrue(heard(a, "block.wool.break"), "another target is never swallowed");
        assertTrue(heard(a, "entity.player.attack.knockback"), "a heavy toy thumps deep");
        quiet(a);
        h.ticks(feel.settings().soundGapTicks());
        feel.creature(jelly, here, null, CreatureKind.JELLY, HitStrength.NORMAL, false, 1);
        assertTrue(heard(a, "entity.slime.squish_small"), "after the gap it sounds again");
        quiet(a);
        feel.creature(jelly, here, null, CreatureKind.JELLY, HitStrength.NORMAL, true, 1);
        assertTrue(heard(a, "entity.slime.squish_small"), "a killing blow always sounds");
        jelly.remove();
        other.remove();
    }

    @Test
    void howHardAHitReads() {
        assertEquals(HitStrength.LIGHT, HitFeel.strength(DamageCause.FIRE_TICK, 5, 20, BasicAttack.Accent.NONE,
                false, 0.3), "burns");
        assertEquals(HitStrength.LIGHT, HitFeel.strength(DamageCause.ENTITY_ATTACK, 0.5, 20,
                BasicAttack.Accent.NONE, false, 0.3), "tiny ticks");
        assertEquals(HitStrength.NORMAL, HitFeel.strength(DamageCause.ENTITY_ATTACK, 4, 20, BasicAttack.Accent.NONE,
                false, 0.3));
        assertEquals(HitStrength.STRONG, HitFeel.strength(DamageCause.ENTITY_ATTACK, 4, 20,
                BasicAttack.Accent.CRITICAL, false, 0.3), "a critical hit");
        assertEquals(HitStrength.STRONG, HitFeel.strength(DamageCause.ENTITY_ATTACK, 4, 20,
                BasicAttack.Accent.FINISH, false, 0.3), "a finisher");
        assertEquals(HitStrength.STRONG, HitFeel.strength(DamageCause.MAGIC, 7, 20, BasicAttack.Accent.NONE,
                true, 0.3), "a big ability blow");
        assertEquals(HitStrength.NORMAL, HitFeel.strength(DamageCause.ENTITY_ATTACK, 7, 20,
                BasicAttack.Accent.NONE, false, 0.3), "a big plain hit is still plain");
    }

    @Test
    void sparksLandOnTheSideFacingTheBlow() {
        SiegeMatch match = start();
        Location here = member(match, 0).getLocation();
        Sheep body = here.getWorld().spawn(here.clone().add(10, 0, 0), Sheep.class);
        Location from = body.getLocation().add(5, 0, 0);
        Location at = HitFeel.impactPoint(body, from, 2);
        assertTrue(at.getX() - body.getX() > body.getWidth() * 0.4, "on its near side: " + at);
        assertTrue(at.getY() > body.getY() + 0.4 && at.getY() < body.getY() + 1.8, "about chest high: " + at);
        assertEquals(body.getX(), HitFeel.impactPoint(body, null, 2).getX(), 1e-9, "the middle when unknown");
        body.remove();
    }

    @Test
    void goldPopsForTheWinnerOnlyAndGoesAway() {
        SiegeMatch match = start();
        Player a = member(match, 0);
        Player b = member(match, 1);
        b.teleport(a.getLocation().add(2, 0, 0));
        Location at = a.getLocation().add(3, 1, 0);
        match.hitFeel().gold(a, at, 15);
        h.ticks(DamageNumbers.AGGREGATE_TICKS + 1);
        List<TextDisplay> pops = h.flatWorld().getEntitiesByClass(TextDisplay.class).stream()
                .filter(d -> Scenario.plain(d.text()).equals("+15 골드")).toList();
        assertEquals(1, pops.size(), "one +15 골드");
        assertTrue(a.canSee(pops.getFirst()), "the winner sees it");
        assertFalse(b.canSee(pops.getFirst()), "nobody else does");
        h.ticks(DamageNumbers.LIFETIME_TICKS + 1);
        assertFalse(pops.getFirst().isValid(), "and it goes away");
    }
}
