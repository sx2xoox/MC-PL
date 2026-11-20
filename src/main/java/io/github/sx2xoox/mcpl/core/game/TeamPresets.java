package io.github.sx2xoox.mcpl.core.game;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import io.github.sx2xoox.mcpl.core.Hub;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Team picks made before a match ({@code /게임 팀 선택|지정|섞기}, the team menu): player → palette team id
 * ({@code "red"}). {@link GameSession} forms the teams from them when the countdown ends ({@link TeamAssigner#form})
 * and puts late joiners on their pick; players without a pick are placed automatically. Picks stay for the next
 * matches until removed ({@code /게임 팀 해제|초기화}) and are kept in memory only (a restart clears them).
 */
public final class TeamPresets {

    private final Hub hub;
    /** Player → team id, in pick order (the latest pick last). */
    private final Map<UUID, String> picks = new LinkedHashMap<>();

    public TeamPresets(Hub hub) {
        this.hub = hub;
    }

    /** The player's picked team id, or null (placed automatically). */
    public @Nullable String get(UUID player) {
        return picks.get(player);
    }

    /** Picks a team for the player (a palette id such as {@code "red"}). */
    public void set(UUID player, String teamId) {
        picks.remove(player);
        picks.put(player, teamId);
    }

    /** Removes the player's pick; false if there was none. */
    public boolean remove(UUID player) {
        return picks.remove(player) != null;
    }

    /** Removes every pick and returns how many there were. */
    public int clear() {
        int count = picks.size();
        picks.clear();
        return count;
    }

    /** Every pick, in pick order (a copy). */
    public Map<UUID, String> all() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(picks));
    }

    /** Players who picked this team, in pick order. */
    public List<UUID> members(String teamId) {
        List<UUID> out = new ArrayList<>();
        picks.forEach((player, team) -> {
            if (team.equals(teamId)) {
                out.add(player);
            }
        });
        return out;
    }

    /**
     * The teams players can pick now, in palette order; empty when the match counting down or running cannot use
     * teams (a free-for-all or bots-only mode).
     * <ul>
     *     <li>a match with a fixed team count (공성전: 2, 증강 봇전 팀전: N): its first palette colors;</li>
     *     <li>a running team match: its teams;</li>
     *     <li>else with a team size ({@code match.team-size}): as many teams as the waiting players need (at least 2)
     *     plus colors already picked;</li>
     *     <li>no match and no team size: 빨강 and 파랑 (two teams fit every team mode).</li>
     * </ul>
     */
    public List<TeamAssigner.Entry> available() {
        GameSession session = hub.games().session();
        int teamSize = hub.config().teamSize();
        int count;
        if (session != null && session.state() != GameState.CLOSED) {
            MiniGame game = session.game();
            if (!game.supportsTeams() || game.spectatorsOnly()) {
                return List.of();
            }
            if (session.isTeamMode()) {
                List<TeamAssigner.Entry> out = new ArrayList<>();
                for (MatchTeam team : session.teams()) {
                    TeamAssigner.Entry entry = TeamAssigner.entry(team.id());
                    if (entry != null) {
                        out.add(entry);
                    }
                }
                return out;
            }
            int fixed = session.fixedTeamCount();
            if (fixed >= 2) {
                return first(fixed);
            }
            if (teamSize <= 0 || session.state() != GameState.COUNTDOWN) {
                return List.of();
            }
            count = needed(session.participantIds().size(), teamSize);
        } else {
            count = teamSize <= 0 ? 2 : needed(hub.games().eligiblePlayers().size(), teamSize);
        }
        List<TeamAssigner.Entry> out = new ArrayList<>(first(count));
        if (teamSize > 0) {
            for (TeamAssigner.Entry entry : TeamAssigner.palette()) {
                if (!out.contains(entry) && picks.containsValue(entry.id())) {
                    out.add(entry);
                }
            }
            out.sort(java.util.Comparator.comparingInt(TeamAssigner.palette()::indexOf));
        }
        return out;
    }

    /** The available team matching what a player typed ({@code 빨강}, {@code red}), or null. */
    public TeamAssigner.@Nullable Entry find(String input) {
        TeamAssigner.Entry entry = TeamAssigner.parse(input);
        return entry != null && available().contains(entry) ? entry : null;
    }

    /**
     * Splits {@code players} at random over the {@link #available()} teams (sizes differ by at most one), replacing
     * their picks; other players keep theirs. Returns false (nothing changed) if no team can be picked now.
     */
    public boolean shuffle(Collection<? extends Player> players, Random random) {
        List<TeamAssigner.Entry> teams = available();
        if (teams.isEmpty()) {
            return false;
        }
        List<UUID> order = new ArrayList<>();
        for (Player player : players) {
            order.add(player.getUniqueId());
        }
        Collections.shuffle(order, random);
        int count = Math.min(teams.size(), Math.max(2, order.size()));
        for (int i = 0; i < order.size(); i++) {
            set(order.get(i), teams.get(i % count).id());
        }
        return true;
    }

    /** The players a {@link #shuffle} splits: the counting-down match's participants, else who would play next. */
    public List<Player> waitingPlayers() {
        GameSession session = hub.games().session();
        if (session != null && session.state() == GameState.COUNTDOWN) {
            return session.participants();
        }
        return hub.games().eligiblePlayers();
    }

    private static int needed(int players, int teamSize) {
        return Math.min(TeamAssigner.palette().size(), Math.max(2, (players + teamSize - 1) / teamSize));
    }

    private static List<TeamAssigner.Entry> first(int count) {
        List<TeamAssigner.Entry> palette = TeamAssigner.palette();
        return palette.subList(0, Math.min(palette.size(), count));
    }
}
