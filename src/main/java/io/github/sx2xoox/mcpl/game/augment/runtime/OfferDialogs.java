package io.github.sx2xoox.mcpl.game.augment.runtime;

import java.util.function.Consumer;

import org.bukkit.entity.Player;

/**
 * Shows augment pick dialogs ({@link OfferDialog}) to players and reports their clicks. The plugin uses
 * {@link PaperOfferDialogs}; tests swap in a recorder (MockBukkit cannot build Paper dialogs).
 */
public interface OfferDialogs {

    /**
     * Shows {@code dialog} to {@code player}, replacing any dialog they see. {@code onClick} runs on the main thread
     * for each button the player clicks (also the exit button, which Esc presses), at most once per button.
     */
    void show(Player player, OfferDialog dialog, Consumer<OfferDialog.Action> onClick);

    /** Closes the dialog the player sees, if any. */
    void close(Player player);
}
