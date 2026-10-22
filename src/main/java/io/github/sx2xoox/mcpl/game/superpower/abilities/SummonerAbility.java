package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Sittable;
import org.bukkit.entity.Wolf;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 소환사 — fights through a pack. Kills raise a spirit wolf, the active calls a pair of wolves and the ultimate drops
 * an iron golem that slams the ground on arrival.
 *
 * <p>Every summon is driven by its own owner-scoped controller task (targeting, following, lifetime, name tag), so
 * the ultimate also works when borrowed. The owner's own hits mark a focus target that every summon switches to.
 */
public final class SummonerAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("summoner", "소환사", Material.BONE)
            .color(TextColor.color(0xB48CFF))
            .difficulty(1)
            .passive("사냥의 부름",
                    "적을 처치하면 그 자리에서 영혼 늑대가 깨어나 {kill_wolf_duration}초간 함께 싸웁니다. "
                            + "소환수는 내가 마지막으로 공격한 적을 우선 노립니다.")
            .active("늑대 무리",
                    "늑대 {wolf_count}마리(체력 {wolf_health}, 공격력 {wolf_damage})를 {wolf_duration}초간 소환합니다. "
                            + "늑대는 최대 {max_wolves}마리까지 유지됩니다.",
                    15)
            .ultimate("철골렘",
                    "철골렘(체력 {golem_health}, 공격력 {golem_damage})을 {golem_duration}초간 소환합니다. "
                            + "착지 충격으로 반경 {golem_slam_radius}블록의 적에게 {golem_slam_damage} 피해를 주고 띄웁니다.")
            .param("kill_wolf_duration", 30)
            .param("wolf_count", 2)
            .param("wolf_duration", 20)
            .param("wolf_health", 16)
            .param("wolf_damage", 3)
            .param("max_wolves", 6)
            .param("golem_duration", 25)
            .param("golem_health", 80)
            .param("golem_damage", 7)
            .param("golem_slam_radius", 3)
            .param("golem_slam_damage", 3)
            .param("aggro_range", 16)
            .build();

    /** Ticks between two controller updates of a summon. */
    private static final long CONTROL_PERIOD = 5;
    /** How long the owner's last hit stays the pack's focus target. */
    private static final long FOCUS_TICKS = 100;
    /** A summon keeps chasing its target up to this multiple of the aggro range. */
    private static final double LEASH_FACTOR = 1.5;
    /** The golem walks back to the owner beyond this distance and is brought back beyond the second one. */
    private static final double GOLEM_FOLLOW_DISTANCE = 6;
    private static final double GOLEM_RECALL_DISTANCE = 28;

    private static final double WOLF_WIDTH = 0.6;
    private static final double WOLF_HEIGHT = 0.85;
    private static final double GOLEM_SCALE = 1.15;
    private static final double GOLEM_WIDTH = 1.4 * GOLEM_SCALE;
    private static final double GOLEM_HEIGHT = 2.7 * GOLEM_SCALE;
    /** Vanilla iron golems walk at 0.25; slightly faster so it can keep up with a fight. */
    private static final double GOLEM_SPEED = 0.3;
    private static final double MAX_SPEED = 2.5;

    private static final TextColor NAME_COLOR = TextColor.color(0xB48CFF);
    private static final TextColor SPIRIT_COLOR = TextColor.color(0x8FE3FF);
    private static final Particle.DustOptions SUMMON_DUST = new Particle.DustOptions(Color.fromRGB(0xB48CFF), 1.2f);
    private static final Particle.DustOptions IRON_DUST = new Particle.DustOptions(Color.fromRGB(0xD8D8D8), 1.5f);

    /** Alive summons, oldest first. */
    private final List<Summon> summons = new ArrayList<>();
    private @Nullable LivingEntity focus;
    private long focusUntil;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    // ------------------------------------------------------------------ passive: call of the hunt

    @Override
    public void onKill(Player victim) {
        Location death = victim.getLocation();
        Location spot = death.getWorld().equals(player().getWorld()) ? findSpot(death, 0, WOLF_WIDTH, WOLF_HEIGHT, 0)
                : null;
        if (spot == null) {
            spot = findSpot(player().getLocation(), 1.5, WOLF_WIDTH, WOLF_HEIGHT, 180);
        }
        if (spot == null) {
            spot = player().getLocation();
        }
        Wolf wolf = summonWolf(spot, params().ticks("kill_wolf_duration"), true);
        if (wolf == null) {
            return;
        }
        World world = spot.getWorld();
        world.spawnParticle(Particle.SOUL, spot.clone().add(0, 0.5, 0), 16, 0.4, 0.5, 0.4, 0.04);
        world.spawnParticle(Particle.SCULK_SOUL, spot.clone().add(0, 0.3, 0), 8, 0.3, 0.2, 0.3, 0.03);
        sound(spot, Sound.ENTITY_WOLF_GROWL, 1.0f, 0.6f + jitter(0.05f));
        sound(spot, Sound.PARTICLE_SOUL_ESCAPE, 1.5f, 0.8f);
        player().sendActionBar(Text.mm("<#8FE3FF>영혼 늑대가 깨어났습니다</#8FE3FF>"));
    }

    @Override
    public void onRevoke() {
        // The framework removes the summoned mobs; only forget them here.
        for (Summon summon : summons) {
            summon.dismissed = true;
        }
        summons.clear();
        focus = null;
    }

    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        Entity damager = event.getDamager();
        Summon summon = find(damager);
        if (summon != null) {
            // Keep summon hits at their advertised strength regardless of vanilla randomness or difficulty.
            if (!ctx().isAbilityDamage()) {
                event.setDamage(summon.damage);
            }
            return;
        }
        if (damager.equals(player()) || (damager instanceof Projectile projectile
                && player().equals(projectile.getShooter()))) {
            focus = victim;
            focusUntil = ctx().currentTick() + FOCUS_TICKS;
        }
    }

    // ------------------------------------------------------------------ active: wolf pack

    @Override
    public ActivationResult onActive() {
        int count = Math.max(1, params().integer("wolf_count"));
        long lifetime = params().ticks("wolf_duration");
        Location origin = player().getLocation();
        int summoned = 0;
        for (int i = 0; i < count; i++) {
            // Flank the owner: left, right, then further back on each side.
            double angle = (i % 2 == 0 ? 1 : -1) * (70 + (i / 2) * 25.0);
            Location spot = findSpot(origin, 1.8, WOLF_WIDTH, WOLF_HEIGHT, angle);
            if (spot != null && summonWolf(spot, lifetime, false) != null) {
                summoned++;
                spot.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, spot.clone().add(0, 0.4, 0), 12,
                        0.3, 0.3, 0.3, 0.03);
            }
        }
        if (summoned == 0) {
            return ActivationResult.fail("늑대를 부를 공간이 없습니다");
        }
        drawCircle(origin, 1.4, SUMMON_DUST);
        origin.getWorld().spawnParticle(Particle.ENCHANT, origin.clone().add(0, 1, 0), 30, 0.6, 0.5, 0.6, 0.6);
        sound(origin, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1.0f, 1.2f + jitter(0.05f));
        sound(origin, Sound.ENTITY_WOLF_ANGRY_GROWL, 1.0f, 0.9f + jitter(0.1f));
        player().sendActionBar(Text.mm("<#B48CFF>늑대 " + summoned + "마리 소환 · 현재 " + wolfCount() + "마리</#B48CFF>"));
        return ActivationResult.success();
    }

    private @Nullable Wolf summonWolf(Location at, long lifetime, boolean spirit) {
        double health = Math.max(1, params().num("wolf_health"));
        double damage = Math.max(0, params().num("wolf_damage"));
        int limit = Math.max(1, params().integer("max_wolves"));
        while (wolfCount() >= limit) {
            Summon oldest = summons.stream().filter(s -> !s.golem).findFirst().orElse(null);
            if (oldest == null) {
                break;
            }
            dismiss(oldest, true);
        }

        Wolf wolf = at.getWorld().spawn(at, Wolf.class, spawned -> {
            spawned.setPersistent(false);
            spawned.setRemoveWhenFarAway(false);
            spawned.setCanPickupItems(false);
            spawned.setAdult();
            // Taming resets max health to the vanilla value, so the stats are applied afterwards.
            spawned.setTamed(true);
            spawned.setOwner(player());
            spawned.setSitting(false);
            spawned.setVariant(spirit ? Wolf.Variant.PALE : Wolf.Variant.BLACK);
            spawned.setCollarColor(spirit ? DyeColor.LIGHT_BLUE : DyeColor.PURPLE);
            setBase(spawned, Attribute.MAX_HEALTH, health);
            setBase(spawned, Attribute.ATTACK_DAMAGE, damage);
            spawned.setHealth(Math.min(health, maxHealth(spawned)));
            spawned.setCustomNameVisible(true);
        });
        if (!wolf.isValid()) {
            return null;
        }
        ctx().summon(player(), wolf);
        Summon summon = new Summon(wolf, spirit ? "영혼 늑대" : "늑대", damage, lifetime, false, spirit);
        summons.add(summon);
        summon.start();
        return wolf;
    }

    private int wolfCount() {
        int count = 0;
        for (Summon summon : summons) {
            if (!summon.golem) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ ultimate: iron golem

    @Override
    public ActivationResult onUltimate() {
        Location spot = findSpot(player().getLocation(), 2.5, GOLEM_WIDTH, GOLEM_HEIGHT, 0);
        if (spot == null) {
            return ActivationResult.fail("골렘을 부를 공간이 없습니다");
        }
        double health = Math.max(1, params().num("golem_health"));
        double damage = Math.max(0, params().num("golem_damage"));
        IronGolem golem = spot.getWorld().spawn(spot, IronGolem.class, spawned -> {
            spawned.setPersistent(false);
            spawned.setRemoveWhenFarAway(false);
            spawned.setPlayerCreated(false); // player-made golems refuse to attack players
            setBase(spawned, Attribute.MAX_HEALTH, health);
            setBase(spawned, Attribute.ATTACK_DAMAGE, damage);
            setBase(spawned, Attribute.SCALE, GOLEM_SCALE);
            setBase(spawned, Attribute.MOVEMENT_SPEED, GOLEM_SPEED);
            spawned.setHealth(Math.min(health, maxHealth(spawned)));
            spawned.setCustomNameVisible(true);
        });
        if (!golem.isValid()) {
            return ActivationResult.fail("골렘을 부를 수 없습니다");
        }
        ctx().summon(player(), golem);
        Summon summon = new Summon(golem, "철골렘", damage, params().ticks("golem_duration"), true, false);
        summons.add(summon);
        summon.start();
        slam(spot);
        return ActivationResult.success();
    }

    /** The golem's arrival: a ground shockwave that hurts and lifts nearby enemies. */
    private void slam(Location spot) {
        World world = spot.getWorld();
        double radius = Math.max(0.5, params().num("golem_slam_radius"));
        double damage = params().num("golem_slam_damage");
        world.spawnParticle(Particle.BLOCK, spot.clone().add(0, 0.2, 0), 60, radius * 0.4, 0.1, radius * 0.4, 0.15,
                Material.IRON_BLOCK.createBlockData());
        world.spawnParticle(Particle.EXPLOSION, spot.clone().add(0, 0.5, 0), 2, 0.4, 0.1, 0.4, 0);
        world.spawnParticle(Particle.DUST_PILLAR, spot.clone().add(0, 0.1, 0), 30, radius * 0.5, 0, radius * 0.5, 0.2,
                Material.IRON_BLOCK.createBlockData());
        drawCircle(spot, radius, IRON_DUST);
        sound(spot, Sound.BLOCK_ANVIL_LAND, 1.3f, 0.6f + jitter(0.05f));
        sound(spot, Sound.ENTITY_IRON_GOLEM_REPAIR, 1.3f, 0.8f);
        sound(spot, Sound.ENTITY_IRON_GOLEM_ATTACK, 1.2f, 0.7f);

        for (LivingEntity target : ctx().enemiesNear(player(), spot, radius)) {
            if (damage > 0) {
                target.setNoDamageTicks(0);
                ctx().damage(player(), target, damage);
            }
            if (!target.isValid() || target.isDead()) {
                continue;
            }
            Vector away = target.getLocation().toVector().subtract(spot.toVector()).setY(0);
            if (away.lengthSquared() > 1.0E-4) {
                away.normalize().multiply(0.4);
            }
            away.setY(0.6);
            target.setVelocity(clamp(target.getVelocity().add(away.multiply(knockbackFactor(target)))));
        }
    }

    // ------------------------------------------------------------------ summons

    /** One summoned mob and the controller task that keeps it fighting until its time runs out. */
    private final class Summon {
        private final Mob mob;
        private final String label;
        private final double damage;
        private final long expiresAt;
        private final boolean golem;
        private final boolean spirit;
        private boolean dismissed;

        Summon(Mob mob, String label, double damage, long lifetime, boolean golem, boolean spirit) {
            this.mob = mob;
            this.label = label;
            this.damage = damage;
            this.expiresAt = ctx().currentTick() + Math.max(1, lifetime);
            this.golem = golem;
            this.spirit = spirit;
        }

        void start() {
            updateName(expiresAt - ctx().currentTick());
            long runs = (expiresAt - ctx().currentTick()) / CONTROL_PERIOD + 2;
            new Repeater(runs, this::control, () -> dismiss(this, true)).start(CONTROL_PERIOD, CONTROL_PERIOD);
        }

        private boolean control(long run) {
            if (dismissed) {
                return false;
            }
            if (!mob.isValid() || mob.isDead()) {
                summons.remove(this);
                dismissed = true;
                return false;
            }
            long left = expiresAt - ctx().currentTick();
            if (left <= 0) {
                return false;
            }
            if (run % (20 / CONTROL_PERIOD) == 0) {
                updateName(left);
            }
            if (mob instanceof Sittable sittable && sittable.isSitting()) {
                sittable.setSitting(false);
            }
            LivingEntity target = chooseTarget();
            if (target != mob.getTarget()) {
                mob.setTarget(target);
            }
            if (target == null && golem) {
                followOwner();
            }
            if (run % 4 == 0) {
                Location body = mob.getLocation().add(0, mob.getHeight() * 0.5, 0);
                if (spirit) {
                    mob.getWorld().spawnParticle(Particle.SOUL, body, 1, 0.2, 0.2, 0.2, 0.01);
                } else {
                    mob.getWorld().spawnParticle(Particle.DUST, body, 1, 0.25, 0.3, 0.25, 0, SUMMON_DUST);
                }
            }
            return true;
        }

        private @Nullable LivingEntity chooseTarget() {
            double range = Math.max(1, params().num("aggro_range"));
            double leash = range * LEASH_FACTOR;
            LivingEntity focused = focus;
            if (focused != null && ctx().currentTick() <= focusUntil && isValidTarget(focused, leash)) {
                return focused;
            }
            LivingEntity current = mob.getTarget();
            if (current != null && isValidTarget(current, leash)) {
                return current;
            }
            List<LivingEntity> nearby = ctx().enemiesNear(player(), mob.getLocation(), range);
            return nearby.isEmpty() ? null : nearby.getFirst();
        }

        private boolean isValidTarget(LivingEntity target, double leash) {
            return target.isValid() && !target.isDead() && target.getWorld().equals(mob.getWorld())
                    && ctx().isEnemy(player(), target)
                    && target.getLocation().distanceSquared(mob.getLocation()) <= leash * leash;
        }

        private void followOwner() {
            Player owner = player();
            if (!owner.getWorld().equals(mob.getWorld())
                    || owner.getLocation().distanceSquared(mob.getLocation()) > GOLEM_RECALL_DISTANCE * GOLEM_RECALL_DISTANCE) {
                Location spot = findSpot(owner.getLocation(), 2.5, GOLEM_WIDTH, GOLEM_HEIGHT, 180);
                if (spot != null) {
                    poof(mob.getLocation());
                    mob.teleport(spot);
                    poof(spot);
                }
                return;
            }
            if (owner.getLocation().distanceSquared(mob.getLocation()) > GOLEM_FOLLOW_DISTANCE * GOLEM_FOLLOW_DISTANCE) {
                mob.getPathfinder().moveTo(owner, 1.0);
            }
        }

        private void updateName(long ticksLeft) {
            long seconds = Math.max(0, (ticksLeft + 19) / 20);
            mob.customName(Component.text(player().getName() + "의 " + label, spirit ? SPIRIT_COLOR : NAME_COLOR)
                    .append(Component.text(" " + seconds + "초", NamedTextColor.GRAY)));
        }
    }

    private @Nullable Summon find(Entity entity) {
        for (Summon summon : summons) {
            if (summon.mob.equals(entity)) {
                return summon;
            }
        }
        return null;
    }

    /** Removes a summon with a puff of smoke. Safe to call more than once. */
    private void dismiss(Summon summon, boolean effects) {
        summons.remove(summon);
        if (summon.dismissed) {
            return;
        }
        summon.dismissed = true;
        Mob mob = summon.mob;
        if (!mob.isValid()) {
            return;
        }
        if (effects) {
            Location at = mob.getLocation();
            poof(at);
            sound(at, summon.golem ? Sound.ENTITY_IRON_GOLEM_DEATH : Sound.ENTITY_WOLF_WHINE, 0.8f,
                    1.0f + jitter(0.1f));
        }
        mob.remove();
    }

    private static void poof(Location at) {
        World world = at.getWorld();
        world.spawnParticle(Particle.POOF, at.clone().add(0, 0.5, 0), 12, 0.3, 0.4, 0.3, 0.03);
        world.spawnParticle(Particle.SOUL, at.clone().add(0, 0.5, 0), 4, 0.3, 0.3, 0.3, 0.02);
    }

    // ------------------------------------------------------------------ placement

    /**
     * A free spot for a {@code width} x {@code height} mob around {@code center}: first {@code distance} blocks in
     * the facing direction turned by {@code firstAngle} degrees, then the other directions, then the center itself.
     * Each candidate must stand on the ground, be free of blocks and be visible from the center (never through a
     * wall). Null if nothing fits.
     */
    private static @Nullable Location findSpot(Location center, double distance, double width, double height,
                                               double firstAngle) {
        World world = center.getWorld();
        Vector forward = center.getDirection().setY(0);
        if (forward.lengthSquared() < 1.0E-4) {
            forward = new Vector(0, 0, 1);
        }
        forward.normalize();
        Location eye = center.clone().add(0, 1.0, 0);
        int directions = distance <= 0 ? 0 : 8;
        for (int i = 0; i <= directions; i++) {
            Location candidate;
            if (i == directions) {
                candidate = center.clone();
            } else {
                double turn = firstAngle + (i % 2 == 0 ? 1 : -1) * ((i + 1) / 2) * 45.0;
                candidate = center.clone().add(forward.clone().rotateAroundY(Math.toRadians(-turn)).multiply(distance));
            }
            Location ground = groundBelow(world, candidate, 3);
            if (ground == null || !fits(world, ground, width, height)) {
                continue;
            }
            if (i != directions && !clearLine(eye, ground.clone().add(0, Math.min(1.0, height * 0.5), 0))) {
                continue;
            }
            ground.setYaw(center.getYaw());
            ground.setPitch(0);
            return ground;
        }
        return null;
    }

    private static @Nullable Location groundBelow(World world, Location point, double maxDrop) {
        Location start = point.clone().add(0, 1.0, 0);
        RayTraceResult down = world.rayTraceBlocks(start, new Vector(0, -1, 0), maxDrop + 1.0,
                FluidCollisionMode.NEVER, true);
        return down == null ? null : down.getHitPosition().toLocation(world).add(0, 0.01, 0);
    }

    private static boolean fits(World world, Location feet, double width, double height) {
        double half = width / 2;
        BoundingBox box = new BoundingBox(feet.getX() - half, feet.getY() + 0.02, feet.getZ() - half,
                feet.getX() + half, feet.getY() + height, feet.getZ() + half);
        return !world.hasCollisionsIn(box);
    }

    private static boolean clearLine(Location from, Location to) {
        Vector between = to.toVector().subtract(from.toVector());
        double distance = between.length();
        if (distance < 0.1) {
            return true;
        }
        return from.getWorld().rayTraceBlocks(from, between.multiply(1 / distance), distance,
                FluidCollisionMode.NEVER, true) == null;
    }

    // ------------------------------------------------------------------ helpers

    private static void drawCircle(Location center, double radius, Particle.DustOptions dust) {
        World world = center.getWorld();
        int count = (int) Math.max(12, Math.min(40, radius * 8));
        double y = center.getY() + 0.15;
        for (int i = 0; i < count; i++) {
            double angle = i * (Math.PI * 2 / count);
            world.spawnParticle(Particle.DUST, center.getX() + Math.cos(angle) * radius, y,
                    center.getZ() + Math.sin(angle) * radius, 1, 0, 0, 0, 0, dust);
        }
    }

    private static void setBase(LivingEntity entity, Attribute attribute, double value) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
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
                ctx().plugin().getLogger().log(Level.WARNING, "Summoner ability task failed", e);
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
