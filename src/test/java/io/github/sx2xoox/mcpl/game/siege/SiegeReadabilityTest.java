package io.github.sx2xoox.mcpl.game.siege;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import io.github.sx2xoox.mcpl.core.game.GameStartException;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import io.github.sx2xoox.mcpl.game.siege.tower.Tower;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.support.MatchHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.Scenario;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import io.github.sx2xoox.mcpl.game.superpower.ui.AbilityItems;
import io.github.sx2xoox.mcpl.game.superpower.ui.AbilityWeaponModels;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
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
import org.mockbukkit.mockbukkit.sound.AudioExperience;

/**
 * What 공성전 shows and plays (docs/siege.md "화면 표시", "소리"): overhead bars instead of tower boss bars, one timer
 * boss bar, floating damage numbers, the "▶ 지금 할 일" line, the start tutorial, the name plates and the sounds.
 */
class SiegeReadabilityTest {

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
        siege.sitesOverride(new FallbackLaneSites((int) FlatWorld.SURFACE));
        config = siege.config();
        config.set(SiegeSettings.ABILITY_POOL, List.of("dummy", "probe"));
        config.set(SiegeSettings.ROUND_PREP_SECONDS, 1);
        config.set(SiegeSettings.SPAWN_ZONE_DAMAGE, 0);
        config.set(SiegeSettings.ATTACK_DAMAGE, 0);
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
                2000, "the fight to start");
        return siege.currentMatch();
    }

    private static Player member(SiegeMatch match, int team, int index) {
        Iterator<UUID> members = match.teams().get(team).members().iterator();
        for (int i = 0; i < index; i++) {
            members.next();
        }
        return match.session().hub().plugin().getServer().getPlayer(members.next());
    }

    private static Tower enemyOuter(SiegeMatch match, Player player) {
        LaneSites.Side side = match.side(player.getUniqueId());
        return SiegeMatchTest.structure(match, side == LaneSites.Side.BLUE ? LaneSites.Side.RED
                : LaneSites.Side.BLUE, Tower.Kind.OUTER, LaneSites.Lane.TOP);
    }

    /** Whether {@code player} heard the sound with this key ({@code block.bell.use}). */
    private static boolean heard(Player player, String sound) {
        return ((TestPlayer) player).getHeardSounds().stream().map(AudioExperience::getSound)
                .anyMatch(name -> name.endsWith(sound));
    }

    private static List<String> chat(Player player) {
        List<String> lines = new ArrayList<>();
        Component next;
        while ((next = ((TestPlayer) player).nextComponentMessage()) != null) {
            lines.add(Scenario.plain(next));
        }
        return lines;
    }

    private static List<String> actionBars(Player player) {
        List<String> bars = new ArrayList<>();
        Component next;
        while ((next = ((TestPlayer) player).nextActionBar()) != null) {
            bars.add(Scenario.plain(next));
        }
        return bars;
    }

    // ------------------------------------------------------------------ overhead bars

    @Test
    void overheadBarText() {
        assertEquals(20, OverheadBar.filled(1, 20));
        assertEquals(19, OverheadBar.filled(0.99, 20), "never full while damaged");
        assertEquals(1, OverheadBar.filled(0.001, 20), "one segment while anything is left");
        assertEquals(0, OverheadBar.filled(0, 20));
        Component text = OverheadBar.render(Component.text("바깥 타워"), 750, 1500, NamedTextColor.BLUE, false, true);
        assertEquals("바깥 타워\n" + "|".repeat(20) + " 750", Scenario.plain(text));
        assertEquals("🛡 코어\n" + "|".repeat(20), Scenario.plain(OverheadBar.render(Component.text("코어"), 10, 10,
                NamedTextColor.RED, true, false)));
    }

    @Test
    void structuresShowOverheadBarsAndTheOnlyBossBarIsTheTimer() {
        SiegeMatch match = start(2);
        Player a = member(match, 0, 0);
        h.ticks(11);
        for (Tower tower : match.towers()) {
            OverheadBar bar = tower.bar();
            assertNotNull(bar, tower.id() + " has an overhead bar");
            TextDisplay display = bar.display();
            assertNotNull(display);
            assertTrue(display.isValid());
            assertTrue(display.getLocation().getY() > tower.base().getY() + 3, "above the structure");
            assertTrue(Scenario.plain(bar.text()).contains(tower.name()));
        }
        assertEquals(1, ((TestPlayer) a).getBossBars().size(), "one boss bar: the round timer");
        assertTrue(Scenario.plain(match.hud().timer().bar().name()).contains("1판"));

        Tower outer = enemyOuter(match, a);
        Tower inner = SiegeMatchTest.structure(match, outer.owner(), Tower.Kind.INNER, LaneSites.Lane.TOP);
        assertTrue(Scenario.plain(inner.bar().text()).startsWith("🛡"), "a shielded tower's bar says so");
        match.hitTower(outer, a, 500);
        assertTrue(Scenario.plain(outer.bar().text()).endsWith(" 250"), Scenario.plain(outer.bar().text()));
        match.hitTower(outer, a, 10_000);
        assertTrue(outer.bar() == null, "a destroyed tower's bar is gone");
        assertFalse(Scenario.plain(inner.bar().text()).startsWith("🛡"), "the shield went down");
        assertEquals(1, ((TestPlayer) a).getBossBars().size(), "still no tower boss bars");
    }

    // ------------------------------------------------------------------ damage numbers

    @Test
    void damageNumberText() {
        assertEquals("3", DamageNumbers.format(3.0));
        assertEquals("2.5", DamageNumbers.format(2.46));
        assertEquals("12", DamageNumbers.format(12.4));
        assertEquals("+3", Scenario.plain(DamageNumbers.text(3, DamageNumbers.Kind.HEAL)));
        assertEquals(NamedTextColor.GREEN, DamageNumbers.text(3, DamageNumbers.Kind.HEAL).color());
        assertEquals(NamedTextColor.WHITE, DamageNumbers.Kind.NORMAL.color());
        assertEquals(NamedTextColor.YELLOW, DamageNumbers.Kind.ABILITY.color());
        assertEquals(NamedTextColor.GOLD, DamageNumbers.Kind.STRUCTURE.color(), "orange");
    }

    @Test
    void damageNumbersAddUpAndOnlyNearbyPlayersSeeThem() {
        SiegeMatch match = start(3);
        Player a = member(match, 0, 0);
        Player b = member(match, 1, 0);
        Player far = match.teams().get(0).size() > 1 ? member(match, 0, 1) : member(match, 1, 1);
        Location centre = new Location(h.flatWorld(), 0.5, FlatWorld.SURFACE, 0.5);
        a.teleport(centre);
        b.teleport(centre.clone().add(3, 0, 0));
        far.teleport(centre.clone().add(0, 0, 40));
        h.ticks(DamageNumbers.AGGREGATE_TICKS + 1);
        DamageNumbers numbers = match.damageNumbers();
        int before = numbers.spawned();
        for (int i = 0; i < 3; i++) {
            b.setNoDamageTicks(0);
            match.abilities().context().damage(a, b, 2);
        }
        assertEquals(1, numbers.pending(), "three quick hits: one number");
        h.ticks(DamageNumbers.AGGREGATE_TICKS);
        assertEquals(before + 1, numbers.spawned());
        // Three hits of 2, scaled by the 공성전 ability balance, summed into one yellow (ability) number.
        String total = DamageNumbers.format(3 * 2 * match.balance().damageMultiplier(
                match.abilities().participant(a.getUniqueId()).abilityId(), 1, a.getUniqueId(), b.getUniqueId(),
                false));
        TextDisplay number = h.flatWorld().getEntitiesByClass(TextDisplay.class).stream()
                .filter(display -> NamedTextColor.YELLOW.equals(display.text().color())).findFirst().orElseThrow();
        assertEquals(total, Scenario.plain(number.text()), "ability damage is yellow and added up");
        assertTrue(a.canSee(number), "the attacker nearby sees it");
        assertFalse(far.canSee(number), "too far away: not shown");
        h.ticks(DamageNumbers.LIFETIME_TICKS + 1);
        assertFalse(number.isValid(), "it fades away");

        match.hitTower(enemyOuter(match, a), a, 40);
        h.ticks(DamageNumbers.AGGREGATE_TICKS + 1);
        assertTrue(h.flatWorld().getEntitiesByClass(TextDisplay.class).stream()
                .anyMatch(display -> NamedTextColor.GOLD.equals(display.text().color())
                        && "40".equals(Scenario.plain(display.text()))), "structure damage is orange");
    }

    // ------------------------------------------------------------------ guidance and tutorial

    @Test
    void theGuidanceLineKeepsTheMostImportantSuggestion() {
        SiegeMatch match = start(2);
        Player a = member(match, 0, 0);
        Guidance guidance = match.guidance();
        String fallback = Scenario.plain(guidance.current(a));
        assertTrue(fallback.contains("바깥 타워") && fallback.contains("m"), "default: the nearest target: " + fallback);
        actionBars(a);
        guidance.suggest(a, Component.text("A"), Guidance.ALERT);
        guidance.suggest(a, Component.text("B"), Guidance.INFO);
        assertEquals("A", Scenario.plain(guidance.current(a)), "a lower priority waits");
        assertTrue(actionBars(a).stream().anyMatch(bar -> bar.startsWith("▶ 지금 할 일") && bar.endsWith("A")));
        guidance.suggest(a, Component.text("C"), Guidance.URGENT, 5);
        assertEquals("C", Scenario.plain(guidance.current(a)));
        h.ticks(6);
        assertEquals(fallback.substring(0, 6), Scenario.plain(guidance.current(a)).substring(0, 6),
                "back to the default line when it runs out");
    }

    @Test
    void theFirstRoundStartsWithTheTutorial() {
        config.set(SiegeSettings.ROUND_PREP_SECONDS, 20);
        List<TestPlayer> players = h.join(2);
        try {
            h.hub.games().start(siege, null);
        } catch (GameStartException e) {
            throw new AssertionError(e);
        }
        h.tickUntil(() -> siege.currentMatch() != null && siege.currentMatch().round() == 1, 400, "the round");
        List<String> lines = chat(players.getFirst());
        assertTrue(lines.stream().anyMatch(line -> line.contains("공성전 규칙")), String.join("\n", lines));
        assertTrue(lines.stream().anyMatch(line -> line.contains("코어 파괴 = 즉시 판 승리")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("지금 할 일")), "the summary points at the guide");
    }

    // ------------------------------------------------------------------ sounds

    @Test
    void structuresSoundLikeToysAndWarnTheirOwners() {
        config.set(SiegeSettings.WARNING_COOLDOWN, 8);
        SiegeMatch match = start(2);
        Player a = member(match, 0, 0);
        Player b = member(match, 1, 0);
        Tower outer = enemyOuter(match, a);
        a.teleport(outer.base().add(3, 0, 0));
        ((TestPlayer) a).getHeardSounds().clear();
        ((TestPlayer) b).getHeardSounds().clear();
        actionBars(b);
        match.hitTower(outer, a, 10);
        assertTrue(heard(a, "block.bamboo_wood.hit"), "a wooden toy tower knocks");
        assertTrue(heard(b, "block.bell.use"), "the owner hears the alarm, wherever they are");
        assertTrue(actionBars(b).stream().anyMatch(bar -> bar.contains("공격받는 중")), "and reads it");
        ((TestPlayer) b).getHeardSounds().clear();
        h.ticks(SiegeSounds.HIT_GAP_TICKS);
        match.hitTower(outer, a, 10);
        assertFalse(heard(b, "block.bell.use"), "the alarm is rate-limited");

        ((TestPlayer) a).getHeardSounds().clear();
        match.hitTower(outer, a, outer.health() - outer.maxHealth() * 0.7);
        assertTrue(heard(a, "block.note_block.bell"), "the 75% warning chime");
        ((TestPlayer) a).getHeardSounds().clear();
        ((TestPlayer) b).getHeardSounds().clear();
        match.hitTower(outer, a, 10_000);
        assertTrue(heard(a, "ui.toast.challenge_complete"), "the fanfare for the side that broke it");
        assertTrue(heard(b, "block.beacon.deactivate"), "a low tone for the side that lost it");
    }

    @Test
    void soundsAreRateLimitedByKey() {
        SiegeMatch match = start(2);
        SiegeSounds sounds = match.sounds();
        assertTrue(sounds.ready("x", 10));
        assertFalse(sounds.ready("x", 10));
        assertTrue(sounds.ready("y", 10), "per key");
        h.ticks(10);
        assertTrue(sounds.ready("x", 10));
    }

    // ------------------------------------------------------------------ name plates

    @Test
    void plateText() {
        SiegeMatch match = start(2);
        Component plate = NamePlates.plate(3, 14, 20, NamedTextColor.RED, match.messages());
        assertEquals("Lv.3 ▮▮▮▮▮▮▮▯▯▯ 14", Scenario.plain(plate));
        assertEquals("▮▯▯▯▯▯▯▯▯▯ 1", Scenario.plain(NamePlates.plate(0, 0.4, 20, NamedTextColor.RED,
                match.messages())), "no level: bar and health only; one segment while alive");
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
        assertTrue(Scenario.plain(match.hud().plates().plate(a1)).startsWith("Lv." + match.level(a1.getUniqueId())
                + " "), "the in-match level is on the plate");

        a1.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 400, 0));
        h.ticks(11);
        assertFalse(plateShown(b1, a1), "an invisible player's plate is gone for enemies");
        assertTrue(plateShown(a2, a1), "teammates still see it");
        a1.removePotionEffect(PotionEffectType.INVISIBILITY);
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
        SiegeMatch match = start(2);
        Player attacker = member(match, 0, 0);
        Player defender = member(match, 1, 0);
        AbilityItems items = siege.superpower().items();
        ItemStack sword = attacker.getInventory().getItem(AbilityItems.WEAPON_SLOT);
        assertNotNull(sword);
        assertTrue(items.isAbilityItem(sword), "the sword is the ability item");
        assertEquals(Material.IRON_SWORD, sword.getType(), "it is the kit's sword");
        String id = items.abilityId(sword);
        assertNotNull(id);
        AbilityInfo info = siege.superpower().registry().get(id).info();
        ItemStack made = items.createWeapon(info, siege.superpower().abilities().snapshot().params(info),
                ItemStack.of(Material.IRON_SWORD), siege.superpower().texts());
        Key model = made.getData(DataComponentTypes.ITEM_MODEL);
        assertNotNull(model);
        assertEquals("minecraft", model.namespace(), "the item model stays a vanilla sword, for every viewer");
        assertTrue(AbilityWeaponModels.SWORDS.contains(model.value()), "a sword the pack overrides: " + model);
        CustomModelData cmd = made.getData(DataComponentTypes.CUSTOM_MODEL_DATA);
        assertNotNull(cmd, "the pack selects the 3D weapon by custom_model_data");
        assertEquals(List.of(id), cmd.strings(), "string 0 is the ability id");
        long swords = java.util.Arrays.stream(attacker.getInventory().getContents())
                .filter(item -> item != null && Tag.ITEMS_SWORDS.isTagged(item.getType())).count();
        assertEquals(1, swords, "the kit's sword is not given twice");

        attacker.getInventory().setHeldItemSlot(AbilityItems.WEAPON_SLOT);
        h.server.getPluginManager().callEvent(new PlayerInteractEvent(attacker, Action.RIGHT_CLICK_AIR, sword,
                null, BlockFace.SELF, EquipmentSlot.HAND));
        assertTrue(attacker.getCooldown(items.cooldownGroup()) > 0, "right-click casts the active skill at level 1");

        double before = defender.getHealth();
        defender.setNoDamageTicks(0);
        defender.damage(5, DamageSource.builder(DamageType.PLAYER_ATTACK).withCausingEntity(attacker)
                .withDirectEntity(attacker).build());
        assertEquals(before, defender.getHealth(), 1.0E-6,
                "a vanilla sword hit is replaced by the basic attack (평타, BasicAttackMatchTest)");
        assertTrue(match.abilities().basicAttack(attacker), "left-click fires the ability's basic attack");
    }
}
