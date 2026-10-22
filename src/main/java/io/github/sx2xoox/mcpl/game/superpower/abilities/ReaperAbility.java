package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.HashSet;
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
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 사신 — the grim reaper. Strikes harder at enemies close to death (and sees them by the soul flame over their
 * heads), sweeps a huge scythe around itself to harvest life from everyone nearby, and as the ultimate carves a mark of
 * death into an enemy that, when the knell ends, falls as one blow scaled by everything the victim suffered meanwhile.
 *
 * <p>Look: black and the cyan of souls. A scythe (a giant netherite hoe display) sweeps a full circle around the
 * reaper, trailing a cyan arc, and each victim bleeds a stream of soul motes back into the reaper. The mark is a
 * scythe hanging over the victim's head with a countdown that also shows the blow it is building up; souls are drawn
 * into it whenever the victim is hurt, a death knell tolls every second, and at the end the scythe swings down
 * through the victim into a black X outlined in cyan. Executions flash a thin black slash with a cyan glow.
 */
public final class ReaperAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("reaper", "사신", Material.NETHERITE_HOE)
            .color(TextColor.color(0x4FE3D6))
            .difficulty(2)
            .passive("처형",
                    "체력이 최대 체력의 {execute_threshold}% 이하인 적에게 주는 모든 피해가 {execute_bonus}% 증가합니다. "
                            + "그런 적의 머리 위에는 나에게만 보이는 영혼불이 깜박입니다.")
            .active("낫 회전",
                    "거대한 낫을 크게 한 바퀴 휘둘러 주변 {spin_radius}블록의 적에게 {spin_damage} 피해를 주고 "
                            + "나에게로 살짝 끌어당기며, 입힌 피해의 {spin_lifesteal}%만큼 체력을 회복합니다.",
                    9)
            .ultimate("사신의 표식",
                    "조준한 적({mark_range}블록 이내)에게 {mark_duration}초간 죽음의 표식을 새깁니다. 표식이 끝나면 "
                            + "그동안 대상이 받은 피해의 {mark_ratio}%에 {mark_base}를 더한 피해(최대 {mark_cap})를 한 번에 "
                            + "줍니다. 그 전에 대상이 쓰러지면 영혼을 거둬 체력을 {reap_heal} 회복합니다.")
            .param("execute_threshold", 30)
            .param("execute_bonus", 30)
            .param("spin_radius", 3.5)
            .param("spin_damage", 4)
            .param("spin_lifesteal", 50)
            .param("mark_range", 20)
            .param("mark_duration", 4)
            .param("mark_base", 3)
            .param("mark_ratio", 60)
            .param("mark_cap", 12)
            .param("reap_heal", 6)
            .build();

    private static final String SOUL_TAG = "#4FE3D6";
    private static final double TAU = Math.PI * 2;

    // ------------------------------------------------------------------ look
    /** Pale soul glow, soul cyan, and the black of the reaper's robe. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xD9FFFB, 0x4FE3D6, 0x0B0F14);
    private static final Particle.DustTransition SOUL_GLOW = PALETTE.glow(1.0f);
    private static final Particle.DustTransition SOUL_FADE = PALETTE.fade(1.1f);
    private static final Particle.DustOptions ROBE_DUST = new Particle.DustOptions(Color.fromRGB(0x0B0F14), 1.2f);

    /** Block and item types of the visuals; block data and items are created on use (they need a running server). */
    private static final Material SCYTHE_ITEM = Material.NETHERITE_HOE;
    private static final Material SLASH_BLOCK = Material.BLACK_CONCRETE;

    /** Caps every velocity this ability applies. */
    private static final double MAX_SPEED = 1.5;

    // passive
    private static final long SENSE_PERIOD = 10;
    private static final double SENSE_RANGE = 24;
    private static final long EXECUTE_FX_COOLDOWN = 6;

    // active: scythe spin
    private static final int SPIN_TICKS = 8;
    private static final float SCYTHE_SCALE = 2.6f;
    /** Distance from the reaper's chest to the middle of the scythe sprite. */
    private static final float SCYTHE_REACH = 2.0f;
    private static final double CHEST = 0.62;
    /** Enemies closer than this are not pulled any further (they are already at the reaper's feet). */
    private static final double PULL_MIN_DISTANCE = 1.6;
    private static final double PULL_SPEED = 0.45;
    private static final double PULL_LIFT = 0.18;
    private static final int SWEEP_ARC_POINTS = 10;
    private static final int SPIN_AFTERGLOW_TICKS = 12;

    // ultimate: mark of death
    private static final double MARK_ABOVE = 0.9;
    private static final float MARK_SCYTHE_SCALE = 1.3f;
    private static final float MARK_SCYTHE_SPIN = 6;
    private static final long KNELL_PERIOD = 20;
    private static final int SWING_TICKS = 3;
    private static final float SLASH_LENGTH = 2.6f;
    private static final float SLASH_WIDTH = 0.09f;
    private static final long SLASH_HOLD_TICKS = 6;
    private static final int SLASH_FADE_TICKS = 5;

    private long lastExecuteFx = Long.MIN_VALUE / 2; // "/ 2": now - this must not overflow

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    // ------------------------------------------------------------------ passive: execution

    /** True when {@code victim} is at or below the execution threshold of its maximum health. */
    private boolean isDoomed(LivingEntity victim) {
        double threshold = Math.clamp(params().num("execute_threshold") / 100.0, 0.0, 1.0);
        return victim.getHealth() <= Combat.maxHealth(victim) * threshold;
    }

    /** Any hit of the reaper's on a doomed enemy hits harder; the victim may still cancel it afterwards. */
    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled() || !ctx().isEnemy(player(), victim) || !isDoomed(victim)) {
            return;
        }
        double bonus = Math.max(0, params().num("execute_bonus")) / 100.0;
        if (bonus > 0) {
            event.setDamage(event.getDamage() * (1 + bonus));
        }
    }

    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        long now = ctx().currentTick();
        if (!isDoomed(victim) || now - lastExecuteFx < EXECUTE_FX_COOLDOWN) {
            return;
        }
        lastExecuteFx = now;
        executeFx(victim);
    }

    /** An execution: a thin black slash glowing cyan across the victim, and its soul wavering out of the body. */
    private void executeFx(LivingEntity victim) {
        Player owner = player();
        Location body = Fx.body(victim);
        Vector toVictim = body.toVector().subtract(owner.getEyeLocation().toVector());
        slash(body, toVictim, 1.6f, 35 + Fx.jitter(15), 0);
        Vfx.cloud(ctx(), body, Particle.SOUL, 4, 0.25, 0.03, null, false);
        Vfx.cloud(ctx(), body, Particle.DUST_COLOR_TRANSITION, 8, 0.3, 0, SOUL_GLOW, false);
        Fx.sound(body, Sound.PARTICLE_SOUL_ESCAPE, 1.0f, 0.8f + Fx.jitter(0.1f));
        Fx.sound(body, Sound.ITEM_TRIDENT_HIT, 0.6f, 0.6f + Fx.jitter(0.06f));
        owner.sendActionBar(Text.mm("<" + SOUL_TAG + ">☠ 처형</" + SOUL_TAG + "> <gray>+"
                + AbilityInfo.formatNumber(params().num("execute_bonus")) + "%</gray>"));
    }

    /** The reaper senses the doomed: a soul flame flickers over their heads, shown to the reaper alone. */
    @Override
    public void onTick(long tick) {
        if (tick % SENSE_PERIOD != 0) {
            return;
        }
        Player owner = player();
        for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), SENSE_RANGE)) {
            if (!isDoomed(enemy) || enemy instanceof Player hidden && !owner.canSee(hidden)) {
                continue;
            }
            Location head = enemy.getLocation().add(0, enemy.getHeight() + 0.45, 0);
            owner.spawnParticle(Particle.SOUL_FIRE_FLAME, head, 2, 0.06, 0.08, 0.06, 0.005);
            if (tick % (SENSE_PERIOD * 3) == 0) {
                owner.spawnParticle(Particle.SOUL, head.add(0, 0.2, 0), 1, 0.05, 0.05, 0.05, 0.01);
            }
        }
    }

    /** A kill: the victim's soul rises out of it and flies into the reaper. */
    @Override
    public void onKill(Player victim) {
        Player owner = player();
        Location from = victim.getLocation().add(0, 1.0, 0);
        Location to = owner.getLocation().add(0, owner.getHeight() * CHEST, 0);
        if (!from.getWorld().equals(to.getWorld())) {
            return;
        }
        Vfx.cloud(ctx(), from, Particle.SOUL, 8, 0.3, 0.05, null, false);
        for (int i = 0; i < Vfx.n(ctx(), 4); i++) {
            Vfx.trail(from.clone().add(0, 0.3 * i, 0), to, i % 2 == 0 ? PALETTE.core() : PALETTE.body(), 14 + 2 * i,
                    false);
        }
        Fx.sound(from, Sound.PARTICLE_SOUL_ESCAPE, 1.2f, 0.6f);
        Fx.sound(to, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.5f, 1.7f);
    }

    // ------------------------------------------------------------------ active: scythe spin

    @Override
    public ActivationResult onActive() {
        new Spin(player()).start();
        return ActivationResult.success();
    }

    /**
     * One full sweep of the scythe around the reaper over {@link #SPIN_TICKS} ticks, starting in front and turning
     * right. Each enemy in reach (and in sight) is cut once, when the blade passes it.
     */
    private final class Spin implements LongPredicate {

        private final Player owner;
        private final float startYaw;
        private final double radius;
        private final Set<UUID> hit = new HashSet<>();
        private @Nullable ItemDisplay scythe;
        private double healed;

        Spin(Player owner) {
            this.owner = owner;
            this.startYaw = owner.getLocation().getYaw();
            this.radius = Math.max(1, params().num("spin_radius"));
        }

        void start() {
            Location chest = chest(startYaw);
            ItemDisplay display = Displays.item(ctx(), owner, chest, new ItemStack(SCYTHE_ITEM), scythePose(0.3f),
                    SPIN_TICKS + 12L);
            display.setTeleportDuration(1);
            Displays.animateNextTick(ctx(), owner, display, scythePose(SCYTHE_SCALE), 2);
            scythe = display;
            // The wind-up: shadows pooling at the feet and a low whistle of the blade.
            Location feet = owner.getLocation().add(0, 0.08, 0);
            Vfx.ring(ctx(), feet, 0.8, 12, Math.toRadians(startYaw), Particle.DUST, ROBE_DUST, false);
            Vfx.cloud(ctx(), chest, Particle.SOUL, 4, 0.3, 0.02, null, false);
            Fx.sound(chest, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.5f + Fx.jitter(0.05f));
            Fx.sound(chest, Sound.ITEM_TRIDENT_THROW, 0.8f, 0.55f + Fx.jitter(0.05f));
            Fx.sound(chest, Sound.ENTITY_WITHER_SKELETON_AMBIENT, 0.5f, 0.6f + Fx.jitter(0.05f));
            Loop.repeat(ctx(), owner, 0, 1, SPIN_TICKS, this, this::finish);
        }

        @Override
        public boolean test(long tick) {
            double swept = 360.0 * (tick + 1) / SPIN_TICKS;
            float yaw = (float) (startYaw + swept);
            ItemDisplay display = scythe;
            if (display != null && display.isValid()) {
                display.teleport(chest(yaw));
            }
            cut(swept);
            drawSweep(yaw, tick);
            return true;
        }

        private Location chest(float yaw) {
            Location at = owner.getLocation().add(0, owner.getHeight() * CHEST, 0);
            at.setYaw(yaw);
            at.setPitch(0);
            return at;
        }

        /** Cuts every enemy in reach the blade has swept past by now. */
        private void cut(double swept) {
            Location center = owner.getLocation().add(0, owner.getHeight() * CHEST, 0);
            double damage = params().num("spin_damage");
            double lifesteal = Math.max(0, params().num("spin_lifesteal")) / 100.0;
            for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius)) {
                if (hit.contains(enemy.getUniqueId())) {
                    continue;
                }
                Vector to = enemy.getLocation().toVector().subtract(owner.getLocation().toVector());
                double yawTo = Math.toDegrees(Math.atan2(-to.getX(), to.getZ()));
                double behind = Math.floorMod(Math.round(yawTo - startYaw), 360);
                if (behind > swept + 1 && to.clone().setY(0).lengthSquared() > 0.25) {
                    continue;
                }
                Location body = Fx.body(enemy);
                if (!Spots.clearPath(center, body)) {
                    continue;
                }
                hit.add(enemy.getUniqueId());
                double before = enemy.getHealth() + enemy.getAbsorptionAmount();
                Combat.hit(ctx(), owner, enemy, damage);
                double after = enemy.isDead() ? 0 : enemy.getHealth() + enemy.getAbsorptionAmount();
                double dealt = Math.max(0, before - after);
                if (dealt > 0 && lifesteal > 0 && owner.isValid() && !owner.isDead()) {
                    ctx().heal(owner, dealt * lifesteal);
                    healed += dealt * lifesteal;
                    drain(body);
                }
                cutFx(body);
                if (Combat.isTargetable(ctx(), owner, enemy) && to.length() > PULL_MIN_DISTANCE) {
                    Vector pull = Motion.horizontal(to).multiply(-PULL_SPEED).setY(PULL_LIFT);
                    Motion.push(enemy, pull, MAX_SPEED);
                }
            }
        }

        /** The blade's wake: a cyan arc trailing the scythe, soul wisps and a sweep flash at its tip. */
        private void drawSweep(float yaw, long tick) {
            Location center = owner.getLocation().add(0, owner.getHeight() * CHEST, 0);
            double reach = radius - 0.3;
            Vfx.arc(ctx(), center, reach, yaw - 22, 48, SWEEP_ARC_POINTS, Particle.DUST_COLOR_TRANSITION, SOUL_GLOW,
                    false);
            Vfx.arc(ctx(), center.clone().subtract(0, 0.15, 0), reach * 0.7, yaw - 30, 40, SWEEP_ARC_POINTS / 2,
                    Particle.DUST, ROBE_DUST, false);
            double angle = Math.toRadians(yaw);
            Location tip = center.clone().add(-Math.sin(angle) * reach, 0, Math.cos(angle) * reach);
            if (tick % 2 == 0) {
                Vfx.dot(tip, Particle.SWEEP_ATTACK, null, false);
                Fx.sound(tip, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.5f, 0.7f + 0.08f * tick + Fx.jitter(0.05f));
            }
            Vfx.dot(tip.add(0, 0.2, 0), Particle.SOUL, null, false);
        }

        /** A cut: cyan sparks and a dark puff on the victim. */
        private void cutFx(Location body) {
            Vfx.burst(ctx(), body, PALETTE, 0.5, Particle.SOUL, false);
            Vfx.cloud(ctx(), body, Particle.DUST, 6, 0.25, 0, ROBE_DUST, false);
            Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.8f, 0.7f + Fx.jitter(0.08f));
            Fx.sound(body, Sound.PARTICLE_SOUL_ESCAPE, 0.9f, 1.0f + Fx.jitter(0.15f));
        }

        /** The stolen life streams back into the reaper as soul motes. */
        private void drain(Location body) {
            Location chest = owner.getLocation().add(0, owner.getHeight() * CHEST, 0);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < Vfx.n(ctx(), 4); i++) {
                Location from = body.clone().add(random.nextDouble(-0.25, 0.25), random.nextDouble(-0.4, 0.4),
                        random.nextDouble(-0.25, 0.25));
                Vfx.trail(from, chest, i % 2 == 0 ? PALETTE.core() : PALETTE.body(), 8 + random.nextInt(5), false);
            }
        }

        /** The scythe vanishes into the shadows; a cyan ring settles on the ground where it swept. */
        private void finish() {
            ItemDisplay display = scythe;
            if (display != null && display.isValid()) {
                Displays.shrinkAway(ctx(), owner, display, 4);
            }
            Location feet = owner.getLocation().add(0, 0.1, 0);
            Vfx.ring(ctx(), feet, radius, (int) Math.max(16, radius * 7), 0, Particle.DUST_COLOR_TRANSITION, SOUL_FADE,
                    false);
            Loop.repeat(ctx(), owner, 2, 2, SPIN_AFTERGLOW_TICKS / 2, run -> {
                for (int i = 0; i < Vfx.n(ctx(), 3); i++) {
                    Location at = Vfx.scatter(feet, radius);
                    at.getWorld().spawnParticle(Particle.SOUL, at, 0, 0, 0.05, 0, 1);
                }
                return true;
            });
            if (!hit.isEmpty()) {
                owner.sendActionBar(Text.mm("<" + SOUL_TAG + ">☠ 낫 회전</" + SOUL_TAG + "> <white>" + hit.size()
                        + "명</white><dark_gray> · </dark_gray><gray>회복</gray> <white>+"
                        + AbilityInfo.formatNumber(Math.round(healed * 10) / 10.0) + "</white>"));
            }
        }
    }

    /**
     * The scythe lying flat at chest height, its handle pointing away from the reaper and the blade leading the turn:
     * the hoe sprite is turned so its handle runs along +Y, laid down onto +Z and pushed out by
     * {@link #SCYTHE_REACH}. The display's yaw then swings it around.
     */
    private static Transformation scythePose(float scale) {
        return Displays.centered(0, 0, SCYTHE_REACH * scale / SCYTHE_SCALE, scale, scale, scale, 0, 90, 45);
    }

    // ------------------------------------------------------------------ ultimate: mark of death

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        LivingEntity target = ctx().findTarget(owner, params().num("mark_range"));
        if (target == null || target instanceof Player hidden && !owner.canSee(hidden)) {
            return ActivationResult.fail("조준한 곳에 적이 없습니다");
        }
        new Mark(owner, target).start();
        return ActivationResult.success();
    }

    /**
     * The mark of death: for its duration it adds up every drop in the victim's health (and absorption) from any
     * source, then deals {@code mark_base + mark_ratio% × that}, capped. If the victim falls first, the reaper reaps
     * its soul instead.
     */
    private final class Mark implements LongPredicate {

        private final Player owner;
        private final LivingEntity target;
        private final long duration;
        private double lastHealth;
        private double suffered;
        private @Nullable ItemDisplay scythe;
        private @Nullable TextDisplay label;
        private float spin;
        private boolean hidden;

        Mark(Player owner, LivingEntity target) {
            this.owner = owner;
            this.target = target;
            this.duration = Math.max(1, params().ticks("mark_duration"));
            this.lastHealth = target.getHealth() + target.getAbsorptionAmount();
        }

        void start() {
            Location over = over();
            ItemDisplay display = Displays.item(ctx(), owner, over, new ItemStack(SCYTHE_ITEM), hangingPose(0.1f),
                    duration + SWING_TICKS + 12);
            display.setTeleportDuration(2);
            display.setViewRange(2.0f);
            Displays.animateNextTick(ctx(), owner, display, hangingPose(MARK_SCYTHE_SCALE), 6);
            scythe = display;
            TextDisplay text = Displays.text(ctx(), owner, over.clone().add(0, 0.95, 0), labelText(duration), 0.8f,
                    duration + 4);
            text.setTeleportDuration(2);
            text.setViewRange(2.0f);
            label = text;

            // The reaper points its scythe: a thread of souls to the victim, and the first toll of the knell.
            Location from = owner.getEyeLocation().add(owner.getEyeLocation().getDirection().multiply(0.6));
            Location body = Fx.body(target);
            Vfx.line(ctx(), from, body, 3, Particle.DUST_COLOR_TRANSITION, SOUL_GLOW, true);
            for (int i = 0; i < Vfx.n(ctx(), 4); i++) {
                Vfx.trail(from, body, i % 2 == 0 ? PALETTE.core() : PALETTE.body(), 6 + i, true);
            }
            Vfx.cloud(ctx(), body, Particle.SOUL, 12, 0.35, 0.04, null, true);
            Vfx.shockwave(ctx(), owner, target.getLocation().add(0, 0.1, 0), 1.6, 0.6, 5,
                    Particle.DUST_COLOR_TRANSITION, SOUL_GLOW, true);
            Fx.sound(body, Sound.BLOCK_BELL_USE, 1.2f, 0.5f);
            Fx.sound(body, Sound.BLOCK_BELL_RESONATE, 0.7f, 0.6f);
            Fx.sound(owner.getLocation(), Sound.ENTITY_WITHER_AMBIENT, 0.6f, 0.5f + Fx.jitter(0.05f));
            Fx.sound(body, Sound.PARTICLE_SOUL_ESCAPE, 1.2f, 0.7f);
            owner.sendActionBar(Text.mm("<" + SOUL_TAG + "><b>☠ 사신의 표식</b></" + SOUL_TAG + "> <gray>"
                    + AbilityInfo.formatNumber(duration / 20.0) + "초 뒤 심판</gray>"));
            Loop.repeat(ctx(), owner, 1, 1, duration, this, this::detonate);
        }

        @Override
        public boolean test(long tick) {
            if (!Combat.isTargetable(ctx(), owner, target)) {
                if (target.isDead() || !target.isValid() || target.getHealth() <= 0
                        || target instanceof Player victim && !ctx().isAlive(victim)) {
                    reap();
                } else {
                    fizzle();
                }
                return false;
            }
            double now = target.getHealth() + target.getAbsorptionAmount();
            if (now < lastHealth - 1.0E-9) {
                suffered += lastHealth - now;
                feed();
            }
            lastHealth = now;
            follow(tick);
            if (tick % KNELL_PERIOD == KNELL_PERIOD - 1) {
                knell(tick);
            }
            return true;
        }

        /** The blow the mark would deal now. */
        private double blow() {
            double ratio = Math.max(0, params().num("mark_ratio")) / 100.0;
            double raw = Math.max(0, params().num("mark_base")) + suffered * ratio;
            return Math.min(Math.max(0, params().num("mark_cap")), raw);
        }

        private Location over() {
            Location at = target.getLocation().add(0, target.getHeight() + MARK_ABOVE, 0);
            at.setPitch(0);
            at.setYaw(spin);
            return at;
        }

        /** The scythe and the countdown hover over the victim; hidden while the victim is hidden from the reaper. */
        private void follow(long tick) {
            boolean nowHidden = target instanceof Player victim && !owner.canSee(victim);
            spin += MARK_SCYTHE_SPIN;
            Location over = over();
            ItemDisplay display = scythe;
            if (display != null && display.isValid()) {
                display.teleport(over);
            }
            TextDisplay text = label;
            if (text != null && text.isValid()) {
                text.teleport(over.clone().add(0, 0.95, 0));
                if (tick % 2 == 0) {
                    text.text(labelText(duration - tick - 1));
                }
            }
            if (nowHidden != hidden) {
                hidden = nowHidden;
                float range = hidden ? 0f : 2.0f;
                if (display != null && display.isValid()) {
                    display.setViewRange(range);
                }
                if (text != null && text.isValid()) {
                    text.setViewRange(range);
                }
            }
            if (!hidden && tick % 3 == 0) {
                Location feet = target.getLocation().add(0, 0.08, 0);
                Vfx.ring(ctx(), feet, 0.75, 10, tick * 0.2, Particle.DUST_COLOR_TRANSITION, SOUL_FADE, false);
                Vfx.dot(Vfx.scatter(feet, 0.6), Particle.SOUL_FIRE_FLAME, null, false);
            }
        }

        private Component labelText(long ticksLeft) {
            String blow = AbilityInfo.formatNumber(Math.round(blow() * 10) / 10.0);
            String color = ticksLeft <= 20 ? "#FF5A6E" : SOUL_TAG;
            return Text.mm("<b><" + color + ">☠ " + Fx.seconds(ticksLeft) + "</" + color + "></b>\n<gray>심판</gray> <"
                    + SOUL_TAG + ">" + blow + "</" + SOUL_TAG + ">");
        }

        /** The victim was hurt: its pain is drawn up into the hanging scythe. */
        private void feed() {
            if (hidden) {
                return;
            }
            Location body = Fx.body(target);
            Location over = over();
            for (int i = 0; i < Vfx.n(ctx(), 3); i++) {
                Vfx.trail(body.clone().add(0, -0.3 + 0.3 * i, 0), over, i == 1 ? PALETTE.core() : PALETTE.body(), 8,
                        false);
            }
            Fx.sound(body, Sound.PARTICLE_SOUL_ESCAPE, 0.6f, 1.3f + Fx.jitter(0.15f));
        }

        /** The death knell: a deeper toll and a ring of soul fire every second. */
        private void knell(long tick) {
            Location body = Fx.body(target);
            float progress = (float) Math.min(1.0, (double) (tick + 1) / duration);
            Fx.sound(body, Sound.BLOCK_BELL_USE, 1.0f, 0.5f + 0.25f * progress);
            Fx.sound(body, Sound.BLOCK_BELL_RESONATE, 0.4f, 0.6f + 0.2f * progress);
            if (!hidden) {
                Vfx.shockwave(ctx(), owner, target.getLocation().add(0, 0.1, 0), 0.4, 1.4, 4,
                        Particle.DUST_COLOR_TRANSITION, SOUL_GLOW, true);
            }
        }

        /** The knell ends: the scythe swings down through the victim and the mark is paid in full. */
        private void detonate() {
            if (!Combat.isTargetable(ctx(), owner, target)) {
                return;
            }
            double blow = blow();
            Location body = Fx.body(target);
            ItemDisplay display = scythe;
            if (display != null && display.isValid()) {
                Location swingAt = body.clone();
                swingAt.setYaw(spin);
                swingAt.setPitch(0);
                display.teleport(swingAt);
                Displays.animate(display, Displays.centered(0, 0, 0, MARK_SCYTHE_SCALE * 1.4f,
                        MARK_SCYTHE_SCALE * 1.4f, MARK_SCYTHE_SCALE * 1.4f, 0, 0, -35), SWING_TICKS);
                ctx().runLater(owner, SWING_TICKS + 2, () -> {
                    if (display.isValid()) {
                        Displays.shrinkAway(ctx(), owner, display, 4);
                    }
                });
            }
            TextDisplay text = label;
            if (text != null && text.isValid()) {
                text.remove();
            }
            Combat.hit(ctx(), owner, target, blow);
            boolean killed = target.isDead() || target.getHealth() <= 0;

            Vector toVictim = body.toVector().subtract(owner.getEyeLocation().toVector());
            slash(body, toVictim, SLASH_LENGTH, 40, 0);
            slash(body, toVictim, SLASH_LENGTH, -40, 1);
            Vfx.dot(body, Particle.FLASH, PALETTE.core(), true);
            Vfx.sphere(ctx(), body, 1.0, 30, Particle.DUST_COLOR_TRANSITION, SOUL_GLOW, true);
            Vfx.cloud(ctx(), body, Particle.SOUL, 16, 0.4, 0.06, null, true);
            Vfx.cloud(ctx(), body, Particle.SCULK_SOUL, 8, 0.3, 0.05, null, true);
            Vfx.shockwave(ctx(), owner, target.getLocation().add(0, 0.1, 0), 0.4, 2.6, 5, Particle.DUST, ROBE_DUST,
                    true);
            Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.2f, 0.5f);
            Fx.sound(body, Sound.BLOCK_BELL_USE, 1.4f, 1.0f);
            Fx.sound(body, Sound.ITEM_TRIDENT_THUNDER, 0.5f, 1.6f + Fx.jitter(0.05f));
            Fx.sound(body, Sound.PARTICLE_SOUL_ESCAPE, 1.5f, 0.5f);
            if (killed) {
                TextDisplay verdict = Displays.text(ctx(), owner, body.clone().add(0, 1.4, 0),
                        Text.mm("<b><" + SOUL_TAG + ">처형</" + SOUL_TAG + "></b>"), 0.6f, 24);
                Displays.animateNextTick(ctx(), owner, verdict, Displays.centered(0, 0.4f, 0, 1.3f, 1.3f, 1.3f, 0, 0,
                        0), 5);
            }
            owner.sendActionBar(Text.mm("<" + SOUL_TAG + ">☠ 사신의 표식</" + SOUL_TAG + "> <white>"
                    + AbilityInfo.formatNumber(Math.round(blow * 10) / 10.0) + " 피해</white>"
                    + (killed ? " <" + SOUL_TAG + "><b>처형!</b></" + SOUL_TAG + ">" : "")));
        }

        /** The victim fell before the knell ended: its soul rises and flows into the reaper. */
        private void reap() {
            removeMark();
            Location from = target.getLocation().add(0, 1.0, 0);
            double heal = Math.max(0, params().num("reap_heal"));
            if (heal > 0 && owner.isValid() && !owner.isDead()) {
                ctx().heal(owner, heal);
            }
            if (!from.getWorld().equals(owner.getWorld())) {
                return;
            }
            Location chest = owner.getLocation().add(0, owner.getHeight() * CHEST, 0);
            Vfx.helix(ctx(), from.clone().subtract(0, 0.9, 0), 0.4, 2.2, 1.5, 2, 10, 0, Particle.DUST_COLOR_TRANSITION,
                    SOUL_GLOW, true);
            for (int i = 0; i < Vfx.n(ctx(), 5); i++) {
                Vfx.trail(from.clone().add(0, 0.25 * i, 0), chest, i % 2 == 0 ? PALETTE.core() : PALETTE.body(),
                        12 + 2 * i, true);
            }
            Fx.sound(from, Sound.PARTICLE_SOUL_ESCAPE, 1.5f, 0.6f);
            Fx.sound(chest, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.7f, 1.6f);
            owner.sendActionBar(Text.mm("<" + SOUL_TAG + ">☠ 영혼 수확</" + SOUL_TAG + "> <gray>체력</gray> <white>+"
                    + AbilityInfo.formatNumber(heal) + "</white>"));
        }

        /** The victim slipped away (left the match): the mark simply fades. */
        private void fizzle() {
            removeMark();
        }

        private void removeMark() {
            ItemDisplay display = scythe;
            if (display != null && display.isValid()) {
                Displays.shrinkAway(ctx(), owner, display, 4);
            }
            TextDisplay text = label;
            if (text != null && text.isValid()) {
                text.remove();
            }
        }
    }

    /** The scythe hanging over the victim, handle up and blade down, turning slowly with the display's yaw. */
    private static Transformation hangingPose(float scale) {
        return Displays.centered(0, 0, 0, scale, scale, scale, 0, 0, -135);
    }

    // ------------------------------------------------------------------ visuals

    /**
     * A thin black slash outlined in cyan across {@code at}, turned to face along {@code facing} (horizontal part)
     * and tilted by {@code rollDegrees}; it flashes, then shrinks away.
     */
    private void slash(Location at, Vector facing, float length, float rollDegrees, long delay) {
        Player owner = player();
        Vector flat = Motion.horizontal(facing);
        Location anchor = at.clone();
        if (flat.lengthSquared() > 1.0E-6) {
            anchor.setDirection(flat);
        }
        anchor.setPitch(0);
        ctx().runLater(owner, Math.max(1, delay), () -> {
            if (!anchor.getWorld().equals(owner.getWorld())) {
                return;
            }
            BlockDisplay bar = Displays.block(ctx(), owner, anchor, SLASH_BLOCK.createBlockData(),
                    Displays.transform(0, 0, 0, length * 0.2f, SLASH_WIDTH, SLASH_WIDTH, 0, 0, rollDegrees),
                    SLASH_HOLD_TICKS + SLASH_FADE_TICKS + 4);
            Displays.outline(bar, PALETTE.body());
            Displays.animateNextTick(ctx(), owner, bar, Displays.transform(0, 0, 0, length, SLASH_WIDTH, SLASH_WIDTH,
                    0, 0, rollDegrees), 2);
            ctx().runLater(owner, SLASH_HOLD_TICKS, () -> {
                if (bar.isValid()) {
                    Displays.shrinkAway(ctx(), owner, bar, SLASH_FADE_TICKS);
                }
            });
        });
    }
}
