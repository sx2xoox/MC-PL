package io.github.sx2xoox.mcpl.game.augment.augments;

import java.util.ArrayList;
import java.util.List;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wolf;

/**
 * 늑대 친구: tamed wolves that fight for the owner. Their hits count as the owner's (no player damage while farming,
 * no friendly fire), and they drop any target that is not an enemy. They leave with the augment.
 */
public final class BeastFriendAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("beast_friend", "늑대 친구", AugmentTier.GOLD,
                    AugmentKind.COMBAT, Material.BONE)
            .description("길들인 늑대 {wolves}마리가 따라다니며 적만 공격합니다. 경기가 끝나면 사라집니다.")
            .param("wolves", 2)
            .build();

    private final AugmentContext ctx;
    private final List<Wolf> wolves = new ArrayList<>();

    public BeastFriendAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onGrant() {
        Player player = ctx.player();
        int count = ctx.params().integer("wolves");
        for (int i = 0; i < count; i++) {
            double angle = Math.PI * 2 * i / Math.max(1, count);
            Location at = player.getLocation().add(Math.cos(angle), 0, Math.sin(angle));
            Wolf wolf = player.getWorld().spawn(at, Wolf.class, w -> {
                w.setTamed(true);
                w.setOwner(player);
                w.setCollarColor(DyeColor.LIME);
                w.setPersistent(false);
            });
            wolves.add(ctx.summon(wolf));
        }
    }

    @Override
    public void onTick() {
        wolves.removeIf(wolf -> !wolf.isValid());
        for (Wolf wolf : wolves) {
            LivingEntity target = wolf.getTarget();
            if (target != null && (!ctx.isEnemy(target) || target instanceof Player && !ctx.pvp())) {
                wolf.setTarget(null);
                wolf.setAngry(false);
            }
        }
    }

    /** The wolves still alive (tests). */
    public List<Wolf> wolves() {
        wolves.removeIf(wolf -> !wolf.isValid());
        return List.copyOf(wolves);
    }
}
