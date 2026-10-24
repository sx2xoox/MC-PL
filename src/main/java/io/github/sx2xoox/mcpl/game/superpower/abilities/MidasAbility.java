package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Motion;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 미다스 — the Greek king whose touch turned everything to gold, his food included.
 *
 * <p>His blows gild one armour piece of the enemy at a time: it looks like gold armour to everyone and protects like
 * gold armour, and gold is soft — whoever hits a gilded enemy next (the king, his allies, in free-for-all anyone)
 * cracks a piece off for extra damage, so the gold is a mark everybody plays for. The active flicks a fan of gold coins
 * that plate what they hit; the ultimate spreads a golden floor under the king that gilds whoever stands on it, curses
 * him (no healing, but a golden skin that takes less damage) and ends by collecting the tax: every gilded piece around
 * cracks at once.
 *
 * <p>Look: gold, royal purple and white marble. Gilded pieces are sent to every viewer as real gold armour (resent every
 * tick, since the server sends the true equipment again on every hit), glittering with wax sparkles; a crack throws
 * gold shards and a "쨍그랑" pops up. The coins are small spinning gold plates that ping off armour. The golden floor is
 * a gold octagon spreading out from the king with a marble core, a purple rim and rising glitter; the tax is a rain of
 * gold motes flying from every victim into the king's purse.
 */
