package io.github.sx2xoox.mcpl.game.superpower.basic;

import static io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttack.Effect.DOT;
import static io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttack.Effect.HEAL_ALLIES;
import static io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttack.Effect.KNOCKBACK;
import static io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttack.Effect.LIFESTEAL;
import static io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttack.Effect.LUNGE;
import static io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttack.Effect.PULL;
import static io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttack.Effect.SLOW;
import static io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttack.Effect.WEAKEN;
import static io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttack.Shape.CHAIN;
import static io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttack.Shape.COMBO;
import static io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttack.Shape.CONE;
import static io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttack.Shape.LOB;
import static io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttack.Shape.NOVA;
import static io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttack.Shape.PROJECTILE;
import static io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttack.Shape.SHOT;
import static io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttack.Shape.STRIKE;
import static io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttack.Shape.SWING;
import static io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttack.Shape.THRUST;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttack.Effect;
import io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttack.Palette;
import io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttack.Shape;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.jspecify.annotations.Nullable;

/**
 * The default basic attack (평타) of every 공성전 pool ability, plus {@link #FALLBACK} for any other ability.
 *
 * <p>Balance (docs/siege-v2.md §8): damage is before armor, like a vanilla sword hit (iron sword: 6 every 0.625 s).
 * Melee attacks deal about 7–8 damage per second to one target, ranged ones about 4–5 (less per hit, safer), wide or
 * splashing ones less per target; side effects are small (slows ≤ 20 % for ≤ 1 s, no stuns). The host scales the
 * damage with the attacker's level and attack damage items ({@link BasicAttackSettings#bonusReference()}).
 */
public final class BasicAttacks {

    /** Any ability without its own basic attack: a plain sword swing. */
    public static final BasicAttack FALLBACK = attack("default", "베기", "칼을 앞으로 휘두릅니다.", SWING)
            .damage(4.5).cooldown(12).range(3.0).angle(70)
            .look(Particle.SWEEP_ATTACK, 0xE6E6E6, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.1f,
                    Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.2f, null)
            .build();

    private static final Map<String, BasicAttack> DEFAULTS = new LinkedHashMap<>();

