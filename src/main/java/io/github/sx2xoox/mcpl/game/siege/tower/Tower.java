package io.github.sx2xoox.mcpl.game.siege.tower;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.map.siege.SiegeSites;
import io.github.sx2xoox.mcpl.core.ui.ManagedBossBar;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.jspecify.annotations.Nullable;

/**
 * One tower of a 공성전 half: its rules (health, shield, who hurt it, defence timer) and its parts: an invisible,
 * immovable {@link Mannequin} as the hitbox (melee and ability damage land on it and are turned into tower damage by
 * the game), the {@link TowerLook.Visual} and a boss bar. Created when a half starts, {@link #dispose() disposed}
 * when it ends.
 */
public final class Tower {

    private final SiegeSites.Tower site;
    private final int index;
    private final Location base;
    private final double maxHealth;
    private double health;
    private boolean shielded;
    private boolean destroyed;
    private final Map<UUID, Double> damageBy = new HashMap<>();
    private long lastDamageTick = Long.MIN_VALUE / 2;
    private long attackableSince = -1;
    private long lastWarningTick = Long.MIN_VALUE / 2;
    /** Health quarter last announced (4 = full). */
    private int announcedQuarter = 4;
    private @Nullable Mannequin hitbox;
    private TowerLook.@Nullable Visual visual;
    private @Nullable ManagedBossBar bar;

    public Tower(SiegeSites.Tower site, int index, Location base, double maxHealth) {
        this.site = site;
        this.index = index;
        this.base = base.clone();
        this.maxHealth = maxHealth;
        this.health = maxHealth;
    }

    // ------------------------------------------------------------------ parts

    /** Spawns the hitbox (tracked by the session) and the visual. */
    public void spawn(GameSession session, TowerLook look, net.kyori.adventure.text.format.TextColor color,
                      double hitboxScale) {
        World world = base.getWorld();
        if (world == null) {
            return;
        }
        try {
            visual = look.spawn(base.clone(), site.id(), color);
        } catch (RuntimeException e) {
            session.plugin().getLogger().log(Level.WARNING, "The tower look failed to spawn " + site.id(), e);
        }
        try {
            Mannequin body = world.spawn(base.clone(), Mannequin.class, spawned -> {
                spawned.setPersistent(false);
                spawned.setInvisible(true);
                spawned.setImmovable(true);
                spawned.setGravity(false);
                spawned.setSilent(true);
                spawned.setDescription(null);
                AttributeInstance scale = spawned.getAttribute(Attribute.SCALE);
                if (scale != null) {
                    scale.setBaseValue(hitboxScale);
                }
            });
            hitbox = session.track(body);
        } catch (RuntimeException e) {
            session.plugin().getLogger().log(Level.WARNING, "Could not spawn the hitbox of tower " + site.id(), e);
        }
    }

    /** Puts the hitbox back where it belongs (abilities may push or pull it) and keeps it at full health. */
    public void anchor() {
        Mannequin body = hitbox;
        if (body == null || !body.isValid()) {
            return;
        }
        if (body.getLocation().distanceSquared(base) > 0.01) {
            body.teleport(base.clone());
        }
        body.setFireTicks(0);
    }

    public void bar(ManagedBossBar bar) {
        this.bar = bar;
    }

    public @Nullable ManagedBossBar bar() {
        return bar;
    }

    /** Removes the hitbox and the visual (the boss bar belongs to the session). */
    public void dispose() {
        Mannequin body = hitbox;
        hitbox = null;
        if (body != null) {
            body.remove();
        }
        TowerLook.Visual look = visual;
        visual = null;
        if (look != null) {
            look.remove();
        }
        if (bar != null) {
            bar.hideAll();
        }
    }

    // ------------------------------------------------------------------ rules

