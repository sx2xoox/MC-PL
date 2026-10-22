package io.github.sx2xoox.mcpl.game.superpower;

import java.util.List;

import io.github.sx2xoox.mcpl.core.game.GameInstance;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.game.MiniGame;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/**
 * 능력 연습 — the 초능력전쟁 abilities alone (or with friends) against training dummies, without the two-player minimum
 * of a real match. It runs the real {@link io.github.sx2xoox.mcpl.game.superpower.runtime.SuperpowerMatch} in practice
 * mode, so abilities behave and look exactly like in a match. Started with {@code /게임 능력 연습 [능력]} (only the
 * sender plays) or {@code /게임 시작 연습} (everyone plays); never part of the rotation unless the owner adds
 * {@code practice} to {@code rotation.games} by hand. See {@code docs/superpower.md}.
 *
 * <p>Owned by {@link SuperpowerGame} (registry, messages, abilities and the {@code /게임 능력} commands are shared).
 */
public final class SuperpowerPracticeGame implements MiniGame {

    public static final String ID = "practice";

    private final SuperpowerGame game;

    SuperpowerPracticeGame(SuperpowerGame game) {
        this.game = game;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "능력 연습";
    }

    @Override
    public Component displayComponent() {
        return game.messages().get("practice.name");
    }

    @Override
    public List<String> aliases() {
        return List.of("연습", "능력연습", "practice");
    }

    @Override
    public Material icon() {
        return Material.TARGET;
    }

    @Override
    public List<Component> description() {
        return game.messages().lines("practice.description");
    }

    @Override
    public int minPlayers() {
        return 1;
    }

    @Override
    public void configDefaults(ConfigurationSection defaults) {
        PracticeSettings.declareDefaults(defaults);
    }

    @Override
    public GameInstance createInstance(GameSession session) {
        return game.createMatch(session, true);
    }

    /** The 초능력전쟁 mode this practice belongs to. */
    public SuperpowerGame superpower() {
        return game;
    }
}
