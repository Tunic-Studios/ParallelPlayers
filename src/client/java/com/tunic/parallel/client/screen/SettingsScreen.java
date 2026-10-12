package com.tunic.parallel.client.screen;

import com.tunic.parallel.character.BackupTiming;
import com.tunic.parallel.client.config.ClientConfig;
import com.tunic.parallel.config.ConfigFile;
import com.tunic.parallel.config.ServerConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;

/**
 * The mod's settings, opened from Mod Menu: vanilla's scrolling two-column options list, like the game's
 * own option screens. Changes are saved when the screen is left.
 *
 * <p>The world rules are the {@link ServerConfig} of this computer: they apply to its singleplayer worlds
 * and to LAN games it hosts. A dedicated server is configured through its own file instead.
 */
public class SettingsScreen extends OptionsSubScreen {

    /** Width of a button in one of the list's two columns. */
    private static final int BUTTON_WIDTH = 150;

    // Edited copies, read fresh from the files so that leaving the screen writes exactly what is shown.
    private final ClientConfig client = ConfigFile.load("client", ClientConfig.class, ClientConfig::new);
    private final ServerConfig server = ConfigFile.load("server", ServerConfig.class, ServerConfig::new);

    public SettingsScreen(Screen parent) {
        super(parent, Minecraft.getInstance().options, Component.translatable("parallel.settings.title"));
    }

    @Override
    protected void addOptions() {
        if (this.list == null) {
            return;
        }
        this.list.addHeader(Component.translatable("parallel.settings.section.display"));
        this.list.addSmall(this.toggle("parallel.settings.doll_follows_cursor", null, this.client.dollFollowsCursor,
                value -> this.client.dollFollowsCursor = value), null);
        this.list.addSmall(
                this.toggle("parallel.settings.doll_shows_armor", null, this.client.dollShowsArmor,
                        value -> this.client.dollShowsArmor = value),
                this.toggle("parallel.settings.doll_shows_items", null, this.client.dollShowsItems,
                        value -> this.client.dollShowsItems = value));

        this.list.addHeader(Component.translatable("parallel.settings.section.characters"));
        this.list.addSmall(new BackupLimitSlider(),
                CycleButton.builder(timing -> Component.translatable(timing.translationKey()),
                                this.client.backupTiming == null ? BackupTiming.ENTER_WORLD : this.client.backupTiming)
                        .withValues(BackupTiming.values())
                        .withTooltip(timing -> Tooltip.create(Component.translatable(timing.translationKey() + ".tooltip")))
                        .create(0, 0, BUTTON_WIDTH, 20, Component.translatable("parallel.settings.backup_timing"),
                                (_, timing) -> this.client.backupTiming = timing));

        this.list.addHeader(Component.translatable("parallel.settings.section.worlds"));
        Component note = Component.translatable("parallel.settings.section.worlds.note");
        this.list.addSmall(
                this.toggle("parallel.settings.characters_enabled", note, this.server.charactersEnabled,
                        value -> this.server.charactersEnabled = value),
                this.toggle("parallel.settings.enforce_gamemodes", note, this.server.enforceGamemodes,
                        value -> this.server.enforceGamemodes = value));
    }

    @Override
    public void removed() {
        super.removed();
        this.client.save();
        this.server.save();
    }

    /**
     * An on/off button; its tooltip is the translation of {@code key + ".tooltip"}, followed by {@code note}
     * in grey if there is one.
     */
    private CycleButton<Boolean> toggle(String key, @Nullable Component note, boolean value, Consumer<Boolean> onChange) {
        Component text = Component.translatable(key + ".tooltip");
        if (note != null) {
            text = text.copy().append("\n\n").append(note.copy().withStyle(ChatFormatting.GRAY));
        }
        Tooltip tooltip = Tooltip.create(text);
        return CycleButton.onOffBuilder(value)
                .withTooltip(ignored -> tooltip)
                .create(0, 0, BUTTON_WIDTH, 20, Component.translatable(key), (_, newValue) -> onChange.accept(newValue));
    }

    /** Backups kept per character, from none to {@link ClientConfig#MAX_BACKUP_LIMIT}. */
    private class BackupLimitSlider extends AbstractSliderButton {

        BackupLimitSlider() {
            super(0, 0, BUTTON_WIDTH, 20, Component.empty(),
                    Math.clamp((double) SettingsScreen.this.client.backupLimit / ClientConfig.MAX_BACKUP_LIMIT, 0.0, 1.0));
            this.setTooltip(Tooltip.create(Component.translatable("parallel.settings.backup_limit.tooltip")));
            this.updateMessage();
        }

        private int limit() {
            return (int) Math.round(this.value * ClientConfig.MAX_BACKUP_LIMIT);
        }

        @Override
        protected void updateMessage() {
            int limit = this.limit();
            Component value = limit == 0 ? CommonComponents.OPTION_OFF : Component.literal(Integer.toString(limit));
            this.setMessage(CommonComponents.optionNameValue(Component.translatable("parallel.settings.backup_limit"), value));
        }

        @Override
        protected void applyValue() {
            SettingsScreen.this.client.backupLimit = this.limit();
        }
    }
}
