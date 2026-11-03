package io.github.sx2xoox.mcpl.game.siege.creature;

import java.util.Locale;

import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import org.jspecify.annotations.Nullable;

/**
 * Every creature 공성전 v2 spawns (docs/siege-v2.md §2, §4, §7): the minions, the jungle monsters, the dragon and the
 * shopkeeper. Each has a resource pack model {@link #modelId()} ({@code mcpl:creature/<id>}) the models agent provides;
 * {@link VanillaCreatureLook} is the look without the pack.
 */
public enum CreatureKind {
    /** 태엽 병정: the melee minion. */
    MINION_MELEE,
    /** 장난감 궁수: the ranged minion. */
    MINION_RANGED,
    /** 장난감 대포: the cannon (tank) minion of every third wave. */
    MINION_CANNON,
    /** 젤리: one of the 젤리 무리. */
    JELLY,
    /** 태엽 쥐: one of the 태엽 쥐 떼. */
    MICE,
    /** 곰인형 대장. */
    TEDDY,
    /** 시계 토끼. */
    RABBIT,
    /** 장난감 드래곤. */
    DRAGON,
    /** 태엽 상인: stands at each side's shop terminal. */
    SHOPKEEPER;

    /** Lower-case id ({@code minion_melee}, {@code jelly}, ...). */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The pack model of this creature ({@code mcpl:creature/<id>}). */
    public String modelId() {
        return "mcpl:creature/" + id();
    }

    /** The creature of a jungle objective kind, or null for a capture point (FOUNTAIN, TELESCOPE). */
    public static @Nullable CreatureKind of(LaneSites.ObjectiveKind kind) {
        return switch (kind) {
            case JELLY -> JELLY;
            case MICE -> MICE;
            case TEDDY -> TEDDY;
            case RABBIT -> RABBIT;
            case DRAGON -> DRAGON;
            case FOUNTAIN, TELESCOPE -> null;
        };
    }
}
