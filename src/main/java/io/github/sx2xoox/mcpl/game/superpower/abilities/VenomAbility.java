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
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
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
    private static final Color VENOM_GREEN = Color.fromRGB(0x7C, 0xD6, 0x3A);
    private static final Color PLAGUE_GREEN = Color.fromRGB(0x5E, 0x8C, 0x2E);
    private static final Particle.DustOptions[] CLOUD_DUST = {
            new Particle.DustOptions(Color.fromRGB(0x9B, 0xE0, 0x4C), 2.0f),
            new Particle.DustOptions(Color.fromRGB(0x5F, 0xA8, 0x2A), 2.2f),
            new Particle.DustOptions(Color.fromRGB(0x3E, 0x6B, 0x1F), 1.8f),
    };
    private static final Particle.DustOptions CLOUD_EDGE = new Particle.DustOptions(Color.fromRGB(0x4E, 0x8A, 0x22), 1.0f);
    private static final Particle.DustOptions POISON_DUST = new Particle.DustOptions(VENOM_GREEN, 0.9f);
    private static final Particle.DustOptions PLAGUE_DUST = new Particle.DustOptions(Color.fromRGB(0x2F, 0x3D, 0x1A), 1.2f);
    private static final Particle.DustTransition PLAGUE_WAVE =
            new Particle.DustTransition(Color.fromRGB(0xA6, 0xE8, 0x5A), Color.fromRGB(0x1A, 0x1F, 0x10), 1.5f);

    // active: toxic cloud
    private static final double FLASK_SPEED = 1.4;
    private static final int FLASK_MIN_TICKS = 3;
    private static final int FLASK_MAX_TICKS = 16;
    private static final int GROUND_SNAP = 6;
    private static final double CLOUD_HEIGHT = 2.2;
    /** Height of the cloud's hit-test centre above the ground, so enemies standing in it are inside the sphere. */
    private static final double CLOUD_CORE = 1.0;

    // ultimate: plague
    private static final int WAVE_TICKS = 10;
    private static final long SPREAD_PERIOD = 10;
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
        if (tick % 10 == 0 && player().hasPotionEffect(PotionEffectType.POISON)) {
            player().removePotionEffect(PotionEffectType.POISON);
        }
    }

    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        DamageCause cause = event.getCause();
        if (event.isCancelled() || ctx().isAbilityDamage() || !player().equals(event.getDamager())
                || cause != DamageCause.ENTITY_ATTACK && cause != DamageCause.ENTITY_SWEEP_ATTACK
                || !ctx().isEnemy(player(), victim)) {
            return;
        }
        int ticks = (int) Math.min(Integer.MAX_VALUE, params().ticks("poison_duration"));
        if (ticks <= 0) {
            return;
        }
        victim.addPotionEffect(new PotionEffect(PotionEffectType.POISON, ticks, 0, false, true, true));
        Location body = body(victim);
        victim.getWorld().spawnParticle(Particle.ITEM_SLIME, body, 4, 0.25, 0.35, 0.25, 0.05);
        victim.getWorld().spawnParticle(Particle.DUST, body, 3, 0.25, 0.35, 0.25, 0, POISON_DUST);
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
        loop(0, 1, flask.flightTicks + 1L, flask);
        sound(eye, Sound.ENTITY_SPLASH_POTION_THROW, 1.0f, 0.8f + jitter(0.05f));
        sound(eye, Sound.ENTITY_WITCH_THROW, 0.6f, 1.1f + jitter(0.1f));
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

    /** The thrown flask: a green splash potion flying in a short arc, bursting into the cloud where it lands. */
    private final class Flask implements LongPredicate {

        private final Location start;
        private final Location landing;
        private final Vector path;
        private final double arc;
        private final int flightTicks;
        private final ItemDisplay visual;

        Flask(Location start, Location landing) {
            this.start = start;
            this.landing = landing;
            this.path = landing.toVector().subtract(start.toVector());
            double distance = path.length();
            this.flightTicks = Math.clamp((long) Math.ceil(distance / FLASK_SPEED), FLASK_MIN_TICKS, FLASK_MAX_TICKS);
            this.arc = Math.min(4.0, distance * 0.18);
            ItemStack item = ItemStack.of(Material.SPLASH_POTION);
            item.setData(DataComponentTypes.POTION_CONTENTS, PotionContents.potionContents().customColor(VENOM_GREEN));
            this.visual = ctx().track(player(), start.getWorld().spawn(start, ItemDisplay.class, display -> {
                display.setPersistent(false);
                display.setItemStack(item);
                display.setBillboard(Display.Billboard.CENTER);
                display.setTeleportDuration(1);
                display.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                        new Vector3f(0.55f, 0.55f, 0.55f), new Quaternionf()));
            }));
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
            }
            position.getWorld().spawnParticle(Particle.DUST, position, 1, 0.05, 0.05, 0.05, 0, POISON_DUST);
            if (tick % 2 == 0) {
                position.getWorld().spawnParticle(Particle.ITEM_SLIME, position, 1, 0.05, 0.05, 0.05, 0);
            }
            return true;
        }

        private void burst() {
            World world = landing.getWorld();
            Location splash = landing.clone().add(0, 0.3, 0);
            world.spawnParticle(Particle.EFFECT, splash, 30, 0.6, 0.3, 0.6, 0.2, new Particle.Spell(VENOM_GREEN, 1.0f));
            world.spawnParticle(Particle.ITEM_SLIME, splash, 12, 0.5, 0.2, 0.5, 0.1);
            sound(splash, Sound.ENTITY_SPLASH_POTION_BREAK, 1.0f, 0.8f + jitter(0.05f));
            sound(splash, Sound.ENTITY_PUFFER_FISH_BLOW_UP, 0.8f, 0.6f);
            Cloud cloud = new Cloud(landing);
            loop(0, 1, cloud.lifeTicks, cloud);
        }
    }

    /** The lingering toxic cloud. Applies its effects to enemies inside on every pulse. */
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
            if (tick % 2 == 0) {
                drawCloud();
            }
            if (tick % 6 == 0) {
                drawEdge();
            }
            if (tick % 15 == 0) {
                sound(core, Sound.BLOCK_BUBBLE_COLUMN_BUBBLE_POP, 0.9f, 0.6f + jitter(0.1f));
                sound(core, Sound.ENTITY_SLIME_SQUISH_SMALL, 0.5f, 0.6f + jitter(0.1f));
            }
            return tick + 1 < lifeTicks;
        }

        private void pulse() {
            for (LivingEntity enemy : ctx().enemiesNear(player(), core, radius)) {
                if (poisonTicks > 0) {
                    enemy.addPotionEffect(new PotionEffect(PotionEffectType.POISON, poisonTicks, 1, false, true, true));
                }
                hit(enemy, damage, DamageType.MAGIC);
                enemy.getWorld().spawnParticle(Particle.ITEM_SLIME, body(enemy), 3, 0.25, 0.4, 0.25, 0.05);
            }
        }

        private void drawCloud() {
            World world = core.getWorld();
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < 5; i++) {
                double angle = random.nextDouble(Math.PI * 2);
                double distance = Math.sqrt(random.nextDouble()) * radius * 0.9;
                world.spawnParticle(Particle.DUST, floor.getX() + Math.cos(angle) * distance,
                        floor.getY() + random.nextDouble() * CLOUD_HEIGHT, floor.getZ() + Math.sin(angle) * distance,
                        1, 0.1, 0.1, 0.1, 0, CLOUD_DUST[random.nextInt(CLOUD_DUST.length)]);
            }
            world.spawnParticle(Particle.ENTITY_EFFECT, core, 1, radius * 0.4, 0.5, radius * 0.4, 0, VENOM_GREEN);
        }

        private void drawEdge() {
            World world = floor.getWorld();
            int points = (int) Math.max(12, Math.min(32, radius * 5));
            for (int i = 0; i < points; i++) {
                double angle = i * (Math.PI * 2 / points);
                world.spawnParticle(Particle.DUST, floor.getX() + Math.cos(angle) * radius, floor.getY(),
                        floor.getZ() + Math.sin(angle) * radius, 1, 0, 0.05, 0, 0, CLOUD_EDGE);
            }
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
        sound(origin, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.8f, 1.2f);
        sound(origin, Sound.ENTITY_WITHER_SHOOT, 0.8f, 0.6f);
        loop(0, 1, WAVE_TICKS, tick -> {
            drawWave(origin, radius * (tick + 1) / WAVE_TICKS);
            return true;
        });

        Plague plague = new Plague();
        for (LivingEntity target : List.copyOf(targets)) {
            plague.infect(target, null);
        }
        player().sendActionBar(Text.mm("<" + COLOR_TAG + ">☣ 역병 감염 <white>" + plague.infectedCount()
                + "명</white></" + COLOR_TAG + ">"));
        loop(1, 1, plague.duration + 1, plague);
        return ActivationResult.success();
    }

    private void drawWave(Location origin, double radius) {
        World world = origin.getWorld();
        int points = (int) Math.max(12, Math.min(48, radius * 4));
        double y = origin.getY() + 0.3;
        for (int i = 0; i < points; i++) {
            double angle = i * (Math.PI * 2 / points);
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, origin.getX() + Math.cos(angle) * radius, y,
                    origin.getZ() + Math.sin(angle) * radius, 1, 0, 0.1, 0, 0, PLAGUE_WAVE);
        }
    }

    /**
     * One plague outbreak. Every infection ends when the outbreak does (spread infections inherit the remaining time),
     * and each enemy can be infected only once per outbreak, so the chain always dies out.
     */
    private final class Plague implements LongPredicate {

        private final long duration;
        private final long interval;
        private final double damage;
        private final double spreadRadius;
        private final Map<UUID, Infection> active = new LinkedHashMap<>();
        private final Set<UUID> infectedOnce = new HashSet<>();
        private long now;

        /** One infected enemy: when it caught the plague and when the plague leaves it. */
        private record Infection(LivingEntity target, long start, long end) {
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
            if (!isTargetable(target) || infectedOnce.contains(target.getUniqueId())) {
                return;
            }
            long end = source == null ? now + duration : source.end();
            if (end - now < interval) {
                return; // too little time left to matter
            }
            infectedOnce.add(target.getUniqueId());
            active.put(target.getUniqueId(), new Infection(target, now, end));
            int remaining = (int) Math.min(Integer.MAX_VALUE, end - now);
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, remaining, 0, false, true, true));
            showWither(target, remaining);
            hit(target, damage, DamageType.WITHER);

            Location body = body(target);
            World world = target.getWorld();
            world.spawnParticle(Particle.SQUID_INK, body, 6, 0.3, 0.4, 0.3, 0.02);
            world.spawnParticle(Particle.DUST, body, 6, 0.3, 0.5, 0.3, 0, PLAGUE_DUST);
            sound(body, Sound.ENTITY_ZOMBIE_INFECT, 0.8f, 1.3f + jitter(0.1f));
            if (source != null && source.target().getWorld().equals(world)) {
                world.spawnParticle(Particle.TRAIL, body(source.target()), 8, 0.2, 0.3, 0.2, 0,
                        new Particle.Trail(body, PLAGUE_GREEN, 8));
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
                if (!isTargetable(target) || tick >= infection.end()) {
                    iterator.remove();
                    continue;
                }
                long age = tick - infection.start();
                if (age > 0 && age % interval == 0) {
                    hit(target, damage, DamageType.WITHER);
                }
                if (age > 0 && age % 20 == 0) {
                    showWither(target, (int) Math.min(Integer.MAX_VALUE, infection.end() - tick));
                }
                if (tick % 4 == 0) {
                    Location body = body(target);
                    target.getWorld().spawnParticle(Particle.DUST, body, 2, 0.3, 0.5, 0.3, 0, PLAGUE_DUST);
                    target.getWorld().spawnParticle(Particle.SQUID_INK, body, 1, 0.25, 0.4, 0.25, 0.01);
                }
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
    }

    /** Black hearts without vanilla wither damage (see {@link #WITHER_VISUAL_TICKS}). */
    private static void showWither(LivingEntity target, int remaining) {
        int ticks = Math.min(WITHER_VISUAL_TICKS, remaining);
        if (ticks > 0) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, ticks, 0, false, false, true));
        }
    }

    // ------------------------------------------------------------------ helpers

    private boolean isTargetable(LivingEntity entity) {
        return entity.isValid() && !entity.isDead() && ctx().isEnemy(player(), entity);
    }

    /** Deals credited ability damage. Clears the hurt cooldown first so the tick is never swallowed. */
    private void hit(LivingEntity target, double amount, DamageType type) {
        if (amount <= 0 || !isTargetable(target)) {
            return;
        }
        target.setNoDamageTicks(0);
        ctx().damage(player(), target, amount, type);
    }

    private static Location body(LivingEntity entity) {
        return entity.getLocation().add(0, entity.getHeight() * 0.5, 0);
    }

    /**
     * Runs {@code step} with a 0-based run index every {@code period} ticks after {@code delay}, until it returns
     * false, has run {@code maxRuns} times or the owner is no longer alive. Owner-scoped: cancelled on revoke.
     */
    private void loop(long delay, long period, long maxRuns, LongPredicate step) {
        if (maxRuns <= 0) {
            return;
        }
        BukkitTask[] handle = new BukkitTask[1];
        long[] runs = {0};
        handle[0] = ctx().runTimer(player(), delay, period, () -> {
            long index = runs[0]++;
            boolean more = index < maxRuns && ctx().isAlive(player()) && step.test(index);
            if ((!more || index + 1 >= maxRuns) && handle[0] != null) {
                handle[0].cancel();
            }
        });
    }

    private static void sound(Location at, Sound sound, float volume, float pitch) {
        at.getWorld().playSound(at, sound, SoundCategory.PLAYERS, volume, pitch);
    }

    private static float jitter(float amount) {
        return (ThreadLocalRandom.current().nextFloat() * 2 - 1) * amount;
    }
}
