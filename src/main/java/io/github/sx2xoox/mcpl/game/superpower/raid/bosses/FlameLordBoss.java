package io.github.sx2xoox.mcpl.game.superpower.raid.bosses;

import io.github.sx2xoox.mcpl.game.superpower.raid.BossInfo;
import io.github.sx2xoox.mcpl.game.superpower.raid.RaidBoss;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Blaze;

/** 화염 군주 — TODO: placeholder body without attack patterns. */
public final class FlameLordBoss extends RaidBoss {

    public static final BossInfo INFO = new BossInfo("flame_lord", "화염 군주", "네더의 왕좌에서", TextColor.color(0xFF5A1F),
            BossBar.Color.RED);

    @Override
    public BossInfo info() {
        return INFO;
    }

    @Override
    public LivingEntity spawn(Location at, double healthMultiplier) {
        Blaze body = at.getWorld().spawn(at, Blaze.class);
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
