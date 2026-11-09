package io.github.sx2xoox.mcpl.core.command;

/** Permission nodes (declared in {@code plugin.yml}). */
public final class Permissions {

    /** Start/stop matches, settings, arena, rotation, reload. Default: op. */
    public static final String ADMIN = "mcpl.admin";

    /** Join/spectate, lists and info. Default: everyone. */
    public static final String PLAY = "mcpl.play";

    private Permissions() {
    }
}