public final class MidasAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("midas", "미다스", Material.GOLD_BLOCK)
            .color(TextColor.color(0xF2C230))
            .difficulty(1)
            .passive("황금 손",
                    "근접 공격한 적의 갑옷 한 부위를 {gild_duration}초간 황금으로 바꿉니다(가슴→다리→신발→투구 순, 최대 "
                            + "{gild_max}부위). 모두에게 금 갑옷으로 보이고 방어력도 금 갑옷만큼 떨어집니다(철 갑옷이면 "
                            + "가슴 −1·다리 −2·신발 −1). 황금은 무릅니다: 금 부위가 있는 적을 누구든(나·아군, 개인전에서는 "
                            + "다른 적도) 무엇으로든 맞히면 한 부위가 쨍그랑 깨지며 그 공격의 피해가 {shatter_bonus} "
                            + "늘어납니다(같은 적은 {shatter_cooldown}초마다).")
            .active("금화 세례",
                    "앞쪽 {coin_spread}° 부채꼴로 금화 {coin_count}닢을 튕겨 날립니다({coin_range}블록). 금화는 처음 맞은 "
                            + "적에게 {coin_damage} 피해를 주고(적마다 최대 {coin_max_hits}닢) 갑옷 한 부위를 도금합니다"
                            + "(한 번에 적마다 최대 {coin_gild_max}부위). 금화는 금을 깨뜨리지 않습니다.",
                    9)
            .ultimate("황금 손길",
                    "{touch_duration}초간 내가 딛는 땅이 반경 {touch_radius}블록까지 황금으로 번지며 나를 따라옵니다. "
                            + "황금 위의 적은 {touch_gild_interval}초마다 한 부위씩 더 도금됩니다. 저주: 그동안 나는 회복할 "
                            + "수 없지만 황금 피부로 받는 피해가 {touch_reduction}% 줄어듭니다. 끝날 때 세금 징수: "
                            + "{tax_radius}블록 안 모든 적의 도금 부위가 한꺼번에 깨지며 부위당 {tax_damage} 피해(적마다 최대 "
                            + "{tax_max})를 주고, 나는 부위당 {tax_heal} 회복합니다.")
            .param("gild_duration", 8)
            .param("gild_max", 4)
            .param("shatter_bonus", 1.5)
            .param("shatter_cooldown", 0.5)
            .param("coin_count", 7)
            .param("coin_spread", 50)
            .param("coin_range", 14)
            .param("coin_damage", 1)
            .param("coin_max_hits", 4)
            .param("coin_gild_max", 2)
            .param("touch_duration", 6)
            .param("touch_radius", 6)
            .param("touch_gild_interval", 1)
            .param("touch_reduction", 20)
            .param("tax_radius", 20)
            .param("tax_damage", 1.5)
            .param("tax_max", 6)
            .param("tax_heal", 0.5)
            .build();

    private static final String GOLD_TAG = "#F2C230";
    private static final String PURPLE_TAG = "#9B59C9";
    private static final String MARBLE_TAG = "#EEE8DE";

    // ------------------------------------------------------------------ look
    /** White marble glint, gold, royal purple. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFF3C4, 0xF2C230, 0x6A2C91);
    private static final Particle.DustOptions GOLD = new Particle.DustOptions(Color.fromRGB(0xF2C230), 1.0f);
    private static final Particle.DustOptions GOLD_SMALL = new Particle.DustOptions(Color.fromRGB(0xFFD75A), 0.6f);
    private static final Particle.DustOptions PURPLE = new Particle.DustOptions(Color.fromRGB(0x6A2C91), 1.1f);
    private static final Particle.DustOptions MARBLE = new Particle.DustOptions(Color.fromRGB(0xEEE8DE), 0.8f);
    private static final Particle.DustTransition GILD = PALETTE.glow(1.0f);
    private static final Particle.DustTransition ROYAL = PALETTE.fade(1.2f);
    private static final Color COIN_TRAIL = Color.fromRGB(0xFFD75A);
    private static final ItemStack NUGGET = new ItemStack(Material.GOLD_NUGGET);

    /** Materials of the visuals; block data is created when used (it needs a running server). */
    private static final Material COIN = Material.GOLD_BLOCK;
    private static final Material FLOOR = Material.GOLD_BLOCK;
    private static final Material FLOOR_CORE = Material.CHISELED_QUARTZ_BLOCK;
    private static final Material FLOOR_INLAY = Material.PURPLE_GLAZED_TERRACOTTA;

    private static final double MAX_SPEED = 1.5;
    private static final int MAX_DISPLAYS = 24;

    /** The order in which a body is gilded: where gold costs the most protection first. */
    private static final EquipmentSlot[] GILD_ORDER = {EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
            EquipmentSlot.HEAD};

    // passive
    private static final long GLITTER_PERIOD = 5;
    private static final long CRACK_LABEL_TICKS = 14;

    // active
    private static final double COIN_SPEED = 1.15;
    private static final double COIN_HIT_GROW = 0.28;
    private static final float COIN_SIZE = 0.3f;
    private static final float COIN_THICK = 0.05f;
    private static final int COIN_STAGGER = 1;

    // ultimate
    private static final int FLOOR_GROW_TICKS = 8;
    private static final float FLOOR_THICK = 0.04f;
    private static final double FLOOR_SNAP = 3;
    private static final double ON_GOLD_BELOW = 1.0;
    private static final double ON_GOLD_ABOVE = 2.5;
    private static final long TAX_WARNING = 20;
    private static final int TAX_TRAIL_MOTES = 3;

    /** The real king's gold (null for a borrowed ultimate, which keeps its own for the length of the spell). */
    private @Nullable Ledger ledger;
    private final List<Display> displays = new ArrayList<>();
    /** Above 0 while the king's own coins or tax land: they plate or collect, they never crack. */
    private int plating;
    private @Nullable Touch touch;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onGrant() {
        Ledger book = new Ledger(player(), true);
        ledger = book;
        book.open();
    }

    @Override
    public void onRevoke() {
        Touch spell = touch;
        if (spell != null) {
            spell.end(false);
        }
        touch = null;
        Ledger book = ledger;
        if (book != null) {
            book.release();
        }
        ledger = null;
        displays.clear();
    }

    @Override
    public void onTick(long tick) {
        Ledger book = ledger;
        if (book != null && (touch == null || touch.book != book)) {
            book.tick(tick);
        }
    }

    /** Package-private for tests: the gilded slots of an enemy in the king's own ledger. */
    Set<EquipmentSlot> gildedSlots(LivingEntity enemy) {
        Ledger book = ledger;
        Touch spell = touch;
        if (book == null && spell != null) {
            book = spell.book;
        }
        if (book == null) {
            return Set.of();
        }
        Gilding gilding = book.gilded.get(enemy.getUniqueId());
        return gilding == null ? Set.of() : Collections.unmodifiableSet(gilding.pieces.keySet());
    }

    // ------------------------------------------------------------------ the gold

    /** One gilded armour piece: the modifiers that make it protect like gold, and when it runs out. */
    private static final class Piece {
        final EquipmentSlot slot;
        final @Nullable NamespacedKey armorKey;
        final @Nullable NamespacedKey toughnessKey;
        final long expiry;

        Piece(EquipmentSlot slot, @Nullable NamespacedKey armorKey, @Nullable NamespacedKey toughnessKey, long expiry) {
            this.slot = slot;
            this.armorKey = armorKey;
            this.toughnessKey = toughnessKey;
            this.expiry = expiry;
        }
    }

    /** The gold on one enemy. */
    private static final class Gilding {
        final LivingEntity entity;
        final Map<EquipmentSlot, Piece> pieces = new LinkedHashMap<>();
        long nextCrack = Long.MIN_VALUE / 2;

        Gilding(LivingEntity entity) {
            this.entity = entity;
        }
    }

    /**
     * Everything gilded by one king: the pieces with their armour modifiers, the fake gold armour shown to every viewer
     * (resent every tick), and the listener that lets anyone crack the gold. The real king keeps one for the whole
     * match; a borrowed ultimate keeps its own for the length of the spell (it never sees a revoke, so it lets go when
     * its owner falls or leaves or its floor is removed).
     */
    private final class Ledger implements Listener {

        private final Player owner;
        /** The real king's: his own melee gilds through it. */
        private final boolean real;
        private final Map<UUID, Gilding> gilded = new LinkedHashMap<>();
        /** Hits raised by a crack, waiting to be confirmed as landed (identity, by event). */
        private final Map<EntityDamageEvent, LivingEntity> pending = new WeakHashMap<>();
        /** Displays whose removal means the spell this ledger belongs to is over (borrowed ledgers only). */
        private final Set<UUID> anchors = new HashSet<>();
        private boolean registered;
        private boolean released;

        Ledger(Player owner, boolean real) {
            this.owner = owner;
            this.real = real;
        }

        void open() {
            Bukkit.getPluginManager().registerEvents(this, ctx().plugin());
            registered = true;
        }

        /** Lets go of every piece (the true armour is shown again) and of the listener. */
        void release() {
            if (released) {
                return;
            }
            released = true;
            if (registered) {
                HandlerList.unregisterAll(this);
                registered = false;
            }
            for (Gilding gilding : List.copyOf(gilded.values())) {
                for (Piece piece : List.copyOf(gilding.pieces.values())) {
                    strip(gilding, piece);
                }
            }
            gilded.clear();
            pending.clear();
        }

        int pieces(LivingEntity entity) {
            Gilding gilding = gilded.get(entity.getUniqueId());
            return gilding == null ? 0 : gilding.pieces.size();
        }

        /**
         * Gilds the next armour piece of {@code enemy} (refreshing the oldest one when every slot is gold already).
         * False if it could not be gilded.
         */
        boolean gild(LivingEntity enemy) {
            if (released || !Combat.isTargetable(ctx(), owner, enemy)) {
                return false;
            }
            int max = Math.clamp(params().integer("gild_max"), 0, GILD_ORDER.length);
            long duration = params().ticks("gild_duration");
            if (max <= 0 || duration <= 0) {
                return false;
            }
            Gilding gilding = gilded.computeIfAbsent(enemy.getUniqueId(), id -> new Gilding(enemy));
            EquipmentSlot slot = null;
            for (int i = 0; i < max; i++) {
                if (!gilding.pieces.containsKey(GILD_ORDER[i])) {
                    slot = GILD_ORDER[i];
                    break;
                }
            }
            if (slot == null) {
                // Every slot is gold already: the oldest piece is freshly gilded.
                Piece oldest = gilding.pieces.values().iterator().next();
                strip(gilding, oldest);
                slot = oldest.slot;
            }
            ItemStack worn = worn(enemy, slot);
            double armor = Math.max(0, armorOf(worn, slot) - goldArmor(slot));
            double toughness = Math.max(0, toughnessOf(worn));
            NamespacedKey armorKey = armor > 0
                    ? ctx().addModifier(owner, enemy, Attribute.ARMOR, -armor, Operation.ADD_NUMBER, duration) : null;
            NamespacedKey toughnessKey = toughness > 0
                    ? ctx().addModifier(owner, enemy, Attribute.ARMOR_TOUGHNESS, -toughness, Operation.ADD_NUMBER,
                    duration) : null;
            gilding.pieces.put(slot, new Piece(slot, armorKey, toughnessKey, ctx().currentTick() + duration));
            show(gilding);
            gildFx(enemy, slot);
            return true;
        }

        /** Takes one piece off (expired, cracked or collected): its modifiers go and the true armour shows again. */
        private void strip(Gilding gilding, Piece piece) {
            gilding.pieces.remove(piece.slot);
            LivingEntity entity = gilding.entity;
            if (piece.armorKey != null) {
                ctx().removeModifier(entity, piece.armorKey);
            }
            if (piece.toughnessKey != null) {
                ctx().removeModifier(entity, piece.toughnessKey);
            }
            ItemStack real = worn(entity, piece.slot);
            for (Player viewer : viewers(entity)) {
                viewer.sendEquipmentChange(entity, piece.slot, real);
            }
        }

        /** Cracks the oldest gold piece of {@code enemy}. False if it had none. */
        boolean crack(LivingEntity enemy, @Nullable Player hitter) {
            Gilding gilding = gilded.get(enemy.getUniqueId());
            if (gilding == null || gilding.pieces.isEmpty()) {
                return false;
            }
            Piece oldest = gilding.pieces.values().iterator().next();
            strip(gilding, oldest);
            crackFx(enemy, oldest.slot, hitter);
            return true;
        }

        /** Every piece of {@code enemy} at once (the tax): how many there were. */
        int collect(LivingEntity enemy) {
            Gilding gilding = gilded.remove(enemy.getUniqueId());
            if (gilding == null) {
                return 0;
            }
            int count = gilding.pieces.size();
            for (Piece piece : List.copyOf(gilding.pieces.values())) {
                strip(gilding, piece);
                crackFx(enemy, piece.slot, null);
            }
            return count;
        }

        /** Expires pieces, forgets the gone, glitters, and shows the gold to every viewer again. */
        void tick(long tick) {
            if (released) {
                return;
            }
            long now = ctx().currentTick();
            for (Iterator<Gilding> it = gilded.values().iterator(); it.hasNext(); ) {
                Gilding gilding = it.next();
                LivingEntity entity = gilding.entity;
                if (!entity.isValid() || entity.isDead()) {
                    gilding.pieces.clear();
                    it.remove();
                    continue;
                }
                for (Piece piece : List.copyOf(gilding.pieces.values())) {
                    if (piece.expiry <= now) {
                        strip(gilding, piece);
                        fadeFx(entity, piece.slot);
                    }
                }
                if (gilding.pieces.isEmpty()) {
                    it.remove();
                    continue;
                }
                show(gilding);
                if (tick % GLITTER_PERIOD == 0 && !isHidden(entity)) {
                    glitter(gilding);
                }
            }
        }

        /** Sends the gold pieces to everyone who can see the enemy (the server overwrites them on every hit). */
        private void show(Gilding gilding) {
            if (gilding.pieces.isEmpty()) {
                return;
            }
            Map<EquipmentSlot, ItemStack> gold = new EnumMap<>(EquipmentSlot.class);
            for (EquipmentSlot slot : gilding.pieces.keySet()) {
                gold.put(slot, new ItemStack(goldItem(slot)));
            }
            for (Player viewer : viewers(gilding.entity)) {
                viewer.sendEquipmentChange(gilding.entity, gold);
            }
        }

        private void glitter(Gilding gilding) {
            LivingEntity entity = gilding.entity;
            World world = entity.getWorld();
            for (EquipmentSlot slot : gilding.pieces.keySet()) {
                Location at = slotSpot(entity, slot);
                world.spawnParticle(Particle.WAX_OFF, at, 1, 0.2, 0.1, 0.2, 0);
                world.spawnParticle(Particle.DUST, at, Vfx.n(ctx(), 1), 0.2, 0.1, 0.2, 0, GOLD_SMALL);
            }
        }

        // -------------------------------------------------------------- anyone may crack the gold

        /**
         * A blow on a gilded enemy, by anyone, with anything: one piece will crack and the blow is raised. The piece
         * cracks only once the blow has landed ({@link #onLanded}); the king's coins and tax never crack.
         */
        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onBlow(EntityDamageByEntityEvent event) {
            if (released) {
                return;
            }
            try {
                raise(event);
            } catch (RuntimeException e) {
                ctx().plugin().getLogger().log(Level.WARNING, "Midas gold crack failed", e);
            }
        }

        private void raise(EntityDamageByEntityEvent event) {
            if (!(event.getEntity() instanceof LivingEntity victim) || plating > 0 || !(event.getDamage() > 0)) {
                return;
            }
            Gilding gilding = gilded.get(victim.getUniqueId());
            if (gilding == null || gilding.pieces.isEmpty() || !ctx().isAlive(owner) || !ctx().isEnemy(owner, victim)) {
                return;
            }
            Entity causing = event.getDamageSource().getCausingEntity();
            Player hitter = ctx().resolveAttacker(causing != null ? causing : event.getDamager());
            if (hitter == null || hitter.equals(victim) || !ctx().isAlive(hitter)) {
                return;
            }
            long now = ctx().currentTick();
            if (now < gilding.nextCrack) {
                return;
            }
            double bonus = Math.max(0, params().num("shatter_bonus"));
            event.setDamage(event.getDamage() + bonus);
            pending.put(event, victim);
        }

        /** The blow landed: the gold cracks; the king's own swing then gilds the next piece. */
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onLanded(EntityDamageByEntityEvent event) {
            if (released) {
                return;
            }
            try {
                land(event);
            } catch (RuntimeException e) {
                ctx().plugin().getLogger().log(Level.WARNING, "Midas golden touch failed", e);
            }
        }

        private void land(EntityDamageByEntityEvent event) {
            if (!(event.getEntity() instanceof LivingEntity victim)) {
                return;
            }
            LivingEntity cracked = pending.remove(event);
            if (cracked != null) {
                Gilding gilding = gilded.get(cracked.getUniqueId());
                if (gilding != null) {
                    gilding.nextCrack = ctx().currentTick() + params().ticks("shatter_cooldown");
                }
                Entity causing = event.getDamageSource().getCausingEntity();
                Player hitter = ctx().resolveAttacker(causing != null ? causing : event.getDamager());
                crack(cracked, hitter);
            }
            if (real && !ctx().isAbilityDamage() && owner.equals(event.getDamager())
                    && (event.getCause() == DamageCause.ENTITY_ATTACK
                    || event.getCause() == DamageCause.ENTITY_SWEEP_ATTACK)
                    && ctx().isAlive(owner) && ctx().isEnemy(owner, victim)) {
                gild(victim);
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onRemove(EntityRemoveEvent event) {
            if (!released && !real && anchors.contains(event.getEntity().getUniqueId())) {
                release();
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onOwnerDeath(PlayerDeathEvent event) {
            if (!real && event.getEntity().equals(owner)) {
                release();
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onOwnerQuit(PlayerQuitEvent event) {
            if (!real && event.getPlayer().equals(owner)) {
                release();
            }
        }
    }

    /** Everyone who can see {@code entity} (but itself): they get the fake gold armour. */
    private static List<Player> viewers(LivingEntity entity) {
        List<Player> viewers = new ArrayList<>();
        for (Player viewer : entity.getWorld().getPlayers()) {
            if (!viewer.equals(entity) && viewer.isOnline() && viewer.canSee(entity)) {
                viewers.add(viewer);
            }
        }
        return viewers;
    }

    private static ItemStack worn(LivingEntity entity, EquipmentSlot slot) {
        EntityEquipment equipment = entity.getEquipment();
        ItemStack item = null;
        if (equipment != null) {
            try {
                item = equipment.getItem(slot);
            } catch (IllegalArgumentException unsupported) {
                item = null;
            }
        }
        return item == null ? ItemStack.empty() : item;
    }

    private static Material goldItem(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> Material.GOLDEN_HELMET;
            case CHEST -> Material.GOLDEN_CHESTPLATE;
            case LEGS -> Material.GOLDEN_LEGGINGS;
            default -> Material.GOLDEN_BOOTS;
        };
    }

    /** Vanilla armour points of a gold piece in {@code slot}. */
    private static double goldArmor(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> 2;
            case CHEST -> 5;
            case LEGS -> 3;
            default -> 1;
        };
    }

    /** Vanilla armour points of the piece worn in {@code slot} (0 for anything that is not armour). */
    private static double armorOf(ItemStack item, EquipmentSlot slot) {
        String name = item.getType().name();
        int index = switch (slot) {
            case HEAD -> 0;
            case CHEST -> 1;
            case LEGS -> 2;
            case FEET -> 3;
            default -> -1;
        };
        if (index < 0) {
            return 0;
        }
        String suffix = switch (index) {
            case 0 -> "_HELMET";
            case 1 -> "_CHESTPLATE";
            case 2 -> "_LEGGINGS";
            default -> "_BOOTS";
        };
        if (!name.endsWith(suffix)) {
            return 0;
        }
        String kind = name.substring(0, name.length() - suffix.length());
        double[] points = switch (kind) {
            case "LEATHER" -> new double[] {1, 3, 2, 1};
            case "CHAINMAIL", "COPPER" -> kind.equals("COPPER") ? new double[] {2, 4, 3, 1} : new double[] {2, 5, 4, 1};
            case "IRON" -> new double[] {2, 6, 5, 2};
            case "GOLDEN" -> new double[] {2, 5, 3, 1};
            case "DIAMOND", "NETHERITE" -> new double[] {3, 8, 6, 3};
            case "TURTLE" -> new double[] {2, 0, 0, 0};
            default -> new double[] {0, 0, 0, 0};
        };
        return points[index];
    }

    /** Vanilla armour toughness of a piece (gold has none). */
    private static double toughnessOf(ItemStack item) {
        String name = item.getType().name();
        if (name.startsWith("NETHERITE_")) {
            return 3;
        }
        return name.startsWith("DIAMOND_") && !name.endsWith("_HORSE_ARMOR") ? 2 : 0;
    }

    /** Where a slot sits on a body (for sparkles and cracks). */
    private static Location slotSpot(LivingEntity entity, EquipmentSlot slot) {
        double height = entity.getHeight();
        double y = switch (slot) {
            case HEAD -> 0.88;
            case CHEST -> 0.64;
            case LEGS -> 0.38;
            default -> 0.1;
        };
        return entity.getLocation().add(0, height * y, 0);
    }

    private static String slotName(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> "투구";
            case CHEST -> "가슴";
            case LEGS -> "다리";
            default -> "신발";
        };
    }

    // ------------------------------------------------------------------ gold visuals

    private void gildFx(LivingEntity enemy, EquipmentSlot slot) {
        if (isHidden(enemy)) {
            return;
        }
        Location at = slotSpot(enemy, slot);
        World world = at.getWorld();
        Vfx.dot(at, Particle.FLASH, PALETTE.body(), false);
        Vfx.ring(ctx(), at, 0.42, 10, ThreadLocalRandom.current().nextDouble(Math.PI * 2),
                Particle.DUST_COLOR_TRANSITION, GILD, false);
        world.spawnParticle(Particle.WAX_ON, at, Vfx.n(ctx(), 5), 0.25, 0.15, 0.25, 0);
        Fx.sound(at, Sound.ITEM_ARMOR_EQUIP_GOLD, 1.0f, 1.2f + Fx.jitter(0.1f));
        Fx.sound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, 1.6f + Fx.jitter(0.1f));
    }

    /** "쨍그랑": a gold piece cracks off, raising the blow of whoever hit it. */
    private void crackFx(LivingEntity enemy, EquipmentSlot slot, @Nullable Player hitter) {
        Player owner = player();
        if (!isHidden(enemy)) {
            Location at = slotSpot(enemy, slot);
            World world = at.getWorld();
            Vfx.dot(at, Particle.FLASH, PALETTE.core(), false);
            world.spawnParticle(Particle.BLOCK, at, Vfx.n(ctx(), 10), 0.25, 0.2, 0.25, 0,
                    Material.GOLD_BLOCK.createBlockData());
            world.spawnParticle(Particle.ITEM, at, Vfx.n(ctx(), 6), 0.15, 0.15, 0.15, 0.15, NUGGET);
            world.spawnParticle(Particle.DUST, at, Vfx.n(ctx(), 6), 0.3, 0.2, 0.3, 0, GOLD);
            Fx.sound(at, Sound.BLOCK_GLASS_BREAK, 0.9f, 1.5f + Fx.jitter(0.1f));
            Fx.sound(at, Sound.ITEM_ARMOR_EQUIP_GOLD, 0.8f, 0.6f + Fx.jitter(0.06f));
            Fx.sound(at, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 0.7f, 1.3f + Fx.jitter(0.1f));
            if (reserve(1)) {
                TextDisplay label = keep(Displays.text(ctx(), owner, Displays.unrotated(at.clone().add(0, 0.5, 0)),
                        Text.mm("<" + GOLD_TAG + "><b>쨍그랑!</b></" + GOLD_TAG + ">"), 0.5f, CRACK_LABEL_TICKS));
                label.setTeleportDuration(CRACK_LABEL_TICKS > 0 ? 6 : 0);
                ctx().runLater(owner, 1, () -> {
                    if (label.isValid()) {
                        Displays.moveTo(label, label.getLocation().add(0, 0.4, 0));
                        Displays.animate(label, Displays.centered(0, 0, 0, 0.75f, 0.75f, 0.75f, 0, 0, 0), 4);
                    }
                });
            }
        }
        if (hitter != null && hitter.isOnline() && !hitter.equals(owner)) {
            hitter.sendActionBar(Text.mm("<" + GOLD_TAG + ">쨍그랑!</" + GOLD_TAG + "> <gray>미다스의 금이 깨지며 피해 +"
                    + AbilityInfo.formatNumber(params().num("shatter_bonus")) + "</gray>"));
        } else if (hitter != null && hitter.equals(owner)) {
            owner.sendActionBar(Text.mm("<" + GOLD_TAG + ">쨍그랑!</" + GOLD_TAG + "> <gray>" + slotName(slot)
                    + " 황금이 깨졌습니다 · 피해 +" + AbilityInfo.formatNumber(params().num("shatter_bonus"))
                    + "</gray>"));
        }
    }

    private void fadeFx(LivingEntity enemy, EquipmentSlot slot) {
        if (isHidden(enemy)) {
            return;
        }
        Location at = slotSpot(enemy, slot);
        at.getWorld().spawnParticle(Particle.DUST, at, Vfx.n(ctx(), 4), 0.25, 0.15, 0.25, 0, GOLD_SMALL);
    }

    // ------------------------------------------------------------------ active: shower of coins

    @Override
    public ActivationResult onActive() {
        Ledger book = ledger;
        if (book == null) {
            return ActivationResult.fail("황금 손이 아직 깨어나지 않았습니다");
        }
        Player owner = player();
        int count = Math.max(1, params().integer("coin_count"));
        double spread = Math.clamp(params().num("coin_spread"), 0, 180);
        double range = Math.max(1, params().num("coin_range"));
        Location eye = owner.getEyeLocation();
        Vector look = eye.getDirection();
        if (look.lengthSquared() < 1.0E-8) {
            look = Motion.horizontalFacing(eye);
        }
        look.normalize();
        Volley volley = new Volley(owner, book);
        for (int i = 0; i < count; i++) {
            double offset = count == 1 ? 0 : -spread / 2 + spread * i / (count - 1);
            Vector direction = look.clone().rotateAroundY(Math.toRadians(-offset));
            int index = i;
            // The coins leave the thumb one after another, the middle one first.
            int delay = Math.abs(i - (count - 1) / 2) * COIN_STAGGER;
            ctx().runLater(owner, delay, () -> volley.flick(direction, range, index));
        }
        Location hand = hand(owner);
        Vfx.dot(hand, Particle.FLASH, PALETTE.body(), false);
        Vfx.arc(ctx(), eye.clone().subtract(0, 0.35, 0), 0.9, eye.getYaw(), spread + 10, 11,
                Particle.DUST_COLOR_TRANSITION, GILD, false);
        owner.getWorld().spawnParticle(Particle.WAX_ON, hand, Vfx.n(ctx(), 6), 0.15, 0.15, 0.15, 0);
        Fx.sound(hand, Sound.BLOCK_VAULT_EJECT_ITEM, 1.0f, 1.3f + Fx.jitter(0.08f));
        Fx.sound(hand, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.6f + Fx.jitter(0.1f));
        Fx.sound(hand, Sound.BLOCK_CHAIN_PLACE, 0.7f, 1.8f + Fx.jitter(0.1f));
        return ActivationResult.success();
    }

    /** One cast of coins: per-enemy counts of hits and platings. */
    private final class Volley {
        private final Player owner;
        private final Ledger book;
        private final Map<UUID, Integer> hits = new HashMap<>();
        private final Map<UUID, Integer> gilds = new HashMap<>();

        Volley(Player owner, Ledger book) {
            this.owner = owner;
            this.book = book;
        }

        void flick(Vector direction, double range, int index) {
            if (!ctx().isAlive(owner)) {
                return;
            }
            Location start = hand(owner);
            BlockDisplay coin = reserve(1) ? keep(Displays.block(ctx(), owner, Displays.unrotated(start),
                    COIN.createBlockData(), coinPose(direction, 0, 1f), (long) Math.ceil(range / COIN_SPEED) + 10))
                    : null;
            if (coin != null) {
                coin.setTeleportDuration(1);
            }
            Coin flight = new Coin(this, start, direction, range, coin, index);
            Loop.repeat(ctx(), owner, 1, 1, (long) Math.ceil(range / COIN_SPEED) + 1, flight);
        }

        /** A coin hit {@code enemy}: damage (up to the cap) and a plating (up to the cap). */
        void hit(LivingEntity enemy, Location at) {
            UUID id = enemy.getUniqueId();
            int hitCount = hits.merge(id, 1, Integer::sum);
            if (hitCount <= Math.max(0, params().integer("coin_max_hits"))) {
                plating++;
                try {
                    Combat.hit(ctx(), owner, enemy, params().num("coin_damage"));
                } finally {
                    plating--;
                }
            }
            if (Combat.isTargetable(ctx(), owner, enemy)
                    && gilds.getOrDefault(id, 0) < Math.max(0, params().integer("coin_gild_max"))
                    && book.gild(enemy)) {
                gilds.merge(id, 1, Integer::sum);
            }
            if (!isHidden(enemy)) {
                World world = at.getWorld();
                world.spawnParticle(Particle.ITEM, at, Vfx.n(ctx(), 3), 0.08, 0.08, 0.08, 0.08, NUGGET);
                world.spawnParticle(Particle.WAX_ON, at, Vfx.n(ctx(), 2), 0.1, 0.1, 0.1, 0);
                Fx.sound(at, Sound.BLOCK_NOTE_BLOCK_BELL, 0.45f, 1.9f + Fx.jitter(0.08f));
                Fx.sound(at, Sound.BLOCK_CHAIN_HIT, 0.6f, 1.7f + Fx.jitter(0.1f));
            }
        }
    }

    /** One gold coin in flight: a little spinning plate, first enemy or block it meets ends it. */
    private final class Coin implements LongPredicate {
        private final Volley volley;
        private final Vector step;
        private final double range;
        private final @Nullable BlockDisplay display;
        private final Location position;
        private final Vector direction;
        private final int index;
        private double travelled;

        Coin(Volley volley, Location start, Vector direction, double range, @Nullable BlockDisplay display, int index) {
            this.volley = volley;
            this.direction = direction.clone().normalize();
            this.step = this.direction.clone().multiply(COIN_SPEED);
            this.range = range;
            this.display = display;
            this.position = start.clone();
            this.index = index;
        }

        @Override
        public boolean test(long tick) {
            Player owner = volley.owner;
            World world = position.getWorld();
            double length = Math.min(COIN_SPEED, range - travelled);
            if (length <= 0) {
                drop(position);
                return false;
            }
            Vector move = direction.clone().multiply(length);
            LivingEntity hit = null;
            double best = Double.MAX_VALUE;
            for (LivingEntity enemy : ctx().enemiesNear(owner, position, length + 2)) {
                BoundingBox box = enemy.getBoundingBox().expand(COIN_HIT_GROW);
                RayTraceResult trace = box.rayTrace(position.toVector(), direction, length);
                double distance = box.contains(position.toVector()) ? 0
                        : trace == null ? Double.MAX_VALUE : trace.getHitPosition().distance(position.toVector());
                if (distance < best) {
                    best = distance;
                    hit = enemy;
                }
            }
            RayTraceResult wall = world.rayTraceBlocks(position, direction, length, FluidCollisionMode.NEVER, true);
            double wallDistance = wall == null ? Double.MAX_VALUE
                    : wall.getHitPosition().distance(position.toVector());
            if (hit != null && best <= wallDistance) {
                Location at = position.clone().add(direction.clone().multiply(best));
                stick(hit, at);
                volley.hit(hit, at);
                return false;
            }
            if (wall != null) {
                Location at = wall.getHitPosition().toLocation(world).subtract(direction.clone().multiply(0.15));
                drop(at);
                return false;
            }
            position.add(move);
            travelled += length;
            if (display != null && display.isValid()) {
                Displays.moveTo(display, position);
                Displays.animate(display, coinPose(direction, (tick + 1) * 55f + index * 20f, 1f), 1);
            }
            if (tick % 2 == 0) {
                Vfx.dot(position, Particle.DUST, GOLD_SMALL, false);
            }
            if (tick % 3 == index % 3) {
                Vfx.dot(position, Particle.WAX_OFF, null, false);
            }
            return true;
        }

        /** The coin sticks to the enemy's armour and melts into it. */
        private void stick(LivingEntity enemy, Location at) {
            BlockDisplay coin = display;
            if (coin == null || !coin.isValid()) {
                return;
            }
            if (isHidden(enemy)) {
                coin.remove();
                return;
            }
            Displays.moveTo(coin, at);
            Displays.shrinkAway(ctx(), volley.owner, coin, 3);
        }

        /** A miss: the coin pings off the ground or a wall and is gone. */
        private void drop(Location at) {
            Fx.sound(at, Sound.BLOCK_CHAIN_PLACE, 0.5f, 1.9f + Fx.jitter(0.1f));
            at.getWorld().spawnParticle(Particle.WAX_OFF, at, Vfx.n(ctx(), 2), 0.1, 0.1, 0.1, 0);
            BlockDisplay coin = display;
            if (coin != null && coin.isValid()) {
                Displays.moveTo(coin, at.clone().add(0, -0.2, 0));
                Displays.shrinkAway(ctx(), volley.owner, coin, 4);
            }
        }
    }

    /** A coin: a thin gold plate standing across its flight, spinning around the flight direction. */
    private static Transformation coinPose(Vector direction, float spinDeg, float size) {
        Vector3f forward = new Vector3f((float) direction.getX(), (float) direction.getY(), (float) direction.getZ());
        if (forward.lengthSquared() < 1.0E-6f) {
            forward.set(0, 0, 1);
        }
        forward.normalize();
        Quaternionf rotation = new Quaternionf().rotationTo(new Vector3f(0, 0, 1), forward)
                .rotateZ((float) Math.toRadians(spinDeg)).rotateZ((float) Math.toRadians(45));
        float s = COIN_SIZE * size;
        Vector3f scale = new Vector3f(s, s, COIN_THICK);
        Vector3f translation = new Quaternionf(rotation).transform(new Vector3f(scale).mul(-0.5f));
        return new Transformation(translation, rotation, scale, new Quaternionf());
    }

    private static Location hand(Player owner) {
        Location eye = owner.getEyeLocation();
        return eye.add(eye.getDirection().multiply(0.55)).subtract(0, 0.3, 0);
    }

    // ------------------------------------------------------------------ ultimate: the golden touch

    @Override
    public ActivationResult onUltimate() {
        Touch running = touch;
        if (running != null && !running.over) {
            return ActivationResult.fail("이미 황금이 번지고 있습니다");
        }
        Player owner = player();
        Ledger book = ledger;
        boolean borrowed = book == null;
        if (borrowed) {
            book = new Ledger(owner, false);
            book.open();
        }
        Touch spell = new Touch(owner, book, borrowed);
        touch = spell;
        spell.begin();
        return ActivationResult.success();
    }

    /**
     * The golden touch: a gold floor spreads under the king and follows him; whoever stands on it is gilded piece by
     * piece. The king himself turns to gold: no healing, less damage. At the end the tax is collected from every gilded
     * enemy around.
     */
    private final class Touch implements LongPredicate, Listener {

        private final Player owner;
        private final Ledger book;
        private final boolean borrowed;
        private final long duration;
        private final double radius;
        private final long gildEvery;
        private final List<BlockDisplay> floor = new ArrayList<>();
        private @Nullable TextDisplay crown;
        private double lastHealth;
        private boolean registered;
        private boolean over;

        Touch(Player owner, Ledger book, boolean borrowed) {
            this.owner = owner;
            this.book = book;
            this.borrowed = borrowed;
            this.duration = Math.max(1, params().ticks("touch_duration"));
            this.radius = Math.max(1, params().num("touch_radius"));
            this.gildEvery = Math.max(1, params().ticks("touch_gild_interval"));
        }

        void begin() {
            Location ground = floorSpot();
            long life = duration + 12;
            // Two gold squares turned 45 degrees make an eight-pointed sunburst whose points reach the edge of the gold;
            // a purple inlay and a marble core lie on top.
            float side = (float) (radius * Math.sqrt(2));
            float[][] layers = {
                    {side, 0f, 0f},
                    {side, 45f, 0.004f},
                    {(float) (radius * 0.75), 22.5f, 0.008f},
                    {(float) (radius * 0.36), 0f, 0.012f},
            };
            Material[] materials = {FLOOR, FLOOR, FLOOR_INLAY, FLOOR_CORE};
            for (int i = 0; i < layers.length && reserve(1); i++) {
                float[] layer = layers[i];
                BlockDisplay plate = keep(Displays.block(ctx(), owner, ground, materials[i].createBlockData(),
                        floorPose(layer[0], layer[1], layer[2], 0.02f), life));
                plate.setTeleportDuration(2);
                floor.add(plate);
                book.anchors.add(plate.getUniqueId());
            }
            ctx().runLater(owner, 1, () -> {
                for (int i = 0; i < floor.size(); i++) {
                    float[] layer = layers[i];
                    Displays.animate(floor.get(i), floorPose(layer[0], layer[1], layer[2], 1f), FLOOR_GROW_TICKS);
                }
            });
            if (reserve(1)) {
                crown = keep(Displays.text(ctx(), owner, crownSpot(), Text.mm("<" + GOLD_TAG + "><b>♛</b></"
                        + GOLD_TAG + ">"), 1.1f, life));
                crown.setTeleportDuration(1);
            }
            lastHealth = owner.getHealth();
            Bukkit.getPluginManager().registerEvents(this, ctx().plugin());
            registered = true;

            Location chest = Fx.body(owner);
            Vfx.dot(chest, Particle.FLASH, PALETTE.body(), true);
            Vfx.shockwave(ctx(), owner, ground.clone().add(0, 0.1, 0), 0.5, radius, FLOOR_GROW_TICKS,
                    Particle.DUST_COLOR_TRANSITION, GILD, true);
            owner.getWorld().spawnParticle(Particle.WAX_ON, chest, Vfx.n(ctx(), 24), 0.6, 0.8, 0.6, 0, null, true);
            Vfx.helix(ctx(), owner.getLocation(), 0.8, owner.getHeight() + 0.6, 2, 2, 16, 0, Particle.DUST, GOLD, true);
            Fx.sound(chest, Sound.BLOCK_BEACON_ACTIVATE, 1.2f, 1.6f);
            Fx.sound(chest, Sound.ITEM_ARMOR_EQUIP_GOLD, 1.2f, 0.7f);
            Fx.sound(chest, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.0f, 0.8f);
            Fx.sound(chest, Sound.BLOCK_BELL_USE, 0.8f, 1.6f);
            owner.sendActionBar(Text.mm("<" + GOLD_TAG + "><b>황금 손길</b></" + GOLD_TAG + "> <gray>" + Fx.seconds(duration)
                    + "초 · </gray><" + PURPLE_TAG + ">저주: 회복 불가</" + PURPLE_TAG + ">"));
            Loop.repeat(ctx(), owner, 1, 1, duration, this, () -> end(true));
        }

        /** Where the floor lies: on the ground under the king (it stays there when he jumps). */
        private Location floorSpot() {
            Location feet = owner.getLocation();
            Location probe = feet.clone();
            Spots.snapToGround(owner.getWorld(), probe, FLOOR_SNAP);
            return Displays.unrotated(probe.add(0, 0.02, 0));
        }

        private Location crownSpot() {
            return Displays.unrotated(owner.getLocation().add(0, owner.getHeight() + 0.45, 0));
        }

        @Override
        public boolean test(long tick) {
            if (over) {
                return false;
            }
            book.tick(tick);
            Location ground = floorSpot();
            for (BlockDisplay plate : floor) {
                Displays.moveTo(plate, ground);
            }
            if (crown != null) {
                Displays.moveTo(crown, crownSpot());
            }
            curse();
            goldSkin();
            if ((tick + 1) % gildEvery == 0) {
                spread(ground);
            }
            drawRim(ground, tick);
            long left = duration - tick - 1;
            if (left == TAX_WARNING) {
                warnTax();
            }
            return true;
        }

        /** No healing under the curse: whatever came back since the last tick is taken again. */
        private void curse() {
            double health = owner.getHealth();
            if (health > lastHealth + 1.0E-9) {
                owner.setHealth(Math.max(0.5, Math.min(health, lastHealth)));
                health = owner.getHealth();
                Location chest = Fx.body(owner);
                chest.getWorld().spawnParticle(Particle.DUST, chest, Vfx.n(ctx(), 3), 0.2, 0.3, 0.2, 0, PURPLE);
            }
            lastHealth = health;
        }

        /** The king's own armour shows as gold to everyone else while the curse lasts. */
        private void goldSkin() {
            Map<EquipmentSlot, ItemStack> gold = new EnumMap<>(EquipmentSlot.class);
            for (EquipmentSlot slot : GILD_ORDER) {
                gold.put(slot, new ItemStack(goldItem(slot)));
            }
            for (Player viewer : viewers(owner)) {
                viewer.sendEquipmentChange(owner, gold);
            }
        }

        private void restoreSkin() {
            for (Player viewer : viewers(owner)) {
                for (EquipmentSlot slot : GILD_ORDER) {
                    viewer.sendEquipmentChange(owner, slot, worn(owner, slot));
                }
            }
        }

        /** Everyone standing on the gold gets one more piece. */
        private void spread(Location ground) {
            for (LivingEntity enemy : ctx().enemiesNear(owner, ground, radius + 1)) {
                Location feet = enemy.getLocation();
                double dy = feet.getY() - ground.getY();
                if (Math.hypot(feet.getX() - ground.getX(), feet.getZ() - ground.getZ()) > radius
                        || dy < -ON_GOLD_BELOW || dy > ON_GOLD_ABOVE) {
                    continue;
                }
                if (book.gild(enemy) && !isHidden(enemy)) {
                    // Gold creeping up from the floor.
                    Vfx.line(ctx(), feet.clone().add(0, 0.05, 0), Fx.body(enemy), 5, Particle.DUST, GOLD_SMALL, false);
                }
            }
        }

        private void drawRim(Location ground, long tick) {
            if (tick % 3 != 0) {
                return;
            }
            Location rim = ground.clone().add(0, 0.12, 0);
            int points = (int) Math.max(16, Math.min(40, radius * 5));
            Vfx.ring(ctx(), rim, radius, points, tick * 0.03, Particle.DUST_COLOR_TRANSITION, ROYAL, true);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            World world = ground.getWorld();
            for (int i = 0; i < Vfx.n(ctx(), 3); i++) {
                Location at = Vfx.scatter(ground, radius).add(0, 0.15 + random.nextDouble() * 0.4, 0);
                world.spawnParticle(Particle.WAX_ON, at, 1, 0, 0, 0, 0);
            }
            if (tick % 12 == 0) {
                Fx.sound(owner.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.5f, 1.8f + Fx.jitter(0.1f));
            }
        }

        /** A second before the tax: every gilded enemy glints and hears the coins. */
        private void warnTax() {
            double taxRadius = Math.max(0, params().num("tax_radius"));
            for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), taxRadius)) {
                if (book.pieces(enemy) <= 0) {
                    continue;
                }
                if (!isHidden(enemy)) {
                    Location head = enemy.getLocation().add(0, enemy.getHeight() + 0.3, 0);
                    head.getWorld().spawnParticle(Particle.WAX_ON, head, Vfx.n(ctx(), 6), 0.25, 0.1, 0.25, 0, null,
                            true);
                    Fx.sound(head, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, 1.4f);
                }
                if (enemy instanceof Player victim) {
                    victim.sendActionBar(Text.mm("<" + GOLD_TAG + "><b>세금 징수 1초 전!</b></" + GOLD_TAG + "> <gray>"
                            + "미다스에게서 멀어지세요</gray>"));
                }
            }
        }

        /** The spell is over: the tax (if the king still stands), the curse lifts, the floor sinks. */
        void end(boolean collect) {
            if (over) {
                return;
            }
            over = true;
            if (touch == this) {
                touch = null;
            }
            if (registered) {
                HandlerList.unregisterAll(this);
                registered = false;
            }
            if (owner.isOnline()) {
                restoreSkin();
            }
            if (collect && ctx().isAlive(owner)) {
                tax();
            }
            if (borrowed) {
                book.release();
            }
            for (BlockDisplay plate : floor) {
                if (plate.isValid()) {
                    Displays.shrinkAway(ctx(), owner, plate, 8);
                }
            }
            if (crown != null && crown.isValid()) {
                Displays.shrinkAway(ctx(), owner, crown, 6);
            }
        }

        /** Every gilded piece around cracks at once: damage per piece, and the king pockets a little health. */
        private void tax() {
            double taxRadius = Math.max(0, params().num("tax_radius"));
            double perPiece = Math.max(0, params().num("tax_damage"));
            double cap = Math.max(0, params().num("tax_max"));
            double heal = Math.max(0, params().num("tax_heal"));
            Location purse = Fx.body(owner);
            int total = 0;
            int victims = 0;
            for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), taxRadius)) {
                int pieces = book.collect(enemy);
                if (pieces <= 0) {
                    continue;
                }
                total += pieces;
                victims++;
                plating++;
                try {
                    Combat.hit(ctx(), owner, enemy, Math.min(cap, perPiece * pieces));
                } finally {
                    plating--;
                }
                if (!isHidden(enemy) && enemy.getWorld().equals(purse.getWorld())) {
                    Location from = Fx.body(enemy);
                    int motes = Vfx.n(ctx(), TAX_TRAIL_MOTES * pieces);
                    for (int i = 0; i < motes; i++) {
                        Vfx.trail(Vfx.scatter(from, 0.4), purse, i % 3 == 0 ? PALETTE.core() : COIN_TRAIL,
                                (int) Math.max(6, Math.min(30, from.distance(purse) * 1.5)) + i, true);
                    }
                }
            }
            World world = purse.getWorld();
            Fx.sound(purse, Sound.BLOCK_VAULT_OPEN_SHUTTER, 1.0f, 1.2f);
            if (total <= 0) {
                world.spawnParticle(Particle.DUST, purse, Vfx.n(ctx(), 8), 0.3, 0.4, 0.3, 0, MARBLE);
                owner.sendActionBar(Text.mm("<gray>거둘 세금이 없습니다</gray>"));
                return;
            }
            if (heal > 0) {
                ctx().heal(owner, heal * total);
            }
            Vfx.dot(purse, Particle.FLASH, PALETTE.core(), true);
            Vfx.burst(ctx(), purse, PALETTE, 0.9, Particle.WAX_ON, true);
            world.spawnParticle(Particle.TOTEM_OF_UNDYING, purse, Vfx.n(ctx(), 14), 0.4, 0.5, 0.4, 0.2, null, true);
            Fx.sound(purse, Sound.BLOCK_VAULT_EJECT_ITEM, 1.2f, 0.8f);
            Fx.sound(purse, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.6f);
            Fx.sound(purse, Sound.BLOCK_GLASS_BREAK, 1.0f, 1.2f + Fx.jitter(0.05f));
            ctx().announce(Text.mm("<" + GOLD_TAG + "><b>세금 징수!</b></" + GOLD_TAG + "> <" + MARBLE_TAG + ">미다스가 "
                    + "</" + MARBLE_TAG + "><white>" + victims + "명</white><" + MARBLE_TAG + ">에게서 황금 " + total
                    + "부위를 거둬들였습니다</" + MARBLE_TAG + ">"));
            owner.sendActionBar(Text.mm("<" + GOLD_TAG + "><b>세금 징수</b></" + GOLD_TAG + "> <gray>황금 " + total
                    + "부위 · 체력 +" + AbilityInfo.formatNumber(heal * total) + "</gray>"));
        }

        /** The golden skin: the king takes less damage while the curse lasts. */
        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onHurt(EntityDamageEvent event) {
            if (over || !event.getEntity().equals(owner)) {
                return;
            }
            double reduction = Math.clamp(params().num("touch_reduction") / 100.0, 0.0, 1.0);
            if (reduction > 0 && event.getDamage() > 0) {
                event.setDamage(event.getDamage() * (1 - reduction));
                Location chest = Fx.body(owner);
                chest.getWorld().spawnParticle(Particle.WAX_OFF, chest, Vfx.n(ctx(), 3), 0.25, 0.35, 0.25, 0);
                Fx.sound(chest, Sound.BLOCK_CHAIN_HIT, 0.6f, 1.4f + Fx.jitter(0.1f));
            }
        }

        /** The curse: nothing heals the king (natural regeneration, food, potions). */
        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onHeal(EntityRegainHealthEvent event) {
            if (!over && event.getEntity().equals(owner)) {
                event.setCancelled(true);
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onOwnerDeath(PlayerDeathEvent event) {
            if (event.getEntity().equals(owner)) {
                end(false);
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onOwnerQuit(PlayerQuitEvent event) {
            if (event.getPlayer().equals(owner)) {
                end(false);
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onRemove(EntityRemoveEvent event) {
            if (!over && floor.stream().anyMatch(plate -> plate.getUniqueId().equals(event.getEntity().getUniqueId()))) {
                end(false);
            }
        }
    }

    /** A flat floor plate of the given width, turned by {@code yawDeg}, lifted by {@code lift}, grown to {@code grow}. */
    private static Transformation floorPose(float width, float yawDeg, float lift, float grow) {
        float w = Math.max(0.01f, width * grow);
        return Displays.transform(0, lift + FLOOR_THICK / 2, 0, w, FLOOR_THICK, w, yawDeg, 0, 0);
    }

    // ------------------------------------------------------------------ helpers

    /** True for a player the king cannot see (stealth): no visual may give it away. */
    private boolean isHidden(LivingEntity entity) {
        return entity instanceof Player hidden && !player().canSee(hidden);
    }

    private <T extends Display> T keep(T display) {
        displays.add(display);
        return display;
    }

    private boolean reserve(int count) {
        displays.removeIf(display -> !display.isValid());
        return displays.size() + count <= MAX_DISPLAYS;
    }
}
