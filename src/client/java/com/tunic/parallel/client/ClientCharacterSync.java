package com.tunic.parallel.client;

import com.tunic.parallel.ParallelPlayers;
import com.tunic.parallel.character.CharacterBundle;
import com.tunic.parallel.character.CharacterGamemode;
import com.tunic.parallel.client.config.ClientConfig;
import com.tunic.parallel.client.screen.CharactersScreen;
import com.tunic.parallel.mixin.client.ClientCommonPacketListenerImplAccessor;
import com.tunic.parallel.network.CharacterPayloads;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The client's side of playing characters on servers (see {@link CharacterPayloads}): sends the active
 * character when a server asks for it, and keeps the local copy up to date with what the server saves.
 */
public final class ClientCharacterSync {

    private static final Logger LOGGER = LoggerFactory.getLogger(ParallelPlayers.MOD_ID);

    /** The character played on the server this client is connected to, or {@code null} for the account. */
    private static volatile @Nullable UUID playingAs;
    /** The world id of the server this client is connected to, once it has asked for a character. */
    private static volatile @Nullable String serverWorld;
    /** Whether the connected server lets this player see the usernames of players on characters. */
    private static volatile boolean showUsernames;
    /** The names of the characters other players on the connected server play as, by player. */
    private static final Map<UUID, String> ALIASES = new ConcurrentHashMap<>();
    /** The disconnect held back until the server's last save arrives; only touched on the client thread. */
    private static @Nullable Runnable pendingLeave;
    private static long leaveDeadline;
    /** Set while the held-back disconnect runs, so it is not held back again. */
    private static boolean leaving;
    /**
     * The connection to a server that is waiting while the player picks a character to join it with. The
     * "Connecting" screen normally keeps it going; while the character screen is up instead, this does.
     */
    private static @Nullable Connection choosingFor;

    private ClientCharacterSync() {
    }

