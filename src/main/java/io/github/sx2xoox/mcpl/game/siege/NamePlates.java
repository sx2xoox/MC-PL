package io.github.sx2xoox.mcpl.game.siege;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToIntFunction;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.game.superpower.runtime.MatchContext;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The health plate above every fighter's head (docs/siege.md "화면 표시"): a {@link TextDisplay} riding on the player
 * as a passenger, two lines: {@code Lv.3 이름} (the name in the team colour) and {@code ■■■■■■■□□□ ❤ 14/20}. It replaces
 * the vanilla name tag, which is hidden on every viewer's board for the match (the teams' name tag option is put back
 * on {@link #dispose}; the boards themselves go with the match).
 *
 * <p>Every display is hidden by default and shown per viewer: never to the player themself, only to the match's
 * viewers, and never to one from whom the player is concealed ({@link MatchContext#isConcealedFrom}: hidden with
 * {@code hidePlayer}, stealth, an invisible enemy), so the plate never gives a hidden player away. Who sees what is
 * checked every tick; the text is redrawn right after a health change and on every HUD update. A player out of play
 * (dead, respawning, spectating, gone) has no plate; one that lost its seat (a teleport, a respawn) gets a new one.
 */
final class NamePlates {

    static final int SEGMENTS = 10;
    /** Blocks between the top of the head (where passengers sit) and the bottom of the plate. */
    static final float HEIGHT = 0.3f;
    private static final float SCALE = 0.9f;
    private static final Display.Brightness FULL_BRIGHT = new Display.Brightness(15, 15);

    private final SiegeMatch match;
    private final GameSession session;
    private final Messages messages;
    private final ToIntFunction<UUID> level;
    private final Map<UUID, Plate> plates = new HashMap<>();
    private final Set<UUID> dirty = new HashSet<>();
    /** Board teams whose name tag option this match changed, with the option they had. */
    private final Map<Team, Team.OptionStatus> hiddenTags = new HashMap<>();
    private boolean reported;

    private static final class Plate {
        final TextDisplay display;
        final Set<UUID> viewers = new HashSet<>();
        Component text = Component.empty();

        Plate(TextDisplay display) {
            this.display = display;
        }
    }

    NamePlates(SiegeMatch match, ToIntFunction<UUID> level) {
        this.match = match;
        this.session = match.session();
        this.messages = match.messages();
        this.level = level;
    }

    /** The player's health changed: their plate is redrawn on the next {@link #tick}. */
    void changed(Player player) {
        dirty.add(player.getUniqueId());
    }

    /** Every tick: plates come and go with play, follow who may see them, and redraw after a health change. */
    void tick() {
        sync(false);
    }

    /** Redraws every plate and hides the vanilla name tags (also on boards made since). */
    void update() {
        sync(true);
        try {
            hideNameTags();
        } catch (RuntimeException e) {
            report(e);
        }
    }

    private void sync(boolean redrawAll) {
        try {
            Set<UUID> fighters = new HashSet<>();
            for (Player target : participants()) {
                UUID id = target.getUniqueId();
                if (!hasPlate(target)) {
                    remove(id);
                    continue;
                }
                fighters.add(id);
                Plate plate = plates.get(id);
                if (plate != null && !seated(plate, target)) {
                    remove(id);
                    plate = null;
                }
                if (plate == null) {
                    plate = spawn(target);
                    if (plate == null) {
                        continue;
                    }
                    plates.put(id, plate);
                    redraw(plate, target);
                } else if (redrawAll || dirty.contains(id)) {
                    redraw(plate, target);
                }
                showTo(plate, target);
            }
            dirty.clear();
            for (UUID id : List.copyOf(plates.keySet())) {
                if (!fighters.contains(id)) {
                    remove(id);
                }
            }
        } catch (RuntimeException e) {
            report(e);
        }
    }

    private void redraw(Plate plate, Player target) {
        Component next = plate(target);
        if (!next.equals(plate.text)) {
            plate.text = next;
            plate.display.text(next);
        }
    }

    /** Whether {@code target} carries a plate at all: in play, alive, on a team, not a spectator. */
    private boolean hasPlate(Player target) {
        return target.isOnline() && session.isInPlay(target) && !target.isDead()
                && target.getGameMode() != GameMode.SPECTATOR && match.teamIndex(target.getUniqueId()) >= 0;
    }

    private static boolean seated(Plate plate, Player target) {
        TextDisplay display = plate.display;
        return display.isValid() && target.equals(display.getVehicle());
    }

    private @Nullable Plate spawn(Player target) {
        TextDisplay display = target.getWorld().spawn(target.getLocation(), TextDisplay.class, d -> {
            d.setPersistent(false);
            // Nobody gets it with its first packets: viewers are added one by one (showTo).
            d.setVisibleByDefault(false);
            d.setBillboard(Display.Billboard.CENTER);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setShadowed(true);
            d.setSeeThrough(false);
            d.setBackgroundColor(Color.fromARGB(64, 0, 0, 0));
            d.setBrightness(FULL_BRIGHT);
            d.setViewRange(0.75f);
            d.setTransformation(new Transformation(new Vector3f(0, HEIGHT, 0), new AxisAngle4f(),
                    new Vector3f(SCALE, SCALE, SCALE), new AxisAngle4f()));
        });
        session.track(display);
        if (!target.addPassenger(display)) {
            display.remove();
            session.untrack(display);
            return null;
        }
        return new Plate(display);
    }

    private void showTo(Plate plate, Player target) {
        Plugin plugin = session.plugin();
        Set<UUID> now = new HashSet<>();
        for (Player viewer : session.viewers()) {
            if (isShown(target, viewer)) {
                now.add(viewer.getUniqueId());
                if (plate.viewers.add(viewer.getUniqueId())) {
                    viewer.showEntity(plugin, plate.display);
                }
            }
        }
        Iterator<UUID> shown = plate.viewers.iterator();
        while (shown.hasNext()) {
            UUID id = shown.next();
            if (!now.contains(id)) {
                shown.remove();
                Player viewer = Bukkit.getPlayer(id);
                if (viewer != null) {
                    viewer.hideEntity(plugin, plate.display);
                }
            }
        }
    }

    /** Takes the player's plate away now (death, quit). */
    void remove(UUID player) {
        Plate plate = plates.remove(player);
        if (plate != null) {
            TextDisplay display = plate.display;
            display.leaveVehicle();
            display.remove();
            session.untrack(display);
        }
    }

    /** Removes every plate and gives the name tags back. */
    void dispose() {
        for (UUID id : List.copyOf(plates.keySet())) {
            try {
                remove(id);
            } catch (RuntimeException e) {
                report(e);
            }
        }
        dirty.clear();
        for (Map.Entry<Team, Team.OptionStatus> hidden : hiddenTags.entrySet()) {
            try {
                hidden.getKey().setOption(Team.Option.NAME_TAG_VISIBILITY, hidden.getValue());
            } catch (RuntimeException e) {
                // The team was unregistered meanwhile: nothing to give back.
            }
        }
        hiddenTags.clear();
    }

    /** The plate riding on {@code player} (null when they have none). */
    @Nullable TextDisplay display(Player player) {
        Plate plate = plates.get(player.getUniqueId());
        return plate == null ? null : plate.display;
    }

    /** Whether {@code viewer} is shown {@code target}'s plate right now. */
    boolean isShownTo(Player target, Player viewer) {
        Plate plate = plates.get(target.getUniqueId());
        return plate != null && plate.viewers.contains(viewer.getUniqueId());
    }

    /** Hides the vanilla name tag of every fighter on every viewer's private board (never the main board). */
    private void hideNameTags() {
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        List<Player> fighters = participants();
        for (Player viewer : session.viewers()) {
            Scoreboard board = viewer.getScoreboard();
            if (board == main) {
                continue;
            }
            for (Player fighter : fighters) {
                Team team = board.getEntryTeam(fighter.getName());
                if (team == null) {
                    continue;
                }
                Team.OptionStatus status = team.getOption(Team.Option.NAME_TAG_VISIBILITY);
                if (status != Team.OptionStatus.NEVER) {
                    hiddenTags.putIfAbsent(team, status);
                    team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
                }
            }
        }
    }

    /**
     * Whether {@code viewer} sees {@code target}'s plate: never their own, only for a player in play, and never when the
     * target is concealed from the viewer.
     */
    boolean isShown(Player target, Player viewer) {
        if (target.equals(viewer) || !hasPlate(target)) {
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

    /** The plate of {@code target}: level, name in the team colour (or the name its ability shows), health. */
    Component plate(Player target) {
        UUID id = target.getUniqueId();
        int team = match.teamIndex(id);
        TextColor color = team < 0 ? NamedTextColor.GRAY : match.teams().get(team).color();
        Component override = match.nameOverride(id);
        Component name = override != null ? override.colorIfAbsent(color) : Component.text(target.getName(), color);
        AttributeInstance attribute = target.getAttribute(Attribute.MAX_HEALTH);
        double max = attribute == null ? 20 : attribute.getValue();
        return plate(level.applyAsInt(id), name, target.getHealth(), target.getAbsorptionAmount(), max, messages);
    }

    /** {@code Lv.3 이름} over {@code ■■■■■■■□□□ ❤ 14/20} (the level only when the match has levels; {@code +4} absorption). */
    static Component plate(int level, Component name, double health, double absorption, double max,
                           Messages messages) {
        double shown = Math.max(0, health);
        Component levelPart = level > 0 ? messages.get("hud.plate-level", Messages.tag("level", level))
                : Component.empty();
        Component extra = absorption > 0 ? messages.get("hud.plate-absorption",
                Messages.tag("amount", (int) Math.ceil(absorption))) : Component.empty();
        return messages.get("hud.plate", Messages.tag("level", levelPart), Messages.tag("name", name),
                Messages.tag("bar", bar(shown, max)), Messages.tag("health", (int) Math.ceil(shown)),
                Messages.tag("max", (int) Math.ceil(max)), Messages.tag("absorption", extra));
    }

    /** {@link #SEGMENTS} squares, green over half health, gold over a quarter, red below; lost ones dark grey. */
    static Component bar(double health, double max) {
        int filled = filled(health, max);
        double fraction = max > 0 ? health / max : 0;
        TextColor fill = fraction > 0.5 ? NamedTextColor.GREEN : fraction > 0.25 ? NamedTextColor.GOLD
                : NamedTextColor.RED;
        return Component.text("■".repeat(filled), fill)
                .append(Component.text("□".repeat(SEGMENTS - filled), NamedTextColor.DARK_GRAY));
    }

    /** Filled segments of the bar: at least one while alive. */
    static int filled(double health, double max) {
        if (!(health > 0) || !(max > 0)) {
            return 0;
        }
        return Math.max(1, Math.min(SEGMENTS, (int) Math.round(health / max * SEGMENTS)));
    }

    private void report(RuntimeException e) {
        if (!reported) {
            reported = true;
            session.plugin().getLogger().log(Level.WARNING, "Could not update the siege name plates", e);
        }
    }
}
