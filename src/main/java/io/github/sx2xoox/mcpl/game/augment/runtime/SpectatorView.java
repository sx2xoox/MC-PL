package io.github.sx2xoox.mcpl.game.augment.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import io.github.sx2xoox.mcpl.core.game.GameResult;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.game.MatchTeam;
import io.github.sx2xoox.mcpl.core.ui.Format;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.core.ui.Sidebar;
import io.github.sx2xoox.mcpl.game.augment.bot.BotBrain;
import io.github.sx2xoox.mcpl.game.augment.bot.BotController;
import io.github.sx2xoox.mcpl.game.augment.bot.BotManager;
import io.github.sx2xoox.mcpl.game.augment.bot.BotSettings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * What the humans of a 봇 관전전 see and use: the sidebar rows of every bot (health, kills, augments and what it is
 * doing), a short chat line when a bot starts something notable (rate-limited), following a bot with the spectator
 * camera ({@code /게임 증강 관전}) and the winner's announcement.
 */
public final class SpectatorView {

    /** A bot's own feed lines at most this often (ticks). */
    static final long BOT_FEED_GAP = 15 * 20;
    /** Feed lines of all bots at most this often (ticks). */
    static final long FEED_GAP = 3 * 20;

    private final AugmentMatch match;
    private final BotManager bots;
    private final BotSettings.Spectate settings;
    private final Map<UUID, BotBrain.Behaviour> lastBehaviour = new HashMap<>();
    private final Map<UUID, Long> lastFeed = new HashMap<>();
    /** Viewer → the bot their camera follows. */
    private final Map<UUID, UUID> following = new HashMap<>();
    private long lastAnyFeed = Long.MIN_VALUE / 2;

    SpectatorView(AugmentMatch match, BotManager bots, BotSettings.Spectate settings) {
        this.match = match;
        this.bots = bots;
        this.settings = settings;
    }

    private Messages messages() {
        return match.messages();
    }

    private GameSession session() {
        return match.session();
    }

    // =================================================================== announcements

    /** The first lines every viewer gets: how to follow the bots. */
    void welcome() {
        match.announce(messages().prefixed("spectate.welcome"));
    }

    /** Team mode: who is on which team. */
    void announceTeams() {
        if (!session().isTeamMode()) {
            return;
        }
        for (MatchTeam team : session().teams()) {
            List<Component> names = new ArrayList<>();
            for (UUID member : team.members()) {
                names.add(session().name(member));
            }
            match.announce(messages().get("spectate.team", Messages.tag("team", team.displayName()),
                    Messages.tag("members", Component.join(JoinConfiguration.commas(true), names))));
        }
    }

    /** The match is over: the winning bot (or team) with its kills and augments. */
    void announceWinner(GameResult result) {
        if (result.isDraw() || result.winners().isEmpty()) {
            match.announce(messages().prefixed("spectate.draw"));
            return;
        }
        MatchTeam winningTeam = session().isTeamMode() ? session().teamOf(result.winners().getFirst()) : null;
        if (winningTeam != null) {
            int kills = 0;
            int alive = 0;
            List<Component> names = new ArrayList<>();
            for (UUID member : winningTeam.members()) {
                kills += session().stats().of(member).kills();
                alive += session().isAlive(member) ? 1 : 0;
                names.add(session().name(member));
            }
            match.announce(messages().prefixed("spectate.team-winner", Messages.tag("team", winningTeam.displayName()),
                    Messages.tag("alive", alive), Messages.tag("size", winningTeam.size()), Messages.tag("kills", kills),
                    Messages.tag("members", Component.join(JoinConfiguration.commas(true), names))));
            return;
        }
        if (result.winners().size() == 1) {
            UUID winner = result.winners().getFirst();
            match.announce(messages().prefixed("spectate.winner", Messages.tag("bot", session().name(winner)),
                    Messages.tag("kills", session().stats().of(winner).kills()),
                    Messages.tag("augments", match.augments(winner).size())));
            return;
        }
        List<Component> names = new ArrayList<>();
        for (UUID id : result.winners()) {
            names.add(session().name(id));
        }
        match.announce(messages().prefixed("spectate.winners",
                Messages.tag("bots", Component.join(JoinConfiguration.commas(true), names))));
    }

    // =================================================================== sidebar

