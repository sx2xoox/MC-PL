package io.github.sx2xoox.speedrunbot.mixin;

import io.github.sx2xoox.speedrunbot.server.FakePlayer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.vehicle.BoatEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The side that moves a boat is its driver's: for the bot (no client) that is the server (see BoatEntityMixin). */
@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method = "isLogicalSideForUpdatingMovement", at = @At("HEAD"), cancellable = true)
    private void srb$botDrivesItsBoat(CallbackInfoReturnable<Boolean> cir) {
        Object self = this;
        if (self instanceof BoatEntity && ((Entity) self).getPrimaryPassenger() instanceof FakePlayer) {
            cir.setReturnValue(true);
        }
    }
}
