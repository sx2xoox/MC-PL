package io.github.sx2xoox.mcpl.game.superpower.support;

import io.github.sx2xoox.mcpl.core.Hub;

/**
 * Turns the MCPL resource pack off in a mock server (on by default in {@code config.yml}): MockBukkit has neither
 * Paper's channel listeners nor the players' connect address, so a match harness plays with the vanilla looks and
 * hosts nothing ({@code ResourcePackSettingsTest}, {@code SamePortHostTest} cover the pack).
 */
public final class TestPack {

    private TestPack() {
    }

    public static void disable(Hub hub) {
        hub.plugin().getConfig().set("resource-pack.enabled", false);
        hub.plugin().saveConfig();
        hub.config().load();
        hub.resourcePacks().reload();
    }
}
