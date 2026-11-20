package io.github.sx2xoox.speedrunbot.brain;

import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;

/**
 * Output side of the brain: exactly the controls a player has on a vanilla client (keys, mouse, recipe book).
 * The server fake player implements it by driving a {@link net.minecraft.entity.player.PlayerEntity} through the
 * vanilla server code paths; a client port would implement it by pressing KeyBindings / steering the
 * ClientPlayerEntity input and calling ClientPlayerInteractionManager. Rate limits (turn speed, click spacing) are
 * applied by the brain's {@link Controls} before anything reaches this interface.
 */
public interface BotInputs {
    /** W/S (forward -1..1), A/D (strafe -1..1), space, ctrl (sprint), shift (sneak); held for the next tick. */
    void setMovement(float forward, float strafe, boolean jump, boolean sprint, boolean sneak);

    /** Camera angles for the next tick (already rate limited). */
    void setLook(float yaw, float pitch);

    /** Left mouse button held: mines the block under the crosshair with the client's progress rules. */
    void setAttackHeld(boolean held);

    /** One left click on the entity under the crosshair (melee attack). */
    void attackEntity();

    /** One right click: use the held item on the crosshair block, or in the air (buckets, food...). */
    void use();

    /**
     * One right click on the entity under the crosshair (any entity the client can target, e.g. a boat to get in).
     * While riding a boat, the movement keys steer it (A/D turn, W/S paddle) and sneak gets out, as on a client.
     */
    void useEntity();

    /** Hotbar key 1-9 (0-based). */
    void selectHotbar(int slot);

    /** Opens the player's own inventory screen (E). */
    void openInventory();

    /** One click in the open screen (slot id of the open screen handler, mouse button / hotbar key, action). */
    void clickSlot(int slotId, int button, SlotActionType action);

    /** A recipe book click in the open crafting screen. */
    void craftRecipe(Identifier recipe, boolean craftAll);

    /** Esc: closes any open screen. */
    void closeScreen();
}
