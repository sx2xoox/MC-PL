package io.github.sx2xoox.mcpl.core.game;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.core.Hub;
import io.github.sx2xoox.mcpl.core.arena.Arena;
import io.github.sx2xoox.mcpl.core.arena.ProtectionListener;
import io.github.sx2xoox.mcpl.core.arena.Spread;
import io.github.sx2xoox.mcpl.core.arena.TerrainDamage;
import io.github.sx2xoox.mcpl.core.arena.VirtualBorder;
import io.github.sx2xoox.mcpl.core.combat.CombatTracker;
import io.github.sx2xoox.mcpl.core.combat.MatchStats;
import io.github.sx2xoox.mcpl.core.config.ProtectionSettings;
import io.github.sx2xoox.mcpl.core.player.PlayerReset;
import io.github.sx2xoox.mcpl.core.player.SnapshotWriteException;
import io.github.sx2xoox.mcpl.core.ui.Feedback;
import io.github.sx2xoox.mcpl.core.ui.ManagedBossBar;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.core.ui.Sidebar;
import io.github.sx2xoox.mcpl.core.ui.ViewerBoards;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

/**
 * The running match, provided by the hub. There is at most one session at a time
 * ({@link GameManager#session()}). Modes talk to the hub through this object.
 *
 * <h2>Flow</h2>
 * {@link GameState#COUNTDOWN} (title countdown; aborts if participants drop below {@link MiniGame#minPlayers()})
 * → {@link GameState#RUNNING} ({@link GameInstance#start()}, then {@link GameInstance#tick()} every tick)
 * → {@link #finish(GameResult)} → {@link GameState#ENDING} (results, fireworks; {@link GameInstance#stop()} is called)
 * → everyone restored → {@link GameState#CLOSED}. {@link #abort(Component)} jumps straight to restoring.
 *
 * <h2>Players</h2>
 * <ul>
 *     <li><b>Participants</b> — online players that did not choose {@code /게임 관전} when the countdown ended (plus
 *     accepted late joiners). Includes eliminated and offline ones.</li>
 *     <li><b>Alive</b> — participants not eliminated yet ({@link #aliveIds()}); {@link #alivePlayers()} are the ones
 *     actually in play right now (online, not dead, not waiting for a delayed respawn).</li>
 *     <li><b>Spectators</b> — everyone else online in the session: eliminated participants and watchers, all in
 *     spectator mode.</li>
 * </ul>
 * Every participant and spectator is snapshotted when they enter and restored when the session closes (or on their
 * next join if they are offline then).
 *
 * <h2>Automatic behaviour while RUNNING</h2>
 * Deaths never drop items/xp and show no vanilla message; respawn is immediate; kill credit, kill feed and
 * {@link #stats() stats} are handled; friendly fire between teammates and damage by/to spectators is cancelled; the
 * world is protected ({@link ProtectionListener}); quitting eliminates.
 *
 * <p>All methods must be called on the main thread.
 */
public final class GameSession {

    /** Why a session ended. */
    public enum EndReason {
        /** {@link #finish} and the ending phase completed. */
        FINISHED,
        /** {@link #abort} (not enough players, error, ...). Rotation continues. */
        ABORTED,
        /** An admin used {@code /게임 중지}. Rotation does not continue on its own. */
        STOPPED,
        /** The plugin is disabling. */
        SHUTDOWN
    }

    private final Hub hub;
    private final GameManager manager;
    private final MiniGame game;
    private final Arena arena;
    private final ProtectionSettings protection;
    private final Random random = new Random();
    private final MatchStats stats = new MatchStats();
    private final SessionResources resources;
    private final ViewerBoards boards;
    private final TerrainDamage terrain;

    private final Set<UUID> participants = new LinkedHashSet<>();
    private final Set<UUID> alive = new LinkedHashSet<>();
    private final Set<UUID> members = new LinkedHashSet<>();
    private final Set<UUID> watchers = new LinkedHashSet<>();
    private final List<UUID> eliminationOrder = new ArrayList<>();
    private final Map<UUID, String> names = new HashMap<>();
    private final Map<UUID, Location> deathLocations = new HashMap<>();
    private final Set<UUID> awaitingSpectator = new HashSet<>();
    private final Map<UUID, DeathOutcome.Respawn> awaitingRespawn = new HashMap<>();
    private final Set<UUID> respawning = new HashSet<>();
    private final List<BukkitTask> hubTasks = new ArrayList<>();
    private List<MatchTeam> teams = List.of();
    private final Map<UUID, MatchTeam> teamByPlayer = new HashMap<>();

    private GameInstance instance;
    private GameState state = GameState.COUNTDOWN;
    private boolean instanceStopped;
    private boolean closing;
    private long tick;
    private int countdownLeft;
    private int phaseTicks;
    private int endingTicksLeft;
    private long startedAt;
    private long endedAt;
    private @Nullable Location spectatorSpawn;
    private @Nullable GameResult result;
    private ResultPresenter.@Nullable Summary summary;
    private @Nullable BukkitTask loop;
    private @Nullable SessionListener listener;
    private @Nullable ProtectionListener protectionListener;

    GameSession(Hub hub, GameManager manager, MiniGame game, Arena arena, Collection<? extends Player> initial) {
        this.hub = hub;
        this.manager = manager;
        this.game = game;
        this.arena = arena;
        this.protection = game.protection(hub.config().protection());
        this.resources = new SessionResources(hub.plugin());
        this.boards = new ViewerBoards(game.displayComponent());
        this.terrain = new TerrainDamage(hub.tempBlocks(), hub.config().abilityBlockDamage(),
                hub.config().abilityBlockDamageLimit(), hub.maps()::isMapWorld, hub.plugin().getLogger());
        for (Player player : initial) {
            participants.add(player.getUniqueId());
            names.put(player.getUniqueId(), player.getName());
        }
    }

    // =================================================================== identity

    public MiniGame game() {
        return game;
    }

    /** The mode logic of this match. */
    public GameInstance instance() {
        return instance;
    }

    public Hub hub() {
        return hub;
    }

