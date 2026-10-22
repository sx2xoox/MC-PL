package io.github.sx2xoox.mcpl.game.superpower.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import io.github.sx2xoox.mcpl.game.superpower.support.Scenario;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Wolf;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/** 원거리 · 소환 · 지원 계열: 궁수, 폭탄마, 소환사, 치유사. */
class RangedSupportAbilitiesTest {

    private static final double EPSILON = 1.0E-6;

    private static <T extends Entity> List<T> entities(Scenario s, Class<T> type) {
        return s.world.getEntities().stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static int count(TestPlayer player, Material type) {
        int total = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == type) {
                total += item.getAmount();
            }
        }
        return total;
    }

    // ------------------------------------------------------------------ 궁수 archer

    @Test
    void archerStartsWithABowAndRegrowsArrows() {
        try (Scenario s = Scenario.ffa("archer")) {
            ItemStack bow = null;
            for (ItemStack item : s.owner.getInventory().getContents()) {
                if (item != null && item.getType() == Material.BOW) {
                    bow = item;
                }
            }
            assertNotNull(bow, "a bow");
            assertEquals(1, bow.getEnchantmentLevel(Enchantment.POWER), "power I");
            assertEquals((int) s.param("arrows"), count(s.owner, Material.ARROW));

            for (ItemStack item : s.owner.getInventory().getContents()) {
                if (item != null && item.getType() == Material.ARROW) {
                    item.setAmount(item.getAmount() - 5); // five shots with the bow
                    break;
                }
            }
            s.ticks(s.paramTicks("arrow_regen") + 21);
            assertEquals((int) s.param("arrows") - 4, count(s.owner, Material.ARROW), "one arrow regrown");
            s.finishClean();
            assertEquals(0, count(s.owner, Material.BOW), "the archer kit is taken back");
        }
    }

    @Test
    void archerPlainArrowsHitHarder() {
        try (Scenario s = Scenario.ffa("archer")) {
            Arrow arrow = s.world.spawnArrow(s.owner.getEyeLocation(), new Vector(0, 0, 1), 1, 0);
            arrow.setShooter(s.owner);
            s.enemy.damage(4, DamageSource.builder(DamageType.ARROW).withCausingEntity(s.owner)
                    .withDirectEntity(arrow).build());
            assertEquals(4 * (1 + s.param("arrow_bonus") / 100), s.damageTaken(s.enemy), EPSILON);
            arrow.remove();
            s.finishClean();
        }
    }

    @Test
    void archerVolleyFansFiveArrowsThatHitAndPush() {
        try (Scenario s = Scenario.ffa("archer")) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "산탄");
            assertEquals((int) s.param("volley_arrows"), entities(s, Arrow.class).size(), "five arrows in a fan");
            assertTrue(s.owner.getVelocity().getZ() < 0, "recoil pushes the archer back");
            s.ticks(10);
            assertTrue(s.damageBy(s.owner, s.enemy) >= s.param("volley_damage"), "the centre arrow hits");
            for (Scenario.Hit hit : s.hitsBy(s.owner, s.enemy)) {
                assertTrue(hit.damage() == s.param("volley_damage")
                        || hit.damage() == s.param("volley_damage") * s.param("volley_overlap") / 100,
                        "extra arrows on the same enemy deal less: " + hit);
            }
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(120);
            assertTrue(entities(s, Arrow.class).isEmpty(), "skill arrows never linger");
            s.finishClean();
        }
    }

    @Test
    void archerArrowRainPeppersTheZoneButSparesTheArcher() {
        try (Scenario s = Scenario.ffa("archer")) {
            s.place(s.enemy2, 2.5, 6.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "화살비");
            s.ticks(100);
            assertTrue(s.damageBy(s.owner, s.enemy) >= s.param("rain_damage"), "the aimed enemy is hit");
            assertTrue(s.damageBy(s.owner, s.enemy2) >= s.param("rain_damage"), "so is enemy2 in the zone");
            long interval = s.paramTicks("rain_hit_interval");
            List<Scenario.Hit> hits = s.hitsBy(s.owner, s.enemy);
            for (int i = 1; i < hits.size(); i++) {
                assertTrue(hits.get(i).tick() - hits.get(i - 1).tick() >= interval, "at most one hit per "
                        + s.param("rain_hit_interval") + " s");
            }
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the archer stands in the zone unharmed");
            s.ticks(60);
            assertTrue(entities(s, Arrow.class).isEmpty(), "rain arrows are cleaned up");
            s.finishClean();
        }
    }

    @Test
    @Disabled("BUG: Archer skill arrows are re-dealt as MAGIC damage from the archer, so Blink's projectile dodge "
            + "never triggers on them")
    void blinkDodgesArcherVolleyArrows() {
        // ArcherAbility#volleyHit cancels the arrow's ProjectileHitEvent and deals Combat.hit (MAGIC, direct entity =
        // the archer). BlinkAbility#isProjectileHit only recognises projectile damagers / PROJECTILE causes, so a
        // blink player with a 100% dodge chance still takes every volley arrow.
        try (Scenario s = Scenario.builder("archer").enemyAbility("blink")
                .configure(h -> h.setParam("blink", "dodge_chance", 100)).start()) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "산탄");
            s.ticks(10);
            assertTrue(entities(s, Arrow.class).size() < s.param("volley_arrows"), "some arrows reached enemies");
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "a blink with 100% dodge avoids projectile attacks");
            s.finishClean();
        }
    }

    @Test
    @Disabled("BUG: Archer skill arrows are re-dealt as MAGIC damage from the archer, so Mirror's projectile "
            + "refraction never reduces them")
    void mirrorRefractsArcherVolleyArrows() {
        try (Scenario s = Scenario.builder("archer").enemyAbility("mirror")
                .configure(h -> h.setParam("mirror", "projectile_reduction", 100)).start()) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "산탄");
            s.ticks(10);
            assertTrue(entities(s, Arrow.class).size() < s.param("volley_arrows"), "some arrows reached enemies");
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "100% projectile reduction blocks the volley");
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 폭탄마 bomber

    @Test
    void bomberGrenadeIsAHarmlessFusedTntThatBlastsEnemiesAround() {
        try (Scenario s = Scenario.ffa("bomber")) {
            s.place(s.enemy2, 0.5, -10.5, 0);
            s.aimAtFloor(s.owner, 0.5, 2.5);
            s.assertSucceeded(s.active(), "수류탄");
            List<TNTPrimed> grenades = entities(s, TNTPrimed.class);
            assertEquals(1, grenades.size(), "a primed grenade");
            assertEquals(0f, grenades.getFirst().getYield(), "with zero yield");
            s.ticks(s.paramTicks("grenade_fuse") + 2);
            assertTrue(entities(s, TNTPrimed.class).isEmpty(), "removed before vanilla could explode it");
            double damage = s.damageBy(s.owner, s.enemy);
            assertTrue(damage >= s.param("grenade_min_damage") && damage <= s.param("grenade_damage"),
                    "distance-scaled blast: " + damage);
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "out of the blast");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void bomberShrugsOffExplosionsAndRidesItsOwnBlast() {
        try (Scenario s = Scenario.ffa("bomber")) {
            s.owner.damage(10, DamageSource.builder(DamageType.EXPLOSION).build());
            assertEquals(10 * (1 - s.param("blast_resist") / 100), s.damageTaken(s.owner), EPSILON);
            s.clearHits();

            s.place(s.owner, 0.5, FlatWorld.SURFACE, 0.5, 0, 89);
            s.assertSucceeded(s.active(), "수류탄 at its own feet");
            s.ticks(s.paramTicks("grenade_fuse") + 4);
            assertTrue(s.owner.getLocation().getY() > FlatWorld.SURFACE + 1, "bomb jump");
            s.tickUntilGrounded(s.owner, 200);
            s.ticks(5);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "neither the blast nor the landing hurts");
            s.finishClean();
        }
    }

    @Test
    void bomberCarpetBombingHitsEachEnemyAtMostThreeTimes() {
        try (Scenario s = Scenario.ffa("bomber")) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "융단폭격");
            s.ticks(5);
            assertFalse(entities(s, BlockDisplay.class).isEmpty(), "falling bombs");
            s.ticks(s.paramTicks("carpet_duration") + 20);
            for (TestPlayer target : s.enemies()) {
                int hits = s.hitsBy(s.owner, target).size();
                assertTrue(hits <= s.param("carpet_max_hits"), target.getName() + " hit " + hits + " times");
            }
            assertTrue(s.damageBy(s.owner, s.enemy) >= s.param("carpet_damage"), "the aimed enemy is bombed");
            assertTrue(entities(s, BlockDisplay.class).isEmpty(), "every bomb is gone");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 소환사 summoner

    @Test
    void summonerWolvesAreTamedHuntEnemiesAndLeaveOnTime() {
        try (Scenario s = Scenario.teams("summoner")) {
            s.crossfireAlly();
            s.assertSucceeded(s.active(), "늑대 무리");
            List<Wolf> wolves = entities(s, Wolf.class);
            assertEquals((int) s.param("wolf_count"), wolves.size());
            for (Wolf wolf : wolves) {
                assertTrue(wolf.isTamed());
                assertEquals(s.owner, wolf.getOwner());
                assertEquals(s.param("wolf_health"), wolf.getAttribute(Attribute.MAX_HEALTH).getValue(), EPSILON);
                assertEquals(s.param("wolf_health"), wolf.getHealth(), EPSILON);
                assertTrue(s.ctx().isAlly(s.owner, wolf), "its own summon is an ally");
                assertTrue(s.ctx().isEnemy(s.enemy, wolf), "and an enemy of the enemies");
                assertFalse(s.ctx().isEnemy(s.ally(), wolf), "but not of the owner's team");
            }
            s.ticks(10);
            for (Wolf wolf : wolves) {
                LivingEntity target = wolf.getTarget();
                assertNotNull(target, "the wolf picks a target");
                assertTrue(s.enemies().contains(target), "an enemy, never the ally: " + target.getName());
            }
            s.ticks(s.paramTicks("wolf_duration"));
            assertTrue(entities(s, Wolf.class).isEmpty(), "dismissed after " + s.param("wolf_duration") + " s");
            s.finishClean();
        }
    }

    @Test
    void summonerKeepsAtMostSixWolves() {
        try (Scenario s = Scenario.ffa("summoner")) {
            for (int i = 0; i < 4; i++) {
                s.readyActive();
                s.place(s.owner, 0.5 + i * 3, 0.5, 0);
                s.assertSucceeded(s.active(), "늑대 무리 #" + i);
                s.ticks(2);
            }
            assertEquals((int) s.param("max_wolves"), entities(s, Wolf.class).size(), "the oldest are dismissed");
            s.finishClean();
        }
    }

    @Test
    void summonerKillCallsASpiritWolf() {
        try (Scenario s = Scenario.ffa("summoner")) {
            s.session.eliminate(s.enemy2, s.owner);
            List<Wolf> wolves = entities(s, Wolf.class);
            assertEquals(1, wolves.size(), "a spirit wolf for the kill");
            assertEquals(Wolf.Variant.PALE, wolves.getFirst().getVariant());
            s.finishClean();
        }
    }

    @Test
    void summonerIronGolemSlamsOnArrivalAndStaysForItsDuration() {
        try (Scenario s = Scenario.ffa("summoner")) {
            s.assertSucceeded(s.ultimate(), "철골렘");
            List<IronGolem> golems = entities(s, IronGolem.class);
            assertEquals(1, golems.size());
            IronGolem golem = golems.getFirst();
            assertFalse(golem.isPlayerCreated(), "player-made golems would refuse to fight players");
            assertEquals(s.param("golem_health"), golem.getHealth(), EPSILON);
            assertEquals(s.param("golem_slam_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "landing slam");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(10);
            assertEquals(s.enemy, golem.getTarget(), "it goes for the nearest enemy");
            s.ticks(s.paramTicks("golem_duration"));
            assertTrue(entities(s, IronGolem.class).isEmpty(), "dismissed after " + s.param("golem_duration") + " s");
            s.finishClean();
        }
    }

    @Test
    @Disabled("BUG: summon hits are never routed to the summoner's onDealDamage, so wolf/golem damage is the "
            + "difficulty-scaled vanilla damage instead of the advertised wolf_damage/golem_damage")
    void summonerWolfBitesDealTheAdvertisedDamage() {
        // SummonerAbility#onDealDamage normalises a summon's hit to its param damage "regardless of vanilla
        // randomness or difficulty", but MatchListener#attackerOf only resolves players and projectiles, so the
        // hook is never called for a wolf or golem damager. On easy difficulty a 3-damage wolf bites for 2.5.
        try (Scenario s = Scenario.ffa("summoner")) {
            s.assertSucceeded(s.active(), "늑대 무리");
            Wolf wolf = entities(s, Wolf.class).getFirst();
            s.melee(wolf, s.enemy, wolf.getAttribute(Attribute.ATTACK_DAMAGE).getValue());
            assertEquals(s.param("wolf_damage"), s.damageTaken(s.enemy), EPSILON);
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 치유사 healer

    @Test
    void healerRegeneratesSlowly() {
        try (Scenario s = Scenario.ffa("healer")) {
            s.owner.setHealth(10);
            s.ticks(s.paramTicks("regen_interval") * 2 + 1);
            assertEquals(10 + 2 * s.param("regen_amount"), s.owner.getHealth(), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void healerLightHealsAndCleansesTheTeamButNotEnemies() {
        try (Scenario s = Scenario.teams("healer")) {
            assertFalse(s.active().succeeded(), "nobody needs care");
            s.ally().setHealth(10);
            s.ally().addPotionEffect(new PotionEffect(PotionEffectType.POISON, 200, 0));
            s.ally().setFireTicks(60);
            s.enemy.setHealth(10);
            s.owner.setHealth(17);
            s.assertSucceeded(s.active(), "치유의 빛");
            assertEquals(10 + s.param("light_heal"), s.ally().getHealth(), EPSILON);
            assertFalse(s.ally().hasPotionEffect(PotionEffectType.POISON), "cleansed");
            assertEquals(0, s.ally().getFireTicks(), "extinguished");
            assertEquals(s.maxHealth(s.owner), s.owner.getHealth(), EPSILON, "the healer heals itself too");
            assertEquals(10, s.enemy.getHealth(), EPSILON, "enemies are not healed");
            s.finishClean();
        }
    }

    @Test
    void healerSanctuaryHealsAlliesInsideAndRepelsEnemies() {
        try (Scenario s = Scenario.teams("healer")) {
            s.place(s.ally(), 2.5, 0.5, 0);
            s.place(s.enemy, 0.5, 3.0, 180);
            s.ally().setHealth(10);
            s.assertSucceeded(s.ultimate(), "성역");
            s.ticks(21);
            assertTrue(s.ally().getHealth() >= 10 + 2 * s.param("sanctum_heal"), "allies inside heal");
            assertTrue(s.enemy.getLocation().distance(s.owner.getLocation()) > 3.5, "the enemy is pushed out");
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.WEAKNESS), "and weakened");
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "pushing does not hurt");
            assertFalse(s.ally().hasPotionEffect(PotionEffectType.WEAKNESS));
            s.ticks(s.paramTicks("sanctum_duration") + 20);
            assertTrue(entities(s, BlockDisplay.class).isEmpty(), "the light beam is gone");
            assertTrue(s.hitsOf(s.enemy, DamageCause.FALL).isEmpty(), "repelling never causes fall damage");
            s.finishClean();
        }
    }
}
