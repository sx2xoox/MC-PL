package io.github.sx2xoox.mcpl.core.combat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EvokerFangs;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.jspecify.annotations.Nullable;

/**
 * Who hurt whom, for kill credit. The hub records every hit between participants automatically while a match runs,
 * so a player who falls, drowns, hits the void or the border shortly after being hit (within
 * {@link #DEFAULT_WINDOW_TICKS}) is credited to the last attacker.
 *
 * <p>Attackers are resolved from players, projectile shooters (arrows, fireballs, tridents, potions...), primed TNT
 * sources, area effect cloud sources, evoker fang owners and any entity registered with
 * {@link #registerOwner(Entity, Player)} (summons, custom projectiles, display-based hitboxes...). Tamed animals are
 * not resolved to their owner unless registered: a real pet is not part of the match.
 *
 * <p>All state is cleared when a match ends.
 */
public final class CombatTracker {

    /** Ten seconds. */
    public static final int DEFAULT_WINDOW_TICKS = 200;

    private static final int MAX_DEPTH = 4;

    private record Hit(UUID attacker, int tick) {
    }

    private final Map<UUID, Hit> lastHits = new HashMap<>();
    private final Map<UUID, UUID> owners = new HashMap<>();

    // ------------------------------------------------------------------ ownership

    /** Credits everything {@code entity} does to {@code owner} (until {@link #unregisterOwner} or match end). */
    public void registerOwner(Entity entity, Player owner) {
        owners.put(entity.getUniqueId(), owner.getUniqueId());
    }

    /**
     * Credits everything {@code entity} does to the participant {@code owner} by id (also a mode's bot, which no
     * {@link #resolveAttacker} returns: read it with {@link #ownerOf}).
     */
    public void registerOwner(Entity entity, UUID owner) {
        owners.put(entity.getUniqueId(), owner);
    }

    public void unregisterOwner(Entity entity) {
        owners.remove(entity.getUniqueId());
    }

    /** The registered owner of an entity, or null. */
    public @Nullable UUID ownerOf(Entity entity) {
        return owners.get(entity.getUniqueId());
    }

    // ------------------------------------------------------------------ resolution

    /**
     * The player responsible for {@code damager}: the player itself, a projectile's shooter, a TNT source, a cloud's
     * source, a fang owner or a registered owner. Null if no online player is responsible.
     */
    public @Nullable Player resolveAttacker(@Nullable Entity damager) {
        return resolve(damager, 0);
    }

    /**
     * The player responsible for a damage event: the causing entity of its {@link DamageSource} if any, else the
     * direct entity or the {@link EntityDamageByEntityEvent#getDamager() damager}.
     */
    public @Nullable Player resolveAttacker(EntityDamageEvent event) {
        DamageSource source = event.getDamageSource();
        Player attacker = resolve(source.getCausingEntity(), 0);
        if (attacker == null) {
            attacker = resolve(source.getDirectEntity(), 0);
        }
        if (attacker == null && event instanceof EntityDamageByEntityEvent byEntity) {
            attacker = resolve(byEntity.getDamager(), 0);
        }
        return attacker;
    }

    private @Nullable Player resolve(@Nullable Entity entity, int depth) {
        if (entity == null || depth > MAX_DEPTH) {
            return null;
        }
        if (entity instanceof Player player) {
            return player;
        }
        UUID owner = owners.get(entity.getUniqueId());
        if (owner != null) {
            return Bukkit.getPlayer(owner);
        }
        if (entity instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            return shooter instanceof Entity shooterEntity ? resolve(shooterEntity, depth + 1) : null;
        }
        if (entity instanceof TNTPrimed tnt) {
            return resolve(tnt.getSource(), depth + 1);
        }
        if (entity instanceof AreaEffectCloud cloud) {
            ProjectileSource source = cloud.getSource();
            return source instanceof Entity sourceEntity ? resolve(sourceEntity, depth + 1) : null;
        }
        if (entity instanceof EvokerFangs fangs) {
            return resolve(fangs.getOwner(), depth + 1);
        }
        // Tamed animals count only when registered above (summons): a real pet that followed its owner into the arena
        // is not part of the match and must not earn its owner hits or kills.
        return null;
    }

    // ------------------------------------------------------------------ hits

    /** Remembers that {@code attacker} hit {@code victim} now. Self-hits are ignored. */
    public void recordHit(Player victim, Player attacker) {
        if (victim.getUniqueId().equals(attacker.getUniqueId())) {
            return;
        }
        lastHits.put(victim.getUniqueId(), new Hit(attacker.getUniqueId(), Bukkit.getCurrentTick()));
    }

    /**
     * Remembers that {@code attacker} hit {@code victim} now, by id: for combatants that are not players (a mode's
     * bots or dummies), on either side. Self-hits are ignored.
     */
    public void recordHit(UUID victim, UUID attacker) {
        if (!victim.equals(attacker)) {
            lastHits.put(victim, new Hit(attacker, Bukkit.getCurrentTick()));
        }
    }

    /** The last player who hit {@code victim} within {@link #DEFAULT_WINDOW_TICKS}, if still online. */
    public @Nullable Player lastAttacker(Player victim) {
        return lastAttacker(victim.getUniqueId(), DEFAULT_WINDOW_TICKS);
    }

    /** The last player who hit {@code victim} within {@code windowTicks}, if still online. */
    public @Nullable Player lastAttacker(UUID victim, int windowTicks) {
        UUID id = lastAttackerId(victim, windowTicks);
        return id == null ? null : Bukkit.getPlayer(id);
    }

    /** Like {@link #lastAttacker(UUID, int)} but also returns offline attackers. */
    public @Nullable UUID lastAttackerId(UUID victim, int windowTicks) {
        Hit hit = lastHits.get(victim);
        if (hit == null || Bukkit.getCurrentTick() - hit.tick() > windowTicks) {
            return null;
        }
        return hit.attacker();
    }

    /** Forgets the hits on {@code victim} (after their death was credited). */
    public void forget(UUID victim) {
        lastHits.remove(victim);
    }

    /** Forgets all hits and owners. Called when a match ends. */
    public void clear() {
        lastHits.clear();
        owners.clear();
    }
}
