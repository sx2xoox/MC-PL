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

/** 질주 본능: after sprinting for a while, Speed until the sprint stops. */
public final class SprinterAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("sprinter", "질주 본능", AugmentTier.GOLD,
                    AugmentKind.MOBILITY, Material.GOLDEN_BOOTS)
            .description("{seconds}초 넘게 쉬지 않고 달리면 달리는 동안 신속 {level} 효과를 받습니다.")
            .param("seconds", 3)
            .param("level", 2)
            .build();

    /** How long one refresh of the effect lasts (it fades this long after the sprint stops). */
    static final int EFFECT_TICKS = 30;
    private static final int REFRESH_TICKS = 10;

    private final AugmentContext ctx;
    private long sprintSince = -1;

    public SprinterAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onTick() {
        Player player = ctx.player();
        if (!player.isSprinting()) {
            sprintSince = -1;
            return;
        }
        if (sprintSince < 0) {
            sprintSince = ctx.tick();
        }
        long boosted = ctx.tick() - sprintSince - ctx.params().ticks("seconds");
        if (boosted >= 0 && boosted % REFRESH_TICKS == 0) {
            int amplifier = Math.max(0, ctx.params().integer("level") - 1);
            player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, EFFECT_TICKS, amplifier, true, false,
                    true));
        }
    }
}
