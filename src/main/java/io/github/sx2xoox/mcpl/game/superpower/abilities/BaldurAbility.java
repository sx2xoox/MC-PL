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
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.tag.DamageTypeTags;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 발두르 — the shining god of the Norse myths: everything in the world swore never to harm him, all but the mistletoe,
 * too young to swear, and a mistletoe dart brought him down.
 *
 * <p>The passive makes him a puzzle rather than a wall: two of the three kinds of harm (melee, projectiles, abilities)
 * barely scratch him and the third, his weakness, hurts more; the weakness turns on a timer, shown to everyone over his
 * head, so enemies switch weapons to keep up. The active bursts with light and turns the weakness on at once, so a bad
 * one can be skipped. The ultimate names a single enemy who can harm him at all: the whole lobby sees who holds the
 * mistletoe, and the team has to protect, or become, that one.
 *
 * <p>Look: radiant white, pale gold and mistletoe green. A glowing icon of his current weakness (a sword, an arrow or a
 * rune book) floats over his head in a ring of green leaves and white berries and blinks before it turns; blows that
 * the oath turns away shimmer gold, blows on the weakness burst in green. The burst is a sphere of light with golden
 * rods of light flying out. The holder of the mistletoe carries a glowing arrow over its head, outlined so everyone
 * sees it through walls, and a green thread of light runs from it to the god.
 */
