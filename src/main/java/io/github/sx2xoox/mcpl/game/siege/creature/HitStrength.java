package io.github.sx2xoox.mcpl.game.siege.creature;

/**
 * How hard a landed hit on a 공성전 creature (or structure) reads (the hit feel): {@link #LIGHT} for burns, poisons and
 * other small ticks (a small flinch, no hitstop, a quiet sound), {@link #NORMAL} for a plain hit, {@link #STRONG} for
 * a basic attack's critical hit or combo finisher and a big ability blow (a longer hitstop, a bigger flinch, more
 * sparks, a louder layer).
 */
public enum HitStrength {
    LIGHT(0.45f),
    NORMAL(1f),
    STRONG(1.45f);

    private final float amount;

    HitStrength(float amount) {
        this.amount = amount;
    }

    /** How big the reaction is against a normal hit (1). */
    public float amount() {
        return amount;
    }
}
