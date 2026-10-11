package com.tunic.parallel.network;

import com.tunic.parallel.ParallelPlayers;
import com.tunic.parallel.character.CharacterBundle;
import com.tunic.parallel.character.CharacterMeta;
import com.tunic.parallel.character.CharacterSkin;
import com.tunic.parallel.character.CharacterMode;
import com.tunic.parallel.character.CharacterSwap;
import com.tunic.parallel.config.ParallelPermissions;
import com.tunic.parallel.config.ServerConfig;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

    private static final Logger LOGGER = LoggerFactory.getLogger(ParallelPlayers.MOD_ID);

    private CharacterSync() {
    }

    public static void register() {
        ServerConfigurationConnectionEvents.CONFIGURE.register(CharacterSync::onConfigure);
        ServerConfigurationConnectionEvents.DISCONNECT.register((listener, server) -> ASKED.remove(listener.getOwner().id()));
        ServerConfigurationNetworking.registerGlobalReceiver(CharacterPayloads.Upload.TYPE, CharacterSync::onUpload);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> shareSkins(server, handler.getPlayer()));
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
     * Shows a player who just joined with their character's skin to everyone else, and everyone else's to them.
     * The newcomer's entry is sent even when they play as their account, replacing anything a client still
     * remembers for them from before.
     */
    private static void shareSkins(MinecraftServer server, ServerPlayer joined) {
        CharacterPayloads.PlayerSkins.Entry theirs = skinOf(joined);
        List<CharacterPayloads.PlayerSkins.Entry> others = new ArrayList<>();
        for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            if (other == joined) {
                continue;
            }
            CharacterPayloads.PlayerSkins.Entry entry = skinOf(other);
            if (entry.skin() != null) {
                others.add(entry);
            }
            if (ServerPlayNetworking.canSend(other, CharacterPayloads.PlayerSkins.TYPE)) {
                ServerPlayNetworking.send(other, new CharacterPayloads.PlayerSkins(List.of(theirs)));
            }
        }
        if (!others.isEmpty() && ServerPlayNetworking.canSend(joined, CharacterPayloads.PlayerSkins.TYPE)) {
            ServerPlayNetworking.send(joined, new CharacterPayloads.PlayerSkins(others));
        }
    }

    /** The skin of the player's character, or an entry without one when they play as their account. */
    private static CharacterPayloads.PlayerSkins.Entry skinOf(ServerPlayer player) {
        UUID id = player.getUUID();
        CharacterSwap.Session session = CharacterSwap.session(player.nameAndId()).orElse(null);
        CharacterMeta meta = session == null ? null : session.meta().orElse(null);
        if (meta == null || meta.skinChoice().isAccount()) {
            return new CharacterPayloads.PlayerSkins.Entry(id, null, null);
        }
        CharacterSkin skin = meta.skinChoice();
        if (!skin.isCustom()) {
            return new CharacterPayloads.PlayerSkins.Entry(id, skin, null);
        }
        try {
            Path file = session.storage().skinFile(meta.id());
            byte[] image = Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
            return image == null || image.length > CharacterBundle.MAX_SKIN_SIZE
                    ? new CharacterPayloads.PlayerSkins.Entry(id, null, null)
                    : new CharacterPayloads.PlayerSkins.Entry(id, skin, image);
        } catch (IOException e) {
            LOGGER.warn("Could not read the skin of {}'s character", player.getPlainTextName(), e);
            return new CharacterPayloads.PlayerSkins.Entry(id, null, null);
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
