package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 치유사 — sustain and support. Slowly regenerates, bursts healing light over itself and nearby allies (cleansing
 * harmful effects), and raises a sanctuary that heals allies inside and throws enemies out.
 */
public final class HealerAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("healer", "치유사", Material.GLISTERING_MELON_SLICE)
            .color(TextColor.color(0xFFD866))
            .difficulty(1)
            .passive("재생의 기운", "{regen_interval}초마다 체력을 {regen_amount} 회복합니다.")
            .active("치유의 빛",
                    "나와 반경 {light_radius}블록 안의 아군 체력을 {light_heal} 회복하고 해로운 효과와 불을 없앱니다. "
                            + "치유할 대상이 없으면 쓰이지 않습니다.",
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
    private static final float BEAM_HEIGHT = 5f;
    private static final float BEAM_WIDTH = 0.22f;
    private static final int BEAM_GROW_TICKS = 8;
    private static final double MAX_SPEED = 2.5;

    private static final Color GOLD = Color.fromRGB(0xFFD866);
    private static final Particle.DustOptions LIGHT_DUST = new Particle.DustOptions(GOLD, 1.3f);
    private static final Particle.DustTransition HALO_DUST =
            new Particle.DustTransition(GOLD, Color.fromRGB(255, 255, 240), 1.1f);

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
        if (self.getHealth() >= maxHealth(self) - 0.01) {
            return;
        }
        ctx().heal(self, params().num("regen_amount"));
        self.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, self.getLocation().add(0, 1.0, 0), 3,
                0.3, 0.4, 0.3, 0);
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
            cleanse(target);
            bless(target);
        }

        World world = origin.getWorld();
        world.spawnParticle(Particle.END_ROD, origin.clone().add(0, 1, 0), 20, 0.3, 0.8, 0.3, 0.05);
        world.spawnParticle(Particle.FLASH, origin.clone().add(0, 1, 0), 1, 0, 0, 0, 0, GOLD);
        new Repeater(LIGHT_RING_TICKS, t -> {
            drawRing(origin, radius * (t + 1) / LIGHT_RING_TICKS, Particle.DUST_COLOR_TRANSITION, HALO_DUST, 0);
            return true;
        }, null).start(0, 1);
        sound(origin, Sound.BLOCK_BEACON_POWER_SELECT, 1.0f, 1.6f + jitter(0.05f));
        sound(origin, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.4f, 1.2f + jitter(0.1f));
        sound(origin, Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.8f);

        int allies = needy.contains(player()) ? needy.size() - 1 : needy.size();
        player().sendActionBar(Text.mm(allies > 0
                ? "<#FFD866>✚ 치유의 빛 · 아군 " + allies + "명 치유</#FFD866>"
                : "<#FFD866>✚ 치유의 빛</#FFD866>"));
        return ActivationResult.success();
    }

    private static boolean needsCare(LivingEntity target) {
        if (target.getHealth() < maxHealth(target) - 0.01 || target.getFireTicks() > 0) {
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

    private static void cleanse(LivingEntity target) {
        for (PotionEffect effect : List.copyOf(target.getActivePotionEffects())) {
            if (isHarmful(effect)) {
                target.removePotionEffect(effect.getType());
            }
        }
        if (target.getFireTicks() > 0) {
            target.setFireTicks(0);
        }
        if (target.getFreezeTicks() > 0) {
            target.setFreezeTicks(0);
        }
    }

    /** Hearts and a rising column of light on a healed target, with a light trail from the healer to allies. */
    private void bless(LivingEntity target) {
        World world = target.getWorld();
        Location body = target.getLocation().add(0, target.getHeight() * 0.5, 0);
        world.spawnParticle(Particle.HEART, target.getLocation().add(0, target.getHeight() + 0.4, 0), 3,
                0.35, 0.15, 0.35, 0);
        world.spawnParticle(Particle.END_ROD, body, 8, 0.25, 0.6, 0.25, 0.03);
        world.spawnParticle(Particle.DUST, body, 10, 0.35, 0.5, 0.35, 0, LIGHT_DUST);
        if (!target.equals(player()) && world.equals(player().getWorld())) {
            Location from = player().getLocation().add(0, player().getHeight() * 0.6, 0);
            world.spawnParticle(Particle.TRAIL, from, 8, 0.15, 0.2, 0.15, 0, new Particle.Trail(body, GOLD, 14));
            sound(body, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.3f + jitter(0.1f));
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

        BlockDisplay beam = spawnBeam(center);
        TextDisplay label = spawnLabel(center);
        updateLabel(label, duration);
        sound(center, Sound.BLOCK_BEACON_ACTIVATE, 1.5f, 1.3f);
        sound(center, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.2f, 0.8f);
        sound(center, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 1.0f, 1.4f);
        center.getWorld().spawnParticle(Particle.FLASH, center.clone().add(0, 1, 0), 1, 0, 0, 0, 0, GOLD);

        Map<UUID, Long> lastPush = new HashMap<>();
        long[] nextHeal = {0};
        new Repeater(duration, t -> {
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
            drawSanctum(center, radius, t);
            if (t % 20 == 0) {
                updateLabel(label, duration - t);
            }
            return true;
        }, () -> close(center, beam, label)).start(0, 1);
        return ActivationResult.success();
    }

    /** Heals the owner (when inside) and every ally inside the sanctuary. */
    private void pulse(Location center, double radius, double amount) {
        List<LivingEntity> inside = new ArrayList<>(ctx().alliesNear(player(), center, radius));
        Player self = player();
        if (self.getWorld().equals(center.getWorld()) && distanceToBox(center, self.getBoundingBox()) <= radius) {
            inside.add(self);
        }
        for (LivingEntity target : inside) {
            if (target.getHealth() >= maxHealth(target) - 0.01) {
                continue;
            }
            ctx().heal(target, amount);
            target.getWorld().spawnParticle(Particle.HEART, target.getLocation().add(0, target.getHeight() + 0.3, 0),
                    1, 0.2, 0.1, 0.2, 0);
        }
        sound(center, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, 1.5f + jitter(0.15f));
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
        double factor = knockbackFactor(enemy);
        if (factor > 0.05) {
            double depth = 1 - Math.min(1, distance / radius);
            out.multiply(SANCTUM_PUSH * (0.6 + 0.8 * depth) * factor).setY(SANCTUM_LIFT * factor);
            enemy.setVelocity(clamp(out));
        }
        if (weaknessTicks > 0) {
            enemy.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, weaknessTicks, 0, false, true, true));
        }
        Location body = enemy.getLocation().add(0, enemy.getHeight() * 0.5, 0);
        enemy.getWorld().spawnParticle(Particle.END_ROD, body, 6, 0.2, 0.4, 0.2, 0.08);
        enemy.getWorld().spawnParticle(Particle.DUST, body, 6, 0.3, 0.4, 0.3, 0, LIGHT_DUST);
        sound(body, Sound.ITEM_SHIELD_BLOCK, 0.7f, 1.5f + jitter(0.1f));
    }

    private void drawSanctum(Location center, double radius, long t) {
        World world = center.getWorld();
        // Four bright motes circling the edge, plus a full ring every half second.
        double spin = t * 0.12;
        for (int i = 0; i < 4; i++) {
            double angle = spin + i * (Math.PI / 2);
            world.spawnParticle(Particle.END_ROD, center.getX() + Math.cos(angle) * radius, center.getY() + 0.3,
                    center.getZ() + Math.sin(angle) * radius, 1, 0, 0, 0, 0);
        }
        if (t % 10 == 0) {
            drawRing(center, radius, Particle.DUST, LIGHT_DUST, 0.15);
        }
        if (t % 4 == 0) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < 3; i++) {
                double distance = Math.sqrt(random.nextDouble()) * radius;
                double angle = random.nextDouble() * Math.PI * 2;
                // count 0: the offsets are a direction, so the mote drifts straight up
                world.spawnParticle(Particle.END_ROD, center.getX() + Math.cos(angle) * distance, center.getY() + 0.2,
                        center.getZ() + Math.sin(angle) * distance, 0, 0, 1, 0, 0.06);
            }
        }
    }

    /** A thin golden pillar of light that grows out of the ground. */
    private BlockDisplay spawnBeam(Location center) {
        Location at = center.clone();
        at.setYaw(0);
        at.setPitch(0);
        BlockDisplay beam = ctx().track(player(), center.getWorld().spawn(at, BlockDisplay.class, display -> {
            display.setPersistent(false);
            display.setBlock(Material.YELLOW_STAINED_GLASS.createBlockData());
            display.setBrightness(new Display.Brightness(15, 15));
            display.setTransformation(beamShape(0.01f));
        }));
        ctx().runLater(player(), 2, () -> {
            if (beam.isValid()) {
                beam.setInterpolationDelay(0);
                beam.setInterpolationDuration(BEAM_GROW_TICKS);
                beam.setTransformation(beamShape(BEAM_HEIGHT));
            }
        });
        return beam;
    }

    private static Transformation beamShape(float height) {
        return new Transformation(new Vector3f(-BEAM_WIDTH / 2, 0, -BEAM_WIDTH / 2), new Quaternionf(),
                new Vector3f(BEAM_WIDTH, height, BEAM_WIDTH), new Quaternionf());
    }

    private TextDisplay spawnLabel(Location center) {
        Location at = center.clone().add(0, BEAM_HEIGHT + 0.6, 0);
        return ctx().track(player(), center.getWorld().spawn(at, TextDisplay.class, display -> {
            display.setPersistent(false);
            display.setBillboard(Display.Billboard.CENTER);
            display.setShadowed(true);
            display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            display.setBrightness(new Display.Brightness(15, 15));
        }));
    }

    private static void updateLabel(TextDisplay label, long ticksLeft) {
        if (!label.isValid()) {
            return;
        }
        long seconds = Math.max(0, (ticksLeft + 19) / 20);
        label.text(Text.mm("<#FFD866><bold>✚ 성역</bold></#FFD866> <white>" + seconds + "</white>"));
    }

    /** Shrinks the pillar back into the ground and removes the sanctuary's displays. */
    private void close(Location center, BlockDisplay beam, TextDisplay label) {
        if (label.isValid()) {
            label.remove();
        }
        center.getWorld().spawnParticle(Particle.END_ROD, center.clone().add(0, 1.5, 0), 20, 0.3, 1.2, 0.3, 0.06);
        sound(center, Sound.BLOCK_BEACON_DEACTIVATE, 1.2f, 1.2f);
        if (!beam.isValid()) {
            return;
        }
        beam.setInterpolationDelay(0);
        beam.setInterpolationDuration(BEAM_GROW_TICKS);
        beam.setTransformation(beamShape(0.01f));
        ctx().runLater(player(), BEAM_GROW_TICKS, () -> {
            if (beam.isValid()) {
                beam.remove();
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    /** The first solid surface at most a few blocks below {@code point}, or the point itself. */
    private static Location ground(Location point) {
        World world = point.getWorld();
        RayTraceResult down = world.rayTraceBlocks(point.clone().add(0, 0.2, 0), new Vector(0, -1, 0), 6,
                FluidCollisionMode.NEVER, true);
        return down == null ? point.clone() : down.getHitPosition().toLocation(world);
    }

    private static <T> void drawRing(Location center, double radius, Particle particle, T data, double lift) {
        World world = center.getWorld();
        int points = (int) Math.max(12, Math.min(40, radius * 6));
        double y = center.getY() + 0.15 + lift;
        for (int i = 0; i < points; i++) {
            double angle = i * (Math.PI * 2 / points);
            world.spawnParticle(particle, center.getX() + Math.cos(angle) * radius, y,
                    center.getZ() + Math.sin(angle) * radius, 1, 0, 0, 0, 0, data);
        }
    }

    /** Distance from a point to the closest point of a box (0 inside), matching how allies are found. */
    private static double distanceToBox(Location point, BoundingBox box) {
        double dx = Math.max(Math.max(box.getMinX() - point.getX(), 0), point.getX() - box.getMaxX());
        double dy = Math.max(Math.max(box.getMinY() - point.getY(), 0), point.getY() - box.getMaxY());
        double dz = Math.max(Math.max(box.getMinZ() - point.getZ(), 0), point.getZ() - box.getMaxZ());
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double maxHealth(LivingEntity entity) {
        AttributeInstance instance = entity.getAttribute(Attribute.MAX_HEALTH);
        return instance == null ? 20.0 : instance.getValue();
    }

    /** 1 minus the target's knockback resistance, so tanky abilities keep their identity. */
    private static double knockbackFactor(LivingEntity target) {
        AttributeInstance resistance = target.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
        return resistance == null ? 1.0 : Math.max(0.0, 1.0 - resistance.getValue());
    }

    private static Vector clamp(Vector velocity) {
        return velocity.lengthSquared() > MAX_SPEED * MAX_SPEED ? velocity.normalize().multiply(MAX_SPEED) : velocity;
    }

    private static void sound(Location at, Sound sound, float volume, float pitch) {
        at.getWorld().playSound(at, sound, SoundCategory.PLAYERS, volume, pitch);
    }

    private static float jitter(float amount) {
        return (ThreadLocalRandom.current().nextFloat() * 2 - 1) * amount;
    }

    /**
     * Runs a step with a 0-based index every period until it returns false, has run {@code maxRuns} times or the
     * owner is no longer alive; {@code onEnd} then runs once. Owner-scoped (cancelled on revoke) and fail-safe: a
     * step that throws stops the loop instead of failing every tick.
     */
    private final class Repeater implements Runnable {
        private final long maxRuns;
        private final LongPredicate step;
        private final @Nullable Runnable onEnd;
        private @Nullable BukkitTask task;
        private long runs;
        private boolean finished;

        Repeater(long maxRuns, LongPredicate step, @Nullable Runnable onEnd) {
            this.maxRuns = maxRuns;
            this.step = step;
            this.onEnd = onEnd;
        }

        void start(long delay, long period) {
            if (maxRuns <= 0) {
                finish();
                return;
            }
            task = ctx().runTimer(player(), delay, period, this);
            if (finished) {
                task.cancel();
            }
        }

        @Override
        public void run() {
            if (finished) {
                return;
            }
            long index = runs++;
            boolean more;
            try {
                more = ctx().isAlive(player()) && step.test(index);
            } catch (RuntimeException e) {
                ctx().plugin().getLogger().log(Level.WARNING, "Healer ability task failed", e);
                more = false;
            }
            if (!more || index + 1 >= maxRuns) {
                finish();
            }
        }

        private void finish() {
            finished = true;
            if (task != null) {
                task.cancel();
            }
            if (onEnd != null) {
                onEnd.run();
            }
        }
    }
}
