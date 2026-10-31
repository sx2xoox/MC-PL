package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.List;
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
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.tag.DamageTypeTags;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 빛의 사도 — a herald of the sun. Sunlight shields it by day and lights its way by night, the active is a flash
 * that blinds and reveals the enemies in front (longest for those who stared right into it), and the ultimate calls
 * a pillar of judgment down from the sky that smites enemies and heals allies.
 *
 * <p>Look (white, warm gold, deep amber): a blocked share of a hit flares a little sun behind the herald's head, and
 * at night a soft golden halo floats over it. The flash is a camera-facing star blooming in the hand, thin white and
 * gold shafts of light shooting across the cone, a cone of light racing forward and sparks thrown along it; dazzled
 * enemies have motes circling their eyes. The judgment lays a slowly turning eight-pointed star of golden glass on the
 * ground with a particle seal under a thin golden thread from the sky that thickens while a bell tolls, then a column
 * of light (white core, layered yellow and white glass) slams down with a flash, a golden shockwave and rising
 * sparks, the star flares, and the column fades from the outside in while light motes drift down.
 */
public final class LightAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("light", "빛의 사도", Material.SUNFLOWER)
            .color(TextColor.color(0xFFD54F))
            .difficulty(1)
            .passive("태양의 가호",
                    "낮에는 받는 피해가 {day_reduction}% 줄어듭니다. 밤에는 몸에서 은은한 빛이 나 어둠 속에서도 "
                            + "앞이 잘 보입니다(야간 투시).")
            .active("섬광",
                    "전방 {flash_range}블록, {flash_angle}° 부채꼴에 섬광을 터뜨려 적에게 {flash_damage} 피해를 주고 "
                            + "{flash_glow}초간 발광시킵니다. 나를 바라보던 적은 {flash_blind}초, 고개를 돌린 적은 그 절반만큼 "
                            + "실명합니다.",
                    9)
            .ultimate("심판의 빛",
                    "조준한 곳({ult_range}블록 이내)에 {ult_delay}초 뒤 하늘에서 거대한 빛기둥이 내리꽂힙니다. "
                            + "빛기둥 반경 {ult_radius}블록 안의 적은 {ult_damage} 피해를 받고, 그 안에 있는 나와 아군은 "
                            + "{ult_heal} 회복합니다.")
            .param("day_reduction", 15)
            .param("flash_range", 8)
            .param("flash_angle", 80)
            .param("flash_damage", 3)
            .param("flash_blind", 1.5)
            .param("flash_glow", 5)
            .param("ult_range", 30)
            .param("ult_delay", 2)
            .param("ult_radius", 5)
            .param("ult_damage", 9)
            .param("ult_heal", 6)
            .build();

    private static final String GOLD = "#FFD54F";
    /** White core, warm gold body, deep amber edge. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFFDF2, 0xFFD54F, 0xE09A00);
    private static final Color FLASH_WHITE = Color.fromRGB(0xFFFFFF);
    private static final Particle.DustTransition GLOW = PALETTE.glow(1.2f);
    private static final Particle.DustTransition GLOW_SMALL = PALETTE.glow(0.7f);
    private static final Particle.DustTransition FADE = PALETTE.fade(1.0f);
    private static final Particle.DustOptions GOLD_DUST = PALETTE.dust(0.9f);
    private static final Particle.DustOptions AMBER_DUST = new Particle.DustOptions(PALETTE.edge(), 1.1f);

    // passive
    private static final long PASSIVE_PERIOD = 5;
    private static final long NIGHT_VISION_PERIOD = 20;
    /** Night vision flickers on the client below 10 s, so it is kept well above that. */
    private static final int NIGHT_VISION_TICKS = 400;
    private static final int NIGHT_VISION_REFRESH = 300;
    private static final long WARD_FX_GAP = 10;
    private static final long WARD_GLYPH_TICKS = 12;

    // active
    /** An enemy whose view is within this angle of the herald counts as looking at the flash. */
    private static final double FACING_COS = Math.cos(Math.toRadians(60));
    private static final int FLASH_RAYS = 26;
    private static final int FLASH_FRONT_TICKS = 4;
    private static final int STAR_TICKS = 9;
    private static final int DAZZLE_PERIOD = 3;
    /** Shafts of light fanned across the cone. */
    private static final int SHAFTS = 5;
    private static final float SHAFT_WIDTH = 0.035f;
    private static final int SHAFT_TICKS = 10;
    private static final BlockData[] SHAFT_BLOCKS = {Material.WHITE_CONCRETE.createBlockData(),
            Material.GOLD_BLOCK.createBlockData()};

    // ultimate
    private static final double BEAM_HEIGHT = 30;
    private static final double GROUND_SNAP = 24;
    private static final int PILOT_DROP_TICKS = 8;
    private static final float PILOT_WIDTH = 0.1f;
    private static final float PILOT_WIDTH_MAX = 0.4f;
    private static final long CLOSING_TICKS = 8;
    private static final int BEAM_GROW_TICKS = 3;
    private static final int BEAM_HOLD_TICKS = 6;
    private static final int BEAM_FADE_TICKS = 10;
    private static final int AFTERGLOW_TICKS = 24;
    private static final BlockData BEAM_CORE = Material.WHITE_CONCRETE.createBlockData();
    private static final BlockData BEAM_INNER = Material.YELLOW_STAINED_GLASS.createBlockData();
    private static final BlockData BEAM_OUTER = Material.WHITE_STAINED_GLASS.createBlockData();
    private static final int SEAL_SPOKES = 8;
    /** The seal is two thin squares of golden glass turned 45° to each other: an eight-pointed star. */
    private static final BlockData SEAL_PLATE = Material.YELLOW_STAINED_GLASS.createBlockData();
    private static final float PLATE_THICKNESS = 0.02f;
    private static final int PLATE_GROW_TICKS = 6;
    private static final int PLATE_TURN_PERIOD = 4;

    private boolean nightVision;
    /** Whether it was day at the last check, null before the first one. */
    private @Nullable Boolean daylight;
    private long nextWardFx;
    private @Nullable TextDisplay sunGlyph;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        removeNightVision();
    }

    // ------------------------------------------------------------------ passive: blessing of the sun

    @Override
    public void onTick(long tick) {
        if (tick % PASSIVE_PERIOD != 0) {
            return;
        }
        Player owner = player();
        boolean day = owner.getWorld().isDayTime();
        if (daylight == null || daylight != day) {
            if (daylight != null) {
                dawnOrDusk(owner, day);
            }
            daylight = day;
        }
        if (day) {
            removeNightVision();
            return;
        }
        if (tick % NIGHT_VISION_PERIOD == 0) {
            PotionEffect current = owner.getPotionEffect(PotionEffectType.NIGHT_VISION);
            if (current == null || isOurNightVision(current) && current.getDuration() < NIGHT_VISION_REFRESH) {
                owner.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, NIGHT_VISION_TICKS, 0, true,
                        false, true));
                nightVision = true;
            }
        }
        nightHalo(owner, tick);
    }

    /** At night the herald glows softly: a slow golden halo over the head and a mote now and then. */
    private void nightHalo(Player owner, long tick) {
        Location head = owner.getLocation().add(0, owner.getHeight() + 0.25, 0);
        Vfx.ring(ctx(), head, 0.32, 7, tick * 0.08, Particle.DUST_COLOR_TRANSITION, GLOW_SMALL, false);
        if (tick % (PASSIVE_PERIOD * 3) == 0) {
            double height = 0.3 + ThreadLocalRandom.current().nextDouble(1.4);
            Vfx.dot(Vfx.scatter(owner.getLocation(), 0.7).add(0, height, 0), Particle.END_ROD, null, false);
        }
    }

    /** The passive changes with the time of day: a pulse of light and a word to the herald. */
    private void dawnOrDusk(Player owner, boolean day) {
        Location feet = owner.getLocation().add(0, 0.1, 0);
        Vfx.shockwave(ctx(), owner, feet, 0.3, 1.8, 6, Particle.DUST_COLOR_TRANSITION, day ? GLOW : FADE, false);
        Vfx.helix(ctx(), owner.getLocation(), 0.6, 2.0, 1, 2, 10, 0, Particle.DUST_COLOR_TRANSITION, GLOW_SMALL,
                false);
        Fx.sound(feet, day ? Sound.BLOCK_BEACON_ACTIVATE : Sound.BLOCK_BEACON_DEACTIVATE, 0.5f, 1.6f);
        owner.sendActionBar(Text.mm(day
                ? "<" + GOLD + ">☀ 태양의 가호</" + GOLD + "> <gray>· 받는 피해 감소</gray>"
                : "<" + GOLD + ">☾ 밤의 빛</" + GOLD + "> <gray>· 어둠 속에서도 앞이 보입니다</gray>"));
    }

    /** Our night vision is ambient (no particles), which no potion or beacon gives at level I. */
    private static boolean isOurNightVision(PotionEffect effect) {
        return effect.isAmbient() && effect.getAmplifier() == 0 && !effect.hasParticles();
    }

    private void removeNightVision() {
        if (!nightVision) {
            return;
        }
        nightVision = false;
        Player owner = player();
        PotionEffect current = owner.getPotionEffect(PotionEffectType.NIGHT_VISION);
        if (current != null && isOurNightVision(current)) {
            owner.removePotionEffect(PotionEffectType.NIGHT_VISION);
        }
    }

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled() || event.getDamage() <= 0 || !player().getWorld().isDayTime()) {
            return;
        }
        Tag<DamageType> bypass = DamageTypeTags.BYPASSES_INVULNERABILITY;
        if (bypass != null && bypass.isTagged(event.getDamageSource().getDamageType())) {
            return;
        }
        double reduction = Math.clamp(params().num("day_reduction") / 100.0, 0.0, 1.0);
        if (reduction <= 0) {
            return;
        }
        event.setDamage(event.getDamage() * (1 - reduction));
        long now = ctx().currentTick();
        if (now >= nextWardFx) {
            nextWardFx = now + WARD_FX_GAP;
            sunWard();
        }
    }

    /** A blocked share of a hit: a small sun flares behind the herald's head and light flickers over its body. */
    private void sunWard() {
        Player owner = player();
        Location body = Fx.body(owner);
        double phase = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        Vfx.ring(ctx(), body, 0.55, 10, phase, Particle.DUST_COLOR_TRANSITION, GLOW_SMALL, false);
        Vfx.cloud(ctx(), body, Particle.WAX_OFF, 4, 0.35, 0.5, null, false);
        Fx.sound(body, Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.5f, 1.8f + Fx.jitter(0.1f));
        Fx.sound(body, Sound.BLOCK_BEACON_POWER_SELECT, 0.25f, 2.0f);

        if (sunGlyph != null && sunGlyph.isValid()) {
            sunGlyph.remove();
        }
        Vector back = Motion.horizontalFacing(owner.getLocation()).multiply(-0.35);
        Location behind = owner.getEyeLocation().add(back).add(0, 0.3, 0);
        TextDisplay sun = Displays.text(ctx(), owner, behind,
                Text.mm("<gradient:#FFFFFF:#FFD54F:#E09A00>☀</gradient>"), 0.2f, WARD_GLYPH_TICKS);
        sunGlyph = sun;
        Displays.animateNextTick(ctx(), owner, sun, Displays.centered(0, 0, 0, 1.1f, 1.1f, 1.1f, 0, 0, 30), 3);
        ctx().runLater(owner, WARD_GLYPH_TICKS - 5, () -> {
            if (sun.isValid()) {
                Displays.shrinkAway(ctx(), owner, sun, 4);
            }
        });
    }

    // ------------------------------------------------------------------ active: flash

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        double range = Math.max(0.5, params().num("flash_range"));
        double halfAngle = Math.toRadians(Math.clamp(params().num("flash_angle"), 1.0, 360.0) / 2);
        double minCos = Math.cos(halfAngle);

        List<LivingEntity> flashed = new ArrayList<>();
        for (LivingEntity enemy : ctx().enemiesNear(owner, eye, range)) {
            Location body = Fx.body(enemy);
            Vector to = body.toVector().subtract(eye.toVector());
            double distance = to.length();
            if (distance > 0.8 && to.multiply(1 / distance).dot(direction) < minCos) {
                continue;
            }
            if (!Spots.clearPath(eye, enemy.getEyeLocation()) && !Spots.clearPath(eye, body)) {
                continue;
            }
            flashed.add(enemy);
        }

        flashFx(eye, direction, range, halfAngle);
        double damage = params().num("flash_damage");
        long blind = params().ticks("flash_blind");
        int glow = (int) Math.min(Integer.MAX_VALUE, params().ticks("flash_glow"));
        int dazzled = 0;
        for (LivingEntity enemy : flashed) {
            boolean facing = isLookingAt(enemy, eye);
            Combat.hit(ctx(), owner, enemy, damage);
            if (!Combat.isTargetable(ctx(), owner, enemy)) {
                continue;
            }
            int blindTicks = (int) Math.min(Integer.MAX_VALUE, facing ? blind : blind / 2);
            if (blindTicks > 0) {
                enemy.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, blindTicks, 0, false, true, true));
            }
            if (glow > 0) {
                enemy.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, glow, 0, false, false, true));
            }
            if (facing) {
                dazzled++;
            }
            dazzle(enemy, eye, blindTicks, facing);
        }
        owner.sendActionBar(Text.mm(flashed.isEmpty()
                ? "<" + GOLD + ">✦ 섬광</" + GOLD + ">"
                : "<" + GOLD + ">✦ 섬광</" + GOLD + "> <white>" + flashed.size() + "명 적중</white>"
                        + (dazzled > 0 ? " <gray>· 정면 " + dazzled + "명</gray>" : "")));
        return ActivationResult.success();
    }

    /** True if {@code enemy} looks roughly towards {@code point}: it stared right into the flash. */
    private static boolean isLookingAt(LivingEntity enemy, Location point) {
        Location eye = enemy.getEyeLocation();
        Vector toPoint = point.toVector().subtract(eye.toVector());
        if (toPoint.lengthSquared() < 1.0E-6) {
            return true;
        }
        return eye.getDirection().normalize().dot(toPoint.normalize()) >= FACING_COS;
    }

    /**
     * The flash: a star blooms in the hand and collapses, a cone of light races forward to the range as expanding
     * golden rings, and sparks shoot along the cone.
     */
    private void flashFx(Location eye, Vector direction, double range, double halfAngle) {
        Player owner = player();
        World world = eye.getWorld();
        Location hand = eye.clone().add(direction.clone().multiply(0.7)).subtract(0, 0.15, 0);

        world.spawnParticle(Particle.FLASH, hand, 1, 0, 0, 0, 0, FLASH_WHITE);
        Vfx.cloud(ctx(), hand, Particle.DUST_COLOR_TRANSITION, 10, 0.15, 0, GLOW, false);
        star(hand);
        shafts(hand, direction, range, halfAngle);

        // Sparks thrown along the cone (count 0: each flies along its own direction).
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Vector[] basis = basis(direction);
        int rays = Vfx.n(ctx(), FLASH_RAYS);
        double spread = Math.tan(Math.min(halfAngle, Math.toRadians(80)));
        for (int i = 0; i < rays; i++) {
            double angle = random.nextDouble(Math.PI * 2);
            double r = Math.sqrt(random.nextDouble()) * spread;
            Vector ray = direction.clone().add(basis[0].clone().multiply(Math.cos(angle) * r))
                    .add(basis[1].clone().multiply(Math.sin(angle) * r)).normalize();
            double speed = 0.35 + random.nextDouble() * 0.35;
            world.spawnParticle(i % 3 == 0 ? Particle.WAX_OFF : Particle.END_ROD, hand, 0, ray.getX(), ray.getY(),
                    ray.getZ(), speed);
        }

        // The light front: rings growing with the cone as they race to the range.
        double reach = Math.tan(Math.min(halfAngle, Math.toRadians(75)));
        Loop.repeat(ctx(), owner, 0, 1, FLASH_FRONT_TICKS, step -> {
            double distance = range * (step + 1) / FLASH_FRONT_TICKS;
            Location at = hand.clone().add(direction.clone().multiply(distance));
            double radius = Math.max(0.3, distance * reach * 0.85);
            int points = (int) Math.min(40, 8 + radius * 5);
            perpendicularRing(at, direction, basis, radius, points, step * 0.4, Particle.DUST_COLOR_TRANSITION,
                    step < FLASH_FRONT_TICKS / 2 ? GLOW : FADE);
            if (step % 2 == 0) {
                perpendicularRing(at, direction, basis, radius * 0.55, Math.max(6, points / 2), -step * 0.4,
                        Particle.END_ROD, null);
            }
            return true;
        });

        Fx.sound(hand, Sound.BLOCK_BEACON_POWER_SELECT, 1.0f, 1.9f + Fx.jitter(0.05f));
        Fx.sound(hand, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.8f, 1.7f + Fx.jitter(0.1f));
        Fx.sound(hand, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 2.0f);
        Fx.sound(hand, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.5f, 1.6f + Fx.jitter(0.05f));
    }

    /**
     * Shafts of light: thin white and gold beams shooting from the hand across the cone (stopped by walls), then
     * thinning out.
     */
    private void shafts(Location hand, Vector direction, double range, double halfAngle) {
        Player owner = player();
        World world = hand.getWorld();
        Vector[] basis = basis(direction);
        double phase = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        double spread = Math.tan(Math.min(halfAngle, Math.toRadians(75)) * 0.7);
        for (int i = 0; i < SHAFTS; i++) {
            Vector ray = direction.clone();
            if (i > 0) {
                double angle = phase + i * Math.PI * 2 / (SHAFTS - 1);
                ray.add(basis[0].clone().multiply(Math.cos(angle) * spread))
                        .add(basis[1].clone().multiply(Math.sin(angle) * spread));
            }
            ray.normalize();
            double length = range * (i == 0 ? 1.0 : 0.8);
            RayTraceResult wall = world.rayTraceBlocks(hand, ray, length, FluidCollisionMode.NEVER, true);
            if (wall != null) {
                length = wall.getHitPosition().distance(hand.toVector());
            }
            if (length < 0.5) {
                continue;
            }
            Vector full = ray.clone().multiply(length);
            float width = i == 0 ? SHAFT_WIDTH * 1.6f : SHAFT_WIDTH;
            BlockDisplay shaft = Displays.block(ctx(), owner, hand, SHAFT_BLOCKS[i == 0 ? 0 : 1],
                    beamPose(ray.clone().multiply(0.2), width), SHAFT_TICKS);
            Displays.animateNextTick(ctx(), owner, shaft, beamPose(full, width), 2);
            ctx().runLater(owner, 4, () -> {
                if (shaft.isValid()) {
                    Displays.animate(shaft, beamPose(full, 0.001f), SHAFT_TICKS - 5);
                }
            });
        }
    }

    /** The camera-facing star of light that blooms in the hand and collapses. */
    private void star(Location at) {
        Player owner = player();
        TextDisplay star = Displays.text(ctx(), owner, at,
                Text.mm("<b><gradient:#FFFFFF:#FFD54F:#FFFFFF>✦</gradient></b>"), 0.3f, STAR_TICKS);
        star.setSeeThrough(true);
        Displays.animateNextTick(ctx(), owner, star, Displays.centered(0, 0, 0, 3.2f, 3.2f, 3.2f, 0, 0, 0), 2);
        ctx().runLater(owner, 3, () -> {
            if (star.isValid()) {
                Displays.animate(star, Displays.centered(0, 0, 0, 1.6f, 1.6f, 1.6f, 0, 0, 45), 2);
            }
        });
        ctx().runLater(owner, 5, () -> {
            if (star.isValid()) {
                Displays.shrinkAway(ctx(), owner, star, 4);
            }
        });
    }

    /** A flashed enemy: a flash in its eyes, and motes circling its head while it is blind. */
    private void dazzle(LivingEntity enemy, Location from, int blindTicks, boolean facing) {
        Location eyes = enemy.getEyeLocation();
        World world = enemy.getWorld();
        world.spawnParticle(Particle.FLASH, eyes, 1, 0, 0, 0, 0, facing ? FLASH_WHITE : PALETTE.body());
        Vfx.cloud(ctx(), eyes, Particle.END_ROD, facing ? 8 : 4, 0.2, 0.06, null, false);
        Vfx.trail(from, eyes, PALETTE.core(), 4, false);
        Fx.sound(eyes, Sound.ENTITY_ILLUSIONER_PREPARE_BLINDNESS, 0.7f, 1.7f + Fx.jitter(0.1f));
        if (enemy instanceof Player victim) {
            victim.playSound(victim, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 0.6f, 2.0f);
        }
        long runs = Math.max(1, blindTicks / DAZZLE_PERIOD);
        Loop.repeat(ctx(), player(), DAZZLE_PERIOD, DAZZLE_PERIOD, runs, step -> {
            if (!Combat.isTargetable(ctx(), player(), enemy)) {
                return false;
            }
            Location head = enemy.getEyeLocation().add(0, 0.25, 0);
            double angle = step * 0.9;
            for (int i = 0; i < 3; i++) {
                double a = angle + i * Math.PI * 2 / 3;
                Location mote = head.clone().add(Math.cos(a) * 0.38, Math.sin(step * 0.7 + i) * 0.08,
                        Math.sin(a) * 0.38);
                Vfx.dot(mote, i == 0 ? Particle.END_ROD : Particle.DUST_COLOR_TRANSITION, i == 0 ? null : GLOW_SMALL,
                        false);
            }
            return true;
        });
    }

    // ------------------------------------------------------------------ ultimate: judgment

    @Override
    public ActivationResult onUltimate() {
        double range = params().num("ult_range");
        Location point = aimPoint(range);
        if (point == null) {
            return ActivationResult.fail(AbilityInfo.formatNumber(range) + "블록 안의 땅이나 적을 조준하세요");
        }
        Judgment judgment = new Judgment(point, Math.max(0.5, params().num("ult_radius")),
                Math.max(1, params().ticks("ult_delay")));
        judgment.begin();
        return ActivationResult.success();
    }

    /** The ground under the aimed enemy or the aimed block, null if nothing is in range. */
    private @Nullable Location aimPoint(double range) {
        Player owner = player();
        LivingEntity aimed = ctx().findTarget(owner, range);
        Location point;
        if (aimed != null) {
            point = aimed.getLocation();
        } else {
            Location eye = owner.getEyeLocation();
            RayTraceResult hit = owner.getWorld().rayTraceBlocks(eye, eye.getDirection(), range,
                    FluidCollisionMode.SOURCE_ONLY, true);
            if (hit == null) {
                return null;
            }
            point = hit.getHitPosition().toLocation(owner.getWorld())
                    .subtract(eye.getDirection().multiply(0.2));
        }
        Double ground = groundBelow(point);
        if (ground != null) {
            point.setY(ground);
        }
        point.setYaw(0);
        point.setPitch(0);
        return point;
    }

    /** The top of the first solid block at most {@link #GROUND_SNAP} blocks below {@code point}, null if none. */
    private static @Nullable Double groundBelow(Location point) {
        World world = point.getWorld();
        int x = point.getBlockX();
        int z = point.getBlockZ();
        int start = Math.min(point.getBlockY(), world.getMaxHeight() - 1);
        int end = Math.max(world.getMinHeight(), (int) Math.floor(point.getY() - GROUND_SNAP));
        for (int y = start; y >= end; y--) {
            Block block = world.getBlockAt(x, y, z);
            if (!block.isPassable()) {
                return block.getBoundingBox().getMaxY();
            }
        }
        return null;
    }

    /**
     * The pillar of judgment: a holy seal on the ground and a golden thread from the sky for the delay, then the
     * column of light falls, smites the enemies in it and heals the allies.
     */
    private final class Judgment implements LongPredicate {

        private final Location center;
        private final World world;
        private final double radius;
        private final long delay;
        private @Nullable BlockDisplay pilot;
        private final BlockDisplay[] plates = new BlockDisplay[2];

        Judgment(Location ground, double radius, long delay) {
            this.center = ground.clone().add(0, 0.05, 0);
            this.world = ground.getWorld();
            this.radius = radius;
            this.delay = delay;
        }

        void begin() {
            Player owner = player();
            pilot = Displays.block(ctx(), owner, center, BEAM_CORE,
                    column(PILOT_WIDTH, (float) BEAM_HEIGHT, 0.01f, 0), delay + 2);
            pilot.setViewRange(3.0f);
            Displays.outline(pilot, PALETTE.body());
            Displays.animateNextTick(ctx(), owner, pilot, column(PILOT_WIDTH, 0, (float) BEAM_HEIGHT, 0),
                    PILOT_DROP_TICKS);
            Location floor = center.clone().add(0, 0.01, 0);
            for (int i = 0; i < plates.length; i++) {
                plates[i] = Displays.block(ctx(), owner, floor, SEAL_PLATE,
                        column(0.1f, 0, PLATE_THICKNESS, plateYaw(i, 0)), delay + 16);
                plates[i].setViewRange(2.0f);
                Displays.animateNextTick(ctx(), owner, plates[i],
                        column(plateSize(), 0, PLATE_THICKNESS, plateYaw(i, PLATE_GROW_TICKS)), PLATE_GROW_TICKS);
            }

            Vfx.shockwave(ctx(), owner, center, 0.4, radius, 6, Particle.DUST_COLOR_TRANSITION, GLOW, true);
            Vfx.cloud(ctx(), center.clone().add(0, 0.4, 0), Particle.END_ROD, 16, radius * 0.3, 0.02, null, true);
            Fx.sound(center, Sound.BLOCK_BELL_USE, 2.0f, 0.6f);
            Fx.sound(center, Sound.BLOCK_BEACON_ACTIVATE, 1.5f, 1.2f);
            Fx.sound(owner.getLocation(), Sound.ENTITY_EVOKER_PREPARE_SUMMON, 0.8f, 1.6f);
            Loop.repeat(ctx(), owner, 1, 1, delay, this);
        }

        @Override
        public boolean test(long tick) {
            long left = delay - tick;
            if (left <= 1) {
                impact();
                return false;
            }
            seal(tick);
            fallingLight(tick);
            if (tick >= PLATE_GROW_TICKS && tick % PLATE_TURN_PERIOD == 0) {
                for (int i = 0; i < plates.length; i++) {
                    if (plates[i] != null && plates[i].isValid()) {
                        Displays.animate(plates[i], column(plateSize(), 0, PLATE_THICKNESS,
                                plateYaw(i, tick + PLATE_TURN_PERIOD)), PLATE_TURN_PERIOD);
                    }
                }
            }
            if (left <= CLOSING_TICKS) {
                // The last moment: a ring closes in on the edge of the column.
                double closing = radius * (1 + 0.5 * (left - 1) / CLOSING_TICKS);
                Vfx.ring(ctx(), center.clone().add(0, 0.1, 0), closing, (int) Math.min(48, 14 + closing * 5),
                        -tick * 0.3, Particle.DUST_COLOR_TRANSITION, GLOW, true);
            }
            if (tick == delay / 2) {
                Fx.sound(center, Sound.BLOCK_BELL_RESONATE, 1.4f, 1.0f);
                Fx.sound(center, Sound.BLOCK_BELL_USE, 1.6f, 0.8f);
            }
            if (left == CLOSING_TICKS) {
                Fx.sound(center, Sound.BLOCK_BEACON_AMBIENT, 1.6f, 1.6f);
                Fx.sound(center, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.2f, 1.4f);
            }
            if (tick > PILOT_DROP_TICKS && tick % 2 == 0 && pilot != null && pilot.isValid()) {
                // The thread of light thickens as the judgment nears.
                double progress = (double) (tick - PILOT_DROP_TICKS) / Math.max(1, delay - PILOT_DROP_TICKS);
                float width = (float) (PILOT_WIDTH + (PILOT_WIDTH_MAX - PILOT_WIDTH) * progress);
                Displays.animate(pilot, column(width, 0, (float) BEAM_HEIGHT, (float) (tick * 3)), 2);
            }
            return true;
        }

        /** Square plates whose corners touch the radius. */
        private float plateSize() {
            return (float) (radius * Math.sqrt(2));
        }

        /** The two plates turn slowly in opposite directions. */
        private float plateYaw(int index, long tick) {
            return index * 45f + (index == 0 ? 2.5f : -2.5f) * tick;
        }

        /** The golden star flares as the column lands, then fades into the floor. */
        private void flarePlates() {
            Player owner = player();
            for (int i = 0; i < plates.length; i++) {
                BlockDisplay plate = plates[i];
                if (plate == null || !plate.isValid()) {
                    continue;
                }
                Displays.animate(plate, column(plateSize() * 1.25f, 0, PLATE_THICKNESS, plateYaw(i, delay + 4)), 2);
                ctx().runLater(owner, 3, () -> {
                    if (plate.isValid()) {
                        Displays.shrinkAway(ctx(), owner, plate, 9);
                    }
                });
            }
        }

        /** The holy seal: the radius, a counter-rotating inner ring and spokes of light. */
        private void seal(long tick) {
            Location ground = center.clone().add(0, 0.08, 0);
            if (tick % 2 == 0) {
                Vfx.ring(ctx(), ground, radius, (int) Math.min(56, 16 + radius * 6), tick * 0.04,
                        Particle.DUST_COLOR_TRANSITION, GLOW, true);
                Vfx.ring(ctx(), ground, radius * 0.62, (int) Math.min(36, 10 + radius * 4), -tick * 0.07,
                        Particle.DUST, GOLD_DUST, true);
            } else {
                double phase = tick * 0.05;
                for (int spoke = 0; spoke < SEAL_SPOKES; spoke++) {
                    double angle = phase + spoke * Math.PI * 2 / SEAL_SPOKES;
                    Vector out = new Vector(Math.cos(angle), 0, Math.sin(angle));
                    Location inner = ground.clone().add(out.clone().multiply(radius * 0.62));
                    Location outer = ground.clone().add(out.clone().multiply(radius * 0.95));
                    Vfx.line(ctx(), inner, outer, 1.5, Particle.DUST, AMBER_DUST, true);
                }
                Vfx.ring(ctx(), ground, 0.6, 8, -tick * 0.2, Particle.END_ROD, null, true);
            }
        }

        /** Light gathers: motes falling into the seal and threads of light running down to it. */
        private void fallingLight(long tick) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int motes = Vfx.n(ctx(), 3);
            for (int i = 0; i < motes; i++) {
                Location from = Vfx.scatter(center, radius).add(0, 5 + random.nextDouble() * 6, 0);
                world.spawnParticle(Particle.END_ROD, from, 0, 0, -1, 0, 0.35 + random.nextDouble() * 0.2, null,
                        true);
            }
            if (tick % 3 == 0) {
                Location top = Vfx.scatter(center, radius * 0.8).add(0, 12, 0);
                Vfx.trail(top, Vfx.scatter(center, radius * 0.9).add(0, 0.1, 0),
                        random.nextBoolean() ? PALETTE.core() : PALETTE.body(), 14, true);
            }
        }

        private void impact() {
            Player owner = player();
            if (pilot != null && pilot.isValid()) {
                pilot.remove();
            }
            beam();
            flarePlates();

            Location ground = center.clone().add(0, 0.15, 0);
            world.spawnParticle(Particle.FLASH, ground.clone().add(0, 1, 0), 1, 0, 0, 0, 0, FLASH_WHITE, true);
            world.spawnParticle(Particle.FLASH, ground.clone().add(0, 3, 0), 1, 0, 0, 0, 0, PALETTE.body(), true);
            Vfx.shockwave(ctx(), owner, ground, 0.5, radius * 1.2, 6, Particle.DUST_COLOR_TRANSITION, GLOW, true);
            Vfx.shockwave(ctx(), owner, ground.clone().add(0, 0.3, 0), 0.3, radius * 0.9, 8, Particle.END_ROD, null,
                    true);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int sparks = Vfx.n(ctx(), 40);
            for (int i = 0; i < sparks; i++) {
                Location at = Vfx.scatter(center, radius * 0.7).add(0, 0.2, 0);
                world.spawnParticle(i % 4 == 0 ? Particle.FIREWORK : Particle.END_ROD, at, 0,
                        random.nextGaussian() * 0.15, 1, random.nextGaussian() * 0.15,
                        0.5 + random.nextDouble() * 0.6, null, true);
            }
            BlockData floor = center.clone().subtract(0, 0.3, 0).getBlock().getBlockData();
            if (!floor.getMaterial().isAir()) {
                world.spawnParticle(Particle.DUST_PILLAR, ground, Vfx.n(ctx(), 24), radius * 0.4, 0.1, radius * 0.4,
                        0.1, floor, true);
            }
            Fx.sound(center, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.3f, 1.6f + Fx.jitter(0.05f));
            Fx.sound(center, Sound.BLOCK_BEACON_POWER_SELECT, 2.0f, 0.7f);
            Fx.sound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.4f + Fx.jitter(0.05f));
            Fx.sound(center, Sound.BLOCK_BELL_RESONATE, 1.5f, 1.2f);
            Fx.sound(center, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.5f, 0.8f);

            int smitten = smite(owner);
            int healed = bless(owner);
            afterglow();
            if (owner.isOnline() && (smitten > 0 || healed > 0)) {
                owner.sendActionBar(Text.mm("<" + GOLD + ">✦ 심판의 빛</" + GOLD + "> <white>" + smitten
                        + "명 적중</white>" + (healed > 0 ? " <gray>· " + healed + "명 회복</gray>" : "")));
            }
        }

        /** The column: a white core inside two layers of glass (each two boxes turned 45°), then fading away. */
        private void beam() {
            Player owner = player();
            long life = BEAM_GROW_TICKS + BEAM_HOLD_TICKS + BEAM_FADE_TICKS + 2L;
            float height = (float) BEAM_HEIGHT;
            float core = 1.3f;
            float inner = (float) Math.max(1.8, radius * 0.85);
            float outer = (float) (radius * 2);
            List<BlockDisplay> layers = new ArrayList<>();
            layers.add(layer(BEAM_CORE, core, life, 0, true));
            layers.add(layer(BEAM_INNER, inner, life, 0, false));
            layers.add(layer(BEAM_INNER, inner, life, 45, false));
            layers.add(layer(BEAM_OUTER, outer, life, 22.5f, false));
            layers.add(layer(BEAM_OUTER, outer, life, 67.5f, false));
            float[] widths = {core, inner, inner, outer, outer};
            float[] yaws = {0, 0, 45, 22.5f, 67.5f};
            for (int i = 0; i < layers.size(); i++) {
                Displays.animateNextTick(ctx(), owner, layers.get(i), column(widths[i], 0, height, yaws[i]),
                        BEAM_GROW_TICKS);
            }
            ctx().runLater(owner, BEAM_GROW_TICKS + BEAM_HOLD_TICKS, () -> {
                for (int i = 0; i < layers.size(); i++) {
                    BlockDisplay layer = layers.get(i);
                    if (layer.isValid()) {
                        // The outer glass fades first, the white core last.
                        int ticks = BEAM_FADE_TICKS - (i == 0 ? 0 : 2 + i);
                        Displays.animate(layer, column(0.001f, 0, height, yaws[i] + 30), Math.max(3, ticks));
                    }
                }
            });
        }

        private BlockDisplay layer(BlockData data, float width, long life, float yaw, boolean outline) {
            BlockDisplay display = Displays.block(ctx(), player(), center, data,
                    column(width * 0.15f, 0, (float) BEAM_HEIGHT, yaw), life);
            display.setViewRange(3.0f);
            if (outline) {
                Displays.outline(display, PALETTE.body());
            }
            return display;
        }

        /** Every enemy inside the column (any height up to the sky) takes the judgment. */
        private int smite(Player owner) {
            double damage = params().num("ult_damage");
            int hits = 0;
            for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius + BEAM_HEIGHT)) {
                if (!insideColumn(enemy)) {
                    continue;
                }
                Combat.hit(ctx(), owner, enemy, damage);
                hits++;
                Location body = Fx.body(enemy);
                world.spawnParticle(Particle.FLASH, body, 1, 0, 0, 0, 0, PALETTE.body(), false);
                Vfx.cloud(ctx(), body, Particle.END_ROD, 10, 0.3, 0.12, null, false);
                Vfx.cloud(ctx(), body, Particle.DUST_COLOR_TRANSITION, 8, 0.3, 0, GLOW, false);
            }
            return hits;
        }

        /** The owner and its allies inside the column are healed by the light. */
        private int bless(Player owner) {
            double heal = params().num("ult_heal");
            if (heal <= 0) {
                return 0;
            }
            List<LivingEntity> blessed = new ArrayList<>();
            if (insideColumn(owner)) {
                blessed.add(owner);
            }
            for (Player ally : ctx().alliesNear(owner, center, radius + BEAM_HEIGHT)) {
                if (insideColumn(ally)) {
                    blessed.add(ally);
                }
            }
            for (LivingEntity target : blessed) {
                ctx().heal(target, heal);
                Location body = Fx.body(target);
                Vfx.cloud(ctx(), body.clone().add(0, 0.6, 0), Particle.HEART, 4, 0.35, 0, null, false);
                Vfx.helix(ctx(), target.getLocation(), 0.55, 2.2, 1.5, 2, 10, 0, Particle.DUST_COLOR_TRANSITION,
                        GLOW_SMALL, false);
                Fx.sound(body, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.8f + Fx.jitter(0.1f));
            }
            return blessed.size();
        }

        /** Inside the column: horizontally within the radius (hitbox included), from the ground up to the sky. */
        private boolean insideColumn(LivingEntity entity) {
            if (!entity.getWorld().equals(world)) {
                return false;
            }
            BoundingBox box = entity.getBoundingBox();
            double dx = Math.max(0, Math.max(box.getMinX() - center.getX(), center.getX() - box.getMaxX()));
            double dz = Math.max(0, Math.max(box.getMinZ() - center.getZ(), center.getZ() - box.getMaxZ()));
            return dx * dx + dz * dz <= radius * radius && box.getMaxY() >= center.getY() - 1
                    && box.getMinY() <= center.getY() + BEAM_HEIGHT;
        }

        /** Light motes drift down where the column stood and the seal fades. */
        private void afterglow() {
            Loop.repeat(ctx(), player(), 3, 1, AFTERGLOW_TICKS, step -> {
                double fade = 1 - (double) step / AFTERGLOW_TICKS;
                ThreadLocalRandom random = ThreadLocalRandom.current();
                int motes = (int) Math.round(Vfx.n(ctx(), 4) * fade + 0.4);
                for (int i = 0; i < motes; i++) {
                    Location at = Vfx.scatter(center, radius).add(0, 0.5 + random.nextDouble() * 4, 0);
                    world.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, 0, 0, -1, 0, 0.05, FADE);
                    if (i == 0 && step % 3 == 0) {
                        world.spawnParticle(Particle.WAX_OFF, at, 1, 0.1, 0.1, 0.1, 0.5);
                    }
                }
                if (step % 4 == 0) {
                    Vfx.ring(ctx(), center.clone().add(0, 0.1, 0), radius * (0.9 + 0.1 * fade),
                            (int) Math.max(10, radius * 4 * fade), step * 0.05, Particle.DUST, GOLD_DUST, false);
                }
                return true;
            });
        }
    }

    // ------------------------------------------------------------------ geometry helpers

    /**
     * A vertical box of {@code width} x {@code height}, centered on the block display's origin horizontally, from
     * {@code bottom} upwards, turned by {@code yawDeg} around the vertical axis.
     */
    private static Transformation column(float width, float bottom, float height, float yawDeg) {
        Quaternionf rotation = new Quaternionf().rotateY((float) Math.toRadians(yawDeg));
        Vector3f translation = rotation.transform(new Vector3f(-width / 2, 0, -width / 2)).add(0, bottom, 0);
        return new Transformation(translation, rotation, new Vector3f(width, height, width), new Quaternionf());
    }

    /** A thin box of the given width from the block display's origin along {@code delta}. */
    private static Transformation beamPose(Vector delta, float width) {
        Vector3f direction = new Vector3f((float) delta.getX(), (float) delta.getY(), (float) delta.getZ());
        float length = direction.length();
        Quaternionf rotation = new Quaternionf().rotationTo(new Vector3f(0, 0, 1), direction.normalize());
        Vector3f translation = rotation.transform(new Vector3f(-width / 2, -width / 2, 0));
        return new Transformation(translation, rotation, new Vector3f(width, width, length), new Quaternionf());
    }

    /** Two unit vectors perpendicular to {@code axis} and to each other. */
    private static Vector[] basis(Vector axis) {
        Vector helper = Math.abs(axis.getY()) > 0.95 ? new Vector(1, 0, 0) : new Vector(0, 1, 0);
        Vector u = axis.clone().crossProduct(helper).normalize();
        Vector v = axis.clone().crossProduct(u).normalize();
        return new Vector[] {u, v};
    }

    /** A ring of particles around {@code center} in the plane perpendicular to {@code axis}. */
    private <T> void perpendicularRing(Location center, Vector axis, Vector[] basis, double radius, int points,
                                       double phase, Particle particle, @Nullable T data) {
        int count = Vfx.n(ctx(), points);
        World world = center.getWorld();
        for (int i = 0; i < count; i++) {
            double angle = phase + i * Math.PI * 2 / count;
            Vector offset = basis[0].clone().multiply(Math.cos(angle) * radius)
                    .add(basis[1].clone().multiply(Math.sin(angle) * radius));
            world.spawnParticle(particle, center.getX() + offset.getX(), center.getY() + offset.getY(),
                    center.getZ() + offset.getZ(), 1, 0, 0, 0, 0, data);
        }
    }
}
