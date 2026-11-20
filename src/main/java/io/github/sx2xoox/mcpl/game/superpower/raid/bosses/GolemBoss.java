package io.github.sx2xoox.mcpl.game.superpower.raid.bosses;

import io.github.sx2xoox.mcpl.game.superpower.raid.BossInfo;
import io.github.sx2xoox.mcpl.game.superpower.raid.RaidBoss;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.IronGolem;

/** 고대 수호 골렘 — TODO: placeholder body without attack patterns. */
public final class GolemBoss extends RaidBoss {

    public static final BossInfo INFO = new BossInfo("golem", "고대 수호 골렘", "잠에서 깨어난 산", TextColor.color(0xB0A08A),
            BossBar.Color.WHITE);

    @Override
    public BossInfo info() {
        return INFO;
    }

    @Override
    public LivingEntity spawn(Location at, double healthMultiplier) {
        IronGolem body = at.getWorld().spawn(at, IronGolem.class);
        var maxHealth = body.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue(200 * healthMultiplier);
            body.setHealth(maxHealth.getValue());
        }
        return ctx().registerHostile(body);
    }

    @Override
    public void tick(long bossTick) {
    }
}
