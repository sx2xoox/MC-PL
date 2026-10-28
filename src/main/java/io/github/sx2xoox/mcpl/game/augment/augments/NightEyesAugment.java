package io.github.sx2xoox.mcpl.game.augment.augments;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** 올빼미 눈: permanent night vision (given back after milk). */
public final class NightEyesAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("night_eyes", "올빼미 눈", AugmentTier.SILVER,
                    AugmentKind.STAT, Material.ENDER_EYE)
            .description("야간 투시가 계속 유지되어 동굴 속과 밤에도 잘 보입니다.")
            .build();

    private final AugmentContext ctx;

    public NightEyesAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onGrant() {
        apply();
    }

    @Override
    public void onTick() {
        if (ctx.tick() % 20 == 0) {
            apply();
        }
    }

    private void apply() {
        Player player = ctx.player();
        PotionEffect current = player.getPotionEffect(PotionEffectType.NIGHT_VISION);
        if (current == null || !current.isInfinite()) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, PotionEffect.INFINITE_DURATION, 0,
                    true, false, true));
        }
    }

    @Override
    public void onRemove() {
        Player player = ctx.player();
        PotionEffect current = player.getPotionEffect(PotionEffectType.NIGHT_VISION);
        if (current != null && current.isInfinite()) {
            player.removePotionEffect(PotionEffectType.NIGHT_VISION);
        }
    }
}
