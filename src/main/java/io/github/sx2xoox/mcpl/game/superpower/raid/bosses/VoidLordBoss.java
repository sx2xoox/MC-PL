package io.github.sx2xoox.mcpl.game.superpower.raid.bosses;

import io.github.sx2xoox.mcpl.game.superpower.raid.BossInfo;
import io.github.sx2xoox.mcpl.game.superpower.raid.RaidBoss;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Enderman;

/** 공허의 군주 — TODO: placeholder body without attack patterns. */
public final class VoidLordBoss extends RaidBoss {

    public static final BossInfo INFO = new BossInfo("void_lord", "공허의 군주", "모든 것의 끝", TextColor.color(0x8A2BE2),
            BossBar.Color.PURPLE);

    @Override
    public BossInfo info() {
        return INFO;
    }

    @Override
    public LivingEntity spawn(Location at, double healthMultiplier) {
        Enderman body = at.getWorld().spawn(at, Enderman.class);
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
