package io.github.sx2xoox.mcpl.game.augment.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.core.arena.Spread;
import io.github.sx2xoox.mcpl.core.game.DeathOutcome;
import io.github.sx2xoox.mcpl.core.game.GameInstance;
import io.github.sx2xoox.mcpl.core.game.GameResult;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.game.MatchTeam;
import io.github.sx2xoox.mcpl.core.player.PlayerReset;
import io.github.sx2xoox.mcpl.core.ui.Feedback;
import io.github.sx2xoox.mcpl.core.ui.Format;
import io.github.sx2xoox.mcpl.core.ui.ManagedBossBar;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.game.augment.AugmentGame;
import io.github.sx2xoox.mcpl.game.augment.AugmentPracticeSettings;
import io.github.sx2xoox.mcpl.game.augment.AugmentSettings;
import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentRegistry;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import io.github.sx2xoox.mcpl.game.augment.api.Params;
import io.github.sx2xoox.mcpl.game.augment.bonus.BonusCondition;
import io.github.sx2xoox.mcpl.game.augment.bot.BotChat;
import io.github.sx2xoox.mcpl.game.augment.bot.BotController;
import io.github.sx2xoox.mcpl.game.augment.bot.BotDifficulty;
import io.github.sx2xoox.mcpl.game.augment.bot.BotHost;
import io.github.sx2xoox.mcpl.game.augment.bot.BotManager;
import io.github.sx2xoox.mcpl.game.augment.bot.BotMotion;
import io.github.sx2xoox.mcpl.game.augment.bot.BotSettings;
import io.github.sx2xoox.mcpl.game.augment.config.AugmentConfig;
import io.github.sx2xoox.mcpl.core.combat.CombatTracker;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.WorldBorder;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jspecify.annotations.Nullable;

/**
 * One match of 증강 야생전 (see {@code docs/augment.md}): {@link MatchPhase#PREPARING} while the fresh world
 * generates, {@link MatchPhase#FARMING} (survival, no PvP), then {@link MatchPhase#FIGHT} (PvP, the world border
 * shrinks) until one player or team is left. Augments are offered in timed rounds and for special achievements
 * ({@link BonusTracker}); each player picks one of three cards in a dialog ({@link OfferDialog}) or, with
 * {@code augments.ui: CHEST}, a chest menu ({@link OfferMenu}).
 */
public final class AugmentMatch implements GameInstance, BotHost {

    private final AugmentGame game;
    private final GameSession session;
    private final WildArena wild;
    private final AugmentSettings settings;
    private final AugmentConfig.Snapshot config;
    private final Map<UUID, PlayerState> players = new LinkedHashMap<>();
    private final Set<BonusCondition> claimed = EnumSet.noneOf(BonusCondition.class);
    private final BonusTracker bonus;
    private final MatchHud hud;
    private MatchPhase phase = MatchPhase.PREPARING;
    /** Ticks since the farming started (0 while preparing). */
    private long clock;
    private long preparingTicks;
    private int nextRound;
    private int roundsPlayed;
    private boolean borderShrinking;
    /** {@link #clock} when the fight phase started. */
    private long fightStart;
    private boolean borderFinal;
    private @Nullable ManagedBossBar bar;
    private boolean stopped;
    /** Nesting depth of {@link #damage} calls (augment damage runs no augment damage hooks). */
    private int augmentDamageDepth;
    private final MatchMode mode;
    private final @Nullable AugmentPracticeSettings practiceSettings;
    private final @Nullable PracticeDummies dummies;
    /** Practice: practising players can hurt each other. */
    private boolean practicePvp;
    private final @Nullable BotManager bots;
    private final @Nullable BotSetup botSetup;
    private final @Nullable SpectatorView spectators;
    /** Recent hits between participants (bot matches): the bots' chat reads it (a third party joining a fight). */
    private final FightLog fightLog = new FightLog();
    /** 봇 채팅 (bot matches). */
    private final @Nullable BotChatFeed chat;
    /** Match-clock ticks since the farming started: phases, rounds and the border run on it. */
    private long time;
    private int timeScale = 1;
    /** The fraction of a match-clock tick carried over (the clock runs at a fractional rate while the border closes). */
    private double timeCarry;

    /**
     * The bots of a 봇전 or 봇 관전전 match.
     *
     * @param settings {@code games.augment.bots}
     * @param lineup   the difficulty of each bot (how many and how well they play)
     */
    public record BotSetup(BotSettings settings, List<BotDifficulty> lineup) {

        public BotSetup {
            lineup = List.copyOf(lineup);
            if (lineup.isEmpty()) {
                throw new IllegalArgumentException("no bots");
            }
        }

        /** {@code count} bots of one difficulty. */
        public static BotSetup of(BotSettings settings, BotDifficulty difficulty, int count) {
            return new BotSetup(settings, Collections.nCopies(Math.max(1, count), difficulty));
        }

        public int count() {
            return lineup.size();
        }

        /** More than one difficulty plays. */
        public boolean mixed() {
            return lineup.stream().distinct().count() > 1;
        }

        /** "쉬움 3 · 어려움 2" or "보통" (the lineup for announcements). */
        public String describe() {
            Map<BotDifficulty, Integer> counts = new java.util.EnumMap<>(BotDifficulty.class);
            for (BotDifficulty each : lineup) {
                counts.merge(each, 1, Integer::sum);
            }
            if (counts.size() == 1) {
                return lineup.getFirst().koreanName();
            }
            List<String> parts = new ArrayList<>();
            counts.forEach((difficulty, count) -> parts.add(difficulty.koreanName() + " " + count));
            return String.join(" · ", parts);
        }
    }

    public AugmentMatch(AugmentGame game, GameSession session, WildArena wild, AugmentSettings settings) {
        this(game, session, wild, settings, MatchMode.NORMAL, null);
    }

    public AugmentMatch(AugmentGame game, GameSession session, WildArena wild, AugmentSettings settings,
                        MatchMode mode, @Nullable AugmentPracticeSettings practiceSettings) {
        this(game, session, wild, settings, mode, practiceSettings, null);
    }

    public AugmentMatch(AugmentGame game, GameSession session, WildArena wild, AugmentSettings settings,
                        MatchMode mode, @Nullable AugmentPracticeSettings practiceSettings,
                        @Nullable BotSetup botSetup) {
        this.game = game;
        this.session = session;
        this.wild = wild;
        this.settings = settings;
        this.mode = mode;
        this.config = game.augments().snapshot();
        this.bonus = new BonusTracker(this);
        this.hud = new MatchHud(this);
        AugmentPracticeSettings practice = practiceSettings;
        if (mode == MatchMode.PRACTICE && practice == null) {
            practice = game.readPracticeSettings();
        }
        this.practiceSettings = mode == MatchMode.PRACTICE ? practice : null;
        this.dummies = this.practiceSettings != null ? new PracticeDummies(this, this.practiceSettings) : null;
        this.practicePvp = this.practiceSettings != null && this.practiceSettings.pvp();
        this.botSetup = botSetup;
        boolean spectate = mode == MatchMode.SPECTATE;
        this.bots = botSetup != null ? new BotManager(this, botSetup.settings(), botSetup.lineup(),
                spectate || botSetup.mixed(), true) : null;
        this.spectators = spectate && bots != null ? new SpectatorView(this, bots, botSetup.settings().spectate())
                : null;
        this.chat = bots != null && mode != MatchMode.PRACTICE ? new BotChatFeed(this, bots,
                new BotChat(BotChat.Settings.read(game.hub().config().gameSection(AugmentGame.ID)),
                        BotChatFeed.loadLines(game.hub().plugin()), new Random()), fightLog, new Random()) : null;
        if (spectate && botSetup != null) {
            this.timeScale = botSetup.settings().spectate().timeScale();
        }
    }

    /** True for a 봇 관전전 (only bots play). */
    public boolean isSpectate() {
        return mode == MatchMode.SPECTATE;
    }

