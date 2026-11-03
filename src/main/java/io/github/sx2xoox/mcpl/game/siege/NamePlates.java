package io.github.sx2xoox.mcpl.game.siege;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToIntFunction;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.game.superpower.runtime.MatchContext;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.jspecify.annotations.Nullable;

/**
 * The health plate under every player's (team-coloured) name: {@code Lv.3 ▮▮▮▮▮▮▮▯▯▯ 14}. It is the scoreboard's
 * below-name line on each viewer's private board (the session gives every viewer one), so the client draws it as part
 * of the name tag: never for the player themself, and gone whenever the name tag is (a player hidden with
 * {@code hidePlayer}, an invisible enemy). On top of that, a viewer from whom the player is concealed
 * ({@link MatchContext#isConcealedFrom}: stealth, invisibility) gets no score for that player at all, so the plate never
 * gives a stealthed player away. Updated every HUD update and right after a player's health changed.
 */
final class NamePlates {

    static final String OBJECTIVE = "mcpl_siege_plate";
    static final int SEGMENTS = 10;

    private final SiegeMatch match;
    private final GameSession session;
    private final Messages messages;
    private final ToIntFunction<UUID> level;
    /** What each viewer's board shows now, by player name (null = nothing). */
    private final Map<UUID, Map<String, Component>> shown = new HashMap<>();
    private final Set<UUID> dirty = new HashSet<>();
    private boolean reported;

    NamePlates(SiegeMatch match, ToIntFunction<UUID> level) {
        this.match = match;
        this.session = match.session();
        this.messages = match.messages();
        this.level = level;
    }

    /** The player's health changed: their plate is redrawn on the next {@link #flush}. */
    void changed(Player player) {
        dirty.add(player.getUniqueId());
    }

    /** Redraws the plates of players whose health changed. */
    void flush() {
        if (dirty.isEmpty()) {
            return;
        }
        Set<UUID> targets = Set.copyOf(dirty);
        dirty.clear();
        draw(targets);
    }

    /** Redraws every plate. */
    void update() {
        dirty.clear();
        draw(null);
    }

    private void draw(@Nullable Set<UUID> only) {
        try {
            Set<UUID> viewers = new HashSet<>();
            for (Player viewer : session.viewers()) {
                viewers.add(viewer.getUniqueId());
                Objective objective = objective(viewer);
                if (objective == null) {
                    continue;
                }
                Map<String, Component> current = shown.computeIfAbsent(viewer.getUniqueId(), id -> new HashMap<>());
                for (Player target : participants()) {
                    if (only != null && !only.contains(target.getUniqueId())) {
                        continue;
                    }
                    Component plate = isShown(target, viewer) ? plate(target) : null;
                    String entry = target.getName();
                    if (plate == null) {
                        if (current.remove(entry) != null) {
                            Score score = objective.getScore(entry);
                            if (score.isScoreSet()) {
                                score.resetScore();
                            }
                        }
                    } else if (!plate.equals(current.get(entry))) {
                        current.put(entry, plate);
                        Score score = objective.getScore(entry);
                        score.setScore((int) Math.ceil(target.getHealth()));
                        score.numberFormat(NumberFormat.fixed(plate));
                    }
                }
            }
            shown.keySet().retainAll(viewers);
        } catch (RuntimeException e) {
            if (!reported) {
                reported = true;
                session.plugin().getLogger().log(Level.WARNING, "Could not update the siege name plates", e);
            }
        }
    }

    /** The below-name objective on the viewer's private board (null without one, e.g. on the main board). */
    private @Nullable Objective objective(Player viewer) {
        Scoreboard board = viewer.getScoreboard();
        if (board == Bukkit.getScoreboardManager().getMainScoreboard()) {
            return null;
        }
        Objective objective = board.getObjective(OBJECTIVE);
        if (objective == null) {
            objective = board.registerNewObjective(OBJECTIVE, Criteria.DUMMY, Component.empty());
            objective.setDisplaySlot(DisplaySlot.BELOW_NAME);
            objective.numberFormat(NumberFormat.blank());
        }
        return objective;
    }

    /**
     * Whether {@code viewer} sees {@code target}'s plate: never their own, only for a player in play, and never when the
     * target is concealed from the viewer.
     */
    boolean isShown(Player target, Player viewer) {
        if (target.equals(viewer) || !session.isInPlay(target) || target.isDead()
                || match.teamIndex(target.getUniqueId()) < 0) {
            return false;
        }
        MatchContext ctx = match.abilities().context();
        return !ctx.isConcealedFrom(target, viewer);
    }

    /** Online members of both teams. */
    private List<Player> participants() {
        List<Player> out = new ArrayList<>();
        for (int team = 0; team < 2; team++) {
            for (UUID member : match.members(team)) {
                Player player = Bukkit.getPlayer(member);
                if (player != null) {
                    out.add(player);
                }
            }
        }
        return out;
    }

    /** The plate of {@code target}: level (when the match has levels), health bar in the team colour, health. */
    Component plate(Player target) {
        int team = match.teamIndex(target.getUniqueId());
        TextColor color = team < 0 ? NamedTextColor.GRAY : match.teams().get(team).color();
        AttributeInstance attribute = target.getAttribute(Attribute.MAX_HEALTH);
        double max = attribute == null ? 20 : attribute.getValue();
        double health = target.getHealth() + target.getAbsorptionAmount();
        return plate(level.applyAsInt(target.getUniqueId()), health, max, color, messages);
    }

    static Component plate(int level, double health, double max, TextColor color, Messages messages) {
        int filled = filled(health, max);
        Component bar = Component.text("▮".repeat(filled), color)
                .append(Component.text("▯".repeat(SEGMENTS - filled), NamedTextColor.DARK_GRAY));
        Component levelPart = level > 0 ? messages.get("hud.plate-level", Messages.tag("level", level))
                : Component.empty();
        return messages.get("hud.plate", Messages.tag("level", levelPart), Messages.tag("bar", bar),
                Messages.tag("health", (int) Math.ceil(Math.max(0, health))));
    }

    /** Filled segments of the bar: at least one while alive. */
    static int filled(double health, double max) {
        if (!(health > 0) || !(max > 0)) {
            return 0;
        }
        return Math.max(1, Math.min(SEGMENTS, (int) Math.round(health / max * SEGMENTS)));
    }
}
