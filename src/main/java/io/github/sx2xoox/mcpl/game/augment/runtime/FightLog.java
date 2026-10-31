package io.github.sx2xoox.mcpl.game.augment.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.jspecify.annotations.Nullable;

/**
 * The recent hits between participants of a bot match (pure): who hit whom when and how hard. The bots' chat reads
 * it to notice a third party joining a fight, the 자동 중계 카메라 to find the most intense fight.
 */
public final class FightLog {

    /** One hit. */
    public record Hit(UUID attacker, UUID victim, long tick, double damage) {
    }

    /** Hits are kept this long (ticks). */
    static final long KEEP = 20 * 20;
    private static final int MAX = 512;

    private final Deque<Hit> hits = new ArrayDeque<>();

    /** Records a hit at {@code tick} (old hits are dropped). */
    public void record(UUID attacker, UUID victim, long tick, double damage) {
        if (attacker.equals(victim)) {
            return;
        }
        hits.addLast(new Hit(attacker, victim, tick, damage));
        while (hits.size() > MAX || !hits.isEmpty() && tick - hits.peekFirst().tick() > KEEP) {
            hits.removeFirst();
        }
    }

    /** Hits within the last {@code window} ticks, oldest first. */
    public List<Hit> since(long now, long window) {
        List<Hit> out = new ArrayList<>();
        for (Hit hit : hits) {
            if (now - hit.tick() <= window) {
                out.add(hit);
            }
        }
        return out;
    }

    /** Damage {@code who} dealt in the last {@code window} ticks. */
    public double dealt(UUID who, long now, long window) {
        double sum = 0;
        for (Hit hit : hits) {
            if (hit.attacker().equals(who) && now - hit.tick() <= window) {
                sum += hit.damage();
            }
        }
        return sum;
    }

    /** Damage {@code who} took from others in the last {@code window} ticks. */
    public double taken(UUID who, long now, long window) {
        double sum = 0;
        for (Hit hit : hits) {
            if (hit.victim().equals(who) && now - hit.tick() <= window) {
                sum += hit.damage();
            }
        }
        return sum;
    }

    /** Everyone {@code who} hit or was hit by in the last {@code window} ticks. */
    public Set<UUID> opponents(UUID who, long now, long window) {
        Set<UUID> out = new HashSet<>();
        for (Hit hit : hits) {
            if (now - hit.tick() > window) {
                continue;
            }
            if (hit.attacker().equals(who)) {
                out.add(hit.victim());
            } else if (hit.victim().equals(who)) {
                out.add(hit.attacker());
            }
        }
        return out;
    }

    /**
     * The newest hit on {@code victim} was a third party: its attacker had not fought {@code victim} in the
     * {@code window} before, while {@code victim} was trading hits with someone else (both ways). Returns that
     * attacker, or null.
     */
    public @Nullable UUID thirdParty(UUID victim, long now, long window) {
        Hit last = null;
        for (Hit hit : hits) {
            if (hit.victim().equals(victim)) {
                last = hit;
            }
        }
        if (last == null || now - last.tick() > 1) {
            return null;
        }
        UUID newcomer = last.attacker();
        boolean fought = false;
        Set<UUID> dealtTo = new HashSet<>();
        Set<UUID> takenFrom = new HashSet<>();
        for (Hit hit : hits) {
            if (hit == last || now - hit.tick() > window) {
                continue;
            }
            if (hit.attacker().equals(newcomer) && hit.victim().equals(victim)
                    || hit.attacker().equals(victim) && hit.victim().equals(newcomer)) {
                fought = true;
            }
            if (hit.attacker().equals(victim)) {
                dealtTo.add(hit.victim());
            } else if (hit.victim().equals(victim)) {
                takenFrom.add(hit.attacker());
            }
        }
        if (fought) {
            return null;
        }
        dealtTo.retainAll(takenFrom);
        dealtTo.remove(newcomer);
        return dealtTo.isEmpty() ? null : newcomer;
    }

    /** The last one to hit {@code victim} within {@code window} ticks, or null. */
    public @Nullable UUID lastAttacker(UUID victim, long now, long window) {
        UUID out = null;
        for (Hit hit : hits) {
            if (hit.victim().equals(victim) && now - hit.tick() <= window) {
                out = hit.attacker();
            }
        }
        return out;
    }

    /** Records the hits of a match's participants into its log (after every other plugin and the rules). */
    static final class Recorder implements Listener {

        private final AugmentMatch match;
        private final FightLog log;
        private final java.util.function.BiConsumer<UUID, UUID> after;

        Recorder(AugmentMatch match, FightLog log, java.util.function.BiConsumer<UUID, UUID> after) {
            this.match = match;
            this.log = log;
            this.after = after;
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onHit(EntityDamageEvent event) {
            if (!(event.getEntity() instanceof LivingEntity victim)) {
                return;
            }
            UUID victimId = match.participantOf(victim);
            UUID attacker = victimId == null ? null : match.attackerOf(event);
            if (attacker == null || attacker.equals(victimId)) {
                return;
            }
            double dealt = Math.min(event.getFinalDamage(), victim.getHealth() + victim.getAbsorptionAmount());
            log.record(attacker, victimId, match.clock(), Math.max(0, dealt));
            after.accept(attacker, victimId);
        }
    }
}
