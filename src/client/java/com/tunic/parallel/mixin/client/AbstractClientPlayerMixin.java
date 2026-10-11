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
     * The local player is drawn with their character's skin: the active one in singleplayer, and on a server
     * the one the server accepted. Other players are drawn as usual.
     */
    @ModifyReturnValue(method = "getSkin", at = @At("RETURN"))
    private PlayerSkin parallel$characterSkin(PlayerSkin original) {
        if (!(self() instanceof LocalPlayer)) {
            return original;
        }
        return Minecraft.getInstance().isLocalServer()
                ? CharacterSkins.forActiveCharacter(original)
                : CharacterSkins.forServerCharacter(original);
    }

    @Unique
    private AbstractClientPlayer self() {
        return (AbstractClientPlayer) (Object) this;
    }
}
