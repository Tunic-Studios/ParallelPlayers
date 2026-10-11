package com.tunic.parallel.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.tunic.parallel.client.CharacterSkins;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerMixin {

    /**
     * The local player is drawn with the active character's skin. Only in singleplayer: that is where the
     * character system applies, and other players on a server should not be affected.
     */
    @ModifyReturnValue(method = "getSkin", at = @At("RETURN"))
    private PlayerSkin parallel$characterSkin(PlayerSkin original) {
        if (self() instanceof LocalPlayer && Minecraft.getInstance().isLocalServer()) {
            return CharacterSkins.forActiveCharacter(original);
        }
        return original;
    }

    @Unique
    private AbstractClientPlayer self() {
        return (AbstractClientPlayer) (Object) this;
    }
}