    public JavaPlugin plugin() {
        return hub.plugin();
    }

    public GameState state() {
        return state;
    }

    public boolean isRunning() {
        return state == GameState.RUNNING;
    }

    public Arena arena() {
        return arena;
    }

    /** Ticks since the match started (1 on the first {@link GameInstance#tick()}, 0 during the countdown). */
    public long tick() {
        return tick;
    }

    /** Milliseconds since the match started (0 during the countdown; frozen once it ends). */
    public long elapsedMillis() {
        if (startedAt == 0) {
            return 0;
        }
        return (endedAt != 0 ? endedAt : System.currentTimeMillis()) - startedAt;
    }

    public MatchStats stats() {
        return stats;
    }

    public CombatTracker combat() {
        return hub.combat();
    }

    public Messages messages() {
        return hub.messages();
    }

    /** {@code games.<id>} of {@code config.yml} (see {@link MiniGame#configDefaults}). */
    public ConfigurationSection gameConfig() {
        return hub.config().gameSection(game.id());
    }

    /** The protection settings in effect for this match. */
    public ProtectionSettings protection() {
        return protection;
    }

    public Random random() {
        return random;
    }

    /** The result passed to {@link #finish}, null until then. */
    public @Nullable GameResult result() {
        return result;
    }

    // =================================================================== players

    /** Every participant (eliminated and offline included), in join order. */
    public Set<UUID> participantIds() {
        return Collections.unmodifiableSet(participants);
    }

    /** Online participants (eliminated included). */
    public List<Player> participants() {
        return online(participants);
    }

    public boolean isParticipant(UUID playerId) {
        return participants.contains(playerId);
    }

    public boolean isParticipant(Player player) {
        return participants.contains(player.getUniqueId());
    }

    /** Participants not eliminated yet (offline-safe; includes players dead for a moment or waiting to respawn). */
    public Set<UUID> aliveIds() {
        return Collections.unmodifiableSet(alive);
    }

