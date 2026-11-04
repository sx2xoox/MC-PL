package io.github.sx2xoox.mcpl.game.siege.creature;

import java.util.function.Consumer;

import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Sheep;
import org.jspecify.annotations.Nullable;

/**
 * The bodies of 공성전 v2 creatures (docs/siege-v2.md §7.1): <b>one neutral mob for every kind</b> — minions, jungle
 * monsters, the dragon and the shopkeeper — invisible to everyone and silent, only the hitbox the rules hit and the
 * legs the pathfinder walks. What players see and hear is the toy ({@link ModelCreatureLook}: the pack's model, its
 * own gait, attacks and step sounds), so nothing of a vanilla mob's movement, size, sounds or animations gives a
 * creature away (a vanilla rabbit's jumps, a slime's hops, a silverfish's scuttle, a ravager's charge).
 *
 * <p>The body is a sheep ({@link #TYPE}): a plain walker (ground navigation, the standard move control, no jump of its
 * own), no daylight burning or water conversion, not despawned in peaceful, a 0.9 × 1.3 hitbox that a uniform
 * {@code Attribute.SCALE} fits to every toy ({@link CreatureModels#BODY_SCALES} × the settings' size). Its vanilla
 * goals are removed and players' clicks on it are cancelled ({@link ModelCreatureLook}), sheared so it has nothing to
 * give. Every body is spawned without vanilla's random spawn data (no lamb, no random colour).
 */
public final class CreatureBodies implements CreatureLook {

    /** The one entity type of every creature body. */
    public static final EntityType TYPE = EntityType.SHEEP;

    /** Team colours (minion uniforms, toy armour). */
    public static final Color BLUE = Color.fromRGB(0x4F, 0x8D, 0xF5);
    public static final Color RED = Color.fromRGB(0xEF, 0x5A, 0x6F);

    @Override
    public Mob spawn(CreatureKind kind, LaneSites.@Nullable Side side, Location at, Consumer<Mob> configure) {
        return at.getWorld().spawn(at, Sheep.class, false, sheep -> {
            optional(sheep::setAdult);
            optional(() -> sheep.setSheared(true));
            sheep.setInvisible(true);
            configure.accept(sheep);
            // the settings give the toy's size (1 = as modelled); the body is that toy's size of this one mob
            AttributeInstance scale = sheep.getAttribute(Attribute.SCALE);
            if (scale != null) {
                scale.setBaseValue(scale.getBaseValue() * bodyScale(kind));
            }
        });
    }

    /** The body scale of a kind at size 1 ({@link CreatureModels#BODY_SCALES}). */
    public static double bodyScale(CreatureKind kind) {
        String model = CreatureModels.kindOf(kind.id());
        return model == null ? 1 : CreatureModels.BODY_SCALES.getOrDefault(model, 1.0);
    }

    /** The toy size of a body: its scale over its kind's body scale (1 = as modelled). */
    public static double size(org.bukkit.entity.LivingEntity body, String kind) {
        AttributeInstance scale = body.getAttribute(Attribute.SCALE);
        String model = CreatureModels.kindOf(kind);
        double base = model == null ? 1 : CreatureModels.BODY_SCALES.getOrDefault(model, 1.0);
        return scale == null ? 1 : Math.max(0.1, scale.getBaseValue() / base);
    }

    /** Vanilla behaviour a test server may not implement (the creature works without it). */
    static void optional(Runnable call) {
        try {
            call.run();
        } catch (RuntimeException testServer) {
            // MockBukkit's UnimplementedOperationException: cosmetic only.
        }
    }
}
