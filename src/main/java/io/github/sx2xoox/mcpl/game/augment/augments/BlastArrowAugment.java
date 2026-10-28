package io.github.sx2xoox.mcpl.game.augment.augments;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentKind;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;

/**
 * 폭발 화살: every few bow shots the arrow explodes where it lands. The explosion is only particles and sound plus
 * augment damage to enemies around it: no blocks break and nobody else is hurt.
 */
public final class BlastArrowAugment implements Augment {

    public static final AugmentInfo INFO = AugmentInfo.builder("blast_arrow", "폭발 화살", AugmentTier.GOLD,
                    AugmentKind.COMBAT, Material.TNT)
            .description("활이나 쇠뇌로 {every}발째마다 쏜 화살(빛남)이 맞은 곳에서 작게 폭발해 반경 {radius}블록 안의 적에게"
                    + " 피해 {damage}를 줍니다 (블록은 부서지지 않음).")
            .param("every", 3)
            .param("radius", 3)
            .param("damage", 4)
            .build();

    private final AugmentContext ctx;
    /** Blast arrows in flight. */
    private final Map<UUID, AbstractArrow> armed = new HashMap<>();
    private int shots;

    public BlastArrowAugment(AugmentContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onShootBow(EntityShootBowEvent event) {
        if (!(event.getProjectile() instanceof AbstractArrow arrow)) {
            return;
        }
        armed.values().removeIf(flying -> !flying.isValid());
        shots++;
        if (shots < Math.max(1, ctx.params().integer("every"))) {
            return;
        }
        shots = 0;
        arrow.setGlowing(true);
        armed.put(arrow.getUniqueId(), arrow);
    }

    @Override
    public void onProjectileHit(ProjectileHitEvent event) {
        AbstractArrow arrow = armed.remove(event.getEntity().getUniqueId());
        if (arrow == null) {
            return;
        }
        arrow.setGlowing(false);
        Entity hit = event.getHitEntity();
        explode(hit != null ? hit.getLocation().add(0, hit.getHeight() / 2, 0) : arrow.getLocation());
    }

    private void explode(Location at) {
        World world = at.getWorld();
        world.spawnParticle(Particle.EXPLOSION, at, 1);
        world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.4f);
        double radius = Math.max(0.0, ctx.params().num("radius"));
        double damage = ctx.params().num("damage");
        for (LivingEntity entity : world.getNearbyLivingEntities(at, radius)) {
            if (ctx.isEnemy(entity)
                    && entity.getBoundingBox().getCenter().distanceSquared(at.toVector()) <= radius * radius) {
                ctx.damage(entity, damage);
            }
        }
    }
}
