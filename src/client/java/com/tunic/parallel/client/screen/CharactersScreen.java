package com.tunic.parallel.client.screen;

import com.tunic.parallel.ParallelPlayers;
import com.tunic.parallel.character.CharacterLoadout;
import com.tunic.parallel.character.CharacterMeta;
import com.tunic.parallel.character.CharacterMode;
import com.tunic.parallel.client.CharacterSkins;
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
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.PlayerSkin;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Main-menu screen for creating, selecting, renaming and deleting characters. */
public class CharactersScreen extends Screen {

    private static final int ACTIVE_COLOR = 0xFF55FF55;
    private static final int SUBTEXT_COLOR = 0xFF808080;
    private static final int DISABLED_COLOR = 0xFF555555;
    private static final int PLAYTIME_COLOR = 0xFF555555;
    private static final int DISABLED_ICON_TINT = 0xFF606060;
    private static final int MODE_ICON_SIZE = 8;
    private static final int MODE_ICON_GAP = 3;

    private final Screen parent;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 8 + 9 + 8 + 9 + 4, 60);
    private CharacterList list;
    private StringWidget statusLine;
    private CharacterDollWidget doll;
    private Supplier<PlayerSkin> accountSkin;
    private @Nullable UUID dollCharacter;
    private Button selectButton;
    private Button renameButton;
    private Button deleteButton;
    private Button skinButton;
    /** The highlighted row: a character's id, or {@link #ACCOUNT_ID} for the account row. */
    private @Nullable UUID selectedId;

    /** Stands for the account row, which plays without a character; no character has this id. */
    private static final UUID ACCOUNT_ID = new UUID(0L, 0L);

    /**
     * A world that is about to be opened and needs a character that can enter it. While this is set, the
     * characters that cannot are greyed out, choosing one that can continues into the world, and backing out
     * releases it.
     *
     * @param world    the kind of world being entered
     * @param onChosen continues opening the world; called after a compatible character became active
     * @param onCancel releases the world; called when the player backs out
     */
    public record WorldRequirement(CharacterMode world, Runnable onChosen, Runnable onCancel) {
    }

    private final @Nullable WorldRequirement requirement;

    public CharactersScreen(Screen parent) {
        this(parent, null);
    }

    public CharactersScreen(Screen parent, @Nullable WorldRequirement requirement) {
        super(Component.translatable("parallel.characters.title"));
        this.parent = parent;
        this.requirement = requirement;
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
        this.accountSkin = this.minecraft.getSkinManager().createLookup(this.minecraft.getGameProfile(), false);
        this.doll = this.addRenderableWidget(new CharacterDollWidget(
                DollPanelLayout.DOLL_MIN_WIDTH, DollPanelLayout.DOLL_MIN_HEIGHT, this.minecraft, this.accountSkin));

        GridLayout footer = this.layout.addToFooter(new GridLayout().columnSpacing(8).rowSpacing(4));
        footer.defaultCellSetting().alignHorizontallyCenter();
        GridLayout.RowHelper row = footer.createRowHelper(3);
        this.renameButton = row.addChild(Button.builder(Component.translatable("parallel.characters.rename"), button -> this.renameCharacter()).width(98).build());
        row.addChild(Button.builder(Component.translatable("parallel.characters.new"), button -> this.createCharacter()).width(98).build());
        this.skinButton = row.addChild(Button.builder(Component.translatable("parallel.characters.skin"), button -> this.editSkin()).width(98).build());
        this.selectButton = row.addChild(Button.builder(Component.translatable("parallel.characters.select"), button -> this.selectHighlighted()).width(98).build());
        this.deleteButton = row.addChild(Button.builder(Component.translatable("parallel.characters.delete"), button -> this.deleteCharacter()).width(98).build());
        row.addChild(Button.builder(CommonComponents.GUI_BACK, button -> this.onClose()).width(98).build());

        this.layout.visitWidgets(this::addRenderableWidget);
        this.repositionElements();
        if (this.selectedId == null) {
            // Open with the active row highlighted, so its doll is shown straight away.
            this.selectedId = ParallelPlayers.activeCharacter().get().orElse(ACCOUNT_ID);
        }
        this.refresh();
    }

    @Override
    protected void repositionElements() {
        this.layout.arrangeElements();
        if (this.list == null || this.doll == null) {
            return;
        }
        DollPanelLayout.place(this.width, this.layout, this.list, this.doll);
    }

    @Override
    public void extractRenderState(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        CharacterEntry selected = this.list.getSelected();
        if (selected != null) {
            int centerX = this.doll.getX() + this.doll.getWidth() / 2;
            graphics.centeredText(this.font, selected.name(), centerX, this.doll.getBottom() + 4, 0xFFFFFFFF);
        }
    }

    @Override
    public void onClose() {
        if (this.requirement != null) {
            this.requirement.onCancel().run();
        } else {
            this.minecraft.gui.setScreen(this.parent);
        }
    }

    /** Rebuilds the list from disk, keeping the previous selection where possible. */
    private void refresh() {
        Optional<UUID> active = ParallelPlayers.activeCharacter().get();
        List<CharacterEntry> entries = new ArrayList<>();
        // The account always comes first, and is active whenever no character is.
        entries.add(new CharacterEntry(null, active.isEmpty()));
        ParallelPlayers.characters().list().forEach(meta ->
                entries.add(new CharacterEntry(meta, active.filter(meta.id()::equals).isPresent())));
        this.list.replaceEntries(entries);
        if (this.selectedId != null) {
            this.list.children().stream()
                    .filter(entry -> entry.id().equals(this.selectedId))
                    .findFirst()
                    .ifPresent(this.list::setSelected);
        }
        this.updateButtons();
    }

    private void updateStatusLine() {
        if (this.requirement != null) {
            this.statusLine.setMessage(Component.translatable("parallel.characters.choose_for_world",
                    Component.translatable(this.requirement.world().translationKey())).withStyle(ChatFormatting.YELLOW));
            this.layout.arrangeElements();
            return;
        }
        Optional<CharacterMeta> active = ParallelPlayers.activeCharacter().get().flatMap(id -> ParallelPlayers.characters().get(id));
        String name = active.map(CharacterMeta::name).orElseGet(this::accountName);
        this.statusLine.setMessage(Component.translatable("parallel.characters.playing_as", name).withStyle(ChatFormatting.GRAY));
        this.layout.arrangeElements();
    }

    private void updateButtons() {
        this.updateStatusLine();
        CharacterEntry selected = this.list.getSelected();
        UUID id = selected == null ? null : selected.id();
        this.selectedId = id;
        this.doll.visible = id != null;
        if (selected != null && !id.equals(this.dollCharacter)) {
            // The account's inventory lives in each world, so there is nothing of it to dress the doll in.
            this.doll.setLoadout(selected.meta == null ? CharacterLoadout.EMPTY : CharacterLoadout.read(ParallelPlayers.characters(), id));
        }
        this.dollCharacter = id;
        boolean character = selected != null && selected.meta != null;
        // Selecting is how you switch rows, so it is pointless on the row already in use, except when
        // picking for a world, where it also carries on into the world.
        this.selectButton.active = selected != null && (this.requirement != null || !selected.active);
        this.renameButton.active = character;
        this.deleteButton.active = character;
        this.skinButton.active = character;
        this.doll.setSkin(!character ? this.accountSkin : () -> CharacterSkins.resolve(selected.meta, this.accountSkin.get()));
    }

    private void selectHighlighted() {
        CharacterEntry selected = this.list.getSelected();
        if (selected != null) {
            this.activate(selected);
        }
    }

    /** Makes the entry the active one: its character, or none at all for the account row. */
    private void activate(CharacterEntry entry) {
        if (!entry.enabled) {
            return;
        }
        this.selectedId = entry.id();
        if (this.requirement != null) {
            this.choose(entry.id());
        } else if (!entry.active) {
            makeActive(entry.id());
            this.refresh();
        }
    }

    /** Makes the character active, or plays without one for {@link #ACCOUNT_ID}. */
    private static void makeActive(UUID id) {
        if (ACCOUNT_ID.equals(id)) {
            ParallelPlayers.activeCharacter().clear();
        } else {
            ParallelPlayers.activeCharacter().set(id);
        }
    }

    /** Makes the row active and carries on into the world this screen was opened for. */
    private void choose(UUID id) {
        makeActive(id);
        if (this.requirement != null) {
            this.requirement.onChosen().run();
        }
    }

    private void createCharacter() {
        Component title = Component.translatable("parallel.characters.name.new");
        // For a world that needs a certain kind of character the mode is not up to the player.
        boolean askMode = this.requirement == null;
        this.minecraft.gui.setScreen(new CharacterNameScreen(this, title, "", CommonComponents.GUI_CONTINUE, askMode, (name, chosenMode) ->
                // Second step: choose the skin; the character only exists once that is confirmed.
                this.minecraft.gui.setScreen(new CharacterSkinScreen(this, name, null, this.accountSkin, result -> {
                    CharacterMode mode = this.requirement != null ? this.requirement.world() : chosenMode;
                    CharacterMeta created = ParallelPlayers.characters().create(name, mode, result.skin(), result.image());
                    CharacterSkins.invalidate(created.id());
                    if (this.requirement != null) {
                        this.choose(created.id());
                        return;
                    }
                    this.selectedId = created.id();
                    // Make the first character active right away so it is used without an extra step.
                    if (ParallelPlayers.characters().list().size() == 1) {
                        ParallelPlayers.activeCharacter().set(created.id());
                    }
                    this.refresh();
                }))));
    }

    private void editSkin() {
        CharacterEntry selected = this.list.getSelected();
        if (selected == null || selected.meta == null) {
            return;
        }
        CharacterMeta meta = selected.meta;
        this.minecraft.gui.setScreen(new CharacterSkinScreen(this, meta.name(), meta, this.accountSkin, result -> {
            ParallelPlayers.characters().setSkin(meta.id(), result.skin(), result.image());
            CharacterSkins.invalidate(meta.id());
            this.refresh();
        }));
    }

    private void renameCharacter() {
        CharacterEntry selected = this.list.getSelected();
        if (selected == null || selected.meta == null) {
            return;
        }
        UUID id = selected.meta.id();
        this.minecraft.gui.setScreen(new CharacterNameScreen(this, Component.translatable("parallel.characters.name.rename"), selected.meta.name(), (name, mode) -> {
            ParallelPlayers.characters().rename(id, name);
            this.refresh();
        }));
    }

    private void deleteCharacter() {
        CharacterEntry selected = this.list.getSelected();
        if (selected == null || selected.meta == null) {
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
                Component.translatable("parallel.characters.delete.title"),
                Component.translatable("parallel.characters.delete.message", selected.meta.name()),
                Component.translatable("parallel.characters.delete"),
                CommonComponents.GUI_CANCEL));
    }

    private String accountName() {
        return this.minecraft.getUser().getName();
    }

    /** "2h 15m", "40m", or "less than a minute" for a character that has barely been played. */
    private static String formatPlayTime(long ticks) {
        long minutes = ticks / (20L * 60L);
        long hours = minutes / 60L;
        if (hours > 0) {
            return hours + "h " + (minutes % 60L) + "m";
        }
        if (minutes > 0) {
            return minutes + "m";
        }
        return Component.translatable("parallel.characters.playtime.short").getString();
    }

    private class CharacterList extends DollSideList<CharacterEntry> {

        CharacterList(Minecraft minecraft, int width, int height, int y) {
            super(minecraft, width, height, y, 40);
        }

        @Override
        int leftGutter() {
            return MODE_ICON_SIZE + MODE_ICON_GAP;
        }

        @Override
        public void setSelected(@Nullable CharacterEntry selected) {
            if (selected != null && !selected.enabled) {
                return;
            }
            super.setSelected(selected);
            CharactersScreen.this.updateButtons();
        }

        @Override
        public void extractWidgetRenderState(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
            super.extractWidgetRenderState(graphics, mouseX, mouseY, a);
            // The account row is always there; the hint is for when it is the only one.
            if (this.children().size() <= 1) {
                graphics.centeredText(CharactersScreen.this.font, Component.translatable("parallel.characters.empty"),
                        this.rowsLeft + this.getRowWidth() / 2, this.getY() + this.getHeight() / 2 - 4, SUBTEXT_COLOR);
            }
        }
    }

    private class CharacterEntry extends ObjectSelectionList.Entry<CharacterEntry> {

        /** The character, or {@code null} for the account row. */
        final @Nullable CharacterMeta meta;
        final boolean active;
        /** False for a character that cannot enter the world being chosen for; it is greyed out. */
        final boolean enabled;
        final long playTimeTicks;

        CharacterEntry(@Nullable CharacterMeta meta, boolean active) {
            this.meta = meta;
            this.active = active;
            this.playTimeTicks = meta == null ? 0L : ParallelPlayers.characters().playTimeTicks(meta.id());
            WorldRequirement requirement = CharactersScreen.this.requirement;
            // The account has no mode: without a character every world uses its own player data.
            CharacterMode mode = meta == null ? null : meta.modeChoice();
            this.enabled = requirement == null || mode == null || mode.canJoin(requirement.world());
        }

        @Override
        public @NonNull Component getNarration() {
            return Component.literal(this.name());
        }

        private void playClick() {
            CharactersScreen.this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }

        UUID id() {
            return this.meta == null ? ACCOUNT_ID : this.meta.id();
        }

        String name() {
            return this.meta == null ? CharactersScreen.this.accountName() : this.meta.name();
        }

        @Override
        public boolean mouseClicked(@NonNull MouseButtonEvent event, boolean doubleClick) {
            if (doubleClick) {
                this.playClick();
                CharactersScreen.this.activate(this);
                return true;
            }
            return super.mouseClicked(event, false);
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (event.isSelection()) {
                this.playClick();
                CharactersScreen.this.activate(this);
                return true;
            }
            return super.keyPressed(event);
        }

        /**
         * The character's mode as an icon just left of the row, named in a tooltip on hover; nothing for a
         * character that has none yet.
         */
        private void extractModeIcon(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
            CharacterMode mode = this.meta == null ? null : this.meta.modeChoice();
            if (mode == null) {
                return;
            }
            int x = this.getX() - MODE_ICON_GAP - MODE_ICON_SIZE;
            int y = this.getY() + (this.getHeight() - MODE_ICON_SIZE) / 2;
            Identifier sprite = Identifier.fromNamespaceAndPath(ParallelPlayers.MOD_ID, "mode/" + mode.id());
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, MODE_ICON_SIZE, MODE_ICON_SIZE,
                    this.enabled ? -1 : DISABLED_ICON_TINT);
            if (mouseX >= x && mouseX < x + MODE_ICON_SIZE && mouseY >= y && mouseY < y + MODE_ICON_SIZE) {
                graphics.setTooltipForNextFrame(Component.translatable(mode.translationKey()), mouseX, mouseY);
            }
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
            int x = this.getContentX() + 4;
            int y = this.getContentY() + 2;
            graphics.text(CharactersScreen.this.font, this.name(), x, y, this.enabled ? 0xFFFFFFFF : DISABLED_COLOR);
            if (this.active) {
                Component label = Component.translatable("parallel.characters.active");
                graphics.text(CharactersScreen.this.font, label, x + CharactersScreen.this.font.width(this.name()) + 6, y, ACTIVE_COLOR);
            }
            if (this.meta == null) {
                graphics.text(CharactersScreen.this.font, Component.translatable("parallel.characters.account"), x, y + 12, SUBTEXT_COLOR);
                graphics.text(CharactersScreen.this.font, Component.translatable("parallel.characters.account.hint"), x, y + 22, PLAYTIME_COLOR);
                return;
            }
            this.extractModeIcon(graphics, mouseX, mouseY);
            int detailColor = this.enabled ? SUBTEXT_COLOR : DISABLED_COLOR;
            if (!this.enabled && CharactersScreen.this.requirement != null && this.meta.modeChoice() != null) {
                // Say why it is greyed out, in the line that normally holds the last-played time.
                Component reason = Component.translatable("parallel.characters.cannot_join",
                        Component.translatable(CharactersScreen.this.requirement.world().translationKey()));
                graphics.text(CharactersScreen.this.font, reason, x, y + 12, DISABLED_COLOR);
            } else {
                String played = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(this.meta.lastPlayedAt()));
                graphics.text(CharactersScreen.this.font, Component.translatable("parallel.characters.last_played", played), x, y + 12, detailColor);
            }
            graphics.text(CharactersScreen.this.font,
                    Component.translatable("parallel.characters.playtime", formatPlayTime(this.playTimeTicks)), x, y + 22, PLAYTIME_COLOR);
        }
    }
}
