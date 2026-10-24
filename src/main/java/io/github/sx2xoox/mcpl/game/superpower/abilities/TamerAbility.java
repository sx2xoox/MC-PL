package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Body;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Inputs;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Motion;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.game.superpower.ability.InputKey;
import io.github.sx2xoox.mcpl.game.superpower.ability.Prompt;
import io.github.sx2xoox.mcpl.game.superpower.ability.PromptHandle;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
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
import org.bukkit.block.BlockFace;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 몬스터 수집가 — the collector of the creature-catching RPG genre: the weaker the prey, the likelier the catch. The
 * passive reads the prey (a health bar and a catch rate over every enemy it hits, for its eyes only) and bends the
 * capsule towards badly hurt ones. The active throws a capsule that swallows an enemy: three shakes, each one a chance
 * to break free (better at full health, and better the harder the prisoner mashes jump); held through all three, the
 * capsule comes back to the collector's hand, to be thrown where it likes (a sneak) or dropped at its feet. The ultimate
 * drops a giant capsule from the sky that swallows everyone around where it lands, shakes them all and bursts.
 *
 * <p>Look (capsule sky blue, gold rim, button white): a capsule of block displays (sky-blue top, white bottom, a gold
 * band and a white button) tumbling along its arc with a gold trail, wobbling with a clunk at every shake while its
 * button flashes; stars and chimes for a catch, the lid flying off for an escape. The legendary capsule casts a growing
 * shadow before it lands with a shockwave, the prisoners' light streams into it, and it bursts in a flash of sky blue and
 * gold.
 */
