package io.github.sx2xoox.mcpl.game.siege.balance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityParams;

/**
 * A damage model of every 공성전 pool ability, written from its description and read from its params, so the report
 * follows every change to {@code abilities.yml} defaults: the hits one cast of the active and of the ultimate deal to
 * a single enemy that stays in it (perfect use), when they land, and the crowd control and reach.
 *
 * <p>Conventions: burning (vanilla fire ticks), fall damage and melee (passives that buff swords) are not ability
 * damage and not modelled; a lingering zone the target can walk out of counts {@link #ZONE_SECONDS}; hits that only
 * land on other enemies (splash) are left out.
 */
final class SiegeBalanceModels {

    /** How long a target stays in a zone it can leave (pools, clouds, nests). */
    static final double ZONE_SECONDS = 2.0;

    /** One hit on the target: when (seconds after the cast) and how much; {@code ult} = part of the ultimate. */
    record Hit(double t, double damage, boolean ult) {
    }

    /**
     * @param active  hits of one active cast (passive hits it triggers included)
     * @param ult     hits of one ultimate (may hold skill hits it enables, {@code ult = false})
     * @param hardCc  seconds of stun/root/freeze/prison/silence one active + one ultimate put on the target
     * @param softCc  seconds of slows and blindness
     * @param range   reach of the active in blocks (0 = self/melee)
     * @param utility a support or mobility kit whose damage is not its job (left out of the median)
     * @param note    what the model assumes
     */
    record Profile(List<Hit> active, List<Hit> ult, double hardCc, double softCc, double range, boolean utility,
                   String note) {
    }

    private static final class B {
        final List<Hit> active = new ArrayList<>();
        final List<Hit> ult = new ArrayList<>();
        double hard;
        double soft;
        double range;
        boolean utility;
        String note = "";

        B a(double t, double damage) {
            active.add(new Hit(t, damage, false));
            return this;
        }

        B as(double start, double period, double count, double damage) {
            for (int i = 0; i < (int) Math.floor(count + 1.0E-9); i++) {
                a(start + i * period, damage);
            }
            return this;
        }

        B u(double t, double damage) {
            ult.add(new Hit(t, damage, true));
            return this;
        }

        B us(double start, double period, double count, double damage) {
            for (int i = 0; i < (int) Math.floor(count + 1.0E-9); i++) {
                u(start + i * period, damage);
            }
            return this;
        }

        /** Skill hits the ultimate enables (mech rocket punches, sniper shots during the mark). */
        B uSkill(double start, double period, double count, double damage) {
            for (int i = 0; i < (int) Math.floor(count + 1.0E-9); i++) {
                ult.add(new Hit(start + i * period, damage, false));
            }
            return this;
        }

        B hard(double seconds) {
            hard += seconds;
            return this;
        }

        B soft(double seconds) {
            soft += seconds;
            return this;
        }

        B range(double blocks) {
            range = blocks;
            return this;
        }

        B utility() {
            utility = true;
            return this;
        }

        B note(String text) {
            note = text;
            return this;
        }

        Profile build() {
            return new Profile(List.copyOf(active), List.copyOf(ult), hard, soft, range, utility, note);
        }
    }

    private static final Map<String, Function<AbilityParams, Profile>> MODELS = new LinkedHashMap<>();

    private static void m(String id, Function<AbilityParams, B> model) {
        MODELS.put(id, p -> model.apply(p).build());
    }

    private static double n(AbilityParams p, String key) {
        return p.num(key);
    }

    private static double zone(double duration) {
        return Math.min(duration, ZONE_SECONDS);
    }

