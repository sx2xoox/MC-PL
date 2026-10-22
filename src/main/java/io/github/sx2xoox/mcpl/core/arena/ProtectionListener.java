package io.github.sx2xoox.mcpl.core.arena;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import io.github.sx2xoox.mcpl.core.config.ProtectionSettings;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.game.GameState;
import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.inventory.InventoryHolder;
import org.jspecify.annotations.Nullable;

/**
 * Keeps the arena world unchanged while a match exists (registered by {@link GameSession} for its whole lifetime).
 * Every handler first checks the cheapest condition (toggle, then world identity, then session membership).
 *
 * <p>Blocks: participants cannot break/place blocks or use buckets; explosions destroy no blocks; fire neither
 * ignites, spreads nor burns; non-player entities (falling blocks, endermen, ...) cannot change blocks.
 * Decorations: paintings, item frames and armor stands cannot be broken, damaged or manipulated.
 * Interaction: participants and spectators cannot use doors, trapdoors, gates, buttons, levers, containers, beds and
 * similar blocks, nor trample farmland or press plates. Participants cannot drop items.
 */
public final class ProtectionListener implements Listener {

    private final GameSession session;
    private final ProtectionSettings settings;
    private final World world;
    private final Set<Material> interactable;

    public ProtectionListener(GameSession session, ProtectionSettings settings) {
        this.session = session;
        this.settings = settings;
        this.world = session.arena().world();
        this.interactable = settings.interact() ? buildInteractable() : EnumSet.noneOf(Material.class);
    }

    private static Set<Material> buildInteractable() {
        EnumSet<Material> set = EnumSet.of(
                Material.LEVER, Material.CHEST, Material.TRAPPED_CHEST, Material.ENDER_CHEST, Material.BARREL,
                Material.FURNACE, Material.BLAST_FURNACE, Material.SMOKER, Material.HOPPER, Material.DROPPER,
                Material.DISPENSER, Material.BREWING_STAND, Material.CRAFTING_TABLE, Material.ENCHANTING_TABLE,
                Material.GRINDSTONE, Material.STONECUTTER, Material.LOOM, Material.CARTOGRAPHY_TABLE,
                Material.SMITHING_TABLE, Material.LECTERN, Material.BEACON, Material.COMPOSTER, Material.NOTE_BLOCK,
                Material.JUKEBOX, Material.REPEATER, Material.COMPARATOR, Material.DAYLIGHT_DETECTOR,
                Material.RESPAWN_ANCHOR, Material.CAKE, Material.CHISELED_BOOKSHELF, Material.DECORATED_POT,
                Material.CRAFTER, Material.BELL, Material.COMMAND_BLOCK, Material.CHAIN_COMMAND_BLOCK,
                Material.REPEATING_COMMAND_BLOCK, Material.STRUCTURE_BLOCK, Material.JIGSAW, Material.TEST_BLOCK,
                Material.VAULT, Material.DRAGON_EGG, Material.SWEET_BERRY_BUSH, Material.CAVE_VINES,
                Material.CAVE_VINES_PLANT, Material.BEEHIVE, Material.BEE_NEST, Material.END_PORTAL_FRAME,
                Material.REDSTONE_WIRE, Material.TNT, Material.LODESTONE);
        for (Tag<Material> tag : List.of(Tag.DOORS, Tag.TRAPDOORS, Tag.FENCE_GATES, Tag.BUTTONS, Tag.BEDS,
                Tag.SHULKER_BOXES, Tag.ANVIL, Tag.ALL_SIGNS, Tag.FLOWER_POTS, Tag.CANDLE_CAKES, Tag.CANDLES,
                Tag.CAULDRONS, Tag.CAMPFIRES, Tag.COPPER_CHESTS, Tag.COPPER_GOLEM_STATUES, Tag.WOODEN_SHELVES,
                Tag.PRESSURE_PLATES)) {
            if (tag != null) {
                set.addAll(tag.getValues());
            }
        }
        return set;
    }

    private boolean inArena(@Nullable World other) {
        return other == world || world.equals(other);
    }

    private boolean involved(Player player) {
        return session.isParticipant(player) || session.isMember(player);
    }

    private boolean involvedInArena(Player player) {
        return inArena(player.getWorld()) && involved(player);
    }

    // ------------------------------------------------------------------ blocks

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (settings.blockBreak() && involvedInArena(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** Also covers multi-block placement (beds, doors). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (settings.blockPlace() && involvedInArena(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (settings.blockPlace() && involvedInArena(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (settings.blockPlace() && involvedInArena(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** Armor stands, boats, minecarts and end crystals placed by players. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        Player player = event.getPlayer();
        if (settings.blockPlace() && player != null && involvedInArena(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent event) {
        if (!settings.blockPlace() || !inArena(event.getBlock().getWorld())) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null || involved(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (settings.explosions() && inArena(event.getLocation().getWorld())) {
            event.blockList().clear();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (settings.explosions() && inArena(event.getBlock().getWorld())) {
            event.blockList().clear();
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (settings.fire() && inArena(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (settings.fire() && inArena(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (settings.fire() && Tag.FIRE.isTagged(event.getSource().getType()) && inArena(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (!settings.entityChangeBlock() || !inArena(event.getBlock().getWorld())) {
            return;
        }
        if (!(event.getEntity() instanceof Player player) || involved(player)) {
            event.setCancelled(true);
        }
    }

    /** Frost walker ice, snow golem trails. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityForm(EntityBlockFormEvent event) {
        if (settings.entityChangeBlock() && inArena(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ decorations

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent event) {
        if (settings.hanging() && inArena(event.getEntity().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        Player player = event.getPlayer();
        if (settings.hanging() && player != null && involvedInArena(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDecorationDamage(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        if (settings.hanging() && (entity instanceof Hanging || entity instanceof ArmorStand)
                && inArena(entity.getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (settings.hanging() && involvedInArena(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onItemFrame(PlayerItemFrameChangeEvent event) {
        if (settings.hanging() && involvedInArena(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Entity clicked = event.getRightClicked();
        if (settings.hanging() && (clicked instanceof Hanging || clicked instanceof ArmorStand)
                && involvedInArena(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        if (!settings.hanging() || !inArena(event.getVehicle().getWorld())) {
            return;
        }
        if (!(event.getAttacker() instanceof Player player) || involved(player)) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ interaction

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        if (!settings.interact()) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.PHYSICAL) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || !inArena(block.getWorld()) || !involved(event.getPlayer())) {
            return;
        }
        if (action == Action.PHYSICAL) {
            event.setCancelled(true);
            return;
        }
        if (interactable.contains(block.getType()) || block.getState(false) instanceof InventoryHolder) {
            event.setUseInteractedBlock(Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLectern(PlayerTakeLecternBookEvent event) {
        if (settings.interact() && involvedInArena(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (settings.itemDrop() && session.state() != GameState.COUNTDOWN && session.isParticipant(event.getPlayer())) {
            event.setCancelled(true);
        }
    }
}
