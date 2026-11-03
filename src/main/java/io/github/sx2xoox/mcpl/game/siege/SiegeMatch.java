package io.github.sx2xoox.mcpl.game.siege;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.core.Hub;
import io.github.sx2xoox.mcpl.core.command.Permissions;
import io.github.sx2xoox.mcpl.core.game.DeathOutcome;
import io.github.sx2xoox.mcpl.core.game.GameInstance;
import io.github.sx2xoox.mcpl.core.game.GameResult;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.game.MatchTeam;
import io.github.sx2xoox.mcpl.core.ui.Feedback;
import io.github.sx2xoox.mcpl.core.ui.Format;
import io.github.sx2xoox.mcpl.core.ui.ManagedBossBar;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.game.siege.tower.Tower;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityRegistry;
import io.github.sx2xoox.mcpl.game.superpower.runtime.MatchHost;
import io.github.sx2xoox.mcpl.game.superpower.runtime.Participant;
import io.github.sx2xoox.mcpl.game.superpower.runtime.SuperpowerMatch;
import io.github.sx2xoox.mcpl.game.superpower.ui.AbilityTexts;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.meta.FireworkMeta;
import org.jspecify.annotations.Nullable;

/**
 * One match of 공성전 (see docs/siege.md): rounds of (prep → 전반 → 하프타임 → 후반 [→ 연장]) until a team won
 * {@code rounds-to-win} rounds. The abilities run in a hosted {@link SuperpowerMatch}; this class owns the flow,
 * the score ({@link SiegeScore}), the towers, respawns and spawn protection.
 */
public final class SiegeMatch implements GameInstance {

    /** Phases of the match (all inside the hub's RUNNING state). */
    public enum Phase {
        /** Before a half: everyone at their spawn, invulnerable; at the start of a round rerolls are open. */
        PREP,
        /** A half is being played. */
        FIGHT,
        /** Between the halves of a round. */
        HALFTIME,
        /** Between rounds. */
        BREAK,
        /** The match is decided. */
        OVER
    }

    /** Why a half ended. */
    public enum HalfEnd { TIME, CORE }

    private final SiegeGame game;
    private final GameSession session;
    private final Messages messages;
    private SiegeSettings settings;
    private @Nullable SuperpowerMatch abilities;
    private @Nullable SiegeLayout layout;
    private @Nullable SiegeScore score;
    private @Nullable SiegeHud hud;
    private final List<MatchTeam> teams = new ArrayList<>();
    private final List<Tower> towers = new ArrayList<>();
    private final Map<UUID, Long> protectedUntil = new HashMap<>();
    private final Map<UUID, Integer> rerollsLeft = new HashMap<>();
    /** Chunks held loaded around the towers ({@link #holdTowerChunks}), as {@code x, z} pairs. */
    private final List<int[]> heldChunks = new ArrayList<>();
    /** The half each player who is waiting to respawn died in ({@link #halfSerial}). */
    private final Map<UUID, Integer> diedInHalf = new HashMap<>();
    /** Counts every half begun, so a player who respawns after a half change gets the fresh start too. */
    private int halfSerial;
    private List<String> roundPool = List.of();
    private Phase phase = Phase.PREP;
    private long phaseTicks;
    private long prepTicks;
    /** Ticks of the current half's fight (also the time base of towers and protection). */
    private long halfTicks;
    private int round;
    private int half;
    private int towersDown;
    private boolean climax;
    private boolean suddenDeath;
    private int lastCountdown;
    private boolean stopped;
    /** A team started empty (one player trying the mode alone): an empty team does not lose the match. */
    private boolean solo;

    public SiegeMatch(SiegeGame game, GameSession session) {
        this.game = game;
        this.session = session;
        this.messages = game.messages();
        this.settings = SiegeSettings.read(new org.bukkit.configuration.MemoryConfiguration());
    }

    // =================================================================== lifecycle

    @Override
    public int teamCount() {
        return 2;
    }

    @Override
    public void start() {
        settings = SiegeSettings.read(session.gameConfig());
        if (session.teams().size() < 2) {
            session.abort(messages.get("start.no-teams"));
            return;
        }
        teams.addAll(session.teams().subList(0, 2));
        solo = teams.stream().anyMatch(team -> team.members().isEmpty());
        layout = game.layoutFor(session.arena());
        holdTowerChunks();
        score = new SiegeScore(settings, this::teamIndex);
        SuperpowerMatch hosted = new SuperpowerMatch(game.superpower(), session, new Host());
        abilities = hosted;
        hosted.start();
        if (!session.isRunning()) {
            return;
        }
        session.registerListener(new SiegeListener(this));
        hud = new SiegeHud(this);
        startRound();
    }

