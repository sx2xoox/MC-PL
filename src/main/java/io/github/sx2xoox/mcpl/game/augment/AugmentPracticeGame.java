package io.github.sx2xoox.mcpl.game.augment;

import java.util.List;

import io.github.sx2xoox.mcpl.core.arena.Arena;
import io.github.sx2xoox.mcpl.core.config.ProtectionSettings;
import io.github.sx2xoox.mcpl.core.game.GameInstance;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.game.GameStartException;
import io.github.sx2xoox.mcpl.core.game.MiniGame;
import io.github.sx2xoox.mcpl.game.augment.runtime.MatchMode;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;

/**
 * 증강 연습 — the 증강 야생전 augments alone (or with friends), without the two-player minimum of a real match. It runs
 * the real {@link io.github.sx2xoox.mcpl.game.augment.runtime.AugmentMatch} in {@link MatchMode#PRACTICE} in a fresh
 * (smaller) temp world: no timed rounds, phases advance by command, augments are granted and removed by command and
 * dummies stand in for enemies. Started with {@code /게임 증강 연습}; never part of the rotation unless the owner adds
 * {@code augment_practice} to {@code rotation.games} by hand. See {@code docs/augment.md}.
 *
 * <p>Owned by {@link AugmentGame} (registry, messages, augments and the {@code /게임 증강} commands are shared).
 */
public final class AugmentPracticeGame implements MiniGame {

    public static final String ID = "augment_practice";

    private final AugmentGame game;

    AugmentPracticeGame(AugmentGame game) {
        this.game = game;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "증강 연습";
    }

    @Override
    public Component displayComponent() {
        return game.messages().get("practice.name");
    }

    @Override
    public List<String> aliases() {
        return List.of("증강연습", "augmentpractice");
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
    public ProtectionSettings protection(ProtectionSettings configured) {
        return game.protection(configured);
    }

    @Override
    public Arena createArena() throws GameStartException {
        return game.createArena(MatchMode.PRACTICE);
    }

    @Override
    public void releaseArena(Arena arena) {
        game.releaseArena(arena);
    }

    @Override
    public GameInstance createInstance(GameSession session) {
        return game.createMatch(session, MatchMode.PRACTICE);
    }

    /** The 증강 야생전 mode this practice belongs to. */
    public AugmentGame augment() {
        return game;
    }
}