    @SuppressWarnings({"resource", "unused"})
    public static void register() {
        // A new connection starts as the account; a server with the mod says otherwise while connecting.
        ClientConfigurationConnectionEvents.INIT.register((_, client) -> {
            playingAs = null;
            showUsernames = false;
            serverWorld = null;
            ALIASES.clear();
            // Called on a network thread; textures are released on the client thread.
            client.execute(CharacterSkins::clearOtherPlayers);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((_, client) -> {
            playingAs = null;
            showUsernames = false;
            serverWorld = null;
            pendingLeave = null;
            ALIASES.clear();
            // Textures are released on the client thread.
            client.execute(CharacterSkins::clearOtherPlayers);
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            Connection waiting = choosingFor;
            if (waiting != null) {
                // As the "Connecting" screen does: keep the connection going, and notice when it drops.
                if (waiting.isConnected()) {
                    waiting.tick();
                } else {
                    choosingFor = null;
                    waiting.handleDisconnection();
                }
            }
            if (pendingLeave != null && System.nanoTime() - leaveDeadline > 0) {
                LOGGER.warn("The server did not send the character's last save in time; leaving without it");
                finishLeaving();
            }
        });

        ClientConfigurationNetworking.registerGlobalReceiver(CharacterPayloads.Request.TYPE, (payload, context) ->
                // Received on a network thread; the storage is read and screens are shown on the client thread.
                context.client().execute(() -> answerRequest(payload, context)));
        ClientConfigurationNetworking.registerGlobalReceiver(CharacterPayloads.Result.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    UUID character = payload.playingAs();
                    playingAs = character;
                    if (character == null) {
                        return;
                    }
                    // Joining a server is entering a world, for the backup timing.
                    backUp(character, true);
                    CharacterBundle newer = payload.newer();
                    if (newer != null) {
                        if (!character.equals(newer.meta().id())) {
                            LOGGER.warn("Ignoring the server's copy of another character than the one played ({})", newer.meta().id());
                            return;
                        }
                        if (payload.conflict()) {
                            keepConflictCopy(character);
                        }
                        if (!saveLocally(newer)) {
                            return;
                        }
                    }
                    markSynced(character, payload.saveId());
                }));
        ClientPlayNetworking.registerGlobalReceiver(CharacterPayloads.Sync.TYPE, (payload, _) -> {
            UUID character = payload.character().meta().id();
            if (!character.equals(playingAs)) {
                LOGGER.warn("Ignoring a save of another character than the one played ({})", character);
                return;
            }
            backUp(character, false);
            // Only a save that was stored counts as shared with the server.
            if (saveLocally(payload.character())) {
                markSynced(character, payload.character().meta().saveId());
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(CharacterPayloads.LeaveReady.TYPE, (_, _) -> finishLeaving());
        ClientPlayNetworking.registerGlobalReceiver(CharacterPayloads.ShowUsernames.TYPE, (payload, _) -> showUsernames = payload.show());
        ClientPlayNetworking.registerGlobalReceiver(CharacterPayloads.PlayerCharacters.TYPE, (payload, _) -> {
            for (CharacterPayloads.PlayerCharacters.Entry entry : payload.players()) {
                if (entry.name() != null) {
                    ALIASES.put(entry.player(), entry.name());
                } else {
                    ALIASES.remove(entry.player());
                }
                CharacterSkins.setOtherPlayer(entry.player(), entry.skin(), entry.image());
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(CharacterPayloads.SkinImage.TYPE, (payload, _) ->
                CharacterSkins.receiveImage(payload.hash(), payload.image()));
    }

    /**
     * Called when the player chooses to leave a server. If they play a character there, asks the server to
     * save it and send it over first, and holds the disconnect back until it has arrived, or a few seconds
     * have passed.
     *
     * @param disconnect leaves the server; run once the last save is in
     * @return whether the disconnect is being held back, so the caller must not go ahead with it now
     */
    public static boolean holdDisconnect(Minecraft minecraft, Runnable disconnect) {
        if (leaving || pendingLeave != null || playingAs == null || minecraft.isLocalServer()
                || !ClientPlayNetworking.canSend(CharacterPayloads.Leave.TYPE)) {
            return pendingLeave != null && !leaving;
        }
        pendingLeave = disconnect;
        leaveDeadline = System.nanoTime() + ClientConfig.get().leaveWaitSeconds * 1_000_000_000L;
        ClientPlayNetworking.send(new CharacterPayloads.Leave());
        minecraft.gui.setScreen(new GenericMessageScreen(Component.translatable("parallel.leaving.saving")));
        return true;
    }

    /**
     * Called when the game is about to close, its window closed or Quit Game chosen. While playing a character
     * on a server, leaves it first the way the pause menu does, waiting for the last save.
     *
     * @return whether closing is being held back for now; the game asks again every frame until it may close
     */
    public static boolean holdQuit(Minecraft minecraft) {
        return holdDisconnect(minecraft, () -> {
            minecraft.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
            minecraft.stop();
        });
    }

    private static void finishLeaving() {
        Runnable disconnect = pendingLeave;
        if (disconnect == null) {
            return;
        }
        pendingLeave = null;
        leaving = true;
        try {
            disconnect.run();
        } finally {
            leaving = false;
        }
    }

    /** The name of the character another player on the connected server plays as, if they play one. */
    public static Optional<String> alias(UUID player) {
        return Optional.ofNullable(ALIASES.get(player));
    }

    /**
     * A player's name as shown: followed by their username in grey when they are on a character and the server
     * lets this player see usernames, otherwise as it is. Covers this player too, in the tab list.
     */
    public static Component withUsername(UUID player, String username, Component name) {
        if (!showUsernames) {
            return name;
        }
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        boolean self = connection != null && player.equals(connection.getLocalGameProfile().id());
        if (!(self ? playingAs != null : ALIASES.containsKey(player))) {
            return name;
        }
        return name.copy().append(Component.literal(" (" + username + ")").withStyle(ChatFormatting.GRAY));
    }

    /** The character played on the connected server, if it is not singleplayer and one is being played. */
    public static Optional<UUID> playingAs() {
        return Optional.ofNullable(playingAs);
    }

    /**
     * Sends the active character, if it can join the server. Otherwise the player first picks one that can,
     * on the character screen with the others greyed out, or backs out and the connection is closed.
     */
    @SuppressWarnings("resource")
    private static void answerRequest(CharacterPayloads.Request request, ClientConfigurationNetworking.Context context) {
        serverWorld = request.worldId();
        CharacterGamemode world = request.worldGamemode();
        if (world == null || activeCanJoin(world)) {
            context.responseSender().sendPacket(upload());
            return;
        }
        Minecraft minecraft = context.client();
        Connection connection = ((ClientCommonPacketListenerImplAccessor) context.packetListener()).parallel$connection();
        Screen connecting = minecraft.gui.screen();
        choosingFor = connection;
        minecraft.gui.setScreen(new CharactersScreen(connecting, new CharactersScreen.WorldRequirement(world,
                () -> {
                    choosingFor = null;
                    minecraft.gui.setScreen(connecting);
                    context.responseSender().sendPacket(upload());
                },
                () -> {
                    choosingFor = null;
                    minecraft.gui.setScreen(connecting);
                    connection.disconnect(Component.translatable("parallel.characters.join_cancelled"));
                })));
    }

    /** Whether the active character may join a world of this kind; the account always may. */
    private static boolean activeCanJoin(CharacterGamemode world) {
        return ParallelPlayers.activeCharacter().get()
                .flatMap(ParallelPlayers.characters()::get)
                .map(character -> character.gamemode().canJoin(world))
                .orElse(true);
    }

    /** The active character for the server, with the save it last synced there at. */
    private static CharacterPayloads.Upload upload() {
        CharacterBundle character = activeCharacter();
        String world = serverWorld;
        if (character == null || world == null) {
            return new CharacterPayloads.Upload(character, null);
        }
        OptionalLong synced = ParallelPlayers.characters().syncedSaveId(character.meta().id(), world);
        return new CharacterPayloads.Upload(character, synced.isPresent() ? synced.getAsLong() : null);
    }

    /** Records that this character and the connected server's copy of it are now the same, at this save. */
    private static void markSynced(UUID character, long saveId) {
        String world = serverWorld;
        if (world == null) {
            return;
        }
        try {
            ParallelPlayers.characters().setSyncedSaveId(character, world, saveId);
        } catch (RuntimeException e) {
            LOGGER.error("Could not record the sync state of character {}", character, e);
        }
    }

    private static void keepConflictCopy(UUID character) {
        try {
            ParallelPlayers.characters().keepConflictCopy(character);
        } catch (RuntimeException e) {
            LOGGER.error("Could not back up character {} before taking the server's copy", character, e);
        }
    }

    private static @Nullable CharacterBundle activeCharacter() {
        try {
            return ParallelPlayers.activeCharacter().get().flatMap(id -> ParallelPlayers.characters().export(id, true)).orElse(null);
        } catch (RuntimeException e) {
            LOGGER.error("Could not read the active character to send to the server; joining as the account", e);
            return null;
        }
    }

    /**
     * Backs the local copy up as the backup timing asks, before a copy from the server replaces it.
     *
     * @param joining whether the character is entering the server, as opposed to receiving one of its saves
     */
    private static void backUp(UUID character, boolean joining) {
        try {
            if (joining) {
                ParallelPlayers.characters().onEnterWorld(character);
            } else {
                ParallelPlayers.characters().beforeSave(character);
            }
        } catch (RuntimeException e) {
            LOGGER.error("Could not back up character {}", character, e);
        }
    }

    /** Stores a copy received from the server; returns whether that worked. */
    private static boolean saveLocally(CharacterBundle character) {
        try {
            ParallelPlayers.characters().importBundle(character, true, false);
            return true;
        } catch (RuntimeException e) {
            LOGGER.error("Could not store character {} received from the server", character.meta().id(), e);
            return false;
        }
    }
}
