package com.tunic.parallel.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.tunic.parallel.client.ClientCharacterSync;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PlayerTabOverlay.class)
public abstract class PlayerTabOverlayMixin {

    /** For players allowed to see usernames, a player on a character is listed with their username too. */
    @ModifyReturnValue(method = "getNameForDisplay", at = @At("RETURN"))
    private Component parallel$withUsername(Component original, PlayerInfo info) {
        return ClientCharacterSync.withUsername(info.getProfile().id(), info.getProfile().name(), original);
    }
}
