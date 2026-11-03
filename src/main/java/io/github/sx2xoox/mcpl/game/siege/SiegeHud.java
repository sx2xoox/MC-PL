package io.github.sx2xoox.mcpl.game.siege;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.game.MatchTeam;
import io.github.sx2xoox.mcpl.core.map.MapService;
import io.github.sx2xoox.mcpl.core.ui.Format;
import io.github.sx2xoox.mcpl.core.ui.ManagedBossBar;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.core.ui.Sidebar;
import io.github.sx2xoox.mcpl.game.siege.tower.Tower;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * The 공성전 HUD: a timer boss bar per viewer (round, half, time, red during the 총공세, and the way to the target
 * tower: {@link Wayfinding#pointer}), the tower boss bars only for players a tower matters to ({@link TowerBars}), the
 * health plates under the names ({@link NamePlates}), the target tower's beam and frame and the district titles
 * ({@link Wayfinding}), and a private sidebar per viewer: {@code A팀 12 : 9 B팀}, the round score (●○), the phase and
 * time left, who attacks, the attackable tower's health, the 오버클럭 코어, and the viewer's own ability,
 * kills/deaths and level.
 */
final class SiegeHud {

    private final SiegeMatch match;
    private final GameSession session;
    private final Messages messages;
    private final SiegeHudSettings settings;
    private final Map<UUID, ManagedBossBar> timers = new HashMap<>();
    private final TowerBars towerBars;
    private final NamePlates plates;
    private final Wayfinding wayfinding;
    private final Set<UUID> broken = new HashSet<>();
    private boolean reported;

    SiegeHud(SiegeMatch match) {
        this.match = match;
        this.session = match.session();
        this.messages = match.messages();
        this.settings = SiegeHudSettings.read(session.gameConfig());
        this.towerBars = new TowerBars(match, settings);
        this.plates = new NamePlates(match, match::plateLevel);
        this.wayfinding = new Wayfinding(match, settings);
        if (settings.night()) {
            night(match.layout().world());
        }
    }

    /**
     * Night in clear weather for the whole match, in a plugin map world only (time does not advance there, see
     * {@link MapService}); {@link #dispose} puts back the map's noon.
     */
    private static void night(World world) {
        if (!world.getName().startsWith(MapService.WORLD_PREFIX)) {
            return;
        }
        world.setStorm(false);
        world.setThundering(false);
        world.setTime(SiegeHudSettings.NIGHT_TIME);
    }

    /** Every tick: plates of players whose health changed. */
    void tick() {
        if (settings.namePlates()) {
            plates.flush();
        }
    }

    /** {@code player} hit {@code tower}: its bar shows to them for a while. */
    void towerHit(Tower tower, Player player) {
        towerBars.hit(tower, player.getUniqueId());
    }

    /** A title was shown to {@code player} (null: everyone); the district titles wait a moment. */
    void titleShown(@Nullable Player player) {
        wayfinding.titleShown(player);
    }

    /** {@code player}'s health changed: their plate is redrawn next tick. */
    void healthChanged(Player player) {
        plates.changed(player);
    }

    /** The match ends: the markers go (the session hides the bars and removes tracked entities anyway). */
    void dispose() {
        wayfinding.removeMarkers();
        World world = match.layout().world();
        if (settings.night() && world.getName().startsWith(MapService.WORLD_PREFIX)) {
            MapService.resetSky(world);
        }
    }

    SiegeHudSettings settings() {
        return settings;
    }

    TowerBars towerBars() {
        return towerBars;
    }

    NamePlates plates() {
        return plates;
    }

    Wayfinding wayfinding() {
        return wayfinding;
    }

    void update() {
        wayfinding.update();
        try {
            updateTimers();
            updateTowers();
            towerBars.update();
        } catch (RuntimeException e) {
            report("boss bars", e);
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

    private void updateTimers() {
        long left = match.ticksLeft();
        long total = switch (match.phase()) {
            case PREP -> match.prepTicks();
            case FIGHT -> match.isSuddenDeath() ? match.settings().suddenDeathTicks() : match.settings().halfTicks();
            case HALFTIME -> match.settings().halftimeTicks();
            case BREAK -> match.settings().roundBreakTicks();
            case OVER -> 1;
        };
        Component base = messages.get("hud.bossbar", Messages.tag("phase", phaseName()),
                Messages.tag("time", Format.clock((left + 19) / 20)),
                Messages.tag("attackers", attackers().displayName()));
        float progress = total <= 0 ? 0f : Math.max(0f, Math.min(1f, (float) left / total));
        BossBar.Color color = match.isClimax() || match.isSuddenDeath() ? BossBar.Color.RED
                : match.phase() == SiegeMatch.Phase.FIGHT ? BossBar.Color.YELLOW : BossBar.Color.BLUE;
        Set<UUID> viewers = new HashSet<>();
        for (Player viewer : session.viewers()) {
            viewers.add(viewer.getUniqueId());
            Component pointer = wayfinding.pointer(viewer);
            Component name = pointer == null ? base
                    : messages.get("hud.bossbar-pointer", Messages.tag("timer", base), Messages.tag("pointer", pointer));
            ManagedBossBar timer = timers.get(viewer.getUniqueId());
            if (timer == null) {
                timer = session.playerBossBar(viewer, name, progress, color, BossBar.Overlay.PROGRESS);
                timers.put(viewer.getUniqueId(), timer);
            } else if (!timer.isShownTo(viewer)) {
                timer.show(viewer);
            }
            timer.name(name);
            timer.progress(progress);
            if (timer.bar().color() != color) {
                timer.color(color);
            }
        }
        timers.entrySet().removeIf(entry -> {
            if (viewers.contains(entry.getKey())) {
                return false;
            }
            entry.getValue().forget(entry.getKey());
            return true;
        });
    }

    /** The viewer's timer bar (tests). */
    @org.jspecify.annotations.Nullable ManagedBossBar timer(UUID viewer) {
        return timers.get(viewer);
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
            addIfPresent(lines, match.camps().hudLine());
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
