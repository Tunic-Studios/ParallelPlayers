package com.tunic.parallel.client.screen;

import com.tunic.parallel.ParallelPlayers;
import com.tunic.parallel.character.CharacterLoadout;
import com.tunic.parallel.character.CharacterMeta;
import com.tunic.parallel.character.CharacterSkin;
import com.tunic.parallel.client.CharacterSkins;
import com.tunic.parallel.client.SkinFileDialog;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.PlayerSkin;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Chooses a character's skin: one of vanilla's default skins, an imported PNG, or the account's own.
 * Nothing is saved until "Done"; the result is handed to the caller, which stores it.
 */
public class CharacterSkinScreen extends Screen {

    /** The chosen skin and, for a newly imported custom skin, its PNG bytes. */
    public record Result(CharacterSkin skin, byte @Nullable [] image) {
    }

    private static final long MAX_IMAGE_BYTES = 1L << 20;
    private static final int FOOTER_BUTTON_WIDTH = 98;
    private static final int FOOTER_SPACING = 8;

    private final Screen parent;
    private final @Nullable CharacterMeta existing;
    private final Supplier<PlayerSkin> accountSkin;
    private final Consumer<Result> onDone;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 8 + 9 + 8 + 9 + 4, 60);

    private CharacterSkin choice;
    private byte @Nullable [] imported;
    private Component status = Component.empty();
    private boolean syncing;

    private PresetList list;
    private CharacterDollWidget doll;
    private StringWidget statusLine;
    private Button modelButton;
    private Button accountButton;

    /**
     * @param existing the character being edited, or {@code null} while creating a new one
     */
    public CharacterSkinScreen(Screen parent, String characterName, @Nullable CharacterMeta existing,
                               Supplier<PlayerSkin> accountSkin, Consumer<Result> onDone) {
        super(Component.translatable("parallel.skin.title", characterName));
        this.parent = parent;
        this.existing = existing;
        this.accountSkin = accountSkin;
        this.onDone = onDone;
        this.choice = existing != null ? existing.skinChoice() : CharacterSkin.ACCOUNT;
    }

    @Override
    protected void init() {
        LinearLayout header = this.layout.addToHeader(LinearLayout.vertical().spacing(4));
        header.defaultCellSetting().alignHorizontallyCenter();
        header.addChild(new StringWidget(this.title, this.font));
        this.statusLine = header.addChild(new StringWidget(Component.empty(), this.font));

        this.list = this.addRenderableWidget(new PresetList(this.minecraft, this.width, this.layout.getContentHeight(), this.layout.getHeaderHeight()));
        this.doll = this.addRenderableWidget(new CharacterDollWidget(
                DollPanelLayout.DOLL_MIN_WIDTH, DollPanelLayout.DOLL_MIN_HEIGHT, this.minecraft, this::previewSkin));
        if (this.existing != null) {
            this.doll.setLoadout(CharacterLoadout.read(ParallelPlayers.characters(), this.existing.id()));
        }

        // Two rows of the same total width: three buttons on top, and "Cancel" / "Done" splitting that width.
        LinearLayout footer = this.layout.addToFooter(LinearLayout.vertical().spacing(4));
        footer.defaultCellSetting().alignHorizontallyCenter();
        LinearLayout top = footer.addChild(LinearLayout.horizontal().spacing(FOOTER_SPACING));
        top.addChild(Button.builder(Component.translatable("parallel.skin.import"), button -> this.chooseFile()).width(FOOTER_BUTTON_WIDTH).build());
        this.modelButton = top.addChild(Button.builder(Component.empty(), button -> this.toggleModel()).width(FOOTER_BUTTON_WIDTH).build());
        this.accountButton = top.addChild(Button.builder(Component.translatable("parallel.skin.account"), button -> this.useAccountSkin()).width(FOOTER_BUTTON_WIDTH).build());
        int topRowWidth = 3 * FOOTER_BUTTON_WIDTH + 2 * FOOTER_SPACING;
        int halfWidth = (topRowWidth - FOOTER_SPACING) / 2;
        LinearLayout bottom = footer.addChild(LinearLayout.horizontal().spacing(FOOTER_SPACING));
        bottom.addChild(Button.builder(CommonComponents.GUI_CANCEL, button -> this.onClose()).width(halfWidth).build());
        bottom.addChild(Button.builder(CommonComponents.GUI_DONE, button -> this.finish()).width(halfWidth).build());

        this.layout.visitWidgets(this::addRenderableWidget);
        this.repositionElements();
        this.syncList();
        this.update();
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
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }

    @Override
    public void onFilesDrop(@NonNull List<Path> files) {
        files.stream()
                .filter(file -> file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
                .findFirst()
                .ifPresent(this::importFile);
    }

    /** The skin being previewed on the doll, from what is chosen so far. */
    private PlayerSkin previewSkin() {
        PlayerSkin account = this.accountSkin.get();
        if (this.choice.isPreset()) {
            return CharacterSkins.preset(this.choice, account);
        }
        if (this.choice.isCustom()) {
            if (this.imported != null) {
                PlayerSkin draft = CharacterSkins.draft(this.choice.slim(), account);
                if (draft != null) {
                    return draft;
                }
            } else if (this.existing != null) {
                return CharacterSkins.resolve(this.existing.withSkin(this.choice), account);
            }
        }
        return account;
    }

    private void chooseFile() {
        SkinFileDialog.open(this::importFile);
    }

    private void importFile(Path file) {
        try {
            if (Files.size(file) > MAX_IMAGE_BYTES) {
                this.status = Component.translatable("parallel.skin.error.too_large").withStyle(ChatFormatting.RED);
                this.update();
                return;
            }
            byte[] bytes = Files.readAllBytes(file);
            var problem = CharacterSkins.loadDraft(bytes);
            if (problem.isPresent()) {
                this.status = Component.literal(problem.get()).withStyle(ChatFormatting.RED);
            } else {
                this.imported = bytes;
                this.choice = new CharacterSkin(CharacterSkin.CUSTOM, this.choice.slim());
                this.status = Component.translatable("parallel.skin.imported", file.getFileName().toString()).withStyle(ChatFormatting.GREEN);
            }
        } catch (IOException e) {
            this.status = Component.translatable("parallel.skin.error.read").withStyle(ChatFormatting.RED);
        }
        this.syncList();
        this.update();
    }

    private void toggleModel() {
        this.choice = this.choice.withSlim(!this.choice.slim());
        this.update();
    }

    private void useAccountSkin() {
        this.choice = CharacterSkin.ACCOUNT;
        this.imported = null;
        this.status = Component.empty();
        this.syncList();
        this.update();
    }

    private void finish() {
        Result result = new Result(this.choice, this.choice.isCustom() ? this.imported : null);
        // Return first: the callback may open another screen, which must not be replaced afterwards.
        this.minecraft.gui.setScreen(this.parent);
        this.onDone.accept(result);
    }

    /** Highlights the entry of the chosen preset, or none for an imported or account skin. */
    private void syncList() {
        this.syncing = true;
        try {
            PresetEntry match = this.list.children().stream()
                    .filter(entry -> entry.name.equals(this.choice.id()))
                    .findFirst()
                    .orElse(null);
            this.list.setSelected(match);
        } finally {
            this.syncing = false;
        }
    }

    private void update() {
        boolean account = this.choice.isAccount();
        this.modelButton.active = !account;
        this.accountButton.active = !account;
        this.modelButton.setMessage(Component.translatable("parallel.skin.model",
                Component.translatable(this.choice.slim() ? "parallel.skin.model.slim" : "parallel.skin.model.wide")));
        Component line = this.status.getString().isEmpty() && account
                ? Component.translatable("parallel.skin.using_account").withStyle(ChatFormatting.GRAY)
                : this.status;
        this.statusLine.setMessage(line);
        this.layout.arrangeElements();
    }

    private class PresetList extends DollSideList<PresetEntry> {

        PresetList(Minecraft minecraft, int width, int height, int y) {
            super(minecraft, width, height, y, 20);
            for (String name : CharacterSkin.PRESETS) {
                this.addEntry(new PresetEntry(name));
            }
        }

        @Override
        public void setSelected(@Nullable PresetEntry selected) {
            super.setSelected(selected);
            if (!CharacterSkinScreen.this.syncing && selected != null) {
                // Picking a default skin replaces anything imported; the model type is kept.
                CharacterSkinScreen.this.choice = new CharacterSkin(selected.name, CharacterSkinScreen.this.choice.slim());
                CharacterSkinScreen.this.imported = null;
                CharacterSkinScreen.this.status = Component.empty();
                CharacterSkinScreen.this.update();
            }
        }
    }

    private class PresetEntry extends ObjectSelectionList.Entry<PresetEntry> {

        final String name;

        PresetEntry(String name) {
            this.name = name;
        }

        private String label() {
            return this.name.substring(0, 1).toUpperCase(Locale.ROOT) + this.name.substring(1);
        }

        @Override
        public @NonNull Component getNarration() {
            return Component.literal(this.label());
        }

        @Override
        public void extractContent(net.minecraft.client.gui.@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
            graphics.text(CharacterSkinScreen.this.font, this.label(), this.getContentX() + 4, this.getContentY() + 5, 0xFFFFFFFF);
        }
    }
}
