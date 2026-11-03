package io.github.sx2xoox.mcpl.game.siege.lanes;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.game.siege.SiegeGame;
import io.github.sx2xoox.mcpl.game.siege.arena.SiegeArena;
import io.github.sx2xoox.mcpl.game.siege.arena.SiegeSystems;
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureMover;
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureSkin;
import io.github.sx2xoox.mcpl.game.siege.creature.SkinnedCreatureLook;
import io.github.sx2xoox.mcpl.game.siege.creature.VanillaCreatureLook;
import io.github.sx2xoox.mcpl.game.superpower.support.MatchHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * Runs the 공성전 v2 lane systems ({@link SiegeSystems}) on a flat world with {@link TestLaneSites} and a plain
 * {@link SiegeArena} ({@link TestArena}) instead of the match rules.
 */
abstract class LaneTestBase {

    static final int Y = (int) FlatWorld.SURFACE;

    MatchHarness h;
    MemoryConfiguration config;
    TestArena arena;
    SiegeSystems systems;
    CreatureMover mover = CreatureMover.pathfinder();
    /** What the creatures' skins were asked to play: {@code "<kind> attack"}, {@code "<kind> hit"}. */
    final List<String> skinEvents = new ArrayList<>();
    /** A skin that records its visuals' calls (the look is the match's: vanilla bodies + a skin). */
    final CreatureSkin recordingSkin = (body, kind, team) -> new CreatureSkin.Visual() {
        @Override
        public void hit() {
            skinEvents.add(kind + " hit");
        }

        @Override
        public void attack() {
            skinEvents.add(kind + " attack");
        }

        @Override
        public void remove() {
        }
    };

    @BeforeEach
    void setUpLanes() {
        h = MatchHarness.flat();
        config = new MemoryConfiguration();
        configure(config);
    }

    /** Settings for the systems (before {@link #start}). */
    void configure(MemoryConfiguration config) {
    }

    @AfterEach
    void tearDownLanes() {
        if (systems != null) {
            systems.stop();
            for (Listener listener : systems.listeners()) {
                HandlerList.unregisterAll(listener);
            }
        }
        h.close();
    }

    /** Starts the systems with {@code blue} and {@code red} players (all at their spawn), fight on. */
    void start(int blue, int red) {
        SiegeGame siege = (SiegeGame) h.hub.games().game(SiegeGame.ID);
        arena = new TestArena(h.hub.plugin(), siege.messages(), new TestLaneSites(Y), h.flatWorld());
        for (int i = 0; i < blue; i++) {
            arena.add(join("B" + (i + 1)), LaneSites.Side.BLUE);
        }
        for (int i = 0; i < red; i++) {
            arena.add(join("R" + (i + 1)), LaneSites.Side.RED);
        }
        systems = new SiegeSystems(arena, config,
                new SkinnedCreatureLook(new VanillaCreatureLook(), recordingSkin), mover);
        for (Listener listener : systems.listeners()) {
            Bukkit.getPluginManager().registerEvents(listener, h.hub.plugin());
        }
        systems.beginRound();
    }

    private TestPlayer join(String name) {
        TestPlayer player = h.join(name);
        player.teleport(new Location(h.flatWorld(), 0.5, Y, 60.5));
        return player;
    }

    /** Runs the systems and the server for {@code count} ticks. */
    void run(int count) {
        for (int i = 0; i < count; i++) {
            systems.tick();
            h.ticks(1);
        }
    }

    Player player(LaneSites.Side side, int index) {
        return arena.players.get(side).get(index);
    }

    Location at(double x, double z) {
        return new Location(h.flatWorld(), x + 0.5, Y, z + 0.5);
    }

    long count(Class<? extends Entity> type) {
        return h.flatWorld().getEntities().stream().filter(type::isInstance).filter(e -> !e.isDead()).count();
    }

    static DamageSource by(Entity attacker) {
        return DamageSource.builder(DamageType.PLAYER_ATTACK).withCausingEntity(attacker).withDirectEntity(attacker)
                .build();
    }

    /** Ability damage from {@code attacker} (the arena reports ability damage while it is delivered). */
    void abilityHit(Player attacker, LivingEntity victim, double amount) {
        arena.abilityDamage = true;
        try {
            victim.setNoDamageTicks(0);
            victim.damage(amount, DamageSource.builder(DamageType.MAGIC).withCausingEntity(attacker)
                    .withDirectEntity(attacker).build());
        } finally {
            arena.abilityDamage = false;
        }
    }

