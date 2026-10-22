package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 경찰 — the law. Enemies that use a skill near the police are put on the wanted list (a flashing light bar over their
 * heads that shows through walls, and they take more damage from the police); the active fires a taser whose two
 * darts lock an enemy's legs with current; the ultimate arrests one enemy: handcuffs, a drag to the police and a jail
 * cell of iron bars where the prisoner can neither move nor use skills, with a police car pulling up, siren wailing.
 *
 * <p>Unlike the 음파술사 (who only learns where enemies were), a wanted enemy is marked for everyone and punished by the
 * police; unlike the 대지술사's cages (every enemy around, held where they stand), the arrest takes one enemy and
 * brings it in, and its cell is electrified.
 *
 * <p>Look: navy, white and the red and blue of the siren. The wanted light bar spins and flashes red and blue over
 * the suspect's head. The taser fires two yellow darts trailing crackling wires; a hit locks the victim in a cage of
 * sparks while the wires buzz. The handcuffs spin through the air, snap shut with a "철컥" and a silver chain drags the
 * prisoner in; iron bars slam shut around it under an iron lid, a "체포!" pops over it and a navy and white police
 * car with a flashing light bar rolls up beside the cell, siren wailing, and drives off when the prisoner is released.
 */
public final class PoliceAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("police", "경찰", Material.IRON_BARS)
            .color(TextColor.color(0x3D7BFF))
            .difficulty(2)
            .passive("사이렌",
                    "반경 {siren_radius}블록 안의 적이 스킬을 쓰면 사이렌이 울리고 그 적을 {wanted_duration}초간 수배합니다. "
                            + "수배된 적은 머리 위에서 경광등이 번쩍여 벽 너머로도 위치가 드러나고, 내가 주는 모든 피해가 "
                            + "{wanted_bonus}% 늘어납니다.")
            .active("테이저",
                    "바라보는 방향으로 전기침 두 개를 쏩니다({taser_range}블록). 맞은 적은 {taser_damage} 피해를 입고 "
                            + "{taser_stun}초간 감전되어 움직이거나 뛸 수 없습니다(수배된 적은 {taser_wanted_stun}초). "
                            + "빗나가면 쿨타임이 {taser_refund}% 돌아옵니다.",
                    9)
            .ultimate("체포",
                    "조준한 적({arrest_range}블록 이내)에게 수갑을 던져 {arrest_damage} 피해를 주고 내 앞으로 끌고 온 뒤 "
                            + "{jail_duration}초간 철창에 가둡니다. 갇힌 적은 움직이거나 능력을 쓸 수 없고 "
                            + "{jail_shock_period}초마다 {jail_shock} 감전 피해를 입습니다. 경찰차가 달려와 사이렌을 울립니다.")
            .param("siren_radius", 16)
            .param("wanted_duration", 5)
            .param("wanted_bonus", 15)
            .param("taser_range", 10)
            .param("taser_damage", 3)
            .param("taser_stun", 1)
            .param("taser_wanted_stun", 1.5)
            .param("taser_refund", 50)
            .param("arrest_range", 16)
            .param("arrest_damage", 2)
            .param("jail_duration", 2.5)
            .param("jail_shock", 0.75)
            .param("jail_shock_period", 0.5)
            .build();

    private static final String COLOR_TAG = "#3D7BFF";
    private static final String RED_TAG = "#FF3B3B";
    private static final double TAU = Math.PI * 2;

    // ------------------------------------------------------------------ look
    /** Pale blue-white, police blue, navy. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xE8F0FF, 0x3D7BFF, 0x0D1B3E);
    /** The taser's current: white, taser yellow, police blue. */
    private static final Vfx.Palette VOLT = Vfx.Palette.of(0xFFFFFF, 0xFFE14D, 0x3D7BFF);
    private static final Color SIREN_RED = Color.fromRGB(0xFF3B3B);
    private static final Color SIREN_BLUE = Color.fromRGB(0x2F6BFF);
    private static final Particle.DustOptions RED_DUST = new Particle.DustOptions(SIREN_RED, 1.0f);
    private static final Particle.DustOptions BLUE_DUST = new Particle.DustOptions(SIREN_BLUE, 1.0f);
    private static final Particle.DustOptions WIRE = new Particle.DustOptions(Color.fromRGB(0x2B2B2B), 0.45f);
    private static final Particle.DustTransition SPARK = VOLT.glow(0.7f);
    private static final Particle.DustTransition NAVY_FADE = PALETTE.fade(1.0f);
    private static final Particle.DustOptions CHAIN_DUST = new Particle.DustOptions(Color.fromRGB(0xC9D1DA), 0.7f);

    /** Block and item types of the visuals (block data is created on use: it needs a running server). */
    private static final Material LIGHT_RED = Material.RED_CONCRETE;
    private static final Material LIGHT_BLUE = Material.BLUE_CONCRETE;
    private static final Material DART_BLOCK = Material.YELLOW_CONCRETE;
    private static final Material CUFFS_ITEM = Material.IRON_CHAIN;
    private static final Material BARS = Material.IRON_BARS;
    private static final Material LID = Material.IRON_TRAPDOOR;

    /** Display entities this ability keeps alive at most at once (optional pieces are skipped beyond it). */
    private static final int MAX_DISPLAYS = 24;
    /** Caps every velocity this ability applies. */
    private static final double MAX_SPEED = 1.6;
    /** Vanilla's vertical speed of a body resting on the ground: one tick of gravity. */
    private static final double REST_FALL = -Motion.GRAVITY * Motion.DRAG;

    // passive: siren
    private static final long SENSE_PERIOD = 5;
    private static final double COOLDOWN_JUMP = 0.3;
    private static final double GAUGE_DROP = 50;
    private static final int MAX_LIGHT_BARS = 6;
    private static final double LIGHT_ABOVE = 0.45;
    private static final float LIGHT_SIZE = 0.2f;
    private static final long FLASH_PERIOD = 4;

    // active: taser
    private static final double DART_SPEED = 1.6;
    private static final double DART_PAD = 0.25;
    private static final double DART_SPREAD = 0.13;
    private static final float DART_LENGTH = 0.28f;
    private static final float DART_WIDTH = 0.06f;
    private static final long CRACKLE_PERIOD = 3;

    // ultimate: arrest
    private static final double CUFF_SPEED = 1.4;
    private static final long CUFF_MAX_TICKS = 16;
    private static final double DRAG_STOP = 2.3;
    private static final double DRAG_ARRIVE = 0.9;
    private static final double DRAG_SPEED = 1.2;
    private static final long DRAG_MAX_TICKS = 24;
    private static final double DRAG_LEASH = 3.0;
    private static final long MAX_LAG_TICKS = 6;
    private static final double MILLIS_PER_TICK = 50.0;
    private static final double CAR_SIDE = 2.6;
    private static final double CAR_RUN = 9;
    private static final long CAR_ARRIVE_TICKS = 12;
    private static final long SIREN_PERIOD = 6;

    /** Enemies on the wanted list, by entity id. */
    private final Map<UUID, Wanted> wanted = new HashMap<>();
    /** What the police last saw of each enemy's cooldown and gauge. */
    private final Map<UUID, Watch> watched = new HashMap<>();
    private final List<Display> displays = new ArrayList<>();
    /** The target of the taser hit being dealt right now, and whether it landed (see {@link #onDamageDealt}). */
    private @Nullable LivingEntity pendingTarget;
    private boolean landed;

    private static final class Wanted {
        final LivingEntity target;
        long until;
        @Nullable BlockDisplay red;
        @Nullable BlockDisplay blue;

        Wanted(LivingEntity target) {
            this.target = target;
        }
    }

    private static final class Watch {
        boolean known;
        double cooldown;
        double gauge;
    }

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        for (Wanted entry : wanted.values()) {
            removeLights(entry);
        }
        wanted.clear();
        watched.clear();
        displays.clear();
        pendingTarget = null;
    }

    // ------------------------------------------------------------------ passive: siren

    @Override
    public void onTick(long tick) {
        Player owner = player();
        if (tick % SENSE_PERIOD == 0) {
            listen(owner);
        }
        updateWanted(owner, tick);
    }

    /** Puts every enemy within earshot that just used a skill (its cooldown jumped, its gauge was spent) on the list. */
    private void listen(Player owner) {
        double radius = params().num("siren_radius");
        Location at = owner.getLocation();
        watched.keySet().removeIf(id -> {
            Player other = owner.getServer().getPlayer(id);
            return other == null || !ctx().isAlive(other);
        });
        for (Player other : ctx().alivePlayers()) {
            if (other.equals(owner) || !ctx().isEnemy(owner, other)) {
                continue;
            }
            Watch watch = watched.computeIfAbsent(other.getUniqueId(), id -> new Watch());
            double cooldown = ctx().cooldownRemaining(other);
            double gauge = ctx().gauge(other);
            boolean skill = watch.known
                    && (cooldown > watch.cooldown + COOLDOWN_JUMP || watch.gauge - gauge >= GAUGE_DROP);
            watch.known = true;
            watch.cooldown = cooldown;
            watch.gauge = gauge;
            if (skill && other.getWorld().equals(owner.getWorld())
                    && other.getLocation().distanceSquared(at) <= radius * radius) {
                markWanted(owner, other);
            }
        }
    }

    /** Puts {@code suspect} on the wanted list (or renews it): the siren whoops and the light bar goes up. */
    private void markWanted(Player owner, LivingEntity suspect) {
        long ticks = params().ticks("wanted_duration");
        if (ticks <= 0) {
            return;
        }
        long now = ctx().currentTick();
        Wanted entry = wanted.computeIfAbsent(suspect.getUniqueId(), id -> new Wanted(suspect));
        boolean fresh = entry.until <= now;
        entry.until = now + ticks;
        if (isHidden(owner, suspect)) {
            return;
        }
        Location feet = suspect.getLocation().add(0, 0.1, 0);
        Vfx.ring(ctx(), feet, 0.9, 12, 0, Particle.DUST, RED_DUST, true);
        Vfx.ring(ctx(), feet.clone().add(0, 0.12, 0), 0.9, 12, Math.PI / 12, Particle.DUST, BLUE_DUST, true);
        Fx.sound(feet, Sound.BLOCK_NOTE_BLOCK_BIT, 0.6f, 1.7f);
        // Only the police hears the siren from the suspect's direction: whoop-whoop.
        owner.playSound(feet, Sound.BLOCK_NOTE_BLOCK_BIT, SoundCategory.PLAYERS, 0.9f, 1.4f);
        ctx().runLater(owner, 3, () -> owner.playSound(feet, Sound.BLOCK_NOTE_BLOCK_BIT, SoundCategory.PLAYERS,
                0.9f, 1.0f));
        ctx().runLater(owner, 6, () -> owner.playSound(feet, Sound.BLOCK_NOTE_BLOCK_BIT, SoundCategory.PLAYERS,
                0.9f, 1.4f));
        owner.sendActionBar(Text.mm("<" + RED_TAG + ">⚠</" + RED_TAG + "> <" + COLOR_TAG + ">"
                        + (fresh ? "수배" : "수배 연장") + "</" + COLOR_TAG + "> <white><suspect></white> <gray>스킬 사용</gray>",
                Placeholder.component("suspect", suspect.name())));
    }

    /** Keeps the light bars spinning over the suspects and takes them down when the warrant runs out. */
    private void updateWanted(Player owner, long tick) {
        if (wanted.isEmpty()) {
            return;
        }
        long now = ctx().currentTick();
        int shown = 0;
        Iterator<Wanted> entries = wanted.values().iterator();
        while (entries.hasNext()) {
            Wanted entry = entries.next();
            LivingEntity target = entry.target;
            if (entry.until <= now || !Combat.isTargetable(ctx(), owner, target)
                    || !target.getWorld().equals(owner.getWorld())) {
                if (entry.red != null && entry.red.isValid() && target.isValid()) {
                    Vfx.cloud(ctx(), lightSpot(target), Particle.SMOKE, 3, 0.1, 0.01, null, false);
                }
                removeLights(entry);
                entries.remove();
                continue;
            }
            if (isHidden(owner, target)) {
                // Stealth hides the light too: it must not give the suspect away.
                removeLights(entry);
                continue;
            }
            if (shown >= MAX_LIGHT_BARS) {
                removeLights(entry);
                continue;
            }
            shown++;
            spinLights(owner, entry, tick);
        }
    }

    /** The light bar over a suspect: a red and a blue lamp turning round the head, flashing in turn. */
    private void spinLights(Player owner, Wanted entry, long tick) {
        Location over = lightSpot(entry.target);
        if (entry.red == null || !entry.red.isValid() || entry.blue == null || !entry.blue.isValid()) {
            removeLights(entry);
            if (!reserve(2)) {
                return;
            }
            entry.red = Displays.block(ctx(), owner, over, LIGHT_RED.createBlockData(), lampPose(0, 0.02f), 0);
            entry.blue = Displays.block(ctx(), owner, over, LIGHT_BLUE.createBlockData(), lampPose(180, 0.02f), 0);
            for (BlockDisplay lamp : new BlockDisplay[] {entry.red, entry.blue}) {
                lamp.setTeleportDuration(1);
                lamp.setViewRange(2.0f);
                displays.add(lamp);
            }
            return;
        }
        Displays.moveTo(entry.red, over);
        Displays.moveTo(entry.blue, over);
        if (tick % 2 == 0) {
            float angle = (tick * 24f) % 360f;
            Displays.animate(entry.red, lampPose(angle, LIGHT_SIZE), 2);
            Displays.animate(entry.blue, lampPose(angle + 180, LIGHT_SIZE), 2);
        }
        if (tick % FLASH_PERIOD == 0) {
            boolean redTurn = (tick / FLASH_PERIOD) % 2 == 0;
            Displays.outline(redTurn ? entry.red : entry.blue, redTurn ? SIREN_RED : SIREN_BLUE);
            (redTurn ? entry.blue : entry.red).setGlowing(false);
            Vfx.dot(over, Particle.DUST, redTurn ? RED_DUST : BLUE_DUST, false);
        }
    }

    /** A lamp of the light bar, {@code yaw} degrees round the head, as a small cube a little off the center. */
    private static Transformation lampPose(float yaw, float size) {
        double angle = Math.toRadians(yaw);
        float r = 0.17f;
        return Displays.transform((float) (-Math.sin(angle) * r), 0, (float) (Math.cos(angle) * r), size, size * 0.8f,
                size, yaw, 0, 0);
    }

    private static Location lightSpot(LivingEntity target) {
        return target.getLocation().add(0, target.getHeight() + LIGHT_ABOVE, 0);
    }

    private void removeLights(Wanted entry) {
        if (entry.red != null && entry.red.isValid()) {
            entry.red.remove();
        }
        if (entry.blue != null && entry.blue.isValid()) {
            entry.blue.remove();
        }
        entry.red = null;
        entry.blue = null;
    }

    private boolean isWanted(LivingEntity target) {
        Wanted entry = wanted.get(target.getUniqueId());
        return entry != null && entry.until > ctx().currentTick();
    }

    /** Every hit of the police on a wanted enemy is harder; the victim may still cancel it afterwards. */
    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled() || !ctx().isEnemy(player(), victim) || !isWanted(victim)) {
            return;
        }
        double bonus = Math.max(0, params().num("wanted_bonus")) / 100.0;
        if (bonus > 0) {
            event.setDamage(event.getDamage() * (1 + bonus));
        }
    }

    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (ctx().isAbilityDamage() && victim.equals(pendingTarget)) {
            landed = true;
        }
        if (isWanted(victim) && !isHidden(player(), victim)) {
            Location body = Fx.body(victim);
            Vfx.dot(body.clone().add(0.15, 0.2, 0), Particle.DUST, RED_DUST, false);
            Vfx.dot(body.clone().add(-0.15, 0.2, 0), Particle.DUST, BLUE_DUST, false);
        }
    }

    // ------------------------------------------------------------------ active: taser

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection();
        if (!(direction.lengthSquared() > 1.0E-8)) {
            return ActivationResult.fail("쏠 방향이 없습니다");
        }
        new Taser(owner, direction.normalize()).fire();
        return ActivationResult.success();
    }

    /** Two darts flying side by side, trailing wires; the first enemy they reach is stunned with current. */
    private final class Taser implements LongPredicate {

        private final Player owner;
        private final Vector direction;
        private final Vector side;
        private final double range;
        private final World world;
        private Location position;
        private double travelled;
        private final BlockDisplay[] darts = new BlockDisplay[2];
        private boolean done;

        Taser(Player owner, Vector direction) {
            this.owner = owner;
            this.direction = direction;
            Vector across = direction.getCrossProduct(new Vector(0, 1, 0));
            this.side = across.lengthSquared() < 1.0E-6 ? new Vector(1, 0, 0) : across.normalize();
            this.range = Math.max(1, params().num("taser_range"));
            this.world = owner.getWorld();
            this.position = owner.getEyeLocation();
        }

        void fire() {
            Location muzzle = hand(owner);
            for (int i = 0; i < darts.length; i++) {
                if (!reserve(1)) {
                    break;
                }
                Location at = muzzle.clone().add(side.clone().multiply(i == 0 ? DART_SPREAD : -DART_SPREAD));
                darts[i] = Displays.block(ctx(), owner, at, DART_BLOCK.createBlockData(), dartPose(direction),
                        (long) Math.ceil(range / DART_SPEED) + 8);
                darts[i].setTeleportDuration(1);
                displays.add(darts[i]);
            }
            Vfx.dot(muzzle, Particle.FLASH, VOLT.body(), false);
            Vfx.cloud(ctx(), muzzle, Particle.ELECTRIC_SPARK, 8, 0.1, 0.15, null, false);
            Fx.sound(muzzle, Sound.ITEM_CROSSBOW_SHOOT, 0.8f, 1.8f + Fx.jitter(0.05f));
            Fx.sound(muzzle, Sound.BLOCK_COPPER_BULB_TURN_ON, 0.9f, 1.6f + Fx.jitter(0.1f));
            Fx.sound(muzzle, Sound.ENTITY_BEE_STING, 0.6f, 1.5f + Fx.jitter(0.1f));
            Loop.repeat(ctx(), owner, 1, 1, (long) Math.ceil(range / DART_SPEED) + 2, this, this::end);
        }

        @Override
        public boolean test(long tick) {
            if (done) {
                return false;
            }
            double step = Math.min(DART_SPEED, range - travelled);
            if (step <= 1.0E-3) {
                miss(null);
                return false;
            }
            Vector start = position.toVector();
            RayTraceResult wall = world.rayTraceBlocks(position, direction, step, FluidCollisionMode.NEVER, true);
            double reach = wall == null ? step : wall.getHitPosition().distance(start);
            LivingEntity victim = firstEnemy(start, reach);
            if (victim != null) {
                position = Fx.body(victim);
                drawDarts();
                hit(victim);
                return false;
            }
            if (wall != null) {
                position = wall.getHitPosition().toLocation(world).subtract(direction.clone().multiply(0.1));
                drawDarts();
                miss(wall.getHitPosition().toLocation(world));
                return false;
            }
            position = position.clone().add(direction.clone().multiply(step));
            travelled += step;
            drawDarts();
            return true;
        }

        /** The nearest enemy (visible to the police) whose padded hitbox this tick's segment crosses. */
        private @Nullable LivingEntity firstEnemy(Vector start, double reach) {
            Location middle = start.clone().add(direction.clone().multiply(reach / 2)).toLocation(world);
            LivingEntity best = null;
            double bestDistance = Double.MAX_VALUE;
            for (LivingEntity enemy : ctx().enemiesNear(owner, middle, reach / 2 + 1.5)) {
                if (isHidden(owner, enemy)) {
                    continue;
                }
                BoundingBox box = enemy.getBoundingBox().expand(DART_PAD);
                double distance;
                if (box.contains(start)) {
                    distance = 0;
                } else {
                    RayTraceResult crossing = box.rayTrace(start, direction, reach);
                    if (crossing == null) {
                        continue;
                    }
                    distance = crossing.getHitPosition().distance(start);
                }
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = enemy;
                }
            }
            return best;
        }

        private void drawDarts() {
            Location muzzle = hand(owner);
            for (int i = 0; i < darts.length; i++) {
                Location at = position.clone().add(side.clone().multiply(i == 0 ? DART_SPREAD : -DART_SPREAD));
                BlockDisplay dart = darts[i];
                if (dart != null && dart.isValid()) {
                    Displays.moveTo(dart, at);
                }
                wire(muzzle.clone().add(side.clone().multiply(i == 0 ? 0.05 : -0.05)), at, false);
            }
            Vfx.dot(position, Particle.ELECTRIC_SPARK, null, false);
        }

        private void hit(LivingEntity victim) {
            done = true;
            boolean marked = isWanted(victim);
            pendingTarget = victim;
            landed = false;
            try {
                Combat.hit(ctx(), owner, victim, params().num("taser_damage"));
            } finally {
                pendingTarget = null;
            }
            if (!landed || !Combat.isTargetable(ctx(), owner, victim)) {
                removeDarts();
                Vfx.cloud(ctx(), Fx.body(victim), Particle.ELECTRIC_SPARK, 6, 0.3, 0.1, null, false);
                return;
            }
            long stun = params().ticks(marked ? "taser_wanted_stun" : "taser_stun");
            if (stun > 0) {
                ctx().addModifier(owner, victim, Attribute.MOVEMENT_SPEED, -1.0, Operation.MULTIPLY_SCALAR_1, stun);
                ctx().addModifier(owner, victim, Attribute.JUMP_STRENGTH, -1.0, Operation.MULTIPLY_SCALAR_1, stun);
            }
            Location body = Fx.body(victim);
            Vfx.dot(body, Particle.FLASH, VOLT.core(), false);
            Vfx.burst(ctx(), body, VOLT, 0.6, Particle.ELECTRIC_SPARK, false);
            Fx.sound(body, Sound.ENTITY_BEE_STING, 1.0f, 0.8f + Fx.jitter(0.1f));
            Fx.sound(body, Sound.BLOCK_BEEHIVE_WORK, 1.0f, 1.8f);
            Fx.sound(body, Sound.ENTITY_GUARDIAN_ATTACK, 0.5f, 2.0f);
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">⚡ 테이저 명중</" + COLOR_TAG + "> <gray>"
                    + AbilityInfo.formatNumber(stun / 20.0) + "초 감전</gray>"
                    + (marked ? " <" + RED_TAG + ">수배자!</" + RED_TAG + ">" : "")));
            electrify(victim, Math.max(1, stun));
        }

        /** The current runs while the victim is held: darts stuck in it, the wires buzzing, sparks all over. */
        private void electrify(LivingEntity victim, long stun) {
            Loop.repeat(ctx(), owner, 1, 1, stun, run -> {
                if (!Combat.isTargetable(ctx(), owner, victim)) {
                    return false;
                }
                Location body = Fx.body(victim);
                Location muzzle = hand(owner);
                for (int i = 0; i < darts.length; i++) {
                    Location at = body.clone().add(side.clone().multiply(i == 0 ? 0.12 : -0.12));
                    BlockDisplay dart = darts[i];
                    if (dart != null && dart.isValid()) {
                        Displays.moveTo(dart, at);
                    }
                    if (run % 2 == 0 && muzzle.distanceSquared(at) < 400) {
                        wire(muzzle, at, true);
                    }
                }
                zigzag(victim, run);
                if (run % CRACKLE_PERIOD == 0) {
                    Fx.sound(body, Sound.BLOCK_BEEHIVE_WORK, 0.6f, 1.9f + Fx.jitter(0.1f));
                }
                return true;
            }, this::removeDarts);
        }

        /** Sparks crawling over the victim's body in short zigzags. */
        private void zigzag(LivingEntity victim, long run) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            Location feet = victim.getLocation();
            double height = victim.getHeight();
            double radius = victim.getWidth() * 0.7;
            double angle = run * 1.3;
            Location a = feet.clone().add(Math.cos(angle) * radius, random.nextDouble(0.1, height), Math.sin(angle) * radius);
            Location b = feet.clone().add(Math.cos(angle + 1.7) * radius, random.nextDouble(0.1, height),
                    Math.sin(angle + 1.7) * radius);
            Vfx.line(ctx(), a, b, 5, Particle.DUST_COLOR_TRANSITION, SPARK, false);
            Vfx.dot(a, Particle.ELECTRIC_SPARK, null, false);
        }

        private void miss(@Nullable Location wallHit) {
            done = true;
            if (wallHit != null) {
                Vfx.cloud(ctx(), wallHit, Particle.ELECTRIC_SPARK, 6, 0.1, 0.1, null, false);
                Fx.sound(wallHit, Sound.BLOCK_IRON_HIT, 0.5f, 1.8f + Fx.jitter(0.1f));
            }
            double refund = Math.clamp(params().num("taser_refund") / 100.0, 0.0, 1.0) * params().cooldownSeconds()
                    * cooldownShare();
            if (refund > 0) {
                ctx().reduceCooldown(owner, refund);
            }
            owner.sendActionBar(Text.mm("<gray>테이저가 빗나갔습니다</gray>" + (refund > 0 ? " <" + COLOR_TAG
                    + ">쿨타임 -" + AbilityInfo.formatNumber(Math.round(refund * 10) / 10.0) + "초</" + COLOR_TAG
                    + ">" : "")));
            // The darts drop, trailing slack wire.
            ctx().runLater(owner, 2, this::removeDarts);
        }

        /** The loop ran out without hitting or missing (should not happen): nothing is left in the air. */
        void end() {
            if (!done) {
                miss(null);
            }
        }

        private void removeDarts() {
            for (BlockDisplay dart : darts) {
                if (dart != null && dart.isValid()) {
                    Displays.shrinkAway(ctx(), owner, dart, 3);
                }
            }
        }
    }

    /** The share of the taser's cooldown that is really running (cooldowns may be off in practice). */
    private double cooldownShare() {
        return ctx().cooldownRemaining(player()) > 0 ? 1.0 : 0.0;
    }

    /** A dart lying along {@code direction}: a thin yellow box, turned only in its transformation. */
    private static Transformation dartPose(Vector direction) {
        Quaternionf rotation = new Quaternionf().rotationTo(new Vector3f(0, 0, 1), toJoml(direction));
        return box(rotation, DART_WIDTH, DART_WIDTH, DART_LENGTH);
    }

    /** A taser wire: a thin dark line with a few sparks; a live wire crackles with yellow current. */
    private void wire(Location from, Location to, boolean live) {
        double length = from.distance(to);
        if (length < 0.2 || length > 24) {
            return;
        }
        Vfx.line(ctx(), from, to, Math.min(3, 40 / length), Particle.DUST, WIRE, false);
        if (live) {
            Location mid = from.clone().add(to.toVector().subtract(from.toVector()).multiply(
                    ThreadLocalRandom.current().nextDouble(0.2, 0.8)));
            Vfx.dot(mid, Particle.ELECTRIC_SPARK, null, false);
            Vfx.trail(from, to, VOLT.body(), 4, false);
        }
    }

    // ------------------------------------------------------------------ ultimate: arrest

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        LivingEntity target = ctx().findTarget(owner, params().num("arrest_range"));
        if (target == null || isHidden(owner, target)) {
            return ActivationResult.fail("조준한 곳에 체포할 적이 없습니다");
        }
        new Arrest(owner, target).start();
        return ActivationResult.success();
    }

    private enum Stage { THROW, DRAG, JAIL, DONE }

    /**
     * One arrest: the cuffs fly to the target, snap shut and drag it to the police; then a cell of iron bars closes
     * round it for the jail time while the police car stands by with its siren on.
     */
    private final class Arrest implements LongPredicate {

        private final Player owner;
        private final LivingEntity target;
        private final World world;
        private final long jail;
        private final long lag;
        private Stage stage = Stage.THROW;
        private long stageStart;
        private @Nullable ItemDisplay cuffs;
        private @Nullable TextDisplay label;
        private final List<BlockDisplay> car = new ArrayList<>();
        private Location cuffAt;
        private Location destination;
        private Vector inward = new Vector(0, 0, 1);
        private float carYaw;
        private @Nullable Location carPark;
        private long jailEnd;
        private float spin;
        /** Where the hauled target will be once it has followed every haul sent (see {@link #drag}). */
        private double hauledX;
        private double hauledZ;
        private double liftSent;

        Arrest(Player owner, LivingEntity target) {
            this.owner = owner;
            this.target = target;
            this.world = owner.getWorld();
            this.jail = Math.max(1, params().ticks("jail_duration"));
            this.lag = Math.min(MAX_LAG_TICKS, (long) Math.ceil(Math.max(0, owner.getPing()) / MILLIS_PER_TICK));
            this.cuffAt = hand(owner);
            this.destination = owner.getLocation();
        }

        void start() {
            cuffs = Displays.item(ctx(), owner, cuffAt, new ItemStack(CUFFS_ITEM), cuffPose(0.2f, 0),
                    CUFF_MAX_TICKS + DRAG_MAX_TICKS + jail + 30);
            cuffs.setTeleportDuration(1);
            cuffs.setViewRange(2.0f);
            displays.add(cuffs);
            Fx.sound(cuffAt, Sound.BLOCK_CHAIN_PLACE, 1.0f, 1.2f + Fx.jitter(0.05f));
            Fx.sound(cuffAt, Sound.ITEM_ARMOR_EQUIP_CHAIN, 1.0f, 1.0f);
            Fx.sound(cuffAt, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.5f, 1.8f);
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>⛓ 체포!</b></" + COLOR_TAG + "> <gray>수갑을 던졌습니다</gray>"));
            Loop.repeat(ctx(), owner, 1, 1, CUFF_MAX_TICKS + DRAG_MAX_TICKS + lag + jail + 4, this, this::end);
        }

        @Override
        public boolean test(long tick) {
            return switch (stage) {
                case THROW -> fly(tick);
                case DRAG -> drag(tick);
                case JAIL -> hold(tick);
                case DONE -> false;
            };
        }

        /** The cuffs spin through the air, homing on the target's wrists. */
        private boolean fly(long tick) {
            // A suspect that vanishes (stealth) before the cuffs reach it gets away: homing on it would give it away.
            if (!Combat.isTargetable(ctx(), owner, target) || !target.getWorld().equals(world)
                    || isHidden(owner, target)) {
                return false;
            }
            Location wrists = wrists();
            Vector to = wrists.toVector().subtract(cuffAt.toVector());
            double distance = to.length();
            spin += 40;
            if (distance <= CUFF_SPEED || tick >= CUFF_MAX_TICKS - 1) {
                cuffAt = wrists;
                moveCuffs(wrists, 0.55f);
                cuff();
                return true;
            }
            cuffAt = cuffAt.clone().add(to.multiply(CUFF_SPEED / distance));
            moveCuffs(cuffAt, 0.55f);
            Vfx.line(ctx(), hand(owner), cuffAt, 2, Particle.DUST, CHAIN_DUST, false);
            Vfx.dot(cuffAt, Particle.WAX_OFF, null, false);
            if (tick % 2 == 0) {
                Fx.sound(cuffAt, Sound.BLOCK_CHAIN_STEP, 0.6f, 1.5f + Fx.jitter(0.1f));
            }
            return true;
        }

        /** Click: the cuffs close, the arrest damage lands and the drag begins; the police car is called. */
        private void cuff() {
            Location body = Fx.body(target);
            Combat.hit(ctx(), owner, target, params().num("arrest_damage"));
            Vfx.dot(body, Particle.FLASH, PALETTE.core(), true);
            Vfx.burst(ctx(), body, PALETTE, 0.6, Particle.WAX_OFF, true);
            Fx.sound(body, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 1.2f, 1.6f);
            Fx.sound(body, Sound.BLOCK_CHAIN_PLACE, 1.2f, 0.8f);
            Fx.sound(body, Sound.ENTITY_IRON_GOLEM_REPAIR, 0.7f, 1.6f);
            popLabel(Fx.body(target).add(0, 1.3, 0), "<b><gradient:#FFFFFF:" + COLOR_TAG + ">철컥!</gradient></b>", 14);
            if (!Combat.isTargetable(ctx(), owner, target)) {
                stage = Stage.DONE;
                finishVisuals(false);
                return;
            }
            // Drag it to a spot in front of the police, along the line between them.
            Location feet = owner.getLocation();
            Vector toTarget = Motion.horizontal(target.getLocation().toVector().subtract(feet.toVector()));
            if (toTarget.lengthSquared() < 1.0E-6) {
                toTarget = Motion.horizontalFacing(feet);
            }
            inward = toTarget.clone();
            double flat = Math.hypot(target.getLocation().getX() - feet.getX(), target.getLocation().getZ() - feet.getZ());
            destination = flat <= DRAG_STOP ? target.getLocation() : feet.clone().add(toTarget.clone().multiply(DRAG_STOP));
            destination.setY(target.getLocation().getY());
            hauledX = target.getLocation().getX();
            hauledZ = target.getLocation().getZ();
            carYaw = yawOf(inward);
            Vector side = new Vector(-inward.getZ(), 0, inward.getX());
            carPark = parkSpot(destination, side);
            callCar();
            stage = Stage.DRAG;
            stageStart = ctx().currentTick();
        }

        /**
         * Hauls the cuffed target in by the chain; one that gets stuck (a wall) or slips too far is jailed there.
         *
         * <p>The target is steered by where it will be once it has followed every haul sent so far (dead reckoning),
         * not by its position on the server: that trails a player's client by its ping, and steering by it would keep
         * hauling a target that is already there, yanking it past the police.
         */
        private boolean drag(long tick) {
            if (!Combat.isTargetable(ctx(), owner, target) || !target.getWorld().equals(world)) {
                return false;
            }
            long elapsed = ctx().currentTick() - stageStart;
            Vector to = new Vector(destination.getX() - hauledX, 0, destination.getZ() - hauledZ);
            double distance = to.length();
            boolean arrived = distance <= DRAG_ARRIVE;
            boolean timedOut = elapsed >= DRAG_MAX_TICKS + lag;
            boolean slipped = elapsed > lag + 2 && target.getLocation().distance(owner.getLocation())
                    > params().num("arrest_range") + DRAG_LEASH;
            if (arrived || timedOut || slipped) {
                // Stop the haul. A mob keeps the fall speed the server simulates for it; a player's server-side
                // velocity is stale, so it gets vanilla's resting fall speed instead of a replayed one.
                double fall = target instanceof Player ? REST_FALL : Math.min(0, target.getVelocity().getY());
                target.setVelocity(new Vector(0, fall, 0));
                target.setFallDistance(0);
                lockUp();
                return true;
            }
            double speed = Math.min(DRAG_SPEED, Math.max(0.25, distance * 0.45));
            Vector velocity = to.multiply(speed / Math.max(distance, 1.0E-6));
            boolean grounded = Motion.isGrounded(target) || target.isInWater();
            velocity.setY(grounded ? 0.2 : Math.max(-0.4, liftSent - 0.08));
            Vector sent = Motion.clamp(velocity, MAX_SPEED);
            liftSent = sent.getY();
            hauledX += sent.getX();
            hauledZ += sent.getZ();
            // The haul is crowd control, not knockback: it ignores knockback resistance by design.
            target.setVelocity(sent);
            target.setFallDistance(0);
            Location wrists = wrists();
            moveCuffs(wrists, 0.55f);
            Location from = hand(owner);
            if (!veil()) {
                Vfx.line(ctx(), from, wrists, 3, Particle.DUST, CHAIN_DUST, false);
            }
            if (tick % 2 == 0) {
                Fx.sound(wrists, Sound.BLOCK_CHAIN_STEP, 0.8f, 0.9f + Fx.jitter(0.1f));
            }
            driveCar(tick);
            return true;
        }

        /** The cell closes: centered, rooted, silenced, the bars slam shut under an iron lid. */
        private void lockUp() {
            stage = Stage.JAIL;
            stageStart = ctx().currentTick();
            jailEnd = stageStart + jail;
            centerInBlock(target);
            ctx().addModifier(owner, target, Attribute.MOVEMENT_SPEED, -1.0, Operation.MULTIPLY_SCALAR_1, jail);
            ctx().addModifier(owner, target, Attribute.JUMP_STRENGTH, -1.0, Operation.MULTIPLY_SCALAR_1, jail);
            if (target instanceof Player prisoner) {
                ctx().silence(prisoner, jail);
            }
            buildCell(target, jail);
            shock();
            Location body = Fx.body(target);
            Location feet = target.getLocation();
            world.spawnParticle(Particle.BLOCK, body, Vfx.n(ctx(), 20), 0.8, 0.7, 0.8, 0.1, BARS.createBlockData(), true);
            Vfx.shockwave(ctx(), owner, feet.clone().add(0, 0.1, 0), 0.4, 1.8, 4, Particle.DUST_COLOR_TRANSITION,
                    NAVY_FADE, true);
            Vfx.helix(ctx(), feet.clone().add(0, 0.1, 0), 1.3, target.getHeight() + 0.6, 1.0, 2, 10,
                    ThreadLocalRandom.current().nextDouble(TAU), Particle.DUST_COLOR_TRANSITION, SPARK, true);
            Fx.sound(body, Sound.BLOCK_IRON_DOOR_CLOSE, 1.4f, 0.7f);
            Fx.sound(body, Sound.BLOCK_ANVIL_LAND, 0.4f, 1.6f);
            Fx.sound(body, Sound.BLOCK_CHAIN_PLACE, 1.0f, 0.6f);
            ItemDisplay held = cuffs;
            if (held != null && held.isValid()) {
                Displays.moveTo(held, wrists());
            }
            if (reserve(1)) {
                label = Displays.text(ctx(), owner, labelSpot(), labelText(jail), 0.75f, jail + 6);
                label.setTeleportDuration(2);
                label.setViewRange(2.0f);
                displays.add(label);
            }
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>⛓ 체포!</b></" + COLOR_TAG + "> <white><prisoner></white> <gray>"
                    + AbilityInfo.formatNumber(jail / 20.0) + "초 구금</gray>",
                    Placeholder.component("prisoner", target.name())));
        }

        /** The prisoner sits in the cell: shocks every period, the siren wails, then release. */
        private boolean hold(long tick) {
            long now = ctx().currentTick();
            long elapsed = now - stageStart;
            if (!Combat.isTargetable(ctx(), owner, target) || now >= jailEnd) {
                release();
                return false;
            }
            long period = Math.max(1, params().ticks("jail_shock_period"));
            if (elapsed > 0 && elapsed % period == 0) {
                shock();
            }
            boolean hidden = veil();
            ItemDisplay held = cuffs;
            if (held != null && held.isValid()) {
                spin += 6;
                moveCuffs(wrists(), 0.55f);
            }
            TextDisplay text = label;
            if (text != null && text.isValid()) {
                Displays.moveTo(text, labelSpot());
                if (tick % 2 == 0) {
                    text.text(labelText(jailEnd - now));
                }
            }
            driveCar(tick);
            if (!hidden && tick % 4 == 0) {
                Location feet = target.getLocation().add(0, 0.08, 0);
                Vfx.ring(ctx(), feet, 1.45, 14, tick * 0.15, Particle.DUST, tick % 8 == 0 ? RED_DUST : BLUE_DUST, true);
            }
            return true;
        }

        /** The bars are live: a jolt for the prisoner and sparks running up the bars. */
        private void shock() {
            Combat.hit(ctx(), owner, target, params().num("jail_shock"));
            if (isHidden(owner, target)) {
                return;
            }
            Location feet = target.getLocation();
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < Vfx.n(ctx(), 3); i++) {
                double angle = random.nextDouble(TAU);
                Location bar = feet.clone().add(Math.cos(angle) * 1.1, 0.1, Math.sin(angle) * 1.1);
                Vfx.line(ctx(), bar, bar.clone().add(0, target.getHeight() + 0.2, 0), 4, Particle.DUST_COLOR_TRANSITION,
                        SPARK, false);
            }
            Location body = Fx.body(target);
            Vfx.cloud(ctx(), body, Particle.ELECTRIC_SPARK, 8, 0.3, 0.1, null, false);
            Fx.sound(body, Sound.BLOCK_BEEHIVE_WORK, 0.8f, 1.8f + Fx.jitter(0.1f));
            Fx.sound(body, Sound.ENTITY_BEE_STING, 0.5f, 0.7f + Fx.jitter(0.1f));
        }

        /** The cell opens: the cuffs fall away and the car drives off. */
        private void release() {
            stage = Stage.DONE;
            if (target.isValid()) {
                Location body = Fx.body(target);
                world.spawnParticle(Particle.BLOCK, body, Vfx.n(ctx(), 12), 0.8, 0.7, 0.8, 0.1, BARS.createBlockData());
                Fx.sound(body, Sound.BLOCK_IRON_DOOR_OPEN, 1.0f, 0.9f);
                popLabel(body.clone().add(0, 1.3, 0), "<gray>석방</gray>", 12);
            }
            finishVisuals(true);
        }

        /** The arrest ends early or late: everything it shows is taken away. */
        void end() {
            if (stage != Stage.DONE) {
                stage = Stage.DONE;
                finishVisuals(false);
            }
        }

        private void finishVisuals(boolean driveOff) {
            ItemDisplay held = cuffs;
            if (held != null && held.isValid()) {
                Displays.shrinkAway(ctx(), owner, held, 4);
            }
            TextDisplay text = label;
            if (text != null && text.isValid()) {
                text.remove();
            }
            Location park = carPark;
            if (car.isEmpty() || park == null) {
                return;
            }
            if (!driveOff) {
                for (BlockDisplay part : car) {
                    if (part.isValid()) {
                        Displays.shrinkAway(ctx(), owner, part, 4);
                    }
                }
                return;
            }
            // The car pulls away the way it was facing and fades out.
            Vector forward = new Vector(-Math.sin(Math.toRadians(carYaw)), 0, Math.cos(Math.toRadians(carYaw)));
            Location away = park.clone().add(forward.multiply(4));
            Fx.sound(park, Sound.ENTITY_MINECART_RIDING, 0.6f, 1.4f);
            for (BlockDisplay part : car) {
                if (part.isValid()) {
                    part.setTeleportDuration(8);
                    Displays.moveTo(part, away);
                }
            }
            ctx().runLater(owner, 6, () -> {
                for (BlockDisplay part : car) {
                    if (part.isValid()) {
                        Displays.shrinkAway(ctx(), owner, part, 4);
                    }
                }
            });
        }

        /**
         * Hides the cuffs and the label from view while the prisoner is hidden from the police (stealth): they must
         * not give it away. Returns whether it is hidden.
         */
        private boolean veil() {
            boolean hidden = isHidden(owner, target);
            float range = hidden ? 0f : 2.0f;
            for (Display display : new Display[] {cuffs, label}) {
                if (display != null && display.isValid() && display.getViewRange() != range) {
                    display.setViewRange(range);
                }
            }
            return hidden;
        }

        private Location wrists() {
            return target.getLocation().add(0, target.getHeight() * 0.48, 0);
        }

        private Location labelSpot() {
            return target.getLocation().add(0, target.getHeight() + 1.0, 0);
        }

        private Component labelText(long ticksLeft) {
            return Text.mm("<b><" + COLOR_TAG + ">⛓ 체포</" + COLOR_TAG + "></b>\n<white>" + Fx.seconds(ticksLeft)
                    + "</white>");
        }

        private void moveCuffs(Location at, float scale) {
            ItemDisplay held = cuffs;
            if (held == null || !held.isValid()) {
                return;
            }
            Displays.moveTo(held, at);
            Displays.animate(held, cuffPose(scale, spin), 1);
        }

        private void popLabel(Location at, String text, long ticks) {
            if (!reserve(1)) {
                return;
            }
            TextDisplay pop = Displays.text(ctx(), owner, at, Text.mm(text), 0.5f, ticks);
            displays.add(pop);
            Displays.animateNextTick(ctx(), owner, pop, Displays.centered(0, 0.3f, 0, 1.1f, 1.1f, 1.1f, 0, 0, 0), 4);
        }

        // -------------------------------------------------------------- the police car

        /** A spot beside the cell for the car, on a side that is free; null if both sides are walls. */
        private @Nullable Location parkSpot(Location cell, Vector side) {
            for (double sign : new double[] {1, -1}) {
                Location spot = cell.clone().add(side.clone().multiply(CAR_SIDE * sign));
                Spots.snapToGround(world, spot, 3);
                if (Spots.isFree(world, spot, 1.4, 1.3) && Spots.clearPath(cell.clone().add(0, 0.6, 0),
                        spot.clone().add(0, 0.6, 0))) {
                    return spot;
                }
            }
            return null;
        }

        /** The car rolls in from behind (when the road is clear) and parks beside the cell. */
        private void callCar() {
            Location park = carPark;
            if (park == null || !reserve(CarPart.ALL.length)) {
                return;
            }
            Vector forward = new Vector(-Math.sin(Math.toRadians(carYaw)), 0, Math.cos(Math.toRadians(carYaw)));
            Location start = park.clone().subtract(forward.clone().multiply(CAR_RUN));
            boolean road = Spots.clearPath(start.clone().add(0, 0.6, 0), park.clone().add(0, 0.6, 0))
                    && Spots.clearPath(start.clone().add(0, 1.2, 0), park.clone().add(0, 1.2, 0));
            Location spawn = road ? start : park;
            long life = CAR_ARRIVE_TICKS + DRAG_MAX_TICKS + lag + jail + 24;
            for (CarPart part : CarPart.ALL) {
                BlockDisplay display = Displays.block(ctx(), owner, spawn, part.material.createBlockData(),
                        part.pose(carYaw, road ? 1f : 0.05f), life);
                display.setViewRange(2.0f);
                display.setTeleportDuration(road ? (int) CAR_ARRIVE_TICKS : 2);
                car.add(display);
                displays.add(display);
            }
            if (road) {
                ctx().runLater(owner, 1, () -> {
                    for (BlockDisplay display : car) {
                        Displays.moveTo(display, park);
                    }
                });
                Fx.sound(start, Sound.ENTITY_MINECART_RIDING, 1.0f, 1.2f);
            } else {
                ctx().runLater(owner, 1, () -> {
                    for (int i = 0; i < car.size() && i < CarPart.ALL.length; i++) {
                        Displays.animate(car.get(i), CarPart.ALL[i].pose(carYaw, 1f), 5);
                    }
                });
            }
            ctx().runLater(owner, CAR_ARRIVE_TICKS, () -> {
                for (BlockDisplay display : car) {
                    if (display.isValid()) {
                        display.setTeleportDuration(2);
                    }
                }
                if (!car.isEmpty()) {
                    Fx.sound(park, Sound.BLOCK_PISTON_CONTRACT, 0.6f, 1.6f);
                    Vfx.cloud(ctx(), park.clone().add(0, 0.2, 0), Particle.CLOUD, 6, 0.6, 0.02, null, false);
                }
            });
        }

        /** The light bar on the car flashes red and blue and the siren wails while the car is on the scene. */
        private void driveCar(long tick) {
            Location siren = carPark != null ? carPark : destination;
            if (tick % SIREN_PERIOD == 0) {
                boolean high = (tick / SIREN_PERIOD) % 2 == 0;
                Fx.sound(siren, Sound.BLOCK_NOTE_BLOCK_BIT, 0.9f, high ? 1.5f : 1.1f);
                Fx.sound(siren, Sound.BLOCK_NOTE_BLOCK_FLUTE, 0.4f, high ? 1.5f : 1.1f);
            }
            if (car.size() < CarPart.ALL.length) {
                return;
            }
            if (tick % 3 == 0) {
                boolean redTurn = (tick / 3) % 2 == 0;
                BlockDisplay red = car.get(CarPart.RED_INDEX);
                BlockDisplay blue = car.get(CarPart.BLUE_INDEX);
                if (red.isValid() && blue.isValid()) {
                    Displays.outline(redTurn ? red : blue, redTurn ? SIREN_RED : SIREN_BLUE);
                    (redTurn ? blue : red).setGlowing(false);
                    Location lamp = (redTurn ? red : blue).getLocation().add(0, 1.4, 0);
                    Vfx.dot(lamp, Particle.DUST, redTurn ? RED_DUST : BLUE_DUST, true);
                }
            }
        }
    }

    /** One block of the police car, in the car's frame (+z forward, y up from the road). */
    private record CarPart(Material material, float x, float y, float z, float sx, float sy, float sz) {

        static final CarPart[] ALL = {
                new CarPart(Material.BLUE_CONCRETE, 0, 0.5f, 0, 1.5f, 0.55f, 3.2f),
                new CarPart(Material.WHITE_CONCRETE, 0, 0.52f, 0, 1.53f, 0.24f, 1.7f),
                new CarPart(Material.GRAY_STAINED_GLASS, 0, 1.02f, -0.2f, 1.3f, 0.5f, 1.6f),
                new CarPart(Material.WHITE_CONCRETE, 0, 1.3f, -0.2f, 1.32f, 0.06f, 1.62f),
                new CarPart(Material.RED_CONCRETE, 0.26f, 1.42f, -0.1f, 0.46f, 0.18f, 0.3f),
                new CarPart(Material.BLUE_CONCRETE, -0.26f, 1.42f, -0.1f, 0.46f, 0.18f, 0.3f),
                new CarPart(Material.BLACK_CONCRETE, 0.7f, 0.22f, 1.05f, 0.24f, 0.44f, 0.44f),
                new CarPart(Material.BLACK_CONCRETE, -0.7f, 0.22f, 1.05f, 0.24f, 0.44f, 0.44f),
                new CarPart(Material.BLACK_CONCRETE, 0.7f, 0.22f, -1.05f, 0.24f, 0.44f, 0.44f),
                new CarPart(Material.BLACK_CONCRETE, -0.7f, 0.22f, -1.05f, 0.24f, 0.44f, 0.44f),
        };
        static final int RED_INDEX = 4;
        static final int BLUE_INDEX = 5;

        /** This part turned to the car's {@code yaw} (around the car's center), scaled by {@code scale}. */
        Transformation pose(float yaw, float scale) {
            double angle = Math.toRadians(yaw);
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            // Minecraft's yaw turns +z towards -x: local (x, z) -> world (x cos - z sin, x sin + z cos).
            float wx = (float) (x * cos - z * sin) * scale;
            float wz = (float) (x * sin + z * cos) * scale;
            return Displays.transform(wx, y * scale, wz, sx * scale, sy * scale, sz * scale, yaw, 0, 0);
        }
    }

    // ------------------------------------------------------------------ the cell

    /**
     * Builds the cell from temporary blocks: a ring of iron bars round the prisoner from its feet to its head, joined
     * into walls, and an iron lid above. Cells that are not replaceable or that overlap any living entity are skipped,
     * so nobody can suffocate; the root holds the prisoner anyway.
     */
    private void buildCell(LivingEntity target, long ticks) {
        World world = target.getWorld();
        BoundingBox box = target.getBoundingBox();
        int bx = (int) Math.floor(box.getCenterX());
        int bz = (int) Math.floor(box.getCenterZ());
        int bottom = (int) Math.floor(box.getMinY());
        int top = (int) Math.floor(box.getMaxY() - 1.0E-3);
        for (int y = bottom; y <= top; y++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    placeCell(world.getBlockAt(bx + dx, y, bz + dz), barsData(dx, dz), ticks);
                }
            }
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                placeCell(world.getBlockAt(bx + dx, top + 1, bz + dz), LID.createBlockData(), ticks);
            }
        }
    }

    /** Iron bars at ring offset (dx, dz), joined to the ring cells beside it so the bars read as walls. */
    private static BlockData barsData(int dx, int dz) {
        BlockData data = BARS.createBlockData();
        if (data instanceof MultipleFacing facing) {
            joinIfRing(facing, BlockFace.EAST, dx + 1, dz);
            joinIfRing(facing, BlockFace.WEST, dx - 1, dz);
            joinIfRing(facing, BlockFace.SOUTH, dx, dz + 1);
            joinIfRing(facing, BlockFace.NORTH, dx, dz - 1);
        }
        return data;
    }

    private static void joinIfRing(MultipleFacing facing, BlockFace face, int dx, int dz) {
        boolean ring = Math.abs(dx) <= 1 && Math.abs(dz) <= 1 && !(dx == 0 && dz == 0);
        if (ring && facing.getAllowedFaces().contains(face)) {
            facing.setFace(face, true);
        }
    }

    private void placeCell(Block block, BlockData data, long ticks) {
        int y = block.getY();
        if (y < block.getWorld().getMinHeight() || y >= block.getWorld().getMaxHeight() || isOccupied(block)) {
            return;
        }
        ctx().placeTempBlock(block, data, ticks);
    }

    private static boolean isOccupied(Block block) {
        BoundingBox cell = BoundingBox.of(block);
        return !block.getWorld().getNearbyEntities(cell, entity -> entity instanceof LivingEntity).isEmpty();
    }

    /**
     * Moves the prisoner to the middle of its block column when that space is free (and inside the arena), so the
     * cell fits around it; a player is moved even when already centred, since its client runs ahead of the server.
     */
    private void centerInBlock(LivingEntity target) {
        Location location = target.getLocation();
        Location centered = location.clone();
        centered.setX(location.getBlockX() + 0.5);
        centered.setZ(location.getBlockZ() + 0.5);
        if (!(target instanceof Player) && centered.distanceSquared(location) < 0.05 * 0.05) {
            return;
        }
        if (!Spots.isFree(target.getWorld(), centered, target.getWidth(), target.getHeight())
                || !ctx().isInsideArena(centered)) {
            return;
        }
        target.teleport(centered);
    }

    // ------------------------------------------------------------------ helpers

    /** Reserves room for {@code count} more displays under {@link #MAX_DISPLAYS}; false when there is none. */
    private boolean reserve(int count) {
        displays.removeIf(display -> !display.isValid());
        return displays.size() + count <= MAX_DISPLAYS;
    }

    private static boolean isHidden(Player owner, LivingEntity entity) {
        return entity instanceof Player hidden && !owner.canSee(hidden);
    }

    /** Where the taser and the cuffs leave the police: just in front of the right hand. */
    private static Location hand(Player owner) {
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection();
        if (!(direction.lengthSquared() > 1.0E-8)) {
            direction = Motion.horizontalFacing(eye);
        }
        direction.normalize();
        Vector right = direction.getCrossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1.0E-6) {
            double yaw = Math.toRadians(eye.getYaw());
            right = new Vector(-Math.cos(yaw), 0, -Math.sin(yaw));
        }
        right.normalize();
        return eye.add(direction.multiply(0.5)).add(right.multiply(0.25)).subtract(0, 0.35, 0);
    }

    /** The cuffs: a chain sprite standing on edge, turned {@code spin} degrees around the vertical. */
    private static Transformation cuffPose(float scale, float spin) {
        return Displays.centered(0, 0, 0, scale, scale, scale, spin, 0, 90);
    }

    /** Minecraft's yaw (degrees: 0 = +Z, 90 = -X) of the horizontal part of {@code direction}, 0 if it has none. */
    static float yawOf(Vector direction) {
        Vector flat = Motion.horizontal(direction);
        if (flat.lengthSquared() < 1.0E-6) {
            return 0;
        }
        return (float) Math.toDegrees(Math.atan2(-flat.getX(), flat.getZ()));
    }

    private static Vector3f toJoml(Vector vector) {
        Vector3f v = new Vector3f((float) vector.getX(), (float) vector.getY(), (float) vector.getZ());
        return v.lengthSquared() < 1.0E-8f ? new Vector3f(0, 0, 1) : v.normalize();
    }

    /** A box of the given size centered on the block display's origin, turned by {@code rotation}. */
    private static Transformation box(Quaternionf rotation, float sx, float sy, float sz) {
        Vector3f translation = new Quaternionf(rotation).transform(new Vector3f(sx / 2, sy / 2, sz / 2)).negate();
        return new Transformation(translation, new Quaternionf(rotation), new Vector3f(sx, sy, sz), new Quaternionf());
    }
}
