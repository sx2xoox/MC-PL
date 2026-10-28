package io.github.sx2xoox.mcpl.game.augment.augments;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/** 대장장이의 축복: a full iron armor set (worn where nothing is) and iron tools. */
public final class SmithBlessingAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("smith_blessing", "대장장이의 축복", AugmentTier.GOLD,
                    AugmentKind.RESOURCE, Material.ANVIL)
            .description("철 갑옷 한 벌(비어 있는 부위는 바로 착용)과 철 곡괭이·철 도끼를 받습니다.")
            .build();

    private final AugmentContext ctx;

    public SmithBlessingAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onGrant() {
        wear(EquipmentSlot.HEAD, Material.IRON_HELMET);
        wear(EquipmentSlot.CHEST, Material.IRON_CHESTPLATE);
        wear(EquipmentSlot.LEGS, Material.IRON_LEGGINGS);
        wear(EquipmentSlot.FEET, Material.IRON_BOOTS);
        ctx.give(ItemStack.of(Material.IRON_PICKAXE), ItemStack.of(Material.IRON_AXE));
    }

    private void wear(EquipmentSlot slot, Material armor) {
        PlayerInventory inventory = ctx.player().getInventory();
        if (inventory.getItem(slot).isEmpty()) {
            inventory.setItem(slot, ItemStack.of(armor));
        } else {
            ctx.give(ItemStack.of(armor));
        }
    }
}
