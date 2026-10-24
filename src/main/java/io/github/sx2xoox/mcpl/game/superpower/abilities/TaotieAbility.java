package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Body;
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
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 도철 — one of the Four Perils of Chinese myth: a monster of endless gluttony that ate until it had eaten its own
 * body and only the face was left. That face — the taotie pattern on ancient bronze vessels — hangs over it like a
 * mask.
 *
 * <p>Whoever the taotie tastes becomes its mask for a while: the taotie hits that enemy harder ("it knows the taste")
 * and its ultimate becomes, once, that enemy's ultimate. The active opens a bronze maw for a second that sucks in the
 * cone in front, swallows shots and spells from afar and bites the first enemy it gets, spitting it out behind. The
 * ultimate (without a mask) is the maw of the Four Perils: a huge mouth sucks in a wide cone, gnawing; whoever it gets
 * is swallowed whole for a moment and digested — buffs, absorption and ultimate gauge melt away — then spat out covered
 * in slobber.
 *
 * <p>Look: bronze patina, bronze and greedy crimson. The maw is a taotie face of bronze plates: an upper and a lower
 * jaw hinged at the back that open wider and wider, rows of bronze teeth, crimson eyes on a brow and curled horns. A
 * crimson cone is drawn on the ground and motes stream into the mouth as the suction builds. A taste peels the victim's
 * face off (its head flies into the taotie's face, spinning) and the bronze mask appears, with the victim's name over
 * the taotie's head. A swallowed enemy vanishes into the mouth with a gulp and comes back out with a splash of slobber.
 */
public final class TaotieAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("taotie", "도철", Material.CAULDRON)
            .color(TextColor.color(0xB07A3B))
            .difficulty(2)
            .passive("탐식의 가면",
                    "맛본 적(플레이어와 허수아비)의 얼굴이 청동 가면이 되어 {mask_duration}초간 내 얼굴에 씌워집니다"
                            + "(모두에게 보임, 한 번에 하나). 가면을 쓴 동안 그 적에게 주는 피해가 {mask_bonus}% 늘고, 내 "
                            + "궁극기는 한 번 그 적의 궁극기가 됩니다. 따라 할 수 없는 궁극기(허수아비 포함)라면 '맛이 없다!' "
                            + "— 가면을 뱉고 사흉의 아가리를 쓰며 궁극기 게이지 {tasteless_refund}%를 돌려받습니다.")
            .active("한입 맛보기",
                    "{gulp_duration}초간 청동 아가리를 크게 벌려 앞 {gulp_range}블록·{gulp_angle}° 부채꼴을 "
                            + "빨아들입니다(점점 강해지고, 그동안 내 이동 속도 −{gulp_slow}%). 입을 벌린 동안 앞쪽 "
                            + "{swallow_distance}블록 밖에서 온 투사체와 능력 피해는 입으로 들어가 무효가 됩니다. 입에 닿은 "
                            + "첫 적을 한입 물어 {bite_damage} 피해를 주고 뒤로 뱉어 내며, 그 적을 맛봅니다. 아무도 물지 "
                            + "못하면 쿨타임이 {miss_cooldown}%만 돕니다.",
                    10)
            .ultimate("사흉의 아가리",
                    "(가면이 없을 때) {maw_duration}초간 거대한 아가리가 앞 {maw_range}블록·{maw_angle}° 부채꼴을 "
                            + "빨아들입니다(그동안 내 이동 속도 −{gulp_slow}%). 적은 끌려오며 {maw_tick_interval}초마다 {maw_tick_damage} 피해를 입고(적마다 "
                            + "최대 {maw_tick_max}), 앞쪽 {swallow_distance}블록 밖에서 온 투사체·능력 피해는 삼켜집니다. "
                            + "아가리에 들어온 적(최대 {maw_swallow_max}명)은 {digest_duration}초간 삼켜져 소화됩니다: "
                            + "보이지 않고 아무것도 못 하며 {digest_damage} 피해를 입고, 이로운 포션 효과와 흡수 체력이 모두 "
                            + "녹고(녹인 흡수 체력만큼 내가 {digest_absorb_duration}초간 흡수 체력을 얻음, 모두 합쳐 최대 "
                            + "{digest_absorb_max}) 궁극기 게이지 {digest_gauge}%를 먹힙니다. 그 뒤 앞으로 뱉어져 "
                            + "{spit_damage} 피해를 입고 {slobber_duration}초간 침 범벅(발광, 이동 속도 −{slobber_slow}%)이 "
                            + "됩니다. 처음 삼킨 적을 맛봅니다. 앞에 삼킬 적이 없으면 쓸 수 없습니다.")
            .param("mask_duration", 20)
            .param("mask_bonus", 15)
            .param("tasteless_refund", 25)
            .param("gulp_duration", 1)
            .param("gulp_range", 5)
            .param("gulp_angle", 60)
            .param("gulp_slow", 50)
            .param("swallow_distance", 6)
            .param("bite_damage", 3)
            .param("miss_cooldown", 50)
            .param("maw_duration", 2)
            .param("maw_range", 10)
            .param("maw_angle", 120)
            .param("maw_tick_interval", 0.25)
            .param("maw_tick_damage", 0.5)
            .param("maw_tick_max", 2)
            .param("maw_swallow_max", 3)
            .param("digest_duration", 1)
            .param("digest_damage", 2)
            .param("digest_absorb_duration", 10)
            .param("digest_absorb_max", 4)
            .param("digest_gauge", 15)
            .param("spit_damage", 2)
            .param("slobber_duration", 3)
            .param("slobber_slow", 20)
            .build();

    private static final String BRONZE_TAG = "#C98E4E";
    private static final String CRIMSON_TAG = "#C8263F";
    private static final String PATINA_TAG = "#5FB3A0";

    // ------------------------------------------------------------------ look
    /** Bright bronze core, patina body, greedy crimson edge. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xE8B878, 0x3E8E7E, 0x9E1B32);
    private static final Particle.DustOptions BRONZE = new Particle.DustOptions(Color.fromRGB(0xB07A3B), 1.0f);
    private static final Particle.DustOptions PATINA = new Particle.DustOptions(Color.fromRGB(0x3E8E7E), 0.9f);
    private static final Particle.DustOptions CRIMSON = new Particle.DustOptions(Color.fromRGB(0x9E1B32), 1.0f);
    private static final Particle.DustOptions SLOBBER = new Particle.DustOptions(Color.fromRGB(0xD8E6E0), 0.8f);
    private static final Particle.DustTransition GREED = new Particle.DustTransition(Color.fromRGB(0xE04A5E),
            Color.fromRGB(0x9E1B32), 1.1f);
    private static final Particle.DustTransition BRONZE_GLOW = new Particle.DustTransition(Color.fromRGB(0xE8B878),
            Color.fromRGB(0xB07A3B), 1.0f);
    private static final Color INFLOW = Color.fromRGB(0xE04A5E);
    private static final Color INFLOW_BRONZE = Color.fromRGB(0xE8B878);

    /** Materials of the visuals; block data is created when used (it needs a running server). */
    private static final Material JAW = Material.WAXED_CUT_COPPER;
    private static final Material TEETH = Material.WAXED_EXPOSED_CUT_COPPER;
    private static final Material MASK_FACE = Material.WAXED_OXIDIZED_COPPER;
    private static final Material MASK_BROW = Material.WAXED_COPPER_BLOCK;
    private static final Material EYE = Material.REDSTONE_BLOCK;
    private static final Material HORN = Material.WAXED_WEATHERED_CUT_COPPER;

    private static final int MAX_DISPLAYS = 24;
    private static final double MAX_SPEED = 1.6;

    // mask
    private static final long FACE_FLIGHT = 8;
    private static final long HUNGER_PERIOD = 30;
    private static final double LABEL_LIFT = 0.65;

    // maw
    /** The mouth's middle, in maw sizes: forward of the feet, and down from the eyes. */
    private static final double MOUTH_FORWARD = 0.62;
    private static final double MOUTH_DROP = 0.26;
    private static final double BITE_REACH = 1.4;
    private static final double KING_REACH = 2.3;
    private static final double PULL_MIN = 0.12;
    private static final double PULL_MAX = 0.42;
    private static final double KING_PULL_MIN = 0.15;
    private static final double KING_PULL_MAX = 0.5;
    private static final double PULL_HOP = 0.2;
    private static final double VERTICAL_REACH = 3;
    private static final float GULP_SIZE = 1f;
    private static final float KING_SIZE = 2.3f;
    private static final float JAW_OPEN = 38f;
    private static final int JAW_OPEN_TICKS = 4;
    private static final int JAW_SHUT_TICKS = 2;
    private static final long CONE_PERIOD = 4;
    private static final double SPIT_BACK = 0.95;
    private static final double SPIT_BACK_LIFT = 0.72;
    private static final double SPIT_FORWARD = 1.1;
    private static final double SPIT_FORWARD_LIFT = 0.45;
    private static final long MAX_LAG_TICKS = 8;

    /** A worn mask: whose face it is, until when, and its displays. */
    private final class Mask {
        final LivingEntity enemy;
        final long until;
        final List<BlockDisplay> parts = new ArrayList<>();
        final float[][] layout;
        @Nullable TextDisplay label;

        Mask(LivingEntity enemy, long until, float[][] layout) {
            this.enemy = enemy;
            this.until = until;
            this.layout = layout;
        }
    }

    private final List<Display> displays = new ArrayList<>();
    private @Nullable Mask mask;
    private @Nullable Maw maw;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        Maw open = maw;
        if (open != null) {
            open.release();
        }
        maw = null;
        mask = null;
        displays.clear();
    }

    /** Package-private for tests: whose face the taotie wears, null if none. */
    @Nullable LivingEntity maskOf() {
        Mask worn = mask;
        return worn == null || ctx().currentTick() >= worn.until ? null : worn.enemy;
    }

    @Override
    public void onTick(long tick) {
        Mask worn = mask;
        if (worn == null) {
            if (tick % HUNGER_PERIOD == 0) {
                // Endless hunger: a bronze glint and a crimson drool at the mouth while no face is worn.
                Player owner = player();
                Location mouth = owner.getEyeLocation().add(Motion.horizontalFacing(owner.getLocation())
                        .multiply(0.3)).subtract(0, 0.25, 0);
                mouth.getWorld().spawnParticle(Particle.DUST, mouth, 1, 0.06, 0.04, 0.06, 0, CRIMSON);
                mouth.getWorld().spawnParticle(Particle.DUST, mouth, 1, 0.08, 0.04, 0.08, 0, BRONZE);
            }
            return;
        }
        if (ctx().currentTick() >= worn.until || !worn.enemy.isValid()
                || worn.enemy instanceof Player face && !ctx().isAlive(face)) {
            dropMask(worn, false);
            return;
        }
        follow(worn, tick);
    }

    // ------------------------------------------------------------------ passive: the mask of gluttony

    /** "It knows the taste": more damage to the enemy whose face it wears. */
    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        Mask worn = mask;
        if (worn == null || event.isCancelled() || !victim.equals(worn.enemy) || ctx().currentTick() >= worn.until) {
            return;
        }
        double bonus = Math.max(0, params().num("mask_bonus")) / 100.0;
        event.setDamage(event.getDamage() * (1 + bonus));
        if (!isHidden(victim)) {
            Location body = Fx.body(victim);
            body.getWorld().spawnParticle(Particle.DUST, body, Vfx.n(ctx(), 3), 0.2, 0.3, 0.2, 0, CRIMSON);
        }
    }

    /**
     * The taotie tastes {@code enemy}: its face is peeled off and becomes the mask (players and mannequins such as the
     * training dummies, which have faces; summoned beasts do not).
     */
    private void taste(LivingEntity enemy) {
        if (!(enemy instanceof Player || enemy instanceof Mannequin) || ctx().abilityOf(player()) != this) {
            return;
        }
        Player owner = player();
        Mask old = mask;
        if (old != null) {
            dropMask(old, true);
        }
        long duration = Math.max(1, params().ticks("mask_duration"));
        float[][] layout = maskLayout();
        Mask worn = new Mask(enemy, ctx().currentTick() + duration, layout);
        mask = worn;
        // The face flies off the victim into the taotie's face, spinning.
        if (reserve(1)) {
            Location head = Displays.unrotated(enemy.getLocation().add(0, enemy.getHeight() - 0.25, 0));
            ItemDisplay face = keep(Displays.item(ctx(), owner, head, headOf(enemy),
                    Displays.centered(0, 0, 0, 0.5f, 0.5f, 0.5f, 0, 0, 0), FACE_FLIGHT + 4));
            face.setTeleportDuration((int) FACE_FLIGHT);
            owner.hideEntity(ctx().plugin(), face);
            ctx().runLater(owner, 1, () -> {
                if (face.isValid()) {
                    Displays.moveTo(face, owner.getEyeLocation());
                    Displays.animate(face, Displays.centered(0, 0, 0, 0.3f, 0.3f, 0.3f, 720, 0, 0), (int) FACE_FLIGHT);
                }
            });
        }
        ctx().runLater(owner, FACE_FLIGHT, () -> wear(worn));
        Location chest = Fx.body(enemy);
        if (!isHidden(enemy)) {
            chest.getWorld().spawnParticle(Particle.DUST_COLOR_TRANSITION, chest, Vfx.n(ctx(), 10), 0.25, 0.35, 0.25,
                    0, GREED);
        }
        owner.sendActionBar(Text.mm("<" + CRIMSON_TAG + "><b>맛봤다!</b></" + CRIMSON_TAG + "> <white><name></white><gray>"
                + "의 얼굴 · " + Fx.seconds(duration) + "초 · 피해 +" + AbilityInfo.formatNumber(params().num("mask_bonus"))
                + "% · 궁극기를 따라 합니다</gray>", Placeholder.component("name", enemy.name())));
    }

    /** The bronze mask appears over the taotie's face (everyone but the taotie itself sees it). */
    private void wear(Mask worn) {
        if (mask != worn) {
            return;
        }
        Player owner = player();
        Location eye = Displays.unrotated(owner.getEyeLocation());
        Material[] materials = {MASK_FACE, MASK_BROW, EYE, EYE, HORN, HORN};
        float yaw = owner.getLocation().getYaw();
        float pitch = owner.getLocation().getPitch();
        for (int i = 0; i < worn.layout.length && reserve(1); i++) {
            BlockDisplay part = keep(Displays.block(ctx(), owner, eye, materials[i].createBlockData(),
                    maskPose(worn.layout[i], yaw, pitch, 0.05f), 0));
            part.setTeleportDuration(1);
            owner.hideEntity(ctx().plugin(), part);
            worn.parts.add(part);
        }
        if (reserve(1)) {
            worn.label = keep(Displays.text(ctx(), owner, labelSpot(owner), Text.mm("<" + CRIMSON_TAG + "><name></"
                    + CRIMSON_TAG + "><" + BRONZE_TAG + ">의 얼굴</" + BRONZE_TAG + ">",
                    Placeholder.component("name", worn.enemy.name())), 0.55f, 0));
            worn.label.setTeleportDuration(1);
        }
        ctx().runLater(owner, 1, () -> {
            for (int i = 0; i < worn.parts.size(); i++) {
                Displays.animate(worn.parts.get(i), maskPose(worn.layout[i], owner.getLocation().getYaw(),
                        owner.getLocation().getPitch(), 1f), 3);
            }
        });
        Location face = owner.getEyeLocation();
        World world = face.getWorld();
        Vfx.dot(face, Particle.FLASH, PALETTE.edge(), false);
        world.spawnParticle(Particle.DUST, face, Vfx.n(ctx(), 8), 0.25, 0.25, 0.25, 0, PATINA);
        world.spawnParticle(Particle.WAX_OFF, face, Vfx.n(ctx(), 4), 0.25, 0.25, 0.25, 0);
        Fx.sound(face, Sound.BLOCK_COPPER_PLACE, 1.0f, 0.7f + Fx.jitter(0.05f));
        Fx.sound(face, Sound.ENTITY_EVOKER_PREPARE_ATTACK, 0.6f, 0.6f + Fx.jitter(0.05f));
    }

    /** The mask moves with the taotie's head. */
    private void follow(Mask worn, long tick) {
        Player owner = player();
        if (worn.parts.isEmpty() && worn.label == null) {
            return;
        }
        Location eye = Displays.unrotated(owner.getEyeLocation());
        float yaw = owner.getLocation().getYaw();
        float pitch = owner.getLocation().getPitch();
        for (int i = 0; i < worn.parts.size(); i++) {
            BlockDisplay part = worn.parts.get(i);
            Displays.moveTo(part, eye);
            Displays.animate(part, maskPose(worn.layout[i], yaw, pitch, 1f), 1);
        }
        if (worn.label != null) {
            Displays.moveTo(worn.label, labelSpot(owner));
            if (tick % 20 == 0) {
                long left = Math.max(0, worn.until - ctx().currentTick());
                worn.label.text(Text.mm("<" + CRIMSON_TAG + "><name></" + CRIMSON_TAG + "><" + BRONZE_TAG + ">의 얼굴</"
                        + BRONZE_TAG + "> <gray>" + Fx.seconds(left) + "s</gray>",
                        Placeholder.component("name", worn.enemy.name())));
            }
        }
        if (tick % 10 == 0) {
            Location face = owner.getEyeLocation().add(Motion.horizontalFacing(owner.getLocation()).multiply(0.35));
            face.getWorld().spawnParticle(Particle.DUST, face, 1, 0.15, 0.15, 0.15, 0, CRIMSON);
        }
    }

    /** The mask comes off (expired, replaced, spat out or used up). */
    private void dropMask(Mask worn, boolean quiet) {
        if (mask == worn) {
            mask = null;
        }
        Player owner = player();
        for (BlockDisplay part : worn.parts) {
            if (part.isValid()) {
                Displays.shrinkAway(ctx(), owner, part, 4);
            }
        }
        worn.parts.clear();
        if (worn.label != null && worn.label.isValid()) {
            Displays.shrinkAway(ctx(), owner, worn.label, 4);
        }
        worn.label = null;
        if (!quiet && owner.isOnline()) {
            Location face = owner.getEyeLocation();
            face.getWorld().spawnParticle(Particle.DUST, face, Vfx.n(ctx(), 6), 0.25, 0.25, 0.25, 0, BRONZE);
            Fx.sound(face, Sound.BLOCK_COPPER_BREAK, 0.8f, 0.9f + Fx.jitter(0.05f));
        }
    }

    /** The mask's six plates in the head's frame: (x left, y up, z forward) center, size, roll. */
    private static float[][] maskLayout() {
        return new float[][] {
                {0f, -0.06f, 0.29f, 0.56f, 0.5f, 0.05f, 0f},
                {0f, 0.16f, 0.32f, 0.62f, 0.08f, 0.06f, 0f},
                {0.12f, 0.03f, 0.325f, 0.11f, 0.08f, 0.03f, 0f},
                {-0.12f, 0.03f, 0.325f, 0.11f, 0.08f, 0.03f, 0f},
                {0.29f, 0.26f, 0.27f, 0.07f, 0.3f, 0.07f, -28f},
                {-0.29f, 0.26f, 0.27f, 0.07f, 0.3f, 0.07f, 28f},
        };
    }

    private static Transformation maskPose(float[] p, float yaw, float pitch, float grow) {
        float g = Math.max(0.001f, grow);
        return piece(yaw, pitch, p[0], p[1], p[2], p[3] * g, p[4] * g, p[5] * g, p[6]);
    }

    private static Location labelSpot(Player owner) {
        return Displays.unrotated(owner.getLocation().add(0, owner.getHeight() + LABEL_LIFT, 0));
    }

    /** The victim's own head: a player's skin, a mannequin's profile, otherwise a skull. */
    private static ItemStack headOf(LivingEntity target) {
        ItemStack head = ItemStack.of(Material.PLAYER_HEAD);
        if (target instanceof Player player) {
            head.setData(DataComponentTypes.PROFILE, ResolvableProfile.resolvableProfile(player.getPlayerProfile()));
        } else if (target instanceof Mannequin mannequin) {
            head.setData(DataComponentTypes.PROFILE, mannequin.getProfile());
        } else {
            return ItemStack.of(Material.SKELETON_SKULL);
        }
        return head;
    }

    // ------------------------------------------------------------------ active: a taste

    @Override
    public ActivationResult onActive() {
        Maw open = maw;
        if (open != null && !open.closed) {
            return ActivationResult.fail("아가리가 이미 벌어져 있습니다");
        }
        Maw gulp = new Maw(player(), false);
        maw = gulp;
        gulp.open();
        return ActivationResult.success();
    }

    // ------------------------------------------------------------------ ultimate: the maw of the Four Perils

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        Mask worn = mask;
        boolean tasteless = false;
        if (worn != null && ctx().currentTick() < worn.until && worn.enemy.isValid() && !worn.enemy.isDead()
                && !(worn.enemy instanceof Player gone && !ctx().isAlive(gone))) {
            if (worn.enemy instanceof Player face) {
                Ability theirs = ctx().abilityOf(face);
                AbilityInfo info = theirs == null ? null : theirs.info();
                if (info != null && info.ultimateStandalone() && ctx().isEnabled(info.id())) {
                    ActivationResult result = copy(owner, info);
                    if (result.succeeded()) {
                        dropMask(worn, true);
                        copied(owner, face, info);
                    }
                    return result;
                }
            }
            // An ultimate that cannot be copied, or a dummy's face (it has none).
            tasteless = true;
        } else if (worn != null) {
            dropMask(worn, true);
        }
        Maw open = maw;
        if (open != null && !open.released && open.king) {
            return ActivationResult.fail("아가리가 이미 벌어져 있습니다");
        }
        if (!tasteless && !hasPrey(owner)) {
            return ActivationResult.fail("앞쪽 " + AbilityInfo.formatNumber(params().num("maw_range"))
                    + "블록 안에 삼킬 적이 없습니다");
        }
        if (open != null && !open.released) {
            // The small mouth gives way to the great one.
            open.release();
        }
        if (tasteless && worn != null) {
            spitMask(owner, worn);
        }
        Maw king = new Maw(owner, true);
        maw = king;
        king.open();
        if (tasteless) {
            double refund = Math.max(0, params().num("tasteless_refund"));
            // The match empties the gauge right after a successful ultimate: the refund comes a tick later.
            ctx().runLater(owner, 1, () -> ctx().addGauge(owner, refund));
        }
        return ActivationResult.success();
    }

    /** Any visible enemy within the maw's reach in front. */
    private boolean hasPrey(Player owner) {
        double range = Math.max(1, params().num("maw_range"));
        double half = Math.toRadians(Math.clamp(params().num("maw_angle"), 1, 360) / 2);
        for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), range)) {
            if (!isHidden(enemy) && inCone(owner, enemy, half)) {
                return true;
            }
        }
        return false;
    }

    /** Runs the tasted enemy's ultimate as the taotie's own, the gambler's way. */
    private ActivationResult copy(Player owner, AbilityInfo info) {
        try {
            Ability copy = ctx().registry().create(info.id());
            copy.bind(owner, ctx(), ctx().paramsFor(info.id()));
            ActivationResult result = copy.onUltimate();
            return result == null ? ActivationResult.fail("궁극기를 따라 하지 못했습니다") : result;
        } catch (RuntimeException e) {
            ctx().plugin().getLogger().log(Level.WARNING,
                    "Taotie failed to run the ultimate of '" + info.id() + "'", e);
            return ActivationResult.fail("궁극기를 따라 하지 못했습니다");
        }
    }

    private void copied(Player owner, Player face, AbilityInfo info) {
        Component ultimate = Text.mm(info.ultimate().name()).colorIfAbsent(info.color());
        ctx().announce(Text.mm("<" + CRIMSON_TAG + "><b>탐식!</b></" + CRIMSON_TAG + "> <white><owner></white><gray>이(가) "
                        + "</gray><white><face></white><gray>의 궁극기</gray> <ult><gray>를 삼켜 토해 냅니다</gray>",
                Placeholder.component("owner", owner.name()), Placeholder.component("face", face.name()),
                Placeholder.component("ult", ultimate)));
        Location eye = owner.getEyeLocation();
        World world = eye.getWorld();
        Vfx.dot(eye, Particle.FLASH, PALETTE.edge(), true);
        Vfx.burst(ctx(), eye, PALETTE, 0.8, Particle.WAX_OFF, true);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, eye, Vfx.n(ctx(), 16), 0.4, 0.4, 0.4, 0, GREED, true);
        Fx.sound(eye, Sound.ENTITY_PLAYER_BURP, 1.2f, 0.5f);
        Fx.sound(eye, Sound.ENTITY_EVOKER_CAST_SPELL, 1.0f, 0.7f);
    }

    /** "맛이 없다!": the mask is spat out. */
    private void spitMask(Player owner, Mask worn) {
        dropMask(worn, true);
        Location face = owner.getEyeLocation();
        World world = face.getWorld();
        world.spawnParticle(Particle.SPIT, face, Vfx.n(ctx(), 6), 0.1, 0.1, 0.1, 0.1);
        world.spawnParticle(Particle.DUST, face, Vfx.n(ctx(), 8), 0.3, 0.3, 0.3, 0, BRONZE);
        Fx.sound(face, Sound.ENTITY_LLAMA_SPIT, 1.0f, 0.6f);
        Fx.sound(face, Sound.ENTITY_VILLAGER_NO, 0.8f, 0.5f);
        owner.sendActionBar(Text.mm("<" + BRONZE_TAG + "><b>맛이 없다!</b></" + BRONZE_TAG + "> <gray>따라 할 수 없는 "
                + "궁극기입니다 · 게이지 " + AbilityInfo.formatNumber(params().num("tasteless_refund"))
                + "% 반환</gray>"));
    }

    // ------------------------------------------------------------------ the maw

    /** One enemy being digested. */
    private final class Swallowed {
        final LivingEntity enemy;
        final long until;
        final Set<UUID> hiddenFrom = new HashSet<>();
        boolean out;

        Swallowed(LivingEntity enemy, long until) {
            this.enemy = enemy;
            this.until = until;
        }
    }

    /**
     * The maw: a bronze taotie face whose jaws open over the channel and pull in the cone in front. It swallows shots
     * and spells from afar while it is open (a scoped listener on the owner's damage, so a borrowed one works too and
     * lets go when its owner falls or leaves or its jaws are removed). The small one bites the first enemy it gets; the
     * great one swallows them whole and digests them.
     */
    private final class Maw implements LongPredicate, Listener {

        private final Player owner;
        private final boolean king;
        private final long duration;
        private final double range;
        private final double half;
        private final double reach;
        private final float size;
        private final boolean real;
        private final List<BlockDisplay> parts = new ArrayList<>();
        private final float[][] layout;
        private final Map<UUID, double[]> reckoned = new HashMap<>();
        private final Map<UUID, Double> gnawed = new HashMap<>();
        private final Map<UUID, Swallowed> belly = new LinkedHashMap<>();
        private int swallowedShots;
        /** Absorption the great maw has fed the taotie so far (capped over the whole ultimate). */
        private double absorbed;
        private boolean bitten;
        private boolean tasted;
        private boolean closed;
        private boolean released;
        private boolean registered;

        Maw(Player owner, boolean king) {
            this.owner = owner;
            this.king = king;
            this.duration = Math.max(1, params().ticks(king ? "maw_duration" : "gulp_duration"));
            this.range = Math.max(1, params().num(king ? "maw_range" : "gulp_range"));
            this.half = Math.toRadians(Math.clamp(params().num(king ? "maw_angle" : "gulp_angle"), 1, 360) / 2);
            float body = (float) Body.scaleOf(owner);
            this.size = (king ? KING_SIZE : GULP_SIZE) * body;
            this.reach = (king ? KING_REACH : BITE_REACH) * body;
            this.real = ctx().abilityOf(owner) == TaotieAbility.this;
            this.layout = jawLayout();
        }

        void open() {
            double slow = Math.clamp(params().num("gulp_slow") / 100.0, 0.0, 1.0);
            if (slow > 0) {
                ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, -slow, Operation.MULTIPLY_SCALAR_1, duration);
            }
            Material[] materials = {JAW, JAW, TEETH, TEETH, EYE, EYE, HORN, HORN};
            Location feet = Displays.unrotated(owner.getLocation());
            float yaw = owner.getLocation().getYaw();
            for (int i = 0; i < layout.length && reserve(1); i++) {
                BlockDisplay part = keep(Displays.block(ctx(), owner, feet, materials[i].createBlockData(),
                        jawPose(i, yaw, 0f, 0.05f), duration + digestTicks() + 30));
                part.setTeleportDuration(1);
                // The maw hangs right in front of the taotie's eyes: only everyone else sees it.
                owner.hideEntity(ctx().plugin(), part);
                parts.add(part);
            }
            Bukkit.getPluginManager().registerEvents(this, ctx().plugin());
            registered = true;

            Location mouth = mouth();
            World world = mouth.getWorld();
            Vfx.dot(mouth, Particle.FLASH, PALETTE.edge(), king);
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, mouth, Vfx.n(ctx(), king ? 24 : 10), 0.4, 0.3, 0.4, 0,
                    GREED, king);
            Fx.sound(mouth, Sound.ENTITY_BREEZE_INHALE, king ? 1.6f : 1.0f, king ? 0.5f : 0.8f);
            Fx.sound(mouth, Sound.BLOCK_COPPER_DOOR_OPEN, 1.0f, king ? 0.5f : 0.8f);
            if (king) {
                Fx.sound(mouth, Sound.ENTITY_RAVAGER_ROAR, 1.3f, 0.6f);
                Fx.sound(mouth, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 0.7f);
                owner.sendActionBar(Text.mm("<" + CRIMSON_TAG + "><b>사흉의 아가리</b></" + CRIMSON_TAG + "> <gray>"
                        + Fx.seconds(duration) + "초간 모든 것을 삼킵니다</gray>"));
            } else {
                owner.sendActionBar(Text.mm("<" + BRONZE_TAG + "><b>한입 맛보기</b></" + BRONZE_TAG + "> <gray>"
                        + "아가리를 벌립니다…</gray>"));
            }
            Loop.repeat(ctx(), owner, 1, 1, duration + digestTicks() + 4, this, this::release);
        }

        @Override
        public boolean test(long tick) {
            if (released) {
                return false;
            }
            long now = ctx().currentTick();
            if (!closed) {
                if (tick >= duration) {
                    shut();
                } else {
                    channel(tick);
                }
            }
            digest(now);
            if (closed && belly.values().stream().allMatch(swallowed -> swallowed.out)) {
                release();
                return false;
            }
            return true;
        }

        private long digestTicks() {
            return king ? Math.max(1, params().ticks("digest_duration")) : 0;
        }

        // ---------------------------------------------------------------- the channel

        private void channel(long tick) {
            double progress = Math.min(1.0, (tick + 1.0) / duration);
            Location feet = Displays.unrotated(owner.getLocation());
            float yaw = owner.getLocation().getYaw();
            float open = (float) Math.min(1.0, (tick + 1.0) / JAW_OPEN_TICKS);
            for (int i = 0; i < parts.size(); i++) {
                BlockDisplay part = parts.get(i);
                Displays.moveTo(part, feet);
                Displays.animate(part, jawPose(i, yaw, open * (0.85f + 0.15f * (float) progress), 1f), 1);
            }
            Location mouth = mouth();
            double minSpeed = king ? KING_PULL_MIN : PULL_MIN;
            double maxSpeed = king ? KING_PULL_MAX : PULL_MAX;
            double speed = minSpeed + (maxSpeed - minSpeed) * progress;
            long tickEvery = Math.max(1, params().ticks("maw_tick_interval"));
            boolean gnawTick = king && (tick + 1) % tickEvery == 0;
            for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), range)) {
                if (isHidden(enemy) || belly.containsKey(enemy.getUniqueId()) || !inCone(owner, enemy, half)) {
                    continue;
                }
                if (gnawTick) {
                    gnaw(enemy);
                    if (!Combat.isTargetable(ctx(), owner, enemy)) {
                        continue;
                    }
                }
                if (atMouth(enemy, mouth, speed)) {
                    if (king) {
                        if (belly.size() < Math.max(0, params().integer("maw_swallow_max"))) {
                            swallow(enemy);
                            continue;
                        }
                    } else {
                        bite(enemy);
                        return;
                    }
                }
                pull(enemy, mouth, speed);
            }
            if (tick % CONE_PERIOD == 0) {
                drawCone(tick);
            }
            inflow(mouth, progress);
            if (tick % 6 == 0) {
                Fx.sound(mouth, Sound.ENTITY_BREEZE_INHALE, king ? 0.9f : 0.5f, 0.6f + (float) progress * 0.5f);
            }
        }

        /**
         * Where an enemy is once it has followed every pull sent so far (a player's own position trails its client by
         * its ping); the server's position must agree within that lag for a bite to count.
         */
        private boolean atMouth(LivingEntity enemy, Location mouth, double speed) {
            Location at = enemy.getLocation();
            double[] guess = reckoned.get(enemy.getUniqueId());
            double x = enemy instanceof Player && guess != null ? guess[0] : at.getX();
            double z = enemy instanceof Player && guess != null ? guess[1] : at.getZ();
            double guessed = Math.hypot(x - mouth.getX(), z - mouth.getZ());
            double served = Math.hypot(at.getX() - mouth.getX(), at.getZ() - mouth.getZ());
            double slack = enemy instanceof Player player ? speed * (lagTicks(player) + 2) + 0.5 : 0.3;
            double dy = Math.abs(at.getY() + enemy.getHeight() * 0.5 - mouth.getY());
            return guessed <= reach && served <= reach + slack && dy <= VERTICAL_REACH;
        }

        /** The suction (crowd control: ignores knockback resistance). */
        private void pull(LivingEntity enemy, Location mouth, double speed) {
            Location at = enemy.getLocation();
            double[] guess = reckoned.computeIfAbsent(enemy.getUniqueId(), id -> new double[] {at.getX(), at.getZ()});
            if (!(enemy instanceof Player)) {
                guess[0] = at.getX();
                guess[1] = at.getZ();
            }
            Vector to = new Vector(mouth.getX() - guess[0], 0, mouth.getZ() - guess[1]);
            double distance = to.length();
            if (distance < 1.0E-3) {
                return;
            }
            double step = Math.min(speed, Math.max(0.05, (distance - reach * 0.6) * 0.5));
            Vector velocity = to.multiply(step / distance);
            boolean grounded = Motion.isGrounded(enemy) || enemy.isInWater();
            velocity.setY(grounded ? PULL_HOP : Math.clamp((mouth.getY() - 1 - at.getY()) * 0.1, -0.3, 0.1));
            Vector sent = Motion.clamp(velocity, MAX_SPEED);
            guess[0] += sent.getX();
            guess[1] += sent.getZ();
            enemy.setVelocity(sent);
            enemy.setFallDistance(0);
            if (ThreadLocalRandom.current().nextInt(3) == 0) {
                Vfx.trail(Fx.body(enemy), mouth, INFLOW, 6, false);
            }
        }

        private void gnaw(LivingEntity enemy) {
            double cap = Math.max(0, params().num("maw_tick_max"));
            double done = gnawed.getOrDefault(enemy.getUniqueId(), 0.0);
            double hit = Math.min(Math.max(0, params().num("maw_tick_damage")), cap - done);
            if (hit <= 1.0E-6) {
                return;
            }
            gnawed.put(enemy.getUniqueId(), done + hit);
            Combat.hit(ctx(), owner, enemy, hit);
            Location body = Fx.body(enemy);
            body.getWorld().spawnParticle(Particle.CRIT, body, Vfx.n(ctx(), 4), 0.25, 0.3, 0.25, 0.1);
            Fx.sound(body, Sound.ENTITY_PHANTOM_BITE, 0.5f, 1.0f + Fx.jitter(0.1f));
        }

        // ---------------------------------------------------------------- the small maw: a bite

        private void bite(LivingEntity enemy) {
            bitten = true;
            Location body = Fx.body(enemy);
            Combat.hit(ctx(), owner, enemy, params().num("bite_damage"));
            World world = body.getWorld();
            Vfx.dot(body, Particle.FLASH, PALETTE.core(), false);
            Vfx.burst(ctx(), body, PALETTE, 0.6, Particle.CRIT, false);
            Fx.sound(body, Sound.ENTITY_EVOKER_FANGS_ATTACK, 1.1f, 0.7f + Fx.jitter(0.06f));
            Fx.sound(body, Sound.ENTITY_GENERIC_EAT, 1.0f, 0.6f + Fx.jitter(0.06f));
            Fx.sound(body, Sound.BLOCK_COPPER_DOOR_CLOSE, 1.0f, 0.7f);
            shut();
            if (!Combat.isTargetable(ctx(), owner, enemy)) {
                return;
            }
            // Spat out behind the taotie, over its head.
            Vector back = Motion.horizontalFacing(owner.getLocation()).multiply(-SPIT_BACK).setY(SPIT_BACK_LIFT);
            enemy.setVelocity(Motion.clamp(back, MAX_SPEED));
            enemy.setFallDistance(0);
            world.spawnParticle(Particle.SPIT, body, Vfx.n(ctx(), 6), 0.2, 0.2, 0.2, 0.08);
            Fx.sound(body, Sound.ENTITY_LLAMA_SPIT, 1.0f, 0.8f + Fx.jitter(0.08f));
            if (!tasted) {
                tasted = true;
                if (real) {
                    taste(enemy);
                }
            }
        }

        // ---------------------------------------------------------------- the great maw: swallowed whole

        private void swallow(LivingEntity enemy) {
            long digestTicks = Math.max(1, params().ticks("digest_duration"));
            Swallowed swallowed = new Swallowed(enemy, ctx().currentTick() + digestTicks);
            belly.put(enemy.getUniqueId(), swallowed);
            Location body = Fx.body(enemy);
            World world = body.getWorld();
            Vfx.dot(body, Particle.FLASH, PALETTE.edge(), true);
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, body, Vfx.n(ctx(), 14), 0.3, 0.5, 0.3, 0, GREED, true);
            Vfx.trail(body, mouth(), INFLOW, 4, true);
            Fx.sound(body, Sound.ENTITY_GENERIC_EAT, 1.4f, 0.4f);
            Fx.sound(body, Sound.ENTITY_EVOKER_FANGS_ATTACK, 1.2f, 0.5f);
            enemy.setVelocity(new Vector());
            Combat.hit(ctx(), owner, enemy, params().num("digest_damage"));
            if (!Combat.isTargetable(ctx(), owner, enemy)) {
                swallowed.out = true;
                return;
            }
            for (Player viewer : enemy.getWorld().getPlayers()) {
                if (viewer.equals(enemy) || !viewer.canSee(enemy)) {
                    continue;
                }
                if (enemy instanceof Player victim) {
                    viewer.hidePlayer(ctx().plugin(), victim);
                } else {
                    viewer.hideEntity(ctx().plugin(), enemy);
                }
                swallowed.hiddenFrom.add(viewer.getUniqueId());
            }
            ctx().addModifier(owner, enemy, Attribute.MOVEMENT_SPEED, -1.0, Operation.MULTIPLY_SCALAR_1,
                    digestTicks + 2);
            ctx().addModifier(owner, enemy, Attribute.JUMP_STRENGTH, -1.0, Operation.MULTIPLY_SCALAR_1,
                    digestTicks + 2);
            if (enemy instanceof Player victim) {
                ctx().silence(victim, digestTicks);
                victim.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, (int) digestTicks + 10, 0, false,
                        false, true));
                victim.sendActionBar(Text.mm("<" + CRIMSON_TAG + "><b>도철에게 삼켜졌습니다!</b></" + CRIMSON_TAG
                        + "> <gray>소화되는 중…</gray>"));
            }
            if (!tasted) {
                tasted = true;
                if (real) {
                    taste(enemy);
                }
            }
        }

        /** Swallowed enemies sit still in the dark; when their time is up they are digested and spat out. */
        private void digest(long now) {
            for (Swallowed swallowed : belly.values()) {
                if (swallowed.out) {
                    continue;
                }
                LivingEntity enemy = swallowed.enemy;
                if (!enemy.isValid() || enemy.isDead()) {
                    letOut(swallowed);
                    continue;
                }
                enemy.setVelocity(new Vector(0, Math.min(0, -Motion.GRAVITY), 0));
                enemy.setFallDistance(0);
                if (now >= swallowed.until) {
                    melt(enemy);
                    spit(swallowed);
                } else if ((swallowed.until - now) % 5 == 0) {
                    // The belly churns and the closed jaws chew.
                    chew((swallowed.until - now) % 10 == 0 ? 0.18f : 0f);
                    Location mouth = mouth();
                    mouth.getWorld().spawnParticle(Particle.DUST, mouth, Vfx.n(ctx(), 3), 0.3, 0.2, 0.3, 0, CRIMSON);
                    Fx.sound(mouth, Sound.ENTITY_STRIDER_EAT, 0.7f, 0.5f + Fx.jitter(0.05f));
                }
            }
        }

        /** The closed jaws move with the taotie and chew ({@code open} a little ajar). */
        private void chew(float open) {
            Location feet = Displays.unrotated(owner.getLocation());
            float yaw = owner.getLocation().getYaw();
            for (int i = 0; i < parts.size(); i++) {
                BlockDisplay part = parts.get(i);
                Displays.moveTo(part, feet);
                Displays.animate(part, jawPose(i, yaw, open, 1f), 3);
            }
        }

        /** Digestion: good potion effects and absorption melt (the absorption feeds the taotie), the gauge is eaten. */
        private void melt(LivingEntity enemy) {
            List<String> melted = new ArrayList<>();
            for (PotionEffect effect : List.copyOf(enemy.getActivePotionEffects())) {
                if (effect.getType().getEffectCategory() == PotionEffectType.Category.BENEFICIAL) {
                    enemy.removePotionEffect(effect.getType());
                    melted.add(effect.getType().getKey().getKey());
                }
            }
            double absorption = enemy.getAbsorptionAmount();
            double fed = Math.max(0, Math.min(absorption, Math.max(0, params().num("digest_absorb_max")) - absorbed));
            absorbed += fed;
            if (absorption > 0) {
                enemy.setAbsorptionAmount(0);
            }
            if (fed > 0 && ctx().isAlive(owner)) {
                long keep = Math.max(1, params().ticks("digest_absorb_duration"));
                ctx().addModifier(owner, owner, Attribute.MAX_ABSORPTION, fed, Operation.ADD_NUMBER, keep);
                owner.setAbsorptionAmount(owner.getAbsorptionAmount() + fed);
                // When the borrowed maximum runs out, the absorption above it goes too.
                ctx().runLater(owner, keep + 1, () -> owner.setAbsorptionAmount(owner.getAbsorptionAmount()));
            }
            double gauge = Math.max(0, params().num("digest_gauge"));
            if (enemy instanceof Player victim && gauge > 0) {
                ctx().addGauge(victim, -gauge);
            }
            owner.sendActionBar(Text.mm("<" + CRIMSON_TAG + ">소화</" + CRIMSON_TAG + "> <white><name></white> <gray>"
                    + (melted.isEmpty() ? "" : "효과 " + melted.size() + "개 · ") + (fed > 0 ? "흡수 +"
                    + AbilityInfo.formatNumber(Math.round(fed * 10) / 10.0) + " · " : "") + "게이지 -"
                    + AbilityInfo.formatNumber(gauge) + "%</gray>", Placeholder.component("name", enemy.name())));
        }

        /** Out of the mouth, forward, covered in slobber. */
        private void spit(Swallowed swallowed) {
            LivingEntity enemy = swallowed.enemy;
            Vector forward = Motion.horizontalFacing(owner.getLocation());
            Location out = owner.getLocation().add(forward.clone().multiply(1.2 * Body.scaleOf(owner)));
            out.setYaw(enemy.getLocation().getYaw());
            out.setPitch(enemy.getLocation().getPitch());
            Spots.snapToGround(out.getWorld(), out, 2);
            if (ctx().isAlive(owner) && Spots.isSafe(ctx(), out, Body.standing(enemy))
                    && Spots.clearPath(owner.getEyeLocation(), out.clone().add(0, 1, 0))) {
                enemy.teleport(out);
            }
            letOut(swallowed);
            if (!ctx().isAlive(owner) || !Combat.isTargetable(ctx(), owner, enemy)) {
                return;
            }
            Combat.hit(ctx(), owner, enemy, params().num("spit_damage"));
            if (!Combat.isTargetable(ctx(), owner, enemy)) {
                return;
            }
            enemy.setVelocity(Motion.clamp(forward.clone().multiply(SPIT_FORWARD).setY(SPIT_FORWARD_LIFT), MAX_SPEED));
            long slobber = Math.max(0, params().ticks("slobber_duration"));
            double slow = Math.clamp(params().num("slobber_slow") / 100.0, 0.0, 1.0);
            if (slobber > 0) {
                enemy.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, (int) slobber, 0, false, false, true));
                if (slow > 0) {
                    ctx().addModifier(owner, enemy, Attribute.MOVEMENT_SPEED, -slow, Operation.MULTIPLY_SCALAR_1,
                            slobber);
                }
                drip(enemy, slobber);
            }
            Location body = Fx.body(enemy);
            World world = body.getWorld();
            world.spawnParticle(Particle.SPIT, body, Vfx.n(ctx(), 12), 0.3, 0.4, 0.3, 0.1);
            world.spawnParticle(Particle.DUST, body, Vfx.n(ctx(), 10), 0.3, 0.5, 0.3, 0, SLOBBER);
            Fx.sound(body, Sound.ENTITY_LLAMA_SPIT, 1.3f, 0.5f);
            Fx.sound(body, Sound.ENTITY_PLAYER_BURP, 1.0f, 0.6f);
            Fx.sound(body, Sound.BLOCK_SLIME_BLOCK_FALL, 1.0f, 0.8f);
            if (enemy instanceof Player victim) {
                victim.sendActionBar(Text.mm("<" + PATINA_TAG + ">퉤!</" + PATINA_TAG + "> <gray>침 범벅이 되었습니다</gray>"));
            }
        }

        /** Slobber drips off the spat-out enemy for a while. */
        private void drip(LivingEntity enemy, long ticks) {
            Loop.repeat(ctx(), owner, 3, 3, Math.max(1, ticks / 3), run -> {
                if (!Combat.isTargetable(ctx(), owner, enemy)) {
                    return false;
                }
                if (!isHidden(enemy)) {
                    Location body = enemy.getLocation().add(0, enemy.getHeight() * 0.7, 0);
                    body.getWorld().spawnParticle(Particle.DUST, body, Vfx.n(ctx(), 2), 0.25, 0.3, 0.25, 0, SLOBBER);
                    body.getWorld().spawnParticle(Particle.DRIPPING_WATER, body, 1, 0.2, 0.2, 0.2, 0);
                }
                return true;
            });
        }

        /** Shows a swallowed enemy again (it is digested, its owner fell, or the maw let go). */
        private void letOut(Swallowed swallowed) {
            if (swallowed.out) {
                return;
            }
            swallowed.out = true;
            LivingEntity enemy = swallowed.enemy;
            for (UUID id : swallowed.hiddenFrom) {
                Player viewer = Bukkit.getPlayer(id);
                if (viewer == null) {
                    continue;
                }
                if (enemy instanceof Player victim) {
                    viewer.showPlayer(ctx().plugin(), victim);
                } else {
                    viewer.showEntity(ctx().plugin(), enemy);
                }
            }
            swallowed.hiddenFrom.clear();
        }

        // ---------------------------------------------------------------- swallowing shots

        /** Shots and spells from afar fly into the open mouth. */
        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onHurt(EntityDamageEvent event) {
            if (released || closed || !event.getEntity().equals(owner)
                    || !(event instanceof EntityDamageByEntityEvent byEntity)) {
                return;
            }
            try {
                gulpShot(event, byEntity);
            } catch (RuntimeException e) {
                ctx().plugin().getLogger().log(Level.WARNING, "Taotie maw failed", e);
            }
        }

        private void gulpShot(EntityDamageEvent event, EntityDamageByEntityEvent byEntity) {
            Entity damager = byEntity.getDamager();
            boolean shot = event.getCause() == DamageCause.PROJECTILE && damager instanceof Projectile;
            if (!shot && !ctx().isAbilityDamage()) {
                return;
            }
            Entity causing = event.getDamageSource().getCausingEntity();
            Player attacker = ctx().resolveAttacker(causing != null ? causing : damager);
            Location from;
            if (attacker != null && !attacker.equals(owner)) {
                from = attacker.getLocation();
            } else if (shot) {
                from = damager.getOrigin();
            } else {
                return;
            }
            if (from == null || !from.getWorld().equals(owner.getWorld())) {
                return;
            }
            Location at = owner.getLocation();
            double distance = Math.hypot(from.getX() - at.getX(), from.getZ() - at.getZ());
            Vector to = Motion.horizontal(from.toVector().subtract(at.toVector()));
            if (distance < Math.max(0, params().num("swallow_distance"))
                    || to.dot(Motion.horizontalFacing(at)) < 0) {
                return;
            }
            event.setCancelled(true);
            swallowedShots++;
            if (shot && damager instanceof AbstractArrow arrow && !(arrow instanceof Trident)) {
                arrow.remove();
            }
            Location mouth = mouth();
            World world = mouth.getWorld();
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, mouth, Vfx.n(ctx(), 8), 0.25, 0.2, 0.25, 0,
                    BRONZE_GLOW);
            world.spawnParticle(Particle.SMOKE, mouth, Vfx.n(ctx(), 3), 0.15, 0.1, 0.15, 0.01);
            Fx.sound(mouth, Sound.ENTITY_GENERIC_DRINK, 1.0f, 0.5f + Fx.jitter(0.05f));
            Fx.sound(mouth, Sound.BLOCK_COPPER_HIT, 0.8f, 0.6f + Fx.jitter(0.05f));
            owner.sendActionBar(Text.mm("<" + BRONZE_TAG + ">꿀꺽!</" + BRONZE_TAG + "> <gray>먼 곳의 공격을 삼켰습니다 ×"
                    + swallowedShots + "</gray>"));
        }

        /** Swallowed enemies cannot hit anyone from inside the belly. */
        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        public void onBellyBlow(EntityDamageByEntityEvent event) {
            if (released || belly.isEmpty()) {
                return;
            }
            Entity causing = event.getDamageSource().getCausingEntity();
            Entity source = causing != null ? causing : event.getDamager();
            Swallowed swallowed = source == null ? null : belly.get(source.getUniqueId());
            if (swallowed != null && !swallowed.out) {
                event.setCancelled(true);
            }
        }

        /**
         * The maw's displays are only removed early when the context releases its owner (death, quitting, the match
         * ending): the maw is over then, also for a borrowed one, which never sees a revoke. (They stay up while anyone
         * is still being digested.)
         */
        @EventHandler(priority = EventPriority.MONITOR)
        public void onRemove(EntityRemoveEvent event) {
            if (!released && parts.stream().anyMatch(part -> part.getUniqueId().equals(event.getEntity()
                    .getUniqueId()))) {
                release();
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onOwnerDeath(PlayerDeathEvent event) {
            if (event.getEntity().equals(owner)) {
                release();
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onOwnerQuit(PlayerQuitEvent event) {
            if (event.getPlayer().equals(owner)) {
                release();
            }
        }

        // ---------------------------------------------------------------- closing

        /** The jaws snap shut (bite, time up, or the maw let go). */
        private void shut() {
            if (closed) {
                return;
            }
            closed = true;
            float yaw = owner.getLocation().getYaw();
            for (int i = 0; i < parts.size(); i++) {
                Displays.animate(parts.get(i), jawPose(i, yaw, 0f, 1f), JAW_SHUT_TICKS);
            }
            if (belly.isEmpty()) {
                for (BlockDisplay part : parts) {
                    ctx().runLater(owner, JAW_SHUT_TICKS + 3, () -> {
                        if (part.isValid()) {
                            Displays.shrinkAway(ctx(), owner, part, 4);
                        }
                    });
                }
            }
            Location mouth = mouth();
            Fx.sound(mouth, Sound.BLOCK_COPPER_DOOR_CLOSE, 1.0f, king ? 0.5f : 0.8f);
            mouth.getWorld().spawnParticle(Particle.DUST, mouth, Vfx.n(ctx(), 6), 0.3, 0.2, 0.3, 0, BRONZE);
            if (!king && !bitten) {
                double refund = (1 - Math.clamp(params().num("miss_cooldown") / 100.0, 0.0, 1.0))
                        * params().cooldownSeconds();
                if (refund > 0 && ctx().isAlive(owner)) {
                    ctx().reduceCooldown(owner, refund);
                }
                owner.sendActionBar(Text.mm("<gray>아무것도 물지 못했습니다</gray>" + (swallowedShots > 0 ? " <"
                        + BRONZE_TAG + ">(삼킨 공격 ×" + swallowedShots + ")</" + BRONZE_TAG + ">" : "")));
            }
        }

        /** Lets go of everything: the listener, and whoever is still in the belly. */
        void release() {
            if (released) {
                return;
            }
            released = true;
            closed = true;
            if (registered) {
                HandlerList.unregisterAll(this);
                registered = false;
            }
            for (Swallowed swallowed : belly.values()) {
                letOut(swallowed);
            }
            if (maw == this) {
                maw = null;
            }
            for (BlockDisplay part : parts) {
                if (part.isValid()) {
                    Displays.shrinkAway(ctx(), owner, part, 3);
                }
            }
        }

        // ---------------------------------------------------------------- looks

        /** The middle of the open mouth, between the jaws. */
        private Location mouth() {
            Location feet = owner.getLocation();
            Vector forward = Motion.horizontalFacing(feet);
            return feet.add(forward.multiply(MOUTH_FORWARD * size)).add(0, owner.getEyeHeight() - MOUTH_DROP * size,
                    0);
        }

        /**
         * The jaw parts in the head's horizontal frame (yaw only): x left, y up from the feet, z forward. Each row:
         * hinge (x, y, z), corner offset from the hinge (x, y, z), size (x, y, z), and how the part opens (+1 with the
         * upper jaw, -1 with the lower one, 0 fixed).
         */
        private float[][] jawLayout() {
            float s = size;
            float eye = (float) owner.getEyeHeight();
            float upper = eye - 0.1f * s;
            float lower = eye - 0.42f * s;
            float depth = 0.85f * s;
            float width = 0.95f * s;
            return new float[][] {
                    // upper jaw, lower jaw
                    {0, upper, 0.2f * s, -width / 2, 0, 0, width, 0.14f * s, depth, 1},
                    {0, lower, 0.2f * s, -width / 2, -0.14f * s, 0, width, 0.14f * s, depth, -1},
                    // upper teeth hanging down at the front, lower teeth standing up
                    {0, upper, 0.2f * s, -width * 0.45f, -0.12f * s, depth - 0.12f * s, width * 0.9f, 0.12f * s,
                            0.08f * s, 1},
                    {0, lower, 0.2f * s, -width * 0.45f, 0, depth - 0.12f * s, width * 0.9f, 0.12f * s, 0.08f * s, -1},
                    // crimson eyes on the brow above the upper jaw
                    {0, upper, 0.2f * s, 0.12f * s, 0.16f * s, 0.18f * s, 0.16f * s, 0.12f * s, 0.06f * s, 1},
                    {0, upper, 0.2f * s, -0.28f * s, 0.16f * s, 0.18f * s, 0.16f * s, 0.12f * s, 0.06f * s, 1},
                    // curled horns
                    {0, upper, 0.2f * s, 0.36f * s, 0.12f * s, 0.05f * s, 0.08f * s, 0.42f * s, 0.08f * s, 1},
                    {0, upper, 0.2f * s, -0.44f * s, 0.12f * s, 0.05f * s, 0.08f * s, 0.42f * s, 0.08f * s, 1},
            };
        }

        private Transformation jawPose(int index, float yaw, float open, float grow) {
            float[] p = layout[index];
            float pitch = -p[9] * JAW_OPEN * open;
            float g = Math.max(0.001f, grow);
            return hinged(yaw, pitch, p[0], p[1], p[2], p[3] * g, p[4] * g, p[5] * g, p[6] * g, p[7] * g, p[8] * g);
        }

        /** The cone on the ground: its two edges and its far arc. */
        private void drawCone(long tick) {
            Location at = owner.getLocation().add(0, 0.12, 0);
            float yaw = at.getYaw();
            double degrees = Math.toDegrees(half * 2);
            int points = (int) Math.max(8, Math.min(30, range * degrees / 25));
            Vfx.arc(ctx(), at, range, yaw, degrees, points, Particle.DUST_COLOR_TRANSITION, GREED, king);
            Vfx.arc(ctx(), at, range * 0.55, yaw, degrees, Math.max(5, points / 2), Particle.DUST, BRONZE, king);
            for (int side = -1; side <= 1; side += 2) {
                Vector edge = Motion.horizontalFacing(at).rotateAroundY(-side * half);
                Vfx.line(ctx(), at.clone().add(edge.clone().multiply(0.8)), at.clone().add(edge.multiply(range)),
                        king ? 1.2 : 1.6, Particle.DUST, CRIMSON, king);
            }
        }

        /** Motes stream from all over the cone into the mouth, more as the suction builds. */
        private void inflow(Location mouth, double progress) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            Location at = owner.getLocation();
            int motes = Vfx.n(ctx(), (int) Math.round((king ? 4 : 2) * (0.5 + progress)));
            for (int i = 0; i < motes; i++) {
                double angle = random.nextDouble(-half, half);
                double distance = random.nextDouble(range * 0.4, range);
                Vector direction = Motion.horizontalFacing(at).rotateAroundY(angle).multiply(distance);
                Location from = at.clone().add(direction).add(0, random.nextDouble(0.2, 2.0), 0);
                Vfx.trail(from, mouth, random.nextBoolean() ? INFLOW : INFLOW_BRONZE,
                        (int) Math.max(4, distance * 1.6), king);
            }
        }
    }

    /** True if {@code enemy} is inside the horizontal cone of half-angle {@code half} in front of {@code owner}. */
    private static boolean inCone(Player owner, LivingEntity enemy, double half) {
        Location at = owner.getLocation();
        Vector to = enemy.getLocation().toVector().subtract(at.toVector());
        if (Math.abs(to.getY()) > VERTICAL_REACH) {
            return false;
        }
        Vector flat = Motion.horizontal(to);
        if (flat.lengthSquared() < 1.0E-6) {
            return true;
        }
        double cos = flat.dot(Motion.horizontalFacing(at));
        return cos >= Math.cos(half) - 1.0E-9 || Math.hypot(to.getX(), to.getZ()) < 0.8;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * A block display box of size (sx, sy, sz) centered on the local point (lx, ly, lz) of a frame turned by
     * {@code yaw} (0 = facing +Z, local +X is the left hand side) and tilted by {@code pitch} (positive = down), the
     * box itself rolled by {@code roll} around its own center. All of it lives in the transformation.
     */
    private static Transformation piece(float yaw, float pitch, float lx, float ly, float lz, float sx, float sy,
                                        float sz, float roll) {
        Quaternionf frame = new Quaternionf().rotateY((float) Math.toRadians(-yaw))
                .rotateX((float) Math.toRadians(pitch));
        Quaternionf rotation = new Quaternionf(frame).rotateZ((float) Math.toRadians(roll));
        Vector3f translation = frame.transform(new Vector3f(lx, ly, lz))
                .add(new Quaternionf(rotation).transform(new Vector3f(-sx / 2, -sy / 2, -sz / 2)));
        return new Transformation(translation, rotation, new Vector3f(sx, sy, sz), new Quaternionf());
    }

    /**
     * A box hinged at (hx, hy, hz) of a frame turned by {@code yaw}: the box's corner sits at (cx, cy, cz) from the
     * hinge in the jaw's own frame, which is tilted by {@code pitch} around the hinge (positive = down).
     */
    private static Transformation hinged(float yaw, float pitch, float hx, float hy, float hz, float cx, float cy,
                                         float cz, float sx, float sy, float sz) {
        Quaternionf frame = new Quaternionf().rotateY((float) Math.toRadians(-yaw));
        Quaternionf rotation = new Quaternionf(frame).rotateX((float) Math.toRadians(pitch));
        Vector3f translation = frame.transform(new Vector3f(hx, hy, hz))
                .add(new Quaternionf(rotation).transform(new Vector3f(cx, cy, cz)));
        return new Transformation(translation, rotation, new Vector3f(sx, sy, sz), new Quaternionf());
    }

    private static long lagTicks(Player player) {
        return Math.min(MAX_LAG_TICKS, (long) Math.ceil(Math.max(0, player.getPing()) / 50.0));
    }

    /** True for a player the taotie cannot see (stealth): no visual may give it away. */
    private boolean isHidden(LivingEntity entity) {
        return entity instanceof Player hidden && !player().canSee(hidden);
    }

    private <T extends Display> T keep(T display) {
        displays.add(display);
        return display;
    }

    private boolean reserve(int count) {
        displays.removeIf(display -> !display.isValid());
        return displays.size() + count <= MAX_DISPLAYS;
    }
}
