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
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public class ParallelPlayers implements ModInitializer {

    public static final String MOD_ID = "parallel";

    /**
     * Folder in the game directory holding a folder per account, named by its UUID, with that account's
     * characters and active-character file. Accounts on the same game folder each have their own characters.
     */
    private static final String FOLDER = "characters";
    private static final String ACTIVE_FILE = "active_character.txt";

    /** The UUID of the account playing on this client; set by the client, which is the only side with storage. */
    private static Supplier<UUID> account = () -> {
        throw new IllegalStateException("Character storage is only available on the client");
    };
    /** The account {@link #characterStorage} and {@link #activeCharacter} belong to. */
    private static UUID storageAccount;
    private static CharacterStorage characterStorage;
    private static ActiveCharacter activeCharacter;

    public static void setAccount(Supplier<UUID> account) {
        ParallelPlayers.account = account;
    }

    /** The current account's character storage, shared by every world. */
    public static CharacterStorage characters() {
        ensureAccount();
        return characterStorage;
    }

    /** The character the current account is playing as. */
    public static ActiveCharacter activeCharacter() {
        ensureAccount();
        return activeCharacter;
    }

    /** Opens the current account's storage, again if the account changed since (account switcher mods). */
    private static synchronized void ensureAccount() {
        UUID current = Objects.requireNonNull(account.get());
        if (current.equals(storageAccount)) {
            return;
        }
        Path dir = FabricLoader.getInstance().getGameDir().resolve(FOLDER).resolve(current.toString());
        CharacterStorage previous = characterStorage;
        characterStorage = new CharacterStorage(dir);
        if (previous != null) {
            characterStorage.copyBackupSettings(previous);
        }
        activeCharacter = new ActiveCharacter(dir.resolve(ACTIVE_FILE), characterStorage);
        storageAccount = current;
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
