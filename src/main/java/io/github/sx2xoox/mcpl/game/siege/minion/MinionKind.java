package io.github.sx2xoox.mcpl.game.siege.minion;

import io.github.sx2xoox.mcpl.game.siege.creature.CreatureKind;

/** The three minions of a wave (docs/siege-v2.md §2). */
public enum MinionKind {
    /** 태엽 병정. */
    MELEE("melee", CreatureKind.MINION_MELEE, false),
    /** 장난감 궁수. */
    RANGED("ranged", CreatureKind.MINION_RANGED, true),
    /** 장난감 대포: every third wave, tanky, hits from range. */
    CANNON("cannon", CreatureKind.MINION_CANNON, true);

    private final String key;
    private final CreatureKind creature;
    private final boolean ranged;

    MinionKind(String key, CreatureKind creature, boolean ranged) {
        this.key = key;
        this.creature = creature;
        this.ranged = ranged;
    }

    /** Settings and message key. */
    public String key() {
        return key;
    }

    public CreatureKind creature() {
        return creature;
    }

    public boolean ranged() {
        return ranged;
    }
}
