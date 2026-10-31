package io.github.sx2xoox.mcpl.game.augment;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.core.Hub;
import io.github.sx2xoox.mcpl.core.arena.Arena;
import io.github.sx2xoox.mcpl.core.command.GameCommandRegistrar;
import io.github.sx2xoox.mcpl.core.command.Permissions;
import io.github.sx2xoox.mcpl.core.config.ProtectionSettings;
import io.github.sx2xoox.mcpl.core.config.SettingsSection;
import io.github.sx2xoox.mcpl.core.game.GameInstance;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.game.GameStartException;
import io.github.sx2xoox.mcpl.core.game.MiniGame;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.core.world.ChunkPregen;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentRegistry;
import io.github.sx2xoox.mcpl.game.augment.augments.BuiltinAugments;
import io.github.sx2xoox.mcpl.game.augment.bot.BotSettings;
import io.github.sx2xoox.mcpl.game.augment.command.AugmentCommand;
import io.github.sx2xoox.mcpl.game.augment.config.AugmentConfig;
import io.github.sx2xoox.mcpl.game.augment.runtime.AugmentMatch;
import io.github.sx2xoox.mcpl.game.augment.runtime.MatchMode;
import io.github.sx2xoox.mcpl.game.augment.runtime.OfferDialogs;
import io.github.sx2xoox.mcpl.game.augment.runtime.PaperOfferDialogs;
import io.github.sx2xoox.mcpl.game.augment.runtime.WildArena;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.GameRules;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * 증강 야생전 — every match is played in a fresh vanilla survival world: farm without PvP, then fight while the world
 * border closes in; timed augment rounds and special achievements hand out augments (pick 1 of 3, LoL ARAM / TFT
 * style). See {@code docs/augment.md}.
 *
 * <p>Owns what outlives a match: the augment registry, {@code augments.yml}, the {@code augment.yml} messages and the
 * match world being prepared ({@link #createArena()} makes it before the countdown, {@link #releaseArena} deletes it).
 */
public final class AugmentGame implements MiniGame {

    public static final String ID = "augment";
    public static final String MESSAGES_FILE = "augment.yml";
    /** Name prefix of the match worlds ({@code mcpl_wild_1}, ...). */
    public static final String WORLD_PREFIX = "mcpl_wild_";

    /** Nothing in the throw-away world needs protecting: survival rules apply there. */
    private static final ProtectionSettings UNPROTECTED =
            new ProtectionSettings(false, false, false, false, false, false, false, false, false);

    private final Hub hub;
    private final AugmentRegistry registry = new AugmentRegistry();
    private final AugmentConfig augments;
    private final Messages messages;
    private final Random random = new Random();
    private @Nullable List<SettingsSection> settings;
    private @Nullable WildArena preparing;
    private @Nullable AugmentSettings preparingSettings;
    private @Nullable AugmentMatch current;
    private OfferDialogs offerDialogs =
            PaperOfferDialogs.INSTANCE;
    private final AugmentPracticeGame practice;
    private final AugmentBotGame botGame;
    private final AugmentSpectateGame spectateGame;
    /** Bots and difficulty asked for with {@code /게임 증강 봇전 [관전]}, used by the next bot match. */
    private AugmentMatch.@Nullable BotSetup botRequest;
    /** Players who asked to join the running practice ({@code /게임 증강 연습}). */
    private final java.util.Set<java.util.UUID> practiceJoins = new java.util.HashSet<>();

    public AugmentGame(Hub hub) {
        this.hub = hub;
        BuiltinAugments.registerAll(registry);
        this.practice = new AugmentPracticeGame(this);
        this.botGame = new AugmentBotGame(this);
        this.spectateGame = new AugmentSpectateGame(this);
        this.messages = new Messages(hub.plugin(), MESSAGES_FILE, hub.messages());
        this.messages.load();
        this.augments = new AugmentConfig(new File(hub.plugin().getDataFolder(), AugmentConfig.FILE_NAME),
                hub.plugin().getLogger());
        this.augments.load(registry);
        // Worlds of matches that never ended cleanly (crash, kill -9) are deleted now.
        hub.tempWorlds().registerPrefix(WORLD_PREFIX);
    }

    // =================================================================== MiniGame

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "증강 야생전";
    }

    @Override
    public Component displayComponent() {
        return messages.get("game.name");
    }

    @Override
    public List<String> aliases() {
        return List.of("증강", "야생전", "증강전", "augments", "wild");
    }

    @Override
    public Material icon() {
        return Material.AMETHYST_CLUSTER;
    }

    @Override
    public List<Component> description() {
        return messages.lines("game.description", Messages.tag("count", registry.all().size()));
    }

    @Override
    public boolean supportsTeams() {
        return true;
    }

    @Override
    public void configDefaults(ConfigurationSection defaults) {
        AugmentSettings.declareDefaults(defaults);
        AugmentPracticeSettings.declareDefaults(defaults);
        BotSettings.declareDefaults(defaults);
        io.github.sx2xoox.mcpl.game.augment.bot.BotChat.Settings.declareDefaults(defaults);
    }

    @Override
    public ProtectionSettings protection(ProtectionSettings configured) {
        return UNPROTECTED;
    }

    /** Reads {@code games.augment} now. */
    public AugmentSettings readSettings() {
        return AugmentSettings.read(hub.config().gameSection(ID), hub.plugin().getLogger());
    }

    /**
     * Makes the match world: a new vanilla overworld with a random seed, the world border around its spawn and the
     * play area pre-generating in the background while the countdown runs.
     */
    @Override
    public Arena createArena() throws GameStartException {
        return createArena(MatchMode.NORMAL);
    }

    /** {@link #createArena()} for a session of {@code mode} (a practice plays in a smaller area). */
    Arena createArena(MatchMode mode) throws GameStartException {
        AugmentSettings read = readSettings();
        if (mode == MatchMode.PRACTICE) {
            read = read.withRadius(readPracticeSettings().radius());
        } else if (mode == MatchMode.SPECTATE) {
            BotSettings.Spectate spectate = readBotSettings().spectate();
            read = read.withTimings(spectate.farmingTicks(), spectate.borderDelayTicks(),
                    spectate.borderShrinkTicks());
        }
        World world;
        try {
            world = hub.tempWorlds().create(WORLD_PREFIX, random.nextLong(), read.structures());
        } catch (RuntimeException | LinkageError e) {
            hub.plugin().getLogger().log(Level.SEVERE, "Could not create the match world", e);
            throw new GameStartException(messages.prefixed("error.world-failed"), e);
        }
        try {
            setUp(world, read);
            Location spawn = world.getSpawnLocation();
            int x = spawn.getBlockX();
            int z = spawn.getBlockZ();
            int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
            Arena arena = new Arena(world, new Location(world, x + 0.5, y, z + 0.5), read.radius());
            ChunkPregen pregen = read.pregenerate()
                    ? ChunkPregen.start(hub.plugin(), hub.tempWorlds().provider(), world, arena.centerX(),
                    arena.centerZ(), read.radius() + 16, read.pregenChunksPerTick(), read.pregenChunksPerTick() * 8)
                    : null;
            preparing = new WildArena(world, arena, pregen);
            preparingSettings = read;
            return arena;
        } catch (RuntimeException | LinkageError e) {
            hub.plugin().getLogger().log(Level.SEVERE, "Could not set up the match world " + world.getName(), e);
            hub.tempWorlds().dispose(world);
            throw new GameStartException(messages.prefixed("error.world-failed"), e);
        }
    }

    private void setUp(World world, AugmentSettings read) {
        world.setDifficulty(read.difficulty());
        world.setTime(0);
        rule(world, GameRules.KEEP_INVENTORY, false);
        // PvP is gated by the match phase, not the world: a server with pvp off by default must not block the fight
        rule(world, GameRules.PVP, true);
        // Overworld only, and nothing that leaks positions or hints at the real world.
        rule(world, GameRules.ALLOW_ENTERING_NETHER_USING_PORTALS, false);
        rule(world, GameRules.LOCATOR_BAR, false);
        rule(world, GameRules.SPECTATORS_GENERATE_CHUNKS, false);
        // Phantoms follow a player's real time without sleep; nobody sleeps in a match.
        rule(world, GameRules.SPAWN_PHANTOMS, false);
        world.getWorldBorder().setCenter(world.getSpawnLocation());
        world.getWorldBorder().setSize(read.radius() * 2.0);
    }

    private <T> void rule(World world, GameRule<T> rule, T value) {
        try {
            world.setGameRule(rule, value);
        } catch (RuntimeException e) {
            hub.plugin().getLogger().log(Level.FINE, "Could not set " + rule + " in " + world.getName(), e);
        }
    }

    /** The match is over and everyone left: the world is unloaded and deleted. */
    @Override
    public void releaseArena(Arena arena) {
        WildArena wild = preparing;
        if (wild != null && wild.world().equals(arena.world())) {
            if (wild.pregen() != null) {
                wild.pregen().cancel();
            }
            preparing = null;
            preparingSettings = null;
        }
        hub.tempWorlds().dispose(arena.world());
    }

    @Override
    public GameInstance createInstance(GameSession session) {
        return createMatch(session, MatchMode.NORMAL);
    }

    /** A new match of this mode or a practice session (both are the {@link #currentMatch()} while they run). */
    AugmentMatch createMatch(GameSession session, MatchMode mode) {
        WildArena wild = preparing;
        AugmentSettings read = preparingSettings;
        if (wild == null || read == null || !wild.world().equals(session.arena().world())) {
            // Not made by createArena (should not happen): play in the session's arena as it is.
            wild = new WildArena(session.arena().world(), session.arena(), null);
            read = readSettings();
        }
        AugmentMatch.BotSetup bots = null;
        if (mode == MatchMode.BOTS || mode == MatchMode.SPECTATE) {
            bots = botRequest;
            botRequest = null;
            if (bots == null) {
                BotSettings botSettings = readBotSettings();
                bots = AugmentMatch.BotSetup.of(botSettings, botSettings.difficulty(), botSettings.count());
            }
            if (mode == MatchMode.SPECTATE && bots.count() < 2) {
                bots = AugmentMatch.BotSetup.of(bots.settings(), bots.lineup().getFirst(), 2); // a fight needs two
            }
        }
        AugmentMatch match = new AugmentMatch(this, session, wild, read, mode,
                mode == MatchMode.PRACTICE ? readPracticeSettings() : null, bots);
        current = match;
        return match;
    }

    @Override
    public List<SettingsSection> settings() {
        if (settings == null) {
            settings = AugmentSettingEntries.create(this);
        }
        return settings;
    }

    @Override
    public void registerCommands(GameCommandRegistrar registrar) {
        new AugmentCommand(this).register(registrar);
    }

    @Override
    public void onReload() {
        messages.load();
        if (!augments.load(registry)) {
            Component warning = messages.prefixed("config.augments-broken");
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission(Permissions.ADMIN)) {
                    player.sendMessage(warning);
                }
            }
        }
    }

    @Override
    public void onShutdown() {
        current = null;
        practiceJoins.clear();
        botRequest = null;
    }

    // =================================================================== shared state

    public Hub hub() {
        return hub;
    }

    /** Reads {@code games.augment.practice} now. */
    public AugmentPracticeSettings readPracticeSettings() {
        return AugmentPracticeSettings.read(hub.config().gameSection(ID));
    }

    /** Reads {@code games.augment.bots} now. */
    public BotSettings readBotSettings() {
        return BotSettings.read(hub.config().gameSection(ID));
    }

    /** 봇전 ({@code /게임 증강 봇전}), a third mini-game sharing this mode's augments. */
    public AugmentBotGame botGame() {
        return botGame;
    }

    /** 봇 관전전 ({@code /게임 증강 봇전 관전}): only bots play, everyone watches. */
    public AugmentSpectateGame spectateGame() {
        return spectateGame;
    }

    /** The bots of the next 봇전 or 봇 관전전 (null: the configured defaults). */
    public void requestBots(AugmentMatch.@Nullable BotSetup setup) {
        this.botRequest = setup;
    }

    /** 증강 연습 ({@code /게임 증강 연습}), a second mini-game sharing this mode's augments. */
    public AugmentPracticeGame practice() {
        return practice;
    }

    /** {@code player} asked to join the running practice (only such players, and rejoining ones, are let in). */
    public void allowPracticeJoin(java.util.UUID player, boolean allowed) {
        if (allowed) {
            practiceJoins.add(player);
        } else {
            practiceJoins.remove(player);
        }
    }

    /** Removes and returns whether {@code player} asked to join the running practice. */
    public boolean takePracticeJoin(java.util.UUID player) {
        return practiceJoins.remove(player);
    }

    /** Shows the augment pick dialogs ({@code augments.ui: DIALOG}). */
    public OfferDialogs offerDialogs() {
        return offerDialogs;
    }

    /** Replaces how dialogs are shown (tests: MockBukkit cannot build Paper dialogs). */
    public void offerDialogs(OfferDialogs dialogs) {
        this.offerDialogs = dialogs;
    }

    public AugmentRegistry registry() {
        return registry;
    }

    /** {@code augments.yml}. */
    public AugmentConfig augments() {
        return augments;
    }

    public Messages messages() {
        return messages;
    }

    /** The running match of this mode, or null. */
    public @Nullable AugmentMatch currentMatch() {
        AugmentMatch match = current;
        GameSession session = hub.games().session();
        if (match == null || session == null || session.instance() != match) {
            return null;
        }
        return match;
    }

    /** Called by the match when it stops. */
    public void matchEnded(AugmentMatch match) {
        if (current == match) {
            current = null;
        }
    }

    /** Finds an augment by id or Korean name (case and spaces ignored), also by a unique prefix. */
    public @Nullable AugmentInfo findAugment(String input) {
        String wanted = normalize(input);
        if (wanted.isEmpty()) {
            return null;
        }
        AugmentInfo prefixMatch = null;
        int prefixMatches = 0;
        for (AugmentRegistry.Entry entry : registry.all()) {
            AugmentInfo info = entry.info();
            if (info.id().equals(wanted) || normalize(info.name()).equals(wanted)) {
                return info;
            }
            if (normalize(info.name()).startsWith(wanted) || info.id().startsWith(wanted)) {
                prefixMatch = info;
                prefixMatches++;
            }
        }
        return prefixMatches == 1 ? prefixMatch : null;
    }

    private static String normalize(String text) {
        return text.replace(" ", "").toLowerCase(Locale.ROOT);
    }
}
