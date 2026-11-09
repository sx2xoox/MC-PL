package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Body;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
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
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
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
 * 히드라 — the many-headed serpent of the swamp. The more it is hurt, the more heads sprout from its shoulders (up to
 * three), each spitting venom darts at the nearest enemy; the active sends every head at a different enemy for a
 * venomous bite, and as the ultimate nine giant heads burst out of the ground around it and spit globs of venom one
 * after another at the enemies around, each landing spot marked in advance.
 *
 * <p>Look (swamp green, venom yellow): the heads are mossy necks with dark green heads on the hydra's back and
 * shoulders, swaying, with yellow eyes glinting; a new one bursts out with a squelch, a lost one withers back. Darts are
 * yellow-cored green streaks; a bite is a neck lashing out to the victim and snapping back. The nine heads rise out of
 * pillars of mud, rear back before each spit and lunge as they spit; each glob arcs through the air towards a pulsing
 * green ring on the ground and bursts into a bubbling splash.
 */
public final class HydraAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("hydra", "히드라", Material.VINE)
            .color(TextColor.color(0x8DC63F))
            .difficulty(2)
            .passive("재생하는 머리",
                    "체력이 최대 체력의 {head_threshold_1}%, {head_threshold_2}% 아래로 떨어질 때마다 어깨에서 머리가 "
                            + "하나씩 더 돋아납니다(최대 3개, 체력을 회복하면 다시 움츠러듭니다). 머리마다 {spit_interval}초마다 "
                            + "{spit_range}블록 안의 가장 가까운 적에게 독침을 뱉어 {spit_damage} 피해와 {spit_poison}초간 독을 "
                            + "겁니다.")
            .active("머리 물기",
                    "머리 수만큼 반경 {bite_range}블록 안의 서로 다른 적을 한꺼번에 물어 {bite_damage} 피해와 "
                            + "{bite_poison}초간 독을 겁니다. 물 적이 머리보다 적으면 남은 머리들이 가장 가까운 적에게 함께 "
                            + "달려들어 한 번 더 물어 {extra_bite_damage} 피해를 줍니다.",
                    8)
            .ultimate("아홉 머리",
                    "내 머리들이 땅속으로 파고들어 {ult_duration}초간 주위 땅에서 거대한 머리 9개로 솟아오르고, 반경 "
                            + "{ult_radius}블록 안에 보이는 적에게 차례로 독액을 뱉습니다. 독액이 떨어질 곳은 미리 표시되고, "
                            + "반경 {glob_radius}블록 안의 적에게 {glob_damage} 피해와 {glob_poison}초간 독을 줍니다(적마다 최대 "
                            + "{glob_max_hits}회). 독액은 벽과 지붕에 막힙니다.")
            .param("head_threshold_1", 70)
            .param("head_threshold_2", 40)
            .param("spit_interval", 5)
            .param("spit_range", 10)
            .param("spit_damage", 1)
            .param("spit_poison", 1.5)
            .param("bite_range", 6)
            .param("bite_damage", 4)
            .param("bite_poison", 3)
            .param("extra_bite_damage", 1.5)
            .param("ult_duration", 5)
            .param("ult_radius", 14)
            .param("glob_radius", 1.6)
            .param("glob_damage", 2)
            .param("glob_poison", 2)
            .param("glob_max_hits", 5)
            .build();

    private static final String SWAMP_TAG = "#8DC63F";
    private static final String VENOM_TAG = "#E4F25C";

    // ------------------------------------------------------------------ look
    /** Venom yellow, swamp green, the dark of deep water. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xE4F25C, 0x6FA832, 0x27401A);
    private static final Color VENOM = Color.fromRGB(0xE4F25C);
    private static final Color SWAMP = Color.fromRGB(0x6FA832);
    private static final Particle.DustOptions VENOM_DUST = new Particle.DustOptions(VENOM, 0.7f);
    private static final Particle.DustOptions SWAMP_DUST = new Particle.DustOptions(SWAMP, 1.0f);
    private static final Particle.DustTransition VENOM_GLOW = PALETTE.glow(1.0f);
    private static final Particle.DustTransition MIRE = PALETTE.fade(1.2f);
    private static final Particle.DustOptions EYE = new Particle.DustOptions(Color.fromRGB(0xFFE53B), 0.45f);

    /** Block types of the visuals; block data is created on use (it needs a running server). */
    private static final Material NECK = Material.MOSS_BLOCK;
    private static final Material HEAD = Material.GREEN_TERRACOTTA;
    private static final Material MUD = Material.MUD;
    private static final Material GLOB = Material.SLIME_BLOCK;

    private static final int MAX_DISPLAYS = 24;

    // passive: heads
    private static final int MAX_HEADS = 3;
    private static final long HEAD_CHECK_PERIOD = 5;
    private static final int HEAD_GROW_TICKS = 8;
    /** Where each head's neck leaves the body and where its head sits, on a 1.8 block body (+Z front, +X left). */
    private static final Vector3f[] NECK_BASE = {
            new Vector3f(0, 1.4f, -0.2f), new Vector3f(0.27f, 1.32f, -0.12f), new Vector3f(-0.27f, 1.32f, -0.12f)};
    private static final Vector3f[] NECK_TOP = {
            new Vector3f(0, 2.12f, -0.45f), new Vector3f(0.72f, 1.92f, -0.3f), new Vector3f(-0.72f, 1.92f, -0.3f)};
    private static final float NECK_WIDTH = 0.15f;
    private static final Vector3f HEAD_SIZE = new Vector3f(0.28f, 0.24f, 0.42f);
    private static final long LOOK_TICKS = 10;
    private static final double DART_SPEED = 1.4;
    private static final double DART_PAD = 0.25;

    // active: bite
    private static final int LASH_TICKS = 3;
    private static final int HOLD_TICKS = 2;
    private static final int RETRACT_TICKS = 4;

    // ultimate: nine heads
    private static final int ULT_HEADS = 9;
    private static final double RING_RADIUS = 2.6;
    private static final float ULT_NECK_WIDTH = 0.42f;
    private static final float ULT_NECK_HEIGHT = 3.0f;
    private static final Vector3f ULT_HEAD_SIZE = new Vector3f(0.62f, 0.5f, 0.9f);
    private static final long RISE_STAGGER = 2;
    private static final int RISE_TICKS = 6;
    private static final long FIRST_SPIT = 20;
    private static final long SPIT_GAP = 6;
    private static final int REAR_TICKS = 3;
    private static final int GLOB_FLIGHT = 12;
    private static final double GLOB_ARC = 3.0;
    private static final int SINK_TICKS = 8;
    private static final float GLOB_SIZE = 0.42f;
    /** Room a glob keeps below a ceiling. */
    private static final double CEILING_GAP = 0.4;

    // ------------------------------------------------------------------ state

    private final List<Display> live = new ArrayList<>();
    private final Head[] heads = new Head[MAX_HEADS];
    private int count;
    /** The nine heads standing: the hydra's own heads have dived into the ground to become them. */
    private @Nullable Brood brood;

    /** One head on the hydra's body: its displays, when it next spits and where it looks. */
    private final class Head {
        final int slot;
        @Nullable BlockDisplay neck;
        @Nullable BlockDisplay skull;
        long nextSpit;
        long lookUntil = Long.MIN_VALUE / 2;
        Vector look = new Vector(0, 0, 1);
        long bornTick;
        boolean biting;

        Head(int slot) {
            this.slot = slot;
        }

        void remove(boolean wither) {
            for (BlockDisplay part : new BlockDisplay[] {neck, skull}) {
                if (part != null && part.isValid()) {
                    if (wither) {
                        Displays.shrinkAway(ctx(), player(), part, 6);
                    } else {
                        part.remove();
                    }
                }
            }
            neck = null;
            skull = null;
        }
    }

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onGrant() {
        updateHeads(true);
    }

    @Override
    public void onRevoke() {
        for (int i = 0; i < heads.length; i++) {
            heads[i] = null;
        }
        count = 0;
        brood = null;
        live.clear();
    }

    // ------------------------------------------------------------------ passive: regrowing heads

    /** How many heads the hydra's health calls for: one, plus one under each threshold. */
    private int wantedHeads() {
        Player owner = player();
        double share = owner.getHealth() / Combat.maxHealth(owner) * 100;
        int wanted = 1;
        if (share <= params().num("head_threshold_1")) {
            wanted++;
        }
        if (share <= params().num("head_threshold_2")) {
            wanted++;
        }
        return Math.min(MAX_HEADS, wanted);
    }

    /** Grows or withers heads to match the health. */
    private void updateHeads(boolean silent) {
        int wanted = wantedHeads();
        if (wanted == count) {
            return;
        }
        Player owner = player();
        long now = ctx().currentTick();
        long interval = Math.max(10, params().ticks("spit_interval"));
        while (count < wanted) {
            Head head = new Head(count);
            head.bornTick = now;
            head.nextSpit = now + interval / 2 + count * interval / 3;
            if (brood == null) {
                dress(owner, head); // under the ground while the nine heads stand: it surfaces with the others
            }
            heads[count] = head;
            count++;
            if (!silent && brood == null) {
                sprout(owner, head);
            }
        }
        while (count > wanted) {
            count--;
            Head head = heads[count];
            heads[count] = null;
            if (head != null) {
                head.remove(true);
                if (!silent) {
                    Location shoulder = bodyPoint(owner, NECK_TOP[head.slot]);
                    Vfx.cloud(ctx(), shoulder, Particle.DUST, 8, 0.25, 0, SWAMP_DUST, false);
                    Fx.sound(shoulder, Sound.ENTITY_SLIME_SQUISH_SMALL, 0.8f, 0.6f);
                }
            }
        }
        if (!silent) {
            owner.sendActionBar(Text.mm("<" + SWAMP_TAG + ">🐍 히드라의 머리</" + SWAMP_TAG + "> <white>×" + count
                    + "</white>"));
        }
    }

    /** The neck and head displays of {@code head}, grown from nothing (if the display budget allows). */
    private void dress(Player owner, Head head) {
        if (!reserve(2)) {
            return;
        }
        Location feet = owner.getLocation();
        head.neck = keep(Displays.block(ctx(), owner, feet, NECK.createBlockData(), neckPose(head, owner, 0.02f), 0));
        head.skull = keep(Displays.block(ctx(), owner, feet, HEAD.createBlockData(), headPose(head, owner, 0.02f),
                0));
        head.neck.setTeleportDuration(1);
        head.skull.setTeleportDuration(1);
    }

    /** The hydra's heads dive into the ground (they rise again as the nine): mud spurts from its shoulders. */
    private void submerge(Player owner) {
        for (int i = 0; i < count; i++) {
            Head head = heads[i];
            if (head == null) {
                continue;
            }
            head.remove(true);
            Location shoulder = bodyPoint(owner, NECK_TOP[head.slot]);
            owner.getWorld().spawnParticle(Particle.BLOCK, shoulder, Vfx.n(ctx(), 6), 0.2, 0.2, 0.2, 0,
                    MUD.createBlockData());
        }
        Fx.sound(owner.getLocation(), Sound.BLOCK_MUD_PLACE, 1.0f, 0.6f);
    }

    /** The nine heads sank: the hydra's own heads grow back out of its shoulders and spit again. */
    private void resurface(Player owner) {
        long now = ctx().currentTick();
        long interval = Math.max(10, params().ticks("spit_interval"));
        for (int i = 0; i < count; i++) {
            Head head = heads[i];
            if (head == null) {
                continue;
            }
            head.bornTick = now;
            head.nextSpit = Math.max(head.nextSpit, now + interval / 2 + i * interval / 3);
            if (head.neck == null) {
                dress(owner, head);
            }
            sprout(owner, head);
        }
    }

    /** A new head bursts out of the shoulder. */
    private void sprout(Player owner, Head head) {
        Location shoulder = bodyPoint(owner, NECK_BASE[head.slot]);
        Location top = bodyPoint(owner, NECK_TOP[head.slot]);
        Vfx.cloud(ctx(), top, Particle.ITEM_SLIME, 8, 0.25, 0.05, null, false);
        Vfx.cloud(ctx(), shoulder, Particle.DUST_COLOR_TRANSITION, 10, 0.2, 0, VENOM_GLOW, false);
        Vfx.line(ctx(), shoulder, top, 6, Particle.DUST, SWAMP_DUST, false);
        Fx.sound(top, Sound.ENTITY_SLIME_SQUISH, 1.0f, 0.6f + Fx.jitter(0.05f));
        Fx.sound(top, Sound.ENTITY_CAT_HISS, 0.8f, 0.5f + Fx.jitter(0.05f));
        Fx.sound(top, Sound.BLOCK_ROOTED_DIRT_BREAK, 0.8f, 0.7f);
    }

    @Override
    public void onTick(long tick) {
        Player owner = player();
        if (tick % HEAD_CHECK_PERIOD == 0) {
            updateHeads(tick == 0);
        }
        long now = ctx().currentTick();
        if (brood != null) {
            return; // the heads are under the ground, risen as the nine
        }
        for (int i = 0; i < count; i++) {
            Head head = heads[i];
            if (head == null) {
                continue;
            }
            if (!head.biting) {
                pose(owner, head, now);
            }
            if (now >= head.nextSpit) {
                head.nextSpit = now + Math.max(10, params().ticks("spit_interval"));
                spit(owner, head);
            }
        }
        if (tick % 15 == 7 && count > 0) {
            Head head = heads[ThreadLocalRandom.current().nextInt(count)];
            if (head != null && !head.biting) {
                // Yellow eyes glint in the heads now and then.
                Location eyes = mouth(owner, head).add(0, 0.08, 0);
                Vfx.dot(eyes, Particle.DUST, EYE, false);
            }
        }
    }

    /** Sways a head and keeps it on the body (turned to its look while it spits). */
    private void pose(Player owner, Head head, long now) {
        float grow = Math.min(1f, (now - head.bornTick + 1f) / HEAD_GROW_TICKS);
        Location feet = owner.getLocation();
        for (BlockDisplay part : new BlockDisplay[] {head.neck, head.skull}) {
            if (part != null && part.isValid() && part.getWorld().equals(owner.getWorld())) {
                Displays.moveTo(part, feet);
            }
        }
        if (head.neck != null && head.neck.isValid()) {
            Displays.animate(head.neck, neckPose(head, owner, grow), 2);
        }
        if (head.skull != null && head.skull.isValid()) {
            Displays.animate(head.skull, headPose(head, owner, grow), 2);
        }
    }

    /** The swaying top of a head's neck, relative to the owner's feet. */
    private Vector3f neckTop(Head head, Player owner, float grow) {
        double phase = ctx().currentTick() * 0.13 + head.slot * 2.1;
        Vector3f local = new Vector3f(NECK_TOP[head.slot]).add((float) Math.sin(phase) * 0.05f,
                (float) Math.cos(phase * 0.8) * 0.04f, 0);
        Vector3f base = new Vector3f(NECK_BASE[head.slot]);
        Vector3f top = base.add(new Vector3f(local).sub(NECK_BASE[head.slot]).mul(grow));
        return yawTurn(owner.getBodyYaw()).transform(top.mul(bodyScale(owner)));
    }

    private Transformation neckPose(Head head, Player owner, float grow) {
        float scale = (float) Body.scaleOf(owner);
        Vector3f base = yawTurn(owner.getBodyYaw()).transform(new Vector3f(NECK_BASE[head.slot])
                .mul(bodyScale(owner)));
        Vector3f top = neckTop(head, owner, grow);
        return beam(base, new Vector3f(top).sub(base), NECK_WIDTH * scale * Math.max(0.1f, grow));
    }

    /** The head on top of the neck, facing the body's way (or its target while it spits). */
    private Transformation headPose(Head head, Player owner, float grow) {
        float scale = (float) Body.scaleOf(owner) * Math.max(0.02f, grow);
        Vector3f top = neckTop(head, owner, grow);
        Quaternionf facing;
        if (ctx().currentTick() < head.lookUntil) {
            facing = pointing(toVector3f(head.look));
        } else {
            facing = yawTurn(owner.getBodyYaw());
        }
        Vector3f center = new Vector3f(top).add(facing.transform(new Vector3f(0, 0.04f, 0.1f).mul(scale)));
        return box(center, new Vector3f(HEAD_SIZE).mul(scale), facing);
    }

    /** The head's mouth, in the world. */
    private Location mouth(Player owner, Head head) {
        Vector3f top = neckTop(head, owner, 1f);
        Vector3f forward = ctx().currentTick() < head.lookUntil ? toVector3f(head.look).normalize()
                : yawTurn(owner.getBodyYaw()).transform(new Vector3f(0, 0, 1));
        return owner.getLocation().add(top.x() + forward.x() * 0.3, top.y() + forward.y() * 0.3,
                top.z() + forward.z() * 0.3);
    }

    // ------------------------------------------------------------------ passive: venom darts

    /** The head spits a dart at the nearest visible enemy in range (nothing if there is none). */
    private void spit(Player owner, Head head) {
        Location from = mouth(owner, head);
        double range = Math.max(1, params().num("spit_range"));
        for (LivingEntity enemy : ctx().enemiesNear(owner, from, range)) {
            if (!seen(owner, enemy)) {
                continue;
            }
            Location target = Fx.body(enemy);
            if (!Spots.clearPath(from, target)) {
                continue;
            }
            Vector direction = target.toVector().subtract(from.toVector());
            if (direction.lengthSquared() < 1.0E-6) {
                continue;
            }
            head.look = direction.clone().normalize();
            head.lookUntil = ctx().currentTick() + LOOK_TICKS;
            Location mouth = mouth(owner, head);
            Vfx.dot(mouth, Particle.SPIT, null, false);
            Vfx.cloud(ctx(), mouth, Particle.DUST, 3, 0.05, 0, VENOM_DUST, false);
            Fx.sound(mouth, Sound.ENTITY_LLAMA_SPIT, 0.8f, 1.4f + Fx.jitter(0.1f));
            Fx.sound(mouth, Sound.ENTITY_CAT_HISS, 0.4f, 1.5f + Fx.jitter(0.1f));
            Dart dart = new Dart(owner, mouth, direction.normalize(), range + 2);
            Loop.repeat(ctx(), owner, 1, 1, (long) Math.ceil((range + 2) / DART_SPEED) + 1, dart);
            return;
        }
    }

    /** A venom dart: a yellow-cored green streak flying straight, hit-tested every tick. */
    private final class Dart implements LongPredicate {

        private final Player owner;
        private final Vector direction;
        private final double range;
        private Location position;
        private double travelled;

        Dart(Player owner, Location from, Vector direction, double range) {
            this.owner = owner;
            this.position = from.clone();
            this.direction = direction;
            this.range = range;
        }

        @Override
        public boolean test(long tick) {
            double step = Math.min(DART_SPEED, range - travelled);
            if (step <= 1.0E-3) {
                return false;
            }
            World world = position.getWorld();
            RayTraceResult wall = world.rayTraceBlocks(position, direction, step, FluidCollisionMode.NEVER, true);
            double reach = wall == null ? step : wall.getHitPosition().distance(position.toVector());
            LivingEntity victim = firstEnemy(owner, position.toVector(), direction, reach, DART_PAD);
            Location before = position.clone();
            if (victim != null) {
                Location at = Fx.body(victim);
                Vfx.line(ctx(), before, at, 4, Particle.DUST, SWAMP_DUST, false);
                sting(victim);
                return false;
            }
            position = position.clone().add(direction.clone().multiply(reach));
            travelled += reach;
            Vfx.line(ctx(), before, position, 4, Particle.DUST, SWAMP_DUST, false);
            Vfx.dot(position, Particle.DUST, VENOM_DUST, false);
            if (wall != null) {
                Vfx.cloud(ctx(), position, Particle.ITEM_SLIME, 3, 0.1, 0.02, null, false);
                Fx.sound(position, Sound.ENTITY_SLIME_SQUISH_SMALL, 0.6f, 1.4f);
                return false;
            }
            return true;
        }

        private void sting(LivingEntity victim) {
            Combat.hit(ctx(), owner, victim, params().num("spit_damage"));
            poison(victim, params().ticks("spit_poison"));
            Location at = Fx.body(victim);
            if (seen(owner, victim)) {
                Vfx.cloud(ctx(), at, Particle.DUST_COLOR_TRANSITION, 6, 0.2, 0, VENOM_GLOW, false);
                Vfx.cloud(ctx(), at, Particle.ITEM_SLIME, 2, 0.15, 0.02, null, false);
            }
            Fx.sound(at, Sound.ENTITY_PUFFER_FISH_STING, 0.7f, 1.3f + Fx.jitter(0.1f));
        }
    }

    private void poison(LivingEntity victim, long ticks) {
        if (ticks > 0 && Combat.isTargetable(ctx(), player(), victim)) {
            victim.addPotionEffect(new PotionEffect(PotionEffectType.POISON, (int) Math.min(Integer.MAX_VALUE, ticks),
                    0, false, true, true));
        }
    }

    /** The first enemy whose (padded) hitbox the segment from {@code start} along {@code direction} crosses. */
    private @Nullable LivingEntity firstEnemy(Player owner, Vector start, Vector direction, double reach, double pad) {
        Location middle = start.clone().add(direction.clone().multiply(reach / 2)).toLocation(owner.getWorld());
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity enemy : ctx().enemiesNear(owner, middle, reach / 2 + 1.5)) {
            BoundingBox box = enemy.getBoundingBox().expand(pad);
            double distance;
            if (box.contains(start)) {
                distance = 0;
            } else {
                RayTraceResult crossing = box.rayTrace(start, direction, Math.max(1.0E-3, reach));
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

    // ------------------------------------------------------------------ active: head bite

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        Location chest = Fx.body(owner);
        List<LivingEntity> targets = new ArrayList<>();
        for (LivingEntity enemy : ctx().enemiesNear(owner, chest, Math.max(1, params().num("bite_range")))) {
            if (seen(owner, enemy) && Spots.clearPath(chest, Fx.body(enemy))) {
                targets.add(enemy);
            }
        }
        if (targets.isEmpty()) {
            return ActivationResult.fail("물 수 있는 적이 없습니다");
        }
        int mouths = Math.max(1, count);
        boolean spare = mouths > targets.size();
        for (int i = 0; i < mouths; i++) {
            boolean extra = i >= targets.size();
            LivingEntity victim = extra ? targets.getFirst() : targets.get(i);
            // Heads gone under the ground with the nine strike as venom lashes from the shoulders.
            Head head = i < count && brood == null ? heads[i] : null;
            // The spare heads lunge at the nearest enemy together: one more bite between them.
            lash(owner, head, i, victim, extra, i <= targets.size());
        }
        Fx.sound(chest, Sound.ENTITY_CAT_HISS, 1.0f, 0.5f + Fx.jitter(0.05f));
        Fx.sound(chest, Sound.ENTITY_EVOKER_PREPARE_ATTACK, 0.5f, 1.6f + Fx.jitter(0.05f));
        owner.sendActionBar(Text.mm("<" + SWAMP_TAG + ">🐍 머리 물기</" + SWAMP_TAG + "> <white>"
                + Math.min(targets.size(), mouths) + "명</white>"
                + (spare ? " <" + VENOM_TAG + ">+1 추가</" + VENOM_TAG + ">" : "")));
        return ActivationResult.success();
    }

    /** One head lashes out at {@code victim}: its neck stretches to it, bites, and snaps back. */
    private void lash(Player owner, @Nullable Head head, int index, LivingEntity victim, boolean extra,
                      boolean deal) {
        if (head != null) {
            head.biting = true;
        }
        Vector3f baseLocal = new Vector3f(NECK_BASE[Math.min(index, MAX_HEADS - 1)]);
        Loop.repeat(ctx(), owner, index, 1, LASH_TICKS + HOLD_TICKS + RETRACT_TICKS, run -> {
            Location feet = owner.getLocation();
            float scale = (float) Body.scaleOf(owner);
            Vector3f base = yawTurn(owner.getBodyYaw()).transform(new Vector3f(baseLocal).mul(bodyScale(owner)));
            boolean reachable = victim.isValid() && !victim.isDead() && victim.getWorld().equals(owner.getWorld());
            Vector3f toVictim = reachable ? toVector3f(Fx.body(victim).toVector().subtract(feet.toVector())).sub(base)
                    : new Vector3f(0, 0.5f, 0);
            float reach;
            if (run < LASH_TICKS) {
                reach = (run + 1f) / LASH_TICKS;
            } else if (run < LASH_TICKS + HOLD_TICKS) {
                reach = 1f;
                if (run == LASH_TICKS && reachable && deal) {
                    bite(owner, victim, extra);
                }
            } else {
                reach = Math.max(0.15f, 1f - (run - LASH_TICKS - HOLD_TICKS + 1f) / RETRACT_TICKS);
            }
            Vector3f along = new Vector3f(toVictim).mul(reach);
            if (head != null) {
                if (head.neck != null && head.neck.isValid()) {
                    Displays.moveTo(head.neck, feet);
                    Displays.animate(head.neck, beam(base, along, NECK_WIDTH * scale), 1);
                }
                if (head.skull != null && head.skull.isValid()) {
                    Displays.moveTo(head.skull, feet);
                    Quaternionf facing = pointing(toVictim);
                    float snap = run == LASH_TICKS ? 0.75f : 1f;
                    Vector3f center = new Vector3f(base).add(along);
                    Displays.animate(head.skull, box(center, new Vector3f(HEAD_SIZE).mul(scale * 1.15f)
                            .mul(1f, snap, 1f), facing), 1);
                }
            } else if (run < LASH_TICKS + HOLD_TICKS) {
                // No display budget (or no head yet): the lash is drawn in venom.
                Location from = feet.clone().add(base.x(), base.y(), base.z());
                Vfx.line(ctx(), from, from.clone().add(along.x(), along.y(), along.z()), 4, Particle.DUST,
                        SWAMP_DUST, false);
            }
            return true;
        }, () -> {
            if (head != null) {
                head.biting = false;
            }
        });
    }

    private void bite(Player owner, LivingEntity victim, boolean extra) {
        if (!Combat.isTargetable(ctx(), owner, victim)) {
            return;
        }
        Combat.hit(ctx(), owner, victim, params().num(extra ? "extra_bite_damage" : "bite_damage"));
        if (!extra) {
            poison(victim, params().ticks("bite_poison"));
        }
        Location at = Fx.body(victim);
        if (seen(owner, victim)) {
            Vfx.burst(ctx(), at, PALETTE, 0.5, Particle.ITEM_SLIME, false);
            Vfx.dot(at, Particle.CRIT, null, false);
        }
        Fx.sound(at, Sound.ENTITY_EVOKER_FANGS_ATTACK, 0.9f, 1.1f + Fx.jitter(0.1f));
        Fx.sound(at, Sound.ENTITY_PHANTOM_BITE, 0.8f, 0.8f + Fx.jitter(0.1f));
        Fx.sound(at, Sound.ENTITY_FOX_BITE, 0.6f, 0.7f + Fx.jitter(0.1f));
    }

    // ------------------------------------------------------------------ ultimate: nine heads

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        double radius = Math.max(1, params().num("ult_radius"));
        boolean anyone = false;
        for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), radius)) {
            anyone |= seen(owner, enemy);
        }
        if (!anyone) {
            return ActivationResult.fail("반경 " + AbilityInfo.formatNumber(radius) + "블록 안에 적이 없습니다");
        }
        Brood nine = new Brood(owner);
        brood = nine;
        submerge(owner);
        nine.rise();
        return ActivationResult.success();
    }

    /** One of the nine giant heads: where it stands, which way it looks, and its two displays. */
    private static final class Giant {
        final Location ground;
        final float outward;
        @Nullable BlockDisplay neck;
        @Nullable BlockDisplay skull;
        Vector look;
        long rearAt = Long.MIN_VALUE / 2;

        Giant(Location ground, float outward) {
            this.ground = ground;
            this.outward = outward;
            this.look = facing(outward);
        }
    }

    /**
     * The nine heads: they rise one after another out of the ground in a ring around where the hydra stood, then take
     * turns spitting globs at the enemies in range (the one hit least so far first), each glob arcing to a marked spot.
     */
    private final class Brood implements LongPredicate {

        private final Player owner;
        private final Location center;
        private final long life;
        private final double radius;
        private final List<Giant> giants = new ArrayList<>();
        private final Map<UUID, Integer> hits = new HashMap<>();
        private int spits;

        Brood(Player owner) {
            this.owner = owner;
            this.center = owner.getLocation();
            this.life = Math.max(FIRST_SPIT + GLOB_FLIGHT + 10, params().ticks("ult_duration"));
            this.radius = Math.max(1, params().num("ult_radius"));
        }

        void rise() {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            double phase = random.nextDouble(Math.PI * 2);
            World world = center.getWorld();
            for (int i = 0; i < ULT_HEADS; i++) {
                double angle = phase + i * Math.PI * 2 / ULT_HEADS;
                Location spot = center.clone().add(Math.cos(angle) * RING_RADIUS, 1.5, Math.sin(angle) * RING_RADIUS);
                Spots.snapToGround(world, spot, 4);
                // Room for the whole neck: a head never rises into a ceiling.
                if (!Spots.isFree(world, spot, 0.4, ULT_NECK_HEIGHT + 0.6) || !Spots.hasGroundBelow(world, spot)) {
                    continue;
                }
                float outward = (float) Math.toDegrees(Math.atan2(-Math.cos(angle), Math.sin(angle)));
                giants.add(new Giant(spot, outward));
            }
            long lifetime = life + SINK_TICKS + 6;
            for (int i = 0; i < giants.size(); i++) {
                Giant giant = giants.get(i);
                if (!reserve(2)) {
                    continue;
                }
                giant.neck = keep(Displays.block(ctx(), owner, giant.ground, NECK.createBlockData(),
                        giantNeck(giant, 0.02f), lifetime));
                giant.skull = keep(Displays.block(ctx(), owner, giant.ground, HEAD.createBlockData(),
                        giantHead(giant, 0.02f, 0), lifetime));
                Displays.outline(giant.skull, SWAMP);
            }
            Vfx.shockwave(ctx(), owner, center.clone().add(0, 0.1, 0), 0.5, RING_RADIUS + 1, 6, Particle.DUST_COLOR_TRANSITION,
                    MIRE, true);
            Fx.sound(center, Sound.ENTITY_CAT_HISS, 1.5f, 0.4f);
            Fx.sound(center, Sound.BLOCK_ROOTED_DIRT_BREAK, 1.5f, 0.5f);
            Fx.sound(center, Sound.ENTITY_RAVAGER_ROAR, 0.7f, 1.5f);
            owner.sendActionBar(Text.mm("<" + SWAMP_TAG + "><b>🐍 아홉 머리</b></" + SWAMP_TAG + "> <gray>"
                    + Fx.seconds(life) + "초간 독액 세례</gray>"));
            Loop.repeat(ctx(), owner, 0, 1, life, this, this::sink);
        }

        @Override
        public boolean test(long tick) {
            for (int i = 0; i < giants.size(); i++) {
                Giant giant = giants.get(i);
                long risen = tick - i * RISE_STAGGER;
                if (risen == 0) {
                    burst(giant);
                }
                if (risen >= 1 && risen <= RISE_TICKS) {
                    pose(giant, Math.min(1f, risen / (float) RISE_TICKS), 0, 1);
                }
            }
            if (tick >= FIRST_SPIT && tick <= life - GLOB_FLIGHT - REAR_TICKS - 2
                    && (tick - FIRST_SPIT) % SPIT_GAP == 0 && !giants.isEmpty()) {
                Giant giant = giants.get(spits % giants.size());
                LivingEntity target = pickTarget(giant);
                if (target != null) {
                    rear(giant, target, tick);
                    spits++;
                }
            }
            for (Giant giant : giants) {
                long since = tick - giant.rearAt;
                if (since >= 0 && since <= REAR_TICKS + 4) {
                    // Rears back up, then lunges forward and down as it spits, and settles.
                    float lunge = since < REAR_TICKS ? -(since + 1f) / REAR_TICKS : 1f - (since - REAR_TICKS) / 4f;
                    pose(giant, 1f, lunge, 1);
                } else if (tick > RISE_TICKS + ULT_HEADS * RISE_STAGGER && tick % 5 == 0) {
                    // Idle heads weave a little.
                    pose(giant, 1f, (float) Math.sin(tick * 0.2 + giant.outward) * 0.15f, 5);
                }
            }
            if (tick % 6 == 0) {
                Vfx.ring(ctx(), center.clone().add(0, 0.12, 0), RING_RADIUS + 0.4, 18, tick * 0.05,
                        Particle.DUST_COLOR_TRANSITION, MIRE, false);
            }
            return true;
        }

        /** A head bursts out of the ground in a pillar of mud. */
        private void burst(Giant giant) {
            World world = giant.ground.getWorld();
            world.spawnParticle(Particle.DUST_PILLAR, giant.ground.clone().add(0, 0.2, 0), Vfx.n(ctx(), 10), 0.3,
                    0.2, 0.3, 0, MUD.createBlockData(), true);
            world.spawnParticle(Particle.BLOCK, giant.ground.clone().add(0, 0.3, 0), Vfx.n(ctx(), 10), 0.3, 0.2, 0.3,
                    0, MUD.createBlockData(), true);
            Vfx.cloud(ctx(), giant.ground.clone().add(0, 1, 0), Particle.ITEM_SLIME, 5, 0.3, 0.05, null, true);
            Fx.sound(giant.ground, Sound.BLOCK_MUD_BREAK, 1.2f, 0.6f + Fx.jitter(0.1f));
            Fx.sound(giant.ground, Sound.ENTITY_SLIME_SQUISH, 0.9f, 0.5f + Fx.jitter(0.05f));
        }

        private void pose(Giant giant, float grow, float lunge, int ticks) {
            if (giant.neck != null && giant.neck.isValid()) {
                Displays.animate(giant.neck, giantNeck(giant, grow), ticks);
            }
            if (giant.skull != null && giant.skull.isValid()) {
                Displays.animate(giant.skull, giantHead(giant, grow, lunge), ticks);
            }
        }

        /**
         * The enemy in range hit least so far (nearest to the head among equals), not yet at the cap, that the head
         * can see: nobody is lobbed at behind a wall or under a roof.
         */
        private @Nullable LivingEntity pickTarget(Giant giant) {
            int cap = Math.max(1, params().integer("glob_max_hits"));
            LivingEntity best = null;
            int bestHits = Integer.MAX_VALUE;
            double bestDistance = Double.MAX_VALUE;
            Location crown = giant.ground.clone().add(0, ULT_NECK_HEIGHT + 0.1, 0);
            for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius)) {
                if (!seen(owner, enemy) || !Spots.clearPath(crown, Fx.body(enemy))
                        && !Spots.clearPath(crown, enemy.getEyeLocation())) {
                    continue;
                }
                int taken = hits.getOrDefault(enemy.getUniqueId(), 0);
                if (taken >= cap) {
                    continue;
                }
                double distance = enemy.getLocation().distanceSquared(giant.ground);
                if (taken < bestHits || taken == bestHits && distance < bestDistance) {
                    best = enemy;
                    bestHits = taken;
                    bestDistance = distance;
                }
            }
            return best;
        }

        /** The head rears back, then lunges and spits a glob at where the target stands now. */
        private void rear(Giant giant, LivingEntity target, long tick) {
            Location landing = target.getLocation();
            Location ground = landing.clone().add(0, 0.3, 0);
            Spots.snapToGround(landing.getWorld(), ground, 6);
            giant.look = landing.toVector().subtract(giant.ground.toVector()).setY(0);
            if (giant.look.lengthSquared() < 1.0E-6) {
                giant.look = facing(giant.outward);
            }
            giant.look.normalize();
            giant.rearAt = tick;
            Location mouth = giantMouth(giant);
            Fx.sound(mouth, Sound.ENTITY_CAT_HISS, 0.9f, 0.6f + Fx.jitter(0.1f));
            ctx().runLater(owner, REAR_TICKS, () -> {
                Location from = giantMouth(giant);
                Vfx.dot(from, Particle.SPIT, null, true);
                Vfx.cloud(ctx(), from, Particle.DUST_COLOR_TRANSITION, 6, 0.15, 0, VENOM_GLOW, true);
                Fx.sound(from, Sound.ENTITY_LLAMA_SPIT, 1.2f, 0.7f + Fx.jitter(0.1f));
                Fx.sound(from, Sound.ENTITY_DROWNED_SHOOT, 0.7f, 0.6f + Fx.jitter(0.1f));
                Glob glob = new Glob(from, ground);
                Loop.repeat(ctx(), owner, 1, 1, GLOB_FLIGHT, glob, glob::land);
            });
        }

        /**
         * A lobbed glob of venom: a spinning blob of slime arcing to its landing spot, which is ringed on the ground
         * in advance. The arc stays under ceilings, and a glob that meets a wall or a roof on the way bursts there.
         */
        private final class Glob implements LongPredicate {

            private final Location from;
            private final Location to;
            private final double lift;
            private @Nullable BlockDisplay blob;
            /** Where it burst against a block on the way (null: it reached its spot). */
            private @Nullable Location impact;
            private boolean landed;

            Glob(Location from, Location to) {
                this.from = from;
                this.to = to;
                this.lift = arcLift(from, to);
                if (reserve(1)) {
                    blob = keep(Displays.block(ctx(), owner, from, GLOB.createBlockData(), Displays.scaled(0.05f),
                            GLOB_FLIGHT + 6));
                    blob.setTeleportDuration(1);
                    Displays.animateNextTick(ctx(), owner, blob, globPose(1), 2);
                }
            }

            @Override
            public boolean test(long tick) {
                double t = (tick + 1.0) / GLOB_FLIGHT;
                Location before = arc(tick / (double) GLOB_FLIGHT);
                Location at = arc(t);
                Vector step = at.toVector().subtract(before.toVector());
                double length = step.length();
                World world = before.getWorld();
                if (length > 1.0E-6) {
                    // The last stretch stops short of the floor it lands on.
                    double probe = t >= 1 ? Math.max(0, length - 0.05) : length;
                    RayTraceResult block = probe <= 1.0E-6 ? null : world.rayTraceBlocks(before,
                            step.clone().multiply(1 / length), probe, FluidCollisionMode.NEVER, true);
                    if (block != null) {
                        impact = block.getHitPosition().toLocation(world)
                                .subtract(step.clone().multiply(0.25 / length));
                        at = impact;
                    }
                }
                BlockDisplay body = blob;
                if (body != null && body.isValid()) {
                    Displays.moveTo(body, at);
                    Displays.animate(body, globPose(tick + 2), 1);
                }
                Vfx.line(ctx(), before, at, 3, Particle.DUST, SWAMP_DUST, true);
                Vfx.dot(at, Particle.DUST_COLOR_TRANSITION, VENOM_GLOW, true);
                if (tick % 3 == 0) {
                    Vfx.dot(at, Particle.FALLING_SPORE_BLOSSOM, null, false);
                }
                // The landing spot pulses on the ground: dodge it.
                double glob = Math.max(0.3, params().num("glob_radius"));
                Vfx.ring(ctx(), to.clone().add(0, 0.1, 0), glob * (0.75 + 0.25 * (tick % 4) / 3.0),
                        (int) Math.max(10, glob * 9), tick * 0.2, Particle.DUST_COLOR_TRANSITION, VENOM_GLOW, true);
                return impact == null;
            }

            /** The point of the flight at {@code t} (0..1): the straight line, lifted by a parabola. */
            private Location arc(double t) {
                Vector delta = to.toVector().subtract(from.toVector());
                return from.clone().add(delta.multiply(t)).add(0, lift * 4 * t * (1 - t), 0);
            }

            /** The blob spinning as it flies, a little squashed. */
            private Transformation globPose(long tick) {
                return Displays.transform(0, 0, 0, GLOB_SIZE, GLOB_SIZE * 0.85f, GLOB_SIZE, tick * 37f, tick * 23f,
                        0);
            }

            /** It lands (or burst against a block on the way): venom splashes whoever is in reach and in sight. */
            void land() {
                if (landed) {
                    return;
                }
                landed = true;
                Location point = impact != null ? impact : to;
                // The splash reaches out from just above the floor it landed on (or right where it burst), and only
                // to bodies in sight of it: never through the block it hit.
                Location splash = impact != null ? impact : to.clone().add(0, 0.5, 0);
                double globRadius = Math.max(0.3, params().num("glob_radius"));
                int cap = Math.max(1, params().integer("glob_max_hits"));
                double damage = params().num("glob_damage");
                long poisonTicks = params().ticks("glob_poison");
                for (LivingEntity enemy : ctx().enemiesNear(owner, splash, globRadius)) {
                    if (!Spots.clearPath(splash, Fx.body(enemy)) && !Spots.clearPath(splash, enemy.getEyeLocation())) {
                        continue;
                    }
                    int taken = hits.getOrDefault(enemy.getUniqueId(), 0);
                    if (taken >= cap) {
                        continue;
                    }
                    hits.put(enemy.getUniqueId(), taken + 1);
                    Combat.hit(ctx(), owner, enemy, damage);
                    poison(enemy, poisonTicks);
                }
                BlockDisplay body = blob;
                if (body != null && body.isValid()) {
                    Displays.shrinkAway(ctx(), owner, body, 2);
                }
                World world = point.getWorld();
                Vfx.burst(ctx(), point.clone().add(0, 0.3, 0), PALETTE, 0.7, Particle.ITEM_SLIME, true);
                world.spawnParticle(Particle.SPLASH, point.clone().add(0, 0.2, 0), Vfx.n(ctx(), 12),
                        globRadius * 0.4, 0.1, globRadius * 0.4, 0.1, null, true);
                Fx.sound(point, Sound.ENTITY_SLIME_SQUISH, 1.0f, 0.7f + Fx.jitter(0.1f));
                Fx.sound(point, Sound.BLOCK_BUBBLE_COLUMN_BUBBLE_POP, 1.0f, 0.8f + Fx.jitter(0.1f));
                Fx.sound(point, Sound.ENTITY_GENERIC_SPLASH, 0.6f, 1.2f + Fx.jitter(0.1f));
                if (impact != null) {
                    return; // splattered on a wall or a roof: no puddle on the marked spot
                }
                Vfx.shockwave(ctx(), owner, to.clone().add(0, 0.1, 0), 0.2, globRadius, 4, Particle.DUST, SWAMP_DUST,
                        true);
                // A puddle bubbles for a moment.
                Location puddle = to.clone().add(0, 0.12, 0);
                Loop.repeat(ctx(), owner, 2, 3, 5, run -> {
                    Vfx.ring(ctx(), puddle, globRadius * 0.6, 8, run * 0.7, Particle.DUST, VENOM_DUST, false);
                    Vfx.dot(Vfx.scatter(puddle, globRadius * 0.6), Particle.BUBBLE_POP, null, false);
                    return true;
                });
            }
        }

        /**
         * How high a glob may arc between {@code from} and {@code to}: {@link #GLOB_ARC}, lowered so the arc passes
         * under any ceiling above its path (see {@code ArcherAbility}'s rain).
         */
        private double arcLift(Location from, Location to) {
            World world = from.getWorld();
            Vector delta = to.toVector().subtract(from.toVector());
            double lift = GLOB_ARC;
            for (double t : new double[] {0.25, 0.5, 0.75}) {
                Location point = from.clone().add(delta.clone().multiply(t));
                RayTraceResult ceiling = world.rayTraceBlocks(point, new Vector(0, 1, 0), GLOB_ARC + CEILING_GAP,
                        FluidCollisionMode.NEVER, true);
                if (ceiling != null) {
                    double room = ceiling.getHitPosition().getY() - point.getY() - CEILING_GAP;
                    lift = Math.min(lift, Math.max(0, room) / (4 * t * (1 - t)));
                }
            }
            return lift;
        }

        private Location giantMouth(Giant giant) {
            return giant.ground.clone().add(0, ULT_NECK_HEIGHT + 0.1, 0).add(giant.look.clone().multiply(0.7));
        }

        /** The heads sink back into the mud, and the hydra's own heads grow back. */
        void sink() {
            if (brood == this) {
                brood = null;
                resurface(owner);
            }
            for (Giant giant : giants) {
                for (BlockDisplay part : new BlockDisplay[] {giant.neck, giant.skull}) {
                    if (part != null && part.isValid()) {
                        Displays.shrinkAway(ctx(), owner, part, SINK_TICKS);
                    }
                }
                giant.ground.getWorld().spawnParticle(Particle.BLOCK, giant.ground.clone().add(0, 0.3, 0),
                        Vfx.n(ctx(), 6), 0.3, 0.2, 0.3, 0, MUD.createBlockData());
            }
            Fx.sound(center, Sound.BLOCK_MUD_BREAK, 1.2f, 0.5f);
            Fx.sound(center, Sound.ENTITY_SLIME_SQUISH, 0.8f, 0.4f);
            int total = hits.values().stream().mapToInt(Integer::intValue).sum();
            if (total > 0) {
                owner.sendActionBar(Text.mm("<" + SWAMP_TAG + ">🐍 아홉 머리</" + SWAMP_TAG + "> <white>독액 " + total
                        + "회 적중</white>"));
            }
        }
    }

    /** A giant neck rising from its spot, leaning a little outwards, grown to {@code grow}. */
    private static Transformation giantNeck(Giant giant, float grow) {
        Vector out = facing(giant.outward).multiply(0.35);
        Vector3f along = new Vector3f((float) out.getX(), ULT_NECK_HEIGHT, (float) out.getZ()).mul(Math.max(0.02f,
                grow));
        return beam(new Vector3f(0, -0.2f, 0), along, ULT_NECK_WIDTH);
    }

    /**
     * A giant head on its neck, facing where it looks; {@code lunge} below 0 rears it back and up, above 0 thrusts it
     * forward and down.
     */
    private static Transformation giantHead(Giant giant, float grow, float lunge) {
        Vector out = facing(giant.outward).multiply(0.35);
        Vector3f top = new Vector3f((float) out.getX(), ULT_NECK_HEIGHT - 0.2f, (float) out.getZ())
                .mul(Math.max(0.02f, grow));
        Vector look = giant.look.clone();
        Vector3f dir = new Vector3f((float) look.getX(), -0.35f * lunge, (float) look.getZ());
        Vector3f center = new Vector3f(top).add(new Vector3f(dir).normalize().mul(0.25f + 0.3f * lunge));
        float scale = Math.max(0.02f, grow);
        return box(center, new Vector3f(ULT_HEAD_SIZE).mul(scale), pointing(dir));
    }

    // ------------------------------------------------------------------ helpers

    /** True unless {@code entity} is a player hidden from {@code owner} (stealth): nothing may reveal it. */
    private static boolean seen(Player owner, Entity entity) {
        return !(entity instanceof Player hidden) || owner.canSee(hidden);
    }

    private boolean reserve(int count) {
        live.removeIf(display -> !display.isValid());
        return live.size() + count <= MAX_DISPLAYS;
    }

    private <T extends Display> T keep(T display) {
        live.add(display);
        return display;
    }

    /**
     * How body-space points of a 1.8 block body map onto the owner: its size across, its current height (which also
     * drops while it crouches) up.
     */
    private static Vector3f bodyScale(Player owner) {
        float scale = (float) Body.scaleOf(owner);
        return new Vector3f(scale, (float) (owner.getHeight() / 1.8), scale);
    }

    /** A body-space point of the owner (scaled, turned with its body) in the world. */
    private static Location bodyPoint(Player owner, Vector3f local) {
        Vector3f offset = yawTurn(owner.getBodyYaw()).transform(new Vector3f(local).mul(bodyScale(owner)));
        return owner.getLocation().add(offset.x(), offset.y(), offset.z());
    }

    /** The horizontal unit vector a Minecraft yaw faces. */
    private static Vector facing(float yaw) {
        double radians = Math.toRadians(yaw);
        return new Vector(-Math.sin(radians), 0, Math.cos(radians));
    }

    /** The rotation of a Minecraft yaw (0 faces +Z, 90 faces -X) about the vertical. */
    private static Quaternionf yawTurn(float yaw) {
        return new Quaternionf().rotateY((float) Math.toRadians(-yaw));
    }

    /** A box of {@code size} centered on {@code center} (relative to the display), turned by {@code rotation}. */
    private static Transformation box(Vector3f center, Vector3f size, Quaternionf rotation) {
        Vector3f translation = new Vector3f(center).sub(new Quaternionf(rotation).transform(new Vector3f(size).mul(0.5f)));
        return new Transformation(translation, new Quaternionf(rotation), new Vector3f(size), new Quaternionf());
    }

    /** A square bar of {@code width} from {@code start} along {@code along} (both relative to the display). */
    private static Transformation beam(Vector3f start, Vector3f along, float width) {
        float length = Math.max(0.001f, along.length());
        Vector3f center = new Vector3f(start).add(new Vector3f(along).mul(0.5f));
        return box(center, new Vector3f(width, width, length), pointing(along));
    }

    /** The rotation turning +Z onto {@code direction} (+Z if it is zero). */
    private static Quaternionf pointing(Vector3f direction) {
        if (direction.lengthSquared() < 1.0E-8f) {
            return new Quaternionf();
        }
        return new Quaternionf().rotationTo(new Vector3f(0, 0, 1), new Vector3f(direction).normalize());
    }

    private static Vector3f toVector3f(Vector vector) {
        return new Vector3f((float) vector.getX(), (float) vector.getY(), (float) vector.getZ());
    }
}
