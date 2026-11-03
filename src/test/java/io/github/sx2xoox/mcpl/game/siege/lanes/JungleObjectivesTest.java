package io.github.sx2xoox.mcpl.game.siege.lanes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites.ObjectiveKind;
import io.github.sx2xoox.mcpl.game.siege.arena.LaneModifiers;
import io.github.sx2xoox.mcpl.game.siege.jungle.JungleObjectives;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Endermite;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Panda;
import org.bukkit.entity.Player;
import org.bukkit.entity.Rabbit;
import org.bukkit.entity.Ravager;
import org.bukkit.entity.Silverfish;
import org.bukkit.entity.Slime;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.Test;

/** 공성전 v2 jungle objectives (docs/siege-v2.md §4). */
class JungleObjectivesTest extends LaneTestBase {

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

    private JungleObjectives.Site site(LaneSites.Side home, ObjectiveKind kind) {
        JungleObjectives.Site site = jungle().site(home, kind);
        assertNotNull(site);
        return site;
    }

    private void startUp(int blue, int red) {
        start(blue, red);
        run(2);
        assertTrue(jungle().sites().stream().allMatch(JungleObjectives.Site::isUp), "everything up");
    }

    /** Kills every monster of a site with melee hits from {@code killer}. */
    private void clear(JungleObjectives.Site site, Player killer) {
        killer.teleport(site.at().add(1, 0, 0));
        for (Mob mob : site.mobs()) {
            melee(killer, mob, 100_000);
        }
        assertFalse(site.isUp(), site.objective().id() + " cleared");
    }

    @Test
    void everyKindLooksDifferentAndLeavesNothingBehind() {
        startUp(1, 1);
        assertEquals(13, jungle().sites().size(), "six a side and the dragon");
        assertEquals(6, count(Slime.class), "젤리 3 a side");
        assertEquals(4, count(Silverfish.class), "쥐: silverfish…");
        assertEquals(4, count(Endermite.class), "…and endermites");
        assertEquals(2, count(Panda.class), "곰인형");
        assertEquals(2, count(Rabbit.class), "시계 토끼");
        assertEquals(1, count(Ravager.class), "드래곤");
        Mob teddy = site(LaneSites.Side.BLUE, ObjectiveKind.TEDDY).mobs().getFirst();
        assertEquals(1.4, teddy.getAttribute(Attribute.SCALE).getBaseValue(), 1.0E-6);
        assertEquals(Panda.Gene.BROWN, ((Panda) teddy).getMainGene());
        assertEquals(Rabbit.Type.GOLD, ((Rabbit) site(LaneSites.Side.RED, ObjectiveKind.RABBIT).mobs().getFirst())
                .getRabbitType());
        assertNotNull(site(null, ObjectiveKind.DRAGON).label(), "a label over the site");
        assertTrue(arena.tracked.contains(site(null, ObjectiveKind.DRAGON).label()));

        systems.endRound();
        assertEquals(0, count(Slime.class) + count(Silverfish.class) + count(Endermite.class) + count(Panda.class)
                + count(Rabbit.class) + count(Ravager.class));
        assertTrue(arena.tracked.isEmpty(), "monsters and labels are gone");
    }

    @Test
    void monstersStandStillUntilHitThenFightAndResetWhenPulledAway() {
        startUp(1, 1);
        JungleObjectives.Site teddy = site(LaneSites.Side.BLUE, ObjectiveKind.TEDDY);
        Mob bear = teddy.mobs().getFirst();
        Location home = bear.getLocation();
        Player player = player(LaneSites.Side.BLUE, 0);
        player.teleport(teddy.at().add(5, 0, 0));
        run(40);
        assertEquals(home, bear.getLocation(), "idle until hit");
        assertFalse(teddy.isFighting());
        assertEquals(20, player.getHealth(), 1.0E-6);

        melee(player, bear, 10);
        assertTrue(teddy.isFighting());
        run(60);
        assertTrue(player.getHealth() < 20, "the teddy fights back");
        assertTrue(skinEvents.contains("teddy hit"), "the skin flinches when hit: " + skinEvents);
        assertTrue(skinEvents.contains("teddy attack"), "and swings when it strikes: " + skinEvents);
        assertTrue(arena.sounds.contains(Sound.BLOCK_WOOL_PLACE), "a soft whump: " + arena.sounds);

        // Pulled beyond the leash: home, full health, idle.
        bear.teleport(teddy.at().add(30, 0, 0));
        run(6);
        assertFalse(teddy.isFighting());
        assertEquals(home.getX(), bear.getLocation().getX(), 1.0E-6);
        assertEquals(bear.getAttribute(Attribute.MAX_HEALTH).getValue(), bear.getHealth(), 1.0E-6);

        // Hit and left alone: the camp resets after a while.
        melee(player, bear, 10);
        player.teleport(at(0, 80));
        run(8 * 20 + 10);
        assertFalse(teddy.isFighting());
        assertEquals(bear.getAttribute(Attribute.MAX_HEALTH).getValue(), bear.getHealth(), 1.0E-6);
    }

