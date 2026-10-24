package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
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
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 중2병 — the "eighth grader" with a black-flame dragon sealed in a bandaged right arm and an evil eye. Staring an enemy
 * down long enough "sees through" it (its next hit lands harder), the unsealing stance swallows half the blows it takes
 * into the arm and lets them out as black flame where it chooses, and the ultimate is a long chant that draws the path
 * of a black-flame dragon on the ground — unless someone looks it in the face, which is just too embarrassing.
 *
 * <p>Look (jet black, black-flame purple, bandage white, evil-eye red): a thin red line of sight runs from its eye to the
 * enemy it stares at, and "◉ 간파" floats over the enemy it has seen through. The stance holds a bandaged arm (bone
 * strips) across the face that swells and cracks open into crying obsidian while black flame wisps off it; the release
 * thrusts it forward in a cone of black and purple flame. The chant hangs its lines over its head over a turning magic
 * circle, a red trail is drawn on the ground where it looks, and the dragon — a black head with red eyes and purple
 * horns, a long body of black stone and crying obsidian — skims along the trail and dives into its end.
 */
public final class ChuuniAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("chuuni", "중2병", Material.BLACK_CANDLE)
            .color(TextColor.color(0x6B2FD6))
            .difficulty(2)
            .passive("사안(邪眼)",
                    "{eye_range}블록 안의 적 하나를 {eye_time}초 넘게 노려보면(둘 사이에 붉은 시선이 모두에게 보입니다) "
                            + "\"…간파했다\" — {eye_window}초 안에 그 적에게 주는 다음 피해가 {eye_bonus}% 늘어납니다(최대 "
                            + "+{eye_bonus_max}). 한 번에 한 명만 간파하며, 시선을 돌리면 쌓이던 시간이 사라집니다.")
            .active("봉인 해제 자세",
                    "붕대 감긴 오른팔을 얼굴 앞에 대고 최대 {stance_time}초 버팁니다(이동 속도 -{stance_slow}%). 그동안 "
                            + "받는 피해의 {swallow_share}%를 오른팔이 삼키고, 삼킨 양의 {flame_ratio}배가 흑염으로 쌓입니다"
                            + "(최대 {flame_max}). 웅크리거나 시간이 다 되면 오른팔을 내질러 앞 {strike_range}블록·"
                            + "{strike_angle}° 부채꼴의 적에게 {strike_base} + 쌓인 흑염만큼 피해를 주고 약 {strike_push}블록 "
                            + "밀쳐냅니다. 아무것도 삼키지 못했다면 \"…오늘은 봉인이 단단하군\".",
                    10)
            .ultimate("흑염룡 영창",
                    "{chant_time}초간 세 줄의 영창을 읊습니다(주변에는 머리 위 글자와 액션바로 보입니다). 그동안 내가 "
                            + "바라보는 땅에 붉은 궤적이 그려집니다(최대 {trail_length}블록). 영창 중 침묵에 걸리거나, "
                            + "{shy_range}블록 안의 적들이 벽 너머가 아닌 곳에서 나를 정면으로 합쳐 {shy_time}초 넘게 쳐다보면 "
                            + "\"보, 보지 마…!\" 부끄러워 실패하고 궁극기 게이지 {fail_refund}%를 돌려받습니다. 성공하면 검은 "
                            + "불꽃의 용이 {dragon_time}초에 걸쳐 궤적을 따라 낮게 날아가 궤적 {dragon_width}블록 안의 적에게 "
                            + "{dragon_damage} 피해와 {dragon_darkness}초 어둠을 주고(적마다 한 번), 끝에서 내리꽂혀 반경 "
                            + "{plunge_radius}블록의 적에게 {plunge_damage} 피해를 줍니다. 흑염은 불이 아니라 불타지 않습니다.")
            .param("eye_range", 16)
            .param("eye_time", 1.5)
            .param("eye_window", 6)
            .param("eye_bonus", 40)
            .param("eye_bonus_max", 3)
            .param("stance_time", 2)
            .param("stance_slow", 50)
            .param("swallow_share", 50)
            .param("flame_ratio", 2)
            .param("flame_max", 6)
            .param("strike_base", 2)
            .param("strike_range", 6)
            .param("strike_angle", 60)
            .param("strike_push", 3)
            .param("chant_time", 1.8)
            .param("trail_length", 20)
            .param("shy_range", 10)
            .param("shy_time", 0.8)
            .param("fail_refund", 50)
            .param("dragon_time", 1)
            .param("dragon_width", 2)
            .param("dragon_damage", 5)
            .param("dragon_darkness", 2)
            .param("plunge_radius", 3)
            .param("plunge_damage", 3)
            .build();

    private static final String PURPLE_TAG = "#6B2FD6";
    private static final String RED_TAG = "#E0182D";
    private static final String BANDAGE_TAG = "#ECE6DA";
    private static final String BLUSH_TAG = "#FF7AA8";

    // ------------------------------------------------------------------ look
    /** Black flame: lilac core, purple body, jet black edge. */
    private static final Vfx.Palette FLAME = Vfx.Palette.of(0xC9A6FF, 0x6B2FD6, 0x14121A);
    private static final Color RED = Color.fromRGB(0xE0182D);
    private static final Color PURPLE = Color.fromRGB(0x6B2FD6);
    private static final Color BLACK = Color.fromRGB(0x14121A);
    private static final Particle.DustOptions RED_DUST = new Particle.DustOptions(RED, 0.6f);
    private static final Particle.DustOptions TRAIL_DUST = new Particle.DustOptions(RED, 1.1f);
    private static final Particle.DustOptions BLACK_DUST = new Particle.DustOptions(BLACK, 1.5f);
    private static final Particle.DustOptions BANDAGE_DUST = new Particle.DustOptions(Color.fromRGB(0xECE6DA), 0.8f);
    private static final Particle.DustTransition FLAME_GLOW = FLAME.glow(1.2f);
    private static final Particle.DustTransition FLAME_FADE = FLAME.fade(1.5f);

    /** Block types (block data is created on use: it needs a running server). */
    private static final Material BANDAGE = Material.BONE_BLOCK;
    private static final Material SEAL_CRACK = Material.CRYING_OBSIDIAN;
    private static final Material DRAGON_SKULL = Material.BLACK_CONCRETE;
    private static final Material DRAGON_SNOUT = Material.BLACKSTONE;
    private static final Material DRAGON_EYE = Material.RED_CONCRETE;
    private static final Material DRAGON_HORN = Material.PURPLE_CONCRETE;
    private static final Material DRAGON_SCALE = Material.CRYING_OBSIDIAN;

    private static final int MAX_DISPLAYS = 24;
    private static final double TAU = Math.PI * 2;
    private static final double MAX_SPEED = 2.0;

    // passive: evil eye
    /** The red line of sight is only drawn once a stare has lasted this long (not for every glance). */
    private static final int LINE_AFTER = 6;
    private static final double LABEL_ABOVE = 0.6;

    // active: unsealing stance
    private static final int CRACKS = 3;
    private static final double PUSH_LIFT = 0.3;
    private static final double AIR_DRAG = 0.91;
    private static final int PUSH_AIR_TICKS = 9;

    // ultimate: chant
    private static final String[] LINES = {"봉인이여, 풀려라…", "칠흑의 맹약에 따라…", "깨어나라, 흑염룡!"};
    /** Someone whose view is within this angle of the chanter's face is looking it in the face. */
    private static final double FACE_COS = Math.cos(Math.toRadians(25));
    private static final double MIN_STEP = 0.8;
    private static final double LINE_HEARING = 24;
    private static final double FALLBACK_PATH = 10;
    private static final double DRAGON_ABOVE = 1.1;
    private static final int BODY_SEGMENTS = 7;
    private static final double SEGMENT_GAP = 0.75;

    // ------------------------------------------------------------------ state

    private final List<Display> live = new ArrayList<>();
    private @Nullable LivingEntity staring;
    private int stareTicks;
    private @Nullable LivingEntity seenThrough;
    private long seenUntil = Long.MIN_VALUE / 2;
    private @Nullable TextDisplay seenLabel;
    private @Nullable Stance stance;
    private @Nullable Chant chant;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        staring = null;
        seenThrough = null;
        seenLabel = null;
        stance = null;
        chant = null;
        live.clear();
    }

    // ------------------------------------------------------------------ passive: evil eye

    @Override
    public void onTick(long tick) {
        Player owner = player();
        long now = ctx().currentTick();
        LivingEntity aimed = ctx().findTarget(owner, Math.max(1, params().num("eye_range")));
        if (aimed == null || !seen(owner, aimed)) {
            staring = null;
            stareTicks = 0;
        } else if (aimed.equals(staring)) {
            stareTicks++;
        } else {
            staring = aimed;
            stareTicks = 1;
        }
        LivingEntity target = staring;
        long needed = Math.max(1, params().ticks("eye_time"));
        if (target != null && !target.equals(seenThrough)) {
            if (stareTicks >= LINE_AFTER && tick % 2 == 0) {
                gazeLine(owner, target, Math.min(1, (double) stareTicks / needed));
            }
            if (stareTicks >= needed) {
                seeThrough(owner, target, now);
            }
        }
        updateSeenThrough(owner, now, tick);
    }

    /** The red line of sight from the evil eye to the stared enemy, denser as the stare builds up. */
    private void gazeLine(Player owner, LivingEntity target, double progress) {
        Location from = owner.getEyeLocation();
        Location to = target.getEyeLocation();
        Vfx.line(ctx(), from, to, 0.6 + progress * 1.4, Particle.DUST, RED_DUST, false);
        Vfx.dot(from.clone().add(from.getDirection().multiply(0.3)), Particle.DUST, TRAIL_DUST, false);
        if (stareTicks % 10 == 0) {
            Fx.sound(owner.getEyeLocation(), Sound.BLOCK_SCULK_SENSOR_CLICKING, 0.3f, 1.4f + (float) progress * 0.6f);
        }
    }

    /** "…간파했다": the next hit on that enemy within the window lands harder. */
    private void seeThrough(Player owner, LivingEntity target, long now) {
        clearSeenThrough(owner, false);
        seenThrough = target;
        seenUntil = now + Math.max(1, params().ticks("eye_window"));
        Location head = labelPoint(target);
        if (reserve(1)) {
            TextDisplay label = keep(Displays.text(ctx(), owner, head, Text.mm("<b><" + RED_TAG + ">◉ 간파</" + RED_TAG
                    + "></b>"), 0.2f, 0));
            label.setTeleportDuration(1);
            Displays.animateNextTick(ctx(), owner, label, Displays.centered(0, 0, 0, 1.2f, 1.2f, 1.2f, 0, 0, 0), 4);
            seenLabel = label;
        }
        Vfx.line(ctx(), owner.getEyeLocation(), target.getEyeLocation(), 4, Particle.DUST_COLOR_TRANSITION,
                new Particle.DustTransition(RED, BLACK, 0.9f), false);
        Vfx.ring(ctx(), head.clone().subtract(0, 0.3, 0), 0.4, 12, 0, Particle.DUST, RED_DUST, false);
        Fx.sound(owner.getEyeLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.8f, 0.5f);
        Fx.sound(head, Sound.ENTITY_ENDERMAN_STARE, 0.3f, 1.6f);
        say(owner, "<i><" + RED_TAG + ">…간파했다</" + RED_TAG + "></i>", 24);
        owner.sendActionBar(Text.mm("<" + RED_TAG + ">◉ 사안</" + RED_TAG + "> <white><name></white><gray> 간파 — 다음 "
                + "피해 +" + AbilityInfo.formatNumber(params().num("eye_bonus")) + "%</gray>",
                Placeholder.component("name", target.name())));
    }

    private void updateSeenThrough(Player owner, long now, long tick) {
        LivingEntity target = seenThrough;
        if (target == null) {
            return;
        }
        if (now > seenUntil || !Combat.isTargetable(ctx(), owner, target) || !seen(owner, target)
                || !target.getWorld().equals(owner.getWorld())) {
            clearSeenThrough(owner, false);
            return;
        }
        TextDisplay label = seenLabel;
        if (label != null && label.isValid()) {
            Displays.moveTo(label, labelPoint(target));
        }
        if (tick % 6 == 0) {
            Vfx.dot(target.getEyeLocation().add(0, 0.25, 0), Particle.DUST, RED_DUST, false);
        }
    }

    private void clearSeenThrough(Player owner, boolean pierced) {
        TextDisplay label = seenLabel;
        seenLabel = null;
        LivingEntity target = seenThrough;
        seenThrough = null;
        if (label != null && label.isValid()) {
            if (pierced) {
                Displays.animate(label, Displays.centered(0, 0.3f, 0, 1.8f, 1.8f, 1.8f, 0, 0, 0), 3);
                ctx().runLater(owner, 5, () -> {
                    if (label.isValid()) {
                        Displays.shrinkAway(ctx(), owner, label, 4);
                    }
                });
            } else {
                Displays.shrinkAway(ctx(), owner, label, 4);
            }
        }
        if (pierced && target != null && seen(owner, target)) {
            Location body = Fx.body(target);
            Vfx.burst(ctx(), body, FLAME, 0.4, Particle.CRIT, false);
            Vfx.cloud(ctx(), body, Particle.DUST, 6, 0.3, 0, RED_DUST, false);
            Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 0.7f);
        }
        if (target != null && target.equals(staring)) {
            stareTicks = 0;
        }
    }

    private static Location labelPoint(LivingEntity target) {
        return target.getLocation().add(0, target.getHeight() + LABEL_ABOVE, 0);
    }

    /** The seen-through bonus: only changes the damage (the victim may still cancel the hit). */
    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled() || !victim.equals(seenThrough)) {
            return;
        }
        double bonus = Math.min(event.getDamage() * Math.max(0, params().num("eye_bonus")) / 100.0,
                Math.max(0, params().num("eye_bonus_max")));
        event.setDamage(event.getDamage() + bonus);
    }

    /** The seen-through hit landed: the insight is spent. */
    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (victim.equals(seenThrough)) {
            clearSeenThrough(player(), true);
        }
    }

    // ------------------------------------------------------------------ active: unsealing stance

    @Override
    public ActivationResult onActive() {
        if (chant != null) {
            return ActivationResult.fail("영창 중입니다");
        }
        if (stance != null) {
            return ActivationResult.fail("이미 봉인을 풀고 있습니다");
        }
        Stance started = new Stance(player());
        stance = started;
        started.begin();
        return ActivationResult.success();
    }

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        Stance current = stance;
        if (event.isCancelled() || current == null || current.done) {
            return;
        }
        DamageCause cause = event.getCause();
        if (cause == DamageCause.VOID || cause == DamageCause.KILL || cause == DamageCause.SUICIDE) {
            return;
        }
        double share = Math.clamp(params().num("swallow_share") / 100.0, 0.0, 1.0);
        double felt = felt(event);
        if (share <= 0 || felt <= 0) {
            return;
        }
        // The base damage shrinks by the share; what the blow would have taken (after armor, resistance and absorption
        // hearts) minus what it takes now is what the arm swallowed: the flame grows with the damage as players read it.
        event.setDamage(event.getDamage() * (1 - share));
        double swallowed = Math.max(0, felt - felt(event));
        if (swallowed > 0) {
            current.swallow(swallowed);
        }
    }

    /** What the hit takes off health and absorption hearts together, after armor and resistance. */
    @SuppressWarnings("deprecation") // DamageModifier is the only exact source of the absorbed amount; no replacement
    private static double felt(EntityDamageEvent event) {
        return Math.max(0, event.getFinalDamage() - event.getDamage(EntityDamageEvent.DamageModifier.ABSORPTION));
    }

    /**
     * The right arm held across the face: it swallows half of every blow and stores it as black flame until the
     * chuuni crouches (a fresh crouch) or holds it as long as it can; then the arm is thrust out.
     */
    private final class Stance implements LongPredicate {

        private final Player owner;
        private final long duration;
        private final List<BlockDisplay> cracks = new ArrayList<>();
        private @Nullable NamespacedKey slow;
        private @Nullable BlockDisplay arm;
        private double flame;
        private boolean wasSneaking;
        boolean done;

        Stance(Player owner) {
            this.owner = owner;
            this.duration = Math.max(1, params().ticks("stance_time"));
            this.wasSneaking = owner.isSneaking();
        }

        double maxFlame() {
            return Math.max(0, params().num("flame_max"));
        }

        void begin() {
            double slowness = Math.clamp(params().num("stance_slow") / 100.0, 0.0, 1.0);
            if (slowness > 0) {
                slow = ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, -slowness,
                        Operation.MULTIPLY_SCALAR_1, duration + 2);
            }
            Location eye = owner.getEyeLocation();
            if (reserve(1)) {
                arm = keep(Displays.block(ctx(), owner, eye, BANDAGE.createBlockData(), armPose(eye.getYaw(), 0.02f, 0),
                        duration + 30));
                arm.setTeleportDuration(1);
                // The arm is in front of the face: only the others see it.
                owner.hideEntity(ctx().plugin(), arm);
                Displays.animateNextTick(ctx(), owner, arm, armPose(eye.getYaw(), 1f, 0), 3);
            }
            Fx.sound(eye, Sound.ITEM_ARMOR_EQUIP_LEATHER, 1.0f, 0.7f);
            Fx.sound(eye, Sound.BLOCK_RESPAWN_ANCHOR_AMBIENT, 0.8f, 0.6f);
            say(owner, "<i><" + BANDAGE_TAG + ">크윽… 오른팔이…!</" + BANDAGE_TAG + "></i>", 26);
            Loop.repeat(ctx(), owner, 1, 1, duration, this, this::release);
        }

        void swallow(double amount) {
            double before = flame;
            flame = Math.min(maxFlame(), flame + amount * Math.max(0, params().num("flame_ratio")));
            Location eye = owner.getEyeLocation();
            Location at = armPoint(owner);
            Vfx.burst(ctx(), at, FLAME, 0.35, Particle.SQUID_INK, false);
            Fx.sound(at, Sound.BLOCK_SCULK_CHARGE, 1.0f, 0.6f + Fx.jitter(0.1f));
            Fx.sound(at, Sound.ENTITY_WITHER_HURT, 0.3f, 1.6f);
            // The bandages split open as the flame builds up.
            int wanted = maxFlame() <= 0 ? 0 : (int) Math.ceil(flame / maxFlame() * CRACKS);
            for (int i = cracks.size(); i < wanted && reserve(1); i++) {
                BlockDisplay crack = keep(Displays.block(ctx(), owner, eye, SEAL_CRACK.createBlockData(),
                        crackPose(eye.getYaw(), i, 0.01f), duration + 30));
                crack.setTeleportDuration(1);
                owner.hideEntity(ctx().plugin(), crack);
                cracks.add(crack);
            }
            if (flame > before) {
                owner.sendActionBar(Text.mm("<" + PURPLE_TAG + ">흑염</" + PURPLE_TAG + "> <white>"
                        + AbilityInfo.formatNumber(Math.round(flame * 10) / 10.0) + "</white><gray>/"
                        + AbilityInfo.formatNumber(maxFlame()) + " — 웅크리면 해방</gray>"));
            }
        }

        @Override
        public boolean test(long tick) {
            if (done) {
                return false;
            }
            boolean sneaking = owner.isSneaking();
            boolean pressed = sneaking && !wasSneaking;
            wasSneaking = sneaking;
            if (pressed) {
                return false;
            }
            Location eye = owner.getEyeLocation();
            float swell = (float) (maxFlame() <= 0 ? 0 : flame / maxFlame());
            BlockDisplay shown = arm;
            if (shown != null && shown.isValid()) {
                Displays.moveTo(shown, eye);
                Displays.animate(shown, armPose(eye.getYaw(), 1f, swell), 1);
            }
            for (int i = 0; i < cracks.size(); i++) {
                BlockDisplay crack = cracks.get(i);
                if (crack.isValid()) {
                    Displays.moveTo(crack, eye);
                    Displays.animate(crack, crackPose(eye.getYaw(), i, 1f + swell * 0.6f), 1);
                }
            }
            Location at = armPoint(owner);
            if (tick % 2 == 0) {
                Vfx.dot(at.clone().add(Fx.jitter(0.25f), 0.1, Fx.jitter(0.25f)), Particle.DUST_COLOR_TRANSITION,
                        swell > 0 ? FLAME_GLOW : new Particle.DustTransition(Color.fromRGB(0xECE6DA), PURPLE, 0.7f),
                        false);
            }
            if (flame > 0 && tick % 3 == 0) {
                Vfx.dot(at.clone().add(0, 0.2, 0), Particle.SMOKE, null, false);
                Vfx.dot(at, Particle.REVERSE_PORTAL, null, false);
            }
            if (tick % 8 == 0) {
                Fx.sound(at, Sound.BLOCK_RESPAWN_ANCHOR_AMBIENT, 0.5f, 0.7f + swell * 0.5f);
            }
            return true;
        }

        /** The arm is thrust out: a cone of black flame, as strong as what it swallowed. */
        void release() {
            if (done) {
                return;
            }
            done = true;
            if (stance == this) {
                stance = null;
            }
            if (slow != null) {
                ctx().removeModifier(owner, slow);
                slow = null;
            }
            for (BlockDisplay crack : cracks) {
                if (crack.isValid()) {
                    Displays.shrinkAway(ctx(), owner, crack, 4);
                }
            }
            if (!ctx().isAlive(owner)) {
                return;
            }
            thrust();
            strike();
        }

        /** The bandaged arm shoots forward out of the face and snaps back. */
        private void thrust() {
            BlockDisplay shown = arm;
            if (shown == null || !shown.isValid()) {
                return;
            }
            Location eye = owner.getEyeLocation();
            Displays.moveTo(shown, eye);
            Displays.animate(shown, thrustPose(eye.getYaw(), eye.getPitch(), 2.4f, 0.22f), 2);
            ctx().runLater(owner, 4, () -> {
                if (shown.isValid()) {
                    Location now = owner.getEyeLocation();
                    Displays.moveTo(shown, now);
                    Displays.animate(shown, thrustPose(now.getYaw(), now.getPitch(), 0.4f, 0.16f), 3);
                    ctx().runLater(owner, 3, () -> {
                        if (shown.isValid()) {
                            Displays.shrinkAway(ctx(), owner, shown, 3);
                        }
                    });
                }
            });
        }

        private void strike() {
            double range = Math.max(1, params().num("strike_range"));
            double half = Math.toRadians(Math.clamp(params().num("strike_angle"), 5, 180) / 2);
            double damage = params().num("strike_base") + flame;
            Location eye = owner.getEyeLocation();
            Vector look = Motion.horizontalFacing(eye);
            int hits = 0;
            for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), range)) {
                Vector to = Motion.horizontal(enemy.getLocation().toVector().subtract(owner.getLocation().toVector()));
                if (to.lengthSquared() > 1.0E-6 && to.angle(look) > half) {
                    continue;
                }
                if (!Spots.clearPath(eye, Fx.body(enemy)) && !Spots.clearPath(eye, enemy.getEyeLocation())) {
                    continue;
                }
                Combat.hit(ctx(), owner, enemy, damage);
                hits++;
                if (Combat.isTargetable(ctx(), owner, enemy)) {
                    Vector away = to.lengthSquared() > 1.0E-6 ? to : look.clone();
                    double distance = Math.max(0, params().num("strike_push"));
                    double speed = distance * (1 - AIR_DRAG) / (1 - Math.pow(AIR_DRAG, PUSH_AIR_TICKS));
                    Motion.push(enemy, away.multiply(speed).setY(PUSH_LIFT), MAX_SPEED);
                }
                if (seen(owner, enemy)) {
                    Location body = Fx.body(enemy);
                    Vfx.burst(ctx(), body, FLAME, 0.5, Particle.SQUID_INK, false);
                }
            }
            flameCone(eye, look, range, half);
            Fx.sound(eye, Sound.ENTITY_WITHER_SHOOT, 0.8f, 0.7f + Fx.jitter(0.05f));
            Fx.sound(eye, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.6f);
            Fx.sound(eye, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.9f, 0.8f);
            if (flame >= maxFlame() - 1.0E-9 && flame > 0) {
                Fx.sound(eye, Sound.ENTITY_RAVAGER_ROAR, 0.6f, 1.4f);
            }
            if (flame <= 1.0E-9) {
                say(owner, "<i><gray>…오늘은 봉인이 단단하군</gray></i>", 34);
            } else {
                say(owner, "<b><" + PURPLE_TAG + ">흑염이여, 삼켜라!</" + PURPLE_TAG + "></b>", 26);
            }
            owner.sendActionBar(Text.mm("<" + PURPLE_TAG + ">봉인 해제</" + PURPLE_TAG + "> <white>"
                    + AbilityInfo.formatNumber(Math.round(damage * 10) / 10.0) + " 피해</white>"
                    + (hits > 0 ? " <gray>" + hits + "명</gray>" : "")));
        }

        /** Arcs of black and purple flame fanning out from the arm, with ink and lilac sparks. */
        private void flameCone(Location eye, Vector look, double range, double half) {
            Location chest = eye.clone().subtract(0, 0.4, 0);
            float yaw = (float) Math.toDegrees(Math.atan2(-look.getX(), look.getZ()));
            double width = Math.toDegrees(half) * 2;
            double strength = maxFlame() <= 0 ? 0 : flame / maxFlame();
            Loop.repeat(ctx(), owner, 0, 1, 5, step -> {
                for (int ring = 0; ring < 2; ring++) {
                    double radius = range * (step * 2 + ring + 1) / 10.0;
                    Location at = chest.clone();
                    Vfx.arc(ctx(), at, radius, yaw, width, (int) Math.round(6 + radius * 3),
                            Particle.DUST_COLOR_TRANSITION, ring == 0 ? FLAME_GLOW : FLAME_FADE, false);
                }
                Location tip = chest.clone().add(look.clone().multiply(range * (step + 1) / 5.0));
                Vfx.cloud(ctx(), tip, Particle.SQUID_INK, 2 + (int) Math.round(strength * 3), 0.3, 0.02, null, false);
                Vfx.cloud(ctx(), tip, Particle.REVERSE_PORTAL, 4, 0.4, 0.05, null, false);
                Vfx.cloud(ctx(), tip, Particle.DUST, 3, 0.3, 0, BLACK_DUST, false);
                return true;
            });
        }
    }

    /** In front of the face, where the arm is held. */
    private static Location armPoint(Player owner) {
        Location eye = owner.getEyeLocation();
        return eye.add(Motion.horizontalFacing(eye).multiply(0.4)).subtract(0, 0.12, 0);
    }

    /**
     * The bandaged forearm held across the face (head space: +Z front, +X left), tilted, swelling with the stored flame;
     * turned by the head's yaw in the transformation only.
     */
    private static Transformation armPose(float yaw, float grow, float swell) {
        Quaternionf frame = yawTurn(yaw).rotateZ((float) Math.toRadians(-24));
        float thickness = (0.17f + 0.13f * swell) * Math.max(0.05f, grow);
        float length = 0.62f * Math.max(0.05f, grow);
        Vector3f center = yawTurn(yaw).transform(new Vector3f(0.02f, -0.12f, 0.38f));
        return box(center, new Vector3f(length, thickness, thickness), frame);
    }

    /** Crack {@code index} of the seal: a small cube of crying obsidian bursting out of the bandages. */
    private static Transformation crackPose(float yaw, int index, float grow) {
        Quaternionf frame = yawTurn(yaw).rotateZ((float) Math.toRadians(-24));
        float along = -0.2f + 0.2f * index;
        Vector3f local = new Vector3f(along, 0.07f - 0.03f * index, 0.0f);
        Vector3f center = yawTurn(yaw).transform(new Vector3f(0.02f, -0.12f, 0.4f))
                .add(new Quaternionf(frame).transform(local));
        float size = 0.11f * Math.max(0.05f, grow);
        return box(center, new Vector3f(size, size, size), new Quaternionf(frame).rotateY(0.7f + index));
    }

    /** The arm thrust out from the right shoulder along the look, {@code length} long. */
    private static Transformation thrustPose(float yaw, float pitch, float length, float width) {
        Quaternionf frame = yawTurn(yaw).rotateX((float) Math.toRadians(Math.clamp(pitch, -60f, 60f)));
        Vector3f shoulder = yawTurn(yaw).transform(new Vector3f(-0.3f, -0.35f, 0.1f));
        Vector3f along = new Quaternionf(frame).transform(new Vector3f(0, 0, length));
        Vector3f center = new Vector3f(shoulder).add(new Vector3f(along).mul(0.5f));
        return box(center, new Vector3f(width, width, length), frame);
    }

    // ------------------------------------------------------------------ ultimate: black-flame dragon chant

    /**
     * Self-contained (owner-scoped tasks; silence and stares are polled), so a Gambler can borrow it. A chant cut short
     * gives back half the gauge.
     */
    @Override
    public ActivationResult onUltimate() {
        if (chant != null) {
            return ActivationResult.fail("이미 영창 중입니다");
        }
        Stance running = stance;
        if (running != null) {
            running.release(); // the arm lets go first
        }
        Chant started = new Chant(player());
        chant = started;
        started.begin();
        return ActivationResult.success();
    }

    /**
     * The chant: three lines over the chuuni's head while it draws the dragon's path on the ground with its gaze; being
     * silenced or stared in the face fails it.
     */
    private final class Chant implements LongPredicate {

        private final Player owner;
        private final long duration;
        private final List<Location> path = new ArrayList<>();
        private final Set<UUID> watchers = new HashSet<>();
        private double length;
        private double stared;
        private int line = -1;
        private @Nullable TextDisplay words;
        private boolean over;

        Chant(Player owner) {
            this.owner = owner;
            this.duration = Math.max(3, params().ticks("chant_time"));
            path.add(owner.getLocation().add(0, 0.1, 0));
        }

        void begin() {
            Location feet = owner.getLocation();
            Fx.sound(feet, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1.0f, 0.7f);
            Fx.sound(feet, Sound.BLOCK_END_PORTAL_FRAME_FILL, 1.0f, 0.6f);
            if (reserve(1)) {
                words = keep(Displays.text(ctx(), owner, wordsPoint(owner), Text.mm(""), 0.2f, duration + 10));
                words.setTeleportDuration(1);
                Displays.animateNextTick(ctx(), owner, words, Displays.centered(0, 0, 0, 1.0f, 1.0f, 1.0f, 0, 0, 0),
                        3);
            }
            line = 0;
            speak(0);
            Loop.repeat(ctx(), owner, 1, 1, duration, this, this::finish);
        }

        @Override
        public boolean test(long tick) {
            if (over) {
                return false;
            }
            int now = (int) Math.min(LINES.length - 1, tick * LINES.length / duration);
            if (now != line) {
                line = now;
                speak(now);
            }
            if (ctx().isSilenced(owner)) {
                fail(false);
                return false;
            }
            if (watched(tick)) {
                fail(true);
                return false;
            }
            draw(owner);
            TextDisplay shown = words;
            if (shown != null && shown.isValid()) {
                Location at = wordsPoint(owner);
                if (stared > 0) {
                    at.add(Fx.jitter(0.05f), 0, Fx.jitter(0.05f));
                }
                Displays.moveTo(shown, at);
            }
            if (tick % 3 == 0) {
                drawTrail();
            }
            chantFx(owner, tick);
            return true;
        }

        /** The next line: over the head and on the action bar of everyone near, as a big title for the chuuni alone. */
        private void speak(int index) {
            String text = LINES[index];
            String mini = "<b><" + PURPLE_TAG + ">" + text + "</" + PURPLE_TAG + "></b>";
            TextDisplay shown = words;
            if (shown != null && shown.isValid()) {
                shown.text(Text.mm(mini));
            }
            Location at = owner.getLocation();
            for (Player near : at.getWorld().getPlayers()) {
                if (!near.equals(owner) && near.getLocation().distanceSquared(at) <= LINE_HEARING * LINE_HEARING) {
                    near.sendActionBar(Text.mm("<gray><name>:</gray> " + mini, Placeholder.component("name",
                            owner.name())));
                }
            }
            long per = Math.max(1, duration / LINES.length);
            owner.showTitle(Title.title(Text.mm(mini), Text.mm("<gray>바라보는 땅에 흑염룡의 길이 그려집니다</gray>"),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(per * 50 + 100), Duration.ofMillis(200))));
            Fx.sound(at, Sound.ENTITY_EVOKER_CAST_SPELL, 0.8f, 0.6f + 0.15f * index);
            Fx.sound(at, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.6f, 0.5f + 0.2f * index);
        }

        /**
         * Enemies looking the chuuni in the face from within range and in plain view: their stares add up; too much of
         * it and the chant fails. Only players look; nobody hidden from the chuuni counts.
         */
        private boolean watched(long tick) {
            double range = Math.max(0, params().num("shy_range"));
            Location face = owner.getEyeLocation();
            int lookers = 0;
            for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), range)) {
                if (!(enemy instanceof Player watcher) || !seen(owner, watcher)) {
                    continue;
                }
                if (!lookingAt(watcher, face) || !watcher.hasLineOfSight(owner)) {
                    continue;
                }
                lookers++;
                if (watchers.add(watcher.getUniqueId())) {
                    watcher.sendActionBar(Text.mm("<" + BLUSH_TAG + ">계속 쳐다보면 영창이 끊깁니다…</" + BLUSH_TAG
                            + ">"));
                }
                if (tick % 2 == 0) {
                    Vfx.line(ctx(), watcher.getEyeLocation(), face, 1.2, Particle.DUST,
                            new Particle.DustOptions(Color.fromRGB(0xFF7AA8), 0.6f), false);
                }
            }
            if (lookers > 0) {
                stared += lookers;
                if (tick % 4 == 0) {
                    Vfx.cloud(ctx(), face.clone().add(0, 0.3, 0), Particle.DUST, 2, 0.2, 0,
                            new Particle.DustOptions(Color.fromRGB(0xFF7AA8), 0.8f), false);
                    owner.sendActionBar(Text.mm("<" + BLUSH_TAG + ">누, 누가 보고 있어…!</" + BLUSH_TAG + ">"));
                }
            }
            return stared >= Math.max(1, params().ticks("shy_time"));
        }

        /** Extends the path to the ground the chuuni looks at, up to the trail's full length. */
        private void draw(Player owner) {
            double max = Math.max(1, params().num("trail_length"));
            if (length >= max - 1.0E-6) {
                return;
            }
            Location eye = owner.getEyeLocation();
            Vector look = eye.getDirection();
            if (!(look.lengthSquared() > 1.0E-6)) {
                return;
            }
            RayTraceResult hit = eye.getWorld().rayTraceBlocks(eye, look.normalize(), max, FluidCollisionMode.NEVER,
                    true);
            if (hit == null || hit.getHitBlock() == null) {
                return;
            }
            Location point = hit.getHitPosition().toLocation(eye.getWorld());
            if (hit.getHitBlockFace() != BlockFace.UP) {
                // A wall: the path runs along its foot.
                point.subtract(look.clone().multiply(0.3));
                Spots.snapToGround(point.getWorld(), point, 6);
            }
            point.add(0, 0.1, 0);
            Location last = path.getLast();
            double step = last.distance(point);
            if (step < MIN_STEP) {
                return;
            }
            double left = max - length;
            if (step > left) {
                point = last.clone().add(point.toVector().subtract(last.toVector()).multiply(left / step));
                step = left;
            }
            path.add(point);
            length += step;
            Vfx.dot(point.clone().add(0, 0.2, 0), Particle.DUST, TRAIL_DUST, true);
            if (path.size() % 3 == 0) {
                Fx.sound(point, Sound.BLOCK_SCULK_SENSOR_CLICKING, 0.4f, 0.8f + Fx.jitter(0.1f));
            }
        }

        /** The red trail on the ground, for everyone to read where the dragon will fly. */
        private void drawTrail() {
            for (int i = 1; i < path.size(); i++) {
                Vfx.line(ctx(), path.get(i - 1), path.get(i), 2, Particle.DUST, TRAIL_DUST, true);
            }
        }

        /** A magic circle turning at the feet, black flame rising and the evil eye glowing. */
        private void chantFx(Player owner, long tick) {
            Location feet = owner.getLocation().add(0, 0.08, 0);
            double phase = tick * 0.15;
            // The two rings of the circle take turns (each is redrawn every other tick, turning as it goes).
            if (tick % 2 == 0) {
                Vfx.ring(ctx(), feet, 1.4, 18, phase, Particle.DUST_COLOR_TRANSITION, FLAME_GLOW, true);
            } else {
                Vfx.ring(ctx(), feet, 0.9, 10, -phase * 1.5, Particle.DUST, RED_DUST, true);
            }
            if (tick % 2 == 0) {
                for (int i = 0; i < 3; i++) {
                    double angle = phase * 2 + i * TAU / 3;
                    Vfx.dot(feet.clone().add(Math.cos(angle) * 0.6, (tick * 0.1 + i * 0.5) % 2.0, Math.sin(angle)
                            * 0.6), Particle.DUST, BLACK_DUST, false);
                }
                Vfx.dot(owner.getEyeLocation().add(owner.getEyeLocation().getDirection().multiply(0.3)),
                        Particle.DUST, TRAIL_DUST, false);
            }
            if (tick % 5 == 0) {
                Vfx.cloud(ctx(), feet.clone().add(0, 1.0, 0), Particle.REVERSE_PORTAL, 4, 0.5, 0.05, null, false);
            }
        }

        /** "보, 보지 마…!" (or silenced): the chant breaks and half the gauge comes back. */
        private void fail(boolean shy) {
            over = true;
            if (chant == this) {
                chant = null;
            }
            TextDisplay shown = words;
            if (shown != null && shown.isValid()) {
                shown.text(Text.mm(shy ? "<b><" + BLUSH_TAG + ">보, 보지 마…!</" + BLUSH_TAG + "></b>"
                        : "<b><gray>…! (영창이 끊겼다)</gray></b>"));
                Displays.animate(shown, Displays.centered(0, 0.2f, 0, 1.3f, 1.3f, 1.3f, 0, 0, 0), 3);
                ctx().runLater(owner, 24, () -> {
                    if (shown.isValid()) {
                        Displays.shrinkAway(ctx(), owner, shown, 5);
                    }
                });
            }
            double refund = Math.clamp(params().num("fail_refund"), 0, 100);
            if (refund > 0) {
                ctx().addGauge(owner, refund);
            }
            Location face = owner.getEyeLocation();
            Vfx.cloud(ctx(), face, Particle.SMOKE, 8, 0.3, 0.02, null, false);
            Vfx.cloud(ctx(), face.clone().add(0, 0.2, 0), Particle.DUST, 8, 0.3, 0,
                    new Particle.DustOptions(Color.fromRGB(0xFF7AA8), 1.0f), false);
            Fx.sound(face, Sound.BLOCK_FIRE_EXTINGUISH, 0.8f, 1.2f);
            Fx.sound(face, Sound.ENTITY_VILLAGER_HURT, 0.6f, 1.6f);
            owner.showTitle(Title.title(Text.mm(shy ? "<b><" + BLUSH_TAG + ">보, 보지 마…!</" + BLUSH_TAG + "></b>"
                            : "<b><gray>영창이 끊겼다…!</gray></b>"),
                    Text.mm("<gray>궁극기 게이지 " + AbilityInfo.formatNumber(refund) + "% 반환</gray>"),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(300))));
            owner.sendActionBar(Text.mm("<gray>영창 실패 — 궁극기 게이지 " + AbilityInfo.formatNumber(refund)
                    + "% 반환</gray>"));
        }

        /** The chant is complete: the dragon awakens and flies the path. */
        void finish() {
            if (over) {
                return;
            }
            over = true;
            if (chant == this) {
                chant = null;
            }
            TextDisplay shown = words;
            if (shown != null && shown.isValid()) {
                Displays.animate(shown, Displays.centered(0, 0.3f, 0, 1.5f, 1.5f, 1.5f, 0, 0, 0), 3);
                ctx().runLater(owner, 10, () -> {
                    if (shown.isValid()) {
                        Displays.shrinkAway(ctx(), owner, shown, 5);
                    }
                });
            }
            if (!ctx().isAlive(owner)) {
                return;
            }
            if (path.size() < 2) {
                fallbackPath();
            }
            new Dragon(owner, path).fly();
        }

        /** Nothing was drawn (the chuuni looked at the sky): the dragon flies straight ahead over the ground. */
        private void fallbackPath() {
            Location start = path.getFirst();
            Vector ahead = Motion.horizontalFacing(owner.getLocation());
            double reach = Spots.arenaReach(ctx(), start, ahead, FALLBACK_PATH);
            for (double d = 2; d <= reach + 1.0E-6; d += 2) {
                Location point = start.clone().add(ahead.clone().multiply(d)).add(0, 1.5, 0);
                Spots.snapToGround(point.getWorld(), point, 5);
                path.add(point.add(0, 0.1, 0));
            }
            if (path.size() < 2) {
                path.add(start.clone().add(ahead.multiply(2)));
            }
        }
    }

    private static Location wordsPoint(Player owner) {
        return owner.getLocation().add(0, owner.getHeight() + 0.65, 0);
    }

    /** True if {@code watcher} looks at {@code point} (within the face angle). */
    private static boolean lookingAt(LivingEntity watcher, Location point) {
        Location eye = watcher.getEyeLocation();
        Vector toPoint = point.toVector().subtract(eye.toVector());
        if (toPoint.lengthSquared() < 1.0E-6) {
            return true;
        }
        Vector look = eye.getDirection();
        if (look.lengthSquared() < 1.0E-6) {
            return false;
        }
        return look.normalize().dot(toPoint.normalize()) >= FACE_COS;
    }

    /**
     * The black-flame dragon: a head and a long body skimming low over the drawn path in {@code dragon_time}, burning
     * every enemy near it once (damage and darkness, no fire), then diving into the end of the path.
     */
    private final class Dragon implements LongPredicate {

        private final Player owner;
        private final List<Location> path;
        private final double[] distances;
        private final double total;
        private final double speed;
        private final Set<UUID> burnt = new HashSet<>();
        private final List<BlockDisplay> head = new ArrayList<>();
        private final List<Transformation> headLocal = new ArrayList<>();
        private final List<BlockDisplay> body = new ArrayList<>();
        private double travelled;

        Dragon(Player owner, List<Location> path) {
            this.owner = owner;
            this.path = List.copyOf(path);
            this.distances = new double[path.size()];
            double sum = 0;
            for (int i = 1; i < path.size(); i++) {
                sum += path.get(i - 1).distance(path.get(i));
                distances[i] = sum;
            }
            this.total = Math.max(0.1, sum);
            long ticks = Math.max(1, params().ticks("dragon_time"));
            this.speed = Math.max(0.3, total / ticks);
        }

        void fly() {
            Location start = at(0);
            long life = (long) Math.ceil(total / speed) + 20;
            spawnHead(start, life);
            for (int i = 0; i < BODY_SEGMENTS && reserve(1); i++) {
                Material type = i % 2 == 0 ? DRAGON_SKULL : DRAGON_SCALE;
                BlockDisplay segment = keep(Displays.block(ctx(), owner, start, type.createBlockData(),
                        Displays.scaled(0.02f), life));
                segment.setTeleportDuration(1);
                body.add(segment);
            }
            Fx.sound(start, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.2f, 1.2f);
            Fx.sound(start, Sound.ENTITY_WITHER_SHOOT, 1.0f, 0.5f);
            Fx.sound(start, Sound.ENTITY_PHANTOM_FLAP, 1.0f, 0.6f);
            owner.sendActionBar(Text.mm("<b><" + PURPLE_TAG + ">흑염룡</" + PURPLE_TAG + "></b> <" + RED_TAG
                    + ">깨어나라!</" + RED_TAG + ">"));
            Vfx.burst(ctx(), start.clone().add(0, 1, 0), FLAME, 1.0, Particle.SQUID_INK, true);
            Loop.repeat(ctx(), owner, 1, 1, (long) Math.ceil(total / speed) + 1, this, this::plunge);
        }

        /** The head: skull, snout, two red eyes, two purple horns — posed in head space, +Z along the flight. */
        private void spawnHead(Location at, long life) {
            Object[][] parts = {
                    {DRAGON_SKULL, new Vector3f(0, 0, 0), new Vector3f(0.55f, 0.45f, 0.7f)},
                    {DRAGON_SNOUT, new Vector3f(0, -0.06f, 0.5f), new Vector3f(0.4f, 0.28f, 0.45f)},
                    {DRAGON_EYE, new Vector3f(0.2f, 0.12f, 0.3f), new Vector3f(0.1f, 0.08f, 0.1f)},
                    {DRAGON_EYE, new Vector3f(-0.2f, 0.12f, 0.3f), new Vector3f(0.1f, 0.08f, 0.1f)},
                    {DRAGON_HORN, new Vector3f(0.17f, 0.33f, -0.3f), new Vector3f(0.08f, 0.08f, 0.5f)},
                    {DRAGON_HORN, new Vector3f(-0.17f, 0.33f, -0.3f), new Vector3f(0.08f, 0.08f, 0.5f)},
            };
            for (Object[] part : parts) {
                if (!reserve(1)) {
                    return;
                }
                Material type = (Material) part[0];
                Transformation local = new Transformation((Vector3f) part[1], new Quaternionf(),
                        (Vector3f) part[2], new Quaternionf());
                BlockDisplay piece = keep(Displays.block(ctx(), owner, at, type.createBlockData(),
                        Displays.scaled(0.02f), life));
                piece.setTeleportDuration(1);
                head.add(piece);
                headLocal.add(local);
            }
        }

        /** The point {@code distance} along the path, {@link #DRAGON_ABOVE} over the ground. */
        private Location at(double distance) {
            double d = Math.clamp(distance, 0, total);
            for (int i = 1; i < path.size(); i++) {
                if (d <= distances[i] + 1.0E-9) {
                    double span = distances[i] - distances[i - 1];
                    double t = span <= 1.0E-9 ? 0 : (d - distances[i - 1]) / span;
                    Location a = path.get(i - 1);
                    Location b = path.get(i);
                    return a.clone().add(b.toVector().subtract(a.toVector()).multiply(t)).add(0, DRAGON_ABOVE, 0);
                }
            }
            return path.getLast().clone().add(0, DRAGON_ABOVE, 0);
        }

        @Override
        public boolean test(long tick) {
            travelled = Math.min(total, travelled + speed);
            Location front = at(travelled);
            Location behind = at(Math.max(0, travelled - 0.6));
            Vector heading = front.toVector().subtract(behind.toVector());
            if (heading.lengthSquared() < 1.0E-6) {
                heading = Motion.horizontalFacing(owner.getLocation());
            }
            heading.normalize();
            // A slow undulation, like a serpent swimming through the air.
            front.add(0, Math.sin(tick * 0.6) * 0.15, 0);
            poseHead(front, heading);
            poseBody(tick);
            burn(front);
            flightFx(front, heading, tick);
            return travelled < total - 1.0E-6;
        }

        private void poseHead(Location at, Vector heading) {
            Quaternionf frame = facing(heading);
            for (int i = 0; i < head.size(); i++) {
                BlockDisplay piece = head.get(i);
                if (!piece.isValid()) {
                    continue;
                }
                Transformation local = headLocal.get(i);
                Vector3f center = new Quaternionf(frame).transform(new Vector3f(local.getTranslation()));
                Displays.moveTo(piece, at);
                Displays.animate(piece, box(center, new Vector3f(local.getScale()), frame), 1);
            }
        }

        private void poseBody(long tick) {
            for (int i = 0; i < body.size(); i++) {
                BlockDisplay segment = body.get(i);
                if (!segment.isValid()) {
                    continue;
                }
                double back = travelled - (i + 1) * SEGMENT_GAP;
                Location at = at(Math.max(0, back));
                at.add(0, Math.sin(tick * 0.6 - (i + 1) * 0.9) * 0.15, 0);
                Location ahead = at(Math.max(0, back + 0.4));
                Vector heading = ahead.toVector().subtract(at(Math.max(0, back)).toVector());
                if (heading.lengthSquared() < 1.0E-6) {
                    heading = Motion.horizontalFacing(owner.getLocation());
                }
                float size = (float) (0.5 - 0.045 * i);
                Displays.moveTo(segment, at);
                Displays.animate(segment, box(new Vector3f(), new Vector3f(size, size * 0.85f, 0.7f),
                        facing(heading.normalize())), 1);
            }
        }

        /** Every enemy near the head burns once: damage and darkness (black flame, not fire). */
        private void burn(Location at) {
            double width = Math.max(0.5, params().num("dragon_width"));
            int darkness = (int) Math.min(Integer.MAX_VALUE, params().ticks("dragon_darkness"));
            for (LivingEntity enemy : ctx().enemiesNear(owner, at.clone().subtract(0, DRAGON_ABOVE * 0.5, 0),
                    width)) {
                if (!burnt.add(enemy.getUniqueId())) {
                    continue;
                }
                Combat.hit(ctx(), owner, enemy, params().num("dragon_damage"));
                if (Combat.isTargetable(ctx(), owner, enemy) && darkness > 0) {
                    enemy.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, darkness, 0, false, true,
                            true));
                }
                Fx.sound(enemy.getLocation(), Sound.ENTITY_WITHER_HURT, 0.6f, 1.4f + Fx.jitter(0.1f));
                if (seen(owner, enemy)) {
                    Vfx.burst(ctx(), Fx.body(enemy), FLAME, 0.6, Particle.SQUID_INK, false);
                }
            }
        }

        private void flightFx(Location at, Vector heading, long tick) {
            Location tail = at.clone().subtract(heading.clone().multiply(0.5));
            Vfx.cloud(ctx(), tail, Particle.DUST_COLOR_TRANSITION, 4, 0.35, 0, FLAME_GLOW, true);
            Vfx.cloud(ctx(), tail, Particle.DUST, 3, 0.4, 0, BLACK_DUST, false);
            Vfx.cloud(ctx(), tail, Particle.SQUID_INK, 1, 0.2, 0.01, null, false);
            Vfx.dot(at.clone().add(heading.clone().multiply(0.6)), Particle.REVERSE_PORTAL, null, false);
            Location ground = at.clone().subtract(0, DRAGON_ABOVE - 0.05, 0);
            Vfx.dot(ground, Particle.DUST, BLACK_DUST, false);
            if (tick % 4 == 0) {
                Fx.sound(at, Sound.ENTITY_PHANTOM_FLAP, 0.9f, 0.7f + Fx.jitter(0.1f));
            }
        }

        /** The end of the path: the dragon dives in and the black flame bursts out over the ground. */
        void plunge() {
            Location end = path.getLast().clone();
            for (Display display : new ArrayList<Display>(head)) {
                dive(display, end);
            }
            for (Display display : new ArrayList<Display>(body)) {
                dive(display, end);
            }
            double radius = Math.max(0.5, params().num("plunge_radius"));
            Location center = end.clone().add(0, 0.6, 0);
            int hits = 0;
            for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius)) {
                if (!Spots.clearPath(center, Fx.body(enemy)) && !Spots.clearPath(center, enemy.getEyeLocation())) {
                    continue;
                }
                Combat.hit(ctx(), owner, enemy, params().num("plunge_damage"));
                hits++;
            }
            plungeFx(end, radius);
            owner.sendActionBar(Text.mm("<b><" + PURPLE_TAG + ">흑염룡</" + PURPLE_TAG + "></b> <white>"
                    + burnt.size() + "명을 불태우고 내리꽂혔다</white>" + (hits > 0 ? " <gray>(+" + hits + ")</gray>"
                    : "")));
        }

        private void dive(Display display, Location end) {
            if (!display.isValid()) {
                return;
            }
            display.setTeleportDuration(3);
            Displays.moveTo(display, end.clone().subtract(0, 0.6, 0));
            ctx().runLater(owner, 3, () -> {
                if (display.isValid()) {
                    Displays.shrinkAway(ctx(), owner, display, 4);
                }
            });
        }

        private void plungeFx(Location end, double radius) {
            World world = end.getWorld();
            Location at = end.clone().add(0, 0.1, 0);
            world.spawnParticle(Particle.FLASH, at.clone().add(0, 0.5, 0), 1, 0, 0, 0, 0, PURPLE, true);
            Vfx.burst(ctx(), at.clone().add(0, 0.6, 0), FLAME, radius * 0.6, Particle.SQUID_INK, true);
            Vfx.shockwave(ctx(), owner, at, 0.3, radius + 0.5, 6, Particle.DUST_COLOR_TRANSITION, FLAME_GLOW, true);
            Vfx.shockwave(ctx(), owner, at.clone().add(0, 0.2, 0), 0.2, radius, 8, Particle.DUST, BLACK_DUST, true);
            Vfx.helix(ctx(), at, 0.8, 3.0, 1.5, 2, 14, ThreadLocalRandom.current().nextDouble(TAU),
                    Particle.DUST_COLOR_TRANSITION, FLAME_FADE, true);
            Vfx.cloud(ctx(), at, Particle.REVERSE_PORTAL, 20, radius * 0.4, 0.1, null, true);
            Block ground = at.clone().subtract(0, 0.4, 0).getBlock();
            if (!ground.isPassable()) {
                world.spawnParticle(Particle.BLOCK, at, Vfx.n(ctx(), 20), radius * 0.4, 0.1, radius * 0.4, 0,
                        ground.getBlockData());
            }
            Fx.sound(at, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 0.8f);
            Fx.sound(at, Sound.ENTITY_WITHER_SHOOT, 1.0f, 0.5f);
            Fx.sound(at, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 1.0f, 0.6f);
            // Black flame smoulders on the spot for a moment.
            Loop.repeat(ctx(), owner, 4, 3, 6, step -> {
                Vfx.cloud(ctx(), at, Particle.DUST_COLOR_TRANSITION, 4, radius * 0.4, 0, FLAME_FADE, false);
                Vfx.cloud(ctx(), at, Particle.SMOKE, 2, radius * 0.3, 0.02, null, false);
                return true;
            });
        }
    }

    /** The rotation turning head space (+Z front) to face {@code heading} (yaw and pitch). */
    private static Quaternionf facing(Vector heading) {
        double flat = Math.hypot(heading.getX(), heading.getZ());
        float yaw = (float) Math.toDegrees(Math.atan2(-heading.getX(), heading.getZ()));
        float pitch = (float) Math.toDegrees(Math.atan2(-heading.getY(), Math.max(1.0E-6, flat)));
        return yawTurn(yaw).rotateX((float) Math.toRadians(pitch));
    }

    // ------------------------------------------------------------------ helpers

    /** A short line of text over the chuuni's head that everyone can read. */
    private void say(Player owner, String mini, long ticks) {
        if (!reserve(1)) {
            return;
        }
        TextDisplay line = keep(Displays.text(ctx(), owner, wordsPoint(owner), Text.mm(mini), 0.2f, ticks + 6));
        line.setTeleportDuration(1);
        Displays.animateNextTick(ctx(), owner, line, Displays.centered(0, 0, 0, 0.8f, 0.8f, 0.8f, 0, 0, 0), 3);
        Loop.repeat(ctx(), owner, 1, 1, ticks, run -> {
            if (!line.isValid()) {
                return false;
            }
            Displays.moveTo(line, wordsPoint(owner).add(0, run * 0.005, 0));
            return true;
        }, () -> {
            if (line.isValid()) {
                Displays.shrinkAway(ctx(), owner, line, 4);
            }
        });
    }

    /** True unless {@code entity} is a player hidden from {@code owner} (stealth): nothing may reveal it. */
    private static boolean seen(Player owner, Entity entity) {
        return !(entity instanceof Player hidden) || owner.canSee(hidden);
    }

    private boolean reserve(int count) {
        live.removeIf(display -> !display.isValid());
        return live.size() + count <= MAX_DISPLAYS;
    }

    private <T extends Display> T keep(T display) {
        live.add(display);
        return display;
    }

    /** The rotation of a Minecraft yaw (0 faces +Z, 90 faces -X) about the vertical. */
    private static Quaternionf yawTurn(float yaw) {
        return new Quaternionf().rotateY((float) Math.toRadians(-yaw));
    }

    /** A box of {@code size} centered on {@code center} (relative to the display), turned by {@code rotation}. */
    private static Transformation box(Vector3f center, Vector3f size, Quaternionf rotation) {
        Vector3f translation = new Vector3f(center).sub(new Quaternionf(rotation).transform(new Vector3f(size)
                .mul(0.5f)));
        return new Transformation(translation, new Quaternionf(rotation), new Vector3f(size), new Quaternionf());
    }
}
