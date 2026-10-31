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
 * 봇 관전전 — a 증강 야생전 match only bots play: every human online watches (spectator mode, snapshot and restore
 * as usual) while the bots fight to the last one (or team), on the quicker {@code games.augment.bots.spectate.*}
 * timings. Started with {@code /게임 증강 봇전 관전 [봇 수] [난이도 | 난이도별 수]}; never part of the rotation.
 */
public final class AugmentSpectateGame implements MiniGame {

    public static final String ID = "augment_spectate";

    private final AugmentGame game;

    AugmentSpectateGame(AugmentGame game) {
        this.game = game;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "증강 봇 관전전";
    }

    @Override
    public Component displayComponent() {
        return game.messages().get("spectate.name");
    }

    @Override
    public List<String> aliases() {
        return List.of("봇관전전", "관전전", "증강관전전", "augmentspectate");
    }

    @Override
    public Material icon() {
        return Material.ENDER_EYE;
    }

    @Override
    public List<Component> description() {
        return game.messages().lines("spectate.game-description");
    }

    @Override
    public boolean spectatorsOnly() {
        return true;
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
        return game.createArena(MatchMode.SPECTATE);
    }

    @Override
    public void releaseArena(Arena arena) {
        game.releaseArena(arena);
    }

    @Override
    public GameInstance createInstance(GameSession session) {
        return game.createMatch(session, MatchMode.SPECTATE);
    }

    public AugmentGame augment() {
        return game;
    }
}
