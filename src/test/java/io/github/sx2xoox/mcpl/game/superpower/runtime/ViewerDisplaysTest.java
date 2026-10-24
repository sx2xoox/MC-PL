package io.github.sx2xoox.mcpl.game.superpower.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Stealth;
import io.github.sx2xoox.mcpl.game.superpower.support.DummyAbility;
import io.github.sx2xoox.mcpl.game.superpower.support.Scenario;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * Per-viewer displays ({@code Displays.onlyFor}, the {@code viewers} overloads, {@code AbilityContext#showOnlyTo}) and
 * the test doubles behind them: who sees what, filters added inside the spawn, late joiners, viewers whose answer
 * changes (also at once on a stealth), and the end of a filter.
 */
class ViewerDisplaysTest {

    private static TextDisplay text(Scenario s, String text) {
        Location at = s.owner.getLocation().add(0, 2.5, 0);
        return Displays.text(s.ctx(), s.owner, at, Component.text(text), 0.6f, 0);
    }

    @Test
    void anOwnerOnlyDisplayIsSeenByItsOwnerAlone() {
        try (Scenario s = Scenario.ffa(DummyAbility.INFO.id())) {
            MatchContext ctx = s.ctx();
            TextDisplay hint = Displays.ownerOnly(ctx, s.owner, text(s, "약점"));
            assertFalse(hint.isVisibleByDefault(), "hidden by default, shown per viewer");
            assertEquals(List.of(s.owner), s.seenBy(hint));
            assertEquals(1, ctx.viewerFilterCount());
            s.ticks((int) MatchContext.VIEW_REFRESH_TICKS * 2);
            assertEquals(List.of(s.owner), s.seenBy(hint), "the refreshes keep it so");
            s.finishClean();
        }
    }

    @Test
    void aLateJoinerNeverSeesAPrivateDisplayButAFilterAcceptingSpectatorsShowsIt() {
        try (Scenario s = Scenario.ffa(DummyAbility.INFO.id())) {
            MatchContext ctx = s.ctx();
            TextDisplay hint = Displays.ownerOnly(ctx, s.owner, text(s, "약점"));
            TextDisplay marker = Displays.onlyFor(ctx, s.owner, text(s, "표식"),
                    Displays.ownerAndAllies(ctx, s.owner).or(Displays.spectators(ctx)));
            assertEquals(List.of(s.owner), s.seenBy(marker), "free-for-all: no allies, no spectators yet");

            TestPlayer late = s.h.join("Late");
            assertTrue(ctx.isSpectator(late), "a player joining a running match watches it");
            assertFalse(late.canSee(hint), "never the owner's private display");
            assertTrue(late.canSee(marker), "shown as soon as it joined");
            s.finishClean();
        }
    }

    @Test
    void filtersFollowViewersWhoAreEliminated() {
        try (Scenario s = Scenario.teams(DummyAbility.INFO.id())) {
            MatchContext ctx = s.ctx();
            TextDisplay team = Displays.onlyFor(ctx, s.owner, text(s, "집합"), Displays.ownerAndAllies(ctx, s.owner));
            TextDisplay watch = Displays.onlyFor(ctx, s.owner, text(s, "관전"), Displays.spectators(ctx));
            assertEquals(List.of(s.owner, s.ally()), s.seenBy(team));
            assertTrue(s.seenBy(watch).isEmpty());

            s.session.eliminate(s.ally(), null);
            assertEquals(List.of(s.owner), s.seenBy(team), "an eliminated ally is no ally in play any more");
            assertEquals(List.of(s.ally()), s.seenBy(watch), "and now it spectates");
            s.session.eliminate(s.enemy, null);
            assertEquals(List.of(s.enemy, s.ally()), s.seenBy(watch));
            s.finishClean();
        }
    }

    @Test
    void aSecondFilterReplacesTheFirst() {
        try (Scenario s = Scenario.ffa(DummyAbility.INFO.id())) {
            MatchContext ctx = s.ctx();
            TextDisplay label = Displays.ownerOnly(ctx, s.owner, text(s, "이름"));
            Displays.onlyFor(ctx, s.owner, label, viewer -> viewer.equals(s.enemy));
            assertEquals(List.of(s.enemy), s.seenBy(label));
            assertEquals(1, ctx.viewerFilterCount());
            s.finishClean();
        }
    }

    @Test
    void aFilterEndsWithItsEntityAndWithItsOwner() {
        try (Scenario s = Scenario.ffa(DummyAbility.INFO.id())) {
            MatchContext ctx = s.ctx();
            TextDisplay gone = Displays.ownerOnly(ctx, s.owner, text(s, "잠깐"));
            gone.remove();
            s.ticks((int) MatchContext.VIEW_REFRESH_TICKS);
            assertEquals(0, ctx.viewerFilterCount(), "a removed entity drops its filter");

            TextDisplay loose = s.world.spawn(s.owner.getLocation().add(0, 3, 0), TextDisplay.class);
            Displays.ownerOnly(ctx, s.owner, loose);
            assertEquals(1, ctx.viewerFilterCount());
            s.eliminateOwner(null);
            assertEquals(0, ctx.viewerFilterCount(), "the owner's filters end with its ability");
            assertTrue(s.seenBy(loose).isEmpty() || s.seenBy(loose).equals(List.of(s.owner)),
                    "an untracked entity keeps its last view, never shown to everyone");
            Displays.ownerOnly(ctx, s.owner, loose);
            assertEquals(0, ctx.viewerFilterCount(), "an owner that is out cannot filter");
            loose.remove();
            s.assertNoProblems();
        }
    }

    @Test
    void aFilterThatThrowsShowsNothingAndIsReported() {
        try (Scenario s = Scenario.ffa(DummyAbility.INFO.id())) {
            MatchContext ctx = s.ctx();
            TextDisplay broken = Displays.onlyFor(ctx, s.owner, text(s, "버그"), viewer -> {
                throw new IllegalStateException("broken filter");
            });
            assertTrue(s.seenBy(broken).isEmpty());
            assertTrue(s.match.errors().totalFailures() > 0, "the guard reported it");
        }
    }

    @Test
    void theDoublesKeepPapersPerViewerSemantics() {
        try (Scenario s = Scenario.ffa(DummyAbility.INFO.id())) {
            TextDisplay display = text(s, "표시");
            assertTrue(display.isVisibleByDefault());
            assertEquals(s.all(), s.seenBy(display));
            // The batch A pattern: hide an entity visible by default from some players.
            s.enemy.hideEntity(s.h.hub.plugin(), display);
            assertEquals(List.of(s.owner, s.enemy2), s.seenBy(display));
            s.enemy.showEntity(s.h.hub.plugin(), display);
            assertEquals(s.all(), s.seenBy(display));

            // Hidden by default: nobody but those shown; changing the default resets every player's own choice.
            s.enemy.hideEntity(s.h.hub.plugin(), display);
            display.setVisibleByDefault(false);
            assertTrue(s.seenBy(display).isEmpty());
            s.owner.showEntity(s.h.hub.plugin(), display);
            assertEquals(List.of(s.owner), s.seenBy(display));
            s.owner.hideEntity(s.h.hub.plugin(), display);
            assertTrue(s.seenBy(display).isEmpty());
            s.enemy2.showEntity(s.h.hub.plugin(), display);
            display.setVisibleByDefault(true);
            assertEquals(s.all(), s.seenBy(display), "back to visible for everyone, the old hide is forgotten");
            s.finishClean();
        }
    }

    @Test
    void aFilterAddedInsideTheSpawnIsKeptAndNobodyElseEverSeesTheEntity() {
        try (Scenario s = Scenario.ffa(DummyAbility.INFO.id())) {
            MatchContext ctx = s.ctx();
            List<String> seenWhileSpawning = new ArrayList<>();
            // Every player who could ever see the entity is asked by the server only after the consumer: record who
            // could see it at the end of it.
            TextDisplay raw = s.world.spawn(s.owner.getLocation().add(0, 2.5, 0), TextDisplay.class, d -> {
                assertFalse(d.isValid(), "like Paper: not in the world yet while the consumer runs");
                ctx.showOnlyTo(s.owner, d, Displays.owner(s.owner));
                for (TestPlayer player : s.all()) {
                    if (player.canSee(d)) {
                        seenWhileSpawning.add(player.getName());
                    }
                }
            });
            ctx.track(s.owner, raw);
            assertTrue(raw.isValid());
            assertEquals(List.of(s.owner.getName()), seenWhileSpawning, "shown to the owner before anyone got it");
            assertEquals(List.of(s.owner), s.seenBy(raw));
            assertEquals(1, ctx.viewerFilterCount(), "the spawning entity kept its filter");

            TextDisplay hint = Displays.text(ctx, s.owner, s.owner.getLocation().add(0, 3, 0),
                    Component.text("약점"), 0.6f, 0, Displays.owner(s.owner));
            assertFalse(hint.isVisibleByDefault());
            assertEquals(List.of(s.owner), s.seenBy(hint));
            s.ticks((int) MatchContext.VIEW_REFRESH_TICKS * 2);
            assertEquals(List.of(s.owner), s.seenBy(raw), "the refreshes keep it so");
            assertEquals(List.of(s.owner), s.seenBy(hint));
            assertEquals(2, ctx.viewerFilterCount());
            s.finishClean();
        }
    }

    @Test
    void aSpawnThatNeverHappensDropsItsFilter() {
        try (Scenario s = Scenario.ffa(DummyAbility.INFO.id())) {
            MatchContext ctx = s.ctx();
            Listener veto = new Listener() {
                @EventHandler
                public void onSpawn(EntitySpawnEvent event) {
                    event.setCancelled(true);
                }
            };
            s.h.server.getPluginManager().registerEvents(veto, MockBukkit.createMockPlugin("SpawnVeto"));
            TextDisplay cancelled = s.world.spawn(s.owner.getLocation(), TextDisplay.class,
                    d -> ctx.showOnlyTo(s.owner, d, Displays.owner(s.owner)));
            HandlerList.unregisterAll(veto);
            assertFalse(cancelled.isValid());
            s.ticks((int) MatchContext.VIEW_REFRESH_TICKS);
            assertEquals(0, ctx.viewerFilterCount());
            s.finishClean();
        }
    }

    @Test
    void aStealthHidesConcealmentFilteredMarkersAtOnce() {
        try (Scenario s = Scenario.ffa(DummyAbility.INFO.id())) {
            MatchContext ctx = s.ctx();
            s.ticks(1);
            TextDisplay mark = Displays.text(ctx, s.owner, s.enemy.getLocation().add(0, 2.5, 0),
                    Component.text("표적"), 0.6f, 0, viewer -> !ctx.isConcealedFrom(s.enemy, viewer));
            assertEquals(s.all(), s.seenBy(mark));
            Stealth stealth = new Stealth();
            stealth.begin(ctx, s.enemy, 100, () -> { });
            assertEquals(List.of(s.enemy), s.seenBy(mark), "gone in the same tick, not at the next refresh");
            stealth.end(ctx, s.enemy);
            assertEquals(s.all(), s.seenBy(mark));
            ctx.refreshViewers();
            assertEquals(s.all(), s.seenBy(mark));
            s.finishClean();
        }
    }
}
