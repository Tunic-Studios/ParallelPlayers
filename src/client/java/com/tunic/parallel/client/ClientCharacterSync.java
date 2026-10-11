package com.tunic.parallel.client;

import com.tunic.parallel.ParallelPlayers;
import com.tunic.parallel.character.CharacterBundle;
import com.tunic.parallel.network.CharacterPayloads;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.UUID;

/**
 * The client's side of playing characters on servers (see {@link CharacterPayloads}): sends the active
 * character when a server asks for it, and keeps the local copy up to date with what the server saves.
 */
public final class ClientCharacterSync {

    private static final Logger LOGGER = LoggerFactory.getLogger(ParallelPlayers.MOD_ID);

    /** How long to wait for the server's last save when leaving before disconnecting anyway. */
    private static final long LEAVE_TIMEOUT_NANOS = 5_000_000_000L;

    /** The character played on the server this client is connected to, or {@code null} for the account. */
    private static volatile @Nullable UUID playingAs;
    /** The disconnect held back until the server's last save arrives; only touched on the client thread. */
    private static @Nullable Runnable pendingLeave;
    private static long leaveDeadline;
    /** Set while the held-back disconnect runs, so it is not held back again. */
    private static boolean leaving;

    private ClientCharacterSync() {
    }

    @SuppressWarnings("resource")
    public static void register() {
        // A new connection starts as the account; a server with the mod says otherwise while connecting.
        ClientConfigurationConnectionEvents.INIT.register((listener, client) -> playingAs = null);
        ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> {
            playingAs = null;
            pendingLeave = null;
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (pendingLeave != null && System.nanoTime() - leaveDeadline > 0) {
                LOGGER.warn("The server did not send the character's last save in time; leaving without it");
                finishLeaving();
            }
        });

        ClientConfigurationNetworking.registerGlobalReceiver(CharacterPayloads.Request.TYPE, (payload, context) ->
                // Received on a network thread; the storage is read on the client thread.
                context.client().execute(() -> context.responseSender().sendPacket(new CharacterPayloads.Upload(activeCharacter()))));
        ClientConfigurationNetworking.registerGlobalReceiver(CharacterPayloads.Result.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    playingAs = payload.playingAs();
                    if (payload.newer() != null) {
                        saveLocally(payload.newer());
                    }
                }));
        ClientPlayNetworking.registerGlobalReceiver(CharacterPayloads.Sync.TYPE, (payload, context) -> saveLocally(payload.character()));
        ClientPlayNetworking.registerGlobalReceiver(CharacterPayloads.LeaveReady.TYPE, (payload, context) -> finishLeaving());
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
        leaveDeadline = System.nanoTime() + LEAVE_TIMEOUT_NANOS;
        ClientPlayNetworking.send(new CharacterPayloads.Leave());
        minecraft.gui.setScreen(new GenericMessageScreen(Component.translatable("parallel.leaving.saving")));
        return true;
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

    /** The character played on the connected server, if it is not singleplayer and one is being played. */
    public static Optional<UUID> playingAs() {
        return Optional.ofNullable(playingAs);
    }

    private static @Nullable CharacterBundle activeCharacter() {
        try {
            return ParallelPlayers.activeCharacter().get().flatMap(ParallelPlayers.characters()::export).orElse(null);
        } catch (RuntimeException e) {
            LOGGER.error("Could not read the active character to send to the server; joining as the account", e);
            return null;
        }
    }

    private static void saveLocally(CharacterBundle character) {
        try {
            ParallelPlayers.characters().importBundle(character, true);
        } catch (RuntimeException e) {
            LOGGER.error("Could not store character {} received from the server", character.meta().id(), e);
        }
    }
}