    /** The rows under the phase line: the next round, how many bots stand and one row per bot. */
    List<Component> lines() {
        GameSession session = session();
        List<Component> lines = new ArrayList<>();
        long toRound = match.ticksToNextRound();
        lines.add(toRound < 0 ? messages().get("hud.rounds-done")
                : messages().get("hud.next-round", Messages.tag("round", match.nextRoundNumber()),
                Messages.tag("time", Format.clock((toRound + 19) / 20))));
        lines.add(messages().get("spectate.hud-alive", Messages.tag("alive", session.aliveCount()),
                Messages.tag("total", bots.all().size())));
        List<BotController> standing = ranked();
        int room = Sidebar.MAX_LINES - 4 - lines.size();
        if (session.isTeamMode()) {
            // per team: a header (alive / members, kills) and its standing bots
            int shown = 0;
            for (MatchTeam team : session.teams()) {
                List<BotController> members = standing.stream().filter(bot -> team.contains(bot.id())).toList();
                if (members.isEmpty() || lines.size() >= Sidebar.MAX_LINES - 4) {
                    continue;
                }
                int kills = 0;
                for (UUID member : team.members()) {
                    kills += session.stats().of(member).kills();
                }
                lines.add(messages().get("spectate.hud-team", Messages.tag("team", team.displayName()),
                        Messages.tag("alive", members.size()), Messages.tag("size", team.size()),
                        Messages.tag("kills", kills)));
                for (BotController bot : members) {
                    if (lines.size() >= Sidebar.MAX_LINES - 4) {
                        break;
                    }
                    lines.add(row(bot));
                    shown++;
                }
            }
            if (shown < standing.size()) {
                lines.add(messages().get("spectate.hud-more", Messages.tag("count", standing.size() - shown)));
            }
            return lines;
        }
        for (int i = 0; i < standing.size() && i < room; i++) {
            lines.add(row(standing.get(i)));
        }
        if (standing.size() > room) {
            lines.add(messages().get("spectate.hud-more", Messages.tag("count", standing.size() - room)));
        }
        return lines;
    }

    /** The bots still standing, most kills first, then the healthiest. */
    List<BotController> ranked() {
        List<BotController> out = new ArrayList<>();
        for (BotController bot : bots.all()) {
            if (bots.body(bot.id()) != null) {
                out.add(bot);
            }
        }
        out.sort(Comparator.<BotController>comparingInt(bot -> -session().stats().of(bot.id()).kills())
                .thenComparingDouble(bot -> -health(bot)));
        return out;
    }

    private double health(BotController bot) {
        LivingEntity body = bots.body(bot.id());
        return body == null ? 0 : body.getHealth() + body.getAbsorptionAmount();
    }

    /** One bot's row: "철수 봇 [어려움] ❤18 ⚔2 ◆3 교전". */
    Component row(BotController bot) {
        BotBrain.Decision decision = bot.decision();
        return messages().get("spectate.hud-bot", Messages.tag("bot", session().name(bot.id())),
                Messages.tag("health", Math.round(health(bot))),
                Messages.tag("kills", session().stats().of(bot.id()).kills()),
                Messages.tag("augments", match.augments(bot.id()).size()),
                Messages.tag("doing", decision == null ? "-" : decision.behaviour().koreanName()));
    }

    // =================================================================== feed and cameras

    /** Every second: the behaviour feed and the cameras of viewers that stopped following. */
    void tick() {
        long now = match.clock();
        for (BotController bot : bots.all()) {
            BotBrain.Decision decision = bot.decision();
            if (decision == null || bots.body(bot.id()) == null) {
                continue;
            }
            BotBrain.Behaviour before = lastBehaviour.put(bot.id(), decision.behaviour());
            if (!settings.behaviourChat() || before == decision.behaviour() || !decision.behaviour().notable()
                    || !match.fight()) {
                continue;
            }
            Long last = lastFeed.get(bot.id());
            if (last != null && now - last < BOT_FEED_GAP || now - lastAnyFeed < FEED_GAP) {
                continue;
            }
            lastFeed.put(bot.id(), now);
            lastAnyFeed = now;
            match.announce(feedLine(bot, decision));
        }
        following.entrySet().removeIf(entry -> {
            Player viewer = org.bukkit.Bukkit.getPlayer(entry.getKey());
            return viewer == null || viewer.getGameMode() != GameMode.SPECTATOR || viewer.getSpectatorTarget() == null;
        });
    }

