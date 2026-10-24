package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Body;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.LandingGuard;
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
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.tag.DamageTypeTags;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 사방신 — the four guardians of the Goguryeo tomb murals: the azure dragon of the east, the vermilion bird of the
 * south, the white tiger of the west and the black tortoise of the north. The owner's stance turns through them in that
 * fixed order (east, south, west, north), one step per active, and the current and the next guardian are shown to
 * everyone, so both sides can read the next move: a tiger pounce, a swap, a flight, a dragon wave. Each stance carries
 * its own bonus; each summon is a spirit that appears once (nothing stays behind like a summoner's pack). The ultimate
 * raises a square barrier whose four quarters keep turning clockwise, so enemies inside must keep moving away from the
 * tiger's quarter while the owner, standing in the middle, holds all four stances at once.
 *
 * <p>Look: the four mural colours — azure blue, vermilion, white and the deep teal of the north. The owner's feet carry
 * a turning ring in the current guardian's colour, the next one's motes rise from it, and the two names float over the
 * head. The dragon is a sinuous chain of glassy blue scales, the bird spreads two vermilion wings and leaves a path of
 * glowing feathers, the tiger is a white body with black stripes that leaps and claws, the tortoise wraps both swapped
 * players in a teal shell. The barrier paints its four triangles on the ground in the four colours, with the guardians'
 * names standing over each side and gliding on with every turn.
 */
public final class FourGodsAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("fourgods", "사방신", Material.COMPASS)
            .color(TextColor.color(0x2E8BD8))
            .difficulty(3)
            .passive("사신의 순환",
                    "수호신 자세가 액티브를 쓸 때마다 청룡 → 주작 → 백호 → 현무 순서(동 → 남 → 서 → 북)로 돌아갑니다. "
                            + "지금과 다음 수호신이 머리 위 이름과 발밑 기운의 색으로 모두에게 보입니다. 청룡: 공격 속도 "
                            + "+{dragon_attack_speed}%. 주작: 이동 속도 +{bird_speed}%, 낙하 피해 없음. 백호: 근접 피해 "
                            + "+{tiger_melee}%. 현무: 받는 피해 -{tortoise_guard}%.")
            .active("사신 소환",
                    "지금의 수호신을 부른 뒤 다음 수호신으로 넘어갑니다. 청룡: 용의 혼이 앞 {dragon_length}블록을 "
                            + "물결치며 지나가(벽에 막힙니다) 닿은 적에게 {dragon_damage} 피해를 주고 약 {dragon_drag}블록 "
                            + "끌고 갑니다. 주작: 바라보는 쪽으로 {bird_distance}블록 낮게 날아가며 {bird_trail}초간 불깃털 "
                            + "길(폭 {bird_width}블록)을 남깁니다. 길 위의 나와 아군은 이동 속도 +{trail_speed}%·점프 높이 "
                            + "+{trail_jump}블록, 적은 0.5초마다 {trail_damage} 피해(불 아님)를 받습니다. 백호: "
                            + "{tiger_range}블록 안의 조준한 적에게 호랑이 혼이 덮쳐 {tiger_damage} 피해를 주고 "
                            + "{tiger_stagger}초간 휘청이게 합니다(이동·공격 속도 {stagger_slow}% 감소). 현무: "
                            + "{tortoise_range}블록 안의 가장 가까운 아군(없으면 적)과 자리를 맞바꿉니다. 아군이면 둘 다, "
                            + "적이면 나만 {tortoise_shell}초간 저항 II(받는 피해 40% 감소)를 얻고, 그 적은 "
                            + "{tortoise_slow}초간 구속 II에 걸립니다.",
                    6)
            .ultimate("사신 결계",
                    "사방에 네 수호신을 세워 {ult_duration}초간 반경 {ult_radius}블록의 정사각 결계를 칩니다. 동·남·서·북 "
                            + "네 구역의 힘이 {ult_rotate}초마다 시계 방향으로 한 칸씩 돌아가며(바닥 색이 함께 돕니다) 그 구역의 "
                            + "적에게 듭니다. 청룡: 0.5초마다 {ult_dragon_damage} 피해 + 가운데로 끌림. 주작: 0.5초마다 "
                            + "{ult_bird_damage} 피해 + 살짝 띄움. 백호: 초당 {ult_tiger_dps} 피해 + 이동 속도 "
                            + "-{ult_tiger_slow}%. 현무: 점프 불가 + 이동 속도 -{ult_tortoise_slow}%. 결계 한가운데"
                            + "({ult_center}블록 이내)의 나는 네 자세의 힘을 모두 받습니다.")
            .ultimateNotStandalone()
            .param("dragon_attack_speed", 15)
            .param("bird_speed", 15)
            .param("tiger_melee", 15)
            .param("tortoise_guard", 15)
            .param("dragon_length", 12)
            .param("dragon_damage", 3.5)
            .param("dragon_drag", 2)
            .param("dragon_width", 1.4)
            .param("bird_distance", 10)
            .param("bird_trail", 4)
            .param("bird_width", 2)
            .param("trail_speed", 25)
            .param("trail_jump", 1)
            .param("trail_damage", 0.5)
            .param("tiger_range", 8)
            .param("tiger_damage", 5)
            .param("tiger_stagger", 0.5)
            .param("stagger_slow", 50)
            .param("tortoise_range", 8)
            .param("tortoise_shell", 2)
            .param("tortoise_slow", 1)
            .param("ult_radius", 9)
            .param("ult_duration", 6)
            .param("ult_rotate", 1.5)
            .param("ult_dragon_damage", 0.5)
            .param("ult_bird_damage", 0.5)
            .param("ult_tiger_dps", 1.5)
            .param("ult_tiger_slow", 25)
            .param("ult_tortoise_slow", 30)
            .param("ult_center", 2.5)
            .build();

    // ------------------------------------------------------------------ the four guardians

    /** The guardians in the order the stance turns: east, south, west, north (clockwise seen from above). */
    public enum Guardian {
        DRAGON("청룡", "靑龍", 0x2E8BD8, Vfx.Palette.of(0xBFE6FF, 0x2E8BD8, 0x123E6B)),
        BIRD("주작", "朱雀", 0xE0442B, Vfx.Palette.of(0xFFD39A, 0xE0442B, 0x6E1409)),
        TIGER("백호", "白虎", 0xF2F2EE, Vfx.Palette.of(0xFFFFFF, 0xF2F2EE, 0x3A3A40)),
        TORTOISE("현무", "玄武", 0x3FA796, Vfx.Palette.of(0x9FE6D6, 0x2F7D72, 0x1F4F4A));

        final String korean;
        final String hanja;
        /** The text colour (the tortoise's mural teal, lightened so it reads on dark ground). */
        final String tag;
        final Vfx.Palette palette;
        final Particle.DustOptions dust;
        final Particle.DustOptions small;
        final Particle.DustOptions floor;

        Guardian(String korean, String hanja, int rgb, Vfx.Palette palette) {
            this.korean = korean;
            this.hanja = hanja;
            this.tag = String.format("#%06X", rgb);
            this.palette = palette;
            this.dust = palette.dust(1.0f);
            this.small = palette.dust(0.6f);
            this.floor = palette.dust(1.5f);
        }

        Guardian next() {
            return values()[(ordinal() + 1) % 4];
        }

        String colored(String text) {
            return "<" + tag + ">" + text + "</" + tag + ">";
        }
    }

    private static final double TAU = Math.PI * 2;
    private static final Color GOLD = Color.fromRGB(0xF2C14E);
    private static final Particle.DustOptions GOLD_DUST = new Particle.DustOptions(GOLD, 0.9f);
    private static final Particle.DustOptions WHITE_DUST = new Particle.DustOptions(Color.WHITE, 0.7f);
    private static final Particle.DustOptions STRIPE_DUST = new Particle.DustOptions(Color.fromRGB(0x1A1A1E), 0.8f);

    private static final Material SCALE_BLOCK = Material.LIGHT_BLUE_STAINED_GLASS;
    private static final Material DRAGON_HEAD_BLOCK = Material.CYAN_STAINED_GLASS;
    private static final Material WING_BLOCK = Material.RED_STAINED_GLASS;
    private static final Material FEATHER_BLOCK = Material.ORANGE_STAINED_GLASS;
    private static final Material TIGER_FUR = Material.WHITE_WOOL;
    private static final Material TIGER_STRIPE = Material.BLACK_WOOL;
    private static final Material SHELL_BLOCK = Material.CYAN_STAINED_GLASS;
    private static final Material SHELL_TOP = Material.GREEN_STAINED_GLASS;
    private static final Material POST_BLOCK = Material.GOLD_BLOCK;

    /** Display entities this ability keeps alive at once (optional pieces are skipped beyond it). */
    private static final int MAX_DISPLAYS = 24;
    /** Ticks between two passive feedbacks (claw marks, shell flashes), so a flurry does not spam effects. */
    private static final long FEEDBACK_COOLDOWN = 10;
    /** Followers of a moving player glide this many ticks, like a client's interpolation of the player. */
    private static final int FOLLOW_GLIDE = 3;
    private static final double LABEL_ABOVE = 0.85;

    // the dragon
    private static final double DRAGON_SPEED = 1.0;
    private static final int DRAGON_SEGMENTS = 6;
    private static final double SEGMENT_GAP = 0.55;
    private static final double WAVE_FREQUENCY = 1.05;
    private static final double WAVE_AMPLITUDE = 0.9;
    /** Horizontal push per block of drag: an enemy flung at this speed slides about one block per {@code 1/x}. */
    private static final double DRAG_PER_BLOCK = 0.17;
    private static final double DRAG_LIFT = 0.24;
    private static final double MAX_PUSH = 1.4;

    // the bird
    private static final double BIRD_SPEED = 1.0;
    private static final double BIRD_POP = 0.3;
    private static final double BIRD_HOVER = 0.03;
    private static final double BIRD_GLIDE = 0.15;
    private static final long TRAIL_PULSE = 10;
    /** Trail buffs last a little longer than a pulse, so standing on the path keeps them without a gap. */
    private static final long TRAIL_BUFF_TICKS = 14;
    private static final int TRAIL_FEATHERS = 6;
    private static final double TRAIL_BELOW = 2.0;
    private static final double TRAIL_ABOVE = 3.0;
    /** The vanilla jump (base jump strength) rises about this high. */
    private static final double VANILLA_JUMP = 1.25;

    // the tiger
    private static final long POUNCE_TICKS = 6;
    private static final double POUNCE_ARC = 1.6;

    // the tortoise
    private static final int RESISTANCE_II = 1;
    private static final int SLOWNESS_II = 1;
    private static final long SHELL_TICKS = 16;

    // the barrier
    private static final long ULT_PULSE = 10;
    private static final double ULT_HEIGHT = 4;
    private static final double PULL_SPEED = 0.42;
    private static final double LIFT_SPEED = 0.36;
    private static final long ULT_MOD_TICKS = 14;
    private static final double GLYPH_HEIGHT = 2.6;
    private static final int GLYPH_GLIDE = 8;
    private static final double GROUND_PROBE = 6;

    // ------------------------------------------------------------------ state

    private Guardian stance = Guardian.DRAGON;
    private final List<Display> displays = new ArrayList<>();
    private final LandingGuard landing = new LandingGuard();
    private @Nullable TextDisplay label;
    private @Nullable NamespacedKey attackSpeedKey;
    private @Nullable NamespacedKey moveSpeedKey;
    private boolean granted;
    /** The running barrier (the owner in its middle holds every stance). */
    private @Nullable Barrier barrier;
    private long lastFeedback = Long.MIN_VALUE / 2; // "/ 2": now - this must not overflow

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onGrant() {
        granted = true;
        stance = Guardian.DRAGON;
        refreshBonuses();
        Player owner = player();
        owner.sendActionBar(Text.mm(stanceLine("수호신 자세")));
    }

    @Override
    public void onRevoke() {
        granted = false;
        barrier = null;
        attackSpeedKey = null;
        moveSpeedKey = null;
        label = null;
        landing.disarm();
        displays.clear();
    }

    // ================================================================== passive: the turning of the four

    /** The guardians whose bonus the owner holds now: its stance, and all four in the middle of its barrier. */
    private Set<Guardian> held() {
        Set<Guardian> held = EnumSet.of(stance);
        Barrier running = barrier;
        if (running != null && running.ownerInCenter) {
            held.addAll(EnumSet.allOf(Guardian.class));
        }
        return held;
    }

    /** Applies the attribute side of the held bonuses (attack speed, movement speed); the rest lives in the hooks. */
    private void refreshBonuses() {
        if (!granted) {
            return;
        }
        Player owner = player();
        Set<Guardian> held = held();
        attackSpeedKey = swap(owner, attackSpeedKey, held.contains(Guardian.DRAGON), Attribute.ATTACK_SPEED,
                params().num("dragon_attack_speed"));
        moveSpeedKey = swap(owner, moveSpeedKey, held.contains(Guardian.BIRD), Attribute.MOVEMENT_SPEED,
                params().num("bird_speed"));
    }

    private @Nullable NamespacedKey swap(Player owner, @Nullable NamespacedKey current, boolean wanted,
                                         Attribute attribute, double percent) {
        if (wanted && current == null && percent > 0) {
            return ctx().addModifier(owner, owner, attribute, percent / 100.0, Operation.MULTIPLY_SCALAR_1, 0);
        }
        if (!wanted && current != null) {
            ctx().removeModifier(owner, current);
            return null;
        }
        return current;
    }

    /** The guardian of the owner's stance now (its next active summons it). */
    public Guardian stance() {
        return stance;
    }

    private boolean holds(Guardian guardian) {
        return held().contains(guardian);
    }

    @Override
    public void onTick(long tick) {
        Player owner = player();
        landing.update(owner, tick);
        followLabel(owner);
        if (tick % 2 == 0) {
            // The current guardian's ring turns at the feet; the next one's motes rise from it.
            double angle = tick * 0.21;
            Location feet = owner.getLocation().add(0, 0.12, 0);
            for (int i = 0; i < 2; i++) {
                double a = angle + i * Math.PI;
                Vfx.dot(feet.clone().add(Math.cos(a) * 0.55, 0, Math.sin(a) * 0.55), Particle.DUST, stance.dust,
                        false);
            }
        }
        if (tick % 8 == 4) {
            Location mote = Vfx.scatter(owner.getLocation().add(0, 0.2, 0), 0.45);
            Vfx.dot(mote, Particle.DUST, stance.next().small, false);
        }
        if (tick % 14 == 0) {
            Vfx.dot(Vfx.scatter(owner.getLocation().add(0, 1.0, 0), 0.4), accent(stance), null, false);
        }
    }

    /** The current and the next guardian over the owner's head, for everyone. */
    private void followLabel(Player owner) {
        Location at = labelSpot(owner);
        TextDisplay shown = label;
        if (shown == null || !shown.isValid()) {
            if (!reserve(1)) {
                return;
            }
            shown = Displays.text(ctx(), owner, at, labelText(), 0.75f, 0);
            shown.setTeleportDuration(FOLLOW_GLIDE);
            shown.setViewRange(1.0f);
            keep(shown);
            label = shown;
            return;
        }
        Displays.moveTo(shown, at);
    }

    private static Location labelSpot(Player owner) {
        return Displays.unrotated(owner.getLocation().add(0, owner.getHeight() + LABEL_ABOVE, 0));
    }

    private Component labelText() {
        Guardian next = stance.next();
        return Text.mm("<b>" + stance.colored(stance.hanja + " " + stance.korean) + "</b> <gray>›</gray> "
                + next.colored(next.korean));
    }

    private String stanceLine(String prefix) {
        Guardian next = stance.next();
        return "<gray>" + prefix + ":</gray> <b>" + stance.colored(stance.korean) + "</b> <dark_gray>› 다음</dark_gray> "
                + next.colored(next.korean);
    }

    /** The stance moves on to the next guardian, with a flourish everyone sees. */
    private void advance(Player owner) {
        stance = stance.next();
        refreshBonuses();
        TextDisplay shown = label;
        if (shown != null && shown.isValid()) {
            shown.text(labelText());
        }
        Location head = owner.getLocation().add(0, owner.getHeight() + 0.2, 0);
        Vfx.ring(ctx(), owner.getLocation().add(0, 0.1, 0), 0.9, 16, 0, Particle.DUST_COLOR_TRANSITION,
                stance.palette.glow(1.0f), false);
        Vfx.cloud(ctx(), head, accent(stance), 6, 0.3, 0.02, null, false);
        if (reserve(1)) {
            TextDisplay glyph = keep(Displays.text(ctx(), owner, head.clone().add(0, 0.6, 0),
                    Text.mm("<b>" + stance.colored(stance.hanja) + "</b>"), 0.4f, 14));
            Displays.animateNextTick(ctx(), owner, glyph, Displays.centered(0, 0.5f, 0, 1.3f, 1.3f, 1.3f, 0, 0, 0), 5);
            ctx().runLater(owner, 8, () -> {
                if (glyph.isValid()) {
                    Displays.shrinkAway(ctx(), owner, glyph, 5);
                }
            });
        }
        cry(head, stance, 0.6f);
        owner.sendActionBar(Text.mm(stanceLine("수호신이 바뀌었습니다")));
    }

    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled() || !isOwnMelee(event) || !holds(Guardian.TIGER)) {
            return;
        }
        double bonus = Math.max(0, params().num("tiger_melee")) / 100.0;
        if (bonus > 0) {
            event.setDamage(event.getDamage() * (1 + bonus));
        }
    }

    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (!isOwnMelee(event) || !holds(Guardian.TIGER) || !feedbackReady()) {
            return;
        }
        // The tiger's claws: three white scratches across the victim.
        Player owner = player();
        if (isHidden(owner, victim)) {
            return;
        }
        Location body = Fx.body(victim);
        Vector side = Motion.horizontal(body.toVector().subtract(owner.getLocation().toVector()))
                .crossProduct(new Vector(0, 1, 0));
        if (side.lengthSquared() < 1.0E-6) {
            side = new Vector(1, 0, 0);
        }
        side.normalize();
        for (int i = -1; i <= 1; i++) {
            Location from = body.clone().add(side.clone().multiply(i * 0.18 - 0.25)).add(0, 0.35, 0);
            Location to = body.clone().add(side.clone().multiply(i * 0.18 + 0.25)).add(0, -0.3, 0);
            Vfx.line(ctx(), from, to, 6, Particle.DUST, WHITE_DUST, false);
        }
        Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.6f, 1.3f + Fx.jitter(0.1f));
    }

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled()) {
            return;
        }
        long now = ctx().currentTick();
        if (landing.absorb(event, now)) {
            return;
        }
        if (Combat.isFall(event) && holds(Guardian.BIRD)) {
            // The vermilion bird never falls hard.
            event.setCancelled(true);
            Location feet = player().getLocation().add(0, 0.2, 0);
            Vfx.cloud(ctx(), feet, Particle.DUST, 8, 0.4, 0, Guardian.BIRD.dust, false);
            Fx.sound(feet, Sound.ENTITY_PARROT_FLY, 0.6f, 1.2f + Fx.jitter(0.1f));
            return;
        }
        if (!holds(Guardian.TORTOISE) || event.getDamage() <= 0 || bypasses(event)) {
            return;
        }
        double guard = Math.clamp(params().num("tortoise_guard") / 100.0, 0.0, 1.0);
        if (guard <= 0) {
            return;
        }
        event.setDamage(event.getDamage() * (1 - guard));
        if (feedbackReady() && event.getDamage() >= 1) {
            // A hexagonal teal shell flashes around the owner.
            Player owner = player();
            Location chest = owner.getLocation().add(0, owner.getHeight() * 0.55, 0);
            for (int i = 0; i < 6; i++) {
                double a = i * TAU / 6 + now * 0.1;
                double b = a + TAU / 6;
                Location from = chest.clone().add(Math.cos(a) * 0.65, 0, Math.sin(a) * 0.65);
                Location to = chest.clone().add(Math.cos(b) * 0.65, 0, Math.sin(b) * 0.65);
                Vfx.line(ctx(), from, to, 4, Particle.DUST, Guardian.TORTOISE.dust, false);
            }
            Fx.sound(chest, Sound.ITEM_ARMOR_EQUIP_TURTLE, 0.5f, 1.4f + Fx.jitter(0.1f));
        }
    }

    private boolean feedbackReady() {
        long now = ctx().currentTick();
        if (now - lastFeedback < FEEDBACK_COOLDOWN) {
            return false;
        }
        lastFeedback = now;
        return true;
    }

    private boolean isOwnMelee(EntityDamageByEntityEvent event) {
        return !ctx().isAbilityDamage() && player().equals(event.getDamager())
                && (event.getCause() == DamageCause.ENTITY_ATTACK || event.getCause() == DamageCause.ENTITY_SWEEP_ATTACK);
    }

    private static boolean bypasses(EntityDamageEvent event) {
        DamageType type = event.getDamageSource().getDamageType();
        return DamageTypeTags.BYPASSES_INVULNERABILITY != null
                && DamageTypeTags.BYPASSES_INVULNERABILITY.isTagged(type);
    }

    // ================================================================== active: summon the guardian

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        ActivationResult result = switch (stance) {
            case DRAGON -> dragon(owner);
            case BIRD -> bird(owner);
            case TIGER -> tiger(owner);
            case TORTOISE -> tortoise(owner);
        };
        if (result.succeeded()) {
            advance(owner);
        }
        return result;
    }

    // ------------------------------------------------------------------ 청룡: the dragon wave

    private ActivationResult dragon(Player owner) {
        Vector dir = Motion.horizontalFacing(owner.getLocation());
        Location start = owner.getLocation().add(0, 1.0, 0).add(dir.clone().multiply(0.8));
        double length = Math.max(1, params().num("dragon_length"));
        RayTraceResult wall = start.getWorld().rayTraceBlocks(start, dir, length, FluidCollisionMode.NEVER, true);
        if (wall != null) {
            length = Math.max(0.5, wall.getHitPosition().distance(start.toVector()) - 0.3);
        }
        length = Math.max(0.5, Spots.arenaReach(ctx(), start, dir, length));
        new DragonWave(owner, Displays.unrotated(start), dir, length).start();
        Fx.sound(start, Sound.ENTITY_ENDER_DRAGON_FLAP, 0.9f, 1.5f + Fx.jitter(0.1f));
        Fx.sound(start, Sound.ENTITY_DOLPHIN_SPLASH, 0.8f, 1.2f + Fx.jitter(0.1f));
        Fx.sound(start, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.8f, 1.5f);
        Vfx.burst(ctx(), start, Guardian.DRAGON.palette, 0.5, Particle.GLOW, false);
        return ActivationResult.success();
    }

    /**
     * The azure dragon's spirit: a chain of glassy scales swimming forward along a wave, every scale following the head
     * along the same path. Whoever the head touches is hurt once and dragged along.
     */
    private final class DragonWave implements LongPredicate {

        private final Player owner;
        private final Location start;
        private final Vector dir;
        private final Vector side;
        private final double length;
        private final float yaw;
        private final List<BlockDisplay> body = new ArrayList<>();
        private final Set<UUID> struck = new HashSet<>();
        private double head;
        private boolean done;

        DragonWave(Player owner, Location start, Vector dir, double length) {
            this.owner = owner;
            this.start = start;
            this.dir = dir;
            this.side = new Vector(-dir.getZ(), 0, dir.getX());
            this.length = length;
            this.yaw = yawOf(dir);
        }

        void start() {
            int parts = reserve(1 + DRAGON_SEGMENTS) ? 1 + DRAGON_SEGMENTS : reserve(3) ? 3 : 0;
            long life = (long) Math.ceil(length / DRAGON_SPEED) + DRAGON_SEGMENTS + 20;
            for (int i = 0; i < parts; i++) {
                Material type = i == 0 ? DRAGON_HEAD_BLOCK : SCALE_BLOCK;
                BlockDisplay part = keep(Displays.block(ctx(), owner, start, type.createBlockData(),
                        segmentPose(i, 0, 0.05f), life));
                part.setTeleportDuration(1);
                part.setViewRange(1.5f);
                body.add(part);
            }
            if (!body.isEmpty()) {
                Displays.outline(body.getFirst(), Guardian.DRAGON.palette.body());
            }
            long runs = (long) Math.ceil(length / DRAGON_SPEED) + DRAGON_SEGMENTS + 2;
            Loop.repeat(ctx(), owner, 1, 1, runs, this, this::end);
        }

        /** Where the wave is {@code tau} blocks along: swaying sideways and bobbing, calm at the start. */
        private Location at(double tau) {
            double t = Math.clamp(tau, 0, length);
            double settle = Math.min(1, t / 1.5);
            double wave = Math.sin(t * WAVE_FREQUENCY) * WAVE_AMPLITUDE * settle;
            double bob = Math.sin(t * WAVE_FREQUENCY * 0.5 + 1) * 0.22 * settle;
            return start.clone().add(dir.clone().multiply(t)).add(side.clone().multiply(wave)).add(0, bob, 0);
        }

        /** The scale {@code index} (0 = head), turned along the wave and rolling as it swims. */
        private Transformation segmentPose(int index, long tick, float grow) {
            float size = index == 0 ? 0.62f : (float) (0.5 - index * 0.045);
            float s = size * grow;
            return Displays.transform(0, 0, 0, s, s * (index == 0 ? 0.8f : 1f), s * (index == 0 ? 1.3f : 1f),
                    yaw + (float) Math.toDegrees(Math.cos(head - index * SEGMENT_GAP) * 0.6), 0,
                    index == 0 ? 0 : tick * 24f + index * 35f);
        }

        @Override
        public boolean test(long tick) {
            if (done) {
                return false;
            }
            head = Math.min(length, head + DRAGON_SPEED);
            for (int i = 0; i < body.size(); i++) {
                BlockDisplay part = body.get(i);
                if (!part.isValid()) {
                    continue;
                }
                double tau = head - i * SEGMENT_GAP * (body.size() < DRAGON_SEGMENTS ? 2.5 : 1);
                Displays.moveTo(part, at(Math.max(0, tau)));
                Displays.animate(part, segmentPose(i, tick, 1f), 1);
            }
            Location tip = at(head);
            Vfx.dot(tip, Particle.DUST_COLOR_TRANSITION, Guardian.DRAGON.palette.glow(1.2f), false);
            Vfx.trail(tip, at(Math.max(0, head - 2.5)), Guardian.DRAGON.palette.body(), 8, false);
            if (tick % 2 == 0) {
                Vfx.dot(Vfx.scatter(tip, 0.4), Particle.GLOW, null, false);
                Vfx.dot(at(Math.max(0, head - 1.5)).add(0, -0.2, 0), Particle.SPLASH, null, false);
            }
            if (tick % 3 == 0) {
                Fx.sound(tip, Sound.ENTITY_PHANTOM_SWOOP, 0.35f, 1.6f + Fx.jitter(0.1f));
            }
            double width = Math.max(0.3, params().num("dragon_width"));
            for (LivingEntity enemy : ctx().enemiesNear(owner, tip, width)) {
                if (struck.add(enemy.getUniqueId())) {
                    strike(enemy);
                }
            }
            if (head >= length && tick >= (long) Math.ceil(length / DRAGON_SPEED) + DRAGON_SEGMENTS / 2) {
                end();
                return false;
            }
            return true;
        }

        private void strike(LivingEntity enemy) {
            Combat.hit(ctx(), owner, enemy, params().num("dragon_damage"));
            double drag = Math.max(0, params().num("dragon_drag"));
            if (drag > 0 && Combat.isTargetable(ctx(), owner, enemy)) {
                Motion.push(enemy, dir.clone().multiply(drag * DRAG_PER_BLOCK).setY(DRAG_LIFT), MAX_PUSH);
            }
            if (!isHidden(owner, enemy)) {
                Location body = Fx.body(enemy);
                Vfx.burst(ctx(), body, Guardian.DRAGON.palette, 0.6, Particle.GLOW, false);
                Vfx.cloud(ctx(), body, Particle.SPLASH, 10, 0.4, 0.1, null, false);
            }
            Fx.sound(Fx.body(enemy), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.9f, 1.2f + Fx.jitter(0.1f));
            Fx.sound(Fx.body(enemy), Sound.ENTITY_GENERIC_SPLASH, 0.6f, 1.6f);
        }

        void end() {
            if (done) {
                return;
            }
            done = true;
            Location tip = at(head);
            Vfx.burst(ctx(), tip, Guardian.DRAGON.palette, 0.8, Particle.SPLASH, false);
            Vfx.cloud(ctx(), tip, Particle.BUBBLE_POP, 12, 0.5, 0.05, null, false);
            Fx.sound(tip, Sound.ENTITY_DOLPHIN_SPLASH, 0.7f, 0.9f);
            for (BlockDisplay part : body) {
                if (part.isValid()) {
                    Displays.moveTo(part, part.getLocation().add(0, 0.6, 0));
                    Displays.shrinkAway(ctx(), owner, part, 6);
                }
            }
        }
    }

    // ------------------------------------------------------------------ 주작: the vermilion flight

    private ActivationResult bird(Player owner) {
        if (owner.isInsideVehicle()) {
            return ActivationResult.fail("탈것에 탄 채로는 날 수 없습니다");
        }
        Vector dir = Motion.horizontalFacing(owner.getLocation());
        Location start = owner.getLocation();
        double distance = Spots.arenaReach(ctx(), start, dir, Math.max(1, params().num("bird_distance")));
        if (distance < 1) {
            return ActivationResult.fail("자기장 밖으로는 날 수 없습니다");
        }
        Body body = Body.standing(owner);
        if (blocked(start.getWorld(), start, dir, Math.min(1.0, distance), body)) {
            return ActivationResult.fail("앞이 막혀 날 수 없습니다");
        }
        Flight flight = new Flight(owner, start, dir, distance);
        flight.start();
        return ActivationResult.success();
    }

    /** True if the body moved {@code ahead} blocks along {@code dir} from {@code feet} would run into a wall. */
    private static boolean blocked(World world, Location feet, Vector dir, double ahead, Body body) {
        Location next = feet.clone().add(dir.clone().multiply(ahead));
        double half = body.width() / 2 - 0.02;
        // The probe skips the feet, so a step or a slab is not a wall.
        BoundingBox probe = new BoundingBox(next.getX() - half, next.getY() + 0.65, next.getZ() - half,
                next.getX() + half, next.getY() + body.height() - 0.05, next.getZ() + half);
        return world.hasCollisionsIn(probe);
    }

    /**
     * The vermilion bird's flight: the owner skims low along the ground, steered by the steps sent to it (its client
     * runs them, the server hears where it is a moment later), and lays a path of burning feathers behind it.
     */
    private final class Flight implements LongPredicate {

        private final Player owner;
        private final Location start;
        private final Vector dir;
        private final double distance;
        private final float yaw;
        private final List<BlockDisplay> wings = new ArrayList<>();
        private double sent;
        private boolean landed;

        Flight(Player owner, Location start, Vector dir, double distance) {
            this.owner = owner;
            this.start = start.clone();
            this.dir = dir;
            this.distance = distance;
            this.yaw = yawOf(dir);
        }

        void start() {
            landing.arm(ctx().currentTick(), (long) Math.ceil(distance / BIRD_SPEED) + 60);
            owner.setFallDistance(0);
            if (reserve(2)) {
                for (int side = -1; side <= 1; side += 2) {
                    BlockDisplay wing = keep(Displays.block(ctx(), owner, wingSpot(), WING_BLOCK.createBlockData(),
                            wingPose(side, 0.2f, 0), (long) Math.ceil(distance / BIRD_SPEED) + 12));
                    wing.setTeleportDuration(1);
                    Displays.outline(wing, Guardian.BIRD.palette.body());
                    wings.add(wing);
                }
            }
            Fx.sound(start, Sound.ENTITY_PHANTOM_FLAP, 1.0f, 1.4f + Fx.jitter(0.1f));
            Fx.sound(start, Sound.ITEM_FIRECHARGE_USE, 0.7f, 1.3f + Fx.jitter(0.1f));
            Fx.sound(start, Sound.ENTITY_PARROT_FLY, 0.9f, 0.9f);
            Vfx.burst(ctx(), start.clone().add(0, 1, 0), Guardian.BIRD.palette, 0.7, Particle.SMALL_FLAME, false);
            long runs = (long) Math.ceil(distance / BIRD_SPEED) + 1;
            Loop.repeat(ctx(), owner, 0, 1, runs, this, this::land);
        }

        private Location wingSpot() {
            return Displays.unrotated(owner.getLocation().add(0, owner.getHeight() * 0.72, 0)
                    .subtract(dir.clone().multiply(0.18)));
        }

        /** One wing, spread to the side and beating: {@code side} -1 left, 1 right. */
        private Transformation wingPose(int side, float spread, double beat) {
            float roll = (float) (side * (12 + Math.sin(beat) * 28));
            return Displays.transform(side * (0.2f + 0.55f * spread), 0.05f, -0.1f, 1.1f * spread + 0.05f, 0.04f,
                    0.55f, yaw, 0, roll);
        }

        @Override
        public boolean test(long tick) {
            if (!owner.getWorld().equals(start.getWorld())) {
                return false;
            }
            double remaining = distance - sent;
            if (remaining <= 0.05) {
                return false;
            }
            double step = Math.min(BIRD_SPEED, remaining);
            Location planned = start.clone().add(dir.clone().multiply(sent));
            planned.setY(owner.getLocation().getY());
            if (blocked(owner.getWorld(), planned, dir, step, Body.standing(owner))) {
                return false;
            }
            owner.setVelocity(Motion.clamp(dir.clone().multiply(step).setY(tick == 0 ? BIRD_POP : BIRD_HOVER), 1.5));
            owner.setFallDistance(0);
            sent += step;
            for (int i = 0; i < wings.size(); i++) {
                BlockDisplay wing = wings.get(i);
                Displays.moveTo(wing, wingSpot());
                Displays.animate(wing, wingPose(i == 0 ? -1 : 1, 1f, tick * 1.3), 1);
            }
            Location body = owner.getLocation().add(0, 0.9, 0);
            Vfx.dot(body, Particle.FLAME, null, false);
            Vfx.trail(body, body.clone().subtract(dir.clone().multiply(2)), Guardian.BIRD.palette.body(), 6, false);
            Vfx.dot(Vfx.scatter(body, 0.4), Particle.DUST_COLOR_TRANSITION, Guardian.BIRD.palette.glow(1.0f), false);
            if (tick % 3 == 0) {
                Fx.sound(body, Sound.ENTITY_PHANTOM_FLAP, 0.5f, 1.6f + Fx.jitter(0.1f));
            }
            return true;
        }

        void land() {
            if (landed) {
                return;
            }
            landed = true;
            owner.setVelocity(dir.clone().multiply(BIRD_GLIDE));
            owner.setFallDistance(0);
            for (BlockDisplay wing : wings) {
                if (wing.isValid()) {
                    Displays.shrinkAway(ctx(), owner, wing, 5);
                }
            }
            if (sent > 0.5) {
                new FeatherPath(owner, start, dir, sent).start();
            }
            Location feet = owner.getLocation().add(0, 0.2, 0);
            Vfx.ring(ctx(), feet, 1.0, 14, 0, Particle.DUST_COLOR_TRANSITION, Guardian.BIRD.palette.glow(1.0f), false);
            Fx.sound(feet, Sound.ENTITY_PARROT_FLY, 0.7f, 1.5f);
        }
    }

    /** The path of burning feathers the bird leaves: quick for the owner and allies, scorching for enemies. */
    private final class FeatherPath implements LongPredicate {

        private final Player owner;
        private final Location start;
        private final Vector dir;
        private final double length;
        private final double halfWidth;
        private final Map<UUID, List<NamespacedKey>> buffs = new HashMap<>();
        private final List<BlockDisplay> feathers = new ArrayList<>();
        private final World world;

        FeatherPath(Player owner, Location start, Vector dir, double length) {
            this.owner = owner;
            this.start = start.clone();
            this.dir = dir;
            this.length = length;
            this.halfWidth = Math.max(0.25, params().num("bird_width") / 2);
            this.world = start.getWorld();
        }

        void start() {
            long life = Math.max(1, params().ticks("bird_trail"));
            int count = Math.min(TRAIL_FEATHERS, (int) Math.ceil(length / 1.6));
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < count && reserve(1); i++) {
                double along = (i + 0.5) * length / count;
                Location at = ground(start.clone().add(dir.clone().multiply(along))
                        .add(new Vector(-dir.getZ(), 0, dir.getX()).multiply(random.nextDouble(-halfWidth, halfWidth)
                                * 0.7)));
                at.add(0, 0.03, 0);
                float turn = yawOf(dir) + random.nextFloat(-50f, 50f);
                BlockDisplay feather = keep(Displays.block(ctx(), owner, at, FEATHER_BLOCK.createBlockData(),
                        Displays.transform(0, 0.02f, 0, 0.02f, 0.02f, 0.02f, turn, 0, 0), life + 8));
                Displays.animateNextTick(ctx(), owner, feather,
                        Displays.transform(0, 0.02f, 0, 0.16f, 0.025f, 0.62f, turn, 0, 0), 4);
                feathers.add(feather);
            }
            Loop.repeat(ctx(), owner, 0, 1, life, this, this::end);
        }

        private Location ground(Location at) {
            Location probe = at.clone();
            probe.setY(start.getY() + 1.5);
            Spots.snapToGround(world, probe, 4);
            return Displays.unrotated(probe);
        }

        @Override
        public boolean test(long tick) {
            if (tick % 2 == 0) {
                for (int i = 0; i < Vfx.n(ctx(), 4); i++) {
                    Location at = ground(start.clone().add(dir.clone().multiply(ThreadLocalRandom.current()
                            .nextDouble(length))).add(new Vector(-dir.getZ(), 0, dir.getX())
                            .multiply(ThreadLocalRandom.current().nextDouble(-halfWidth, halfWidth)))).add(0, 0.1, 0);
                    Vfx.dot(at, i % 2 == 0 ? Particle.DUST_COLOR_TRANSITION : Particle.SMALL_FLAME,
                            i % 2 == 0 ? Guardian.BIRD.palette.glow(1.1f) : null, false);
                }
            }
            if (tick % 7 == 0) {
                Location at = ground(start.clone().add(dir.clone().multiply(ThreadLocalRandom.current()
                        .nextDouble(length)))).add(0, 0.2, 0);
                Vfx.dot(at, Particle.FLAME, null, false);
            }
            if (tick % 20 == 10) {
                Fx.sound(start.clone().add(dir.clone().multiply(length / 2)), Sound.BLOCK_FIRE_AMBIENT, 0.5f, 1.4f);
            }
            if (tick % TRAIL_PULSE == 0) {
                pulse();
            }
            return true;
        }

        private boolean onPath(LivingEntity entity) {
            Location feet = entity.getLocation();
            if (!feet.getWorld().equals(world) || feet.getY() < start.getY() - TRAIL_BELOW
                    || feet.getY() > start.getY() + TRAIL_ABOVE) {
                return false;
            }
            Vector rel = feet.toVector().subtract(start.toVector()).setY(0);
            double along = rel.dot(dir);
            double reach = halfWidth + entity.getWidth() / 2;
            if (along < -reach || along > length + reach) {
                return false;
            }
            double across = Math.abs(rel.dot(new Vector(-dir.getZ(), 0, dir.getX())));
            return across <= reach;
        }

        private void pulse() {
            List<Player> friends = new ArrayList<>(ctx().alliesNear(owner, start, length + 4));
            friends.add(owner);
            for (Player friend : friends) {
                if (ctx().isAlive(friend) && onPath(friend)) {
                    buff(friend);
                }
            }
            double damage = params().num("trail_damage");
            for (LivingEntity enemy : ctx().enemiesNear(owner, start.clone().add(dir.clone().multiply(length / 2)),
                    length / 2 + halfWidth + 2)) {
                if (onPath(enemy)) {
                    Combat.hit(ctx(), owner, enemy, damage);
                    if (!isHidden(owner, enemy)) {
                        Vfx.cloud(ctx(), enemy.getLocation().add(0, 0.3, 0), Particle.SMALL_FLAME, 4, 0.25, 0.02,
                                null, false);
                    }
                }
            }
        }

        /** Swift feet and a high jump on the feathers, refreshed every pulse (never stacked). */
        private void buff(Player friend) {
            List<NamespacedKey> old = buffs.remove(friend.getUniqueId());
            if (old != null) {
                for (NamespacedKey key : old) {
                    ctx().removeModifier(friend, key);
                }
            }
            List<NamespacedKey> keys = new ArrayList<>();
            double speed = Math.max(0, params().num("trail_speed")) / 100.0;
            if (speed > 0) {
                keys.add(ctx().addModifier(owner, friend, Attribute.MOVEMENT_SPEED, speed, Operation.MULTIPLY_SCALAR_1,
                        TRAIL_BUFF_TICKS));
            }
            double jump = Math.max(0, params().num("trail_jump"));
            if (jump > 0) {
                double add = Motion.launchSpeed(VANILLA_JUMP + jump) - Motion.launchSpeed(VANILLA_JUMP);
                keys.add(ctx().addModifier(owner, friend, Attribute.JUMP_STRENGTH, add, Operation.ADD_NUMBER,
                        TRAIL_BUFF_TICKS));
            }
            buffs.put(friend.getUniqueId(), keys);
            if (ctx().currentTick() % 20 < TRAIL_PULSE) {
                Vfx.cloud(ctx(), friend.getLocation().add(0, 0.2, 0), Particle.DUST, 4, 0.3, 0, Guardian.BIRD.small,
                        false);
            }
        }

        void end() {
            for (BlockDisplay feather : feathers) {
                if (feather.isValid()) {
                    Vfx.dot(feather.getLocation().add(0, 0.2, 0), Particle.SMOKE, null, false);
                    Displays.shrinkAway(ctx(), owner, feather, 6);
                }
            }
        }
    }

    // ------------------------------------------------------------------ 백호: the tiger's pounce

    private ActivationResult tiger(Player owner) {
        LivingEntity target = ctx().findTarget(owner, Math.max(1, params().num("tiger_range")));
        if (target == null || isHidden(owner, target)) {
            return ActivationResult.fail("백호가 덮칠 적을 조준하세요 (" + AbilityInfo.formatNumber(
                    params().num("tiger_range")) + "블록 이내)");
        }
        new Pounce(owner, target).start();
        return ActivationResult.success();
    }

    /** The white tiger's spirit leaps from the owner onto the aimed enemy in an arc, and claws it. */
    private final class Pounce implements LongPredicate {

        private final Player owner;
        private final LivingEntity target;
        private final Location from;
        private final List<BlockDisplay> parts = new ArrayList<>();
        private boolean done;

        Pounce(Player owner, LivingEntity target) {
            this.owner = owner;
            this.target = target;
            this.from = owner.getLocation().add(0, 0.9, 0);
        }

        void start() {
            float yaw = yawOf(Fx.body(target).toVector().subtract(from.toVector()));
            if (reserve(5)) {
                long life = POUNCE_TICKS + 14;
                parts.add(keep(Displays.block(ctx(), owner, from, TIGER_FUR.createBlockData(),
                        Displays.transform(0, 0, 0, 0.46f, 0.42f, 0.95f, yaw, 0, 0), life)));
                parts.add(keep(Displays.block(ctx(), owner, from, TIGER_FUR.createBlockData(),
                        tigerPart(yaw, 0, 0.12f, 0.6f, 0.4f, 0.38f, 0.36f), life)));
                parts.add(keep(Displays.block(ctx(), owner, from, TIGER_STRIPE.createBlockData(),
                        tigerPart(yaw, 0, 0.0f, 0.15f, 0.48f, 0.44f, 0.07f), life)));
                parts.add(keep(Displays.block(ctx(), owner, from, TIGER_STRIPE.createBlockData(),
                        tigerPart(yaw, 0, 0.0f, -0.18f, 0.48f, 0.44f, 0.07f), life)));
                parts.add(keep(Displays.block(ctx(), owner, from, TIGER_FUR.createBlockData(),
                        tigerPart(yaw, 0, 0.1f, -0.68f, 0.1f, 0.1f, 0.5f), life)));
                for (BlockDisplay part : parts) {
                    part.setTeleportDuration(1);
                }
                Displays.outline(parts.getFirst(), Color.WHITE);
            }
            Fx.sound(from, Sound.ENTITY_POLAR_BEAR_WARNING, 1.0f, 1.3f + Fx.jitter(0.1f));
            Fx.sound(from, Sound.ENTITY_GOAT_LONG_JUMP, 0.9f, 1.1f);
            Vfx.burst(ctx(), from, Guardian.TIGER.palette, 0.5, Particle.WHITE_ASH, false);
            Loop.repeat(ctx(), owner, 1, 1, POUNCE_TICKS + 1, this, this::end);
        }

        /** A box of the tiger offset along its body ({@code forward}) and up, turned with it. */
        private Transformation tigerPart(float yaw, float side, float up, float forward, float sx, float sy, float sz) {
            Vector offset = rotate(new Vector(side, up, forward), yaw);
            return Displays.transform((float) offset.getX(), (float) offset.getY(), (float) offset.getZ(), sx, sy, sz,
                    yaw, 0, 0);
        }

        @Override
        public boolean test(long tick) {
            if (done) {
                return false;
            }
            if (!Combat.isTargetable(ctx(), owner, target) || !target.getWorld().equals(from.getWorld())) {
                end();
                return false;
            }
            Location to = Fx.body(target);
            double t = Math.min(1.0, (tick + 1) / (double) POUNCE_TICKS);
            Vector path = to.toVector().subtract(from.toVector());
            Location at = from.clone().add(path.clone().multiply(t)).add(0, Math.sin(t * Math.PI) * POUNCE_ARC, 0);
            float yaw = yawOf(path);
            float pitch = (float) (Math.cos(t * Math.PI) * -25);
            for (int i = 0; i < parts.size(); i++) {
                BlockDisplay part = parts.get(i);
                Displays.moveTo(part, Displays.unrotated(at));
                Displays.animate(part, pose(i, yaw, pitch), 1);
            }
            Vfx.dot(at, Particle.DUST, WHITE_DUST, false);
            Vfx.trail(at, at.clone().subtract(path.clone().normalize().multiply(1.5)), Color.WHITE, 5, false);
            if (t >= 1.0) {
                claw();
                return false;
            }
            return true;
        }

        private Transformation pose(int index, float yaw, float pitch) {
            return switch (index) {
                case 0 -> Displays.transform(0, 0, 0, 0.46f, 0.42f, 0.95f, yaw, pitch, 0);
                case 1 -> tigerPart(yaw, 0, 0.12f, 0.6f, 0.4f, 0.38f, 0.36f);
                case 2 -> tigerPart(yaw, 0, 0.0f, 0.15f, 0.48f, 0.44f, 0.07f);
                case 3 -> tigerPart(yaw, 0, 0.0f, -0.18f, 0.48f, 0.44f, 0.07f);
                default -> tigerPart(yaw, 0, 0.1f, -0.68f, 0.1f, 0.1f, 0.5f);
            };
        }

        private void claw() {
            done = true;
            Combat.hit(ctx(), owner, target, params().num("tiger_damage"));
            long stagger = params().ticks("tiger_stagger");
            if (stagger > 0 && Combat.isTargetable(ctx(), owner, target)) {
                double slow = Math.clamp(params().num("stagger_slow") / 100.0, 0.0, 1.0);
                ctx().addModifier(owner, target, Attribute.MOVEMENT_SPEED, -slow, Operation.MULTIPLY_SCALAR_1,
                        stagger);
                ctx().addModifier(owner, target, Attribute.ATTACK_SPEED, -slow, Operation.MULTIPLY_SCALAR_1,
                        stagger);
            }
            Location body = Fx.body(target);
            if (!isHidden(owner, target)) {
                Vector side = Motion.horizontal(body.toVector().subtract(owner.getLocation().toVector()))
                        .crossProduct(new Vector(0, 1, 0));
                if (side.lengthSquared() < 1.0E-6) {
                    side = new Vector(1, 0, 0);
                }
                side.normalize();
                for (int i = -1; i <= 1; i++) {
                    Location a = body.clone().add(side.clone().multiply(i * 0.22 - 0.45)).add(0, 0.55, 0);
                    Location b = body.clone().add(side.clone().multiply(i * 0.22 + 0.45)).add(0, -0.45, 0);
                    Vfx.line(ctx(), a, b, 8, Particle.DUST, WHITE_DUST, false);
                    Vfx.line(ctx(), a.clone().add(0, 0.05, 0), b.clone().add(0, 0.05, 0), 3, Particle.DUST,
                            STRIPE_DUST, false);
                }
                Vfx.burst(ctx(), body, Guardian.TIGER.palette, 0.7, Particle.CRIT, false);
                Vfx.dot(body, Particle.SWEEP_ATTACK, null, false);
            }
            Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.8f + Fx.jitter(0.1f));
            Fx.sound(body, Sound.ENTITY_RAVAGER_ATTACK, 0.7f, 1.1f + Fx.jitter(0.1f));
            Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.8f, 0.9f);
            end();
        }

        void end() {
            done = true;
            for (BlockDisplay part : parts) {
                if (part.isValid()) {
                    Displays.shrinkAway(ctx(), owner, part, 5);
                }
            }
            parts.clear();
        }
    }

    // ------------------------------------------------------------------ 현무: the tortoise's swap

    private ActivationResult tortoise(Player owner) {
        double range = Math.max(1, params().num("tortoise_range"));
        LivingEntity partner = null;
        boolean ally = false;
        for (Player friend : ctx().alliesNear(owner, owner.getLocation(), range)) {
            if (canSwap(owner, friend)) {
                partner = friend;
                ally = true;
                break;
            }
        }
        if (partner == null) {
            for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), range)) {
                if (!isHidden(owner, enemy) && canSwap(owner, enemy)) {
                    partner = enemy;
                    break;
                }
            }
        }
        if (partner == null) {
            return ActivationResult.fail("현무가 자리를 바꿔 줄 아군이나 적이 " + AbilityInfo.formatNumber(range)
                    + "블록 안에 없습니다");
        }
        swap(owner, partner, ally);
        return ActivationResult.success();
    }

    /** Both bodies fit at the other's spot, inside the arena, and neither rides anything. */
    private boolean canSwap(Player owner, LivingEntity other) {
        if (owner.isInsideVehicle() || other.isInsideVehicle() || !other.getWorld().equals(owner.getWorld())) {
            return false;
        }
        Location ownerFeet = owner.getLocation();
        Location otherFeet = other.getLocation();
        return Spots.isSafe(ctx(), otherFeet, Body.standing(owner)) && Spots.isSafe(ctx(), ownerFeet,
                Body.standing(other));
    }

    private void swap(Player owner, LivingEntity partner, boolean ally) {
        Location ownerFeet = owner.getLocation();
        Location partnerFeet = partner.getLocation();
        Location ownerDest = partnerFeet.clone();
        ownerDest.setYaw(ownerFeet.getYaw());
        ownerDest.setPitch(ownerFeet.getPitch());
        Location partnerDest = ownerFeet.clone();
        partnerDest.setYaw(partnerFeet.getYaw());
        partnerDest.setPitch(partnerFeet.getPitch());
        Spots.snapToGround(owner.getWorld(), ownerDest, 1.1);
        Spots.snapToGround(owner.getWorld(), partnerDest, 1.1);
        shellBurst(owner, ownerFeet);
        shellBurst(owner, partnerFeet);
        for (int i = 0; i < Vfx.n(ctx(), 5); i++) {
            Vfx.trail(ownerFeet.clone().add(0, 0.4 + i * 0.3, 0), partnerFeet.clone().add(0, 0.4 + i * 0.3, 0),
                    Guardian.TORTOISE.palette.body(), 8, false);
            Vfx.trail(partnerFeet.clone().add(0, 0.5 + i * 0.3, 0), ownerFeet.clone().add(0, 0.5 + i * 0.3, 0),
                    Guardian.TORTOISE.palette.core(), 8, false);
        }
        owner.teleport(ownerDest);
        owner.setVelocity(new Vector());
        owner.setFallDistance(0);
        partner.teleport(partnerDest);
        partner.setVelocity(new Vector());
        partner.setFallDistance(0);
        landing.armIfAirborne(owner, ctx().currentTick(), 60);
        int shell = (int) Math.min(Integer.MAX_VALUE, params().ticks("tortoise_shell"));
        if (shell > 0) {
            owner.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, shell, RESISTANCE_II, false, true,
                    true));
        }
        if (ally) {
            if (shell > 0) {
                partner.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, shell, RESISTANCE_II, false, true,
                        true));
            }
            shell(owner, partner);
            shell(owner, owner);
            if (partner instanceof Player friend) {
                friend.sendActionBar(Text.mm(Guardian.TORTOISE.colored("◆ 현무") + " <gray>— <white><owner></white>와(과) "
                        + "자리를 바꿔 등껍질이 감싸 줍니다</gray>", Placeholder.component("owner", owner.name())));
            }
            owner.sendActionBar(Text.mm(Guardian.TORTOISE.colored("◆ 현무 구출") + " <gray>— <white><ally></white>와(과) "
                    + "자리를 바꿨습니다</gray>", Placeholder.component("ally", partner.name())));
        } else {
            int slow = (int) Math.min(Integer.MAX_VALUE, params().ticks("tortoise_slow"));
            if (slow > 0 && Combat.isTargetable(ctx(), owner, partner)) {
                partner.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slow, SLOWNESS_II, false, true,
                        true));
            }
            shell(owner, owner);
            owner.sendActionBar(Text.mm(Guardian.TORTOISE.colored("◆ 현무") + " <gray>— <white><enemy></white>와(과) "
                    + "자리를 바꿨습니다</gray>", Placeholder.component("enemy", partner.name())));
        }
        Fx.sound(ownerDest, Sound.ITEM_ARMOR_EQUIP_TURTLE, 1.0f, 0.8f + Fx.jitter(0.05f));
        Fx.sound(ownerDest, Sound.ENTITY_ENDERMAN_TELEPORT, 0.7f, 0.7f);
        Fx.sound(partnerDest, Sound.ENTITY_TURTLE_SHAMBLE, 1.0f, 0.8f);
        Fx.sound(partnerDest, Sound.BLOCK_CONDUIT_ACTIVATE, 0.5f, 1.4f);
    }

    /** Teal water and a coiling snake of motes where a body left. */
    private void shellBurst(Player owner, Location feet) {
        Location at = feet.clone().add(0, 0.9, 0);
        Vfx.burst(ctx(), at, Guardian.TORTOISE.palette, 0.6, Particle.BUBBLE_POP, false);
        Vfx.helix(ctx(), feet.clone().add(0, 0.1, 0), 0.7, 1.8, 1.5, 1, 12, 0, Particle.DUST,
                Guardian.TORTOISE.dust, false);
    }

    /** A teal shell closes around {@code wearer} for a moment, following it. */
    private void shell(Player owner, LivingEntity wearer) {
        if (!reserve(2) || isHidden(owner, wearer)) {
            return;
        }
        Location at = Displays.unrotated(wearer.getLocation());
        float h = (float) wearer.getHeight();
        BlockDisplay outer = keep(Displays.block(ctx(), owner, at, SHELL_BLOCK.createBlockData(),
                Displays.transform(0, h * 0.5f, 0, 0.2f, 0.2f, 0.2f, 45, 0, 0), SHELL_TICKS + 8));
        BlockDisplay top = keep(Displays.block(ctx(), owner, at, SHELL_TOP.createBlockData(),
                Displays.transform(0, h + 0.05f, 0, 0.2f, 0.06f, 0.2f, 45, 0, 0), SHELL_TICKS + 8));
        outer.setTeleportDuration(FOLLOW_GLIDE);
        top.setTeleportDuration(FOLLOW_GLIDE);
        Displays.animateNextTick(ctx(), owner, outer, Displays.transform(0, h * 0.5f, 0, 1.15f, h * 0.75f, 1.15f, 45,
                0, 0), 4);
        Displays.animateNextTick(ctx(), owner, top, Displays.transform(0, h + 0.05f, 0, 0.9f, 0.08f, 0.9f, 45, 0, 0),
                4);
        Loop.repeat(ctx(), owner, 1, 1, SHELL_TICKS, tick -> {
            if (!wearer.isValid() || wearer.isDead()) {
                return false;
            }
            Location feet = Displays.unrotated(wearer.getLocation());
            Displays.moveTo(outer, feet);
            Displays.moveTo(top, feet);
            if (tick % 4 == 0) {
                Vfx.ring(ctx(), feet.clone().add(0, 0.15, 0), 0.75, 8, tick * 0.3, Particle.DUST,
                        Guardian.TORTOISE.small, false);
            }
            return true;
        }, () -> {
            for (BlockDisplay part : List.of(outer, top)) {
                if (part.isValid()) {
                    Displays.shrinkAway(ctx(), owner, part, 4);
                }
            }
        });
    }

    // ================================================================== ultimate: the barrier of the four

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        if (barrier != null) {
            return ActivationResult.fail("사신 결계가 이미 서 있습니다");
        }
        Barrier raised = new Barrier(owner);
        barrier = raised;
        raised.start();
        return ActivationResult.success();
    }

    /**
     * The square barrier. Its four triangles (east, south, west, north of the centre) each carry a guardian; every
     * turn moves each guardian one side on, clockwise seen from above (east → south → west → north). The owner standing
     * in its middle holds every stance.
     */
    private final class Barrier implements LongPredicate {

        private final Player owner;
        private final Location center;
        private final double radius;
        private final long duration;
        private final long turn;
        private final World world;
        private final List<TextDisplay> glyphs = new ArrayList<>();
        private final List<BlockDisplay> posts = new ArrayList<>();
        private final Map<UUID, List<NamespacedKey>> zoneMods = new HashMap<>();
        private int turns;
        boolean ownerInCenter;
        private boolean closed;

        Barrier(Player owner) {
            this.owner = owner;
            Location feet = owner.getLocation();
            Spots.snapToGround(feet.getWorld(), feet, GROUND_PROBE);
            this.center = Displays.unrotated(feet);
            this.radius = Math.max(2, params().num("ult_radius"));
            this.duration = Math.max(1, params().ticks("ult_duration"));
            this.turn = Math.max(2, params().ticks("ult_rotate"));
            this.world = center.getWorld();
        }

        void start() {
            for (Guardian guardian : Guardian.values()) {
                if (!reserve(1)) {
                    break;
                }
                TextDisplay glyph = keep(Displays.text(ctx(), owner, sideSpot(guardian.ordinal()),
                        Text.mm("<b>" + guardian.colored(guardian.hanja) + "</b>"), 0.3f, duration + 20));
                glyph.setViewRange(2.5f);
                glyph.setTeleportDuration(GLYPH_GLIDE);
                Displays.animateNextTick(ctx(), owner, glyph, Displays.centered(0, 0, 0, 2.6f, 2.6f, 2.6f, 0, 0, 0),
                        8);
                glyphs.add(glyph);
            }
            for (int corner = 0; corner < 4; corner++) {
                if (!reserve(1)) {
                    break;
                }
                double x = (corner == 0 || corner == 3) ? radius : -radius;
                double z = corner < 2 ? radius : -radius;
                Location at = groundAt(center.clone().add(x, 0, z));
                BlockDisplay post = keep(Displays.block(ctx(), owner, at, POST_BLOCK.createBlockData(),
                        Displays.transform(0, 0.05f, 0, 0.22f, 0.05f, 0.22f, 0, 0, 0), duration + 20));
                post.setViewRange(2.5f);
                Displays.animateNextTick(ctx(), owner, post, Displays.transform(0, 1.5f, 0, 0.22f, 3.0f, 0.22f, 0, 0,
                        0), 8);
                posts.add(post);
            }
            Location head = center.clone().add(0, 1.5, 0);
            Vfx.shockwave(ctx(), owner, center.clone().add(0, 0.2, 0), 0.5, radius, 8, Particle.DUST_COLOR_TRANSITION,
                    new Particle.DustTransition(Color.fromRGB(0xFFF1C2), GOLD, 1.3f), true);
            Fx.sound(head, Sound.BLOCK_BELL_USE, 1.6f, 0.6f);
            Fx.sound(head, Sound.BLOCK_BELL_RESONATE, 1.2f, 0.8f);
            Fx.sound(head, Sound.ENTITY_ENDER_DRAGON_FLAP, 1.0f, 0.8f);
            Fx.sound(head, Sound.ENTITY_POLAR_BEAR_WARNING, 1.0f, 0.7f);
            Fx.sound(head, Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.2f);
            ctx().announce(Text.mm("<" + Guardian.DRAGON.tag + ">◆</" + Guardian.DRAGON.tag + "> <white><owner></white>"
                            + "<gray>이(가) </gray><b>" + Guardian.DRAGON.colored("사") + Guardian.BIRD.colored("신")
                            + " " + Guardian.TIGER.colored("결") + Guardian.TORTOISE.colored("계") + "</b><gray>를 "
                            + "펼칩니다!</gray>", Placeholder.component("owner", owner.name())));
            Loop.repeat(ctx(), owner, 0, 1, duration, this, this::close);
        }

        /** The guardian standing at side {@code direction} (0 east, 1 south, 2 west, 3 north) after the turns so far. */
        private Guardian at(int direction) {
            return Guardian.values()[Math.floorMod(direction - turns, 4)];
        }

        /** The middle of side {@code direction}, high over the ground. */
        private Location sideSpot(int direction) {
            double r = radius * 0.92;
            Vector out = switch (Math.floorMod(direction, 4)) {
                case 0 -> new Vector(r, 0, 0);
                case 1 -> new Vector(0, 0, r);
                case 2 -> new Vector(-r, 0, 0);
                default -> new Vector(0, 0, -r);
            };
            return groundAt(center.clone().add(out)).add(0, GLYPH_HEIGHT, 0);
        }

        private Location groundAt(Location column) {
            Location probe = column.clone();
            probe.setY(center.getY() + 3);
            Spots.snapToGround(world, probe, GROUND_PROBE);
            return Displays.unrotated(probe);
        }

        /** The side (0 east, 1 south, 2 west, 3 north) a point belongs to, -1 outside the square. */
        private int sideOf(Location at) {
            double dx = at.getX() - center.getX();
            double dz = at.getZ() - center.getZ();
            if (Math.abs(dx) > radius || Math.abs(dz) > radius || Math.abs(at.getY() - center.getY()) > ULT_HEIGHT) {
                return -1;
            }
            if (Math.abs(dx) >= Math.abs(dz)) {
                return dx >= 0 ? 0 : 2;
            }
            return dz >= 0 ? 1 : 3;
        }

        @Override
        public boolean test(long tick) {
            if (closed) {
                return false;
            }
            if (tick > 0 && tick % turn == 0) {
                rotate();
            }
            updateCenter(tick);
            paint(tick);
            if (tick % ULT_PULSE == 0) {
                pulse();
            }
            return true;
        }

        /** Every guardian moves one side on, clockwise; the floor colours turn with them. */
        private void rotate() {
            turns++;
            for (int i = 0; i < glyphs.size(); i++) {
                TextDisplay glyph = glyphs.get(i);
                if (glyph.isValid()) {
                    Displays.moveTo(glyph, sideSpot(i + turns));
                }
            }
            Location head = center.clone().add(0, 1.2, 0);
            Vfx.ring(ctx(), head, radius * 0.5, 24, turns * 0.6, Particle.DUST, GOLD_DUST, true);
            Fx.sound(head, Sound.BLOCK_BELL_USE, 1.0f, 0.9f + turns * 0.08f);
            for (int side = 0; side < 4; side++) {
                Guardian guardian = at(side);
                cry(sideSpot(side), guardian, 0.5f);
            }
        }

        private void updateCenter(long tick) {
            boolean inside = owner.getWorld().equals(world)
                    && horizontalDistance(owner.getLocation(), center) <= Math.max(0.5, params().num("ult_center"))
                    && Math.abs(owner.getLocation().getY() - center.getY()) <= ULT_HEIGHT;
            if (inside != ownerInCenter) {
                ownerInCenter = inside;
                refreshBonuses();
                if (inside) {
                    owner.sendActionBar(Text.mm("<b>" + Guardian.DRAGON.colored("청룡") + "·" + Guardian.BIRD.colored("주작")
                            + "·" + Guardian.TIGER.colored("백호") + "·" + Guardian.TORTOISE.colored("현무")
                            + "</b> <gray>— 네 자세의 힘을 모두 받습니다</gray>"));
                }
            }
            if (ownerInCenter && tick % 3 == 0) {
                Vfx.helix(ctx(), owner.getLocation().add(0, 0.1, 0), 0.6, 2.2, 1, 4, 3, tick * 0.25,
                        Particle.DUST, Guardian.values()[(int) ((tick / 3) % 4)].small, false);
            }
        }

        /** The floor of each triangle glows in its guardian's colour; the square and its diagonals are drawn. */
        private void paint(long tick) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int points = Vfx.n(ctx(), 14);
            for (int i = 0; i < points; i++) {
                Location spot = center.clone().add(random.nextDouble(-radius, radius), 0, random.nextDouble(-radius,
                        radius));
                int side = sideOf(spot);
                if (side < 0) {
                    continue;
                }
                Location ground = groundAt(spot).add(0, 0.12, 0);
                Vfx.dot(ground, Particle.DUST, at(side).floor, true);
            }
            if (tick % 4 == 0) {
                int edge = Vfx.n(ctx(), 8);
                for (int k = 0; k <= edge; k++) {
                    double t = -radius + 2 * radius * k / edge;
                    for (Vector corner : new Vector[] {new Vector(t, 0, radius), new Vector(t, 0, -radius),
                            new Vector(radius, 0, t), new Vector(-radius, 0, t)}) {
                        Vfx.dot(groundAt(center.clone().add(corner)).add(0, 0.25, 0), Particle.DUST, GOLD_DUST, true);
                    }
                }
            }
            if (tick % 4 == 2) {
                int diag = Vfx.n(ctx(), 7);
                for (int k = 1; k <= diag; k++) {
                    double t = radius * k / diag;
                    for (Vector corner : new Vector[] {new Vector(t, 0, t), new Vector(-t, 0, t), new Vector(t, 0, -t),
                            new Vector(-t, 0, -t)}) {
                        Vfx.dot(groundAt(center.clone().add(corner)).add(0, 0.18, 0), Particle.END_ROD, null, false);
                    }
                }
            }
        }

        /** Every enemy inside feels the guardian of its triangle. */
        private void pulse() {
            double reach = radius * Math.sqrt(2) + 1;
            for (LivingEntity enemy : ctx().enemiesNear(owner, center, reach)) {
                int side = sideOf(enemy.getLocation());
                clearZone(enemy);
                if (side < 0) {
                    continue;
                }
                Guardian guardian = at(side);
                switch (guardian) {
                    case DRAGON -> {
                        Combat.hit(ctx(), owner, enemy, params().num("ult_dragon_damage"));
                        Vector pull = Motion.horizontal(center.toVector().subtract(enemy.getLocation().toVector()));
                        if (pull.lengthSquared() > 0 && Combat.isTargetable(ctx(), owner, enemy)) {
                            Motion.push(enemy, pull.multiply(PULL_SPEED).setY(0.12), MAX_PUSH);
                        }
                    }
                    case BIRD -> {
                        Combat.hit(ctx(), owner, enemy, params().num("ult_bird_damage"));
                        if (Combat.isTargetable(ctx(), owner, enemy)) {
                            Motion.push(enemy, new Vector(0, LIFT_SPEED, 0), MAX_PUSH);
                            enemy.setFallDistance(0);
                        }
                    }
                    case TIGER -> {
                        Combat.hit(ctx(), owner, enemy, params().num("ult_tiger_dps") * ULT_PULSE / 20.0);
                        slow(enemy, params().num("ult_tiger_slow"), false);
                    }
                    case TORTOISE -> slow(enemy, params().num("ult_tortoise_slow"), true);
                }
                if (!isHidden(owner, enemy)) {
                    Location body = Fx.body(enemy);
                    Vfx.cloud(ctx(), body, Particle.DUST, 6, 0.35, 0, guardian.dust, false);
                    Vfx.dot(body, accent(guardian), null, false);
                }
            }
        }

        private void slow(LivingEntity enemy, double percent, boolean grounded) {
            if (!Combat.isTargetable(ctx(), owner, enemy)) {
                return;
            }
            List<NamespacedKey> keys = new ArrayList<>();
            double amount = Math.clamp(percent / 100.0, 0.0, 1.0);
            if (amount > 0) {
                keys.add(ctx().addModifier(owner, enemy, Attribute.MOVEMENT_SPEED, -amount, Operation.MULTIPLY_SCALAR_1,
                        ULT_MOD_TICKS));
            }
            if (grounded) {
                keys.add(ctx().addModifier(owner, enemy, Attribute.JUMP_STRENGTH, -1, Operation.MULTIPLY_SCALAR_1,
                        ULT_MOD_TICKS));
            }
            zoneMods.put(enemy.getUniqueId(), keys);
        }

        /** Takes off the previous pulse's slow, so the effects of a new triangle never stack with the old one's. */
        private void clearZone(LivingEntity enemy) {
            List<NamespacedKey> keys = zoneMods.remove(enemy.getUniqueId());
            if (keys != null) {
                for (NamespacedKey key : keys) {
                    ctx().removeModifier(enemy, key);
                }
            }
        }

        void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (barrier == this) {
                barrier = null;
            }
            ownerInCenter = false;
            refreshBonuses();
            for (TextDisplay glyph : glyphs) {
                if (glyph.isValid()) {
                    Displays.shrinkAway(ctx(), owner, glyph, 8);
                }
            }
            for (BlockDisplay post : posts) {
                if (post.isValid()) {
                    Displays.shrinkAway(ctx(), owner, post, 8);
                }
            }
            Vfx.shockwave(ctx(), owner, center.clone().add(0, 0.3, 0), radius, 0.5, 8, Particle.END_ROD, null, false);
            Fx.sound(center, Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 1.1f);
            Fx.sound(center, Sound.BLOCK_BELL_RESONATE, 0.8f, 1.2f);
        }
    }

    // ================================================================== helpers

    /** The guardian's own particle: the dragon's glow, the bird's flame, the tiger's ash, the tortoise's bubbles. */
    private static Particle accent(Guardian guardian) {
        return switch (guardian) {
            case DRAGON -> Particle.GLOW;
            case BIRD -> Particle.SMALL_FLAME;
            case TIGER -> Particle.WHITE_ASH;
            case TORTOISE -> Particle.BUBBLE_POP;
        };
    }

    /** The guardian's voice. */
    private static void cry(Location at, Guardian guardian, float volume) {
        switch (guardian) {
            case DRAGON -> {
                Fx.sound(at, Sound.ENTITY_ENDER_DRAGON_FLAP, volume, 1.6f + Fx.jitter(0.1f));
                Fx.sound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, volume, 1.2f + Fx.jitter(0.1f));
            }
            case BIRD -> {
                Fx.sound(at, Sound.ENTITY_PARROT_FLY, volume, 1.3f + Fx.jitter(0.1f));
                Fx.sound(at, Sound.ITEM_FIRECHARGE_USE, volume * 0.6f, 1.6f + Fx.jitter(0.1f));
            }
            case TIGER -> {
                Fx.sound(at, Sound.ENTITY_POLAR_BEAR_WARNING, volume, 1.4f + Fx.jitter(0.1f));
                Fx.sound(at, Sound.ENTITY_CAT_HISS, volume * 0.7f, 0.8f + Fx.jitter(0.1f));
            }
            case TORTOISE -> {
                Fx.sound(at, Sound.ITEM_ARMOR_EQUIP_TURTLE, volume, 1.0f + Fx.jitter(0.1f));
                Fx.sound(at, Sound.BLOCK_CONDUIT_AMBIENT_SHORT, volume, 1.2f + Fx.jitter(0.1f));
            }
        }
    }

    /** The yaw (degrees) a horizontal direction faces, in the display convention (0 = +Z, 90 = -X). */
    private static float yawOf(Vector direction) {
        if (direction.getX() * direction.getX() + direction.getZ() * direction.getZ() < 1.0E-9) {
            return 0;
        }
        return (float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
    }

    /** {@code local} (x left, y up, z forward) turned to face {@code yaw}. */
    private static Vector rotate(Vector local, float yaw) {
        double rad = Math.toRadians(yaw);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        return new Vector(local.getX() * cos - local.getZ() * sin, local.getY(), local.getX() * sin + local.getZ() * cos);
    }

    private static double horizontalDistance(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private <T extends Display> T keep(T display) {
        displays.add(display);
        return display;
    }

    /** Reserves room for {@code count} more displays under {@link #MAX_DISPLAYS}; false when there is none. */
    private boolean reserve(int count) {
        displays.removeIf(display -> !display.isValid());
        return displays.size() + count <= MAX_DISPLAYS;
    }

    private static boolean isHidden(Player owner, LivingEntity entity) {
        return entity instanceof Player hidden && !owner.canSee(hidden);
    }
}