    static {
        // ----------------------------------------------------------------- jobs and weapons
        put(attack("police", "진압봉 내려치기", "진압봉으로 내려쳐 맞은 적을 잠깐 굼뜨게 합니다.", SWING)
                .damage(4.5).cooldown(12).range(3.0).angle(70).effect(SLOW, 0.1, 0.5)
                .look(Particle.CRIT, 0x3B82F6, Sound.ITEM_SHIELD_BLOCK, 1.6f,
                        Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.3f, null));
        put(attack("miner", "곡괭이 찍기", "곡괭이로 묵직하게 찍습니다. 느리지만 한 방이 셉니다.", SWING)
                .damage(6).cooldown(16).range(3.0).angle(50)
                .look(Particle.CRIT, 0x8D8D8D, Sound.BLOCK_STONE_BREAK, 0.9f,
                        Sound.BLOCK_DEEPSLATE_BREAK, 1.2f, null));
        put(attack("blacksmith", "망치 내려치기", "달군 망치로 내려쳐 불똥을 튀기고 적을 밀어냅니다.", SWING)
                .damage(6.5).cooldown(18).range(3.0).angle(90).effect(KNOCKBACK, 0.4, 0)
                .look(Particle.LAVA, 0xF97316, Sound.BLOCK_ANVIL_PLACE, 1.7f,
                        Sound.BLOCK_ANVIL_LAND, 1.9f, null));
        put(attack("gunslinger", "권총 사격", "조준한 적에게 권총을 한 발 쏩니다.", SHOT)
                .damage(3.5).cooldown(14).range(22)
                .look(Particle.SMOKE, 0xFDE68A, Sound.ITEM_CROSSBOW_SHOOT, 1.8f,
                        Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.9f, null));
        put(attack("chef", "다지기 2연타", "식칼로 앞을 빠르게 두 번 썹니다.", COMBO)
                .damage(2.6).cooldown(14).range(3.0).angle(70).hits(2, 4)
                .look(Particle.CRIT, 0xF1F5F9, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.9f,
                        Sound.ITEM_AXE_STRIP, 1.8f, null));
        put(attack("ninja", "수리검 3연발", "수리검 세 개를 부채꼴로 던집니다. 가까울수록 많이 맞습니다.", PROJECTILE)
                .damage(1.6).cooldown(16).range(16).hits(3, 0).angle(20).speed(1.4).radius(0.45)
                .look(Particle.CRIT, 0xCBD5E1, Sound.ITEM_TRIDENT_THROW, 2.0f,
                        Sound.ITEM_TRIDENT_HIT, 1.9f, Material.NETHER_STAR));
        put(attack("blade", "일섬", "긴 검을 넓게 그어 앞의 적들을 벱니다.", SWING)
                .damage(5).cooldown(13).range(3.6).angle(100)
                .look(Particle.SWEEP_ATTACK, 0xE0F2FE, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.25f,
                        Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.3f, null));
        put(attack("duelist", "레이피어 찌르기", "한 걸음 내디디며 길게 찌릅니다.", THRUST)
                .damage(4.8).cooldown(12).range(4.0).radius(0.5).pierce(1).effect(LUNGE, 0.35, 0)
                .look(Particle.ENCHANTED_HIT, 0xFDE047, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.7f,
                        Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.6f, null));
        put(attack("archer", "속사 화살", "빠른 화살을 한 발 쏩니다.", PROJECTILE)
                .damage(3.5).cooldown(15).range(26).speed(2.2).radius(0.4)
                .look(Particle.CRIT, 0xA3E635, Sound.ENTITY_ARROW_SHOOT, 1.4f,
                        Sound.ENTITY_ARROW_HIT, 1.2f, Material.ARROW));
        put(attack("sniper", "정밀 사격", "멀리까지 닿는 한 발. 느리지만 정확합니다.", SHOT)
                .damage(5.5).cooldown(26).range(40)
                .look(Particle.END_ROD, 0xF87171, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 1.6f,
                        Sound.ENTITY_ARROW_HIT, 0.8f, null));
        put(attack("hunter", "사냥칼 투척", "사냥칼을 던져 맞은 적에게 출혈을 남깁니다.", PROJECTILE)
                .damage(3).cooldown(14).range(14).speed(1.6).radius(0.45).effect(DOT, 1, 2)
                .look(Particle.DAMAGE_INDICATOR, 0xB91C1C, Sound.ENTITY_SNOWBALL_THROW, 0.8f,
                        Sound.ITEM_TRIDENT_HIT, 1.4f, Material.FLINT));
        put(attack("lancer", "장창 찌르기", "긴 창으로 일직선을 꿰뚫습니다.", THRUST)
                .damage(5).cooldown(15).range(5.0).radius(0.55)
                .look(Particle.CRIT, 0x94A3B8, Sound.ITEM_TRIDENT_THROW, 1.3f,
                        Sound.ITEM_TRIDENT_HIT, 1.1f, null));
        put(attack("berserker", "광폭 베기", "도끼를 크게 휘둘러 주변 앞쪽을 모두 벱니다.", SWING)
                .damage(5.5).cooldown(18).range(3.4).angle(160)
                .look(Particle.SWEEP_ATTACK, 0xDC2626, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.7f,
                        Sound.ENTITY_IRON_GOLEM_ATTACK, 1.2f, null));
        put(attack("guardian", "방패 강타", "방패로 밀쳐 치며 적을 떼어냅니다.", SWING)
                .damage(4).cooldown(14).range(2.8).angle(80).effect(KNOCKBACK, 0.55, 0)
                .look(Particle.CRIT, 0xFACC15, Sound.ITEM_SHIELD_BLOCK, 0.9f,
                        Sound.BLOCK_HEAVY_CORE_HIT, 1.2f, null));
        put(attack("fighter", "원투 펀치", "주먹을 빠르게 두 번 내지릅니다.", COMBO)
                .damage(2.4).cooldown(12).range(2.8).angle(60).hits(2, 3)
                .look(Particle.CRIT, 0xFB923C, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.5f,
                        Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.2f, null));
        // ----------------------------------------------------------------- elements
        put(attack("pyro", "불씨 탄", "작은 불덩이를 쏘아 맞은 적을 잠깐 태웁니다.", PROJECTILE)
                .damage(3).cooldown(14).range(14).speed(1.2).radius(0.5).effect(DOT, 1, 2)
                .look(Particle.FLAME, 0xF97316, Sound.ITEM_FIRECHARGE_USE, 1.7f,
                        Sound.BLOCK_LAVA_POP, 1.4f, Material.FIRE_CHARGE));
        put(attack("frost", "서리 구슬", "짧게 날아가는 서리 구슬. 맞은 적은 아주 조금 느려집니다.", PROJECTILE)
                .damage(3.2).cooldown(15).range(12).speed(1.0).radius(0.5).effect(SLOW, 0.15, 1)
                .look(Particle.SNOWFLAKE, 0x7DD3FC, Sound.ENTITY_PLAYER_HURT_FREEZE, 1.7f,
                        Sound.BLOCK_GLASS_BREAK, 1.8f, Material.BLUE_ICE));
        put(attack("thunder", "연쇄 번개", "조준한 적을 감전시키고, 가까운 적 둘에게 튀어 갑니다.", CHAIN)
                .damage(3).cooldown(18).range(10).hits(3, 0).radius(4).falloff(0.7)
                .look(Particle.ELECTRIC_SPARK, 0x93C5FD, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 1.9f,
                        Sound.BLOCK_AMETHYST_BLOCK_HIT, 2.0f, null));
        put(attack("aero", "바람 칼날", "적을 꿰뚫는 바람 칼날. 맞은 적을 살짝 밀어냅니다.", PROJECTILE)
                .damage(3).cooldown(14).range(14).speed(1.5).radius(0.7).pierce(3).effect(KNOCKBACK, 0.3, 0)
                .look(Particle.SMALL_GUST, 0xE0F2FE, Sound.ENTITY_BREEZE_SHOOT, 1.5f,
                        Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1.6f, null));
        put(attack("earth", "땅 가시", "조준한 땅에서 잠시 뒤 돌가시가 솟습니다.", STRIKE)
                .damage(4.5).cooldown(18).range(12).hits(1, 6).radius(1.4)
                .look(Particle.CRIT, 0x92400E, Sound.BLOCK_ROOTED_DIRT_BREAK, 0.8f,
                        Sound.BLOCK_POINTED_DRIPSTONE_LAND, 0.7f, Material.POINTED_DRIPSTONE));
        put(attack("hydro", "물방울 탄", "적 둘까지 꿰뚫는 물방울을 쏩니다.", PROJECTILE)
                .damage(3.2).cooldown(14).range(16).speed(1.6).radius(0.5).pierce(2)
                .look(Particle.SPLASH, 0x38BDF8, Sound.ENTITY_FISHING_BOBBER_SPLASH, 1.5f,
                        Sound.ENTITY_PLAYER_SPLASH, 1.7f, Material.PRISMARINE_CRYSTALS));
        put(attack("magma", "용암 덩이", "용암 덩이를 던져 떨어진 자리를 태웁니다.", LOB)
                .damage(3.4).cooldown(18).range(14).speed(0.9).radius(1.6).effect(DOT, 1, 2)
                .look(Particle.LAVA, 0xEA580C, Sound.ENTITY_MAGMA_CUBE_JUMP, 1.4f,
                        Sound.BLOCK_LAVA_POP, 0.8f, Material.MAGMA_BLOCK));
        put(attack("light", "빛줄기", "조준한 적에게 곧게 뻗는 빛줄기를 쏩니다.", SHOT)
                .damage(3.6).cooldown(15).range(18)
                .look(Particle.END_ROD, 0xFEF08A, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.8f,
                        Sound.BLOCK_AMETHYST_BLOCK_HIT, 1.5f, null));
        put(attack("shadow", "그림자 단검", "그림자 단검으로 소리 없이 두 번 벱니다.", COMBO)
                .damage(2.5).cooldown(13).range(3.0).angle(60).hits(2, 3)
                .look(Particle.SQUID_INK, 0x4C1D95, Sound.ENTITY_PHANTOM_BITE, 1.7f,
                        Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.5f, null));
        // ----------------------------------------------------------------- dark and myth
        put(attack("vampire", "흡혈 할퀴기", "날카롭게 할퀴고 준 피해의 일부를 빨아들입니다.", SWING)
                .damage(4.5).cooldown(14).range(3.0).angle(80).effect(LIFESTEAL, 0.25, 0)
                .look(Particle.DAMAGE_INDICATOR, 0x7F1D1D, Sound.ENTITY_PHANTOM_BITE, 0.8f,
                        Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.9f, null));
        put(attack("reaper", "영혼 베기", "큰 낫으로 넓게 베며 맞은 적을 끌어당깁니다.", SWING)
                .damage(5).cooldown(17).range(3.8).angle(140).effect(PULL, 0.3, 0)
                .look(Particle.SOUL, 0x67E8F9, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.6f,
                        Sound.PARTICLE_SOUL_ESCAPE, 1.0f, null));
        put(attack("necromancer", "망령 구체", "느린 망령 구체. 맞은 적은 잠깐 약해집니다.", PROJECTILE)
                .damage(3.2).cooldown(15).range(14).speed(0.9).radius(0.55).effect(WEAKEN, 1, 2)
                .look(Particle.SCULK_SOUL, 0x14B8A6, Sound.ENTITY_VEX_CHARGE, 1.2f,
                        Sound.PARTICLE_SOUL_ESCAPE, 0.7f, Material.SKELETON_SKULL));
        put(attack("demon", "지옥불 손톱", "앞으로 지옥불을 휘둘러 부채꼴의 적을 태웁니다.", CONE)
                .damage(4.2).cooldown(15).range(3.5).angle(70).effect(DOT, 1, 2)
                .look(Particle.FLAME, 0x991B1B, Sound.ENTITY_BLAZE_SHOOT, 0.7f,
                        Sound.BLOCK_LAVA_POP, 1.0f, null));
        put(attack("phoenix", "불사조 깃털", "불타는 깃털 두 개를 날려 적을 태웁니다.", PROJECTILE)
                .damage(1.8).cooldown(14).range(14).hits(2, 0).angle(10).speed(1.4).radius(0.5)
                .effect(DOT, 0.6, 2)
                .look(Particle.FLAME, 0xFB923C, Sound.ENTITY_BLAZE_SHOOT, 1.6f,
                        Sound.ITEM_FIRECHARGE_USE, 1.9f, Material.FEATHER));
        put(attack("dragon", "용의 숨결", "짧은 숨결을 두 번 내뿜어 앞의 적을 태웁니다.", CONE)
                .damage(2).cooldown(18).range(4.5).angle(50).hits(2, 4)
                .look(Particle.REVERSE_PORTAL, 0xA855F7, Sound.ENTITY_ENDER_DRAGON_SHOOT, 1.4f,
                        Sound.ENTITY_GHAST_SHOOT, 1.6f, null));
        put(attack("werewolf", "할퀴기 3연타", "발톱으로 세 번 연달아 할큅니다.", COMBO)
                .damage(1.9).cooldown(15).range(2.8).angle(80).hits(3, 3)
                .look(Particle.CRIT, 0x78716C, Sound.ENTITY_FOX_BITE, 0.8f,
                        Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.4f, null));
        put(attack("dokkaebi", "도깨비 방망이", "방망이로 후려쳐 적을 튕겨 냅니다.", SWING)
                .damage(5.5).cooldown(16).range(3.0).angle(90).effect(KNOCKBACK, 0.4, 0)
                .look(Particle.SOUL_FIRE_FLAME, 0x2563EB, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.8f,
                        Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 1.8f, null));
        // ----------------------------------------------------------------- support
        put(attack("healer", "치유의 빛", "빛 구슬을 쏩니다. 지나가며 닿은 아군은 체력을 조금 회복합니다.", PROJECTILE)
                .damage(2.6).cooldown(15).range(16).speed(1.6).radius(0.6).effect(HEAL_ALLIES, 1.5, 0)
                .look(Particle.HAPPY_VILLAGER, 0xBBF7D0, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.4f,
                        Sound.BLOCK_AMETHYST_BLOCK_HIT, 1.8f, null));
        put(attack("bard", "화음 파동", "주변으로 음파를 퍼뜨려 적을 치고 아군을 조금 회복시킵니다.", NOVA)
                .damage(2.8).cooldown(18).range(3.5).effect(HEAL_ALLIES, 1, 0)
                .look(Particle.NOTE, 0xF0ABFC, Sound.BLOCK_NOTE_BLOCK_HARP, 1.2f,
                        Sound.BLOCK_NOTE_BLOCK_BELL, 1.5f, null));
        put(attack("alchemist", "산성 플라스크", "플라스크를 던져 터진 자리의 적을 중독시킵니다.", LOB)
                .damage(3).cooldown(18).range(14).speed(0.85).radius(1.8).effect(DOT, 1.2, 2)
                .look(Particle.ITEM_SLIME, 0x84CC16, Sound.ENTITY_SPLASH_POTION_THROW, 1.2f,
                        Sound.ENTITY_SPLASH_POTION_BREAK, 1.3f, Material.SPLASH_POTION));
        // ----------------------------------------------------------------- tech, space-time, venom, sea and field
        put(attack("engineer", "리벳 2연사", "리벳건으로 두 발을 빠르게 박습니다.", SHOT)
                .damage(1.9).cooldown(15).range(16).hits(2, 3)
                .look(Particle.WAX_OFF, 0xF59E0B, Sound.ITEM_CROSSBOW_HIT, 1.6f,
                        Sound.BLOCK_CHAIN_HIT, 1.4f, null));
        put(attack("hacker", "데이터 패킷", "데이터 큐브를 쏴 맞은 적의 움직임을 잠깐 버벅이게 합니다.", PROJECTILE)
                .damage(3).cooldown(14).range(18).speed(1.8).radius(0.45).effect(SLOW, 0.1, 0.8)
                .look(Particle.WAX_ON, 0x22C55E, Sound.BLOCK_NOTE_BLOCK_BIT, 1.8f,
                        Sound.BLOCK_NOTE_BLOCK_BIT, 0.8f, Material.LIME_CONCRETE));
        put(attack("mech", "기관포 점사", "어깨 기관포로 세 발을 짧게 끊어 쏩니다.", SHOT)
                .damage(1.3).cooldown(16).range(18).hits(3, 2)
                .look(Particle.SMOKE, 0x9CA3AF, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 2.0f,
                        Sound.BLOCK_CHAIN_HIT, 1.7f, null));
        put(attack("bomber", "꼬마 폭탄", "작은 폭탄을 던져 떨어진 자리에서 터뜨립니다.", LOB)
                .damage(3.6).cooldown(20).range(14).speed(0.85).radius(2)
                .look(Particle.SMOKE, 0xEF4444, Sound.ENTITY_TNT_PRIMED, 1.9f,
                        Sound.ENTITY_GENERIC_EXPLODE, 1.7f, Material.TNT));
        put(attack("gravity", "중력 구체", "무거운 구체를 쏴 맞은 적을 잠깐 무겁게 합니다.", PROJECTILE)
                .damage(3).cooldown(15).range(12).speed(0.8).radius(0.6).effect(SLOW, 0.12, 1)
                .look(Particle.REVERSE_PORTAL, 0x6D28D9, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.6f,
                        Sound.ENTITY_SHULKER_BULLET_HIT, 0.8f, Material.CRYING_OBSIDIAN));
        put(attack("magnet", "자력 끌개", "자력선을 쏴 맞은 적을 내 쪽으로 끌어옵니다.", SHOT)
                .damage(3.2).cooldown(16).range(12).effect(PULL, 0.35, 0)
                .look(Particle.CRIT, 0xEF4444, Sound.ITEM_LODESTONE_COMPASS_LOCK, 1.6f,
                        Sound.BLOCK_CHAIN_HIT, 0.8f, null));
        put(attack("chrono", "시곗바늘 투척", "시곗바늘 두 개를 던집니다. 맞은 적의 시간이 잠깐 느려집니다.", PROJECTILE)
                .damage(1.7).cooldown(14).range(14).hits(2, 0).angle(12).speed(1.3).radius(0.45)
                .effect(SLOW, 0.12, 0.8)
                .look(Particle.WAX_OFF, 0xFDE047, Sound.BLOCK_COMPARATOR_CLICK, 1.6f,
                        Sound.BLOCK_NOTE_BLOCK_BELL, 1.9f, Material.CLOCK));
        put(attack("blink", "공간 절단", "조준한 곳의 공간을 그 자리에서 베어 냅니다.", STRIKE)
                .damage(4).cooldown(16).range(7).hits(1, 0).radius(1.2)
                .look(Particle.PORTAL, 0xC084FC, Sound.ENTITY_ENDERMAN_TELEPORT, 1.8f,
                        Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.6f, null));
        put(attack("speedster", "음속 4연타", "앞으로 튀어 나가며 눈에 안 보이게 네 번 칩니다.", COMBO)
                .damage(1.3).cooldown(14).range(2.8).angle(70).hits(4, 2).effect(LUNGE, 0.25, 0)
                .look(Particle.ELECTRIC_SPARK, 0xFACC15, Sound.ENTITY_PLAYER_ATTACK_WEAK, 1.9f,
                        Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.8f, null));
        put(attack("venom", "독침", "독침을 쏴 맞은 적에게 독을 남깁니다.", PROJECTILE)
                .damage(2.2).cooldown(13).range(16).speed(1.8).radius(0.4).effect(DOT, 1.5, 3)
                .look(Particle.SPIT, 0x65A30D, Sound.ENTITY_LLAMA_SPIT, 1.3f,
                        Sound.ENTITY_SLIME_SQUISH_SMALL, 1.2f, null));
        put(attack("spider", "거미줄 탄", "끈적한 거미줄을 쏴 맞은 적을 느리게 합니다.", PROJECTILE)
                .damage(2.8).cooldown(15).range(12).speed(1.2).radius(0.5).effect(SLOW, 0.2, 1)
                .look(Particle.ITEM_COBWEB, 0xE5E7EB, Sound.ENTITY_SPIDER_AMBIENT, 1.6f,
                        Sound.BLOCK_SLIME_BLOCK_HIT, 1.2f, Material.COBWEB));
        put(attack("pirate", "플린트락 한 발", "구식 권총으로 묵직한 한 발. 맞은 적이 살짝 밀립니다.", SHOT)
                .damage(4.5).cooldown(20).range(18).effect(KNOCKBACK, 0.25, 0)
                .look(Particle.LARGE_SMOKE, 0x78350F, Sound.ENTITY_GENERIC_EXPLODE, 1.9f,
                        Sound.ENTITY_ARROW_HIT, 0.9f, null));
        put(attack("angler", "낚싯바늘 채기", "낚싯줄을 던져 걸린 적을 끌어옵니다.", THRUST)
                .damage(3.4).cooldown(16).range(6.0).radius(0.5).pierce(1).effect(PULL, 0.45, 0)
                .look(Particle.FISHING, 0x0EA5E9, Sound.ENTITY_FISHING_BOBBER_THROW, 1.3f,
                        Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1.2f, null));
        put(attack("farmer", "낫 휘두르기", "낫으로 넓게 베어 앞의 적들을 칩니다.", SWING)
                .damage(4.5).cooldown(15).range(3.2).angle(120)
                .look(Particle.COMPOSTER, 0x65A30D, Sound.ITEM_HOE_TILL, 1.2f,
                        Sound.BLOCK_GRASS_BREAK, 1.3f, null));
        put(attack("batter", "풀스윙", "배트를 풀스윙해 맞은 적을 멀리 날립니다.", SWING)
                .damage(5.5).cooldown(18).range(3.2).angle(100).effect(KNOCKBACK, 0.7, 0)
                .look(Particle.CRIT, 0xF5F5F4, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.9f,
                        Sound.BLOCK_BAMBOO_WOOD_HIT, 1.4f, null));
    }

