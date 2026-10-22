package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.tag.DamageTypeTags;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 그림자 — assassin. Hits harder from behind, vanishes completely from enemies' screens for a few seconds and, as
 * the ultimate, flickers between nearby enemies' backs, cutting each, before slipping back to where it started.
 */
public final class ShadowAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("shadow", "그림자", Material.BLACK_DYE)
            .color(TextColor.color(0x9A8CC2))
            .difficulty(3)
            .passive("암습", "적의 등 뒤에서 근접 공격하면 피해가 {backstab_bonus}% 증가합니다.")
            .active("그림자 은신",
                    "{stealth_duration}초간 적의 눈에서 완전히 사라지고 신속 I을 얻습니다(발밑에 희미한 그림자가 남습니다). "
                            + "공격하거나 공격받으면 풀리며, 은신 중의 첫 근접 공격은 무조건 암습이 됩니다.",
                    14)
            .ultimate("그림자 난무",
                    "반경 {ult_radius}블록의 적들 등 뒤로 차례로 순간이동하며 {ult_strikes}번 베어 각 {ult_damage} 피해를 준 뒤 "
                            + "원래 자리로 돌아옵니다. 대상이 부족하면 같은 적을 여러 번 벱니다.")
            .param("backstab_bonus", 40)
            .param("stealth_duration", 5)
            .param("ult_radius", 12)
            .param("ult_strikes", 3)
            .param("ult_damage", 5)
            .build();

    private static final String COLOR_TAG = "#9A8CC2";
    private static final Color SHADOW_BLACK = Color.fromRGB(0x16, 0x10, 0x22);
    private static final Color SHADOW_VIOLET = Color.fromRGB(0x6A, 0x4C, 0x9C);
    private static final Particle.DustOptions SHADOW_DUST = new Particle.DustOptions(SHADOW_BLACK, 1.4f);
    private static final Particle.DustOptions FOOTPRINT_DUST = new Particle.DustOptions(SHADOW_BLACK, 0.9f);
    private static final Particle.DustTransition SHADOW_FADE =
            new Particle.DustTransition(SHADOW_BLACK, SHADOW_VIOLET, 1.3f);

    /** Cosine of the angle (from the victim's view direction) beyond which the attacker counts as behind (~105°). */
    private static final double BACKSTAB_DOT = -0.26;
    /** Enemy mobs this close that are chasing the owner lose track of them when stealth starts. */
    private static final double AGGRO_DROP_RANGE = 24;

    // Ultimate
    private static final long DANCE_STEP_TICKS = 6;
    /** A target that fled this many radii away from the start point is skipped. */
    private static final double DANCE_LEASH = 2.0;
    /** Repeated strikes on the same target come from a different side each time. */
    private static final double REPEAT_ANGLE_STEP = 120;
    private static final double[] AROUND_ANGLES = {0, 40, -40, 80, -80, 120, -120, 180};
    private static final double[] AROUND_DISTANCES = {1.2, 2.0};
    private static final double[] AROUND_HEIGHTS = {0, 0.5, 1.0, -0.5, -1.0};
    private static final double GROUND_SNAP = 1.1;

    private static final double STANDING_WIDTH = 0.6;
    private static final double STANDING_HEIGHT = 1.8;
    private static final double STANDING_EYE = 1.62;
    private static final long LANDING_GRACE_TICKS = 100;

    private boolean stealthed;
    /** Generation counter so that a stale expiry timer never ends a newer stealth. */
    private int stealthToken;
    private long stealthEndTick;
    /** Players the owner is currently hidden from (must all be shown again). */
    private final Set<UUID> hiddenFrom = new HashSet<>();
    /** Until this tick the next fall damage is cancelled (-1: none); set when a teleport ends in mid-air. */
    private long landingGraceUntil = -1;

    private enum Reveal { ATTACK, HIT, EXPIRED, REVOKE }

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        reveal(Reveal.REVOKE);
    }

    // ------------------------------------------------------------------ passive: backstab

    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled()) {
            return;
        }
        boolean fromStealth = stealthed;
        if (fromStealth) {
            reveal(Reveal.ATTACK);
        }
        if (ctx().isAbilityDamage() || !player().equals(event.getDamager())
                || event.getCause() != DamageCause.ENTITY_ATTACK) {
            return;
        }
        if (!fromStealth && !isBehind(victim)) {
            return;
        }
        double bonus = Math.max(0, params().num("backstab_bonus"));
        event.setDamage(event.getDamage() * (1 + bonus / 100.0));

        Location at = victim.getLocation().add(0, victim.getHeight() * 0.6, 0);
        World world = at.getWorld();
        world.spawnParticle(Particle.CRIT, at, 12, 0.25, 0.3, 0.25, 0.25);
        world.spawnParticle(Particle.DUST, at, 10, 0.3, 0.35, 0.3, 0, SHADOW_DUST);
        world.spawnParticle(Particle.SQUID_INK, at, 4, 0.15, 0.2, 0.15, 0.02);
        sound(at, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 0.65f + jitter(0.08f));
        sound(at, Sound.ENTITY_PHANTOM_BITE, 0.7f, 0.8f + jitter(0.1f));
        String label = fromStealth ? "기습 암습!" : "암습!";
        player().sendActionBar(Text.mm("<" + COLOR_TAG + ">⚔ " + label + "</" + COLOR_TAG + "> <gray>+"
                + AbilityInfo.formatNumber(bonus) + "%</gray>"));
    }

    /** True if the owner stands outside the victim's field of view, roughly at its back. */
    private boolean isBehind(LivingEntity victim) {
        Location victimLocation = victim.getLocation();
        Vector facing = victimLocation.getDirection().setY(0);
        Vector toAttacker = player().getLocation().toVector().subtract(victimLocation.toVector()).setY(0);
        if (facing.lengthSquared() < 1.0E-6 || toAttacker.lengthSquared() < 1.0E-6) {
            return false;
        }
        return facing.normalize().dot(toAttacker.normalize()) <= BACKSTAB_DOT;
    }

    // ------------------------------------------------------------------ active: shadow stealth

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        long duration = Math.max(1, params().ticks("stealth_duration"));
        int token = ++stealthToken;
        stealthed = true;
        stealthEndTick = ctx().currentTick() + duration;

        // Invisibility hides the owner from viewers that are not enemies too (allies, spectators see the vanilla
        // effect); enemies do not receive the player at all, so armor, held items and the name tag vanish as well.
        int effectTicks = (int) Math.min(Integer.MAX_VALUE, duration);
        owner.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, effectTicks + 5, 0, false, false, true));
        owner.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, effectTicks, 0, false, false, true));
        hideFromEnemies();
        dropAggro();

        Location body = owner.getLocation().add(0, owner.getHeight() * 0.5, 0);
        World world = body.getWorld();
        world.spawnParticle(Particle.LARGE_SMOKE, body, 16, 0.3, 0.6, 0.3, 0.02);
        world.spawnParticle(Particle.SQUID_INK, body, 10, 0.3, 0.5, 0.3, 0.05);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, body, 14, 0.4, 0.7, 0.4, 0, SHADOW_FADE);
        sound(body, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.9f, 0.6f + jitter(0.05f));
        sound(body, Sound.ENTITY_PHANTOM_FLAP, 0.8f, 0.6f + jitter(0.1f));

        ctx().runLater(owner, duration, () -> {
            if (token == stealthToken) {
                reveal(Reveal.EXPIRED);
            }
        });
        return ActivationResult.success();
    }

    private void hideFromEnemies() {
        Player owner = player();
        for (Player viewer : ctx().alivePlayers()) {
            if (viewer.equals(owner) || !ctx().isEnemy(owner, viewer)) {
                continue;
            }
            if (hiddenFrom.add(viewer.getUniqueId())) {
                viewer.hidePlayer(ctx().plugin(), owner);
            }
        }
    }

    private void dropAggro() {
        Player owner = player();
        for (Entity entity : owner.getNearbyEntities(AGGRO_DROP_RANGE, AGGRO_DROP_RANGE / 2, AGGRO_DROP_RANGE)) {
            if (entity instanceof Mob mob && owner.equals(mob.getTarget()) && ctx().isEnemy(owner, mob)) {
                mob.setTarget(null);
            }
        }
    }

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (landingGraceUntil >= 0 && isFall(event)) {
            // The dance may end next to an airborne enemy; that drop is not the owner's fault.
            if (ctx().currentTick() <= landingGraceUntil) {
                event.setCancelled(true);
            }
            landingGraceUntil = -1;
            return;
        }
        if (stealthed && event.getDamage() > 0 && event instanceof EntityDamageByEntityEvent) {
            reveal(Reveal.HIT);
        }
    }

    private static boolean isFall(EntityDamageEvent event) {
        if (event.getCause() == DamageCause.FALL) {
            return true;
        }
        Tag<DamageType> fall = DamageTypeTags.IS_FALL;
        return fall != null && fall.isTagged(event.getDamageSource().getDamageType());
    }

    @Override
    public void onTick(long tick) {
        if (landingGraceUntil >= 0 && tick % 2 == 0 && (tick > landingGraceUntil || isGrounded(player()))) {
            landingGraceUntil = -1;
        }
        if (!stealthed) {
            return;
        }
        Player owner = player();
        if (tick % 4 == 0) {
            // The only tell: a faint shadow pooling at the owner's feet.
            Location feet = owner.getLocation().add(0, 0.05, 0);
            World world = feet.getWorld();
            world.spawnParticle(Particle.DUST, feet, 1, 0.2, 0.01, 0.2, 0, FOOTPRINT_DUST);
            world.spawnParticle(Particle.SMOKE, feet, 1, 0.15, 0.01, 0.15, 0.002);
        }
        if (tick % 5 == 0) {
            double left = Math.max(0, stealthEndTick - ctx().currentTick()) / 20.0;
            owner.sendActionBar(Text.mm("<dark_gray>☾</dark_gray> <" + COLOR_TAG + ">그림자 은신</" + COLOR_TAG
                    + "> <gray>" + AbilityInfo.formatNumber(Math.ceil(left * 10) / 10.0) + "초</gray>"));
        }
        if (tick % 20 == 0) {
            hideFromEnemies();
            dropAggro();
        }
    }

    /** Ends the stealth (if any), showing the owner to everyone it was hidden from. */
    private void reveal(Reveal reason) {
        if (!stealthed) {
            return;
        }
        stealthed = false;
        stealthToken++;
        Player owner = player();
        for (UUID id : hiddenFrom) {
            Player viewer = Bukkit.getPlayer(id);
            if (viewer != null) {
                viewer.showPlayer(ctx().plugin(), owner);
            }
        }
        hiddenFrom.clear();
        if (!owner.isOnline()) {
            return;
        }
        removeOwnEffect(owner, PotionEffectType.INVISIBILITY);
        removeOwnEffect(owner, PotionEffectType.SPEED);
        if (reason == Reveal.REVOKE) {
            return;
        }

        Location body = owner.getLocation().add(0, owner.getHeight() * 0.5, 0);
        World world = body.getWorld();
        world.spawnParticle(Particle.LARGE_SMOKE, body, 10, 0.3, 0.6, 0.3, 0.01);
        world.spawnParticle(Particle.DUST, body, 8, 0.35, 0.6, 0.35, 0, SHADOW_DUST);
        sound(body, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.7f, 1.2f + jitter(0.1f));
        switch (reason) {
            case HIT -> owner.sendActionBar(Text.mm("<gray>공격받아 은신이 풀렸습니다</gray>"));
            case EXPIRED -> owner.sendActionBar(Text.mm("<gray>그림자 은신이 끝났습니다</gray>"));
            default -> {
                // ATTACK: the backstab feedback is shown by onDealDamage.
            }
        }
    }

    /** Removes our level-I effect; a stronger effect from another source is left alone. */
    private static void removeOwnEffect(Player owner, PotionEffectType type) {
        PotionEffect effect = owner.getPotionEffect(type);
        if (effect != null && effect.getAmplifier() == 0) {
            owner.removePotionEffect(type);
        }
    }

    // ------------------------------------------------------------------ ultimate: shadow dance

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        if (owner.isInsideVehicle()) {
            return ActivationResult.fail("탈것에 탄 채로는 사용할 수 없습니다");
        }
        double radius = params().num("ult_radius");
        List<LivingEntity> targets = new ArrayList<>(ctx().enemiesNear(owner, owner.getLocation(), radius));
        if (targets.isEmpty()) {
            return ActivationResult.fail("반경 " + AbilityInfo.formatNumber(radius) + "블록 안에 적이 없습니다");
        }
        int strikes = Math.max(1, params().integer("ult_strikes"));
        // Visible enemies first; enemiesNear is nearest first and the sort is stable.
        targets.sort(Comparator.comparingInt(enemy -> owner.hasLineOfSight(enemy) ? 0 : 1));
        if (targets.size() > strikes) {
            targets = new ArrayList<>(targets.subList(0, strikes));
        }

        Location origin = owner.getLocation();
        Location body = origin.clone().add(0, owner.getHeight() * 0.5, 0);
        World world = origin.getWorld();
        world.spawnParticle(Particle.LARGE_SMOKE, body, 20, 0.4, 0.7, 0.4, 0.03);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, body, 16, 0.5, 0.8, 0.5, 0, SHADOW_FADE);
        sound(origin, Sound.ENTITY_PHANTOM_SWOOP, 1.0f, 0.7f);
        sound(origin, Sound.ENTITY_ILLUSIONER_PREPARE_MIRROR, 1.0f, 0.8f);

        loop(0, DANCE_STEP_TICKS, strikes + 1L, new Dance(origin, targets, strikes, radius));
        return ActivationResult.success();
    }

    /** One step per strike, then a final step that brings the owner home. */
    private final class Dance implements LongPredicate {

        private final Location origin;
        private final List<LivingEntity> targets;
        private final int strikes;
        private final double leash;
        private final double damage;
        private final Map<UUID, Integer> hits = new HashMap<>();
        private int cursor;

        Dance(Location origin, List<LivingEntity> targets, int strikes, double radius) {
            this.origin = origin.clone();
            this.targets = targets;
            this.strikes = strikes;
            this.leash = Math.max(4, radius * DANCE_LEASH);
            this.damage = params().num("ult_damage");
        }

        @Override
        public boolean test(long step) {
            if (step >= strikes) {
                returnHome();
                return false;
            }
            LivingEntity target = nextTarget();
            if (target == null) {
                returnHome();
                return false;
            }
            strike(target);
            return true;
        }

        private @Nullable LivingEntity nextTarget() {
            for (int i = 0; i < targets.size(); i++) {
                int index = (cursor + i) % targets.size();
                LivingEntity candidate = targets.get(index);
                if (isValidTarget(candidate)) {
                    cursor = index + 1;
                    return candidate;
                }
            }
            return null;
        }

        private boolean isValidTarget(LivingEntity target) {
            return target.isValid() && !target.isDead() && ctx().isEnemy(player(), target)
                    && target.getWorld().equals(origin.getWorld())
                    && target.getLocation().distanceSquared(origin) <= leash * leash;
        }

        private void strike(LivingEntity target) {
            Player owner = player();
            int repeat = hits.merge(target.getUniqueId(), 1, Integer::sum) - 1;
            Location before = owner.getLocation();
            Location spot = spotAround(owner, target, repeat * REPEAT_ANGLE_STEP);
            if (spot != null && owner.teleport(spot, TeleportCause.PLUGIN)) {
                owner.setFallDistance(0);
                protectLanding(owner);
                shadowStep(owner, before, spot);
            } else {
                // No room next to the target: the shadow lashes out from where the owner stands instead.
                shadowStep(owner, before, target.getLocation());
            }

            target.setNoDamageTicks(0);
            ctx().damage(owner, target, damage);

            Location at = target.getLocation().add(0, target.getHeight() * 0.55, 0);
            World world = at.getWorld();
            world.spawnParticle(Particle.SWEEP_ATTACK, at, 1, 0.1, 0.1, 0.1, 0);
            world.spawnParticle(Particle.DUST, at, 14, 0.35, 0.45, 0.35, 0, SHADOW_DUST);
            world.spawnParticle(Particle.SQUID_INK, at, 6, 0.2, 0.3, 0.2, 0.04);
            world.spawnParticle(Particle.CRIT, at, 8, 0.3, 0.3, 0.3, 0.3);
            sound(at, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 1.25f + jitter(0.15f));
            sound(at, Sound.ENTITY_PHANTOM_BITE, 0.8f, 1.1f + jitter(0.15f));
        }

        private void returnHome() {
            Player owner = player();
            if (!owner.isValid() || !owner.getWorld().equals(origin.getWorld())) {
                return;
            }
            double scale = scaleOf(owner);
            if (!fits(origin.getWorld(), origin, STANDING_WIDTH * scale, STANDING_HEIGHT * scale)) {
                return;
            }
            Location before = owner.getLocation();
            if (owner.teleport(origin, TeleportCause.PLUGIN)) {
                owner.setFallDistance(0);
                protectLanding(owner);
                shadowStep(owner, before, origin);
                sound(origin, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.9f, 0.85f + jitter(0.05f));
            }
        }
    }

    /** Dark streaks from the old position to the new one plus a puff of smoke where the owner left. */
    private static void shadowStep(Player owner, Location from, Location to) {
        World world = from.getWorld();
        Location fromBody = from.clone().add(0, owner.getHeight() * 0.5, 0);
        world.spawnParticle(Particle.LARGE_SMOKE, fromBody, 6, 0.25, 0.5, 0.25, 0.01);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, fromBody, 8, 0.3, 0.6, 0.3, 0, SHADOW_FADE);
        if (!world.equals(to.getWorld())) {
            return;
        }
        int duration = Math.clamp(Math.round(from.distance(to) * 0.7), 3, 10);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < 8; i++) {
            double ox = (random.nextDouble() - 0.5) * 0.5;
            double oy = random.nextDouble() * owner.getHeight();
            double oz = (random.nextDouble() - 0.5) * 0.5;
            Color color = i % 2 == 0 ? SHADOW_BLACK : SHADOW_VIOLET;
            world.spawnParticle(Particle.TRAIL, from.clone().add(ox, oy, oz), 1, 0, 0, 0, 0,
                    new Particle.Trail(to.clone().add(ox, oy, oz), color, duration));
        }
    }

    /**
     * A safe spot next to {@code target}, preferring its back rotated by {@code angleOffset} degrees, facing it.
     * Every spot needs a clear line to the target's eyes so the owner never lands behind a wall. Null if none.
     */
    private static @Nullable Location spotAround(Player owner, LivingEntity target, double angleOffset) {
        World world = target.getWorld();
        Location base = target.getLocation();
        Vector back = base.getDirection().setY(0);
        if (back.lengthSquared() < 1.0E-4) {
            back = new Vector(0, 0, 1);
        }
        back.normalize().multiply(-1).rotateAroundY(Math.toRadians(angleOffset));

        double scale = scaleOf(owner);
        double width = STANDING_WIDTH * scale;
        double height = STANDING_HEIGHT * scale;
        double eyeHeight = STANDING_EYE * scale;
        Location targetEye = target.getEyeLocation();
        double clearance = target.getWidth() / 2 + width / 2;

        for (double angle : AROUND_ANGLES) {
            Vector side = back.clone().rotateAroundY(Math.toRadians(angle));
            for (double distance : AROUND_DISTANCES) {
                for (double dy : AROUND_HEIGHTS) {
                    Location feet = base.clone().add(side.clone().multiply(distance + clearance)).add(0, dy, 0);
                    snapToGround(world, feet, GROUND_SNAP);
                    if (!fits(world, feet, width, height) || !hasGroundBelow(world, feet)
                            || !clearPath(world, targetEye, feet.clone().add(0, eyeHeight, 0))) {
                        continue;
                    }
                    Vector look = target.getLocation().add(0, target.getHeight() * 0.6, 0).toVector()
                            .subtract(feet.toVector().add(new Vector(0, eyeHeight, 0)));
                    if (look.lengthSquared() > 1.0E-6) {
                        feet.setDirection(look);
                    }
                    return feet;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    /** After a teleport into mid-air, the landing that follows deals no fall damage. */
    private void protectLanding(Player owner) {
        if (!isGrounded(owner)) {
            landingGraceUntil = ctx().currentTick() + LANDING_GRACE_TICKS;
        }
    }

    /** True when a solid surface is right below the entity's feet (server-side, not the client's claim). */
    private static boolean isGrounded(LivingEntity entity) {
        BoundingBox box = entity.getBoundingBox();
        BoundingBox below = new BoundingBox(box.getMinX() + 0.05, box.getMinY() - 0.15, box.getMinZ() + 0.05,
                box.getMaxX() - 0.05, box.getMinY() - 0.01, box.getMaxZ() - 0.05);
        return entity.getWorld().hasCollisionsIn(below) || entity.isInWater();
    }

    private static boolean fits(World world, Location feet, double width, double height) {
        if (feet.getY() < world.getMinHeight() + 1 || feet.getY() + height > world.getMaxHeight()) {
            return false;
        }
        double half = width / 2 - 0.01;
        BoundingBox box = new BoundingBox(feet.getX() - half, feet.getY() + 0.01, feet.getZ() - half,
                feet.getX() + half, feet.getY() + height, feet.getZ() + half);
        if (world.hasCollisionsIn(box)) {
            return false;
        }
        int x = feet.getBlockX();
        int z = feet.getBlockZ();
        for (int y = (int) Math.floor(box.getMinY()); y <= (int) Math.floor(box.getMaxY()); y++) {
            if (isHazard(world.getBlockAt(x, y, z))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHazard(Block block) {
        Material type = block.getType();
        return type == Material.LAVA || type == Material.FIRE || type == Material.SOUL_FIRE
                || type == Material.POWDER_SNOW || type == Material.COBWEB;
    }

    private static boolean hasGroundBelow(World world, Location feet) {
        double depth = feet.getY() - world.getMinHeight();
        return depth > 0 && world.rayTraceBlocks(feet, new Vector(0, -1, 0), depth, FluidCollisionMode.ALWAYS,
                true) != null;
    }

    private static void snapToGround(World world, Location feet, double maxDrop) {
        RayTraceResult ground = world.rayTraceBlocks(feet, new Vector(0, -1, 0), maxDrop, FluidCollisionMode.NEVER,
                true);
        if (ground != null) {
            feet.setY(ground.getHitPosition().getY());
        }
    }

    private static boolean clearPath(World world, Location from, Location to) {
        Vector delta = to.toVector().subtract(from.toVector());
        double length = delta.length();
        if (length < 1.0E-3) {
            return true;
        }
        return world.rayTraceBlocks(from, delta.multiply(1 / length), length, FluidCollisionMode.NEVER, true) == null;
    }

    private static double scaleOf(LivingEntity entity) {
        AttributeInstance scale = entity.getAttribute(Attribute.SCALE);
        return scale == null ? 1.0 : Math.max(0.1, scale.getValue());
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
            boolean more = ctx().isAlive(player()) && step.test(index);
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
