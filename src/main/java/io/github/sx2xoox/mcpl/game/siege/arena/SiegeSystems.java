package io.github.sx2xoox.mcpl.game.siege.arena;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import io.github.sx2xoox.mcpl.game.siege.balance.SiegeModifiers;
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureLook;
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureMover;
import io.github.sx2xoox.mcpl.game.siege.economy.Gold;
import io.github.sx2xoox.mcpl.game.siege.jungle.JungleObjectives;
import io.github.sx2xoox.mcpl.game.siege.jungle.JungleSettings;
import io.github.sx2xoox.mcpl.game.siege.minion.MinionSettings;
import io.github.sx2xoox.mcpl.game.siege.minion.MinionWaves;
import io.github.sx2xoox.mcpl.game.siege.shop.ShopSettings;
import io.github.sx2xoox.mcpl.game.siege.shop.SiegeShop;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.jspecify.annotations.Nullable;

/**
 * The lane systems of 공성전 v2 in one place (docs/siege-v2.md §2–§4): {@link Gold}, {@link MinionWaves},
 * {@link JungleObjectives}, {@link SiegeShop} and the shared {@link LaneModifiers}. The match (rules) creates it
 * with its {@link SiegeArena}, registers {@link #listeners()} with the session and calls:
 *
 * <ul>
 *     <li>{@link #beginRound()} when a round's fight starts, {@link #tick()} every tick, {@link #endRound()} when the
 *     round ends and {@link #stop()} when the match ends;</li>
 *     <li>from its {@code MatchHost}: {@link #extraTargets()}, {@link #isExtraTarget}, {@link #isExtraEnemy} (minions
 *     are enemies of the other side, monsters of everyone);</li>
 *     <li>from its rules: {@link #playerKilled} (kill and assist gold), {@link #towerDestroyed} (team gold),
 *     {@link #respawnSeconds} (부활 시계), {@link #towerTarget} (towers shoot minions first),
 *     {@link #hudLine()} (dragon and buff timers for the sidebar), {@link Gold#balance} (sidebar gold).</li>
 * </ul>
 *
 * This class also applies the {@link LaneModifiers} that are not attributes: ability damage dealt and taken and
 * structure damage (on every damage event) and cooldown reduction (every second).
 */
public final class SiegeSystems implements Listener {

    private final SiegeArena arena;
    private final LaneModifiers modifiers;
    private final Gold gold;
    private final MinionWaves minions;
    private final JungleObjectives jungle;
    private final SiegeShop shop;
    private long ticks;
    private boolean running;

    public SiegeSystems(SiegeArena arena, ConfigurationSection config, CreatureLook look, CreatureMover mover) {
        this(arena, config, look, mover, null);
    }

    /**
     * Systems whose ability-stat modifiers (shop items, buffs) go to the match's balance modifiers
     * ({@code SiegeMatch#modifiers()}), which the hosted ability match applies.
     */
    public SiegeSystems(SiegeArena arena, ConfigurationSection config, CreatureLook look, CreatureMover mover,
            @Nullable SiegeModifiers balance) {
        this.arena = arena;
        this.modifiers = new LaneModifiers(balance);
        this.gold = new Gold(arena, Gold.Settings.read(config));
        this.minions = new MinionWaves(arena, MinionSettings.read(config), look, mover, gold);
        this.jungle = new JungleObjectives(arena, JungleSettings.read(config), look, mover, gold, modifiers, minions);
        this.shop = new SiegeShop(arena, ShopSettings.read(config), gold, modifiers, look);
    }

    /** Everything the session must register. */
    public List<Listener> listeners() {
        return List.of(this, minions, jungle, shop);
    }

    // ------------------------------------------------------------------ flow

    /** A round's fight begins: fresh gold, items, minion clock and jungle timers. */
    public void beginRound() {
        modifiers.clear();
        gold.reset();
        shop.begin();
        minions.begin();
        jungle.begin();
        ticks = 0;
        running = true;
    }

