package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
import io.github.sx2xoox.mcpl.game.superpower.ability.InputKey;
import io.github.sx2xoox.mcpl.game.superpower.ability.Prompt;
import io.github.sx2xoox.mcpl.game.superpower.ability.PromptHandle;
import io.github.sx2xoox.mcpl.util.Text;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent;
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
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 갓파 — the river imp of Japanese folklore with a dish of water on its head: while the dish is full it is strong, and
 * it loves sumo. A hit from above or on the head spills the water, and an enemy who bows to it politely makes it bow
 * back and pour the water out itself (the folk weakness anyone can use). It drags an enemy into a small sumo ring where
 * whoever leaves first loses, and as its ultimate it raises a great ring that pulls every enemy inside into the bout:
 * immovable, it shoves them out one by one.
 *
 * <p>Look (moss green, dish white, cucumber green): a white octagonal dish on the head with a glinting water layer
 * (hidden from the owner's own camera), dripping now and then; spilled, it splashes and the dish dries grey. The rings
 * are straw bales laid in a circle on sand-coloured dust, with a white belt line between the two wrestlers; the loser
 * sees stars. The great ring raises sixteen bales out of the ground and a rising sand haze.
 */
public final class KappaAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("kappa", "갓파", Material.KELP)
            .color(TextColor.color(0x4F8A3C))
            .difficulty(2)
            .passive("머리 접시",
                    "접시에 물이 차 있으면 근접 피해가 {plate_bonus}%, 넉백이 {plate_knockback}% 늘고, 비면 근접 피해와 "
                            + "이동 속도가 줄어듭니다. 위에서 맞거나 머리에 투사체를 맞거나 적이 나를 보며 웅크려 절하면 물이 "
                            + "쏟아지고, {refill_time}초 뒤나 물·비에 닿으면 다시 찹니다.")
            .active("씨름 한판",
                    "앞 {sumo_range}블록 안의 적에게 샅바를 걸어, 둘 사이에 반경 {sumo_radius}블록의 씨름판을 "
                            + "{sumo_duration}초간 깝니다. 판 안에서 그 적에게 주는 내 근접 넉백이 {sumo_knockback}배가 "
                            + "되고, 먼저 판 밖으로 나간 쪽이 집니다. 진 적은 {sumo_damage} 피해를 입고 {sumo_down}초간 "
                            + "넘어지며(이동·점프 불가), 내가 지면 내가 {sumo_down}초간 넘어집니다. 승부가 나지 않으면 "
                            + "둘 다 지쳐 적은 {draw_damage} 피해를 입고 나는 접시의 물을 쏟습니다.",
                    10)
            .ultimate("대씨름판",
                    "내 주위에 반경 {grand_radius}블록의 큰 씨름판을 {grand_telegraph}초에 걸쳐 깔고, 그때 판 안에 있던 "
                            + "적을 모두 {grand_duration}초간 씨름에 끌어들입니다. 접시가 가득 차고, 나는 넉백을 받지 않으며 "
                            + "그 적들에게 주는 근접 넉백이 {grand_knockback}배가 되고, 달리며 부딪힌 적은 약 {charge_push}"
                            + "블록 밀어냅니다(같은 적은 {charge_cooldown}초마다). 판 밖으로 나간 적은 {grand_damage} "
                            + "피해를 입고 {grand_down}초간 넘어지며 그 판에서 탈락합니다.")
            .ultimateNotStandalone()
            .param("plate_bonus", 15)
            .param("plate_knockback", 30)
            .param("spill_height", 1.5)
            .param("bow_range", 6)
            .param("bow_cooldown", 10)
            .param("bow_freeze", 0.5)
            .param("empty_penalty", 20)
            .param("empty_slow", 10)
            .param("refill_time", 6)
            .param("sumo_range", 5)
            .param("sumo_radius", 3)
            .param("sumo_duration", 3)
            .param("sumo_knockback", 2)
            .param("sumo_damage", 5)
            .param("sumo_down", 1)
            .param("draw_damage", 2)
            .param("grand_radius", 7)
            .param("grand_telegraph", 0.75)
            .param("grand_duration", 6)
            .param("grand_knockback", 2.5)
            .param("charge_push", 4)
            .param("charge_cooldown", 0.5)
            .param("grand_damage", 6)
            .param("grand_down", 1.5)
            .build();

    private static final String MOSS_TAG = "#4F8A3C";
    private static final String CUCUMBER_TAG = "#A7D46F";
    private static final String WATER_TAG = "#5FB4E8";

    // ------------------------------------------------------------------ look
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xEDEFEA, 0xA7D46F, 0x4F8A3C);
    private static final Color WATER = Color.fromRGB(0x5FB4E8);
    private static final Particle.DustOptions WATER_DUST = new Particle.DustOptions(WATER, 0.8f);
    private static final Particle.DustOptions MOSS_DUST = new Particle.DustOptions(Color.fromRGB(0x4F8A3C), 1.0f);
    private static final Particle.DustOptions SAND_DUST = new Particle.DustOptions(Color.fromRGB(0xD8C08A), 1.3f);
    private static final Particle.DustOptions BELT_DUST = new Particle.DustOptions(Color.fromRGB(0xF4EEDC), 0.7f);
    private static final Particle.DustOptions DRY_DUST = new Particle.DustOptions(Color.fromRGB(0x9A9A90), 0.6f);
    private static final Particle.DustOptions STAR_DUST = new Particle.DustOptions(Color.fromRGB(0xFFE066), 0.8f);

    /** Block types (block data is created on use: it needs a running server). */
    private static final Material DISH = Material.WHITE_CONCRETE;
    private static final Material DISH_DRY = Material.LIGHT_GRAY_CONCRETE;
    private static final Material WATER_LAYER = Material.LIGHT_BLUE_STAINED_GLASS;
    private static final Material BALE = Material.HAY_BLOCK;

    private static final float DISH_SIZE = 0.42f;
    private static final float DISH_THICK = 0.05f;
    private static final float WATER_SIZE = 0.34f;
    private static final float WATER_THICK = 0.03f;
    private static final int FOLLOW_LERP = 3;
    private static final double HEAD_MARGIN = 0.3;
    /** A bow counts when the enemy looks within about 30 degrees of the kappa. */
    private static final double BOW_DOT = 0.86;
    private static final long FRESH_PRESS = 2;
    private static final long RAIN_CHECK = 10;
    private static final int SKY_SCAN = 48;
    private static final double MAX_KNOCK = 1.6;
    private static final int BOUT_BALES = 12;
    private static final int GRAND_BALES = 16;
    private static final float BALE_SIZE = 0.42f;
    private static final double CHARGE_REACH = 1.5;
    private static final double CHARGE_DOT = 0.2;
    private static final double PUSH_LIFT = 0.3;
    /** Horizontal blocks an entity flies per block/tick of initial speed, for a lift of {@link #PUSH_LIFT}. */
    private static final double PUSH_REACH = 6.0;
    private static final double MAX_SPEED = 1.8;
    private static final double RING_GROUND = 3;
    private static final int MAX_DISPLAYS = 24;

    // ------------------------------------------------------------------ state

    /** The small ring of the active. */
    private final class Bout {
        final LivingEntity rival;
        final Location center;
        final double radius;
        final long until;
        final List<BlockDisplay> bales = new ArrayList<>();
        @Nullable PromptHandle rule;
        boolean over;

        Bout(LivingEntity rival, Location center, double radius, long until) {
            this.rival = rival;
            this.center = center;
            this.radius = radius;
            this.until = until;
        }
    }

    /** The great ring of the ultimate. */
    private final class Grand {
        final Location center;
        final double radius;
        final long rulesFrom;
        final long until;
        final Set<UUID> contestants = new HashSet<>();
        final Set<UUID> out = new HashSet<>();
        final Map<UUID, Long> charged = new HashMap<>();
        final List<BlockDisplay> bales = new ArrayList<>();
        final List<PromptHandle> rules = new ArrayList<>();

        Grand(Location center, double radius, long rulesFrom, long until) {
            this.center = center;
            this.radius = radius;
            this.rulesFrom = rulesFrom;
            this.until = until;
        }

        boolean holds(Entity entity) {
            return contestants.contains(entity.getUniqueId()) && !out.contains(entity.getUniqueId());
        }
    }

    private final List<Display> live = new ArrayList<>();
    private final Map<UUID, Long> bowCooldown = new HashMap<>();
    private final Map<UUID, Long> seenBow = new HashMap<>();
    private final List<BlockDisplay> dish = new ArrayList<>();
    private @Nullable BlockDisplay water;
    private @Nullable KappaListener listener;
    private @Nullable NamespacedKey emptySlow;
    private boolean full = true;
    private long refillAt = Long.MIN_VALUE / 2;
    private boolean raining;
    private @Nullable Bout bout;
    private @Nullable Grand grand;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onGrant() {
        KappaListener created = new KappaListener();
        Bukkit.getPluginManager().registerEvents(created, ctx().plugin());
        listener = created;
        spawnDish(player());
    }

    @Override
    public void onRevoke() {
        if (listener != null) {
            HandlerList.unregisterAll(listener);
            listener = null;
        }
        emptySlow = null;
        bout = null;
        grand = null;
        dish.clear();
        water = null;
        live.clear();
    }

    // ================================================================== test hooks

    /** True while the dish holds water. */
    public boolean isPlateFull() {
        return full;
    }

    /** True while a small bout of the active runs. */
    public boolean inBout() {
        Bout current = bout;
        return current != null && !current.over;
    }

    // ================================================================== passive: the dish

    private final class KappaListener implements Listener {

        /** The kappa's own melee knockback: stronger with water in the dish, and in the rings against its rivals. */
        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onKnockback(EntityKnockbackEvent event) {
            if (!(event instanceof EntityPushedByEntityAttackEvent pushed) || !player().equals(pushed.getPushedBy())) {
                return;
            }
            if (event.getCause() != EntityKnockbackEvent.Cause.ENTITY_ATTACK
                    && event.getCause() != EntityKnockbackEvent.Cause.SWEEP_ATTACK) {
                return;
            }
            Entity victim = event.getEntity();
            if (!ctx().isEnemy(player(), victim)) {
                return;
            }
            double factor = knockbackFactor(victim);
            if (Math.abs(factor - 1) < 1.0E-9) {
                return;
            }
            Vector knock = event.getKnockback();
            Vector flat = new Vector(knock.getX(), 0, knock.getZ()).multiply(factor);
            Motion.clamp(flat, MAX_KNOCK);
            event.setKnockback(new Vector(flat.getX(), knock.getY(), flat.getZ()));
        }
    }

    /** How much harder the kappa's melee pushes {@code victim} right now. */
    public double knockbackFactor(Entity victim) {
        double factor = full ? 1 + Math.max(0, params().num("plate_knockback")) / 100.0 : 1;
        Bout current = bout;
        Grand ring = grand;
        if (current != null && !current.over && current.rival.equals(victim)) {
            factor *= Math.max(1, params().num("sumo_knockback"));
        } else if (ring != null && ring.holds(victim) && ctx().currentTick() >= ring.rulesFrom) {
            factor *= Math.max(1, params().num("grand_knockback"));
        }
        return factor;
    }

    private void spawnDish(Player owner) {
        Location head = headSpot(owner);
        for (int i = 0; i < 2; i++) {
            BlockDisplay plate = Displays.block(ctx(), owner, head, DISH.createBlockData(),
                    Displays.transform(0, 0, 0, DISH_SIZE, DISH_THICK, DISH_SIZE, i * 45f, 0, 0), 0,
                    viewer -> !viewer.equals(owner) && !ctx().isConcealedFrom(owner, viewer));
            plate.setTeleportDuration(FOLLOW_LERP);
            dish.add(plate);
        }
        BlockDisplay layer = Displays.block(ctx(), owner, head, WATER_LAYER.createBlockData(), waterShape(true), 0,
                viewer -> !viewer.equals(owner) && !ctx().isConcealedFrom(owner, viewer));
        layer.setTeleportDuration(FOLLOW_LERP);
        water = layer;
    }

    private static Transformation waterShape(boolean filled) {
        float size = filled ? WATER_SIZE : 0.001f;
        return Displays.transform(0, DISH_THICK * 0.5f + WATER_THICK * 0.5f, 0, size, WATER_THICK, size, 22.5f, 0, 0);
    }

    private static Location headSpot(Player owner) {
        return owner.getLocation().add(0, owner.getHeight() + 0.06, 0);
    }

    @Override
    public void onTick(long tick) {
        Player owner = player();
        long now = ctx().currentTick();
        Location head = headSpot(owner);
        for (BlockDisplay plate : dish) {
            Displays.moveTo(plate, head);
        }
        BlockDisplay layer = water;
        if (layer != null) {
            Displays.moveTo(layer, head);
        }
        if (tick % RAIN_CHECK == 0) {
            raining = isRainedOn(owner);
        }
        if (!full && (now >= refillAt || owner.isInWater() || raining)) {
            refill(owner, owner.isInWater() || raining);
        }
        if (full && tick % 14 == 0) {
            Vfx.dot(head.clone().add(Fx.jitter(0.2f), 0.05, Fx.jitter(0.2f)), Particle.DRIPPING_WATER, null, false);
        } else if (!full && tick % 10 == 0) {
            Vfx.dot(head.clone().add(Fx.jitter(0.15f), 0.08, Fx.jitter(0.15f)), Particle.DUST, DRY_DUST, false);
        }
        watchBows(owner, now);
    }

    /** True in the rain: a storm in this world and nothing solid above the head. */
    private static boolean isRainedOn(Player owner) {
        World world = owner.getWorld();
        if (!world.hasStorm()) {
            return false;
        }
        Location eye = owner.getEyeLocation();
        int x = eye.getBlockX();
        int z = eye.getBlockZ();
        int top = Math.min(world.getMaxHeight() - 1, eye.getBlockY() + SKY_SCAN);
        for (int y = eye.getBlockY() + 1; y <= top; y++) {
            if (!world.getBlockAt(x, y, z).isPassable()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled() || ctx().isAbilityDamage() || !player().equals(event.getDamager())) {
            return;
        }
        if (event.getCause() != DamageCause.ENTITY_ATTACK && event.getCause() != DamageCause.ENTITY_SWEEP_ATTACK) {
            return;
        }
        double factor = full ? 1 + Math.max(0, params().num("plate_bonus")) / 100.0
                : 1 - Math.clamp(params().num("empty_penalty") / 100.0, 0.0, 1.0);
        event.setDamage(event.getDamage() * factor);
    }

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (!full || event.isCancelled() || event.getFinalDamage() <= 0
                || !(event instanceof EntityDamageByEntityEvent byEntity)) {
            return;
        }
        Player owner = player();
        Entity damager = byEntity.getDamager();
        if (damager instanceof Projectile projectile) {
            if (projectile.getLocation().getY() >= owner.getEyeLocation().getY() - HEAD_MARGIN) {
                spill(owner, "머리에 맞아");
            }
            return;
        }
        Player attacker = ctx().resolveAttacker(damager);
        Entity source = damager instanceof LivingEntity ? damager : attacker;
        if (source == null || source.equals(owner) || !ctx().isEnemy(owner, source)) {
            return;
        }
        if (source.getLocation().getY() >= owner.getLocation().getY() + Math.max(0, params().num("spill_height"))) {
            spill(owner, "위에서 맞아");
        }
    }

    /** The water goes: the dish empties until it refills. */
    private void spill(Player owner, String why) {
        if (!full) {
            return;
        }
        full = false;
        refillAt = ctx().currentTick() + params().ticks("refill_time");
        double slow = Math.clamp(params().num("empty_slow") / 100.0, 0.0, 1.0);
        if (slow > 0 && emptySlow == null) {
            emptySlow = ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, -slow, Operation.MULTIPLY_SCALAR_1,
                    0);
        }
        BlockDisplay layer = water;
        if (layer != null) {
            Displays.animate(layer, waterShape(false), 4);
        }
        for (BlockDisplay plate : dish) {
            plate.setBlock(DISH_DRY.createBlockData());
        }
        Location head = headSpot(owner);
        Vfx.cloud(ctx(), head, Particle.SPLASH, 24, 0.35, 0.1, null, false);
        Vfx.cloud(ctx(), head, Particle.FALLING_WATER, 10, 0.4, 0, null, false);
        Vfx.ring(ctx(), head, 0.5, 12, 0, Particle.DUST, WATER_DUST, false);
        Fx.sound(head, Sound.ENTITY_GENERIC_SPLASH, 0.9f, 1.4f + Fx.jitter(0.1f));
        Fx.sound(head, Sound.ITEM_BUCKET_EMPTY, 0.8f, 1.2f);
        owner.sendActionBar(Text.mm("<" + WATER_TAG + ">" + why + " 접시의 물이 쏟아졌습니다!</" + WATER_TAG + "> <gray>"
                + AbilityInfo.formatNumber(params().num("refill_time")) + "초 뒤(물·비에서는 곧바로) 다시 찹니다</gray>"));
    }

    private void refill(Player owner, boolean fromWater) {
        full = true;
        if (emptySlow != null) {
            ctx().removeModifier(owner, emptySlow);
            emptySlow = null;
        }
        BlockDisplay layer = water;
        if (layer != null) {
            Displays.animate(layer, waterShape(true), 5);
        }
        for (BlockDisplay plate : dish) {
            plate.setBlock(DISH.createBlockData());
        }
        Location head = headSpot(owner);
        Vfx.cloud(ctx(), head.clone().add(0, 0.1, 0), Particle.DUST, 8, 0.2, 0, WATER_DUST, false);
        Vfx.dot(head.clone().add(0, 0.2, 0), Particle.END_ROD, null, false);
        Fx.sound(head, Sound.ITEM_BUCKET_FILL, 0.8f, 1.4f);
        Fx.sound(head, Sound.BLOCK_BUBBLE_COLUMN_UPWARDS_AMBIENT, 0.6f, 1.2f);
        owner.sendActionBar(Text.mm("<" + WATER_TAG + ">접시에 물이 다시 찼습니다</" + WATER_TAG + ">"
                + (fromWater ? " <gray>· 시원하다!</gray>" : "")));
    }

    /** An enemy that bows to the kappa (crouches looking at it) makes it bow back. */
    private void watchBows(Player owner, long now) {
        double range = Math.max(0, params().num("bow_range"));
        for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), range)) {
            if (!(enemy instanceof Player bower)) {
                continue;
            }
            long press = ctx().lastPressTick(bower, InputKey.SNEAK);
            long seen = seenBow.getOrDefault(bower.getUniqueId(), -1L);
            if (press < 0 || press <= seen) {
                continue;
            }
            seenBow.put(bower.getUniqueId(), press);
            if (!full || now - press > FRESH_PRESS || now < bowCooldown.getOrDefault(bower.getUniqueId(),
                    Long.MIN_VALUE / 2)) {
                continue;
            }
            Vector toKappa = Fx.body(owner).toVector().subtract(bower.getEyeLocation().toVector());
            if (toKappa.lengthSquared() < 1.0E-4 || !bower.hasLineOfSight(owner)) {
                continue;
            }
            if (bower.getEyeLocation().getDirection().dot(toKappa.normalize()) < BOW_DOT) {
                continue;
            }
            bowBack(owner, bower, now);
        }
    }

    /** The kappa returns the courtesy: it freezes in a bow and pours out its own water. */
    private void bowBack(Player owner, Player bower, long now) {
        bowCooldown.put(bower.getUniqueId(), now + params().ticks("bow_cooldown"));
        long freeze = params().ticks("bow_freeze");
        if (freeze > 0) {
            ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, -1, Operation.MULTIPLY_SCALAR_1, freeze);
            ctx().addModifier(owner, owner, Attribute.JUMP_STRENGTH, -1, Operation.MULTIPLY_SCALAR_1, freeze);
        }
        Location head = headSpot(owner);
        Vector forward = Motion.horizontalFacing(owner.getLocation());
        for (int i = 0; i < 6; i++) {
            Location drop = head.clone().add(forward.clone().multiply(0.3 + i * 0.12)).add(0, -i * 0.18, 0);
            Vfx.dot(drop, Particle.FALLING_WATER, null, false);
            Vfx.dot(drop, Particle.DUST, WATER_DUST, false);
        }
        for (BlockDisplay plate : dish) {
            float turn = dish.indexOf(plate) * 45f;
            Displays.animate(plate, Displays.transform(0, 0, 0.12f, DISH_SIZE, DISH_THICK, DISH_SIZE, turn, 35, 0), 3);
            ctx().runLater(owner, Math.max(4, freeze), () -> Displays.animate(plate,
                    Displays.transform(0, 0, 0, DISH_SIZE, DISH_THICK, DISH_SIZE, turn, 0, 0), 4));
        }
        if (room(2)) {
            keep(Displays.text(ctx(), owner, head.clone().add(0, 0.5, 0), Text.mm("<" + CUCUMBER_TAG + ">꾸벅</"
                    + CUCUMBER_TAG + ">"), 0.8f, 24, viewer -> !ctx().isConcealedFrom(owner, viewer)));
            keep(Displays.text(ctx(), owner, bower.getLocation().add(0, bower.getHeight() + 0.5, 0),
                    Text.mm("<white>꾸벅</white>"), 0.7f, 20, viewer -> !ctx().isConcealedFrom(bower, viewer)));
        }
        Fx.sound(head, Sound.BLOCK_NOTE_BLOCK_COW_BELL, 0.8f, 1.2f);
        spill(owner, "따라 절하느라");
    }

    // ================================================================== active: one bout

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        LivingEntity rival = ctx().findTarget(owner, Math.max(1, params().num("sumo_range")));
        if (rival == null) {
            return ActivationResult.fail("<gray>앞 " + AbilityInfo.formatNumber(params().num("sumo_range"))
                    + "블록 안에 맞붙을 적이 없습니다</gray>");
        }
        Bout old = bout;
        if (old != null) {
            endBout(owner, old);
        }
        Location a = owner.getLocation();
        Location b = rival.getLocation();
        Location center = a.clone().add(b).multiply(0.5);
        center.setY(Math.max(a.getY(), b.getY()) + 0.5);
        Spots.snapToGround(owner.getWorld(), center, RING_GROUND);
        center.setYaw(0);
        center.setPitch(0);
        double radius = Math.max(1, params().num("sumo_radius"));
        long now = ctx().currentTick();
        long duration = Math.max(1, params().ticks("sumo_duration"));
        Bout created = new Bout(rival, center, radius, now + duration);
        bout = created;
        raiseBales(owner, center, radius, BOUT_BALES, created.bales, duration + 10);
        Vfx.ring(ctx(), center.clone().add(0, 0.1, 0), radius, 30, 0, Particle.DUST, SAND_DUST, true);
        Fx.sound(center, Sound.BLOCK_GRASS_PLACE, 1.0f, 0.8f);
        Fx.sound(center, Sound.ENTITY_IRON_GOLEM_ATTACK, 0.8f, 1.3f);
        Fx.sound(center, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1.0f, 0.6f);
        if (rival instanceof Player rivalPlayer) {
            created.rule = ctx().prompt(owner, rivalPlayer, Prompt.builder(Text.mm("<b><" + MOSS_TAG + ">씨름 한판!</"
                            + MOSS_TAG + "></b> <gray>판 밖으로 먼저 나가면 집니다 · 갓파를 밀어내세요</gray>"), duration)
                    .priority(Prompt.NORMAL)
                    .overhead(Text.mm("<b><" + MOSS_TAG + ">씨름!</" + MOSS_TAG + "></b>"))
                    .maxWait(0)
                    .build());
        }
        owner.sendActionBar(Text.mm("<b><" + MOSS_TAG + ">씨름 한판!</" + MOSS_TAG + "></b> <gray>상대를 판 밖으로 "
                + "밀어내세요</gray>"));
        Loop.repeat(ctx(), owner, 1, 1, duration, i -> boutStep(owner, created, i), () -> {
            if (!created.over) {
                draw(owner, created);
            }
        });
        return ActivationResult.success();
    }

    private boolean boutStep(Player owner, Bout b, long i) {
        if (b.over || bout != b) {
            return false;
        }
        LivingEntity rival = b.rival;
        if (!rival.isValid() || rival.isDead() || !ctx().isEnemy(owner, rival)) {
            endBout(owner, b);
            return false;
        }
        if (i % 2 == 0) {
            Vfx.line(ctx(), waist(owner), waist(rival), 3, Particle.DUST, BELT_DUST, false);
        }
        if (i % 4 == 0) {
            Vfx.ring(ctx(), b.center.clone().add(0, 0.1, 0), b.radius, 24, i * 0.1, Particle.DUST, SAND_DUST, false);
        }
        boolean ownerOut = flatDistance(owner.getLocation(), b.center) > b.radius;
        boolean rivalOut = flatDistance(rival.getLocation(), b.center) > b.radius;
        if (rivalOut && !ownerOut) {
            win(owner, b);
            return false;
        }
        if (ownerOut && !rivalOut) {
            lose(owner, b);
            return false;
        }
        if (ownerOut) {
            draw(owner, b);
            return false;
        }
        return true;
    }

    private void win(Player owner, Bout b) {
        LivingEntity rival = b.rival;
        Location at = Fx.body(rival);
        Combat.hit(ctx(), owner, rival, params().num("sumo_damage"));
        if (Combat.isTargetable(ctx(), owner, rival)) {
            knockDown(owner, rival, params().ticks("sumo_down"));
        }
        Vfx.burst(ctx(), at, PALETTE, 0.8, Particle.CLOUD, false);
        Vfx.cloud(ctx(), rival.getLocation().add(0, 0.2, 0), Particle.DUST, 14, 0.6, 0, SAND_DUST, false);
        Fx.sound(at, Sound.BLOCK_ANVIL_LAND, 0.5f, 1.4f);
        Fx.sound(at, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 0.8f);
        cheer(owner, "<b><" + CUCUMBER_TAG + ">이겼다!</" + CUCUMBER_TAG + "></b>");
        endBout(owner, b);
    }

    private void lose(Player owner, Bout b) {
        knockDown(owner, owner, params().ticks("sumo_down"));
        Fx.sound(owner.getLocation(), Sound.ENTITY_PLAYER_HURT, 0.6f, 0.7f);
        Fx.sound(owner.getLocation(), Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 0.8f, 0.6f);
        cheer(owner, "<gray>졌다…</gray>");
        endBout(owner, b);
    }

    private void draw(Player owner, Bout b) {
        Combat.hit(ctx(), owner, b.rival, params().num("draw_damage"));
        Vfx.cloud(ctx(), Fx.body(b.rival), Particle.SWEEP_ATTACK, 2, 0.2, 0, null, false);
        Fx.sound(b.center, Sound.ENTITY_PLAYER_BREATH, 1.0f, 0.8f);
        cheer(owner, "<gray>무승부 — 둘 다 지쳤다</gray>");
        spill(owner, "힘을 다 써서");
        endBout(owner, b);
    }

    private void cheer(Player owner, String mini) {
        if (room(1)) {
            keep(Displays.text(ctx(), owner, owner.getLocation().add(0, owner.getHeight() + 0.9, 0), Text.mm(mini),
                    0.9f, 26, viewer -> !ctx().isConcealedFrom(owner, viewer)));
        }
    }

    private void endBout(Player owner, Bout b) {
        b.over = true;
        if (bout == b) {
            bout = null;
        }
        PromptHandle rule = b.rule;
        if (rule != null && rule.isActive()) {
            rule.cancel();
        }
        sinkBales(owner, b.bales);
    }

    // ------------------------------------------------------------------ shared ring pieces

    private void raiseBales(Player owner, Location center, double radius, int count, List<BlockDisplay> into,
                            long lifetime) {
        for (int k = 0; k < count; k++) {
            if (!room(1)) {
                break;
            }
            double angle = k * Math.PI * 2 / count;
            Location at = center.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
            float yaw = (float) Math.toDegrees(-angle);
            BlockDisplay bale = keep(Displays.block(ctx(), owner, at, BALE.createBlockData(),
                    Displays.transform(0, -BALE_SIZE * 0.5f, 0, BALE_SIZE * 1.6f, 0.01f, BALE_SIZE, yaw, 0, 0),
                    lifetime));
            Displays.animateNextTick(ctx(), owner, bale,
                    Displays.transform(0, BALE_SIZE * 0.5f, 0, BALE_SIZE * 1.6f, BALE_SIZE, BALE_SIZE, yaw, 0, 0), 6);
            into.add(bale);
        }
    }

    private void sinkBales(Player owner, List<BlockDisplay> bales) {
        for (BlockDisplay bale : bales) {
            if (bale.isValid()) {
                Displays.shrinkAway(ctx(), owner, bale, 8);
            }
        }
        bales.clear();
    }

    /** Down on the ground: no walking, no jumping, stars over the head. */
    private void knockDown(Player owner, LivingEntity target, long ticks) {
        if (ticks <= 0) {
            return;
        }
        ctx().addModifier(owner, target, Attribute.MOVEMENT_SPEED, -1, Operation.MULTIPLY_SCALAR_1, ticks);
        ctx().addModifier(owner, target, Attribute.JUMP_STRENGTH, -1, Operation.MULTIPLY_SCALAR_1, ticks);
        Loop.repeat(ctx(), owner, 0, 2, Math.max(1, ticks / 2), i -> {
            if (!target.isValid() || target.isDead()) {
                return false;
            }
            Location over = target.getLocation().add(0, target.getHeight() + 0.25, 0);
            for (int s = 0; s < 3; s++) {
                double angle = i * 0.6 + s * Math.PI * 2 / 3;
                Vfx.dot(over.clone().add(Math.cos(angle) * 0.4, 0, Math.sin(angle) * 0.4), Particle.DUST, STAR_DUST,
                        false);
            }
            return true;
        });
    }

    private static Location waist(LivingEntity entity) {
        return entity.getLocation().add(0, entity.getHeight() * 0.5, 0);
    }

    private static double flatDistance(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    // ================================================================== ultimate: the great ring

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        if (grand != null) {
            return ActivationResult.fail("<gray>이미 큰 씨름판이 깔려 있습니다</gray>");
        }
        Bout old = bout;
        if (old != null) {
            endBout(owner, old);
        }
        Location center = owner.getLocation().add(0, 0.5, 0);
        Spots.snapToGround(owner.getWorld(), center, RING_GROUND);
        center.setYaw(0);
        center.setPitch(0);
        double radius = Math.max(1, params().num("grand_radius"));
        long now = ctx().currentTick();
        long telegraph = Math.max(1, params().ticks("grand_telegraph"));
        long duration = Math.max(1, params().ticks("grand_duration"));
        Grand ring = new Grand(center, radius, now + telegraph, now + telegraph + duration);
        grand = ring;
        if (!full) {
            refill(owner, false);
        }
        ctx().addModifier(owner, owner, Attribute.KNOCKBACK_RESISTANCE, 1, Operation.ADD_NUMBER, telegraph + duration);
        raiseBales(owner, center, radius, GRAND_BALES, ring.bales, telegraph + duration + 12);
        Fx.sound(center, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1.2f, 0.5f);
        Fx.sound(center, Sound.ENTITY_RAVAGER_STEP, 1.2f, 0.6f);
        Fx.sound(center, Sound.BLOCK_BELL_USE, 1.0f, 0.8f);
        Loop.repeat(ctx(), owner, 0, 1, telegraph + duration, i -> grandStep(owner, ring, i, telegraph),
                () -> endGrand(owner, ring));
        return ActivationResult.success();
    }

    private boolean grandStep(Player owner, Grand ring, long i, long telegraph) {
        if (grand != ring) {
            return false;
        }
        long now = ctx().currentTick();
        Location floor = ring.center.clone().add(0, 0.1, 0);
        if (i < telegraph) {
            double t = (double) (i + 1) / telegraph;
            Vfx.ring(ctx(), floor, ring.radius, 32, i * 0.08, Particle.DUST, SAND_DUST, true);
            Vfx.ring(ctx(), floor, ring.radius * (1.4 - 0.4 * t), 20, -i * 0.1, Particle.DUST, MOSS_DUST, true);
            if (i == telegraph - 1) {
                closeRing(owner, ring);
            }
            return true;
        }
        if (i % 3 == 0) {
            Vfx.ring(ctx(), floor, ring.radius, 30, i * 0.05, Particle.DUST, SAND_DUST, true);
            Vfx.dot(Vfx.scatter(floor, ring.radius), Particle.DUST, SAND_DUST, false);
        }
        double chargeSpeed = Math.max(0, params().num("charge_push")) / PUSH_REACH;
        long chargeGap = params().ticks("charge_cooldown");
        for (UUID id : List.copyOf(ring.contestants)) {
            if (ring.out.contains(id)) {
                continue;
            }
            Entity entity = Bukkit.getEntity(id);
            if (!(entity instanceof LivingEntity contestant) || !Combat.isTargetable(ctx(), owner, contestant)) {
                ring.out.add(id);
                continue;
            }
            if (flatDistance(contestant.getLocation(), ring.center) > ring.radius) {
                ringOut(owner, ring, contestant);
                continue;
            }
            if (owner.isSprinting() && chargeSpeed > 0
                    && now >= ring.charged.getOrDefault(id, Long.MIN_VALUE / 2)) {
                Vector offset = contestant.getLocation().toVector().subtract(owner.getLocation().toVector());
                Vector flat = new Vector(offset.getX(), 0, offset.getZ());
                if (flat.length() <= CHARGE_REACH && flat.lengthSquared() > 1.0E-6) {
                    Vector away = flat.clone().normalize();
                    if (Motion.horizontalFacing(owner.getLocation()).dot(away) >= CHARGE_DOT) {
                        ring.charged.put(id, now + chargeGap);
                        Motion.push(contestant, away.multiply(chargeSpeed).setY(PUSH_LIFT), MAX_SPEED);
                        Location hit = waist(contestant);
                        Vfx.burst(ctx(), hit, PALETTE, 0.5, Particle.CLOUD, false);
                        Fx.sound(hit, Sound.ENTITY_IRON_GOLEM_ATTACK, 0.9f, 1.1f + Fx.jitter(0.1f));
                        Fx.sound(hit, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.8f, 0.8f);
                    }
                }
            }
        }
        return true;
    }

    /** The ring closes: whoever stands inside now is in the bout. */
    private void closeRing(Player owner, Grand ring) {
        Fx.sound(ring.center, Sound.ENTITY_IRON_GOLEM_ATTACK, 1.2f, 0.7f);
        Fx.sound(ring.center, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1.2f, 0.5f);
        Vfx.shockwave(ctx(), owner, ring.center.clone().add(0, 0.12, 0), 0.5, ring.radius, 8, Particle.DUST,
                SAND_DUST, true);
        long left = Math.max(1, ring.until - ctx().currentTick());
        Map<UUID, LivingEntity> inside = new LinkedHashMap<>();
        for (LivingEntity enemy : ctx().enemiesNear(owner, ring.center, ring.radius + 1)) {
            if (flatDistance(enemy.getLocation(), ring.center) <= ring.radius) {
                inside.put(enemy.getUniqueId(), enemy);
            }
        }
        ring.contestants.addAll(inside.keySet());
        for (LivingEntity enemy : inside.values()) {
            Vfx.line(ctx(), waist(owner), waist(enemy), 2, Particle.DUST, BELT_DUST, false);
            if (enemy instanceof Player rival) {
                ring.rules.add(ctx().prompt(owner, rival, Prompt.builder(Text.mm("<b><" + MOSS_TAG + ">대씨름판!</"
                                + MOSS_TAG + "></b> <gray>판 밖으로 밀려나면 " + AbilityInfo.formatNumber(
                                params().num("grand_damage")) + " 피해 · 넘어짐</gray>"), left)
                        .priority(Prompt.HIGH)
                        .overhead(Text.mm("<b><" + MOSS_TAG + ">씨름!</" + MOSS_TAG + "></b>"))
                        .maxWait(0)
                        .build()));
            }
        }
        owner.sendActionBar(Text.mm("<b><" + MOSS_TAG + ">대씨름판!</" + MOSS_TAG + "></b> <gray>" + inside.size()
                + "명이 씨름판에 올랐습니다 · 달려서 부딪혀 밀어내세요</gray>"));
    }

    private void ringOut(Player owner, Grand ring, LivingEntity contestant) {
        ring.out.add(contestant.getUniqueId());
        Location at = waist(contestant);
        Combat.hit(ctx(), owner, contestant, params().num("grand_damage"));
        if (Combat.isTargetable(ctx(), owner, contestant)) {
            knockDown(owner, contestant, params().ticks("grand_down"));
        }
        Vfx.burst(ctx(), at, PALETTE, 0.9, Particle.CLOUD, true);
        Vfx.cloud(ctx(), contestant.getLocation().add(0, 0.2, 0), Particle.DUST, 16, 0.6, 0, SAND_DUST, false);
        Fx.sound(at, Sound.BLOCK_ANVIL_LAND, 0.6f, 1.2f);
        Fx.sound(at, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 0.7f);
        if (room(1)) {
            keep(Displays.text(ctx(), owner, contestant.getLocation().add(0, contestant.getHeight() + 0.9, 0),
                    Text.mm("<b><" + CUCUMBER_TAG + ">탈락!</" + CUCUMBER_TAG + "></b>"), 0.9f, 26,
                    viewer -> !ctx().isConcealedFrom(contestant, viewer)));
        }
    }

    private void endGrand(Player owner, Grand ring) {
        if (grand == ring) {
            grand = null;
        }
        for (PromptHandle rule : ring.rules) {
            if (rule.isActive()) {
                rule.cancel();
            }
        }
        sinkBales(owner, ring.bales);
        Fx.sound(ring.center, Sound.BLOCK_GRASS_BREAK, 1.0f, 0.8f);
    }

    // ================================================================== display budget

    private boolean room(int count) {
        live.removeIf(display -> !display.isValid());
        return live.size() + dish.size() + 1 + count <= MAX_DISPLAYS;
    }

    private <T extends Display> T keep(T display) {
        live.add(display);
        return display;
    }
}
