package io.github.sx2xoox.mcpl.game.superpower.runtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.game.MatchTeam;
import io.github.sx2xoox.mcpl.core.ui.Format;
import io.github.sx2xoox.mcpl.core.ui.ManagedBossBar;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.core.ui.Sidebar;
import io.github.sx2xoox.mcpl.game.superpower.SelectionMode;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ui.AbilityTexts;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * The match HUD: one shared boss bar (phase and border timer) and a private sidebar per viewer (alive count, own
 * kills, own ability with cooldown/gauge, team, border state, time). Spectators get a reduced sidebar. Updated from
 * the match tick (every 10 ticks); the sidebar only sends changed lines.
 */
final class MatchHud {

    private final SuperpowerMatch match;
    private final GameSession session;
    private final Messages messages;
    private final ManagedBossBar bar;
    private final Set<UUID> brokenSidebars = new HashSet<>();
    private boolean reported;

    MatchHud(SuperpowerMatch match) {
        this.match = match;
        this.session = match.session();
        this.messages = match.messages();
        this.bar = session.sharedBossBar(messages.get("bossbar.intro"), 1f, BossBar.Color.PURPLE,
                BossBar.Overlay.PROGRESS);
    }

    /** Refreshes the boss bar and every viewer's sidebar. HUD failures are logged once and never stop the match. */
    void update() {
        try {
            updateBossBar();
        } catch (RuntimeException e) {
            report("boss bar", e);
        }
        for (Player viewer : session.viewers()) {
            if (brokenSidebars.contains(viewer.getUniqueId()) || !session.isMember(viewer)) {
                continue;
            }
            try {
                Sidebar sidebar = session.sidebar(viewer);
                sidebar.title(messages.get("hud.title"));
                Participant participant = match.participant(viewer.getUniqueId());
                sidebar.lines(participant != null && session.isInPlay(viewer)
                        ? playerLines(participant) : spectatorLines(participant));
            } catch (RuntimeException e) {
                brokenSidebars.add(viewer.getUniqueId());
                report("sidebar of " + viewer.getName(), e);
            }
        }
    }

    private void report(String what, RuntimeException error) {
        if (!reported) {
            reported = true;
            session.plugin().getLogger().log(Level.WARNING, "Could not update the superpower HUD (" + what
                    + "); the match continues without it", error);
        }
    }

    // ------------------------------------------------------------------ boss bar

    private void updateBossBar() {
        long phaseTicks = match.phaseTicks();
        switch (match.phase()) {
            case INTRO -> show(messages.get("bossbar.intro"), 1f, BossBar.Color.PURPLE);
            case SELECT -> {
                long left = Math.max(0, match.settings().choiceTicks() - phaseTicks);
                show(messages.get("bossbar.select", Messages.tag("seconds", seconds(left))),
                        ratio(left, match.settings().choiceTicks()), BossBar.Color.PURPLE);
            }
            case REVEAL -> show(messages.get("bossbar.reveal"), 1f, BossBar.Color.PURPLE);
            case PREP -> {
                long left = Math.max(0, match.settings().prepTicks() - phaseTicks);
                show(messages.get("bossbar.prep", Messages.tag("seconds", seconds(left))),
                        ratio(left, match.settings().prepTicks()), BossBar.Color.GREEN);
            }
            case FIGHT -> fightBossBar();
        }
    }

    private void fightBossBar() {
        BorderController border = match.border();
        long fight = match.fightTicks();
        switch (border.state(fight)) {
            case WAITING -> {
                long left = border.ticksUntilShrink(fight);
                show(messages.get("bossbar.border-waiting", Messages.tag("time", clock(left))),
                        ratio(left, border.delayTicks()), BossBar.Color.BLUE);
            }
            case SHRINKING -> {
                long left = border.shrinkRemainingTicks();
                show(messages.get("bossbar.border-shrinking", Messages.tag("time", clock(left))),
                        ratio(left, border.shrinkTicks()), BossBar.Color.RED);
            }
            case FINAL -> {
                long limit = match.settings().timeLimitTicks();
                if (limit > 0) {
                    long left = Math.max(0, limit - fight);
                    show(messages.get("bossbar.final", Messages.tag("time", clock(left))), ratio(left, limit),
                            BossBar.Color.YELLOW);
                } else {
                    show(messages.get("bossbar.final-unlimited"), 1f, BossBar.Color.YELLOW);
                }
            }
        }
    }

    private void show(Component name, float progress, BossBar.Color color) {
        bar.name(name).progress(progress);
        if (bar.bar().color() != color) {
            bar.color(color);
        }
    }

