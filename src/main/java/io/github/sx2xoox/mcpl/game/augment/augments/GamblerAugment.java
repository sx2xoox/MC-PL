package io.github.sx2xoox.mcpl.game.augment.augments;

import java.util.concurrent.ThreadLocalRandom;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Material;

/** 도박꾼: random augments of random tiers, right away and without a choice. */
public final class GamblerAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("gambler", "도박꾼", AugmentTier.GOLD,
                    AugmentKind.SPECIAL, Material.EMERALD)
            .description("무작위 증강 {count}개를 바로 받습니다. 하나마다 등급도 무작위입니다 (실버 {silver}%, 골드 {gold}%,"
                    + " 프리즘 {prism}%).")
            .param("count", 2)
            .param("silver", 50)
            .param("gold", 35)
            .param("prism", 15)
            .build();

    private final AugmentContext ctx;

    public GamblerAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onGrant() {
        for (int i = 0; i < ctx.params().integer("count"); i++) {
            ctx.grantRandom(rollTier());
        }
    }

    private AugmentTier rollTier() {
        double silver = Math.max(0.0, ctx.params().num("silver"));
        double gold = Math.max(0.0, ctx.params().num("gold"));
        double prism = Math.max(0.0, ctx.params().num("prism"));
        double total = silver + gold + prism;
        if (total <= 0) {
            return AugmentTier.SILVER;
        }
        double roll = ThreadLocalRandom.current().nextDouble(total);
        if (roll < silver) {
            return AugmentTier.SILVER;
        }
        return roll < silver + gold ? AugmentTier.GOLD : AugmentTier.PRISM;
    }
}
