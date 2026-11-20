package io.github.sx2xoox.mcpl.core.ui;

import java.util.ArrayList;
import java.util.List;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.util.Ticks;
import org.bukkit.Bukkit;

/**
 * Title and sound shortcuts. Times are in server ticks (20 per second). Sounds use the {@link Sound.Source#MASTER}
 * channel so they are heard regardless of the player's music/ambient sliders.
 */
public final class Feedback {

    private Feedback() {
    }

    /** Every online player plus the console, as one audience (for announcements that belong in the server log). */
    public static Audience everyone() {
        List<Audience> all = new ArrayList<>(Bukkit.getOnlinePlayers());
        all.add(Bukkit.getConsoleSender());
        return Audience.audience(all);
    }

    /** Every online player, as one audience. */
    public static Audience players() {
        return Audience.audience(List.copyOf(Bukkit.getOnlinePlayers()));
    }

    /** Title times in ticks. */
    public static Title.Times times(int fadeInTicks, int stayTicks, int fadeOutTicks) {
        return Title.Times.times(Ticks.duration(fadeInTicks), Ticks.duration(stayTicks), Ticks.duration(fadeOutTicks));
    }

    public static Title title(Component title, Component subtitle, int fadeInTicks, int stayTicks, int fadeOutTicks) {
        return Title.title(title, subtitle, times(fadeInTicks, stayTicks, fadeOutTicks));
    }

    /** Shows a title with the given timings. */
    public static void title(Audience audience, Component title, Component subtitle,
                             int fadeInTicks, int stayTicks, int fadeOutTicks) {
        audience.showTitle(title(title, subtitle, fadeInTicks, stayTicks, fadeOutTicks));
    }

    /** A short punchy title (fade 0, stay 1s, fade out 0.25s), good for countdown numbers. */
    public static void quickTitle(Audience audience, Component title, Component subtitle) {
        title(audience, title, subtitle, 0, 20, 5);
    }

    /** A master-channel sound. */
    public static Sound sound(org.bukkit.Sound type, float volume, float pitch) {
        return Sound.sound(type, Sound.Source.MASTER, volume, pitch);
    }

    /** Plays a sound at the listener's own position. */
    public static void play(Audience audience, org.bukkit.Sound type, float volume, float pitch) {
        audience.playSound(sound(type, volume, pitch), Sound.Emitter.self());
    }

    /** Plays a sound at volume 1. */
    public static void play(Audience audience, org.bukkit.Sound type, float pitch) {
        play(audience, type, 1.0f, pitch);
    }
}