    @Override
    public void tick() {
        SuperpowerMatch hosted = abilities;
        if (stopped || hosted == null) {
            return;
        }
        hosted.tick();
        if (stopped || !session.isRunning()) {
            return;
        }
        phaseTicks++;
        switch (phase) {
            case PREP -> tickPrep();
            case FIGHT -> tickFight();
            case HALFTIME -> {
                if (phaseTicks >= settings.halftimeTicks()) {
                    beginHalf(2);
                }
            }
            case BREAK -> {
                if (phaseTicks >= settings.roundBreakTicks()) {
                    startRound();
                }
            }
            case OVER -> {
            }
        }
        if (!stopped && session.isRunning() && session.tick() % 10 == 0 && hud != null) {
            hud.update();
        }
    }

    @Override
    public DeathOutcome onDeath(Player victim, @Nullable Player killer, PlayerDeathEvent event) {
        return abilities().onDeath(victim, killer, event);
    }

    @Override
    public void onRespawn(Player player) {
        abilities().onRespawn(player);
    }

    @Override
    public void onQuit(Player player) {
        abilities().onQuit(player);
    }

    @Override
    public void onEliminated(Player player, @Nullable Player killer) {
        abilities().onEliminated(player, killer);
    }

    @Override
    public @Nullable Component playerTag(UUID player) {
        return abilities == null ? null : abilities.playerTag(player);
    }

    @Override
    public @Nullable Component nameOverride(UUID player) {
        return abilities == null ? null : abilities.nameOverride(player);
    }

