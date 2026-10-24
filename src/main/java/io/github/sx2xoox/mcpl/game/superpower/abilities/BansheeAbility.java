package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;
import java.util.logging.Level;

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
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 밴시 — the Irish fairy woman who keens all night for the one in the house about to die. She hears the dying: an enemy
 * around her that takes a beating in a short time is keened over, marked through walls for her and her allies (it is
 * about to fall: finish it) and her ultimate fills a little. The active throws a shroud that wraps the first enemy it
 * reaches: it hurts, and for a while the shrouded cannot heal above a ceiling under its maximum health (a second shroud
 * lowers it further); the health it has is never taken. The ultimate foretells death for the enemies around and makes
 * them race: after a countdown, the one with the least health then is taken, the others only shiver.
 *
 * <p>Unlike the 사신, who executes those under a share of their health, and the 사냥꾼, who hunts the weakest, the
 * banshee points at whoever was <em>just</em> beaten hard, and her omen is a rule the doomed play against each other:
 * heal, or hurt the others, to pass the skull on. The healing ceiling is new: it cuts healing (a scoped regain event for
 * vanilla healing, a check every tick for healing that sets health directly), it never lowers maximum health, which
 * would be hidden damage outside the damage pipeline.
 *
 * <p>Look: grey-green mist, silver and mourning black. A keened enemy carries a swinging soul lantern with a silver
 * outline only the banshee's side sees. The shroud flies as a fluttering white cloth trailing ash; the shrouded wear a
 * veil of white glass over their heads with the ceiling written on it, and ash drifts down around them. The omen hangs a
 * countdown over every doomed enemy and a skull over the one who goes first; the skull floats from head to head as
 * health changes, while the banshee raises her lantern and wails louder every second.
 */
