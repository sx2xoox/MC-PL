package io.github.sx2xoox.mcpl.core.game;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.DyeColor;
import org.jspecify.annotations.Nullable;

/**
 * Splits participants into balanced teams of the configured size. Pure logic (no server access) so it is easy to
 * test; {@link GameSession} calls it when the countdown ends.
 */
public final class TeamAssigner {

    private record Entry(String id, String shortName, NamedTextColor color, DyeColor dye) {
    }

    private static final List<Entry> PALETTE = List.of(
            new Entry("red", "빨강", NamedTextColor.RED, DyeColor.RED),
            new Entry("blue", "파랑", NamedTextColor.BLUE, DyeColor.BLUE),
            new Entry("green", "초록", NamedTextColor.GREEN, DyeColor.LIME),
            new Entry("yellow", "노랑", NamedTextColor.YELLOW, DyeColor.YELLOW),
            new Entry("purple", "보라", NamedTextColor.DARK_PURPLE, DyeColor.PURPLE),
            new Entry("orange", "주황", NamedTextColor.GOLD, DyeColor.ORANGE),
            new Entry("aqua", "하늘", NamedTextColor.AQUA, DyeColor.LIGHT_BLUE),
            new Entry("pink", "분홍", NamedTextColor.LIGHT_PURPLE, DyeColor.PINK),
            new Entry("white", "하양", NamedTextColor.WHITE, DyeColor.WHITE),
            new Entry("cyan", "청록", NamedTextColor.DARK_AQUA, DyeColor.CYAN),
            new Entry("crimson", "진홍", NamedTextColor.DARK_RED, DyeColor.BROWN),
            new Entry("forest", "숲", NamedTextColor.DARK_GREEN, DyeColor.GREEN),
            new Entry("gray", "회색", NamedTextColor.GRAY, DyeColor.LIGHT_GRAY),
            new Entry("navy", "남색", NamedTextColor.DARK_BLUE, DyeColor.BLUE),
            new Entry("charcoal", "먹색", NamedTextColor.DARK_GRAY, DyeColor.GRAY),
            new Entry("black", "검정", NamedTextColor.BLACK, DyeColor.BLACK));

    private TeamAssigner() {
    }

    /**
     * Assigns {@code players} to teams of about {@code teamSize}. The number of teams is
     * {@code max(2, ceil(players / teamSize))} and team sizes differ by at most one.
     *
     * @return the teams in palette order, or an empty list (free-for-all) when {@code teamSize <= 0} or there are
     * fewer than two players
     */
    public static List<MatchTeam> assign(Collection<UUID> players, int teamSize, Random random) {
        if (teamSize <= 0 || players.size() < 2) {
            return List.of();
        }
        int count = Math.max(2, (players.size() + teamSize - 1) / teamSize);
        count = Math.min(count, players.size());
        List<MatchTeam> teams = createTeams(count);
        List<UUID> shuffled = new ArrayList<>(players);
        Collections.shuffle(shuffled, random);
        for (int i = 0; i < shuffled.size(); i++) {
            teams.get(i % count).add(shuffled.get(i));
        }
        return List.copyOf(teams);
    }

    /**
     * Spreads {@code players} round-robin over exactly {@code count} teams (at least 2), shuffled first when a
     * {@code random} is given (else in the given order, e.g. a bot lineup sorted by difficulty, so every team gets a
     * fair share). Teams may stay empty: a mode fills them later with {@link GameSession#addBot bots}.
     */
    public static List<MatchTeam> spread(Collection<UUID> players, int count, @Nullable Random random) {
        List<MatchTeam> teams = createTeams(Math.max(2, count));
        List<UUID> order = new ArrayList<>(players);
        if (random != null) {
            Collections.shuffle(order, random);
        }
        for (int i = 0; i < order.size(); i++) {
            teams.get(i % teams.size()).add(order.get(i));
        }
        return List.copyOf(teams);
    }

    /** Creates {@code count} empty teams with palette colors and Korean names. */
    static List<MatchTeam> createTeams(int count) {
        List<MatchTeam> teams = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            if (i < PALETTE.size()) {
                Entry entry = PALETTE.get(i);
                teams.add(new MatchTeam(i, entry.id(), entry.shortName(), entry.color(), entry.dye()));
            } else {
                teams.add(new MatchTeam(i, "team" + (i + 1), (i + 1) + "번", NamedTextColor.WHITE, DyeColor.WHITE));
            }
        }
        return teams;
    }

    /** Adds a late joiner to the team with the fewest members (first such team on ties) and returns it. */
    public static MatchTeam addToSmallest(List<MatchTeam> teams, UUID player) {
        if (teams.isEmpty()) {
            throw new IllegalArgumentException("no teams");
        }
        MatchTeam smallest = teams.stream().min(Comparator.comparingInt(MatchTeam::size)).orElseThrow();
        smallest.add(player);
        return smallest;
    }
}
