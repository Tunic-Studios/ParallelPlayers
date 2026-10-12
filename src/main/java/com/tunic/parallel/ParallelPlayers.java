package com.tunic.parallel;

import com.tunic.parallel.character.ActiveCharacter;
import com.tunic.parallel.character.CharacterRules;
import com.tunic.parallel.character.CharacterStorage;
import com.tunic.parallel.character.CharacterSwap;
import com.tunic.parallel.config.ServerConfig;
import com.tunic.parallel.network.CharacterPayloads;
import com.tunic.parallel.network.CharacterSync;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

public class ParallelPlayers implements ModInitializer {

    public static final String MOD_ID = "parallel";

    /** Folder in the game directory holding every character and the active-character file. */
    private static final String FOLDER = "characters";
    private static final String ACTIVE_FILE = "active_character.txt";

    private static CharacterStorage characterStorage;
    private static ActiveCharacter activeCharacter;

    /** The global character storage, shared by every world. */
    public static CharacterStorage characters() {
        if (characterStorage == null) {
            characterStorage = new CharacterStorage(characterDir());
        }
        return characterStorage;
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
        ServerConfig.reload();
        // Read again for every world or server start, so edits to the file apply without restarting the game.
        ServerLifecycleEvents.SERVER_STARTING.register(_ -> ServerConfig.reload());
        ServerLifecycleEvents.SERVER_STOPPED.register(_ -> CharacterSwap.onServerStopped());
        CharacterPayloads.register();
        CharacterSync.register();
        ServerPlayConnectionEvents.JOIN.register((handler, _, server) ->
                CharacterRules.onJoin(server, handler.getPlayer()).ifPresent(refusal -> handler.disconnect(refusal.message())));
    }
}
