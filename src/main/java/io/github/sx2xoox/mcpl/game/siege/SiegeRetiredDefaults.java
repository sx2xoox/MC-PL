package io.github.sx2xoox.mcpl.game.siege;

import io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttackSettings;
import org.bukkit.configuration.ConfigurationSection;

/**
 * The {@code games.siege.*} defaults written to {@code config.yml} by versions before the defaults bookkeeping
 * ({@link io.github.sx2xoox.mcpl.core.config.HubConfig#applyGameDefaults(String, java.util.function.Consumer,
 * java.util.function.Consumer)}) whose value the 공성전 balance patch changed (docs/siege-v2.md §11). A server that
 * still holds exactly these values never chose them: they move to today's defaults. Only changed keys are listed.
 */
final class SiegeRetiredDefaults {

    private SiegeRetiredDefaults() {
    }

    static void declare(ConfigurationSection d) {
        d.set(SiegeProgressSettings.XP_FIRST, 60);
        d.set(SiegeProgressSettings.XP_STEP, 40);
        d.set(SiegeSettings.RESPAWN_MAX_SECONDS, 14);
        d.set(SiegeSettings.OUTER_HEALTH, 1500);
        d.set(SiegeSettings.INNER_HEALTH, 2000);
        String a = SiegeMatch.BASIC_ATTACKS + "." + BasicAttackSettings.ATTACKS + ".";
        // basic attacks: {ability id, key, value before the patch}
        for (Object[] old : BASIC_ATTACKS) {
            d.set(a + old[0] + "." + old[1], old[2]);
        }
    }

    /** Basic attack numbers before the patch (only the ones it changed). */
    private static final Object[][] BASIC_ATTACKS = {
        {"ninja", BasicAttackSettings.DAMAGE, 1.6},
        {"ninja", BasicAttackSettings.ANGLE, 20.0},
        {"chrono", BasicAttackSettings.ANGLE, 12.0},
        {"phoenix", BasicAttackSettings.DAMAGE, 1.8},
        {"phoenix", BasicAttackSettings.ANGLE, 10.0},
        {"gumiho", BasicAttackSettings.DAMAGE, 1.6},
        {"gumiho", BasicAttackSettings.ANGLE, 24.0},
        {"blade", BasicAttackSettings.DAMAGE, 5.0},
        {"duelist", BasicAttackSettings.DAMAGE, 4.8},
        {"fighter", BasicAttackSettings.DAMAGE, 2.4},
        {"wukong", BasicAttackSettings.DAMAGE, 2.0},
        {"bear", BasicAttackSettings.DAMAGE, 6.0},
        {"dragon", BasicAttackSettings.DAMAGE, 2.0},
        {"earth", BasicAttackSettings.DAMAGE, 4.5},
        {"blink", BasicAttackSettings.DAMAGE, 4.0},
        {"ra", BasicAttackSettings.DAMAGE, 4.5},
        {"crystal", BasicAttackSettings.DAMAGE, 4.2},
        {"sonic", BasicAttackSettings.DAMAGE, 3.8},
    };
}
