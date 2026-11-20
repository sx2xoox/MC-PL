package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

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
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 레벨업 헌터 — the lone hunter of the web-novel trope whose status window pops up and who alone keeps levelling up.
 * Every bit of damage dealt fills an experience bar; each level heals, hits harder and runs faster for the rest of the
 * hunter's life, and everyone reads the level over its head. The sword-aura skill grows with the level, from a short
 * forward cut to a full circle. The ultimate names the most threatening enemy a raid boss: everyone sees its health
 * bar, it grows and is shielded, but everyone hits it harder, and whoever fells it gets a legendary drop.
 *
 * <p>Look (system blue, status-window white, epic purple): a blue aura at the feet that thickens with the level, a
 * glowing "Lv.N" over the head, status windows (blue panels with white text) where the sword aura swept, blue-to-purple
 * crescents, a pillar of light and chimes for each level-up; the raid boss stands in a turning purple ring under a
 * "RAID BOSS" sign, and its legendary drop is a spinning star that flies to the one who earned it.
 */
public final class LevelerAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("leveler", "레벨업 헌터", Material.EXPERIENCE_BOTTLE)
            .color(TextColor.color(0x3BA0FF))
            .difficulty(1)
            .passive("레벨 업",
                    "적에게 {xp_per_level} 피해를 줄 때마다 레벨이 올라 체력을 {level_heal} 회복하고, 레벨마다 주는 "
                            + "피해가 {level_damage}%, 이동 속도가 {level_speed}% 늘어납니다(최대 Lv.{max_level}). 레벨은 "
                            + "쓰러질 때까지 유지되며 모두에게 보입니다.")
            .active("스킬: 검기",
                    "레벨에 따라 커지는 검기를 휘두릅니다. Lv.1은 앞 {slash_range}블록·{slash_angle}° 안의 적에게 "
                            + "{slash_damage} 피해를 주고, 레벨마다 범위가 {slash_range_step}블록, 각도가 "
                            + "{slash_angle_step}°(최대 360°), 피해가 {slash_damage_step}씩 늘어납니다. 벽 너머는 베지 "
                            + "못합니다.",
                    8)
            .ultimate("레이드 보스 지정",
                    "{boss_range}블록 안에서 남은 체력이 가장 많은 적을 {boss_duration}초간 레이드 보스로 지정합니다. "
                            + "보스는 커지고 흡수 체력 {boss_absorption}을 얻지만 모두가 보스에게 주는 피해가 {boss_bonus}% "
                            + "늘어납니다. 보스를 쓰러뜨린 사람은 체력 {drop_heal} 회복과 궁극기 게이지 {drop_gauge}%를 얻고, "
                            + "내가 쓰러뜨리면 레벨이 {boss_levels} 오릅니다.")
            .ultimateNotStandalone()
            .param("xp_per_level", 10)
            .param("max_level", 5)
            .param("level_heal", 3)
            .param("level_damage", 4)
            .param("level_speed", 3)
            .param("slash_range", 4)
            .param("slash_angle", 90)
            .param("slash_damage", 3)
            .param("slash_range_step", 0.5)
            .param("slash_angle_step", 67.5)
            .param("slash_damage_step", 0.5)
            .param("boss_range", 40)
            .param("boss_duration", 10)
            .param("boss_scale", 20)
            .param("boss_absorption", 6)
            .param("boss_bonus", 25)
            .param("drop_heal", 6)
            .param("drop_gauge", 30)
            .param("boss_levels", 2)
            .build();

    private static final String BLUE_TAG = "#3BA0FF";
    private static final String WHITE_TAG = "#EAF4FF";
    private static final String EPIC_TAG = "#8B4DFF";
    private static final String[] LEVEL_TAGS = {"#EAF4FF", "#9FD3FF", "#3BA0FF", "#6F6BFF", "#8B4DFF"};

    // ------------------------------------------------------------------ look
    /** Status white, system blue, epic purple. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xEAF4FF, 0x3BA0FF, 0x8B4DFF);
    private static final Color BLUE = Color.fromRGB(0x3BA0FF);
    private static final Color EPIC = Color.fromRGB(0x8B4DFF);
    private static final Color GOLD = Color.fromRGB(0xFFD45A);
    private static final Particle.DustOptions BLUE_DUST = new Particle.DustOptions(BLUE, 0.8f);
    private static final Particle.DustOptions EPIC_DUST = new Particle.DustOptions(EPIC, 1.0f);
    private static final Particle.DustOptions GOLD_DUST = new Particle.DustOptions(GOLD, 1.0f);
    private static final Particle.DustTransition SLASH = new Particle.DustTransition(Color.fromRGB(0xEAF4FF), BLUE,
            1.2f);
    private static final Particle.DustTransition SLASH_EDGE = new Particle.DustTransition(BLUE, EPIC, 1.0f);
    private static final Color WINDOW = Color.fromARGB(150, 18, 64, 150);
    private static final Color BOSS_WINDOW = Color.fromARGB(150, 70, 30, 140);

    private static final Material PANE = Material.LIGHT_BLUE_STAINED_GLASS;
    private static final Material EPIC_PANE = Material.PURPLE_STAINED_GLASS;

    private static final int MAX_DISPLAYS = 24;
    /** The level tag's height over the hunter's head (it rides the hunter, clear of the name tag). */
    private static final float TAG_ABOVE = 0.6f;
    /** The RAID BOSS sign's height over the boss's head (it rides the boss). */
    private static final float SIGN_ABOVE = 0.75f;
    private static final long BAR_PERIOD = 40;
    private static final long SWEEP_TICKS = 4;
    private static final double SLASH_HEIGHT_DOWN = 1.2;
    private static final double SLASH_HEIGHT_UP = 2.6;
    private static final int PANES = 3;
    /** A boss killed within this long of a player's last hit counts as that player's kill. */
    private static final long KILL_CREDIT_TICKS = 100;
    private static final long BAR_VIEWERS_PERIOD = 10;
    /** Teleport duration of the legendary star flying to its slayer (moved every tick). */
    private static final int STAR_GLIDE = 2;

    // ------------------------------------------------------------------ state

    private final List<Display> live = new ArrayList<>();
    private int level = 1;
    private double xp;
    private @Nullable NamespacedKey speedKey;
    private @Nullable TextDisplay tag;
    private int tagLevel;
    private boolean tagHidden;
    private long nextBar;
    private @Nullable Raid raid;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    /** The current level, 1..{@code max_level}. */
    public int level() {
        return level;
    }

    /** Experience gathered towards the next level. */
    public double xp() {
        return xp;
    }

    /** The current raid boss, null when none. */
    public @Nullable LivingEntity raidBoss() {
        Raid current = raid;
        return current == null ? null : current.boss;
    }

    @Override
    public void onRevoke() {
        Raid current = raid;
        if (current != null) {
            current.end(false);
        }
        raid = null;
        speedKey = null;
        tag = null;
        live.clear();
    }

    // ================================================================== passive: level up

    private int maxLevel() {
        return Math.max(1, (int) Math.round(params().num("max_level")));
    }

    private double damageFactor() {
        return 1 + Math.max(0, params().num("level_damage")) / 100.0 * (level - 1);
    }

    @Override
    public void onTick(long tick) {
        Player owner = player();
        boolean concealed = concealed(owner);
        if (concealed != tagHidden) {
            tagHidden = concealed;
            ctx().refreshViewers();
        }
        followTag(owner);
        long now = ctx().currentTick();
        if (now >= nextBar) {
            showBar(owner);
        }
        if (concealed) {
            return;
        }
        if (level > 1 && tick % 6 == 0) {
            // The aura at the feet thickens with every level; at the top it turns epic purple.
            Location feet = owner.getLocation().add(0, 0.1, 0);
            boolean epic = level >= maxLevel();
            int points = 2 + level;
            Vfx.ring(ctx(), feet, 0.5, points, tick * 0.15, Particle.DUST, epic ? EPIC_DUST : BLUE_DUST, false);
            if (tick % 18 == 0) {
                Vfx.dot(Vfx.scatter(feet.clone().add(0, 0.6, 0), 0.4), Particle.END_ROD, null, false);
            }
        }
    }

    /** True if enemies should not learn where the owner is (invisible or hidden from someone). */
    private boolean concealed(Player owner) {
        if (owner.isInvisible() || owner.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
            return true;
        }
        for (Player viewer : ctx().alivePlayers()) {
            if (!viewer.equals(owner) && ctx().isConcealedFrom(owner, viewer)) {
                return true;
            }
        }
        return false;
    }

    /**
     * "Lv.N" over the owner's head for everyone it is not concealed from. It rides the owner, so every client moves it
     * with the body frame by frame (a tag teleported after the hunter every tick slides ahead and trails behind).
     */
    private void followTag(Player owner) {
        TextDisplay shown = tag;
        if (shown == null || !shown.isValid()) {
            if (!reserve(1)) {
                return;
            }
            shown = keep(headText(owner, owner, tagText(), 0.8f, TAG_ABOVE, 0,
                    viewer -> !ctx().isConcealedFrom(owner, viewer)));
            tag = shown;
            tagLevel = level;
        } else if (tagLevel != level) {
            shown.text(tagText());
            tagLevel = level;
        }
        ride(owner, shown);
    }

    /** A camera-facing text that rides {@code carrier}, {@code above} blocks over its head. */
    private TextDisplay headText(Player owner, LivingEntity carrier, Component text, float scale, float above,
                                 long lifetime, java.util.function.Predicate<? super Player> viewers) {
        Location seat = Displays.unrotated(carrier.getLocation().add(0, carrier.getHeight(), 0));
        TextDisplay display = Displays.spawn(ctx(), owner, seat, TextDisplay.class, lifetime, viewers, d -> {
            d.text(text);
            d.setBillboard(Display.Billboard.CENTER);
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            d.setShadowed(true);
            d.setTransformation(Displays.centered(0, above, 0, scale, scale, scale, 0, 0, 0));
        });
        ride(carrier, display);
        return display;
    }

    /**
     * Keeps {@code display} riding {@code carrier}: a passenger moves with its carrier's body on every client, frame by
     * frame, where a display teleported after it every tick trails and wobbles. Mounted again if it was thrown off.
     */
    private static void ride(Entity carrier, Display display) {
        if (!display.isValid() || !carrier.isValid() || !display.getWorld().equals(carrier.getWorld())) {
            return;
        }
        Entity vehicle = display.getVehicle();
        if (vehicle != null && vehicle.getUniqueId().equals(carrier.getUniqueId())) {
            return;
        }
        if (vehicle != null) {
            vehicle.removePassenger(display);
        }
        carrier.addPassenger(display);
    }

    private Component tagText() {
        String color = LEVEL_TAGS[Math.min(LEVEL_TAGS.length - 1, Math.max(0, level - 1))];
        String max = level >= maxLevel() ? " <" + EPIC_TAG + ">★</" + EPIC_TAG + ">" : "";
        return Text.mm("<b><" + color + ">Lv." + level + "</" + color + "></b>" + max);
    }

    /** The experience bar on the owner's action bar. */
    private void showBar(Player owner) {
        nextBar = ctx().currentTick() + BAR_PERIOD;
        if (ctx().hasShownPrompt(owner)) {
            return;
        }
        double per = Math.max(1, params().num("xp_per_level"));
        StringBuilder bar = new StringBuilder();
        if (level >= maxLevel()) {
            bar.append("<").append(EPIC_TAG).append(">▰▰▰▰▰▰▰▰▰▰ MAX</").append(EPIC_TAG).append(">");
        } else {
            int filled = (int) Math.floor(Math.min(1.0, xp / per) * 10);
            bar.append("<").append(BLUE_TAG).append(">").append("▰".repeat(filled)).append("</").append(BLUE_TAG)
                    .append("><dark_gray>").append("▱".repeat(10 - filled)).append("</dark_gray> <gray>")
                    .append(AbilityInfo.formatNumber(Math.floor(xp * 10) / 10)).append("/")
                    .append(AbilityInfo.formatNumber(per)).append(" EXP</gray>");
        }
        owner.sendActionBar(Text.mm("<" + BLUE_TAG + ">[시스템]</" + BLUE_TAG + "> <b><" + WHITE_TAG + ">Lv." + level
                + "</" + WHITE_TAG + "></b> " + bar));
    }

    /** True for the owner's own hits: melee, its projectiles and its ability damage (not its summons). */
    private boolean isOwnHit(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        Player owner = player();
        return owner.equals(damager) || damager instanceof Projectile projectile
                && owner.equals(projectile.getShooter());
    }

    /** Every level makes the hunter's own hits stronger; only changes the damage. */
    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled() || level <= 1 || !isOwnHit(event) || !ctx().isEnemy(player(), victim)) {
            return;
        }
        event.setDamage(event.getDamage() * damageFactor());
    }

    /** Damage that landed is experience. */
    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        double dealt = Math.min(event.getFinalDamage(), Math.max(0, victim.getHealth() + victim.getAbsorptionAmount()));
        if (dealt > 0) {
            gainXp(dealt);
        }
    }

    private void gainXp(double amount) {
        int max = maxLevel();
        if (level >= max) {
            xp = 0;
            return;
        }
        double per = Math.max(1, params().num("xp_per_level"));
        xp += amount;
        int gained = 0;
        while (xp >= per - 1.0E-9 && level < max) {
            xp -= per;
            gained++;
            levelUp(false);
        }
        if (level >= max) {
            xp = 0;
        }
        if (gained == 0) {
            showBar(player());
        }
    }

    /** One level more: healed, stronger, faster, announced by the system window. */
    private void levelUp(boolean legendary) {
        int max = maxLevel();
        if (level >= max) {
            return;
        }
        level++;
        Player owner = player();
        ctx().heal(owner, Math.max(0, params().num("level_heal")));
        refreshSpeed(owner);
        nextBar = 0;
        boolean epic = level >= max;
        owner.showTitle(Title.title(Component.empty(), Text.mm("<" + BLUE_TAG + ">[시스템]</" + BLUE_TAG + "> <"
                + WHITE_TAG + ">레벨이 올랐습니다!</" + WHITE_TAG + "> <b><" + (epic ? EPIC_TAG : BLUE_TAG) + ">Lv."
                + level + "</" + (epic ? EPIC_TAG : BLUE_TAG) + "></b>" + (legendary ? " <" + EPIC_TAG + ">(전설 처치)</"
                + EPIC_TAG + ">" : "")), Title.Times.times(java.time.Duration.ZERO, java.time.Duration.ofMillis(1200),
                java.time.Duration.ofMillis(300))));
        if (concealed(owner)) {
            Fx.sound(owner.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.4f, 1.3f);
            return;
        }
        Location feet = owner.getLocation();
        Location body = Fx.body(owner);
        // A pillar of system light rises through the hunter, chimes ring out and a status window pops up.
        Vfx.helix(ctx(), feet, 0.7, 2.6, 2, 2, 14, 0, Particle.DUST_COLOR_TRANSITION, epic ? SLASH_EDGE : SLASH, true);
        Vfx.ring(ctx(), feet.clone().add(0, 0.1, 0), 1.0, 18, 0, Particle.DUST, epic ? EPIC_DUST : BLUE_DUST, true);
        Vfx.cloud(ctx(), body, Particle.END_ROD, 10, 0.4, 0.08, null, false);
        Vfx.cloud(ctx(), body.clone().add(0, 0.5, 0), Particle.ENCHANT, 16, 0.5, 0.6, null, false);
        Fx.sound(feet, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.1f + Fx.jitter(0.05f));
        Fx.sound(feet, Sound.BLOCK_BEACON_ACTIVATE, 0.6f, 1.6f + Fx.jitter(0.05f));
        Fx.sound(feet, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, 1.5f);
        ctx().runLater(owner, 3, () -> Fx.sound(owner.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, 2.0f));
        Location windowAt = Displays.unrotated(feet.clone().add(0, owner.getHeight() + 1.1, 0));
        window(owner, windowAt, "<b><" + WHITE_TAG + ">[시스템]</" + WHITE_TAG + "></b>\n<" + WHITE_TAG
                + ">레벨이 올랐습니다!</" + WHITE_TAG + ">\n<b><" + (epic ? EPIC_TAG : "#9FD3FF") + ">Lv." + (level - 1)
                + " → Lv." + level + "</" + (epic ? EPIC_TAG : "#9FD3FF") + "></b>", 0.55f, 36, epic ? BOSS_WINDOW
                : WINDOW);
    }

    private void refreshSpeed(Player owner) {
        NamespacedKey old = speedKey;
        if (old != null) {
            ctx().removeModifier(owner, old);
            speedKey = null;
        }
        double speed = Math.max(0, params().num("level_speed")) / 100.0 * (level - 1);
        if (speed > 0) {
            speedKey = ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, speed, Operation.MULTIPLY_SCALAR_1, 0);
        }
    }

    /** A floating status window (a blue panel with white text) that rises a little and closes. */
    private @Nullable TextDisplay window(Player owner, Location at, String mini, float scale, long lifetime,
                                         Color background) {
        if (!reserve(1)) {
            return null;
        }
        TextDisplay panel = keep(Displays.spawn(ctx(), owner, at, TextDisplay.class, lifetime + 2, display -> {
            display.text(Text.mm(mini));
            display.setBillboard(Display.Billboard.CENTER);
            display.setBackgroundColor(background);
            display.setShadowed(false);
            display.setLineWidth(220);
            display.setTransformation(Displays.centered(0, 0, 0, scale * 0.3f, scale * 0.3f, scale * 0.3f, 0, 0, 0));
        }));
        Displays.animateNextTick(ctx(), owner, panel, Displays.centered(0, 0.15f, 0, scale, scale, scale, 0, 0, 0), 3);
        ctx().runLater(owner, Math.max(4, lifetime - 6), () -> {
            if (panel.isValid()) {
                Displays.animate(panel, Displays.centered(0, 0.45f, 0, scale, 0.02f, scale, 0, 0, 0), 5);
            }
        });
        return panel;
    }

    // ================================================================== active: sword aura

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        int lv = level;
        double range = Math.max(1, params().num("slash_range") + params().num("slash_range_step") * (lv - 1));
        double angle = Math.clamp(params().num("slash_angle") + params().num("slash_angle_step") * (lv - 1), 10, 360);
        double damage = Math.max(0, params().num("slash_damage") + params().num("slash_damage_step") * (lv - 1));
        Location feet = owner.getLocation();
        Location eye = owner.getEyeLocation();
        float yaw = feet.getYaw();
        Vector facing = new Vector(-Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw)));

        List<LivingEntity> struck = new ArrayList<>();
        for (LivingEntity enemy : ctx().enemiesNear(owner, feet, range + 1.5)) {
            if (inSlash(owner, enemy, facing, range, angle) && reachable(eye, enemy)) {
                struck.add(enemy);
            }
        }
        slashFx(owner, feet, yaw, range, angle, lv);
        for (LivingEntity enemy : struck) {
            Combat.hit(ctx(), owner, enemy, damage);
            if (seen(owner, enemy)) {
                Location body = Fx.body(enemy);
                Vfx.burst(ctx(), body, PALETTE, 0.5, Particle.END_ROD, false);
                Vfx.dot(body, Particle.SWEEP_ATTACK, null, false);
                Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.8f, 1.2f + Fx.jitter(0.1f));
                window(owner, Displays.unrotated(enemy.getLocation().add(0, enemy.getHeight() + 0.5, 0)),
                        "<b><" + WHITE_TAG + ">-" + AbilityInfo.formatNumber(Math.round(damage * damageFactor() * 10)
                                / 10.0) + "</" + WHITE_TAG + "></b>", 0.5f, 16, WINDOW);
            }
        }
        return ActivationResult.success();
    }

    /** Within the cut: close enough on the ground, at body height and inside the arc (with the hitbox's width). */
    private static boolean inSlash(Player owner, LivingEntity enemy, Vector facing, double range, double angle) {
        Location feet = owner.getLocation();
        Location at = enemy.getLocation();
        double dy = at.getY() - feet.getY();
        if (dy < -SLASH_HEIGHT_DOWN - enemy.getHeight() || dy > SLASH_HEIGHT_UP) {
            return false;
        }
        Vector to = new Vector(at.getX() - feet.getX(), 0, at.getZ() - feet.getZ());
        double distance = to.length();
        double half = enemy.getWidth() / 2;
        if (distance - half > range) {
            return false;
        }
        if (angle >= 359.9 || distance < 0.4) {
            return true;
        }
        double between = Math.toDegrees(facing.angle(to.multiply(1 / distance)));
        double allowance = Math.toDegrees(Math.atan2(half, distance));
        return between <= angle / 2 + allowance;
    }

    /** No wall between the hunter and the enemy (its eyes or its body). */
    private static boolean reachable(Location eye, LivingEntity enemy) {
        return Spots.clearPath(eye, enemy.getEyeLocation()) || Spots.clearPath(eye, Fx.body(enemy));
    }

    /**
     * The cut sweeps across the arc over a few ticks in blue-to-purple crescents with a bright leading edge, status
     * window panes flash up along it, and a window names the skill.
     */
    private void slashFx(Player owner, Location feet, float yaw, double range, double angle, int lv) {
        boolean epic = lv >= maxLevel();
        Location chest = feet.clone().add(0, 1.0, 0);
        double start = yaw - angle / 2;
        Fx.sound(feet, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.9f + Fx.jitter(0.05f));
        Fx.sound(feet, Sound.ITEM_TRIDENT_RIPTIDE_1, 0.7f, 1.4f + Fx.jitter(0.05f));
        Fx.sound(feet, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 0.8f + 0.1f * lv);
        if (epic) {
            Fx.sound(feet, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.8f, 1.2f);
        }
        Loop.repeat(ctx(), owner, 0, 1, SWEEP_TICKS, run -> {
            double from = start + angle * run / SWEEP_TICKS;
            double width = angle / SWEEP_TICKS;
            double center = from + width / 2;
            for (double r : new double[] {range * 0.45, range * 0.75, range}) {
                int points = (int) Math.max(4, Math.min(18, r * width / 22));
                Vfx.arc(ctx(), chest, r, center, width, points, Particle.DUST_COLOR_TRANSITION,
                        r >= range ? SLASH_EDGE : SLASH, false);
            }
            double edge = Math.toRadians(from + width) + Math.PI / 2;
            Location tip = chest.clone().add(Math.cos(edge) * range, 0, Math.sin(edge) * range);
            Vfx.line(ctx(), chest.clone().add(Math.cos(edge) * 0.8, 0, Math.sin(edge) * 0.8), tip, 2.0,
                    Particle.END_ROD, null, false);
            if (run % 2 == 0) {
                Fx.sound(tip, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.5f, 1.3f + 0.1f * run);
            }
            return true;
        });
        // Status window panes stand up along the arc and fade.
        if (reserve(PANES)) {
            Material pane = epic ? EPIC_PANE : PANE;
            for (int i = 0; i < PANES; i++) {
                double at = PANES == 1 ? yaw : start + angle * (i + 0.5) / PANES;
                double rad = Math.toRadians(at) + Math.PI / 2;
                double r = range * 0.7;
                Location spot = Displays.unrotated(chest.clone().add(Math.cos(rad) * r, 0.1, Math.sin(rad) * r));
                float paneYaw = (float) at;
                BlockDisplay panel = keep(Displays.block(ctx(), owner, spot, pane.createBlockData(),
                        Displays.transform(0, 0, 0, 0.1f, 0.06f, 0.02f, paneYaw, 0, 0), 22));
                Displays.outline(panel, epic ? EPIC : BLUE);
                int delay = (int) Math.min(SWEEP_TICKS - 1, i * SWEEP_TICKS / PANES);
                ctx().runLater(owner, 1 + delay, () -> Displays.animate(panel,
                        Displays.transform(0, 0, 0, 1.3f, 0.8f, 0.02f, paneYaw, 0, 0), 3));
                ctx().runLater(owner, 12 + delay, () -> {
                    if (panel.isValid()) {
                        Displays.shrinkAway(ctx(), owner, panel, 8);
                    }
                });
            }
        }
        Location front = Displays.unrotated(feet.clone().add(new Vector(-Math.sin(Math.toRadians(yaw)), 0,
                Math.cos(Math.toRadians(yaw))).multiply(1.4)).add(0, 2.3, 0));
        window(owner, front, "<b><" + WHITE_TAG + ">[스킬: 검기]</" + WHITE_TAG + "></b>\n<" + (epic ? EPIC_TAG
                : "#9FD3FF") + ">Lv." + lv + " · " + AbilityInfo.formatNumber(range) + "블록 · "
                + (angle >= 359.9 ? "전방위" : AbilityInfo.formatNumber(angle) + "°") + "</" + (epic ? EPIC_TAG
                : "#9FD3FF") + ">", 0.5f, 26, epic ? BOSS_WINDOW : WINDOW);
    }

    // ================================================================== ultimate: raid boss

    @Override
    public ActivationResult onUltimate() {
        if (raid != null) {
            return ActivationResult.fail("이미 레이드가 진행 중입니다");
        }
        Player owner = player();
        LivingEntity boss = pickBoss(owner);
        if (boss == null) {
            return ActivationResult.fail("레이드 보스로 지정할 적이 없습니다");
        }
        Raid started = new Raid(owner, boss);
        raid = started;
        started.begin();
        return ActivationResult.success();
    }

    /** The most threatening enemy in range: the most health left (absorption included), then the fuller gauge. */
    private @Nullable LivingEntity pickBoss(Player owner) {
        LivingEntity best = null;
        double bestHealth = -1;
        double bestGauge = -1;
        for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), Math.max(1, params().num("boss_range")))) {
            if (ctx().isConcealedFrom(enemy, owner)) {
                continue;
            }
            double health = enemy.getHealth() + enemy.getAbsorptionAmount();
            double gauge = enemy instanceof Player player ? ctx().gauge(player) : 0;
            if (health > bestHealth + 1.0E-6 || Math.abs(health - bestHealth) <= 1.0E-6 && gauge > bestGauge) {
                best = enemy;
                bestHealth = health;
                bestGauge = gauge;
            }
        }
        return best;
    }

    /**
     * The raid: a boss bar for everyone watching, the boss grown and shielded, every player's hits on it stronger, and a
     * legendary drop for whoever fells it. Ends when its time is up, the boss falls or leaves, or the hunter is out.
     */
    private final class Raid implements Listener {

        private final Player owner;
        private final LivingEntity boss;
        private final long duration = Math.max(20, params().ticks("boss_duration"));
        private final long endTick;
        private final BossBar bar;
        private final Set<UUID> viewers = new HashSet<>();
        private final List<NamespacedKey> keys = new ArrayList<>();
        private @Nullable TextDisplay sign;
        private @Nullable UUID lastHitter;
        private long lastHitTick = Long.MIN_VALUE / 2;
        private boolean ended;

        Raid(Player owner, LivingEntity boss) {
            this.owner = owner;
            this.boss = boss;
            this.endTick = ctx().currentTick() + duration;
            this.bar = BossBar.bossBar(barName(), progress(), BossBar.Color.PURPLE, BossBar.Overlay.NOTCHED_10);
        }

        void begin() {
            Bukkit.getPluginManager().registerEvents(this, ctx().plugin());
            long keep = duration + 5;
            double scale = Math.max(0, params().num("boss_scale")) / 100.0;
            if (scale > 0 && Spots.isFree(boss.getWorld(), boss.getLocation(), boss.getWidth() * (1 + scale),
                    boss.getHeight() * (1 + scale))) {
                keys.add(ctx().addModifier(owner, boss, Attribute.SCALE, scale, Operation.MULTIPLY_SCALAR_1, keep));
            }
            double absorption = Math.max(0, params().num("boss_absorption"));
            if (absorption > 0) {
                keys.add(ctx().addModifier(owner, boss, Attribute.MAX_ABSORPTION, absorption, Operation.ADD_NUMBER,
                        keep));
                boss.setAbsorptionAmount(boss.getAbsorptionAmount() + absorption);
            }
            updateViewers();
            Component name = boss.name();
            ctx().announce(Text.mm("<" + EPIC_TAG + "><b>[시스템]</b></" + EPIC_TAG + "> <" + WHITE_TAG
                    + ">레이드 보스</" + WHITE_TAG + "> <b><name></b><" + WHITE_TAG + ">이(가) 나타났습니다! 모두가 주는 "
                    + "피해 +" + AbilityInfo.formatNumber(params().num("boss_bonus")) + "%</" + WHITE_TAG + ">",
                    Placeholder.component("name", name)));
            owner.showTitle(Title.title(Text.mm("<b><" + EPIC_TAG + ">RAID BOSS</" + EPIC_TAG + "></b>"),
                    Text.mm("<" + WHITE_TAG + ">레이드 보스: </" + WHITE_TAG + "><name>",
                            Placeholder.component("name", name))));
            Location at = boss.getLocation();
            if (!ctx().isConcealedFrom(boss, owner)) {
                Vfx.helix(ctx(), at, 1.1, 4.0, 2.5, 3, 16, 0, Particle.DUST_COLOR_TRANSITION, SLASH_EDGE, true);
                Vfx.dot(Fx.body(boss), Particle.FLASH, EPIC, true);
                Vfx.cloud(ctx(), Fx.body(boss), Particle.WITCH, 20, 0.6, 0.1, null, true);
            }
            Fx.sound(at, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.3f + Fx.jitter(0.05f));
            Fx.sound(at, Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 0.7f);
            Fx.sound(owner.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 0.6f);
            Loop.repeat(ctx(), owner, 1, 1, duration, this::tick, () -> end(true));
        }

        private boolean tick(long run) {
            if (ended) {
                return false;
            }
            if (bossGone()) {
                return false;
            }
            long left = endTick - ctx().currentTick();
            if (run % 4 == 0) {
                bar.name(barName());
            }
            bar.progress(progress());
            if (run % BAR_VIEWERS_PERIOD == 0) {
                updateViewers();
            }
            followSign();
            if (!ctx().isConcealedFrom(boss, owner) && !boss.isInvisible()) {
                Location feet = boss.getLocation().add(0, 0.1, 0);
                if (run % 2 == 0) {
                    Vfx.ring(ctx(), feet, 1.0 + 0.15 * Math.sin(run * 0.3), 14, run * 0.12, Particle.DUST, EPIC_DUST,
                            false);
                }
                if (run % 10 == 0) {
                    Vfx.dot(Vfx.scatter(feet.clone().add(0, 1.4, 0), 0.5), Particle.WITCH, null, false);
                }
            }
            if (left == 40 || left == 20) {
                Fx.sound(boss.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.7f, left == 20 ? 1.2f : 0.9f);
            }
            return true;
        }

        private boolean bossGone() {
            if (!boss.isValid() || boss.isDead()) {
                return true;
            }
            return boss instanceof Player player && !ctx().isAlive(player);
        }

        private Component barName() {
            long left = Math.max(0, endTick - ctx().currentTick());
            return Text.mm("<b><" + EPIC_TAG + ">⚔ 레이드 보스</" + EPIC_TAG + "></b> <" + WHITE_TAG + "><name></"
                    + WHITE_TAG + "> <gray>" + Fx.seconds(left) + "초</gray>", Placeholder.component("name",
                    boss.name()));
        }

        private float progress() {
            double max = Combat.maxHealth(boss);
            return (float) Math.clamp(boss.getHealth() / max, 0.0, 1.0);
        }

        /** Everyone in play or watching sees the bar; leavers and late joiners are followed. */
        private void updateViewers() {
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                boolean wanted = viewer.getWorld().equals(boss.getWorld())
                        && (ctx().isAlive(viewer) || ctx().isSpectator(viewer));
                boolean shown = viewers.contains(viewer.getUniqueId());
                if (wanted && !shown) {
                    viewer.showBossBar(bar);
                    viewers.add(viewer.getUniqueId());
                } else if (!wanted && shown) {
                    viewer.hideBossBar(bar);
                    viewers.remove(viewer.getUniqueId());
                }
            }
        }

        /** "RAID BOSS" riding over the boss's head, for everyone the boss is not concealed from. */
        private void followSign() {
            TextDisplay shown = sign;
            if (shown == null || !shown.isValid()) {
                if (!reserve(1)) {
                    return;
                }
                shown = keep(headText(owner, boss, Text.mm("<b><" + EPIC_TAG + ">⚔ RAID BOSS ⚔</" + EPIC_TAG
                        + "></b>"), 0.7f, SIGN_ABOVE, duration + 4, viewer -> !ctx().isConcealedFrom(boss, viewer)));
                sign = shown;
            }
            ride(boss, shown);
        }

        // -------------------------------------------------------------- the raid's rules

        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onHitBoss(EntityDamageByEntityEvent event) {
            if (ended || !event.getEntity().equals(boss)) {
                return;
            }
            try {
                Player attacker = ctx().resolveAttacker(event.getDamager());
                if (attacker != null && !attacker.equals(boss) && ctx().isAlive(attacker)) {
                    event.setDamage(event.getDamage() * (1 + Math.max(0, params().num("boss_bonus")) / 100.0));
                }
            } catch (RuntimeException e) {
                ctx().plugin().getLogger().log(Level.WARNING, "Leveler raid bonus failed", e);
            }
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onBossHit(EntityDamageByEntityEvent event) {
            if (ended || !event.getEntity().equals(boss)) {
                return;
            }
            Player attacker = ctx().resolveAttacker(event.getDamager());
            if (attacker != null && !attacker.equals(boss)) {
                lastHitter = attacker.getUniqueId();
                lastHitTick = ctx().currentTick();
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onDeath(EntityDeathEvent event) {
            if (ended || !event.getEntity().equals(boss)) {
                return;
            }
            try {
                felled();
            } catch (RuntimeException e) {
                ctx().plugin().getLogger().log(Level.WARNING, "Leveler raid reward failed", e);
            }
        }

        /** The boss fell: its last attacker gets the legendary drop. */
        private void felled() {
            Location at = boss.getLocation();
            UUID id = lastHitter;
            Player killer = id == null || ctx().currentTick() - lastHitTick > KILL_CREDIT_TICKS ? null
                    : Bukkit.getPlayer(id);
            end(true);
            Fx.sound(at, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
            Vfx.line(ctx(), at.clone().add(0, 0.2, 0), at.clone().add(0, 8, 0), 3, Particle.DUST_COLOR_TRANSITION,
                    SLASH_EDGE, true);
            Vfx.cloud(ctx(), at.clone().add(0, 1, 0), Particle.DUST, 20, 0.6, 0, GOLD_DUST, true);
            if (killer == null || !ctx().isAlive(killer)) {
                ctx().announce(Text.mm("<" + EPIC_TAG + "><b>[시스템]</b></" + EPIC_TAG + "> <" + WHITE_TAG
                        + ">레이드 보스가 쓰러졌습니다.</" + WHITE_TAG + ">"));
                return;
            }
            ctx().announce(Text.mm("<" + EPIC_TAG + "><b>[시스템]</b></" + EPIC_TAG + "> <b><name></b><" + WHITE_TAG
                    + ">이(가) 레이드 보스를 쓰러뜨려 </" + WHITE_TAG + "><b><" + EPIC_TAG + ">전설 드랍</" + EPIC_TAG
                    + "></b><" + WHITE_TAG + ">을 얻었습니다!</" + WHITE_TAG + ">", Placeholder.component("name",
                    killer.name())));
            drop(at, killer);
            ctx().heal(killer, Math.max(0, params().num("drop_heal")));
            ctx().addGauge(killer, Math.max(0, params().num("drop_gauge")));
            if (killer.equals(owner) && ctx().isAlive(owner)) {
                int levels = Math.max(0, (int) Math.round(params().num("boss_levels")));
                for (int i = 0; i < levels; i++) {
                    levelUp(true);
                }
                if (level >= maxLevel()) {
                    xp = 0;
                }
            }
        }

        /** A spinning legendary star rises where the boss fell and flies into its slayer. */
        private void drop(Location at, Player killer) {
            if (!reserve(1)) {
                return;
            }
            Location spot = Displays.unrotated(at.clone().add(0, 1.2, 0));
            ItemDisplay star = keep(Displays.item(ctx(), owner, spot, new ItemStack(Material.NETHER_STAR),
                    Displays.centered(0, 0, 0, 0.2f, 0.2f, 0.2f, 0, 0, 0), 30));
            Displays.outline(star, EPIC);
            star.setTeleportDuration(STAR_GLIDE);
            Displays.animateNextTick(ctx(), owner, star, Displays.centered(0, 0, 0, 0.8f, 0.8f, 0.8f, 180, 0, 0), 8);
            Loop.repeat(ctx(), owner, 10, 1, 14, run -> {
                if (!star.isValid() || !killer.isValid()) {
                    return false;
                }
                Location from = star.getLocation();
                Location to = Fx.body(killer);
                double t = Math.min(1.0, (run + 1) / 8.0);
                Location next = from.clone().add(to.toVector().subtract(from.toVector()).multiply(t));
                Displays.moveTo(star, next);
                Vfx.dot(next, Particle.DUST, run % 2 == 0 ? GOLD_DUST : EPIC_DUST, false);
                if (t >= 1.0 || next.distanceSquared(to) < 0.3) {
                    // The clients see the star arrive a glide later: it shrinks into the slayer, then the burst.
                    Displays.shrinkAway(ctx(), owner, star, STAR_GLIDE + 1);
                    ctx().runLater(owner, STAR_GLIDE, () -> {
                        Location into = killer.isValid() ? Fx.body(killer) : to;
                        Vfx.cloud(ctx(), into, Particle.TOTEM_OF_UNDYING, 18, 0.4, 0.3, null, false);
                        Fx.sound(into, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.6f);
                    });
                    return false;
                }
                return true;
            });
        }

        /** Ends the raid: the bar goes, the boss shrinks back and loses what is left of the raid shield. */
        void end(boolean visible) {
            if (ended) {
                return;
            }
            ended = true;
            HandlerList.unregisterAll(this);
            for (UUID id : viewers) {
                Player viewer = Bukkit.getPlayer(id);
                if (viewer != null) {
                    viewer.hideBossBar(bar);
                }
            }
            viewers.clear();
            for (NamespacedKey key : keys) {
                ctx().removeModifier(boss, key);
            }
            keys.clear();
            if (boss.isValid() && !boss.isDead()) {
                boss.setAbsorptionAmount(Math.max(0, boss.getAbsorptionAmount()));
            }
            TextDisplay shown = sign;
            if (shown != null && shown.isValid()) {
                if (visible && ctx().isAlive(owner)) {
                    Displays.shrinkAway(ctx(), owner, shown, 4);
                } else {
                    shown.remove();
                }
            }
            sign = null;
            if (raid == this) {
                raid = null;
            }
        }
    }

    // ================================================================== helpers

    /** True if the owner can see {@code target} (hit effects on a hidden enemy would reveal it). */
    private boolean seen(Player owner, LivingEntity target) {
        return !ctx().isConcealedFrom(target, owner);
    }

    private boolean reserve(int count) {
        live.removeIf(display -> !display.isValid());
        return live.size() + count <= MAX_DISPLAYS;
    }

    private <T extends Display> T keep(T display) {
        live.add(display);
        return display;
    }
}