    /** The spectator tools of a 봇 관전전 (null otherwise). */
    public @Nullable SpectatorView spectators() {
        return spectators;
    }

    // =================================================================== time scale

    /** How fast the match clocks run (1 normally, up to 4 with {@code /게임 증강 배속}). */
    @Override
    public int timeScale() {
        return timeScale;
    }

    /**
     * Sets how fast the match clocks run: the farming timer, the augment rounds, the border and with them the bots'
     * gear schedule and base building ({@link BotHost#timeScale()}). Entities, offers and augments stay real-time.
     *
     * @return false if this match has no bots, is a practice or is over
     */
    public boolean setTimeScale(int scale) {
        if (bots == null || isPractice() || stopped) {
            return false;
        }
        int clamped = Math.max(1, Math.min(BotSettings.Spectate.MAX_TIME_SCALE, scale));
        if (clamped == timeScale) {
            return true;
        }
        timeScale = clamped;
        if (phase == MatchPhase.FIGHT && borderShrinking && !borderFinal) {
            // the vanilla border keeps moving on its own: aim it again at the new pace
            long left = Math.max(0, settings.borderDelayTicks() + settings.borderShrinkTicks() - fightTicks());
            wild.world().getWorldBorder().changeSize(settings.borderFinalSize(), realTicks(left));
        }
        hud.update();
        return true;
    }

    /** Real ticks for {@code scaled} ticks of the match clocks at the current speed ({@link #clockRate()}). */
    long realTicks(long scaled) {
        return (long) Math.ceil(Math.max(0, scaled) / clockRate() - 1.0E-9);
    }

    /**
     * Match-clock ticks per real tick right now: the {@link #timeScale()}, except while the border closes, where it is
     * capped so the border's edge never moves faster than {@link BotMotion#maxBorderEdgeSpeed()} (70 % of a bot's
     * sprint): a faster clock must not make the border impossible to outrun ({@link BotMotion#shrinkClockRate}).
     */
    public double clockRate() {
        if (timeScale <= 1 || phase != MatchPhase.FIGHT || !borderShrinking || borderFinal) {
            return timeScale;
        }
        return BotMotion.shrinkClockRate(timeScale, settings.radius() * 2.0, settings.borderFinalSize(),
                settings.borderShrinkTicks());
    }

    /** Ticks of the match clocks since the farming started (runs {@link #timeScale()} times as fast as the clock). */
    public long time() {
        return time;
    }

    /** The bots of a 봇전 match (null otherwise). */
    public @Nullable BotManager bots() {
        return bots;
    }

    // =================================================================== accessors

    public AugmentGame game() {
        return game;
    }

    public GameSession session() {
        return session;
    }

    public Messages messages() {
        return game.messages();
    }

    public AugmentSettings settings() {
        return settings;
    }

    public AugmentConfig.Snapshot config() {
        return config;
    }

    public WildArena wild() {
        return wild;
    }

    public MatchPhase phase() {
        return phase;
    }

    public MatchMode mode() {
        return mode;
    }

    /** True for a 증강 연습 session. */
    public boolean isPractice() {
        return mode == MatchMode.PRACTICE;
    }

    /**
     * The farming of a real match (also 봇전 and 봇 관전전, not practice) with {@code farming.invulnerable}:
     * participants, players and bots, take no damage. Ends when the fight starts.
     */
    public boolean farmingInvulnerable() {
        return !isPractice() && phase == MatchPhase.FARMING && settings.farmingInvulnerable();
    }

    /** The farming of a real match with {@code farming.no-hunger}: players' food and saturation stay full. */
    public boolean farmingNoHunger() {
        return !isPractice() && phase == MatchPhase.FARMING && settings.farmingNoHunger();
    }

    /** Practice: whether practising players can hurt each other (always false in a real match: see the phases). */
    public boolean practicePvp() {
        return practicePvp;
    }

    /** Ticks since the farming started. */
    public long clock() {
        return clock;
    }

    /** Augments the player owns, in pick order (also after elimination). */
    public List<AugmentInfo> augments(UUID player) {
        PlayerState state = players.get(player);
        return state == null ? List.of() : state.augments();
    }

    /** The player's current (first queued) offer, or null. */
    public @Nullable Offer currentOffer(UUID player) {
        PlayerState state = players.get(player);
        return state == null ? null : state.current();
    }

    /** Offers waiting for the player (the current one included). */
    public int pendingOffers(UUID player) {
        PlayerState state = players.get(player);
        return state == null ? 0 : state.offers.size();
    }

    public int rerollsLeft(UUID player) {
        PlayerState state = players.get(player);
        return state == null ? 0 : state.rerolls;
    }

    /** Bonus conditions the player achieved. */
    public Set<BonusCondition> bonuses(UUID player) {
        PlayerState state = players.get(player);
        return state == null ? Set.of() : Collections.unmodifiableSet(state.bonuses);
    }

    /** First-come conditions already claimed in this match. */
    public Set<BonusCondition> claimed() {
        return Collections.unmodifiableSet(claimed);
    }

    /** The open card menu of the player, or null. */
    public @Nullable OfferMenu menu(UUID player) {
        PlayerState state = players.get(player);
        return state == null ? null : state.menu;
    }

    /** Index (1-based) of the next timed round, or -1 if all were played. */
    public int nextRoundNumber() {
        return nextRound < settings.rounds().size() ? nextRound + 1 : -1;
    }

    /** Real ticks until the next timed round (at the current {@link #timeScale()}), or -1. */
    public long ticksToNextRound() {
        if (nextRound >= settings.rounds().size()) {
            return -1;
        }
        return realTicks(settings.rounds().get(nextRound).atTicks() - time);
    }

    public int roundsPlayed() {
        return roundsPlayed;
    }

    public BonusTracker bonus() {
        return bonus;
    }

    @Nullable PlayerState state(UUID player) {
        return players.get(player);
    }

    Collection<PlayerState> states() {
        return players.values();
    }

    // =================================================================== GameInstance

    @Override
    public void start() {
        for (UUID id : session.participantIds()) {
            players.put(id, new PlayerState(id, settings.rerolls()));
        }
        session.registerListener(new MatchListener(this));
        if (dummies != null) {
            session.registerListener(dummies);
        }
        if (bots != null && botSetup != null) {
            for (UUID id : bots.register()) {
                players.put(id, new PlayerState(id, 0));
            }
            session.registerListener(bots);
            if (chat != null) {
                session.registerListener(new FightLog.Recorder(this, fightLog, chat::hit));
            }
            if (isSpectate()) {
                session.formTeams();
                announce(messages().prefixed("spectate.joined", Messages.tag("count", bots.all().size()),
                        Messages.tag("difficulty", botSetup.describe())));
                if (spectators != null) {
                    spectators.announceTeams();
                    spectators.welcome();
                }
            } else {
                announce(messages().prefixed("bots.joined", Messages.tag("count", bots.all().size()),
                        Messages.tag("difficulty", botSetup.describe())));
            }
        }
        bar = session.sharedBossBar(messages().get("bar.preparing", Messages.tag("percent", 0),
                        Messages.tag("done", 0), Messages.tag("total", 0)), 0f, BossBar.Color.WHITE,
                BossBar.Overlay.PROGRESS);
        if (wild.ready()) {
            beginFarming();
        } else {
            announce(messages().prefixed("phase.preparing"));
        }
    }

