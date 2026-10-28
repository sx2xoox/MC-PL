package io.github.sx2xoox.mcpl.game.augment.augments;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** 인어: water breathing and faster swimming. */
public final class SwimmerAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("swimmer", "인어", AugmentTier.SILVER,
                    AugmentKind.MOBILITY, Material.TROPICAL_FISH)
            .description("물속에서 숨이 막히지 않고, 물속 이동 효율이 {swim}% 늘어납니다 (100 = 물갈퀴 III).")
            .param("swim", 60)
            .build();

    private final AugmentContext ctx;

    public SwimmerAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onGrant() {
        ctx.addModifier(Attribute.WATER_MOVEMENT_EFFICIENCY, Math.max(0.0, ctx.params().fraction("swim")),
                AttributeModifier.Operation.ADD_NUMBER);
        breathe();
    }

    @Override
    public void onTick() {
        if (ctx.tick() % 20 == 0) {
            breathe(); // back after drinking milk
        }
    }

    private void breathe() {
        Player player = ctx.player();
        if (!player.hasPotionEffect(PotionEffectType.WATER_BREATHING)) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING, PotionEffect.INFINITE_DURATION,
                    0, true, false, true));
        }
    }

    @Override
    public void onRemove() {
        Player player = ctx.player();
        PotionEffect effect = player.getPotionEffect(PotionEffectType.WATER_BREATHING);
        if (effect != null && effect.isInfinite()) {
            player.removePotionEffect(PotionEffectType.WATER_BREATHING);
        }
    }
}