    /** A melee hit from {@code attacker}. */
    static void melee(Player attacker, LivingEntity victim, double amount) {
        victim.setNoDamageTicks(0);
        victim.damage(amount, by(attacker));
    }

    /** The match side of the tests. */
    static final class TestArena implements SiegeArena {
        final JavaPlugin plugin;
        final Messages messages;
        final LaneSites sites;
        final World world;
        final Map<LaneSites.Side, List<Player>> players = new EnumMap<>(LaneSites.Side.class);
        final Map<UUID, LaneSites.Side> sides = new HashMap<>();
        final Set<UUID> out = new HashSet<>();
        final Map<LaneSites.Side, Integer> score = new EnumMap<>(LaneSites.Side.class);
        final Map<UUID, Integer> xp = new HashMap<>();
        final Set<Entity> tracked = new HashSet<>();
        final Map<LaneSites.Side, List<LivingEntity>> structures = new EnumMap<>(LaneSites.Side.class);
        final Map<LivingEntity, Double> structureDamage = new LinkedHashMap<>();
        final Map<UUID, Double> cooldownGiven = new HashMap<>();
        boolean fighting = true;
        boolean abilityDamage;
        final List<Sound> sounds = new ArrayList<>();

        TestArena(JavaPlugin plugin, Messages messages, LaneSites sites, World world) {
            this.plugin = plugin;
            this.messages = messages;
            this.sites = sites;
            this.world = world;
            for (LaneSites.Side side : LaneSites.Side.values()) {
                players.put(side, new ArrayList<>());
                structures.put(side, new ArrayList<>());
                score.put(side, 0);
            }
        }

        void add(Player player, LaneSites.Side side) {
            players.get(side).add(player);
            sides.put(player.getUniqueId(), side);
        }

        @Override
        public JavaPlugin plugin() {
            return plugin;
        }

        @Override
        public Messages messages() {
            return messages;
        }

        @Override
        public LaneSites sites() {
            return sites;
        }

        @Override
        public World world() {
            return world;
        }

        @Override
        public Location at(int x, int y, int z) {
            return new Location(world, x + 0.5, y, z + 0.5);
        }

        @Override
        public LaneSites.@Nullable Side side(UUID player) {
            return sides.get(player);
        }

        @Override
        public List<UUID> members(LaneSites.Side side) {
            return players.get(side).stream().map(Player::getUniqueId).toList();
        }

        @Override
        public List<Player> fighters(LaneSites.Side side) {
            return players.get(side).stream().filter(p -> !p.isDead() && !out.contains(p.getUniqueId())).toList();
        }

        @Override
        public boolean isFighting() {
            return fighting;
        }

        @Override
        public void sound(Location at, Sound sound, float volume, float pitch) {
            sounds.add(sound);
        }

        @Override
        public void addScore(LaneSites.Side side, int points) {
            score.merge(side, points, Integer::sum);
        }

        @Override
        public void addXp(UUID player, int amount) {
            xp.merge(player, amount, Integer::sum);
        }

        @Override
        public void track(Entity entity) {
            tracked.add(entity);
        }

        @Override
        public void untrack(Entity entity) {
            tracked.remove(entity);
        }

        @Override
        public void broadcast(Component message) {
        }

        @Override
        public @Nullable Player attacker(EntityDamageEvent event) {
            Entity causing = event.getDamageSource().getCausingEntity();
            if (causing instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
                return shooter;
            }
            return causing instanceof Player player ? player : null;
        }

        @Override
        public boolean isAbilityDamage() {
            return abilityDamage;
        }

        @Override
        public void reduceCooldown(Player player, double seconds) {
            cooldownGiven.merge(player.getUniqueId(), seconds, Double::sum);
        }

        @Override
        public List<LivingEntity> structures(LaneSites.Side side) {
            return structures.get(side);
        }

        @Override
        public boolean isStructure(Entity entity) {
            return structures.values().stream().anyMatch(list -> list.contains(entity));
        }

        @Override
        public void damageStructure(LivingEntity structure, LaneSites.Side attacker, double amount) {
            structureDamage.merge(structure, amount, Double::sum);
        }

        int xp(Player player) {
            return xp.getOrDefault(player.getUniqueId(), 0);
        }
    }
}
