package io.github.sx2xoox.mcpl.game.siege;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.game.siege.arena.SiegeArena;
import io.github.sx2xoox.mcpl.game.siege.arena.SiegeSystems;
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureMover;
import io.github.sx2xoox.mcpl.game.siege.creature.Creatures;
import io.github.sx2xoox.mcpl.game.siege.creature.ModelCreatureLook;
import io.github.sx2xoox.mcpl.game.siege.creature.SkinnedCreatureLook;
import io.github.sx2xoox.mcpl.game.siege.creature.VanillaCreatureLook;
import io.github.sx2xoox.mcpl.game.siege.economy.Gold;
import io.github.sx2xoox.mcpl.game.siege.tower.Tower;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/**
 * The 공성전 v2 lane systems ({@link SiegeSystems}: minions, jungle objectives, gold, shop) plugged into a
 * {@link SiegeMatch}: this module drives them through the round and forwards the match's hooks, and its
 * {@link SiegeArena} answers what they ask of the rules (sides, fighters, structures, score, experience, overhead
 * bars, damage numbers, sounds). Creatures wear the 3D models of the pack ({@link ModelCreatureLook}) for players who
 * have it, and the vanilla mob for everyone else; their overhead bars are separate displays that follow them (players
 * with the pack do not see the mob, so a bar riding on it would be invisible to them).
 */
final class LaneSystemsModule implements SiegeModule {

    /** Blocks above a creature's head where its bar floats. */
    static final double BAR_GAP = 0.45;

    private final SiegeMatch match;
    private final GameSession session;
    private final SiegeSystems systems;
    private final Map<UUID, Bar> bars = new HashMap<>();

    private record Bar(LivingEntity creature, OverheadBar bar) {
    }

    LaneSystemsModule(SiegeMatch match) {
        this.match = match;
        this.session = match.session();
        ModelCreatureLook skins = new ModelCreatureLook(session.plugin(), match.hub().resourcePacks());
        session.registerListener(skins);
        this.systems = new SiegeSystems(new Arena(), session.gameConfig(),
                new SkinnedCreatureLook(new VanillaCreatureLook(), skins), CreatureMover.pathfinder(),
                match.modifiers());
        for (var listener : systems.listeners()) {
            session.registerListener(listener);
        }
        Gold gold = systems.gold();
        if (match.economy() != SiegeEconomy.NONE) {
            return;
        }
        match.economy(new SiegeEconomy() {
            @Override
            public int gold(UUID player) {
                return gold.balance(player);
            }

            @Override
            public void addGold(UUID player, int amount, String reason) {
                gold.add(player, amount, reason);
            }
        });
    }

    SiegeSystems systems() {
        return systems;
    }

    // ------------------------------------------------------------------ flow

    @Override
    public void fightStarted() {
        systems.beginRound();
    }

    @Override
    public void tick(long fightTicks) {
        systems.tick();
        followBars();
    }

    @Override
    public void roundEnded() {
        systems.endRound();
        clearBars();
    }

    @Override
    public void stop() {
        systems.stop();
        clearBars();
    }

    // ------------------------------------------------------------------ hooks

    @Override
    public void playerKilled(UUID victim, @Nullable UUID killer, List<UUID> assisters) {
        systems.playerKilled(killer, assisters);
    }

    @Override
    public void structureDestroyed(Tower tower, LaneSites.Side by, @Nullable UUID destroyer) {
        if (!tower.isCore()) {
            systems.towerDestroyed(by);
        }
    }

    @Override
    public int respawnTicks(UUID player, int ticks) {
        return ticks + (int) Math.round(systems.respawnSeconds(player) * 20);
    }

    @Override
    public List<LivingEntity> targets() {
        return systems.extraTargets();
    }

    @Override
    public boolean isTarget(Entity entity) {
        return systems.isExtraTarget(entity);
    }

    @Override
    public boolean isEnemy(Player player, Entity entity) {
        return systems.isExtraEnemy(player, entity);
    }

    @Override
    public @Nullable LivingEntity towerTarget(Tower tower, double range) {
        return systems.towerTarget(tower.owner(), tower.base(), range);
    }

    @Override
    public void towerShot(Tower tower, LivingEntity target, double damage) {
        target.damage(damage);
    }

    @Override
    public @Nullable Component sidebarLine(Player viewer) {
        return systems.hudLine();
    }

    // ------------------------------------------------------------------ overhead bars

    private void followBars() {
        for (Iterator<Bar> it = bars.values().iterator(); it.hasNext(); ) {
            Bar entry = it.next();
            LivingEntity creature = entry.creature();
            if (!creature.isValid() || creature.isDead()) {
                entry.bar().remove();
                it.remove();
                continue;
            }
            entry.bar().moveTo(barSpot(creature));
            entry.bar().health(creature.getHealth(), Creatures.maxHealth(creature));
        }
    }

