package io.github.sx2xoox.mcpl.game.siege.creature;

import java.util.function.Consumer;

import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import org.bukkit.Location;
import org.bukkit.entity.Mob;
import org.jspecify.annotations.Nullable;

/**
 * How a 공성전 v2 creature looks: the seam between the lane systems (which drive a {@link Mob} body: its health,
 * hitbox, pathfinding and attacks) and the models.
 *
 * <p>{@link VanillaCreatureLook} is the look for everyone (team-coloured vanilla mobs). A pack look (models agent)
 * spawns the same vanilla body, so every rule keeps working, and adds the model {@link CreatureKind#modelId()}
 * ({@code mcpl:creature/<kind>}) on an item display riding it for {@code PackAudience#hasPack} viewers only, hiding the
 * body from those viewers ({@code Player#hideEntity}) and leaving it as the fallback for everyone else; it removes its
 * displays in {@link #removed}. The body must stay a {@link Mob} of the size the settings give
 * ({@code Attribute.SCALE}), since it is the hitbox.
 */
public interface CreatureLook {

    /**
     * Spawns the body of a creature at {@code at}. {@code configure} (health, name, AI) runs inside the spawn, before
     * the body is added to the world; the look's own dressing runs before it.
     *
     * @param side the team of a minion or shopkeeper, null for neutral monsters
     */
    Mob spawn(CreatureKind kind, LaneSites.@Nullable Side side, Location at, Consumer<Mob> configure);

    /** The body is gone (died or removed): the look drops whatever it added. */
    default void removed(Mob body) {
    }

    /** Called every tick for each live body (animations); nothing by default. */
    default void tick(Mob body, CreatureKind kind) {
    }
}
