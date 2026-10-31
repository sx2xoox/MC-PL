package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Body;
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
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.block.Block;
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
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 텐구 — the red-faced, long-nosed mountain goblin of Japan, with the magic fan of the folk tale: fanned with one side
 * it makes a nose grow, with the other it shrinks. Every hit it lands without being hit back makes its own nose grow a
 * stage (and its blows heavier) until someone breaks it; its fan does not push, it changes sizes — the red side shrinks
 * the enemies in front (weaker blows, shorter reach, more knockback), the white side makes the tengu and its allies
 * bigger — and the ultimate turns every enemy in a wide sweep into a little person under a giant tengu that stomps them.
 *
 * <p>Look (tengu red, crow black, gold leaf): a red nose that really sticks out of the face and grows with every
 * stage, under a little black cap, with a gold glint at full length; when it breaks it bends down and snaps back to a
 * stub. The fan is a semicircle of red (or white) feathers on a bamboo handle that sweeps across the cone, trailing red
 * and gold leaves; shrunk enemies are wrapped in a collapsing red spiral and keep a little red ring over their head,
 * grown allies rise in a white-gold helix. The ultimate's fan is huge; every stomp of the giant cracks a ring into the
 * ground.
 */
public final class TenguAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("tengu", "텐구", Material.BAMBOO)
            .color(TextColor.color(0xC8281E))
            .difficulty(2)
            .passive("콧대",
                    "맞지 않고 적을 맞힐 때마다(근접·화살·능력) 코가 한 단계 길어져 단계마다 주는 피해가 {nose_bonus}% "
                            + "늘어납니다(최대 {nose_max}단계). 적에게 맞으면 처음으로 돌아가고, 최대 단계에서 꺾이면 "
                            + "{snap_stagger}초간 휘청입니다.")
            .active("요술 부채",
                    "앞쪽 {fan_range}블록 부채꼴의 적에게 {fan_damage} 피해를 주고 {shrink_duration}초간 {shrink_scale}배로 "
                            + "작게 만듭니다(근접 피해 -{shrink_melee}%, 공격 사거리 -{shrink_reach}블록, 받는 넉백 "
                            + "+{shrink_knockback}%). 웅크리고 쓰면 대신 나와 앞쪽 아군을 {grow_duration}초간 "
                            + "{grow_scale}배로 키웁니다(근접 피해 +{grow_melee}%, 공격 사거리 +{grow_reach}블록).",
                    8)
            .ultimate("소인국",
                    "{ult_windup}초간 큰 부채를 치켜들었다 휘둘러 앞쪽 {ult_range}블록 부채꼴의 적을 {ult_duration}초간 "
                            + "{tiny_scale}배 소인으로 만들고(근접 피해 -{tiny_melee}%, 공격 사거리 -{tiny_reach}블록, 넉백 "
                            + "{tiny_knockback}배), 나는 {giant_scale}배로 커집니다. 그동안 소인에게 주는 근접 피해가 "
                            + "{giant_melee}% 늘고, 뛰었다가 소인 {stomp_radius}블록 안에 착지하면 짓밟아 {stomp_damage} "
                            + "피해를 줍니다.")
            .ultimateNotStandalone()
            .param("nose_max", 4)
            .param("nose_bonus", 6)
            .param("nose_interval", 0.5)
            .param("snap_stagger", 0.6)
            .param("snap_slow", 50)
            .param("fan_range", 6)
            .param("fan_angle", 80)
            .param("fan_damage", 2)
            .param("shrink_duration", 4)
            .param("shrink_scale", 0.65)
            .param("shrink_melee", 30)
            .param("shrink_reach", 1)
            .param("shrink_knockback", 50)
            .param("grow_duration", 4)
            .param("grow_scale", 1.25)
            .param("grow_melee", 15)
            .param("grow_reach", 0.75)
            .param("ult_windup", 0.75)
            .param("ult_range", 10)
            .param("ult_angle", 100)
            .param("ult_duration", 6)
            .param("tiny_scale", 0.45)
            .param("tiny_melee", 50)
            .param("tiny_reach", 1.5)
            .param("tiny_knockback", 2)
            .param("giant_scale", 1.4)
            .param("giant_melee", 30)
            .param("stomp_radius", 2)
            .param("stomp_damage", 3)
            .param("stomp_cooldown", 1)
            .param("regrow_wait", 3)
            .build();

    private static final String RED_TAG = "#C8281E";
    private static final String GOLD_TAG = "#D4AF37";

    // ------------------------------------------------------------------ look
    /** Pale gold, tengu red, crow black. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFE7A0, 0xC8281E, 0x1C1C22);
    private static final Color RED = Color.fromRGB(0xC8281E);
    private static final Color GOLD = Color.fromRGB(0xD4AF37);
    private static final Color BLACK = Color.fromRGB(0x1C1C22);
    private static final Color WHITE = Color.fromRGB(0xF6F1E6);
    private static final Particle.DustOptions RED_DUST = new Particle.DustOptions(RED, 0.9f);
    private static final Particle.DustOptions SMALL_RED = new Particle.DustOptions(RED, 0.6f);
    private static final Particle.DustOptions GOLD_DUST = new Particle.DustOptions(GOLD, 0.8f);
    private static final Particle.DustOptions WHITE_DUST = new Particle.DustOptions(WHITE, 0.9f);
    private static final Particle.DustOptions BLACK_DUST = new Particle.DustOptions(BLACK, 0.9f);
    private static final Particle.DustTransition RED_FADE = new Particle.DustTransition(RED, BLACK, 1.1f);
    private static final Particle.DustTransition GOLD_GLOW = new Particle.DustTransition(GOLD, WHITE, 0.9f);

    /** Block types; block data is created on use (it needs a running server). */
    private static final Material NOSE = Material.RED_CONCRETE;
    private static final Material CAP = Material.BLACK_CONCRETE;
    private static final Material RED_FEATHER = Material.RED_CONCRETE;
    private static final Material RED_FEATHER_DARK = Material.RED_TERRACOTTA;
    private static final Material WHITE_FEATHER = Material.WHITE_CONCRETE;
    private static final Material WHITE_FEATHER_SOFT = Material.WHITE_WOOL;
    private static final Material HANDLE = Material.STRIPPED_BAMBOO_BLOCK;

    private static final int MAX_DISPLAYS = 24;
    private static final double TAU = Math.PI * 2;
    /**
     * Ticks the worn pieces take to turn with the head: as long as a client lerps another player's head (3 steps), so
     * the nose turns with the face instead of leading it.
     */
    private static final int HEAD_LERP = 3;

    // passive: the nose
    private static final float NOSE_BASE = 0.12f;
    private static final float NOSE_STEP = 0.11f;
    private static final float NOSE_WIDTH = 0.085f;
    /**
     * A player's head turns about its neck, this far under the eyes (blocks at scale 1: the model's neck is 24 pixels
     * up a 0.9375-scaled body, 1.41 against the 1.62 eye height).
     */
    private static final float NECK_BELOW_EYES = 0.22f;
    /** The nose's root, from the neck: up to just under the eyes, forward to just inside the face (0.234 deep). */
    private static final float NOSE_UP = 0.16f;
    private static final float FACE_FRONT = 0.2f;
    /** The cap's middle, from the neck: on top of the head (0.47 up), over the forehead. */
    private static final float CAP_UP = 0.47f;
    private static final float CAP_FRONT = 0.12f;
    private static final long VISIBILITY_PERIOD = 10;
    private static final long GLINT_PERIOD = 12;
    private static final int BREAK_TICKS = 6;

    // the fan
    private static final int FAN_BLADES = 7;
    private static final int BIG_FAN_BLADES = 9;
    private static final float BLADE_LENGTH = 0.6f;
    private static final float BLADE_WIDTH = 0.13f;
    private static final float BLADE_SPREAD = 70f;
    private static final float BIG_FAN = 2.2f;
    private static final int SWING_TICKS = 6;
    /** The fan takes effect at this tick of its swing. */
    private static final int SWING_HIT = 3;
    private static final float SWING_ARC = 55f;

    // size changes
    private static final long SIZE_PERIOD = 2;
    private static final long MARK_PERIOD = 10;
    /** Allies over this distance (blocks) from the tengu are not checked for a cramped growth any more. */
    private static final double GROWTH_REACH = 64;
    private static final double MAX_SPEED = 1.5;

    // ultimate: the stomp
    private static final int MIN_AIR_TICKS = 4;
    private static final double STOMP_HEIGHT = 1.5;
    private static final double STOMP_PUSH = 0.25;
    private static final long GIANT_STEP_PERIOD = 8;
    private static final double GIANT_STEP_MIN = 0.6;
    private static final double STOMP_LIFT = 0.2;

    // ------------------------------------------------------------------ state

    private final List<Display> live = new ArrayList<>();
    private final Map<UUID, Shrink> shrunk = new HashMap<>();
    private final Map<UUID, Growth> grown = new HashMap<>();
    private final Map<UUID, Long> stomped = new HashMap<>();
    private final Set<UUID> noseHiddenFrom = new HashSet<>();
    private @Nullable TenguListener listener;
    private @Nullable BlockDisplay nose;
    private @Nullable BlockDisplay cap;
    private int stage;
    private long lastGain = Long.MIN_VALUE / 2;
    private long breakStart = Long.MIN_VALUE / 2;
    private int brokenFrom;
    private @Nullable NamespacedKey stagger;
    /** The land of little people lasts until then (with or without a giant tengu). */
    private long ultUntil = Long.MIN_VALUE / 2;
    private int ultToken;
    private @Nullable Growth giant;
    private int airTicks;
    private @Nullable Location lastStep;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onGrant() {
        TenguListener created = new TenguListener();
        Bukkit.getPluginManager().registerEvents(created, ctx().plugin());
        listener = created;
        Player owner = player();
        if (reserve(2)) {
            List<Player> unseen = new ArrayList<>();
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (!viewer.equals(owner) && !viewer.canSee(owner)) {
                    unseen.add(viewer);
                }
            }
            // The owner's own nose would sit right in front of its camera, and whoever cannot see the tengu does not
            // see its nose: neither ever receives it.
            Consumer<BlockDisplay> hide = part -> {
                owner.hideEntity(ctx().plugin(), part);
                for (Player viewer : unseen) {
                    viewer.hideEntity(ctx().plugin(), part);
                }
            };
            nose = keep(wear(owner, NOSE, nosePose(owner, 0, 0), 0, hide));
            cap = keep(wear(owner, CAP, capPose(owner), 0, hide));
            for (Player viewer : unseen) {
                noseHiddenFrom.add(viewer.getUniqueId());
            }
        }
    }

    /**
     * A block display worn by the owner: it rides the owner, so every client draws it exactly on the body it shows (a
     * display teleported after a player every tick trails and jitters around it: the player's own position reaches
     * clients later, and lerped). It is posed relative to where it rides, the top of the owner's hitbox
     * ({@code getHeight()} over the feet); {@code setup} runs inside the spawn, before any client receives it.
     * (A player carrying passengers cannot be teleported to another world: the match takes them off before it sends
     * its players home, and these go with the ability.)
     */
    private BlockDisplay wear(Player owner, Material type, Transformation pose, long lifetime,
                              Consumer<BlockDisplay> setup) {
        Location top = Displays.unrotated(owner.getLocation().add(0, owner.getHeight(), 0));
        BlockDisplay part = Displays.spawn(ctx(), owner, top, BlockDisplay.class, lifetime, display -> {
            display.setBlock(type.createBlockData());
            display.setTransformation(pose);
            setup.accept(display);
        });
        owner.addPassenger(part);
        return part;
    }

    /** Puts a worn piece back on the owner if something took it off (a respawn gives the player a new body). */
    private static void ride(Player owner, Entity part) {
        if (part.isValid() && !owner.equals(part.getVehicle()) && part.getWorld().equals(owner.getWorld())) {
            owner.addPassenger(part);
        }
    }

    @Override
    public void onRevoke() {
        if (listener != null) {
            HandlerList.unregisterAll(listener);
            listener = null;
        }
        // Shrunk enemies with room to stand regrow now; the others keep their timed fallback.
        for (Shrink shrink : shrunk.values()) {
            if (shrink.enemy.isValid() && fitsNormal(shrink.enemy, shrink.factor)) {
                shrink.strip();
            }
        }
        for (Growth growth : grown.values()) {
            growth.strip();
        }
        if (giant != null) {
            giant.strip();
        }
        shrunk.clear();
        grown.clear();
        stomped.clear();
        noseHiddenFrom.clear();
        giant = null;
        ultUntil = Long.MIN_VALUE / 2;
        ultToken++;
        nose = null;
        cap = null;
        stagger = null;
        live.clear();
    }

    /** Vanilla knockback on shrunk enemies is stronger (abilities push with velocities, those are their own). */
    private final class TenguListener implements Listener {

        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onKnockback(EntityKnockbackEvent event) {
            Shrink shrink = shrunk.get(event.getEntity().getUniqueId());
            if (shrink == null || ctx().currentTick() >= shrink.until) {
                return;
            }
            switch (event.getCause()) {
                case ENTITY_ATTACK, SWEEP_ATTACK, DAMAGE, EXPLOSION -> {
                    Vector knock = event.getKnockback();
                    double extra = shrink.knockback;
                    event.setKnockback(new Vector(knock.getX() * extra, knock.getY(), knock.getZ() * extra));
                }
                default -> {
                }
            }
        }
    }

    // ------------------------------------------------------------------ passive: the nose

    @Override
    public void onTick(long tick) {
        Player owner = player();
        long now = ctx().currentTick();
        poseNose(owner, now);
        if (tick % VISIBILITY_PERIOD == 0) {
            syncNoseVisibility(owner);
        }
        if (stage >= maxStage() && tick % GLINT_PERIOD == 0 && !hiddenOwner(owner)) {
            Location tip = noseTip(owner);
            Vfx.dot(tip, Particle.WAX_OFF, null, false);
            Vfx.dot(tip, Particle.DUST_COLOR_TRANSITION, GOLD_GLOW, false);
        }
        if (tick % SIZE_PERIOD == 0) {
            tickShrinks(now);
            tickGrowths(now);
        }
        if (tick % MARK_PERIOD == 0) {
            markShrunk(tick);
        }
    }

    private int maxStage() {
        return Math.max(1, params().integer("nose_max"));
    }

    /** The tengu's own hits: melee, its own projectiles or ability damage it deals itself (not a summon's bite). */
    private boolean isOwnHit(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        return player().equals(damager) || damager instanceof Projectile projectile
                && player().equals(projectile.getShooter());
    }

    private static boolean isMelee(EntityDamageByEntityEvent event) {
        return event.getCause() == DamageCause.ENTITY_ATTACK || event.getCause() == DamageCause.ENTITY_SWEEP_ATTACK;
    }

    /** A long nose hits harder, and the giant crushes little people: only changes the damage. */
    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled() || !isOwnHit(event)) {
            return;
        }
        double multiplier = 1 + stage * Math.max(0, params().num("nose_bonus")) / 100.0;
        Shrink shrink = shrunk.get(victim.getUniqueId());
        if (ultActive() && shrink != null && shrink.tiny && isMelee(event) && !ctx().isAbilityDamage()) {
            multiplier *= 1 + Math.max(0, params().num("giant_melee")) / 100.0;
        }
        if (multiplier != 1) {
            event.setDamage(event.getDamage() * multiplier);
        }
    }

    /** The hit landed: the nose grows a stage (once per interval). */
    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (!isOwnHit(event)) {
            return;
        }
        long now = ctx().currentTick();
        long interval = Math.max(1, params().ticks("nose_interval"));
        if (now - lastGain < interval || stage >= maxStage()) {
            return;
        }
        lastGain = now;
        stage++;
        Player owner = player();
        Location tip = noseTip(owner);
        float pitch = 0.7f + 0.2f * stage;
        Fx.sound(tip, Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, pitch);
        Fx.sound(tip, Sound.BLOCK_BAMBOO_HIT, 0.5f, 1.4f + Fx.jitter(0.1f));
        if (!hiddenOwner(owner)) {
            Vfx.cloud(ctx(), tip, Particle.DUST, 4, 0.08, 0, RED_DUST, false);
            Vfx.dot(tip, Particle.DUST_COLOR_TRANSITION, GOLD_GLOW, false);
        }
        boolean full = stage >= maxStage();
        if (full) {
            Fx.sound(tip, Sound.ENTITY_PLAYER_LEVELUP, 0.4f, 1.6f);
            if (!hiddenOwner(owner)) {
                Vfx.cloud(ctx(), tip, Particle.WAX_OFF, 6, 0.15, 0.02, null, false);
            }
        }
        owner.sendActionBar(Text.mm(noseBar(full)));
    }

    private String noseBar(boolean full) {
        StringBuilder bar = new StringBuilder("<" + RED_TAG + "><b>콧대</b></" + RED_TAG + "> ");
        for (int i = 1; i <= maxStage(); i++) {
            bar.append(i <= stage ? "<" + RED_TAG + ">▲</" + RED_TAG + ">" : "<dark_gray>△</dark_gray>");
        }
        double bonus = stage * Math.max(0, params().num("nose_bonus"));
        bar.append(" <gray>피해 +").append(AbilityInfo.formatNumber(bonus)).append("%</gray>");
        if (full) {
            bar.append(" <" + GOLD_TAG + "><b>하늘을 찌르는 콧대!</b></" + GOLD_TAG + ">");
        }
        return bar.toString();
    }

    /** An enemy's blow breaks the nose: back to the start, and a full nose staggers. */
    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled() || !(event instanceof EntityDamageByEntityEvent byEntity)
                || event.getFinalDamage() <= 0) {
            return;
        }
        Player owner = player();
        Entity damager = byEntity.getDamager();
        Player attacker = ctx().resolveAttacker(damager);
        boolean enemy = attacker != null ? ctx().isEnemy(owner, attacker) : ctx().isEnemy(owner, damager);
        if (!enemy || stage <= 0) {
            return;
        }
        breakNose(owner);
    }

    private void breakNose(Player owner) {
        boolean full = stage >= maxStage();
        brokenFrom = stage;
        stage = 0;
        breakStart = ctx().currentTick();
        Location tip = noseTip(owner);
        Fx.sound(tip, Sound.BLOCK_BAMBOO_BREAK, 1.0f, 0.9f + Fx.jitter(0.1f));
        Fx.sound(tip, Sound.ENTITY_PLAYER_HURT_SWEET_BERRY_BUSH, 0.4f, 1.6f);
        if (!hiddenOwner(owner)) {
            Vfx.cloud(ctx(), tip, Particle.DUST, 6, 0.12, 0, RED_DUST, false);
            Vfx.cloud(ctx(), tip, Particle.CRIT, 4, 0.1, 0.1, null, false);
        }
        if (full) {
            Fx.sound(tip, Sound.ITEM_SHIELD_BREAK, 0.8f, 1.3f);
            long ticks = Math.max(1, params().ticks("snap_stagger"));
            double slow = Math.clamp(params().num("snap_slow") / 100.0, 0.0, 1.0);
            if (stagger != null) {
                ctx().removeModifier(owner, stagger);
            }
            stagger = ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, -slow, Operation.MULTIPLY_SCALAR_1,
                    ticks);
            if (!hiddenOwner(owner)) {
                Vfx.ring(ctx(), owner.getLocation().add(0, owner.getHeight() + 0.3, 0), 0.35, 8, 0, Particle.DUST,
                        BLACK_DUST, false);
            }
            owner.sendActionBar(Text.mm("<" + RED_TAG + "><b>콧대가 꺾였다!</b></" + RED_TAG + "> <gray>휘청…</gray>"));
        } else {
            owner.sendActionBar(Text.mm("<" + RED_TAG + "><b>콧대</b></" + RED_TAG + "> <gray>꺾여서 처음부터</gray>"));
        }
    }

    /**
     * The nose and cap ride the owner and turn with its head every tick (over a client's head lerp); the nose bends
     * down and snaps back after a break.
     */
    private void poseNose(Player owner, long now) {
        BlockDisplay shown = nose;
        BlockDisplay hat = cap;
        if (shown == null || hat == null) {
            return;
        }
        if (!shown.isValid() || !shown.getWorld().equals(owner.getWorld())) {
            return;
        }
        ride(owner, shown);
        ride(owner, hat);
        if (hiddenOwner(owner)) {
            Displays.animate(shown, Displays.scaled(0.001f), 1);
            Displays.animate(hat, Displays.scaled(0.001f), 1);
            return;
        }
        long sinceBreak = now - breakStart;
        float droop = 0;
        int length = stage;
        if (sinceBreak >= 0 && sinceBreak < BREAK_TICKS) {
            droop = 70f * (float) sinceBreak / BREAK_TICKS;
            length = brokenFrom;
        }
        Displays.animate(shown, nosePose(owner, length, droop), HEAD_LERP);
        Displays.animate(hat, capPose(owner), HEAD_LERP);
    }

    /** The head's rotation: yaw then pitch (yaw 0 faces +Z, positive pitch looks down). */
    private static Quaternionf headTurn(Player owner) {
        Location at = owner.getLocation();
        return new Quaternionf().rotateY((float) Math.toRadians(-at.getYaw()))
                .rotateX((float) Math.toRadians(at.getPitch()));
    }

    /**
     * The head's pivot, the neck, relative to where the worn pieces ride (the top of the hitbox). Standing or
     * crouching, a player's head turns about it: a nose turned about the eyes would sink into the face looking down
     * and float in front of it looking up.
     */
    private static Vector3f neck(Player owner) {
        float scale = (float) Body.scaleOf(owner);
        return new Vector3f(0, (float) (owner.getEyeHeight() - owner.getHeight()) - NECK_BELOW_EYES * scale, 0);
    }

    /** The nose for {@code length} stages, bent down by {@code droop} degrees: a red box sticking out of the face. */
    private Transformation nosePose(Player owner, int length, float droop) {
        float scale = (float) Body.scaleOf(owner);
        float size = (NOSE_BASE + NOSE_STEP * Math.max(0, length)) * scale;
        Quaternionf turn = headTurn(owner);
        Quaternionf rotation = new Quaternionf(turn).rotateX((float) Math.toRadians(droop));
        Vector3f root = neck(owner).add(turn.transform(new Vector3f(0, NOSE_UP * scale, FACE_FRONT * scale)));
        Vector3f center = new Vector3f(root).add(new Quaternionf(rotation).transform(new Vector3f(0, 0, size / 2)));
        float width = NOSE_WIDTH * scale * (1 + 0.08f * Math.max(0, length));
        return box(center, new Vector3f(width, width, Math.max(0.01f, size)), rotation);
    }

    /** The little black cap (tokin) on the forehead. */
    private static Transformation capPose(Player owner) {
        float scale = (float) Body.scaleOf(owner);
        Quaternionf turn = headTurn(owner);
        Vector3f center = neck(owner).add(turn.transform(new Vector3f(0, CAP_UP * scale, CAP_FRONT * scale)));
        return box(center, new Vector3f(0.16f * scale, 0.13f * scale, 0.16f * scale),
                new Quaternionf(turn).rotateY((float) Math.toRadians(45)));
    }

    /** Where the nose tip is in the world (for its particles and sounds). */
    private Location noseTip(Player owner) {
        float scale = (float) Body.scaleOf(owner);
        float size = (NOSE_BASE + NOSE_STEP * stage) * scale;
        Vector3f local = neck(owner).add(0, (float) owner.getHeight(), 0).add(headTurn(owner).transform(
                new Vector3f(0, NOSE_UP * scale, FACE_FRONT * scale + size)));
        return owner.getLocation().add(local.x, local.y, local.z);
    }

    /** True if the tengu itself cannot be seen (invisibility): its nose must not give it away. */
    private static boolean hiddenOwner(Player owner) {
        return owner.isInvisible() || owner.hasPotionEffect(PotionEffectType.INVISIBILITY);
    }

    /** Whoever cannot see the tengu (stealth) does not see its nose either. */
    private void syncNoseVisibility(Player owner) {
        BlockDisplay shown = nose;
        BlockDisplay hat = cap;
        if (shown == null || hat == null) {
            return;
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(owner)) {
                continue;
            }
            boolean hide = !viewer.canSee(owner);
            UUID id = viewer.getUniqueId();
            if (hide && noseHiddenFrom.add(id)) {
                viewer.hideEntity(ctx().plugin(), shown);
                viewer.hideEntity(ctx().plugin(), hat);
            } else if (!hide && noseHiddenFrom.remove(id)) {
                viewer.showEntity(ctx().plugin(), shown);
                viewer.showEntity(ctx().plugin(), hat);
            }
        }
    }

    // ------------------------------------------------------------------ active: the magic fan

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        boolean white = owner.isSneaking();
        float yaw = owner.getLocation().getYaw();
        if (white) {
            List<Player> targets = new ArrayList<>();
            targets.add(owner);
            targets.addAll(alliesInCone(owner, yaw, params().num("fan_range"), params().num("fan_angle")));
            swingFan(owner, yaw, true, false, () -> whiteFan(owner, targets));
        } else {
            swingFan(owner, yaw, false, false, () -> redFan(owner, yaw));
        }
        return ActivationResult.success();
    }

    /** The red side: the enemies in front take a little damage and shrink. */
    private void redFan(Player owner, float yaw) {
        double range = Math.max(1, params().num("fan_range"));
        List<LivingEntity> hit = enemiesInCone(owner, yaw, range, params().num("fan_angle"));
        double factor = Math.clamp(params().num("shrink_scale"), 0.1, 1.0);
        for (LivingEntity enemy : hit) {
            Combat.hit(ctx(), owner, enemy, params().num("fan_damage"));
            if (!Combat.isTargetable(ctx(), owner, enemy)) {
                continue;
            }
            shrink(owner, enemy, factor, params().num("shrink_melee"), params().num("shrink_reach"),
                    1 + Math.max(0, params().num("shrink_knockback")) / 100.0, params().ticks("shrink_duration"),
                    false);
        }
        if (hit.isEmpty()) {
            owner.sendActionBar(Text.mm("<gray>부채 바람에 걸린 적이 없습니다</gray>"));
        }
    }

    /** The white side: the tengu and its allies in front grow (if there is room for them). */
    private void whiteFan(Player owner, List<Player> targets) {
        double factor = Math.max(1.0, params().num("grow_scale"));
        long ticks = Math.max(1, params().ticks("grow_duration"));
        int grew = 0;
        for (Player target : targets) {
            if (!target.isValid() || target.isDead() || !(target.equals(owner) || ctx().isAlly(owner, target))) {
                continue;
            }
            if (grow(owner, target, factor, ticks)) {
                grew++;
            }
        }
        if (grew == 0) {
            owner.sendActionBar(Text.mm("<gray>머리 위가 막혀 커질 자리가 없습니다</gray>"));
        }
    }

    /** The enemies in the fan facing {@code yaw} (fixed when the fan was raised: the telegraph shows the real cone). */
    private List<LivingEntity> enemiesInCone(Player owner, float yaw, double range, double angle) {
        List<LivingEntity> found = new ArrayList<>();
        Location eye = owner.getEyeLocation();
        for (LivingEntity enemy : ctx().enemiesNear(owner, eye, range)) {
            if (inCone(yaw, eye, enemy, range, angle)) {
                found.add(enemy);
            }
        }
        return found;
    }

    private List<Player> alliesInCone(Player owner, float yaw, double range, double angle) {
        List<Player> found = new ArrayList<>();
        Location eye = owner.getEyeLocation();
        for (Player ally : ctx().alliesNear(owner, eye, range)) {
            if (inCone(yaw, eye, ally, range, angle)) {
                found.add(ally);
            }
        }
        return found;
    }

    /** The horizontal direction of {@code yaw} (0 = +Z south, 90 = -X west). */
    private static Vector facing(float yaw) {
        double rad = Math.toRadians(yaw);
        return new Vector(-Math.sin(rad), 0, Math.cos(rad));
    }

    /** In the horizontal fan in front of the eyes, not too far above or below, and not behind a wall. */
    private static boolean inCone(float yaw, Location eye, LivingEntity target, double range, double angle) {
        Location body = Fx.body(target);
        Vector to = body.toVector().subtract(eye.toVector());
        if (Math.abs(to.getY()) > range * 0.6 + 1.5) {
            return false;
        }
        Vector flat = new Vector(to.getX(), 0, to.getZ());
        if (flat.lengthSquared() > 0.36) {
            double half = Math.toRadians(Math.clamp(angle, 1, 360) / 2);
            if (facing(yaw).angle(flat) > half) {
                return false;
            }
        }
        return Spots.clearPath(eye, body) || Spots.clearPath(eye, target.getEyeLocation());
    }

    /**
     * The fan sweeps across the front: telegraph arc, the feathers swing from right to left, at {@link #SWING_HIT}
     * the effect lands with a trail of leaves along the cone, then the fan folds away.
     */
    private void swingFan(Player owner, float yaw, boolean white, boolean big, Runnable effect) {
        Location feet = owner.getLocation();
        double range = big ? params().num("ult_range") : params().num("fan_range");
        double angle = big ? params().num("ult_angle") : params().num("fan_angle");
        Location chest = feet.clone().add(0, owner.getHeight() * 0.7, 0);
        // Telegraph: the edge of the cone glints for a moment.
        Vfx.arc(ctx(), chest.clone().subtract(0, owner.getHeight() * 0.35, 0), range, yaw, angle,
                (int) Math.round(range * 3), Particle.DUST, white ? WHITE_DUST : RED_DUST, big);
        Fx.sound(chest, Sound.ENTITY_PHANTOM_FLAP, 0.9f, big ? 0.6f : 1.0f + Fx.jitter(0.1f));
        Fx.sound(chest, Sound.BLOCK_BAMBOO_HIT, 0.7f, 1.2f + Fx.jitter(0.1f));
        if (big) {
            Fx.sound(chest, Sound.ENTITY_BREEZE_WIND_BURST, 1.0f, 0.7f);
            Fx.sound(chest, Sound.BLOCK_BELL_RESONATE, 0.6f, 1.6f);
        }

        int blades = big ? BIG_FAN_BLADES : FAN_BLADES;
        float size = big ? BIG_FAN : 1f;
        List<BlockDisplay> parts = new ArrayList<>();
        if (reserve(blades + 1)) {
            for (int i = 0; i <= blades; i++) {
                Material type = i == blades ? HANDLE : white ? (i % 2 == 0 ? WHITE_FEATHER : WHITE_FEATHER_SOFT)
                        : (i % 2 == 0 ? RED_FEATHER : RED_FEATHER_DARK);
                // Held in the hand, the fan rides the tengu: it never falls behind the body on the run.
                BlockDisplay part = keep(wear(owner, type, fanPose(owner, i, blades, yaw + SWING_ARC, size, 0.2f),
                        SWING_TICKS + 12, display -> hideFromThoseWhoCannotSee(owner, display)));
                parts.add(part);
            }
        }
        Loop.repeat(ctx(), owner, 1, 1, SWING_TICKS + 1, run -> {
            float t = Math.min(1f, (run + 1f) / SWING_TICKS);
            float sweep = yaw + SWING_ARC - 2 * SWING_ARC * t;
            float open = Math.min(1f, 0.2f + 0.8f * t * 2);
            for (int i = 0; i < parts.size(); i++) {
                BlockDisplay part = parts.get(i);
                ride(owner, part);
                Displays.animate(part, fanPose(owner, i, blades, sweep, size, open), 2);
            }
            if (run < SWING_TICKS) {
                sweepFx(owner, chest, yaw, sweep, range, white, big, run);
            }
            if (run == SWING_HIT) {
                effect.run();
            }
            return true;
        }, () -> {
            for (BlockDisplay part : parts) {
                if (part.isValid()) {
                    Displays.shrinkAway(ctx(), owner, part, 5);
                }
            }
        });
    }

    /**
     * Feather {@code index} of the fan (the last index is the bamboo handle), relative to where the fan rides (the top
     * of the tengu's hitbox): the fan is held at the right hand, its face turned to {@code fanYaw}, feathers fanned out
     * over {@link #BLADE_SPREAD} degrees each side (folded together at {@code open} 0).
     */
    private static Transformation fanPose(Player owner, int index, int blades, float fanYaw, float size, float open) {
        float scale = (float) Body.scaleOf(owner) * size;
        Quaternionf face = new Quaternionf().rotateY((float) Math.toRadians(-fanYaw))
                .rotateX((float) Math.toRadians(-15));
        Vector3f forward = face.transform(new Vector3f(0, 0, 0.55f * (float) Body.scaleOf(owner)));
        Vector3f side = new Quaternionf().rotateY((float) Math.toRadians(-owner.getLocation().getYaw()))
                .transform(new Vector3f(-0.3f * (float) Body.scaleOf(owner), 0, 0));
        Vector3f pivot = new Vector3f(0, (float) (owner.getHeight() * (0.62 - 1)), 0).add(forward).add(side);
        if (index == blades) {
            Vector3f center = new Vector3f(pivot).add(face.transform(new Vector3f(0, -0.2f * scale, 0)));
            return box(center, new Vector3f(0.06f * scale, 0.42f * scale, 0.06f * scale), face);
        }
        float spread = blades <= 1 ? 0 : -BLADE_SPREAD + 2 * BLADE_SPREAD * index / (blades - 1);
        Quaternionf blade = new Quaternionf(face).rotateZ((float) Math.toRadians(spread * open));
        float length = BLADE_LENGTH * scale * (index % 2 == 0 ? 1f : 0.88f);
        Vector3f center = new Vector3f(pivot).add(new Quaternionf(blade).transform(new Vector3f(0,
                length / 2 + 0.04f * scale, 0)));
        return box(center, new Vector3f(BLADE_WIDTH * scale, length, 0.025f * scale), blade);
    }

    /** The swing's wind: colored arcs and drifting leaves along the swept part of the cone. */
    private void sweepFx(Player owner, Location chest, float yaw, float sweep, double range, boolean white,
                         boolean big, long run) {
        Location low = chest.clone().subtract(0, owner.getHeight() * 0.25, 0);
        double width = big ? 30 : 22;
        for (double r = 1.5; r <= range; r += big ? 2.0 : 1.5) {
            Vfx.arc(ctx(), low.clone().add(0, Fx.jitter(0.15f), 0), r, sweep, width, (int) Math.round(r * 1.2),
                    Particle.DUST, white ? WHITE_DUST : (run % 2 == 0 ? RED_DUST : GOLD_DUST), big);
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Vector facing = facing(yaw);
        for (int i = 0; i < Vfx.n(ctx(), big ? 6 : 3); i++) {
            double r = 1 + random.nextDouble(range);
            Vector dir = facing.clone().rotateAroundY(Math.toRadians(-(sweep - yaw) + Fx.jitter(10f)));
            Location at = low.clone().add(dir.multiply(r)).add(0, random.nextDouble(1.2) - 0.4, 0);
            Vfx.dot(at, Particle.TINTED_LEAVES, white ? WHITE : (random.nextBoolean() ? RED : GOLD), big);
        }
        if (run == SWING_HIT) {
            Fx.sound(chest, Sound.ENTITY_BREEZE_SHOOT, 0.8f, white ? 1.6f : 1.3f + Fx.jitter(0.1f));
            Vfx.cloud(ctx(), chest.clone().add(facing.clone().multiply(1.2)), Particle.SMALL_GUST, big ? 4 : 2, 0.4,
                    0, null, big);
        }
    }

    // ------------------------------------------------------------------ size changes

    /** One enemy made small: its modifiers, until when, and how much more knockback it takes. */
    private final class Shrink {
        final LivingEntity enemy;
        final double factor;
        final double knockback;
        final boolean tiny;
        final long until;
        final long deadline;
        final List<NamespacedKey> keys = new ArrayList<>();

        Shrink(LivingEntity enemy, double factor, double knockback, boolean tiny, long until, long deadline) {
            this.enemy = enemy;
            this.factor = factor;
            this.knockback = knockback;
            this.tiny = tiny;
            this.until = until;
            this.deadline = deadline;
        }

        void strip() {
            for (NamespacedKey key : keys) {
                ctx().removeModifier(enemy, key);
            }
            keys.clear();
        }
    }

    /** One player made big: its modifiers (timed), removed early when its head hits a ceiling. */
    private final class Growth {
        final Player target;
        final long until;
        final List<NamespacedKey> keys = new ArrayList<>();

        Growth(Player target, long until) {
            this.target = target;
            this.until = until;
        }

        void strip() {
            for (NamespacedKey key : keys) {
                ctx().removeModifier(target, key);
            }
            keys.clear();
        }
    }

    /**
     * Shrinks {@code enemy} to {@code factor} for {@code ticks}: smaller, weaker blows, shorter reach, more knockback. A
     * newer shrink replaces an older one. The modifiers carry a fallback expiry {@code regrow_wait} later, so they end
     * even if the tengu falls while the enemy is stuck somewhere too small to grow back in.
     */
    private void shrink(Player owner, LivingEntity enemy, double factor, double melee, double reach,
                        double knockback, long ticks, boolean tiny) {
        Shrink old = shrunk.remove(enemy.getUniqueId());
        if (old != null) {
            old.strip();
        }
        long now = ctx().currentTick();
        long life = Math.max(1, ticks);
        long fallback = life + Math.max(0, params().ticks("regrow_wait")) + 1;
        Shrink shrink = new Shrink(enemy, factor, Math.max(1, knockback), tiny, now + life, now + fallback);
        shrink.keys.add(ctx().addModifier(owner, enemy, Attribute.SCALE, factor - 1, Operation.MULTIPLY_SCALAR_1,
                fallback));
        double weaker = Math.clamp(melee / 100.0, 0.0, 1.0);
        if (weaker > 0) {
            shrink.keys.add(ctx().addModifier(owner, enemy, Attribute.ATTACK_DAMAGE, -weaker,
                    Operation.MULTIPLY_SCALAR_1, fallback));
        }
        if (reach > 0) {
            shrink.keys.add(ctx().addModifier(owner, enemy, Attribute.ENTITY_INTERACTION_RANGE, -reach,
                    Operation.ADD_NUMBER, fallback));
        }
        shrunk.put(enemy.getUniqueId(), shrink);

        Location body = Fx.body(enemy);
        if (seen(owner, enemy)) {
            Fx.sound(body, Sound.ENTITY_PUFFER_FISH_BLOW_OUT, 0.9f, tiny ? 1.4f : 1.7f + Fx.jitter(0.1f));
            Fx.sound(body, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, 1.8f + Fx.jitter(0.1f));
            // A red spiral collapses into the shrinking body.
            Location feet = enemy.getLocation();
            Vfx.helix(ctx(), feet, 0.7, enemy.getHeight() / factor, 1.5, 2, 9, ThreadLocalRandom.current()
                    .nextDouble(TAU), Particle.DUST, RED_DUST, tiny);
            Vfx.cloud(ctx(), body, Particle.POOF, 4, 0.2, 0.02, null, false);
            Vfx.ring(ctx(), feet.clone().add(0, 0.1, 0), 0.5, 10, 0, Particle.DUST_COLOR_TRANSITION, RED_FADE, false);
        }
        if (enemy instanceof Player victim) {
            victim.sendActionBar(Text.mm("<" + RED_TAG + "><b>" + (tiny ? "소인이 되었다!" : "작아졌다!") + "</b></"
                    + RED_TAG + "> <gray>약해진 주먹, 짧아진 팔, 쉽게 날아가는 몸</gray>"));
        }
    }

    /** The shrunk enemies whose time is up grow back once there is room around them for their normal size. */
    private void tickShrinks(long now) {
        Iterator<Shrink> iterator = shrunk.values().iterator();
        while (iterator.hasNext()) {
            Shrink shrink = iterator.next();
            LivingEntity enemy = shrink.enemy;
            if (!enemy.isValid() || enemy.isDead() || now >= shrink.deadline) {
                iterator.remove();
                shrink.strip();
                continue;
            }
            if (now < shrink.until) {
                continue;
            }
            if (!fitsNormal(enemy, shrink.factor)) {
                continue; // a tunnel too low to grow back in: stays small a little longer
            }
            iterator.remove();
            shrink.strip();
            if (seen(player(), enemy)) {
                Vfx.cloud(ctx(), Fx.body(enemy), Particle.POOF, 3, 0.25, 0.02, null, false);
                Fx.sound(enemy.getLocation(), Sound.ENTITY_PUFFER_FISH_BLOW_UP, 0.6f, 1.5f + Fx.jitter(0.1f));
            }
        }
    }

    /** True if {@code entity} would fit where it stands at its size without a shrink of {@code factor}. */
    private static boolean fitsNormal(LivingEntity entity, double factor) {
        double grow = 1 / Math.max(0.05, factor);
        return Spots.isFree(entity.getWorld(), entity.getLocation(), entity.getWidth() * grow,
                entity.getHeight() * grow);
    }

    /** A little red ring circles over each shrunk enemy's head. */
    private void markShrunk(long tick) {
        Player owner = player();
        for (Shrink shrink : shrunk.values()) {
            LivingEntity enemy = shrink.enemy;
            if (!enemy.isValid() || !seen(owner, enemy) || enemy.getWorld() != owner.getWorld()) {
                continue;
            }
            Location head = enemy.getLocation().add(0, enemy.getHeight() + 0.25, 0);
            Vfx.ring(ctx(), head, 0.25, 6, tick * 0.3, Particle.DUST, SMALL_RED, false);
        }
    }

    /**
     * Makes {@code target} bigger by {@code factor} for {@code ticks}, if there is room over its head. Returns whether
     * it grew.
     */
    private boolean grow(Player owner, Player target, double factor, long ticks) {
        Location feet = target.getLocation();
        if (!Spots.isFree(target.getWorld(), feet, target.getWidth() * factor, target.getHeight() * factor)) {
            return false;
        }
        Growth old = grown.remove(target.getUniqueId());
        if (old != null) {
            old.strip();
        }
        Growth growth = new Growth(target, ctx().currentTick() + ticks);
        growth.keys.add(ctx().addModifier(owner, target, Attribute.SCALE, factor - 1, Operation.MULTIPLY_SCALAR_1,
                ticks));
        double stronger = Math.max(0, params().num("grow_melee")) / 100.0;
        if (stronger > 0) {
            growth.keys.add(ctx().addModifier(owner, target, Attribute.ATTACK_DAMAGE, stronger,
                    Operation.MULTIPLY_SCALAR_1, ticks));
        }
        double reach = Math.max(0, params().num("grow_reach"));
        if (reach > 0) {
            growth.keys.add(ctx().addModifier(owner, target, Attribute.ENTITY_INTERACTION_RANGE, reach,
                    Operation.ADD_NUMBER, ticks));
        }
        grown.put(target.getUniqueId(), growth);
        if (!concealed(target)) {
            Fx.sound(feet, Sound.BLOCK_BEACON_POWER_SELECT, 0.5f, 1.6f);
            Fx.sound(feet, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 0.8f + Fx.jitter(0.1f));
            Vfx.helix(ctx(), feet, 0.6, target.getHeight() * factor, 1.5, 2, 10, 0, Particle.DUST_COLOR_TRANSITION,
                    GOLD_GLOW, false);
            Vfx.cloud(ctx(), Fx.body(target), Particle.WAX_OFF, 6, 0.4, 0.02, null, false);
        }
        if (!target.equals(owner)) {
            target.sendActionBar(Text.mm("<white><b>텐구의 흰 부채</b></white> <gray>몸이 커졌습니다</gray>"));
        }
        return true;
    }

    /** Grown players whose head hits a ceiling go back to their size at once (no suffocating giants). */
    private void tickGrowths(long now) {
        Player owner = player();
        Iterator<Growth> iterator = grown.values().iterator();
        while (iterator.hasNext()) {
            Growth growth = iterator.next();
            Player target = growth.target;
            if (!target.isValid() || target.isDead() || now >= growth.until) {
                iterator.remove();
                continue;
            }
            boolean far = !target.getWorld().equals(owner.getWorld())
                    || target.getLocation().distanceSquared(owner.getLocation()) > GROWTH_REACH * GROWTH_REACH;
            if (!far && !Spots.isFree(target.getWorld(), target.getLocation(), target.getWidth(),
                    target.getHeight())) {
                iterator.remove();
                growth.strip();
                Fx.sound(target.getLocation(), Sound.ENTITY_PUFFER_FISH_BLOW_UP, 0.6f, 1.2f);
            }
        }
    }

    // ------------------------------------------------------------------ ultimate: the land of little people

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        // The cone is fixed when the fan goes up: what the telegraph shows is what the sweep hits.
        float yaw = owner.getLocation().getYaw();
        raiseBigFan(owner, yaw, () -> swingFan(owner, yaw, false, true, () -> bigFan(owner, yaw)));
        return ActivationResult.success();
    }

    /**
     * The telegraph: the big fan is raised for {@code ult_windup} while the cone it will sweep glows on the ground,
     * its edge closing in from the tengu and a gust gathering behind it; then {@code swing} runs.
     */
    private void raiseBigFan(Player owner, float yaw, Runnable swing) {
        long windup = Math.max(1, params().ticks("ult_windup"));
        double range = Math.max(1, params().num("ult_range"));
        double angle = Math.clamp(params().num("ult_angle"), 1, 360);
        Location start = owner.getLocation();
        Fx.sound(start, Sound.ENTITY_BREEZE_CHARGE, 1.0f, 0.6f);
        Fx.sound(start, Sound.BLOCK_BAMBOO_WOOD_PLACE, 0.8f, 0.7f);
        owner.sendActionBar(Text.mm("<" + RED_TAG + "><b>소인국</b></" + RED_TAG + "> <gray>큰 부채를 "
                + "치켜듭니다…</gray>"));
        Loop.repeat(ctx(), owner, 0, 1, windup, run -> {
            Location feet = owner.getLocation();
            Location ground = feet.clone().add(0, 0.12, 0);
            double progress = (run + 1.0) / windup;
            if (run % 2 == 0) {
                // The edge of the cone, its two sides, and a glow sweeping out from the tengu.
                Vfx.arc(ctx(), ground, range, yaw, angle, (int) Math.round(range * 2.5), Particle.DUST, RED_DUST,
                        true);
                for (double side : new double[] {-angle / 2, angle / 2}) {
                    Vector edge = facing(yaw).rotateAroundY(Math.toRadians(-side)).multiply(range);
                    Vfx.line(ctx(), ground.clone().add(edge.clone().multiply(0.12)), ground.clone().add(edge), 0.8,
                            Particle.DUST, GOLD_DUST, true);
                }
                Vfx.arc(ctx(), ground, Math.max(0.8, range * progress), yaw, angle, (int) Math.round(range * 1.5),
                        Particle.DUST_COLOR_TRANSITION, RED_FADE, true);
            }
            // A gust gathers behind the raised fan.
            Location back = feet.clone().add(0, owner.getHeight() * 1.1, 0).add(facing(yaw).multiply(-0.4));
            Vfx.dot(back.add(Fx.jitter(0.5f), Fx.jitter(0.4f), Fx.jitter(0.5f)), Particle.TINTED_LEAVES,
                    run % 2 == 0 ? RED : GOLD, true);
            if (run % 4 == 0) {
                Fx.sound(feet, Sound.ENTITY_PHANTOM_FLAP, 0.8f, 0.5f + (float) progress * 0.6f);
            }
            return true;
        }, swing);
    }

    private void bigFan(Player owner, float yaw) {
        long ticks = Math.max(1, params().ticks("ult_duration"));
        double factor = Math.clamp(params().num("tiny_scale"), 0.1, 1.0);
        List<LivingEntity> hit = enemiesInCone(owner, yaw, Math.max(1, params().num("ult_range")),
                params().num("ult_angle"));
        for (LivingEntity enemy : hit) {
            shrink(owner, enemy, factor, params().num("tiny_melee"), params().num("tiny_reach"),
                    Math.max(1, params().num("tiny_knockback")), ticks, true);
        }
        // The land of little people: the bonus against them and the stomps last the whole time, giant or not.
        long now = ctx().currentTick();
        ultUntil = now + ticks;
        int token = ++ultToken;
        airTicks = 0;
        // The giant's stomps belong to the ultimate: they run in its own task.
        Loop.repeat(ctx(), owner, 1, 1, ticks + 1, run -> {
            tickGiant(owner, ctx().currentTick());
            return token == ultToken && ultActive();
        });
        // The tengu grows into a giant over them (if there is room).
        double giantFactor = Math.max(1.0, params().num("giant_scale"));
        Location feet = owner.getLocation();
        if (giant != null) {
            giant.strip();
            giant = null;
        }
        if (Spots.isFree(owner.getWorld(), feet, owner.getWidth() * giantFactor, owner.getHeight() * giantFactor)) {
            Growth growth = new Growth(owner, now + ticks);
            growth.keys.add(ctx().addModifier(owner, owner, Attribute.SCALE, giantFactor - 1,
                    Operation.MULTIPLY_SCALAR_1, ticks));
            giant = growth;
            Fx.sound(feet, Sound.ENTITY_RAVAGER_ROAR, 0.7f, 1.3f);
            Fx.sound(feet, Sound.BLOCK_BELL_USE, 0.8f, 0.6f);
            Vfx.helix(ctx(), feet, 1.0, owner.getHeight() * giantFactor, 2, 3, 14, 0, Particle.DUST, RED_DUST, true);
            Vfx.cloud(ctx(), feet.clone().add(0, 1, 0), Particle.TINTED_LEAVES, 16, 1.0, 0.05, RED, true);
            Vfx.cloud(ctx(), feet.clone().add(0, 1, 0), Particle.TINTED_LEAVES, 10, 1.0, 0.05, GOLD, true);
        }
        owner.sendActionBar(Text.mm("<" + RED_TAG + "><b>소인국</b></" + RED_TAG + "> <white>" + hit.size()
                + "</white><gray>명을 소인으로 만들었습니다 · " + (giant == null ? "머리 위가 막혀 거인은 되지 "
                + "못했지만 " : "") + "뛰었다 착지해 짓밟으세요</gray>"));
    }

    private boolean ultActive() {
        return ctx().currentTick() < ultUntil;
    }

    /**
     * The land of little people: the giant's size breaks off under a low ceiling (the tengu goes on stomping at its
     * own size), and every landing near little people stomps them.
     */
    private void tickGiant(Player owner, long now) {
        if (now >= ultUntil || !owner.isValid()) {
            giant = null;
            return;
        }
        Growth growth = giant;
        if (growth != null && !Spots.isFree(owner.getWorld(), owner.getLocation(), owner.getWidth(),
                owner.getHeight())) {
            growth.strip();
            giant = null;
            owner.sendActionBar(Text.mm("<gray>머리 위가 막혀 원래 크기로 돌아왔습니다 · 짓밟기는 계속됩니다</gray>"));
        }
        boolean grounded = Motion.isGrounded(owner) || owner.isInWater();
        if (!grounded) {
            airTicks++;
            return;
        }
        if (airTicks >= MIN_AIR_TICKS) {
            stomp(owner, now);
        } else if (giant != null && now % GIANT_STEP_PERIOD == 0) {
            // The giant's footsteps shake the ground as it walks.
            Location feet = owner.getLocation();
            Location last = lastStep;
            lastStep = feet;
            if (last != null && last.getWorld().equals(feet.getWorld()) && Math.hypot(feet.getX() - last.getX(),
                    feet.getZ() - last.getZ()) > GIANT_STEP_MIN) {
                Fx.sound(feet, Sound.ENTITY_RAVAGER_STEP, 0.5f, 0.9f + Fx.jitter(0.1f));
                Block below = feet.clone().subtract(0, 0.2, 0).getBlock();
                if (below.getType().isSolid()) {
                    Vfx.cloud(ctx(), feet.clone().add(0, 0.1, 0), Particle.BLOCK, 4, 0.3, 0.05, below.getBlockData(),
                            false);
                }
            }
        }
        airTicks = 0;
    }

    private void stomp(Player owner, long now) {
        Location feet = owner.getLocation();
        double radius = Math.max(0.5, params().num("stomp_radius"));
        long cooldown = Math.max(1, params().ticks("stomp_cooldown"));
        int crushed = 0;
        for (LivingEntity enemy : ctx().enemiesNear(owner, feet, radius + 1.5)) {
            Shrink shrink = shrunk.get(enemy.getUniqueId());
            if (shrink == null || !shrink.tiny || now >= shrink.until) {
                continue;
            }
            Location at = enemy.getLocation();
            double dx = at.getX() - feet.getX();
            double dz = at.getZ() - feet.getZ();
            if (dx * dx + dz * dz > radius * radius || Math.abs(at.getY() - feet.getY()) > STOMP_HEIGHT) {
                continue;
            }
            Long last = stomped.get(enemy.getUniqueId());
            if (last != null && now - last < cooldown) {
                continue;
            }
            stomped.put(enemy.getUniqueId(), now);
            Combat.hit(ctx(), owner, enemy, params().num("stomp_damage"));
            if (Combat.isTargetable(ctx(), owner, enemy)) {
                Vector away = Motion.horizontal(new Vector(dx, 0, dz)).multiply(STOMP_PUSH);
                Motion.push(enemy, away.setY(STOMP_LIFT), MAX_SPEED);
            }
            crushed++;
            if (seen(owner, enemy)) {
                Vfx.burst(ctx(), at.clone().add(0, 0.2, 0), PALETTE, 0.4, Particle.CRIT, false);
            }
        }
        // The ground shakes either way: a ring cracks out from the feet.
        Location ground = feet.clone().add(0, 0.1, 0);
        Vfx.shockwave(ctx(), owner, ground, 0.3, radius + 0.5, 5, Particle.DUST, crushed > 0 ? RED_DUST : BLACK_DUST,
                crushed > 0);
        Block below = owner.getLocation().subtract(0, 0.2, 0).getBlock();
        if (!below.isEmpty() && below.getType().isSolid()) {
            Vfx.cloud(ctx(), ground, Particle.BLOCK, 10, radius * 0.4, 0.1, below.getBlockData(), false);
        }
        Fx.sound(feet, Sound.ENTITY_RAVAGER_STEP, 1.0f, 0.7f + Fx.jitter(0.1f));
        if (crushed > 0) {
            Fx.sound(feet, Sound.ENTITY_IRON_GOLEM_ATTACK, 0.9f, 0.8f + Fx.jitter(0.1f));
            Fx.sound(feet, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.7f, 0.7f);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** True unless {@code entity} is a player hidden from the owner (its visuals would give it away). */
    private static boolean seen(Player owner, Entity entity) {
        return !(entity instanceof Player player) || owner.canSee(player);
    }

    /**
     * True if {@code player} is hiding from someone: invisible, or hidden from any other player in its world
     * (stealth). Particles and sounds are seen and heard by everyone near them, so none may be made on such a player.
     */
    private static boolean concealed(Player player) {
        if (hiddenOwner(player)) {
            return true;
        }
        for (Player viewer : player.getWorld().getPlayers()) {
            if (!viewer.equals(player) && !viewer.canSee(player)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Hides a display of the tengu's from every viewer that cannot see the tengu (stealth or invisibility). Called
     * inside the display's spawn, so those viewers never receive it.
     */
    private void hideFromThoseWhoCannotSee(Player owner, Display display) {
        boolean invisible = hiddenOwner(owner);
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.equals(owner) && (invisible || !viewer.canSee(owner))) {
                viewer.hideEntity(ctx().plugin(), display);
            }
        }
    }

    /** A box of {@code size} centered on {@code center} (relative to the display), turned by {@code rotation}. */
    private static Transformation box(Vector3f center, Vector3f size, Quaternionf rotation) {
        Vector3f translation = new Vector3f(center).sub(new Quaternionf(rotation).transform(new Vector3f(size)
                .mul(0.5f)));
        return new Transformation(translation, new Quaternionf(rotation), new Vector3f(size), new Quaternionf());
    }

    private boolean reserve(int count) {
        live.removeIf(display -> !display.isValid());
        return live.size() + count <= MAX_DISPLAYS;
    }

    private <T extends Display> T keep(T display) {
        live.add(display);
        return display;
    }

    /** The current nose stage (for tests and the HUD). */
    public int noseStage() {
        return stage;
    }

    /** Whether {@code entity} is shrunk by this tengu right now. */
    public boolean isShrunk(Entity entity) {
        Shrink shrink = shrunk.get(entity.getUniqueId());
        return shrink != null && ctx().currentTick() < shrink.until;
    }

}
