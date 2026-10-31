package io.github.sx2xoox.mcpl.game.superpower.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.github.sx2xoox.mcpl.core.game.DeathOutcome;
import io.github.sx2xoox.mcpl.core.game.GameInstance;
import io.github.sx2xoox.mcpl.core.game.GameResult;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.game.MatchTeam;
import io.github.sx2xoox.mcpl.core.ui.Feedback;
import io.github.sx2xoox.mcpl.core.ui.Format;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.game.superpower.PracticeSettings;
import io.github.sx2xoox.mcpl.game.superpower.SelectionMode;
import io.github.sx2xoox.mcpl.game.superpower.SuperpowerGame;
import io.github.sx2xoox.mcpl.game.superpower.SuperpowerSettings;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityParams;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityRegistry;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.game.superpower.config.AbilityConfig;
import io.github.sx2xoox.mcpl.game.superpower.loot.LootListener;
import io.github.sx2xoox.mcpl.game.superpower.loot.LootManager;
import io.github.sx2xoox.mcpl.game.superpower.menu.ChoiceMenu;
import io.github.sx2xoox.mcpl.game.superpower.ui.AbilityItems;
import io.github.sx2xoox.mcpl.game.superpower.ui.AbilityTexts;
import io.github.sx2xoox.mcpl.game.superpower.ui.RevealAnimation;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jspecify.annotations.Nullable;

/**
 * One match of 초능력전쟁 ({@link GameInstance}). Phases: {@link MatchPhase#INTRO} → abilities handed out
 * ({@link MatchPhase#SELECT} menus in CHOICE mode, slot-machine {@link MatchPhase#REVEAL} in RANDOM mode) →
 * invulnerable {@link MatchPhase#PREP} (rerolls) → {@link MatchPhase#FIGHT} (abilities bound and granted, shrinking
 * border, ultimate gauge) → last player or team standing, or the time limit.
 *
 * <p>All ability code runs through the {@link AbilityGuard}: an exception in any hook is logged with the ability id
 * and never escapes into the hub or affects other players.
 *
 * <p>Loot chests ({@link LootManager}, {@code games.superpower.loot}) appear when the fight starts and as supply drops
 * when the border starts shrinking; the superpower consumables found in them go through
 * {@link #chargeGaugeWithItem} and {@link #rechargeActiveWithItem}, which apply the match rules.
 *
 * <p><b>Practice</b> ({@code /게임 능력 연습}, {@link #isPractice()}): the same match machinery with the fight starting
 * right away (requested or random ability, no reveal, prep phase, border shrink, time limit or win check), training
 * dummies ({@link TrainingDummies}) in front of every player, respawning instead of elimination, ability switching,
 * a gauge refill and a cooldown toggle. It ends with {@code /게임 중지} or when every player left.
 */
public final class SuperpowerMatch implements GameInstance {

    /** Pause after the hub's START title before the reveal starts. */
    static final int INTRO_TICKS = 25;
    /** Time the final reveal title stays before the prep phase starts. */
    static final int REVEAL_HOLD_TICKS = 30;
    /** Length of the short re-reveal after a reroll or an admin assignment. */
    static final int SHORT_REVEAL_TICKS = 24;
    static final int CHOICE_CANDIDATES = 3;
    /** Delay before a CHOICE menu closed without a pick opens again. */
    static final int MENU_REOPEN_TICKS = 20;
    /** How often a CHOICE menu closed without a pick is reopened automatically ({@code /게임 능력 선택} always works). */
    static final int MENU_AUTO_REOPENS = 2;

    /** Outcome of {@link #reroll(Player)}. */
    public enum RerollResult { OK, NOT_PARTICIPANT, WRONG_PHASE, WRONG_MODE, FORCED, NONE_LEFT, NO_OPTIONS }

    /** Outcome of {@link #showTeamAbilities(Player)}. */
    public enum TeamAbilitiesResult { OK, NOT_TEAM_MODE, DISABLED, NO_TEAM, ALONE }

    /** Outcome of {@link #assign(Player, String)}. */
    public enum AssignResult { APPLIED, NOT_STARTED, NOT_PARTICIPANT, TOO_LATE }

    /** Outcome of the practice actions ({@link #switchAbility}, {@link #chargeUltimate}, {@link #placeDummies}). */
    public enum PracticeResult { OK, NOT_PRACTICE, NOT_STARTED, NOT_PARTICIPANT, UNKNOWN_ABILITY, FAILED }

    /** Outcome of a loot consumable ({@link #chargeGaugeWithItem}, {@link #rechargeActiveWithItem}). */
    public enum ItemUseResult {
        OK,
        /** Not during the fight of a running match. */
        NOT_FIGHTING,
        /** Not a participant in play with an ability. */
        NOT_IN_PLAY,
        SILENCED,
        /** The post-ultimate recharge delay ({@code ultimate.recharge-delay-seconds}) is running. */
        GAUGE_LOCKED,
        GAUGE_FULL,
        /** The active skill is not on cooldown. */
        COOLDOWN_READY
    }

    private final SuperpowerGame game;
    private final GameSession session;
    private final Messages messages;
    private final AbilityGuard guard;
    private final AbilityItems items;
    private final AbilityTexts texts;
    private final Map<UUID, Participant> participants = new LinkedHashMap<>();
    private final Map<UUID, RevealAnimation> reveals = new LinkedHashMap<>();
    private final Map<UUID, String> forced = new LinkedHashMap<>();
    private final boolean practice;
    /** Practice: where each player stands for its dummies and respawns. */
    private final Map<UUID, Location> homes = new HashMap<>();
    private @Nullable PracticeSettings practiceSettings;
    private @Nullable TrainingDummies dummies;
    private boolean cooldownsEnabled = true;
    private AbilityConfig.Snapshot abilities;
    private SuperpowerSettings settings;
    private @Nullable MatchContext ctx;
    private @Nullable BorderController border;
    /** Day to night and showers over a plugin map world, null elsewhere and in practice ({@link MatchSky}). */
    private @Nullable MatchSky sky;
    private @Nullable MatchHud hud;
    /** Loot chests and supply drops, null when loot is off for this match ({@code loot.enabled}). */
    private @Nullable LootManager loot;
    private MatchPhase phase = MatchPhase.INTRO;
    private long phaseTicks;
    private long fightTicks;
    private long revealHoldUntil = -1;
    private boolean started;
    private boolean stopped;
    /** While the border damages everyone outside at once, deaths do not decide the match one by one. */
    private boolean deferWinCheck;
    /** Last prep countdown number shown (3, 2, 1). */
    private int lastCountdown;

    public SuperpowerMatch(SuperpowerGame game, GameSession session) {
        this(game, session, false);
    }

    /**
     * @param practice true for a practice session ({@code /게임 능력 연습}); its settings come from
     *                 {@code games.superpower} ({@link SuperpowerSettings#forPractice()}) and {@code games.practice}
     */
    public SuperpowerMatch(SuperpowerGame game, GameSession session, boolean practice) {
        this.game = game;
        this.practice = practice;
        this.session = session;
        this.messages = game.messages();
        this.guard = new AbilityGuard(session.plugin().getLogger());
        this.items = game.items();
        this.texts = game.texts();
        this.abilities = game.abilities().snapshot();
        // Defaults until start() reads the live config (so config warnings are logged once per match).
        this.settings = SuperpowerSettings.read(new MemoryConfiguration(), logger());
    }

    // =================================================================== lifecycle

    @Override
    public void start() {
        started = true;
        ConfigurationSection superpowerConfig = practice
                ? game.hub().config().gameSection(SuperpowerGame.ID) : session.gameConfig();
        SuperpowerSettings configured = SuperpowerSettings.read(superpowerConfig, logger());
        settings = practice ? configured.forPractice() : configured;
        abilities = game.abilities().snapshot();
        if (!practice && abilities.enabledIds().isEmpty()) {
            session.abort(messages.get("match.no-abilities"));
            return;
        }
        ctx = new MatchContext(this);
        session.registerListener(new MatchListener(this));
        World world = session.arena().center().getWorld();
        if (world != null && world.getDifficulty() == Difficulty.PEACEFUL) {
            logger().warning("The arena world is on Peaceful difficulty: summoned mobs (summoner wolves and golem) "
                    + "cannot damage players there. Use Easy or harder for 초능력전쟁.");
        }
        border = new BorderController(session, settings);
        if (!practice) {
            sky = MatchSky.create(session, messages, settings, border);
            if (sky != null) {
                sky.begin();
            }
        }
        hud = new MatchHud(this);
        loot = LootManager.create(game, this, superpowerConfig);
        if (loot != null) {
            session.registerListener(new LootListener(loot, session));
        }
        if (practice) {
            startPractice();
            return;
        }
        session.spreadParticipants();
        for (Player player : session.alivePlayers()) {
            Participant participant = new Participant(player);
            participants.put(participant.id(), participant);
            giveKit(player);
            syncXp(participant, player);
        }
        forced.putAll(game.takeAssignments(participants.keySet()));
        phase = MatchPhase.INTRO;
        phaseTicks = 0;
        hud.update();
    }

