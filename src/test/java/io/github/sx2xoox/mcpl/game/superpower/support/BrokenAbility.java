package io.github.sx2xoox.mcpl.game.superpower.support;

import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

/** An ability that throws from every hook, to prove the match isolates ability errors. */
public final class BrokenAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("broken", "고장", Material.BARRIER)
            .color(TextColor.color(0xFF0000))
            .passive("고장", "고장났습니다.")
            .active("고장", "고장났습니다.", 1)
            .ultimate("고장", "고장났습니다.")
            .build();

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onGrant() {
        throw new IllegalStateException("grant");
    }

    @Override
    public void onRevoke() {
        throw new IllegalStateException("revoke");
    }

    @Override
    public void onTick(long tick) {
        throw new IllegalStateException("tick");
    }

    @Override
    public ActivationResult onActive() {
        throw new IllegalStateException("active");
    }

    @Override
    public ActivationResult onUltimate() {
        throw new IllegalStateException("ultimate");
    }

    @Override
    public double cooldownMultiplier() {
        throw new IllegalStateException("multiplier");
    }

    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        throw new IllegalStateException("deal");
    }

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        throw new IllegalStateException("take");
    }

    @Override
    public void onKill(Player victim) {
        throw new IllegalStateException("kill");
    }
}
