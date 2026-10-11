package com.tunic.parallel.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.tunic.parallel.client.CharacterSkins;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.UUID;

@Mixin(PlayerInfo.class)
public abstract class PlayerInfoMixin {

    /**
     * Players are drawn with their character's skin: everywhere a player's skin is looked up (the player
     * model, the first-person arm, heads in the tab list) goes through here. The local player uses the
     * active character in singleplayer and, on a server, the one the server accepted; other players use
     * what the server said their character looks like.
     */
    @ModifyReturnValue(method = "getSkin", at = @At("RETURN"))
    private PlayerSkin parallel$characterSkin(PlayerSkin original) {
        Minecraft minecraft = Minecraft.getInstance();
        UUID player = ((PlayerInfo) (Object) this).getProfile().id();
        if (!player.equals(minecraft.getUser().getProfileId())) {
            return CharacterSkins.forOtherPlayer(player, original);
        }
        return minecraft.isLocalServer()
                ? CharacterSkins.forActiveCharacter(original)
                : CharacterSkins.forServerCharacter(original);
    }
}