    @Override
    public void tick() {
        if (stopped || ctx == null) {
            return;
        }
        phaseTicks++;
        switch (phase) {
            case INTRO -> {
                if (phaseTicks >= INTRO_TICKS) {
                    beginSelection();
                }
            }
            case SELECT -> tickSelection();
            case REVEAL -> {
                tickReveals();
                if (reveals.isEmpty()) {
                    if (revealHoldUntil < 0) {
                        revealHoldUntil = phaseTicks + REVEAL_HOLD_TICKS;
                    } else if (phaseTicks >= revealHoldUntil) {
                        beginPrep();
                    }
                }
            }
            case PREP -> {
                tickReveals();
                tickPrep();
            }
            case FIGHT -> tickFight();
        }
        if (stopped || !session.isRunning()) {
            return;
        }
        long tick = session.tick();
        if (tick % 10 == 0 && hud != null) {
            hud.update();
        }
        if (tick % 20 == 0) {
            maintainItems();
        }
        if (tick % 100 == 0) {
            ctx.prune();
        }
    }

    @Override
    public void onQuit(Player player) {
        Participant participant = participants.get(player.getUniqueId());
        if (participant != null) {
            closeMenu(participant, player);
            reveals.remove(participant.id());
            revoke(participant);
        }
        if (ctx != null) {
            ctx.leftPlay(player);
        }
        if (dummies != null) {
            dummies.remove(player.getUniqueId());
        }
    }

    @Override
    public void onEliminated(Player player, @Nullable Player killer) {
        Participant participant = participants.get(player.getUniqueId());
        if (participant != null) {
            closeMenu(participant, player);
            reveals.remove(participant.id());
            revoke(participant);
            participant.gauge.reset();
            if (player.isOnline()) {
                player.setLevel(0);
                player.setExp(0f);
            }
        }
        if (ctx != null) {
            ctx.leftPlay(player);
        }
        if (killer != null && phase == MatchPhase.FIGHT) {
            Participant credited = participants.get(killer.getUniqueId());
            Ability ability = credited == null ? null : credited.ability;
            if (ability != null && session.isInPlay(killer)) {
                ownCode(credited, () -> guard.run(credited.abilityId, "onKill", () -> ability.onKill(player)));
            }
        }
        if (!deferWinCheck) {
            checkWinner();
        }
    }

    @Override
    public @Nullable Component playerTag(UUID playerId) {
        Participant participant = participants.get(playerId);
        AbilityInfo info = participant == null ? null : info(participant.abilityId);
        if (info == null || (session.isRunning() && !settings.showAbilityInKillFeed())) {
            return null;
        }
        return AbilityTexts.name(info);
    }

    /** The name a participant's ability shows instead of the player's own (see {@link Ability#publicName}). */
    @Override
    public @Nullable Component nameOverride(UUID playerId) {
        Participant participant = participants.get(playerId);
        Ability ability = participant == null ? null : participant.ability;
        if (ability == null || participant.abilityId == null) {
            return null;
        }
        return guard.call(participant.abilityId, "publicName", ability::publicName);
    }

