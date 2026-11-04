package io.github.sx2xoox.mcpl.game.siege.lanes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites.ObjectiveKind;
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureModels;
import io.github.sx2xoox.mcpl.game.siege.creature.Creatures;
import io.github.sx2xoox.mcpl.game.siege.creature.ToyShots;
import io.github.sx2xoox.mcpl.game.siege.jungle.JungleObjectives;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

/**
 * The jungle monsters' own skills (docs/siege-v2.md §4): every skill warns first (a mark on the ground, the monster
 * charging) and lands after; the rabbit now fights back; the dragon rages. And the creatures' own knockback.
 */
class CreatureSkillsTest extends LaneTestBase {

    @Override
    void configure(MemoryConfiguration config) {
        config.set("minions.enabled", false);
        config.set("gold.per-second", 0);
        for (ObjectiveKind kind : ObjectiveKind.values()) {
            config.set("jungle." + kind.name().toLowerCase() + ".first-seconds", 0.05);
        }
    }

    private JungleObjectives jungle() {
        return systems.jungle();
    }

    private JungleObjectives.Site site(LaneSites.@org.jspecify.annotations.Nullable Side home, ObjectiveKind kind) {
        JungleObjectives.Site site = jungle().site(home, kind);
        assertNotNull(site);
        return site;
    }

    private void startUp(int blue, int red) {
        start(blue, red);
        run(2);
    }

    /** Runs until {@code done} (at most {@code limit} ticks); true if it happened. */
    private boolean runUntil(int limit, BooleanSupplier done) {
        for (int i = 0; i < limit; i++) {
            if (done.getAsBoolean()) {
                return true;
            }
            run(1);
        }
        return done.getAsBoolean();
    }

    private static Location ahead(JungleObjectives.Site site, double x, double z) {
        return site.at().add(x, 0, z);
    }

    // ------------------------------------------------------------------ teddy

    @Test
    void theTeddyMarksItsPawSlamBeforeItLands() {
        config.set("jungle.teddy.shockwave.cooldown-seconds", 1000);
        startUp(1, 1);
        JungleObjectives.Site teddy = site(LaneSites.Side.BLUE, ObjectiveKind.TEDDY);
        Mob bear = teddy.mobs().getFirst();
        Player player = player(LaneSites.Side.BLUE, 0);
        player.teleport(ahead(teddy, 2, 0));
        melee(player, bear, 1);
        assertTrue(runUntil(10, () -> jungle().isCasting(bear)), "the slam starts");
        assertEquals(20, player.getHealth(), 1.0E-6, "a warning first: nothing has landed");
        assertTrue(skinEvents.contains("teddy charge"), "it rears up and shivers: " + skinEvents);
        assertTrue(arena.sounds.contains(Sound.BLOCK_WOOL_HIT), "its tell: " + arena.sounds);
        run(16);
        assertTrue(skinEvents.contains("teddy slam"), skinEvents.toString());
        assertEquals(15, player.getHealth(), 1.0E-6, "then the paws land: 5");
    }

    @Test
    void theTeddyShockwaveHitsEveryoneRoundItAfterAWarning() {
        config.set("jungle.teddy.shockwave.cooldown-seconds", 0.1);
        startUp(2, 1);
        JungleObjectives.Site teddy = site(LaneSites.Side.BLUE, ObjectiveKind.TEDDY);
        Mob bear = teddy.mobs().getFirst();
        Player front = player(LaneSites.Side.BLUE, 0);
        Player behind = player(LaneSites.Side.BLUE, 1);
        Player far = player(LaneSites.Side.RED, 0);
        front.teleport(ahead(teddy, 3, 0));
        behind.teleport(ahead(teddy, -3.5, 0));
        far.teleport(ahead(teddy, 0, 9));
        melee(front, bear, 1);
        assertTrue(runUntil(10, () -> jungle().isCasting(bear)));
        assertEquals(20, behind.getHealth(), 1.0E-6, "marked first");
        assertTrue(runUntil(30, () -> skinEvents.contains("teddy shockwave")), skinEvents.toString());
        assertEquals(20, front.getHealth(), 1.0E-6, "the jump: it lands with the stomp");
        run(CreatureModels.Attack.SHOCKWAVE.impact() + 1);
        assertTrue(front.getHealth() <= 14 + 1.0E-6, "the target: 6");
        assertTrue(behind.getHealth() <= 14 + 1.0E-6, "and whoever stands round it, behind too");
        assertEquals(20, far.getHealth(), 1.0E-6, "outside the circle: nothing");
        assertTrue(arena.sounds.contains(Sound.ENTITY_PLAYER_BIG_FALL), arena.sounds.toString());
    }

    // ------------------------------------------------------------------ jelly

