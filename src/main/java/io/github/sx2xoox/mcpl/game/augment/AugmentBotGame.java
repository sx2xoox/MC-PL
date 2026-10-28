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
 * 증강 봇전 — a real 증강 야생전 match in which bots fill the lobby, so one player (or a few) can play the mode.
 * Started with {@code /게임 증강 봇전 [봇 수] [난이도]} (defaults in {@code games.augment.bots}); never part of the
 * rotation unless the owner adds {@code augment_bots} to {@code rotation.games} by hand. See
 * {@link io.github.sx2xoox.mcpl.game.augment.bot.BotManager} and {@code docs/augment.md}.
 */
public final class AugmentBotGame implements MiniGame {

    public static final String ID = "augment_bots";

    private final AugmentGame game;

    AugmentBotGame(AugmentGame game) {
        this.game = game;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "증강 봇전";
    }

    @Override
    public Component displayComponent() {
        return game.messages().get("bots.name");
    }

    @Override
    public List<String> aliases() {
        return List.of("봇전", "증강봇전", "augmentbots");
    }

    @Override
    public Material icon() {
        return Material.ARMOR_STAND;
    }

    @Override
    public List<Component> description() {
        return game.messages().lines("bots.game-description");
    }

    @Override
    public int minPlayers() {
        return 1;
    }

    @Override
    public boolean supportsTeams() {
        return true;
    }

    @Override
    public ProtectionSettings protection(ProtectionSettings configured) {
        return game.protection(configured);
    }

    @Override
    public Arena createArena() throws GameStartException {
        return game.createArena(MatchMode.BOTS);
    }

    @Override
    public void releaseArena(Arena arena) {
        game.releaseArena(arena);
    }

    @Override
    public GameInstance createInstance(GameSession session) {
        return game.createMatch(session, MatchMode.BOTS);
    }

    public AugmentGame augment() {
        return game;
    }
}