    @Override
    public void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        try {
            if (ctx != null) {
                // Before the revokes, so the prompts end as MATCH_END rather than OWNER_REVOKED (never throws).
                ctx.matchEnding();
            }
            for (Participant participant : participants.values()) {
                try {
                    ChoiceMenu menu = participant.menu;
                    participant.menu = null;
                    Player player = participant.player();
                    if (player != null && menu != null && isViewing(player, menu)) {
                        player.closeInventory();
                    }
                    revoke(participant);
                } catch (RuntimeException e) {
                    logger().log(Level.WARNING, "Could not clean up " + participant.name() + " after the match", e);
                }
            }
            reveals.clear();
            if (dummies != null) {
                dummies.dispose();
            }
            if (loot != null) {
                loot.dispose();
            }
        } finally {
            if (sky != null) {
                sky.restore(logger());
            }
            if (ctx != null) {
                ctx.dispose();
            }
            game.matchEnded(this);
        }
    }

    // =================================================================== phases

    private void beginSelection() {
        List<String> pool = abilities.enabledIds();
        List<UUID> players = new ArrayList<>();
        for (Participant participant : participants.values()) {
            if (session.isAlive(participant.id())) {
                players.add(participant.id());
            }
        }
        Map<UUID, String> valid = new LinkedHashMap<>();
        forced.forEach((id, ability) -> {
            if (pool.contains(ability)) {
                valid.put(id, ability);
            } else {
                logger().warning("Ignoring the assignment of disabled/unknown ability '" + ability + "'");
            }
        });
        forced.clear();
        phaseTicks = 0;
        if (settings.selectionMode() == SelectionMode.RANDOM) {
            Map<UUID, String> assignment = AbilityAssigner.assign(players, pool, valid, session.random());
            assignment.forEach((id, ability) -> {
                Participant participant = participants.get(id);
                participant.abilityId = ability;
                participant.forced = valid.containsKey(id);
                participant.rerollsLeft = participant.forced ? 0 : settings.rerolls();
                startReveal(participant, settings.revealTicks());
            });
            phase = MatchPhase.REVEAL;
            revealHoldUntil = -1;
            return;
        }
        List<UUID> choosers = new ArrayList<>();
        for (UUID id : players) {
            Participant participant = participants.get(id);
            String assigned = valid.get(id);
            if (assigned != null) {
                participant.abilityId = assigned;
                participant.forced = true;
                participant.chosen = true;
                startReveal(participant, 0);
            } else {
                choosers.add(id);
            }
        }
        phase = MatchPhase.SELECT;
        if (choosers.isEmpty()) {
            finishSelection();
            return;
        }
        Map<UUID, List<String>> candidates = AbilityAssigner.candidates(choosers, pool, CHOICE_CANDIDATES,
                valid.values(), session.random());
        candidates.forEach((id, list) -> {
            Participant participant = participants.get(id);
            participant.candidates = list;
            Player player = participant.player();
            if (player != null) {
                messages.send(player, "choice.started",
                        Messages.tag("seconds", (settings.choiceTicks() + 19) / 20));
                openMenu(participant, player);
            }
        });
    }

    private void tickSelection() {
        List<Participant> waiting = new ArrayList<>();
        for (Participant participant : participants.values()) {
            if (!participant.chosen && session.isAlive(participant.id())) {
                waiting.add(participant);
            }
        }
        if (waiting.isEmpty()) {
            finishSelection();
            return;
        }
        if (phaseTicks >= settings.choiceTicks()) {
            for (Participant participant : waiting) {
                choose(participant, randomCandidate(participant), true);
            }
            finishSelection();
        }
    }

    private String randomCandidate(Participant participant) {
        List<String> from = participant.candidates.isEmpty() ? abilities.enabledIds() : participant.candidates;
        return from.get(session.random().nextInt(from.size()));
    }

    /** CHOICE: {@code participant} picked {@code abilityId} (or it was picked for them). */
    void choose(Participant participant, String abilityId, boolean automatic) {
        if (participant.chosen || phase != MatchPhase.SELECT) {
            return;
        }
        participant.chosen = true;
        participant.abilityId = abilityId;
        participant.rerollsLeft = 0;
        Player player = participant.player();
        if (player != null) {
            closeMenu(participant, player);
            AbilityInfo info = info(abilityId);
            if (info != null) {
                messages.send(player, automatic ? "choice.auto-picked" : "choice.picked",
                        Messages.tag("ability", AbilityTexts.name(info)));
            }
        }
        startReveal(participant, 0);
    }

    private void finishSelection() {
        if (phase != MatchPhase.SELECT) {
            return;
        }
        phase = MatchPhase.REVEAL;
        phaseTicks = 0;
        revealHoldUntil = -1;
    }

    private void beginPrep() {
        phase = MatchPhase.PREP;
        phaseTicks = 0;
        lastCountdown = Integer.MAX_VALUE;
        if (settings.prepTicks() <= 0) {
            // No prep phase: nothing to announce (and no time to reroll).
            beginFight();
            return;
        }
        int seconds = (settings.prepTicks() + 19) / 20;
        for (Participant participant : participants.values()) {
            Player player = participant.player();
            if (player == null || !session.isInPlay(player)) {
                continue;
            }
            messages.send(player, "prep.started", Messages.tag("seconds", seconds));
            if (canReroll(participant)) {
                messages.send(player, "prep.reroll-hint", Messages.tag("count", participant.rerollsLeft));
            }
            Feedback.play(player, Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.2f);
        }
    }

    private void tickPrep() {
        long left = settings.prepTicks() - phaseTicks;
        if (left <= 0) {
            beginFight();
            return;
        }
        int seconds = (int) ((left + 19) / 20);
        if (seconds <= 3 && seconds < lastCountdown) {
            lastCountdown = seconds;
            for (Player player : session.alivePlayers()) {
                if (!reveals.containsKey(player.getUniqueId())) {
                    Feedback.title(player, messages.get("prep.countdown-title", Messages.tag("seconds", seconds)),
                            messages.get("prep.countdown-subtitle"), 0, 20, 5);
                }
                Feedback.play(player, Sound.BLOCK_NOTE_BLOCK_HAT, 1f, seconds == 1 ? 1.6f : 1.2f);
            }
        }
    }

    private void beginFight() {
        for (Map.Entry<UUID, RevealAnimation> entry : List.copyOf(reveals.entrySet())) {
            Participant participant = participants.get(entry.getKey());
            Player player = participant == null ? null : participant.player();
            if (player != null) {
                entry.getValue().complete(player, messages);
                revealed(participant, player);
            }
        }
        reveals.clear();
        phase = MatchPhase.FIGHT;
        phaseTicks = 0;
        fightTicks = 0;
        MatchContext context = context();
        List<Participant> bound = new ArrayList<>();
        for (Participant participant : participants.values()) {
            Player player = participant.player();
            String id = participant.abilityId;
            if (player == null || id == null || !session.isInPlay(player)) {
                continue;
            }
            if (!bind(participant, player, context)) {
                messages.send(player, "fight.ability-error");
                continue;
            }
            bound.add(participant);
        }
        for (Participant participant : bound) {
            Ability ability = participant.ability;
            if (ability == null) {
                continue;
            }
            participant.granted = true;
            ownCode(participant, () -> guard.run(participant.abilityId, "onGrant", ability::onGrant));
            if (stopped) {
                return;
            }
        }
        session.title(messages.get("fight.title"), messages.get("fight.subtitle"));
        session.playSound(Sound.EVENT_RAID_HORN, 0.7f, 1.0f);
        Component borderHint = border().shrinks() ? messages.get("fight.border-hint") : Component.empty();
        session.broadcast(messages.prefixed(session.isTeamMode() ? "fight.started-teams" : "fight.started",
                Messages.tag("border", borderHint)));
        // Every ability is final now (rerolls and assignments end with the prep phase).
        sendSpectatorSummary();
        sendTeamSummaries();
        if (loot != null) {
            loot.spreadChests();
        }
    }

    /** Creates and binds the participant's ability (not granted yet); false if that failed. */
    private boolean bind(Participant participant, Player player, MatchContext context) {
        String id = participant.abilityId;
        if (id == null) {
            return false;
        }
        Ability ability = guard.call(id, "bind", () -> createAbility(id, player));
        if (ability == null) {
            return false;
        }
        participant.ability = ability;
        context.openScope(participant.id());
        ensureAbilityItem(participant, player);
        return true;
    }

    /** Binds and grants the participant's ability right away (practice, ability switch); false if that failed. */
    private boolean bindAndGrant(Participant participant, Player player) {
        if (!bind(participant, player, context())) {
            return false;
        }
        Ability ability = participant.ability;
        if (ability == null) {
            return false;
        }
        participant.granted = true;
        ownCode(participant, () -> guard.run(participant.abilityId, "onGrant", ability::onGrant));
        return true;
    }

    private Ability createAbility(String id, Player player) {
        Ability ability = game.registry().create(id);
        ability.bind(player, context(), params(id));
        return ability;
    }

    private void tickFight() {
        fightTicks++;
        long now = fightTicks;
        for (Participant participant : List.copyOf(participants.values())) {
            Ability ability = participant.ability;
            if (ability == null) {
                continue;
            }
            Player player = participant.player();
            if (player == null || !session.isInPlay(player)) {
                continue;
            }
            ownCode(participant, () -> guard.run(participant.abilityId, "onTick", () -> ability.onTick(now)));
            if (stopped) {
                return;
            }
        }
        context().tickRuntime(now);
        if (stopped) {
            return;
        }
        BorderController zone = border();
        if (zone.tick(now)) {
            announceBorderShrink();
            if (loot != null) {
                loot.dropSupplies(zone.finalSize());
            }
        } else if (zone.state(now) == BorderController.State.WAITING && zone.ticksUntilShrink(now) == 200) {
            session.broadcast(messages.prefixed("border.warning", Messages.tag("seconds", 10)));
        }
        if (sky != null) {
            sky.tick(now);
        }
        if (now % 5 == 0) {
            refreshSilences();
        }
        if (dummies != null && now % TrainingDummies.MAINTAIN_PERIOD == 0) {
            dummies.tick(now);
        }
        if (loot != null) {
            loot.tick();
        }
        if (now % 20 == 0) {
            secondTick();
            if (stopped) {
                return;
            }
        }
        long limit = settings.timeLimitTicks();
        if (limit > 0 && now >= limit) {
            finishByTimeLimit();
        }
    }

    private void secondTick() {
        double perSecond = settings.gaugePerSecond();
        for (Participant participant : participants.values()) {
            Player player = participant.player();
            if (participant.ability != null && player != null && session.isInPlay(player)) {
                addEarnedGauge(participant, perSecond);
            }
        }
        double damage = settings.borderDamagePerSecond();
        // Everyone outside is hit in the same moment: if the last players all die to it, that is a draw, not a win
        // for whoever happens to be damaged last.
        deferWinCheck = true;
        try {
            for (Player player : session.alivePlayers()) {
                if (!session.isInPlay(player) || !border().isOutside(player.getLocation())) {
                    continue;
                }
                player.sendActionBar(messages.get("border.outside"));
                Feedback.play(player, Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.5f);
                context().borderDamage(player, damage);
                if (stopped) {
                    return;
                }
            }
        } finally {
            deferWinCheck = false;
        }
        checkWinner();
    }

    private void announceBorderShrink() {
        BorderController zone = border();
        session.broadcast(messages.prefixed("border.shrinking",
                Messages.tag("seconds", (zone.shrinkTicks() + 19) / 20),
                Messages.tag("size", Format.number(zone.finalSize()))));
        session.title(messages.get("border.title"), messages.get("border.subtitle"));
        session.playSound(Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.6f, 1.2f);
    }

    // =================================================================== ending

    private void checkWinner() {
        if (stopped || !session.isRunning()) {
            return;
        }
        if (practice) {
            // No winner in practice: it only ends with /게임 중지 or when every player left.
            if (session.aliveIds().isEmpty()) {
                session.abort(messages.get("practice.everyone-left"));
            }
            return;
        }
        if (session.isTeamMode()) {
            List<MatchTeam> alive = session.aliveTeams();
            if (alive.size() == 1) {
                session.finish(GameResult.team(alive.getFirst()));
            } else if (alive.isEmpty()) {
                session.finish(GameResult.draw());
            }
            return;
        }
        Set<UUID> alive = session.aliveIds();
        if (alive.size() == 1) {
            session.finish(GameResult.winner(alive.iterator().next()));
        } else if (alive.isEmpty()) {
            session.finish(GameResult.draw());
        }
    }

    /** Time is up: most kills wins, then most remaining health; a full tie is a draw. */
    private void finishByTimeLimit() {
        Component reason = messages.get("result.time-limit");
        if (session.isTeamMode()) {
            List<MatchTeam> alive = session.aliveTeams();
            Comparator<MatchTeam> order = Comparator.<MatchTeam>comparingInt(this::teamKills)
                    .thenComparingDouble(this::teamHealth).reversed();
            List<MatchTeam> ranked = new ArrayList<>(alive);
            ranked.sort(order);
            if (ranked.isEmpty() || (ranked.size() > 1 && order.compare(ranked.get(0), ranked.get(1)) == 0)) {
                session.finish(GameResult.builder().reason(reason).build());
            } else {
                session.finish(GameResult.builder().team(ranked.getFirst()).reason(reason).build());
            }
            return;
        }
        Comparator<UUID> order = Comparator.<UUID>comparingInt(id -> session.stats().of(id).kills())
                .thenComparingDouble(this::remainingHealth).reversed();
        List<UUID> ranked = new ArrayList<>(session.aliveIds());
        ranked.sort(order);
        if (ranked.isEmpty() || (ranked.size() > 1 && order.compare(ranked.get(0), ranked.get(1)) == 0)) {
            session.finish(GameResult.builder().placement(ranked).reason(reason).build());
        } else {
            session.finish(GameResult.builder().winner(ranked.getFirst()).placement(ranked).reason(reason).build());
        }
    }

    private int teamKills(MatchTeam team) {
        int kills = 0;
        for (UUID member : team.members()) {
            kills += session.stats().of(member).kills();
        }
        return kills;
    }

    private double teamHealth(MatchTeam team) {
        double health = 0;
        for (UUID member : team.members()) {
            if (session.isAlive(member)) {
                health += remainingHealth(member);
            }
        }
        return health;
    }

    private double remainingHealth(UUID id) {
        Participant participant = participants.get(id);
        Player player = participant == null ? null : participant.player();
        return player == null || player.isDead() ? 0 : player.getHealth() + player.getAbsorptionAmount();
    }

    // =================================================================== reveal

    private void startReveal(Participant participant, int ticks) {
        AbilityInfo info = info(participant.abilityId);
        if (info == null) {
            return;
        }
        Player player = participant.player();
        if (player != null && ticks > 0) {
            player.getInventory().setItem(AbilityItems.SLOT, null);
        }
        List<AbilityInfo> reel = new ArrayList<>();
        for (String id : abilities.enabledIds()) {
            AbilityInfo other = info(id);
            if (other != null) {
                reel.add(other);
            }
        }
        reveals.put(participant.id(), ticks <= 0 ? RevealAnimation.instant(info)
                : RevealAnimation.spin(info, reel, ticks, session.random()));
    }

    private void tickReveals() {
        Iterator<Map.Entry<UUID, RevealAnimation>> iterator = reveals.entrySet().iterator();
        List<Participant> finished = new ArrayList<>();
        while (iterator.hasNext()) {
            Map.Entry<UUID, RevealAnimation> entry = iterator.next();
            Participant participant = participants.get(entry.getKey());
            Player player = participant == null ? null : participant.player();
            if (player == null || !session.isAlive(participant.id())) {
                iterator.remove();
                continue;
            }
            if (entry.getValue().tick(player, messages)) {
                iterator.remove();
                finished.add(participant);
            }
        }
        for (Participant participant : finished) {
            Player player = participant.player();
            if (player != null) {
                revealed(participant, player);
            }
        }
    }

    /** The reveal of {@code participant} landed: hand out the item and the chat summary. */
    private void revealed(Participant participant, Player player) {
        AbilityInfo info = info(participant.abilityId);
        if (info == null) {
            return;
        }
        ensureAbilityItem(participant, player);
        sendSummary(player, info, params(info));
    }

    boolean isRevealing(Participant participant) {
        return reveals.containsKey(participant.id());
    }

    private void sendSummary(Player player, AbilityInfo info, AbilityParams params) {
        player.sendMessage(messages.prefixed("summary.header",
                Messages.tag("ability", texts.nameWithCard(info, params).decorate(TextDecoration.BOLD)),
                Messages.tag("stars", AbilityTexts.stars(info.difficulty()))));
        player.sendMessage(messages.get("summary.passive", Messages.tag("skill", texts.skill(info.passive(), params))));
        player.sendMessage(messages.get("summary.active", Messages.tag("skill", texts.skill(info.active(), params)),
                Messages.tag("cooldown", AbilityInfo.formatNumber(params.cooldownSeconds()))));
        player.sendMessage(messages.get("summary.ultimate",
                Messages.tag("skill", texts.skill(info.ultimate(), params))));
        player.sendMessage(messages.get("summary.hint"));
    }

    /** Spectators (watchers and casters) see everyone's ability; players only learn their own. */
    private void sendSpectatorSummary() {
        List<Player> spectators = new ArrayList<>();
        for (Player viewer : session.viewers()) {
            if (!session.isInPlay(viewer)) {
                spectators.add(viewer);
            }
        }
        if (spectators.isEmpty()) {
            return;
        }
        List<Component> lines = new ArrayList<>();
        lines.add(messages.prefixed("summary.spectator-header"));
        for (Participant participant : participants.values()) {
            AbilityInfo info = info(participant.abilityId);
            if (info != null && session.isAlive(participant.id())) {
                lines.add(messages.get("summary.spectator-line", Messages.tag("player", session.name(participant.id())),
                        Messages.tag("ability", texts.nameWithCard(info, params(info)))));
            }
        }
        for (Player spectator : spectators) {
            lines.forEach(spectator::sendMessage);
        }
    }

    // =================================================================== team abilities

    /** True if teammates see each other's abilities in this match (team mode and enabled in the config). */
    boolean showsTeamAbilities() {
        return session.isTeamMode() && settings.showTeamAbilities();
    }

    /** The other participants on {@code playerId}'s team: alive ones first, then eliminated ones, in team order. */
    List<Participant> teammates(UUID playerId) {
        MatchTeam team = session.teamOf(playerId);
        if (team == null) {
            return List.of();
        }
        List<Participant> alive = new ArrayList<>();
        List<Participant> out = new ArrayList<>();
        for (UUID member : team.members()) {
            Participant mate = participants.get(member);
            if (mate != null && !member.equals(playerId)) {
                (session.isAlive(member) ? alive : out).add(mate);
            }
        }
        alive.addAll(out);
        return alive;
    }

    /** The teammate's ability once their teammates may know it: assigned and revealed to its owner. */
    @Nullable AbilityInfo knownAbility(Participant teammate) {
        return teammate.abilityId == null || isRevealing(teammate) ? null : info(teammate.abilityId);
    }

    /** Chat lines with the abilities of {@code playerId}'s teammates (hover: the ability card). */
    private List<Component> teamSummaryLines(UUID playerId) {
        List<Component> lines = new ArrayList<>();
        for (Participant mate : teammates(playerId)) {
            AbilityInfo info = knownAbility(mate);
            if (session.isAlive(mate.id())) {
                lines.add(messages.get("summary.team-line", Messages.tag("player", session.name(mate.id())),
                        Messages.tag("ability", info == null ? messages.get("summary.team-unknown")
                                : texts.nameWithCard(info, params(info)))));
            } else {
                lines.add(messages.get("summary.team-line-out", Messages.tag("player", session.plainName(mate.id())),
                        Messages.tag("ability", info == null ? "" : info.name())));
            }
        }
        return lines;
    }

    private void sendTeamSummary(Player viewer, MatchTeam team, List<Component> lines) {
        viewer.sendMessage(messages.prefixed("summary.team-header", Messages.tag("team", team.displayName())));
        lines.forEach(viewer::sendMessage);
    }

    /** When the fight starts every player in play learns the (now final) abilities of their teammates. */
    private void sendTeamSummaries() {
        if (!showsTeamAbilities()) {
            return;
        }
        for (Participant participant : participants.values()) {
            Player player = participant.player();
            MatchTeam team = session.teamOf(participant.id());
            if (player == null || team == null || !session.isInPlay(player)) {
                continue;
            }
            List<Component> lines = teamSummaryLines(participant.id());
            if (!lines.isEmpty()) {
                sendTeamSummary(player, team, lines);
            }
        }
    }

    /** {@code /게임 능력 팀}: sends the abilities of the viewer's teammates. */
    public TeamAbilitiesResult showTeamAbilities(Player viewer) {
        if (!session.isTeamMode()) {
            return TeamAbilitiesResult.NOT_TEAM_MODE;
        }
        if (!settings.showTeamAbilities()) {
            return TeamAbilitiesResult.DISABLED;
        }
        MatchTeam team = session.teamOf(viewer.getUniqueId());
        if (team == null) {
            return TeamAbilitiesResult.NO_TEAM;
        }
        List<Component> lines = teamSummaryLines(viewer.getUniqueId());
        if (lines.isEmpty()) {
            return TeamAbilitiesResult.ALONE;
        }
        sendTeamSummary(viewer, team, lines);
        return TeamAbilitiesResult.OK;
    }

    // =================================================================== menus

    private void openMenu(Participant participant, Player player) {
        List<AbilityInfo> infos = new ArrayList<>();
        for (String id : participant.candidates) {
            AbilityInfo info = info(id);
            if (info != null) {
                infos.add(info);
            }
        }
        ChoiceMenu menu = new ChoiceMenu(participant.id(), infos, this::params, texts,
                messages.get("choice.title"), messages.get("choice.pick-hint"));
        participant.menu = menu;
        player.openInventory(menu.getInventory());
        Feedback.play(player, Sound.BLOCK_ENDER_CHEST_OPEN, 0.6f, 1.3f);
    }

    /** Closes the menu one tick later (never close an inventory from inside its own click event). */
    private void closeMenu(Participant participant, Player player) {
        ChoiceMenu menu = participant.menu;
        participant.menu = null;
        if (menu != null && isViewing(player, menu)) {
            session.runLater(1, () -> {
                if (isViewing(player, menu)) {
                    player.closeInventory();
                }
            });
        }
    }

    private static boolean isViewing(Player player, ChoiceMenu menu) {
        return player.isOnline() && player.getOpenInventory().getTopInventory() == menu.getInventory();
    }

    /** The choice menu currently offered to {@code player} (null once picked, closed or never opened). */
    @Nullable ChoiceMenu menuOf(Player player) {
        Participant participant = participants.get(player.getUniqueId());
        return participant == null ? null : participant.menu;
    }

    void onMenuClick(Player player, ChoiceMenu menu, int rawSlot) {
        Participant participant = participants.get(player.getUniqueId());
        if (participant == null || participant.menu != menu) {
            return;
        }
        String picked = menu.candidateAt(rawSlot);
        if (picked != null) {
            Feedback.play(player, Sound.UI_BUTTON_CLICK, 0.8f, 1.2f);
            choose(participant, picked, false);
        }
    }

    /**
     * The menu was closed without a pick. Nothing is decided here (only the timeout picks at random): the menu opens
     * again a moment later, a limited number of times for closes by the player ({@code /게임 능력 선택} reopens it).
     */
    void onMenuClosed(Player player, ChoiceMenu menu, InventoryCloseEvent.Reason reason) {
        Participant participant = participants.get(player.getUniqueId());
        if (participant == null || participant.menu != menu) {
            return;
        }
        participant.menu = null;
        if (participant.chosen || phase != MatchPhase.SELECT || !session.isAlive(participant.id())) {
            return;
        }
        if (reason == InventoryCloseEvent.Reason.PLAYER && ++participant.menuCloses > MENU_AUTO_REOPENS) {
            messages.send(player, "choice.closed");
            return;
        }
        player.sendActionBar(messages.get("choice.reopening"));
        session.runLater(MENU_REOPEN_TICKS, () -> reopenMenu(participant));
    }

    /**
     * Opens the CHOICE menu again for a participant who has not picked yet ({@code /게임 능력 선택}).
     *
     * @return false if there is nothing to pick right now
     */
    public boolean reopenMenu(Player player) {
        Participant participant = participants.get(player.getUniqueId());
        return participant != null && reopenMenu(participant);
    }

    private boolean reopenMenu(Participant participant) {
        Player player = participant.player();
        if (stopped || player == null || participant.chosen || phase != MatchPhase.SELECT
                || participant.candidates.isEmpty() || !session.isInPlay(player)) {
            return false;
        }
        if (participant.menu != null && isViewing(player, participant.menu)) {
            return true;
        }
        // Never take the screen from another open container (another plugin's menu, a chest).
        if (player.getOpenInventory().getType() != InventoryType.CRAFTING) {
            return false;
        }
        openMenu(participant, player);
        return true;
    }

    // =================================================================== input

    /** Right-click with the ability item. */
    void handleUse(Player player) {
        Participant participant = participants.get(player.getUniqueId());
        if (participant == null || !session.isInPlay(player)) {
            return;
        }
        long tick = session.tick();
        if (participant.lastUseTick == tick) {
            return;
        }
        participant.lastUseTick = tick;
        switch (phase) {
            case INTRO, SELECT, REVEAL -> deny(player, messages.get("input.not-yet"));
            case PREP -> {
                if (player.isSneaking() && settings.selectionMode() == SelectionMode.RANDOM) {
                    RerollResult result = reroll(player);
                    if (result == RerollResult.OK) {
                        reportReroll(player, result);
                    } else {
                        // Holding right-click repeats the use every few ticks: the action bar, not chat.
                        deny(player, messages.get(rerollMessageKey(result)));
                    }
                } else {
                    deny(player, messages.get(canReroll(participant) ? "input.prep-reroll" : "input.prep",
                            Messages.tag("seconds", Math.max(1, (settings.prepTicks() - phaseTicks + 19) / 20))));
                }
            }
            case FIGHT -> useActive(player);
        }
    }

    /** F (swap hands). */
    void handleSwap(Player player) {
        Participant participant = participants.get(player.getUniqueId());
        if (participant == null || !session.isInPlay(player)) {
            return;
        }
        if (phase != MatchPhase.FIGHT) {
            deny(player, messages.get(phase == MatchPhase.PREP ? "input.prep-ultimate" : "input.not-yet",
                    Messages.tag("seconds", Math.max(1, (settings.prepTicks() - phaseTicks + 19) / 20))));
            return;
        }
        Ability ability = participant.ability;
        if (ability != null && Boolean.TRUE.equals(ownCode(participant,
                () -> guard.call(participant.abilityId, "onUltimateKey", ability::onUltimateKey)))) {
            return; // the ability took the key (e.g. to end its running ultimate)
        }
        useUltimate(player);
    }

    /**
     * Uses the active skill of {@code player} like a right-click would (cooldown, silence, feedback).
     *
     * @return the ability's result, or a failure if it could not be used
     */
    public ActivationResult useActive(Player player) {
        Participant participant = participants.get(player.getUniqueId());
        Ability ability = participant == null ? null : participant.ability;
        if (ability == null || phase != MatchPhase.FIGHT || !session.isInPlay(player)) {
            return ActivationResult.fail("not in the fight");
        }
        long now = fightTicks;
        if (participant.isSilenced(now)) {
            deny(player, messages.get("input.silenced",
                    Messages.tag("seconds", Format.number((participant.silencedUntil - now) / 20.0))));
            return ActivationResult.fail("silenced");
        }
        if (!participant.cooldown.isReady(now)) {
            deny(player, messages.get("input.cooldown",
                    Messages.tag("seconds", Format.number(participant.cooldown.remainingSeconds(now)))));
            return ActivationResult.fail("cooldown");
        }
        String id = participant.abilityId;
        ActivationResult result = ownCode(participant, () -> guard.call(id, "onActive", ability::onActive));
        if (result == null) {
            deny(player, messages.get("input.error"));
            return ActivationResult.fail("error");
        }
        if (!result.succeeded()) {
            deny(player, reason(result));
            return result;
        }
        if (participant.ability == ability && session.isInPlay(player)) {
            if (cooldownsEnabled) {
                Double multiplier = guard.call(id, "cooldownMultiplier", ability::cooldownMultiplier);
                participant.cooldown.start(fightTicks, params(id).cooldownSeconds(),
                        multiplier == null ? 1.0 : multiplier);
                showCooldown(participant, player);
            }
            // A quiet cue only: abilities show their own action-bar feedback, which must not be overwritten.
            Feedback.play(player, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.4f, 1.6f);
        }
        return result;
    }

    /**
     * Uses the ultimate of {@code player} like the F key would (gauge, silence, announcement).
     *
     * @return the ability's result, or a failure if it could not be used
     */
    public ActivationResult useUltimate(Player player) {
        Participant participant = participants.get(player.getUniqueId());
        Ability ability = participant == null ? null : participant.ability;
        if (ability == null || phase != MatchPhase.FIGHT || !session.isInPlay(player)) {
            return ActivationResult.fail("not in the fight");
        }
        long now = fightTicks;
        if (participant.isSilenced(now)) {
            deny(player, messages.get("input.silenced",
                    Messages.tag("seconds", Format.number((participant.silencedUntil - now) / 20.0))));
            return ActivationResult.fail("silenced");
        }
        if (!participant.gauge.isFull()) {
            deny(player, messages.get("input.gauge", Messages.tag("percent", participant.gauge.level())));
            return ActivationResult.fail("gauge");
        }
        String id = participant.abilityId;
        // Everything the ultimate does, now or later, counts as the ultimate: it never refills the caster's gauge.
        ActivationResult result = context().asUltimate(participant.id(),
                () -> ownCode(participant, () -> guard.call(id, "onUltimate", ability::onUltimate)));
        if (result == null) {
            deny(player, messages.get("input.error"));
            return ActivationResult.fail("error");
        }
        if (!result.succeeded()) {
            deny(player, reason(result));
            return result;
        }
        if (stopped || participant.ability != ability || !session.isInPlay(player)) {
            // The ultimate ended the match (or killed its caster): no announcement over the results.
            return result;
        }
        participant.gauge.reset();
        participant.gaugeLockedUntil = fightTicks + settings.ultimateLockTicks();
        syncXp(participant, player);
        AbilityInfo info = info(id);
        if (info != null) {
            announceUltimate(player, info);
        }
        return result;
    }

    private void announceUltimate(Player player, AbilityInfo info) {
        Component ultimate = Component.text(info.ultimate().name(), info.color())
                .decorate(TextDecoration.BOLD);
        if (!settings.announceUltimates()) {
            player.sendActionBar(messages.get("ultimate.used-quiet", Messages.tag("ultimate", ultimate)));
            return;
        }
        Component name = session.publicName(player.getUniqueId());
        session.broadcast(messages.prefixed("ultimate.announce", Messages.tag("player", name),
                Messages.tag("ability", AbilityTexts.name(info)), Messages.tag("ultimate", ultimate)));
        Feedback.title(player, messages.get("ultimate.title", Messages.tag("ultimate", ultimate)),
                messages.get("ultimate.subtitle", Messages.tag("ability", AbilityTexts.name(info))), 0, 30, 10);
        Location location = player.getLocation();
        player.getWorld().playSound(location, Sound.ENTITY_ILLUSIONER_CAST_SPELL, SoundCategory.PLAYERS, 1.6f, 0.9f);
        player.getWorld().playSound(location, Sound.BLOCK_BEACON_POWER_SELECT, SoundCategory.PLAYERS, 1.2f, 1.4f);
    }

    private Component reason(ActivationResult result) {
        String reason = result.reason();
        if (reason == null || reason.isBlank()) {
            return messages.get("input.failed");
        }
        try {
            return messages.get("input.reason", Messages.parsed("reason", reason));
        } catch (RuntimeException invalidMiniMessage) {
            return messages.get("input.reason", Messages.tag("reason", reason));
        }
    }

    /** Action-bar feedback with a soft sound (the sound at most every half second, e.g. while right-click is held). */
    private void deny(Player player, Component message) {
        player.sendActionBar(message);
        Participant participant = participants.get(player.getUniqueId());
        long now = session.tick();
        if (participant == null || now - participant.lastDenySoundTick >= 10) {
            if (participant != null) {
                participant.lastDenySoundTick = now;
            }
            Feedback.play(player, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.7f);
        }
    }

    // =================================================================== rerolls & assignments

    private boolean canReroll(Participant participant) {
        return settings.selectionMode() == SelectionMode.RANDOM && participant.rerollsLeft > 0 && !participant.forced;
    }

    /** Rerolls the ability of {@code player} (RANDOM mode, prep phase, rerolls left). */
    public RerollResult reroll(Player player) {
        Participant participant = participants.get(player.getUniqueId());
        if (participant == null || !session.isInPlay(player) || participant.abilityId == null) {
            return RerollResult.NOT_PARTICIPANT;
        }
        if (settings.selectionMode() != SelectionMode.RANDOM) {
            return RerollResult.WRONG_MODE;
        }
        if (phase != MatchPhase.PREP) {
            return RerollResult.WRONG_PHASE;
        }
        if (participant.forced) {
            return RerollResult.FORCED;
        }
        if (participant.rerollsLeft <= 0) {
            return RerollResult.NONE_LEFT;
        }
        List<String> held = new ArrayList<>();
        for (Participant other : participants.values()) {
            if (other != participant && other.abilityId != null && session.isAlive(other.id())) {
                held.add(other.abilityId);
            }
        }
        String next = AbilityAssigner.reroll(participant.abilityId, abilities.enabledIds(), held, session.random());
        if (next == null) {
            return RerollResult.NO_OPTIONS;
        }
        participant.rerollsLeft--;
        participant.abilityId = next;
        startReveal(participant, SHORT_REVEAL_TICKS);
        return RerollResult.OK;
    }

    /** Sends the Korean feedback for a reroll attempt (chat, for the command). */
    public void reportReroll(Player player, RerollResult result) {
        if (result == RerollResult.OK) {
            Participant participant = participants.get(player.getUniqueId());
            messages.send(player, "reroll.done",
                    Messages.tag("count", participant == null ? 0 : participant.rerollsLeft));
            Feedback.play(player, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1.2f);
            return;
        }
        messages.send(player, rerollMessageKey(result));
        Feedback.play(player, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.7f);
    }

    private static String rerollMessageKey(RerollResult result) {
        return switch (result) {
            case OK -> "reroll.done";
            case NOT_PARTICIPANT -> "reroll.not-participant";
            case WRONG_MODE -> "reroll.wrong-mode";
            case WRONG_PHASE -> "reroll.wrong-phase";
            case FORCED -> "reroll.forced";
            case NONE_LEFT -> "reroll.none-left";
            case NO_OPTIONS -> "reroll.no-options";
        };
    }

    /**
     * Admin assignment for a participant of this match: applied before the fight (re-revealed), refused once the
     * fight began.
     */
    public AssignResult assign(Player target, String abilityId) {
        Participant participant = participants.get(target.getUniqueId());
        if (!started) {
            return AssignResult.NOT_STARTED;
        }
        if (stopped) {
            return AssignResult.TOO_LATE;
        }
        if (participant == null || !session.isAlive(target)) {
            return AssignResult.NOT_PARTICIPANT;
        }
        if (practice) {
            return switchAbility(target, abilityId) == PracticeResult.OK ? AssignResult.APPLIED
                    : AssignResult.NOT_PARTICIPANT;
        }
        switch (phase) {
            case INTRO -> {
                forced.put(participant.id(), abilityId);
                return AssignResult.APPLIED;
            }
            case SELECT -> {
                participant.forced = true;
                if (!participant.chosen) {
                    choose(participant, abilityId, false);
                } else {
                    participant.abilityId = abilityId;
                    startReveal(participant, 0);
                }
                participant.rerollsLeft = 0;
                return AssignResult.APPLIED;
            }
            case REVEAL, PREP -> {
                participant.forced = true;
                participant.rerollsLeft = 0;
                participant.abilityId = abilityId;
                startReveal(participant, SHORT_REVEAL_TICKS);
                return AssignResult.APPLIED;
            }
            default -> {
                return AssignResult.TOO_LATE;
            }
        }
    }

    // =================================================================== gauge, cooldown, silence

    void addGauge(Player player, double amount) {
        Participant participant = participants.get(player.getUniqueId());
        if (participant != null && session.isAlive(participant.id())) {
            addGauge(participant, amount);
        }
    }

    /**
     * Gauge earned by playing (damage dealt or taken, time alive): nothing while the post-ultimate lock runs
     * ({@code ultimate.recharge-delay-seconds}). Explicit grants ({@link #addGauge(Player, double)}) are never locked.
     */
    void addEarnedGauge(Player player, double amount) {
        Participant participant = participants.get(player.getUniqueId());
        if (participant != null && session.isAlive(participant.id())) {
            addEarnedGauge(participant, amount);
        }
    }

    private void addEarnedGauge(Participant participant, double amount) {
        if (!participant.isGaugeLocked(fightTicks)) {
            addGauge(participant, amount);
        }
    }

    /** True while a participant's gauge earns nothing after an ultimate ({@code ultimate.recharge-delay-seconds}). */
    boolean isGaugeLocked(Player player) {
        Participant participant = participants.get(player.getUniqueId());
        return participant != null && participant.isGaugeLocked(fightTicks);
    }

    void addGauge(Participant participant, double amount) {
        boolean filled = participant.gauge.add(amount);
        Player player = participant.player();
        if (player == null) {
            return;
        }
        syncXp(participant, player);
        if (filled && phase == MatchPhase.FIGHT && participant.ability != null) {
            AbilityInfo info = info(participant.abilityId);
            Feedback.title(player, messages.get("ultimate.ready-title"), messages.get("ultimate.ready-subtitle",
                    Messages.tag("ultimate", info == null ? Component.empty()
                            : Component.text(info.ultimate().name(), info.color()))), 5, 30, 10);
            Feedback.play(player, Sound.BLOCK_BEACON_POWER_SELECT, 1f, 1.5f);
        }
    }

    double gauge(Player player) {
        Participant participant = participants.get(player.getUniqueId());
        return participant == null ? 0.0 : participant.gauge.value();
    }

    void reduceCooldown(Player player, double seconds) {
        Participant participant = participants.get(player.getUniqueId());
        if (participant == null) {
            return;
        }
        participant.cooldown.reduce(fightTicks, seconds);
        if (session.isInPlay(player)) {
            showCooldown(participant, player);
        }
    }

    double cooldownRemaining(Player player) {
        Participant participant = participants.get(player.getUniqueId());
        return participant == null ? 0.0 : participant.cooldown.remainingSeconds(fightTicks);
    }

    void silence(Player target, long ticks) {
        Participant participant = participants.get(target.getUniqueId());
        if (participant == null || ticks <= 0 || phase != MatchPhase.FIGHT || !session.isInPlay(target)) {
            return;
        }
        long until = fightTicks + ticks;
        if (until <= participant.silencedUntil) {
            return;
        }
        participant.silencedUntil = until;
        participant.silenceShown = true;
        target.sendActionBar(messages.get("silence.applied", Messages.tag("seconds", Format.number(ticks / 20.0))));
        Feedback.play(target, Sound.ENTITY_ILLUSIONER_PREPARE_BLINDNESS, 0.8f, 1.4f);
        showCooldown(participant, target);
    }

    boolean isSilenced(Player player) {
        Participant participant = participants.get(player.getUniqueId());
        return participant != null && participant.isSilenced(fightTicks);
    }

    // =================================================================== loot consumables

    /**
     * A 궁극기 결정 was used: adds {@code percent} to the gauge. Refused (nothing changes, the item is kept) outside
     * the fight, for anyone not in play, while silenced, during the post-ultimate recharge delay (the gauge earns
     * nothing then, see {@link #addEarnedGauge}) and when the gauge is already full.
     */
    public ItemUseResult chargeGaugeWithItem(Player player, double percent) {
        ItemUseResult check = checkItemUse(player);
        if (check != ItemUseResult.OK) {
            return check;
        }
        Participant participant = participants.get(player.getUniqueId());
        if (participant.isGaugeLocked(fightTicks)) {
            return ItemUseResult.GAUGE_LOCKED;
        }
        if (participant.gauge.isFull()) {
            return ItemUseResult.GAUGE_FULL;
        }
        addGauge(participant, percent);
        return ItemUseResult.OK;
    }

    /**
     * A 재충전 부적 was used: the active skill's cooldown ends now. Refused outside the fight, for anyone not in play,
     * while silenced and when the skill is not on cooldown.
     */
    public ItemUseResult rechargeActiveWithItem(Player player) {
        ItemUseResult check = checkItemUse(player);
        if (check != ItemUseResult.OK) {
            return check;
        }
        Participant participant = participants.get(player.getUniqueId());
        if (participant.cooldown.isReady(fightTicks)) {
            return ItemUseResult.COOLDOWN_READY;
        }
        participant.cooldown.reset();
        showCooldown(participant, player);
        return ItemUseResult.OK;
    }

    private ItemUseResult checkItemUse(Player player) {
        if (stopped || !session.isRunning() || phase != MatchPhase.FIGHT) {
            return ItemUseResult.NOT_FIGHTING;
        }
        Participant participant = participants.get(player.getUniqueId());
        if (participant == null || participant.ability == null || !session.isInPlay(player)) {
            return ItemUseResult.NOT_IN_PLAY;
        }
        if (participant.isSilenced(fightTicks)) {
            return ItemUseResult.SILENCED;
        }
        return ItemUseResult.OK;
    }

    /** Seconds left of the player's silence (0 when not silenced). */
    public double silenceSeconds(Player player) {
        Participant participant = participants.get(player.getUniqueId());
        return participant == null ? 0.0 : Math.max(0, participant.silencedUntil - fightTicks) / 20.0;
    }

    /** Seconds left of the player's post-ultimate gauge lock (0 when not locked). */
    public double gaugeLockSeconds(Player player) {
        Participant participant = participants.get(player.getUniqueId());
        return participant == null ? 0.0 : Math.max(0, participant.gaugeLockedUntil - fightTicks) / 20.0;
    }

    /** The loot of this match, or null when loot chests are off ({@code loot.enabled}, practice). */
    public @Nullable LootManager loot() {
        return loot;
    }

    private void refreshSilences() {
        for (Participant participant : participants.values()) {
            if (participant.silenceShown && !participant.isSilenced(fightTicks)) {
                participant.silenceShown = false;
                Player player = participant.player();
                if (player != null && session.isInPlay(player)) {
                    showCooldown(participant, player);
                    player.sendActionBar(messages.get("silence.ended"));
                }
            }
        }
    }

    /** Shows the cooldown (or a longer silence) as the vanilla item cooldown overlay of the ability item. */
    private void showCooldown(Participant participant, Player player) {
        long ticks = participant.cooldown.remainingTicks(fightTicks);
        if (participant.isSilenced(fightTicks)) {
            ticks = Math.max(ticks, participant.silencedUntil - fightTicks);
        }
        player.setCooldown(items.cooldownGroup(), (int) Math.min(Integer.MAX_VALUE, ticks));
    }

    private void syncXp(Participant participant, Player player) {
        int level = participant.gauge.level();
        float progress = participant.gauge.progress();
        if (player.getLevel() != level) {
            player.setLevel(level);
        }
        if (Math.abs(player.getExp() - progress) > 1.0E-4f) {
            player.setExp(progress);
        }
    }

    // =================================================================== items & kit

    private void giveKit(Player player) {
        PlayerInventory inventory = player.getInventory();
        int next = 0;
        for (ItemStack stack : settings.kitCopy()) {
            EquipmentSlot slot = equipmentSlot(stack.getType());
            if (slot != null && isEmpty(inventory.getItem(slot))) {
                inventory.setItem(slot, stack);
                continue;
            }
            while (next < 36 && (next == AbilityItems.SLOT || !isEmpty(inventory.getItem(next)))) {
                next++;
            }
            if (next >= 36) {
                break;
            }
            inventory.setItem(next++, stack);
        }
    }

    /** Where a kit item is worn, or null for the main inventory. */
    static @Nullable EquipmentSlot equipmentSlot(Material material) {
        if (Tag.ITEMS_HEAD_ARMOR.isTagged(material)) {
            return EquipmentSlot.HEAD;
        }
        if (Tag.ITEMS_CHEST_ARMOR.isTagged(material) || material == Material.ELYTRA) {
            return EquipmentSlot.CHEST;
        }
        if (Tag.ITEMS_LEG_ARMOR.isTagged(material)) {
            return EquipmentSlot.LEGS;
        }
        if (Tag.ITEMS_FOOT_ARMOR.isTagged(material)) {
            return EquipmentSlot.FEET;
        }
        if (material == Material.SHIELD) {
            return EquipmentSlot.OFF_HAND;
        }
        return null;
    }

    private static boolean isEmpty(@Nullable ItemStack stack) {
        return stack == null || stack.isEmpty();
    }

    /** Once a second: the ability item is where it belongs and the XP bar shows the gauge. */
    private void maintainItems() {
        for (Participant participant : participants.values()) {
            Player player = participant.player();
            if (player == null || !session.isInPlay(player)) {
                continue;
            }
            syncXp(participant, player);
            if (participant.abilityId != null && !reveals.containsKey(participant.id())) {
                ensureAbilityItem(participant, player);
            }
        }
    }

    /**
     * Makes sure the ability item of the current ability sits in hotbar slot 9 and nowhere else (re-gives it if it was
     * lost; a non-ability item found in slot 9 is moved to a free slot).
     */
    void ensureAbilityItem(Participant participant, Player player) {
        AbilityInfo info = info(participant.abilityId);
        if (info == null) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (slot != AbilityItems.SLOT && items.isAbilityItem(inventory.getItem(slot))) {
                inventory.setItem(slot, null);
            }
        }
        if (items.isAbilityItem(player.getItemOnCursor())) {
            player.setItemOnCursor(null);
        }
        ItemStack current = inventory.getItem(AbilityItems.SLOT);
        if (info.id().equals(items.abilityId(current))) {
            return;
        }
        ItemStack displaced = isEmpty(current) || items.isAbilityItem(current) ? null : current;
        inventory.setItem(AbilityItems.SLOT, items.create(info, params(info), settings.abilityItemMaterial(), texts));
        if (displaced != null) {
            for (ItemStack leftover : inventory.addItem(displaced).values()) {
                Item dropped = player.getWorld().dropItem(player.getLocation(), leftover);
                session.track(dropped);
            }
        }
    }

    // =================================================================== practice

    /** Practice start: every player keeps its spot, gets the kit and its ability and the fight begins right away. */
    private void startPractice() {
        PracticeSettings options = PracticeSettings.read(session.gameConfig());
        practiceSettings = options;
        cooldownsEnabled = options.cooldowns();
        TrainingDummies created = new TrainingDummies(this, options);
        dummies = created;
        session.registerListener(created);
        List<Player> players = session.alivePlayers();
        for (Player player : players) {
            Participant participant = new Participant(player);
            participants.put(participant.id(), participant);
            enterPractice(participant, player);
        }
        phase = MatchPhase.FIGHT;
        phaseTicks = 0;
        fightTicks = 0;
        for (Player player : players) {
            Participant participant = participants.get(player.getUniqueId());
            if (participant != null) {
                grantPractice(participant, player);
            }
            if (stopped) {
                return;
            }
        }
        session.title(messages.get("practice.title"), messages.get("practice.subtitle"));
        session.playSound(Sound.BLOCK_BEACON_ACTIVATE, 0.7f, 1.4f);
        if (hud != null) {
            hud.update();
        }
        if (loot != null) {
            loot.spreadChests();
        }
    }

    /** A player enters the practice (start or late join): into the arena, kit, ability picked, XP bar. */
    private void enterPractice(Participant participant, Player player) {
        Location at = player.getLocation();
        if (!session.arena().contains(at) || border().isOutside(at)) {
            player.setFallDistance(0f);
            player.teleport(session.safeSpot(session.arena().center()));
        }
        homes.put(participant.id(), player.getLocation());
        giveKit(player);
        participant.abilityId = practiceAbility(participant.id());
        participant.forced = true;
        participant.rerollsLeft = 0;
        syncXp(participant, player);
    }

    /** Binds and grants the practice ability, tells the player and places its dummies. */
    private void grantPractice(Participant participant, Player player) {
        if (!bindAndGrant(participant, player)) {
            messages.send(player, "fight.ability-error");
        }
        AbilityInfo info = info(participant.abilityId);
        if (info != null) {
            sendSummary(player, info, params(info));
        }
        messages.send(player, "practice.started", Messages.tag("count", practiceOptions().dummyCount()),
                Messages.tag("cooldown", cooldownState()));
        TrainingDummies training = dummies;
        if (training != null && session.isInPlay(player)) {
            training.place(player, practiceOptions().dummyCount());
        }
    }

    /** The ability requested with {@code /게임 능력 연습 <능력>}, or a random one. */
    private String practiceAbility(UUID player) {
        String requested = game.takePracticeRequest(player);
        if (requested != null && info(requested) != null) {
            return requested;
        }
        List<String> pool = abilities.enabledIds();
        if (pool.isEmpty()) {
            pool = new ArrayList<>();
            for (AbilityRegistry.Entry entry : game.registry().all()) {
                pool.add(entry.info().id());
            }
        }
        return pool.get(session.random().nextInt(pool.size()));
    }

    private PracticeSettings practiceOptions() {
        if (practiceSettings == null) {
            throw new IllegalStateException("Not a practice session");
        }
        return practiceSettings;
    }

    private Component cooldownState() {
        return messages.get(cooldownsEnabled ? "practice.state-on" : "practice.state-off");
    }

    /** Practice: death is not the end, the player comes back at its spot (onRespawn re-equips). */
    @Override
    public DeathOutcome onDeath(Player victim, @Nullable Player killer, PlayerDeathEvent event) {
        if (!practice) {
            return DeathOutcome.eliminate();
        }
        Participant participant = participants.get(victim.getUniqueId());
        if (participant != null) {
            revoke(participant);
        }
        if (ctx != null) {
            ctx.leftPlay(victim);
        }
        if (killer != null && phase == MatchPhase.FIGHT) {
            Participant credited = participants.get(killer.getUniqueId());
            Ability ability = credited == null ? null : credited.ability;
            if (ability != null && session.isInPlay(killer)) {
                ownCode(credited, () -> guard.run(credited.abilityId, "onKill", () -> ability.onKill(victim)));
            }
        }
        Location home = homes.get(victim.getUniqueId());
        return DeathOutcome.respawn(home != null && home.getWorld() != null ? home
                : session.safeSpot(session.arena().center()));
    }

    /** Practice: back from a death with a fresh kit and the same ability (bound again). */
    @Override
    public void onRespawn(Player player) {
        Participant participant = participants.get(player.getUniqueId());
        if (!practice || participant == null || stopped || participant.abilityId == null) {
            return;
        }
        player.getInventory().clear();
        player.setItemOnCursor(null);
        giveKit(player);
        if (!bindAndGrant(participant, player)) {
            messages.send(player, "fight.ability-error");
        }
        syncXp(participant, player);
        if (ctx != null) {
            ctx.viewersChanged();
        }
        messages.send(player, "practice.respawned");
    }

    /**
     * Practice: players who ask with {@code /게임 능력 연습} join the running practice, and so do practicing players
     * coming back after quitting. Anyone else (e.g. someone logging in) watches, as in every match.
     */
    @Override
    public boolean allowLateJoin(Player player, boolean rejoining) {
        boolean asked = game.takePracticeJoin(player.getUniqueId());
        return practice && started && !stopped && (asked || rejoining);
    }

    @Override
    public void onLateJoin(Player player) {
        if (!practice || ctx == null || stopped) {
            return;
        }
        Participant participant = participants.computeIfAbsent(player.getUniqueId(), id -> new Participant(player));
        enterPractice(participant, player);
        grantPractice(participant, player);
        ctx.viewersChanged();
    }

    /** True for a practice session ({@code /게임 능력 연습}). */
    public boolean isPractice() {
        return practice;
    }

    /** True once {@link #start()} ran (false while the hub counts down). */
    public boolean hasStarted() {
        return started && !stopped;
    }

    /** Practice: whether active skills go on cooldown. */
    public boolean cooldownsEnabled() {
        return cooldownsEnabled;
    }

    private PracticeResult practiceCheck(Player player) {
        if (!practice) {
            return PracticeResult.NOT_PRACTICE;
        }
        if (!started || stopped || phase != MatchPhase.FIGHT) {
            return PracticeResult.NOT_STARTED;
        }
        if (participants.get(player.getUniqueId()) == null || !session.isInPlay(player)) {
            return PracticeResult.NOT_PARTICIPANT;
        }
        return PracticeResult.OK;
    }

    /** Practice: swaps the player's ability (old one revoked, new one bound and granted) and heals them. */
    public PracticeResult switchAbility(Player player, String abilityId) {
        PracticeResult check = practiceCheck(player);
        if (check != PracticeResult.OK) {
            return check;
        }
        AbilityInfo info = info(abilityId);
        if (info == null) {
            return PracticeResult.UNKNOWN_ABILITY;
        }
        if (!replaceAbility(player, abilityId)) {
            return PracticeResult.FAILED;
        }
        if (session.isInPlay(player)) {
            player.setHealth(MatchContext.maxHealth(player));
            player.setFireTicks(0);
        }
        Participant participant = participants.get(player.getUniqueId());
        if (participant != null) {
            syncXp(participant, player);
        }
        sendSummary(player, info, params(info));
        return PracticeResult.OK;
    }

    /** Practice: fills the player's ultimate gauge (and lifts the post-ultimate lock). */
    public PracticeResult chargeUltimate(Player player) {
        PracticeResult check = practiceCheck(player);
        if (check != PracticeResult.OK) {
            return check;
        }
        Participant participant = participants.get(player.getUniqueId());
        if (participant == null) {
            return PracticeResult.NOT_PARTICIPANT;
        }
        participant.gaugeLockedUntil = 0;
        if (participant.gauge.isFull()) {
            syncXp(participant, player);
        } else {
            addGauge(participant, UltimateGauge.MAX - participant.gauge.value());
        }
        return PracticeResult.OK;
    }

    /** Practice: turns active cooldowns on or off for everyone (off also clears the running ones). */
    public PracticeResult setCooldownsEnabled(boolean enabled) {
        if (!practice) {
            return PracticeResult.NOT_PRACTICE;
        }
        cooldownsEnabled = enabled;
        if (!enabled && started) {
            for (Participant participant : participants.values()) {
                participant.cooldown.reset();
                Player player = participant.player();
                if (player != null && session.isInPlay(player)) {
                    showCooldown(participant, player);
                }
            }
        }
        return PracticeResult.OK;
    }

    /** Practice: places {@code count} fresh dummies in front of the player (its respawn spot moves there too). */
    public PracticeResult placeDummies(Player player, int count) {
        PracticeResult check = practiceCheck(player);
        TrainingDummies training = dummies;
        if (check != PracticeResult.OK || training == null) {
            return check == PracticeResult.OK ? PracticeResult.FAILED : check;
        }
        homes.put(player.getUniqueId(), player.getLocation());
        return training.place(player, count) > 0 ? PracticeResult.OK : PracticeResult.FAILED;
    }

    /** Practice: the live training dummies placed for {@code player} (empty outside practice). */
    public List<Mannequin> dummiesOf(Player player) {
        TrainingDummies training = dummies;
        return training == null ? List.of() : training.of(player.getUniqueId());
    }

    /** Practice: true for a live training dummy of this session. */
    public boolean isDummy(@Nullable Entity entity) {
        TrainingDummies training = dummies;
        return training != null && training.isDummy(entity);
    }

    /** Live training dummies (enemy candidates), empty outside practice. */
    List<LivingEntity> liveDummies() {
        TrainingDummies training = dummies;
        return training == null ? List.of() : training.live();
    }

    // =================================================================== accessors

    SuperpowerGame game() {
        return game;
    }

    public GameSession session() {
        return session;
    }

    Messages messages() {
        return messages;
    }

    AbilityGuard guard() {
        return guard;
    }

    /** The guard isolating ability errors (failure counts for diagnostics and tests). */
    public AbilityGuard errors() {
        return guard;
    }

    AbilityItems items() {
        return items;
    }

    AbilityConfig.Snapshot abilities() {
        return abilities;
    }

    public SuperpowerSettings settings() {
        return settings;
    }

    public MatchPhase phase() {
        return phase;
    }

    long phaseTicks() {
        return phaseTicks;
    }

    /** Ticks since the fight began (0 before). */
    public long fightTicks() {
        return fightTicks;
    }

    /**
     * Runs a hook of {@code participant}'s ability as its own code (movement it gives its owner is no displacement,
     * see {@link MatchContext#asOwnCode}); plainly before the match context exists.
     */
    private void ownCode(Participant participant, Runnable hook) {
        MatchContext context = ctx;
        if (context == null) {
            hook.run();
        } else {
            context.runOwnCode(participant.id(), hook);
        }
    }

    private <T> @Nullable T ownCode(Participant participant, Supplier<@Nullable T> hook) {
        MatchContext context = ctx;
        return context == null ? hook.get() : context.asOwnCode(participant.id(), hook);
    }

    /** The ability context of this match (abilities receive it in {@link Ability#bind}). */
    public MatchContext context() {
        if (ctx == null) {
            throw new IllegalStateException("The match has not started");
        }
        return ctx;
    }

    BorderController border() {
        if (border == null) {
            throw new IllegalStateException("The match has not started");
        }
        return border;
    }

    /** The sky of this match, or null outside plugin map worlds, in practice and before the start. */
    @Nullable MatchSky sky() {
        return sky;
    }

    public @Nullable Participant participant(UUID id) {
        return participants.get(id);
    }

    public Collection<Participant> participants() {
        return Collections.unmodifiableCollection(participants.values());
    }

    @Nullable AbilityInfo info(@Nullable String abilityId) {
        if (abilityId == null) {
            return null;
        }
        AbilityRegistry.Entry entry = game.registry().get(abilityId);
        return entry == null ? null : entry.info();
    }

    AbilityParams params(AbilityInfo info) {
        return abilities.params(info);
    }

    AbilityParams params(String abilityId) {
        AbilityInfo info = info(abilityId);
        if (info == null) {
            throw new IllegalArgumentException("Unknown ability: " + abilityId);
        }
        return params(info);
    }

    /** The ability id of an owner, for log messages. */
    String abilityIdOf(UUID owner) {
        Participant participant = participants.get(owner);
        return participant == null || participant.abilityId == null ? "unknown" : participant.abilityId;
    }

    /** True while the owner may create owner-scoped resources (alive, ability bound, match running). */
    boolean canOwnResources(UUID owner) {
        Participant participant = participants.get(owner);
        return !stopped && participant != null && participant.ability != null && session.isAlive(owner);
    }

    private Logger logger() {
        return session.plugin().getLogger();
    }

    // =================================================================== revoke

    private void revoke(Participant participant) {
        Ability ability = participant.ability;
        participant.ability = null;
        if (ability != null && participant.granted) {
            ownCode(participant, () -> guard.run(participant.abilityId, "onRevoke", ability::onRevoke));
        }
        if (ctx != null) {
            ctx.releaseOwner(participant.id());
        }
        participant.cooldown.reset();
        participant.gaugeLockedUntil = 0;
        participant.silencedUntil = 0;
        participant.silenceShown = false;
        Player player = participant.player();
        if (player != null && started) {
            player.setCooldown(items.cooldownGroup(), 0);
        }
    }

    /**
     * Replaces the bound ability of a fighting participant (revoke, bind, grant): the practice ability switch, tests
     * and debugging. The damage the player had taken stays the same, so a passive that changed max health (the
     * titan's bonus, the pixie's penalty) neither leaves its clamped health behind nor hands out free health.
     */
    public boolean replaceAbility(Player player, String abilityId) {
        Participant participant = participants.get(player.getUniqueId());
        if (stopped || participant == null || phase != MatchPhase.FIGHT || !session.isInPlay(player)
                || info(abilityId) == null) {
            return false;
        }
        double missing = Math.max(0.0, MatchContext.maxHealth(player) - player.getHealth());
        revoke(participant);
        participant.abilityId = abilityId;
        boolean bound = bindAndGrant(participant, player);
        if (!player.isDead() && session.isInPlay(player)) {
            double max = MatchContext.maxHealth(player);
            player.setHealth(Math.max(Math.min(1.0, max), Math.min(max, max - missing)));
        }
        return bound;
    }

    /** Sets the gauge of a participant (tests/debugging); triggers the "ready" notification when it fills. */
    public void setGauge(Player player, double value) {
        Participant participant = participants.get(player.getUniqueId());
        if (participant != null) {
            addGauge(participant, value - participant.gauge.value());
        }
    }
}
