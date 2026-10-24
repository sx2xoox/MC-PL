package io.github.sx2xoox.mcpl.core.game;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.regex.Pattern;

import io.github.sx2xoox.mcpl.core.Hub;
import io.github.sx2xoox.mcpl.core.arena.Arena;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Registry of {@link MiniGame}s and owner of the (single) running {@link GameSession}, the participation
 * preferences ({@code /게임 참가}, {@code /게임 관전}) and the {@link Rotation}.
 */
public final class GameManager {

    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_]+");

    private final Hub hub;
    private final Map<String, MiniGame> games = new LinkedHashMap<>();
    private final Set<UUID> spectatorPreference = new LinkedHashSet<>();
    private final File preferencesFile;
    private final Rotation rotation;
    private @Nullable GameSession session;
    private @Nullable String lastGameId;
    private @Nullable Arena lastArena;
    private boolean shuttingDown;
    private boolean rotationStarted;
    private boolean preferencesBroken;

    public GameManager(Hub hub) {
        this.hub = hub;
        this.preferencesFile = new File(hub.plugin().getDataFolder(), "players.yml");
        this.rotation = new Rotation(hub, this);
    }

    /** Loads preferences. The rotation starts with {@link #startRotation()} once every built-in mode is registered. */
    public void enable() {
        loadPreferences();
    }

    /**
     * Starts the rotation (schedules the first match if it is enabled). Call once after all built-in modes are
     * registered, so the first match follows the configured order; modes registered later join a running rotation.
     */
    public void startRotation() {
        rotationStarted = true;
        if (!shuttingDown && rotation.isEnabled() && session == null && !rotation.isWaiting() && !games.isEmpty()) {
            rotation.schedule();
        }
    }

    // ------------------------------------------------------------------ registry

    /**
     * Registers a mode. Its config defaults ({@link MiniGame#configDefaults}) are merged into {@code config.yml}.
     * Register during plugin enable so its subcommands are part of the command tree.
     *
     * @throws IllegalArgumentException for an invalid or duplicate id
     */
    public void register(MiniGame game) {
        String id = game.id();
        if (id == null || !ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid mini-game id: " + id);
        }
        if (games.containsKey(id)) {
            throw new IllegalArgumentException("Duplicate mini-game id: " + id);
        }
        games.put(id, game);
        try {
            hub.config().applyGameDefaults(id, game::configDefaults);
        } catch (RuntimeException e) {
            hub.plugin().getLogger().log(Level.WARNING, "Could not apply config defaults of " + id, e);
        }
        hub.plugin().getLogger().info("Registered mini-game " + id + " (" + game.displayName() + ")");
        if (rotationStarted && !shuttingDown && rotation.isEnabled() && session == null && !rotation.isWaiting()
                && hub.isEnabled()) {
            rotation.schedule();
        }
    }

    /** All registered modes in registration order. */
    public Collection<MiniGame> games() {
        return Collections.unmodifiableCollection(games.values());
    }

    /** The mode with exactly this id, or null. */
    public @Nullable MiniGame game(String id) {
        return games.get(id);
    }

    /** Finds a mode by id, display name or alias (case and spaces ignored), or null. */
    public @Nullable MiniGame find(String input) {
        String wanted = normalize(input);
        if (wanted.isEmpty()) {
            return null;
        }
        for (MiniGame game : games.values()) {
            if (normalize(game.id()).equals(wanted) || normalize(game.displayName()).equals(wanted)) {
                return game;
            }
            for (String alias : game.aliases()) {
                if (normalize(alias).equals(wanted)) {
                    return game;
                }
            }
        }
        return null;
    }

    private static String normalize(String text) {
        return text.replace(" ", "").toLowerCase(Locale.ROOT);
    }

    /** The mode {@code /게임 시작} uses without an argument: the next rotation mode, or the only registered one. */
    public @Nullable MiniGame defaultGame() {
        MiniGame next = rotation.next();
        if (next != null) {
            return next;
        }
        return games.size() == 1 ? games.values().iterator().next() : null;
    }

    // ------------------------------------------------------------------ sessions

    /** The running match, or null. */
    public @Nullable GameSession session() {
        return session;
    }

    public boolean hasSession() {
        return session != null;
    }

    /** Id of the mode that was started last (for rotation), or null. */
    public @Nullable String lastGameId() {
        return lastGameId;
    }

    /**
     * Starts a match of {@code game}: the countdown begins right away with every eligible online player.
     *
     * @param starter who started it (its location centers the arena if none is configured); null for automatic
     *                starts (rotation)
     * @throws GameStartException with a ready-to-send Korean message if the match cannot start
     */
    public GameSession start(MiniGame game, @Nullable CommandSender starter) throws GameStartException {
        return start(game, starter, eligiblePlayers());
    }

    /**
     * Starts a match of {@code game} with only the given players as participants (e.g. a solo practice started for
     * one player). Everyone else online watches as usual and may join if the mode allows late joining.
     *
     * @throws GameStartException with a ready-to-send Korean message if the match cannot start
     */
    public GameSession start(MiniGame game, @Nullable CommandSender starter, Collection<? extends Player> players)
            throws GameStartException {
        Messages messages = hub.messages();
        if (shuttingDown) {
            throw new GameStartException(messages.prefixed("error.internal"));
        }
        if (session != null) {
            throw new GameStartException(messages.prefixed("error.already-running",
                    Messages.tag("game", session.game().displayComponent())));
        }
        List<Player> eligible = List.copyOf(players);
        if (eligible.size() < Math.max(1, game.minPlayers())) {
            throw new GameStartException(messages.prefixed("error.not-enough-players",
                    Messages.tag("count", eligible.size()), Messages.tag("min", game.minPlayers())));
        }
        Arena arena = resolveArena(starter);
        rotation.cancel();
        GameSession created = new GameSession(hub, this, game, arena, eligible);
        session = created;
        rotation.onMatchStarted(game);
        lastGameId = game.id();
        lastArena = arena;
        try {
            created.open();
        } catch (RuntimeException | LinkageError e) {
            hub.plugin().getLogger().log(Level.SEVERE, "Could not start " + game.id(), e);
            if (session == created) {
                created.abort(messages.get("match.reason-error"));
            }
            session = null;
            throw new GameStartException(messages.prefixed("error.internal"), e);
        }
        hub.settings().refreshMenus(); // their status shows that changes now wait for the next match
        return created;
    }

    private Arena resolveArena(@Nullable CommandSender starter) throws GameStartException {
        int radius = hub.config().arenaRadius();
        Location configured = hub.config().arenaCenter();
        if (configured != null) {
            return Arena.around(configured, radius);
        }
        if (hub.config().hasArena()) {
            throw new GameStartException(hub.messages().prefixed("error.arena-world-missing",
                    Messages.tag("world", hub.config().arenaWorldName())));
        }
        if (starter instanceof Entity entity) {
            return Arena.around(entity.getLocation(), radius);
        }
        if (starter instanceof BlockCommandSender block) {
            return Arena.around(block.getBlock().getLocation().add(0.5, 1, 0.5), radius);
        }
        if (lastArena != null && Bukkit.getWorld(lastArena.world().getUID()) != null) {
            return lastArena;
        }
        return Arena.around(Bukkit.getWorlds().getFirst().getSpawnLocation(), radius);
    }

    /**
     * Stops the running match (no results, everyone restored) or cancels a pending rotation countdown. Rotation does
     * not schedule the next match after an admin stop.
     *
     * @return false if there was nothing to stop
     */
    public boolean stop(Component reason) {
        boolean stopped = rotation.isWaiting();
        rotation.cancel();
        if (session != null) {
            session.stopByAdmin(reason);
            stopped = true;
        }
        return stopped;
    }

    void onSessionClosed(GameSession closed, GameSession.EndReason reason) {
        if (session == closed) {
            session = null;
            hub.settings().refreshMenus();
        }
        if (shuttingDown || reason == GameSession.EndReason.STOPPED || reason == GameSession.EndReason.SHUTDOWN) {
            return;
        }
        if (rotation.isEnabled()) {
            rotation.schedule();
        }
    }

    // ------------------------------------------------------------------ preferences

    /** True if the player chose {@code /게임 관전}: they spectate instead of playing. */
    public boolean prefersSpectating(UUID playerId) {
        return spectatorPreference.contains(playerId);
    }

    /** Stores the player's choice (persisted) and applies it to a match that is still counting down. */
    public void setPrefersSpectating(Player player, boolean spectate) {
        boolean changed = spectate ? spectatorPreference.add(player.getUniqueId())
                : spectatorPreference.remove(player.getUniqueId());
        if (changed) {
            savePreferences();
        }
        if (session != null) {
            session.preferenceChanged(player, spectate);
        }
    }

    /** Online players that would take part in a match started now. */
    public List<Player> eligiblePlayers() {
        List<Player> out = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!spectatorPreference.contains(player.getUniqueId())) {
                out.add(player);
            }
        }
        return out;
    }

    private void loadPreferences() {
        spectatorPreference.clear();
        preferencesBroken = false;
        if (!preferencesFile.exists()) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(preferencesFile);
        } catch (IOException | InvalidConfigurationException e) {
            // never save over a file we could not read: the owner may want to fix it by hand
            preferencesBroken = true;
            hub.plugin().getLogger().severe("Could not read " + preferencesFile.getName() + " (" + e.getMessage()
                    + "); spectator preferences are kept in memory only until it is fixed.");
            return;
        }
        for (String id : yaml.getStringList("spectators")) {
            try {
                spectatorPreference.add(UUID.fromString(id));
            } catch (IllegalArgumentException ignored) {
                // skip malformed entries
            }
        }
    }

    private void savePreferences() {
        if (preferencesBroken) {
            hub.plugin().getLogger().warning(preferencesFile.getName() + " could not be read at startup; not overwriting it.");
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("spectators", spectatorPreference.stream().map(UUID::toString).toList());
        try {
            yaml.save(preferencesFile);
        } catch (IOException e) {
            hub.plugin().getLogger().log(Level.WARNING, "Could not save " + preferencesFile, e);
        }
    }

    // ------------------------------------------------------------------ rotation & lifecycle

    public Rotation rotation() {
        return rotation;
    }

    /** {@code /게임 리로드}: tells every mode to reload and refreshes the rotation. */
    public void reload() {
        for (MiniGame game : games.values()) {
            try {
                game.onReload();
            } catch (RuntimeException e) {
                hub.plugin().getLogger().log(Level.WARNING, "Error reloading " + game.id(), e);
            }
        }
        if (!rotation.isEnabled()) {
            rotation.cancel();
        } else if (session == null && !rotation.isWaiting()) {
            rotation.schedule();
        }
    }

    /** Plugin disable: aborts the match synchronously (everyone restored) and stops the rotation. */
    public void shutdown() {
        shuttingDown = true;
        rotation.cancel();
        if (session != null) {
            session.shutdown();
            session = null;
        }
        for (MiniGame game : games.values()) {
            try {
                game.onShutdown();
            } catch (RuntimeException e) {
                hub.plugin().getLogger().log(Level.WARNING, "Error shutting down " + game.id(), e);
            }
        }
    }
}
