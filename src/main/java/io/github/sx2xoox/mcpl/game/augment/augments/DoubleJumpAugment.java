package io.github.sx2xoox.mcpl.game.augment.augments;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.util.Vector;

/**
 * 이단 점프: jump again in mid-air. Works through the flight toggle: while the jump is ready the owner may "fly", and
 * pressing jump twice is turned into a leap instead of flight.
 */
public final class DoubleJumpAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("double_jump", "이단 점프", AugmentTier.GOLD,
                    AugmentKind.MOBILITY, Material.RABBIT_FOOT)
            .description("공중에서 점프 키를 한 번 더 누르면 위로 {up}, 앞으로 {forward}만큼 도약합니다 (재사용 {cooldown}초,"
                    + " 땅에 닿아야 다시 충전).")
            .param("up", 0.9)
            .param("forward", 0.6)
            .param("cooldown", 3)
            .build();

    private final AugmentContext ctx;
    private long readyAt;

    public DoubleJumpAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onGrant() {
        readyAt = ctx.tick();
    }

    @Override
    public void onTick() {
        Player player = ctx.player();
        if (!survival(player) || player.getAllowFlight()) {
            return;
        }
        if (ctx.tick() >= readyAt && grounded(player)) {
            player.setAllowFlight(true);
        }
    }

    @Override
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        Player player = ctx.player();
        if (!event.isFlying() || !survival(player)) {
            return;
        }
        event.setCancelled(true);
        player.setFlying(false);
        player.setAllowFlight(false);
        Vector look = player.getLocation().getDirection().setY(0);
        if (look.lengthSquared() > 1.0E-6) {
            look.normalize().multiply(ctx.params().num("forward"));
        }
        player.setVelocity(look.setY(ctx.params().num("up")));
        player.setFallDistance(0f);
        readyAt = ctx.tick() + ctx.params().ticks("cooldown");
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_BREEZE_JUMP, 0.8f, 1.2f);
    }

    @Override
    public void onRemove() {
        Player player = ctx.player();
        if (survival(player)) {
            player.setFlying(false);
            player.setAllowFlight(false);
        }
    }

    private static boolean survival(Player player) {
        return player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE;
    }

    /** Standing on something (the client's on-ground flag cannot be trusted). */
    private static boolean grounded(Player player) {
        Location below = player.getLocation().subtract(0, 0.2, 0);
        return !below.getBlock().isPassable();
    }
}