public final class BaldurAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("baldur", "발두르", Material.GLOW_BERRIES)
            .color(TextColor.color(0xF2DD8C))
            .difficulty(2)
            .passive("겨우살이의 약점",
                    "근접·투사체·능력 중 지금의 약점이 아닌 피해(낙하·불 등 포함)는 {sworn_reduction}% 줄고, 약점 "
                            + "피해는 {weak_bonus}% 늘어납니다. 약점은 {weak_period}초마다 근접 → 투사체 → 능력 순으로 바뀌며 "
                            + "머리 위 아이콘으로 모두에게 보입니다.")
            .active("찬란한 빛",
                    "빛이 터져 반경 {burst_radius}블록의 적에게 {burst_damage} 피해를 주고 밀쳐 내며 {burst_blind}초간 "
                            + "실명시킵니다. 약점이 곧바로 다음 종류로 넘어갑니다.",
                    10)
            .ultimate("겨우살이를 쥔 자",
                    "{mistletoe_time}초간 {mistletoe_range}블록 안의 적 하나(플레이어 우선)에게 겨우살이를 쥐여 주고, "
                            + "그 적 말고는 무엇에도 해를 입지 않습니다. 쥔 자는 모두에게 표시되며 그 공격은 {holder_bonus}% "
                            + "더 아픕니다(1:1이면 평소 약점 규칙). 그동안 1초마다 반경 {aura_radius}블록의 적에게 "
                            + "{aura_damage} 피해를 줍니다. 쥔 자가 쓰러지거나 {pass_range}블록 넘게 멀어지면 가장 가까운 "
                            + "다른 적에게 넘어갑니다.")
            .ultimateNotStandalone()
            .param("sworn_reduction", 40)
            .param("weak_bonus", 25)
            .param("weak_period", 8)
            .param("burst_radius", 4)
            .param("burst_damage", 4)
            .param("burst_knockback", 3)
            .param("burst_blind", 1)
            .param("mistletoe_time", 5)
            .param("mistletoe_range", 20)
            .param("holder_bonus", 50)
            .param("aura_radius", 5)
            .param("aura_damage", 1)
            .param("pass_range", 30)
            .build();

    private static final String GOLD_TAG = "#F2DD8C";
    private static final String GREEN_TAG = "#5FAE4E";
    private static final double TAU = Math.PI * 2;

    // ------------------------------------------------------------------ look: radiant white, pale gold, mistletoe
    private static final Vfx.Palette RADIANT = Vfx.Palette.of(0xFFFFFF, 0xF2DD8C, 0xB8902E);
    private static final Vfx.Palette MISTLETOE = Vfx.Palette.of(0xE8F6D8, 0x5FAE4E, 0x24521C);
    private static final Color WHITE = Color.fromRGB(0xFFFDF2);
    private static final Color GREEN = Color.fromRGB(0x5FAE4E);
    private static final Particle.DustOptions GOLD_DUST = RADIANT.dust(0.9f);
    private static final Particle.DustOptions GREEN_DUST = MISTLETOE.dust(0.8f);
    private static final Particle.DustOptions BERRY_DUST = new Particle.DustOptions(WHITE, 0.55f);
    private static final Particle.DustTransition RADIANT_GLOW = RADIANT.glow(1.2f);
    private static final Particle.DustTransition GREEN_FADE = MISTLETOE.fade(1.1f);

    private static final Material RAY = Material.END_ROD;
    private static final Material MISTLETOE_DART = Material.ARROW;

    /** Display entities this ability keeps alive at once (optional pieces are skipped beyond it). */
    private static final int MAX_DISPLAYS = 24;
    private static final int FOLLOW_GLIDE = 3;
    private static final long FEEDBACK_COOLDOWN = 8;
    private static final long BLINK_TICKS = 20;
    private static final double ICON_ABOVE = 0.95;
    private static final int RAYS = 8;
    private static final double KNOCK_UP = 0.35;
    private static final double MAX_PUSH = 1.5;
    private static final long AURA_PERIOD = 20;
    private static final long PICK_PERIOD = 10;
    /** Ticks the mistletoe dart flies to its holder, and how high its arc rises. */
    private static final long DART_FLIGHT = 6;
    private static final double DART_ARC = 1.2;

    /** The three kinds of harm, in the order the weakness turns. */
    public enum Kind {
        MELEE("근접", "⚔", Material.IRON_SWORD),
        PROJECTILE("투사체", "➶", Material.ARROW),
        ABILITY("능력", "✦", Material.ENCHANTED_BOOK);

        final String korean;
        final String symbol;
        final Material icon;

        Kind(String korean, String symbol, Material icon) {
            this.korean = korean;
            this.symbol = symbol;
            this.icon = icon;
        }

        Kind next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    // ------------------------------------------------------------------ state

    private final List<Display> displays = new ArrayList<>();
    private Kind weakness = Kind.MELEE;
    private long nextTurn;
    private boolean granted;
    private @Nullable ItemDisplay icon;
    private @Nullable TextDisplay label;
    private long lastSworn = Long.MIN_VALUE / 2; // "/ 2": now - this must not overflow
    private long lastWeak = Long.MIN_VALUE / 2;
    private @Nullable Mistletoe mistletoe;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onGrant() {
        granted = true;
        weakness = Kind.MELEE;
        nextTurn = ctx().currentTick() + period();
        player().sendActionBar(Text.mm(weaknessLine("약점")));
    }

    @Override
    public void onRevoke() {
        granted = false;
        icon = null;
        label = null;
        mistletoe = null;
        displays.clear();
    }

    private long period() {
        return Math.max(20, params().ticks("weak_period"));
    }

    /** The kind of harm the god is weak to right now. */
    public Kind weakness() {
        return weakness;
    }

    // ================================================================== passive: the mistletoe's weakness

    @Override
    public void onTick(long tick) {
        if (!granted) {
            return;
        }
        Player owner = player();
        long now = ctx().currentTick();
        if (now >= nextTurn) {
            turn(owner, false);
        }
        followIcon(owner, now, tick);
        if (tick % 10 == 0 && !concealed(owner)) {
            // A faint radiance always hangs about the god.
            Vfx.dot(Vfx.scatter(owner.getLocation().add(0, 1.0, 0), 0.45), Particle.DUST, GOLD_DUST, false);
        }
    }

    /** The weakness moves on to the next kind, with a burst of leaves everyone sees. */
    private void turn(Player owner, boolean forced) {
        weakness = weakness.next();
        nextTurn = ctx().currentTick() + period();
        ItemDisplay shown = icon;
        if (shown != null && shown.isValid()) {
            shown.setItemStack(new ItemStack(weakness.icon));
            Displays.animate(shown, iconPose(0.75f), 3);
        }
        TextDisplay text = label;
        if (text != null && text.isValid()) {
            text.text(labelText());
        }
        owner.sendActionBar(Text.mm(weaknessLine(forced ? "약점을 넘겼습니다" : "약점이 바뀌었습니다")));
        if (concealed(owner)) {
            return;
        }
        Location head = iconSpot(owner);
        Vfx.cloud(ctx(), head, Particle.DUST, 10, 0.3, 0, GREEN_DUST, false);
        Vfx.ring(ctx(), head, 0.5, 10, 0, Particle.DUST, BERRY_DUST, false);
        Fx.sound(head, Sound.BLOCK_AZALEA_LEAVES_BREAK, 0.8f, 1.2f + Fx.jitter(0.1f));
        Fx.sound(head, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, forced ? 1.6f : 1.2f);
    }

    private String weaknessLine(String prefix) {
        return "<" + GOLD_TAG + ">" + prefix + ":</" + GOLD_TAG + "> <b><" + GREEN_TAG + ">" + weakness.symbol + " "
                + weakness.korean + "</" + GREEN_TAG + "></b> <dark_gray>› 다음 " + weakness.next().korean
                + "</dark_gray>";
    }

    private Component labelText() {
        return Text.mm("<" + GREEN_TAG + ">약점</" + GREEN_TAG + "> <b><white>" + weakness.korean + "</white></b>");
    }

    /** The icon of the weakness over the head, ringed with mistletoe; it blinks for the last second before it turns. */
    private void followIcon(Player owner, long now, long tick) {
        Location at = iconSpot(owner);
        ItemDisplay shown = icon;
        if (concealed(owner)) {
            TextDisplay text = label;
            for (Display display : new Display[] {shown, text}) {
                if (display != null && display.isValid()) {
                    Displays.shrinkAway(ctx(), owner, display, 3);
                }
            }
            icon = null;
            label = null;
            return;
        }
        if (shown == null || !shown.isValid()) {
            if (!reserve(2)) {
                return;
            }
            shown = keep(Displays.item(ctx(), owner, at, new ItemStack(weakness.icon), iconPose(0.75f), 0));
            shown.setBillboard(Display.Billboard.CENTER);
            shown.setTeleportDuration(FOLLOW_GLIDE);
            shown.setViewRange(1.0f);
            icon = shown;
            TextDisplay text = keep(Displays.text(ctx(), owner, at.clone().add(0, -0.42, 0), labelText(), 0.45f, 0));
            text.setTeleportDuration(FOLLOW_GLIDE);
            text.setViewRange(1.0f);
            label = text;
            return;
        }
        Displays.moveTo(shown, at);
        TextDisplay text = label;
        if (text != null && text.isValid()) {
            Displays.moveTo(text, at.clone().add(0, -0.42, 0));
        }
        long left = nextTurn - now;
        if (left <= BLINK_TICKS && left > 0) {
            if (left % 4 == 0) {
                Displays.animate(shown, iconPose((left / 4) % 2 == 0 ? 0.95f : 0.5f), 2);
            }
            if (left == BLINK_TICKS) {
                Fx.sound(at, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.6f, 1.8f);
            }
        }
        if (tick % 3 == 0) {
            // Green leaves and white berries turn around the icon.
            double angle = tick * 0.12;
            for (int i = 0; i < 3; i++) {
                double a = angle + i * TAU / 3;
                Location leaf = at.clone().add(Math.cos(a) * 0.42, Math.sin(a * 2) * 0.05, Math.sin(a) * 0.42);
                Vfx.dot(leaf, Particle.DUST, i == 0 ? BERRY_DUST : GREEN_DUST, false);
            }
        }
    }

    private static Location iconSpot(Player owner) {
        return Displays.unrotated(owner.getLocation().add(0, owner.getHeight() + ICON_ABOVE, 0));
    }

    private static org.bukkit.util.Transformation iconPose(float scale) {
        return Displays.centered(0, 0, 0, scale, scale, scale, 0, 0, 0);
    }

    /** The kind of harm {@code event} is, or null for anything else (falls, fire, ...). */
    private @Nullable Kind kindOf(EntityDamageEvent event) {
        if (ctx().isAbilityDamage()) {
            return Kind.ABILITY;
        }
        DamageCause cause = event.getCause();
        if (cause == DamageCause.PROJECTILE) {
            return Kind.PROJECTILE;
        }
        if (cause == DamageCause.ENTITY_ATTACK || cause == DamageCause.ENTITY_SWEEP_ATTACK) {
            return Kind.MELEE;
        }
        return null;
    }

    /** Harm nothing may soften: the void, a kill command, the arena border. */
    private static boolean unswearable(EntityDamageEvent event) {
        DamageType type = event.getDamageSource().getDamageType();
        if (type == DamageType.OUTSIDE_BORDER || event.getCause() == DamageCause.WORLD_BORDER) {
            return true;
        }
        return DamageTypeTags.BYPASSES_INVULNERABILITY != null
                && DamageTypeTags.BYPASSES_INVULNERABILITY.isTagged(type);
    }

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled() || event.getDamage() <= 0 || unswearable(event)) {
            return;
        }
        Player owner = player();
        Mistletoe ult = mistletoe;
        if (ult != null && ult.running()) {
            if (!ult.isHolder(event)) {
                event.setCancelled(true);
                ult.warded(owner, event);
                return;
            }
            if (!ult.alone()) {
                event.setDamage(event.getDamage() * (1 + Math.max(0, params().num("holder_bonus")) / 100.0));
                ult.pierced(owner);
                return;
            }
            // A duel: the mistletoe does not pierce deeper than the oath allows (it would only weaken the god).
        }
        Kind kind = kindOf(event);
        if (kind == weakness) {
            event.setDamage(event.getDamage() * (1 + Math.max(0, params().num("weak_bonus")) / 100.0));
            weakFeedback(owner);
        } else {
            double reduction = Math.clamp(params().num("sworn_reduction") / 100.0, 0.0, 1.0);
            event.setDamage(event.getDamage() * (1 - reduction));
            swornFeedback(owner, event);
        }
    }

    /** A blow the oath turns away: a ripple of gold between the god and the blow. */
    private void swornFeedback(Player owner, EntityDamageEvent event) {
        long now = ctx().currentTick();
        if (now - lastSworn < FEEDBACK_COOLDOWN) {
            return;
        }
        lastSworn = now;
        Location chest = owner.getLocation().add(0, owner.getHeight() * 0.55, 0);
        Location at = chest;
        if (event instanceof EntityDamageByEntityEvent hit) {
            Vector toward = Motion.horizontal(hit.getDamager().getLocation().toVector().subtract(chest.toVector()));
            at = chest.clone().add(toward.multiply(owner.getWidth() * 0.5 + 0.3));
        }
        Vfx.ring(ctx(), at, 0.45, 10, now * 0.3, Particle.DUST_COLOR_TRANSITION, RADIANT_GLOW, false);
        Vfx.dot(at, Particle.WAX_OFF, null, false);
        Fx.sound(at, Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.6f, 1.6f + Fx.jitter(0.1f));
    }

    /** A blow on the weakness: green leaves burst from the god. */
    private void weakFeedback(Player owner) {
        long now = ctx().currentTick();
        if (now - lastWeak < FEEDBACK_COOLDOWN) {
            return;
        }
        lastWeak = now;
        Location chest = owner.getLocation().add(0, owner.getHeight() * 0.55, 0);
        Vfx.cloud(ctx(), chest, Particle.DUST_COLOR_TRANSITION, 10, 0.35, 0, GREEN_FADE, false);
        Vfx.cloud(ctx(), chest, Particle.TINTED_LEAVES, 6, 0.4, 0.05, GREEN, false);
        Fx.sound(chest, Sound.BLOCK_AZALEA_LEAVES_BREAK, 0.9f, 0.8f + Fx.jitter(0.1f));
        Fx.sound(chest, Sound.ENTITY_PLAYER_HURT_SWEET_BERRY_BUSH, 0.6f, 1.0f);
    }

    // ================================================================== active: radiant light

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        Location center = owner.getLocation().add(0, owner.getHeight() * 0.55, 0);
        double radius = Math.max(0.5, params().num("burst_radius"));
        double knock = Math.max(0, params().num("burst_knockback"));
        int blind = (int) Math.min(Integer.MAX_VALUE, params().ticks("burst_blind"));
        int hits = 0;
        for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius)) {
            hits++;
            Combat.hit(ctx(), owner, enemy, params().num("burst_damage"));
            if (!Combat.isTargetable(ctx(), owner, enemy)) {
                continue;
            }
            Vector away = Motion.horizontal(enemy.getLocation().toVector().subtract(owner.getLocation().toVector()));
            if (away.lengthSquared() < 1.0E-6) {
                away = Motion.horizontalFacing(owner.getLocation());
            }
            if (knock > 0) {
                Motion.push(enemy, away.multiply(knock * 0.19).setY(KNOCK_UP), MAX_PUSH);
            }
            if (blind > 0) {
                enemy.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, blind, 0, false, true, true));
            }
            if (!isHidden(owner, enemy)) {
                Vfx.burst(ctx(), Fx.body(enemy), RADIANT, 0.5, Particle.END_ROD, false);
            }
        }
        radiance(owner, center, radius);
        if (granted) {
            turn(owner, true);
        }
        if (hits > 0) {
            owner.sendActionBar(Text.mm("<" + GOLD_TAG + ">✹ 찬란한 빛</" + GOLD_TAG + "> <white>" + hits
                    + "명</white> <dark_gray>·</dark_gray> <gray>약점</gray> <b><" + GREEN_TAG + ">" + weakness.symbol
                    + " " + weakness.korean + "</" + GREEN_TAG + "></b>"));
        }
        return ActivationResult.success();
    }

    /** The burst: a flash, a sphere of light swelling out, and golden rods of light flying away from the god. */
    private void radiance(Player owner, Location center, double radius) {
        Vfx.dot(center, Particle.FLASH, WHITE, true);
        Vfx.sphere(ctx(), center, 0.8, 30, Particle.END_ROD, null, false);
        Loop.repeat(ctx(), owner, 1, 1, 4, run -> {
            double r = radius * (run + 1) / 4.0;
            Vfx.sphere(ctx(), center, r, (int) (14 + r * 6), Particle.DUST_COLOR_TRANSITION, RADIANT_GLOW, false);
            Vfx.ring(ctx(), owner.getLocation().add(0, 0.15, 0), r, (int) (10 + r * 4), run * 0.3, Particle.DUST,
                    GOLD_DUST, false);
            return true;
        });
        if (reserve(RAYS)) {
            float spin = ThreadLocalRandom.current().nextFloat() * 45f;
            for (int i = 0; i < RAYS; i++) {
                float yaw = spin + i * 360f / RAYS;
                float pitch = -10f - (i % 2) * 20f;
                BlockDisplay ray = keep(Displays.block(ctx(), owner, Displays.unrotated(center), RAY.createBlockData(),
                        rayPose(yaw, pitch, 0.3f, 0.2f), 14));
                ray.setViewRange(1.5f);
                Displays.animateNextTick(ctx(), owner, ray, rayPose(yaw, pitch, (float) radius * 0.9f, 1.0f), 4);
                ctx().runLater(owner, 7, () -> {
                    if (ray.isValid()) {
                        Displays.animate(ray, rayPose(yaw, pitch, (float) radius * 1.2f, 0.01f), 5);
                    }
                });
            }
        }
        Fx.sound(center, Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.8f);
        Fx.sound(center, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1.0f, 1.2f + Fx.jitter(0.1f));
        Fx.sound(center, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.2f, 0.8f);
        Fx.sound(center, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.8f, 1.5f);
    }

    /** A rod of light leaving the god at {@code yaw}/{@code pitch}, {@code out} blocks away, {@code thick} wide. */
    private static org.bukkit.util.Transformation rayPose(float yaw, float pitch, float out, float thick) {
        double y = Math.toRadians(yaw);
        double p = Math.toRadians(pitch);
        Vector dir = new Vector(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
        float length = Math.max(0.2f, out * 0.6f);
        Vector center = dir.multiply(out * 0.5 + 0.3);
        // An end rod block stands along its own y axis: tip it over (pitch 90) so it lies along the ray.
        return Displays.transform((float) center.getX(), (float) center.getY(), (float) center.getZ(), thick, length,
                thick, yaw, pitch + 90, 0);
    }

    // ================================================================== ultimate: who holds the mistletoe

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        if (mistletoe != null && mistletoe.running()) {
            return ActivationResult.fail("이미 겨우살이가 누군가의 손에 있습니다");
        }
        LivingEntity holder = pick(owner, Math.max(1, params().num("mistletoe_range")), null, true);
        if (holder == null) {
            return ActivationResult.fail("겨우살이를 쥘 적이 " + AbilityInfo.formatNumber(params().num(
                    "mistletoe_range")) + "블록 안에 없습니다");
        }
        Mistletoe ult = new Mistletoe(owner);
        mistletoe = ult;
        ult.start(holder);
        return ActivationResult.success();
    }

    /**
     * An enemy within {@code range} to hold the mistletoe, never one hidden from the god: a random player (or, with
     * {@code random} false, the nearest), a summon or a training dummy only when no player is around.
     */
    private @Nullable LivingEntity pick(Player owner, double range, @Nullable LivingEntity except, boolean random) {
        List<LivingEntity> players = new ArrayList<>();
        List<LivingEntity> others = new ArrayList<>();
        for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), range)) {
            if (enemy.equals(except) || isHidden(owner, enemy)) {
                continue;
            }
            (enemy instanceof Player ? players : others).add(enemy);
        }
        List<LivingEntity> pool = players.isEmpty() ? others : players;
        if (pool.isEmpty()) {
            return null;
        }
        return random ? pool.get(ThreadLocalRandom.current().nextInt(pool.size())) : pool.getFirst();
    }

    /**
     * The ultimate: for its time the god is harmed by nothing but the holder of the mistletoe, whose blows hurt more;
     * his radiance scorches enemies around him every second. The mistletoe passes on when its holder falls or strays.
     */
    private final class Mistletoe implements LongPredicate {

        private final Player owner;
        private final long duration;
        private @Nullable LivingEntity holder;
        private @Nullable ItemDisplay dart;
        /** Where the dart flew from to its holder, and how many ticks of its flight have passed. */
        private Location flightFrom;
        private long flightAge = DART_FLIGHT;
        private boolean over;
        private long lastWard = Long.MIN_VALUE / 2;
        private long lastPierce = Long.MIN_VALUE / 2;

        Mistletoe(Player owner) {
            this.owner = owner;
            this.duration = Math.max(1, params().ticks("mistletoe_time"));
            this.flightFrom = chest(owner);
        }

        boolean running() {
            return !over;
        }

        void start(LivingEntity first) {
            Location head = owner.getLocation().add(0, owner.getHeight() + 0.5, 0);
            Vfx.dot(head, Particle.FLASH, WHITE, true);
            Vfx.helix(ctx(), owner.getLocation(), 0.8, 2.4, 2, 2, 14, 0, Particle.DUST_COLOR_TRANSITION, RADIANT_GLOW,
                    true);
            Fx.sound(head, Sound.BLOCK_BEACON_POWER_SELECT, 1.2f, 1.4f);
            Fx.sound(head, Sound.BLOCK_BELL_USE, 1.4f, 1.3f);
            Fx.sound(head, Sound.ITEM_TOTEM_USE, 0.4f, 1.6f);
            give(first, false);
            Loop.repeat(ctx(), owner, 1, 1, duration, this, this::end);
        }

        /** True if the blow comes from the holder (its hand, its arrows and spells, its summons). */
        boolean isHolder(EntityDamageEvent event) {
            LivingEntity current = holder;
            if (current == null || !(event instanceof EntityDamageByEntityEvent hit)) {
                return false;
            }
            Entity damager = hit.getDamager();
            if (current.equals(damager) || current.equals(ctx().resolveAttacker(damager))) {
                return true;
            }
            if (damager instanceof Projectile projectile) {
                ProjectileSource shooter = projectile.getShooter();
                return current.equals(shooter);
            }
            return false;
        }

        /** The mistletoe goes to {@code next}: everyone sees who holds it now. */
        private void give(LivingEntity next, boolean passed) {
            holder = next;
            ItemDisplay old = dart;
            // The dart flies from the god (or from the hand that held it) to its new holder.
            Location from = old != null && old.isValid() ? Displays.unrotated(old.getLocation()) : chest(owner);
            if (old != null && old.isValid()) {
                old.remove();
            }
            dart = null;
            Location at = dartSpot(next);
            showDart(next, from);
            Fx.sound(at, Sound.BLOCK_AZALEA_LEAVES_PLACE, 1.0f, 1.0f);
            Fx.sound(at, Sound.ENTITY_ARROW_SHOOT, 0.7f, 0.6f);
            ctx().announce(Text.mm("<" + GREEN_TAG + ">❦</" + GREEN_TAG + "> <white><holder></white><gray>이(가) "
                            + (passed ? "이어받아 " : "") + "</gray><" + GREEN_TAG + "><b>겨우살이</b></" + GREEN_TAG + ">"
                            + "<gray>를 쥐었습니다 — <white><owner></white>을(를) 해칠 수 있는 건 그뿐!</gray>",
                    Placeholder.component("holder", next.name()), Placeholder.component("owner", owner.name())));
            if (next instanceof Player chosen) {
                chosen.sendActionBar(Text.mm("<b><" + GREEN_TAG + ">❦ 발두르를 쓰러뜨릴 수 있는 건 당신뿐</" + GREEN_TAG
                        + "></b>"));
                chosen.playSound(chosen.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, SoundCategory.PLAYERS, 1.0f, 1.4f);
            }
        }

        /**
         * The glowing mistletoe dart over the holder's head, pointing down, outlined for everyone to see. It flies
         * there from {@code from} (or appears in place, with {@code from} null).
         */
        private void showDart(LivingEntity target, @Nullable Location from) {
            if (isHidden(owner, target) || !reserve(1)) {
                return;
            }
            Location at = from != null ? from : dartSpot(target);
            flightFrom = at.clone();
            flightAge = from != null ? 0 : DART_FLIGHT;
            ItemDisplay made = keep(Displays.item(ctx(), owner, at, new ItemStack(MISTLETOE_DART),
                    Displays.centered(0, 0, 0, 0.2f, 0.2f, 0.2f, 0, 0, -135), duration + 10));
            made.setTeleportDuration(from != null ? 1 : FOLLOW_GLIDE);
            made.setBillboard(Display.Billboard.VERTICAL);
            made.setViewRange(3.0f);
            Displays.outline(made, GREEN);
            Displays.animateNextTick(ctx(), owner, made, Displays.centered(0, 0, 0, 0.9f, 0.9f, 0.9f, 0, 0, -135), 5);
            dart = made;
            Vfx.cloud(ctx(), at, Particle.TINTED_LEAVES, 10, 0.3, 0.05, GREEN, true);
        }

        private Location dartSpot(LivingEntity target) {
            return Displays.unrotated(target.getLocation().add(0, target.getHeight() + 0.75, 0));
        }

        /** One tick of the dart's arc onto its holder, trailing leaves; it lands with a rustle. */
        private void fly(ItemDisplay shown, LivingEntity current) {
            flightAge++;
            double t = Math.min(1, flightAge / (double) DART_FLIGHT);
            Location goal = dartSpot(current);
            Vector path = goal.toVector().subtract(flightFrom.toVector());
            Location at = Displays.unrotated(flightFrom.clone().add(path.multiply(t)).add(0, Math.sin(t * Math.PI)
                    * DART_ARC, 0));
            Displays.moveTo(shown, at);
            Vfx.dot(at, Particle.DUST, GREEN_DUST, true);
            Vfx.dot(Vfx.scatter(at, 0.15), Particle.DUST, BERRY_DUST, false);
            if (flightAge >= DART_FLIGHT) {
                shown.setTeleportDuration(FOLLOW_GLIDE);
                Vfx.cloud(ctx(), goal, Particle.TINTED_LEAVES, 10, 0.3, 0.05, GREEN, true);
                Vfx.ring(ctx(), goal, 0.5, 10, 0, Particle.DUST_COLOR_TRANSITION, GREEN_FADE, true);
                Fx.sound(goal, Sound.BLOCK_AZALEA_LEAVES_PLACE, 1.0f, 1.3f);
                Fx.sound(goal, Sound.ENTITY_ARROW_HIT, 0.8f, 0.8f);
            }
        }

        @Override
        public boolean test(long tick) {
            if (over) {
                return false;
            }
            keepHolder(tick);
            LivingEntity current = holder;
            ItemDisplay shown = dart;
            if (current != null && shown != null && shown.isValid()) {
                if (isHidden(owner, current)) {
                    // A hidden holder's dart would give it away.
                    shown.remove();
                    dart = null;
                } else if (flightAge < DART_FLIGHT) {
                    fly(shown, current);
                } else {
                    Displays.moveTo(shown, dartSpot(current));
                }
            } else if (current != null && (shown == null || !shown.isValid()) && tick % 5 == 0) {
                showDart(current, null);
            }
            if (current != null && tick % 5 == 0 && !isHidden(owner, current)) {
                // A green thread of light from the holder to the god.
                Vfx.trail(Fx.body(current), Fx.body(owner), GREEN, 10, true);
            }
            // The god's shell of light.
            Location feet = owner.getLocation();
            double angle = tick * 0.25;
            for (int i = 0; i < 2; i++) {
                double h = 0.3 + i * 1.1 + Math.sin(angle + i) * 0.2;
                Vfx.ring(ctx(), feet.clone().add(0, h, 0), 0.75, 8, angle * (i == 0 ? 1 : -1), Particle.DUST,
                        GOLD_DUST, false);
            }
            if (tick % 4 == 0) {
                Vfx.dot(feet.clone().add(0, owner.getHeight() + 0.2, 0), Particle.END_ROD, null, false);
            }
            if (tick % AURA_PERIOD == AURA_PERIOD - 1) {
                aura();
            }
            return true;
        }

        /**
         * The holder fell or left: the mistletoe passes to the nearest other enemy (or to nobody until one comes
         * near). A holder that strayed too far hands it to the nearest other enemy, or keeps it if there is none: the
         * god is never left harmless to all while his only foe walks away.
         */
        private void keepHolder(long tick) {
            LivingEntity current = holder;
            double pass = Math.max(1, params().num("pass_range"));
            boolean gone = current == null || !Combat.isTargetable(ctx(), owner, current)
                    || !current.getWorld().equals(owner.getWorld());
            boolean strayed = !gone && current.getLocation().distance(owner.getLocation()) > pass;
            if (!gone && !strayed) {
                return;
            }
            if (current == null && tick % PICK_PERIOD != 0) {
                return;
            }
            LivingEntity next = pick(owner, current == null ? Math.max(1, params().num("mistletoe_range")) : pass,
                    current, false);
            if (next != null) {
                give(next, true);
            } else if (gone && current != null) {
                holder = null;
                ItemDisplay old = dart;
                if (old != null && old.isValid()) {
                    old.remove();
                }
                dart = null;
            }
        }

        /**
         * True if no enemy but the holder (and its own summons) is within the mistletoe's range: a duel, where the
         * holder's blows meet the oath as usual.
         */
        boolean alone() {
            LivingEntity current = holder;
            if (current == null) {
                return true;
            }
            for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(),
                    Math.max(1, params().num("mistletoe_range")))) {
                if (!enemy.equals(current) && !current.equals(ctx().resolveAttacker(enemy))) {
                    return false;
                }
            }
            return true;
        }

        /** Every second the radiance scorches the enemies around the god. */
        private void aura() {
            double radius = Math.max(0.5, params().num("aura_radius"));
            Location center = owner.getLocation().add(0, 0.2, 0);
            Vfx.shockwave(ctx(), owner, center, 0.6, radius, 5, Particle.DUST_COLOR_TRANSITION, RADIANT_GLOW, true);
            Fx.sound(center, Sound.BLOCK_BEACON_AMBIENT, 1.0f, 1.8f);
            for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation().add(0, 1, 0), radius)) {
                Combat.hit(ctx(), owner, enemy, params().num("aura_damage"));
                if (!isHidden(owner, enemy)) {
                    Vfx.dot(Fx.body(enemy), Particle.END_ROD, null, false);
                }
            }
        }

        /** A blow from anyone else: nothing in the world may harm the god. */
        void warded(Player owner, EntityDamageEvent event) {
            long now = ctx().currentTick();
            if (now - lastWard < FEEDBACK_COOLDOWN) {
                return;
            }
            lastWard = now;
            Location chest = owner.getLocation().add(0, owner.getHeight() * 0.55, 0);
            Vfx.sphere(ctx(), chest, 0.9, 16, Particle.DUST, GOLD_DUST, false);
            Vfx.dot(chest, Particle.WAX_OFF, null, false);
            Fx.sound(chest, Sound.ITEM_SHIELD_BLOCK, 0.6f, 1.7f + Fx.jitter(0.1f));
            Fx.sound(chest, Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.8f, 1.8f);
            if (event instanceof EntityDamageByEntityEvent hit) {
                Player attacker = ctx().resolveAttacker(hit.getDamager());
                if (attacker != null && !attacker.equals(owner)) {
                    attacker.sendActionBar(Text.mm("<" + GOLD_TAG + ">발두르는 맹세로 지켜집니다</" + GOLD_TAG + "> <gray>— "
                            + "겨우살이를 쥔 자만 해칠 수 있습니다</gray>"));
                }
            }
        }

        /** The mistletoe strikes home: a green burst on the god. */
        void pierced(Player owner) {
            long now = ctx().currentTick();
            if (now - lastPierce < FEEDBACK_COOLDOWN) {
                return;
            }
            lastPierce = now;
            Location chest = owner.getLocation().add(0, owner.getHeight() * 0.55, 0);
            Vfx.burst(ctx(), chest, MISTLETOE, 0.6, Particle.HAPPY_VILLAGER, false);
            Vfx.cloud(ctx(), chest, Particle.TINTED_LEAVES, 8, 0.4, 0.05, GREEN, false);
            Fx.sound(chest, Sound.BLOCK_AZALEA_LEAVES_BREAK, 1.0f, 0.7f);
            Fx.sound(chest, Sound.ENTITY_ARROW_HIT_PLAYER, 0.8f, 0.8f);
        }

        void end() {
            if (over) {
                return;
            }
            over = true;
            if (mistletoe == this) {
                mistletoe = null;
            }
            ItemDisplay shown = dart;
            if (shown != null && shown.isValid()) {
                Vfx.cloud(ctx(), shown.getLocation(), Particle.TINTED_LEAVES, 8, 0.3, 0.05, GREEN, false);
                Displays.shrinkAway(ctx(), owner, shown, 5);
            }
            Location head = owner.getLocation().add(0, owner.getHeight(), 0);
            Vfx.cloud(ctx(), head, Particle.END_ROD, 10, 0.4, 0.05, null, false);
            Fx.sound(head, Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 1.4f);
            owner.sendActionBar(Text.mm("<" + GOLD_TAG + ">❦ 겨우살이의 맹세가 풀렸습니다</" + GOLD_TAG + ">"));
        }
    }

    // ================================================================== helpers

    private <T extends Display> T keep(T display) {
        displays.add(display);
        return display;
    }

    /** Reserves room for {@code count} more displays under {@link #MAX_DISPLAYS}; false when there is none. */
    private boolean reserve(int count) {
        displays.removeIf(display -> !display.isValid());
        return displays.size() + count <= MAX_DISPLAYS;
    }

    private static boolean isHidden(Player owner, LivingEntity entity) {
        return entity instanceof Player hidden && !owner.canSee(hidden);
    }

    /** True while the god is invisible: his icon, label and radiance must not give him away. */
    private static boolean concealed(Player owner) {
        return owner.isInvisible() || owner.hasPotionEffect(PotionEffectType.INVISIBILITY);
    }

    private static Location chest(Player owner) {
        return Displays.unrotated(owner.getLocation().add(0, owner.getHeight() * 0.6, 0));
    }
}
