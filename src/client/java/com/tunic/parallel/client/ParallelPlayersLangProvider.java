package com.tunic.parallel.client;

import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricLanguageProvider;
import net.minecraft.core.HolderLookup;
import org.jspecify.annotations.NonNull;

import java.util.concurrent.CompletableFuture;

/** Generates {@code assets/parallel/lang/en_us.json}. The English text is edited here, not in the json. */
public class ParallelPlayersLangProvider extends FabricLanguageProvider {

    public ParallelPlayersLangProvider(FabricPackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
        super(output, registries);
    }

    @Override
    public void generateTranslations(HolderLookup.@NonNull Provider registries, TranslationBuilder builder) {
        // Characters screen
        builder.add("parallel.characters.title", "Characters");
        builder.add("parallel.characters.button", "Manage your characters");
        builder.add("parallel.characters.select", "Select");
        builder.add("parallel.characters.new", "New Character");
        builder.add("parallel.characters.rename", "Rename");
        builder.add("parallel.characters.delete", "Delete");
        builder.add("parallel.characters.skin", "Skin");
        builder.add("parallel.characters.active", "Active");
        builder.add("parallel.characters.last_played", "Last played: %s");
        builder.add("parallel.characters.playtime", "%s");
        builder.add("parallel.characters.playtime.short", "Less than a minute");
        builder.add("parallel.characters.empty", "No characters yet. Create one to get started.");
        builder.add("parallel.characters.playing_as", "Playing as %s");
        builder.add("parallel.characters.account", "Account");

        // Delete confirmation
        builder.add("parallel.characters.delete.title", "Delete this character?");
        builder.add("parallel.characters.delete.message", "\"%s\" and all of its data will be permanently lost.");

        // Name prompt (create and rename)
        builder.add("parallel.characters.name", "Character name");
        builder.add("parallel.characters.name.new", "Create a new Character");
        builder.add("parallel.characters.name.rename", "Rename Character");
        builder.add("parallel.characters.name.problem.invalid_characters", "Only letters, numbers, _ and spaces");
        builder.add("parallel.characters.name.problem.spacing", "No spaces at the ends or two in a row");
        builder.add("parallel.characters.name.problem.too_short", "At least %s characters");
        builder.add("parallel.characters.name.problem.too_long", "At most %2$s characters");
        builder.add("parallel.characters.name.checking", "Checking the name...");
        builder.add("parallel.characters.name.not_allowed", "This name is not allowed");

        // Skin screen
        builder.add("parallel.skin.title", "Skin for %s");
        builder.add("parallel.skin.import", "Import Skin");
        builder.add("parallel.skin.account", "Account Skin");
        builder.add("parallel.skin.model", "Model: %s");
        builder.add("parallel.skin.model.wide", "Wide");
        builder.add("parallel.skin.model.slim", "Slim");
        builder.add("parallel.skin.imported", "Imported %s");
        builder.add("parallel.skin.using_account", "Using your account's skin. Pick a default skin or import a 64x64 PNG.");
        builder.add("parallel.skin.error.too_large", "That file is too large to be a skin");
        builder.add("parallel.skin.error.read", "Could not read that file");

        // Character gamemodes
        builder.add("parallel.gamemode.label", "Gamemode");
        builder.add("parallel.gamemode.survival", "Survival");
        builder.add("parallel.gamemode.creative", "Creative");
        builder.add("parallel.gamemode.hardcore", "Hardcore");
        builder.add("parallel.gamemode.survival.description", "Can only join survival worlds with cheats off.");
        builder.add("parallel.gamemode.creative.description", "Can only join worlds that are Creative or have cheats enabled.");
        builder.add("parallel.gamemode.hardcore.description", "Can only join hardcore worlds.");
        builder.add("parallel.gamemode.locked", "The gamemode cannot be changed after the character is created.");
        builder.add("parallel.gamemode.blocked.title", "Wrong character for this world");
        builder.add("parallel.gamemode.blocked.message", "%s is a %s character, but this is a %s world.");
        builder.add("parallel.gamemode.blocked.suggest", "Characters that can join: %s");
        builder.add("parallel.gamemode.blocked.server", "Choose a %s character in the Characters menu, or switch to your account, then join again.");
        builder.add("parallel.gamemode.blocked.none", "You have no %s characters yet. Create one in the Characters menu, or switch to your account.");

        // Character select when a world needs a certain kind of character
        builder.add("parallel.characters.choose_for_world", "Choose a %s character to enter this world");
        builder.add("parallel.characters.cannot_join", "Cannot join %s worlds");
        builder.add("parallel.characters.join_cancelled", "No valid character was selected to join with");

        // New world without a usable character
        builder.add("parallel.newworld.title", "Create a character for this world?");
        builder.add("parallel.newworld.message", "You are not playing as a character. This will be a %s world.");
        builder.add("parallel.newworld.mismatch", "%s is a %s character, but this will be a %s world.");
        builder.add("parallel.newworld.create", "Create a %s character");
        builder.add("parallel.newworld.without", "Play without a character");

        // Settings screen
        builder.add("parallel.settings.title", "Parallel Players Settings");
        builder.add("parallel.settings.section.display", "Display");
        builder.add("parallel.settings.doll_follows_cursor", "Doll Follows Cursor");
        builder.add("parallel.settings.doll_follows_cursor.tooltip", "The paper doll on the character screens turns to look at the mouse.");
        builder.add("parallel.settings.doll_shows_armor", "Doll Armor");
        builder.add("parallel.settings.doll_shows_armor.tooltip", "The paper doll wears the character's armor and anything else on its head.");
        builder.add("parallel.settings.doll_shows_items", "Doll Items");
        builder.add("parallel.settings.doll_shows_items.tooltip", "The paper doll holds the items in the character's hands.");
        builder.add("parallel.settings.section.characters", "Characters");
        builder.add("parallel.settings.backup_limit", "Backups");
        builder.add("parallel.settings.backup_limit.tooltip", "How many earlier copies of each character's data to keep, one made each time it enters a world. Lowering this deletes the oldest copies the next time a character is played.");
        builder.add("parallel.settings.backup_timing", "Back Up");
        builder.add("parallel.settings.backup_timing.enter_world", "Entering a World");
        builder.add("parallel.settings.backup_timing.enter_world.tooltip", "Each time a character enters a world.");
        builder.add("parallel.settings.backup_timing.world_save", "Every Save");
        builder.add("parallel.settings.backup_timing.world_save.tooltip", "Before every save, autosaves included. Gives the most restore points, but fills up the backup limit fastest.");
        builder.add("parallel.settings.backup_timing.game_launch", "Once per Launch");
        builder.add("parallel.settings.backup_timing.game_launch.tooltip", "The first time each character enters a world after starting the game.");
        builder.add("parallel.settings.section.worlds", "Your Worlds");
        builder.add("parallel.settings.section.worlds.note", "Applies to your singleplayer worlds and LAN games you host. Servers have their own config/parallel/server.json.");
        builder.add("parallel.settings.characters_enabled", "Characters");
        builder.add("parallel.settings.characters_enabled.tooltip", "When off, every world uses its own player data, as it would without the mod.");
        builder.add("parallel.settings.enforce_gamemodes", "Gamemode Rules");
        builder.add("parallel.settings.enforce_gamemodes.tooltip", "Characters can only enter worlds of their own gamemode: survival, creative or hardcore.");

        // Leaving a server while playing a character
        builder.add("parallel.leaving.saving", "Saving your character...");

        // Join message for a player on a character (the server also carries this text as a fallback)
        builder.add("parallel.join.as", "%s has joined the game as %s");

        // In-game nickname hover
        builder.add("parallel.nickname.username", "%s");
    }
}