    /**
     * Takes {@code amount} damage from {@code attacker}, reduced by {@code reduction} (0..1).
     *
     * @return the health actually lost
     */
    public double damage(double amount, double reduction, @Nullable UUID attacker, long tick) {
        if (destroyed || shielded || !(amount > 0)) {
            return 0;
        }
        double dealt = Math.min(health, amount * (1 - Math.max(0, Math.min(1, reduction))));
        health -= dealt;
        lastDamageTick = tick;
        if (attacker != null) {
            damageBy.merge(attacker, dealt, Double::sum);
        }
        if (visual != null) {
            visual.hit();
            visual.health(fraction());
        }
        if (health <= 1.0E-6) {
            health = 0;
            destroyed = true;
            if (visual != null) {
                visual.destroyed();
            }
            Mannequin body = hitbox;
            hitbox = null;
            if (body != null) {
                body.remove();
            }
        }
        return dealt;
    }

    /** Heals {@code amount} health points (never above the maximum, never a destroyed tower). */
    public void heal(double amount) {
        if (destroyed || !(amount > 0)) {
            return;
        }
        health = Math.min(maxHealth, health + amount);
        if (visual != null) {
            visual.health(fraction());
        }
        announcedQuarter = Math.max(announcedQuarter, quarter());
    }

    public void shielded(boolean shielded) {
        if (this.shielded == shielded || destroyed) {
            return;
        }
        this.shielded = shielded;
        if (visual != null) {
            visual.shielded(shielded);
        }
    }

    /** Starts (or restarts) the defence timer: the tower became the one the attackers can hit. */
    public void attackableFrom(long tick) {
        attackableSince = tick;
    }

    public long attackableSince() {
        return attackableSince;
    }

    /**
     * The next health quarter crossed downwards (3 = 75%, 2 = 50%, 1 = 25%) since the last call, or 0.
     */
    public int takeQuarterCrossed() {
        int now = quarter();
        if (now < announcedQuarter && !destroyed) {
            announcedQuarter = now;
            return now;
        }
        return 0;
    }

    private int quarter() {
        return (int) Math.ceil(fraction() * 4 - 1.0E-9);
    }

    /** True if a warning may be sent now (and remembers it). */
    public boolean warn(long tick, int cooldown) {
        if (tick - lastWarningTick < cooldown) {
            return false;
        }
        lastWarningTick = tick;
        return true;
    }

    /** The attacker who dealt the most damage to this tower, or null. */
    public @Nullable UUID topDamager() {
        UUID best = null;
        double most = 0;
        for (Map.Entry<UUID, Double> entry : damageBy.entrySet()) {
            if (entry.getValue() > most) {
                most = entry.getValue();
                best = entry.getKey();
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ queries

    public SiegeSites.Tower site() {
        return site;
    }

    public String id() {
        return site.id();
    }

    public String name() {
        return site.name();
    }

    /** 0 front, 1 middle, 2 core. */
    public int index() {
        return index;
    }

    public Location base() {
        return base.clone();
    }

    public double health() {
        return health;
    }

    public double maxHealth() {
        return maxHealth;
    }

    public double fraction() {
        return maxHealth <= 0 ? 0 : health / maxHealth;
    }

    public boolean isShielded() {
        return shielded;
    }

    public boolean isDestroyed() {
        return destroyed;
    }

    public long lastDamageTick() {
        return lastDamageTick;
    }

    public @Nullable Mannequin hitbox() {
        return hitbox;
    }

    /** True for this tower's live hitbox. */
    public boolean isHitbox(@Nullable Entity entity) {
        Mannequin body = hitbox;
        return entity != null && body != null && body.getUniqueId().equals(entity.getUniqueId());
    }

    /** Horizontal distance check against the tower's radius (the defence zone). */
    public boolean isNear(Location location) {
        World world = base.getWorld();
        if (world == null || !world.equals(location.getWorld())) {
            return false;
        }
        double dx = location.getX() - base.getX();
        double dz = location.getZ() - base.getZ();
        double dy = location.getY() - base.getY();
        double r = site.radius();
        return dx * dx + dz * dz <= r * r && Math.abs(dy) <= Math.max(6, r);
    }
}
