package io.github.sx2xoox.mcpl.game.siege;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.ui.ManagedBossBar;
import io.github.sx2xoox.mcpl.game.siege.tower.Tower;
import org.bukkit.Location;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * Who sees which tower's boss bar: only players the tower matters to right now, so the top of the screen holds the
 * timer and at most the tower in front of the player. A tower's bar shows to a viewer who is near it
 * ({@link SiegeHudSettings#towerBarRange()}), looks at its hitbox ({@link SiegeHudSettings#towerBarLookRange()}) or hit
 * it lately ({@link SiegeHudSettings#towerBarHitTicks()}); never for a destroyed tower.
 */
final class TowerBars {

    private final SiegeMatch match;
    private final GameSession session;
    private final SiegeHudSettings settings;
    /** Session tick of every player's last hit, per tower (the towers are new objects every half). */
    private final Map<Tower, Map<UUID, Long>> hits = new IdentityHashMap<>();

    TowerBars(SiegeMatch match, SiegeHudSettings settings) {
        this.match = match;
        this.session = match.session();
        this.settings = settings;
    }

    /** {@code player} hit {@code tower} just now. */
    void hit(Tower tower, UUID player) {
        hits.computeIfAbsent(tower, key -> new HashMap<>()).put(player, session.tick());
    }

    /** Shows and hides every tower's bar by the rules above. */
    void update() {
        hits.keySet().removeIf(tower -> !match.towers().contains(tower));
        Set<UUID> viewers = new HashSet<>();
        for (Player viewer : session.viewers()) {
            viewers.add(viewer.getUniqueId());
        }
        for (Tower tower : match.towers()) {
            ManagedBossBar bar = tower.bar();
            if (bar == null) {
                continue;
            }
            for (Player viewer : session.viewers()) {
                if (!tower.isDestroyed() && wants(viewer, tower)) {
                    bar.show(viewer);
                } else {
                    bar.hide(viewer);
                }
            }
            for (UUID gone : bar.viewers()) {
                if (!viewers.contains(gone)) {
                    bar.forget(gone);
                }
            }
        }
    }

    /** Whether {@code viewer} should see {@code tower}'s bar now. */
    boolean wants(Player viewer, Tower tower) {
        Location base = tower.base();
        Location eye = viewer.getEyeLocation();
        double distance = base.getWorld() != null && base.getWorld().equals(eye.getWorld())
                ? Math.min(eye.distance(base), viewer.getLocation().distance(base)) : Double.MAX_VALUE;
        Long last = hits.getOrDefault(tower, Map.of()).get(viewer.getUniqueId());
        long sinceHit = last == null ? Long.MAX_VALUE : session.tick() - last;
        return visible(distance, distance <= settings.towerBarLookRange() && looksAt(viewer, tower), sinceHit,
                settings);
    }

    /** The rule itself: near, looking at it, or hit it lately. */
    static boolean visible(double distance, boolean looking, long ticksSinceHit, SiegeHudSettings settings) {
        return distance <= settings.towerBarRange() || looking || ticksSinceHit <= settings.towerBarHitTicks();
    }

    /** True if the viewer's line of sight passes through the tower's hitbox within the look range. */
    boolean looksAt(Player viewer, Tower tower) {
        Mannequin body = tower.hitbox();
        if (body == null || !body.isValid() || !body.getWorld().equals(viewer.getWorld())) {
            return false;
        }
        BoundingBox box = body.getBoundingBox().clone().expand(0.5);
        Location eye = viewer.getEyeLocation();
        Vector direction = eye.getDirection();
        if (direction.lengthSquared() < 1.0E-6) {
            return false;
        }
        RayTraceResult hit = box.rayTrace(eye.toVector(), direction, settings.towerBarLookRange());
        return hit != null;
    }
}