    /** Every server tick (the systems pause while {@link SiegeArena#isFighting()} is false). */
    public void tick() {
        if (!running) {
            return;
        }
        shop.tick();
        if (!arena.isFighting()) {
            return;
        }
        ticks++;
        minions.tick();
        jungle.tick();
        if (ticks % 20 == 0) {
            gold.second();
            cooldowns();
        }
    }

    /** The round is over: minions, monsters, items, buffs and gold are reset. */
    public void endRound() {
        running = false;
        minions.clear();
        jungle.clear();
        shop.clear();
        modifiers.clear();
        gold.reset();
    }

    /** The match is over. */
    public void stop() {
        endRound();
    }

    // ------------------------------------------------------------------ MatchHost hooks

    /** Minions and monsters abilities may target. */
    public List<LivingEntity> extraTargets() {
        List<LivingEntity> out = new ArrayList<>(minions.bodies());
        out.addAll(jungle.targets());
        return out;
    }

    public boolean isExtraTarget(Entity entity) {
        return minions.minionOf(entity) != null || jungle.siteOf(entity) != null;
    }

    public boolean isExtraEnemy(Player owner, Entity entity) {
        return minions.isEnemy(owner, entity) || jungle.isEnemy(owner, entity);
    }

    // ------------------------------------------------------------------ rules hooks

    /** A player was killed: kill and assist gold. */
    public void playerKilled(@Nullable UUID killer, Collection<UUID> assisters) {
        gold.kill(killer, assisters);
    }

    /** {@code side} destroyed an enemy tower: gold for the whole team. */
    public void towerDestroyed(LaneSites.Side side) {
        gold.towerDestroyed(side);
    }

    /** The change (seconds, negative = sooner) a player's items make to the respawn delay. */
    public double respawnSeconds(UUID player) {
        return modifiers.respawnSeconds(player);
    }

    /** What a tower of {@code towerSide} at {@code at} should shoot first: the nearest enemy minion in range. */
    public @Nullable Mob towerTarget(LaneSites.Side towerSide, Location at, double radius) {
        return minions.nearestMinion(MinionWaves.enemy(towerSide), at, radius);
    }

    /** The sidebar line about the dragon and the buff monsters, or null. */
    public @Nullable Component hudLine() {
        return jungle.hudLine();
    }

    // ------------------------------------------------------------------ modifiers

    /** Ability damage dealt and taken, and damage to structures, as the {@link LaneModifiers} say. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!running) {
            return;
        }
        Player attacker = arena.attacker(event);
        UUID victim = event.getEntity() instanceof Player player ? player.getUniqueId() : null;
        double factor = 1;
        if (arena.isAbilityDamage() && !modifiers.bridged()) {
            factor *= modifiers.skillDamageFactor(attacker == null ? null : attacker.getUniqueId(), victim);
        }
        if (attacker != null && arena.isStructure(event.getEntity())) {
            factor *= modifiers.structureDamageFactor(attacker.getUniqueId());
        }
        if (factor != 1) {
            event.setDamage(event.getDamage() * factor);
        }
    }

    /** Cooldowns run faster by the reduction: {@code r} shorter means {@code r / (1 − r)} extra seconds a second. */
    private void cooldowns() {
        if (modifiers.bridged()) {
            return;
        }
        for (LaneSites.Side side : LaneSites.Side.values()) {
            for (Player player : arena.fighters(side)) {
                double reduction = modifiers.cooldownReduction(player.getUniqueId());
                if (reduction > 0) {
                    arena.reduceCooldown(player, reduction / (1 - reduction));
                }
            }
        }
    }

    // ------------------------------------------------------------------ parts

    public LaneModifiers modifiers() {
        return modifiers;
    }

    public Gold gold() {
        return gold;
    }

    public MinionWaves minions() {
        return minions;
    }

    public JungleObjectives jungle() {
        return jungle;
    }

    public SiegeShop shop() {
        return shop;
    }
}
