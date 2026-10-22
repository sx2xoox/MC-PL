package io.github.sx2xoox.mcpl.game.superpower.runtime;

import java.util.UUID;

import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.menu.ChoiceMenu;
import io.github.sx2xoox.mcpl.game.superpower.ui.AbilityItems;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Bukkit events of one superpower match (registered through the session, so it is unregistered when the match
 * stops): ability input, ability-item protection, hook routing, the ultimate gauge, summon behaviour and the block
 * safety of everything abilities create.
 */
final class MatchListener implements Listener {

    private final SuperpowerMatch match;
    private final GameSession session;
    private final AbilityItems items;
    private final AbilityGuard guard;

    MatchListener(SuperpowerMatch match) {
        this.match = match;
        this.session = match.session();
        this.items = match.items();
        this.guard = match.guard();
    }

    private MatchContext ctx() {
        return match.context();
    }

    private boolean fighting() {
        return match.phase() == MatchPhase.FIGHT;
    }

    // =================================================================== damage

    /**
     * Before the fight every participant is invulnerable. During the fight summons are never hurt by their owner or
     * the owner's allies, and never hurt them.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamageCheck(EntityDamageEvent event) {
        Entity victim = event.getEntity();
        if (!fighting()) {
            if (victim instanceof Player player && session.isParticipant(player)) {
                event.setCancelled(true);
                if (event.getDamageSource().getDamageType() == DamageType.OUT_OF_WORLD && session.isInPlay(player)) {
                    player.setFallDistance(0f);
                    player.teleport(session.safeSpot(session.arena().center()));
                }
            }
            return;
        }
        MatchContext ctx = ctx();
        if (ctx.summonOwner(victim) != null) {
            Player attacker = session.combat().resolveAttacker(event);
            if (attacker != null && session.isParticipant(attacker) && !ctx.isEnemy(attacker, victim)) {
                event.setCancelled(true);
                return;
            }
        }
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            UUID owner = ctx.summonOwner(byEntity.getDamager());
            Entity causing = event.getDamageSource().getCausingEntity();
            if (owner == null && causing != null) {
                owner = ctx.summonOwner(causing);
            }
            if (owner == null) {
                return;
            }
            boolean matchEntity = victim instanceof Player player ? session.isParticipant(player)
                    : ctx.summonOwner(victim) != null;
            Player ownerPlayer = Bukkit.getPlayer(owner);
            if (matchEntity && (ownerPlayer == null || !ctx.isEnemy(ownerPlayer, victim))) {
                event.setCancelled(true);
            }
        }
    }

    /** Ability damage applies no vanilla knockback. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onKnockback(EntityKnockbackEvent event) {
        EntityKnockbackEvent.Cause cause = event.getCause();
        if ((cause == EntityKnockbackEvent.Cause.DAMAGE || cause == EntityKnockbackEvent.Cause.ENTITY_ATTACK)
                && ctx().suppressesKnockback(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /**
     * Hook routing: the attacker's onDealDamage (enemy victims only), then the victim's onTakeDamage. A summon's
     * melee hit first gets the summon's exact attack damage, then goes to its owner's onDealDamage.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!fighting()) {
            return;
        }
        Entity victimEntity = event.getEntity();
        if (event instanceof EntityDamageByEntityEvent byEntity && victimEntity instanceof LivingEntity victim) {
            normalizeSummonHit(byEntity);
            Player attacker = dealerOf(byEntity);
            Participant dealer = attacker == null ? null : match.participant(attacker.getUniqueId());
            Ability ability = dealer == null ? null : dealer.ability;
            if (ability != null && session.isInPlay(attacker) && ctx().isEnemy(attacker, victim)) {
                ctx().runAs(attacker.getUniqueId(), fromUltimateEntity(byEntity), () -> guard.run(dealer.abilityId,
                        "onDealDamage", () -> ability.onDealDamage(byEntity, victim)));
                if (event.isCancelled()) {
                    return;
                }
            }
        }
        if (victimEntity instanceof Player victim) {
            Participant taker = match.participant(victim.getUniqueId());
            Ability ability = taker == null ? null : taker.ability;
            if (ability != null && session.isInPlay(victim)) {
                guard.run(taker.abilityId, "onTakeDamage", () -> ability.onTakeDamage(event));
            }
        }
    }

    /**
     * The hit landed (nothing cancelled it): the attacker's read-only {@link Ability#onDamageDealt}, for on-hit
     * effects that must not happen when the victim dodged.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageDealt(EntityDamageByEntityEvent event) {
        if (!fighting() || !(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }
        Player attacker = dealerOf(event);
        Participant dealer = attacker == null ? null : match.participant(attacker.getUniqueId());
        Ability ability = dealer == null ? null : dealer.ability;
        if (ability != null && session.isInPlay(attacker) && ctx().isEnemy(attacker, victim)) {
            ctx().runAs(attacker.getUniqueId(), fromUltimateEntity(event), () -> guard.run(dealer.abilityId,
                    "onDamageDealt", () -> ability.onDamageDealt(event, victim)));
        }
    }

    /**
     * Summons hit with exactly their {@link Attribute#ATTACK_DAMAGE}: no vanilla randomness (iron golem) and no
     * difficulty scaling against players, so the numbers in {@code abilities.yml} are what lands.
     */
    private void normalizeSummonHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof LivingEntity summon) || ctx().summonOwner(summon) == null
                || ctx().isAbilityDamage() || event.getDamageSource().getDamageType() != DamageType.MOB_ATTACK) {
            return;
        }
        AttributeInstance attack = summon.getAttribute(Attribute.ATTACK_DAMAGE);
        if (attack != null && Double.isFinite(attack.getValue())) {
            event.setDamage(Math.max(0.0, attack.getValue()));
        }
    }

    /** True if the hit comes from an entity an ultimate created (its owner's hooks then run as that ultimate). */
    private boolean fromUltimateEntity(EntityDamageByEntityEvent event) {
        return ctx().isUltimateEntity(event.getDamager())
                || ctx().isUltimateEntity(event.getDamageSource().getCausingEntity());
    }

    /** The participant behind a hit: the player, the shooter/owner of a projectile, or the owner of a summon. */
    private @Nullable Player dealerOf(EntityDamageByEntityEvent event) {
        Player attacker = attackerOf(event);
        if (attacker != null) {
            return attacker;
        }
        UUID owner = ctx().summonOwner(event.getDamager());
        Entity causing = event.getDamageSource().getCausingEntity();
        if (owner == null && causing != null) {
            owner = ctx().summonOwner(causing);
        }
        Player player = owner == null ? null : Bukkit.getPlayer(owner);
        return player != null && session.isParticipant(player) ? player : null;
    }

    /** The participant behind a hit: the player, or the shooter/owner of a projectile. */
    private @Nullable Player attackerOf(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        Player attacker = null;
        if (damager instanceof Player player) {
            attacker = player;
        } else if (damager instanceof Projectile projectile) {
            MatchContext.Tagged tagged = ctx().tagged(projectile);
            if (tagged != null) {
                attacker = Bukkit.getPlayer(tagged.owner());
            } else if (projectile.getShooter() instanceof Player shooter) {
                attacker = shooter;
            }
        }
        return attacker != null && session.isParticipant(attacker) ? attacker : null;
    }

    /**
     * Ultimate gauge: damage taken, and damage dealt to enemy players. Damage that belongs to the attacker's own
     * ultimate never charges the attacker; nothing charges during the post-ultimate lock.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageMonitor(EntityDamageEvent event) {
        if (!fighting() || !(event.getEntity() instanceof Player victim) || !session.isParticipant(victim)
                || !session.isInPlay(victim)) {
            return;
        }
        double amount = Math.min(event.getFinalDamage(), victim.getHealth() + victim.getAbsorptionAmount());
        if (!(amount > 0)) {
            return;
        }
        DamageType type = event.getDamageSource().getDamageType();
        if (type != DamageType.OUTSIDE_BORDER && type != DamageType.OUT_OF_WORLD && type != DamageType.GENERIC_KILL) {
            match.addEarnedGauge(victim, amount * match.settings().gaugePerDamageTaken());
        }
        Player attacker = session.combat().resolveAttacker(event);
        if (attacker != null && !attacker.equals(victim) && session.isInPlay(attacker)
                && ctx().isEnemy(attacker, victim) && !ctx().isUltimateHit(event, attacker.getUniqueId())) {
            match.addEarnedGauge(attacker, amount * match.settings().gaugePerDamageDealt());
        }
    }

    // =================================================================== summons & created entities

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Player) && ctx().isManaged(event.getEntity())) {
            event.getDrops().clear();
            event.setDroppedExp(0);
        }
    }

    /** Summons only ever target enemies of their owner (covers tamed-wolf owner AI and golem targeting). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(EntityTargetEvent event) {
        Entity target = event.getTarget();
        UUID owner = ctx().summonOwner(event.getEntity());
        if (owner == null || target == null) {
            return;
        }
        Player ownerPlayer = Bukkit.getPlayer(owner);
        if (ownerPlayer == null || !ctx().isEnemy(ownerPlayer, target)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        MatchContext.Tagged tagged = ctx().tagged(event.getEntity());
        if (tagged == null) {
            return;
        }
        Participant owner = match.participant(tagged.owner());
        Ability ability = owner == null ? null : owner.ability;
        if (ability != null) {
            // A projectile an ultimate launched keeps counting as that ultimate when its hit is handled.
            ctx().runAs(tagged.owner(), ctx().isUltimateEntity(event.getEntity()), () -> guard.run(owner.abilityId,
                    "onProjectileHit", () -> ability.onProjectileHit(event, tagged.tag())));
        }
    }

    /**
     * Safety net: anything spawned while ability code runs (hooks, skills, ability tasks) that the ability did not
     * track itself is made non-persistent and removed when the match ends, so a buggy ability cannot leave entities in
     * the world.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(EntitySpawnEvent event) {
        Entity entity = event.getEntity();
        if (guard.isRunningAbilityCode() && !(entity instanceof Player) && !ctx().isManaged(entity)) {
            session.track(entity);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        Entity igniter = event.getIgnitingEntity();
        if (igniter != null && ctx().isManaged(igniter)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChangeBlock(EntityChangeBlockEvent event) {
        if (ctx().isManaged(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        if (ctx().isManaged(event.getEntity())) {
            event.blockList().clear();
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPrime(ExplosionPrimeEvent event) {
        if (ctx().isManaged(event.getEntity())) {
            event.setFire(false);
        }
    }

    // =================================================================== input

    /** Right-click with the ability item: never a vanilla use; the active skill (or prep actions). */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (!items.isAbilityItem(event.getItem()) || !session.isParticipant(player)) {
            return;
        }
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);
        if (event.getHand() == EquipmentSlot.HAND) {
            match.handleUse(player);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        if (event.getHand() != EquipmentSlot.HAND || !session.isParticipant(player)
                || !items.isAbilityItem(player.getInventory().getItemInMainHand())) {
            return;
        }
        event.setCancelled(true);
        match.handleUse(player);
    }

    /** F: always cancelled for alive participants; the ultimate during the fight. */
    @EventHandler(priority = EventPriority.LOW)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        if (!session.isParticipant(player) || !session.isInPlay(player)) {
            return;
        }
        event.setCancelled(true);
        match.handleSwap(player);
    }

    /** The XP bar shows the ultimate gauge: vanilla experience never changes it. */
    @EventHandler(priority = EventPriority.LOW)
    public void onExp(PlayerExpChangeEvent event) {
        if (session.isParticipant(event.getPlayer())) {
            event.setAmount(0);
        }
    }

    // =================================================================== ability item & menus

    @EventHandler(priority = EventPriority.LOW)
    public void onDrop(PlayerDropItemEvent event) {
        if (session.isParticipant(event.getPlayer()) && items.isAbilityItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    /**
     * Every click while any choice menu is open is cancelled, including the moment between a pick and the menu
     * closing (a stale menu is no longer the participant's, but its icons are real items).
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getView().getTopInventory().getHolder(false) instanceof ChoiceMenu menu) {
            event.setCancelled(true);
            if (event.getClickedInventory() == menu.getInventory() && menu == match.menuOf(player)) {
                match.onMenuClick(player, menu, event.getSlot());
            }
            return;
        }
        if (session.isParticipant(player) && involvesAbilityItem(event, player)) {
            event.setCancelled(true);
        }
    }

    private boolean involvesAbilityItem(InventoryClickEvent event, Player player) {
        if (items.isAbilityItem(event.getCurrentItem()) || items.isAbilityItem(event.getCursor())) {
            return true;
        }
        if (event.getClick() == ClickType.NUMBER_KEY) {
            int button = event.getHotbarButton();
            if (button >= 0 && items.isAbilityItem(player.getInventory().getItem(button))) {
                return true;
            }
        }
        return event.getClick() == ClickType.SWAP_OFFHAND
                && items.isAbilityItem(player.getInventory().getItemInOffHand());
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getView().getTopInventory().getHolder(false) instanceof ChoiceMenu) {
            event.setCancelled(true);
            return;
        }
        ItemStack dragged = event.getOldCursor();
        if (session.isParticipant(player) && items.isAbilityItem(dragged)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        ChoiceMenu menu = match.menuOf(player);
        if (menu != null && event.getInventory() == menu.getInventory()) {
            match.onMenuClosed(player, menu, event.getReason());
        }
    }
}
