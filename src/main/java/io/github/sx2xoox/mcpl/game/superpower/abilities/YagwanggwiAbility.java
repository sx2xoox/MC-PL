package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
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
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.game.superpower.ability.Prompt;
import io.github.sx2xoox.mcpl.game.superpower.ability.PromptHandle;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 야광귀 — the New Year's night ghost of Korean folklore that comes down to the villages and walks off in whatever shoes
 * fit it. It wears the shoes it stole (each pair makes it quicker), its glowing hand snatches an enemy's shoes off their
 * feet, and a barefoot enemy cannot sprint (Minecraft PvP's sprint knockback, chase and sprint jump) until it hits the
 * ghost and takes them back. On the New Year's night of its ultimate it darkens everything around it and brushes shoes
 * off everyone it passes, until the rooster crows and it throws every pair back at its owner.
 *
 * <p>Look (New Year's night navy, glow lime, straw yellow): stolen straw sandals dangle from the ghost's waist glowing
 * lime; a barefoot enemy's boots vanish from everyone's view and it leaves glowing footprints. The snatch is a thin lime
 * arm shooting out with a glowing hand at its tip. The night is a navy haze ring with white stars overhead and a lime
 * glow around the ghost; dawn flashes gold and the sandals fly back in arcs.
 */
public final class YagwanggwiAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("yagwanggwi", "야광귀", Material.LEATHER_BOOTS)
            .color(TextColor.color(0xB6FF6A))
            .difficulty(1)
            .passive("신어 보기",
                    "훔친 신을 신고 다녀, 지금 가진 신 한 켤레마다 이동 속도가 {shoe_speed}% 빨라집니다(최대 "
                            + "{shoe_max}켤레, 한 켤레는 {shoe_keep}초 동안 신고 다님). 신을 잃은 적이 맨발인 동안 나를 "
                            + "근접 공격으로 맞히면 신을 되찾아 곧바로 맨발이 풀리고, 그 켤레는 사라집니다.")
            .active("신 훔치기",
                    "조준한 적({steal_range}블록)에게 야광 손을 쭉 뻗어 {steal_damage} 피해를 주고 신을 훔칩니다. "
                            + "{barefoot_duration}초간 그 적은 맨발 — 달릴 수 없고, 이동 속도가 {barefoot_slow}%, 방어력이 "
                            + "{barefoot_armor} 줄며, 낙하 피해를 {barefoot_fall}% 더 받습니다(신발이 정말 사라져 보이고, "
                            + "배고픔이 잠시 3칸으로 묶여 자연 회복도 멈춤).",
                    9)
            .ultimate("설날 밤",
                    "{night_duration}초간 설날 밤이 됩니다. 내 주위 {night_radius}블록의 적은 어둠에 휩싸이고, 나는 "
                            + "야광으로 빛나는 귀신이 되어 이동 속도가 {night_speed}% 빨라집니다. {brush_radius}블록 안을 "
                            + "스친 적은 신을 빼앗겨 {brush_damage} 피해를 입고 {brush_barefoot}초간 맨발이 됩니다(같은 적은 "
                            + "{brush_cooldown}초마다). 닭이 울며 밤이 끝날 때 가진 신을 모두 주인에게 도로 던져 한 켤레마다 "
                            + "{return_damage} 피해를 줍니다(적마다 최대 {return_max}켤레).")
            .ultimateNotStandalone()
            .param("shoe_speed", 8)
            .param("shoe_max", 3)
            .param("shoe_keep", 20)
            .param("steal_range", 12)
            .param("steal_damage", 3)
            .param("barefoot_duration", 5)
            .param("barefoot_slow", 15)
            .param("barefoot_armor", 2)
            .param("barefoot_fall", 50)
            .param("night_duration", 7)
            .param("night_radius", 12)
            .param("night_speed", 25)
            .param("brush_radius", 1.5)
            .param("brush_damage", 2)
            .param("brush_barefoot", 4)
            .param("brush_cooldown", 2)
            .param("return_damage", 2)
            .param("return_max", 3)
            .build();

    private static final String GLOW_TAG = "#B6FF6A";
    private static final String STRAW_TAG = "#D8B864";

    // ------------------------------------------------------------------ look
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xE9FFD2, 0xB6FF6A, 0x1E2450);
    private static final Color GLOW = Color.fromRGB(0xB6FF6A);
    private static final Color STRAW = Color.fromRGB(0xD8B864);
    private static final Particle.DustOptions GLOW_DUST = new Particle.DustOptions(GLOW, 0.9f);
    private static final Particle.DustOptions SMALL_GLOW = new Particle.DustOptions(GLOW, 0.6f);
    private static final Particle.DustOptions NAVY_DUST = new Particle.DustOptions(Color.fromRGB(0x1E2450), 1.6f);
    private static final Particle.DustOptions STAR_DUST = new Particle.DustOptions(Color.fromRGB(0xF4F6FF), 0.7f);
    private static final Particle.DustOptions STRAW_DUST = new Particle.DustOptions(STRAW, 0.8f);
    private static final Particle.DustOptions DAWN_DUST = new Particle.DustOptions(Color.fromRGB(0xFFC46A), 1.2f);

    /** Block types (block data is created on use: it needs a running server). */
    private static final Material SANDAL = Material.HAY_BLOCK;
    private static final Material ARM = Material.LIME_STAINED_GLASS;
    private static final Material PALM = Material.LIME_CONCRETE;

    /** Hunger at or below this switches off sprinting (vanilla: more than 6 is needed to sprint). */
    private static final int SPRINT_FOOD = 6;
    private static final int MAX_DISPLAYS = 24;
    private static final int FOLLOW_LERP = 3;
    private static final int REACH_TICKS = 3;
    private static final float ARM_WIDTH = 0.12f;
    private static final float PALM_SIZE = 0.3f;
    private static final float SANDAL_W = 0.2f;
    private static final float SANDAL_H = 0.06f;
    private static final float SANDAL_L = 0.34f;
    private static final long FOOTPRINT_PERIOD = 6;
    private static final long DARKNESS_PERIOD = 10;
    private static final int DARKNESS_TICKS = 40;
    private static final long THROW_GAP = 3;
    private static final long THROW_FLIGHT = 10;

    // ------------------------------------------------------------------ state

    /** An enemy without its shoes. */
    private final class Barefoot {
        final LivingEntity victim;
        final List<NamespacedKey> keys = new ArrayList<>();
        final @Nullable Integer savedFood;
        long until;
        @Nullable PromptHandle notice;

        Barefoot(LivingEntity victim, long until) {
            this.victim = victim;
            this.until = until;
            this.savedFood = victim instanceof Player player ? player.getFoodLevel() : null;
        }
    }

    /** A pair of stolen shoes the ghost wears. */
    private static final class Pair {
        final UUID from;
        final long until;
        final @Nullable BlockDisplay sandal;

        Pair(UUID from, long until, @Nullable BlockDisplay sandal) {
            this.from = from;
            this.until = until;
            this.sandal = sandal;
        }

        void remove() {
            if (sandal != null && sandal.isValid()) {
                sandal.remove();
            }
        }
    }

    private final Map<UUID, Barefoot> barefoot = new LinkedHashMap<>();
    private final List<Pair> pairs = new ArrayList<>();
    private final List<Display> live = new ArrayList<>();
    private final Map<UUID, Long> brushed = new HashMap<>();
    private final Set<UUID> darkened = new HashSet<>();
    private @Nullable NamespacedKey shoeSpeed;
    private int speedPairs;
    private boolean night;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        for (Barefoot b : List.copyOf(barefoot.values())) {
            restore(b, false);
        }
        barefoot.clear();
        for (Pair pair : pairs) {
            pair.remove();
        }
        pairs.clear();
        clearDarkness();
        shoeSpeed = null;
        live.clear();
        night = false;
    }

    // ================================================================== test hooks

    /** Pairs of stolen shoes the ghost wears now. */
    public int pairCount() {
        return pairs.size();
    }

    /** True while {@code entity} is barefoot from this ghost. */
    public boolean isBarefoot(Entity entity) {
        return barefoot.containsKey(entity.getUniqueId());
    }

    // ================================================================== passive: wearing the shoes

    @Override
    public void onTick(long tick) {
        Player owner = player();
        long now = ctx().currentTick();
        Iterator<Pair> it = pairs.iterator();
        boolean changed = false;
        while (it.hasNext()) {
            Pair pair = it.next();
            if (now >= pair.until) {
                it.remove();
                if (pair.sandal != null && pair.sandal.isValid()) {
                    Vfx.cloud(ctx(), pair.sandal.getLocation(), Particle.DUST, 5, 0.1, 0, STRAW_DUST, false);
                }
                pair.remove();
                changed = true;
            }
        }
        if (changed) {
            updateSpeed(owner, true);
        }
        hangSandals(owner, tick);
        tickBarefoot(owner, now);
    }

    /** The sandals dangle from the belt behind the ghost, swinging a little as it moves. */
    private void hangSandals(Player owner, long tick) {
        int n = pairs.size();
        if (n == 0) {
            return;
        }
        float yaw = owner.getBodyYaw();
        for (int i = 0; i < n; i++) {
            BlockDisplay sandal = pairs.get(i).sandal;
            if (sandal == null || !sandal.isValid()) {
                continue;
            }
            double deg = yaw + 180 + (i - (n - 1) / 2.0) * 40;
            double rad = Math.toRadians(deg);
            Location spot = owner.getLocation().add(-Math.sin(rad) * 0.36, owner.getHeight() * 0.42
                    + Math.sin((tick + i * 7) * 0.25) * 0.03, Math.cos(rad) * 0.36);
            Displays.moveTo(sandal, spot);
            if (tick % 10 == i) {
                Displays.animate(sandal, sandalShape((float) deg, 75), 4);
            }
        }
        if (tick % 8 == 0) {
            BlockDisplay first = pairs.getFirst().sandal;
            if (first != null && first.isValid()) {
                Vfx.dot(first.getLocation().add(Fx.jitter(0.15f), Fx.jitter(0.1f), Fx.jitter(0.15f)),
                        Particle.DUST, SMALL_GLOW, false);
            }
        }
    }

    private static Transformation sandalShape(float yaw, float pitch) {
        return Displays.transform(0, 0, 0, SANDAL_W, SANDAL_H, SANDAL_L, yaw, pitch, 0);
    }

    private void updateSpeed(Player owner, boolean tell) {
        int count = Math.min(pairs.size(), Math.max(0, params().integer("shoe_max")));
        if (count == speedPairs) {
            return;
        }
        speedPairs = count;
        if (shoeSpeed != null) {
            ctx().removeModifier(owner, shoeSpeed);
            shoeSpeed = null;
        }
        double each = Math.max(0, params().num("shoe_speed")) / 100.0;
        if (count > 0 && each > 0) {
            shoeSpeed = ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, each * count,
                    Operation.MULTIPLY_SCALAR_1, 0);
        }
        if (tell) {
            owner.sendActionBar(Text.mm("<" + STRAW_TAG + ">짚신 " + count + "켤레</" + STRAW_TAG + "> <gray>· 이동 "
                    + "속도 +" + AbilityInfo.formatNumber(params().num("shoe_speed") * count) + "%</gray>"));
        }
    }

    private void addPair(Player owner, LivingEntity victim, Location from) {
        int max = Math.max(1, params().integer("shoe_max"));
        while (pairs.size() >= max) {
            Pair oldest = pairs.removeFirst();
            oldest.remove();
        }
        long now = ctx().currentTick();
        BlockDisplay sandal = null;
        if (room(1)) {
            sandal = Displays.block(ctx(), owner, from, SANDAL.createBlockData(), sandalShape(0, 0), 0);
            sandal.setTeleportDuration(6);
            Displays.outline(sandal, GLOW);
            BlockDisplay created = sandal;
            ctx().runLater(owner, 6, () -> {
                if (created.isValid()) {
                    created.setTeleportDuration(FOLLOW_LERP);
                }
            });
        }
        pairs.add(new Pair(victim.getUniqueId(), now + Math.max(1, params().ticks("shoe_keep")), sandal));
        updateSpeed(owner, true);
    }

    /** A barefoot enemy hit the ghost in melee: it takes its shoes back. */
    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled() || !(event instanceof EntityDamageByEntityEvent byEntity)) {
            return;
        }
        if (event.getCause() != DamageCause.ENTITY_ATTACK && event.getCause() != DamageCause.ENTITY_SWEEP_ATTACK) {
            return;
        }
        Entity damager = byEntity.getDamager();
        Barefoot b = barefoot.get(damager.getUniqueId());
        if (b == null) {
            return;
        }
        Player owner = player();
        int returned = 0;
        Iterator<Pair> it = pairs.iterator();
        while (it.hasNext()) {
            Pair pair = it.next();
            if (pair.from.equals(damager.getUniqueId())) {
                it.remove();
                if (pair.sandal != null && pair.sandal.isValid()) {
                    Vfx.trail(pair.sandal.getLocation(), b.victim.getLocation().add(0, 0.2, 0), STRAW, 8, false);
                }
                pair.remove();
                returned++;
            }
        }
        restore(b, true);
        barefoot.remove(damager.getUniqueId());
        updateSpeed(owner, false);
        Location feet = b.victim.getLocation().add(0, 0.2, 0);
        Vfx.cloud(ctx(), feet, Particle.DUST, 10, 0.3, 0, STRAW_DUST, false);
        Fx.sound(feet, Sound.ITEM_ARMOR_EQUIP_LEATHER, 1.0f, 1.0f);
        Fx.sound(owner.getLocation(), Sound.ENTITY_VEX_HURT, 0.8f, 1.4f);
        owner.sendActionBar(Text.mm("<gray><victim>이(가) 신을 되찾아 갔습니다" + (returned > 0 ? " · 짚신 "
                + returned + "켤레를 잃었습니다" : "") + "</gray>", Placeholder.component("victim", b.victim.name())));
    }

    // ------------------------------------------------------------------ barefoot

    private void makeBarefoot(Player owner, LivingEntity victim, long ticks) {
        long now = ctx().currentTick();
        Barefoot b = barefoot.get(victim.getUniqueId());
        if (b != null) {
            b.until = Math.max(b.until, now + ticks);
            return;
        }
        b = new Barefoot(victim, now + ticks);
        barefoot.put(victim.getUniqueId(), b);
        double slow = Math.clamp(params().num("barefoot_slow") / 100.0, 0.0, 1.0);
        if (slow > 0) {
            b.keys.add(ctx().addModifier(owner, victim, Attribute.MOVEMENT_SPEED, -slow, Operation.MULTIPLY_SCALAR_1,
                    0));
        }
        double armor = Math.max(0, params().num("barefoot_armor"));
        if (armor > 0) {
            b.keys.add(ctx().addModifier(owner, victim, Attribute.ARMOR, -armor, Operation.ADD_NUMBER, 0));
        }
        double fall = Math.max(0, params().num("barefoot_fall")) / 100.0;
        if (fall > 0) {
            b.keys.add(ctx().addModifier(owner, victim, Attribute.FALL_DAMAGE_MULTIPLIER, fall, Operation.ADD_NUMBER,
                    0));
        }
        if (victim instanceof Player player) {
            player.setSprinting(false);
            if (player.getFoodLevel() > SPRINT_FOOD) {
                player.setFoodLevel(SPRINT_FOOD);
            }
            b.notice = ctx().prompt(owner, player, Prompt.builder(Text.mm("<b><" + GLOW_TAG + ">맨발!</" + GLOW_TAG
                            + "></b> <gray>신을 빼앗겨 달릴 수 없습니다 · 야광귀를 때리면 되찾습니다</gray>"), ticks)
                    .priority(Prompt.LOW)
                    .maxWait(10)
                    .build());
        }
        hideBoots(victim);
    }

    private void tickBarefoot(Player owner, long now) {
        Iterator<Barefoot> it = barefoot.values().iterator();
        while (it.hasNext()) {
            Barefoot b = it.next();
            LivingEntity victim = b.victim;
            if (!victim.isValid() || victim.isDead() || !ctx().isEnemy(owner, victim)) {
                it.remove();
                restore(b, false);
                continue;
            }
            if (now >= b.until) {
                it.remove();
                restore(b, true);
                continue;
            }
            if (victim instanceof Player player) {
                if (player.getFoodLevel() > SPRINT_FOOD) {
                    player.setFoodLevel(SPRINT_FOOD);
                }
                if (player.isSprinting()) {
                    player.setSprinting(false);
                }
            }
            hideBoots(victim);
            if (now % FOOTPRINT_PERIOD == 0 && !ctx().isConcealedFrom(victim, owner)) {
                Vfx.dot(victim.getLocation().add(Fx.jitter(0.15f), 0.04, Fx.jitter(0.15f)), Particle.DUST,
                        SMALL_GLOW, false);
            }
        }
    }

    /** Every viewer sees the barefoot entity's boots gone (re-sent every tick: the server re-sends real gear). */
    private static void hideBoots(LivingEntity victim) {
        ItemStack none = ItemStack.empty();
        for (Player viewer : victim.getWorld().getPlayers()) {
            if (!viewer.equals(victim)) {
                viewer.sendEquipmentChange(victim, EquipmentSlot.FEET, none);
            }
        }
    }

    /** The shoes are back: modifiers off, hunger back, real boots shown again. */
    private void restore(Barefoot b, boolean effects) {
        LivingEntity victim = b.victim;
        for (NamespacedKey key : b.keys) {
            ctx().removeModifier(victim, key);
        }
        b.keys.clear();
        PromptHandle notice = b.notice;
        if (notice != null && notice.isActive()) {
            notice.cancel();
        }
        if (victim instanceof Player player && b.savedFood != null && player.isOnline()) {
            player.setFoodLevel(Math.max(player.getFoodLevel(), b.savedFood));
        }
        EntityEquipment worn = victim.getEquipment();
        if (worn != null && victim.isValid()) {
            ItemStack real = worn.getItem(EquipmentSlot.FEET);
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (!viewer.equals(victim)) {
                    viewer.sendEquipmentChange(victim, EquipmentSlot.FEET, real);
                }
            }
        }
        if (effects && victim.isValid()) {
            Vfx.cloud(ctx(), victim.getLocation().add(0, 0.15, 0), Particle.DUST, 6, 0.25, 0, STRAW_DUST, false);
        }
    }

    // ================================================================== active: the snatch

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        double range = Math.max(1, params().num("steal_range"));
        LivingEntity victim = ctx().findTarget(owner, range);
        if (victim == null) {
            return ActivationResult.fail("<gray>조준한 곳에 신을 훔칠 적이 없습니다</gray>");
        }
        Location hand = handSpot(owner);
        Location goal = victim.getLocation().add(0, 0.25, 0);
        Vector delta = goal.toVector().subtract(hand.toVector());
        double length = Math.max(0.3, delta.length());
        Vector dir = delta.clone().normalize();
        Location look = hand.clone();
        look.setDirection(dir);
        float yaw = look.getYaw();
        float pitch = look.getPitch();
        BlockDisplay arm = room(2) ? keep(Displays.block(ctx(), owner, hand, ARM.createBlockData(),
                armShape(dir, 0.1, yaw, pitch), 14)) : null;
        BlockDisplay palm = arm != null ? keep(Displays.block(ctx(), owner, hand, PALM.createBlockData(),
                palmShape(dir, 0.1, yaw, pitch), 14)) : null;
        if (arm != null && palm != null) {
            Displays.outline(palm, GLOW);
            Displays.animateNextTick(ctx(), owner, arm, armShape(dir, length, yaw, pitch), REACH_TICKS - 1);
            Displays.animateNextTick(ctx(), owner, palm, palmShape(dir, length, yaw, pitch), REACH_TICKS - 1);
        }
        Fx.sound(hand, Sound.ENTITY_VEX_CHARGE, 1.0f, 1.3f + Fx.jitter(0.1f));
        Fx.sound(hand, Sound.ENTITY_ALLAY_ITEM_THROWN, 0.8f, 0.8f);
        Vfx.line(ctx(), hand, goal, 2, Particle.DUST, SMALL_GLOW, false);
        ctx().runLater(owner, REACH_TICKS, () -> {
            Location feet = victim.isValid() ? victim.getLocation().add(0, 0.2, 0) : goal;
            if (Combat.isTargetable(ctx(), owner, victim)) {
                Combat.hit(ctx(), owner, victim, params().num("steal_damage"));
            }
            if (Combat.isTargetable(ctx(), owner, victim)) {
                makeBarefoot(owner, victim, Math.max(1, params().ticks("barefoot_duration")));
                addPair(owner, victim, feet);
                Vfx.burst(ctx(), feet, PALETTE, 0.5, Particle.END_ROD, false);
                Fx.sound(feet, Sound.ENTITY_ALLAY_ITEM_TAKEN, 1.0f, 1.0f);
                Fx.sound(owner.getLocation(), Sound.ENTITY_WITCH_CELEBRATE, 0.7f, 1.7f);
            }
            if (arm != null && palm != null) {
                Displays.animate(arm, armShape(dir, 0.1, yaw, pitch), REACH_TICKS);
                Displays.animate(palm, palmShape(dir, 0.1, yaw, pitch), REACH_TICKS);
                ctx().runLater(owner, REACH_TICKS + 1, () -> {
                    arm.remove();
                    palm.remove();
                });
            }
        });
        return ActivationResult.success();
    }

    private static Location handSpot(Player owner) {
        Location eye = owner.getEyeLocation();
        Vector forward = Motion.horizontalFacing(owner.getLocation());
        Vector right = new Vector(-forward.getZ(), 0, forward.getX());
        return eye.add(right.multiply(0.35)).add(forward.multiply(0.2)).add(0, -0.45, 0);
    }

    /** The arm: a thin bar from the hand along {@code dir}, {@code length} long. */
    private static Transformation armShape(Vector dir, double length, float yaw, float pitch) {
        Vector middle = dir.clone().multiply(length / 2);
        return Displays.transform((float) middle.getX(), (float) middle.getY(), (float) middle.getZ(), ARM_WIDTH,
                ARM_WIDTH, (float) length, yaw, pitch, 0);
    }

    private static Transformation palmShape(Vector dir, double length, float yaw, float pitch) {
        Vector tip = dir.clone().multiply(length);
        return Displays.transform((float) tip.getX(), (float) tip.getY(), (float) tip.getZ(), PALM_SIZE, PALM_SIZE * 0.4f,
                PALM_SIZE, yaw, pitch, 0);
    }

    // ================================================================== ultimate: New Year's night

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        if (night) {
            return ActivationResult.fail("<gray>이미 설날 밤입니다</gray>");
        }
        night = true;
        brushed.clear();
        long duration = Math.max(1, params().ticks("night_duration"));
        double speed = Math.max(0, params().num("night_speed")) / 100.0;
        if (speed > 0) {
            ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, speed, Operation.MULTIPLY_SCALAR_1, duration);
        }
        Location at = owner.getLocation().add(0, 1, 0);
        owner.showTitle(Title.title(Text.mm("<b><gradient:#B6FF6A:#6E7BD6>설날 밤</gradient></b>"),
                Text.mm("<gray>스치는 적의 신을 모두 훔치세요</gray>")));
        Fx.sound(at, Sound.AMBIENT_CAVE, 1.0f, 1.4f);
        Fx.sound(at, Sound.ENTITY_VEX_AMBIENT, 1.0f, 0.8f);
        Fx.sound(at, Sound.BLOCK_BELL_RESONATE, 0.8f, 0.6f);
        Vfx.burst(ctx(), at, PALETTE, 1.2, Particle.END_ROD, true);
        double radius = Math.max(1, params().num("night_radius"));
        Loop.repeat(ctx(), owner, 0, 1, duration, i -> nightStep(owner, i, radius), () -> dawn(owner));
        return ActivationResult.success();
    }

    private boolean nightStep(Player owner, long i, double radius) {
        long now = ctx().currentTick();
        Location feet = owner.getLocation();
        Location body = Fx.body(owner);
        for (int k = 0; k < 2; k++) {
            Vfx.dot(body.clone().add(Fx.jitter(0.4f), Fx.jitter(0.8f), Fx.jitter(0.4f)), Particle.DUST, GLOW_DUST,
                    false);
        }
        if (i % 4 == 0) {
            Vfx.dot(body.clone().add(Fx.jitter(0.3f), Fx.jitter(0.6f), Fx.jitter(0.3f)), Particle.END_ROD, null,
                    false);
        }
        if (i % 5 == 0) {
            Vfx.ring(ctx(), feet.clone().add(0, 0.2, 0), radius, 30, i * 0.03, Particle.DUST, NAVY_DUST, true);
            Vfx.dot(Vfx.scatter(feet, radius).add(0, 6 + Fx.jitter(1.5f), 0), Particle.DUST, STAR_DUST, true);
        }
        if (i % DARKNESS_PERIOD == 0) {
            for (LivingEntity enemy : ctx().enemiesNear(owner, feet, radius)) {
                enemy.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, DARKNESS_TICKS, 0, false, false,
                        true));
                darkened.add(enemy.getUniqueId());
            }
        }
        double reach = Math.max(0.5, params().num("brush_radius"));
        long gap = params().ticks("brush_cooldown");
        for (LivingEntity enemy : ctx().enemiesNear(owner, body, reach)) {
            if (now < brushed.getOrDefault(enemy.getUniqueId(), Long.MIN_VALUE / 2)) {
                continue;
            }
            brushed.put(enemy.getUniqueId(), now + gap);
            Location shoes = enemy.getLocation().add(0, 0.2, 0);
            Combat.hit(ctx(), owner, enemy, params().num("brush_damage"));
            if (!Combat.isTargetable(ctx(), owner, enemy)) {
                continue;
            }
            makeBarefoot(owner, enemy, Math.max(1, params().ticks("brush_barefoot")));
            addPair(owner, enemy, shoes);
            Vfx.burst(ctx(), shoes, PALETTE, 0.4, Particle.END_ROD, false);
            Fx.sound(shoes, Sound.ENTITY_ALLAY_ITEM_TAKEN, 1.0f, 1.3f + Fx.jitter(0.1f));
            Fx.sound(shoes, Sound.ENTITY_VEX_CHARGE, 0.6f, 1.6f);
        }
        return true;
    }

    /** The rooster crows: the night lifts and every stolen pair flies back to its owner's head. */
    private void dawn(Player owner) {
        night = false;
        clearDarkness();
        Location at = owner.getLocation().add(0, 1.2, 0);
        Fx.sound(at, Sound.ENTITY_CHICKEN_AMBIENT, 1.4f, 0.6f);
        Fx.sound(at, Sound.ENTITY_CHICKEN_AMBIENT, 1.2f, 0.75f);
        Fx.sound(at, Sound.BLOCK_BELL_USE, 1.0f, 1.2f);
        at.getWorld().spawnParticle(Particle.FLASH, at, 1, 0, 0, 0, 0, Color.fromRGB(0xFFC46A), true);
        Vfx.ring(ctx(), owner.getLocation().add(0, 0.2, 0), 2.5, 24, 0, Particle.DUST, DAWN_DUST, true);
        if (room(1)) {
            keep(Displays.text(ctx(), owner, owner.getLocation().add(0, owner.getHeight() + 0.8, 0),
                    Text.mm("<b><#FFC46A>꼬끼오—!</#FFC46A></b>"), 1.0f, 30,
                    viewer -> !ctx().isConcealedFrom(owner, viewer)));
        }
        Map<UUID, Integer> counts = new LinkedHashMap<>();
        for (Pair pair : pairs) {
            counts.merge(pair.from, 1, Integer::sum);
        }
        List<Location> starts = new ArrayList<>();
        for (Pair pair : pairs) {
            starts.add(pair.sandal != null && pair.sandal.isValid() ? pair.sandal.getLocation() : Fx.body(owner));
            pair.remove();
        }
        pairs.clear();
        updateSpeed(owner, false);
        int max = Math.max(0, params().integer("return_max"));
        int launched = 0;
        for (Map.Entry<UUID, Integer> entry : counts.entrySet()) {
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (!(entity instanceof LivingEntity victim) || !Combat.isTargetable(ctx(), owner, victim)) {
                continue;
            }
            Barefoot b = barefoot.remove(victim.getUniqueId());
            if (b != null) {
                restore(b, false);
            }
            int n = Math.min(entry.getValue(), max);
            for (int k = 0; k < n; k++) {
                Location from = starts.isEmpty() ? Fx.body(owner) : starts.get(Math.min(launched, starts.size() - 1));
                throwBack(owner, victim, from, THROW_GAP * launched);
                launched++;
            }
        }
    }

    /** One sandal arcs back onto its owner and hits. */
    private void throwBack(Player owner, LivingEntity victim, Location from, long delay) {
        ctx().runLater(owner, Math.max(1, delay), () -> {
            if (!Combat.isTargetable(ctx(), owner, victim)) {
                return;
            }
            Location start = from.clone();
            BlockDisplay sandal = room(1) ? keep(Displays.block(ctx(), owner, start, SANDAL.createBlockData(),
                    sandalShape(0, 0), THROW_FLIGHT + 4)) : null;
            if (sandal != null) {
                sandal.setTeleportDuration(1);
                Displays.outline(sandal, GLOW);
            }
            Fx.sound(start, Sound.ENTITY_SNOWBALL_THROW, 0.8f, 0.9f + Fx.jitter(0.1f));
            Loop.repeat(ctx(), owner, 1, 1, THROW_FLIGHT, i -> {
                double t = (double) (i + 1) / THROW_FLIGHT;
                Location end = victim.getEyeLocation();
                Location at = start.clone().add(end.toVector().subtract(start.toVector()).multiply(t))
                        .add(0, Math.sin(t * Math.PI) * 1.5, 0);
                Vfx.dot(at, Particle.DUST, STRAW_DUST, false);
                if (sandal != null) {
                    Displays.moveTo(sandal, at);
                    Displays.animate(sandal, sandalShape((i + 1) * 60f, (i + 1) * 40f), 1);
                }
                return victim.isValid() && !victim.isDead();
            }, () -> {
                if (sandal != null && sandal.isValid()) {
                    sandal.remove();
                }
                if (!Combat.isTargetable(ctx(), owner, victim)) {
                    return;
                }
                Location head = victim.getEyeLocation();
                Combat.hit(ctx(), owner, victim, params().num("return_damage"));
                Vfx.cloud(ctx(), head, Particle.DUST, 8, 0.25, 0, STRAW_DUST, false);
                Vfx.dot(head, Particle.CRIT, null, false);
                Fx.sound(head, Sound.BLOCK_WOOL_HIT, 1.0f, 0.8f);
                Fx.sound(head, Sound.ENTITY_PLAYER_ATTACK_WEAK, 1.0f, 1.2f);
            });
        });
    }

    private void clearDarkness() {
        for (UUID id : darkened) {
            Entity entity = Bukkit.getEntity(id);
            if (entity instanceof LivingEntity living) {
                PotionEffect effect = living.getPotionEffect(PotionEffectType.DARKNESS);
                if (effect != null && !effect.hasParticles()) {
                    living.removePotionEffect(PotionEffectType.DARKNESS);
                }
            }
        }
        darkened.clear();
    }

    // ================================================================== display budget

    private boolean room(int count) {
        live.removeIf(display -> !display.isValid());
        return live.size() + pairs.size() + count <= MAX_DISPLAYS;
    }

    private <T extends Display> T keep(T display) {
        live.add(display);
        return display;
    }
}
