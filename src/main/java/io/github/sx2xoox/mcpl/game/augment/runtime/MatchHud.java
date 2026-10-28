package io.github.sx2xoox.mcpl.game.augment.runtime;

import java.util.ArrayList;
import java.util.List;

import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.ui.Format;
import io.github.sx2xoox.mcpl.core.ui.ManagedBossBar;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.core.ui.Sidebar;
import io.github.sx2xoox.mcpl.game.augment.AugmentSettings;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.Params;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * The boss bar (phase timer) and every viewer's sidebar: phase and time to PvP or the border, the next augment round,
 * players alive, own kills and own augments.
 */
final class MatchHud {

    /** Augment names shown in the sidebar at most (the rest as "+n"). */
    private static final int MAX_AUGMENT_LINES = 6;

    private final AugmentMatch match;

    MatchHud(AugmentMatch match) {
        this.match = match;
    }

    private Messages messages() {
        return match.messages();
    }

    void update() {
        GameSession session = match.session();
        if (!session.isRunning() || match.phase() == MatchPhase.PREPARING) {
            return;
        }
        updateBar();
        for (Player viewer : session.viewers()) {
            try {
                Sidebar sidebar = session.sidebar(viewer);
                sidebar.title(match.game().displayComponent());
                sidebar.lines(lines(viewer));
            } catch (IllegalArgumentException notMember) {
                // a player that is not part of the match (yet)
            }
        }
    }

    private void updateBar() {
        ManagedBossBar bar = match.bar();
        if (bar == null) {
            return;
        }
        AugmentSettings settings = match.settings();
        if (match.phase() == MatchPhase.FARMING) {
            long left = Math.max(0, settings.farmingTicks() - match.clock());
            bar.name(messages().get("bar.farming", Messages.tag("time", Format.clock((left + 19) / 20))));
            bar.progress(settings.farmingTicks() <= 0 ? 0f : (float) left / settings.farmingTicks());
            return;
        }
        long fight = match.fightTicks();
        String size = Params.format(Math.round(match.borderSize()));
        if (!match.borderShrinking()) {
            long left = Math.max(0, settings.borderDelayTicks() - fight);
            bar.name(messages().get("bar.fight-wait", Messages.tag("time", Format.clock((left + 19) / 20)),
                    Messages.tag("size", size)));
            bar.progress(settings.borderDelayTicks() <= 0 ? 1f : (float) left / settings.borderDelayTicks());
        } else if (!match.borderFinal()) {
            long left = Math.max(0, settings.borderDelayTicks() + settings.borderShrinkTicks() - fight);
            bar.name(messages().get("bar.fight-shrink", Messages.tag("time", Format.clock((left + 19) / 20)),
                    Messages.tag("size", size)));
            bar.progress(settings.borderShrinkTicks() <= 0 ? 0f : (float) left / settings.borderShrinkTicks());
        } else {
            bar.name(messages().get("bar.fight-final", Messages.tag("size", size),
                    Messages.tag("alive", match.session().aliveCount())));
            bar.progress(1f);
        }
    }

    List<Component> lines(Player viewer) {
        GameSession session = match.session();
        AugmentSettings settings = match.settings();
        List<Component> lines = new ArrayList<>();
        lines.add(Component.empty());
        if (match.phase() == MatchPhase.FARMING) {
            long left = Math.max(0, settings.farmingTicks() - match.clock());
            lines.add(messages().get("hud.farming", Messages.tag("time", Format.clock((left + 19) / 20))));
        } else {
            lines.add(messages().get("hud.fight", Messages.tag("size", Params.format(Math.round(match.borderSize())))));
        }
        long toRound = match.ticksToNextRound();
        lines.add(toRound < 0 ? messages().get("hud.rounds-done")
                : messages().get("hud.next-round", Messages.tag("round", match.nextRoundNumber()),
                Messages.tag("time", Format.clock((toRound + 19) / 20))));
        lines.add(messages().get("hud.alive", Messages.tag("alive", session.aliveCount())));
        boolean participant = session.isParticipant(viewer);
        if (participant) {
            lines.add(messages().get("hud.kills", Messages.tag("kills",
                    session.stats().of(viewer.getUniqueId()).kills())));
            List<AugmentInfo> augments = match.augments(viewer.getUniqueId());
            lines.add(Component.empty());
            lines.add(messages().get("hud.augments", Messages.tag("count", augments.size()),
                    Messages.tag("rerolls", match.rerollsLeft(viewer.getUniqueId()))));
            if (augments.isEmpty()) {
                lines.add(messages().get("hud.no-augments"));
            }
            for (int i = 0; i < augments.size() && i < MAX_AUGMENT_LINES; i++) {
                lines.add(messages().get("hud.augment", Messages.tag("augment", OfferMenu.name(augments.get(i)))));
            }
            if (augments.size() > MAX_AUGMENT_LINES) {
                lines.add(messages().get("hud.more", Messages.tag("count", augments.size() - MAX_AUGMENT_LINES)));
            }
            int pending = match.pendingOffers(viewer.getUniqueId());
            if (pending > 0 && session.isAlive(viewer)) {
                lines.add(messages().get("hud.pending", Messages.tag("count", pending)));
            }
        } else {
            lines.add(Component.empty());
            lines.add(messages().get("hud.spectating"));
        }
        return lines;
    }
}