    @Override
    public void tick() {
        if (stopped) {
            return;
        }
        if (phase == MatchPhase.PREPARING) {
            preparingTicks++;
            if (wild.ready() || preparingTicks >= settings.pregenMaxWaitTicks()) {
                beginFarming();
            } else if (preparingTicks % 10 == 0) {
                updatePreparingBar();
            }
            return;
        }
        clock++;
        timeCarry += clockRate();
        long step = (long) timeCarry;
        timeCarry -= step;
        time += step;
        if (isSpectate() && clock % 20 == 0 && session.viewers().isEmpty()) {
            session.abort(messages().get("spectate.everyone-left")); // nobody left to watch
            return;
        }
        if (!isPractice()) {
            if (phase == MatchPhase.FARMING && time >= settings.farmingTicks()) {
                beginFight();
            }
            startDueRounds();
        } else if (dummies != null && clock % 10 == 0) {
            dummies.tick(clock);
        }
        if (phase == MatchPhase.FIGHT) {
            tickBorder();
        } else if (farmingNoHunger() && clock % 20 == 0) {
            for (Player player : session.alivePlayers()) {
                FarmingRules.feed(player);
            }
        }
        tickOffers();
        tickAugments();
        if (bots != null) {
            bots.tick(clock);
        }
        if (spectators != null && clock % 20 == 0) {
            spectators.tick();
        }
        if (chat != null && clock % 10 == 0) {
            chat.tick();
        }
        bonus.tick();
        if (clock % 10 == 0) {
            hud.update();
        }
        if (clock % 20 == 0) {
            checkWin();
        }
    }

    @Override
    public boolean dropsOnDeath() {
        return !isPractice(); // a practising player keeps their things
    }

    @Override
    public DeathOutcome onDeath(Player victim, @Nullable Player killer, PlayerDeathEvent event) {
        if (killer != null && !session.sameTeam(killer.getUniqueId(), victim.getUniqueId())) {
            creditKill(killer, victim);
        }
        LivingEntity killerBody = killer;
        UUID bot = killer == null ? botKiller(victim.getUniqueId()) : null;
        if (bot != null) {
            killerBody = body(bot);
            forEachAugment(bot, "onKill", augment -> augment.onKill(victim));
            if (chat != null) {
                chat.killed(bot, victim.getUniqueId());
            }
        }
        if (isPractice()) {
            // practice: back on your feet near where you fell, augments kept
            return DeathOutcome.respawn(session.safeSpot(victim.getLocation()));
        }
        PlayerState dead = players.get(victim.getUniqueId());
        if (dead != null && !stopped) {
            for (AugmentSlot slot : List.copyOf(dead.slots)) {
                if (!slot.isRemoved()) {
                    try {
                        slot.augment().onDeath(killerBody);
                    } catch (RuntimeException e) {
                        log("onDeath of " + slot.info().id(), e);
                    }
                }
            }
        }
        return DeathOutcome.eliminate();
    }

    /** The bot credited with the last hit on {@code victim} (kill credit window), or null. */
    private @Nullable UUID botKiller(UUID victim) {
        if (bots == null) {
            return null;
        }
        UUID id = session.combat().lastAttackerId(victim, CombatTracker.DEFAULT_WINDOW_TICKS);
        return id != null && bots.isBot(id) ? id : null;
    }

    /**
     * The participant {@code killer} was credited with killing the enemy combatant {@code victim} (a player, a bot's
     * body or a practice dummy; the caller checked it was an enemy: it is dead by now).
     */
    void creditKill(Player killer, LivingEntity victim) {
        if (stopped) {
            return;
        }
        bonus.onPlayerKill(killer, victim);
        forEachAugment(killer, "onKill", augment -> augment.onKill(victim));
    }

    @Override
    public void onRespawn(Player player) {
        player.setGameMode(GameMode.SURVIVAL);
        PlayerReset.heal(player);
    }

    @Override
    public boolean allowLateJoin(Player player, boolean rejoining) {
        return isPractice() && !stopped && (game.takePracticeJoin(player.getUniqueId()) || rejoining);
    }

    @Override
    public void onLateJoin(Player player) {
        players.computeIfAbsent(player.getUniqueId(), id -> new PlayerState(id, settings.rerolls()));
        if (phase == MatchPhase.PREPARING) {
            return; // spread with everyone when the world is ready
        }
        Location spot = null;
        for (Player other : session.alivePlayers()) {
            if (!other.equals(player) && other.getWorld().equals(wild.world())) {
                spot = session.safeSpot(other.getLocation());
                break;
            }
        }
        if (spot == null) {
            spot = Spread.findSurfaceSpawns(wild.arena(), 1, session.random()).getFirst();
        }
        player.setFallDistance(0f);
        player.teleport(spot);
        player.setGameMode(GameMode.SURVIVAL);
        PlayerReset.heal(player);
        hud.update();
    }

    @Override
    public void onQuit(Player player) {
        strip(player.getUniqueId());
    }

    @Override
    public void onEliminated(Player player, @Nullable Player killer) {
        strip(player.getUniqueId());
        if (bots != null) {
            bots.forget(player.getUniqueId());
        }
        if (player.isOnline()) {
            player.removePotionEffect(PotionEffectType.GLOWING);
        }
        checkWin();
    }

    @Override
    public @Nullable Component playerTag(UUID player) {
        PlayerState state = players.get(player);
        if (state == null || state.slots.isEmpty()) {
            return null;
        }
        Component tag = Component.empty();
        for (AugmentInfo info : state.augments()) {
            tag = tag.append(Component.text("◆", info.tier().color()));
        }
        return tag;
    }

