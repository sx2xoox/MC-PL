package io.github.sx2xoox.mcpl.game.augment.augments;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/** 발소리: now and then, particles at the feet of nearby enemy players that only the owner sees. */
public final class FootstepsAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("footsteps", "발소리", AugmentTier.SILVER,
                    AugmentKind.SPECIAL, Material.SCULK_SENSOR)
            .description("{interval}초마다 {radius}블록 안에 있는 적 플레이어의 발밑에 나에게만 보이는 붉은 발자국이"
                    + " 찍힙니다 (벽 너머도).")
            .param("interval", 3)
            .param("radius", 24)
            .build();

    private static final Particle.DustOptions STEP = new Particle.DustOptions(Color.fromRGB(0xE0, 0x30, 0x30), 1.6f);

    private final AugmentContext ctx;

    public FootstepsAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onTick() {
        long every = Math.max(1, ctx.params().ticks("interval"));
        if (ctx.tick() % every != 0) {
            return;
        }
        Player owner = ctx.player();
        double radius = ctx.params().num("radius");
        for (LivingEntity enemy : Nearby.enemyPlayers(ctx)) {
            Location feet = enemy.getLocation();
            if (feet.distanceSquared(owner.getLocation()) > radius * radius) {
                continue;
            }
            // Player#spawnParticle: sent to the owner alone; force = seen from far away
            owner.spawnParticle(Particle.DUST, feet.getX(), feet.getY() + 0.1, feet.getZ(), 8, 0.25, 0.02, 0.25, 0,
                    STEP, true);
        }
    }
}
