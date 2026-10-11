package com.tunic.parallel.mixin.client;

import com.mojang.serialization.Dynamic;
import com.tunic.parallel.character.CharacterMode;
import com.tunic.parallel.character.CharacterRules;
import com.tunic.parallel.client.screen.CharactersScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldOpenFlows.class)
public abstract class WorldOpenFlowsMixin {

    @Shadow
    private void openWorldCheckVersionCompatibility(LevelStorageSource.LevelStorageAccess worldAccess, LevelSummary summary,
                                                    Dynamic<?> levelDataTag, Runnable onCancel) {
    }

    /** Set once the player has picked another character, so opening the world is not intercepted again. */
    @Unique
    private boolean parallel$characterHandled;

    /**
     * Holds a world back, before anything is loaded, when the active character may not enter it, and sends
     * the player to character select to pick one that can. This is where the world's settings are first known.
     */
    @Inject(method = "openWorldCheckVersionCompatibility", at = @At("HEAD"), cancellable = true)
    private void parallel$checkCharacter(LevelStorageSource.LevelStorageAccess worldAccess, LevelSummary summary,
                                                Dynamic<?> levelDataTag, Runnable onCancel, CallbackInfo ci) {
        if (this.parallel$characterHandled) {
            this.parallel$characterHandled = false;
            return;
        }
        CharacterMode world = CharacterMode.ofWorld(summary.isHardcore(), summary.getGameMode(), summary.hasCommands());
        CharacterRules.check(world).ifPresent(refusal -> {
            ci.cancel();
            // Back to character select with the incompatible characters greyed out. The world stays open
            // meanwhile; it is released only if the player backs out.
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.gui.setScreen(new CharactersScreen(minecraft.gui.screen(), new CharactersScreen.WorldRequirement(
                    refusal.world(),
                    () -> {
                        this.parallel$characterHandled = true;
                        this.openWorldCheckVersionCompatibility(worldAccess, summary, levelDataTag, onCancel);
                    },
                    () -> {
                        worldAccess.safeClose();
                        onCancel.run();
                    })));
        });
    }
}