    @Test
    void aJellySpitsGooThatLeavesASlowingPuddle() {
        config.set("jungle.jelly.goo.cooldown-seconds", 3);
        config.set("jungle.jelly.goo.effect-seconds", 3);
        startUp(1, 1);
        JungleObjectives.Site jelly = site(LaneSites.Side.BLUE, ObjectiveKind.JELLY);
        Player player = player(LaneSites.Side.BLUE, 0);
        player.teleport(ahead(jelly, 6, 0));
        melee(player, jelly.mobs().getFirst(), 1);
        assertTrue(runUntil(60, () -> jungle().shots().shots().stream()
                .anyMatch(shot -> shot.look() == ToyShots.Look.GOO)), "a blob of goo flies");
        ToyShots.Shot blob = jungle().shots().shots().getFirst();
        assertNotNull(blob.landing());
        assertTrue(Creatures.flatDistanceSquared(blob.landing(), player.getLocation()) < 0.01,
                "aimed where the target stood");
        assertTrue(skinEvents.contains("jelly goop"), skinEvents.toString());
        assertTrue(runUntil(40, () -> jungle().puddles() > 0), "it lands as a puddle");
        assertTrue(runUntil(6, () -> player.hasPotionEffect(PotionEffectType.SLOWNESS)), "sticky: slowed");
        assertTrue(count(BlockDisplay.class) >= 2, "the puddle is drawn on the ground");
        systems.endRound();
        assertEquals(0, jungle().puddles());
        assertEquals(0, count(BlockDisplay.class), "nothing is left");
    }

    // ------------------------------------------------------------------ mice

    @Test
    void theMiceSurroundTheirTargetScatterAndRushBack() {
        config.set("jungle.mice.scatter.cooldown-seconds", 3);
        startUp(1, 1);
        JungleObjectives.Site mice = site(LaneSites.Side.BLUE, ObjectiveKind.MICE);
        Player player = player(LaneSites.Side.BLUE, 0);
        player.teleport(ahead(mice, 3, 0));
        melee(player, mice.mobs().getFirst(), 1);
        run(25);
        // round the target, each on its own side
        List<Location> spots = new ArrayList<>();
        for (Mob mouse : mice.mobs()) {
            spots.add(mouse.getLocation());
        }
        double spread = 0;
        for (Location a : spots) {
            for (Location b : spots) {
                spread = Math.max(spread, a.distance(b));
            }
        }
        assertTrue(spread > 1.5, "spread round the target, not in a heap: " + spread);
        assertTrue(player.getHealth() < 20, "nibbled");
        assertTrue(runUntil(60, () -> skinEvents.contains("mice squeak")), "they rear up squeaking");
        double[] farthest = {0};
        assertTrue(runUntil(40, () -> {
            double sum = 0;
            for (Mob mouse : mice.mobs()) {
                sum += Math.sqrt(Creatures.flatDistanceSquared(mouse.getLocation(), player.getLocation()));
            }
            farthest[0] = Math.max(farthest[0], sum / mice.mobs().size());
            return farthest[0] > 3.5;
        }), "and scatter: " + farthest[0]);
        assertTrue(runUntil(60, () -> mice.mobs().stream().allMatch(mouse ->
                Creatures.flatDistanceSquared(mouse.getLocation(), player.getLocation()) < 2.5 * 2.5)),
                "then rush back in");
    }

    // ------------------------------------------------------------------ rabbit

    @Test
    void theClockRabbitThrowsHandsThatSlowTime() {
        startUp(1, 1);
        JungleObjectives.Site rabbit = site(LaneSites.Side.BLUE, ObjectiveKind.RABBIT);
        Mob bunny = rabbit.mobs().getFirst();
        Player player = player(LaneSites.Side.BLUE, 0);
        player.teleport(ahead(rabbit, 6, 0));
        melee(player, bunny, 1);
        assertTrue(runUntil(20, () -> jungle().shots().shots().stream()
                .anyMatch(shot -> shot.look() == ToyShots.Look.CLOCK_HAND)), "a clock hand flies");
        assertEquals(20, player.getHealth(), 1.0E-6, "it hits when it gets there");
        assertTrue(skinEvents.contains("rabbit attack"), skinEvents.toString());
        assertTrue(runUntil(20, () -> player.getHealth() < 20), "then it hits");
        assertEquals(17, player.getHealth(), 1.0E-6, "3 a hand");
        assertTrue(player.hasPotionEffect(PotionEffectType.SLOWNESS), "시간 지연: slowed");
        assertTrue(arena.sounds.contains(Sound.BLOCK_NOTE_BLOCK_BELL), arena.sounds.toString());
    }

    @Test
    void theClockRabbitKeepsItsDistanceAndWindsAwayWhenHurt() {
        config.set("jungle.rabbit.blink.cooldown-seconds", 1);
        startUp(1, 1);
        JungleObjectives.Site rabbit = site(LaneSites.Side.BLUE, ObjectiveKind.RABBIT);
        Mob bunny = rabbit.mobs().getFirst();
        Player player = player(LaneSites.Side.BLUE, 0);
        player.teleport(ahead(rabbit, -1.5, 0));
        double before = bunny.getLocation().distance(player.getLocation());
        melee(player, bunny, 1);
        run(20);
        assertTrue(bunny.getLocation().distance(player.getLocation()) > before + 1, "backs away from a close fighter");

        // below half health: winds itself up and pops up far away
        player.teleport(bunny.getLocation().add(2, 0, 0));
        bunny.setHealth(Creatures.maxHealth(bunny) * 0.4);
        Location[] last = {bunny.getLocation()};
        double[] jump = {0};
        assertTrue(runUntil(40, () -> {
            jump[0] = Math.max(jump[0], bunny.getLocation().distance(last[0]));
            last[0] = bunny.getLocation();
            return jump[0] > 3;
        }), "a jump of " + jump[0]);
        assertTrue(skinEvents.contains("rabbit wind"), skinEvents.toString());
        assertTrue(Creatures.flatDistanceSquared(bunny.getLocation(), rabbit.at()) <= 14 * 14, "inside its leash");
    }

