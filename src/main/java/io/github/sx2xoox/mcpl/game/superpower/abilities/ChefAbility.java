package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;

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
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Consumable;
import io.papermc.paper.datacomponent.item.FoodProperties;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.type.Candle;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 요리사 — a cook. Every so often a fresh snack lands in the hotbar (it can be eaten even when full and heals the cook
 * or a teammate), the active whacks the enemies in front with a frying pan — a ringing "팅!", a strong knockback and
 * a short stun — and the ultimate lays a dinner table that feeds and buffs the cook's team while it lobs hot dishes at
 * the enemies around.
 *
 * <p>Snacks are real food items marked as the cook's (removed from the inventory when the ability is revoked), with
 * data components that make them quick to eat and edible at full hunger; eating one is caught with a consume
 * listener that lives exactly as long as the ability.
 *
 * <p>Look: cream, tomato red and warm brown. The pan is a dark disc on a wooden handle swung as one body (the turn
 * folded into its parts' transformations; displays never carry a rotation of their own), the hit pops a "팅!" sign, a fried egg flies off and a gold star circles the stunned head. The table assembles
 * leg by leg (top, white cloth, red runner, a cake, dishes and a lit candle), steams while it serves, and every hot
 * dish is a spinning food item on a telegraphed arc that shatters like a plate where it lands.
 */
public final class ChefAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("chef", "요리사", Material.CAKE)
            .color(TextColor.color(0xE8505B))
            .difficulty(1)
            .passive("간식",
                    "{snack_interval}초마다 갓 만든 간식을 하나 받습니다(최대 {snack_max}개). 간식은 배가 불러도 "
                            + "{snack_eat_time}초 만에 먹을 수 있고 체력을 {snack_heal} 회복시킵니다. 아군이 먹어도 "
                            + "회복하며, 경기가 끝나면 사라집니다.")
            .active("프라이팬",
                    "앞쪽 {pan_range}블록 안의 적들을 프라이팬으로 후려쳐 {pan_damage} 피해를 주고 강하게 날려 보내며 "
                            + "{pan_stun}초간 기절시킵니다(이동·점프 불가).",
                    7)
            .ultimate("만찬",
                    "앞에 {ult_duration}초간 식탁을 차립니다. 식탁 반경 {ult_radius}블록의 나와 아군은 곧바로 {ult_heal} "
                            + "회복하고 이후 초당 {ult_regen} 회복하며 신속 I·힘 I을 얻습니다. 식탁은 "
                            + "{ult_throw_interval}초마다 반경 {ult_throw_radius}블록의 적 최대 {ult_throw_targets}명에게 "
                            + "뜨거운 요리를 던져, 떨어진 자리 반경 {ult_splash}블록에 {ult_throw_damage} 피해를 줍니다.")
            .param("snack_interval", 20)
            .param("snack_max", 2)
            .param("snack_heal", 4)
            .param("snack_eat_time", 1)
            .param("pan_range", 3.5)
            .param("pan_angle", 100)
            .param("pan_damage", 4)
            .param("pan_stun", 0.5)
            .param("ult_duration", 8)
            .param("ult_radius", 6)
            .param("ult_heal", 4)
            .param("ult_regen", 1)
            .param("ult_throw_radius", 10)
            .param("ult_throw_interval", 1.2)
            .param("ult_throw_targets", 3)
            .param("ult_throw_damage", 2)
            .param("ult_splash", 1.5)
            .build();

    private static final String COLOR_TAG = "#E8505B";
    private static final String CREAM_TAG = "#FFF3D6";

    // ------------------------------------------------------------------ look: cream, tomato red, warm brown
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFF3D6, 0xE8505B, 0x7A4A2A);
    private static final Particle.DustTransition WARM = PALETTE.glow(1.0f);
    private static final Particle.DustTransition EMBER = PALETTE.fade(0.9f);
    private static final Particle.DustOptions CREAM = new Particle.DustOptions(Color.fromRGB(0xFFF3D6), 1.0f);
    private static final Particle.DustOptions STAR = new Particle.DustOptions(Color.fromRGB(0xFFD54F), 0.8f);

    /** Upper bound of display entities alive at once; purely decorative ones are skipped past it. */
    private static final int DISPLAY_BUDGET = 24;
    /** Hard cap for every velocity this ability applies. */
    private static final double MAX_SPEED = 2.2;

    // ------------------------------------------------------------------ passive: snacks
    private static final String SNACK_KEY = "chef_snack";
    private static final long SNACK_CHECK_PERIOD = 20;
    private static final long SIZZLE_PERIOD = 100;
    private static final int SNACK_NUTRITION = 2;
    private static final float SNACK_SATURATION = 1.2f;
    private static final long SERVE_POP_TICKS = 16;
    private static final Snack[] SNACKS = {
            new Snack(Material.COOKIE, "갓 구운 쿠키"),
            new Snack(Material.BREAD, "따끈한 빵"),
            new Snack(Material.PUMPKIN_PIE, "호박 파이"),
            new Snack(Material.BAKED_POTATO, "버터 감자")};

    private record Snack(Material material, String name) {
    }

    // ------------------------------------------------------------------ active: frying pan
    private static final double PAN_KNOCK = 1.45;
    private static final double PAN_LIFT = 0.5;
    private static final double PAN_HEIGHT = 1.15;
    private static final long PAN_SWING_TICKS = 3;
    private static final double PAN_SWING_FROM = 75;
    private static final double PAN_SWING_TO = -45;
    private static final long PAN_HOLD_TICKS = 3;
    private static final Material PAN_BLOCK = Material.NETHERITE_BLOCK;
    private static final Material HANDLE_BLOCK = Material.DARK_OAK_PLANKS;
    private static final long DING_TICKS = 14;
    private static final long EGG_TICKS = 14;

    // ------------------------------------------------------------------ ultimate: feast
    private static final double TABLE_FORWARD = 2.2;
    private static final int GROUND_SNAP = 3;
    private static final long ASSEMBLE_TICKS = 12;
    private static final long FOLD_TICKS = 8;
    private static final long BUFF_TICKS = 30;
    private static final long FLIGHT_TICKS = 12;
    private static final double FLIGHT_APEX = 2.5;
    private static final long HOT_TEXT_TICKS = 14;
    private static final Material[] HOT_DISHES = {Material.COOKED_BEEF, Material.COOKED_CHICKEN,
            Material.BAKED_POTATO, Material.MUSHROOM_STEW, Material.RABBIT_STEW, Material.BEETROOT_SOUP,
            Material.COOKED_SALMON, Material.PUMPKIN_PIE};
    /** Dishes set on the table: item, x, z (in the table's frame) and turn. */
    private static final TableDish[] TABLE_DISHES = {
            new TableDish(Material.COOKED_CHICKEN, -0.62f, 0.12f, 20f),
            new TableDish(Material.BREAD, 0.6f, -0.15f, -35f),
            new TableDish(Material.APPLE, 0.32f, 0.25f, 0f)};

    private record TableDish(Material item, float x, float z, float turn) {
    }

    private final List<Display> displays = new ArrayList<>();
    private @Nullable SnackListener listener;
    private @Nullable NamespacedKey snackKey;
    private long nextSnack;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    // ------------------------------------------------------------------ passive: snacks

    @Override
    public void onGrant() {
        SnackListener created = new SnackListener();
        Bukkit.getPluginManager().registerEvents(created, ctx().plugin());
        listener = created;
        nextSnack = ctx().currentTick() + Math.max(1, params().ticks("snack_interval"));
    }

    @Override
    public void onRevoke() {
        if (listener != null) {
            HandlerList.unregisterAll(listener);
            listener = null;
        }
        displays.clear();
        // Only the snacks this ability handed out carry the marker, so a restored inventory is never touched.
        Player owner = player();
        PlayerInventory inventory = owner.getInventory();
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (isSnack(contents[slot])) {
                inventory.setItem(slot, null);
            }
        }
        if (isSnack(owner.getItemOnCursor())) {
            owner.setItemOnCursor(null);
        }
    }

    @Override
    public void onTick(long tick) {
        if (tick % SNACK_CHECK_PERIOD != 0) {
            return;
        }
        Player owner = player();
        long now = ctx().currentTick();
        long interval = Math.max(1, params().ticks("snack_interval"));
        int max = Math.max(0, params().integer("snack_max"));
        if (countSnacks(owner) >= max) {
            // The next snack starts cooking once one is eaten.
            nextSnack = now + interval;
            return;
        }
        if (now >= nextSnack) {
            if (cook(owner)) {
                nextSnack = now + interval;
            }
        } else if ((nextSnack - now) % SIZZLE_PERIOD < SNACK_CHECK_PERIOD) {
            // Visible passive: something is always sizzling while the next snack cooks.
            Location hand = owner.getLocation().add(0, owner.getHeight() * 0.55, 0)
                    .add(Motion.horizontalFacing(owner.getLocation()).multiply(0.45));
            Vfx.cloud(ctx(), hand, Particle.WHITE_SMOKE, 3, 0.1, 0.01, null, false);
            Fx.sound(hand, Sound.BLOCK_SMOKER_SMOKE, 0.25f, 1.4f + Fx.jitter(0.1f));
        }
    }

    /** Hands out a fresh snack; false if the inventory is full. */
    private boolean cook(Player owner) {
        Snack snack = SNACKS[ThreadLocalRandom.current().nextInt(SNACKS.length)];
        ItemStack item = snackItem(snack, owner);
        if (!owner.getInventory().addItem(item).isEmpty()) {
            return false;
        }
        Location head = owner.getLocation().add(0, owner.getHeight() + 0.4, 0);
        if (hasDisplayRoom()) {
            ItemDisplay pop = Displays.item(ctx(), owner, head, ItemStack.of(snack.material()),
                    Displays.centered(0, 0, 0, 0.2f, 0.2f, 0.2f, 0, 0, 0), SERVE_POP_TICKS);
            pop.setBillboard(Display.Billboard.VERTICAL);
            displays.add(pop);
            Displays.animateNextTick(ctx(), owner, pop, Displays.centered(0, 0.45f, 0, 0.6f, 0.6f, 0.6f, 0, 0, 0),
                    6);
            ctx().runLater(owner, SERVE_POP_TICKS - 5, () -> {
                if (pop.isValid()) {
                    Displays.shrinkAway(ctx(), owner, pop, 4);
                }
            });
        }
        Vfx.cloud(ctx(), head, Particle.WHITE_SMOKE, 5, 0.2, 0.02, null, false);
        Vfx.ring(ctx(), head.clone().subtract(0, 0.3, 0), 0.45, 10, 0, Particle.DUST_COLOR_TRANSITION, WARM, false);
        Fx.sound(head, Sound.BLOCK_NOTE_BLOCK_BELL, 0.7f, 1.6f);
        Fx.sound(head, Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.2f + Fx.jitter(0.1f));
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">간식 완성!</" + COLOR_TAG + "> <" + CREAM_TAG + ">"
                + snack.name() + "</" + CREAM_TAG + "> <gray>(체력 +"
                + AbilityInfo.formatNumber(params().num("snack_heal")) + ")</gray>"));
        return true;
    }

    private ItemStack snackItem(Snack snack, Player owner) {
        ItemStack item = ItemStack.of(snack.material());
        String heal = AbilityInfo.formatNumber(Math.max(0, params().num("snack_heal")));
        item.setData(DataComponentTypes.ITEM_NAME, Text.mm("<" + COLOR_TAG + ">요리사의 " + snack.name() + "</"
                + COLOR_TAG + ">"));
        item.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(
                Text.mm("<!italic><gray>먹으면 체력 <white>+" + heal + "</white> (배불러도 먹을 수 있음)"),
                Text.mm("<!italic><dark_gray>경기가 끝나면 사라집니다"))));
        item.setData(DataComponentTypes.FOOD, FoodProperties.food().nutrition(SNACK_NUTRITION)
                .saturation(SNACK_SATURATION).canAlwaysEat(true).build());
        item.setData(DataComponentTypes.CONSUMABLE, Consumable.consumable()
                .consumeSeconds((float) Math.max(0.05, params().num("snack_eat_time")))
                .animation(ItemUseAnimation.EAT).hasConsumeParticles(true).build());
        item.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        NamespacedKey key = snackKey();
        String id = owner.getUniqueId().toString();
        item.editPersistentDataContainer(data -> data.set(key, PersistentDataType.STRING, id));
        return item;
    }

    private boolean isSnack(@Nullable ItemStack item) {
        return item != null && !item.isEmpty() && player().getUniqueId().toString()
                .equals(item.getPersistentDataContainer().get(snackKey(), PersistentDataType.STRING));
    }

    private int countSnacks(Player owner) {
        int count = 0;
        for (ItemStack item : owner.getInventory().getContents()) {
            if (isSnack(item)) {
                count += item.getAmount();
            }
        }
        return count;
    }

    private NamespacedKey snackKey() {
        if (snackKey == null) {
            snackKey = new NamespacedKey(ctx().plugin(), SNACK_KEY);
        }
        return snackKey;
    }

    /** Eating one of this cook's snacks heals the cook or a teammate; for anybody else it is plain food. */
    private final class SnackListener implements Listener {

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onConsume(PlayerItemConsumeEvent event) {
            Player owner = player();
            Player eater = event.getPlayer();
            if (!isSnack(event.getItem()) || !ctx().isAlive(owner)
                    || !(eater.equals(owner) || ctx().isAlly(owner, eater))) {
                return;
            }
            ctx().heal(eater, Math.max(0, params().num("snack_heal")));
            Location mouth = eater.getEyeLocation().subtract(0, 0.2, 0);
            World world = mouth.getWorld();
            world.spawnParticle(Particle.ITEM, mouth, Vfx.n(ctx(), 8), 0.15, 0.1, 0.15, 0.06,
                    ItemStack.of(event.getItem().getType()));
            Vfx.cloud(ctx(), mouth.clone().add(0, 0.5, 0), Particle.HEART, 2, 0.3, 0, null, false);
            Vfx.ring(ctx(), eater.getLocation().add(0, 0.1, 0), 0.55, 12, 0, Particle.DUST_COLOR_TRANSITION, WARM,
                    false);
            Vfx.ring(ctx(), eater.getLocation().add(0, 0.6, 0), 0.45, 10, 0.3, Particle.DUST, CREAM, false);
            Fx.sound(mouth, Sound.ENTITY_PLAYER_BURP, 0.7f, 1.1f + Fx.jitter(0.1f));
            if (!eater.equals(owner)) {
                Vfx.trail(owner.getLocation().add(0, owner.getHeight() * 0.6, 0), mouth, PALETTE.body(), 10, false);
            }
        }
    }

    // ------------------------------------------------------------------ active: frying pan

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        List<LivingEntity> targets = panTargets(owner);
        // Only enemies the cook can see make the pan swing: going off (and on cooldown) when the only one in reach is
        // stealthed would tell the cook that someone hidden stands right in front. Once it swings, it hits them all.
        int seen = (int) targets.stream().filter(target -> visibleTo(owner, target)).count();
        if (seen == 0) {
            return ActivationResult.fail("프라이팬이 닿는 곳에 적이 없습니다");
        }
        Vector facing = Motion.horizontalFacing(owner.getLocation());
        swingPan(owner);
        long stun = params().ticks("pan_stun");
        double damage = Math.max(0, params().num("pan_damage"));
        Location contact = null;
        for (LivingEntity target : targets) {
            Combat.hit(ctx(), owner, target, damage);
            Location body = Fx.body(target);
            // A stealthed enemy is hit all the same, but no sign or star is drawn over it to give it away.
            boolean shown = visibleTo(owner, target);
            if (shown && contact == null) {
                contact = body;
            }
            if (!Combat.isTargetable(ctx(), owner, target)) {
                continue;
            }
            Vector away = Motion.horizontal(target.getLocation().toVector().subtract(owner.getLocation().toVector()));
            if (away.lengthSquared() < 1.0E-6) {
                away = facing.clone();
            }
            Motion.push(target, away.multiply(PAN_KNOCK).setY(PAN_LIFT), MAX_SPEED);
            if (stun > 0) {
                ctx().addModifier(owner, target, Attribute.MOVEMENT_SPEED, -1.0, Operation.MULTIPLY_SCALAR_1, stun);
                ctx().addModifier(owner, target, Attribute.JUMP_STRENGTH, -1.0, Operation.MULTIPLY_SCALAR_1, stun);
                if (shown) {
                    dizzy(owner, target, stun);
                }
            }
            if (!shown) {
                continue;
            }
            World world = body.getWorld();
            world.spawnParticle(Particle.FLASH, body, 1, 0, 0, 0, 0, PALETTE.core());
            world.spawnParticle(Particle.CRIT, body, Vfx.n(ctx(), 12), 0.3, 0.35, 0.3, 0.3);
            world.spawnParticle(Particle.ENCHANTED_HIT, body, Vfx.n(ctx(), 8), 0.3, 0.3, 0.3, 0.2);
            Vfx.ring(ctx(), body, 0.7, 14, 0, Particle.DUST_COLOR_TRANSITION, WARM, false);
        }
        if (contact != null) {
            ding(owner, contact, seen);
            friedEgg(owner, contact, facing);
        }
        return ActivationResult.success();
    }

    /** Every enemy within reach in front of the cook, with a clear line to it. */
    private List<LivingEntity> panTargets(Player owner) {
        Location feet = owner.getLocation();
        Location chest = feet.clone().add(0, owner.getHeight() * 0.6, 0);
        Vector facing = Motion.horizontalFacing(feet);
        double range = Math.max(0.5, params().num("pan_range"));
        double cos = Math.cos(Math.toRadians(Math.clamp(params().num("pan_angle"), 1, 360) / 2));
        List<LivingEntity> found = new ArrayList<>();
        for (LivingEntity enemy : ctx().enemiesNear(owner, chest, range)) {
            Vector offset = enemy.getLocation().toVector().subtract(feet.toVector()).setY(0);
            double distance = offset.length();
            // Enemies hugging the cook count as in front; otherwise the bearing must be inside the cone.
            if (distance > enemy.getWidth() && offset.multiply(1 / distance).dot(facing) < cos) {
                continue;
            }
            if (Spots.clearPath(chest, Fx.body(enemy))) {
                found.add(enemy);
            }
        }
        return found;
    }

    /**
     * The pan sweeps across the front: a dark disc on a wooden handle, turned as one body around the cook's shoulder
     * (the turn lives in the parts' transformations; the displays stay unrotated).
     */
    private void swingPan(Player owner) {
        float facing = owner.getLocation().getYaw();
        Location anchor = panAnchor(owner);
        long lifetime = PAN_SWING_TICKS + PAN_HOLD_TICKS + 8;
        Transformation[] shape = {
                Displays.transform(0, 0, 0.7f, 0.08f, 0.08f, 0.8f, 0, 0, 0),
                Displays.transform(0, 0, 1.45f, 0.07f, 0.72f, 0.72f, 0, 0, 0),
                Displays.transform(0, 0, 1.45f, 0.07f, 0.52f, 0.86f, 0, 0, 0)};
        Material[] materials = {HANDLE_BLOCK, PAN_BLOCK, PAN_BLOCK};
        List<BlockDisplay> parts = new ArrayList<>();
        for (int i = 0; i < shape.length; i++) {
            BlockDisplay part = Displays.block(ctx(), owner, anchor, materials[i].createBlockData(),
                    turned(shape[i], (float) (facing + PAN_SWING_FROM), 0), lifetime);
            part.setTeleportDuration(1);
            displays.add(part);
            parts.add(part);
        }
        Fx.sound(owner.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.9f, 1.3f + Fx.jitter(0.1f));
        Fx.sound(owner.getLocation(), Sound.ITEM_ARMOR_EQUIP_IRON, 0.8f, 1.6f + Fx.jitter(0.1f));
        Loop.repeat(ctx(), owner, 1, 1, PAN_SWING_TICKS + PAN_HOLD_TICKS, run -> {
            double t = Math.min(1.0, (double) (run + 1) / PAN_SWING_TICKS);
            double yaw = facing + PAN_SWING_FROM + (PAN_SWING_TO - PAN_SWING_FROM) * t;
            Location at = panAnchor(owner);
            for (int i = 0; i < parts.size(); i++) {
                BlockDisplay part = parts.get(i);
                if (part.isValid()) {
                    Displays.moveTo(part, at);
                    Displays.animate(part, turned(shape[i], (float) yaw, 0), 1);
                }
            }
            if (run < PAN_SWING_TICKS) {
                Vfx.arc(ctx(), at, 1.45, yaw, 40, 6, Particle.DUST_COLOR_TRANSITION, EMBER, false);
                Vfx.dot(at.clone().add(direction(yaw).multiply(1.45)), Particle.SMOKE, null, false);
            }
            return true;
        }, () -> {
            for (BlockDisplay part : parts) {
                if (part.isValid()) {
                    Displays.shrinkAway(ctx(), owner, part, 4);
                }
            }
        });
    }

    /** The pan's pivot at the cook's shoulder (a plain position: the swing's turn is in the transformations). */
    private static Location panAnchor(Player owner) {
        return Displays.unrotated(owner.getLocation().add(0, PAN_HEIGHT, 0));
    }

    /** The ringing "팅!": a sign popping off the first enemy hit, with metal-on-skull sounds. */
    private void ding(Player owner, Location contact, int hits) {
        Fx.sound(contact, Sound.BLOCK_ANVIL_LAND, 0.55f, 2.0f);
        Fx.sound(contact, Sound.BLOCK_BELL_USE, 1.0f, 1.8f + Fx.jitter(0.05f));
        Fx.sound(contact, Sound.ITEM_SHIELD_BLOCK, 0.9f, 1.5f + Fx.jitter(0.1f));
        Fx.sound(contact, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 1.0f + Fx.jitter(0.1f));
        if (hasDisplayRoom()) {
            TextDisplay sign = Displays.text(ctx(), owner, contact.clone().add(0, 0.7, 0),
                    Text.mm("<b><gradient:#FFFFFF:#FFD54F>팅!</gradient></b>"), 1.0f, DING_TICKS);
            displays.add(sign);
            Displays.animateNextTick(ctx(), owner, sign, Displays.centered(0, 0.6f, 0, 1.9f, 1.9f, 1.9f, 0, 0, 0), 4);
            ctx().runLater(owner, DING_TICKS - 5, () -> {
                if (sign.isValid()) {
                    Displays.shrinkAway(ctx(), owner, sign, 4);
                }
            });
        }
        if (hits > 1) {
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">프라이팬</" + COLOR_TAG + "> <white>" + hits
                    + "명 팅!</white>"));
        }
    }

    /** A fried egg is knocked off the pan, tumbles through the air and splats. */
    private void friedEgg(Player owner, Location contact, Vector facing) {
        if (!hasDisplayRoom()) {
            return;
        }
        Location start = contact.clone().add(0, 0.4, 0);
        start.setYaw(0);
        start.setPitch(0);
        ItemDisplay white = Displays.item(ctx(), owner, start, ItemStack.of(Material.WHITE_CONCRETE),
                Displays.centered(0, 0, 0, 0.42f, 0.04f, 0.38f, 0, 0, 0), EGG_TICKS + 4);
        ItemDisplay yolk = Displays.item(ctx(), owner, start, ItemStack.of(Material.YELLOW_CONCRETE),
                Displays.centered(0, 0.03f, 0, 0.16f, 0.06f, 0.16f, 0, 0, 0), EGG_TICKS + 4);
        displays.add(white);
        displays.add(yolk);
        white.setTeleportDuration(1);
        yolk.setTeleportDuration(1);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Vector velocity = facing.clone().multiply(0.18).add(new Vector(random.nextDouble(-0.08, 0.08), 0.42,
                random.nextDouble(-0.08, 0.08)));
        Location pos = start.clone();
        Loop.repeat(ctx(), owner, 1, 1, EGG_TICKS, run -> {
            pos.add(velocity);
            velocity.setY(velocity.getY() - 0.07);
            Block below = pos.getBlock();
            boolean landed = !below.isPassable();
            if (landed) {
                pos.setY(Math.floor(pos.getY()) + 1.02);
            }
            float tumble = landed ? 0 : run * 47f;
            for (ItemDisplay part : List.of(white, yolk)) {
                if (part.isValid()) {
                    Displays.moveTo(part, pos);
                    float lift = part == yolk ? 0.03f : 0f;
                    float w = part == yolk ? 0.16f : 0.42f;
                    float h = part == yolk ? 0.06f : 0.04f;
                    float d = part == yolk ? 0.16f : 0.38f;
                    Displays.animate(part, Displays.centered(0, lift, 0, w, h, d, tumble, tumble * 0.6f, 0), 1);
                }
            }
            if (landed) {
                Vfx.cloud(ctx(), pos, Particle.ITEM, 6, 0.15, 0.05, ItemStack.of(Material.EGG), false);
                Fx.sound(pos, Sound.ENTITY_TURTLE_EGG_CRACK, 0.7f, 1.3f + Fx.jitter(0.1f));
                return false;
            }
            return true;
        }, () -> {
            for (ItemDisplay part : List.of(white, yolk)) {
                if (part.isValid()) {
                    Displays.shrinkAway(ctx(), owner, part, 6);
                }
            }
        });
    }

    /**
     * A gold star circling the stunned head for the length of the stun; it fades if the target slips into stealth
     * (the stun goes on), so it never marks where a hidden player is.
     */
    private void dizzy(Player owner, LivingEntity target, long ticks) {
        TextDisplay star = null;
        if (hasDisplayRoom()) {
            star = Displays.text(ctx(), owner, target.getLocation().add(0, target.getHeight() + 0.3, 0),
                    Text.mm("<#FFD54F>★</#FFD54F>"), 0.8f, ticks + 4);
            star.setTeleportDuration(1);
            displays.add(star);
        }
        TextDisplay shown = star;
        double phase = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        Loop.repeat(ctx(), owner, 0, 1, ticks, run -> {
            if (!target.isValid() || target.isDead() || !visibleTo(owner, target)) {
                return false;
            }
            double angle = phase + run * 0.7;
            Location head = target.getLocation().add(0, target.getHeight() + 0.3, 0);
            Location at = head.clone().add(Math.cos(angle) * 0.45, Math.sin(run * 0.9) * 0.05, Math.sin(angle) * 0.45);
            if (shown != null && shown.isValid()) {
                Displays.moveTo(shown, at);
            }
            Location opposite = head.clone().add(-Math.cos(angle) * 0.45, 0, -Math.sin(angle) * 0.45);
            Vfx.dot(opposite, Particle.DUST, STAR, false);
            if (run % 3 == 0) {
                Vfx.dot(at, Particle.WAX_ON, null, false);
            }
            return true;
        }, () -> {
            if (shown != null && shown.isValid()) {
                shown.remove();
            }
        });
    }

    // ------------------------------------------------------------------ ultimate: feast

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        Location spot = tableSpot(owner);
        if (spot == null) {
            return ActivationResult.fail("식탁을 차릴 땅이 없습니다");
        }
        new Feast(owner, spot).lay();
        return ActivationResult.success();
    }

    /** In front of the cook on the ground, or at the cook's feet when that is blocked or outside the arena. */
    private @Nullable Location tableSpot(Player owner) {
        Location feet = owner.getLocation();
        Vector facing = Motion.horizontalFacing(feet);
        Location ahead = feet.clone().add(facing.clone().multiply(TABLE_FORWARD));
        Location chest = feet.clone().add(0, 1.0, 0);
        Double top = groundBelow(ahead.clone().add(0, 1.0, 0));
        if (top != null && Spots.clearPath(chest, ahead.clone().add(0, 1.0, 0)) && ctx().isInsideArena(ahead)
                && ahead.clone().add(0, 0.5, 0).getBlock().isPassable()) {
            ahead.setY(top);
        } else {
            Double here = groundBelow(feet.clone().add(0, 0.5, 0));
            if (here == null) {
                return null;
            }
            ahead = feet.clone();
            ahead.setY(here);
        }
        ahead.setYaw(feet.getYaw());
        ahead.setPitch(0);
        return ahead;
    }

    /** One table part: its display and its final pose, revealed at {@code delay}. */
    private record TablePart(Display display, Transformation pose, long delay) {
    }

    /**
     * The feast: a dinner table that heals and buffs the cook's team around it and lobs hot dishes at enemies in
     * range.
     */
    private final class Feast implements LongPredicate {

        private final Player owner;
        private final Location table;
        private final World world;
        private final long duration;
        private final double radius;
        private final double throwRadius;
        private final long throwInterval;
        private final int throwTargets;
        private final double throwDamage;
        private final double splash;
        private final List<TablePart> parts = new ArrayList<>();
        private final List<Dish> dishes = new ArrayList<>();
        private final float tableYaw;
        private @Nullable Location candleTip;
        private long nextThrow = ASSEMBLE_TICKS + 6;

        Feast(Player owner, Location table) {
            this.owner = owner;
            this.table = table;
            this.world = table.getWorld();
            this.duration = Math.max(ASSEMBLE_TICKS + 20, params().ticks("ult_duration"));
            this.radius = Math.max(1, params().num("ult_radius"));
            this.throwRadius = Math.max(1, params().num("ult_throw_radius"));
            this.throwInterval = Math.max(5, params().ticks("ult_throw_interval"));
            this.throwTargets = Math.max(0, params().integer("ult_throw_targets"));
            this.throwDamage = Math.max(0, params().num("ult_throw_damage"));
            this.splash = Math.max(0.5, params().num("ult_splash"));
            this.tableYaw = table.getYaw();
        }

        void lay() {
            long lifetime = duration + FOLD_TICKS + FLIGHT_TICKS + 20;
            build(lifetime);
            Location floor = table.clone().add(0, 0.1, 0);
            Vfx.shockwave(ctx(), owner, floor, 0.4, radius, 8, Particle.DUST_COLOR_TRANSITION, WARM, true);
            Vfx.cloud(ctx(), floor.clone().add(0, 1, 0), Particle.WHITE_SMOKE, 16, 0.8, 0.02, null, true);
            Fx.sound(table, Sound.BLOCK_WOOD_PLACE, 1.2f, 0.8f);
            Fx.sound(table, Sound.ITEM_ARMOR_EQUIP_GENERIC, 1.0f, 1.2f);
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>만찬</b></" + COLOR_TAG + "> <gray>식사가 준비되는 "
                    + "중…</gray>"));
            Loop.repeat(ctx(), owner, 1, 1, lifetime, this, this::finish);
        }

        /** Legs, top, white cloth, red runner, a cake, three dishes and a lit candle. */
        private void build(long lifetime) {
            // The table stands broadside to the cook: its length (local x) runs across the cook's view.
            Location anchor = Displays.unrotated(table);
            for (int i = 0; i < 4; i++) {
                float x = i % 2 == 0 ? -0.85f : 0.85f;
                float z = i < 2 ? -0.38f : 0.38f;
                addBlock(anchor, Material.DARK_OAK_PLANKS, Displays.transform(x, 0.43f, z, 0.12f, 0.86f, 0.12f,
                        0, 0, 0), 1 + i / 2, lifetime);
            }
            addBlock(anchor, Material.SPRUCE_PLANKS, Displays.transform(0, 0.91f, 0, 2.0f, 0.1f, 1.0f, 0, 0, 0), 3,
                    lifetime);
            addBlock(anchor, Material.WHITE_WOOL, Displays.transform(0, 0.975f, 0, 2.12f, 0.04f, 1.12f, 0, 0, 0), 5,
                    lifetime);
            addBlock(anchor, Material.RED_WOOL, Displays.transform(0, 0.985f, 0, 2.14f, 0.045f, 0.34f, 0, 0, 0), 6,
                    lifetime);
            addBlock(anchor, Material.CAKE, Displays.transform(-0.05f, 1.29f, -0.05f, 0.6f, 0.6f, 0.6f, 15, 0, 0), 8,
                    lifetime);
            for (int i = 0; i < TABLE_DISHES.length; i++) {
                TableDish dish = TABLE_DISHES[i];
                ItemDisplay item = Displays.item(ctx(), owner, anchor, ItemStack.of(dish.item()),
                        turned(Displays.centered(dish.x(), 1.6f, dish.z(), 0.01f, 0.01f, 0.01f, dish.turn(), 90, 0),
                                tableYaw, 0), lifetime);
                item.setViewRange(2.0f);
                displays.add(item);
                parts.add(new TablePart(item, turned(Displays.centered(dish.x(), 1.03f, dish.z(), 0.42f, 0.42f,
                        0.42f, dish.turn(), 90, 0), tableYaw, 0), 9 + i));
            }
            BlockData candle = Material.CANDLE.createBlockData();
            if (candle instanceof Candle candles) {
                candles.setCandles(Math.min(3, candles.getMaximumCandles()));
            }
            if (candle instanceof Lightable lit) {
                lit.setLit(true);
            }
            addBlock(anchor, candle, Displays.transform(0.35f, 1.3f, -0.22f, 0.6f, 0.6f, 0.6f, 0, 0, 0), 11,
                    lifetime);
            candleTip = table.clone().add(rotated(0.35, -0.22, tableYaw)).add(0, 1.38, 0);
        }

        private void addBlock(Location anchor, Material material, Transformation pose, long delay, long lifetime) {
            addBlock(anchor, material.createBlockData(), pose, delay, lifetime);
        }

        /** Adds a block of the table; {@code local} is its pose in the table's own frame (length along x). */
        private void addBlock(Location anchor, BlockData data, Transformation local, long delay, long lifetime) {
            // Starts small and a little above its place, then drops into it.
            Transformation start = new Transformation(new Vector3f(local.getTranslation()).add(0, 1.0f, 0),
                    local.getLeftRotation(), new Vector3f(0.01f), local.getRightRotation());
            BlockDisplay display = Displays.block(ctx(), owner, anchor, data, turned(start, tableYaw, 0), lifetime);
            display.setViewRange(2.0f);
            displays.add(display);
            parts.add(new TablePart(display, turned(local, tableYaw, 0), delay));
        }

        @Override
        public boolean test(long tick) {
            for (TablePart part : parts) {
                if (part.delay() == tick && part.display().isValid()) {
                    Displays.animate(part.display(), part.pose(), 3);
                    Fx.sound(table, part.display() instanceof ItemDisplay ? Sound.ENTITY_ITEM_PICKUP
                            : Sound.BLOCK_WOOD_PLACE, 0.6f, 1.0f + 0.05f * tick);
                }
            }
            if (tick == ASSEMBLE_TICKS) {
                serve();
            }
            if (tick < duration) {
                if (tick > ASSEMBLE_TICKS && (tick - ASSEMBLE_TICKS) % 20 == 0) {
                    feed(false);
                }
                if (tick >= nextThrow) {
                    nextThrow = tick + throwInterval;
                    throwDishes();
                }
                ambience(tick);
            } else if (tick == duration) {
                fold();
            }
            Iterator<Dish> it = dishes.iterator();
            while (it.hasNext()) {
                if (it.next().update()) {
                    it.remove();
                }
            }
            return tick < duration + FOLD_TICKS || !dishes.isEmpty();
        }

        /** Dinner is served: the bell rings and everyone at the table is fed at once. */
        private void serve() {
            Location top = table.clone().add(0, 1.2, 0);
            world.spawnParticle(Particle.FLASH, top, 1, 0, 0, 0, 0, PALETTE.core(), true);
            Vfx.sphere(ctx(), top, 1.2, 28, Particle.DUST_COLOR_TRANSITION, WARM, true);
            Vfx.shockwave(ctx(), owner, table.clone().add(0, 0.15, 0), 0.5, radius, 6, Particle.DUST, CREAM, true);
            Fx.sound(top, Sound.BLOCK_BELL_USE, 1.4f, 1.2f);
            ctx().runLater(owner, 4, () -> Fx.sound(top, Sound.BLOCK_BELL_USE, 1.2f, 1.5f));
            Fx.sound(top, Sound.ENTITY_VILLAGER_CELEBRATE, 1.0f, 1.1f);
            Fx.sound(top, Sound.BLOCK_CAKE_ADD_CANDLE, 1.0f, 1.0f);
            feed(true);
        }

        /** Heals (a big first serving, then a little every second) and buffs the cook and allies at the table. */
        private void feed(boolean first) {
            double amount = Math.max(0, params().num(first ? "ult_heal" : "ult_regen"));
            Location top = table.clone().add(0, 1.1, 0);
            List<Player> diners = new ArrayList<>();
            if (owner.getWorld().equals(world) && owner.getLocation().distance(table) <= radius) {
                diners.add(owner);
            }
            diners.addAll(ctx().alliesNear(owner, table, radius));
            for (Player diner : diners) {
                ctx().heal(diner, amount);
                diner.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, (int) BUFF_TICKS, 0, true, true,
                        true));
                diner.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, (int) BUFF_TICKS, 0, true, true,
                        true));
                Location chest = diner.getLocation().add(0, diner.getHeight() * 0.6, 0);
                int motes = Vfx.n(ctx(), first ? 6 : 2);
                for (int i = 0; i < motes; i++) {
                    Vfx.trail(Vfx.scatter(top, 0.6), chest, i % 2 == 0 ? PALETTE.body() : PALETTE.core(), 10 + i,
                            false);
                }
                Vfx.ring(ctx(), diner.getLocation().add(0, 0.1, 0), 0.55, 12, 0, Particle.DUST_COLOR_TRANSITION, WARM,
                        false);
                if (first) {
                    Vfx.cloud(ctx(), chest.clone().add(0, 0.6, 0), Particle.HEART, 3, 0.3, 0, null, false);
                }
            }
        }

        /** Steam off the food and the candle flame. */
        private void ambience(long tick) {
            if (tick < ASSEMBLE_TICKS) {
                return;
            }
            if (tick % 3 == 0) {
                Vfx.dot(Vfx.scatter(table.clone().add(0, 1.2, 0), 0.8), Particle.WHITE_SMOKE, null, false);
            }
            if (candleTip != null && tick % 4 == 0) {
                Vfx.dot(candleTip, Particle.SMALL_FLAME, null, false);
            }
            if (tick % 10 == 0) {
                Vfx.ring(ctx(), table.clone().add(0, 0.12, 0), radius, (int) Math.min(40, Math.max(16, radius * 5)),
                        tick * 0.02, Particle.DUST_COLOR_TRANSITION, EMBER, false);
            }
        }

        /** A hot dish for each of the nearest visible enemies in range, aimed where they stand right now. */
        private void throwDishes() {
            if (throwTargets <= 0 || throwDamage <= 0) {
                return;
            }
            int thrown = 0;
            for (LivingEntity enemy : ctx().enemiesNear(owner, table, throwRadius)) {
                if (thrown >= throwTargets) {
                    break;
                }
                if (enemy instanceof Player hidden && !owner.canSee(hidden)) {
                    continue;
                }
                dishes.add(new Dish(enemy.getLocation()));
                thrown++;
            }
            if (thrown > 0) {
                Fx.sound(table, Sound.ENTITY_WITCH_THROW, 1.0f, 1.2f + Fx.jitter(0.1f));
                Fx.sound(table, Sound.ENTITY_SNOW_GOLEM_SHOOT, 0.7f, 0.9f + Fx.jitter(0.1f));
            }
        }

        /** The meal is over: the table is cleared and folds away. */
        private void fold() {
            int i = 0;
            for (TablePart part : parts) {
                Display display = part.display();
                if (display.isValid()) {
                    long delay = (parts.size() - 1 - i) / 3;
                    ctx().runLater(owner, delay, () -> {
                        if (display.isValid()) {
                            Displays.shrinkAway(ctx(), owner, display, (int) (FOLD_TICKS - delay));
                        }
                    });
                }
                i++;
            }
            Vfx.cloud(ctx(), table.clone().add(0, 1, 0), Particle.WHITE_SMOKE, 14, 0.8, 0.02, null, false);
            Fx.sound(table, Sound.ENTITY_PLAYER_BURP, 1.0f, 0.8f);
            Fx.sound(table, Sound.BLOCK_WOOL_PLACE, 0.8f, 0.8f);
        }

        private void finish() {
            for (TablePart part : parts) {
                if (part.display().isValid()) {
                    part.display().remove();
                }
            }
            parts.clear();
            for (Dish dish : dishes) {
                dish.remove();
            }
            dishes.clear();
        }

        /** One hot dish on its arc from the table; the landing spot is marked so it can be dodged. */
        private final class Dish {

            private final ItemDisplay display;
            private final Location from;
            private final Location to;
            private long age;

            Dish(Location target) {
                this.from = table.clone().add(0, 1.3, 0);
                this.from.setYaw(0);
                this.from.setPitch(0);
                Double ground = groundBelow(target.clone().add(0, 0.5, 0));
                this.to = target.clone();
                if (ground != null) {
                    this.to.setY(ground);
                }
                Material food = HOT_DISHES[ThreadLocalRandom.current().nextInt(HOT_DISHES.length)];
                this.display = Displays.item(ctx(), owner, from, ItemStack.of(food),
                        Displays.centered(0, 0, 0, 0.55f, 0.55f, 0.55f, 0, 0, 0), FLIGHT_TICKS + 6);
                display.setTeleportDuration(1);
                display.setViewRange(2.0f);
                displays.add(display);
                Vfx.ring(ctx(), to.clone().add(0, 0.12, 0), splash, (int) Math.max(10, splash * 9), 0,
                        Particle.DUST_COLOR_TRANSITION, WARM, false);
            }

            /** One tick; true once the dish has landed. */
            boolean update() {
                age++;
                if (!display.isValid()) {
                    return true;
                }
                double s = Math.min(1.0, (double) age / FLIGHT_TICKS);
                Vector at = from.toVector().multiply(1 - s).add(to.toVector().multiply(s))
                        .add(new Vector(0, 4 * FLIGHT_APEX * s * (1 - s), 0));
                Location location = at.toLocation(world);
                Displays.moveTo(display, location);
                if (age % 2 == 0) {
                    float spin = age * 40f;
                    Displays.animate(display, Displays.centered(0, 0, 0, 0.55f, 0.55f, 0.55f, spin, spin * 0.5f, 0),
                            2);
                    Vfx.dot(location, Particle.WHITE_SMOKE, null, false);
                    Vfx.dot(location, Particle.DUST_COLOR_TRANSITION, EMBER, false);
                }
                if (age == FLIGHT_TICKS / 2) {
                    Vfx.ring(ctx(), to.clone().add(0, 0.12, 0), splash * 0.6, 10, age * 0.3,
                            Particle.DUST_COLOR_TRANSITION, WARM, false);
                }
                if (age < FLIGHT_TICKS) {
                    return false;
                }
                land(location);
                return true;
            }

            /** The dish shatters like a plate: hot damage around the spot, steam and a puff of flame. */
            private void land(Location at) {
                display.remove();
                Location spot = to.clone().add(0, 0.3, 0);
                for (LivingEntity enemy : ctx().enemiesNear(owner, spot, splash)) {
                    Combat.hit(ctx(), owner, enemy, throwDamage);
                    if (Combat.isTargetable(ctx(), owner, enemy) && visibleTo(owner, enemy)) {
                        hot(enemy);
                    }
                }
                world.spawnParticle(Particle.ITEM, spot, Vfx.n(ctx(), 10), 0.25, 0.15, 0.25, 0.12,
                        display.getItemStack());
                world.spawnParticle(Particle.CLOUD, spot, Vfx.n(ctx(), 8), 0.4, 0.2, 0.4, 0.04);
                world.spawnParticle(Particle.FLAME, spot, Vfx.n(ctx(), 6), 0.3, 0.1, 0.3, 0.03);
                world.spawnParticle(Particle.LAVA, spot, Vfx.n(ctx(), 2), 0.2, 0.1, 0.2, 0);
                Vfx.ring(ctx(), to.clone().add(0, 0.15, 0), splash, (int) Math.max(10, splash * 9), 0,
                        Particle.DUST_COLOR_TRANSITION, EMBER, false);
                Fx.sound(at, Sound.BLOCK_DECORATED_POT_SHATTER, 0.9f, 1.2f + Fx.jitter(0.1f));
                Fx.sound(at, Sound.BLOCK_FIRE_EXTINGUISH, 0.6f, 1.5f + Fx.jitter(0.1f));
            }

            void remove() {
                if (display.isValid()) {
                    display.remove();
                }
            }
        }

        /** "앗 뜨거!" over an enemy hit by a hot dish. */
        private void hot(LivingEntity enemy) {
            Location head = enemy.getLocation().add(0, enemy.getHeight() + 0.45, 0);
            Fx.sound(head, Sound.ENTITY_GENERIC_BURN, 0.6f, 1.3f + Fx.jitter(0.1f));
            Vfx.cloud(ctx(), head, Particle.WHITE_SMOKE, 4, 0.2, 0.03, null, false);
            if (!hasDisplayRoom()) {
                return;
            }
            TextDisplay text = Displays.text(ctx(), owner, head, Text.mm("<b><gradient:#FFB74D:#E8505B>앗 뜨거!"
                    + "</gradient></b>"), 0.7f, HOT_TEXT_TICKS);
            displays.add(text);
            Displays.animateNextTick(ctx(), owner, text, Displays.centered(0, 0.45f, 0, 1.0f, 1.0f, 1.0f, 0, 0, 0),
                    5);
            ctx().runLater(owner, HOT_TEXT_TICKS - 4, () -> {
                if (text.isValid()) {
                    Displays.shrinkAway(ctx(), owner, text, 3);
                }
            });
        }
    }

    // ------------------------------------------------------------------ helpers

    private static boolean visibleTo(Player owner, LivingEntity entity) {
        return !(entity instanceof Player player) || owner.canSee(player);
    }

    private boolean hasDisplayRoom() {
        displays.removeIf(display -> !display.isValid());
        return displays.size() < DISPLAY_BUDGET;
    }

    /** The top of the first solid block at most {@link #GROUND_SNAP} blocks below {@code point}, null if none. */
    private static @Nullable Double groundBelow(Location point) {
        World world = point.getWorld();
        int x = point.getBlockX();
        int z = point.getBlockZ();
        int start = point.getBlockY();
        int end = Math.max(world.getMinHeight(), start - GROUND_SNAP);
        for (int y = Math.min(start, world.getMaxHeight() - 1); y >= end; y--) {
            Block block = world.getBlockAt(x, y, z);
            if (!block.isPassable()) {
                return block.getBoundingBox().getMaxY();
            }
        }
        return null;
    }

    /**
     * {@code local} (a pose in the rig's own frame, facing +z) turned as part of a rigid body by {@code yaw} and
     * {@code pitch} (Minecraft degrees: yaw 0 = +z, 90 = -x; positive pitch looks down). The rotation lives in the
     * transformation only, so the display entity keeps yaw/pitch 0 and is moved with {@link Displays#moveTo}.
     */
    private static Transformation turned(Transformation local, float yaw, float pitch) {
        Quaternionf turn = new Quaternionf().rotateY((float) Math.toRadians(-yaw))
                .rotateX((float) Math.toRadians(pitch));
        return new Transformation(turn.transform(new Vector3f(local.getTranslation())),
                new Quaternionf(turn).mul(local.getLeftRotation()), new Vector3f(local.getScale()),
                new Quaternionf(local.getRightRotation()));
    }

    /** A point of the table's own frame ({@code x} along its length, {@code z} across) in world space. */
    private static Vector rotated(double x, double z, float yawDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        double cos = Math.cos(yaw);
        double sin = Math.sin(yaw);
        // Display frames turn by -yaw around Y: local +x maps to (cos, 0, sin), local +z to (-sin, 0, cos).
        return new Vector(x * cos - z * sin, 0, x * sin + z * cos);
    }

    /** The horizontal unit vector of a Minecraft yaw. */
    private static Vector direction(double yawDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        return new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
    }
}
