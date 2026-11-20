package io.github.sx2xoox.mcpl.game.augment.augments;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Material;

/** 네더라이트 조각: netherite ingots and the upgrade templates to use them (the match world has no nether). */
public final class NetheriteScrapAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("netherite_scrap", "네더라이트 조각", AugmentTier.PRISM,
                    AugmentKind.RESOURCE, Material.NETHERITE_INGOT)
            .description("네더라이트 주괴 {ingots}개와 네더라이트 강화 대장장이 형판 {templates}개를 받습니다 (대장장이 작업대에서"
                    + " 다이아몬드 장비를 강화합니다).")
            .param("ingots", 1)
            .param("templates", 1)
            .build();

    private final AugmentContext ctx;

    public NetheriteScrapAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onGrant() {
        ctx.give(Items.stacks(Material.NETHERITE_INGOT, ctx.params().integer("ingots")));
        ctx.give(Items.stacks(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE, ctx.params().integer("templates")));
    }
}
