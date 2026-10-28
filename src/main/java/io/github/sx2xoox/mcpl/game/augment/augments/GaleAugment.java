package io.github.sx2xoox.mcpl.game.augment.augments;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;

/** 질풍: faster movement. */
public final class GaleAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("gale", "질풍", AugmentTier.SILVER,
                    AugmentKind.STAT, Material.SUGAR)
            .description("이동 속도가 {speed}% 빨라집니다.")
            .param("speed", 15)
            .build();

    private final AugmentContext ctx;

    public GaleAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onGrant() {
        ctx.addModifier(Attribute.MOVEMENT_SPEED, ctx.params().fraction("speed"),
                AttributeModifier.Operation.ADD_SCALAR);
    }
}
