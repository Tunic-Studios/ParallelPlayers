package com.tunic.parallel.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.tunic.parallel.character.CharacterSwap;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Player.class)
public abstract class PlayerMixin {

    /**
     * Chat, death and advancement messages, the join message and so on all go through the player's
     * name, so this is what gives the character its in-game nickname.
     */
    @ModifyReturnValue(method = "getName", at = @At("RETURN"))
    private Component parallel$characterNickname(Component original) {
        if (self() instanceof ServerPlayer player) {
            return CharacterSwap.nickname(player).<Component>map(Component::literal).orElse(original);
        }
        return original;
    }

    /**
     * Vanilla's display name hovers as the entity (type, UUID, name). For a nicknamed player the hover
     * shows the account's real username instead, so a nickname can always be traced back.
     */
    @ModifyReturnValue(method = "getDisplayName", at = @At("RETURN"))
    private Component parallel$showRealUsername(Component original) {
        if (self() instanceof ServerPlayer player
                && original instanceof MutableComponent name
                && CharacterSwap.nickname(player).isPresent()) {
            String username = player.getGameProfile().name();
            Component hover = Component.translatableWithFallback("parallel.nickname.username", "%s", username);
            return name.withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(hover)));
        }
        return original;
    }

    /** At runtime {@code this} is always the {@link Player} the mixin is applied to. */
    @Unique
    private Player self() {
        return (Player) (Object) this;
    }
}
