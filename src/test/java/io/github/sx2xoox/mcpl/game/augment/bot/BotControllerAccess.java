package io.github.sx2xoox.mcpl.game.augment.bot;

/** Test access to the controller's switches from other packages. */
public final class BotControllerAccess {

    private BotControllerAccess() {
    }

    /** Turns the stuck detection on or off (the mock pathfinder never moves the legs). */
    public static void autoRecovery(boolean on) {
        BotController.autoRecovery = on;
    }
}
