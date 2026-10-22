package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
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
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Axis;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 악마 — trades its own blood for power. The lower its health, the harder its abilities hit; the active pays two
 * health for a cone of soul fire, and the ultimate tears open a gate to hell from which pillars of soul fire burst
 * up under every enemy around it.
 *
 * <p>Look: crimson and charred black, with the cold cyan of soul fire for the burning souls. The contract shows as
 * crimson embers rising off the demon (more and brighter as its health drops) and a crimson sigil flaring wherever
 * its abilities strike while the contract is in force. 지옥불 begins with blood drawn from the chest into a contract
 * circle at the hand and sweeps outward as a sheet of soul flames (block displays riding the wave front) leaving ash
 * behind. 지옥문 rises out of the ground as an obsidian gate with a swirling portal and soul-fire braziers; spinning
 * crimson sigils mark where the pillars will erupt, then columns of soul fire roar up and throw enemies away from it.
 */
public final class DemonAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("demon", "악마", Material.CRYING_OBSIDIAN)
            .color(TextColor.color(0xE0273F))
            .difficulty(2)
            .passive("계약",
                    "잃은 체력에 비례해 능력으로 주는 피해가 늘어납니다. 체력이 최대 체력의 {pact_floor}% 이하로 "
                            + "떨어지면 최대치인 {pact_max}%만큼 증가합니다.")
            .active("지옥불",
                    "체력 {hellfire_cost}을 바쳐 바라보는 방향 {hellfire_range}블록 앞까지 원뿔 모양으로 영혼불 화염파를 "
                            + "내뿜습니다. 닿은 적에게 {hellfire_damage} 피해를 주고 {hellfire_burn}초간 불태우며 살짝 "
                            + "밀어냅니다. 벽 너머로는 닿지 않고, 체력이 {hellfire_cost} 이하일 때는 쓸 수 없습니다.",
                    8)
            .ultimate("지옥문",
                    "조준한 곳({gate_range}블록 이내)에 {gate_duration}초간 지옥문을 엽니다. 문 주변 {gate_radius}블록 "
                            + "안의 적들 발밑에 {pillar_interval}초마다 붉은 인장이 새겨지고, {pillar_delay}초 뒤 그 자리에서 "
                            + "영혼불 기둥이 솟아 {pillar_damage} 피해를 주고 문 바깥쪽으로 날려 보냅니다.")
            .param("pact_max", 50)
            .param("pact_floor", 25)
            .param("hellfire_cost", 2)
            .param("hellfire_range", 7)
            .param("hellfire_angle", 50)
            .param("hellfire_damage", 5)
            .param("hellfire_burn", 3)
            .param("gate_range", 20)
            .param("gate_duration", 4)
            .param("gate_radius", 8)
            .param("pillar_interval", 0.8)
            .param("pillar_delay", 0.6)
            .param("pillar_radius", 1.5)
            .param("pillar_damage", 2.5)
            .build();

    private static final String CRIMSON = "#E0273F";
    private static final String SOUL_TAG = "#5CE1E6";
    private static final double TAU = Math.PI * 2;

    // ------------------------------------------------------------------ look
    /** Hot rose glow, crimson body, the near-black of charred obsidian. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFF8A8A, 0xC4142B, 0x1A0508);
    /** The cold cyan of burning souls. */
    private static final Color SOUL = Color.fromRGB(0x5CE1E6);
    private static final Particle.DustTransition SOUL_GLOW = new Particle.DustTransition(Color.fromRGB(0xD8FFFF),
            SOUL, 1.0f);
    private static final Particle.DustOptions CHAR_DUST = new Particle.DustOptions(Color.fromRGB(0x1A0508), 1.3f);
    private static final Particle.DustTransition EMBER = PALETTE.glow(0.8f);

    /** Block types of the visuals; their block data is created on first use (it needs a running server). */
    private static final Material FLAME_BLOCK = Material.SOUL_FIRE;
    private static final Material PILLAR_BLOCK = Material.CRYING_OBSIDIAN;
    private static final Material BEAM_BLOCK = Material.OBSIDIAN;
    private static final Material PORTAL_BLOCK = Material.NETHER_PORTAL;
    private static final Material SIGIL_BLOCK = Material.RED_STAINED_GLASS;

    /** Caps every velocity this ability applies, so nobody is flung out of the arena. */
    private static final double MAX_SPEED = 2.0;

    // passive
    private static final long PACT_PERIOD = 10;
    /** The contract shows in four tiers; reaching a higher tier is announced to the demon. */
    private static final int PACT_TIERS = 4;
    private static final long SIGIL_COOLDOWN_TICKS = 6;

    // active: hellfire
    private static final double WAVE_SPEED = 1.5;
    /** Enemies this close to the hand are always caught, whatever the angle (point blank). */
    private static final double POINT_BLANK = 1.3;
    private static final double MOUTH_FORWARD = 0.6;
    private static final double HAND_DROP = 0.35;
    private static final double WAVE_PUSH = 0.45;
    private static final double WAVE_LIFT = 0.22;
    private static final int SHEET_FLAMES = 5;
    private static final float SHEET_HEIGHT = 1.25f;
    private static final int SHEET_FADE_TICKS = 4;
    private static final int ASH_TICKS = 14;

    // ultimate: hellgate
    private static final int GROUND_SNAP = 8;
    private static final int RISE_TICKS = 12;
    private static final int CLOSE_TICKS = 10;
    private static final int MAX_PILLARS_PER_WAVE = 3;
    private static final float PILLAR_HEIGHT = 4.2f;
    private static final int PILLAR_GROW_TICKS = 3;
    private static final long PILLAR_HOLD_TICKS = 8;
    private static final int PILLAR_FADE_TICKS = 5;
    /** Enemies a little below or above the sigil are still caught by its pillar. */
    private static final double PILLAR_REACH_BELOW = 1.0;
    private static final double PILLAR_PUSH = 0.85;
    private static final double PILLAR_LIFT = 0.6;
    private static final double SIGIL_SPIN = 9;
    private static final double SIGIL_GROUND_SEARCH = 3;
    // The gate in its own frame: +X is its left, +Y up, +Z faces the demon.
    private static final float GATE_HALF_WIDTH = 1.6f;
    private static final float GATE_HEIGHT = 4.2f;
    private static final float POST_WIDTH = 0.5f;
    private static final float BEAM_HEIGHT = 0.5f;
    private static final float GATE_DEPTH = 0.5f;
    private static final float BRAZIER_SIZE = 0.75f;

    private int pactTier;
    private long lastSigil = Long.MIN_VALUE / 2; // "/ 2": now - this must not overflow

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onGrant() {
        pactTier = tierOf(pact());
    }

    // ------------------------------------------------------------------ passive: contract

    /** How far the contract has come into force: 0 at full health, 1 at or below the floor. */
    private double pact() {
        Player owner = player();
        double max = Combat.maxHealth(owner);
        double floor = Math.clamp(params().num("pact_floor") / 100.0, 0.0, 0.99);
        double missing = 1 - Math.clamp(owner.getHealth() / max, 0.0, 1.0);
        return Math.clamp(missing / (1 - floor), 0.0, 1.0);
    }

    /** The current ability damage bonus as a fraction (0.5 = +50%). */
    private double pactBonus() {
        return Math.max(0, params().num("pact_max")) / 100.0 * pact();
    }

    private static int tierOf(double pact) {
        return (int) Math.floor(pact * PACT_TIERS + 1.0E-9);
    }

    /** Only the owner's own ability damage is amplified (not melee, arrows or anything else). */
    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled() || !isOwnAbilityHit(event)) {
            return;
        }
        double bonus = pactBonus();
        if (bonus > 0) {
            event.setDamage(event.getDamage() * (1 + bonus));
        }
    }

    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (!isOwnAbilityHit(event)) {
            return;
        }
        double pact = pact();
        long now = ctx().currentTick();
        if (pact < 0.1 || now - lastSigil < SIGIL_COOLDOWN_TICKS) {
            return;
        }
        lastSigil = now;
        pactSigil(victim, pact);
    }

    private boolean isOwnAbilityHit(EntityDamageByEntityEvent event) {
        return ctx().isAbilityDamage() && player().equals(event.getDamager());
    }

    /** The contract collects: a crimson sigil flares on the victim, brighter the stronger the contract. */
    private void pactSigil(LivingEntity victim, double pact) {
        Location body = Fx.body(victim);
        double phase = ThreadLocalRandom.current().nextDouble(TAU);
        Vfx.ring(ctx(), body, 0.55 + 0.2 * pact, 12, phase, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.0f), false);
        Vfx.ring(ctx(), body.clone().add(0, 0.05, 0), 0.35 + 0.15 * pact, 4, phase + Math.PI / 4, Particle.DUST,
                CHAR_DUST, false);
        Vfx.cloud(ctx(), body, Particle.CRIMSON_SPORE, (int) Math.round(4 + 6 * pact), 0.3, 0.02, null, false);
        Fx.sound(body, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.35f, 1.6f + Fx.jitter(0.1f));
    }

    @Override
    public void onTick(long tick) {
        if (tick % PACT_PERIOD != 0) {
            return;
        }
        Player owner = player();
        double pact = pact();
        int tier = tierOf(pact);
        if (tier > pactTier) {
            tierUp(owner, tier);
        }
        pactTier = tier;
        if (pact < 0.15) {
            return;
        }
        // Crimson embers rise off the demon, more and brighter as its health drops.
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int embers = (int) Math.round(1 + pact * 3);
        for (int i = 0; i < embers; i++) {
            Location at = owner.getLocation().add(random.nextDouble(-0.4, 0.4), random.nextDouble(0.2, 1.7),
                    random.nextDouble(-0.4, 0.4));
            Vfx.dot(at, Particle.DUST_COLOR_TRANSITION, pact >= 0.75 ? PALETTE.glow(1.0f) : EMBER, false);
        }
        if (pact >= 0.75 && tick % (PACT_PERIOD * 2) == 0) {
            // A fully sealed contract: soul flames lick off the shoulders and the ground darkens underfoot.
            for (int side = -1; side <= 1; side += 2) {
                Vector across = Motion.horizontalFacing(owner.getLocation()).crossProduct(new Vector(0, 1, 0))
                        .multiply(0.3 * side);
                Location shoulder = owner.getLocation().add(across).add(0, owner.getHeight() * 0.78, 0);
                owner.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, shoulder, 0, 0, 0.05, 0, 1);
            }
            Vfx.ring(ctx(), owner.getLocation().add(0, 0.05, 0), 0.6, 8, tick * 0.1, Particle.DUST, CHAR_DUST,
                    false);
        }
    }

    /** The contract deepens: a crimson ring closes on the demon and it is told the new bonus. */
    private void tierUp(Player owner, int tier) {
        Location feet = owner.getLocation().add(0, 0.1, 0);
        Vfx.shockwave(ctx(), owner, feet, 1.4, 0.3, 4, Particle.DUST_COLOR_TRANSITION, PALETTE.fade(1.1f), false);
        Vfx.helix(ctx(), owner.getLocation(), 0.5, owner.getHeight(), 1.0, 2, 8, 0, Particle.DUST, PALETTE.dust(0.8f),
                false);
        Fx.sound(feet, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.6f, 1.3f - 0.15f * tier);
        Fx.sound(feet, Sound.ENTITY_WITHER_AMBIENT, 0.2f, 1.6f + Fx.jitter(0.1f));
        double bonus = Math.max(0, params().num("pact_max")) * Math.min(1.0, (double) tier / PACT_TIERS);
        owner.sendActionBar(Text.mm("<" + CRIMSON + ">✠ 계약</" + CRIMSON + "> <gray>능력 피해</gray> <white>+"
                + AbilityInfo.formatNumber(Math.round(bonus)) + "%</white>"));
    }

    // ------------------------------------------------------------------ active: hellfire

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        double cost = Math.max(0, params().num("hellfire_cost"));
        if (owner.getHealth() <= cost) {
            return ActivationResult.fail("계약에 바칠 체력이 부족합니다");
        }
        if (cost > 0) {
            owner.setHealth(owner.getHealth() - cost);
        }
        new Hellfire(owner).start(cost);
        return ActivationResult.success();
    }

    /**
     * The hellfire wave: its front rushes out from the hand at {@link #WAVE_SPEED} blocks per tick and burns every
     * enemy in the cone (in sight of the hand) the moment it reaches it, each once.
     */
    private final class Hellfire implements LongPredicate {

        private final Player owner;
        private final Location origin;
        private final Vector direction;
        private final Vector right;
        private final Vector up;
        private final double range;
        private final double halfAngle;
        private final double cosHalf;
        private final int steps;
        private final Set<UUID> hit = new HashSet<>();
        private final List<BlockDisplay> sheet = new ArrayList<>();

        Hellfire(Player owner) {
            this.owner = owner;
            Location eye = owner.getEyeLocation();
            Vector look = eye.getDirection();
            this.direction = look.lengthSquared() < 1.0E-6 ? new Vector(0, 0, 1) : look.normalize();
            this.origin = eye.add(direction.clone().multiply(MOUTH_FORWARD)).subtract(0, HAND_DROP, 0);
            Vector side = direction.clone().crossProduct(new Vector(0, 1, 0));
            this.right = side.lengthSquared() < 1.0E-6 ? new Vector(1, 0, 0) : side.normalize();
            this.up = right.clone().crossProduct(direction).normalize();
            this.range = Math.max(1, params().num("hellfire_range"));
            this.halfAngle = Math.toRadians(Math.clamp(params().num("hellfire_angle"), 5, 170) / 2);
            this.cosHalf = Math.cos(halfAngle);
            this.steps = (int) Math.ceil(range / WAVE_SPEED);
        }

        void start(double cost) {
            pay(cost);
            BlockData flame = FLAME_BLOCK.createBlockData();
            for (int i = 0; i < SHEET_FLAMES; i++) {
                Location at = frontPoint(0.6, lateral(i));
                BlockDisplay display = Displays.block(ctx(), owner, at, flame, Displays.scaled(0.3f),
                        steps + SHEET_FADE_TICKS + 6L);
                display.setTeleportDuration(1);
                sheet.add(display);
            }
            Loop.repeat(ctx(), owner, 0, 1, steps + 1L, this, this::afterglow);
        }

        /** The price: blood drawn out of the chest into a crimson contract circle at the hand. */
        private void pay(double cost) {
            Location chest = owner.getLocation().add(0, owner.getHeight() * 0.6, 0);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < Vfx.n(ctx(), 5); i++) {
                Location from = chest.clone().add(random.nextDouble(-0.2, 0.2), random.nextDouble(-0.2, 0.2),
                        random.nextDouble(-0.2, 0.2));
                Vfx.trail(from, origin, i % 2 == 0 ? PALETTE.body() : PALETTE.edge(), 4 + random.nextInt(3), false);
            }
            int points = Vfx.n(ctx(), 16);
            for (int i = 0; i < points; i++) {
                double angle = i * TAU / points;
                Location at = origin.clone().add(right.clone().multiply(Math.cos(angle) * 0.55))
                        .add(up.clone().multiply(Math.sin(angle) * 0.55));
                Vfx.dot(at, Particle.DUST_COLOR_TRANSITION, i % 4 == 0 ? SOUL_GLOW : PALETTE.glow(0.9f), false);
            }
            Vfx.cloud(ctx(), origin, Particle.SOUL_FIRE_FLAME, 6, 0.12, 0.04, null, false);
            Fx.sound(origin, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.8f, 0.6f + Fx.jitter(0.05f));
            Fx.sound(origin, Sound.ENTITY_BLAZE_SHOOT, 1.0f, 0.6f + Fx.jitter(0.06f));
            Fx.sound(origin, Sound.ITEM_FIRECHARGE_USE, 0.8f, 0.7f + Fx.jitter(0.08f));
            Fx.sound(origin, Sound.ENTITY_WITHER_SHOOT, 0.35f, 1.4f + Fx.jitter(0.1f));
            owner.sendActionBar(Text.mm("<" + CRIMSON + ">✠ 지옥불</" + CRIMSON + "> <gray>체력</gray> <white>-"
                    + AbilityInfo.formatNumber(cost) + "</white><dark_gray> · </dark_gray><gray>계약</gray> <white>+"
                    + AbilityInfo.formatNumber(Math.round(pactBonus() * 100)) + "%</white>"));
        }

        @Override
        public boolean test(long tick) {
            double front = Math.min(range, (tick + 1) * WAVE_SPEED);
            burn(front);
            drawFront(front, tick);
            moveSheet(front);
            return front < range;
        }

        private void burn(double front) {
            double damage = params().num("hellfire_damage");
            long burnTicks = params().ticks("hellfire_burn");
            for (LivingEntity enemy : ctx().enemiesNear(owner, origin, front + 0.5)) {
                if (hit.contains(enemy.getUniqueId()) || !inCone(enemy, front)) {
                    continue;
                }
                hit.add(enemy.getUniqueId());
                Combat.hit(ctx(), owner, enemy, damage);
                if (!Combat.isTargetable(ctx(), owner, enemy)) {
                    continue;
                }
                ignite(enemy, burnTicks);
                Vector push = Motion.horizontal(direction).multiply(WAVE_PUSH).setY(WAVE_LIFT);
                Motion.push(enemy, push, MAX_SPEED);
                Location body = Fx.body(enemy);
                Vfx.burst(ctx(), body, PALETTE, 0.6, Particle.SOUL_FIRE_FLAME, false);
                Vfx.cloud(ctx(), body, Particle.FLAME, 5, 0.25, 0.03, null, false);
                Fx.sound(body, Sound.ENTITY_BLAZE_HURT, 0.6f, 0.7f + Fx.jitter(0.1f));
                Fx.sound(body, Sound.ITEM_FIRECHARGE_USE, 0.5f, 1.4f + Fx.jitter(0.1f));
            }
        }

        /** In the cone (or right at the hand), within the wave front and in sight of the hand. */
        private boolean inCone(LivingEntity enemy, double front) {
            Location body = Fx.body(enemy);
            Vector to = body.toVector().subtract(origin.toVector());
            double distance = to.length();
            double reach = distance - enemy.getWidth() / 2;
            if (reach > front) {
                return false;
            }
            if (distance > POINT_BLANK && to.dot(direction) / distance < cosHalf) {
                return false;
            }
            return Spots.clearPath(origin, body);
        }

        /** The curved wave front: soul flames, crimson heat and charred smoke behind it. */
        private void drawFront(double front, long tick) {
            World world = origin.getWorld();
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int points = Vfx.n(ctx(), (int) Math.max(6, Math.round(front * 2.2)));
            for (int i = 0; i < points; i++) {
                double t = points == 1 ? 0.5 : (double) i / (points - 1);
                double angle = (t * 2 - 1) * halfAngle;
                Location at = frontPoint(front, angle).add(up.clone().multiply(random.nextDouble(-0.45, 0.45)));
                if (i % 2 == 0) {
                    world.spawnParticle(Particle.SOUL_FIRE_FLAME, at, 0, direction.getX(), direction.getY() + 0.1,
                            direction.getZ(), 0.06);
                } else {
                    Vfx.dot(at, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.3f), false);
                }
                if (i % 3 == 1) {
                    Vfx.dot(at.add(up.clone().multiply(0.3)), Particle.FLAME, null, false);
                }
            }
            if (tick % 2 == 0) {
                Location behind = frontPoint(Math.max(0.5, front - 1.5), random.nextDouble(-halfAngle, halfAngle));
                Vfx.cloud(ctx(), behind, Particle.LARGE_SMOKE, 2, 0.3, 0.01, null, false);
            }
            if (tick % 2 == 1) {
                Fx.sound(frontPoint(front, 0), Sound.BLOCK_FIRE_AMBIENT, 0.9f, 0.8f + Fx.jitter(0.2f));
            }
        }

        /** The soul-fire sheet rides the front, widening with the cone. */
        private void moveSheet(double front) {
            float width = (float) Math.max(0.6, 2 * front * Math.tan(halfAngle) / SHEET_FLAMES * 1.3);
            float height = (float) (SHEET_HEIGHT * (0.6 + 0.4 * front / range));
            for (int i = 0; i < sheet.size(); i++) {
                BlockDisplay flame = sheet.get(i);
                if (!flame.isValid()) {
                    continue;
                }
                flame.teleport(frontPoint(front, lateral(i)));
                Displays.animate(flame, Displays.transform(0, 0, 0, width, height, width, 0, 0, 0), 1);
            }
        }

        /** The angle of sheet flame {@code index} across the cone. */
        private double lateral(int index) {
            return ((double) index / (SHEET_FLAMES - 1) * 2 - 1) * halfAngle * 0.85;
        }

        private Location frontPoint(double distance, double angle) {
            Vector offset = direction.clone().multiply(Math.cos(angle) * distance)
                    .add(right.clone().multiply(Math.sin(angle) * distance));
            return origin.clone().add(offset);
        }

        /** The sheet dies down; ash and the last soul flames drift over the scorched cone. */
        private void afterglow() {
            for (BlockDisplay flame : sheet) {
                if (flame.isValid()) {
                    Displays.shrinkAway(ctx(), owner, flame, SHEET_FADE_TICKS);
                }
            }
            if (!hit.isEmpty()) {
                owner.sendActionBar(Text.mm("<" + CRIMSON + ">✠ 지옥불</" + CRIMSON + "> <white>" + hit.size()
                        + "명 적중</white>"));
            }
            World world = origin.getWorld();
            Loop.repeat(ctx(), owner, 1, 2, ASH_TICKS / 2, run -> {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                int count = Math.max(1, Vfx.n(ctx(), 4) - (int) run / 2);
                for (int i = 0; i < count; i++) {
                    Location at = frontPoint(random.nextDouble(0.8, range), random.nextDouble(-halfAngle, halfAngle))
                            .add(0, random.nextDouble(-0.6, 0.4), 0);
                    if (i % 2 == 0) {
                        world.spawnParticle(Particle.SOUL_FIRE_FLAME, at, 0, 0, 0.04, 0, 1);
                    } else {
                        Vfx.dot(at, Particle.ASH, null, false);
                        Vfx.dot(at.add(0, 0.2, 0), Particle.DUST, CHAR_DUST, false);
                    }
                }
                return true;
            });
        }
    }

    // ------------------------------------------------------------------ ultimate: hellgate

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        Location ground = gateSpot(owner, params().num("gate_range"));
        if (ground == null) {
            return ActivationResult.fail("지옥문을 세울 바닥이 없습니다");
        }
        double radius = params().num("gate_radius");
        if (visibleEnemies(ground.clone().add(0, 1, 0), radius).isEmpty()) {
            return ActivationResult.fail("지옥문 주변 " + AbilityInfo.formatNumber(radius) + "블록 안에 적이 없습니다");
        }
        new Hellgate(owner, ground).open();
        return ActivationResult.success();
    }

    /**
     * Where the gate rises: under the aimed enemy, or where the view ray meets a block (or ends), snapped onto the
     * ground below; facing the demon. Null if there is no ground close below.
     */
    private @Nullable Location gateSpot(Player owner, double range) {
        LivingEntity aimed = ctx().findTarget(owner, range);
        if (aimed instanceof Player hidden && !owner.canSee(hidden)) {
            aimed = null;
        }
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection();
        Location point;
        if (aimed != null) {
            point = aimed.getLocation().add(0, 0.5, 0);
        } else {
            RayTraceResult hit = owner.getWorld().rayTraceBlocks(eye, direction, range, FluidCollisionMode.NEVER, true);
            point = hit == null
                    ? eye.clone().add(direction.clone().multiply(range))
                    : hit.getHitPosition().toLocation(owner.getWorld()).subtract(direction.clone().multiply(0.4));
        }
        Double ground = groundBelow(point, GROUND_SNAP);
        if (ground == null) {
            return null;
        }
        point.setY(ground);
        Vector toOwner = Motion.horizontal(owner.getLocation().toVector().subtract(point.toVector()));
        if (toOwner.lengthSquared() < 1.0E-6) {
            toOwner = Motion.horizontalFacing(owner.getLocation()).multiply(-1);
        }
        point.setDirection(toOwner);
        point.setPitch(0);
        return point;
    }

    /** The top of the first solid block at most {@code depth} blocks below {@code point}, null if none. */
    private static @Nullable Double groundBelow(Location point, int depth) {
        World world = point.getWorld();
        int x = point.getBlockX();
        int z = point.getBlockZ();
        int start = Math.min(point.getBlockY(), world.getMaxHeight() - 1);
        int end = Math.max(world.getMinHeight(), start - depth);
        for (int y = start; y >= end; y--) {
            Block block = world.getBlockAt(x, y, z);
            if (!block.isPassable()) {
                return block.getBoundingBox().getMaxY();
            }
        }
        return null;
    }

    /** Enemies around {@code center} the demon can see (a stealthed enemy is never marked, which would reveal it). */
    private List<LivingEntity> visibleEnemies(Location center, double radius) {
        List<LivingEntity> found = ctx().enemiesNear(player(), center, radius);
        found.removeIf(enemy -> enemy instanceof Player hidden && !player().canSee(hidden));
        return found;
    }

    /** One pillar waiting to erupt: its sigil on the ground and the tick it erupts at. */
    private record PendingPillar(Location mark, long eruptAt, BlockDisplay sigil) {
    }

    /**
     * The gate of hell: rises out of the ground, then every interval marks the ground under up to
     * {@link #MAX_PILLARS_PER_WAVE} enemies around it and erupts a pillar of soul fire on each mark after the delay,
     * and finally sinks back.
     */
    private final class Hellgate implements LongPredicate {

        private final Player owner;
        private final Location base;
        private final World world;
        private final double radius;
        private final long interval;
        private final long delay;
        private final int waves;
        private final long total;
        private final List<BlockDisplay> frame = new ArrayList<>();
        private final List<Transformation> framePose = new ArrayList<>();
        private final List<PendingPillar> pending = new ArrayList<>();
        private final Vector facing;
        private final Vector left;
        private int pillars;
        private final Set<UUID> struck = new HashSet<>();

        Hellgate(Player owner, Location base) {
            this.owner = owner;
            this.base = base;
            this.world = base.getWorld();
            this.radius = Math.max(1, params().num("gate_radius"));
            this.interval = Math.max(2, params().ticks("pillar_interval"));
            this.delay = Math.max(1, params().ticks("pillar_delay"));
            long duration = Math.max(interval, params().ticks("gate_duration"));
            this.waves = (int) Math.max(1, duration / interval);
            this.total = RISE_TICKS + (waves - 1) * interval + delay + PILLAR_HOLD_TICKS;
            this.facing = Motion.horizontalFacing(base);
            this.left = new Vector(facing.getZ(), 0, -facing.getX());
        }

        void open() {
            buildFrame();
            // Telegraph: the reach of the gate burns into the ground, and the ground cracks where it rises.
            Location floor = base.clone().add(0, 0.12, 0);
            Vfx.shockwave(ctx(), owner, floor, 0.6, radius, 8, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.4f),
                    true);
            Vfx.shockwave(ctx(), owner, floor.clone().add(0, 0.1, 0), 0.4, radius * 0.9, 8, Particle.SOUL_FIRE_FLAME,
                    null, true);
            Block under = base.clone().subtract(0, 0.5, 0).getBlock();
            if (!under.isPassable()) {
                world.spawnParticle(Particle.BLOCK, floor, Vfx.n(ctx(), 30), 1.2, 0.1, 0.4, 0.15,
                        under.getBlockData(), true);
            }
            Vfx.cloud(ctx(), floor.clone().add(0, 0.5, 0), Particle.LARGE_SMOKE, 16, 0.9, 0.03, null, true);
            Fx.sound(base, Sound.BLOCK_END_PORTAL_SPAWN, 1.0f, 0.55f);
            Fx.sound(base, Sound.BLOCK_PORTAL_TRIGGER, 0.8f, 0.6f + Fx.jitter(0.05f));
            Fx.sound(base, Sound.ENTITY_WITHER_SPAWN, 0.5f, 1.4f + Fx.jitter(0.05f));
            Fx.sound(base, Sound.BLOCK_RESPAWN_ANCHOR_SET_SPAWN, 1.0f, 0.5f);
            owner.sendActionBar(Text.mm("<" + CRIMSON + "><b>✠ 지옥문</b></" + CRIMSON + "> <gray>영혼불 기둥이 "
                    + "솟아오릅니다</gray>"));
            Loop.repeat(ctx(), owner, 1, 1, total + 1, this, this::close);
        }

        /** Obsidian posts, beam and sill, a portal in between and a soul-fire brazier on each post. */
        private void buildFrame() {
            float h = GATE_HEIGHT;
            float w = GATE_HALF_WIDTH;
            float d = GATE_DEPTH;
            BlockData post = PILLAR_BLOCK.createBlockData();
            BlockData beam = BEAM_BLOCK.createBlockData();
            BlockData portal = PORTAL_BLOCK.createBlockData();
            if (portal instanceof Orientable orientable) {
                orientable.setAxis(Axis.X);
            }
            BlockData brazier = FLAME_BLOCK.createBlockData();
            long life = total + CLOSE_TICKS + 10;
            part(post, box(w - POST_WIDTH, 0, -d / 2, POST_WIDTH, h, d), life);
            part(post, box(-w, 0, -d / 2, POST_WIDTH, h, d), life);
            part(beam, box(-w - 0.15f, h - BEAM_HEIGHT, -d / 2 - 0.05f, 2 * w + 0.3f, BEAM_HEIGHT, d + 0.1f), life);
            part(beam, box(-w - 0.1f, -0.05f, -d / 2 - 0.05f, 2 * w + 0.2f, 0.4f, d + 0.1f), life);
            float inner = w - POST_WIDTH;
            // The portal block model is a pane 4/16 thick in the middle of the block: centre it on the frame.
            BlockDisplay fill = part(portal, box(-inner, 0.35f, -0.5f, 2 * inner, h - BEAM_HEIGHT - 0.35f, 1f), life);
            Displays.outline(fill, PALETTE.body());
            float b = BRAZIER_SIZE;
            part(brazier, box(w - POST_WIDTH / 2 - b / 2, h, -b / 2, b, b, b), life);
            part(brazier, box(-w + POST_WIDTH / 2 - b / 2, h, -b / 2, b, b, b), life);
        }

        private BlockDisplay part(BlockData data, Transformation pose, long life) {
            Vector3f t = pose.getTranslation();
            Vector3f s = pose.getScale();
            Transformation sunk = new Transformation(new Vector3f(t.x(), 0, t.z()), new Quaternionf(),
                    new Vector3f(s.x(), 0.01f, s.z()), new Quaternionf());
            BlockDisplay display = Displays.block(ctx(), owner, base, data, sunk, life);
            display.setViewRange(2.0f);
            Displays.animateNextTick(ctx(), owner, display, pose, RISE_TICKS);
            frame.add(display);
            framePose.add(pose);
            return display;
        }

        @Override
        public boolean test(long tick) {
            long sinceRise = tick - RISE_TICKS;
            if (sinceRise >= 0 && sinceRise % interval == 0 && sinceRise / interval < waves) {
                wave();
            }
            long now = ctx().currentTick();
            Iterator<PendingPillar> it = pending.iterator();
            while (it.hasNext()) {
                PendingPillar pillar = it.next();
                if (now >= pillar.eruptAt()) {
                    it.remove();
                    erupt(pillar);
                } else {
                    drawSigil(pillar, tick);
                }
            }
            ambience(tick);
            return true;
        }

        /** Marks the ground under the nearest visible enemies around the gate. */
        private void wave() {
            List<LivingEntity> targets = visibleEnemies(base.clone().add(0, 1, 0), radius);
            BlockData glass = SIGIL_BLOCK.createBlockData();
            int count = 0;
            for (LivingEntity enemy : targets) {
                if (count++ >= MAX_PILLARS_PER_WAVE) {
                    break;
                }
                Location mark = enemy.getLocation();
                Double ground = groundBelow(mark.clone().add(0, 0.2, 0), (int) SIGIL_GROUND_SEARCH);
                if (ground != null) {
                    mark.setY(ground);
                }
                mark.setPitch(0);
                mark.setYaw(ThreadLocalRandom.current().nextFloat() * 90);
                float size = (float) (params().num("pillar_radius") * 1.5);
                BlockDisplay sigil = Displays.block(ctx(), owner, mark.clone().add(0, 0.03, 0), glass,
                        Displays.transform(0, 0, 0, 0.1f, 0.02f, 0.1f, 0, 0, 0), delay + 4);
                sigil.setTeleportDuration(1);
                Displays.outline(sigil, PALETTE.body());
                Displays.animateNextTick(ctx(), owner, sigil, Displays.transform(0, 0, 0, size, 0.02f, size, 0, 0, 0),
                        (int) Math.min(6, delay));
                pending.add(new PendingPillar(mark, ctx().currentTick() + delay, sigil));
                Fx.sound(mark, Sound.BLOCK_SCULK_CHARGE, 0.7f, 0.6f + Fx.jitter(0.1f));
                Fx.sound(mark, Sound.ENTITY_GHAST_WARN, 0.25f, 1.6f + Fx.jitter(0.1f));
            }
        }

        /** The closing crimson ring of a sigil about to erupt (the dodge window). */
        private void drawSigil(PendingPillar pillar, long tick) {
            long remaining = Math.max(0, pillar.eruptAt() - ctx().currentTick());
            double radiusNow = params().num("pillar_radius") * (0.5 + 0.8 * remaining / delay);
            Location at = pillar.mark().clone().add(0, 0.1, 0);
            Vfx.ring(ctx(), at, radiusNow, (int) Math.max(10, radiusNow * 9), tick * 0.25,
                    Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.1f), true);
            if (tick % 2 == 0) {
                Vfx.dot(Vfx.scatter(at, radiusNow * 0.7), Particle.SOUL_FIRE_FLAME, null, false);
            }
            BlockDisplay sigil = pillar.sigil();
            if (sigil.isValid()) {
                Location spun = sigil.getLocation();
                spun.setYaw((float) (spun.getYaw() + SIGIL_SPIN));
                sigil.teleport(spun);
            }
        }

        private void erupt(PendingPillar pillar) {
            Location mark = pillar.mark();
            if (pillar.sigil().isValid()) {
                pillar.sigil().remove();
            }
            pillars++;
            BlockDisplay column = Displays.block(ctx(), owner, mark, FLAME_BLOCK.createBlockData(),
                    columnPose(0.2f), PILLAR_GROW_TICKS + PILLAR_HOLD_TICKS + PILLAR_FADE_TICKS + 4);
            column.setViewRange(2.0f);
            Displays.animateNextTick(ctx(), owner, column, columnPose(PILLAR_HEIGHT), PILLAR_GROW_TICKS);
            ctx().runLater(owner, PILLAR_GROW_TICKS + PILLAR_HOLD_TICKS, () -> {
                if (column.isValid()) {
                    Displays.shrinkAway(ctx(), owner, column, PILLAR_FADE_TICKS);
                }
            });
            eruptionFx(mark);
            strike(mark);
        }

        private Transformation columnPose(float height) {
            float width = (float) Math.max(0.6, params().num("pillar_radius") * 1.2);
            return new Transformation(new Vector3f(-width / 2, 0, -width / 2), new Quaternionf(),
                    new Vector3f(width, height, width), new Quaternionf());
        }

        private void strike(Location mark) {
            double pillarRadius = Math.max(0.3, params().num("pillar_radius"));
            double damage = params().num("pillar_damage");
            Location core = mark.clone().add(0, PILLAR_HEIGHT / 2, 0);
            for (LivingEntity enemy : ctx().enemiesNear(owner, core, pillarRadius + PILLAR_HEIGHT / 2)) {
                Location feet = enemy.getLocation();
                double horizontal = Math.hypot(feet.getX() - mark.getX(), feet.getZ() - mark.getZ());
                if (horizontal > pillarRadius + enemy.getWidth() / 2 || feet.getY() < mark.getY() - PILLAR_REACH_BELOW
                        || feet.getY() > mark.getY() + PILLAR_HEIGHT) {
                    continue;
                }
                Combat.hit(ctx(), owner, enemy, damage);
                struck.add(enemy.getUniqueId());
                if (!Combat.isTargetable(ctx(), owner, enemy)) {
                    continue;
                }
                Vector away = Motion.horizontal(feet.toVector().subtract(base.toVector()));
                if (away.lengthSquared() < 1.0E-6) {
                    double angle = ThreadLocalRandom.current().nextDouble(TAU);
                    away = new Vector(Math.cos(angle), 0, Math.sin(angle));
                }
                Motion.push(enemy, away.multiply(PILLAR_PUSH).setY(PILLAR_LIFT), MAX_SPEED);
                Location body = Fx.body(enemy);
                Vfx.burst(ctx(), body, PALETTE, 0.7, Particle.SOUL_FIRE_FLAME, true);
                Fx.sound(body, Sound.ENTITY_BLAZE_HURT, 0.6f, 0.6f + Fx.jitter(0.1f));
            }
        }

        /** A roaring column: a soul-fire double helix, a crimson heat core, sparks and smoke over the top. */
        private void eruptionFx(Location mark) {
            Location ground = mark.clone().add(0, 0.1, 0);
            Vfx.helix(ctx(), ground, 0.6, PILLAR_HEIGHT, 1.5, 2, 12, ThreadLocalRandom.current().nextDouble(TAU),
                    Particle.SOUL_FIRE_FLAME, null, true);
            Vfx.line(ctx(), ground, ground.clone().add(0, PILLAR_HEIGHT, 0), 3, Particle.DUST_COLOR_TRANSITION,
                    PALETTE.glow(1.4f), true);
            Vfx.shockwave(ctx(), owner, ground, 0.3, params().num("pillar_radius") + 0.4, 4,
                    Particle.DUST_COLOR_TRANSITION, PALETTE.fade(1.2f), true);
            Location top = ground.clone().add(0, PILLAR_HEIGHT, 0);
            Vfx.cloud(ctx(), top, Particle.LARGE_SMOKE, 6, 0.4, 0.04, null, false);
            Vfx.cloud(ctx(), ground.clone().add(0, 0.5, 0), Particle.LAVA, 4, 0.4, 0, null, false);
            Fx.sound(mark, Sound.ENTITY_BLAZE_SHOOT, 1.0f, 0.5f + Fx.jitter(0.06f));
            Fx.sound(mark, Sound.ENTITY_GHAST_SHOOT, 0.8f, 0.55f + Fx.jitter(0.06f));
            Fx.sound(mark, Sound.ITEM_FIRECHARGE_USE, 0.9f, 0.6f + Fx.jitter(0.08f));
            Loop.repeat(ctx(), owner, 1, 2, PILLAR_HOLD_TICKS / 2, run -> {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                for (int i = 0; i < Vfx.n(ctx(), 4); i++) {
                    Location at = ground.clone().add(random.nextDouble(-0.5, 0.5), random.nextDouble(0, PILLAR_HEIGHT),
                            random.nextDouble(-0.5, 0.5));
                    world.spawnParticle(Particle.SOUL_FIRE_FLAME, at, 0, 0, 0.2, 0, 1);
                }
                return true;
            });
        }

        /** The open gate: the portal swirls, the braziers burn and the reach of the gate glows on the ground. */
        private void ambience(long tick) {
            Location center = base.clone().add(0, GATE_HEIGHT * 0.45, 0);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < Vfx.n(ctx(), 3); i++) {
                double x = random.nextDouble(-1, 1) * (GATE_HALF_WIDTH - POST_WIDTH);
                double y = random.nextDouble(-1.4, 1.4);
                Location at = center.clone().add(left.clone().multiply(x)).add(0, y, 0);
                world.spawnParticle(Particle.PORTAL, at, 0, facing.getX(), 0.2, facing.getZ(), 0.6, null, true);
                if (i == 0) {
                    Vfx.dot(at, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.2f), true);
                }
            }
            if (tick % 2 == 0) {
                for (int side = -1; side <= 1; side += 2) {
                    Location top = base.clone().add(left.clone().multiply(side * (GATE_HALF_WIDTH - POST_WIDTH / 2)))
                            .add(0, GATE_HEIGHT + BRAZIER_SIZE, 0);
                    world.spawnParticle(Particle.SOUL_FIRE_FLAME, top, 0, 0, 0.08, 0, 1, null, true);
                }
            }
            if (tick % 5 == 0) {
                Vfx.ring(ctx(), base.clone().add(0, 0.12, 0), radius, (int) Math.min(48, Math.max(16, radius * 4)),
                        tick * 0.03, Particle.DUST_COLOR_TRANSITION, PALETTE.fade(1.2f), true);
            }
            if (tick % 20 == 10) {
                Fx.sound(base, Sound.BLOCK_PORTAL_AMBIENT, 0.8f, 0.5f + Fx.jitter(0.05f));
                Fx.sound(base, Sound.BLOCK_FIRE_AMBIENT, 1.0f, 0.6f + Fx.jitter(0.1f));
            }
        }

        /** The gate sinks back into the ground with a last gust of soul fire. */
        private void close() {
            for (PendingPillar pillar : pending) {
                if (pillar.sigil().isValid()) {
                    pillar.sigil().remove();
                }
            }
            pending.clear();
            for (int i = 0; i < frame.size(); i++) {
                BlockDisplay part = frame.get(i);
                if (!part.isValid()) {
                    continue;
                }
                Transformation pose = framePose.get(i);
                Vector3f t = pose.getTranslation();
                Vector3f s = pose.getScale();
                Displays.animate(part, new Transformation(new Vector3f(t.x(), 0, t.z()), new Quaternionf(),
                        new Vector3f(s.x(), 0.01f, s.z()), new Quaternionf()), CLOSE_TICKS);
                ctx().runLater(owner, CLOSE_TICKS, () -> {
                    if (part.isValid()) {
                        part.remove();
                    }
                });
            }
            Location center = base.clone().add(0, GATE_HEIGHT * 0.5, 0);
            Vfx.cloud(ctx(), center, Particle.REVERSE_PORTAL, 40, 0.8, 0.3, null, true);
            Vfx.cloud(ctx(), center, Particle.SOUL_FIRE_FLAME, 24, 0.9, 0.08, null, true);
            Vfx.shockwave(ctx(), owner, base.clone().add(0, 0.12, 0), 0.5, 3.0, 6, Particle.DUST, CHAR_DUST, true);
            Fx.sound(base, Sound.BLOCK_PORTAL_TRAVEL, 0.35f, 1.6f);
            Fx.sound(base, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 1.0f, 0.5f);
            Fx.sound(base, Sound.BLOCK_FIRE_EXTINGUISH, 0.8f, 0.6f + Fx.jitter(0.05f));
            if (pillars > 0 && owner.isOnline()) {
                owner.sendActionBar(Text.mm("<" + CRIMSON + ">✠ 지옥문</" + CRIMSON + "> <white>영혼불 기둥 "
                        + pillars + "개</white><dark_gray> · </dark_gray><" + SOUL_TAG + ">" + struck.size()
                        + "명 적중</" + SOUL_TAG + ">"));
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** A box in the display's own frame from corner {@code (x, y, z)} with size {@code (sx, sy, sz)}. */
    private static Transformation box(float x, float y, float z, float sx, float sy, float sz) {
        return new Transformation(new Vector3f(x, y, z), new Quaternionf(), new Vector3f(sx, sy, sz),
                new Quaternionf());
    }

    private static void ignite(LivingEntity target, long ticks) {
        if (ticks <= 0 || !target.isValid()) {
            return;
        }
        int fireTicks = (int) Math.min(Integer.MAX_VALUE, ticks);
        if (target.getFireTicks() < fireTicks) {
            target.setFireTicks(fireTicks);
        }
    }
}