    /** Participants in play right now: not eliminated, online, not dead and not waiting for a delayed respawn. */
    public List<Player> alivePlayers() {
        List<Player> out = new ArrayList<>(alive.size());
        for (UUID id : alive) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && !player.isDead() && !respawning.contains(id)) {
                out.add(player);
            }
        }
        return out;
    }

    /** Number of participants not eliminated yet. */
    public int aliveCount() {
        return alive.size();
    }

    /** True if the participant is not eliminated (see {@link #isInPlay} for "can be hit right now"). */
    public boolean isAlive(UUID playerId) {
        return alive.contains(playerId);
    }

    public boolean isAlive(Player player) {
        return alive.contains(player.getUniqueId());
    }

    /** Alive, online, not dead and not waiting for a delayed respawn. */
    public boolean isInPlay(Player player) {
        UUID id = player.getUniqueId();
        return alive.contains(id) && player.isOnline() && !player.isDead() && !respawning.contains(id);
    }

    /** True while the participant spectates before a delayed {@link DeathOutcome.Respawn}. */
    public boolean isRespawning(UUID playerId) {
        return respawning.contains(playerId);
    }

    /** Online players of the session that are not in play (eliminated participants and watchers); empty while counting down. */
    public List<Player> spectators() {
        List<Player> out = new ArrayList<>();
        if (state == GameState.COUNTDOWN) {
            return out;
        }
        for (Player player : viewers()) {
            if (!isInPlay(player)) {
                out.add(player);
            }
        }
        return out;
    }

    public boolean isSpectator(Player player) {
        return members.contains(player.getUniqueId()) && !isInPlay(player);
    }

    /** True for everyone pulled into the match (snapshotted): participants and spectators, online or not. */
    public boolean isMember(Player player) {
        return members.contains(player.getUniqueId());
    }

    public boolean isMember(UUID playerId) {
        return members.contains(playerId);
    }

    /** Everyone online who watches this match: participants and spectators (all online players while counting down). */
    public List<Player> viewers() {
        if (state == GameState.COUNTDOWN) {
            return List.copyOf(Bukkit.getOnlinePlayers());
        }
        return online(members);
    }

    /** {@link #viewers()} as one audience. */
    public Audience audience() {
        return Audience.audience(viewers());
    }

    /** Participants in the order they were eliminated (first = first out). */
    public List<UUID> eliminationOrder() {
        return Collections.unmodifiableList(eliminationOrder);
    }

    /** Last known name of a player of this session (works offline). */
    public String plainName(UUID playerId) {
        String name = names.get(playerId);
        if (name != null) {
            return name;
        }
        String offline = Bukkit.getOfflinePlayer(playerId).getName();
        return offline != null ? offline : playerId.toString().substring(0, 8);
    }

    /** The player name in their team color (white in free-for-all). */
    public Component name(UUID playerId) {
        MatchTeam team = teamByPlayer.get(playerId);
        return Component.text(plainName(playerId), team == null ? NamedTextColor.WHITE : team.color());
    }

    public Component name(Player player) {
        return name(player.getUniqueId());
    }

    /** {@link #name(UUID)} followed by the mode's {@link GameInstance#playerTag tag}, e.g. {@code Steve(화염술사)}. */
    public Component displayName(UUID playerId) {
        Component base = name(playerId);
        Component tag = safeTag(playerId);
        if (tag == null) {
            return base;
        }
        return base.append(Component.text("(", NamedTextColor.GRAY)).append(tag).append(Component.text(")", NamedTextColor.GRAY));
    }

    public Component displayName(Player player) {
        return displayName(player.getUniqueId());
    }

    @Nullable Component safeTag(UUID playerId) {
        if (instance == null) {
            return null;
        }
        try {
            return instance.playerTag(playerId);
        } catch (RuntimeException e) {
            log("playerTag", e);
            return null;
        }
    }

    private static List<Player> online(Collection<UUID> ids) {
        List<Player> out = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                out.add(player);
            }
        }
        return out;
    }

    // =================================================================== teams

    /** True if this match is played in teams. */
    public boolean isTeamMode() {
        return !teams.isEmpty();
    }

    /** The teams (empty in free-for-all), assigned when the countdown ends. */
    public List<MatchTeam> teams() {
        return teams;
    }

    public @Nullable MatchTeam teamOf(UUID playerId) {
        return teamByPlayer.get(playerId);
    }

    public @Nullable MatchTeam teamOf(Player player) {
        return teamByPlayer.get(player.getUniqueId());
    }

    /** True if both are on the same team; in free-for-all only for the same player. */
    public boolean sameTeam(UUID a, UUID b) {
        if (a.equals(b)) {
            return true;
        }
        MatchTeam team = teamByPlayer.get(a);
        return team != null && team == teamByPlayer.get(b);
    }

    public boolean sameTeam(Player a, Player b) {
        return sameTeam(a.getUniqueId(), b.getUniqueId());
    }

    /** Teams that still have at least one alive member. */
    public List<MatchTeam> aliveTeams() {
        List<MatchTeam> out = new ArrayList<>();
        for (MatchTeam team : teams) {
            for (UUID member : team.members()) {
                if (alive.contains(member)) {
                    out.add(team);
                    break;
                }
            }
        }
        return out;
    }

    // =================================================================== messaging

    /** Sends a component to all online players and the console. */
    public void broadcast(Component message) {
        Feedback.everyone().sendMessage(message);
    }

    /** Sends a prefixed {@code messages.yml} message to all online players and the console. */
    public void broadcast(String key, TagResolver... resolvers) {
        broadcast(messages().prefixed(key, resolvers));
    }

    /** Shows a title to all viewers (fade 0.25s, stay 2s, fade 0.5s). */
    public void title(Component title, Component subtitle) {
        audience().showTitle(Feedback.title(title, subtitle, 5, 40, 10));
    }

    public void actionBar(Component message) {
        audience().sendActionBar(message);
    }

    /** Plays a sound to all viewers at their own position. */
    public void playSound(Sound sound, float volume, float pitch) {
        Feedback.play(audience(), sound, volume, pitch);
    }

    // =================================================================== UI

    /**
     * The private sidebar of a viewer (participant or spectator of this match), created on first use. Valid while
     * RUNNING and ENDING; the hub replaces its content with the results when the match finishes.
     *
     * @throws IllegalArgumentException if the player is not part of the session
     */
    public Sidebar sidebar(Player viewer) {
        if (!members.contains(viewer.getUniqueId()) || state == GameState.CLOSED) {
            throw new IllegalArgumentException(viewer.getName() + " is not part of the match");
        }
        return boards.sidebar(viewer);
    }

    /** A boss bar shown to every viewer (also late joiners), hidden automatically when the mode stops. */
    public ManagedBossBar sharedBossBar(Component name, float progress, BossBar.Color color, BossBar.Overlay overlay) {
        ManagedBossBar bar = resources.sharedBar(new ManagedBossBar(name, progress, color, overlay));
        if (!resources.isClosed()) {
            viewers().forEach(bar::show);
        }
        return bar;
    }

    /** A boss bar for one player only, hidden automatically when the mode stops. */
    public ManagedBossBar playerBossBar(Player player, Component name, float progress,
                                        BossBar.Color color, BossBar.Overlay overlay) {
        ManagedBossBar bar = resources.playerBar(new ManagedBossBar(name, progress, color, overlay));
        if (!resources.isClosed()) {
            bar.show(player);
        }
        return bar;
    }

    /**
     * A per-player virtual world border centered on the arena with side length {@code size}, shown to every viewer
     * (also late joiners) and removed when the mode stops. It deals no damage by itself.
     */
    public VirtualBorder createBorder(double size) {
        VirtualBorder border = resources.border(new VirtualBorder(arena.center(), size));
        if (!resources.isClosed()) {
            viewers().forEach(border::show);
        }
        return border;
    }

    // =================================================================== resources

    /** Runs once after {@code delayTicks}; cancelled automatically when the mode stops. */
    public BukkitTask runLater(long delayTicks, Runnable task) {
        return resources.runLater(delayTicks, guarded(task));
    }

    /** Repeats every {@code periodTicks}; cancelled automatically when the mode stops. */
    public BukkitTask runTimer(long delayTicks, long periodTicks, Runnable task) {
        return resources.runTimer(delayTicks, periodTicks, guarded(task));
    }

    private Runnable guarded(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log("scheduled task", e);
            }
        };
    }

    /** Removes {@code entity} when the mode stops (it is also made non-persistent so it never saves to disk). */
    public <T extends Entity> T track(T entity) {
        return resources.track(entity);
    }

    /** Stops tracking an entity you removed yourself. */
    public void untrack(Entity entity) {
        resources.untrack(entity);
    }

    /**
     * True if the match created or owns the entity: {@link #track tracked} or registered with an owner in the
     * {@link CombatTracker} (summons, decoys, projectiles, displays...). Other entities are bystanders the
     * {@link ProtectionListener} protects.
     */
    public boolean isMatchEntity(Entity entity) {
        return resources.isTracked(entity) || hub.combat().ownerOf(entity) != null;
    }

    /** Registers a Bukkit listener until the mode stops. */
    public void registerListener(Listener listener) {
        resources.registerListener(listener);
    }

    public void unregisterListener(Listener listener) {
        resources.unregisterListener(listener);
    }

    /** Runs {@code cleanup} when the mode stops (after {@link GameInstance#stop()}, newest first). */
    public void onCleanup(Runnable cleanup) {
        resources.onCleanup(cleanup);
    }

    /**
     * Temporarily replaces a block (see {@link io.github.sx2xoox.mcpl.core.arena.TempBlockService#place}); every
     * temporary block is restored when the mode stops at the latest.
     */
    public boolean placeTempBlock(Block block, BlockData data, long durationTicks) {
        if (resources.isClosed()) {
            return false;
        }
        return hub.tempBlocks().place(block, data, durationTicks);
    }

    /**
     * Breaks the blocks within {@code radius} of {@code center}, where the server allows it
     * ({@code protection.ability-block-damage}) and only inside the arena and where {@code allowed} accepts a block's
     * center (see {@link TerrainDamage} for what breaks and what never does). Everything broken comes back when the
     * match closes, after the players are restored. Returns what broke, nearest first; nothing once the mode stopped.
     */
    public List<TerrainDamage.Broken> breakBlocks(Location center, double radius, Predicate<Location> allowed) {
        if (resources.isClosed()) {
            return List.of();
        }
        return terrain.blast(center, radius, at -> arena.contains(at) && allowed.test(at));
    }

    /** {@link #breakBlocks} along the segment {@code from}-{@code to} (a dash, a charge). */
    public List<TerrainDamage.Broken> breakBlocksAlong(Location from, Location to, double radius,
                                                       Predicate<Location> allowed) {
        if (resources.isClosed()) {
            return List.of();
        }
        return terrain.blastAlong(from, to, radius, at -> arena.contains(at) && allowed.test(at));
    }

    /** The terrain damage of this match (scope, blocks broken so far). */
    public TerrainDamage terrain() {
        return terrain;
    }

    // =================================================================== positions

    /** {@code count} safe, spread-out standing locations in the arena (see {@link Spread#findSpawns}). */
    public List<Location> spreadLocations(int count) {
        return Spread.findSpawns(arena, count, random);
    }

    /**
     * Teleports every player in play to safe spread-out locations facing the arena center: one spot per player in
     * free-for-all, one shared spot per team in team mode.
     */
    public void spreadParticipants() {
        List<Player> players = alivePlayers();
        if (players.isEmpty()) {
            return;
        }
        if (!isTeamMode()) {
            List<Location> spots = spreadLocations(players.size());
            for (int i = 0; i < players.size(); i++) {
                teleportSafely(players.get(i), spots.get(i));
            }
            return;
        }
        List<MatchTeam> playing = aliveTeams();
        List<Location> spots = spreadLocations(Math.max(1, playing.size()));
        for (Player player : players) {
            MatchTeam team = teamOf(player);
            int index = team == null ? 0 : Math.max(0, playing.indexOf(team));
            teleportSafely(player, spots.get(Math.min(index, spots.size() - 1)));
        }
    }

    private static void teleportSafely(Player player, Location location) {
        player.setFallDistance(0f);
        player.setVelocity(new org.bukkit.util.Vector());
        player.teleport(location);
    }

    /** A safe spot near the arena center, a few blocks up, used for spectators. */
    public Location spectatorSpawn() {
        if (spectatorSpawn == null) {
            spectatorSpawn = Spread.safeSpot(arena, arena.center()).add(0, 3, 0);
        }
        return spectatorSpawn.clone();
    }

    /** A safe standing spot near {@code near} inside the arena. */
    public Location safeSpot(Location near) {
        return Spread.safeSpot(arena, near);
    }

    // =================================================================== flow (public)

    /**
     * Eliminates an alive participant without a vanilla death (e.g. disqualified, out of lives): records a death
     * (and a kill for {@code killer}), shows the kill feed, makes them a spectator where they stand and calls
     * {@link GameInstance#onEliminated}. Ignored unless RUNNING and the player is alive.
     */
    public void eliminate(Player player, @Nullable Player killer) {
        UUID id = player.getUniqueId();
        if (state != GameState.RUNNING || !alive.contains(id)) {
            return;
        }
        Player credited = validKiller(player, killer);
        stats.addDeath(id);
        if (credited != null) {
            stats.addKill(credited.getUniqueId());
        }
        hub.combat().forget(id);
        markEliminated(id);
        if (player.isDead()) {
            awaitingSpectator.add(id);
        } else {
            makeSpectator(player, null);
        }
        killFeed(id, credited, null, true);
        notifyEliminated(player, credited);
    }

    /**
     * Ends the match with a result: the mode is stopped ({@link GameInstance#stop()}, then all session resources and
     * temporary blocks are released), results are presented, and after {@code match.ending-seconds} everyone is
     * restored. Ignored unless RUNNING (so calling it twice is harmless).
     */
    public void finish(GameResult result) {
        if (state != GameState.RUNNING) {
            return;
        }
        state = GameState.ENDING;
        endedAt = System.currentTimeMillis();
        this.result = result;
        ResultPresenter.Summary computed;
        try {
            computed = ResultPresenter.compute(this, result);
        } catch (RuntimeException e) {
            log("results", e);
            computed = null;
        }
        summary = computed;
        stopInstance();
        cancelHubTasks();
        respawning.clear();
        awaitingRespawn.clear();
        if (computed != null) {
            try {
                ResultPresenter.present(this, computed);
            } catch (RuntimeException e) {
                log("results", e);
            }
        }
        endingTicksLeft = hub.config().endingSeconds() * 20;
        updateEndingSidebars();
        hub.plugin().getLogger().info("Match " + game.id() + " finished after " + elapsedMillis() / 1000 + "s");
    }

    /**
     * Cancels the match immediately: the mode is stopped and everyone is restored without results. The reason is
     * broadcast (e.g. {@code "인원이 부족합니다"}). Works in every phase; ignored once closed.
     */
    public void abort(Component reason) {
        terminate(reason, EndReason.ABORTED);
    }

    // =================================================================== lifecycle (hub internal)

    void open() {
        instance = game.createInstance(this);
        if (instance == null) {
            throw new IllegalStateException(game.id() + " returned no GameInstance");
        }
        listener = new SessionListener(this);
        Bukkit.getPluginManager().registerEvents(listener, hub.plugin());
        protectionListener = new ProtectionListener(this, protection);
        Bukkit.getPluginManager().registerEvents(protectionListener, hub.plugin());
        countdownLeft = hub.config().countdownSeconds();
        loop = Bukkit.getScheduler().runTaskTimer(hub.plugin(), this::loop, 1L, 1L);
        broadcast("match.starting", Messages.tag("game", game.displayComponent()),
                Messages.tag("count", participants.size()), Messages.tag("seconds", countdownLeft));
    }

    /** Admin stop: no results, rotation pauses. */
    void stopByAdmin(Component reason) {
        terminate(reason, EndReason.STOPPED);
    }

    /** Plugin disable: synchronous abort without messages to players that are gone anyway. */
    void shutdown() {
        terminate(messages().get("match.reason-shutdown"), EndReason.SHUTDOWN);
    }

    private void loop() {
        switch (state) {
            case COUNTDOWN -> {
                if (phaseTicks++ % 20 == 0) {
                    countdownStep();
                }
            }
            case RUNNING -> {
                tick++;
                try {
                    instance.tick();
                } catch (RuntimeException | LinkageError e) {
                    fail("tick", e);
                }
            }
            case ENDING -> {
                if (endingTicksLeft <= 0) {
                    close(EndReason.FINISHED);
                    return;
                }
                if (endingTicksLeft % 20 == 0) {
                    updateEndingSidebars();
                }
                endingTicksLeft--;
            }
            case CLOSED -> {
                if (loop != null) {
                    loop.cancel();
                }
            }
        }
    }

    private void countdownStep() {
        participants.removeIf(id -> Bukkit.getPlayer(id) == null || manager.prefersSpectating(id));
        if (participants.size() < Math.max(1, game.minPlayers())) {
            abort(messages().get("match.reason-not-enough", Messages.tag("count", participants.size()),
                    Messages.tag("min", game.minPlayers())));
            return;
        }
        if (countdownLeft <= 0) {
            begin();
            return;
        }
        Audience everyone = audience();
        Feedback.quickTitle(everyone,
                messages().get("match.countdown-title", Messages.tag("seconds", countdownLeft)),
                messages().get("match.countdown-subtitle", Messages.tag("game", game.displayComponent())));
        Feedback.play(everyone, Sound.BLOCK_NOTE_BLOCK_HAT, 1f, countdownLeft <= 3 ? 1.6f : 1.2f);
        countdownLeft--;
    }

    private void begin() {
        List<Player> players = online(participants);
        if (players.size() < Math.max(1, game.minPlayers())) {
            abort(messages().get("match.reason-not-enough", Messages.tag("count", players.size()),
                    Messages.tag("min", game.minPlayers())));
            return;
        }
        participants.retainAll(players.stream().map(Player::getUniqueId).toList());

        // Snapshot everyone before anyone is reset: a player whose state cannot be saved to disk is left out untouched.
        Set<UUID> unsaved = new HashSet<>();
        List<Player> captured = new ArrayList<>(players.size());
        for (Player player : players) {
            UUID id = player.getUniqueId();
            names.put(id, player.getName());
            if (snapshot(player)) {
                members.add(id);
                captured.add(player);
            } else {
                unsaved.add(id);
                participants.remove(id);
            }
        }
        if (captured.size() < Math.max(1, game.minPlayers())) {
            abort(messages().get("match.reason-not-enough", Messages.tag("count", captured.size()),
                    Messages.tag("min", game.minPlayers())));
            return;
        }
        players = captured;

        int teamSize = hub.config().teamSize();
        if (game.supportsTeams() && teamSize > 0) {
            teams = TeamAssigner.assign(participants, teamSize, random);
            for (MatchTeam team : teams) {
                for (UUID member : team.members()) {
                    teamByPlayer.put(member, team);
                }
            }
        }

        startedAt = System.currentTimeMillis();
        String namespace = hub.snapshots().namespace();
        for (Player player : players) {
            UUID id = player.getUniqueId();
            PlayerReset.reset(player, namespace);
            alive.add(id);
            stats.of(id);
        }
        boards.setTeams(teams, this::plainName);
        for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
            if (!participants.contains(player.getUniqueId()) && !unsaved.contains(player.getUniqueId())) {
                addWatcher(player, false);
            }
        }
        for (Player player : players) {
            attachViewer(player);
        }
        try {
            hub.gameRules().set(arena.world(), GameRules.IMMEDIATE_RESPAWN, true);
        } catch (RuntimeException e) {
            hub.plugin().getLogger().log(Level.WARNING, "Could not enable immediate respawn in " + arena.world().getName(), e);
        }

        state = GameState.RUNNING;
        tick = 0;
        hub.settings().refreshMenus(); // open settings menus: changes now wait for the next match
        announceTeams();
        Audience everyone = audience();
        Feedback.title(everyone, messages().get("match.start-title"),
                messages().get("match.start-subtitle", Messages.tag("game", game.displayComponent())), 0, 30, 10);
        Feedback.play(everyone, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.2f);
        try {
            instance.start();
        } catch (RuntimeException | LinkageError e) {
            fail("start", e);
        }
    }

    private void announceTeams() {
        if (!isTeamMode()) {
            return;
        }
        for (MatchTeam team : teams) {
            List<Component> mates = new ArrayList<>();
            for (UUID member : team.members()) {
                mates.add(name(member));
            }
            Component list = Component.join(net.kyori.adventure.text.JoinConfiguration.commas(true), mates);
            for (Player player : online(team.members())) {
                messages().send(player, "match.team-assigned", Messages.tag("team", team.displayName()),
                        Messages.tag("members", list));
            }
        }
    }

    private void fail(String phase, Throwable error) {
        hub.plugin().getLogger().log(Level.SEVERE, "Mini-game " + game.id() + " failed in " + phase
                + "; aborting the match", error);
        abort(messages().get("match.reason-error"));
    }

    private void terminate(Component reason, EndReason why) {
        if (state == GameState.CLOSED || closing) {
            return;
        }
        if (why != EndReason.SHUTDOWN) {
            broadcast("match.aborted", Messages.tag("reason", reason));
            Feedback.play(audience(), Sound.BLOCK_BEACON_DEACTIVATE, 1f, 1f);
        } else {
            hub.plugin().getLogger().info("Aborting match " + game.id() + " (plugin disabling)");
        }
        close(why);
    }

    private void stopInstance() {
        if (instanceStopped) {
            return;
        }
        instanceStopped = true;
        if (instance != null) {
            try {
                instance.stop();
            } catch (RuntimeException | LinkageError e) {
                log("stop", e);
            }
        }
        resources.cleanup();
        // Temporary blocks go now; broken terrain stays for the results and comes back when everyone left (close).
        hub.tempBlocks().restorePlacements();
    }

    private void close(EndReason why) {
        if (state == GameState.CLOSED || closing) {
            return;
        }
        closing = true;
        try {
            stopInstance();
        } finally {
            state = GameState.CLOSED;
        }
        if (loop != null) {
            loop.cancel();
            loop = null;
        }
        cancelHubTasks();
        if (listener != null) {
            org.bukkit.event.HandlerList.unregisterAll(listener);
            listener = null;
        }
        if (protectionListener != null) {
            org.bukkit.event.HandlerList.unregisterAll(protectionListener);
            protectionListener = null;
        }
        hub.tempBlocks().restorePlacements();

        for (UUID id : members) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || player.isDead()) {
                continue; // restored on their next join / respawn
            }
            try {
                if (hub.snapshots().restore(player) && why != EndReason.SHUTDOWN) {
                    messages().send(player, "match.restored");
                }
            } catch (RuntimeException e) {
                log("restore " + player.getName(), e);
            }
        }
        // The broken terrain comes back at once, after the players were taken out of the arena (anyone still in a
        // crater is lifted out of the restored ground).
        try {
            hub.tempBlocks().restoreRemovals();
        } catch (RuntimeException e) {
            log("terrain", e);
        }
        boards.clear();
        try {
            hub.gameRules().restoreAll();
        } catch (RuntimeException e) {
            log("game rules", e);
        }
        hub.combat().clear();
        manager.onSessionClosed(this, why);
    }

    private void cancelHubTasks() {
        for (BukkitTask task : hubTasks) {
            task.cancel();
        }
        hubTasks.clear();
    }

    /** Schedules hub-owned work (fireworks, delayed respawns) cancelled when the session ends. */
    void hubLater(long delayTicks, Runnable task) {
        if (state == GameState.CLOSED) {
            return;
        }
        hubTasks.removeIf(BukkitTask::isCancelled);
        hubTasks.add(Bukkit.getScheduler().runTaskLater(hub.plugin(), guarded(task), Math.max(0, delayTicks)));
    }

    private void updateEndingSidebars() {
        if (summary == null || state != GameState.ENDING) {
            return;
        }
        int seconds = (endingTicksLeft + 19) / 20;
        for (Player viewer : viewers()) {
            try {
                ResultPresenter.endingSidebar(this, summary, boards.sidebar(viewer), viewer, seconds);
            } catch (RuntimeException e) {
                log("ending sidebar", e);
                return;
            }
        }
    }

    // =================================================================== players (hub internal)

    private void attachViewer(Player player) {
        try {
            boards.attach(player);
        } catch (RuntimeException e) {
            log("scoreboard", e);
        }
        resources.showTo(player);
    }

    /**
     * A player on the death screen is respawned first: snapshotting (or healing) a corpse would record the death spot
     * and leave the client stuck on the death screen, since the server ignores the respawn button once health is > 0.
     */
    private static void ensureAlive(Player player) {
        if (player.isDead()) {
            player.spigot().respawn();
        }
    }

    /**
     * Respawns the player if needed and takes their snapshot (a no-op if one is pending). If it cannot be saved to
     * disk the player is told and left untouched.
     */
    private boolean snapshot(Player player) {
        ensureAlive(player);
        try {
            hub.snapshots().capture(player, game.id());
            return true;
        } catch (RuntimeException e) {
            if (!(e instanceof SnapshotWriteException)) {
                log("snapshot of " + player.getName(), e);
            }
            messages().send(player, "match.snapshot-failed");
            return false;
        }
    }

    /** Puts a non-participant into the session as a spectator (snapshotted first); false if that failed. */
    private boolean addWatcher(Player player, boolean announce) {
        UUID id = player.getUniqueId();
        names.put(id, player.getName());
        ensureAlive(player);
        if (!members.contains(id) && hub.snapshots().has(id)) {
            hub.snapshots().restore(player); // left over from an earlier, interrupted match
        }
        if (!snapshot(player)) {
            return false;
        }
        members.add(id);
        watchers.add(id);
        makeSpectator(player, spectatorSpawn());
        boards.addSpectator(player.getName());
        attachViewer(player);
        if (announce) {
            messages().send(player, "match.joined-spectator", Messages.tag("game", game.displayComponent()));
        }
        return true;
    }

    /** Lets a player into the running match; false (player untouched) if their snapshot could not be saved. */
    private boolean addLateParticipant(Player player, boolean rejoining) {
        UUID id = player.getUniqueId();
        if (!snapshot(player)) {
            return false;
        }
        members.add(id);
        if (watchers.remove(id)) {
            boards.removeSpectator(player.getName());
        }
        participants.add(id);
        alive.add(id);
        eliminationOrder.remove(id);
        awaitingSpectator.remove(id);
        stats.of(id);
        PlayerReset.reset(player, hub.snapshots().namespace());
        if (isTeamMode() && !teamByPlayer.containsKey(id)) {
            MatchTeam team = TeamAssigner.addToSmallest(teams, id);
            teamByPlayer.put(id, team);
            boards.addTeamMember(team, id);
        }
        attachViewer(player);
        broadcast(rejoining ? "match.rejoined-participant" : "match.late-join", Messages.tag("player", name(id)));
        try {
            instance.onLateJoin(player);
        } catch (RuntimeException e) {
            log("onLateJoin", e);
        }
        return true;
    }

    private void makeSpectator(Player player, @Nullable Location at) {
        player.setGameMode(GameMode.SPECTATOR);
        player.setFireTicks(0);
        if (at != null) {
            player.teleport(at);
        }
    }

    private void markEliminated(UUID id) {
        alive.remove(id);
        eliminationOrder.remove(id);
        eliminationOrder.add(id);
        respawning.remove(id);
        awaitingRespawn.remove(id);
    }

    private void notifyEliminated(Player player, @Nullable Player killer) {
        if (player.isOnline()) {
            Feedback.title(player, messages().get("match.eliminated-title"),
                    killer == null ? messages().get("match.eliminated-subtitle")
                            : messages().get("match.eliminated-subtitle-killer", Messages.tag("killer", displayName(killer))),
                    5, 50, 15);
            Feedback.play(player, Sound.ENTITY_WITHER_SPAWN, 0.4f, 1.6f);
        }
        if (killer != null && killer.isOnline()) {
            Feedback.play(killer, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 0.8f);
        }
        if (state == GameState.RUNNING) {
            try {
                instance.onEliminated(player, killer);
            } catch (RuntimeException e) {
                log("onEliminated", e);
            }
        }
    }

    private @Nullable Player validKiller(Player victim, @Nullable Player killer) {
        if (killer == null || killer.getUniqueId().equals(victim.getUniqueId())
                || !participants.contains(killer.getUniqueId())) {
            return null;
        }
        return killer;
    }

    private void killFeed(UUID victim, @Nullable Player killer, @Nullable Component cause, boolean eliminated) {
        Component line;
        if (killer != null) {
            line = messages().get("kill-feed.kill", Messages.tag("killer", displayName(killer)),
                    Messages.tag("victim", displayName(victim)));
        } else {
            line = messages().get("kill-feed.death", Messages.tag("victim", displayName(victim)),
                    Messages.tag("cause", cause == null ? messages().get("death-cause.eliminated") : cause));
        }
        if (eliminated && state == GameState.RUNNING) {
            line = line.append(messages().get("kill-feed.remaining", Messages.tag("alive", alive.size())));
        }
        broadcast(line);
    }

    // =================================================================== events (called by SessionListener)

    void handleJoin(Player player) {
        UUID id = player.getUniqueId();
        names.put(id, player.getName());
        switch (state) {
            case COUNTDOWN -> {
                if (hub.snapshots().has(id)) {
                    hub.snapshots().restore(player);
                }
                if (!manager.prefersSpectating(id) && participants.add(id)) {
                    broadcast("match.joined-countdown", Messages.tag("player", Component.text(player.getName())),
                            Messages.tag("count", participants.size()));
                }
            }
            case RUNNING -> {
                boolean rejoining = participants.contains(id);
                if (!members.contains(id) && hub.snapshots().has(id)) {
                    hub.snapshots().restore(player);
                }
                if (!manager.prefersSpectating(id) && !alive.contains(id) && allowLateJoin(player, rejoining)) {
                    addLateParticipant(player, rejoining); // on failure the player is told and left untouched
                } else if (members.contains(id)) {
                    makeSpectator(player, spectatorSpawn());
                    attachViewer(player);
                    messages().send(player, "match.joined-spectator", Messages.tag("game", game.displayComponent()));
                } else {
                    addWatcher(player, true);
                }
            }
            case ENDING -> {
                if (members.contains(id)) {
                    makeSpectator(player, spectatorSpawn());
                    attachViewer(player);
                } else if (hub.snapshots().has(id)) {
                    hub.snapshots().restore(player);
                }
            }
            case CLOSED -> {
                // the restore listener handles it
            }
        }
        if (members.contains(id)) {
            // Paper sends the level's real world border after the join event, replacing a virtual border shown now
            hubLater(1, () -> {
                Player online = Bukkit.getPlayer(id);
                if (online != null && members.contains(id)) {
                    resources.showBordersTo(online);
                }
            });
        }
    }

    /** A player changed worlds: the client got the new world's real border, so virtual borders are sent again. */
    void handleWorldChange(Player player) {
        if (members.contains(player.getUniqueId())) {
            resources.showBordersTo(player);
        }
    }

    /**
     * {@code /게임 참가|관전} changed the player's preference: applied right away while counting down; while RUNNING a
     * spectator asking to join is let in if the mode allows late joining.
     */
    void preferenceChanged(Player player, boolean spectate) {
        UUID id = player.getUniqueId();
        if (state == GameState.RUNNING && !spectate && !alive.contains(id) && members.contains(id)) {
            boolean rejoining = participants.contains(id);
            if (allowLateJoin(player, rejoining)) {
                addLateParticipant(player, rejoining);
            }
            return;
        }
        if (state != GameState.COUNTDOWN) {
            return;
        }
        if (spectate) {
            if (participants.remove(id)) {
                broadcast("match.left-countdown", Messages.tag("player", Component.text(player.getName())),
                        Messages.tag("count", participants.size()));
            }
        } else if (participants.add(id)) {
            names.put(id, player.getName());
            broadcast("match.joined-countdown", Messages.tag("player", Component.text(player.getName())),
                    Messages.tag("count", participants.size()));
        }
    }

    private boolean allowLateJoin(Player player, boolean rejoining) {
        try {
            return instance.allowLateJoin(player, rejoining);
        } catch (RuntimeException e) {
            log("allowLateJoin", e);
            return false;
        }
    }

    void handleQuit(Player player) {
        UUID id = player.getUniqueId();
        names.put(id, player.getName());
        boards.detach(id);
        resources.forget(id);
        // A pearl still flying is saved with the player and thrown again on the next join, possibly into the arena
        // long after the match; removed now, it is not saved (the quit event comes before the save).
        resources.removeTracked(entity -> entity instanceof EnderPearl pearl
                && pearl.getShooter() instanceof Player shooter && shooter.getUniqueId().equals(id));
        switch (state) {
            case COUNTDOWN -> {
                if (participants.remove(id)) {
                    broadcast("match.left-countdown", Messages.tag("player", Component.text(player.getName())),
                            Messages.tag("count", participants.size()));
                }
            }
            case RUNNING -> {
                if (!alive.contains(id)) {
                    return;
                }
                try {
                    instance.onQuit(player);
                } catch (RuntimeException e) {
                    log("onQuit", e);
                }
                if (state != GameState.RUNNING || !alive.contains(id)) {
                    return;
                }
                Player killer = validKiller(player, hub.combat().lastAttacker(player));
                if (killer != null) {
                    stats.addKill(killer.getUniqueId());
                    stats.addDeath(id);
                }
                hub.combat().forget(id);
                markEliminated(id);
                killFeed(id, killer, messages().get("death-cause.quit"), true);
                notifyEliminated(player, killer);
            }
            case ENDING, CLOSED -> {
                // restored on next join from the persisted snapshot
            }
        }
    }

    void handleDeath(PlayerDeathEvent event) {
        Player victim = event.getPlayer();
        UUID id = victim.getUniqueId();
        if (state == GameState.COUNTDOWN || state == GameState.CLOSED || !members.contains(id)) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setShouldDropExperience(false);
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.deathMessage(null);
        deathLocations.put(id, victim.getLocation());
        if (state != GameState.RUNNING || !alive.contains(id) || respawning.contains(id)) {
            return;
        }

        Player killer = validKiller(victim, hub.combat().lastAttacker(victim));
        Component cause = DeathCauses.describe(messages(), victim.getLastDamageCause());
        DeathOutcome outcome;
        try {
            outcome = instance.onDeath(victim, killer, event);
        } catch (RuntimeException e) {
            log("onDeath", e);
            outcome = null;
        }
        if (outcome == null) {
            outcome = DeathOutcome.eliminate();
        }
        stats.addDeath(id);
        if (killer != null) {
            stats.addKill(killer.getUniqueId());
        }
        hub.combat().forget(id);
        if (outcome instanceof DeathOutcome.Respawn respawn && state == GameState.RUNNING) {
            awaitingRespawn.put(id, respawn);
            killFeed(id, killer, cause, false);
            if (killer != null) {
                Feedback.play(killer, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 0.8f);
            }
            return;
        }
        markEliminated(id);
        awaitingSpectator.add(id);
        killFeed(id, killer, cause, true);
        notifyEliminated(victim, killer);
    }

    void handleRespawn(PlayerRespawnEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (!members.contains(id)) {
            return;
        }
        DeathOutcome.Respawn respawn = awaitingRespawn.get(id);
        if (respawn != null && respawn.delayTicks() == 0) {
            event.setRespawnLocation(respawn.location());
        } else {
            event.setRespawnLocation(spectatorSpotFor(id));
        }
    }

    void handlePostRespawn(Player player) {
        UUID id = player.getUniqueId();
        if (!members.contains(id)) {
            return;
        }
        if (awaitingSpectator.remove(id)) {
            makeSpectator(player, null);
            attachViewer(player);
            return;
        }
        resources.showBordersTo(player); // a respawn resends the level's real border
        DeathOutcome.Respawn respawn = awaitingRespawn.remove(id);
        if (respawn == null || state != GameState.RUNNING || !alive.contains(id)) {
            return;
        }
        if (respawn.delayTicks() == 0) {
            completeRespawn(player, null);
            return;
        }
        respawning.add(id);
        makeSpectator(player, null);
        int seconds = (respawn.delayTicks() + 19) / 20;
        Feedback.title(player, messages().get("match.respawn-title"),
                messages().get("match.respawn-subtitle", Messages.tag("seconds", seconds)), 0, respawn.delayTicks(), 10);
        hubLater(respawn.delayTicks(), () -> {
            if (!respawning.remove(id) || state != GameState.RUNNING || !alive.contains(id)) {
                return;
            }
            Player online = Bukkit.getPlayer(id);
            if (online != null) {
                completeRespawn(online, respawn.location());
            }
        });
    }

    private void completeRespawn(Player player, @Nullable Location to) {
        if (to != null) {
            player.teleport(to);
        }
        player.setGameMode(GameMode.ADVENTURE);
        player.setFireTicks(0);
        player.setFallDistance(0f);
        PlayerReset.heal(player);
        try {
            instance.onRespawn(player);
        } catch (RuntimeException e) {
            log("onRespawn", e);
        }
    }

    private Location spectatorSpotFor(UUID id) {
        Location death = deathLocations.get(id);
        if (death == null || !arena.world().equals(death.getWorld())
                || death.getY() < arena.world().getMinHeight() + 1) {
            return spectatorSpawn();
        }
        return arena.clampInside(death);
    }

    /**
     * Damage pre-check (LOW priority): cancels damage outside RUNNING, to/from spectators and between teammates, so
     * mode listeners running later with {@code ignoreCancelled = true} only see legitimate hits.
     */
    void handleDamageCheck(EntityDamageEvent event, Player victim) {
        UUID id = victim.getUniqueId();
        boolean involved = members.contains(id) || participants.contains(id);
        if (!involved) {
            return;
        }
        if (state != GameState.RUNNING) {
            if (event.getCause() == EntityDamageEvent.DamageCause.VOID) {
                if (state != GameState.COUNTDOWN) {
                    event.setCancelled(true);
                    victim.teleport(spectatorSpawn());
                }
                return;
            }
            event.setCancelled(true);
            return;
        }
        if (!alive.contains(id) || respawning.contains(id)) {
            event.setCancelled(true);
            return;
        }
        Player attacker = hub.combat().resolveAttacker(event);
        if (attacker == null || attacker.getUniqueId().equals(id)) {
            return;
        }
        UUID attackerId = attacker.getUniqueId();
        if (members.contains(attackerId) && (!alive.contains(attackerId) || respawning.contains(attackerId))) {
            event.setCancelled(true);
            return;
        }
        if (isTeamMode() && sameTeam(id, attackerId)) {
            event.setCancelled(true);
        }
    }

    /** Damage bookkeeping (MONITOR priority, not cancelled): kill credit and stats between participants. */
    void handleDamageRecord(EntityDamageEvent event, Player victim) {
        if (state != GameState.RUNNING || !alive.contains(victim.getUniqueId())) {
            return;
        }
        Player attacker = hub.combat().resolveAttacker(event);
        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())
                || !participants.contains(attacker.getUniqueId())) {
            return;
        }
        hub.combat().recordHit(victim, attacker);
        double dealt = Math.min(event.getFinalDamage(), victim.getHealth() + victim.getAbsorptionAmount());
        stats.addDamage(attacker.getUniqueId(), victim.getUniqueId(), dealt);
    }

    private void log(String what, Throwable error) {
        hub.plugin().getLogger().log(Level.WARNING, "Error in " + game.id() + " (" + what + ")", error);
    }
}