    @Test
    void smallCampsGiveScoreGoldAndExperienceAndComeBack() {
        startUp(2, 1);
        Player killer = player(LaneSites.Side.BLUE, 0);
        Player helper = player(LaneSites.Side.BLUE, 1);
        JungleObjectives.Site jelly = site(LaneSites.Side.BLUE, ObjectiveKind.JELLY);
        helper.teleport(jelly.at().add(-2, 0, 0));
        int gold = systems.gold().balance(killer.getUniqueId());
        clear(jelly, killer);
        assertEquals(3, count(Slime.class), "only the RED jellies are left (no splitting)");
        assertEquals(1, arena.score.get(LaneSites.Side.BLUE));
        assertEquals(gold + 40, systems.gold().balance(killer.getUniqueId()));
        assertEquals(15, arena.xp(killer), "30 XP split");
        assertEquals(15, arena.xp(helper));
        long back = jelly.upAt() - jungle().now();
        assertEquals(60 * 20, back, "60 s respawn");
        run((int) back + 1);
        assertTrue(jelly.isUp(), "back");
        assertEquals(6, count(Slime.class));

        JungleObjectives.Site mice = site(LaneSites.Side.RED, ObjectiveKind.MICE);
        clear(mice, killer);
        assertEquals(2, arena.score.get(LaneSites.Side.BLUE), "either side may take any camp");
    }

    @Test
    void theTeddyGivesAnAttackBuffThatEndsOnDeath() {
        startUp(2, 1);
        Player killer = player(LaneSites.Side.BLUE, 0);
        Player ally = player(LaneSites.Side.BLUE, 1);
        Player enemy = player(LaneSites.Side.RED, 0);
        JungleObjectives.Site teddy = site(LaneSites.Side.RED, ObjectiveKind.TEDDY);
        ally.teleport(teddy.at().add(0, 0, 3));
        enemy.teleport(teddy.at().add(0, 0, -3));
        int gold = systems.gold().balance(killer.getUniqueId());
        clear(teddy, killer);
        assertEquals(3, arena.score.get(LaneSites.Side.BLUE));
        assertEquals(gold + 90, systems.gold().balance(killer.getUniqueId()));
        for (Player buffed : new Player[] {killer, ally}) {
            assertTrue(jungle().hasBuff(buffed.getUniqueId(), ObjectiveKind.TEDDY));
            assertEquals(0.10, systems.modifiers().total(buffed.getUniqueId(), LaneModifiers.Stat.SKILL_DAMAGE),
                    1.0E-9);
        }
        assertFalse(jungle().hasBuff(enemy.getUniqueId(), ObjectiveKind.TEDDY), "never the enemy");
        double attack = killer.getAttribute(Attribute.ATTACK_DAMAGE).getValue();
        assertEquals(teddy.upAt() - jungle().now(), 150 * 20, "150 s respawn");

        killer.setHealth(0);
        run(1);
        assertFalse(jungle().hasBuff(killer.getUniqueId(), ObjectiveKind.TEDDY), "gone on death");
        assertEquals(0, systems.modifiers().total(killer.getUniqueId(), LaneModifiers.Stat.SKILL_DAMAGE), 1.0E-9);
        assertEquals(attack - 2, killer.getAttribute(Attribute.ATTACK_DAMAGE).getValue(), 1.0E-9);

        systems.endRound();
        assertFalse(jungle().hasBuff(ally.getUniqueId(), ObjectiveKind.TEDDY), "gone at the round end");
        assertEquals(0, systems.modifiers().total(ally.getUniqueId(), LaneModifiers.Stat.SKILL_DAMAGE), 1.0E-9);
    }

    @Test
    void theRabbitRunsAwayAndGivesFasterCooldowns() {
        startUp(1, 1);
        Player player = player(LaneSites.Side.BLUE, 0);
        JungleObjectives.Site rabbit = site(LaneSites.Side.BLUE, ObjectiveKind.RABBIT);
        Mob bunny = rabbit.mobs().getFirst();
        player.teleport(rabbit.at().add(-2, 0, 0));
        double before = bunny.getLocation().distance(player.getLocation());
        melee(player, bunny, 1);
        run(20);
        assertTrue(bunny.getLocation().distance(player.getLocation()) > before + 1, "flees");
        assertEquals(20, player.getHealth(), 1.0E-6, "never bites");
        assertTrue(skinEvents.contains("rabbit hit") && skinEvents.contains("rabbit attack"),
                "a startled hop when hit: " + skinEvents);
        assertTrue(arena.sounds.contains(Sound.ENTITY_RABBIT_JUMP), arena.sounds.toString());

        clear(rabbit, player);
        assertTrue(jungle().hasBuff(player.getUniqueId(), ObjectiveKind.RABBIT));
        run(40);
        // 20 % shorter cooldowns: 0.25 s back every second.
        assertEquals(0.5, arena.cooldownGiven.get(player.getUniqueId()), 1.0E-6);
    }

