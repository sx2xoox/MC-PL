package io.github.sx2xoox.speedrunbot.core;

/**
 * Block breaking time as in 1.16.1 ({@code AbstractBlock#calcBlockBreakingDelta} + {@code PlayerEntity#getBlockBreakingSpeed}
 * + the client's {@code ClientPlayerInteractionManager} progress loop). Used by the planner to estimate costs; the
 * bot itself always mines through the server's interaction manager, so the real time is vanilla by construction.
 */
public final class MiningTime {
    /** Ticks the vanilla client waits after breaking a block before it starts the next one while attack is held. */
    public static final int BREAK_COOLDOWN_TICKS = 5;

    /** Tool tier speeds (ToolMaterials#getMiningSpeedMultiplier). */
    public static final float HAND = 1f, WOOD = 2f, STONE = 4f, IRON = 6f, DIAMOND = 8f, NETHERITE = 9f, GOLD = 12f;

    private MiningTime() {
    }

    /**
     * Progress added per tick.
     *
     * @param hardness      block hardness (-1 = unbreakable)
     * @param harvestable   true when the block drops with the held item ({@code !isToolRequired || isEffectiveOn})
     * @param toolSpeed     held item speed on this block (1 when not effective)
     * @param efficiency    efficiency level of the held tool
     * @param inWater       eyes submerged in water without aqua affinity
     * @param onGround      the player stands on ground
     */
    public static float delta(float hardness, boolean harvestable, float toolSpeed, int efficiency, boolean inWater, boolean onGround) {
        if (hardness < 0) {
            return 0f;
        }
        if (hardness == 0f) {
            return Float.POSITIVE_INFINITY;
        }
        float f = toolSpeed;
        if (f > 1f && efficiency > 0) {
            f += efficiency * efficiency + 1;
        }
        if (inWater) {
            f /= 5f;
        }
        if (!onGround) {
            f /= 5f;
        }
        return f / hardness / (harvestable ? 30f : 100f);
    }

    /**
     * Ticks of held attack until the block breaks: 0 for an instant break (delta >= 1), otherwise the number of
     * ticks after the start tick until the client's progress reaches 1. {@link Integer#MAX_VALUE} for unbreakable.
     */
    public static int ticks(float delta) {
        if (delta <= 0f) {
            return Integer.MAX_VALUE;
        }
        if (delta >= 1f) {
            return 0;
        }
        // The client adds delta once per tick and breaks when the sum reaches 1 (float accumulation).
        float progress = 0f;
        int t = 0;
        while (progress < 1f) {
            progress += delta;
            t++;
        }
        return t;
    }

    /** Ticks for one block including the vanilla re-swing cooldown that follows a non-instant break. */
    public static int ticksWithCooldown(float delta) {
        int t = ticks(delta);
        if (t == Integer.MAX_VALUE) {
            return t;
        }
        return t == 0 ? 1 : t + BREAK_COOLDOWN_TICKS;
    }
}
