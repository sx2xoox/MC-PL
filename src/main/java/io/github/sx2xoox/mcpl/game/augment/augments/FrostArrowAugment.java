package io.github.sx2xoox.mcpl.game.augment.augments;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** 얼음 화살: arrows slow the enemies they hit. */
public final class FrostArrowAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("frost_arrow", "얼음 화살", AugmentTier.GOLD,
                    AugmentKind.COMBAT, Material.PACKED_ICE)
            .description("화살에 맞은 적이 {seconds}초 동안 구속 {level}에 걸립니다.")
            .param("seconds", 2)
            .param("level", 2)
            .build();

    private final AugmentContext ctx;

    public FrostArrowAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onOutgoingDamage(EntityDamageEvent event, LivingEntity victim) {
        int ticks = (int) ctx.params().ticks("seconds");
        if (Hits.arrow(event) == null || ticks <= 0) {
            return;
        }
        int amplifier = Math.max(0, ctx.params().integer("level") - 1);
        victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, amplifier, false, true, true));
        victim.getWorld().spawnParticle(Particle.SNOWFLAKE, victim.getLocation().add(0, 1, 0), 15, 0.3, 0.5, 0.3, 0.02);
    }
}
