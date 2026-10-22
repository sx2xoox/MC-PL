package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
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
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 주술사 — a hexer who curses with every strike. Melee and arrow hits carve curse marks that make the hexer's own
 * damage hurt more, the active makes a voodoo doll of the aimed enemy whose pain spreads to the enemies around it,
 * and the ultimate binds every enemy nearby with soul chains that share their suffering.
 *
 * <p>Look (sickly lime, hex purple, near-black): curse marks are lime and purple motes circling the victim, and a
 * sigil of crosses (lime for each mark, gray for the free ones) pops over its head whenever a mark is carved. The
 * voodoo doll grows out of a puff of dark magic by the hexer's hand: a purple-robed burlap body wearing the victim's
 * own head, tied to the victim by a taut purple yarn with lime motes running along it; a needle stabs into the doll
 * and it twitches whenever pain is passed on along lime streaks. For the soul chains, glowing lime lashes shoot out
 * of the hand to every victim, then links of lime and purple light hang between wither skulls floating over the bound
 * enemies; the skulls pop and lime pulses run along the chains whenever one of them is hurt, and souls are drawn up
 * into the skulls with every drain.
 */
public final class HexerAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("hexer", "주술사", Material.WITHER_ROSE)
            .color(TextColor.color(0xA05CDB))
            .difficulty(3)
            .passive("저주 표식",
                    "근접 공격이나 화살로 맞힌 적에게 저주 표식을 새깁니다(최대 {max_stacks}개, {stack_duration}초 유지). "
                            + "표식 하나마다 내가 그 적에게 주는 모든 피해가 {stack_bonus}% 늘어납니다.")
            .active("부두 인형",
                    "조준한 적({doll_range}블록 이내)의 부두 인형을 바늘로 찔러 {doll_damage} 피해와 표식 "
                            + "{doll_stacks}개를 남깁니다. {doll_duration}초 동안 그 적이 받는 피해의 {doll_share}%가 반경 "
                            + "{doll_radius}블록의 다른 적들에게 옮아가며, 주변에 아무도 없으면 {doll_echo}%가 그 적에게 "
                            + "한 번 더 박힙니다.",
                    12)
            .ultimate("영혼 사슬",
                    "반경 {ult_radius}블록의 적을 모두 영혼 사슬로 묶어 {ult_damage} 피해와 {ult_duration}초 구속 I을 "
                            + "겁니다. 묶인 적은 {ult_tick}초마다 {ult_drain} 피해를 받고, 한 명이 받은 피해의 {ult_share}%를 "
                            + "묶인 나머지 적 모두가 함께 받습니다.")
            .param("max_stacks", 3)
            .param("stack_duration", 6)
            .param("stack_bonus", 8)
            .param("doll_range", 20)
            .param("doll_damage", 3)
            .param("doll_stacks", 1)
            .param("doll_duration", 4)
            .param("doll_share", 50)
            .param("doll_radius", 7)
            .param("doll_echo", 25)
            .param("ult_radius", 15)
            .param("ult_damage", 2)
            .param("ult_duration", 5)
            .param("ult_tick", 1)
            .param("ult_drain", 1)
            .param("ult_share", 40)
            .build();

    private static final String PURPLE = "#A05CDB";
    private static final String LIME = "#C6FF7A";
    /** Sickly lime core, hex purple body, near-black edge. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xC6FF7A, 0x8E3FD1, 0x1C0A26);
    private static final Color LIME_COLOR = PALETTE.core();
    private static final Particle.DustTransition GLOW = PALETTE.glow(1.0f);
    private static final Particle.DustTransition GLOW_SMALL = PALETTE.glow(0.7f);
    private static final Particle.DustTransition FADE = PALETTE.fade(1.0f);
    private static final Particle.DustOptions LIME_DUST = new Particle.DustOptions(LIME_COLOR, 0.7f);
    private static final Particle.DustOptions PURPLE_DUST = PALETTE.dust(0.7f);
    private static final Particle.DustOptions DARK_DUST = new Particle.DustOptions(PALETTE.edge(), 1.2f);

    /** Shared or echoed damage never exceeds this share, so two hexers can never pass pain back and forth forever. */
    private static final double MAX_SHARE = 0.9;
    /** Health changes below this are rounding noise, not damage. */
    private static final double MIN_LOSS = 0.01;

    // passive
    private static final long MARK_PERIOD = 3;
    private static final long SIGIL_TICKS = 18;

    // active
    private static final int MAX_PINS = 3;
    private static final int DOLL_POSE_PERIOD = 2;
    private static final int DOLL_PARTS = 5;
    private static final float DOLL_START_SCALE = 0.15f;
    private static final int DOLL_GROW_TICKS = 6;
    private static final BlockData DOLL_ROBE = Material.PURPLE_WOOL.createBlockData();
    private static final BlockData DOLL_BURLAP = Material.BROWN_WOOL.createBlockData();
    private static final BlockData PIN = Material.LIGHT_GRAY_CONCRETE.createBlockData();
    /** The purple yarn from the doll to its victim. */
    private static final BlockData YARN = Material.PURPLE_WOOL.createBlockData();
    private static final float YARN_WIDTH = 0.03f;

    // ultimate
    private static final int MAX_SKULLS = 10;
    private static final long CHAIN_DRAW_PERIOD = 3;
    private static final int CHAIN_MAX_LINKS = 20;
    /**
     * Links drawn per chain update over all chains together: with many bound enemies each chain gets fewer, longer
     * links, so a big fight does not flood the clients with particles for the whole binding.
     */
    private static final int CHAIN_LINK_BUDGET = 48;
    private static final ItemStack SKULL = ItemStack.of(Material.WITHER_SKELETON_SKULL);
    /** The chains lash out as glowing lime beams. */
    private static final BlockData LASH = Material.VERDANT_FROGLIGHT.createBlockData();
    private static final float LASH_WIDTH = 0.08f;
    private static final long LASH_TICKS = 14;

    /** Curse marks carved into enemies, by entity id. */
    private final Map<UUID, Curse> curses = new HashMap<>();
    /** The voodoo doll in play, if any (a new one replaces it). */
    private @Nullable Doll doll;

    private static final class Curse {
        final LivingEntity victim;
        int stacks;
        long expires;
        @Nullable TextDisplay sigil;

        Curse(LivingEntity victim) {
            this.victim = victim;
        }
    }

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        curses.clear();
        doll = null;
    }

    // ------------------------------------------------------------------ passive: curse marks

    /** Every mark makes the hexer's damage to the victim heavier (melee, arrows, skills, summons). */
    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled()) {
            return;
        }
        int stacks = stacksOn(victim);
        if (stacks > 0) {
            double bonus = Math.max(0, params().num("stack_bonus")) / 100.0;
            event.setDamage(event.getDamage() * (1 + stacks * bonus));
        }
    }

    /** A landed melee or arrow hit carves a new mark. */
    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (ctx().isAbilityDamage() || !ctx().isEnemy(player(), victim)) {
            return;
        }
        DamageCause cause = event.getCause();
        boolean melee = player().equals(event.getDamager())
                && (cause == DamageCause.ENTITY_ATTACK || cause == DamageCause.ENTITY_SWEEP_ATTACK);
        boolean arrow = event.getDamager() instanceof Projectile projectile && player().equals(projectile.getShooter());
        if (melee || arrow) {
            curse(victim, 1);
        }
    }

    /** The victim's live marks, 0 if none (expired marks count as none). */
    private int stacksOn(LivingEntity victim) {
        Curse curse = curses.get(victim.getUniqueId());
        return curse == null || curse.expires <= ctx().currentTick() ? 0 : curse.stacks;
    }

    /** Carves {@code count} marks into {@code victim} (up to the maximum) and refreshes their duration. */
    private void curse(LivingEntity victim, int count) {
        int max = Math.max(0, params().integer("max_stacks"));
        if (max <= 0 || count <= 0) {
            return;
        }
        long now = ctx().currentTick();
        Curse curse = curses.computeIfAbsent(victim.getUniqueId(), id -> new Curse(victim));
        if (curse.expires <= now) {
            curse.stacks = 0;
        }
        int before = curse.stacks;
        curse.stacks = Math.min(max, curse.stacks + count);
        curse.expires = now + params().ticks("stack_duration");
        carveFx(curse, max, curse.stacks > before);
    }

    /** A sigil of crosses pops over the victim's head (lime for each mark, gray for the free slots). */
    private void carveFx(Curse curse, int max, boolean grew) {
        Player owner = player();
        LivingEntity victim = curse.victim;
        Location head = sigilPoint(victim);
        if (curse.sigil != null && curse.sigil.isValid()) {
            curse.sigil.remove();
        }
        String filled = "✠".repeat(curse.stacks);
        String empty = "✠".repeat(Math.max(0, max - curse.stacks));
        TextDisplay sigil = Displays.text(ctx(), owner, head, Text.mm("<b><gradient:" + LIME + ":" + PURPLE + ">"
                + filled + "</gradient><dark_gray>" + empty + "</dark_gray></b>"), 0.5f, SIGIL_TICKS);
        sigil.setTeleportDuration((int) MARK_PERIOD);
        curse.sigil = sigil;
        Displays.animateNextTick(ctx(), owner, sigil, Displays.centered(0, 0.2f, 0, 0.8f, 0.8f, 0.8f, 0, 0, 0), 4);
        ctx().runLater(owner, SIGIL_TICKS - 5, () -> {
            if (sigil.isValid()) {
                Displays.shrinkAway(ctx(), owner, sigil, 4);
            }
        });
        Location body = Fx.body(victim);
        Vfx.cloud(ctx(), body, Particle.WITCH, grew ? 6 : 3, 0.3, 0.02, null, false);
        Vfx.ring(ctx(), body, 0.55, 10, ThreadLocalRandom.current().nextDouble(Math.PI * 2),
                Particle.DUST_COLOR_TRANSITION, GLOW, false);
        if (grew) {
            Fx.sound(body, Sound.ENTITY_WITCH_CELEBRATE, 0.35f, 1.6f + 0.15f * curse.stacks);
            Fx.sound(body, Sound.BLOCK_SOUL_SAND_BREAK, 0.6f, 0.7f + Fx.jitter(0.1f));
        }
    }

    @Override
    public void onTick(long tick) {
        if (tick % MARK_PERIOD != 0 || curses.isEmpty()) {
            return;
        }
        long now = ctx().currentTick();
        Iterator<Curse> iterator = curses.values().iterator();
        while (iterator.hasNext()) {
            Curse curse = iterator.next();
            LivingEntity victim = curse.victim;
            if (curse.expires <= now || !Combat.isTargetable(ctx(), player(), victim)) {
                if (victim.isValid() && !victim.isDead()) {
                    Vfx.cloud(ctx(), Fx.body(victim), Particle.SMOKE, 4, 0.25, 0.02, null, false);
                }
                iterator.remove();
                continue;
            }
            markMotes(curse, tick);
            if (curse.sigil != null) {
                Displays.moveTo(curse.sigil, sigilPoint(victim));
            }
        }
    }

    private static Location sigilPoint(LivingEntity victim) {
        return victim.getLocation().add(0, victim.getHeight() + 0.5, 0);
    }

    /** One mote per mark circling the victim's waist, alternating lime and purple. */
    private void markMotes(Curse curse, long tick) {
        LivingEntity victim = curse.victim;
        Location waist = victim.getLocation().add(0, victim.getHeight() * 0.45, 0);
        double radius = victim.getWidth() * 0.5 + 0.3;
        for (int i = 0; i < curse.stacks; i++) {
            double angle = tick * 0.25 + i * Math.PI * 2 / curse.stacks;
            Location at = waist.clone().add(Math.cos(angle) * radius, Math.sin(tick * 0.2 + i) * 0.15,
                    Math.sin(angle) * radius);
            Vfx.dot(at, Particle.DUST, i % 2 == 0 ? LIME_DUST : PURPLE_DUST, false);
        }
        if (tick % (MARK_PERIOD * 5) == 0) {
            Vfx.dot(waist.clone().add(0, 0.6, 0), Particle.WITCH, null, false);
        }
    }

    // ------------------------------------------------------------------ active: voodoo doll

    @Override
    public ActivationResult onActive() {
        LivingEntity target = ctx().findTarget(player(), params().num("doll_range"));
        if (target == null) {
            return ActivationResult.fail("조준한 곳에 적이 없습니다");
        }
        Doll previous = doll;
        if (previous != null) {
            previous.crumble();
        }
        Doll made = new Doll(target);
        doll = made;
        made.begin();
        return ActivationResult.success();
    }

    /** Polls a linked enemy's health: what it lost since the last poll is the damage it took. */
    private static final class Link {
        final LivingEntity entity;
        double lastHealth;
        @Nullable ItemDisplay skull;

        Link(LivingEntity entity) {
            this.entity = entity;
            this.lastHealth = health(entity);
        }

        /** Health lost since the last {@link #reset()}, 0 if it healed. */
        double loss() {
            return Math.max(0, lastHealth - health(entity));
        }

        void reset() {
            lastHealth = health(entity);
        }

        private static double health(LivingEntity entity) {
            return entity.isDead() || !entity.isValid() ? 0 : entity.getHealth();
        }
    }

    /**
     * The voodoo doll: it floats by the hexer's hand wearing the victim's head; for its duration whatever the victim
     * loses is passed on to the enemies near it (or echoes back into the victim when it is alone).
     */
    private final class Doll implements LongPredicate {

        private final Link link;
        private final long duration;
        private final double share;
        private final double echo;
        private final double radius;
        private final List<Display> parts = new ArrayList<>();
        private final Deque<BlockDisplay> pins = new ArrayDeque<>();
        private @Nullable BlockDisplay yarn;
        private float yaw;
        private float twitch;
        private boolean done;

        Doll(LivingEntity target) {
            this.link = new Link(target);
            this.duration = Math.max(1, params().ticks("doll_duration"));
            this.share = Math.clamp(params().num("doll_share") / 100.0, 0.0, MAX_SHARE);
            this.echo = Math.clamp(params().num("doll_echo") / 100.0, 0.0, MAX_SHARE);
            this.radius = Math.max(0, params().num("doll_radius"));
        }

        void begin() {
            Player owner = player();
            LivingEntity target = link.entity;
            Location anchor = anchor();
            yaw = anchor.getYaw();
            // The pose carries the doll's turn; the entities themselves stay unturned.
            Location place = anchor.clone();
            place.setYaw(0);
            long life = duration + 16;
            for (int i = 0; i < DOLL_PARTS; i++) {
                Transformation start = poseOf(i, yaw, 0, DOLL_START_SCALE);
                parts.add(i == 0 ? Displays.item(ctx(), owner, place, headOf(target), start, life)
                        : Displays.block(ctx(), owner, place, i == 1 ? DOLL_ROBE : DOLL_BURLAP, start, life));
            }
            for (Display part : parts) {
                part.setTeleportDuration(DOLL_POSE_PERIOD);
            }
            Vector delta = Fx.body(target).toVector().subtract(anchor.toVector());
            if (delta.lengthSquared() > 1.0E-4) {
                yarn = Displays.block(ctx(), owner, place, YARN, beamPose(delta.clone().multiply(0.05), YARN_WIDTH),
                        life);
                yarn.setTeleportDuration(DOLL_POSE_PERIOD);
                Displays.animateNextTick(ctx(), owner, yarn, beamPose(delta, YARN_WIDTH), 3);
            }

            // The doll forms from a puff of dark magic, then the first needle goes in.
            Vfx.cloud(ctx(), anchor, Particle.SMOKE, 10, 0.2, 0.02, null, false);
            Vfx.cloud(ctx(), anchor, Particle.WITCH, 8, 0.25, 0.02, null, false);
            Vfx.sphere(ctx(), anchor, 0.45, 16, Particle.DUST_COLOR_TRANSITION, GLOW, false);
            Fx.sound(anchor, Sound.ENTITY_EVOKER_PREPARE_WOLOLO, 0.8f, 1.4f + Fx.jitter(0.05f));
            Fx.sound(anchor, Sound.BLOCK_WOOL_PLACE, 1.0f, 0.8f);
            Vfx.trail(anchor, Fx.body(target), LIME_COLOR, 6, false);
            stab();
            Combat.hit(ctx(), owner, target, params().num("doll_damage"));
            if (Combat.isTargetable(ctx(), owner, target)) {
                curse(target, params().integer("doll_stacks"));
            }
            link.reset();
            owner.sendActionBar(Text.mm("<" + PURPLE + ">✠ 부두 인형</" + PURPLE + "> <white><target></white>",
                    Placeholder.component("target", target.name())));
            Loop.repeat(ctx(), owner, 1, 1, duration, this, this::crumble);
        }

        @Override
        public boolean test(long tick) {
            if (done) {
                return false;
            }
            Player owner = player();
            LivingEntity target = link.entity;
            double loss = link.loss();
            if (loss > MIN_LOSS) {
                pass(loss);
            }
            link.reset();
            if (!Combat.isTargetable(ctx(), owner, target) || !target.getWorld().equals(owner.getWorld())) {
                return false;
            }
            if (tick % DOLL_POSE_PERIOD == 0) {
                pose(tick);
            }
            if (tick % 2 == 0) {
                thread(tick);
            }
            return true;
        }

        /** Passes the victim's pain on: to every other enemy around it, or back into the victim when alone. */
        private void pass(double loss) {
            Player owner = player();
            LivingEntity target = link.entity;
            Location from = Fx.body(target);
            List<LivingEntity> others = new ArrayList<>();
            // Even the blow that kills the victim is passed on.
            for (LivingEntity other : ctx().enemiesNear(owner, target.getLocation(), radius)) {
                if (!other.equals(target)) {
                    others.add(other);
                }
            }
            stab();
            twitch = (ThreadLocalRandom.current().nextBoolean() ? 1 : -1) * 18;
            if (!others.isEmpty()) {
                double amount = loss * share;
                for (LivingEntity other : others) {
                    Location to = Fx.body(other);
                    Vfx.trail(from, to, LIME_COLOR, 5, false);
                    Vfx.line(ctx(), from, to, 2, Particle.DUST, PURPLE_DUST, false);
                    Combat.hit(ctx(), owner, other, amount);
                    Vfx.cloud(ctx(), to, Particle.WITCH, 6, 0.3, 0.03, null, false);
                    Vfx.cloud(ctx(), to, Particle.DUST_COLOR_TRANSITION, 6, 0.3, 0, GLOW, false);
                }
                Fx.sound(from, Sound.ENTITY_WITCH_HURT, 0.6f, 1.6f + Fx.jitter(0.1f));
                Fx.sound(from, Sound.BLOCK_SOUL_SAND_HIT, 1.0f, 0.6f);
            } else if (echo > 0 && Combat.isTargetable(ctx(), owner, target)) {
                Combat.hit(ctx(), owner, target, loss * echo);
                Vfx.cloud(ctx(), from, Particle.DUST_COLOR_TRANSITION, 8, 0.3, 0, GLOW, false);
                Vfx.cloud(ctx(), from, Particle.SMOKE, 4, 0.2, 0.02, null, false);
                Fx.sound(from, Sound.ENTITY_WITCH_HURT, 0.5f, 0.8f + Fx.jitter(0.1f));
            }
        }

        /** Where the doll floats: in front of the hexer's hand, a little to the right, facing the hexer. */
        private Location anchor() {
            Player owner = player();
            Location eye = owner.getEyeLocation();
            Vector facing = eye.getDirection().setY(0);
            if (facing.lengthSquared() < 1.0E-6) {
                facing = new Vector(0, 0, 1);
            }
            facing.normalize();
            Vector right = new Vector(-facing.getZ(), 0, facing.getX());
            Location at = eye.clone().add(facing.multiply(0.95)).add(right.multiply(0.45)).subtract(0, 0.35, 0);
            at.setYaw(eye.getYaw() + 180);
            at.setPitch(0);
            return at;
        }

        /** Moves the doll with the hand, turns it to face the hexer, grows it in and lets a twitch settle. */
        private void pose(long tick) {
            Location anchor = anchor();
            yaw = anchor.getYaw();
            float roll = twitch;
            twitch *= 0.4f;
            float scale = Math.min(1f, DOLL_START_SCALE + (1 - DOLL_START_SCALE) * (tick + DOLL_POSE_PERIOD)
                    / (float) DOLL_GROW_TICKS);
            Location place = anchor.clone();
            place.setYaw(0);
            for (int i = 0; i < parts.size(); i++) {
                Display part = parts.get(i);
                if (!part.isValid()) {
                    continue;
                }
                part.teleport(place);
                Displays.animate(part, poseOf(i, yaw, roll, scale), DOLL_POSE_PERIOD);
            }
            for (BlockDisplay pin : pins) {
                if (pin.isValid()) {
                    pin.teleport(place);
                }
            }
        }

        /** The doll's parts: 0 head, 1 robed body, 2 arms, 3 and 4 legs, all scaled around the anchor. */
        private Transformation poseOf(int index, float yaw, float roll, float scale) {
            float k = scale;
            return switch (index) {
                case 0 -> headPose(yaw, roll, k);
                case 1 -> part(0, -0.02f * k, 0, 0.2f * k, 0.24f * k, 0.1f * k, yaw, roll);
                case 2 -> part(0, 0.06f * k, 0, 0.42f * k, 0.06f * k, 0.06f * k, yaw, roll);
                case 3 -> part(-0.05f * k, -0.22f * k, 0, 0.07f * k, 0.16f * k, 0.07f * k, yaw, roll);
                default -> part(0.05f * k, -0.22f * k, 0, 0.07f * k, 0.16f * k, 0.07f * k, yaw, roll);
            };
        }

        /** A needle stabbed into the doll at a random angle (the oldest one is pulled out past the maximum). */
        private void stab() {
            Player owner = player();
            if (pins.size() >= MAX_PINS) {
                BlockDisplay oldest = pins.pollFirst();
                if (oldest != null && oldest.isValid()) {
                    oldest.remove();
                }
            }
            Location anchor = anchor();
            Location place = anchor.clone();
            place.setYaw(0);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            float pinYaw = yaw + random.nextFloat(-50, 50);
            float pinPitch = random.nextFloat(-35, 35);
            float height = random.nextFloat(-0.1f, 0.08f);
            BlockDisplay pin = Displays.block(ctx(), owner, place, PIN,
                    needle(height, pinYaw, pinPitch, 0.32f, 0.018f), duration + 16);
            pin.setTeleportDuration(DOLL_POSE_PERIOD);
            pins.addLast(pin);
            Displays.animateNextTick(ctx(), owner, pin, needle(height, pinYaw, pinPitch, 0.12f, 0.018f), 2);
            Location point = anchor.clone().add(0, height, 0);
            Vfx.dot(point, Particle.DUST, LIME_DUST, false);
            Vfx.cloud(ctx(), point, Particle.CRIT, 3, 0.05, 0.1, null, false);
            Fx.sound(point, Sound.ENTITY_ARROW_HIT, 0.7f, 1.6f + Fx.jitter(0.15f));
        }

        /** The lime thread from the doll to the victim. */
        private void thread(long tick) {
            Location from = anchor();
            Location to = Fx.body(link.entity);
            BlockDisplay line = yarn;
            Vector delta = to.toVector().subtract(from.toVector());
            if (line != null && line.isValid() && tick > 2 && delta.lengthSquared() > 1.0E-4) {
                Location place = from.clone();
                place.setYaw(0);
                place.setPitch(0);
                line.teleport(place);
                Displays.animate(line, beamPose(delta, YARN_WIDTH), DOLL_POSE_PERIOD);
            }
            Vfx.trail(from, to, LIME_COLOR, 6, false);
            if (tick % 6 == 0) {
                Vfx.line(ctx(), from, to, 1.2, Particle.DUST, PURPLE_DUST, false);
            }
            Vfx.dot(from.clone().add(0, -0.35, 0), Particle.DUST, DARK_DUST, false);
        }

        /** The link ends: the doll unravels into smoke. */
        void crumble() {
            if (done) {
                return;
            }
            done = true;
            if (doll == this) {
                doll = null;
            }
            Player owner = player();
            Location at = parts.isEmpty() || !parts.getFirst().isValid() ? anchor() : parts.getFirst().getLocation();
            Vfx.cloud(ctx(), at, Particle.SMOKE, 10, 0.2, 0.03, null, false);
            Vfx.cloud(ctx(), at, Particle.DUST, 8, 0.2, 0, PURPLE_DUST, false);
            Fx.sound(at, Sound.BLOCK_WOOL_BREAK, 1.0f, 0.7f);
            Fx.sound(at, Sound.ENTITY_EVOKER_FANGS_ATTACK, 0.4f, 1.5f);
            for (Display part : parts) {
                if (part.isValid()) {
                    Displays.shrinkAway(ctx(), owner, part, 5);
                }
            }
            for (BlockDisplay pin : pins) {
                if (pin.isValid()) {
                    Displays.shrinkAway(ctx(), owner, pin, 3);
                }
            }
            pins.clear();
            if (yarn != null && yarn.isValid()) {
                // The yarn snaps and falls slack.
                Displays.shrinkAway(ctx(), owner, yarn, 4);
            }
        }
    }

    /** The victim's own head for the doll: a player's skin, a mannequin's profile, otherwise a wither skull. */
    private static ItemStack headOf(LivingEntity target) {
        ItemStack head = ItemStack.of(Material.PLAYER_HEAD);
        if (target instanceof Player player) {
            head.setData(DataComponentTypes.PROFILE, ResolvableProfile.resolvableProfile(player.getPlayerProfile()));
        } else if (target instanceof Mannequin mannequin) {
            head.setData(DataComponentTypes.PROFILE, mannequin.getProfile());
        } else {
            return ItemStack.of(Material.WITHER_SKELETON_SKULL);
        }
        return head;
    }


    // ------------------------------------------------------------------ ultimate: soul chains

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        double radius = Math.max(1, params().num("ult_radius"));
        List<LivingEntity> bound = ctx().enemiesNear(owner, owner.getLocation(), radius);
        if (bound.isEmpty()) {
            return ActivationResult.fail("반경 " + AbilityInfo.formatNumber(radius) + "블록 안에 적이 없습니다");
        }
        new Chains(bound).begin();
        return ActivationResult.success();
    }

    /**
     * Soul chains: every bound enemy is slowed and drained, and whatever one of them loses is shared with all the
     * others. The chains run around the bound enemies in a loop (or to the hexer's hand when only one is bound).
     */
    private final class Chains implements LongPredicate {

        private final List<Link> links = new ArrayList<>();
        private final long duration;
        private final long drainEvery;
        private final double share;

        Chains(List<LivingEntity> bound) {
            Location center = player().getLocation();
            List<LivingEntity> sorted = new ArrayList<>(bound);
            // Around the hexer in angle order, so the loop of chains never crosses itself.
            sorted.sort(Comparator.comparingDouble(entity -> Math.atan2(entity.getLocation().getZ() - center.getZ(),
                    entity.getLocation().getX() - center.getX())));
            for (LivingEntity entity : sorted) {
                links.add(new Link(entity));
            }
            this.duration = Math.max(1, params().ticks("ult_duration"));
            this.drainEvery = Math.max(1, params().ticks("ult_tick"));
            this.share = Math.clamp(params().num("ult_share") / 100.0, 0.0, MAX_SHARE);
        }

        void begin() {
            Player owner = player();
            Location feet = owner.getLocation().add(0, 0.1, 0);
            Vfx.shockwave(ctx(), owner, feet, 0.4, 3.0, 6, Particle.DUST_COLOR_TRANSITION, GLOW, true);
            Vfx.ring(ctx(), feet, 1.4, 20, 0, Particle.WITCH, null, true);
            Vfx.helix(ctx(), owner.getLocation(), 0.8, 2.4, 1.5, 3, 14, 0, Particle.DUST_COLOR_TRANSITION, GLOW, true);
            Fx.sound(feet, Sound.ENTITY_WITHER_SPAWN, 0.5f, 1.6f);
            Fx.sound(feet, Sound.BLOCK_CHAIN_PLACE, 1.4f, 0.6f);
            Fx.sound(feet, Sound.ENTITY_EVOKER_CAST_SPELL, 1.0f, 0.7f + Fx.jitter(0.05f));
            Fx.sound(feet, Sound.PARTICLE_SOUL_ESCAPE, 1.5f, 0.8f);

            double damage = params().num("ult_damage");
            int slow = (int) Math.min(Integer.MAX_VALUE, duration);
            Location hand = owner.getEyeLocation().subtract(0, 0.3, 0);
            int skulls = 0;
            for (Link link : links) {
                LivingEntity entity = link.entity;
                Vfx.trail(hand, Fx.body(entity), LIME_COLOR, 6, true);
                if (skulls < MAX_SKULLS) {
                    lash(owner, hand, Fx.body(entity));
                }
                Combat.hit(ctx(), owner, entity, damage);
                if (!Combat.isTargetable(ctx(), owner, entity)) {
                    continue;
                }
                entity.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slow, 0, false, true, true));
                Location body = Fx.body(entity);
                Vfx.cloud(ctx(), body, Particle.SOUL, 6, 0.3, 0.03, null, false);
                Vfx.ring(ctx(), body, 0.6, 12, 0, Particle.DUST_COLOR_TRANSITION, GLOW, false);
                Fx.sound(body, Sound.BLOCK_CHAIN_HIT, 1.0f, 0.8f + Fx.jitter(0.1f));
                if (entity instanceof Player victim) {
                    victim.playSound(victim, Sound.PARTICLE_SOUL_ESCAPE, SoundCategory.PLAYERS, 1.0f, 0.6f);
                }
                if (skulls++ < MAX_SKULLS) {
                    ItemDisplay skull = Displays.item(ctx(), owner, skullPoint(entity, 0), SKULL,
                            Displays.centered(0, 0, 0, 0.05f, 0.05f, 0.05f, 0, 0, 0), duration + 12);
                    skull.setTeleportDuration(1);
                    Displays.animateNextTick(ctx(), owner, skull,
                            Displays.centered(0, 0, 0, 0.45f, 0.45f, 0.45f, 0, 0, 0), 4);
                    link.skull = skull;
                }
            }
            for (Link link : links) {
                link.reset();
            }
            owner.sendActionBar(Text.mm("<" + PURPLE + ">⛓ 영혼 사슬</" + PURPLE + "> <white>" + links.size()
                    + "명 결박</white>"));
            Loop.repeat(ctx(), owner, 1, 1, duration, this, this::end);
        }

        /** A chain of light lashing out from the hand to a victim: it shoots out, then thins away. */
        private void lash(Player owner, Location hand, Location body) {
            Vector delta = body.toVector().subtract(hand.toVector());
            if (delta.lengthSquared() < 1.0E-4) {
                return;
            }
            Location place = hand.clone();
            place.setYaw(0);
            place.setPitch(0);
            BlockDisplay beam = Displays.block(ctx(), owner, place, LASH, beamPose(delta.clone().multiply(0.05),
                    LASH_WIDTH), LASH_TICKS);
            Displays.outline(beam, PALETTE.body());
            Displays.animateNextTick(ctx(), owner, beam, beamPose(delta, LASH_WIDTH), 3);
            ctx().runLater(owner, 5, () -> {
                if (beam.isValid()) {
                    Displays.animate(beam, beamPose(delta, 0.001f), (int) LASH_TICKS - 6);
                }
            });
        }

        @Override
        public boolean test(long tick) {
            Player owner = player();
            // 1. What each bound enemy lost since the last tick (to anything: melee, skills, allies, the border...).
            double[] losses = new double[links.size()];
            for (int i = 0; i < links.size(); i++) {
                losses[i] = links.get(i).loss();
            }
            // 2. Shared with every other bound enemy. Shared damage is not shared again: everyone is re-read below.
            for (int i = 0; i < links.size(); i++) {
                if (losses[i] <= MIN_LOSS || share <= 0) {
                    continue;
                }
                Link source = links.get(i);
                for (int j = 0; j < links.size(); j++) {
                    Link other = links.get(j);
                    if (j == i || !Combat.isTargetable(ctx(), owner, other.entity)) {
                        continue;
                    }
                    Combat.hit(ctx(), owner, other.entity, losses[i] * share);
                    pulse(source, other);
                }
            }
            // 3. The drain.
            boolean drain = (tick + 1) % drainEvery == 0;
            double drainDamage = params().num("ult_drain");
            for (Link link : links) {
                if (drain && Combat.isTargetable(ctx(), owner, link.entity)) {
                    Combat.hit(ctx(), owner, link.entity, drainDamage);
                    drainFx(link);
                }
                link.reset();
            }
            // 4. Drop the dead and released, then draw.
            links.removeIf(link -> {
                if (Combat.isTargetable(ctx(), owner, link.entity) && link.entity.getWorld().equals(owner.getWorld())) {
                    return false;
                }
                if (link.skull != null && link.skull.isValid()) {
                    Displays.shrinkAway(ctx(), owner, link.skull, 4);
                }
                return true;
            });
            for (Link link : links) {
                if (link.skull != null && link.skull.isValid()) {
                    link.skull.teleport(skullPoint(link.entity, tick));
                }
            }
            if (tick % CHAIN_DRAW_PERIOD == 0) {
                drawChains(tick);
            }
            return !links.isEmpty();
        }

        private Location skullPoint(LivingEntity entity, long tick) {
            Location at = entity.getLocation().add(0, entity.getHeight() + 0.55 + Math.sin(tick * 0.25) * 0.08, 0);
            at.setYaw((float) (tick * 6 % 360));
            at.setPitch(0);
            return at;
        }

        /**
         * The chains: a loop through the skulls (or from the hexer's hand to a lone victim), plus each skull's tether.
         */
        private void drawChains(long tick) {
            Player owner = player();
            if (links.size() == 1) {
                Link only = links.getFirst();
                chain(owner.getEyeLocation().subtract(0, 0.4, 0), anchorOf(only, tick), tick, CHAIN_MAX_LINKS);
            } else {
                int edges = links.size() == 2 ? 1 : links.size();
                int perEdge = Math.clamp(CHAIN_LINK_BUDGET / edges, 2, CHAIN_MAX_LINKS);
                for (int i = 0; i < edges; i++) {
                    chain(anchorOf(links.get(i), tick), anchorOf(links.get((i + 1) % links.size()), tick), tick,
                            perEdge);
                }
            }
            for (Link link : links) {
                Location skull = anchorOf(link, tick);
                Vfx.line(ctx(), skull, Fx.body(link.entity), 3, Particle.DUST, DARK_DUST, false);
            }
        }

        private Location anchorOf(Link link, long tick) {
            return skullPoint(link.entity, tick).subtract(0, 0.1, 0);
        }

        /** Links of light: small loops alternating between two planes along the line, lime and purple. */
        private void chain(Location from, Location to, long tick, int maxLinks) {
            if (!from.getWorld().equals(to.getWorld())) {
                return;
            }
            Vector axis = to.toVector().subtract(from.toVector());
            double length = axis.length();
            if (length < 0.3) {
                return;
            }
            axis.multiply(1 / length);
            Vector helper = Math.abs(axis.getY()) > 0.95 ? new Vector(1, 0, 0) : new Vector(0, 1, 0);
            Vector u = axis.clone().crossProduct(helper).normalize();
            Vector v = axis.clone().crossProduct(u).normalize();
            int count = (int) Math.min(maxLinks, Math.max(2, Math.round(length / 0.45)));
            double spacing = length / count;
            int points = Vfx.n(ctx(), 4);
            World world = from.getWorld();
            // A gentle sag, like a hanging chain.
            double sag = Math.min(0.6, length * 0.06);
            for (int k = 0; k < count; k++) {
                double t = (k + 0.5) / count;
                Location center = from.clone().add(axis.clone().multiply(t * length))
                        .subtract(0, Math.sin(t * Math.PI) * sag, 0);
                Vector side = (k + tick / CHAIN_DRAW_PERIOD) % 2 == 0 ? u : v;
                Particle.DustOptions dust = k % 2 == 0 ? LIME_DUST : PURPLE_DUST;
                for (int p = 0; p < points; p++) {
                    double angle = p * Math.PI * 2 / points;
                    Vector offset = axis.clone().multiply(Math.cos(angle) * spacing * 0.42)
                            .add(side.clone().multiply(Math.sin(angle) * 0.07));
                    world.spawnParticle(Particle.DUST, center.getX() + offset.getX(), center.getY() + offset.getY(),
                            center.getZ() + offset.getZ(), 1, 0, 0, 0, 0, dust, true);
                }
            }
        }

        /** Pain flowing along the chains from one bound enemy to another. */
        private void pulse(Link source, Link other) {
            Location from = Fx.body(source.entity);
            Location to = Fx.body(other.entity);
            Vfx.trail(from, to, LIME_COLOR, 6, true);
            Vfx.trail(from.clone().add(0, 0.2, 0), to, PALETTE.body(), 8, true);
            Vfx.cloud(ctx(), to, Particle.SOUL, 3, 0.25, 0.02, null, false);
            Fx.sound(to, Sound.BLOCK_CHAIN_HIT, 0.8f, 1.3f + Fx.jitter(0.15f));
            ItemDisplay skull = other.skull;
            if (skull != null && skull.isValid()) {
                Displays.animate(skull, Displays.centered(0, 0, 0, 0.62f, 0.62f, 0.62f, 0, 0, 0), 2);
                ctx().runLater(player(), 3, () -> {
                    if (skull.isValid()) {
                        Displays.animate(skull, Displays.centered(0, 0, 0, 0.45f, 0.45f, 0.45f, 0, 0, 0), 4);
                    }
                });
            }
        }

        /** The drain: a wisp of the soul pulled up into the skull. */
        private void drainFx(Link link) {
            Location body = Fx.body(link.entity);
            Location skull = anchorOf(link, 0);
            Vfx.trail(body, skull, PALETTE.body(), 8, false);
            Vfx.cloud(ctx(), body, Particle.DUST_COLOR_TRANSITION, 5, 0.3, 0, FADE, false);
            Fx.sound(body, Sound.PARTICLE_SOUL_ESCAPE, 0.8f, 0.8f + Fx.jitter(0.1f));
        }

        /** The chains break: every skull bursts into souls. */
        void end() {
            Player owner = player();
            for (Link link : links) {
                if (link.skull != null && link.skull.isValid()) {
                    Location at = link.skull.getLocation();
                    Vfx.cloud(ctx(), at, Particle.SOUL, 6, 0.2, 0.04, null, false);
                    Vfx.cloud(ctx(), at, Particle.DUST, 8, 0.25, 0, LIME_DUST, false);
                    Fx.sound(at, Sound.BLOCK_CHAIN_BREAK, 1.0f, 0.9f + Fx.jitter(0.1f));
                    Displays.shrinkAway(ctx(), owner, link.skull, 4);
                }
            }
        }
    }

    // ------------------------------------------------------------------ display helpers

    /**
     * A box of {@code (sx, sy, sz)} centered on {@code (ox, oy, oz)} in the doll's frame, the frame turned by
     * {@code yawDeg} and rolled by {@code rollDeg} around the display's origin.
     */
    private static Transformation part(float ox, float oy, float oz, float sx, float sy, float sz, float yawDeg,
                                       float rollDeg) {
        Quaternionf rotation = frame(yawDeg, rollDeg);
        Vector3f translation = rotation.transform(new Vector3f(ox - sx / 2, oy - sy / 2, oz - sz / 2));
        return new Transformation(translation, rotation, new Vector3f(sx, sy, sz), new Quaternionf());
    }

    private static Transformation headPose(float yawDeg, float rollDeg, float scale) {
        Quaternionf rotation = frame(yawDeg, rollDeg);
        Vector3f translation = rotation.transform(new Vector3f(0, 0.2f * scale, 0));
        return new Transformation(translation, rotation, new Vector3f(0.36f * scale), new Quaternionf());
    }

    /** A thin needle sticking out of the doll's body at {@code height}, pushed in to {@code out} blocks. */
    private static Transformation needle(float height, float yawDeg, float pitchDeg, float out, float width) {
        Quaternionf rotation = new Quaternionf().rotateY((float) Math.toRadians(-yawDeg))
                .rotateX((float) Math.toRadians(pitchDeg));
        float length = 0.26f;
        Vector3f translation = rotation.transform(new Vector3f(-width / 2, -width / 2, out - length)).add(0, height, 0);
        return new Transformation(translation, rotation, new Vector3f(width, width, length), new Quaternionf());
    }

    /** A thin box of the given width from the block display's origin along {@code delta}. */
    private static Transformation beamPose(Vector delta, float width) {
        Vector3f direction = new Vector3f((float) delta.getX(), (float) delta.getY(), (float) delta.getZ());
        float length = direction.length();
        Quaternionf rotation = new Quaternionf().rotationTo(new Vector3f(0, 0, 1), direction.normalize());
        Vector3f translation = rotation.transform(new Vector3f(-width / 2, -width / 2, 0));
        return new Transformation(translation, rotation, new Vector3f(width, width, length), new Quaternionf());
    }

    private static Quaternionf frame(float yawDeg, float rollDeg) {
        return new Quaternionf().rotateY((float) Math.toRadians(-yawDeg)).rotateZ((float) Math.toRadians(rollDeg));
    }
}