    static {
        m("police", p -> new B()
                .a(0, n(p, "taser_damage")).hard(n(p, "taser_stun"))
                .u(n(p, "arrest_windup"), n(p, "arrest_damage"))
                .us(n(p, "arrest_windup") + n(p, "jail_shock_period"), n(p, "jail_shock_period"),
                        n(p, "jail_duration") / n(p, "jail_shock_period"), n(p, "jail_shock"))
                .hard(n(p, "jail_duration")).range(n(p, "taser_range"))
                .note("체포: 철창 감전 전부"));
        m("miner", p -> new B()
                .as(n(p, "rock_delay"), 0.25, Math.min(n(p, "rock_max_hits"), n(p, "rock_count")), n(p, "rock_damage"))
                .soft(n(p, "rock_slow_duration"))
                .u(n(p, "cart_delay"), n(p, "cart_damage")).u(n(p, "cart_delay") + 1.5, n(p, "crash_damage"))
                .soft(n(p, "crash_slow_duration")).range(n(p, "rock_range")));
        m("blacksmith", p -> new B()
                .a(n(p, "anvil_delay"), n(p, "anvil_damage")).hard(n(p, "anvil_stun"))
                .us(0.5, n(p, "hammer_period"), n(p, "hammer_blows"), n(p, "hammer_damage"))
                .range(n(p, "anvil_range")).note("강타(근접) 제외"));
        m("gunslinger", p -> new B()
                .as(0, n(p, "shot_interval"), n(p, "rounds"), n(p, "shot_damage") * (1 + n(p, "headshot_bonus") / 100))
                .u(n(p, "noon_draw"), n(p, "noon_max")).range(n(p, "shot_range"))
                .note("액티브 = 탄창 전부 헤드샷"));
        m("chef", p -> new B()
                .a(0, n(p, "pan_damage")).hard(n(p, "pan_stun"))
                .us(n(p, "ult_throw_interval"), n(p, "ult_throw_interval"),
                        n(p, "ult_duration") / n(p, "ult_throw_interval"), n(p, "ult_throw_damage"))
                .range(n(p, "pan_range")));
        m("ninja", p -> {
            B b = new B().a(0, n(p, "shuriken_damage"));
            for (int i = 1; i < p.integer("shuriken_count"); i++) {
                b.a(0, n(p, "shuriken_damage") * (1 - n(p, "shuriken_overlap") / 100));
            }
            return b.as(n(p, "bleed_interval"), n(p, "bleed_interval"),
                            n(p, "bleed_duration") / n(p, "bleed_interval"), n(p, "bleed_damage"))
                    .range(n(p, "shuriken_range")).note("수리검 전부 + 출혈, 궁극기는 근접 치명타");
        });
        m("blade", p -> new B()
                .a(0.2, n(p, "dash_damage"))
                .us(0.5, n(p, "ult_interval"), n(p, "ult_swords"), n(p, "ult_damage"))
                .range(n(p, "dash_distance")).note("천검: 검 전부 한 명에게, 검기(패시브) 제외"));
        m("duelist", p -> new B().hard(0).range(n(p, "challenge_range")).utility()
                .note("피해 증감만 (결투장 " + n(p, "ult_duration") + "초 가둠)"));
        m("archer", p -> {
            B b = new B().a(0, n(p, "volley_damage"));
            for (int i = 1; i < p.integer("volley_arrows"); i++) {
                b.a(0, n(p, "volley_damage") * (1 - n(p, "volley_overlap") / 100));
            }
            return b.us(0.2, n(p, "rain_hit_interval"), n(p, "rain_duration") / n(p, "rain_hit_interval"),
                            n(p, "rain_damage"))
                    .range(n(p, "rain_range")).note("산탄 전부 근거리, 화살비 안에 계속");
        });
        m("sniper", p -> {
            double shot = n(p, "shot_damage") * (1 + n(p, "headshot_bonus") / 100) * (1 + n(p, "bonus_max") / 100);
            double markedGap = n(p, "cooldown") * (1 - n(p, "mark_cooldown_cut") / 100) + n(p, "mark_aim_time");
            return new B().a(n(p, "aim_time"), shot)
                    .uSkill(n(p, "mark_aim_time"), markedGap, Math.floor(n(p, "mark_duration") / markedGap) + 1,
                            shot * (1 + n(p, "mark_bonus") / 100))
                    .range(n(p, "shot_range")).note("원거리 헤드샷 최대치, 표적 중 연사");
        });
        m("hunter", p -> new B()
                .a(n(p, "trap_arm"), n(p, "trap_damage")).hard(n(p, "trap_root"))
                .soft(n(p, "hunt_duration")).range(n(p, "trap_range")).note("사냥 개시는 이동·피해 증가"));
        m("lancer", p -> new B()
                .a(0.3, n(p, "throw_damage")).a(0.9, n(p, "return_damage"))
                .u(0.5, n(p, "ult_damage")).u(1.5, n(p, "ult_fling_damage")).range(n(p, "throw_range")));
        m("berserker", p -> new B()
                .a(0, n(p, "charge_damage") * (1 + n(p, "rage_max") / 100))
                .range(n(p, "charge_distance")).note("돌진은 분노 최대, 궁극기는 근접 강화"));
        m("guardian", p -> new B()
                .a(0, n(p, "bash_damage")).soft(n(p, "bash_slow")).range(n(p, "bash_distance")).utility()
                .note("탱커: 궁극기는 보호막"));
        m("fighter", p -> new B()
                .a(0.5, n(p, "suplex_damage")).hard(n(p, "suplex_stun"))
                .u(1.2, n(p, "ult_damage")).hard(n(p, "ult_stun")).range(n(p, "grab_range"))
                .note("반격(근접) 제외"));
        m("pyro", p -> new B()
                .as(0, 0.2, n(p, "shots"), n(p, "fireball_damage"))
                .us(0, n(p, "ult_interval"), n(p, "ult_duration") / n(p, "ult_interval"), n(p, "ult_damage"))
                .range(n(p, "fireball_range")).note("불태우기 제외, 불바다는 따라다님"));
        m("frost", p -> new B()
                .a(0, n(p, "spear_damage")).hard(n(p, "freeze_duration"))
                .u(n(p, "ult_duration"), n(p, "ult_damage")).hard(n(p, "ult_duration"))
                .range(n(p, "spear_range")));
        m("thunder", p -> new B()
                .a(n(p, "strike_delay"), n(p, "damage"))
                .us(0, n(p, "ult_interval"), n(p, "ult_strikes"), n(p, "ult_damage")).range(n(p, "range")));
        m("aero", p -> new B()
                .a(0, n(p, "damage")).u(2, n(p, "slam_damage")).range(n(p, "distance")));
        m("earth", p -> new B()
                .a(0, n(p, "quake_damage")).hard(n(p, "ult_duration")).range(n(p, "quake_range"))
                .note("바위 감옥은 피해 없음"));
        m("hydro", p -> new B()
                .as(0, n(p, "beam_interval"), n(p, "beam_duration") / n(p, "beam_interval"), n(p, "beam_damage"))
                .u(0.5, n(p, "wave_damage")).u(1.5, n(p, "crash_damage")).range(n(p, "beam_range")));
        m("magma", p -> {
            double chunks = n(p, "rain_duration") / n(p, "rain_interval") * n(p, "rain_aimed") / 100;
            return new B().a(0.8, n(p, "bomb_damage"))
                    .as(0.8 + n(p, "pool_interval"), n(p, "pool_interval"),
                            zone(n(p, "pool_duration")) / n(p, "pool_interval"), n(p, "pool_damage"))
                    .u(n(p, "erupt_delay"), n(p, "erupt_damage"))
                    .us(n(p, "erupt_delay") + n(p, "rain_warning"), n(p, "rain_duration") / Math.max(1, chunks),
                            chunks, n(p, "chunk_damage"))
                    .range(n(p, "bomb_range")).note("조준 용암 덩어리만");
        });
        m("light", p -> new B()
                .a(0, n(p, "flash_damage")).soft(n(p, "flash_blind"))
                .u(n(p, "ult_delay"), n(p, "ult_damage")).range(n(p, "flash_range")));
        m("shadow", p -> new B()
                .us(0, 0.4, n(p, "ult_strikes"), n(p, "ult_damage")).range(0)
                .note("은신 암습(근접) 제외, 난무는 한 명에게 전부"));
        m("vampire", p -> new B()
                .as(n(p, "drain_interval"), n(p, "drain_interval"), n(p, "drain_duration") / n(p, "drain_interval"),
                        n(p, "drain_damage"))
                .range(n(p, "drain_range")).note("궁극기는 흡혈 강화"));
        m("reaper", p -> new B()
                .a(0, n(p, "spin_damage"))
                .u(n(p, "mark_duration"), Math.min(n(p, "mark_cap"), n(p, "mark_base") + n(p, "mark_ratio") / 100 * 8))
                .range(n(p, "spin_radius")).note("표식: 그동안 8 피해를 받았다고 가정"));
        m("necromancer", p -> {
            double swing = n(p, "legion_count") * (n(p, "zombie_damage") + n(p, "skeleton_damage")) / 2 * 0.5;
            return new B().as(0.5, 0.3, n(p, "skull_count"), n(p, "skull_damage"))
                    .u(0.5, n(p, "rise_damage")).hard(n(p, "rise_hold"))
                    .us(1.5, 1, n(p, "legion_duration"), swing)
                    .range(n(p, "skull_range")).note("망자 절반이 초당 한 번씩 때림");
        });
        m("demon", p -> new B()
                .a(0, n(p, "hellfire_damage"))
                .us(n(p, "pillar_delay"), n(p, "pillar_interval"), n(p, "gate_duration") / n(p, "pillar_interval"),
                        n(p, "pillar_damage"))
                .range(n(p, "hellfire_range")).note("계약(저체력 강화) 제외"));
        m("phoenix", p -> new B()
                .u(0.3, n(p, "dash_damage")).range(0).note("액티브는 비행, 환생 폭발 제외"));
        m("dragon", p -> new B()
                .as(0, n(p, "breath_interval"), n(p, "breath_duration") / n(p, "breath_interval"),
                        n(p, "breath_damage"))
                .us(1, n(p, "bomb_interval"), n(p, "flight_duration") / n(p, "bomb_interval"), n(p, "bomb_damage"))
                .range(n(p, "breath_range")).note("폭탄 전부 한 명에게"));
        m("werewolf", p -> new B()
                .a(0.3, n(p, "claw_damage"))
                .as(1.3, n(p, "bleed_interval"), n(p, "bleed_duration") / n(p, "bleed_interval"), n(p, "bleed_damage"))
                .soft(n(p, "howl_duration")).range(n(p, "leap_distance")).note("야수화는 근접 강화"));
        m("dokkaebi", p -> new B()
                .a(0, n(p, "club_damage"))
                .as(0.3, 0.2, n(p, "wisp_count"), n(p, "wisp_damage"))
                .a(0.8, n(p, "bump_pop_damage")).hard(n(p, "bump_stun"))
                .us(0.5, 1.5, 3, n(p, "prank_damage"))
                .range(n(p, "club_reach")).note("방망이 + 도깨비불 → 혹 터짐, 감투 장난 3번"));
        m("healer", p -> new B().range(n(p, "light_radius")).utility().note("피해 없음"));
        m("bard", p -> new B()
                .a(0, n(p, "discord_damage")).soft(n(p, "discord_duration"))
                .us(0, n(p, "chorus_interval"), n(p, "chorus_duration") / n(p, "chorus_interval"),
                        n(p, "chorus_damage"))
                .range(n(p, "discord_radius")).utility());
        m("alchemist", p -> new B()
                .a(0.5, n(p, "splash_damage")).soft(n(p, "bane_duration"))
                .range(n(p, "throw_range")).utility().note("궁극기는 회복"));
        m("engineer", p -> new B()
                .as(0.5, n(p, "turret_interval"), n(p, "turret_duration") / n(p, "turret_interval"),
                        n(p, "turret_damage"))
                .us(n(p, "laser_charge"), n(p, "laser_interval"),
                        (n(p, "laser_duration") - n(p, "laser_charge")) / n(p, "laser_interval"), n(p, "laser_damage"))
                .range(n(p, "deploy_range") + n(p, "turret_range")));
        m("hacker", p -> new B()
                .a(0, n(p, "glitch_damage")).hard(n(p, "exploit_silence"))
                .u(n(p, "ult_upload"), n(p, "ult_damage")).hard(n(p, "ult_silence"))
                .range(n(p, "glitch_range")).note("침묵은 강한 통제로 셈"));
        m("mech", p -> new B()
                .as(0, n(p, "missile_interval"), n(p, "missile_count"), n(p, "missile_damage"))
                .uSkill(0, n(p, "punch_cooldown"), Math.floor(n(p, "ult_duration") / n(p, "punch_cooldown")),
                        n(p, "punch_damage"))
                .range(n(p, "missile_range")).note("변신 중 로켓 펀치(액티브)"));
        m("bomber", p -> new B()
                .a(n(p, "grenade_fuse"), n(p, "grenade_damage"))
                .us(0.5, n(p, "carpet_duration") / n(p, "carpet_bombs"), n(p, "carpet_max_hits"),
                        n(p, "carpet_damage"))
                .range(n(p, "carpet_range")));
        m("gravity", p -> new B()
                .soft(n(p, "pull_slow"))
                .u(n(p, "ult_duration"), n(p, "blast_damage")).hard(n(p, "ult_duration"))
                .range(n(p, "pull_range")).note("블랙홀에 빨려 든 시간을 강한 통제로 셈"));
        m("magnet", p -> new B()
                .a(0, n(p, "polarity_damage"))
                .u(n(p, "storm_pull"), n(p, "storm_damage") + n(p, "storm_crash_bonus") * n(p, "storm_crash_max"))
                .soft(n(p, "storm_slow_duration")).range(n(p, "cone_range")).note("충전·철 갑옷 보너스 제외"));
        m("chrono", p -> new B().hard(n(p, "ult_duration")).range(0).utility().note("시간 정지 (피해 없음)"));
        m("blink", p -> new B().range(n(p, "distance")).utility().note("궁극기는 근접 2배"));
        m("speedster", p -> new B()
                .as(0, n(p, "dash_gap"), n(p, "dash_charges"),
                        n(p, "dash_damage") * (1 + n(p, "momentum_dash_bonus") / 100))
                .us(0.3, 0.5, n(p, "ult_max_hits"), n(p, "ult_damage"))
                .range(n(p, "dash_distance") * n(p, "dash_charges")).note("최고 속도 질주 전부"));
        m("venom", p -> new B()
                .as(0.5, n(p, "cloud_interval"), zone(n(p, "cloud_duration")) / n(p, "cloud_interval"),
                        n(p, "cloud_damage"))
                .us(1, n(p, "ult_interval"), n(p, "ult_duration") / n(p, "ult_interval"), n(p, "ult_damage"))
                .soft(n(p, "ult_duration")).range(n(p, "cloud_range")).note("독 효과(바닐라) 제외"));
        m("spider", p -> new B()
                .a(0, n(p, "web_damage")).hard(n(p, "root_duration"))
                .us(1, n(p, "nest_interval"), n(p, "nest_duration") / n(p, "nest_interval"), n(p, "nest_damage"))
                .soft(zone(n(p, "nest_duration"))).range(n(p, "web_range")));
        m("pirate", p -> new B()
                .a(0.3, n(p, "ball_damage"))
                .us(n(p, "ult_interval"), n(p, "ult_interval"), n(p, "ult_max_hits"), n(p, "ult_damage"))
                .range(n(p, "ball_range")));
        m("angler", p -> new B()
                .a(0.3, n(p, "reel_damage")).a(1.0, n(p, "slam_damage")).soft(n(p, "reel_slow"))
                .u(n(p, "ult_warning"), n(p, "ult_burst_damage")).u(n(p, "ult_warning") + 1, n(p, "ult_slam_damage"))
                .soft(n(p, "ult_slow")).range(n(p, "cast_range")));
        m("farmer", p -> new B()
                .a(0, n(p, "sweep_damage") + n(p, "sweet_bonus"))
                .as(n(p, "bleed_interval"), n(p, "bleed_interval"), n(p, "bleed_duration") / n(p, "bleed_interval"),
                        n(p, "bleed_damage"))
                .us(n(p, "pumpkin_interval"), n(p, "pumpkin_interval"),
                        n(p, "ult_duration") / n(p, "pumpkin_interval"), n(p, "pumpkin_damage"))
                .hard(n(p, "ult_root")).soft(n(p, "ult_duration")).range(n(p, "sweep_radius"))
                .note("날 끝 + 출혈, 호박 전부"));
        m("batter", p -> new B()
                .a(0.2, n(p, "fastball_damage") + n(p, "head_bonus")).hard(n(p, "head_stun"))
                .u(n(p, "ult_windup"), n(p, "ult_damage"))
                .range(n(p, "fastball_range")).note("헤드샷, 홈런 낙하 피해(바닐라) 제외"));
    }

    private SiegeBalanceModels() {
    }

    static Map<String, Function<AbilityParams, Profile>> all() {
        return MODELS;
    }
}
