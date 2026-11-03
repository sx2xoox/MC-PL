package io.github.sx2xoox.mcpl.game.siege;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.game.MatchTeam;
import io.github.sx2xoox.mcpl.core.map.siege.SiegeSites;
import io.github.sx2xoox.mcpl.core.ui.Feedback;
import io.github.sx2xoox.mcpl.core.ui.Format;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The neutral objectives of a 공성전 half (docs/siege.md §13): the map's data nodes (open from the start, back a while
 * after each capture) and the 오버클럭 코어 (appears on a timer with an announcement). Players of one team standing
 * inside capture a point over time; both teams inside pause it (contested), an empty point slowly falls back. The
 * capturing team gets experience ({@link SiegeProgress#teamXp}) and a short buff. A label and a glowing block mark each
 * point; everything is tracked by the session and removed at the end of the half.
 */
final class ObjectivePoints {

    /** Blocks a player's feet may be above or below the point and still count as inside. */
    static final double HEIGHT_RANGE = 3.5;

    private final SiegeMatch match;
    private final GameSession session;
    private final Messages messages;
    private final SiegeProgress progress;
    private final List<Point> points = new ArrayList<>();
    private final Set<UUID> buffed = new HashSet<>();

    ObjectivePoints(SiegeMatch match, SiegeProgress progress) {
        this.match = match;
        this.session = match.session();
        this.messages = match.messages();
        this.progress = progress;
    }

    /** One capture point of the half. */
    final class Point {
        private final SiegeSites.Objective site;
        private final Location at;
        private boolean open;
        /** Half tick at which a closed point opens. */
        private long opensAt;
        private boolean warned;
        /** The team the progress belongs to, or -1. */
        private int capturer = -1;
        private int progressTicks;
        private boolean contested;
        private @Nullable TextDisplay label;
        private @Nullable BlockDisplay body;

        private Point(SiegeSites.Objective site, Location at) {
            this.site = site;
            this.at = at;
        }

        SiegeSites.Objective site() {
            return site;
        }

        Location at() {
            return at.clone();
        }

        boolean isOpen() {
            return open;
        }

        int capturer() {
            return capturer;
        }

        boolean isContested() {
            return contested;
        }

        /** Capture progress 0..1. */
        double fraction() {
            return Math.min(1.0, (double) progressTicks / captureTicks());
        }

        long opensAt() {
            return opensAt;
        }

        int captureTicks() {
            return site.major() ? settings().majorCaptureTicks() : settings().nodeCaptureTicks();
        }

        boolean contains(Location location) {
            if (location.getWorld() == null || !location.getWorld().equals(at.getWorld())
                    || Math.abs(location.getY() - at.getY()) > HEIGHT_RANGE) {
                return false;
            }
            double dx = location.getX() - at.getX();
            double dz = location.getZ() - at.getZ();
            return dx * dx + dz * dz <= (double) site.radius() * site.radius();
        }
    }

    private SiegeProgressSettings settings() {
        return progress.settings();
    }

    // ------------------------------------------------------------------ flow

    /** The fight of a half begins: data nodes open, the 오버클럭 코어 waits for its first time. */
    void beginFight() {
        clear();
        SiegeLayout layout = match.layout();
        for (SiegeSites.Objective site : layout.sites().objectives()) {
            Point point = new Point(site, layout.at(site));
            point.open = !site.major();
            point.opensAt = site.major() ? settings().majorFirstTicks() : 0;
            points.add(point);
            if (site.major() && point.opensAt <= 0) {
                open(point);
            }
            show(point, true);
        }
    }

    /** The half is over (or the match): the points and their buffs are gone. */
    void clear() {
        for (Point point : points) {
            remove(point);
        }
        points.clear();
        for (UUID id : buffed) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                player.removePotionEffect(PotionEffectType.SPEED);
                player.removePotionEffect(PotionEffectType.RESISTANCE);
                player.removePotionEffect(PotionEffectType.REGENERATION);
            }
        }
        buffed.clear();
    }

    /** Once a second of the fight. */
    void second(long now) {
        for (Point point : points) {
            if (!point.open) {
                waitToOpen(point, now);
            } else {
                capture(point, now);
            }
            if (match.phase() != SiegeMatch.Phase.FIGHT) {
                return;
            }
            show(point, false);
            if (point.open) {
                ring(point);
            }
        }
    }

    private void waitToOpen(Point point, long now) {
        long left = point.opensAt - now;
        if (left <= 0) {
            open(point);
            return;
        }
        int warning = settings().majorWarningTicks();
        if (point.site.major() && !point.warned && warning > 0 && left <= warning) {
            point.warned = true;
            session.broadcast(messages.prefixed("objective.core-soon", Messages.tag("name", point.site.name()),
                    Messages.tag("seconds", (left + 19) / 20)));
            session.playSound(Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 0.8f);
        }
    }

    private void open(Point point) {
        point.open = true;
        point.capturer = -1;
        point.progressTicks = 0;
        point.contested = false;
        if (point.site.major()) {
            match.title(messages.get("objective.core-title", Messages.tag("name", point.site.name())),
                    messages.get("objective.core-subtitle", Messages.tag("xp", settings().xpMajor())));
            session.broadcast(messages.prefixed("objective.core-open", Messages.tag("name", point.site.name()),
                    Messages.tag("xp", settings().xpMajor())));
            session.playSound(Sound.BLOCK_END_PORTAL_SPAWN, 0.5f, 1.4f);
        } else {
            session.broadcast(messages.prefixed("objective.node-open", Messages.tag("name", point.site.name())));
        }
    }

    private void capture(Point point, long now) {
        int[] inside = {0, 0};
        List<Player> present = new ArrayList<>();
        for (Player player : session.alivePlayers()) {
            int team = match.teamIndex(player.getUniqueId());
            if (team >= 0 && session.isInPlay(player) && point.contains(player.getLocation())) {
                inside[team]++;
                present.add(player);
            }
        }
        point.contested = inside[0] > 0 && inside[1] > 0;
        if (!point.contested) {
            int team = inside[0] > 0 ? 0 : inside[1] > 0 ? 1 : -1;
            if (team < 0) {
                point.progressTicks = Math.max(0, point.progressTicks - 20);
                if (point.progressTicks == 0) {
                    point.capturer = -1;
                }
            } else if (point.capturer < 0 || point.capturer == team) {
                point.capturer = team;
                point.progressTicks += 20;
            } else {
                // The other team's progress drains first.
                point.progressTicks = Math.max(0, point.progressTicks - 20);
                if (point.progressTicks == 0) {
                    point.capturer = team;
                }
            }
        }
        for (Player player : present) {
            player.sendActionBar(status(point));
        }
        if (!point.contested && point.capturer >= 0 && point.progressTicks >= point.captureTicks()) {
            captured(point, point.capturer, now);
        }
    }

    private void captured(Point point, int team, long now) {
        boolean major = point.site.major();
        int xp = major ? settings().xpMajor() : settings().xpNode();
        MatchTeam winner = match.teams().get(team);
        point.open = false;
        point.warned = false;
        point.capturer = -1;
        point.progressTicks = 0;
        point.contested = false;
        point.opensAt = now + (major ? settings().majorIntervalTicks() : settings().nodeRespawnTicks());
        session.broadcast(messages.prefixed(major ? "objective.core-captured" : "objective.node-captured",
                Messages.tag("team", winner.displayName()), Messages.tag("name", point.site.name()),
                Messages.tag("xp", xp)));
        if (major) {
            match.title(messages.get("objective.core-captured-title", Messages.tag("team", winner.displayName())),
                    messages.get("objective.core-captured-subtitle", Messages.tag("xp", xp)));
            session.playSound(Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
        } else {
            session.playSound(Sound.BLOCK_BEACON_ACTIVATE, 0.6f, 1.5f);
        }
        buff(team, major);
        progress.teamXp(team, xp);
    }

    private void buff(int team, boolean major) {
        int ticks = major ? settings().majorBuffTicks() : settings().nodeBuffTicks();
        if (ticks <= 0) {
            return;
        }
        for (UUID member : match.members(team)) {
            Player player = Bukkit.getPlayer(member);
            if (player == null || !session.isInPlay(player)) {
                continue;
            }
            buffed.add(member);
            if (major) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, ticks, 0, false, true, true));
                player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, ticks, 0, false, true, true));
            } else {
                player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, ticks, 0, false, true, true));
            }
        }
    }

    // ------------------------------------------------------------------ looks

    private Component status(Point point) {
        if (!point.open) {
            return messages.get("objective.status-closed",
                    Messages.tag("time", Format.clock((Math.max(0, point.opensAt - match.halfTicks()) + 19) / 20)));
        }
        if (point.contested) {
            return messages.get("objective.status-contested");
        }
        if (point.capturer < 0) {
            return messages.get("objective.status-neutral");
        }
        return messages.get("objective.status-capturing",
                Messages.tag("team", match.teams().get(point.capturer).displayName()),
                Messages.tag("percent", Math.round(point.fraction() * 100)));
    }

    private Color glow(Point point) {
        if (!point.open) {
            return Color.GRAY;
        }
        if (point.contested) {
            return Color.RED;
        }
        return point.capturer < 0 ? Color.WHITE : match.teams().get(point.capturer).rgb();
    }

    /**
     * Spawns or refreshes the label and the block: at the start of the fight, and again later if their chunk unloaded
     * them (only while the chunk is loaded again, a lost label never loads a chunk by itself).
     */
    private void show(Point point, boolean spawn) {
        World world = point.at.getWorld();
        if (world == null || !spawn && !world.isChunkLoaded(point.at.getBlockX() >> 4, point.at.getBlockZ() >> 4)) {
            return;
        }
        Component text = messages.get(point.site.major() ? "objective.label-core" : "objective.label-node",
                Messages.tag("name", point.site.name()), Messages.tag("status", status(point)));
        try {
            TextDisplay label = point.label;
            if (label == null || !label.isValid()) {
                label = world.spawn(point.at.clone().add(0, point.site.major() ? 3.4 : 2.6, 0), TextDisplay.class,
                        display -> {
                            display.setPersistent(false);
                            display.setBillboard(Display.Billboard.CENTER);
                            display.setShadowed(true);
                            display.setBrightness(new Display.Brightness(15, 15));
                        });
                session.track(label);
                point.label = label;
            }
            label.text(text);
            BlockDisplay body = point.body;
            if (body == null || !body.isValid()) {
                float size = point.site.major() ? 1.4f : 0.9f;
                body = world.spawn(point.at.clone().add(0, 0.3, 0), BlockDisplay.class, display -> {
                    display.setPersistent(false);
                    display.setBlock((point.site.major() ? Material.BEACON : Material.LODESTONE).createBlockData());
                    display.setTransformation(new Transformation(new Vector3f(-size / 2, 0, -size / 2),
                            new AxisAngle4f(), new Vector3f(size, size, size), new AxisAngle4f()));
                    display.setBrightness(new Display.Brightness(15, 15));
                    display.setGlowing(true);
                });
                session.track(body);
                point.body = body;
            }
            body.setGlowColorOverride(glow(point));
        } catch (RuntimeException e) {
            session.plugin().getLogger().log(Level.FINE, "Could not show objective " + point.site.id(), e);
        }
    }

    private void ring(Point point) {
        World world = point.at.getWorld();
        if (world == null) {
            return;
        }
        Particle.DustOptions dust = new Particle.DustOptions(glow(point), 1.4f);
        int steps = Math.max(12, point.site.radius() * 6);
        for (int i = 0; i < steps; i++) {
            double angle = Math.PI * 2 * i / steps;
            world.spawnParticle(Particle.DUST, point.at.getX() + Math.cos(angle) * point.site.radius(),
                    point.at.getY() + 0.2, point.at.getZ() + Math.sin(angle) * point.site.radius(), 1, 0, 0, 0, 0,
                    dust);
        }
    }

    private void remove(Point point) {
        if (point.label != null) {
            point.label.remove();
            session.untrack(point.label);
            point.label = null;
        }
        if (point.body != null) {
            point.body.remove();
            session.untrack(point.body);
            point.body = null;
        }
    }

    // ------------------------------------------------------------------ queries

    List<Point> points() {
        return List.copyOf(points);
    }

    /** The sidebar line about the 오버클럭 코어 (when it comes or who takes it), or null without one. */
    @Nullable Component hudLine() {
        for (Point point : points) {
            if (point.site.major()) {
                return messages.get("hud.objective", Messages.tag("name", point.site.name()),
                        Messages.tag("status", status(point)));
            }
        }
        return null;
    }
}
