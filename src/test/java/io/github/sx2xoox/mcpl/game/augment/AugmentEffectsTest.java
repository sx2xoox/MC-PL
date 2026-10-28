package io.github.sx2xoox.mcpl.game.augment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import io.github.sx2xoox.mcpl.game.augment.runtime.AugmentMatch;
import io.github.sx2xoox.mcpl.game.augment.support.AugmentHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** What each built-in augment does, and that it leaves nothing behind. */
class AugmentEffectsTest {

    private AugmentHarness h;
    private TestPlayer a;
    private TestPlayer b;
    private AugmentMatch match;

    @BeforeEach
    void setUp() {
        h = new AugmentHarness();
        h.noRounds();
        List<TestPlayer> players = h.join(3);
        a = players.get(0);
        b = players.get(1);
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    private void grant(TestPlayer player, String id) {
        match.grant(player, h.game.registry().get(id).info());
    }

    private double maxHealth(TestPlayer player) {
        return player.getAttribute(Attribute.MAX_HEALTH).getValue();
    }

    @Test
    void ironBodyAndBerserkerChangeMaxHealthUntilTheMatchEnds() {
        match = h.startFarming();
        a.setHealth(16);
        grant(a, "iron_body");
        assertEquals(24, maxHealth(a), 1.0E-6);
        assertEquals(20, a.getHealth(), 1.0E-6, "healed by the new health");
        grant(b, "berserker_pact");
        assertEquals(16, maxHealth(b), 1.0E-6);
        assertTrue(b.getHealth() <= 16);
        grant(a, "iron_body");
        assertEquals(1, match.augments(a.getUniqueId()).size(), "never twice");

        h.hub.games().stop(Component.text("test"));
        assertEquals(20, maxHealth(a), 1.0E-6, "modifiers are removed");
        assertEquals(20, maxHealth(b), 1.0E-6);
    }

    @Test
    void galeMakesFasterAndResourcesAreHandedOut() {
        match = h.startFarming();
        double before = a.getAttribute(Attribute.MOVEMENT_SPEED).getValue();
        grant(a, "gale");
        assertEquals(before * 1.15, a.getAttribute(Attribute.MOVEMENT_SPEED).getValue(), 1.0E-6);
        grant(a, "iron_bundle");
        assertTrue(a.getInventory().contains(Material.IRON_INGOT, 12));
        assertTrue(a.getInventory().contains(Material.BREAD, 6));
        grant(a, "diamond_vein");
        assertTrue(a.getInventory().contains(Material.DIAMOND, 5));
        grant(a, "triple_shot");
        assertTrue(a.getInventory().contains(Material.BOW));
        assertTrue(a.getInventory().contains(Material.ARROW, 32));
    }

    @Test
    void diamondVeinDropsExtraDiamonds() {
        match = h.startFarming();
        grant(a, "diamond_vein");
        Block ore = match.wild().world().getBlockAt(3, 30, 3);
        ore.setType(Material.DIAMOND_ORE);
        long before = match.wild().world().getEntitiesByClass(org.bukkit.entity.Item.class).size();
        h.server.getPluginManager().callEvent(new org.bukkit.event.block.BlockBreakEvent(ore, a));
        assertEquals(before + 1, match.wild().world().getEntitiesByClass(org.bukkit.entity.Item.class).size(),
                "one more diamond on the ground");
    }

    @Test
    void featherFallCancelsFallDamage() {
        match = h.startFarming();
        grant(a, "feather_fall");
        a.damage(6, DamageSource.builder(DamageType.FALL).build());
        assertEquals(20, a.getHealth(), 1.0E-6);
        a.damage(6, DamageSource.builder(DamageType.LAVA).build());
        assertTrue(a.getHealth() < 20, "other damage still hurts");
    }

    @Test
    void berserkerHitsHarderAndBloodthirstHealsOnlyAgainstEnemies() {
        match = h.startFight();
        grant(a, "berserker_pact");
        b.simulateDamage(4, a);
        assertEquals(15, b.getHealth(), 1.0E-6, "+25% damage");

        grant(b, "bloodthirst");
        b.setHealth(10);
        a.simulateDamage(10, b);
        assertEquals(12, b.getHealth(), 1.0E-6, "20% of 10 healed");
    }

    @Test
    void phoenixFeatherSavesOnce() {
        match = h.startFight();
        grant(a, "phoenix_feather");
        a.damage(100, DamageSource.builder(DamageType.LAVA).build());
        assertFalse(a.isDead(), "saved");
        assertEquals(10, a.getHealth(), 1.0E-6);
        assertTrue(a.hasPotionEffect(PotionEffectType.RESISTANCE));
        assertTrue(match.session().isAlive(a));
        a.removePotionEffect(PotionEffectType.RESISTANCE);
        a.setNoDamageTicks(0);
        a.damage(100, DamageSource.builder(DamageType.LAVA).build());
        assertFalse(match.session().isAlive(a), "only once");
    }

    @Test
    void doubleJumpLeapsAndCleansUpFlight() {
        match = h.startFarming();
        grant(a, "double_jump");
        h.ticks(1);
        assertTrue(a.getAllowFlight(), "ready on the ground");
        h.server.getPluginManager().callEvent(new org.bukkit.event.player.PlayerToggleFlightEvent(a, true));
        assertFalse(a.isFlying(), "a leap, not flight");
        assertFalse(a.getAllowFlight(), "used until it recharges");
        assertTrue(a.getVelocity().getY() > 0.5);
        h.ticks(3 * 20 + 1);
        assertTrue(a.getAllowFlight(), "recharged after the cooldown on the ground");
        h.hub.games().stop(Component.text("test"));
        assertFalse(a.getAllowFlight(), "no flight left after the match");
    }

    @Test
    void tripleShotFiresTwoExtraArrowsThatCannotBePickedUp() {
        match = h.startFight();
        grant(a, "triple_shot");
        Arrow main = match.wild().world().spawn(a.getEyeLocation(), Arrow.class);
        main.setVelocity(new Vector(0, 0, 2));
        main.setDamage(4);
        EntityShootBowEvent event = new EntityShootBowEvent(a, ItemStack.of(Material.BOW), ItemStack.of(Material.ARROW),
                main, EquipmentSlot.HAND, 1f, true);
        h.server.getPluginManager().callEvent(event);
        List<Arrow> arrows = match.wild().world().getEntitiesByClass(Arrow.class).stream()
                .filter(arrow -> arrow != main).toList();
        assertEquals(2, arrows.size(), "two extra arrows");
        for (Arrow extra : arrows) {
            assertEquals(AbstractArrow.PickupStatus.DISALLOWED, extra.getPickupStatus());
            assertEquals(4 * 0.6, extra.getDamage(), 1.0E-6);
            assertTrue(match.session().isMatchEntity(extra), "removed with the match");
        }
    }

    @Test
    void cardsComeFromNeighbouringTiersWhenATierRunsOut() {
        match = h.startFarming();
        for (AugmentInfo info : h.game.registry().ofTier(AugmentTier.PRISM)) {
            grant(a, info.id());
        }
        match.offer(a, AugmentTier.PRISM, Component.text("test"));
        List<AugmentInfo> cards = match.currentOffer(a.getUniqueId()).cards();
        assertEquals(3, cards.size());
        assertTrue(cards.stream().allMatch(card -> card.tier() == AugmentTier.GOLD), "prism exhausted → gold");
        GameSession session = match.session();
        assertTrue(session.isRunning());
    }
}
