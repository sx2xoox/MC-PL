package io.github.sx2xoox.mcpl.game.augment.augments;

import java.util.UUID;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDamageEvent;
import org.jspecify.annotations.Nullable;

/** 연타: every third melee hit on the same enemy in quick succession deals bonus damage. */
public final class ComboAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("combo", "연타", AugmentTier.GOLD,
                    AugmentKind.COMBAT, Material.GOLDEN_SWORD)
            .description("같은 적을 {window}초 안에 근접 공격으로 {hits}번 때리면 마지막 공격의 피해가 {damage} 늘어납니다.")
            .param("hits", 3)
            .param("window", 3)
            .param("damage", 4)
            .forBots()
            .build();

    private final AugmentContext ctx;
    private @Nullable UUID target;
    private int count;
    private long chainStart;

    public ComboAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onOutgoingDamage(EntityDamageEvent event, LivingEntity victim) {
        if (!Hits.meleeBy(event, ctx.owner())) {
            return;
        }
        long now = ctx.tick();
        if (!victim.getUniqueId().equals(target) || now - chainStart > ctx.params().ticks("window")) {
            target = victim.getUniqueId();
            count = 0;
            chainStart = now;
        }
        count++;
        if (count < Math.max(1, ctx.params().integer("hits"))) {
            return;
        }
        target = null;
        count = 0;
        event.setDamage(Math.max(0.0, event.getDamage() + ctx.params().num("damage")));
        victim.getWorld().spawnParticle(Particle.CRIT, victim.getLocation().add(0, 1, 0), 20, 0.3, 0.5, 0.3, 0.2);
        victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 0.7f);
    }
}
