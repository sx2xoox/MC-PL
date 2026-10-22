package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

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
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 치유사 — sustain and support. Slowly regenerates, bursts healing light over itself and nearby allies (cleansing
 * harmful potion effects and fire), and raises a sanctuary that heals allies inside and throws enemies out.
 *
 * <p>Visuals: healing is a golden halo rising over the body with hearts and motes, and a golden thread from the
 * healer to every ally it reaches. The sanctuary unfolds as a glowing ring of light bars on the ground with a central
 * beam and four slowly breathing light pillars; enemies thrown out flash against a pane of light.
 */
public final class HealerAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("healer", "치유사", Material.GLISTERING_MELON_SLICE)
            .color(TextColor.color(0xFFD866))
            .difficulty(1)
            .passive("재생의 기운", "{regen_interval}초마다 체력을 {regen_amount} 회복합니다.")
            .active("치유의 빛",
                    "나와 반경 {light_radius}블록 안의 아군 체력을 {light_heal} 회복하고 해로운 포션 효과와 불을 "
                            + "없앱니다. 치유할 대상이 없으면 쓰이지 않습니다.",
                    12)
            .ultimate("성역",
                    "{sanctum_duration}초간 반경 {sanctum_radius}블록의 성역을 펼칩니다. 안에 있는 나와 아군은 "
                            + "{sanctum_interval}초마다 {sanctum_heal} 회복하고, 들어온 적은 밀려나며 {sanctum_weakness}초간 "
                            + "나약함에 걸립니다.")
            .param("regen_interval", 3)
            .param("regen_amount", 1)
            .param("light_radius", 8)
            .param("light_heal", 6)
            .param("sanctum_duration", 6)
            .param("sanctum_radius", 6)
            .param("sanctum_heal", 1)
            .param("sanctum_interval", 0.5)
            .param("sanctum_weakness", 3)
            .build();

    /** Ticks the light ring of the active takes to expand to its full radius. */
    private static final long LIGHT_RING_TICKS = 6;

    /** Horizontal speed an enemy is thrown out of the sanctuary with (more when deeper inside). */
    private static final double SANCTUM_PUSH = 0.8;
    private static final double SANCTUM_LIFT = 0.3;
    /** Ticks before the same enemy can be pushed again, so the push is not re-applied every tick. */
    private static final long PUSH_COOLDOWN = 6;
    private static final double MAX_SPEED = 2.5;

    // ------------------------------------------------------------------ visuals

    /** Warm white core, soft gold body, gentle green edge. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFFDE7, 0xFFF59D, 0xA5D6A7);
    private static final Color GOLD = Color.fromRGB(0xFFD866);
    private static final Vfx.Palette GOLDEN = new Vfx.Palette(PALETTE.core(), GOLD, PALETTE.edge());

    private static final float BEAM_HEIGHT = 5f;
    private static final float BEAM_WIDTH = 0.22f;
    private static final int BEAM_GROW_TICKS = 8;
    /** The sanctuary's ground ring: this many light bars, unfolding outwards and turning slowly. */
    private static final int RING_BARS = 16;
    private static final float RING_BAR_WIDTH = 0.12f;
    private static final float RING_BAR_HEIGHT = 0.04f;
    private static final int RING_UNFOLD_TICKS = 8;
    /** Light pillars standing on the ring, breathing between two heights. */
    private static final int PILLARS = 4;
    private static final float PILLAR_WIDTH = 0.12f;
    private static final float PILLAR_LOW = 1.7f;
    private static final float PILLAR_HIGH = 2.7f;
    /** The pane of light an enemy is thrown against. */
    private static final int PANE_TICKS = 7;
    /** A rising halo takes this many ticks to climb over a healed body. */
    private static final long HALO_TICKS = 8;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    // ------------------------------------------------------------------ passive: regeneration

    @Override
    public void onTick(long tick) {
        long every = params().ticks("regen_interval");
        if (every <= 0 || tick % every != 0) {
            return;
        }
        Player self = player();
        if (self.getHealth() >= Combat.maxHealth(self) - 0.01) {
            return;
        }
        ctx().heal(self, params().num("regen_amount"));
        // A gentle spiral of gold-to-green motes winding up the body.
        Vfx.helix(ctx(), self.getLocation().add(0, 0.1, 0), 0.45, 1.6, 1.0, 1, 7,
                ThreadLocalRandom.current().nextDouble(Math.PI * 2), Particle.DUST_COLOR_TRANSITION,
                PALETTE.fade(0.7f), false);
        Vfx.dot(self.getLocation().add(0, 1.9, 0), Particle.HAPPY_VILLAGER, null, false);
    }

    // ------------------------------------------------------------------ active: healing light

    @Override
    public ActivationResult onActive() {
        Location origin = player().getLocation();
        double radius = Math.max(0, params().num("light_radius"));
        List<LivingEntity> targets = new ArrayList<>();
        targets.add(player());
        targets.addAll(ctx().alliesNear(player(), origin, radius));
        List<LivingEntity> needy = targets.stream().filter(HealerAbility::needsCare).toList();
        if (needy.isEmpty()) {
            return ActivationResult.fail("치유할 대상이 없습니다");
        }

        double amount = params().num("light_heal");
        for (LivingEntity target : needy) {
            ctx().heal(target, amount);
            boolean cleansed = cleanse(target);
            bless(target, cleansed);
        }

        lightBurst(origin, radius);
        Fx.sound(origin, Sound.BLOCK_BEACON_POWER_SELECT, 1.0f, 1.6f + Fx.jitter(0.05f));
        Fx.sound(origin, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.4f, 1.2f + Fx.jitter(0.1f));
        Fx.sound(origin, Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.8f + Fx.jitter(0.05f));

        int allies = needy.contains(player()) ? needy.size() - 1 : needy.size();
        player().sendActionBar(Text.mm(allies > 0
                ? "<#FFD866>✚ 치유의 빛 · 아군 " + allies + "명 치유</#FFD866>"
                : "<#FFD866>✚ 치유의 빛</#FFD866>"));
        return ActivationResult.success();
    }

    /**
     * The light bursting from the healer: a flash, a column of light shooting up and fading, and a golden ring
     * racing over the ground to the edge of the light's reach.
     */
    private void lightBurst(Location origin, double radius) {
        World world = origin.getWorld();
        Location chest = origin.clone().add(0, 1.1, 0);
        world.spawnParticle(Particle.FLASH, chest, 1, 0, 0, 0, 0, GOLD, false);
        Vfx.cloud(ctx(), chest, Particle.END_ROD, 14, 0.3, 0.06, null, false);

        Location base = origin.clone();
        base.setYaw(0);
        base.setPitch(0);
        BlockDisplay column = Displays.block(ctx(), player(), base, Material.YELLOW_STAINED_GLASS.createBlockData(),
                pillar(0, 0, 0.5f, 0.01f), 16);
        Displays.animateNextTick(ctx(), player(), column, pillar(0, 0, 0.5f, 4.0f), 3);
        ctx().runLater(player(), 5, () -> {
            if (column.isValid()) {
                Displays.animate(column, pillar(0, 0, 0.02f, 5.0f), 8);
            }
        });

        Location ground = origin.clone().add(0, 0.15, 0);
        Vfx.shockwave(ctx(), player(), ground, 0.4, Math.max(0.5, radius), LIGHT_RING_TICKS,
                Particle.DUST_COLOR_TRANSITION, GOLDEN.glow(1.2f), false);
        ctx().runLater(player(), LIGHT_RING_TICKS, () -> Vfx.ring(ctx(), ground.clone().add(0, 0.2, 0),
                Math.max(0.5, radius), (int) Math.max(12, Math.min(40, radius * 5)), 0, Particle.END_ROD, null, false));
    }

    private static boolean needsCare(LivingEntity target) {
        if (target.getHealth() < Combat.maxHealth(target) - 0.01 || target.getFireTicks() > 0) {
            return true;
        }
        for (PotionEffect effect : target.getActivePotionEffects()) {
            if (isHarmful(effect)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHarmful(PotionEffect effect) {
        return effect.getType().getEffectCategory() == PotionEffectType.Category.HARMFUL;
    }

    /**
     * Removes harmful potion effects and fire. A freeze overlay is only cleared when no ability debuff holds the
     * target: an ability's freeze is an attribute modifier the light cannot lift, so its overlay must stay visible.
     *
     * @return whether anything was removed
     */
    private boolean cleanse(LivingEntity target) {
        boolean cleansed = false;
        for (PotionEffect effect : List.copyOf(target.getActivePotionEffects())) {
            if (isHarmful(effect)) {
                target.removePotionEffect(effect.getType());
                cleansed = true;
            }
        }
        if (target.getFireTicks() > 0) {
            target.setFireTicks(0);
            cleansed = true;
        }
        if (target.getFreezeTicks() > 0 && !isHeldByAbility(target)) {
            target.setFreezeTicks(0);
            cleansed = true;
        }
        return cleansed;
    }

    /** True while a match-applied negative movement speed or jump strength modifier (a freeze, a root) is active. */
    private boolean isHeldByAbility(LivingEntity target) {
        String namespace = new NamespacedKey(ctx().plugin(), "probe").getNamespace();
        for (Attribute attribute : List.of(Attribute.MOVEMENT_SPEED, Attribute.JUMP_STRENGTH)) {
            AttributeInstance instance = target.getAttribute(attribute);
            if (instance == null) {
                continue;
            }
            for (AttributeModifier modifier : instance.getModifiers()) {
                if (modifier.getAmount() < 0 && namespace.equals(modifier.getKey().getNamespace())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * A healed body: a golden halo climbing from the feet over the head, motes rising, hearts on top and, for allies,
     * a golden thread from the healer. A cleansed body also sheds a puff of pale smoke.
     */
    private void bless(LivingEntity target, boolean cleansed) {
        World world = target.getWorld();
        Location feet = target.getLocation();
        double height = target.getHeight();
        double phase = ThreadLocalRandom.current().nextDouble(Math.PI);
        Loop.repeat(ctx(), player(), 0, 1, HALO_TICKS, t -> {
            if (!target.isValid()) {
                return false;
            }
            double rise = (double) (t + 1) / HALO_TICKS;
            Location at = target.getLocation().add(0, 0.1 + (height + 0.3) * rise, 0);
            Vfx.ring(ctx(), at, 0.55 - 0.15 * rise, 10, phase + t * 0.4, Particle.DUST_COLOR_TRANSITION,
                    GOLDEN.glow(0.8f), false);
            return true;
        }, () -> {
            if (target.isValid()) {
                Vfx.cloud(ctx(), target.getLocation().add(0, height + 0.45, 0), Particle.HEART, 3, 0.3, 0, null,
                        false);
            }
        });
        int motes = Vfx.n(ctx(), 6);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < motes; i++) {
            Location at = feet.clone().add(random.nextGaussian() * 0.3, random.nextDouble(height), random.nextGaussian() * 0.3);
            // count 0: the offsets are a direction, so the mote drifts straight up
            world.spawnParticle(Particle.END_ROD, at.getX(), at.getY(), at.getZ(), 0, 0, 1, 0, 0.06, null, false);
        }
        if (cleansed) {
            Vfx.cloud(ctx(), Fx.body(target), Particle.WHITE_SMOKE, 6, 0.3, 0.04, null, false);
            Fx.sound(Fx.body(target), Sound.BLOCK_FIRE_EXTINGUISH, 0.4f, 1.6f + Fx.jitter(0.1f));
        }
        if (!target.equals(player()) && world.equals(player().getWorld())) {
            Location from = player().getLocation().add(0, player().getHeight() * 0.6, 0);
            Location body = Fx.body(target);
            Vfx.trail(from, body, PALETTE.core(), 10, false);
            Vfx.trail(from, body, GOLD, 12, false);
            Vfx.trail(from, body, PALETTE.edge(), 14, false);
            Fx.sound(body, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.3f + Fx.jitter(0.1f));
        }
    }

    // ------------------------------------------------------------------ ultimate: sanctuary

    @Override
    public ActivationResult onUltimate() {
        Location center = ground(player().getLocation());
        double radius = Math.max(1, params().num("sanctum_radius"));
        long duration = Math.max(1, params().ticks("sanctum_duration"));
        long healEvery = Math.max(1, params().ticks("sanctum_interval"));
        double heal = params().num("sanctum_heal");
        int weaknessTicks = (int) Math.min(Integer.MAX_VALUE, params().ticks("sanctum_weakness"));

        Sanctum sanctum = new Sanctum(center, radius, duration);
        Fx.sound(center, Sound.BLOCK_BEACON_ACTIVATE, 1.5f, 1.3f);
        Fx.sound(center, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.2f, 0.8f + Fx.jitter(0.05f));
        Fx.sound(center, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 1.0f, 1.4f + Fx.jitter(0.05f));
        center.getWorld().spawnParticle(Particle.FLASH, center.clone().add(0, 1, 0), 1, 0, 0, 0, 0, GOLD, true);

        Map<UUID, Long> lastPush = new HashMap<>();
        long[] nextHeal = {0};
        Loop.repeat(ctx(), player(), 0, 1, duration, t -> {
            if (t % 2 == 0) {
                for (LivingEntity enemy : ctx().enemiesNear(player(), center, radius)) {
                    Long last = lastPush.get(enemy.getUniqueId());
                    if (last == null || t - last >= PUSH_COOLDOWN) {
                        lastPush.put(enemy.getUniqueId(), t);
                        repel(center, radius, enemy, weaknessTicks);
                    }
                }
            }
            if (t >= nextHeal[0]) {
                nextHeal[0] = t + healEvery;
                pulse(center, radius, heal);
            }
            sanctum.tick(t, duration - t);
            return true;
        }, sanctum::close);
        return ActivationResult.success();
    }

    /** Heals the owner (when inside) and every ally inside the sanctuary, each along a thread from the beam. */
    private void pulse(Location center, double radius, double amount) {
        List<LivingEntity> inside = new ArrayList<>(ctx().alliesNear(player(), center, radius));
        Player self = player();
        if (self.getWorld().equals(center.getWorld()) && distanceToBox(center, self.getBoundingBox()) <= radius) {
            inside.add(self);
        }
        Location beamTop = center.clone().add(0, BEAM_HEIGHT * 0.8, 0);
        for (LivingEntity target : inside) {
            if (target.getHealth() >= Combat.maxHealth(target) - 0.01) {
                continue;
            }
            ctx().heal(target, amount);
            Vfx.trail(beamTop, Fx.body(target), GOLD, 8, false);
            Vfx.dot(target.getLocation().add(0, target.getHeight() + 0.3, 0), Particle.HEART, null, false);
        }
        Fx.sound(center, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, 1.5f + Fx.jitter(0.15f));
    }

    /** Throws an enemy out of the sanctuary (harder the deeper it got) and weakens it. */
    private void repel(Location center, double radius, LivingEntity enemy, int weaknessTicks) {
        Vector out = enemy.getLocation().toVector().subtract(center.toVector()).setY(0);
        double distance = out.length();
        if (distance < 0.05) {
            double angle = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
            out = new Vector(Math.cos(angle), 0, Math.sin(angle));
        } else {
            out.multiply(1 / distance);
        }
        Vector facing = out.clone();
        double factor = Motion.knockbackFactor(enemy);
        if (factor > 0.05) {
            double depth = 1 - Math.min(1, distance / radius);
            out.multiply(SANCTUM_PUSH * (0.6 + 0.8 * depth) * factor).setY(SANCTUM_LIFT * factor);
            enemy.setVelocity(Motion.clamp(out, MAX_SPEED));
        }
        if (weaknessTicks > 0) {
            enemy.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, weaknessTicks, 0, false, true, true));
        }
        barrierFlash(enemy, facing);
        Location body = Fx.body(enemy);
        Vfx.cloud(ctx(), body, Particle.END_ROD, 6, 0.2, 0.08, null, false);
        Vfx.cloud(ctx(), body, Particle.DUST_COLOR_TRANSITION, 6, 0.3, 0, GOLDEN.glow(1.0f), false);
        Fx.sound(body, Sound.ITEM_SHIELD_BLOCK, 0.7f, 1.5f + Fx.jitter(0.1f));
        Fx.sound(body, Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.8f, 1.2f + Fx.jitter(0.1f));
    }

    /** A pane of light flashing up where an enemy is thrown out, facing the sanctuary's center. */
    private void barrierFlash(LivingEntity enemy, Vector outwards) {
        Location at = enemy.getLocation();
        at.setDirection(outwards);
        at.setPitch(0);
        float width = (float) Math.max(1.0, enemy.getWidth() * 2.0);
        float height = (float) Math.max(1.4, enemy.getHeight() * 1.1);
        BlockDisplay pane = Displays.block(ctx(), player(), at, Material.YELLOW_STAINED_GLASS.createBlockData(),
                Displays.transform(0, height * 0.5f, -0.1f, width * 0.5f, height * 0.5f, 0.05f, 0, 0, 0),
                PANE_TICKS + 2);
        Displays.animateNextTick(ctx(), player(), pane,
                Displays.transform(0, height * 0.5f, -0.1f, width, height, 0.05f, 0, 0, 0), 2);
        ctx().runLater(player(), 3, () -> {
            if (pane.isValid()) {
                Displays.shrinkAway(ctx(), player(), pane, PANE_TICKS - 3);
            }
        });
    }

    /**
     * The sanctuary's displays: a ring of light bars that unfolds over the ground and turns slowly, a central beam,
     * pillars of light breathing on the ring and a countdown label; plus the particle motes drifting inside.
     */
    private final class Sanctum {
        private final Location center;
        private final double radius;
        private final double startAngle = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        private final List<BlockDisplay> bars = new ArrayList<>();
        private final List<BlockDisplay> pillars = new ArrayList<>();
        private final BlockDisplay beam;
        private final @Nullable TextDisplay label;

        Sanctum(Location ground, double radius, long duration) {
            this.center = ground.clone().add(0, 0.03, 0);
            this.center.setYaw(0);
            this.center.setPitch(0);
            this.radius = radius;
            long lifetime = duration + 30;

            beam = Displays.block(ctx(), player(), center, Material.YELLOW_STAINED_GLASS.createBlockData(),
                    pillar(0, 0, BEAM_WIDTH, 0.01f), lifetime);
            Displays.animateNextTick(ctx(), player(), beam, pillar(0, 0, BEAM_WIDTH, BEAM_HEIGHT), BEAM_GROW_TICKS);

            for (int i = 0; i < RING_BARS; i++) {
                double angle = startAngle + i * Math.PI * 2 / RING_BARS;
                BlockDisplay bar = Displays.block(ctx(), player(), center,
                        Material.YELLOW_STAINED_GLASS.createBlockData(), ringBar(angle, radius * 0.12), lifetime);
                bars.add(bar);
                Displays.animateNextTick(ctx(), player(), bar, ringBar(angle, radius), RING_UNFOLD_TICKS);
            }
            for (int i = 0; i < PILLARS; i++) {
                double angle = startAngle + (i + 0.5) * Math.PI * 2 / PILLARS;
                float x = (float) (Math.cos(angle) * radius);
                float z = (float) (Math.sin(angle) * radius);
                BlockDisplay pillar = Displays.block(ctx(), player(), center,
                        Material.WHITE_STAINED_GLASS.createBlockData(), pillar(x, z, PILLAR_WIDTH, 0.01f), lifetime);
                pillars.add(pillar);
                Displays.animateNextTick(ctx(), player(), pillar, pillar(x, z, PILLAR_WIDTH, PILLAR_HIGH), 12);
            }
            label = Displays.text(ctx(), player(), center.clone().add(0, BEAM_HEIGHT + 0.6, 0), labelText(duration),
                    1.0f, lifetime);
        }

        void tick(long t, long left) {
            draw(t);
            if (t > RING_UNFOLD_TICKS && t % 20 == RING_UNFOLD_TICKS % 20) {
                turn(t + 20);
            }
            if (t >= 12 && t % 20 == 12) {
                breathe(t);
            }
            if (t % 20 == 0 && label != null && label.isValid()) {
                label.text(labelText(left));
            }
        }

        /** Turns the ring to its angle at {@code t} over the next 20 ticks. */
        private void turn(long t) {
            double spin = t * 0.008;
            for (int i = 0; i < bars.size(); i++) {
                Displays.animate(bars.get(i), ringBar(startAngle + spin + i * Math.PI * 2 / RING_BARS, radius), 20);
            }
        }

        /** The pillars breathe: up and down between two heights, alternating every second. */
        private void breathe(long t) {
            boolean high = (t / 20) % 2 == 1;
            for (int i = 0; i < pillars.size(); i++) {
                double angle = startAngle + (i + 0.5) * Math.PI * 2 / PILLARS;
                float height = (i % 2 == 0) == high ? PILLAR_HIGH : PILLAR_LOW;
                Displays.animate(pillars.get(i), pillar((float) (Math.cos(angle) * radius), (float) (Math.sin(angle)
                        * radius), PILLAR_WIDTH, height), 20);
            }
        }

        private void draw(long t) {
            World world = center.getWorld();
            // A golden inner ring turning against the outer one, and a few bright motes on the edge.
            if (t % 5 == 0) {
                Vfx.ring(ctx(), center.clone().add(0, 0.12, 0), radius * 0.62, (int) Math.max(10, radius * 3),
                        -t * 0.03, Particle.DUST_COLOR_TRANSITION, GOLDEN.glow(0.8f), true);
            }
            Vfx.ring(ctx(), center.clone().add(0, 0.3, 0), radius, 4, t * 0.12, Particle.END_ROD, null, false);
            if (t % 4 == 0) {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                int motes = Vfx.n(ctx(), 3);
                for (int i = 0; i < motes; i++) {
                    Location spot = Vfx.scatter(center, radius).add(0, 0.2, 0);
                    // count 0: the offsets are a direction, so the mote drifts straight up
                    world.spawnParticle(Particle.END_ROD, spot.getX(), spot.getY(), spot.getZ(), 0, 0, 1, 0,
                            0.04 + random.nextDouble(0.04), null, false);
                }
            }
            if (t % 10 == 5) {
                for (int i = 0; i < PILLARS; i++) {
                    double angle = startAngle + (i + 0.5) * Math.PI * 2 / PILLARS;
                    Location foot = center.clone().add(Math.cos(angle) * radius, 0.3, Math.sin(angle) * radius);
                    world.spawnParticle(Particle.END_ROD, foot.getX(), foot.getY(), foot.getZ(), 0, 0, 1, 0, 0.12,
                            null, true);
                }
            }
        }

        /** Folds the sanctuary away: the ring collapses inwards, pillars and beam sink into the ground. */
        void close() {
            if (label != null && label.isValid()) {
                label.remove();
            }
            for (int i = 0; i < bars.size(); i++) {
                BlockDisplay bar = bars.get(i);
                Displays.animate(bar, ringBar(startAngle + i * Math.PI * 2 / RING_BARS, radius * 0.1), BEAM_GROW_TICKS);
            }
            for (int i = 0; i < pillars.size(); i++) {
                double angle = startAngle + (i + 0.5) * Math.PI * 2 / PILLARS;
                Displays.animate(pillars.get(i), pillar((float) (Math.cos(angle) * radius), (float) (Math.sin(angle)
                        * radius), PILLAR_WIDTH, 0.01f), BEAM_GROW_TICKS);
            }
            Displays.animate(beam, pillar(0, 0, BEAM_WIDTH, 0.01f), BEAM_GROW_TICKS);
            ctx().runLater(player(), BEAM_GROW_TICKS, () -> {
                removeAll(bars);
                removeAll(pillars);
                if (beam.isValid()) {
                    beam.remove();
                }
            });
            // Afterglow: a last golden wave over the ground and motes released upwards.
            Vfx.shockwave(ctx(), player(), center.clone().add(0, 0.15, 0), radius * 0.3, radius + 0.5, 6,
                    Particle.DUST_COLOR_TRANSITION, GOLDEN.fade(1.2f), true);
            Vfx.cloud(ctx(), center.clone().add(0, 1.5, 0), Particle.END_ROD, 20, 0.4, 0.08, null, true);
            Fx.sound(center, Sound.BLOCK_BEACON_DEACTIVATE, 1.2f, 1.2f + Fx.jitter(0.05f));
            Fx.sound(center, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 0.9f + Fx.jitter(0.1f));
        }

        private void removeAll(List<BlockDisplay> displays) {
            for (BlockDisplay display : displays) {
                if (display.isValid()) {
                    display.remove();
                }
            }
        }
    }

    private static net.kyori.adventure.text.Component labelText(long ticksLeft) {
        long seconds = Math.max(0, (ticksLeft + 19) / 20);
        return Text.mm("<#FFD866><bold>✚ 성역</bold></#FFD866> <white>" + seconds + "</white>");
    }

    /** A thin vertical bar of light standing on the ground at {@code (x, z)} from the display. */
    private static Transformation pillar(float x, float z, float width, float height) {
        return Displays.transform(x, height * 0.5f, z, width, height, width, 0, 0, 0);
    }

    /** A flat light bar on the ground, tangent to the circle of {@code distance} at {@code angle}. */
    private static Transformation ringBar(double angle, double distance) {
        float length = (float) (2 * distance * Math.sin(Math.PI / RING_BARS) * 0.86);
        // rotateY(90° - angle) lays the bar's length (local X) along the tangent; transform() takes -yaw.
        return Displays.transform((float) (Math.cos(angle) * distance), RING_BAR_HEIGHT * 0.5f,
                (float) (Math.sin(angle) * distance), length, RING_BAR_HEIGHT, RING_BAR_WIDTH,
                (float) Math.toDegrees(angle) - 90f, 0, 0);
    }

    // ------------------------------------------------------------------ helpers

    /** The first solid surface at most a few blocks below {@code point}, or the point itself. */
    private static Location ground(Location point) {
        World world = point.getWorld();
        RayTraceResult down = world.rayTraceBlocks(point.clone().add(0, 0.2, 0), new Vector(0, -1, 0), 6,
                FluidCollisionMode.NEVER, true);
        return down == null ? point.clone() : down.getHitPosition().toLocation(world);
    }

    /** Distance from a point to the closest point of a box (0 inside), matching how allies are found. */
    private static double distanceToBox(Location point, BoundingBox box) {
        double dx = Math.max(Math.max(box.getMinX() - point.getX(), 0), point.getX() - box.getMaxX());
        double dy = Math.max(Math.max(box.getMinY() - point.getY(), 0), point.getY() - box.getMaxY());
        double dz = Math.max(Math.max(box.getMinZ() - point.getZ(), 0), point.getZ() - box.getMaxZ());
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
