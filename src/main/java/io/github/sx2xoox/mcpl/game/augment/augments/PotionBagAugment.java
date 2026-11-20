package io.github.sx2xoox.mcpl.game.augment.augments;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.PotionContents;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionType;

/** 물약 가방: healing, strength and swiftness potions. */
public final class PotionBagAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("potion_bag", "물약 가방", AugmentTier.GOLD,
                    AugmentKind.RESOURCE, Material.BREWING_STAND)
            .description("치유의 물약 {healing}개, 힘의 물약 {strength}개, 신속의 물약 {swiftness}개를 바로 받습니다.")
            .param("healing", 2)
            .param("strength", 2)
            .param("swiftness", 2)
            .build();

    private final AugmentContext ctx;

    public PotionBagAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onGrant() {
        give(PotionType.HEALING, ctx.params().integer("healing"));
        give(PotionType.STRENGTH, ctx.params().integer("strength"));
        give(PotionType.SWIFTNESS, ctx.params().integer("swiftness"));
    }

    private void give(PotionType type, int count) {
        for (int i = 0; i < count; i++) {
            ctx.give(potion(type));
        }
    }

    /** A drinkable potion of {@code type}. */
    public static ItemStack potion(PotionType type) {
        ItemStack potion = ItemStack.of(Material.POTION);
        potion.setData(DataComponentTypes.POTION_CONTENTS, PotionContents.potionContents().potion(type).build());
        return potion;
    }
}
