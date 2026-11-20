package io.github.sx2xoox.mcpl.game.augment.augments;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

/** 방패술: a shield, and blocking a melee hit with it hurts the attacker. */
public final class ShieldMasterAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("shield_master", "방패술", AugmentTier.SILVER,
                    AugmentKind.COMBAT, Material.SHIELD)
            .description("방패를 받습니다. 방패로 적의 근접 공격을 막으면 공격한 적이 피해 {damage}를 받습니다.")
            .param("damage", 2)
            .build();

    private final AugmentContext ctx;

    public ShieldMasterAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onGrant() {
        ctx.give(ItemStack.of(Material.SHIELD));
    }

    @Override
    public void onIncomingDamage(EntityDamageEvent event) {
        Player player = ctx.player();
        LivingEntity attacker = Hits.attacker(event);
        if (!player.isBlocking() || !Hits.melee(event) || attacker == null || !ctx.isEnemy(attacker)
                || !inFront(player, attacker)) {
            return;
        }
        ctx.damage(attacker, ctx.params().num("damage"));
        player.getWorld().spawnParticle(Particle.CRIT, attacker.getLocation().add(0, 1, 0), 10, 0.3, 0.4, 0.3, 0.1);
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1f, 0.8f);
    }

    /** Like a shield: the attacker stands within 90 degrees of where the owner looks. */
    private static boolean inFront(Player player, LivingEntity attacker) {
        Vector look = player.getLocation().getDirection().setY(0);
        Vector toAttacker = attacker.getLocation().toVector().subtract(player.getLocation().toVector()).setY(0);
        if (look.lengthSquared() < 1.0E-6 || toAttacker.lengthSquared() < 1.0E-6) {
            return true;
        }
        return look.dot(toAttacker) > 0;
    }
}
