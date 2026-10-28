package io.github.sx2xoox.mcpl.game.augment.augments;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jspecify.annotations.Nullable;

/** 복수의 영혼: the enemy player who kills the owner withers and glows for everyone to see. */
public final class VengefulAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("vengeful", "복수의 영혼", AugmentTier.PRISM,
                    AugmentKind.COMBAT, Material.WITHER_ROSE)
            .description("나를 죽인 적 플레이어는 {seconds}초 동안 위더 {level} 효과를 받고, 모두에게 위치가 드러나도록"
                    + " 발광합니다.")
            .param("seconds", 10)
            .param("level", 2)
            .forBots()
            .build();

    private final AugmentContext ctx;

    public VengefulAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onDeath(@Nullable LivingEntity killer) {
        if (killer == null || !ctx.isEnemy(killer)) {
            return;
        }
        int ticks = (int) ctx.params().ticks("seconds");
        if (ticks <= 0) {
            return;
        }
        int amplifier = Math.max(0, ctx.params().integer("level") - 1);
        killer.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, ticks, amplifier, false, true, true));
        killer.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, ticks, 0, false, false, true));
        killer.getWorld().spawnParticle(Particle.SOUL, killer.getLocation().add(0, 1, 0), 25, 0.4, 0.7, 0.4, 0.03);
        killer.getWorld().playSound(killer.getLocation(), Sound.ENTITY_WITHER_AMBIENT, 0.8f, 1.2f);
        ctx.message("augment-fx.vengeful");
    }
}
