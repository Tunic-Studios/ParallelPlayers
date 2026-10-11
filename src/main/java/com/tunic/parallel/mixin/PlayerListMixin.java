package com.tunic.parallel.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.DataFixer;
import com.tunic.parallel.character.CharacterSwap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;
import java.util.Optional;

@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    @Shadow
    @Final
    private MinecraftServer server;

    // The handler has to take the target method's return type, so the Optional parameter is not a choice.
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    @ModifyReturnValue(method = "loadPlayerData", at = @At("RETURN"))
    private Optional<CompoundTag> parallel$swapInCharacter(Optional<CompoundTag> original, NameAndId nameAndId) {
        return CharacterSwap.onLoad(this.server, nameAndId, original.orElse(null));
    }

    @ModifyReturnValue(method = "locateStatsFile", at = @At("RETURN"))
    private Path parallel$characterStatsFile(Path original, GameProfile gameProfile) {
        return CharacterSwap.statsFile(this.server, new NameAndId(gameProfile)).orElse(original);
    }

    @WrapOperation(
            method = "getPlayerAdvancements",
            at = @At(value = "NEW", target = "net/minecraft/server/PlayerAdvancements")
    )
    private PlayerAdvancements parallel$characterAdvancements(
            DataFixer dataFixer, PlayerList playerList, ServerAdvancementManager manager, Path playerSavePath, ServerPlayer player,
            Operation<PlayerAdvancements> original
    ) {
        Path target = CharacterSwap.advancementsFile(this.server, player.nameAndId()).orElse(playerSavePath);
        return original.call(dataFixer, playerList, manager, target, player);
    }

    @Inject(method = "save", at = @At("TAIL"))
    private void parallel$saveCharacter(ServerPlayer player, CallbackInfo ci) {
        CharacterSwap.onSave(this.server, player);
    }
}
