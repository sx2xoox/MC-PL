package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 바쿠 — the dream eater with an elephant's trunk that devours nightmares (write "바쿠" on a pillow and bad dreams stay
 * away). It eats part of the curses thrown at it and keeps them as black bubbles over its head; the active sucks in a
 * cone with its trunk and steals the enemies' good dreams — their beneficial potion effects and absorption hearts — for
 * itself or the nearest ally; the ultimate spews every nightmare it ate back out over the enemies in front of it.
 *
 * <p>Unlike the 연금술사 (who brews random effects) or the 무당 (who cleanses), the baku moves effects from one player to
 * another. Only potion effects and absorption move: attribute-modifier buffs of other abilities cannot be stolen.
 *
 * <p>Look: dream lilac, night navy and starlight white. The trunk is a curling chain of muted purple segments that
 * reaches out of the face while lilac motes and stars swirl into it; stolen effects fly to the receiver as motes in their
 * own colors. Eaten nightmares orbit over the head as small black glass bubbles; the ultimate swells them, marks the
 * cone on the ground and pours out a torrent of ink, black shards and phantom membranes.
 */
public final class BakuAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("baku", "바쿠", Material.PURPLE_BED)
            .color(TextColor.color(0xB9A3E3))
            .difficulty(2)
            .passive("악몽 먹기",
                    "나에게 걸리는 해로운 효과(구속·나약함·실명·멀미·독·위더·어둠·공중 부양…)를 {eat_chance}% 확률로 먹어 "
                            + "치워 걸리지 않습니다. 먹은 악몽은 머리 위에 검은 거품으로 쌓입니다(최대 {max_nightmares}개).")
            .active("꿈 빨아들이기",
                    "긴 코로 {suck_time}초간 앞쪽 {suck_range}블록 부채꼴({suck_angle}°)을 빨아들여 {suck_damage} 피해를 주고, 맞은 적의 이로운 "
                            + "효과(신속·힘·저항·재생·점프 강화·화염 저항…)와 흡수 체력을 빼앗아 남은 시간만큼(효과는 최대 "
                            + "{steal_duration}초, 흡수 체력은 최대 {steal_absorption}) 내가 받습니다. 웅크리고 쓰면 빼앗은 것을 "
                            + "가장 가까운 아군({gift_range}블록 이내)에게 넘겨 줍니다. 빼앗은 것이 없으면 대신 악몽을 하나 얻고, "
                            + "아무도 맞지 않으면 쿨타임이 {whiff_refund}% 돌아옵니다.",
                    9)
            .ultimate("악몽 토해내기",
                    "{spew_windup}초 뒤 모은 악몽을 앞쪽 {spew_range}블록 부채꼴({spew_angle}°)로 토해 냅니다. 맞은 적은 악몽 "
                            + "하나마다 서로 다른 해로운 효과(구속 II·나약함·멀미·독 {curse_duration}초, 실명 {curse_blind}초) "
                            + "하나와 {nightmare_damage} 피해를 받고, {nightmare_lock}초간 깨지 않는 악몽에 빠져 이로운 효과를 받을 "
                            + "수 없습니다. 나에게 걸려 있던 해로운 효과도 함께 토해 넘기고(최대 {curse_duration}초) 나는 "
                            + "깨끗해집니다. 악몽이 없으면 {empty_damage} 피해와 {empty_slow}초 구속 II만 줍니다.")
            .param("eat_chance", 35)
            .param("max_nightmares", 4)
            .param("suck_time", 0.3)
            .param("suck_range", 8)
            .param("suck_angle", 70)
            .param("suck_damage", 3)
            .param("steal_duration", 6)
            .param("steal_absorption", 4)
            .param("gift_range", 12)
            .param("whiff_refund", 50)
            .param("spew_windup", 0.5)
            .param("spew_range", 10)
            .param("spew_angle", 90)
            .param("curse_duration", 4)
            .param("curse_blind", 1)
            .param("nightmare_damage", 1.5)
            .param("nightmare_lock", 3)
            .param("empty_damage", 3)
            .param("empty_slow", 3)
            .build();

    private static final String LILAC = "#B9A3E3";
    private static final String STAR = "#F5F3FF";
    private static final String NIGHT = "#6A70C8";
    private static final String INK = "#5B3E8A";

    // ------------------------------------------------------------------ look: lilac, night navy, starlight
    /** Starlight white, dream lilac, night navy. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xF5F3FF, 0xB9A3E3, 0x22264B);
    private static final Color LILAC_COLOR = Color.fromRGB(0xB9A3E3);
    private static final Color STAR_COLOR = Color.fromRGB(0xF5F3FF);
    private static final Color NIGHT_COLOR = Color.fromRGB(0x22264B);
    private static final Particle.DustOptions LILAC_DUST = PALETTE.dust(0.8f);
    private static final Particle.DustOptions LILAC_SMALL = PALETTE.dust(0.5f);
    private static final Particle.DustOptions STAR_DUST = new Particle.DustOptions(STAR_COLOR, 0.5f);
    private static final Particle.DustOptions NIGHT_DUST = new Particle.DustOptions(NIGHT_COLOR, 1.0f);
    private static final Particle.DustTransition GLOW = PALETTE.glow(1.0f);
    private static final Particle.DustTransition NIGHTMARE = new Particle.DustTransition(Color.fromRGB(0x5B3E8A),
            Color.fromRGB(0x0B0B18), 1.3f);

    /** Block and item types of the visuals; block data and items are created on use (they need a running server). */
    private static final Material TRUNK = Material.PURPLE_TERRACOTTA;
    private static final Material TRUNK_TIP = Material.MAGENTA_TERRACOTTA;
    private static final Material BUBBLE = Material.BLACK_STAINED_GLASS;
    private static final Material SHARD = Material.TINTED_GLASS;
    private static final Material MEMBRANE = Material.PHANTOM_MEMBRANE;

    /** Display entities one baku keeps alive at most at once (extra visuals fall back to particles). */
    private static final int MAX_DISPLAYS = 24;

    // passive
    /** A repeat of the same curse within this many ticks follows the first roll (abilities re-apply effects). */
    private static final long MIN_MEMORY = 20;
    private static final long MAX_MEMORY = 100;
    private static final long BUBBLE_PERIOD = 2;
    private static final double BUBBLE_ABOVE = 0.55;
    private static final double BUBBLE_ORBIT = 0.42;
    private static final float BUBBLE_SIZE = 0.2f;
    private static final double HEAD_GAP = 0.15;
    /**
     * Ticks a display following a player glides to each new spot: as long as a client lerps a moving player's body
     * (3 steps), so a follower moved every tick keeps pace with the body instead of trailing or jittering.
     */
    private static final int FOLLOW_LERP = 3;
    private static final long EAT_FX_PERIOD = 6;

    // active: the trunk
    private static final int SEGMENTS = 5;
    private static final float SEGMENT_LENGTH = 0.36f;
    private static final float SEGMENT_WIDTH = 0.2f;
    private static final float DROOP = 9f;
    private static final long RETRACT_TICKS = 4;

    // ultimate: the spew
    private static final int WAVE_TICKS = 7;
    private static final int SHARDS = 4;

    /** Each curse type's last decision (eaten or let through) and until when it holds. */
    private final Map<PotionEffectType, Decision> decisions = new HashMap<>();
    /** Enemies in an unbreakable nightmare (no beneficial effects), until when. */
    private final Map<UUID, Long> locked = new HashMap<>();
    private final List<BlockDisplay> bubbles = new ArrayList<>();
    /** How big the bubbles orbit: swollen while the ultimate winds up. */
    private float bubbleScale = BUBBLE_SIZE;
    private final List<Display> live = new ArrayList<>();
    private int nightmares;
    private long lastEatFx = Long.MIN_VALUE / 2;
    private @Nullable Listener hooks;

    private record Decision(boolean eaten, long until) {
    }

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onGrant() {
        Listener listener = new Hooks();
        Bukkit.getPluginManager().registerEvents(listener, ctx().plugin());
        hooks = listener;
    }

    @Override
    public void onRevoke() {
        Listener listener = hooks;
        hooks = null;
        if (listener != null) {
            HandlerList.unregisterAll(listener);
        }
        decisions.clear();
        locked.clear();
        bubbles.clear();
        live.clear();
        nightmares = 0;
    }

    // ================================================================== passive: eating nightmares

    /** Curses on the baku (some are eaten) and good effects on enemies in an unbreakable nightmare (refused). */
    private final class Hooks implements Listener {

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onEffect(EntityPotionEffectEvent event) {
            EntityPotionEffectEvent.Action action = event.getAction();
            PotionEffect effect = event.getNewEffect();
            if ((action != EntityPotionEffectEvent.Action.ADDED && action != EntityPotionEffectEvent.Action.CHANGED)
                    || effect == null) {
                return;
            }
            PotionEffectType.Category category = effect.getType().getEffectCategory();
            if (event.getEntity().equals(player())) {
                if (category == PotionEffectType.Category.HARMFUL && eats(effect)) {
                    event.setCancelled(true);
                }
                return;
            }
            Long until = locked.get(event.getEntity().getUniqueId());
            if (until != null && until > ctx().currentTick() && category == PotionEffectType.Category.BENEFICIAL) {
                event.setCancelled(true);
                Location head = head(event.getEntity());
                Vfx.cloud(ctx(), head, Particle.SQUID_INK, 3, 0.15, 0.01, null, false);
                Fx.sound(head, Sound.ENTITY_PHANTOM_AMBIENT, 0.4f, 1.6f + Fx.jitter(0.1f));
            }
        }
    }

    /**
     * Whether the baku eats {@code curse}: rolled once per curse type, and a re-application of the same type soon after
     * follows that roll (abilities re-apply their effects every few ticks: one roll per curse, not per packet).
     */
    private boolean eats(PotionEffect curse) {
        long now = ctx().currentTick();
        PotionEffectType type = curse.getType();
        Decision decision = decisions.get(type);
        if (decision != null && decision.until() > now) {
            return decision.eaten();
        }
        double chance = Math.clamp(params().num("eat_chance"), 0, 100) / 100.0;
        boolean eaten = chance > 0 && ThreadLocalRandom.current().nextDouble() < chance;
        int duration = curse.getDuration() < 0 ? (int) MAX_MEMORY : curse.getDuration();
        decisions.put(type, new Decision(eaten, now + Math.clamp(duration, MIN_MEMORY, MAX_MEMORY)));
        if (eaten) {
            gainNightmare(type);
        }
        return eaten;
    }

    /** One more black bubble over the head (up to the cap), with a satisfied gulp. */
    private void gainNightmare(@Nullable PotionEffectType from) {
        Player owner = player();
        int max = Math.max(0, params().integer("max_nightmares"));
        boolean grew = nightmares < max;
        if (grew) {
            nightmares++;
            syncBubbles(owner);
        }
        long now = ctx().currentTick();
        if (now - lastEatFx < EAT_FX_PERIOD) {
            return;
        }
        lastEatFx = now;
        Location mouth = mouth(owner);
        Vfx.cloud(ctx(), mouth, Particle.SQUID_INK, 4, 0.12, 0.01, null, false);
        Vfx.cloud(ctx(), mouth, Particle.DUST, 6, 0.2, 0, LILAC_DUST, false);
        Vfx.dot(head(owner).add(0, BUBBLE_ABOVE, 0), Particle.WAX_OFF, null, false);
        Fx.sound(mouth, Sound.ENTITY_GENERIC_EAT, 0.9f, 0.8f + Fx.jitter(0.1f));
        Fx.sound(mouth, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, 0.6f + 0.15f * nightmares);
        String what = from == null ? "악몽" : effectName(from);
        owner.sendActionBar(Text.mm("<" + LILAC + "><b>냠</b></" + LILAC + "> <white>" + what + "</white><gray>을(를) "
                + "먹었습니다 · 악몽 " + nightmares + "/" + max + (grew ? "" : " (가득)") + "</gray>"));
    }

    /**
     * As many black bubbles as nightmares, orbiting over the head (hidden from the baku's own eyes). They follow the
     * head every tick, gliding as long as a client lerps a moving player ({@link #FOLLOW_LERP}), and orbit through
     * their transformation, so they keep pace with a running baku instead of trailing and jittering behind it.
     */
    private void syncBubbles(Player owner) {
        bubbles.removeIf(bubble -> !bubble.isValid());
        while (bubbles.size() > nightmares) {
            BlockDisplay bubble = bubbles.removeLast();
            Vfx.cloud(ctx(), head(owner).add(0, BUBBLE_ABOVE, 0), Particle.SQUID_INK, 3, 0.1, 0.01, null, false);
            bubble.remove();
        }
        while (bubbles.size() < nightmares && room(1)) {
            BlockDisplay bubble = keep(Displays.block(ctx(), owner, bubbleCenter(owner), BUBBLE.createBlockData(),
                    bubblePose(0.02f, 0, 0, 0), 0));
            bubble.setTeleportDuration(FOLLOW_LERP);
            owner.hideEntity(ctx().plugin(), bubble);
            Displays.animateNextTick(ctx(), owner, bubble, bubblePose(bubbleScale, 0, 0, 0), 5);
            bubbles.add(bubble);
        }
    }

    /**
     * A bubble of {@code size}, turned {@code spin} degrees, at {@code angle} round the orbit around
     * {@link #bubbleCenter}, bobbing by {@code bob}.
     */
    private static Transformation bubblePose(float size, float spin, double angle, double bob) {
        return Displays.transform((float) (Math.cos(angle) * BUBBLE_ORBIT), (float) bob,
                (float) (Math.sin(angle) * BUBBLE_ORBIT), size, size, size, spin, 35, 45);
    }

    /** The middle of the bubbles' orbit, over the head. */
    private static Location bubbleCenter(Player owner) {
        return head(owner).add(0, BUBBLE_ABOVE, 0);
    }

    @Override
    public void onTick(long tick) {
        Player owner = player();
        if (!bubbles.isEmpty()) {
            orbitBubbles(owner, tick);
        }
        if (tick % 100 == 0) {
            long now = ctx().currentTick();
            decisions.values().removeIf(decision -> decision.until() <= now);
            locked.values().removeIf(until -> until <= now);
        }
    }

    private void orbitBubbles(Player owner, long tick) {
        Location center = bubbleCenter(owner);
        boolean orbit = tick % BUBBLE_PERIOD == 0;
        int count = bubbles.size();
        for (int i = 0; i < count; i++) {
            BlockDisplay bubble = bubbles.get(i);
            if (!bubble.isValid()) {
                continue;
            }
            Displays.moveTo(bubble, center);
            if (orbit) {
                double angle = tick * 0.08 + i * Math.PI * 2 / count;
                double bob = 0.06 * Math.sin(tick * 0.15 + i);
                Displays.animate(bubble, bubblePose(bubbleScale, (tick * 6f) % 360f, angle, bob),
                        (int) BUBBLE_PERIOD);
            }
        }
        if (tick % 10 == 0 && !owner.isInvisible()) {
            Vfx.dot(center.clone().add(0, 0.05, 0), Particle.DUST, NIGHT_DUST, false);
        }
    }

    // ================================================================== active: sucking in dreams

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        Location eye = owner.getEyeLocation();
        Vector look = eye.getDirection();
        if (!(look.lengthSquared() > 1.0E-8)) {
            look = Motion.horizontalFacing(eye);
        }
        new Suck(owner, look.normalize(), owner.isSneaking()).start();
        return ActivationResult.success();
    }

    /** The trunk reaches out, the dreams swirl into it, and at the end of the breath the cone is sucked dry. */
    private final class Suck implements LongPredicate {

        private final Player owner;
        private final Vector look;
        private final boolean gift;
        private final double range;
        private final double half;
        private final List<BlockDisplay> trunk = new ArrayList<>();
        private final long breath;

        Suck(Player owner, Vector look, boolean gift) {
            this.owner = owner;
            this.look = look;
            this.gift = gift;
            this.range = Math.max(0.5, params().num("suck_range"));
            this.half = Math.toRadians(Math.clamp(params().num("suck_angle"), 1, 360) / 2);
            this.breath = Math.max(1, params().ticks("suck_time"));
        }

        void start() {
            if (room(SEGMENTS)) {
                for (int i = 0; i < SEGMENTS; i++) {
                    Material material = i == SEGMENTS - 1 ? TRUNK_TIP : TRUNK;
                    BlockDisplay segment = keep(Displays.block(ctx(), owner, mouth(owner), material.createBlockData(),
                            segmentPose(i, 0.05f, 0), breath + RETRACT_TICKS + 8));
                    segment.setTeleportDuration(1);
                    trunk.add(segment);
                }
                for (int i = 0; i < trunk.size(); i++) {
                    Displays.animateNextTick(ctx(), owner, trunk.get(i), segmentPose(i, 1, 0), 3);
                }
            }
            Location mouth = mouth(owner);
            Fx.sound(mouth, Sound.ENTITY_SNIFFER_SNIFFING, 1.0f, 0.8f + Fx.jitter(0.05f));
            Fx.sound(mouth, Sound.ITEM_BOTTLE_FILL_DRAGONBREATH, 0.9f, 0.7f + Fx.jitter(0.05f));
            Fx.sound(mouth, Sound.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_INSIDE, 0.8f, 1.2f);
            Loop.repeat(ctx(), owner, 1, 1, breath, this, this::resolve);
        }

        /** Segment {@code i} of the trunk, curling down a little more with every segment, swaying by {@code sway}. */
        private Transformation segmentPose(int i, float grow, float sway) {
            float yaw = (float) Math.toDegrees(Math.atan2(-look.getX(), look.getZ())) + sway * (i + 1);
            float pitch = (float) -Math.toDegrees(Math.asin(Math.clamp(look.getY(), -1, 1)));
            Vector center = new Vector();
            for (int k = 0; k <= i; k++) {
                Vector along = along(yaw - sway * (i - k), pitch + DROOP * k);
                center.add(along.multiply(k == i ? SEGMENT_LENGTH * 0.5 * grow : SEGMENT_LENGTH * grow));
            }
            float width = SEGMENT_WIDTH * (1 - 0.12f * i);
            return Displays.transform((float) center.getX(), (float) center.getY(), (float) center.getZ(), width * grow,
                    width * grow, SEGMENT_LENGTH * grow, yaw, pitch + DROOP * i, 0);
        }

        private Vector tip() {
            Transformation last = segmentPose(SEGMENTS - 1, 1, 0);
            Location mouth = mouth(owner);
            return mouth.toVector().add(new Vector(last.getTranslation().x(), last.getTranslation().y(),
                    last.getTranslation().z())).add(look.clone().multiply(SEGMENT_LENGTH * 0.6));
        }

        @Override
        public boolean test(long run) {
            Location mouth = mouth(owner);
            float sway = (float) (6 * Math.sin(run * 1.3));
            for (int i = 0; i < trunk.size(); i++) {
                BlockDisplay segment = trunk.get(i);
                if (segment.isValid()) {
                    Displays.moveTo(segment, mouth);
                    if (run >= 2) {
                        Displays.animate(segment, segmentPose(i, 1, sway), 1);
                    }
                }
            }
            // Dreams and stars swirl in from the cone into the trunk.
            Location tip = tip().toLocation(owner.getWorld());
            Vector side = look.getCrossProduct(new Vector(0, 1, 0));
            if (side.lengthSquared() < 1.0E-6) {
                side = new Vector(1, 0, 0);
            }
            side.normalize();
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int motes = Vfx.n(ctx(), 6);
            for (int i = 0; i < motes; i++) {
                double distance = 1.5 + random.nextDouble() * (range - 1.5);
                double spread = Math.tan(Math.min(half, Math.toRadians(70))) * distance;
                Location from = owner.getEyeLocation().add(look.clone().multiply(distance))
                        .add(side.clone().multiply((random.nextDouble() * 2 - 1) * spread))
                        .add(0, (random.nextDouble() * 2 - 1) * spread * 0.4, 0);
                Vfx.trail(from, tip, i % 3 == 0 ? STAR_COLOR : LILAC_COLOR, 6 + random.nextInt(4), false);
            }
            if (run % 2 == 0) {
                Vfx.dot(tip, Particle.REVERSE_PORTAL, null, false);
            }
            return true;
        }

        /** The end of the breath: the cone is sucked dry. */
        private void resolve() {
            Location eye = owner.getEyeLocation();
            List<LivingEntity> victims = new ArrayList<>();
            for (LivingEntity enemy : ctx().enemiesNear(owner, eye, range)) {
                if (!isHidden(owner, enemy) && inCone(eye, look, half, enemy)) {
                    victims.add(enemy);
                }
            }
            retract();
            Fx.sound(eye, Sound.ENTITY_GENERIC_DRINK, 1.0f, 0.6f + Fx.jitter(0.05f));
            Fx.sound(eye, Sound.ENTITY_SNIFFER_EAT, 0.8f, 1.0f);
            if (victims.isEmpty()) {
                double left = ctx().cooldownRemaining(owner);
                double refund = Math.clamp(params().num("whiff_refund"), 0, 100) / 100.0;
                if (left > 0 && refund > 0) {
                    ctx().reduceCooldown(owner, left * refund);
                }
                owner.sendActionBar(Text.mm("<gray>빨아들일 꿈이 없었습니다</gray> <dark_gray>(쿨타임 "
                        + AbilityInfo.formatNumber(refund * 100) + "% 회복)</dark_gray>"));
                return;
            }
            Player receiver = gift ? nearestAlly(owner) : null;
            Player to = receiver != null ? receiver : owner;
            double damage = Math.max(0, params().num("suck_damage"));
            long maxTicks = Math.max(1, params().ticks("steal_duration"));
            double absorptionLeft = Math.max(0, params().num("steal_absorption"));
            int stolenEffects = 0;
            double stolenAbsorption = 0;
            for (LivingEntity victim : victims) {
                Location body = Fx.body(victim);
                Vfx.cloud(ctx(), body, Particle.DUST_COLOR_TRANSITION, 8, 0.3, 0, GLOW, false);
                // The dreams go first (absorption hearts too), then the bite of the breath.
                for (PotionEffect effect : new ArrayList<>(victim.getActivePotionEffects())) {
                    if (!isStealable(effect)) {
                        continue;
                    }
                    victim.removePotionEffect(effect.getType());
                    int duration = effect.isInfinite() || effect.getDuration() < 0 ? (int) maxTicks
                            : (int) Math.min(maxTicks, effect.getDuration());
                    if (duration > 0) {
                        to.addPotionEffect(new PotionEffect(effect.getType(), duration, effect.getAmplifier(), false,
                                true, true));
                        stolenEffects++;
                        Color color = effect.getType().getColor();
                        Vfx.trail(body, Fx.body(to), color, 10, false);
                    }
                }
                double absorption = victim.getAbsorptionAmount();
                if (absorption > 0 && absorptionLeft > 0) {
                    double take = Math.min(absorption, absorptionLeft);
                    absorptionLeft -= take;
                    victim.setAbsorptionAmount(absorption - take);
                    stolenAbsorption += take;
                    Vfx.trail(body, Fx.body(to), Color.fromRGB(0xFFD34D), 10, false);
                }
                Combat.hit(ctx(), owner, victim, damage);
            }
            if (stolenAbsorption > 0) {
                giveAbsorption(owner, to, stolenAbsorption, maxTicks);
            }
            if (stolenEffects == 0 && stolenAbsorption <= 0) {
                gainNightmare(null);
                owner.sendActionBar(Text.mm("<" + LILAC + "><b>꿈 빨아들이기</b></" + LILAC + "> <gray>빼앗을 꿈이 없어 "
                        + "악몽을 삼켰습니다 · " + nightmares + "/" + Math.max(0, params().integer("max_nightmares"))
                        + "</gray>"));
                return;
            }
            Location at = Fx.body(to);
            Vfx.helix(ctx(), to.getLocation(), 0.5, to.getHeight(), 1.5, 2, 10, 0, Particle.DUST, STAR_DUST, false);
            Vfx.cloud(ctx(), at, Particle.WAX_OFF, 6, 0.3, 0.05, null, false);
            Fx.sound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9f, 1.6f + Fx.jitter(0.05f));
            String whom = receiver != null ? "아군에게 넘겼습니다" : "내 것이 되었습니다";
            owner.sendActionBar(Text.mm("<" + LILAC + "><b>꿈 빨아들이기</b></" + LILAC + "> <white>" + stolenEffects
                    + "</white><gray>개의 효과" + (stolenAbsorption > 0 ? "와 흡수 체력 " + AbilityInfo.formatNumber(
                    stolenAbsorption) : "") + " · " + whom + "</gray>"));
            if (receiver != null) {
                receiver.sendActionBar(Text.mm("<" + LILAC + ">바쿠의 꿈 선물</" + LILAC + "> <gray>빼앗은 효과를 "
                        + "받았습니다</gray>"));
            }
        }

        private void retract() {
            for (int i = 0; i < trunk.size(); i++) {
                BlockDisplay segment = trunk.get(i);
                if (segment.isValid()) {
                    Displays.animate(segment, segmentPose(i, 0.05f, 0), (int) RETRACT_TICKS);
                    ctx().runLater(owner, RETRACT_TICKS, () -> {
                        if (segment.isValid()) {
                            segment.remove();
                        }
                    });
                }
            }
            trunk.clear();
        }
    }

    /** Beneficial effects that can move: not the bookkeeping kind (ambient and invisible) and never absorption. */
    private static boolean isStealable(PotionEffect effect) {
        PotionEffectType type = effect.getType();
        return type.getEffectCategory() == PotionEffectType.Category.BENEFICIAL && !PotionEffectType.ABSORPTION.equals(type)
                && !(effect.isAmbient() && !effect.hasParticles());
    }

    /** Stolen absorption hearts: room for them (max absorption) for as long as they may last. */
    private void giveAbsorption(Player owner, Player to, double amount, long ticks) {
        ctx().addModifier(owner, to, Attribute.MAX_ABSORPTION, amount, Operation.ADD_NUMBER, ticks);
        to.setAbsorptionAmount(to.getAbsorptionAmount() + amount);
        ctx().runLater(owner, ticks + 1, () -> {
            AttributeInstance max = to.getAttribute(Attribute.MAX_ABSORPTION);
            if (max != null && to.isValid() && to.getAbsorptionAmount() > max.getValue()) {
                to.setAbsorptionAmount(Math.max(0, max.getValue()));
            }
        });
    }

    private @Nullable Player nearestAlly(Player owner) {
        List<Player> allies = ctx().alliesNear(owner, owner.getLocation(), Math.max(0, params().num("gift_range")));
        return allies.isEmpty() ? null : allies.getFirst();
    }

    // ================================================================== ultimate: spewing the nightmares

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        Location eye = owner.getEyeLocation();
        Vector look = eye.getDirection();
        if (!(look.lengthSquared() > 1.0E-8)) {
            look = Motion.horizontalFacing(eye);
        }
        look.normalize();
        double range = Math.max(0.5, params().num("spew_range"));
        double half = Math.toRadians(Math.clamp(params().num("spew_angle"), 1, 360) / 2);
        boolean anyone = false;
        for (LivingEntity enemy : ctx().enemiesNear(owner, eye, range)) {
            if (!isHidden(owner, enemy) && inCone(eye, look, half, enemy)) {
                anyone = true;
                break;
            }
        }
        if (!anyone) {
            return ActivationResult.fail("<gray>앞쪽 " + AbilityInfo.formatNumber(range) + "블록 안에 토해 낼 적이 "
                    + "없습니다</gray>");
        }
        new Spew(owner, look, range, half).start();
        return ActivationResult.success();
    }

    /** The bubbles swell during the wind-up while the cone shows on the ground, then the torrent of nightmares. */
    private final class Spew implements LongPredicate {

        private final Player owner;
        private final Vector look;
        private final double range;
        private final double half;
        private final long windup;

        Spew(Player owner, Vector look, double range, double half) {
            this.owner = owner;
            this.look = look;
            this.range = range;
            this.half = half;
            this.windup = Math.max(1, params().ticks("spew_windup"));
        }

        void start() {
            Location mouth = mouth(owner);
            Fx.sound(mouth, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.0f, 0.6f);
            Fx.sound(mouth, Sound.ENTITY_PHANTOM_AMBIENT, 1.0f, 0.5f);
            Fx.sound(mouth, Sound.AMBIENT_CAVE, 0.6f, 1.4f);
            // The nightmares swell over the head while it winds up (the orbit grows them).
            bubbleScale = BUBBLE_SIZE * 1.8f;
            Loop.repeat(ctx(), owner, 0, 1, windup, this, this::spew);
        }

        @Override
        public boolean test(long run) {
            // The cone on the ground: its two edges and the far arc, so everyone can step out of it.
            if (run % 3 == 0) {
                Location feet = owner.getLocation().add(0, 0.15, 0);
                Vector flat = Motion.horizontal(look);
                if (flat.lengthSquared() < 1.0E-6) {
                    flat = Motion.horizontalFacing(owner.getLocation());
                }
                double yaw = Math.toDegrees(Math.atan2(-flat.getX(), flat.getZ()));
                Vfx.arc(ctx(), feet, range, yaw, Math.toDegrees(half * 2), (int) Math.max(14, range * 3),
                        Particle.DUST_COLOR_TRANSITION, NIGHTMARE, true);
                for (int side = -1; side <= 1; side += 2) {
                    Vector edge = flat.clone().rotateAroundY(-side * half);
                    Vfx.line(ctx(), feet, feet.clone().add(edge.multiply(range)), 1.5, Particle.DUST, NIGHT_DUST,
                            true);
                }
            }
            Location mouth = mouth(owner);
            Vfx.cloud(ctx(), mouth, Particle.SQUID_INK, 2, 0.1, 0.01, null, false);
            return true;
        }

        private void spew() {
            bubbleScale = BUBBLE_SIZE;
            Location eye = owner.getEyeLocation();
            int count = nightmares;
            List<LivingEntity> victims = new ArrayList<>();
            for (LivingEntity enemy : ctx().enemiesNear(owner, eye, range)) {
                if (!isHidden(owner, enemy) && inCone(eye, look, half, enemy)) {
                    victims.add(enemy);
                }
            }
            torrent(eye);
            // The baku's own curses go out with the nightmares: it is clean afterwards.
            long curseTicks = Math.max(1, params().ticks("curse_duration"));
            List<PotionEffect> passed = new ArrayList<>();
            for (PotionEffect effect : new ArrayList<>(owner.getActivePotionEffects())) {
                if (effect.getType().getEffectCategory() == PotionEffectType.Category.HARMFUL
                        && !(effect.isAmbient() && !effect.hasParticles())) {
                    owner.removePotionEffect(effect.getType());
                    int duration = effect.isInfinite() || effect.getDuration() < 0 ? (int) curseTicks
                            : (int) Math.min(curseTicks, effect.getDuration());
                    passed.add(new PotionEffect(effect.getType(), duration, effect.getAmplifier(), false, true, true));
                }
            }
            if (!passed.isEmpty()) {
                Vfx.helix(ctx(), owner.getLocation(), 0.5, owner.getHeight(), 1.5, 2, 10, 0, Particle.DUST, STAR_DUST,
                        false);
                Fx.sound(eye, Sound.BLOCK_BEACON_DEACTIVATE, 0.6f, 1.6f);
            }
            if (victims.isEmpty()) {
                owner.sendActionBar(Text.mm("<gray>악몽이 아무에게도 닿지 않았습니다</gray>"));
                return;
            }
            long now = ctx().currentTick();
            List<PotionEffectType> pool = new ArrayList<>(List.of(PotionEffectType.SLOWNESS, PotionEffectType.WEAKNESS,
                    PotionEffectType.NAUSEA, PotionEffectType.POISON, PotionEffectType.BLINDNESS));
            for (LivingEntity victim : victims) {
                Location head = head(victim);
                Vfx.trail(eye, head, NIGHT_COLOR, 6, false);
                Vfx.cloud(ctx(), head, Particle.DUST_COLOR_TRANSITION, 10, 0.3, 0, NIGHTMARE, false);
                Vfx.ring(ctx(), head, 0.4, 10, 0, Particle.DUST, NIGHT_DUST, false);
                if (count > 0) {
                    Combat.hit(ctx(), owner, victim, Math.max(0, params().num("nightmare_damage")) * count);
                    if (!Combat.isTargetable(ctx(), owner, victim)) {
                        continue;
                    }
                    Collections.shuffle(pool);
                    for (int i = 0; i < Math.min(count, pool.size()); i++) {
                        curse(victim, pool.get(i));
                    }
                    long lock = params().ticks("nightmare_lock");
                    if (lock > 0) {
                        locked.put(victim.getUniqueId(), now + lock);
                        nightmareMark(victim, lock);
                    }
                } else {
                    Combat.hit(ctx(), owner, victim, Math.max(0, params().num("empty_damage")));
                    if (!Combat.isTargetable(ctx(), owner, victim)) {
                        continue;
                    }
                    long slow = params().ticks("empty_slow");
                    if (slow > 0) {
                        victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, (int) slow, 1, false, true,
                                true));
                    }
                }
                for (PotionEffect effect : passed) {
                    victim.addPotionEffect(effect);
                }
            }
            if (count > 0) {
                nightmares = 0;
                syncBubbles(owner);
            }
            owner.sendActionBar(Text.mm("<" + INK + "><b>악몽 토해내기</b></" + INK + "> <white>" + victims.size()
                    + "</white><gray>명 · 악몽 " + count + "개" + (passed.isEmpty() ? "" : " · 내 저주 " + passed.size()
                    + "개도 함께") + "</gray>"));
        }

        /** One curse of the spewed nightmares, for its own time. */
        private void curse(LivingEntity victim, PotionEffectType type) {
            long ticks = Math.max(1, params().ticks("curse_duration"));
            if (PotionEffectType.NAUSEA.equals(type)) {
                Fx.nausea(victim, ticks);
            } else if (PotionEffectType.BLINDNESS.equals(type)) {
                long blind = Math.max(1, params().ticks("curse_blind"));
                victim.addPotionEffect(new PotionEffect(type, (int) Math.min(Integer.MAX_VALUE, blind), 0, false, true,
                        true));
            } else {
                int amplifier = PotionEffectType.SLOWNESS.equals(type) ? 1 : 0;
                victim.addPotionEffect(new PotionEffect(type, (int) ticks, amplifier, false, true, true));
            }
        }

        /** The torrent: dark rings rolling out across the cone, ink and shards and membranes tumbling along. */
        private void torrent(Location eye) {
            Vfx.dot(mouth(owner), Particle.FLASH, NIGHT_COLOR, true);
            Fx.sound(eye, Sound.ENTITY_PHANTOM_SWOOP, 1.2f, 0.6f);
            Fx.sound(eye, Sound.ENTITY_PHANTOM_BITE, 1.0f, 0.5f);
            Fx.sound(eye, Sound.ENTITY_RAVAGER_ROAR, 0.6f, 1.6f);
            Fx.sound(eye, Sound.ENTITY_WITHER_SHOOT, 0.5f, 0.6f);
            double tan = Math.tan(Math.min(half, Math.toRadians(75)));
            Location start = eye.clone();
            Loop.repeat(ctx(), owner, 0, 1, WAVE_TICKS, run -> {
                double t = (run + 1.0) / WAVE_TICKS;
                double distance = 0.8 + (range - 0.8) * t;
                Location center = start.clone().add(look.clone().multiply(distance));
                double radius = Math.max(0.3, Math.min(4, distance * tan));
                Vfx.ring(ctx(), center, radius, (int) Math.max(10, radius * 6), run * 0.5,
                        Particle.DUST_COLOR_TRANSITION, NIGHTMARE, true);
                center.getWorld().spawnParticle(Particle.SQUID_INK, center, Vfx.n(ctx(), 6), radius * 0.4,
                        radius * 0.3, radius * 0.4, 0.02, null, true);
                return true;
            });
            for (int i = 0; i < SHARDS; i++) {
                if (!room(1)) {
                    break;
                }
                double spread = (i - (SHARDS - 1) / 2.0) * 0.18;
                Vector direction = look.clone().rotateAroundY(spread).normalize();
                Location from = mouth(owner);
                boolean membrane = i % 2 == 1;
                Display flying = membrane
                        ? keep(Displays.item(ctx(), owner, from, new ItemStack(MEMBRANE),
                        Displays.centered(0, 0, 0, 0.5f, 0.5f, 0.5f, 0, 0, 0), WAVE_TICKS + 4))
                        : keep(Displays.block(ctx(), owner, from, SHARD.createBlockData(),
                        Displays.transform(0, 0, 0, 0.25f, 0.25f, 0.25f, 0, 30, 45), WAVE_TICKS + 4));
                Vector travel = direction.multiply(range * 0.9);
                Transformation end = membrane
                        ? Displays.centered((float) travel.getX(), (float) travel.getY(), (float) travel.getZ(), 0.8f,
                        0.8f, 0.8f, i * 90f, 0, 180)
                        : Displays.transform((float) travel.getX(), (float) travel.getY(), (float) travel.getZ(), 0.1f,
                        0.1f, 0.1f, i * 90f, 200, 270);
                Displays.animateNextTick(ctx(), owner, flying, end, WAVE_TICKS);
            }
        }
    }

    /** A dark halo over an enemy in the unbreakable nightmare, for its time. */
    private void nightmareMark(LivingEntity victim, long ticks) {
        Player owner = player();
        Loop.repeat(ctx(), owner, 2, 3, Math.max(1, ticks / 3), run -> {
            if (!Combat.isTargetable(ctx(), owner, victim) || isHidden(owner, victim)) {
                return victim.isValid();
            }
            Location head = head(victim).add(0, 0.15, 0);
            Vfx.ring(ctx(), head, 0.36, 8, run * 0.6, Particle.DUST, NIGHT_DUST, false);
            if (run % 2 == 0) {
                Vfx.dot(head.clone().add(0, 0.2, 0), Particle.SQUID_INK, null, false);
            }
            return true;
        });
    }

    // ================================================================== helpers

    /** True if {@code enemy} is within {@code half} (radians) of {@code look} from {@code eye}, in plain sight. */
    private static boolean inCone(Location eye, Vector look, double half, LivingEntity enemy) {
        Location body = Fx.body(enemy);
        Vector to = body.toVector().subtract(eye.toVector());
        double distance = to.length();
        boolean inside = distance < 0.8 || to.multiply(1 / distance).angle(look) <= half;
        return inside && (Spots.clearPath(eye, enemy.getEyeLocation()) || Spots.clearPath(eye, body));
    }

    /** Korean names of the common curses, for the action bar. */
    private static String effectName(PotionEffectType type) {
        Map<PotionEffectType, String> names = Map.of(PotionEffectType.SLOWNESS, "구속",
                PotionEffectType.WEAKNESS, "나약함", PotionEffectType.BLINDNESS, "실명", PotionEffectType.NAUSEA, "멀미",
                PotionEffectType.POISON, "독", PotionEffectType.WITHER, "위더", PotionEffectType.DARKNESS, "어둠",
                PotionEffectType.LEVITATION, "공중 부양", PotionEffectType.MINING_FATIGUE, "채굴 피로",
                PotionEffectType.HUNGER, "허기");
        return names.getOrDefault(type, "악몽");
    }

    /** The mouth, just below the eyes, where the trunk grows from. */
    private static Location mouth(Player owner) {
        Location eye = owner.getEyeLocation();
        Vector facing = Motion.horizontalFacing(eye);
        return Displays.unrotated(eye.add(facing.multiply(0.3)).subtract(0, 0.25, 0));
    }

    private static Location head(Entity entity) {
        return entity.getLocation().add(0, entity.getHeight() + HEAD_GAP, 0);
    }

    /** The direction +Z points at after a yaw and pitch (yaw 0 is south, pitch positive is down). */
    private static Vector along(float yaw, float pitch) {
        double y = Math.toRadians(yaw);
        double p = Math.toRadians(pitch);
        return new Vector(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
    }

    private static boolean isHidden(Player owner, LivingEntity entity) {
        return entity instanceof Player hidden && !owner.canSee(hidden);
    }

    private boolean room(int count) {
        live.removeIf(display -> !display.isValid());
        return live.size() + count <= MAX_DISPLAYS;
    }

    private <T extends Display> T keep(T display) {
        live.add(display);
        return display;
    }
}
