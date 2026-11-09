package io.github.sx2xoox.mcpl.core.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import io.github.sx2xoox.mcpl.core.Hub;
import io.github.sx2xoox.mcpl.core.combat.MatchStats;
import io.github.sx2xoox.mcpl.core.combat.PlayerStats;
import io.github.sx2xoox.mcpl.core.ui.Feedback;
import io.github.sx2xoox.mcpl.core.ui.Format;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.core.ui.Sidebar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

/** Computes placement/MVP for a {@link GameResult} and presents it (titles, chat summary, sounds, fireworks). */
final class ResultPresenter {

    static final int MAX_ROWS = 10;

    private static final TextColor GOLD = TextColor.color(0xFFD700);
    private static final TextColor SILVER = TextColor.color(0xC0C0C0);
    private static final TextColor BRONZE = TextColor.color(0xCD7F32);

    record Row(UUID id, int rank, PlayerStats stats, Component name) {
    }

    record Summary(GameResult result, List<Row> rows, Map<UUID, Row> byPlayer, @Nullable UUID mvp, Component winner,
                   long durationMillis) {
    }

    private ResultPresenter() {
    }

    static NamespacedKey celebrationKey(Hub hub) {
        return hub.key("celebration");
    }

    // ------------------------------------------------------------------ compute

    static Summary compute(GameSession session, GameResult result) {
        Set<UUID> participants = session.participantIds();
        MatchStats stats = session.stats();
        Comparator<UUID> performance = (a, b) -> MatchStats.PERFORMANCE.compare(stats.of(a), stats.of(b));

        LinkedHashSet<UUID> order = new LinkedHashSet<>();
        for (UUID id : result.placement()) {
            if (participants.contains(id)) {
                order.add(id);
            }
        }
        List<UUID> winners = new ArrayList<>();
        for (UUID id : result.winners()) {
            if (participants.contains(id) && !order.contains(id)) {
                winners.add(id);
            }
        }
        winners.sort(Comparator.<UUID, Boolean>comparing(id -> !session.isAlive(id)).thenComparing(performance));
        order.addAll(winners);

        List<UUID> survivors = new ArrayList<>();
        for (UUID id : session.aliveIds()) {
            if (!order.contains(id)) {
                survivors.add(id);
            }
        }
        survivors.sort(performance);
        order.addAll(survivors);

        List<UUID> eliminated = new ArrayList<>(session.eliminationOrder());
        Collections.reverse(eliminated);
        order.addAll(eliminated);

        List<UUID> rest = new ArrayList<>();
        for (UUID id : participants) {
            if (!order.contains(id)) {
                rest.add(id);
            }
        }
        rest.sort(performance);
        order.addAll(rest);

        List<Row> rows = new ArrayList<>(order.size());
        Map<UUID, Row> byPlayer = new HashMap<>();
        int rank = 1;
        for (UUID id : order) {
            Row row = new Row(id, rank++, stats.of(id), session.displayName(id));
            rows.add(row);
            byPlayer.put(id, row);
        }

        UUID mvp = result.mvp() != null && participants.contains(result.mvp()) ? result.mvp() : stats.mvp(participants);
        return new Summary(result, List.copyOf(rows), Map.copyOf(byPlayer), mvp, winnerComponent(session, result),
                session.elapsedMillis());
    }

    private static Component winnerComponent(GameSession session, GameResult result) {
        Messages messages = session.messages();
        if (result.winningTeam() != null) {
            return result.winningTeam().displayName();
        }
        List<UUID> winners = result.winners();
        if (winners.isEmpty()) {
            return messages.get("results.nobody");
        }
        List<Component> names = new ArrayList<>();
        for (int i = 0; i < winners.size() && i < 4; i++) {
            names.add(session.name(winners.get(i)));
        }
        Component joined = Component.join(JoinConfiguration.commas(true), names);
        if (winners.size() > 4) {
            joined = joined.append(messages.get("results.and-more", Messages.tag("count", winners.size() - 4)));
        }
        return joined;
    }

    // ------------------------------------------------------------------ present

