package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;

import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.tag.DamageTypeTags;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 거울술사 — turns the enemy's strength against them. Bends incoming projectiles, raises a mirror shield that blunts
 * damage and throws part of it back at the attacker, and as the ultimate trades places with an aimed enemy and
 * reflects everything it takes for a while.
 */
public final class MirrorAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("mirror", "거울술사", Material.AMETHYST_SHARD)
            .color(TextColor.color(0xC8F4FF))
            .difficulty(3)
            .passive("굴절",
                    "받는 투사체 피해가 {projectile_reduction}% 줄어듭니다.")
            .active("반사막",
                    "{shield_duration}초간 거울 조각을 두릅니다. 받는 피해가 {shield_reduction}% 줄고, "
                            + "공격자에게 원래 피해의 {shield_reflect}%를 되돌려줍니다.",
                    10)
            .ultimate("거울 세계",
                    "조준한 적({ult_range}블록 이내)과 위치를 맞바꾸고, {ult_duration}초간 받는 피해의 "
                            + "{ult_reflect}%를 공격자에게 되돌려줍니다.")
            .ultimateNotStandalone()
            .param("projectile_reduction", 25)
            .param("shield_duration", 3)
            .param("shield_reduction", 50)
            .param("shield_reflect", 60)
            .param("ult_range", 25)
            .param("ult_duration", 6)
            .param("ult_reflect", 100)
            .build();

    private static final String COLOR_TAG = "#C8F4FF";
    private static final Color GLASS_WHITE = Color.fromRGB(0xF2, 0xFB, 0xFF);
    private static final Color GLASS_CYAN = Color.fromRGB(0x7F, 0xD8, 0xFF);
    private static final Particle.DustOptions GLINT_DUST = new Particle.DustOptions(GLASS_WHITE, 0.8f);
    private static final Particle.DustTransition BEAM_DUST = new Particle.DustTransition(GLASS_WHITE, GLASS_CYAN, 0.9f);
    private static final Particle.DustTransition SWAP_DUST =
            new Particle.DustTransition(GLASS_WHITE, Color.fromRGB(0xC9, 0xA6, 0xFF), 1.1f);
    private static final BlockData GLASS = Material.GLASS.createBlockData();

    /** Reflections smaller than this are not worth a hit (and a hurt animation). */
    private static final double MIN_REFLECT = 0.1;
    private static final double BEAM_SPACING = 0.5;
    private static final int MAX_BEAM_POINTS = 48;
    /** How long a swap into mid-air protects the owner from the resulting fall damage. */
    private static final long FALL_GRACE_TICKS = 60;

    private static final int SHIELD_SHARDS = 5;
    private static final double SHIELD_RADIUS = 1.0;
    private static final double SHIELD_SPIN = 0.5;
    private static final int WORLD_SHARDS = 8;
    private static final double WORLD_RADIUS = 1.45;
    private static final double WORLD_SPIN = -0.3;
    /** Ticks between two shard ring updates; display teleports are interpolated over the same time. */
    private static final int RING_PERIOD = 2;

    /**
     * True while any mirror deals a reflection, so reflected damage is never reflected again (two mirrors would
     * otherwise bounce the same hit back and forth). Everything runs on the main thread.
     */
    private static boolean reflecting;

    private long shieldUntil = -1;
    private long mirrorWorldUntil = -1;
    private long fallGraceUntil = -1;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        shieldUntil = -1;
        mirrorWorldUntil = -1;
        fallGraceUntil = -1;
    }

    // ------------------------------------------------------------------ passive + reflection

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled()) {
            return;
        }
        long now = ctx().currentTick();
        if (now < fallGraceUntil && isFall(event)) {
            event.setCancelled(true);
            fallGraceUntil = -1;
            return;
        }
        if (isProjectile(event)) {
            double reduction = percent("projectile_reduction");
            if (reduction > 0) {
                event.setDamage(event.getDamage() * (1 - reduction));
                glint();
            }
        }

        boolean shield = now < shieldUntil;
        boolean mirrorWorld = now < mirrorWorldUntil;
        if (!shield && !mirrorWorld || isUnblockable(event)) {
            return;
        }
        double incoming = event.getFinalDamage();
        if (shield) {
            event.setDamage(event.getDamage() * (1 - percent("shield_reduction")));
        }
        if (reflecting || incoming <= 0) {
            return;
        }
        LivingEntity attacker = attackerOf(event);
        if (attacker == null) {
            return;
        }
        double fraction = Math.max(shield ? percent("shield_reflect") : 0, mirrorWorld ? percent("ult_reflect") : 0);
        double amount = incoming * fraction;
        if (amount >= MIN_REFLECT) {
            // Reflect on the next tick: never nest a second damage event inside this one.
            ctx().runLater(player(), 1, () -> reflect(attacker, amount));
        }
    }

    private void reflect(LivingEntity attacker, double amount) {
        Player owner = player();
        if (!isTargetable(attacker) || !ctx().isAlive(owner) || !attacker.getWorld().equals(owner.getWorld())) {
            return;
        }
        reflecting = true;
        try {
            attacker.setNoDamageTicks(0);
            ctx().damage(owner, attacker, amount);
        } finally {
            reflecting = false;
        }
        Location from = body(owner);
        Location to = body(attacker);
        beam(from, to);
        World world = attacker.getWorld();
        world.spawnParticle(Particle.END_ROD, to, 8, 0.25, 0.35, 0.25, 0.05);
        world.spawnParticle(Particle.BLOCK, to, 8, 0.25, 0.35, 0.25, 0.1, GLASS);
        sound(to, Sound.BLOCK_AMETHYST_CLUSTER_HIT, 1.0f, 1.5f + jitter(0.1f));
        sound(from, Sound.BLOCK_GLASS_HIT, 0.8f, 1.8f);
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">◈ 반사 <white>" + AbilityInfo.formatNumber(amount)
                + "</white></" + COLOR_TAG + ">"));
    }

    /** The enemy to reflect to: the living damager, the shooter of a projectile, or the responsible participant. */
    private @Nullable LivingEntity attackerOf(EntityDamageEvent event) {
        Entity direct = event instanceof EntityDamageByEntityEvent byEntity
                ? byEntity.getDamager()
                : event.getDamageSource().getDirectEntity();
        Entity causing = event.getDamageSource().getCausingEntity();
        if (direct instanceof LivingEntity living && isTargetable(living)) {
            return living;
        }
        if (direct instanceof Projectile projectile && projectile.getShooter() instanceof LivingEntity shooter
                && isTargetable(shooter)) {
            return shooter;
        }
        if (causing instanceof LivingEntity living && isTargetable(living)) {
            return living;
        }
        Player responsible = ctx().resolveAttacker(direct != null ? direct : causing);
        return responsible != null && isTargetable(responsible) ? responsible : null;
    }

    private void glint() {
        Location body = body(player());
        player().getWorld().spawnParticle(Particle.DUST, body, 5, 0.35, 0.45, 0.35, 0, GLINT_DUST);
        player().getWorld().spawnParticle(Particle.END_ROD, body, 2, 0.3, 0.4, 0.3, 0.02);
        sound(body, Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.7f, 1.8f + jitter(0.1f));
    }

    // ------------------------------------------------------------------ active: mirror shield

    @Override
    public ActivationResult onActive() {
        long ticks = Math.max(1, params().ticks("shield_duration"));
        shieldUntil = ctx().currentTick() + ticks;
        new ShardRing(SHIELD_SHARDS, Material.LIGHT_BLUE_STAINED_GLASS_PANE, SHIELD_RADIUS, SHIELD_SPIN, ticks).start();

        Location body = body(player());
        player().getWorld().spawnParticle(Particle.END_ROD, body, 16, 0.5, 0.6, 0.5, 0.06);
        sound(body, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.2f, 1.4f);
        sound(body, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.8f, 1.6f + jitter(0.05f));
        return ActivationResult.success();
    }

    // ------------------------------------------------------------------ ultimate: mirror world

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        LivingEntity target = ctx().findTarget(owner, params().num("ult_range"));
        if (target == null) {
            return ActivationResult.fail("조준한 곳에 적이 없습니다");
        }
        Location ownerSpot = owner.getLocation();
        Location targetSpot = target.getLocation();
        if (!target.getWorld().equals(owner.getWorld()) || !fits(owner, targetSpot) || !fits(target, ownerSpot)) {
            return ActivationResult.fail("위치를 바꿀 공간이 없습니다");
        }

        // The owner arrives facing the enemy; the enemy keeps its own facing and has to find the owner again.
        Location ownerDestination = targetSpot.clone();
        Vector enemyBodyAfter = ownerSpot.toVector().add(new Vector(0, target.getHeight() * 0.5, 0));
        Vector ownerEyeAfter = targetSpot.toVector().add(new Vector(0, owner.getEyeHeight(), 0));
        Vector look = enemyBodyAfter.subtract(ownerEyeAfter);
        if (look.lengthSquared() > 1.0E-6) {
            ownerDestination.setDirection(look);
        }
        Location targetDestination = ownerSpot.clone();
        targetDestination.setYaw(targetSpot.getYaw());
        targetDestination.setPitch(targetSpot.getPitch());

        if (!target.teleport(targetDestination, TeleportCause.PLUGIN)) {
            return ActivationResult.fail("지금은 위치를 바꿀 수 없습니다");
        }
        if (!owner.teleport(ownerDestination, TeleportCause.PLUGIN)) {
            target.teleport(targetSpot, TeleportCause.PLUGIN);
            return ActivationResult.fail("지금은 위치를 바꿀 수 없습니다");
        }
        owner.setVelocity(new Vector());
        target.setVelocity(new Vector());
        owner.setFallDistance(0);
        target.setFallDistance(0);

        long now = ctx().currentTick();
        if (!isGrounded(owner)) {
            fallGraceUntil = now + FALL_GRACE_TICKS;
        }
        long ticks = Math.max(1, params().ticks("ult_duration"));
        mirrorWorldUntil = now + ticks;
        new ShardRing(WORLD_SHARDS, Material.GLASS_PANE, WORLD_RADIUS, WORLD_SPIN, ticks).start();

        swapFlash(ownerSpot, owner.getHeight());
        swapFlash(targetSpot, target.getHeight());
        line(ownerSpot.clone().add(0, 1, 0), targetSpot.clone().add(0, 1, 0), SWAP_DUST);
        sound(ownerSpot, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.2f, 1.0f);
        sound(targetSpot, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.2f, 1.0f);
        sound(targetSpot, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.2f, 0.8f);
        sound(targetSpot, Sound.BLOCK_GLASS_BREAK, 0.8f, 1.5f);
        return ActivationResult.success();
    }

    /**
     * True if {@code entity}'s standing hitbox would be free of blocks at {@code destination} (and above the void).
     * A sneaking player is checked at full height, since it stands up again right after the swap.
     */
    private static boolean fits(LivingEntity entity, Location destination) {
        World world = destination.getWorld();
        if (destination.getY() < world.getMinHeight() + 1) {
            return false;
        }
        double height = entity.getHeight() * (entity instanceof Player player && player.isSneaking() ? 1.2 : 1.0);
        double half = entity.getWidth() / 2;
        BoundingBox box = new BoundingBox(destination.getX() - half, destination.getY(), destination.getZ() - half,
                destination.getX() + half, destination.getY() + height, destination.getZ() + half).expand(-0.02);
        return !world.hasCollisionsIn(box);
    }

    private void swapFlash(Location feet, double height) {
        World world = feet.getWorld();
        Location body = feet.clone().add(0, height * 0.5, 0);
        world.spawnParticle(Particle.BLOCK, body, 20, 0.35, height * 0.35, 0.35, 0.1, GLASS);
        world.spawnParticle(Particle.END_ROD, body, 12, 0.3, height * 0.3, 0.3, 0.08);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, body, 10, 0.35, height * 0.35, 0.35, 0, SWAP_DUST);
    }

    /**
     * A ring of glass shards orbiting the owner's chest for a fixed time, then shattering. Each shard is an item
     * display whose teleports are interpolated, so the orbit looks smooth with an update every few ticks.
     */
    private final class ShardRing implements LongPredicate {

        private final ItemDisplay[] shards;
        private final Material pane;
        private final double radius;
        private final double spin;
        private final long lifeTicks;
        private double phase = ThreadLocalRandom.current().nextDouble(Math.PI * 2);

        ShardRing(int count, Material pane, double radius, double spin, long lifeTicks) {
            this.shards = new ItemDisplay[count];
            this.pane = pane;
            this.radius = radius;
            this.spin = spin;
            this.lifeTicks = lifeTicks;
        }

        void start() {
            Player owner = player();
            ItemStack item = ItemStack.of(pane);
            for (int i = 0; i < shards.length; i++) {
                Location at = slot(owner, i);
                shards[i] = ctx().track(owner, owner.getWorld().spawn(at, ItemDisplay.class, display -> {
                    display.setPersistent(false);
                    display.setItemStack(item);
                    display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                    display.setBrightness(new Display.Brightness(15, 15));
                    display.setTeleportDuration(RING_PERIOD);
                    display.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                            new Vector3f(0.6f, 0.6f, 0.6f), new Quaternionf()));
                }));
            }
            loop(RING_PERIOD, RING_PERIOD, lifeTicks / RING_PERIOD + 1, this);
        }

        @Override
        public boolean test(long run) {
            Player owner = player();
            if (!owner.isOnline() || (run + 1) * RING_PERIOD >= lifeTicks) {
                shatter();
                return false;
            }
            phase += spin * RING_PERIOD;
            for (int i = 0; i < shards.length; i++) {
                ItemDisplay shard = shards[i];
                if (shard == null || !shard.isValid()) {
                    continue;
                }
                if (!shard.getWorld().equals(owner.getWorld())) {
                    shard.remove();
                    continue;
                }
                shard.teleport(slot(owner, i));
            }
            if (run % 2 == 0) {
                Location body = body(owner);
                owner.getWorld().spawnParticle(Particle.WAX_OFF, body, 1, radius * 0.6, 0.5, radius * 0.6, 0);
            }
            return true;
        }

        /** Where shard {@code index} floats now: on the ring around the chest, bobbing, facing outwards. */
        private Location slot(Player owner, int index) {
            double angle = phase + index * (Math.PI * 2 / shards.length);
            double x = Math.cos(angle);
            double z = Math.sin(angle);
            double bob = Math.sin(phase * 1.5 + index) * 0.15;
            Location at = owner.getLocation().add(x * radius, owner.getHeight() * 0.55 + bob, z * radius);
            at.setYaw((float) Math.toDegrees(Math.atan2(-x, z)));
            at.setPitch(0);
            return at;
        }

        private void shatter() {
            for (ItemDisplay shard : shards) {
                if (shard == null) {
                    continue;
                }
                if (shard.isValid()) {
                    shard.getWorld().spawnParticle(Particle.BLOCK, shard.getLocation(), 6, 0.1, 0.1, 0.1, 0.1, GLASS);
                }
                shard.remove();
            }
            Player owner = player();
            if (owner.isOnline()) {
                sound(body(owner), Sound.BLOCK_GLASS_BREAK, 0.8f, 1.4f + jitter(0.1f));
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private boolean isTargetable(LivingEntity entity) {
        return entity.isValid() && !entity.isDead() && ctx().isEnemy(player(), entity);
    }

    /** A percentage param as a fraction in 0..1. */
    private double percent(String key) {
        return Math.clamp(params().num(key) / 100.0, 0.0, 1.0);
    }

    private static boolean isProjectile(EntityDamageEvent event) {
        if (event.getCause() == DamageCause.PROJECTILE) {
            return true;
        }
        Tag<DamageType> projectile = DamageTypeTags.IS_PROJECTILE;
        return projectile != null && projectile.isTagged(event.getDamageSource().getDamageType());
    }

    private static boolean isFall(EntityDamageEvent event) {
        if (event.getCause() == DamageCause.FALL) {
            return true;
        }
        Tag<DamageType> fall = DamageTypeTags.IS_FALL;
        return fall != null && fall.isTagged(event.getDamageSource().getDamageType());
    }

    /** Damage the shield does not touch: falling out of the world, the border and kill commands. */
    private static boolean isUnblockable(EntityDamageEvent event) {
        DamageCause cause = event.getCause();
        return cause == DamageCause.VOID || cause == DamageCause.KILL || cause == DamageCause.WORLD_BORDER
                || cause == DamageCause.SUICIDE;
    }

    /** True if the entity stands on something (or swims), judged by collisions right below its feet. */
    private static boolean isGrounded(Entity entity) {
        if (entity.isInWater()) {
            return true;
        }
        BoundingBox box = entity.getBoundingBox();
        BoundingBox below = new BoundingBox(box.getMinX() + 0.01, box.getMinY() - 0.12, box.getMinZ() + 0.01,
                box.getMaxX() - 0.01, box.getMinY(), box.getMaxZ() - 0.01);
        return entity.getWorld().hasCollisionsIn(below);
    }

    private static Location body(LivingEntity entity) {
        return entity.getLocation().add(0, entity.getHeight() * 0.5, 0);
    }

    private static void beam(Location from, Location to) {
        line(from, to, BEAM_DUST);
    }

    private static void line(Location from, Location to, Particle.DustTransition dust) {
        Vector delta = to.toVector().subtract(from.toVector());
        double length = delta.length();
        if (length < 1.0E-3) {
            return;
        }
        int points = (int) Math.min(MAX_BEAM_POINTS, Math.max(2, Math.ceil(length / BEAM_SPACING)));
        World world = from.getWorld();
        for (int i = 0; i <= points; i++) {
            double t = (double) i / points;
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, from.getX() + delta.getX() * t,
                    from.getY() + delta.getY() * t, from.getZ() + delta.getZ() * t, 1, 0, 0, 0, 0, dust);
        }
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
