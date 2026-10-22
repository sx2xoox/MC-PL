package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

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
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
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
                    "받는 피해를 {dodge_chance}% 확률로 무시하고, 가하는 피해가 {crit_chance}% 확률로 "
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
    private static final Color GOLD_COLOR = Color.fromRGB(0xFF, 0xC8, 0x3D);
    private static final Color COIN_SHINE = Color.fromRGB(0xFF, 0xF4, 0xB8);
    private static final Particle.DustOptions GOLD_DUST = new Particle.DustOptions(GOLD_COLOR, 1.0f);
    private static final Particle.DustTransition LUCK_DUST = new Particle.DustTransition(GOLD_COLOR, COIN_SHINE, 0.9f);
    private static final Color TRANSPARENT = Color.fromARGB(0, 0, 0, 0);

    /** Ticks the dice tumble before landing, and how long the result stays above the head. */
    private static final int ROLL_TICKS = 14;
    private static final int RESULT_HOLD_TICKS = 26;
    private static final int JACKPOT_HOLD_TICKS = 40;
    /** Heights of the floating dice and slot reel above the owner's head (they never overlap). */
    private static final double DICE_LIFT = 0.95;
    private static final double REEL_LIFT = 1.6;
    private static final float DICE_SCALE = 1.7f;
    private static final float DICE_LANDED_SCALE = 2.3f;
    private static final float REEL_SCALE = 1.25f;
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
        Player owner = player();
        Location body = owner.getLocation().add(0, owner.getHeight() * 0.6, 0);
        World world = body.getWorld();
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, body, 14, 0.35, 0.5, 0.35, 0, LUCK_DUST);
        world.spawnParticle(Particle.ITEM, body, 6, 0.2, 0.3, 0.2, 0.12, new ItemStack(Material.GOLD_NUGGET));
        sound(body, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.5f + jitter(0.2f));
        sound(body, Sound.BLOCK_NOTE_BLOCK_BELL, 0.5f, 1.8f);
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
        if (event.isCancelled() || event.getDamage() <= 0) {
            return;
        }
        if (!chance(params().num("crit_chance"))) {
            return;
        }
        double multiplier = Math.max(1, params().num("crit_multiplier"));
        // Only scales the event's own damage: no new damage is dealt, so this cannot recurse.
        event.setDamage(event.getDamage() * multiplier);
        long now = ctx().currentTick();
        if (event.getDamage() < BIG_HIT * multiplier && now - lastCritFeedback < FEEDBACK_COOLDOWN) {
            return;
        }
        lastCritFeedback = now;
        Location at = victim.getLocation().add(0, victim.getHeight() * 0.6, 0);
        World world = at.getWorld();
        world.spawnParticle(Particle.ITEM, at, 10, 0.25, 0.3, 0.25, 0.18, new ItemStack(Material.GOLD_INGOT));
        world.spawnParticle(Particle.DUST, at, 10, 0.35, 0.45, 0.35, 0, GOLD_DUST);
        world.spawnParticle(Particle.ENCHANTED_HIT, at, 12, 0.3, 0.4, 0.3, 0.25);
        sound(at, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 1.1f + jitter(0.1f));
        sound(at, Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 1.9f);
        player().sendActionBar(Text.mm("<" + GOLD + "><b>✦ 더블!</b></" + GOLD + "> <white>피해 ×"
                + AbilityInfo.formatNumber(multiplier) + "</white>"));
    }

    // ------------------------------------------------------------------ active: dice

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        TextDisplay dice = spawnDisplay(owner, DICE_LIFT, DICE_SCALE);
        if (dice == null) {
            // No room for the show (display refused): the dice still lands, just without the tumble.
            land(null);
            return ActivationResult.success();
        }
        DiceRoll roll = new DiceRoll(dice);
        dice.text(faceText(roll.shown, false));
        startDisplay(dice, roll);
        sound(owner.getLocation(), Sound.BLOCK_DECORATED_POT_INSERT, 0.9f, 1.4f + jitter(0.1f));
        owner.sendActionBar(Text.mm("<" + GOLD + ">주사위를 굴립니다…</" + GOLD + ">"));
        return ActivationResult.success();
    }

    /** Tumbles the dice above the owner's head, then lands on a face and applies it. */
    private final class DiceRoll implements DisplayAnimation {
        private final TextDisplay display;
        private int age;
        private Face shown = randomFace(null);
        private boolean landed;

        DiceRoll(TextDisplay display) {
            this.display = display;
        }

        @Override
        public boolean step() {
            Player owner = player();
            age++;
            follow(display, owner, DICE_LIFT);
            if (!landed) {
                if (age < ROLL_TICKS) {
                    if (age % 2 == 0) {
                        shown = randomFace(shown);
                        display.text(faceText(shown, false));
                        sound(owner.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f,
                                0.9f + 0.6f * age / ROLL_TICKS);
                    }
                    return true;
                }
                landed = true;
                land(display);
                return true;
            }
            return age < ROLL_TICKS + RESULT_HOLD_TICKS;
        }
    }

    private void land(@Nullable TextDisplay display) {
        Player owner = player();
        LivingEntity boltTarget = nearestEnemy(owner, params().num("dice_bolt_range"));
        List<Face> pool = new ArrayList<>(List.of(Face.values()));
        if (boltTarget == null) {
            pool.remove(Face.BOLT);
        }
        Face face = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        if (display != null) {
            display.text(faceText(face, true));
            pop(display, DICE_LANDED_SCALE);
        }

        String head = "<" + face.color + "><b>" + face.glyph + " " + face.pips() + "</b></" + face.color + "> ";
        Location body = owner.getLocation().add(0, owner.getHeight() * 0.55, 0);
        World world = body.getWorld();
        switch (face) {
            case BUST -> {
                double damage = Math.min(params().num("dice_self_damage"), owner.getHealth() - 1);
                world.spawnParticle(Particle.LARGE_SMOKE, body, 10, 0.3, 0.4, 0.3, 0.02);
                sound(body, Sound.ENTITY_VILLAGER_NO, 0.9f, 0.8f + jitter(0.1f));
                sound(body, Sound.BLOCK_NOTE_BLOCK_BIT, 0.8f, 0.5f);
                owner.sendActionBar(Text.mm(head + "<gray>꽝!</gray>"));
                if (damage > 0) {
                    owner.damage(damage, DamageSource.builder(DamageType.MAGIC).build());
                }
            }
            case SWIFT -> {
                int ticks = (int) params().ticks("dice_buff_duration");
                owner.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, ticks, 1, false, true, true));
                world.spawnParticle(Particle.CLOUD, owner.getLocation().add(0, 0.1, 0), 12, 0.3, 0.05, 0.3, 0.06);
                sound(body, Sound.ENTITY_BREEZE_JUMP, 0.8f, 1.3f + jitter(0.1f));
                owner.sendActionBar(Text.mm(head + "<white>신속 II</white> <gray>"
                        + AbilityInfo.formatNumber(params().num("dice_buff_duration")) + "초</gray>"));
            }
            case MIGHT -> {
                int ticks = (int) params().ticks("dice_buff_duration");
                owner.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, ticks, 0, false, true, true));
                world.spawnParticle(Particle.CRIT, body, 16, 0.35, 0.45, 0.35, 0.2);
                sound(body, Sound.ENTITY_EVOKER_CAST_SPELL, 0.7f, 1.4f + jitter(0.1f));
                owner.sendActionBar(Text.mm(head + "<white>힘 I</white> <gray>"
                        + AbilityInfo.formatNumber(params().num("dice_buff_duration")) + "초</gray>"));
            }
            case MEND -> {
                ctx().heal(owner, params().num("dice_heal"));
                world.spawnParticle(Particle.HEART, body.clone().add(0, 0.5, 0), 5, 0.4, 0.3, 0.4, 0);
                sound(body, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f + jitter(0.1f));
                owner.sendActionBar(Text.mm(head + "<white>체력 "
                        + AbilityInfo.formatNumber(params().num("dice_heal")) + " 회복</white>"));
            }
            case BOLT -> strikeBolt(owner, boltTarget, head);
            case JACKPOT -> {
                double gauge = params().num("dice_gauge");
                ctx().addGauge(owner, gauge);
                world.spawnParticle(Particle.TOTEM_OF_UNDYING, body, 24, 0.4, 0.6, 0.4, 0.25);
                world.spawnParticle(Particle.WAX_ON, body, 10, 0.5, 0.6, 0.5, 0);
                sound(body, Sound.BLOCK_NOTE_BLOCK_BELL, 0.9f, 1.2f);
                sound(body, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f);
                owner.sendActionBar(Text.mm(head + "<" + GOLD + ">궁극기 게이지 +"
                        + AbilityInfo.formatNumber(gauge) + "%</" + GOLD + ">"));
            }
        }
    }

    private void strikeBolt(Player owner, @Nullable LivingEntity target, String head) {
        if (target == null || !target.isValid() || !ctx().isEnemy(owner, target)) {
            owner.sendActionBar(Text.mm(head + "<gray>낙뢰가 빗나갔습니다</gray>"));
            return;
        }
        Location strike = target.getLocation();
        World world = strike.getWorld();
        world.strikeLightningEffect(strike);
        Location chest = strike.clone().add(0, target.getHeight() * 0.5, 0);
        world.spawnParticle(Particle.ELECTRIC_SPARK, chest, 24, 0.35, 0.6, 0.35, 0.15);
        Location from = owner.getLocation().add(0, owner.getHeight() + DICE_LIFT, 0);
        world.spawnParticle(Particle.TRAIL, from, 6, 0.05, 0.05, 0.05, 0,
                new Particle.Trail(chest, Color.fromRGB(0xFF, 0xE3, 0x4D), 12));
        ctx().damage(owner, target, params().num("dice_bolt_damage"));
        owner.sendActionBar(Text.mm(head + "<white>낙뢰!</white> <gray>→ </gray>")
                .append(target.name().colorIfAbsent(TextColor.color(0xFFE34D))));
    }

    private @Nullable LivingEntity nearestEnemy(Player owner, double range) {
        List<LivingEntity> enemies = ctx().enemiesNear(owner, owner.getLocation(), range);
        return enemies.isEmpty() ? null : enemies.getFirst();
    }

    private static Face randomFace(@Nullable Face except) {
        Face[] faces = Face.values();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Face face;
        do {
            face = faces[random.nextInt(faces.length)];
        } while (face == except);
        return face;
    }

    private static Component faceText(Face face, boolean landed) {
        if (!landed) {
            return Text.mm("<white>" + face.glyph + "</white>");
        }
        return Text.mm("<" + face.color + "><b>" + face.glyph + "</b></" + face.color + ">");
    }

    // ------------------------------------------------------------------ ultimate: jackpot

    @Override
    public ActivationResult onUltimate() {
        List<AbilityRegistry.Entry> pool = jackpotPool();
        if (pool.isEmpty()) {
            return ActivationResult.fail("발동할 수 있는 궁극기가 없습니다");
        }
        Player owner = player();
        TextDisplay reel = spawnDisplay(owner, REEL_LIFT, REEL_SCALE);
        if (reel == null) {
            // No show possible: pay out right away (and keep the gauge if nothing could fire).
            return payOut(pool, null)
                    ? ActivationResult.success()
                    : ActivationResult.fail("발동할 수 있는 궁극기가 없습니다");
        }
        JackpotSpin spin = new JackpotSpin(reel, pool);
        reel.text(reelText(pool.get(ThreadLocalRandom.current().nextInt(pool.size())).info()));
        startDisplay(reel, spin);
        sound(owner.getLocation(), Sound.BLOCK_VAULT_OPEN_SHUTTER, 1.0f, 1.2f);
        owner.sendActionBar(Text.mm("<" + GOLD + "><b>★ 잭팟</b></" + GOLD + "> <gray>슬롯머신이 돌아갑니다…</gray>"));
        return ActivationResult.success();
    }

    /** Every other registered ability whose ultimate works on a freshly bound instance. */
    private List<AbilityRegistry.Entry> jackpotPool() {
        List<AbilityRegistry.Entry> pool = new ArrayList<>();
        for (AbilityRegistry.Entry entry : ctx().registry().all()) {
            AbilityInfo info = entry.info();
            if (info.ultimateStandalone() && !info.id().equals(INFO.id())) {
                pool.add(entry);
            }
        }
        return pool;
    }

    /** The slot machine reel: names flicker by fast, slow down, then the result is paid out. */
    private final class JackpotSpin implements DisplayAnimation {
        private final TextDisplay display;
        private final List<AbilityRegistry.Entry> pool;
        private final int spinTicks;
        private int age;
        private int sinceChange;
        private int shownIndex;
        private boolean paid;

        JackpotSpin(TextDisplay display, List<AbilityRegistry.Entry> pool) {
            this.display = display;
            this.pool = pool;
            this.spinTicks = (int) Math.max(1, params().ticks("jackpot_spin"));
        }

        @Override
        public boolean step() {
            Player owner = player();
            age++;
            follow(display, owner, REEL_LIFT);
            if (paid) {
                return age < spinTicks + JACKPOT_HOLD_TICKS;
            }
            if (age >= spinTicks) {
                paid = true;
                if (!payOut(pool, display)) {
                    // The gauge was already consumed when the spin started: give it back.
                    ctx().addGauge(owner, 100);
                    display.text(Text.mm("<gray><b>꽝…</b></gray>"));
                    sound(owner.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 0.9f, 0.5f);
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
                sound(owner.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.7f, 0.8f + (float) progress);
            }
            return true;
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
                announce(owner, entry.info(), display);
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

    private void announce(Player owner, AbilityInfo info, @Nullable TextDisplay display) {
        Component ultimate = skillName(info);
        Component ability = Text.mm(info.name()).colorIfAbsent(info.color());
        if (display != null) {
            display.text(Text.mm("<" + GOLD + "><b>JACKPOT!</b></" + GOLD + ">\n<ult>",
                    Placeholder.component("ult", ultimate)));
            pop(display, REEL_SCALE * 1.25f);
        }
        Component message = Text.mm("<" + GOLD + "><b>★ 잭팟!</b></" + GOLD + "> <white><player></white><gray>의 "
                        + "슬롯머신 →</gray> <ult> <gray>(<ability>)</gray>",
                Placeholder.unparsed("player", owner.getName()),
                Placeholder.component("ult", ultimate),
                Placeholder.component("ability", ability));
        World world = owner.getWorld();
        for (Player viewer : world.getPlayers()) {
            viewer.sendMessage(message);
        }
        Location at = owner.getLocation().add(0, owner.getHeight() + REEL_LIFT, 0);
        world.spawnParticle(Particle.TOTEM_OF_UNDYING, at, 30, 0.5, 0.4, 0.5, 0.35);
        world.spawnParticle(Particle.FIREWORK, at, 16, 0.3, 0.3, 0.3, 0.12);
        sound(at, Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.0f);
        sound(at, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.9f, 1.1f + jitter(0.1f));
        owner.playSound(owner, Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 0.6f, 1.2f);
    }

    private static Component skillName(AbilityInfo info) {
        return Text.mm(info.ultimate().name()).colorIfAbsent(info.color());
    }

    private static Component reelText(AbilityInfo info) {
        return Text.mm("<gray>[</gray> <ult> <gray>]</gray>", Placeholder.component("ult", skillName(info)));
    }

    // ------------------------------------------------------------------ floating display

    /** One step per tick; returns false when the display should be removed. */
    private interface DisplayAnimation {
        boolean step();
    }

    private @Nullable TextDisplay spawnDisplay(Player owner, double lift, float scale) {
        try {
            TextDisplay display = owner.getWorld().spawn(displaySpot(owner, lift), TextDisplay.class, d -> {
                d.setPersistent(false);
                d.setBillboard(Display.Billboard.CENTER);
                d.setAlignment(TextDisplay.TextAlignment.CENTER);
                d.setBackgroundColor(TRANSPARENT);
                d.setShadowed(true);
                d.setSeeThrough(false);
                d.setTeleportDuration(2);
                d.setBrightness(new Display.Brightness(15, 15));
                d.setTransformation(scaled(scale));
            });
            if (!display.isValid()) {
                return null;
            }
            return ctx().track(owner, display);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Runs {@code animation} every tick until it returns false or the owner is gone, then removes the display.
     * Both the task and the tracked display are also cleaned up by the framework on revoke.
     */
    private void startDisplay(TextDisplay display, DisplayAnimation animation) {
        BukkitTask[] task = new BukkitTask[1];
        task[0] = ctx().runTimer(player(), 1, 1, () -> {
            Player owner = player();
            boolean keep = display.isValid() && owner.isOnline() && ctx().isAlive(owner) && animation.step();
            if (!keep) {
                task[0].cancel();
                display.remove();
            }
        });
    }

    private static void follow(TextDisplay display, Player owner, double lift) {
        display.teleport(displaySpot(owner, lift));
    }

    private static Location displaySpot(Player owner, double lift) {
        Location at = owner.getLocation().add(0, owner.getHeight() + lift * scaleOf(owner), 0);
        at.setYaw(0);
        at.setPitch(0);
        return at;
    }

    /** A quick "pop" to a bigger size when the result lands. */
    private static void pop(TextDisplay display, float scale) {
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(4);
        display.setTransformation(scaled(scale));
    }

    private static Transformation scaled(float scale) {
        return new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(scale, scale, scale),
                new AxisAngle4f());
    }

    // ------------------------------------------------------------------ helpers

    private static boolean chance(double percent) {
        return ThreadLocalRandom.current().nextDouble() * 100.0 < percent;
    }

    private static double scaleOf(LivingEntity entity) {
        AttributeInstance scale = entity.getAttribute(Attribute.SCALE);
        return scale == null ? 1.0 : Math.max(0.1, scale.getValue());
    }

    private static void sound(Location at, Sound sound, float volume, float pitch) {
        at.getWorld().playSound(at, sound, SoundCategory.PLAYERS, volume, pitch);
    }

    private static float jitter(float amount) {
        return (ThreadLocalRandom.current().nextFloat() * 2 - 1) * amount;
    }
}
