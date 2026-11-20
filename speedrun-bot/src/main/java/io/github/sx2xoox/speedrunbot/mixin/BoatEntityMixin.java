package io.github.sx2xoox.speedrunbot.mixin;

import io.github.sx2xoox.speedrunbot.server.FakePlayer;
import net.minecraft.entity.vehicle.BoatEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A boat ridden by a player is driven by that player's client: the client runs the boat's physics with the keys held
 * (BoatEntity#updatePaddles after updateVelocity) and sends the result. The bot has no client, so for a boat it rides
 * the server runs exactly that client code with the keys the bot holds (BoatEntity#setInputs, as
 * ClientPlayerEntity#tickRiding does). Physics are vanilla; only the side that computes them moves.
 */
@Mixin(BoatEntity.class)
public abstract class BoatEntityMixin implements io.github.sx2xoox.speedrunbot.server.BoatControl {
    private static final boolean DEBUG = Boolean.getBoolean("srb.debugBoat");
    @Shadow
    private BoatEntity.Location location;
    @Shadow
    private double waterLevel;
    @Shadow
    private boolean pressingLeft;
    @Shadow
    private boolean pressingRight;
    @Shadow
    private boolean pressingForward;
    @Shadow
    private boolean pressingBack;

    @Override
    public void srb$setInputs(boolean left, boolean right, boolean forward, boolean back) {
        pressingLeft = left;
        pressingRight = right;
        pressingForward = forward;
        pressingBack = back;
    }

    @Shadow
    private void updatePaddles() {
        throw new AssertionError();
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/vehicle/BoatEntity;updateVelocity()V", shift = At.Shift.AFTER))
    private void srb$paddleForTheBot(CallbackInfo ci) {
        BoatEntity self = (BoatEntity) (Object) this;
        if (!self.world.isClient && self.getPrimaryPassenger() instanceof FakePlayer) {
            updatePaddles();
            if (DEBUG && self.getVelocity().y < -0.1) {
                System.out.println("[srb] boat " + location + " y=" + self.getY() + " v=" + self.getVelocity() + " waterLevel=" + waterLevel
                        + " at " + self.world.getBlockState(self.getBlockPos()) + " loaded=" + self.world.isChunkLoaded(self.getBlockPos()));
            }
        }
    }
}
