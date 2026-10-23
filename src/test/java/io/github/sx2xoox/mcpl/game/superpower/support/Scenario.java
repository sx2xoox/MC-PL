package io.github.sx2xoox.mcpl.game.superpower.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.game.GameState;
import io.github.sx2xoox.mcpl.core.game.MatchTeam;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityParams;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.game.superpower.runtime.MatchContext;
import io.github.sx2xoox.mcpl.game.superpower.runtime.Participant;
import io.github.sx2xoox.mcpl.game.superpower.runtime.SuperpowerMatch;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * One ability under test in a real superpower match on a {@link FlatWorld}: the match is started through the hub
 * (countdown, reveal, prep, fight), the owner is assigned the ability with an admin assignment (so it is bound and
 * granted by the normal fight start), every other participant gets the neutral {@link DummyAbility} unless the
 * {@link Builder} gives the enemies a real ability.
 *
 * <p>Default layout (the owner looks along +z, enemies stand in front and face it):
 * <pre>
 *   owner  (0.5, 64, 0.5)   yaw 0
 *   enemy  (0.5, 64, 4.5)   4 blocks ahead, the aimed target
 *   enemy2 (2.0, 64, 6.5)   about 6.2 blocks ahead, slightly to the side
 *   ally   (-1.5, 64, 0.5)  team mode only, 2 blocks to the owner's side ({@link #crossfireAlly()} moves it
 *                           next to the enemy, into the area of most skills)
 * </pre>
 * Targets (enemies, ally) get {@link #TANK_HEALTH} extra max health so multi-hit skills never kill them by accident.
 *
 * <p>Every WARNING (or worse) logged by the plugin is collected ({@link #problems()}): the {@link
 * io.github.sx2xoox.mcpl.game.superpower.runtime.AbilityGuard} logs every ability exception there, and so do ability
 * loops and session tasks that catch their own errors. Every damage event that went through is recorded
 * ({@link #hits()}).
 */
public final class Scenario implements AutoCloseable {

    /** Extra max health of the targets. */
    public static final double TANK_HEALTH = 80;
    public static final NamespacedKey TANK = new NamespacedKey("test", "tank");

    /** Effects an ability must never put on its owner's allies. */
    public static final List<PotionEffectType> HARMFUL = List.of(PotionEffectType.SLOWNESS,
            PotionEffectType.BLINDNESS, PotionEffectType.POISON, PotionEffectType.WITHER, PotionEffectType.WEAKNESS,
            PotionEffectType.LEVITATION, PotionEffectType.DARKNESS, PotionEffectType.NAUSEA,
            PotionEffectType.MINING_FATIGUE, PotionEffectType.HUNGER, PotionEffectType.INSTANT_DAMAGE);

    /** One damage event that was not cancelled (recorded at MONITOR). */
    public record Hit(Entity victim, @Nullable Entity causer, @Nullable Entity direct, DamageCause cause,
            DamageType type, double damage, long tick) {
    }

    public final MatchHarness h;
    public final FlatWorld world;
    public final String abilityId;
    public final GameSession session;
    public final SuperpowerMatch match;
    public final TestPlayer owner;
    public final TestPlayer enemy;
    public final TestPlayer enemy2;
    public final @Nullable TestPlayer ally;
    private final List<LogRecord> problems = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();
    private final Handler handler;
    private final Logger logger;
    private final int listenersBefore;

    /** Options of a scenario. */
    public static final class Builder {

        private final String abilityId;
        private boolean teams;
        private String enemyAbility = DummyAbility.INFO.id();
        private String allyAbility = DummyAbility.INFO.id();
        private final Map<String, Double> params = new LinkedHashMap<>();
        private Consumer<MatchHarness> configure = h -> { };

        private Builder(String abilityId) {
            this.abilityId = abilityId;
        }

        /** Two teams of two: the owner with an ally against two enemies. */
        public Builder teams() {
            teams = true;
            return this;
        }

        /** Gives both enemies a real ability (default: {@link DummyAbility}). */
        public Builder enemyAbility(String id) {
            enemyAbility = id;
            return this;
        }

        /** Gives the ally a real ability (team mode). */
        public Builder allyAbility(String id) {
            allyAbility = id;
            return this;
        }

        /** Overrides a param of the ability under test. */
        public Builder param(String key, double value) {
            params.put(key, value);
            return this;
        }

        /** Runs before the match starts (settings, other params). */
        public Builder configure(Consumer<MatchHarness> configure) {
            this.configure = this.configure.andThen(configure);
            return this;
        }

        public Scenario start() {
            return new Scenario(this);
        }
    }

    private Scenario(Builder options) {
        this.abilityId = options.abilityId;
        h = MatchHarness.flat();
        world = h.flatWorld();
        h.registerDummy();
        h.settings().set("time-limit-seconds", 0);
        options.params.forEach((key, value) -> h.setParam(options.abilityId, key, value));
        options.configure.accept(h);
        if (options.teams) {
            h.hub.config().setTeamSize(2);
        }
        List<TestPlayer> players = new ArrayList<>();
        players.add(h.join("Owner"));
        players.add(h.join("Red"));
        players.add(h.join("Blue"));
        if (options.teams) {
            players.add(h.join("Green"));
        }
        logger = h.hub.plugin().getLogger();
        handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    problems.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        listenersBefore = listenerCount();
        h.server.getPluginManager().registerEvents(new DamageRecorder(), MockBukkit.createMockPlugin("ScenarioProbe"));

        // Teams are drawn at start; queue the owner first, the others once the teams are known (see below).
        h.game.queueAssignment(players.getFirst().getUniqueId(), abilityId);
        for (TestPlayer other : players.subList(1, players.size())) {
            h.game.queueAssignment(other.getUniqueId(), options.enemyAbility);
        }
        match = h.startFight();
        session = match.session();
        owner = players.getFirst();
        assertEquals(abilityId, participant(owner).abilityId(), "the owner got the ability under test");
        assertNotNull(participant(owner).ability(), "the ability is bound");

        TestPlayer allyFound = null;
        List<TestPlayer> enemies = new ArrayList<>();
        for (TestPlayer other : players.subList(1, players.size())) {
            if (options.teams && session.sameTeam(owner, other)) {
                allyFound = other;
            } else {
                enemies.add(other);
            }
        }
        ally = allyFound;
        enemy = enemies.get(0);
        enemy2 = enemies.get(1);
        if (options.teams) {
            assertNotNull(ally, "team mode puts one ally with the owner");
            if (!options.allyAbility.equals(options.enemyAbility)) {
                assertTrue(match.replaceAbility(ally, options.allyAbility), "ally ability replaced");
            }
        }
        for (TestPlayer target : targets()) {
            target.getAttribute(Attribute.MAX_HEALTH).addModifier(new AttributeModifier(TANK, TANK_HEALTH,
                    AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
        }
        layout();
        healAll();
        for (TestPlayer player : all()) {
            match.setGauge(player, 0);
        }
        hits.clear();
    }

    public static Builder builder(String abilityId) {
        return new Builder(abilityId);
    }

    /** Free-for-all: the owner and two enemies. */
    public static Scenario ffa(String abilityId) {
        return builder(abilityId).start();
    }

    /** Two teams of two: the owner with an ally against two enemies. */
    public static Scenario teams(String abilityId) {
        return builder(abilityId).teams().start();
    }

    // ------------------------------------------------------------------ players

    public List<TestPlayer> all() {
        List<TestPlayer> all = new ArrayList<>(List.of(owner, enemy, enemy2));
        if (ally != null) {
            all.add(ally);
        }
        return all;
    }

    /** Everyone but the owner. */
    public List<TestPlayer> targets() {
        List<TestPlayer> targets = new ArrayList<>(List.of(enemy, enemy2));
        if (ally != null) {
            targets.add(ally);
        }
        return targets;
    }

    public List<TestPlayer> enemies() {
        return List.of(enemy, enemy2);
    }

    public TestPlayer ally() {
        assertNotNull(ally, "team mode");
        return ally;
    }

    public Participant participant(Player player) {
        Participant participant = match.participant(player.getUniqueId());
        assertNotNull(participant, player.getName() + " is a participant");
        return participant;
    }

    /** The owner's bound ability instance. */
    public Ability ability() {
        Ability ability = participant(owner).ability();
        assertNotNull(ability, "the owner's ability is bound");
        return ability;
    }

    public MatchContext ctx() {
        return match.context();
    }

    /** The params of the ability under test (with the scenario's overrides). */
    public AbilityParams params() {
        return ctx().paramsFor(abilityId);
    }

    public double param(String key) {
        return params().num(key);
    }

    public int paramTicks(String key) {
        return (int) params().ticks(key);
    }

    /** Puts everyone at the default layout, standing still. */
    public void layout() {
        place(owner, 0.5, 0.5, 0);
        place(enemy, 0.5, 4.5, 180);
        place(enemy2, 2.0, 6.5, 180);
        if (ally != null) {
            place(ally, -1.5, 0.5, 0);
        }
    }

    /** Moves the ally right next to the aimed enemy (1.7 blocks away), inside the area of most skills. */
    public void crossfireAlly() {
        place(ally(), 1.3, 3.0, 180);
    }

    /** Teleports {@code player} onto the floor at (x, z), looking horizontally at {@code yaw}, standing still. */
    public void place(Player player, double x, double z, float yaw) {
        place(player, x, FlatWorld.SURFACE, z, yaw, 0);
    }

    public void place(Player player, double x, double y, double z, float yaw, float pitch) {
        player.teleport(new Location(world, x, y, z, yaw, pitch));
        player.setVelocity(new Vector());
        player.setFallDistance(0);
    }

    /** Makes {@code player} look at {@code target}'s chest. */
    public void aimAt(Player player, Entity target) {
        aimAt(player, target.getLocation().add(0, target.getHeight() * 0.6, 0));
    }

    /** Makes {@code player} look at a point. */
    public void aimAt(Player player, Location point) {
        Location eye = player.getEyeLocation();
        Location look = player.getLocation();
        look.setDirection(point.toVector().subtract(eye.toVector()));
        player.teleport(look);
    }

    /** Makes {@code player} look at the floor at (x, z). */
    public void aimAtFloor(Player player, double x, double z) {
        aimAt(player, new Location(world, x, FlatWorld.SURFACE, z));
    }

    /** Full health for everyone (the ability's own max health modifiers included). */
    public void healAll() {
        for (TestPlayer player : all()) {
            player.setHealth(player.getAttribute(Attribute.MAX_HEALTH).getValue());
            player.setFireTicks(0);
            player.setNoDamageTicks(0);
        }
    }

    public double health(Player player) {
        return player.getHealth();
    }

    public double maxHealth(Player player) {
        return player.getAttribute(Attribute.MAX_HEALTH).getValue();
    }

    /** A plain melee hit from {@code attacker} (a sword swing as the server reports it). */
    public void melee(LivingEntity attacker, LivingEntity victim, double amount) {
        victim.setNoDamageTicks(0);
        victim.damage(amount, DamageSource.builder(attacker instanceof Player ? DamageType.PLAYER_ATTACK
                : DamageType.MOB_ATTACK).withCausingEntity(attacker).withDirectEntity(attacker).build());
    }

    /** True if {@code player} has none of the {@link #HARMFUL} effects. */
    public static boolean hasNoHarmfulEffect(Player player) {
        for (PotionEffectType type : HARMFUL) {
            if (player.hasPotionEffect(type)) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ skills & time

    public void ticks(int count) {
        h.ticks(count);
    }

    public ActivationResult active() {
        return match.useActive(owner);
    }

    /** Fills the gauge and uses the ultimate. */
    public ActivationResult ultimate() {
        match.setGauge(owner, 100);
        return match.useUltimate(owner);
    }

    public void assertSucceeded(ActivationResult result, String what) {
        assertTrue(result.succeeded(), what + " should succeed but failed: " + result.reason());
    }

    /** Resets the active cooldown so the active can be used again right away. */
    public void readyActive() {
        ctx().reduceCooldown(owner, 1000);
    }

    /** Eliminates the owner without a death (disqualification), as if killed by {@code killer}. */
    public void eliminateOwner(@Nullable Player killer) {
        session.eliminate(owner, killer);
    }

    /**
     * Kills the owner for real: a lethal hit from {@code killer} (if any, so the kill is credited) then, should the
     * ability survive it (revive, dodge...), health 0. The owner respawns as a spectator like on a server.
     */
    public void killOwner(@Nullable Player killer) {
        if (killer != null) {
            melee(killer, owner, 1);
        }
        owner.setNoDamageTicks(0);
        owner.damage(10_000, DamageSource.builder(DamageType.GENERIC_KILL).build());
        if (!owner.isDead() && session.isAlive(owner)) {
            owner.setHealth(0);
        }
        assertFalse(session.isAlive(owner), "the owner died");
        respawn(owner);
        assertNull(participant(owner).ability(), "the dead owner's ability is revoked");
    }

    /** Clicks "respawn" for a dead player like the client does (respawn, then the post-respawn event). */
    public void respawn(TestPlayer player) {
        if (player.isDead()) {
            player.respawn();
            h.server.getPluginManager().callEvent(new PlayerPostRespawnEvent(player, player.getLocation(), false,
                    false, false, PlayerRespawnEvent.RespawnReason.DEATH));
        }
    }

    /** Every chat message {@code player} received since the last call, as plain text. */
    public static List<String> messages(TestPlayer player) {
        List<String> messages = new ArrayList<>();
        Component message;
        while ((message = player.nextComponentMessage()) != null) {
            messages.add(plain(message));
        }
        return messages;
    }

    // ------------------------------------------------------------------ damage log

    /** Every damage event that went through since the scenario started (after the reset at the fight start). */
    public List<Hit> hits() {
        return hits;
    }

    /** Total damage {@code victim} took since the scenario started. */
    public double damageTaken(Entity victim) {
        double total = 0;
        for (Hit hit : hits) {
            if (hit.victim().equals(victim)) {
                total += hit.damage();
            }
        }
        return total;
    }

    /** Damage dealt to {@code victim} by hits whose causing entity is {@code attacker}. */
    public double damageBy(Entity attacker, Entity victim) {
        double total = 0;
        for (Hit hit : hits) {
            if (hit.victim().equals(victim) && attacker.equals(hit.causer())) {
                total += hit.damage();
            }
        }
        return total;
    }

    /** The hits {@code victim} took from hits whose causing entity is {@code attacker}. */
    public List<Hit> hitsBy(Entity attacker, Entity victim) {
        List<Hit> found = new ArrayList<>();
        for (Hit hit : hits) {
            if (hit.victim().equals(victim) && attacker.equals(hit.causer())) {
                found.add(hit);
            }
        }
        return found;
    }

    /** The hits {@code victim} took with the given cause. */
    public List<Hit> hitsOf(Entity victim, DamageCause cause) {
        List<Hit> found = new ArrayList<>();
        for (Hit hit : hits) {
            if (hit.victim().equals(victim) && hit.cause() == cause) {
                found.add(hit);
            }
        }
        return found;
    }

    /** Ticks until {@code entity} stands on the ground again (at most {@code max} ticks). */
    public void tickUntilGrounded(Entity entity, int max) {
        h.ticks(2); // the on-ground flag of a teleported entity is only updated by its next move
        h.tickUntil(() -> entity.isOnGround() && entity.getVelocity().getY() <= 0, max,
                entity.getName() + " landing");
    }

    /** Forgets the recorded hits. */
    public void clearHits() {
        hits.clear();
    }

    private final class DamageRecorder implements Listener {

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onDamage(EntityDamageEvent event) {
            DamageSource source = event.getDamageSource();
            hits.add(new Hit(event.getEntity(), source.getCausingEntity(), source.getDirectEntity(), event.getCause(),
                    source.getDamageType(), event.getFinalDamage(), h.server.getScheduler().getCurrentTick()));
        }
    }

    // ------------------------------------------------------------------ problems

    /** Every WARNING or SEVERE record the plugin logged since the scenario started. */
    public List<LogRecord> problems() {
        return problems;
    }

    /** Asserts the plugin logged no warnings: no ability error caught by the guard, no failed ability task. */
    public void assertNoProblems() {
        if (problems.isEmpty() && match.errors().totalFailures() == 0) {
            return;
        }
        StringBuilder report = new StringBuilder("the plugin logged " + problems.size() + " problem(s), the guard "
                + match.errors().totalFailures() + " ability failure(s):");
        for (LogRecord record : problems) {
            report.append("\n- ").append(record.getMessage());
            if (record.getThrown() != null) {
                StringWriter trace = new StringWriter();
                record.getThrown().printStackTrace(new PrintWriter(trace));
                report.append("\n").append(trace.toString().lines().limit(12)
                        .reduce((a, b) -> a + "\n" + b).orElse(""));
            }
        }
        fail(report.toString());
    }

    // ------------------------------------------------------------------ ending & cleanup

    /** Ends the match normally: the enemies are eliminated, the ending runs and everyone is restored. */
    public void finishMatch() {
        for (TestPlayer target : targets()) {
            target.getAttribute(Attribute.MAX_HEALTH).removeModifier(TANK);
        }
        if (session.state() == GameState.RUNNING) {
            for (Player loser : List.of(enemy, enemy2)) {
                if (session.isAlive(loser)) {
                    session.eliminate(loser, null);
                }
            }
        }
        assertNotEquals(GameState.RUNNING, session.state(), "eliminating the enemies ends the match");
        h.ticks(h.hub.config().endingSeconds() * 20 + 5);
        assertEquals(GameState.CLOSED, session.state());
    }

    /** No entity but players (and the result fireworks) in the world. */
    public void assertNoLeftoverEntities() {
        for (Entity entity : world.getEntities()) {
            assertTrue(entity instanceof Player || entity instanceof Firework,
                    "entity left behind: " + entity.getType() + " at " + entity.getLocation());
        }
    }

    /**
     * After {@link #finishMatch()}: nothing the match created is left in the world (entities, temporary blocks,
     * listeners) and every player is back to normal (no plugin attribute modifiers, visible to everyone, no flight,
     * default size and health).
     */
    public void assertCleanWorld() {
        assertNoLeftoverEntities();
        assertEquals(0, h.hub.tempBlocks().size(), "temporary blocks restored");
        assertEquals(0, h.hub.tempBlocks().removedCount(), "broken terrain restored");
        for (int x = -24; x <= 24; x++) {
            for (int z = -24; z <= 24; z++) {
                for (int y = FlatWorld.FLOOR_Y - 2; y <= FlatWorld.FLOOR_Y + 8; y++) {
                    Material expected = y <= FlatWorld.FLOOR_Y ? Material.STONE : Material.AIR;
                    Material actual = world.getBlockAt(x, y, z).getType();
                    if (actual != expected) {
                        fail("block changed at " + x + "," + y + "," + z + ": " + actual);
                    }
                }
            }
        }
        for (TestPlayer player : all()) {
            for (Attribute attribute : Registry.ATTRIBUTE) {
                AttributeInstance instance = player.getAttribute(attribute);
                if (instance == null) {
                    continue;
                }
                for (AttributeModifier modifier : instance.getModifiers()) {
                    fail(player.getName() + " kept a modifier on " + attribute.getKey() + ": " + modifier.getKey());
                }
            }
            assertEquals(20.0, player.getAttribute(Attribute.MAX_HEALTH).getValue(), 1.0E-9,
                    player.getName() + " max health");
            assertFalse(player.isFlying(), player.getName() + " is not flying");
            assertFalse(player.getAllowFlight(), player.getName() + " cannot fly");
            assertNotEquals(GameMode.SPECTATOR, player.getGameMode(), player.getName() + " is restored");
            assertFalse(player.isInvisible(), player.getName() + " is not invisible");
            for (TestPlayer viewer : all()) {
                assertTrue(viewer.canSee(player), viewer.getName() + " sees " + player.getName() + " again");
            }
        }
        assertEquals(listenersBefore, listenerCount(), "every listener the match registered is unregistered");
    }

    /** {@link #finishMatch()}, {@link #assertNoProblems()} and {@link #assertCleanWorld()}. */
    public void finishClean() {
        finishMatch();
        assertNoProblems();
        assertCleanWorld();
    }

    private int listenerCount() {
        int count = 0;
        for (RegisteredListener listener : EntityDamageEvent.getHandlerList().getRegisteredListeners()) {
            if (listener.getPlugin() == h.hub.plugin()) {
                count++;
            }
        }
        return count;
    }

    /** Team of a player (team mode only). */
    public @Nullable MatchTeam teamOf(Player player) {
        return session.teamOf(player);
    }

    /** The plain text of a component (for messages). */
    public static String plain(Component component) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(component);
    }

    @Override
    public void close() {
        logger.removeHandler(handler);
        h.close();
    }
}
