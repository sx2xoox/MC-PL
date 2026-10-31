package io.github.sx2xoox.mcpl.game.augment.augments;

import java.util.concurrent.ThreadLocalRandom;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 미다스의 곡괭이: stone mined with a pickaxe sometimes yields gold or a diamond. Only stone and deepslate count: they
 * drop cobblestone, so placing and mining them again does not farm the bonus.
 */
public final class MidasPickAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("midas_pick", "미다스의 곡괭이", AugmentTier.PRISM,
                    AugmentKind.RESOURCE, Material.GOLDEN_PICKAXE)
            .description("곡괭이로 돌·심층암을 캘 때 {gold}% 확률로 금 주괴, {diamond}% 확률로 다이아몬드가 함께 떨어집니다"
                    + " (섬세한 손길 제외).")
            .param("gold", 5)
            .param("diamond", 1)
            .build();

    private final AugmentContext ctx;

    public MidasPickAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Material type = block.getType();
        Player player = ctx.player();
        if (type != Material.STONE && type != Material.DEEPSLATE || !event.isDropItems() || !Ores.pickaxe(player)
                || Ores.silkTouch(player)) {
            return;
        }
        Location at = block.getLocation().add(0.5, 0.5, 0.5);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        boolean lucky = false;
        if (random.nextDouble() < ctx.params().fraction("gold")) {
            block.getWorld().dropItemNaturally(at, ItemStack.of(Material.GOLD_INGOT));
            lucky = true;
        }
        if (random.nextDouble() < ctx.params().fraction("diamond")) {
            block.getWorld().dropItemNaturally(at, ItemStack.of(Material.DIAMOND));
            lucky = true;
        }
        if (lucky) {
            block.getWorld().spawnParticle(Particle.WAX_ON, at, 10, 0.3, 0.3, 0.3, 0);
        }
    }
}
