package io.github.sx2xoox.speedrunbot.brain;

import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.recipe.Recipe;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Collection;
import java.util.List;

/**
 * Input side of the brain: what a player knows from its own screen. Its own state (position, look, health, food,
 * inventory, open screen, crosshair target, game time) plus raw block/entity access that only {@link Perception}
 * may use, and only along sight rays inside the view frustum ({@link BlockSource}). The server fake player
 * implements it from the ServerWorld; a client port would implement it from MinecraftClient / ClientWorld.
 */
public interface BotSenses extends BlockSource {
    /** Game ticks since the world started (the IGT clock source). */
    long gameTick();

    Vec3d pos();

    Vec3d eyePos();

    Vec3d velocity();

    float yaw();

    float pitch();

    boolean onGround();

    boolean inWater();

    boolean inLava();

    boolean onFire();

    /** Remaining air ticks (300 = full; the client shows it as bubbles). */
    int air();

    /** Eyes under water. */
    boolean submerged();

    /**
     * The map held in the main hand as its screen shows it (scale and markers: the player's own and decorations such
     * as the treasure X), or null when no filled map is held.
     */
    io.github.sx2xoox.speedrunbot.core.TreasureMap.Reading heldMap();

    boolean horizontalCollision();

    float health();

    int food();

    boolean alive();

    /** "overworld", "the_nether" or "the_end". */
    String dimension();

    PlayerInventory inventory();

    /** The open screen handler (the player inventory when no other screen is open). */
    ScreenHandler screen();

    boolean screenOpen();

    /** Index of the player-inventory slot shown at a screen slot, or -1 for a container slot. */
    int playerInvIndexOfScreenSlot(int screenSlot);

    /** Crafting recipes unlocked in the player's recipe book. */
    Collection<Recipe<?>> knownCraftingRecipes();

    /** What the crosshair points at (client: MinecraftClient#crosshairTarget). */
    HitResult crosshair();

    /**
     * The fluid source (or block) the crosshair points at when fluid sources count as targets: what an empty bucket
     * would scoop (the player sees the lava/water surface it aims at).
     */
    HitResult fluidCrosshair();

    /** Progress per tick of breaking this block with the held item in the current situation (vanilla formula). */
    float breakingDelta(BlockState state, BlockPos pos);

    /** Entities near the player; only {@link Perception} filters them by view and line of sight. */
    List<Entity> entitiesNear(Box box);

    /** True while the bot is inside a nether portal block. */
    boolean inPortal();

    /**
     * Lava sounds heard this tick, as a player perceives them (rough direction and distance only, see
     * {@link io.github.sx2xoox.speedrunbot.core.Hearing}); empty when hearing is off.
     */
    List<io.github.sx2xoox.speedrunbot.core.Hearing.Heard> heardLava();
}
