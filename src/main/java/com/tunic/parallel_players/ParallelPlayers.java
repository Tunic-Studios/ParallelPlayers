package com.tunic.parallel_players;

import com.tunic.parallel_players.character.ActiveCharacter;
import com.tunic.parallel_players.character.CharacterRules;
import com.tunic.parallel_players.character.CharacterStore;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

public class ParallelPlayers implements ModInitializer {

    public static final String MOD_ID = "parallelplayers";

    /** Folder in the game directory holding every character and the active-character file. */
    private static final String FOLDER = "characters";
    private static final String ACTIVE_FILE = "active_character.txt";

    private static CharacterStore characterStore;
    private static ActiveCharacter activeCharacter;

    /** The global character store, shared by every world. */
    public static CharacterStore characters() {
        if (characterStore == null) {
            characterStore = new CharacterStore(characterDir());
        }
        return characterStore;
    }

    /** The character the local player is playing as. */
    public static ActiveCharacter activeCharacter() {
        if (activeCharacter == null) {
            activeCharacter = new ActiveCharacter(characterDir().resolve(ACTIVE_FILE), characters());
        }
        return activeCharacter;
    }

    private static Path characterDir() {
        return FabricLoader.getInstance().getGameDir().resolve(FOLDER);
    }

    @Override
    public void onInitialize() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                CharacterRules.onJoin(server, handler.getPlayer()).ifPresent(refusal -> handler.disconnect(refusal.message())));
    }
}
