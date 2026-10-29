package io.github.sx2xoox.mcpl.game.augment.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.game.MatchTeam;
import io.github.sx2xoox.mcpl.core.ui.Format;
import io.github.sx2xoox.mcpl.core.ui.ManagedBossBar;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.core.ui.Sidebar;
import io.github.sx2xoox.mcpl.game.augment.AugmentSettings;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.Params;
import net.kyori.adventure.text.Component;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * The boss bar (phase timer) and every viewer's sidebar: phase and time to PvP or the border, the next augment round,
 * players alive, own kills and own augments.
 */
final class MatchHud {

    /** Augment names shown in the sidebar at most (the rest as "+n"). */
    private static final int MAX_AUGMENT_LINES = 6;
    /** 팀전 team power lines in a player's sidebar at most. */
    private static final int MAX_TEAM_LINES = 4;
    /** Max health of a member never seen in play. */
    private static final double DEFAULT_MAX_HEALTH = 20;

    private final AugmentMatch match;
    /** 팀전: every member's max health when last seen in play (the dead count as 0 of it). */
    private final Map<UUID, Double> lastMax = new HashMap<>();

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
        if (match.phase() == MatchPhase.FARMING && match.isPractice()) {
            bar.name(messages().get("bar.practice"));
            bar.progress(1f);
            return;
        }
        if (match.phase() == MatchPhase.FARMING) {
            long left = Math.max(0, settings.farmingTicks() - match.time());
            bar.name(messages().get("bar.farming", Messages.tag("time", seconds(left))));
            bar.progress(settings.farmingTicks() <= 0 ? 0f : (float) left / settings.farmingTicks());
            return;
        }
        long fight = match.fightTicks();
        String size = Params.format(Math.round(match.borderSize()));
        if (!match.borderShrinking()) {
            long left = Math.max(0, settings.borderDelayTicks() - fight);
            bar.name(messages().get("bar.fight-wait", Messages.tag("time", seconds(left)),
                    Messages.tag("size", size)));
            bar.progress(settings.borderDelayTicks() <= 0 ? 1f : (float) left / settings.borderDelayTicks());
        } else if (!match.borderFinal()) {
            long left = Math.max(0, settings.borderDelayTicks() + settings.borderShrinkTicks() - fight);
            bar.name(messages().get("bar.fight-shrink", Messages.tag("time", seconds(left)),
                    Messages.tag("size", size)));
            bar.progress(settings.borderShrinkTicks() <= 0 ? 0f : (float) left / settings.borderShrinkTicks());
        } else {
            bar.name(messages().get("bar.fight-final", Messages.tag("size", size),
                    Messages.tag("alive", match.session().aliveCount())));
            bar.progress(1f);
        }
    }

    /** {@code scaled} match-clock ticks as the real time left ("3:20"). */
    private String seconds(long scaled) {
        return Format.clock((match.realTicks(scaled) + 19) / 20);
    }

    List<Component> lines(Player viewer) {
        GameSession session = match.session();
        AugmentSettings settings = match.settings();
        List<Component> lines = new ArrayList<>();
        lines.add(Component.empty());
        if (match.phase() == MatchPhase.FARMING && match.isPractice()) {
            lines.add(messages().get("hud.practice-farming"));
        } else if (match.phase() == MatchPhase.FARMING) {
            long left = Math.max(0, settings.farmingTicks() - match.time());
            lines.add(messages().get("hud.farming", Messages.tag("time", seconds(left))));
        } else {
            lines.add(messages().get("hud.fight", Messages.tag("size", Params.format(Math.round(match.borderSize())))));
        }
        if (match.timeScale() > 1) {
            lines.add(messages().get("hud.time-scale", Messages.tag("scale", match.timeScale())));
        }
        SpectatorView spectating = match.spectators();
        if (spectating != null) {
            lines.addAll(spectating.lines());
            return lines;
        }
        long toRound = match.ticksToNextRound();
        if (match.isPractice()) {
            lines.add(messages().get(match.practicePvp() ? "hud.practice-pvp-on" : "hud.practice-pvp-off"));
        } else {
            lines.add(toRound < 0 ? messages().get("hud.rounds-done")
                    : messages().get("hud.next-round", Messages.tag("round", match.nextRoundNumber()),
                    Messages.tag("time", Format.clock((toRound + 19) / 20))));
        }
        lines.add(messages().get("hud.alive", Messages.tag("alive", session.aliveCount())));
        if (session.isTeamMode()) {
            // own team first, then the others in order
            MatchTeam own = session.teamOf(viewer.getUniqueId());
            List<MatchTeam> teams = new ArrayList<>(session.teams());
            if (own != null && teams.remove(own)) {
                teams.addFirst(own);
            }
            for (int i = 0; i < teams.size() && i < MAX_TEAM_LINES; i++) {
                lines.add(teamPower(teams.get(i), alive(teams.get(i)), "hud.team"));
            }
        }
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
            int pending = match.pendingOffers(viewer.getUniqueId());
            // the team lines take room: fewer augment names fit (the rest as "+n")
            int room = Sidebar.MAX_LINES - lines.size() - (pending > 0 ? 1 : 0);
            int shown = Math.min(augments.size(), MAX_AUGMENT_LINES);
            if (shown + (augments.size() > shown ? 1 : 0) > room) {
                shown = Math.max(0, room - 1);
            }
            for (int i = 0; i < shown; i++) {
                lines.add(messages().get("hud.augment", Messages.tag("augment", OfferMenu.name(augments.get(i)))));
            }
            if (augments.size() > shown) {
                lines.add(messages().get("hud.more", Messages.tag("count", augments.size() - shown)));
            }
            if (pending > 0 && session.isAlive(viewer)) {
                lines.add(messages().get("hud.pending", Messages.tag("count", pending)));
            }
        } else {
            lines.add(Component.empty());
            lines.add(messages().get("hud.spectating"));
        }
        return lines;
    }

    /** 팀전: how many members of {@code team} are in play. */
    private int alive(MatchTeam team) {
        int alive = 0;
        for (UUID member : team.members()) {
            if (match.inPlay(member)) {
                alive++;
            }
        }
        return alive;
    }

    /**
     * 팀전: one team's power line ({@code key}: its MiniMessage): its name, a gauge of its members' total health over
     * their total max health ({@link TeamGauge}) in its colour, the share, members alive and its kills.
     */
    Component teamPower(MatchTeam team, int alive, String key) {
        GameSession session = match.session();
        List<TeamGauge.Member> members = new ArrayList<>();
        int kills = 0;
        for (UUID member : team.members()) {
            kills += session.stats().of(member).kills();
            LivingEntity body = match.inPlay(member) ? match.body(member) : null;
            if (body != null && !body.isDead()) {
                AttributeInstance max = body.getAttribute(Attribute.MAX_HEALTH);
                double maxHealth = max == null ? DEFAULT_MAX_HEALTH : max.getValue();
                lastMax.put(member, maxHealth);
                members.add(new TeamGauge.Member(body.getHealth(), maxHealth, true));
            } else {
                members.add(new TeamGauge.Member(0, lastMax.getOrDefault(member, DEFAULT_MAX_HEALTH), false));
            }
        }
        double share = TeamGauge.share(members);
        return messages().get(key, Messages.tag("team", team.displayName()),
                Messages.tag("bar", Component.text(TeamGauge.bar(share, TeamGauge.SEGMENTS), team.color())),
                Messages.tag("percent", TeamGauge.percent(share)), Messages.tag("alive", alive),
                Messages.tag("size", team.size()), Messages.tag("kills", kills));
    }
}
