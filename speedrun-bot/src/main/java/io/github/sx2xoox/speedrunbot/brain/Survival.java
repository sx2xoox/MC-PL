package io.github.sx2xoox.speedrunbot.brain;

import io.github.sx2xoox.speedrunbot.core.InputLimits;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.ChickenEntity;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * Staying alive with what a player sees and does: hit hostile mobs that come close, back away from creepers, step
 * out of lava toward the last safe spot, swim up for air, eat when hungry (food carried or an animal close by).
 * {@link #tick()} returns true when it took the controls this tick (the run's goal waits).
 */
public final class Survival {
    private final RunBrain b;
    private int attackCooldown;
    private int fleeTicks;
    private Vec3d fleeFrom;
    private BlockPos lastSafe;
    private int eatTicks;
    private int lastHurtTick = -1000;
    private int ticks;
    private float lastHealth = 20f;
    private Entity hunt;
    private boolean pouredWater;
    private BlockPos waterAt;

    /** Direction (x, z) away from the known lava around the feet, toward the last safe spot when there is no lava known. */
    private Vec3d awayFromLava(BlockPos f) {
        double sx = 0, sz = 0;
        int n = 0;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -1; dy <= 3; dy++) {
                    net.minecraft.block.BlockState st = b.mem.get(f.getX() + dx, f.getY() + dy, f.getZ() + dz);
                    if (st != null && Blocks2.isLava(st)) {
                        sx += dx;
                        sz += dz;
                        n++;
                    }
                }
            }
        }
        Vec3d p = b.s.pos();
        if (n > 0 && (sx != 0 || sz != 0)) {
            return new Vec3d(-sx, 0, -sz);
        }
        if (lastSafe != null) {
            return new Vec3d(lastSafe.getX() + 0.5 - p.x, 0, lastSafe.getZ() + 0.5 - p.z);
        }
        return new Vec3d(-Math.sin(Math.toRadians(b.s.yaw())) * -1, 0, Math.cos(Math.toRadians(b.s.yaw())) * -1);
    }
    private int huntTicks;
    private int lookTicks;
    private int lookedForAttacker = -1;

    public Survival(RunBrain b) {
        this.b = b;
    }

    /** Eating in progress: keep the hotbar still. */
    public boolean busy() {
        return eatTicks > 0;
    }

    /** Hurt by what the player knows without looking: magma underfoot, no air left. */
    private boolean burnOrDrown() {
        net.minecraft.block.BlockState under = b.mem.get(b.feet().down());
        return b.s.air() <= 0 || (under != null && under.getBlock() == net.minecraft.block.Blocks.MAGMA_BLOCK);
    }

    public boolean tick() {
        ticks++;
        if (attackCooldown > 0) {
            attackCooldown--;
        }
        float hp = b.s.health();
        if (hp < lastHealth - 0.01f) {
            lastHurtTick = ticks;
        }
        lastHealth = hp;
        BlockPos f = b.feet();
        if (b.s.onGround() && !b.s.inLava() && !b.s.onFire() && !b.mover.lavaNear(f)) {
            lastSafe = f;
        }
        // 1. lava: water at the feet if we carry it (puts the lava out), and get away from the lava we know of
        if (b.s.inLava() || (b.s.onFire() && b.mover.lavaNear(f))) {
            interruptScreens();
            if (b.s.inLava() && b.inv().has(net.minecraft.item.Items.WATER_BUCKET) && !pouredWater) {
                b.c.lookAngles(b.s.yaw(), 90f);
                if (b.a.equip(net.minecraft.item.Items.WATER_BUCKET) == Actions.Status.DONE && b.s.pitch() > 80f && b.c.use()) {
                    pouredWater = true;
                    waterAt = f;
                    b.trace("water on the lava at " + f);
                }
                return true;
            }
            Vec3d away = awayFromLava(f);
            Vec3d p = b.s.pos();
            float yaw = InputLimits.yawTo(away.x, away.z);
            b.c.lookAngles(yaw, 0f);
            b.c.move(1f, 0f, true, true, false);
            return true;
        }
        if (pouredWater && waterAt != null && !b.s.onFire()) {
            // take the water back (it is the only bucket)
            pouredWater = false;
            b.recoverWater(waterAt);
            waterAt = null;
        }
        // 2. buried (gravel or sand fell on the head): dig the head free
        BlockPos eye = new BlockPos(b.s.eyePos());
        net.minecraft.block.BlockState es = b.mem.get(eye);
        if (es != null && Blocks2.pass(es) == Blocks2.Pass.SOLID && es.isOpaque()) {
            interruptScreens();
            b.a.mine(eye, false);
            return true;
        }
        // 2b. air: swim up
        if (b.s.air() < 100 && b.s.submerged() && !b.ocean.managesAir()) {
            b.c.lookAngles(b.s.yaw(), -60f);
            b.c.move(0.6f, 0f, true, false, false);
            interruptScreens();
            return true;
        }
        if (eatTicks > 0) {
            eatTicks--;
            b.c.lookAngles(b.s.yaw(), b.s.pitch());
            return true;
        }
        // 3. mobs
        if (fleeTicks > 0) {
            fleeTicks--;
            Vec3d p = b.s.pos();
            float yaw = InputLimits.yawTo(p.x - fleeFrom.x, p.z - fleeFrom.z);
            b.c.lookAngles(yaw, 0f);
            b.c.move(1f, 0f, b.s.horizontalCollision(), true, false);
            return true;
        }
        List<HostileEntity> near = b.perception.visible(HostileEntity.class, 6.0, null);
        if (!near.isEmpty()) {
            HostileEntity e = closest(near);
            double d = e.getPos().distanceTo(b.s.pos());
            if (e instanceof CreeperEntity && d < 4.5 && (b.s.health() < 14 || ((CreeperEntity) e).getFuseSpeed() > 0)) {
                fleeFrom = e.getPos();
                fleeTicks = 30;
                interruptScreens();
                b.trace("backing off from a creeper");
                return true;
            }
            boolean threat = d < 3.4 || (ticks - lastHurtTick < 60 && d < 6);
            if (threat) {
                interruptScreens();
                Vec3d aim = e.getPos().add(0, e.getHeight() * 0.6, 0);
                b.c.lookAt(aim);
                if (d > 2.8) {
                    b.c.move(1f, 0f, b.s.horizontalCollision(), false, false);
                }
                if (attackCooldown == 0 && d < 3.2 && b.c.aimed(aim, 15f)) {
                    hit();
                }
                return true;
            }
        }
        // shot at: charge the archer we see (healthy) or get out of its sight (hurt)
        if (ticks - lastHurtTick < 60) {
            List<net.minecraft.entity.mob.AbstractSkeletonEntity> archers = b.perception.visible(net.minecraft.entity.mob.AbstractSkeletonEntity.class, 16.0, null);
            if (!archers.isEmpty()) {
                net.minecraft.entity.mob.AbstractSkeletonEntity e = closest(archers);
                interruptScreens();
                double dArcher = e.getPos().distanceTo(b.s.pos());
                // charge only while healthy and close: chasing an archer that backs off across a cave cost runs
                // four arrows in a row (20 -> 2.5 hp in 15 s)
                if (b.s.health() > Math.max(12, b.p.get("safety.fleeHealth")) && dArcher < 10) {
                    Vec3d aim = e.getPos().add(0, e.getHeight() * 0.6, 0);
                    double d = e.getPos().distanceTo(b.s.pos());
                    if (d > 2.8) {
                        b.mover.go(Navigator.near(e.getBlockPos(), 1.5));
                    } else {
                        b.c.lookAt(aim);
                        if (attackCooldown == 0 && b.c.aimed(aim, 15f)) {
                            hit();
                        }
                    }
                } else {
                    fleeFrom = e.getPos();
                    fleeTicks = 40;
                    b.trace("hurt by an archer: getting out of sight");
                }
                return true;
            }
        }
        // hit by something not in view (a zombie behind us): turn around to see it, as a player would
        if (ticks - lastHurtTick < 24 && !b.s.inLava() && !b.s.onFire() && lastHurtTick != lookedForAttacker && !burnOrDrown()) {
            if (lookTicks == 0) {
                b.trace("hit by something out of view: looking around");
            }
            interruptScreens();
            b.c.lookAngles(b.s.yaw() + 45f, lookTicks % 4 < 2 ? 5f : -55f); // around and up (a shaft's mouth)
            if (++lookTicks >= 10) {
                lookTicks = 0;
                lookedForAttacker = lastHurtTick;
            }
            return true;
        }
        lookTicks = 0;
        // 4. food
        // hurt: health comes back only with the food bar at 18+, so eat earlier then (a run died at 2 hp with 8 bread)
        boolean regen = b.s.health() < 12 && b.s.food() < 18;
        if ((b.s.food() <= b.p.getInt("food.eatBelow") || regen) && !b.s.screenOpen() && !b.bench.busy() && !b.smelter.screenBusy()) {
            int slot = Actions.findSlot(b.s.inventory(), st -> st.getItem().isFood());
            if (slot >= 0) {
                if (b.a.equip(st -> st.getItem().isFood()) == Actions.Status.DONE && b.c.use()) {
                    eatTicks = 34;
                    b.trace("eating " + b.s.inventory().getMainHandStack());
                }
                return true;
            }
            if (b.s.food() <= 6 || (regen && b.s.food() <= 12)) {
                return huntAnimal();
            }
        }
        return false;
    }

    private boolean huntAnimal() {
        if (hunt == null || !hunt.isAlive() || ++huntTicks > 300) {
            hunt = null;
            huntTicks = 0;
            List<AnimalEntity> animals = b.perception.visible(AnimalEntity.class, 12.0,
                    a -> a instanceof CowEntity || a instanceof PigEntity || a instanceof SheepEntity || a instanceof ChickenEntity);
            if (animals.isEmpty()) {
                return false;
            }
            hunt = closest(animals);
        }
        Vec3d aim = hunt.getPos().add(0, hunt.getHeight() * 0.6, 0);
        double d = hunt.getPos().distanceTo(b.s.pos());
        if (d > 2.6) {
            b.mover.go(Navigator.near(hunt.getBlockPos(), 1.5));
        } else {
            b.c.lookAt(aim);
            if (attackCooldown == 0 && b.c.aimed(aim, 15f)) {
                b.c.attackEntity();
                attackCooldown = 12;
            }
        }
        return true;
    }

    /**
     * One full-strength hit: the sword when there is one (1.16 attack cooldown: a hit before the weapon recharged
     * does a fraction of the damage; sword 13 ticks, pickaxe 17).
     */
    private void hit() {
        boolean sword = Actions.findSlot(b.s.inventory(), st -> st.getItem() instanceof net.minecraft.item.SwordItem) >= 0;
        java.util.function.Predicate<ItemStack> w = sword ? st -> st.getItem() instanceof net.minecraft.item.SwordItem : Survival::weapon;
        if (!w.test(b.s.inventory().getMainHandStack())) {
            b.a.equip(w);
            attackCooldown = sword ? 13 : 17; // switching items restarts the attack cooldown
            return;
        }
        b.c.attackEntity();
        attackCooldown = sword ? 13 : 17;
    }

    private static boolean weapon(ItemStack st) {
        net.minecraft.item.Item i = st.getItem();
        return i instanceof net.minecraft.item.SwordItem || i instanceof net.minecraft.item.AxeItem || i instanceof net.minecraft.item.PickaxeItem;
    }

    private <T extends Entity> T closest(List<T> list) {
        T best = null;
        double bd = Double.MAX_VALUE;
        for (T e : list) {
            double d = e.getPos().squaredDistanceTo(b.s.pos());
            if (d < bd) {
                bd = d;
                best = e;
            }
        }
        return best;
    }

    private void interruptScreens() {
        if (b.bench.busy()) {
            b.bench.interrupt();
        }
        b.smelter.interrupt();
        b.looter().interrupt();
    }
}
