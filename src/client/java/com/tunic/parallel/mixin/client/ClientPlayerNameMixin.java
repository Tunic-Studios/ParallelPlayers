package com.tunic.parallel.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.tunic.parallel.client.ClientCharacterSync;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Player.class)
public abstract class ClientPlayerNameMixin {

    /**
     * Other players are named after the character they play as, which the server tells the client. The name
     * above their head and anything else drawn from it on this client follows; the tab list and chat come from
     * the server, which names them already.
     */
    @ModifyReturnValue(method = "getName", at = @At("RETURN"))
    private Component parallel$characterName(Component original) {
        if ((Object) this instanceof RemotePlayer player) {
            return ClientCharacterSync.alias(player.getUUID()).<Component>map(Component::literal).orElse(original);
        }
        return original;
    }
}
