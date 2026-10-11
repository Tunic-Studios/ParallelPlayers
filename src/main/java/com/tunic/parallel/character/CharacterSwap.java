package com.tunic.parallel.character;

import com.tunic.parallel.ParallelPlayers;
import com.tunic.parallel.config.ParallelPermissions;
import com.tunic.parallel.config.ServerConfig;
import com.tunic.parallel.network.CharacterSync;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueOutput;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Swaps a player's data with the data of the character they play as.
 *
 * <p>The singleplayer owner plays as their active character, straight from the client's own storage. Other
 * players, on a server or in a LAN game, play as the character their client uploaded while connecting (see
 * {@link #choose}); the server keeps its copy of it under {@code <world>/parallel/players/<player>/}, and
 * sends every save back to the client.
 *
 * <p>Everything except where the player is travels with the character. Location-like data (position,
 * dimension, respawn point, ...) is remembered per character <em>per world</em> inside the character's
 * data under {@link #LOCATIONS_KEY}, so a character that visits a new world starts at that world's
 * spawn, and returns to where it left off in worlds it has been to before.
 *
 * <p>Vanilla still saves whoever is playing into the world's own player data, so while a character plays
 * in a world the account's data there is put aside in {@link #ACCOUNTS_DIR} and given back when the
 * account plays that world again.
 */
public final class CharacterSwap {

    private static final Logger LOGGER = LoggerFactory.getLogger("parallel");

    static final String LOCATIONS_KEY = "parallel_locations";
    /** Each player's own data in a world, kept under the world folder while they play there as a character. */
    private static final String ACCOUNTS_DIR = "accounts";
    /** The server's copies of the characters its players have played, one folder per player. */
    private static final String PLAYERS_DIR = "players";
    /** A random id for the world, so locations are remembered per world even across different servers. */
    private static final String WORLD_ID_FILE = "world_id.txt";

    /**
     * Who a player is playing as: a character, and the storage it is kept in.
     *
     * @param remote whether the player is on another machine, so the character's saves are sent back to them
     */
    public record Session(CharacterStorage storage, UUID character, boolean remote) {

        public Optional<CharacterMeta> meta() {
            return this.storage.get(this.character);
        }
    }

    /**
     * The outcome of {@link #choose}: the character the player will play as, if any, and the server's copy of it
     * when that is newer than what the client sent.
     */
    public record Choice(@Nullable UUID playingAs, @Nullable CharacterBundle newer) {

        public static final Choice ACCOUNT = new Choice(null, null);
    }

    /**
     * The session each player was loaded with, by player id. Decided once, when their data is loaded, so that
     * statistics, advancements and saving all agree for the whole session even if the settings change.
     */
    private static final Map<UUID, Session> PLAYING = new ConcurrentHashMap<>();
    /** Characters chosen by connecting players, by player id, until the player leaves. */
    private static final Map<UUID, Session> CHOSEN = new ConcurrentHashMap<>();
    /** The server's character storage for each player, by player id. */
    private static final Map<UUID, CharacterStorage> SERVER_STORAGES = new ConcurrentHashMap<>();
    private static @Nullable String worldId;

    /** Keys of the player's NBT that describe where the player is in a particular world. */
    static final Set<String> LOCATION_KEYS = Set.of(
            "Pos", "Motion", "Rotation", "Dimension", "respawn", "LastDeathLocation", "RootVehicle",
            "entered_nether_pos", "last_explosion_impact_pos", "current_explosion_impact_pos",
            "raid_omen_position", "ender_pearls", "FallDistance", "fall_distance", "OnGround"
    );

    private CharacterSwap() {
    }

    /**
     * Called after vanilla looked up the player's data in the world.
     *
     * @param worldData what vanilla found in the world's own playerdata folder, or {@code null} if nothing
     * @return the data the player should be loaded from
     */
    public static Optional<CompoundTag> onLoad(MinecraftServer server, NameAndId player, @Nullable CompoundTag worldData) {
        Session session = server.isSingleplayerOwner(player) ? ownerSession(player) : CHOSEN.get(player.id());
        if (session == null) {
            PLAYING.remove(player.id());
            return accountData(server, player, worldData);
        }
        PLAYING.put(player.id(), session);
        UUID id = session.character();
        CharacterStorage storage = session.storage();
        try {
            setAccountAside(server, player, worldData);
            storage.touch(id);
            storage.onEnterWorld(id);
            Optional<byte[]> bytes = storage.readPlayerData(id);
            if (bytes.isEmpty()) {
                // A brand new character: start fresh instead of inheriting the world's last player.
                LOGGER.info("Character {} has no saved data yet, starting fresh", id);
                return Optional.empty();
            }
            CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(bytes.get()), NbtAccounter.unlimitedHeap());
            tag = DataFixTypes.PLAYER.updateToCurrentVersion(server.getFixerUpper(), tag, NbtUtils.getDataVersion(tag));

            CompoundTag worldLocation = tag.getCompound(LOCATIONS_KEY)
                    .flatMap(locations -> locations.getCompound(worldId(server)))
                    .orElse(null);
            tag.remove(LOCATIONS_KEY);
            // The player keeps the current account's UUID, whichever account the character was saved with.
            tag.remove("UUID");
            for (String key : LOCATION_KEYS) {
                tag.remove(key);
            }
            if (worldLocation != null) {
                copyLocation(worldLocation, tag);
            }
            // Otherwise the character has never been in this world: it spawns at the world's spawn point.
            return Optional.of(tag);
        } catch (Exception e) {
            LOGGER.error("Failed to load character {}, falling back to the world's own player data", id, e);
            return Optional.ofNullable(worldData);
        }
    }

    /**
     * Statistics file to use for this player instead of the world's, if a character is in use. Vanilla
     * reads and writes the file itself, so statistics follow the character with no copying.
     */
    public static Optional<Path> statsFile(MinecraftServer server, NameAndId player) {
        return session(player).map(session -> session.storage().statsFile(session.character()));
    }

    /** Same as {@link #statsFile} for advancement progress. */
    public static Optional<Path> advancementsFile(MinecraftServer server, NameAndId player) {
        return session(player).map(session -> session.storage().advancementsFile(session.character()));
    }

    /** The singleplayer owner's active character, if this server lets them use it. */
    private static @Nullable Session ownerSession(NameAndId player) {
        Optional<UUID> active = ParallelPlayers.activeCharacter().get();
        if (active.isEmpty() || mustPlayAsAccount(player)) {
            return null;
        }
        return new Session(ParallelPlayers.characters(), active.get(), false);
    }

    /** Whether this server makes the player play as their account: characters are off, or not allowed for them. */
    private static boolean mustPlayAsAccount(NameAndId player) {
        if (!ServerConfig.get().charactersEnabled) {
            return true;
        }
        if (!ParallelPermissions.canUseCharacters(player)) {
            LOGGER.info("{} may not use characters here ({}), playing as the account", player.name(), ParallelPermissions.USE_CHARACTERS);
            return true;
        }
        return false;
    }

    /**
     * Takes the character a connecting player's client sent, before the player's data is loaded. The server's
     * copy is replaced by it, unless the server's copy was saved later: then the client missed the last save
     * (it left or crashed before the save reached it), and the server's copy is kept and sent back.
     *
     * @param upload the client's active character, or {@code null} to play as the account
     */
    public static Choice choose(MinecraftServer server, NameAndId player, @Nullable CharacterBundle upload) {
        CHOSEN.remove(player.id());
        if (upload == null || mustPlayAsAccount(player)) {
            return Choice.ACCOUNT;
        }
        UUID id = upload.meta().id();
        CharacterStorage storage = serverStorage(server, player.id());
        CharacterBundle newer = null;
        try {
            Optional<CharacterMeta> ours = storage.get(id);
            if (ours.isPresent() && ours.get().savedAt() > upload.meta().savedAt()) {
                LOGGER.info("{}'s character {} is newer on this server than on their client, sending it back", player.name(), id);
                storage.importIdentity(upload.meta());
                newer = storage.export(id).orElse(null);
            } else {
                storage.importBundle(upload, false);
            }
        } catch (RuntimeException e) {
            LOGGER.error("Could not store {}'s character {}, they play as their account", player.name(), id, e);
            return Choice.ACCOUNT;
        }
        CHOSEN.put(player.id(), new Session(storage, id, true));
        return new Choice(id, newer);
    }

    /** Who the player is playing as on this server, or empty when they play as their account. */
    public static Optional<Session> session(NameAndId player) {
        return Optional.ofNullable(PLAYING.get(player.id()));
    }

    /** Forgets the player's session once they have left and their last save is done. */
    public static void onLeave(ServerPlayer player) {
        PLAYING.remove(player.getUUID());
        CHOSEN.remove(player.getUUID());
    }

    /** Forgets everything about the server that is stopping; in singleplayer the next world is another server. */
    public static void onServerStopped() {
        PLAYING.clear();
        CHOSEN.clear();
        SERVER_STORAGES.clear();
        worldId = null;
    }

    private static CharacterStorage serverStorage(MinecraftServer server, UUID player) {
        return SERVER_STORAGES.computeIfAbsent(player, id -> new CharacterStorage(
                modFolder(server).resolve(PLAYERS_DIR).resolve(id.toString())));
    }

    /**
     * The in-game name for a player: the character's name when they play as one. Commands, scoreboards and
     * the account itself keep using the real name.
     */
    public static Optional<String> nickname(ServerPlayer player) {
        return session(player.nameAndId())
                .flatMap(Session::meta)
                .map(CharacterMeta::name);
    }

    /** Called after vanilla saved the player into the world; mirrors the data into the character. */
    public static void onSave(MinecraftServer server, ServerPlayer player) {
        Session session = PLAYING.get(player.getUUID());
        if (session == null) {
            // The account's data is back in the world's own save, so the copy put aside is no longer needed.
            Path file = accountFile(server, player.nameAndId());
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                LOGGER.warn("Could not remove {}", file, e);
            }
            return;
        }
        UUID id = session.character();
        CharacterStorage storage = session.storage();
        try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(player.problemPath(), LOGGER)) {
            TagValueOutput output = TagValueOutput.createWithContext(reporter, player.registryAccess());
            player.saveWithoutId(output);
            CompoundTag tag = output.buildResult();

            // Keep the locations remembered for the other worlds and update this world's.
            CompoundTag locations = new CompoundTag();
            Optional<byte[]> previous = storage.readPlayerData(id);
            if (previous.isPresent()) {
                try {
                    CompoundTag old = NbtIo.readCompressed(new ByteArrayInputStream(previous.get()), NbtAccounter.unlimitedHeap());
                    old.getCompound(LOCATIONS_KEY).ifPresent(existing -> existing.keySet().forEach(world -> {
                        Tag location = existing.get(world);
                        if (location != null) {
                            locations.put(world, location);
                        }
                    }));
                } catch (Exception e) {
                    LOGGER.warn("Could not read previous locations of character {}", id, e);
                }
            }
            CompoundTag here = new CompoundTag();
            for (String key : LOCATION_KEYS) {
                Tag value = tag.get(key);
                if (value != null) {
                    here.put(key, value);
                }
            }
            locations.put(worldId(server), here);
            tag.put(LOCATIONS_KEY, locations);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, out);
            storage.beforeSave(id);
            storage.writePlayerData(id, out.toByteArray());
            storage.writeAppearance(id, CharacterLoadout.capture(player));
            storage.markSaved(id);
            if (session.remote()) {
                // Keep the client's copy up to date. The last save, made as the player leaves, cannot reach
                // them any more; the next time they join, the server sees its copy is newer and sends it.
                storage.export(id).ifPresent(bundle -> CharacterSync.send(player, bundle));
            }
        } catch (Exception e) {
            LOGGER.error("Failed to save character {}", id, e);
        }
    }

    /**
     * Saves the player's character right away and, for a remote player, sends it to their client, which is
     * about to disconnect. Statistics and advancements are written first, since they travel with it.
     */
    public static void saveBeforeLeaving(MinecraftServer server, ServerPlayer player) {
        if (!PLAYING.containsKey(player.getUUID())) {
            return;
        }
        server.getPlayerList().getPlayerStats(player).save();
        player.getAdvancements().save();
        onSave(server, player);
    }

    /**
     * Puts the account's data in this world aside before a character is loaded over it. Only the first time:
     * once a character has played here, the world's own data is that character's, not the account's.
     *
     * @param worldData the world's own player data, or {@code null} if the account has never played here
     */
    private static void setAccountAside(MinecraftServer server, NameAndId player, @Nullable CompoundTag worldData) throws IOException {
        Path file = accountFile(server, player);
        if (Files.exists(file)) {
            return;
        }
        Files.createDirectories(file.getParent());
        // An empty tag records that the account has no data here, so it starts fresh rather than as the character.
        NbtIo.writeCompressed(worldData == null ? new CompoundTag() : NbtUtils.addCurrentDataVersion(worldData.copy()), file);
    }

    /** The account's data in this world: what was put aside for it if a character has played here since. */
    private static Optional<CompoundTag> accountData(MinecraftServer server, NameAndId player, @Nullable CompoundTag worldData) {
        Path file = accountFile(server, player);
        if (!Files.isRegularFile(file)) {
            return Optional.ofNullable(worldData);
        }
        try {
            CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            if (tag.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(DataFixTypes.PLAYER.updateToCurrentVersion(server.getFixerUpper(), tag, NbtUtils.getDataVersion(tag)));
        } catch (Exception e) {
            LOGGER.error("Failed to read the account's data from {}, using the world's own player data", file, e);
            return Optional.ofNullable(worldData);
        }
    }

    private static Path accountFile(MinecraftServer server, NameAndId player) {
        return modFolder(server).resolve(ACCOUNTS_DIR).resolve(player.id() + ".dat");
    }

    private static Path modFolder(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(ParallelPlayers.MOD_ID);
    }

    private static void copyLocation(CompoundTag from, CompoundTag to) {
        for (String key : from.keySet()) {
            Tag value = from.get(key);
            if (value != null && LOCATION_KEYS.contains(key)) {
                to.put(key, value);
            }
        }
    }

    /**
     * Identifies the world, for remembering where a character was in it. A random id made the first time it is
     * needed and kept in the world folder: folder names repeat (every server's is "world"), this does not, and
     * it stays the same when the world is renamed.
     */
    private static synchronized String worldId(MinecraftServer server) {
        if (worldId != null) {
            return worldId;
        }
        Path file = modFolder(server).resolve(WORLD_ID_FILE);
        try {
            if (Files.isRegularFile(file)) {
                worldId = Files.readString(file, StandardCharsets.UTF_8).strip();
            }
            if (worldId == null || worldId.isEmpty()) {
                worldId = UUID.randomUUID().toString();
                Files.createDirectories(file.getParent());
                Files.writeString(file, worldId, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            LOGGER.error("Could not read or write {}; locations in this world will not be remembered", file, e);
            worldId = "unknown";
        }
        return worldId;
    }
}
