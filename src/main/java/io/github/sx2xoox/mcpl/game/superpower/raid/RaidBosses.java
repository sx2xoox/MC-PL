package io.github.sx2xoox.mcpl.game.superpower.raid;

import java.util.List;
import java.util.function.Supplier;

import io.github.sx2xoox.mcpl.game.superpower.raid.bosses.FlameLordBoss;
import io.github.sx2xoox.mcpl.game.superpower.raid.bosses.FrostQueenBoss;
import io.github.sx2xoox.mcpl.game.superpower.raid.bosses.GolemBoss;
import io.github.sx2xoox.mcpl.game.superpower.raid.bosses.VoidLordBoss;

/** The built-in raid bosses in the order they appear (boss waves 5, 10, 15, 20, then repeating harder). */
public final class RaidBosses {

    private RaidBosses() {
    }

    public static List<Supplier<RaidBoss>> all() {
        return List.of(GolemBoss::new, FlameLordBoss::new, FrostQueenBoss::new, VoidLordBoss::new);
    }
}
