package io.github.sx2xoox.mcpl.game.siege.creature;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Mob;
import org.jspecify.annotations.Nullable;

/**
 * The look the match uses: {@code bodies} spawns the vanilla mob (the hitbox, and what players without the pack see),
 * then {@code skin} dresses it (the pack's 3D toy model for pack players). The skin is removed with the body.
 */
public final class SkinnedCreatureLook implements CreatureLook {

    private final CreatureLook bodies;
    private final CreatureSkin skin;
    private final Map<UUID, CreatureSkin.Visual> visuals = new HashMap<>();

    public SkinnedCreatureLook(CreatureLook bodies, CreatureSkin skin) {
        this.bodies = bodies;
        this.skin = skin;
    }

    @Override
    public Mob spawn(CreatureKind kind, LaneSites.@Nullable Side side, Location at, Consumer<Mob> configure) {
        Mob body = bodies.spawn(kind, side, at, configure);
        Color team = side == null ? null : side == LaneSites.Side.RED ? VanillaCreatureLook.RED : VanillaCreatureLook.BLUE;
        CreatureSkin.Visual visual = skin.attach(body, kind.id(), team);
        if (visual != CreatureSkin.Visual.NONE) {
            visuals.put(body.getUniqueId(), visual);
        }
        return body;
    }

    /** The skin of a live body (to play {@code hit()} / {@code attack()}), or {@link CreatureSkin.Visual#NONE}. */
    public CreatureSkin.Visual visual(Mob body) {
        return visuals.getOrDefault(body.getUniqueId(), CreatureSkin.Visual.NONE);
    }

    @Override
    public void attack(Mob body) {
        visual(body).attack();
        bodies.attack(body);
    }

    @Override
    public void hurt(Mob body) {
        visual(body).hit();
        bodies.hurt(body);
    }

    @Override
    public void removed(Mob body) {
        CreatureSkin.Visual visual = visuals.remove(body.getUniqueId());
        if (visual != null) {
            visual.remove();
        }
        bodies.removed(body);
    }

    @Override
    public void tick(Mob body, CreatureKind kind) {
        bodies.tick(body, kind);
    }
}
