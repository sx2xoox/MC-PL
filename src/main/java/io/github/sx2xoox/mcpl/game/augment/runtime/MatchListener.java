package io.github.sx2xoox.mcpl.game.augment.runtime;

import com.destroystokyo.paper.event.player.PlayerSetSpawnEvent;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.inventory.InventoryHolder;

/**
 * The match's events: the PvP gate, augment hooks, the card menu, bonus conditions and the rules that keep the
 * throw-away world from leaking into the server (no ender chests, no portals, no respawn points).
 */
final class MatchListener implements Listener {

    private final AugmentMatch match;
    private final GameSession session;

    MatchListener(AugmentMatch match) {
        this.match = match;
        this.session = match.session();
    }

    private boolean inWild(World world) {
        return match.wild().world().equals(world);
    }

    private boolean inPlay(Entity entity) {
        return entity instanceof Player player && session.isInPlay(player);
    }

    // =================================================================== damage

    /**
     * While the world is prepared nobody gets hurt; during the farming players cannot hurt each other (anything
     * credited to a participant: melee, arrows, TNT, ...). Mobs, falls and lava still hurt.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDamageGate(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim) || !session.isAlive(victim)) {
            return;
        }
        if (match.phase() == MatchPhase.PREPARING) {
            event.setCancelled(true);
            return;
        }
        if (match.phase() == MatchPhase.FARMING) {
            Player attacker = session.combat().resolveAttacker(event);
            if (attacker != null && !attacker.getUniqueId().equals(victim.getUniqueId())
                    && session.isParticipant(attacker)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamageModify(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity victim) || match.isAugmentDamage()) {
            return;
        }
        Player attacker = session.combat().resolveAttacker(event);
        if (attacker != null && match.isEnemy(attacker.getUniqueId(), victim)) {
            match.forEachAugment(attacker, "onOutgoingDamage", augment -> augment.onOutgoingDamage(event, victim));
        }
        if (victim instanceof Player player && !event.isCancelled()) {
            match.forEachAugment(player, "onIncomingDamage", augment -> augment.onIncomingDamage(event));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLethal(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || !session.isInPlay(player)) {
            return;
        }
        if (event.getFinalDamage() >= player.getHealth() + player.getAbsorptionAmount()) {
            match.saveFromDeath(player, event);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageDone(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }
        double dealt = Math.min(event.getFinalDamage(), victim.getHealth() + victim.getAbsorptionAmount());
        Player attacker = session.combat().resolveAttacker(event);
        if (attacker != null && match.isEnemy(attacker.getUniqueId(), victim)) {
            if (!match.isAugmentDamage()) {
                match.forEachAugment(attacker, "afterOutgoingDamage",
                        augment -> augment.afterOutgoingDamage(victim, dealt));
            }
            if (victim instanceof Player hurt) {
                match.bonus().onPlayerHit(hurt, attacker);
            }
        }
        if (victim instanceof Player player && session.isInPlay(player)
                && event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            match.bonus().onFallDamage(player, event.getFinalDamage());
        }
    }

    // =================================================================== augment hooks

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onShootBow(EntityShootBowEvent event) {
        if (event.getEntity() instanceof Player player) {
            match.forEachAugment(player, "onShootBow", augment -> augment.onShootBow(event));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (event.getEntity().getShooter() instanceof Player player) {
            match.forEachAugment(player, "onProjectileHit", augment -> augment.onProjectileHit(event));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        match.forEachAugment(event.getPlayer(), "onToggleFlight", augment -> augment.onToggleFlight(event));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        match.forEachAugment(event.getPlayer(), "onSwapHands", augment -> augment.onSwapHands(event));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!session.isInPlay(player) || !inWild(event.getBlock().getWorld())) {
            return;
        }
        match.forEachAugment(player, "onBlockBreak", augment -> augment.onBlockBreak(event));
        match.bonus().onBlockBreak(player, event.getBlock());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockDrop(BlockDropItemEvent event) {
        Player player = event.getPlayer();
        if (session.isInPlay(player) && inWild(event.getBlock().getWorld())) {
            match.forEachAugment(player, "onBlockDrop", augment -> augment.onBlockDrop(event));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        match.forEachAugment(event.getPlayer(), "onConsume", augment -> augment.onConsume(event));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onExpChange(PlayerExpChangeEvent event) {
        match.forEachAugment(event.getPlayer(), "onExpChange", augment -> augment.onExpChange(event));
    }

    // =================================================================== bonus conditions

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLoot(LootGenerateEvent event) {
        if (!event.isPlugin() && event.getInventoryHolder() != null && event.getEntity() instanceof Player player
                && inPlay(player) && inWild(event.getWorld())) {
            match.bonus().onLootContainer(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMobDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        Player killer = dead.getKiller();
        if (!(dead instanceof Player) && killer != null && session.isInPlay(killer) && inWild(dead.getWorld())) {
            match.bonus().onMobKill(killer, dead);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        if (inPlay(event.getEnchanter())) {
            match.bonus().onEnchant(event.getEnchanter());
        }
    }

    // =================================================================== card menu

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMenuClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder(false);
        if (!(holder instanceof OfferMenu menu)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !menu.owner().equals(player.getUniqueId())
                || match.menu(player.getUniqueId()) != menu) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot == OfferMenu.REROLL_SLOT) {
            match.reroll(player);
            return;
        }
        AugmentInfo card = menu.cardAt(slot);
        if (card != null) {
            match.pick(player, card);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMenuDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof OfferMenu) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMenuClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder(false) instanceof OfferMenu menu
                && event.getPlayer() instanceof Player player) {
            match.menuClosed(player, menu);
        }
    }

    // =================================================================== world rules

    /**
     * Ender chests are not part of the snapshot: one opened in the match world could smuggle items out of (or into)
     * the match.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (event.getInventory().getType() == InventoryType.ENDER_CHEST && event.getPlayer() instanceof Player player
                && session.isMember(player) && inWild(player.getWorld())) {
            event.setCancelled(true);
            match.messages().send(player, "rules.ender-chest");
        }
    }

    /** No respawn point in a world that is deleted after the match. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSetSpawn(PlayerSetSpawnEvent event) {
        Player player = event.getPlayer();
        if (session.isMember(player) && inWild(player.getWorld())) {
            event.setCancelled(true);
        }
    }

    /** Overworld only: no portals out of the match world. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        if (inWild(event.getFrom().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        if (inWild(event.getFrom().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPortalCreate(PortalCreateEvent event) {
        if (inWild(event.getWorld())) {
            event.setCancelled(true);
        }
    }
}
