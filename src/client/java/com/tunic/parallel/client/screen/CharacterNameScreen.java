package com.tunic.parallel.client.screen;

import com.tunic.parallel.character.CharacterGamemode;
import com.tunic.parallel.character.CharacterNames;
import com.tunic.parallel.client.MojangNameCheck;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * Asks for a character name, used both when creating and renaming a character. When creating, it also
 * asks for the character's gamemode, which cannot be changed afterwards.
 *
 * <p>The name has to follow {@link CharacterNames}' rules, and is checked with Mojang (see
 * {@link MojangNameCheck}) before it is accepted.
 */
public class CharacterNameScreen extends Screen {

    private static final int ERROR_COLOR = 0xFFFF5555;
    private static final int HINT_COLOR = 0xFFA0A0A0;

    private final Screen parent;
    private final String initialName;
    private final Callback onConfirm;
    private final Component confirmLabel;
    private final boolean askGamemode;
    private CharacterGamemode gamemode = CharacterGamemode.SURVIVAL;
    private final LinearLayout layout = LinearLayout.vertical().spacing(8);
    private EditBox nameBox;
    private StringWidget status;
    private Button confirmButton;
    /** Set while Mojang is being asked about the name. */
    private boolean checking;

    /** Called with the confirmed name and, if the screen asked for one, the chosen gamemode (else {@code null}). */
    @FunctionalInterface
    public interface Callback {
        void accept(String name, @Nullable CharacterGamemode gamemode);
    }

    /** A rename prompt: just a name. */
    public CharacterNameScreen(Screen parent, Component title, String initialName, Callback onConfirm) {
        this(parent, title, initialName, CommonComponents.GUI_DONE, false, onConfirm);
    }

    public CharacterNameScreen(Screen parent, Component title, String initialName, Component confirmLabel,
                               boolean askGamemode, Callback onConfirm) {
        super(title);
        this.confirmLabel = confirmLabel;
        this.askGamemode = askGamemode;
        this.parent = parent;
        this.initialName = initialName;
        this.onConfirm = onConfirm;
    }

    @Override
    protected void init() {
        this.layout.defaultCellSetting().alignHorizontallyCenter();
        this.layout.addChild(new StringWidget(this.title, this.font));

        this.nameBox = this.layout.addChild(new EditBox(this.font, 200, 20, Component.translatable("parallel.characters.name")));
        this.nameBox.setMaxLength(CharacterNames.MAX_LENGTH);
        this.nameBox.setValue(this.initialName);
        this.nameBox.setResponder(value -> {
            // Anything a name may not have is dropped as it is typed or pasted.
            String allowed = CharacterNames.withoutDisallowedCharacters(value);
            if (!allowed.equals(value)) {
                this.nameBox.setValue(allowed);
                return;
            }
            this.updateStatus();
        });
        // As wide as its text, at most as wide as the box, so the layout centres it under the box.
        this.status = this.layout.addChild(new StringWidget(200, 9, Component.empty(), this.font).setMaxWidth(200));

        if (this.askGamemode) {
            this.layout.addChild(CycleButton.builder(value -> Component.translatable(value.translationKey()), this.gamemode)
                    .withValues(CharacterGamemode.values())
                    .withTooltip(value -> Tooltip.create(Component.translatable(value.descriptionKey())
                            .append("\n\n").append(Component.translatable("parallel.gamemode.locked"))))
                    .create(0, 0, 200, 20, Component.translatable("parallel.gamemode.label"), (button, value) -> this.gamemode = value));
        }

        LinearLayout buttons = this.layout.addChild(LinearLayout.horizontal().spacing(4));
        buttons.defaultCellSetting().paddingTop(8);
        buttons.addChild(Button.builder(CommonComponents.GUI_CANCEL, button -> this.onClose()).width(98).build());
        this.confirmButton = buttons.addChild(Button.builder(this.confirmLabel, button -> this.confirm()).width(98).build());

        this.layout.visitWidgets(this::addRenderableWidget);
        this.updateStatus();
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

    /** Says what is wrong with the name, if anything, and only lets it be confirmed when nothing is. */
    private void updateStatus() {
        if (this.checking) {
            return;
        }
        String name = this.nameBox.getValue();
        Optional<CharacterNames.Problem> problem = name.isEmpty() ? Optional.empty() : CharacterNames.problem(name);
        this.showStatus(problem.map(p -> Component.translatable(p.translationKey(), CharacterNames.MIN_LENGTH, CharacterNames.MAX_LENGTH))
                .orElse(Component.empty()), ERROR_COLOR);
        this.confirmButton.active = !name.isEmpty() && problem.isEmpty();
    }

    private void showStatus(Component message, int color) {
        this.status.setMessage(message.copy().withColor(color));
        this.repositionElements();
    }

    /** Asks Mojang about the name first; an unchanged name was accepted before and is not asked about again. */
    private void confirm() {
        String name = this.nameBox.getValue();
        if (CharacterNames.problem(name).isPresent()) {
            return;
        }
        if (name.equals(this.initialName)) {
            this.accept(name);
            return;
        }
        this.checking = true;
        this.confirmButton.active = false;
        this.showStatus(Component.translatable("parallel.characters.name.checking"), HINT_COLOR);
        MojangNameCheck.check(name).thenAcceptAsync(result -> {
            this.checking = false;
            if (this.minecraft.gui.screen() != this || !name.equals(this.nameBox.getValue())) {
                // Closed, or the name was changed while Mojang was asked: that one has to be confirmed anew.
                this.updateStatus();
                return;
            }
            if (result == MojangNameCheck.Result.NOT_ALLOWED) {
                this.showStatus(Component.translatable("parallel.characters.name.not_allowed"), ERROR_COLOR);
                return;
            }
            this.accept(name);
        }, this.minecraft);
    }

    private void accept(String name) {
        // Return first: the callback may open another screen, which must not be replaced afterwards.
        this.minecraft.gui.setScreen(this.parent);
        this.onConfirm.accept(name, this.askGamemode ? this.gamemode : null);
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }
}