public final class BansheeAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("banshee", "밴시", Material.SOUL_LANTERN)
            .color(TextColor.color(0x8FA89A))
            .difficulty(1)
            .passive("곡하는 여인",
                    "{keen_radius}블록 안의 적이 {keen_window}초 안에 합쳐서 {keen_damage} 이상 피해를 받으면 곡소리를 "
                            + "냅니다. 그 적은 {keen_mark}초간 나와 아군에게만 벽 너머로도 보이는 영혼 등불이 머리 위에 "
                            + "걸리고, 내 궁극기 게이지가 {keen_gauge}% 찹니다(같은 적은 {keen_cooldown}초마다).")
            .active("수의",
                    "바라보는 방향으로 수의를 던집니다({shroud_range}블록). 처음 닿은 적은 {shroud_damage} 피해를 입고 "
                            + "{shroud_duration}초간 수의에 감겨 체력을 '최대 체력 - {shroud_cap}'보다 높게 회복할 수 "
                            + "없습니다(지금 체력은 깎이지 않습니다). 수의에 감긴 적을 다시 감으면 한도가 '최대 체력 - "
                            + "{shroud_stack_cap}'까지 내려갑니다.",
                    10)
            .ultimate("누가 먼저 갈까",
                    "{omen_radius}블록 안의 적 최대 {omen_targets}명(눈에 보이는 적 먼저)에게 죽음을 예고하고 "
                            + "{omen_duration}초를 셉니다. 예고된 적 가운데 지금 체력이 가장 낮은 적의 머리 위에 모두에게 "
                            + "보이는 해골이 뜨고, 체력이 바뀌면 해골도 옮겨 갑니다. 끝나는 순간 해골이 뜬 적은 데려가져 "
                            + "{omen_take} 피해를 입고 {omen_silence}초간 침묵하며, 나머지 예고된 적은 {omen_others} 피해를 "
                            + "입습니다. 예고된 적이 한 명뿐이면 {omen_solo} 피해와 침묵을 받습니다.")
            .param("keen_radius", 20)
            .param("keen_window", 2)
            .param("keen_damage", 6)
            .param("keen_mark", 3)
            .param("keen_gauge", 5)
            .param("keen_cooldown", 15)
            .param("shroud_range", 18)
            .param("shroud_damage", 4)
            .param("shroud_duration", 10)
            .param("shroud_cap", 3)
            .param("shroud_stack_cap", 6)
            .param("omen_radius", 20)
            .param("omen_targets", 4)
            .param("omen_duration", 6)
            .param("omen_take", 8)
            .param("omen_silence", 1.5)
            .param("omen_others", 2)
            .param("omen_solo", 6)
            .build();

    private static final String COLOR_TAG = "#8FA89A";
    private static final String SILVER_TAG = "#C9CED6";
    private static final String DOOM_TAG = "#E0E4EA";
    private static final String BLOOD_TAG = "#C2414B";
    private static final double TAU = Math.PI * 2;

    // ------------------------------------------------------------------ look: mist, silver, mourning black
    /** Silver, grey-green mist, mourning black. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xC9CED6, 0x8FA89A, 0x17171C);
    private static final Color SILVER = Color.fromRGB(0xC9CED6);
    private static final Color MIST = Color.fromRGB(0x8FA89A);
    private static final Color MOURNING = Color.fromRGB(0x17171C);
    private static final Particle.DustOptions MIST_DUST = PALETTE.dust(1.0f);
    private static final Particle.DustOptions MIST_SMALL = PALETTE.dust(0.6f);
    private static final Particle.DustOptions SILVER_DUST = new Particle.DustOptions(SILVER, 0.7f);
    private static final Particle.DustOptions BLACK_DUST = new Particle.DustOptions(MOURNING, 0.9f);
    private static final Particle.DustTransition MIST_GLOW = PALETTE.glow(1.0f);
    private static final Particle.DustTransition MIST_FADE = PALETTE.fade(1.2f);

    /** Block and item types of the visuals (block data and items are created on use: they need a running server). */
    private static final Material CLOTH = Material.WHITE_WOOL;
    private static final Material VEIL = Material.WHITE_STAINED_GLASS;
    private static final Material VEIL_TWO = Material.LIGHT_GRAY_STAINED_GLASS;
    private static final Material LANTERN_ITEM = Material.SOUL_LANTERN;
    private static final Material SKULL_ITEM = Material.SKELETON_SKULL;
    private static final Material BONE_ITEM = Material.BONE;

    /** Display entities this ability keeps alive at most at once (optional pieces are skipped beyond it). */
    private static final int MAX_DISPLAYS = 24;

    // passive: keening
    private static final int MAX_KEEN_MARKS = 4;
    private static final double LANTERN_ABOVE = 0.55;

    // active: the shroud
    private static final double CLOTH_SPEED = 1.0;
    private static final double CLOTH_PAD = 0.3;
    private static final double VEIL_ABOVE = 0.22;
    private static final double LABEL_ABOVE = 0.75;
    private static final long VICTIM_NOTE_PERIOD = 20;

    // ultimate: the omen
    private static final double SKULL_ABOVE = 1.15;
    private static final double COUNT_ABOVE = 0.5;
    private static final long SKULL_FLIGHT = 6;
    private static final long WAIL_PERIOD = 20;

    /** Damage recently taken by each enemy around: (tick, amount), oldest first. */
    private final Map<UUID, ArrayDeque<double[]>> beatings = new HashMap<>();
    /** When each enemy was last keened over (tick). */
    private final Map<UUID, Long> keened = new HashMap<>();
    /** Shrouded enemies, by entity id. */
    private final Map<UUID, Shroud> shrouds = new HashMap<>();
    private final List<Display> displays = new ArrayList<>();
    private int keenMarks;
    private @Nullable Mourning listener;
    /** The target of the shroud hit being dealt right now, and whether it landed (see {@link #onDamageDealt}). */
    private @Nullable LivingEntity pendingTarget;
    private boolean landed;

    private static final class Shroud {
        final LivingEntity target;
        int layers;
        long until;
        double lastHealth;
        long noted = Long.MIN_VALUE / 2;
        @Nullable BlockDisplay veil;
        @Nullable BlockDisplay veilTwo;
        @Nullable TextDisplay label;

        Shroud(LivingEntity target) {
            this.target = target;
        }
    }

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onGrant() {
        Mourning created = new Mourning();
        Bukkit.getPluginManager().registerEvents(created, ctx().plugin());
        listener = created;
    }

    @Override
    public void onRevoke() {
        if (listener != null) {
            HandlerList.unregisterAll(listener);
            listener = null;
        }
        for (Shroud shroud : shrouds.values()) {
            removeVeil(shroud);
        }
        shrouds.clear();
        beatings.clear();
        keened.clear();
        displays.clear();
        pendingTarget = null;
    }

    /** The banshee's ears and the shroud's grip: scoped to this ability, unregistered when it is revoked. */
    private final class Mourning implements Listener {

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onDamage(EntityDamageEvent event) {
            try {
                hear(event);
            } catch (RuntimeException e) {
                ctx().plugin().getLogger().log(Level.WARNING, "Banshee keening failed", e);
            }
        }

        /** Healing above a shrouded enemy's ceiling is cut off before it happens. */
        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onRegain(EntityRegainHealthEvent event) {
            Shroud shroud = shrouds.get(event.getEntity().getUniqueId());
            if (shroud == null || shroud.until <= ctx().currentTick()
                    || !(event.getEntity() instanceof LivingEntity target) || !target.equals(shroud.target)) {
                return;
            }
            double room = ceiling(shroud) - target.getHealth();
            if (room <= 1.0E-9) {
                event.setCancelled(true);
                stifled(shroud);
            } else if (event.getAmount() > room) {
                event.setAmount(room);
                stifled(shroud);
            }
        }
    }

    // ------------------------------------------------------------------ passive: the keening

    /** Every hit an enemy around takes is counted; a beating that adds up makes the banshee keen. */
    private void hear(EntityDamageEvent event) {
        Player owner = player();
        if (!(event.getEntity() instanceof LivingEntity victim) || !(event.getFinalDamage() > 0)
                || !ctx().isAlive(owner) || !ctx().isEnemy(owner, victim)
                || !victim.getWorld().equals(owner.getWorld())) {
            return;
        }
        double radius = params().num("keen_radius");
        if (victim.getLocation().distanceSquared(owner.getLocation()) > radius * radius) {
            return;
        }
        long now = ctx().currentTick();
        long window = Math.max(1, params().ticks("keen_window"));
        ArrayDeque<double[]> hits = beatings.computeIfAbsent(victim.getUniqueId(), id -> new ArrayDeque<>());
        hits.addLast(new double[] {now, event.getFinalDamage()});
        double total = 0;
        for (Iterator<double[]> it = hits.iterator(); it.hasNext(); ) {
            double[] hit = it.next();
            if (now - hit[0] > window) {
                it.remove();
            } else {
                total += hit[1];
            }
        }
        long cooldown = params().ticks("keen_cooldown");
        Long last = keened.get(victim.getUniqueId());
        if (total + 1.0E-9 < params().num("keen_damage") || last != null && now - last < cooldown) {
            return;
        }
        keened.put(victim.getUniqueId(), now);
        hits.clear();
        // The wail comes once the blow has landed (the victim may not survive it).
        ctx().runLater(owner, 1, () -> keen(owner, victim));
    }

    /** The banshee keens over {@code victim}: it is marked for her and her allies, and her ultimate fills a little. */
    private void keen(Player owner, LivingEntity victim) {
        if (!Combat.isTargetable(ctx(), owner, victim)) {
            return;
        }
        double gauge = Math.max(0, params().num("keen_gauge"));
        boolean charged = gauge > 0 && !ctx().isGaugeLocked(owner);
        ctx().addEarnedGauge(owner, gauge);
        Location head = victim.getLocation().add(0, victim.getHeight() + 0.3, 0);
        // Everyone around hears the keening; the banshee hears it from wherever she is.
        Fx.sound(head, Sound.ENTITY_ALLAY_HURT, 0.9f, 0.5f + Fx.jitter(0.05f));
        Fx.sound(head, Sound.ENTITY_VEX_CHARGE, 0.7f, 0.6f + Fx.jitter(0.05f));
        owner.playSound(head, Sound.PARTICLE_SOUL_ESCAPE, SoundCategory.PLAYERS, 1.2f, 0.7f);
        if (isHidden(owner, victim)) {
            return;
        }
        Vfx.ring(ctx(), victim.getLocation().add(0, 0.1, 0), 0.8, 14, 0, Particle.DUST_COLOR_TRANSITION, MIST_GLOW,
                false);
        Vfx.cloud(ctx(), head, Particle.SOUL, 6, 0.25, 0.03, null, false);
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">♫ 곡소리</" + COLOR_TAG + "> <white><target></white>"
                        + (charged ? " <" + SILVER_TAG + ">궁 +" + AbilityInfo.formatNumber(gauge) + "%</" + SILVER_TAG
                        + ">" : ""),
                Placeholder.component("target", victim.name())));
        markKeened(owner, victim);
    }

    /** A swinging soul lantern over the keened enemy, outlined through walls, shown only to the banshee's side. */
    private void markKeened(Player owner, LivingEntity victim) {
        long ticks = params().ticks("keen_mark");
        if (ticks <= 0 || keenMarks >= MAX_KEEN_MARKS || !reserve(1)) {
            return;
        }
        ItemDisplay lantern = Displays.item(ctx(), owner, lanternSpot(victim, 0), new ItemStack(LANTERN_ITEM),
                lanternPose(0), ticks + 2);
        lantern.setTeleportDuration(1);
        lantern.setViewRange(3.0f);
        Displays.outline(lantern, SILVER);
        Set<UUID> hiddenFrom = new HashSet<>();
        onlyFor(owner, lantern, hiddenFrom);
        displays.add(lantern);
        keenMarks++;
        Loop.repeat(ctx(), owner, 1, 1, ticks, run -> {
            if (!lantern.isValid() || !Combat.isTargetable(ctx(), owner, victim)) {
                return false;
            }
            if (run % 10 == 0) {
                onlyFor(owner, lantern, hiddenFrom);
            }
            boolean hidden = isHidden(owner, victim);
            lantern.setViewRange(hidden ? 0f : 3.0f);
            Displays.moveTo(lantern, lanternSpot(victim, run));
            Displays.animate(lantern, lanternPose(run), 1);
            if (!hidden && run % 4 == 0) {
                Location at = lantern.getLocation();
                for (Player viewer : viewers(owner)) {
                    viewer.spawnParticle(Particle.SOUL_FIRE_FLAME, at.getX(), at.getY() - 0.1, at.getZ(), 1, 0.05, 0.05,
                            0.05, 0);
                }
            }
            return true;
        }, () -> {
            keenMarks = Math.max(0, keenMarks - 1);
            if (lantern.isValid()) {
                lantern.remove();
            }
        });
    }

    /**
     * Hides {@code display} from everyone online but the banshee and her allies (also spectators and players who come
     * later, which is why it is called again now and then); {@code hiddenFrom} remembers who it is hidden from.
     */
    private void onlyFor(Player owner, Display display, Set<UUID> hiddenFrom) {
        List<Player> viewers = viewers(owner);
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!viewers.contains(online) && hiddenFrom.add(online.getUniqueId())) {
                online.hideEntity(ctx().plugin(), display);
            }
        }
    }

    /** The banshee and her allies alive in the match. */
    private List<Player> viewers(Player owner) {
        List<Player> viewers = new ArrayList<>();
        viewers.add(owner);
        for (Player ally : ctx().alivePlayers()) {
            if (ctx().isAlly(owner, ally)) {
                viewers.add(ally);
            }
        }
        return viewers;
    }

    private static Location lanternSpot(LivingEntity victim, long run) {
        return victim.getLocation().add(0, victim.getHeight() + LANTERN_ABOVE + Math.sin(run * 0.25) * 0.05, 0);
    }

    /** The lantern swings a little on its chain. */
    private static Transformation lanternPose(long run) {
        float swing = (float) (Math.sin(run * 0.3) * 14);
        return Displays.centered(0, 0, 0, 0.42f, 0.42f, 0.42f, run * 4f, 0, swing);
    }

    // ------------------------------------------------------------------ the shroud: a ceiling on healing

    @Override
    public void onTick(long tick) {
        Player owner = player();
        updateShrouds(owner, tick);
        if (tick % 6 == 0) {
            // Mist clings to the banshee's hem.
            Location feet = owner.getLocation().add(0, 0.1, 0);
            Vfx.dot(Vfx.scatter(feet, 0.5), Particle.DUST, MIST_SMALL, false);
        }
        if (tick % 200 == 0) {
            long now = ctx().currentTick();
            long cooldown = params().ticks("keen_cooldown");
            keened.values().removeIf(last -> now - last > cooldown);
            beatings.values().removeIf(ArrayDeque::isEmpty);
        }
    }

    /** The highest health the shrouded may heal to: its maximum minus the shroud's share, at least 1. */
    private double ceiling(Shroud shroud) {
        double cut = params().num(shroud.layers >= 2 ? "shroud_stack_cap" : "shroud_cap");
        return Math.max(1, Combat.maxHealth(shroud.target) - Math.max(0, cut));
    }

    private void updateShrouds(Player owner, long tick) {
        if (shrouds.isEmpty()) {
            return;
        }
        long now = ctx().currentTick();
        Iterator<Shroud> entries = shrouds.values().iterator();
        while (entries.hasNext()) {
            Shroud shroud = entries.next();
            LivingEntity target = shroud.target;
            if (shroud.until <= now || !target.isValid() || target.isDead() || !ctx().isEnemy(owner, target)) {
                if (target.isValid() && !isHidden(owner, target)) {
                    Vfx.cloud(ctx(), Fx.body(target), Particle.WHITE_ASH, 8, 0.4, 0.02, null, false);
                }
                removeVeil(shroud);
                entries.remove();
                continue;
            }
            clamp(shroud);
            boolean hidden = isHidden(owner, target);
            dressVeil(owner, shroud, hidden, tick);
            if (!hidden && tick % 4 == 0) {
                Location ash = Vfx.scatter(target.getLocation(), 0.5).add(0, target.getHeight() * 0.9, 0);
                Vfx.dot(ash, Particle.WHITE_ASH, null, false);
                Vfx.dot(ash.clone().subtract(0, 0.4, 0), Particle.DUST, SILVER_DUST, false);
            }
            if (target instanceof Player shrouded && now - shroud.noted >= VICTIM_NOTE_PERIOD) {
                shroud.noted = now;
                shrouded.sendActionBar(Text.mm("<" + SILVER_TAG + ">⚱ 수의</" + SILVER_TAG + "> <gray>체력을 "
                        + AbilityInfo.formatNumber(Math.floor(ceiling(shroud) * 10) / 10) + " 넘게 회복할 수 없습니다 ("
                        + Fx.seconds(shroud.until - now) + "초)</gray>"));
            }
        }
    }

    /**
     * Healing that set the health directly (not through a regain event) is taken back above the ceiling, never below
     * the health the shrouded already had: the shroud only stops healing.
     */
    private void clamp(Shroud shroud) {
        LivingEntity target = shroud.target;
        double health = target.getHealth();
        double allowed = Math.max(ceiling(shroud), shroud.lastHealth);
        if (health > allowed + 1.0E-9) {
            target.setHealth(Math.min(Combat.maxHealth(target), allowed));
            stifled(shroud);
            health = target.getHealth();
        }
        shroud.lastHealth = health;
    }

    /** A grey puff where healing was stifled. */
    private void stifled(Shroud shroud) {
        LivingEntity target = shroud.target;
        if (target.isValid() && !isHidden(player(), target)) {
            Vfx.cloud(ctx(), Fx.body(target).add(0, 0.4, 0), Particle.DUST, 4, 0.3, 0, BLACK_DUST, false);
            Vfx.dot(Fx.body(target).add(0, 0.7, 0), Particle.SMOKE, null, false);
        }
    }

    /** The veil over the shrouded head and the ceiling written over it; hidden from the shrouded itself. */
    private void dressVeil(Player owner, Shroud shroud, boolean hidden, long tick) {
        LivingEntity target = shroud.target;
        Location top = target.getLocation().add(0, target.getHeight() + VEIL_ABOVE, 0);
        float range = hidden ? 0f : 2.0f;
        if (shroud.veil == null || !shroud.veil.isValid()) {
            if (!reserve(1)) {
                return;
            }
            shroud.veil = Displays.block(ctx(), owner, top, VEIL.createBlockData(), veilPose(0.05f, 0), 0);
            shroud.veil.setTeleportDuration(1);
            hideFrom(target, shroud.veil);
            displays.add(shroud.veil);
            Displays.animateNextTick(ctx(), owner, shroud.veil, veilPose(1f, tick * 2f), 4);
        }
        if (shroud.layers >= 2 && (shroud.veilTwo == null || !shroud.veilTwo.isValid()) && reserve(1)) {
            shroud.veilTwo = Displays.block(ctx(), owner, top, VEIL_TWO.createBlockData(), veilPose(0.05f, 45), 0);
            shroud.veilTwo.setTeleportDuration(1);
            hideFrom(target, shroud.veilTwo);
            displays.add(shroud.veilTwo);
        }
        if (shroud.label == null || !shroud.label.isValid()) {
            if (reserve(1)) {
                shroud.label = Displays.text(ctx(), owner, top.clone().add(0, LABEL_ABOVE - VEIL_ABOVE, 0),
                        ceilingText(shroud), 0.5f, 0);
                shroud.label.setTeleportDuration(1);
                hideFrom(target, shroud.label);
                displays.add(shroud.label);
            }
        }
        for (Display part : new Display[] {shroud.veil, shroud.veilTwo, shroud.label}) {
            if (part != null && part.isValid() && part.getViewRange() != range) {
                part.setViewRange(range);
            }
        }
        if (shroud.veil != null && shroud.veil.isValid()) {
            Displays.moveTo(shroud.veil, top);
            if (tick % 2 == 0) {
                Displays.animate(shroud.veil, veilPose(1f, tick * 2f), 2);
            }
        }
        if (shroud.veilTwo != null && shroud.veilTwo.isValid()) {
            Displays.moveTo(shroud.veilTwo, top.clone().add(0, 0.06, 0));
            if (tick % 2 == 0) {
                Displays.animate(shroud.veilTwo, veilPose(0.85f, 45 - tick * 2f), 2);
            }
        }
        if (shroud.label != null && shroud.label.isValid()) {
            Displays.moveTo(shroud.label, top.clone().add(0, LABEL_ABOVE - VEIL_ABOVE, 0));
            if (tick % 10 == 0) {
                shroud.label.text(ceilingText(shroud));
            }
        }
    }

    private Component ceilingText(Shroud shroud) {
        String layers = shroud.layers >= 2 ? "⚱⚱" : "⚱";
        return Text.mm("<" + SILVER_TAG + ">" + layers + " 수의</" + SILVER_TAG + "> <gray>♥"
                + AbilityInfo.formatNumber(Math.floor(ceiling(shroud) * 10) / 10) + "</gray>");
    }

    private void hideFrom(LivingEntity target, Display display) {
        if (target instanceof Player wearer) {
            // The veil hangs right over its eyes: it would only blur the shrouded's own view.
            wearer.hideEntity(ctx().plugin(), display);
        }
    }

    private static void removeVeil(Shroud shroud) {
        for (Display part : new Display[] {shroud.veil, shroud.veilTwo, shroud.label}) {
            if (part != null && part.isValid()) {
                part.remove();
            }
        }
        shroud.veil = null;
        shroud.veilTwo = null;
        shroud.label = null;
    }

    /** A sheet of veil glass over the head, sagging a little, turned {@code yaw} degrees. */
    private static Transformation veilPose(float scale, float yaw) {
        return Displays.transform(0, 0, 0, 0.95f * scale, 0.04f, 0.95f * scale, yaw, 0, 0);
    }

    // ------------------------------------------------------------------ active: the shroud

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection();
        if (!(direction.lengthSquared() > 1.0E-8)) {
            return ActivationResult.fail("던질 방향이 없습니다");
        }
        new Cloth(owner, direction.normalize()).fire();
        return ActivationResult.success();
    }

    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (ctx().isAbilityDamage() && victim.equals(pendingTarget)) {
            landed = true;
        }
    }

    /** The thrown shroud: a white cloth fluttering through the air, wrapping the first enemy it reaches. */
    private final class Cloth implements LongPredicate {

        private final Player owner;
        private final Vector direction;
        private final double range;
        private final World world;
        private final float yaw;
        private Location position;
        private double travelled;
        private @Nullable BlockDisplay sheet;
        private boolean done;

        Cloth(Player owner, Vector direction) {
            this.owner = owner;
            this.direction = direction;
            this.range = Math.max(1, params().num("shroud_range"));
            this.world = owner.getWorld();
            this.yaw = yawOf(direction);
            this.position = hand(owner);
        }

        void fire() {
            long flight = (long) Math.ceil(range / CLOTH_SPEED) + 2;
            if (reserve(1)) {
                sheet = Displays.block(ctx(), owner, position, CLOTH.createBlockData(), clothPose(0), flight + 12);
                sheet.setTeleportDuration(1);
                displays.add(sheet);
            }
            Vfx.cloud(ctx(), position, Particle.WHITE_ASH, 8, 0.2, 0.02, null, false);
            Fx.sound(position, Sound.ENTITY_PHANTOM_FLAP, 0.9f, 1.3f + Fx.jitter(0.1f));
            Fx.sound(position, Sound.BLOCK_WOOL_PLACE, 0.8f, 0.7f);
            Fx.sound(position, Sound.ENTITY_VEX_AMBIENT, 0.6f, 0.8f + Fx.jitter(0.1f));
            Loop.repeat(ctx(), owner, 1, 1, flight, this, this::end);
        }

        @Override
        public boolean test(long tick) {
            if (done) {
                return false;
            }
            double step = Math.min(CLOTH_SPEED, range - travelled);
            if (step <= 1.0E-3) {
                drop(null);
                return false;
            }
            Vector start = position.toVector();
            RayTraceResult wall = world.rayTraceBlocks(position, direction, step, FluidCollisionMode.NEVER, true);
            double reach = wall == null ? step : wall.getHitPosition().distance(start);
            LivingEntity victim = firstEnemyOnPath(owner, world, start, direction, reach);
            if (victim != null) {
                position = Fx.body(victim);
                moveSheet(tick);
                wrap(victim);
                return false;
            }
            if (wall != null) {
                position = wall.getHitPosition().toLocation(world).subtract(direction.clone().multiply(0.15));
                moveSheet(tick);
                drop(position);
                return false;
            }
            position = position.clone().add(direction.clone().multiply(step));
            travelled += step;
            moveSheet(tick);
            Vfx.dot(position, Particle.WHITE_ASH, null, false);
            if (tick % 2 == 0) {
                Vfx.dot(position.clone().subtract(direction.clone().multiply(0.4)), Particle.DUST, MIST_DUST, false);
            }
            if (tick % 4 == 0) {
                Fx.sound(position, Sound.ENTITY_PHANTOM_FLAP, 0.4f, 1.6f + Fx.jitter(0.1f));
            }
            return true;
        }

        private void moveSheet(long tick) {
            BlockDisplay cloth = sheet;
            if (cloth != null && cloth.isValid()) {
                Displays.moveTo(cloth, position);
                Displays.animate(cloth, clothPose(tick), 1);
            }
        }

        /** The cloth fluttering as it flies: it billows and rolls, lying along the throw. */
        private Transformation clothPose(long tick) {
            float flutter = (float) (Math.sin(tick * 1.3) * 22);
            float roll = (float) (Math.cos(tick * 0.9) * 14);
            return Displays.transform(0, 0, 0, 0.9f, 0.03f, 0.7f, yaw, flutter, roll);
        }

        /** The shroud wraps the first enemy it reached: it hurts, and the healing ceiling closes over it. */
        private void wrap(LivingEntity victim) {
            done = true;
            double damage = params().num("shroud_damage");
            pendingTarget = victim;
            // Without damage there is no hit to land: the shroud just wraps.
            landed = !(damage > 0);
            try {
                Combat.hit(ctx(), owner, victim, damage);
            } finally {
                pendingTarget = null;
            }
            BlockDisplay cloth = sheet;
            if (cloth != null && cloth.isValid()) {
                Displays.shrinkAway(ctx(), owner, cloth, 4);
            }
            if (!landed || !Combat.isTargetable(ctx(), owner, victim)) {
                Vfx.cloud(ctx(), Fx.body(victim), Particle.WHITE_ASH, 8, 0.3, 0.02, null, false);
                return;
            }
            long duration = params().ticks("shroud_duration");
            if (duration <= 0) {
                return;
            }
            long now = ctx().currentTick();
            Shroud shroud = shrouds.get(victim.getUniqueId());
            boolean again = shroud != null && shroud.until > now;
            if (!again) {
                if (shroud != null) {
                    removeVeil(shroud);
                }
                shroud = new Shroud(victim);
                shroud.lastHealth = victim.getHealth();
                shrouds.put(victim.getUniqueId(), shroud);
            }
            shroud.layers = again ? 2 : 1;
            shroud.until = now + duration;
            shroud.lastHealth = Math.min(shroud.lastHealth, victim.getHealth());
            if (shroud.label != null && shroud.label.isValid()) {
                shroud.label.text(ceilingText(shroud));
            }
            Location body = Fx.body(victim);
            Vfx.helix(ctx(), victim.getLocation().add(0, 0.05, 0), 0.55, victim.getHeight(), 1.5, 2, 12,
                    ThreadLocalRandom.current().nextDouble(TAU), Particle.DUST_COLOR_TRANSITION, MIST_FADE, false);
            Vfx.cloud(ctx(), body, Particle.WHITE_ASH, 14, 0.35, 0.03, null, false);
            Vfx.dot(body, Particle.FLASH, SILVER, false);
            Fx.sound(body, Sound.BLOCK_WOOL_PLACE, 1.0f, 0.6f);
            Fx.sound(body, Sound.ENTITY_ALLAY_HURT, 0.6f, 0.5f + Fx.jitter(0.05f));
            Fx.sound(body, Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.9f, 0.7f);
            double ceiling = ceiling(shroud);
            owner.sendActionBar(Text.mm("<" + SILVER_TAG + ">⚱ 수의" + (again ? " 두 겹" : "") + "</" + SILVER_TAG
                            + "> <white><target></white> <gray>회복 한도 ♥" + AbilityInfo.formatNumber(
                            Math.floor(ceiling * 10) / 10) + "</gray>",
                    Placeholder.component("target", victim.name())));
        }

        /** A miss: the cloth sinks to the ground and fades. */
        private void drop(@Nullable Location wallHit) {
            done = true;
            BlockDisplay cloth = sheet;
            if (wallHit != null) {
                Vfx.cloud(ctx(), wallHit, Particle.WHITE_ASH, 6, 0.2, 0.02, null, false);
                Fx.sound(wallHit, Sound.BLOCK_WOOL_HIT, 0.8f, 0.8f);
            }
            if (cloth != null && cloth.isValid()) {
                Location floor = position.clone();
                Spots.snapToGround(world, floor, 8);
                cloth.setTeleportDuration(6);
                Displays.moveTo(cloth, floor.add(0, 0.03, 0));
                Displays.animate(cloth, Displays.transform(0, 0, 0, 0.9f, 0.02f, 0.7f, yaw, 0, 0), 6);
                ctx().runLater(owner, 10, () -> {
                    if (cloth.isValid()) {
                        Displays.shrinkAway(ctx(), owner, cloth, 4);
                    }
                });
            }
        }

        void end() {
            if (!done) {
                drop(null);
            }
        }
    }

    // ------------------------------------------------------------------ ultimate: who goes first

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        List<LivingEntity> doomed = chooseDoomed(owner);
        if (doomed.isEmpty()) {
            return ActivationResult.fail("죽음을 예고할 적이 없습니다");
        }
        new Omen(owner, doomed).start();
        return ActivationResult.success();
    }

    /** Up to the omen's count of enemies around, those in sight first, nearest first; never one hidden from her. */
    private List<LivingEntity> chooseDoomed(Player owner) {
        Location eye = owner.getEyeLocation();
        List<LivingEntity> seen = new ArrayList<>();
        List<LivingEntity> behind = new ArrayList<>();
        for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), params().num("omen_radius"))) {
            if (isHidden(owner, enemy)) {
                continue;
            }
            Location to = enemy.getLocation().add(0, enemy.getHeight() * 0.8, 0);
            (Spots.clearPath(eye, to) ? seen : behind).add(enemy);
        }
        List<LivingEntity> doomed = new ArrayList<>(seen);
        doomed.addAll(behind);
        int max = Math.max(1, params().integer("omen_targets"));
        return doomed.size() > max ? new ArrayList<>(doomed.subList(0, max)) : doomed;
    }

    /**
     * The omen: a countdown over every doomed enemy and a skull over the one with the least health, moving as health
     * changes; when the time is up the skull's bearer is taken. A doomed enemy that dies, leaves or slips out of the
     * banshee's sight (stealth) drops out of the race.
     */
    private final class Omen implements LongPredicate {

        private final Player owner;
        private final long duration;
        private final List<LivingEntity> doomed;
        private final Map<UUID, TextDisplay> counters = new HashMap<>();
        private @Nullable ItemDisplay skull;
        private @Nullable ItemDisplay lantern;
        private @Nullable LivingEntity bearer;
        private long flightUntil;
        private boolean done;

        Omen(Player owner, List<LivingEntity> doomed) {
            this.owner = owner;
            this.duration = Math.max(1, params().ticks("omen_duration"));
            this.doomed = new ArrayList<>(doomed);
        }

        void start() {
            Location head = owner.getEyeLocation();
            if (reserve(1)) {
                lantern = Displays.item(ctx(), owner, lanternOverhead(0), new ItemStack(LANTERN_ITEM),
                        Displays.centered(0, 0, 0, 0.7f, 0.7f, 0.7f, 0, 0, 0), duration + 10);
                lantern.setTeleportDuration(1);
                lantern.setViewRange(2.0f);
                Displays.outline(lantern, MIST);
                displays.add(lantern);
            }
            for (LivingEntity target : doomed) {
                if (!reserve(1)) {
                    break;
                }
                TextDisplay counter = Displays.text(ctx(), owner, counterSpot(target), counterText(duration, false),
                        0.7f, duration + 10);
                counter.setTeleportDuration(1);
                counter.setViewRange(2.0f);
                counters.put(target.getUniqueId(), counter);
                displays.add(counter);
                Location feet = target.getLocation().add(0, 0.1, 0);
                Vfx.ring(ctx(), feet, 0.9, 16, 0, Particle.DUST_COLOR_TRANSITION, MIST_FADE, true);
                Vfx.cloud(ctx(), Fx.body(target), Particle.SOUL, 8, 0.3, 0.03, null, true);
                if (target instanceof Player victim) {
                    victim.sendActionBar(Text.mm("<" + DOOM_TAG + ">☠ 밴시가 당신의 죽음을 예고했습니다</" + DOOM_TAG
                            + "> <gray>체력이 가장 낮은 자가 데려가집니다</gray>"));
                }
            }
            Vfx.shockwave(ctx(), owner, owner.getLocation().add(0, 0.1, 0), 0.5, 6, 8, Particle.DUST_COLOR_TRANSITION,
                    MIST_GLOW, true);
            Fx.sound(head, Sound.ENTITY_ALLAY_DEATH, 1.2f, 0.5f);
            Fx.sound(head, Sound.ENTITY_GHAST_AMBIENT, 1.0f, 0.6f);
            Fx.sound(head, Sound.BLOCK_BELL_RESONATE, 1.2f, 0.6f);
            ctx().announce(Text.mm("<" + COLOR_TAG + ">☠</" + COLOR_TAG + "> <white><owner></white><gray>이(가) "
                            + doomed.size() + "명의 죽음을 예고했습니다 — </gray><" + COLOR_TAG + "><b>누가 먼저 갈까</b></"
                            + COLOR_TAG + ">",
                    Placeholder.component("owner", owner.name())));
            pickBearer(0);
            Loop.repeat(ctx(), owner, 1, 1, duration, this, this::end);
        }

        @Override
        public boolean test(long tick) {
            if (done) {
                return false;
            }
            long left = duration - tick - 1;
            prune();
            if (doomed.isEmpty()) {
                fizzle();
                return false;
            }
            pickBearer(tick);
            dress(tick, left);
            if (tick % WAIL_PERIOD == 0) {
                wail(tick / WAIL_PERIOD);
            }
            if (left <= 0) {
                reap();
                return false;
            }
            return true;
        }

        /** Drops the doomed that died, left the match or slipped out of sight. */
        private void prune() {
            for (Iterator<LivingEntity> it = doomed.iterator(); it.hasNext(); ) {
                LivingEntity target = it.next();
                if (!Combat.isTargetable(ctx(), owner, target) || isHidden(owner, target)
                        || !target.getWorld().equals(owner.getWorld())) {
                    it.remove();
                    TextDisplay counter = counters.remove(target.getUniqueId());
                    if (counter != null && counter.isValid()) {
                        counter.remove();
                    }
                }
            }
        }

        /** The skull goes to the doomed with the least health; it flies over when that changes. */
        private void pickBearer(long tick) {
            LivingEntity lowest = doomed.stream().min(Comparator.comparingDouble(LivingEntity::getHealth)).orElse(null);
            if (lowest == null) {
                return;
            }
            if (bearer != null && doomed.contains(bearer) && bearer.getHealth() <= lowest.getHealth() + 1.0E-9) {
                lowest = bearer;
            }
            if (lowest.equals(bearer) && skull != null && skull.isValid()) {
                return;
            }
            boolean moved = bearer != null;
            bearer = lowest;
            Location over = skullSpot(lowest, tick);
            if (skull == null || !skull.isValid()) {
                if (!reserve(1)) {
                    return;
                }
                skull = Displays.item(ctx(), owner, over, new ItemStack(SKULL_ITEM), skullPose(tick), duration + 10);
                skull.setBillboard(Display.Billboard.VERTICAL);
                skull.setViewRange(2.5f);
                Displays.outline(skull, SILVER);
                displays.add(skull);
                skull.setTeleportDuration(1);
                Fx.sound(over, Sound.ENTITY_SKELETON_AMBIENT, 1.0f, 0.6f);
                return;
            }
            if (moved) {
                skull.setTeleportDuration((int) SKULL_FLIGHT);
                Displays.moveTo(skull, over);
                flightUntil = ctx().currentTick() + SKULL_FLIGHT;
                Fx.sound(over, Sound.ENTITY_SKELETON_AMBIENT, 1.0f, 0.8f + Fx.jitter(0.1f));
                Fx.sound(over, Sound.BLOCK_BONE_BLOCK_BREAK, 0.8f, 0.7f);
                Vfx.trail(skull.getLocation(), over, SILVER, (int) SKULL_FLIGHT, true);
                owner.sendActionBar(Text.mm("<" + DOOM_TAG + ">☠ 해골이 <white><target></white>에게로</" + DOOM_TAG
                        + ">", Placeholder.component("target", lowest.name())));
            }
        }

        /** Keeps the countdowns, the skull and the lantern on their heads; mist coils round the doomed. */
        private void dress(long tick, long left) {
            long now = ctx().currentTick();
            ItemDisplay held = lantern;
            if (held != null && held.isValid()) {
                Displays.moveTo(held, lanternOverhead(tick));
                Displays.animate(held, Displays.centered(0, 0, 0, 0.7f, 0.7f, 0.7f, tick * 3f, 0,
                        (float) Math.sin(tick * 0.25) * 12f), 1);
            }
            ItemDisplay head = skull;
            if (head != null && head.isValid() && bearer != null && now >= flightUntil) {
                head.setTeleportDuration(1);
                Displays.moveTo(head, skullSpot(bearer, tick));
                Displays.animate(head, skullPose(tick), 1);
            }
            for (LivingEntity target : doomed) {
                TextDisplay counter = counters.get(target.getUniqueId());
                if (counter != null && counter.isValid()) {
                    Displays.moveTo(counter, counterSpot(target));
                    if (tick % 2 == 0) {
                        counter.text(counterText(left, target.equals(bearer)));
                    }
                }
                if (tick % 3 == 0) {
                    double angle = tick * 0.35 + target.getEntityId();
                    Location feet = target.getLocation();
                    double r = 0.75;
                    Location mist = feet.clone().add(Math.cos(angle) * r, 0.2 + (tick % 24) * 0.06,
                            Math.sin(angle) * r);
                    Vfx.dot(mist, Particle.DUST, target.equals(bearer) ? BLACK_DUST : MIST_DUST, false);
                }
            }
        }

        /** The banshee wails, louder and higher every second. */
        private void wail(long second) {
            Location head = owner.getEyeLocation();
            float pitch = (float) Math.min(1.4, 0.5 + second * 0.12);
            Fx.sound(head, Sound.ENTITY_ALLAY_HURT, 1.0f + second * 0.1f, pitch);
            Fx.sound(head, Sound.ENTITY_VEX_AMBIENT, 0.8f, pitch);
            if (bearer != null) {
                Fx.sound(bearer.getLocation(), Sound.BLOCK_BELL_RESONATE, 0.6f, 0.5f + second * 0.1f);
            }
            Vfx.ring(ctx(), owner.getLocation().add(0, 0.15, 0), 1.2 + second * 0.3, 18, second, Particle.DUST,
                    MIST_DUST, true);
        }

        /** The time is up: the skull's bearer is taken, the others shiver. */
        private void reap() {
            done = true;
            boolean solo = doomed.size() == 1;
            LivingEntity taken = bearer != null && doomed.contains(bearer) ? bearer
                    : doomed.stream().min(Comparator.comparingDouble(LivingEntity::getHealth)).orElse(null);
            for (LivingEntity target : List.copyOf(doomed)) {
                if (target.equals(taken)) {
                    continue;
                }
                Combat.hit(ctx(), owner, target, params().num("omen_others"));
                if (Combat.isTargetable(ctx(), owner, target)) {
                    Vfx.cloud(ctx(), Fx.body(target), Particle.WHITE_ASH, 10, 0.3, 0.02, null, false);
                    popWord(target.getLocation().add(0, target.getHeight() + 0.6, 0), "<gray><i>…살았다</i></gray>");
                }
            }
            if (taken != null) {
                take(taken, solo);
            }
            close();
        }

        private void take(LivingEntity taken, boolean solo) {
            Location body = Fx.body(taken);
            Combat.hit(ctx(), owner, taken, params().num(solo ? "omen_solo" : "omen_take"));
            long silence = params().ticks("omen_silence");
            if (silence > 0 && taken instanceof Player victim && ctx().isAlive(victim)) {
                ctx().silence(victim, silence);
            }
            World world = body.getWorld();
            Vfx.dot(body, Particle.FLASH, SILVER, true);
            Vfx.burst(ctx(), body, PALETTE, 0.8, Particle.SOUL, true);
            Vfx.helix(ctx(), taken.getLocation().add(0, 0.05, 0), 0.7, taken.getHeight() + 1.2, 2, 3, 12, 0,
                    Particle.DUST_COLOR_TRANSITION, MIST_FADE, true);
            world.spawnParticle(Particle.SCULK_SOUL, body, Vfx.n(ctx(), 12), 0.3, 0.6, 0.3, 0.05, null, true);
            ItemDisplay head = skull;
            if (head != null && head.isValid()) {
                world.spawnParticle(Particle.ITEM, head.getLocation(), Vfx.n(ctx(), 14), 0.2, 0.2, 0.2, 0.12,
                        new ItemStack(BONE_ITEM), true);
            }
            Fx.sound(body, Sound.ENTITY_ALLAY_DEATH, 1.4f, 0.5f);
            Fx.sound(body, Sound.ENTITY_WARDEN_SONIC_BOOM, 0.35f, 1.7f);
            Fx.sound(body, Sound.BLOCK_BELL_USE, 1.2f, 0.5f);
            popWord(taken.getLocation().add(0, taken.getHeight() + 0.7, 0),
                    "<b><" + BLOOD_TAG + ">데려간다</" + BLOOD_TAG + "></b>");
            // Its soul is led away: wisps rise from where it stood for a moment.
            Location feet = taken.getLocation();
            Loop.repeat(ctx(), owner, 2, 2, 10, run -> {
                Location wisp = Vfx.scatter(feet, 0.5).add(0, 0.3 + run * 0.22, 0);
                Vfx.dot(wisp, Particle.SOUL, null, false);
                Vfx.dot(wisp.clone().add(0, 0.25, 0), Particle.DUST, MIST_DUST, false);
                return true;
            });
            owner.sendActionBar(Text.mm("<" + DOOM_TAG + "><b>☠ <target></b></" + DOOM_TAG + "><gray>을(를) "
                    + "데려갑니다</gray>", Placeholder.component("target", taken.name())));
        }

        /** Nobody is left to take: the wail dies away. */
        private void fizzle() {
            done = true;
            Fx.sound(owner.getEyeLocation(), Sound.ENTITY_ALLAY_HURT, 0.6f, 0.4f);
            owner.sendActionBar(Text.mm("<gray>예고된 자가 모두 사라졌습니다</gray>"));
            close();
        }

        private void close() {
            for (TextDisplay counter : counters.values()) {
                if (counter.isValid()) {
                    counter.remove();
                }
            }
            counters.clear();
            for (Display part : new Display[] {skull, lantern}) {
                if (part != null && part.isValid()) {
                    Displays.shrinkAway(ctx(), owner, part, 5);
                }
            }
        }

        void end() {
            if (!done) {
                done = true;
                close();
            }
        }

        private Location lanternOverhead(long tick) {
            return Displays.unrotated(owner.getLocation().add(0, owner.getHeight() + 0.9 + Math.sin(tick * 0.2) * 0.06,
                    0));
        }

        private Location skullSpot(LivingEntity target, long tick) {
            return target.getLocation().add(0, target.getHeight() + SKULL_ABOVE + Math.sin(tick * 0.3) * 0.06, 0);
        }

        private Location counterSpot(LivingEntity target) {
            return target.getLocation().add(0, target.getHeight() + COUNT_ABOVE, 0);
        }

        private Component counterText(long ticksLeft, boolean bearer) {
            String tag = bearer ? BLOOD_TAG : SILVER_TAG;
            return Text.mm("<b><" + tag + ">☠ " + Fx.seconds(Math.max(0, ticksLeft)) + "</" + tag + "></b>");
        }

        private void popWord(Location at, String text) {
            if (!reserve(1)) {
                return;
            }
            TextDisplay word = Displays.text(ctx(), owner, at, Text.mm(text), 0.6f, 20);
            word.setViewRange(2.0f);
            displays.add(word);
            Displays.animateNextTick(ctx(), owner, word, Displays.centered(0, 0.3f, 0, 1.1f, 1.1f, 1.1f, 0, 0, 0), 5);
        }
    }

    /** The skull: turned a little from side to side (it faces viewers through its billboard). */
    private static Transformation skullPose(long tick) {
        float tilt = (float) (Math.sin(tick * 0.2) * 8);
        return Displays.centered(0, 0, 0, 0.55f, 0.55f, 0.55f, 0, 0, tilt);
    }

    // ------------------------------------------------------------------ helpers

    /** The nearest enemy (visible to the banshee) whose padded hitbox the segment from {@code start} crosses. */
    private @Nullable LivingEntity firstEnemyOnPath(Player owner, World world, Vector start, Vector direction,
                                                   double reach) {
        Location middle = start.clone().add(direction.clone().multiply(reach / 2)).toLocation(world);
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity enemy : ctx().enemiesNear(owner, middle, reach / 2 + 1.5)) {
            if (isHidden(owner, enemy)) {
                continue;
            }
            BoundingBox box = enemy.getBoundingBox().expand(CLOTH_PAD);
            double distance;
            if (box.contains(start)) {
                distance = 0;
            } else {
                RayTraceResult crossing = box.rayTrace(start, direction, Math.max(reach, 1.0E-3));
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

    /** Where the shroud leaves the banshee: just in front of her right hand. */
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
        return Displays.unrotated(eye.add(direction.multiply(0.6)).add(right.multiply(0.25)).subtract(0, 0.3, 0));
    }

    /** Minecraft's yaw (degrees: 0 = +Z, 90 = -X) of the horizontal part of {@code direction}, 0 if it has none. */
    private static float yawOf(Vector direction) {
        Vector flat = Motion.horizontal(direction);
        if (flat.lengthSquared() < 1.0E-6) {
            return 0;
        }
        return (float) Math.toDegrees(Math.atan2(-flat.getX(), flat.getZ()));
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
