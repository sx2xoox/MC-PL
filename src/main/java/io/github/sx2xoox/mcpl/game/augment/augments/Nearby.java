package io.github.sx2xoox.mcpl.game.augment.augments;

import java.util.List;

import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.LivingEntity;
import org.jspecify.annotations.Nullable;

/** Enemies around the owner and small UI helpers shared by the information and active augments. */
final class Nearby {

    private Nearby() {
    }

    /** Enemy combatants in the owner's world: enemy players, bots and practice dummies ({@code ctx.rivals()}). */
    static List<LivingEntity> enemyPlayers(AugmentContext ctx) {
        return ctx.rivals();
    }

    /** The nearest enemy combatant in the owner's world, or null. */
    static @Nullable LivingEntity nearestEnemy(AugmentContext ctx) {
        LivingEntity owner = ctx.owner();
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity enemy : enemyPlayers(ctx)) {
            double distance = enemy.getLocation().distanceSquared(owner.getLocation());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = enemy;
            }
        }
        return best;
    }

    /** Tells the owner (action bar) how long until an active augment can be used again. */
    static void cooldown(AugmentContext ctx, long readyAt) {
        long seconds = Math.max(1, (readyAt - ctx.tick() + 19) / 20);
        ctx.actionBar("augment-fx.cooldown",
                Placeholder.component("augment", Component.text(ctx.info().name(), ctx.info().tier().color())),
                Placeholder.unparsed("seconds", Long.toString(seconds)));
    }
}
