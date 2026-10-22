package io.github.sx2xoox.mcpl.game.superpower;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.sx2xoox.mcpl.core.game.GameResult;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.game.GameState;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityRegistry;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.game.superpower.menu.ChoiceMenu;
import io.github.sx2xoox.mcpl.game.superpower.runtime.MatchPhase;
import io.github.sx2xoox.mcpl.game.superpower.runtime.Participant;
import io.github.sx2xoox.mcpl.game.superpower.runtime.SuperpowerMatch;
import io.github.sx2xoox.mcpl.game.superpower.support.MatchHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.ProbeAbility;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.game.superpower.ui.AbilityItems;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SuperpowerMatchTest {

    private MatchHarness h;

    @BeforeEach
    void setUp() {
        h = MatchHarness.flat();
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    private Participant participant(SuperpowerMatch match, Player player) {
        Participant participant = match.participant(player.getUniqueId());
        assertNotNull(participant, player.getName() + " is a participant");
        return participant;
    }

    private ItemStack abilityItem(Player player) {
        return player.getInventory().getItem(AbilityItems.SLOT);
    }

    // =================================================================== full match

    @Test
    void fullMatchWithEveryAbilityIsIsolatedAndRestoresEveryone() {
        List<TestPlayer> players = h.join(4);
        for (TestPlayer player : players) {
            player.setGameMode(GameMode.SURVIVAL);
            player.getInventory().setItem(3, new ItemStack(Material.DIAMOND, 5));
            player.setLevel(7);
        }
        GameSession session = h.start();
        h.tickUntilPhase(MatchPhase.FIGHT);
        SuperpowerMatch match = h.match();
        assertNotNull(match);
        assertSame(match, session.instance());

        Set<String> assigned = new HashSet<>();
        for (TestPlayer player : players) {
            Participant participant = participant(match, player);
            assertNotNull(participant.ability(), "ability bound for " + player.getName());
            assertNotNull(participant.abilityId());
            assigned.add(participant.abilityId());
            assertEquals(participant.abilityId(), h.game.items().abilityId(abilityItem(player)),
                    "ability item in hotbar slot 9");
            assertEquals(Material.IRON_HELMET, player.getInventory().getHelmet().getType(), "kit armor is worn");
            assertEquals(Material.IRON_SWORD, player.getInventory().getItem(0).getType());
            assertEquals(GameMode.ADVENTURE, player.getGameMode());
            assertNotNull(match.playerTag(player.getUniqueId()));
        }
        assertEquals(4, assigned.size(), "abilities are unique while the pool allows it");

        h.ticks(40);
        assertTrue(match.fightTicks() >= 40);

        // Keep everyone alive while every ability fires its skills next to the others.
        NamespacedKey tank = new NamespacedKey("test", "tank");
        Location center = session.arena().center();
        for (TestPlayer player : players) {
            player.getAttribute(Attribute.MAX_HEALTH).addModifier(new AttributeModifier(tank, 2000,
                    AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
        }
        TestPlayer caster = players.getFirst();
        for (AbilityRegistry.Entry entry : h.game.registry().all()) {
            String id = entry.info().id();
            for (int i = 0; i < players.size(); i++) {
                TestPlayer player = players.get(i);
                player.teleport(center.clone().add(i * 1.5, 0, 0));
                player.setHealth(player.getAttribute(Attribute.MAX_HEALTH).getValue());
            }
            assertTrue(match.replaceAbility(caster, id), "bind " + id);
            assertEquals(id, h.game.items().abilityId(abilityItem(caster)));
            h.ticks(3);
            ActivationResult active = match.useActive(caster);
            assertNotNull(active, id + " active");
            match.setGauge(caster, 100);
            ActivationResult ultimate = match.useUltimate(caster);
            assertNotNull(ultimate, id + " ultimate");
            h.ticks(30);
            assertEquals(GameState.RUNNING, session.state(), "the match survives " + id);
        }
        assertEquals(0, match.errors().totalFailures(), "on the flat test world every ability runs without errors");

        for (TestPlayer player : players) {
            player.getAttribute(Attribute.MAX_HEALTH).removeModifier(tank);
        }
        for (TestPlayer player : players.subList(1, players.size())) {
            session.eliminate(player, caster);
        }
        assertEquals(GameState.ENDING, session.state());
        GameResult result = session.result();
        assertNotNull(result);
        assertTrue(result.isWinner(caster.getUniqueId()));

        h.ticks(h.hub.config().endingSeconds() * 20 + 5);
        assertEquals(GameState.CLOSED, session.state());
        assertNull(h.game.currentMatch());
        for (TestPlayer player : players) {
            assertEquals(GameMode.SURVIVAL, player.getGameMode(), player.getName() + " restored");
            ItemStack diamonds = player.getInventory().getItem(3);
            assertNotNull(diamonds);
            assertEquals(Material.DIAMOND, diamonds.getType());
            assertEquals(7, player.getLevel());
            for (ItemStack item : player.getInventory().getContents()) {
                assertFalse(h.game.items().isAbilityItem(item), "no ability item left");
            }
            for (Attribute attribute : Registry.ATTRIBUTE) {
                AttributeInstance instance = player.getAttribute(attribute);
                if (instance != null) {
                    for (AttributeModifier modifier : instance.getModifiers()) {
                        assertNotEquals("mcpl", modifier.getKey().getNamespace(), "modifier left on " + attribute);
                    }
                }
            }
        }
        for (Entity entity : session.arena().world().getEntities()) {
            // Celebration fireworks belong to the hub and explode on their own on a real server.
            assertTrue(entity instanceof Player || entity instanceof org.bukkit.entity.Firework,
                    "entity left behind: " + entity.getType());
        }
        assertEquals(0, h.hub.tempBlocks().size(), "temporary blocks restored");
    }

    // =================================================================== selection

    @Test
    void choiceModeUsesTheMenuAndOnlyTheTimeoutPicksAtRandom() {
        h.settings().set("selection.mode", "CHOICE");
        List<TestPlayer> players = h.join(3);
        h.start();
        h.tickUntilPhase(MatchPhase.SELECT);
        h.ticks(2);
        SuperpowerMatch match = h.match();

        TestPlayer picker = players.get(0);
        assertTrue(picker.getOpenInventory().getTopInventory().getHolder() instanceof ChoiceMenu);
        ChoiceMenu menu = (ChoiceMenu) picker.getOpenInventory().getTopInventory().getHolder();
        assertEquals(3, menu.candidates().size());
        String wanted = menu.candidates().get(1);
        InventoryClickEvent click = new InventoryClickEvent(picker.getOpenInventory(),
                InventoryType.SlotType.CONTAINER, menu.slotOf(wanted), ClickType.LEFT, InventoryAction.PICKUP_ALL);
        h.server.getPluginManager().callEvent(click);
        assertTrue(click.isCancelled(), "menu items cannot be taken");
        assertEquals(wanted, participant(match, picker).abilityId());

        // The menu closes a tick after the pick: a second click in between must not take the other icons.
        assertSame(menu, picker.getOpenInventory().getTopInventory().getHolder(), "still open this tick");
        String other = menu.candidates().get(2);
        InventoryClickEvent late = new InventoryClickEvent(picker.getOpenInventory(),
                InventoryType.SlotType.CONTAINER, menu.slotOf(other), ClickType.LEFT, InventoryAction.PICKUP_ALL);
        h.server.getPluginManager().callEvent(late);
        assertTrue(late.isCancelled(), "a click on a stale menu is cancelled");
        assertEquals(wanted, participant(match, picker).abilityId(), "a repeated pick changes nothing");

        TestPlayer closer = players.get(1);
        ChoiceMenu closerMenu = (ChoiceMenu) closer.getOpenInventory().getTopInventory().getHolder();
        closer.closeInventory(InventoryCloseEvent.Reason.PLAYER);
        assertNull(participant(match, closer).abilityId(), "closing the menu (ESC/E) does not pick");
        h.ticks(25);
        assertTrue(closer.getOpenInventory().getTopInventory().getHolder() instanceof ChoiceMenu reopened
                && reopened.candidates().equals(closerMenu.candidates()), "the menu opens again with the same choices");
        for (int i = 0; i < 2; i++) {
            closer.closeInventory(InventoryCloseEvent.Reason.PLAYER);
            h.ticks(25);
        }
        assertFalse(closer.getOpenInventory().getTopInventory().getHolder() instanceof ChoiceMenu,
                "after a few closes it stays closed");
        assertTrue(match.reopenMenu(closer), "/게임 능력 선택 opens it again");
        assertTrue(closer.getOpenInventory().getTopInventory().getHolder() instanceof ChoiceMenu);
        closer.closeInventory(InventoryCloseEvent.Reason.PLAYER);

        h.tickUntilPhase(MatchPhase.FIGHT);
        String auto = participant(match, closer).abilityId();
        assertNotNull(auto, "the timeout picks for players who closed the menu");
        assertTrue(closerMenu.candidates().contains(auto));
        TestPlayer waiter = players.get(2);
        assertNotNull(participant(match, waiter).abilityId(), "the timeout picks for players who never chose");
        assertFalse(match.reopenMenu(waiter), "nothing to pick in the fight");
        for (TestPlayer player : players) {
            assertNotNull(participant(match, player).ability());
        }
    }

    @Test
    void rerollDuringPrepChangesTheAbilityOnce() {
        h.settings().set("prep-seconds", 30);
        List<TestPlayer> players = h.join(2);
        h.start();
        h.tickUntilPhase(MatchPhase.PREP);
        SuperpowerMatch match = h.match();
        TestPlayer player = players.getFirst();
        String before = participant(match, player).abilityId();
        assertEquals(1, participant(match, player).rerollsLeft());

        player.setSneaking(true);
        PlayerInteractEvent use = new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, abilityItem(player), null,
                BlockFace.SELF, EquipmentSlot.HAND);
        h.server.getPluginManager().callEvent(use);
        assertEquals(Event.Result.DENY, use.useItemInHand(), "the ability item is never used vanilla-style");
        String after = participant(match, player).abilityId();
        assertNotEquals(before, after);
        assertEquals(0, participant(match, player).rerollsLeft());
        assertEquals(SuperpowerMatch.RerollResult.NONE_LEFT, match.reroll(player));

        h.ticks(40);
        assertEquals(after, h.game.items().abilityId(abilityItem(player)), "the new item arrives after the re-reveal");
        assertEquals(SuperpowerMatch.RerollResult.NOT_PARTICIPANT, match.reroll(h.join("Late")));
    }

    @Test
    void adminAssignmentsAreHonouredAndDisabledAbilitiesNeverAssigned() {
        List<TestPlayer> players = h.join(4);
        TestPlayer admin = players.getFirst();
        admin.setOp(true);
        Set<String> keep = Set.of("pixie", "phoenix", "illusionist", "gambler");
        for (var entry : h.game.registry().all()) {
            if (!keep.contains(entry.info().id())) {
                h.game.abilities().setEnabled(h.game.registry(), entry.info().id(), false);
            }
        }
        assertTrue(h.server.dispatchCommand(admin, "게임 능력 지정 P2 도박사"));
        assertEquals("gambler", h.game.pendingAssignments().get(players.get(1).getUniqueId()));
        h.server.dispatchCommand(admin, "게임 능력 지정 P3 화염술사");
        assertNull(h.game.pendingAssignments().get(players.get(2).getUniqueId()), "disabled abilities are refused");

        SuperpowerMatch match = h.startFight();
        assertEquals("gambler", participant(match, players.get(1)).abilityId());
        assertTrue(participant(match, players.get(1)).isForced());
        Set<String> enabled = Set.copyOf(h.game.abilities().snapshot().enabledIds());
        assertEquals(4, enabled.size());
        for (TestPlayer player : players) {
            assertTrue(enabled.contains(participant(match, player).abilityId()), "only enabled abilities");
        }
        assertTrue(h.game.pendingAssignments().isEmpty(), "assignments are consumed by the match");
    }

    // =================================================================== input, cooldown, gauge

    @Test
    void rightClickUsesTheActiveWithCooldownAndFUsesTheUltimate() {
        h.registerTestAbilities();
        List<TestPlayer> players = h.join(2);
        SuperpowerMatch match = h.startFight();
        TestPlayer player = players.getFirst();
        assertTrue(match.replaceAbility(player, "probe"));
        ProbeAbility probe = ProbeAbility.INSTANCES.getLast();
        assertEquals(1, probe.grants);
        h.ticks(5);
        assertTrue(probe.ticks >= 5, "onTick runs every tick");

        PlayerInteractEvent use = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, abilityItem(player),
                player.getLocation().getBlock(), BlockFace.UP, EquipmentSlot.HAND);
        h.server.getPluginManager().callEvent(use);
        h.server.getPluginManager().callEvent(new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR,
                abilityItem(player), null, BlockFace.SELF, EquipmentSlot.HAND));
        assertEquals(1, probe.actives, "one click fires once even if block and air interactions both arrive");
        assertEquals(Event.Result.DENY, use.useInteractedBlock());
        assertEquals(5.0, match.context().cooldownRemaining(player), 1.0E-9, "10 s cooldown x 0.5 multiplier");
        assertEquals(100, player.getCooldown(h.game.items().cooldownGroup()), "vanilla cooldown overlay");

        h.ticks(1);
        h.server.getPluginManager().callEvent(new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR,
                abilityItem(player), null, BlockFace.SELF, EquipmentSlot.HAND));
        assertEquals(1, probe.actives, "not ready while on cooldown");

        match.context().reduceCooldown(player, 10);
        assertEquals(0.0, match.context().cooldownRemaining(player), 1.0E-9);
        probe.failActive = true;
        assertFalse(match.useActive(player).succeeded());
        assertEquals(0.0, match.context().cooldownRemaining(player), 1.0E-9, "a failed activation costs nothing");
        probe.failActive = false;

        match.context().silence(player, 40);
        assertTrue(match.context().isSilenced(player));
        assertFalse(match.useActive(player).succeeded(), "silenced players cannot use skills");
        assertEquals(2, probe.actives);
        h.ticks(45);
        assertFalse(match.context().isSilenced(player));

        PlayerSwapHandItemsEvent swap = new PlayerSwapHandItemsEvent(player, new ItemStack(Material.AIR),
                abilityItem(player));
        h.server.getPluginManager().callEvent(swap);
        assertTrue(swap.isCancelled(), "F never swaps hands for participants");
        assertEquals(0, probe.ultimates, "the gauge is not full");

        match.context().addGauge(player, 250);
        assertEquals(100.0, match.context().gauge(player), 1.0E-9, "the gauge is clamped");
        assertEquals(100, player.getLevel(), "the XP level shows the gauge");
        PlayerExpChangeEvent orb = new PlayerExpChangeEvent(player, 30);
        h.server.getPluginManager().callEvent(orb);
        assertEquals(0, orb.getAmount(), "vanilla experience cannot change the gauge bar");

        h.server.getPluginManager().callEvent(new PlayerSwapHandItemsEvent(player, new ItemStack(Material.AIR),
                abilityItem(player)));
        assertEquals(1, probe.ultimates);
        assertEquals(0.0, match.context().gauge(player), 1.0E-9, "the ultimate consumes the whole gauge");
        assertEquals(0, player.getLevel());
    }

    @Test
    void gaugeChargesFromDamageAndTime() {
        h.registerTestAbilities();
        List<TestPlayer> players = h.join(2);
        SuperpowerMatch match = h.startFight();
        TestPlayer attacker = players.get(0);
        TestPlayer victim = players.get(1);
        assertTrue(match.replaceAbility(attacker, "probe"));
        assertTrue(match.replaceAbility(victim, "probe"));
        h.healAll(players);
        match.setGauge(attacker, 0);
        match.setGauge(victim, 0);

        victim.simulateDamage(5, attacker);
        assertEquals(20.0, match.context().gauge(attacker), 1.0E-6, "4% per damage dealt");
        assertEquals(10.0, match.context().gauge(victim), 1.0E-6, "2% per damage taken");

        double before = match.context().gauge(attacker);
        h.ticks(40);
        assertEquals(before + 1.0, match.context().gauge(attacker), 0.51, "0.5% per second");
    }

    // =================================================================== hooks & isolation

    @Test
    void hooksAreRoutedAndBrokenAbilitiesNeverBreakTheMatch() {
        h.registerTestAbilities();
        List<TestPlayer> players = h.join(3);
        SuperpowerMatch match = h.startFight();
        TestPlayer probeOwner = players.get(0);
        TestPlayer broken = players.get(1);
        TestPlayer third = players.get(2);
        assertTrue(match.replaceAbility(probeOwner, "probe"));
        ProbeAbility probe = ProbeAbility.INSTANCES.getLast();
        assertTrue(match.replaceAbility(third, "probe"));
        assertTrue(match.replaceAbility(broken, "broken"));
        h.healAll(players);

        broken.simulateDamage(2, probeOwner);
        assertEquals(1, probe.dealt, "onDealDamage for hits on enemies");
        probeOwner.simulateDamage(2, broken);
        assertEquals(1, probe.taken, "onTakeDamage for the victim");
        third.simulateDamage(1, broken);

        h.ticks(20);
        assertFalse(match.useActive(broken).succeeded());
        match.setGauge(broken, 100);
        assertFalse(match.useUltimate(broken).succeeded());
        assertTrue(match.errors().failures("broken", "onTick") >= 20, "onTick keeps being called and caught");
        assertTrue(match.errors().failures("broken", "onGrant") >= 1);
        assertTrue(match.errors().failures("broken", "onDealDamage") >= 1);
        assertTrue(match.errors().failures("broken", "onTakeDamage") >= 1);
        assertTrue(match.errors().failures("broken", "onActive") >= 1);
        assertTrue(match.errors().failures("broken", "onUltimate") >= 1);

        match.context().damage(probeOwner, third, 4);
        assertFalse(match.context().isAbilityDamage());
        assertEquals(15.0, third.getHealth(), 1.0E-6, "ability damage lands");
        assertEquals(2, probe.dealt, "ability damage reaches onDealDamage");
        match.context().damage(probeOwner, probeOwner, 4);
        assertEquals(20.0 - 2, probeOwner.getHealth(), 1.0E-6, "never damages non-enemies");

        match.session().eliminate(broken, probeOwner);
        assertEquals(1, probe.kills, "onKill for the credited killer");
        assertTrue(match.errors().failures("broken", "onRevoke") >= 1);
        assertEquals(GameState.RUNNING, match.session().state());
        assertNull(participant(match, broken).ability(), "revoked on elimination");
    }

    @Test
    void revokeReleasesEverythingTheOwnerCreated() {
        h.registerTestAbilities();
        List<TestPlayer> players = h.join(3);
        SuperpowerMatch match = h.startFight();
        TestPlayer owner = players.get(0);
        TestPlayer other = players.get(1);
        assertTrue(match.replaceAbility(owner, "probe"));
        assertTrue(match.replaceAbility(other, "probe"));
        h.healAll(players);
        var ctx = match.context();

        int[] runs = {0};
        ctx.runTimer(owner, 1, 1, () -> runs[0]++);
        Entity marker = ctx.track(owner, owner.getWorld().spawn(owner.getLocation(), org.bukkit.entity.Snowball.class));
        var wolf = ctx.summon(owner, owner.getWorld().spawn(owner.getLocation(), org.bukkit.entity.Wolf.class));
        NamespacedKey untimed = ctx.addModifier(owner, owner, Attribute.MAX_HEALTH, 10,
                AttributeModifier.Operation.ADD_NUMBER, 0);
        NamespacedKey timed = ctx.addModifier(owner, other, Attribute.MAX_HEALTH, -10,
                AttributeModifier.Operation.ADD_NUMBER, 60);
        assertEquals("mcpl", untimed.getNamespace(), "modifiers use the plugin namespace (stripped on restore)");
        assertEquals(10.0, other.getHealth(), 1.0E-6, "health is clamped when max health shrinks");
        assertNotNull(owner.getAttribute(Attribute.MAX_HEALTH).getModifier(untimed));
        h.ticks(5);
        assertTrue(runs[0] >= 4);

        match.session().eliminate(owner, null);
        int afterRevoke = runs[0];
        h.ticks(5);
        assertEquals(afterRevoke, runs[0], "owner tasks are cancelled on revoke");
        assertFalse(marker.isValid(), "tracked entities are removed");
        assertFalse(wolf.isValid(), "summons are removed");
        assertNull(owner.getAttribute(Attribute.MAX_HEALTH).getModifier(untimed), "untimed modifiers are removed");
        assertNotNull(other.getAttribute(Attribute.MAX_HEALTH).getModifier(timed),
                "timed modifiers outlive the owner until they expire");
        h.ticks(60);
        assertNull(other.getAttribute(Attribute.MAX_HEALTH).getModifier(timed), "timed modifiers expire");

        int later = runs[0];
        ctx.runLater(owner, 1, () -> runs[0]++);
        h.ticks(3);
        assertEquals(later, runs[0], "a revoked owner cannot schedule anything new");
    }

    // =================================================================== ending

    @Test
    void timeLimitPicksTheMostKills() {
        h.settings().set("time-limit-seconds", 2);
        List<TestPlayer> players = h.join(3);
        SuperpowerMatch match = h.startFight();
        GameSession session = match.session();
        session.eliminate(players.get(2), players.get(1));
        assertEquals(GameState.RUNNING, session.state());
        h.tickUntil(() -> session.state() != GameState.RUNNING, 100, "time limit");
        GameResult result = session.result();
        assertNotNull(result);
        assertTrue(result.isWinner(players.get(1).getUniqueId()), "most kills wins");
        assertNotNull(result.reason());
    }

    @Test
    void borderDamagesPlayersOutside() {
        h.settings().set("border.start-delay-seconds", 0);
        h.settings().set("border.shrink-seconds", 0);
        h.settings().set("border.final-diameter", 6);
        h.settings().set("border.damage-per-second", 3);
        h.registerTestAbilities();
        List<TestPlayer> players = h.join(2);
        SuperpowerMatch match = h.startFight();
        Location center = match.session().arena().center();
        TestPlayer inside = players.get(0);
        TestPlayer outside = players.get(1);
        assertTrue(match.replaceAbility(inside, "probe"));
        assertTrue(match.replaceAbility(outside, "probe"));
        h.healAll(players);
        inside.teleport(center.clone().add(0.5, 0, 0.5));
        outside.teleport(center.clone().add(40, 0, 0));
        h.ticks(41);
        assertEquals(20.0, inside.getHealth(), 1.0E-6);
        assertTrue(outside.getHealth() <= 20.0 - 3, "outside the border hurts every second");
        assertFalse(match.context().isInsideArena(outside.getLocation()));
        assertTrue(match.context().isInsideArena(inside.getLocation()));
    }

    // =================================================================== item protection

    @Test
    void abilityItemCannotBeMovedAndComesBack() {
        List<TestPlayer> players = h.join(2);
        SuperpowerMatch match = h.startFight();
        TestPlayer player = players.getFirst();
        ItemStack item = abilityItem(player);
        assertTrue(h.game.items().isAbilityItem(item));

        player.openInventory(h.server.createInventory(null, 27));
        InventoryClickEvent click = new InventoryClickEvent(player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER, 0, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        click.setCurrentItem(item);
        h.server.getPluginManager().callEvent(click);
        assertTrue(click.isCancelled(), "the ability item cannot be moved into containers");
        player.closeInventory();

        var drop = new org.bukkit.event.player.PlayerDropItemEvent(player,
                player.getWorld().dropItem(player.getLocation(), item.clone()));
        h.server.getPluginManager().callEvent(drop);
        assertTrue(drop.isCancelled(), "the ability item cannot be dropped");
        drop.getItemDrop().remove();

        player.getInventory().setItem(AbilityItems.SLOT, null);
        player.getInventory().setItem(20, item.clone());
        h.ticks(21);
        assertTrue(h.game.items().isAbilityItem(abilityItem(player)), "re-given to slot 9");
        assertFalse(h.game.items().isAbilityItem(player.getInventory().getItem(20)), "strays are removed");
        assertEquals(participant(match, player).abilityId(), h.game.items().abilityId(abilityItem(player)));
    }

    @Test
    void abilityTagIsShownInTheKillFeed() {
        List<TestPlayer> players = h.join(2);
        SuperpowerMatch match = h.startFight();
        Participant first = participant(match, players.getFirst());
        AbilityInfo info = h.game.registry().get(first.abilityId()).info();
        assertEquals(info.name(), net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(match.playerTag(players.getFirst().getUniqueId())));
    }
}
