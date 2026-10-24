package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Motion;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Trident;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 불가사리 — the iron-eating monster of the late Goryeo tale: kneaded from rice paste, it ate needles, pots and
 * weapons until it grew as big as a house. Blades and arrows could not kill it (不可殺伊), only fire could (火可殺伊).
 *
 * <p>Its melee bites gnaw the enemy's armour away and every swallowed scrap makes it a little bigger and sturdier; fire
 * is its weakness. The active bites one enemy and chews its weapon, so for a few seconds that enemy's blows only feed
 * the monster. The ultimate turns its body to iron: blades and plain projectiles are swallowed whole (and heal it),
 * abilities still hurt and fire hurts twice as much, and at the end the swallowed iron is spat out as shrapnel.
 *
 * <p>Look: rust brown, cast-iron grey and ember orange. Gnawing throws iron crumbs and drags motes of iron into the
 * monster's mouth; a gnawed enemy sheds rust flakes. The bite is a pair of iron jaws snapping shut on the victim, whose
 * weapon then floats over its head, wobbling and crunching while it is being chewed. The iron body is a set of riveted
 * plates clamped around the torso with glowing seams and a gulp counter; the finale is a ring of iron shards flung out
 * with a burp and a clang.
 */
public final class BulgasariAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("bulgasari", "불가사리", Material.RAW_IRON)
            .color(TextColor.color(0xB8683A))
            .difficulty(1)
            .passive("쇠붙이 먹보",
                    "근접 공격한 적의 갑옷을 갉아 {gnaw_duration}초간 방어력을 {gnaw_armor} 깎습니다(적마다 최대 "
                            + "{gnaw_max}번 겹침, 같은 적은 {gnaw_cooldown}초마다). 갉아 삼킬 때마다 최대 체력이 "
                            + "{grow_health} 늘고(그만큼 회복) 몸이 {grow_scale}% 커집니다(최대 {grow_max}번, 쓰러질 "
                            + "때까지 유지). 대신 불 피해(화상·불·용암과 화염술사·용암술사·드래곤·악마·불사조의 능력)를 "
                            + "{fire_weakness}% 더 받습니다.")
            .active("칼 씹어먹기",
                    "앞 {bite_range}블록·{bite_angle}° 안의 적 하나를 덥석 물어 {bite_damage} 피해를 주고 갑옷을 한 번 갉으며, 손에 든 "
                            + "무기를 우걱우걱 씹습니다. {chew_duration}초간 그 적의 근접 공격은 나에게 피해를 주지 못하고 "
                            + "먹이가 되어 오히려 내가 {chew_heal} 회복합니다({chew_heal_interval}초마다 한 번, 다른 "
                            + "사람에게는 그대로 아픕니다). 물 적이 없으면 쿨타임이 {miss_cooldown}%만 돕니다.",
                    9)
            .ultimate("불가살이",
                    "{iron_duration}초간 쇳덩이 몸이 됩니다. 적의 근접 공격과 화살·삼지창 같은 일반 투사체는 피해 없이 "
                            + "꿀꺽 삼켜져 체력을 {iron_heal} 회복합니다({iron_heal_interval}초마다 한 번). 능력 피해는 "
                            + "그대로 받고 불 피해는 {iron_fire}% 더 받습니다. 끝나기 {spit_warning}초 전부터 몸이 "
                            + "달아오르며 반경을 알리고, 끝날 때 삼킨 쇳조각을 토해 반경 {spit_radius}블록의 적에게 삼킨 "
                            + "횟수마다 {spit_damage} 피해(최대 {spit_max})를 주고 밀쳐냅니다.")
            .param("gnaw_armor", 1)
            .param("gnaw_duration", 8)
            .param("gnaw_max", 4)
            .param("gnaw_cooldown", 1)
            .param("grow_health", 0.5)
            .param("grow_scale", 2)
            .param("grow_max", 8)
            .param("fire_weakness", 30)
            .param("bite_range", 3.5)
            .param("bite_angle", 70)
            .param("bite_damage", 4)
            .param("chew_duration", 4)
            .param("chew_heal", 1)
            .param("chew_heal_interval", 0.5)
            .param("miss_cooldown", 50)
            .param("iron_duration", 6)
            .param("iron_heal", 1)
            .param("iron_heal_interval", 0.5)
            .param("iron_fire", 100)
            .param("spit_warning", 1)
            .param("spit_radius", 5)
            .param("spit_damage", 1)
            .param("spit_max", 8)
            // The iron body lives in the damage hook of a granted bulgasari.
            .ultimateNotStandalone()
            .build();

    private static final String RUST_TAG = "#B8683A";
    private static final String IRON_TAG = "#A9AEB3";
    private static final String EMBER_TAG = "#FF7A1A";

    // ------------------------------------------------------------------ look
    /** Ember orange core, rust brown body, cast-iron grey edge. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFF7A1A, 0x8A4B2A, 0x5C6166);
    private static final Particle.DustOptions RUST = new Particle.DustOptions(Color.fromRGB(0x8A4B2A), 1.0f);
    private static final Particle.DustOptions RUST_FLAKE = new Particle.DustOptions(Color.fromRGB(0xA0582E), 0.6f);
    private static final Particle.DustOptions IRON = new Particle.DustOptions(Color.fromRGB(0x9AA0A6), 1.1f);
    private static final Particle.DustOptions IRON_SMALL = new Particle.DustOptions(Color.fromRGB(0x7A8086), 0.7f);
    private static final Particle.DustOptions TOOTH = new Particle.DustOptions(Color.fromRGB(0xF2EEE2), 0.7f);
    private static final Particle.DustTransition SEAM = new Particle.DustTransition(Color.fromRGB(0xFFB15A),
            Color.fromRGB(0xFF5A12), 0.8f);
    private static final Particle.DustTransition RUST_RING = PALETTE.fade(1.1f);
    private static final Particle.DustTransition EMBER = PALETTE.glow(0.9f);
    private static final Color IRON_TRAIL = Color.fromRGB(0xB7BCC1);
    private static final Color HEAL_TRAIL = Color.fromRGB(0xFFC07A);
    private static final ItemStack IRON_CRUMB = new ItemStack(Material.IRON_NUGGET);

    /** Materials of the visuals; block data is created when used (it needs a running server). */
    private static final Material JAW = Material.IRON_BLOCK;
    private static final Material TEETH = Material.QUARTZ_BLOCK;
    private static final Material PLATE = Material.IRON_BLOCK;
    private static final Material RIVET_PLATE = Material.WAXED_EXPOSED_COPPER;
    private static final Material GLOW_SEAM = Material.ORANGE_CONCRETE;
    private static final Material SHARD = Material.IRON_NUGGET;

    /** Ability ids whose damage counts as fire (they all burn). */
    private static final Set<String> FIRE_ABILITIES = Set.of("pyro", "magma", "dragon", "demon", "phoenix");

    private static final double MAX_SPEED = 1.6;
    private static final int MAX_DISPLAYS = 24;

    // passive
    private static final long FLAKE_PERIOD = 20;
    private static final long CLEANUP_PERIOD = 100;
    private static final long FIRE_FX_COOLDOWN = 8;
    private static final long FOOD_FX_COOLDOWN = 4;
    /** A grown bulgasari sheds rust now and then once it has eaten this many times. */
    private static final int BIG_MEALS = 4;

    // active
    private static final int JAW_CLOSE_TICKS = 2;
    private static final int JAW_HOLD_TICKS = 5;
    private static final float JAW_WIDTH = 0.85f;
    private static final float JAW_DEPTH = 0.42f;
    private static final float JAW_THICK = 0.14f;
    private static final float JAW_OPEN = 0.5f;
    private static final float CHEW_ITEM_SIZE = 0.55f;
    private static final double CHEW_LIFT = 0.55;
    private static final long CHOMP_PERIOD = 10;
    private static final float CHEW_GROW_FROM = 0.2f;
    private static final float CHEW_GROW_TICKS = 4f;

    // ultimate
    private static final float PLATE_THICK = 0.06f;
    private static final long COUNTER_POP = 4;
    private static final long PLATE_FADE = 5;
    private static final double SPIT_PUSH = 1.0;
    private static final double SPIT_LIFT = 0.42;
    private static final int SHARD_DISPLAYS = 8;
    private static final long SHARD_FLIGHT = 7;
    private static final long AFTERGLOW_TICKS = 14;

    /** One gnaw on an enemy's armour: its modifier and when it runs out. */
    private record Gnaw(NamespacedKey key, long expiry) {
    }

    private final Map<UUID, Deque<Gnaw>> gnaws = new HashMap<>();
    private final Map<UUID, Long> nextGnaw = new HashMap<>();
    private final List<Display> displays = new ArrayList<>();
    private int meals;
    private @Nullable NamespacedKey healthKey;
    private @Nullable NamespacedKey scaleKey;
    private @Nullable Chew chew;
    private @Nullable IronBody iron;
    private double cooldownShare = 1.0;
    private long lastFireFx = Long.MIN_VALUE / 2;
    private long lastFoodFx = Long.MIN_VALUE / 2;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        // Modifiers, tasks and displays are owner-scoped: the context releases them.
        gnaws.clear();
        nextGnaw.clear();
        displays.clear();
        chew = null;
        iron = null;
        meals = 0;
        healthKey = null;
        scaleKey = null;
    }

    @Override
    public double cooldownMultiplier() {
        double share = cooldownShare;
        cooldownShare = 1.0;
        return share;
    }

    /** Package-private for tests: how many times the bulgasari has eaten iron (its growth). */
    int meals() {
        return meals;
    }

    /** Package-private for tests: the live gnaws on an enemy's armour. */
    int gnawsOn(LivingEntity enemy) {
        Deque<Gnaw> stack = gnaws.get(enemy.getUniqueId());
        if (stack == null) {
            return 0;
        }
        long now = ctx().currentTick();
        int live = 0;
        for (Gnaw gnaw : stack) {
            if (gnaw.expiry() > now) {
                live++;
            }
        }
        return live;
    }

    /** Package-private for tests: how many blows the iron body has swallowed so far (0 when it is not up). */
    int swallowed() {
        IronBody body = iron;
        return body == null || body.over ? 0 : body.swallows;
    }

    @Override
    public void onTick(long tick) {
        long now = ctx().currentTick();
        if (tick % FLAKE_PERIOD == 0) {
            shedRust(now);
        }
        if (tick % CLEANUP_PERIOD == 0) {
            gnaws.values().forEach(stack -> stack.removeIf(gnaw -> gnaw.expiry() <= now));
            gnaws.values().removeIf(Deque::isEmpty);
            nextGnaw.values().removeIf(next -> next <= now);
            displays.removeIf(display -> !display.isValid());
        }
    }

    /** Rust flakes drift off every gnawed enemy (and off a well-fed bulgasari): the passive at a glance. */
    private void shedRust(long now) {
        Player owner = player();
        World world = owner.getWorld();
        for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), 32)) {
            Deque<Gnaw> stack = gnaws.get(enemy.getUniqueId());
            if (stack == null || isHidden(enemy)) {
                continue;
            }
            int live = (int) stack.stream().filter(gnaw -> gnaw.expiry() > now).count();
            if (live <= 0) {
                continue;
            }
            Location chest = enemy.getLocation().add(0, enemy.getHeight() * 0.6, 0);
            world.spawnParticle(Particle.DUST, chest, Vfx.n(ctx(), 1 + live), 0.22, 0.3, 0.22, 0, RUST_FLAKE);
            if (live >= 2) {
                world.spawnParticle(Particle.FALLING_DUST, chest, 1, 0.2, 0.2, 0.2, 0,
                        Material.RAW_IRON_BLOCK.createBlockData());
            }
        }
        if (meals >= BIG_MEALS && (iron == null || iron.over)) {
            Location back = owner.getLocation().add(0, owner.getHeight() * 0.7, 0);
            world.spawnParticle(Particle.DUST, back, Vfx.n(ctx(), 2), 0.25, 0.35, 0.25, 0, RUST_FLAKE);
        }
    }

    // ------------------------------------------------------------------ passive: iron eater

    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (ctx().isAbilityDamage() || !player().equals(event.getDamager()) || !isMeleeCause(event.getCause())) {
            return;
        }
        long now = ctx().currentTick();
        Long next = nextGnaw.get(victim.getUniqueId());
        if (next != null && now < next) {
            return;
        }
        if (gnaw(victim)) {
            nextGnaw.put(victim.getUniqueId(), now + params().ticks("gnaw_cooldown"));
        }
    }

    private static boolean isMeleeCause(DamageCause cause) {
        return cause == DamageCause.ENTITY_ATTACK || cause == DamageCause.ENTITY_SWEEP_ATTACK;
    }

    /** Something made of iron to gnaw: an armour piece in any slot (a wolf's armour counts too). */
    private static boolean wearsArmor(LivingEntity target) {
        EntityEquipment equipment = target.getEquipment();
        if (equipment == null) {
            return false;
        }
        for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                EquipmentSlot.FEET, EquipmentSlot.BODY}) {
            try {
                ItemStack item = equipment.getItem(slot);
                if (item != null && !item.getType().isAir()) {
                    return true;
                }
            } catch (IllegalArgumentException unsupported) {
                // The slot does not exist for this entity type (body armour on a player).
            }
        }
        return false;
    }

    /**
     * One gnaw: the enemy's armour loses a point for a while (the oldest gnaw gives way at the cap) and the swallowed
     * iron makes the bulgasari grow. False if there was nothing to gnaw.
     */
    private boolean gnaw(LivingEntity victim) {
        if (!Combat.isTargetable(ctx(), player(), victim) || !wearsArmor(victim)) {
            return false;
        }
        Player owner = player();
        long now = ctx().currentTick();
        Deque<Gnaw> stack = gnaws.computeIfAbsent(victim.getUniqueId(), id -> new ArrayDeque<>());
        stack.removeIf(gnaw -> gnaw.expiry() <= now);
        int max = Math.max(0, params().integer("gnaw_max"));
        double armor = Math.max(0, params().num("gnaw_armor"));
        long duration = params().ticks("gnaw_duration");
        if (max > 0 && armor > 0 && duration > 0) {
            while (stack.size() >= max) {
                Gnaw oldest = stack.pollFirst();
                if (oldest != null) {
                    ctx().removeModifier(victim, oldest.key());
                }
            }
            NamespacedKey key = ctx().addModifier(owner, victim, Attribute.ARMOR, -armor, Operation.ADD_NUMBER,
                    duration);
            stack.addLast(new Gnaw(key, now + duration));
        }
        boolean grew = grow();
        gnawFx(victim, stack.size(), grew);
        return true;
    }

    /** One more meal: bigger and sturdier, up to the cap. */
    private boolean grow() {
        int max = Math.max(0, params().integer("grow_max"));
        if (meals >= max) {
            return false;
        }
        meals++;
        Player owner = player();
        double health = Math.max(0, params().num("grow_health"));
        double scale = Math.max(0, params().num("grow_scale")) / 100.0;
        if (health > 0) {
            // The new maximum goes on before the old one comes off, so current health is never clamped down.
            NamespacedKey old = healthKey;
            healthKey = ctx().addModifier(owner, owner, Attribute.MAX_HEALTH, health * meals, Operation.ADD_NUMBER, 0);
            if (old != null) {
                ctx().removeModifier(owner, old);
            }
            ctx().heal(owner, health);
        }
        if (scale > 0) {
            NamespacedKey old = scaleKey;
            scaleKey = ctx().addModifier(owner, owner, Attribute.SCALE, scale * meals, Operation.MULTIPLY_SCALAR_1, 0);
            if (old != null) {
                ctx().removeModifier(owner, old);
            }
        }
        return true;
    }

    /** Iron crumbs fly off the bitten armour into the bulgasari's mouth. */
    private void gnawFx(LivingEntity victim, int stacks, boolean grew) {
        Player owner = player();
        Location mouth = mouth(owner);
        if (!isHidden(victim)) {
            Location chest = victim.getLocation().add(0, victim.getHeight() * 0.62, 0);
            World world = chest.getWorld();
            world.spawnParticle(Particle.ITEM, chest, Vfx.n(ctx(), 6), 0.18, 0.18, 0.18, 0.06, IRON_CRUMB);
            world.spawnParticle(Particle.DUST, chest, Vfx.n(ctx(), 5), 0.2, 0.2, 0.2, 0, RUST);
            // Two rows of teeth snapping shut across the armour, on the side the bulgasari bites from.
            double yaw = owner.getLocation().getYaw() + 180;
            Vfx.arc(ctx(), chest.clone().add(0, 0.16, 0), 0.42, yaw, 120, 7, Particle.DUST, TOOTH, false);
            Vfx.arc(ctx(), chest.clone().add(0, -0.16, 0), 0.42, yaw, 120, 7, Particle.DUST, TOOTH, false);
            int motes = Vfx.n(ctx(), 3);
            for (int i = 0; i < motes; i++) {
                Vfx.trail(Vfx.scatter(chest, 0.25), mouth, IRON_TRAIL, 7 + i * 2, false);
            }
            Fx.sound(chest, Sound.ENTITY_GENERIC_EAT, 0.8f, 0.75f + Fx.jitter(0.08f));
            Fx.sound(chest, Sound.BLOCK_CHAIN_BREAK, 0.55f, 1.3f + 0.08f * stacks + Fx.jitter(0.06f));
            Fx.sound(chest, Sound.ENTITY_ITEM_BREAK, 0.35f, 1.6f + Fx.jitter(0.1f));
        }
        if (grew) {
            Location feet = owner.getLocation().add(0, 0.08, 0);
            Vfx.shockwave(ctx(), owner, feet, 0.4, 1.1 + meals * 0.05, 4, Particle.DUST_COLOR_TRANSITION, RUST_RING,
                    false);
            Fx.sound(mouth, Sound.ENTITY_PLAYER_BURP, 0.35f, 0.6f + Fx.jitter(0.05f));
        }
        int max = Math.max(0, params().integer("grow_max"));
        double health = Math.max(0, params().num("grow_health")) * meals;
        owner.sendActionBar(Text.mm("<" + RUST_TAG + "><b>쇠붙이 먹보</b></" + RUST_TAG + "> <gray>갉은 갑옷</gray> "
                + "<white>" + stacks + "겹</white> <dark_gray>·</dark_gray> <" + IRON_TAG + ">삼킨 쇠 " + meals + "/"
                + max + "</" + IRON_TAG + "> <gray>(최대 체력 +" + AbilityInfo.formatNumber(health) + ")</gray>"));
    }

    // ------------------------------------------------------------------ damage taken

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled() || !(event.getDamage() > 0)) {
            return;
        }
        IronBody body = iron;
        if (body != null && !body.over && body.swallow(event)) {
            return;
        }
        Chew chewing = chew;
        if (chewing != null && !chewing.over && chewing.eat(event)) {
            return;
        }
        if (isFire(event)) {
            double extra = body != null && !body.over ? params().num("iron_fire") : params().num("fire_weakness");
            double factor = 1 + Math.max(0, extra) / 100.0;
            if (factor > 1) {
                event.setDamage(event.getDamage() * factor);
                fireFx(body != null && !body.over);
            }
        }
    }

    /** Fire: burns, flames, lava, magma floors, campfires, and the damage of the fire-wielding abilities. */
    private boolean isFire(EntityDamageEvent event) {
        switch (event.getCause()) {
            case FIRE, FIRE_TICK, LAVA, HOT_FLOOR, CAMPFIRE -> {
                return true;
            }
            default -> {
            }
        }
        if (!ctx().isAbilityDamage() || !(event instanceof EntityDamageByEntityEvent byEntity)) {
            return false;
        }
        Entity source = event.getDamageSource().getCausingEntity();
        Player attacker = ctx().resolveAttacker(source != null ? source : byEntity.getDamager());
        if (attacker == null || attacker.equals(player())) {
            return false;
        }
        Ability ability = ctx().abilityOf(attacker);
        return ability != null && FIRE_ABILITIES.contains(ability.info().id());
    }

    /** The weak spot: the rice-paste body sizzles and the iron glows. */
    private void fireFx(boolean ironBody) {
        long now = ctx().currentTick();
        if (now - lastFireFx < FIRE_FX_COOLDOWN) {
            return;
        }
        lastFireFx = now;
        Player owner = player();
        Location body = Fx.body(owner);
        World world = body.getWorld();
        world.spawnParticle(Particle.LAVA, body, Vfx.n(ctx(), ironBody ? 3 : 1), 0.2, 0.3, 0.2, 0);
        world.spawnParticle(Particle.SMOKE, body, Vfx.n(ctx(), 6), 0.25, 0.4, 0.25, 0.02);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, body, Vfx.n(ctx(), 6), 0.25, 0.4, 0.25, 0, EMBER);
        Fx.sound(body, Sound.BLOCK_FIRE_EXTINGUISH, 0.5f, 1.3f + Fx.jitter(0.1f));
        Fx.sound(body, Sound.ENTITY_GENERIC_BURN, 0.6f, 0.8f + Fx.jitter(0.08f));
        owner.sendActionBar(Text.mm("<" + EMBER_TAG + "><b>火可殺伊</b></" + EMBER_TAG + "> <gray>불에 약합니다! 불 피해 +"
                + AbilityInfo.formatNumber(ironBody ? params().num("iron_fire") : params().num("fire_weakness"))
                + "%</gray>"));
    }

    /** A blow that becomes food: the bulgasari heals (at most once per interval) and crunches. */
    private void feed(double amount, long interval, Location from) {
        Player owner = player();
        long now = ctx().currentTick();
        Location mouth = mouth(owner);
        if (amount > 0) {
            ctx().heal(owner, amount);
        }
        if (now - lastFoodFx >= Math.min(FOOD_FX_COOLDOWN, Math.max(1, interval))) {
            lastFoodFx = now;
            World world = mouth.getWorld();
            world.spawnParticle(Particle.ITEM, mouth, Vfx.n(ctx(), 5), 0.12, 0.12, 0.12, 0.05, IRON_CRUMB);
            world.spawnParticle(Particle.HEART, mouth.clone().add(0, 0.35, 0), 1, 0.1, 0.05, 0.1, 0);
            if (from.getWorld().equals(mouth.getWorld())) {
                int motes = Vfx.n(ctx(), 3);
                for (int i = 0; i < motes; i++) {
                    Vfx.trail(Vfx.scatter(from, 0.2), mouth, i == 0 ? HEAL_TRAIL : IRON_TRAIL, 5 + i * 2, false);
                }
            }
            Fx.sound(mouth, Sound.ENTITY_GENERIC_EAT, 0.9f, 0.6f + Fx.jitter(0.08f));
            Fx.sound(mouth, Sound.BLOCK_ANVIL_PLACE, 0.25f, 1.9f + Fx.jitter(0.05f));
        }
    }

    // ------------------------------------------------------------------ active: chewing the blade

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        double range = Math.max(0.5, params().num("bite_range"));
        LivingEntity prey = ctx().findTarget(owner, range);
        if (prey == null) {
            prey = nearestInFront(owner, range);
        }
        Location eye = owner.getEyeLocation();
        if (prey == null) {
            // Chomping on air: the jaws snap shut in front and only half the cooldown runs.
            Location front = eye.clone().add(eye.getDirection().multiply(Math.min(1.6, range))).subtract(0, 0.35, 0);
            jaws(owner, Displays.unrotated(front), owner.getLocation().getYaw(), false);
            Fx.sound(front, Sound.ENTITY_EVOKER_FANGS_ATTACK, 0.8f, 1.2f + Fx.jitter(0.08f));
            cooldownShare = Math.clamp(params().num("miss_cooldown") / 100.0, 0.0, 1.0);
            owner.sendActionBar(Text.mm("<gray>허공을 씹었습니다</gray> <dark_gray>(쿨타임 "
                    + AbilityInfo.formatNumber(Math.round(cooldownShare * 100)) + "%)</dark_gray>"));
            return ActivationResult.success();
        }
        bite(owner, prey);
        return ActivationResult.success();
    }

    /** The nearest visible enemy within {@code range} in front (a cone), for a bite that does not need exact aim. */
    private @Nullable LivingEntity nearestInFront(Player owner, double range) {
        Location eye = owner.getEyeLocation();
        Vector facing = Motion.horizontal(eye.getDirection());
        double cos = Math.cos(Math.toRadians(Math.clamp(params().num("bite_angle"), 0, 360) / 2));
        for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), range)) {
            if (isHidden(enemy)) {
                continue;
            }
            Vector to = Motion.horizontal(enemy.getLocation().toVector().subtract(owner.getLocation().toVector()));
            boolean pointBlank = enemy.getLocation().distanceSquared(owner.getLocation()) < 0.8 * 0.8;
            if ((pointBlank || to.dot(facing) >= cos) && owner.hasLineOfSight(enemy)) {
                return enemy;
            }
        }
        return null;
    }

    private void bite(Player owner, LivingEntity prey) {
        Location chest = prey.getLocation().add(0, prey.getHeight() * 0.6, 0);
        jaws(owner, Displays.unrotated(chest), owner.getLocation().getYaw(), true);
        Combat.hit(ctx(), owner, prey, params().num("bite_damage"));
        if (!Combat.isTargetable(ctx(), owner, prey)) {
            return;
        }
        gnaw(prey);
        nextGnaw.put(prey.getUniqueId(), ctx().currentTick() + params().ticks("gnaw_cooldown"));
        Chew old = chew;
        if (old != null) {
            old.finish();
        }
        Chew fresh = new Chew(owner, prey);
        chew = fresh;
        fresh.start();

        World world = chest.getWorld();
        Vfx.dot(chest, Particle.FLASH, PALETTE.core(), false);
        Vfx.burst(ctx(), chest, PALETTE, 0.6, Particle.CRIT, false);
        world.spawnParticle(Particle.ITEM, chest, Vfx.n(ctx(), 10), 0.2, 0.2, 0.2, 0.12, IRON_CRUMB);
        world.spawnParticle(Particle.BLOCK, chest, Vfx.n(ctx(), 8), 0.25, 0.25, 0.25, 0,
                Material.IRON_BLOCK.createBlockData());
        Fx.sound(chest, Sound.ENTITY_EVOKER_FANGS_ATTACK, 1.1f, 0.75f + Fx.jitter(0.06f));
        Fx.sound(chest, Sound.ENTITY_GENERIC_EAT, 1.0f, 0.55f + Fx.jitter(0.06f));
        Fx.sound(chest, Sound.BLOCK_ANVIL_HIT, 0.6f, 1.5f + Fx.jitter(0.08f));
        owner.sendActionBar(Text.mm("<" + RUST_TAG + "><b>우걱우걱!</b></" + RUST_TAG + "> <white><name></white><gray>의 "
                + "무기를 " + Fx.seconds(params().ticks("chew_duration")) + "초간 씹습니다</gray>",
                Placeholder.component("name", prey.name())));
        if (prey instanceof Player victim) {
            victim.sendActionBar(Text.mm("<" + RUST_TAG + ">불가사리가 무기를 씹고 있습니다!</" + RUST_TAG + "> <gray>"
                    + Fx.seconds(params().ticks("chew_duration")) + "초간 근접 공격은 불가사리의 먹이가 됩니다</gray>"));
        }
    }

    /** Two iron jaws with a row of teeth snap shut at {@code at} (turned to {@code yaw}), then fade. */
    private void jaws(Player owner, Location at, float yaw, boolean hit) {
        if (!reserve(2)) {
            return;
        }
        BlockDisplay upper = keep(Displays.block(ctx(), owner, at, JAW.createBlockData(),
                jawPose(yaw, JAW_OPEN, 0.6f), JAW_CLOSE_TICKS + JAW_HOLD_TICKS + 6L));
        BlockDisplay lower = keep(Displays.block(ctx(), owner, at, TEETH.createBlockData(),
                jawPose(yaw, -JAW_OPEN, 0.6f), JAW_CLOSE_TICKS + JAW_HOLD_TICKS + 6L));
        ctx().runLater(owner, 1, () -> {
            Displays.animate(upper, jawPose(yaw, JAW_THICK * 0.55f, 1f), JAW_CLOSE_TICKS);
            Displays.animate(lower, jawPose(yaw, -JAW_THICK * 0.55f, 1f), JAW_CLOSE_TICKS);
        });
        ctx().runLater(owner, 1 + JAW_CLOSE_TICKS, () -> {
            World world = at.getWorld();
            world.spawnParticle(Particle.CRIT, at, Vfx.n(ctx(), hit ? 10 : 5), 0.3, 0.1, 0.3, 0.2);
            Vfx.arc(ctx(), at, 0.5, yaw, 150, 9, Particle.DUST, IRON, false);
        });
        ctx().runLater(owner, 1 + JAW_CLOSE_TICKS + JAW_HOLD_TICKS, () -> {
            for (BlockDisplay jaw : List.of(upper, lower)) {
                if (jaw.isValid()) {
                    Displays.shrinkAway(ctx(), owner, jaw, 4);
                }
            }
        });
    }

    /** A jaw: a flat bar across the bite, {@code lift} above (or below) the bite point, turned to {@code yaw}. */
    private static Transformation jawPose(float yaw, float lift, float grow) {
        return part(yaw, 0, 0, lift, 0.12f, JAW_WIDTH * grow, JAW_THICK, JAW_DEPTH * grow);
    }

    /**
     * The chewed weapon: for a while every melee blow of that one enemy against the bulgasari is food. Its weapon
     * floats over its head, slowly turning, wobbling and crunching.
     */
    private final class Chew implements LongPredicate {

        private final Player owner;
        private final LivingEntity victim;
        private final long duration;
        private final double heal;
        private final long healInterval;
        private @Nullable ItemDisplay weapon;
        private long nextHeal = Long.MIN_VALUE / 2;
        private int meals;
        private boolean over;

        Chew(Player owner, LivingEntity victim) {
            this.owner = owner;
            this.victim = victim;
            this.duration = Math.max(1, params().ticks("chew_duration"));
            this.heal = Math.max(0, params().num("chew_heal"));
            this.healInterval = Math.max(1, params().ticks("chew_heal_interval"));
        }

        void start() {
            ItemStack held = heldWeapon(victim);
            if (reserve(1)) {
                weapon = keep(Displays.item(ctx(), owner, above(victim), held, weaponPose(0, CHEW_GROW_FROM),
                        duration + 8));
                weapon.setTeleportDuration(1);
            }
            Loop.repeat(ctx(), owner, 1, 1, duration, this, this::finish);
        }

        @Override
        public boolean test(long tick) {
            if (over || !Combat.isTargetable(ctx(), owner, victim)) {
                finish();
                return false;
            }
            ItemDisplay item = weapon;
            boolean hidden = isHidden(victim);
            if (item != null && item.isValid()) {
                Displays.moveTo(item, above(victim));
                // A chewed weapon over a hidden enemy would give it away: it shrinks out of sight meanwhile. It grows in
                // over the first ticks (the per-tick turn would cut a separate grow-in animation short).
                float grow = Math.min(1f, CHEW_GROW_FROM + (1f - CHEW_GROW_FROM) * (tick + 1) / CHEW_GROW_TICKS);
                float size = hidden ? 0.001f : chompScale(tick) * grow;
                Displays.animate(item, weaponPose(tick * 6f, size), 1);
            }
            if (!hidden && tick % CHOMP_PERIOD == 0) {
                Location at = above(victim);
                World world = at.getWorld();
                world.spawnParticle(Particle.ITEM, at, Vfx.n(ctx(), 3), 0.1, 0.1, 0.1, 0.04, IRON_CRUMB);
                world.spawnParticle(Particle.DUST, at, Vfx.n(ctx(), 3), 0.15, 0.1, 0.15, 0, TOOTH);
                Fx.sound(at, Sound.ENTITY_GENERIC_EAT, 0.35f, 0.9f + Fx.jitter(0.1f));
            }
            return true;
        }

        /** The weapon jerks every time the bulgasari chomps down on it. */
        private float chompScale(long tick) {
            long phase = tick % CHOMP_PERIOD;
            return phase < 2 ? 0.82f : 1f;
        }

        /** A melee blow of the chewed enemy against the bulgasari turns into food. */
        boolean eat(EntityDamageEvent event) {
            if (over || !(event instanceof EntityDamageByEntityEvent byEntity) || ctx().isAbilityDamage()
                    || !isMeleeCause(event.getCause()) || !victim.equals(byEntity.getDamager())) {
                return false;
            }
            event.setCancelled(true);
            long now = ctx().currentTick();
            double amount = 0;
            if (now >= nextHeal) {
                nextHeal = now + healInterval;
                amount = heal;
            }
            meals++;
            feed(amount, healInterval, isHidden(victim) ? mouth(owner) : above(victim));
            ItemDisplay item = weapon;
            if (item != null && item.isValid() && !isHidden(victim)) {
                Location at = item.getLocation();
                at.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, at, Vfx.n(ctx(), 4), 0.15, 0.15, 0.15, 0.05);
            }
            owner.sendActionBar(Text.mm("<" + RUST_TAG + ">냠!</" + RUST_TAG + "> <gray>씹힌 무기는 아프지 않습니다</gray> "
                    + "<white>×" + meals + "</white>"));
            return true;
        }

        void finish() {
            if (over) {
                return;
            }
            over = true;
            if (chew == this) {
                chew = null;
            }
            ItemDisplay item = weapon;
            if (item != null && item.isValid()) {
                Location at = item.getLocation();
                if (!isHidden(victim)) {
                    at.getWorld().spawnParticle(Particle.ITEM, at, Vfx.n(ctx(), 6), 0.12, 0.12, 0.12, 0.08,
                            IRON_CRUMB);
                    Fx.sound(at, Sound.ENTITY_PLAYER_BURP, 0.5f, 0.9f + Fx.jitter(0.08f));
                }
                Displays.shrinkAway(ctx(), owner, item, 4);
            }
        }
    }

    /** The weapon the victim holds (a copy for the display), a bone for an unarmed one. */
    private static ItemStack heldWeapon(LivingEntity victim) {
        EntityEquipment equipment = victim.getEquipment();
        ItemStack held = equipment == null ? null : equipment.getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            return new ItemStack(Material.BONE);
        }
        ItemStack copy = held.clone();
        copy.setAmount(1);
        return copy;
    }

    private static Location above(LivingEntity victim) {
        return Displays.unrotated(victim.getLocation().add(0, victim.getHeight() + CHEW_LIFT, 0));
    }

    /** The chewed weapon tilted on its side, turning about the vertical axis. */
    private static Transformation weaponPose(float spinDeg, float size) {
        float s = CHEW_ITEM_SIZE * Math.max(0.001f, size);
        return Displays.centered(0, 0, 0, s, s, s, spinDeg, 0, 45);
    }

    // ------------------------------------------------------------------ ultimate: the unkillable

    @Override
    public ActivationResult onUltimate() {
        IronBody body = iron;
        if (body != null && !body.over) {
            return ActivationResult.fail("이미 쇳덩이 몸입니다");
        }
        IronBody fresh = new IronBody(player());
        iron = fresh;
        fresh.start();
        return ActivationResult.success();
    }

    /**
     * The iron body: riveted plates clamp around the torso; plain blades and projectiles are swallowed whole and every
     * swallow is counted; at the end the iron comes back out as shrapnel.
     */
    private final class IronBody implements LongPredicate {

        /**
         * Local plate layout around the body (x = left, y = up from the feet, z = forward; sizes), clear of the arms
         * (x 0.25..0.5, y 0.75..1.5) and the head: a breastplate and a backplate over the torso, two tassets on the
         * hips below the arms, two pauldrons on top of the shoulders.
         */
        private static final float[][] PLATES = {
                {0f, 1.02f, 0.17f, 0.48f, 0.62f, PLATE_THICK},
                {0f, 1.02f, -0.17f, 0.48f, 0.62f, PLATE_THICK},
                {0.285f, 0.6f, 0f, PLATE_THICK, 0.26f, 0.3f},
                {-0.285f, 0.6f, 0f, PLATE_THICK, 0.26f, 0.3f},
                {0.39f, 1.54f, 0f, 0.28f, 0.07f, 0.34f},
                {-0.39f, 1.54f, 0f, 0.28f, 0.07f, 0.34f},
        };

        private final Player owner;
        private final long duration;
        private final double heal;
        private final long healInterval;
        private final long warnTicks;
        private final List<BlockDisplay> plates = new ArrayList<>();
        private final List<float[]> layout = new ArrayList<>();
        private @Nullable BlockDisplay seam;
        private @Nullable TextDisplay counter;
        private long nextHeal = Long.MIN_VALUE / 2;
        private int swallows;
        /** 0..1 while the swallowed iron heats up before the finale (the seam swells with it). */
        private float heat;
        private boolean warned;
        private boolean over;

        IronBody(Player owner) {
            this.owner = owner;
            this.duration = Math.max(1, params().ticks("iron_duration"));
            this.heal = Math.max(0, params().num("iron_heal"));
            this.healInterval = Math.max(1, params().ticks("iron_heal_interval"));
            this.warnTicks = Math.clamp(params().ticks("spit_warning"), 0, duration);
        }

        void start() {
            Location feet = Displays.unrotated(owner.getLocation());
            float yaw = owner.getBodyYaw();
            float scale = (float) bodyScale();
            for (int i = 0; i < PLATES.length && reserve(1); i++) {
                float[] p = PLATES[i];
                Material material = i >= 4 ? RIVET_PLATE : PLATE;
                BlockDisplay plate = keep(Displays.block(ctx(), owner, feet, material.createBlockData(),
                        platePose(p, yaw, scale, 0.05f), duration + PLATE_FADE + 6));
                plate.setTeleportDuration(1);
                plates.add(plate);
                layout.add(p);
                // The plates sit right around the owner's camera (it would see them when looking down): only everyone
                // else sees them.
                owner.hideEntity(ctx().plugin(), plate);
            }
            if (reserve(1)) {
                seam = keep(Displays.block(ctx(), owner, feet, GLOW_SEAM.createBlockData(),
                        seamPose(yaw, scale, 0.05f), duration + PLATE_FADE + 6));
                seam.setTeleportDuration(1);
                owner.hideEntity(ctx().plugin(), seam);
            }
            if (reserve(1)) {
                counter = keep(Displays.text(ctx(), owner, counterSpot(), Text.mm("<" + IRON_TAG + "><b>不可殺伊</b></"
                        + IRON_TAG + ">"), 0.7f, duration + 6));
                counter.setTeleportDuration(1);
            }
            ctx().runLater(owner, 1, () -> pose(owner.getBodyYaw(), (float) bodyScale(), 1f, 4));

            Location body = Fx.body(owner);
            World world = body.getWorld();
            Vfx.dot(body, Particle.FLASH, PALETTE.core(), true);
            world.spawnParticle(Particle.BLOCK, body, Vfx.n(ctx(), 24), 0.4, 0.6, 0.4, 0,
                    Material.IRON_BLOCK.createBlockData());
            world.spawnParticle(Particle.ITEM, body, Vfx.n(ctx(), 14), 0.4, 0.6, 0.4, 0.1, IRON_CRUMB);
            Vfx.shockwave(ctx(), owner, owner.getLocation().add(0, 0.1, 0), 0.5, 3.0, 6,
                    Particle.DUST_COLOR_TRANSITION, RUST_RING, true);
            Vfx.helix(ctx(), owner.getLocation(), 0.75, owner.getHeight() + 0.2, 1.5, 2, 12, 0, Particle.DUST, IRON,
                    true);
            Fx.sound(body, Sound.BLOCK_ANVIL_LAND, 1.0f, 0.6f + Fx.jitter(0.05f));
            Fx.sound(body, Sound.ENTITY_IRON_GOLEM_REPAIR, 1.2f, 0.7f + Fx.jitter(0.05f));
            Fx.sound(body, Sound.BLOCK_IRON_DOOR_CLOSE, 1.0f, 0.5f);
            owner.sendActionBar(Text.mm("<" + IRON_TAG + "><b>불가살이</b></" + IRON_TAG + "> <gray>칼과 화살은 먹이, "
                    + "</gray><" + EMBER_TAG + ">불은 조심</" + EMBER_TAG + ">"));
            Loop.repeat(ctx(), owner, 1, 1, duration, this, this::finish);
        }

        private double bodyScale() {
            return Math.max(0.5, owner.getHeight() / 1.8);
        }

        private Location counterSpot() {
            return Displays.unrotated(owner.getLocation().add(0, owner.getHeight() + 0.55, 0));
        }

        @Override
        public boolean test(long tick) {
            if (over) {
                return false;
            }
            Location feet = Displays.unrotated(owner.getLocation());
            float yaw = owner.getBodyYaw();
            float scale = (float) bodyScale();
            for (BlockDisplay plate : plates) {
                Displays.moveTo(plate, feet);
            }
            if (seam != null) {
                Displays.moveTo(seam, feet);
            }
            if (counter != null) {
                Displays.moveTo(counter, counterSpot());
            }
            if (tick % 2 == 0) {
                pose(yaw, scale, 1f, 2);
            }
            if (tick % 4 == 0) {
                // Molten seams between the plates and the odd spark off the riveted iron.
                Location chest = owner.getLocation().add(0, owner.getHeight() * 0.58, 0);
                World world = chest.getWorld();
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, chest, Vfx.n(ctx(), 3), 0.25, 0.32, 0.25, 0,
                        SEAM);
                world.spawnParticle(Particle.DUST, chest, Vfx.n(ctx(), 2), 0.3, 0.4, 0.3, 0, IRON_SMALL);
            }
            if (tick % 20 == 10) {
                Fx.sound(owner.getLocation(), Sound.BLOCK_CHAIN_STEP, 0.6f, 0.6f + Fx.jitter(0.05f));
            }
            long left = duration - tick - 1;
            if (swallows > 0 && warnTicks > 0 && left >= 0 && left < warnTicks) {
                windUp(tick, left);
            }
            return true;
        }

        /**
         * The last moments before the finale: the swallowed iron heats up (the seam swells and glows, the chains rattle
         * faster and faster) and the reach of the shrapnel glows on the ground; whoever stands in it is warned.
         */
        private void windUp(long tick, long left) {
            double radius = Math.max(0, params().num("spit_radius"));
            heat = 1f - (float) left / warnTicks;
            if (!warned) {
                warned = true;
                int count = Math.min(swallows, Math.max(0, params().integer("spit_max")));
                String damage = AbilityInfo.formatNumber(Math.max(0, params().num("spit_damage")) * count);
                for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), radius + 2)) {
                    if (enemy instanceof Player victim) {
                        victim.sendActionBar(Text.mm("<" + EMBER_TAG + "><b>불가사리가 쇳조각을 토하려 합니다!</b></"
                                + EMBER_TAG + "> <gray>반경 " + AbilityInfo.formatNumber(radius) + "블록 · −" + damage
                                + "</gray>"));
                    }
                }
                owner.sendActionBar(Text.mm("<" + IRON_TAG + ">쇳조각이 목까지 찼습니다</" + IRON_TAG + "> <gray>곧 "
                        + "토해 냅니다 · ×" + count + "</gray>"));
                Fx.sound(Fx.body(owner), Sound.BLOCK_ANVIL_USE, 0.8f, 0.6f);
            }
            Location feet = owner.getLocation();
            if (tick % 3 == 0 && radius > 0) {
                int points = (int) Math.max(16, Math.min(32, radius * 5));
                Vfx.ring(ctx(), feet.clone().add(0, 0.12, 0), radius, points, tick * 0.05,
                        Particle.DUST_COLOR_TRANSITION, SEAM, true);
            }
            if (left % 4 == 0) {
                Location chest = Fx.body(owner);
                chest.getWorld().spawnParticle(Particle.SMALL_FLAME, chest, Vfx.n(ctx(), 3), 0.25, 0.35, 0.25, 0.01);
                Fx.sound(chest, Sound.BLOCK_CHAIN_STEP, 0.9f, 0.7f + 0.9f * heat);
            }
        }

        private void pose(float yaw, float scale, float grow, int ticks) {
            for (int i = 0; i < plates.size(); i++) {
                Displays.animate(plates.get(i), platePose(layout.get(i), yaw, scale, grow), ticks);
            }
            if (seam != null) {
                Displays.animate(seam, seamPose(yaw, scale, grow * (1f + 0.4f * heat)), ticks);
            }
        }

        /** A plain blade or projectile is swallowed whole. */
        boolean swallow(EntityDamageEvent event) {
            if (!(event instanceof EntityDamageByEntityEvent byEntity) || ctx().isAbilityDamage()) {
                return false;
            }
            Entity damager = byEntity.getDamager();
            boolean blade = isMeleeCause(event.getCause()) && ctx().isEnemy(owner, damager);
            boolean shot = event.getCause() == DamageCause.PROJECTILE && damager instanceof Projectile;
            if (!blade && !shot) {
                return false;
            }
            event.setCancelled(true);
            swallows++;
            long now = ctx().currentTick();
            double amount = 0;
            if (now >= nextHeal) {
                nextHeal = now + healInterval;
                amount = heal;
            }
            // Iron flows in from where the shot struck or from the blade (from right at the mouth for a hidden
            // attacker: the stream must not show where it stands).
            Location from = shot ? damager.getLocation()
                    : damager instanceof Player hidden && !owner.canSee(hidden) ? mouth(owner) : Fx.body(damager);
            if (shot && damager instanceof AbstractArrow arrow && !(arrow instanceof Trident)) {
                // An arrow is eaten for good (a trident is someone's weapon: it only bounces off the iron).
                arrow.remove();
            }
            feed(amount, healInterval, from);
            gulp();
            return true;
        }

        private void gulp() {
            int max = Math.max(0, params().integer("spit_max"));
            TextDisplay label = counter;
            if (label != null && label.isValid()) {
                label.text(Text.mm("<" + IRON_TAG + "><b>꿀꺽 ×" + swallows + "</b></" + IRON_TAG + ">"
                        + (swallows >= max && max > 0 ? " <" + EMBER_TAG + ">MAX</" + EMBER_TAG + ">" : "")));
                Displays.animate(label, Displays.centered(0, 0, 0, 0.95f, 0.95f, 0.95f, 0, 0, 0), 2);
                ctx().runLater(owner, COUNTER_POP, () -> Displays.animate(label,
                        Displays.centered(0, 0, 0, 0.7f, 0.7f, 0.7f, 0, 0, 0), 3));
            }
            Fx.sound(mouth(owner), Sound.ENTITY_GENERIC_DRINK, 0.6f, 0.6f + Math.min(0.8f, swallows * 0.06f));
            owner.sendActionBar(Text.mm("<" + IRON_TAG + ">꿀꺽!</" + IRON_TAG + "> <gray>삼킨 쇠붙이</gray> <white>×"
                    + swallows + "</white>"));
        }

        void finish() {
            if (over) {
                return;
            }
            over = true;
            for (BlockDisplay plate : plates) {
                if (plate.isValid()) {
                    fallOff(plate);
                }
            }
            if (seam != null && seam.isValid()) {
                Displays.shrinkAway(ctx(), owner, seam, (int) PLATE_FADE);
            }
            if (counter != null && counter.isValid()) {
                Displays.shrinkAway(ctx(), owner, counter, (int) PLATE_FADE);
            }
            if (!owner.isOnline() || !ctx().isAlive(owner)) {
                return;
            }
            spit();
        }

        /** A plate drops off the body, tumbling, and is gone. */
        private void fallOff(BlockDisplay plate) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            plate.setTeleportDuration((int) PLATE_FADE);
            Displays.moveTo(plate, plate.getLocation().add(random.nextDouble(-0.4, 0.4), -0.6,
                    random.nextDouble(-0.4, 0.4)));
            Displays.shrinkAway(ctx(), owner, plate, (int) PLATE_FADE);
        }

        /** The shrapnel finale: one point of damage per swallowed blow, to every enemy around. */
        private void spit() {
            int count = Math.min(swallows, Math.max(0, params().integer("spit_max")));
            Location center = Fx.body(owner);
            World world = center.getWorld();
            double radius = Math.max(0, params().num("spit_radius"));
            Fx.sound(center, Sound.ENTITY_PLAYER_BURP, 1.2f, 0.5f + Fx.jitter(0.04f));
            if (count <= 0) {
                world.spawnParticle(Particle.DUST, center, Vfx.n(ctx(), 10), 0.3, 0.4, 0.3, 0, RUST_FLAKE);
                Fx.sound(center, Sound.BLOCK_CHAIN_FALL, 1.0f, 0.8f);
                owner.sendActionBar(Text.mm("<gray>삼킨 쇠붙이가 없어 그냥 털어냅니다</gray>"));
                return;
            }
            double damage = Math.max(0, params().num("spit_damage")) * count;
            shards(center, radius, count);
            Vfx.dot(center, Particle.FLASH, PALETTE.core(), true);
            Vfx.shockwave(ctx(), owner, owner.getLocation().add(0, 0.15, 0), 0.6, radius, 6,
                    Particle.DUST_COLOR_TRANSITION, RUST_RING, true);
            world.spawnParticle(Particle.ITEM, center, Vfx.n(ctx(), 20 + count * 4), 0.3, 0.3, 0.3, 0.45,
                    IRON_CRUMB);
            world.spawnParticle(Particle.CRIT, center, Vfx.n(ctx(), 20), 0.3, 0.3, 0.3, 0.6);
            Fx.sound(center, Sound.BLOCK_ANVIL_BREAK, 1.1f, 0.8f + Fx.jitter(0.05f));
            Fx.sound(center, Sound.BLOCK_CHAIN_BREAK, 1.2f, 0.6f + Fx.jitter(0.05f));
            Fx.sound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.4f + Fx.jitter(0.05f));
            int hits = 0;
            for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), radius)) {
                Combat.hit(ctx(), owner, enemy, damage);
                hits++;
                if (!Combat.isTargetable(ctx(), owner, enemy)) {
                    continue;
                }
                Vector away = Motion.horizontal(enemy.getLocation().toVector().subtract(owner.getLocation().toVector()));
                if (away.lengthSquared() < 1.0E-6) {
                    away = Motion.horizontalFacing(owner.getLocation());
                }
                Motion.push(enemy, away.multiply(SPIT_PUSH).setY(SPIT_LIFT), MAX_SPEED);
                if (!isHidden(enemy)) {
                    Location chest = Fx.body(enemy);
                    world.spawnParticle(Particle.ITEM, chest, Vfx.n(ctx(), 6), 0.2, 0.3, 0.2, 0.1, IRON_CRUMB);
                    Fx.sound(chest, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 0.8f, 1.4f + Fx.jitter(0.1f));
                }
            }
            afterglow(center);
            owner.sendActionBar(Text.mm("<" + IRON_TAG + "><b>퉤!</b></" + IRON_TAG + "> <gray>쇳조각 " + count + "개 · "
                    + "</gray><white>" + AbilityInfo.formatNumber(damage) + " 피해</white><gray> · " + hits
                    + "명 적중</gray>"));
        }

        /** Iron shards (nugget displays) flung out in a ring. */
        private void shards(Location center, double radius, int count) {
            int pieces = Math.min(SHARD_DISPLAYS, count);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            ItemStack nugget = new ItemStack(SHARD);
            for (int i = 0; i < pieces && reserve(1); i++) {
                double angle = i * Math.PI * 2 / pieces + random.nextDouble(-0.2, 0.2);
                Vector out = new Vector(Math.cos(angle), random.nextDouble(0.05, 0.35), Math.sin(angle))
                        .multiply(Math.max(1.5, radius * (0.75 + random.nextDouble() * 0.3)));
                ItemDisplay shard = keep(Displays.item(ctx(), owner, center, nugget,
                        Displays.centered(0, 0, 0, 0.45f, 0.45f, 0.45f, random.nextFloat() * 360, 0, 0),
                        SHARD_FLIGHT + 8));
                shard.setTeleportDuration((int) SHARD_FLIGHT);
                Location to = center.clone().add(out);
                float spin = random.nextFloat() * 720;
                ctx().runLater(owner, 1, () -> {
                    if (shard.isValid()) {
                        Displays.moveTo(shard, to);
                        Displays.animate(shard, Displays.centered(0, 0, 0, 0.35f, 0.35f, 0.35f, spin, 90, 45),
                                (int) SHARD_FLIGHT);
                    }
                });
                ctx().runLater(owner, SHARD_FLIGHT, () -> {
                    if (shard.isValid()) {
                        Displays.shrinkAway(ctx(), owner, shard, 4);
                    }
                });
            }
        }

        private void afterglow(Location center) {
            Loop.repeat(ctx(), owner, 2, 2, AFTERGLOW_TICKS / 2, run -> {
                double fade = 1 - (double) run / (AFTERGLOW_TICKS / 2.0);
                World world = center.getWorld();
                world.spawnParticle(Particle.DUST, center, Vfx.n(ctx(), (int) Math.max(1, Math.round(4 * fade))),
                        0.8, 0.5, 0.8, 0, RUST_FLAKE);
                world.spawnParticle(Particle.SMOKE, center, Vfx.n(ctx(), 1), 0.4, 0.3, 0.4, 0.01);
                return true;
            });
        }
    }

    /** A body plate from the layout, turned with the body. */
    private static Transformation platePose(float[] p, float yaw, float scale, float grow) {
        float g = Math.max(0.001f, grow) * scale;
        return part(yaw, 0, p[0] * scale, p[1] * scale, p[2] * scale, p[3] * g, p[4] * g, p[5] * g);
    }

    /** The glowing seam: a thin molten band around the waist, just inside the plates. */
    private static Transformation seamPose(float yaw, float scale, float grow) {
        float g = Math.max(0.001f, grow) * scale;
        return part(yaw, 0, 0, 0.72f * scale, 0, 0.54f * g, 0.05f * g, 0.32f * g);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * A block display box of size (sx, sy, sz) centered on the local point (lx, ly, lz), in a frame turned by
     * {@code yaw} (0 = facing +Z, local +X is the left hand side) and tilted by {@code pitch}. All of it lives in the
     * transformation: the display itself stays unrotated.
     */
    private static Transformation part(float yaw, float pitch, float lx, float ly, float lz, float sx, float sy, float sz) {
        Quaternionf rotation = new Quaternionf().rotateY((float) Math.toRadians(-yaw))
                .rotateX((float) Math.toRadians(pitch));
        Vector3f translation = rotation.transform(new Vector3f(lx - sx / 2, ly - sy / 2, lz - sz / 2));
        return new Transformation(translation, rotation, new Vector3f(sx, sy, sz), new Quaternionf());
    }

    /** Where swallowed iron goes: just in front of the bulgasari's mouth. */
    private static Location mouth(Player owner) {
        Location eye = owner.getEyeLocation();
        return eye.add(Motion.horizontalFacing(eye).multiply(0.3)).subtract(0, 0.2, 0);
    }

    /** True for a player the bulgasari cannot see (stealth): no visual may give it away. */
    private boolean isHidden(LivingEntity entity) {
        return entity instanceof Player hidden && !player().canSee(hidden);
    }

    private <T extends Display> T keep(T display) {
        displays.add(display);
        return display;
    }

    private boolean reserve(int count) {
        for (Iterator<Display> it = displays.iterator(); it.hasNext(); ) {
            if (!it.next().isValid()) {
                it.remove();
            }
        }
        return displays.size() + count <= MAX_DISPLAYS;
    }
}
