package io.github.sx2xoox.mcpl.game.augment.augments;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/** 점멸: crouch and press the swap-hands key to teleport a few blocks where you look; walls stop it. */
public final class BlinkAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("blink", "점멸", AugmentTier.GOLD,
                    AugmentKind.MOBILITY, Material.ENDER_EYE)
            .description("웅크린 채 손 바꾸기 키(F)를 누르면 바라보는 쪽으로 최대 {distance}블록 순간이동합니다. 벽은 넘지"
                    + " 못합니다 (재사용 {cooldown}초).")
            .param("distance", 8)
            .param("cooldown", 12)
            .build();

    /** Step of the path check, in blocks. */
    private static final double STEP = 0.25;

    private final AugmentContext ctx;
    private long readyAt;

    public BlinkAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onGrant() {
        readyAt = ctx.tick();
    }

    @Override
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player player = ctx.player();
        if (!player.isSneaking()) {
            return;
        }
        event.setCancelled(true);
        if (ctx.tick() < readyAt) {
            Nearby.cooldown(ctx, readyAt);
            return;
        }
        Location from = player.getLocation();
        Location to = destination(from, ctx.params().num("distance"));
        if (to == null) {
            ctx.actionBar("augment-fx.blink-blocked");
            return;
        }
        World world = player.getWorld();
        world.spawnParticle(Particle.PORTAL, from.clone().add(0, 1, 0), 30, 0.3, 0.6, 0.3, 0.2);
        player.teleport(to);
        player.setFallDistance(0f);
        world.spawnParticle(Particle.REVERSE_PORTAL, to.clone().add(0, 1, 0), 30, 0.3, 0.6, 0.3, 0.05);
        world.playSound(to, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.4f);
        readyAt = ctx.tick() + ctx.params().ticks("cooldown");
    }

    /**
     * The farthest place along the look direction (up to {@code distance}) the body fits into without passing through
     * a block or the world border; null if not even a short step is free.
     */
    public static @Nullable Location destination(Location from, double distance) {
        Vector direction = from.getDirection();
        if (direction.lengthSquared() < 1.0E-6) {
            return null;
        }
        direction.normalize();
        Location best = null;
        for (double d = STEP; d <= distance + 1.0E-9; d += STEP) {
            Location candidate = from.clone().add(direction.clone().multiply(d));
            if (!fits(candidate)) {
                break;
            }
            best = candidate;
        }
        if (best == null || best.distanceSquared(from) < 1.0) {
            return null;
        }
        return best;
    }

    /** The player's body (0.6 × 1.8, lifted off the floor a hair) fits at {@code feet}. */
    private static boolean fits(Location feet) {
        World world = feet.getWorld();
        double x = feet.getX();
        double y = feet.getY();
        double z = feet.getZ();
        return world.getWorldBorder().isInside(feet)
                && y >= world.getMinHeight()
                && !world.hasCollisionsIn(new BoundingBox(x - 0.3, y + 1.0E-3, z - 0.3, x + 0.3, y + 1.8, z + 0.3));
    }
}
