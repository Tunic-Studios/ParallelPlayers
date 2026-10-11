package com.tunic.parallel_players.mixin.client;

import com.mojang.serialization.Dynamic;
import com.tunic.parallel_players.character.CharacterMode;
import com.tunic.parallel_players.character.CharacterRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldOpenFlows.class)
public abstract class WorldOpenFlowsMixin {

    /**
     * Stops a world from being opened with a character that may not enter it, before anything is loaded,
     * and says why. This is the point where the world's settings are first known.
     */
    @Inject(method = "openWorldCheckVersionCompatibility", at = @At("HEAD"), cancellable = true)
    private void parallelplayers$checkCharacter(LevelStorageSource.LevelStorageAccess worldAccess, LevelSummary summary,
                                                Dynamic<?> levelDataTag, Runnable onCancel, CallbackInfo ci) {
        CharacterMode world = CharacterMode.ofWorld(summary.isHardcore(), summary.getGameMode(), summary.hasCommands());
        CharacterRules.check(world).ifPresent(refusal -> {
            worldAccess.safeClose();
            Minecraft.getInstance().gui.setScreen(new AlertScreen(onCancel, refusal.title(), refusal.message()));
            ci.cancel();
        });
    }
}
