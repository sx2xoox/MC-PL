package io.github.sx2xoox.mcpl.game.superpower.loot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.ui.Feedback;
import io.github.sx2xoox.mcpl.core.ui.Format;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.game.superpower.SuperpowerGame;
import io.github.sx2xoox.mcpl.game.superpower.runtime.MatchPhase;
import io.github.sx2xoox.mcpl.game.superpower.runtime.SuperpowerMatch;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The loot of one 초능력전쟁 match (owned by {@link SuperpowerMatch}):
 *
 * <ul>
 *     <li>{@link #spreadChests()} when the fight starts: normal and rare chests spread over the arena
 *     ({@link LootPlacer}, {@link LootSettings#chestCount});</li>
 *     <li>{@link #dropSupplies(double)} when the border starts shrinking: supply drops announced in chat that fall
 *     from the sky near the center and land as chests with a particle beam and a glowing marker;</li>
 *     <li>opening (a shared virtual inventory per chest, take-only), emptied chests disappearing, the superpower
 *     consumables ({@link LootConsumables});</li>
 *     <li>{@link #dispose()} when the match ends: every chest block, display and open view is gone.</li>
 * </ul>
 * Chest blocks are temporary blocks (restored at the latest with every other temporary block when the match ends, and
 * after a crash from {@code tempblocks.yml}); displays are tracked session entities (non-persistent). A bug in here
 * never ends the match: it is logged and the loot stops.
 */
public final class LootManager {

    /** Visual effects (beams, sparkles) and the viewer checks run every this many ticks. */
    static final int EFFECT_PERIOD = 10;
    /** A viewer farther than this from the chest (or no longer in play) has the chest closed. */
    static final double VIEW_DISTANCE = 6.0;
    private static final int BEAM_HEIGHT = 32;
    private static final float MARKER_HEIGHT = 2.2f;
    private static final Color SUPPLY_GLOW = Color.fromRGB(0xFFC83D);
    private static final Display.Brightness FULL_BRIGHT = new Display.Brightness(15, 15);
    /** Within this distance of the arena center a supply drop is announced as "중앙". */
    private static final double CENTER_RADIUS = 8.0;
    private static final String[] DIRECTIONS = {
            "north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};

    private final SuperpowerMatch match;
    private final GameSession session;
    private final LootSettings settings;
    private final LootConsumables consumables;
    private final Messages messages;
    private final Random random;
    private final List<LootChest> chests = new ArrayList<>();
    private final List<SupplyDrop> falling = new ArrayList<>();
    private final Map<UUID, Long> lastUse = new HashMap<>();
    private boolean spread;
    private boolean suppliesDropped;
    private boolean broken;
    private boolean disposed;
    private long age;

    /** A supply drop on its way down: a crate display falling onto {@code spot}. */
    private static final class SupplyDrop {
        final LootPlacer.Spot spot;
        final ItemDisplay crate;
        final double startY;
        final double endY;
        int ticks;

        SupplyDrop(LootPlacer.Spot spot, ItemDisplay crate, double startY, double endY) {
            this.spot = spot;
            this.crate = crate;
            this.startY = startY;
            this.endY = endY;
        }
    }

    public LootManager(SuperpowerGame game, SuperpowerMatch match, LootSettings settings) {
        this.match = match;
        this.session = match.session();
        this.settings = settings;
        this.messages = game.messages();
        this.consumables = new LootConsumables(game.hub(), messages);
        this.random = session.random();
    }

    /**
     * The loot of a match, or null if loot is off for it ({@code loot.enabled}, {@code loot.practice}). A broken
     * {@code loot} section never stops the match: it only turns the loot off (with a console warning).
     *
     * @param superpower the live {@code games.superpower} section
     */
    public static @Nullable LootManager create(SuperpowerGame game, SuperpowerMatch match,
                                               ConfigurationSection superpower) {
        Logger logger = match.session().plugin().getLogger();
        LootSettings settings;
        try {
            settings = LootSettings.read(superpower, logger);
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "games.superpower.loot could not be read; no loot chests this match", e);
            return null;
        }
        if (!settings.enabled() || (match.isPractice() && !settings.inPractice())) {
            return null;
        }
        return new LootManager(game, match, settings);
    }

    // =================================================================== lifecycle

    /** The fight started: spreads the chests (once). */
    public void spreadChests() {
        if (spread || disposed || broken) {
            return;
        }
        spread = true;
        guarded("spreading chests", () -> {
            List<Player> players = session.alivePlayers();
            List<Location> avoid = new ArrayList<>();
            for (Player player : players) {
                avoid.add(player.getLocation());
            }
            int count = settings.chestCount(players.size(), session.arena().radius());
            List<LootPlacer.Spot> spots = placer().chestSpots(count, avoid, settings.playerDistance(), List.of());
            int placed = 0;
            for (LootPlacer.Spot spot : spots) {
                LootTier tier = rollTier();
                if (tier != null && place(spot, tier) != null) {
                    placed++;
                }
            }
            if (placed < count) {
                logger().info("Placed " + placed + " of " + count + " loot chests (not enough good spots)");
            }
            if (placed > 0) {
                session.broadcast(messages.prefixed("loot.spread", Messages.tag("count", placed)));
            }
        });
    }

    /**
     * The border started shrinking: announces the supply drops and lets them fall (once).
     *
     * @param finalBorderSize the side length the border shrinks to (drops land where it stays longest)
     */
    public void dropSupplies(double finalBorderSize) {
        if (suppliesDropped || disposed || broken) {
            return;
        }
        suppliesDropped = true;
        guarded("dropping supplies", () -> {
            int count = settings.supplyCount(session.aliveIds().size());
            if (count <= 0 || settings.supply().pool().isEmpty()) {
                return;
            }
            List<LootPlacer.Spot> taken = spots();
            List<LootPlacer.Spot> spots = placer().supplySpots(count, finalBorderSize * 0.4, taken);
            if (spots.isEmpty()) {
                logger().info("No open spot for a supply drop in the arena");
                return;
            }
            for (LootPlacer.Spot spot : spots) {
                Block block = spot.block();
                session.broadcast(messages.prefixed("loot.supply-incoming",
                        Messages.tag("direction", direction(spot.center())),
                        Messages.tag("x", block.getX()), Messages.tag("y", block.getY()),
                        Messages.tag("z", block.getZ())));
                falling.add(startFall(spot));
            }
            session.playSound(Sound.BLOCK_BELL_USE, 1.0f, 0.7f);
            session.playSound(Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.0f, 0.6f);
        });
    }

    /** Every fight tick: falling supply drops, effects and viewer checks. */
    public void tick() {
        if (disposed || broken) {
            return;
        }
        guarded("ticking", () -> {
            age++;
            if (!falling.isEmpty()) {
                tickFalling();
            }
            if (age % 2 == 0) {
                spinMarkers();
            }
            if (age % EFFECT_PERIOD == 0) {
                maintain();
            }
        });
    }

    /** The match ended: removes every chest (block, views, displays) and falling drop. Safe to call twice. */
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        for (LootChest chest : List.copyOf(chests)) {
            try {
                remove(chest, false);
            } catch (RuntimeException e) {
                logger().log(Level.WARNING, "Could not remove a loot chest at " + chest.block(), e);
            }
        }
        chests.clear();
        for (SupplyDrop drop : falling) {
            removeDisplay(drop.crate);
        }
        falling.clear();
        lastUse.clear();
    }

    // =================================================================== queries

    /** The live chests (read-only). */
    public List<LootChest> chests() {
        return Collections.unmodifiableList(chests);
    }

    /** The chest whose block is {@code block}, or null. */
    public @Nullable LootChest chestAt(@Nullable Block block) {
        if (block == null) {
            return null;
        }
        for (LootChest chest : chests) {
            if (chest.block().equals(block)) {
                return chest;
            }
        }
        return null;
    }

    /** The chest whose virtual inventory is {@code inventory}, or null. */
    public @Nullable LootChest chestOf(@Nullable Inventory inventory) {
        if (inventory == null) {
            return null;
        }
        for (LootChest chest : chests) {
            if (chest.getInventory() == inventory || chest.getInventory().equals(inventory)) {
                return chest;
            }
        }
        return null;
    }

    /** Supply drops still falling. */
    public int fallingCount() {
        return falling.size();
    }

    /** The falling crate displays (tests). */
    public List<ItemDisplay> fallingCrates() {
        List<ItemDisplay> crates = new ArrayList<>();
        for (SupplyDrop drop : falling) {
            crates.add(drop.crate);
        }
        return crates;
    }

    public LootSettings settings() {
        return settings;
    }

    public LootConsumables consumables() {
        return consumables;
    }

    /** True if right-clicking a loot chest opens it (like vanilla: not when sneaking with something in hand). */
    public static boolean opensOnClick(Player player) {
        return !player.isSneaking() || (player.getInventory().getItemInMainHand().isEmpty()
                && player.getInventory().getItemInOffHand().isEmpty());
    }

    // =================================================================== chests

    private LootPlacer placer() {
        return LootPlacer.of(session, session.hub().maps());
    }

    private List<LootPlacer.Spot> spots() {
        List<LootPlacer.Spot> spots = new ArrayList<>();
        for (LootChest chest : chests) {
            spots.add(new LootPlacer.Spot(chest.block(), BlockFace.SOUTH));
        }
        for (SupplyDrop drop : falling) {
            spots.add(drop.spot);
        }
        return spots;
    }

    /** Normal or rare by {@code loot.chances}; the other tier if one has no usable pool; null if neither has. */
    private @Nullable LootTier rollTier() {
        boolean normal = !settings.normal().pool().isEmpty() && settings.normalWeight() > 0;
        boolean rare = !settings.rare().pool().isEmpty() && settings.rareWeight() > 0;
        if (normal && rare) {
            int total = settings.normalWeight() + settings.rareWeight();
            return random.nextInt(total) < settings.rareWeight() ? LootTier.RARE : LootTier.NORMAL;
        }
        return normal ? LootTier.NORMAL : rare ? LootTier.RARE : null;
    }

    /** Places a filled chest of {@code tier} at {@code spot}; null if it got no items or the block was refused. */
    private @Nullable LootChest place(LootPlacer.Spot spot, LootTier tier) {
        LootSettings.Tier options = settings.tier(tier);
        int entries = options.minItems() + random.nextInt(options.maxItems() - options.minItems() + 1);
        List<ItemStack> stacks = options.pool().roll(random, entries, consumables, settings.crystalGauge());
        if (stacks.isEmpty()) {
            return null;
        }
        BlockData data = Material.CHEST.createBlockData();
        if (data instanceof Directional directional) {
            directional.setFacing(spot.facing());
        }
        if (!session.placeTempBlock(spot.block(), data, 0)) {
            return null;
        }
        LootChest chest = new LootChest(spot.block(), tier, messages.get("loot.title." + tier.key()));
        fill(chest.getInventory(), stacks);
        chests.add(chest);
        return chest;
    }

    /** Puts the stacks into random slots. */
    private void fill(Inventory inventory, List<ItemStack> stacks) {
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            slots.add(slot);
        }
        Collections.shuffle(slots, random);
        for (int i = 0; i < stacks.size() && i < slots.size(); i++) {
            inventory.setItem(slots.get(i), stacks.get(i));
        }
    }

    /** {@code player} right-clicked {@code chest}. */
    void open(Player player, LootChest chest) {
        if (disposed || chest.isRemoved()) {
            return;
        }
        if (match.phase() != MatchPhase.FIGHT || !session.isInPlay(player)) {
            player.sendActionBar(messages.get("loot.not-now"));
            return;
        }
        player.openInventory(chest.getInventory());
        if (!chest.wasOpened()) {
            chest.markOpened();
            ItemDisplay marker = chest.marker();
            chest.marker(null);
            removeDisplay(marker);
        }
        chest.lid(true);
    }

    /** A view of {@code chest} was closed: next tick the lid closes if nobody looks, and an empty chest goes. */
    void closed(LootChest chest) {
        if (disposed || chest.isRemoved()) {
            return;
        }
        session.runLater(1, () -> refresh(chest));
    }

    private void refresh(LootChest chest) {
        if (disposed || chest.isRemoved()) {
            return;
        }
        if (!chest.isIntact()) {
            remove(chest, false);
            return;
        }
        if (!chest.getInventory().getViewers().isEmpty()) {
            return;
        }
        chest.lid(false);
        if (chest.getInventory().isEmpty()) {
            remove(chest, true);
        }
    }

    /** Removes a chest: views closed, marker gone, block restored; with {@code effect} a small puff. */
    private void remove(LootChest chest, boolean effect) {
        if (chest.isRemoved()) {
            return;
        }
        chest.markRemoved();
        chests.remove(chest);
        for (HumanEntity viewer : List.copyOf(chest.getInventory().getViewers())) {
            if (disposed) {
                viewer.setItemOnCursor(null);
            }
            viewer.closeInventory();
        }
        chest.getInventory().clear();
        removeDisplay(chest.marker());
        chest.marker(null);
        Block block = chest.block();
        if (block.getType() == Material.CHEST) {
            session.hub().tempBlocks().restore(block);
        }
        if (effect) {
            Location center = chest.center();
            World world = center.getWorld();
            world.spawnParticle(Particle.POOF, center, 12, 0.3, 0.3, 0.3, 0.02);
            world.playSound(center, Sound.BLOCK_WOOD_BREAK, SoundCategory.BLOCKS, 0.8f, 1.2f);
        }
    }

    // =================================================================== supply drops

    private SupplyDrop startFall(LootPlacer.Spot spot) {
        Block block = spot.block();
        World world = block.getWorld();
        double endY = block.getY() + 0.5;
        double startY = Math.min(world.getMaxHeight() - 1.0, endY + LootPlacer.FALL_HEIGHT);
        Location at = new Location(world, block.getX() + 0.5, startY, block.getZ() + 0.5);
        ItemDisplay crate = world.spawn(at, ItemDisplay.class, display -> {
            display.setPersistent(false);
            display.setItemStack(ItemStack.of(Material.CHEST));
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            display.setBrightness(FULL_BRIGHT);
            display.setViewRange(4f);
            display.setTeleportDuration(1);
            display.setGlowColorOverride(SUPPLY_GLOW);
            display.setGlowing(true);
        });
        session.track(crate);
        return new SupplyDrop(spot, crate, startY, endY);
    }

    private void tickFalling() {
        int duration = Math.max(1, settings.supplyFallTicks());
        for (SupplyDrop drop : List.copyOf(falling)) {
            drop.ticks++;
            double t = Math.min(1.0, drop.ticks / (double) duration);
            double y = drop.startY - (drop.startY - drop.endY) * t * t;
            if (drop.crate.isValid()) {
                Location to = drop.crate.getLocation();
                to.setY(y);
                to.setYaw(to.getYaw() + 14f);
                to.setPitch(0f);
                drop.crate.teleport(to);
                if (drop.ticks % 2 == 0) {
                    to.getWorld().spawnParticle(Particle.CLOUD, to.getX(), y + 0.7, to.getZ(), 2, 0.2, 0.1, 0.2,
                            0.01, null, true);
                }
            }
            if (drop.ticks >= duration) {
                falling.remove(drop);
                land(drop);
            }
        }
    }

    private void land(SupplyDrop drop) {
        removeDisplay(drop.crate);
        LootPlacer.Spot spot = drop.spot;
        LootPlacer placer = placer();
        if (!placer.isChestSpot(spot.block())) {
            // Something took the spot while the crate fell (an ability's block): land right next to it.
            spot = placer.spotNear(spot.block().getLocation(), 4, false, spots());
        }
        LootChest chest = spot == null ? null : place(spot, LootTier.SUPPLY);
        if (chest == null) {
            logger().info("A supply drop could not land near " + drop.spot.block());
            return;
        }
        Location center = chest.center();
        World world = center.getWorld();
        liftPlayersOutOf(chest.block());
        world.spawnParticle(Particle.POOF, center, 24, 0.6, 0.3, 0.6, 0.05, null, true);
        world.spawnParticle(Particle.FIREWORK, center, 16, 0.4, 0.4, 0.4, 0.15, null, true);
        world.playSound(center, Sound.BLOCK_ANVIL_LAND, SoundCategory.BLOCKS, 1.4f, 0.6f);
        world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.BLOCKS, 0.5f, 1.6f);
        chest.marker(spawnMarker(chest));
    }

    /** A player standing where the crate lands is put on top of it instead of inside. */
    private void liftPlayersOutOf(Block block) {
        BoundingBox box = BoundingBox.of(block);
        for (Player player : session.alivePlayers()) {
            if (player.getWorld().equals(block.getWorld()) && player.getBoundingBox().overlaps(box)) {
                Location up = player.getLocation();
                up.setY(block.getY() + 1.0);
                player.teleport(up);
            }
        }
    }

    /** The glowing star above an unopened supply drop (visible through walls, so players gather). */
    private ItemDisplay spawnMarker(LootChest chest) {
        Location at = chest.center().add(0, MARKER_HEIGHT, 0);
        ItemDisplay marker = at.getWorld().spawn(at, ItemDisplay.class, display -> {
            display.setPersistent(false);
            display.setItemStack(ItemStack.of(Material.NETHER_STAR));
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            display.setBillboard(Display.Billboard.VERTICAL);
            display.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.9f),
                    new Quaternionf()));
            display.setBrightness(FULL_BRIGHT);
            display.setViewRange(6f);
            display.setTeleportDuration(2);
            display.setGlowColorOverride(SUPPLY_GLOW);
            display.setGlowing(true);
        });
        session.track(marker);
        return marker;
    }

    private void spinMarkers() {
        for (LootChest chest : chests) {
            ItemDisplay marker = chest.marker();
            if (marker != null && marker.isValid()) {
                // A gentle bob; the billboard keeps the star facing everyone.
                Location to = chest.center().add(0, MARKER_HEIGHT + Math.sin(age / 10.0) * 0.15, 0);
                marker.teleport(to);
            }
        }
    }

    // =================================================================== upkeep

    /** Effects, broken chests, viewers who walked away or left play, lids, emptied chests. */
    private void maintain() {
        double scale = match.settings().particleScale();
        for (LootChest chest : List.copyOf(chests)) {
            if (!chest.isIntact()) {
                remove(chest, false);
                continue;
            }
            Location center = chest.center();
            for (HumanEntity viewer : List.copyOf(chest.getInventory().getViewers())) {
                if (!(viewer instanceof Player player) || !session.isInPlay(player)
                        || !player.getWorld().equals(center.getWorld())
                        || player.getLocation().distanceSquared(center) > VIEW_DISTANCE * VIEW_DISTANCE) {
                    viewer.closeInventory();
                }
            }
            if (chest.getInventory().getViewers().isEmpty()) {
                chest.lid(false);
                if (chest.getInventory().isEmpty()) {
                    remove(chest, true);
                    continue;
                }
            }
            effects(chest, center, scale);
        }
    }

    private void effects(LootChest chest, Location center, double scale) {
        World world = center.getWorld();
        switch (chest.tier()) {
            case NORMAL -> {
                // plain: the chest itself is the hint
            }
            case RARE -> {
                if (age % (EFFECT_PERIOD * 2) == 0) {
                    int count = Math.max(1, (int) Math.round(3 * scale));
                    world.spawnParticle(Particle.WAX_ON, center.getX(), center.getY() + 0.3, center.getZ(), count,
                            0.35, 0.3, 0.35, 0.0);
                }
            }
            case SUPPLY -> {
                if (!chest.wasOpened()) {
                    for (int i = 0; i < BEAM_HEIGHT; i += 2) {
                        world.spawnParticle(Particle.END_ROD, center.getX(), center.getY() + 1.0 + i, center.getZ(),
                                1, 0.02, 0.4, 0.02, 0.0, null, true);
                    }
                }
            }
        }
    }

    private static void removeDisplay(@Nullable ItemDisplay display) {
        if (display != null && display.isValid()) {
            display.remove();
        }
    }

    // =================================================================== consumables

    /** {@code player} right-clicked with a consumable in {@code hand}. */
    void useConsumable(Player player, EquipmentSlot hand, LootConsumables.Kind kind) {
        long tick = session.tick();
        Long last = lastUse.put(player.getUniqueId(), tick);
        if (last != null && last == tick) {
            return; // the block and the air interaction of one click
        }
        SuperpowerMatch.ItemUseResult result = switch (kind) {
            case ULTIMATE_CRYSTAL -> match.chargeGaugeWithItem(player, settings.crystalGauge());
            case RECHARGE_CHARM -> match.rechargeActiveWithItem(player);
        };
        if (result != SuperpowerMatch.ItemUseResult.OK) {
            refuse(player, result);
            return;
        }
        consumeOne(player, hand, kind);
        Location body = player.getLocation().add(0, 1.0, 0);
        World world = player.getWorld();
        if (kind == LootConsumables.Kind.ULTIMATE_CRYSTAL) {
            player.sendActionBar(messages.get("loot.use.crystal",
                    Messages.tag("amount", Format.number(settings.crystalGauge())),
                    Messages.tag("percent", (int) Math.floor(match.context().gauge(player) + 1.0E-9))));
            world.spawnParticle(Particle.WITCH, body, 18, 0.35, 0.6, 0.35, 0.02);
            world.playSound(body, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 1.0f, 1.2f);
        } else {
            player.sendActionBar(messages.get("loot.use.charm"));
            world.spawnParticle(Particle.HAPPY_VILLAGER, body, 14, 0.35, 0.6, 0.35, 0.0);
            world.playSound(body, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.PLAYERS, 0.9f, 1.4f);
        }
    }

    private void refuse(Player player, SuperpowerMatch.ItemUseResult result) {
        Component message = switch (result) {
            case SILENCED -> messages.get("loot.use.silenced",
                    Messages.tag("seconds", Format.number(match.silenceSeconds(player))));
            case GAUGE_LOCKED -> messages.get("loot.use.gauge-locked",
                    Messages.tag("seconds", Format.number(match.gaugeLockSeconds(player))));
            case GAUGE_FULL -> messages.get("loot.use.gauge-full");
            case COOLDOWN_READY -> messages.get("loot.use.cooldown-ready");
            default -> messages.get("loot.use.not-now");
        };
        player.sendActionBar(message);
        Feedback.play(player, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.7f);
    }

    private void consumeOne(Player player, EquipmentSlot hand, LootConsumables.Kind kind) {
        ItemStack held = player.getInventory().getItem(hand);
        if (consumables.kindOf(held) != kind) {
            return;
        }
        if (held.getAmount() <= 1) {
            player.getInventory().setItem(hand, null);
        } else {
            held.setAmount(held.getAmount() - 1);
            player.getInventory().setItem(hand, held);
        }
    }

    // =================================================================== helpers

    /** The rough direction of {@code at} from the arena center, in Korean (북동쪽, 중앙, ...). */
    Component direction(Location at) {
        return messages.get("loot.direction." + directionKey(session.arena().center(), at));
    }

    /** {@code center}, {@code north}, {@code north-east}, ... of {@code at} seen from {@code center}. */
    public static String directionKey(Location center, Location at) {
        double dx = at.getX() - center.getX();
        double dz = at.getZ() - center.getZ();
        if (dx * dx + dz * dz < CENTER_RADIUS * CENTER_RADIUS) {
            return "center";
        }
        // 0 = north (-z), clockwise: 90 = east (+x).
        double degrees = Math.toDegrees(Math.atan2(dx, -dz));
        int index = (int) Math.round((degrees + 360) % 360 / 45.0) % 8;
        return DIRECTIONS[index];
    }

    private void guarded(String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | LinkageError e) {
            broken = true;
            logger().log(Level.SEVERE, "Loot chests failed while " + what + "; no more loot this match", e);
            try {
                dispose();
            } catch (RuntimeException cleanup) {
                logger().log(Level.WARNING, "Could not clean up the loot chests", cleanup);
            }
        }
    }

    private Logger logger() {
        return session.plugin().getLogger();
    }
}
