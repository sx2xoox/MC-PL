package io.github.sx2xoox.mcpl.core.config;

import java.util.List;

import io.github.sx2xoox.mcpl.core.ui.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;

/**
 * A group of {@link Setting}s: one row (or more) of the settings menu and one block of {@code /게임 설정 목록}. The hub
 * has its own section (팀 인원, 카운트다운, 지형 파괴); every mini-game may add more through
 * {@link io.github.sx2xoox.mcpl.core.game.MiniGame#settings()}.
 *
 * @param id       stable id, e.g. {@code "superpower.loot"}
 * @param icon     item of the row header
 * @param messages the file with the texts: {@code <textKey>.name} (title) and {@code <textKey>.lore}
 * @param textKey  key prefix of the texts
 * @param settings the entries, in menu order
 */
public record SettingsSection(String id, Material icon, Messages messages, String textKey, List<Setting> settings) {

    public SettingsSection {
        settings = List.copyOf(settings);
    }

    /** The title, e.g. "초능력전쟁 · 보급 상자". */
    public Component title() {
        return messages.get(textKey + ".name");
    }

    /** The explanation lines of the row header. */
    public List<Component> description() {
        return messages.lines(textKey + ".lore");
    }
}
