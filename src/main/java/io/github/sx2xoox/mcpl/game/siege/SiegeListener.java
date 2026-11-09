package io.github.sx2xoox.mcpl.game.siege;

import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.game.siege.tower.Tower;
import io.github.sx2xoox.mcpl.game.superpower.runtime.MatchContext;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.jspecify.annotations.Nullable;

/**
 * Bukkit events of one 공성전 match: hits on structure hitboxes become structure damage (the hitbox itself is never
 * hurt), spawn protection, the hits that count for assists, and health changes for the name plates and the floating
 * numbers.
 */
final class SiegeListener implements Listener {

    private final SiegeMatch match;
    private final GameSession session;

    /** The match's named creature whose death event is running (see {@link #onCreatureDying}). */
    private @Nullable LivingEntity dyingCreature;

    SiegeListener(SiegeMatch match) {
        this.match = match;
        this.session = match.session();
    }

    /** Melee and ability damage on a tower: the event is cancelled, the tower takes the final damage. */
    /** Fighters' gear never wears out in 공성전 (rounds are long; a broken toy armour or weapon is no fun). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGearWear(PlayerItemDamageEvent event) {
        if (session.isParticipant(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTowerDamage(EntityDamageEvent event) {
        Tower tower = match.towerOf(event.getEntity());
        if (tower == null) {
            return;
        }
        if (event.getCause() == EntityDamageEvent.DamageCause.SUFFOCATION
                || event.getCause() == EntityDamageEvent.DamageCause.CRAMMING) {
            event.setCancelled(true); // the hitbox stands in its own walls: no structure damage from them
            return;
        }
        double amount = event.getFinalDamage();
        event.setCancelled(true);
        Player attacker = session.combat().resolveAttacker(event);
        match.hitTower(tower, attacker != null && session.isParticipant(attacker) ? attacker : null, amount, event);
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
     * never for viewers from whom the player is concealed; the attacker hears their hit land ({@link HitFeel}).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHealthLost(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && session.isParticipant(player)) {
            match.healthChanged(player);
            MatchContext ctx = match.abilities().context();
            match.damageNumbers().show(player, event.getFinalDamage(),
                    DamageNumbers.Kind.ofHit(ctx.isAbilityDamage(), ctx.basicHitAccent()),
                    viewer -> !ctx.isConcealedFrom(player, viewer));
            Player attacker = session.combat().resolveAttacker(event);
            if (attacker != null && !attacker.equals(player) && session.isParticipant(attacker)) {
                // vanilla shows the victim's hurt; the attacker hears their own "딱"
                match.hitFeel().fighter(attacker, match.hitFeel().strength(event));
            }
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

    /**
     * The server logs the death of every named mob ("Named entity … died") after the death event: the match's minions
     * and monsters die by the hundred, so they lose their name tag once every other listener has read it. Whether the
     * dead one is the match's is asked first (the systems stop tracking their creatures during the event).
     *
     * <p>The name tag goes with the name: the client shows a name tag whenever {@code CustomNameVisible} is set, and
     * without a custom name it shows the entity type's name, so a minion would say "스켈레톤" through its death
     * animation.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCreatureDying(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        dyingCreature = !(dead instanceof Player) && dead.customName() != null && session.isMatchEntity(dead)
                ? dead : null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreatureDied(EntityDeathEvent event) {
        LivingEntity dead = dyingCreature;
        dyingCreature = null;
        if (dead != null && dead.equals(event.getEntity())) {
            dead.setCustomNameVisible(false);
            dead.customName(null);
        }
    }
}
