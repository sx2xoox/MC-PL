package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;

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
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.block.data.BlockData;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 크리퍼 — that hiss right behind you. Sneaking packs gunpowder into its body, the active swells it up until it
 * blows (hurting itself a little) and the ultimate calls down a lightning bolt that charges it: for a few seconds it
 * can blow up again and again, harder and without hurting itself.
 *
 * <p>Look: creeper green and white. Stored gunpowder circles the creeper's waist (one pinch per third). The fuse
 * swells the creeper's body, a lime glass bubble blinks white faster and faster around it and the blast radius closes
 * in on the ground. The blast is a white flash, a glass shock sphere, a ring of smoke and green dust rolling out, a
 * crater rim that pops out of the ground and clods of earth thrown around. Charged, the creeper wears a slowly turning
 * shell of blue light crackling with sparks, and every blast is struck by lightning.
 */
public final class CreeperAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("creeper", "크리퍼", Material.CREEPER_HEAD)
            .color(TextColor.color(0x5DBB4A))
            .difficulty(2)
            .passive("쉬익",
                    "웅크리고 있으면 몸속에 화약이 차올라({charge_time}초면 가득) 다음 자폭의 피해가 최대 "
                            + "{charge_damage}%, 범위가 최대 {charge_radius}블록 늘어납니다. 웅크리기를 풀면 "
                            + "{charge_hold}초 뒤부터 화약이 서서히 흩어집니다.")
            .active("자폭",
                    "{fuse}초간 부풀어 오르며 쉬익거리다(이동 속도 -{fuse_slow}%) 폭발해, 반경 {blast_radius}블록의 "
                            + "적에게 가까울수록 큰 {blast_min_damage}~{blast_damage} 피해를 주고 날려 보냅니다. 자신도 "
                            + "최대 피해의 {self_damage}%를 입지만 죽지는 않으며, 벽 뒤의 적은 무사합니다.",
                    9)
            .ultimate("충전된 크리퍼",
                    "번개를 맞아 {ult_duration}초간 충전됩니다. 그동안 자폭은 쿨타임 없이 {ult_fuse}초 만에 터지고, "
                            + "피해가 {ult_damage}% 늘고 범위가 {ult_radius}블록 넓어지며, 자신은 피해를 입지 않고 "
                            + "부풀어 오르는 동안 느려지지도 않습니다.")
            .param("charge_time", 3)
            .param("charge_damage", 50)
            .param("charge_radius", 1.5)
            .param("charge_hold", 3)
            .param("fuse", 1.5)
            .param("fuse_slow", 50)
            .param("blast_radius", 4)
            .param("blast_damage", 6)
            .param("blast_min_damage", 2.5)
            .param("self_damage", 25)
            .param("ult_duration", 6)
            .param("ult_fuse", 0.8)
            .param("ult_damage", 30)
            .param("ult_radius", 1.5)
            .ultimateNotStandalone()
            .build();

    private static final String GREEN = "#5DBB4A";
    private static final String BLUE = "#7FD8FF";
    private static final double TAU = Math.PI * 2;

    // ------------------------------------------------------------------ look
    /** Pale white-green flash, creeper green, the dark green of its spots. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xF2FFE8, 0x5DBB4A, 0x1F4A1A);
    /** The charged creeper's electric aura. */
    private static final Vfx.Palette POWER = Vfx.Palette.of(0xFFFFFF, 0x7FD8FF, 0x1E5A9E);
    private static final Particle.DustOptions WHITE_FLASH = new Particle.DustOptions(Color.WHITE, 1.2f);
    private static final Particle.DustOptions SOOT = new Particle.DustOptions(Color.fromRGB(0x3A3A3A), 1.4f);
    private static final Material BUBBLE = Material.LIME_STAINED_GLASS;
    private static final Material BUBBLE_FLASH = Material.WHITE_STAINED_GLASS;
    private static final Material POWER_SHELL = Material.LIGHT_BLUE_STAINED_GLASS;
    private static final Material SHOCK_INNER = Material.WHITE_STAINED_GLASS;
    private static final Material SHOCK_OUTER = Material.LIME_STAINED_GLASS;
    private static final Material POWER_SHOCK = Material.LIGHT_BLUE_STAINED_GLASS;
    private static final Material SCORCH = Material.BLACK_CONCRETE_POWDER;
    private static final Material[] RIM = {Material.COARSE_DIRT, Material.DIRT, Material.ROOTED_DIRT};
    private static final Material CHARGED_RIM = Material.LIGHT_BLUE_CONCRETE_POWDER;
    private static final Material FALLBACK_GROUND = Material.DIRT;

    /** Every display this ability keeps alive at once stays within this budget (orbit + fuse + blasts + aura). */
    private static final int DISPLAY_BUDGET = 24;

    // ------------------------------------------------------------------ passive: gunpowder
    private static final int POWDER_PIECES = 3;
    private static final float POWDER_SCALE = 0.32f;
    private static final double POWDER_ORBIT = 0.62;
    private static final double POWDER_HEIGHT = 0.55;
    private static final double POWDER_SPIN = 0.22;
    private static final long CHARGE_FEEDBACK_PERIOD = 4;
    private static final long HISS_PERIOD = 12;
    private static final long BAR_PERIOD = 5;

    // ------------------------------------------------------------------ active: self-destruct
    /** The body swells by at most this much (scale) while the fuse burns, if there is room for it. */
    private static final double SWELL_MAX = 0.2;
    private static final long SWELL_PERIOD = 3;
    private static final float BUBBLE_START = 1.0f;
    private static final float BUBBLE_END = 1.55f;
    private static final double KNOCK_MIN = 0.7;
    private static final double KNOCK_MAX = 1.35;
    private static final double KNOCK_UP = 0.45;
    private static final double MAX_SPEED = 2.2;
    private static final int SHOCK_GROW_TICKS = 3;
    private static final int SHOCK_FADE_TICKS = 4;
    private static final int RIM_PIECES = 6;
    private static final long RIM_HOLD_TICKS = 22;
    private static final int RIM_SINK_TICKS = 8;
    private static final int DEBRIS_PIECES = 4;
    private static final int DEBRIS_FLIGHT_TICKS = 4;
    private static final long AFTERGLOW_TICKS = 20;
    private static final long SCORCH_TICKS = 40;

    // ------------------------------------------------------------------ ultimate: charged creeper
    private static final long SPARK_ARC_PERIOD = 5;
    private static final long RUMBLE_PERIOD = 30;

    /** Stored gunpowder, 0..1. */
    private double charge;
    private long lastSneakTick = Long.MIN_VALUE / 2; // "/ 2": now - this must not overflow
    private boolean announcedFull;
    private long lastBarTick = Long.MIN_VALUE / 2;
    private final ItemDisplay[] powder = new ItemDisplay[POWDER_PIECES];
    private double powderPhase;
    private @Nullable Fuse fuse;
    private long chargedUntil = -1;
    private @Nullable BlockDisplay powerShell;
    /** Displays spawned by blasts and the fuse, for the display budget. */
    private final List<Display> blastDisplays = new ArrayList<>();

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        fuse = null;
        charge = 0;
        chargedUntil = -1;
        powerShell = null;
        blastDisplays.clear();
        for (int i = 0; i < powder.length; i++) {
            if (powder[i] != null && powder[i].isValid()) {
                powder[i].remove();
            }
            powder[i] = null;
        }
    }

    /** Charged: the active has no cooldown at all. */
    @Override
    public double cooldownMultiplier() {
        return isCharged() ? 0.0 : 1.0;
    }

    private boolean isCharged() {
        return ctx().currentTick() < chargedUntil;
    }

    // ------------------------------------------------------------------ passive: hiss

    @Override
    public void onTick(long tick) {
        Player owner = player();
        long now = ctx().currentTick();
        double perTick = 1.0 / Math.max(1, params().ticks("charge_time"));
        boolean sneaking = owner.isSneaking() && fuse == null;
        double before = charge;
        if (sneaking) {
            lastSneakTick = now;
            charge = Math.min(1.0, charge + perTick);
        } else if (charge > 0 && now - lastSneakTick > params().ticks("charge_hold")) {
            charge = Math.max(0.0, charge - perTick);
        }
        if (charge < 1.0) {
            announcedFull = false;
        }
        if (sneaking && charge < 1.0 && tick % CHARGE_FEEDBACK_PERIOD == 0) {
            chargingFx(owner, tick);
        }
        if (charge >= 1.0 && before < 1.0 && !announcedFull) {
            announcedFull = true;
            fullFx(owner);
        }
        if (charge != before && now - lastBarTick >= BAR_PERIOD) {
            lastBarTick = now;
            chargeBar(owner, sneaking);
        }
        updatePowder(owner);
    }

    /** Green motes are drawn into the body and a soft fuse hiss gives a sneaking creeper away. */
    private void chargingFx(Player owner, long tick) {
        Location chest = owner.getLocation().add(0, owner.getHeight() * 0.55, 0);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int motes = Vfx.n(ctx(), 3);
        for (int i = 0; i < motes; i++) {
            double angle = random.nextDouble(TAU);
            Location from = chest.clone().add(Math.cos(angle) * 1.3, random.nextDouble(-0.6, 0.5),
                    Math.sin(angle) * 1.3);
            Vfx.trail(from, chest, i % 2 == 0 ? PALETTE.body() : PALETTE.core(), 6, false);
        }
        Vfx.dot(owner.getLocation().add(random.nextDouble(-0.3, 0.3), 0.1, random.nextDouble(-0.3, 0.3)),
                Particle.SMOKE, null, false);
        if (tick % HISS_PERIOD == 0) {
            Fx.sound(chest, Sound.BLOCK_FIRE_EXTINGUISH, 0.18f, 1.7f + (float) charge * 0.3f + Fx.jitter(0.05f));
        }
    }

    /** The gunpowder is packed: a green pulse and a click only the creeper hears clearly. */
    private void fullFx(Player owner) {
        Location chest = owner.getLocation().add(0, owner.getHeight() * 0.55, 0);
        Vfx.sphere(ctx(), chest, 0.7, 16, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(0.9f), false);
        Vfx.cloud(ctx(), chest, Particle.ITEM, 6, 0.25, 0.05, new ItemStack(Material.GUNPOWDER), false);
        owner.playSound(chest, Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.PLAYERS, 0.7f, 1.6f);
        owner.playSound(chest, Sound.ENTITY_CREEPER_HURT, SoundCategory.PLAYERS, 0.5f, 1.4f);
        owner.sendActionBar(Text.mm("<" + GREEN + "><b>화약 가득!</b></" + GREEN + "> <gray>다음 자폭 피해 +"
                + AbilityInfo.formatNumber(params().num("charge_damage")) + "%</gray>"));
    }

    private void chargeBar(Player owner, boolean sneaking) {
        if (charge >= 1.0 && sneaking) {
            return; // the "full" message stays up
        }
        int filled = (int) Math.round(charge * 10);
        String bar = "<" + GREEN + ">" + "■".repeat(filled) + "</" + GREEN + "><dark_gray>" + "■".repeat(10 - filled)
                + "</dark_gray>";
        String state = sneaking ? "<gray>충전 중</gray>" : charge > 0 ? "<gray>흩어지는 중</gray>" : "<gray>비었음</gray>";
        owner.sendActionBar(Text.mm("<" + GREEN + ">화약</" + GREEN + "> " + bar + " <white>"
                + Math.round(charge * 100) + "%</white> " + state));
    }

    /** One pinch of gunpowder per third of the charge circles the waist. */
    private void updatePowder(Player owner) {
        boolean hidden = concealed(owner);
        powderPhase += POWDER_SPIN;
        double scale = Body.scaleOf(owner);
        for (int i = 0; i < POWDER_PIECES; i++) {
            boolean wanted = !hidden && charge >= (i + 1.0) / POWDER_PIECES - 1.0E-9;
            ItemDisplay piece = powder[i];
            if (piece != null && !piece.isValid()) {
                piece = null;
                powder[i] = null;
            }
            if (wanted && piece == null) {
                piece = Displays.item(ctx(), owner, powderSpot(owner, i, scale), new ItemStack(Material.GUNPOWDER),
                        Displays.centered(0, 0, 0, 0.05f, 0.05f, 0.05f, 0, 0, 0), 0);
                piece.setTeleportDuration(1);
                float size = (float) (POWDER_SCALE * scale);
                Displays.animateNextTick(ctx(), owner, piece, Displays.centered(0, 0, 0, size, size, size, 0, 0, 0),
                        4);
                powder[i] = piece;
                Vfx.cloud(ctx(), piece.getLocation(), Particle.SMOKE, 2, 0.05, 0.01, null, false);
            } else if (!wanted && piece != null) {
                Vfx.cloud(ctx(), piece.getLocation(), Particle.SMOKE, 2, 0.05, 0.01, null, false);
                Displays.shrinkAway(ctx(), owner, piece, 4);
                powder[i] = null;
            } else if (piece != null) {
                piece.teleport(powderSpot(owner, i, scale));
            }
        }
    }

    private Location powderSpot(Player owner, int index, double scale) {
        double angle = powderPhase + index * TAU / POWDER_PIECES;
        double bob = Math.sin(powderPhase * 0.7 + index * 2.1) * 0.08;
        double height = (POWDER_HEIGHT + bob) * scale * (owner.isSneaking() ? 0.85 : 1.0);
        Location at = owner.getLocation().add(Math.cos(angle) * POWDER_ORBIT * scale, height,
                Math.sin(angle) * POWDER_ORBIT * scale);
        at.setYaw((float) Math.toDegrees(angle) * 2);
        at.setPitch(0);
        return at;
    }

    // ------------------------------------------------------------------ active: self-destruct

    @Override
    public ActivationResult onActive() {
        if (fuse != null) {
            return ActivationResult.fail("이미 폭발 직전입니다");
        }
        Fuse lit = new Fuse(isCharged());
        fuse = lit;
        lit.light();
        return ActivationResult.success();
    }

    /** The burning fuse: the creeper swells, blinks white and hisses, then blows up. */
    private final class Fuse implements LongPredicate {

        private final long ticks;
        private final double swell;
        private @Nullable NamespacedKey swellKey;
        private @Nullable BlockDisplay bubble;
        private boolean flashing;
        private float spin;

        Fuse(boolean charged) {
            this.ticks = Math.max(2, params().ticks(charged ? "ult_fuse" : "fuse"));
            this.swell = roomToSwell(player());
            if (!charged) {
                double slow = Math.clamp(params().num("fuse_slow") / 100.0, 0.0, 1.0);
                if (slow > 0) {
                    ctx().addModifier(player(), player(), Attribute.MOVEMENT_SPEED, -slow,
                            Operation.MULTIPLY_SCALAR_1, ticks + 2);
                }
            }
        }

        void light() {
            Player owner = player();
            Location chest = owner.getLocation().add(0, owner.getHeight() * 0.55, 0);
            Fx.sound(chest, Sound.ENTITY_CREEPER_PRIMED, 1.0f, 1.0f + Fx.jitter(0.05f));
            Fx.sound(chest, Sound.ENTITY_TNT_PRIMED, 0.5f, 1.6f + Fx.jitter(0.05f));
            Vfx.cloud(ctx(), chest, Particle.SMOKE, 6, 0.25, 0.02, null, false);
            if (budgetFor(1)) {
                Location at = chest.clone();
                at.setYaw(0);
                at.setPitch(0);
                bubble = Displays.block(ctx(), owner, at, BUBBLE.createBlockData(),
                        diamond(0.3f, 0), ticks + 6);
                bubble.setTeleportDuration(1);
                blastDisplays.add(bubble);
                float size = bubbleSize(owner, 0);
                Displays.animateNextTick(ctx(), owner, bubble, diamond(size, 0), 3);
            }
            Loop.repeat(ctx(), owner, 0, 1, ticks, this, this::dispose);
        }

        @Override
        public boolean test(long run) {
            Player owner = player();
            double progress = (double) (run + 1) / ticks;
            if (run % SWELL_PERIOD == 0) {
                swellTo(owner, swell * progress);
            }
            long left = ticks - run - 1;
            long period = left > 16 ? 6 : left > 8 ? 4 : 2;
            boolean flash = (left / Math.max(1, period / 2)) % 2 == 0;
            Location chest = owner.getLocation().add(0, owner.getHeight() * 0.55, 0);
            spin += 6 + 18 * (float) progress;
            if (bubble != null && bubble.isValid()) {
                Location at = chest.clone();
                at.setYaw(0);
                at.setPitch(0);
                bubble.teleport(at);
                if (flash != flashing) {
                    bubble.setBlock((flash ? BUBBLE_FLASH : BUBBLE).createBlockData());
                }
                if (run > 0 && run % 2 == 0) {
                    Displays.animate(bubble, diamond(bubbleSize(owner, progress), spin), 2);
                }
            }
            if (flash && !flashing) {
                Vfx.sphere(ctx(), chest, 0.75 + 0.35 * progress, 14, Particle.DUST, WHITE_FLASH, false);
                Fx.sound(chest, Sound.BLOCK_NOTE_BLOCK_HAT, 0.4f, 0.8f + (float) progress);
            }
            flashing = flash;
            if (run % 2 == 0) {
                telegraph(owner, progress, run);
            }
            if (run % 3 == 0) {
                Location head = owner.getEyeLocation().add(0, 0.25, 0);
                Vfx.cloud(ctx(), head, Particle.SMOKE, 2, 0.15, 0.02, null, false);
                Vfx.dot(head, Particle.ELECTRIC_SPARK, null, false);
            }
            if (left <= 0) {
                fuse = null;
                dispose();
                detonate(isCharged());
                return false;
            }
            return true;
        }

        /** The blast radius on the ground, with a white ring closing in as the fuse runs out. */
        private void telegraph(Player owner, double progress, long run) {
            double radius = blastRadius(isCharged());
            Location floor = floorUnder(owner.getLocation());
            int points = (int) Math.max(16, Math.min(36, radius * 7));
            Vfx.ring(ctx(), floor, radius, points, run * 0.05, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.1f),
                    true);
            double closing = 0.4 + (radius - 0.4) * (1 - progress);
            Vfx.ring(ctx(), floor.clone().add(0, 0.05, 0), closing, (int) Math.max(8, closing * 5), -run * 0.12,
                    Particle.DUST, WHITE_FLASH, false);
        }

        private void swellTo(Player owner, double amount) {
            NamespacedKey old = swellKey;
            swellKey = null;
            if (old != null) {
                ctx().removeModifier(owner, old);
            }
            if (amount > 0.005) {
                swellKey = ctx().addModifier(owner, owner, Attribute.SCALE, amount, Operation.MULTIPLY_SCALAR_1,
                        ticks + 4);
            }
        }

        /** Runs once, whether the creeper blew up or the fuse was cut short. */
        void dispose() {
            Player owner = player();
            if (swellKey != null) {
                ctx().removeModifier(owner, swellKey);
                swellKey = null;
            }
            if (bubble != null && bubble.isValid()) {
                bubble.remove();
            }
            bubble = null;
            if (fuse == this) {
                fuse = null;
            }
        }
    }

    /** How much the body may swell where it stands (none under a low ceiling or in a tight spot). */
    private static double roomToSwell(Player owner) {
        Body body = Body.standing(owner);
        for (double swell = SWELL_MAX; swell > 0.01; swell -= 0.1) {
            double scale = 1 + swell;
            if (Spots.isFree(owner.getWorld(), owner.getLocation(), body.width() * scale, body.height() * scale)) {
                return swell;
            }
        }
        return 0;
    }

    private static float bubbleSize(Player owner, double progress) {
        double base = Math.max(owner.getHeight(), owner.getWidth()) * 0.75;
        return (float) (base * (BUBBLE_START + (BUBBLE_END - BUBBLE_START) * progress));
    }

    private double blastRadius(boolean charged) {
        return Math.max(0.5, params().num("blast_radius") + charge * Math.max(0, params().num("charge_radius"))
                + (charged ? Math.max(0, params().num("ult_radius")) : 0));
    }

    /** The explosion: damage falling off with distance, knockback, the creeper's own share and the look. */
    private void detonate(boolean charged) {
        Player owner = player();
        Location center = owner.getLocation().add(0, owner.getHeight() * 0.5, 0);
        double radius = blastRadius(charged);
        double multiplier = (1 + charge * Math.max(0, params().num("charge_damage")) / 100.0)
                * (charged ? 1 + Math.max(0, params().num("ult_damage")) / 100.0 : 1.0);
        double max = Math.max(0, params().num("blast_damage")) * multiplier;
        double min = Math.min(max, Math.max(0, params().num("blast_min_damage")) * multiplier);
        boolean boosted = charge > 0.05;
        charge = 0;
        lastSneakTick = Long.MIN_VALUE / 2;

        int hits = 0;
        for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius)) {
            if (!Spots.clearPath(center, Fx.body(enemy)) && !Spots.clearPath(center, enemy.getEyeLocation())) {
                continue; // a wall takes the blast
            }
            double closeness = 1 - Math.clamp(distanceToBox(center, enemy.getBoundingBox()) / radius, 0.0, 1.0);
            Combat.hit(ctx(), owner, enemy, min + (max - min) * closeness);
            hits++;
            if (!Combat.isTargetable(ctx(), owner, enemy)) {
                continue;
            }
            Vector away = Motion.horizontal(enemy.getLocation().toVector().subtract(center.toVector()));
            if (away.lengthSquared() < 1.0E-6) {
                double angle = ThreadLocalRandom.current().nextDouble(TAU);
                away = new Vector(Math.cos(angle), 0, Math.sin(angle));
            }
            double speed = KNOCK_MIN + (KNOCK_MAX - KNOCK_MIN) * closeness;
            Motion.push(enemy, away.multiply(speed).setY(KNOCK_UP), MAX_SPEED);
            enemy.setFallDistance(0);
            if (visible(owner, enemy)) {
                Vfx.burst(ctx(), Fx.body(enemy), charged ? POWER : PALETTE, 0.5, Particle.POOF, false);
            }
        }

        blastFx(owner, center, radius, charged, boosted);
        if (!charged) {
            hurtSelf(owner, max * Math.clamp(params().num("self_damage") / 100.0, 0.0, 1.0));
        }
        String color = charged ? BLUE : GREEN;
        owner.sendActionBar(Text.mm("<" + color + "><b>콰광!</b></" + color + "> "
                + (hits > 0 ? "<white>" + hits + "명 적중</white>" : "<gray>아무도 없었다…</gray>")
                + (boosted ? " <" + GREEN + ">(화약 강화)</" + GREEN + ">" : "")));
    }

    /** The creeper's own share of the blast: never lethal. */
    private void hurtSelf(Player owner, double amount) {
        double damage = Math.min(amount, owner.getHealth() - 1);
        if (damage > 0 && ctx().isAlive(owner)) {
            owner.setNoDamageTicks(0);
            owner.damage(damage, DamageSource.builder(DamageType.MAGIC).build());
        }
    }

    // ------------------------------------------------------------------ blast look

    private void blastFx(Player owner, Location center, double radius, boolean charged, boolean boosted) {
        World world = center.getWorld();
        Vfx.Palette palette = charged ? POWER : PALETTE;
        Location floor = floorUnder(center);
        world.spawnParticle(Particle.FLASH, center, 1, 0, 0, 0, 0, palette.core(), true);
        world.spawnParticle(Particle.EXPLOSION_EMITTER, center, 1, 0, 0, 0, 0, null, true);
        Vfx.burst(ctx(), center, palette, 1.2, Particle.POOF, true);
        Vfx.cloud(ctx(), center, Particle.ITEM, 14, 0.5, 0.18, new ItemStack(Material.GUNPOWDER), false);
        Vfx.shockwave(ctx(), owner, floor.clone().add(0, 0.15, 0), 0.5, radius, 5, Particle.DUST_COLOR_TRANSITION,
                palette.glow(1.5f), true);
        Vfx.shockwave(ctx(), owner, floor.clone().add(0, 0.35, 0), 0.3, radius * 0.9, 6, Particle.CLOUD, null, false);
        Fx.sound(center, Sound.ENTITY_GENERIC_EXPLODE, 1.6f, 0.75f + Fx.jitter(0.05f));
        Fx.sound(center, Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, 0.7f, 0.6f + Fx.jitter(0.05f));
        Fx.sound(center, Sound.ENTITY_CREEPER_DEATH, 0.6f, 0.8f + Fx.jitter(0.1f));
        if (boosted) {
            Fx.sound(center, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 1.0f, 0.7f);
        }
        if (charged) {
            world.strikeLightningEffect(floor);
            Fx.sound(center, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 1.2f, 1.1f + Fx.jitter(0.05f));
            Vfx.ring(ctx(), center, radius * 0.6, 24, 0, Particle.ELECTRIC_SPARK, null, true);
        }
        shockSphere(owner, center, radius, charged);
        crater(owner, floor, radius, charged);
        debris(owner, floor);
        smokeColumn(owner, center, radius, charged);
    }

    /** Two nested glass cubes (diamond-turned) burst out to most of the blast radius and pop. */
    private void shockSphere(Player owner, Location center, double radius, boolean charged) {
        Location at = center.clone();
        at.setYaw(0);
        at.setPitch(0);
        Material[] layers = charged ? new Material[] {SHOCK_INNER, POWER_SHOCK} : new Material[] {SHOCK_INNER,
                SHOCK_OUTER};
        for (int i = 0; i < layers.length; i++) {
            if (!budgetFor(1)) {
                return;
            }
            float end = (float) (radius * (i == 0 ? 0.75 : 1.15));
            float turn = i * 30f;
            BlockDisplay layer = Displays.block(ctx(), owner, at, layers[i].createBlockData(), diamond(0.4f, turn),
                    SHOCK_GROW_TICKS + SHOCK_FADE_TICKS + 4);
            layer.setViewRange(2.0f);
            blastDisplays.add(layer);
            Displays.animateNextTick(ctx(), owner, layer, diamond(end, turn + 40), SHOCK_GROW_TICKS);
            ctx().runLater(owner, SHOCK_GROW_TICKS + 1, () -> {
                if (layer.isValid()) {
                    Displays.shrinkAway(ctx(), owner, layer, SHOCK_FADE_TICKS);
                }
            });
        }
    }

    /** A crater: a scorch mark and a rim of earth clods that pop out of the ground, then sink back. */
    private void crater(Player owner, Location floor, double radius, boolean charged) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Location at = floor.clone().add(0, 0.02, 0);
        at.setYaw(0);
        at.setPitch(0);
        if (budgetFor(1)) {
            float yaw = random.nextFloat() * 90;
            BlockDisplay scorch = Displays.block(ctx(), owner, at, SCORCH.createBlockData(),
                    Displays.transform(0, 0, 0, 0.3f, 0.02f, 0.3f, yaw, 0, 0), SCORCH_TICKS + 12);
            blastDisplays.add(scorch);
            float size = (float) Math.min(3.0, radius * 0.55);
            Displays.animateNextTick(ctx(), owner, scorch, Displays.transform(0, 0, 0, size, 0.02f, size, yaw, 0, 0),
                    3);
            ctx().runLater(owner, SCORCH_TICKS, () -> {
                if (scorch.isValid()) {
                    Displays.shrinkAway(ctx(), owner, scorch, 10);
                }
            });
        }
        double rim = Math.min(2.2, radius * 0.4);
        double offset = random.nextDouble(TAU);
        for (int i = 0; i < RIM_PIECES; i++) {
            if (!budgetFor(1)) {
                return;
            }
            double angle = offset + i * TAU / RIM_PIECES;
            float yaw = (float) Math.toDegrees(Math.atan2(-Math.cos(angle), Math.sin(angle)));
            float x = (float) (Math.cos(angle) * rim);
            float z = (float) (Math.sin(angle) * rim);
            BlockData data = (charged ? CHARGED_RIM : RIM[i % RIM.length]).createBlockData();
            BlockDisplay clod = Displays.block(ctx(), owner, at, data,
                    Displays.transform(x * 0.6f, -0.3f, z * 0.6f, 0.5f, 0.2f, 0.2f, yaw, 0, 0),
                    RIM_HOLD_TICKS + RIM_SINK_TICKS + 8);
            blastDisplays.add(clod);
            float tilt = 30f + random.nextFloat() * 15f;
            Displays.animateNextTick(ctx(), owner, clod,
                    Displays.transform(x, 0.12f, z, 0.85f, 0.4f, 0.3f, yaw, -tilt, 0), 3);
            ctx().runLater(owner, RIM_HOLD_TICKS, () -> {
                if (clod.isValid()) {
                    Displays.animate(clod, Displays.transform(x, -0.35f, z, 0.6f, 0.05f, 0.2f, yaw, -tilt, 0),
                            RIM_SINK_TICKS);
                    ctx().runLater(owner, RIM_SINK_TICKS, () -> {
                        if (clod.isValid()) {
                            clod.remove();
                        }
                    });
                }
            });
        }
    }

    /** Clods of the ground the creeper stood on are thrown out in arcs and crumble. */
    private void debris(Player owner, Location floor) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        BlockData ground = groundUnder(floor);
        World world = floor.getWorld();
        world.spawnParticle(Particle.BLOCK, floor.clone().add(0, 0.2, 0), Vfx.n(ctx(), 24), 0.8, 0.2, 0.8, 0.3,
                ground);
        for (int i = 0; i < DEBRIS_PIECES; i++) {
            if (!budgetFor(1)) {
                return;
            }
            double angle = random.nextDouble(TAU);
            double reach = random.nextDouble(1.8, 3.4);
            Vector out = new Vector(Math.cos(angle), 0, Math.sin(angle));
            Location start = floor.clone().add(0, 0.4, 0);
            start.setYaw(0);
            start.setPitch(0);
            float size = 0.2f + random.nextFloat() * 0.15f;
            BlockDisplay clod = Displays.block(ctx(), owner, start, ground, Displays.scaled(size),
                    DEBRIS_FLIGHT_TICKS * 2L + 12);
            blastDisplays.add(clod);
            clod.setTeleportDuration(DEBRIS_FLIGHT_TICKS);
            Location apex = start.clone().add(out.clone().multiply(reach * 0.5)).add(0, 1.2 + random.nextDouble(), 0);
            Location land = floor.clone().add(out.clone().multiply(reach)).add(0, size * 0.5, 0);
            land.setYaw(0);
            land.setPitch(0);
            ctx().runLater(owner, 1, () -> {
                if (clod.isValid()) {
                    clod.teleport(apex);
                    Displays.animate(clod, Displays.transform(0, 0, 0, size, size, size, random.nextFloat() * 180,
                            random.nextFloat() * 180, 0), DEBRIS_FLIGHT_TICKS * 2);
                }
            });
            ctx().runLater(owner, 1 + DEBRIS_FLIGHT_TICKS, () -> {
                if (clod.isValid()) {
                    clod.teleport(land);
                }
            });
            ctx().runLater(owner, 1 + DEBRIS_FLIGHT_TICKS * 2L, () -> {
                if (clod.isValid()) {
                    world.spawnParticle(Particle.BLOCK, clod.getLocation(), Vfx.n(ctx(), 5), 0.1, 0.05, 0.1, 0.05,
                            ground);
                    Displays.shrinkAway(ctx(), owner, clod, 5);
                }
            });
        }
    }

    /** Afterglow: soot and smoke rising from the crater, green embers drifting off. */
    private void smokeColumn(Player owner, Location center, double radius, boolean charged) {
        Location base = center.clone();
        Loop.repeat(ctx(), owner, 2, 2, AFTERGLOW_TICKS / 2, run -> {
            double fade = 1 - (double) run / (AFTERGLOW_TICKS / 2.0);
            Location at = Vfx.scatter(base, radius * 0.35);
            at.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, at, 0, 0, 0.06, 0, 1, null, false);
            if (run % 2 == 0) {
                Vfx.cloud(ctx(), base, Particle.LARGE_SMOKE, (int) Math.max(1, Math.round(3 * fade)), radius * 0.25,
                        0.02, null, false);
            }
            Vfx.dot(Vfx.scatter(base, radius * 0.6).add(0, ThreadLocalRandom.current().nextDouble(-0.5, 0.8), 0),
                    Particle.DUST_COLOR_TRANSITION, (charged ? POWER : PALETTE).fade(0.9f), false);
            Vfx.dot(Vfx.scatter(base, radius * 0.5), Particle.DUST, SOOT, false);
            return true;
        });
    }

    // ------------------------------------------------------------------ ultimate: charged creeper

    @Override
    public ActivationResult onUltimate() {
        if (isCharged()) {
            return ActivationResult.fail("이미 충전된 상태입니다");
        }
        Player owner = player();
        long duration = Math.max(20, params().ticks("ult_duration"));
        chargedUntil = ctx().currentTick() + duration;
        ctx().reduceCooldown(owner, 1_000_000);

        Location feet = owner.getLocation();
        Location chest = feet.clone().add(0, owner.getHeight() * 0.55, 0);
        World world = feet.getWorld();
        world.strikeLightningEffect(feet);
        world.spawnParticle(Particle.FLASH, chest, 1, 0, 0, 0, 0, POWER.core(), true);
        Vfx.burst(ctx(), chest, POWER, 1.0, Particle.ELECTRIC_SPARK, true);
        Vfx.helix(ctx(), feet, 0.9, owner.getHeight() + 1.2, 2, 2, 16, 0, Particle.ELECTRIC_SPARK, null, true);
        Vfx.shockwave(ctx(), owner, feet.clone().add(0, 0.1, 0), 0.4, 3.0, 5, Particle.DUST_COLOR_TRANSITION,
                POWER.glow(1.3f), true);
        Fx.sound(feet, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.4f, 1.0f + Fx.jitter(0.05f));
        Fx.sound(feet, Sound.ENTITY_CREEPER_PRIMED, 1.0f, 0.6f);
        Fx.sound(feet, Sound.BLOCK_BEACON_POWER_SELECT, 1.0f, 1.4f);
        owner.sendActionBar(Text.mm("<" + BLUE + "><b>⚡ 충전된 크리퍼!</b></" + BLUE + "> <gray>"
                + Fx.seconds(duration) + "초간 자폭 쿨타임 없음</gray>"));

        if (powerShell != null && powerShell.isValid()) {
            powerShell.remove();
        }
        Location at = chest.clone();
        at.setYaw(0);
        at.setPitch(0);
        BlockDisplay shell = Displays.block(ctx(), owner, at, POWER_SHELL.createBlockData(), diamond(0.2f, 0),
                duration + 10);
        shell.setTeleportDuration(1);
        powerShell = shell;
        Displays.animateNextTick(ctx(), owner, shell, diamond(auraSize(owner), 0), 5);
        Loop.repeat(ctx(), owner, 1, 1, duration, run -> aura(owner, run), this::powerDown);
        return ActivationResult.success();
    }

    private static float auraSize(Player owner) {
        return (float) (Math.max(owner.getHeight(), owner.getWidth()) * 0.95);
    }

    /** One tick of the charged aura: a turning shell of blue light, swirling sparks and crackling arcs. */
    private boolean aura(Player owner, long run) {
        if (!isCharged()) {
            return false;
        }
        Location feet = owner.getLocation();
        Location chest = feet.clone().add(0, owner.getHeight() * 0.55, 0);
        BlockDisplay shell = powerShell;
        if (shell != null && shell.isValid()) {
            boolean hidden = concealed(owner);
            Location at = chest.clone();
            at.setYaw(0);
            at.setPitch(0);
            shell.teleport(at);
            if (run % 2 == 0) {
                Displays.animate(shell, diamond(hidden ? 0.001f : auraSize(owner), run * 4f), 2);
            }
            if (hidden) {
                return true;
            }
        }
        double height = owner.getHeight();
        Vfx.helix(ctx(), feet, 0.55 * Body.scaleOf(owner), height, 1.0, 2, 5, run * 0.35,
                Particle.DUST_COLOR_TRANSITION, POWER.glow(0.8f), false);
        if (run % 2 == 0) {
            Vfx.dot(chest.clone().add(ThreadLocalRandom.current().nextDouble(-0.4, 0.4),
                    ThreadLocalRandom.current().nextDouble(-0.6, 0.7), ThreadLocalRandom.current().nextDouble(-0.4,
                            0.4)), Particle.ELECTRIC_SPARK, null, false);
        }
        if (run % SPARK_ARC_PERIOD == 0) {
            arc(chest, 0.7);
        }
        if (run % RUMBLE_PERIOD == 0) {
            Fx.sound(feet, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.25f, 1.8f + Fx.jitter(0.1f));
        }
        long left = chargedUntil - ctx().currentTick();
        if (run % 10 == 0) {
            owner.sendActionBar(Text.mm("<" + BLUE + ">⚡ 충전</" + BLUE + "> <white>" + Fx.seconds(left)
                    + "초</white> <gray>자폭 쿨타임 없음</gray>"));
        }
        return true;
    }

    /** A jagged spark arc between two points on a sphere around {@code center}. */
    private void arc(Location center, double radius) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Vector a = randomUnit(random).multiply(radius);
        Vector b = randomUnit(random).multiply(radius);
        Location from = center.clone().add(a);
        Location to = center.clone().add(b);
        Location mid = from.clone().add(to).multiply(0.5).add(randomUnit(random).multiply(0.25));
        Vfx.line(ctx(), from, mid, 6, Particle.ELECTRIC_SPARK, null, false);
        Vfx.line(ctx(), mid, to, 6, Particle.ELECTRIC_SPARK, null, false);
        Vfx.dot(mid, Particle.DUST, POWER.dust(0.8f), false);
        Fx.sound(center, Sound.BLOCK_COPPER_BULB_TURN_ON, 0.25f, 1.6f + Fx.jitter(0.2f));
    }

    /** The charge wears off: the blue shell fizzles and sparks rain down. */
    private void powerDown() {
        Player owner = player();
        chargedUntil = -1;
        BlockDisplay shell = powerShell;
        powerShell = null;
        if (shell != null && shell.isValid()) {
            Displays.shrinkAway(ctx(), owner, shell, 6);
        }
        Location chest = owner.getLocation().add(0, owner.getHeight() * 0.55, 0);
        Vfx.cloud(ctx(), chest, Particle.ELECTRIC_SPARK, 10, 0.4, 0.05, null, false);
        Vfx.cloud(ctx(), chest, Particle.SMOKE, 6, 0.3, 0.02, null, false);
        Fx.sound(chest, Sound.BLOCK_BEACON_DEACTIVATE, 0.8f, 1.4f);
        Fx.sound(chest, Sound.BLOCK_FIRE_EXTINGUISH, 0.5f, 1.2f);
        owner.sendActionBar(Text.mm("<gray>충전이 풀렸습니다</gray>"));
    }

    // ------------------------------------------------------------------ helpers

    /** False for players hidden from the owner (stealth): no effect may give their position away. */
    private static boolean visible(Player owner, LivingEntity entity) {
        return !(entity instanceof Player hidden) || owner.canSee(hidden);
    }

    /** True while the owner is invisible: nothing of this ability may give its position away then. */
    private static boolean concealed(Player owner) {
        return owner.isInvisible() || owner.hasPotionEffect(PotionEffectType.INVISIBILITY);
    }

    /** Keeps the display budget: forgets removed displays and says whether {@code count} more fit. */
    private boolean budgetFor(int count) {
        blastDisplays.removeIf(display -> !display.isValid());
        int alive = blastDisplays.size() + (powerShell != null && powerShell.isValid() ? 1 : 0);
        for (ItemDisplay piece : powder) {
            if (piece != null && piece.isValid()) {
                alive++;
            }
        }
        return alive + count <= DISPLAY_BUDGET;
    }

    /** A cube of edge {@code size} turned onto its corner (a rough sphere) and spun by {@code yaw} degrees. */
    private static Transformation diamond(float size, float yaw) {
        Quaternionf rotation = new Quaternionf().rotateY((float) Math.toRadians(yaw))
                .rotateX((float) Math.toRadians(35.26)).rotateZ((float) Math.toRadians(45));
        Vector3f translation = new Quaternionf(rotation).transform(new Vector3f(size / 2)).negate();
        return new Transformation(translation, rotation, new Vector3f(size), new Quaternionf());
    }

    /** The ground right below {@code at} (at most 3 blocks down), or {@code at} itself. */
    private static Location floorUnder(Location at) {
        Location floor = at.clone();
        RayTraceResult ground = at.getWorld().rayTraceBlocks(at.clone().add(0, 0.1, 0), new Vector(0, -1, 0), 3.5,
                FluidCollisionMode.NEVER, true);
        if (ground != null) {
            floor.setY(ground.getHitPosition().getY());
        }
        return floor;
    }

    /** The solid block below {@code floor}, dirt over air, fluids and plants. */
    private static BlockData groundUnder(Location floor) {
        BlockData ground = floor.clone().subtract(0, 0.2, 0).getBlock().getBlockData();
        return ground.getMaterial().isSolid() ? ground : FALLBACK_GROUND.createBlockData();
    }

    private static double distanceToBox(Location point, BoundingBox box) {
        double dx = Math.max(Math.max(box.getMinX() - point.getX(), 0), point.getX() - box.getMaxX());
        double dy = Math.max(Math.max(box.getMinY() - point.getY(), 0), point.getY() - box.getMaxY());
        double dz = Math.max(Math.max(box.getMinZ() - point.getZ(), 0), point.getZ() - box.getMaxZ());
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static Vector randomUnit(ThreadLocalRandom random) {
        Vector vector = new Vector(random.nextGaussian(), random.nextGaussian(), random.nextGaussian());
        return vector.lengthSquared() < 1.0E-6 ? new Vector(0, 1, 0) : vector.normalize();
    }
}
