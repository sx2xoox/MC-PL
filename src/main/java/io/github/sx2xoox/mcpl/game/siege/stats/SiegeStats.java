package io.github.sx2xoox.mcpl.game.siege.stats;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.game.MatchTeam;
import io.github.sx2xoox.mcpl.core.map.ArenaMap;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import io.github.sx2xoox.mcpl.game.siege.SiegeMatch;
import io.github.sx2xoox.mcpl.game.siege.SiegeModule;
import io.github.sx2xoox.mcpl.game.siege.bot.BotJob;
import io.github.sx2xoox.mcpl.game.siege.bot.SiegeBots;
import io.github.sx2xoox.mcpl.game.siege.tower.Tower;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityRegistry;
import io.github.sx2xoox.mcpl.game.superpower.runtime.MatchContext;
import io.github.sx2xoox.mcpl.game.superpower.runtime.Participant;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.jspecify.annotations.Nullable;

/**
 * The balance records of one 공성전 match (docs/siege-v2.md §10): per round, participant and ability (a bot: its bot
 * job) the damage dealt to enemy players and bots (skills and basic attacks apart), to structures, to minions and
 * monsters, the damage taken, kills, deaths, assists, fight time, the round's result and the final level. Damage is the
 * health actually lost (overkill capped). It only watches (MONITOR events and the module hooks), shows nothing to
 * anyone, and writes the files once when the match stops ({@link SiegeStatsStore}); a failure is only logged.
 */
public final class SiegeStats implements SiegeModule, Listener {

    private record Key(int round, UUID participant, String id) {
    }

    private final SiegeMatch match;
    private final GameSession session;
    private final SiegeStatsStore store;
    private final AbilityRegistry registry;
    private final boolean enabled;
    private final Map<Key, StatLine> lines = new LinkedHashMap<>();
    /** The line each participant adds to now (checked against its round and ability on every use). */
    private final Map<UUID, StatLine> current = new java.util.HashMap<>();
    private int round;
    private boolean fighting;
    /** The round that ended and whose result is read once the score has it ({@link #settle}), or 0. */
    private int pendingRound;
    private final int[] winsAtStart = new int[2];
    private boolean saved;
    private @Nullable Path lastFile;

    public SiegeStats(SiegeMatch match, SiegeStatsStore store, AbilityRegistry registry) {
        this.match = match;
        this.session = match.session();
        this.store = store;
        this.registry = registry;
        this.enabled = SiegeStatsSettings.read(session.gameConfig()).enabled();
        if (enabled) {
            session.registerListener(this);
        }
    }

    // ------------------------------------------------------------------ module hooks

    @Override
    public void roundStarted(int round) {
        if (!enabled) {
            return;
        }
        settle();
        this.round = round;
        fighting = false;
        for (int team = 0; team < 2; team++) {
            winsAtStart[team] = match.score().roundWins(team);
        }
    }

    @Override
    public void fightStarted() {
        fighting = enabled;
    }

    @Override
    public void tick(long fightTicks) {
        if (!fighting) {
            return;
        }
        for (MatchTeam team : match.teams()) {
            for (UUID id : team.members()) {
                if (session.isAlive(id) && (session.isBot(id) || Bukkit.getPlayer(id) != null)) {
                    StatLine line = line(id);
                    if (line != null) {
                        line.fightTicks++;
                    }
                }
            }
        }
    }

    @Override
    public void roundEnded() {
        if (!fighting) {
            return;
        }
        fighting = false;
        levels();
        pendingRound = round;
    }

    @Override
    public void playerKilled(UUID victim, @Nullable UUID killer, List<UUID> assisters) {
        if (!fighting) {
            return;
        }
        add(victim, line -> line.deaths++);
        if (killer != null) {
            add(killer, line -> line.kills++);
        }
        for (UUID assister : assisters) {
            add(assister, line -> line.assists++);
        }
    }

    @Override
    public void structureDamaged(Tower tower, LaneSites.Side by, double dealt, @Nullable UUID credit) {
        if (fighting && credit != null && dealt > 0) {
            add(credit, line -> line.structure += dealt);
        }
    }

    @Override
    public void stop() {
        if (!enabled || saved) {
            return;
        }
        saved = true;
        session.unregisterListener(this);
        settle();
        if (fighting) {
            levels();
            fighting = false;
        }
        List<StatLine> written = new ArrayList<>();
        for (StatLine line : lines.values()) {
            if (line.hasData()) {
                written.add(line);
            }
        }
        if (written.isEmpty()) {
            return;
        }
        try {
            lastFile = store.save(written, mapId(), LocalDateTime.now());
        } catch (Exception e) {
            session.plugin().getLogger().log(Level.WARNING, "Could not save the siege balance records", e);
        }
    }

    /** The CSV this match wrote when it stopped, or null. */
    public @Nullable Path lastFile() {
        return lastFile;
    }

    /** The lines recorded so far, in the order they began. */
    public List<StatLine> lines() {
        return List.copyOf(lines.values());
    }

    // ------------------------------------------------------------------ damage

