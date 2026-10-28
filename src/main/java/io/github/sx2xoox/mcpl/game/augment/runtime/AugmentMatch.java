package io.github.sx2xoox.mcpl.game.augment.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import io.github.sx2xoox.mcpl.game.augment.config.AugmentConfig;
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
 * ({@link BonusTracker}); each player picks one of three cards in a chest menu ({@link OfferMenu}).
 */
public final class AugmentMatch implements GameInstance {

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

    public AugmentMatch(AugmentGame game, GameSession session, WildArena wild, AugmentSettings settings) {
        this(game, session, wild, settings, MatchMode.NORMAL, null);
    }

    public AugmentMatch(AugmentGame game, GameSession session, WildArena wild, AugmentSettings settings,
                        MatchMode mode, @Nullable AugmentPracticeSettings practiceSettings) {
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

    /** Ticks until the next timed round, or -1. */
    public long ticksToNextRound() {
        if (nextRound >= settings.rounds().size()) {
            return -1;
        }
        return Math.max(0, settings.rounds().get(nextRound).atTicks() - clock);
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
        if (!isPractice()) {
            if (phase == MatchPhase.FARMING && clock >= settings.farmingTicks()) {
                beginFight();
            }
            while (nextRound < settings.rounds().size() && clock >= settings.rounds().get(nextRound).atTicks()) {
                startRound(nextRound++);
            }
        } else if (dummies != null && clock % 10 == 0) {
            dummies.tick(clock);
        }
        if (phase == MatchPhase.FIGHT) {
            tickBorder();
        }
        tickOffers();
        tickAugments();
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
        if (isPractice()) {
            // practice: back on your feet near where you fell, augments kept
            return DeathOutcome.respawn(session.safeSpot(victim.getLocation()));
        }
        PlayerState dead = players.get(victim.getUniqueId());
        if (dead != null && !stopped) {
            for (AugmentSlot slot : List.copyOf(dead.slots)) {
                if (!slot.isRemoved()) {
                    try {
                        slot.augment().onDeath(killer);
                    } catch (RuntimeException e) {
                        log("onDeath of " + slot.info().id(), e);
                    }
                }
            }
        }
        return DeathOutcome.eliminate();
    }

    /** The participant {@code killer} was credited with killing the enemy combatant {@code victim}. */
    void creditKill(Player killer, LivingEntity victim) {
        if (stopped || !isEnemy(killer.getUniqueId(), victim) && !(victim instanceof Player)) {
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
        spread();
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
            Feedback.title(session.audience(), messages().get("phase.farming-title"),
                    messages().get("phase.farming-subtitle", Messages.tag("time", Format.duration(
                            settings.farmingTicks() * 50))), 5, 60, 15);
            announce(messages().prefixed("phase.farming", Messages.tag("time",
                    Format.duration(settings.farmingTicks() * 50)), Messages.tag("rounds", settings.rounds().size())));
        }
        session.playSound(Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.2f);
        hud.update();
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

    private void beginFight() {
        phase = MatchPhase.FIGHT;
        fightStart = clock;
        if (bar != null) {
            bar.color(BossBar.Color.RED);
        }
        Feedback.title(session.audience(), messages().get("phase.fight-title"), messages().get("phase.fight-subtitle"),
                5, 60, 15);
        announce(messages().prefixed("phase.fight", Messages.tag("time",
                Format.duration(settings.borderDelayTicks() * 50)), Messages.tag("size",
                Params.format(settings.borderFinalSize()))));
        session.playSound(Sound.ENTITY_ENDER_DRAGON_GROWL, 0.7f, 1f);
    }

    /** Fight ticks since PvP started. */
    long fightTicks() {
        return phase == MatchPhase.FIGHT ? clock - fightStart : 0;
    }

    private void tickBorder() {
        long fight = fightTicks();
        WorldBorder border = wild.world().getWorldBorder();
        if (!borderShrinking && fight >= settings.borderDelayTicks()) {
            borderShrinking = true;
            if (settings.borderShrinkTicks() <= 0) {
                border.setSize(settings.borderFinalSize());
            } else {
                border.changeSize(settings.borderFinalSize(), settings.borderShrinkTicks());
            }
            announce(messages().prefixed("phase.border-shrinking", Messages.tag("time",
                    Format.duration(settings.borderShrinkTicks() * 50)), Messages.tag("size",
                    Params.format(settings.borderFinalSize()))));
            session.playSound(Sound.BLOCK_BEACON_POWER_SELECT, 1f, 0.8f);
        }
        if (borderShrinking && !borderFinal && fight >= settings.borderDelayTicks() + settings.borderShrinkTicks()) {
            borderFinal = true;
            announce(messages().prefixed(settings.glowAtFinal() ? "phase.final-glow" : "phase.final"));
            session.playSound(Sound.ENTITY_WITHER_SPAWN, 0.5f, 1.4f);
        }
        if (borderFinal && settings.glowAtFinal() && clock % 20 == 0) {
            for (Player player : session.alivePlayers()) {
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
        OfferMenu menu = new OfferMenu(player.getUniqueId(), offer, messages(), config::params, state.rerolls,
                secondsLeft(offer));
        state.menu = menu;
        player.openInventory(menu.getInventory());
        Feedback.play(player, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.8f, 1.3f);
        return true;
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
        if (state.current() != null && state.menu == null) { // an augment may have queued and opened one
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
                if (state.current() != null && state.menu == null) {
                    activate(player, state);
                }
                continue;
            }
            if (clock % 20 == 0) {
                long seconds = secondsLeft(offer);
                if (state.menu != null) {
                    state.menu.updateClock(seconds);
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
        hud.update();
    }

    /** Removes the augments and offers of a player who is out (they stay listed for the results). */
    private void strip(UUID id) {
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
            Player player = org.bukkit.Bukkit.getPlayer(state.id);
            if (player == null || !session.isInPlay(player)) {
                continue;
            }
            for (AugmentSlot slot : state.slots) {
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

    /** Runs {@code hook} on every augment of a player in play (errors are logged). */
    void forEachAugment(Player player, String hook, Consumer<Augment> action) {
        PlayerState state = players.get(player.getUniqueId());
        if (state == null || state.slots.isEmpty() || stopped || !session.isInPlay(player)) {
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
        PlayerState state = players.get(player.getUniqueId());
        if (state == null || stopped || !session.isInPlay(player)) {
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
        Player source = org.bukkit.Bukkit.getPlayer(owner);
        if (stopped || !(amount > 0) || !Double.isFinite(amount) || source == null || !session.isInPlay(source)
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

    /** Practice ({@code /게임 증강 다음단계}): skips to the next step of the match flow. */
    public PhaseStep advancePhase() {
        if (!isPractice() || stopped || phase == MatchPhase.PREPARING) {
            return PhaseStep.NONE;
        }
        if (phase == MatchPhase.FARMING) {
            beginFight();
            hud.update();
            return PhaseStep.FIGHT;
        }
        if (!borderShrinking) {
            fightStart = clock - settings.borderDelayTicks();
            tickBorder();
            hud.update();
            return PhaseStep.SHRINK;
        }
        if (!borderFinal) {
            fightStart = clock - settings.borderDelayTicks() - settings.borderShrinkTicks();
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
    boolean isEnemy(UUID owner, @Nullable Entity entity) {
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
        return entity instanceof Enemy;
    }

    /** True for a non-player combatant of this match (a practice dummy). */
    boolean isRival(@Nullable Entity entity) {
        return dummies != null && dummies.isDummy(entity);
    }

    /** See {@link io.github.sx2xoox.mcpl.game.augment.api.AugmentContext#rivals()}. */
    List<LivingEntity> rivals(UUID owner, org.bukkit.World world) {
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
        return out;
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
