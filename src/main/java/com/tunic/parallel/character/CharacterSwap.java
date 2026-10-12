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
import java.util.function.LongSupplier;

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
     *
     * @param playingAs the character the player plays as, or {@code null} for the account
     * @param newer     the server's copy, when the client must take it over its own
     * @param saveId    the save client and server agree on from now on
     * @param conflict  whether both copies had changed; the client then keeps its own as a backup
     */
    public record Choice(@Nullable UUID playingAs, @Nullable CharacterBundle newer, long saveId, boolean conflict) {

        public static final Choice ACCOUNT = new Choice(null, null, 0L, false);
    }

    /**
     * The session each player was loaded with, by player id. Decided once, when their data is loaded, so that
     * statistics, advancements and saving all agree for the whole session even if the settings change.
     */
    private static final Map<UUID, Session> PLAYING = new ConcurrentHashMap<>();
    /** Characters chosen by connecting players, by player id, until the player leaves. */
    private static final Map<UUID, Session> CHOSEN = new ConcurrentHashMap<>();
    /**
     * Players whose client already has their character's final save, from the leave handshake. The save
     * vanilla makes as they disconnect is skipped: it would give the server's copy a save the client never
     * receives, and the next join would see both copies as changed.
     */
    private static final Set<UUID> LEFT_SYNCED = ConcurrentHashMap.newKeySet();
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
            LOGGER.error("Failed to load character {}, playing as the account instead", id, e);
            PLAYING.remove(player.id());
            return accountData(server, player, worldData);
        }
    }

    /**
     * Statistics file to use for this player instead of the world's, if a character is in use. Vanilla
     * reads and writes the file itself, so statistics follow the character with no copying.
     */
    @SuppressWarnings("unused")
    public static Optional<Path> statsFile(MinecraftServer server, NameAndId player) {
        return session(player).map(session -> session.storage().statsFile(session.character()));
    }

    /** Same as {@link #statsFile} for advancement progress. */
    @SuppressWarnings("unused")
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
     * Takes the character a connecting player's client sent, before the player's data is loaded, and decides
     * between it and the server's copy without asking the player. Both copies were the same at the save
     * they last synced at; whichever changed since then wins:
     * <ul>
     *   <li>only the client's (it was played elsewhere, or not at all): the client's replaces the server's,</li>
     *   <li>only the server's (the client left or crashed before the last save reached it): the server's is
     *       kept and sent back,</li>
     *   <li>both, or there is no record of syncing with this server: the copy played for longer wins, and the
     *       other is kept as a backup on its own side.</li>
     * </ul>
     * Saves are told apart by random ids, not times, so the clocks of the client and the server do not matter.
     *
     * @param upload the client's active character, or {@code null} to play as the account
     * @param synced the save the client last synced with this server at, or {@code null} if never
     */
    public static Choice choose(MinecraftServer server, NameAndId player, @Nullable CharacterBundle upload, @Nullable Long synced) {
        CHOSEN.remove(player.id());
        if (upload == null || mustPlayAsAccount(player)) {
            return Choice.ACCOUNT;
        }
        UUID id = upload.meta().id();
        CharacterStorage storage = serverStorage(server, player.id());
        try {
            CharacterMeta ours = storage.get(id).orElse(null);
            long theirs = upload.meta().saveId();
            Winner winner = decide(ours == null ? null : ours.saveId(), theirs, synced,
                    () -> storage.playTimeTicks(id), () -> CharacterStorage.playTimeTicks(upload.stats()));
            if (winner == Winner.SERVER) {
                LOGGER.info("{}'s character {} is newer on this server than on their client, sending it back", player.name(), id);
            } else if (winner.conflict) {
                LOGGER.info("{}'s character {} changed both here and on their client; keeping the {} copy, the other is backed up",
                        player.name(), id, winner == Winner.SERVER_CONFLICT ? "server's" : "client's");
            }
            Choice choice;
            if (ours != null && winner.server) {
                storage.importIdentity(upload);
                CharacterBundle newer = storage.export(id, false).orElseThrow(() -> new IllegalStateException("Server copy vanished"));
                choice = new Choice(id, newer, ours.saveId(), winner.conflict);
            } else {
                if (winner.conflict) {
                    storage.keepConflictCopy(id);
                }
                storage.importBundle(upload, false, true);
                choice = new Choice(id, null, theirs, false);
            }
            CHOSEN.put(player.id(), new Session(storage, id, true));
            return choice;
        } catch (RuntimeException e) {
            LOGGER.error("Could not store {}'s character {}, they play as their account", player.name(), id, e);
            return Choice.ACCOUNT;
        }
    }

    /** Which copy of a character {@link #choose} keeps. */
    enum Winner {
        CLIENT(false, false),
        SERVER(true, false),
        CLIENT_CONFLICT(false, true),
        SERVER_CONFLICT(true, true);

        final boolean server;
        /** Both copies had changed, so the losing one is kept as a backup. */
        final boolean conflict;

        Winner(boolean server, boolean conflict) {
            this.server = server;
            this.conflict = conflict;
        }
    }

    /**
     * The decision behind {@link #choose}, on save ids alone.
     *
     * @param ours        the server copy's save, or {@code null} if the server has no copy
     * @param theirs      the client copy's save
     * @param synced      the save the client last synced with this server at, or {@code null} if never
     * @param serverTicks the server copy's play time; only asked for on a conflict
     * @param clientTicks the client copy's play time; only asked for on a conflict
     */
    static Winner decide(@Nullable Long ours, long theirs, @Nullable Long synced, LongSupplier serverTicks, LongSupplier clientTicks) {
        // The same save on both sides is the same copy, whatever the client remembers.
        boolean serverChanged = ours != null && ours != theirs && !ours.equals(synced);
        boolean clientChanged = synced == null || theirs != synced;
        if (!serverChanged) {
            return Winner.CLIENT;
        }
        if (!clientChanged) {
            return Winner.SERVER;
        }
        // Both copies started out the same, so the one with more play time was played more since.
        return serverTicks.getAsLong() >= clientTicks.getAsLong() ? Winner.SERVER_CONFLICT : Winner.CLIENT_CONFLICT;
    }

    /** Forgets a character chosen on an earlier connection that never got as far as joining. */
    public static void forgetChoice(UUID player) {
        CHOSEN.remove(player);
    }

    /** Who the player is playing as on this server, or empty when they play as their account. */
    public static Optional<Session> session(NameAndId player) {
        return Optional.ofNullable(PLAYING.get(player.id()));
    }

    /** Forgets the player's session once they have left and their last save is done. */
    public static void onLeave(ServerPlayer player) {
        PLAYING.remove(player.getUUID());
        CHOSEN.remove(player.getUUID());
        LEFT_SYNCED.remove(player.getUUID());
    }

    /** Forgets everything about the server that is stopping; in singleplayer the next world is another server. */
    public static void onServerStopped() {
        PLAYING.clear();
        CHOSEN.clear();
        LEFT_SYNCED.clear();
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
        if (LEFT_SYNCED.contains(player.getUUID())) {
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
                storage.export(id, false).ifPresent(bundle -> CharacterSync.send(player, bundle));
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
        Session session = PLAYING.get(player.getUUID());
        if (session == null) {
            return;
        }
        saveNow(server, player);
        if (session.remote()) {
            LEFT_SYNCED.add(player.getUUID());
        }
    }

    /**
     * Saves the player's character now, statistics and advancements included, and sends it to their client
     * if they joined from another computer. Nothing happens once their last save before leaving went out.
     */
    public static void saveNow(MinecraftServer server, ServerPlayer player) {
        if (!PLAYING.containsKey(player.getUUID()) || LEFT_SYNCED.contains(player.getUUID())) {
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
    public static synchronized String worldId(MinecraftServer server) {
        String known = worldId;
        if (known != null) {
            return known;
        }
        Path file = modFolder(server).resolve(WORLD_ID_FILE);
        String id;
        try {
            id = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8).strip() : "";
            if (id.isEmpty()) {
                id = UUID.randomUUID().toString();
                Files.createDirectories(file.getParent());
                Files.writeString(file, id, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            LOGGER.error("Could not read or write {}; locations in this world will not be remembered", file, e);
            id = "unknown";
        }
        worldId = id;
        return id;
    }
}
