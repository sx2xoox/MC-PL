package io.github.sx2xoox.mcpl.game.augment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.game.augment.runtime.AugmentMatch;
import io.github.sx2xoox.mcpl.game.augment.support.AugmentHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The combat augments (group B): what each does, only to enemies, and that nothing is left behind. */
class CombatAugmentsTest {

    private static final double EPS = 1.0E-6;

    private AugmentHarness h;
    private List<TestPlayer> players;
    private TestPlayer a;
    private TestPlayer b;
    private TestPlayer c;
    private AugmentMatch match;
    private World world;

    @BeforeEach
    void setUp() {
        h = new AugmentHarness();
        h.noRounds();
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    /** Three players in a free-for-all fight: a at the origin looking +z, b 2 blocks in front, c 6 blocks away. */
    private void fight() {
        players = h.join(3);
        a = players.get(0);
        b = players.get(1);
        c = players.get(2);
        match = h.startFight();
        world = match.wild().world();
        place(a, 0, 0);
        place(b, 0, 2);
        place(c, 6, 0);
    }

    private Location base() {
        return world.getSpawnLocation().toCenterLocation();
    }

    private void place(TestPlayer player, double dx, double dz) {
        Location at = base().add(dx, 0, dz);
        at.setYaw(0);
        at.setPitch(0);
        player.teleport(at);
    }

    private void grant(TestPlayer player, String id) {
        match.grant(player, h.game.registry().get(id).info());
    }

    private void melee(TestPlayer attacker, TestPlayer victim, double amount) {
        victim.setNoDamageTicks(0);
        victim.damage(amount, DamageSource.builder(DamageType.PLAYER_ATTACK)
                .withCausingEntity(attacker).withDirectEntity(attacker).build());
    }

    private Arrow arrow(TestPlayer shooter) {
        return world.spawn(shooter.getEyeLocation(), Arrow.class, arrow -> {
            arrow.setShooter(shooter);
            arrow.setPickupStatus(AbstractArrow.PickupStatus.ALLOWED);
        });
    }

    private void arrowHit(TestPlayer shooter, TestPlayer victim, double amount) {
        Arrow arrow = arrow(shooter);
        victim.setNoDamageTicks(0);
        victim.damage(amount, DamageSource.builder(DamageType.ARROW)
                .withCausingEntity(shooter).withDirectEntity(arrow).build());
    }

    private EntityShootBowEvent shoot(TestPlayer shooter) {
        EntityShootBowEvent event = new EntityShootBowEvent(shooter, ItemStack.of(Material.BOW),
                ItemStack.of(Material.ARROW), arrow(shooter), EquipmentSlot.HAND, 1f, true);
        h.server.getPluginManager().callEvent(event);
        return event;
    }

    private double attribute(TestPlayer player, Attribute attribute) {
        return player.getAttribute(attribute).getValue();
    }

    private void stopMatch() {
        h.hub.games().stop(Component.text("test"));
    }

    // =================================================================== silver

    @Test
    void sharpEdgeAddsMeleeDamageOnly() {
        fight();
        grant(a, "sharp_edge");
        melee(a, b, 4);
        assertEquals(20 - 5.5, b.getHealth(), EPS);
        arrowHit(a, c, 4);
        assertEquals(16, c.getHealth(), EPS, "arrows are not melee");
    }

    @Test
    void thickSkinReducesMeleeDamageTaken() {
        fight();
        grant(b, "thick_skin");
        melee(a, b, 10);
        assertEquals(11, b.getHealth(), EPS);
        arrowHit(a, b, 4);
        assertEquals(7, b.getHealth(), EPS, "arrows are not reduced");
    }

    @Test
    void quiverGivesArrowsAndSometimesKeepsTheShotArrow() {
        h.setParam("augments", "quiver", "chance", 100);
        fight();
        grant(a, "quiver");
        assertTrue(a.getInventory().contains(Material.ARROW, 64));
        EntityShootBowEvent event = shoot(a);
        assertTrue(a.getInventory().containsAtLeast(ItemStack.of(Material.ARROW), 65), "the arrow comes back");
        AbstractArrow shot = (AbstractArrow) event.getProjectile();
        assertEquals(AbstractArrow.PickupStatus.CREATIVE_ONLY, shot.getPickupStatus(), "no duplicate by pickup");
    }

    @Test
    void quiverWithoutLuckKeepsTheShotNormal() {
        h.setParam("augments", "quiver", "chance", 0);
        fight();
        grant(a, "quiver");
        EntityShootBowEvent event = shoot(a);
        assertFalse(a.getInventory().containsAtLeast(ItemStack.of(Material.ARROW), 65));
        assertEquals(AbstractArrow.PickupStatus.ALLOWED, ((AbstractArrow) event.getProjectile()).getPickupStatus());
    }

    @Test
    void shieldMasterHurtsAttackersOfARaisedShieldInFront() {
        fight();
        place(b, 0, 0);
        place(a, 0, 2); // in front of b (b looks +z)
        grant(b, "shield_master");
        assertTrue(b.getInventory().contains(Material.SHIELD));
        melee(a, b, 1);
        assertEquals(20, a.getHealth(), EPS, "no shield raised");
        b.setBlocking(true);
        melee(a, b, 1);
        assertEquals(18, a.getHealth(), EPS, "parried");
        place(a, 0, -2); // behind b
        a.setNoDamageTicks(0);
        melee(a, b, 1);
        assertEquals(18, a.getHealth(), EPS, "a shield does not cover the back");
    }

    @Test
    void fireBladeIgnitesWithACooldown() {
        fight();
        grant(a, "fire_blade");
        melee(a, b, 1);
        assertTrue(b.getFireTicks() >= 40, "burning");
        b.setFireTicks(0);
        melee(a, b, 1);
        assertEquals(0, b.getFireTicks(), "on cooldown");
        h.ticks(4 * 20 + 1);
        melee(a, b, 1);
        assertTrue(b.getFireTicks() > 0, "ready again");
    }

    @Test
    void venomPoisonsWithACooldown() {
        fight();
        grant(a, "venom");
        melee(a, b, 1);
        PotionEffect poison = b.getPotionEffect(PotionEffectType.POISON);
        assertNotNull(poison);
        assertEquals(1, poison.getAmplifier(), "poison II");
        melee(a, c, 1);
        assertFalse(c.hasPotionEffect(PotionEffectType.POISON), "on cooldown");
    }

    // =================================================================== gold

    @Test
    void executionerHitsWeakEnemiesHarder() {
        fight();
        grant(a, "executioner");
        melee(a, b, 2);
        assertEquals(18, b.getHealth(), EPS, "healthy: normal damage");
        c.setHealth(6);
        melee(a, c, 2);
        assertEquals(6 - 2.6, c.getHealth(), EPS, "+30% below 30% health");
    }

    @Test
    void comboAddsDamageToTheThirdHitOnTheSameEnemy() {
        fight();
        grant(a, "combo");
        melee(a, b, 1);
        melee(a, c, 1);
        melee(a, b, 1);
        melee(a, b, 1);
        assertEquals(20 - 3, b.getHealth(), EPS, "switching targets restarts the chain");
        melee(a, b, 1);
        assertEquals(20 - 3 - 5, b.getHealth(), EPS, "third hit in a row: +4");
        melee(a, b, 1);
        h.ticks(3 * 20 + 1);
        melee(a, b, 1);
        melee(a, b, 1);
        assertEquals(20 - 3 - 5 - 3, b.getHealth(), EPS, "too slow: no bonus");
    }

    @Test
    void sniperRewardsLongShotsOnly() {
        fight();
        grant(a, "sniper");
        arrowHit(a, b, 4);
        assertEquals(16, b.getHealth(), EPS, "close: normal damage");
        place(c, 0, 25);
        arrowHit(a, c, 4);
        assertEquals(14, c.getHealth(), EPS, "far: +50%");
        melee(a, b, 4);
        assertEquals(12, b.getHealth(), EPS);
    }

    @Test
    void thornsReflectsMeleeDamageWithoutLooping() {
        fight();
        grant(b, "thorns");
        grant(a, "thorns");
        melee(a, b, 10);
        assertEquals(10, b.getHealth(), EPS);
        assertEquals(18, a.getHealth(), EPS, "20% back, and a's thorns do not answer augment damage");
    }

    @Test
    void heavyHitterRaisesAttackKnockbackUntilRemoved() {
        fight();
        double before = attribute(a, Attribute.ATTACK_KNOCKBACK);
        grant(a, "heavy_hitter");
        assertEquals(before + 2, attribute(a, Attribute.ATTACK_KNOCKBACK), EPS);
        stopMatch();
        assertEquals(before, attribute(a, Attribute.ATTACK_KNOCKBACK), EPS);
    }

    @Test
    void frostArrowSlowsEnemiesHitByArrows() {
        fight();
        grant(a, "frost_arrow");
        melee(a, c, 1);
        assertFalse(c.hasPotionEffect(PotionEffectType.SLOWNESS), "melee does not freeze");
        arrowHit(a, b, 2);
        PotionEffect slow = b.getPotionEffect(PotionEffectType.SLOWNESS);
        assertNotNull(slow);
        assertEquals(1, slow.getAmplifier(), "slowness II");
    }

    @Test
    void blastArrowExplodesEveryThirdArrowAroundTheHit() {
        fight();
        grant(a, "blast_arrow");
        grant(a, "sharp_edge");
        place(c, 1, 2); // next to b
        shoot(a);
        AbstractArrow second = (AbstractArrow) shoot(a).getProjectile();
        h.server.getPluginManager().callEvent(new ProjectileHitEvent(second, b, null, null));
        assertEquals(20, b.getHealth(), EPS, "an ordinary arrow");
        AbstractArrow third = (AbstractArrow) shoot(a).getProjectile();
        assertTrue(third.isGlowing(), "the blast arrow glows");
        h.server.getPluginManager().callEvent(new ProjectileHitEvent(third, b, null, null));
        assertEquals(16, b.getHealth(), EPS, "blast damage, not raised by other augments");
        assertEquals(16, c.getHealth(), EPS, "enemies around are hit too");
        assertEquals(20, a.getHealth(), EPS, "never the shooter");
    }

    @Test
    void blastArrowSparesTeammates() {
        h.hub.config().setTeamSize(2);
        h.setParam("augments", "blast_arrow", "every", 1);
        players = h.join(4);
        match = h.startFight();
        world = match.wild().world();
        GameSession session = match.session();
        a = players.getFirst();
        TestPlayer mate = players.stream().filter(p -> p != a && session.sameTeam(p, a)).findFirst().orElseThrow();
        TestPlayer enemy = players.stream().filter(p -> !session.sameTeam(p, a)).findFirst().orElseThrow();
        place(a, 0, 0);
        place(mate, 0, 6);
        place(enemy, 1, 6);
        grant(a, "blast_arrow");
        AbstractArrow arrow = (AbstractArrow) shoot(a).getProjectile();
        h.server.getPluginManager().callEvent(new ProjectileHitEvent(arrow, mate, null, null));
        assertEquals(20, mate.getHealth(), EPS, "no friendly fire");
        assertTrue(enemy.getHealth() < 20, "the enemy next to it is hit");
    }

    // =================================================================== prism

    @Test
    void giantGrowsAndSlowsUntilRemoved() {
        fight();
        double speed = attribute(a, Attribute.MOVEMENT_SPEED);
        a.setHealth(20);
        grant(a, "giant");
        assertEquals(1.3, attribute(a, Attribute.SCALE), EPS);
        assertEquals(28, attribute(a, Attribute.MAX_HEALTH), EPS);
        assertEquals(28, a.getHealth(), EPS, "healed by the new health");
        assertEquals(speed * 0.9, attribute(a, Attribute.MOVEMENT_SPEED), EPS);
        stopMatch();
        assertEquals(1, attribute(a, Attribute.SCALE), EPS);
        assertEquals(20, attribute(a, Attribute.MAX_HEALTH), EPS);
        assertEquals(speed, attribute(a, Attribute.MOVEMENT_SPEED), EPS);
    }

    @Test
    void shadowStrikeTurnsInvisibleAfterSneakingAndEmpowersOneHit() {
        fight();
        grant(a, "shadow_strike");
        a.setSneaking(true);
        h.ticks(2 * 20);
        assertFalse(a.hasPotionEffect(PotionEffectType.INVISIBILITY), "not yet");
        h.ticks(20 + 1);
        assertTrue(a.hasPotionEffect(PotionEffectType.INVISIBILITY), "after 3 s of sneaking");
        a.setSneaking(false);
        melee(a, b, 3);
        assertEquals(14, b.getHealth(), EPS, "double damage");
        assertFalse(a.hasPotionEffect(PotionEffectType.INVISIBILITY), "revealed by the strike");
        melee(a, b, 3);
        assertEquals(11, b.getHealth(), EPS, "only one hit");
        a.setSneaking(true);
        h.ticks(3 * 20 + 1);
        assertFalse(a.hasPotionEffect(PotionEffectType.INVISIBILITY), "on cooldown");
    }

    @Test
    void shadowStrikeExpiresAndCleansUp() {
        fight();
        grant(a, "shadow_strike");
        a.setSneaking(true);
        h.ticks(3 * 20 + 1);
        assertTrue(a.hasPotionEffect(PotionEffectType.INVISIBILITY));
        h.ticks(5 * 20 + 1);
        assertFalse(a.hasPotionEffect(PotionEffectType.INVISIBILITY), "the strike expires unused");
        a.setSneaking(false);
        h.ticks(1);
        a.setSneaking(true);
        h.ticks(3 * 20 + 1);
        assertTrue(a.hasPotionEffect(PotionEffectType.INVISIBILITY), "no cooldown after an unused strike");
        stopMatch();
        assertFalse(a.hasPotionEffect(PotionEffectType.INVISIBILITY), "no invisibility left after the match");
    }

    @Test
    void warGodGrowsStrongerWithEveryKill() {
        fight();
        grant(a, "war_god");
        double damage = attribute(a, Attribute.ATTACK_DAMAGE);
        a.setHealth(10);
        b.simulateDamage(1, a);
        b.setHealth(0);
        assertFalse(match.session().isAlive(b));
        assertEquals(damage + 1, attribute(a, Attribute.ATTACK_DAMAGE), EPS);
        assertEquals(14, a.getHealth(), EPS);
        stopMatch();
        assertEquals(damage, attribute(a, Attribute.ATTACK_DAMAGE), EPS);
    }

    @Test
    void fortressAddsArmorToughnessAndKnockbackResistance() {
        fight();
        grant(a, "fortress");
        assertEquals(4, attribute(a, Attribute.ARMOR), EPS);
        assertEquals(4, attribute(a, Attribute.ARMOR_TOUGHNESS), EPS);
        assertEquals(0.5, attribute(a, Attribute.KNOCKBACK_RESISTANCE), EPS);
        stopMatch();
        assertEquals(0, attribute(a, Attribute.ARMOR), EPS);
        assertEquals(0, attribute(a, Attribute.KNOCKBACK_RESISTANCE), EPS);
    }

    @Test
    void combatAugmentsDoNothingToPlayersDuringTheFarming() {
        h.setParam("augments", "blast_arrow", "every", 1);
        players = h.join(3);
        a = players.get(0);
        b = players.get(1);
        match = h.startFarming();
        world = match.wild().world();
        place(a, 0, 0);
        place(b, 0, 2);
        grant(a, "blast_arrow");
        AbstractArrow arrow = (AbstractArrow) shoot(a).getProjectile();
        h.server.getPluginManager().callEvent(new ProjectileHitEvent(arrow, b, null, null));
        assertEquals(20, b.getHealth(), EPS, "no PvP yet");
    }
}
