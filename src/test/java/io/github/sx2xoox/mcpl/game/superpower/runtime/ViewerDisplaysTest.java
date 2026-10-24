package io.github.sx2xoox.mcpl.game.superpower.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.support.DummyAbility;
import io.github.sx2xoox.mcpl.game.superpower.support.Scenario;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.TextDisplay;
import org.junit.jupiter.api.Test;

/**
 * Per-viewer displays ({@code Displays.onlyFor}, {@code AbilityContext#showOnlyTo}) and the test doubles behind them:
 * who sees what, late joiners, viewers whose answer changes, and the end of a filter.
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
}
