package io.github.sx2xoox.speedrunbot.server;

/**
 * The keys a client's player holds in a boat (BoatEntity#setInputs exists only in the client jar; the server copy of
 * the boat gets the same fields set through this, see mixin/BoatEntityMixin).
 */
public interface BoatControl {
    void srb$setInputs(boolean left, boolean right, boolean forward, boolean back);
}
