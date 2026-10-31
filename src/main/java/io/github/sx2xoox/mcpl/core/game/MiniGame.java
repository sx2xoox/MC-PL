package io.github.sx2xoox.mcpl.core.game;

import java.util.List;

import io.github.sx2xoox.mcpl.core.arena.Arena;
import io.github.sx2xoox.mcpl.core.command.GameCommandRegistrar;
import io.github.sx2xoox.mcpl.core.config.ProtectionSettings;
import io.github.sx2xoox.mcpl.core.config.Setting;
import io.github.sx2xoox.mcpl.core.config.SettingsSection;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.jspecify.annotations.Nullable;

/**
 * A game mode the hub can run (초능력전쟁, 랜덤무기전쟁, 살인자를 찾아라, ...). One instance per mode, registered once
 * with {@link GameManager#register(MiniGame)} during plugin enable; it describes the mode and creates a fresh
 * {@link GameInstance} for every match.
 *
 * <h2>Registration example</h2>
 * <pre>{@code
 * public void onEnable() {
 *     hub = new Hub(this);
 *     hub.enable();
 *     hub.games().register(new SuperpowerGame(hub));
 * }
 * }</pre>
 *
 * <p>Everything here is called on the main thread.
 */
public interface MiniGame {

    /**
     * Stable lowercase id ({@code [a-z0-9_]+}), e.g. {@code "superpower"}. Used in {@code config.yml}
     * ({@code games.<id>}, {@code rotation.games}) and accepted by {@code /게임 시작 <id>}.
     */
    String id();

    /** Korean display name, e.g. {@code "초능력전쟁"}. Also accepted by {@code /게임 시작} (spaces ignored). */
    String displayName();

    /** The display name as shown in chat, titles and boss bars. Defaults to the name in gold. */
    default Component displayComponent() {
        return Component.text(displayName(), NamedTextColor.GOLD);
    }

    /** Item that represents the mode in menus and lists. */
    Material icon();

    /** A few short lines describing the mode (shown by {@code /게임 목록}). */
    List<Component> description();

    /** Minimum number of participants to start or continue a countdown. At least 1; defaults to 2. */
    default int minPlayers() {
        return 2;
    }

    /**
     * True for a mode nobody plays in person (a bots-only match): it starts without participants, every online
     * player watches it as a spectator (snapshotted and restored as usual) and joiners never take part. The mode adds
     * its own participants in {@link GameInstance#start()} ({@link GameSession#addBot}); {@link #minPlayers()} is
     * ignored and the match is aborted when nobody is online to watch it during the countdown.
     */
    default boolean spectatorsOnly() {
        return false;
    }

    /** Whether the configured team size applies to this mode. False → always free-for-all. */
    default boolean supportsTeams() {
        return false;
    }

    /** Additional names accepted by {@code /게임 시작} (case and spaces ignored). */
    default List<String> aliases() {
        return List.of();
    }

    /**
     * Creates the per-match logic. Called when a match is created, before the countdown; the session is in
     * {@link GameState#COUNTDOWN} and participants are not yet snapshotted. Keep the constructor cheap: do the real
     * setup in {@link GameInstance#start()}.
     */
    GameInstance createInstance(GameSession session);

    /**
     * The arena of a new match, for a mode that brings its own (e.g. a fresh world created for every match, see
     * {@link io.github.sx2xoox.mcpl.core.world.TempWorlds}); null (default) for the hub's arena ({@code config.yml}
     * or the starter's position). Called by {@link GameManager#start} before the session is created, so the countdown
     * already runs while the arena gets ready. Every arena returned here is handed back to {@link #releaseArena}.
     *
     * @throws GameStartException with a ready-to-send Korean message if no arena can be made
     */
    default @Nullable Arena createArena() throws GameStartException {
        return null;
    }

    /**
     * The match played in an arena from {@link #createArena} is over: everyone online was restored and taken out of
     * it (also called if the match could not start). Delete the arena's world here.
     */
    default void releaseArena(Arena arena) {
    }

    /**
     * Declares default settings for {@code games.<id>.*} in {@code config.yml}. Set values (and optionally comments
     * with {@code section.setComments(key, lines)}) on the given scratch section; keys missing from the file are
     * added and saved, keys the server owner changed are kept. Read the live values with
     * {@code hub.config().gameSection(id())} or {@link GameSession#gameConfig()}.
     */
    default void configDefaults(ConfigurationSection defaults) {
    }

    /**
     * Adds mode-specific subcommands under {@code /게임} (e.g. {@code /게임 능력 ...}). Called every time the command
     * tree is (re)built, so create fresh builders each call.
     */
    default void registerCommands(GameCommandRegistrar registrar) {
    }

    /**
     * The settings of this mode an admin can change in game: rows of the settings menu ({@code /게임 설정}) and
     * subcommands of {@code /게임 설정} (e.g. {@code /게임 설정 상자 켜기}), shown after the hub's own. Build the
     * entries with {@link Setting#builder(String)}; their setters should write through
     * {@link io.github.sx2xoox.mcpl.core.config.HubConfig#setGameValue} so the value is saved at once. The hub checks
     * the permission and that {@code config.yml} can be written before calling a setter, and refreshes open menus
     * after it. Called whenever the menu opens or the command tree is built; return the same objects each time.
     */
    default List<SettingsSection> settings() {
        return List.of();
    }

    /**
     * World protection used while this mode runs. Defaults to the configured settings; a mode that needs players to
     * build may relax them, e.g. {@code return new ProtectionSettings(false, false, ...)}.
     */
    default ProtectionSettings protection(ProtectionSettings configured) {
        return configured;
    }

    /** {@code /게임 리로드} was used: re-read your own files. {@code config.yml} was already reloaded. */
    default void onReload() {
    }

    /** The plugin is disabling (after any running match was aborted). */
    default void onShutdown() {
    }
}
