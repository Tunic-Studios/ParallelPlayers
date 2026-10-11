package com.tunic.parallel_players.client;

import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricLanguageProvider;
import net.minecraft.core.HolderLookup;
import org.jspecify.annotations.NonNull;

import java.util.concurrent.CompletableFuture;

/** Generates {@code assets/parallelplayers/lang/en_us.json}. The English text is edited here, not in the json. */
public class ParallelPlayersLangProvider extends FabricLanguageProvider {

    public ParallelPlayersLangProvider(FabricPackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
        super(output, registries);
    }

    @Override
    public void generateTranslations(HolderLookup.@NonNull Provider registries, TranslationBuilder builder) {
        // Characters screen
        builder.add("parallelplayers.characters.title", "Characters");
        builder.add("parallelplayers.characters.button", "Manage your characters");
        builder.add("parallelplayers.characters.select", "Select");
        builder.add("parallelplayers.characters.deselect", "Deselect");
        builder.add("parallelplayers.characters.new", "New Character");
        builder.add("parallelplayers.characters.rename", "Rename");
        builder.add("parallelplayers.characters.delete", "Delete");
        builder.add("parallelplayers.characters.skin", "Skin");
        builder.add("parallelplayers.characters.active", "Active");
        builder.add("parallelplayers.characters.last_played", "Last played: %s");
        builder.add("parallelplayers.characters.empty", "No characters yet. Create one to get started.");
        builder.add("parallelplayers.characters.playing_as", "Playing as %s");
        builder.add("parallelplayers.characters.vanilla_hint", "No character selected: worlds use their own player data.");

        // Delete confirmation
        builder.add("parallelplayers.characters.delete.title", "Delete this character?");
        builder.add("parallelplayers.characters.delete.message", "\"%s\" and all of its data will be permanently lost.");

        // Name prompt (create and rename)
        builder.add("parallelplayers.characters.name", "Character name");
        builder.add("parallelplayers.characters.name.new", "Create a new Character");
        builder.add("parallelplayers.characters.name.rename", "Rename Character");

        // Skin screen
        builder.add("parallelplayers.skin.title", "Skin for %s");
        builder.add("parallelplayers.skin.import", "Import Skin");
        builder.add("parallelplayers.skin.account", "Account Skin");
        builder.add("parallelplayers.skin.model", "Model: %s");
        builder.add("parallelplayers.skin.model.wide", "Wide");
        builder.add("parallelplayers.skin.model.slim", "Slim");
        builder.add("parallelplayers.skin.imported", "Imported %s");
        builder.add("parallelplayers.skin.using_account", "Using your account's skin. Pick a default skin or import a 64x64 PNG.");
        builder.add("parallelplayers.skin.error.too_large", "That file is too large to be a skin");
        builder.add("parallelplayers.skin.error.read", "Could not read that file");

        // In-game nickname hover
        builder.add("parallelplayers.nickname.username", "%s");
    }
}