    // ------------------------------------------------------------------ sidebar

    private List<Component> playerLines(Participant participant) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.empty());
        lines.add(aliveLine());
        lines.add(messages.get("hud.kills", Messages.tag("kills", kills(participant))));
        lines.add(Component.empty());
        AbilityInfo info = match.info(participant.abilityId);
        boolean revealed = info != null && !match.isRevealing(participant);
        lines.add(revealed
                ? messages.get("hud.ability", Messages.tag("ability", AbilityTexts.name(info)))
                : messages.get("hud.ability-hidden"));
        switch (match.phase()) {
            case INTRO, SELECT, REVEAL -> lines.add(messages.get("hud.phase-reveal"));
            case PREP -> {
                long left = Math.max(0, match.settings().prepTicks() - match.phaseTicks());
                lines.add(messages.get("hud.prep", Messages.tag("seconds", seconds(left))));
                if (match.settings().selectionMode() == SelectionMode.RANDOM && participant.rerollsLeft > 0
                        && !participant.forced) {
                    lines.add(messages.get("hud.rerolls", Messages.tag("count", participant.rerollsLeft)));
                }
            }
            case FIGHT -> {
                long now = match.fightTicks();
                if (participant.isSilenced(now)) {
                    lines.add(messages.get("hud.silenced",
                            Messages.tag("seconds", Format.number((participant.silencedUntil - now) / 20.0))));
                } else if (participant.cooldown.isReady(now)) {
                    lines.add(messages.get("hud.active-ready"));
                } else {
                    lines.add(messages.get("hud.active-cooldown",
                            Messages.tag("seconds", Format.number(participant.cooldown.remainingSeconds(now)))));
                }
                lines.add(participant.gauge.isFull() ? messages.get("hud.ultimate-ready")
                        : messages.get("hud.ultimate", Messages.tag("percent", participant.gauge.level())));
            }
        }
        if (session.isTeamMode()) {
            MatchTeam team = session.teamOf(participant.id());
            if (team != null) {
                lines.add(Component.empty());
                lines.add(messages.get("hud.team", Messages.tag("team", team.displayName())));
            }
        }
        if (match.phase() == MatchPhase.FIGHT) {
            lines.add(Component.empty());
            lines.add(borderLine());
            lines.add(timeLine());
        }
        return lines;
    }

    private List<Component> spectatorLines(@Nullable Participant eliminated) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.empty());
        lines.add(aliveLine());
        if (eliminated != null) {
            lines.add(messages.get("hud.kills", Messages.tag("kills", kills(eliminated))));
        }
        if (match.phase() == MatchPhase.FIGHT) {
            lines.add(Component.empty());
            lines.add(borderLine());
            lines.add(timeLine());
        }
        lines.add(Component.empty());
        lines.add(messages.get("hud.spectating"));
        return lines;
    }

    private Component aliveLine() {
        if (session.isTeamMode()) {
            return messages.get("hud.alive-teams", Messages.tag("teams", session.aliveTeams().size()),
                    Messages.tag("count", session.aliveCount()));
        }
        return messages.get("hud.alive", Messages.tag("count", session.aliveCount()));
    }

    private Component borderLine() {
        BorderController border = match.border();
        long fight = match.fightTicks();
        return switch (border.state(fight)) {
            case WAITING -> messages.get("hud.border-waiting",
                    Messages.tag("time", clock(border.ticksUntilShrink(fight))));
            case SHRINKING -> messages.get("hud.border-shrinking",
                    Messages.tag("time", clock(border.shrinkRemainingTicks())));
            case FINAL -> messages.get("hud.border-final",
                    Messages.tag("size", Format.number(border.currentSize())));
        };
    }

    private Component timeLine() {
        long fight = match.fightTicks();
        long limit = match.settings().timeLimitTicks();
        if (limit > 0) {
            return messages.get("hud.time-limited", Messages.tag("time", Format.clock(fight / 20)),
                    Messages.tag("limit", clock(limit)));
        }
        return messages.get("hud.time", Messages.tag("time", Format.clock(fight / 20)));
    }

    private int kills(Participant participant) {
        return session.stats().of(participant.id()).kills();
    }

    private static String clock(long ticks) {
        return Format.clock((ticks + 19) / 20);
    }

    private static long seconds(long ticks) {
        return (ticks + 19) / 20;
    }

    private static float ratio(long left, long total) {
        return total <= 0 ? 0f : (float) left / total;
    }
}