    private BasicAttacks() {
    }

    /** Every built-in basic attack by ability id, in the 공성전 pool order. */
    public static Map<String, BasicAttack> defaults() {
        return Collections.unmodifiableMap(DEFAULTS);
    }

    /** The built-in basic attack of {@code abilityId}, or null. */
    public static @Nullable BasicAttack builtin(String abilityId) {
        return DEFAULTS.get(abilityId);
    }

    private static void put(Builder builder) {
        BasicAttack attack = builder.build();
        DEFAULTS.put(attack.id(), attack);
    }

    private static Builder attack(String id, String name, String description, Shape shape) {
        return new Builder(id, name, description, shape);
    }

    /** Builder for the table above (every number has a sensible default for its shape). */
    private static final class Builder {
        private final String id;
        private final String name;
        private final String description;
        private final Shape shape;
        private double damage = 4;
        private int cooldown = 12;
        private double range = 3;
        private int hits = 1;
        private int interval;
        private double radius = 0.5;
        private double angle;
        private double speed = 1;
        private int pierce;
        private double falloff = 1;
        private Effect effect = Effect.NONE;
        private double effectPower;
        private double effectSeconds;
        private @Nullable Palette palette;

        Builder(String id, String name, String description, Shape shape) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.shape = shape;
            // A flying projectile stops at the first enemy unless told otherwise.
            this.pierce = shape == PROJECTILE ? 1 : 0;
        }

