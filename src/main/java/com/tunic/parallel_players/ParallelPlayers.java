package com.tunic.parallel_players;

import com.tunic.parallel_players.character.ActiveCharacter;
import com.tunic.parallel_players.character.CharacterStore;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

public class ParallelPlayers implements ModInitializer {

    public static final String MOD_ID = "parallelplayers";

    private static CharacterStore characterStore;
    private static ActiveCharacter activeCharacter;

    /** The global character store, shared by every world. */
    public static CharacterStore characters() {
        if (characterStore == null) {
            characterStore = new CharacterStore(baseDir().resolve("characters"));
        }
        return characterStore;
    }

    /** The character the local player is playing as. */
    public static ActiveCharacter activeCharacter() {
        if (activeCharacter == null) {
            activeCharacter = new ActiveCharacter(baseDir().resolve("active_character.txt"), characters());
        }
        return activeCharacter;
    }

    private static Path baseDir() {
        return FabricLoader.getInstance().getGameDir().resolve(MOD_ID);
    }

    @Override
    public void onInitialize() {
    }
}
