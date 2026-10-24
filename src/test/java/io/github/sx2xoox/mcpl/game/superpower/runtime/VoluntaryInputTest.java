package io.github.sx2xoox.mcpl.game.superpower.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Inputs;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Motion;
import io.github.sx2xoox.mcpl.game.superpower.ability.InputKey;
import io.github.sx2xoox.mcpl.game.superpower.support.DummyAbility;
import io.github.sx2xoox.mcpl.game.superpower.support.Scenario;
import io.github.sx2xoox.mcpl.game.superpower.support.TestInput;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * Voluntary input for rules (kit {@code Inputs} and the context's input history): held keys, presses and releases
 * between ticks, swings, ping slack, and the exemptions for hard crowd control and displacement.
 */
class VoluntaryInputTest {

    /** A scenario whose players stand still and are no longer displaced by the layout teleports. */
    private static Scenario settled() {
        Scenario s = Scenario.ffa(DummyAbility.INFO.id());
        s.ticks((int) Inputs.DISPLACED_TICKS + 1);
        return s;
    }

    @Test
    void theTestClientHasASettableInputAndSendsItLikeAServer() {
        try (Scenario s = settled()) {
            assertEquals(TestInput.NONE, s.enemy.getCurrentInput(), "nothing held by default");
            s.enemy.setInput(TestInput.of(InputKey.FORWARD, InputKey.SPRINT));
            assertTrue(Inputs.isHeld(s.enemy, InputKey.FORWARD));
            assertTrue(Inputs.isHeld(s.enemy, InputKey.SPRINT));
            assertFalse(Inputs.isHeld(s.enemy, InputKey.JUMP));
            assertTrue(Inputs.isWalking(s.enemy));

            List<Input> during = new ArrayList<>();
            s.h.server.getPluginManager().registerEvents(new Listener() {
                @EventHandler
                public void onInput(PlayerInputEvent event) {
                    during.add(TestInput.copyOf(event.getPlayer().getCurrentInput()));
                }
            }, MockBukkit.createMockPlugin("InputProbe"));
            s.enemy.sendInput(InputKey.JUMP);
            assertEquals(List.of(TestInput.of(InputKey.FORWARD, InputKey.SPRINT)), during,
                    "during the event the player still reports its old input, like Paper");
            assertEquals(TestInput.of(InputKey.JUMP), s.enemy.getCurrentInput());
            s.enemy.sendInput(InputKey.JUMP);
            assertEquals(1, during.size(), "an unchanged input sends nothing");
            assertFalse(Inputs.isWalking(s.enemy));
            s.finishClean();
        }
    }

    @Test
    void pressesAndReleasesAreRecordedSoATapBetweenTwoTicksCounts() {
        try (Scenario s = settled()) {
            MatchContext ctx = s.ctx();
            long now = ctx.currentTick();
            assertEquals(-1, ctx.lastPressTick(s.enemy, InputKey.FORWARD));
            s.enemy.sendInput(InputKey.FORWARD);
            s.enemy.sendInput();
            assertEquals(now, ctx.lastPressTick(s.enemy, InputKey.FORWARD));
            assertEquals(now, ctx.lastReleaseTick(s.enemy, InputKey.FORWARD));
            assertFalse(Inputs.isHeld(s.enemy, InputKey.FORWARD));
            assertTrue(Inputs.usedSince(ctx, s.enemy, InputKey.FORWARD, now), "the tap counts");
            assertTrue(Inputs.walkedSince(ctx, s.enemy, now));
            s.ticks(1);
            assertFalse(Inputs.usedSince(ctx, s.enemy, InputKey.FORWARD, now + 1));
            assertFalse(Inputs.walkedSince(ctx, s.enemy, now + 1));

            // A key held since before the first input event is not a fresh press.
            s.enemy2.setInput(TestInput.of(InputKey.BACKWARD));
            s.enemy2.sendInput(InputKey.BACKWARD, InputKey.JUMP);
            assertEquals(-1, ctx.lastPressTick(s.enemy2, InputKey.BACKWARD));
            assertEquals(now + 1, ctx.lastPressTick(s.enemy2, InputKey.JUMP));
            assertTrue(Inputs.usedSince(ctx, s.enemy2, InputKey.BACKWARD, now + 1), "but it is held");
            s.finishClean();
        }
    }

    @Test
    void armSwingsAreRecorded() {
        try (Scenario s = settled()) {
            MatchContext ctx = s.ctx();
            long now = ctx.currentTick();
            assertEquals(-1, ctx.lastSwingTick(s.enemy));
            s.enemy.swingArm();
            assertEquals(now, ctx.lastSwingTick(s.enemy));
            assertTrue(Inputs.swungSince(ctx, s.enemy, now));
            assertTrue(Inputs.breaksSwing(ctx, s.enemy, now), "swings are never exempt");
            assertFalse(Inputs.breaksSwing(ctx, s.enemy, now + 1), "not judged before the judging starts");
            s.finishClean();
        }
    }

    @Test
    void theJudgingStartsAfterTheGraceAndThePingSlack() {
        try (Scenario s = settled()) {
            MatchContext ctx = s.ctx();
            s.enemy.setPing(100);
            assertEquals(3, ctx.reactionSlackTicks(s.enemy));
            assertEquals(50 + 12 + 3, Inputs.judgeFrom(ctx, s.enemy, 50, 12));
            assertEquals(50 + 3, Inputs.judgeFrom(ctx, s.enemy, 50, -4), "a negative grace is none");

            long from = Inputs.judgeFrom(ctx, s.enemy, ctx.currentTick(), 4);
            s.enemy.sendInput(InputKey.LEFT);
            s.enemy.sendInput();
            assertFalse(Inputs.breaksWalking(ctx, s.enemy, from, null), "a tap during the grace is not judged");
            s.ticks((int) (from - ctx.currentTick()));
            assertFalse(Inputs.breaksWalking(ctx, s.enemy, from, null), "it ended before the judging started");
            s.enemy.sendInput(InputKey.LEFT);
            assertTrue(Inputs.breaksWalking(ctx, s.enemy, from, null));
            assertTrue(Inputs.breaks(ctx, s.enemy, InputKey.LEFT, from, null));
            assertFalse(Inputs.breaks(ctx, s.enemy, InputKey.RIGHT, from, null));
            s.finishClean();
        }
    }

    @Test
    void pushesDisplaceAPlayerButStoppingItDoesNot() {
        try (Scenario s = settled()) {
            MatchContext ctx = s.ctx();
            assertFalse(ctx.wasDisplaced(s.enemy, Inputs.DISPLACED_TICKS), "the layout teleport is long past");
            assertFalse(Inputs.isExempt(ctx, s.enemy, InputKey.FORWARD, null));

            s.enemy.setVelocity(new Vector());
            assertFalse(ctx.wasDisplaced(s.enemy, Inputs.DISPLACED_TICKS), "stopping a player is no displacement");

            long now = ctx.currentTick();
            assertTrue(Motion.push(s.enemy, new Vector(0.8, 0.3, 0), 2.0));
            assertEquals(now, ctx.lastDisplacedTick(s.enemy));
            assertTrue(Inputs.isDisplaced(ctx, s.enemy));
            assertTrue(Inputs.isExempt(ctx, s.enemy, InputKey.FORWARD, null));
            assertTrue(Inputs.isExempt(ctx, s.enemy, InputKey.JUMP, null));
            assertFalse(Inputs.isExempt(ctx, s.enemy, InputKey.SNEAK, null), "sneaking is always voluntary");

            s.ticks((int) Inputs.DISPLACED_TICKS);
            assertTrue(Inputs.isDisplaced(ctx, s.enemy), "exempt for half a second");
            s.ticks(1);
            assertFalse(Inputs.isDisplaced(ctx, s.enemy));
            s.finishClean();
        }
    }

    @Test
    void voluntaryVelocityIsNoDisplacement() {
        try (Scenario s = settled()) {
            MatchContext ctx = s.ctx();
            ctx.setVoluntaryVelocity(s.enemy, new Vector(0.3, 0.45, 0));
            assertFalse(ctx.wasDisplaced(s.enemy, Inputs.DISPLACED_TICKS), "a hop the target's own keys asked for");
            assertEquals(0.45, s.enemy.getVelocity().getY(), 1.0E-9, "the velocity is applied");
            s.enemy.setVelocity(new Vector(0, 0.8, 0));
            assertTrue(ctx.wasDisplaced(s.enemy, Inputs.DISPLACED_TICKS), "a later push still displaces");
            s.finishClean();
        }
    }

    @Test
    void teleportsThatMoveAPlayerDisplaceItButTurningItDoesNot() {
        try (Scenario s = settled()) {
            MatchContext ctx = s.ctx();
            s.aimAt(s.enemy, s.owner);
            assertFalse(ctx.wasDisplaced(s.enemy, Inputs.DISPLACED_TICKS), "a turn in place");
            Location pearl = s.enemy.getLocation().add(2, 0, 0);
            s.enemy.teleport(pearl, PlayerTeleportEvent.TeleportCause.ENDER_PEARL);
            assertFalse(ctx.wasDisplaced(s.enemy, Inputs.DISPLACED_TICKS), "its own ender pearl");
            s.enemy.teleport(s.enemy.getLocation().add(0, 0, 3));
            assertTrue(ctx.wasDisplaced(s.enemy, Inputs.DISPLACED_TICKS), "a plugin teleport");
            s.finishClean();
        }
    }

    @Test
    void vanillaKnockbackDisplaces() {
        try (Scenario s = settled()) {
            MatchContext ctx = s.ctx();
            s.h.server.getPluginManager().callEvent(new EntityKnockbackEvent(s.enemy,
                    EntityKnockbackEvent.Cause.ENTITY_ATTACK, new Vector(0.4, 0.4, 0)));
            assertTrue(ctx.wasDisplaced(s.enemy, Inputs.DISPLACED_TICKS));
            s.finishClean();
        }
    }

    @Test
    void hardCrowdControlExemptsUnlessItIsTheRuleOwnersOwn() {
        try (Scenario s = settled()) {
            MatchContext ctx = s.ctx();
            ctx.addModifier(s.owner, s.enemy, Attribute.MOVEMENT_SPEED, -0.5, Operation.MULTIPLY_SCALAR_1, 40);
            assertFalse(ctx.isMovementLocked(s.enemy, null), "a slow is no lock");

            ctx.addModifier(s.enemy2, s.enemy, Attribute.MOVEMENT_SPEED, -1.0, Operation.MULTIPLY_SCALAR_1, 20);
            assertTrue(ctx.isMovementLocked(s.enemy, null), "a stun locks movement");
            assertTrue(ctx.isMovementLocked(s.enemy, s.owner));
            assertFalse(ctx.isMovementLocked(s.enemy, s.enemy2), "the rule owner's own lock does not count");
            assertFalse(ctx.isJumpLocked(s.enemy, null));
            assertTrue(Inputs.isExempt(ctx, s.enemy, InputKey.FORWARD, null));
            assertFalse(Inputs.isExempt(ctx, s.enemy, InputKey.FORWARD, s.enemy2));
            assertFalse(Inputs.isExempt(ctx, s.enemy, InputKey.JUMP, null), "jumping is not locked");

            ctx.addModifier(s.owner, s.enemy, Attribute.JUMP_STRENGTH, -1.0, Operation.MULTIPLY_SCALAR_1, 20);
            assertTrue(ctx.isJumpLocked(s.enemy, null));
            assertTrue(Inputs.isExempt(ctx, s.enemy, InputKey.JUMP, null));

            long from = ctx.currentTick();
            s.enemy.sendInput(InputKey.FORWARD);
            assertFalse(Inputs.breaks(ctx, s.enemy, InputKey.FORWARD, from, null), "not judged while stunned");
            assertTrue(Inputs.breaks(ctx, s.enemy, InputKey.FORWARD, from, s.enemy2),
                    "the rule owner's own lock judges the keys anyway");

            s.ticks(21);
            assertFalse(ctx.isMovementLocked(s.enemy, null), "the lock ended with its modifier");
            assertFalse(ctx.isJumpLocked(s.enemy, null));
            assertTrue(Inputs.breaks(ctx, s.enemy, InputKey.FORWARD, from, null), "a key still held afterwards counts");
            s.finishClean();
        }
    }

    @Test
    void keysUsedWhileBeingPushedDoNotCountButAKeyStillHeldAfterwardsDoes() {
        try (Scenario s = settled()) {
            MatchContext ctx = s.ctx();
            long from = ctx.currentTick();
            Motion.push(s.enemy, new Vector(0, 0.6, 0.9), 2.0);
            s.enemy.sendInput(InputKey.BACKWARD);
            assertFalse(Inputs.breaksWalking(ctx, s.enemy, from, null), "exempt while displaced");
            s.ticks(3);
            s.enemy.sendInput();
            s.ticks((int) Inputs.DISPLACED_TICKS);
            assertFalse(Inputs.breaksWalking(ctx, s.enemy, from, null),
                    "a key pressed and released while being pushed does not count afterwards");
            s.enemy.sendInput(InputKey.BACKWARD);
            assertTrue(Inputs.breaksWalking(ctx, s.enemy, from, null));

            s.enemy.sendInput();
            Motion.push(s.enemy2, new Vector(0.9, 0.2, 0), 2.0);
            s.enemy2.sendInput(InputKey.FORWARD);
            s.ticks((int) Inputs.DISPLACED_TICKS);
            assertFalse(Inputs.breaksWalking(ctx, s.enemy2, from, null));
            s.ticks(1);
            assertTrue(Inputs.breaksWalking(ctx, s.enemy2, from, null), "still holding it when the window ends");
            s.finishClean();
        }
    }
}
