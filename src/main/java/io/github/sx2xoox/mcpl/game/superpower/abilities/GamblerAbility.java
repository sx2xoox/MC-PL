package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Body;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityRegistry;
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
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
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
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 도박사 — everything is a coin flip. Luck occasionally shrugs off a hit or doubles one, the dice give one of six
 * outcomes (rolled above the owner's head for everyone to see), and the jackpot spins a slot machine that fires a
 * random other ability's ultimate.
 */
public final class GamblerAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("gambler", "도박사", Material.GOLD_INGOT)
            .color(TextColor.color(0xFFC83D))
            .difficulty(2)
            .passive("행운",
                    "받는 피해를 {dodge_chance}% 확률로 무시하고, 직접 가하는 피해가 {crit_chance}% 확률로 "
                            + "{crit_multiplier}배가 됩니다.")
            .active("주사위",
                    "주사위를 굴려 무작위 효과를 얻습니다. 1 자해 {dice_self_damage}(죽지는 않음) · 2 신속 II "
                            + "{dice_buff_duration}초 · 3 힘 I {dice_buff_duration}초 · 4 체력 {dice_heal} 회복 · 5 "
                            + "{dice_bolt_range}블록 안 가장 가까운 적에게 낙뢰 {dice_bolt_damage} 피해 · 6 궁극기 게이지 "
                            + "+{dice_gauge}%",
                    8)
            .ultimate("잭팟",
                    "슬롯머신을 {jackpot_spin}초간 돌려 다른 능력의 궁극기 하나를 무작위로 발동합니다. 무엇이 "
                            + "나왔는지 모두에게 공지됩니다.")
            // Borrowing is done by this ultimate; never let another borrower chain into it.
            .ultimateNotStandalone()
            .param("dodge_chance", 10)
            .param("crit_chance", 10)
            .param("crit_multiplier", 2)
            .param("dice_self_damage", 2)
            .param("dice_buff_duration", 6)
            .param("dice_heal", 6)
            .param("dice_bolt_range", 20)
            .param("dice_bolt_damage", 5)
            .param("dice_gauge", 50)
            .param("jackpot_spin", 1.5)
            .build();

    private static final String GOLD = "#FFC83D";

    // ------------------------------------------------------------------ look: white shine, pale gold, deep gold
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFFFFF, 0xFFE082, 0xFFC83D);
    private static final Particle.DustTransition LUCK_DUST = PALETTE.glow(0.9f);
    private static final Particle.DustOptions GOLD_DUST = new Particle.DustOptions(PALETTE.edge(), 1.0f);
    private static final Color BOLT_COLOR = Color.fromRGB(0xFF, 0xE3, 0x4D);
    private static final Particle.DustOptions BOLT_DUST = new Particle.DustOptions(BOLT_COLOR, 0.8f);
    private static final Particle.DustOptions BUST_SMOKE = new Particle.DustOptions(Color.fromRGB(0x70, 0x70, 0x70),
            1.3f);
    private static final Particle.DustOptions MIGHT_DUST = new Particle.DustOptions(Color.fromRGB(0xFF, 0x5A, 0x5A),
            1.0f);
    private static final Particle.DustOptions SWIFT_DUST = new Particle.DustOptions(Color.fromRGB(0x7F, 0xDB, 0xFF),
            0.9f);
    /** A dark amber slot-machine window behind the reel text. */
    private static final Color REEL_WINDOW = Color.fromARGB(150, 40, 24, 0);
    private static final BlockData DICE_BLOCK = Material.WHITE_CONCRETE.createBlockData();

    /** Ticks the dice tumble before landing, and how long the result stays above the head. */
    private static final int ROLL_TICKS = 14;
    private static final int RESULT_HOLD_TICKS = 26;
    private static final int JACKPOT_HOLD_TICKS = 40;
    /** Upper bound of any floating animation, in case a step never ends it. */
    private static final long MAX_ANIMATION_TICKS = 30 * 20;
    /** Heights of the floating dice and slot reel above the owner's head (they never overlap). */
    private static final double DICE_LIFT = 0.95;
    private static final double REEL_LIFT = 1.6;
    private static final float DICE_SIZE = 0.42f;
    private static final float DICE_SCALE = 1.7f;
    private static final float DICE_LANDED_SCALE = 2.3f;
    private static final float REEL_SCALE = 1.25f;
    private static final int TUMBLE_STEP_TICKS = 2;
    // Coins (gold nugget item displays)
    private static final float COIN_SIZE = 0.35f;
    private static final int REEL_COINS = 6;
    private static final double REEL_COIN_ORBIT = 0.85;
    private static final int REEL_COIN_STEP_TICKS = 3;
    private static final int FOUNTAIN_COINS = 5;
    private static final double[] FLIP_HEIGHTS = {0.3, 0.55, 0.65, 0.6};
    private static final int FLIP_STEP_TICKS = 3;
    /** Luck feedback for tiny hits (damage over time) is throttled to this many ticks. */
    private static final long FEEDBACK_COOLDOWN = 10;
    private static final double BIG_HIT = 1.0;
    private static final int MAX_JACKPOT_ATTEMPTS = 64;

    /** Dice faces in order 1..6; the glyphs are U+2680..U+2685. */
    private enum Face {
        BUST("⚀", "#A0A0A0"),
        SWIFT("⚁", "#7FDBFF"),
        MIGHT("⚂", "#FF5A5A"),
        MEND("⚃", "#FF7EB6"),
        BOLT("⚄", "#FFE34D"),
        JACKPOT("⚅", GOLD);

        private final String glyph;
        private final String color;

        Face(String glyph, String color) {
            this.glyph = glyph;
            this.color = color;
        }

        int pips() {
            return ordinal() + 1;
        }
    }

    private long lastDodgeFeedback = Long.MIN_VALUE / 2;
    private long lastCritFeedback = Long.MIN_VALUE / 2;
    /** The hit that was just doubled, so its feedback shows only if it lands ({@link #onDamageDealt}). */
    private @Nullable EntityDamageByEntityEvent pendingCrit;
    private double pendingMultiplier = 1;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    // ------------------------------------------------------------------ passive: luck

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled() || event.getDamage() <= 0 || isInescapable(event)) {
            return;
        }
        if (!chance(params().num("dodge_chance"))) {
            return;
        }
        boolean lethal = event.getFinalDamage() >= player().getHealth();
        event.setCancelled(true);
        long now = ctx().currentTick();
        if (!lethal && event.getDamage() < BIG_HIT && now - lastDodgeFeedback < FEEDBACK_COOLDOWN) {
            return;
        }
        lastDodgeFeedback = now;
        // Heads, you miss: a gold coin flips up off the gambler's chest in a puff of lucky sparkle.
        Player owner = player();
        Location body = owner.getLocation().add(0, owner.getHeight() * 0.6, 0);
        Vfx.cloud(ctx(), body, Particle.DUST_COLOR_TRANSITION, 12, 0.35, 0, LUCK_DUST, false);
        Vfx.cloud(ctx(), body, Particle.WAX_OFF, 5, 0.3, 0, null, false);
        coinFlip(owner, body);
        Fx.sound(body, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.5f + Fx.jitter(0.2f));
        Fx.sound(body, Sound.BLOCK_NOTE_BLOCK_BELL, 0.5f, 1.8f);
        Fx.sound(body, Sound.BLOCK_CHAIN_HIT, 0.4f, 2.0f);
        owner.sendActionBar(Text.mm(lethal
                ? "<" + GOLD + "><b>✦ 기적의 행운!</b></" + GOLD + "> <white>치명상을 피했습니다</white>"
                : "<" + GOLD + ">✦ 행운!</" + GOLD + "> <gray>피해 무효</gray>"));
    }

    /** Damage that luck must not save from (the void would just kill again a tick later). */
    private static boolean isInescapable(EntityDamageEvent event) {
        DamageCause cause = event.getCause();
        return cause == DamageCause.VOID || cause == DamageCause.KILL || cause == DamageCause.SUICIDE;
    }

    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        pendingCrit = null;
        // Luck is the gambler's own: hits of its summons (e.g. a borrowed golem) are never doubled.
        if (event.isCancelled() || event.getDamage() <= 0 || !isOwnHit(event)) {
            return;
        }
        if (!chance(params().num("crit_chance"))) {
            return;
        }
        double multiplier = Math.max(1, params().num("crit_multiplier"));
        // Only scales the event's own damage: no new damage is dealt, so this cannot recurse.
        event.setDamage(event.getDamage() * multiplier);
        pendingCrit = event;
        pendingMultiplier = multiplier;
    }

    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event != pendingCrit) {
            return;
        }
        pendingCrit = null;
        double multiplier = pendingMultiplier;
        long now = ctx().currentTick();
        if (event.getDamage() < BIG_HIT * multiplier && now - lastCritFeedback < FEEDBACK_COOLDOWN) {
            return;
        }
        lastCritFeedback = now;
        // Double!: a burst of gold at the victim and a "×2" popping up over its head.
        Location at = victim.getLocation().add(0, victim.getHeight() * 0.6, 0);
        Vfx.burst(ctx(), at, PALETTE, 0.6, Particle.WAX_OFF, false);
        at.getWorld().spawnParticle(Particle.ITEM, at, Vfx.n(ctx(), 8), 0.25, 0.3, 0.25, 0.18,
                new ItemStack(Material.GOLD_INGOT));
        Vfx.cloud(ctx(), at, Particle.ENCHANTED_HIT, 10, 0.3, 0.25, null, false);
        critPopup(victim, multiplier);
        Fx.sound(at, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 1.1f + Fx.jitter(0.1f));
        Fx.sound(at, Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 1.9f);
        player().sendActionBar(Text.mm("<" + GOLD + "><b>✦ 더블!</b></" + GOLD + "> <white>피해 ×"
                + AbilityInfo.formatNumber(multiplier) + "</white>"));
    }

    /** The owner's own hits: melee, its projectiles and its ability damage, but not its summons'. */
    private boolean isOwnHit(EntityDamageByEntityEvent event) {
        Player owner = player();
        Entity damager = event.getDamager();
        return owner.equals(damager)
                || damager instanceof Projectile projectile && owner.equals(projectile.getShooter());
    }

    // ------------------------------------------------------------------ active: dice

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        BlockDisplay dice = spawnDice(owner);
        if (dice == null) {
            // No room for the show (display refused): the dice still lands, just without the tumble.
            land(null);
            return ActivationResult.success();
        }
        play(new DiceRoll(dice));
        Fx.sound(owner.getLocation(), Sound.BLOCK_DECORATED_POT_INSERT, 0.9f, 1.4f + Fx.jitter(0.1f));
        owner.sendActionBar(Text.mm("<" + GOLD + ">주사위를 굴립니다…</" + GOLD + ">"));
        return ActivationResult.success();
    }

    private @Nullable BlockDisplay spawnDice(Player owner) {
        try {
            BlockDisplay dice = Displays.block(ctx(), owner, displaySpot(owner, DICE_LIFT), DICE_BLOCK,
                    cube(new Quaternionf(), DICE_SIZE * 0.4f), ROLL_TICKS + 10);
            return dice.isValid() ? dice : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A white die (block display) tumbles above the owner's head, lands, and the face pops up in its place. */
    private final class DiceRoll implements DisplayAnimation {
        private final BlockDisplay dice;
        private final Quaternionf spin = new Quaternionf();
        private @Nullable TextDisplay result;
        private int age;
        private boolean landed;

        DiceRoll(BlockDisplay dice) {
            this.dice = dice;
        }

        @Override
        public boolean step() {
            Player owner = player();
            age++;
            Location spot = displaySpot(owner, DICE_LIFT);
            if (!landed) {
                if (age < ROLL_TICKS && dice.isValid()) {
                    dice.teleport(spot);
                    if (age % TUMBLE_STEP_TICKS == 0) {
                        tumble(spot);
                    }
                    return true;
                }
                landed = true;
                boolean shown = dice.isValid();
                if (shown) {
                    // Settles upright with a little bounce, then makes way for the face.
                    Displays.animate(dice, cube(new Quaternionf(), DICE_SIZE * 1.25f), 2);
                    ctx().runLater(owner, 2, () -> Displays.shrinkAway(ctx(), owner, dice, 3));
                }
                result = land(shown ? spot : null);
                return result != null;
            }
            if (result == null || !result.isValid()) {
                return false;
            }
            result.teleport(spot);
            return age < ROLL_TICKS + RESULT_HOLD_TICKS;
        }

        private void tumble(Location spot) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            spin.rotateXYZ((float) random.nextDouble(0.9, 1.7), (float) random.nextDouble(0.5, 1.3),
                    (float) random.nextDouble(0.2, 0.9));
            Displays.animate(dice, cube(spin, DICE_SIZE), TUMBLE_STEP_TICKS);
            Vfx.dot(spot.clone().add(random.nextDouble(-0.3, 0.3), random.nextDouble(-0.2, 0.3),
                    random.nextDouble(-0.3, 0.3)), Particle.WAX_OFF, null, false);
            Vfx.dot(spot, Particle.DUST, GOLD_DUST, false);
            Fx.sound(spot, Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 0.9f + 0.6f * age / ROLL_TICKS);
        }

        @Override
        public void end() {
            if (dice.isValid()) {
                dice.remove();
            }
            if (result != null && result.isValid()) {
                result.remove();
            }
        }
    }

    /**
     * Picks a face and pays it out. With {@code at} (where the die landed) the face pops up there as text; returns
     * that text display, null without one.
     */
    private @Nullable TextDisplay land(@Nullable Location at) {
        Player owner = player();
        LivingEntity boltTarget = nearestEnemy(owner, params().num("dice_bolt_range"));
        List<Face> pool = new ArrayList<>(List.of(Face.values()));
        if (boltTarget == null) {
            pool.remove(Face.BOLT);
        }
        Face face = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        TextDisplay result = null;
        if (at != null) {
            result = spawnText(owner, at, faceText(face), DICE_SCALE, RESULT_HOLD_TICKS + 6);
            if (result != null) {
                Displays.animateNextTick(ctx(), owner, result, Displays.centered(0, 0, 0, DICE_LANDED_SCALE,
                        DICE_LANDED_SCALE, DICE_LANDED_SCALE, 0, 0, 0), 4);
            }
            landingFlash(at, face);
        }

        String head = "<" + face.color + "><b>" + face.glyph + " " + face.pips() + "</b></" + face.color + "> ";
        Location feet = owner.getLocation();
        Location body = feet.clone().add(0, owner.getHeight() * 0.55, 0);
        switch (face) {
            case BUST -> {
                double damage = Math.min(params().num("dice_self_damage"), owner.getHealth() - 1);
                Vfx.cloud(ctx(), body, Particle.LARGE_SMOKE, 10, 0.3, 0.02, null, false);
                Vfx.cloud(ctx(), body, Particle.DUST, 8, 0.35, 0, BUST_SMOKE, false);
                Fx.sound(body, Sound.ENTITY_VILLAGER_NO, 0.9f, 0.8f + Fx.jitter(0.1f));
                Fx.sound(body, Sound.BLOCK_NOTE_BLOCK_BIT, 0.8f, 0.5f);
                owner.sendActionBar(Text.mm(head + "<gray>꽝!</gray>"));
                if (damage > 0) {
                    owner.damage(damage, DamageSource.builder(DamageType.MAGIC).build());
                }
            }
            case SWIFT -> {
                int ticks = (int) params().ticks("dice_buff_duration");
                owner.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, ticks, 1, false, true, true));
                Location ground = feet.clone().add(0, 0.1, 0);
                Vfx.dot(ground, Particle.SMALL_GUST, null, false);
                Vfx.shockwave(ctx(), owner, ground, 0.3, 1.5, 4, Particle.DUST, SWIFT_DUST, false);
                Vfx.ring(ctx(), ground, 0.6, 10, 0, Particle.CLOUD, null, false);
                Fx.sound(body, Sound.ENTITY_BREEZE_JUMP, 0.8f, 1.3f + Fx.jitter(0.1f));
                owner.sendActionBar(Text.mm(head + "<white>신속 II</white> <gray>"
                        + AbilityInfo.formatNumber(params().num("dice_buff_duration")) + "초</gray>"));
            }
            case MIGHT -> {
                int ticks = (int) params().ticks("dice_buff_duration");
                owner.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, ticks, 0, false, true, true));
                Vfx.cloud(ctx(), body, Particle.CRIT, 16, 0.35, 0.2, null, false);
                Vfx.ring(ctx(), body, 0.7, 14, 0, Particle.DUST, MIGHT_DUST, false);
                Vfx.ring(ctx(), body.clone().add(0, -0.4, 0), 0.55, 10, 0.3, Particle.DUST, MIGHT_DUST, false);
                Fx.sound(body, Sound.ENTITY_EVOKER_CAST_SPELL, 0.7f, 1.4f + Fx.jitter(0.1f));
                owner.sendActionBar(Text.mm(head + "<white>힘 I</white> <gray>"
                        + AbilityInfo.formatNumber(params().num("dice_buff_duration")) + "초</gray>"));
            }
            case MEND -> {
                ctx().heal(owner, params().num("dice_heal"));
                Vfx.cloud(ctx(), body.clone().add(0, 0.5, 0), Particle.HEART, 5, 0.4, 0, null, false);
                Vfx.helix(ctx(), feet, 0.55, owner.getHeight() + 0.3, 1.2, 2, 10, 0, Particle.DUST_COLOR_TRANSITION,
                        LUCK_DUST, false);
                Fx.sound(body, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f + Fx.jitter(0.1f));
                owner.sendActionBar(Text.mm(head + "<white>체력 "
                        + AbilityInfo.formatNumber(params().num("dice_heal")) + " 회복</white>"));
            }
            case BOLT -> strikeBolt(owner, boltTarget, head, at);
            case JACKPOT -> {
                double gauge = params().num("dice_gauge");
                ctx().addGauge(owner, gauge);
                Vfx.cloud(ctx(), body, Particle.TOTEM_OF_UNDYING, 24, 0.4, 0.25, null, false);
                Vfx.cloud(ctx(), body, Particle.WAX_ON, 10, 0.5, 0, null, false);
                coinFountain(at != null ? at : body.clone().add(0, 1.2, 0));
                Fx.sound(body, Sound.BLOCK_NOTE_BLOCK_BELL, 0.9f, 1.2f);
                Fx.sound(body, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f);
                owner.sendActionBar(Text.mm(head + "<" + GOLD + ">궁극기 게이지 +"
                        + AbilityInfo.formatNumber(gauge) + "%</" + GOLD + ">"));
            }
        }
        return result;
    }

    /** The die lands: a gold flash for a good face, a crack and gray smoke for a bust. */
    private void landingFlash(Location at, Face face) {
        if (face == Face.BUST) {
            at.getWorld().spawnParticle(Particle.BLOCK, at, Vfx.n(ctx(), 12), 0.15, 0.15, 0.15, 0, DICE_BLOCK);
            Vfx.cloud(ctx(), at, Particle.LARGE_SMOKE, 6, 0.2, 0.02, null, false);
            Fx.sound(at, Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
        } else {
            Vfx.burst(ctx(), at, PALETTE, 0.5, Particle.WAX_OFF, false);
            Fx.sound(at, Sound.BLOCK_NOTE_BLOCK_XYLOPHONE, 0.8f, 1.2f + 0.1f * face.pips());
        }
    }

    private void strikeBolt(Player owner, @Nullable LivingEntity target, String head, @Nullable Location from) {
        if (target == null || !target.isValid() || !ctx().isEnemy(owner, target)) {
            owner.sendActionBar(Text.mm(head + "<gray>낙뢰가 빗나갔습니다</gray>"));
            return;
        }
        Location strike = target.getLocation();
        World world = strike.getWorld();
        world.strikeLightningEffect(strike);
        Location chest = strike.clone().add(0, target.getHeight() * 0.5, 0);
        Location start = from != null ? from : owner.getLocation().add(0, owner.getHeight() + DICE_LIFT, 0);
        if (start.getWorld() == world) {
            zigzag(start, chest);
        }
        Vfx.cloud(ctx(), chest, Particle.ELECTRIC_SPARK, 24, 0.35, 0.15, null, false);
        ctx().damage(owner, target, params().num("dice_bolt_damage"));
        owner.sendActionBar(Text.mm(head + "<white>낙뢰!</white> <gray>→ </gray>")
                .append(target.name().colorIfAbsent(TextColor.color(0xFFE34D))));
    }

    /** A jagged golden bolt from the die to the target. */
    private void zigzag(Location from, Location to) {
        Vector delta = to.toVector().subtract(from.toVector());
        int segments = Math.max(3, Math.min(8, (int) Math.ceil(delta.length() / 2.5)));
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Location previous = from.clone();
        for (int i = 1; i <= segments; i++) {
            Location next = from.clone().add(delta.clone().multiply((double) i / segments));
            if (i < segments) {
                next.add(random.nextDouble(-0.4, 0.4), random.nextDouble(-0.4, 0.4), random.nextDouble(-0.4, 0.4));
            }
            Vfx.line(ctx(), previous, next, 2.5, Particle.ELECTRIC_SPARK, null, false);
            Vfx.line(ctx(), previous, next, 1.5, Particle.DUST, BOLT_DUST, false);
            previous = next;
        }
        Vfx.dot(to, Particle.FLASH, BOLT_COLOR, false);
    }

    private @Nullable LivingEntity nearestEnemy(Player owner, double range) {
        List<LivingEntity> enemies = ctx().enemiesNear(owner, owner.getLocation(), range);
        return enemies.isEmpty() ? null : enemies.getFirst();
    }

    private static Component faceText(Face face) {
        return Text.mm("<" + face.color + "><b>" + face.glyph + "</b></" + face.color + ">");
    }

    // ------------------------------------------------------------------ coins

    /** A gold coin flips up off {@code chest}, end over end, and vanishes at the top. */
    private void coinFlip(Player owner, Location chest) {
        Location at = chest.clone();
        at.setYaw(0);
        at.setPitch(0);
        float yaw = owner.getLocation().getYaw();
        ItemDisplay coin = Displays.item(ctx(), owner, at, new ItemStack(Material.GOLD_NUGGET),
                Displays.centered(0, 0, 0, COIN_SIZE, COIN_SIZE, COIN_SIZE, yaw, 0, 0),
                (long) FLIP_HEIGHTS.length * FLIP_STEP_TICKS + 8);
        Loop.repeat(ctx(), owner, 1, FLIP_STEP_TICKS, FLIP_HEIGHTS.length, run -> {
            Displays.animate(coin, Displays.centered(0, (float) FLIP_HEIGHTS[(int) run], 0, COIN_SIZE, COIN_SIZE,
                    COIN_SIZE, yaw, 170f * (run + 1), 0), FLIP_STEP_TICKS);
            return coin.isValid();
        }, () -> {
            if (coin.isValid()) {
                Vfx.dot(at.clone().add(0, FLIP_HEIGHTS[FLIP_HEIGHTS.length - 1], 0), Particle.WAX_OFF, null, false);
                Displays.shrinkAway(ctx(), owner, coin, 4);
            }
        });
    }

    /** Coins spring out of {@code at}, arc up and rain down around it. */
    private void coinFountain(Location at) {
        Player owner = player();
        Location spot = at.clone();
        spot.setYaw(0);
        spot.setPitch(0);
        ItemStack nugget = new ItemStack(Material.GOLD_NUGGET);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double base = random.nextDouble(Math.PI * 2);
        for (int i = 0; i < FOUNTAIN_COINS; i++) {
            double angle = base + i * Math.PI * 2 / FOUNTAIN_COINS;
            float yaw = (float) Math.toDegrees(angle);
            float x = (float) Math.cos(angle);
            float z = (float) Math.sin(angle);
            ItemDisplay coin = Displays.item(ctx(), owner, spot, nugget,
                    Displays.centered(0, 0, 0, 0.15f, 0.15f, 0.15f, yaw, 0, 0), 24);
            Displays.animateNextTick(ctx(), owner, coin, Displays.centered(x * 0.45f,
                    (float) random.nextDouble(0.6, 0.9), z * 0.45f, COIN_SIZE, COIN_SIZE, COIN_SIZE, yaw, 120, 0), 5);
            ctx().runLater(owner, 6, () -> Displays.animate(coin, Displays.centered(x * 1.4f, -1.3f, z * 1.4f,
                    COIN_SIZE, COIN_SIZE, COIN_SIZE, yaw, 280, 0), 8));
            ctx().runLater(owner, 14, () -> Displays.shrinkAway(ctx(), owner, coin, 5));
        }
        Fx.sound(at, Sound.BLOCK_TRIAL_SPAWNER_EJECT_ITEM, 0.7f, 1.4f + Fx.jitter(0.1f));
    }

    // ------------------------------------------------------------------ ultimate: jackpot

    @Override
    public ActivationResult onUltimate() {
        List<AbilityRegistry.Entry> pool = jackpotPool();
        if (pool.isEmpty()) {
            return ActivationResult.fail("발동할 수 있는 궁극기가 없습니다");
        }
        Player owner = player();
        int spinTicks = (int) Math.max(1, params().ticks("jackpot_spin"));
        Location spot = displaySpot(owner, REEL_LIFT);
        TextDisplay reel = spawnText(owner, spot,
                reelText(pool.get(ThreadLocalRandom.current().nextInt(pool.size())).info()), REEL_SCALE,
                spinTicks + JACKPOT_HOLD_TICKS + 10);
        if (reel == null) {
            // No show possible: pay out right away (and keep the gauge if nothing could fire).
            return payOut(pool, null)
                    ? ActivationResult.success()
                    : ActivationResult.fail("발동할 수 있는 궁극기가 없습니다");
        }
        reel.setBackgroundColor(REEL_WINDOW);
        play(new JackpotSpin(reel, pool, spinTicks, reelCoins(owner, spot, spinTicks + JACKPOT_HOLD_TICKS)));
        Fx.sound(owner.getLocation(), Sound.BLOCK_VAULT_OPEN_SHUTTER, 1.0f, 1.2f);
        Fx.sound(owner.getLocation(), Sound.BLOCK_VAULT_ACTIVATE, 0.8f, 1.3f + Fx.jitter(0.1f));
        owner.sendActionBar(Text.mm("<" + GOLD + "><b>★ 잭팟</b></" + GOLD + "> <gray>슬롯머신이 돌아갑니다…</gray>"));
        return ActivationResult.success();
    }

    /**
     * Every other ability enabled for this match (abilities.yml) whose ultimate works on a freshly bound instance.
     */
    private List<AbilityRegistry.Entry> jackpotPool() {
        List<AbilityRegistry.Entry> pool = new ArrayList<>();
        for (AbilityRegistry.Entry entry : ctx().registry().all()) {
            AbilityInfo info = entry.info();
            if (info.ultimateStandalone() && !info.id().equals(INFO.id()) && ctx().isEnabled(info.id())) {
                pool.add(entry);
            }
        }
        return pool;
    }

    /** Gold coins (item displays) circling the reel while it spins. */
    private List<ItemDisplay> reelCoins(Player owner, Location spot, long lifetime) {
        List<ItemDisplay> coins = new ArrayList<>();
        ItemStack nugget = new ItemStack(Material.GOLD_NUGGET);
        for (int i = 0; i < REEL_COINS; i++) {
            ItemDisplay coin = Displays.item(ctx(), owner, spot, nugget, coinOrbit(i, 0, 0.1f), lifetime);
            Displays.animateNextTick(ctx(), owner, coin, coinOrbit(i, 0, 1f), REEL_COIN_STEP_TICKS);
            coins.add(coin);
        }
        return coins;
    }

    /** Coin {@code index} on its orbit around the reel at {@code age} ticks, spinning on its own axis. */
    private static Transformation coinOrbit(int index, int age, float size) {
        double angle = Math.toRadians(index * 360.0 / REEL_COINS + age * 12.0);
        float s = COIN_SIZE * size;
        return Displays.centered((float) (Math.cos(angle) * REEL_COIN_ORBIT),
                (float) (Math.sin(age * 0.3 + index) * 0.15), (float) (Math.sin(angle) * REEL_COIN_ORBIT), s, s, s,
                age * 40f + index * 30f, 0, 0);
    }

    /** The slot machine reel: names flicker by fast, slow down, then the result is paid out. */
    private final class JackpotSpin implements DisplayAnimation {
        private final TextDisplay display;
        private final List<AbilityRegistry.Entry> pool;
        private final int spinTicks;
        private final List<ItemDisplay> coins;
        private int age;
        private int sinceChange;
        private int shownIndex;
        private boolean paid;

        JackpotSpin(TextDisplay display, List<AbilityRegistry.Entry> pool, int spinTicks, List<ItemDisplay> coins) {
            this.display = display;
            this.pool = pool;
            this.spinTicks = spinTicks;
            this.coins = coins;
        }

        @Override
        public boolean step() {
            if (!display.isValid()) {
                return false;
            }
            Player owner = player();
            age++;
            Location spot = displaySpot(owner, REEL_LIFT);
            display.teleport(spot);
            for (ItemDisplay coin : coins) {
                if (coin.isValid()) {
                    coin.teleport(spot);
                }
            }
            if (paid) {
                return age < spinTicks + JACKPOT_HOLD_TICKS;
            }
            if (age % REEL_COIN_STEP_TICKS == 0) {
                for (int i = 0; i < coins.size(); i++) {
                    Displays.animate(coins.get(i), coinOrbit(i, age, 1f), REEL_COIN_STEP_TICKS);
                }
            }
            if (age % 2 == 0) {
                // The machine running: a golden ring turning under the gambler's feet (seen from afar).
                Vfx.ring(ctx(), owner.getLocation().add(0, 0.1, 0), 1.1, 16, age * 0.2, Particle.DUST, GOLD_DUST,
                        true);
            }
            if (age >= spinTicks) {
                paid = true;
                if (payOut(pool, display)) {
                    scatterCoins(true);
                } else {
                    // The gauge was already consumed when the spin started: give it back.
                    ctx().addGauge(owner, 100);
                    display.text(Text.mm("<gray><b>꽝…</b></gray>"));
                    scatterCoins(false);
                    Vfx.cloud(ctx(), spot, Particle.LARGE_SMOKE, 10, 0.35, 0.02, null, false);
                    Vfx.cloud(ctx(), spot, Particle.DUST, 8, 0.4, 0, BUST_SMOKE, false);
                    Fx.sound(owner.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 0.9f, 0.5f);
                    owner.sendActionBar(Text.mm("<gray>발동할 수 있는 궁극기가 없어 게이지를 돌려받았습니다</gray>"));
                }
                return true;
            }
            double progress = (double) age / spinTicks;
            int interval = 1 + (int) Math.floor(progress * progress * 5);
            if (++sinceChange >= interval && pool.size() > 1) {
                sinceChange = 0;
                int next = ThreadLocalRandom.current().nextInt(pool.size() - 1);
                shownIndex = next >= shownIndex ? next + 1 : next;
                display.text(reelText(pool.get(shownIndex).info()));
                Fx.sound(owner.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.7f, 0.8f + (float) progress);
            }
            return true;
        }

        /** A win flings the coins outwards; a loss drops them. */
        private void scatterCoins(boolean won) {
            Player owner = player();
            for (int i = 0; i < coins.size(); i++) {
                ItemDisplay coin = coins.get(i);
                if (!coin.isValid()) {
                    continue;
                }
                double angle = Math.toRadians(i * 360.0 / REEL_COINS + age * 12.0);
                double reach = won ? 2.4 : REEL_COIN_ORBIT;
                Displays.animate(coin, Displays.centered((float) (Math.cos(angle) * reach), won ? 0.8f : -1.6f,
                        (float) (Math.sin(angle) * reach), COIN_SIZE, COIN_SIZE, COIN_SIZE, age * 40f + 170, 90, 0),
                        won ? 8 : 10);
                ctx().runLater(owner, won ? 8 : 10, () -> Displays.shrinkAway(ctx(), owner, coin, 5));
            }
        }

        @Override
        public void end() {
            if (display.isValid()) {
                display.remove();
            }
            for (ItemDisplay coin : coins) {
                if (coin.isValid()) {
                    coin.remove();
                }
            }
        }
    }

    /**
     * Tries the pool in random order until one ultimate succeeds (a failed ultimate consumes and changes nothing, by
     * contract) and announces it. Returns false if none could fire.
     */
    private boolean payOut(List<AbilityRegistry.Entry> pool, @Nullable TextDisplay display) {
        Player owner = player();
        List<AbilityRegistry.Entry> order = new ArrayList<>(pool);
        Collections.shuffle(order);
        int attempts = 0;
        for (AbilityRegistry.Entry entry : order) {
            if (++attempts > MAX_JACKPOT_ATTEMPTS) {
                break;
            }
            if (fireBorrowed(owner, entry.info())) {
                announceJackpot(owner, entry.info(), display);
                return true;
            }
        }
        return false;
    }

    private boolean fireBorrowed(Player owner, AbilityInfo info) {
        try {
            Ability borrowed = ctx().registry().create(info.id());
            borrowed.bind(owner, ctx(), ctx().paramsFor(info.id()));
            ActivationResult result = borrowed.onUltimate();
            return result != null && result.succeeded();
        } catch (RuntimeException e) {
            ctx().plugin().getLogger().log(Level.WARNING,
                    "Gambler jackpot failed to run the ultimate of '" + info.id() + "'", e);
            return false;
        }
    }

    /** Shows the jackpot on the reel and tells everyone watching the match which ultimate fired. */
    private void announceJackpot(Player owner, AbilityInfo info, @Nullable TextDisplay display) {
        Component ultimate = skillName(info);
        Component ability = Text.mm(info.name()).colorIfAbsent(info.color());
        if (display != null) {
            display.text(Text.mm("<" + GOLD + "><b>JACKPOT!</b></" + GOLD + ">\n<ult>",
                    Placeholder.component("ult", ultimate)));
            float pop = REEL_SCALE * 1.25f;
            Displays.animate(display, Displays.centered(0, 0, 0, pop, pop, pop, 0, 0, 0), 4);
        }
        ctx().announce(Text.mm("<" + GOLD + "><b>★ 잭팟!</b></" + GOLD + "> <white><player></white><gray>의 "
                        + "슬롯머신 →</gray> <ult> <gray>(<ability>)</gray>",
                Placeholder.unparsed("player", owner.getName()),
                Placeholder.component("ult", ultimate),
                Placeholder.component("ability", ability)));
        // A shower of gold: totem confetti, fireworks sparkle and a golden double helix around the winner.
        Location feet = owner.getLocation();
        Location at = feet.clone().add(0, owner.getHeight() + REEL_LIFT, 0);
        Vfx.cloud(ctx(), at, Particle.TOTEM_OF_UNDYING, 30, 0.5, 0.35, null, true);
        Vfx.cloud(ctx(), at, Particle.FIREWORK, 16, 0.3, 0.12, null, true);
        Vfx.burst(ctx(), at, PALETTE, 0.8, Particle.WAX_OFF, true);
        Vfx.helix(ctx(), feet, 1.0, owner.getHeight() + 1.2, 1.5, 2, 14, 0, Particle.DUST_COLOR_TRANSITION, LUCK_DUST,
                true);
        Fx.sound(at, Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.0f);
        Fx.sound(at, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.9f, 1.1f + Fx.jitter(0.1f));
        Fx.sound(at, Sound.BLOCK_VAULT_EJECT_ITEM, 0.8f, 1.2f + Fx.jitter(0.1f));
        owner.playSound(owner, Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 0.6f, 1.2f);
    }

    private static Component skillName(AbilityInfo info) {
        return Text.mm(info.ultimate().name()).colorIfAbsent(info.color());
    }

    private static Component reelText(AbilityInfo info) {
        return Text.mm("<gray>[</gray> <ult> <gray>]</gray>", Placeholder.component("ult", skillName(info)));
    }

    // ------------------------------------------------------------------ floating displays

    /** One step per tick; returns false when the animation is over. {@link #end} then removes its displays. */
    private interface DisplayAnimation {
        boolean step();

        void end();
    }

    /**
     * Runs {@code animation} every tick until it returns false, then ends it. If the owner is gone first, the
     * framework cancels the task and removes the tracked displays on revoke.
     */
    private void play(DisplayAnimation animation) {
        Loop.repeat(ctx(), player(), 1, 1, MAX_ANIMATION_TICKS, run -> animation.step(), animation::end);
    }

    private @Nullable TextDisplay spawnText(Player owner, Location at, Component text, float scale, long lifetime) {
        try {
            TextDisplay display = Displays.text(ctx(), owner, at, text, scale, lifetime);
            if (!display.isValid()) {
                return null;
            }
            display.setAlignment(TextDisplay.TextAlignment.CENTER);
            return display;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A "×2" popping up over the victim of a lucky double hit. */
    private void critPopup(LivingEntity victim, double multiplier) {
        Player owner = player();
        Location at = victim.getLocation().add(0, victim.getHeight() + 0.3, 0);
        TextDisplay popup = spawnText(owner, at, Text.mm("<" + GOLD + "><b>×" + AbilityInfo.formatNumber(multiplier)
                + "</b></" + GOLD + ">"), 0.8f, 20);
        if (popup == null) {
            return;
        }
        Displays.animateNextTick(ctx(), owner, popup, Displays.centered(0, 0.6f, 0, 1.4f, 1.4f, 1.4f, 0, 0, 0), 6);
        ctx().runLater(owner, 12, () -> {
            if (popup.isValid()) {
                Displays.shrinkAway(ctx(), owner, popup, 6);
            }
        });
    }

    private static Location displaySpot(Player owner, double lift) {
        Location at = owner.getLocation().add(0, owner.getHeight() + lift * Body.scaleOf(owner), 0);
        at.setYaw(0);
        at.setPitch(0);
        return at;
    }

    /** A cube of {@code size} turned by {@code rotation} around its own centre (a block display's origin). */
    private static Transformation cube(Quaternionf rotation, float size) {
        Quaternionf turn = new Quaternionf(rotation);
        Vector3f corner = turn.transform(new Vector3f(-size / 2));
        return new Transformation(corner, turn, new Vector3f(size), new Quaternionf());
    }

    // ------------------------------------------------------------------ helpers

    private static boolean chance(double percent) {
        return ThreadLocalRandom.current().nextDouble() * 100.0 < percent;
    }
}
