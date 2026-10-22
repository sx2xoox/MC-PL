package io.github.sx2xoox.mcpl.game.superpower.abilities.kit;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityContext;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Timed stealth that hides the owner from enemy players completely ({@code hidePlayer}: body, armor, held item,
 * name tag and tab list entry). Allies and spectators are not touched; abilities usually add a particle-less
 * invisibility effect for them.
 *
 * <p>The bookkeeping lives here so the owner is always shown again: call {@link #end} when the stealth breaks and
 * from {@code onRevoke}. A newer {@link #begin} supersedes the expiry of an older one.
 */
public final class Stealth {

    private final Set<UUID> hiddenFrom = new HashSet<>();
    private boolean active;
    private int generation;
    private long endTick;

    /**
     * Hides the owner from every alive enemy for {@code ticks}; {@code onExpire} runs when the time is up, unless
     * {@link #end} or another {@code begin} came first.
     */
    public void begin(AbilityContext ctx, Player owner, long ticks, Runnable onExpire) {
        active = true;
        int token = ++generation;
        endTick = ctx.currentTick() + ticks;
        hideFromEnemies(ctx, owner);
        ctx.runLater(owner, ticks, () -> {
            if (active && token == generation) {
                onExpire.run();
            }
        });
    }

    /** Hides the owner from alive enemies it is not hidden from yet (call it now and then while active). */
    public void hideFromEnemies(AbilityContext ctx, Player owner) {
        if (!active) {
            return;
        }
        for (Player viewer : ctx.alivePlayers()) {
            if (!viewer.equals(owner) && ctx.isEnemy(owner, viewer) && hiddenFrom.add(viewer.getUniqueId())) {
                viewer.hidePlayer(ctx.plugin(), owner);
            }
        }
    }

    /**
     * Ends the stealth: everyone the owner was hidden from sees it again (also works when the owner is offline).
     *
     * @return false if the stealth was not active
     */
    public boolean end(AbilityContext ctx, Player owner) {
        if (!active) {
            return false;
        }
        active = false;
        generation++;
        for (UUID id : hiddenFrom) {
            Player viewer = Bukkit.getPlayer(id);
            if (viewer != null) {
                viewer.showPlayer(ctx.plugin(), owner);
            }
        }
        hiddenFrom.clear();
        return true;
    }

    public boolean isActive() {
        return active;
    }

    /** Ticks until the current stealth expires, 0 if it is not active. */
    public long ticksLeft(long now) {
        return active ? Math.max(0, endTick - now) : 0;
    }

    /** Enemy mobs within {@code range} that are chasing the owner lose their target. */
    public static void dropAggro(AbilityContext ctx, Player owner, double range) {
        redirectAggro(ctx, owner, range, mob -> null);
    }

    /** Enemy mobs within {@code range} that are chasing the owner switch to {@code newTarget} (null: no target). */
    public static void redirectAggro(AbilityContext ctx, Player owner, double range,
                                     Function<Mob, @Nullable LivingEntity> newTarget) {
        for (Entity entity : owner.getNearbyEntities(range, range / 2, range)) {
            if (entity instanceof Mob mob && owner.equals(mob.getTarget()) && ctx.isEnemy(owner, mob)) {
                mob.setTarget(newTarget.apply(mob));
            }
        }
    }
}
