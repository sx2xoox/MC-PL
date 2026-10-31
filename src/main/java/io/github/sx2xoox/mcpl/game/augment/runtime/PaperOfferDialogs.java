package io.github.sx2xoox.mcpl.game.augment.runtime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;

/**
 * {@link OfferDialogs} with Paper's dialog API: a multi-action dialog whose button grid ({@link OfferDialog#grid()},
 * one column per card) draws the cards side by side; it does not pause the game and closes on any click (the match
 * then shows what comes next: after a reroll the new cards, after a pick the next queued offer).
 * Buttons run one-use click callbacks that only answer the player they were shown to.
 */
public final class PaperOfferDialogs implements OfferDialogs {

    public static final PaperOfferDialogs INSTANCE = new PaperOfferDialogs();

    /** How long the buttons of a shown dialog answer (longer than any choice time; a reopen makes new ones). */
    private static final Duration LIFETIME = Duration.ofMinutes(10);

    private PaperOfferDialogs() {
    }

    @Override
    public void show(Player player, OfferDialog dialog, Consumer<OfferDialog.Action> onClick) {
        player.showDialog(build(player.getUniqueId(), dialog, onClick));
    }

    @Override
    public void close(Player player) {
        player.closeDialog();
    }

    static Dialog build(UUID owner, OfferDialog dialog, Consumer<OfferDialog.Action> onClick) {
        List<DialogBody> body = new ArrayList<>();
        for (Component line : dialog.header()) {
            body.add(DialogBody.plainMessage(line, dialog.bodyWidth()));
        }
        body.add(DialogBody.plainMessage(dialog.countdown(), dialog.bodyWidth()));

        List<ActionButton> buttons = new ArrayList<>();
        for (OfferDialog.Button button : dialog.grid()) {
            buttons.add(ActionButton.builder(button.label()).tooltip(button.tooltip()).width(button.width())
                    .action(callback(owner, button.action(), onClick)).build());
        }
        ActionButton later = ActionButton.builder(dialog.later()).tooltip(dialog.laterTooltip())
                .width(OfferDialog.LATER_WIDTH).action(callback(owner, OfferDialog.Action.LATER, onClick)).build();

        DialogBase base = DialogBase.builder(dialog.title())
                .externalTitle(dialog.externalTitle())
                .canCloseWithEscape(true) // Esc runs the exit button: the offer waits, /게임 증강 reopens it
                .pause(false)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .body(body)
                .build();
        DialogType type = DialogType.multiAction(buttons).exitAction(later).columns(dialog.columns()).build();
        return Dialog.create(factory -> factory.empty().base(base).type(type));
    }

    private static DialogAction callback(UUID owner, OfferDialog.Action action, Consumer<OfferDialog.Action> onClick) {
        return DialogAction.customClick((response, audience) -> {
            if (audience instanceof Player player && player.getUniqueId().equals(owner)) {
                onClick.accept(action);
            }
        }, ClickCallback.Options.builder().uses(1).lifetime(LIFETIME).build());
    }
}
