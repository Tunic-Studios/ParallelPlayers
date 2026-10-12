package com.tunic.parallel.client.screen;

import com.tunic.parallel.ParallelPlayers;
import com.tunic.parallel.character.CharacterGamemode;
import com.tunic.parallel.character.CharacterMeta;
import com.tunic.parallel.client.CharacterSkins;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.function.Supplier;

/**
 * Shown when a new world is about to be created without a character that can play it. Offers to create
 * one of the world's gamemode, to play without a character, or to go back.
 */
public class NewWorldCharacterScreen extends Screen {

    private final Screen parent;
    private final CharacterGamemode world;
    private final Runnable onContinue;
    private final LinearLayout layout = LinearLayout.vertical().spacing(8);

    /**
     * @param parent     the create world screen, returned to on cancel
     * @param world      the kind of world being created
     * @param onContinue creates the world; called once the character question is settled
     */
    public NewWorldCharacterScreen(Screen parent, CharacterGamemode world, Runnable onContinue) {
        super(Component.translatable("parallel.newworld.title"));
        this.parent = parent;
        this.world = world;
        this.onContinue = onContinue;
    }

    @Override
    protected void init() {
        this.layout.defaultCellSetting().alignHorizontallyCenter();
        this.layout.addChild(new StringWidget(this.title, this.font));

        Component worldName = Component.translatable(this.world.translationKey());
        // A selected character that does not fit is named; with none selected it is a plain note.
        Component message = ParallelPlayers.activeCharacter().get()
                .flatMap(id -> ParallelPlayers.characters().get(id))
                .map(character -> Component.translatable("parallel.newworld.mismatch",
                        character.name(), Component.translatable(character.gamemode().translationKey()), worldName))
                .orElseGet(() -> Component.translatable("parallel.newworld.message", worldName));
        this.layout.addChild(new MultiLineTextWidget(message, this.font).setMaxWidth(this.width - 50).setMaxRows(10).setCentered(true));

        LinearLayout buttons = this.layout.addChild(LinearLayout.vertical().spacing(4));
        buttons.defaultCellSetting().paddingTop(8).alignHorizontallyCenter();
        buttons.addChild(Button.builder(Component.translatable("parallel.newworld.create", worldName), _ -> this.createCharacter()).width(200).build());
        buttons.addChild(Button.builder(Component.translatable("parallel.newworld.without"), _ -> this.playWithoutCharacter()).width(200).build());
        buttons.addChild(Button.builder(CommonComponents.GUI_CANCEL, _ -> this.onClose()).width(200).build());

        this.layout.visitWidgets(this::addRenderableWidget);
        this.repositionElements();
    }

    @Override
    protected void repositionElements() {
        this.layout.arrangeElements();
        this.layout.setPosition((this.width - this.layout.getWidth()) / 2, this.height / 4);
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }

    /** Name, then skin; the gamemode is fixed to what the world needs. Returns here if either is cancelled. */
    private void createCharacter() {
        Component title = Component.translatable("parallel.characters.name.new");
        this.minecraft.gui.setScreen(new CharacterNameScreen(this, title, "", CommonComponents.GUI_CONTINUE, false, (name, _) -> {
            Supplier<PlayerSkin> account = this.minecraft.getSkinManager().createLookup(this.minecraft.getGameProfile(), false);
            this.minecraft.gui.setScreen(new CharacterSkinScreen(this, name, null, account, result -> {
                CharacterMeta created = ParallelPlayers.characters().create(name, this.world, result.skin(), result.image());
                CharacterSkins.invalidate(created.id());
                ParallelPlayers.activeCharacter().set(created.id());
                this.onContinue.run();
            }));
        }));
    }

    /** Plays with the world's own player data, as the game does without the mod. */
    private void playWithoutCharacter() {
        ParallelPlayers.activeCharacter().clear();
        this.onContinue.run();
    }
}
