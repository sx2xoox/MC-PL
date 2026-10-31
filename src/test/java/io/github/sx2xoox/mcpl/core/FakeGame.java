package io.github.sx2xoox.mcpl.core;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.github.sx2xoox.mcpl.core.game.GameInstance;
import io.github.sx2xoox.mcpl.core.game.GameResult;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.game.MiniGame;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/** A minimal last-player-standing mode used by the hub tests. */
public final class FakeGame implements MiniGame {

    private final String id;
    private final boolean teams;
    public final List<FakeInstance> instances = new ArrayList<>();

    public FakeGame(String id) {
        this(id, false);
    }

    public FakeGame(String id, boolean teams) {
        this.id = id;
        this.teams = teams;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String displayName() {
        return "가짜 게임 " + id;
    }

    @Override
    public Material icon() {
        return Material.STONE;
    }

    @Override
    public List<Component> description() {
        return List.of(Component.text("테스트용"));
    }

    @Override
    public boolean supportsTeams() {
        return teams;
    }

    @Override
    public void configDefaults(ConfigurationSection defaults) {
        defaults.set("round-seconds", 300);
    }

    @Override
    public GameInstance createInstance(GameSession session) {
        FakeInstance instance = new FakeInstance(session);
        instances.add(instance);
        return instance;
    }

    public FakeInstance last() {
        return instances.getLast();
    }

    public static final class FakeInstance implements GameInstance {
        public final GameSession session;
        public int starts;
        public int ticks;
        public int stops;
        public final List<UUID> eliminated = new ArrayList<>();

        FakeInstance(GameSession session) {
            this.session = session;
        }

        @Override
        public void start() {
            starts++;
        }

        @Override
        public void tick() {
            ticks++;
        }

        @Override
        public void onEliminated(Player player, @Nullable Player killer) {
            eliminated.add(player.getUniqueId());
            List<Player> alive = session.alivePlayers();
            if (alive.size() == 1) {
                session.finish(GameResult.winner(alive.getFirst()));
            }
        }

        @Override
        public @Nullable Component playerTag(UUID player) {
            return Component.text("테스터");
        }

        @Override
        public void stop() {
            stops++;
        }
    }
}