    private static Location barSpot(LivingEntity creature) {
        return creature.getLocation().add(0, creature.getHeight() + BAR_GAP, 0);
    }

    private void clearBars() {
        for (Bar entry : bars.values()) {
            entry.bar().remove();
        }
        bars.clear();
    }

    /** The bar of a creature (tests). */
    @Nullable OverheadBar bar(LivingEntity creature) {
        Bar entry = bars.get(creature.getUniqueId());
        return entry == null ? null : entry.bar();
    }

    // ------------------------------------------------------------------ the arena

    /** What the lane systems ask of the rules. */
    private final class Arena implements SiegeArena {

        @Override
        public JavaPlugin plugin() {
            return session.plugin();
        }

        @Override
        public Messages messages() {
            return match.messages();
        }

        @Override
        public LaneSites sites() {
            return match.sites();
        }

        @Override
        public World world() {
            return match.layout().world();
        }

        @Override
        public Location at(int x, int y, int z) {
            return match.layout().at(new LaneSites.Point(x, y, z));
        }

        @Override
        public LaneSites.@Nullable Side side(UUID player) {
            return match.side(player);
        }

        @Override
        public List<UUID> members(LaneSites.Side side) {
            return match.members(match.teamOf(side));
        }

        @Override
        public List<Player> fighters(LaneSites.Side side) {
            List<Player> out = new ArrayList<>();
            for (Player player : match.online(match.teamOf(side))) {
                if (session.isInPlay(player) && !player.isDead()) {
                    out.add(player);
                }
            }
            return out;
        }

        @Override
        public List<LivingEntity> extraFighters(LaneSites.Side side) {
            List<LivingEntity> out = new ArrayList<>();
            for (SiegeModule module : match.modules()) {
                out.addAll(module.fighters(side));
            }
            return out;
        }

        @Override
        public @Nullable UUID fighterOf(@Nullable Entity entity) {
            return match.fighterOf(entity);
        }

        @Override
        public boolean isFighting() {
            return match.phase() == SiegeMatch.Phase.FIGHT;
        }

        @Override
        public void addScore(LaneSites.Side side, int points) {
            match.addScore(side, points, "lanes");
        }

        @Override
        public void addXp(UUID player, int xp) {
            match.addXp(player, xp);
        }

        @Override
        public void track(Entity entity) {
            session.track(entity);
        }

        @Override
        public void untrack(Entity entity) {
            session.untrack(entity);
        }

        @Override
        public void broadcast(Component message) {
            session.broadcast(message);
        }

        @Override
        public void sound(Location at, Sound sound, float volume, float pitch) {
            match.sounds().at(at, sound, volume, pitch);
        }

        @Override
        public @Nullable Player attacker(EntityDamageEvent event) {
            return session.combat().resolveAttacker(event);
        }

        @Override
        public boolean isAbilityDamage() {
            return match.abilities().context().isAbilityDamage();
        }

        @Override
        public void reduceCooldown(Player player, double seconds) {
            match.abilities().context().reduceCooldown(player, seconds);
        }

        @Override
        public List<LivingEntity> structures(LaneSites.Side side) {
            List<LivingEntity> out = new ArrayList<>();
            for (Tower tower : match.towers(side)) {
                LivingEntity body = tower.hitbox();
                if (body != null && body.isValid() && !tower.isDestroyed() && !tower.isShielded()) {
                    out.add(body);
                }
            }
            return out;
        }

        @Override
        public boolean isStructure(Entity entity) {
            return match.towerOf(entity) != null;
        }

        @Override
        public void damageStructure(LivingEntity structure, LaneSites.Side attacker, double amount) {
            Tower tower = match.towerOf(structure);
            if (tower != null) {
                match.damageStructure(tower, attacker, amount, null);
            }
        }

        @Override
        public boolean overheadBar(LivingEntity creature, Component name) {
            Bar entry = bars.get(creature.getUniqueId());
            if (entry == null) {
                TextColor colour = name.color() != null ? name.color() : NamedTextColor.RED;
                entry = new Bar(creature, OverheadBar.at(session, barSpot(creature), name, colour, 0.75f));
                bars.put(creature.getUniqueId(), entry);
            } else {
                entry.bar().name(name);
            }
            entry.bar().health(creature.getHealth(), Creatures.maxHealth(creature));
            return true;
        }

        @Override
        public void removeOverheadBar(LivingEntity creature) {
            Bar entry = bars.remove(creature.getUniqueId());
            if (entry != null) {
                entry.bar().remove();
            }
        }

        @Override
        public void damageNumber(LivingEntity victim, double amount) {
            match.damageNumbers().show(victim, amount, DamageNumbers.Kind.ofHit(isAbilityDamage(),
                    match.abilities().context().basicHitAccent()));
        }
    }
}
