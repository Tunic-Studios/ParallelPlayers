package com.tunic.parallel_players.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.tunic.parallel_players.character.CharacterSwap;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {

    @ModifyReturnValue(method = "getTabListDisplayName", at = @At("RETURN"))
    private @Nullable Component parallelplayers$characterTabName(@Nullable Component original) {
        if (original != null) {
            return original;
        }
        return CharacterSwap.nickname((ServerPlayer) (Object) this).<Component>map(Component::literal).orElse(null);
    }
}
