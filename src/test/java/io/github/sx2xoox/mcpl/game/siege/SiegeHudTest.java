package io.github.sx2xoox.mcpl.game.siege;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import io.github.sx2xoox.mcpl.core.game.GameStartException;
import io.github.sx2xoox.mcpl.core.map.siege.SiegeSites;
import io.github.sx2xoox.mcpl.core.ui.ManagedBossBar;
import io.github.sx2xoox.mcpl.game.siege.tower.Tower;
import io.github.sx2xoox.mcpl.game.superpower.support.MatchHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.Scenario;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import io.github.sx2xoox.mcpl.game.superpower.ui.AbilityItems;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The 공성전 screen: tower bars by relevance, name plates, the ability sword, the way to the target, districts. */
class SiegeHudTest {

    private MatchHarness h;
    private SiegeGame siege;
    private ConfigurationSection config;

    @BeforeEach
    void setUp() {
        h = MatchHarness.flat();
        h.registerDummy();
        h.registerTestAbilities();
        h.settings().set("ultimate.per-second", 0);
        siege = (SiegeGame) h.hub.games().game(SiegeGame.ID);
        assertNotNull(siege);
        siege.sitesOverride(new Districted((int) FlatWorld.SURFACE));
        config = siege.config();
        config.set(SiegeSettings.ABILITY_POOL, List.of("dummy"));
        config.set(SiegeSettings.ROUND_PREP_SECONDS, 1);
        config.set(SiegeSettings.HALF_PREP_SECONDS, 1);
        config.set(SiegeSettings.SPAWN_ZONE_DAMAGE, 0);
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    /** The fallback layout with three districts across x (west, middle, east). */
    private static final class Districted implements SiegeSites {
        private final FallbackSiegeSites base;

        Districted(int feetY) {
            this.base = new FallbackSiegeSites(feetY);
        }

        @Override
        public List<Spot> attackerSpawns() {
            return base.attackerSpawns();
        }

        @Override
        public List<List<Spot>> defenderSpawns() {
            return base.defenderSpawns();
        }

        @Override
        public List<Tower> towers() {
            return base.towers();
        }

        @Override
        public List<District> districts() {
            return List.of(new District("west", "서쪽 구역", -1000, -10), new District("middle", "가운데", -9, 9),
                    new District("east", "동쪽 구역", 10, 1000));
        }
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
        h.ticks(11);
        return siege.currentMatch();
    }

    private Player member(SiegeMatch match, int team, int index) {
        UUID id = List.copyOf(match.teams().get(team).members()).get(index);
        return h.server.getPlayer(id);
    }

    private static Tower tower(SiegeMatch match, String id) {
        return match.towers().stream().filter(tower -> tower.id().equals(id)).findFirst().orElseThrow();
    }

    private static SiegeHud hud(SiegeMatch match) {
        SiegeHud hud = match.hud();
        assertNotNull(hud);
        return hud;
    }

    /** Stands {@code distance} blocks in front of the tower (towards the attackers' side), looking at it or up. */
    private static void stand(Player player, Tower tower, double distance, boolean looking) {
        Location at = tower.base().add(0, 0, -distance);
        at.setYaw(0f);
        at.setPitch(looking ? 0f : -90f);
        player.teleport(at);
    }

    // ------------------------------------------------------------------ tower bars

    @Test
    void towerBarRule() {
        SiegeHudSettings settings = SiegeHudSettings.DEFAULTS;
        assertTrue(TowerBars.visible(10, false, Long.MAX_VALUE, settings), "near");
        assertFalse(TowerBars.visible(40, false, Long.MAX_VALUE, settings), "far, not looking, not hit");
        assertTrue(TowerBars.visible(40, true, Long.MAX_VALUE, settings), "looking at it");
        assertTrue(TowerBars.visible(40, false, 60, settings), "hit three seconds ago");
        assertFalse(TowerBars.visible(40, false, 120, settings), "hit six seconds ago");
    }

    @Test
    void towerBarsShowOnlyToPlayersTheTowerMattersTo() {
        SiegeMatch match = start(2);
        Player attacker = member(match, 0, 0);
        Tower front = tower(match, "front");
        ManagedBossBar bar = front.bar();
        assertNotNull(bar);

        stand(attacker, front, 45, false);
        h.ticks(11);
        assertFalse(bar.isShownTo(attacker), "far away and looking elsewhere: no tower bar");
        ManagedBossBar timer = hud(match).timer(attacker.getUniqueId());
        assertNotNull(timer);
        assertTrue(timer.isShownTo(attacker), "the timer bar always shows");

        stand(attacker, front, 45, true);
        h.ticks(11);
        assertTrue(bar.isShownTo(attacker), "looking at the tower shows its bar");

        stand(attacker, front, 20, false);
        h.ticks(11);
        assertTrue(bar.isShownTo(attacker), "near the tower");

        stand(attacker, front, 45, false);
        h.ticks(11);
        assertFalse(bar.isShownTo(attacker));
        match.hitTower(front, attacker, 1);
        h.ticks(11);
        assertTrue(bar.isShownTo(attacker), "hit it just now");
        h.ticks(6 * 20);
        assertFalse(bar.isShownTo(attacker), "the hit is a while ago");

        match.abilities().context().damage(attacker, front.hitbox(), 100_000);
        assertTrue(front.isDestroyed());
        stand(attacker, front, 5, true);
        h.ticks(11);
        assertFalse(bar.isShownTo(attacker), "a destroyed tower has no bar");
    }

    // ------------------------------------------------------------------ name plates

    @Test
    void plateText() {
        SiegeMatch match = start(2);
        Component plate = NamePlates.plate(3, 14, 20, NamedTextColor.RED, match.messages());
        assertEquals("Lv.3 ▮▮▮▮▮▮▮▯▯▯ 14", Scenario.plain(plate));
        assertEquals("▮▯▯▯▯▯▯▯▯▯ 1", Scenario.plain(NamePlates.plate(0, 0.4, 20, NamedTextColor.RED,
                match.messages())), "no level: bar and health only; one segment while alive");
        assertEquals(0, NamePlates.filled(0, 20));
        assertEquals(10, NamePlates.filled(24, 20), "absorption caps at a full bar");
    }

    @Test
    void platesShowToOthersNeverToSelfAndNeverGiveAwayAConcealedPlayer() {
        SiegeMatch match = start(4);
        Player a1 = member(match, 0, 0);
        Player a2 = member(match, 0, 1);
        Player b1 = member(match, 1, 0);
        h.ticks(11);
        assertTrue(plateShown(b1, a1), "an enemy sees the plate");
        assertTrue(plateShown(a2, a1), "a teammate sees the plate");
        assertFalse(plateShown(a1, a1), "never your own");
        assertTrue(Scenario.plain(hud(match).plates().plate(a1)).startsWith("Lv." + match.level(a1.getUniqueId())
                + " "), "the in-match level is on the plate");

        Score score = plate(b1, a1);
        a1.setHealth(10);
        match.healthChanged(a1);
        h.ticks(1);
        assertEquals(10, score.getScore(), "updated on the next tick after a health change");

        a1.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 400, 0));
        h.ticks(11);
        assertFalse(plateShown(b1, a1), "an invisible player's plate is gone for enemies");
        assertTrue(plateShown(a2, a1), "teammates still see it");
        a1.removePotionEffect(PotionEffectType.INVISIBILITY);
        h.ticks(11);
        assertTrue(plateShown(b1, a1));