    private Component feedLine(BotController bot, BotBrain.Decision decision) {
        UUID target = decision.target();
        if (target != null && session().isParticipant(target)) {
            return messages().get("spectate.feed-target", Messages.tag("bot", follow(bot.id())),
                    Messages.tag("doing", decision.behaviour().koreanName()),
                    Messages.tag("target", follow(target)));
        }
        return messages().get("spectate.feed", Messages.tag("bot", follow(bot.id())),
                Messages.tag("doing", decision.behaviour().koreanName()));
    }

    /** A bot's name that follows it when clicked. */
    private Component follow(UUID bot) {
        BotController controller = bots.bot(bot);
        Component name = session().name(bot);
        if (controller == null) {
            return name;
        }
        return name.clickEvent(ClickEvent.runCommand("/게임 증강 관전 " + controller.name()))
                .hoverEvent(HoverEvent.showText(messages().get("spectate.click-follow")));
    }

    /** A bot was eliminated: viewers following it move on to its killer (if a bot standing) or the next one. */
    void botDied(UUID bot, @Nullable UUID killer) {
        for (Map.Entry<UUID, UUID> entry : List.copyOf(following.entrySet())) {
            if (!entry.getValue().equals(bot)) {
                continue;
            }
            Player viewer = org.bukkit.Bukkit.getPlayer(entry.getKey());
            if (viewer == null) {
                following.remove(entry.getKey());
                continue;
            }
            UUID next = killer != null && bots.body(killer) != null ? killer : next(bot);
            if (next == null || !follow(viewer, next)) {
                following.remove(entry.getKey());
            }
        }
    }

    /** The bot standing after {@code after} in the list (wrapping), or the first; null if none stands. */
    public @Nullable UUID next(@Nullable UUID after) {
        List<BotController> all = bots.all();
        int start = 0;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id().equals(after)) {
                start = i + 1;
                break;
            }
        }
        for (int i = 0; i < all.size(); i++) {
            BotController candidate = all.get((start + i) % all.size());
            if (bots.body(candidate.id()) != null && !candidate.id().equals(after)) {
                return candidate.id();
            }
        }
        return null;
    }

    /** The bot whose name starts like {@code query} (spaces, case and the difficulty tag ignored), standing. */
    public @Nullable UUID find(String query) {
        String wanted = normalize(query);
        if (wanted.isEmpty()) {
            return null;
        }
        UUID prefix = null;
        for (BotController bot : bots.all()) {
            if (bots.body(bot.id()) == null) {
                continue;
            }
            String name = normalize(bot.name());
            String full = normalize(session().plainName(bot.id()));
            if (name.equals(wanted) || full.equals(wanted)) {
                return bot.id();
            }
            if (prefix == null && (name.startsWith(wanted) || full.startsWith(wanted))) {
                prefix = bot.id();
            }
        }
        return prefix;
    }

    private static String normalize(String text) {
        return text.replace(" ", "").toLowerCase(Locale.ROOT);
    }

    /** Puts the viewer's spectator camera on {@code bot}; false if it is not standing or they cannot watch. */
    public boolean follow(Player viewer, UUID bot) {
        LivingEntity body = bots.body(bot);
        if (body == null || !session().isSpectator(viewer)) {
            return false;
        }
        if (viewer.getGameMode() != GameMode.SPECTATOR) {
            viewer.setGameMode(GameMode.SPECTATOR);
        }
        if (viewer.getSpectatorTarget() != null) {
            viewer.setSpectatorTarget(null);
        }
        viewer.teleport(body.getLocation().add(0, 1.5, 0));
        viewer.setSpectatorTarget(body);
        following.put(viewer.getUniqueId(), bot);
        messages().actionBar(viewer, "spectate.following", Messages.tag("bot", session().name(bot)));
        return true;
    }

    /** The bot the viewer's camera follows, or null. */
    public @Nullable UUID followed(Player viewer) {
        UUID bot = following.get(viewer.getUniqueId());
        Entity target = viewer.getSpectatorTarget();
        return bot != null && target != null && target.equals(bots.body(bot)) ? bot : null;
    }

    /** The clickable list of the bots standing (click: follow). */
    public List<Component> list() {
        List<Component> lines = new ArrayList<>();
        lines.add(messages().get("spectate.list-header", Messages.tag("alive", ranked().size())));
        for (BotController bot : ranked()) {
            lines.add(row(bot).clickEvent(ClickEvent.runCommand("/게임 증강 관전 " + bot.name()))
                    .hoverEvent(HoverEvent.showText(messages().get("spectate.click-follow"))));
        }
        if (lines.size() == 1) {
            lines.add(Component.text(" -", NamedTextColor.DARK_GRAY));
        }
        return lines;
    }
}
