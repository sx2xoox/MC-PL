package io.github.sx2xoox.mcpl.game.superpower.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.github.sx2xoox.mcpl.core.game.GameInstance;
import io.github.sx2xoox.mcpl.core.game.GameResult;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.game.MatchTeam;
import io.github.sx2xoox.mcpl.core.ui.Feedback;
import io.github.sx2xoox.mcpl.core.ui.Format;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.game.superpower.SelectionMode;
import io.github.sx2xoox.mcpl.game.superpower.SuperpowerGame;
import io.github.sx2xoox.mcpl.game.superpower.SuperpowerSettings;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityParams;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityRegistry;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.game.superpower.config.AbilityConfig;
import io.github.sx2xoox.mcpl.game.superpower.menu.ChoiceMenu;
import io.github.sx2xoox.mcpl.game.superpower.ui.AbilityItems;
import io.github.sx2xoox.mcpl.game.superpower.ui.AbilityTexts;
import io.github.sx2xoox.mcpl.game.superpower.ui.RevealAnimation;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.Tag;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
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
 */
public final class SuperpowerMatch implements GameInstance {

    /** Pause after the hub's START title before the reveal starts. */
    static final int INTRO_TICKS = 25;
    /** Time the final reveal title stays before the prep phase starts. */
    static final int REVEAL_HOLD_TICKS = 30;
    /** Length of the short re-reveal after a reroll or an admin assignment. */
    static final int SHORT_REVEAL_TICKS = 24;
    static final int CHOICE_CANDIDATES = 3;

    /** Outcome of {@link #reroll(Player)}. */
    public enum RerollResult { OK, NOT_PARTICIPANT, WRONG_PHASE, WRONG_MODE, FORCED, NONE_LEFT, NO_OPTIONS }

    /** Outcome of {@link #assign(Player, String)}. */
    public enum AssignResult { APPLIED, NOT_STARTED, NOT_PARTICIPANT, TOO_LATE }

    private final SuperpowerGame game;
    private final GameSession session;
    private final Messages messages;
    private final AbilityGuard guard;
    private final AbilityItems items;
    private final AbilityTexts texts;
    private final Map<UUID, Participant> participants = new LinkedHashMap<>();
    private final Map<UUID, RevealAnimation> reveals = new LinkedHashMap<>();
    private final Map<UUID, String> forced = new LinkedHashMap<>();
    private AbilityConfig.Snapshot abilities;
    private SuperpowerSettings settings;
    private @Nullable MatchContext ctx;
    private @Nullable BorderController border;
    private @Nullable MatchHud hud;
    private MatchPhase phase = MatchPhase.INTRO;
    private long phaseTicks;
    private long fightTicks;
    private long revealHoldUntil = -1;
    private boolean started;
    private boolean stopped;

