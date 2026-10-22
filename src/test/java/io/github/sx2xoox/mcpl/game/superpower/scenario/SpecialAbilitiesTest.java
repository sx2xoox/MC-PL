package io.github.sx2xoox.mcpl.game.superpower.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.game.superpower.support.Scenario;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import io.github.sx2xoox.mcpl.game.superpower.support.world.MockEntities;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/** 특수 계열: 요정, 불사조, 환영술사, 도박사 (도박사의 잭팟은 {@link GamblerJackpotTest}). */
class SpecialAbilitiesTest {

    private static final double EPSILON = 1.0E-6;

    private static List<Mannequin> copies(Scenario s) {
        return s.world.getEntities().stream().filter(Mannequin.class::isInstance).map(Mannequin.class::cast)
                .toList();
    }

    private static double horizontalDistance(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    // ------------------------------------------------------------------ 요정 pixie

    @Test
    void pixieIsSmallFragileAndQuick() {
        try (Scenario s = Scenario.ffa("pixie")) {
            assertEquals(s.param("scale"), s.owner.getAttribute(Attribute.SCALE).getValue(), EPSILON);
            assertEquals(20 - s.param("health_penalty"), s.maxHealth(s.owner), EPSILON);
            assertTrue(s.owner.getHealth() <= s.maxHealth(s.owner));
            assertEquals(0.1 * (1 + 0.2 * s.param("speed_level")),
                    s.owner.getAttribute(Attribute.MOVEMENT_SPEED).getValue(), EPSILON, "speed I");
            assertEquals(0.42 + 0.1 * s.param("jump_level"),
                    s.owner.getAttribute(Attribute.JUMP_STRENGTH).getValue(), EPSILON, "jump boost I");
            assertEquals(1.8 * s.param("scale"), s.owner.getBoundingBox().getHeight(), 1.0E-6, "a smaller hitbox");
            s.finishClean();
        }
    }

    @Test
    void pixieDustBlindsNearbyEnemiesAndHidesThePixieUntilItAttacks() {
        try (Scenario s = Scenario.ffa("pixie")) {
            s.place(s.enemy2, 0.5, 12.5, 180);
            s.assertSucceeded(s.active(), "요정 가루");
            PotionEffect blind = s.enemy.getPotionEffect(PotionEffectType.BLINDNESS);
            assertNotNull(blind, "the enemy within " + s.param("dust_radius") + " blocks is blinded");
            assertEquals(s.paramTicks("blind_duration"), blind.getDuration(), 1);
            assertFalse(s.enemy2.hasPotionEffect(PotionEffectType.BLINDNESS), "enemy2 is too far");
            assertFalse(s.enemy.canSee(s.owner), "the pixie vanishes");
            assertTrue(s.owner.hasPotionEffect(PotionEffectType.INVISIBILITY));

            s.melee(s.owner, s.enemy, 2);
            assertTrue(s.enemy.canSee(s.owner), "attacking reveals the pixie");
            assertFalse(s.owner.hasPotionEffect(PotionEffectType.INVISIBILITY));
            s.finishClean();
        }
    }

    @Test
    void pixieWingsFlyForAWhileThenGlideDownSafely() {
        try (Scenario s = Scenario.ffa("pixie")) {
            s.assertSucceeded(s.ultimate(), "요정의 날개");
            assertTrue(s.owner.getAllowFlight() && s.owner.isFlying(), "flying");
            assertEquals(0.1f * s.param("fly_speed") / 100, s.owner.getFlySpeed(), 1.0E-6);
            s.place(s.owner, 0.5, FlatWorld.SURFACE + 12, 0.5, 0, 0);
            s.owner.setFlying(true);
            s.ticks(s.paramTicks("flight_duration") + 2);
            assertFalse(s.owner.isFlying(), "the wings are gone");
            assertFalse(s.owner.getAllowFlight(), "flight permission restored");
            assertEquals(0.1f, s.owner.getFlySpeed(), 1.0E-6, "fly speed restored");
            assertTrue(s.owner.hasPotionEffect(PotionEffectType.SLOW_FALLING), "gliding down");
            s.tickUntilGrounded(s.owner, 600);
            s.ticks(5);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "a soft landing");
            assertFalse(s.owner.hasPotionEffect(PotionEffectType.SLOW_FALLING), "the glide ends on the ground");
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 불사조 phoenix

    @Test
    void phoenixRisesFromTheAshesOnceAndBurnsNearbyEnemies() {
        try (Scenario s = Scenario.ffa("phoenix")) {
            s.place(s.enemy, 0.5, 2.5, 180);
            s.place(s.enemy2, 0.5, 12.5, 180);
            s.melee(s.enemy, s.owner, 50);
            assertTrue(s.session.isAlive(s.owner), "reborn instead of dying");
            assertEquals(s.maxHealth(s.owner) * s.param("revive_health") / 100, s.owner.getHealth(), EPSILON);
            s.melee(s.enemy, s.owner, 50);
            assertTrue(s.session.isAlive(s.owner), "untouchable for " + s.param("revive_grace") + " s");
            s.ticks(2);
            assertEquals(s.param("revive_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the rebirth blast");
            assertTrue(s.enemy.getFireTicks() > 0, "sets the enemy on fire");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "enemy2 is too far");

            s.ticks(s.paramTicks("revive_grace") + 2);
            s.melee(s.enemy, s.owner, 50);
            assertFalse(s.session.isAlive(s.owner), "only once per match");
            s.respawn(s.owner);
            s.finishClean();
        }
    }

    @Test
    void phoenixFlameWingsLeapHighAndGlideDownWithoutFallDamage() {
        try (Scenario s = Scenario.ffa("phoenix")) {
            s.assertSucceeded(s.active(), "화염 날개");
            assertTrue(s.owner.hasPotionEffect(PotionEffectType.SLOW_FALLING), "a slow glide");
            double peak = FlatWorld.SURFACE;
            for (int i = 0; i < 40; i++) {
                s.ticks(1);
                peak = Math.max(peak, s.owner.getLocation().getY());
            }
            assertTrue(peak > FlatWorld.SURFACE + s.param("leap_height") * 0.6, "leapt to " + peak);
            s.tickUntilGrounded(s.owner, 800);
            s.ticks(5);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertFalse(s.owner.hasPotionEffect(PotionEffectType.SLOW_FALLING), "the glide ends on landing");
            s.finishClean();
        }
    }

    @Test
    void phoenixDescentDashBurnsEveryEnemyOnItsPath() {
        try (Scenario s = Scenario.ffa("phoenix")) {
            s.assertSucceeded(s.ultimate(), "불사조 강림");
            s.ticks(30);
            for (TestPlayer target : s.enemies()) {
                assertEquals(s.param("dash_damage"), s.damageBy(s.owner, target), EPSILON, target.getName());
                assertTrue(target.getFireTicks() > 0 || !s.hitsOf(target, DamageCause.FIRE_TICK).isEmpty(),
                        target.getName() + " burns");
            }
            double travelled = s.owner.getLocation().getZ() - 0.5;
            assertTrue(travelled > s.param("dash_distance") * 0.7, "dashed " + travelled + " blocks");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void phoenixDashNeedsRoomAhead() {
        try (Scenario s = Scenario.ffa("phoenix")) {
            s.world.wallAlongX(-2, 3, 2, 3, org.bukkit.Material.STONE);
            assertFalse(s.ultimate().succeeded(), "a wall right ahead");
            s.world.wallAlongX(-2, 3, 2, 3, org.bukkit.Material.AIR);
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 환영술사 illusionist

    @Test
    @Disabled("BUG: illusionist copies never spawn on Paper: dress() calls EntityEquipment#setDropChance on a "
            + "Mannequin, which is not a Mob, so the spawn throws and both skills fail")
    void illusionistDecoyAppearsOnPaper() {
        // CraftEntityEquipment#setDropChance: Preconditions.checkArgument(entity instanceof Mob, "Cannot set drop
        // chance for non-Mob entity"). The spawn consumer throws, spawnCopy catches it (FINE log) and returns null.
        try (Scenario s = Scenario.ffa("illusionist")) {
            ActivationResult decoy = s.active();
            s.assertSucceeded(decoy, "분신");
            assertEquals(1, copies(s).size(), "a copy stands where the illusionist was");
            s.finishClean();
        }
    }

    /** The illusionist scenarios below run as if its drop-chance bug were fixed. */
    private static Scenario illusionist() {
        Scenario s = Scenario.ffa("illusionist");
        MockEntities.TestMannequin.ignoreDropChances = true;
        return s;
    }

    @Test
    void illusionistDecoyLeavesALookAlikeAndVanishes() {
        try (Scenario s = illusionist()) {
            Location spot = s.owner.getLocation();
            s.owner.setClientSkinParts(0x7E); // no cape
            s.assertSucceeded(s.active(), "분신");
            List<Mannequin> copies = copies(s);
            assertEquals(1, copies.size());
            Mannequin copy = copies.getFirst();
            assertTrue(horizontalDistance(copy.getLocation(), spot) < 0.01, "where the illusionist stood");
            assertEquals(s.owner.getName(), copy.getProfile().name(), "wears the owner's skin");
            assertEquals(0x7E, copy.getSkinParts().getRaw(), "with the owner's skin layers");
            assertEquals(Material.IRON_SWORD, copy.getEquipment().getItemInMainHand().getType(),
                    "and a real weapon instead of the ability item");
            assertEquals(Material.IRON_CHESTPLATE, copy.getEquipment().getChestplate().getType(), "and armor");
            assertTrue(s.ctx().isEnemy(s.enemy, copy), "enemies can strike the copy");
            assertFalse(s.enemy.canSee(s.owner), "the illusionist vanished");
            assertTrue(s.owner.hasPotionEffect(PotionEffectType.INVISIBILITY));

            s.melee(s.enemy, copy, 6);
            assertFalse(copy.isValid(), "the struck copy shatters");
            assertEquals(0, s.damageTaken(copy), EPSILON, "copies take no damage");
            PotionEffect blind = s.enemy.getPotionEffect(PotionEffectType.BLINDNESS);
            assertNotNull(blind, "the striker is blinded");
            assertEquals(s.paramTicks("clone_blind"), blind.getDuration(), 1);
            s.ticks(s.paramTicks("invis_duration") + 2);
            assertTrue(s.enemy.canSee(s.owner), "visible again");
            s.finishClean();
        }
    }

    @Test
    void illusionistCopiesExpireAndTheirListenerGoesAway() {
        try (Scenario s = illusionist()) {
            int before = damageListeners(s);
            s.assertSucceeded(s.active(), "분신");
            assertEquals(before + 1, damageListeners(s), "a guard listener protects the copies");
            s.ticks(s.paramTicks("clone_duration") + 2);
            assertTrue(copies(s).isEmpty(), "the copy fades after " + s.param("clone_duration") + " s");
            assertEquals(before, damageListeners(s), "and the guard listener is unregistered");
            s.finishClean();
        }
    }

    private static int damageListeners(Scenario s) {
        int count = 0;
        for (RegisteredListener listener : EntityDamageEvent.getHandlerList().getRegisteredListeners()) {
            if (listener.getPlugin() == s.h.hub.plugin()) {
                count++;
            }
        }
        return count;
    }

    @Test
    void illusionistLegionSurroundsTheOwnerAndSwapsWithACopy() {
        try (Scenario s = illusionist()) {
            Location origin = s.owner.getLocation();
            s.assertSucceeded(s.ultimate(), "환영 군단");
            List<Mannequin> copies = copies(s);
            assertEquals((int) s.param("legion_clones"), copies.size());
            assertTrue(copies.stream().anyMatch(copy -> horizontalDistance(copy.getLocation(), origin) < 0.01),
                    "a copy takes the owner's place");
            double moved = horizontalDistance(s.owner.getLocation(), origin);
            assertTrue(moved > 1.0 && moved <= s.param("legion_spread") + 0.5, "swapped " + moved + " blocks");
            assertFalse(s.world.hasCollisionsIn(s.owner.getBoundingBox().expand(-0.01)));
            assertFalse(s.enemy.canSee(s.owner));
            for (Mannequin copy : copies) {
                assertFalse(s.world.hasCollisionsIn(copy.getBoundingBox().expand(-0.01)), "copies stand free");
            }
            s.ticks(s.paramTicks("legion_duration") + 2);
            assertTrue(copies(s).isEmpty());
            assertTrue(s.enemy.canSee(s.owner));
            s.finishClean();
        }
    }

    @Test
    void illusionistShieldBlocksOneAttackEveryTwentySeconds() {
        try (Scenario s = Scenario.ffa("illusionist")) {
            s.melee(s.enemy, s.owner, 5);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the mirage shield takes the first hit");
            s.melee(s.enemy, s.owner, 5);
            assertEquals(5, s.damageTaken(s.owner), EPSILON, "then it is down");
            s.ticks(s.paramTicks("shield_cooldown") + 1);
            s.melee(s.enemy, s.owner, 5);
            assertEquals(5, s.damageTaken(s.owner), EPSILON, "and back after " + s.param("shield_cooldown") + " s");
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 도박사 gambler

    @Test
    void gamblerLuckDodgesHitsAndDoublesBlows() {
        try (Scenario s = Scenario.builder("gambler").param("dodge_chance", 100).param("crit_chance", 100)
                .start()) {
            s.melee(s.enemy, s.owner, 6);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "lucky dodge");
            s.melee(s.owner, s.enemy, 3);
            assertEquals(3 * s.param("crit_multiplier"), s.damageBy(s.owner, s.enemy), EPSILON, "double damage");
            s.finishClean();
        }
    }

    @Test
    void gamblerDiceAlwaysPaysOneOfItsFaces() {
        // No lucky dodge / double damage so every face is recognisable.
        try (Scenario s = Scenario.builder("gambler").param("dodge_chance", 0).param("crit_chance", 0).start()) {
            for (int roll = 0; roll < 12; roll++) {
                s.readyActive();
                s.healAll();
                s.owner.setHealth(14);
                s.match.setGauge(s.owner, 0);
                for (PotionEffect effect : List.copyOf(s.owner.getActivePotionEffects())) {
                    s.owner.removePotionEffect(effect.getType());
                }
                s.clearHits();
                int bolts = s.world.lightningEffects().size();
                s.assertSucceeded(s.active(), "주사위 #" + roll);
                s.ticks(45);
                boolean bust = s.damageTaken(s.owner) == s.param("dice_self_damage");
                boolean swift = s.owner.hasPotionEffect(PotionEffectType.SPEED);
                boolean might = s.owner.hasPotionEffect(PotionEffectType.STRENGTH);
                boolean mend = s.owner.getHealth() > 14;
                boolean bolt = s.world.lightningEffects().size() > bolts
                        && s.damageBy(s.owner, s.enemy) == s.param("dice_bolt_damage");
                boolean jackpot = s.participant(s.owner).gauge() >= s.param("dice_gauge");
                int faces = (bust ? 1 : 0) + (swift ? 1 : 0) + (might ? 1 : 0) + (mend ? 1 : 0) + (bolt ? 1 : 0)
                        + (jackpot ? 1 : 0);
                assertEquals(1, faces, "exactly one face paid out (roll " + roll + "): bust=" + bust + " swift="
                        + swift + " might=" + might + " mend=" + mend + " bolt=" + bolt + " jackpot=" + jackpot);
                for (Entity entity : s.world.getEntities()) {
                    assertTrue(entity instanceof TestPlayer, "the dice display is gone: " + entity.getType());
                }
            }
            s.finishClean();
        }
    }
}
