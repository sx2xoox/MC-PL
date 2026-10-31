package io.github.sx2xoox.mcpl.game.augment.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.core.arena.Spread;
import io.github.sx2xoox.mcpl.core.combat.CombatTracker;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.ui.Format;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.game.augment.AugmentPracticeSettings;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * The dummies of 증강 연습 ({@code /게임 증강 허수아비}): {@link Mannequin}s named "허수아비" standing in front of the
 * player who placed them. They are enemies of every practising player ({@link AugmentMatch#isEnemy}), so every
 * damage hook, executioner, thorns, sniper, arrows and the tracking augments work on them; a kill is credited to the
 * last practising player who hit it (war god, 첫 번째 피, 역전의 명수 ...).
 *
 * <p>A dummy is healed and put back on its spot after a pause without hits, and comes back a second after it dies, is
 * removed or is thrown far away. Dummies are tracked by the session, so they are gone when the practice ends.
 */
final class PracticeDummies implements Listener {

    static final double LOST_DISTANCE = 24.0;
    static final double HOME_TOLERANCE = 1.0;
    static final int RESPAWN_TICKS = 20;
    static final double LATERAL_STEP = 2.0;
    static final double FIRST_DISTANCE = 4.0;
    static final double SPACING = 3.0;

    private final AugmentMatch match;
    private final GameSession session;
    private final AugmentPracticeSettings settings;
    private final Map<UUID, List<Dummy>> byOwner = new LinkedHashMap<>();
    private final Map<UUID, Dummy> byBody = new HashMap<>();
    private boolean disposed;

    static final class Dummy {
        final Location spot;
        @Nullable Mannequin body;
        long lastHit = Long.MIN_VALUE / 2;
        long goneSince = -1;

        Dummy(Location spot) {
            this.spot = spot;
        }
    }

    PracticeDummies(AugmentMatch match, AugmentPracticeSettings settings) {
        this.match = match;
        this.session = match.session();
        this.settings = settings;
    }

    /** Places {@code count} dummies in front of {@code owner}, replacing theirs; returns how many stand. */
    int place(Player owner, int count) {
        remove(owner.getUniqueId());
        if (disposed) {
            return 0;
        }
        Location base = owner.getLocation();
        double yaw = Math.toRadians(base.getYaw());
        Vector forward = new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
        Vector side = new Vector(-forward.getZ(), 0, forward.getX());
        List<Dummy> placed = new ArrayList<>();
        int wanted = Math.max(1, Math.min(AugmentPracticeSettings.MAX_DUMMIES, count));
        for (int i = 0; i < wanted; i++) {
            double lateral = i == 0 ? 0.0 : (i % 2 == 1 ? -LATERAL_STEP : LATERAL_STEP) * ((i + 1) / 2);
            Location point = base.clone().add(forward.clone().multiply(FIRST_DISTANCE + i * SPACING))
                    .add(side.clone().multiply(lateral));
            Location spot = ground(point, base);
            spot.setYaw(base.getYaw() + 180f);
            spot.setPitch(0f);
            Dummy dummy = new Dummy(spot);
            spawn(dummy);
            placed.add(dummy);
        }
        byOwner.put(owner.getUniqueId(), placed);
        return (int) placed.stream().filter(d -> d.body != null).count();
    }

    private Location ground(Location point, Location base) {
        Location inside = session.arena().contains(point) ? point : session.arena().clampInside(point);
        World world = inside.getWorld();
        Location ground = world == null ? null
                : Spread.safeGround(world, inside.getBlockX(), inside.getBlockZ(), base.getBlockY());
        if (ground == null || Math.abs(ground.getY() - base.getY()) > 8) {
            return inside;
        }
        ground.setX(inside.getX());
        ground.setZ(inside.getZ());
        return ground;
    }

    private void spawn(Dummy dummy) {
        World world = dummy.spot.getWorld();
        if (world == null || disposed) {
            return;
        }
        Mannequin body;
        try {
            body = world.spawn(dummy.spot.clone(), Mannequin.class, this::dress);
        } catch (RuntimeException e) {
            session.plugin().getLogger().log(Level.WARNING, "Could not spawn a practice dummy", e);
            return;
        }
        if (!body.isValid()) {
            return;
        }
        session.track(body);
        dummy.body = body;
        dummy.goneSince = -1;
        byBody.put(body.getUniqueId(), dummy);
        describe(body, body.getHealth());
    }

    private void dress(Mannequin mannequin) {
        mannequin.setPersistent(false);
        mannequin.customName(match.messages().get("practice.dummy-name"));
        mannequin.setCustomNameVisible(true);
        mannequin.setBodyYaw(mannequin.getLocation().getYaw());
        AttributeInstance maxHealth = mannequin.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue(settings.dummyHealth());
        }
        mannequin.setHealth(Math.min(settings.dummyHealth(), maxHealth(mannequin)));
    }

    private void describe(Mannequin body, double health) {
        body.setDescription(match.messages().get("practice.dummy-health",
                Messages.tag("health", Format.number(Math.max(0, health))),
                Messages.tag("max", Format.number(maxHealth(body)))));
    }

    static double maxHealth(LivingEntity entity) {
        AttributeInstance max = entity.getAttribute(Attribute.MAX_HEALTH);
        return max == null ? 20.0 : max.getValue();
    }

    void remove(UUID owner) {
        List<Dummy> old = byOwner.remove(owner);
        if (old == null) {
            return;
        }
        for (Dummy dummy : old) {
            Mannequin body = dummy.body;
            dummy.body = null;
            if (body != null) {
                byBody.remove(body.getUniqueId());
                session.untrack(body);
                body.remove();
            }
        }
    }

    void dispose() {
        disposed = true;
        for (UUID owner : List.copyOf(byOwner.keySet())) {
            remove(owner);
        }
    }

    /** True for a live dummy of this practice. */
    boolean isDummy(@Nullable Entity entity) {
        if (entity == null) {
            return false;
        }
        Dummy dummy = byBody.get(entity.getUniqueId());
        return dummy != null && dummy.body != null && !dummy.body.isDead();
    }

    /** Every live dummy. */
    List<LivingEntity> live() {
        List<LivingEntity> out = new ArrayList<>(byBody.size());
        for (Dummy dummy : byBody.values()) {
            Mannequin body = dummy.body;
            if (body != null && body.isValid() && !body.isDead()) {
                out.add(body);
            }
        }
        return out;
    }

    /** The live dummies placed by {@code owner}. */
    List<Mannequin> of(UUID owner) {
        List<Mannequin> out = new ArrayList<>();
        for (Dummy dummy : byOwner.getOrDefault(owner, List.of())) {
            if (dummy.body != null && dummy.body.isValid() && !dummy.body.isDead()) {
                out.add(dummy.body);
            }
        }
        return out;
    }

    /** Every 10 ticks: respawn, bring back, refill. */
    void tick(long now) {
        for (List<Dummy> dummies : byOwner.values()) {
            for (Dummy dummy : dummies) {
                maintain(dummy, now);
            }
        }
    }

    private void maintain(Dummy dummy, long now) {
        Mannequin body = dummy.body;
        if (body == null || !body.isValid() || body.isDead()) {
            if (body != null) {
                byBody.remove(body.getUniqueId());
                session.untrack(body);
                dummy.body = null;
            }
            if (dummy.goneSince < 0) {
                dummy.goneSince = now;
            } else if (now - dummy.goneSince >= RESPAWN_TICKS) {
                spawn(dummy);
            }
            return;
        }
        Location at = body.getLocation();
        World world = dummy.spot.getWorld();
        boolean lost = world == null || !world.equals(at.getWorld())
                || at.distanceSquared(dummy.spot) > LOST_DISTANCE * LOST_DISTANCE || at.getY() < world.getMinHeight();
        if (lost) {
            sendHome(body, dummy);
            refill(body);
            return;
        }
        if (now - dummy.lastHit < settings.dummyRefillTicks()) {
            return;
        }
        if (body.getHealth() < maxHealth(body)) {
            refill(body);
        }
        if (at.distanceSquared(dummy.spot) > HOME_TOLERANCE * HOME_TOLERANCE) {
            sendHome(body, dummy);
        }
    }

    private static void sendHome(Mannequin body, Dummy dummy) {
        body.setVelocity(new Vector());
        body.setFallDistance(0f);
        body.setFireTicks(0);
        body.teleport(dummy.spot.clone());
    }

    private void refill(Mannequin body) {
        double max = maxHealth(body);
        body.setHealth(max);
        describe(body, max);
    }

    // =================================================================== events

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        Dummy dummy = byBody.get(event.getEntity().getUniqueId());
        if (dummy == null || !(event.getEntity() instanceof Mannequin body) || body != dummy.body) {
            return;
        }
        dummy.lastHit = match.clock();
        describe(body, body.getHealth() - Math.min(event.getFinalDamage(), body.getHealth()));
        Player attacker = session.combat().resolveAttacker(event);
        if (attacker != null && session.isParticipant(attacker)) {
            session.combat().recordHit(body.getUniqueId(), attacker.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (!byBody.containsKey(dead.getUniqueId())) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        UUID killer = session.combat().lastAttackerId(dead.getUniqueId(), CombatTracker.DEFAULT_WINDOW_TICKS);
        session.combat().forget(dead.getUniqueId());
        Player player = killer == null ? null : Bukkit.getPlayer(killer);
        if (player != null && session.isInPlay(player)) {
            match.creditKill(player, dead);
        }
    }
}
