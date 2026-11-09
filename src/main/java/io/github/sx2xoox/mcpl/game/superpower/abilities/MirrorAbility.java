package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Body;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.LandingGuard;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
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
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
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
import org.bukkit.tag.DamageTypeTags;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 거울술사 — turns the enemy's strength against them. Bends incoming ranged attacks, raises a mirror shield that blunts
 * damage and throws part of it back at the attacker, and as the ultimate trades places with an aimed enemy and
 * reflects everything it takes for a while.
 */
public final class MirrorAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("mirror", "거울술사", Material.AMETHYST_SHARD)
            .color(TextColor.color(0xC8F4FF))
            .difficulty(3)
            .passive("굴절",
                    "투사체와 {ranged_distance}블록 이상 떨어진 적의 능력 공격으로 받는 피해가 "
                            + "{projectile_reduction}% 줄어듭니다.")
            .active("반사막",
                    "{shield_duration}초간 거울 조각을 두릅니다. 받는 피해가 {shield_reduction}% 줄고, "
                            + "공격자에게 원래 피해의 {shield_reflect}%를 되돌려줍니다.",
                    10)
            .ultimate("거울 세계",
                    "조준한 적({ult_range}블록 이내)과 위치를 맞바꾸고, {ult_duration}초간 받는 피해의 "
                            + "{ult_reflect}%를 공격자에게 되돌려줍니다.")
            .ultimateNotStandalone()
            .param("projectile_reduction", 25)
            .param("ranged_distance", 6)
            .param("shield_duration", 3)
            .param("shield_reduction", 50)
            .param("shield_reflect", 60)
            .param("ult_range", 25)
            .param("ult_duration", 6)
            .param("ult_reflect", 100)
            .build();

    private static final String COLOR_TAG = "#C8F4FF";
    /** Pure white glint, silvered glass, pale cyan edge. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFFFFF, 0xE8FBFF, 0xC8F4FF);
    /** A deeper cyan accent, so beams still read against bright skies and snow. */
    private static final Color PRISM = Color.fromRGB(0x7F, 0xD8, 0xFF);
    private static final Particle.DustTransition SILVER = PALETTE.glow(0.9f);
    private static final Particle.DustTransition BEAM = new Particle.DustTransition(PALETTE.core(), PRISM, 0.9f);
    private static final Particle.DustTransition BEAM_BACK = new Particle.DustTransition(PRISM, PALETTE.core(), 0.9f);
    private static final Particle.DustOptions EDGE = new Particle.DustOptions(PALETTE.edge(), 1.0f);
    private static final BlockData GLASS = Material.GLASS.createBlockData();
    private static final BlockData MIRROR_PANE = Material.WHITE_STAINED_GLASS.createBlockData();

    /** Reflections smaller than this are not worth a hit (and a hurt animation). */
    private static final double MIN_REFLECT = 0.1;
    private static final double BEAM_PER_BLOCK = 2.0;
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
    private static final float SHARD_SCALE = 0.6f;
    private static final int PANE_FLASH_TICKS = 7;
    private static final int SWAP_BEAM_TICKS = 6;
    private static final int SWAP_MIRROR_TICKS = 9;
    private static final int SHARD_FLIGHT_TICKS = 2;

    /**
     * True while any mirror deals a reflection, so reflected damage is never reflected again (two mirrors would
     * otherwise bounce the same hit back and forth). Everything runs on the main thread.
     */
    private static boolean reflecting;

    private long shieldUntil = -1;
    private long mirrorWorldUntil = -1;
    /** Cancels the fall damage of the landing after a swap into mid-air. */
    private final LandingGuard landing = new LandingGuard();

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        shieldUntil = -1;
        mirrorWorldUntil = -1;
        landing.disarm();
    }

    @Override
    public void onTick(long tick) {
        if (tick % 2 == 0) {
            landing.update(player(), tick);
        }
    }

    // ------------------------------------------------------------------ passive + reflection

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled()) {
            return;
        }
        long now = ctx().currentTick();
        if (landing.absorb(event, now)) {
            return;
        }
        boolean flashed = false;
        if (isRanged(event)) {
            double reduction = percent("projectile_reduction");
            if (reduction > 0) {
                event.setDamage(event.getDamage() * (1 - reduction));
                paneFlash(attackerOf(event));
                flashed = true;
            }
        }

        boolean shield = now < shieldUntil;
        boolean mirrorWorld = now < mirrorWorldUntil;
        if (!shield && !mirrorWorld || isUnblockable(event)) {
            return;
        }
        double incoming = event.getFinalDamage();
        LivingEntity attacker = attackerOf(event);
        if (shield) {
            event.setDamage(event.getDamage() * (1 - percent("shield_reduction")));
            if (!flashed) {
                paneFlash(attacker);
            }
        }
        if (reflecting || incoming <= 0 || attacker == null) {
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
        if (!Combat.isTargetable(ctx(), owner, attacker) || !ctx().isAlive(owner)
                || !attacker.getWorld().equals(owner.getWorld())) {
            return;
        }
        reflecting = true;
        try {
            ctx().damage(owner, attacker, amount);
        } finally {
            reflecting = false;
        }
        reflectFx(Fx.body(owner), Fx.body(attacker));
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">◈ 반사 <white>" + AbilityInfo.formatNumber(amount)
                + "</white></" + COLOR_TAG + ">"));
    }

    /**
     * The reflection: a bright white-to-cyan line back to the attacker with light running along it, and a glass
     * shard that flies the same way and shatters on impact.
     */
    private void reflectFx(Location from, Location to) {
        World world = from.getWorld();
        double length = from.distance(to);
        if (length > 1.0E-3) {
            Vfx.line(ctx(), from, to, Math.min(BEAM_PER_BLOCK, MAX_BEAM_POINTS / length), Particle.DUST_COLOR_TRANSITION,
                    BEAM, false);
            int travel = Math.clamp(Math.round(length / 2.5), 3, 12);
            Vfx.trail(from, to, PALETTE.core(), travel, false);
            Vfx.trail(from.clone().add(0, 0.15, 0), to, PRISM, travel + 1, false);
            flyShard(from, to);
        }
        Vfx.dot(to, Particle.FLASH, PALETTE.edge(), false);
        Vfx.cloud(ctx(), to, Particle.END_ROD, 8, 0.25, 0.05, null, false);
        world.spawnParticle(Particle.BLOCK, to, Vfx.n(ctx(), 8), 0.25, 0.35, 0.25, 0.1, GLASS);
        Fx.sound(to, Sound.BLOCK_AMETHYST_CLUSTER_HIT, 1.0f, 1.5f + Fx.jitter(0.1f));
        Fx.sound(to, Sound.BLOCK_GLASS_BREAK, 0.6f, 1.7f + Fx.jitter(0.1f));
        Fx.sound(from, Sound.BLOCK_GLASS_HIT, 0.8f, 1.8f + Fx.jitter(0.1f));
    }

    /**
     * A glass shard display shot from {@code from} to {@code to}, its face turned along the flight (through its
     * transformation; the display itself stays unrotated), removed when it arrives.
     */
    private void flyShard(Location from, Location to) {
        Location heading = from.clone().setDirection(to.toVector().subtract(from.toVector()));
        ItemDisplay shard = Displays.item(ctx(), player(), from, ItemStack.of(Material.GLASS_PANE),
                Displays.centered(0, 0, 0, 0.45f, 0.45f, 0.45f, heading.getYaw(), heading.getPitch(), 45),
                SHARD_FLIGHT_TICKS + 2L);
        shard.setTeleportDuration(SHARD_FLIGHT_TICKS);
        Location end = to.clone();
        ctx().runLater(player(), 1, () -> Displays.moveTo(shard, end));
    }

    /** The enemy to reflect to: the living damager, the shooter of a projectile, or the responsible participant. */
    private @Nullable LivingEntity attackerOf(EntityDamageEvent event) {
        Entity direct = event instanceof EntityDamageByEntityEvent byEntity
                ? byEntity.getDamager()
                : event.getDamageSource().getDirectEntity();
        Entity causing = event.getDamageSource().getCausingEntity();
        if (direct instanceof LivingEntity living && Combat.isTargetable(ctx(), player(), living)) {
            return living;
        }
        if (direct instanceof Projectile projectile && projectile.getShooter() instanceof LivingEntity shooter
                && Combat.isTargetable(ctx(), player(), shooter)) {
            return shooter;
        }
        if (causing instanceof LivingEntity living && Combat.isTargetable(ctx(), player(), living)) {
            return living;
        }
        Player responsible = ctx().resolveAttacker(direct != null ? direct : causing);
        return responsible != null && Combat.isTargetable(ctx(), player(), responsible) ? responsible : null;
    }

    /**
     * A mirror pane flashes up between the owner and the attacker (or in front of the owner) and fades: the hit is
     * caught and bent. Used by the refraction passive and the shield.
     */
    private void paneFlash(@Nullable LivingEntity attacker) {
        Player owner = player();
        Location body = Fx.body(owner);
        Vector toward = attacker != null && attacker.getWorld().equals(owner.getWorld())
                ? Fx.body(attacker).toVector().subtract(body.toVector())
                : owner.getEyeLocation().getDirection();
        if (toward.lengthSquared() < 1.0E-6) {
            toward = owner.getEyeLocation().getDirection();
        }
        toward.normalize();
        Location at = body.clone().add(toward.clone().multiply(0.75));
        // The pane faces the attacker through its transformation (the display itself stays unrotated).
        Location facing = at.clone().setDirection(toward);
        float yaw = facing.getYaw();
        float pitch = facing.getPitch();
        ItemDisplay pane = Displays.item(ctx(), owner, at, ItemStack.of(Material.GLASS_PANE),
                Displays.centered(0, 0, 0, 0.2f, 0.2f, 0.2f, yaw, pitch, 0), PANE_FLASH_TICKS + 1L);
        Displays.animateNextTick(ctx(), owner, pane, Displays.centered(0, 0, 0, 0.95f, 0.95f, 0.95f, yaw, pitch, 0),
                2);
        ctx().runLater(owner, 3, () -> {
            if (pane.isValid()) {
                Displays.shrinkAway(ctx(), owner, pane, PANE_FLASH_TICKS - 3);
            }
        });
        Vfx.cloud(ctx(), at, Particle.ELECTRIC_SPARK, 4, 0.2, 0.08, null, false);
        Vfx.cloud(ctx(), at, Particle.WAX_OFF, 3, 0.25, 0, null, false);
        at.getWorld().spawnParticle(Particle.DUST_COLOR_TRANSITION, at, Vfx.n(ctx(), 5), 0.25, 0.3, 0.25, 0, SILVER);
        Fx.sound(at, Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.7f, 1.8f + Fx.jitter(0.1f));
        Fx.sound(at, Sound.BLOCK_GLASS_HIT, 0.5f, 1.6f + Fx.jitter(0.15f));
    }

    // ------------------------------------------------------------------ active: mirror shield

    @Override
    public ActivationResult onActive() {
        long ticks = Math.max(1, params().ticks("shield_duration"));
        shieldUntil = ctx().currentTick() + ticks;
        new ShardRing(SHIELD_SHARDS, Material.LIGHT_BLUE_STAINED_GLASS_PANE, SHIELD_RADIUS, SHIELD_SPIN, ticks, false)
                .start();

        Player owner = player();
        Location body = Fx.body(owner);
        hexagon(owner.getLocation().add(0, 0.1, 0), 1.25, ThreadLocalRandom.current().nextDouble(Math.PI), false);
        Vfx.cloud(ctx(), body, Particle.END_ROD, 12, 0.5, 0.06, null, false);
        body.getWorld().spawnParticle(Particle.DUST_COLOR_TRANSITION, body, Vfx.n(ctx(), 12), 0.5, 0.6, 0.5, 0, SILVER);
        Fx.sound(body, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.2f, 1.4f + Fx.jitter(0.05f));
        Fx.sound(body, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.8f, 1.6f + Fx.jitter(0.05f));
        Fx.sound(body, Sound.BLOCK_GLASS_PLACE, 0.8f, 1.5f + Fx.jitter(0.1f));
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
        if (!target.getWorld().equals(owner.getWorld())) {
            return ActivationResult.fail("위치를 바꿀 공간이 없습니다");
        }
        if (!ctx().isInsideArena(targetSpot) || !ctx().isInsideArena(ownerSpot)) {
            return ActivationResult.fail("자기장 밖으로는 위치를 바꿀 수 없습니다");
        }
        // The owner's destination gets the full teleport check (fit, hazards, ground below); the enemy only needs
        // room, since it lands where the owner stood.
        if (!Spots.isSafe(ctx(), targetSpot, Body.standing(owner))) {
            return ActivationResult.fail("그곳으로는 위치를 바꿀 수 없습니다");
        }
        if (!fits(target, ownerSpot)) {
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
        landing.armIfAirborne(owner, now, FALL_GRACE_TICKS);
        long ticks = Math.max(1, params().ticks("ult_duration"));
        mirrorWorldUntil = now + ticks;
        new ShardRing(WORLD_SHARDS, Material.GLASS_PANE, WORLD_RADIUS, WORLD_SPIN, ticks, true).start();

        swapFx(ownerSpot, targetSpot, owner.getHeight(), target.getHeight());
        Fx.sound(ownerSpot, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.2f, 1.0f + Fx.jitter(0.05f));
        Fx.sound(targetSpot, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.2f, 1.0f + Fx.jitter(0.05f));
        Fx.sound(targetSpot, Sound.ENTITY_ILLUSIONER_PREPARE_MIRROR, 1.0f, 1.2f);
        Fx.sound(targetSpot, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.2f, 0.8f);
        Fx.sound(targetSpot, Sound.BLOCK_GLASS_BREAK, 0.8f, 1.5f + Fx.jitter(0.1f));
        return ActivationResult.success();
    }

    /**
     * True if {@code entity}'s standing hitbox would be free of blocks at {@code destination}.
     * A sneaking player is checked at full height, since it stands up again right after the swap.
     */
    private static boolean fits(LivingEntity entity, Location destination) {
        double height = entity.getHeight() * (entity instanceof Player player && player.isSneaking() ? 1.2 : 1.0);
        return Spots.isFree(destination.getWorld(), destination, entity.getWidth(), height);
    }

    /**
     * The swap: a tall mirror stands at each spot, facing the other, a two-way shimmering beam links them (white one
     * way, cyan the other), and both mirrors shatter.
     */
    private void swapFx(Location ownerSpot, Location targetSpot, double ownerHeight, double targetHeight) {
        Player owner = player();
        Location a = ownerSpot.clone().add(0, ownerHeight * 0.55, 0);
        Location b = targetSpot.clone().add(0, targetHeight * 0.55, 0);
        Vector across = b.toVector().subtract(a.toVector());
        float yaw = across.lengthSquared() < 1.0E-6 ? 0f
                : (float) Math.toDegrees(Math.atan2(-across.getX(), across.getZ()));
        BlockDisplay[] mirrors = {standingMirror(ownerSpot, yaw), standingMirror(targetSpot, yaw)};
        swapFlash(ownerSpot, ownerHeight);
        swapFlash(targetSpot, targetHeight);

        Loop.repeat(ctx(), owner, 0, 1, SWAP_BEAM_TICKS, run -> {
            Vfx.trail(a, b, PALETTE.core(), 5, true);
            Vfx.trail(b, a, PRISM, 5, true);
            if (run % 2 == 0) {
                shimmer(a, b, run, run % 4 == 0 ? BEAM : BEAM_BACK);
            }
            return true;
        });
        ctx().runLater(owner, SWAP_MIRROR_TICKS - 3L, () -> {
            for (BlockDisplay mirror : mirrors) {
                if (!mirror.isValid()) {
                    continue;
                }
                Location at = mirror.getLocation().add(0, 1.0, 0);
                at.getWorld().spawnParticle(Particle.BLOCK, at, Vfx.n(ctx(), 18), 0.4, 0.8, 0.4, 0.1, GLASS);
                Displays.shrinkAway(ctx(), owner, mirror, 3);
            }
            Fx.sound(targetSpot, Sound.BLOCK_GLASS_BREAK, 1.0f, 1.2f + Fx.jitter(0.1f));
        });
    }

    /** A tall, thin pane of glass standing at {@code feet}, rotated by {@code yaw}, growing out of the ground. */
    private BlockDisplay standingMirror(Location feet, float yaw) {
        BlockDisplay mirror = Displays.block(ctx(), player(), feet, MIRROR_PANE,
                Displays.transform(0, 0.05f, 0, 1.2f, 0.05f, 0.06f, yaw, 0, 0), SWAP_MIRROR_TICKS + 2L);
        Displays.animateNextTick(ctx(), player(), mirror, Displays.transform(0, 1.15f, 0, 1.2f, 2.3f, 0.06f, yaw, 0, 0),
                2);
        return mirror;
    }

    /** A wavering line between the two spots, alternately white and cyan. */
    private void shimmer(Location a, Location b, long run, Particle.DustTransition dust) {
        Vector delta = b.toVector().subtract(a.toVector());
        double length = delta.length();
        if (length < 1.0E-3) {
            return;
        }
        int points = (int) Math.min(MAX_BEAM_POINTS, Math.max(4, Math.round(length * 2 * ctx().effectScale())));
        World world = a.getWorld();
        for (int i = 0; i <= points; i++) {
            double t = (double) i / points;
            double wave = Math.sin(t * Math.PI * 6 + run) * 0.12 * Math.sin(t * Math.PI);
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, a.getX() + delta.getX() * t,
                    a.getY() + delta.getY() * t + wave, a.getZ() + delta.getZ() * t, 1, 0, 0, 0, 0, dust, true);
        }
    }

    private void swapFlash(Location feet, double height) {
        World world = feet.getWorld();
        Location body = feet.clone().add(0, height * 0.5, 0);
        Vfx.dot(body, Particle.FLASH, PALETTE.core(), true);
        world.spawnParticle(Particle.BLOCK, body, Vfx.n(ctx(), 20), 0.35, height * 0.35, 0.35, 0.1, GLASS, true);
        world.spawnParticle(Particle.END_ROD, body, Vfx.n(ctx(), 12), 0.3, height * 0.3, 0.3, 0.08, null, true);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, body, Vfx.n(ctx(), 10), 0.35, height * 0.35, 0.35, 0,
                SILVER, true);
        hexagon(feet.clone().add(0, 0.1, 0), 1.0, 0, true);
    }

    /** A flat hexagon outline: the mirror's sigil. */
    private void hexagon(Location center, double radius, double phase, boolean force) {
        for (int i = 0; i < 6; i++) {
            double a1 = phase + i * Math.PI / 3;
            double a2 = phase + (i + 1) * Math.PI / 3;
            Location p1 = center.clone().add(Math.cos(a1) * radius, 0, Math.sin(a1) * radius);
            Location p2 = center.clone().add(Math.cos(a2) * radius, 0, Math.sin(a2) * radius);
            Vfx.line(ctx(), p1, p2, 3.0, Particle.DUST_COLOR_TRANSITION, SILVER, force);
        }
    }

    /**
     * A ring of glass shards orbiting the owner's chest for a fixed time, then shattering outwards. Each shard is an
     * item display: its moves are interpolated, so the orbit looks smooth with an update every few ticks; its
     * transformation turns its face outwards and rocks it gently (the display itself is never rotated). Light glints
     * run from shard to shard; in the mirror world a hexagon turns on the ground as well.
     */
    private final class ShardRing implements LongPredicate {

        private final ItemDisplay[] shards;
        private final Material pane;
        private final double radius;
        private final double spin;
        private final long lifeTicks;
        private final boolean sigil;
        private double phase = ThreadLocalRandom.current().nextDouble(Math.PI * 2);

        ShardRing(int count, Material pane, double radius, double spin, long lifeTicks, boolean sigil) {
            this.shards = new ItemDisplay[count];
            this.pane = pane;
            this.radius = radius;
            this.spin = spin;
            this.lifeTicks = lifeTicks;
            this.sigil = sigil;
        }

        void start() {
            Player owner = player();
            ItemStack item = ItemStack.of(pane);
            for (int i = 0; i < shards.length; i++) {
                // Shards fly in from further out and grow into place.
                shards[i] = Displays.item(ctx(), owner, slot(owner, i, radius * 2.2), item,
                        Displays.centered(0, 0, 0, 0.2f, 0.2f, 0.2f, outwardYaw(i), 0, 0), lifeTicks + 10);
                shards[i].setTeleportDuration(RING_PERIOD);
                Displays.animateNextTick(ctx(), owner, shards[i], pose(i), RING_PERIOD + 1);
            }
            Loop.repeat(ctx(), owner, RING_PERIOD, RING_PERIOD, lifeTicks / RING_PERIOD + 1, this);
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
                Displays.moveTo(shard, slot(owner, i, radius));
                // Also on the first run: the shard's facing turns with the ring (it lives in the transformation).
                Displays.animate(shard, pose(i), RING_PERIOD);
            }
            if (run % 2 == 0) {
                Vfx.cloud(ctx(), Fx.body(owner), Particle.WAX_OFF, 1, radius * 0.6, 0, null, false);
            }
            if (run % 3 == 1) {
                // A glint of light running from one shard to the next.
                int from = ThreadLocalRandom.current().nextInt(shards.length);
                ItemDisplay a = shards[from];
                ItemDisplay b = shards[(from + 1) % shards.length];
                if (a != null && b != null && a.isValid() && b.isValid()) {
                    Vfx.trail(a.getLocation(), b.getLocation(), PALETTE.core(), 4, false);
                    Vfx.dot(a.getLocation(), Particle.END_ROD, null, false);
                }
            }
            if (sigil && run % 5 == 0) {
                hexagon(owner.getLocation().add(0, 0.1, 0), radius + 0.4, phase * 0.5, false);
            }
            return true;
        }

        /** Where shard {@code index} floats now: on a ring around the chest, bobbing. */
        private Location slot(Player owner, int index, double ringRadius) {
            double angle = angleOf(index);
            double bob = Math.sin(phase * 1.5 + index) * 0.15;
            return owner.getLocation().add(Math.cos(angle) * ringRadius, owner.getHeight() * 0.55 + bob,
                    Math.sin(angle) * ringRadius);
        }

        private double angleOf(int index) {
            return phase + index * (Math.PI * 2 / shards.length);
        }

        /** The yaw that turns shard {@code index}'s face outwards, away from the owner. */
        private float outwardYaw(int index) {
            double angle = angleOf(index);
            return (float) Math.toDegrees(Math.atan2(-Math.cos(angle), Math.sin(angle)));
        }

        /** The shard facing outwards, rocking gently. */
        private Transformation pose(int index) {
            float roll = (float) (Math.sin(phase * 2 + index * 1.3) * 25);
            float pitch = (float) (Math.cos(phase * 1.7 + index) * 15);
            return Displays.centered(0, 0, 0, SHARD_SCALE, SHARD_SCALE, SHARD_SCALE, outwardYaw(index), pitch, roll);
        }

        private void shatter() {
            Player owner = player();
            for (int i = 0; i < shards.length; i++) {
                ItemDisplay shard = shards[i];
                if (shard == null || !shard.isValid()) {
                    continue;
                }
                shard.getWorld().spawnParticle(Particle.BLOCK, shard.getLocation(), Vfx.n(ctx(), 5), 0.1, 0.1, 0.1, 0.1,
                        GLASS);
                if (owner.isOnline() && shard.getWorld().equals(owner.getWorld())) {
                    shard.setTeleportDuration(3);
                    Displays.moveTo(shard, slot(owner, i, radius * 2.2));
                    Displays.shrinkAway(ctx(), owner, shard, 3);
                } else {
                    shard.remove();
                }
            }
            if (owner.isOnline()) {
                Location body = Fx.body(owner);
                Vfx.ring(ctx(), body, radius + 0.3, 16, phase, Particle.DUST, EDGE, false);
                Fx.sound(body, Sound.BLOCK_GLASS_BREAK, 0.8f, 1.4f + Fx.jitter(0.1f));
                Fx.sound(body, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 0.6f, 1.6f + Fx.jitter(0.1f));
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** A percentage param as a fraction in 0..1. */
    private double percent(String key) {
        return Math.clamp(params().num(key) / 100.0, 0.0, 1.0);
    }

    /**
     * True for hits the refraction passive reduces: vanilla projectiles, and ability damage from an enemy at least
     * {@code ranged_distance} blocks away. Ability "projectiles" (fireballs, ice spears, skill arrows, bolts) are
     * delivered as magic damage from the caster, so the caster's distance is what tells them apart from melee.
     */
    private boolean isRanged(EntityDamageEvent event) {
        if (isProjectile(event)) {
            return true;
        }
        if (!ctx().isAbilityDamage()) {
            return false;
        }
        LivingEntity attacker = attackerOf(event);
        double min = Math.max(0, params().num("ranged_distance"));
        return attacker != null && attacker.getWorld().equals(player().getWorld())
                && attacker.getLocation().distanceSquared(player().getLocation()) >= min * min;
    }

    private static boolean isProjectile(EntityDamageEvent event) {
        if (event.getCause() == DamageCause.PROJECTILE) {
            return true;
        }
        Tag<DamageType> projectile = DamageTypeTags.IS_PROJECTILE;
        return projectile != null && projectile.isTagged(event.getDamageSource().getDamageType());
    }

    /** Damage the shield does not touch: falling out of the world, the border and kill commands. */
    private static boolean isUnblockable(EntityDamageEvent event) {
        DamageCause cause = event.getCause();
        return cause == DamageCause.VOID || cause == DamageCause.KILL || cause == DamageCause.WORLD_BORDER
                || cause == DamageCause.SUICIDE;
    }
}
