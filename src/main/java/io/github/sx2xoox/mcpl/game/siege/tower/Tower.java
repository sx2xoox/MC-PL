package io.github.sx2xoox.mcpl.game.siege.tower;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import io.github.sx2xoox.mcpl.game.siege.OverheadBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.jspecify.annotations.Nullable;

/**
 * One structure of a 공성전 round, a lane tower or a core: its rules (health, shield, who hurt it, warnings) and its
 * parts: an invisible, immovable {@link Mannequin} as the hitbox (melee and ability damage land on it and are turned
 * into structure damage by the game), the {@link TowerLook.Visual} and an {@link OverheadBar}. Created when a round
 * starts, {@link #dispose() disposed} when it ends.
 */
public final class Tower {

    /** What the structure is. */
    public enum Kind {
        /** A lane's outer tower (stands first). */
        OUTER(TowerLook.TOWER),
        /** A lane's inner tower: shielded while the outer tower of its lane stands. */
        INNER(TowerLook.TOWER),
        /** The core: shielded while an inner tower of its side stands; destroying it wins the round. */
        CORE(TowerLook.CORE);

        private final String lookId;

        Kind(String lookId) {
            this.lookId = lookId;
        }

        public String lookId() {
            return lookId;
        }
    }

    /** Size of a structure's health bar (a creature's is 1). */
    public static final float BAR_SCALE = 1.5f;
    /** View range of a structure's health bar (× 64 blocks). */
    public static final float BAR_VIEW_RANGE = 0.75f;

    private final LaneSites.Structure site;
    private final LaneSites.Side owner;
    private final LaneSites.@Nullable Lane lane;
    private final Kind kind;
    private final Location base;
    private final double maxHealth;
    private double health;
    private boolean shielded;
    private boolean destroyed;
    private final Map<UUID, Double> damageBy = new HashMap<>();
    private long lastDamageTick = Long.MIN_VALUE / 2;
    private long lastWarningTick = Long.MIN_VALUE / 2;
    private long nextAttackTick;
    /** Health quarter last announced (4 = full). */
    private int announcedQuarter = 4;
    private @Nullable Mannequin hitbox;
    private TowerLook.@Nullable Visual visual;
    private @Nullable OverheadBar bar;

    public Tower(LaneSites.Structure site, LaneSites.Side owner, LaneSites.@Nullable Lane lane, Kind kind,
                 Location base, double maxHealth) {
        this.site = site;
        this.owner = owner;
        this.lane = lane;
        this.kind = kind;
        this.base = base.clone();
        this.maxHealth = maxHealth;
        this.health = maxHealth;
    }

    // ------------------------------------------------------------------ parts

    /** Spawns the visual, the hitbox (tracked by the session) and the overhead bar named {@code barName}. */
    public void spawn(GameSession session, TowerLook look, TextColor color, double hitboxScale, Component barName) {
        World world = base.getWorld();
        if (world == null) {
            return;
        }
        try {
            visual = look.spawn(base.clone(), kind.lookId(), color);
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
        // At eye level in the building, drawn through it, bigger than a creature's, seen within ~48 blocks: a bar
        // over the top of a toy tower is out of sight from anywhere near it.
        bar = OverheadBar.at(session, base.clone().add(0, look.barHeight(kind.lookId()), 0), barName, color,
                BAR_VIEW_RANGE).style(BAR_SCALE, true);
        bar.health(health, maxHealth);
        bar.shielded(shielded);
    }

    /** Puts the hitbox back where it belongs (abilities may push or pull it) and keeps it from burning. */
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

    public @Nullable OverheadBar bar() {
        return bar;
    }

    /** Removes the hitbox, the visual and the bar. */
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
        OverheadBar overhead = bar;
        bar = null;
        if (overhead != null) {
            overhead.remove();
        }
    }

    // ------------------------------------------------------------------ rules

    /**
     * Takes {@code amount} damage from {@code attacker} (null: a minion or the world).
     *
     * @return the health actually lost
     */
    public double damage(double amount, @Nullable UUID attacker, long tick) {
        if (destroyed || shielded || !(amount > 0)) {
            return 0;
        }
        double dealt = Math.min(health, amount);
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
            OverheadBar overhead = bar;
            bar = null;
            if (overhead != null) {
                overhead.remove();
            }
            return dealt;
        }
        if (bar != null) {
            bar.health(health, maxHealth);
        }
        return dealt;
    }

    /** Heals {@code amount} health points (never above the maximum, never a destroyed structure). */
    public void heal(double amount) {
        if (destroyed || !(amount > 0)) {
            return;
        }
        health = Math.min(maxHealth, health + amount);
        if (visual != null) {
            visual.health(fraction());
        }
        if (bar != null) {
            bar.health(health, maxHealth);
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
        if (bar != null) {
            bar.shielded(shielded);
        }
    }

    /** The next health quarter crossed downwards (3 = 75%, 2 = 50%, 1 = 25%) since the last call, or 0. */
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

    /** True if the structure may shoot at {@code tick}. */
    public boolean canAttack(long tick) {
        return tick >= nextAttackTick;
    }

    /** The structure shot at {@code tick}: the next shot comes {@code cooldown} ticks later. */
    public void attacked(long tick, int cooldown) {
        nextAttackTick = tick + cooldown;
    }

    /** The attacker who dealt the most damage to this structure, or null. */
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

    public LaneSites.Structure site() {
        return site;
    }

    public String id() {
        return site.id();
    }

    public String name() {
        return site.name();
    }

    /** The side the structure belongs to (its enemies destroy it). */
    public LaneSites.Side owner() {
        return owner;
    }

    /** The lane of a tower, null for a core. */
    public LaneSites.@Nullable Lane lane() {
        return lane;
    }

    public Kind kind() {
        return kind;
    }

    public boolean isCore() {
        return kind == Kind.CORE;
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

    /** True for this structure's live hitbox. */
    public boolean isHitbox(@Nullable Entity entity) {
        Mannequin body = hitbox;
        return entity != null && body != null && body.getUniqueId().equals(entity.getUniqueId());
    }

    /** Horizontal distance check against the structure's radius. */
    public boolean isNear(Location location) {
        return isWithin(location, site.radius());
    }

    /** True if {@code location} is within {@code range} blocks horizontally (and not far above or below). */
    public boolean isWithin(Location location, double range) {
        World world = base.getWorld();
        if (world == null || !world.equals(location.getWorld())) {
            return false;
        }
        double dx = location.getX() - base.getX();
        double dz = location.getZ() - base.getZ();
        double dy = location.getY() - base.getY();
        return dx * dx + dz * dz <= range * range && Math.abs(dy) <= Math.max(8, range);
    }
}
