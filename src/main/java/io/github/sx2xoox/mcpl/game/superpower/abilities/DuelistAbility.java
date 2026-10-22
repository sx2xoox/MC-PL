package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.List;
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
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.tag.DamageTypeTags;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 결투가 — a duellist who wants every fight to be one against one. Fights better when only one enemy is near,
 * challenges one enemy to a duel (more damage to it, less from everyone else), and as the ultimate raises a ring of
 * golden glass around itself and one enemy: neither can leave it and nobody outside can touch them (nor be touched).
 *
 * <p>Look: gold and crimson. The one-on-one stance shows a slow golden ring at the feet; a challenge rings a bell,
 * sends a stream of gold to the target and hangs a golden ⚔ over its head; boosted hits flash gold. 결투장 rises as a
 * sweeping circle of golden glass panels with crimson and gold trims, a ⚔ countdown floating above; blocked
 * interference flashes the panel it struck, and the panels shatter outwards when the duel is won.
 */
public final class DuelistAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("duelist", "결투가", Material.GOLDEN_SWORD)
            .color(TextColor.color(0xF2B632))
            .difficulty(2)
            .passive("일기토",
                    "반경 {duel_radius}블록 안에 적이 한 명뿐이면 그 적에게 주는 피해가 {duel_offense}% 증가하고 "
                            + "받는 피해가 {duel_defense}% 감소합니다.")
            .active("결투 신청",
                    "조준한 적({challenge_range}블록)에게 {challenge_duration}초간 결투 표식을 겁니다. 표식이 붙은 적에게 "
                            + "주는 피해가 {challenge_bonus}% 증가하고, 다른 적에게 받는 피해는 {challenge_guard}% 감소합니다.",
                    12)
            .ultimate("결투장",
                    "조준한(없으면 가장 가까운) {ult_range}블록 안의 적과 함께 반경 {ult_radius}블록의 결투장에 "
                            + "{ult_duration}초간 들어갑니다. 둘 다 결투장을 벗어날 수 없고, 바깥과는 서로 피해를 주고받을 수 "
                            + "없으며, 다른 적은 밀려납니다. 결투 중에는 일기토가 항상 적용되고, 상대를 쓰러뜨리면 체력을 "
                            + "{ult_victory_heal} 회복합니다.")
            .ultimateNotStandalone()
            .param("duel_radius", 8)
            .param("duel_offense", 15)
            .param("duel_defense", 15)
            .param("challenge_range", 20)
            .param("challenge_duration", 6)
            .param("challenge_bonus", 30)
            .param("challenge_guard", 30)
            .param("ult_range", 12)
            .param("ult_radius", 7)
            .param("ult_duration", 6)
            .param("ult_victory_heal", 6)
            .build();

    private static final String COLOR_TAG = "#F2B632";
    private static final String CRIMSON_TAG = "#C0283B";
    private static final double TAU = Math.PI * 2;

    // Passive
    private static final long STANCE_PERIOD = 10;

    // Challenge
    private static final long MARK_FX_PERIOD = 2;
    private static final double MARK_HEIGHT = 0.75;

    // Arena
    private static final int PANELS = 20;
    private static final float PANEL_HEIGHT = 2.6f;
    private static final float PANEL_THICKNESS = 0.04f;
    /** How close to the wall (inside) a duellist gets before the wall pushes it back. */
    private static final double WALL_MARGIN = 0.7;
    private static final double WALL_PUSH = 0.55;
    /** A duellist found this far outside the wall (a teleport, a launch) is brought back in. */
    private static final double ESCAPE_DISTANCE = 1.5;
    private static final double REPEL_PUSH = 0.9;
    private static final int RISE_TICKS = 6;
    private static final long RISE_SWEEP = 8;
    private static final int SINK_TICKS = 6;
    private static final long FLASH_TICKS = 3;
    private static final double GROUND_SNAP = 2.5;
    private static final long TRIM_PERIOD = 3;

    // Visuals
    /** Pale gold core, gold body, crimson edge. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFF4C7, 0xF2B632, 0x8B1E2D);
    private static final Color CRIMSON = Color.fromRGB(0xC0283B);
    private static final Particle.DustOptions CRIMSON_DUST = new Particle.DustOptions(CRIMSON, 1.1f);
    private static final Particle.DustOptions GOLD_DUST = new Particle.DustOptions(PALETTE.body(), 0.9f);
    private static final Material PANEL = Material.YELLOW_STAINED_GLASS;
    private static final Material PANEL_FLASH = Material.WHITE_STAINED_GLASS;
    private static final TextColor GOLD = TextColor.color(0xF2B632);
    private static final Component EMBLEM = Component.text("⚔", GOLD, TextDecoration.BOLD);

    private @Nullable LivingEntity marked;
    private long markUntil = -1;
    private int markToken;
    private boolean stanceShown;
    private @Nullable Arena arena;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        Arena current = arena;
        if (current != null) {
            current.release();
        }
        arena = null;
        marked = null;
        markUntil = -1;
        markToken++;
    }

    // ------------------------------------------------------------------ damage rules

    /** The one enemy the duellist faces alone, or null: the arena's opponent, else the only enemy within range. */
    private @Nullable LivingEntity loneEnemy() {
        Player owner = player();
        Arena current = arena;
        if (current != null && Combat.isTargetable(ctx(), owner, current.target)) {
            return current.target;
        }
        double radius = Math.max(0, params().num("duel_radius"));
        List<LivingEntity> near = ctx().enemiesNear(owner, owner.getLocation(), radius);
        return near.size() == 1 ? near.getFirst() : null;
    }

    private boolean isMarked(Entity entity) {
        LivingEntity current = marked;
        return current != null && ctx().currentTick() < markUntil && current.equals(entity);
    }

    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled() || event.getDamage() <= 0) {
            return;
        }
        double multiplier = 1;
        if (victim.equals(loneEnemy())) {
            multiplier *= 1 + Math.max(0, params().num("duel_offense")) / 100.0;
        }
        if (isMarked(victim)) {
            multiplier *= 1 + Math.max(0, params().num("challenge_bonus")) / 100.0;
        }
        if (multiplier != 1) {
            event.setDamage(event.getDamage() * multiplier);
        }
    }

    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (ctx().isAbilityDamage() || !player().equals(event.getDamager())) {
            return;
        }
        boolean challenged = isMarked(victim);
        if (!challenged && !victim.equals(loneEnemy())) {
            return;
        }
        // A boosted blow: a flash of gold where the blades meet, a crimson spark and a ringing clang.
        Location body = Fx.body(victim);
        World world = body.getWorld();
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, body, Vfx.n(ctx(), challenged ? 10 : 6), 0.25, 0.3, 0.25,
                0, PALETTE.glow(1.0f));
        world.spawnParticle(Particle.CRIT, body, Vfx.n(ctx(), 6), 0.2, 0.25, 0.2, 0.25);
        Vfx.ring(ctx(), body, 0.45, 10, ThreadLocalRandom.current().nextDouble(TAU), Particle.DUST,
                challenged ? CRIMSON_DUST : GOLD_DUST, false);
        Fx.sound(body, Sound.BLOCK_ANVIL_LAND, 0.25f, 1.8f + Fx.jitter(0.1f));
        if (challenged) {
            Fx.sound(body, Sound.BLOCK_BELL_USE, 0.4f, 1.6f + Fx.jitter(0.1f));
        }
    }

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled() || event.getDamage() <= 0) {
            return;
        }
        Tag<DamageType> bypass = DamageTypeTags.BYPASSES_INVULNERABILITY;
        if (bypass != null && bypass.isTagged(event.getDamageSource().getDamageType())) {
            return;
        }
        double multiplier = 1;
        if (loneEnemy() != null) {
            multiplier *= 1 - Math.clamp(params().num("duel_defense") / 100.0, 0.0, 1.0);
        }
        LivingEntity challenged = marked;
        if (challenged != null && ctx().currentTick() < markUntil
                && event instanceof EntityDamageByEntityEvent byEntity) {
            Entity damager = byEntity.getDamager();
            Player attacker = ctx().resolveAttacker(damager);
            boolean fromChallenged = challenged.equals(damager) || challenged.equals(attacker);
            boolean fromEnemy = ctx().isEnemy(player(), damager) || (attacker != null && ctx().isEnemy(player(),
                    attacker));
            if (fromEnemy && !fromChallenged) {
                multiplier *= 1 - Math.clamp(params().num("challenge_guard") / 100.0, 0.0, 1.0);
                guardVisual(byEntity);
            }
        }
        if (multiplier != 1) {
            event.setDamage(event.getDamage() * multiplier);
        }
    }

    /** Someone else's hit glances off during a challenge: a crimson spark between the duellist and the attacker. */
    private void guardVisual(EntityDamageByEntityEvent event) {
        Player owner = player();
        Location chest = owner.getLocation().add(0, owner.getHeight() * 0.6, 0);
        Vector toward = Motion.horizontal(event.getDamager().getLocation().toVector().subtract(chest.toVector()));
        Location at = chest.clone().add(toward.multiply(0.6));
        Vfx.cloud(ctx(), at, Particle.ENCHANTED_HIT, 5, 0.2, 0.1, null, false);
        Vfx.dot(at, Particle.DUST, CRIMSON_DUST, false);
        Fx.sound(at, Sound.ITEM_SHIELD_BLOCK, 0.3f, 1.6f + Fx.jitter(0.1f));
    }

    // ------------------------------------------------------------------ passive: stance feedback

    @Override
    public void onTick(long tick) {
        if (tick % STANCE_PERIOD != 0) {
            return;
        }
        Player owner = player();
        LivingEntity lone = loneEnemy();
        if ((lone != null) != stanceShown) {
            stanceShown = lone != null;
            if (stanceShown) {
                owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">⚔ 일기토</" + COLOR_TAG + "> <gray>"
                        + "<name>와(과) 1:1</gray>", Placeholder.component("name", lone.name())));
                Fx.sound(owner.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.5f, 1.5f + Fx.jitter(0.1f));
            }
        }
        if (lone == null) {
            return;
        }
        Location feet = owner.getLocation().add(0, 0.08, 0);
        Vfx.ring(ctx(), feet, 0.65, 12, tick * 0.05, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(0.7f), false);
        if (tick % (STANCE_PERIOD * 2) == 0 && lone.getWorld().equals(feet.getWorld())) {
            Location chest = feet.clone().add(0, owner.getHeight() * 0.5, 0);
            Location toward = chest.clone().add(Motion.horizontal(lone.getLocation().toVector()
                    .subtract(chest.toVector())).multiply(1.4));
            Vfx.trail(chest, toward, PALETTE.body(), 6, false);
        }
    }

    // ------------------------------------------------------------------ active: challenge

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        double range = Math.max(1, params().num("challenge_range"));
        LivingEntity target = ctx().findTarget(owner, range);
        // An enemy hidden by stealth cannot be challenged: the golden mark would give it away to everyone.
        if (target == null || !seen(owner, target)) {
            return ActivationResult.fail("시야에 결투할 적이 없습니다");
        }
        long duration = Math.max(1, params().ticks("challenge_duration"));
        marked = target;
        markUntil = ctx().currentTick() + duration;
        int token = ++markToken;
        TextDisplay emblem = Displays.text(ctx(), owner, emblemSpot(target), EMBLEM, 0.4f, duration + 10);
        emblem.setTeleportDuration(2);
        Displays.animateNextTick(ctx(), owner, emblem, Displays.centered(0, 0, 0, 1.6f, 1.6f, 1.6f, 0, 0, 0), 4);
        challengeCast(owner, target);

        boolean[] veiled = {false};
        Loop.repeat(ctx(), owner, MARK_FX_PERIOD, MARK_FX_PERIOD, duration / MARK_FX_PERIOD, run -> {
            if (token != markToken || !Combat.isTargetable(ctx(), owner, target)) {
                return false;
            }
            // The challenge holds through stealth, but its mark never shows where a stealthed enemy is.
            boolean hidden = !seen(owner, target);
            if (emblem.isValid()) {
                if (hidden != veiled[0]) {
                    veiled[0] = hidden;
                    emblem.text(hidden ? Component.empty() : EMBLEM);
                }
                emblem.teleport(emblemSpot(target));
                if (run % 10 == 0) {
                    float pulse = run % 20 == 0 ? 1.9f : 1.6f;
                    Displays.animate(emblem, Displays.centered(0, 0, 0, pulse, pulse, pulse, 0, 0, 0), 5);
                }
            }
            if (!hidden) {
                markAura(owner, target, run);
            }
            return true;
        }, () -> {
            if (emblem.isValid()) {
                Displays.shrinkAway(ctx(), owner, emblem, 5);
            }
            if (token == markToken) {
                marked = null;
                markUntil = -1;
            }
        });
        return ActivationResult.success();
    }

    private static Location emblemSpot(LivingEntity target) {
        Location at = target.getLocation().add(0, target.getHeight() + MARK_HEIGHT, 0);
        at.setYaw(0);
        at.setPitch(0);
        return at;
    }

    /** The challenge: a bell, a stream of gold flying to the target and a ring snapping round its feet. */
    private void challengeCast(Player owner, LivingEntity target) {
        Location chest = owner.getLocation().add(0, owner.getHeight() * 0.6, 0);
        Location targetChest = Fx.body(target);
        World world = chest.getWorld();
        if (targetChest.getWorld().equals(world)) {
            int travel = (int) Math.max(4, Math.min(16, chest.distance(targetChest) * 0.8));
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int motes = Vfx.n(ctx(), 10);
            for (int i = 0; i < motes; i++) {
                Vfx.trail(Vfx.scatter(chest, 0.3).add(0, random.nextDouble(-0.2, 0.2), 0), targetChest,
                        i % 3 == 0 ? CRIMSON : PALETTE.body(), travel + random.nextInt(4), true);
            }
            ctx().runLater(owner, travel, () -> {
                if (target.isValid()) {
                    Location feet = target.getLocation().add(0, 0.1, 0);
                    Vfx.ring(ctx(), feet, 0.9, 16, 0, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.2f), true);
                    Vfx.burst(ctx(), Fx.body(target), PALETTE, 0.5, Particle.WAX_OFF, true);
                    Fx.sound(feet, Sound.ITEM_ARMOR_EQUIP_GOLD, 1.0f, 0.8f + Fx.jitter(0.05f));
                }
            });
        }
        world.spawnParticle(Particle.FLASH, chest, 1, 0, 0, 0, 0, PALETTE.core());
        Vfx.ring(ctx(), owner.getLocation().add(0, 0.1, 0), 0.8, 14, 0, Particle.DUST_COLOR_TRANSITION,
                PALETTE.glow(1.0f), false);
        Fx.sound(chest, Sound.BLOCK_BELL_USE, 1.0f, 1.0f);
        Fx.sound(chest, Sound.ITEM_ARMOR_EQUIP_GOLD, 0.8f, 1.2f + Fx.jitter(0.05f));
        Fx.sound(chest, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.5f, 1.4f + Fx.jitter(0.1f));
        String seconds = AbilityInfo.formatNumber(params().num("challenge_duration"));
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">⚔ 결투 신청</" + COLOR_TAG + "> <gray>→</gray> <white><name>"
                + "</white> <gray>" + seconds + "초</gray>", Placeholder.component("name", target.name())));
        if (target instanceof Player victim) {
            victim.sendActionBar(Text.mm("<" + CRIMSON_TAG + ">⚔</" + CRIMSON_TAG + "> <white><name></white>"
                    + "<gray>이(가) 결투를 신청했습니다!</gray>", Placeholder.unparsed("name", owner.getName())));
            victim.playSound(victim, Sound.BLOCK_BELL_USE, 0.8f, 0.9f);
        }
    }

    /** The challenge in progress: a crimson ring at the target's feet and now and then a thread of gold to it. */
    private void markAura(Player owner, LivingEntity target, long run) {
        Location feet = target.getLocation().add(0, 0.08, 0);
        if (run % 2 == 0) {
            Vfx.ring(ctx(), feet, 0.75, 12, run * 0.15, Particle.DUST, CRIMSON_DUST, false);
        }
        if (run % 5 == 0 && feet.getWorld().equals(owner.getWorld())) {
            Location chest = owner.getLocation().add(0, owner.getHeight() * 0.6, 0);
            Vfx.trail(chest, Fx.body(target), PALETTE.body(), 8, false);
        }
    }

    // ------------------------------------------------------------------ ultimate: dueling ground

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        if (arena != null) {
            return ActivationResult.fail("이미 결투 중입니다");
        }
        double range = Math.max(1, params().num("ult_range"));
        LivingEntity target = ctx().findTarget(owner, range);
        if (target == null || !seen(owner, target)) {
            // The nearest enemy the duellist can see: nobody is dragged into a duel out of stealth.
            target = null;
            for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), range)) {
                if (seen(owner, enemy)) {
                    target = enemy;
                    break;
                }
            }
        }
        if (target == null || !target.getWorld().equals(owner.getWorld())) {
            return ActivationResult.fail("반경 " + AbilityInfo.formatNumber(range) + "블록 안에 결투할 적이 없습니다");
        }
        Arena created = new Arena(owner, target);
        arena = created;
        created.open();
        Loop.repeat(ctx(), owner, 1, 1, created.duration, created, created::close);
        return ActivationResult.success();
    }

    /** One 결투장: the ring, who is inside it and the interference guard. */
    private final class Arena implements LongPredicate, Listener {

        private final LivingEntity target;
        private final UUID ownerId;
        private final UUID targetId;
        private final Location center;
        private final double radius = Math.max(2, params().num("ult_radius"));
        private final long duration = Math.max(1, params().ticks("ult_duration"));
        private final List<BlockDisplay> panels = new ArrayList<>();
        private final double spin = ThreadLocalRandom.current().nextDouble(TAU);
        private @Nullable TextDisplay timer;
        private boolean registered;
        private boolean finished;
        private long lastFlash = Long.MIN_VALUE / 2; // "/ 2": now - this must not overflow

        Arena(Player owner, LivingEntity target) {
            this.target = target;
            this.ownerId = owner.getUniqueId();
            this.targetId = target.getUniqueId();
            Location a = owner.getLocation();
            Location b = target.getLocation();
            Location middle = new Location(a.getWorld(), (a.getX() + b.getX()) / 2, Math.min(a.getY(), b.getY()),
                    (a.getZ() + b.getZ()) / 2);
            this.center = middle;
        }

        void open() {
            Player owner = player();
            Bukkit.getPluginManager().registerEvents(this, ctx().plugin());
            registered = true;
            raiseWall(owner);
            timer = Displays.text(ctx(), owner, center.clone().add(0, PANEL_HEIGHT + 1.2, 0), timerText(duration), 2.2f,
                    duration + 20);

            World world = center.getWorld();
            Location at = center.clone().add(0, 0.1, 0);
            world.spawnParticle(Particle.FLASH, at.clone().add(0, 1, 0), 1, 0, 0, 0, 0, PALETTE.core(), true);
            Vfx.shockwave(ctx(), owner, at, 0.5, radius, 7, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.5f), true);
            Vfx.shockwave(ctx(), owner, at.clone().add(0, 0.05, 0), 0.3, radius * 0.9, 9, Particle.DUST, CRIMSON_DUST,
                    true);
            for (LivingEntity duelist : List.of(owner, target)) {
                Vfx.helix(ctx(), duelist.getLocation(), 0.6, duelist.getHeight() + 0.4, 1.25, 2, 10, spin,
                        Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.0f), true);
            }
            Fx.sound(center, Sound.BLOCK_BELL_USE, 1.4f, 0.8f);
            Fx.sound(center, Sound.ITEM_GOAT_HORN_SOUND_0, 1.0f, 1.0f);
            Fx.sound(center, Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.4f);
            Fx.sound(center, Sound.ITEM_ARMOR_EQUIP_GOLD, 1.0f, 0.7f);
            ctx().runLater(owner, 6, () -> Fx.sound(center, Sound.BLOCK_BELL_USE, 1.2f, 1.0f));

            String seconds = AbilityInfo.formatNumber(params().num("ult_duration"));
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>⚔ 결투장</b></" + COLOR_TAG + "> <gray>vs</gray> "
                    + "<white><name></white> <gray>" + seconds + "초</gray>", Placeholder.component("name",
                    target.name())));
            if (target instanceof Player victim) {
                victim.sendActionBar(Text.mm("<" + CRIMSON_TAG + "><b>⚔ 결투장</b></" + CRIMSON_TAG + "> <white>"
                        + "<name></white><gray>와(과)의 결투에서 벗어날 수 없습니다!</gray>",
                        Placeholder.unparsed("name", owner.getName())));
            }
            ctx().announce(Text.mm("<" + COLOR_TAG + ">⚔</" + COLOR_TAG + "> <white><owner></white><gray>이(가) "
                    + "</gray><white><target></white><gray>에게 </gray><" + COLOR_TAG + ">결투</" + COLOR_TAG
                    + "><gray>를 걸었습니다!</gray>", Placeholder.unparsed("owner", owner.getName()),
                    Placeholder.component("target", target.name())));
        }

        /** The ring of golden glass panels rising from the ground in a sweep around the circle. */
        private void raiseWall(Player owner) {
            Location anchor = center.clone();
            anchor.setYaw(0);
            anchor.setPitch(0);
            float width = (float) (2 * radius * Math.sin(Math.PI / PANELS) * 0.96);
            long lifetime = duration + RISE_SWEEP + RISE_TICKS + SINK_TICKS + 20;
            for (int i = 0; i < PANELS; i++) {
                double angle = spin + i * TAU / PANELS;
                BlockDisplay panel = Displays.block(ctx(), owner, anchor, PANEL.createBlockData(),
                        panelPose(angle, width, 0.02f), lifetime);
                panels.add(panel);
                long delay = 1 + i * RISE_SWEEP / PANELS;
                Transformation up = panelPose(angle, width, PANEL_HEIGHT);
                ctx().runLater(owner, delay, () -> Displays.animate(panel, up, RISE_TICKS));
            }
            Fx.sound(center, Sound.BLOCK_GLASS_PLACE, 1.2f, 0.8f);
        }

        private Transformation panelPose(double angle, float width, float height) {
            float x = (float) (Math.cos(angle) * radius);
            float z = (float) (Math.sin(angle) * radius);
            float yaw = (float) Math.toDegrees(angle) - 90;
            return Displays.transform(x, height / 2, z, width, height, PANEL_THICKNESS, yaw, 0, 0);
        }

        @Override
        public boolean test(long run) {
            Player owner = player();
            if (finished) {
                return false;
            }
            if (!target.isValid() || target.isDead()
                    || (target instanceof Player opponent && !ctx().isAlive(opponent))) {
                victory(owner);
                return false;
            }
            if (!target.getWorld().equals(center.getWorld()) || !owner.getWorld().equals(center.getWorld())) {
                return false;
            }
            contain(owner);
            contain(target);
            repel(owner);
            if (run % TRIM_PERIOD == 0) {
                trims(run);
            }
            if (timer != null && timer.isValid()) {
                long left = duration - run - 1;
                if (left % 20 == 0) {
                    timer.text(timerText(left));
                    if (left > 0 && left <= 60) {
                        Fx.sound(center, Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f, 1.6f);
                    }
                }
            }
            if (run % 5 == 0) {
                for (LivingEntity duelist : List.of(owner, target)) {
                    if (!seen(owner, duelist)) {
                        continue; // an opponent that slipped into stealth is not pointed out
                    }
                    Vfx.ring(ctx(), duelist.getLocation().add(0, 0.08, 0), 0.6, 10, run * 0.2,
                            Particle.DUST_COLOR_TRANSITION, duelist == owner ? PALETTE.glow(0.8f) : PALETTE.fade(0.8f),
                            false);
                }
            }
            return true;
        }

        /** Keeps a duellist inside: pushed back off the wall, and brought back in if it got out anyway. */
        private void contain(LivingEntity duelist) {
            Location at = duelist.getLocation();
            if (!ctx().isInsideArena(at)) {
                return; // the closing border outranks the duel: nobody is held where the border hurts
            }
            Vector offset = new Vector(at.getX() - center.getX(), 0, at.getZ() - center.getZ());
            double distance = offset.length();
            if (distance <= radius - WALL_MARGIN) {
                return;
            }
            Vector inward = distance > 1.0E-6 ? offset.clone().multiply(-1 / distance) : new Vector();
            if (distance > radius + ESCAPE_DISTANCE) {
                bringBack(duelist, offset, distance);
                return;
            }
            Vector velocity = duelist.getVelocity();
            double outward = -velocity.clone().setY(0).dot(inward);
            Vector push = velocity.clone().add(inward.clone().multiply(WALL_PUSH + Math.max(0, outward)));
            push.setY(Math.max(velocity.getY(), 0.12));
            duelist.setVelocity(Motion.clamp(push, 1.2));
            flashPanel(at, false);
        }

        /** A duellist that got out (teleported, launched) is put back just inside the wall, or at the center. */
        private void bringBack(LivingEntity duelist, Vector offset, double distance) {
            Body body = Body.standing(duelist);
            double y = Math.max(center.getY(), duelist.getLocation().getY());
            Location[] tries = {
                    center.clone().add(offset.clone().multiply((radius - 1.5) / distance)),
                    center.clone()};
            for (Location spot : tries) {
                spot.setY(y + 1);
                Spots.snapToGround(spot.getWorld(), spot, GROUND_SNAP + Math.max(0, y - center.getY()));
                spot.setYaw(duelist.getLocation().getYaw());
                spot.setPitch(duelist.getLocation().getPitch());
                if (Spots.isSafe(ctx(), spot, body) && duelist.teleport(spot, TeleportCause.PLUGIN)) {
                    duelist.setFallDistance(0);
                    Vfx.cloud(ctx(), Fx.body(duelist), Particle.DUST_COLOR_TRANSITION, 12, 0.3, 0,
                            PALETTE.glow(1.0f), true);
                    Fx.sound(spot, Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.4f);
                    return;
                }
            }
        }

        /** Other enemies are thrown out of the ring. Allies of the duellist are left alone (they cannot interfere). */
        private void repel(Player owner) {
            for (LivingEntity enemy : ctx().enemiesNear(owner, center.clone().add(0, 1, 0), radius + 0.3)) {
                if (enemy.getUniqueId().equals(targetId)) {
                    continue;
                }
                Location at = enemy.getLocation();
                Vector out = Motion.horizontal(new Vector(at.getX() - center.getX(), 0, at.getZ() - center.getZ()));
                if (out.lengthSquared() < 1.0E-6) {
                    out = Motion.horizontalFacing(at);
                }
                if (Motion.push(enemy, out.multiply(REPEL_PUSH).setY(0.3), 1.5)) {
                    flashPanel(at, true);
                }
            }
        }

        /** The gold trim along the top of the wall and the crimson one on the ground, slowly turning. */
        private void trims(long run) {
            int points = (int) Math.max(20, Math.min(40, radius * 5));
            Vfx.ring(ctx(), center.clone().add(0, PANEL_HEIGHT + 0.05, 0), radius, points, spin + run * 0.02,
                    Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.0f), true);
            Vfx.ring(ctx(), center.clone().add(0, 0.1, 0), radius, points, spin - run * 0.02, Particle.DUST,
                    CRIMSON_DUST, true);
            if (run % (TRIM_PERIOD * 2) == 0) {
                Location mote = center.clone().add(0, 0.2, 0);
                double angle = ThreadLocalRandom.current().nextDouble(TAU);
                mote.add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
                Vfx.trail(mote, mote.clone().add(0, PANEL_HEIGHT, 0), PALETTE.core(), 14, false);
            }
        }

        /**
         * The interference guard: a hit between someone in the duel and someone outside it is cancelled, both ways.
         * Damage without a responsible participant (falls, the border, wild mobs) is left alone.
         */
        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        public void onDamage(EntityDamageByEntityEvent event) {
            if (finished || arena != this) {
                return;
            }
            try {
                screen(event);
            } catch (RuntimeException e) {
                ctx().plugin().getLogger().log(Level.WARNING, "Duelist arena guard failed", e);
            }
        }

        private void screen(EntityDamageByEntityEvent event) {
            Entity victim = event.getEntity();
            Entity damager = event.getDamager();
            Entity causing = event.getDamageSource().getCausingEntity();
            Player attacker = ctx().resolveAttacker(damager);
            boolean victimIn = isDuelist(victim);
            boolean attackerIn = isDuelist(damager) || (causing != null && isDuelist(causing))
                    || (attacker != null && isDuelist(attacker));
            if (!attackerIn && attacker == null) {
                return;
            }
            if (victimIn == attackerIn) {
                return;
            }
            event.setCancelled(true);
            Location at = (victimIn ? victim : damager).getLocation();
            flashPanel(at, true);
            if (victim instanceof LivingEntity living) {
                Vfx.cloud(ctx(), Fx.body(living), Particle.ENCHANTED_HIT, 6, 0.25, 0.1, null, false);
            }
            Fx.sound(victim.getLocation(), Sound.ITEM_SHIELD_BLOCK, 0.6f, 1.3f + Fx.jitter(0.1f));
        }

        private boolean isDuelist(Entity entity) {
            UUID id = entity.getUniqueId();
            return id.equals(ownerId) || id.equals(targetId);
        }

        /** The panel nearest to {@code near} flashes white for a moment with a spray of sparks. */
        private void flashPanel(Location near, boolean loud) {
            long now = ctx().currentTick();
            if (panels.isEmpty() || now - lastFlash < FLASH_TICKS) {
                return;
            }
            lastFlash = now;
            double angle = Math.atan2(near.getZ() - center.getZ(), near.getX() - center.getX());
            double step = TAU / PANELS;
            int index = (int) Math.floorMod(Math.round((angle - spin) / step), (long) PANELS);
            BlockDisplay panel = panels.get(index);
            if (!panel.isValid()) {
                return;
            }
            panel.setBlock(PANEL_FLASH.createBlockData());
            ctx().runLater(player(), FLASH_TICKS, () -> {
                if (panel.isValid()) {
                    panel.setBlock(PANEL.createBlockData());
                }
            });
            double panelAngle = spin + index * step;
            Location spot = center.clone().add(Math.cos(panelAngle) * radius, Math.min(PANEL_HEIGHT - 0.3,
                    Math.max(0.5, near.getY() - center.getY() + 1.0)), Math.sin(panelAngle) * radius);
            spot.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, spot, Vfx.n(ctx(), 8), 0.3, 0.4, 0.3, 0.08);
            Vfx.dot(spot, Particle.FLASH, PALETTE.core(), false);
            if (loud) {
                Fx.sound(spot, Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.9f, 1.2f + Fx.jitter(0.1f));
            }
        }

        /** The opponent fell: the duellist heals, the win is announced and the wall bursts outwards. */
        private void victory(Player owner) {
            double heal = Math.max(0, params().num("ult_victory_heal"));
            if (heal > 0) {
                ctx().heal(owner, heal);
            }
            Location chest = owner.getLocation().add(0, owner.getHeight() * 0.6, 0);
            World world = chest.getWorld();
            world.spawnParticle(Particle.FLASH, chest, 1, 0, 0, 0, 0, PALETTE.core(), true);
            world.spawnParticle(Particle.TOTEM_OF_UNDYING, chest, Vfx.n(ctx(), 30), 0.4, 0.6, 0.4, 0.35, null, true);
            Vfx.helix(ctx(), owner.getLocation(), 0.7, owner.getHeight() + 0.8, 2, 2, 14, spin,
                    Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.2f), true);
            Fx.sound(chest, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
            Fx.sound(chest, Sound.BLOCK_BELL_USE, 1.2f, 1.3f);
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>⚔ 결투 승리!</b></" + COLOR_TAG + ">"));
            ctx().announce(Text.mm("<" + COLOR_TAG + ">⚔</" + COLOR_TAG + "> <white><owner></white><gray>이(가) "
                    + "결투에서 승리했습니다!</gray>", Placeholder.unparsed("owner", owner.getName())));
            finish(true);
        }

        /** The duel time is over. */
        void close() {
            finish(false);
        }

        private void finish(boolean shatter) {
            if (finished) {
                return;
            }
            finished = true;
            release();
            Player owner = player();
            for (int i = 0; i < panels.size(); i++) {
                BlockDisplay panel = panels.get(i);
                if (!panel.isValid()) {
                    continue;
                }
                double angle = spin + i * TAU / PANELS;
                float width = (float) (2 * radius * Math.sin(Math.PI / PANELS) * 0.96);
                Transformation pose = shatter
                        ? Displays.transform((float) (Math.cos(angle) * radius * 1.4), PANEL_HEIGHT / 2,
                        (float) (Math.sin(angle) * radius * 1.4), width * 0.1f, PANEL_HEIGHT * 0.1f,
                        PANEL_THICKNESS, (float) Math.toDegrees(angle) - 90, 0, 0)
                        : panelPose(angle, width, 0.02f);
                Displays.animate(panel, pose, SINK_TICKS);
                ctx().runLater(owner, SINK_TICKS, () -> {
                    if (panel.isValid()) {
                        panel.remove();
                    }
                });
            }
            panels.clear();
            TextDisplay clock = timer;
            if (clock != null && clock.isValid()) {
                Displays.shrinkAway(ctx(), owner, clock, 5);
            }
            timer = null;
            Location ring = center.clone().add(0, 0.2, 0);
            Vfx.ring(ctx(), ring, radius, 40, spin, Particle.DUST_COLOR_TRANSITION, PALETTE.fade(1.2f), true);
            if (shatter) {
                Fx.sound(center, Sound.BLOCK_GLASS_BREAK, 1.4f, 0.9f);
                Vfx.ring(ctx(), ring.clone().add(0, 1.2, 0), radius, 30, spin, Particle.WAX_OFF, null, true);
            } else {
                Fx.sound(center, Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 1.2f);
                if (owner.isOnline()) {
                    owner.sendActionBar(Text.mm("<gray>결투장이 사라졌습니다</gray>"));
                }
            }
            if (arena == this) {
                arena = null;
            }
        }

        /** Unregisters the interference guard (also when the owner's ability is revoked mid-duel). */
        void release() {
            finished = true;
            if (registered) {
                HandlerList.unregisterAll(this);
                registered = false;
            }
        }
    }

    /** False for an enemy player hidden from the owner by stealth. */
    private static boolean seen(Player owner, LivingEntity entity) {
        return !(entity instanceof Player player) || player.equals(owner) || owner.canSee(player);
    }

    private static Component timerText(long ticks) {
        return Component.text("⚔ " + Fx.seconds(Math.max(0, ticks)), GOLD, TextDecoration.BOLD);
    }
}
