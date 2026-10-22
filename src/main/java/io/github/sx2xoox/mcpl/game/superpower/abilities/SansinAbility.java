package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Motion;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 산신령 — the mountain spirit of "the golden axe and the silver axe". It rewards honesty: the blows it takes from
 * enemies add up, and once enough have landed the spirit promises gold, so the next axe it throws is sure to come up
 * gold (no random shield: the reward is a sure verdict, unlike the beekeeper's honey). The active throws a spinning
 * axe; whoever it hits is asked "이 도끼가 네 도끼냐?" and judged by gold (a heavy blow), silver (a slow) or plain iron
 * (a shove), the odds of gold rising the farther and more precisely the axe flew. The ultimate brings the spirit down
 * on its cloud with its tiger, which bounds out where the sansin looks and pins every enemy in its path.
 *
 * <p>The tiger needs no passive state, so the Gambler can borrow the ultimate (the golden promise it also brings is
 * only kept by a real sansin).
 *
 * <p>Displays: the spirit and its tiger have their own share of the budget, so the halo axe, the thrown axe and the
 * judgement can never crowd them out; within the rest the judgement comes first, then the halo axe (which falls back
 * to golden sparkles until there is room again), then the sign.
 *
 * <p>Look: mountain mist white, gold and pine green. A promise is a shaft of golden light falling onto the sansin
 * with a "정직한 자로구나!" sign, and while the promise of gold stands, a little golden axe hovers over its shoulder. The
 * thrown axe tumbles end over end along its flight trailing mist and gold dust; on a hit it splashes like the pond of
 * the tale, three axes (gold, silver, iron) rise over the victim under the spirit's question and a highlight runs over
 * them like a roulette until the verdict axe chops down. The ultimate rolls in mountain mist: the white-bearded spirit
 * on its cloud, staff in hand, a golden lane on the ground, and a striped tiger that grows out of the mist, crouches,
 * roars and bounds off with swinging legs and a wagging tail, its claws raking whoever it pins, until it leaps and
 * dissolves into mist again.
 */
public final class SansinAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("sansin", "산신령", Material.GOLDEN_AXE)
            .color(TextColor.color(0xFFC940))
            .difficulty(2)
            .passive("정직",
                    "적에게 받은 피해가 {honest_need}만큼 쌓이면 산신령이 정직함을 알아보고 금도끼를 약속합니다. 다음 "
                            + "금도끼 은도끼는 반드시 금도끼가 되며, 약속이 지켜지기 전에는 피해가 더 쌓이지 않습니다.")
            .active("금도끼 은도끼",
                    "도끼를 던져({axe_range}블록) 맞은 적에게 {axe_damage} 피해를 주고 '이 도끼가 네 도끼냐?' 묻습니다. "
                            + "금도끼면 {gold_damage} 추가 피해, 은도끼면 {silver_damage} 추가 피해와 {silver_time}초간 "
                            + "이동 속도 -{silver_slow}%, 쇠도끼면 {iron_damage} 추가 피해와 약 {iron_push}블록 넉백을 "
                            + "줍니다. 멀리서 정확히 맞힐수록 금도끼가 잘 나오고({gold_min}~{gold_max}%), 나머지는 은과 쇠가 "
                            + "반반입니다.",
                    8)
            .ultimate("산신령 강림",
                    "구름 탄 산신령이 강림해 {tiger_delay}초 뒤 호랑이를 풀어놓습니다. 호랑이는 내가 바라보는 쪽으로 "
                            + "방향을 틀며 {tiger_distance}블록을 달려, 폭 {tiger_width}블록 길목의 적을 덮쳐 "
                            + "{tiger_damage} 피해를 주고 {tiger_pin}초간 짓누릅니다(이동·점프 불가). 다음 금도끼 "
                            + "은도끼는 금도끼가 됩니다.")
            .param("honest_need", 8)
            .param("axe_range", 18)
            .param("axe_damage", 2)
            .param("gold_damage", 4)
            .param("silver_damage", 1)
            .param("silver_slow", 50)
            .param("silver_time", 2.5)
            .param("iron_damage", 1)
            .param("iron_push", 6)
            .param("gold_min", 10)
            .param("gold_max", 60)
            .param("tiger_delay", 1)
            .param("tiger_distance", 18)
            .param("tiger_width", 3.5)
            .param("tiger_damage", 7)
            .param("tiger_pin", 1)
            .build();

    private static final String GOLD_TAG = "#FFC940";
    private static final String MIST_TAG = "#F4F7F5";
    private static final String SILVER_TAG = "#DDE6EE";
    private static final String IRON_TAG = "#9AA0A8";
    private static final double TAU = Math.PI * 2;

    // ------------------------------------------------------------------ look: mist white, gold, pine green
    /** Mountain mist white, spirit gold, pine green. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFFDF0, 0xFFC940, 0x2E6B3A);
    private static final Particle.DustTransition GOLD_GLOW = PALETTE.glow(1.0f);
    private static final Particle.DustTransition GOLD_SMALL = PALETTE.glow(0.6f);
    private static final Particle.DustOptions GOLD_DUST = PALETTE.dust(0.8f);
    private static final Particle.DustOptions MIST_DUST = new Particle.DustOptions(Color.fromRGB(0xF4F7F5), 1.6f);
    private static final Particle.DustOptions MIST_SMALL = new Particle.DustOptions(Color.fromRGB(0xF4F7F5), 0.9f);
    private static final Particle.DustOptions PINE_DUST = new Particle.DustOptions(Color.fromRGB(0x2E6B3A), 0.9f);
    private static final Particle.DustOptions SILVER_DUST = new Particle.DustOptions(Color.fromRGB(0xDDE6EE), 0.9f);
    private static final Particle.DustOptions IRON_DUST = new Particle.DustOptions(Color.fromRGB(0x8A8F96), 1.0f);
    private static final Particle.DustOptions TIGER_DUST = new Particle.DustOptions(Color.fromRGB(0xFF8A1E), 1.0f);
    private static final Particle.DustOptions CLAW_DUST = new Particle.DustOptions(Color.fromRGB(0xFFF1D6), 0.7f);

    /** Item and block types of the visuals; items and block data are created on use (they need a server). */
    private static final Material GOLD_AXE = Material.GOLDEN_AXE;
    private static final Material SILVER_AXE = Material.IRON_AXE;
    private static final Material PLAIN_AXE = Material.STONE_AXE;
    private static final Material WATER_SPLASH = Material.WATER;

    /** Hard cap for every velocity this ability applies. */
    private static final double MAX_SPEED = 1.5;
    /** Display entities one sansin keeps alive at most at once (extra visuals fall back to particles). */
    private static final int MAX_DISPLAYS = 24;

    // passive: honesty
    private static final float HALO_AXE_SCALE = 0.5f;
    private static final long SHIMMER_PERIOD = 8;
    private static final long HALO_SPARKLE_PERIOD = 4;

    // active: the thrown axe
    private static final double AXE_SPEED = 1.1;
    private static final double AXE_HITBOX = 0.45;
    private static final double AXE_SAMPLE = 0.3;
    private static final float AXE_SCALE = 0.9f;
    private static final float AXE_SPIN = 48f;
    /** The distance (share of the range) from which a throw counts as a long one. */
    private static final double LONG_THROW = 0.8;
    /** Ticks of the roulette before the verdict, and the ticks the highlight moves on. */
    private static final int[] ROULETTE = {0, 1, 2, 3, 5, 7, 10};
    private static final int VERDICT_TICK = ROULETTE[ROULETTE.length - 1];
    private static final int CHOP_TICKS = 3;
    private static final float VERDICT_AXE_SCALE = 0.55f;
    private static final double VERDICT_ROW = 0.55;
    private static final double VERDICT_ABOVE = 0.75;
    private static final double SILVER_SLIDE = 8.5;
    private static final double PUSH_LIFT = 0.35;

    // ultimate: the spirit and its tiger
    private static final float TIGER_SCALE = 1.35f;
    private static final double TIGER_SPEED = 0.8;
    private static final float TIGER_TURN = 4f;
    private static final double TIGER_STEP_UP = 1.1;
    private static final double TIGER_DROP = 3.0;
    private static final int TIGER_GROW_TICKS = 10;
    private static final int TIGER_LEAP_TICKS = 8;
    private static final long SPIRIT_LINGER = 12;
    private static final double SPIRIT_BEHIND = 1.4;
    private static final double SPIRIT_ABOVE = 1.6;
    private static final double TIGER_START = 1.4;

    private enum Verdict {
        GOLD("금도끼", GOLD_AXE), SILVER("은도끼", SILVER_AXE), IRON("쇠도끼", PLAIN_AXE);

        private final String label;
        private final Material axe;

        Verdict(String label, Material axe) {
            this.label = label;
            this.axe = axe;
        }
    }

    /** One block of a figure in its own space (origin on the ground, local +Z = facing, +X = its left). */
    private record Part(Material block, float x, float y, float z, float sx, float sy, float sz, Role role) {
    }

    /** How a part of the tiger moves while it runs. */
    private enum Role { BODY, HEAD, FRONT_LEFT, FRONT_RIGHT, BACK_LEFT, BACK_RIGHT, TAIL, EYE }

    private static final float HIP_Y = 0.62f;
    private static final float LEG_LENGTH = 0.6f;
    private static final Vector3f TAIL_ROOT = new Vector3f(0f, 0.95f, -0.62f);

    private static final Part[] TIGER = {
            new Part(Material.ORANGE_CONCRETE, 0f, 0.8f, 0f, 0.62f, 0.5f, 1.25f, Role.BODY),
            new Part(Material.BLACK_CONCRETE, 0f, 0.8f, -0.38f, 0.64f, 0.52f, 0.08f, Role.BODY),
            new Part(Material.BLACK_CONCRETE, 0f, 0.8f, 0.02f, 0.64f, 0.52f, 0.08f, Role.BODY),
            new Part(Material.BLACK_CONCRETE, 0f, 0.8f, 0.4f, 0.64f, 0.52f, 0.08f, Role.BODY),
            new Part(Material.ORANGE_CONCRETE, 0f, 1.04f, 0.78f, 0.5f, 0.44f, 0.42f, Role.HEAD),
            new Part(Material.WHITE_CONCRETE, 0f, 0.93f, 1.02f, 0.28f, 0.2f, 0.12f, Role.HEAD),
            new Part(Material.BLACK_CONCRETE, 0.17f, 1.3f, 0.72f, 0.12f, 0.12f, 0.06f, Role.HEAD),
            new Part(Material.BLACK_CONCRETE, -0.17f, 1.3f, 0.72f, 0.12f, 0.12f, 0.06f, Role.HEAD),
            new Part(Material.YELLOW_CONCRETE, 0.12f, 1.1f, 0.995f, 0.07f, 0.06f, 0.02f, Role.EYE),
            new Part(Material.YELLOW_CONCRETE, -0.12f, 1.1f, 0.995f, 0.07f, 0.06f, 0.02f, Role.EYE),
            new Part(Material.ORANGE_CONCRETE, 0.2f, HIP_Y, 0.42f, 0.16f, LEG_LENGTH, 0.16f, Role.FRONT_LEFT),
            new Part(Material.ORANGE_CONCRETE, -0.2f, HIP_Y, 0.42f, 0.16f, LEG_LENGTH, 0.16f, Role.FRONT_RIGHT),
            new Part(Material.ORANGE_CONCRETE, 0.2f, HIP_Y, -0.42f, 0.16f, LEG_LENGTH, 0.16f, Role.BACK_LEFT),
            new Part(Material.ORANGE_CONCRETE, -0.2f, HIP_Y, -0.42f, 0.16f, LEG_LENGTH, 0.16f, Role.BACK_RIGHT),
            new Part(Material.ORANGE_CONCRETE, 0f, 0f, 0f, 0.09f, 0.75f, 0.09f, Role.TAIL),
    };

    /** The spirit on its cloud (cloud, robe, head, beard); the staff (last) is posed separately. */
    private static final Part[] SPIRIT = {
            new Part(Material.WHITE_WOOL, 0f, 0.17f, 0f, 1.8f, 0.42f, 1.15f, Role.BODY),
            new Part(Material.LIGHT_GRAY_CONCRETE, 0f, 0.76f, 0f, 0.5f, 0.8f, 0.32f, Role.BODY),
            new Part(Material.WHITE_TERRACOTTA, 0f, 1.33f, 0f, 0.32f, 0.32f, 0.32f, Role.BODY),
            new Part(Material.WHITE_WOOL, 0f, 1.12f, 0.17f, 0.24f, 0.42f, 0.06f, Role.BODY),
            new Part(Material.STRIPPED_SPRUCE_LOG, -0.32f, 0.85f, 0.12f, 0.08f, 1.5f, 0.08f, Role.TAIL),
    };

    /**
     * The ultimate's share of {@link #MAX_DISPLAYS}: the whole spirit and tiger, kept free of everything the passive
     * and the active show, so casting the ultimate right after an axe hit still brings the full figures.
     */
    private static final int ULTIMATE_DISPLAYS = SPIRIT.length + TIGER.length;
    /** The rest, for the thrown axe or the judgement (which may push out the halo axe and the sign). */
    private static final int SKILL_DISPLAYS = MAX_DISPLAYS - ULTIMATE_DISPLAYS;

    private boolean granted;
    /** Damage taken from enemies towards the next golden promise. */
    private double honesty;
    private boolean goldPending;
    private @Nullable ItemDisplay haloAxe;
    private @Nullable TextDisplay sign;
    private boolean throwing;
    /** Displays of the passive and the active (at most {@link #SKILL_DISPLAYS}). */
    private final List<Display> skillDisplays = new ArrayList<>();
    /** Displays of the ultimate (at most {@link #ULTIMATE_DISPLAYS}). */
    private final List<Display> ultimateDisplays = new ArrayList<>();

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onGrant() {
        granted = true;
    }

    @Override
    public void onRevoke() {
        // The context removes the displays; only forget them here.
        granted = false;
        honesty = 0;
        goldPending = false;
        haloAxe = null;
        sign = null;
    }

    // ------------------------------------------------------------------ passive: honesty

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (!granted || goldPending || event.isCancelled() || !(event instanceof EntityDamageByEntityEvent byEntity)) {
            return;
        }
        double damage = event.getFinalDamage();
        if (!(damage > 0)) {
            return;
        }
        Player owner = player();
        Player attacker = ctx().resolveAttacker(byEntity.getDamager());
        if (attacker == null || attacker.equals(owner) || !ctx().isEnemy(owner, attacker)) {
            return;
        }
        double need = Math.max(0, params().num("honest_need"));
        honesty += damage;
        if (honesty >= need - 1.0E-9) {
            honesty = 0;
            bless(owner);
            return;
        }
        // Each blow brings the promise closer: a golden glimmer and the count in the action bar.
        Location chest = owner.getLocation().add(0, owner.getHeight() * 0.6, 0);
        Vfx.cloud(ctx(), chest, Particle.DUST_COLOR_TRANSITION, 2 + (int) (4 * honesty / Math.max(1, need)), 0.3, 0,
                GOLD_SMALL, false);
        Fx.sound(chest, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.35f, 0.8f + (float) (0.8 * honesty / Math.max(1, need)));
        owner.sendActionBar(Text.mm("<" + GOLD_TAG + ">정직</" + GOLD_TAG + "> " + honestyBar(honesty / need)
                + " <gray>" + AbilityInfo.formatNumber(Math.round(honesty * 10) / 10.0) + " / "
                + AbilityInfo.formatNumber(need) + "</gray>"));
    }

    private static String honestyBar(double progress) {
        int filled = (int) Math.clamp(Math.floor(progress * 8), 0, 8);
        return "<" + GOLD_TAG + ">" + "▮".repeat(filled) + "</" + GOLD_TAG + "><dark_gray>" + "▯".repeat(8 - filled)
                + "</dark_gray>";
    }

    /** "정직한 자로구나!": golden light falls on the sansin and the next axe is promised to be gold. */
    private void bless(Player owner) {
        promiseGold(owner);

        Location feet = owner.getLocation().add(0, 0.05, 0);
        Location head = owner.getLocation().add(0, owner.getHeight() + 0.3, 0);
        World world = feet.getWorld();
        Vfx.line(ctx(), head.clone().add(0, 3.5, 0), head, 4, Particle.DUST_COLOR_TRANSITION, GOLD_GLOW, false);
        world.spawnParticle(Particle.END_ROD, head.clone().add(0, 1.5, 0), Vfx.n(ctx(), 8), 0.15, 1.2, 0.15, 0.01);
        Vfx.ring(ctx(), feet, 0.8, 16, 0, Particle.DUST_COLOR_TRANSITION, GOLD_GLOW, false);
        Vfx.helix(ctx(), feet, 0.55, owner.getHeight(), 1.25, 2, 10, 0, Particle.DUST, GOLD_DUST, false);
        Fx.sound(head, Sound.BLOCK_BEACON_POWER_SELECT, 0.6f, 1.6f);
        Fx.sound(head, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9f, 1.3f);
        Fx.sound(head, Sound.ITEM_ARMOR_EQUIP_GOLD, 0.8f, 1.2f);
        TextDisplay old = sign;
        if (old != null && old.isValid()) {
            old.remove();
        }
        sign = null;
        // The sign is a flourish: it only takes a display that nothing else needs.
        if (skillRoom() >= 1) {
            TextDisplay text = Displays.text(ctx(), owner, head.clone().add(0, 0.4, 0),
                    Text.mm("<b><gradient:#FFFDF0:" + GOLD_TAG + ">정직한 자로구나!</gradient></b>"), 0.3f, 30);
            remember(text);
            Displays.animateNextTick(ctx(), owner, text, Displays.centered(0, 0.3f, 0, 1.1f, 1.1f, 1.1f, 0, 0, 0), 5);
            sign = text;
        }
        owner.sendActionBar(Text.mm("<" + GOLD_TAG + ">✦ 정직한 자로구나!</" + GOLD_TAG + "> <gray>다음 도끼는</gray> <"
                + GOLD_TAG + ">금도끼</" + GOLD_TAG + ">"));
    }

    /** From now until it is thrown, the next axe is gold; a little golden axe hovers over the shoulder meanwhile. */
    private void promiseGold(Player owner) {
        goldPending = true;
        honesty = 0;
        ItemDisplay axe = haloAxe;
        if ((axe == null || !axe.isValid()) && !throwing && skillRoom() >= 1) {
            spawnHalo(owner, 0);
        }
    }

    /** The hovering golden axe; it only takes a display the thrown axe and the judgement leave free. */
    private void spawnHalo(Player owner, long tick) {
        ItemDisplay axe = Displays.item(ctx(), owner, haloPoint(owner, tick), new ItemStack(GOLD_AXE),
                Displays.centered(0, 0, 0, 0.05f, 0.05f, 0.05f, 0, 0, 0), 0);
        axe.setTeleportDuration(1);
        remember(axe);
        Displays.animateNextTick(ctx(), owner, axe, haloPose(tick), 4);
        haloAxe = axe;
    }

    private static Location haloPoint(Player owner, long tick) {
        Location at = owner.getLocation();
        Quaternionf yaw = yawRotation(owner.getBodyYaw());
        float height = (float) (owner.getHeight() + 0.25 + 0.07 * Math.sin(tick * 0.12));
        Vector3f shoulder = yaw.transform(new Vector3f(-0.55f, height, -0.1f));
        return Displays.unrotated(at.add(shoulder.x(), shoulder.y(), shoulder.z()));
    }

    private static Transformation haloPose(long tick) {
        return Displays.centered(0, 0, 0, HALO_AXE_SCALE, HALO_AXE_SCALE, HALO_AXE_SCALE, tick * 6f, 0, 0);
    }

    @Override
    public void onTick(long tick) {
        if (!granted) {
            return;
        }
        Player owner = player();
        ItemDisplay axe = haloAxe;
        if (axe != null && (!axe.isValid() || !axe.getWorld().equals(owner.getWorld()))) {
            if (axe.isValid()) {
                axe.remove();
            }
            haloAxe = null;
            axe = null;
        }
        if (goldPending && !throwing && axe == null) {
            // The promise always shows: the halo axe comes back as soon as a display is free, golden sparkles until.
            if (skillRoom() >= 1) {
                spawnHalo(owner, tick);
            } else if (tick % HALO_SPARKLE_PERIOD == 0) {
                Location at = haloPoint(owner, tick);
                Vfx.dot(at, Particle.WAX_OFF, null, false);
                Vfx.dot(at.clone().add(0, 0.18, 0), Particle.DUST, GOLD_DUST, false);
                Vfx.dot(at.clone().add(0, -0.18, 0), Particle.DUST_COLOR_TRANSITION, GOLD_SMALL, false);
            }
            return;
        }
        if (axe != null) {
            Displays.moveTo(axe, haloPoint(owner, tick));
            if (tick % 2 == 0 && !throwing) {
                Displays.animate(axe, haloPose(tick), 2);
            }
            if (tick % SHIMMER_PERIOD == 0 && !throwing) {
                Location at = axe.getLocation();
                Vfx.dot(Vfx.scatter(at, 0.2), Particle.WAX_OFF, null, false);
                Vfx.dot(at.clone().add(0, -0.25, 0), Particle.DUST, GOLD_DUST, false);
            }
        }
    }

    // ------------------------------------------------------------------ active: the golden and the silver axe

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection();
        if (direction.lengthSquared() < 1.0E-6) {
            return ActivationResult.fail("조준 방향을 알 수 없습니다");
        }
        direction.normalize();
        boolean golden = goldPending;
        Location start = eye.clone().add(direction.clone().multiply(0.6));
        ThrownAxe axe = new ThrownAxe(owner, start, direction, golden);
        throwing = true;
        ItemDisplay halo = haloAxe;
        if (halo != null && halo.isValid()) {
            halo.setViewRange(0);
        }
        long maxTicks = (long) Math.ceil(Math.max(1, params().num("axe_range")) / AXE_SPEED) + 2;
        Loop.repeat(ctx(), owner, 1, 1, maxTicks, axe, axe::miss);
        Vfx.cloud(ctx(), start, Particle.DUST, 6, 0.15, 0, MIST_SMALL, false);
        Fx.sound(start, Sound.ITEM_TRIDENT_THROW, 0.9f, 0.8f + Fx.jitter(0.05f));
        Fx.sound(start, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.7f, 0.9f + Fx.jitter(0.05f));
        return ActivationResult.success();
    }

    /** The axe in flight: it tumbles end over end along the aim, hits the first enemy or drops at a wall. */
    private final class ThrownAxe implements LongPredicate {
        private final Player owner;
        private final World world;
        private final Location origin;
        private final Location position;
        private final Vector direction;
        private final boolean golden;
        private final @Nullable ItemDisplay visual;
        private double travelled;
        private float spin;
        private boolean done;

        ThrownAxe(Player owner, Location start, Vector direction, boolean golden) {
            this.owner = owner;
            this.world = start.getWorld();
            this.origin = start.clone();
            this.position = start.clone();
            this.direction = direction.clone();
            this.golden = golden;
            if (claimSkill(1)) {
                ItemDisplay display = Displays.item(ctx(), owner, Displays.unrotated(start),
                        new ItemStack(golden ? GOLD_AXE : SILVER_AXE), spinPose(direction, 0, 0.3f),
                        (long) Math.ceil(params().num("axe_range") / AXE_SPEED) + 12);
                display.setTeleportDuration(1);
                remember(display);
                visual = display;
            } else {
                visual = null;
            }
        }

        @Override
        public boolean test(long tick) {
            if (done) {
                return false;
            }
            double remaining = Math.max(0, params().num("axe_range")) - travelled;
            double step = Math.min(AXE_SPEED, remaining);
            if (step <= 1.0E-3) {
                miss();
                return false;
            }
            RayTraceResult wall = world.rayTraceBlocks(position, direction, step, FluidCollisionMode.NEVER, true);
            double travel = wall == null ? step : wall.getHitPosition().distance(position.toVector());
            int samples = Math.max(1, (int) Math.ceil(travel / AXE_SAMPLE));
            for (int i = 1; i <= samples; i++) {
                Location probe = position.clone().add(direction.clone().multiply(travel * i / samples));
                for (LivingEntity enemy : ctx().enemiesNear(owner, probe, AXE_HITBOX)) {
                    if (isVisible(enemy)) {
                        travelled += travel * i / samples;
                        position.set(probe.getX(), probe.getY(), probe.getZ());
                        hit(enemy);
                        return false;
                    }
                }
            }
            Location from = position.clone();
            position.add(direction.clone().multiply(travel));
            travelled += travel;
            fly(from);
            if (wall != null) {
                position.subtract(direction.clone().multiply(0.3));
                clang();
                miss();
                return false;
            }
            return true;
        }

        private void fly(Location from) {
            spin += AXE_SPIN;
            ItemDisplay display = visual;
            if (display != null && display.isValid()) {
                Displays.moveTo(display, position);
                Displays.animate(display, spinPose(direction, spin, AXE_SCALE), 1);
            }
            Vfx.line(ctx(), from, position, 2.5, Particle.DUST, MIST_SMALL, false);
            Vfx.dot(position, Particle.DUST_COLOR_TRANSITION, golden ? GOLD_GLOW : GOLD_SMALL, false);
            if (spin % (AXE_SPIN * 3) < AXE_SPIN) {
                Fx.sound(position, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.35f, 1.6f + Fx.jitter(0.1f));
            }
        }

        private void clang() {
            Vfx.cloud(ctx(), position, Particle.ELECTRIC_SPARK, 6, 0.1, 0.2, null, false);
            Fx.sound(position, Sound.ITEM_SHIELD_BLOCK, 0.6f, 1.4f + Fx.jitter(0.1f));
        }

        /** The axe hits: a sting, a splash like the pond of the tale, and the spirit's question. */
        private void hit(LivingEntity enemy) {
            done = true;
            removeVisual();
            Combat.hit(ctx(), owner, enemy, params().num("axe_damage"));
            Location body = Fx.body(enemy);
            splash(body);
            Fx.sound(body, Sound.ITEM_AXE_STRIP, 1.0f, 0.8f + Fx.jitter(0.05f));
            Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.8f, 0.9f + Fx.jitter(0.05f));
            if (!Combat.isTargetable(ctx(), owner, enemy)) {
                throwing = false;
                showHalo();
                return;
            }
            double chance = goldChance(enemy);
            Verdict verdict;
            ThreadLocalRandom random = ThreadLocalRandom.current();
            if (golden || random.nextDouble() < chance) {
                verdict = Verdict.GOLD;
            } else {
                verdict = random.nextBoolean() ? Verdict.SILVER : Verdict.IRON;
            }
            if (golden) {
                // The promise is kept: the hovering golden axe was the one thrown.
                goldPending = false;
                ItemDisplay halo = haloAxe;
                haloAxe = null;
                if (halo != null && halo.isValid()) {
                    halo.remove();
                }
            }
            throwing = false;
            showHalo();
            new Judgement(owner, enemy, verdict, golden, chance).start();
        }

        /**
         * The odds of gold: half from the aim (how close the flight line passed the victim's chest, against the hit
         * box) and half from the distance (a long throw counts fully).
         */
        private double goldChance(LivingEntity enemy) {
            Vector chest = Fx.body(enemy).toVector().subtract(origin.toVector());
            double along = chest.dot(direction);
            double perpendicular = chest.clone().subtract(direction.clone().multiply(along)).length();
            double accuracy = 1 - Math.clamp(perpendicular / (enemy.getWidth() * 0.5 + AXE_HITBOX), 0.0, 1.0);
            double range = Math.max(1, params().num("axe_range")) * LONG_THROW;
            double distance = Math.clamp(travelled / range, 0.0, 1.0);
            double score = (accuracy + distance) / 2;
            double min = params().num("gold_min");
            double max = params().num("gold_max");
            return Math.clamp((min + (max - min) * score) / 100.0, 0.0, 1.0);
        }

        /** Nothing hit (or a wall): the axe drops and sinks away in a splash. */
        void miss() {
            if (done) {
                return;
            }
            done = true;
            throwing = false;
            showHalo();
            ItemDisplay display = visual;
            if (display != null && display.isValid()) {
                Displays.moveTo(display, position);
                Displays.shrinkAway(ctx(), owner, display, 5);
            }
            splash(position);
            owner.sendActionBar(Text.mm("<gray>도끼가 빗나갔습니다</gray>"));
        }

        private void removeVisual() {
            if (visual != null && visual.isValid()) {
                visual.remove();
            }
        }
    }

    private void showHalo() {
        ItemDisplay halo = haloAxe;
        if (halo != null && halo.isValid()) {
            halo.setViewRange(1.0f);
        }
    }

    /** A splash like an axe falling into the pond of the tale: water drops, bubbles and a ring of mist. */
    private void splash(Location at) {
        World world = at.getWorld();
        world.spawnParticle(Particle.SPLASH, at, Vfx.n(ctx(), 16), 0.3, 0.2, 0.3, 0.1);
        world.spawnParticle(Particle.BUBBLE_POP, at, Vfx.n(ctx(), 6), 0.25, 0.2, 0.25, 0.02);
        world.spawnParticle(Particle.BLOCK, at, Vfx.n(ctx(), 6), 0.2, 0.15, 0.2, 0.1, WATER_SPLASH.createBlockData());
        Vfx.ring(ctx(), at, 0.6, 12, 0, Particle.DUST, MIST_SMALL, false);
        Fx.sound(at, Sound.ENTITY_GENERIC_SPLASH, 0.6f, 1.3f + Fx.jitter(0.1f));
    }

    /**
     * A tumbling axe: its blade sprite stands in the vertical plane of the flight (turned a quarter from the flight's
     * yaw) and spins about the sprite's normal, so it turns end over end along the path. The display itself is never
     * rotated.
     */
    static Transformation spinPose(Vector direction, float spin, float scale) {
        float yaw = yawOf(direction);
        return Displays.centered(0, 0, 0, scale, scale, scale, yaw + 90f, 0, -spin);
    }

    /** Minecraft's yaw (degrees: 0 = +Z, 90 = -X) of the horizontal part of {@code direction}, 0 if it has none. */
    static float yawOf(Vector direction) {
        Vector flat = Motion.horizontal(direction);
        if (flat.lengthSquared() < 1.0E-6) {
            return 0;
        }
        return (float) Math.toDegrees(Math.atan2(-flat.getX(), flat.getZ()));
    }

    /** Players hidden from the owner (stealth) are never targeted, so nothing gives them away. */
    private boolean isVisible(LivingEntity target) {
        return !(target instanceof Player hidden) || player().canSee(hidden);
    }

    /**
     * "이 도끼가 네 도끼냐?": three axes rise over the victim, a highlight runs over them like a roulette and stops on
     * the verdict, which chops down onto the victim.
     */
    private final class Judgement implements LongPredicate {
        private final Player owner;
        private final LivingEntity victim;
        private final Verdict verdict;
        private final boolean promised;
        private final double chance;
        private final ItemDisplay[] axes = new ItemDisplay[Verdict.values().length];
        private @Nullable TextDisplay question;
        private final Vector side;
        private int highlighted = -1;

        Judgement(Player owner, LivingEntity victim, Verdict verdict, boolean promised, double chance) {
            this.owner = owner;
            this.victim = victim;
            this.verdict = verdict;
            this.promised = promised;
            this.chance = chance;
            Vector across = victim.getLocation().toVector().subtract(owner.getLocation().toVector()).setY(0)
                    .crossProduct(new Vector(0, 1, 0));
            this.side = across.lengthSquared() > 1.0E-6 ? across.normalize() : new Vector(1, 0, 0);
        }

        void start() {
            Location over = over();
            // The judgement is what the throw is about: it may push out the sign and the halo axe for a moment.
            if (claimSkill(axes.length + 1)) {
                Verdict[] all = Verdict.values();
                for (int i = 0; i < all.length; i++) {
                    ItemDisplay axe = Displays.item(ctx(), owner, slot(over, i), new ItemStack(all[i].axe),
                            Displays.centered(0, 0, 0, 0.05f, 0.05f, 0.05f, 0, 0, 0), VERDICT_TICK + CHOP_TICKS + 20);
                    axe.setBillboard(Display.Billboard.VERTICAL);
                    axe.setTeleportDuration(1);
                    remember(axe);
                    axes[i] = axe;
                }
                TextDisplay text = Displays.text(ctx(), owner, over.clone().add(0, 0.65, 0),
                        Text.mm("<" + MIST_TAG + "><i>이 도끼가 네 도끼냐?</i></" + MIST_TAG + ">"), 0.65f,
                        VERDICT_TICK + CHOP_TICKS + 24);
                text.setTeleportDuration(1);
                remember(text);
                question = text;
            }
            Vfx.cloud(ctx(), over, Particle.DUST, 10, 0.5, 0, MIST_DUST, false);
            Vfx.cloud(ctx(), over, Particle.END_ROD, 4, 0.4, 0.01, null, false);
            Fx.sound(over, Sound.BLOCK_BUBBLE_COLUMN_UPWARDS_INSIDE, 0.8f, 1.2f);
            Fx.sound(over, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 0.8f);
            Loop.repeat(ctx(), owner, 1, 1, VERDICT_TICK + 1, this, () -> { });
        }

        private Location over() {
            return Displays.unrotated(victim.getLocation().add(0, victim.getHeight() + VERDICT_ABOVE, 0));
        }

        private Location slot(Location over, int index) {
            return over.clone().add(side.clone().multiply((index - 1) * VERDICT_ROW));
        }

        @Override
        public boolean test(long tick) {
            if (!Combat.isTargetable(ctx(), owner, victim) || !victim.getWorld().equals(owner.getWorld())) {
                vanish(false);
                return false;
            }
            if (!isVisible(victim)) {
                // The victim slipped out of sight: the question must not hover over it and give it away.
                vanish(true);
                return false;
            }
            Location over = over();
            for (int i = 0; i < axes.length; i++) {
                ItemDisplay axe = axes[i];
                if (axe != null && axe.isValid()) {
                    Displays.moveTo(axe, slot(over, i));
                }
            }
            TextDisplay text = question;
            if (text != null && text.isValid()) {
                Displays.moveTo(text, over.clone().add(0, 0.65, 0));
            }
            for (int k = 0; k < ROULETTE.length; k++) {
                if (ROULETTE[k] == tick) {
                    highlight((verdict.ordinal() + k) % axes.length, k);
                }
            }
            if (tick == VERDICT_TICK) {
                pronounce();
                return false;
            }
            return true;
        }

        /** The highlight jumps to axe {@code index}: it swells and the others dim, with a tick of a chime. */
        private void highlight(int index, int step) {
            highlighted = index;
            for (int i = 0; i < axes.length; i++) {
                ItemDisplay axe = axes[i];
                if (axe == null || !axe.isValid()) {
                    continue;
                }
                float size = VERDICT_AXE_SCALE * (i == index ? 1.35f : 0.8f);
                Displays.animate(axe, Displays.centered(0, i == index ? 0.12f : 0, 0, size, size, size, 0, 0,
                        i == index ? 15f : 0), 2);
            }
            Location at = slot(over(), index);
            Vfx.dot(at, Particle.END_ROD, null, false);
            Fx.sound(at, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.6f, 0.9f + step * 0.12f);
        }

        /** The verdict: the chosen axe chops down, the others sink back into the pond. */
        private void pronounce() {
            int chosen = verdict.ordinal();
            for (int i = 0; i < axes.length; i++) {
                ItemDisplay axe = axes[i];
                if (axe == null || !axe.isValid()) {
                    continue;
                }
                if (i == chosen) {
                    float size = VERDICT_AXE_SCALE * 1.6f;
                    Displays.animate(axe, Displays.centered(0, (float) -(VERDICT_ABOVE + victim.getHeight() * 0.45),
                            0, size, size, size, 0, 0, -80f), CHOP_TICKS);
                    ctx().runLater(owner, CHOP_TICKS + 4, () -> {
                        if (axe.isValid()) {
                            Displays.shrinkAway(ctx(), owner, axe, 4);
                        }
                    });
                } else {
                    Displays.shrinkAway(ctx(), owner, axe, 4);
                }
            }
            TextDisplay text = question;
            if (text != null && text.isValid()) {
                text.text(verdictText());
                Displays.animate(text, Displays.centered(0, 0.2f, 0, 1.3f, 1.3f, 1.3f, 0, 0, 0), 3);
            }
            ctx().runLater(owner, CHOP_TICKS, this::chop);
        }

        private Component verdictText() {
            return switch (verdict) {
                case GOLD -> Text.mm("<b><gradient:#FFF6C9:" + GOLD_TAG + ">금도끼!</gradient></b>");
                case SILVER -> Text.mm("<b><" + SILVER_TAG + ">은도끼</" + SILVER_TAG + "></b>");
                case IRON -> Text.mm("<b><" + IRON_TAG + ">쇠도끼</" + IRON_TAG + "></b>");
            };
        }

        /** The axe lands: gold hits hard, silver slows, iron shoves (unless the victim fell or slipped out of sight). */
        private void chop() {
            if (!Combat.isTargetable(ctx(), owner, victim) || !isVisible(victim)) {
                vanish(true);
                return;
            }
            Location body = Fx.body(victim);
            World world = body.getWorld();
            switch (verdict) {
                case GOLD -> {
                    Combat.hit(ctx(), owner, victim, params().num("gold_damage"));
                    Vfx.burst(ctx(), body, PALETTE, 0.9, Particle.WAX_OFF, false);
                    world.spawnParticle(Particle.ITEM, body, Vfx.n(ctx(), 10), 0.25, 0.3, 0.25, 0.15,
                            new ItemStack(Material.GOLD_NUGGET));
                    Vfx.shockwave(ctx(), owner, victim.getLocation().add(0, 0.1, 0), 0.3, 1.8, 5,
                            Particle.DUST_COLOR_TRANSITION, GOLD_GLOW, false);
                    Fx.sound(body, Sound.BLOCK_ANVIL_LAND, 0.5f, 1.6f);
                    Fx.sound(body, Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.5f);
                    Fx.sound(body, Sound.ITEM_AXE_STRIP, 1.0f, 0.6f);
                }
                case SILVER -> {
                    Combat.hit(ctx(), owner, victim, params().num("silver_damage"));
                    double slow = Math.clamp(params().num("silver_slow") / 100.0, 0.0, 1.0);
                    long time = params().ticks("silver_time");
                    if (slow > 0 && time > 0 && Combat.isTargetable(ctx(), owner, victim)) {
                        ctx().addModifier(owner, victim, Attribute.MOVEMENT_SPEED, -slow,
                                Operation.MULTIPLY_SCALAR_1, time);
                        silverChill(time);
                    }
                    world.spawnParticle(Particle.DUST, body, Vfx.n(ctx(), 14), 0.3, 0.4, 0.3, 0, SILVER_DUST);
                    Vfx.cloud(ctx(), body, Particle.SNOWFLAKE, 8, 0.3, 0.02, null, false);
                    Fx.sound(body, Sound.BLOCK_CHAIN_HIT, 0.9f, 1.3f);
                    Fx.sound(body, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 0.7f, 1.2f);
                }
                case IRON -> {
                    Combat.hit(ctx(), owner, victim, params().num("iron_damage"));
                    if (Combat.isTargetable(ctx(), owner, victim)) {
                        Vector away = victim.getLocation().toVector().subtract(owner.getLocation().toVector())
                                .setY(0);
                        if (away.lengthSquared() < 1.0E-4) {
                            away = Motion.horizontalFacing(owner.getLocation());
                        }
                        double speed = Math.max(0, params().num("iron_push")) / SILVER_SLIDE;
                        Motion.push(victim, away.normalize().multiply(speed).setY(PUSH_LIFT), MAX_SPEED);
                    }
                    world.spawnParticle(Particle.DUST, body, Vfx.n(ctx(), 12), 0.3, 0.4, 0.3, 0, IRON_DUST);
                    Vfx.cloud(ctx(), body, Particle.CRIT, 10, 0.3, 0.3, null, false);
                    Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 0.8f);
                    Fx.sound(body, Sound.BLOCK_ANVIL_LAND, 0.4f, 0.8f);
                }
            }
            String odds = promised ? "정직의 상" : "금도끼 확률 " + Math.round(chance * 100) + "%";
            owner.sendActionBar(Text.mm("<" + GOLD_TAG + ">금도끼 은도끼</" + GOLD_TAG + "> ").append(verdictText())
                    .append(Text.mm(" <dark_gray>|</dark_gray> <gray>" + odds + "</gray>")));
        }

        /** Silver's chill: frosty silver motes trickle off the slowed victim while it lasts. */
        private void silverChill(long time) {
            Loop.repeat(ctx(), owner, 2, 4, Math.max(1, time / 4), run -> {
                if (!victim.isValid() || victim.isDead() || !isVisible(victim)) {
                    return false;
                }
                Location feet = victim.getLocation().add(0, 0.1, 0);
                Vfx.ring(ctx(), feet, 0.5, 8, run * 0.4, Particle.DUST, SILVER_DUST, false);
                return true;
            });
        }

        /** The judgement is called off: the axes sink away ({@code instant}: gone at once, so they mark nothing). */
        private void vanish(boolean instant) {
            for (ItemDisplay axe : axes) {
                if (axe != null && axe.isValid()) {
                    if (instant) {
                        axe.remove();
                    } else {
                        Displays.shrinkAway(ctx(), owner, axe, 3);
                    }
                }
            }
            TextDisplay text = question;
            if (text != null && text.isValid()) {
                text.remove();
            }
        }
    }

    // ------------------------------------------------------------------ ultimate: the spirit descends

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        Location feet = owner.getLocation();
        Vector facing = Motion.horizontalFacing(feet);
        Location start = feet.clone().add(facing.clone().multiply(TIGER_START));
        Location ground = start.clone().add(0, TIGER_STEP_UP, 0);
        if (!Spots.isFree(owner.getWorld(), ground, 0.6, 0.4)) {
            ground = feet.clone();
        } else {
            Spots.snapToGround(owner.getWorld(), ground, TIGER_STEP_UP + TIGER_DROP);
            if (ground.getY() > start.getY() + TIGER_STEP_UP - 1.0E-3) {
                ground = feet.clone();
            }
        }
        new Descent(owner, Displays.unrotated(ground), yawOf(facing)).start();
        if (granted) {
            promiseGold(owner);
        }
        return ActivationResult.success();
    }

    /** The spirit on its cloud and its tiger: the summoning, the run and the fading back into the mist. */
    private final class Descent implements LongPredicate {
        private final Player owner;
        private final World world;
        private final Location position;
        private final Location spiritSpot;
        private final long delay;
        private final double distance;
        private final List<BlockDisplay> tiger = new ArrayList<>();
        private final List<BlockDisplay> spirit = new ArrayList<>();
        private final Set<UUID> pounced = new HashSet<>();
        private float yaw;
        private final float spiritYaw;
        private double travelled;
        private double stride;
        private int pins;

        Descent(Player owner, Location ground, float yaw) {
            this.owner = owner;
            this.world = ground.getWorld();
            this.position = ground.clone();
            this.yaw = yaw;
            this.spiritYaw = yaw;
            this.delay = Math.max(1, params().ticks("tiger_delay"));
            this.distance = Math.max(1, params().num("tiger_distance"));
            Location feet = owner.getLocation();
            Vector back = Motion.horizontalFacing(feet).multiply(-SPIRIT_BEHIND);
            Location spot = feet.clone().add(back).add(0, SPIRIT_ABOVE, 0);
            if (!Spots.isFree(world, spot, 1.0, 1.8)) {
                spot = feet.clone().add(0, SPIRIT_ABOVE + 0.6, 0);
            }
            this.spiritSpot = Displays.unrotated(spot);
        }

        void start() {
            long runTicks = (long) Math.ceil(distance / TIGER_SPEED) + 2;
            long life = delay + runTicks + TIGER_LEAP_TICKS + SPIRIT_LINGER + 30;
            if (canSpawnUltimate(SPIRIT.length)) {
                for (Part part : SPIRIT) {
                    BlockDisplay display = Displays.block(ctx(), owner, spiritSpot, part.block().createBlockData(),
                            spiritPose(part, spiritYaw, 0.05f, 0f, 0f), life);
                    display.setTeleportDuration(1);
                    if (part.block() != Material.WHITE_WOOL) {
                        display.setBrightness(null);
                    }
                    rememberUltimate(display);
                    spirit.add(display);
                }
            }
            if (canSpawnUltimate(TIGER.length)) {
                for (Part part : TIGER) {
                    BlockDisplay display = Displays.block(ctx(), owner, position, part.block().createBlockData(),
                            tigerPose(part, yaw, 0.05f, 0, 0, 0), life);
                    display.setTeleportDuration(1);
                    if (part.role() == Role.EYE) {
                        Displays.outline(display, PALETTE.body());
                    }
                    rememberUltimate(display);
                    tiger.add(display);
                }
            }
            World w = spiritSpot.getWorld();
            w.spawnParticle(Particle.CLOUD, spiritSpot, Vfx.n(ctx(), 24), 1.0, 0.4, 1.0, 0.02, null, true);
            w.spawnParticle(Particle.DUST, spiritSpot, Vfx.n(ctx(), 20), 1.2, 0.5, 1.2, 0, MIST_DUST, true);
            Vfx.cloud(ctx(), position.clone().add(0, 0.6, 0), Particle.CLOUD, 14, 0.6, 0.02, null, true);
            Fx.sound(spiritSpot, Sound.BLOCK_BELL_USE, 1.2f, 0.5f);
            Fx.sound(spiritSpot, Sound.BLOCK_BELL_RESONATE, 0.8f, 0.6f);
            Fx.sound(spiritSpot, Sound.BLOCK_BEACON_ACTIVATE, 0.9f, 1.3f);
            Fx.sound(position, Sound.ENTITY_POLAR_BEAR_WARNING, 1.0f, 0.6f);
            owner.sendActionBar(Text.mm("<" + GOLD_TAG + "><b>산신령 강림</b></" + GOLD_TAG + "> <gray>"
                    + AbilityInfo.formatNumber(delay / 20.0) + "초 뒤 호랑이가 달려나갑니다 · 바라보는 쪽으로 방향을 틉니다"
                    + "</gray>"));
            Loop.repeat(ctx(), owner, 1, 1, delay + runTicks, this, this::finish);
        }

        @Override
        public boolean test(long tick) {
            float bob = (float) (0.08 * Math.sin(tick * 0.15));
            if (tick < delay) {
                summon(tick, bob);
                return true;
            }
            if (tick == delay) {
                release();
            }
            poseSpirit(bob, 55f);
            return run(tick);
        }

        /** The summoning: the tiger grows out of the mist and crouches; a golden lane shows where it will run. */
        private void summon(long tick, float bob) {
            float grow = Math.min(1f, (tick + 1f) / TIGER_GROW_TICKS);
            float crouch = 0.12f * grow;
            poseTiger(0.2f + 0.8f * grow, 0, crouch, 0);
            poseSpirit(bob, -12f);
            if (tick % 4 == 0) {
                Vector dir = direction();
                for (double d = 1; d <= distance; d += 0.6) {
                    Location point = position.clone().add(dir.clone().multiply(d)).add(0, 0.08, 0);
                    Vfx.dot(point, Particle.DUST_COLOR_TRANSITION, GOLD_SMALL, true);
                }
            }
            if (tick % 3 == 0) {
                Vfx.dot(Vfx.scatter(spiritSpot, 1.0), Particle.CLOUD, null, false);
                Vfx.dot(Vfx.scatter(position.clone().add(0, 0.5, 0), 0.8), Particle.DUST, MIST_DUST, false);
            }
            if (tick == delay / 2) {
                Fx.sound(position, Sound.ENTITY_POLAR_BEAR_WARNING, 1.2f, 0.75f);
            }
        }

        /** The spirit points its staff and the tiger roars and bounds off. */
        private void release() {
            Location head = position.clone().add(direction().multiply(TIGER_SCALE)).add(0, TIGER_SCALE, 0);
            Vfx.cloud(ctx(), head, Particle.CLOUD, 10, 0.4, 0.06, null, true);
            Vfx.shockwave(ctx(), owner, position.clone().add(0, 0.1, 0), 0.4, 2.4, 6, Particle.DUST, MIST_DUST, true);
            Fx.sound(head, Sound.ENTITY_RAVAGER_ROAR, 1.4f, 1.1f);
            Fx.sound(head, Sound.ENTITY_POLAR_BEAR_WARNING, 1.2f, 0.5f);
            Fx.sound(spiritSpot, Sound.ITEM_GOAT_HORN_SOUND_0, 0.6f, 1.2f);
        }

        /** One stride: steering towards where the owner looks, following the ground, pinning whoever is in reach. */
        private boolean run(long tick) {
            if (travelled >= distance) {
                return false;
            }
            steer();
            Vector dir = direction();
            double step = Math.min(TIGER_SPEED, distance - travelled);
            Location next = position.clone().add(dir.clone().multiply(step));
            Location probe = next.clone().add(0, TIGER_STEP_UP, 0);
            if (!Spots.isFree(world, probe, 0.6, 0.4)) {
                crash();
                return false;
            }
            Spots.snapToGround(world, probe, TIGER_STEP_UP + TIGER_DROP);
            if (probe.getY() > next.getY() + TIGER_STEP_UP - 1.0E-3 || !Spots.isFree(world, probe, 0.6, 1.0)
                    || !ctx().isInsideArena(probe)) {
                // No ground ahead (a cliff), a wall or the border: the tiger leaps away here.
                return false;
            }
            Location from = position.clone();
            position.set(probe.getX(), probe.getY(), probe.getZ());
            travelled += step;
            stride += 0.9;
            poseTiger(1f, stride, 0f, (float) (0.05 * Math.abs(Math.sin(stride))));
            pounce();
            trail(from, tick);
            return true;
        }

        private void steer() {
            if (!owner.isOnline() || !owner.getWorld().equals(world)) {
                return;
            }
            float want = owner.getLocation().getYaw();
            float diff = (float) Math.IEEEremainder(want - yaw, 360.0);
            yaw += Math.clamp(diff, -TIGER_TURN, TIGER_TURN);
        }

        private Vector direction() {
            double radians = Math.toRadians(yaw);
            return new Vector(-Math.sin(radians), 0, Math.cos(radians));
        }

        /** Every enemy within the lane's half width is pounced on once: a heavy blow and a short pin. */
        private void pounce() {
            double half = Math.max(0.3, params().num("tiger_width") / 2);
            Location center = position.clone().add(0, 0.8 * TIGER_SCALE, 0);
            for (LivingEntity enemy : ctx().enemiesNear(owner, center, half)) {
                if (pounced.contains(enemy.getUniqueId()) || !isVisible(enemy)
                        || Math.abs(enemy.getLocation().getY() - position.getY()) > 2.5
                        || !Spots.clearPath(center, Fx.body(enemy))) {
                    continue;
                }
                pounced.add(enemy.getUniqueId());
                Combat.hit(ctx(), owner, enemy, params().num("tiger_damage"));
                pins++;
                long pin = params().ticks("tiger_pin");
                if (pin > 0 && Combat.isTargetable(ctx(), owner, enemy)) {
                    ctx().addModifier(owner, enemy, Attribute.MOVEMENT_SPEED, -1.0, Operation.MULTIPLY_SCALAR_1, pin);
                    ctx().addModifier(owner, enemy, Attribute.JUMP_STRENGTH, -1.0, Operation.MULTIPLY_SCALAR_1, pin);
                }
                claws(enemy);
            }
        }

        /** Three raking claw marks across the victim, a roar and a puff of mist. */
        private void claws(LivingEntity enemy) {
            Location body = Fx.body(enemy);
            Vector dir = direction();
            Vector side = dir.clone().crossProduct(new Vector(0, 1, 0)).normalize();
            for (int i = -1; i <= 1; i++) {
                Location top = body.clone().add(side.clone().multiply(i * 0.18)).add(0, 0.5, 0)
                        .subtract(dir.clone().multiply(0.35));
                Location bottom = body.clone().add(side.clone().multiply(i * 0.18 + 0.25)).add(0, -0.45, 0)
                        .subtract(dir.clone().multiply(0.35));
                Vfx.line(ctx(), top, bottom, 8, Particle.DUST, CLAW_DUST, true);
                Vfx.line(ctx(), top, bottom, 3, Particle.DUST, TIGER_DUST, true);
            }
            Vfx.burst(ctx(), body, PALETTE, 0.8, Particle.CLOUD, true);
            Vfx.cloud(ctx(), body, Particle.CRIT, 12, 0.3, 0.35, null, true);
            Fx.sound(body, Sound.ENTITY_RAVAGER_ATTACK, 1.0f, 1.0f + Fx.jitter(0.08f));
            Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 0.7f);
            Fx.sound(body, Sound.ENTITY_POLAR_BEAR_WARNING, 0.9f, 1.0f + Fx.jitter(0.1f));
        }

        /** Dust kicked up behind, an orange streak and wisps of mountain mist. */
        private void trail(Location from, long tick) {
            Location feet = position.clone().add(0, 0.1, 0);
            Vfx.line(ctx(), from.clone().add(0, 0.8, 0), feet.clone().add(0, 0.7, 0), 2, Particle.DUST, TIGER_DUST,
                    false);
            Vfx.dot(Vfx.scatter(feet, 0.5), Particle.CLOUD, null, false);
            if (tick % 2 == 0) {
                Vfx.dot(Vfx.scatter(feet.clone().add(0, 0.4, 0), 0.6), Particle.DUST, MIST_SMALL, false);
                org.bukkit.block.data.BlockData floor = feet.clone().subtract(0, 0.3, 0).getBlock().getBlockData();
                if (floor.getMaterial().isSolid()) {
                    world.spawnParticle(Particle.BLOCK, feet, Vfx.n(ctx(), 4), 0.3, 0.05, 0.3, 0.1, floor);
                }
            }
            if (tick % 5 == 0) {
                Fx.sound(feet, Sound.ENTITY_RAVAGER_STEP, 0.7f, 1.3f + Fx.jitter(0.1f));
            }
        }

        /** The tiger runs into a wall: it rears back with a growl. */
        private void crash() {
            Location head = position.clone().add(direction().multiply(TIGER_SCALE)).add(0, TIGER_SCALE * 0.8, 0);
            Vfx.cloud(ctx(), head, Particle.CLOUD, 8, 0.3, 0.04, null, false);
            Fx.sound(head, Sound.ENTITY_POLAR_BEAR_WARNING, 0.8f, 1.3f);
        }

        /** The run is over: the tiger leaps and dissolves into mist, then the spirit fades away on its cloud. */
        private void finish() {
            Vector dir = direction();
            Vector3f leap = new Vector3f((float) dir.getX() * 1.6f, 1.3f, (float) dir.getZ() * 1.6f);
            for (BlockDisplay part : tiger) {
                if (!part.isValid()) {
                    continue;
                }
                Transformation current = part.getTransformation();
                Transformation away = new Transformation(new Vector3f(current.getTranslation()).add(leap),
                        current.getLeftRotation(), new Vector3f(current.getScale()).mul(0.3f), new Quaternionf());
                Displays.animate(part, away, TIGER_LEAP_TICKS);
                ctx().runLater(owner, TIGER_LEAP_TICKS, () -> {
                    if (part.isValid()) {
                        part.remove();
                    }
                });
            }
            Location mist = position.clone().add(dir.clone().multiply(1.6)).add(0, 1.3, 0);
            ctx().runLater(owner, TIGER_LEAP_TICKS, () -> {
                World w = mist.getWorld();
                w.spawnParticle(Particle.CLOUD, mist, Vfx.n(ctx(), 16), 0.6, 0.4, 0.6, 0.03, null, true);
                w.spawnParticle(Particle.DUST, mist, Vfx.n(ctx(), 12), 0.6, 0.4, 0.6, 0, MIST_DUST, true);
                w.spawnParticle(Particle.DUST, mist, Vfx.n(ctx(), 8), 0.5, 0.4, 0.5, 0, PINE_DUST, true);
                Fx.sound(mist, Sound.ENTITY_PARROT_FLY, 0.5f, 0.6f);
            });
            Fx.sound(position, Sound.ENTITY_RAVAGER_ROAR, 0.8f, 1.4f);
            ctx().runLater(owner, TIGER_LEAP_TICKS + SPIRIT_LINGER, () -> {
                for (BlockDisplay part : spirit) {
                    if (part.isValid()) {
                        Displays.shrinkAway(ctx(), owner, part, 8);
                    }
                }
                World w = spiritSpot.getWorld();
                w.spawnParticle(Particle.CLOUD, spiritSpot, Vfx.n(ctx(), 20), 1.0, 0.5, 1.0, 0.02, null, true);
                Fx.sound(spiritSpot, Sound.BLOCK_BELL_USE, 0.9f, 0.7f);
            });
            if (owner.isOnline()) {
                owner.sendActionBar(Text.mm("<" + GOLD_TAG + ">산신령 강림</" + GOLD_TAG + "> <white>" + pins
                        + "명</white> <gray>덮침</gray>"));
            }
        }

        private void poseTiger(float grow, double phase, float crouch, float bob) {
            if (tiger.isEmpty()) {
                sketchTiger(grow);
                return;
            }
            for (int i = 0; i < tiger.size(); i++) {
                BlockDisplay display = tiger.get(i);
                if (!display.isValid() || !display.getWorld().equals(world)) {
                    continue;
                }
                Displays.moveTo(display, position);
                Displays.animate(display, tigerPose(TIGER[i], yaw, grow, phase, crouch, bob), 1);
            }
        }

        /** No displays to spare (a second spirit at once): the tiger is sketched in orange dust instead. */
        private void sketchTiger(float grow) {
            Vector dir = direction();
            Location body = position.clone().add(0, 0.8 * TIGER_SCALE * grow, 0);
            for (int i = -1; i <= 1; i++) {
                Vfx.dot(body.clone().add(dir.clone().multiply(i * 0.5 * grow)), Particle.DUST, TIGER_DUST, false);
            }
            Location head = body.clone().add(dir.clone().multiply(1.05 * grow)).add(0, 0.3 * grow, 0);
            Vfx.dot(head, Particle.DUST, TIGER_DUST, false);
            Vfx.dot(head.clone().add(0, 0.1, 0), Particle.DUST_COLOR_TRANSITION, GOLD_SMALL, false);
        }

        private void poseSpirit(float bob, float staff) {
            for (int i = 0; i < spirit.size(); i++) {
                BlockDisplay display = spirit.get(i);
                if (!display.isValid()) {
                    continue;
                }
                Displays.animate(display, spiritPose(SPIRIT[i], spiritYaw, 1f, bob, staff), 2);
            }
        }
    }

    /**
     * A tiger part in the tiger's own space scaled by {@link #TIGER_SCALE} and {@code grow}, turned to {@code yaw}.
     * Legs swing about their hips with the stride {@code phase} (diagonal pairs together), the tail wags, the body
     * bobs and crouches. The display stays unrotated at the tiger's feet; everything lives in the transformation.
     */
    private static Transformation tigerPose(Part part, float yaw, float grow, double phase, float crouch,
                                            float bob) {
        float s = TIGER_SCALE * Math.max(0.01f, grow);
        Quaternionf facing = yawRotation(yaw);
        Quaternionf local = new Quaternionf();
        Vector3f center;
        Vector3f size = new Vector3f(part.sx(), part.sy(), part.sz()).mul(s);
        float lift = bob - crouch;
        switch (part.role()) {
            case FRONT_LEFT, FRONT_RIGHT, BACK_LEFT, BACK_RIGHT -> {
                boolean diagonal = part.role() == Role.FRONT_LEFT || part.role() == Role.BACK_RIGHT;
                float swing = (float) (Math.sin(phase + (diagonal ? 0 : Math.PI)) * Math.toRadians(35));
                local.rotateX(swing);
                // The legs bend with the crouch and stretch with the bob, so the paws stay on the ground.
                float length = Math.max(0.05f, part.sy() + lift);
                size = new Vector3f(part.sx(), length, part.sz()).mul(s);
                Vector3f hip = new Vector3f(part.x(), part.y() + lift, part.z());
                center = new Vector3f(hip).add(local.transform(new Vector3f(0, -length * 0.5f, 0)));
            }
            case TAIL -> {
                float wag = (float) (Math.sin(phase * 0.8) * 0.5);
                Vector3f dir = new Vector3f((float) Math.sin(wag) * 0.5f, 0.7f, -0.75f).normalize();
                local.rotationTo(new Vector3f(0, 1, 0), dir);
                Vector3f root = new Vector3f(TAIL_ROOT).add(0, lift, 0);
                center = root.add(local.transform(new Vector3f(0, part.sy() * 0.5f, 0)));
            }
            case HEAD, EYE -> center = new Vector3f(part.x(), part.y() + lift + bob * 0.5f, part.z());
            default -> center = new Vector3f(part.x(), part.y() + lift, part.z());
        }
        center.mul(s);
        Quaternionf rotation = new Quaternionf(facing).mul(local);
        Vector3f world = facing.transform(center);
        Vector3f translation = world.sub(rotation.transform(new Vector3f(size).mul(0.5f)));
        return new Transformation(translation, rotation, size, new Quaternionf());
    }

    /**
     * A part of the spirit on its cloud, turned to {@code yaw}, bobbing by {@code bob}. The staff (the {@link Role#TAIL}
     * part) is held at the right hand and tipped forward by {@code staff} degrees.
     */
    private static Transformation spiritPose(Part part, float yaw, float grow, float bob, float staff) {
        float s = Math.max(0.01f, grow);
        Quaternionf facing = yawRotation(yaw);
        Quaternionf local = new Quaternionf();
        Vector3f size = new Vector3f(part.sx(), part.sy(), part.sz()).mul(s);
        Vector3f center;
        if (part.role() == Role.TAIL) {
            local.rotateX((float) Math.toRadians(staff));
            Vector3f hand = new Vector3f(part.x(), part.y() + bob, part.z());
            center = hand.add(local.transform(new Vector3f(0, part.sy() * 0.35f, 0)));
        } else {
            center = new Vector3f(part.x(), part.y() + bob, part.z());
        }
        center.mul(s);
        Quaternionf rotation = new Quaternionf(facing).mul(local);
        Vector3f world = facing.transform(center);
        Vector3f translation = world.sub(rotation.transform(new Vector3f(size).mul(0.5f)));
        return new Transformation(translation, rotation, size, new Quaternionf());
    }

    private static Quaternionf yawRotation(float yaw) {
        return new Quaternionf().rotateY((float) Math.toRadians(-yaw));
    }

    // ------------------------------------------------------------------ display budget

    /** Free displays in the passive's and the active's share. */
    private int skillRoom() {
        skillDisplays.removeIf(display -> !display.isValid());
        return SKILL_DISPLAYS - skillDisplays.size();
    }

    /**
     * True if {@code count} more displays fit in the passive's and the active's share, making room if needed by
     * removing the sign and then the halo axe (which comes back once there is room, sparkling until then).
     */
    private boolean claimSkill(int count) {
        if (skillRoom() >= count) {
            return true;
        }
        TextDisplay text = sign;
        sign = null;
        if (text != null && text.isValid()) {
            text.remove();
        }
        if (skillRoom() >= count) {
            return true;
        }
        ItemDisplay halo = haloAxe;
        haloAxe = null;
        if (halo != null && halo.isValid()) {
            halo.remove();
        }
        return skillRoom() >= count;
    }

    /** True if {@code count} more displays fit in the ultimate's own share. */
    private boolean canSpawnUltimate(int count) {
        ultimateDisplays.removeIf(display -> !display.isValid());
        return ultimateDisplays.size() + count <= ULTIMATE_DISPLAYS;
    }

    private void remember(Display display) {
        skillDisplays.add(display);
    }

    private void rememberUltimate(Display display) {
        ultimateDisplays.add(display);
    }
}
