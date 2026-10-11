package com.tunic.parallel.mixin.client;

import com.tunic.parallel.client.ClientCharacterSync;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @Shadow
    public abstract void disconnectFromWorld(Component message);

    /**
     * Leaving a server from the pause menu: when playing a character there, wait for its last save before
     * going, so the client's copy has everything that happened on the server.
     */
    @Inject(method = "disconnectFromWorld", at = @At("HEAD"), cancellable = true)
    private void parallel$saveCharacterFirst(Component message, CallbackInfo ci) {
        if (ClientCharacterSync.holdDisconnect((Minecraft) (Object) this, () -> this.disconnectFromWorld(message))) {
            ci.cancel();
        }
    }
}
