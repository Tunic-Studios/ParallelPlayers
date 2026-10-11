package com.tunic.parallel_players.client.screen;

import com.tunic.parallel_players.character.CharacterMode;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Asks for a character name, used both when creating and renaming a character. When creating, it also
 * asks for the character's mode, which cannot be changed afterwards.
 */
public class CharacterNameScreen extends Screen {

    private static final int MAX_LENGTH = 32;

    private final Screen parent;
    private final String initialName;
    private final Callback onConfirm;
    private final Component confirmLabel;
    private final boolean askMode;
    private CharacterMode mode = CharacterMode.SURVIVAL;
    private final LinearLayout layout = LinearLayout.vertical().spacing(8);
    private EditBox nameBox;
    private Button confirmButton;

    /** Called with the confirmed name and, if the screen asked for one, the chosen mode (else {@code null}). */
    @FunctionalInterface
    public interface Callback {
        void accept(String name, @Nullable CharacterMode mode);
    }

    /** A rename prompt: just a name. */
    public CharacterNameScreen(Screen parent, Component title, String initialName, Callback onConfirm) {
        this(parent, title, initialName, CommonComponents.GUI_DONE, false, onConfirm);
    }

    public CharacterNameScreen(Screen parent, Component title, String initialName, Component confirmLabel,
                               boolean askMode, Callback onConfirm) {
        super(title);
        this.confirmLabel = confirmLabel;
        this.askMode = askMode;
        this.parent = parent;
        this.initialName = initialName;
        this.onConfirm = onConfirm;
    }

    @Override
    protected void init() {
        this.layout.defaultCellSetting().alignHorizontallyCenter();
        this.layout.addChild(new StringWidget(this.title, this.font));

        this.nameBox = this.layout.addChild(new EditBox(this.font, 200, 20, Component.translatable("parallelplayers.characters.name")));
        this.nameBox.setMaxLength(MAX_LENGTH);
        this.nameBox.setValue(this.initialName);
        this.nameBox.setResponder(value -> this.confirmButton.active = !value.isBlank());

        if (this.askMode) {
            this.layout.addChild(CycleButton.builder(value -> Component.translatable(value.translationKey()), this.mode)
                    .withValues(CharacterMode.values())
                    .withTooltip(value -> Tooltip.create(Component.translatable(value.descriptionKey())
                            .append("\n\n").append(Component.translatable("parallelplayers.mode.locked"))))
                    .create(0, 0, 200, 20, Component.translatable("parallelplayers.mode.label"), (button, value) -> this.mode = value));
        }

        LinearLayout buttons = this.layout.addChild(LinearLayout.horizontal().spacing(4));
        buttons.defaultCellSetting().paddingTop(8);
        buttons.addChild(Button.builder(CommonComponents.GUI_CANCEL, button -> this.onClose()).width(98).build());
        this.confirmButton = buttons.addChild(Button.builder(this.confirmLabel, button -> this.confirm()).width(98).build());
        this.confirmButton.active = !this.nameBox.getValue().isBlank();

        this.layout.visitWidgets(this::addRenderableWidget);
        this.repositionElements();
    }

    @Override
    protected void repositionElements() {
        this.layout.arrangeElements();
        this.layout.setPosition((this.width - this.layout.getWidth()) / 2, this.height / 3);
    }

    @Override
    public boolean keyPressed(@NonNull KeyEvent event) {
        // Enter (also on the numpad) confirms, like pressing the button: ignored while the name is blank.
        if (event.isConfirmation()) {
            if (this.confirmButton.active) {
                this.confirm();
            }
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    protected void setInitialFocus() {
        this.setInitialFocus(this.nameBox);
    }

    private void confirm() {
        String name = this.nameBox.getValue().strip();
        if (!name.isEmpty()) {
            // Return first: the callback may open another screen, which must not be replaced afterwards.
            this.minecraft.gui.setScreen(this.parent);
            this.onConfirm.accept(name, this.askMode ? this.mode : null);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }
}
