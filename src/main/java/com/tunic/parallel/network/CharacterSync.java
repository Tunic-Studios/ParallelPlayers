package com.tunic.parallel.network;

import com.tunic.parallel.ParallelPlayers;
import com.tunic.parallel.character.CharacterBundle;
import com.tunic.parallel.character.CharacterMeta;
import com.tunic.parallel.character.CharacterSkin;
import com.tunic.parallel.character.CharacterGamemode;
import com.tunic.parallel.character.CharacterSwap;
import com.tunic.parallel.config.ParallelPermissions;
import com.tunic.parallel.config.ServerConfig;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    /** The character as last sent to each player, so later syncs only need to carry what changed. */
    private static final Map<UUID, CharacterBundle> LAST_SENT = new ConcurrentHashMap<>();

    private static final Logger LOGGER = LoggerFactory.getLogger(ParallelPlayers.MOD_ID);

    private CharacterSync() {
    }

    public static void register() {
        ServerConfigurationConnectionEvents.CONFIGURE.register(CharacterSync::onConfigure);
        ServerConfigurationConnectionEvents.DISCONNECT.register((listener, server) -> ASKED.remove(listener.getOwner().id()));
        ServerConfigurationNetworking.registerGlobalReceiver(CharacterPayloads.Upload.TYPE, CharacterSync::onUpload);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            LAST_SENT.remove(handler.getPlayer().getUUID());
            shareCharacters(server, handler.getPlayer());
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> LAST_SENT.remove(handler.getPlayer().getUUID()));
        ServerTickEvents.END_SERVER_TICK.register(CharacterSync::syncPeriodically);
        // Play-phase handlers run on the server thread.
        ServerPlayNetworking.registerGlobalReceiver(CharacterPayloads.Leave.TYPE, (payload, context) -> {
            CharacterSwap.saveBeforeLeaving(context.server(), context.player());
            context.responseSender().sendPacket(new CharacterPayloads.LeaveReady());
        });
    }

    /**
     * Sends a character, as it was just saved, back to the player's client. After the first time only the
     * parts that changed since are sent; the client keeps its copy of the rest.
     */
    public static void send(ServerPlayer player, CharacterBundle character) {
        if (!ServerPlayNetworking.canSend(player, CharacterPayloads.Sync.TYPE)) {
            return;
        }
        CharacterBundle previous = LAST_SENT.put(player.getUUID(), character);
        ServerPlayNetworking.send(player, new CharacterPayloads.Sync(previous == null ? character : changedSince(previous, character)));
    }

    private static CharacterBundle changedSince(CharacterBundle previous, CharacterBundle current) {
        return new CharacterBundle(current.meta(),
                Arrays.equals(previous.playerData(), current.playerData()) ? null : current.playerData(),
                Arrays.equals(previous.appearance(), current.appearance()) ? null : current.appearance(),
                Objects.equals(previous.stats(), current.stats()) ? null : current.stats(),
                Objects.equals(previous.advancements(), current.advancements()) ? null : current.advancements(),
                null);
    }

    /**
     * Every {@link ServerConfig#syncIntervalSeconds}, saves the characters of players who joined from another
     * computer and sends them over, so a crash or lost connection costs little. The singleplayer owner's
     * character is saved straight into their own storage by the game's saves.
     */
    private static void syncPeriodically(MinecraftServer server) {
        int interval = ServerConfig.get().syncIntervalSeconds;
        if (interval <= 0 || server.getTickCount() % (interval * 20) != 0) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (CharacterSwap.session(player.nameAndId()).filter(CharacterSwap.Session::remote).isPresent()) {
                CharacterSwap.saveNow(server, player);
            }
        }
    }

    /**
     * Tells everyone else who a player who just joined is playing as, and them who everyone else is. The
     * newcomer's entry is sent even when they play as their account, replacing anything a client still
     * remembers for them from before.
     */
    private static void shareCharacters(MinecraftServer server, ServerPlayer joined) {
        CharacterPayloads.PlayerCharacters.Entry theirs = characterOf(joined);
        List<CharacterPayloads.PlayerCharacters.Entry> others = new ArrayList<>();
        for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            if (other == joined) {
                continue;
            }
            CharacterPayloads.PlayerCharacters.Entry entry = characterOf(other);
            if (entry.name() != null) {
                others.add(entry);
            }
            if (ServerPlayNetworking.canSend(other, CharacterPayloads.PlayerCharacters.TYPE)) {
                ServerPlayNetworking.send(other, new CharacterPayloads.PlayerCharacters(List.of(theirs)));
            }
        }
        if (!others.isEmpty() && ServerPlayNetworking.canSend(joined, CharacterPayloads.PlayerCharacters.TYPE)) {
            ServerPlayNetworking.send(joined, new CharacterPayloads.PlayerCharacters(others));
        }
    }

    /** The name and skin of the player's character, or an entry with neither when they play as their account. */
    private static CharacterPayloads.PlayerCharacters.Entry characterOf(ServerPlayer player) {
        UUID id = player.getUUID();
        CharacterSwap.Session session = CharacterSwap.session(player.nameAndId()).orElse(null);
        CharacterMeta meta = session == null ? null : session.meta().orElse(null);
        if (meta == null) {
            return new CharacterPayloads.PlayerCharacters.Entry(id, null, null, null);
        }
        CharacterSkin skin = meta.skinChoice();
        if (skin.isAccount()) {
            return new CharacterPayloads.PlayerCharacters.Entry(id, meta.name(), null, null);
        }
        if (!skin.isCustom()) {
            return new CharacterPayloads.PlayerCharacters.Entry(id, meta.name(), skin, null);
        }
        try {
            Path file = session.storage().skinFile(meta.id());
            byte[] image = Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
            return image == null || image.length > CharacterBundle.MAX_SKIN_SIZE
                    ? new CharacterPayloads.PlayerCharacters.Entry(id, meta.name(), null, null)
                    : new CharacterPayloads.PlayerCharacters.Entry(id, meta.name(), skin, image);
        } catch (IOException e) {
            LOGGER.warn("Could not read the skin of {}'s character", player.getPlainTextName(), e);
            return new CharacterPayloads.PlayerCharacters.Entry(id, meta.name(), null, null);
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
        CharacterGamemode worldGamemode = ServerConfig.get().enforceGamemodes && !ParallelPermissions.bypassesGamemodes(player)
                ? CharacterGamemode.ofWorld(data.isHardcore(), data.getGameType(), data.isAllowCommands())
                : null;
        listener.addTask(new AskForCharacter(player.id(), worldGamemode, CharacterSwap.worldId(server)));
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
            CharacterSwap.Choice choice = CharacterSwap.choose(server, player, payload.character(), payload.synced());
            context.responseSender().sendPacket(new CharacterPayloads.Result(choice.playingAs(), choice.newer(),
                    choice.saveId(), choice.conflict()));
            listener.completeTask(TASK);
        });
    }

    private record AskForCharacter(UUID player, @Nullable CharacterGamemode worldGamemode, String worldId) implements ConfigurationTask {

        @Override
        public void start(@NonNull Consumer<Packet<?>> connection) {
            ASKED.add(this.player);
            connection.accept(ServerConfigurationNetworking.createClientboundPacket(new CharacterPayloads.Request(this.worldGamemode, this.worldId)));
        }

        @Override
        public @NonNull Type type() {
            return TASK;
        }
    }
}
