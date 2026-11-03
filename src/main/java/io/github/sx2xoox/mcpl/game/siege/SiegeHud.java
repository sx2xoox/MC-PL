package io.github.sx2xoox.mcpl.game.siege;

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
import io.github.sx2xoox.mcpl.game.siege.tower.Tower;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * The 공성전 HUD: a shared timer boss bar (round, half, time, red during the 총공세), one boss bar per tower (health,
 * shield) and a private sidebar per viewer: {@code A팀 12 : 9 B팀}, the round score (●○), the phase and time left,
 * who attacks, the attackable tower's health, the 오버클럭 코어, and the viewer's own ability, kills/deaths and level.
 */
final class SiegeHud {

    private final SiegeMatch match;
    private final GameSession session;
    private final Messages messages;
    private final ManagedBossBar timer;
    private final Set<UUID> broken = new HashSet<>();
    private boolean reported;

    SiegeHud(SiegeMatch match) {
        this.match = match;
        this.session = match.session();
        this.messages = match.messages();
        this.timer = session.sharedBossBar(Component.empty(), 1f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
    }

    void update() {
        try {
            updateTimer();
            updateTowers();
        } catch (RuntimeException e) {
            report("boss bars", e);
        }
        for (Player viewer : session.viewers()) {
            if (broken.contains(viewer.getUniqueId()) || !session.isMember(viewer)) {
                continue;
            }
            try {
                Sidebar sidebar = session.sidebar(viewer);
                sidebar.title(messages.get("game.name"));
                sidebar.lines(lines(viewer));
            } catch (RuntimeException e) {
                broken.add(viewer.getUniqueId());
                report("sidebar of " + viewer.getName(), e);
            }
        }
    }

    private void report(String what, RuntimeException error) {
        if (!reported) {
            reported = true;
            session.plugin().getLogger().log(Level.WARNING, "Could not update the siege HUD (" + what + ")", error);
        }
    }

    private void updateTimer() {
        long left = match.ticksLeft();
        long total = switch (match.phase()) {
            case PREP -> match.prepTicks();
            case FIGHT -> match.isSuddenDeath() ? match.settings().suddenDeathTicks() : match.settings().halfTicks();
            case HALFTIME -> match.settings().halftimeTicks();
            case BREAK -> match.settings().roundBreakTicks();
            case OVER -> 1;
        };
        timer.name(messages.get("hud.bossbar", Messages.tag("phase", phaseName()),
                Messages.tag("time", Format.clock((left + 19) / 20)),
                Messages.tag("attackers", attackers().displayName())));
        timer.progress(total <= 0 ? 0f : Math.max(0f, Math.min(1f, (float) left / total)));
        BossBar.Color color = match.isClimax() || match.isSuddenDeath() ? BossBar.Color.RED
                : match.phase() == SiegeMatch.Phase.FIGHT ? BossBar.Color.YELLOW : BossBar.Color.BLUE;
        if (timer.bar().color() != color) {
            timer.color(color);
        }
    }

    private void updateTowers() {
        for (Tower tower : match.towers()) {
            ManagedBossBar bar = tower.bar();
            if (bar == null || tower.isDestroyed()) {
                continue;
            }
            bar.name(messages.get(tower.isShielded() ? "hud.tower-shielded" : "hud.tower",
                    Messages.tag("tower", tower.name()),
                    Messages.tag("health", Format.number(Math.ceil(tower.health()))),
                    Messages.tag("max", Format.number(tower.maxHealth()))));
            bar.progress((float) Math.max(0, Math.min(1, tower.fraction())));
            BossBar.Color color = tower.isShielded() ? BossBar.Color.WHITE : BossBar.Color.PURPLE;
            if (bar.bar().color() != color) {
                bar.color(color);
            }
        }
    }

    private Component phaseName() {
        Component half = messages.get(match.half() == 2 ? "half.second" : "half.first");
        return switch (match.phase()) {
            case PREP -> messages.get("hud.phase-prep", Messages.tag("round", match.round()), Messages.tag("half", half));
            case FIGHT -> messages.get(match.isSuddenDeath() ? "hud.phase-sudden-death"
                            : match.isClimax() ? "hud.phase-climax" : "hud.phase-fight",
                    Messages.tag("round", match.round()), Messages.tag("half", half));
            case HALFTIME -> messages.get("hud.phase-halftime", Messages.tag("round", match.round()));
            case BREAK -> messages.get("hud.phase-break", Messages.tag("round", match.round() + 1));
            case OVER -> messages.get("hud.phase-over");
        };
    }

    private MatchTeam attackers() {
        return match.teams().get(match.attackingTeam());
    }

    private List<Component> lines(Player viewer) {
        SiegeScore score = match.score();
        List<MatchTeam> teams = match.teams();
        List<Component> lines = new ArrayList<>();
        lines.add(Component.empty());
        lines.add(messages.get("hud.score",
                Messages.tag("team_a", Component.text(teams.get(0).shortName(), teams.get(0).color())),
                Messages.tag("team_b", Component.text(teams.get(1).shortName(), teams.get(1).color())),
                Messages.tag("a", score.points(0)), Messages.tag("b", score.points(1)),
                Messages.tag("goal", match.settings().winScore())));
        lines.add(messages.get("hud.rounds", Messages.tag("a", dots(score.roundWins(0))),
                Messages.tag("b", dots(score.roundWins(1)))));
        lines.add(Component.empty());
        lines.add(messages.get("hud.phase", Messages.tag("phase", phaseName()),
                Messages.tag("time", Format.clock((match.ticksLeft() + 19) / 20))));
        lines.add(messages.get("hud.attackers", Messages.tag("team", attackers().displayName())));
        Tower tower = match.attackableTower();
        if (tower != null && (match.phase() == SiegeMatch.Phase.FIGHT || match.phase() == SiegeMatch.Phase.PREP)) {
            lines.add(messages.get("hud.tower", Messages.tag("tower", tower.name()),
                    Messages.tag("health", Format.number(Math.ceil(tower.health()))),
                    Messages.tag("max", Format.number(tower.maxHealth()))));
        }
        if (match.isClimax()) {
            lines.add(messages.get("hud.climax"));
        }
        if (match.phase() == SiegeMatch.Phase.FIGHT) {
            addIfPresent(lines, match.objectives().hudLine());
        }
        UUID id = viewer.getUniqueId();
        if (match.teamIndex(id) >= 0) {
            lines.add(Component.empty());
            lines.add(messages.get("hud.ability", Messages.tag("ability", match.abilityName(id))));
            lines.add(messages.get("hud.kd", Messages.tag("kills", session.stats().of(id).kills()),
                    Messages.tag("deaths", session.stats().of(id).deaths())));
            addIfPresent(lines, match.progress().hudLine(id));
            if (match.phase() == SiegeMatch.Phase.PREP && match.half() == 1 && match.rerollsLeft(viewer) > 0) {
                lines.add(messages.get("hud.rerolls", Messages.tag("count", match.rerollsLeft(viewer))));
            }
        } else {
            lines.add(Component.empty());
            lines.add(messages.get("hud.spectating"));
        }
        return lines;
    }

    private static void addIfPresent(List<Component> lines, @Nullable Component line) {
        if (line != null) {
            lines.add(line);
        }
    }

    private String dots(int wins) {
        int total = match.settings().roundsToWin();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < total; i++) {
            out.append(i < wins ? '●' : '○');
        }
        return out.toString();
    }
}
