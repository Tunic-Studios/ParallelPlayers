package com.tunic.parallel_players.client.screen;

import com.tunic.parallel_players.ParallelPlayers;
import com.tunic.parallel_players.character.CharacterLoadout;
import com.tunic.parallel_players.character.CharacterMeta;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.text.DateFormat;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/** Main-menu screen for creating, selecting, renaming and deleting characters. */
public class CharactersScreen extends Screen {

    private static final int ACTIVE_COLOR = 0xFF55FF55;
    private static final int SUBTEXT_COLOR = 0xFF808080;
    private static final int DOLL_MIN_WIDTH = 40;
    private static final int DOLL_MIN_HEIGHT = 40;
    private static final int DOLL_MAX_WIDTH = 160;
    private static final int DOLL_PADDING = 8;
    /**
     * Where the character list is centred, as a fraction of the screen width: 0.5 is the middle of the
     * screen, 1.0 the right edge. The paper doll is centred in the space to the left of the list.
     */
    private static final float LIST_CENTER = 0.7F;
    /** Least space kept between the list and the right edge of the screen, for the scrollbar. */
    private static final int LIST_RIGHT_MARGIN = 24;
    private static final int DOLL_LABEL_HEIGHT = 14;

    private final Screen parent;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 8 + 9 + 8 + 9 + 4, 60);
    private CharacterList list;
    private StringWidget statusLine;
    private CharacterDollWidget doll;
    private @Nullable UUID dollCharacter;
    private Button selectButton;
    private Button renameButton;
    private Button deleteButton;
    private @Nullable UUID selectedId;

    public CharactersScreen(Screen parent) {
        super(Component.translatable("parallelplayers.characters.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        LinearLayout header = this.layout.addToHeader(LinearLayout.vertical().spacing(4));
        header.defaultCellSetting().alignHorizontallyCenter();
        header.addChild(new StringWidget(this.title, this.font));
        this.statusLine = header.addChild(new StringWidget(Component.empty(), this.font));

        // Not part of the header/footer layout's contents, which would centre it; it is placed by hand next
        // to the paper doll in repositionElements().
        this.list = this.addRenderableWidget(new CharacterList(this.minecraft, this.width, this.layout.getContentHeight(), this.layout.getHeaderHeight()));
        this.doll = this.addRenderableWidget(new CharacterDollWidget(
                DOLL_MIN_WIDTH, DOLL_MIN_HEIGHT, this.minecraft,
                this.minecraft.getSkinManager().createLookup(this.minecraft.getGameProfile(), false)));

        GridLayout footer = this.layout.addToFooter(new GridLayout().columnSpacing(8).rowSpacing(4));
        footer.defaultCellSetting().alignHorizontallyCenter();
        GridLayout.RowHelper row = footer.createRowHelper(3);
        this.selectButton = row.addChild(Button.builder(Component.translatable("parallelplayers.characters.select"), button -> this.toggleActive()).width(98).build());
        row.addChild(Button.builder(Component.translatable("parallelplayers.characters.new"), button -> this.createCharacter()).width(98).build());
        row.addChild(Button.builder(CommonComponents.GUI_BACK, button -> this.onClose()).width(98).build());
        this.renameButton = row.addChild(Button.builder(Component.translatable("parallelplayers.characters.rename"), button -> this.renameCharacter()).width(98).build());
        this.deleteButton = row.addChild(Button.builder(Component.translatable("parallelplayers.characters.delete"), button -> this.deleteCharacter()).width(98).build());

        this.layout.visitWidgets(this::addRenderableWidget);
        this.repositionElements();
        if (this.selectedId == null) {
            // Open with the active character highlighted, so its doll is shown straight away.
            this.selectedId = ParallelPlayers.activeCharacter().get().orElse(null);
        }
        this.refresh();
    }

    @Override
    protected void repositionElements() {
        this.layout.arrangeElements();
        if (this.list == null || this.doll == null) {
            return;
        }
        int top = this.layout.getHeaderHeight();
        int height = this.layout.getContentHeight();

        // The rows are centred at LIST_CENTER of the screen width (0.5 is the middle, 1.0 the right edge),
        // but never closer to the right edge than the scrollbar needs, nor so far left that the doll
        // would not fit. The doll is centred in the space between the screen's left edge and the rows.
        int rowWidth = this.list.getRowWidth();
        int rowsLeft = Math.round(this.width * LIST_CENTER - rowWidth / 2.0F);
        rowsLeft = Math.min(rowsLeft, this.width - rowWidth - LIST_RIGHT_MARGIN);
        rowsLeft = Math.max(rowsLeft, DOLL_MIN_WIDTH + 2 * DOLL_PADDING);
        int dollHeight = Math.max(DOLL_MIN_HEIGHT, height - 2 * DOLL_PADDING - DOLL_LABEL_HEIGHT);
        // The model is drawn in proportion to the box height, so on tall layouts the box has to be able to
        // grow wider than the usual cap or the model gets clipped at the sides.
        int maxDollWidth = Math.max(DOLL_MAX_WIDTH, dollHeight * 3 / 4);
        int dollWidth = Math.clamp(rowsLeft - 2 * DOLL_PADDING, DOLL_MIN_WIDTH, maxDollWidth);

        // The list spans the whole width so its dark background also sits behind the doll.
        this.list.rowsLeft = rowsLeft;
        this.list.updateSizeAndPosition(this.width, height, 0, top);
        this.doll.setSize(dollWidth, dollHeight);
        this.doll.setPosition(Math.max(0, (rowsLeft - dollWidth) / 2), top + DOLL_PADDING);
    }

    @Override
    public void extractRenderState(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        CharacterEntry selected = this.list.getSelected();
        if (selected != null) {
            int centerX = this.doll.getX() + this.doll.getWidth() / 2;
            graphics.centeredText(this.font, selected.meta.name(), centerX, this.doll.getBottom() + 4, 0xFFFFFFFF);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }

    /** Rebuilds the list from disk, keeping the previous selection where possible. */
    private void refresh() {
        Optional<UUID> active = ParallelPlayers.activeCharacter().get();
        this.list.replaceEntries(ParallelPlayers.characters().list().stream()
                .map(meta -> new CharacterEntry(meta, active.filter(meta.id()::equals).isPresent()))
                .toList());
        if (this.selectedId != null) {
            this.list.children().stream()
                    .filter(entry -> entry.meta.id().equals(this.selectedId))
                    .findFirst()
                    .ifPresent(this.list::setSelected);
        }
        this.updateButtons();
    }

    private void updateStatusLine() {
        Optional<CharacterMeta> active = ParallelPlayers.activeCharacter().get().flatMap(id -> ParallelPlayers.characters().get(id));
        this.statusLine.setMessage(active
                .map(meta -> Component.translatable("parallelplayers.characters.playing_as", meta.name()))
                .orElseGet(() -> Component.translatable("parallelplayers.characters.vanilla_hint"))
                .withStyle(ChatFormatting.GRAY));
        this.layout.arrangeElements();
    }

    private void updateButtons() {
        this.updateStatusLine();
        CharacterEntry selected = this.list.getSelected();
        this.selectedId = selected == null ? null : selected.meta.id();
        this.doll.visible = selected != null;
        if (selected != null && !selected.meta.id().equals(this.dollCharacter)) {
            this.doll.setLoadout(CharacterLoadout.read(ParallelPlayers.characters(), selected.meta.id()));
        }
        this.dollCharacter = selected == null ? null : selected.meta.id();
        this.selectButton.active = selected != null;
        this.renameButton.active = selected != null;
        this.deleteButton.active = selected != null;
        this.selectButton.setMessage(Component.translatable(
                selected != null && selected.active ? "parallelplayers.characters.deselect" : "parallelplayers.characters.select"));
    }

    private void toggleActive() {
        CharacterEntry selected = this.list.getSelected();
        if (selected == null) {
            return;
        }
        if (selected.active) {
            ParallelPlayers.activeCharacter().clear();
        } else {
            ParallelPlayers.activeCharacter().set(selected.meta.id());
        }
        this.refresh();
    }

    /** Makes the entry's character the active one; unlike {@link #toggleActive} it never deselects. */
    private void activate(CharacterEntry entry) {
        this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        this.selectedId = entry.meta.id();
        if (!entry.active) {
            ParallelPlayers.activeCharacter().set(entry.meta.id());
            this.refresh();
        }
    }

    private void createCharacter() {
        this.minecraft.gui.setScreen(new CharacterNameScreen(this, Component.translatable("parallelplayers.characters.name.new"), "", name -> {
            CharacterMeta created = ParallelPlayers.characters().create(name);
            this.selectedId = created.id();
            // Make the first character active right away so it is used without an extra step.
            if (ParallelPlayers.activeCharacter().get().isEmpty()) {
                ParallelPlayers.activeCharacter().set(created.id());
            }
            this.refresh();
        }));
    }

    private void renameCharacter() {
        CharacterEntry selected = this.list.getSelected();
        if (selected == null) {
            return;
        }
        UUID id = selected.meta.id();
        this.minecraft.gui.setScreen(new CharacterNameScreen(this, Component.translatable("parallelplayers.characters.name.rename"), selected.meta.name(), name -> {
            ParallelPlayers.characters().rename(id, name);
            this.refresh();
        }));
    }

    private void deleteCharacter() {
        CharacterEntry selected = this.list.getSelected();
        if (selected == null) {
            return;
        }
        UUID id = selected.meta.id();
        this.minecraft.gui.setScreen(new ConfirmScreen(
                confirmed -> {
                    if (confirmed) {
                        if (ParallelPlayers.activeCharacter().get().filter(id::equals).isPresent()) {
                            ParallelPlayers.activeCharacter().clear();
                        }
                        ParallelPlayers.characters().delete(id);
                        this.selectedId = null;
                    }
                    this.minecraft.gui.setScreen(this);
                    this.refresh();
                },
                Component.translatable("parallelplayers.characters.delete.title"),
                Component.translatable("parallelplayers.characters.delete.message", selected.meta.name()),
                Component.translatable("parallelplayers.characters.delete"),
                CommonComponents.GUI_CANCEL));
    }

    private class CharacterList extends ObjectSelectionList<CharacterEntry> {

        /** Left edge of the rows; everything to the left of it is the paper doll's. */
        int rowsLeft;

        CharacterList(Minecraft minecraft, int width, int height, int y) {
            super(minecraft, width, height, y, 36);
        }

        @Override
        public int getRowLeft() {
            return this.rowsLeft;
        }

        @Override
        public boolean isMouseOver(double mouseX, double mouseY) {
            // The doll panel is part of the list's background only; clicks and drags there belong to the doll.
            return mouseX >= this.rowsLeft - DOLL_PADDING && super.isMouseOver(mouseX, mouseY);
        }

        @Override
        public void setSelected(@Nullable CharacterEntry selected) {
            super.setSelected(selected);
            CharactersScreen.this.updateButtons();
        }

        @Override
        public void extractWidgetRenderState(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
            super.extractWidgetRenderState(graphics, mouseX, mouseY, a);
            if (this.children().isEmpty()) {
                graphics.centeredText(CharactersScreen.this.font, Component.translatable("parallelplayers.characters.empty"),
                        this.rowsLeft + this.getRowWidth() / 2, this.getY() + this.getHeight() / 2 - 4, SUBTEXT_COLOR);
            }
        }
    }

    private class CharacterEntry extends ObjectSelectionList.Entry<CharacterEntry> {

        final CharacterMeta meta;
        final boolean active;

        CharacterEntry(CharacterMeta meta, boolean active) {
            this.meta = meta;
            this.active = active;
        }

        @Override
        public @NonNull Component getNarration() {
            return Component.literal(this.meta.name());
        }

        @Override
        public boolean mouseClicked(@NonNull MouseButtonEvent event, boolean doubleClick) {
            if (doubleClick) {
                CharactersScreen.this.activate(this);
                return true;
            }
            return super.mouseClicked(event, false);
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (event.isSelection()) {
                CharactersScreen.this.activate(this);
                return true;
            }
            return super.keyPressed(event);
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
            int x = this.getContentX() + 4;
            int y = this.getContentY() + 2;
            graphics.text(CharactersScreen.this.font, this.meta.name(), x, y, 0xFFFFFFFF);
            if (this.active) {
                Component label = Component.translatable("parallelplayers.characters.active");
                graphics.text(CharactersScreen.this.font, label, x + CharactersScreen.this.font.width(this.meta.name()) + 6, y, ACTIVE_COLOR);
            }
            String played = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(this.meta.lastPlayedAt()));
            graphics.text(CharactersScreen.this.font, Component.translatable("parallelplayers.characters.last_played", played), x, y + 12, SUBTEXT_COLOR);
        }
    }
}