    public SuperpowerMatch(SuperpowerGame game, GameSession session) {
        this.game = game;
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
        settings = SuperpowerSettings.read(session.gameConfig(), logger());
        abilities = game.abilities().snapshot();
        if (abilities.enabledIds().isEmpty()) {
            session.abort(messages.get("match.no-abilities"));
            return;
        }
        ctx = new MatchContext(this);
        session.registerListener(new MatchListener(this));
        border = new BorderController(session, settings);
        hud = new MatchHud(this);
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
        if (killer != null && phase == MatchPhase.FIGHT) {
            Participant credited = participants.get(killer.getUniqueId());
            Ability ability = credited == null ? null : credited.ability;
            if (ability != null && session.isInPlay(killer)) {
                guard.run(credited.abilityId, "onKill", () -> ability.onKill(player));
            }
        }
        checkWinner();
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

    @Override
    public void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        try {
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
        } finally {
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
        sendSpectatorSummary();
        if (settings.prepTicks() <= 0) {
            beginFight();
        }
    }

    private void tickPrep() {
        long left = settings.prepTicks() - phaseTicks;
        if (left <= 0) {
            beginFight();
            return;
        }
        if (left == 60 || left == 40 || left == 20) {
            int seconds = (int) (left / 20);
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
            Ability ability = guard.call(id, "bind", () -> createAbility(id, player));
            if (ability == null) {
                messages.send(player, "fight.ability-error");
                continue;
            }
            participant.ability = ability;
            context.openScope(participant.id());
            ensureAbilityItem(participant, player);
            bound.add(participant);
        }
        for (Participant participant : bound) {
            Ability ability = participant.ability;
            if (ability == null) {
                continue;
            }
            participant.granted = true;
            guard.run(participant.abilityId, "onGrant", ability::onGrant);
            if (stopped) {
                return;
            }
        }
        session.title(messages.get("fight.title"), messages.get("fight.subtitle"));
        session.playSound(Sound.EVENT_RAID_HORN, 0.7f, 1.0f);
        session.broadcast(messages.prefixed("fight.started"));
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
            guard.run(participant.abilityId, "onTick", () -> ability.onTick(now));
            if (stopped) {
                return;
            }
        }
        BorderController zone = border();
        if (zone.tick(now)) {
            announceBorderShrink();
        } else if (zone.state(now) == BorderController.State.WAITING && zone.ticksUntilShrink(now) == 200) {
            session.broadcast(messages.prefixed("border.warning", Messages.tag("seconds", 10)));
        }
        if (now % 5 == 0) {
            refreshSilences();
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
                addGauge(participant, perSecond);
            }
        }
        double damage = settings.borderDamagePerSecond();
        for (Player player : session.alivePlayers()) {
            if (border().isOutside(player.getLocation())) {
                player.sendActionBar(messages.get("border.outside"));
                Feedback.play(player, Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.5f);
                context().borderDamage(player, damage);
                if (stopped) {
                    return;
                }
            }
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

    /** The open choice menu of a viewer, if {@code top} is it. */
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

    void onMenuClosed(Player player, ChoiceMenu menu) {
        Participant participant = participants.get(player.getUniqueId());
        if (participant == null || participant.menu != menu) {
            return;
        }
        participant.menu = null;
        if (!participant.chosen && phase == MatchPhase.SELECT && session.isAlive(participant.id())) {
            choose(participant, randomCandidate(participant), true);
        }
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
                    reportReroll(player, reroll(player));
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
        ActivationResult result = guard.call(id, "onActive", ability::onActive);
        if (result == null) {
            deny(player, messages.get("input.error"));
            return ActivationResult.fail("error");
        }
        if (!result.succeeded()) {
            deny(player, reason(result));
            return result;
        }
        if (participant.ability == ability && session.isInPlay(player)) {
            Double multiplier = guard.call(id, "cooldownMultiplier", ability::cooldownMultiplier);
            participant.cooldown.start(fightTicks, params(id).cooldownSeconds(), multiplier == null ? 1.0 : multiplier);
            showCooldown(participant, player);
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
        ActivationResult result = guard.call(id, "onUltimate", ability::onUltimate);
        if (result == null) {
            deny(player, messages.get("input.error"));
            return ActivationResult.fail("error");
        }
        if (!result.succeeded()) {
            deny(player, reason(result));
            return result;
        }
        participant.gauge.reset();
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
        session.broadcast(messages.prefixed("ultimate.announce", Messages.tag("player", session.name(player)),
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

    /** Sends the Korean feedback for a reroll attempt. */
    public void reportReroll(Player player, RerollResult result) {
        Participant participant = participants.get(player.getUniqueId());
        switch (result) {
            case OK -> {
                messages.send(player, "reroll.done",
                        Messages.tag("count", participant == null ? 0 : participant.rerollsLeft));
                Feedback.play(player, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1.2f);
            }
            case NOT_PARTICIPANT -> messages.send(player, "reroll.not-participant");
            case WRONG_MODE -> messages.send(player, "reroll.wrong-mode");
            case WRONG_PHASE -> messages.send(player, "reroll.wrong-phase");
            case FORCED -> messages.send(player, "reroll.forced");
            case NONE_LEFT -> messages.send(player, "reroll.none-left");
            case NO_OPTIONS -> messages.send(player, "reroll.no-options");
        }
        if (result != RerollResult.OK) {
            Feedback.play(player, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.7f);
        }
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
            guard.run(participant.abilityId, "onRevoke", ability::onRevoke);
        }
        if (ctx != null) {
            ctx.releaseOwner(participant.id());
        }
        participant.cooldown.reset();
        participant.silencedUntil = 0;
        participant.silenceShown = false;
        Player player = participant.player();
        if (player != null && started) {
            player.setCooldown(items.cooldownGroup(), 0);
        }
    }

    /** Used by tests and debugging: replaces the bound ability of a fighting participant (revoke, bind, grant). */
    public boolean replaceAbility(Player player, String abilityId) {
        Participant participant = participants.get(player.getUniqueId());
        if (participant == null || phase != MatchPhase.FIGHT || !session.isInPlay(player) || info(abilityId) == null) {
            return false;
        }
        revoke(participant);
        participant.abilityId = abilityId;
        Ability ability = guard.call(abilityId, "bind", () -> createAbility(abilityId, player));
        if (ability == null) {
            return false;
        }
        participant.ability = ability;
        context().openScope(participant.id());
        ensureAbilityItem(participant, player);
        participant.granted = true;
        guard.run(abilityId, "onGrant", ability::onGrant);
        return true;
    }

    /** Sets the gauge of a participant (tests/debugging); triggers the "ready" notification when it fills. */
    public void setGauge(Player player, double value) {
        Participant participant = participants.get(player.getUniqueId());
        if (participant != null) {
            addGauge(participant, value - participant.gauge.value());
        }
    }
}