    // ------------------------------------------------------------------ dragon

    @Test
    void theDragonsBreathHitsItsMarkedConeOnly() {
        config.set("jungle.dragon.breath.cooldown-seconds", 0.1);
        config.set("jungle.dragon.tail.cooldown-seconds", 1000);
        config.set("jungle.dragon.gust.cooldown-seconds", 1000);
        startUp(2, 1);
        JungleObjectives.Site dragon = site(null, ObjectiveKind.DRAGON);
        Mob body = dragon.mobs().getFirst();
        Player front = player(LaneSites.Side.BLUE, 0);
        Player beside = player(LaneSites.Side.BLUE, 1);
        front.teleport(body.getLocation().add(5, 0, 0));
        beside.teleport(body.getLocation().add(0, 0, 6));
        melee(front, body, 1);
        assertTrue(runUntil(10, () -> jungle().isCasting(body)), "the breath is marked");
        assertTrue(skinEvents.contains("dragon charge"), skinEvents.toString());
        assertEquals(20, front.getHealth(), 1.0E-6, "warned first");
        assertTrue(runUntil(30, () -> skinEvents.contains("dragon breath")), skinEvents.toString());
        run(CreatureModels.Attack.BREATH.impact() + 1);
        assertTrue(front.getHealth() <= 10 + 1.0E-6, "in the cone: 10");
        assertEquals(20, beside.getHealth(), 1.0E-6, "beside it, outside the cone");
    }

    @Test
    void theDragonRagesBelowItsRageHealth() {
        config.set("jungle.dragon.breath.cooldown-seconds", 1000);
        config.set("jungle.dragon.tail.cooldown-seconds", 1000);
        config.set("jungle.dragon.gust.cooldown-seconds", 1000);
        startUp(1, 1);
        JungleObjectives.Site dragon = site(null, ObjectiveKind.DRAGON);
        Mob body = dragon.mobs().getFirst();
        Player player = player(LaneSites.Side.BLUE, 0);
        player.teleport(body.getLocation().add(3, 0, 0));
        melee(player, body, 1);
        run(10);
        assertFalse(jungle().isEnraged(body));
        body.setHealth(Creatures.maxHealth(body) * 0.3);
        assertTrue(runUntil(20, () -> jungle().isEnraged(body)), "enraged");
        assertTrue(skinEvents.contains("dragon roar") && skinEvents.contains("dragon enraged"), skinEvents.toString());
        assertTrue(arena.sounds.contains(Sound.ENTITY_ENDER_DRAGON_GROWL), arena.sounds.toString());
        // enraged bites hurt more: 8 × 1.25
        double before = player.getHealth();
        player.setHealth(20);
        assertTrue(runUntil(60, () -> player.getHealth() < 20), "it bites");
        assertEquals(10, player.getHealth(), 1.0E-6, "10 instead of 8 (before: " + before + ")");
        jungle().reset(dragon);
        assertTrue(skinEvents.contains("dragon calm"), "calm again at home");
        assertFalse(jungle().isEnraged(body));
    }

    // ------------------------------------------------------------------ knockback

    @Test
    void creaturesSlideALittleInsteadOfTheVanillaFlingAndStagger() {
        startUp(1, 1);
        Mob jelly = site(LaneSites.Side.BLUE, ObjectiveKind.JELLY).mobs().getFirst();
        EntityKnockbackEvent push = new EntityKnockbackEvent(jelly, EntityKnockbackEvent.Cause.ENTITY_ATTACK,
                new Vector(1.2, 0.45, 0));
        Bukkit.getPluginManager().callEvent(push);
        assertFalse(push.isCancelled());
        assertEquals(0, push.getKnockback().getY(), 1.0E-9, "never lifted");
        assertEquals(0.5 * 0.454, push.getKnockback().length(), 1.0E-6, "a small push (jelly: 0.5 blocks)");
        assertTrue(push.getKnockback().getX() > 0, "away from the blow");
        assertTrue(skinEvents.contains("jelly stagger"), skinEvents.toString());

        Mob dragon = site(null, ObjectiveKind.DRAGON).mobs().getFirst();
        EntityKnockbackEvent shove = new EntityKnockbackEvent(dragon, EntityKnockbackEvent.Cause.ENTITY_ATTACK,
                new Vector(1, 0.4, 0));
        Bukkit.getPluginManager().callEvent(shove);
        assertTrue(shove.isCancelled(), "the dragon stands its ground");
    }
}