        Builder damage(double value) {
            damage = value;
            return this;
        }

        Builder cooldown(int ticks) {
            cooldown = ticks;
            return this;
        }

        Builder range(double value) {
            range = value;
            return this;
        }

        Builder hits(int count, int ticksApart) {
            hits = count;
            interval = ticksApart;
            return this;
        }

        Builder radius(double value) {
            radius = value;
            return this;
        }

        Builder angle(double degrees) {
            angle = degrees;
            return this;
        }

        Builder speed(double value) {
            speed = value;
            return this;
        }

        Builder pierce(int value) {
            pierce = value;
            return this;
        }

        Builder falloff(double value) {
            falloff = value;
            return this;
        }

        Builder effect(Effect value, double power, double seconds) {
            effect = value;
            effectPower = power;
            effectSeconds = seconds;
            return this;
        }

        Builder look(Particle particle, int rgb, Sound sound, float pitch, Sound impact, float impactPitch,
                     @Nullable Material item) {
            palette = Palette.of(particle, rgb, sound, pitch, impact, impactPitch, item);
            return this;
        }

        BasicAttack build() {
            if (palette == null) {
                throw new IllegalStateException("No palette for " + id);
            }
            return new BasicAttack(id, name, description, shape, damage, cooldown, range, hits, interval, radius,
                    angle, speed, pierce, falloff, effect, effectPower, effectSeconds, palette);
        }
    }
}
