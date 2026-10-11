package com.tunic.parallel.character;

import com.tunic.parallel.ParallelPlayers;
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
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Swaps the singleplayer owner's player data with the active character's data.
 *
 * <p>Everything except where the player is travels with the character. Location-like data (position,
 * dimension, respawn point, ...) is remembered per character <em>per world</em> inside the character's
 * data under {@link #LOCATIONS_KEY}, so a character that visits a new world starts at that world's
 * spawn, and returns to where it left off in worlds it has been to before.
 */
public final class CharacterSwap {

    private static final Logger LOGGER = LoggerFactory.getLogger("parallel");

    static final String LOCATIONS_KEY = "parallel_locations";

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
        Optional<UUID> active = ParallelPlayers.activeCharacter().get();
        if (active.isEmpty() || !server.isSingleplayerOwner(player)) {
            return Optional.ofNullable(worldData);
        }
        UUID id = active.get();
        CharacterStore store = ParallelPlayers.characters();
        try {
            store.touch(id);
            // One backup per time a world is entered, not per autosave, so older ones are not rotated out.
            store.backupPlayerData(id);
            Optional<byte[]> bytes = store.readPlayerData(id);
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
        return characterFor(server, player).map(ParallelPlayers.characters()::statsFile);
    }

    /** Same as {@link #statsFile} for advancement progress. */
    public static Optional<Path> advancementsFile(MinecraftServer server, NameAndId player) {
        return characterFor(server, player).map(ParallelPlayers.characters()::advancementsFile);
    }

    private static Optional<UUID> characterFor(MinecraftServer server, NameAndId player) {
        return server.isSingleplayerOwner(player) ? ParallelPlayers.activeCharacter().get() : Optional.empty();
    }

    /**
     * The in-game name for a player: the active character's name when this is the singleplayer owner
     * playing as a character. Commands, scoreboards and the account itself keep using the real name.
     */
    @SuppressWarnings("resource") // the level is owned and closed by the server, never by callers
    public static Optional<String> nickname(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        return characterFor(server, player.nameAndId())
                .flatMap(id -> ParallelPlayers.characters().get(id))
                .map(CharacterMeta::name);
    }

    /** Called after vanilla saved the player into the world; mirrors the data into the character. */
    public static void onSave(MinecraftServer server, ServerPlayer player) {
        Optional<UUID> active = ParallelPlayers.activeCharacter().get();
        if (active.isEmpty() || !server.isSingleplayerOwner(player.nameAndId())) {
            return;
        }
        UUID id = active.get();
        CharacterStore store = ParallelPlayers.characters();
        try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(player.problemPath(), LOGGER)) {
            TagValueOutput output = TagValueOutput.createWithContext(reporter, player.registryAccess());
            player.saveWithoutId(output);
            CompoundTag tag = output.buildResult();

            // Keep the locations remembered for the other worlds and update this world's.
            CompoundTag locations = new CompoundTag();
            Optional<byte[]> previous = store.readPlayerData(id);
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
            store.writePlayerData(id, out.toByteArray());
            store.writeAppearance(id, CharacterLoadout.capture(player));
        } catch (Exception e) {
            LOGGER.error("Failed to save character {}", id, e);
        }
    }

    private static void copyLocation(CompoundTag from, CompoundTag to) {
        for (String key : from.keySet()) {
            Tag value = from.get(key);
            if (value != null && LOCATION_KEYS.contains(key)) {
                to.put(key, value);
            }
        }
    }

    /** Identifies the world by its folder name, which stays the same when the world is renamed. */
    private static String worldId(MinecraftServer server) {
        Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        return root.getFileName().toString();
    }
}
