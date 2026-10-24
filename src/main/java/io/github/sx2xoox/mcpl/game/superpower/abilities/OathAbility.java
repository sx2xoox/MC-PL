package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongPredicate;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Motion;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Display;
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
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 맹세의 능력자 — the fighter who grows strong by binding itself with vows. While it keeps a vow it hits harder, a
 * broken one burns it, and a vow kept to the end after real damage is rewarded; every vow is public, so the enemies try
 * to make it break its word. The vows: strike three times, never step back (from the nearest enemy), or stand still
 * after landing — and as its ultimate, a vow on its life: no retreat at all and no skills, but every swing lands heavy,
 * and a kill in time is the only way out without a price.
 *
 * <p>Look (vow gold, sealing-scroll paper, breach crimson): a band of paper slips lettered in gold circles its waist
 * while it keeps a vow, with the vow written over its head for everyone. A broken vow turns the slips crimson, they burn
 * up in smoke and "맹세 위반!" bursts over its head; a kept one makes them glow and rise away in gold. The vow on its life
 * is a blazing band of crimson-and-gold slips and a red cross flaring behind it whenever it tries to step back.
 */
public final class OathAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("oath", "맹세의 능력자", Material.ENCHANTED_BOOK)
            .color(TextColor.color(0xE6B422))
            .difficulty(3)
            .passive("맹세의 무게",
                    "맹세를 지키는 동안 주는 피해가 {vow_bonus}% 늘고 몸 주위에 금빛 글자 띠가 돕니다. 맹세를 어기면 \"맹세 "
                            + "위반!\" 띠가 붉게 타며 {break_damage} 피해(죽지는 않음)를 입고 {break_bind}초간 이동 속도가 "
                            + "{break_slow}% 느려지며 {break_silence}초간 침묵합니다. 맹세를 끝까지 지키고 그동안 적에게 "
                            + "{fulfil_damage} 이상 피해를 줬다면 '성취' — 체력 {fulfil_heal} 회복과 궁극기 게이지 "
                            + "{fulfil_gauge}%를 얻습니다.")
            .active("맹세",
                    "{vow_duration}초짜리 맹세를 선언합니다(머리 위 글자와 액션바로 모두에게 알려집니다). 그냥 쓰면 '세 번 "
                            + "벤다' — 그 안에 적을 {strike_count}번 맞히지(근접·능력) 못하면 위반입니다. 웅크리고 쓰면 "
                            + "'물러서지 않는다' — 뒤로 걷기 키를 누르거나 가장 가까운 적({retreat_range}블록 이내)에게서 "
                            + "멀어지는 쪽으로 걸으면 위반이고(남에게 밀려나는 건 상관없음), 대신 넉백을 받지 않습니다. 점프 "
                            + "중에 쓰면 '움직이지 않는다' — 착지 후 {still_duration}초간 이동·점프 키를 누르면 위반이고, 대신 "
                            + "근접 피해 보너스가 {still_bonus}%로 오르고 받는 피해가 {still_guard}% 줄어듭니다.",
                    10)
            .ultimate("목숨을 건 맹세",
                    "{life_duration}초간 '이 싸움에서 물러서지 않는다' — 이동 속도 +{life_speed}%, 근접 피해 "
                            + "+{life_bonus}%, 넉백 면역. 대신 능력을 쓸 수 없고 받는 피해가 {life_vulnerable}% 늘며, 뒤로 "
                            + "걷기 키를 누르는 동안에는 한 걸음도 움직일 수 없습니다. 그 안에 적을 처치하면 맹세 성취 — 체력 "
                            + "{life_heal} 회복. 못 하면 대가로 {life_cost} 피해를 입습니다(체력 1은 남김).")
            .ultimateNotStandalone()
            .param("vow_bonus", 30)
            .param("break_damage", 4)
            .param("break_bind", 1.5)
            .param("break_slow", 35)
            .param("break_silence", 1.5)
            .param("fulfil_damage", 4)
            .param("fulfil_heal", 2)
            .param("fulfil_gauge", 5)
            .param("vow_duration", 6)
            .param("strike_count", 3)
            .param("retreat_range", 12)
            .param("still_duration", 4)
            .param("still_bonus", 60)
            .param("still_guard", 30)
            .param("life_duration", 6)
            .param("life_speed", 20)
            .param("life_bonus", 60)
            .param("life_vulnerable", 20)
            .param("life_heal", 10)
            .param("life_cost", 4)
            .build();

    private static final String GOLD_TAG = "#E6B422";
    private static final String PAPER_TAG = "#EFE3C2";
    private static final String CRIMSON_TAG = "#B0132B";

    // ------------------------------------------------------------------ look
    /** Paper white, vow gold, old gold. */
    private static final Vfx.Palette GOLDEN = Vfx.Palette.of(0xFFF4C8, 0xE6B422, 0x8A6A12);
    /** Breach: pale red, crimson, dried blood. */
    private static final Vfx.Palette BREACH = Vfx.Palette.of(0xFF8A8A, 0xB0132B, 0x3A0008);
    private static final Color GOLD = Color.fromRGB(0xE6B422);
    private static final Color CRIMSON = Color.fromRGB(0xB0132B);
    private static final Color PAPER = Color.fromARGB(210, 0xEF, 0xE3, 0xC2);
    private static final Color BURNING_PAPER = Color.fromARGB(220, 0xB0, 0x13, 0x2B);
    private static final Color BLAZING_PAPER = Color.fromARGB(220, 0x5A, 0x10, 0x10);
    private static final Particle.DustOptions GOLD_DUST = new Particle.DustOptions(GOLD, 0.8f);
    private static final Particle.DustOptions CRIMSON_DUST = new Particle.DustOptions(CRIMSON, 1.0f);
    private static final Particle.DustTransition GOLD_GLOW = GOLDEN.glow(1.0f);
    private static final Particle.DustTransition CRIMSON_FADE = BREACH.fade(1.2f);
    /** Dark gold ink on the paper slips (bright gold is lost on paper). */
    private static final String INK_TAG = "#8A5A00";

    private static final int MAX_DISPLAYS = 24;
    private static final double TAU = Math.PI * 2;

    // the band of slips
    private static final int SLIPS = 6;
    private static final double BAND_RADIUS = 0.85;
    private static final double BAND_HEIGHT = 1.0;
    private static final double BAND_SPIN = 0.11;
    private static final float SLIP_SCALE = 0.55f;
    private static final double HEADLINE_ABOVE = 0.6;
    private static final double ANNOUNCE_RANGE = 24;

    // judging input
    /** Input right after a vow comes into force is not judged yet (a key still held from before). */
    private static final long START_GRACE = 6;
    /** Ticks a breaking input must last before it counts, plus one per 50 ms of ping (capped). */
    private static final int BASE_SLACK = 1;
    private static final int MAX_SLACK = 6;
    /** Walking away from the nearest enemy: the walk points this far (cosine) from it. */
    private static final double RETREAT_COS = Math.cos(Math.toRadians(110));
    private static final long LANDING_WAIT = 60;

    // ------------------------------------------------------------------ state

    private final List<Display> live = new ArrayList<>();
    private @Nullable OathListener listener;
    /** The owner's movement keys as its client last sent them. */
    private Keys keys = Keys.NONE;
    private @Nullable Vow vow;
    private @Nullable LifeVow lifeVow;
    /** True while the oath deals its own penalty: no vow modifies it. */
    private boolean selfHurt;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onGrant() {
        OathListener created = new OathListener();
        Bukkit.getPluginManager().registerEvents(created, ctx().plugin());
        listener = created;
    }

    @Override
    public void onRevoke() {
        if (listener != null) {
            HandlerList.unregisterAll(listener);
            listener = null;
        }
        vow = null;
        lifeVow = null;
        live.clear();
        keys = Keys.NONE;
    }

    /** The movement keys a client holds (only the ones a vow cares about). */
    private record Keys(boolean forward, boolean backward, boolean left, boolean right, boolean jump) {

        static final Keys NONE = new Keys(false, false, false, false, false);

        static Keys of(Input input) {
            return new Keys(input.isForward(), input.isBackward(), input.isLeft(), input.isRight(), input.isJump());
        }

        boolean walking() {
            return forward || backward || left || right;
        }
    }

    /**
     * The owner's input as the server receives it ({@link PlayerInputEvent} fires on every change). The vows judge
     * voluntary input, never position or velocity, so being pushed around never breaks one.
     */
    private final class OathListener implements Listener {

        @EventHandler(priority = EventPriority.MONITOR)
        public void onInput(PlayerInputEvent event) {
            if (!event.getPlayer().equals(player())) {
                return;
            }
            keys = Keys.of(event.getInput());
            LifeVow life = lifeVow;
            if (life != null) {
                life.lock();
            }
        }
    }

    /** Ticks of slack for a breaking input: the server hears the keys a ping late. */
    private static int slack(Player owner) {
        return Math.min(MAX_SLACK, BASE_SLACK + (int) Math.ceil(Math.max(0, owner.getPing()) / 50.0));
    }

    // ------------------------------------------------------------------ passive: the weight of a vow

    private boolean isOwnMelee(EntityDamageByEntityEvent event) {
        return !ctx().isAbilityDamage() && player().equals(event.getDamager())
                && (event.getCause() == DamageCause.ENTITY_ATTACK || event.getCause() == DamageCause.ENTITY_SWEEP_ATTACK);
    }

    /** Kept vows make every hit heavier: only changes the damage (the victim may still cancel the hit). */
    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled()) {
            return;
        }
        boolean melee = isOwnMelee(event);
        double bonus = 0;
        Vow current = vow;
        if (current != null && current.inForce()) {
            bonus = melee && current.kind == Kind.STILL ? params().num("still_bonus") : params().num("vow_bonus");
        }
        if (lifeVow != null) {
            bonus = Math.max(bonus, melee ? params().num("life_bonus") : params().num("vow_bonus"));
        }
        if (bonus > 0) {
            event.setDamage(event.getDamage() * (1 + bonus / 100.0));
        }
    }

    /** A hit of the owner's own landed: it counts for the vow. */
    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        Vow current = vow;
        if (current == null || !current.inForce()) {
            return;
        }
        Entity damager = event.getDamager();
        Player owner = player();
        boolean own = owner.equals(damager) || owner.equals(ctx().resolveAttacker(damager));
        if (own && !(damager instanceof LivingEntity summon && !summon.equals(owner))) {
            current.landed(event.getFinalDamage());
        }
    }

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled() || selfHurt) {
            return;
        }
        Vow current = vow;
        if (current != null && current.inForce() && current.kind == Kind.STILL) {
            double guard = Math.clamp(params().num("still_guard") / 100.0, 0.0, 1.0);
            event.setDamage(event.getDamage() * (1 - guard));
        }
        if (lifeVow != null) {
            event.setDamage(event.getDamage() * (1 + Math.max(0, params().num("life_vulnerable")) / 100.0));
        }
    }

    @Override
    public void onKill(Player victim) {
        LifeVow life = lifeVow;
        if (life != null) {
            life.fulfil(victim);
        }
    }

    /**
     * The oath's own price: dealt as plain damage the vows do not change, never lethal. It must not shield the owner:
     * a vanilla hurt starts invulnerability frames (enemy blows up to the price would be ignored for half a second),
     * so the frames and the last hurt are put back as they were before the price was paid.
     */
    private void hurtSelf(Player owner, double amount) {
        double damage = Math.min(amount, owner.getHealth() - 1);
        if (damage <= 0 || !ctx().isAlive(owner)) {
            return;
        }
        int frames = owner.getNoDamageTicks();
        double lastHurt = owner.getLastDamage();
        selfHurt = true;
        try {
            owner.setNoDamageTicks(0);
            owner.damage(damage, DamageSource.builder(DamageType.MAGIC).build());
        } finally {
            selfHurt = false;
            if (owner.isValid() && !owner.isDead()) {
                owner.setNoDamageTicks(frames);
                owner.setLastDamage(lastHurt);
            }
        }
    }

    // ------------------------------------------------------------------ active: a vow

    /** The three vows. */
    private enum Kind {
        STRIKES("세 번 벤다", new String[] {"三", "斬", "誓"}),
        NO_RETREAT("물러서지 않는다", new String[] {"不", "退", "誓"}),
        STILL("움직이지 않는다", new String[] {"不", "動", "誓"});

        final String words;
        final String[] glyphs;

        Kind(String words, String[] glyphs) {
            this.words = words;
            this.glyphs = glyphs;
        }
    }

    @Override
    public ActivationResult onActive() {
        if (lifeVow != null) {
            return ActivationResult.fail("목숨을 건 맹세 중에는 능력을 쓸 수 없습니다");
        }
        if (vow != null) {
            return ActivationResult.fail("이미 맹세를 지키고 있습니다");
        }
        Player owner = player();
        boolean airborne = !Motion.isGrounded(owner) && !owner.isInWater() && !owner.isFlying();
        Kind kind = airborne ? Kind.STILL : owner.isSneaking() ? Kind.NO_RETREAT : Kind.STRIKES;
        Vow sworn = new Vow(owner, kind);
        vow = sworn;
        sworn.swear();
        return ActivationResult.success();
    }

    /** A vow in progress: its rule is judged every tick while it is in force, the reward or the penalty at its end. */
    private final class Vow implements LongPredicate {

        final Kind kind;
        private final Player owner;
        private final Band band;
        private long from = Long.MAX_VALUE;
        private long until = Long.MAX_VALUE;
        private final long sworn;
        private int hits;
        private double dealt;
        private int breaking;
        private @Nullable NamespacedKey steady;
        private boolean over;

        Vow(Player owner, Kind kind) {
            this.owner = owner;
            this.kind = kind;
            this.sworn = ctx().currentTick();
            this.band = new Band(owner, kind.glyphs, PAPER, INK_TAG);
        }

        boolean inForce() {
            return !over && ctx().currentTick() >= from;
        }

        void swear() {
            long duration = kind == Kind.STILL ? Math.max(1, params().ticks("still_duration"))
                    : Math.max(1, params().ticks("vow_duration"));
            if (kind != Kind.STILL) {
                begin(duration);
            }
            band.unfurl();
            band.headline(headline(ctx().currentTick()));
            Location at = owner.getLocation();
            Fx.sound(at, Sound.ITEM_BOOK_PAGE_TURN, 1.0f, 0.8f);
            Fx.sound(at, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0f, 0.9f);
            Fx.sound(at, Sound.BLOCK_BELL_USE, 0.5f, 1.6f);
            Vfx.helix(ctx(), at, 0.9, 2.0, 1.0, 2, 14, 0, Particle.DUST_COLOR_TRANSITION, GOLD_GLOW, false);
            Vfx.cloud(ctx(), at.clone().add(0, 1, 0), Particle.ENCHANT, 16, 0.6, 0.6, null, false);
            String line = kind == Kind.STILL ? "착지 후 " + Fx.seconds(duration) + "초" : Fx.seconds(duration) + "초";
            announce("<b><" + GOLD_TAG + ">誓 " + kind.words + "</" + GOLD_TAG + "></b> <gray>(" + line + ")</gray>");
            Loop.repeat(ctx(), owner, 1, 1, LANDING_WAIT + duration + 2, this, this::expire);
        }

        /** The vow comes into force: the clock starts (for "stand still", on landing). */
        private void begin(long duration) {
            long now = ctx().currentTick();
            from = now;
            until = now + duration;
            if (kind == Kind.NO_RETREAT) {
                steady = ctx().addModifier(owner, owner, Attribute.KNOCKBACK_RESISTANCE, 1.0, Operation.ADD_NUMBER,
                        duration + 1);
            }
        }

        void landed(double damage) {
            hits++;
            dealt += Math.max(0, damage);
            if (kind == Kind.STRIKES) {
                int count = Math.max(1, params().integer("strike_count"));
                band.flash(hits <= count);
                if (hits == count) {
                    Fx.sound(owner.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.8f);
                }
            }
        }

        @Override
        public boolean test(long tick) {
            if (over) {
                return false;
            }
            long now = ctx().currentTick();
            if (kind == Kind.STILL && from == Long.MAX_VALUE) {
                if (Motion.isGrounded(owner) || owner.isInWater() || now - sworn >= LANDING_WAIT) {
                    begin(Math.max(1, params().ticks("still_duration")));
                    Location feet = owner.getLocation().add(0, 0.1, 0);
                    Vfx.ring(ctx(), feet, 0.9, 16, 0, Particle.DUST_COLOR_TRANSITION, GOLD_GLOW, false);
                    Fx.sound(feet, Sound.BLOCK_ANVIL_LAND, 0.4f, 1.6f);
                    owner.sendActionBar(Text.mm("<b><" + GOLD_TAG + ">誓 움직이지 않는다</" + GOLD_TAG + "></b> <gray>"
                            + "지금부터 " + Fx.seconds(until - now) + "초</gray>"));
                }
            } else if (now >= until) {
                complete();
                return false;
            } else if (now - from >= START_GRACE && breaks()) {
                if (++breaking > slack(owner)) {
                    violate();
                    return false;
                }
            } else {
                breaking = 0;
            }
            band.spin(tick, false);
            band.headline(headline(now));
            return true;
        }

        /** True while the owner's input breaks the vow. */
        private boolean breaks() {
            return switch (kind) {
                case STRIKES -> false;
                case STILL -> keys.walking() || keys.jump();
                case NO_RETREAT -> keys.backward() || retreating();
            };
        }

        /** Walking (by the keys held) away from the nearest enemy it can see in range. */
        private boolean retreating() {
            Vector walk = walkDirection(owner, keys);
            if (walk == null) {
                return false;
            }
            double range = Math.max(0, params().num("retreat_range"));
            for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), range)) {
                if (!seen(owner, enemy)) {
                    continue;
                }
                Vector to = Motion.horizontal(enemy.getLocation().toVector().subtract(owner.getLocation().toVector()));
                return to.lengthSquared() > 1.0E-6 && walk.dot(to) < RETREAT_COS;
            }
            return false;
        }

        private String headline(long now) {
            String clock = from == Long.MAX_VALUE ? "<gray>착지 대기</gray>"
                    : "<white>" + Fx.seconds(Math.max(0, until - now)) + "</white>";
            String progress = "";
            if (kind == Kind.STRIKES) {
                int count = Math.max(1, params().integer("strike_count"));
                progress = " <" + (hits >= count ? GOLD_TAG : "#FFFFFF") + ">⚔ " + Math.min(hits, count) + "/" + count
                        + "</" + (hits >= count ? GOLD_TAG : "#FFFFFF") + ">";
            }
            return "<b><" + GOLD_TAG + ">誓 " + kind.words + "</" + GOLD_TAG + "></b> " + clock + progress;
        }

        /** It ran its course: a kept vow (three strikes landed, for "strike three times") may be fulfilled. */
        private void complete() {
            if (kind == Kind.STRIKES && hits < Math.max(1, params().integer("strike_count"))) {
                violate();
                return;
            }
            finish();
            if (dealt + 1.0E-9 >= params().num("fulfil_damage")) {
                achieve();
            } else {
                band.release(false);
                owner.sendActionBar(Text.mm("<" + GOLD_TAG + ">誓</" + GOLD_TAG + "> <gray>맹세를 지켰지만 성취하지는 "
                        + "못했습니다 (피해 " + AbilityInfo.formatNumber(Math.round(dealt * 10) / 10.0) + "/"
                        + AbilityInfo.formatNumber(params().num("fulfil_damage")) + ")</gray>"));
            }
        }

        /** The loop ran out without a decision (it never landed): treat it as the end of the vow. */
        void expire() {
            if (!over) {
                complete();
            }
        }

        /** "성취": the band glows and rises away; health and gauge are the reward. */
        private void achieve() {
            ctx().heal(owner, Math.max(0, params().num("fulfil_heal")));
            double gauge = Math.max(0, params().num("fulfil_gauge"));
            if (gauge > 0) {
                ctx().addGauge(owner, gauge);
            }
            band.release(true);
            Location at = owner.getLocation().add(0, 1.0, 0);
            Vfx.cloud(ctx(), at, Particle.END_ROD, 12, 0.5, 0.05, null, false);
            Vfx.cloud(ctx(), at, Particle.HEART, 3, 0.4, 0, null, false);
            Fx.sound(at, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
            Fx.sound(at, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0f, 1.5f);
            pop(owner, "<b><" + GOLD_TAG + ">성취!</" + GOLD_TAG + "></b>", 1.6f);
            owner.sendActionBar(Text.mm("<b><" + GOLD_TAG + ">誓 성취!</" + GOLD_TAG + "></b> <white>+"
                    + AbilityInfo.formatNumber(params().num("fulfil_heal")) + " 체력, 궁극기 +"
                    + AbilityInfo.formatNumber(gauge) + "%</white>"));
        }

        /** "맹세 위반!": the band burns, the price is paid at once. */
        private void violate() {
            finish();
            band.burn();
            penalty(owner);
        }

        /** The vow is over, whatever came of it (also when the ultimate's vow supersedes it). */
        void finish() {
            over = true;
            if (vow == this) {
                vow = null;
            }
            if (steady != null) {
                ctx().removeModifier(owner, steady);
                steady = null;
            }
        }

        void supersede() {
            finish();
            band.release(false);
        }
    }

    /** A broken vow: damage, a heavy step and a silence. */
    private void penalty(Player owner) {
        hurtSelf(owner, Math.max(0, params().num("break_damage")));
        long bind = params().ticks("break_bind");
        double slow = Math.clamp(params().num("break_slow") / 100.0, 0.0, 1.0);
        if (bind > 0 && slow > 0) {
            ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, -slow, Operation.MULTIPLY_SCALAR_1, bind);
        }
        long silence = params().ticks("break_silence");
        if (silence > 0) {
            ctx().silence(owner, silence);
        }
        Location at = owner.getLocation().add(0, 1.0, 0);
        Vfx.burst(ctx(), at, BREACH, 0.7, Particle.SMALL_FLAME, false);
        Vfx.cloud(ctx(), at, Particle.LARGE_SMOKE, 6, 0.4, 0.03, null, false);
        Fx.sound(at, Sound.ENTITY_GENERIC_BURN, 1.0f, 0.8f);
        Fx.sound(at, Sound.BLOCK_GLASS_BREAK, 0.8f, 0.6f);
        Fx.sound(at, Sound.ENTITY_WITHER_HURT, 0.4f, 1.2f);
        pop(owner, "<b><" + CRIMSON_TAG + ">맹세 위반!</" + CRIMSON_TAG + "></b>", 1.8f);
        announce("<b><" + CRIMSON_TAG + ">맹세 위반!</" + CRIMSON_TAG + "></b>");
    }

    /** The direction the held keys walk in (horizontal, unit), null when no walking key is held. */
    private static @Nullable Vector walkDirection(Player owner, Keys keys) {
        double forward = (keys.forward() ? 1 : 0) - (keys.backward() ? 1 : 0);
        double left = (keys.left() ? 1 : 0) - (keys.right() ? 1 : 0);
        if (forward == 0 && left == 0) {
            return null;
        }
        Vector facing = Motion.horizontalFacing(owner.getLocation());
        Vector leftward = new Vector(facing.getZ(), 0, -facing.getX());
        Vector walk = facing.multiply(forward).add(leftward.multiply(left));
        return walk.lengthSquared() < 1.0E-6 ? null : walk.normalize();
    }

    // ------------------------------------------------------------------ ultimate: a vow on one's life

    @Override
    public ActivationResult onUltimate() {
        if (lifeVow != null) {
            return ActivationResult.fail("이미 목숨을 건 맹세 중입니다");
        }
        Vow running = vow;
        if (running != null) {
            running.supersede(); // the greater vow takes its place
        }
        LifeVow sworn = new LifeVow(player());
        lifeVow = sworn;
        sworn.swear();
        return ActivationResult.success();
    }

    /**
     * The vow on its life: faster, heavier blows and no knockback, but no skills, more damage taken and not a single step
     * back; a kill fulfils it, otherwise it costs health at the end.
     */
    private final class LifeVow implements LongPredicate {

        private final Player owner;
        private final long duration;
        private final long until;
        private final Band band;
        private final List<NamespacedKey> boosts = new ArrayList<>();
        private @Nullable NamespacedKey lock;
        private boolean fulfilled;
        private boolean over;

        LifeVow(Player owner) {
            this.owner = owner;
            this.duration = Math.max(1, params().ticks("life_duration"));
            this.until = ctx().currentTick() + duration;
            this.band = new Band(owner, new String[] {"命", "誓", "戰"}, BLAZING_PAPER, GOLD_TAG);
        }

        void swear() {
            double speed = params().num("life_speed") / 100.0;
            if (speed != 0) {
                boosts.add(ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, speed,
                        Operation.MULTIPLY_SCALAR_1, duration));
            }
            boosts.add(ctx().addModifier(owner, owner, Attribute.KNOCKBACK_RESISTANCE, 1.0, Operation.ADD_NUMBER,
                    duration));
            band.unfurl();
            band.headline("<b><" + CRIMSON_TAG + ">誓 목숨을 건 맹세</" + CRIMSON_TAG + "></b> <white>"
                    + Fx.seconds(duration) + "</white>");
            Location at = owner.getLocation();
            Fx.sound(at, Sound.ITEM_BOOK_PAGE_TURN, 1.0f, 0.6f);
            Fx.sound(at, Sound.ENTITY_WITHER_SHOOT, 0.6f, 0.6f);
            Fx.sound(at, Sound.BLOCK_BELL_USE, 1.0f, 0.7f);
            Fx.sound(at, Sound.ENTITY_RAVAGER_ROAR, 0.6f, 1.2f);
            Vfx.shockwave(ctx(), owner, at.clone().add(0, 0.1, 0), 0.4, 3.5, 6, Particle.DUST_COLOR_TRANSITION,
                    GOLD_GLOW, true);
            Vfx.helix(ctx(), at, 1.0, 2.4, 1.5, 2, 18, 0, Particle.DUST_COLOR_TRANSITION, CRIMSON_FADE, true);
            announce("<b><" + CRIMSON_TAG + ">誓 목숨을 건 맹세</" + CRIMSON_TAG + "></b> <white>이 싸움에서 물러서지 "
                    + "않는다</white> <gray>(" + Fx.seconds(duration) + "초)</gray>");
            lock();
            Loop.repeat(ctx(), owner, 1, 1, duration, this, this::end);
        }

        /** While the back key is held the oath-taker cannot move at all (about a ping of slip is accepted). */
        void lock() {
            if (over) {
                return;
            }
            long left = until - ctx().currentTick();
            if (keys.backward() && lock == null && left > 0) {
                lock = ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, -1.0, Operation.MULTIPLY_SCALAR_1, left);
                Location back = owner.getLocation().add(0, 1.0, 0)
                        .subtract(Motion.horizontalFacing(owner.getLocation()).multiply(0.7));
                Vfx.line(ctx(), back.clone().add(-0.35, 0.35, 0), back.clone().add(0.35, -0.35, 0), 8, Particle.DUST,
                        CRIMSON_DUST, false);
                Vfx.line(ctx(), back.clone().add(-0.35, -0.35, 0), back.clone().add(0.35, 0.35, 0), 8, Particle.DUST,
                        CRIMSON_DUST, false);
                Fx.sound(back, Sound.BLOCK_ANVIL_LAND, 0.3f, 1.8f);
                owner.sendActionBar(Text.mm("<b><" + CRIMSON_TAG + ">물러설 수 없다!</" + CRIMSON_TAG + "></b>"));
            } else if (!keys.backward() && lock != null) {
                ctx().removeModifier(owner, lock);
                lock = null;
            }
        }

        void fulfil(Player victim) {
            if (over || fulfilled) {
                return;
            }
            fulfilled = true;
            ctx().heal(owner, Math.max(0, params().num("life_heal")));
            Location at = owner.getLocation().add(0, 1.0, 0);
            Vfx.burst(ctx(), at, GOLDEN, 0.8, Particle.END_ROD, true);
            Vfx.cloud(ctx(), at, Particle.HEART, 5, 0.5, 0, null, false);
            Fx.sound(at, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.2f);
            Fx.sound(at, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 0.8f);
            pop(owner, "<b><" + GOLD_TAG + ">맹세 성취!</" + GOLD_TAG + "></b>", 2.0f);
            owner.sendActionBar(Text.mm("<b><" + GOLD_TAG + ">誓 맹세 성취!</" + GOLD_TAG + "></b> <white><name></white> "
                    + "<gray>처치 — 체력 +" + AbilityInfo.formatNumber(params().num("life_heal")) + "</gray>",
                    Placeholder.component("name", victim.name())));
        }

        @Override
        public boolean test(long tick) {
            if (over) {
                return false;
            }
            lock();
            band.spin(tick, true);
            long left = Math.max(0, until - ctx().currentTick());
            band.headline("<b><" + CRIMSON_TAG + ">誓 목숨을 건 맹세</" + CRIMSON_TAG + "></b> <white>"
                    + Fx.seconds(left) + "</white>" + (fulfilled ? " <" + GOLD_TAG + ">✔</" + GOLD_TAG + ">" : ""));
            if (tick % 3 == 0) {
                Location feet = owner.getLocation().add(0, 0.1, 0);
                Vfx.ring(ctx(), feet, 0.7, 10, tick * 0.3, Particle.DUST_COLOR_TRANSITION, CRIMSON_FADE, false);
                Vfx.dot(feet.clone().add(Fx.jitter(0.4f), 0.2, Fx.jitter(0.4f)), Particle.SMALL_FLAME, null, false);
            }
            return true;
        }

        /** The vow ends: kept by a kill, or paid for. */
        void end() {
            if (over) {
                return;
            }
            over = true;
            if (lifeVow == this) {
                lifeVow = null;
            }
            for (NamespacedKey key : boosts) {
                ctx().removeModifier(owner, key);
            }
            boosts.clear();
            if (lock != null) {
                ctx().removeModifier(owner, lock);
                lock = null;
            }
            if (fulfilled) {
                band.release(true);
                return;
            }
            band.burn();
            hurtSelf(owner, Math.max(0, params().num("life_cost")));
            Location at = owner.getLocation().add(0, 1.0, 0);
            Vfx.burst(ctx(), at, BREACH, 0.8, Particle.SMALL_FLAME, false);
            Fx.sound(at, Sound.ENTITY_GENERIC_BURN, 1.0f, 0.6f);
            Fx.sound(at, Sound.ENTITY_WITHER_HURT, 0.5f, 0.9f);
            pop(owner, "<b><" + CRIMSON_TAG + ">대가</" + CRIMSON_TAG + "></b>", 1.8f);
            owner.sendActionBar(Text.mm("<" + CRIMSON_TAG + ">誓 맹세를 이루지 못했습니다</" + CRIMSON_TAG + "> <gray>대가 "
                    + AbilityInfo.formatNumber(params().num("life_cost")) + " 피해</gray>"));
        }
    }

    // ------------------------------------------------------------------ the band of paper slips

    /**
     * Six paper slips lettered with the vow, circling the waist, and the vow itself written over the head. Everyone sees
     * them: a vow is a public promise.
     */
    private final class Band {

        private final Player owner;
        private final String[] glyphs;
        private final Color paper;
        private final String ink;
        private final List<TextDisplay> slips = new ArrayList<>();
        private @Nullable TextDisplay title;
        private String shown = "";
        private double phase;

        Band(Player owner, String[] glyphs, Color paper, String ink) {
            this.owner = owner;
            this.glyphs = glyphs;
            this.paper = paper;
            this.ink = ink;
        }

        void unfurl() {
            for (int i = 0; i < SLIPS && reserve(1); i++) {
                TextDisplay slip = keep(Displays.text(ctx(), owner, slipPoint(i, 0), glyph(i, ink), 0.05f, 0));
                slip.setBackgroundColor(paper);
                slip.setTeleportDuration(1);
                slips.add(slip);
                Displays.animateNextTick(ctx(), owner, slip, Displays.centered(0, 0, 0, SLIP_SCALE, SLIP_SCALE,
                        SLIP_SCALE, 0, 0, 0), 4 + i);
            }
            if (reserve(1)) {
                title = keep(Displays.text(ctx(), owner, titlePoint(), Text.mm(""), 0.1f, 0));
                title.setTeleportDuration(1);
                Displays.animateNextTick(ctx(), owner, title, Displays.centered(0, 0, 0, 0.9f, 0.9f, 0.9f, 0, 0, 0), 4);
            }
        }

        private net.kyori.adventure.text.Component glyph(int index, String color) {
            return Text.mm("<b><" + color + ">" + glyphs[index % glyphs.length] + "</" + color + "></b>");
        }

        private Location slipPoint(int index, double bob) {
            double angle = phase + index * TAU / SLIPS;
            return owner.getLocation().add(Math.cos(angle) * BAND_RADIUS, BAND_HEIGHT + bob, Math.sin(angle)
                    * BAND_RADIUS);
        }

        private Location titlePoint() {
            return owner.getLocation().add(0, owner.getHeight() + HEADLINE_ABOVE, 0);
        }

        /** Turns the band around the waist and draws a thin gold thread joining the slips. */
        void spin(long tick, boolean blazing) {
            phase += blazing ? BAND_SPIN * 1.6 : BAND_SPIN;
            for (int i = 0; i < slips.size(); i++) {
                TextDisplay slip = slips.get(i);
                if (slip.isValid()) {
                    Displays.moveTo(slip, slipPoint(i, Math.sin(tick * 0.15 + i) * 0.06));
                }
            }
            TextDisplay headline = title;
            if (headline != null && headline.isValid()) {
                Displays.moveTo(headline, titlePoint());
            }
            if (tick % 2 == 0) {
                Location ring = owner.getLocation().add(0, BAND_HEIGHT, 0);
                Vfx.ring(ctx(), ring, BAND_RADIUS, 12, phase + 0.26, Particle.DUST, blazing ? CRIMSON_DUST : GOLD_DUST,
                        false);
            }
        }

        void headline(String mini) {
            TextDisplay headline = title;
            if (headline != null && headline.isValid() && !mini.equals(shown)) {
                shown = mini;
                headline.text(Text.mm(mini));
            }
        }

        /** A strike counted: the slips flash bright for a moment. */
        void flash(boolean counted) {
            for (TextDisplay slip : slips) {
                if (slip.isValid()) {
                    Displays.animate(slip, Displays.centered(0, 0, 0, SLIP_SCALE * 1.4f, SLIP_SCALE * 1.4f,
                            SLIP_SCALE * 1.4f, 0, 0, 0), 2);
                    ctx().runLater(owner, 3, () -> Displays.animate(slip, Displays.centered(0, 0, 0, SLIP_SCALE,
                            SLIP_SCALE, SLIP_SCALE, 0, 0, 0), 3));
                }
            }
            if (counted) {
                Vfx.ring(ctx(), owner.getLocation().add(0, BAND_HEIGHT, 0), BAND_RADIUS + 0.15, 16, 0,
                        Particle.DUST_COLOR_TRANSITION, GOLD_GLOW, false);
                Fx.sound(owner.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.6f, 1.4f + Fx.jitter(0.1f));
            }
        }

        /** Kept: the slips glow and float up and away (or simply fade when nothing was achieved). */
        void release(boolean glory) {
            for (int i = 0; i < slips.size(); i++) {
                TextDisplay slip = slips.get(i);
                if (!slip.isValid()) {
                    continue;
                }
                if (glory) {
                    slip.text(glyph(i, GOLD_TAG));
                    slip.setTeleportDuration(12);
                    Location up = slip.getLocation().add(0, 1.6 + i * 0.1, 0);
                    ctx().runLater(owner, 1, () -> Displays.moveTo(slip, up));
                    ctx().runLater(owner, 10, () -> {
                        if (slip.isValid()) {
                            Displays.shrinkAway(ctx(), owner, slip, 5);
                        }
                    });
                } else {
                    Displays.shrinkAway(ctx(), owner, slip, 5);
                }
            }
            dropTitle();
        }

        /** Broken: the slips turn crimson, read "破" and burn up in smoke. */
        void burn() {
            for (TextDisplay slip : slips) {
                if (!slip.isValid()) {
                    continue;
                }
                slip.setBackgroundColor(BURNING_PAPER);
                slip.text(Text.mm("<b><white>破</white></b>"));
                Location at = slip.getLocation();
                Vfx.dot(at, Particle.SMALL_FLAME, null, false);
                Vfx.dot(at, Particle.DUST_COLOR_TRANSITION, CRIMSON_FADE, false);
                ctx().runLater(owner, 6, () -> {
                    if (slip.isValid()) {
                        Vfx.dot(slip.getLocation(), Particle.SMOKE, null, false);
                        Displays.shrinkAway(ctx(), owner, slip, 5);
                    }
                });
            }
            TextDisplay headline = title;
            if (headline != null && headline.isValid()) {
                headline.text(Text.mm("<b><" + CRIMSON_TAG + ">" + Text.miniMessage().stripTags(shown) + "</"
                        + CRIMSON_TAG + "></b>"));
            }
            dropTitle();
        }

        private void dropTitle() {
            TextDisplay headline = title;
            title = null;
            if (headline != null && headline.isValid()) {
                ctx().runLater(owner, 8, () -> {
                    if (headline.isValid()) {
                        Displays.shrinkAway(ctx(), owner, headline, 4);
                    }
                });
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** The vow for everyone near to read on the action bar (the owner sees it on its own action bar too). */
    private void announce(String mini) {
        Player owner = player();
        Location at = owner.getLocation();
        for (Player near : at.getWorld().getPlayers()) {
            if (near.equals(owner)) {
                near.sendActionBar(Text.mm(mini));
            } else if (near.getLocation().distanceSquared(at) <= ANNOUNCE_RANGE * ANNOUNCE_RANGE) {
                near.sendActionBar(Text.mm("<gray><name>:</gray> " + mini, Placeholder.component("name",
                        owner.name())));
            }
        }
    }

    /** A word popping up over the oath-taker's head and fading. */
    private void pop(Player owner, String mini, float scale) {
        if (!reserve(1)) {
            return;
        }
        Location at = owner.getLocation().add(0, owner.getHeight() + 1.0, 0);
        TextDisplay text = keep(Displays.text(ctx(), owner, at, Text.mm(mini), 0.3f, 26));
        Displays.animateNextTick(ctx(), owner, text, Displays.centered(0, 0.3f, 0, scale, scale, scale, 0, 0, 0), 4);
        ctx().runLater(owner, 18, () -> {
            if (text.isValid()) {
                Displays.shrinkAway(ctx(), owner, text, 5);
            }
        });
    }

    /** True unless {@code entity} is a player hidden from {@code owner} (stealth). */
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
}
