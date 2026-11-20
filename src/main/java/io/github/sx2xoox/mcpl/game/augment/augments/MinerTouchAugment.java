package io.github.sx2xoox.mcpl.game.augment.augments;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;

/** 광부의 손길: faster mining. */
public final class MinerTouchAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("miner_touch", "광부의 손길", AugmentTier.SILVER,
                    AugmentKind.STAT, Material.IRON_PICKAXE)
            .description("블록을 부수는 속도가 {speed}% 빨라집니다.")
            .param("speed", 30)
            .build();

    private final AugmentContext ctx;

    public MinerTouchAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onGrant() {
        ctx.addModifier(Attribute.BLOCK_BREAK_SPEED, ctx.params().fraction("speed"),
                AttributeModifier.Operation.ADD_SCALAR);
    }
}
