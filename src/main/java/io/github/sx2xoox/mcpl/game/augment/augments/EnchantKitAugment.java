package io.github.sx2xoox.mcpl.game.augment.augments;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemEnchantments;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;

/** 마법 서고: enchanted books with random useful enchantments. */
public final class EnchantKitAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("enchant_kit", "마법 서고", AugmentTier.GOLD,
                    AugmentKind.RESOURCE, Material.BOOKSHELF)
            .description("서로 다른 무작위 마법이 담긴 마법 부여 책 {books}권을 받습니다 (모루로 붙입니다).")
            .param("books", 3)
            .build();

    /** Enchantments worth having in a survival fight (no curses, no fishing). */
    static List<Enchantment> pool() {
        return List.of(Enchantment.SHARPNESS, Enchantment.PROTECTION, Enchantment.PROJECTILE_PROTECTION,
                Enchantment.FEATHER_FALLING, Enchantment.EFFICIENCY, Enchantment.FORTUNE, Enchantment.UNBREAKING,
                Enchantment.POWER, Enchantment.PUNCH, Enchantment.FLAME, Enchantment.INFINITY, Enchantment.FIRE_ASPECT,
                Enchantment.KNOCKBACK, Enchantment.LOOTING, Enchantment.THORNS, Enchantment.BLAST_PROTECTION);
    }

    private final AugmentContext ctx;

    public EnchantKitAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onGrant() {
        List<Enchantment> pool = new ArrayList<>(pool());
        Collections.shuffle(pool, ThreadLocalRandom.current());
        int books = Math.min(pool.size(), ctx.params().integer("books"));
        for (int i = 0; i < books; i++) {
            Enchantment enchantment = pool.get(i);
            int level = 1 + ThreadLocalRandom.current().nextInt(Math.max(1, enchantment.getMaxLevel()));
            ctx.give(book(enchantment, level));
        }
    }

    /** An enchanted book holding one enchantment. */
    public static ItemStack book(Enchantment enchantment, int level) {
        ItemStack book = ItemStack.of(Material.ENCHANTED_BOOK);
        book.setData(DataComponentTypes.STORED_ENCHANTMENTS,
                ItemEnchantments.itemEnchantments().add(enchantment, level).build());
        return book;
    }
}
