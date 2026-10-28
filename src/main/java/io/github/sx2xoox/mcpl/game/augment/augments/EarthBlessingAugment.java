package io.github.sx2xoox.mcpl.game.augment.augments;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.event.block.BlockBreakEvent;

/** 대지의 축복: much faster mining and more experience from ores. */
public final class EarthBlessingAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("earth_blessing", "대지의 축복", AugmentTier.PRISM,
                    AugmentKind.RESOURCE, Material.DIAMOND_PICKAXE)
            .description("블록을 부수는 속도가 {speed}% 빨라지고, 광석을 캘 때 나오는 경험치가 {xp}% 늘어납니다.")
            .param("speed", 100)
            .param("xp", 100)
            .build();

    private final AugmentContext ctx;

    public EarthBlessingAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onGrant() {
        ctx.addModifier(Attribute.BLOCK_BREAK_SPEED, ctx.params().fraction("speed"),
                AttributeModifier.Operation.ADD_SCALAR);
    }

    @Override
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!Ores.isOre(block.getType()) || event.getExpToDrop() <= 0) {
            return;
        }
        int extra = (int) Math.round(event.getExpToDrop() * ctx.params().fraction("xp"));
        if (extra > 0) {
            block.getWorld().spawn(block.getLocation().add(0.5, 0.5, 0.5), ExperienceOrb.class,
                    orb -> orb.setExperience(extra));
        }
    }
}