        b1.hidePlayer(h.hub.plugin(), a1);
        h.ticks(11);
        assertFalse(plateShown(b1, a1), "hidden by stealth: no plate");
        b1.showPlayer(h.hub.plugin(), a1);
        h.ticks(11);
        assertTrue(plateShown(b1, a1));
    }

    private static Score plate(Player viewer, Player target) {
        Objective objective = viewer.getScoreboard().getObjective(NamePlates.OBJECTIVE);
        assertNotNull(objective, "the viewer's board has the plate objective");
        assertEquals(DisplaySlot.BELOW_NAME, objective.getDisplaySlot());
        return objective.getScore(target.getName());
    }

    private static boolean plateShown(Player viewer, Player target) {
        return plate(viewer, target).isScoreSet();
    }

    // ------------------------------------------------------------------ the ability sword

    @Test
    void theSwordFightsAndCasts() {
        config.set(SiegeProgressSettings.LEVELS_ENABLED, false);
        SiegeMatch match = start(2);
        Player attacker = member(match, 0, 0);
        Player defender = member(match, 1, 0);
        AbilityItems items = siege.superpower().items();
        ItemStack sword = attacker.getInventory().getItem(AbilityItems.WEAPON_SLOT);
        assertNotNull(sword);
        assertTrue(items.isAbilityItem(sword), "the sword is the ability item");
        assertEquals(Material.IRON_SWORD, sword.getType(), "it is the kit's sword");
        assertEquals(Material.IRON_SWORD.getKey(), items.weaponModelOf(sword),
                "without the pack: a vanilla sword by difficulty");
        assertFalse(items.isAbilityItem(attacker.getInventory().getItem(AbilityItems.SLOT)), "no iron ingot");
        long swords = java.util.Arrays.stream(attacker.getInventory().getContents())
                .filter(item -> item != null && Tag.ITEMS_SWORDS.isTagged(item.getType())).count();
        assertEquals(1, swords, "the kit's sword is not given twice");

        attacker.getInventory().setHeldItemSlot(AbilityItems.WEAPON_SLOT);
        h.server.getPluginManager().callEvent(new PlayerInteractEvent(attacker, Action.RIGHT_CLICK_AIR, sword,
                null, BlockFace.SELF, EquipmentSlot.HAND));
        assertTrue(attacker.getCooldown(items.cooldownGroup()) > 0, "right-click casts the active skill");

        double before = defender.getHealth();
        defender.setNoDamageTicks(0);
        defender.damage(5, DamageSource.builder(DamageType.PLAYER_ATTACK).withCausingEntity(attacker)
                .withDirectEntity(attacker).build());
        assertEquals(before - 5, defender.getHealth(), 1.0E-6, "a sword hit still hurts");
    }

    // ------------------------------------------------------------------ way-finding

    @Test
    void arrowsFollowTheView() {
        assertEquals("↑", Wayfinding.arrow(0f, 0, 10), "facing +z, target ahead");
        assertEquals("→", Wayfinding.arrow(0f, -10, 0), "facing +z, -x is to the right");
        assertEquals("←", Wayfinding.arrow(0f, 10, 0));
        assertEquals("↓", Wayfinding.arrow(0f, 0, -10));
        assertEquals("↑", Wayfinding.arrow(90f, -10, 0), "facing -x, target at -x");
        assertEquals("↗", Wayfinding.arrow(0f, -10, 10));
        assertEquals("●", Wayfinding.arrow(0f, 1, 0), "standing on it");
    }

    @Test
    void thePointerBeamAndFrameMarkTheTarget() {
        SiegeMatch match = start(2);
        Player attacker = member(match, 0, 0);
        Player defender = member(match, 1, 0);
        Tower front = tower(match, "front");
        stand(attacker, front, 42, true);
        h.ticks(11);
        Component pointer = hud(match).wayfinding().pointer(attacker);
        assertNotNull(pointer);
        assertEquals("▶ " + front.name() + " 42m ↑", Scenario.plain(pointer));
        ManagedBossBar timer = hud(match).timer(attacker.getUniqueId());
        assertNotNull(timer);
        assertTrue(Scenario.plain(timer.bar().name()).endsWith("▶ " + front.name() + " 42m ↑"),
                "the pointer is in the timer bar");
        assertTrue(Scenario.plain(hud(match).wayfinding().pointer(defender)).startsWith("🛡 " + front.name()));

        BlockDisplay beam = hud(match).wayfinding().beam();
        assertNotNull(beam);
        assertTrue(beam.isValid() && beam.isGlowing(), "a glowing beam over the target");
        assertTrue(beam.getLocation().getY() > front.base().getY());
        List<BlockDisplay> frame = hud(match).wayfinding().frame();
        assertEquals(12, frame.size());
        assertTrue(attacker.canSee(frame.getFirst()), "the attackers see the frame");
        assertFalse(defender.canSee(frame.getFirst()), "the defenders do not");

        match.abilities().context().damage(attacker, front.hitbox(), 100_000);
        h.ticks(11);
        assertFalse(beam.isValid(), "the old beam goes with the tower");
        BlockDisplay next = hud(match).wayfinding().beam();
        assertNotNull(next);
        assertEquals(tower(match, "middle").base().getZ(), next.getLocation().getZ(), 0.6, "over the next tower");
    }

    @Test
    void enteringADistrictShowsItsName() {
        SiegeMatch match = start(2);
        Player attacker = member(match, 0, 0);
        Wayfinding way = hud(match).wayfinding();
        assertNotNull(way.districtOf(attacker.getUniqueId()));
        assertEquals("middle", way.districtOf(attacker.getUniqueId()).id());
        attacker.teleport(attacker.getLocation().add(25, 0, 0));
        h.ticks(11);
        assertEquals("east", way.districtOf(attacker.getUniqueId()).id());

        match.title(Component.text("x"), Component.empty());
        attacker.teleport(attacker.getLocation().add(-25, 0, 0));
        h.ticks(11);
        assertEquals("middle", way.districtOf(attacker.getUniqueId()).id(), "still tracked");
        assertTrue(way.isQuiet(attacker), "another title is on screen: the district title waits");

        attacker.setHealth(0);
        h.ticks(11);
        assertNull(way.districtOf(attacker.getUniqueId()), "dead players start over");
    }
}
