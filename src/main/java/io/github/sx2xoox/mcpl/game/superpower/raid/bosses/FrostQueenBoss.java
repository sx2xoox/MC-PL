package io.github.sx2xoox.mcpl.game.superpower.raid.bosses;

import io.github.sx2xoox.mcpl.game.superpower.raid.BossInfo;
import io.github.sx2xoox.mcpl.game.superpower.raid.RaidBoss;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Stray;

/** 서리 여왕 — TODO: placeholder body without attack patterns. */
public final class FrostQueenBoss extends RaidBoss {

    public static final BossInfo INFO = new BossInfo("frost_queen", "서리 여왕", "영원한 겨울의 주인", TextColor.color(0x8EDBFF),
            BossBar.Color.BLUE);

    @Override
    public BossInfo info() {
        return INFO;
    }

    @Override
    public LivingEntity spawn(Location at, double healthMultiplier) {
        Stray body = at.getWorld().spawn(at, Stray.class);
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
