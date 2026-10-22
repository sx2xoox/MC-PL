package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
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
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.PotionContents;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 독술사 — poison and plague. Immune to poison and poisons on every melee hit, throws a flask that leaves a lingering
 * toxic cloud, and as the ultimate infects every nearby enemy with a plague that keeps spreading between them.
 */
public final class VenomAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("venom", "독술사", Material.SPIDER_EYE)
            .color(TextColor.color(0x8FD14F))
            .difficulty(2)
            .passive("맹독 체질",
                    "독에 걸리지 않습니다. 근접 공격한 적에게 {poison_duration}초간 독 I을 겁니다.")
            .active("독안개",
                    "조준한 지점({cloud_range}블록 이내)에 독병을 던져 {cloud_duration}초간 반경 {cloud_radius}블록의 "
                            + "독구름을 만듭니다. 구름 속 적은 독 II({cloud_poison}초)에 걸리고 {cloud_interval}초마다 "
                            + "{cloud_damage} 피해를 입습니다.",
                    10)
            .ultimate("역병",
                    "반경 {ult_radius}블록의 모든 적을 {ult_duration}초간 역병에 감염시킵니다. 감염된 적은 "
                            + "{ult_interval}초마다 {ult_damage} 피해를 입고 구속 I에 걸리며, {spread_radius}블록 안의 "
                            + "다른 적에게 역병을 옮깁니다.")
            .param("poison_duration", 3)
            .param("cloud_range", 20)
            .param("cloud_duration", 5)
            .param("cloud_radius", 3.5)
            .param("cloud_poison", 2)
            .param("cloud_interval", 0.5)
            .param("cloud_damage", 0.5)
            .param("ult_radius", 15)
            .param("ult_duration", 6)
            .param("ult_interval", 1)
            .param("ult_damage", 1)
            .param("spread_radius", 3)
            .build();

    private static final String COLOR_TAG = "#8FD14F";
    /** Acid lime highlight, venom green body, deep swamp green. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xD4FF8F, 0x8FD14F, 0x2E5A12);
    private static final Particle.DustTransition ACID = PALETTE.glow(0.9f);
    private static final Particle.DustOptions VENOM_DROP = PALETTE.dust(0.8f);
    private static final Particle.DustTransition MIASMA = PALETTE.fade(1.8f);
    private static final Particle.DustOptions[] FOG = {
            new Particle.DustOptions(PALETTE.body(), 2.2f),
            new Particle.DustOptions(Color.fromRGB(0x5F, 0xA8, 0x2A), 2.4f),
            new Particle.DustOptions(PALETTE.edge(), 2.0f),
    };
    private static final Particle.DustOptions RIM = new Particle.DustOptions(PALETTE.edge(), 1.1f);
    private static final Particle.DustOptions RIM_INNER = PALETTE.dust(0.9f);
    private static final Particle.DustOptions PLAGUE_DUST = new Particle.DustOptions(Color.fromRGB(0x2F, 0x3D, 0x1A), 1.2f);
    private static final Particle.DustTransition PLAGUE_RING = PALETTE.glow(0.9f);
    private static final Particle.DustTransition CONTAGION = PALETTE.glow(1.2f);
    private static final BlockData BUBBLE = Material.SLIME_BLOCK.createBlockData();

    // passive
    private static final long POISON_CHECK_PERIOD = 10;

    // active: toxic cloud
    private static final double FLASK_SPEED = 1.4;
    private static final int FLASK_MIN_TICKS = 3;
    private static final int FLASK_MAX_TICKS = 16;
    private static final int GROUND_SNAP = 6;
    private static final double CLOUD_HEIGHT = 2.2;
    /** Height of the cloud's hit-test centre above the ground, so enemies standing in it are inside the sphere. */
    private static final double CLOUD_CORE = 1.0;
    private static final long BUBBLE_PERIOD = 5;
    private static final int BUBBLE_GROW_TICKS = 5;
    /** The cloud thins out over its last ticks instead of vanishing at once. */
    private static final long CLOUD_FADE_TICKS = 15;

    // ultimate: plague
    private static final int WAVE_TICKS = 10;
    private static final long SPREAD_PERIOD = 10;
    private static final int CONTAGION_TICKS = 6;
    private static final int RING_CLIMB_TICKS = 20;
    /**
     * The wither effect is only applied for its black hearts. Vanilla wither I hurts on ticks where the remaining
     * duration is a multiple of 40, so a duration below 40 never deals vanilla (uncredited) damage; the plague
     * deals its damage itself through the context.
     */
    private static final int WITHER_VISUAL_TICKS = 39;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    // ------------------------------------------------------------------ passive: venom blood

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.getCause() == DamageCause.POISON) {
            event.setCancelled(true);
        }
    }

    @Override
    public void onTick(long tick) {
        if (tick % POISON_CHECK_PERIOD == 0 && player().hasPotionEffect(PotionEffectType.POISON)) {
            player().removePotionEffect(PotionEffectType.POISON);
            // The poison is sweated out as a little green vapour.
            Location body = Fx.body(player());
            body.getWorld().spawnParticle(Particle.DUST_COLOR_TRANSITION, body, Vfx.n(ctx(), 6), 0.3, 0.45, 0.3, 0,
                    ACID);
            Vfx.cloud(ctx(), body, Particle.WHITE_SMOKE, 3, 0.25, 0.01, null, false);
        }
    }

    /** The hit landed (a dodged or blocked swing poisons nobody). */
    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        DamageCause cause = event.getCause();
        if (ctx().isAbilityDamage() || !player().equals(event.getDamager())
                || cause != DamageCause.ENTITY_ATTACK && cause != DamageCause.ENTITY_SWEEP_ATTACK
                || !ctx().isEnemy(player(), victim)) {
            return;
        }
        int ticks = (int) Math.min(Integer.MAX_VALUE, params().ticks("poison_duration"));
        if (ticks <= 0) {
            return;
        }
        victim.addPotionEffect(new PotionEffect(PotionEffectType.POISON, ticks, 0, false, true, true));
        // A venom splash on the wound.
        Location body = Fx.body(victim);
        Vfx.ring(ctx(), body, 0.45, 8, ThreadLocalRandom.current().nextDouble(Math.PI * 2),
                Particle.DUST_COLOR_TRANSITION, ACID, false);
        body.getWorld().spawnParticle(Particle.ITEM_SLIME, body, Vfx.n(ctx(), 3), 0.2, 0.3, 0.2, 0.05);
        Vfx.dot(body, Particle.DUST, VENOM_DROP, false);
        Fx.sound(body, Sound.ENTITY_SLIME_SQUISH_SMALL, 0.4f, 1.3f + Fx.jitter(0.15f));
    }

    // ------------------------------------------------------------------ active: toxic cloud

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection();
        Location landing = aimPoint(eye, direction, params().num("cloud_range"));
        Location start = eye.clone().add(direction.clone().multiply(0.6)).subtract(0, 0.2, 0);
        Flask flask = new Flask(start, landing);
        Loop.repeat(ctx(), player(), 0, 1, flask.flightTicks + 1L, flask);
        start.getWorld().spawnParticle(Particle.DUST_COLOR_TRANSITION, start, Vfx.n(ctx(), 6), 0.12, 0.12, 0.12, 0,
                ACID);
        Fx.sound(eye, Sound.ENTITY_SPLASH_POTION_THROW, 1.0f, 0.8f + Fx.jitter(0.05f));
        Fx.sound(eye, Sound.ENTITY_WITCH_THROW, 0.6f, 1.1f + Fx.jitter(0.1f));
        return ActivationResult.success();
    }

    /** Where the flask lands: the aimed enemy or block (or the end of the range), dropped onto the ground below. */
    private Location aimPoint(Location eye, Vector direction, double range) {
        Player owner = player();
        World world = owner.getWorld();
        RayTraceResult hit = world.rayTrace(eye, direction, range, FluidCollisionMode.NEVER, true, 0.25,
                entity -> entity instanceof LivingEntity && ctx().isEnemy(owner, entity));
        Location point;
        if (hit == null) {
            point = eye.clone().add(direction.clone().multiply(range));
        } else if (hit.getHitEntity() != null) {
            point = hit.getHitEntity().getLocation();
        } else {
            point = hit.getHitPosition().toLocation(world).subtract(direction.clone().multiply(0.3));
        }
        Double ground = groundBelow(point);
        if (ground != null) {
            point.setY(ground);
        }
        return point;
    }

    /** The top of the first solid block at most {@link #GROUND_SNAP} blocks below {@code point}, null if none. */
    private static @Nullable Double groundBelow(Location point) {
        World world = point.getWorld();
        int start = Math.min(point.getBlockY(), world.getMaxHeight() - 1);
        int end = Math.max(world.getMinHeight(), point.getBlockY() - GROUND_SNAP);
        for (int y = start; y >= end; y--) {
            Block block = world.getBlockAt(point.getBlockX(), y, point.getBlockZ());
            if (!block.isPassable()) {
                return block.getBoundingBox().getMaxY();
            }
        }
        return null;
    }

    /**
     * The thrown flask: a tumbling green splash potion flying in a short arc and dripping venom, while a ring on the
     * ground marks where it will burst into the cloud.
     */
    private final class Flask implements LongPredicate {

        private final Location start;
        private final Location landing;
        private final Location marker;
        private final Vector path;
        private final double arc;
        private final double radius;
        private final int flightTicks;
        private final ItemDisplay visual;

        Flask(Location start, Location landing) {
            this.start = start;
            this.landing = landing;
            this.marker = landing.clone().add(0, 0.1, 0);
            this.path = landing.toVector().subtract(start.toVector());
            double distance = path.length();
            this.flightTicks = Math.clamp((long) Math.ceil(distance / FLASK_SPEED), FLASK_MIN_TICKS, FLASK_MAX_TICKS);
            this.arc = Math.min(4.0, distance * 0.18);
            this.radius = Math.max(0.5, params().num("cloud_radius"));
            ItemStack item = ItemStack.of(Material.SPLASH_POTION);
            item.setData(DataComponentTypes.POTION_CONTENTS, PotionContents.potionContents().customColor(PALETTE.body()));
            this.visual = Displays.item(ctx(), player(), start, item,
                    Displays.centered(0, 0, 0, 0.55f, 0.55f, 0.55f, 0, 0, 0), flightTicks + 2L);
            visual.setTeleportDuration(1);
        }

        @Override
        public boolean test(long tick) {
            if (tick >= flightTicks) {
                visual.remove();
                burst();
                return false;
            }
            double t = (double) (tick + 1) / flightTicks;
            Location position = start.clone().add(path.clone().multiply(t)).add(0, 4 * arc * t * (1 - t), 0);
            if (visual.isValid()) {
                visual.teleport(position);
                // Tumbling end over end.
                Displays.animate(visual, Displays.centered(0, 0, 0, 0.55f, 0.55f, 0.55f, tick * 25f, tick * 40f, 0), 1);
            }
            World world = position.getWorld();
            Vfx.dot(position, Particle.DUST_COLOR_TRANSITION, ACID, false);
            world.spawnParticle(Particle.ITEM_SLIME, position, 1, 0.05, 0.05, 0.05, 0);
            if (tick % 2 == 0) {
                Vfx.dot(position.clone().subtract(path.clone().normalize().multiply(0.3)), Particle.DUST, VENOM_DROP,
                        false);
            }
            if (tick % 2 == 0) {
                // Telegraph: the splash zone on the ground, with a ring closing in as the flask comes down.
                Vfx.ring(ctx(), marker, radius, (int) Math.max(12, radius * 6), tick * 0.1, Particle.DUST, RIM, true);
                Vfx.ring(ctx(), marker, Math.max(0.3, radius * (1 - t)), (int) Math.max(8, radius * 4), -tick * 0.15,
                        Particle.DUST_COLOR_TRANSITION, ACID, true);
            }
            return true;
        }

        private void burst() {
            World world = landing.getWorld();
            Location splash = landing.clone().add(0, 0.3, 0);
            Vfx.dot(splash, Particle.FLASH, PALETTE.core(), false);
            world.spawnParticle(Particle.EFFECT, splash, Vfx.n(ctx(), 30), 0.6, 0.3, 0.6, 0.2,
                    new Particle.Spell(PALETTE.body(), 1.0f));
            world.spawnParticle(Particle.ITEM_SLIME, splash, Vfx.n(ctx(), 12), 0.5, 0.2, 0.5, 0.1);
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, splash, Vfx.n(ctx(), 14), 0.5, 0.3, 0.5, 0, MIASMA);
            Vfx.shockwave(ctx(), player(), marker, 0.3, radius, 5, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.4f),
                    false);
            Fx.sound(splash, Sound.ENTITY_SPLASH_POTION_BREAK, 1.0f, 0.8f + Fx.jitter(0.05f));
            Fx.sound(splash, Sound.ENTITY_PUFFER_FISH_BLOW_UP, 0.8f, 0.6f + Fx.jitter(0.05f));
            Fx.sound(splash, Sound.BLOCK_BUBBLE_COLUMN_UPWARDS_AMBIENT, 0.8f, 0.7f);
            Cloud cloud = new Cloud(landing);
            Loop.repeat(ctx(), player(), 0, 1, cloud.lifeTicks, cloud, cloud::dissipate);
        }
    }

    /**
     * The lingering toxic cloud. Applies its effects to enemies inside on every pulse.
     *
     * <p>Visuals in layers: a dense low fog, a lighter miasma above it, drifting firefly-like spores, a double rim on
     * the ground, and slime bubbles (small slime block displays) that swell and pop.
     */
    private final class Cloud implements LongPredicate {

        private final Location floor;
        private final Location core;
        private final double radius;
        private final long lifeTicks;
        private final long interval;
        private final double damage;
        private final int poisonTicks;

        Cloud(Location landing) {
            this.floor = landing.clone().add(0, 0.1, 0);
            this.core = landing.clone().add(0, CLOUD_CORE, 0);
            this.radius = Math.max(0.5, params().num("cloud_radius"));
            this.lifeTicks = Math.max(1, params().ticks("cloud_duration"));
            this.interval = Math.max(1, params().ticks("cloud_interval"));
            this.damage = params().num("cloud_damage");
            this.poisonTicks = (int) Math.min(Integer.MAX_VALUE, params().ticks("cloud_poison"));
        }

        @Override
        public boolean test(long tick) {
            if (tick % interval == 0) {
                pulse();
            }
            double density = Math.min(1.0, (double) (lifeTicks - tick) / CLOUD_FADE_TICKS);
            if (tick % 2 == 0) {
                drawCloud(density);
            }
            if (tick % 6 == 0) {
                drawRim(tick);
            }
            if (tick % BUBBLE_PERIOD == 0 && density >= 1.0) {
                bubble();
            }
            if (tick % 15 == 0) {
                Fx.sound(core, Sound.BLOCK_BUBBLE_COLUMN_BUBBLE_POP, 0.9f, 0.6f + Fx.jitter(0.1f));
                Fx.sound(core, Sound.ENTITY_SLIME_SQUISH_SMALL, 0.5f, 0.6f + Fx.jitter(0.1f));
            }
            return tick + 1 < lifeTicks;
        }

        private void pulse() {
            for (LivingEntity enemy : ctx().enemiesNear(player(), core, radius)) {
                if (poisonTicks > 0) {
                    enemy.addPotionEffect(new PotionEffect(PotionEffectType.POISON, poisonTicks, 1, false, true, true));
                }
                Combat.hit(ctx(), player(), enemy, damage);
                Location body = Fx.body(enemy);
                enemy.getWorld().spawnParticle(Particle.ITEM_SLIME, body, Vfx.n(ctx(), 3), 0.25, 0.4, 0.25, 0.05);
                enemy.getWorld().spawnParticle(Particle.DUST_COLOR_TRANSITION, body, Vfx.n(ctx(), 3), 0.25, 0.4, 0.25,
                        0, ACID);
            }
        }

        private void drawCloud(double density) {
            World world = core.getWorld();
            ThreadLocalRandom random = ThreadLocalRandom.current();
            // Low fog hugging the ground.
            int fog = (int) Math.round(Vfx.n(ctx(), 4) * density);
            for (int i = 0; i < fog; i++) {
                Location at = Vfx.scatter(floor, radius * 0.95).add(0, random.nextDouble() * 0.5, 0);
                world.spawnParticle(Particle.DUST, at, 1, 0.05, 0.05, 0.05, 0, FOG[random.nextInt(FOG.length)]);
            }
            // Lighter miasma rising above it.
            int miasma = (int) Math.round(Vfx.n(ctx(), 3) * density);
            for (int i = 0; i < miasma; i++) {
                Location at = Vfx.scatter(floor, radius * 0.85).add(0, 0.5 + random.nextDouble() * (CLOUD_HEIGHT - 0.5), 0);
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, 1, 0.05, 0.05, 0.05, 0, MIASMA);
            }
            if (random.nextDouble() < 0.6 * density) {
                // Glowing spores drifting through the cloud.
                Location at = Vfx.scatter(floor, radius * 0.8).add(0, 0.3 + random.nextDouble() * CLOUD_HEIGHT, 0);
                Vfx.dot(at, Particle.FIREFLY, null, false);
            }
            if (random.nextDouble() < 0.25 * density) {
                Vfx.dot(Vfx.scatter(floor, radius * 0.7).add(0, 0.2, 0), Particle.NOXIOUS_GAS, null, false);
            }
        }

        /** A double rim on the ground, turning slowly in opposite directions. */
        private void drawRim(long tick) {
            Vfx.ring(ctx(), floor, radius, (int) Math.max(12, Math.min(32, radius * 5)), tick * 0.02, Particle.DUST,
                    RIM, false);
            Vfx.ring(ctx(), floor.clone().add(0, 0.35, 0), radius * 0.9, (int) Math.max(8, Math.min(20, radius * 3)),
                    -tick * 0.04, Particle.DUST, RIM_INNER, false);
        }

        /** A slime bubble swells out of the toxic pool and pops. */
        private void bubble() {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            Location at = Vfx.scatter(floor, radius * 0.75);
            float size = 0.22f + random.nextFloat() * 0.14f;
            float yaw = random.nextFloat() * 90;
            BlockDisplay bubble = Displays.block(ctx(), player(), at, BUBBLE,
                    Displays.transform(0, 0.05f, 0, 0.05f, 0.05f, 0.05f, yaw, 0, 0), BUBBLE_GROW_TICKS + 4L);
            Displays.animateNextTick(ctx(), player(), bubble,
                    Displays.transform(0, 0.15f + size / 2, 0, size, size, size, yaw + 30, 0, 0), BUBBLE_GROW_TICKS);
            ctx().runLater(player(), BUBBLE_GROW_TICKS + 1L, () -> {
                if (!bubble.isValid()) {
                    return;
                }
                Location pop = at.clone().add(0, 0.15 + size / 2, 0);
                pop.getWorld().spawnParticle(Particle.ITEM_SLIME, pop, Vfx.n(ctx(), 4), 0.12, 0.08, 0.12, 0.05);
                Vfx.cloud(ctx(), pop, Particle.BUBBLE_POP, 3, 0.1, 0.02, null, false);
                Vfx.dot(pop, Particle.DUST_COLOR_TRANSITION, ACID, false);
                Fx.sound(pop, Sound.BLOCK_BUBBLE_COLUMN_BUBBLE_POP, 0.6f, 1.2f + Fx.jitter(0.2f));
                Displays.shrinkAway(ctx(), player(), bubble, 2);
            });
        }

        /** Afterglow: the last of the cloud drifts up and thins out. */
        void dissipate() {
            World world = core.getWorld();
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, core, Vfx.n(ctx(), 10), radius * 0.4, 0.6,
                    radius * 0.4, 0, MIASMA);
            Vfx.cloud(ctx(), core, Particle.WHITE_SMOKE, 4, radius * 0.3, 0.02, null, false);
        }
    }

    // ------------------------------------------------------------------ ultimate: plague

    @Override
    public ActivationResult onUltimate() {
        double radius = params().num("ult_radius");
        Location origin = player().getLocation();
        List<LivingEntity> targets = ctx().enemiesNear(player(), origin, radius);
        if (targets.isEmpty()) {
            return ActivationResult.fail("반경 " + AbilityInfo.formatNumber(radius) + "블록 안에 적이 없습니다");
        }
        Vfx.burst(ctx(), Fx.body(player()), PALETTE, 1.2, Particle.SQUID_INK, true);
        Fx.sound(origin, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.8f, 1.2f);
        Fx.sound(origin, Sound.ENTITY_WITHER_SHOOT, 0.8f, 0.6f + Fx.jitter(0.05f));
        Fx.sound(origin, Sound.ENTITY_WITCH_CELEBRATE, 0.7f, 0.7f + Fx.jitter(0.05f));
        Loop.repeat(ctx(), player(), 0, 1, WAVE_TICKS, tick -> {
            drawWave(origin, radius * (tick + 1) / WAVE_TICKS, tick);
            return true;
        });

        Plague plague = new Plague();
        for (LivingEntity target : List.copyOf(targets)) {
            plague.infect(target, null);
        }
        player().sendActionBar(Text.mm("<" + COLOR_TAG + ">☣ 역병 감염 <white>" + plague.infectedCount()
                + "명</white></" + COLOR_TAG + ">"));
        Loop.repeat(ctx(), player(), 1, 1, plague.duration + 1, plague, plague::clear);
        return ActivationResult.success();
    }

    /** The outbreak wave: a sickly bright front with a dark wake behind it. */
    private void drawWave(Location origin, double radius, long tick) {
        Location front = origin.clone().add(0, 0.3, 0);
        int points = (int) Math.max(12, Math.min(48, radius * 4));
        Vfx.ring(ctx(), front, radius, points, tick * 0.1, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.5f), true);
        if (radius > 1.2) {
            Vfx.ring(ctx(), front, radius - 0.9, Math.max(8, points / 2), -tick * 0.1, Particle.DUST, PLAGUE_DUST, true);
        }
    }

    /**
     * One plague outbreak. Every infection ends when the outbreak does (spread infections inherit the remaining time),
     * and each enemy can be infected only once per outbreak, so the chain always dies out.
     *
     * <p>Every infected enemy carries a floating ☣ mark and a green ring that climbs up and down its body; each new
     * infection is shown as a glob of plague arcing over from the enemy that passed it on.
     */
    private final class Plague implements LongPredicate {

        private final long duration;
        private final long interval;
        private final double damage;
        private final double spreadRadius;
        private final Map<UUID, Infection> active = new LinkedHashMap<>();
        private final Set<UUID> infectedOnce = new HashSet<>();
        private long now;

        /** One infected enemy: when it caught the plague, when the plague leaves it, and its floating mark. */
        private record Infection(LivingEntity target, long start, long end, @Nullable TextDisplay mark) {
        }

        Plague() {
            this.duration = Math.max(1, params().ticks("ult_duration"));
            this.interval = Math.max(1, params().ticks("ult_interval"));
            this.damage = params().num("ult_damage");
            this.spreadRadius = params().num("spread_radius");
        }

        int infectedCount() {
            return infectedOnce.size();
        }

        /** Infects {@code target}, caught from {@code source} (null: from the owner's outbreak). */
        void infect(LivingEntity target, @Nullable Infection source) {
            if (!Combat.isTargetable(ctx(), player(), target) || infectedOnce.contains(target.getUniqueId())) {
                return;
            }
            long end = source == null ? now + duration : source.end();
            if (end - now < interval) {
                return; // too little time left to matter
            }
            infectedOnce.add(target.getUniqueId());
            int remaining = (int) Math.min(Integer.MAX_VALUE, end - now);
            TextDisplay mark = Displays.text(ctx(), player(), markPoint(target),
                    Text.mm("<b><gradient:#D4FF8F:#8FD14F>☣</gradient></b>"), 0.4f, remaining + 6L);
            mark.setTeleportDuration(1);
            Displays.animateNextTick(ctx(), player(), mark, Displays.centered(0, 0, 0, 1.2f, 1.2f, 1.2f, 0, 0, 0), 4);
            active.put(target.getUniqueId(), new Infection(target, now, end, mark));
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, remaining, 0, false, true, true));
            showWither(target, remaining);
            Combat.hit(ctx(), player(), target, damage, DamageType.WITHER);

            Location body = Fx.body(target);
            World world = target.getWorld();
            world.spawnParticle(Particle.SQUID_INK, body, Vfx.n(ctx(), 6), 0.3, 0.4, 0.3, 0.02);
            world.spawnParticle(Particle.DUST, body, Vfx.n(ctx(), 6), 0.3, 0.5, 0.3, 0, PLAGUE_DUST);
            Vfx.ring(ctx(), target.getLocation().add(0, 0.1, 0), 0.8, 14, 0, Particle.DUST_COLOR_TRANSITION,
                    PLAGUE_RING, true);
            Fx.sound(body, Sound.ENTITY_ZOMBIE_INFECT, 0.8f, 1.3f + Fx.jitter(0.1f));
            if (source != null && source.target().getWorld().equals(world)) {
                contagion(source.target(), target);
            }
        }

        @Override
        public boolean test(long tick) {
            now = tick;
            List<Map.Entry<LivingEntity, Infection>> spreads = new ArrayList<>();
            Iterator<Infection> iterator = active.values().iterator();
            while (iterator.hasNext()) {
                Infection infection = iterator.next();
                LivingEntity target = infection.target();
                if (!Combat.isTargetable(ctx(), player(), target) || tick >= infection.end()) {
                    iterator.remove();
                    fadeMark(infection);
                    continue;
                }
                long age = tick - infection.start();
                if (age > 0 && age % interval == 0) {
                    Combat.hit(ctx(), player(), target, damage, DamageType.WITHER);
                    Location body = Fx.body(target);
                    target.getWorld().spawnParticle(Particle.SQUID_INK, body, Vfx.n(ctx(), 3), 0.25, 0.4, 0.25, 0.02);
                    target.getWorld().spawnParticle(Particle.DUST_COLOR_TRANSITION, body, Vfx.n(ctx(), 4), 0.3, 0.45,
                            0.3, 0, PLAGUE_RING);
                }
                if (age > 0 && age % 20 == 0) {
                    showWither(target, (int) Math.min(Integer.MAX_VALUE, infection.end() - tick));
                }
                drawInfection(infection, tick);
                if (tick % SPREAD_PERIOD == 0 && spreadRadius > 0) {
                    for (LivingEntity near : ctx().enemiesNear(player(), target.getLocation(), spreadRadius)) {
                        if (!infectedOnce.contains(near.getUniqueId())) {
                            spreads.add(Map.entry(near, infection));
                        }
                    }
                }
            }
            int before = infectedOnce.size();
            spreads.forEach(spread -> infect(spread.getKey(), spread.getValue()));
            if (infectedOnce.size() > before && player().isOnline()) {
                player().sendActionBar(Text.mm("<" + COLOR_TAG + ">☣ 역병 확산! <white>총 " + infectedOnce.size()
                        + "명</white></" + COLOR_TAG + ">"));
            }
            return !active.isEmpty();
        }

        /** The mark follows its enemy; a green ring climbs up and down its body; dark spores drip off it. */
        private void drawInfection(Infection infection, long tick) {
            LivingEntity target = infection.target();
            TextDisplay mark = infection.mark();
            if (mark != null && mark.isValid()) {
                if (mark.getWorld().equals(target.getWorld())) {
                    mark.teleport(markPoint(target));
                } else {
                    mark.remove();
                }
            }
            if (tick % 2 == 0) {
                long phase = (tick - infection.start()) % (RING_CLIMB_TICKS * 2L);
                double climb = (phase < RING_CLIMB_TICKS ? phase : RING_CLIMB_TICKS * 2L - phase)
                        / (double) RING_CLIMB_TICKS;
                Location at = target.getLocation().add(0, 0.1 + climb * (target.getHeight() - 0.2), 0);
                Vfx.ring(ctx(), at, 0.55, 10, tick * 0.3, Particle.DUST_COLOR_TRANSITION, PLAGUE_RING, true);
            }
            if (tick % 4 == 0) {
                Location body = Fx.body(target);
                target.getWorld().spawnParticle(Particle.DUST, body, Vfx.n(ctx(), 2), 0.3, 0.5, 0.3, 0, PLAGUE_DUST);
                target.getWorld().spawnParticle(Particle.SQUID_INK, body, 1, 0.25, 0.4, 0.25, 0.01);
            }
        }

        /** A glob of plague arcing from {@code from} to {@code to}, bursting where it lands. */
        private void contagion(LivingEntity from, LivingEntity to) {
            Location start = Fx.body(from);
            Fx.sound(start, Sound.ENTITY_SLIME_JUMP_SMALL, 0.7f, 0.6f + Fx.jitter(0.1f));
            Loop.repeat(ctx(), player(), 0, 1, CONTAGION_TICKS, run -> {
                if (!to.isValid() || !to.getWorld().equals(start.getWorld())) {
                    return false;
                }
                Location end = Fx.body(to);
                double height = Math.max(0.8, start.distance(end) * 0.35);
                double head = (run + 1.0) / CONTAGION_TICKS;
                for (int i = 0; i < 4; i++) {
                    double t = Math.max(0, head - i * 0.06);
                    Location at = arcPoint(start, end, height, t);
                    if (i == 0) {
                        Vfx.dot(at, Particle.DUST_COLOR_TRANSITION, CONTAGION, true);
                        Vfx.dot(at, Particle.ITEM_SLIME, null, false);
                    } else {
                        Vfx.dot(at, Particle.DUST, PLAGUE_DUST, true);
                    }
                }
                if (run == CONTAGION_TICKS - 1) {
                    end.getWorld().spawnParticle(Particle.DUST_COLOR_TRANSITION, end, Vfx.n(ctx(), 10), 0.3, 0.4, 0.3,
                            0, CONTAGION);
                    Vfx.dot(end, Particle.FLASH, PALETTE.body(), true);
                }
                return true;
            });
        }

        /** The infection ended: its mark shrinks away. */
        private void fadeMark(Infection infection) {
            TextDisplay mark = infection.mark();
            if (mark != null && mark.isValid()) {
                Displays.shrinkAway(ctx(), player(), mark, 4);
            }
        }

        /** The outbreak is over: every remaining mark fades. */
        void clear() {
            active.values().forEach(this::fadeMark);
            active.clear();
        }
    }

    /** Where an infected enemy's ☣ mark floats: just above its head. */
    private static Location markPoint(LivingEntity target) {
        return target.getLocation().add(0, target.getHeight() + 0.55, 0);
    }

    /** A point on the parabola from {@code from} to {@code to} that peaks {@code height} above the straight line. */
    private static Location arcPoint(Location from, Location to, double height, double t) {
        Vector delta = to.toVector().subtract(from.toVector());
        return from.clone().add(delta.multiply(t)).add(0, 4 * height * t * (1 - t), 0);
    }

    /** Black hearts without vanilla wither damage (see {@link #WITHER_VISUAL_TICKS}). */
    private static void showWither(LivingEntity target, int remaining) {
        int ticks = Math.min(WITHER_VISUAL_TICKS, remaining);
        if (ticks > 0) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, ticks, 0, false, false, true));
        }
    }
}
