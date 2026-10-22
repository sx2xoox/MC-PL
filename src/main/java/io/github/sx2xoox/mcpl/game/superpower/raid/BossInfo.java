package io.github.sx2xoox.mcpl.game.superpower.raid;

import java.util.Objects;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.format.TextColor;

/**
 * Static description of a raid boss.
 *
 * @param id       stable lowercase id
 * @param name     Korean display name, e.g. {@code "고대 수호 골렘"}
 * @param epithet  short Korean subtitle shown on its entrance, e.g. {@code "잠에서 깨어난 산"}
 * @param color    theme color for names and titles
 * @param barColor boss bar color
 */
public record BossInfo(String id, String name, String epithet, TextColor color, BossBar.Color barColor) {

    public BossInfo {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(epithet, "epithet");
        Objects.requireNonNull(color, "color");
        Objects.requireNonNull(barColor, "barColor");
    }
}
