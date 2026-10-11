package com.tunic.parallel.network;

import com.tunic.parallel.ParallelPlayers;
import com.tunic.parallel.character.CharacterBundle;
import com.tunic.parallel.character.CharacterMode;
import com.tunic.parallel.character.CharacterSwap;
import com.tunic.parallel.config.ParallelPermissions;
import com.tunic.parallel.config.ServerConfig;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.storage.WorldData;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The server's side of carrying characters to and from clients (see {@link CharacterPayloads}). Players whose
 * client does not have the mod are never asked, and play as their account.
 */
public final class CharacterSync {

    private static final ConfigurationTask.Type TASK = new ConfigurationTask.Type(ParallelPlayers.MOD_ID + ":character");

    /** Players who have been asked for their character and have not answered yet. */
    private static final Set<UUID> ASKED = ConcurrentHashMap.newKeySet();

    private CharacterSync() {
    }

    public static void register() {
        ServerConfigurationConnectionEvents.CONFIGURE.register(CharacterSync::onConfigure);
        ServerConfigurationConnectionEvents.DISCONNECT.register((listener, server) -> ASKED.remove(listener.getOwner().id()));
        ServerConfigurationNetworking.registerGlobalReceiver(CharacterPayloads.Upload.TYPE, CharacterSync::onUpload);
        // Play-phase handlers run on the server thread.
        ServerPlayNetworking.registerGlobalReceiver(CharacterPayloads.Leave.TYPE, (payload, context) -> {
            CharacterSwap.saveBeforeLeaving(context.server(), context.player());
            context.responseSender().sendPacket(new CharacterPayloads.LeaveReady());
        });
    }

    /** Sends a character, as it was just saved, back to the player's client. */
    public static void send(ServerPlayer player, CharacterBundle character) {
        if (ServerPlayNetworking.canSend(player, CharacterPayloads.Sync.TYPE)) {
            ServerPlayNetworking.send(player, new CharacterPayloads.Sync(character));
        }
    }

    /**
     * Adds the step that asks for the player's character. Fabric runs it before vanilla's own steps, so the
     * answer is in before vanilla loads the player's data. The singleplayer owner is skipped: their client's
     * storage is right there.
     */
    private static void onConfigure(ServerConfigurationPacketListenerImpl listener, MinecraftServer server) {
        NameAndId player = new NameAndId(listener.getOwner());
        if (server.isSingleplayerOwner(player) || !ServerConfig.get().charactersEnabled
                || !ServerConfigurationNetworking.canSend(listener, CharacterPayloads.Request.TYPE)) {
            return;
        }
        // Which kind of world a character must be for, so the client can let the player pick one that fits.
        WorldData data = server.getWorldData();
        String worldMode = ServerConfig.get().enforceModes && !ParallelPermissions.bypassesModes(player)
                ? CharacterMode.ofWorld(data.isHardcore(), data.getGameType(), data.isAllowCommands()).id()
                : null;
        listener.addTask(new AskForCharacter(player.id(), worldMode));
    }

    @SuppressWarnings("resource")
    private static void onUpload(CharacterPayloads.Upload payload, ServerConfigurationNetworking.Context context) {
        ServerConfigurationPacketListenerImpl listener = context.packetListener();
        MinecraftServer server = context.server();
        // Received on a network thread; the character is stored on the server thread.
        server.execute(() -> {
            NameAndId player = new NameAndId(listener.getOwner());
            if (!ASKED.remove(player.id())) {
                return;
            }
            CharacterSwap.Choice choice = CharacterSwap.choose(server, player, payload.character());
            context.responseSender().sendPacket(new CharacterPayloads.Result(choice.playingAs(), choice.newer()));
            listener.completeTask(TASK);
        });
    }

    private record AskForCharacter(UUID player, @Nullable String worldMode) implements ConfigurationTask {

        @Override
        public void start(@NonNull Consumer<Packet<?>> connection) {
            ASKED.add(this.player);
            connection.accept(ServerConfigurationNetworking.createClientboundPacket(new CharacterPayloads.Request(this.worldMode)));
        }

        @Override
        public @NonNull Type type() {
            return TASK;
        }
    }
}
