package io.github.sx2xoox.mcpl.game.augment.support;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import io.github.sx2xoox.mcpl.game.augment.runtime.OfferDialog;
import io.github.sx2xoox.mcpl.game.augment.runtime.OfferDialogs;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Records the augment pick dialogs shown to players and plays a client: a click closes the dialog (after action
 * {@code CLOSE}) and runs that button's one-use callback.
 */
public final class TestDialogs implements OfferDialogs {

    /** One dialog as shown. */
    public static final class Shown {

        public final OfferDialog dialog;
        private final Consumer<OfferDialog.Action> onClick;
        private final Set<OfferDialog.Action> used = new HashSet<>();

        Shown(OfferDialog dialog, Consumer<OfferDialog.Action> onClick) {
            this.dialog = dialog;
            this.onClick = onClick;
        }
    }

    private final Map<UUID, Shown> open = new HashMap<>();
    private final List<Shown> history = new ArrayList<>();
    private int closes;

    @Override
    public void show(Player player, OfferDialog dialog, Consumer<OfferDialog.Action> onClick) {
        Shown shown = new Shown(dialog, onClick);
        open.put(player.getUniqueId(), shown);
        history.add(shown);
    }

    @Override
    public void close(Player player) {
        if (open.remove(player.getUniqueId()) != null) {
            closes++;
        }
    }

    /** The dialog the player sees, or null. */
    public @Nullable Shown open(Player player) {
        return open.get(player.getUniqueId());
    }

    /** Every dialog shown, in order. */
    public List<Shown> history() {
        return history;
    }

    /** Dialogs the server closed. */
    public int closes() {
        return closes;
    }

    /** The player clicks a button of the dialog they see. */
    public void click(Player player, OfferDialog.Action action) {
        Shown shown = open.get(player.getUniqueId());
        if (shown == null) {
            throw new AssertionError("no dialog open for " + player.getName());
        }
        click(player, shown, action);
    }

    /** The player clicks a button of {@code shown} (maybe a stale dialog; each button answers once). */
    public void click(Player player, Shown shown, OfferDialog.Action action) {
        if (open.get(player.getUniqueId()) == shown) {
            open.remove(player.getUniqueId());
        }
        if (shown.used.add(action)) {
            shown.onClick.accept(action);
        }
    }

    /** Esc: runs the exit button. */
    public void escape(Player player) {
        click(player, OfferDialog.Action.LATER);
    }
}
