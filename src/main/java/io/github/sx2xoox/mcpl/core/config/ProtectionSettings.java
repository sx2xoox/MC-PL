package io.github.sx2xoox.mcpl.core.config;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Which world changes the {@link io.github.sx2xoox.mcpl.core.arena.ProtectionListener} blocks while a match runs
 * ({@code protection.*} in {@code config.yml}). {@code true} means "prevent".
 *
 * @param blockBreak         participants breaking blocks
 * @param blockPlace         participants placing blocks or emptying/filling buckets
 * @param explosions         explosions destroying blocks
 * @param fire               ignition, fire spread and burning blocks
 * @param entityChangeBlock  non-player entities changing blocks (falling blocks, endermen, ...)
 * @param hanging            breaking or manipulating paintings, item frames and armor stands
 * @param interact           using doors, trapdoors, gates, buttons, levers, containers, beds, ...
 * @param itemDrop           participants dropping items
 */
public record ProtectionSettings(
        boolean blockBreak,
        boolean blockPlace,
        boolean explosions,
        boolean fire,
        boolean entityChangeBlock,
        boolean hanging,
        boolean interact,
        boolean itemDrop
) {

    /** Everything protected. */
    public static final ProtectionSettings ALL = new ProtectionSettings(true, true, true, true, true, true, true, true);

    static ProtectionSettings read(ConfigurationSection section) {
        return new ProtectionSettings(
                section.getBoolean("block-break", true),
                section.getBoolean("block-place", true),
                section.getBoolean("explosions", true),
                section.getBoolean("fire", true),
                section.getBoolean("entity-change-block", true),
                section.getBoolean("hanging", true),
                section.getBoolean("interact", true),
                section.getBoolean("item-drop", true));
    }
}
