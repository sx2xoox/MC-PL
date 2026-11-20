package io.github.sx2xoox.mcpl.game.superpower.support;

import java.util.ArrayList;
import java.util.List;

import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;

/** A deterministic test ability that records every hook call. Register it before a match starts. */
public final class ProbeAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("probe", "탐침", Material.STICK)
            .color(TextColor.color(0x55FFAA))
            .difficulty(2)
            .passive("관찰", "모든 것을 {power}번 기록합니다.")
            .active("찌르기", "아무 일도 하지 않습니다.", 10)
            .ultimate("기록", "아무 일도 하지 않습니다.")
            .param("power", 3)
            .build();

    /** Every instance created since the last {@link #reset()}. */
    public static final List<ProbeAbility> INSTANCES = new ArrayList<>();

    public int grants;
    public int revokes;
    public int ticks;
    public int actives;
    public int ultimates;
    public int dealt;
    /** Hits that landed ({@link Ability#onDamageDealt}). */
    public int landed;
    public int taken;
    public int kills;
    public int projectileHits;
    public boolean failActive;
    public double multiplier = 0.5;

    public ProbeAbility() {
        INSTANCES.add(this);
    }

    public static void reset() {
        INSTANCES.clear();
    }

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onGrant() {
        grants++;
    }

    @Override
    public void onRevoke() {
        revokes++;
    }

    @Override
    public void onTick(long tick) {
        ticks++;
    }

    @Override
    public ActivationResult onActive() {
        actives++;
        return failActive ? ActivationResult.fail("<red>지금은 안 됩니다") : ActivationResult.success();
    }

    @Override
    public ActivationResult onUltimate() {
        ultimates++;
        return ActivationResult.success();
    }

    @Override
    public double cooldownMultiplier() {
        return multiplier;
    }

    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        dealt++;
    }

    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        landed++;
    }

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        taken++;
    }

    @Override
    public void onProjectileHit(ProjectileHitEvent event, String tag) {
        projectileHits++;
    }

    @Override
    public void onKill(Player victim) {
        kills++;
    }
}
