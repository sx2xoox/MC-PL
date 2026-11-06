package io.github.sx2xoox.mcpl.game.augment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import io.github.sx2xoox.mcpl.game.augment.augments.BlinkAugment;
import io.github.sx2xoox.mcpl.game.augment.runtime.AugmentMatch;
import io.github.sx2xoox.mcpl.game.augment.runtime.Offer;
import io.github.sx2xoox.mcpl.game.augment.runtime.OfferMenu;
import io.github.sx2xoox.mcpl.game.augment.support.AugmentHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Group C augments: movement, information, tricks and gambles. */
class TrickAugmentsTest {

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

    private AugmentInfo info(String id) {
        return h.game.registry().get(id).info();
    }

    private void grant(TestPlayer player, String id) {
        match.grant(player, info(id));
    }

    private World wild() {
        return match.wild().world();
    }

    /** Puts a facing +z and b a few blocks away on the floor of the match world. */
    private void place() {
        a.teleport(new Location(wild(), 0.5, FlatWorld.SURFACE, 0.5, 0f, 0f));
        b.teleport(new Location(wild(), 5.5, FlatWorld.SURFACE, 0.5));
    }

    private boolean swapHands(TestPlayer player) {
        PlayerSwapHandItemsEvent event = new PlayerSwapHandItemsEvent(player, ItemStack.empty(), ItemStack.empty());
        h.server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    private void stop() {
        h.hub.games().stop(Component.text("test"));
    }

    @Test
    void swimmerBreathesAndSwimsFasterUntilRemoved() {
        match = h.startFarming();
        grant(a, "swimmer");
        assertEquals(0.6, a.getAttribute(Attribute.WATER_MOVEMENT_EFFICIENCY).getValue(), 1.0E-6);
        assertTrue(a.hasPotionEffect(PotionEffectType.WATER_BREATHING));
        a.removePotionEffect(PotionEffectType.WATER_BREATHING);
        h.ticks(20);
        assertTrue(a.hasPotionEffect(PotionEffectType.WATER_BREATHING), "back after milk");
        stop();
        assertFalse(a.hasPotionEffect(PotionEffectType.WATER_BREATHING));
        assertEquals(0, a.getAttribute(Attribute.WATER_MOVEMENT_EFFICIENCY).getValue(), 1.0E-6);
    }

    @Test
    void highJumpJumpsHigherAndHalvesFallDamage() {
        match = h.startFarming();
        double before = a.getAttribute(Attribute.JUMP_STRENGTH).getValue();
        grant(a, "high_jump");
        assertEquals(before * 1.25, a.getAttribute(Attribute.JUMP_STRENGTH).getValue(), 1.0E-6);
        a.damage(8, DamageSource.builder(DamageType.FALL).build());
        assertEquals(16, a.getHealth(), 1.0E-6);
    }

    @Test
    void itemAugmentsHandOutTheirItems() {
        match = h.startFarming();
        grant(a, "pearls");
        grant(a, "cobweb_trap");
        grant(a, "wings");
        assertTrue(a.getInventory().contains(Material.ENDER_PEARL, 4));
        assertTrue(a.getInventory().contains(Material.COBWEB, 12));
        assertTrue(a.getInventory().contains(Material.ELYTRA));
        assertTrue(a.getInventory().contains(Material.FIREWORK_ROCKET, 8));
    }

    @Test
    void hunterCompassPointsAtTheNearestEnemyOnlyDuringTheFight() {
        match = h.startFarming();
        Location original = a.getCompassTarget();
        grant(a, "hunter_compass");
        assertTrue(a.getInventory().contains(Material.COMPASS));
        place();
        h.ticks(40);
        assertEquals(original, a.getCompassTarget(), "not while farming");

        h.tickUntil(() -> match.phase() == io.github.sx2xoox.mcpl.game.augment.runtime.MatchPhase.FIGHT, 400,
                "fight");
        place();
        h.ticks(20);
        assertEquals(5.5, a.getCompassTarget().getX(), 1.0E-6, "points at b");
        stop();
        assertEquals(original, a.getCompassTarget(), "restored");
    }

    @Test
    void footstepsShowNearbyEnemiesToTheOwnerOnly() {
        match = h.startFarming();
        grant(a, "footsteps");
        place();
        a.particles().clear();
        b.particles().clear();
        h.ticks(60);
        assertTrue(a.particles().stream().anyMatch(p -> p.particle() == Particle.DUST
                && Math.abs(p.at().getX() - 5.5) < 1.0E-6), "a step at b's feet");
        assertTrue(b.particles().isEmpty(), "only the owner sees them");

        b.teleport(new Location(wild(), 40.5, FlatWorld.SURFACE, 0.5));
        a.particles().clear();
        h.ticks(60);
        assertTrue(a.particles().isEmpty(), "out of range");
    }

    @Test
    void blinkTeleportsForwardButNotThroughWalls() {
        match = h.startFarming();
        grant(a, "blink");
        place();
        b.teleport(new Location(wild(), 30.5, FlatWorld.SURFACE, 0.5));
        assertFalse(swapHands(a), "a normal hand swap while standing");
        a.setSneaking(true);
        assertTrue(swapHands(a), "used by the blink");
        assertEquals(8.5, a.getLocation().getZ(), 1.0E-6, "8 blocks forward");
        assertEquals(FlatWorld.SURFACE, a.getLocation().getY(), 1.0E-6);

        a.teleport(new Location(wild(), 0.5, FlatWorld.SURFACE, 0.5, 0f, 0f));
        assertTrue(swapHands(a));
        assertEquals(0.5, a.getLocation().getZ(), 1.0E-6, "on cooldown");
        h.ticks(12 * 20 + 1);
        wild().getBlockAt(0, (int) FlatWorld.SURFACE + 1, 4).setType(Material.STONE);
        assertTrue(swapHands(a));
        assertTrue(a.getLocation().getZ() < 4 - 0.3 + 1.0E-6 && a.getLocation().getZ() > 2,
                "stopped in front of the wall: " + a.getLocation().getZ());

        Location here = new Location(wild(), 0.5, FlatWorld.SURFACE, 0.5, 0f, 90f);
        assertNull(BlinkAugment.destination(here, 8), "looking at the floor");
    }

    @Test
    void gamblerGrantsTwoRandomAugments() {
        match = h.startFarming();
        grant(a, "gambler");
        List<AugmentInfo> owned = match.augments(a.getUniqueId());
        assertEquals(3, owned.size(), "gambler and two random ones: " + owned);
        assertEquals("gambler", owned.getFirst().id());
    }

    @Test
    void stillCloakHidesAStillPlayerUntilTheyMoveOrGetHurt() {
        match = h.startFarming();
        place();
        grant(a, "still_cloak");
        h.ticks(2 * 20);
        assertFalse(a.hasPotionEffect(PotionEffectType.INVISIBILITY));
        h.ticks(20 + 1);
        assertTrue(a.hasPotionEffect(PotionEffectType.INVISIBILITY), "hidden after standing still");

        a.teleport(a.getLocation().add(1, 0, 0));
        h.ticks(1);
        assertFalse(a.hasPotionEffect(PotionEffectType.INVISIBILITY), "moving reveals");

        h.ticks(3 * 20 + 1);
        assertTrue(a.hasPotionEffect(PotionEffectType.INVISIBILITY));
        a.damage(1, DamageSource.builder(DamageType.LAVA).build());
        assertFalse(a.hasPotionEffect(PotionEffectType.INVISIBILITY), "getting hurt reveals");

        h.ticks(3 * 20 + 1);
        stop();
        assertFalse(a.hasPotionEffect(PotionEffectType.INVISIBILITY), "nothing left after the match");
    }

    @Test
    void sprinterGivesSpeedAfterSprintingForAWhile() {
        match = h.startFarming();
        grant(a, "sprinter");
        a.setSprinting(true);
        h.ticks(2 * 20);
        assertFalse(a.hasPotionEffect(PotionEffectType.SPEED));
        h.ticks(20 + 1);
        PotionEffect speed = a.getPotionEffect(PotionEffectType.SPEED);
        assertNotNull(speed, "speed after 3 s of sprinting");
        assertEquals(1, speed.getAmplifier(), "speed II");
        a.setSprinting(false);
        h.ticks(40);
        assertFalse(a.hasPotionEffect(PotionEffectType.SPEED), "fades after the sprint");
    }

    @Test
    void luckyCoinAndCollectorShowMoreCards() {
        h.chestMenu();
        match = h.startFarming();
        int rerolls = match.rerollsLeft(a.getUniqueId());
        grant(a, "lucky_coin");
        assertEquals(rerolls + 1, match.rerollsLeft(a.getUniqueId()));
        match.offer(a, AugmentTier.SILVER, Component.text("test"));
        Offer offer = match.currentOffer(a.getUniqueId());
        assertEquals(4, offer.cards().size());
        OfferMenu menu = match.menu(a.getUniqueId());
        assertNotNull(menu);
        assertEquals(10, menu.slotOf(offer.cards().getFirst().id()));
        assertEquals(16, menu.slotOf(offer.cards().getLast().id()));
        match.pick(a, offer.cards().getFirst());

        grant(a, "collector");
        Offer gold = match.currentOffer(a.getUniqueId());
        assertNotNull(gold, "collector offers a gold augment at once");
        assertEquals(AugmentTier.GOLD, gold.tier());
        assertEquals(5, gold.cards().size(), "lucky coin + collector");
        assertEquals(17, match.menu(a.getUniqueId()).slotOf(gold.cards().getLast().id()));
    }

    @Test
    void rewindGoesBackInTimeWithTheOldHealth() {
        match = h.startFarming();
        place();
        grant(a, "rewind");
        h.ticks(5 * 20);
        a.teleport(new Location(wild(), 20.5, FlatWorld.SURFACE, 20.5));
        a.damage(10, DamageSource.builder(DamageType.LAVA).build());
        h.ticks(10);
        a.setSneaking(true);
        assertFalse(swapHands(a), "crouching swaps hands (that is the blink's control)");
        a.setSneaking(false);
        assertTrue(swapHands(a));
        assertEquals(0.5, a.getLocation().getX(), 1.0E-6, "back where a was 5 s ago");
        assertEquals(0.5, a.getLocation().getZ(), 1.0E-6);
        assertEquals(20, a.getHealth(), 1.0E-6, "with the health of then");

        a.teleport(new Location(wild(), 20.5, FlatWorld.SURFACE, 20.5));
        h.ticks(6 * 20);
        assertTrue(swapHands(a));
        assertEquals(20.5, a.getLocation().getX(), 1.0E-6, "on cooldown");
    }

    @Test
    void clairvoyanceOutlinesEnemiesForTheOwnerOnly() {
        match = h.startFight();
        place();
        grant(a, "clairvoyance");
        h.ticks(1);
        List<BlockDisplay> boxes = wild().getEntitiesByClass(BlockDisplay.class).stream().toList();
        assertEquals(2, boxes.size(), "one per enemy (b and the third player)");
        for (BlockDisplay box : boxes) {
            assertTrue(box.isGlowing());
            assertTrue(a.canSee(box), "the owner sees it");
            assertFalse(b.canSee(box), "nobody else does");
            assertTrue(match.session().isMatchEntity(box));
        }
        b.teleport(new Location(wild(), 7.5, FlatWorld.SURFACE, 3.5));
        h.ticks(1);
        assertTrue(wild().getEntitiesByClass(BlockDisplay.class).stream()
                .anyMatch(box -> Math.abs(box.getLocation().getX() - 7.5) < 1.0E-6), "follows b");
        h.ticks(3 * 20);
        assertTrue(wild().getEntitiesByClass(BlockDisplay.class).isEmpty(), "gone after 3 s");
        h.ticks(17 * 20);
        assertEquals(2, wild().getEntitiesByClass(BlockDisplay.class).size(), "again after 20 s");
        stop();
        assertTrue(boxes.stream().noneMatch(BlockDisplay::isValid));
    }

    @Test
    void vengefulWithersAndRevealsTheKiller() {
        match = h.startFight();
        grant(a, "vengeful");
        a.simulateDamage(1, b);
        a.setHealth(0);
        assertFalse(match.session().isAlive(a));
        PotionEffect wither = b.getPotionEffect(PotionEffectType.WITHER);
        assertNotNull(wither, "the killer withers");
        assertEquals(1, wither.getAmplifier());
        assertEquals(10 * 20, wither.getDuration());
        assertTrue(b.hasPotionEffect(PotionEffectType.GLOWING), "and glows for everyone");
    }
}
