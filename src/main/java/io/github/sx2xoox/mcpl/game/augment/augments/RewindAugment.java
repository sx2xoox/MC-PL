package io.github.sx2xoox.mcpl.game.augment.augments;

import java.util.LinkedList;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import io.github.sx2xoox.mcpl.game.augment.api.Params;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

/**
 * 시간 역행: press the swap-hands key (standing, not crouching, so it does not clash with 점멸) to go back to where
 * the owner was a few seconds ago, with at least the health they had then.
 */
public final class RewindAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("rewind", "시간 역행", AugmentTier.PRISM,
                    AugmentKind.MOBILITY, Material.RECOVERY_COMPASS)
            .description("손 바꾸기 키(F, 웅크리지 않고)를 누르면 {seconds}초 전의 위치로 돌아가고, 체력이 그때보다 적으면"
                    + " 그때 체력이 됩니다 (재사용 {cooldown}초).")
            .param("seconds", 5)
            .param("cooldown", 40)
            .build();

    /** Ticks between two remembered moments. */
    private static final int SAMPLE = 5;

    private record Moment(long tick, Location location, double health) {
    }

    private final AugmentContext ctx;
    private final LinkedList<Moment> history = new LinkedList<>();
    private long readyAt;

    public RewindAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onGrant() {
        readyAt = ctx.tick();
        remember();
    }

    @Override
    public void onTick() {
        if (ctx.tick() % SAMPLE == 0) {
            remember();
        }
    }

    private void remember() {
        Player player = ctx.player();
        long now = ctx.tick();
        Moment last = history.peekLast();
        if (last != null && last.location().getWorld() != player.getWorld()) {
            history.clear();
        }
        history.addLast(new Moment(now, player.getLocation(), player.getHealth()));
        long keep = ctx.params().ticks("seconds");
        // keep the newest moment that is at least `seconds` old
        while (history.size() > 1 && now - history.get(1).tick() >= keep) {
            history.pollFirst();
        }
    }

    @Override
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player player = ctx.player();
        if (player.isSneaking()) {
            return;
        }
        event.setCancelled(true);
        if (ctx.tick() < readyAt) {
            Nearby.cooldown(ctx, readyAt);
            return;
        }
        Moment then = history.peekFirst();
        if (then == null || then.location().getWorld() != player.getWorld()) {
            return;
        }
        Location here = player.getLocation();
        player.getWorld().spawnParticle(Particle.REVERSE_PORTAL, here.clone().add(0, 1, 0), 40, 0.3, 0.7, 0.3, 0.05);
        player.setFallDistance(0f);
        player.teleport(then.location());
        AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
        double cap = max == null ? 20.0 : max.getValue();
        if (then.health() > player.getHealth()) {
            player.setHealth(Math.min(cap, then.health()));
        }
        player.getWorld().spawnParticle(Particle.END_ROD, then.location().clone().add(0, 1, 0), 30, 0.3, 0.7, 0.3,
                0.05);
        player.getWorld().playSound(then.location(), Sound.BLOCK_BEACON_DEACTIVATE, 0.8f, 1.6f);
        ctx.actionBar("augment-fx.rewind", Placeholder.unparsed("seconds",
                Params.format((ctx.tick() - then.tick()) / 20.0)));
        history.clear();
        remember();
        readyAt = ctx.tick() + ctx.params().ticks("cooldown");
    }
}