public final class TamerAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("tamer", "몬스터 수집가", Material.FIREWORK_STAR)
            .color(TextColor.color(0x4FB6E8))
            .difficulty(1)
            .passive("몬스터 정보",
                    "내가 맞힌 적의 머리 위에 {info_duration}초간 체력 바와 '포획률'(체력이 낮을수록 높음)이 나에게만 "
                            + "보입니다. 체력이 {homing_health}% 이하인 적에게는 캡슐이 살짝 휘어 날아갑니다.")
            .active("포획 캡슐",
                    "캡슐을 던져({throw_range}블록) 맞은 적을 가둡니다(갇힌 동안 피해를 받지 않음). 캡슐은 {shakes}번 "
                            + "흔들리고 그때마다 탈출할 수 있습니다(체력이 낮을수록 어렵고, 점프를 연타하면 쉬워짐). 탈출하면 "
                            + "{escape_damage} 피해만 받고, 끝까지 잡은 적은 웅크려 바라보는 곳에 던지거나 내 앞에 풀어 "
                            + "{release_damage} 피해를 주고 {stagger_duration}초간 휘청이게 합니다. 빗나가면 쿨타임이 "
                            + "{miss_refund}% 돌아옵니다.",
                    11)
            .ultimate("전설의 포획",
                    "조준한 곳({legend_range}블록)에 {legend_fall}초 뒤 거대한 캡슐이 떨어져 반경 {legend_radius}블록의 적을 "
                            + "모두 가두고 {shakes}번 흔들립니다(적마다 탈출 판정, 체력이 낮을수록 잘 잡힘). 탈출한 적은 "
                            + "{legend_escape_damage} 피해를 입고 튕겨 나가며, 끝까지 잡힌 적은 {legend_burst_delay}초 뒤 "
                            + "캡슐이 터져 {legend_damage} 피해를 입습니다.")
            .param("info_duration", 6)
            .param("homing_health", 30)
            .param("throw_range", 18)
            .param("shake_interval", 0.5)
            .param("shakes", 3)
            .param("escape_full", 45)
            .param("escape_low", 10)
            .param("low_health", 25)
            .param("mash_bonus", 3)
            .param("mash_max", 15)
            .param("escape_damage", 1)
            .param("hold_time", 1)
            .param("release_range", 18)
            .param("release_damage", 3)
            .param("stagger_duration", 1)
            .param("stagger_slow", 50)
            .param("miss_refund", 40)
            .param("legend_range", 24)
            .param("legend_fall", 1)
            .param("legend_radius", 5)
            .param("legend_escape_damage", 3)
            .param("legend_burst_delay", 1.5)
            .param("legend_damage", 6)
            .build();

    private static final String SKY_TAG = "#4FB6E8";
    private static final String GOLD_TAG = "#E2B33C";

    // ------------------------------------------------------------------ look
    /** Button white, capsule sky blue, gold rim. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xF7F7F7, 0x4FB6E8, 0xE2B33C);
    private static final Color SKY = Color.fromRGB(0x4FB6E8);
    private static final Color GOLD = Color.fromRGB(0xE2B33C);
    private static final Particle.DustOptions SKY_DUST = new Particle.DustOptions(SKY, 1.0f);
    private static final Particle.DustOptions GOLD_DUST = new Particle.DustOptions(GOLD, 0.8f);
    private static final Particle.DustOptions SHADOW_DUST = new Particle.DustOptions(Color.fromRGB(0x23262B), 1.6f);
    private static final Particle.DustTransition GLEAM = new Particle.DustTransition(Color.fromRGB(0xF7F7F7), SKY,
            1.0f);

    private static final Material TOP = Material.LIGHT_BLUE_CONCRETE;
    private static final Material BOTTOM = Material.WHITE_CONCRETE;
    private static final Material BAND = Material.GOLD_BLOCK;
    private static final Material BUTTON = Material.QUARTZ_BLOCK;
    private static final Material BUTTON_LIT = Material.SEA_LANTERN;

    /** One capsule part in capsule space (a unit capsule: 1 block across), x right, y up, z front. */
    private record Part(Material material, float cx, float cy, float cz, float sx, float sy, float sz) {
    }

    private static final Part[] CAPSULE = {
            new Part(TOP, 0, 0.25f, 0, 1.0f, 0.5f, 1.0f),
            new Part(BOTTOM, 0, -0.25f, 0, 1.0f, 0.5f, 1.0f),
            new Part(BAND, 0, 0, 0, 1.04f, 0.12f, 1.04f),
            new Part(BUTTON, 0, 0, 0.53f, 0.3f, 0.3f, 0.06f),
    };
    private static final int BUTTON_PART = 3;

    private static final int MAX_DISPLAYS = 24;
    private static final double MAX_SPEED = 2.0;
    private static final double THROW_SPEED = 1.3;
    private static final double THROW_GRAVITY = 0.045;
    private static final double THROW_DRAG = 0.99;
    private static final double HOMING = 0.18;
    private static final double HOMING_CONE = Math.toRadians(35);
    private static final float FLIGHT_SIZE = 0.38f;
    private static final float HELD_SIZE = 0.9f;
    private static final float HAND_SIZE = 0.32f;
    private static final float LEGEND_SIZE = 3.4f;
    private static final double LEGEND_HEIGHT = 12;
    private static final double SHRINK = -0.9;
    private static final double DRIFT = 0.4;
    private static final double REST_FALL = -0.0784;
    private static final long INFO_REFRESH = 5;
    private static final long SILENCE_TICKS = 4;
    private static final int MAX_INFOS = 5;

    // ------------------------------------------------------------------ state

    private final List<Display> live = new ArrayList<>();
    private final Map<UUID, Info> infos = new LinkedHashMap<>();
    private final Map<UUID, Capture> captures = new HashMap<>();
    private @Nullable CaptureRules rules;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        for (Capture capture : List.copyOf(captures.values())) {
            capture.abort();
        }
        captures.clear();
        stopRules();
        for (Info info : infos.values()) {
            info.remove();
        }
        infos.clear();
        live.clear();
    }

    /** True while {@code entity} is inside one of this collector's capsules. */
    public boolean isCaptured(Entity entity) {
        return captures.containsKey(entity.getUniqueId());
    }

    // ================================================================== passive: monster info

    /** The prey's chance to break free at one shake, from its health (mashing aside). */
    private double escapeChance(LivingEntity prey) {
        double fraction = Math.clamp(prey.getHealth() / Combat.maxHealth(prey), 0.0, 1.0);
        double low = Math.clamp(params().num("low_health") / 100.0, 0.0, 0.99);
        double full = Math.clamp(params().num("escape_full") / 100.0, 0.0, 1.0);
        double weak = Math.clamp(params().num("escape_low") / 100.0, 0.0, 1.0);
        double t = Math.clamp((fraction - low) / (1 - low), 0.0, 1.0);
        return weak + (full - weak) * t;
    }

    /** The chance to hold the prey through every shake, in percent. */
    private int catchRate(LivingEntity prey) {
        int shakes = Math.max(1, (int) Math.round(params().num("shakes")));
        return (int) Math.round(Math.pow(1 - escapeChance(prey), shakes) * 100);
    }

    private boolean weakened(LivingEntity prey) {
        return prey.getHealth() / Combat.maxHealth(prey) <= Math.max(0, params().num("homing_health")) / 100.0 + 1.0E-9;
    }

    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        Player owner = player();
        if (!ctx().isEnemy(owner, victim)) {
            return;
        }
        long until = ctx().currentTick() + Math.max(1, params().ticks("info_duration"));
        Info info = infos.get(victim.getUniqueId());
        if (info == null) {
            if (infos.size() >= MAX_INFOS) {
                UUID oldest = infos.keySet().iterator().next();
                infos.remove(oldest).remove();
            }
            info = new Info(victim);
            infos.put(victim.getUniqueId(), info);
        }
        info.until = until;
        info.dirty = true;
    }

    @Override
    public void onTick(long tick) {
        Player owner = player();
        long now = ctx().currentTick();
        for (var it = infos.values().iterator(); it.hasNext(); ) {
            Info info = it.next();
            if (now > info.until || !info.prey.isValid() || info.prey.isDead() || !ctx().isEnemy(owner, info.prey)) {
                info.remove();
                it.remove();
                continue;
            }
            info.follow(owner, tick);
        }
    }

    /** The owner-only readout over one enemy's head: a health bar and the catch rate. */
    private final class Info {
        private final LivingEntity prey;
        private long until;
        private boolean dirty = true;
        private @Nullable TextDisplay panel;

        Info(LivingEntity prey) {
            this.prey = prey;
        }

        void follow(Player owner, long tick) {
            if (ctx().isConcealedFrom(prey, owner) || isCaptured(prey)) {
                remove();
                return;
            }
            Location at = Displays.unrotated(prey.getLocation().add(0, prey.getHeight() + 0.75, 0));
            TextDisplay shown = panel;
            if (shown == null || !shown.isValid()) {
                if (!reserve(1)) {
                    return;
                }
                shown = keep(Displays.text(ctx(), owner, at, readout(), 0.55f, 0, Displays.owner(owner)));
                shown.setTeleportDuration(2);
                panel = shown;
                dirty = false;
                return;
            }
            if (dirty || tick % INFO_REFRESH == 0) {
                shown.text(readout());
                dirty = false;
            }
            Displays.moveTo(shown, at);
            if (weakened(prey) && tick % 8 == 0) {
                Vfx.dot(at.clone().add(Fx.jitter(0.3f), -0.2, Fx.jitter(0.3f)), Particle.DUST, GOLD_DUST, false);
            }
        }

        private Component readout() {
            double max = Combat.maxHealth(prey);
            double fraction = Math.clamp(prey.getHealth() / max, 0.0, 1.0);
            int filled = (int) Math.ceil(fraction * 10 - 1.0E-9);
            String color = fraction > 0.5 ? "#5FD35F" : fraction > 0.25 ? "#FFD400" : "#FF5A4F";
            String chance = weakened(prey) ? "<b><" + GOLD_TAG + ">포획 찬스! " + catchRate(prey) + "%</" + GOLD_TAG
                    + "></b>" : "<" + SKY_TAG + ">포획률 " + catchRate(prey) + "%</" + SKY_TAG + ">";
            return Text.mm("<" + color + ">" + "■".repeat(filled) + "</" + color + "><dark_gray>"
                    + "■".repeat(10 - filled) + "</dark_gray> <white>" + AbilityInfo.formatNumber(Math.ceil(
                    prey.getHealth())) + "/" + AbilityInfo.formatNumber(Math.round(max)) + "</white>\n" + chance);
        }

        void remove() {
            TextDisplay shown = panel;
            if (shown != null && shown.isValid()) {
                shown.remove();
            }
            panel = null;
        }
    }

    // ================================================================== the capsule

    /** A capsule part posed at {@code size} with {@code rotation} about the capsule's middle. */
    private static Transformation posePart(Part part, float size, Quaternionf rotation) {
        Vector3f extent = new Vector3f(part.sx(), part.sy(), part.sz()).mul(size);
        Vector3f corner = new Vector3f(part.cx(), part.cy(), part.cz()).mul(size).sub(new Vector3f(extent).mul(0.5f));
        return new Transformation(new Quaternionf(rotation).transform(corner), new Quaternionf(rotation), extent,
                new Quaternionf());
    }

    /** A capsule of block displays centered on {@code at}. */
    private final class Capsule {
        private final Player owner;
        private final List<BlockDisplay> parts = new ArrayList<>();
        private float size;
        private Quaternionf rotation = new Quaternionf();

        Capsule(Player owner, Location at, float size, long lifetime) {
            this.owner = owner;
            this.size = size;
            if (!reserve(CAPSULE.length)) {
                return;
            }
            Location spot = Displays.unrotated(at);
            for (Part part : CAPSULE) {
                BlockDisplay display = keep(Displays.block(ctx(), owner, spot, part.material().createBlockData(),
                        posePart(part, size * 0.2f, rotation), lifetime));
                display.setTeleportDuration(1);
                parts.add(display);
            }
            Displays.outline(parts.getFirst(), SKY);
            ctx().runLater(owner, 1, () -> pose(this.size, rotation, 3));
        }

        boolean contains(Entity entity) {
            for (BlockDisplay part : parts) {
                if (part.getUniqueId().equals(entity.getUniqueId())) {
                    return true;
                }
            }
            return false;
        }

        boolean isValid() {
            return !parts.isEmpty() && parts.getFirst().isValid();
        }

        void moveTo(Location at) {
            Location spot = Displays.unrotated(at);
            for (BlockDisplay part : parts) {
                Displays.moveTo(part, spot);
            }
        }

        void glide(int ticks) {
            for (BlockDisplay part : parts) {
                part.setTeleportDuration(Math.clamp(ticks, 0, 59));
            }
        }

        void pose(float newSize, Quaternionf newRotation, int ticks) {
            size = newSize;
            rotation = new Quaternionf(newRotation);
            for (int i = 0; i < parts.size(); i++) {
                Displays.animate(parts.get(i), TamerAbility.posePart(CAPSULE[i], size, rotation), ticks);
            }
        }

        void flash(boolean lit) {
            if (parts.size() > BUTTON_PART && parts.get(BUTTON_PART).isValid()) {
                parts.get(BUTTON_PART).setBlock((lit ? BUTTON_LIT : BUTTON).createBlockData());
            }
        }

        /** One wobble: a tilt to one side and back, the button flashing, a clunk. */
        void shake(Location at, int index) {
            float side = index % 2 == 0 ? 1 : -1;
            Quaternionf tilt = new Quaternionf().rotateZ((float) Math.toRadians(24 * side));
            pose(size, tilt, 2);
            flash(true);
            ctx().runLater(owner, 3, () -> pose(size, new Quaternionf().rotateZ((float) Math.toRadians(-12 * side)), 2));
            ctx().runLater(owner, 6, () -> {
                pose(size, new Quaternionf(), 2);
                flash(false);
            });
            Fx.sound(at, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 0.9f, 1.1f + Fx.jitter(0.1f));
            Fx.sound(at, Sound.BLOCK_COPPER_BULB_TURN_ON, 0.9f, 1.0f + 0.15f * index);
            Vfx.cloud(ctx(), at, Particle.DUST, 6, 0.3 * size, 0, GOLD_DUST, false);
        }

        /** The lid pops off and everything shrinks away (an escape or a release). */
        void open(boolean burst) {
            List<BlockDisplay> opened = List.copyOf(parts);
            parts.clear();
            for (int i = 0; i < opened.size(); i++) {
                BlockDisplay part = opened.get(i);
                if (!part.isValid()) {
                    continue;
                }
                if (i == 0) {
                    Quaternionf flip = new Quaternionf().rotateX((float) Math.toRadians(-80));
                    Transformation lid = TamerAbility.posePart(CAPSULE[0], size, flip);
                    Displays.animate(part, new Transformation(lid.getTranslation().add(0, size * 0.7f, -size * 0.3f),
                            lid.getLeftRotation(), lid.getScale(), lid.getRightRotation()), 3);
                    ctx().runLater(owner, 4, () -> {
                        if (part.isValid()) {
                            Displays.shrinkAway(ctx(), owner, part, 5);
                        }
                    });
                } else {
                    Displays.shrinkAway(ctx(), owner, part, burst ? 3 : 6);
                }
            }
        }

        void remove() {
            List<BlockDisplay> removed = List.copyOf(parts);
            parts.clear();
            for (BlockDisplay part : removed) {
                if (part.isValid()) {
                    part.remove();
                }
            }
        }
    }

    // ================================================================== a capture

    /** How a capture ends. */
    private enum Outcome { ESCAPED, CAUGHT, ABORTED }

    /**
     * One enemy inside a capsule: shrunk out of sight, frozen, untouchable and silenced while the capsule shakes; it can
     * mash jump to break free.
     */
    private final class Capture {
        private final Player owner;
        private final LivingEntity prey;
        private final Body body;
        private final Location anchor;
        private final List<NamespacedKey> keys = new ArrayList<>();
        private final @Nullable PotionEffect hadInvisibility;
        private @Nullable Capsule capsule;
        private @Nullable PromptHandle prompt;
        private int mashes;
        private int mashTotal;
        private boolean ended;
        private boolean jumpHeld;

        Capture(Player owner, LivingEntity prey, Location anchor, @Nullable Capsule capsule, long maxTicks,
                boolean legendary) {
            this.owner = owner;
            this.prey = prey;
            this.body = Body.standing(prey);
            this.capsule = capsule;
            this.hadInvisibility = prey.getPotionEffect(PotionEffectType.INVISIBILITY);
            Location spot = anchor.clone();
            if (!Spots.isSafe(ctx(), spot, body)) {
                spot = prey.getLocation();
            }
            spot.setYaw(prey.getLocation().getYaw());
            spot.setPitch(prey.getLocation().getPitch());
            this.anchor = spot;
            if (prey.getLocation().distanceSquared(spot) > 0.01) {
                prey.teleport(spot);
            }
            prey.setVelocity(new Vector(0, prey instanceof Player ? REST_FALL : 0, 0));
            prey.setFallDistance(0);
            long keep = maxTicks + 10;
            keys.add(ctx().addModifier(owner, prey, Attribute.SCALE, SHRINK, Operation.MULTIPLY_SCALAR_1, keep));
            keys.add(ctx().addModifier(owner, prey, Attribute.MOVEMENT_SPEED, -1.0, Operation.MULTIPLY_SCALAR_1, keep));
            keys.add(ctx().addModifier(owner, prey, Attribute.JUMP_STRENGTH, -1.0, Operation.MULTIPLY_SCALAR_1, keep));
            prey.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, (int) keep, 0, false, false, false));
            if (prey instanceof Player enemy) {
                ctx().silence(enemy, SILENCE_TICKS);
                Prompt line = Prompt.builder(Text.mm("<b><" + SKY_TAG + ">캡슐에 갇혔습니다!</" + SKY_TAG
                                + "></b> <white>점프를 연타해 탈출하세요</white>"), maxTicks)
                        .priority(legendary ? Prompt.HIGH : Prompt.NORMAL)
                        .title(Text.mm("<b><" + SKY_TAG + ">포획!</" + SKY_TAG + "></b>"),
                                Text.mm("<white>점프 연타로 탈출!</white>"))
                        .overhead(Text.mm("<" + GOLD_TAG + ">∴ 덜컹덜컹 ∴</" + GOLD_TAG + ">"))
                        .maxWait(10)
                        .reactionSlack(false)
                        .build();
                prompt = ctx().prompt(owner, enemy, line);
            }
            captures.put(prey.getUniqueId(), this);
            startRules();
        }

        boolean alive() {
            return !ended && prey.isValid() && !prey.isDead()
                    && (!(prey instanceof Player player) || ctx().isAlive(player));
        }

        /** Keeps the prisoner inside: back at the anchor if something moved it, still. */
        void hold() {
            if (ended) {
                return;
            }
            Location at = prey.getLocation();
            if (at.getWorld().equals(anchor.getWorld()) && at.distanceSquared(anchor) > DRIFT * DRIFT) {
                Location back = anchor.clone();
                back.setYaw(at.getYaw());
                back.setPitch(at.getPitch());
                prey.teleport(back);
            }
            prey.setFallDistance(0);
            if (prey instanceof Player enemy && ctx().currentTick() % 2 == 0) {
                // No skills from inside the capsule (renewed in short steps, so a prisoner that breaks free is free).
                ctx().silence(enemy, SILENCE_TICKS);
            }
        }

        void mashed() {
            mashes++;
            mashTotal++;
            Capsule shown = capsule;
            Location at = anchor.clone().add(0, 0.5, 0);
            Vfx.cloud(ctx(), at, Particle.DUST, 2, 0.3, 0, SKY_DUST, false);
            if (mashTotal % 3 == 0) {
                Fx.sound(at, Sound.BLOCK_WOODEN_BUTTON_CLICK_ON, 0.5f, 1.4f + Fx.jitter(0.1f));
                if (shown != null && shown.isValid()) {
                    float wiggle = (mashTotal / 3 % 2 == 0 ? 6 : -6);
                    shown.pose(shown.size, new Quaternionf().rotateX((float) Math.toRadians(wiggle)), 1);
                }
            }
            PromptHandle line = prompt;
            if (line != null && line.isActive()) {
                int bonus = (int) Math.round(mashBonus() * 100);
                line.setActionBar(Text.mm("<b><" + SKY_TAG + ">캡슐에 갇혔습니다!</" + SKY_TAG + "></b> <white>점프 연타"
                        + "</white> <" + GOLD_TAG + ">탈출 +" + bonus + "%</" + GOLD_TAG + ">"));
            }
        }

        private double mashBonus() {
            double per = Math.max(0, params().num("mash_bonus")) / 100.0;
            double max = Math.max(0, params().num("mash_max")) / 100.0;
            return Math.min(max, mashes * per);
        }

        /** One shake: does it break free? Mashing since the last shake helps. */
        boolean rollEscape() {
            double chance = Math.clamp(escapeChance(prey) + mashBonus(), 0.0, 1.0);
            mashes = 0;
            return chance > 0 && ThreadLocalRandom.current().nextDouble() < chance;
        }

        /**
         * The prisoner comes out at {@code feet} (a safe spot; the anchor if null): full size, visible, free to move.
         * Nothing here hurts it; the caller deals the damage of the way it came out.
         */
        void free(@Nullable Location feet, Outcome outcome) {
            if (ended) {
                return;
            }
            ended = true;
            captures.remove(prey.getUniqueId());
            for (NamespacedKey key : keys) {
                ctx().removeModifier(prey, key);
            }
            keys.clear();
            prey.removePotionEffect(PotionEffectType.INVISIBILITY);
            PotionEffect before = hadInvisibility;
            if (before != null) {
                prey.addPotionEffect(before);
            }
            PromptHandle line = prompt;
            if (line != null && line.isActive()) {
                line.cancel();
            }
            prompt = null;
            if (prey.isValid() && !prey.isDead()) {
                Location to = feet == null ? anchor.clone() : feet.clone();
                to.setYaw(prey.getLocation().getYaw());
                to.setPitch(prey.getLocation().getPitch());
                if (prey.getLocation().getWorld().equals(to.getWorld()) && prey.getLocation().distanceSquared(to) > 0.01) {
                    prey.teleport(to);
                }
                prey.setFallDistance(0);
            }
            if (outcome != Outcome.ABORTED) {
                Location at = Fx.body(prey);
                Vfx.burst(ctx(), at, PALETTE, 0.7, Particle.END_ROD, false);
            }
            if (captures.isEmpty()) {
                stopRules();
            }
        }

        /** The collector is gone (or the capsule was removed): the prisoner simply comes out where it was. */
        void abort() {
            Capsule shown = capsule;
            free(null, Outcome.ABORTED);
            if (shown != null) {
                shown.remove();
            }
        }
    }

    // ------------------------------------------------------------------ the rules while anyone is captured

    private void startRules() {
        if (rules == null) {
            CaptureRules created = new CaptureRules();
            Bukkit.getPluginManager().registerEvents(created, ctx().plugin());
            rules = created;
        }
    }

    private void stopRules() {
        CaptureRules current = rules;
        if (current != null) {
            HandlerList.unregisterAll(current);
            rules = null;
        }
    }

    /**
     * Prisoners take no damage and deal none, their jump presses are counted, and a capture whose capsule disappears
     * with its collector (death, quitting, the match ending) lets its prisoner out.
     */
    private final class CaptureRules implements Listener {

        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
        public void onDamage(EntityDamageEvent event) {
            if (captures.isEmpty()) {
                return;
            }
            Capture inside = captures.get(event.getEntity().getUniqueId());
            if (inside != null && !inside.ended) {
                DamageType type = event.getDamageSource().getDamageType();
                if (type != DamageType.OUT_OF_WORLD && type != DamageType.OUTSIDE_BORDER
                        && type != DamageType.GENERIC_KILL) {
                    event.setCancelled(true);
                }
                return;
            }
            if (event instanceof EntityDamageByEntityEvent byEntity) {
                Entity damager = byEntity.getDamager();
                Entity source = damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity
                        shooter ? shooter : damager;
                Capture attacker = captures.get(source.getUniqueId());
                if (attacker != null && !attacker.ended) {
                    event.setCancelled(true);
                }
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onInput(PlayerInputEvent event) {
            Capture inside = captures.get(event.getPlayer().getUniqueId());
            if (inside == null || inside.ended) {
                return;
            }
            try {
                boolean jump = event.getInput().isJump();
                if (jump && !inside.jumpHeld) {
                    inside.mashed();
                }
                inside.jumpHeld = jump;
            } catch (RuntimeException e) {
                ctx().plugin().getLogger().log(Level.WARNING, "Tamer mash failed", e);
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onRemove(EntityRemoveEvent event) {
            if (captures.isEmpty()) {
                return;
            }
            // Every prisoner of that capsule first (the giant one holds several), then they all come out.
            List<Capture> released = new ArrayList<>();
            for (Capture capture : captures.values()) {
                Capsule shown = capture.capsule;
                if (!capture.ended && shown != null && shown.contains(event.getEntity())) {
                    released.add(capture);
                }
            }
            for (Capture capture : released) {
                try {
                    capture.abort();
                } catch (RuntimeException e) {
                    ctx().plugin().getLogger().log(Level.WARNING, "Tamer capture release failed", e);
                }
            }
        }
    }

    // ================================================================== active: capture capsule

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        Location eye = owner.getEyeLocation();
        Location hand = hand(owner);
        Vector velocity = eye.getDirection().multiply(THROW_SPEED);
        Fx.sound(hand, Sound.ENTITY_SNOWBALL_THROW, 1.0f, 0.8f + Fx.jitter(0.05f));
        Fx.sound(hand, Sound.ENTITY_ALLAY_ITEM_THROWN, 0.8f, 1.2f + Fx.jitter(0.05f));
        Capsule capsule = new Capsule(owner, hand, FLIGHT_SIZE, 80);
        new Throw(owner, hand, velocity, capsule).fly();
        return ActivationResult.success();
    }

    private static Location hand(Player owner) {
        Location eye = owner.getEyeLocation();
        Vector look = eye.getDirection();
        Vector right = new Vector(-look.getZ(), 0, look.getX());
        if (right.lengthSquared() > 1.0E-6) {
            right.normalize().multiply(-0.35);
        }
        return eye.clone().add(look.multiply(0.6)).add(right).subtract(0, 0.3, 0);
    }

    /** The thrown capsule: an arc that bends a little towards badly hurt enemies, swallowing the first enemy it hits. */
    private final class Throw {
        private final Player owner;
        private final Capsule capsule;
        private final double range = Math.max(2, params().num("throw_range"));
        private Location at;
        private Vector velocity;
        private double travelled;
        private float spin;

        Throw(Player owner, Location from, Vector velocity, Capsule capsule) {
            this.owner = owner;
            this.at = from.clone();
            this.velocity = velocity;
            this.capsule = capsule;
        }

        void fly() {
            Loop.repeat(ctx(), owner, 0, 1, 60, run -> step(), null);
        }

        private boolean step() {
            home();
            Vector move = velocity.clone();
            double length = move.length();
            if (length < 1.0E-6) {
                return miss(at);
            }
            Location next = at.clone().add(move);
            LivingEntity hit = firstHit(at, move, length);
            RayTraceResult block = at.getWorld().rayTraceBlocks(at, move.clone().multiply(1 / length), length,
                    FluidCollisionMode.NEVER, true);
            if (hit != null && (block == null || block.getHitPosition().distance(at.toVector())
                    >= Fx.body(hit).distance(at) - 0.6)) {
                swallow(hit);
                return false;
            }
            if (block != null) {
                return miss(block.getHitPosition().toLocation(at.getWorld()));
            }
            at = next;
            travelled += length;
            spin += 40;
            capsule.moveTo(at);
            capsule.pose(FLIGHT_SIZE, new Quaternionf().rotateY((float) Math.atan2(move.getX(), move.getZ()))
                    .rotateX((float) Math.toRadians(spin)), 1);
            Vfx.dot(at, Particle.DUST, travelled % 2 < 1 ? GOLD_DUST : SKY_DUST, false);
            Vfx.dot(at.clone().subtract(move.clone().multiply(0.5)), Particle.END_ROD, null, false);
            velocity = velocity.multiply(THROW_DRAG).add(new Vector(0, -THROW_GRAVITY, 0));
            if (travelled >= range) {
                return miss(at);
            }
            return true;
        }

        /** Bends the flight a little towards the weakest enemy ahead at or under the homing health. */
        private void home() {
            double speed = velocity.length();
            if (speed < 1.0E-6) {
                return;
            }
            Vector heading = velocity.clone().multiply(1 / speed);
            LivingEntity best = null;
            double bestAngle = HOMING_CONE;
            for (LivingEntity enemy : ctx().enemiesNear(owner, at, Math.max(1, range - travelled))) {
                if (!weakened(enemy) || ctx().isConcealedFrom(enemy, owner) || isCaptured(enemy)) {
                    continue;
                }
                Vector to = Fx.body(enemy).toVector().subtract(at.toVector());
                if (to.lengthSquared() < 1.0E-6) {
                    continue;
                }
                double angle = heading.angle(to);
                if (angle < bestAngle && Spots.clearPath(at, Fx.body(enemy))) {
                    bestAngle = angle;
                    best = enemy;
                }
            }
            if (best == null) {
                return;
            }
            Vector want = Fx.body(best).toVector().subtract(at.toVector()).normalize().multiply(speed);
            velocity = velocity.add(want.subtract(velocity).multiply(HOMING));
        }

        private @Nullable LivingEntity firstHit(Location from, Vector move, double length) {
            Vector direction = move.clone().multiply(1 / length);
            LivingEntity best = null;
            double bestDistance = Double.MAX_VALUE;
            Location middle = from.clone().add(move.clone().multiply(0.5));
            for (LivingEntity enemy : ctx().enemiesNear(owner, middle, length / 2 + 1.5)) {
                if (isCaptured(enemy)) {
                    continue;
                }
                BoundingBox box = enemy.getBoundingBox().expand(0.3);
                RayTraceResult hit = box.rayTrace(from.toVector(), direction, length);
                double distance;
                if (box.contains(from.toVector())) {
                    distance = 0;
                } else if (hit != null) {
                    distance = hit.getHitPosition().distance(from.toVector());
                } else {
                    continue;
                }
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = enemy;
                }
            }
            return best;
        }

        /** Nothing caught: the capsule pops open empty and part of the cooldown comes back. */
        private boolean miss(Location where) {
            capsule.moveTo(where);
            capsule.open(false);
            Vfx.cloud(ctx(), where, Particle.POOF, 6, 0.2, 0.02, null, false);
            Fx.sound(where, Sound.BLOCK_COPPER_BULB_TURN_OFF, 0.8f, 0.8f);
            double refund = Math.clamp(params().num("miss_refund") / 100.0, 0.0, 1.0) * params().num("cooldown");
            if (refund > 0 && ctx().isAlive(owner)) {
                ctx().reduceCooldown(owner, refund);
            }
            return false;
        }

        private void swallow(LivingEntity prey) {
            Location ground = prey.getLocation();
            Spots.snapToGround(ground.getWorld(), ground, 3);
            new Catch(owner, prey, ground, capsule).begin();
        }
    }

    /**
     * One capture by the active: the capsule settles where the enemy stood and shakes; held through every shake it flies
     * back to the collector's hand, where a sneak throws it.
     */
    private final class Catch {
        private final Player owner;
        private final LivingEntity prey;
        private final Location ground;
        private final Capsule capsule;
        private final int shakes = Math.max(1, (int) Math.round(params().num("shakes")));
        private final long interval = Math.max(2, params().ticks("shake_interval"));
        private final long hold = Math.max(1, params().ticks("hold_time"));
        private @Nullable Capture capture;

        Catch(Player owner, LivingEntity prey, Location ground, Capsule capsule) {
            this.owner = owner;
            this.prey = prey;
            this.ground = ground;
            this.capsule = capsule;
        }

        void begin() {
            Location body = Fx.body(prey);
            Vfx.dot(body, Particle.FLASH, SKY, false);
            Vfx.cloud(ctx(), body, Particle.DUST_COLOR_TRANSITION, 16, 0.4, 0, GLEAM, false);
            Vfx.trail(body, ground.clone().add(0, 0.5, 0), SKY, 5, false);
            Fx.sound(body, Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.8f);
            Fx.sound(body, Sound.BLOCK_BEACON_POWER_SELECT, 0.7f, 2.0f);
            capture = new Capture(owner, prey, ground, capsule, shakes * interval + hold + 30, false);
            capsule.moveTo(ground.clone().add(0, HELD_SIZE / 2, 0));
            capsule.pose(HELD_SIZE, new Quaternionf(), 3);
            Loop.repeat(ctx(), owner, 1, 1, shakes * interval + 1, this::shaking, this::caught);
        }

        private boolean shaking(long run) {
            Capture inside = capture;
            if (inside == null || !inside.alive()) {
                endEarly();
                return false;
            }
            inside.hold();
            if ((run + 1) % interval == 0) {
                int index = (int) ((run + 1) / interval) - 1;
                Location at = ground.clone().add(0, HELD_SIZE / 2, 0);
                capsule.shake(at, index);
                if (inside.rollEscape()) {
                    escape(inside);
                    return false;
                }
            }
            return true;
        }

        /** The prisoner broke free: out it comes, barely scratched. */
        private void escape(Capture inside) {
            capsule.open(false);
            inside.free(null, Outcome.ESCAPED);
            Location at = Fx.body(prey);
            Fx.sound(at, Sound.BLOCK_CHEST_OPEN, 0.8f, 1.6f);
            Fx.sound(at, Sound.ENTITY_ITEM_BREAK, 0.6f, 1.2f);
            Vfx.cloud(ctx(), at, Particle.CLOUD, 8, 0.3, 0.05, null, false);
            Combat.hit(ctx(), owner, prey, params().num("escape_damage"));
            if (Combat.isTargetable(ctx(), owner, prey)) {
                Motion.push(prey, new Vector(0, 0.4, 0), MAX_SPEED);
            }
            if (prey instanceof Player enemy && !ctx().hasShownPrompt(enemy)) {
                enemy.sendActionBar(Text.mm("<b><white>탈출 성공!</white></b>"));
            }
            owner.sendActionBar(Text.mm("<gray>앗, 캡슐에서 빠져나왔다!</gray>"));
        }

        private void endEarly() {
            Capture inside = capture;
            if (inside != null && !inside.ended) {
                inside.free(null, Outcome.ABORTED);
            }
            capsule.open(false);
        }

        /** Held through every shake: "잡았다!" — the capsule flies back to the collector's hand. */
        private void caught() {
            Capture inside = capture;
            if (inside == null || !inside.alive()) {
                endEarly();
                return;
            }
            Location at = ground.clone().add(0, HELD_SIZE / 2, 0);
            Fx.sound(at, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.8f);
            Fx.sound(at, Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0f, 1.2f);
            ctx().runLater(owner, 3, () -> Fx.sound(at, Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0f, 1.6f));
            ctx().runLater(owner, 6, () -> Fx.sound(at, Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0f, 2.0f));
            for (int i = 0; i < 3; i++) {
                double a = i * Math.PI * 2 / 3;
                Vfx.line(ctx(), at, at.clone().add(Math.cos(a) * 1.2, 0.8, Math.sin(a) * 1.2), 4, Particle.DUST,
                        GOLD_DUST, false);
            }
            Vfx.cloud(ctx(), at, Particle.WAX_ON, 10, 0.5, 0.2, null, false);
            if (reserve(1)) {
                TextDisplay cheer = keep(Displays.text(ctx(), owner, at.clone().add(0, 1.2, 0), Text.mm("<b><" + GOLD_TAG
                        + ">잡았다!</" + GOLD_TAG + "></b>"), 0.3f, 24));
                Displays.animateNextTick(ctx(), owner, cheer, Displays.centered(0, 0.3f, 0, 1.2f, 1.2f, 1.2f, 0, 0, 0),
                        4);
            }
            capsule.glide(3);
            capsule.pose(HAND_SIZE, new Quaternionf(), 3);
            PromptHandle choice = null;
            if (!ctx().hasShownPrompt(owner)) {
                choice = ctx().prompt(owner, owner, Prompt.builder(Text.mm("<b><" + GOLD_TAG + ">잡았다!</" + GOLD_TAG
                        + "></b> <white>웅크리면 바라보는 곳에 던져 풀어 줍니다</white>"), hold).priority(Prompt.NORMAL)
                        .maxWait(0).build());
            }
            PromptHandle shown = choice;
            long caughtAt = ctx().currentTick();
            Loop.repeat(ctx(), owner, 0, 1, hold + 1, run -> {
                if (!inside.alive()) {
                    endEarly();
                    return false;
                }
                inside.hold();
                Location held = hand(owner);
                capsule.moveTo(held);
                if (run > 2 && Inputs.usedSince(ctx(), owner, InputKey.SNEAK, caughtAt)) {
                    if (shown != null && shown.isActive()) {
                        shown.cancel();
                    }
                    release(inside, true);
                    return false;
                }
                if (run >= hold) {
                    release(inside, false);
                    return false;
                }
                return true;
            }, null);
        }

        /** Thrown where the collector looks (a sneak) or dropped in front of it. */
        private void release(Capture inside, boolean thrown) {
            Location spot = thrown ? aimedSpot(owner, inside.body) : null;
            if (spot == null) {
                spot = besideOwner(owner, inside.body);
            }
            if (spot == null) {
                spot = inside.anchor.clone();
            }
            Location to = spot;
            Location from = hand(owner);
            long flight = Math.max(2, Math.min(8, Math.round(from.distance(to) / 2.0)));
            capsule.glide(1);
            Fx.sound(from, Sound.ENTITY_SNOWBALL_THROW, 0.9f, 0.7f);
            Loop.repeat(ctx(), owner, 0, 1, flight, run -> {
                if (!inside.alive()) {
                    endEarly();
                    return false;
                }
                inside.hold();
                double t = (run + 1) / (double) flight;
                Location point = from.clone().add(to.toVector().subtract(from.toVector()).multiply(t))
                        .add(0, Math.sin(t * Math.PI) * Math.min(3, from.distance(to) * 0.25) + 0.3, 0);
                capsule.moveTo(point);
                capsule.pose(Math.max(HAND_SIZE, HELD_SIZE * (float) t), new Quaternionf()
                        .rotateX((float) Math.toRadians(run * 45)), 1);
                Vfx.dot(point, Particle.DUST, GOLD_DUST, false);
                return true;
            }, () -> land(inside, to));
        }

        /** The capsule lands and opens: the enemy drops out, hurt and staggering. */
        private void land(Capture inside, Location spot) {
            if (!inside.alive()) {
                endEarly();
                return;
            }
            Location drop = spot.clone();
            Location raised = spot.clone().add(0, 1.0, 0);
            if (Spots.isSafe(ctx(), raised, inside.body)) {
                drop = raised;
            }
            capsule.moveTo(spot.clone().add(0, 0.4, 0));
            capsule.open(false);
            inside.free(drop, Outcome.CAUGHT);
            Location at = spot.clone().add(0, 0.6, 0);
            Vfx.burst(ctx(), at, PALETTE, 1.0, Particle.END_ROD, false);
            Vfx.ring(ctx(), spot.clone().add(0, 0.1, 0), 1.2, 16, 0, Particle.DUST, SKY_DUST, false);
            Fx.sound(at, Sound.BLOCK_CHEST_OPEN, 0.9f, 1.3f);
            Fx.sound(at, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.9f, 0.9f);
            Combat.hit(ctx(), owner, prey, params().num("release_damage"));
            if (Combat.isTargetable(ctx(), owner, prey)) {
                stagger(owner, prey);
            }
        }
    }

    /** One second of staggering: slower, with dizzy motes. */
    private void stagger(Player owner, LivingEntity prey) {
        long ticks = params().ticks("stagger_duration");
        double slow = Math.clamp(params().num("stagger_slow") / 100.0, 0.0, 0.89);
        if (ticks <= 0) {
            return;
        }
        if (slow > 0) {
            ctx().addModifier(owner, prey, Attribute.MOVEMENT_SPEED, -slow, Operation.MULTIPLY_SCALAR_1, ticks);
        }
        Loop.repeat(ctx(), owner, 0, 3, Math.max(1, ticks / 3), run -> {
            if (!prey.isValid() || prey.isDead() || ctx().isConcealedFrom(prey, owner)) {
                return false;
            }
            Location head = prey.getLocation().add(0, prey.getHeight() + 0.2, 0);
            double a = run * 1.2;
            Vfx.dot(head.clone().add(Math.cos(a) * 0.4, 0, Math.sin(a) * 0.4), Particle.WAX_ON, null, false);
            Vfx.dot(head.clone().add(-Math.cos(a) * 0.4, 0, -Math.sin(a) * 0.4), Particle.DUST, GOLD_DUST, false);
            return true;
        });
    }

    /** A safe spot for a body at what the collector looks at, within the release range; null if there is none. */
    private @Nullable Location aimedSpot(Player owner, Body body) {
        World world = owner.getWorld();
        Location eye = owner.getEyeLocation();
        Vector look = eye.getDirection();
        double range = Math.max(1, params().num("release_range"));
        RayTraceResult hit = world.rayTraceBlocks(eye, look, range, FluidCollisionMode.NEVER, true);
        Location point;
        if (hit == null) {
            point = eye.clone().add(look.clone().multiply(range));
        } else if (hit.getHitBlockFace() == BlockFace.UP) {
            point = hit.getHitPosition().toLocation(world);
        } else {
            point = hit.getHitPosition().toLocation(world).subtract(look.clone().multiply(0.6));
        }
        point = Displays.unrotated(point).add(0, 0.3, 0);
        Spots.snapToGround(world, point, 10);
        for (double[] offset : new double[][] {{0, 0}, {0.6, 0}, {-0.6, 0}, {0, 0.6}, {0, -0.6}}) {
            Location feet = point.clone().add(offset[0], 0, offset[1]);
            if (Spots.isSafe(ctx(), feet, body) && Spots.clearPath(eye, feet.clone().add(0, 1, 0))) {
                return feet;
            }
        }
        return null;
    }

    /** A safe spot right in front of (or else beside) the collector. */
    private @Nullable Location besideOwner(Player owner, Body body) {
        Location feet = owner.getLocation();
        Vector facing = Motion.horizontalFacing(feet);
        for (double angle : new double[] {0, 45, -45, 90, -90, 135, -135, 180}) {
            Vector side = facing.clone().rotateAroundY(Math.toRadians(angle)).multiply(1.4);
            Location spot = Displays.unrotated(feet.clone().add(side)).add(0, 0.5, 0);
            Spots.snapToGround(feet.getWorld(), spot, 2);
            if (Spots.isSafe(ctx(), spot, body) && Spots.clearPath(owner.getEyeLocation(), spot.clone().add(0, 1, 0))) {
                return spot;
            }
        }
        return null;
    }

    // ================================================================== ultimate: legendary capture

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        Location center = aimPoint(owner, Math.max(1, params().num("legend_range")));
        if (center == null) {
            return ActivationResult.fail("거대한 캡슐을 떨어뜨릴 자리가 없습니다");
        }
        new Legend(owner, center).drop();
        return ActivationResult.success();
    }

    /** The ground where the owner aims (an aimed enemy's feet, the block looked at, or the ground at the range). */
    private @Nullable Location aimPoint(Player owner, double range) {
        World world = owner.getWorld();
        Location eye = owner.getEyeLocation();
        Vector look = eye.getDirection();
        LivingEntity aimed = ctx().findTarget(owner, range);
        RayTraceResult block = world.rayTraceBlocks(eye, look, range, FluidCollisionMode.NEVER, true);
        Location point;
        if (aimed != null && (block == null || block.getHitPosition().distance(eye.toVector())
                > aimed.getLocation().distance(eye))) {
            point = aimed.getLocation();
        } else if (block != null) {
            point = block.getHitPosition().toLocation(world).add(0, 0.05, 0);
        } else {
            point = eye.clone().add(look.clone().multiply(range));
        }
        point = Displays.unrotated(point).add(0, 0.5, 0);
        Spots.snapToGround(world, point, 12);
        if (!Spots.hasGroundBelow(world, point) || !ctx().isInsideArena(point)) {
            Location feet = Displays.unrotated(owner.getLocation());
            return ctx().isInsideArena(feet) ? feet : null;
        }
        return point;
    }

    /**
     * The legendary capsule: it falls from the sky over its shadow, swallows every enemy around where it lands, shakes
     * them all (each rolls for itself) and bursts on the ones still inside.
     */
    private final class Legend {
        private final Player owner;
        private final Location center;
        private final long fall = Math.max(4, params().ticks("legend_fall"));
        private final double radius = Math.max(1, params().num("legend_radius"));
        private final int shakes = Math.max(1, (int) Math.round(params().num("shakes")));
        private final long interval = Math.max(2, params().ticks("shake_interval"));
        private final long burstDelay = Math.max(1, params().ticks("legend_burst_delay"));
        private final List<Capture> inside = new ArrayList<>();
        private @Nullable Capsule capsule;

        Legend(Player owner, Location center) {
            this.owner = owner;
            this.center = center;
        }

        void drop() {
            capsule = new Capsule(owner, center.clone().add(0, LEGEND_HEIGHT, 0), LEGEND_SIZE,
                    fall + shakes * interval + burstDelay + 40);
            Fx.sound(center, Sound.ENTITY_ENDER_DRAGON_FLAP, 1.2f, 0.6f);
            Fx.sound(center, Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.4f);
            owner.showTitle(net.kyori.adventure.title.Title.title(Text.mm("<b><" + GOLD_TAG + ">전설의 포획!</" + GOLD_TAG
                    + "></b>"), Text.mm("<" + SKY_TAG + ">거대한 캡슐이 떨어집니다</" + SKY_TAG + ">")));
            Loop.repeat(ctx(), owner, 0, 1, fall, run -> {
                double t = (run + 1) / (double) fall;
                Capsule shown = capsule;
                if (shown != null) {
                    double height = LEGEND_HEIGHT * (1 - t * t) + LEGEND_SIZE / 2;
                    shown.moveTo(center.clone().add(0, height, 0));
                    shown.pose(LEGEND_SIZE, new Quaternionf().rotateY((float) Math.toRadians(run * 9)), 1);
                }
                // The shadow grows and darkens as the capsule falls.
                double shadow = radius * (0.3 + 0.7 * t);
                Vfx.ring(ctx(), center.clone().add(0, 0.08, 0), shadow, (int) Math.max(10, shadow * 5), run * 0.1,
                        Particle.DUST, SHADOW_DUST, true);
                if (run % 3 == 0) {
                    Vfx.ring(ctx(), center.clone().add(0, 0.1, 0), radius, (int) (radius * 6), -run * 0.05,
                            Particle.DUST, SKY_DUST, true);
                    Fx.sound(center, Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 0.6f + (float) t);
                }
                return true;
            }, this::land);
        }

        private void land() {
            Capsule shown = capsule;
            if (shown != null) {
                shown.moveTo(center.clone().add(0, LEGEND_SIZE / 2, 0));
                shown.pose(LEGEND_SIZE, new Quaternionf(), 2);
            }
            Fx.sound(center, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 1.2f, 0.8f);
            Fx.sound(center, Sound.BLOCK_ANVIL_LAND, 1.0f, 0.5f);
            Vfx.shockwave(ctx(), owner, center.clone().add(0, 0.15, 0), 0.5, radius + 1, 8,
                    Particle.DUST_COLOR_TRANSITION, GLEAM, true);
            Vfx.cloud(ctx(), center.clone().add(0, 0.3, 0), Particle.CLOUD, 20, radius * 0.4, 0.05, null, false);
            long maxTicks = shakes * interval + burstDelay + 30;
            Location anchor = center.clone();
            for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius)) {
                if (ctx().isConcealedFrom(enemy, owner) || isCaptured(enemy)) {
                    continue;
                }
                Vfx.trail(Fx.body(enemy), center.clone().add(0, LEGEND_SIZE / 2, 0), SKY, 6, true);
                inside.add(new Capture(owner, enemy, anchor, shown, maxTicks, true));
            }
            if (inside.isEmpty()) {
                Fx.sound(center, Sound.BLOCK_COPPER_BULB_TURN_OFF, 1.0f, 0.6f);
                ctx().runLater(owner, 10, () -> {
                    Capsule empty = capsule;
                    if (empty != null) {
                        empty.open(false);
                    }
                });
                return;
            }
            Fx.sound(center, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 0.6f);
            Loop.repeat(ctx(), owner, 1, 1, shakes * interval + burstDelay, this::shaking, this::burst);
        }

        private boolean shaking(long run) {
            inside.removeIf(capture -> {
                if (!capture.alive()) {
                    capture.free(null, Outcome.ABORTED);
                    return true;
                }
                return false;
            });
            for (Capture capture : inside) {
                capture.hold();
            }
            if ((run + 1) % interval == 0 && run + 1 <= shakes * interval) {
                int index = (int) ((run + 1) / interval) - 1;
                Capsule shown = capsule;
                if (shown != null) {
                    shown.shake(center.clone().add(0, LEGEND_SIZE / 2, 0), index);
                }
                for (Capture capture : List.copyOf(inside)) {
                    if (capture.rollEscape()) {
                        inside.remove(capture);
                        breakOut(capture);
                    }
                }
            }
            if (run + 1 == shakes * interval && !inside.isEmpty()) {
                Location top = center.clone().add(0, LEGEND_SIZE + 0.6, 0);
                Fx.sound(top, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.6f);
                if (reserve(1)) {
                    TextDisplay cheer = keep(Displays.text(ctx(), owner, top, Text.mm("<b><" + GOLD_TAG + ">잡았다!</"
                            + GOLD_TAG + "></b>"), 0.4f, burstDelay + 4));
                    Displays.animateNextTick(ctx(), owner, cheer, Displays.centered(0, 0.3f, 0, 1.8f, 1.8f, 1.8f, 0, 0,
                            0), 4);
                }
            }
            if (run + 1 > shakes * interval && run % 4 == 0 && !inside.isEmpty()) {
                // The capsule swells and shakes harder before it bursts.
                Capsule shown = capsule;
                if (shown != null) {
                    float swell = LEGEND_SIZE * (1 + 0.08f * (run - shakes * interval) / Math.max(1, burstDelay));
                    shown.pose(swell, new Quaternionf().rotateZ((float) Math.toRadians(run % 8 == 0 ? 6 : -6)), 2);
                }
                Fx.sound(center, Sound.BLOCK_COPPER_BULB_TURN_ON, 0.8f, 1.4f + 0.02f * run);
            }
            return !inside.isEmpty();
        }

        /** One prisoner broke out of the giant capsule: thrown clear, hurt. */
        private void breakOut(Capture capture) {
            capture.free(null, Outcome.ESCAPED);
            LivingEntity prey = capture.prey;
            Location at = Fx.body(prey);
            Fx.sound(at, Sound.ENTITY_ITEM_BREAK, 0.8f, 0.9f);
            Vfx.cloud(ctx(), at, Particle.CLOUD, 8, 0.4, 0.05, null, false);
            Combat.hit(ctx(), owner, prey, params().num("legend_escape_damage"));
            fling(prey, 0.8, 0.45);
        }

        /** The capsule bursts: everyone still inside is hurt and scattered in every direction. */
        private void burst() {
            Capsule shown = capsule;
            Location at = center.clone().add(0, LEGEND_SIZE / 2, 0);
            if (shown != null) {
                shown.open(true);
            }
            if (inside.isEmpty()) {
                return;
            }
            Vfx.burst(ctx(), at, PALETTE, 2.5, Particle.END_ROD, true);
            Vfx.dot(at, Particle.EXPLOSION, null, true);
            Vfx.sphere(ctx(), at, 2.0, 40, Particle.DUST_COLOR_TRANSITION, GLEAM, true);
            Fx.sound(at, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.4f);
            Fx.sound(at, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.2f, 1.0f);
            Fx.sound(at, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1.0f, 1.2f);
            List<Capture> released = List.copyOf(inside);
            inside.clear();
            int index = 0;
            for (Capture capture : released) {
                capture.free(null, Outcome.CAUGHT);
                LivingEntity prey = capture.prey;
                Combat.hit(ctx(), owner, prey, params().num("legend_damage"));
                double angle = index++ * Math.PI * 2 / Math.max(1, released.size())
                        + ThreadLocalRandom.current().nextDouble(0.5);
                fling(prey, 1.0, 0.6, angle);
            }
        }

        private void fling(LivingEntity prey, double out, double up) {
            Vector away = prey.getLocation().toVector().subtract(center.toVector()).setY(0);
            double angle = away.lengthSquared() < 1.0E-4 ? ThreadLocalRandom.current().nextDouble(Math.PI * 2)
                    : Math.atan2(away.getZ(), away.getX());
            fling(prey, out, up, angle);
        }

        private void fling(LivingEntity prey, double out, double up, double angle) {
            if (!Combat.isTargetable(ctx(), owner, prey)) {
                return;
            }
            Vector push = new Vector(Math.cos(angle) * out, up, Math.sin(angle) * out);
            Motion.push(prey, push, MAX_SPEED);
        }
    }

    // ================================================================== helpers

    private boolean reserve(int count) {
        live.removeIf(display -> !display.isValid());
        return live.size() + count <= MAX_DISPLAYS;
    }

    private <T extends Display> T keep(T display) {
        live.add(display);
        return display;
    }
}