    /** Every landed hit during the fight: who lost how much health to whom. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!fighting || match.phase() != SiegeMatch.Phase.FIGHT || !(event.getEntity() instanceof LivingEntity hurt)) {
            return;
        }
        double dealt = Math.min(event.getFinalDamage(), hurt.getHealth() + hurt.getAbsorptionAmount());
        if (!(dealt > 0)) {
            return;
        }
        UUID victim = hurt instanceof Player player ? (session.isParticipant(player) ? player.getUniqueId() : null)
                : match.fighterOf(hurt);
        Player attackingPlayer = session.combat().resolveAttacker(event);
        UUID attacker = attackingPlayer != null && session.isParticipant(attackingPlayer)
                ? attackingPlayer.getUniqueId() : botAttacker(event);
        if (victim != null) {
            add(victim, line -> line.taken += dealt);
            int attackerTeam = attacker == null ? -1 : match.teamIndex(attacker);
            if (attackerTeam < 0 || attackerTeam == match.teamIndex(victim) || attacker.equals(victim)) {
                return;
            }
            boolean skill = attackingPlayer != null ? isSkill(event, attackingPlayer) : bots() != null
                    && bots().isSkillStrike();
            add(attacker, line -> {
                if (skill) {
                    line.enemySkill += dealt;
                } else {
                    line.enemyBasic += dealt;
                }
            });
            return;
        }
        if (attacker != null && isCreature(hurt)) {
            add(attacker, line -> line.creature += dealt);
        }
    }

    /**
     * Skill damage (actives, passives, ultimates, and whatever an ability's summons and projectiles do) unless a basic
     * attack lands now or it is a plain melee hit of the player itself.
     */
    private boolean isSkill(EntityDamageEvent event, Player attacker) {
        MatchContext ctx = match.abilities().context();
        if (ctx.isAbilityDamage()) {
            return true;
        }
        if (ctx.isBasicHit()) {
            return false;
        }
        EntityDamageEvent.DamageCause cause = event.getCause();
        boolean melee = cause == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || cause == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK;
        return !(melee && attacker.equals(event.getDamageSource().getDirectEntity()));
    }

    private @Nullable UUID botAttacker(EntityDamageEvent event) {
        UUID id = match.fighterOf(event.getDamageSource().getCausingEntity());
        return id != null ? id : match.fighterOf(event.getDamageSource().getDirectEntity());
    }

    private boolean isCreature(Entity entity) {
        for (SiegeModule module : match.modules()) {
            if (module != this && module.isTarget(entity)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ lines

    private void add(UUID id, java.util.function.Consumer<StatLine> change) {
        StatLine line = line(id);
        if (line != null) {
            change.accept(line);
        }
    }

    /** The line of {@code id} for its ability (bot job) now in this round, or null if it has none. */
    private @Nullable StatLine line(UUID id) {
        if (round <= 0 || match.teamIndex(id) < 0) {
            return null;
        }
        String abilityId;
        boolean bot = session.isBot(id);
        if (bot) {
            SiegeBots bots = bots();
            BotJob job = bots == null ? null : bots.job(id);
            if (job == null) {
                return null;
            }
            abilityId = job.key();
        } else {
            Participant participant = match.abilities().participant(id);
            abilityId = participant == null ? null : participant.abilityId();
            if (abilityId == null) {
                return null;
            }
        }
        StatLine known = current.get(id);
        if (known != null && known.round() == round && known.id().equals(abilityId)) {
            return known;
        }
        String name = session.plainName(id);
        String key = abilityId;
        StatLine line = lines.computeIfAbsent(new Key(round, id, abilityId),
                k -> new StatLine(round, id, name, bot, key, displayNameOf(bot, key)));
        current.put(id, line);
        return line;
    }

    private String displayNameOf(boolean bot, String id) {
        if (bot) {
            BotJob job = BotJob.parse(id);
            return job == null ? id : job.koreanName();
        }
        AbilityRegistry.Entry entry = registry.get(id);
        return entry == null ? id : entry.info().name();
    }

    private void levels() {
        for (StatLine line : lines.values()) {
            if (line.round() == round) {
                line.level = match.level(line.participant());
            }
        }
    }

    /** The result of the round that ended, now that the score counted it. */
    private void settle() {
        if (pendingRound <= 0) {
            return;
        }
        int[] won = new int[2];
        for (int team = 0; team < 2; team++) {
            won[team] = match.score().roundWins(team) - winsAtStart[team];
        }
        for (StatLine line : lines.values()) {
            if (line.round() != pendingRound) {
                continue;
            }
            int team = match.teamIndex(line.participant());
            if (team >= 0 && won[team] > 0) {
                line.result = StatLine.Result.WIN;
            } else if (team >= 0 && won[1 - team] > 0) {
                line.result = StatLine.Result.LOSS;
            }
        }
        pendingRound = 0;
    }

    private @Nullable SiegeBots bots() {
        for (SiegeModule module : match.modules()) {
            if (module instanceof SiegeBots bots) {
                return bots;
            }
        }
        return null;
    }

    private String mapId() {
        try {
            return match.layout().sites() instanceof ArenaMap map ? map.id() : "fallback";
        } catch (IllegalStateException e) {
            return "unknown";
        }
    }
}
