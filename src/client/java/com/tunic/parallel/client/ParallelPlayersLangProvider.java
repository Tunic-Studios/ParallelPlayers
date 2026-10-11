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

        // Character modes
        builder.add("parallel.mode.label", "Mode");
        builder.add("parallel.mode.survival", "Survival");
        builder.add("parallel.mode.creative", "Creative");
        builder.add("parallel.mode.hardcore", "Hardcore");
        builder.add("parallel.mode.survival.description", "Can only join survival worlds with cheats off.");
        builder.add("parallel.mode.creative.description", "Can only join worlds that are Creative or have cheats enabled.");
        builder.add("parallel.mode.hardcore.description", "Can only join hardcore worlds.");
        builder.add("parallel.mode.locked", "The mode cannot be changed after the character is created.");
        builder.add("parallel.mode.blocked.title", "Wrong character for this world");
        builder.add("parallel.mode.blocked.message", "%s is a %s character, but this is a %s world.");
        builder.add("parallel.mode.blocked.suggest", "Characters that can join: %s");
        builder.add("parallel.mode.blocked.none", "You have no %s characters yet. Create one in the Characters menu, or switch to your account.");

        // Character select when a world needs a certain kind of character
        builder.add("parallel.characters.choose_for_world", "Choose a %s character to enter this world");
        builder.add("parallel.characters.cannot_join", "Cannot join %s worlds");

        // New world without a usable character
        builder.add("parallel.newworld.title", "Create a character for this world?");
        builder.add("parallel.newworld.message", "You are not playing as a character. This will be a %s world.");
        builder.add("parallel.newworld.mismatch", "%s is a %s character, but this will be a %s world.");
        builder.add("parallel.newworld.create", "Create a %s character");
        builder.add("parallel.newworld.without", "Play without a character");

        // In-game nickname hover
        builder.add("parallel.nickname.username", "%s");
    }
}
