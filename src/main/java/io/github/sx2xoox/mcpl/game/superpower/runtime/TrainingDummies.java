package io.github.sx2xoox.mcpl.game.superpower.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.core.arena.Spread;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.ui.Format;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.game.superpower.PracticeSettings;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * The training dummies of a practice session ({@code /게임 능력 연습}): {@link Mannequin}s named "훈련용 허수아비"
 * standing in front of each player. They are enemies of every participant ({@link MatchContext#isEnemy}), so every
 * skill, summon and on-hit effect works on them exactly like on a player, and the damage they take charges the
 * attacker's ultimate gauge.
 *
 * <p>Every hit shows a floating damage number. A dummy is healed and put back on its spot after a short pause without
 * hits, and comes back if it dies, is removed or is thrown far away. Dummies have no AI (mannequins never move on
 * their own) but knockback, velocity, potion effects and freezing apply to them visibly. They are tracked by the
 * session, so they are removed when the practice ends at the latest.
 */
final class TrainingDummies implements Listener {

    /** A dummy thrown farther than this from its spot is put back right away. */
    static final double LOST_DISTANCE = 24.0;
    /** A dummy displaced by more than this is put back on its spot when it refills. */
    static final double HOME_TOLERANCE = 1.0;
    /** How long a killed or removed dummy stays gone. */
    static final int RESPAWN_TICKS = 20;
    /** Lifetime of a damage number. */
    static final int NUMBER_TICKS = 20;
    static final int NUMBER_STEP_TICKS = 2;
    /** How often {@link #tick(long)} runs (match fight ticks). */
    static final int MAINTAIN_PERIOD = 10;
    /** Sideways step between dummies, so the far ones can be aimed at past the near ones. */
    static final double LATERAL_STEP = 2.0;

    private final SuperpowerMatch match;
    private final GameSession session;
    private final Messages messages;
    private final PracticeSettings settings;
    private final Map<UUID, List<Dummy>> byOwner = new LinkedHashMap<>();
    /** Live bodies (entity id → dummy). */
    private final Map<UUID, Dummy> byBody = new HashMap<>();
    private boolean disposed;

    /** One dummy and the spot it belongs to. */
    static final class Dummy {
        final UUID owner;
        final Location spot;
        @Nullable Mannequin body;
        long lastHit = Long.MIN_VALUE / 2;
        long goneSince = -1;

        Dummy(UUID owner, Location spot) {
            this.owner = owner;
            this.spot = spot;
        }
    }

    TrainingDummies(SuperpowerMatch match, PracticeSettings settings) {
        this.match = match;
        this.session = match.session();
        this.messages = match.messages();
        this.settings = settings;
    }

    // =================================================================== placement

    /**
     * Places {@code count} dummies in front of {@code owner} (replacing the owner's earlier ones): the first at
     * {@code dummies.first-distance}, then every {@code dummies.spacing} blocks, alternately a bit to the left and to
     * the right so the far ones can be aimed at past the near ones. They face the owner.
     *
     * @return the number of dummies placed
     */
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
        int wanted = Math.max(1, Math.min(PracticeSettings.MAX_DUMMIES, count));
        for (int i = 0; i < wanted; i++) {
            double distance = settings.dummyDistance() + i * settings.dummySpacing();
            double lateral = i == 0 ? 0.0 : (i % 2 == 1 ? -LATERAL_STEP : LATERAL_STEP) * ((i + 1) / 2);
            Location point = base.clone().add(forward.clone().multiply(distance)).add(side.clone().multiply(lateral));
            Location spot = standingSpot(point, base);
            // Facing the owner.
            spot.setYaw(base.getYaw() + 180f);
            spot.setPitch(0f);
            Dummy dummy = new Dummy(owner.getUniqueId(), spot);
            spawn(dummy);
            placed.add(dummy);
        }
        byOwner.put(owner.getUniqueId(), placed);
        return placed.size();
    }

    /** The ground under {@code point} (inside the arena), or the point itself if there is none nearby. */
    private Location standingSpot(Location point, Location base) {
        Location inside = session.arena().contains(point) ? point : session.arena().clampInside(point);
        World world = inside.getWorld();
        if (world == null) {
            return inside;
        }
        Location ground = Spread.safeGround(world, inside.getBlockX(), inside.getBlockZ(), base.getBlockY());
        if (ground == null || Math.abs(ground.getY() - base.getY()) > 8) {
            return inside;
        }
        // Keep the exact horizontal position (safeGround centers on the block).
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
            session.plugin().getLogger().log(Level.WARNING, "Could not spawn a training dummy", e);
            return;
        }
        if (!body.isValid()) {
            return; // the spawn was cancelled (e.g. by a protection plugin)
        }
        session.track(body);
        dummy.body = body;
        dummy.goneSince = -1;
        byBody.put(body.getUniqueId(), dummy);
        describe(body, body.getHealth());
    }

    private void dress(Mannequin mannequin) {
        mannequin.setPersistent(false);
        mannequin.customName(messages.get("practice.dummy-name"));
        mannequin.setCustomNameVisible(true);
        mannequin.setBodyYaw(mannequin.getLocation().getYaw());
        AttributeInstance maxHealth = mannequin.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue(settings.dummyHealth());
        }
        mannequin.setHealth(Math.min(settings.dummyHealth(), MatchContext.maxHealth(mannequin)));
    }

    /** Shows the health under the name, e.g. {@code ❤ 183.5 / 200}. */
    private void describe(Mannequin body, double health) {
        body.setDescription(messages.get("practice.dummy-health",
                Messages.tag("health", Format.number(Math.max(0, health))),
                Messages.tag("max", Format.number(MatchContext.maxHealth(body)))));
    }

    /** Removes the dummies of one owner. */
    void remove(UUID owner) {
        List<Dummy> old = byOwner.remove(owner);
        if (old == null) {
            return;
        }
        for (Dummy dummy : old) {
            despawn(dummy);
        }
    }

    private void despawn(Dummy dummy) {
        Mannequin body = dummy.body;
        dummy.body = null;
        if (body != null) {
            byBody.remove(body.getUniqueId());
            session.untrack(body);
            body.remove();
        }
    }

    /** Removes every dummy (end of the practice). */
    void dispose() {
        disposed = true;
        for (UUID owner : List.copyOf(byOwner.keySet())) {
            remove(owner);
        }
    }

    // =================================================================== queries

    /** True for a live training dummy of this practice. */
    boolean isDummy(@Nullable Entity entity) {
        if (entity == null) {
            return false;
        }
        Dummy dummy = byBody.get(entity.getUniqueId());
        return dummy != null && dummy.body != null && !dummy.body.isDead();
    }

    /** Every live dummy (enemy candidates for targeting). */
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

    /** The live dummies of one owner, nearest spot first. */
    List<Mannequin> of(UUID owner) {
        List<Mannequin> out = new ArrayList<>();
        for (Dummy dummy : byOwner.getOrDefault(owner, List.of())) {
            if (dummy.body != null && dummy.body.isValid() && !dummy.body.isDead()) {
                out.add(dummy.body);
            }
        }
        return out;
    }

    // =================================================================== upkeep

    /** Every {@link #MAINTAIN_PERIOD} fight ticks: respawn, bring back, refill. */
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
        boolean lost = world == null || !world.equals(at.getWorld()) || at.distanceSquared(dummy.spot) > LOST_DISTANCE
                * LOST_DISTANCE || at.getY() < world.getMinHeight();
        if (lost) {
            sendHome(body, dummy);
            refill(body);
            return;
        }
        if (now - dummy.lastHit < settings.dummyRefillTicks()) {
            return;
        }
        if (body.getHealth() < MatchContext.maxHealth(body)) {
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
        double max = MatchContext.maxHealth(body);
        body.setHealth(max);
        describe(body, max);
    }

    // =================================================================== events

    /** Every hit that lands: a damage number, the health line and the attacker's gauge. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        Dummy dummy = byBody.get(event.getEntity().getUniqueId());
        if (dummy == null || !(event.getEntity() instanceof Mannequin body) || body != dummy.body) {
            return;
        }
        double amount = Math.min(event.getFinalDamage(), body.getHealth() + body.getAbsorptionAmount());
        if (!(amount > 0)) {
            return;
        }
        dummy.lastHit = match.fightTicks();
        describe(body, body.getHealth() - amount);
        if (settings.damageNumbers()) {
            showNumber(body, event.getFinalDamage());
        }
        if (match.phase() != MatchPhase.FIGHT || event.getDamageSource().getDamageType() == DamageType.GENERIC_KILL) {
            return;
        }
        Player attacker = session.combat().resolveAttacker(event);
        if (attacker != null && session.isParticipant(attacker) && session.isInPlay(attacker)
                && !match.context().isUltimateHit(event, attacker.getUniqueId())) {
            match.addEarnedGauge(attacker, amount * match.settings().gaugePerDamageDealt());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent event) {
        if (byBody.containsKey(event.getEntity().getUniqueId())) {
            event.getDrops().clear();
            event.setDroppedExp(0);
        }
    }

    // =================================================================== damage numbers

    private void showNumber(Mannequin body, double amount) {
        World world = body.getWorld();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Location at = body.getLocation().add(random.nextDouble(-0.35, 0.35),
                body.getHeight() + 0.2 + random.nextDouble(0.0, 0.3), random.nextDouble(-0.35, 0.35));
        TextDisplay number;
        try {
            number = world.spawn(at, TextDisplay.class, display -> {
                display.setPersistent(false);
                display.text(messages.get("practice.damage-number", Messages.tag("amount", Format.number(amount))));
                display.setBillboard(Display.Billboard.CENTER);
                display.setShadowed(true);
                display.setDefaultBackground(false);
                display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                display.setBrightness(new Display.Brightness(15, 15));
                display.setTeleportDuration(NUMBER_STEP_TICKS);
            });
        } catch (RuntimeException e) {
            session.plugin().getLogger().log(Level.FINE, "Could not spawn a damage number", e);
            return;
        }
        session.track(number);
        new FloatingNumber(number).start();
    }

    /** Rises and fades, then removes itself. */
    private final class FloatingNumber implements Runnable {
        private final TextDisplay display;
        private @Nullable BukkitTask task;
        private int age;

        FloatingNumber(TextDisplay display) {
            this.display = display;
        }

        void start() {
            task = session.runTimer(NUMBER_STEP_TICKS, NUMBER_STEP_TICKS, this);
        }

        @Override
        public void run() {
            age += NUMBER_STEP_TICKS;
            if (!display.isValid() || age >= NUMBER_TICKS) {
                finish();
                return;
            }
            display.teleport(display.getLocation().add(0, 0.07, 0));
            int fadeStart = NUMBER_TICKS / 2;
            if (age > fadeStart) {
                // 255 → about 40 (very low values render fully opaque again on the client).
                double left = 1.0 - (double) (age - fadeStart) / (NUMBER_TICKS - fadeStart);
                display.setTextOpacity((byte) (40 + Math.round(215 * left)));
            }
        }

        private void finish() {
            if (task != null) {
                task.cancel();
            }
            session.untrack(display);
            display.remove();
        }
    }
}
