package io.github.sx2xoox.speedrunbot.brain;

import io.github.sx2xoox.speedrunbot.core.InputLimits;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

/**
 * Per-tick intents of the brain, rate limited to human input before they reach {@link BotInputs}: turning speed,
 * one use per {@link InputLimits#USE_COOLDOWN_TICKS}, screen clicks spaced by {@link InputLimits#GUI_CLICK_TICKS},
 * one hotbar change per tick, sprint only when the client would allow it. Movement intents last one tick; tasks set
 * them every tick they want to move.
 */
public final class Controls {
    private final BotInputs in;
    private final BotSenses s;

    private float targetYaw, targetPitch;
    private boolean lookSet;
    private float forward, strafe;
    private boolean jump, sprint, sneak, attack;
    private int useCooldown, guiCooldown;
    private boolean hotbarChanged;
    private long clicks;

    public Controls(BotInputs in, BotSenses s) {
        this.in = in;
        this.s = s;
    }

    // ------------------------------------------------------------------ look

    public void lookAt(Vec3d p) {
        Vec3d e = s.eyePos();
        lookAngles(InputLimits.yawTo(p.x - e.x, p.z - e.z), InputLimits.pitchTo(p.x - e.x, p.y - e.y, p.z - e.z));
    }

    public void lookAngles(float yaw, float pitch) {
        targetYaw = yaw;
        targetPitch = pitch;
        lookSet = true;
    }

    /** True when the current camera direction is within tol degrees of looking at p. */
    public boolean aimed(Vec3d p, float tolDeg) {
        Vec3d e = s.eyePos();
        float y = InputLimits.yawTo(p.x - e.x, p.z - e.z);
        float pt = InputLimits.pitchTo(p.x - e.x, p.y - e.y, p.z - e.z);
        return Math.abs(InputLimits.wrap(y - s.yaw())) <= tolDeg && Math.abs(pt - s.pitch()) <= tolDeg;
    }

    // ------------------------------------------------------------------ movement

    public void move(float forward, float strafe, boolean jump, boolean sprint, boolean sneak) {
        this.forward = forward;
        this.strafe = strafe;
        this.jump = jump;
        this.sprint = sprint;
        this.sneak = sneak;
    }

    public void jump() {
        this.jump = true;
    }

    public void attack(boolean held) {
        this.attack = held;
    }

    // ------------------------------------------------------------------ clicks

    public boolean canUse() {
        return useCooldown <= 0;
    }

    public boolean use() {
        if (useCooldown > 0) {
            return false;
        }
        in.use();
        useCooldown = InputLimits.USE_COOLDOWN_TICKS;
        clicks++;
        return true;
    }

    public boolean attackEntity() {
        if (useCooldown > 0) {
            return false;
        }
        in.attackEntity();
        clicks++;
        return true;
    }

    public boolean guiReady() {
        return guiCooldown <= 0;
    }

    public boolean openInventory() {
        if (guiCooldown > 0) {
            return false;
        }
        in.openInventory();
        guiCooldown = InputLimits.GUI_OPEN_TICKS;
        return true;
    }

    /** Opening a block screen happens through use(); this only waits the open delay. */
    public void screenOpened() {
        guiCooldown = Math.max(guiCooldown, InputLimits.GUI_OPEN_TICKS);
    }

    public boolean click(int slotId, int button, SlotActionType type) {
        if (guiCooldown > 0) {
            return false;
        }
        in.clickSlot(slotId, button, type);
        guiCooldown = InputLimits.GUI_CLICK_TICKS;
        clicks++;
        return true;
    }

    public boolean craft(Identifier recipe, boolean all) {
        if (guiCooldown > 0) {
            return false;
        }
        in.craftRecipe(recipe, all);
        guiCooldown = InputLimits.GUI_CLICK_TICKS;
        clicks++;
        return true;
    }

    public boolean closeScreen() {
        if (guiCooldown > 0) {
            return false;
        }
        in.closeScreen();
        guiCooldown = 1;
        return true;
    }

    public boolean selectHotbar(int slot) {
        if (s.inventory().selectedSlot == slot) {
            return true;
        }
        if (hotbarChanged) {
            return false;
        }
        in.selectHotbar(slot);
        hotbarChanged = true;
        return true;
    }

    public long clicks() {
        return clicks;
    }

    // ------------------------------------------------------------------ apply

    /** Sends this tick's intents (rate limited) and clears the one-tick ones. */
    public void flush() {
        float yaw = s.yaw(), pitch = s.pitch();
        if (lookSet) {
            yaw = InputLimits.approachAngle(yaw, targetYaw, InputLimits.MAX_YAW_PER_TICK);
            pitch = InputLimits.approachPitch(pitch, targetPitch, InputLimits.MAX_PITCH_PER_TICK);
        }
        in.setLook(yaw, pitch);
        if (s.inWater() && !jump && s.screenOpen() == false) {
            jump = true; // hold space in water: stay at the surface (players do this to avoid drowning)
        }
        boolean canSprint = sprint && forward >= 0.8f && s.food() > 6 && !sneak && !s.horizontalCollision();
        in.setMovement(forward, strafe, jump, canSprint, sneak);
        in.setAttackHeld(attack && !s.screenOpen());
        forward = 0;
        strafe = 0;
        jump = false;
        sprint = false;
        sneak = false;
        attack = false;
        lookSet = false;
        hotbarChanged = false;
        if (useCooldown > 0) {
            useCooldown--;
        }
        if (guiCooldown > 0) {
            guiCooldown--;
        }
    }
}
