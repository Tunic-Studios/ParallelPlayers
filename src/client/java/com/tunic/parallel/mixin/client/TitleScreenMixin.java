package com.tunic.parallel.mixin.client;

import com.tunic.parallel.client.screen.CharactersScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {

    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void parallel$addCharactersButton(CallbackInfo ci) {
        if (this.minecraft.isDemo()) {
            return;
        }
        // Vanilla's "Singleplayer" button is the top row: 200 wide at height / 4 + 48, rows are 24 apart.
        // The new button goes in the row above it, so the vanilla rows stay exactly where they were and
        // their bottom edge does not move down on high GUI scales.
        int singleplayerY = this.height / 4 + 48;
        int y = singleplayerY - 24;
        // The logo (drawn at y = 30) and its edition banner end at about y = 81.
        int logoBottom = 30 + 37 + 14;
        Button button;
        if (y > logoBottom) {
            button = Button.builder(Component.translatable("parallel.characters.title"), _ -> this.openCharacters())
                    .bounds(this.width / 2 - 100, y, 200, 20)
                    .build();
        } else {
            // Not enough room above on very high GUI scales: fall back to a compact button beside Singleplayer.
            button = Button.builder(Component.literal("C"), _ -> this.openCharacters())
                    .bounds(this.width / 2 - 100 - 24, singleplayerY, 20, 20)
                    .tooltip(Tooltip.create(Component.translatable("parallel.characters.title")))
                    .build();
        }
        this.addRenderableWidget(button);
    }

    @Unique
    private void openCharacters() {
        this.minecraft.gui.setScreen(new CharactersScreen(this));
    }
}
