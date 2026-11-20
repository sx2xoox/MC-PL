package io.github.sx2xoox.mcpl.game.siege.balance;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * The role of every 공성전 pool ability (docs/siege-balance.md): what the balance expects it to be good at. The balance
 * report checks each ability's measured numbers against its role's band (an assassin bursts but is fragile, a tank
 * survives and controls but hits softly, a marksman out-ranges, a support helps allies).
 */
public final class SiegeRoles {

    /** A role and its Korean name. */
    public enum Role {
        TANK("탱커"), FIGHTER("전사"), ASSASSIN("암살"), MARKSMAN("원거리"), MAGE("마법"), SUPPORT("서포터");

        private final String label;

        Role(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private static final Map<String, Role> ROLES = new LinkedHashMap<>();

    static {
        put(Role.TANK, "guardian", "turtle", "bear", "earth");
        put(Role.FIGHTER, "blacksmith", "chef", "blade", "duelist", "lancer", "berserker", "fighter", "vampire",
                "reaper", "demon", "werewolf", "dokkaebi", "farmer", "wukong", "hydra", "monk", "mech", "minotaur",
                "titan", "odin", "dragon");
        put(Role.ASSASSIN, "ninja", "shadow", "phoenix", "blink", "speedster", "tengu", "gumiho");
        put(Role.MARKSMAN, "gunslinger", "archer", "sniper", "pirate", "angler", "batter", "tennis", "necromancer");
        put(Role.MAGE, "miner", "pyro", "frost", "thunder", "aero", "hydro", "magma", "light", "engineer", "bomber",
                "magnet", "venom", "ra", "crystal", "sonic", "kiwarrior", "medusa", "octopus");
        put(Role.SUPPORT, "healer", "bard", "alchemist", "chrono", "druid", "shaman", "magicalgirl", "police", "hacker",
                "spider", "hunter", "gravity");
    }

    private SiegeRoles() {
    }

    private static void put(Role role, String... ids) {
        for (String id : ids) {
            ROLES.put(id, role);
        }
    }

    /** The role of a pool ability, or null for an ability outside the pool. */
    public static @Nullable Role of(String abilityId) {
        return ROLES.get(abilityId);
    }

    /** Every ability with a role, in table order. */
    public static List<String> abilities() {
        return List.copyOf(ROLES.keySet());
    }
}
