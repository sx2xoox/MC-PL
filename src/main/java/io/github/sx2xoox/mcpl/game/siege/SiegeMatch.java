package io.github.sx2xoox.mcpl.game.siege;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.core.Hub;
import io.github.sx2xoox.mcpl.core.combat.CombatTracker;
import io.github.sx2xoox.mcpl.core.command.Permissions;
import io.github.sx2xoox.mcpl.core.game.DeathOutcome;
import io.github.sx2xoox.mcpl.core.game.GameInstance;
import io.github.sx2xoox.mcpl.core.game.GameResult;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.game.MatchTeam;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import io.github.sx2xoox.mcpl.core.ui.Feedback;
import io.github.sx2xoox.mcpl.core.ui.Format;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.game.siege.balance.SiegeBalance;
import io.github.sx2xoox.mcpl.game.siege.balance.SiegeBalanceSettings;
import io.github.sx2xoox.mcpl.game.siege.balance.SiegeModifiers;
import io.github.sx2xoox.mcpl.game.siege.bot.SiegeBots;
import io.github.sx2xoox.mcpl.game.siege.creature.HitMaterial;
import io.github.sx2xoox.mcpl.game.siege.tower.Footprint;
import io.github.sx2xoox.mcpl.game.siege.tower.Tower;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityRegistry;
import io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttack;
import io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttackSettings;
import io.github.sx2xoox.mcpl.game.superpower.runtime.MatchHost;
import io.github.sx2xoox.mcpl.game.superpower.runtime.Participant;
import io.github.sx2xoox.mcpl.game.superpower.runtime.SuperpowerMatch;
import io.github.sx2xoox.mcpl.game.superpower.ui.AbilityTexts;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * One match of 공성전 (see docs/siege.md): two sides, BLUE and RED, each with two towers per lane and a core, race for
 * points in timed rounds (destroying the enemy core wins the round at once) until a side won {@code rounds-to-win}
 * rounds. The abilities run in a hosted {@link SuperpowerMatch}; this class owns the flow, the score
 * ({@link SiegeScore}), the structures, respawns and spawn protection, and offers the hooks other features
 * ({@link SiegeModule}s: minions, jungle, gold and shop) build on: {@link #side}, {@link #sites}, {@link #layout},
 * {@link #addScore}, {@link #addXp}, {@link #gold}, {@link #addGold}, {@link #damageStructure}, {@link #guidance},
 * {@link #sounds}, {@link #damageNumbers}.
 */
public final class SiegeMatch implements GameInstance {

    /** The config section of the basic attacks (평타) under {@code games.siege}. */
    public static final String BASIC_ATTACKS = "basic-attacks";

    /** Phases of the match (all inside the hub's RUNNING state). */
    public enum Phase {
        /** Before a round's fight: everyone at their spawn, invulnerable, rerolls open. */
        PREP,
        /** A round is being played. */
        FIGHT,
        /** Between rounds. */
        BREAK,
        /** The match is decided. */
        OVER
    }

    private final SiegeGame game;
    private final GameSession session;
    private final Messages messages;
    private SiegeSettings settings;
    private @Nullable SuperpowerMatch abilities;
    private @Nullable LaneLayout layout;
    private @Nullable SiegeScore score;
    private @Nullable SiegeHud hud;
    private @Nullable SiegeProgress progress;
    private @Nullable Guidance guidance;
    /** games.siege.hunger.*: full food and a fixed saturation for every fighter while the match runs. */
    private boolean hungerLocked;
    private float hungerSaturation = 1f;
    private @Nullable SiegeSounds sounds;
    private @Nullable DamageNumbers damageNumbers;
    private @Nullable HitFeel hitFeel;
    private SiegeEconomy economy = SiegeEconomy.NONE;
    /** Stat modifiers of items and buffs ({@link #modifiers()}) and the ability balance read from them. */
    private final SiegeModifiers modifiers = new SiegeModifiers();
    private SiegeBalance balance = new SiegeBalance(SiegeBalanceSettings.defaults(), modifiers);
    /** The basic attacks (평타) of the ability weapons, read at the start ({@code games.siege.basic-attacks}). */
    private BasicAttackSettings basicAttacks = BasicAttackSettings.defaults();
    private final List<SiegeModule> modules = new ArrayList<>();
    /** The built-in lane systems (minions, jungle, gold, shop), null if they failed to start. */
    private @Nullable LaneSystemsModule lanes;
    private final List<MatchTeam> teams = new ArrayList<>();
    /** The side of each team (index as in {@link #teams}). */
    private final LaneSites.Side[] sides = {LaneSites.Side.BLUE, LaneSites.Side.RED};
    private final List<Tower> towers = new ArrayList<>();
    /** Session tick until which a respawned player is protected. */
    private final Map<UUID, Long> protectedUntil = new HashMap<>();
    private final Map<UUID, Integer> rerollsLeft = new HashMap<>();
    /** Chunks held loaded around the structures ({@link #holdChunks}), as {@code x, z} pairs. */
    private final List<int[]> heldChunks = new ArrayList<>();
    /** The round each player who is waiting to respawn died in ({@link #roundSerial}). */
    private final Map<UUID, Integer> diedInRound = new HashMap<>();
    private int roundSerial;
    private List<String> roundPool = List.of();
    private Phase phase = Phase.PREP;
    private long phaseTicks;
    private long prepTicks;
    /** Ticks of the current round's fight clock (restarts for the overtime). */
    private long fightTicks;
    private int round;
    private boolean overtime;
    private boolean climax;
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
        balance = new SiegeBalance(SiegeBalanceSettings.read(session.gameConfig()), modifiers);
        basicAttacks = BasicAttackSettings.read(session.gameConfig().getConfigurationSection(BASIC_ATTACKS));
        if (session.teams().size() < 2) {
            session.abort(messages.get("start.no-teams"));
            return;
        }
        teams.addAll(session.teams().subList(0, 2));
        assignSides();
        SiegeBots.Setup botSetup = game.takeBotRequest();
        List<SiegeBots.Seat> seats = botSetup == null ? List.of() : SiegeBots.enlist(session, botSetup);
        solo = teams.stream().anyMatch(team -> team.members().isEmpty());
        layout = game.layoutFor(session.arena());
        holdChunks();
        score = new SiegeScore(settings, this::teamIndex);
        SiegeHudSettings hudSettings = SiegeHudSettings.read(session.gameConfig());
        hungerLocked = hudSettings.lockHunger();
        hungerSaturation = hudSettings.saturation();
        sounds = new SiegeSounds(session);
        guidance = new Guidance(session, messages);
        guidance.enabled(hudSettings.guidance());
        // no standing "where to go" line: the action bar only carries short alerts (a tower under attack, a shield)
        damageNumbers = new DamageNumbers(session, hudSettings.damageNumberRange());
        damageNumbers.enabled(hudSettings.damageNumbers());
        hitFeel = new HitFeel(session, sounds, damageNumbers, HitFeelSettings.read(session.gameConfig()),
                () -> abilities == null ? BasicAttack.Accent.NONE : abilities.context().basicHitAccent(),
                () -> abilities != null && abilities.context().isAbilityDamage());
        progress = new SiegeProgress(this, SiegeProgressSettings.read(session.gameConfig()));
        SuperpowerMatch hosted = new SuperpowerMatch(game.superpower(), session, new Host());
        abilities = hosted;
        hosted.start();
        if (!session.isRunning()) {
            return;
        }
        session.registerListener(new SiegeListener(this));
        modules.addAll(game.createModules(this));
        try {
            lanes = new LaneSystemsModule(this);
            modules.add(lanes);
        } catch (RuntimeException e) {
            session.plugin().getLogger().log(Level.WARNING, "The siege lane systems could not start", e);
        }
        if (botSetup != null) {
            try {
                modules.add(new SiegeBots(this, botSetup, seats));
            } catch (RuntimeException e) {
                session.plugin().getLogger().log(Level.WARNING, "The siege bots could not start", e);
            }
        }
        hud = new SiegeHud(this, hudSettings);
        startRound();
    }

    /** The team of the hub's "파랑" palette entry plays BLUE, "빨강" RED; otherwise the first team is BLUE. */
    private void assignSides() {
        boolean swap = "blue".equals(teams.get(1).id()) || "red".equals(teams.get(0).id());
        if ("blue".equals(teams.get(0).id()) || "red".equals(teams.get(1).id())) {
            swap = false;
        }
        sides[0] = swap ? LaneSites.Side.RED : LaneSites.Side.BLUE;
        sides[1] = swap ? LaneSites.Side.BLUE : LaneSites.Side.RED;
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
            case BREAK -> {
                if (phaseTicks >= settings.roundBreakTicks()) {
                    startRound();
                }
            }
            case OVER -> {
            }
        }
        if (!stopped && session.isRunning() && hud != null) {
            if (session.tick() % 10 == 0) {
                hud.update();
            }
            hud.tick();
            guidance().tick();
        }
        if (!stopped && hungerLocked) {
            for (Player player : session.participants()) {
                player.setFoodLevel(20);
                player.setSaturation(hungerSaturation);
                player.setExhaustion(0);
            }
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
        if (hud != null) {
            hud.playerLeft(player);
        }
        abilities().onQuit(player);
    }

    @Override
    public void onEliminated(Player player, @Nullable Player killer) {
        abilities().onEliminated(player, killer);
    }

    @Override
    public @Nullable Component playerTag(UUID player) {
        if (session.isBot(player)) {
            for (SiegeModule module : modules) {
                Component tag = module.fighterTag(player);
                if (tag != null) {
                    return tag;
                }
            }
            return null;
        }
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
            eachModule(SiegeModule::stop);
            if (hud != null) {
                hud.dispose();
            }
            disposeTowers();
            if (progress != null) {
                progress.clear();
            }
            releaseChunks();
            if (abilities != null) {
                abilities.stop();
            }
        } finally {
            game.matchEnded(this);
        }
    }

    private void eachModule(Consumer<SiegeModule> call) {
        for (SiegeModule module : List.copyOf(modules)) {
            try {
                call.accept(module);
            } catch (RuntimeException e) {
                session.plugin().getLogger().log(Level.WARNING, "A siege module failed: " + module, e);
            }
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

        /** 공성전: the sword is the ability item (right-click = active skill, F = ultimate). */
        @Override
        public boolean abilityWeapon() {
            return true;
        }

        /** 공성전: left-clicks fire each ability's basic attack (평타, {@code games.siege.basic-attacks}). */
        @Override
        public BasicAttackSettings basicAttacks() {
            return basicAttacks;
        }

        /** Basic attacks grow with the level like ability damage does ({@code balance.damage-per-level}). */
        @Override
        public double basicAttackMultiplier(Player attacker, LivingEntity target) {
            return balance.levelFactor(balanceLevel(attacker.getUniqueId()));
        }

        @Override
        public List<LivingEntity> extraTargets() {
            List<LivingEntity> targets = towerTargets();
            for (SiegeModule module : modules) {
                targets.addAll(module.targets());
            }
            return targets;
        }

        @Override
        public boolean isExtraTarget(Entity entity) {
            if (towerOf(entity) != null) {
                return true;
            }
            for (SiegeModule module : modules) {
                if (module.isTarget(entity)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean isExtraEnemy(Player owner, Entity entity) {
            if (towerOf(entity) != null) {
                return isTowerEnemy(owner, entity);
            }
            for (SiegeModule module : modules) {
                if (module.isTarget(entity)) {
                    return module.isEnemy(owner, entity);
                }
            }
            return false;
        }

        /**
         * Gauge for hits on more than players ({@code games.siege.gauge.*}): 봇전 bots, minions and monsters. Structures
         * take their hits themselves ({@link #hitTower}), with {@link SiegeSettings#gaugeStructureFactor()}.
         */
        @Override
        public double gaugeDealtFactor(Player attacker, LivingEntity target) {
            if (target instanceof Player) {
                return 1.0;
            }
            if (towerOf(target) != null) {
                return settings.gaugeStructureFactor();
            }
            if (fighterOf(target) != null) {
                return settings.gaugeBotFactor();
            }
            return isExtraTarget(target) ? settings.gaugeCreatureFactor() : 0.0;
        }

        @Override
        public int abilityLevel(Player player) {
            return balanceLevel(player.getUniqueId());
        }

        /**
         * Buildings, minions and jungle creatures keep their size against shrinking and growing skills (텐구): their
         * model is drawn at a fixed size; 봇전 bots fight like players and may be shrunk.
         */
        @Override
        public boolean keepsSize(Entity entity) {
            return towerOf(entity) != null || fighterOf(entity) == null;
        }

        /** A building is aimed at, reached and seen as drawn: its whole box, not only its hitbox. */
        @Override
        public @Nullable BoundingBox targetBounds(Entity entity) {
            Tower tower = towerOf(entity);
            return tower == null ? null : tower.bounds();
        }

        /** The buildings' barrier walls stop bodies only: rays and sight pass them (to the building inside). */
        @Override
        public boolean isSeeThrough(Block block) {
            return isStructureWall(block);
        }

        @Override
        public double abilityDamageMultiplier(Player attacker, LivingEntity target) {
            return abilityDamageMultiplier(attacker, target, false);
        }

        /**
         * Players, summons and 봇전 bots (fighters like players) take the balance's factor; structures and the other
         * modules' targets (minions, monsters) keep their own numbers but for the ability's objective correction.
         */
        @Override
        public double abilityDamageMultiplier(Player attacker, LivingEntity target, boolean ultimate) {
            UUID fighter = target instanceof Player player ? player.getUniqueId() : fighterOf(target);
            UUID id = attacker.getUniqueId();
            Participant participant = abilities == null ? null : abilities.participant(id);
            if (fighter == null && isExtraTarget(target)) {
                return balance.objectiveMultiplier(participant == null ? null : participant.abilityId());
            }
            return balance.damageMultiplier(participant == null ? null : participant.abilityId(), balanceLevel(id),
                    id, fighter, ultimate);
        }

        @Override
        public double abilityParam(Player owner, String abilityId, String key, double value) {
            return balance.param(abilityId, balanceLevel(owner.getUniqueId()), key, value);
        }

        @Override
        public double cooldownMultiplier(Player player) {
            return balance.cooldownMultiplier(player.getUniqueId());
        }
    }

    /** The level abilities scale with: the player's level ({@link #level}), 1 while levels are off or before start. */
    private int balanceLevel(UUID player) {
        return progress == null || !progress.settings().levelsEnabled() ? 1 : progress.levels().level(player);
    }

    /**
     * Per-player stat modifiers (skill damage, skill damage taken, cooldown) for shop items and buffs: set them with
     * a key of their source and remove them when it ends; ability hits and cooldowns read them at once.
     */
    public SiegeModifiers modifiers() {
        return modifiers;
    }

    /** The ability balance of this match (global factor, corrections, level scaling, perks, modifiers). */
    public SiegeBalance balance() {
        return balance;
    }

    private DeathOutcome hostDeath(Player victim, @Nullable Player killer) {
        UUID id = victim.getUniqueId();
        protectedUntil.remove(id);
        UUID killerId = killer == null ? null : killer.getUniqueId();
        if (killerId == null) {
            // A 봇전 bot that hit the player last gets the kill (the hub credits it the same way).
            UUID last = session.combat().lastAttackerId(id, CombatTracker.DEFAULT_WINDOW_TICKS);
            killerId = last != null && session.isBot(last) ? last : null;
        }
        creditKill(id, killerId);
        diedInRound.put(id, roundSerial);
        int delay = respawnTicks(id);
        Location spawn = spawnFor(id);
        session.runLater(2, () -> watchTeammate(victim));
        return DeathOutcome.respawn(spawn, delay);
    }

    /**
     * Scores a death during the fight: the death, the kill points of {@code killerId} (a player or a bot of the other
     * team; null: nobody) and the assists, experience, the modules' kill rewards.
     */
    private void creditKill(UUID id, @Nullable UUID killerId) {
        if (phase != Phase.FIGHT) {
            return;
        }
        SiegeScore.Kill kill = score().kill(id, killerId, session.tick());
        int killerTeam = killerId == null ? -1 : teamIndex(killerId);
        if (killerId != null && killerTeam >= 0 && killerTeam != teamIndex(id)) {
            progress().kill(killerId, kill.assisters());
            Player killer = Bukkit.getPlayer(killerId);
            if (killer != null) {
                sounds().kill(killer);
                guidance().suggest(killer, messages.get("score.kill-points", Messages.tag("points", kill.points())),
                        Guidance.INFO, 40);
            }
            for (UUID assister : kill.assisters()) {
                Player helper = Bukkit.getPlayer(assister);
                if (helper != null) {
                    guidance().suggest(helper, messages.get("score.assist-points",
                            Messages.tag("points", settings.assistPoints())), Guidance.INFO, 40);
                }
            }
            UUID credited = killerId;
            eachModule(module -> module.playerKilled(id, credited, kill.assisters()));
            scored(killerTeam);
        } else {
            eachModule(module -> module.playerKilled(id, null, List.of()));
        }
    }

    /**
     * A 봇전 bot died ({@code killer}: the player or bot credited, null for none): scored like a player's death
     * (kill points, assists, gold, experience). Its module respawns it after {@link #respawnTicks}.
     */
    public void fighterKilled(UUID bot, @Nullable UUID killer) {
        creditKill(bot, killer);
    }

    /** The respawn delay (ticks) of a participant now: the deaths of this round, then what the modules change. */
    public int respawnTicks(UUID id) {
        int delay = settings.respawnDelay(score().roundDeaths(id), level(id));
        for (SiegeModule module : modules) {
            delay = Math.max(20, module.respawnTicks(id, delay));
        }
        return delay;
    }

    private void hostRespawn(Player player) {
        player.teleport(spawnFor(player.getUniqueId()));
        Integer died = diedInRound.remove(player.getUniqueId());
        if (died != null && died != roundSerial) {
            // Died before the round changed: the kit and health reset the others got at the change.
            abilities().hostResetPlayer(player);
        }
        progress().respawned(player);
        if (phase == Phase.FIGHT) {
            protectedUntil.put(player.getUniqueId(), session.tick() + settings.protectionTicks());
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
        if (phase == Phase.PREP && player.isSneaking()) {
            reportReroll(player, reroll(player));
            return true;
        }
        if (phase == Phase.PREP && rerollsLeft.getOrDefault(player.getUniqueId(), 0) > 0) {
            guidance().suggest(player, messages.get("reroll.hint",
                    Messages.tag("count", rerollsLeft.getOrDefault(player.getUniqueId(), 0))), Guidance.INFO);
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

    /** A structure is an enemy of the other side only, while the fight runs and it can be hit (no shield). */
    public boolean isTowerEnemy(Player owner, Entity entity) {
        Tower tower = towerOf(entity);
        LaneSites.Side side = side(owner.getUniqueId());
        return tower != null && phase == Phase.FIGHT && !tower.isDestroyed() && !tower.isShielded()
                && side != null && side != tower.owner();
    }

    // =================================================================== rounds

    private void startRound() {
        if (!session.isRunning()) {
            return;
        }
        round++;
        score().newRound();
        economy.newRound();
        assignAbilities();
        if (!session.isRunning()) {
            return;
        }
        SuperpowerMatch hosted = abilities();
        hosted.hostEndFight();
        roundSerial++;
        overtime = false;
        climax = false;
        fightTicks = 0;
        protectedUntil.clear();
        guidance().clear();
        progress().newRound();
        disposeTowers();
        createTowers();
        int[] next = {0, 0};
        for (Player player : session.alivePlayers()) {
            int team = teamIndex(player.getUniqueId());
            if (team < 0) {
                continue;
            }
            Location spot = spotFor(layout().spawns(sides[team]), next[team]++);
            player.setFallDistance(0f);
            player.teleport(spot);
            hosted.hostResetPlayer(player);
        }
        phase = Phase.PREP;
        phaseTicks = 0;
        prepTicks = settings.roundPrepTicks();
        lastCountdown = Integer.MAX_VALUE;
        title(messages.get("round.title", Messages.tag("round", round)), messages.get("round.subtitle",
                Messages.tag("a", score().roundWins(0)), Messages.tag("b", score().roundWins(1))));
        session.broadcast(messages.prefixed("round.started", Messages.tag("round", round),
                Messages.tag("minutes", Format.number(settings.roundTicks() / 1200.0))));
        if (settings.rerolls() > 0) {
            session.broadcast(messages.prefixed("reroll.announce", Messages.tag("count", settings.rerolls())));
        }
        sounds().roundStart(session.viewers());
        if (hud != null && round == 1) {
            hud.tutorial();
        }
        eachModule(module -> module.roundStarted(round));
        if (prepTicks <= 0) {
            beginFight();
        }
    }

    private void assignAbilities() {
        SuperpowerMatch hosted = abilities();
        AbilityRegistry registry = game.superpower().registry();
        List<String> enabled = new ArrayList<>(game.superpower().abilities().snapshot().enabledIds());
        enabled.removeIf(id -> registry.get(id) == null);
        List<UUID> first = players(0);
        List<UUID> second = players(1);
        int biggest = Math.max(first.size(), second.size());
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
        Map<UUID, String> assignment = SiegeAbilities.assign(List.of(first, second), roundPool, session.random());
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
            }
            sounds().countdown(session.viewers(), seconds);
        }
    }

    private void beginFight() {
        phase = Phase.FIGHT;
        phaseTicks = 0;
        fightTicks = 0;
        rerollsLeft.replaceAll((id, left) -> 0);
        abilities().hostBeginFight();
        refreshShields();
        title(messages.get("fight.title"), messages.get("fight.subtitle"));
        sounds().fightStart(session.viewers());
        eachModule(SiegeModule::fightStarted);
    }

    private void tickFight() {
        fightTicks++;
        long now = session.tick();
        if (fightTicks % 10 == 0) {
            for (Tower tower : towers) {
                tower.anchor();
            }
        }
        if (fightTicks % 5 == 0) {
            towerShots(now);
            fallenOut();
        }
        if (fightTicks % 20 == 0) {
            secondTick(now);
        }
        if (phase != Phase.FIGHT) {
            return;
        }
        long tick = fightTicks;
        eachModule(module -> module.tick(tick));
        if (phase != Phase.FIGHT) {
            return;
        }
        long limit = overtime ? settings.overtimeTicks() : settings.roundTicks();
        if (!overtime && !climax && settings.climaxTicks() > 0 && limit - fightTicks <= settings.climaxTicks()) {
            startClimax();
        }
        if (fightTicks >= limit) {
            timeUp();
        }
    }

    /**
     * The height below which a fighter has fallen off the map (a drop or the edge into the void): well under the floor
     * of the lowest spawn.
     */
    public double fallLine() {
        int lowest = Integer.MAX_VALUE;
        for (LaneSites.Side side : LaneSites.Side.values()) {
            for (LaneSites.Spot spot : sites().spawns(side)) {
                lowest = Math.min(lowest, spot.y());
            }
        }
        return lowest - FALL_DEPTH;
    }

    /** How far under the lowest spawn a fall becomes a death. */
    private static final int FALL_DEPTH = 12;

    /**
     * Players who fell off the map die at once (out of the world) instead of after the long fall to the void, so the
     * enemy who knocked them off still gets the kill (the last attacker of the last 10 seconds).
     */
    private void fallenOut() {
        double line = fallLine();
        for (Player player : session.alivePlayers()) {
            if (player.getY() < line && session.isInPlay(player) && !player.isDead()) {
                player.damage(1_000_000, DamageSource.builder(DamageType.OUT_OF_WORLD).build());
            }
        }
    }

    private void secondTick(long now) {
        protectedUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
        spawnZoneDamage();
        progress().second();
        for (Tower tower : towers) {
            if (tower.isDestroyed() || tower.isShielded() || tower.health() >= tower.maxHealth()
                    || now - tower.lastDamageTick() < settings.regenDelayTicks() || enemyNear(tower)) {
                continue;
            }
            tower.heal(tower.maxHealth() * settings.regenPerSecond());
        }
    }

    private boolean enemyNear(Tower tower) {
        for (Player player : session.alivePlayers()) {
            LaneSites.Side side = side(player.getUniqueId());
            if (side != null && side != tower.owner() && session.isInPlay(player) && tower.isNear(player.getLocation())) {
                return true;
            }
        }
        LaneSites.Side enemy = tower.owner() == LaneSites.Side.BLUE ? LaneSites.Side.RED : LaneSites.Side.BLUE;
        for (SiegeModule module : modules) {
            for (LivingEntity fighter : module.fighters(enemy)) {
                if (tower.isNear(fighter.getLocation())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** "마지막 3분": an announcement and a red timer; the rules stay the same. */
    private void startClimax() {
        climax = true;
        title(messages.get("climax.title"), messages.get("climax.subtitle",
                Messages.tag("minutes", Format.number(settings.climaxTicks() / 1200.0))));
        sounds().alarm(session.viewers());
    }

    /** Spawn camping hurts: enemies inside a side's spawn area take damage every second. */
    private void spawnZoneDamage() {
        double radius = settings.spawnZoneRadius();
        double damage = settings.spawnZoneDamage();
        if (!(radius > 0) || !(damage > 0)) {
            return;
        }
        for (Player player : session.alivePlayers()) {
            LaneSites.Side side = side(player.getUniqueId());
            if (side == null || !session.isInPlay(player)) {
                continue;
            }
            LaneSites.Side enemy = side == LaneSites.Side.BLUE ? LaneSites.Side.RED : LaneSites.Side.BLUE;
            if (near(player.getLocation(), layout().spawns(enemy), radius)) {
                guidance().suggest(player, messages.get("spawn.zone-warning"), Guidance.URGENT, 30);
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

    /** Points changed for {@code team}: in the overtime any score wins the round. */
    private void scored(int team) {
        if (overtime && phase == Phase.FIGHT && team >= 0) {
            roundWon(team, messages.get("result.reason-overtime"));
        }
    }

    private void timeUp() {
        if (phase != Phase.FIGHT) {
            return;
        }
        int winner = score().timeUpWinner();
        if (winner >= 0) {
            roundWon(winner, messages.get(overtime ? "result.reason-overtime-time" : "result.reason-time"));
            return;
        }
        if (overtime) {
            int sturdier = sturdierTeam();
            roundWon(sturdier >= 0 ? sturdier : session.random().nextInt(2),
                    messages.get(sturdier >= 0 ? "result.reason-structures" : "result.reason-coin"));
            return;
        }
        overtime = true;
        fightTicks = 0;
        title(messages.get("overtime.title"), messages.get("overtime.subtitle",
                Messages.tag("seconds", settings.overtimeTicks() / 20)));
        session.broadcast(messages.prefixed("overtime.announce", Messages.tag("seconds", settings.overtimeTicks() / 20)));
        sounds().alarm(session.viewers());
    }

    /** The team whose structures kept more health in all (fractions summed), or -1 on a tie. */
    int sturdierTeam() {
        double[] left = new double[2];
        for (Tower tower : towers) {
            int team = teamOf(tower.owner());
            if (team >= 0 && !tower.isDestroyed()) {
                left[team] += tower.fraction();
            }
        }
        if (Math.abs(left[0] - left[1]) < 1.0E-9) {
            return -1;
        }
        return left[0] > left[1] ? 0 : 1;
    }

    private void roundWon(int team, Component reason) {
        if (phase == Phase.OVER || phase == Phase.BREAK) {
            return;
        }
        SiegeScore points = score();
        abilities().hostEndFight();
        eachModule(SiegeModule::roundEnded);
        disposeTowers();
        points.roundWon(team);
        MatchTeam winner = teams.get(team);
        UUID mvp = points.roundMvp();
        title(messages.get("round.won-title", Messages.tag("team", winner.displayName())),
                messages.get("round.won-subtitle", Messages.tag("a", points.points(0)),
                        Messages.tag("b", points.points(1))));
        session.broadcast(messages.prefixed("round.won", Messages.tag("round", round),
                Messages.tag("team", winner.displayName()), Messages.tag("reason", reason),
                Messages.tag("a", points.points(0)), Messages.tag("b", points.points(1))));
        if (mvp != null) {
            session.broadcast(messages.prefixed("round.mvp", Messages.tag("player", session.name(mvp)),
                    Messages.tag("points", points.roundContribution(mvp))));
        }
        sounds().roundEnd(online(team), online(1 - team));
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
        session.finish(GameResult.builder().team(teams.get(team)).mvp(score().matchMvp()).reason(reason).build());
    }

    // =================================================================== structures

    private void createTowers() {
        for (int team = 0; team < 2; team++) {
            LaneSites.Side side = sides[team];
            MatchTeam owner = teams.get(team);
            int enemies = Math.max(1, members(1 - team).size());
            for (LaneSites.Lane lane : LaneSites.Lane.values()) {
                List<LaneSites.Structure> sites = layout().sites().towers(side, lane);
                for (int i = 0; i < sites.size(); i++) {
                    addTower(sites.get(i), side, lane, i == 0 ? Tower.Kind.OUTER : Tower.Kind.INNER, owner, enemies);
                }
            }
            addTower(layout().sites().core(side), side, null, Tower.Kind.CORE, owner, enemies);
        }
        refreshShields();
    }

    private void addTower(LaneSites.Structure site, LaneSites.Side side, LaneSites.@Nullable Lane lane,
                          Tower.Kind kind, MatchTeam owner, int enemies) {
        Tower tower = new Tower(site, side, lane, kind, layout().at(site, side),
                settings.structureHealth(kind, enemies));
        tower.spawn(session, game.towerLook(), owner.color(),
                game.towerLook().hitboxScale(kind.lookId(), settings.hitboxScale()),
                messages.get("structure.bar-name", Messages.tag("team", owner.displayName()),
                        Messages.tag("name", site.name())));
        towers.add(tower);
    }

    /**
     * Keeps the chunks around every structure loaded for the whole match with plugin chunk tickets: the structures'
     * entities are not persistent, so a chunk unloading (nobody near it, or not yet: they spawn before the players
     * arrive) would silently delete the hitbox and the visual.
     */
    private void holdChunks() {
        LaneLayout placed = layout();
        World world = placed.world();
        for (LaneSites.Side side : LaneSites.Side.values()) {
            List<LaneSites.Structure> all = new ArrayList<>();
            for (LaneSites.Lane lane : LaneSites.Lane.values()) {
                all.addAll(placed.sites().towers(side, lane));
            }
            all.add(placed.sites().core(side));
            for (LaneSites.Structure site : all) {
                Location at = placed.at(site, side);
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
    }

    private void releaseChunks() {
        LaneLayout placed = layout;
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

    /**
     * The shield rules: a lane's inner tower is shielded while the outer tower of that lane stands; a core is shielded
     * while any inner tower of its side stands (any tower, on a map without inner towers).
     */
    private void refreshShields() {
        for (Tower tower : towers) {
            if (tower.isDestroyed()) {
                continue;
            }
            tower.shielded(switch (tower.kind()) {
                case OUTER -> false;
                case INNER -> standing(tower.owner(), tower.lane(), Tower.Kind.OUTER);
                case CORE -> hasKind(tower.owner(), Tower.Kind.INNER)
                        ? standing(tower.owner(), null, Tower.Kind.INNER)
                        : standing(tower.owner(), null, Tower.Kind.OUTER);
            });
        }
    }

    /** True if a structure of {@code kind} of {@code side} (on {@code lane}, or any lane for null) still stands. */
    private boolean standing(LaneSites.Side side, LaneSites.@Nullable Lane lane, Tower.Kind kind) {
        for (Tower tower : towers) {
            if (tower.owner() == side && tower.kind() == kind && !tower.isDestroyed()
                    && (lane == null || tower.lane() == lane)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasKind(LaneSites.Side side, Tower.Kind kind) {
        for (Tower tower : towers) {
            if (tower.owner() == side && tower.kind() == kind) {
                return true;
            }
        }
        return false;
    }

    /**
     * A melee or ability hit of {@code attacker} on a structure's hitbox: only enemies in play during the fight hurt
     * it, and only when it is not shielded. The attacker earns gauge for the health it lost
     * ({@code games.siege.gauge.structure}).
     */
    void hitTower(Tower tower, @Nullable Player attacker, double amount) {
        hitTower(tower, attacker, amount, null);
    }

    /** {@link #hitTower(Tower, Player, double)} of a damage event (to tell whether it belongs to an ultimate). */
    void hitTower(Tower tower, @Nullable Player attacker, double amount, @Nullable EntityDamageEvent event) {
        if (phase != Phase.FIGHT || attacker == null || tower.isDestroyed() || !session.isInPlay(attacker)) {
            return;
        }
        LaneSites.Side side = side(attacker.getUniqueId());
        if (side == null || side == tower.owner()) {
            return;
        }
        protectedUntil.remove(attacker.getUniqueId());
        if (tower.isShielded()) {
            guidance().suggest(attacker, messages.get(tower.isCore() ? "structure.core-shielded"
                    : "structure.shielded"), Guidance.INFO, 40);
            sounds().shieldBlocked(attacker);
            return;
        }
        double dealt = damageStructure(tower, side, amount, attacker);
        if (abilities != null) {
            // the gauge for the health it lost, unless the hit belongs to the attacker's ultimate
            boolean ultimate = event != null && abilities.isUltimateHit(event, attacker);
            abilities.chargeDealtGauge(attacker, dealt, settings.gaugeStructureFactor(), ultimate);
        }
    }

    /**
     * Damages a structure of the other side: the hook for anything that hurts structures (players through
     * {@link #hitTower}, minions with a null {@code credit}). Does nothing outside the fight, to a shielded or destroyed
     * structure or to one of {@code by}'s own.
     *
     * @return the health the structure lost
     */
    public double damageStructure(Tower tower, LaneSites.Side by, double amount, @Nullable Player credit) {
        return damageStructureBy(tower, by, amount, credit == null ? null : credit.getUniqueId());
    }

    /** {@link #damageStructure(Tower, LaneSites.Side, double, Player)} credited to a participant id (a bot). */
    public double damageStructureBy(Tower tower, LaneSites.Side by, double amount, @Nullable UUID credit) {
        if (phase != Phase.FIGHT || tower.isDestroyed() || tower.isShielded() || by == tower.owner()) {
            return 0;
        }
        long now = session.tick();
        double dealt = tower.damage(amount, credit, now);
        if (!(dealt > 0)) {
            return 0;
        }
        eachModule(module -> module.structureDamaged(tower, by, dealt, credit));
        Location base = tower.base();
        damageNumbers().show(UUID.nameUUIDFromBytes(tower.id().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                base.clone().add(0, game.towerLook().barHeight(tower.kind().lookId()) + 0.9, 0), dealt,
                DamageNumbers.Kind.STRUCTURE);
        sounds().hit(base, surface(tower), tower.id());
        Player striker = credit == null ? null : Bukkit.getPlayer(credit);
        hitFeel().structure(tower.id(), base.getWorld(), tower.bounds(),
                tower.isCore() ? HitMaterial.PORCELAIN : HitMaterial.WOOD, striker, tower.isDestroyed());
        if (tower.isDestroyed()) {
            structureDestroyed(tower, by, credit);
            return dealt;
        }
        int team = teamOf(tower.owner());
        if (tower.warn(now, settings.warningCooldownTicks())) {
            for (Player defender : online(team)) {
                guidance().suggest(defender, messages.get("structure.under-attack", Messages.tag("name", tower.name()),
                        Messages.tag("percent", Math.round(tower.fraction() * 100))), Guidance.ALERT, 100);
                sounds().underAttack(defender, tower.id(), settings.warningCooldownTicks());
            }
        }
        int quarter = tower.takeQuarterCrossed();
        if (quarter > 0) {
            sounds().healthWarning(base, quarter * 25);
            for (Player defender : online(team)) {
                guidance().suggest(defender, messages.get("structure.health-warning",
                        Messages.tag("name", tower.name()), Messages.tag("percent", quarter * 25)), Guidance.ALERT, 80);
            }
        }
        return dealt;
    }

    private static SiegeSounds.Surface surface(Tower tower) {
        return tower.isCore() ? SiegeSounds.Surface.PORCELAIN : SiegeSounds.Surface.WOOD;
    }

    private void structureDestroyed(Tower tower, LaneSites.Side by, @Nullable UUID credit) {
        int team = teamOf(by);
        UUID top = tower.topDamager();
        UUID destroyer = top != null ? top : credit;
        sounds().destroyed(tower.base(), surface(tower), online(team), online(1 - team));
        firework(tower.base().add(0, 4, 0), teams.get(team));
        eachModule(module -> module.structureDestroyed(tower, by, destroyer));
        if (tower.isCore()) {
            roundWon(team, messages.get("result.reason-core", Messages.tag("team", teams.get(1 - team).displayName())));
            return;
        }
        int points = score().towerDestroyed(team, destroyer);
        progress().teamXp(team, progress().settings().xpTowerDestroyed());
        title(messages.get("structure.destroyed-title", Messages.tag("name", tower.name()),
                        Messages.tag("team", teams.get(1 - team).displayName())),
                messages.get("structure.destroyed-subtitle", Messages.tag("team", teams.get(team).displayName()),
                        Messages.tag("points", points)));
        session.broadcast(messages.prefixed("structure.destroyed", Messages.tag("name", tower.name()),
                Messages.tag("owner", teams.get(1 - team).displayName()),
                Messages.tag("player", destroyer == null ? Component.text("-") : session.publicName(destroyer)),
                Messages.tag("team", teams.get(team).displayName()), Messages.tag("points", points)));
        refreshShields();
        scored(team);
    }

    /** Towers (not cores) shoot the nearest enemy in range: a module's target (a minion) first, then players. */
    private void towerShots(long now) {
        double damage = settings.towerAttackDamage();
        double range = settings.towerAttackRange();
        if (!(damage > 0) || !(range > 0)) {
            return;
        }
        for (Tower tower : List.copyOf(towers)) {
            if (tower.isDestroyed() || tower.isCore() || !tower.canAttack(now)) {
                continue;
            }
            for (SiegeModule module : modules) {
                LivingEntity target = module.towerTarget(tower, range);
                if (target != null) {
                    tower.attacked(now, settings.towerAttackTicks());
                    shotEffect(tower, target);
                    module.towerShot(tower, target, damage);
                    break;
                }
            }
            if (!tower.canAttack(now)) {
                continue;
            }
            Player target = nearestEnemy(tower, range);
            if (target != null) {
                tower.attacked(now, settings.towerAttackTicks());
                shotEffect(tower, target);
                target.damage(damage, DamageSource.builder(DamageType.MAGIC).build());
                if (phase != Phase.FIGHT) {
                    return;
                }
            }
        }
    }

    private @Nullable Player nearestEnemy(Tower tower, double range) {
        Player best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Player player : session.alivePlayers()) {
            LaneSites.Side side = side(player.getUniqueId());
            if (side == null || side == tower.owner() || !session.isInPlay(player) || isProtected(player)
                    || !tower.isWithin(player.getLocation(), range)) {
                continue;
            }
            double distance = player.getLocation().distanceSquared(tower.base());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = player;
            }
        }
        return best;
    }

    private void shotEffect(Tower tower, LivingEntity target) {
        Location from = tower.base().add(0, 4, 0);
        Location to = target.getLocation().add(0, target.getHeight() * 0.6, 0);
        World world = from.getWorld();
        if (world == null || !world.equals(to.getWorld())) {
            return;
        }
        MatchTeam owner = teams.get(Math.max(0, teamOf(tower.owner())));
        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(owner.color().value()), 1.2f);
        Vector step = to.toVector().subtract(from.toVector());
        int points = (int) Math.max(2, Math.min(40, step.length() * 2));
        step.multiply(1.0 / points);
        Location at = from.clone();
        for (int i = 0; i <= points; i++) {
            world.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, dust);
            at.add(step);
        }
        sounds().at(from, Sound.ENTITY_ARROW_SHOOT, 0.8f, 1.4f);
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
        return until != null && until > session.tick();
    }

    /** The protected player attacked: the protection ends. */
    void attacked(Player attacker) {
        if (protectedUntil.remove(attacker.getUniqueId()) != null) {
            guidance().suggest(attacker, messages.get("spawn.protection-ended"), Guidance.INFO, 30);
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

    // =================================================================== guidance

    /** The closest enemy structure {@code player} can hit now (no shield), or null. */
    public @Nullable Tower nearestTarget(Player player) {
        LaneSites.Side side = side(player.getUniqueId());
        Tower best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Tower tower : towers) {
            if (side == null || tower.owner() == side || tower.isDestroyed() || tower.isShielded()
                    || !player.getWorld().equals(tower.base().getWorld())) {
                continue;
            }
            double distance = tower.base().distanceSquared(player.getLocation());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = tower;
            }
        }
        return best;
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
        if (phase != Phase.PREP) {
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

    // =================================================================== hooks for other features

    /** The side of {@code player}, or null for a spectator. */
    public LaneSites.@Nullable Side side(UUID player) {
        int team = teamIndex(player);
        return team < 0 ? null : sides[team];
    }

    /** The side team {@code team} (index in {@link #teams}) plays. */
    public LaneSites.Side sideOf(int team) {
        return sides[team];
    }

    /** The team (index in {@link #teams}) playing {@code side}. */
    public int teamOf(LaneSites.Side side) {
        return sides[0] == side ? 0 : 1;
    }

    /** The team playing {@code side}. */
    public MatchTeam team(LaneSites.Side side) {
        return teams.get(teamOf(side));
    }

    /** The map's places (spawns, shops, structures, lanes, objectives). */
    public LaneSites sites() {
        return layout().sites();
    }

    /**
     * Gives {@code side} {@code points} (jungle, objectives, the dragon); {@code reason} is a short id for logs. In the
     * overtime any points win the round. Only during the fight.
     */
    public void addScore(LaneSites.Side side, int points, String reason) {
        addScore(side, points, reason, null);
    }

    /** {@link #addScore(LaneSites.Side, int, String)} with the player who earned it (MVP contribution). */
    public void addScore(LaneSites.Side side, int points, String reason, @Nullable UUID contributor) {
        if (phase != Phase.FIGHT || points == 0) {
            return;
        }
        int team = teamOf(side);
        score().addPoints(team, points, contributor);
        session.plugin().getLogger().log(Level.FINE, () -> "siege score " + side + " +" + points + " (" + reason + ")");
        scored(team);
    }

    /** Gives {@code player} experience (minions, jungle, the dragon). */
    public void addXp(UUID player, int amount) {
        if (progress != null && amount > 0) {
            progress.grant(player, amount);
        }
    }

    /** Experience for every member of {@code side} (in play or waiting to respawn). */
    public void addTeamXp(LaneSites.Side side, int amount) {
        if (progress != null && amount > 0) {
            progress.teamXp(teamOf(side), amount);
        }
    }

    /** The gold of {@code player} ({@link SiegeEconomy}; 0 until a gold feature installs one). */
    public int gold(UUID player) {
        return economy.gold(player);
    }

    /** Gives (negative: takes) gold through the installed {@link SiegeEconomy}. */
    public void addGold(UUID player, int amount, String reason) {
        economy.addGold(player, amount, reason);
    }

    public SiegeEconomy economy() {
        return economy;
    }

    /** Installs the gold of this match (the gold/shop feature, from its {@link SiegeModule}). */
    public void economy(SiegeEconomy economy) {
        this.economy = economy;
    }

    public Guidance guidance() {
        if (guidance == null) {
            throw new IllegalStateException("The match has not started");
        }
        return guidance;
    }

    public SiegeSounds sounds() {
        if (sounds == null) {
            throw new IllegalStateException("The match has not started");
        }
        return sounds;
    }

    /** The hit feel (타격감): sparks, layered hit sounds, the attacker's confirmation. */
    public HitFeel hitFeel() {
        if (hitFeel == null) {
            throw new IllegalStateException("The match has not started");
        }
        return hitFeel;
    }

    public DamageNumbers damageNumbers() {
        if (damageNumbers == null) {
            throw new IllegalStateException("The match has not started");
        }
        return damageNumbers;
    }

    /** The lane systems (minions, jungle, gold, shop), or null if they could not start. */
    public io.github.sx2xoox.mcpl.game.siege.arena.@Nullable SiegeSystems systems() {
        return lanes == null ? null : lanes.systems();
    }

    /** The modules of this match (minions, jungle, ...). */
    public List<SiegeModule> modules() {
        return List.copyOf(modules);
    }

    // =================================================================== queries

    public GameSession session() {
        return session;
    }

    public SiegeGame game() {
        return game;
    }

    /** The 공성전 messages (siege.yml). */
    public Messages messages() {
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

    /** True during the last minutes of a round ("마지막 3분"). */
    public boolean isClimax() {
        return climax;
    }

    public boolean isOvertime() {
        return overtime;
    }

    /** Ticks of the current round's fight clock (restarts for the overtime). */
    public long fightTicks() {
        return fightTicks;
    }

    long prepTicks() {
        return prepTicks;
    }

    /** Ticks left of the current phase (fight, prep, break). */
    public long ticksLeft() {
        return switch (phase) {
            case PREP -> Math.max(0, prepTicks - phaseTicks);
            case FIGHT -> Math.max(0, (overtime ? settings.overtimeTicks() : settings.roundTicks()) - fightTicks);
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

    SiegeProgress progress() {
        if (progress == null) {
            throw new IllegalStateException("The match has not started");
        }
        return progress;
    }

    /** The in-match level (1 … {@code levels.max}) of a player this round, 1 before the match started. */
    public int level(UUID player) {
        return progress == null ? 1 : progress.levels().level(player);
    }

    /** The level shown on {@code player}'s name plate: their level, 0 (none) when levels are off. */
    int plateLevel(UUID player) {
        return progress != null && progress.settings().levelsEnabled() ? level(player) : 0;
    }

    /** The experience a player gathered this round. */
    public int experience(UUID player) {
        return progress == null ? 0 : progress.levels().xp(player);
    }

    public SuperpowerMatch abilities() {
        if (abilities == null) {
            throw new IllegalStateException("The match has not started");
        }
        return abilities;
    }

    public LaneLayout layout() {
        if (layout == null) {
            throw new IllegalStateException("The match has not started");
        }
        return layout;
    }

    public List<MatchTeam> teams() {
        return List.copyOf(teams);
    }

    /** Every structure of the round (towers and cores of both sides). */
    public List<Tower> towers() {
        return List.copyOf(towers);
    }

    /** True for a barrier block of a structure's walls ({@link Tower#isWall}). */
    public boolean isStructureWall(Block block) {
        if (block.getType() != org.bukkit.Material.BARRIER) {
            return false;
        }
        for (Tower tower : towers) {
            if (tower.isWall(block)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The ground squares no body of half width {@code margin} may stand in: every structure's walls (standing or ruin)
     * grown by it ({@link Tower#keepOut}).
     */
    public List<Footprint.Box> structureKeepOut(double margin) {
        List<Footprint.Box> out = new ArrayList<>();
        for (Tower tower : towers) {
            Footprint.Box box = tower.keepOut(margin);
            if (box != null) {
                out.add(box);
            }
        }
        return out;
    }

    /**
     * {@code at} moved out of any structure's walls (to the nearest side, {@code margin} away), for a goal or a
     * teleport of a body of half width {@code margin}; {@code at} itself when it is clear.
     */
    public Location clearOfStructures(Location at, double margin) {
        return clearOf(structureKeepOut(margin), at);
    }

    /** {@code at} moved out of the boxes ({@link Footprint#clear}); itself when it is clear. */
    public static Location clearOf(List<Footprint.Box> boxes, Location at) {
        double[] clear = Footprint.clear(boxes, at.getX(), at.getZ());
        Location out = at.clone();
        out.setX(clear[0]);
        out.setZ(clear[1]);
        return out;
    }

    /** The structures of {@code side}. */
    public List<Tower> towers(LaneSites.Side side) {
        List<Tower> out = new ArrayList<>();
        for (Tower tower : towers) {
            if (tower.owner() == side) {
                out.add(tower);
            }
        }
        return out;
    }

    /** The core of {@code side}, or null between rounds. */
    public @Nullable Tower core(LaneSites.Side side) {
        for (Tower tower : towers) {
            if (tower.owner() == side && tower.isCore()) {
                return tower;
            }
        }
        return null;
    }

    /** The index of the player's team in {@link #teams}, or -1. */
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

    /** The structure whose hitbox is {@code entity}, or null. */
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

    /** Where a player (re)spawns: their side's base. */
    public Location spawnFor(UUID player) {
        int team = teamIndex(player);
        LaneSites.Side side = team < 0 ? LaneSites.Side.BLUE : sides[team];
        int slot = Math.max(0, members(Math.max(0, team)).indexOf(player));
        return spotFor(layout().spawns(side), slot);
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

    /** The members of a team who are players (not 봇전 bots). */
    List<UUID> players(int team) {
        List<UUID> out = members(team);
        out.removeIf(session::isBot);
        return out;
    }

    /** The participant (a 봇전 bot) whose body is {@code entity}, from the modules; null for anything else. */
    public @Nullable UUID fighterOf(@Nullable Entity entity) {
        if (entity == null || entity instanceof Player) {
            return null;
        }
        for (SiegeModule module : modules) {
            UUID id = module.fighterOf(entity);
            if (id != null) {
                return id;
            }
        }
        return null;
    }

    /** The health plate text ({@code Lv.3 이름} over the bar) of a fighter that is not a player (a bot). */
    public Component plateText(UUID id, Component name, double health, double max) {
        return NamePlates.plate(plateLevel(id), name, health, 0, max, messages);
    }

    /** The extra maximum health of a participant's level (docs/siege.md "레벨"), 0 while levels are off. */
    public double levelHealth(UUID id) {
        return progress == null ? 0 : progress.settings().bonusHealth(level(id));
    }

    /** The damage factor of a participant's level (as abilities and basic attacks grow), 1 while levels are off. */
    public double levelFactor(UUID id) {
        return balance.levelFactor(balanceLevel(id));
    }

    /** Experience everyone in play gets every second of the fight. */
    public int passiveXpPerSecond() {
        return progress == null ? 0 : Math.max(0, progress.settings().xpPassivePerSecond());
    }

    /** Online members of a team who are part of the session. */
    List<Player> online(int team) {
        List<Player> out = new ArrayList<>();
        for (UUID member : members(team)) {
            Player player = Bukkit.getPlayer(member);
            if (player != null && session.isMember(player)) {
                out.add(player);
            }
        }
        return out;
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

    /** A title for every viewer. */
    void title(Component title, Component subtitle) {
        session.title(title, subtitle);
    }

    /** {@code player}'s health changed (their name plate follows). */
    void healthChanged(Player player) {
        if (hud != null && !stopped) {
            hud.healthChanged(player);
        }
    }

    /** The HUD (tests). */
    @Nullable SiegeHud hud() {
        return hud;
    }

    // =================================================================== testing

    /** A team started empty (started alone to try the mode): only score and time end the rounds. */
    public boolean isSolo() {
        return solo;
    }

    /** Ends the current round's clock as if its time ran out (tests and admins). */
    public void expireRound() {
        if (phase == Phase.FIGHT) {
            fightTicks = overtime ? settings.overtimeTicks() : settings.roundTicks();
            timeUp();
        }
    }
}
