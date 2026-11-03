package io.github.sx2xoox.mcpl.game.siege;

import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.game.siege.tower.Tower;
import io.github.sx2xoox.mcpl.game.superpower.runtime.MatchContext;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;

/**
 * Bukkit events of one 공성전 match: hits on structure hitboxes become structure damage (the hitbox itself is never
 * hurt), spawn protection, the hits that count for assists, and health changes for the name plates and the floating
 * numbers.
 */
final class SiegeListener implements Listener {

    private final SiegeMatch match;
    private final GameSession session;

    SiegeListener(SiegeMatch match) {
        this.match = match;
        this.session = match.session();
    }

    /** Melee and ability damage on a tower: the event is cancelled, the tower takes the final damage. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTowerDamage(EntityDamageEvent event) {
        Tower tower = match.towerOf(event.getEntity());
        if (tower == null) {
            return;
        }
        double amount = event.getFinalDamage();
        event.setCancelled(true);
        Player attacker = session.combat().resolveAttacker(event);
        match.hitTower(tower, attacker != null && session.isParticipant(attacker) ? attacker : null, amount);
    }

    /** Freshly respawned players take no damage; attacking ends the protection. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onProtectedDamage(EntityDamageEvent event) {
        Player attacker = session.combat().resolveAttacker(event);
        if (attacker != null && match.isProtected(attacker) && !attacker.equals(event.getEntity())) {
            match.attacked(attacker);
        }
        if (event.getEntity() instanceof Player victim && match.isProtected(victim)) {
            event.setCancelled(true);
        }
    }

    /** Landed hits between players, for assists. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim) || !session.isParticipant(victim)
                || match.phase() != SiegeMatch.Phase.FIGHT) {
            return;
        }
        Player attacker = session.combat().resolveAttacker(event);
        if (attacker != null && session.isParticipant(attacker)) {
            match.score().recordHit(attacker.getUniqueId(), victim.getUniqueId(), session.tick());
        }
    }

    /**
     * Health changes redraw the player's name plate; the damage pops up as a number (yellow for ability damage),
     * never for viewers from whom the player is concealed.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHealthLost(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && session.isParticipant(player)) {
            match.healthChanged(player);
            MatchContext ctx = match.abilities().context();
            match.damageNumbers().show(player, event.getFinalDamage(), ctx.isAbilityDamage()
                    ? DamageNumbers.Kind.ABILITY : DamageNumbers.Kind.NORMAL,
                    viewer -> !ctx.isConcealedFrom(player, viewer));
        }
    }

    /** Healing (not the slow natural regeneration) pops up as a green number. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHealthGained(EntityRegainHealthEvent event) {
        if (event.getEntity() instanceof Player player && session.isParticipant(player)) {
            match.healthChanged(player);
            if (event.getRegainReason() != EntityRegainHealthEvent.RegainReason.SATIATED) {
                MatchContext ctx = match.abilities().context();
                match.damageNumbers().show(player, event.getAmount(), DamageNumbers.Kind.HEAL,
                        viewer -> !ctx.isConcealedFrom(player, viewer));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHitboxDeath(EntityDeathEvent event) {
        if (match.towerOf(event.getEntity()) != null) {
            event.getDrops().clear();
            event.setDroppedExp(0);
        }
    }
}
