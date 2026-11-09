package io.github.sx2xoox.mcpl.core.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import io.github.sx2xoox.mcpl.core.game.MatchTeam;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.jspecify.annotations.Nullable;

/**
 * Per-viewer scoreboards for one match. Every viewer gets a private {@link Scoreboard} (so each can have their own
 * {@link Sidebar}) on which the match teams are mirrored: team color and prefix on name tags and the tab list,
 * friendly fire off and teammates visible while invisible. Non-participant spectators share a gray team.
 *
 * <p>The player's previous scoreboard is part of their {@link io.github.sx2xoox.mcpl.core.player.PlayerSnapshot} and
 * comes back when they are restored. Owned by {@link io.github.sx2xoox.mcpl.core.game.GameSession}.
 */
public final class ViewerBoards {

    private static final String TEAM_PREFIX = "mcpl_";
    private static final String SPECTATOR_TEAM = "mcpl_spectator";

    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private final Map<UUID, Sidebar> sidebars = new HashMap<>();
    private final Set<String> spectatorEntries = new LinkedHashSet<>();
    private List<MatchTeam> teams = List.of();
    private Function<UUID, String> names = id -> id.toString().substring(0, 16);
    private Component defaultTitle;

    public ViewerBoards(Component defaultTitle) {
        this.defaultTitle = defaultTitle;
    }

    /** Title used for sidebars created from now on. */
    public void defaultTitle(Component title) {
        this.defaultTitle = title;
    }

    /** Sets the match teams and mirrors them on every board. {@code names} maps member UUIDs to entry names. */
    public void setTeams(List<MatchTeam> teams, Function<UUID, String> names) {
        this.teams = List.copyOf(teams);
        this.names = names;
        for (Scoreboard board : boards.values()) {
            mirrorTeams(board);
        }
    }

    /** Gives the viewer their private board (created on first call) and shows it. */
    public Scoreboard attach(Player viewer) {
        Scoreboard board = boards.computeIfAbsent(viewer.getUniqueId(), id -> {
            Scoreboard created = Bukkit.getScoreboardManager().getNewScoreboard();
            mirrorTeams(created);
            return created;
        });
        if (viewer.getScoreboard() != board) {
            viewer.setScoreboard(board);
        }
        return board;
    }

    /** The viewer's board, or null if they have none. */
    public @Nullable Scoreboard board(Player viewer) {
        return boards.get(viewer.getUniqueId());
    }

    public boolean isAttached(Player viewer) {
        return boards.containsKey(viewer.getUniqueId());
    }

    /** The viewer's sidebar, created (and the board attached) on first call. */
    public Sidebar sidebar(Player viewer) {
        Sidebar sidebar = sidebars.get(viewer.getUniqueId());
        if (sidebar != null && !sidebar.isRemoved()) {
            return sidebar;
        }
        Scoreboard board = attach(viewer);
        sidebar = new Sidebar(board, defaultTitle);
        sidebars.put(viewer.getUniqueId(), sidebar);
        return sidebar;
    }

    /** Sidebars that exist right now, by viewer. */
    public Map<UUID, Sidebar> sidebars() {
        return Map.copyOf(sidebars);
    }

    /** Removes the viewer's sidebar (the board stays). */
    public void removeSidebar(UUID viewer) {
        Sidebar sidebar = sidebars.remove(viewer);
        if (sidebar != null) {
            sidebar.remove();
        }
    }

    /** Forgets the viewer (after they left); a later {@link #attach} creates a fresh board. */
    public void detach(UUID viewer) {
        sidebars.remove(viewer);
        boards.remove(viewer);
    }

    /** Adds a member to a team on every board (late joiners). */
    public void addTeamMember(MatchTeam team, UUID member) {
        String entry = names.apply(member);
        for (Scoreboard board : boards.values()) {
            Team mirrored = board.getTeam(TEAM_PREFIX + team.id());
            if (mirrored != null) {
                mirrored.addEntry(entry);
            }
        }
    }

    /** Puts a non-participant spectator on the gray spectator team on every board. */
    public void addSpectator(String entry) {
        if (!spectatorEntries.add(entry)) {
            return;
        }
        for (Scoreboard board : boards.values()) {
            spectatorTeam(board).addEntry(entry);
        }
    }

    /** Removes a name from the spectator team (it became a participant). */
    public void removeSpectator(String entry) {
        if (!spectatorEntries.remove(entry)) {
            return;
        }
        for (Scoreboard board : boards.values()) {
            Team team = board.getTeam(SPECTATOR_TEAM);
            if (team != null) {
                team.removeEntry(entry);
            }
        }
    }

    /** Removes every sidebar and forgets all boards. Players keep whatever board they have until restored. */
    public void clear() {
        for (Sidebar sidebar : new ArrayList<>(sidebars.values())) {
            sidebar.remove();
        }
        sidebars.clear();
        boards.clear();
        spectatorEntries.clear();
        teams = List.of();
    }

    private void mirrorTeams(Scoreboard board) {
        for (MatchTeam team : teams) {
            Team mirrored = board.getTeam(TEAM_PREFIX + team.id());
            if (mirrored == null) {
                mirrored = board.registerNewTeam(TEAM_PREFIX + team.id());
            }
            mirrored.displayName(team.displayName());
            mirrored.color(team.color());
            mirrored.prefix(Component.text("[" + team.shortName() + "] ", team.color()));
            mirrored.setAllowFriendlyFire(false);
            mirrored.setCanSeeFriendlyInvisibles(true);
            for (UUID member : team.members()) {
                mirrored.addEntry(names.apply(member));
            }
        }
        if (!spectatorEntries.isEmpty()) {
            Team spectators = spectatorTeam(board);
            for (String entry : spectatorEntries) {
                spectators.addEntry(entry);
            }
        }
    }

    private static Team spectatorTeam(Scoreboard board) {
        Team team = board.getTeam(SPECTATOR_TEAM);
        if (team == null) {
            team = board.registerNewTeam(SPECTATOR_TEAM);
            team.color(NamedTextColor.GRAY);
            team.prefix(Component.text("[관전] ", NamedTextColor.DARK_GRAY));
            team.setAllowFriendlyFire(false);
        }
        return team;
    }
}