    @Override
    public void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        try {
            disposeTowers();
            releaseTowerChunks();
            if (abilities != null) {
                abilities.stop();
            }
        } finally {
            game.matchEnded(this);
        }
    }

    // =================================================================== MatchHost

    /** The rules this match gives its hosted abilities match. */
    private final class Host implements MatchHost {
        @Override
        public DeathOutcome onDeath(Player victim, @Nullable Player killer) {
            return hostDeath(victim, killer);
        }

        @Override
        public void onRespawn(Player player) {
            hostRespawn(player);
        }

        @Override
        public void onEliminated(Player player, @Nullable Player killer) {
            hostEliminated();
        }

        @Override
        public boolean onUseOutsideFight(Player player) {
            return useOutsideFight(player);
        }

        @Override
        public List<LivingEntity> extraTargets() {
            return towerTargets();
        }

        @Override
        public boolean isExtraTarget(Entity entity) {
            return towerOf(entity) != null;
        }

        @Override
        public boolean isExtraEnemy(Player owner, Entity entity) {
            return isTowerEnemy(owner, entity);
        }
    }

    private DeathOutcome hostDeath(Player victim, @Nullable Player killer) {
        UUID id = victim.getUniqueId();
        protectedUntil.remove(id);
        SiegeScore points = score();
        boolean fighting = phase == Phase.FIGHT;
        if (fighting) {
            int before = killer == null ? 0 : points.streak(killer.getUniqueId());
            SiegeScore.Kill kill = points.kill(id, killer == null ? null : killer.getUniqueId(), halfTicks);
            if (killer != null && kill.points() > 0) {
                killer.sendActionBar(messages.get("score.kill-points", Messages.tag("points", kill.points())));
                if (kill.bounty()) {
                    session.broadcast(messages.prefixed("score.bounty-claimed",
                            Messages.tag("killer", session.publicName(killer.getUniqueId())),
                            Messages.tag("victim", session.publicName(id)),
                            Messages.tag("streak", kill.victimStreak()), Messages.tag("points", kill.points())));
                    session.playSound(Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 0.7f);
                }
                int streak = points.streak(killer.getUniqueId());
                if (streak > before && streak == settings.bountyStreak()) {
                    session.broadcast(messages.prefixed("score.bounty-on",
                            Messages.tag("player", session.publicName(killer.getUniqueId())),
                            Messages.tag("streak", streak), Messages.tag("points", settings.bountyPoints())));
                    session.playSound(Sound.ENTITY_WITHER_AMBIENT, 0.4f, 1.6f);
                }
                for (UUID assister : kill.assisters()) {
                    Player helper = Bukkit.getPlayer(assister);
                    if (helper != null) {
                        helper.sendActionBar(messages.get("score.assist-points",
                                Messages.tag("points", settings.assistPoints())));
                    }
                }
            }
        }
        diedInHalf.put(id, halfSerial);
        int delay = settings.respawnDelay(points.halfDeaths(id), climax && fighting);
        Location spawn = spawnFor(id);
        session.runLater(2, () -> watchTeammate(victim));
        return DeathOutcome.respawn(spawn, delay);
    }

    private void hostRespawn(Player player) {
        player.teleport(spawnFor(player.getUniqueId()));
        Integer died = diedInHalf.remove(player.getUniqueId());
        if (died != null && died != halfSerial) {
            // Died before the half (or round) changed: the kit and health reset the others got at the change.
            abilities().hostResetPlayer(player);
        }
        if (phase == Phase.FIGHT) {
            protectedUntil.put(player.getUniqueId(), halfTicks + settings.protectionTicks());
        }
        if (hud != null) {
            hud.update();
        }
    }

    private void hostEliminated() {
        if (phase == Phase.OVER || !session.isRunning()) {
            return;
        }
        for (int team = 0; team < 2; team++) {
            if (aliveMembers(team) == 0 && !(solo && teams.get(team).members().isEmpty())) {
                session.broadcast(messages.prefixed("result.team-left", Messages.tag("team",
                        teams.get(team).displayName())));
                finishMatch(1 - team, messages.get("result.reason-left"));
                return;
            }
        }
    }

    private boolean useOutsideFight(Player player) {
        if (phase == Phase.PREP && half == 1 && player.isSneaking()) {
            reportReroll(player, reroll(player));
            return true;
        }
        if (phase == Phase.PREP && half == 1 && rerollsLeft.getOrDefault(player.getUniqueId(), 0) > 0) {
            player.sendActionBar(messages.get("reroll.hint",
                    Messages.tag("count", rerollsLeft.getOrDefault(player.getUniqueId(), 0))));
            return true;
        }
        return false;
    }

    private List<LivingEntity> towerTargets() {
        List<LivingEntity> out = new ArrayList<>();
        for (Tower tower : towers) {
            LivingEntity body = tower.hitbox();
            if (body != null && body.isValid() && !tower.isDestroyed()) {
                out.add(body);
            }
        }
        return out;
    }

    /** A tower is an enemy of the attackers only, while the fight runs and the tower can be hit (no shield). */
    public boolean isTowerEnemy(Player owner, Entity entity) {
        Tower tower = towerOf(entity);
        return tower != null && phase == Phase.FIGHT && !tower.isDestroyed() && !tower.isShielded()
                && teamIndex(owner.getUniqueId()) == attackingTeam();
    }

    // =================================================================== rounds and halves

    private void startRound() {
        round++;
        score().newRound();
        assignAbilities();
        beginHalf(1);
    }

    private void assignAbilities() {
        SuperpowerMatch hosted = abilities();
        AbilityRegistry registry = game.superpower().registry();
        List<String> enabled = new ArrayList<>(game.superpower().abilities().snapshot().enabledIds());
        enabled.removeIf(id -> registry.get(id) == null);
        int biggest = Math.max(members(0).size(), members(1).size());
        SiegeAbilities.Pool pool = SiegeAbilities.pool(game.abilityPool(), enabled, biggest, session.random());
        if (!pool.unknown().isEmpty()) {
            tellAdmins(messages.prefixed("pool.unknown-warning",
                    Messages.tag("ids", String.join(", ", pool.unknown()))));
        }
        if (pool.filledUp()) {
            tellAdmins(messages.prefixed("pool.filled-warning", Messages.tag("count", biggest)));
        }
        roundPool = pool.ids();
        if (roundPool.isEmpty()) {
            session.abort(messages.get("start.no-abilities"));
            return;
        }
        Map<UUID, String> assignment = SiegeAbilities.assign(List.of(members(0), members(1)), roundPool,
                session.random());
        hosted.hostAssign(assignment);
        rerollsLeft.clear();
        for (UUID id : assignment.keySet()) {
            rerollsLeft.put(id, settings.rerolls());
        }
    }

    private void tellAdmins(Component message) {
        for (Player viewer : session.viewers()) {
            if (viewer.hasPermission(Permissions.ADMIN)) {
                viewer.sendMessage(message);
            }
        }
        session.plugin().getLogger().warning(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(message));
    }

    /** Sets up a half: fresh towers, everyone at their spawn with a fresh kit, the prep countdown. */
    private void beginHalf(int which) {
        if (!session.isRunning()) {
            return;
        }
        SuperpowerMatch hosted = abilities();
        hosted.hostEndFight();
        half = which;
        halfSerial++;
        suddenDeath = false;
        climax = false;
        towersDown = 0;
        halfTicks = 0;
        protectedUntil.clear();
        score().newHalf();
        disposeTowers();
        createTowers();
        int attackers = attackingTeam();
        List<Location> attackSpawns = layout().attackerSpawns();
        List<Location> defendSpawns = layout().defenderSpawns(0);
        int[] next = {0, 0};
        for (Player player : session.alivePlayers()) {
            int team = teamIndex(player.getUniqueId());
            if (team < 0) {
                continue;
            }
            List<Location> spots = team == attackers ? attackSpawns : defendSpawns;
            Location spot = spotFor(spots, next[team]++);
            player.setFallDistance(0f);
            player.teleport(spot);
            hosted.hostResetPlayer(player);
        }
        phase = Phase.PREP;
        phaseTicks = 0;
        prepTicks = which == 1 ? settings.roundPrepTicks() : settings.halfPrepTicks();
        lastCountdown = Integer.MAX_VALUE;
        MatchTeam attacking = teams.get(attackers);
        session.title(messages.get("half.title", Messages.tag("round", round),
                        Messages.tag("half", messages.get(which == 1 ? "half.first" : "half.second"))),
                messages.get("half.subtitle", Messages.tag("team", attacking.displayName())));
        session.broadcast(messages.prefixed("half.started", Messages.tag("round", round),
                Messages.tag("half", messages.get(which == 1 ? "half.first" : "half.second")),
                Messages.tag("attackers", attacking.displayName()),
                Messages.tag("defenders", teams.get(1 - attackers).displayName())));
        if (which == 1 && settings.rerolls() > 0) {
            session.broadcast(messages.prefixed("reroll.announce", Messages.tag("count", settings.rerolls())));
        }
        session.playSound(Sound.EVENT_RAID_HORN, 0.6f, 1.2f);
        if (prepTicks <= 0) {
            beginFight();
        }
    }

    private void tickPrep() {
        long left = prepTicks - phaseTicks;
        if (left <= 0) {
            beginFight();
            return;
        }
        int seconds = (int) ((left + 19) / 20);
        if (seconds <= 3 && seconds < lastCountdown) {
            lastCountdown = seconds;
            for (Player player : session.viewers()) {
                Feedback.title(player, messages.get("prep.countdown", Messages.tag("seconds", seconds)),
                        Component.empty(), 0, 20, 5);
                Feedback.play(player, Sound.BLOCK_NOTE_BLOCK_HAT, 1f, seconds == 1 ? 1.6f : 1.2f);
            }
        }
    }

    private void beginFight() {
        phase = Phase.FIGHT;
        phaseTicks = 0;
        halfTicks = 0;
        rerollsLeft.replaceAll((id, left) -> 0);
        abilities().hostBeginFight();
        refreshShields();
        session.title(messages.get("fight.title"), messages.get("fight.subtitle"));
        session.playSound(Sound.ENTITY_ENDER_DRAGON_GROWL, 0.5f, 1.2f);
    }

    private void tickFight() {
        halfTicks++;
        if (halfTicks % 10 == 0) {
            for (Tower tower : towers) {
                tower.anchor();
            }
        }
        if (halfTicks % 20 == 0) {
            secondTick();
            if (phase != Phase.FIGHT) {
                return;
            }
        }
        if (checkRoundWinner()) {
            return;
        }
        long limit = suddenDeath ? settings.suddenDeathTicks() : settings.halfTicks();
        if (!suddenDeath && !climax && settings.climaxTicks() > 0 && limit - halfTicks <= settings.climaxTicks()) {
            startClimax();
        }
        if (halfTicks >= limit) {
            endHalf(HalfEnd.TIME);
        }
    }

    private void secondTick() {
        long now = halfTicks;
        protectedUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
        spawnZoneDamage();
        Tower current = attackableTower();
        if (current == null) {
            return;
        }
        int attackers = attackingTeam();
        boolean attackersNear = false;
        for (Player player : session.alivePlayers()) {
            if (teamIndex(player.getUniqueId()) == attackers && session.isInPlay(player)
                    && current.isNear(player.getLocation())) {
                attackersNear = true;
                break;
            }
        }
        if (!attackersNear && now - current.lastDamageTick() >= settings.regenDelayTicks()
                && current.health() < current.maxHealth()) {
            current.heal(current.maxHealth() * settings.regenPerSecond());
        }
        if (current.attackableSince() >= 0 && now - current.attackableSince() >= settings.defenceTicks()) {
            int defenders = 1 - attackers;
            List<UUID> credited = new ArrayList<>();
            for (UUID member : members(defenders)) {
                if (session.isAlive(member)) {
                    credited.add(member);
                }
            }
            int points = score().defenceSuccess(defenders, credited);
            current.heal(current.maxHealth() * settings.defenceHeal());
            current.attackableFrom(now);
            session.broadcast(messages.prefixed("tower.defended", Messages.tag("team",
                            teams.get(defenders).displayName()), Messages.tag("tower", current.name()),
                    Messages.tag("points", points)));
            session.playSound(Sound.BLOCK_BEACON_POWER_SELECT, 0.7f, 1.3f);
            scored(defenders);
        }
    }

    private void startClimax() {
        climax = true;
        session.title(messages.get("climax.title"), messages.get("climax.subtitle",
                Messages.tag("multiplier", Format.number(settings.climaxTowerMultiplier())),
                Messages.tag("seconds", Format.number(settings.climaxRespawnCutTicks() / 20.0))));
        session.broadcast(messages.prefixed("climax.announce",
                Messages.tag("multiplier", Format.number(settings.climaxTowerMultiplier())),
                Messages.tag("seconds", Format.number(settings.climaxRespawnCutTicks() / 20.0))));
        session.playSound(Sound.ENTITY_WITHER_SPAWN, 0.5f, 1.4f);
        if (hud != null) {
            hud.update();
        }
    }

    /** Spawn camping hurts: enemies inside a team's spawn zone take damage every second. */
    private void spawnZoneDamage() {
        double radius = settings.spawnZoneRadius();
        double damage = settings.spawnZoneDamage();
        if (!(radius > 0) || !(damage > 0)) {
            return;
        }
        int attackers = attackingTeam();
        List<Location> attackSpawns = layout().attackerSpawns();
        List<Location> defendSpawns = layout().defenderSpawns(towersDown);
        for (Player player : session.alivePlayers()) {
            int team = teamIndex(player.getUniqueId());
            if (team < 0 || !session.isInPlay(player)) {
                continue;
            }
            List<Location> enemySpawns = team == attackers ? defendSpawns : attackSpawns;
            if (near(player.getLocation(), enemySpawns, radius)) {
                player.sendActionBar(messages.get("spawn.zone-warning"));
                player.damage(damage, DamageSource.builder(DamageType.MAGIC).build());
            }
        }
    }

    private static boolean near(Location at, List<Location> spots, double radius) {
        for (Location spot : spots) {
            if (spot.getWorld() != null && spot.getWorld().equals(at.getWorld())
                    && spot.distanceSquared(at) <= radius * radius) {
                return true;
            }
        }
        return false;
    }

    /** Points changed for {@code team}: in sudden death any score wins the round; also the win score. */
    private void scored(int team) {
        if (suddenDeath && phase == Phase.FIGHT) {
            roundWon(team, messages.get("result.reason-sudden-death"));
            return;
        }
        checkRoundWinner();
    }

    private boolean checkRoundWinner() {
        if (phase != Phase.FIGHT) {
            return false;
        }
        int reached = score().reachedWinScore();
        if (reached >= 0) {
            roundWon(reached, messages.get("result.reason-score", Messages.tag("score", settings.winScore())));
            return true;
        }
        return false;
    }

    private void endHalf(HalfEnd why) {
        if (phase != Phase.FIGHT) {
            return;
        }
        if (half == 1) {
            abilities().hostEndFight();
            disposeTowers();
            phase = Phase.HALFTIME;
            phaseTicks = 0;
            session.title(messages.get("halftime.title"), messages.get("halftime.subtitle",
                    Messages.tag("a", score().points(0)), Messages.tag("b", score().points(1))));
            session.broadcast(messages.prefixed(why == HalfEnd.CORE ? "halftime.core" : "halftime.time",
                    Messages.tag("seconds", settings.halftimeTicks() / 20)));
            return;
        }
        if (suddenDeath) {
            // Nobody scored in the extra time: the defence held.
            roundWon(1 - attackingTeam(), messages.get("result.reason-sudden-death-held"));
            return;
        }
        int winner = score().timeUpWinner();
        if (winner >= 0) {
            roundWon(winner, messages.get("result.reason-time"));
            return;
        }
        suddenDeath = true;
        halfTicks = 0;
        session.title(messages.get("sudden-death.title"), messages.get("sudden-death.subtitle",
                Messages.tag("seconds", settings.suddenDeathTicks() / 20)));
        session.broadcast(messages.prefixed("sudden-death.announce",
                Messages.tag("seconds", settings.suddenDeathTicks() / 20)));
        session.playSound(Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.6f, 1f);
    }

    private void roundWon(int team, Component reason) {
        if (phase == Phase.OVER || phase == Phase.BREAK) {
            return;
        }
        SiegeScore points = score();
        abilities().hostEndFight();
        disposeTowers();
        points.roundWon(team);
        MatchTeam winner = teams.get(team);
        UUID mvp = points.roundMvp();
        session.title(messages.get("round.won-title", Messages.tag("team", winner.displayName())),
                messages.get("round.won-subtitle", Messages.tag("a", points.points(0)),
                        Messages.tag("b", points.points(1))));
        session.broadcast(messages.prefixed("round.won", Messages.tag("round", round),
                Messages.tag("team", winner.displayName()), Messages.tag("reason", reason),
                Messages.tag("a", points.points(0)), Messages.tag("b", points.points(1))));
        if (mvp != null) {
            session.broadcast(messages.prefixed("round.mvp", Messages.tag("player", session.name(mvp)),
                    Messages.tag("points", points.roundContribution(mvp))));
        }
        session.playSound(Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
        if (points.roundWins(team) >= settings.roundsToWin()) {
            finishMatch(team, messages.get("result.reason-rounds", Messages.tag("a", points.roundWins(0)),
                    Messages.tag("b", points.roundWins(1))));
            return;
        }
        phase = Phase.BREAK;
        phaseTicks = 0;
        session.broadcast(messages.prefixed("round.break", Messages.tag("seconds", settings.roundBreakTicks() / 20),
                Messages.tag("a", points.roundWins(0)), Messages.tag("b", points.roundWins(1))));
    }

    private void finishMatch(int team, Component reason) {
        if (phase == Phase.OVER) {
            return;
        }
        phase = Phase.OVER;
        SiegeScore points = score();
        session.finish(GameResult.builder().team(teams.get(team)).mvp(points.matchMvp()).reason(reason).build());
    }

    // =================================================================== towers

    private void createTowers() {
        int defenders = 1 - attackingTeam();
        MatchTeam team = teams.get(defenders);
        int index = 0;
        for (var site : layout().sites().towers()) {
            Tower tower = new Tower(site, index++, layout().at(site),
                    settings.towerHealth(site.id(), Math.max(1, members(1 - defenders).size())));
            tower.spawn(session, game.towerLook(), team.color(), settings.hitboxScale());
            tower.bar(session.sharedBossBar(Component.text(site.name()), 1f, BossBar.Color.WHITE,
                    BossBar.Overlay.NOTCHED_10));
            towers.add(tower);
        }
        refreshShields();
    }

    /**
     * Keeps the chunks around every tower loaded for the whole match with plugin chunk tickets: the towers' entities are
     * not persistent, so a tower chunk unloading (nobody near it, or not yet: towers spawn before the players arrive)
     * would silently delete the hitbox and the visual.
     */
    private void holdTowerChunks() {
        SiegeLayout placed = layout();
        World world = placed.world();
        for (var site : placed.sites().towers()) {
            Location at = placed.at(site);
            int reach = site.radius() + 2;
            for (int cx = (at.getBlockX() - reach) >> 4; cx <= (at.getBlockX() + reach) >> 4; cx++) {
                for (int cz = (at.getBlockZ() - reach) >> 4; cz <= (at.getBlockZ() + reach) >> 4; cz++) {
                    if (world.addPluginChunkTicket(cx, cz, session.plugin())) {
                        heldChunks.add(new int[] {cx, cz});
                    }
                }
            }
        }
    }

    private void releaseTowerChunks() {
        SiegeLayout placed = layout;
        if (placed == null) {
            return;
        }
        for (int[] chunk : heldChunks) {
            placed.world().removePluginChunkTicket(chunk[0], chunk[1], session.plugin());
        }
        heldChunks.clear();
    }

    private void disposeTowers() {
        for (Tower tower : towers) {
            try {
                tower.dispose();
            } catch (RuntimeException e) {
                session.plugin().getLogger().log(Level.WARNING, "Could not remove tower " + tower.id(), e);
            }
        }
        towers.clear();
    }

    /** Only the first standing tower can be hit; it starts its defence timer when it becomes attackable. */
    private void refreshShields() {
        boolean first = true;
        for (Tower tower : towers) {
            if (tower.isDestroyed()) {
                continue;
            }
            boolean wasShielded = tower.isShielded() || tower.attackableSince() < 0;
            tower.shielded(!first);
            if (first && phase == Phase.FIGHT && wasShielded) {
                tower.attackableFrom(halfTicks);
            }
            first = false;
        }
    }

    /**
     * A hit on a tower's hitbox (melee or ability damage) by {@code attacker}: only attackers in play during the
     * fight hurt it, and only when it is not shielded. Defenders near the tower reduce the damage.
     */
    void hitTower(Tower tower, @Nullable Player attacker, double amount) {
        if (phase != Phase.FIGHT || attacker == null || tower.isDestroyed() || !session.isInPlay(attacker)) {
            return;
        }
        if (teamIndex(attacker.getUniqueId()) != attackingTeam()) {
            return;
        }
        protectedUntil.remove(attacker.getUniqueId());
        if (tower.isShielded()) {
            attacker.sendActionBar(messages.get("tower.shielded"));
            Feedback.play(attacker, Sound.ITEM_SHIELD_BLOCK, 0.6f, 1.2f);
            return;
        }
        double dealt = tower.damage(amount, defenderReduction(tower), attacker.getUniqueId(), halfTicks);
        if (!(dealt > 0)) {
            return;
        }
        if (tower.isDestroyed()) {
            towerDestroyed(tower, attacker);
            return;
        }
        if (tower.warn(halfTicks, settings.warningCooldownTicks())) {
            for (UUID member : members(1 - attackingTeam())) {
                Player defender = Bukkit.getPlayer(member);
                if (defender != null && session.isMember(defender)) {
                    defender.sendActionBar(messages.get("tower.under-attack", Messages.tag("tower", tower.name()),
                            Messages.tag("percent", Math.round(tower.fraction() * 100))));
                    Feedback.play(defender, Sound.BLOCK_BELL_USE, 0.8f, 1.4f);
                }
            }
        }
        int quarter = tower.takeQuarterCrossed();
        if (quarter > 0) {
            session.broadcast(messages.prefixed("tower.health-announce", Messages.tag("tower", tower.name()),
                    Messages.tag("percent", quarter * 25)));
        }
    }

    /** Damage reduction from defenders inside the tower's radius. */
    double defenderReduction(Tower tower) {
        int defenders = 1 - attackingTeam();
        int near = 0;
        for (Player player : session.alivePlayers()) {
            if (teamIndex(player.getUniqueId()) == defenders && session.isInPlay(player)
                    && tower.isNear(player.getLocation())) {
                near++;
            }
        }
        return Math.min(settings.maxDefenderReduction(), near * settings.defenderReduction());
    }

    private void towerDestroyed(Tower tower, Player attacker) {
        int team = attackingTeam();
        int points = score().towerDestroyed(team, tower.id(), climax, tower.topDamager());
        towersDown++;
        session.title(messages.get("tower.destroyed-title", Messages.tag("tower", tower.name())),
                messages.get("tower.destroyed-subtitle", Messages.tag("team", teams.get(team).displayName()),
                        Messages.tag("points", points)));
        session.broadcast(messages.prefixed("tower.destroyed", Messages.tag("tower", tower.name()),
                Messages.tag("player", session.publicName(attacker.getUniqueId())),
                Messages.tag("team", teams.get(team).displayName()), Messages.tag("points", points)));
        session.playSound(Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 0.8f);
        firework(tower.base().add(0, 4, 0), teams.get(team));
        if (tower.bar() != null) {
            tower.bar().hideAll();
        }
        refreshShields();
        Tower next = attackableTower();
        if (next != null) {
            session.broadcast(messages.prefixed("tower.next", Messages.tag("tower", next.name())));
        }
        scored(team);
        if (phase == Phase.FIGHT && next == null) {
            endHalf(HalfEnd.CORE);
        }
    }

    private void firework(Location at, MatchTeam team) {
        if (at.getWorld() == null) {
            return;
        }
        try {
            Firework firework = at.getWorld().spawn(at, Firework.class, spawned -> {
                spawned.setPersistent(false);
                FireworkMeta meta = spawned.getFireworkMeta();
                meta.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE)
                        .withColor(team.rgb()).withFlicker().build());
                meta.setPower(0);
                spawned.setFireworkMeta(meta);
            });
            session.track(firework);
            session.runLater(1, () -> {
                try {
                    firework.detonate();
                } catch (RuntimeException e) {
                    firework.remove();
                }
            });
        } catch (RuntimeException e) {
            session.plugin().getLogger().log(Level.FINE, "Could not spawn a tower firework", e);
        }
    }

    // =================================================================== spawn protection & spectating

    /** True while the player is protected after a respawn. */
    public boolean isProtected(Player player) {
        Long until = protectedUntil.get(player.getUniqueId());
        return until != null && until > halfTicks;
    }

    /** The protected player attacked: the protection ends. */
    void attacked(Player attacker) {
        if (protectedUntil.remove(attacker.getUniqueId()) != null) {
            attacker.sendActionBar(messages.get("spawn.protection-ended"));
        }
    }

    private void watchTeammate(Player victim) {
        if (!victim.isOnline() || victim.getGameMode() != GameMode.SPECTATOR
                || !session.isRespawning(victim.getUniqueId())) {
            return;
        }
        int team = teamIndex(victim.getUniqueId());
        for (UUID member : team < 0 ? List.<UUID>of() : members(team)) {
            Player mate = Bukkit.getPlayer(member);
            if (mate != null && !mate.equals(victim) && session.isInPlay(mate)) {
                try {
                    victim.setSpectatorTarget(mate);
                } catch (RuntimeException e) {
                    victim.teleport(mate.getLocation());
                }
                return;
            }
        }
    }

    // =================================================================== rerolls

    /** Outcome of {@link #reroll(Player)}. */
    public enum RerollResult { OK, NOT_PARTICIPANT, WRONG_PHASE, NONE_LEFT, NO_OPTIONS }

    /** Rerolls the player's ability for this round (round prep only, rerolls left). */
    public RerollResult reroll(Player player) {
        UUID id = player.getUniqueId();
        SuperpowerMatch hosted = abilities;
        Participant participant = hosted == null ? null : hosted.participant(id);
        if (participant == null || participant.abilityId() == null || !session.isInPlay(player)) {
            return RerollResult.NOT_PARTICIPANT;
        }
        if (phase != Phase.PREP || half != 1) {
            return RerollResult.WRONG_PHASE;
        }
        if (rerollsLeft.getOrDefault(id, 0) <= 0) {
            return RerollResult.NONE_LEFT;
        }
        int team = teamIndex(id);
        List<String> mates = new ArrayList<>();
        List<String> enemies = new ArrayList<>();
        for (Participant other : hosted.participants()) {
            if (other.id().equals(id) || other.abilityId() == null || !session.isAlive(other.id())) {
                continue;
            }
            (teamIndex(other.id()) == team ? mates : enemies).add(other.abilityId());
        }
        String next = SiegeAbilities.reroll(participant.abilityId(), roundPool, mates, enemies, session.random());
        if (next == null) {
            return RerollResult.NO_OPTIONS;
        }
        rerollsLeft.merge(id, -1, Integer::sum);
        hosted.hostAssign(Map.of(id, next));
        return RerollResult.OK;
    }

    /** Korean feedback for a reroll attempt. */
    public void reportReroll(Player player, RerollResult result) {
        String key = switch (result) {
            case OK -> "reroll.done";
            case NOT_PARTICIPANT -> "reroll.not-participant";
            case WRONG_PHASE -> "reroll.wrong-phase";
            case NONE_LEFT -> "reroll.none-left";
            case NO_OPTIONS -> "reroll.no-options";
        };
        messages.send(player, key, Messages.tag("count", rerollsLeft.getOrDefault(player.getUniqueId(), 0)));
        Feedback.play(player, result == RerollResult.OK ? Sound.BLOCK_ENCHANTMENT_TABLE_USE
                : Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 1.2f);
    }

    // =================================================================== queries

    public GameSession session() {
        return session;
    }

    SiegeGame game() {
        return game;
    }

    Messages messages() {
        return messages;
    }

    public SiegeSettings settings() {
        return settings;
    }

    public Phase phase() {
        return phase;
    }

    public int round() {
        return round;
    }

    public int half() {
        return half;
    }

    public boolean isClimax() {
        return climax;
    }

    public boolean isSuddenDeath() {
        return suddenDeath;
    }

    public long halfTicks() {
        return halfTicks;
    }

    long phaseTicks() {
        return phaseTicks;
    }

    long prepTicks() {
        return prepTicks;
    }

    /** Ticks left of the current phase (fight, prep, half time, break). */
    public long ticksLeft() {
        return switch (phase) {
            case PREP -> Math.max(0, prepTicks - phaseTicks);
            case FIGHT -> Math.max(0, (suddenDeath ? settings.suddenDeathTicks() : settings.halfTicks()) - halfTicks);
            case HALFTIME -> Math.max(0, settings.halftimeTicks() - phaseTicks);
            case BREAK -> Math.max(0, settings.roundBreakTicks() - phaseTicks);
            case OVER -> 0;
        };
    }

    public SiegeScore score() {
        if (score == null) {
            throw new IllegalStateException("The match has not started");
        }
        return score;
    }

    public SuperpowerMatch abilities() {
        if (abilities == null) {
            throw new IllegalStateException("The match has not started");
        }
        return abilities;
    }

    public SiegeLayout layout() {
        if (layout == null) {
            throw new IllegalStateException("The match has not started");
        }
        return layout;
    }

    public List<MatchTeam> teams() {
        return List.copyOf(teams);
    }

    public List<Tower> towers() {
        return List.copyOf(towers);
    }

    public int towersDown() {
        return towersDown;
    }

    /** 0 (A) attacks in the first half, 1 (B) in the second. */
    public int attackingTeam() {
        return half == 2 ? 1 : 0;
    }

    /** 0 for team A, 1 for team B, -1 otherwise. */
    public int teamIndex(UUID player) {
        for (int i = 0; i < teams.size(); i++) {
            if (teams.get(i).contains(player)) {
                return i;
            }
        }
        return -1;
    }

    public int rerollsLeft(Player player) {
        return rerollsLeft.getOrDefault(player.getUniqueId(), 0);
    }

    /** The tower whose hitbox is {@code entity}, or null. */
    public @Nullable Tower towerOf(@Nullable Entity entity) {
        if (entity == null) {
            return null;
        }
        for (Tower tower : towers) {
            if (tower.isHitbox(entity)) {
                return tower;
            }
        }
        return null;
    }

    /** The first standing tower (the one the attackers can hit), or null. */
    public @Nullable Tower attackableTower() {
        for (Tower tower : towers) {
            if (!tower.isDestroyed()) {
                return tower;
            }
        }
        return null;
    }

    /** Where a player (re)spawns right now. */
    public Location spawnFor(UUID player) {
        int team = teamIndex(player);
        List<Location> spots = team == attackingTeam() ? layout().attackerSpawns()
                : layout().defenderSpawns(towersDown);
        int slot = Math.max(0, members(Math.max(0, team)).indexOf(player));
        return spotFor(spots, slot);
    }

    /**
     * The {@code index}-th spawn of a team: cycles through the map's spots; every further lap stands one block to the
     * side (alternating left and right of the spot's facing), so a big team does not stack up on one block.
     */
    static Location spotFor(List<Location> spots, int index) {
        Location spot = spots.get(Math.floorMod(index, spots.size())).clone();
        int lap = Math.max(0, index) / spots.size();
        if (lap > 0) {
            double side = (lap % 2 == 1 ? 1 : -1) * ((lap + 1) / 2);
            double yaw = Math.toRadians(spot.getYaw());
            // Perpendicular to the facing direction (-sin, cos).
            spot.add(Math.cos(yaw) * side, 0, Math.sin(yaw) * side);
        }
        return spot;
    }

    List<UUID> members(int team) {
        return team < 0 || team >= teams.size() ? List.of() : new ArrayList<>(teams.get(team).members());
    }

    private int aliveMembers(int team) {
        int alive = 0;
        for (UUID member : members(team)) {
            if (session.isAlive(member)) {
                alive++;
            }
        }
        return alive;
    }

    /** The ability of a participant, for the HUD. */
    @Nullable AbilityInfo abilityInfo(UUID player) {
        Participant participant = abilities == null ? null : abilities.participant(player);
        String id = participant == null ? null : participant.abilityId();
        AbilityRegistry.Entry entry = id == null ? null : game.superpower().registry().get(id);
        return entry == null ? null : entry.info();
    }

    Component abilityName(UUID player) {
        AbilityInfo info = abilityInfo(player);
        return info == null ? Component.text("?") : AbilityTexts.name(info);
    }

    Hub hub() {
        return game.hub();
    }

    // =================================================================== testing

    /** A team started empty (started alone to try the mode): only score and time end the rounds. */
    public boolean isSolo() {
        return solo;
    }

    /** Ends the current half as if its time ran out (tests and admins). */
    public void expireHalf() {
        if (phase == Phase.FIGHT) {
            halfTicks = suddenDeath ? settings.suddenDeathTicks() : settings.halfTicks();
            endHalf(HalfEnd.TIME);
        }
    }
}
