package io.github.sx2xoox.mcpl.core.game;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.DyeColor;
import org.jspecify.annotations.Nullable;

/**
 * Splits participants into balanced teams of the configured size. Pure logic (no server access) so it is easy to
 * test; {@link GameSession} calls it when the countdown ends ({@link #form}, with the players' team picks).
 */
public final class TeamAssigner {

    /** One color of the team palette: what {@link MatchTeam}s are made of and what players pick before a match. */
    public record Entry(String id, String shortName, NamedTextColor color, DyeColor dye) {

        /** Korean team name such as {@code "빨강 팀"}. */
        public String name() {
            return shortName + " 팀";
        }

        /** The team name in the team color. */
        public Component displayName() {
            return Component.text(name(), color);
        }
    }

    /**
     * The teams a match formed from team picks ({@link #form}): {@code moved} were moved off their picked team to
     * keep the teams playable, {@code refused} picked a team this match does not have (both were placed
     * automatically).
     */
    public record Formation(List<MatchTeam> teams, Set<UUID> moved, Set<UUID> refused) {
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

    /**
     * Forms the teams of a starting match from the players' team picks ({@code presets}: player → palette id, see
     * {@link TeamPresets}); everyone without a usable pick is placed like before (shuffled onto the smallest team).
     * <ul>
     *     <li>{@code fixedCount >= 2}: exactly that many teams in palette order (공성전: 빨강, 파랑); a pick of another
     *     color is refused;</li>
     *     <li>else {@code teamSize > 0}: {@code max(2, ceil(players / teamSize))} teams (at most one per player), more
     *     if the picks name more colors; the picked colors are used, the rest are the first free palette colors;</li>
     *     <li>else (or fewer than two players without a fixed count): free-for-all (no teams).</li>
     * </ul>
     * Unless {@code botsFill} (the mode fills the teams with bots), a team never stays empty while another has two or
     * more, and with {@code balance} team sizes differ by at most one: members are moved off the biggest team (players
     * without a pick first) and the moved pickers are reported.
     */
    public static Formation form(Collection<UUID> players, Map<UUID, String> presets, int fixedCount, int teamSize,
                                 boolean balance, boolean botsFill, Random random) {
        Set<UUID> moved = new LinkedHashSet<>();
        Set<UUID> refused = new LinkedHashSet<>();
        if (fixedCount < 2 && (teamSize <= 0 || players.size() < 2)) {
            return new Formation(List.of(), moved, refused);
        }
        // the usable picks of these players, in pick order
        Map<UUID, String> picks = new LinkedHashMap<>();
        for (Map.Entry<UUID, String> pick : presets.entrySet()) {
            if (players.contains(pick.getKey()) && entry(pick.getValue()) != null) {
                picks.put(pick.getKey(), pick.getValue());
            }
        }
        List<MatchTeam> teams;
        if (fixedCount >= 2) {
            teams = createTeams(fixedCount);
            Set<String> ids = new HashSet<>();
            teams.forEach(team -> ids.add(team.id()));
            picks.entrySet().removeIf(pick -> {
                if (ids.contains(pick.getValue())) {
                    return false;
                }
                refused.add(pick.getKey());
                return true;
            });
        } else {
            int base = Math.min(Math.max(2, (players.size() + teamSize - 1) / teamSize), players.size());
            Set<String> chosen = new HashSet<>(picks.values());
            int count = Math.max(base, chosen.size());
            List<Entry> entries = new ArrayList<>();
            for (Entry entry : PALETTE) {
                if (chosen.contains(entry.id())) {
                    entries.add(entry);
                }
            }
            for (Entry entry : PALETTE) {
                if (entries.size() >= count) {
                    break;
                }
                if (!chosen.contains(entry.id())) {
                    entries.add(entry);
                }
            }
            entries.sort(Comparator.comparingInt(PALETTE::indexOf));
            teams = new ArrayList<>(count);
            for (Entry entry : entries) {
                teams.add(new MatchTeam(teams.size(), entry.id(), entry.shortName(), entry.color(), entry.dye()));
            }
            for (int i = teams.size(); i < count; i++) {
                teams.add(numbered(i));
            }
        }
        for (Map.Entry<UUID, String> pick : picks.entrySet()) {
            for (MatchTeam team : teams) {
                if (team.id().equals(pick.getValue())) {
                    team.add(pick.getKey());
                }
            }
        }
        List<UUID> rest = new ArrayList<>();
        for (UUID player : players) {
            if (!picks.containsKey(player)) {
                rest.add(player);
            }
        }
        Collections.shuffle(rest, random);
        for (UUID player : rest) {
            addToSmallest(teams, player);
        }
        if (!botsFill) {
            while (true) {
                MatchTeam largest = teams.stream().max(Comparator.comparingInt(MatchTeam::size)).orElseThrow();
                MatchTeam smallest = teams.stream().min(Comparator.comparingInt(MatchTeam::size)).orElseThrow();
                boolean empty = smallest.size() == 0 && largest.size() >= 2;
                if (!empty && !(balance && largest.size() - smallest.size() > 1)) {
                    break;
                }
                List<UUID> candidates = new ArrayList<>();
                for (UUID member : largest.members()) {
                    if (!picks.containsKey(member)) {
                        candidates.add(member);
                    }
                }
                if (candidates.isEmpty()) {
                    candidates.addAll(largest.members());
                }
                UUID move = candidates.get(random.nextInt(candidates.size()));
                largest.remove(move);
                smallest.add(move);
                if (picks.containsKey(move)) {
                    moved.add(move);
                }
            }
        }
        return new Formation(List.copyOf(teams), moved, refused);
    }

    /** The palette, in team order (빨강, 파랑, 초록, ...). */
    public static List<Entry> palette() {
        return PALETTE;
    }

    /** The palette color with this id ({@code "red"}), or null. */
    public static @Nullable Entry entry(String id) {
        for (Entry entry : PALETTE) {
            if (entry.id().equals(id)) {
                return entry;
            }
        }
        return null;
    }

    /** Reads a team the way players type it: {@code 빨강}, {@code 빨강팀}, {@code 빨강 팀} or {@code red}; null if none. */
    public static @Nullable Entry parse(String input) {
        String text = input.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        if (text.endsWith("팀")) {
            text = text.substring(0, text.length() - 1);
        }
        for (Entry entry : PALETTE) {
            if (entry.id().equals(text) || entry.shortName().equals(text)) {
                return entry;
            }
        }
        return null;
    }

    /** Creates {@code count} empty teams with palette colors and Korean names. */
    static List<MatchTeam> createTeams(int count) {
        List<MatchTeam> teams = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            if (i < PALETTE.size()) {
                Entry entry = PALETTE.get(i);
                teams.add(new MatchTeam(i, entry.id(), entry.shortName(), entry.color(), entry.dye()));
            } else {
                teams.add(numbered(i));
            }
        }
        return teams;
    }

    private static MatchTeam numbered(int index) {
        return new MatchTeam(index, "team" + (index + 1), (index + 1) + "번", NamedTextColor.WHITE, DyeColor.WHITE);
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
