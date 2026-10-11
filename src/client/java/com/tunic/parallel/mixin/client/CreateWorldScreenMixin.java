package com.tunic.parallel.mixin.client;

import com.tunic.parallel.character.CharacterMode;
import com.tunic.parallel.character.CharacterRules;
import com.tunic.parallel.config.ServerConfig;
import com.tunic.parallel.client.screen.NewWorldCharacterScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenMixin {

    @Shadow
    @org.spongepowered.asm.mixin.Final
    private WorldCreationUiState uiState;

    @Shadow
    private void onCreate() {
    }

    /** Set once the player has dealt with the character prompt, so creating the world is not intercepted again. */
    @Unique
    private boolean parallel$characterHandled;

    /**
     * Before a world is created, makes sure there is a character that can play it, offering to create one
     * otherwise. The world's rules are known here from what was chosen on the create screen.
     */
    @Inject(method = "onCreate", at = @At("HEAD"), cancellable = true)
    private void parallel$requireCharacter(CallbackInfo ci) {
        if (this.parallel$characterHandled) {
            this.parallel$characterHandled = false;
            return;
        }
        if (!ServerConfig.get().charactersEnabled) {
            return;
        }
        CharacterMode world = CharacterMode.ofWorld(
                this.uiState.isHardcore(), this.uiState.getGameMode().gameType, this.uiState.isAllowCommands());
        if (CharacterRules.hasUsableCharacter(world)) {
            return;
        }
        ci.cancel();
        Screen self = (Screen) (Object) this;
        Minecraft.getInstance().gui.setScreen(new NewWorldCharacterScreen(self, world, () -> {
            this.parallel$characterHandled = true;
            this.onCreate();
        }));
    }
}
