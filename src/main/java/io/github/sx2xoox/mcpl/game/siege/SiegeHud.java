package io.github.sx2xoox.mcpl.game.siege;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.game.MatchTeam;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import io.github.sx2xoox.mcpl.core.ui.Feedback;
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
 * The 공성전 HUD (docs/siege.md "화면 표시"), kept small on purpose: one boss bar with the round and its timer, a
 * private sidebar of five lines per viewer (score, rounds won, standing towers, my level and gold, the next big
 * objective from a {@link SiegeModule}; my ability while preparing), the health plates over the heads ({@link NamePlates}) and the
 * start tutorial (three titles and a chat summary). Structure health floats over the structures ({@link OverheadBar});
 * the "▶ 지금 할 일" line is {@link Guidance}.
 */
final class SiegeHud {

    /** Ticks between the tutorial's titles. */
    static final int TUTORIAL_STEP_TICKS = 130;

    private final SiegeMatch match;
    private final GameSession session;
    private final Messages messages;
    private final SiegeHudSettings settings;
    private final NamePlates plates;
    private final Set<UUID> broken = new HashSet<>();
    private @Nullable ManagedBossBar timer;
    private boolean reported;

    SiegeHud(SiegeMatch match, SiegeHudSettings settings) {
        this.match = match;
        this.session = match.session();
        this.messages = match.messages();
        this.settings = settings;
        this.plates = new NamePlates(match, match::plateLevel);
    }

    /** Every tick: the plates over the heads (who carries one, who sees it, health changes). */
    void tick() {
        if (settings.namePlates()) {
            plates.tick();
        }
    }

    /** {@code player}'s health changed: their plate is redrawn next tick. */
    void healthChanged(Player player) {
        plates.changed(player);
    }

    /** {@code player} left: their plate goes now. */
    void playerLeft(Player player) {
        plates.remove(player.getUniqueId());
    }

    void dispose() {
        plates.dispose();
        if (timer != null) {
            timer.hideAll();
        }
    }

    SiegeHudSettings settings() {
        return settings;
    }

    NamePlates plates() {
        return plates;
    }

    /** The timer boss bar (tests). */
    @Nullable ManagedBossBar timer() {
        return timer;
    }

    // ------------------------------------------------------------------ tutorial

    /** The rules in 20 seconds: three titles {@link #TUTORIAL_STEP_TICKS} apart and a chat summary. */
    void tutorial() {
        if (!settings.tutorial()) {
            return;
        }
        for (Component line : messages.lines("tutorial.summary")) {
            session.broadcast(line);
        }
        for (int step = 1; step <= 3; step++) {
            int index = step;
            Runnable show = () -> {
                if (match.phase() != SiegeMatch.Phase.PREP && index > 1) {
                    return;
                }
                for (Player viewer : session.viewers()) {
                    Feedback.title(viewer, messages.get("tutorial.title-" + index),
                            messages.get("tutorial.subtitle-" + index), 5, TUTORIAL_STEP_TICKS - 25, 10);
                }
            };
            if (step == 1) {
                show.run();
            } else {
                session.runLater((long) (step - 1) * TUTORIAL_STEP_TICKS, show);
            }
        }
    }

    // ------------------------------------------------------------------ update

    void update() {
        try {
            updateTimer();
        } catch (RuntimeException e) {
            report("boss bar", e);
        }
        if (settings.namePlates()) {
            plates.update();
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
            case FIGHT -> match.isOvertime() ? match.settings().overtimeTicks() : match.settings().roundTicks();
            case BREAK -> match.settings().roundBreakTicks();
            case OVER -> 1;
        };
        Component name = messages.get("hud.bossbar", Messages.tag("phase", phaseName()),
                Messages.tag("time", Format.clock((left + 19) / 20)));
        float progress = total <= 0 ? 0f : Math.max(0f, Math.min(1f, (float) left / total));
        BossBar.Color color = match.isClimax() || match.isOvertime() ? BossBar.Color.RED
                : match.phase() == SiegeMatch.Phase.FIGHT ? BossBar.Color.YELLOW : BossBar.Color.BLUE;
        if (timer == null) {
            timer = session.sharedBossBar(name, progress, color, BossBar.Overlay.PROGRESS);
        }
        timer.name(name);
        timer.progress(progress);
        if (timer.bar().color() != color) {
            timer.color(color);
        }
    }

    private Component phaseName() {
        return switch (match.phase()) {
            case PREP -> messages.get("hud.phase-prep", Messages.tag("round", match.round()));
            case FIGHT -> messages.get(match.isOvertime() ? "hud.phase-overtime"
                    : match.isClimax() ? "hud.phase-climax" : "hud.phase-fight", Messages.tag("round", match.round()));
            case BREAK -> messages.get("hud.phase-break", Messages.tag("round", match.round() + 1));
            case OVER -> messages.get("hud.phase-over");
        };
    }

    /**
     * Four lines: the score, the rounds won, my level and gold, the dragon's timer; in the preparation my ability
     * (and rerolls) too. The round and its time are on the boss bar only.
     */
    List<Component> lines(Player viewer) {
        SiegeScore score = match.score();
        List<MatchTeam> teams = match.teams();
        List<Component> lines = new ArrayList<>();
        lines.add(messages.get("hud.score",
                Messages.tag("team_a", Component.text(teams.get(0).shortName(), teams.get(0).color())),
                Messages.tag("team_b", Component.text(teams.get(1).shortName(), teams.get(1).color())),
                Messages.tag("a", score.points(0)), Messages.tag("b", score.points(1))));
        lines.add(messages.get("hud.rounds", Messages.tag("a", score.roundWins(0)),
                Messages.tag("b", score.roundWins(1))));
        UUID id = viewer.getUniqueId();
        if (match.teamIndex(id) < 0) {
            lines.add(messages.get("hud.spectating"));
            return lines;
        }
        Component level = match.progress().hudLine(id);
        SiegeEconomy economy = match.economy();
        if (economy.enabled()) {
            lines.add(messages.get("hud.level-gold", Messages.tag("level", level == null ? Component.empty() : level),
                    Messages.tag("money", economy.gold(id))));
        } else if (level != null) {
            lines.add(level);
        }
        for (SiegeModule module : match.modules()) {
            Component objective = module.sidebarLine(viewer);
            if (objective != null) {
                lines.add(objective);
                break;
            }
        }
        if (match.phase() == SiegeMatch.Phase.PREP) {
            // the ability is new (and may be rerolled) only now; in the fight it is the sword in the hand
            lines.add(match.rerollsLeft(viewer) > 0
                    ? messages.get("hud.ability-reroll", Messages.tag("ability", match.abilityName(id)),
                            Messages.tag("count", match.rerollsLeft(viewer)))
                    : messages.get("hud.ability", Messages.tag("ability", match.abilityName(id))));
        }
        return lines;
    }

    /** Standing towers of a side (its core included). */
    private int standing(LaneSites.Side side) {
        int up = 0;
        for (Tower tower : match.towers(side)) {
            if (!tower.isDestroyed()) {
                up++;
            }
        }
        return up;
    }
}
