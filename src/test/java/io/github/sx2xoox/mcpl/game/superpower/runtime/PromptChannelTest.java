package io.github.sx2xoox.mcpl.game.superpower.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import io.github.sx2xoox.mcpl.game.superpower.SuperpowerSettings;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Stealth;
import io.github.sx2xoox.mcpl.game.superpower.ability.Prompt;
import io.github.sx2xoox.mcpl.game.superpower.ability.PromptEndReason;
import io.github.sx2xoox.mcpl.game.superpower.ability.PromptHandle;
import io.github.sx2xoox.mcpl.game.superpower.support.DummyAbility;
import io.github.sx2xoox.mcpl.game.superpower.support.Scenario;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The prompt channel ({@code AbilityContext#prompt}): one prompt on a player's screen at a time, the queue order,
 * priority push-backs, the answer window starting when a prompt is shown, unseen expiry, cleanup and the public
 * overhead text.
 */
class PromptChannelTest {

    /** What happened to one prompt. */
    private static final class Log {
        final List<Long> shown = new ArrayList<>();
        @Nullable PromptEndReason end;
        long endTick = -1;
        boolean shownAtEnd;
    }

    private static Prompt.Builder prompt(Scenario s, String text, long ticks, Log log) {
        return Prompt.builder(Text.mm(text), ticks)
                .onShown(handle -> log.shown.add(s.ctx().currentTick()))
                .onEnd((handle, reason) -> {
                    log.end = reason;
                    log.endTick = s.ctx().currentTick();
                    log.shownAtEnd = handle.wasShown();
                });
    }

    private static Scenario scenario() {
        return Scenario.ffa(DummyAbility.INFO.id());
    }

    private static String lastActionBar(TestPlayer player) {
        List<Component> bars = player.actionBars();
        return bars.isEmpty() ? "<none>" : Scenario.plain(bars.getLast());
    }

    /** The overhead text over {@code target}, or null. */
    private static @Nullable TextDisplay overhead(Scenario s, Player target) {
        TextDisplay found = null;
        for (TextDisplay display : s.world.getEntitiesByClass(TextDisplay.class)) {
            if (display.isValid() && display.getLocation().distance(target.getLocation()) < 4) {
                found = display;
            }
        }
        return found;
    }

    @Test
    void aFreeChannelShowsAtOnceAndTheWindowIsOnScreenTime() {
        try (Scenario s = scenario()) {
            MatchContext ctx = s.ctx();
            Log log = new Log();
            s.enemy.actionBars();
            long start = ctx.currentTick();
            PromptHandle handle = ctx.prompt(s.owner, s.enemy, prompt(s, "<gold>나 예뻐?</gold>", 20, log)
                    .overhead(Text.mm("<red>나 예뻐?</red>")).build());

            assertTrue(handle.isShown(), "shown at once on a free channel");
            assertEquals(List.of(start), log.shown, "onShown ran inside prompt(...)");
            assertEquals(start, handle.shownAtTick());
            assertTrue(ctx.hasShownPrompt(s.enemy));
            assertFalse(ctx.hasShownPrompt(s.enemy2));
            assertEquals("나 예뻐?", lastActionBar(s.enemy), "the action bar is the primary display");
            TextDisplay text = overhead(s, s.enemy);
            assertNotNull(text, "a public text floats over the target");
            assertEquals("나 예뻐?", Scenario.plain(text.text()));
            assertTrue(text.getLocation().getY() > s.enemy.getLocation().getY() + s.enemy.getHeight(),
                    "over the head");

            s.ticks(19);
            assertTrue(handle.isShown());
            assertEquals(1, handle.remainingTicks());
            assertEquals(19, handle.shownTicks());
            s.ticks(1);
            assertEquals(PromptEndReason.EXPIRED, log.end);
            assertEquals(start + 20, log.endTick, "exactly its duration on screen");
            assertFalse(handle.isActive());
            assertFalse(handle.isShown());
            assertEquals(PromptEndReason.EXPIRED, handle.endReason());
            assertFalse(text.isValid(), "the overhead text is removed");
            assertEquals("", lastActionBar(s.enemy), "the action bar is cleared after the last prompt");
            assertFalse(ctx.hasShownPrompt(s.enemy));
            assertEquals(0, ctx.activePrompts(s.enemy));
            s.finishClean();
        }
    }

    @Test
    void waitingPromptsGoByPriorityThenSubmissionAndTheirWindowStartsWhenShown() {
        try (Scenario s = scenario()) {
            MatchContext ctx = s.ctx();
            List<String> order = new ArrayList<>();
            long start = ctx.currentTick();
            Log a = new Log();
            Log b = new Log();
            Log c = new Log();
            Log d = new Log();
            ctx.prompt(s.owner, s.enemy, prompt(s, "A", 10, a).onShown(h -> order.add("A")).build());
            PromptHandle low = ctx.prompt(s.owner, s.enemy, prompt(s, "B", 5, b).priority(Prompt.LOW)
                    .maxWait(100).onShown(h -> order.add("B")).build());
            PromptHandle first = ctx.prompt(s.owner, s.enemy, prompt(s, "C", 5, c).maxWait(100)
                    .onShown(h -> order.add("C")).build());
            ctx.prompt(s.owner, s.enemy, prompt(s, "D", 5, d).maxWait(100).onShown(h -> order.add("D")).build());

            assertEquals(List.of("A"), order, "an equal or lower priority waits");
            assertFalse(first.isShown());
            assertEquals(-1, first.shownAtTick());
            assertEquals(4, ctx.activePrompts(s.enemy));

            s.ticks(10);
            assertEquals(List.of("A", "C"), order, "higher priority first, then the earlier submission");
            assertEquals(start + 10, first.shownAtTick(), "the answer window starts when it is shown");
            assertEquals(5, first.remainingTicks(), "the whole window is still ahead");
            s.ticks(5);
            assertEquals(List.of("A", "C", "D"), order);
            s.ticks(5);
            assertEquals(List.of("A", "C", "D", "B"), order);
            assertTrue(low.isShown());
            s.ticks(5);
            for (Log log : List.of(a, b, c, d)) {
                assertEquals(PromptEndReason.EXPIRED, log.end);
            }
            s.finishClean();
        }
    }

    @Test
    void aHigherPriorityPushesTheShownPromptBackAndItResumesWithItsTimeLeft() {
        try (Scenario s = scenario()) {
            MatchContext ctx = s.ctx();
            Log low = new Log();
            Log high = new Log();
            long start = ctx.currentTick();
            PromptHandle riddle = ctx.prompt(s.owner, s.enemy, prompt(s, "riddle", 30, low).priority(Prompt.LOW)
                    .overhead(Component.text("?")).build());
            s.ticks(10);
            PromptHandle question = ctx.prompt(s.enemy2, s.enemy, prompt(s, "question", 10, high)
                    .priority(Prompt.HIGH).build());

            assertTrue(question.isShown(), "a strictly higher priority takes the screen at once");
            assertFalse(riddle.isShown());
            assertTrue(riddle.isActive());
            assertTrue(riddle.wasShown());
            assertEquals(10, riddle.shownTicks());
            assertEquals(-1, riddle.showingSinceTick());
            assertNull(overhead(s, s.enemy), "the pushed-back prompt's text is gone meanwhile");
            assertEquals("question", lastActionBar(s.enemy));

            s.ticks(10);
            assertEquals(PromptEndReason.EXPIRED, high.end);
            assertTrue(riddle.isShown(), "it comes back");
            assertEquals(start + 20, riddle.showingSinceTick());
            assertEquals(20, riddle.remainingTicks(), "with the on-screen time it had left");
            assertEquals(List.of(start), low.shown, "onShown runs only the first time");
            assertNotNull(overhead(s, s.enemy));
            assertFalse(riddle.isShownFor(1));
            s.ticks(1);
            assertTrue(riddle.isShownFor(1));
            assertFalse(riddle.isShownFor(2));

            s.ticks(19);
            assertEquals(PromptEndReason.EXPIRED, low.end);
            assertEquals(start + 40, low.endTick, "30 ticks on screen in all");
            s.finishClean();
        }
    }

    @Test
    void aPromptThatWaitedTooLongEndsUnseenAndNeverRunsOnShown() {
        try (Scenario s = scenario()) {
            MatchContext ctx = s.ctx();
            long start = ctx.currentTick();
            Log busy = new Log();
            Log late = new Log();
            Log now = new Log();
            ctx.prompt(s.owner, s.enemy, prompt(s, "busy", 30, busy).build());
            PromptHandle waiting = ctx.prompt(s.owner, s.enemy, prompt(s, "late", 10, late).maxWait(10).build());
            PromptHandle nowOrNever = ctx.prompt(s.owner, s.enemy, prompt(s, "now", 10, now).maxWait(0).build());

            s.ticks(1);
            assertEquals(PromptEndReason.UNSEEN, now.end, "maxWait 0: shown at once or never");
            assertFalse(nowOrNever.wasShown());
            s.ticks(8);
            assertTrue(waiting.isActive());
            s.ticks(1);
            assertEquals(PromptEndReason.UNSEEN, late.end);
            assertEquals(start + 10, late.endTick);
            assertFalse(late.shownAtEnd, "the target never saw it");
            assertTrue(late.shown.isEmpty(), "onShown never ran");
            assertEquals(-1, waiting.shownAtTick());
            assertNull(busy.end, "the shown prompt is untouched");
            s.finishClean();
        }
    }

    @Test
    void aPushedBackPromptThatCannotComeBackInTimeIsInterrupted() {
        try (Scenario s = scenario()) {
            MatchContext ctx = s.ctx();
            Log low = new Log();
            ctx.prompt(s.owner, s.enemy, prompt(s, "low", 40, low).priority(Prompt.LOW).maxWait(5).build());
            s.ticks(5);
            ctx.prompt(s.owner, s.enemy, prompt(s, "high", 20, new Log()).priority(Prompt.HIGH).build());
            s.ticks(5);
            assertEquals(PromptEndReason.INTERRUPTED, low.end);
            assertTrue(low.shownAtEnd, "it was seen, but only partly");
            s.finishClean();
        }
    }

    @Test
    void cancelEndsAPromptAndShowsTheNextInTheSameTick() {
        try (Scenario s = scenario()) {
            MatchContext ctx = s.ctx();
            Log first = new Log();
            Log second = new Log();
            PromptHandle answered = ctx.prompt(s.owner, s.enemy, prompt(s, "first", 40, first).build());
            PromptHandle next = ctx.prompt(s.owner, s.enemy, prompt(s, "second", 40, second).build());
            s.ticks(3);
            long now = ctx.currentTick();
            answered.cancel();
            assertEquals(PromptEndReason.CANCELLED, first.end);
            assertTrue(next.isShown());
            assertEquals(now, next.shownAtTick());
            assertEquals("second", lastActionBar(s.enemy));
            answered.cancel();
            answered.setActionBar(Component.text("ignored"));
            assertEquals(PromptEndReason.CANCELLED, answered.endReason(), "calls after the end do nothing");
            s.finishClean();
        }
    }

    @Test
    void theActionBarIsReassertedOverOtherMessagesAndUpdatesAtOnce() {
        try (Scenario s = scenario()) {
            MatchContext ctx = s.ctx();
            PromptHandle handle = ctx.prompt(s.owner, s.enemy, prompt(s, "<gold>3</gold>", 100, new Log()).build());
            s.enemy.actionBars();
            s.enemy.sendActionBar(Component.text("다른 능력의 상태줄"));
            s.ticks((int) PromptChannel.REFRESH_TICKS);
            assertEquals("3", lastActionBar(s.enemy), "the prompt takes the action bar back within the refresh");
            handle.setActionBar(Text.mm("<gold>2</gold>"));
            assertEquals("2", lastActionBar(s.enemy), "a new line is sent right away");
            handle.setOverhead(Component.text("2"));
            TextDisplay text = overhead(s, s.enemy);
            assertNotNull(text);
            assertEquals("2", Scenario.plain(text.text()));
            handle.setOverhead(null);
            assertFalse(text.isValid());
            s.finishClean();
        }
    }

    @Test
    void onlyTheShownPromptHasATitle() {
        try (Scenario s = scenario()) {
            MatchContext ctx = s.ctx();
            s.enemy.titles().clear();
            PromptHandle plain = ctx.prompt(s.owner, s.enemy, prompt(s, "plain", 10, new Log()).build());
            PromptHandle titled = ctx.prompt(s.owner, s.enemy, prompt(s, "titled", 30, new Log())
                    .title(Text.mm("<red>나 예뻐?</red>"), Component.text("2초 안에 대답하세요")).build());
            assertTrue(plain.isShown());
            assertTrue(s.enemy.titles().isEmpty(), "a waiting prompt never shows its title");

            s.ticks(10);
            assertTrue(titled.isShown());
            assertEquals(1, s.enemy.titles().size());
            Title title = s.enemy.titles().getFirst();
            assertEquals("나 예뻐?", Scenario.plain(title.title()));
            assertEquals("2초 안에 대답하세요", Scenario.plain(title.subtitle()));
            assertNotNull(title.times());
            assertEquals(30 * 50, title.times().stay().toMillis(), "it stays for the window");
            int clears = s.enemy.titleClears();
            titled.cancel();
            assertEquals(clears + 1, s.enemy.titleClears(), "an early end clears its title");
            s.finishClean();
        }
    }

    @Test
    void theOwnersPromptsEndWhenItsAbilityIsRevoked() {
        try (Scenario s = scenario()) {
            MatchContext ctx = s.ctx();
            Log shown = new Log();
            Log waiting = new Log();
            Log other = new Log();
            ctx.prompt(s.owner, s.enemy, prompt(s, "owner's", 40, shown).overhead(Component.text("!")).build());
            ctx.prompt(s.owner, s.enemy, prompt(s, "owner's too", 40, waiting).build());
            PromptHandle enemys = ctx.prompt(s.enemy2, s.enemy, prompt(s, "another ability's", 40, other).build());
            TextDisplay text = overhead(s, s.enemy);
            assertNotNull(text);

            s.eliminateOwner(null);
            assertEquals(PromptEndReason.OWNER_REVOKED, shown.end);
            assertEquals(PromptEndReason.OWNER_REVOKED, waiting.end);
            assertTrue(waiting.shown.isEmpty(), "never shown on the way out");
            assertFalse(text.isValid());
            assertTrue(enemys.isShown(), "the next owner's prompt takes the screen");
            assertEquals("another ability's", lastActionBar(s.enemy));

            Log refused = new Log();
            PromptHandle late = ctx.prompt(s.owner, s.enemy, prompt(s, "too late", 20, refused).build());
            assertFalse(late.isActive(), "an owner that is out cannot ask");
            assertEquals(PromptEndReason.OWNER_REVOKED, refused.end);
            assertTrue(refused.shown.isEmpty());
            s.assertNoProblems();
        }
    }

    @Test
    void theTargetsPromptsEndWhenItDiesAndASpectatorIsNeverAsked() {
        try (Scenario s = scenario()) {
            MatchContext ctx = s.ctx();
            Log shown = new Log();
            Log waiting = new Log();
            ctx.prompt(s.owner, s.enemy, prompt(s, "shown", 40, shown).overhead(Component.text("!")).build());
            ctx.prompt(s.owner, s.enemy, prompt(s, "waiting", 40, waiting).build());
            TextDisplay text = overhead(s, s.enemy);
            assertNotNull(text);

            s.enemy.setNoDamageTicks(0);
            s.enemy.damage(10_000, DamageSource.builder(DamageType.GENERIC_KILL).build());
            s.respawn(s.enemy);
            assertEquals(PromptEndReason.TARGET_LEFT, shown.end);
            assertEquals(PromptEndReason.TARGET_LEFT, waiting.end);
            assertFalse(text.isValid());

            Log refused = new Log();
            PromptHandle late = ctx.prompt(s.owner, s.enemy, prompt(s, "spectator", 20, refused).build());
            assertFalse(late.isActive());
            assertEquals(PromptEndReason.TARGET_LEFT, refused.end, "onEnd runs before prompt(...) returns");
            assertEquals(0, ctx.activePrompts(s.enemy));
            s.finishClean();
        }
    }

    @Test
    void theMatchEndEndsEveryPromptAndLeavesNothingBehind() {
        try (Scenario s = scenario()) {
            MatchContext ctx = s.ctx();
            Log shown = new Log();
            Log waiting = new Log();
            // The owner wins, so its own prompts (a vow, a chant) last until the match end.
            ctx.prompt(s.owner, s.owner, prompt(s, "vow", 400, shown).overhead(Component.text("맹세")).build());
            ctx.prompt(s.owner, s.owner, prompt(s, "next", 400, waiting).maxWait(400).build());
            s.finishMatch();
            assertEquals(PromptEndReason.MATCH_END, shown.end);
            assertEquals(PromptEndReason.MATCH_END, waiting.end);
            assertFalse(shown.end.fullySeen());
            s.assertNoProblems();
            s.assertCleanWorld();
        }
    }

    @Test
    void theOverheadTextFollowsTheTargetAndHidesFromViewersItIsHiddenFrom() {
        try (Scenario s = scenario()) {
            MatchContext ctx = s.ctx();
            ctx.prompt(s.owner, s.enemy, prompt(s, "rule", 200, new Log()).overhead(Component.text("움직이지 마라"))
                    .build());
            TextDisplay text = overhead(s, s.enemy);
            assertNotNull(text);
            assertFalse(text.isVisibleByDefault());
            assertEquals(List.of(s.owner, s.enemy2), s.seenBy(text), "everyone but the target reads it");

            s.place(s.enemy, 3.5, 6.5, 180);
            s.ticks(1);
            assertEquals(s.enemy.getX(), text.getX(), 1.0E-6, "it follows the target");
            assertEquals(s.enemy.getZ(), text.getZ(), 1.0E-6);

            // The target's stealth (hidePlayer from its enemies) hides the text from them too.
            Stealth stealth = new Stealth();
            stealth.begin(ctx, s.enemy, 100, () -> { });
            s.ticks(1);
            assertTrue(s.seenBy(text).isEmpty(), "nobody it is hidden from learns where it stands");
            stealth.end(ctx, s.enemy);
            s.ticks(1);
            assertEquals(List.of(s.owner, s.enemy2), s.seenBy(text));

            // Invisible: opponents in play lose it as well.
            s.enemy.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 100, 0, false, false));
            s.ticks(1);
            assertTrue(s.seenBy(text).isEmpty());
            assertTrue(ctx.isConcealedFrom(s.enemy, s.owner));
            assertFalse(ctx.isConcealedFrom(s.enemy, s.enemy), "never from itself");
            s.enemy.removePotionEffect(PotionEffectType.INVISIBILITY);
            s.ticks(1);
            assertEquals(List.of(s.owner, s.enemy2), s.seenBy(text));
            s.finishClean();
        }
    }

    @Test
    void theTargetsAlliesStillSeeTheTextOfAnInvisibleTarget() {
        try (Scenario s = Scenario.teams(DummyAbility.INFO.id())) {
            MatchContext ctx = s.ctx();
            ctx.prompt(s.owner, s.enemy, prompt(s, "rule", 200, new Log()).overhead(Component.text("!")).build());
            TextDisplay text = overhead(s, s.enemy);
            assertNotNull(text);
            s.enemy.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 100, 0, false, false));
            s.ticks(1);
            assertEquals(List.of(s.enemy2), s.seenBy(text), "the target's ally still sees it, opponents do not");
            s.finishClean();
        }
    }

    @Test
    void theCallbacksOfAnUltimatesPromptCountAsTheUltimate() {
        try (Scenario s = Scenario.builder(DummyAbility.INFO.id())
                .configure(h -> h.settings().set(SuperpowerSettings.GAUGE_SECOND, 0)).start()) {
            MatchContext ctx = s.ctx();
            Prompt punishing = Prompt.builder(Component.text("숨 참아!"), 2)
                    .onEnd((handle, reason) -> ctx.damage(handle.owner(), handle.target(), 4))
                    .build();
            s.match.setGauge(s.owner, 0);
            ctx.prompt(s.owner, s.enemy, punishing);
            s.ticks(2);
            double fromActive = s.participant(s.owner).gauge();
            assertTrue(fromActive > 0, "a hit from an active's prompt charges the gauge");

            s.match.setGauge(s.owner, 0);
            ctx.asUltimate(s.owner.getUniqueId(), () -> ctx.prompt(s.owner, s.enemy2, punishing));
            s.ticks(2);
            assertEquals(0.0, s.participant(s.owner).gauge(), 1.0E-9, "an ultimate never refills its own gauge");
            assertEquals(8.0, s.damageBy(s.owner, s.enemy) + s.damageBy(s.owner, s.enemy2), 1.0E-9);
            s.finishClean();
        }
    }

    @Test
    void reactionSlackFollowsThePingClamped() {
        assertEquals(1, MatchContext.reactionSlack(0));
        assertEquals(1, MatchContext.reactionSlack(-20));
        assertEquals(2, MatchContext.reactionSlack(1));
        assertEquals(2, MatchContext.reactionSlack(50));
        assertEquals(3, MatchContext.reactionSlack(51));
        assertEquals(4, MatchContext.reactionSlack(120));
        assertEquals(8, MatchContext.reactionSlack(350));
        assertEquals(8, MatchContext.reactionSlack(5_000));
        try (Scenario s = scenario()) {
            s.enemy.setPing(120);
            assertEquals(4, s.ctx().reactionSlackTicks(s.enemy));
            assertEquals(1, s.ctx().reactionSlackTicks(s.owner));
        }
    }
}