    static void present(GameSession session, Summary summary) {
        Messages messages = session.messages();
        GameResult result = summary.result();

        for (Player viewer : session.viewers()) {
            if (result.isDraw()) {
                Feedback.title(viewer, messages.get("results.title-draw"), messages.get("results.subtitle-draw"), 10, 70, 20);
                Feedback.play(viewer, Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1f);
            } else if (result.isWinner(viewer.getUniqueId())) {
                Feedback.title(viewer, messages.get("results.title-win"),
                        messages.get("results.subtitle-win", Messages.tag("game", session.game().displayComponent())),
                        10, 70, 20);
                Feedback.play(viewer, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            } else {
                Feedback.title(viewer, messages.get("results.title-other", Messages.tag("winner", summary.winner())),
                        messages.get("results.subtitle-other"), 10, 70, 20);
                Feedback.play(viewer, Sound.ENTITY_PLAYER_LEVELUP, 1f, 0.7f);
            }
        }

        session.broadcast(Component.join(JoinConfiguration.newlines(), chatSummary(session, summary)));

        for (Player player : session.participants()) {
            Row row = summary.byPlayer().get(player.getUniqueId());
            if (row != null) {
                messages.send(player, "results.personal", rowTags(row));
            }
        }

        if (session.hub().config().fireworks() && !result.isDraw()) {
            List<Color> colors = result.winningTeam() != null
                    ? List.of(result.winningTeam().rgb(), Color.WHITE)
                    : List.of(Color.fromRGB(0xFFD700), Color.ORANGE, Color.YELLOW);
            for (UUID id : result.winners()) {
                Player winner = session.hub().plugin().getServer().getPlayer(id);
                if (winner != null && winner.getGameMode() != GameMode.SPECTATOR
                        && session.arena().world().equals(winner.getWorld())) {
                    launchFireworks(session, winner, colors);
                }
            }
        }
    }

    static List<Component> chatSummary(GameSession session, Summary summary) {
        Messages messages = session.messages();
        GameResult result = summary.result();
        List<Component> lines = new ArrayList<>();
        lines.add(messages.get("results.header", Messages.tag("game", session.game().displayComponent())));
        if (result.isDraw()) {
            lines.add(messages.get("results.draw-line"));
        } else {
            lines.add(messages.get("results.winner-line", Messages.tag("winner", summary.winner())));
        }
        if (result.reason() != null) {
            lines.add(messages.get("results.reason-line", Messages.tag("reason", result.reason())));
        }
        lines.add(Component.empty());
        List<Row> rows = summary.rows();
        for (int i = 0; i < rows.size() && i < MAX_ROWS; i++) {
            lines.add(messages.get("results.row", rowTags(rows.get(i))));
        }
        if (rows.size() > MAX_ROWS) {
            lines.add(messages.get("results.more", Messages.tag("count", rows.size() - MAX_ROWS)));
        }
        if (summary.mvp() != null) {
            Row mvp = summary.byPlayer().get(summary.mvp());
            if (mvp != null) {
                lines.add(Component.empty());
                lines.add(messages.get("results.mvp-line", rowTags(mvp)));
            }
        }
        lines.add(messages.get("results.duration-line",
                Messages.tag("duration", Format.duration(summary.durationMillis()))));
        lines.add(messages.get("results.footer"));
        return lines;
    }

    private static TagResolver[] rowTags(Row row) {
        return new TagResolver[] {
                Messages.tag("rank", Component.text(row.rank() + "위", rankColor(row.rank()))),
                Messages.tag("player", row.name()),
                Messages.tag("kills", row.stats().kills()),
                Messages.tag("deaths", row.stats().deaths()),
                Messages.tag("damage", Format.number(row.stats().damageDealt())),
                Messages.tag("taken", Format.number(row.stats().damageTaken()))
        };
    }

    private static TextColor rankColor(int rank) {
        return switch (rank) {
            case 1 -> GOLD;
            case 2 -> SILVER;
            case 3 -> BRONZE;
            default -> NamedTextColor.GRAY;
        };
    }

    static void endingSidebar(GameSession session, Summary summary, Sidebar sidebar, Player viewer, int seconds) {
        Messages messages = session.messages();
        sidebar.title(messages.get("results.sidebar-title"));
        Row row = summary.byPlayer().get(viewer.getUniqueId());
        List<TagResolver> tags = new ArrayList<>();
        tags.add(Messages.tag("winner", summary.result().isDraw() ? messages.get("results.nobody") : summary.winner()));
        tags.add(Messages.tag("seconds", seconds));
        if (row != null) {
            tags.addAll(List.of(rowTags(row)));
        }
        sidebar.lines(messages.lines(row != null ? "results.sidebar" : "results.sidebar-spectator",
                tags.toArray(TagResolver[]::new)));
    }

    // ------------------------------------------------------------------ fireworks

    private static void launchFireworks(GameSession session, Player winner, List<Color> colors) {
        Random random = session.random();
        for (int wave = 0; wave < 3; wave++) {
            session.hubLater(wave * 12L, () -> {
                if (!winner.isOnline() || winner.isDead()) {
                    return;
                }
                Location base = winner.getLocation();
                for (int i = 0; i < 2; i++) {
                    Location spot = base.clone().add(random.nextDouble() * 4 - 2, 0.5, random.nextDouble() * 4 - 2);
                    spawnFirework(session, spot, colors, random);
                }
            });
        }
    }

    private static void spawnFirework(GameSession session, Location location, List<Color> colors, Random random) {
        NamespacedKey key = celebrationKey(session.hub());
        FireworkEffect.Type[] types = {FireworkEffect.Type.BALL_LARGE, FireworkEffect.Type.STAR, FireworkEffect.Type.BURST};
        location.getWorld().spawn(location, Firework.class, firework -> {
            FireworkMeta meta = firework.getFireworkMeta();
            meta.addEffect(FireworkEffect.builder()
                    .with(types[random.nextInt(types.length)])
                    .withColor(colors)
                    .withFade(Color.WHITE)
                    .flicker(random.nextBoolean())
                    .trail(true)
                    .build());
            meta.setPower(1);
            firework.setFireworkMeta(meta);
            firework.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
            firework.setPersistent(false);
        });
    }
}