    @Test
    void theDragonRewardsTheWholeTeam() {
        startUp(2, 1);
        Player killer = player(LaneSites.Side.RED, 0);
        Player far = player(LaneSites.Side.BLUE, 1);
        Player blue = player(LaneSites.Side.BLUE, 0);
        JungleObjectives.Site dragon = site(null, ObjectiveKind.DRAGON);
        assertEquals(400 * 1.25, dragon.mobs().getFirst().getHealth(), 1.0E-6, "+25 % for the 2nd player");
        int blueGold = systems.gold().balance(blue.getUniqueId());
        int gold = systems.gold().balance(killer.getUniqueId());
        clear(dragon, killer);
        assertEquals(10, arena.score.get(LaneSites.Side.RED));
        assertEquals(gold + 200, systems.gold().balance(killer.getUniqueId()));
        assertEquals(blueGold, systems.gold().balance(blue.getUniqueId()));
        assertEquals(blueGold, systems.gold().balance(far.getUniqueId()));
        assertEquals(300, arena.xp(killer));
        assertTrue(jungle().hasBuff(killer.getUniqueId(), ObjectiveKind.DRAGON));
        assertTrue(systems.minions().empowered(LaneSites.Side.RED));
        assertFalse(systems.minions().empowered(LaneSites.Side.BLUE));
        assertEquals(300 * 20, dragon.upAt() - jungle().now(), "5 minutes");
    }

    @Test
    void capturePointsFillForOneSidePauseWhenContestedAndDrainForTheOther() {
        startUp(1, 1);
        Player blue = player(LaneSites.Side.BLUE, 0);
        Player red = player(LaneSites.Side.RED, 0);
        JungleObjectives.Site fountain = site(LaneSites.Side.BLUE, ObjectiveKind.FOUNTAIN);
        red.teleport(at(0, 80));
        blue.teleport(fountain.at());
        run(80);
        assertSame(LaneSites.Side.BLUE, fountain.capturer());
        assertEquals(0.5, fountain.fraction(), 0.02);

        red.teleport(fountain.at().add(1, 0, 0));
        run(40);
        assertTrue(fountain.isContested());
        assertEquals(0.5, fountain.fraction(), 0.02, "paused");

        blue.teleport(at(0, 80));
        run(30);
        assertSame(LaneSites.Side.BLUE, fountain.capturer(), "BLUE's progress drains first");
        assertTrue(fountain.fraction() < 0.2);
        run(15);
        assertSame(LaneSites.Side.RED, fountain.capturer());

        // An empty point falls back slowly.
        red.teleport(at(0, 80));
        run(10);
        assertTrue(fountain.fraction() < 0.05);

        // RED takes it: the team heals.
        red.setHealth(6);
        red.teleport(fountain.at());
        run(8 * 20 + 5);
        assertFalse(fountain.isUp(), "captured");
        assertEquals(2, arena.score.get(LaneSites.Side.RED));
        assertTrue(red.getHealth() >= 14, "healed");
        assertTrue(red.hasPotionEffect(PotionEffectType.REGENERATION));
        assertNull(fountain.capturer());
        assertEquals(90 * 20, fountain.upAt() - jungle().now(), 10);
    }

    @Test
    void theTelescopeRevealsTheEnemiesOnItsHalf() {
        startUp(1, 2);
        Player blue = player(LaneSites.Side.BLUE, 0);
        Player redNear = player(LaneSites.Side.RED, 0);
        Player redFar = player(LaneSites.Side.RED, 1);
        JungleObjectives.Site telescope = site(LaneSites.Side.BLUE, ObjectiveKind.TELESCOPE);
        redNear.teleport(at(-20, 60));
        redFar.teleport(at(20, 60));
        blue.teleport(telescope.at());
        run(8 * 20 + 5);
        assertFalse(telescope.isUp(), "captured");
        assertTrue(jungle().isRevealing(LaneSites.Side.BLUE));
        run(20);
        assertTrue(redNear.hasPotionEffect(PotionEffectType.GLOWING), "on the telescope's half");
        assertFalse(redFar.hasPotionEffect(PotionEffectType.GLOWING), "the other half stays hidden");
        assertFalse(blue.hasPotionEffect(PotionEffectType.GLOWING), "never the capturers");
    }
}
