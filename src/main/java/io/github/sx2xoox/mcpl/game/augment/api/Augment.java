package io.github.sx2xoox.mcpl.game.augment.api;

import org.bukkit.entity.LivingEntity;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;

/**
 * One augment owned by one player, created by the factory registered in {@link AugmentRegistry} with its
 * {@link AugmentContext}. Every hook is optional and runs on the main thread, only while the owner is in play.
 *
 * <h2>Rules</h2>
 * <ul>
 *     <li>Balance numbers come from {@link AugmentContext#params()}, never hard-coded.</li>
 *     <li>Only harm enemies ({@link AugmentContext#isEnemy}); the outgoing hooks are only called for enemies.</li>
 *     <li>Leave nothing behind: attribute modifiers through {@link AugmentContext#addModifier} and entities through
 *     {@link AugmentContext#track} are removed automatically; undo anything else in {@link #onRemove()}.</li>
 * </ul>
 * An augment is removed when its owner is eliminated or leaves, and when the match ends.
 */
public interface Augment {

    /** The owner picked it (or got it on a timeout): apply stats, hand out items. */
    default void onGrant() {
    }

    /** It is taken away (elimination, quit, match end). Modifiers and tracked entities are removed after this. */
    default void onRemove() {
    }

    /** Every server tick while the owner is in play (throttle with {@link AugmentContext#tick()}). */
    default void onTick() {
    }

    /**
     * The owner is about to hurt an enemy (melee, projectile, anything credited to them); change the damage here
     * ({@code HIGH} priority, not cancelled).
     */
    default void onOutgoingDamage(EntityDamageEvent event, LivingEntity victim) {
    }

    /** The owner hurt an enemy for {@code finalDamage} health points (monitor: do not change the event). */
    default void afterOutgoingDamage(LivingEntity victim, double finalDamage) {
    }

    /** The owner is about to take damage; change or cancel it here ({@code HIGH} priority, not cancelled). */
    default void onIncomingDamage(EntityDamageEvent event) {
    }

    /**
     * The damage would kill the owner ({@code HIGHEST} priority). Return true after cancelling the event (or making it
     * non-lethal) to save them; the other augments are then not asked.
     */
    default boolean onLethalDamage(EntityDamageEvent event) {
        return false;
    }

    /** The owner shot a bow or crossbow (the event's projectile is already created). */
    default void onShootBow(EntityShootBowEvent event) {
    }

    /** The owner pressed jump twice while allowed to fly (used for double jumps). */
    default void onToggleFlight(PlayerToggleFlightEvent event) {
    }

    /** The owner broke a block (not cancelled). */
    default void onBlockBreak(BlockBreakEvent event) {
    }

    /**
     * A block the owner broke drops its items ({@code HIGH} priority, not cancelled): change the dropped
     * {@link org.bukkit.entity.Item}s here. The block is already gone; its type is in
     * {@link BlockDropItemEvent#getBlockState()}.
     */
    default void onBlockDrop(BlockDropItemEvent event) {
    }

    /** The owner eats or drinks {@link PlayerItemConsumeEvent#getItem()} ({@code HIGH} priority, not cancelled). */
    default void onConsume(PlayerItemConsumeEvent event) {
    }

    /** The owner is about to gain experience points ({@code HIGH} priority): change the amount here. */
    default void onExpChange(PlayerExpChangeEvent event) {
    }
}