    @Override
    public void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        if (wild.pregen() != null) {
            wild.pregen().cancel();
        }
        if (dummies != null) {
            dummies.dispose();
        }
        if (bots != null) {
            bots.dispose();
        }
        for (PlayerState state : players.values()) {
            closeMenu(state);
            state.offers.clear();
            for (AugmentSlot slot : state.slots) {
                slot.remove();
            }
            Player player = org.bukkit.Bukkit.getPlayer(state.id);
            if (player != null) {
                player.removePotionEffect(PotionEffectType.GLOWING);
            }
        }
        game.matchEnded(this);
    }

    // =================================================================== phases

    private void updatePreparingBar() {
        if (bar == null) {
            return;
        }
        double progress = wild.progress();
        int total = wild.pregen() == null ? 0 : wild.pregen().total();
        int done = wild.pregen() == null ? 0 : wild.pregen().completed();
        bar.name(messages().get("bar.preparing", Messages.tag("percent", (int) Math.floor(progress * 100)),
                Messages.tag("done", done), Messages.tag("total", total)));
        bar.progress((float) progress);
    }

    /** The world is ready: everyone is spread over it in survival mode and the farming clock starts. */
    void beginFarming() {
        if (phase != MatchPhase.PREPARING) {
            return;
        }
        phase = MatchPhase.FARMING;
        clock = 0;
        time = 0;
        spread();
        if (bots != null && !bots.all().isEmpty()) {
            // more spots than bots: the manager prefers the dry ones
            bots.spawnAll(Spread.findSurfaceSpawns(wild.arena(), bots.all().size() * 3, session.random()));
        }
        WorldBorder border = wild.world().getWorldBorder();
        border.setCenter(wild.arena().centerX(), wild.arena().centerZ());
        border.setSize(settings.radius() * 2.0);
        border.setDamageBuffer(0);
        border.setDamageAmount(settings.borderDamage());
        border.setWarningDistance(8);
        if (bar != null) {
            bar.color(BossBar.Color.GREEN);
        }
        if (isPractice()) {
            Feedback.title(session.audience(), messages().get("practice.title"),
                    messages().get("practice.subtitle"), 5, 60, 15);
            announce(messages().prefixed("practice.started"));
        } else {
            long farming = realTicks(settings.farmingTicks()) * 50;
            Feedback.title(session.audience(), messages().get("phase.farming-title"),
                    messages().get(isSpectate() ? "spectate.farming-subtitle" : "phase.farming-subtitle",
                            Messages.tag("time", Format.duration(farming))), 5, 60, 15);
            announce(messages().prefixed(isSpectate() ? "spectate.farming" : "phase.farming",
                    Messages.tag("time", Format.duration(farming)), Messages.tag("rounds", settings.rounds().size())));
            if (farmingInvulnerable() || farmingNoHunger()) {
                announce(messages().prefixed(farmingInvulnerable()
                        ? (farmingNoHunger() ? "phase.farming-grace" : "phase.farming-invulnerable")
                        : "phase.farming-no-hunger"));
            }
        }
        session.playSound(Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.2f);
        hud.update();
        if (chat != null) {
            chat.farmingStarted();
        }
    }

    private void spread() {
        List<Player> playing = session.alivePlayers();
        if (playing.isEmpty()) {
            return;
        }
        Map<Object, Location> spots = new LinkedHashMap<>();
        List<Object> groups = new ArrayList<>();
        for (Player player : playing) {
            MatchTeam team = session.teamOf(player);
            Object group = team != null ? team : player.getUniqueId();
            if (!groups.contains(group)) {
                groups.add(group);
            }
        }
        List<Location> found = Spread.findSurfaceSpawns(wild.arena(), groups.size(), session.random());
        for (int i = 0; i < groups.size(); i++) {
            spots.put(groups.get(i), found.get(Math.min(i, found.size() - 1)));
        }
        for (Player player : playing) {
            MatchTeam team = session.teamOf(player);
            Location spot = spots.get(team != null ? team : player.getUniqueId());
            player.setFallDistance(0f);
            player.setVelocity(new org.bukkit.util.Vector());
            player.teleport(spot);
            player.setGameMode(GameMode.SURVIVAL);
            PlayerReset.heal(player);
        }
    }

    /** A chat line to everyone; in practice only to the practising players. */
    void announce(Component line) {
        if (!isPractice()) {
            session.broadcast(line);
            return;
        }
        for (Player player : session.participants()) {
            player.sendMessage(line);
        }
    }

    /** Opens every timed round whose time has come on the match clock. */
    private void startDueRounds() {
        while (nextRound < settings.rounds().size() && time >= settings.rounds().get(nextRound).atTicks()) {
            startRound(nextRound++);
        }
    }

    private void beginFight() {
        phase = MatchPhase.FIGHT;
        fightStart = time;
        if (bar != null) {
            bar.color(BossBar.Color.RED);
        }
        Feedback.title(session.audience(), messages().get("phase.fight-title"), messages().get("phase.fight-subtitle"),
                5, 60, 15);
        announce(messages().prefixed("phase.fight", Messages.tag("time",
                Format.duration(realTicks(settings.borderDelayTicks()) * 50)), Messages.tag("size",
                Params.format(settings.borderFinalSize()))));
        session.playSound(Sound.ENTITY_ENDER_DRAGON_GROWL, 0.7f, 1f);
        if (chat != null) {
            chat.fightStarted();
        }
    }

    /** Match-clock ticks since PvP started. */
    long fightTicks() {
        return phase == MatchPhase.FIGHT ? time - fightStart : 0;
    }

    private void tickBorder() {
        long fight = fightTicks();
        WorldBorder border = wild.world().getWorldBorder();
        if (!borderShrinking && fight >= settings.borderDelayTicks()) {
            borderShrinking = true;
            long left = settings.borderDelayTicks() + settings.borderShrinkTicks() - fight;
            if (settings.borderShrinkTicks() <= 0 || left <= 0) {
                border.setSize(settings.borderFinalSize());
            } else {
                border.changeSize(settings.borderFinalSize(), realTicks(left));
            }
            announce(messages().prefixed("phase.border-shrinking", Messages.tag("time",
                    Format.duration(realTicks(Math.max(0, left)) * 50)), Messages.tag("size",
                    Params.format(settings.borderFinalSize()))));
            session.playSound(Sound.BLOCK_BEACON_POWER_SELECT, 1f, 0.8f);
            if (chat != null) {
                chat.borderShrinking();
            }
        }
        if (borderShrinking && !borderFinal && fight >= settings.borderDelayTicks() + settings.borderShrinkTicks()) {
            borderFinal = true;
            announce(messages().prefixed(settings.glowAtFinal() ? "phase.final-glow" : "phase.final"));
            session.playSound(Sound.ENTITY_WITHER_SPAWN, 0.5f, 1.4f);
        }
        if (borderFinal && settings.glowAtFinal() && clock % 20 == 0) {
            List<LivingEntity> glowing = new ArrayList<>(session.alivePlayers());
            if (bots != null) {
                glowing.addAll(bots.liveBodies());
            }
            for (LivingEntity player : glowing) {
                if (!player.hasPotionEffect(PotionEffectType.GLOWING)) {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, PotionEffect.INFINITE_DURATION,
                            0, false, false, false));
                }
            }
        }
    }

    /** The border side length the server is moving towards right now (interpolated like the real border). */
    public double borderSize() {
        double start = settings.radius() * 2.0;
        if (phase != MatchPhase.FIGHT || !borderShrinking) {
            return start;
        }
        long into = fightTicks() - settings.borderDelayTicks();
        if (settings.borderShrinkTicks() <= 0 || into >= settings.borderShrinkTicks()) {
            return settings.borderFinalSize();
        }
        double progress = Math.max(0, (double) into / settings.borderShrinkTicks());
        return start + (settings.borderFinalSize() - start) * progress;
    }

    public boolean borderShrinking() {
        return borderShrinking;
    }

    public boolean borderFinal() {
        return borderFinal;
    }

    // =================================================================== rounds and offers

    private void startRound(int index) {
        AugmentSettings.Round round = settings.rounds().get(index);
        AugmentTier tier = round.roll(session.random());
        roundsPlayed++;
        Component tierName = OfferMenu.tierName(messages(), tier);
        List<Component> chances = new ArrayList<>();
        for (AugmentTier each : AugmentTier.values()) {
            if (round.percent(each) > 0) {
                chances.add(messages().get("round.chance", Messages.tag("tier", OfferMenu.tierName(messages(), each)),
                        Messages.tag("percent", Format.number(round.percent(each)))));
            }
        }
        Feedback.title(session.audience(), messages().get("round.title", Messages.tag("tier", tierName)),
                messages().get("round.subtitle", Messages.tag("round", index + 1)), 5, 50, 15);
        announce(messages().prefixed("round.announce", Messages.tag("round", index + 1),
                Messages.tag("tier", tierName),
                Messages.tag("chances", Component.join(JoinConfiguration.separator(Component.text(" · ",
                        NamedTextColor.DARK_GRAY)), chances))));
        session.playSound(tier == AugmentTier.PRISM ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.BLOCK_AMETHYST_BLOCK_CHIME,
                1f, 1f);
        Component source = messages().get("source.round", Messages.tag("round", index + 1));
        for (Player player : session.alivePlayers()) {
            offer(player, tier, source);
        }
        if (bots != null) {
            for (BotController bot : bots.all()) {
                if (bots.body(bot.id()) != null) {
                    grantBot(bot.id(), tier);
                }
            }
        }
    }

    /** A bot "picks" a card: a random augment of {@code tier} bots can own ({@link AugmentInfo#forBots()}). */
    @Nullable AugmentInfo grantBot(UUID bot, AugmentTier tier) {
        PlayerState state = players.get(bot);
        if (state == null || stopped || bots == null || bots.body(bot) == null) {
            return null;
        }
        List<AugmentInfo> cards = draw(state, tier, Set.of(), true);
        if (cards.isEmpty()) {
            return null;
        }
        AugmentInfo info = cards.getFirst();
        AugmentRegistry.Entry entry = game.registry().get(info.id());
        if (entry == null) {
            return null;
        }
        AugmentSlot slot = new AugmentSlot(this, bot, info, config.params(info), entry.factory());
        state.slots.add(slot);
        try {
            slot.augment().onGrant();
        } catch (RuntimeException e) {
            log("onGrant of " + info.id() + " (bot)", e);
        }
        hud.update();
        if (chat != null) {
            chat.augmentGranted(bot, info);
        }
        return info;
    }

    /** Gives a bot a specific augment (tests, admin tools); false if it cannot own it. */
    public boolean grantBot(UUID bot, AugmentInfo info) {
        PlayerState state = players.get(bot);
        AugmentRegistry.Entry entry = game.registry().get(info.id());
        if (state == null || entry == null || !info.forBots() || state.owns(info) || bots == null
                || bots.body(bot) == null || stopped) {
            return false;
        }
        AugmentSlot slot = new AugmentSlot(this, bot, info, config.params(info), entry.factory());
        state.slots.add(slot);
        try {
            slot.augment().onGrant();
        } catch (RuntimeException e) {
            log("onGrant of " + info.id() + " (bot)", e);
        }
        return true;
    }

    /** Queues an offer of {@code tier} for the player (opened at once if nothing else is waiting). */
    public void offer(Player player, AugmentTier tier, Component source) {
        PlayerState state = players.get(player.getUniqueId());
        if (state == null || stopped || !session.isAlive(player)) {
            return;
        }
        List<AugmentInfo> cards = draw(state, tier, Set.of());
        if (cards.isEmpty()) {
            messages().send(player, "offer.none-left");
            return;
        }
        state.offers.addLast(new Offer(tier, source, cards));
        if (state.offers.size() == 1) {
            activate(player, state);
        } else {
            messages().send(player, "offer.queued", Messages.tag("count", state.offers.size()));
            if (state.dialog != null && state.dialog == state.current()) {
                showDialog(player, state, state.dialog); // the dialog says how many more wait
            }
        }
    }

    /** Makes the first queued offer current: its timer starts and the menu opens. */
    private void activate(Player player, PlayerState state) {
        Offer offer = state.current();
        if (offer == null) {
            return;
        }
        // cards picked from an earlier offer meanwhile are replaced
        List<AugmentInfo> valid = new ArrayList<>();
        for (AugmentInfo card : offer.cards()) {
            if (!state.owns(card)) {
                valid.add(card);
            }
        }
        int count = state.cardCount();
        if (valid.size() < offer.cards().size() || valid.size() < count) {
            List<AugmentInfo> topped = new ArrayList<>(valid);
            for (AugmentInfo extra : draw(state, offer.tier(), valid)) {
                if (topped.size() >= count) {
                    break;
                }
                if (!topped.contains(extra)) {
                    topped.add(extra);
                }
            }
            if (topped.isEmpty()) {
                state.offers.pollFirst();
                messages().send(player, "offer.none-left");
                activate(player, state);
                return;
            }
            offer.cards(topped);
        }
        offer.deadline(clock + settings.choiceTicks());
        openMenu(player);
    }

    /**
     * Opens (or reopens) the card menu of the player's current offer.
     *
     * @return false if they have nothing to pick
     */
    public boolean openMenu(Player player) {
        PlayerState state = players.get(player.getUniqueId());
        if (state == null || state.current() == null || stopped || !session.isInPlay(player)) {
            return false;
        }
        Offer offer = state.current();
        if (!offer.started()) {
            offer.deadline(clock + settings.choiceTicks());
        }
        if (settings.offerUi() == AugmentSettings.OfferUi.DIALOG) {
            showDialog(player, state, offer);
            Feedback.play(player, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.8f, 1.3f);
            return true;
        }
        OfferMenu menu = new OfferMenu(player.getUniqueId(), offer, messages(), config::params, state.rerolls,
                secondsLeft(offer));
        state.menu = menu;
        player.openInventory(menu.getInventory());
        Feedback.play(player, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.8f, 1.3f);
        return true;
    }

    /** Shows (again) the pick dialog of the player's current offer, with the cards and countdown as they are now. */
    private void showDialog(Player player, PlayerState state, Offer offer) {
        state.dialog = offer;
        OfferDialog dialog = OfferDialog.of(messages(), offer, config::params, state.rerolls, secondsLeft(offer),
                state.offers.size() - 1);
        UUID id = player.getUniqueId();
        long offerId = offer.id();
        int revision = offer.revision();
        game.offerDialogs().show(player, dialog, action -> dialogClicked(id, offerId, revision, action));
    }

    /**
     * A button of a pick dialog was clicked (the client closed that dialog). Only a dialog of the current offer with
     * its current cards counts; a stale one (after a reroll, a pick, a timeout) changes nothing, and the current
     * offer's dialog is shown again if the player was looking at one.
     */
    void dialogClicked(UUID id, long offerId, int revision, OfferDialog.Action action) {
        Player player = org.bukkit.Bukkit.getPlayer(id);
        PlayerState state = players.get(id);
        if (player == null || state == null || stopped || !session.isInPlay(player)) {
            return;
        }
        Offer offer = state.current();
        if (offer == null || offer.id() != offerId || offer.revision() != revision) {
            if (offer != null && state.dialog == offer && action.kind() != OfferDialog.Kind.LATER) {
                showDialog(player, state, offer);
            }
            return;
        }
        switch (action.kind()) {
            case PICK -> {
                if (action.card() >= 0 && action.card() < offer.cards().size()) {
                    pick(player, offer.cards().get(action.card()));
                }
            }
            case REROLL -> {
                if (!reroll(player) && state.current() == offer) {
                    showDialog(player, state, offer);
                }
            }
            case LATER -> {
                if (state.dialog == offer) {
                    state.dialog = null;
                    messages().send(player, "offer.closed", Messages.tag("seconds", secondsLeft(offer)));
                }
            }
        }
    }

    long secondsLeft(Offer offer) {
        return Math.max(0, (offer.deadline() - clock + 19) / 20);
    }

    /** The player clicked a card of their open menu. */
    public boolean pick(Player player, AugmentInfo card) {
        PlayerState state = players.get(player.getUniqueId());
        if (state == null || stopped) {
            return false;
        }
        Offer offer = state.current();
        if (offer == null || !offer.cards().contains(card) || !session.isInPlay(player)) {
            return false;
        }
        state.offers.pollFirst();
        closeMenu(state);
        grant(player, state, card, false);
        if (state.current() != null && !state.choosing()) { // an augment may have queued and opened one
            activate(player, state);
        }
        return true;
    }

    /** The player asked to redraw the shown cards. */
    public boolean reroll(Player player) {
        PlayerState state = players.get(player.getUniqueId());
        if (state == null || stopped) {
            return false;
        }
        Offer offer = state.current();
        if (offer == null) {
            return false;
        }
        if (state.rerolls <= 0) {
            messages().send(player, "offer.no-rerolls");
            Feedback.play(player, Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return false;
        }
        List<AugmentInfo> cards = draw(state, offer.tier(), offer.cards());
        if (cards.isEmpty()) {
            return false;
        }
        state.rerolls--;
        offer.cards(cards);
        if (state.menu != null && state.menu.offer() == offer) {
            state.menu.render(config::params, state.rerolls);
        }
        if (state.dialog == offer) {
            showDialog(player, state, offer);
        }
        messages().send(player, "offer.rerolled", Messages.tag("count", state.rerolls));
        Feedback.play(player, Sound.ITEM_BOOK_PAGE_TURN, 1f, 1.1f);
        return true;
    }

    /** The menu of the player was closed (by them or the server). */
    void menuClosed(Player player, OfferMenu menu) {
        PlayerState state = players.get(player.getUniqueId());
        if (state == null || state.menu != menu) {
            return;
        }
        state.menu = null;
        Offer offer = state.current();
        if (offer != null && !stopped && session.isInPlay(player)) {
            messages().send(player, "offer.closed", Messages.tag("seconds", secondsLeft(offer)));
        }
    }

    private void closeMenu(PlayerState state) {
        OfferMenu menu = state.menu;
        state.menu = null;
        if (state.dialog != null) {
            state.dialog = null;
            Player player = org.bukkit.Bukkit.getPlayer(state.id);
            if (player != null) {
                game.offerDialogs().close(player);
            }
        }
        if (menu == null) {
            return;
        }
        Player player = org.bukkit.Bukkit.getPlayer(state.id);
        if (player != null) {
            InventoryView view = player.getOpenInventory();
            if (view.getTopInventory().getHolder(false) == menu) {
                player.closeInventory();
            }
        }
    }

    private void tickOffers() {
        for (PlayerState state : players.values()) {
            Offer offer = state.current();
            if (offer == null || !offer.started()) {
                continue;
            }
            Player player = org.bukkit.Bukkit.getPlayer(state.id);
            if (player == null || !session.isInPlay(player)) {
                continue;
            }
            if (clock >= offer.deadline()) {
                AugmentInfo card = offer.cards().get(session.random().nextInt(offer.cards().size()));
                state.offers.pollFirst();
                closeMenu(state);
                grant(player, state, card, true);
                if (state.current() != null && !state.choosing()) {
                    activate(player, state);
                }
                continue;
            }
            if (clock % 20 == 0) {
                long seconds = secondsLeft(offer);
                if (state.menu != null) {
                    state.menu.updateClock(seconds);
                } else if (state.dialog == offer) {
                    if (OfferDialog.REFRESH_AT.contains(seconds)) {
                        showDialog(player, state, offer); // the countdown in the dialog is a fixed text
                    }
                } else {
                    messages().actionBar(player, "offer.reminder", Messages.tag("seconds", seconds));
                }
            }
        }
    }

    /**
     * Up to {@link PlayerState#cardCount} (three) cards for the player: enabled augments of {@code tier} they do not
     * own, not in {@code exclude}; topped up from the neighbouring tiers, then (if still short) with excluded ones.
     */
    List<AugmentInfo> draw(PlayerState state, AugmentTier tier, Collection<AugmentInfo> exclude) {
        return draw(state, tier, exclude, false);
    }

    /** {@link #draw}, only from the augments bots can own when {@code botsOnly}. */
    List<AugmentInfo> draw(PlayerState state, AugmentTier tier, Collection<AugmentInfo> exclude, boolean botsOnly) {
        int count = state.cardCount();
        List<AugmentInfo> picked = new ArrayList<>(count);
        for (boolean allowExcluded : new boolean[] {false, true}) {
            for (AugmentTier from : drawOrder(tier)) {
                if (picked.size() >= count) {
                    return picked;
                }
                List<AugmentInfo> pool = new ArrayList<>();
                for (AugmentInfo info : game.registry().ofTier(from)) {
                    if (config.isEnabled(info.id()) && !state.owns(info) && !picked.contains(info)
                            && (!botsOnly || info.forBots())
                            && (allowExcluded || !exclude.contains(info))) {
                        pool.add(info);
                    }
                }
                Collections.shuffle(pool, session.random());
                for (AugmentInfo info : pool) {
                    if (picked.size() >= count) {
                        break;
                    }
                    picked.add(info);
                }
            }
        }
        return picked;
    }

    private static List<AugmentTier> drawOrder(AugmentTier tier) {
        return switch (tier) {
            case SILVER -> List.of(AugmentTier.SILVER, AugmentTier.GOLD, AugmentTier.PRISM);
            case GOLD -> List.of(AugmentTier.GOLD, AugmentTier.SILVER, AugmentTier.PRISM);
            case PRISM -> List.of(AugmentTier.PRISM, AugmentTier.GOLD, AugmentTier.SILVER);
        };
    }

    /** Gives the player an augment (also used by tests and admin tools); false if they could not get it. */
    public boolean grant(Player player, AugmentInfo info) {
        PlayerState state = players.get(player.getUniqueId());
        if (state == null || state.owns(info)) {
            return false;
        }
        grant(player, state, info, false);
        return state.owns(info);
    }

    private void grant(Player player, PlayerState state, AugmentInfo info, boolean random) {
        if (state.owns(info) || stopped || !session.isAlive(player)) {
            return;
        }
        AugmentRegistry.Entry entry = game.registry().get(info.id());
        if (entry == null) {
            return;
        }
        AugmentSlot slot = new AugmentSlot(this, player.getUniqueId(), info, config.params(info), entry.factory());
        state.slots.add(slot);
        try {
            slot.augment().onGrant();
        } catch (RuntimeException e) {
            log("onGrant of " + info.id(), e);
        }
        messages().send(player, random ? "offer.timeout" : "offer.picked",
                Messages.tag("augment", OfferMenu.name(info)),
                Messages.tag("description", Component.text(config.params(info).render(info.description()))));
        Feedback.play(player, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, info.tier() == AugmentTier.PRISM ? 1.6f : 1.2f);
        hud.update();
    }

    /** Grants the player a random augment of {@code tier} (or a neighbouring one); null if none is left. */
    @Nullable AugmentInfo grantRandom(Player player, AugmentTier tier) {
        PlayerState state = players.get(player.getUniqueId());
        if (state == null || stopped || !session.isAlive(player)) {
            return null;
        }
        List<AugmentInfo> cards = draw(state, tier, Set.of());
        if (cards.isEmpty()) {
            return null;
        }
        AugmentInfo info = cards.getFirst(); // shuffled, from the wanted tier first
        grant(player, state, info, false);
        return state.owns(info) ? info : null;
    }

    /** Gives the player more rerolls (an augment's effect). */
    void addRerolls(UUID player, int count) {
        PlayerState state = players.get(player);
        if (state == null || count <= 0) {
            return;
        }
        state.rerolls += count;
        if (state.menu != null) {
            state.menu.render(config::params, state.rerolls);
        }
        Player owner = org.bukkit.Bukkit.getPlayer(player);
        if (state.dialog != null && state.dialog == state.current() && owner != null) {
            showDialog(owner, state, state.dialog);
        }
        hud.update();
    }

    /** Removes the augments and offers of a player who is out (they stay listed for the results). */
    void strip(UUID id) {
        PlayerState state = players.get(id);
        if (state == null) {
            return;
        }
        closeMenu(state);
        state.offers.clear();
        for (AugmentSlot slot : state.slots) {
            slot.remove();
        }
    }

    private void tickAugments() {
        for (PlayerState state : players.values()) {
            if (state.slots.isEmpty()) {
                continue;
            }
            if (!inPlay(state.id)) {
                continue;
            }
            for (AugmentSlot slot : List.copyOf(state.slots)) {
                if (!slot.isRemoved()) {
                    try {
                        slot.augment().onTick();
                    } catch (RuntimeException e) {
                        log("onTick of " + slot.info().id(), e);
                    }
                }
            }
        }
    }

    /** True for a participant in play: a player ({@link GameSession#isInPlay}) or a bot with a live body. */
    boolean inPlay(UUID id) {
        Player player = org.bukkit.Bukkit.getPlayer(id);
        if (player != null) {
            return session.isInPlay(player);
        }
        return bots != null && bots.body(id) != null;
    }

    /** The body of a participant in play: the player or a bot's body; null if none. */
    @Nullable LivingEntity body(UUID id) {
        Player player = org.bukkit.Bukkit.getPlayer(id);
        if (player != null) {
            return player;
        }
        return bots == null ? null : bots.body(id);
    }

    /** True for a bot of this match. */
    boolean isBotId(UUID id) {
        return bots != null && bots.isBot(id);
    }

    /** Runs {@code hook} on every augment of a player in play (errors are logged). */
    void forEachAugment(Player player, String hook, Consumer<Augment> action) {
        forEachAugment(player.getUniqueId(), hook, action);
    }

    /** Runs {@code hook} on every augment of a participant (player or bot) in play (errors are logged). */
    void forEachAugment(UUID owner, String hook, Consumer<Augment> action) {
        PlayerState state = players.get(owner);
        if (state == null || state.slots.isEmpty() || stopped || !inPlay(owner)) {
            return;
        }
        for (AugmentSlot slot : List.copyOf(state.slots)) {
            if (slot.isRemoved()) {
                continue;
            }
            try {
                action.accept(slot.augment());
            } catch (RuntimeException e) {
                log(hook + " of " + slot.info().id(), e);
            }
        }
    }

    /** Asks the augments of a player in play to save them from a deadly hit; true if one did. */
    boolean saveFromDeath(Player player, org.bukkit.event.entity.EntityDamageEvent event) {
        return saveFromDeath(player.getUniqueId(), event);
    }

    /** {@link #saveFromDeath(Player, org.bukkit.event.entity.EntityDamageEvent)} for any participant. */
    boolean saveFromDeath(UUID owner, org.bukkit.event.entity.EntityDamageEvent event) {
        PlayerState state = players.get(owner);
        if (state == null || stopped || !inPlay(owner)) {
            return false;
        }
        for (AugmentSlot slot : List.copyOf(state.slots)) {
            if (slot.isRemoved()) {
                continue;
            }
            try {
                if (slot.augment().onLethalDamage(event)) {
                    return true;
                }
            } catch (RuntimeException e) {
                log("onLethalDamage of " + slot.info().id(), e);
            }
        }
        return false;
    }

    /**
     * Augment damage of {@code owner} to an enemy (see
     * {@link io.github.sx2xoox.mcpl.game.augment.api.AugmentContext#damage}): {@code MAGIC}, credited to the owner,
     * ignoring (and keeping) the target's invulnerability frames.
     */
    void damage(UUID owner, LivingEntity target, double amount) {
        LivingEntity source = body(owner);
        if (stopped || !(amount > 0) || !Double.isFinite(amount) || source == null || !inPlay(owner)
                || !isEnemy(owner, target)) {
            return;
        }
        DamageSource damageSource = DamageSource.builder(DamageType.MAGIC)
                .withCausingEntity(source)
                .withDirectEntity(source)
                .build();
        int previousNoDamageTicks = target.getNoDamageTicks();
        double previousLastDamage = target.getLastDamage();
        target.setNoDamageTicks(0);
        augmentDamageDepth++;
        try {
            target.damage(amount, damageSource);
        } finally {
            augmentDamageDepth--;
            if (!target.isDead()) {
                target.setNoDamageTicks(previousNoDamageTicks);
                target.setLastDamage(previousLastDamage);
            }
        }
    }

    /** True while {@link #damage} is dealing augment damage. */
    boolean isAugmentDamage() {
        return augmentDamageDepth > 0;
    }

    // =================================================================== practice

    /** What {@link #advancePhase()} did. */
    public enum PhaseStep {
        /** Farming → fight (PvP flags on, the border delay starts). */
        FIGHT,
        /** The border starts shrinking now. */
        SHRINK,
        /** The border jumps to its final size. */
        FINAL,
        /** Nothing left to skip (or not a practice / not started). */
        NONE
    }

    /**
     * {@code /게임 증강 다음단계}: skips to the next step of the match flow (practice, and for admins every real
     * match). Skipping the farming of a real match moves the match clock to the fight start: the rounds due by then
     * open now (the fight-start round included), the bots get their fight gear and PvP comes on.
     */
    public PhaseStep advancePhase() {
        if (stopped || phase == MatchPhase.PREPARING || !session.isRunning()) {
            return PhaseStep.NONE;
        }
        if (phase == MatchPhase.FARMING) {
            if (!isPractice()) {
                time = Math.max(time, settings.farmingTicks());
            }
            beginFight();
            if (!isPractice()) {
                startDueRounds();
            }
            hud.update();
            return PhaseStep.FIGHT;
        }
        if (!borderShrinking) {
            fightStart = time - settings.borderDelayTicks();
            tickBorder();
            hud.update();
            return PhaseStep.SHRINK;
        }
        if (!borderFinal) {
            fightStart = time - settings.borderDelayTicks() - settings.borderShrinkTicks();
            wild.world().getWorldBorder().setSize(settings.borderFinalSize());
            tickBorder();
            hud.update();
            return PhaseStep.FINAL;
        }
        return PhaseStep.NONE;
    }

    /** Practice: practising players can (not) hurt each other. */
    public void setPracticePvp(boolean enabled) {
        practicePvp = enabled;
    }

    /** Practice: places dummies in front of {@code player}; the number standing, 0 if none could be placed. */
    public int placeDummies(Player player, int count) {
        if (dummies == null || stopped || phase == MatchPhase.PREPARING || !session.isInPlay(player)) {
            return 0;
        }
        return dummies.place(player, count);
    }

    /** Practice: the live dummies {@code player} placed. */
    public List<? extends LivingEntity> dummiesOf(UUID player) {
        return dummies == null ? List.of() : dummies.of(player);
    }

    /** The practice settings (null in a real match). */
    public @Nullable AugmentPracticeSettings practiceSettings() {
        return practiceSettings;
    }

    /** Takes one augment away from the player (practice tools); false if they do not own it. */
    public boolean revoke(Player player, AugmentInfo info) {
        PlayerState state = players.get(player.getUniqueId());
        if (state == null || stopped) {
            return false;
        }
        for (AugmentSlot slot : List.copyOf(state.slots)) {
            if (slot.info().id().equals(info.id())) {
                slot.remove();
                state.slots.remove(slot);
                hud.update();
                return true;
            }
        }
        return false;
    }

    /** Takes every augment away from the player (practice tools); the number removed. */
    public int revokeAll(Player player) {
        PlayerState state = players.get(player.getUniqueId());
        if (state == null || stopped) {
            return 0;
        }
        int count = state.slots.size();
        for (AugmentSlot slot : state.slots) {
            slot.remove();
        }
        state.slots.clear();
        hud.update();
        return count;
    }

    /** True if the player owns {@code info} right now. */
    public boolean owns(UUID player, AugmentInfo info) {
        PlayerState state = players.get(player);
        return state != null && state.owns(info);
    }

    // =================================================================== bonuses

    /**
     * The player achieved a bonus condition: unless turned off, already done by them or (first-come) by anyone, it
     * is announced and an extra offer of the condition's tier is queued.
     *
     * @return true if the bonus was awarded
     */
    public boolean award(Player player, BonusCondition condition) {
        PlayerState state = players.get(player.getUniqueId());
        if (state == null || stopped || phase == MatchPhase.PREPARING || !settings.bonusEnabled()
                || !config.isEnabled(condition) || !session.isInPlay(player) || state.bonuses.contains(condition)
                || (condition.firstCome() && claimed.contains(condition))) {
            return false;
        }
        state.bonuses.add(condition);
        if (condition.firstCome()) {
            claimed.add(condition);
        }
        AugmentTier tier = config.tier(condition);
        Params params = config.params(condition);
        announce(messages().prefixed(condition.firstCome() ? "bonus.announce-first" : "bonus.announce",
                Messages.tag("player", session.publicName(player.getUniqueId())),
                Messages.tag("condition", Component.text(condition.koreanName())),
                Messages.tag("description", Component.text(params.render(condition.description()))),
                Messages.tag("tier", OfferMenu.tierName(messages(), tier))));
        Feedback.play(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
        offer(player, tier, messages().get("source.bonus", Messages.tag("condition",
                Component.text(condition.koreanName()))));
        return true;
    }

    // =================================================================== rules

    /** See {@link io.github.sx2xoox.mcpl.game.augment.api.AugmentContext#isEnemy}. */
    @Override
    public boolean isEnemy(UUID owner, @Nullable Entity entity) {
        if (!(entity instanceof LivingEntity living) || living.isDead()) {
            return false;
        }
        if (entity instanceof Player target) {
            return !target.getUniqueId().equals(owner) && session.isParticipant(target) && session.isInPlay(target)
                    && !session.sameTeam(owner, target.getUniqueId());
        }
        if (dummies != null && dummies.isDummy(entity)) {
            return true;
        }
        if (bots != null) {
            UUID bot = bots.botOf(entity);
            if (bot != null) {
                // the body of an enemy bot in play; never its legs
                return bots.isBody(entity) && !bot.equals(owner) && bots.body(bot) != null
                        && !session.sameTeam(owner, bot);
            }
        }
        return entity instanceof Enemy;
    }

    /** True for a non-player combatant of this match (a practice dummy or a bot's body). */
    boolean isRival(@Nullable Entity entity) {
        return dummies != null && dummies.isDummy(entity) || bots != null && bots.isBody(entity);
    }

    /** See {@link io.github.sx2xoox.mcpl.game.augment.api.AugmentContext#rivals()}. */
    @Override
    public List<LivingEntity> rivals(UUID owner, org.bukkit.World world) {
        List<LivingEntity> out = new ArrayList<>();
        for (Player player : session.alivePlayers()) {
            if (player.getWorld().equals(world) && isEnemy(owner, player)) {
                out.add(player);
            }
        }
        if (dummies != null) {
            for (LivingEntity dummy : dummies.live()) {
                if (dummy.getWorld().equals(world)) {
                    out.add(dummy);
                }
            }
        }
        if (bots != null) {
            for (LivingEntity body : bots.liveBodies()) {
                if (body.getWorld().equals(world) && isEnemy(owner, body)) {
                    out.add(body);
                }
            }
        }
        return out;
    }

    // =================================================================== BotHost

    @Override
    public org.bukkit.World world() {
        return wild.world();
    }

    @Override
    public io.github.sx2xoox.mcpl.core.arena.Arena arena() {
        return wild.arena();
    }

    @Override
    public boolean fight() {
        return phase == MatchPhase.FIGHT;
    }

    @Override
    public double farmingProgress() {
        if (phase == MatchPhase.FIGHT) {
            return 1.0 + (double) fightTicks() / Math.max(1, settings.farmingTicks());
        }
        if (phase != MatchPhase.FARMING || settings.farmingTicks() <= 0) {
            return phase == MatchPhase.FARMING ? 1.0 : 0.0;
        }
        return Math.min(1.0, (double) time / settings.farmingTicks());
    }

    @Override
    public double pressure() {
        if (phase != MatchPhase.FIGHT) {
            return 0;
        }
        long span = settings.borderDelayTicks() + settings.borderShrinkTicks();
        return span <= 0 ? 1.0 : Math.min(1.0, (double) fightTicks() / span);
    }

    @Override
    public double borderCenterX() {
        return wild.arena().centerX();
    }

    @Override
    public double borderCenterZ() {
        return wild.arena().centerZ();
    }

    @Override
    public double borderHalfIn(long ticksAhead) {
        double start = settings.radius();
        if (phase != MatchPhase.FIGHT) {
            return start;
        }
        long into = fightTicks() + Math.round(ticksAhead * clockRate()) - settings.borderDelayTicks();
        if (into <= 0) {
            return start;
        }
        if (settings.borderShrinkTicks() <= 0 || into >= settings.borderShrinkTicks()) {
            return settings.borderFinalSize() / 2;
        }
        double progress = (double) into / settings.borderShrinkTicks();
        return start + (settings.borderFinalSize() / 2 - start) * progress;
    }

    @Override
    public double finalHalf() {
        return settings.borderFinalSize() / 2;
    }

    @Override
    public @Nullable UUID participantOf(@Nullable Entity entity) {
        if (entity instanceof Player player) {
            return session.isParticipant(player) ? player.getUniqueId() : null;
        }
        if (bots != null && bots.isBody(entity)) {
            return bots.botOf(entity);
        }
        return null;
    }

    @Override
    public @Nullable UUID attackerOf(org.bukkit.event.entity.EntityDamageEvent event) {
        Player player = session.combat().resolveAttacker(event);
        if (player != null) {
            return player.getUniqueId();
        }
        if (bots == null) {
            return null;
        }
        org.bukkit.damage.DamageSource source = event.getDamageSource();
        for (Entity entity : new Entity[] {source.getCausingEntity(), source.getDirectEntity(),
                event instanceof org.bukkit.event.entity.EntityDamageByEntityEvent byEntity ? byEntity.getDamager()
                        : null}) {
            UUID id = botBehind(entity);
            if (id != null) {
                return id;
            }
        }
        return null;
    }

    private @Nullable UUID botBehind(@Nullable Entity entity) {
        if (entity == null || bots == null) {
            return null;
        }
        UUID id = bots.botOf(entity);
        if (id != null) {
            return id;
        }
        UUID owner = session.combat().ownerOf(entity);
        if (owner != null && bots.isBot(owner)) {
            return owner;
        }
        if (entity instanceof org.bukkit.entity.Projectile projectile
                && projectile.getShooter() instanceof Entity shooter) {
            return bots.botOf(shooter);
        }
        return null;
    }

    @Override
    public void botDied(UUID bot, LivingEntity body, @Nullable UUID killer,
                        org.bukkit.event.entity.@Nullable EntityDamageEvent lastDamage) {
        if (stopped) {
            return;
        }
        UUID credited = killer != null && session.isParticipant(killer) && !session.sameTeam(killer, bot)
                ? killer : null;
        LivingEntity killerBody = credited == null ? null : body(credited);
        if (killerBody instanceof Player player && session.isInPlay(player)) {
            creditKill(player, body);
        } else if (credited != null && isBotId(credited)) {
            forEachAugment(credited, "onKill", augment -> augment.onKill(body));
        }
        PlayerState dead = players.get(bot);
        if (dead != null) {
            for (AugmentSlot slot : List.copyOf(dead.slots)) {
                if (!slot.isRemoved()) {
                    try {
                        slot.augment().onDeath(killerBody);
                    } catch (RuntimeException e) {
                        log("onDeath of " + slot.info().id() + " (bot)", e);
                    }
                }
            }
        }
        strip(bot);
        session.eliminateBot(bot, credited, io.github.sx2xoox.mcpl.core.game.DeathCauses.describe(session.messages(),
                lastDamage));
        if (chat != null) {
            chat.died(bot, credited);
            if (credited != null) {
                chat.killed(credited, bot);
            }
        }
        if (spectators != null) {
            spectators.botDied(bot, credited);
        }
        hud.update();
        checkWin();
    }

    private void checkWin() {
        if (!session.isRunning() || stopped) {
            return;
        }
        if (isPractice()) {
            if (session.aliveCount() == 0) {
                session.abort(messages().get("practice.everyone-left"));
            }
            return;
        }
        if (mode == MatchMode.BOTS && bots != null && !bots.all().isEmpty() && !session.aliveIds().isEmpty()
                && session.aliveIds().stream().allMatch(bots::isBot)) {
            // every human is out: the bots still standing win together (nobody is left to watch them finish it)
            finish(GameResult.builder().winners(session.aliveIds()).reason(messages().get("bots.won-reason")).build());
            return;
        }
        if (session.isTeamMode()) {
            List<MatchTeam> alive = session.aliveTeams();
            if (alive.size() == 1) {
                finish(GameResult.team(alive.getFirst()));
            } else if (alive.isEmpty()) {
                finish(GameResult.draw());
            }
            return;
        }
        Set<UUID> alive = new HashSet<>(session.aliveIds());
        if (alive.size() == 1) {
            finish(GameResult.winner(alive.iterator().next()));
        } else if (alive.isEmpty()) {
            finish(GameResult.draw());
        }
    }

    private void finish(GameResult result) {
        if (spectators != null) {
            spectators.announceWinner(result);
        }
        if (chat != null) {
            chat.finished(result);
        }
        broadcastSummary();
        session.finish(result);
    }

    /** Every participant's augments in chat, right before the results. */
    private void broadcastSummary() {
        List<Component> lines = new ArrayList<>();
        lines.add(messages().get("summary.header"));
        for (PlayerState state : players.values()) {
            List<Component> names = new ArrayList<>();
            for (AugmentInfo info : state.augments()) {
                names.add(OfferMenu.name(info));
            }
            Component list = names.isEmpty() ? messages().get("summary.none")
                    : Component.join(JoinConfiguration.separator(Component.text(", ", NamedTextColor.GRAY)), names);
            lines.add(messages().get("summary.row", Messages.tag("player", session.name(state.id)),
                    Messages.tag("augments", list)));
        }
        announce(Component.join(JoinConfiguration.newlines(), lines));
    }

    void log(String what, Throwable error) {
        game.hub().plugin().getLogger().log(Level.WARNING, "Error in augment match (" + what + ")", error);
    }

    /** The boss bar of the match (null before start). */
    @Nullable ManagedBossBar bar() {
        return bar;
    }
}
