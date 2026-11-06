package io.github.sx2xoox.mcpl.core.game;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * How a match ended, passed to {@link GameSession#finish(GameResult)}. The hub presents it: winner title to everyone,
 * fireworks at the winners, a Korean chat summary (placement, kills, deaths, damage, the mode's
 * {@link GameInstance#playerTag player tag}, MVP, duration), sounds and a results sidebar.
 *
 * <pre>{@code
 * session.finish(GameResult.winner(lastAlive));
 * session.finish(GameResult.team(session.teamOf(lastAlive)));
 * session.finish(GameResult.builder().winner(best).reason(Component.text("제한 시간 종료 · 킬 수 우선")).build());
 * session.finish(GameResult.draw());
 * }</pre>
 *
 * <p>Placement is optional: if not given, the hub orders winners first, then other survivors by performance, then
 * eliminated players from last to first eliminated. MVP is optional too (default: most kills, then damage, then
 * fewest deaths).
 */
public final class GameResult {

    private final List<UUID> winners;
    private final @Nullable MatchTeam winningTeam;
    private final List<UUID> placement;
    private final @Nullable UUID mvp;
    private final @Nullable Component reason;

    private GameResult(Builder builder) {
        LinkedHashSet<UUID> all = new LinkedHashSet<>(builder.winners);
        if (builder.team != null) {
            all.addAll(builder.team.members());
        }
        this.winners = List.copyOf(all);
        this.winningTeam = builder.team;
        this.placement = List.copyOf(new LinkedHashSet<>(builder.placement));
        this.mvp = builder.mvp;
        this.reason = builder.reason;
    }

    public static GameResult winner(Player player) {
        return builder().winner(player.getUniqueId()).build();
    }

    public static GameResult winner(UUID player) {
        return builder().winner(player).build();
    }

    public static GameResult winners(Collection<UUID> players) {
        return builder().winners(players).build();
    }

    /** The whole team wins (all members, eliminated ones included). */
    public static GameResult team(MatchTeam team) {
        return builder().team(team).build();
    }

    /** Nobody wins. */
    public static GameResult draw() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Winning players (for a team win: every team member). Empty for a draw. */
    public List<UUID> winners() {
        return winners;
    }

    public @Nullable MatchTeam winningTeam() {
        return winningTeam;
    }

    public boolean isDraw() {
        return winners.isEmpty() && winningTeam == null;
    }

    public boolean isWinner(UUID player) {
        return winners.contains(player);
    }

    /** Explicit placement (1st first), or empty to let the hub decide. */
    public List<UUID> placement() {
        return placement;
    }

    public @Nullable UUID mvp() {
        return mvp;
    }

    /** Optional line explaining the outcome, e.g. "제한 시간 종료". */
    public @Nullable Component reason() {
        return reason;
    }

    public static final class Builder {
        private final List<UUID> winners = new ArrayList<>();
        private @Nullable MatchTeam team;
        private final List<UUID> placement = new ArrayList<>();
        private @Nullable UUID mvp;
        private @Nullable Component reason;

        private Builder() {
        }

        public Builder winner(UUID player) {
            winners.add(Objects.requireNonNull(player));
            return this;
        }

        public Builder winner(Player player) {
            return winner(player.getUniqueId());
        }

        public Builder winners(Collection<UUID> players) {
            players.forEach(this::winner);
            return this;
        }

        public Builder team(MatchTeam team) {
            this.team = Objects.requireNonNull(team);
            return this;
        }

        /** Full placement, 1st first. Players not listed are appended by the hub. */
        public Builder placement(List<UUID> order) {
            placement.clear();
            placement.addAll(order);
            return this;
        }

        public Builder mvp(@Nullable UUID player) {
            this.mvp = player;
            return this;
        }

        public Builder reason(@Nullable Component reason) {
            this.reason = reason;
            return this;
        }

        public GameResult build() {
            return new GameResult(this);
        }
    }
}
